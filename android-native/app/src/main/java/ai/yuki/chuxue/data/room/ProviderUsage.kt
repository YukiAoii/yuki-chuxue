package ai.yuki.chuxue.data.room

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * **按服务商分桶的用量**（v0.51.0，Room v14 → v15）。
 *
 * ## 为什么必须有这张表
 * `Session.totalHit / totalMiss` 是**按会话**累加的，它不记录"这一轮用的是哪家服务商"。
 * 于是从 v0.51.0 起（用户可以在一段对话中途换分组/换模型）那个百分比变成了
 * **混合口径**：
 *
 * · 不同服务商的"命中"来自**不同的字段**（DeepSeek 顶层 / OpenAI 系嵌套）；
 * · 粒度与起步门槛也不同（精确前缀 vs 部分前缀，128 vs 1024）；
 * · 有的服务商**压根不报**这个数。
 *
 * 把三家的读数加在一起再除，得到的数字**没有对应任何一个真实的服务商** ——
 * 用户拿着它去判断"省钱开关有没有生效"只会被误导。
 * 分桶之后，看板能说清"这段对话在 A 家命中 92%、在 B 家未提供"。
 *
 * ## ⚠️ 建表迁移（不是加列）
 * 与 `MIGRATION_6_7`（emoji_packs）同型：全新一张表，**不动任何已有列**，
 * 因此回滚安全、也不碰"消息不重写"那条红线。
 * 表结构必须与 Room 为 [ProviderUsageEntity] 生成的 schema **逐字段一致**，
 * 否则 Room 启动时 schema 校验会抛 `IllegalStateException`（由
 * `MigrationSchemaTest` 兜住）。
 */
@Entity(
    tableName = "provider_usage",
    primaryKeys = ["sessionId", "providerKey"],
)
data class ProviderUsageEntity(
    val sessionId: String,
    /**
     * 服务商标识 = 归一化后的 baseUrl（见 [ProviderUsage.keyOf]）。
     *
     * ⚠️ 为什么用地址而不是"名字"：用户给分组起的名字随时会改，而**缓存归属**
     * 认的是地址（换地址 = 换缓存）。名字变了把历史数据劈成两行才是错的。
     */
    val providerKey: String,
    val hitTokens: Int,
    val missTokens: Int,
    val inputTokens: Int,
    val outputTokens: Int,
    /** 这一桶里累计了多少次请求 —— 看板上"0 命中"到底是首轮还是真没命中，靠它分辨 */
    val requests: Int,
    val updatedAt: Long,
)

@Dao
interface ProviderUsageDao {

    /** 某段对话的分桶读数（用得多的排前面）。 */
    @Query("SELECT * FROM provider_usage WHERE sessionId = :sessionId ORDER BY requests DESC")
    suspend fun ofSession(sessionId: String): List<ProviderUsageEntity>

    /**
     * 全库按服务商汇总（全局看板用）。
     *
     * ⚠️ `sessionId` 在这里没有意义，但实体要求它非空 —— 用空串占位，
     * 调用方**不要**拿这个字段做任何判断。
     */
    @Query(
        "SELECT '' AS sessionId, providerKey, SUM(hitTokens) AS hitTokens, " +
            "SUM(missTokens) AS missTokens, SUM(inputTokens) AS inputTokens, " +
            "SUM(outputTokens) AS outputTokens, SUM(requests) AS requests, " +
            "MAX(updatedAt) AS updatedAt " +
            "FROM provider_usage GROUP BY providerKey ORDER BY SUM(requests) DESC",
    )
    suspend fun totals(): List<ProviderUsageEntity>

    @Query(
        "SELECT * FROM provider_usage WHERE sessionId = :sessionId " +
            "AND providerKey = :providerKey LIMIT 1",
    )
    suspend fun one(sessionId: String, providerKey: String): ProviderUsageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: ProviderUsageEntity)

    @Query("DELETE FROM provider_usage WHERE sessionId = :sessionId")
    suspend fun clearSession(sessionId: String)

    /** 全清（导入备份时"整体替换"用，见 `SessionRepository.replaceAll`）。 */
    @Query("DELETE FROM provider_usage")
    suspend fun deleteAll()
}

/**
 * 分桶这件事里**与 Android 无关**的那部分 —— 纯函数，有单测。
 *
 * 抽出来是因为"这行该显示什么"和"这段对话是不是混合口径"判断错了，
 * 用户看到的就是一个骗人的数字，而那种错在 UI 里根本测不到。
 */
object ProviderUsage {

    /**
     * 服务商标识：baseUrl 去掉首尾空白与尾部斜杠、主机名小写。
     *
     * ⚠️ 只做**归一化**，不做"识别成哪家"——那是 `ProviderProfiles.resolve` 的事。
     * 这里要的是"缓存归属的键"：`https://API.DeepSeek.com/v1/` 与
     * `https://api.deepseek.com/v1` 必须算同一桶，否则同一家会被劈成两行。
     */
    fun keyOf(baseUrl: String): String {
        val t = baseUrl.trim().trimEnd('/')
        if (t.isEmpty()) return "未配置"
        // 只小写 scheme+host：路径是大小写敏感的（有些网关的路径确实区分）
        val idx = t.indexOf("://")
        if (idx < 0) return t
        val head = t.substring(0, idx + 3)
        val rest = t.substring(idx + 3)
        val slash = rest.indexOf('/')
        return if (slash < 0) head + rest.lowercase() else head + rest.take(slash).lowercase() + rest.substring(slash)
    }

    /**
     * 这些桶是不是**不止一家** —— 是的话，会话级那个累计命中率就是混合口径。
     *
     * 界面必须把这件事说出来。不说的话，用户看到"87%"会以为它对应某一家，
     * 而它其实谁都不对应。
     */
    fun isMixed(keys: List<String>): Boolean = keys.distinct().size > 1

    /**
     * 一行的读数该怎么写（看板用）。
     *
     * ⚠️ 参数是**已经筛过**的命中/未命中（调用方按服务商口径取过字段）：
     * · [reports] = false 表示这家压根不报这个数 → 说"未提供"，**不显示 0%**；
     * · 一次请求都还没记 → 说"尚无请求"；
     * · 否则给百分比。
     */
    fun describeText(hit: Int, miss: Int, requests: Int, reports: Boolean): String {
        if (!reports) return "该服务商未提供缓存用量"
        if (requests <= 0) return "尚无请求"
        val billed = hit + miss
        if (billed <= 0) return "该服务商未提供缓存用量"
        return "命中 %.0f%%".format(hit * 100.0 / billed)
    }
}
