package ai.yuki.chuxue.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「Ta 的状态」上报器的**纯函数**契约（v0.61.34）。
 *
 * 网络本身没法单测（与 `AuthApi` / `Telemetry` 同一条纪律：把请求体与 URL
 * 抽成纯函数，在 JVM 上钉死字段名与格式），所以这里钉的就是这两件事。
 */
class XinchaoReportTest {

    @Test
    fun `URL 拼装 —— 正常与空参`() {
        assertEquals(
            "https://example.com:11445/xinchao/personas/abc123/register",
            XinchaoReport.registerUrl("https://example.com:11445", "abc123"),
        )
        // 尾斜杠要被吃掉（trimEnd），不能拼出双斜杠
        assertEquals(
            "https://example.com:11445/xinchao/personas/abc123/event",
            XinchaoReport.eventUrl("https://example.com:11445/", "abc123"),
        )
        // 空 base / 空 personaId → 空串（调用方据此直接跳过，不打无效请求）
        assertEquals("", XinchaoReport.registerUrl("", "abc"))
        assertEquals("", XinchaoReport.eventUrl("https://x", ""))
    }

    @Test
    fun `register 体 —— name 字段`() {
        assertEquals("""{"name":"小满"}""", XinchaoReport.registerBody("小满"))
    }

    @Test
    fun `event 体 —— 引擎契约格式（她说…他回…）与字段名`() {
        val body = XinchaoReport.eventBody(
            eventId = "e1",
            userText = "今天好累",
            assistantText = "抱抱你",
            atIso = "2026-10-06T02:00:00Z",
        )
        assertTrue("缺 eventId 字段", body.contains("\"eventId\":\"e1\""))
        assertTrue("缺 at 字段", body.contains("\"at\":\"2026-10-06T02:00:00Z\""))
        // ⚠️ 这两个标签是引擎的解析契约（interaction-rules.js 的 splitExchange），
        //    不是用户文案 —— 改了它引擎就切不出双方发言（实测不带标签时 interaction 不 applied）。
        assertTrue("exchange 缺「她说：」标签", body.contains("她说：今天好累"))
        assertTrue("exchange 缺「他回：」标签", body.contains("他回：抱抱你"))
    }
}
