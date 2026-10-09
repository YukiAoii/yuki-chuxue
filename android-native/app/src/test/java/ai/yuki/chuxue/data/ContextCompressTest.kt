package ai.yuki.chuxue.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 上下文压缩内核的契约（纯函数）。
 *
 * 最要紧的两条：
 * 1. **轮边界对齐** —— 切点落在 user 上，且**不能有消息两头都不在**（静默丢内容）；
 * 2. **`buildHistory` 只在有摘要时才改 history** —— 没摘要时必须是原样返回，
 *    否则"没压过"的会话也会被它动一下前缀（缓存白碎）。
 */
class ContextCompressTest {

    private fun msg(role: String, content: String) = ChatMessage(role = role, content = content)

    /** 造 n 轮对话（每轮 user + assistant） */
    private fun turns(n: Int): List<ChatMessage> = buildList {
        repeat(n) { i ->
            add(msg("user", "问第${i}句"))
            add(msg("assistant", "答第${i}句"))
        }
    }

    /* ─────────── 估算 ─────────── */

    @Test
    fun `空文本是 0 token`() {
        assertEquals(0, ContextCompress.estimateTokens(""))
    }

    @Test
    fun `中文按字算，标点也算`() {
        // 6 个汉字 → 6
        assertEquals(6, ContextCompress.estimateTokens("今天天气不错"))
    }

    @Test
    fun `英文按四字符一个 token 粗算`() {
        assertEquals(2, ContextCompress.estimateTokens("abcdefgh"))
    }

    @Test
    fun `中英混排各算各的`() {
        // 2 个汉字 + 4 个英文字符(=1) = 3
        assertEquals(3, ContextCompress.estimateTokens("你好abcd"))
    }

    @Test
    fun `整段历史把思考过程也算进去`() {
        val msgs = listOf(
            ChatMessage(role = "assistant", content = "回答", reasoning = "想了一下"),
        )
        assertEquals(
            ContextCompress.estimateTokens("回答") + ContextCompress.estimateTokens("想了一下"),
            ContextCompress.estimateTokens(msgs),
        )
    }

    /* ─────────── 阈值 ─────────── */

    @Test
    fun `占用率超过阈值时才该压缩`() {
        val short = turns(1)
        assertFalse(ContextCompress.shouldCompress(short, limitTokens = 64_000))
    }

    @Test
    fun `上限非法时不压缩、占用率为 0（不崩）`() {
        assertFalse(ContextCompress.shouldCompress(turns(5), limitTokens = 0))
        assertEquals(0f, ContextCompress.usageRatio(turns(5), limitTokens = 0), 0.0001f)
    }

    @Test
    fun `占用率夹在 0 到 1 之间 —— 进度条不该画出界`() {
        assertEquals(1f, ContextCompress.usageRatio(turns(2000), limitTokens = 100), 0.0001f)
    }

    /* ─────────── 切分：轮边界对齐（本文件的核心） ─────────── */

    @Test
    fun `消息不够多时没有可压的`() {
        assertTrue(ContextCompress.segmentToSummarize(turns(2), keepRecent = 30).isEmpty())
    }

    @Test
    fun `把要压的那一段切出来，保留段从一条 user 开始`() {
        val msgs = turns(20) // 40 条
        val cut = ContextCompress.segmentToSummarize(msgs, keepRecent = 10)
        assertTrue("应当切出一段", cut.isNotEmpty())
        // 保留段的开头必须是 user，且"切走 + 保留 = 全部"——这条钉的是**不丢内容**
        val kept = msgs.drop(cut.size)
        assertEquals("user", kept.first().role)
        assertEquals("切走的 + 保留的必须等于全部", msgs.size, cut.size + kept.size)
    }

    @Test
    fun `切点不会落在 assistant 上 —— 否则会留下半轮对话`() {
        val msgs = turns(20)
        val cut = ContextCompress.segmentToSummarize(msgs, keepRecent = 11) // 故意给奇数
        val kept = msgs.drop(cut.size)
        assertEquals("保留段的第一条必须是 user", "user", kept.first().role)
    }

    @Test
    fun `一条都找不到 user 时返回空，而不是切出半截`() {
        val allAssistant = List(50) { msg("assistant", "答$it") }
        assertTrue(ContextCompress.segmentToSummarize(allAssistant, keepRecent = 10).isEmpty())
    }

    /* ─────────── 组装 history ─────────── */

    @Test
    fun `没有摘要时 history 原样返回 —— 不能凭空动前缀`() {
        val msgs = turns(3)
        assertEquals(msgs, ContextCompress.buildHistory(msgs, summary = null))
        assertEquals(msgs, ContextCompress.buildHistory(msgs, summary = "  "))
    }

