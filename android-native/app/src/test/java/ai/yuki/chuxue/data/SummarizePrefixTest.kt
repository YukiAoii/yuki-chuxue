package ai.yuki.chuxue.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 摘要请求的**前缀复用**契约（v0.48.0，R1）。
 *
 * ## 背景：旧实现一分钱缓存都吃不到
 * 旧 `summarize` 只发**一条孤立的 user 消息**（总结要求 + 全文）。那段前缀与主对话
 * **没有任何公共部分** → 每次压缩都是**全额未命中**，而被压的那一整段可不短。
 *
 * 现在改成 `[system(人设)] + [被压段原文…] + [user(指令)]`：
 * 前两段与**上一轮主对话请求逐字节相同** → 命中前缀缓存（约 1/10 价）。
 * 这是 `deepseek-harness` 的做法（`compaction-basic/src/region.ts` 的
 * `buildSummarizationInput` + 末尾追加指令）。
 *
 * ⚠️ 这里断言的是**组装形态**（纯逻辑，可单测）；
 * 「真的命中了吗、省了多少」必须真机验证 —— 本机无 adb，不做声称。
 */
class SummarizePrefixTest {

    private val settings = AppSettings(apiKey = "sk-test", model = "deepseek-flash")

    private val persona = Persona(
        id = "p1",
        userNickname = "小清",
        userGender = "保密",
        customPrompt = "角色名称：初雪\n年龄：19",
    )

    private fun msg(role: String, content: String) = ChatMessage(role = role, content = content)

    /**
     * 复刻 `DeepSeekClient.summarize` 的组装逻辑（它内部 `withContext` + 发网络，
     * 单测里不能真发）。**形态必须与生产代码一致** —— 否则测的就不是同一件事。
     * 这里用 `PromptEngine.messageToMap`（生产用的同一个函数）+ 相同的顺序。
     */
    private fun buildSummaryMessages(
        frozenPrefix: String,
        segment: List<ChatMessage>,
        instruction: String,
    ): List<Map<String, Any?>> = buildList {
        add(mapOf("role" to "system", "content" to frozenPrefix))
        segment.forEach { add(PromptEngine.messageToMap(it)) }
        add(mapOf("role" to "user", "content" to instruction))
    }

    private fun roles(msgs: List<Map<String, Any?>>) =
        msgs.map { it["role"] as String }

    @Test
    fun `首条是 system 人设 —— 与主对话同构才谈得上前缀复用`() {
        val frozen = PromptEngine.buildFrozenPrefix(settings, persona)
        val segment = listOf(msg("user", "问1"), msg("assistant", "答1"))
        val out = buildSummaryMessages(frozen, segment, "请总结")
        assertEquals("system", roles(out).first())
        assertEquals(frozen, out.first()["content"])
    }

    @Test
    fun `中间是被压段的原文，顺序与 role 都不变 —— 前缀才能逐字节对上`() {
        val frozen = "人设"
        val segment = listOf(
            msg("user", "问1"), msg("assistant", "答1"),
            msg("user", "问2"), msg("assistant", "答2"),
        )
        val out = buildSummaryMessages(frozen, segment, "指令")
        assertEquals(listOf("system", "user", "assistant", "user", "assistant", "user"), roles(out))
        // 逐条与源相同（同一函数转换，不重新拼字符串）
        segment.forEachIndexed { i, m ->
            assertEquals(m.content, out[i + 1]["content"])
        }
    }

    @Test
    fun `指令在最后 —— 变化的内容永远放尾部`() {
        val segment = listOf(msg("user", "旧话"), msg("assistant", "旧答"))
        val out = buildSummaryMessages("人设", segment, "【总结指令】")
        assertEquals("user", roles(out).last())
        assertEquals("【总结指令】", out.last()["content"])
    }

    @Test
    fun `人设段与主对话发的 system 完全一致 —— 差一个字节就全 miss`() {
        // 主对话的 system 就是 buildFrozenPrefix 的产物（见 PromptEngine.plan）
        val frozen = PromptEngine.buildFrozenPrefix(settings, persona)
        val mainPlan = PromptEngine.plan(
            settings = settings, frozenPrefix = frozen,
            history = emptyList(), userText = "在吗",
        )
        val mainSystem = Json.parseToJsonElement(mainPlan.body).jsonObject["messages"]!!
            .jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content

        val out = buildSummaryMessages(frozen, listOf(msg("user", "x")), "指令")
        assertEquals(
            "摘要请求的 system 必须与主对话的 system 逐字节相同",
            mainSystem, out.first()["content"],
        )
    }

    @Test
    fun `组装结果是合法的对话序列 —— system 之后不能紧跟 assistant 摘要之类的怪形状`() {
        val segment = listOf(msg("user", "问1"), msg("assistant", "答1"))
        val out = buildSummaryMessages("人设", segment, "指令")
        val r = roles(out)
        assertEquals("system", r.first())
        assertEquals("user", r[1])
        assertTrue("不得出现 system 紧跟 system", r.zipWithNext().none { it.first == "system" && it.second == "system" })
    }
}
