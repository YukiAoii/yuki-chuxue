package ai.yuki.chuxue.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 人设「Ta 的状态」两个开关的持久化契约（v0.61.34 首建；v0.61.40 扩到「Ta 主动来找我」）。
 *
 * ## ⚠️ 为什么单开一个文件
 * 与 `PersonaPinCodecTest` / `PersonaEmojiChanceCodecTest` 同一个理由：
 * `CodecTest` 的人设往返**不设新字段**，而默认值恰好就是"没开启" ——
 * "缺失 == 缺失"照样通过、**看起来是绿的**。
 * 这里刻意用**非默认值**（true）走一遍往返：`PersonaCodec` 漏搬这一栏就红。
 */
class PersonaXinchaoCodecTest {

    @Test
    fun `开关能往返 —— 用非默认值测，漏搬就会红`() {
        val p = Persona(
            id = "p1",
            userNickname = "阿澈",
            userGender = "男",
            customPrompt = "角色名称：初雪",
            xinchaoEnabled = true,
        )
        val back = PersonaCodec.decode(PersonaCodec.encode(listOf(p)))
        assertEquals(1, back.size)
        assertTrue("开关没有原样读回来", back[0].xinchaoEnabled)
    }

    @Test
    fun `没开启的仍是 false —— 默认值不会被写成 true`() {
        val p = Persona(id = "p2", userNickname = "我", userGender = "女", customPrompt = "x")
        val back = PersonaCodec.decode(PersonaCodec.encode(listOf(p)))
        assertFalse(back[0].xinchaoEnabled)
    }

    @Test
    fun `老数据缺这一栏时读成 false —— 升级后零上报，行为与升级前一致`() {
        // 手工造一份没有 xinchaoEnabled 的旧 JSON —— 用户机器上躺着的就是这种数据。
        // 这条钉的是真实升级路径。
        val legacy = """
            [{"id":"old","userNickname":"你","userGender":"女",
              "customPrompt":"角色名称：旧人设","createdAt":1,"updatedAt":2}]
        """.trimIndent()
        val back = PersonaCodec.decode(legacy)
        assertEquals(1, back.size)
        assertFalse("缺字段应当读成「未接入」（零上报）", back[0].xinchaoEnabled)
    }

    @Test
    fun `不影响其它字段的往返（与置顶、表情概率共存）`() {
        val p = Persona(
            id = "p3",
            userNickname = "阿澈",
            userGender = "男",
            customPrompt = "角色名称：初雪",
            personality = "温柔",
            emojiChanceOverride = 0.25f,
            isPinned = true,
            xinchaoEnabled = true,
        )
        val back = PersonaCodec.decode(PersonaCodec.encode(listOf(p)))[0]
        assertTrue(back.xinchaoEnabled)
        assertTrue(back.isPinned)
        assertEquals(0.25f, back.emojiChanceOverride!!, 0.0001f)
    }

    /* ─────────── v0.61.40：「Ta 主动来找我」（proactiveEnabled） ─────────── */

    @Test
    fun `proactiveEnabled 能往返 —— 用非默认值测，漏搬就会红`() {
        val p = Persona(
            id = "p5",
            userNickname = "阿澈",
            userGender = "男",
            customPrompt = "角色名称：初雪",
            xinchaoEnabled = true,
            proactiveEnabled = true,
        )
        val back = PersonaCodec.decode(PersonaCodec.encode(listOf(p)))
        assertEquals(1, back.size)
        assertTrue("开关没有原样读回来", back[0].proactiveEnabled)
    }

    @Test
    fun `老数据缺 proactiveEnabled 时读成 false —— 升级后行为与升级前一致`() {
        // 手工造一份没有 proactiveEnabled 的旧 JSON（但已有 xinchaoEnabled ——
        // 模拟"上一个版本刚开了 Ta 的状态"的用户机器上躺着的这种数据）
        val legacy = """
            [{"id":"old","userNickname":"你","userGender":"女",
              "customPrompt":"角色名称：旧人设","xinchaoEnabled":true,"createdAt":1,"updatedAt":2}]
        """.trimIndent()
        val back = PersonaCodec.decode(legacy)
        assertEquals(1, back.size)
        assertTrue(back[0].xinchaoEnabled)
        assertFalse("缺字段应当读成「不取件」", back[0].proactiveEnabled)
    }
}
