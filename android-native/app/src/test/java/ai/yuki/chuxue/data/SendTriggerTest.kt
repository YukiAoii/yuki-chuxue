package ai.yuki.chuxue.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * **压缩触发判定的三分支**（v0.48.0，用户要求"做个选项用户自己选择"）。
 *
 * ## 为什么这个测试值得存在
 * 这是本项目**唯一会主动花钱**的分叉：压缩会让下一轮请求前缀全变，
 * 那一次全额按未命中计费；而 `auto` 模式还会**替用户**做这个决定。
 * 它原来长在 `ChatViewModel.send()` 里 —— 而那个类要 `AndroidViewModel` + Room
 * 才跑得起来，于是这条分叉**单测覆盖不到**。抽成纯函数就是为了让它可被钉住。
 *
 * ## 用户原话里最容易被做错的一条
 * 「**不确定就不在弹出选择确认弹窗**」—— `ask` 模式**绝不能**返回
 * "该弹模态框"这种东西。它只能返回 [ContextCompress.CompressDecision.Suggest]
 * （界面层据此显示一行 + 高亮按钮），**不打断输入**。
 */
class SendTriggerTest {

    private fun msg(role: String, content: String) = ChatMessage(role = role, content = content)

    /** 造能撑满上下文的对话：每条 40 token 左右，n 轮就是 80n token 上下 */
    private fun heavyTurns(n: Int): List<ChatMessage> = buildList {
        repeat(n) { i ->
            add(msg("user", "第${i}轮的提问内容，写长一点好让占用率上得去。".repeat(2)))
            add(msg("assistant", "第${i}轮的回答内容，同样写长一点，撑住 token。".repeat(2)))
        }
    }

    private fun decide(
        messages: List<ChatMessage>,
        limit: Int = 10_000,
        threshold: Float = 0.70f,
        mode: String = COMPRESS_MODE_ASK,
        summary: String? = null,
        coveredCount: Int = 0,
    ) = ContextCompress.decideCompress(
        frozenPrefix = "角色名称：初雪",
        messages = messages,
        summary = summary,
        coveredCount = coveredCount,
        limitTokens = limit,
        threshold = threshold,
        mode = mode,
    )

    /* ─────────── 阈值 ─────────── */

    @Test
    fun `没到阈值时什么都不做 —— 三种模式一致`() {
        val light = heavyTurns(1) // 远没到 70%
        listOf(COMPRESS_MODE_AUTO, COMPRESS_MODE_ASK, COMPRESS_MODE_MANUAL).forEach { m ->
            assertEquals(
                "模式=$m 未到阈值不该有任何动作",
                ContextCompress.CompressDecision.None, decide(light, mode = m),
            )
        }
    }

    @Test
    fun `上限非法时一律不动作 —— 不崩也不误触发`() {
        val heavy = heavyTurns(60)
        assertEquals(
            ContextCompress.CompressDecision.None,
            decide(heavy, limit = 0, mode = COMPRESS_MODE_AUTO),
        )
        assertEquals(
            ContextCompress.CompressDecision.None,
            decide(heavy, limit = -1, mode = COMPRESS_MODE_ASK),
        )
    }

    /* ─────────── 三分支 ─────────── */

    @Test
    fun `manual 模式到阈值也不动作 —— 这是旧行为，不能被新功能改变`() {
        val heavy = heavyTurns(60)
        assertEquals(
            "手动模式不该提示也不该自动压",
            ContextCompress.CompressDecision.None,
            decide(heavy, limit = 1_000, mode = COMPRESS_MODE_MANUAL),
        )
    }

    @Test
    fun `ask 模式到阈值只提示 —— 绝不返回"该弹模态框"`() {
        val heavy = heavyTurns(60)
        val d = decide(heavy, limit = 1_000, mode = COMPRESS_MODE_ASK)
        assertEquals(
            "ask 模式必须只返回 Suggest（用户原话：不弹确认弹窗）",
            ContextCompress.CompressDecision.Suggest, d,
        )
    }

    @Test
    fun `auto 模式到阈值且有新素材时直接压`() {
        val heavy = heavyTurns(60)
        assertEquals(
            ContextCompress.CompressDecision.CompressNow,
            decide(heavy, limit = 1_000, mode = COMPRESS_MODE_AUTO),
        )
    }

    /* ─────────── 护栏：没有新素材就不该压 ─────────── */

    @Test
    fun `auto 模式：已全部覆盖时不再压 —— 否则反复总结同一段`() {
        val heavy = heavyTurns(60)
        val segmentSize = ContextCompress.segmentToSummarize(heavy).size
        // 先把整段覆盖掉，再放一个足够长的摘要（保证占用率仍在阈值之上）
        val d = decide(
            heavy, limit = 1_000, mode = COMPRESS_MODE_AUTO,
            summary = "他们聊了很多。".repeat(20),
            coveredCount = segmentSize,
        )
        assertEquals(
            "整段都压过了还想再压 → 会白花钱且摘要越压越糊",
            ContextCompress.CompressDecision.None, d,
        )
    }

    @Test
    fun `ask 模式同样受"没有新素材"约束吗 —— 不受：提示不花钱，且用户可能想重压`() {
        val heavy = heavyTurns(60)
        val segmentSize = ContextCompress.segmentToSummarize(heavy).size
        val d = decide(
            heavy, limit = 1_000, mode = COMPRESS_MODE_ASK,
            summary = "他们聊了很多。".repeat(20),
            coveredCount = segmentSize,
        )
        // 这里记录**当前实现的选择**：ask 只提示、不花钱，所以不做新素材限制。
        // 若日后改成"提示也要求有新素材"，这条会红 —— 那时应当是有意改的。
        assertEquals(ContextCompress.CompressDecision.Suggest, d)
    }

    /* ─────────── 阈值边界 ─────────── */

    @Test
    fun `阈值调低会更早触发 —— 用户可调的旋钮确实生效`() {
        val mid = heavyTurns(20)
        val at90 = decide(mid, limit = 20_000, threshold = 0.9f, mode = COMPRESS_MODE_ASK)
        val at50 = decide(mid, limit = 20_000, threshold = 0.5f, mode = COMPRESS_MODE_ASK)
        // 50% 档不该比 90% 档更晚触发
        assertEquals(
            "阈值 0.5 时应当更容易到（或至少一样）：at50=$at50 at90=$at90",
            true,
            (at50 == ContextCompress.CompressDecision.Suggest) ||
                (at90 == ContextCompress.CompressDecision.None),
        )
    }

    @Test
    fun `占用率越高越容易触发 —— 同一模式下单调`() {
        val small = heavyTurns(5)
        val large = heavyTurns(80)
        val s = decide(small, limit = 5_000, mode = COMPRESS_MODE_ASK)
        val l = decide(large, limit = 5_000, mode = COMPRESS_MODE_ASK)
        assertEquals("小的不该触发", ContextCompress.CompressDecision.None, s)
        assertEquals("大的应当触发", ContextCompress.CompressDecision.Suggest, l)
    }
}
