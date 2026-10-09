package ai.yuki.chuxue.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「发验证码到底成没成」的解析规格。
 *
 * ## 它修的是什么
 * 后端**本来就分得清**三种结局（见 `YukiServer/main.py` 的 `_send_code`）：
 * ```
 * {"ok":true,  "sent":true,  "degraded":false}                    ← 真发出去了
 * {"ok":true,  "sent":false, "degraded":true,  "message":"邮件服务尚未配置…"}
 * {"ok":false, "sent":false, "degraded":true,  "message":"SMTP …"} ← 发失败了
 * ```
 * 但客户端把这个 `sent` **压成了一句中文**，布尔被丢掉 —— 于是界面无从判断
 * "到底发没发出去"，只能按"HTTP 200 就算成功"处理，倒计时照走。
 * 用户 2026-10-05 报的正是这个：**点了、邮件没来，可按钮已经在读秒了**。
 *
 * ## 判据
 * 「真发了」由后端的 `sent` 说了算；**其余一律不算**（包括邮件服务没配、发送失败、
 * 响应坏掉）。保守方向是刻意的：**宁可不让读秒**，也不要让用户对着读秒按钮等一封不来的邮件。
 */
class AuthApiSendCodeTest {

    @Test
    fun `后端说真发了 —— 这才是"成了"`() {
        val r = AuthApi.parseSendCode("""{"ok":true,"sent":true,"degraded":false}""")
        assertTrue("sent 应当为 true", r.sent)
        assertTrue("要给一句人话：${r.message}", r.message.isNotBlank())
    }

    @Test
    fun `邮件服务没配 —— ok 是 true 但 sent 是 false，不算发出去`() {
        val r = AuthApi.parseSendCode(
            """{"ok":true,"sent":false,"degraded":true,"message":"邮件服务尚未配置。验证码已生成，请到管理后台「邮件服务」页查看。"}""",
        )
        assertFalse("邮件没配就不许开始读秒", r.sent)
        assertTrue("后端那句话要照实转达：${r.message}", r.message.contains("邮件服务"))
    }

    @Test
    fun `发送失败 —— ok 是 false，不算发出去`() {
        val r = AuthApi.parseSendCode("""{"ok":false,"sent":false,"degraded":true,"message":"SMTP 连不上"}""")
        assertFalse(r.sent)
        assertTrue("失败原因要照实说：${r.message}", r.message.contains("SMTP"))
    }

    @Test
    fun `缺少 sent 字段 —— 按没发出去处理`() {
        assertFalse(AuthApi.parseSendCode("""{"ok":true}""").sent)
    }

    @Test
    fun `坏 JSON —— 按没发出去处理，且不给一句空话`() {
        val r = AuthApi.parseSendCode("这不是 JSON")
        assertFalse(r.sent)
        assertTrue("兜底也要有话说：${r.message}", r.message.isNotBlank())
    }

    @Test
    fun `响应为空 —— 同样按没发出去处理`() {
        assertFalse(AuthApi.parseSendCode("").sent)
    }
}
