package ai.yuki.chuxue.ui.update

import ai.yuki.chuxue.data.RemoteRelease
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 更新流程的**状态决策**契约（2026-09-30）。
 *
 * ## 它修的是什么
 * 用户手动点「检查更新」、而当前已是最新版时，弹窗**顶部**写着
 * 「更新没有完成」、**正文**却说「已经是最新版本」—— 自相矛盾，
 * 下面还跟着「重试 / 退出应用」两个不该出现的按钮。
 *
 * 根因是「已是最新」被塞进了 `State.Failed`，界面只好按失败渲染。
 * 这一组用例把"已是最新必须是一个**独立于 Failed** 的状态"钉死。
 *
 * ⚠️ 它跑在 **JVM** 上：`onNoUpdate` 是 `companion` 里的纯函数，
 * 不需要 Application / 网络 / 设备 —— 这正是把它抽出来的理由。
 */
class UpdateFlowTest {

    /* ─────────── 无更新时的状态决策 ─────────── */

    @Test
    fun `手动检查且已是最新 → 走 Latest，而不是失败`() {
        assertEquals(
            UpdateViewModel.State.Latest,
            UpdateViewModel.onNoUpdate(manual = true),
        )
    }

    @Test
    fun `启动时静默检查且已是最新 → 什么都不显示`() {
        assertEquals(
            UpdateViewModel.State.Idle,
            UpdateViewModel.onNoUpdate(manual = false),
        )
    }

    @Test
    fun `Latest 绝不能是 Failed —— 本条是"弹窗自相矛盾"的修复核心`() {
        val s = UpdateViewModel.onNoUpdate(manual = true)
        assertFalse(
            "已是最新若被当成失败，界面就会渲染成「更新没有完成」+「重试/退出应用」",
            s is UpdateViewModel.State.Failed,
        )
    }

    /* ─────────── Failed 的可重试信息（修"重试按钮是死的"） ─────────── */

    private fun sampleRelease() = RemoteRelease(
        versionCode = 99,
        versionName = "9.9.9",
        notes = "测试用",
        force = false,
        minSupportedCode = 0,
        apkUrl = "/d/yuki-99.apk?h=deadbeef",
        apkSize = 1024L,
    )

    @Test
    fun `无下载地址这类失败不可重试（retryable 为 null）`() {
        assertNull(
            UpdateViewModel.State.Failed("这个版本没有提供下载地址").retryable,
        )
    }

    @Test
    fun `下载失败带上版本信息，重试才有东西可重来`() {
        val r = sampleRelease()
        val f = UpdateViewModel.State.Failed("网络断了", retryable = r)
        assertEquals(r, f.retryable)
        assertTrue(f.retryable != null)
    }
}
