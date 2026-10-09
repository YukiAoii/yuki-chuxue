package ai.yuki.chuxue.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 使用统计上报的规格。
 *
 * ## 这个测试在守什么
 * 上报是"把数据发出去"的行为，所以它的正确性有两层：
 *
 * 1. **字段名必须与服务端对齐** —— 改了名字服务端就收不到，而失败是**静默**的
 *    （见 `Telemetry.report`），所以不会有任何报错提示你 —— 只有仪表盘悄悄变成空的。
 *    这类"静默失效"只能靠断言字段名挡住。
 * 2. **不能出现用户内容** —— [Telemetry.Snapshot] 的字段必须全是计数与系统属性。
 *    下面有一条断言在守这个：一旦有人往里加"会话标题"这类字段，它会红。
 */
class TelemetryTest {

    private val sample = Telemetry.Snapshot(
        deviceId = "dev-abc",
        model = "Pixel 8",
        androidVersion = "14",
        appVersion = "0.21.0",
        personaCount = 2,
        sessionCount = 5,
        messageCount = 42,
        memoryCount = 3,
        hitTokens = 900,
        missTokens = 100,
    )

    /* ══════════════ 端点拼接 ══════════════ */

    @Test
    fun `普通地址拼出上报端点`() {
        assertEquals("https://example.com/yuki/api/app/report", Telemetry.reportUrl("https://example.com/yuki"))
    }

    @Test
    fun `末尾多余的斜杠会被去掉 —— 少打或多打一个都不该影响上报`() {
        assertEquals(
            Telemetry.reportUrl("https://example.com/yuki"),
            Telemetry.reportUrl("https://example.com/yuki/"),
        )
        assertEquals(
            Telemetry.reportUrl("https://example.com/yuki"),
            Telemetry.reportUrl("https://example.com/yuki///"),
        )
    }

    @Test
    fun `前后空白被忽略`() {
        assertEquals(
            "http://127.0.0.1:11445/api/app/report",
            Telemetry.reportUrl("  http://127.0.0.1:11445  "),
        )
    }

    @Test
    fun `空地址返回空串 —— 调用方据此一行网络都不发`() {
        assertEquals("", Telemetry.reportUrl(""))
        assertEquals("", Telemetry.reportUrl("   "))
        assertEquals("", Telemetry.reportUrl("/"))
    }

    /* ══════════════ 请求体 ══════════════ */

    @Test
    fun `字段名与服务端一致 —— 改名会让仪表盘静默变空`() {
        val json = Telemetry.toJson(sample)
        listOf(
            "device_id", "model", "android_version", "app_version",
            "persona_count", "session_count", "message_count", "memory_count",
            "hit_tokens", "miss_tokens",
        ).forEach { key ->
            assertTrue("缺少字段 $key：$json", json.contains("\"$key\""))
        }
    }

    @Test
    fun `数值字段是数字而不是字符串`() {
        val json = Telemetry.toJson(sample)
        assertTrue("session_count 应当是数字：$json", json.contains("\"session_count\":5"))
        assertTrue("hit_tokens 应当是数字：$json", json.contains("\"hit_tokens\":900"))
    }

    @Test
    fun `零值也要上报 —— 否则服务端无法区分`() {
        val zeros = Telemetry.Snapshot(deviceId = "d")
        val json = Telemetry.toJson(zeros)
        assertTrue(json.contains("\"persona_count\":0"))
        assertTrue(json.contains("\"message_count\":0"))
    }

    @Test
    fun `只上报系统属性与计数 —— 没有任何用户内容的字段`() {
        val json = Telemetry.toJson(sample)
        // 这条是"性质守卫"：Snapshot 的字段全部来自系统与计数，
        // 因此下面的词一个都不该出现。若将来有人加了会话标题/人设文本，这里会红。
        listOf("title", "content", "prompt", "nickname", "persona_text", "message_text", "api_key")
            .forEach { forbidden ->
                assertFalse("上报体里不该出现 $forbidden：$json", json.contains(forbidden))
            }
    }

    @Test
    fun `device_id 是必填项且会进入请求体`() {
        val json = Telemetry.toJson(Telemetry.Snapshot(deviceId = "abc-123"))
        assertTrue(json.contains("\"device_id\":\"abc-123\""))
    }

    /* ══════════════ 登录身份（v0.61.23：让后台把设备与账号对上）══════════════ */

    @Test
    fun `带登录身份时 token 进入请求体 —— 后台据此把设备关联到账号`() {
        val json = Telemetry.toJson(sample.copy(token = "tok-abc"))
        assertTrue("缺 token 字段：$json", json.contains("\"token\":\"tok-abc\""))
    }

    @Test
    fun `未登录时 token 是空串 —— 服务端按匿名设备收（老行为不变）`() {
        val json = Telemetry.toJson(Telemetry.Snapshot(deviceId = "d"))
        assertTrue("token 应始终存在（空串），免得服务端区分「有没有这个字段」：$json",
            json.contains("\"token\":\"\""))
    }

    /* ══════════════ 错峰（v0.61.23：防万人同时上报打挂服务器）══════════════ */

    @Test
    fun `错峰延迟落在约定区间内 —— 多次采样都不许越界`() {
        repeat(200) {
            val d = Telemetry.staggerDelayMs()
            assertTrue(
                "延迟 $d 越界（应在 ${Telemetry.STAGGER_MIN_MS}..${Telemetry.STAGGER_MAX_MS}）",
                d >= Telemetry.STAGGER_MIN_MS && d <= Telemetry.STAGGER_MAX_MS,
            )
        }
    }
}
