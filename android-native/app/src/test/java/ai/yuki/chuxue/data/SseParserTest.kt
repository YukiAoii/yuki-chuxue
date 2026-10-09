package ai.yuki.chuxue.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SSE 解析器的规格测试（开发文档 §9 / §14）。
 *
 * 这是整条流式链路上**唯一能在无设备环境验证**的部分 —— 所以它必须被穷举，
 * 尤其是「坏行不能炸掉整段回复」这条容错纪律。
 */
class SseParserTest {

    /* ─────────── 正常路径 ─────────── */

    @Test
    fun `普通 delta 行解析出正文增量`() {
        val line = """data: {"choices":[{"delta":{"content":"你"},"index":0}]}"""
        val chunk = SseParser.parseLine(line)
        assertEquals(SseChunk.Delta("你"), chunk)
    }

    @Test
    fun `delta 为空对象时不算增量`() {
        val line = """data: {"choices":[{"delta":{},"index":0}]}"""
        assertEquals(SseChunk.Ignore, SseParser.parseLine(line))
    }

    @Test
    fun `最后一块带 finish_reason 时判定结束`() {
        val line = """data: {"choices":[{"delta":{},"finish_reason":"stop"}]}"""
        val chunk = SseParser.parseLine(line)
        assertTrue("应当判为 Done，实际 $chunk", chunk is SseChunk.Done)
    }

    @Test
    fun `DONE 标记判定结束`() {
        assertEquals(SseChunk.Done(null), SseParser.parseLine("data: [DONE]"))
    }

    @Test
    fun `最后一块带 usage 时解析出缓存统计`() {
        val line = """data: {"choices":[{"delta":{},"finish_reason":"stop"}],""" +
            """"usage":{"prompt_cache_hit_tokens":1024,"prompt_cache_miss_tokens":64,"prompt_tokens":1088}}"""
        val chunk = SseParser.parseLine(line)
        assertTrue(chunk is SseChunk.Done)
        val cache = (chunk as SseChunk.Done).cache
        assertNotNull("usage 存在时应给出 CacheStats", cache)
        assertEquals(1024, cache!!.hitTokens)
        assertEquals(64, cache.missTokens)
        assertEquals(1088, cache.inputTokens)
    }

    /* ─────────── 容错纪律：坏行不炸 ─────────── */

    @Test
    fun `空行与纯空白行被忽略`() {
        assertEquals(SseChunk.Ignore, SseParser.parseLine(""))
        assertEquals(SseChunk.Ignore, SseParser.parseLine("   "))
        assertEquals(SseChunk.Ignore, SseParser.parseLine("\r"))
    }

    @Test
    fun `非 data 行被忽略`() {
        // SSE 注释 / keep-alive：OpenAI 兼容服务会定期发 ": keep-alive"
        assertEquals(SseChunk.Ignore, SseParser.parseLine(": keep-alive"))
        assertEquals(SseChunk.Ignore, SseParser.parseLine("event: message"))
        assertEquals(SseChunk.Ignore, SseParser.parseLine("id: 42"))
    }

    @Test
    fun `data 后为空被忽略`() {
        assertEquals(SseChunk.Ignore, SseParser.parseLine("data:"))
        assertEquals(SseChunk.Ignore, SseParser.parseLine("data:   "))
    }

    @Test
    fun `非法 JSON 不抛异常_只忽略这一行`() {
        assertEquals(SseChunk.Ignore, SseParser.parseLine("data: {不是JSON"))
        assertEquals(SseChunk.Ignore, SseParser.parseLine("data: <html>502 Bad Gateway</html>"))
        assertEquals(SseChunk.Ignore, SseParser.parseLine("data: null"))
    }

    @Test
    fun `结构缺失的合法 JSON 被忽略`() {
        // 没有 choices 字段
        assertEquals(SseChunk.Ignore, SseParser.parseLine("""data: {"id":"abc"}"""))
        // choices 为空数组
        assertEquals(SseChunk.Ignore, SseParser.parseLine("""data: {"choices":[]}"""))
    }

    @Test
    fun `Windows 换行残留的 CR 不影响解析`() {
        val line = "data: {\"choices\":[{\"delta\":{\"content\":\"好\"}}]}\r"
        assertEquals(SseChunk.Delta("好"), SseParser.parseLine(line))
    }

