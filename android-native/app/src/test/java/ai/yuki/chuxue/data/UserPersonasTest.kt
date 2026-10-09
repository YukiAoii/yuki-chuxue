package ai.yuki.chuxue.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「角色 → 当前绑定的用户人设」解析（v0.61.41，纯函数）。
 *
 * 三个消费方（冻结前缀 / 上下文估算 / 记忆抽取）必须**共用这一个口径** ——
 * 各写各的就会出现"前缀注入了、估算没算"那种不一致。
 */
class UserPersonasTest {

    private fun persona(bind: String?) = Persona(
        id = "p1",
        userNickname = "",
        userGender = "女",
        customPrompt = "角色名称：初雪",
        userPersonaId = bind,
    )

    private fun up(id: String, text: String = "刀客，说话利落") =
        UserPersona(id = id, name = "快刀", roleText = text)

    @Test
    fun `绑定了就找到`() {
        val r = UserPersonas.resolve(persona("up1"), listOf(up("up1")))
        assertEquals("up1", r?.id)
    }

    @Test
    fun `没绑定给 null`() {
        assertNull(UserPersonas.resolve(persona(null), listOf(up("up1"))))
        assertNull(UserPersonas.resolve(persona(""), listOf(up("up1"))))
    }

    @Test
    fun `绑定的 id 不存在给 null —— 人设被删后的悬空引用不注入空壳`() {
        assertNull(UserPersonas.resolve(persona("gone"), listOf(up("up1"))))
    }

    @Test
    fun `roleText 只有空白的不注入 —— 没内容可拼就不进前缀`() {
        assertNull(UserPersonas.resolve(persona("up1"), listOf(up("up1", text = "   "))))
    }

    @Test
    fun `多个人设里挑出绑定的那一个`() {
        val all = listOf(up("a"), up("b", "说书人"), up("c"))
        assertEquals("b", UserPersonas.resolve(persona("b"), all)?.id)
    }
}