    @Test
    fun `有摘要但没有切点时保留全部原文 —— 绝不按条数截断（截断就是滑动窗口）`() {
        val msgs = turns(20) // 40 条
        val out = ContextCompress.buildHistory(msgs, summary = "他们聊过天气。", keepRecent = 10)
        // ⚠️ 曾经这里是 `takeLast(keepRecent)` → 11 条。那是**滑动窗口**：
        //    窗口每加一条新消息就整体右移，前缀从摘要之后立刻不同 ——
        //    命中量卡死不再增长（实测 640/1704 = 37% vs 固定切点 92%）。
        assertEquals("摘要 + 全部 40 条", 41, out.size)
        assertTrue("第一条应当是摘要", out.first().content.contains("他们聊过天气。"))
        assertTrue("摘要里要写明这不是刚发生的事", out.first().content.contains("不是刚刚发生"))
        assertEquals("其余是全部原文", msgs, out.drop(1))
    }

    @Test
    fun `摘要挂在 assistant 侧 —— 放 user 侧会被当成新指令`() {
        val out = ContextCompress.buildHistory(turns(20), summary = "x", keepRecent = 4)
        // ⚠️ 放 user 侧会让模型以为用户又提了一遍这些内容（v0.46.6 试过，已回滚）。
        //
        // ⚠️ 这里曾有一条 v0.47.0 的断言「摘要块是 system 角色 —— 独占一条 assistant
        //    会让序列非法」，理由是"`system → assistant → user …` 让前缀缓存建不起来"。
        //    **已被实测推翻**（直连 DeepSeek API 做对照，相同内容与长度）：
        //      · `system,assistant,user` → prompt 1371，命中 1152
        //      · `system(合并),user`     → prompt 1368，命中 1152
        //    命中完全相同 —— 序列形状对前缀缓存没有影响。断言按原方案恢复（v0.47.1）。
        assertEquals("assistant", out.first().role)
    }

    @Test
    fun `摘要不带时间戳 —— 它是记忆、不是某条消息`() {
        val out = ContextCompress.buildHistory(turns(20), summary = "x", keepRecent = 4)
        assertEquals(0L, out.first().createdAt)
    }

    @Test
    fun `upTo 不能当切点 —— 老消息 createdAt 是 0，filter 会把它们静默丢掉`() {
        // 真机复现的形态：早期消息 createdAt=0（加该字段之前存下的），
        // 压缩时把 upTo 写成某个正数 → `filter { createdAt > upTo }` 把它们全丢掉。
        // 用户看到的正是"输入只剩 5510 token、命中 512"（项目改动记录 有记载）。
        val msgs = listOf(
            msg("user", "很老的一句"),                       // createdAt = 0
            msg("assistant", "很老的答"),                     // createdAt = 0
            msg("user", "中间的一句").copy(createdAt = 300L),
            msg("assistant", "中间的答").copy(createdAt = 301L),
            msg("user", "新的一句").copy(createdAt = 900L),
            msg("assistant", "新的答").copy(createdAt = 901L),
        )
        val out = ContextCompress.buildHistory(msgs, summary = "摘要", upTo = 500L)
        // 旧实现：filter { createdAt > 500 } → 只剩 2 条（createdAt=900/901），
        //         前 4 条（含两条 createdAt=0 的老消息）**内容真的丢了**。
        assertEquals("摘要 + 全部 6 条", 7, out.size)
        assertEquals("一条都不许丢", msgs, out.drop(1))
        assertTrue("老消息必须在（它们是可读的历史）", out.any { it.content == "很老的一句" })
    }

    @Test
    fun `有 summaryCount 时仍按它切 —— 可信切点优先`() {
        val msgs = (0 until 40).map { msg("user", "第${it}句").copy(createdAt = it.toLong()) }
        // coveredCount=10 且 upTo 也给了 —— 必须走 coveredCount（条数）
        val out = ContextCompress.buildHistory(
            msgs, summary = "摘要", upTo = 5L, coveredCount = 10,
        )
        assertEquals("摘要 + 第 10 条之后的 30 条", 31, out.size)
        assertEquals("第10句", out[1].content)
    }

    /* ─────────── 用户给的格式与口径 ─────────── */

    @Test
    fun `短格式按用户样例：501K 与 1_0M`() {
        assertEquals("501K", ContextCompress.formatShort(501_000))
        assertEquals("1.0M", ContextCompress.formatShort(1_000_000))
        assertEquals("128K", ContextCompress.formatShort(128_000))
        assertEquals("860", ContextCompress.formatShort(860))
    }

