package ai.yuki.chuxue.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「本轮真正发多少」与「还有没有新素材可压」的契约（v0.48.0）。
 *
 * ## 为什么这两条必须钉死
 * 压缩**不动 `messages`**（红线：库里一字不改）。于是拿"原始历史"算占用率，
 * 在压缩**前后完全一样** —— 后果是两个真问题：
 * 1. **触发判定每轮都成立** → `AUTO` 模式下每发一条消息就重新压一次；
 * 2. **进度条压完不降** → 用户看到"压了跟没压一样"。
 *
 * 而 `segmentToSummarize` 只看 `messages` 与 `keepRecent`，**不知道上次压到哪**，
 * 所以它会一次次返回**同一段** —— 反复总结同一段，白花钱且摘要越压越糊。
 * [ContextCompress.hasNewMaterialToCompress] 就是挡这个的。
 */
class SentContextTest {

    private fun msg(role: String, content: String) = ChatMessage(role = role, content = content)

    /** 造 n 轮对话（每轮 user + assistant），每条内容带序号以便复算 token */
    private fun turns(n: Int): List<ChatMessage> = buildList {
        repeat(n) { i ->
            add(msg("user", "问第${i}句"))
            add(msg("assistant", "答第${i}句"))
        }
    }

    /* ─────────── estimateSentContext ─────────── */

    @Test
    fun `没压缩过时与 estimateContext 逐字节等价 —— 老会话行为不变`() {
        val msgs = turns(10)
        val prefix = "角色名称：初雪"
        assertEquals(
            ContextCompress.estimateContext(frozenPrefix = prefix, messages = msgs),
            ContextCompress.estimateSentContext(frozenPrefix = prefix, messages = msgs),
        )
    }

    @Test
    fun `压缩过之后，发送量必须小于原始历史量 —— 否则进度条永远不降`() {
        val msgs = turns(60) // 120 条
        val prefix = "角色名称：初雪"
        val raw = ContextCompress.estimateContext(frozenPrefix = prefix, messages = msgs)
        val sent = ContextCompress.estimateSentContext(
            frozenPrefix = prefix,
            messages = msgs,
            summary = "他们聊过天气、工作、还有那次看雪。",
            coveredCount = 90, // 压掉前 90 条，剩 30 条 + 摘要
        )
        assertTrue(
            "压缩后发送量应显著小于原始（实际：sent=$sent raw=$raw）",
            sent < raw,
        )
    }

    @Test
    fun `coveredCount 越大，发送量越小（同一段历史）`() {
        val msgs = turns(80)
        val prefix = "人设"
        val s = "一段摘要"
        val few = ContextCompress.estimateSentContext(
            frozenPrefix = prefix, messages = msgs, summary = s, coveredCount = 40,
        )
        val many = ContextCompress.estimateSentContext(
            frozenPrefix = prefix, messages = msgs, summary = s, coveredCount = 120,
        )
        assertTrue("压得越多、发得越少（few=$few many=$many）", many < few)
    }

    @Test
    fun `摘要本身也要计入发送量 —— 它确实会发出去`() {
        val msgs = turns(40)
        val prefix = "人设"
        val short = ContextCompress.estimateSentContext(
            frozenPrefix = prefix, messages = msgs, summary = "短", coveredCount = 40,
        )
        val long = ContextCompress.estimateSentContext(
            frozenPrefix = prefix, messages = msgs,
            summary = "很长的一段摘要".repeat(30), coveredCount = 40,
        )
        assertTrue("摘要越长、发送量越大（short=$short long=$long）", long > short)
    }

    /* ─────────── hasNewMaterialToCompress ─────────── */

    @Test
    fun `消息不够多时没有新素材 —— 不该触发压缩`() {
        // keepRecent = 30，只有 10 条（5 轮）→ segmentToSummarize 返回空
        assertFalse(ContextCompress.hasNewMaterialToCompress(turns(5), coveredCount = 0))
    }

    @Test
    fun `没压过时有新素材`() {
        assertTrue(ContextCompress.hasNewMaterialToCompress(turns(40), coveredCount = 0))
    }

    @Test
    fun `已覆盖的部分不算新素材 —— 这是挡住"反复压同一段"的那条`() {
        val msgs = turns(40) // 80 条
        val segment = ContextCompress.segmentToSummarize(msgs)
        assertTrue("前提：这里确实切得出段", segment.isNotEmpty())
        // 已经覆盖了整段 → 再压一次没有新东西
        assertFalse(
            "整段都压过了还想再压 —— 会白花钱且摘要越压越糊",
            ContextCompress.hasNewMaterialToCompress(msgs, coveredCount = segment.size),
        )
        // 覆盖得比段还多（历史被删过等异常情况）也不该触发
        assertFalse(ContextCompress.hasNewMaterialToCompress(msgs, coveredCount = segment.size + 50))
    }

    @Test
    fun `历史变长后又有新素材了 —— 这是它该放行的时候`() {
        val before = turns(40) // 80 条
        val covered = ContextCompress.segmentToSummarize(before).size
        // 再聊 40 轮（80 条）→ 切点右移，超出已覆盖的部分就是新素材
        val after = turns(80)
        assertTrue(
            "聊得更多之后应当又出现可压的新内容",
            ContextCompress.hasNewMaterialToCompress(after, coveredCount = covered),
        )
    }
}
