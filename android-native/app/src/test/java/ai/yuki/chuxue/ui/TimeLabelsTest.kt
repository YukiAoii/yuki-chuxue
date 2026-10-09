package ai.yuki.chuxue.ui

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 时间文案的边界规格。
 *
 * 这些分支在真机上极难穷举（要改系统时间、要等到跨周跨年），
 * 做成纯函数就是为了在这里一次钉死。
 */
class TimeLabelsTest {

    private val today: LocalDate = LocalDate.of(2026, 9, 27) // 周日
    private val noon: Long = at(today, 12)

    private fun at(date: LocalDate, hour: Int, minute: Int = 0): Long =
        date.atTime(hour, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /* ══════════════ 会话列表 ══════════════ */

    @Test
    fun `今天显示时刻`() {
        assertEquals("09:05", TimeLabels.forList(at(today, 9, 5), noon))
    }

    @Test
    fun `昨天显示昨天`() {
        assertEquals("昨天", TimeLabels.forList(at(today.minusDays(1), 22), noon))
    }

    @Test
    fun `一周内显示星期`() {
        // 三天前是周四（2026-09-24）
        assertEquals("周四", TimeLabels.forList(at(today.minusDays(3), 8), noon))
    }

    @Test
    fun `超过一周显示日期`() {
        assertEquals("2026/9/10", TimeLabels.forList(at(today.minusDays(17), 8), noon))
    }

    @Test
    fun `时间未知时返回空串而不是 1970 年`() {
        assertEquals("", TimeLabels.forList(0L, noon))
    }

    /* ══════════════ 时间分割条 ══════════════ */

    @Test
    fun `分割条今天只显示时刻`() {
        assertEquals("14:30", TimeLabels.forDivider(at(today, 14, 30), noon))
    }

    @Test
    fun `分割条昨天带上前缀`() {
        assertEquals("昨天 14:30", TimeLabels.forDivider(at(today.minusDays(1), 14, 30), noon))
    }

    @Test
    fun `分割条一周内带星期`() {
        assertEquals("周四 14:30", TimeLabels.forDivider(at(today.minusDays(3), 14, 30), noon))
    }

    @Test
    fun `分割条同年不带年份`() {
        assertEquals("9月10日 14:30", TimeLabels.forDivider(at(today.minusDays(17), 14, 30), noon))
    }

    @Test
    fun `分割条跨年带上年份`() {
        val lastYear = LocalDate.of(2025, 12, 31)
        assertEquals("2025年12月31日 23:00", TimeLabels.forDivider(at(lastYear, 23), noon))
    }

    @Test
    fun `分割条时间未知时返回空串`() {
        assertEquals("", TimeLabels.forDivider(0L, noon))
    }

    /* ══════════════ 该不该插分割条 ══════════════ */

    @Test
    fun `上一条没有时间戳时插 —— 它是已知的最早时刻`() {
        assertTrue(TimeLabels.needsDivider(prevAt = 0L, currentAt = noon))
    }

    @Test
    fun `连续对话不插（间隔不足 5 分钟）`() {
        assertFalse(
            TimeLabels.needsDivider(
                prevAt = noon,
                currentAt = noon + 60_000L,
            ),
        )
    }

    @Test
    fun `间隔达到 5 分钟就插`() {
        assertTrue(
            TimeLabels.needsDivider(
                prevAt = noon,
                currentAt = noon + TimeLabels.DIVIDER_GAP_MS,
            ),
        )
    }

    @Test
    fun `当前这条没有时间戳时不插 —— 老数据不该被画上时间`() {
        assertFalse(TimeLabels.needsDivider(prevAt = noon, currentAt = 0L))
        assertFalse(TimeLabels.needsDivider(prevAt = 0L, currentAt = 0L))
    }

    /* ══════════════ 按日期查找的一天 ══════════════ */

    @Test
    fun `日期列表今天显示今天`() {
        assertEquals("今天", TimeLabels.forDay(today.toString(), noon))
    }

    @Test
    fun `日期列表昨天显示昨天`() {
        assertEquals("昨天", TimeLabels.forDay(today.minusDays(1).toString(), noon))
    }

    @Test
    fun `日期列表一周内显示星期`() {
        // 三天前是 2026-09-24（周四）
        assertEquals("周四", TimeLabels.forDay(today.minusDays(3).toString(), noon))
    }

    @Test
    fun `日期列表同年显示月日`() {
        assertEquals("9月10日", TimeLabels.forDay(today.minusDays(17).toString(), noon))
    }

    @Test
    fun `日期列表跨年带上年份`() {
        assertEquals("2025年12月31日", TimeLabels.forDay("2025-12-31", noon))
    }

    @Test
    fun `日期列表第 7 天不再算一周内`() {
        // 边界：`isAfter(today - 7)` 对第 7 天为假 —— 与 forList 同一条口径，
        // 差一天会让"周三"多贴一天，而这种错在真机上要等一周才看得出来
        assertEquals("9月20日", TimeLabels.forDay(today.minusDays(7).toString(), noon))
    }

    @Test
    fun `日期列表解析不了的 key 原样返回而不是空串或 1970`() {
        assertEquals("2026-13-45", TimeLabels.forDay("2026-13-45", noon))
        assertEquals("", TimeLabels.forDay("", noon))
    }
}
