"""HN-F1 PAS10/PAS12 的离线、无正文、纯整数候选 checker。

这是什么：开发侧研究预检工具，用严格枚举元数据验证 PAS10 计数合同，
并对 PAS12 的 P0/M1/Rtech 粗分区执行精确整数可行性判断。

这不是什么：它不是 MCP 工具、Dashboard 功能、PAS 授权器或 HN-F1 状态机。
它不读取 OB 配置、环境变量、vault、正文、模型输出或网络资源，也不能把数学
结果解释为 PAS 已获授权、S1-A 可开始或 Spark 有效。
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import stat
import sys
import time
from typing import Any, Mapping, Sequence


PAS10_VERSION = "HN-F1-PAS-COUNT-v0.1-DRAFT"
PAS12_VERSION = "HN-F1-PAS-PARTITION-v0.1-DRAFT"
SNAPSHOT_SCHEMA_VERSION = "hnf1-pas-snapshot.v0.1-draft"
COMMITMENT_SCHEMA_VERSION = "hnf1-pas-math-commitment.v0.1-draft"
PRIVATE_RUN_RECORD_SCHEMA_VERSION = "hnf1-pas-private-run-record.v0.1-draft"
AGGREGATE_RECEIPT_SCHEMA_VERSION = "hnf1-pas-aggregate-receipt.v0.1-draft"
ALGORITHM_VERSION = "pareto-dp-three-batch.v2"
PAIR_COMMIT_PROTOCOL = "aggregate-last.v1"
GOVERNANCE_NOTICE = (
    "仅为候选数学核结果；没有评估 PAS 授权，不得据此联系 donor、暴露正文、"
    "启动 S1-A 或宣称 Spark 可行。"
)

BATCH_ORDER = ("P0", "M1", "Rtech")
SOLVER_STATUSES = frozenset({"feasible", "infeasible", "error"})
MATHEMATICAL_OUTCOMES = frozenset(
    {
        "INPUT_OR_IMPLEMENTATION_INVALID",
        "UB_EXACT_INFEASIBLE",
        "LB_AND_UB_EXACT_FEASIBLE",
        "UB_ONLY_EXACT_FEASIBLE",
        "MATHEMATICAL_INCONCLUSIVE",
    }
)
ANALYTIC_REASON_CODES = frozenset(
    {
        "ANALYTIC_DONOR_FLOOR_IMPOSSIBLE",
        "ANALYTIC_TOTAL_CAPACITY_IMPOSSIBLE",
        "ANALYTIC_P0_CAPACITY_IMPOSSIBLE",
        "ANALYTIC_M1_CAPACITY_IMPOSSIBLE",
        "ANALYTIC_RTECH_CAPACITY_IMPOSSIBLE",
    }
)
RESOURCE_REASON_CODES = frozenset(
    {
        "RESOURCE_SECONDS_EXCEEDED",
        "RESOURCE_FRAME_LIMIT_EXCEEDED",
        "RESOURCE_TRANSITION_LIMIT_EXCEEDED",
        "RESOURCE_FRONTIER_LIMIT_EXCEEDED",
        "RESOURCE_MEMORY_EXHAUSTED",
    }
)
IMPLEMENTATION_REASON_CODES = frozenset(
    {
        "WITNESS_CONSTRUCTION_FAILED",
        "INDEPENDENT_WITNESS_VERIFICATION_FAILED",
        "SOLVER_INTERNAL_ERROR",
    }
)
SOLVER_REASON_CODES = frozenset(
    {
        "EXACT_WITNESS_VERIFIED",
        "EXHAUSTIVE_PARETO_FRONTIER_INFEASIBLE",
        *ANALYTIC_REASON_CODES,
        *RESOURCE_REASON_CODES,
        *IMPLEMENTATION_REASON_CODES,
    }
)
DERIVATION_KINDS = frozenset(
    {
        "EXACT_INTEGER_WITNESS",
        "EXACT_ANALYTIC_NECESSARY_CONDITION",
        "EXACT_EXHAUSTIVE_PARETO_FRONTIER",
        "NONE_RESOURCE_INCOMPLETE",
        "NONE_IMPLEMENTATION_ERROR",
    }
)
NODE_TARGET = {"P0": 144, "M1": 576, "Rtech": 48}
HARD_DONOR_FLOOR = {"P0": 15, "M1": 15, "Rtech": 8}
PLANNING_DONOR_FLOOR = {"P0": 18, "M1": 18, "Rtech": 8}
BATCH_CAP = {"P0": 27, "M1": 114, "Rtech": 8}

COUNT_BANDS: dict[str, tuple[int, int]] = {
    "B00_ZERO": (0, 0),
    "B01_ONE": (1, 1),
    "B02_2_3": (2, 3),
    "B03_4_5": (4, 5),
    "B04_6_7": (6, 7),
    "B05_8_11": (8, 11),
    "B06_12_17": (12, 17),
    "B07_18_26": (18, 26),
    "B08_27_31": (27, 31),
    "B09_32_47": (32, 47),
    "B10_48_63": (48, 63),
    "B11_64_95": (64, 95),
    "B12_96_113": (96, 113),
    "B13_114_PLUS": (114, 114),
    "UNKNOWN": (0, 114),
}

COUNT_FIELDS = (
    "eligible_joint_unique_node_count",
    "eligible_intersection_distinct_event_count",
    "eligible_intersection_unique_source_document_count",
    "eligible_intersection_nonoverlap_time_block_count",
)

# recontact_permission 只决定未来能否再次联系，不决定现有材料是否满足容量资格。
# 该选择属于 v0.1 候选机器合同，不能冒充 PAS11/PAS01-PAS14 已获批准。
REQUIRED_ELIGIBILITY_FIELDS = (
    "willing_to_participate",
    "pre_recruitment_material_possible",
    "external_timestamp_evidence_possible",
    "human_primary_authorship_possible",
    "traceable_original_possible",
    "non_high_risk_possible",
    "human_internal_review_permission_intent",
    "deidentification_permission_intent",
)

ROW_FIELDS = frozenset(
    {
        "pas_snapshot_id",
        "screen_donor_id",
        "screen_response_status",
        "willing_to_participate",
        "pre_recruitment_material_possible",
        "external_timestamp_evidence_possible",
        "human_primary_authorship_possible",
        "traceable_original_possible",
        "non_high_risk_possible",
        "human_internal_review_permission_intent",
        "deidentification_permission_intent",
        "recontact_permission",
        "count_response_status",
        "responded_before_cutoff",
        "withdrawal_status",
        "primary_screen_disposition_code",
    }
    | {f"{field}_{endpoint}_band" for field in COUNT_FIELDS for endpoint in ("lower", "upper")}
)

SNAPSHOT_FIELDS = frozenset(
    {
        "schema_version",
        "pas10_version",
        "pas_snapshot_id",
        "codebook_sha256",
        "question_wording_sha256",
        "rows",
    }
)

COMMITMENT_FIELDS = frozenset(
    {
        "schema_version",
        "commitment_id",
        "pas10_version",
        "pas12_version",
        "pas_snapshot_id",
        "snapshot_sha256",
        "frame_member_set_sha256",
        "codebook_sha256",
        "pas10_machine_contract_sha256",
        "question_wording_sha256",
        "expected_implementation_sha256",
        "expected_machine_schema_sha256",
        "algorithm_version",
        "expected_frame_member_count",
        "resource_limits",
    }
)

RESOURCE_LIMIT_FIELDS = frozenset(
    {
        "max_frame_members",
        "max_frontier_entries",
        "max_transitions",
        "max_seconds",
    }
)

ENUMS: dict[str, frozenset[str]] = {
    "screen_response_status": frozenset({"complete", "pending", "incomplete", "declined", "withdrawn"}),
    "willing_to_participate": frozenset({"yes", "no", "pending"}),
    "pre_recruitment_material_possible": frozenset({"yes", "no", "unknown"}),
    "external_timestamp_evidence_possible": frozenset({"yes", "no", "unknown"}),
    "human_primary_authorship_possible": frozenset({"yes", "no", "unknown"}),
    "traceable_original_possible": frozenset({"yes", "no", "unknown"}),
    "non_high_risk_possible": frozenset({"yes", "no", "unknown"}),
    "human_internal_review_permission_intent": frozenset({"yes", "no", "pending"}),
    "deidentification_permission_intent": frozenset({"yes", "no", "pending"}),
    "recontact_permission": frozenset({"yes", "no", "pending"}),
    "count_response_status": frozenset({"none", "partial", "complete"}),
    "responded_before_cutoff": frozenset({"yes", "no"}),
    "withdrawal_status": frozenset({"active", "withdrawn"}),
    "primary_screen_disposition_code": frozenset(
        {"withdrawn", "duplicate", "late", "declined", "explicit_no", "incomplete", "none"}
    ),
}

EXPLICIT_EXCLUSION_CODES = frozenset({"withdrawn", "duplicate", "late", "declined", "explicit_no"})
OPAQUE_ID_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_-]{7,127}$")
HASH_RE = re.compile(r"^[0-9a-f]{64}$")
JSON_BASENAME_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_-]{0,127}\.json$")
MAX_INPUT_BYTES = 32 * 1024 * 1024

# 这些是实现自身的拒绝服务上限，不是研究容量阈值。正式执行仍须在 commitment
# 中冻结更小或相等的资源预算；触发任何上限都只能得到 error/inconclusive。
ABSOLUTE_MAX_FRAME_MEMBERS = 100_000
ABSOLUTE_MAX_FRONTIER_ENTRIES = 5_000_000
ABSOLUTE_MAX_TRANSITIONS = 100_000_000
ABSOLUTE_MAX_SECONDS = 600
FRAME_MEMBER_HASH_DOMAIN = b"hnf1-pas-frame-members.v1\x00"
WITNESS_ORDERING = "sha256(commitment_id|screen_donor_id), deterministic non-normative witness"


class PasCheckerError(Exception):
    """只携带固定错误码，避免把路径、输入值或 donor ID带入日志。"""

    def __init__(self, code: str):
        super().__init__(code)
        self.code = code


class SolverBudgetExceeded(Exception):
    """精确搜索未完成时使用的内部资源中断，不得解释为 infeasible。"""

    def __init__(self, code: str):
        super().__init__(code)
        self.code = code


@dataclass(frozen=True)
class SolverLimits:
    max_frame_members: int
    max_frontier_entries: int
    max_transitions: int
    max_seconds: int


@dataclass(frozen=True)
class PartitionSpec:
    node_target: Mapping[str, int]
    donor_floor: Mapping[str, int]
    batch_cap: Mapping[str, int]


@dataclass(frozen=True)
class NormalizedDonor:
    donor_id: str
    lower: Mapping[str, int]
    upper: Mapping[str, int]
    eligible_lower: bool
    eligible_upper: bool

    def scalar_capacity(self, scenario: str) -> int:
        values = self.lower if scenario == "LB" else self.upper
        eligible = self.eligible_lower if scenario == "LB" else self.eligible_upper
        return min(values.values()) if eligible else 0


@dataclass(frozen=True)
class CapacityDonor:
    donor_id: str
    capacity: int


@dataclass(frozen=True)
class TraceNode:
    previous: TraceNode | None
    donor_id: str
    batch: str


@dataclass(frozen=True)
class FrontierEntry:
    diverted_capacity: int
    trace: TraceNode | None


@dataclass(frozen=True)
class SolverResult:
    status: str
    reason_code: str
    derivation_kind: str
    derivation_sha256: str
    metrics: Mapping[str, int]
    witness: Mapping[str, Any] | None


def _canonical_json_bytes(value: Any) -> bytes:
    return json.dumps(value, ensure_ascii=True, separators=(",", ":"), sort_keys=True).encode("utf-8")


def _sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def frame_member_set_sha256(donor_ids: Sequence[str]) -> str:
    if len(set(donor_ids)) != len(donor_ids):
        raise PasCheckerError("FRAME_MEMBER_SET_DUPLICATE_DONOR")
    hasher = hashlib.sha256()
    hasher.update(FRAME_MEMBER_HASH_DOMAIN)
    for donor_id in sorted(donor_ids):
        _require_opaque_id(donor_id, "FRAME_MEMBER_SET_DONOR_ID_INVALID")
        hasher.update(donor_id.encode("ascii"))
        hasher.update(b"\n")
    return hasher.hexdigest()


CODEBOOK_CONTRACT = {
    "effective_cap": 114,
    "bands": {key: list(value) for key, value in COUNT_BANDS.items()},
    "count_fields": list(COUNT_FIELDS),
    "version": PAS10_VERSION,
}
CODEBOOK_SHA256 = _sha256_bytes(_canonical_json_bytes(CODEBOOK_CONTRACT))

PAS10_MACHINE_CONTRACT = {
    "version": PAS10_VERSION,
    "codebook_sha256": CODEBOOK_SHA256,
    "schema_versions": {"snapshot": SNAPSHOT_SCHEMA_VERSION},
    "top_level_fields": sorted(SNAPSHOT_FIELDS),
    "row_fields": sorted(ROW_FIELDS),
    "field_enums": {key: sorted(values) for key, values in sorted(ENUMS.items())},
    "count_fields": list(COUNT_FIELDS),
    "band_pair_rule": "LB=lower_band.lower_endpoint;UB=upper_band.upper_endpoint;LB<=UB",
    "unknown_nonresponse_rule": "not_explicitly_excluded=>LB0_UB114",
    "count_response_rule": {
        "none": "all_count_band_pairs_must_be_UNKNOWN",
        "partial": "at_least_one_UNKNOWN_pair_and_one_non_UNKNOWN_pair",
        "complete": "all_fields_answered;explicit_UNKNOWN_is_allowed",
        "pending_response": "count_response_status_none_required",
    },
    "explicit_exclusion_codes": sorted(EXPLICIT_EXCLUSION_CODES),
    "required_eligibility_fields": list(REQUIRED_ELIGIBILITY_FIELDS),
    "recontact_is_capacity_eligibility": False,
    "lower_eligibility_rule": "complete_before_cutoff_active_none_and_all_required_yes",
    "upper_eligibility_rule": "not_explicitly_excluded_and_joint_UB_at_least_1",
    "disposition_priority": [
        "withdrawn",
        "duplicate",
        "late",
        "declined",
        "explicit_no",
        "incomplete",
        "none",
    ],
    "disposition_derivation": {
        "withdrawn": "withdrawal_status_withdrawn_or_response_withdrawn",
        "duplicate": "identity_controller_supplied_code_if_not_withdrawn",
        "late": "complete_or_declined_response_after_cutoff",
        "declined": "response_declined_or_willing_no",
        "explicit_no": "any_required_eligibility_no",
        "incomplete": "response_pending_or_incomplete",
        "none": "all_other_valid_combinations",
    },
    "joint_margin_rule": "normalized_LB_le_each_margin_LB_and_normalized_UB_le_each_margin_UB",
    "opaque_id_pattern": OPAQUE_ID_RE.pattern,
    "sha256_pattern": HASH_RE.pattern,
    "additional_properties": False,
    "duplicate_json_keys": "reject",
    "nonfinite_numbers": "reject",
    "utf8_bom": "reject",
}
PAS10_MACHINE_CONTRACT_SHA256 = _sha256_bytes(_canonical_json_bytes(PAS10_MACHINE_CONTRACT))

OUTPUT_SCHEMA_CONTRACT = {
    "version": "hnf1-pas-output-schema-contract.v0.1-draft",
    "literals": {
        "contract_status": "CANDIDATE_MATH_KERNEL_NOT_FROZEN",
        "pas13_review_status": "NOT_PERFORMED",
        "private_scope": "ACL_PRIVATE_MATHEMATICAL_RUN_RECORD",
        "aggregate_scope": "ACL_ONLY_NOT_PAS13_PUBLIC",
        "pair_commit_protocol": PAIR_COMMIT_PROTOCOL,
        "governance_notice": GOVERNANCE_NOTICE,
        "pas10_version": PAS10_VERSION,
        "pas12_version": PAS12_VERSION,
        "algorithm_version": ALGORITHM_VERSION,
        "witness_ordering": WITNESS_ORDERING,
    },
    "enums": {
        "solver_statuses": sorted(SOLVER_STATUSES),
        "solver_reason_codes": sorted(SOLVER_REASON_CODES),
        "derivation_kinds": sorted(DERIVATION_KINDS),
        "mathematical_outcomes": sorted(MATHEMATICAL_OUTCOMES),
        "batch_order": list(BATCH_ORDER),
    },
    "common": {
        "additional_properties": False,
        "exact_fields": sorted(
            {
                "contract_status",
                "authorization_evaluated",
                "pas_state_computed",
                "pas13_review_status",
                "public_release_authorized",
                "pas10_version",
                "pas12_version",
                "algorithm_version",
                "commitment_id",
                "pas_snapshot_id",
                "commitment_sha256",
                "snapshot_sha256",
                "frame_member_set_sha256",
                "implementation_sha256",
                "machine_schema_sha256",
                "output_schema_sha256",
                "codebook_sha256",
                "pas10_machine_contract_sha256",
                "question_wording_sha256",
                "frame_member_count",
                "normalization_summary",
                "mathematical_outcome",
                "governance_notice",
            }
        ),
        "field_types": {
            "algorithm_version": "literal_ref:algorithm_version",
            "authorization_evaluated": "boolean_false",
            "codebook_sha256": "sha256_string",
            "commitment_id": "opaque_ascii_id_string",
            "commitment_sha256": "sha256_string",
            "contract_status": "literal_ref:contract_status",
            "frame_member_count": "nonnegative_integer_not_boolean",
            "frame_member_set_sha256": "sha256_string",
            "governance_notice": "literal_ref:governance_notice",
            "implementation_sha256": "sha256_string",
            "machine_schema_sha256": "sha256_string",
            "output_schema_sha256": "sha256_string",
            "mathematical_outcome": "mathematical_outcomes_enum",
            "normalization_summary": "normalization_summary_object",
            "pas10_machine_contract_sha256": "sha256_string",
            "pas10_version": "literal_ref:pas10_version",
            "pas12_version": "literal_ref:pas12_version",
            "pas13_review_status": "string_NOT_PERFORMED",
            "pas_snapshot_id": "opaque_ascii_id_string",
            "pas_state_computed": "boolean_false",
            "public_release_authorized": "boolean_false",
            "question_wording_sha256": "sha256_string",
            "snapshot_sha256": "sha256_string",
        },
    },
    "normalization_summary": {
        "additional_properties": False,
        "exact_fields": ["LB", "UB"],
        "scenario_exact_fields": ["eligible_donor_count", "totals"],
        "totals_exact_fields": list(COUNT_FIELDS),
        "leaf_type": "nonnegative_integer_not_boolean",
    },
    "scenario_result": {
        "additional_properties": False,
        "exact_fields": [
            "status",
            "reason_code",
            "derivation_kind",
            "derivation_sha256",
            "metrics",
            "batch_summary",
        ],
        "field_types": {
            "status": "solver_statuses_enum",
            "reason_code": "solver_reason_codes_enum",
            "derivation_kind": "derivation_kinds_enum",
            "derivation_sha256": "empty_or_sha256_string",
            "metrics": "metrics_object",
            "batch_summary": "null_or_batch_summary_object",
        },
    },
    "metrics": {
        "required_fields": ["processed_donors", "transitions", "peak_frontier_entries"],
        "optional_fields": ["available_donors", "terminal_frontier_entries"],
        "additional_properties": False,
        "leaf_type": "nonnegative_integer_not_boolean",
    },
    "batch_summary": {
        "additional_properties": False,
        "exact_fields": list(BATCH_ORDER),
        "batch_exact_fields": ["assigned_donor_count", "allocated_nodes"],
        "leaf_type": "nonnegative_integer_not_boolean",
    },
    "witness": {
        "additional_properties": False,
        "exact_fields": ["ordering", "commitment_id", "assignments", "batch_summary"],
        "assignment_exact_fields": ["screen_donor_id", "batch", "allocated_nodes"],
        "assignment_types": {
            "screen_donor_id": "opaque_ascii_id_string",
            "batch": "batch_order_enum_string",
            "allocated_nodes": "positive_integer_not_boolean",
        },
        "field_types": {
            "ordering": "literal_ref:witness_ordering",
            "commitment_id": "opaque_ascii_id_string",
            "assignments": "assignment_object_array",
            "batch_summary": "batch_summary_object",
        },
    },
    "private_run_record": {
        "additional_properties": False,
        "exact_fields": sorted(
            {
                "schema_version",
                "scope",
                "scenario_results",
                *{
                    "contract_status",
                    "authorization_evaluated",
                    "pas_state_computed",
                    "pas13_review_status",
                    "public_release_authorized",
                    "pas10_version",
                    "pas12_version",
                    "algorithm_version",
                    "commitment_id",
                    "pas_snapshot_id",
                    "commitment_sha256",
                    "snapshot_sha256",
                    "frame_member_set_sha256",
                    "implementation_sha256",
                    "machine_schema_sha256",
                    "output_schema_sha256",
                    "codebook_sha256",
                    "pas10_machine_contract_sha256",
                    "question_wording_sha256",
                    "frame_member_count",
                    "normalization_summary",
                    "mathematical_outcome",
                    "governance_notice",
                },
            }
        ),
        "field_types": {
            "schema_version": f"literal:{PRIVATE_RUN_RECORD_SCHEMA_VERSION}",
            "scope": "literal_ref:private_scope",
            "scenario_results": "exact_LB_UB_scenario_result_plus_witness_object",
        },
        "scenario_exact_fields": ["LB", "UB"],
        "scenario_value": "scenario_result_plus_witness_null_or_witness_object",
    },
    "aggregate_receipt": {
        "additional_properties": False,
        "exact_fields": sorted(
            {
                "schema_version",
                "scope",
                "private_run_record_canonical_sha256",
                "scenario_results",
                "contains_direct_donor_id_fields",
                "pas13_public_projection_created",
                "pair_commit_protocol",
                "pair_complete",
                *{
                    "contract_status",
                    "authorization_evaluated",
                    "pas_state_computed",
                    "pas13_review_status",
                    "public_release_authorized",
                    "pas10_version",
                    "pas12_version",
                    "algorithm_version",
                    "commitment_id",
                    "pas_snapshot_id",
                    "commitment_sha256",
                    "snapshot_sha256",
                    "frame_member_set_sha256",
                    "implementation_sha256",
                    "machine_schema_sha256",
                    "output_schema_sha256",
                    "codebook_sha256",
                    "pas10_machine_contract_sha256",
                    "question_wording_sha256",
                    "frame_member_count",
                    "normalization_summary",
                    "mathematical_outcome",
                    "governance_notice",
                },
            }
        ),
        "field_types": {
            "schema_version": f"literal:{AGGREGATE_RECEIPT_SCHEMA_VERSION}",
            "scope": "literal_ref:aggregate_scope",
            "private_run_record_canonical_sha256": "sha256_string",
            "scenario_results": "exact_LB_UB_scenario_result_object",
            "contains_direct_donor_id_fields": "boolean_false",
            "pas13_public_projection_created": "boolean_false",
            "pair_commit_protocol": "literal_ref:pair_commit_protocol",
            "pair_complete": "boolean_true",
        },
        "scenario_exact_fields": ["LB", "UB"],
        "scenario_value": "scenario_result_without_witness",
        "pair_commit_protocol": PAIR_COMMIT_PROTOCOL,
        "pair_complete_type": "boolean_true",
    },
}
OUTPUT_SCHEMA_SHA256 = _sha256_bytes(_canonical_json_bytes(OUTPUT_SCHEMA_CONTRACT))

MACHINE_CONTRACT = {
    "algorithm_version": ALGORITHM_VERSION,
    "schema_versions": {
        "snapshot": SNAPSHOT_SCHEMA_VERSION,
        "commitment": COMMITMENT_SCHEMA_VERSION,
        "private_run_record": PRIVATE_RUN_RECORD_SCHEMA_VERSION,
        "aggregate_receipt": AGGREGATE_RECEIPT_SCHEMA_VERSION,
    },
    "batch_cap": BATCH_CAP,
    "batch_order": list(BATCH_ORDER),
    "canonical_json": "utf8_sorted_keys_compact_separators_ascii_escaped",
    "codebook_sha256": CODEBOOK_SHA256,
    "pas10_machine_contract_sha256": PAS10_MACHINE_CONTRACT_SHA256,
    "output_schema_sha256": OUTPUT_SCHEMA_SHA256,
    "commitment_fields": sorted(COMMITMENT_FIELDS),
    "resource_limit_fields": sorted(RESOURCE_LIMIT_FIELDS),
    "resource_absolute_maxima": {
        "max_frame_members": ABSOLUTE_MAX_FRAME_MEMBERS,
        "max_frontier_entries": ABSOLUTE_MAX_FRONTIER_ENTRIES,
        "max_transitions": ABSOLUTE_MAX_TRANSITIONS,
        "max_seconds": ABSOLUTE_MAX_SECONDS,
    },
    "resource_failure_result": "error_not_infeasible",
    "deadline_semantics": (
        "shared_cooperative_monotonic_fail_closed_budget_for_normalization_and_both_scenarios;"
        "external_process_watchdog_required_for_hard_wall_clock_limit"
    ),
    "frame_member_set_hash": "sha256(domain||concat(sorted_ascii_id||0x0a))",
    "frame_member_set_hash_domain_hex": FRAME_MEMBER_HASH_DOMAIN.hex(),
    "hard_donor_floor": HARD_DONOR_FLOOR,
    "node_target": NODE_TARGET,
    "pas10_version": PAS10_VERSION,
    "pas12_version": PAS12_VERSION,
    "planning_donor_floor": PLANNING_DONOR_FLOOR,
    "solver_variables": "donor_exclusive_batch_and_positive_integer_nodes",
    "solver_completion_rule": "only_complete_analytic_or_exhaustive_derivation_may_be_infeasible",
    "scenario_result_statuses": ["feasible", "infeasible", "error"],
    "mathematical_truth_table": {
        "LB_feasible_UB_infeasible": "INPUT_OR_IMPLEMENTATION_INVALID",
        "UB_infeasible": "UB_EXACT_INFEASIBLE",
        "LB_feasible_UB_feasible": "LB_AND_UB_EXACT_FEASIBLE",
        "LB_infeasible_UB_feasible": "UB_ONLY_EXACT_FEASIBLE",
        "any_error": "MATHEMATICAL_INCONCLUSIVE",
    },
    "witness_ordering": WITNESS_ORDERING,
    "witness_role": "non_normative_reproducible_exact_verification_record_status_independent",
    "witness_fields": ["ordering", "commitment_id", "assignments", "batch_summary"],
    "assignment_fields": ["screen_donor_id", "batch", "allocated_nodes"],
    "output_schema_contract": OUTPUT_SCHEMA_CONTRACT,
    "output_acceptance_rule": (
        "aggregate_is_last_published_commit_marker;missing_aggregate_means_uncommitted_partial;"
        "consumer_must_validate_both_schemas_common_fields_and_private_canonical_hash"
    ),
    "output_uncertainty_rule": (
        "any_OUTPUT_error_code_containing_STATE_UNCERTAIN_requires_quarantine_of_entire_restricted_root"
    ),
    "execution_environment_preconditions": [
        "external_admin_attests_acl_owner_and_restricted_root_before_run",
        "trusted_single_writer_and_no_untrusted_concurrent_parent_or_file_mutation",
        "read_only_implementation_and_input_mounts",
        "external_hard_wall_clock_memory_cpu_watchdog",
        "network_disabled_and_no_vault_config_or_secret_mounts",
        "operator_quarantines_uncommitted_private_only_or_uncertain_output_pair",
    ],
    "path_and_mode_controls_role": "accident_guard_not_acl_owner_or_immutability_attestation",
    "pair_commit_durability_boundary": (
        "logical_aggregate_last_protocol_not_cross_file_transaction_directory_fsync_or_concurrent_read_snapshot"
    ),
    "output_structure_validator_boundary": (
        "schema_and_intra_pair_consistency_only;not_against_input_math_result_replay;"
        "consumer_must_rerun_frozen_checker_with_original_commitment_and_snapshot"
    ),
    "cli_result_disclosure": "stdout_and_exit_code_do_not_encode_mathematical_outcome;0=committed_pair;2=error",
    "private_output_scope": "ACL_PRIVATE_MATHEMATICAL_RUN_RECORD",
    "aggregate_output_scope": "ACL_ONLY_NOT_PAS13_PUBLIC",
    "authorization_evaluated": False,
    "pas_state_computed": False,
}
MACHINE_SCHEMA_SHA256 = _sha256_bytes(_canonical_json_bytes(MACHINE_CONTRACT))


def _require_exact_keys(value: Mapping[str, Any], expected: frozenset[str], code: str) -> None:
    if set(value) != expected:
        raise PasCheckerError(code)


def _require_mapping(value: Any, code: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise PasCheckerError(code)
    return value


def _require_list(value: Any, code: str) -> list[Any]:
    if not isinstance(value, list):
        raise PasCheckerError(code)
    return value


def _require_string(value: Any, code: str) -> str:
    if not isinstance(value, str):
        raise PasCheckerError(code)
    return value


def _require_integer(value: Any, code: str, *, minimum: int, maximum: int) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or not minimum <= value <= maximum:
        raise PasCheckerError(code)
    return value


def _require_opaque_id(value: Any, code: str) -> str:
    text = _require_string(value, code)
    if not OPAQUE_ID_RE.fullmatch(text):
        raise PasCheckerError(code)
    return text


def _require_hash(value: Any, code: str) -> str:
    text = _require_string(value, code)
    if not HASH_RE.fullmatch(text):
        raise PasCheckerError(code)
    return text


def _reject_json_constant(_value: str) -> None:
    raise PasCheckerError("JSON_NON_FINITE_NUMBER")


def _pairs_without_duplicates(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise PasCheckerError("JSON_DUPLICATE_KEY")
        result[key] = value
    return result


def strict_json_loads(data: bytes) -> dict[str, Any]:
    if len(data) > MAX_INPUT_BYTES:
        raise PasCheckerError("INPUT_TOO_LARGE")
    try:
        text = data.decode("utf-8", errors="strict")
    except UnicodeDecodeError as exc:
        raise PasCheckerError("INPUT_NOT_UTF8") from exc
    if text.startswith("\ufeff"):
        raise PasCheckerError("INPUT_UTF8_BOM_FORBIDDEN")
    try:
        value = json.loads(
            text,
            object_pairs_hook=_pairs_without_duplicates,
            parse_constant=_reject_json_constant,
        )
    except PasCheckerError:
        raise
    except (json.JSONDecodeError, RecursionError, ValueError) as exc:
        raise PasCheckerError("INPUT_INVALID_JSON") from exc
    return _require_mapping(value, "INPUT_TOP_LEVEL_NOT_OBJECT")


def _validate_enum(row: Mapping[str, Any], field: str) -> str:
    value = _require_string(row[field], "ROW_ENUM_TYPE_INVALID")
    if value not in ENUMS[field]:
        raise PasCheckerError("ROW_ENUM_VALUE_INVALID")
    return value


def _expected_disposition(row: Mapping[str, Any]) -> str:
    response_status = row["screen_response_status"]
    disposition = row["primary_screen_disposition_code"]
    if row["withdrawal_status"] == "withdrawn" or response_status == "withdrawn":
        return "withdrawn"
    # duplicate 只能由窄 ACL 的身份台账机械给出，本表没有第二个身份字段可推导。
    if disposition == "duplicate":
        return "duplicate"
    if response_status in {"complete", "declined"} and row["responded_before_cutoff"] == "no":
        return "late"
    if response_status == "declined" or row["willing_to_participate"] == "no":
        return "declined"
    if any(row[field] == "no" for field in REQUIRED_ELIGIBILITY_FIELDS):
        return "explicit_no"
    if response_status in {"pending", "incomplete"}:
        return "incomplete"
    return "none"


def _raw_count_bounds(row: Mapping[str, Any]) -> dict[str, tuple[int, int]]:
    result: dict[str, tuple[int, int]] = {}
    for field in COUNT_FIELDS:
        lower_band = _require_string(row[f"{field}_lower_band"], "COUNT_BAND_TYPE_INVALID")
        upper_band = _require_string(row[f"{field}_upper_band"], "COUNT_BAND_TYPE_INVALID")
        if lower_band not in COUNT_BANDS or upper_band not in COUNT_BANDS:
            raise PasCheckerError("COUNT_BAND_VALUE_INVALID")
        lower = COUNT_BANDS[lower_band][0]
        upper = COUNT_BANDS[upper_band][1]
        if lower > upper:
            raise PasCheckerError("COUNT_BAND_INTERVAL_INVALID")
        result[field] = (lower, upper)
    return result


def _validate_count_response_status(row: Mapping[str, Any]) -> None:
    band_values = [
        row[f"{field}_{endpoint}_band"]
        for field in COUNT_FIELDS
        for endpoint in ("lower", "upper")
    ]
    response_status = row["screen_response_status"]
    count_status = row["count_response_status"]
    if response_status == "pending" and count_status != "none":
        raise PasCheckerError("PENDING_COUNT_RESPONSE_STATUS_INVALID")
    if response_status == "complete" and count_status != "complete":
        raise PasCheckerError("COMPLETE_COUNT_RESPONSE_STATUS_INVALID")
    if count_status == "none" and any(band_value != "UNKNOWN" for band_value in band_values):
        raise PasCheckerError("COUNT_NONRESPONSE_MUST_BE_UNKNOWN")
    if count_status == "partial" and not (
        any(band_value == "UNKNOWN" for band_value in band_values)
        and any(band_value != "UNKNOWN" for band_value in band_values)
    ):
        raise PasCheckerError("COUNT_PARTIAL_RESPONSE_INVALID")


def normalize_row(row_value: Any, snapshot_id: str) -> NormalizedDonor:
    row = _require_mapping(row_value, "ROW_NOT_OBJECT")
    _require_exact_keys(row, ROW_FIELDS, "ROW_FIELDS_INVALID")
    row_snapshot_id = _require_opaque_id(row["pas_snapshot_id"], "ROW_SNAPSHOT_ID_INVALID")
    if row_snapshot_id != snapshot_id:
        raise PasCheckerError("ROW_SNAPSHOT_ID_MISMATCH")
    donor_id = _require_opaque_id(row["screen_donor_id"], "ROW_DONOR_ID_INVALID")
    for field in ENUMS:
        _validate_enum(row, field)
    _validate_count_response_status(row)
    raw_bounds = _raw_count_bounds(row)
    if row["primary_screen_disposition_code"] != _expected_disposition(row):
        raise PasCheckerError("ROW_DISPOSITION_INCONSISTENT")

    explicitly_excluded = (
        row["primary_screen_disposition_code"] in EXPLICIT_EXCLUSION_CODES
        or any(row[field] == "no" for field in REQUIRED_ELIGIBILITY_FIELDS)
    )
    fully_lower_eligible = (
        not explicitly_excluded
        and row["screen_response_status"] == "complete"
        and row["count_response_status"] == "complete"
        and row["responded_before_cutoff"] == "yes"
        and row["withdrawal_status"] == "active"
        and row["primary_screen_disposition_code"] == "none"
        and all(row[field] == "yes" for field in REQUIRED_ELIGIBILITY_FIELDS)
    )

    if explicitly_excluded:
        lower = {field: 0 for field in COUNT_FIELDS}
        upper = {field: 0 for field in COUNT_FIELDS}
    else:
        lower = {field: bounds[0] if fully_lower_eligible else 0 for field, bounds in raw_bounds.items()}
        upper = {field: bounds[1] for field, bounds in raw_bounds.items()}

    joint = "eligible_joint_unique_node_count"
    for margin in COUNT_FIELDS[1:]:
        if lower[joint] > lower[margin] or upper[joint] > upper[margin]:
            raise PasCheckerError("JOINT_BOUND_EXCEEDS_MARGIN")

    eligible_lower = fully_lower_eligible and lower[joint] >= 1
    eligible_upper = not explicitly_excluded and upper[joint] >= 1
    if eligible_lower and not eligible_upper:
        raise PasCheckerError("ELIGIBILITY_MONOTONICITY_INVALID")
    return NormalizedDonor(
        donor_id=donor_id,
        lower=lower,
        upper=upper,
        eligible_lower=eligible_lower,
        eligible_upper=eligible_upper,
    )


def validate_and_normalize_snapshot(
    snapshot: Mapping[str, Any],
    commitment: Mapping[str, Any],
) -> list[NormalizedDonor]:
    _require_exact_keys(snapshot, SNAPSHOT_FIELDS, "SNAPSHOT_FIELDS_INVALID")
    if snapshot["schema_version"] != SNAPSHOT_SCHEMA_VERSION:
        raise PasCheckerError("SNAPSHOT_SCHEMA_VERSION_INVALID")
    if snapshot["pas10_version"] != PAS10_VERSION:
        raise PasCheckerError("SNAPSHOT_PAS10_VERSION_INVALID")
    snapshot_id = _require_opaque_id(snapshot["pas_snapshot_id"], "SNAPSHOT_ID_INVALID")
    if snapshot_id != commitment["pas_snapshot_id"]:
        raise PasCheckerError("SNAPSHOT_COMMITMENT_ID_MISMATCH")
    if _require_hash(snapshot["codebook_sha256"], "SNAPSHOT_CODEBOOK_HASH_INVALID") != CODEBOOK_SHA256:
        raise PasCheckerError("SNAPSHOT_CODEBOOK_HASH_MISMATCH")
    if snapshot["codebook_sha256"] != commitment["codebook_sha256"]:
        raise PasCheckerError("SNAPSHOT_COMMITMENT_CODEBOOK_MISMATCH")
    wording_hash = _require_hash(snapshot["question_wording_sha256"], "SNAPSHOT_WORDING_HASH_INVALID")
    if wording_hash != commitment["question_wording_sha256"]:
        raise PasCheckerError("SNAPSHOT_COMMITMENT_WORDING_MISMATCH")

    rows = _require_list(snapshot["rows"], "SNAPSHOT_ROWS_NOT_ARRAY")
    expected_count = commitment["expected_frame_member_count"]
    if len(rows) != expected_count:
        raise PasCheckerError("SNAPSHOT_FRAME_COUNT_MISMATCH")
    if len(rows) > commitment["resource_limits"].max_frame_members:
        raise PasCheckerError("SNAPSHOT_FRAME_LIMIT_EXCEEDED")

    donors: list[NormalizedDonor] = []
    seen: set[str] = set()
    for row in rows:
        donor = normalize_row(row, snapshot_id)
        if donor.donor_id in seen:
            raise PasCheckerError("SNAPSHOT_DUPLICATE_DONOR")
        seen.add(donor.donor_id)
        donors.append(donor)
    if frame_member_set_sha256([donor.donor_id for donor in donors]) != commitment["frame_member_set_sha256"]:
        raise PasCheckerError("SNAPSHOT_FRAME_MEMBER_SET_HASH_MISMATCH")
    return donors


def validate_commitment(value: Mapping[str, Any], *, implementation_sha256: str) -> dict[str, Any]:
    _require_exact_keys(value, COMMITMENT_FIELDS, "COMMITMENT_FIELDS_INVALID")
    if value["schema_version"] != COMMITMENT_SCHEMA_VERSION:
        raise PasCheckerError("COMMITMENT_SCHEMA_VERSION_INVALID")
    if value["pas10_version"] != PAS10_VERSION or value["pas12_version"] != PAS12_VERSION:
        raise PasCheckerError("COMMITMENT_CONTRACT_VERSION_INVALID")
    commitment_id = _require_opaque_id(value["commitment_id"], "COMMITMENT_ID_INVALID")
    snapshot_id = _require_opaque_id(value["pas_snapshot_id"], "COMMITMENT_SNAPSHOT_ID_INVALID")
    snapshot_sha256 = _require_hash(value["snapshot_sha256"], "COMMITMENT_SNAPSHOT_HASH_INVALID")
    frame_set_hash = _require_hash(value["frame_member_set_sha256"], "COMMITMENT_FRAME_SET_HASH_INVALID")
    codebook_sha256 = _require_hash(value["codebook_sha256"], "COMMITMENT_CODEBOOK_HASH_INVALID")
    if codebook_sha256 != CODEBOOK_SHA256:
        raise PasCheckerError("COMMITMENT_CODEBOOK_HASH_MISMATCH")
    pas10_contract_hash = _require_hash(
        value["pas10_machine_contract_sha256"], "COMMITMENT_PAS10_CONTRACT_HASH_INVALID"
    )
    if pas10_contract_hash != PAS10_MACHINE_CONTRACT_SHA256:
        raise PasCheckerError("COMMITMENT_PAS10_CONTRACT_HASH_MISMATCH")
    expected_implementation_hash = _require_hash(
        value["expected_implementation_sha256"], "COMMITMENT_IMPLEMENTATION_HASH_INVALID"
    )
    if expected_implementation_hash != implementation_sha256:
        raise PasCheckerError("COMMITMENT_IMPLEMENTATION_HASH_MISMATCH")
    expected_schema_hash = _require_hash(
        value["expected_machine_schema_sha256"], "COMMITMENT_MACHINE_SCHEMA_HASH_INVALID"
    )
    if expected_schema_hash != MACHINE_SCHEMA_SHA256:
        raise PasCheckerError("COMMITMENT_MACHINE_SCHEMA_HASH_MISMATCH")
    algorithm_version = _require_string(value["algorithm_version"], "COMMITMENT_ALGORITHM_VERSION_INVALID")
    if algorithm_version != ALGORITHM_VERSION:
        raise PasCheckerError("COMMITMENT_ALGORITHM_VERSION_MISMATCH")
    wording_hash = _require_hash(value["question_wording_sha256"], "COMMITMENT_WORDING_HASH_INVALID")
    expected_count = _require_integer(
        value["expected_frame_member_count"],
        "COMMITMENT_FRAME_COUNT_INVALID",
        minimum=0,
        maximum=ABSOLUTE_MAX_FRAME_MEMBERS,
    )

    raw_limits = _require_mapping(value["resource_limits"], "RESOURCE_LIMITS_NOT_OBJECT")
    _require_exact_keys(raw_limits, RESOURCE_LIMIT_FIELDS, "RESOURCE_LIMIT_FIELDS_INVALID")
    limits = SolverLimits(
        max_frame_members=_require_integer(
            raw_limits["max_frame_members"],
            "RESOURCE_FRAME_LIMIT_INVALID",
            minimum=1,
            maximum=ABSOLUTE_MAX_FRAME_MEMBERS,
        ),
        max_frontier_entries=_require_integer(
            raw_limits["max_frontier_entries"],
            "RESOURCE_FRONTIER_LIMIT_INVALID",
            minimum=1,
            maximum=ABSOLUTE_MAX_FRONTIER_ENTRIES,
        ),
        max_transitions=_require_integer(
            raw_limits["max_transitions"],
            "RESOURCE_TRANSITION_LIMIT_INVALID",
            minimum=1,
            maximum=ABSOLUTE_MAX_TRANSITIONS,
        ),
        max_seconds=_require_integer(
            raw_limits["max_seconds"],
            "RESOURCE_SECONDS_LIMIT_INVALID",
            minimum=1,
            maximum=ABSOLUTE_MAX_SECONDS,
        ),
    )
    if expected_count > limits.max_frame_members:
        raise PasCheckerError("COMMITMENT_FRAME_EXCEEDS_RESOURCE_LIMIT")
    return {
        "schema_version": COMMITMENT_SCHEMA_VERSION,
        "commitment_id": commitment_id,
        "pas10_version": PAS10_VERSION,
        "pas12_version": PAS12_VERSION,
        "pas_snapshot_id": snapshot_id,
        "snapshot_sha256": snapshot_sha256,
        "frame_member_set_sha256": frame_set_hash,
        "codebook_sha256": codebook_sha256,
        "pas10_machine_contract_sha256": pas10_contract_hash,
        "question_wording_sha256": wording_hash,
        "expected_implementation_sha256": expected_implementation_hash,
        "expected_machine_schema_sha256": expected_schema_hash,
        "algorithm_version": algorithm_version,
        "expected_frame_member_count": expected_count,
        "resource_limits": limits,
    }


def _donor_order_key(commitment_id: str, donor_id: str) -> tuple[str, str]:
    digest = _sha256_bytes(f"{commitment_id}|{donor_id}".encode("ascii"))
    return digest, donor_id


def _frontier_digest(
    frontier: Mapping[tuple[int, int, int, int], Mapping[int, FrontierEntry]],
    deadline: float | None = None,
) -> str:
    hasher = hashlib.sha256()
    for state_index, state_key in enumerate(sorted(frontier)):
        if state_index % 256 == 0 and deadline is not None and time.monotonic() > deadline:
            raise SolverBudgetExceeded("RESOURCE_SECONDS_EXCEEDED")
        for diverted_index, diverted_count in enumerate(sorted(frontier[state_key])):
            if diverted_index % 256 == 0 and deadline is not None and time.monotonic() > deadline:
                raise SolverBudgetExceeded("RESOURCE_SECONDS_EXCEEDED")
            entry = frontier[state_key][diverted_count]
            record = (*state_key, diverted_count, entry.diverted_capacity)
            hasher.update((",".join(str(item) for item in record) + "\n").encode("ascii"))
    if deadline is not None and time.monotonic() > deadline:
        raise SolverBudgetExceeded("RESOURCE_SECONDS_EXCEEDED")
    return hasher.hexdigest()


def _analytic_digest(
    reason_code: str,
    donors: Sequence[CapacityDonor],
    spec: PartitionSpec,
    deadline: float | None = None,
) -> str:
    capacities: list[int] = []
    for donor_index, donor in enumerate(donors):
        if donor_index % 256 == 0 and deadline is not None and time.monotonic() > deadline:
            raise SolverBudgetExceeded("RESOURCE_SECONDS_EXCEEDED")
        capacities.append(donor.capacity)
    capacities.sort()
    if deadline is not None and time.monotonic() > deadline:
        raise SolverBudgetExceeded("RESOURCE_SECONDS_EXCEEDED")
    value = {
        "reason_code": reason_code,
        "capacity_multiset": capacities,
        "node_target": dict(spec.node_target),
        "donor_floor": dict(spec.donor_floor),
        "batch_cap": dict(spec.batch_cap),
    }
    return _sha256_bytes(_canonical_json_bytes(value))


def _solver_error(
    reason_code: str,
    *,
    processed_donors: int,
    transitions: int,
    peak_frontier_entries: int,
) -> SolverResult:
    return SolverResult(
        status="error",
        reason_code=reason_code,
        derivation_kind="NONE_RESOURCE_INCOMPLETE",
        derivation_sha256="",
        metrics={
            "processed_donors": processed_donors,
            "transitions": transitions,
            "peak_frontier_entries": peak_frontier_entries,
        },
        witness=None,
    )


def _validate_partition_spec(spec: PartitionSpec) -> None:
    for mapping in (spec.node_target, spec.donor_floor, spec.batch_cap):
        if set(mapping) != set(BATCH_ORDER):
            raise PasCheckerError("PARTITION_SPEC_BATCHES_INVALID")
    for batch in BATCH_ORDER:
        target = spec.node_target[batch]
        floor = spec.donor_floor[batch]
        cap = spec.batch_cap[batch]
        if (
            isinstance(target, bool)
            or isinstance(floor, bool)
            or isinstance(cap, bool)
            or not all(isinstance(item, int) for item in (target, floor, cap))
            or target < 0
            or floor < 0
            or cap < 0
            or floor > target
        ):
            raise PasCheckerError("PARTITION_SPEC_VALUE_INVALID")


def _add_candidate(
    candidates: dict[tuple[int, int, int, int], dict[int, FrontierEntry]],
    state_key: tuple[int, int, int, int],
    diverted_count: int,
    entry: FrontierEntry,
) -> bool:
    by_count = candidates.setdefault(state_key, {})
    current = by_count.get(diverted_count)
    if current is None or entry.diverted_capacity < current.diverted_capacity:
        by_count[diverted_count] = entry
        return current is None
    return False


def _prune_candidates(
    candidates: Mapping[tuple[int, int, int, int], Mapping[int, FrontierEntry]],
    deadline: float | None = None,
) -> dict[tuple[int, int, int, int], dict[int, FrontierEntry]]:
    pruned: dict[tuple[int, int, int, int], dict[int, FrontierEntry]] = {}
    for state_index, state_key in enumerate(sorted(candidates)):
        if state_index % 256 == 0 and deadline is not None and time.monotonic() > deadline:
            raise SolverBudgetExceeded("RESOURCE_SECONDS_EXCEEDED")
        kept: dict[int, FrontierEntry] = {}
        best_capacity: int | None = None
        for diverted_index, diverted_count in enumerate(sorted(candidates[state_key])):
            if diverted_index % 256 == 0 and deadline is not None and time.monotonic() > deadline:
                raise SolverBudgetExceeded("RESOURCE_SECONDS_EXCEEDED")
            entry = candidates[state_key][diverted_count]
            if best_capacity is None or entry.diverted_capacity < best_capacity:
                kept[diverted_count] = entry
                best_capacity = entry.diverted_capacity
        if kept:
            pruned[state_key] = kept
    if deadline is not None and time.monotonic() > deadline:
        raise SolverBudgetExceeded("RESOURCE_SECONDS_EXCEEDED")
    return pruned


def _trace_assignments(trace: TraceNode | None) -> dict[str, list[str]]:
    assignments = {batch: [] for batch in BATCH_ORDER}
    cursor = trace
    while cursor is not None:
        assignments[cursor.batch].append(cursor.donor_id)
        cursor = cursor.previous
    for donor_ids in assignments.values():
        donor_ids.reverse()
    return assignments


def _allocate_exact(
    donor_ids: Sequence[str],
    capacities: Mapping[str, int],
    target: int,
) -> dict[str, int]:
    if len(donor_ids) > target or sum(capacities[donor_id] for donor_id in donor_ids) < target:
        raise PasCheckerError("WITNESS_ALLOCATION_PRECONDITION_INVALID")
    allocation = {donor_id: 1 for donor_id in donor_ids}
    remaining = target - len(donor_ids)
    # 逆序填充使较早 donor 的 n 尽量小；它只稳定 witness，不参与状态判断。
    for donor_id in reversed(donor_ids):
        extra = min(remaining, capacities[donor_id] - 1)
        allocation[donor_id] += extra
        remaining -= extra
    if remaining != 0:
        raise PasCheckerError("WITNESS_ALLOCATION_FAILED")
    return allocation


def _build_witness(
    trace: TraceNode | None,
    donors: Sequence[CapacityDonor],
    spec: PartitionSpec,
    commitment_id: str,
) -> dict[str, Any]:
    capacity_by_id = {donor.donor_id: donor.capacity for donor in donors}
    order = {donor.donor_id: index for index, donor in enumerate(donors)}
    selected = _trace_assignments(trace)
    used = set(selected["P0"]) | set(selected["Rtech"])

    remaining_m = [donor.donor_id for donor in donors if donor.donor_id not in used and donor.capacity > 0]
    selected_m: list[str] = []
    selected_m_capacity = 0
    if spec.node_target["M1"] > 0 or spec.donor_floor["M1"] > 0:
        for donor_id in remaining_m:
            selected_m.append(donor_id)
            selected_m_capacity += min(capacity_by_id[donor_id], spec.batch_cap["M1"])
            if len(selected_m) >= spec.donor_floor["M1"] and selected_m_capacity >= spec.node_target["M1"]:
                break
    selected["M1"] = selected_m

    records: list[dict[str, Any]] = []
    summaries: dict[str, dict[str, int]] = {}
    for batch in BATCH_ORDER:
        donor_ids = sorted(selected[batch], key=order.__getitem__)
        capacities = {donor_id: min(capacity_by_id[donor_id], spec.batch_cap[batch]) for donor_id in donor_ids}
        allocation = _allocate_exact(donor_ids, capacities, spec.node_target[batch])
        for donor_id in donor_ids:
            records.append(
                {
                    "screen_donor_id": donor_id,
                    "batch": batch,
                    "allocated_nodes": allocation[donor_id],
                }
            )
        summaries[batch] = {
            "assigned_donor_count": len(donor_ids),
            "allocated_nodes": sum(allocation.values()),
        }
    records.sort(key=lambda item: (BATCH_ORDER.index(item["batch"]), order[item["screen_donor_id"]]))
    return {
        "ordering": WITNESS_ORDERING,
        "commitment_id": commitment_id,
        "assignments": records,
        "batch_summary": summaries,
    }


def verify_witness(
    witness: Mapping[str, Any],
    donors: Sequence[CapacityDonor],
    spec: PartitionSpec,
    expected_commitment_id: str,
) -> bool:
    try:
        if set(witness) != {"ordering", "commitment_id", "assignments", "batch_summary"}:
            return False
        if witness["ordering"] != WITNESS_ORDERING or witness["commitment_id"] != expected_commitment_id:
            return False
        _validate_partition_spec(spec)
        if len({donor.donor_id for donor in donors}) != len(donors):
            return False
        if any(
            isinstance(donor.capacity, bool) or not isinstance(donor.capacity, int) or donor.capacity < 0
            for donor in donors
        ):
            return False
        capacity_by_id = {donor.donor_id: donor.capacity for donor in donors}
        assignments = witness["assignments"]
        if not isinstance(assignments, list):
            return False
        seen: set[str] = set()
        counts = {batch: 0 for batch in BATCH_ORDER}
        totals = {batch: 0 for batch in BATCH_ORDER}
        for record in assignments:
            if not isinstance(record, dict) or set(record) != {"screen_donor_id", "batch", "allocated_nodes"}:
                return False
            donor_id = record["screen_donor_id"]
            batch = record["batch"]
            nodes = record["allocated_nodes"]
            if (
                donor_id in seen
                or donor_id not in capacity_by_id
                or batch not in BATCH_ORDER
                or isinstance(nodes, bool)
                or not isinstance(nodes, int)
                or nodes < 1
                or nodes > min(capacity_by_id[donor_id], spec.batch_cap[batch])
            ):
                return False
            seen.add(donor_id)
            counts[batch] += 1
            totals[batch] += nodes
        if assignments != sorted(
            assignments,
            key=lambda item: (
                BATCH_ORDER.index(item["batch"]),
                _donor_order_key(expected_commitment_id, item["screen_donor_id"]),
            ),
        ):
            return False
        expected_summary = {
            batch: {"assigned_donor_count": counts[batch], "allocated_nodes": totals[batch]}
            for batch in BATCH_ORDER
        }
        summary = witness["batch_summary"]
        if not isinstance(summary, dict) or set(summary) != set(BATCH_ORDER):
            return False
        for batch in BATCH_ORDER:
            batch_value = summary[batch]
            if (
                not isinstance(batch_value, dict)
                or set(batch_value) != {"assigned_donor_count", "allocated_nodes"}
                or any(isinstance(value, bool) or not isinstance(value, int) for value in batch_value.values())
            ):
                return False
        return (
            summary == expected_summary
            and all(
                counts[batch] >= spec.donor_floor[batch] and totals[batch] == spec.node_target[batch]
                for batch in BATCH_ORDER
            )
        )
    except (KeyError, TypeError, PasCheckerError, ValueError):
        return False


def _find_feasible_entry(
    frontier: Mapping[tuple[int, int, int, int], Mapping[int, FrontierEntry]],
    terminal_state: tuple[int, int, int, int],
    *,
    total_donor_count: int,
    total_m_capacity: int,
    m_donor_floor: int,
    m_node_target: int,
) -> FrontierEntry | None:
    for diverted_count in sorted(frontier.get(terminal_state, {})):
        entry = frontier[terminal_state][diverted_count]
        if (
            total_donor_count - diverted_count >= m_donor_floor
            and total_m_capacity - entry.diverted_capacity >= m_node_target
        ):
            return entry
    return None


def _verified_feasible_result(
    entry: FrontierEntry,
    frontier: Mapping[tuple[int, int, int, int], Mapping[int, FrontierEntry]],
    donors: Sequence[CapacityDonor],
    spec: PartitionSpec,
    commitment_id: str,
    *,
    processed_donors: int,
    transitions: int,
    peak_frontier_entries: int,
    terminal_state: tuple[int, int, int, int],
    deadline: float,
) -> SolverResult:
    try:
        digest = _frontier_digest(frontier, deadline)
        witness = _build_witness(entry.trace, donors, spec, commitment_id)
        if time.monotonic() > deadline:
            raise SolverBudgetExceeded("RESOURCE_SECONDS_EXCEEDED")
    except SolverBudgetExceeded as exc:
        return _solver_error(
            exc.code,
            processed_donors=processed_donors,
            transitions=transitions,
            peak_frontier_entries=peak_frontier_entries,
        )
    except MemoryError:
        return _solver_error(
            "RESOURCE_MEMORY_EXHAUSTED",
            processed_donors=processed_donors,
            transitions=transitions,
            peak_frontier_entries=peak_frontier_entries,
        )
    except PasCheckerError:
        return SolverResult(
            status="error",
            reason_code="WITNESS_CONSTRUCTION_FAILED",
            derivation_kind="NONE_IMPLEMENTATION_ERROR",
            derivation_sha256="",
            metrics={
                "processed_donors": processed_donors,
                "transitions": transitions,
                "peak_frontier_entries": peak_frontier_entries,
            },
            witness=None,
        )
    metrics = {
        "processed_donors": processed_donors,
        "available_donors": len(donors),
        "transitions": transitions,
        "peak_frontier_entries": peak_frontier_entries,
        "terminal_frontier_entries": len(frontier.get(terminal_state, {})),
    }
    witness_verified = verify_witness(witness, donors, spec, commitment_id)
    if time.monotonic() > deadline:
        return _solver_error(
            "RESOURCE_SECONDS_EXCEEDED",
            processed_donors=processed_donors,
            transitions=transitions,
            peak_frontier_entries=peak_frontier_entries,
        )
    if not witness_verified:
        return SolverResult(
            status="error",
            reason_code="INDEPENDENT_WITNESS_VERIFICATION_FAILED",
            derivation_kind="NONE_IMPLEMENTATION_ERROR",
            derivation_sha256=digest,
            metrics=metrics,
            witness=None,
        )
    return SolverResult(
        status="feasible",
        reason_code="EXACT_WITNESS_VERIFIED",
        derivation_kind="EXACT_INTEGER_WITNESS",
        derivation_sha256=digest,
        metrics=metrics,
        witness=witness,
    )


def solve_exact_partition(
    donors: Sequence[CapacityDonor],
    spec: PartitionSpec,
    limits: SolverLimits,
    commitment_id: str,
    *,
    deadline: float | None = None,
) -> SolverResult:
    """用穷尽式 Pareto-DP 判断三批粗分区；只有完整穷尽才返回 infeasible。

    P0/Rtech 被显式分配，未被二者占用的正容量 donor 构成 M1 最大剩余池。
    对相同 P/R 已达容量与人数状态，只保留 diverted donor 数和 diverted M1
    容量的 Pareto 前沿；两者都更大的路径不可能改善任何未来 M1 可行性，故删除
    保持精确性。任一资源上限中断都会返回 error，而不是不可行证明。
    """

    started = time.monotonic()
    deadline = deadline if deadline is not None else started + limits.max_seconds
    _validate_partition_spec(spec)
    if started > deadline:
        return _solver_error(
            "RESOURCE_SECONDS_EXCEEDED",
            processed_donors=0,
            transitions=0,
            peak_frontier_entries=0,
        )
    if len(donors) > limits.max_frame_members:
        return _solver_error(
            "RESOURCE_FRAME_LIMIT_EXCEEDED",
            processed_donors=0,
            transitions=0,
            peak_frontier_entries=0,
        )
    if len({donor.donor_id for donor in donors}) != len(donors):
        raise PasCheckerError("SOLVER_DUPLICATE_DONOR")
    if any(
        isinstance(donor.capacity, bool) or not isinstance(donor.capacity, int) or donor.capacity < 0
        for donor in donors
    ):
        raise PasCheckerError("SOLVER_CAPACITY_INVALID")

    ordered = sorted(donors, key=lambda donor: _donor_order_key(commitment_id, donor.donor_id))
    positive = [donor for donor in ordered if donor.capacity > 0]
    total_assignable_capacity = sum(
        min(donor.capacity, max(spec.batch_cap.values())) for donor in positive
    )
    total_m_capacity = sum(min(donor.capacity, spec.batch_cap["M1"]) for donor in positive)
    total_floor = sum(spec.donor_floor.values())
    total_target = sum(spec.node_target.values())

    analytic_reason = ""
    if len(positive) < total_floor:
        analytic_reason = "ANALYTIC_DONOR_FLOOR_IMPOSSIBLE"
    elif total_assignable_capacity < total_target:
        analytic_reason = "ANALYTIC_TOTAL_CAPACITY_IMPOSSIBLE"
    else:
        for batch in BATCH_ORDER:
            if sum(min(donor.capacity, spec.batch_cap[batch]) for donor in positive) < spec.node_target[batch]:
                analytic_reason = f"ANALYTIC_{batch.upper()}_CAPACITY_IMPOSSIBLE"
                break
    if analytic_reason:
        if time.monotonic() > deadline:
            return _solver_error(
                "RESOURCE_SECONDS_EXCEEDED",
                processed_donors=0,
                transitions=0,
                peak_frontier_entries=1,
            )
        try:
            analytic_digest = _analytic_digest(analytic_reason, positive, spec, deadline)
        except SolverBudgetExceeded:
            return _solver_error(
                "RESOURCE_SECONDS_EXCEEDED",
                processed_donors=0,
                transitions=0,
                peak_frontier_entries=1,
            )
        if time.monotonic() > deadline:
            return _solver_error(
                "RESOURCE_SECONDS_EXCEEDED",
                processed_donors=0,
                transitions=0,
                peak_frontier_entries=1,
            )
        return SolverResult(
            status="infeasible",
            reason_code=analytic_reason,
            derivation_kind="EXACT_ANALYTIC_NECESSARY_CONDITION",
            derivation_sha256=analytic_digest,
            metrics={"processed_donors": 0, "transitions": 0, "peak_frontier_entries": 1},
            witness=None,
        )

    p_target = spec.node_target["P0"]
    r_target = spec.node_target["Rtech"]
    p_floor = spec.donor_floor["P0"]
    r_floor = spec.donor_floor["Rtech"]
    initial_state = (0, 0, 0, 0)
    frontier: dict[tuple[int, int, int, int], dict[int, FrontierEntry]] = {
        initial_state: {0: FrontierEntry(diverted_capacity=0, trace=None)}
    }
    transitions = 0
    peak_entries = 1
    terminal_state = (p_target, p_floor, r_target, r_floor)
    initial_feasible = _find_feasible_entry(
        frontier,
        terminal_state,
        total_donor_count=len(positive),
        total_m_capacity=total_m_capacity,
        m_donor_floor=spec.donor_floor["M1"],
        m_node_target=spec.node_target["M1"],
    )
    if initial_feasible is not None:
        return _verified_feasible_result(
            initial_feasible,
            frontier,
            positive,
            spec,
            commitment_id,
            processed_donors=0,
            transitions=0,
            peak_frontier_entries=1,
            terminal_state=terminal_state,
            deadline=deadline,
        )

    for donor_index, donor in enumerate(positive, start=1):
        candidates: dict[tuple[int, int, int, int], dict[int, FrontierEntry]] = {}
        candidate_entries = 0
        p_contribution = min(donor.capacity, spec.batch_cap["P0"])
        r_contribution = min(donor.capacity, spec.batch_cap["Rtech"])
        m_contribution = min(donor.capacity, spec.batch_cap["M1"])
        for state_key in sorted(frontier):
            p_capacity, p_count, r_capacity, r_count = state_key
            for diverted_count in sorted(frontier[state_key]):
                entry = frontier[state_key][diverted_count]
                transitions += 1
                candidate_entries += int(_add_candidate(candidates, state_key, diverted_count, entry))

                if p_contribution > 0 and (p_capacity < p_target or p_count < p_floor):
                    transitions += 1
                    p_state = (
                        min(p_target, p_capacity + p_contribution),
                        min(p_floor, p_count + 1),
                        r_capacity,
                        r_count,
                    )
                    candidate_entries += int(
                        _add_candidate(
                            candidates,
                            p_state,
                            diverted_count + 1,
                            FrontierEntry(
                                diverted_capacity=entry.diverted_capacity + m_contribution,
                                trace=TraceNode(entry.trace, donor.donor_id, "P0"),
                            ),
                        )
                    )

                if r_contribution > 0 and (r_capacity < r_target or r_count < r_floor):
                    transitions += 1
                    r_state = (
                        p_capacity,
                        p_count,
                        min(r_target, r_capacity + r_contribution),
                        min(r_floor, r_count + 1),
                    )
                    candidate_entries += int(
                        _add_candidate(
                            candidates,
                            r_state,
                            diverted_count + 1,
                            FrontierEntry(
                                diverted_capacity=entry.diverted_capacity + m_contribution,
                                trace=TraceNode(entry.trace, donor.donor_id, "Rtech"),
                            ),
                        )
                    )

                if candidate_entries > limits.max_frontier_entries:
                    return _solver_error(
                        "RESOURCE_FRONTIER_LIMIT_EXCEEDED",
                        processed_donors=donor_index - 1,
                        transitions=transitions,
                        peak_frontier_entries=max(peak_entries, candidate_entries),
                    )

                if transitions > limits.max_transitions:
                    return _solver_error(
                        "RESOURCE_TRANSITION_LIMIT_EXCEEDED",
                        processed_donors=donor_index - 1,
                        transitions=transitions,
                        peak_frontier_entries=peak_entries,
                    )
                if transitions % 1024 == 0 and time.monotonic() > deadline:
                    return _solver_error(
                        "RESOURCE_SECONDS_EXCEEDED",
                        processed_donors=donor_index - 1,
                        transitions=transitions,
                        peak_frontier_entries=peak_entries,
                    )

        try:
            frontier = _prune_candidates(candidates, deadline)
        except (SolverBudgetExceeded, MemoryError):
            return _solver_error(
                "RESOURCE_SECONDS_EXCEEDED" if time.monotonic() > deadline else "RESOURCE_MEMORY_EXHAUSTED",
                processed_donors=donor_index - 1,
                transitions=transitions,
                peak_frontier_entries=peak_entries,
            )
        frontier_entries = sum(len(entries) for entries in frontier.values())
        peak_entries = max(peak_entries, frontier_entries)
        if frontier_entries > limits.max_frontier_entries:
            return _solver_error(
                "RESOURCE_FRONTIER_LIMIT_EXCEEDED",
                processed_donors=donor_index,
                transitions=transitions,
                peak_frontier_entries=peak_entries,
            )
        if time.monotonic() > deadline:
            return _solver_error(
                "RESOURCE_SECONDS_EXCEEDED",
                processed_donors=donor_index,
                transitions=transitions,
                peak_frontier_entries=peak_entries,
            )
        feasible_entry = _find_feasible_entry(
            frontier,
            terminal_state,
            total_donor_count=len(positive),
            total_m_capacity=total_m_capacity,
            m_donor_floor=spec.donor_floor["M1"],
            m_node_target=spec.node_target["M1"],
        )
        if feasible_entry is not None:
            return _verified_feasible_result(
                feasible_entry,
                frontier,
                positive,
                spec,
                commitment_id,
                processed_donors=donor_index,
                transitions=transitions,
                peak_frontier_entries=peak_entries,
                terminal_state=terminal_state,
                deadline=deadline,
            )

    terminal_entries = frontier.get(terminal_state, {})
    try:
        digest = _frontier_digest(frontier, deadline)
    except (SolverBudgetExceeded, MemoryError):
        return _solver_error(
            "RESOURCE_SECONDS_EXCEEDED" if time.monotonic() > deadline else "RESOURCE_MEMORY_EXHAUSTED",
            processed_donors=len(positive),
            transitions=transitions,
            peak_frontier_entries=peak_entries,
        )
    if time.monotonic() > deadline:
        return _solver_error(
            "RESOURCE_SECONDS_EXCEEDED",
            processed_donors=len(positive),
            transitions=transitions,
            peak_frontier_entries=peak_entries,
        )
    metrics = {
        "processed_donors": len(positive),
        "transitions": transitions,
        "peak_frontier_entries": peak_entries,
        "terminal_frontier_entries": len(terminal_entries),
    }
    return SolverResult(
        status="infeasible",
        reason_code="EXHAUSTIVE_PARETO_FRONTIER_INFEASIBLE",
        derivation_kind="EXACT_EXHAUSTIVE_PARETO_FRONTIER",
        derivation_sha256=digest,
        metrics=metrics,
        witness=None,
    )


def _scenario_donors(donors: Sequence[NormalizedDonor], scenario: str) -> list[CapacityDonor]:
    return [CapacityDonor(donor.donor_id, donor.scalar_capacity(scenario)) for donor in donors]


def _result_without_witness(result: SolverResult) -> dict[str, Any]:
    batch_summary = None
    if result.witness is not None:
        batch_summary = result.witness["batch_summary"]
    return {
        "status": result.status,
        "reason_code": result.reason_code,
        "derivation_kind": result.derivation_kind,
        "derivation_sha256": result.derivation_sha256,
        "metrics": dict(result.metrics),
        "batch_summary": batch_summary,
    }


def _normalization_summary(donors: Sequence[NormalizedDonor]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for scenario in ("LB", "UB"):
        bounds_key = "lower" if scenario == "LB" else "upper"
        result[scenario] = {
            "eligible_donor_count": sum(
                donor.eligible_lower if scenario == "LB" else donor.eligible_upper for donor in donors
            ),
            "totals": {
                field: sum(getattr(donor, bounds_key)[field] for donor in donors) for field in COUNT_FIELDS
            },
        }
    return result


def _is_nonnegative_integer(value: Any) -> bool:
    return isinstance(value, int) and not isinstance(value, bool) and value >= 0


def _validate_batch_summary_schema(value: Any, *, nullable: bool) -> None:
    if value is None and nullable:
        return
    expected_batches = set(OUTPUT_SCHEMA_CONTRACT["batch_summary"]["exact_fields"])
    expected_batch_fields = set(OUTPUT_SCHEMA_CONTRACT["batch_summary"]["batch_exact_fields"])
    if expected_batches != set(BATCH_ORDER) or not isinstance(value, dict) or set(value) != expected_batches:
        raise PasCheckerError("OUTPUT_BATCH_SUMMARY_SCHEMA_INVALID")
    for batch in BATCH_ORDER:
        batch_value = value[batch]
        if (
            not isinstance(batch_value, dict)
            or set(batch_value) != expected_batch_fields
            or not all(_is_nonnegative_integer(item) for item in batch_value.values())
        ):
            raise PasCheckerError("OUTPUT_BATCH_SUMMARY_SCHEMA_INVALID")


def _validate_witness_schema(value: Any, *, expected_commitment_id: str) -> None:
    expected_witness_fields = set(OUTPUT_SCHEMA_CONTRACT["witness"]["exact_fields"])
    expected_assignment_fields = set(OUTPUT_SCHEMA_CONTRACT["witness"]["assignment_exact_fields"])
    if (
        expected_witness_fields != {"ordering", "commitment_id", "assignments", "batch_summary"}
        or not isinstance(value, dict)
        or set(value) != expected_witness_fields
    ):
        raise PasCheckerError("OUTPUT_WITNESS_SCHEMA_INVALID")
    if value["ordering"] != WITNESS_ORDERING:
        raise PasCheckerError("OUTPUT_WITNESS_SCHEMA_INVALID")
    _require_opaque_id(value["commitment_id"], "OUTPUT_WITNESS_SCHEMA_INVALID")
    if value["commitment_id"] != expected_commitment_id:
        raise PasCheckerError("OUTPUT_WITNESS_SCHEMA_INVALID")
    assignments = value["assignments"]
    if not isinstance(assignments, list):
        raise PasCheckerError("OUTPUT_WITNESS_SCHEMA_INVALID")
    seen_donors: set[str] = set()
    counts = {batch: 0 for batch in BATCH_ORDER}
    totals = {batch: 0 for batch in BATCH_ORDER}
    for assignment in assignments:
        if not isinstance(assignment, dict) or set(assignment) != expected_assignment_fields:
            raise PasCheckerError("OUTPUT_WITNESS_SCHEMA_INVALID")
        _require_opaque_id(assignment["screen_donor_id"], "OUTPUT_WITNESS_SCHEMA_INVALID")
        donor_id = assignment["screen_donor_id"]
        batch = assignment["batch"]
        nodes = assignment["allocated_nodes"]
        if (
            donor_id in seen_donors
            or not isinstance(batch, str)
            or batch not in BATCH_ORDER
            or not _is_nonnegative_integer(nodes)
        ):
            raise PasCheckerError("OUTPUT_WITNESS_SCHEMA_INVALID")
        if nodes < 1:
            raise PasCheckerError("OUTPUT_WITNESS_SCHEMA_INVALID")
        seen_donors.add(donor_id)
        counts[batch] += 1
        totals[batch] += nodes
    _validate_batch_summary_schema(value["batch_summary"], nullable=False)
    expected_summary = {
        batch: {"assigned_donor_count": counts[batch], "allocated_nodes": totals[batch]}
        for batch in BATCH_ORDER
    }
    if value["batch_summary"] != expected_summary:
        raise PasCheckerError("OUTPUT_WITNESS_SCHEMA_INVALID")


def _validate_normalization_summary_schema(value: Any) -> None:
    expected_scenarios = set(OUTPUT_SCHEMA_CONTRACT["normalization_summary"]["exact_fields"])
    expected_fields = set(OUTPUT_SCHEMA_CONTRACT["normalization_summary"]["scenario_exact_fields"])
    expected_totals = set(OUTPUT_SCHEMA_CONTRACT["normalization_summary"]["totals_exact_fields"])
    if expected_scenarios != {"LB", "UB"} or not isinstance(value, dict) or set(value) != expected_scenarios:
        raise PasCheckerError("OUTPUT_NORMALIZATION_SCHEMA_INVALID")
    for scenario in ("LB", "UB"):
        scenario_value = value[scenario]
        if not isinstance(scenario_value, dict) or set(scenario_value) != expected_fields:
            raise PasCheckerError("OUTPUT_NORMALIZATION_SCHEMA_INVALID")
        totals = scenario_value["totals"]
        if (
            not _is_nonnegative_integer(scenario_value["eligible_donor_count"])
            or not isinstance(totals, dict)
            or set(totals) != expected_totals
            or expected_totals != set(COUNT_FIELDS)
            or not all(_is_nonnegative_integer(item) for item in totals.values())
        ):
            raise PasCheckerError("OUTPUT_NORMALIZATION_SCHEMA_INVALID")


def _validate_metrics_schema(value: Any) -> None:
    required = set(OUTPUT_SCHEMA_CONTRACT["metrics"]["required_fields"])
    optional = set(OUTPUT_SCHEMA_CONTRACT["metrics"]["optional_fields"])
    if required != {"processed_donors", "transitions", "peak_frontier_entries"} or optional != {
        "available_donors",
        "terminal_frontier_entries",
    }:
        raise PasCheckerError("OUTPUT_SCHEMA_CONTRACT_INTERNAL_MISMATCH")
    if (
        not isinstance(value, dict)
        or not required.issubset(value)
        or not set(value).issubset(required | optional)
        or not all(_is_nonnegative_integer(item) for item in value.values())
    ):
        raise PasCheckerError("OUTPUT_METRICS_SCHEMA_INVALID")


def _validate_scenario_result_schema(
    value: Any,
    *,
    include_witness: bool,
    expected_commitment_id: str,
) -> None:
    base_fields = set(OUTPUT_SCHEMA_CONTRACT["scenario_result"]["exact_fields"])
    if base_fields != {"status", "reason_code", "derivation_kind", "derivation_sha256", "metrics", "batch_summary"}:
        raise PasCheckerError("OUTPUT_SCHEMA_CONTRACT_INTERNAL_MISMATCH")
    expected_fields = base_fields | ({"witness"} if include_witness else set())
    if not isinstance(value, dict) or set(value) != expected_fields:
        raise PasCheckerError("OUTPUT_SCENARIO_SCHEMA_INVALID")
    status = value["status"]
    reason_code = value["reason_code"]
    derivation_kind = value["derivation_kind"]
    if (
        not isinstance(status, str)
        or not isinstance(reason_code, str)
        or status not in SOLVER_STATUSES
        or reason_code not in SOLVER_REASON_CODES
    ):
        raise PasCheckerError("OUTPUT_SCENARIO_SCHEMA_INVALID")
    if not isinstance(derivation_kind, str) or derivation_kind not in DERIVATION_KINDS:
        raise PasCheckerError("OUTPUT_SCENARIO_SCHEMA_INVALID")
    derivation_sha256 = value["derivation_sha256"]
    if derivation_sha256 != "":
        _require_hash(derivation_sha256, "OUTPUT_SCENARIO_SCHEMA_INVALID")
    _validate_metrics_schema(value["metrics"])
    _validate_batch_summary_schema(value["batch_summary"], nullable=True)
    if status == "feasible":
        if (
            reason_code != "EXACT_WITNESS_VERIFIED"
            or derivation_kind != "EXACT_INTEGER_WITNESS"
            or value["batch_summary"] is None
            or derivation_sha256 == ""
        ):
            raise PasCheckerError("OUTPUT_SCENARIO_SCHEMA_INVALID")
    elif status == "infeasible":
        analytic_pair = (
            reason_code in ANALYTIC_REASON_CODES
            and derivation_kind == "EXACT_ANALYTIC_NECESSARY_CONDITION"
        )
        exhaustive_pair = (
            reason_code == "EXHAUSTIVE_PARETO_FRONTIER_INFEASIBLE"
            and derivation_kind == "EXACT_EXHAUSTIVE_PARETO_FRONTIER"
        )
        if value["batch_summary"] is not None or derivation_sha256 == "" or not (analytic_pair or exhaustive_pair):
            raise PasCheckerError("OUTPUT_SCENARIO_SCHEMA_INVALID")
    else:
        resource_pair = reason_code in RESOURCE_REASON_CODES and derivation_kind == "NONE_RESOURCE_INCOMPLETE"
        implementation_pair = (
            reason_code in IMPLEMENTATION_REASON_CODES and derivation_kind == "NONE_IMPLEMENTATION_ERROR"
        )
        if value["batch_summary"] is not None or not (resource_pair or implementation_pair):
            raise PasCheckerError("OUTPUT_SCENARIO_SCHEMA_INVALID")
    if include_witness:
        witness = value["witness"]
        if status == "feasible":
            _validate_witness_schema(witness, expected_commitment_id=expected_commitment_id)
            if witness["batch_summary"] != value["batch_summary"]:
                raise PasCheckerError("OUTPUT_SCENARIO_SCHEMA_INVALID")
        elif witness is not None:
            raise PasCheckerError("OUTPUT_SCENARIO_SCHEMA_INVALID")


def _common_output_fields() -> frozenset[str]:
    return frozenset(OUTPUT_SCHEMA_CONTRACT["common"]["exact_fields"])


def _validate_output_schema_contract_constants() -> None:
    expected_literals = {
        "contract_status": "CANDIDATE_MATH_KERNEL_NOT_FROZEN",
        "pas13_review_status": "NOT_PERFORMED",
        "private_scope": "ACL_PRIVATE_MATHEMATICAL_RUN_RECORD",
        "aggregate_scope": "ACL_ONLY_NOT_PAS13_PUBLIC",
        "pair_commit_protocol": PAIR_COMMIT_PROTOCOL,
        "governance_notice": GOVERNANCE_NOTICE,
        "pas10_version": PAS10_VERSION,
        "pas12_version": PAS12_VERSION,
        "algorithm_version": ALGORITHM_VERSION,
        "witness_ordering": WITNESS_ORDERING,
    }
    expected_enums = {
        "solver_statuses": sorted(SOLVER_STATUSES),
        "solver_reason_codes": sorted(SOLVER_REASON_CODES),
        "derivation_kinds": sorted(DERIVATION_KINDS),
        "mathematical_outcomes": sorted(MATHEMATICAL_OUTCOMES),
        "batch_order": list(BATCH_ORDER),
    }
    if (
        OUTPUT_SCHEMA_CONTRACT["literals"] != expected_literals
        or OUTPUT_SCHEMA_CONTRACT["enums"] != expected_enums
        or OUTPUT_SCHEMA_SHA256 != _sha256_bytes(_canonical_json_bytes(OUTPUT_SCHEMA_CONTRACT))
    ):
        raise PasCheckerError("OUTPUT_SCHEMA_CONTRACT_INTERNAL_MISMATCH")


def _validate_common_output_schema(value: Mapping[str, Any]) -> None:
    if set(value) != _common_output_fields():
        raise PasCheckerError("OUTPUT_COMMON_SCHEMA_INVALID")
    for field in (
        "commitment_sha256",
        "snapshot_sha256",
        "frame_member_set_sha256",
        "implementation_sha256",
        "machine_schema_sha256",
        "output_schema_sha256",
        "codebook_sha256",
        "pas10_machine_contract_sha256",
        "question_wording_sha256",
    ):
        _require_hash(value[field], "OUTPUT_COMMON_SCHEMA_INVALID")
    for field in ("commitment_id", "pas_snapshot_id"):
        _require_opaque_id(value[field], "OUTPUT_COMMON_SCHEMA_INVALID")
    if (
        value["contract_status"] != "CANDIDATE_MATH_KERNEL_NOT_FROZEN"
        or value["authorization_evaluated"] is not False
        or value["pas_state_computed"] is not False
        or value["pas13_review_status"] != "NOT_PERFORMED"
        or value["public_release_authorized"] is not False
        or value["pas10_version"] != PAS10_VERSION
        or value["pas12_version"] != PAS12_VERSION
        or value["algorithm_version"] != ALGORITHM_VERSION
        or value["machine_schema_sha256"] != MACHINE_SCHEMA_SHA256
        or value["output_schema_sha256"] != OUTPUT_SCHEMA_SHA256
        or value["codebook_sha256"] != CODEBOOK_SHA256
        or value["pas10_machine_contract_sha256"] != PAS10_MACHINE_CONTRACT_SHA256
        or not _is_nonnegative_integer(value["frame_member_count"])
        or value["mathematical_outcome"] not in MATHEMATICAL_OUTCOMES
        or value["governance_notice"] != GOVERNANCE_NOTICE
    ):
        raise PasCheckerError("OUTPUT_COMMON_SCHEMA_INVALID")
    _validate_normalization_summary_schema(value["normalization_summary"])


def _classify_mathematical_statuses(lb_status: str, ub_status: str) -> str:
    if "error" in {lb_status, ub_status}:
        return "MATHEMATICAL_INCONCLUSIVE"
    if lb_status == "feasible" and ub_status == "infeasible":
        return "INPUT_OR_IMPLEMENTATION_INVALID"
    if ub_status == "infeasible":
        return "UB_EXACT_INFEASIBLE"
    if ub_status == "feasible" and lb_status == "feasible":
        return "LB_AND_UB_EXACT_FEASIBLE"
    if ub_status == "feasible" and lb_status == "infeasible":
        return "UB_ONLY_EXACT_FEASIBLE"
    return "MATHEMATICAL_INCONCLUSIVE"


def validate_output_pair_structure(
    private_run_record: Mapping[str, Any],
    aggregate_receipt: Mapping[str, Any],
) -> None:
    """只验证 exact schema 与两文件内部一致性，不针对原始输入重放数学证明。"""
    _validate_output_schema_contract_constants()
    private_extra = {"schema_version", "scope", "scenario_results"}
    aggregate_extra = {
        "schema_version",
        "scope",
        "private_run_record_canonical_sha256",
        "scenario_results",
        "contains_direct_donor_id_fields",
        "pas13_public_projection_created",
        "pair_commit_protocol",
        "pair_complete",
    }
    private_expected = frozenset(OUTPUT_SCHEMA_CONTRACT["private_run_record"]["exact_fields"])
    aggregate_expected = frozenset(OUTPUT_SCHEMA_CONTRACT["aggregate_receipt"]["exact_fields"])
    if private_expected != _common_output_fields() | private_extra:
        raise PasCheckerError("OUTPUT_SCHEMA_CONTRACT_INTERNAL_MISMATCH")
    if aggregate_expected != _common_output_fields() | aggregate_extra:
        raise PasCheckerError("OUTPUT_SCHEMA_CONTRACT_INTERNAL_MISMATCH")
    if not isinstance(private_run_record, dict) or set(private_run_record) != private_expected:
        raise PasCheckerError("PRIVATE_RUN_RECORD_SCHEMA_INVALID")
    if not isinstance(aggregate_receipt, dict) or set(aggregate_receipt) != aggregate_expected:
        raise PasCheckerError("AGGREGATE_RECEIPT_SCHEMA_INVALID")
    private_common = {field: private_run_record[field] for field in _common_output_fields()}
    aggregate_common = {field: aggregate_receipt[field] for field in _common_output_fields()}
    _validate_common_output_schema(private_common)
    _validate_common_output_schema(aggregate_common)
    if private_common != aggregate_common:
        raise PasCheckerError("OUTPUT_PAIR_COMMON_FIELDS_MISMATCH")
    if (
        private_run_record["schema_version"] != PRIVATE_RUN_RECORD_SCHEMA_VERSION
        or private_run_record["scope"] != "ACL_PRIVATE_MATHEMATICAL_RUN_RECORD"
        or aggregate_receipt["schema_version"] != AGGREGATE_RECEIPT_SCHEMA_VERSION
        or aggregate_receipt["scope"] != "ACL_ONLY_NOT_PAS13_PUBLIC"
        or aggregate_receipt["contains_direct_donor_id_fields"] is not False
        or aggregate_receipt["pas13_public_projection_created"] is not False
        or aggregate_receipt["pair_commit_protocol"] != PAIR_COMMIT_PROTOCOL
        or aggregate_receipt["pair_complete"] is not True
    ):
        raise PasCheckerError("OUTPUT_PAIR_SCHEMA_INVALID")
    for container in (private_run_record, aggregate_receipt):
        scenarios = container["scenario_results"]
        if not isinstance(scenarios, dict) or set(scenarios) != {"LB", "UB"}:
            raise PasCheckerError("OUTPUT_SCENARIO_SCHEMA_INVALID")
    for scenario in ("LB", "UB"):
        private_scenario = private_run_record["scenario_results"][scenario]
        aggregate_scenario = aggregate_receipt["scenario_results"][scenario]
        _validate_scenario_result_schema(
            private_scenario,
            include_witness=True,
            expected_commitment_id=private_common["commitment_id"],
        )
        _validate_scenario_result_schema(
            aggregate_scenario,
            include_witness=False,
            expected_commitment_id=private_common["commitment_id"],
        )
        if {field: private_scenario[field] for field in aggregate_scenario} != aggregate_scenario:
            raise PasCheckerError("OUTPUT_PAIR_SCENARIO_MISMATCH")
    expected_outcome = _classify_mathematical_statuses(
        private_run_record["scenario_results"]["LB"]["status"],
        private_run_record["scenario_results"]["UB"]["status"],
    )
    if private_common["mathematical_outcome"] != expected_outcome:
        raise PasCheckerError("OUTPUT_PAIR_MATHEMATICAL_OUTCOME_MISMATCH")
    expected_private_hash = _sha256_bytes(_canonical_json_bytes(private_run_record))
    if aggregate_receipt["private_run_record_canonical_sha256"] != expected_private_hash:
        raise PasCheckerError("OUTPUT_PAIR_PRIVATE_HASH_MISMATCH")


def classify_mathematical_outcome(lb_result: SolverResult, ub_result: SolverResult) -> str:
    return _classify_mathematical_statuses(lb_result.status, ub_result.status)


def _safe_solve_partition(
    donors: Sequence[CapacityDonor],
    spec: PartitionSpec,
    limits: SolverLimits,
    commitment_id: str,
    deadline: float,
) -> SolverResult:
    try:
        return solve_exact_partition(
            donors,
            spec,
            limits,
            commitment_id,
            deadline=deadline,
        )
    except MemoryError:
        return _solver_error(
            "RESOURCE_MEMORY_EXHAUSTED",
            processed_donors=0,
            transitions=0,
            peak_frontier_entries=0,
        )
    except SolverBudgetExceeded as exc:
        return _solver_error(
            exc.code,
            processed_donors=0,
            transitions=0,
            peak_frontier_entries=0,
        )
    except PasCheckerError:
        return SolverResult(
            status="error",
            reason_code="SOLVER_INTERNAL_ERROR",
            derivation_kind="NONE_IMPLEMENTATION_ERROR",
            derivation_sha256="",
            metrics={"processed_donors": 0, "transitions": 0, "peak_frontier_entries": 0},
            witness=None,
        )


def _run_checker_validated(
    commitment: Mapping[str, Any],
    snapshot: Mapping[str, Any],
    *,
    commitment_sha256: str,
    snapshot_sha256: str,
    implementation_sha256: str,
) -> tuple[dict[str, Any], dict[str, Any]]:
    limits = commitment["resource_limits"]
    deadline = time.monotonic() + limits.max_seconds
    normalized = validate_and_normalize_snapshot(snapshot, commitment)
    normalization = _normalization_summary(normalized)
    lb_spec = PartitionSpec(NODE_TARGET, PLANNING_DONOR_FLOOR, BATCH_CAP)
    ub_spec = PartitionSpec(NODE_TARGET, HARD_DONOR_FLOOR, BATCH_CAP)
    if time.monotonic() > deadline:
        lb_result = _solver_error(
            "RESOURCE_SECONDS_EXCEEDED",
            processed_donors=0,
            transitions=0,
            peak_frontier_entries=0,
        )
        ub_result = lb_result
    else:
        lb_result = _safe_solve_partition(
            _scenario_donors(normalized, "LB"),
            lb_spec,
            limits,
            commitment["commitment_id"],
            deadline,
        )
        ub_result = _safe_solve_partition(
            _scenario_donors(normalized, "UB"),
            ub_spec,
            limits,
            commitment["commitment_id"],
            deadline,
        )
        if time.monotonic() > deadline:
            lb_result = _solver_error(
                "RESOURCE_SECONDS_EXCEEDED",
                processed_donors=0,
                transitions=0,
                peak_frontier_entries=0,
            )
            ub_result = lb_result
    outcome = classify_mathematical_outcome(lb_result, ub_result)
    common = {
        "contract_status": "CANDIDATE_MATH_KERNEL_NOT_FROZEN",
        "authorization_evaluated": False,
        "pas_state_computed": False,
        "pas13_review_status": "NOT_PERFORMED",
        "public_release_authorized": False,
        "pas10_version": PAS10_VERSION,
        "pas12_version": PAS12_VERSION,
        "algorithm_version": ALGORITHM_VERSION,
        "commitment_id": commitment["commitment_id"],
        "pas_snapshot_id": commitment["pas_snapshot_id"],
        "commitment_sha256": commitment_sha256,
        "snapshot_sha256": snapshot_sha256,
        "frame_member_set_sha256": commitment["frame_member_set_sha256"],
        "implementation_sha256": implementation_sha256,
        "machine_schema_sha256": MACHINE_SCHEMA_SHA256,
        "output_schema_sha256": OUTPUT_SCHEMA_SHA256,
        "codebook_sha256": CODEBOOK_SHA256,
        "pas10_machine_contract_sha256": PAS10_MACHINE_CONTRACT_SHA256,
        "question_wording_sha256": commitment["question_wording_sha256"],
        "frame_member_count": len(normalized),
        "normalization_summary": normalization,
        "mathematical_outcome": outcome,
        "governance_notice": GOVERNANCE_NOTICE,
    }
    private_run_record = {
        "schema_version": PRIVATE_RUN_RECORD_SCHEMA_VERSION,
        "scope": "ACL_PRIVATE_MATHEMATICAL_RUN_RECORD",
        **common,
        "scenario_results": {
            "LB": {**_result_without_witness(lb_result), "witness": lb_result.witness},
            "UB": {**_result_without_witness(ub_result), "witness": ub_result.witness},
        },
    }
    private_run_record_sha256 = _sha256_bytes(_canonical_json_bytes(private_run_record))
    aggregate_receipt = {
        "schema_version": AGGREGATE_RECEIPT_SCHEMA_VERSION,
        "scope": "ACL_ONLY_NOT_PAS13_PUBLIC",
        **common,
        "private_run_record_canonical_sha256": private_run_record_sha256,
        "scenario_results": {
            "LB": _result_without_witness(lb_result),
            "UB": _result_without_witness(ub_result),
        },
        "contains_direct_donor_id_fields": False,
        "pas13_public_projection_created": False,
        "pair_commit_protocol": PAIR_COMMIT_PROTOCOL,
        "pair_complete": True,
    }
    validate_output_pair_structure(private_run_record, aggregate_receipt)
    return private_run_record, aggregate_receipt


def _is_reparse_point(path: Path) -> bool:
    try:
        info = path.lstat()
    except OSError as exc:
        raise PasCheckerError("PATH_METADATA_UNAVAILABLE") from exc
    attributes = getattr(info, "st_file_attributes", 0)
    reparse_flag = getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0)
    return path.is_symlink() or bool(attributes & reparse_flag)


def _is_relative_to(path: Path, parent: Path) -> bool:
    try:
        path.relative_to(parent)
        return True
    except ValueError:
        return False


def _validate_basename(value: str, code: str) -> str:
    if not JSON_BASENAME_RE.fullmatch(value):
        raise PasCheckerError(code)
    return value


def _resolve_restricted_paths(
    restricted_root: Path,
    commitment_name: str,
    snapshot_name: str,
    private_run_record_name: str,
    aggregate_receipt_name: str,
) -> tuple[Path, Path, Path, Path]:
    if not restricted_root.exists() or not restricted_root.is_dir() or _is_reparse_point(restricted_root):
        raise PasCheckerError("RESTRICTED_ROOT_INVALID")
    root = restricted_root.resolve(strict=True)
    repo_root = Path(__file__).resolve().parents[1]
    if _is_relative_to(root, repo_root) or _is_relative_to(repo_root, root):
        raise PasCheckerError("RESTRICTED_ROOT_OVERLAPS_REPOSITORY")
    forbidden_parts = {"desktop", "onedrive", "dropbox", "google drive", "icloud drive"}
    if any(part.casefold() in forbidden_parts for part in root.parts):
        raise PasCheckerError("RESTRICTED_ROOT_OBVIOUSLY_SYNCED_OR_DESKTOP")
    for ancestor in (root, *root.parents):
        if (ancestor / ".git").exists():
            raise PasCheckerError("RESTRICTED_ROOT_OVERLAPS_GIT_WORKTREE")

    names = [
        _validate_basename(commitment_name, "COMMITMENT_BASENAME_INVALID"),
        _validate_basename(snapshot_name, "SNAPSHOT_BASENAME_INVALID"),
        _validate_basename(private_run_record_name, "PRIVATE_RUN_RECORD_BASENAME_INVALID"),
        _validate_basename(aggregate_receipt_name, "AGGREGATE_RECEIPT_BASENAME_INVALID"),
    ]
    if len(set(names)) != len(names):
        raise PasCheckerError("INPUT_OUTPUT_BASENAMES_NOT_DISTINCT")
    paths = tuple(root / name for name in names)
    commitment_path, snapshot_path, private_path, aggregate_path = paths
    for input_path in (commitment_path, snapshot_path):
        if (
            not input_path.exists()
            or not input_path.is_file()
            or _is_reparse_point(input_path)
            or input_path.resolve(strict=True).parent != root
        ):
            raise PasCheckerError("INPUT_FILE_INVALID")
    private_exists = private_path.exists()
    aggregate_exists = aggregate_path.exists()
    if private_exists != aggregate_exists:
        raise PasCheckerError("OUTPUT_INCOMPLETE_PAIR_PRESENT")
    if private_exists:
        raise PasCheckerError("OUTPUT_ALREADY_EXISTS")
    return commitment_path, snapshot_path, private_path, aggregate_path


def _read_limited_file(path: Path) -> bytes:
    descriptor = -1
    try:
        before = path.lstat()
        if not stat.S_ISREG(before.st_mode) or before.st_nlink != 1 or _is_reparse_point(path):
            raise PasCheckerError("INPUT_FILE_IDENTITY_INVALID")
        flags = os.O_RDONLY
        if hasattr(os, "O_BINARY"):
            flags |= os.O_BINARY
        if hasattr(os, "O_NOFOLLOW"):
            flags |= os.O_NOFOLLOW
        descriptor = os.open(path, flags)
        opened = os.fstat(descriptor)
        if (
            not stat.S_ISREG(opened.st_mode)
            or (before.st_dev, before.st_ino) != (opened.st_dev, opened.st_ino)
            or opened.st_nlink != 1
        ):
            raise PasCheckerError("INPUT_FILE_CHANGED_DURING_OPEN")
        if opened.st_size > MAX_INPUT_BYTES:
            raise PasCheckerError("INPUT_TOO_LARGE")
        chunks: list[bytes] = []
        total = 0
        while True:
            read_size = min(1024 * 1024, MAX_INPUT_BYTES + 1 - total)
            chunk = os.read(descriptor, read_size)
            if not chunk:
                break
            chunks.append(chunk)
            total += len(chunk)
            if total > MAX_INPUT_BYTES:
                raise PasCheckerError("INPUT_TOO_LARGE")
        finished = os.fstat(descriptor)
        if (
            (opened.st_dev, opened.st_ino, opened.st_size, opened.st_mtime_ns)
            != (finished.st_dev, finished.st_ino, finished.st_size, finished.st_mtime_ns)
            or total != finished.st_size
        ):
            raise PasCheckerError("INPUT_FILE_CHANGED_DURING_READ")
        return b"".join(chunks)
    except PasCheckerError:
        raise
    except OSError as exc:
        raise PasCheckerError("INPUT_READ_FAILED") from exc
    finally:
        if descriptor >= 0:
            try:
                os.close(descriptor)
            except OSError:
                pass


def run_checker(commitment_bytes: bytes, snapshot_bytes: bytes) -> tuple[dict[str, Any], dict[str, Any]]:
    """从原始字节建立全部 hash 绑定后运行候选数学核。"""

    if type(commitment_bytes) is not bytes:  # type identity rejects mutable bytearray inputs
        raise PasCheckerError("RUN_COMMITMENT_BYTES_REQUIRED")
    if type(snapshot_bytes) is not bytes:
        raise PasCheckerError("RUN_SNAPSHOT_BYTES_REQUIRED")
    if len(commitment_bytes) > MAX_INPUT_BYTES or len(snapshot_bytes) > MAX_INPUT_BYTES:
        raise PasCheckerError("INPUT_TOO_LARGE")
    implementation_bytes = _read_limited_file(Path(__file__))
    implementation_sha256 = _sha256_bytes(implementation_bytes)
    raw_commitment = strict_json_loads(commitment_bytes)
    commitment = validate_commitment(raw_commitment, implementation_sha256=implementation_sha256)
    snapshot_sha256 = _sha256_bytes(snapshot_bytes)
    if snapshot_sha256 != commitment["snapshot_sha256"]:
        raise PasCheckerError("SNAPSHOT_FILE_HASH_MISMATCH")
    snapshot = strict_json_loads(snapshot_bytes)
    result = _run_checker_validated(
        commitment,
        snapshot,
        commitment_sha256=_sha256_bytes(commitment_bytes),
        snapshot_sha256=snapshot_sha256,
        implementation_sha256=implementation_sha256,
    )
    if _sha256_bytes(_read_limited_file(Path(__file__))) != implementation_sha256:
        raise PasCheckerError("IMPLEMENTATION_CHANGED_DURING_RUN")
    return result


def _path_identity(path: Path) -> tuple[int, int]:
    info = path.lstat()
    return info.st_dev, info.st_ino


def _unlink_if_owned(path: Path, identity: tuple[int, int]) -> bool:
    try:
        if _path_identity(path) != identity:
            return False
        path.unlink()
        return True
    except FileNotFoundError:
        return True
    except OSError:
        return False


def _write_complete_temp(path: Path, value: Mapping[str, Any]) -> tuple[int, int]:
    data = json.dumps(value, ensure_ascii=True, indent=2, sort_keys=True).encode("utf-8") + b"\n"
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
    if hasattr(os, "O_BINARY"):
        flags |= os.O_BINARY
    descriptor = -1
    created_identity: tuple[int, int] | None = None
    try:
        descriptor = os.open(path, flags, 0o600)
        opened = os.fstat(descriptor)
        created_identity = (opened.st_dev, opened.st_ino)
        with os.fdopen(descriptor, "wb") as handle:
            descriptor = -1
            handle.write(data)
            handle.flush()
            os.fsync(handle.fileno())
        if _path_identity(path) != created_identity:
            raise PasCheckerError("OUTPUT_TEMP_IDENTITY_CHANGED")
        return created_identity
    except FileExistsError as exc:
        raise PasCheckerError("OUTPUT_ALREADY_EXISTS") from exc
    except PasCheckerError:
        if created_identity is not None and not _unlink_if_owned(path, created_identity):
            raise PasCheckerError("OUTPUT_TEMP_STATE_UNCERTAIN")
        raise
    except OSError as exc:
        if created_identity is not None and not _unlink_if_owned(path, created_identity):
            raise PasCheckerError("OUTPUT_TEMP_STATE_UNCERTAIN") from exc
        raise PasCheckerError("OUTPUT_WRITE_FAILED") from exc
    finally:
        if descriptor >= 0:
            try:
                os.close(descriptor)
            except OSError:
                pass


def _publish_temp(temp_path: Path, final_path: Path) -> None:
    try:
        os.link(temp_path, final_path, follow_symlinks=False)
    except FileExistsError as exc:
        raise PasCheckerError("OUTPUT_ALREADY_EXISTS") from exc
    except OSError as exc:
        raise PasCheckerError("OUTPUT_ATOMIC_PUBLISH_FAILED") from exc


def _write_output_pair(
    private_path: Path,
    private_run_record: Mapping[str, Any],
    aggregate_path: Path,
    aggregate_receipt: Mapping[str, Any],
) -> None:
    token = secrets.token_hex(16)
    private_temp = private_path.parent / f".hnf1pas-private-{token}.tmp"
    aggregate_temp = aggregate_path.parent / f".hnf1pas-aggregate-{token}.tmp"
    owned_temps: dict[Path, tuple[int, int]] = {}
    published: dict[Path, tuple[int, int]] = {}
    rollback_failed = False
    try:
        validate_output_pair_structure(private_run_record, aggregate_receipt)
        owned_temps[private_temp] = _write_complete_temp(private_temp, private_run_record)
        owned_temps[aggregate_temp] = _write_complete_temp(aggregate_temp, aggregate_receipt)
        if _path_identity(private_temp) != owned_temps[private_temp]:
            raise PasCheckerError("OUTPUT_TEMP_IDENTITY_CHANGED")
        _publish_temp(private_temp, private_path)
        published[private_path] = owned_temps[private_temp]
        if _path_identity(private_path) != owned_temps[private_temp]:
            raise PasCheckerError("OUTPUT_PUBLISHED_IDENTITY_INVALID")
        if _path_identity(aggregate_temp) != owned_temps[aggregate_temp]:
            raise PasCheckerError("OUTPUT_TEMP_IDENTITY_CHANGED")
        # aggregate 必须最后发布；它是两文件协议唯一的完成标记。
        _publish_temp(aggregate_temp, aggregate_path)
        published[aggregate_path] = owned_temps[aggregate_temp]
        if _path_identity(aggregate_path) != owned_temps[aggregate_temp]:
            raise PasCheckerError("OUTPUT_PUBLISHED_IDENTITY_INVALID")
    except Exception:
        for final_path, identity in reversed(tuple(published.items())):
            if not _unlink_if_owned(final_path, identity):
                rollback_failed = True
        if rollback_failed:
            raise PasCheckerError("OUTPUT_PAIR_STATE_UNCERTAIN_ROLLBACK_FAILED")
        raise
    finally:
        cleanup_failed = False
        for temp_path, identity in owned_temps.items():
            if not _unlink_if_owned(temp_path, identity):
                cleanup_failed = True
        if cleanup_failed:
            if rollback_failed:
                raise PasCheckerError("OUTPUT_PAIR_AND_TEMP_STATE_UNCERTAIN")
            raise PasCheckerError("OUTPUT_TEMP_STATE_UNCERTAIN_CLEANUP_FAILED")


def _verify_published_output_pair_structure(private_path: Path, aggregate_path: Path) -> None:
    try:
        private_value = _require_mapping(
            strict_json_loads(_read_limited_file(private_path)),
            "PRIVATE_RUN_RECORD_SCHEMA_INVALID",
        )
        aggregate_value = _require_mapping(
            strict_json_loads(_read_limited_file(aggregate_path)),
            "AGGREGATE_RECEIPT_SCHEMA_INVALID",
        )
        validate_output_pair_structure(private_value, aggregate_value)
    except Exception as exc:
        raise PasCheckerError("OUTPUT_PAIR_STATE_UNCERTAIN_POST_PUBLISH_VALIDATION_FAILED") from exc


class SafeArgumentParser(argparse.ArgumentParser):
    """参数错误只返回固定错误码，不回显路径或任意输入。"""

    def error(self, _message: str) -> None:
        raise PasCheckerError("CLI_ARGUMENTS_INVALID")


def build_parser() -> argparse.ArgumentParser:
    parser = SafeArgumentParser(
        description="运行离线 HN-F1 PAS10 归一化器与 PAS12 候选数学核",
        allow_abbrev=False,
    )
    parser.add_argument(
        "--restricted-root",
        required=True,
        type=Path,
        help="由外部管理员事前验证 ACL 与单写者边界的受限目录",
    )
    parser.add_argument("--commitment", required=True, help="受限目录内的 commitment JSON 文件名")
    parser.add_argument("--snapshot", required=True, help="受限目录内的 snapshot JSON 文件名")
    parser.add_argument("--private-run-record", required=True, help="新建 ACL 私有数学运行记录 JSON 文件名")
    parser.add_argument("--aggregate-receipt", required=True, help="新建 ACL 聚合 receipt JSON 文件名")
    return parser


def main(argv: list[str] | None = None) -> int:
    try:
        args = build_parser().parse_args(argv)
        commitment_path, snapshot_path, private_path, aggregate_path = _resolve_restricted_paths(
            args.restricted_root,
            args.commitment,
            args.snapshot,
            args.private_run_record,
            args.aggregate_receipt,
        )
        commitment_bytes = _read_limited_file(commitment_path)
        snapshot_bytes = _read_limited_file(snapshot_path)
        private_run_record, aggregate_receipt = run_checker(commitment_bytes, snapshot_bytes)
        _write_output_pair(private_path, private_run_record, aggregate_path, aggregate_receipt)
        _verify_published_output_pair_structure(private_path, aggregate_path)
        print(
            json.dumps(
                {
                    "status": "completed",
                    "private_run_record_written": True,
                    "aggregate_receipt_written": True,
                    "output_pair_committed": True,
                    "authorization_evaluated": False,
                    "pas_state_computed": False,
                },
                ensure_ascii=True,
                sort_keys=True,
            )
        )
        return 0
    except PasCheckerError as exc:
        print(json.dumps({"status": "error", "error_code": exc.code}, sort_keys=True), file=sys.stderr)
        return 2
    except Exception:
        print(json.dumps({"status": "error", "error_code": "UNEXPECTED_ERROR"}, sort_keys=True), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
