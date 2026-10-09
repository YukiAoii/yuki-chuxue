package ai.yuki.chuxue.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 聊天相关的时间文案（会话列表右侧 + 聊天窗口的时间分割条）。
 *
 * ## 为什么要单独拎出来
 * 「今天 / 昨天 / 周三 / 9月20日」这类判断全是**边界敏感**的：跨天、跨周、跨年
 * 各是一条分支，且在真机上很难穷举（你得改系统时间）。做成纯函数之后，
 * `TimeLabelsTest` 能把每条边界钉死，而不必等用户某天凌晨发消息才发现写错了。
 *
 * ## 时间戳为 0 的含义
 * `0L` = **时间未知**（加时间戳字段之前存下的老消息）。这类一律返回空串、
 * 且不画分割条 —— 宁可没有时间，也不要显示一个 1970 年的荒谬时间。
 *
 * ## 与缓存的关系（重要）
 * 这里产出的一切**只上屏**，绝不进请求体。时间进稳定前缀会让缓存从那里碎掉
 * （§11 三铁律里最贵的一条：实测命中率 95% → 10% 以下）。
 */
object TimeLabels {

    /**
     * 两条消息间隔超过它，才值得插一条时间分割条。
     *
     * 5 分钟是微信量级：连续对话（一问一答几秒、几十秒）不插，
     * 中间隔了一会儿才插 —— 插太密会把聊天记录切得支离破碎。
     */
    const val DIVIDER_GAP_MS = 5 * 60 * 1000L

    private val WEEKDAYS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    /* ─────────────── 会话列表右侧（仿微信）─────────────── */

    /**
     * 会话列表那一行的时间。
     *
     * 今天 → `14:30`；昨天 → `昨天`；一周内 → `周三`；更早 → `2026/9/20`。
     * 粒度随"多久以前"变粗，正是为了在窄列里一眼可辨而不用读完整日期。
     */
    fun forList(at: Long, now: Long): String {
        if (at <= 0L) return ""
        val zone = ZoneId.systemDefault()
        val date = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return when {
            date == today -> clock(at)
            date == today.minusDays(1) -> "昨天"
            date.isAfter(today.minusDays(7)) -> weekday(date)
            else -> "${date.year}/${date.monthValue}/${date.dayOfMonth}"
        }
    }

    /* ─────────────── 聊天窗口的时间分割条 ─────────────── */

    /**
     * 分割条文案。比列表多带时间（因为聊天流里"周三"不够定位）。
     *
     * 今天 → `14:30`；昨天 → `昨天 14:30`；一周内 → `周三 14:30`；
     * 今年 → `9月20日 14:30`；跨年 → `2025年12月31日 14:30`。
     */
    fun forDivider(at: Long, now: Long): String {
        if (at <= 0L) return ""
        val zone = ZoneId.systemDefault()
        val moment = Instant.ofEpochMilli(at).atZone(zone)
        val date = moment.toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val time = clock(at)
        return when {
            date == today -> time
            date == today.minusDays(1) -> "昨天 $time"
            date.isAfter(today.minusDays(7)) -> "${weekday(date)} $time"
            date.year == today.year -> "${date.monthValue}月${date.dayOfMonth}日 $time"
            else -> "${date.year}年${date.monthValue}月${date.dayOfMonth}日 $time"
        }
    }

