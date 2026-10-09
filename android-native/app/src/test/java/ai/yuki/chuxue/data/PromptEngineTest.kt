package ai.yuki.chuxue.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Prompt 组装测试 —— 核心是**跨轮前缀的逐字节稳定性**。
 *
 * 缓存命中条件是「从第 0 个 token 起逐字节匹配」，所以断言不能只测单轮，
 * 必须测**第 N 轮的请求是否完整包含第 N-1 轮发出去的全部消息**。
 */
class PromptEngineTest {

    private val settings = AppSettings(
        apiKey = "sk-test",
        model = "deepseek-flash",
        globalPrefixEnabled = false,
    )

    private val persona = Persona(
        id = "p1",
        userNickname = "小明",
        userGender = "男",
        customPrompt = "角色名称：初雪\n年龄：20\n职业：学生",
        greeting = "（抬头）{user_nickname}，你来了。",
    )

    private val frozen = PromptEngine.buildFrozenPrefix(settings, persona)

    private fun messagesOf(body: String) =
        Json.parseToJsonElement(body).jsonObject["messages"]!!.jsonArray

    /* ══════════════ 冻结前缀 ══════════════ */

    @Test
    fun `冻结前缀包含用户信息与角色设定`() {
        assertTrue(frozen.contains("小明"))
        assertTrue(frozen.contains("男"))
        assertTrue(frozen.contains("角色名称：初雪"))
    }

    @Test
    fun `冻结前缀不含任何时间信息`() {
        assertFalse("不得含日期", Regex("""\d{4}-\d{2}-\d{2}""").containsMatchIn(frozen))
        assertFalse("不得含时刻", Regex("""\d{1,2}:\d{2}""").containsMatchIn(frozen))
    }

    @Test
    fun `冻结前缀同样输入产出同样字节`() {
        assertEquals(frozen, PromptEngine.buildFrozenPrefix(settings, persona))
    }

    @Test
    fun `角色性格为空时不输出该段`() {
        val p = persona.copy(personality = null)
        assertFalse(PromptEngine.buildFrozenPrefix(settings, p).contains("# 角色性格"))
        val p2 = persona.copy(personality = "  ")
        assertFalse(PromptEngine.buildFrozenPrefix(settings, p2).contains("# 角色性格"))
    }

    @Test
    fun `人设打开通用设定时排在冻结前缀最前`() {
        // v0.53.0：是否使用通用设定改由**人设**决定（Persona.useGlobalPrefix），
        // 旧的全局面板开关 AppSettings.globalPrefixEnabled 已废弃。
        val s = settings.copy(globalPrefix = "# 全局规则\n- 不要承认是AI")
        val out = PromptEngine.buildFrozenPrefix(s, persona.copy(useGlobalPrefix = true))
        assertTrue(out.startsWith("# 全局规则"))
        assertTrue(out.indexOf("# 全局规则") < out.indexOf("# 关于用户"))
    }

    @Test
    fun `人设关闭通用设定时不输出 —— 默认就是关`() {
        assertFalse(frozen.contains("全局规则"))
    }

    @Test
    fun `老数据未设置过时沿用全局开关 —— 升级不碎老用户缓存`() {
        // 老用户：人设里**没有**这一栏（null），全局开关是 true（升级前的默认值）。
        // ⚠️ 这条是本轮最重要的不变式：升级后前缀必须**一字节不变**，
        //    否则老用户的每个会话都会碎一次缓存（按未命中计费）。
        val s = settings.copy(globalPrefixEnabled = true, globalPrefix = "# 全局规则\n- 不要承认是AI")
        val legacy = persona.copy(useGlobalPrefix = null)
        val out = PromptEngine.buildFrozenPrefix(s, legacy)
        assertTrue("老数据必须沿用全局开关", out.startsWith("# 全局规则"))
    }

    @Test
    fun `人设显式关掉时，全局开关即使是开也不输出`() {
        // 新用户 / 显式关过的人设：globalPrefixEnabled 还是默认 true，
        // 但这个人设写死了 false —— 通用设定**不得**出现。
        val s = settings.copy(globalPrefixEnabled = true, globalPrefix = "# 全局规则")
        val p = persona.copy(useGlobalPrefix = false)
        assertFalse(PromptEngine.buildFrozenPrefix(s, p).contains("# 全局规则"))
    }

    /* ══════════════ 附录（第 4 层）══════════════ */

    @Test
    fun `附录为空时返回空串`() {
        assertEquals("", PromptEngine.buildAppendix())
    }

    @Test
    fun `附录包含时间、约束、记忆`() {
        val a = PromptEngine.buildAppendix(
            constraints = listOf("以后叫我宝贝"),
            memories = listOf("用户喜欢咖啡"),
            now = "2026-09-25 14:30",
        )
        assertTrue(a.startsWith("<appendix>"))
        assertTrue(a.contains("<time>2026-09-25 14:30</time>"))
        assertTrue(a.contains("以后叫我宝贝"))
        assertTrue(a.contains("用户喜欢咖啡"))
    }

