package ai.yuki.chuxue.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 人设「绑定哪个用户人设」（`Persona.userPersonaId`）的持久化契约（v0.61.41）。
 *
 * ⚠️ 与 `PersonaXinchaoCodecTest` 同一个理由单开文件：用**非默认值**（非 null）
 * 走一遍往返，`PersonaCodec` 漏搬这一栏就会红。
 */
class PersonaUserPersonaCodecTest {

    @Test
    fun `userPersonaId 能往返 —— 用非默认值测，漏搬就会红`() {
        val p = Persona(
            id = "p1",
            userNickname = "阿澈",
            userGender = "男",
            customPrompt = "角色名称：初雪",
            userPersonaId = "up1",
        )
        val back = PersonaCodec.decode(PersonaCodec.encode(listOf(p)))
        assertEquals(1, back.size)
        assertEquals("up1", back[0].userPersonaId)
    }

    @Test
    fun `老数据缺这一栏时读成 null —— 不注入任何用户人设，升级前后行为一致`() {
        val legacy = """[{"id":"old","userNickname":"你","userGender":"女","customPrompt":"角色名称：旧人设"}]"""
        assertNull(PersonaCodec.decode(legacy)[0].userPersonaId)
    }

    @Test
    fun `空串读成 null —— 一律当"没绑定"（不让空 id 变成悬空引用）`() {
        val blank = """
            [{"id":"b","userNickname":"你","userGender":"女","customPrompt":"x","userPersonaId":""}]
        """.trimIndent()
        assertNull(PersonaCodec.decode(blank)[0].userPersonaId)
    }

    @Test
    fun `绑定成 null 之后也能往返（解绑场景）`() {
        val p = Persona(id = "p2", userNickname = "", userGender = "女", customPrompt = "x")
        val back = PersonaCodec.decode(PersonaCodec.encode(listOf(p)))
        assertNull(back[0].userPersonaId)
    }
}
