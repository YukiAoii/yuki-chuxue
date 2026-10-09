package ai.yuki.chuxue.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **缓存统计不能丢**（v0.46.3）。
 *
 * 用户报"命中率只降不升"报了两次。第一次的真因是 `[DONE]` 块把 usage 覆盖成 null；
 * **第二次是这里**：`when` 的分支顺序让「正文」优先 ——
 * 而 DeepSeek 的**最后一块常常同时含 delta 和 usage**，于是 usage 被直接丢弃，
 * 统计永远累加不上。
 *
 * 这组断言钉的是"**只要块里有 usage，就必须被带出来**"，
 * 不管它和正文/思考是不是同块。
 */
class SseUsageTest {

    private val usageLine = """
        data: {"choices":[{"delta":{"content":"好"},"finish_reason":"stop"}],
               "usage":{"prompt_tokens":100,"completion_tokens":20,
                        "prompt_cache_hit_tokens":80,"prompt_cache_miss_tokens":20}}
    """.trimIndent().replace("\n", "")

    @Test
    fun `正文与 usage 同块时，两边都要带出来 —— 这是还在降的真因`() {
        val chunk = SseParser.parseLine(usageLine)
        assertTrue("这一块含正文，应走 Delta：$chunk", chunk is SseChunk.Delta)
        val d = chunk as SseChunk.Delta
        assertEquals("好", d.text)
        val c = d.usage
        assertTrue("⚠️ usage 被丢了 —— 统计永远累加不上", c != null)
        assertEquals(80, c!!.hitTokens)
        assertEquals(20, c.missTokens)
    }

    @Test
    fun `只有 usage 没有正文时，仍走 Done（老路径不能破）`() {
        val line = """data: {"choices":[{"finish_reason":"stop"}],""" +
            """"usage":{"prompt_tokens":10,"completion_tokens":1,""" +
            """"prompt_cache_hit_tokens":9,"prompt_cache_miss_tokens":1}}"""
        val chunk = SseParser.parseLine(line)
        assertTrue("应走 Done：$chunk", chunk is SseChunk.Done)
        assertEquals(9, (chunk as SseChunk.Done).cache!!.hitTokens)
    }

    @Test
    fun `没有 usage 的普通正文块不受影响`() {
        val chunk = SseParser.parseLine("""data: {"choices":[{"delta":{"content":"你好"}}]}""")
        assertTrue(chunk is SseChunk.Delta)
        assertEquals(null, (chunk as SseChunk.Delta).usage)
    }
}
