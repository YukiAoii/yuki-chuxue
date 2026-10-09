package ai.yuki.chuxue.data

/**
 * 消息正文的**显示层**处理。
 *
 * ## 它修的是什么
 * `PromptEngine.plan()` 会把后台注入的附录（`<appendix><memories>…`）拼进用户消息的
 * content —— 那是**给模型看的**（记忆、时间、约束）。但它与用户消息共用同一个
 * `ChatMessage` 实例，而 ViewModel 把那个实例同时发往 API、写进历史、又拿去上屏，
 * 于是"给模型看的记忆"被原样显示在了用户的气泡里。
 *
 * ## 为什么不改数据、只改显示（重要）
 * 附录进历史是**刻意的**：它保证「本轮请求的字节」与「下一轮从历史读出的字节」一致。
 * 若把它从历史里剥掉，每轮都会在最近一条用户消息处断开 —— 缓存命中率会塌。
 * 那是本项目最贵的教训（§11 三铁律：已写入的内容不再改写）。
 *
 * 所以：**数据一个字不动，只在渲染前剥掉。** 发给模型的请求、写进历史的字节，
 * 与修这个 bug 之前完全一致；变的只有用户眼睛看到的东西。
 */
object TranscriptText {

    private const val OPEN = "<appendix>"
    private const val CLOSE = "</appendix>"

    /**
     * 剥掉开头的附录块，返回用户真正说的话。
     *
     * 三条纪律：
     * 1. **只剥开头的** —— 用户完全可能自己聊到 "appendix" 这个词，
     *    正文中间出现的一律不动；
     * 2. **结构不完整就原样返回** —— 宁可显示难看，也不凭猜测去截断用户的内容；
     * 3. 多段附录连着出现时整体剥掉（正常情况一轮只有一段，容错不吃亏）。
     */
    fun stripAppendix(raw: String): String {
        var text = raw.trimStart()
        if (!text.startsWith(OPEN)) return raw

        while (text.startsWith(OPEN)) {
            val end = text.indexOf(CLOSE)
            if (end < 0) return raw // 没有闭合标签 → 不猜
            text = text.substring(end + CLOSE.length).trimStart()
        }
        return text.trim()
    }
}
