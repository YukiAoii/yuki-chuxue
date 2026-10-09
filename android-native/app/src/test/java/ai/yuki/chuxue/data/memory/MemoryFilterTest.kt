package ai.yuki.chuxue.data.memory

import ai.yuki.chuxue.data.room.MemoryEntity
import ai.yuki.chuxue.ui.memory.MemoryDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 记忆列表筛选与排序的规格（开发文档 §42）。
 *
 * ## ⚠️ 2026-09-28：筛选维度从「scope」改成了「分类」
 * 记忆的归属改成**只属于会话**之后，所有记忆的 scope 都是 session ——
 * 原来按 scope 分的「角色记忆 / 剧情记忆」里，**前者永远是空的**。
 * 用户看到的就是这个（「角色记忆里啥也没有，全在剧情记忆里」）。
 * 现在按分类分档：喜好 / 经历 / 约定 / 其他。
 *
 * 这一层是纯函数，所以「哪些该出现、按什么次序」可以被精确钉住 ——
 * 而它恰好是用户唯一能直接看到的那部分（记忆管理页的列表）。
 */
class MemoryFilterTest {

    private fun memory(
        id: String,
        category: String = "其他",
        scope: String = MemoryEntity.SCOPE_SESSION,
        importance: Int = 5,
        lastAccessedAt: Long = 0L,
    ) = MemoryEntity(
        id = id,
        userId = MemoryEntity.LOCAL_USER_ID,
        personaId = "p1",
        sessionId = if (scope == MemoryEntity.SCOPE_SESSION) "s1" else null,
        scope = scope,
        content = "内容 $id",
        category = category,
        importance = importance,
        source = "manual",
        embedding = null,
        createdAt = 0L,
        lastAccessedAt = lastAccessedAt,
        expiresAt = null,
    )

    private fun ids(list: List<MemoryEntity>) = list.map { it.id }

    /* ─────────── 筛选（按分类）─────────── */

    @Test
    fun `全部档返回所有记忆`() {
        val all = listOf(memory("a", category = "喜好"), memory("b", category = "经历"))
        assertEquals(listOf("a", "b"), ids(MemoryListQuery.apply(all, MemoryFilter.ALL)).sorted())
    }

    @Test
    fun `喜好档只留喜好`() {
        val all = listOf(memory("keep", category = "喜好"), memory("drop", category = "经历"))
        assertEquals(listOf("keep"), ids(MemoryListQuery.apply(all, MemoryFilter.PREFERENCE)))
    }

    @Test
    fun `约定档只留约定`() {
        val all = listOf(memory("drop", category = "喜好"), memory("keep", category = "约定"))
        assertEquals(listOf("keep"), ids(MemoryListQuery.apply(all, MemoryFilter.PROMISE)))
    }

    @Test
    fun `其他档收纳一切不在预设里的分类`() {
        // 老数据、或自动提取可能产出别的词 —— 它们**不该从按档翻里消失**
        val all = listOf(
            memory("known", category = "喜好"),
            memory("stranger", category = "自动提取的怪分类"),
            memory("other", category = "其他"),
        )
        assertEquals(
            listOf("other", "stranger"),
            ids(MemoryListQuery.apply(all, MemoryFilter.OTHER)).sorted(),
        )
    }

    @Test
    fun `数据层的预设分类与界面层完全一致`() {
        // 数据层不能引用 ui 层，所以两边各写了一份 ——
        // 这条断言就是那份"约束"：谁只改一边，它会立刻红
        assertEquals(
            MemoryDraft.CATEGORIES.filter { it != "其他" },
            MemoryFilter.KNOWN_CATEGORIES,
        )
    }

    @Test
    fun `空空如也时返回空表而不是抛错`() {
        MemoryFilter.entries.forEach { f ->
            assertTrue(ids(MemoryListQuery.apply(emptyList(), f)).isEmpty())
        }
    }

    @Test
    fun `筛选档的标签就是它收的那一类`() {
        assertEquals(
            listOf("全部", "喜好", "经历", "约定", "其他"),
            MemoryFilter.entries.map { it.label },
        )
    }

    /* ─────────── 排序 ─────────── */

    @Test
    fun `按重要性降序`() {
        val all = listOf(memory("low", importance = 3), memory("high", importance = 9))
        assertEquals(listOf("high", "low"), ids(MemoryListQuery.apply(all, MemoryFilter.ALL)))
    }

    @Test
    fun `同重要性时最近被想起的在前`() {
        val all = listOf(
            memory("stale", importance = 5, lastAccessedAt = 100),
            memory("fresh", importance = 5, lastAccessedAt = 900),
        )
        assertEquals(listOf("fresh", "stale"), ids(MemoryListQuery.apply(all, MemoryFilter.ALL)))
    }

    @Test
    fun `前两级都相同时按 id 稳定排序`() {
        // 输入顺序与期望相反：若没有末级 id，排序结果就取决于调用方给的顺序
        val all = listOf(
            memory("m2", importance = 5, lastAccessedAt = 100),
            memory("m1", importance = 5, lastAccessedAt = 100),
        )
        assertEquals(listOf("m1", "m2"), ids(MemoryListQuery.apply(all, MemoryFilter.ALL)))
    }

    @Test
    fun `重要性优先于访问时间`() {
        // 旧但很重要的记忆，应当压过新但不重要的
        val all = listOf(
            memory("newMinnow", importance = 1, lastAccessedAt = 9_000),
            memory("oldAnchor", importance = 10, lastAccessedAt = 1),
        )
        assertEquals(listOf("oldAnchor", "newMinnow"), ids(MemoryListQuery.apply(all, MemoryFilter.ALL)))
    }

    @Test
    fun `筛选项的 accepts 与 apply 行为一致`() {
        val like = memory("like", category = "喜好")
        val other = memory("other", category = "别的")
        assertTrue(MemoryFilter.ALL.accepts(like) && MemoryFilter.ALL.accepts(other))
        assertTrue(MemoryFilter.PREFERENCE.accepts(like))
        assertFalse(MemoryFilter.PREFERENCE.accepts(other))
        assertTrue(MemoryFilter.OTHER.accepts(other))
        assertFalse(MemoryFilter.OTHER.accepts(like))
    }
}
