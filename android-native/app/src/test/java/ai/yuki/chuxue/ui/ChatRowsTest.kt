package ai.yuki.chuxue.ui

import ai.yuki.chuxue.data.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「消息 → 聊天列表行」的映射规格（v0.61.23 · 表情包重写第一波）。
 *
 * ## 它修的是什么
 * 表情包原先是"贴在回复 item 内部的附加块"——图异步解码后整条 item 突然变高，
 * 把列表往下推（项目里为此打了一圈"滚到底"补丁）。重写方向是：
 * **表情包在列表里独立成一行**（跟在它所属的消息后面），每行自己测量、自己稳定。
 *
 * ## 为什么这条映射要单独抽出来测
 * 列表的"行数 ≠ 消息数"（一条消息 + 它的表情包 = 两行）——这是滚动锚点
 * （`ChatWindow.anchorAfterPrepend` 一族）与列表 key 的共同前提。
 * 把它抽成纯函数，是为了让"顺序 / 空值 / 边界"这些口径**可断言**，
 * 而不是散在 Composable 里靠读代码确认。
 *
 * ⚠️ 兼容口径：`emojiPath` 是**空白串**时按"没有表情包"处理 ——
 *    老数据/边界数据里出现过空串，空串不该产出一个"空图行"。
 */
class ChatRowsTest {

    private fun msg(id: String, emojiPath: String? = null) = ChatMessage(
        role = "assistant",
        content = id,
        emojiPath = emojiPath,
    )

    @Test
    fun `没有表情包时 —— 一条消息一行，顺序不变`() {
        val rows = buildChatRows(listOf(msg("a"), msg("b"), msg("c")))
        assertEquals(3, rows.size)
        assertEquals(listOf(0, 1, 2), rows.map { it.msgIndex })
        assertTrue(rows.none { it.isEmoji })
    }

    @Test
    fun `有表情包的消息 —— 后面多出一行，且紧跟它所属的消息`() {
        val rows = buildChatRows(
            listOf(
                msg("a"),                     // 无表情
                msg("b", emojiPath = "/x/1.jpg"), // 有表情
                msg("c"),
            ),
        )
        assertEquals(4, rows.size)
        assertEquals(listOf(0, 1, 1, 2), rows.map { it.msgIndex })
        assertEquals(listOf(false, false, true, false), rows.map { it.isEmoji })
    }

    @Test
    fun `空白表情包路径 —— 不产出空行（老数据的空串当"没有"处理）`() {
        val rows = buildChatRows(listOf(msg("a", emojiPath = ""), msg("b", emojiPath = "  ")))
        assertEquals(2, rows.size)
        assertTrue(rows.none { it.isEmoji })
    }

    @Test
    fun `空列表 —— 空映射，不崩`() {
        assertEquals(emptyList<ChatRow>(), buildChatRows(emptyList()))
    }

    @Test
    fun `每条消息最多多一行 —— 表情包行不带自己的表情包`() {
        val rows = buildChatRows(listOf(msg("a", emojiPath = "/x/1.jpg")))
        assertEquals(2, rows.size)
        assertFalse(rows.first().isEmoji)
        assertTrue(rows.last().isEmoji)
        assertEquals(rows.first().msgIndex, rows.last().msgIndex)
    }
}
