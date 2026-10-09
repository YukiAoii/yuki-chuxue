"""
========================================
tools/hold/core.py — hold 普通存入分支（含自动合并）
========================================

非 feel、非 pinned 时走这里：优先调 LLM 自动打标，失败则用本地中性元数据，
再用检索找近似桶，
超过 merge_threshold 则合并（hold 用 raw_merge=True 拼接原文，不压缩），
否则新建。

关键行为：
- analyze() 失败（API key/限流/网络不可用）时仍逐字保存正文，只降级元数据
- 她/他显式 valence/arousal 优先于 LLM 打标
- 调 _common.merge_or_create 走合并/新建
- iter 2.0：source_tool 写 ``hold``；合并到老桶时只更新 ``last_merged_by``
- embedding 失败时桶正常创建，返回追加向量化降级警告
- 写完 fire-and-forget：plan 完成建议判断 + 新桶疑似重复扫描

不做什么（边界）：
- 不做 pinned 配额检查（那是 pinned 分支的事）
- 不做单桶字节上限校验（已在 dispatch 入口做过）

对外暴露：store_core(content, extra_tags, importance, valence, arousal,
                     why_remembered, meaning, media) → str
========================================
"""

import asyncio
from datetime import datetime

from utils import normalize_memory_title

from .. import _runtime as rt
from .._common import merge_or_create, check_duplicate_for, check_plan_resolution


async def store_core(
    content: str,
    extra_tags: list,
    importance: int,
    valence: float,
    arousal: float,
    why_remembered: str,
    title: str = "",
    meaning: str = "",
    media: list | str | None = None,
    test_data: bool = False,
    explicit_domain: list[str] | None = None,
    source_refs: list[dict] | None = None,
    quotes: list[dict] | None = None,
) -> str:
    """普通存入（含自动合并）。

    ## ⚠️ 2026-10-06 性能改造（用户报「云端记忆上传还是很慢」）
    实测：hold 耗时 **1.79~10.76 秒**（4 次），而 breath（读）只要 **0.01 秒**。
    根因 = 本函数原来**同步 await 一次 LLM 打标**（`dehydrator.analyze`），
    而 App 侧是同步等这个返回的 —— 用户就干等一次外部 API 往返。

    改造：**打标不再阻塞写入**。
      · 正文与元数据**先落盘**（用本地中性默认值）→ 立即返回 → 调用方秒回；
      · 打标改后台任务，完成后用 `bucket_mgr.update` 把真实分类/情绪/标签**补回该桶**。
      · 打标失败/超时：桶已在，只是元数据保持中性（与改造前的降级路径一致）。

    ⚠️ 代价（如实）：新记忆落盘后的**头几秒**，它的分类/情绪标签还是中性的
    （显示为「未分类」），后台补完才准。用户感知的是"记上了没有"——
    那是秒级的；标签是"之后才用到"的信息，晚几秒无妨。
    """
    # ① 先落盘：不阻塞等打标。中性默认值（与 analyze 失败时的降级值一致）。
    default_analysis = getattr(rt.dehydrator, "_default_analysis", None)
    analysis = default_analysis() if callable(default_analysis) else {
        "domain": ["未分类"],
        "valence": 0.5,
        "arousal": 0.3,
        "tags": [],
        "suggested_name": "",
    }
    metadata_fallback = False

    analyzed_domain = analysis.get("domain") or ["未分类"]
    if not isinstance(analyzed_domain, list):
        analyzed_domain = ["未分类"]
    final_domain = explicit_domain or analyzed_domain
    _v = analysis.get("valence", 0.5)
    _a = analysis.get("arousal", 0.3)
    final_valence = valence if 0 <= valence <= 1 else (float(_v) if _v is not None else 0.5)
    final_arousal = arousal if 0 <= arousal <= 1 else (float(_a) if _a is not None else 0.3)
    _raw_tags = analysis.get("tags") or []
    model_tags = _raw_tags if isinstance(_raw_tags, list) else []
    all_tags = list(dict.fromkeys(extra_tags if extra_tags else model_tags))
    suggested_name = analysis.get("suggested_name", "")
    # ⚠️ 2026-10-06：打标挪后台后，`analysis` 是中性默认值 → `suggested_name` 为空，
    #    于是 `final_title` 也是空 —— 而 `_common` 只在 title 非空时才写这个字段，
    #    结果是**新桶没有 title**（OB 自己的测试 `test_hold_explicit_tags_replace_model_suggestions`
    #    抓到了：它断言模型标题被用上，实际 KeyError: 'title'）。
    #    这里给一个**兜底标题**（时间戳，与新建桶的 `name` 同格式），
    #    保证桶始终有 title；后台打标完成后会用模型标题覆盖它（见 `_analyze_and_backfill`）。
    fallback_title = title or normalize_memory_title(suggested_name)
    if not fallback_title:
        fallback_title = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    final_title = fallback_title

    result_name, is_merged, embed_warn = await merge_or_create(
        content=content,
        tags=all_tags,
        importance=importance,
        domain=final_domain,
        valence=final_valence,
        arousal=final_arousal,
        name=suggested_name,
        title=final_title,
        source_refs=source_refs,
        quotes=quotes,
        raw_merge=True,
        why_remembered=why_remembered,
        source_tool="hold",
        meaning=meaning,
        media=media,
        test_data=test_data,
    )

    action = "合并→" if is_merged else "新建→"
    asyncio.create_task(check_plan_resolution(content, source_bucket_id=result_name))
    if not is_merged:
        asyncio.create_task(check_duplicate_for(result_name, content))
    # ⚠️ 2026-10-06：打标改**后台补**（见函数 docstring 的性能改造说明）。
    #    桶已落盘、调用方已可返回；这里在后台把真实分类/情绪/标签补回该桶。
    #    失败只记日志 —— 桶已经在，元数据保持中性即可（与改造前的降级一致）。
    # ⚠️ 传**用户显式指定过哪些字段**：那些字段不能被后台打标覆盖
    #    （用户说的算，模型打标只能补空缺）。
    asyncio.create_task(
        _analyze_and_backfill(
            result_name,
            content,
            locked={
                "domain": bool(explicit_domain),
                "valence": 0 <= valence <= 1,
                "arousal": 0 <= arousal <= 1,
                "tags": bool(extra_tags),
                "title": bool(title),
            },
        )
    )
    result = f"{action}{result_name} {','.join(str(d) for d in final_domain if d is not None)}"
    if embed_warn:
        result += f"\n⚠️ {embed_warn}"
    if metadata_fallback:
        result += "\n⚠️ 打标 API 暂不可用：正文已逐字保存，未做任何压缩；元数据暂用本地中性值。"
    return result


