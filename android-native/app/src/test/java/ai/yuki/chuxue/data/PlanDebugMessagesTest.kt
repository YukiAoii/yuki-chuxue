package ai.yuki.chuxue.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **`Plan.debugMessages` 的接线契约**。
 *
 * ⚠️ 为什么值得单独钉：这个字段的意义是"记录**实际发出**的请求结构"。
 * 而它的默认值是 `emptyList()` —— 如果 `plan()` 忘了填它，
 * 任何读它的地方拿到的都是「消息数: 0」：**看起来在工作，实际什么都没拿到**。
 * 那比没有这个字段更糟（读的人会以为自己看到的就是真相）。
 *
 * ⚠️ v0.53.0 起它**不再被写进文件**：原先的消费者是一个把每轮请求结构追加到
 * **手机下载目录**的 `RequestLog`（v0.46.7 为查命中率临时加的诊断），
 * 用户要求删掉它并改为只在崩溃/出错时记日志（见 `CrashLog`）。
 * 字段本身**留着** —— 它是"实际发出去的是什么"的唯一旁路证据，
 * 将来做诊断界面还会用到；而且有这组测试钉着，它不会悄悄腐烂。
 */
class PlanDebugMessagesTest {

    private val settings = AppSettings(model = "deepseek-chat")
    private val persona = Persona(
        id = "p1",
        userNickname = "阿澈",
        userGender = "保密",
        customPrompt = "角色名称：初雪",
    )

    private val frozen = PromptEngine.buildFrozenPrefix(settings, persona)

    private fun plan(history: List<ChatMessage>) = PromptEngine.plan(
        settings = settings,
        frozenPrefix = frozen,
        history = history,
        userText = "在吗",
    )

    @Test
    fun `plan 必须把实际发出的 messages 带出来 —— 否则读到的只会是「消息数 0」`() {
        val out = plan(listOf(ChatMessage(role = "user", content = "你好")))
        assertTrue(
            "debugMessages 是空的 —— 读它会得到「消息数: 0」，等于没记",
            out.debugMessages.isNotEmpty(),
        )
    }

    @Test
    fun `结构是 system + history + 本轮，与真实请求一致`() {
        val history = listOf(
            ChatMessage(role = "user", content = "第一句"),
            ChatMessage(role = "assistant", content = "第一答"),
        )
        val out = plan(history)
        assertEquals("人设 + 历史2条 + 本轮1条", 4, out.debugMessages.size)
        assertEquals("system", out.debugMessages[0]["role"])
        assertEquals("user", out.debugMessages[1]["role"])
        assertEquals("assistant", out.debugMessages[2]["role"])
        assertEquals("user", out.debugMessages[3]["role"])
    }

    @Test
    fun `压缩后的摘要会出现在结构里 —— 这正是要查的那一环`() {
        val history = ContextCompress.buildHistory(
            messages = listOf(
                ChatMessage(role = "user", content = "很久以前"),
                ChatMessage(role = "assistant", content = "很久以前答"),
            ),
            summary = "他们聊过天气。",
            coveredCount = 1,
        )
        val out = plan(history)
        // 第 1 条应当是摘要（紧跟在人设之后）。
        // ⚠️ v0.47.0 曾把这条改成「摘要并入 system、system 之后必须是 user」，
        //    理由是"`system → assistant` 让缓存前缀建不起来"。**已被实测推翻**
        //    （直连 API 对照：1371→命中 1152 vs 1368→命中 1152，完全相同），v0.47.1 改回。
        val second = out.debugMessages[1]["content"].toString()
        assertTrue("摘要应当出现在第 1 条：$second", second.contains("他们聊过天气"))
    }

    @Test
    fun `debugMessages 不进请求体 —— 它是旁路，不能改变发出的字节`() {
        val withHistory = plan(listOf(ChatMessage(role = "user", content = "x")))
        val out2 = PromptEngine.plan(
            settings = settings, frozenPrefix = frozen,
            history = listOf(ChatMessage(role = "user", content = "x")),
            userText = "在吗",
        )
        // 同样的输入必须产出同样的 body（旁路字段不影响）
        assertEquals("旁路字段不能改变请求体", withHistory.body, out2.body)
        assertTrue("body 里不该出现 debugMessages 字样", !withHistory.body.contains("debugMessages"))
    }
}
