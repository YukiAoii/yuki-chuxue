from __future__ import annotations

import ast
import copy
import hashlib
import importlib.util
import itertools
import json
from pathlib import Path
import random
import subprocess
import sys

import pytest


SCRIPT_PATH = Path(__file__).resolve().parents[1] / "tools" / "hnf1_pas_partition_checker.py"


def _load_checker():
    module_name = "hnf1_pas_partition_checker_under_test"
    spec = importlib.util.spec_from_file_location(module_name, SCRIPT_PATH)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    # dataclass 在解析延迟注解时需要从 sys.modules 找到所属模块。
    sys.modules[module_name] = module
    spec.loader.exec_module(module)
    return module


checker = _load_checker()
IMPLEMENTATION_SHA256 = hashlib.sha256(SCRIPT_PATH.read_bytes()).hexdigest()


WORDING_HASH = "1" * 64
SNAPSHOT_ID = "SNAPSHOT_001"
COMMITMENT_ID = "COMMITMENT_001"


def _row(
    donor_id: str,
    *,
    band: str = "UNKNOWN",
    response_status: str = "complete",
    disposition: str = "none",
    cutoff: str = "yes",
    withdrawal: str = "active",
    overrides: dict[str, str] | None = None,
) -> dict[str, str]:
    value: dict[str, str] = {
        "pas_snapshot_id": SNAPSHOT_ID,
        "screen_donor_id": donor_id,
        "screen_response_status": response_status,
        "willing_to_participate": "yes",
        "pre_recruitment_material_possible": "yes",
        "external_timestamp_evidence_possible": "yes",
        "human_primary_authorship_possible": "yes",
        "traceable_original_possible": "yes",
        "non_high_risk_possible": "yes",
        "human_internal_review_permission_intent": "yes",
        "deidentification_permission_intent": "yes",
        "recontact_permission": "yes",
        "count_response_status": "complete",
        "responded_before_cutoff": cutoff,
        "withdrawal_status": withdrawal,
        "primary_screen_disposition_code": disposition,
    }
    for field in checker.COUNT_FIELDS:
        value[f"{field}_lower_band"] = band
        value[f"{field}_upper_band"] = band
    if overrides:
        value.update(overrides)
    return value


def _snapshot(rows: list[dict[str, str]]) -> dict[str, object]:
    return {
        "schema_version": checker.SNAPSHOT_SCHEMA_VERSION,
        "pas10_version": checker.PAS10_VERSION,
        "pas_snapshot_id": SNAPSHOT_ID,
        "codebook_sha256": checker.CODEBOOK_SHA256,
        "question_wording_sha256": WORDING_HASH,
        "rows": rows,
    }


def _json_bytes(value: object) -> bytes:
    return (json.dumps(value, ensure_ascii=True, indent=2, sort_keys=True) + "\n").encode("utf-8")


def _commitment(snapshot_bytes: bytes, count: int, **limit_overrides: int) -> dict[str, object]:
    limits = {
        "max_frame_members": max(1, count),
        "max_frontier_entries": 1_000_000,
        "max_transitions": 20_000_000,
        "max_seconds": 60,
    }
    limits.update(limit_overrides)
    snapshot_value = json.loads(snapshot_bytes)
    donor_ids = [row["screen_donor_id"] for row in snapshot_value["rows"]]
    return {
        "schema_version": checker.COMMITMENT_SCHEMA_VERSION,
        "commitment_id": COMMITMENT_ID,
        "pas10_version": checker.PAS10_VERSION,
        "pas12_version": checker.PAS12_VERSION,
        "pas_snapshot_id": SNAPSHOT_ID,
        "snapshot_sha256": hashlib.sha256(snapshot_bytes).hexdigest(),
        "frame_member_set_sha256": checker.frame_member_set_sha256(donor_ids),
        "codebook_sha256": checker.CODEBOOK_SHA256,
        "pas10_machine_contract_sha256": checker.PAS10_MACHINE_CONTRACT_SHA256,
        "question_wording_sha256": WORDING_HASH,
        "expected_implementation_sha256": IMPLEMENTATION_SHA256,
        "expected_machine_schema_sha256": checker.MACHINE_SCHEMA_SHA256,
        "algorithm_version": checker.ALGORITHM_VERSION,
        "expected_frame_member_count": count,
        "resource_limits": limits,
    }


def _limits(**overrides: int):
    values = {
        "max_frame_members": 100,
        "max_frontier_entries": 1_000_000,
        "max_transitions": 20_000_000,
        "max_seconds": 30,
    }
    values.update(overrides)
    return checker.SolverLimits(**values)


def _brute_feasible(capacities: list[int], spec) -> bool:
    # 0=不用、1=P0、2=M1、3=Rtech；仅用于小 N 的独立 oracle。
    for choices in itertools.product(range(4), repeat=len(capacities)):
        counts = {batch: 0 for batch in checker.BATCH_ORDER}
        capacity_sums = {batch: 0 for batch in checker.BATCH_ORDER}
        for capacity, choice in zip(capacities, choices):
            if choice == 0 or capacity <= 0:
                continue
            batch = checker.BATCH_ORDER[choice - 1]
            counts[batch] += 1
            capacity_sums[batch] += min(capacity, spec.batch_cap[batch])
        if all(
            spec.donor_floor[batch] <= counts[batch] <= spec.node_target[batch]
            and capacity_sums[batch] >= spec.node_target[batch]
            for batch in checker.BATCH_ORDER
        ):
            return True
    return False


def _result(status: str):
    return checker.SolverResult(
        status=status,
        reason_code="TEST",
        derivation_kind="TEST",
        derivation_sha256="",
        metrics={},
        witness=None,
    )


def _valid_output_pair():
    snapshot = _snapshot([_row("DONOR_0001", band="B13_114_PLUS")])
    snapshot_bytes = _json_bytes(snapshot)
    raw_commitment = _commitment(snapshot_bytes, 1)
    commitment_bytes = _json_bytes(raw_commitment)
    return checker.run_checker(commitment_bytes, snapshot_bytes)


def test_count_band_contract_and_hash_are_stable():
    assert checker.COUNT_BANDS["B00_ZERO"] == (0, 0)
    assert checker.COUNT_BANDS["B13_114_PLUS"] == (114, 114)
    assert checker.COUNT_BANDS["UNKNOWN"] == (0, 114)
    assert checker.CODEBOOK_SHA256 == hashlib.sha256(
        checker._canonical_json_bytes(checker.CODEBOOK_CONTRACT)
    ).hexdigest()
    assert checker.MACHINE_SCHEMA_SHA256 == hashlib.sha256(
        checker._canonical_json_bytes(checker.MACHINE_CONTRACT)
    ).hexdigest()
    assert checker.OUTPUT_SCHEMA_SHA256 == hashlib.sha256(
        checker._canonical_json_bytes(checker.OUTPUT_SCHEMA_CONTRACT)
    ).hexdigest()
    assert checker.CODEBOOK_SHA256 == "74d47470f04cdb8a22d96fdebcfe33733475092f5f70bfda2792a398b763c915"
    assert checker.PAS10_MACHINE_CONTRACT_SHA256 == (
        "6a137ee5b91ce6877707d95a97ab8f0b3129328ea3d7d9131859290aa43a32cf"
    )
    assert checker.OUTPUT_SCHEMA_SHA256 == (
        "4cd44911d3691d064b496c8d2d502b4ace1af908569719f36bc755e4f4ea7752"
    )
    assert checker.MACHINE_SCHEMA_SHA256 == (
        "2fc1981966230ff9e7086a17991149004cced5b338fabcd35286aa33cc40d307"
    )
    assert checker.frame_member_set_sha256(["DONOR_0002", "DONOR_0001"]) == (
        "18869f191dfd44e221e2274cde6fd9b4741bdcbce7856ede1fadc856b7246c4e"
    )


