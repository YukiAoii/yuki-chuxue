package ai.yuki.chuxue.data

/**
 * 文本切分（纯函数）。
 *
 * ## 它存在的唯一理由：**「最后一句」这套口径要两边共用**
 * - `Models.kt` 用它取会话预览的最后一句；
 * - `data/Session.preview` 用它把会话列表的预览取成**最后一句**。
 *
 * ⚠️ 这两处必须用**同一套标点集**，否则就会出现用户报过的那种错位：
 * 列表上显示的是第一句，聊天页末尾明明是另一句。
 * 之前预览是按「换行」取的，而连发是按「句末标点」拆的 —— 两边根本不是一回事。
 *
 * ## 为什么放在 `data` 而不是 `ui`
 * `Session` 在 `data` 层，它**不能**反向依赖 `ui` 层的东西
 *（那会让数据层被迫知道界面怎么渲染）。所以切句口径住在 `data`，
 * ⚠️ `ui/WaifuBubbles` 已于 v0.61.0 随「连发」模式一起删除，本文件不再被它引用。
 */
object TextSegments {

    /**
     * 句末标点（切句依据）。**换行也算**。
     *
     * ⚠️ 改这里等于同时改「气泡怎么拆」和「列表预览取哪句」，
     * 两处会一起变 —— 这是刻意的，因为它们本来就该一致。
     */
    const val SENTENCE_END = "。！？…；!?;.\n"

    /**
     * 取**最后一句有实义的话**。
     *
     * 用于会话列表的预览：连发模式下她的一条回复会被画成好几枚气泡，
     * 用户在聊天页看到的是**末尾那枚**，预览就该显示那一句。
     *
     * ⚠️ 「有实义」= 至少含一个字母或数字。省略号连写（`喂……`）会被切成
     * `喂…` 和一个孤零零的 `…`，后者是**纯标点**，拿它当预览等于什么都没显示。
     *
     * @return 找不到任何有实义的分句时，返回整段（宁可长一点，也不要空）
     */
    fun lastSentence(text: String): String {
        if (text.isBlank()) return text
        var buf = StringBuilder()
        var last: String? = null
        text.forEach { ch ->
            if (ch == '\n') {
                if (buf.isNotEmpty()) {
                    val s = buf.toString()
                    if (s.any { it.isLetterOrDigit() }) last = s
                    buf = StringBuilder()
                }
            } else {
                buf.append(ch)
                if (ch in SENTENCE_END) {
                    val s = buf.toString()
                    if (s.any { it.isLetterOrDigit() }) last = s
                    buf = StringBuilder()
                }
            }
        }
        // 还没说完的尾巴：它比之前任何一句都新，优先用它
        val tail = buf.toString()
        if (tail.any { it.isLetterOrDigit() }) return tail.trim()
        return last?.trim() ?: text.trim()
    }
}
