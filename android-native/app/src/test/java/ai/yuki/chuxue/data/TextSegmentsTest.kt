package ai.yuki.chuxue.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「最后一句」的契约（用户报过两次的那个预览问题）。
 *
 * ⚠️ 这套口径**同时**被两处消费：
 * - `Session.preview`（会话列表显示哪句）
 * - ~~`ui/WaifuBubbles`~~（v0.61.0 随「连发」模式删除）
 * 两边必须一致 —— 不一致就会出现"列表显示第一句、聊天页末尾是另一句"。
 */
class TextSegmentsTest {

    @Test
    fun `取最后一句 —— 就是用户报的那个场景`() {
        val text = "端着刚煮好的可可走过来。你要不要尝一口？就叫我一声，别的都不用"
        assertEquals("就叫我一声，别的都不用", TextSegments.lastSentence(text))
    }

    @Test
    fun `末尾带句号时也能取到最后一句`() {
        assertEquals("我先睡了。", TextSegments.lastSentence("今天好累。我先睡了。"))
    }

    @Test
    fun `省略号连写不会把纯标点当成最后一句`() {
        // `喂……` 会被切成 `喂…` 和一个孤零零的 `…`，后者是纯标点，不能当预览
        val out = TextSegments.lastSentence("喂……你怎么才回来")
        assertEquals("你怎么才回来", out)
    }

    @Test
    fun `整段只有标点时返回原文 —— 宁可长一点也不要空`() {
        assertEquals("……", TextSegments.lastSentence("……"))
    }

    @Test
    fun `空串进来空串出去`() {
        assertEquals("", TextSegments.lastSentence(""))
    }

    @Test
    fun `换行也算分句`() {
        assertEquals("第二行", TextSegments.lastSentence("第一行\n第二行"))
    }

    @Test
    fun `英文句点也算分句 —— 与拆句口径一致`() {
        assertEquals("see you", TextSegments.lastSentence("hello. see you"))
    }
}
