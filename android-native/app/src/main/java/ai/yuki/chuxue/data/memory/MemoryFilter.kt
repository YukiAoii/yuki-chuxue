package ai.yuki.chuxue.data.memory

import ai.yuki.chuxue.data.room.MemoryEntity

/**
 * 记忆列表的筛选档。
 *
 * ## ⚠️ 2026-09-28 二次修正：从「按 scope」改成「按分类」
 * 这里原来是「全部 / 角色记忆 / 剧情记忆」，按 `scope`（persona / session）分档 ——
 * 那是记忆还跟着**人设**走时的设计。
 *
 * 同一天记忆的归属改成了**只属于会话**：所有新记忆的 scope 都是 session，
 * 于是「角色记忆」这一档**永远是空的**。用户看到的就是这个：
 * 「角色记忆里啥也没有，全在剧情记忆里」——**不正常，是那次改动漏掉的消费方**。
 *
 * 现在按**分类**分档：scope 已经没有区分度，而分类是用户写记忆时真的选过的那个字段。
 */
enum class MemoryFilter(val label: String) {
    ALL("全部"),
    PREFERENCE("喜好"),
    EXPERIENCE("经历"),
    PROMISE("约定"),
    OTHER("其他"),
    ;

    fun accepts(memory: MemoryEntity): Boolean = when (this) {
        ALL -> true
        // 不在预设里的分类（老数据、或自动提取产出的别的词）统一落进「其他」——
        // 否则它们只在「全部」里可见，用户按档翻时会以为丢了
        OTHER -> memory.category !in KNOWN_CATEGORIES
        else -> memory.category == label
    }

    companion object {
        /**
         * 预设分类。
         *
         * ⚠️ 必须与 `ui/memory/MemoryDraft.CATEGORIES` 一致 —— 数据层不能引用 ui 层，
         * 所以这里重复了一份，并由 `MemoryFilterTest` 钉住两边一致。
         * （重复而不加约束，就是下一处「悄无声息走偏」的温床。）
         */
        val KNOWN_CATEGORIES = listOf("喜好", "经历", "约定")
    }
}

/**
 * 记忆列表的筛选与排序（纯函数，可在 JVM 单测里直接断言）。
 *
 * ## 排序键为什么是这个次序
 * 1. **重要性降序** —— 用户来这一页最想看到"她最记得什么"；
 * 2. **最近被想起降序** —— 同重要性时，活跃的在前（与 [MemoryDecay] 的取向一致）；
 * 3. **id 升序** —— 兜底，保证同一份数据两次调用得到同样的排列。
 *    没有这一级，前两级相等的条目顺序取决于调用方给的顺序，测试会偶发假红。
 *
 * ⚠️ 这里**只做筛选与排序**，不改变可见性 —— 能拿到哪些记忆由
 * [MemoryDao.observeBySession][ai.yuki.chuxue.data.room.MemoryDao.observeBySession]
 * 的 WHERE 框死（强制带 `userId` + `personaId` + `sessionId`，2026-09-28 起记忆按会话归属）。
 * 可见性一旦散到多层，就迟早有一层漏掉。
 */
object MemoryListQuery {

    fun apply(memories: List<MemoryEntity>, filter: MemoryFilter): List<MemoryEntity> =
        memories
            .filter(filter::accepts)
            .sortedWith(
                compareByDescending<MemoryEntity> { it.importance }
                    .thenByDescending { it.lastAccessedAt }
                    .thenBy { it.id },
            )
}