@pytest.mark.parametrize(("band", "expected"), list(checker.COUNT_BANDS.items()))
def test_all_count_band_endpoints(band: str, expected: tuple[int, int]):
    normalized = checker.normalize_row(_row(f"BANDTEST_{band}", band=band), SNAPSHOT_ID)
    assert set(normalized.lower.values()) == {expected[0]}
    assert set(normalized.upper.values()) == {expected[1]}


def test_pas10_complete_incomplete_exclusion_and_recontact_boundary():
    complete = checker.normalize_row(
        _row("DONOR_0001", band="B05_8_11", overrides={"recontact_permission": "no"}), SNAPSHOT_ID
    )
    assert complete.eligible_lower is True
    assert complete.eligible_upper is True
    assert set(complete.lower.values()) == {8}
    assert set(complete.upper.values()) == {11}

    incomplete = checker.normalize_row(
        _row(
            "DONOR_0002",
            band="B05_8_11",
            response_status="incomplete",
            disposition="incomplete",
            cutoff="no",
        ),
        SNAPSHOT_ID,
    )
    assert incomplete.eligible_lower is False
    assert incomplete.eligible_upper is True
    assert set(incomplete.lower.values()) == {0}
    assert set(incomplete.upper.values()) == {11}

    excluded = checker.normalize_row(
        _row(
            "DONOR_0003",
            band="B13_114_PLUS",
            disposition="explicit_no",
            overrides={"non_high_risk_possible": "no"},
        ),
        SNAPSHOT_ID,
    )
    assert excluded.eligible_lower is False
    assert excluded.eligible_upper is False
    assert set(excluded.lower.values()) == {0}
    assert set(excluded.upper.values()) == {0}


def test_pas10_band_pair_and_nonresponse_rules_are_explicit():
    ranged = _row("DONOR_0004", band="B05_8_11")
    for field in checker.COUNT_FIELDS:
        ranged[f"{field}_upper_band"] = "B06_12_17"
    normalized = checker.normalize_row(ranged, SNAPSHOT_ID)
    assert set(normalized.lower.values()) == {8}
    assert set(normalized.upper.values()) == {17}

    nonresponse = _row(
        "DONOR_0005",
        response_status="pending",
        disposition="incomplete",
        cutoff="no",
        overrides={
            "count_response_status": "none",
            "willing_to_participate": "pending",
            "pre_recruitment_material_possible": "unknown",
            "external_timestamp_evidence_possible": "unknown",
            "human_primary_authorship_possible": "unknown",
            "traceable_original_possible": "unknown",
            "non_high_risk_possible": "unknown",
            "human_internal_review_permission_intent": "pending",
            "deidentification_permission_intent": "pending",
        },
    )
    normalized_nonresponse = checker.normalize_row(nonresponse, SNAPSHOT_ID)
    assert set(normalized_nonresponse.lower.values()) == {0}
    assert set(normalized_nonresponse.upper.values()) == {114}

    reversed_range = _row("DONOR_0006", band="B10_48_63")
    for field in checker.COUNT_FIELDS:
        reversed_range[f"{field}_upper_band"] = "B09_32_47"
    with pytest.raises(checker.PasCheckerError, match="COUNT_BAND_INTERVAL_INVALID"):
        checker.normalize_row(reversed_range, SNAPSHOT_ID)

    pending_with_low_band = _row(
        "DONOR_0007",
        band="B00_ZERO",
        response_status="pending",
        disposition="incomplete",
        cutoff="no",
        overrides={"count_response_status": "none"},
    )
    with pytest.raises(checker.PasCheckerError, match="COUNT_NONRESPONSE_MUST_BE_UNKNOWN"):
        checker.normalize_row(pending_with_low_band, SNAPSHOT_ID)


@pytest.mark.parametrize(
    ("changes", "expected"),
    [
        ({"screen_response_status": "withdrawn", "withdrawal_status": "withdrawn"}, "withdrawn"),
        ({"primary_screen_disposition_code": "duplicate"}, "duplicate"),
        ({"responded_before_cutoff": "no"}, "late"),
        ({"screen_response_status": "declined"}, "declined"),
        ({"traceable_original_possible": "no"}, "explicit_no"),
        ({"screen_response_status": "pending"}, "incomplete"),
    ],
)
def test_disposition_priority_matrix(changes: dict[str, str], expected: str):
    row = _row("DONOR_0100", band="B01_ONE")
    row.update(changes)
    row["primary_screen_disposition_code"] = expected
    if row["screen_response_status"] == "pending":
        row["count_response_status"] = "none"
        for field in checker.COUNT_FIELDS:
            row[f"{field}_lower_band"] = "UNKNOWN"
            row[f"{field}_upper_band"] = "UNKNOWN"
    checker.normalize_row(row, SNAPSHOT_ID)


def test_disposition_mismatch_fails_closed():
    row = _row(
        "DONOR_0101",
        band="B01_ONE",
        response_status="withdrawn",
        withdrawal="withdrawn",
        disposition="none",
    )
    with pytest.raises(checker.PasCheckerError, match="ROW_DISPOSITION_INCONSISTENT"):
        checker.normalize_row(row, SNAPSHOT_ID)


def test_joint_bound_cannot_exceed_any_diagnostic_margin():
    row = _row("DONOR_0200", band="B05_8_11")
    row["eligible_intersection_distinct_event_count_lower_band"] = "B04_6_7"
    row["eligible_intersection_distinct_event_count_upper_band"] = "B04_6_7"
    with pytest.raises(checker.PasCheckerError, match="JOINT_BOUND_EXCEEDS_MARGIN"):
        checker.normalize_row(row, SNAPSHOT_ID)


