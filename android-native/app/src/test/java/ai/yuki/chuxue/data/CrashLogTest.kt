package ai.yuki.chuxue.data

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 崩溃日志**格式**的测试（v0.53.0）。
 *
 * ⚠️ 崩溃处理器本身在单测里跑不了（要真崩一次），但"日志长什么样"必须钉住 ——
 * 它恰恰是最容易悄悄坏掉的部分：等到用户真的崩了、把日志发过来，
 * 才发现里面没有版本号或没有根因，那这次崩溃就白崩了。
 *
 * 这也是 `buildText` 被写成**纯函数**（环境信息由参数传入、不读 `android.os.Build`）的原因：
 * 在 JVM 上读 `Build` 只会拿到 stub 的 null，"看着通过、其实什么都没验"。
 */
class CrashLogTest {

    private fun text(t: Throwable) = CrashLog.buildText(
        t = t,
        at = 1_700_000_000_000L,
        appVersion = "0.53.0 (78)",
        system = "Android 14 (API 34)",
        device = "Xiaomi 2312DRA50C",
    )

    @Test
    fun `排查要用的那几样都得在`() {
        val s = text(IllegalStateException("连不上服务器"))
        assertTrue("版本要在", s.contains("0.53.0 (78)"))
        assertTrue("系统要在", s.contains("Android 14 (API 34)"))
        assertTrue("机型要在", s.contains("Xiaomi 2312DRA50C"))
        assertTrue("异常类型要在", s.contains("IllegalStateException"))
        assertTrue("异常消息要在", s.contains("连不上服务器"))
    }

    @Test
    fun `堆栈要完整 —— 含 caused by 链`() {
        val inner = IllegalArgumentException("根因在这")
        val outer = RuntimeException("外层", inner)
        val s = text(outer)
        assertTrue("外层异常要在", s.contains("RuntimeException"))
        assertTrue("根因也要在，否则排查只能看到一半", s.contains("Caused by"))
        assertTrue(s.contains("根因在这"))
        assertTrue("要有真实栈帧", s.contains("CrashLogTest"))
    }

    @Test
    fun `没有 message 的异常也照样出日志`() {
        val s = text(RuntimeException())
        assertTrue(s.contains("RuntimeException"))
        assertTrue("空 message 不该让日志少一块（否则看起来像格式坏了）", s.contains("消息:"))
    }
}
