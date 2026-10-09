package ai.yuki.chuxue.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 人设「记忆上云」开关的持久化契约（v0.61.37，用户 2026-10-06 拍板做成独立开关）。
 *
 * 与 `PersonaXinchaoCodecTest` 同一个理由单开文件：`CodecTest` 的人设往返**不设新字段**，
 * 而新字段默认值恰好是"没开启"——"缺失==缺失"照样过、看起来是绿的。
 * 这里刻意用**非默认值 true** 走一遍，`PersonaCodec` 漏搬就红。
 */
class PersonaCloudMemoryCodecTest {

    @Test
    fun `开关能往返 —— 用非默认值测，漏搬就红`() {
        val p = Persona(
            id = "p1",
            userGender = "男",
            customPrompt = "角色名称：初雪",
            cloudMemoryEnabled = true,
        )
        val back = PersonaCodec.decode(PersonaCodec.encode(listOf(p)))
        assertEquals(1, back.size)
        assertTrue("记忆上云开关没原样读回来", back[0].cloudMemoryEnabled)
    }

    @Test
    fun `默认是 false —— 老用户升级零变化`() {
        val p = Persona(id = "p2", userGender = "女", customPrompt = "x")
        assertFalse(PersonaCodec.decode(PersonaCodec.encode(listOf(p)))[0].cloudMemoryEnabled)
    }

    @Test
    fun `老数据缺这一栏读成 false`() {
        val legacy = """[{"id":"old","userGender":"女","customPrompt":"角色名称：旧人设"}]"""
        assertFalse(PersonaCodec.decode(legacy)[0].cloudMemoryEnabled)
    }

    @Test
    fun `与「接入 Ta 的状态」是两栏、互不覆盖`() {
        val p = Persona(
            id = "p3",
            userGender = "男",
            customPrompt = "角色名称：初雪",
            xinchaoEnabled = true,
            cloudMemoryEnabled = false, // 接入了状态、但没开记忆上云 —— 两者独立
        )
        val back = PersonaCodec.decode(PersonaCodec.encode(listOf(p)))[0]
        assertTrue(back.xinchaoEnabled)
        assertFalse(back.cloudMemoryEnabled)
    }
}
