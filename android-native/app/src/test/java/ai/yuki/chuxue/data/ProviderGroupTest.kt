package ai.yuki.chuxue.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 连接分组的数据契约（v0.51.0）。
 *
 * 重点钉三件事：
 * 1. **编解码往返不丢字段** —— 丢了的话用户配好的密钥会静默消失；
 * 2. **坏数据降级为可用状态**（空表 / 跳过坏条目），**绝不抛异常** ——
 *    它跑在设置页的启动路径上，一份坏 JSON 不该让整页打不开；
 * 3. **旧扁平配置能迁移成一个默认分组**，且空配置**不会**凭空造出空分组。
 */
class ProviderGroupTest {

    private fun group(
        id: String = "g1",
        name: String = "DeepSeek 官方",
        baseUrl: String = "https://api.deepseek.com/v1",
        apiKey: String = "sk-abc",
        type: String = ProviderType.OPENAI_COMPAT,
        models: List<String> = listOf("deepseek-chat", "deepseek-reasoner"),
        createdAt: Long = 1_700_000_000_000L,
    // ⚠️ 一律用**具名实参**：`ProviderGroup` 中间插一个字段时，位置实参会静默错位
    //    （v0.61.20 加 `modelLabels` 时就真的错位了，编译器拦下才没酿成事故）
    ) = ProviderGroup(
        id = id,
        name = name,
        baseUrl = baseUrl,
        apiKey = apiKey,
        providerType = type,
        checkedModels = models,
        createdAt = createdAt,
    )

    /* ─────────── 编解码往返 ─────────── */

    @Test
    fun `单个分组往返不丢任何字段`() {
        val src = group()
        val out = ProviderGroups.decode(ProviderGroups.encode(listOf(src)))
        assertEquals(1, out.size)
        assertEquals(src, out[0])
    }

    @Test
    fun `多个分组往返保持顺序`() {
        val a = group(id = "a", name = "甲")
        val b = group(id = "b", name = "乙")
        val out = ProviderGroups.decode(ProviderGroups.encode(listOf(a, b)))
        assertEquals(listOf("a", "b"), out.map { it.id })
    }

    @Test
    fun `勾选的模型列表原样保留`() {
        val src = group(models = listOf("m1", "m2", "m3"))
        val out = ProviderGroups.decode(ProviderGroups.encode(listOf(src)))
        assertEquals(listOf("m1", "m2", "m3"), out[0].checkedModels)
    }

    @Test
    fun `空分组列表往返成空`() {
        assertTrue(ProviderGroups.decode(ProviderGroups.encode(emptyList())).isEmpty())
    }

    @Test
    fun `没有勾任何模型的分组也能往返`() {
        val src = group(models = emptyList())
        assertEquals(emptyList<String>(), ProviderGroups.decode(ProviderGroups.encode(listOf(src)))[0].checkedModels)
    }

    /* ─────────── 坏数据：降级，不抛 ─────────── */

    @Test
    fun `null 与空串解码为空列表`() {
        assertTrue(ProviderGroups.decode(null).isEmpty())
        assertTrue(ProviderGroups.decode("").isEmpty())
        assertTrue(ProviderGroups.decode("   ").isEmpty())
    }

    @Test
    fun `完全不是 JSON 时返回空列表而不是抛异常`() {
        assertTrue(ProviderGroups.decode("这不是 json {{{").isEmpty())
    }

    @Test
    fun `形状不对（不是数组）时返回空列表`() {
        assertTrue(ProviderGroups.decode("""{"a":1}""").isEmpty())
    }

    @Test
    fun `缺 baseUrl 的条目被跳过 —— 它在界面上点了就报错`() {
        val raw = """
            [{"id":"x","name":"坏的","apiKey":"sk-1","checkedModels":[]}]
        """.trimIndent()
        assertTrue(ProviderGroups.decode(raw).isEmpty())
    }

    @Test
    fun `一条坏的不会连累其它好的`() {
        val raw = """
            [
              {"id":"good","name":"好的","baseUrl":"https://a.com/v1","apiKey":"sk-1","checkedModels":["m"]},
              {"id":"bad","name":"坏的","apiKey":"sk-2"}
            ]
        """.trimIndent()
        val out = ProviderGroups.decode(raw)
        assertEquals(1, out.size)
        assertEquals("good", out[0].id)
        assertEquals(listOf("m"), out[0].checkedModels)
    }

