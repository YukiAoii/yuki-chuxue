package ai.yuki.chuxue.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 记忆时间文案的契约（v0.49.0）。
 *
 * ## 为什么这批断言值得写
 * 「今天 / 昨天 / N 天前」全是**边界敏感**的：跨天、跨月、时间戳为 0 各是一条分支，
 * 而且在真机上很难穷举（你得改系统时间）。做成纯函数就是为了在 JVM 上钉死它们。
 *
 * ## 为什么不用 `TimeLabels.forList`
 * `forList` 是**会话列表**的口径（一周内给"周三"，因为"最近聊过、周几能定位"）。
 * 记忆要的是「多久以前」——"3 天前"比"周三"好懂。**两套粒度，别混用。**
 */
class MemoryTimeTest {

    private val zone: ZoneId = ZoneId.systemDefault()

    /** 造一个"当地时间的某天某点"，避免夏令时/时区差异导致测试飘 */
    private fun at(year: Int, month: Int, day: Int, hour: Int = 12): Long =
        LocalDate.of(year, month, day).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    private val now = at(2026, 9, 29)

    /* ─────────── 创建时间 ─────────── */

    @Test
    fun `今天记下的`() {
        assertEquals("今天记下", TimeLabels.forMemory(at(2026, 9, 29), 0L, now))
    }

    @Test
    fun `昨天记下的`() {
        assertEquals("昨天记下", TimeLabels.forMemory(at(2026, 9, 28), 0L, now))
    }

    @Test
    fun `几天前`() {
        assertEquals("3 天前记下", TimeLabels.forMemory(at(2026, 9, 26), 0L, now))
        assertEquals("30 天前记下", TimeLabels.forMemory(at(2026, 8, 30), 0L, now))
    }

    @Test
    fun `超过一个月给日期 —— 那时的"38 天前"不如"7_12"好定位`() {
        val out = TimeLabels.forMemory(at(2026, 7, 12), 0L, now)
        assertTrue("应当带上日期：$out", out.contains("2026/7/12"))
        assertTrue("应当说明是记下的：$out", out.contains("记下"))
    }

    /* ─────────── 「最近用到过」── 它证明这条记忆在起作用 ─────────── */

    @Test
    fun `被检索命中过才补"用到过"`() {
        val out = TimeLabels.forMemory(
            createdAt = at(2026, 9, 26),
            lastAccessedAt = at(2026, 9, 29),
            now = now,
        )
        assertTrue("应当同时给出用到的信息：$out", out.contains("今天用到过"))
        assertTrue("创建信息也要在：$out", out.contains("3 天前记下"))
    }

    @Test
    fun `⚠️ 从没被用到过时不补那句话 —— 否则是假话`() {
        // 新建的记忆：createdAt == lastAccessedAt（都还没被检索过）
        val same = at(2026, 9, 26)
        val out = TimeLabels.forMemory(same, same, now)
        assertFalse(
            "刚写下、还没被检索过，说'用到过'是假的：$out",
            out.contains("用到过"),
        )
        assertEquals("3 天前记下", out)
    }

    @Test
    fun `lastAccessedAt 为 0（老数据）时也不补`() {
        val out = TimeLabels.forMemory(at(2026, 9, 26), 0L, now)
        assertFalse(out.contains("用到过"))
    }

    @Test
    fun `用到过的时间也分档`() {
        assertTrue(
            TimeLabels.forMemory(at(2026, 9, 20), at(2026, 9, 28), now).contains("昨天用到过"),
        )
        assertTrue(
            TimeLabels.forMemory(at(2026, 9, 20), at(2026, 9, 25), now).contains("4 天前用到过"),
        )
    }

    /* ─────────── 边界 ─────────── */

    @Test
    fun `创建时间未知（0）时返回空串 —— 宁可不显示，也不显示 1970 年`() {
        assertEquals("", TimeLabels.forMemory(0L, 0L, now))
        assertEquals("", TimeLabels.forMemory(0L, at(2026, 9, 29), now))
    }

    @Test
    fun `按自然日算，不是除以 86400 秒`() {
        // 昨晚 23:00 记下、今天 00:30 看 —— 相隔只 1.5 小时，
        // 但日历上是"昨天"（除以 86400 会算成 0 天 → 说"今天"，那是错的）
        val lateNight = LocalDate.of(2026, 9, 28).atTime(23, 0)
            .atZone(zone).toInstant().toEpochMilli()
        val justAfterMidnight = LocalDate.of(2026, 9, 29).atTime(0, 30)
            .atZone(zone).toInstant().toEpochMilli()
        assertEquals("昨天记下", TimeLabels.forMemory(lateNight, 0L, justAfterMidnight))
    }

    @Test
    fun `未来时间不会崩（时钟回拨或数据异常）`() {
        // 记下的时间比"现在"还晚 —— 不抛异常即可（返回什么都比崩强）
        val out = TimeLabels.forMemory(at(2026, 10, 5), 0L, now)
        assertTrue("不该崩，且应当是个非空串：$out", out.isNotEmpty())
    }

    @Test
    fun `与 forList 的口径确实不同 —— 这两个不能互相替换`() {
        val threeDaysAgo = at(2026, 9, 26)
        // forList 一周内给"周几"，forMemory 给"N 天前"
        val list = TimeLabels.forList(threeDaysAgo, now)
        val mem = TimeLabels.forMemory(threeDaysAgo, 0L, now)
        assertTrue("forList 给的是周几：$list", list.startsWith("周"))
        assertEquals("forMemory 给的是天数", "3 天前记下", mem)
    }
}