def test_snapshot_rejects_extra_fields_duplicate_donor_and_bad_hash():
    rows = [_row("DONOR_0300", band="B01_ONE")]
    snapshot = _snapshot(rows)
    snapshot_bytes = _json_bytes(snapshot)
    commitment = checker.validate_commitment(
        _commitment(snapshot_bytes, 1), implementation_sha256=IMPLEMENTATION_SHA256
    )

    extra = copy.deepcopy(snapshot)
    extra["free_text"] = "forbidden"
    with pytest.raises(checker.PasCheckerError, match="SNAPSHOT_FIELDS_INVALID"):
        checker.validate_and_normalize_snapshot(extra, commitment)

    duplicate = _snapshot([rows[0], copy.deepcopy(rows[0])])
    duplicate_bytes = _json_bytes(duplicate)
    valid_two_rows = _snapshot([rows[0], _row("DONOR_0301", band="B01_ONE")])
    duplicate_commitment_value = _commitment(_json_bytes(valid_two_rows), 2)
    duplicate_commitment_value["snapshot_sha256"] = hashlib.sha256(duplicate_bytes).hexdigest()
    duplicate_commitment = checker.validate_commitment(
        duplicate_commitment_value, implementation_sha256=IMPLEMENTATION_SHA256
    )
    with pytest.raises(checker.PasCheckerError, match="SNAPSHOT_DUPLICATE_DONOR"):
        checker.validate_and_normalize_snapshot(duplicate, duplicate_commitment)

    wrong_hash = _commitment(snapshot_bytes, 1)
    wrong_hash["codebook_sha256"] = "2" * 64
    with pytest.raises(checker.PasCheckerError, match="COMMITMENT_CODEBOOK_HASH_MISMATCH"):
        checker.validate_commitment(wrong_hash, implementation_sha256=IMPLEMENTATION_SHA256)


def test_commitment_binds_implementation_schema_algorithm_and_frame_set():
    original_snapshot = _snapshot([_row("DONOR_0310", band="B01_ONE")])
    snapshot_bytes = _json_bytes(original_snapshot)
    raw_commitment = _commitment(snapshot_bytes, 1)

    with pytest.raises(checker.PasCheckerError, match="COMMITMENT_IMPLEMENTATION_HASH_MISMATCH"):
        checker.validate_commitment(raw_commitment, implementation_sha256="f" * 64)

    wrong_schema = copy.deepcopy(raw_commitment)
    wrong_schema["expected_machine_schema_sha256"] = "e" * 64
    with pytest.raises(checker.PasCheckerError, match="COMMITMENT_MACHINE_SCHEMA_HASH_MISMATCH"):
        checker.validate_commitment(wrong_schema, implementation_sha256=IMPLEMENTATION_SHA256)

    wrong_algorithm = copy.deepcopy(raw_commitment)
    wrong_algorithm["algorithm_version"] = "pareto-dp-three-batch.invalid"
    with pytest.raises(checker.PasCheckerError, match="COMMITMENT_ALGORITHM_VERSION_MISMATCH"):
        checker.validate_commitment(wrong_algorithm, implementation_sha256=IMPLEMENTATION_SHA256)

    commitment = checker.validate_commitment(
        raw_commitment, implementation_sha256=IMPLEMENTATION_SHA256
    )
    replaced_snapshot = copy.deepcopy(original_snapshot)
    replaced_snapshot["rows"][0]["screen_donor_id"] = "DONOR_0311"
    with pytest.raises(checker.PasCheckerError, match="SNAPSHOT_FRAME_MEMBER_SET_HASH_MISMATCH"):
        checker.validate_and_normalize_snapshot(replaced_snapshot, commitment)


def test_run_checker_raw_byte_entry_rejects_snapshot_and_implementation_mismatch(monkeypatch):
    snapshot = _snapshot([_row("DONOR_0305", band="B13_114_PLUS")])
    snapshot_bytes = _json_bytes(snapshot)
    commitment = _commitment(snapshot_bytes, 1)
    commitment_bytes = _json_bytes(commitment)

    changed_snapshot = copy.deepcopy(snapshot)
    changed_snapshot["rows"][0]["eligible_joint_unique_node_count_lower_band"] = "B12_96_113"
    with pytest.raises(checker.PasCheckerError, match="SNAPSHOT_FILE_HASH_MISMATCH"):
        checker.run_checker(commitment_bytes, _json_bytes(changed_snapshot))

    wrong_implementation = copy.deepcopy(commitment)
    wrong_implementation["expected_implementation_sha256"] = "0" * 64
    with pytest.raises(checker.PasCheckerError, match="COMMITMENT_IMPLEMENTATION_HASH_MISMATCH"):
        checker.run_checker(_json_bytes(wrong_implementation), snapshot_bytes)

    original_reader = checker._read_limited_file
    implementation_reads = 0

    def changed_second_implementation_read(path):
        nonlocal implementation_reads
        data = original_reader(path)
        if Path(path).resolve() == SCRIPT_PATH.resolve():
            implementation_reads += 1
            if implementation_reads == 2:
                return data + b"\n# changed during run\n"
        return data

    monkeypatch.setattr(checker, "_read_limited_file", changed_second_implementation_read)
    with pytest.raises(checker.PasCheckerError, match="IMPLEMENTATION_CHANGED_DURING_RUN"):
        checker.run_checker(commitment_bytes, snapshot_bytes)

    with pytest.raises(checker.PasCheckerError, match="RUN_COMMITMENT_BYTES_REQUIRED"):
        checker.run_checker(bytearray(commitment_bytes), snapshot_bytes)
    with pytest.raises(checker.PasCheckerError, match="RUN_SNAPSHOT_BYTES_REQUIRED"):
        checker.run_checker(commitment_bytes, bytearray(snapshot_bytes))

    monkeypatch.setattr(checker, "MAX_INPUT_BYTES", 4)
    with pytest.raises(checker.PasCheckerError, match="INPUT_TOO_LARGE"):
        checker.run_checker(b"12345", b"{}")


def test_strict_json_rejects_duplicate_keys_bom_and_nonfinite_values():
    with pytest.raises(checker.PasCheckerError, match="JSON_DUPLICATE_KEY"):
        checker.strict_json_loads(b'{"a":1,"a":2}')
    with pytest.raises(checker.PasCheckerError, match="INPUT_UTF8_BOM_FORBIDDEN"):
        checker.strict_json_loads(b"\xef\xbb\xbf{}")
    with pytest.raises(checker.PasCheckerError, match="JSON_NON_FINITE_NUMBER"):
        checker.strict_json_loads(b'{"a":NaN}')


def test_exact_solver_finds_fixed_contract_feasible_witness():
    capacities = [8] * 18 + [32] * 18 + [6] * 8
    donors = [checker.CapacityDonor(f"DONOR_{index:04d}", capacity) for index, capacity in enumerate(capacities)]
    spec = checker.PartitionSpec(checker.NODE_TARGET, checker.PLANNING_DONOR_FLOOR, checker.BATCH_CAP)

    result = checker.solve_exact_partition(donors, spec, _limits(), COMMITMENT_ID)

    assert result.status == "feasible"
    assert result.reason_code == "EXACT_WITNESS_VERIFIED"
    assert result.witness is not None
    assert checker.verify_witness(result.witness, donors, spec, COMMITMENT_ID)
    assert result.witness["batch_summary"] == {
        "P0": {"assigned_donor_count": 18, "allocated_nodes": 144},
        "M1": {"assigned_donor_count": 18, "allocated_nodes": 576},
        "Rtech": {"assigned_donor_count": 8, "allocated_nodes": 48},
    }


