package ai.yuki.chuxue.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 心潮两项**此前没接进 App**的能力（v0.61.52）。
 *
 * | 能力 | 端点 | 去向 |
 * |---|---|---|
 * | 当前意图 | `GET /xinchao/personas/{id}/intent` | 「Ta 此刻」卡（"她现在想干嘛"） |
 * | 梦境余韵 | `GET /xinchao/personas/{id}/breath` | 「Ta 此刻」卡（"刚做的梦还剩什么"） |
 *
 * ## 两条清洗边界（别搞混）
 * - 意图的 `label` 是**描述性长句**（"想她、想黏着她、想占有与靠近"）→ 必须过
 *   [XinchaoStateApi.DRIVE_WORDS] 换短词才给用户看；
 * - 梦境只取 `residue`（醒来感受），**不取 `summary`**（"几乎没意识到在做梦"那种元叙述）。
 *
 * ## 边界（与 [XinchaoStateApi] 同一套纪律）
 * 只读、不落库；失败（未登录 / 未登记 / 网络）一律返回 `null`，界面走"没有就不显示"。
 */
data class XinchaoIntent(
    /** 原始驱力键（如 `possess`）。 */
    val key: String = "",
    /** 用户向短词（过词表；认不出的键原样保留）。 */
    val word: String = "",
    val value: Double = 0.0,
    /** 最强的驱力键（并列小字用；可能为空）。 */
    val topDriveKey: String? = null,
)

/** 一条梦境余韵（只留**醒来感受**那一句）。 */
data class XinchaoDream(
    val id: String = "",
    val residue: String = "",
    val createdAt: String? = null,
)

object XinchaoIntentApi {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /** **纯函数**：解析心潮 `/v1/intent`。认不出（缺 intent / 不是 JSON）→ `null`。 */
    internal fun parse(raw: String?): XinchaoIntent? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val intent = json.parseToJsonElement(raw).jsonObject["intent"]?.jsonObject ?: return null
            val key = intent["key"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (key.isBlank()) return null
            val top = json.parseToJsonElement(raw).jsonObject["topDrives"]?.jsonArray
                ?.firstOrNull()?.jsonObject?.get("key")?.jsonPrimitive?.contentOrNull
            XinchaoIntent(
                key = key,
                word = XinchaoStateApi.DRIVE_WORDS[key] ?: key,
                value = intent["value"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                topDriveKey = top,
            )
        }.getOrNull()
    }

    /** 拉某人设的当前意图。失败一律 `null`。 */
    suspend fun fetch(baseUrl: String, token: String, personaId: String): XinchaoIntent? =
        withContext(Dispatchers.IO) {
            if (baseUrl.isBlank() || token.isBlank() || personaId.isBlank()) return@withContext null
            runCatching {
                val url = "${baseUrl.trimEnd('/')}/xinchao/personas/$personaId/intent"
                val req = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $token")
                    .get()
                    .build()
                client.newCall(req).execute().use { res ->
                    if (!res.isSuccessful) null else parse(res.body?.string())
                }
            }.getOrNull()
        }
}

object XinchaoBreathApi {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /**
     * **纯函数**：解析 `/v1/breath-context`，只保留**最新一条**有 `residue` 的梦。
     *
     * `dreams` 按时间升序，所以取 `last`（不是 `first`）。`residue` 空白 → `null`
     * （空句子对用户没意义，不如不显示）。
     */
    internal fun parse(raw: String?): XinchaoDream? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val dreams = json.parseToJsonElement(raw).jsonObject["dreams"]?.jsonArray ?: return null
            dreams.mapNotNull { el ->
                val o = el.jsonObject
                val residue = o["residue"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (residue.isBlank()) return@mapNotNull null
                XinchaoDream(
                    id = o["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    residue = residue,
                    createdAt = o["createdAt"]?.jsonPrimitive?.contentOrNull,
                )
            }.lastOrNull()
        }.getOrNull()
    }

