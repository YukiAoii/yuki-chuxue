package ai.yuki.chuxue.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「用户人设」的持久化契约（v0.61.41）。
 *
 * 与 `PersonaPinCodecTest` / `PersonaXinchaoCodecTest` 同一条纪律：
 * 用**非默认值**往返 —— 默认值会让"漏搬字段"照样绿。
 */
class UserPersonaCodecTest {

    @Test
    fun `往返 —— 每个字段都用非默认值`() {
        val u = UserPersona(
            id = "up1",
            name = "快刀",
            roleText = "江湖上人称「快刀」的刀客，说话利落、讲义气。",
            note = "给初雪和宵宫用",
            createdAt = 1_700_000_000_000L,
            updatedAt = 1_700_000_001_000L,
        )
        val back = UserPersonaCodec.decode(UserPersonaCodec.encode(listOf(u)))
        assertEquals(1, back.size)
        assertEquals(u, back[0])
    }

    @Test
    fun `缺字段的旧数据读成默认值，不抛`() {
        val legacy = """[{"id":"u1"}]"""
        val back = UserPersonaCodec.decode(legacy)
        assertEquals(1, back.size)
        assertEquals("u1", back[0].id)
        assertEquals("", back[0].name)
        assertEquals("", back[0].roleText)
        assertEquals(0L, back[0].createdAt)
    }

    @Test
    fun `空串解码为空表`() {
        assertEquals(0, UserPersonaCodec.decode("").size)
    }

    @Test
    fun `坏输入抛异常（本地存储的既有纪律：失败要能被上层看见、原始数据被备份）`() {
        var threw = false
        try {
            UserPersonaCodec.decode("不是 JSON")
        } catch (e: Throwable) {
            threw = true
        }
        assertTrue("坏数据必须抛，让 Store 走备份分支", threw)
    }
}
