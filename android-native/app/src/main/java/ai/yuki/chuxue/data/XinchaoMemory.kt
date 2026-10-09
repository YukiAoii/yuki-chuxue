package ai.yuki.chuxue.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 「云端记忆」—— App 与**记忆大脑 OB**（经 backend 代理）之间的读写（v0.61.36）。
 *
 * ## 它补的是什么
 * App 本机记忆（Room）是"简单检索"（关键词、无向量）；而云端 OB 才是完整的长期记忆
 * 系统（语义检索 / 做梦 / 地标…）。用户 2026-10-06 拍板：记忆**进 OB**。
 * 本对象负责两件事：
 *  · [fetchText] —— 读该人设云端已有的记忆（`GET  /xinchao/personas/{id}/memory`）；
 *  · [write]     —— 把一条本机记忆送上云（`POST /xinchao/personas/{id}/memory` → OB hold）。
 *
 * ## 三条边界（与 [XinchaoReport] 同一套纪律）
 * 1. **只发给 [ServerConfig.BASE_URL]**（编译期内置的自家服务器）；
 * 2. **失败一律静默/降级**（返回 null / Result，界面走"空"分支，不打扰用户）；
 * 3. **鉴权用登录态 token**（未登录时服务器 401，静默失败）。
 *
 * ## 归属/隐私边界（服务器侧保证，App 侧只管带 token）
 * backend 按 persona 归属校验（非本人 404），并把请求带 `X-Persona-Id` 转发到 OB 该人设的桶。
 * ⚠️ **进 OB 的记忆在服务器上是明文的**（OB 要用它做语义检索/做梦，做不到端到端加密）——
 *    这与"人设快照是 E2E 密文"是两种级别，界面文案不能含糊。
 */
/**
 * 云端记忆里的一条（v0.61.37）—— 供「云端记忆」页按**域**分组渲染。
 *
 * 与 [XinchaoMemoryApi.fetchText] 的纯文本不同：这是**结构化**的一条，
 * 来自 OB 的桶文件（经 backend 解析），带域/标题/重要性/时间。
 */
data class XinchaoBucket(
    val id: String,
    /** 记忆域（日常/工作/居家/梦境…）。 */
    val domain: String,
    /** 短标题（OB 的 title / name）。 */
    val title: String,
    /** 正文。 */
    val content: String,
    /** 重要性 0..10（展示用；0 表示没记）。 */
    val importance: Int = 0,
    /** 创建时间 ISO 串（可能为空）。 */
    val createdAt: String? = null,
)

object XinchaoMemoryApi {

    private val mediaJson = "application/json; charset=utf-8".toMediaType()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /* ─────────────── 纯函数（JVM 可测） ─────────────── */

    /** 读端点 URL（`q` 非空时带上检索关键词）；base/personaId 为空 → 空串。 */
    fun listUrl(baseUrl: String, personaId: String, query: String = ""): String {
        if (baseUrl.isBlank() || personaId.isBlank()) return ""
        val q = query.trim()
        val tail = if (q.isEmpty()) "" else "?q=" + java.net.URLEncoder.encode(q, "UTF-8")
        return "${baseUrl.trimEnd('/')}/xinchao/personas/$personaId/memory$tail"
    }

    /** 写端点 URL；同上。 */
    fun writeUrl(baseUrl: String, personaId: String): String =
        if (baseUrl.isBlank() || personaId.isBlank()) ""
        else "${baseUrl.trimEnd('/')}/xinchao/personas/$personaId/memory"

    /** 写请求体：{"content":…, "title":…, "category":…, "importance":…}（title/category 空则不写）。 */
    fun writeBody(
        content: String,
        title: String? = null,
        category: String? = null,
        importance: Int? = null,
    ): String = buildJsonObject {
        put("content", content)
        title?.takeIf { it.isNotBlank() }?.let { put("title", it) }
        category?.takeIf { it.isNotBlank() }?.let { put("category", it) }
        importance?.let { put("importance", it.coerceIn(1, 10)) }
    }.toString()

    /**
     * **纯函数**：从读端点的响应里取 `text` 字段（服务端已把 OB 的返回段落拼好）。
     * 认不出（不是 JSON / 缺字段）→ `null`。
     */
    internal fun parseText(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            json.parseToJsonElement(raw).jsonObject["text"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()
    }

    /** 结构化列表端点 URL；同上。 */
    fun bucketListUrl(baseUrl: String, personaId: String): String =
        if (baseUrl.isBlank() || personaId.isBlank()) ""
        else "${baseUrl.trimEnd('/')}/xinchao/personas/$personaId/memory/list"

    /**
     * **纯函数**：解析结构化列表端点 `{"buckets":[{id,domain,title,content,importance,createdAt}]}`。
     * 认不出 → `null`；单条缺 id 就跳过（不让一条坏数据毁掉整页）。
     */
    internal fun parseBuckets(raw: String?): List<XinchaoBucket>? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val arr = json.parseToJsonElement(raw).jsonObject["buckets"]?.jsonArray ?: return null
            arr.mapNotNull { el ->
                val o = el.jsonObject
                val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                XinchaoBucket(
                    id = id,
                    domain = o["domain"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "未分类" },
                    title = o["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    content = o["content"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    importance = o["importance"]?.jsonPrimitive?.intOrNull ?: 0,
                    createdAt = o["createdAt"]?.jsonPrimitive?.contentOrNull,
                )
            }
        }.getOrNull()
    }

