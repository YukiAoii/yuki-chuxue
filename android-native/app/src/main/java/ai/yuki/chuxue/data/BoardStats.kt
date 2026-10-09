package ai.yuki.chuxue.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 看板需要的**滚动统计**（v0.54.0）。
 *
 * ## ⚠️ 为什么放在 Store 的 JSON 里，而不是加 Room 表 / 加列
 * 项目有一条红线：**迁移是唯一会损坏用户聊天记录的操作**。
 * 而看板要的这几样（近 20 轮趋势、每个模型的用量、首字耗时）都是
 * **纯增量、可丢的观测量** —— 丢了顶多让图表从零开始重新攒，
 * 完全不该为它去冒一次 schema 迁移的风险。所以它们进 SharedPreferences。
 *
 * ## ⚠️ 三样"只有新数据才有"的东西（界面必须说清，不能装作 0）
 * 1. **首字耗时**：过去从没记过。老会话这块是**缺口**，要显示「还没有数据」——
 *    显示 0 会被读成"瞬间回复"，那是**反向的低报**。
 * 2. **近 N 轮趋势**：只有从这个版本之后聊出来的轮次才有点。
 * 3. **模型维度**：`provider_usage` 表只按**服务商**分桶（`providerKey` = 归一化 baseUrl），
 *    从来没记过模型名 —— 所以"各模型排行"也只能从现在开始攒。
 *
 * ## ⚠️ 它们全是观测量，不是用户数据
 * 读写失败一律**降级为空**，绝不抛：一个统计读不出来，不该让看板打不开。
 */
object BoardStats {

    /** 最多留多少轮（用户要"近 20 轮折线"）。 */
    const val MAX_TURNS = 20

    /** 最多记多少个模型（防脏数据把偏好撑爆）。 */
    private const val MAX_MODELS = 60

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /* ─────────────── 一轮 ─────────────── */

    /**
     * 一轮的观测。
     *
     * @param at 这一轮**结束**的时刻
     * @param hit 该轮命中 token
     * @param miss 该轮未命中 token
     * @param firstByteMs **发出 → 首字**的毫秒数；`0` = 没测到（见类注释）
     */
    data class TurnStat(
        val at: Long,
        val hit: Int,
        val miss: Int,
        val firstByteMs: Long = 0L,
    ) {
        /** 这一轮的命中率（0..1）；没有计费 token 时返回 null（**不是 0**）。 */
        val hitRatio: Double? get() = if (hit + miss <= 0) null else hit.toDouble() / (hit + miss)
    }

    fun appendTurn(list: List<TurnStat>, t: TurnStat): List<TurnStat> =
        (list + t).takeLast(MAX_TURNS)

    fun encodeTurns(list: List<TurnStat>): String = buildJsonArray {
        list.forEach { t ->
            add(
                buildJsonObject {
                    put("at", t.at)
                    put("hit", t.hit)
                    put("miss", t.miss)
                    put("fb", t.firstByteMs)
                },
            )
        }
    }.toString()

    fun decodeTurns(raw: String?): List<TurnStat> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.parseToJsonElement(raw).jsonArray.mapNotNull { el ->
                runCatching {
                    val o = el.jsonObject
                    TurnStat(
                        at = o["at"]?.jsonPrimitive?.content?.toLongOrNull() ?: return@runCatching null,
                        hit = o["hit"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                        miss = o["miss"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                        firstByteMs = o["fb"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                    )
                }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    /* ─────────────── 模型用量 ─────────────── */

    /**
     * 某个「服务商 + 模型」组合的累计用量。
     *
     * ⚠️ 为什么 key 里要带 provider：**同名模型可能出现在不同分组**里
     *（同一个 `deepseek-chat` 既可能在官方分组、也可能在某个中转分组），
     * 而它们的缓存与计价是**分开算**的。用户明确要求"如果同名模型标注出属于哪个分组"。
     */
    data class ModelUsage(
        val providerKey: String,
        val model: String,
        val hit: Int = 0,
        val miss: Int = 0,
        val requests: Int = 0,
    ) {
        val billed: Int get() = hit + miss
    }

    /** 复合键：`providerKey\u0000model`（用不可能出现在 URL/模型名里的字符分隔）。 */
    fun modelKey(providerKey: String, model: String): String = "$providerKey\u0000$model"

    fun bumpModel(
        map: Map<String, ModelUsage>,
        providerKey: String,
        model: String,
        hit: Int,
        miss: Int,
    ): Map<String, ModelUsage> {
        val key = modelKey(providerKey, model)
        val old = map[key]
        val next = if (old == null) {
            ModelUsage(providerKey, model, hit, miss, 1)
        } else {
            old.copy(hit = old.hit + hit, miss = old.miss + miss, requests = old.requests + 1)
        }
        // 超上限时丢掉"最不常请求"的那些（它们对排行榜没有意义，留着只会撑爆偏好文件）
        val merged = map + (key to next)
        return if (merged.size <= MAX_MODELS) {
            merged
        } else {
            merged.entries
                .sortedByDescending { it.value.requests }
                .take(MAX_MODELS)
                .associate { it.key to it.value }
        }
    }

    fun encodeModels(map: Map<String, ModelUsage>): String = buildJsonArray {
        map.values.forEach { u ->
            add(
                buildJsonObject {
                    put("p", u.providerKey)
                    put("m", u.model)
                    put("h", u.hit)
                    put("mi", u.miss)
                    put("r", u.requests)
                },
            )
        }
    }.toString()

    fun decodeModels(raw: String?): Map<String, ModelUsage> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            json.parseToJsonElement(raw).jsonArray.mapNotNull { el ->
                runCatching {
                    val o = el.jsonObject
                    val p = o["p"]?.jsonPrimitive?.content.orEmpty()
                    val m = o["m"]?.jsonPrimitive?.content.orEmpty()
                    if (p.isBlank() && m.isBlank()) return@runCatching null
                    modelKey(p, m) to ModelUsage(
                        providerKey = p,
                        model = m,
                        hit = o["h"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                        miss = o["mi"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                        requests = o["r"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                    )
                }.getOrNull()
            }.toMap()
        }.getOrDefault(emptyMap())
    }
}
