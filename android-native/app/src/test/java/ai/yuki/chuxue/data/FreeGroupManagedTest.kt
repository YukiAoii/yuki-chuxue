package ai.yuki.chuxue.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 服务端下发的**免费分组**（「Yuki初雪Pro」，v0.58.0）的契约。
 *
 * ## 这组用例挡的是什么
 * 用户对这条分组的要求是"**不可删除、地址和密钥不可见**"。这些约束全部落在
 * 数据层的三个纯函数上：
 * · [FreeGroupApi.parse] —— 什么算"一条可用的免费分组"；
 * · [ProviderGroups.withManaged] —— 怎么并/怎么摘，且**不能并出两条来**；
 * · [ProviderGroups.resolveActive] —— 什么时候才该默认用它。
 *
 * ⚠️ 还有一条最容易被忽略的：**`managed` 必须能被存下来**。
 *    只放在内存里的话，用户重启一次 App，"不可删除/不可见"就全没了 ——
 *    而那种 bug 在开发机上永远看不到（开发时很少重启）。
 */
class FreeGroupManagedTest {
/** 测试里少打几个字：明确可用的那一种状态。 */
private fun ok(g: FreeGroup) = FreeGroupApi.State.Ok(g)


    
/**
 * 只取「明确可用」那一种状态里的分组。
 *
 * ⚠️ 解析本身现在返回**三态**（见 `FreeGroupApi.State`）——
 *    「服务端关掉」与「没问到」必须分开，三态各自的用例在 `FreeGroupTristateTest`。
 *    这里只关心解析出来对不对，所以把非 Ok 一律折成 null。
 */
private fun parseOk(raw: String?): FreeGroup? =
    (FreeGroupApi.parseState(raw) as? FreeGroupApi.State.Ok)?.group

private fun json(
        enabled: String = "true",
        baseUrl: String = "https://api.example.com:18443/v1",
        apiKey: String = "sk-test",
        name: String = "Yuki初雪Pro",
    ) = """{"enabled":$enabled,"name":"$name","base_url":"$baseUrl","api_key":"$apiKey","notice":"官方提供"}"""

    /* ─────────── ① 解析 ─────────── */

    @Test
    fun `完整响应解析成免费分组`() {
        val g = parseOk(json())!!
        assertEquals("Yuki初雪Pro", g.name)
        assertEquals("https://api.example.com:18443/v1", g.baseUrl)
        assertEquals("sk-test", g.apiKey)
        assertEquals("官方提供", g.notice)
    }

    @Test
    fun `服务端关掉时返回 null`() {
        assertNull(parseOk(json(enabled = "false")))
        assertNull(parseOk(json(enabled = "0")))
    }

    @Test
    fun `缺地址或缺密钥都不算可用`() {
        // ⚠️ 这两种情况照样显示出来，用户拿到的是一张点了发不出请求的卡片，
        //    而他只会以为是自己手机的问题 —— 所以必须当作"没有"。
        assertNull(parseOk(json(baseUrl = "")))
        assertNull(parseOk(json(apiKey = "")))
    }

    @Test
    fun `坏数据一律 null，不抛`() {
        assertNull(parseOk(null))
        assertNull(parseOk(""))
        assertNull(parseOk("不是 JSON"))
        assertNull(parseOk("[]"))
    }

    @Test
    fun `camelCase 字段也认`() {
        val g = parseOk("""{"enabled":true,"name":"Pro","baseUrl":"https://a.b/v1","apiKey":"sk-1"}""")!!
        assertEquals("https://a.b/v1", g.baseUrl)
        assertEquals("sk-1", g.apiKey)
    }

    /* ─────────── ② 并入本地列表 ─────────── */

    private fun mine(id: String = "abc") =
        ProviderGroup(id = id, name = "我的", baseUrl = "https://mine/v1", apiKey = "sk-mine")

    private val free = FreeGroup("Yuki初雪Pro", "https://your-llm-provider.example.com:18443/v1", "sk-free", "官方提供")

    @Test
    fun `没有自建分组时，列表里只有它一个`() {
        val out = ProviderGroups.withManaged(emptyList(), ok(free))
        assertEquals(1, out.size)
        assertTrue(out[0].managed)
        assertEquals("Yuki初雪Pro", out[0].name)
        assertEquals(ProviderGroups.MANAGED_ID, out[0].id)
    }

    @Test
    fun `它排在自建分组的后面`() {
        val out = ProviderGroups.withManaged(listOf(mine()), ok(free))
        assertEquals(2, out.size)
        assertEquals("我的", out[0].name)
        assertTrue(out[1].managed)
    }

