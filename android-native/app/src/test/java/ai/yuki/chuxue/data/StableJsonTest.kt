package ai.yuki.chuxue.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 稳定序列化的字节一致性测试。
 *
 * 这些断言不是「跑通不报错」式的形式测试 —— 它们断言的是**字节级稳定性**，
 * 也就是 DeepSeek 前缀缓存能否命中的充要条件。
 */
class StableJsonTest {

    @Test
    fun `key 顺序不同的等价 Map 产出完全相同的字节`() {
        val a = linkedMapOf<String, Any?>("b" to 1, "a" to 2)
        val b = linkedMapOf<String, Any?>("a" to 2, "b" to 1)
        assertEquals(StableJson.encode(a), StableJson.encode(b))
    }

    @Test
    fun `嵌套层也逐级排序`() {
        val nested = linkedMapOf<String, Any?>(
            "x" to linkedMapOf<String, Any?>("z" to 1, "y" to 2),
        )
        assertEquals("""{"x":{"y":2,"z":1}}""", StableJson.encode(nested))
    }

    @Test
    fun `数组保持原序（顺序有语义，不能排序）`() {
        assertEquals("[3,1,2]", StableJson.encode(listOf(3, 1, 2)))
    }

    @Test
    fun `剔除 null 字段，避免有 key 与无 key 的字节抖动`() {
        val withNull = linkedMapOf<String, Any?>("a" to 1, "b" to null)
        assertEquals("""{"a":1}""", StableJson.encode(withNull))
    }

    @Test
    fun `中文原样保留，不被转义成 uXXXX`() {
        assertEquals("""{"s":"初雪"}""", StableJson.encode(linkedMapOf<String, Any?>("s" to "初雪")))
    }

    @Test
    fun `同样的输入永远产出同样的字节（多次调用）`() {
        val msg = linkedMapOf<String, Any?>(
            "role" to "user",
            "content" to "你好，今天过得怎么样？",
        )
        val first = StableJson.encode(msg)
        repeat(5) { assertEquals(first, StableJson.encode(msg)) }
    }

    @Test
    fun `整数值的 Double 不输出小数点`() {
        assertEquals("""{"n":1}""", StableJson.encode(linkedMapOf<String, Any?>("n" to 1.0)))
        assertEquals("""{"n":1.5}""", StableJson.encode(linkedMapOf<String, Any?>("n" to 1.5)))
    }

    @Test
    fun `请求体形状：messages 数组的字节稳定`() {
        fun build(messages: List<Map<String, Any?>>) = StableJson.encode(
            linkedMapOf<String, Any?>(
                "model" to "deepseek-chat",
                "messages" to messages,
                "temperature" to 0.9,
            ),
        )

        val m1 = listOf(
            mapOf("role" to "system", "content" to "你是初雪"),
            mapOf("role" to "user", "content" to "在吗"),
        )
        val m2 = listOf(
            mapOf("content" to "你是初雪", "role" to "system"), // key 顺序打乱
            mapOf("content" to "在吗", "role" to "user"),
        )
        assertEquals(build(m1), build(m2))
    }

    @Test
    fun `内容确实变化时字节必须不同（否则说明序列化吃掉了差异）`() {
        val a = StableJson.encode(linkedMapOf<String, Any?>("content" to "第一句"))
        val b = StableJson.encode(linkedMapOf<String, Any?>("content" to "第二句"))
        assertNotEquals(a, b)
    }

    @Test
    fun `特殊字符正确转义且确定`() {
        val s = "换行\n引号\"反斜杠\\"
        val once = StableJson.encode(linkedMapOf<String, Any?>("c" to s))
        val twice = StableJson.encode(linkedMapOf<String, Any?>("c" to s))
        assertEquals(once, twice)
        assertEquals("""{"c":"换行\n引号\"反斜杠\\"}""", once)
    }
}
