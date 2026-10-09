package ai.yuki.chuxue.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 消息列表「空态」的规格（v0.61.21 · 界面美化轮）。
 *
 * ## 为什么值得抽成纯函数
 * 空态是**新用户看到的第一屏**（一条对话都没有时）——它同时承担两件事：
 * 解释"这是什么"，以及给出"下一步"。文案与动作必须**成对**：
 * · 没有人设时给「开始新对话」是死路（点了也没人可选）；
 * · 有人设时给「去创建人设」是绕路（他已经有角色了）。
 *
 * 这条配对关系是**逻辑**，不是文案 —— 所以钉在测试里，而不是靠读代码时记得。
 */
class MessageEmptyStateTest {

    @Test
    fun `没有人设 —— 引导去创建（不给人选不了的新对话）`() {
        val s = messageEmptyState(hasPersonas = false)
        assertEquals(MessageEmptyState.Action.CreatePersona, s.action)
        assertTrue(s.actionLabel.isNotBlank())
        assertTrue(s.desc.contains("人设"))
    }

    @Test
    fun `有人设 —— 引导开始新对话`() {
        val s = messageEmptyState(hasPersonas = true)
        assertEquals(MessageEmptyState.Action.NewChat, s.action)
        assertTrue(s.actionLabel.isNotBlank())
    }

    @Test
    fun `两种空态都有标题与描述 —— 不留空白块`() {
        listOf(false, true).forEach { has ->
            val s = messageEmptyState(has)
            assertTrue(s.title.isNotBlank())
            assertTrue(s.desc.isNotBlank())
        }
    }
}