    /** 拉某人设最近的梦境余韵。失败一律 `null`。 */
    suspend fun fetch(baseUrl: String, token: String, personaId: String): XinchaoDream? =
        withContext(Dispatchers.IO) {
            if (baseUrl.isBlank() || token.isBlank() || personaId.isBlank()) return@withContext null
            runCatching {
                val url = "${baseUrl.trimEnd('/')}/xinchao/personas/$personaId/breath"
                val req = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $token")
                    .get()
                    .build()
                client.newCall(req).execute().use { res ->
                    if (!res.isSuccessful) null else parse(res.body?.string())
                }
            }.getOrNull()
        }
}

/**
 * 心潮「**上下文信封**」（`/v1/context`）—— App 侧的取法（v0.61.53）。
 *
 * ## 它是什么（心潮 README 原文）
 * 「**近期连续性**：Context Envelope 只携带动态短态、近期交接和可选的长期记忆召回，
 *   不替代客户端自己的核心指令或人物基岩。」
 * 也就是"**你不在的时候攒下的东西**"打成一个有 token 预算（默认 2200）的信封。
 *
 * ## ⚠️ 为什么用 `mode=inspect`
 * 信封是**投递式**的（`handoffOnceHours=12`：同一个 session **只交付一次**，之后返回空）。
 * 实测（2026-10-06，连续三次）：默认模式第二次起就 `alreadyDelivered` + `sections: []`；
 * 而 **`mode=inspect` 可以连续重复调用、每次都返回完整 sections** ——
 * 这正是"App 想随时拿一次当前信封"需要的模式。
 *
 * ## 它比 [XinchaoStateApi.fetchModelNote]（`/v1/now`）多什么
 * `sections[]` 里除了 `dynamic_state`（≈ now 那块），还有 `dream_residue`、
 * 匣子提醒、觉察候选等。所以调用方**优先用它，取不到再回落 now**。
 *
 * ⚠️ `sections[].content` 是**给模型看的**（第二人称播报 / 内部字段名）→ 只进对话附录。
 */
object XinchaoContextApi {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /** 信封端点 URL（`session_id` 做 URL 编码 —— 中文/空格不能裸传）。 */
    fun url(baseUrl: String, personaId: String, sessionId: String): String =
        if (baseUrl.isBlank() || personaId.isBlank()) {
            ""
        } else {
            buildString {
                append(baseUrl.trimEnd('/'))
                append("/xinchao/personas/").append(personaId).append("/context")
                val sid = sessionId.trim()
                if (sid.isNotEmpty()) {
                    append("?session_id=").append(java.net.URLEncoder.encode(sid, "UTF-8"))
                }
            }
        }

    /**
     * **纯函数**：把 `sections[]` 拼成一块给模型看的文本（每段 `段名 + 换行 + content`）。
     *
     * `content` 空白 → 跳过该段；拼完为空 → `null`（**空信封不是错误**：它表示"已经投递过"，
     * 调用方回落 `/v1/now` 即可）。
     */
    internal fun parse(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val sections = json.parseToJsonElement(raw).jsonObject["sections"]?.jsonArray ?: return null
            val blocks = sections.mapNotNull { el ->
                val o = el.jsonObject
                val content = o["content"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (content.isBlank()) return@mapNotNull null
                val id = o["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (id.isBlank()) content else "$id\n$content"
            }
            if (blocks.isEmpty()) null else blocks.joinToString("\n\n")
        }.getOrNull()
    }

    /** 取某人设的信封文本。失败 / 空信封一律 `null`。 */
    suspend fun fetch(
        baseUrl: String,
        token: String,
        personaId: String,
        sessionId: String,
    ): String? = withContext(Dispatchers.IO) {
        val u = url(baseUrl, personaId, sessionId)
        if (u.isEmpty() || token.isBlank()) return@withContext null
        runCatching {
            val req = Request.Builder()
                .url(u)
                .header("Authorization", "Bearer $token")
                .get()
                .build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) null else parse(res.body?.string())
            }
        }.getOrNull()
    }
}
