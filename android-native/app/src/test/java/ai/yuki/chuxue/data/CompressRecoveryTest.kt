package ai.yuki.chuxue.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 压缩**恢复链**的纯函数契约（v0.61.46 · 重写上下文压缩）。
 *
 * ## 这一组测试修的是什么（用户报的真实故障）
 * 「上下文超限之后就不能压缩了；点压缩提示"压缩失败：被拒"」——
 * 根因：`summarize` 把**被压缩的那一整段**原文一次性发给模型；
 * 当会话本身已贴到（或超过）模型窗口时，**摘要请求自己也会超窗被 API 拒绝**
 * → 压不了 → 会话卡死。
 *
 * ## 解法（三个新机制，全部纯函数、可 JVM 测）
 * 1. **预算**：压缩请求自身必须留出输出与安全余量（`summarizeBudget`）；
 * 2. **分块 + 滚动合并**：超长段落按**轮边界**切成预算内的块，逐块摘要、
 *    滚动合并成一份（每块输入都远小于窗口 → 不会再超窗）；
 * 3. **识别与警戒线**：能认出"超窗"类错误（`isContextOverflowError`）、
 *    发前贴到 95% 就不再白花一次被拒的钱（`shouldPauseForCeiling`）。
 */
class CompressRecoveryTest {

    /* ─────────────── 统一构造帮手 ─────────────── */

    /** 一段 2000 token 的文本（中文 1 字 = 1 token，见 estimateTokens 口径）。 */
    private fun t(n: Int) = "字".repeat(n)

    private fun user(n: Int) = ChatMessage(role = "user", content = t(n))
    private fun assistant(n: Int) = ChatMessage(role = "assistant", content = t(n))

    /** 交替的 n 轮，每轮 user+assistant 各 [each] token。 */
    private fun rounds(n: Int, each: Int = 10): List<ChatMessage> =
        (0 until n).flatMap { listOf(user(each), assistant(each)) }

    /* ─────────────── 1) summarizeBudget ─────────────── */

    @Test
    fun `预算 = 上限减去输出与一成安全余量`() {
        // 128K − 800(输出) − 12800(10%) = 114400
        assertEquals(114400, ContextCompress.summarizeBudget(128_000))
        // 8K − 800 − 800 = 6400
        assertEquals(6400, ContextCompress.summarizeBudget(8_000))
    }

    @Test
    fun `预算永远小于上限 —— 这是它存在的唯一理由`() {
        for (limit in listOf(2_000, 8_000, 32_000, 128_000, 1_000_000)) {
            assertTrue(
                "上限 $limit 的预算不能把窗口吃满",
                ContextCompress.summarizeBudget(limit) < limit,
            )
        }
    }

    @Test
    fun `上限非法（零或负）给零 —— 调用方据此走单块旧路径，不崩`() {
        assertEquals(0, ContextCompress.summarizeBudget(0))
        assertEquals(0, ContextCompress.summarizeBudget(-5))
        assertEquals(0, ContextCompress.summarizeBudget(800))
    }

    /* ─────────────── 2) chunkSegment ─────────────── */

    @Test
    fun `小段不切 —— 单块原样返回（这条防"无谓分块"：能一次压就一次压）`() {
        val seg = rounds(3, each = 10) // 60 token
        val chunks = ContextCompress.chunkSegment(seg, budgetTokens = 1_000)
        assertEquals(1, chunks.size)
        assertEquals(seg, chunks[0])
    }

    @Test
    fun `超预算段按轮边界切块 —— 每块不超预算、不丢不重、边界落在新轮起点`() {
        val seg = rounds(10, each = 20) // 20 条，共 400 token
        val chunks = ContextCompress.chunkSegment(seg, budgetTokens = 100)
        // 每块最多 100：一段 user20+assistant20=40，两块轮 = 80，三块轮 = 120 > 100 →
        // 应在第 3 轮起点切 → 每块 2 轮（80）
        assertTrue("至少要切成多块：${chunks.size}", chunks.size > 1)
        chunks.forEach { c ->
            assertTrue("块超预算：${ContextCompress.estimateTokens(c)}", ContextCompress.estimateTokens(c) <= 100)
        }
        // 不丢不重：拼接后与原文完全一致
        assertEquals(seg, chunks.flatten())
        // 每块（除第一块）都应从一条 user 开头 —— 不许把一轮劈成两半
        chunks.drop(1).forEach { c ->
            assertEquals("块首不是新轮起点", "user", c.first().role)
        }
    }

