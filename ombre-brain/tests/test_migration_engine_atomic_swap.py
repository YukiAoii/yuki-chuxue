"""migration_engine 原子替换回归测试 —— 验证找茬会话发现的 bug 已经修好。

原 bug：docstring 承诺「先写 embeddings.db.migrating，全部跑完再原子 swap
进主库」，但实际代码从头到尾直接往 live embeddings.db 写，中途崩溃/失败会
让主库永久混入新旧模型/维度不一致的半截向量。

修复：target_engine 的 db_path 被调用方指到 staging_db_path_for(live_db)
返回的独立文件；只有全部成功才 os.replace() 原子替换进 live db；checkpoint
额外记录目标签名（backend:model:dim），目标一变就整个重来，不会把不兼容的
旧向量当成「已完成」直接换进主库。
"""
import ast
import os
from pathlib import Path

import pytest

from migration_engine import (
    MigrationConfig,
    _run_migration,
    _write_checkpoint,
    checkpoint_path_for,
    read_status,
    reset_stale_migration_state,
    staging_db_path_for,
    status_path_for,
    target_signature,
)
from web import embedding as web_embedding
import utils


ROOT = Path(__file__).resolve().parents[1]


class FakeTargetEngine:
    def __init__(self, db_path):
        self.db_path = db_path
        self.calls = []
        self.meta = {}

    async def generate_and_store(self, bucket_id, content):
        self.calls.append(bucket_id)
        # 模拟真的往 db_path 写了东西，确保 os.replace 时文件确实存在且和
        # live db 内容不同，才能验证「swap 前 live 不变」这件事有意义。
        with open(self.db_path, "a", encoding="utf-8") as f:
            f.write(f"{bucket_id}\n")
        return True

    def _write_meta(self, key, value):
        self.meta[key] = value


@pytest.mark.asyncio
async def test_successful_migration_atomically_swaps_staging_into_live(tmp_path):
    buckets_dir = str(tmp_path / "buckets")
    os.makedirs(buckets_dir, exist_ok=True)
    live_db = str(tmp_path / "embeddings.db")
    with open(live_db, "w", encoding="utf-8") as f:
        f.write("OLD-LIVE-CONTENT\n")

    staged_path = staging_db_path_for(live_db)
    target_engine = FakeTargetEngine(staged_path)

    async def fetch_buckets():
        return [("b1", "content 1"), ("b2", "content 2")]

    cfg = MigrationConfig(
        buckets_dir=buckets_dir,
        db_path=live_db,
        target_backend="api",
        target_model="test-model",
        target_dim=8,
        target_engine=target_engine,
        fetch_buckets=fetch_buckets,
    )

    await _run_migration(cfg)

    with open(live_db, "r", encoding="utf-8") as f:
        live_content = f.read()
    assert "OLD-LIVE-CONTENT" not in live_content
    assert "b1" in live_content and "b2" in live_content
    assert not os.path.exists(staged_path), "swap 后 staging 文件应该已经不存在（被 rename 进 live）"
    assert target_engine.db_path == live_db, (
        "swap 后 target_engine 必须指向 live 路径，不能还指着已经消失的 staging 路径"
    )

    status = read_status(status_path_for(buckets_dir))
    assert status["phase"] == "completed"
    assert not os.path.exists(checkpoint_path_for(buckets_dir)), "全部成功后 checkpoint 应该被清掉"


@pytest.mark.asyncio
async def test_failed_migration_never_touches_live_db(tmp_path):
    buckets_dir = str(tmp_path / "buckets")
    os.makedirs(buckets_dir, exist_ok=True)
    live_db = str(tmp_path / "embeddings.db")
    with open(live_db, "w", encoding="utf-8") as f:
        f.write("OLD-LIVE-CONTENT\n")

    staged_path = staging_db_path_for(live_db)

    class FailingTargetEngine(FakeTargetEngine):
        async def generate_and_store(self, bucket_id, content):
            if bucket_id == "b2":
                raise RuntimeError("simulated embedding provider failure")
            return await super().generate_and_store(bucket_id, content)

    target_engine = FailingTargetEngine(staged_path)

    async def fetch_buckets():
        return [("b1", "content 1"), ("b2", "content 2")]

    cfg = MigrationConfig(
        buckets_dir=buckets_dir,
        db_path=live_db,
        target_backend="api",
        target_model="test-model",
        target_dim=8,
        target_engine=target_engine,
        fetch_buckets=fetch_buckets,
    )

    await _run_migration(cfg)

    with open(live_db, "r", encoding="utf-8") as f:
        live_content = f.read()
    assert live_content == "OLD-LIVE-CONTENT\n", "任何失败都不能让新旧向量混进 live db"

    status = read_status(status_path_for(buckets_dir))
    assert status["phase"] == "failed"
    assert status["failed_count"] >= 1
    assert os.path.exists(checkpoint_path_for(buckets_dir)), "失败时应保留 checkpoint 供下次续传"


