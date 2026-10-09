package ai.yuki.chuxue.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 人设**列表置顶**的持久化契约（v0.44.0，长按菜单里加的那一项）。
 *
 * ## ⚠️ 为什么单开一个文件
 * 与 `PersonaEmojiChanceCodecTest` 同一个理由：`CodecTest` 里那两条「人设往返」
 * 构造 `Persona` 时**不设新字段**，而新字段的默认值恰好就是"没置顶" ——
 * "缺失 == 缺失"照样通过，**看起来是绿的**。
 * 所以这里刻意用**非默认值**（true）走一遍往返：`PersonaCodec` 漏搬这一栏就会红。
 */
class PersonaPinCodecTest {

    @Test
    fun `置顶能往返 —— 用非默认值测，漏搬就会红`() {
        val p = Persona(
            id = "p1",
            userNickname = "阿澈",
            userGender = "男",
            customPrompt = "角色名称：初雪",
            isPinned = true,
        )
        val back = PersonaCodec.decode(PersonaCodec.encode(listOf(p)))
        assertEquals(1, back.size)
        assertTrue("置顶标记没有原样读回来", back[0].isPinned)
    }

    @Test
    fun `没置顶的仍是 false —— 默认值不会被写成 true`() {
        val p = Persona(id = "p2", userNickname = "我", userGender = "女", customPrompt = "x")
        val back = PersonaCodec.decode(PersonaCodec.encode(listOf(p)))
        assertFalse(back[0].isPinned)
    }

    @Test
    fun `老数据缺这一栏时读成 false —— 升级后行为与升级前一致`() {
        // 手工造一份 v0.43.x 的 JSON（那时还没有 isPinned 这一栏）。
        // 这条钉的是真实升级路径：用户机器上躺着的就是这种数据。
        val legacy = """
            [{"id":"old","userNickname":"你","userGender":"女",
              "customPrompt":"角色名称：旧人设","createdAt":1,"updatedAt":2}]
        """.trimIndent()
        val back = PersonaCodec.decode(legacy)
        assertEquals(1, back.size)
        assertFalse("缺字段应当读成「没置顶」", back[0].isPinned)
    }

    @Test
    fun `置顶不影响其它字段的往返`() {
        val p = Persona(
            id = "p3",
            userNickname = "阿澈",
            userGender = "男",
            customPrompt = "角色名称：初雪",
            personality = "温柔",
            greeting = "你来啦",
            emojiChanceOverride = 0.25f,
            isPinned = true,
        )
        val back = PersonaCodec.decode(PersonaCodec.encode(listOf(p)))[0]
        assertTrue(back.isPinned)
        assertEquals("温柔", back.personality)
        assertEquals("你来啦", back.greeting)
        assertEquals(0.25f, back.emojiChanceOverride!!, 0.0001f)
    }
}