    @Test
    fun `缺 id 或 name 时用兜底值补齐，而不是丢掉这条`() {
        val raw = """[{"baseUrl":"https://a.com/v1","apiKey":"sk-1"}]"""
        val out = ProviderGroups.decode(raw)
        assertEquals(1, out.size)
        assertTrue("id 应被补上", out[0].id.isNotBlank())
        assertEquals("未命名", out[0].name)
    }

    @Test
    fun `缺 providerType 时默认 openai 兼容`() {
        val raw = """[{"baseUrl":"https://a.com/v1","apiKey":"sk-1"}]"""
        assertEquals(ProviderType.OPENAI_COMPAT, ProviderGroups.decode(raw)[0].providerType)
    }

    /* ─────────── 旧配置迁移 ─────────── */

    @Test
    fun `有旧配置时迁移成一个默认分组，并把旧 model 勾上`() {
        val legacy = AppSettings(
            apiKey = "sk-old",
            baseUrl = "https://api.deepseek.com/v1",
            model = "deepseek-chat",
        )
        val out = ProviderGroups.migrateLegacy(legacy, now = 123L)
        assertEquals(1, out.size)
        assertEquals(ProviderGroups.DEFAULT_GROUP_NAME, out[0].name)
        assertEquals("sk-old", out[0].apiKey)
        assertEquals("https://api.deepseek.com/v1", out[0].baseUrl)
        assertEquals(listOf("deepseek-chat"), out[0].checkedModels)
        assertEquals(123L, out[0].createdAt)
    }

    @Test
    fun `完全没有旧配置时不迁移 —— 不能凭空造一个空分组`() {
        // 新装用户打开设置该看到"还没有分组"，而不是一张点了就报错的卡片
        val empty = AppSettings(apiKey = "", baseUrl = "", model = "")
        assertTrue(ProviderGroups.migrateLegacy(empty, now = 1L).isEmpty())
    }

    @Test
    fun `旧 model 为空时迁移出分组但不勾任何模型`() {
        val legacy = AppSettings(apiKey = "sk-1", baseUrl = "https://a.com/v1", model = "")
        val out = ProviderGroups.migrateLegacy(legacy, now = 1L)
        assertEquals(1, out.size)
        assertTrue(out[0].checkedModels.isEmpty())
    }

    /* ─────────── 当前分组解析 ─────────── */

    @Test
    fun `按 id 找到当前分组`() {
        val groups = listOf(group(id = "a"), group(id = "b"))
        assertEquals("b", ProviderGroups.resolveActive(groups, "b")?.id)
    }

    @Test
    fun `记住的 id 指向已删除的分组时退回第一个 —— 不能报错`() {
        val groups = listOf(group(id = "a"), group(id = "b"))
        assertEquals("a", ProviderGroups.resolveActive(groups, "已经删了")?.id)
    }

    @Test
    fun `没有分组时返回 null`() {
        assertNull(ProviderGroups.resolveActive(emptyList(), "a"))
        assertNull(ProviderGroups.resolveActive(emptyList(), null))
    }

    /* ─────────── 与既有 AppSettings 的桥 ─────────── */

    @Test
    fun `toSettings 带上 baseUrl 与 key，好复用既有的拉取与测试`() {
        val s = group(baseUrl = "https://x.com/v1", apiKey = "sk-x").toSettings()
        assertEquals("https://x.com/v1", s.baseUrl)
        assertEquals("sk-x", s.apiKey)
    }

    @Test
    fun `toSettings 默认用第一个勾选的模型`() {
        val s = group(models = listOf("m1", "m2")).toSettings()
        assertEquals("m1", s.model)
    }

    @Test
    fun `toSettings 可显式指定模型`() {
        val s = group(models = listOf("m1", "m2")).toSettings(model = "m2")
        assertEquals("m2", s.model)
    }

    /* ─────────── 类型表 ─────────── */

    @Test
    fun `Phase 1 只有 openai 兼容是可实现的`() {
        assertTrue(ProviderType.isImplemented(ProviderType.OPENAI_COMPAT))
        assertTrue(!ProviderType.isImplemented(ProviderType.ANTHROPIC))
        assertTrue(!ProviderType.isImplemented(ProviderType.GEMINI))
    }

    @Test
    fun `未支持的类型在界面上要写明暂不支持`() {
        assertTrue(ProviderType.label(ProviderType.ANTHROPIC).contains("暂不支持"))
    }