@pytest.mark.asyncio
async def test_post_swap_publish_failure_is_reported_in_status(tmp_path):
    buckets_dir = str(tmp_path / "buckets")
    os.makedirs(buckets_dir, exist_ok=True)
    live_db = str(tmp_path / "embeddings.db")
    with open(live_db, "w", encoding="utf-8") as f:
        f.write("OLD-LIVE-CONTENT\n")

    target_engine = FakeTargetEngine(staging_db_path_for(live_db))

    async def fetch_buckets():
        return [("b1", "content 1")]

    def fail_publish(success):
        assert success is True
        raise OSError("simulated config publish failure")

    cfg = MigrationConfig(
        buckets_dir=buckets_dir,
        db_path=live_db,
        target_backend="api",
        target_model="test-model",
        target_dim=8,
        target_engine=target_engine,
        fetch_buckets=fetch_buckets,
    )

    await _run_migration(cfg, on_complete=fail_publish)

    status = read_status(status_path_for(buckets_dir))
    assert status["phase"] == "publish_failed"
    assert "运行态/配置发布失败" in status["error"]
    assert "simulated config publish failure" in status["error"]
    with open(live_db, "r", encoding="utf-8") as f:
        assert "b1" in f.read(), "publish 失败不应伪装成原子替换未发生"


def test_embedding_yaml_persistence_failure_propagates(monkeypatch):
    def fail_persist(_mutator):
        raise OSError("simulated yaml write failure")

    monkeypatch.setattr(utils, "atomic_update_config_yaml", fail_persist)

    with pytest.raises(OSError, match="simulated yaml write failure"):
        web_embedding._persist_embedding_yaml({"model": "test-model"})


def test_embedding_package_mode_uses_parent_migration_module():
    tree = ast.parse(
        (ROOT / "src" / "web" / "embedding.py").read_text(encoding="utf-8")
    )
    migration_imports = [
        node
        for node in ast.walk(tree)
        if isinstance(node, ast.ImportFrom) and node.module == "migration_engine"
    ]

    assert not [node for node in migration_imports if node.level == 1]
    assert len([node for node in migration_imports if node.level == 2]) >= 4


def test_dashboard_treats_publish_failure_as_visible_terminal_state():
    dashboard = (ROOT / "frontend" / "dashboard.html").read_text(encoding="utf-8")

    assert "s.phase === 'publish_failed'" in dashboard
    assert "['completed', 'failed', 'publish_failed'].includes(d.status.phase)" in dashboard


@pytest.mark.asyncio
async def test_reset_stale_migration_state_wipes_mismatched_checkpoint_and_staging(tmp_path):
    buckets_dir = str(tmp_path / "buckets")
    os.makedirs(buckets_dir, exist_ok=True)
    live_db = str(tmp_path / "embeddings.db")
    staged_path = staging_db_path_for(live_db)
    with open(staged_path, "w", encoding="utf-8") as f:
        f.write("stale vectors from a different model\n")

    ckpt_path = checkpoint_path_for(buckets_dir)
    _write_checkpoint(ckpt_path, {"b1"}, target_signature("api", "old-model", 4))

    # 换目标（不同 model/dim）→ 旧 checkpoint 和 staging db 都必须被清掉，
    # 否则断点续传会把「old-model 的 b1 已完成」误当成「new-model 的 b1 已完成」。
    reset_stale_migration_state(buckets_dir, live_db, target_signature("api", "new-model", 8))

    assert not os.path.exists(ckpt_path)
    assert not os.path.exists(staged_path)


@pytest.mark.asyncio
async def test_reset_stale_migration_state_keeps_matching_checkpoint(tmp_path):
    buckets_dir = str(tmp_path / "buckets")
    os.makedirs(buckets_dir, exist_ok=True)
    live_db = str(tmp_path / "embeddings.db")
    staged_path = staging_db_path_for(live_db)
    with open(staged_path, "w", encoding="utf-8") as f:
        f.write("in-progress vectors for the same target\n")

    ckpt_path = checkpoint_path_for(buckets_dir)
    sig = target_signature("api", "same-model", 8)
    _write_checkpoint(ckpt_path, {"b1"}, sig)

    reset_stale_migration_state(buckets_dir, live_db, sig)

    assert os.path.exists(ckpt_path), "目标一致时不该清掉正在续传的 checkpoint"
    assert os.path.exists(staged_path), "目标一致时不该清掉正在续传的 staging db"