    /**
     * **「按日期查找」列表里的一天**（v0.51.0）。
     *
     * 输入是 [DateIndex] 产出的 `dayKey`（形如 `2026-09-30`，本地时区的自然日）。
     * 今天 → `今天`；昨天 → `昨天`；一周内 → `周三`；今年 → `9月20日`；
     * 跨年 → `2025年12月31日`。粒度与 [forDivider] 一致，但**不带时刻**
     *（日期列表的一行一天，带时刻是把粒度用错了）。
     *
     * ⚠️ 与 [forList] 的口径差一点：那边"超过一周"给 `2026/9/20`（窄列里省字），
     * 这边给 `9月20日`（一行一个日期，有地方写中文，也更像"日期"）。
     *
     * ⚠️ 解析失败时**原样返回 dayKey**，而不是空串或 1970 ——
     * 这个函数只负责"让人看得懂"，不该把一条真实存在的日期变成空行。
     */
    fun forDay(dayKey: String, now: Long): String {
        val date = runCatching { LocalDate.parse(dayKey) }.getOrNull() ?: return dayKey
        val today = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
        return when {
            date == today -> "今天"
            date == today.minusDays(1) -> "昨天"
            date.isAfter(today.minusDays(7)) -> weekday(date)
            date.year == today.year -> "${date.monthValue}月${date.dayOfMonth}日"
            else -> "${date.year}年${date.monthValue}月${date.dayOfMonth}日"
        }
    }

    /**
     * 这条消息前面该不该插分割条。
     *
     * @param prevAt 上一条**有**时间戳的消息（0 表示还没有）
     * @param currentAt 当前这条的时间戳
     *
     * 两条都为 0（老数据）→ 不插；上一条没有而下一条有 → 插（它是已知的最早时刻）。
     */
    fun needsDivider(prevAt: Long, currentAt: Long): Boolean {
        if (currentAt <= 0L) return false
        if (prevAt <= 0L) return true
        return currentAt - prevAt >= DIVIDER_GAP_MS
    }

    /**
     * **记忆管理页**的时间行（v0.49.0）。
     *
     * ## 为什么不用 [forList]
     * [forList] 是**会话列表**的口径：一周内给"周三"（最近聊过，周几能定位到）。
     * 而记忆是「很久以前记下的事」，用户想知道的是**多久以前** ——
     * "3 天前"比"周三"好懂得多。所以这里以**天**为单位，是另一套粒度。
     *
     * 两者共用的纪律不变：`0L` = 时间未知 → 返回空串
     *（宁可没有时间，也不要显示 1970 年）。
     *
     * @param lastAccessedAt 最近一次被检索命中（`0L` = 还没被用过）
     * @return 形如 `3 天前记下` / `今天记下 · 昨天用到过`；时间未知时为空串
     */
    fun forMemory(createdAt: Long, lastAccessedAt: Long, now: Long): String {
        if (createdAt <= 0L) return ""
        val base = when (val days = daysBetween(createdAt, now)) {
            0L -> "今天记下"
            1L -> "昨天记下"
            in 2L..30L -> "$days 天前记下"
            // 超过一个月就给日期：那时的"38 天前"已经不如"7/12"好定位
            else -> "${dateText(createdAt)}记下"
        }
        // 只有**确实被用过**才补后半句。
        // ⚠️ 判据是 `lastAccessedAt > createdAt`：新建时两者相等（还没被检索过），
        //    这时补一句"今天用到过"是假的 —— 它只是刚被写下而已。
        return if (lastAccessedAt > createdAt) {
            "$base · ${usedText(lastAccessedAt, now)}"
        } else {
            base
        }
    }

    /* ─────────────── 内部 ─────────────── */

    /** 两个时刻相差几个**自然日**（按当地时区的日历日算，不是"除以 86400 秒"） */
    private fun daysBetween(at: Long, now: Long): Long {
        val zone = ZoneId.systemDefault()
        val from = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
        val to = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return ChronoUnit.DAYS.between(from, to)
    }

    private fun dateText(at: Long): String {
        val d = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate()
        return "${d.year}/${d.monthValue}/${d.dayOfMonth}"
    }

    private fun usedText(at: Long, now: Long): String = when (val days = daysBetween(at, now)) {
        0L -> "今天用到过"
        1L -> "昨天用到过"
        else -> "$days 天前用到过"
    }

    private fun clock(at: Long): String {
        val t = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
        return "%02d:%02d".format(t.hour, t.minute)
    }

    private fun weekday(date: LocalDate): String =
        WEEKDAYS[date.dayOfWeek.value - 1]
}