    /** 删除端点 URL：删**某一条**云端记忆（按 OB 桶 id）。 */
    fun deleteMemoryUrl(baseUrl: String, personaId: String, bucketId: String): String =
        if (baseUrl.isBlank() || personaId.isBlank() || bucketId.isBlank()) ""
        else "${baseUrl.trimEnd('/')}/xinchao/personas/$personaId/memory/$bucketId"

    /** 删除端点 URL：删**整个角色**在云端的痕迹（物理清桶）。 */
    fun deletePersonaUrl(baseUrl: String, personaId: String): String =
        if (baseUrl.isBlank() || personaId.isBlank()) ""
        else "${baseUrl.trimEnd('/')}/xinchao/personas/$personaId"

    /** 写端点的响应体里取 OB 分配的桶 id（`{"bucketId":"…"}`）。认不出 → null。 */
    internal fun parseWriteBucketId(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            json.parseToJsonElement(raw).jsonObject["bucketId"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /* ─────────────── 发送 ─────────────── */

    /** 读该人设的云端记忆文本。失败/未接入 → `null`。 */
    suspend fun fetchText(baseUrl: String, token: String, personaId: String, query: String = ""): String? =
        withContext(Dispatchers.IO) {
            val url = listUrl(baseUrl, personaId, query)
            if (url.isEmpty() || token.isBlank()) return@withContext null
            runCatching {
                val req = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $token")
                    .get()
                    .build()
                client.newCall(req).execute().use { res ->
                    if (!res.isSuccessful) null else parseText(res.body?.string())
                }
            }.getOrNull()
        }

    /**
     * 把一条记忆写进云端。**成功时返回云端桶 id**（`null`=写成功但没解析出 id，少见）。
     * 失败 → `Result.failure`（调用方决定忽略或计数）。
     */
    suspend fun write(
        baseUrl: String,
        token: String,
        personaId: String,
        content: String,
        title: String? = null,
        category: String? = null,
        importance: Int? = null,
    ): Result<String?> = withContext(Dispatchers.IO) {
        val url = writeUrl(baseUrl, personaId)
        if (url.isEmpty() || token.isBlank() || content.isBlank()) {
            return@withContext Result.failure(IllegalStateException("未配置地址/未登录/内容为空"))
        }
        runCatching {
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .post(writeBody(content, title, category, importance).toRequestBody(mediaJson))
                .build()
            client.newCall(request).execute().use { res ->
                val raw = res.body?.string()
                if (!res.isSuccessful) error("云端写入失败：HTTP ${res.code}")
                parseWriteBucketId(raw)
            }
        }
    }

    /** 拉该人设的**结构化**云端记忆列表。失败/空 → `null`。 */
    suspend fun fetchBuckets(baseUrl: String, token: String, personaId: String): List<XinchaoBucket>? =
        withContext(Dispatchers.IO) {
            val url = bucketListUrl(baseUrl, personaId)
            if (url.isEmpty() || token.isBlank()) return@withContext null
            runCatching {
                val req = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $token")
                    .get()
                    .build()
                client.newCall(req).execute().use { res ->
                    if (!res.isSuccessful) null else parseBuckets(res.body?.string())
                }
            }.getOrNull()
        }

    /**
     * 删**云端某一条**记忆（按 OB 桶 id）。服务端会先走 OB 的官方归档、再抹掉档案文件。
     * 失败 → `Result.failure`（删除是用户显式动作，调用方应给出提示而不是静默）。
     */
    suspend fun deleteMemory(
        baseUrl: String,
        token: String,
        personaId: String,
        bucketId: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val url = deleteMemoryUrl(baseUrl, personaId, bucketId)
        if (url.isEmpty() || token.isBlank()) {
            return@withContext Result.failure(IllegalStateException("未配置地址/未登录/缺桶 id"))
        }
        runCatching {
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .delete()
                .build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) error("云端删除失败：HTTP ${res.code}")
            }
        }
    }

    /** 删**整个角色**在云端的痕迹（服务端物理清桶 + 注销归属）。失败 → `Result.failure`。 */
    suspend fun deletePersona(baseUrl: String, token: String, personaId: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            val url = deletePersonaUrl(baseUrl, personaId)
            if (url.isEmpty() || token.isBlank()) {
                return@withContext Result.failure(IllegalStateException("未配置地址/未登录"))
            }
            runCatching {
                val req = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $token")
                    .delete()
                    .build()
                client.newCall(req).execute().use { res ->
                    if (!res.isSuccessful) error("云端清除失败：HTTP ${res.code}")
                }
            }
        }
}
