package ai.yuki.chuxue.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 两件事的规格：
 * ① 新建分组时的**供应商目录**（用户 2026-10-04：「新建分组增加第一项选择就是选择模型供应商」）；
 * ② 免费（托管）分组的**默认模型从后端来**，而不是客户端"拉列表取第一个"。
 *
 * ② 的取舍写在这里，因为它是个设计决定，不是实现细节：
 * 免费分组的**地址和密钥本来就由后端维护**（`managed`），模型属于同一类东西 ——
 * 用哪家免费额度、哪个模型更划算，只有出钱的人（后台）说了算。
 * 让客户端去猜"列表第一个"，既不稳定（顺序由服务商定），又凭空多一条会失败的请求。
 */
class ProviderVendorsTest {

    /* ─────────── ① 供应商目录 ─────────── */

    @Test
    fun `每个供应商都有名字，且 id 唯一`() {
        val ids = ProviderVendors.ALL.map { it.id }
        assertEquals("id 不许重复（重复就没法回填）", ids.size, ids.toSet().size)
        assertTrue("每个都要有给用户看的名字", ProviderVendors.ALL.all { it.label.isNotBlank() })
    }

    @Test
    fun `官方那一项给出官方地址`() {
        assertEquals("https://api.deepseek.com", ProviderVendors.byId("deepseek")?.baseUrl)
    }

    @Test
    fun `自定义那一项的地址是空的 —— 留给用户自己填`() {
        assertEquals("", ProviderVendors.byId("custom")?.baseUrl)
    }

    @Test
    fun `认不出来的 id 返回 null，而不是随便给一个`() {
        assertNull(ProviderVendors.byId("没有这个供应商"))
    }

    /* ─────────── ② 免费分组的模型来自后端 ─────────── */

    private fun freeState(models: String): FreeGroupApi.State = FreeGroupApi.parseState(
        """{"enabled":true,"name":"Yuki初雪Pro","base_url":"https://your-llm-provider.example.com:18443/v1",""" +
            """"api_key":"sk-abc","notice":"免费"$models}""",
    )

    @Test
    fun `后端带了模型列表就解析出来`() {
        val state = freeState(""","models":["deepseek-chat","deepseek-reasoner"]""")
        val ok = state as FreeGroupApi.State.Ok
        assertEquals(
            listOf(FreeModel("deepseek-chat"), FreeModel("deepseek-reasoner")),
            ok.group.models,
        )
    }

    /* ─────────── ③ id 与显示名分开（用户 2026-10-04 问出来的那个坑）─────────── */

    @Test
    fun `规范形 id 加 label：两者各归各位`() {
        val state = freeState(""","models":[{"id":"deepseek-flash","label":"闪电"}]""")
        val ok = state as FreeGroupApi.State.Ok
        assertEquals(FreeModel(id = "deepseek-flash", label = "闪电"), ok.group.models.single())
    }

    @Test
    fun `后台输入框那种 等号 写法也认`() {
        val state = freeState(""","models":["deepseek-flash=闪电"]""")
        val ok = state as FreeGroupApi.State.Ok
        assertEquals(FreeModel(id = "deepseek-flash", label = "闪电"), ok.group.models.single())
    }

    @Test
    fun `只写 id 时显示名是空的 —— 界面回落成显示 id`() {
        val state = freeState(""","models":["deepseek-flash"]""")
        val ok = state as FreeGroupApi.State.Ok
        assertEquals(FreeModel(id = "deepseek-flash", label = ""), ok.group.models.single())
        assertEquals("deepseek-flash", ok.group.models.single().shown)
    }

    @Test
    fun `有显示名时，界面看到的是显示名`() {
        assertEquals("闪电", FreeModel("deepseek-flash", "闪电").shown)
    }

    /**
     * ⚠️ **这一条是整个改动的命门**：
     * 用户在界面上看到「闪电」，但发出去的**必须**是 `deepseek-flash`。
     * 否则"改个好看的名字"就等于"请求全废"——那正是改造前的行为。
     */
    @Test
    fun `配了显示名，但真正用来发请求的还是真名`() {
        val state = freeState(""","models":[{"id":"deepseek-flash","label":"闪电"}]""")
        val merged = ProviderGroups.withManaged(emptyList(), state)
        val managed = merged.first { it.id == ProviderGroups.MANAGED_ID }
        // 存的是真名
        assertEquals(listOf("deepseek-flash"), managed.checkedModels)
        // 显示的是显示名
        assertEquals("闪电", ProviderGroups.labelOf(managed, "deepseek-flash"))
        // 请求会用到的那个值 —— 必须是真名
        assertEquals("deepseek-flash", ProviderGroups.resolveModel(managed, null))
    }

