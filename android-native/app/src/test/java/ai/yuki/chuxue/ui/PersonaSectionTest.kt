package ai.yuki.chuxue.ui

import ai.yuki.chuxue.data.Persona
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 人设列表的**排序与分组**契约（v0.44.0）。
 *
 * ⚠️ 包名是 `ai.yuki.chuxue.ui` 而不是 `ui.persona` —— 因为 `PersonaScreen.kt`
 * 自己的包声明就是 `ai.yuki.chuxue.ui`（那一页早于 `ui/persona/` 这个目录，
 * 与 `PersonaFilterTest` 同一个理由）。
 *
 * 这一段是纯函数，所以能把每条边界在 JVM 上钉死 —— 列表顺序在真机上靠肉眼很难验，
 * 尤其"置顶那一组内部该按什么排"这种事，不看断言根本说不清。
 */
class PersonaSectionTest {

    private fun p(id: String, pinned: Boolean = false, createdAt: Long = 0L) = Persona(
        id = id,
        userNickname = "n",
        userGender = "保密",
        customPrompt = "角色名称：$id",
        isPinned = pinned,
        createdAt = createdAt,
    )

    @Test
    fun `置顶的单独成组、且排最前`() {
        val sections = buildPersonaSections(
            listOf(p("a"), p("b", pinned = true)),
            lastChatAt = emptyMap(),
            byRecentChat = false,
        )
        assertEquals("置顶", sections.first().title)
        assertEquals(listOf("b"), sections.first().items.map { it.id })
    }

    @Test
    fun `没置顶的再按聊过、没聊过分开`() {
        val sections = buildPersonaSections(
            listOf(p("a"), p("b"), p("c", pinned = true)),
            lastChatAt = mapOf("a" to 100L),
            byRecentChat = false,
        )
        assertEquals(listOf("置顶", "最近聊过", "其他"), sections.map { it.title })
        assertEquals(listOf("a"), sections[1].items.map { it.id })
        assertEquals(listOf("b"), sections[2].items.map { it.id })
    }

    @Test
    fun `全都没聊过时标题直接叫全部人设 —— 不要「其他」`() {
        val sections = buildPersonaSections(listOf(p("a"), p("b")), emptyMap(), false)
        assertEquals(listOf("全部人设"), sections.map { it.title })
    }

    @Test
    fun `按最近聊过排序：聊得越近越靠前，没聊过的垫底`() {
        val sections = buildPersonaSections(
            listOf(p("old"), p("new"), p("never")),
            lastChatAt = mapOf("old" to 100L, "new" to 900L),
            byRecentChat = true,
        )
        assertEquals(listOf("new", "old", "never"), sections.flatMap { it.items }.map { it.id })
    }

    @Test
    fun `按创建时间排序：新的靠前`() {
        val sections = buildPersonaSections(
            listOf(p("a", createdAt = 100L), p("b", createdAt = 900L)),
            lastChatAt = emptyMap(),
            byRecentChat = false,
        )
        assertEquals(listOf("b", "a"), sections.flatMap { it.items }.map { it.id })
    }

    @Test
    fun `置顶组内部也遵守所选排序 —— 不是随机顺序`() {
        val sections = buildPersonaSections(
            listOf(
                p("p1", pinned = true, createdAt = 100L),
                p("p2", pinned = true, createdAt = 900L),
            ),
            lastChatAt = emptyMap(),
            byRecentChat = false,
        )
        assertEquals(listOf("p2", "p1"), sections.first().items.map { it.id })
    }

    @Test
    fun `空列表进空列表出 —— 不崩、也不造出空标题`() {
        assertTrue(buildPersonaSections(emptyList(), emptyMap(), true).isEmpty())
    }
}
