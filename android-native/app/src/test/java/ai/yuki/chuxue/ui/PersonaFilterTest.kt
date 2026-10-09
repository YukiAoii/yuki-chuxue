package ai.yuki.chuxue.ui

import ai.yuki.chuxue.data.Persona
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 人设搜索的契约（纯函数 `filterPersonas`，定义在 `ui/persona/PersonaScreen.kt`）。
 *
 * ⚠️ 包名是 `ai.yuki.chuxue.ui` 而不是 `ui.persona` —— 因为 `PersonaScreen.kt`
 * 自己的包声明就是 `ai.yuki.chuxue.ui`（那一页早于 `ui/persona/` 这个目录）。
 * 测试与源同包，才拿得到 `internal` 的 `filterPersonas`。
 *
 * 钉三件事：
 *   1. 空查询**不筛人**（清空搜索框后列表必须回到全部）；
 *   2. 命中范围是**用户真的会记得的那几栏** —— 角色名、她怎么称呼你、性格；
 *   3. 两端空白与英文大小写不敏感。
 *
 * ⚠️ 这些断言证明的是"搜索范围对不对"，**证明不了列表好不好看**。
 */
class PersonaFilterTest {

    private fun p(
        id: String,
        roleName: String,
        // ⚠️ 默认值别用用例里要搜的词（这里原来是「小夏」）—— 它会让三条用例
        // 一起被搜中，于是「只搜到一个」的断言必然失败。这类默认值的坑很隐蔽：
        // 失败信息只说「期望 1 实际 3」，看不出是默认参数干的。
        userNickname: String = "阿寻",
        personality: String? = null,
    ) = Persona(
        id = id,
        userNickname = userNickname,
        userGender = "女",
        personality = personality,
        customPrompt = "角色名称：$roleName\n职业：咖啡师",
    )

    private val all = listOf(
        p("a", "小夏", userNickname = "老板"),
        p("b", "阿澈", personality = "温柔、有点闷骚"),
        p("c", "晚晴"),
    )

    @Test
    fun `空查询返回全部`() {
        assertEquals(all, filterPersonas(all, ""))
        assertEquals("全空白也算空查询", all, filterPersonas(all, "   "))
    }

    @Test
    fun `按角色名能搜到`() {
        val hit = filterPersonas(all, "小夏")
        assertEquals(1, hit.size)
        assertEquals("a", hit[0].id)
    }

    @Test
    fun `按她怎么称呼你也能搜到`() {
        // 用户找人未必记得角色名，但记得「那个管我叫老板的」
        assertEquals(listOf("a"), filterPersonas(all, "老板").map { it.id })
    }

    @Test
    fun `按性格也能搜到`() {
        assertEquals(listOf("b"), filterPersonas(all, "闷骚").map { it.id })
    }

    @Test
    fun `搜不到给空列表而不是全部`() {
        assertTrue(
            "搜不到时必须给空 —— 否则用户会以为搜索没生效",
            filterPersonas(all, "不存在的人").isEmpty(),
        )
    }

    @Test
    fun `查询词两端的空白被忽略`() {
        assertEquals(listOf("c"), filterPersonas(all, "  晚晴  ").map { it.id })
    }

    @Test
    fun `英文大小写不敏感`() {
        val list = listOf(p("d", "Yuki"))
        assertEquals(1, filterPersonas(list, "yuki").size)
    }
}
