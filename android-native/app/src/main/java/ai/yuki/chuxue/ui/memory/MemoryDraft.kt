package ai.yuki.chuxue.ui.memory

import ai.yuki.chuxue.data.room.MemoryEntity

/**
 * 记忆编辑表单的草稿（纯数据 + 纯校验，不依赖 Compose，可在 JVM 单测里断言）。
 *
 * ## 为什么把校验从界面里拎出来
 * 「内容不能为空」「不能超长」是**领域规则**，不是某个输入框的装饰。放在这里之后：
 * 1. 单测能钉住边界（恰好 500 字算不算超、全空白算不算空）；
 * 2. 将来若从别处写记忆（自动提取 Worker、聊天页「记住这条」），走的是同一套校验，
 *    不会出现「手动加的不许超长、自动加的不限」这种分叉。
 *
 * ## 500 字上限的来由
 * 单条记忆是**要进附录**的东西（[ai.yuki.chuxue.data.memory.MemoryInjector]），
 * 而附录每轮都花 token 却不吃缓存。一次注入最多 5 条、总计 1500 字，
 * 所以单条 500 字正好是"三条撑满一屏"的量级 —— 再长就该拆成几条，而不是写成一段。
 */
data class MemoryDraft(
    val content: String = "",
    val category: String = DEFAULT_CATEGORY,
    val importance: Int = DEFAULT_IMPORTANCE,
) {

    /** 去除首尾空白后的正文 —— 落库前必须用它，别把前后空格写进历史。 */
    val normalizedContent: String get() = content.trim()

    val isOverLimit: Boolean get() = normalizedContent.length > MAX_CONTENT_CHARS

    /** 能否保存。空白内容与超长都拦在这里。 */
    val isUsable: Boolean get() = normalizedContent.isNotEmpty() && !isOverLimit

    /** 还能写几个字（界面上的计数器）。负数表示已超。 */
    val remainingChars: Int get() = MAX_CONTENT_CHARS - normalizedContent.length

    companion object {
        const val MAX_CONTENT_CHARS = 500
        const val DEFAULT_IMPORTANCE = 5
        const val DEFAULT_CATEGORY = "其他"

        /**
         * 预设分类。刻意用**有限集合**而不是自由输入框：分类只影响列表分组，
         * 允许自由填会立刻长出一堆「爱好/喜好/喜欢」这种同义标签，分组也就失去意义。
         */
        val CATEGORIES = listOf("喜好", "经历", "约定", "其他")

        /** 从已有记忆回填表单（编辑态）。 */
        fun from(memory: MemoryEntity): MemoryDraft = MemoryDraft(
            content = memory.content,
            category = memory.category,
            importance = memory.importance,
        )
    }
}