    @Test
    fun `单条消息自己超预算时它所在的轮独占一块 —— 不拆轮也不拆消息`() {
        val seg = listOf(user(5), assistant(5), user(500), assistant(5), user(5))
        val chunks = ContextCompress.chunkSegment(seg, budgetTokens = 100)
        // 超预算的那一轮（[user500, assistant5]）独占一块，交给执行层处理失败；
        // 前后的正常轮照常成块 —— 关键红线：不丢不重、不拆轮
        val idx = chunks.indexOfFirst { c -> c.any { it.content.length == 500 } }
        assertTrue(idx >= 0)
        assertEquals(2, chunks[idx].size)
        assertEquals(500, chunks[idx][0].content.length)
        assertEquals(seg, chunks.flatten())
    }

    @Test
    fun `空段给空列表`() {
        assertEquals(0, ContextCompress.chunkSegment(emptyList(), budgetTokens = 100).size)
    }

    @Test
    fun `预算非法时退回单块 —— 不硬切`() {
        val seg = rounds(5, each = 20)
        val chunks = ContextCompress.chunkSegment(seg, budgetTokens = 0)
        assertEquals(1, chunks.size)
        assertEquals(seg, chunks[0])
    }

    /* ─────────────── 3) isContextOverflowError ─────────────── */

    @Test
    fun `认得出各家服务商的超窗回执`() {
        for (detail in listOf(
            "This model's maximum context length is 65536 tokens. However you requested 70000 tokens",
            "maximum context length exceeded",
            "prompt is too long: 200000 tokens > 131072 maximum",
            "context window exceeded",
            "Please reduce the length of the messages.",
            "too many tokens: exceeds the model limit",
        )) {
            assertTrue("没认出来：$detail", ContextCompress.isContextOverflowError(detail))
        }
        // 大小写不敏感
        assertTrue(ContextCompress.isContextOverflowError("MAXIMUM CONTEXT LENGTH is 32768"))
    }

    @Test
    fun `不把别的错误认成超窗 —— 防误报`() {
        for (detail in listOf(
            "余额不足，请充值",
            "invalid api key",
            "rate limit exceeded", // 限流不是超窗（关键词只剩 limit 时不许命中）
            "连接超时",
            "",
        )) {
            assertFalse("误报了：$detail", ContextCompress.isContextOverflowError(detail))
        }
        assertFalse(ContextCompress.isContextOverflowError(null))
    }

    /* ─────────────── 4) 滚动合并 prompt 与判据 ─────────────── */

    @Test
    fun `合并版 prompt 同时带上旧摘要与新对话，并明确要求合并`() {
        val p = ContextCompress.buildSummaryPrompt(
            listOf(user(5), assistant(5)),
            previousSummary = "更早的摘要：用户养了一只叫团子的猫。",
        )
        assertTrue("缺旧摘要", p.contains("团子"))
        assertTrue("缺新对话内容", p.contains("用户："))
        assertTrue("没要求合并", p.contains("合并"))
        assertTrue("没要求不丢旧事实", p.contains("不要丢") || p.contains("不丢"))
    }

    @Test
    fun `没有旧摘要时输出与旧版逐字节一致 —— 老路径行为不变`() {
        val seg = listOf(user(5), assistant(5))
        assertEquals(
            ContextCompress.buildSummaryPrompt(seg),
            ContextCompress.buildSummaryPrompt(seg, previousSummary = null),
        )
    }

    @Test
    fun `合并判据：新摘要必须小于旧摘要加新块之和`() {
        val chunk = listOf(user(100), assistant(100)) // 200 token
        val prev = "旧摘要".repeat(10) // 30 token
        // 合并后 100 < 30+200 → 可用
        assertTrue(
            ContextCompress.isUsableSummary("小结".repeat(50), chunk, previousSummary = prev),
        )
        // 合并后 400 > 230 → 不可用（越滚越大 = 假压缩）
        assertFalse(
            ContextCompress.isUsableSummary("膨胀".repeat(200), chunk, previousSummary = prev),
        )
    }

    @Test
    fun `无旧摘要时判据与旧版一致（向后兼容）`() {
        val chunk = listOf(user(100), assistant(100))
        assertTrue(ContextCompress.isUsableSummary("小结".repeat(50), chunk))
        assertTrue(ContextCompress.isUsableSummary("小结".repeat(50), chunk, previousSummary = null))
        assertFalse(ContextCompress.isUsableSummary("", chunk, previousSummary = null))
    }

    /* ─────────────── 5) shouldPauseForCeiling ─────────────── */

    @Test
    fun `贴到 95% 警戒线就该停下先压缩 —— 不再白花一次被拒的钱`() {
        assertTrue(ContextCompress.shouldPauseForCeiling(950, 1000))
        assertTrue(ContextCompress.shouldPauseForCeiling(1200, 1000)) // 已超
        assertFalse(ContextCompress.shouldPauseForCeiling(949, 1000))
        assertFalse(ContextCompress.shouldPauseForCeiling(0, 1000))
        // 上限非法时不拦（不崩）
        assertFalse(ContextCompress.shouldPauseForCeiling(100, 0))
    }
}
