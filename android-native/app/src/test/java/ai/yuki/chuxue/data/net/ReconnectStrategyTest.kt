package ai.yuki.chuxue.data.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 重连退避的规格（开发文档 §14.5 / §25.6）。
 *
 * 网络行为在无设备环境验证不了，但**等待多久**这件事可以 ——
 * 而它恰好决定了弱网下会不会变成请求风暴（退避太浅）
 * 或一直连不上（退避太深）。
 */
class ReconnectStrategyTest {

    @Test
    fun `基础等待按 2 的幂增长`() {
        assertEquals(1_000L, ReconnectStrategy.baseDelay(0))
        assertEquals(2_000L, ReconnectStrategy.baseDelay(1))
        assertEquals(4_000L, ReconnectStrategy.baseDelay(2))
        assertEquals(8_000L, ReconnectStrategy.baseDelay(3))
        assertEquals(16_000L, ReconnectStrategy.baseDelay(4))
    }

    @Test
    fun `封顶不超过 30 秒`() {
        // 第 5 次本该是 32s，被 MAX 截到 30s（文档 §25.6 的「退避最大 30 秒」）
        assertEquals(30_000L, ReconnectStrategy.baseDelay(5))
        assertEquals(30_000L, ReconnectStrategy.baseDelay(10))
        assertEquals(30_000L, ReconnectStrategy.baseDelay(1000))
    }

    @Test
    fun `负数尝试按 0 处理`() {
        assertEquals(ReconnectStrategy.baseDelay(0), ReconnectStrategy.baseDelay(-1))
        assertEquals(ReconnectStrategy.baseDelay(0), ReconnectStrategy.baseDelay(-999))
    }

    @Test
    fun `抖动落在基础等待的 50 到 100 百分比之间`() {
        // random = 0 → 0.5 倍
        assertEquals(500L, ReconnectStrategy.jitteredDelay(0, 0.0))
        // random 接近 1 → 接近 1 倍（但 toLong 向下取整）
        assertEquals(999L, ReconnectStrategy.jitteredDelay(0, 0.999))
        assertEquals(1_000L, ReconnectStrategy.jitteredDelay(0, 1.0))
    }

    @Test
    fun `抖动不会让等待超出封顶上限`() {
        for (r in listOf(0.0, 0.3, 0.5, 0.9, 1.0)) {
            val d = ReconnectStrategy.jitteredDelay(20, r)
            assertTrue("抖动后 $d 不应超过封顶 ${ReconnectStrategy.MAX_MS}", d <= ReconnectStrategy.MAX_MS)
        }
    }

    @Test
    fun `越界的抖动参数被钳制而不是算出负数或超长等待`() {
        assertEquals(500L, ReconnectStrategy.jitteredDelay(0, -5.0))
        assertEquals(1_000L, ReconnectStrategy.jitteredDelay(0, 9.0))
    }

    @Test
    fun `超过最大次数后不再重试`() {
        assertTrue(ReconnectStrategy.shouldRetry(0))
        assertTrue(ReconnectStrategy.shouldRetry(9))
        assertFalse(ReconnectStrategy.shouldRetry(10))
        assertFalse(ReconnectStrategy.shouldRetry(99))
    }
}