def test_exact_solver_analytic_infeasibility_is_not_resource_failure():
    donors = [checker.CapacityDonor(f"DONOR_{index:04d}", 114) for index in range(37)]
    spec = checker.PartitionSpec(checker.NODE_TARGET, checker.HARD_DONOR_FLOOR, checker.BATCH_CAP)

    result = checker.solve_exact_partition(donors, spec, _limits(), COMMITMENT_ID)

    assert result.status == "infeasible"
    assert result.reason_code == "ANALYTIC_DONOR_FLOOR_IMPOSSIBLE"
    assert result.derivation_kind == "EXACT_ANALYTIC_NECESSARY_CONDITION"


def test_fixed_contract_ub_only_and_total_capacity_boundary():
    ub_only_capacities = [10] * 15 + [39] * 15 + [6] * 8
    ub_only = [
        checker.CapacityDonor(f"UBONLY_{index:04d}", capacity)
        for index, capacity in enumerate(ub_only_capacities)
    ]
    ub_spec = checker.PartitionSpec(checker.NODE_TARGET, checker.HARD_DONOR_FLOOR, checker.BATCH_CAP)
    lb_spec = checker.PartitionSpec(checker.NODE_TARGET, checker.PLANNING_DONOR_FLOOR, checker.BATCH_CAP)
    ub_result = checker.solve_exact_partition(ub_only, ub_spec, _limits(), COMMITMENT_ID)
    lb_result = checker.solve_exact_partition(ub_only, lb_spec, _limits(), COMMITMENT_ID)
    assert ub_result.status == "feasible"
    assert lb_result.status == "infeasible"
    assert checker.classify_mathematical_outcome(lb_result, ub_result) == "UB_ONLY_EXACT_FEASIBLE"

    insufficient = [checker.CapacityDonor(f"LOWTOTAL_{index:04d}", 17) for index in range(44)]
    total_result = checker.solve_exact_partition(insufficient, lb_spec, _limits(), COMMITMENT_ID)
    assert total_result.status == "infeasible"
    assert total_result.reason_code == "ANALYTIC_TOTAL_CAPACITY_IMPOSSIBLE"


def test_fixed_contract_nonanalytic_no_go_and_high_capacity_success():
    planning_spec = checker.PartitionSpec(
        checker.NODE_TARGET, checker.PLANNING_DONOR_FLOOR, checker.BATCH_CAP
    )
    hard_spec = checker.PartitionSpec(checker.NODE_TARGET, checker.HARD_DONOR_FLOOR, checker.BATCH_CAP)
    concentrated = [checker.CapacityDonor(f"CAP25_{index:04d}", 25) for index in range(44)]
    no_go = checker.solve_exact_partition(concentrated, planning_spec, _limits(), COMMITMENT_ID)
    assert no_go.status == "infeasible"
    assert no_go.reason_code == "EXHAUSTIVE_PARETO_FRONTIER_INFEASIBLE"

    abundant = [checker.CapacityDonor(f"CAP114_{index:04d}", 114) for index in range(44)]
    planning = checker.solve_exact_partition(abundant, planning_spec, _limits(), COMMITMENT_ID)
    hard = checker.solve_exact_partition(abundant, hard_spec, _limits(), COMMITMENT_ID)
    assert planning.status == hard.status == "feasible"
    assert planning.metrics["processed_donors"] < planning.metrics["available_donors"] == 44
    assert planning.witness is not None and checker.verify_witness(
        planning.witness, abundant, planning_spec, COMMITMENT_ID
    )
    assert hard.witness is not None and checker.verify_witness(
        hard.witness, abundant, hard_spec, COMMITMENT_ID
    )


def test_exact_solver_matches_small_bruteforce_oracle():
    spec = checker.PartitionSpec(
        node_target={"P0": 3, "M1": 4, "Rtech": 2},
        donor_floor={"P0": 1, "M1": 1, "Rtech": 1},
        batch_cap={"P0": 2, "M1": 3, "Rtech": 1},
    )
    rng = random.Random(20260803)
    profiles = [[rng.randrange(0, 5) for _ in range(6)] for _ in range(40)]
    profiles.extend([[2, 2, 3, 3, 1, 1], [4, 4, 4, 1, 1, 1], [0, 0, 0, 0, 0, 0]])

    for profile in profiles:
        donors = [checker.CapacityDonor(f"TESTDONOR_{index:02d}", value) for index, value in enumerate(profile)]
        try:
            result = checker.solve_exact_partition(donors, spec, _limits(max_seconds=5), COMMITMENT_ID)
        except checker.PasCheckerError as exc:
            pytest.fail(f"profile={profile}, error={exc.code}")
        assert result.status != "error"
        assert (result.status == "feasible") is _brute_feasible(profile, spec), profile


def test_capacity_reaching_target_before_floor_still_adds_donors():
    spec = checker.PartitionSpec(
        node_target={"P0": 4, "M1": 4, "Rtech": 2},
        donor_floor={"P0": 3, "M1": 2, "Rtech": 2},
        batch_cap={"P0": 4, "M1": 4, "Rtech": 2},
    )
    capacities = [4, 1, 1, 4, 1, 2, 1]
    donors = [checker.CapacityDonor(f"FLOORCASE_{index:02d}", value) for index, value in enumerate(capacities)]

    result = checker.solve_exact_partition(donors, spec, _limits(), COMMITMENT_ID)

    assert result.status == "feasible"
    assert result.witness is not None
    assert checker.verify_witness(result.witness, donors, spec, COMMITMENT_ID)


def test_m1_cap_is_used_consistently_in_pareto_and_witness():
    spec = checker.PartitionSpec(
        node_target={"P0": 8, "M1": 8, "Rtech": 0},
        donor_floor={"P0": 2, "M1": 2, "Rtech": 0},
        batch_cap={"P0": 7, "M1": 5, "Rtech": 0},
    )
    donors = [
        checker.CapacityDonor(f"MCAPCASE_{index:02d}", value)
        for index, value in enumerate([4, 4, 7, 2])
    ]

    result = checker.solve_exact_partition(donors, spec, _limits(), COMMITMENT_ID)

    assert result.status == "feasible"
    assert result.witness is not None
    assert checker.verify_witness(result.witness, donors, spec, COMMITMENT_ID)


def test_zero_target_batch_does_not_gain_phantom_donor():
    spec = checker.PartitionSpec(
        node_target={"P0": 5, "M1": 0, "Rtech": 0},
        donor_floor={"P0": 2, "M1": 0, "Rtech": 0},
        batch_cap={"P0": 1, "M1": 5, "Rtech": 4},
    )
    donors = [
        checker.CapacityDonor(f"ZEROTARGET_{index:02d}", value)
        for index, value in enumerate([5, 6, 1, 1, 4, 3, 2])
    ]
    result = checker.solve_exact_partition(donors, spec, _limits(), COMMITMENT_ID)
    assert result.status == "feasible"
    assert result.witness is not None
    assert result.witness["batch_summary"]["M1"] == {
        "assigned_donor_count": 0,
        "allocated_nodes": 0,
    }
    assert checker.verify_witness(result.witness, donors, spec, COMMITMENT_ID)