    /* ─────────── 流式拼接（模拟一整条流） ─────────── */

    @Test
    fun `完整流的增量按序拼接成全文`() {
        val stream = listOf(
            ": keep-alive",
            """data: {"choices":[{"delta":{"content":"初"}}]}""",
            "",
            """data: {"choices":[{"delta":{"content":"雪"}}]}""",
            """data: {"choices":[{"delta":{"content":"，你好"}}]}""",
            """data: {"choices":[{"delta":{},"finish_reason":"stop"}]}""",
            "data: [DONE]",
        )

        val sb = StringBuilder()
        var doneCount = 0
        stream.forEach { line ->
            when (val c = SseParser.parseLine(line)) {
                is SseChunk.Delta -> sb.append(c.text)
                is SseChunk.Done -> doneCount++
                // 本用例只关心正文与结束标记；思考过程有专门的用例覆盖
                is SseChunk.Reasoning -> Unit
                SseChunk.Ignore -> Unit
            }
        }

        assertEquals("初雪，你好", sb.toString())
        assertEquals("finish_reason 与 [DONE] 各触发一次结束", 2, doneCount)
    }

    @Test
    fun `中间插一条坏行不影响后续增量`() {
        val stream = listOf(
            """data: {"choices":[{"delta":{"content":"前"}}]}""",
            "data: {坏 JSON",
            """data: {"choices":[{"delta":{"content":"后"}}]}""",
        )
        val text = stream.mapNotNull { (SseParser.parseLine(it) as? SseChunk.Delta)?.text }
            .joinToString("")
        assertEquals("前后", text)
    }

    /* ══════════════ 思考过程（reasoning_content）══════════════ */

    @Test
    fun `解析思考内容增量`() {
        val line = """data: {"choices":[{"delta":{"reasoning_content":"让我想想"}}]}"""
        assertEquals(SseChunk.Reasoning("让我想想"), SseParser.parseLine(line))
    }

    @Test
    fun `同一块里同时有思考与正文时以正文为准 —— 思考不能吃掉回答`() {
        val line = """data: {"choices":[{"delta":{"reasoning_content":"想完了","content":"你好"}}]}"""
        assertEquals(SseChunk.Delta("你好"), SseParser.parseLine(line))
    }

    @Test
    fun `正文为空而只有思考时仍产出思考增量`() {
        val line = """data: {"choices":[{"delta":{"content":"","reasoning_content":"嗯……"}}]}"""
        assertEquals(SseChunk.Reasoning("嗯……"), SseParser.parseLine(line))
    }

    @Test
    fun `空思考增量被忽略`() {
        val line = """data: {"choices":[{"delta":{"reasoning_content":""}}]}"""
        assertEquals(SseChunk.Ignore, SseParser.parseLine(line))
    }

    @Test
    fun `usage 里解析出输出 tokens`() {
        val line = """data: {"choices":[{"finish_reason":"stop"}],"usage":{"prompt_tokens":100,"completion_tokens":42,"prompt_cache_hit_tokens":80,"prompt_cache_miss_tokens":20}}"""
        val chunk = SseParser.parseLine(line) as SseChunk.Done
        assertEquals(42, chunk.cache?.outputTokens)
        assertEquals(100, chunk.cache?.inputTokens)
    }

    @Test
    fun `一整段思考流能被完整拼接`() {
        val stream = listOf(
            """data: {"choices":[{"delta":{"reasoning_content":"先看看"}}]}""",
            """data: {"choices":[{"delta":{"reasoning_content":"再想想"}}]}""",
            """data: {"choices":[{"delta":{"content":"答案是"}}]}""",
            """data: {"choices":[{"delta":{"content":"42"}}]}""",
        )
        val (reasoning, text) = stream.map { SseParser.parseLine(it) }
            .fold("" to "") { acc, chunk ->
                when (chunk) {
                    is SseChunk.Reasoning -> (acc.first + chunk.text) to acc.second
                    is SseChunk.Delta -> acc.first to (acc.second + chunk.text)
                    else -> acc
                }
            }
        assertEquals("先看看再想想", reasoning)
        assertEquals("答案是42", text)
    }
}