    @Test
    fun `附录在用户输入之前`() {
        val plan = PromptEngine.plan(
            settings, frozen, emptyList(), "在吗",
            constraints = listOf("叫我宝贝"), now = "2026-09-25 10:00",
        )
        val content = plan.userMessage.content
        assertTrue("附录应在最前", content.indexOf("<appendix>") < content.indexOf("在吗"))
        assertTrue("用户输入应在最后", content.endsWith("在吗"))
    }

    /* ══════════════ 跨轮前缀稳定（核心）══════════════ */

    @Test
    fun `第二轮请求完整包含第一轮的全部消息`() {
        val p1 = PromptEngine.plan(settings, frozen, emptyList(), "你好", now = "10:00")
        val first = messagesOf(p1.body)

        val history = listOf(p1.userMessage, ChatMessage("assistant", "你好呀"))
        val p2 = PromptEngine.plan(settings, frozen, history, "在做什么", now = "10:01")
        val second = messagesOf(p2.body)

        for (i in first.indices) {
            assertEquals(
                "第 2 轮第 $i 条与第 1 轮不一致 —— 前缀漂移会让缓存失效",
                first[i], second[i],
            )
        }
    }

    @Test
    fun `连续五轮，前缀累积且从不改写`() {
        var history = emptyList<ChatMessage>()
        val prefixes = mutableListOf<List<kotlinx.serialization.json.JsonElement>>()

        repeat(5) { turn ->
            val p = PromptEngine.plan(settings, frozen, history, "第 ${turn + 1} 句")
            val msgs = messagesOf(p.body).toList()
            prefixes += msgs
            if (turn > 0) {
                val prev = prefixes[turn - 1]
                for (i in prev.indices) {
                    assertEquals("第 ${turn + 1} 轮第 $i 条漂移", prev[i], msgs[i])
                }
            }
            history = history + p.userMessage + ChatMessage("assistant", "回复 ${turn + 1}")
        }
    }

    @Test
    fun `时间只出现在附录里，不污染冻结前缀与历史`() {
        val p = PromptEngine.plan(settings, frozen, emptyList(), "晚上了", now = "23:45")
        val msgs = messagesOf(p.body)
        // system 层不得含时间
        assertFalse(msgs[0].jsonObject["content"]!!.jsonPrimitive.content.contains("23:45"))
        // 附录里应当有
        assertTrue(p.userMessage.content.contains("23:45"))
    }

    /* ══════════════ system 层的字节稳定 ══════════════ */

    @Test
    fun `没压过的会话 system 内容逐字节不变 —— 老会话不受影响`() {
        val sys = messagesOf(PromptEngine.plan(settings, frozen, emptyList(), "在吗").body)[0]
            .jsonObject["content"]!!.jsonPrimitive.content
        assertEquals(frozen, sys)
    }

    // ⚠️ 这里曾有一条 v0.47.0 的测试「压缩后的摘要并进 system，请求里 system 之后必须是 user」，
    //    断言 system 后面只能跟 user。**已删除**：直连 DeepSeek API 实测表明
    //    `system → assistant → user` 的命中与 `system → user` **完全相同**（1371→1152 vs 1368→1152），
    //    形状对前缀缓存没有影响。留着这条会挡住一类本来合法的实现。
    //    压缩后的 history 形状由 `ContextCompressTest` 覆盖（摘要挂 assistant 侧）。

    /* ══════════════ 请求体与历史一致 ══════════════ */