    @Test
    fun `没配显示名时，labelOf 回落成真名`() {
        assertEquals("deepseek-flash", ProviderGroups.labelOf(managedGroup(checked = listOf("deepseek-flash")), "deepseek-flash"))
    }

    @Test
    fun `模型显示名能存能读 —— 与老版本的分组文件互相兼容`() {
        val g = managedGroup(checked = listOf("deepseek-flash")).copy(
            modelLabels = mapOf("deepseek-flash" to "闪电"),
        )
        val roundTrip = ProviderGroups.decode(ProviderGroups.encode(listOf(g))).single()
        assertEquals(mapOf("deepseek-flash" to "闪电"), roundTrip.modelLabels)
        // 老分组（没有这个字段）读出来是空表，行为与本字段存在之前一致
        assertEquals(emptyMap<String, String>(), ProviderGroups.decode(ProviderGroups.encode(listOf(
            managedGroup(checked = listOf("m")),
        ))).single().modelLabels)
    }

    @Test
    fun `后端没带模型时是空表，而不是整个分组作废`() {
        val state = freeState("")
        val ok = state as FreeGroupApi.State.Ok
        assertTrue("没有模型不该让分组消失（用户还能自己勾）", ok.group.models.isEmpty())
    }

    @Test
    fun `首次拿到托管分组时，把后端给的模型填成勾选`() {
        val state = freeState(""","models":["deepseek-chat"]""")
        val merged = ProviderGroups.withManaged(emptyList(), state)
        val managed = merged.first { it.id == ProviderGroups.MANAGED_ID }
        assertEquals(listOf("deepseek-chat"), managed.checkedModels)
    }

    @Test
    fun `他勾的模型还在新清单里 → 保留他的选择`() {
        val mine = managedGroup(checked = listOf("deepseek-chat"))
        val state = freeState(""","models":["deepseek-chat","deepseek-reasoner"]""")
        val merged = ProviderGroups.withManaged(listOf(mine), state)
        assertEquals(listOf("deepseek-chat"), merged.first { it.id == ProviderGroups.MANAGED_ID }.checkedModels)
    }

    @Test
    fun `他勾的模型已经不在新清单里（后端换了源）→ 换成新清单`() {
        // 这条就是用户要的"我这边调整，他们那边不会有感觉"：
        // 后端换了服务地址、模型清单跟着变，客户端要是还揣着旧模型名，发出去必报错。
        val mine = managedGroup(checked = listOf("早就没有了的模型"))
        val state = freeState(""","models":["deepseek-chat"]""")
        val merged = ProviderGroups.withManaged(listOf(mine), state)
        assertEquals(
            listOf("deepseek-chat"),
            merged.first { it.id == ProviderGroups.MANAGED_ID }.checkedModels,
        )
    }

    @Test
    fun `后端没给清单时不清空他勾的 —— 不能因为后端没配就把他的选择抹了`() {
        val mine = managedGroup(checked = listOf("我自己选的"))
        val state = freeState("")
        val merged = ProviderGroups.withManaged(listOf(mine), state)
        assertEquals(
            listOf("我自己选的"),
            merged.first { it.id == ProviderGroups.MANAGED_ID }.checkedModels,
        )
    }

    private fun managedGroup(checked: List<String>) = ProviderGroup(
        id = ProviderGroups.MANAGED_ID,
        name = "Yuki初雪Pro",
        baseUrl = "https://your-llm-provider.example.com:18443/v1",
        apiKey = "sk-abc",
        managed = true,
        checkedModels = checked,
    )

    @Test
    fun `老用户的自建分组一条不动`() {
        val old = ProviderGroup(
            id = "mine",
            name = "我的中转",
            baseUrl = "https://example.com/v1",
            apiKey = "sk-old",
            checkedModels = listOf("m1"),
        )
        val state = freeState(""","models":["deepseek-chat"]""")
        val merged = ProviderGroups.withManaged(listOf(old), state)
        assertEquals(old, merged.first { it.id == "mine" })
        assertEquals("托管分组排在最后", 2, merged.size)
    }
}