    @Test
    fun `上限按模型给：长上下文 1M，其余 128K`() {
        assertEquals(1_000_000, ContextCompress.contextLimit("deepseek-chat-1m"))
        assertEquals(128_000, ContextCompress.contextLimit("deepseek-chat"))
    }

    @Test
    fun `占用要算上人设那一段 —— 只算历史会明显低报`() {
        val msgs = listOf(msg("user", "你好"))
        val without = ContextCompress.estimateContext(frozenPrefix = "", messages = msgs)
        val withPersona = ContextCompress.estimateContext(frozenPrefix = "角色名称：初雪", messages = msgs)
        assertTrue("人设必须计入占用", withPersona > without)
    }

    @Test
    fun `有切点时用「切点之后全部」而不是滑动窗口 —— 这是缓存命中的关键`() {
        // 60 条消息；切点用**条数**（v0.47.2 起 upTo 不再当切点，它依赖 createdAt 不可靠）
        val msgs = (0 until 60).map { msg("user", "第${it}句").copy(createdAt = it.toLong()) }
        val out = ContextCompress.buildHistory(msgs, summary = "摘要", coveredCount = 10)
        // 摘要 + 第 10 条之后的全部（50 条）
        assertEquals(51, out.size)
        assertEquals("第10句", out[1].content)
        // ⚠️ 再加一条新消息，**前面那 50 条必须逐字不变**（这就是不滑动的意义）
        val more = msgs + msg("assistant", "新回复").copy(createdAt = 100L)
        val out2 = ContextCompress.buildHistory(more, summary = "摘要", coveredCount = 10)
        assertEquals("新消息只该追加在后面", out, out2.dropLast(1))
    }

    @Test
    fun `没有切点的老数据保留全部原文 —— 不能因为升级就丢内容，也不能用滑动窗口`() {
        val msgs = (0 until 60).map { msg("user", "第${it}句").copy(createdAt = it.toLong()) }
        val out = ContextCompress.buildHistory(msgs, summary = "摘要", upTo = 0L, keepRecent = 30)
        // ⚠️ 曾经这里是 `takeLast(30)` → 31 条。那正是用户报的
        //    "只命中开头 512、之后全按未命中"的成因（滑动窗口）。
        assertEquals("摘要 + 全部 60 条", 61, out.size)
        assertEquals(msgs, out.drop(1))
    }

    @Test
    fun `无切点老数据：新增消息后前缀逐字不变 —— 这是它替代滑动窗口的理由`() {
        val msgs = (0 until 60).map { msg("user", "第${it}句").copy(createdAt = it.toLong()) }
        val out1 = ContextCompress.buildHistory(msgs, summary = "摘要")
        val more = msgs + msg("assistant", "新回复").copy(createdAt = 100L)
        val out2 = ContextCompress.buildHistory(more, summary = "摘要")
        // 只追加、不滑动 —— 前缀逐字节稳定，缓存才吃得满
        assertEquals("新消息只该追加在后面", out1, out2.dropLast(1))
    }

    /* ═══════════ v0.51.0：窗口可以由用户在分组里填 ═══════════ */

    @Test
    fun `窗口：用户填的优先于按名字猜的`() {
        // 上下文窗口是压缩触发判定的**分母**，而"某个模型窗口多大"是用户手上的事实。
        // 猜（名字里带 1m/long）不该盖过事实。
        assertEquals(32_000, ContextCompress.contextLimit("qwen-max", override = 32_000))
        assertEquals(8_000, ContextCompress.contextLimit("whatever-1m", override = 8_000))
    }

    @Test
    fun `窗口：填 0 或负数等于没填 —— 不能被当成"窗口是 0"`() {
        // 0 当窗口会让占用率变成无穷大 → 每轮都触发压缩（白花钱且摘要越压越糊）
        assertEquals(ContextCompress.DEFAULT_CONTEXT_LIMIT, ContextCompress.contextLimit("gpt-4o", 0))
        assertEquals(ContextCompress.DEFAULT_CONTEXT_LIMIT, ContextCompress.contextLimit("gpt-4o", -1))
    }

    @Test
    fun `窗口：没填时仍与既有口径一致 —— DeepSeek 128K、名字带 1m 是 1M`() {
        assertEquals(ContextCompress.DEFAULT_CONTEXT_LIMIT, ContextCompress.contextLimit("deepseek-chat"))
        assertEquals(1_000_000, ContextCompress.contextLimit("deepseek-v4-1m"))
    }

    @Test
    fun `窗口：填了就"认得出"，界面不再标"按默认估算"`() {
        assertTrue(ContextCompress.contextLimitKnown("qwen-max", override = 32_000))
        assertTrue(!ContextCompress.contextLimitKnown("qwen-max"))
        assertTrue(ContextCompress.contextLimitKnown("deepseek-chat"))
    }
}