    /* ─────────── id ─────────── */

    @Test
    fun `newId 每次不同`() {
        assertNotEquals(ProviderGroups.newId(), ProviderGroups.newId())
    }

    /* ═══════════ 「实际用哪套值」—— 发消息/压缩/记忆/余额四处共用 ═══════════ */

    @Test
    fun `一个分组都没有时逐字段等于全局 —— 老用户行为不变`() {
        val global = AppSettings(
            apiKey = "sk-global",
            baseUrl = "https://api.deepseek.com",
            model = "deepseek-flash",
            globalPrefix = "用户自己写的开场白",
            thinkingEnabled = false,
        )
        val out = ProviderGroups.applyProvider(global, group = null, model = "")
        assertEquals(global, out)
    }

    @Test
    fun `分组存在时三件套一起换掉`() {
        val global = AppSettings(
            apiKey = "sk-global",
            baseUrl = "https://api.deepseek.com",
            model = "deepseek-flash",
        )
        val relay = group(baseUrl = "https://relay.example.com/v1", apiKey = "sk-relay")
        val out = ProviderGroups.applyProvider(global, relay, "gpt-4o")
        assertEquals("sk-relay", out.apiKey)
        assertEquals("https://relay.example.com/v1", out.baseUrl)
        assertEquals("gpt-4o", out.model)
    }

    @Test
    fun `叠分组不碰"与分组无关"的字段 —— 否则冻结前缀会被重新拼一遍`() {
        // ⚠️ 这条守的是最贵的红线：`buildFrozenPrefix` 读 globalPrefix，
        //    它一变，整个会话的缓存全废（真机实测过 95% → 10% 以下）。
        val global = AppSettings(
            globalPrefixEnabled = true,
            globalPrefix = "用户自己写的开场白",
            thinkingEnabled = false,
        )
        val out = ProviderGroups.applyProvider(global, group(), "m")
        assertEquals("用户自己写的开场白", out.globalPrefix)
        assertTrue(out.globalPrefixEnabled)
        assertTrue(!out.thinkingEnabled)
    }

    @Test
    fun `分组里密钥为空就退回全局密钥`() {
        val global = AppSettings(apiKey = "sk-global")
        assertEquals("sk-global", ProviderGroups.applyProvider(global, group(apiKey = ""), "m").apiKey)
    }

    @Test
    fun `模型为空（或只有空白）就退回全局模型`() {
        val global = AppSettings(model = "deepseek-flash")
        assertEquals("deepseek-flash", ProviderGroups.applyProvider(global, null, "").model)
        assertEquals("deepseek-flash", ProviderGroups.applyProvider(global, null, "   ").model)
    }

    @Test
    fun `会话选过的分组优先于当前分组`() {
        val a = group(id = "a")
        val b = group(id = "b")
        assertEquals("b", ProviderGroups.resolveFor(listOf(a, b), "a", "b")?.id)
    }

    @Test
    fun `会话选的分组已被删掉时退回当前分组而不是 null`() {
        // 返回 null 会让那段对话在"一个分组都没有"和"分组还在"之间摇摆，
        // 表现是"有时发得出去、有时报 401"
        val a = group(id = "a")
        assertEquals("a", ProviderGroups.resolveFor(listOf(a), "a", "早就删了")?.id)
    }

    @Test
    fun `一个分组都没有时 resolveFor 返回 null`() {
        assertNull(ProviderGroups.resolveFor(emptyList(), "", null))
    }

    @Test
    fun `会话选过的模型优先于分组里勾选的第一个`() {
        assertEquals("m2", ProviderGroups.resolveModel(group(models = listOf("m1", "m2")), "m2"))
    }

    @Test
    fun `会话没选模型（null 或空白）时用分组里勾选的第一个`() {
        assertEquals("m1", ProviderGroups.resolveModel(group(models = listOf("m1", "m2")), null))
        assertEquals("m1", ProviderGroups.resolveModel(group(models = listOf("m1", "m2")), "  "))
    }

    @Test
    fun `没有分组也没有会话选择时模型是空串而不是崩溃`() {
        assertEquals("", ProviderGroups.resolveModel(null, null))
    }

