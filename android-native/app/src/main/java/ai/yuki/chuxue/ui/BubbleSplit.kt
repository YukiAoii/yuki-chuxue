package ai.yuki.chuxue.ui

import ai.yuki.chuxue.data.SEND_MODE_STREAM
import ai.yuki.chuxue.data.TextSegments

/**
 * **回复正文 → 气泡序列**（纯函数，可单测 —— 见 `BubbleSplitTest`）。
 *
 * ## 它要解决的问题
 * 流式回复是**一长串累积文本**，而「分段气泡」要把它切成"连续蹦出来的几枚短气泡"，
 * 像真人发微信那样。所以要做三件事：
 *   1. 只把**已经说完的句子**切出来；最后一个不完整的尾巴单独算一枚（它正在长）；
 *   2. 碎片要有规矩 —— 纯标点的碎片不占一枚气泡（见下），总数压到上限以内；
 *   3. 太碎的合并规则**刻意不做** —— 「嗯。」「好的。」独立成枚正是这个观感想要的。
 *
 * ⚠️ **只影响渲染**：气泡文本由回复正文现场切分，历史与请求体一个字节都不动 ——
 * 拆多枚**不会**让任何会话的缓存前缀失效。
 *
 * ## 历史沿革（别重新发明）
 * 这套拆句逻辑来自 v0.32.0 的「连发」模式（`ui/WaifuBubbles`，v0.61.0 随连发删除）。
 * v0.61.4 按用户要求在**流式模式**上恢复它 —— 不再是独立模式，而是流式渲染的一部分，
 * 并可用设置（[ai.yuki.chuxue.data.AppSettings.splitBubbles]）关掉。
 * 原先踩过的坑全部保留在这里：纯标点不独占气泡 / 代码块不拆 / 总数上限 / 最新句保持独立。
 */
object BubbleSplit {

    /**
     * 一顿话最多拆成几枚。用户当年给的定义是 2–4。
     *
     * ⚠️ 这里**刻意不做「短句合并」**：第一版实现写过一条 `MIN_CHARS` 合并规则，
     * 结果「你好。今天天气不错。」被并成了一枚 —— 而「嗯。」「好的。」这类短句
     * 独立成枚**正是这个观感想要的**（真人发微信就是这样）。
     */
    const val MAX_BUBBLES = 4

    /**
     * 一条助手回复**要画成几枚气泡** —— 流式与历史两条渲染路径的**唯一入口**。
     *
     * ⚠️ 为什么非共用一个不可：连发时代曾经只有流式气泡按模式拆多枚，
     * 落库后改由历史气泡渲染 —— 那条路径没拆，于是「说着说着，一落库就变回一大坨」。
     * 判定逻辑收在这里，两条路径谁都不许再各写一份。
     *
     * @param splitEnabled 设置开关（「分段气泡」，默认开）
     * @param sendMode 这条回复的呈现方式（**非流式整段一枚** —— 它本来就是"一次性出现"）
     */
    fun bubbles(content: String, splitEnabled: Boolean, sendMode: String): List<String> {
        if (!splitEnabled || sendMode != SEND_MODE_STREAM) return listOf(content)
        // 含代码块的回复不拆：拆句按句末标点切，而代码里的 `.` `;` 会被当成句子边界 ——
        // 一段代码被切成好几枚气泡、每枚还各渲染一遍 Markdown，只会更难读
        if (content.isBlank() || content.contains("```")) return listOf(content)
        val parts = split(content).filter { it.isNotBlank() }
        // 极端情况（整段只有标点）：宁可照原样画一枚，也不要渲染出"零枚气泡"
        return parts.ifEmpty { listOf(content) }
    }

    /**
     * **历史消息**要画成几枚 —— 渲染层的唯一判定（含「老消息保持原样」，v0.61.6）。
     *
     * @param messageSplit 这条消息**自己的**快照
     *        （[ai.yuki.chuxue.data.ChatMessage.splitBubbles]）。
     *        `null` = v0.61.6 之前的老消息 → **恒为一枚**（用户要求：老消息保持原样，
     *        不能让它们随着新设置"突然长出几枚本来没有的气泡"）。
     *        非 null = 发送那一刻的设置快照 —— **只认它**，之后改设置不影响已说过的话。
     */
    fun forMessage(content: String, messageSplit: Boolean?, sendMode: String): List<String> =
        if (messageSplit == null) {
            listOf(content)
        } else {
            bubbles(content, messageSplit, sendMode)
        }

    /**
     * 把累积文本拆成气泡序列。
     *
     * ⚠️ 返回的**最后一枚是"正在长的那一枚"**（可能是空串）——
     * 调用方据此决定要不要画「正在输入」。
     *
     * 切分依据与 `TextSegments` **共用同一份标点集**（`SENTENCE_END`）——
     * 两处口径必须一致，否则会出现"会话列表预览是这句、聊天页末尾是另一句"那种错位
     *（v0.45.5 修过的缺陷）。换行也算"一句说完"，但换行符本身不进气泡
     *（把它画进一枚小气泡只会让那枚看起来坏掉了）。
     */
    fun split(text: String): List<String> {
        if (text.isEmpty()) return listOf("")

        val sentences = mutableListOf<String>()
        val buf = StringBuilder()
        text.forEach { ch ->
            if (ch == '\n') {
                if (buf.isNotEmpty()) {
                    sentences += buf.toString()
                    buf.clear()
                }
            } else {
                buf.append(ch)
                if (ch in TextSegments.SENTENCE_END) {
                    // ⚠️ 只切出**有实义**的句子（v0.44.4 修）。
                    // `SENTENCE_END` 里有 `…` 与 `.`，而模型很爱写 "喂……" 这种省略号 ——
                    // 它会被切成 "喂…" + 单独一个 "…"：第二枚是**纯标点**，
                    // 画出来就是一枚"什么都没有"的气泡（用户报过的"部分标点被当做单独气泡"）。
                    val s = buf.toString()
                    if (s.any { it.isLetterOrDigit() }) sentences += s
                    buf.clear()
                }
            }
        }
        // buf 里剩下的就是还没说完的尾巴 —— 它永远是最后一枚
        val tail = buf.toString()

        // ⚠️ 兜底（v0.44.4）：整段**只有**标点或省略号时，上面那条"丢弃纯标点碎片"
        // 会把所有句子都丢掉 —— 而"零枚气泡"比"一枚纯标点"更糟（屏幕上什么都没有）。
        if (sentences.isEmpty() && tail.isBlank() && text.isNotBlank()) return listOf(text)

        return capCount(sentences) + tail
    }

    /**
     * 超出 [MAX_BUBBLES] 时，把**最早的那些**并成一枚。
     * 末尾 [MAX_BUBBLES] - 1 枚保持独立 —— 用户看的是最新那几条。
     */
    private fun capCount(sentences: List<String>): List<String> {
        if (sentences.size <= MAX_BUBBLES) return sentences
        val keep = MAX_BUBBLES - 1
        return listOf(sentences.dropLast(keep).joinToString("")) + sentences.takeLast(keep)
    }
}
