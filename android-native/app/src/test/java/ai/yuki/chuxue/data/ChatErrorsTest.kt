package ai.yuki.chuxue.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * 错误码 → 她说的话（H4）。
 *
 * 为什么这些文案值得逐条钉住：它们是**用户看到的第一句话**（对话因此没有断在半空），
 * 而分档一旦错位，用户会被引去做无关的自查 —— 明明是余额不足却让他去查网络，
 * 那比不报错更糟。
 */
class ChatErrorsTest {

    @Test
    fun `402 说她自己的余额不够，技术摘要原样带出`() {
        val line = ChatErrors.forStatus(402, "余额不足（402）：请为 DeepSeek 账户充值。")
        assertTrue("应提到余额：${line.spoken}", line.spoken.contains("余额"))
        // 摘要不加工 —— 用户要自查时，原样的上游信息才有用
        assertEquals("余额不足（402）：请为 DeepSeek 账户充值。", line.detail)
    }

    @Test
    fun `403 但上游说的是模型没权限 —— 不能再让用户去翻 Key`() {
        // 实测原话（2026-10-03）：`This token has no access to model deepseek-flash［官方］`
        // 那时钥匙是好的，提示却让用户去查 API Key —— 他反复检查也查不出问题。
        val line = ChatErrors.forStatus(
            403,
            "认证失败（403）：API Key 无效。This token has no access to model deepseek-flash［官方］",
        )
        assertTrue("该指向模型：${line.spoken}", line.spoken.contains("模型"))
        assertTrue("不该再说钥匙：${line.spoken}", !line.spoken.contains("钥匙"))
    }

    @Test
    fun `401 与 403 同档，都指向 API Key`() {        val a = ChatErrors.forStatus(401, "")
        val b = ChatErrors.forStatus(403, "")
        assertEquals(a.spoken, b.spoken)
        assertTrue("应提到 Key：${a.spoken}", a.spoken.contains("API Key"))
    }

    @Test
    fun `429 与 402 不同档 —— 一个要等、一个要充值`() {
        val busy = ChatErrors.forStatus(429, "")
        val poor = ChatErrors.forStatus(402, "")
        assertNotEquals(poor.spoken, busy.spoken)
        assertTrue("应提到频繁：${busy.spoken}", busy.spoken.contains("频繁"))
    }

    @Test
    fun `503 与 500 分档：一个是忙、一个是故障`() {
        val busy = ChatErrors.forStatus(503, "")
        val broken = ChatErrors.forStatus(500, "")
        assertNotEquals(broken.spoken, busy.spoken)
    }

    @Test
    fun `未列举的状态码兜底也要带上码`() {
        val line = ChatErrors.forStatus(418, "")
        assertTrue("应带上状态码：${line.spoken}", line.spoken.contains("418"))
    }

    @Test
    fun `没单列的 5xx 归入服务异常那一档`() {
        assertEquals(
            ChatErrors.forStatus(500, "x").spoken,
            ChatErrors.forStatus(599, "x").spoken,
        )
    }

    @Test
    fun `连接层失败指向网络与地址，而不是账户`() {
        val line = ChatErrors.forNetwork("连接失败：Connection refused")
        assertTrue("应提到网络：${line.spoken}", line.spoken.contains("网络"))
        assertEquals("连接失败：Connection refused", line.detail)
    }

    @Test
    fun `首字超时把秒数说出来`() {
        val line = ChatErrors.forFirstByteTimeout(30)
        assertTrue("应带上 30 秒：${line.spoken}", line.spoken.contains("30"))
    }

    @Test
    fun `forException 按有没有状态码分流`() {
        val withCode = ChatErrors.forException(ApiHttpException(402, "余额不足"))
        assertTrue("有码走状态码档：${withCode.spoken}", withCode.spoken.contains("余额"))

        val plain = ChatErrors.forException(IOException("连接失败：timeout"))
        assertTrue("无码走连接档：${plain.spoken}", plain.spoken.contains("网络"))
    }

    @Test
    fun `异常没有 message 时摘要回落到异常类名，不留空`() {
        val line = ChatErrors.forException(IOException())
        assertTrue("摘要不该为空", line.detail.isNotBlank())
        assertTrue("应回落到类名：'${line.detail}'", line.detail.contains("IOException"))
    }

    @Test
    fun `ApiHttpException 仍是 IOException —— 既有的 catch 语义不变`() {
        val e: IOException = ApiHttpException(401, "x")
        assertTrue(e is IOException)
        assertEquals(401, (e as ApiHttpException).status)
    }

    @Test
    fun `每条说法都不为空、且不含 Markdown 记号`() {
        // Compose 的 Text 不解析 Markdown —— 文案里写 **加粗** 会被原样画出来
        //（本项目为此修过 5 处）。这里一次性把全部档位扫一遍。
        val all = listOf(400, 401, 402, 403, 404, 422, 429, 500, 503, 599, 418).map {
            ChatErrors.forStatus(it, "d")
        } + ChatErrors.forNetwork("d") + ChatErrors.forFirstByteTimeout(30)

        all.forEach { line ->
            assertTrue("说法不该为空：$line", line.spoken.isNotBlank())
            assertTrue("不该含 Markdown 记号：${line.spoken}", !line.spoken.contains("**"))
        }
    }


    /* ─────────── 空回复（v0.50.x 修「发了消息、她完全不回复」） ─────────── */

    @Test
    fun `只思考没正文时，指向思考强度与长度上限`() {
        val line = ChatErrors.forEmptyReply(sawReasoning = true, detail = "content 为空；推理 2317 字")
        assertTrue("应提到思考：${line.spoken}", line.spoken.contains("思考"))
        assertTrue("应给出可操作项（长度上限）：${line.spoken}", line.spoken.contains("长度"))
        // 技术摘要原样带出 —— 含推理字数，方便对齐"是不是思考把预算吃光了"
        assertEquals("content 为空；推理 2317 字", line.detail)
    }

    @Test
    fun `连思考都没有时只说重发 —— 不要误指思考强度`() {
        val line = ChatErrors.forEmptyReply(sawReasoning = false, detail = "content 为空；推理 0 字")
        assertTrue("不该提思考：${line.spoken}", !line.spoken.contains("思考"))
        assertTrue("应建议重试：${line.spoken}", line.spoken.contains("再发"))
    }

}
