package ai.yuki.chuxue.ui

import ai.yuki.chuxue.data.SEND_MODE_INSTANT
import ai.yuki.chuxue.data.SEND_MODE_STREAM
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「分段气泡」拆句的契约（v0.61.4）。
 *
 * 钉三件事：**只有说完的句子才独立成枚**、碎片不丢、总数不失控。
 * 观感（气泡之间的节奏）测不了，只能看真机。
 *
 * ⚠️ 这些断言大多从 v0.32.0「连发」的 `WaifuBubblesTest` 原样搬来 ——
 * 每一条背后都是当年真机报过、修过的坑（纯标点气泡、代码块被撕碎、落库后塌回一枚…）。
 * 不要因为"看起来啰嗦"删掉它们。
 */
class BubbleSplitTest {

    private fun parts(text: String) = BubbleSplit.split(text).filter { it.isNotBlank() }

    private fun bubbles(
        text: String,
        enabled: Boolean = true,
        mode: String = SEND_MODE_STREAM,
    ) = BubbleSplit.bubbles(text, enabled, mode)

    /* ─────────────────────── split：拆句本身 ─────────────────────── */

    @Test
    fun `空文本得到空`() {
        assertTrue(parts("").isEmpty())
    }

    @Test
    fun `两句各成一枚`() {
        assertEquals(listOf("你好。", "今天天气不错。"), parts("你好。今天天气不错。"))
    }

    @Test
    fun `短句独立成枚 —— 刻意不合并`() {
        // 「嗯。好的。」正是想要的节奏。历史第一版实现有个"短句合并"规则，
        // 会把它们并成一枚 —— 那等于把这个观感的特征抹掉，所以那条规则被删了。
        assertEquals(listOf("嗯。", "好的。"), parts("嗯。好的。"))
    }

    @Test
    fun `没说完的尾巴也占一枚`() {
        assertEquals(listOf("你好。", "我在想"), parts("你好。我在想"))
    }

    @Test
    fun `整句只有标点时保留原文而不是变空`() {
        // 兜底：所有碎片都是纯标点时，"丢弃纯标点"会把句子丢光 ——
        // 而"零枚气泡"比"一枚纯标点"更糟（屏幕上什么都没有）
        assertEquals(listOf("。"), bubbles("。"))
    }

    @Test
    fun `句子太多时会被压到上限以内`() {
        val out = parts("一。二。三。四。五。六。七。八。")
        assertTrue(
            "气泡数不该超过 ${BubbleSplit.MAX_BUBBLES}，实际 ${out.size}：$out",
            out.size <= BubbleSplit.MAX_BUBBLES,
        )
    }

    @Test
    fun `压缩时最新的句子仍然独立`() {
        val many = "第一句话在这里。第二句话在这里。第三句话在这里。第四句话在这里。第五句话在这里。"
        val out = parts(many)
        assertTrue(
            "最后一枚应当是最后一句（连发的观感就靠它）：$out",
            out.last().startsWith("第五句话"),
        )
    }

    @Test
    fun `换行分句但换行符本身不进气泡`() {
        assertEquals(listOf("第一行", "第二行"), parts("第一行\n第二行"))
    }

    @Test
    fun `没有任何标点时整段就是一枚`() {
        assertEquals(listOf("她记得你说过的每一句话"), parts("她记得你说过的每一句话"))
    }

    @Test
    fun `省略号连写不会被切出一枚纯标点的气泡`() {
        // 用户当年报的正是这个：`…` 在句末标点表里，而模型很爱写 "喂……" ——
        // 第二个 "…" 会独占一枚气泡，视觉上就是她发了一条什么都没有的消息。
        val out = parts("喂……你说想我的时候，我其实已经听见了。")
        assertTrue(
            "不该出现纯标点的气泡：$out",
            out.none { s -> s.isNotBlank() && s.none { it.isLetterOrDigit() } },
        )
    }

    @Test
    fun `点子连写也不会切出纯标点气泡`() {
        // `.` 同样在句末标点表里，"好..." 会被逐点切开
        val out = parts("好...我等你。")
        assertTrue(
            "不该出现纯标点的气泡：$out",
            out.none { s -> s.isNotBlank() && s.none { it.isLetterOrDigit() } },
        )
    }

    /* ─────────── bubbles：流式 / 历史两条路径共用的入口 ─────────── */

    @Test
    fun `开关关掉时整段就是一枚`() {
        assertEquals(
            listOf("你好。今天天气不错。"),
            bubbles("你好。今天天气不错。", enabled = false),
        )
    }

    @Test
    fun `非流式整段就是一枚`() {
        // 非流式的语义就是"整段一次性出现"，拆句是流式专属
        assertEquals(
            listOf("你好。今天天气不错。"),
            bubbles("你好。今天天气不错。", mode = SEND_MODE_INSTANT),
        )
    }

    @Test
    fun `含代码块时不拆 —— 别把一段代码切成好几枚`() {
        // 代码里的 `.` `;` 会被当成句末标点，拆开会把代码撕碎，
        // 而且每一枚还要各自渲染一遍 Markdown
        val code = "这样用。\n```kotlin\nval a = 1;\nval b = 2.\n```"
        assertEquals(listOf(code), bubbles(code))
    }

    @Test
    fun `空白内容不会拆成零枚气泡`() {
        // 空串给一枚空串：上层据此画「正在输入」
        assertEquals(listOf(""), bubbles(""))
        assertEquals(listOf("   "), bubbles("   "))
    }

    /* ─── forMessage：老消息保持原样（v0.61.6，用户要求）─── */

    @Test
    fun `老消息恒为一枚 —— 不管模式`() {
        // splitBubbles == null = v0.61.6 之前的老消息：用户要求"保持原样"，
        // 不能让它们随着新设置"突然长出几枚本来没有的气泡"
        val text = "你好。今天天气不错。"
        assertEquals(listOf(text), BubbleSplit.forMessage(text, null, SEND_MODE_STREAM))
        assertEquals(listOf(text), BubbleSplit.forMessage(text, null, SEND_MODE_INSTANT))
    }

    @Test
    fun `新消息按自己的快照拆 —— 与当前设置无关`() {
        val text = "你好。今天天气不错。"
        // 快照 true → 拆（哪怕用户现在已经把设置关掉了）
        assertEquals(
            listOf("你好。", "今天天气不错。"),
            BubbleSplit.forMessage(text, true, SEND_MODE_STREAM),
        )
        // 快照 false → 不拆（哪怕用户现在开着设置）
        assertEquals(listOf(text), BubbleSplit.forMessage(text, false, SEND_MODE_STREAM))
    }
}
