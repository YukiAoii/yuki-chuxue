"""
========================================
web/onboarding.py — 首次部署向导页面与 API
========================================

提供独立 `/onboarding` 页面，以及部署模式目录、当前实际配置报告、预检和保存接口。
配置仍写入现有 config.yaml；本模块只编排，不创建第二套配置真源。

不做什么：不保存密码、不管理 OAuth token、不直接重启服务、不修改平台环境变量。
对外暴露：register(mcp)。
========================================
"""

from __future__ import annotations

import logging
import os
from typing import Any, Mapping

import yaml
from starlette.requests import Request
from starlette.responses import Response

from ombrebrain.security.deployment_profile import (
    assess_mcp_network_safety,
    build_profile_patch,
    effective_configuration_report,
    mcp_network_safety_issue,
    profile_catalog,
    validate_profile_patch,
)
from utils import atomic_update_config_yaml, config_file_path, read_config_yaml

from . import _shared as sh

logger = logging.getLogger("ombre_brain")


def _merge_patch(base: Mapping[str, Any], patch: Mapping[str, Any]) -> dict[str, Any]:
    """合并向导拥有的顶层字段，保留模型、衰减等其他用户配置。"""
    merged = dict(base)
    for key, value in patch.items():
        if key == "deployment" and isinstance(value, Mapping):
            old = merged.get("deployment")
            deployment = dict(old) if isinstance(old, Mapping) else {}
            deployment.update(dict(value))
            if deployment.get("profile") == "local":
                deployment.pop("public_url", None)
            merged[key] = deployment
        else:
            merged[key] = value
    return merged


def _report(path: str, persisted: Mapping[str, Any]) -> dict[str, Any]:
    """组合部署报告，统一复用现有持久卷探测。"""
    persistence = sh.data_dir_persistence(str(sh.config.get("buckets_dir") or ""))
    return effective_configuration_report(
        sh.config,
        persisted,
        environment=os.environ,
        in_docker=sh.in_docker(),
        config_path=path,
        persistence=persistence,
    )


def _network_check(patch: Mapping[str, Any]) -> tuple[dict[str, Any], list[str], list[str]]:
    """让预检与保存共用同一网络安全门禁，避免只修前端被绕过。"""
    decision = assess_mcp_network_safety(
        patch,
        environment=os.environ,
        in_docker=sh.in_docker(),
    )
    issue = mcp_network_safety_issue(decision)
    warnings = [str(decision["reason"])] if decision["override_active"] else []
    return decision, ([issue] if issue else []), warnings


def register(mcp: Any) -> None:
    """注册首次部署页面与受 Dashboard 会话保护的向导 API。"""

    @mcp.custom_route("/onboarding", methods=["GET"])
    async def onboarding_page(request: Request) -> Response:
        from starlette.responses import HTMLResponse

        page_path = os.path.join(sh.repo_root, "frontend", "onboarding.html")
        try:
            with open(page_path, "r", encoding="utf-8") as handle:
                page = handle.read()
            return HTMLResponse(page, headers={"Cache-Control": "no-cache, no-store, must-revalidate"})
        except FileNotFoundError:
            return HTMLResponse("<h1>onboarding.html not found</h1>", status_code=404)
        except OSError as exc:
            logger.error("读取首次部署页面失败: %s", exc)
            return HTMLResponse("<h1>无法读取首次部署页面</h1>", status_code=500)

    @mcp.custom_route("/api/onboarding/profile", methods=["GET"])
    async def onboarding_profile(request: Request) -> Response:
        from starlette.responses import JSONResponse

        err = sh._require_auth(request)
        if err:
            return err
        path = config_file_path()
        try:
            persisted = read_config_yaml()
            return JSONResponse({"ok": True, "profiles": profile_catalog(), "report": _report(path, persisted)})
        except (OSError, ValueError, yaml.YAMLError) as exc:
            logger.error("读取首次部署配置失败: %s", exc)
            return JSONResponse({"ok": False, "error": f"无法读取配置：{exc}"}, status_code=500)

    @mcp.custom_route("/api/onboarding/preflight", methods=["POST"])
    async def onboarding_preflight(request: Request) -> Response:
        from starlette.responses import JSONResponse

        err = sh._require_auth(request)
        if err:
            return err
        try:
            body = await sh._read_json_object(request)
            patch = build_profile_patch(body.get("profile"), body.get("options"))
            issues = validate_profile_patch(patch)
            network_security, network_issues, warnings = _network_check(patch)
            issues.extend(network_issues)
            return JSONResponse({
                "ok": not issues,
                "issues": issues,
                "warnings": warnings,
                "patch": patch,
                "mcp_network_security": network_security,
            })
        except (ValueError, TypeError) as exc:
            return JSONResponse({"ok": False, "error": str(exc), "issues": [str(exc)]}, status_code=400)
        except Exception as exc:
            logger.exception("首次部署预检失败")
            return JSONResponse({"ok": False, "error": f"预检失败：{exc}"}, status_code=500)

    @mcp.custom_route("/api/onboarding/apply", methods=["POST"])
    async def onboarding_apply(request: Request) -> Response:
        from starlette.responses import JSONResponse

        err = sh._require_auth(request)
        if err:
            return err
        try:
            body = await sh._read_json_object(request)
            if body.get("confirm") is not True:
                return JSONResponse({"ok": False, "error": "confirm=true required"}, status_code=400)
            patch = build_profile_patch(body.get("profile"), body.get("options"))
            issues = validate_profile_patch(patch)
            network_security, network_issues, warnings = _network_check(patch)
            issues.extend(network_issues)
            if issues:
                return JSONResponse({
                    "ok": False,
                    "issues": issues,
                    "mcp_network_security": network_security,
                }, status_code=400)
            path = config_file_path()
            # 与 Dashboard 其余配置入口共用同一把读改写锁及 bind-mount
            # EBUSY 兼容路径。旧实现先读后 os.replace，不但会在 Docker
            # 单文件挂载上稳定失败，也可能覆盖同时保存的其他配置。
            merged = atomic_update_config_yaml(
                lambda persisted: persisted.update(_merge_patch(persisted, patch))
            )
            # OAuth/transport 都在启动时绑定；这里只更新“期望值”，不谎报热切换成功。
            report = _report(path, merged)
            restart_required = bool(report.get("restart_required"))
            return JSONResponse({
                "ok": True,
                "saved": patch,
                "report": report,
                "warnings": warnings,
                "mcp_network_security": network_security,
                "restart_required": restart_required,
                "message": (
                    "部署模式已保存，重启服务后生效。"
                    if restart_required
                    else "部署模式已保存，当前进程已使用相同配置。"
                ),
            })
        except (ValueError, TypeError, OSError, yaml.YAMLError) as exc:
            logger.error("保存首次部署配置失败: %s", exc)
            return JSONResponse({"ok": False, "error": f"保存失败：{exc}"}, status_code=500)
        except Exception as exc:
            logger.exception("首次部署保存发生未预期错误")
            return JSONResponse({"ok": False, "error": f"保存失败：{exc}"}, status_code=500)
