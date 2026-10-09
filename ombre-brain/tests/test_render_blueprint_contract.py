from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[1]
DASHBOARD = ROOT / "frontend" / "dashboard.html"


def test_render_blueprint_uses_paid_plan_and_persistent_config_path():
    blueprint = yaml.safe_load((ROOT / "render.yaml").read_text(encoding="utf-8"))
    service = blueprint["services"][0]
    env = {item["key"]: item for item in service["envVars"]}

    assert service["plan"] == "starter"
    assert service["disk"]["mountPath"] == env["OMBRE_BUCKETS_DIR"]["value"]
    assert env["OMBRE_CONFIG_PATH"]["value"].startswith(
        service["disk"]["mountPath"] + "/"
    )


def test_dashboard_warns_that_render_hot_update_will_roll_back():
    html = DASHBOARD.read_text(encoding="utf-8")

    assert "if (_deployInfo && _deployInfo.is_render)" in html
    assert "平台重启或重新部署后会回滚" in html
    assert "建议取消并改用 Render 正式部署" in html
