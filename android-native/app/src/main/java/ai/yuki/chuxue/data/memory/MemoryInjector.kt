package ai.yuki.chuxue.data.memory

import ai.yuki.chuxue.data.room.MemoryEntity

/**
 * 把检索到的记忆转成附录里的文本行（开发文档 §7.7）。
 *
 * ## 为什么只产出 `List<String>`，不自己拼 `<memories>` 标签
 * 标签的拼装已经由 `PromptEngine.buildAppendix` 负责，而且**必须**由它负责：
 * 附录是与冻结前缀并列的那一层结构，形状只能有一处定义。若这里再拼一份，
 * 两边的缩进/换行稍有出入，同一批记忆就会渲染出两种字节 —— 而"字节稳定"正是
 * 这个项目的命根子。所以 Injector 只做**内容预处理**，不做结构。
 *
 * ## 为什么必须转义（这不是洁癖，是安全）
 * 记忆内容有两个来路：用户手写、以及将来从对话里自动提取（后者可能包含
 * 任何模型输出）。它们都会作为文本进入 `<memory>…</memory>`。
 * 一旦内容里带 `<` 或 `&`：
 * 1. **结构会被撑破** —— 模型看到的是一段畸形的 XML，标签的语义没了；
 * 2. **可能被当成指令** —— 记忆是"数据"，但如果它能拼出 `</memories>` 加一段伪指令，
 *    数据就混进了指令通道。转义把这条缝堵死。
 *
 * 转义顺序必须是 `&` 最先（否则 `&lt;` 里的 `&` 会被二次转义成 `&amp;lt;`）。
 *
 * ## 为什么有总长上限
 * 检索上限已经限了**条数**（5 条），但单条长度没有上限。附录不吃缓存却照样花
 * token，所以再加一道**字符总量**的闸：超出的条目整条丢弃，而不是截断 ——
 * 截断后的半句话比不注入更糟（模型会试图补全一个残缺的事实）。
 */
object MemoryInjector {

    /** 注入进附录的记忆正文总长上限（字符）。 */
    const val MAX_TOTAL_CHARS = 1500

    /** 把记忆条目转成可直接交给 `PromptEngine.buildAppendix(memories = …)` 的文本行。 */
    fun buildMemoryLines(memories: List<MemoryEntity>): List<String> {
        val lines = ArrayList<String>(memories.size)
        var used = 0
        for (memory in memories) {
            val escaped = escapeXml(memory.content.trim())
            if (escaped.isEmpty()) continue
            if (used + escaped.length > MAX_TOTAL_CHARS) break
            lines += escaped
            used += escaped.length
        }
        return lines
    }

    /** XML 文本转义。`&` 必须最先替换。 */
    fun escapeXml(text: String): String =
        text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
}
