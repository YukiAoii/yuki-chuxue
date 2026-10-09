package ai.yuki.chuxue.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 流式开关对请求体的影响面（开发文档 §9；`架构红线文档` 的三条铁律）。
 *
 * 核心断言：**`stream` 只改一个字段，messages 的字节完全不动。**
 *
 * 为什么值得单独钉住：如果谁在流式路径里顺手动了 messages（哪怕加一个标记字段），
 * 缓存前缀就会从那里断开 —— 而这类改动在功能上完全「正常」，
 * 测试不看字节就永远发现不了，等用户发现费用涨了往往已经过去很多轮。
 */
class StreamPlanTest {

    private val settings = AppSettings(apiKey = "sk-test", model = "deepseek-flash")

    private val persona = Persona(
        id = "p1",
        userNickname = "阿雪",
        userGender = "男",
        customPrompt = "角色名称：小夏\n职业：咖啡师",
    )

    private val history = listOf(
        ChatMessage("assistant", "你来了。"),
        ChatMessage("user", "嗯，今天有点累。"),
    )

    private fun plan(stream: Boolean): PromptEngine.Plan = PromptEngine.plan(
        settings = settings,
        frozenPrefix = PromptEngine.buildFrozenPrefix(settings, persona),
        history = history,
        userText = "陪我说说话",
        stream = stream,
    )

    @Test
    fun `流式与非流式的用户消息完全一致`() {
        val plain = plan(stream = false)
        val streamed = plan(stream = true)

        // 这条消息会被原样写进历史 —— 只要差一个字节，下一轮前缀就错位
        assertEquals(plain.userMessage, streamed.userMessage)
        assertEquals(plain.userMessage.content, streamed.userMessage.content)
    }

    @Test
    fun `流式开关只改 stream 字段_其余字节不动`() {
        val plain = plan(stream = false)
        val streamed = plan(stream = true)

        assertNotEquals("两个请求体应当不同", plain.body, streamed.body)

        // 把 stream 的取值抹平后，两份请求体应当逐字节相同
        fun normalize(s: String) = s.replace("\"stream\":true", "\"stream\":false")
        assertEquals(
            "流式路径动了 stream 之外的字段 —— 那会破坏缓存前缀",
            normalize(plain.body),
            normalize(streamed.body),
        )
    }
}
