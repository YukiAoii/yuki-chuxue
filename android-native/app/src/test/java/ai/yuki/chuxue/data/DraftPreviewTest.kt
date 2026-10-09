package ai.yuki.chuxue.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 会话列表预览**优先显示未发送草稿**的规格。
 *
 * 用户原话：「输入框留有没发送的草稿，退出之后列表界面会显示那个会话外部消息的
 * 预览内容是［草稿］未发送的内容」。
 *
 * 为什么是"盖过"而不是"追加"：列表那一行回答的问题是
 * **"这段对话现在是什么状态"** —— 手里还攥着一句没发出去的话，
 * 比上一句已经说过的话更该被看见。
 */
class DraftPreviewTest {

    private fun session(vararg msgs: ChatMessage) = Session(messages = msgs.toList())

    @Test
    fun `没有草稿时走原来的预览`() {
        val s = session(ChatMessage("assistant", "你好呀"))
        assertEquals("你好呀", s.previewWith(null))
        assertEquals("你好呀", s.previewWith(""))
        assertEquals("你好呀", s.previewWith("   "))
    }

    @Test
    fun `有草稿时带上［草稿］前缀，盖过原预览`() {
        val s = session(ChatMessage("assistant", "你好呀"))
        assertEquals("［草稿］还没打完", s.previewWith("还没打完"))
    }

    @Test
    fun `草稿里的换行压成空格 —— 列表只有一行`() {
        val s = session(ChatMessage("assistant", "你好呀"))
        assertFalse(s.previewWith("第一行\n第二行").contains("\n"))
    }

    @Test
    fun `草稿也能盖掉表情包占位 —— 它回答的是"我手里还有什么"`() {
        val s = session(ChatMessage("assistant", "在的", emojiPath = "/emoji/x.gif"))
        assertEquals("［草稿］在打字", s.previewWith("在打字"))
    }

    @Test
    fun `草稿的空白被修掉，不会出现［草稿］后面跟一串空格`() {
        val s = session(ChatMessage("assistant", "你好呀"))
        assertEquals("［草稿］喂", s.previewWith("  喂  "))
    }
}
