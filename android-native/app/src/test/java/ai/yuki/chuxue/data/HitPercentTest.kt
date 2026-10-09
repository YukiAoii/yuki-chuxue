package ai.yuki.chuxue.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 命中率百分比的**显示口径**（v0.48.0，R3）。
 *
 * ## 它挡的那个具体谎
 * 整数除法 `hit * 100 / billed` 会把 **99.6% 显示成 100%**。
 * 这个数用户是拿来判断"省钱开关生效了没有"的 —— 报成 100% 等于告诉他
 * "完全命中了"，而实际上**每一轮都还在为那 0.4% 付未命中**的钱。
 *
 * 采纳 `deepseek-harness` 的做法（`client/ui-chat/src/client/chat/token-format.ts`：
 * 舍入到满值时自动加精度）。
 *
 * ## 为什么必须是一个共享函数
 * 同一个数字出现在**三处**（上下文弹窗 / 会话看板 / 列表摘要 `Session.cacheSummary`）。
 * 各写一遍必然漂移，后果是"同一个会话在两个页面显示不同的命中率"。
 */
class HitPercentTest {

    @Test
    fun `常见的命中率就是整数百分比`() {
        assertEquals("50%", formatHitPercent(500, 1000))
        assertEquals("0%", formatHitPercent(0, 1000))
        assertEquals("87%", formatHitPercent(870, 1000))
    }

    @Test
    fun `真的满命中才显示 100%`() {
        assertEquals("100%", formatHitPercent(1000, 1000))
    }

    @Test
    fun `差一点满时**绝不**显示 100% —— 这是本函数存在的理由`() {
        // 996/1000 = 99.6% → 整数舍入会得到 100，必须改成一位小数
        val out = formatHitPercent(996, 1000)
        assertNotEquals("99.6% 不能显示成 100%", "100%", out)
        assertEquals("99.6%", out)
    }

    @Test
    fun `末尾一个 token 没命中也要看得出来`() {
        // 极端的"差一个"：999999/1000000 = 99.9999%
        val out = formatHitPercent(999_999, 1_000_000)
        assertNotEquals("只差一个 token 也不该说成 100%", "100%", out)
        // 一位小数会舍到 100.0 —— 但函数保证只要 hit < total 就不会返回 "100%"
        assertNotEquals("100%", out)
    }

    @Test
    fun `分母为 0 时给破折号而不是 0% —— 那是"还没数据"不是"命中率为零"`() {
        assertEquals("—", formatHitPercent(0, 0))
        assertEquals("—", formatHitPercent(100, 0))
    }

    @Test
    fun `真机实测的那个数能正确还原`() {
        // 用户新截图：命中 10624 / 未命中 344 → 输入共 10968
        // 10624/10968 = 96.86% → 97%
        assertEquals("97%", formatHitPercent(10_624, 10_624 + 344))
        // 旧截图：命中 512 / 未命中 4998
        // 512/5510 = 9.29% → 9%
        assertEquals("9%", formatHitPercent(512, 512 + 4998))
    }

    @Test
    fun `Session_cacheSummary 走同一口径`() {
        val s = Session(
            id = "s1", personaId = "p1", title = "t",
            totalHit = 996, totalMiss = 4,
        )
        // 与函数同源（cacheSummary 内部就是调它）
        assertEquals("命中 " + formatHitPercent(996, 1000), s.cacheSummary)
        assertNotEquals("不能是 100%", "命中 100%", s.cacheSummary)
    }

    @Test
    fun `没有请求时 cacheSummary 说的是"尚无请求"而不是 0%`() {
        val s = Session(id = "s1", personaId = "p1", title = "t")
        assertEquals("尚无请求", s.cacheSummary)
    }
}
