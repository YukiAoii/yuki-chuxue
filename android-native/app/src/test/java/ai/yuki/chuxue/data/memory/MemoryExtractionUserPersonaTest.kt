package ai.yuki.chuxue.data.memory

import ai.yuki.chuxue.data.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 记忆提取里「用户当前在扮演谁」（v0.61.44）。
 *
 * ## 它解决什么
 * 用户绑了「用户人设」以后，他是在**扮演**某个身份跟 Ta 说话。
 * 提取器如果不知道这件事，会把"用户在这个角色里的事"记混成 Ta 自己的事。
 * 所以提取请求里要带一小段**用户背景**（用户人设正文 + 指向条款）。
 *
 * ## ⚠️ 两条纪律
 * 1. 没绑定（或调用方没传）→ 输出与加这个参数之前**逐字节一致**；
 * 2. 背景块里用户可能写「你是…」——它只许出现在背景块里，
 *    **不许**影响提取规则（`# 对话记录` 之前的指令区）。
 */
class MemoryExtractionUserPersonaTest {

    private fun dialogue() = listOf(
        ChatMessage("user", "在吗"),
        ChatMessage("assistant", "在的，怎么了"),
    )

    @Test
    fun `没绑定时输出与旧调用逐字节一致`() {
        assertEquals(
            MemoryExtraction.buildPrompt(dialogue(), personaName = "二柱"),
            MemoryExtraction.buildPrompt(dialogue(), personaName = "二柱", userPersonaText = ""),
        )
    }

    @Test
    fun `绑定时：背景块在对话之前，且带"不是你的"指向条款`() {
        val p = MemoryExtraction.buildPrompt(
            dialogue(),
            personaName = "初雪",
            userPersonaText = "我是江湖上人称「快刀」的刀客。",
        )
        assertTrue("背景正文没进 prompt", p.contains("我是江湖上人称「快刀」的刀客。"))
        assertTrue("缺指向条款", p.contains("不是你的"))
        assertTrue("背景块必须在对话之前", p.indexOf("快刀") < p.indexOf("用户: "))
    }

    @Test
    fun `背景里的"你是…"不改动提取规则区 —— 结构隔离`() {
        val without = MemoryExtraction.buildPrompt(dialogue(), personaName = "初雪")
        val with = MemoryExtraction.buildPrompt(
            dialogue(),
            personaName = "初雪",
            userPersonaText = "你是村里最会做饭的人。",
        )
        // 规则区（# 对话记录 之前的全部内容）逐字节不变 —— 用户人设绝不许碰指令
        assertTrue("规则区结构变了", with.contains("# 对话记录"))
        assertEquals(without.substringBefore("# 对话记录"), with.substringBefore("# 对话记录"))
    }

    @Test
    fun `全空白的 userPersonaText 等同没传 —— 不产生空块`() {
        assertEquals(
            MemoryExtraction.buildPrompt(dialogue(), "二柱"),
            MemoryExtraction.buildPrompt(dialogue(), "二柱", userPersonaText = "   \n "),
        )
    }
}
