package ai.yuki.chuxue.data

import java.time.Instant
import java.time.ZoneId

/**
 * 把消息按**自然日**分组 —— 「按日期查找」的数据层（v0.51.0）。
 *
 * ## 为什么要它
 * 用户要求「查找聊天记录增加**微信那种按日期查找**」。
 * 微信那套的实质是：**先把时间轴列出来**（哪天有聊天、各多少条），
 * 点某一天就跳到那里 —— 而不是让你在一个关键词输入框里猜。
 *
 * ## 为什么是纯函数
 * 与 [MessageSearch] / [TimeLabels] 同一套路：日期分组的边界（跨日、时区、
 * 老消息时间戳为 0）全是**容易错且容易测**的东西 —— 抽出来就能在 JVM 上钉死，
 * 不必等真机。
 *
 * ## ⚠️ 它作用于**全量** messages，不是渲染窗口
 * 与搜索同理（见 `ChatScreen` 里 `MessageSearch.query` 调用点的注释）：
 * `ChatWindow` 只管画多少条，日期索引必须能看到全部历史。
 */
object DateIndex {

    /**
     * 一天（自然日）。
     *
     * @param dayKey     `2026-09-30`（本地时区的自然日，可直接当分组键与展示用）
     * @param firstIndex 这一天**第一条**消息在 `messages` 里的下标 —— 点它就去那天开头
     * @param count      这一天有多少条
     * @param preview    那天第一条的摘要（已剥附录、已压换行、已截断）
     */
    data class DayBucket(
        val dayKey: String,
        val firstIndex: Int,
        val count: Int,
        val preview: String,
    )

    /** 摘要最长多少字。够认出"那天在聊什么"即可，太长会把一行撑成大段。 */
    private const val PREVIEW_MAX = 40

    /**
     * 按自然日分组，**最近的日期在前**。
     *
     * ⚠️ **时间戳为 0 的消息被跳过**（加 `createdAt` 字段之前存下的老消息）。
     * 与 [TimeLabels] 同一条口径 —— 给它们编一个 1970 年的日期，
     * 会在日期列表顶上凭空多出一个"1970-01-01"，那比不显示更糟。
     */
    fun days(
        messages: List<ChatMessage>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<DayBucket> {
        // ⚠️ 用 LinkedHashMap 保序没意义（最后要按日期排序），
        //    但**必须先遍历再排序**：firstIndex 只能在第一次遇到那天时定下来。
        val acc = LinkedHashMap<String, MutableDay>()

        messages.forEachIndexed { index, msg ->
            if (msg.createdAt <= 0L) return@forEachIndexed
            val day = Instant.ofEpochMilli(msg.createdAt).atZone(zone).toLocalDate().toString()
            val existing = acc[day]
            if (existing == null) {
                acc[day] = MutableDay(
                    dayKey = day,
                    firstIndex = index,
                    count = 1,
                    preview = previewOf(msg),
                )
            } else {
                existing.count += 1
                // 首条摘要空（比如那条只有图）时，往后找第一条有字的补上 ——
                // 否则日期列表上会出现一个只有日期、没有任何线索的空行。
                if (existing.preview.isEmpty()) existing.preview = previewOf(msg)
            }
        }

        return acc.values
            .map { DayBucket(it.dayKey, it.firstIndex, it.count, it.preview) }
            .sortedByDescending { it.dayKey }
    }

    private class MutableDay(
        val dayKey: String,
        val firstIndex: Int,
        var count: Int,
        var preview: String,
    )

    /** 一条消息的单行摘要：剥附录 → 压换行 → 截断。 */
    private fun previewOf(msg: ChatMessage): String {
        val text = TranscriptText.stripAppendix(msg.content)
            .replace('\n', ' ')
            .replace('\r', ' ')
            .trim()
        return if (text.length <= PREVIEW_MAX) text else text.take(PREVIEW_MAX) + "…"
    }
}
