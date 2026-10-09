package ai.yuki.chuxue.data

/**
 * 确定性 JSON 序列化 —— 每一层都排序 key。
 *
 * ══ 为什么这个文件是整个缓存优化的地基 ══
 * DeepSeek 官方 Context Caching 文档原文：
 *   "A subsequent request can only hit the cache if it fully matches
 *    a cache prefix unit."
 * 官方 Example 2 更直接：第一轮 A+B、第二轮 A+C —— 第二轮**不命中**。
 *
 * 而 JSON 的 key 顺序会改变字节。语义完全相同的对象，如果序列化出的字节不同，
 * 前缀就不匹配，缓存直接失效。这是最容易被忽略的一层：
 * 你以为「内容没变」，但字节变了。
 *
 * 实现要点：
 *   1. 对象每层按 key 排序
 *   2. 剔除 null / 缺省字段，避免「有 key」与「无 key」的字节抖动
 *   3. 数组保持原序（顺序有语义，不能排序）
 *   4. 中文原样输出，不做 \\uXXXX 转义（转义方式变化同样会破坏前缀）
 */
object StableJson {

    fun encode(value: Any?): String = when (value) {
        null -> "null"
        is String -> quote(value)
        is Boolean -> value.toString()
        is Int -> value.toString()
        is Long -> value.toString()
        is Double ->
            // 整数值的 double 输出为整数形式，避免 "1.0" 与 "1" 的字节差异
            if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
        is Float -> value.toString()
        is Number -> value.toString()

        is Map<*, *> -> {
            val entries = value.entries
                .filter { it.value != null }
                .map { it.key.toString() to it.value!! }
                .sortedBy { it.first }
            entries.joinToString(separator = ",", prefix = "{", postfix = "}") { (k, v) ->
                quote(k) + ":" + encode(v)
            }
        }

        is List<*> -> value.joinToString(separator = ",", prefix = "[", postfix = "]") { encode(it) }
        is Array<*> -> value.joinToString(separator = ",", prefix = "[", postfix = "]") { encode(it) }

        else -> throw IllegalArgumentException("StableJson 不支持的类型: ${value::class.java.name}")
    }

    /** JSON 字符串转义。必须确定性：同样输入永远同样输出。 */
    private fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else ->
                    if (c < ' ') sb.append("\\u%04x".format(c.code))
                    else sb.append(c) // 中文等直接输出（UTF-8 原字节）
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
