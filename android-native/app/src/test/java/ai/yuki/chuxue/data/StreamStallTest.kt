package ai.yuki.chuxue.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「她是不是卡住了」判定的规格。
 *
 * ## 为什么是纯函数
 * "卡住"听起来是观感问题，但它的**判据**是可钉死的：从最后一次收到内容算起，
 * 过了多久。把这句判据写成一个 (lastTokenAt, now) -> Boolean 的函数，
 * 边界（恰好 10 秒、差 1 毫秒、还没开始）就都能逐条断言；
 * 若把计时散在看门狗协程里，这些边界只能靠"盯屏幕数秒"，既不可靠也不可复现。
 *
 * ⚠️ 这个判定只喂给**界面**（一行警告），不写进任何消息体 —— 见 [StreamStall] 的注释。
 */
class StreamStallTest {

    @Test
    fun `刚收到过内容不算卡住`() {
        assertFalse(StreamStall.hasStalled(lastTokenAt = 1_000L, now = 4_000L))
    }

    @Test
    fun `满 10 秒没有新内容就算卡住`() {
        assertTrue(StreamStall.hasStalled(lastTokenAt = 1_000L, now = 11_000L))
    }

    @Test
    fun `恰好到 10 秒这一下就转警告 —— 边界含等号`() {
        assertTrue(StreamStall.hasStalled(lastTokenAt = 1_000L, now = 1_000L + StreamStall.TIMEOUT_MS))
    }

    @Test
    fun `差 1 毫秒不到阈值仍不算`() {
        assertFalse(
            StreamStall.hasStalled(lastTokenAt = 1_000L, now = 1_000L + StreamStall.TIMEOUT_MS - 1L),
        )
    }

    @Test
    fun `还没开始（没有内容时间）永远不算 —— 防止看门狗在空闲时误报`() {
        assertFalse(StreamStall.hasStalled(lastTokenAt = 0L, now = 999_999L))
    }

    @Test
    fun `阈值常量就是 10 秒`() {
        assertEquals(10_000L, StreamStall.TIMEOUT_MS)
    }

    /* ── 首字看门狗的等待时长（v0.61.21 修「非流式被误杀」）── */

    @Test
    fun `非流式不起首字看门狗 —— 它根本没有"首字"这回事`() {
        // "整段出现"模式下，整段回复是一次到的，中途必然没有任何事件；
        // 30 秒一到就会把一轮**完全正常**的请求当成超时掐掉。
        assertEquals(0L, StreamStall.firstByteWatchdogMs(baseMs = 30_000L, nonStream = true))
    }

    @Test
    fun `流式照旧用原来的上限`() {
        assertEquals(30_000L, StreamStall.firstByteWatchdogMs(baseMs = 30_000L, nonStream = false))
    }
}
