package ai.yuki.chuxue.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「角色名称」与显示名的口径（v0.61.10）。
 *
 * 用户要求：「解决**角色设定第一行必须是角色名称**的问题 …… 名称单独填写编辑项，
 * 它不代表角色人设的名称，而是类似备注的东西」+「**记得考虑老用户进行适配**」。
 *
 * 所以这里钉的是那条**回退链**（顺序本身就是兼容策略）：
 * 新字段 [Persona.roleName] → 老写法「角色名称：X」→ 老口径（设定首行）→ 「Ta」。
 */
class PersonaDisplayNameTest {

    @Test
    fun `新字段优先 —— 填了角色名称就用它`() {
        val p = Persona(roleName = "初雪", customPrompt = "角色名称：雪乃\n她是……")
        assertEquals("初雪", p.displayName)
    }

    @Test
    fun `老数据适配：没有新字段时抓「角色名称：X」`() {
        val p = Persona(customPrompt = "角色名称：雪乃\n她是……")
        assertEquals("雪乃", p.displayName)
    }

    @Test
    fun `老数据适配：连「角色名称：」都没写时退回首行`() {
        // 这是老 displayNameOf 的口径 —— 宁可显示一行设定，也不要"未命名角色"
        val p = Persona(customPrompt = "雪乃，19 岁\n喜欢冬天")
        assertEquals("雪乃，19 岁", p.displayName)
    }

    @Test
    fun `什么都没有时给「Ta」`() {
        assertEquals("Ta", Persona().displayName)
        assertEquals("Ta", Persona(customPrompt = "   ").displayName)
    }

    @Test
    fun `新字段只有空白时不算填过 —— 继续走老路径`() {
        val p = Persona(roleName = "   ", customPrompt = "角色名称：雪乃")
        assertEquals("雪乃", p.displayName)
    }
}