    @Test
    fun `effective 一次算清四处要用的取值`() {
        val global = AppSettings(
            apiKey = "sk-global",
            baseUrl = "https://api.deepseek.com",
            model = "deepseek-flash",
        )
        val relay = group(
            id = "a",
            baseUrl = "https://relay.example.com/v1",
            apiKey = "sk-relay",
            models = listOf("m1", "m2"),
        )
        val out = ProviderGroups.effective(global, listOf(relay), "a", "a", null)
        assertEquals("sk-relay", out.apiKey)
        assertEquals("https://relay.example.com/v1", out.baseUrl)
        assertEquals("m1", out.model)
    }

    /* ═══════════ 思考参数开关（v0.51.0） ═══════════ */

    @Test
    fun `sendThinkingParams 往返不丢 —— 关掉的设置不能静默变回开`() {
        val src = group().copy(sendThinkingParams = false)
        val back = ProviderGroups.decode(ProviderGroups.encode(listOf(src))).single()
        assertTrue("关掉的状态必须存回来", !back.sendThinkingParams)
    }

    @Test
    fun `老分组缺这个字段时默认是开 —— 升级的人不能静默失去思考能力`() {
        // 存这个字段之前写下的 JSON（没有 sendThinkingParams）
        val legacy = """[{"id":"g1","name":"默认","baseUrl":"https://api.deepseek.com/v1",""" +
            """"apiKey":"sk-a","checkedModels":["deepseek-chat"]}]"""
        val back = ProviderGroups.decode(legacy).single()
        assertTrue("缺字段必须是 true（= 本开关存在之前的行为）", back.sendThinkingParams)
    }

    /* ═══════════ 上下文窗口（v0.51.0） ═══════════ */

    @Test
    fun `contextWindow 往返不丢 —— 填过的窗口不能静默变回没填`() {
        val src = group().copy(contextWindow = 32_000)
        val back = ProviderGroups.decode(ProviderGroups.encode(listOf(src))).single()
        assertEquals(32_000, back.contextWindow)
    }

    @Test
    fun `老分组没这个字段时是 null —— 没填就是没填，别造一个默认值出来`() {
        // ⚠️ 造一个默认值出来就等于"替用户声明了窗口" —— 而那是我们不知道的事，
        //    会让界面把估算当事实显示（见 ContextCompress.contextLimitKnown）。
        val legacy = """[{"id":"g1","name":"默认","baseUrl":"https://api.deepseek.com/v1",""" +
            """"apiKey":"sk-a"}]"""
        assertNull(ProviderGroups.decode(legacy).single().contextWindow)
    }

    @Test
    fun `窗口填 0 会被当成没填存下来 —— 0 不是有效窗口`() {
        val src = group().copy(contextWindow = 0)
        val back = ProviderGroups.decode(ProviderGroups.encode(listOf(src))).single()
        assertNull(back.contextWindow)
    }

    /* ─────────── 两个窗口分开（v0.53.0）─────────── */

    @Test
    fun `memoryContextWindow 往返不丢`() {
        val src = group().copy(contextWindow = 200_000, memoryContextWindow = 64_000)
        val back = ProviderGroups.decode(ProviderGroups.encode(listOf(src))).single()
        assertEquals(200_000, back.contextWindow)
        assertEquals(64_000, back.memoryContextWindow)
    }

    @Test
    fun `老分组只有 contextWindow —— 记忆窗口回落到它，压缩时机不变`() {
        // ⚠️ 本轮最重要的兼容不变式：老用户没有"记忆窗口"这一栏，
        //    升级后必须继续按他们原来填的那个数压缩 —— 否则要么该压不压
        //（一路撞上服务商上限、请求报错），要么猛压（每次压缩都白花钱）。
        val legacy = """[{"id":"g1","name":"默认","baseUrl":"https://api.deepseek.com/v1",""" +
            """"apiKey":"sk-a","contextWindow":32000}]"""
        val g = ProviderGroups.decode(legacy).single()
        assertNull("不该凭空多出一个记忆窗口", g.memoryContextWindow)
        assertEquals("必须回落到 contextWindow", 32_000, g.effectiveMemoryWindow)
    }

    @Test
    fun `两个都填时，压缩用的是记忆窗口（不是 API 窗口）`() {
        val g = group().copy(contextWindow = 200_000, memoryContextWindow = 32_000)
        assertEquals(32_000, g.effectiveMemoryWindow)
    }

    @Test
    fun `两个都没填 → 压缩窗口为 null，走按模型名猜的默认`() {
        assertNull(group().effectiveMemoryWindow)
    }
}
