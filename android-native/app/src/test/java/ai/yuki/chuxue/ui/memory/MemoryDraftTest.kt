package ai.yuki.chuxue.ui.memory

import ai.yuki.chuxue.data.room.MemoryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 记忆编辑表单的校验规格。
 *
 * 「能不能保存」这件事不该等到点了按钮才发现 —— 把它做成纯数据上的判定，
 * 界面只需读 `isUsable` 决定按钮是否可用，单测则能把边界逐个钉死。
 */
class MemoryDraftTest {

    private fun entity(
        content: String = "我喜欢在雨天听钢琴",
        category: String = "喜好",
        importance: Int = 7,
    ) = MemoryEntity(
        id = "m1",
        userId = MemoryEntity.LOCAL_USER_ID,
        personaId = "p1",
        sessionId = null,
        scope = MemoryEntity.SCOPE_PERSONA,
        content = content,
        category = category,
        importance = importance,
        source = "manual",
        embedding = null,
        createdAt = 1_000L,
        lastAccessedAt = 2_000L,
        expiresAt = null,
    )

    /* ─────────── 空内容 ─────────── */

    @Test
    fun `空草稿不可保存`() {
        assertFalse(MemoryDraft().isUsable)
    }

    @Test
    fun `纯空白不可保存`() {
        assertFalse(MemoryDraft(content = "   \n\t  ").isUsable)
    }

    @Test
    fun `落库取的是去空白后的正文`() {
        assertEquals("喜欢猫", MemoryDraft(content = "  喜欢猫  ").normalizedContent)
    }

    /* ─────────── 长度边界 ─────────── */

    @Test
    fun `恰好到上限仍可保存`() {
        val d = MemoryDraft(content = "字".repeat(MemoryDraft.MAX_CONTENT_CHARS))
        assertTrue("边界值应当允许", d.isUsable)
        assertFalse(d.isOverLimit)
        assertEquals(0, d.remainingChars)
    }

    @Test
    fun `超出上限一个字符就不可保存`() {
        val d = MemoryDraft(content = "字".repeat(MemoryDraft.MAX_CONTENT_CHARS + 1))
        assertFalse(d.isUsable)
        assertTrue(d.isOverLimit)
        assertEquals(-1, d.remainingChars)
    }

    @Test
    fun `计数器不计入首尾空白`() {
        val d = MemoryDraft(content = "  短  ")
        assertEquals(MemoryDraft.MAX_CONTENT_CHARS - 1, d.remainingChars)
    }

    /* ─────────── 回填与默认值 ─────────── */

    @Test
    fun `从已有记忆回填逐字段一致`() {
        val m = entity(content = "她怕打雷", category = "经历", importance = 9)
        val d = MemoryDraft.from(m)
        assertEquals("她怕打雷", d.content)
        assertEquals("经历", d.category)
        assertEquals(9, d.importance)
        assertTrue(d.isUsable)
    }

    @Test
    fun `默认值落在可保存状态`() {
        val d = MemoryDraft(content = "随便写点什么")
        assertEquals(MemoryDraft.DEFAULT_CATEGORY, d.category)
        assertEquals(MemoryDraft.DEFAULT_IMPORTANCE, d.importance)
        assertTrue(MemoryDraft.CATEGORIES.contains(d.category))
    }

    @Test
    fun `分类是有限集合且含默认项`() {
        assertEquals(listOf("喜好", "经历", "约定", "其他"), MemoryDraft.CATEGORIES)
        assertTrue(MemoryDraft.CATEGORIES.contains(MemoryDraft.DEFAULT_CATEGORY))
    }

    @Test
    fun `默认重要性落在 0 到 10 之间`() {
        assertTrue(MemoryDraft.DEFAULT_IMPORTANCE in 0..10)
    }
}