async def _analyze_and_backfill(bucket_id: str, content: str, locked: dict | None = None) -> None:
    """后台打标并把结果补回桶（2026-10-06 性能改造）。

    ⚠️ 这是**后台任务**：任何异常都必须吞掉（记日志），绝不能让一个附加动作
    影响已完成的写入 —— 桶早就落盘了，这里的失败只是"标签没补上"。

    只用 `update` 的元数据字段（domain / valence / arousal / tags / title），
    **不动 content** —— 正文是用户的内容，打标只配改附属元数据。

    `locked` = 用户**显式指定过**的字段（true = 别覆盖）。用户说的算，
    模型打标只能补空缺 —— 否则"用户明确要的分类"会被模型的猜测顶掉。
    """
    locked = locked or {}
    try:
        analysis = await rt.dehydrator.analyze(content)
    except Exception as e:
        rt.logger.warning(
            "hold 后台打标失败（桶已在，元数据保持中性）: "
            f"{type(e).__name__}: {e}"
        )
        return
    if not isinstance(analysis, dict):
        return
    patch: dict = {}
    domain = analysis.get("domain")
    if isinstance(domain, list) and domain and not locked.get("domain"):
        patch["domain"] = domain
    for key in ("valence", "arousal"):
        value = analysis.get(key)
        if isinstance(value, (int, float)) and not locked.get(key):
            patch[key] = float(value)
    tags = analysis.get("tags")
    if isinstance(tags, list) and tags and not locked.get("tags"):
        patch["tags"] = tags
    suggested = analysis.get("suggested_name")
    if isinstance(suggested, str) and suggested.strip() and not locked.get("title"):
        patch["title"] = normalize_memory_title(suggested)
    if not patch:
        return
    try:
        bucket_mgr = getattr(rt, "bucket_mgr", None)
        if bucket_mgr is None:
            return
        # bump_active=False：补标签**不是**一次真实激活，不该刷新 last_active
        #（那会让刚写的记忆在衰减排序里被重复加权）。
        await bucket_mgr.update(bucket_id, bump_active=False, **patch)
    except Exception as e:
        rt.logger.warning(f"hold 后台打标回填失败（桶已在）: {type(e).__name__}: {e}")
