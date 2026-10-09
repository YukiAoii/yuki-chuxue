package ai.yuki.chuxue.data.memory

import ai.yuki.chuxue.data.ChatMessage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 记忆提取**用谁的名字**（v0.61.21）。
 *
 * ## 用户报的（原话）
 * 「记忆库有问题吗，他为什么不跟着人设走，记得都是初雪怎么怎么了 —— 初雪是我的品牌名啊」
 *
 * ## 根因
 * `buildPrompt` 把每条助手发言都贴上**写死的品牌名**：
 * ```
 * val who = if (message.role == "user") "用户" else "初雪"   // ← 品牌名被当成角色身份
 * ```
 * 于是送给模型的对话记录里，AI 的每句话都署名「初雪」——
 * 提炼出来的记忆自然全是"初雪怎么怎么了" ✗ 而这个位置本该是**人设自己的名字**。
 *
 * ## 判据
 * · 用 `personaName`（人设的展示名）；
 * · 名字为空时退回「Ta」—— 既不写空标签，也**绝不**回落到品牌名；
 * · 用户那一侧仍然是「用户」（scope 规则依赖它，别顺手也改掉）。
 */
class MemoryExtractionNameTest {

    private fun dialogue() = listOf(
        ChatMessage("user", "在吗"),
        ChatMessage("assistant", "在的，怎么了"),
    )

    @Test
    fun `助手的话要用角色自己的名字 —— 不能用品牌名`() {
        val p = MemoryExtraction.buildPrompt(dialogue(), personaName = "二柱")
        assertTrue("该用角色名：\n$p", p.contains("二柱: "))
        assertFalse("品牌名不该出现在送给模型的对话里：\n$p", p.contains("初雪"))
    }

    @Test
    fun `名字为空时退回「Ta」—— 不留空标签，也不回落成品牌名`() {
        val p = MemoryExtraction.buildPrompt(dialogue(), personaName = "   ")
        assertFalse("品牌名不该出现：\n$p", p.contains("初雪"))
        assertTrue("空名字要有兜底：\n$p", p.contains("Ta: "))
    }

    @Test
    fun `用户那一侧仍然是「用户」—— scope 规则靠它，别顺手改掉`() {
        val p = MemoryExtraction.buildPrompt(dialogue(), personaName = "二柱")
        assertTrue("用户侧要保持：\n$p", p.contains("用户: "))
    }
}
