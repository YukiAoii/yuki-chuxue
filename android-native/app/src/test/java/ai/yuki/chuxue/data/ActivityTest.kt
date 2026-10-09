package ai.yuki.chuxue.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「Ta 正在做的事」清单该列出什么 —— 纯判定，供界面直接渲染。
 *
 * ## 它回答的问题
 * 聊天页只有"正在输入…"和思考气泡，用户看不出**后台还在忙什么**：
 * 回复发完了、界面上没事了，其实记忆提取/上下文压缩可能正在跑。
 * 这段时间里界面是"静悄悄"的，用户会以为它卡了、或者以为已经完事了。
 *
 * ## 顺序是有意义的
 * 按**流水线的真实先后**排：先回你 → 顺手记下 → 必要时整理前面的对话。
 * 顺序写死在 [current] 里而不是由界面各排各的 —— 界面一旦自己排，
 * 同一条信息在两个地方会长出两种样子。
 *
 * ## 文案是**中性**的（用户 2026-10-05）
 * 条目里**不出现人称代词**，抬头由界面统一写成「Ta 正在做的事」。
 * 用户的人设千奇百怪，条目里写死"她/他"都是替用户认定了性别。
 *
 * ⚠️ 它只驱动界面。**不进 `messages`、不进请求体**（缓存红线）。
 */
class ActivityTest {

    @Test
    fun `什么都没在跑 —— 就不该显示这张卡`() {
        assertEquals(emptyList<Activity.Kind>(), Activity.current(reply = false, memory = false, compressing = false))
    }

    @Test
    fun `只在回你`() {
        assertEquals(listOf(Activity.Kind.REPLY), Activity.current(true, false, false))
    }

    @Test
    fun `三件事都在跑时，顺序是 回你 → 记事 → 整理`() {
        assertEquals(
            listOf(Activity.Kind.REPLY, Activity.Kind.MEMORY, Activity.Kind.COMPRESS),
            Activity.current(true, true, true),
        )
    }

    @Test
    fun `记事与整理可以单独出现 —— 回复发完之后它们还在跑`() {
        assertEquals(listOf(Activity.Kind.MEMORY), Activity.current(false, true, false))
        assertEquals(listOf(Activity.Kind.COMPRESS), Activity.current(false, false, true))
        assertEquals(listOf(Activity.Kind.MEMORY, Activity.Kind.COMPRESS), Activity.current(false, true, true))
    }

    /** 条目文案必须中性：出现"她/他"就是在替用户认定人设的性别。 */
    @Test
    fun `条目文案里不许出现人称代词`() {
        Activity.Kind.entries.forEach { kind ->
            assertTrue("${kind.name} 的文案是空的", kind.label.isNotBlank())
            assertTrue("${kind.name} 的文案里出现了「她」：${kind.label}", !kind.label.contains("她"))
            assertTrue("${kind.name} 的文案里出现了「他」：${kind.label}", !kind.label.contains("他"))
        }
    }

    @Test
    fun `条目文案不带句号 —— 它是清单项，不是一句话`() {
        Activity.Kind.entries.forEach { kind ->
            assertTrue("${kind.name} 的文案不该以句号结尾：${kind.label}", !kind.label.endsWith("。"))
        }
    }
}
