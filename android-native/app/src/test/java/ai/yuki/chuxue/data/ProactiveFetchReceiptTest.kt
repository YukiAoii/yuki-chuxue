package ai.yuki.chuxue.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「Ta 主动来找我」取件后**该弹哪几条**的纯函数契约（v0.61.48）。
 *
 * ## 为什么要有它
 * 原实现是 `if (since.isNotBlank()) { 逐条弹 }` —— 即**首次取件一条都不弹**
 *（"不拿历史消息轰炸"）。但那个设计的副作用是：开关打开后的第一次取件把积压
 * 静默吃掉，之后若心潮没产生**新**消息，用户端**永远没有任何回执**，看起来像坏了。
 *
 * 现在的语义：**首次也只弹最新一条** —— 既是"我看到了"的回执，又不轰炸。
 */
class ProactiveFetchReceiptTest {

    private fun msg(at: String, text: String) =
        XinchaoMessage(at = at, kind = "autonomous_thought", message = text)

    @Test
    fun `首次取件只弹最新一条`() {
        val msgs = listOf(msg("t1", "第一条"), msg("t2", "第二条"), msg("t3", "第三条"))
        val picked = ProactiveFetchReceipt.pickForNotification(isFirstFetch = true, messages = msgs)
        assertEquals(1, picked.size)
        assertEquals("最新那条（最后一条）", "第三条", picked.first().message)
    }

    @Test
    fun `非首次弹全部`() {
        val msgs = listOf(msg("t1", "a"), msg("t2", "b"))
        assertEquals(2, ProactiveFetchReceipt.pickForNotification(isFirstFetch = false, messages = msgs).size)
    }

    @Test
    fun `没有消息就一条都不弹`() {
        assertEquals(0, ProactiveFetchReceipt.pickForNotification(isFirstFetch = true, messages = emptyList()).size)
        assertEquals(0, ProactiveFetchReceipt.pickForNotification(isFirstFetch = false, messages = emptyList()).size)
    }

    @Test
    fun `单条消息时首次与非首次结果一致`() {
        val one = listOf(msg("t1", "只有一条"))
        assertEquals(
            ProactiveFetchReceipt.pickForNotification(isFirstFetch = false, messages = one).map { it.message },
            ProactiveFetchReceipt.pickForNotification(isFirstFetch = true, messages = one).map { it.message },
        )
    }
}
