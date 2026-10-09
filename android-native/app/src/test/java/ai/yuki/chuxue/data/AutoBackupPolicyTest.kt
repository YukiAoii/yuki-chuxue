package ai.yuki.chuxue.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动备份**判定**的测试（v0.53.0）。
 *
 * 这里钉的都是"真机上只会表现为『自动备份怎么从来没跑过』"那类边界：
 * 跨午夜时段、时段开闭区间、首次备份不被节流、份数上限填成 0。
 * 它们在真机上极难复现，在 JVM 上却是一行断言就能钉死。
 */
class AutoBackupPolicyTest {

    private val HOUR = 3_600_000L

    /* ─────────── 时段 ─────────── */

    // ⚠️ v0.55.0：`shouldRun` 不再看时段（用户把那个设置去掉了，开关 = 每天一份），
    //    所以下面这组 `inWindow` 测试**只测函数本身**（它仍被保留，供将来需要时段时复用）。
    @Test
    fun `普通时段 —— 左闭右开`() {
        assertTrue(AutoBackupPolicy.inWindow(3, 3, 6))
        assertTrue(AutoBackupPolicy.inWindow(5, 3, 6))
        assertFalse("右开区间：6 点整已经出界", AutoBackupPolicy.inWindow(6, 3, 6))
        assertFalse(AutoBackupPolicy.inWindow(2, 3, 6))
    }

    @Test
    fun `跨午夜时段 —— 23 点到次日 6 点`() {
        assertTrue(AutoBackupPolicy.inWindow(23, 23, 6))
        assertTrue(AutoBackupPolicy.inWindow(0, 23, 6))
        assertTrue(AutoBackupPolicy.inWindow(5, 23, 6))
        assertFalse(AutoBackupPolicy.inWindow(6, 23, 6))
        assertFalse(AutoBackupPolicy.inWindow(12, 23, 6))
    }

    @Test
    fun `起止相同 = 全天（不是空区间）`() {
        assertTrue(AutoBackupPolicy.inWindow(0, 0, 0))
        assertTrue(AutoBackupPolicy.inWindow(13, 5, 5))
        assertTrue(AutoBackupPolicy.inWindow(23, 0, 0))
    }

    @Test
    fun `越界的小时会被归一化`() {
        assertTrue(AutoBackupPolicy.inWindow(27, 3, 6))  // 27 → 3
        assertTrue(AutoBackupPolicy.inWindow(-1, 23, 6)) // -1 → 23
    }

    /* ─────────── 节流 ─────────── */

    @Test
    fun `刚备份过 → 不跑`() {
        val now = 1_700_000_000_000L
        assertFalse(AutoBackupPolicy.shouldRun(now, lastAt = now - HOUR))
    }

    @Test
    fun `超过 24 小时 → 跑（开关的语义就是每天一份）`() {
        val now = 1_700_000_000_000L
        assertTrue(AutoBackupPolicy.shouldRun(now, lastAt = now - 25 * HOUR))
    }

    @Test
    fun `从没备份过 —— 首次不受间隔节流`() {
        val now = 1_700_000_000_000L
        assertTrue(
            "首次备份不该等满 24 小时",
            AutoBackupPolicy.shouldRun(now, lastAt = 0),
        )
    }

    @Test
    fun `新版本起不再有时段 —— 任何时刻只要够久就跑`() {
        val now = 1_700_000_000_000L
        // 早先这里要求"落在 3-6 点"才跑；现在用户把那个设置去掉了
        assertTrue(AutoBackupPolicy.shouldRun(now, lastAt = now - 25 * HOUR))
    }

    @Test
    fun `间隔可以自定义`() {
        val now = 1_700_000_000_000L
        assertTrue(AutoBackupPolicy.shouldRun(now, lastAt = now - 7 * HOUR, intervalHours = 6))
        assertFalse(AutoBackupPolicy.shouldRun(now, lastAt = now - 5 * HOUR, intervalHours = 6))
    }

    /* ─────────── 份数 ─────────── */

    @Test
    fun `超份数时算得出该删几份`() {
        assertEquals(0, AutoBackupPolicy.excessCount(fileCount = 10, maxFiles = 10))
        assertEquals(2, AutoBackupPolicy.excessCount(fileCount = 12, maxFiles = 10))
        assertEquals(0, AutoBackupPolicy.excessCount(fileCount = 3, maxFiles = 10))
    }

    @Test
    fun `上限填 0 或负数 —— 至少留一份，绝不能全删`() {
        // 真删光的代价是"用户以为有备份，其实一份不剩"，比不备份更糟
        assertEquals(9, AutoBackupPolicy.excessCount(fileCount = 10, maxFiles = 0))
        assertEquals(9, AutoBackupPolicy.excessCount(fileCount = 10, maxFiles = -5))
    }

    @Test
    fun `上限超过 100 会被夹到 100`() {
        assertEquals(0, AutoBackupPolicy.excessCount(fileCount = 50, maxFiles = 999))
    }
}
