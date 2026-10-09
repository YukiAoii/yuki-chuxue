package ai.yuki.chuxue.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 「Ta 主动来找我」—— 取 Ta 主动想说的话（v0.61.40）。
 *
 * ## 它是什么
 * 心潮的情绪状态机自己决定"什么时候想说话"（情绪变化 / 梦境 / 念头涌现 / 驱力变化），
 * 说过的话由服务端 `GET /xinchao/personas/{id}/pending?since=` 暴露。
 * **这个对象只负责"把话取回来"** —— 任何"到点提醒 Ta 说话"的逻辑都不许出现在接入侧。
 *
 * ## 边界（与 [XinchaoMemoryApi] / [XinchaoStateApi] 同一套纪律）
 * 1. 只发给 [ServerConfig.BASE_URL]（编译期内置的自家服务器）；
 * 2. 失败一律静默（返回 null，界面不打扰）；
 * 3. 鉴权用登录态 token（未登录 401，静默失败）。
 */
data class XinchaoMessage(
    /** 服务端给的 ISO 时间串（**原样透传**，去重比较靠它）。 */
    val at: String,
    /** 引擎标注的种类（dream_push / autonomous…）—— 目前只透传，不参与展示判断。 */
    val kind: String,
    /** Ta 说的话（单条一句）。 */
    val message: String,
)

object XinchaoPendingApi {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /* ─────────────── 纯函数（JVM 可测） ─────────────── */

    /**
     * 取件端点 URL；`since` 非空时带 `?since=`。base/persona 为空 → 空串（调用方据此跳过）。
     *
     * ⚠️ ISO 串里的 `+`（时区偏移）必须 URL 编码成 `%2B` —— 不编码的话服务端会把它
     * 读成空格，`since` 比对直接失效（消息会重弹/漏弹）。
     */
    fun pendingUrl(baseUrl: String, personaId: String, since: String = ""): String {
        if (baseUrl.isBlank() || personaId.isBlank()) return ""
        val s = since.trim()
        val tail = if (s.isEmpty()) "" else "?since=" + java.net.URLEncoder.encode(s, "UTF-8")
        return "${baseUrl.trimEnd('/')}/xinchao/personas/$personaId/pending$tail"
    }

    /**
     * **纯函数**：解析 `{"messages":[{at,kind,message}]}`（服务端只回展示需要的字段）。
     *
     * 认不出（不是 JSON / `messages` 不是数组）→ `null`；
     * 单条缺 `message`（空串）就跳过 —— 不让一条坏数据毁掉整批。
     */
    internal fun parse(raw: String?): List<XinchaoMessage>? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val arr = json.parseToJsonElement(raw).jsonObject["messages"]?.jsonArray ?: return null
            arr.mapNotNull { el ->
                val o = el.jsonObject
                val message = o["message"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                XinchaoMessage(
                    at = o["at"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    kind = o["kind"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    message = message,
                )
            }
        }.getOrNull()
    }

    /* ─────────────── 发送 ─────────────── */

    /** 取该人设的新消息（`since` 空串 = 全部最近几条）。失败/未登录 → `null`。 */
    suspend fun fetch(
        baseUrl: String,
        token: String,
        personaId: String,
        since: String = "",
    ): List<XinchaoMessage>? = withContext(Dispatchers.IO) {
        val url = pendingUrl(baseUrl, personaId, since)
        if (url.isEmpty() || token.isBlank()) return@withContext null
        runCatching {
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
