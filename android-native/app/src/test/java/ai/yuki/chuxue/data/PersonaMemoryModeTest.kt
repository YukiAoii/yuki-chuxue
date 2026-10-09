package ai.yuki.chuxue.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 人设「记忆方式」（[Persona.memoryMode]）的持久化与判据契约（v0.61.48）。
 *
 * ## ⚠️ 为什么要单开一个文件（同 [PersonaXinchaoCodecTest] 的理由）
 * 用**非默认值**（cloud）走往返 —— 默认值恰好是"本地"，"缺失 == 缺失"照样绿。
 * 漏搬这一栏就红。
 *
 * ## ⚠️ 为什么它特别要紧
 * 记忆方式**选后不可改**（用户 2026-10-06 拍板）：导出再导入丢了这个字段，
 * 用户就**回不到**原来选的模式了（云端用户会退化成"三项云端功能全关"）。
 */
class PersonaMemoryModeTest {

    private fun persona(mode: String?) = Persona(
        id = "p1",
        userNickname = "阿澈",
        userGender = "男",
        customPrompt = "角色名称：初雪",
        memoryMode = mode,
    )

    @Test
    fun `云端模式能往返 —— 用非默认值测，漏搬就会红`() {
        val back = PersonaCodec.decode(PersonaCodec.encode(listOf(persona(Persona.MEMORY_MODE_CLOUD))))
        assertEquals(1, back.size)
        assertEquals(Persona.MEMORY_MODE_CLOUD, back[0].memoryMode)
    }

    @Test
    fun `本地模式能往返`() {
        val back = PersonaCodec.decode(PersonaCodec.encode(listOf(persona(Persona.MEMORY_MODE_LOCAL))))
        assertEquals(Persona.MEMORY_MODE_LOCAL, back[0].memoryMode)
    }

    @Test
    fun `老数据缺栏读成 null（= 按本地处理）`() {
        val old = """[{"id":"old","userNickname":"","userGender":"女","customPrompt":"y"}]"""
        assertNull(PersonaCodec.decode(old).first().memoryMode)
    }

    @Test
    fun `isCloudMemory 只在 cloud 时为真`() {
        assertTrue(persona(Persona.MEMORY_MODE_CLOUD).isCloudMemory)
        assertFalse(persona(Persona.MEMORY_MODE_LOCAL).isCloudMemory)
        assertFalse("老数据 null = 本地 = 三项云端功能关", persona(null).isCloudMemory)
    }
}