    @Test
    fun `反复并入不会堆出好几条`() {
        // ⚠️ 这是最容易写错的一条：用 newId() 而不是固定 id 的话，
        //    用户每次启动都会多一条同名分组。
        var list = ProviderGroups.withManaged(emptyList(), ok(free))
        repeat(5) { list = ProviderGroups.withManaged(list, ok(free)) }
        assertEquals(1, list.count { it.managed })
        assertEquals(1, list.size)
    }

    @Test
    fun `服务端换了密钥，但用户勾的模型要留着`() {
        val first = ProviderGroups.withManaged(
            emptyList(),
            ok(free),
        ).map { it.copy(checkedModels = listOf("deepseek-chat"), memoryContextWindow = 64000) }
        val second = ProviderGroups.withManaged(first, ok(free.copy(apiKey = "sk-new")))
        assertEquals("sk-new", second[0].apiKey)
        assertEquals(listOf("deepseek-chat"), second[0].checkedModels)
        assertEquals(64000, second[0].memoryContextWindow)
    }

    @Test
    fun `服务端关掉功能时，本地那条要摘掉`() {
        val withIt = ProviderGroups.withManaged(listOf(mine()), ok(free))
        val without = ProviderGroups.withManaged(withIt, FreeGroupApi.State.Disabled)
        assertEquals(1, without.size)
        assertEquals("我的", without[0].name)
        assertTrue(without.none { it.managed })
    }

    /* ─────────── ③ 当前分组怎么挑 ─────────── */

    @Test
    fun `记住的 id 优先`() {
        val mineGroup = mine()
        val managed = ProviderGroups.withManaged(emptyList(), ok(free))[0]
        val picked = ProviderGroups.resolveActive(listOf(managed, mineGroup), mineGroup.id)
        assertEquals(mineGroup.id, picked?.id)
    }

    @Test
    fun `没记住时优先用自己配的，而不是官方那条`() {
        // ⚠️ 用户原话是「没有自己添加过的就默认直接使用这个为默认的调用」——
        //    反过来说：**只要他自己加过，就仍然用自己的**。
        //    否则升上来的老用户会被悄悄切到官方那条，账单和效果都变了却不知道为什么。
        val managed = ProviderGroups.withManaged(emptyList(), ok(free))[0]
        val picked = ProviderGroups.resolveActive(listOf(managed, mine()), null)
        assertEquals("abc", picked?.id)
    }

    @Test
    fun `一个自建分组都没有时才落到官方那条`() {
        val managed = ProviderGroups.withManaged(emptyList(), ok(free))[0]
        assertEquals(ProviderGroups.MANAGED_ID, ProviderGroups.resolveActive(listOf(managed), null)?.id)
    }

    /* ─────────── ④ 存下来（重启后锁定不能丢） ─────────── */

    @Test
    fun `托管分组生效时请求地址就是它下发的源站（客户端直连，不经自家后端）`() {
        // 用户 2026-10-01 明确要求：「用户请求的时候还是直接连接 api 源地址，
        // 不用我服务端做反向代理」。这条钉的就是这句话 ——
        // 托管分组的 baseUrl 必须**原样**进到 AppSettings，中间不许出现任何转发地址。
        val group = ProviderGroups.withManaged(emptyList(), ok(free))[0]
        val global = AppSettings(apiKey = "sk-global", baseUrl = "https://api.deepseek.com", model = "m")

        val eff = ProviderGroups.effective(
            settings = global,
            groups = listOf(group),
            activeId = ProviderGroups.MANAGED_ID,
            sessionGroupId = null,
            sessionModel = null,
        )
        assertEquals("必须直连服务端下发的源站", "https://your-llm-provider.example.com:18443/v1", eff.baseUrl)
        assertEquals("sk-free", eff.apiKey)
        assertEquals("全局那套地址必须被盖掉", false, eff.baseUrl == global.baseUrl)
    }

    @Test
    fun `managed 与 notice 能存能读`() {
        val managed = ProviderGroups.withManaged(emptyList(), ok(free))[0]
        val back = ProviderGroups.decode(ProviderGroups.encode(listOf(managed)))[0]
        assertTrue("重启后必须仍然是托管的，否则地址与密钥就露出来了", back.managed)
        assertEquals("官方提供", back.notice)
        assertEquals(managed.baseUrl, back.baseUrl)
    }

    @Test
    fun `老分组读出来不是托管的`() {
        // 缺 `managed` 字段的旧数据必须默认 false，否则老用户的分组会突然变得不能删
        val raw = """[{"id":"x","name":"官方","baseUrl":"https://a/v1","apiKey":"k"}]"""
        val g = ProviderGroups.decode(raw)[0]
        assertFalse(g.managed)
        assertEquals("", g.notice)
    }
}
