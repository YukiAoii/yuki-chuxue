package ai.yuki.chuxue.data.memory

import ai.yuki.chuxue.data.AppSettings
import ai.yuki.chuxue.data.ChatMessage
import ai.yuki.chuxue.data.Persona
import ai.yuki.chuxue.data.PromptEngine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 记忆注入的**缓存纪律**规格 —— 本项目最不能破的一条不变量
 * （`memory/architecture.md`：记忆检索，只从附录注入）。
 *
 * 记忆是这个项目里唯一"每轮都会变、又必须让模型看见"的东西，因此它是**最接近
 * 危险线**的那一层：只要有人图省事把它拼回冻结前缀，该会话的缓存就会从拼接点起
 * 全部失效，而且是**延迟暴露**（几轮之后才表现为命中率骤降）。所以这里用"逐字节相同"
 * 把边界钉死，而不是断言"能跑通"。
 */
class MemoryInjectionTest {

    private val settings = AppSettings(
        apiKey = "sk-test",
        model = "deepseek-flash",
        globalPrefixEnabled = false,
    )

    private val persona = Persona(
        id = "p1",
        userNickname = "小明",
        userGender = "男",
        customPrompt = "角色名称：初雪",
    )

    private val frozen = PromptEngine.buildFrozenPrefix(settings, persona)

    private fun messagesOf(body: String): JsonArray =
        Json.parseToJsonElement(body).jsonObject["messages"]!!.jsonArray

    /* ══════════ 空记忆 = 零影响（存量会话不受牵连）══════════ */

    @Test
    fun `记忆为空时请求体与接线前逐字节相同`() {
        val before = PromptEngine.plan(settings, frozen, emptyList(), "在吗").body
        val after = PromptEngine.plan(settings, frozen, emptyList(), "在吗", memories = emptyList()).body
        assertEquals(
            "记忆库上线不该让「库里还没有记忆」的会话产生任何字节差异",
            before, after,
        )
    }

    /* ══════════ 注入不得触碰稳定前缀 ══════════ */

    @Test
    fun `换一批记忆时冻结前缀与历史逐字节不变`() {
        val history = listOf(
            ChatMessage("user", "你好"),
            ChatMessage("assistant", "你好呀"),
        )
        val a = messagesOf(
            PromptEngine.plan(settings, frozen, history, "在吗", memories = listOf("用户喜欢猫")).body,
        )
        val b = messagesOf(
            PromptEngine.plan(settings, frozen, history, "在吗", memories = listOf("用户讨厌下雨天")).body,
        )

        assertEquals("system（冻结前缀）必须逐字节相同", a[0], b[0])
        for (i in 1 until a.size - 1) {
            assertEquals("第 $i 条历史被改写了 —— 前缀会从这里断开", a[i], b[i])
        }
        assertNotEquals(
            "差异只允许出现在本轮 user 消息（附录所在处）",
            a.last(), b.last(),
        )
    }

    @Test
    fun `记忆只出现在本轮用户消息的附录里`() {
        val plan = PromptEngine.plan(settings, frozen, emptyList(), "在吗", memories = listOf("用户喜欢猫"))
        val msgs = messagesOf(plan.body)

        assertEquals("system", msgs[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertFalse(
            "冻结前缀里不得出现记忆 —— 那会让所有会话缓存全碎",
            msgs[0].jsonObject["content"]!!.jsonPrimitive.content.contains("用户喜欢猫"),
        )
        assertTrue(plan.userMessage.content.contains("<memories>"))
        assertTrue(plan.userMessage.content.contains("用户喜欢猫"))
        assertEquals(
            "写进历史的那条必须与发出去的逐字节一致",
            plan.userMessage.content,
            msgs.last().jsonObject["content"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `附录排在用户输入之前，用户输入仍在最后`() {
        val plan = PromptEngine.plan(settings, frozen, emptyList(), "今天好累", memories = listOf("用户经常加班"))
        val content = plan.userMessage.content
        assertTrue(content.indexOf("<memories>") < content.indexOf("今天好累"))
        assertTrue(content.endsWith("今天好累"))
    }

    /* ══════════ 记忆一旦进入历史，就成为静态前缀的一部分 ══════════ */

    @Test
    fun `已注入的记忆随历史固化，下一轮不再重复注入`() {
        val p1 = PromptEngine.plan(settings, frozen, emptyList(), "在吗", memories = listOf("用户喜欢猫"))

        // 第二轮：第一轮的 userMessage（含附录）已成为历史
        val history = listOf(p1.userMessage, ChatMessage("assistant", "嗯，我在"))
        val p2 = PromptEngine.plan(settings, frozen, history, "再说一次")

        val msgs = messagesOf(p2.body)
        assertEquals(
            "历史里那条必须原样保留 —— 它是后续所有请求前缀的一部分",
            p1.userMessage.content,
            msgs[1].jsonObject["content"]!!.jsonPrimitive.content,
        )
        assertFalse(
            "本轮没有命中新记忆时，不该再产出一个 memories 块",
            p2.userMessage.content.contains("<memories>"),
        )
    }

    @Test
    fun `同一状态重复构造，注入记忆后请求体仍逐字节稳定`() {
        val h = listOf(ChatMessage("user", "a"), ChatMessage("assistant", "b"))
        val a = PromptEngine.plan(settings, frozen, h, "c", memories = listOf("用户喜欢猫")).body
        val b = PromptEngine.plan(settings, frozen, h, "c", memories = listOf("用户喜欢猫")).body
        assertEquals(a, b)
    }
}
