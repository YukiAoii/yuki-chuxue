package ai.yuki.chuxue.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 截断判定（v0.61.0）。
 *
 * 照搬天枢 `src/agent/worker-repair-route.ts` 的 `isTruncationStopReason` ——
 * 它为什么值得单独存在，那边的注释说得很清楚：命中即"文本必然未闭合"，
 * **且同预算的修复轮只会再撞同一面墙**，所以这个事实必须透传到失败结果里
 *（那边为此踩过两例事故：现场只剩笼统报错，真正的截断原因被吞掉）。
 *
 * 在初雪这里，它把两种**不同的病**分开 —— 以前 `finish_reason` 被当成布尔丢掉，
 * 两种病都只能报同一句"content 为空"：
 * · `length` → 确定是额度被占满，用户调低思考强度就能好；
 * · 其它   → 服务端说正常结束了、正文却是空的，让用户改设置是白改。
 */
class TruncationStopReasonTest {

    @Test
    fun `OpenAI 系与 Anthropic 系的截断值都要认`() {
        assertTrue("OpenAI 兼容族用 length", ChatErrors.isTruncationStopReason("length"))
        assertTrue("Anthropic 原生用 max_tokens", ChatErrors.isTruncationStopReason("max_tokens"))
    }

    @Test
    fun `大小写不该影响判定 —— 各家网关大小写并不统一`() {
        assertTrue(ChatErrors.isTruncationStopReason("LENGTH"))
        assertTrue(ChatErrors.isTruncationStopReason("Max_Tokens"))
    }

    @Test
    fun `正常收尾不能被误判成截断`() {
        assertFalse("stop 是正常收尾", ChatErrors.isTruncationStopReason("stop"))
        assertFalse(ChatErrors.isTruncationStopReason("tool_calls"))
    }

    @Test
    fun `没收到 finish_reason 时不能猜是截断`() {
        // 猜错会让用户白去调设置 —— 宁可说"不一定是长度不够"
        assertFalse(ChatErrors.isTruncationStopReason(null))
        assertFalse(ChatErrors.isTruncationStopReason(""))
    }
}