def test_pareto_pruning_keeps_count_capacity_tradeoff():
    state = (1, 1, 1, 1)
    candidates = {
        state: {
            1: checker.FrontierEntry(diverted_capacity=10, trace=None),
            2: checker.FrontierEntry(diverted_capacity=2, trace=None),
            3: checker.FrontierEntry(diverted_capacity=12, trace=None),
        }
    }

    pruned = checker._prune_candidates(candidates)

    assert set(pruned[state]) == {1, 2}


def test_solver_is_permutation_invariant_and_witness_is_reproducible():
    capacities = [8] * 18 + [32] * 18 + [6] * 8
    donors = [checker.CapacityDonor(f"DONOR_{index:04d}", capacity) for index, capacity in enumerate(capacities)]
    spec = checker.PartitionSpec(checker.NODE_TARGET, checker.PLANNING_DONOR_FLOOR, checker.BATCH_CAP)

    forward = checker.solve_exact_partition(donors, spec, _limits(), COMMITMENT_ID)
    reverse = checker.solve_exact_partition(list(reversed(donors)), spec, _limits(), COMMITMENT_ID)

    assert forward.status == reverse.status == "feasible"
    assert forward.derivation_sha256 == reverse.derivation_sha256
    assert forward.witness == reverse.witness


def test_resource_limit_never_becomes_infeasible():
    donors = [checker.CapacityDonor(f"DONOR_{index:04d}", 114) for index in range(44)]
    spec = checker.PartitionSpec(checker.NODE_TARGET, checker.PLANNING_DONOR_FLOOR, checker.BATCH_CAP)

    result = checker.solve_exact_partition(donors, spec, _limits(max_transitions=1), COMMITMENT_ID)

    assert result.status == "error"
    assert result.reason_code == "RESOURCE_TRANSITION_LIMIT_EXCEEDED"
    assert result.derivation_kind == "NONE_RESOURCE_INCOMPLETE"

    frontier_result = checker.solve_exact_partition(
        donors, spec, _limits(max_frontier_entries=1), COMMITMENT_ID
    )
    assert frontier_result.status == "error"
    assert frontier_result.reason_code == "RESOURCE_FRONTIER_LIMIT_EXCEEDED"

    expired_result = checker.solve_exact_partition(
        donors, spec, _limits(), COMMITMENT_ID, deadline=-1.0
    )
    assert expired_result.status == "error"
    assert expired_result.reason_code == "RESOURCE_SECONDS_EXCEEDED"


def test_run_checker_shares_one_absolute_deadline(monkeypatch):
    snapshot = _snapshot([_row("DONOR_0001", band="B13_114_PLUS")])
    snapshot_bytes = _json_bytes(snapshot)
    raw_commitment = _commitment(snapshot_bytes, 1)
    captured_deadlines: list[float] = []
    fake_result = checker.SolverResult(
        status="infeasible",
        reason_code="ANALYTIC_DONOR_FLOOR_IMPOSSIBLE",
        derivation_kind="EXACT_ANALYTIC_NECESSARY_CONDITION",
        derivation_sha256="a" * 64,
        metrics={"processed_donors": 0, "transitions": 0, "peak_frontier_entries": 1},
        witness=None,
    )

    def fake_safe_solve(_donors, _spec, _limits_value, _commitment_id, deadline):
        captured_deadlines.append(deadline)
        return fake_result

    monkeypatch.setattr(checker.time, "monotonic", lambda: 100.0)
    monkeypatch.setattr(checker, "_safe_solve_partition", fake_safe_solve)
    _, aggregate = checker.run_checker(_json_bytes(raw_commitment), snapshot_bytes)

    assert captured_deadlines == [160.0, 160.0]
    assert captured_deadlines[0] is captured_deadlines[1]
    assert aggregate["mathematical_outcome"] == "UB_EXACT_INFEASIBLE"


def test_terminal_work_crossing_deadline_never_returns_terminal_result(monkeypatch):
    clock = [0.0]
    monkeypatch.setattr(checker.time, "monotonic", lambda: clock[0])

    original_analytic_digest = checker._analytic_digest

    def crossing_analytic_digest(*args, **kwargs):
        result = original_analytic_digest(*args, **kwargs)
        clock[0] = 2.0
        return result

    monkeypatch.setattr(checker, "_analytic_digest", crossing_analytic_digest)
    analytic = checker.solve_exact_partition(
        [checker.CapacityDonor(f"ANALYTIC_{index:04d}", 114) for index in range(37)],
        checker.PartitionSpec(checker.NODE_TARGET, checker.HARD_DONOR_FLOOR, checker.BATCH_CAP),
        _limits(),
        COMMITMENT_ID,
        deadline=1.0,
    )
    assert (analytic.status, analytic.reason_code, analytic.derivation_kind, analytic.witness) == (
        "error",
        "RESOURCE_SECONDS_EXCEEDED",
        "NONE_RESOURCE_INCOMPLETE",
        None,
    )

    monkeypatch.setattr(checker, "_analytic_digest", original_analytic_digest)
    clock[0] = 0.0
    original_frontier_digest = checker._frontier_digest

    def crossing_frontier_digest(*args, **kwargs):
        result = original_frontier_digest(*args, **kwargs)
        clock[0] = 2.0
        return result

    monkeypatch.setattr(checker, "_frontier_digest", crossing_frontier_digest)
    exhaustive = checker.solve_exact_partition(
        [checker.CapacityDonor(f"EXHAUSTIVE_{index:04d}", 25) for index in range(44)],
        checker.PartitionSpec(checker.NODE_TARGET, checker.PLANNING_DONOR_FLOOR, checker.BATCH_CAP),
        _limits(),
        COMMITMENT_ID,
        deadline=1.0,
    )
    assert (exhaustive.status, exhaustive.reason_code, exhaustive.derivation_kind, exhaustive.witness) == (
        "error",
        "RESOURCE_SECONDS_EXCEEDED",
        "NONE_RESOURCE_INCOMPLETE",
        None,
    )

    monkeypatch.setattr(checker, "_frontier_digest", original_frontier_digest)
    clock[0] = 0.0
    original_verify_witness = checker.verify_witness

    def crossing_verify_witness(*args, **kwargs):
        result = original_verify_witness(*args, **kwargs)
        clock[0] = 2.0
        return result

    monkeypatch.setattr(checker, "verify_witness", crossing_verify_witness)
    feasible = checker.solve_exact_partition(
        [checker.CapacityDonor(f"FEASIBLE_{index:04d}", 114) for index in range(44)],
        checker.PartitionSpec(checker.NODE_TARGET, checker.PLANNING_DONOR_FLOOR, checker.BATCH_CAP),
        _limits(),
        COMMITMENT_ID,
        deadline=1.0,
    )
    assert (feasible.status, feasible.reason_code, feasible.derivation_kind, feasible.witness) == (
        "error",
        "RESOURCE_SECONDS_EXCEEDED",
        "NONE_RESOURCE_INCOMPLETE",
        None,
    )


