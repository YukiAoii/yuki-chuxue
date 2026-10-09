package ai.yuki.chuxue.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `sendMode` 的**白名单判据 + 旧值清洗**（v0.61.2）。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * 这个测试是为一次真实故障写的
 * ═══════════════════════════════════════════════════════════════════════════
 * v0.61.0 删掉了「连发」模式（`SEND_MODE_WAIFU = "waifu"`），但**没有清洗已存的旧值**。
 * 而传输判据当时写成了白名单：
 *
 * ```kotlin
 * stream    = sendMode == SEND_MODE_STREAM     // "waifu" == "stream" → false
 * nonStream = sendMode != SEND_MODE_STREAM     //                    → true
 * ```
 *
 * 于是**任何一个不认识的值（旧值 "waifu"、将来可能的新值、被手改的备份）都会掉进
 * 「非流式」** —— 用户明明"选的流式"，拿到的却是一次性全文输出。
 * 而且设置页只画「流式 / 非流式」两枚，`"waifu"` 哪个都不匹配 → 界面看起来像没选中。
 *
 * **教训**：判据要用**黑名单**（只有明确要非流式时才非流式），
 * 未知值一律回落到**默认且体验最好**的那一档（流式）。
 */
class SendModeNormalizeTest {

    @Test
    fun `只有明确的一次性才走非流式 —— 未知值必须回落到流式`() {
        assertTrue("instant 是非流式", isNonStreamMode(SEND_MODE_INSTANT))
        assertFalse("stream 是流式", isNonStreamMode(SEND_MODE_STREAM))
        assertFalse(
            "旧值 waifu 必须回落到流式（这就是那次故障）",
            isNonStreamMode("waifu"),
        )
        assertFalse("空串回落到流式", isNonStreamMode(""))
        assertFalse("将来出现的新值也回落到流式，不该掉进非流式", isNonStreamMode("some-future-mode"))
    }

    @Test
    fun `旧值在加载时就被清洗成流式`() {
        assertEquals(SEND_MODE_STREAM, normalizeSendMode("waifu"))
        assertEquals(SEND_MODE_STREAM, normalizeSendMode(null))
        assertEquals(SEND_MODE_STREAM, normalizeSendMode(""))
        assertEquals(SEND_MODE_STREAM, normalizeSendMode("whatever"))
    }

    @Test
    fun `两个已知值原样保留`() {
        assertEquals(SEND_MODE_STREAM, normalizeSendMode(SEND_MODE_STREAM))
        assertEquals(SEND_MODE_INSTANT, normalizeSendMode(SEND_MODE_INSTANT))
    }
}
