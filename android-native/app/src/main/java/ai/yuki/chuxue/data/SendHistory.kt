package ai.yuki.chuxue.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * 「我发过的话」——**跨会话**的说话历史 + 模糊搜索（v0.61.21，⑤-A 第 7 条）。
 *
 * ## 它补的是哪块
 * App 本来就有"长按气泡 → 重新生成 / 重发"，但那只在**同一段对话里**。
 * 这条给的是**跨会话**：你在别的对话里说过的一句话，能在任何地方翻出来重发。
 *
 * ## 参考实现与两处刻意的偏离
 * 做法照 `_refs/Tianshu-harness/src/tui/history.ts`：上限 1000、**前缀命中 +10、
 * 词命中 +5**、返回前 N 条。两点不一样：
 * 1. 那边**只**跟最近一条去重（同一句话隔几条再发一次会留两条），这里**全表去重**
 *    —— 手机上看重复项纯属噪音。
 * 2. 那边把历史和磁盘读写混在一个文件里，这里只有纯函数；存盘在 [Store]。
 *
 * ## 只驱动界面
 * 它**不进 `messages`、不进请求体**（缓存红线）；点一条只是**填进输入框**，不直接发。
 */
object SendHistory {

    /** 最多留多少条（与参考实现同口径）。 */
    const val MAX = 1000

    /** 一次给用户看几条。 */
    const val DEFAULT_LIMIT = 20

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 记一条。空串/纯空白不记；**已经在表里的挪到最前**（不堆重复项）。
     *
     * ⚠️ 返回新表而不是就地改 —— 调用方是 StateFlow，方便它一眼看出"变了没"。
     */
    fun record(history: List<String>, text: String): List<String> {
        val t = text.trim()
        if (t.isEmpty()) return history
        if (history.firstOrNull() == t) return history
        return (listOf(t) + history.filter { it != t }).take(MAX)
    }

    /**
     * 模糊搜索：先按"**每个词都要出现**"筛，再按分排序。
     *
     * ⚠️ 这里**刻意偏离**了参考实现。天枢那边过滤用的是**整个 query 原样**
     *（`e.includes(lower)`），于是 `"下雨 带伞"` 这种多词查询一条都搜不到 ——
     * 因为没有任何一句里含"下雨 带伞"这五个连着的字。而它下面的打分又是按词切的，
     * 两处口径不一致，**那半截按词的加分等于白写**。
     * 这里改成：**AND 语义**（每个词都要出现），打分按"开头命中 +10、每词命中 +5"。
     * 于是 `"下雨 带伞"` 能搜到"今天下雨记得带伞"，且它比只中一个词的排得前。
     */
    fun search(history: List<String>, query: String, limit: Int = DEFAULT_LIMIT): List<String> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return history.take(limit)
        val words = q.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val head = words.first()
        return history
            .filter { entry ->
                val lower = entry.lowercase()
                words.all { w -> lower.contains(w) }
            }
            .map { entry ->
                val lower = entry.lowercase()
                var score = 0
                if (lower.startsWith(head)) score += 10
                words.forEach { w -> if (lower.contains(w)) score += 5 }
                entry to score
            }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    /* ── 存盘：与 ProviderGroups 同一套手写 codec（本项目刻意不引 @Serializable）── */

    fun encode(history: List<String>): String =
        buildJsonArray { history.forEach { add(it) } }.toString()

    /** 坏数据一律降级为空表，不抛 —— 它跑在设置/聊天的启动路径上。 */
    fun decode(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.parseToJsonElement(raw).jsonArray
                .mapNotNull { it.jsonPrimitive.content.takeIf { s -> s.isNotBlank() } }
                .take(MAX)
        }.getOrElse { emptyList() }
    }
}
