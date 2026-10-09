package ai.yuki.chuxue.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「Ta 来找我」取件的**节流判定**（v0.61.40，纯函数）。
 *
 * 与 `AutoBackupPolicy` 同一条纪律：这类判定最容易在边界出错（第一次 / 刚好到期 /
 * 时钟回拨），抽成纯函数在 JVM 上钉死。
 *
 * ⚠️ 它判的只是"**去不去信箱看**"——绝不参与"Ta 要不要说话"。
 * 何时说、说什么由心潮的情绪状态机决定（情绪变化 / 梦境 / 念头涌现 / 驱力变化）；
 * 接入侧不许有任何"到点就提醒 Ta 说话"的逻辑。
 *
 * ⚠️ 方法名纪律：JVM 方法名不允许 `<` `>` `..` `/` —— 别在测试名里写 `->` 箭头
 *（这个坑项目已踩过三次：`..`、`/`、`>`）。
 */
class ProactiveFetchPolicyTest {

    private val minute = 60_000L

    @Test
    fun `从没取过件时就该取（新开开关不用等一个间隔）`() {
        assertTrue(ProactiveFetchPolicy.shouldFetch(now = 1_000_000_000L, lastAt = 0L))
    }

    @Test
    fun `不足间隔时不取`() {
        val now = 1_000_000_000L
        assertFalse(ProactiveFetchPolicy.shouldFetch(now, lastAt = now - 5 * minute))
        assertFalse(ProactiveFetchPolicy.shouldFetch(now, lastAt = now - 29 * minute))
    }

    @Test
    fun `刚好到间隔时取（边界计入）`() {
        val now = 1_000_000_000L
        assertTrue(ProactiveFetchPolicy.shouldFetch(now, lastAt = now - 30 * minute))
    }

    @Test
    fun `间隔可配 —— 默认 30 分钟`() {
        assertEquals(30, ProactiveFetchPolicy.DEFAULT_INTERVAL_MINUTES)
        val now = 1_000_000_000L
        assertTrue(
            ProactiveFetchPolicy.shouldFetch(now, lastAt = now - 60 * minute, intervalMinutes = 60),
        )
    }

    @Test
    fun `时钟回拨（lastAt 在未来）时不取，不炸`() {
        val now = 1_000_000_000L
        assertFalse(ProactiveFetchPolicy.shouldFetch(now, lastAt = now + 60 * minute))
    }
}
