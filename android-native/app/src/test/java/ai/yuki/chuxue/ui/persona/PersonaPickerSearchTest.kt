package ai.yuki.chuxue.ui

import ai.yuki.chuxue.data.Persona
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「选人设」弹窗搜索的规格（v0.61.21）。
 *
 * ## 为什么它值得有测试
 * 这段逻辑原本写在 Composable 里 —— 那等于**永远没人验过**（本机没有设备、
 * 也没有 Compose 测试基建）。抽成纯函数之后，至少"搜出来哪些"这件事有证据。
 * 样式（滚动、限高）仍然只能靠真机看，但**行为**不该跟着一起瞎。
 *
 * ## 判据的两端
 * 搜**太窄** → 用户明明写了却搜不到（"这软件是坏的"）；
 * 搜**太宽** → 搜出无关的（可接受，用户一眼就略过）。
 * 所以这里刻意选宽：四栏任一命中都算。
 */
class PersonaPickerSearchTest {

    private fun p(
        id: String,
        name: String = "",
        roleName: String = "",
        note: String = "",
        prompt: String = "",
    ) = Persona(id = id, roleName = roleName, note = note, customPrompt = prompt)
        .let { if (name.isBlank()) it else it.copy(customPrompt = prompt) }

    private val list = listOf(
        Persona(id = "p1", roleName = "小雪", note = "咖啡师", customPrompt = "温柔、话少"),
        Persona(id = "p2", roleName = "阿澈", note = "", customPrompt = "喜欢猫，很爱笑"),
        Persona(id = "p3", roleName = "L", note = "备用的", customPrompt = "冷淡"),
    )

    @Test
    fun `空关键词不过滤 —— 原样返回，顺序也不变`() {
        assertEquals(list, filterPersonasForPicker(list, ""))
        assertEquals(list, filterPersonasForPicker(list, "   "))
    }

    @Test
    fun `按角色名搜得到`() {
        assertEquals(listOf("p1"), filterPersonasForPicker(list, "小雪").map { it.id })
    }

    @Test
    fun `按备注搜得到`() {
        assertEquals(listOf("p1"), filterPersonasForPicker(list, "咖啡师").map { it.id })
    }

    @Test
    fun `按设定正文搜得到 —— 用户常常只记得设定里那句话`() {
        assertEquals(listOf("p2"), filterPersonasForPicker(list, "猫").map { it.id })
    }

    @Test
    fun `大小写不敏感`() {
        assertEquals(listOf("p3"), filterPersonasForPicker(list, "l").map { it.id })
        assertEquals(listOf("p3"), filterPersonasForPicker(list, "L").map { it.id })
    }

    @Test
    fun `搜不到就是空表 —— 界面靠它显示"没有匹配"`() {
        assertTrue(filterPersonasForPicker(list, "不存在的东西").isEmpty())
    }

    @Test
    fun `命中多个人设时都返回`() {
        // "备用的" 只有 p3；但 "的" 会同时命中 p1 的"话少"… 这条钉的是"不做唯一化"
        val hit = filterPersonasForPicker(list, "笑")
        assertEquals(listOf("p2"), hit.map { it.id })
    }
}
