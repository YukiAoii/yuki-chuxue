package ai.yuki.chuxue.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 「按日期查找」的数据层契约（v0.51.0）。
 *
 * ⚠️ 时区**固定**成 Asia/Shanghai，不吃本机时区 ——
 * 否则同一个测试在不同机器上会得出不同的分组结果（CI 上必炸）。
 */
class DateIndexTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    private fun msg(text: String, createdAt: Long) =
        ChatMessage(role = "user", content = text, createdAt = createdAt)

    private fun daysOf(vararg msgs: ChatMessage) =
        DateIndex.days(msgs.toList(), zone)

    /* ─────────── 分组 ─────────── */

    @Test
    fun `跨日的消息分成两组，最近的日期在前`() {
        val out = daysOf(
            msg("昨天说的话", at(2026, 9, 29, 20, 0)),
            msg("今天说的话", at(2026, 9, 30, 9, 0)),
        )
        assertEquals(listOf("2026-09-30", "2026-09-29"), out.map { it.dayKey })
    }

    @Test
    fun `同一天的多条只占一组，条数正确`() {
        val out = daysOf(
            msg("一", at(2026, 9, 30, 8, 0)),
            msg("二", at(2026, 9, 30, 12, 0)),
            msg("三", at(2026, 9, 30, 23, 59)),
        )
        assertEquals(1, out.size)
        assertEquals(3, out[0].count)
    }

    @Test
    fun `只有一天时只有一组`() {
        assertEquals(1, daysOf(msg("就一条", at(2026, 9, 30, 1, 0))).size)
    }

    @Test
    fun `空列表返回空 —— 不能凭空造出一个日期`() {
        assertTrue(DateIndex.days(emptyList(), zone).isEmpty())
    }

    /* ─────────── ⚠️ 老消息：时间戳为 0 必须被跳过 ─────────── */

    @Test
    fun `时间戳为 0 的老消息不产生分组 —— 否则顶上会多出一个 1970-01-01`() {
        val out = daysOf(
            msg("加字段之前存下的老消息", 0L),
            msg("今天的话", at(2026, 9, 30, 9, 0)),
        )
        assertEquals(1, out.size)
        assertEquals("2026-09-30", out[0].dayKey)
    }

    @Test
    fun `全都是老消息时返回空，而不是 1970 那一组`() {
        assertTrue(daysOf(msg("老", 0L), msg("也老", 0L)).isEmpty())
    }

    /* ─────────── firstIndex：点日期能跳到那天开头 ─────────── */

    @Test
    fun `firstIndex 指向那天第一条在全量里的下标`() {
        val out = daysOf(
            msg("昨天", at(2026, 9, 29, 10, 0)),        // index 0
            msg("今天早", at(2026, 9, 30, 8, 0)),       // index 1
            msg("今天晚", at(2026, 9, 30, 21, 0)),      // index 2
        )
        val today = out.first { it.dayKey == "2026-09-30" }
        assertEquals(1, today.firstIndex)
        val yesterday = out.first { it.dayKey == "2026-09-29" }
        assertEquals(0, yesterday.firstIndex)
    }

    @Test
    fun `老消息被跳过时，下标仍指向原列表里的真实位置`() {
        // ⚠️ 这条最容易错：如果实现里先 filter 再编号，下标会整体前移，
        //    点日期就会跳到错的位置。
        val out = daysOf(
            msg("老", 0L),                              // index 0（跳过）
            msg("今天", at(2026, 9, 30, 9, 0)),         // index 1
        )
        assertEquals(1, out[0].firstIndex)
    }

    /* ─────────── preview ─────────── */

    @Test
    fun `preview 取那天第一条的正文`() {
        val out = daysOf(msg("今天吃了拉面", at(2026, 9, 30, 9, 0)))
        assertEquals("今天吃了拉面", out[0].preview)
    }

    @Test
    fun `preview 里不保留换行 —— 它是一行摘要`() {
        val out = daysOf(msg("第一行\n第二行", at(2026, 9, 30, 9, 0)))
        assertTrue("preview 不该含换行：${out[0].preview}", '\n' !in out[0].preview)
    }

    @Test
    fun `preview 过长会截断并带省略号`() {
        val long = "啊".repeat(100)
        val out = daysOf(msg(long, at(2026, 9, 30, 9, 0)))
        assertTrue("应被截断：${out[0].preview.length}", out[0].preview.length < long.length)
        assertTrue(out[0].preview.endsWith("…"))
    }

    @Test
    fun `首条是空正文时，preview 往后找第一条有字的`() {
        val out = daysOf(
            msg("", at(2026, 9, 30, 8, 0)),
            msg("有字的那条", at(2026, 9, 30, 9, 0)),
        )
        assertEquals("有字的那条", out[0].preview)
    }
}
