package ai.yuki.chuxue.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * DeepSeekClient 的可测纯函数测试。
 *
 * 为什么这两块值得测：
 *   1. 端点规范化 —— 用户填法五花八门（带不带协议、带不带 /v1、带不带尾斜杠），
 *      规范化错了会一路 404 却看不出原因。
 *   2. 模型列表解析 —— 依赖上游返回格式，格式异常时必须**抛错**而不是静默返回空，
 *      否则用户会以为是「没有可用模型」而不是「解析失败」。
 */
class DeepSeekClientTest {

    private val client = DeepSeekClient()

    /* ───────────────── 端点规范化 ───────────────── */

    @Test
    fun `只填域名时自动补协议与版本段`() {
        assertEquals(
            "https://api.deepseek.com/v1",
            DeepSeekClient.normalizeBaseUrl("api.deepseek.com"),
        )
    }

    @Test
    fun `已含版本段时不重复添加`() {
        assertEquals(
            "https://api.deepseek.com/v1",
            DeepSeekClient.normalizeBaseUrl("https://api.deepseek.com/v1"),
        )
    }

    @Test
    fun `去掉尾部斜杠`() {
        assertEquals(
            "https://api.deepseek.com/v1",
            DeepSeekClient.normalizeBaseUrl("https://api.deepseek.com/v1/"),
        )
    }

    @Test
    fun `保留自定义路径前缀`() {
        assertEquals(
            "https://gw.example.com/openai/v1",
            DeepSeekClient.normalizeBaseUrl("https://gw.example.com/openai"),
        )
    }

    @Test
    fun `空输入回落到默认端点`() {
        assertEquals(DEFAULT_BASE_URL, DeepSeekClient.normalizeBaseUrl("   "))
    }

    @Test
    fun `http 协议被保留（本地推理常用）`() {
        assertEquals(
            "http://localhost:11434/v1",
            DeepSeekClient.normalizeBaseUrl("http://localhost:11434"),
        )
    }

    @Test
    fun `前后空白被裁剪`() {
        assertEquals(
            "https://api.deepseek.com/v1",
            DeepSeekClient.normalizeBaseUrl("  https://api.deepseek.com  "),
        )
    }

    /* ───────────────── 模型列表解析 ───────────────── */

    @Test
    fun `解析 OpenAI 兼容的模型列表`() {
        val raw = """{"object":"list","data":[{"id":"deepseek-chat"},{"id":"deepseek-reasoner"}]}"""
        assertEquals(
            listOf("deepseek-chat", "deepseek-reasoner"),
            client.parseModelList(raw),
        )
    }

    @Test
    fun `结果排序，保证展示顺序稳定`() {
        val raw = """{"data":[{"id":"zzz"},{"id":"aaa"}]}"""
        assertEquals(listOf("aaa", "zzz"), client.parseModelList(raw))
    }

    @Test
    fun `忽略缺少 id 的条目`() {
        val raw = """{"data":[{"id":"ok"},{"object":"model"}]}"""
        assertEquals(listOf("ok"), client.parseModelList(raw))
    }

    @Test
    fun `缺少 data 字段时抛出可读错误`() {
        val e = runCatching { client.parseModelList("""{"object":"list"}""") }.exceptionOrNull()
        assertTrue("应为 IOException", e is IOException)
        assertTrue("错误信息应提到 data：${e?.message}", e!!.message!!.contains("data"))
    }

    @Test
    fun `非法 JSON 抛出可读错误`() {
        val e = runCatching { client.parseModelList("<html>502</html>") }.exceptionOrNull()
        assertTrue(e is IOException)
        assertTrue(e!!.message!!.contains("合法 JSON"))
    }

    @Test
    fun `data 为空数组时抛错，避免静默成功误导用户`() {
        val e = runCatching { client.parseModelList("""{"data":[]}""") }.exceptionOrNull()
        assertTrue(e is IOException)
        assertTrue(e!!.message!!.contains("为空"))
    }
}