    @Test
    fun `plan 产出的 userMessage 与请求体最后一条逐字节相同`() {
        val p = PromptEngine.plan(settings, frozen, emptyList(), "在吗", memories = listOf("喜欢咖啡"))
        val last = messagesOf(p.body).last().jsonObject
        assertEquals("user", last["role"]!!.jsonPrimitive.content)
        assertEquals(
            "写进历史的内容必须与发出去的一致，否则下一轮前缀错位",
            p.userMessage.content, last["content"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `同一状态重复构造请求体，字节完全相同`() {
        val h = listOf(ChatMessage("user", "a"), ChatMessage("assistant", "b"))
        val a = PromptEngine.plan(settings, frozen, h, "c")
        val b = PromptEngine.plan(settings, frozen, h, "c")
        assertEquals(a.body, b.body)
    }

    /* ══════════════ 思考模式 ══════════════ */

    @Test
    fun `思考模式开启时传 thinking 与 effort`() {
        val body = PromptEngine.plan(settings.copy(thinkingEnabled = true, reasoningEffort = "max"),
            frozen, emptyList(), "x").body
        val o = Json.parseToJsonElement(body).jsonObject
        assertEquals("enabled", o["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("max", o["reasoning_effort"]!!.jsonPrimitive.content)
    }

    @Test
    fun `思考模式关闭时传 disabled 且不带 effort`() {
        val body = PromptEngine.plan(settings.copy(thinkingEnabled = false), frozen, emptyList(), "x").body
        val o = Json.parseToJsonElement(body).jsonObject
        assertEquals("disabled", o["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertFalse("关闭思考不应带 effort", o.containsKey("reasoning_effort"))
    }

    @Test
    fun `不传 temperature —— 思考模式下它不生效，传了会误导`() {
        val o = Json.parseToJsonElement(PromptEngine.plan(settings, frozen, emptyList(), "x").body).jsonObject
        assertFalse(o.containsKey("temperature"))
    }

    /* ══════════════ 开场白 ══════════════ */

    @Test
    fun `开场白变量被替换`() {
        val g = PromptEngine.buildGreeting(persona.greeting!!, persona)
        assertTrue(g.contains("小明"))
        assertFalse(g.contains("{user_nickname}"))
    }

    @Test
    fun `开场白替换角色名`() {
        val g = PromptEngine.buildGreeting("{persona_name}来了", persona)
        assertEquals("初雪来了", g)
    }


    /* ─────────── 输出长度上限随思考模式给（v0.50.x） ───────────
     *
     * 背景：这里原本写死 2048，而官方在思考模式下把默认值抬到 64K ——
     * 因为**思考链与正文共用这份预算**。写死 2048 时，思考一长正文就是空的，
     * 表现成"她完全不回复"。下面两条把"别退回去"钉死。
     */

    /**
     * ⚠️ v0.61.1 **推翻了下面两条旧断言**（原本是 `on > off` 与 `off == 4096`）。
     *
     * 推翻它的是一次**实测**（your-llm-provider.example.com / deepseek-flash，请求里
     * **一个 `thinking` / `reasoning_effort` 都没发**）：
     * ```
     * finish_reason      = 'length'
     * message.content    = ''                       ← 正文一个 token 都没分到
     * message.reasoning_content = '我们需要回答用户中文"说三个字"…'
     * ```
     * 模型**自己**推理了，把 `max_tokens` 独吞。旧断言建立在
     * 「不发思考参数 → 模型不推理」这个**错误假设**上，而实测证明它是错的。
     *
     * 所以新契约是：**预算与 `thinkingEnabled` 无关**。
     * 「关思考」只能保证我们不发那个参数，保证不了模型不想。
     */
    @Test
    fun `输出预算不随 thinkingEnabled 变 —— 因为拦不住模型自己推理`() {
        val on = PromptEngine.maxTokensFor(thinkingEnabled = true, reasoningEffort = "high")
        val off = PromptEngine.maxTokensFor(thinkingEnabled = false, reasoningEffort = "high")
        assertEquals("发不发 thinking 参数，预算都该一样：on=$on off=$off", on, off)
    }

    @Test
    fun `不论开不开思考，预算都不能低到被推理吃光`() {
        for (on in listOf(true, false)) {
            val v = PromptEngine.maxTokensFor(thinkingEnabled = on, reasoningEffort = "high")
            // 2048 / 4096 那种量级正是「正文被挤成空白」的元凶，不许再退回去
            assertTrue("thinkingEnabled=$on 时预算不该低于 8192：$v", v >= 8192)
        }
    }

    /* ══════════ v0.51.0：第三方服务商不认识 DeepSeek 的思考参数 ══════════ */

    @Test
    fun `默认照旧发送思考参数 —— DeepSeek 路径逐字节不变`() {
        val body = PromptEngine.plan(settings, "冻结前缀", emptyList(), "你好").body
        assertTrue("默认必须带 thinking：$body", body.contains("\"thinking\""))
        assertTrue("开了思考时必须带 reasoning_effort", body.contains("\"reasoning_effort\""))
    }

    @Test
    fun `关掉之后请求体里不再出现 thinking 与 reasoning_effort`() {
        // ⚠️ 严格些的网关（OpenAI 本体 / 通义 / 智谱）收到不认识的顶层字段会**直接 400**，
        //    用户看到的是"地址和密钥都对却报错"。这个开关就是给那种情况准备的。
        val body = PromptEngine.plan(
            settings = settings.copy(thinkingEnabled = true),
            frozenPrefix = "冻结前缀",
            history = emptyList(),
            userText = "你好",
            sendThinkingParams = false,
        ).body
        assertFalse("不该带 thinking：$body", body.contains("thinking"))
        assertFalse("不该带 reasoning_effort：$body", body.contains("reasoning_effort"))
    }

    @Test
    fun `关掉思考参数不动 messages 一个字节 —— 前缀缓存不受牵连`() {
        // ⚠️ 这是最要紧的一条：这个开关只该改变"两个可选参数发不发"，
        //    一旦它顺带动了 messages，那个会话的缓存就从这里全碎。
        val on = PromptEngine.plan(settings, "f", emptyList(), "你好").body
        val off = PromptEngine.plan(
            settings = settings,
            frozenPrefix = "f",
            history = emptyList(),
            userText = "你好",
            sendThinkingParams = false,
        ).body
        val msgsOn = Json.parseToJsonElement(on).jsonObject["messages"].toString()
        val msgsOff = Json.parseToJsonElement(off).jsonObject["messages"].toString()
        assertEquals(msgsOn, msgsOff)
    }

}