def test_memory_and_escaped_deadline_fail_as_resource_errors(monkeypatch):
    donors = [checker.CapacityDonor(f"RESOURCE_{index:04d}", 114) for index in range(44)]
    spec = checker.PartitionSpec(checker.NODE_TARGET, checker.PLANNING_DONOR_FLOOR, checker.BATCH_CAP)

    def raise_memory(*_args, **_kwargs):
        raise MemoryError

    monkeypatch.setattr(checker, "_frontier_digest", raise_memory)
    memory_result = checker.solve_exact_partition(donors, spec, _limits(), COMMITMENT_ID)
    assert (memory_result.status, memory_result.reason_code, memory_result.derivation_kind) == (
        "error",
        "RESOURCE_MEMORY_EXHAUSTED",
        "NONE_RESOURCE_INCOMPLETE",
    )

    def raise_budget(*_args, **_kwargs):
        raise checker.SolverBudgetExceeded("RESOURCE_SECONDS_EXCEEDED")

    monkeypatch.setattr(checker, "solve_exact_partition", raise_budget)
    escaped = checker._safe_solve_partition(donors, spec, _limits(), COMMITMENT_ID, 1.0)
    assert (escaped.status, escaped.reason_code, escaped.derivation_kind) == (
        "error",
        "RESOURCE_SECONDS_EXCEEDED",
        "NONE_RESOURCE_INCOMPLETE",
    )


def test_independent_witness_verifier_rejects_tampering():
    capacities = [8] * 18 + [32] * 18 + [6] * 8
    donors = [checker.CapacityDonor(f"DONOR_{index:04d}", capacity) for index, capacity in enumerate(capacities)]
    spec = checker.PartitionSpec(checker.NODE_TARGET, checker.PLANNING_DONOR_FLOOR, checker.BATCH_CAP)
    result = checker.solve_exact_partition(donors, spec, _limits(), COMMITMENT_ID)
    assert result.witness is not None
    tampered = copy.deepcopy(result.witness)
    tampered["assignments"][0]["allocated_nodes"] = 999

    assert checker.verify_witness(tampered, donors, spec, COMMITMENT_ID) is False
    for field, replacement in (
        ("commitment_id", "COMMITMENT_999"),
        ("ordering", "untrusted-order"),
    ):
        metadata_tamper = copy.deepcopy(result.witness)
        metadata_tamper[field] = replacement
        assert checker.verify_witness(metadata_tamper, donors, spec, COMMITMENT_ID) is False
    summary_tamper = copy.deepcopy(result.witness)
    summary_tamper["batch_summary"]["P0"]["allocated_nodes"] = 0
    assert checker.verify_witness(summary_tamper, donors, spec, COMMITMENT_ID) is False


def test_independent_witness_verifier_rejects_boolean_summary_integers():
    spec = checker.PartitionSpec(
        node_target={"P0": 1, "M1": 1, "Rtech": 1},
        donor_floor={"P0": 1, "M1": 1, "Rtech": 1},
        batch_cap={"P0": 1, "M1": 1, "Rtech": 1},
    )
    donors = [checker.CapacityDonor(f"BOOL_{index:02d}", 1) for index in range(3)]
    result = checker.solve_exact_partition(donors, spec, _limits(), COMMITMENT_ID)
    assert result.witness is not None
    assert checker.verify_witness(result.witness, donors, spec, COMMITMENT_ID)

    tampered = copy.deepcopy(result.witness)
    tampered["batch_summary"]["P0"] = {"assigned_donor_count": True, "allocated_nodes": True}
    assert checker.verify_witness(tampered, donors, spec, COMMITMENT_ID) is False


@pytest.mark.parametrize(
    ("lb", "ub", "expected"),
    [
        ("feasible", "infeasible", "INPUT_OR_IMPLEMENTATION_INVALID"),
        ("infeasible", "infeasible", "UB_EXACT_INFEASIBLE"),
        ("feasible", "feasible", "LB_AND_UB_EXACT_FEASIBLE"),
        ("infeasible", "feasible", "UB_ONLY_EXACT_FEASIBLE"),
        ("error", "infeasible", "MATHEMATICAL_INCONCLUSIVE"),
    ],
)
def test_lb_ub_truth_table(lb: str, ub: str, expected: str):
    assert checker.classify_mathematical_outcome(_result(lb), _result(ub)) == expected


def test_cli_writes_acl_pair_without_donor_ids_in_aggregate(tmp_path):
    restricted_root = tmp_path / "acl"
    restricted_root.mkdir()
    rows = [_row(f"DONOR_{index:04d}", band="B13_114_PLUS") for index in range(37)]
    snapshot = _snapshot(rows)
    snapshot_bytes = _json_bytes(snapshot)
    commitment = _commitment(snapshot_bytes, len(rows))
    (restricted_root / "commitment.json").write_bytes(_json_bytes(commitment))
    (restricted_root / "snapshot.json").write_bytes(snapshot_bytes)

    completed = subprocess.run(
        [
            sys.executable,
            str(SCRIPT_PATH),
            "--restricted-root",
            str(restricted_root),
            "--commitment",
            "commitment.json",
            "--snapshot",
            "snapshot.json",
            "--private-run-record",
            "private.json",
            "--aggregate-receipt",
            "aggregate.json",
        ],
        capture_output=True,
        check=False,
        text=True,
        timeout=30,
    )

    assert completed.returncode == 0
    assert completed.stderr == ""
    console = json.loads(completed.stdout)
    assert console["authorization_evaluated"] is False
    assert console["pas_state_computed"] is False
    assert "mathematical_outcome" not in console
    assert "DONOR_" not in completed.stdout
    aggregate_text = (restricted_root / "aggregate.json").read_text(encoding="utf-8")
    aggregate = json.loads(aggregate_text)
    for index in range(37):
        assert f"DONOR_{index:04d}" not in aggregate_text
    assert aggregate["contains_direct_donor_id_fields"] is False
    assert aggregate["pas13_public_projection_created"] is False
    assert aggregate["pair_commit_protocol"] == "aggregate-last.v1"
    assert aggregate["pair_complete"] is True
    assert aggregate["mathematical_outcome"] == "UB_EXACT_INFEASIBLE"
    private = json.loads((restricted_root / "private.json").read_text(encoding="utf-8"))
    assert aggregate["private_run_record_canonical_sha256"] == hashlib.sha256(
        checker._canonical_json_bytes(private)
    ).hexdigest()
    assert aggregate["scope"] == "ACL_ONLY_NOT_PAS13_PUBLIC"
    assert aggregate["authorization_evaluated"] is False
    assert aggregate["pas_state_computed"] is False
    checker._verify_published_output_pair_structure(
        restricted_root / "private.json",
        restricted_root / "aggregate.json",
    )


