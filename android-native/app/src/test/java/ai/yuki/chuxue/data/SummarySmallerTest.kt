package ai.yuki.chuxue.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **摘要可用性判据**（v0.48.0，R2）。
 *
 * ## 为什么值得一道护栏
 * "压缩"的**唯一意义**是让每轮发出去的东西变少。如果模型给出的摘要比原文还长
 *（它偶尔会这样：把 30 条揉成 40 条，或者洋洋洒洒写一段"综上所述"），
 * 后果是**双输**：
 * - 用户为这次调用付了钱（全额未命中，因为压缩请求前缀刚变）；
 * - 摘要被写进库，**之后每一轮都发这份更长的文本** → 越压越贵。
 *
 * `deepseek-harness` 在 `compaction-basic/src/region.ts` 就是这么守的：
 * `if (framedSummaryTokenCount >= prepared.shadowedRouteTokenCount) throw`。
 * 我们采纳同一条判据 —— 但选择**放弃这次压缩**（不写状态）而不是抛错：
 * 用户点的是按钮、不是发请求，没必要把异常甩到他脸上；放弃是安全且可解释的。
 *
 * ## ⚠️ 第二条判据是"空白"而不是"更小"能覆盖的
 * 空摘要的 token 数是 0 —— 它**能通过"更小"那条**（0 < 原文），
 * 却会让那一段历史**真的丢掉**（模型看不到、界面又还在，用户查不出来）。
 * 这一条是实测抓出来的（先写的测试断言"空摘要应被拒绝"，跑出来是红的）。
 *
 * ⚠️ 这里调用**生产用的同一个函数**，不是复刻一遍判据 ——
 * 复刻会在漂移那天恰好放行事故。
 */
class SummarySmallerTest {

    private fun msg(role: String, content: String) = ChatMessage(role = role, content = content)

    private fun accept(summary: String?, segment: List<ChatMessage>): Boolean =
        ContextCompress.isUsableSummary(summary, segment)

    @Test
    fun `更短的摘要被接受`() {
        val segment = List(30) { msg("user", "这是第${it}句挺长的一段对话内容，用来占地方") }
        assertTrue(accept("他们聊了很多事。", segment))
    }

    @Test
    fun `更长的摘要被拒绝 —— 否则越压越贵`() {
        val segment = List(3) { msg("user", "短") }
        assertFalse("摘要比原文长就该拒绝", accept("这是一段比原文长得多的摘要。".repeat(10), segment))
    }

    @Test
    fun `长度相等也拒绝 —— 没变小就是没意义`() {
        val segment = listOf(msg("user", "十二个字的原文内容啊"))
        val summary = "十二个字的摘要内容啊"
        assertEquals(
            ContextCompress.estimateTokens(summary),
            ContextCompress.estimateTokens(segment),
        )
        assertFalse("相等也算没变小", accept(summary, segment))
    }

    @Test
    fun `空摘要被拒绝 —— 那是把历史彻底丢了`() {
        val segment = List(20) { msg("user", "第${it}句") }
        assertFalse("空摘要绝不能接受", accept("", segment))
    }

    @Test
    fun `只有空白的摘要被拒绝 —— 它比空串更容易漏过去`() {
        val segment = List(20) { msg("user", "第${it}句") }
        assertFalse("纯空白也不算有效摘要", accept("   \n\t  ", segment))
    }

    @Test
    fun `null 摘要被拒绝 —— 模型没返回内容时不能当成"总结成了空"`() {
        val segment = List(20) { msg("user", "第${it}句") }
        assertFalse(accept(null, segment))
    }

    @Test
    fun `判定用 token 估算而不是字符数 —— 中英混排下才准`() {
        // 20 个汉字 ≈ 20 token；80 个英文字符 ≈ 20 token —— "看起来"长度差很多，
        // 但按 token 口径一样，所以必须判为"没变小"
        val segment = listOf(msg("user", "中".repeat(20)))
        val summary = "a".repeat(80)
        assertEquals(
            ContextCompress.estimateTokens(summary),
            ContextCompress.estimateTokens(segment),
        )
        assertFalse(accept(summary, segment))
    }

    @Test
    fun `空白摘要能通过"更小"却过不了这一关 —— 这条正是它存在的理由`() {
        val segment = List(20) { msg("user", "第${it}句") }
        // 前提：空摘要在"更小"这条判据下是**通过**的（0 < 20）
        assertTrue(
            "前提确认：单纯比大小的话空串算'更小'",
            ContextCompress.estimateTokens("") < ContextCompress.estimateTokens(segment),
        )
        // 所以必须有第二条判据把它挡住
        assertFalse("空摘要必须被单独挡住", accept("", segment))
    }
}
