package ai.yuki.chuxue.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * `Session.preview` 的端到端契约 —— **用户在消息列表上看到的那一行**。
 *
 * ⚠️ 为什么单独测它、而不只测 `TextSegments`：
 * `TextSegments` 只是工具，用户看的是 `preview`。
 * 中间还夹着"剥附录""摘情绪标签""截断 28 字"三步 —— 任一步错了，工具测全绿也没用。
 *（这个预览问题用户报过两次，前两次都只改了口径、没对最终输出下断言。）
 */
class SessionPreviewTest {

    private fun session(vararg contents: String) = Session(
        id = "s1",
        personaId = "p1",
        messages = contents.map { ChatMessage(role = "assistant", content = it) },
    )

    @Test
    fun `连发多句时显示最后一句 —— 用户报的那个场景`() {
        // 连发把这一条拆成三枚气泡，聊天页末尾是「就叫我一声，别的都不用」，
        // 列表就该显示同一句，而不是开头那句。
        val s = session("端着刚煮好的可可走过来。你要不要尝一口？就叫我一声，别的都不用")
        assertEquals("就叫我一声，别的都不用", s.preview)
    }

    @Test
    fun `情绪标签不会漏进预览`() {
        val s = session("[开心] 你好呀")
        assertFalse("预览里不该出现方括号标签：${s.preview}", s.preview.contains("["))
    }

    @Test
    fun `没有消息时给一句人话，而不是空白`() {
        assertEquals("还没有开始说话", Session().preview)
    }

    @Test
    fun `只看最后一条有内容的消息`() {
        val s = session("第一句不相关", "后来才说的这句")
        assertEquals("后来才说的这句", s.preview)
    }

    @Test
    fun `超长预览会截断 —— 列表一行放不下`() {
        val s = session("这是一句特别特别长的话".repeat(10))
        assert(s.preview.length <= 28) { "预览长度 ${s.preview.length} 超过 28" }
    }

    @Test
    fun `换行不会残留在预览里`() {
        val s = session("第一行\n第二行")
        assertFalse("预览里不该有换行", s.preview.contains("\n"))
    }
}