def test_cli_error_does_not_echo_sensitive_row_or_create_outputs(tmp_path):
    restricted_root = tmp_path / "acl"
    restricted_root.mkdir()
    donor_id = "SENSITIVE_DONOR_0001"
    snapshot = _snapshot([_row(donor_id, band="B01_ONE")])
    snapshot["rows"][0]["forbidden_free_text"] = "private words"
    snapshot_bytes = _json_bytes(snapshot)
    commitment = _commitment(snapshot_bytes, 1)
    (restricted_root / "commitment.json").write_bytes(_json_bytes(commitment))
    (restricted_root / "snapshot.json").write_bytes(snapshot_bytes)

    completed = subprocess.run(
        [
            sys.executable,
            str(SCRIPT_PATH),
            "--restricted-root",
            str(restricted_root),
            "--commitment",
            "commitment.json",
            "--snapshot",
            "snapshot.json",
            "--private-run-record",
            "private.json",
            "--aggregate-receipt",
            "aggregate.json",
        ],
        capture_output=True,
        check=False,
        text=True,
        timeout=30,
    )

    assert completed.returncode == 2
    assert donor_id not in completed.stderr
    assert "private words" not in completed.stderr
    assert json.loads(completed.stderr)["error_code"] == "ROW_FIELDS_INVALID"
    assert not (restricted_root / "private.json").exists()
    assert not (restricted_root / "aggregate.json").exists()


def test_cli_completed_exit_code_does_not_encode_inconclusive_outcome(tmp_path):
    restricted_root = tmp_path / "acl"
    restricted_root.mkdir()
    rows = [_row(f"DONOR_{index:04d}", band="B13_114_PLUS") for index in range(44)]
    snapshot = _snapshot(rows)
    snapshot_bytes = _json_bytes(snapshot)
    commitment = _commitment(snapshot_bytes, len(rows), max_transitions=1)
    (restricted_root / "commitment.json").write_bytes(_json_bytes(commitment))
    (restricted_root / "snapshot.json").write_bytes(snapshot_bytes)

    completed = subprocess.run(
        [
            sys.executable,
            str(SCRIPT_PATH),
            "--restricted-root",
            str(restricted_root),
            "--commitment",
            "commitment.json",
            "--snapshot",
            "snapshot.json",
            "--private-run-record",
            "private.json",
            "--aggregate-receipt",
            "aggregate.json",
        ],
        capture_output=True,
        check=False,
        text=True,
        timeout=30,
    )

    assert completed.returncode == 0
    assert "mathematical_outcome" not in completed.stdout
    aggregate = json.loads((restricted_root / "aggregate.json").read_text(encoding="utf-8"))
    assert aggregate["mathematical_outcome"] == "MATHEMATICAL_INCONCLUSIVE"


def test_cli_rejects_repository_as_restricted_root():
    repo_root = SCRIPT_PATH.parents[1]
    with pytest.raises(checker.PasCheckerError, match="RESTRICTED_ROOT_OVERLAPS_REPOSITORY"):
        checker._resolve_restricted_paths(
            repo_root,
            "commitment.json",
            "snapshot.json",
            "private.json",
            "aggregate.json",
        )


def test_cli_rejects_obvious_desktop_sync_and_git_roots(tmp_path):
    desktop_root = tmp_path / "Desktop" / "acl"
    desktop_root.mkdir(parents=True)
    with pytest.raises(checker.PasCheckerError, match="RESTRICTED_ROOT_OBVIOUSLY_SYNCED_OR_DESKTOP"):
        checker._resolve_restricted_paths(
            desktop_root,
            "commitment.json",
            "snapshot.json",
            "private.json",
            "aggregate.json",
        )

    git_root = tmp_path / "secure"
    git_root.mkdir()
    (git_root / ".git").mkdir()
    with pytest.raises(checker.PasCheckerError, match="RESTRICTED_ROOT_OVERLAPS_GIT_WORKTREE"):
        checker._resolve_restricted_paths(
            git_root,
            "commitment.json",
            "snapshot.json",
            "private.json",
            "aggregate.json",
        )


def test_limited_reader_and_output_pair_fail_closed(monkeypatch, tmp_path):
    oversized = tmp_path / "oversized.json"
    oversized.write_bytes(b"12345")
    # 只在 limited-reader 断言内缩小上限；后面的合法 output pair 仍需按
    # 生产上限构造，否则 helper 自己会被残留的 4-byte 上限误伤。
    with monkeypatch.context() as reader_patch:
        reader_patch.setattr(checker, "MAX_INPUT_BYTES", 4)
        with pytest.raises(checker.PasCheckerError, match="INPUT_TOO_LARGE"):
            checker._read_limited_file(oversized)

    private_path = tmp_path / "private.json"
    aggregate_path = tmp_path / "aggregate.json"
    private_run_record, aggregate_receipt = _valid_output_pair()
    original_publish = checker._publish_temp
    calls = 0

    def fail_second_publish(temp_path, final_path):
        nonlocal calls
        calls += 1
        if calls == 2:
            raise checker.PasCheckerError("TEST_SECOND_PUBLISH_FAILED")
        original_publish(temp_path, final_path)

    monkeypatch.setattr(checker, "_publish_temp", fail_second_publish)
    with pytest.raises(checker.PasCheckerError, match="TEST_SECOND_PUBLISH_FAILED"):
        checker._write_output_pair(private_path, private_run_record, aggregate_path, aggregate_receipt)
    assert not private_path.exists()
    assert not aggregate_path.exists()
    assert list(tmp_path.glob(".hnf1pas-*.tmp")) == []


def test_output_pair_validator_rejects_transplant_and_witness_schema_tampering():
    private_run_record, aggregate_receipt = _valid_output_pair()
    checker.validate_output_pair_structure(private_run_record, aggregate_receipt)

    transplant = copy.deepcopy(aggregate_receipt)
    transplant["commitment_id"] = "COMMITMENT_999"
    with pytest.raises(checker.PasCheckerError, match="OUTPUT_PAIR_COMMON_FIELDS_MISMATCH"):
        checker.validate_output_pair_structure(private_run_record, transplant)

    false_outcome_private = copy.deepcopy(private_run_record)
    false_outcome_aggregate = copy.deepcopy(aggregate_receipt)
    false_outcome_private["mathematical_outcome"] = "LB_AND_UB_EXACT_FEASIBLE"
    false_outcome_aggregate["mathematical_outcome"] = "LB_AND_UB_EXACT_FEASIBLE"
    false_outcome_aggregate["private_run_record_canonical_sha256"] = hashlib.sha256(
        checker._canonical_json_bytes(false_outcome_private)
    ).hexdigest()
    with pytest.raises(checker.PasCheckerError, match="OUTPUT_PAIR_MATHEMATICAL_OUTCOME_MISMATCH"):
        checker.validate_output_pair_structure(false_outcome_private, false_outcome_aggregate)

    capacities = [8] * 18 + [32] * 18 + [6] * 8
    donors = [checker.CapacityDonor(f"SCHEMA_{index:04d}", capacity) for index, capacity in enumerate(capacities)]
    spec = checker.PartitionSpec(checker.NODE_TARGET, checker.PLANNING_DONOR_FLOOR, checker.BATCH_CAP)
    result = checker.solve_exact_partition(donors, spec, _limits(), COMMITMENT_ID)
    assert result.witness is not None
    duplicate = copy.deepcopy(result.witness)
    duplicate["assignments"][1]["screen_donor_id"] = duplicate["assignments"][0]["screen_donor_id"]
    with pytest.raises(checker.PasCheckerError, match="OUTPUT_WITNESS_SCHEMA_INVALID"):
        checker._validate_witness_schema(duplicate, expected_commitment_id=COMMITMENT_ID)


