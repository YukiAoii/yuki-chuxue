package ai.yuki.chuxue.data.room

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WAL 恢复的拼接规则（开发文档 §9.5）。
 *
 * 崩溃恢复的进程/网络行为无法在无设备环境验证，但**拼接规则可以** —— 而它恰好是
 * 恢复正确性的全部：拼错顺序或漏掉尾块，恢复出来的就是「她没说过的话」。
 */
class WalRecoveryTest {

    private fun entry(
        id: Long,
        delta: String,
        finishReason: String? = null,
        turnIndex: Int = 0,
        receivedAt: Long = 1_000L,
    ) = WalEntryEntity(
        id = id,
        sessionId = "s1",
        turnIndex = turnIndex,
        delta = delta,
        finishReason = finishReason,
        receivedAt = receivedAt,
    )

    @Test
    fun `片段按 id 顺序拼成完整正文`() {
        val entries = listOf(
            entry(1, "初"),
            entry(2, "雪"),
            entry(3, "，你好"),
        )
        assertEquals("初雪，你好", WalRecovery.joinDeltas(entries))
    }

    @Test
    fun `空列表拼出空串`() {
        assertEquals("", WalRecovery.joinDeltas(emptyList()))
    }

    @Test
    fun `空片段不产生内容`() {
        val entries = listOf(entry(1, ""), entry(2, ""), entry(3, "好"))
        assertEquals("好", WalRecovery.joinDeltas(entries))
    }

    /**
     * 关键：同一毫秒到达的片段，**时间戳相同**。
     * 若按 receivedAt 排序，顺序就是随机的 —— 拼接结果会变成「你好，初雪」。
     * 所以排序只能靠自增主键 id。
     */
    @Test
    fun `同一毫秒的片段不会因时间戳相同而乱序`() {
        val sameMillis = 1_700_000_000_000L
        val entries = listOf(
            entry(10, "一", receivedAt = sameMillis),
            entry(11, "二", receivedAt = sameMillis),
            entry(12, "三", receivedAt = sameMillis),
        )
        assertEquals("一二三", WalRecovery.joinDeltas(entries))
    }

    @Test
    fun `isComplete 识别服务端正常收尾`() {
        assertTrue(
            WalRecovery.isComplete(
                listOf(entry(1, "话"), entry(2, "", finishReason = "stop")),
            ),
        )
        assertFalse(
            WalRecovery.isComplete(
                listOf(entry(1, "话"), entry(2, "没说完")),
            ),
        )
    }

    @Test
    fun `finishReason 为空串不算收尾`() {
        assertFalse(WalRecovery.isComplete(listOf(entry(1, "x", finishReason = ""))))
    }

    @Test
    fun `未收尾的片段照样能拼出已到达的内容`() {
        // 弱网断流：没有 finishReason，但收到的那部分一个字都不能丢
        val entries = listOf(entry(1, "她说了一半"), entry(2, "就断了"))
        assertEquals("她说了一半就断了", WalRecovery.joinDeltas(entries))
        assertFalse(WalRecovery.isComplete(entries))
    }
}
