package ai.yuki.chuxue.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **压缩触发的现实性探针 + 新公式的契约**（v0.61.56）。
 *
 * ## 用户报的问题
 * 「压缩阈值和压缩触发怎么改都不会触发，感觉一点用没有」
 *
 * ## 实测根因（本探针跑出来的数字）
 * 100 轮普通对话只占 **5,507 token**；旧公式触发线 = `128000 × 0.7 = 89,600`
 * → 需要约 **1,629 轮**。旧档位最低 0.5 也要 **1,162 轮** —— 普通用户永远碰不到。
 *
 * ## 本轮的修法（对齐 deepseek-harness 的 compaction-basic）
 * 1. **公式**：`min(W × ratio, W − O − headroom)`（原来只有 `W × ratio`，**没扣输出预留**）；
 * 2. **档位**：0.5~0.9 扩到 **0.2~0.9**（0.2 档 ≈ 465 轮、0.3 档 ≈ 697 轮）；
 * 3. **可见性**：弹窗显示触发线的绝对 token 数。
 */
class CompressTriggerRealityProbe {

    private fun msg(role: String, content: String) = ChatMessage(role = role, content = content)

    /** 一轮普通对话：用户一句 ~15 字，AI 回 ~35 字（真实观感） */
    private fun normalTurns(n: Int): List<ChatMessage> = buildList {
        repeat(n) { i ->
            add(msg("user", "第${i}轮：今天天气怎么样啊我想出去走走"))
            add(msg("assistant", "今天天气不错，阳光很好，适合出门散步。不过傍晚可能会有点凉，记得带件外套。"))
        }
    }

    private fun decide(
        messages: List<ChatMessage>,
        limit: Int = ContextCompress.DEFAULT_CONTEXT_LIMIT,
        threshold: Float,
        mode: String = COMPRESS_MODE_ASK,
    ) = ContextCompress.decideCompress(
        frozenPrefix = "角色名称：初雪",
        messages = messages,
        summary = null,
        coveredCount = 0,
        limitTokens = limit,
        threshold = threshold,
        mode = mode,
    )

    /* ══════════ 新公式的契约（triggerLineTokens）══════════ */

    @Test
    fun `触发线取「比例下界」与「容量下界」的较小者`() {
        // 大窗口 + 低阈值 → 比例下界更小 → 取它
        val w = 128_000
        val line = ContextCompress.triggerLineTokens(w, 0.2f)
        val byRatio = (w * 0.2f).toInt()
        assertEquals("低阈值下应取比例下界", byRatio, line)
    }

    @Test
    fun `容量下界生效：窗口被输出占满时触发线被压低`() {
        // 极小窗口：W−O−headroom 会小于 W×ratio → 取容量下界
        val w = 1_000
        val line = ContextCompress.triggerLineTokens(w, 0.9f)
        val byRatio = (w * 0.9f).toInt()          // 900
        val byCapacity = w - ContextCompress.SUMMARY_OUTPUT_TOKENS -
            (w * ContextCompress.COMPRESS_SAFETY_RATIO).toInt()   // 1000-800-100 = 100
        assertEquals("小窗口下容量下界更小，应取它", byCapacity, line)
        assertTrue("且必须小于比例下界（否则公式没生效）", line < byRatio)
    }

    @Test
    fun `触发线至少为 1 —— 绝不为 0`() {
        // 窗口小到 W−O−headroom ≤ 0 时，返回 0 会让 `used >= 0` 恒真 → 每轮都压缩
        assertEquals(1, ContextCompress.triggerLineTokens(100, 0.5f))
        assertEquals(1, ContextCompress.triggerLineTokens(50, 0.9f))
    }

    @Test
    fun `窗口非法时触发线为 0 —— 判定侧据此不动作`() {
        assertEquals(0, ContextCompress.triggerLineTokens(0, 0.7f))
        assertEquals(0, ContextCompress.triggerLineTokens(-1, 0.7f))
    }

    @Test
    fun `阈值越低调触发线越低 —— 用户那个旋钮真的有效`() {
        val w = 128_000
        val high = ContextCompress.triggerLineTokens(w, 0.9f)
        val low = ContextCompress.triggerLineTokens(w, 0.2f)
        assertTrue("0.2 档的触发线必须显著低于 0.9 档（$low vs $high）", low < high)
    }

    /* ══════════ 现实性：新档位下普通用户能否触发 ══════════ */

    @Test
    fun `探针：最低档 0-2 下 100 轮普通对话仍不触发（但已接近可用）`() {
        val d = decide(normalTurns(100), threshold = 0.20f)
        println("【探针】100 轮普通对话 @0.20 → $d")
        assertEquals(ContextCompress.CompressDecision.None, d)
    }

    @Test
    fun `探针：要多少轮才触发 —— 打印真实数字`() {
        fun turnsNeeded(thr: Float): Int {
            var lo = 1
            var hi = 4000
            while (lo < hi) {
                val mid = (lo + hi) / 2
                if (decide(normalTurns(mid), threshold = thr) == ContextCompress.CompressDecision.None) {
                    lo = mid + 1
                } else {
                    hi = mid
                }
            }
            return lo
        }
        val at20 = turnsNeeded(0.20f)
        val at30 = turnsNeeded(0.30f)
        val at70 = turnsNeeded(0.70f)
        println("【探针】普通对话触发所需轮数：@0.20 = $at20 轮，@0.30 = $at30 轮，@0.70 = $at70 轮")

        // 新档位必须让触发变得**现实可达**（旧最低档要 1162 轮）
        assertTrue("0.2 档应显著低于旧最低档的 1162 轮（实际 $at20）", at20 < 600)
        assertTrue("档位之间必须单调（0.2 比 0.3 早触发）", at20 < at30)
        assertTrue("0.7 仍是最高档之一（$at70）", at70 > at30)
    }

    @Test
    fun `探针：占用率读数（验证分母口径）`() {
        val used = ContextCompress.estimateSentContext(
            frozenPrefix = "角色名称：初雪",
            messages = normalTurns(100),
            summary = null,
            coveredCount = 0,
        )
        println("【探针】100 轮普通对话实际占用 = $used token（分母 ${ContextCompress.DEFAULT_CONTEXT_LIMIT}）")
        println("【探针】触发线 @0.20 = ${ContextCompress.triggerLineTokens(ContextCompress.DEFAULT_CONTEXT_LIMIT, 0.2f)} token")
        println("【探针】触发线 @0.70 = ${ContextCompress.triggerLineTokens(ContextCompress.DEFAULT_CONTEXT_LIMIT, 0.7f)} token")
        assertTrue("100 轮占用应远低于任何触发线", used < 20_000)
    }
}