def test_output_temp_cleanup_failure_is_never_silent(monkeypatch, tmp_path):
    private_run_record, aggregate_receipt = _valid_output_pair()
    original_unlink = checker._unlink_if_owned

    def fail_temp_cleanup(path, identity):
        if path.suffix == ".tmp":
            return False
        return original_unlink(path, identity)

    monkeypatch.setattr(checker, "_unlink_if_owned", fail_temp_cleanup)
    successful_private = tmp_path / "successful-private.json"
    successful_aggregate = tmp_path / "successful-aggregate.json"
    with pytest.raises(checker.PasCheckerError, match="OUTPUT_TEMP_STATE_UNCERTAIN_CLEANUP_FAILED"):
        checker._write_output_pair(
            successful_private,
            private_run_record,
            successful_aggregate,
            aggregate_receipt,
        )
    assert successful_private.exists()
    assert successful_aggregate.exists()

    original_publish = checker._publish_temp
    publish_calls = 0

    def fail_second_publish(temp_path, final_path):
        nonlocal publish_calls
        publish_calls += 1
        if publish_calls == 2:
            raise checker.PasCheckerError("TEST_SECOND_PUBLISH_FAILED")
        original_publish(temp_path, final_path)

    monkeypatch.setattr(checker, "_publish_temp", fail_second_publish)
    failed_private = tmp_path / "failed-private.json"
    failed_aggregate = tmp_path / "failed-aggregate.json"
    with pytest.raises(checker.PasCheckerError, match="OUTPUT_TEMP_STATE_UNCERTAIN_CLEANUP_FAILED"):
        checker._write_output_pair(
            failed_private,
            private_run_record,
            failed_aggregate,
            aggregate_receipt,
        )
    assert not failed_private.exists()
    assert not failed_aggregate.exists()

    publish_calls = 0
    monkeypatch.setattr(checker, "_unlink_if_owned", lambda _path, _identity: False)
    uncertain_private = tmp_path / "uncertain-private.json"
    uncertain_aggregate = tmp_path / "uncertain-aggregate.json"
    with pytest.raises(checker.PasCheckerError, match="OUTPUT_PAIR_AND_TEMP_STATE_UNCERTAIN"):
        checker._write_output_pair(
            uncertain_private,
            private_run_record,
            uncertain_aggregate,
            aggregate_receipt,
        )
    assert uncertain_private.exists()
    assert not uncertain_aggregate.exists()


def test_aggregate_makes_only_direct_donor_field_claim_when_opaque_values_collide():
    snapshot = _snapshot([_row(COMMITMENT_ID, band="B13_114_PLUS")])
    snapshot_bytes = _json_bytes(snapshot)
    raw_commitment = _commitment(snapshot_bytes, 1)
    _, aggregate = checker.run_checker(_json_bytes(raw_commitment), snapshot_bytes)
    serialized = json.dumps(aggregate, ensure_ascii=True, sort_keys=True)
    assert COMMITMENT_ID in serialized
    assert aggregate["contains_direct_donor_id_fields"] is False
    assert "screen_donor_id" not in serialized
    assert "assignments" not in serialized


def test_preexisting_incomplete_pair_and_temp_are_not_silently_deleted(monkeypatch, tmp_path):
    restricted_root = tmp_path / "acl"
    restricted_root.mkdir()
    for name in ("commitment.json", "snapshot.json"):
        (restricted_root / name).write_text("{}", encoding="utf-8")
    (restricted_root / "private.json").write_text("partial", encoding="utf-8")
    with pytest.raises(checker.PasCheckerError, match="OUTPUT_INCOMPLETE_PAIR_PRESENT"):
        checker._resolve_restricted_paths(
            restricted_root,
            "commitment.json",
            "snapshot.json",
            "private.json",
            "aggregate.json",
        )
    assert (restricted_root / "private.json").read_text(encoding="utf-8") == "partial"

    private_path = tmp_path / "new-private.json"
    aggregate_path = tmp_path / "new-aggregate.json"
    occupied = tmp_path / ".hnf1pas-private-fixed.tmp"
    occupied.write_text("owned elsewhere", encoding="utf-8")
    monkeypatch.setattr(checker.secrets, "token_hex", lambda _length: "fixed")
    private_run_record, aggregate_receipt = _valid_output_pair()
    with pytest.raises(checker.PasCheckerError, match="OUTPUT_ALREADY_EXISTS"):
        checker._write_output_pair(private_path, private_run_record, aggregate_path, aggregate_receipt)
    assert occupied.read_text(encoding="utf-8") == "owned elsewhere"
    assert not private_path.exists()
    assert not aggregate_path.exists()


def test_cli_argument_errors_are_fixed_and_do_not_echo_input(tmp_path):
    secret_path = str(tmp_path / "SENSITIVE_PATH")
    completed = subprocess.run(
        [sys.executable, str(SCRIPT_PATH), "--restricted-r", secret_path],
        capture_output=True,
        check=False,
        text=True,
        timeout=30,
    )
    assert completed.returncode == 2
    assert secret_path not in completed.stderr
    assert json.loads(completed.stderr)["error_code"] == "CLI_ARGUMENTS_INVALID"


def test_tool_has_no_network_model_runtime_or_environment_access():
    source = SCRIPT_PATH.read_text(encoding="utf-8")
    forbidden_fragments = (
        "import socket",
        "import requests",
        "import httpx",
        "import urllib",
        "import aiohttp",
        "import openai",
        "import subprocess",
        "os.environ",
        "load_config",
        "BucketManager",
        "LegacyRuntime",
    )
    for fragment in forbidden_fragments:
        assert fragment not in source

    tree = ast.parse(source)
    allowed_import_roots = {
        "__future__",
        "argparse",
        "dataclasses",
        "hashlib",
        "json",
        "os",
        "pathlib",
        "re",
        "secrets",
        "stat",
        "sys",
        "time",
        "typing",
    }
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            assert all(alias.name.split(".")[0] in allowed_import_roots for alias in node.names)
        if isinstance(node, ast.ImportFrom):
            assert (node.module or "").split(".")[0] in allowed_import_roots
        if isinstance(node, ast.Call) and isinstance(node.func, ast.Name):
            assert node.func.id not in {"eval", "exec", "compile", "__import__"}
        if isinstance(node, ast.Attribute):
            assert node.attr not in {"environ", "getenv"}
