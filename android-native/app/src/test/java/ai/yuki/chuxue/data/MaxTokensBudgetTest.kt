package ai.yuki.chuxue.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 输出预算必须**随思考强度一起长**（v0.61.0）。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * 用户报的那个 bug
 * ═══════════════════════════════════════════════════════════════════════════
 * 「思考模式开启 → 想了一分钟左右 → 报错：Ta 想了很多，却一个字都没写出来」
 * 附带的诊断是「content 为空；**推理 2934 字**」。
 *
 * 机制：思考链和正文**共用同一份 `max_tokens`**。而 `maxTokensFor` 原先只看
 * "开没开思考"，**完全不看强度** —— `low` 和 `max` 拿到的都是 8192。
 * 强度越高推理越长，`max` 档的推理自己就能把这 8192 吃光，正文一个 token 都分不到：
 * 请求成功、流正常收尾、`content` 是空的。
 *
 * 借鉴的是天枢的做法（`_refs/Tianshu/src/api/factory.ts`）：它对 Anthropic 把
 * 推理预算按 `maxTokens` 的比例切（`medium`≈30%、`high`≈60%），**保证推理永远
 * 吃不光预算、正文总有位置**。OpenAI 兼容族没有独立的推理预算字段，
 * 那就反过来 —— **把总量按强度抬上去**，让"推理占比"回到安全区间。
 *
 * 这几条测试钉的就是那个比例关系：强度越高，预算必须越大。
 */
class MaxTokensBudgetTest {

    @Test
    fun `思考强度越高，预算必须越大 —— 否则 max 档会重演"推理吃光正文"`() {
        val low = PromptEngine.maxTokensFor(thinkingEnabled = true, reasoningEffort = "low")
        val high = PromptEngine.maxTokensFor(thinkingEnabled = true, reasoningEffort = "high")
        val max = PromptEngine.maxTokensFor(thinkingEnabled = true, reasoningEffort = "max")

        assertTrue("low < high（实际 $low vs $high）", low < high)
        assertTrue("high < max（实际 $high vs $max）", high < max)
    }

    @Test
    fun `max 档要给到足够余量 —— 用户那次推理就有 2934 字`() {
        // 中文约 1 字 ≈ 1~2 token；2934 字的推理 ≈ 3k~6k token。
        // 预算若还停在 8192，正文只剩两三千 token 的位置，稍微写长一点就再次撞墙。
        val max = PromptEngine.maxTokensFor(thinkingEnabled = true, reasoningEffort = "max")
        assertTrue(
            "max 档预算至少 16384（实际 $max）：推理占掉一半后，正文还得有得写",
            max >= 16384,
        )
    }

    @Test
    fun `关掉思考也必须留足推理余量 —— 实测模型会自己推理`() {
        // ⚠️ v0.61.1 改了契约。旧断言是「关思考时一律 4096」，它基于一个**错误假设**：
        //    "不发 thinking / reasoning_effort，模型就不会推理"。
        //
        // 实测打脸（your-llm-provider.example.com / deepseek-flash，**一个思考参数都没发**）：
        //    finish_reason = 'length'
        //    message.content = ''
        //    message.reasoning_content = '我们需要回答用户中文"说三个字"…'
        // 模型自己推理了，把 max_tokens 独吞，正文一个 token 都没分到。
        //
        // 「关思考」只能保证**我们不发那个参数**，保证不了**模型不想**。
        // 所以预算一律按强度给足，与发不发 thinking 参数无关。
        for (effort in listOf("low", "high", "max")) {
            val off = PromptEngine.maxTokensFor(thinkingEnabled = false, reasoningEffort = effort)
            assertTrue(
                "关思考 + effort=$effort 也必须留足余量，实测 $off 太小",
                off >= 8192,
            )
        }
    }

    @Test
    fun `未知强度不能掉进比 low 还小的坑`() {
        val weird = PromptEngine.maxTokensFor(thinkingEnabled = true, reasoningEffort = "whatever")
        val low = PromptEngine.maxTokensFor(thinkingEnabled = true, reasoningEffort = "low")
        assertTrue("未知值应保守地按默认档处理，不能比 low 还小（$weird vs $low）", weird >= low)
    }
}
