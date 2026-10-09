package ai.yuki.chuxue.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 免费分组（「Yuki初雪Pro」）的**三态**契约（v0.58.0）。
 *
 * ## 用户报的问题
 * 「老用户之前注册过的，看不到也不能在连接设置里选择和编辑后端下发的免费 Pro 分组。」
 *
 * ## 真因（读代码即可判定）
 * `fetch()` 原来把两种完全不同的情况**折叠成同一个 null**：
 * · 服务端**明确说关掉**（`enabled:false`）—— 这时候确实该把本地那条摘掉；
 * · **压根没问到**（接口没部署 / 网络抖 / 响应坏）—— 这时候**绝不能**动本地数据。
 * 而 `withManaged(null)` 是"摘掉"，于是**一次网络抖动就能让一个已经好端端摆在那里的
 * 官方分组凭空消失**，用户看到的只有"怎么没有了"。
 *
 * ⚠️ 这类 bug 的特征是：**只在出错的那一侧出现**，正常路径上永远复现不了。
 *    所以必须把"关掉"与"没问到"在**类型上**分开 —— 靠注释提醒是不够的。
 */
class FreeGroupTristateTest {

    private fun json(body: String) = body

    @Test
    fun `服务端明确关掉 = Disabled（这时才该摘掉本地那条）`() {
        val r = FreeGroupApi.parseState(json("""{"enabled":false}"""))
        assertTrue("拿到 $r", r is FreeGroupApi.State.Disabled)
    }

    @Test
    fun `压根没问到 = Unreachable（这时不许动本地数据）`() {
        listOf(null, "", "不是 JSON", "[]", """{"enabled":true}""").forEach { raw ->
            val r = FreeGroupApi.parseState(raw)
            assertTrue(
                "「$raw」应当是 Unreachable（不是 Disabled）—— 否则会把本地那条冤枉地删掉",
                r is FreeGroupApi.State.Unreachable,
            )
        }
    }

    @Test
    fun `完整响应 = Ok`() {
        val r = FreeGroupApi.parseState(
            """{"enabled":true,"name":"Yuki初雪Pro","base_url":"https://a.b/v1","api_key":"sk-1"}""",
        )
        assertTrue("拿到 $r", r is FreeGroupApi.State.Ok)
        assertEquals("https://a.b/v1", (r as FreeGroupApi.State.Ok).group.baseUrl)
    }

    @Test
    fun `缺地址或缺密钥时算 Unreachable 而不是 Disabled`() {
        // ⚠️ 服务端配错了（漏填 key）是**服务端的问题**，不该导致客户端把用户已有的那条删掉。
        //    何况配错时本来就该"保持现状、等它配好"，而不是"悄悄撤掉功能"。
        val r1 = FreeGroupApi.parseState("""{"enabled":true,"base_url":"","api_key":"sk"}""")
        val r2 = FreeGroupApi.parseState("""{"enabled":true,"base_url":"https://a/v1","api_key":""}""")
        assertTrue("$r1", r1 is FreeGroupApi.State.Unreachable)
        assertTrue("$r2", r2 is FreeGroupApi.State.Unreachable)
    }

    @Test
    fun `Unreachable 时 withManaged 原样返回（不增不减）`() {
        val free = FreeGroup("Yuki初雪Pro", "https://your-llm-provider.example.com:18443/v1", "sk-free", "官方提供")
        val withIt = ProviderGroups.withManaged(emptyList(), FreeGroupApi.State.Ok(free))
        assertEquals(1, withIt.size)

        // ⚠️ 这一条就是用户报的 bug 的护栏：没问到 = **保持原样**。
        //    改回"null 就摘掉"的话，一次网络抖动就能让官方分组凭空消失。
        assertEquals(
            "没问到不该动本地数据",
            withIt,
            ProviderGroups.withManaged(withIt, FreeGroupApi.State.Unreachable),
        )

        // 对照：服务端**明确**说关掉时才摘
        assertEquals(
            "明确关掉才摘",
            0,
            ProviderGroups.withManaged(withIt, FreeGroupApi.State.Disabled).size,
        )
    }
}
