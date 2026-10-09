package ai.yuki.chuxue.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** 反馈内容的长度上限（与后端一致；超了后端也会拒，但本地先拦能省一次往返）。 */
internal const val FEEDBACK_CONTENT_MAX = 500
internal const val FEEDBACK_CONTACT_MAX = 100

/**
 * **纯函数**：提交前的本地校验。
 *
 * 抽成纯函数是为了能在 JVM 上钉死 —— 它是"用户会不会白发一次"的唯一闸门，
 * 而真正该拦的只有两件事：**空内容**与**超长**。
 *
 * ⚠️ 注意这里的长度口径与后端**必须一致**：后端按 `len()`（Python 字符数）算，
 *    Kotlin 的 `String.length` 是 UTF-16 码元数 —— 对**中文**是一致的（BMP 内 1 个码元），
 *    对 emoji 会偏大（代理对算 2）。偏大只会"更早拦住"，不会放过超长的，方向安全。
 *
 * @return `null` = 可以发；否则是给用户看的一句人话
 */
internal fun feedbackValidationError(content: String, contact: String): String? = when {
    content.isBlank() -> "写点什么再发吧"
    content.length > FEEDBACK_CONTENT_MAX ->
        "内容请控制在 $FEEDBACK_CONTENT_MAX 字以内（现在 ${content.length} 字）"
    contact.length > FEEDBACK_CONTACT_MAX ->
        "联系方式请控制在 $FEEDBACK_CONTACT_MAX 字以内"
    else -> null
}

/**
 * 反馈上报（v0.57.0）。
 *
 * ## 为什么单独一个 Api，而不是塞进 AuthApi
 * 它与账号无关：**不需要登录、不需要令牌**。官网的反馈表单也打同一个后端
 * （`POST /api/feedback`），所以这里走的是 `ServerConfig.url()` 的绝对路径，
 * 不是 `AuthApi` 那套 `/api/v1` 前缀。
 *
 * ## 失败语义
 * 与更新检查相反：**失败必须说话**。用户刚写完一段话点了发送，
 * 静默失败会让他以为"发出去了"—— 那比报错更糟。
 */
object FeedbackApi {

    sealed interface Result {
        data object Ok : Result

        /** [message] 已经是**给人看的**中文（UI 直接显示，不必再翻译）。 */
        data class Fail(val message: String) : Result
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val mediaJson = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private const val NETWORK_FAIL = "连不上服务器。检查手机网络，或稍后再试。"
    private const val PATH = "/api/feedback"

    /** **纯函数**：构造请求体（抽出来是为了能在 JVM 上断言字段名）。 */
    internal fun body(kind: String, content: String, contact: String): String = buildJsonObject {
        put("kind", kind)
        put("content", content)
        put("contact", contact)
    }.toString()

    /** **纯函数**：从错误响应里取出后端那句人话（取不到就给 [fallback]）。 */
    internal fun parseError(raw: String, fallback: String): String = runCatching {
        val root = json.parseToJsonElement(raw).jsonObject
        root["detail"]?.jsonPrimitive?.content.orEmpty()
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: fallback

    /**
     * 发一条反馈。
     *
     * @param kind `bug`（出问题了）/ `idea`（想要新功能）/ `other`
     */
    suspend fun send(kind: String, content: String, contact: String): Result =
        withContext(Dispatchers.IO) {
            val local = feedbackValidationError(content, contact)
            if (local != null) return@withContext Result.Fail(local)

            runCatching {
                val req = Request.Builder()
                    .url(ServerConfig.url(PATH))
                    .post(body(kind, content.trim(), contact.trim()).toRequestBody(mediaJson))
                    .build()
                client.newCall(req).execute().use { res ->
                    when {
                        res.isSuccessful -> Result.Ok
                        // 后端把原因写在 detail 里（限流 / 太长 / 空内容），照搬给用户看
                        else -> Result.Fail(
                            parseError(res.body?.string().orEmpty(), "没发出去（HTTP ${res.code}），过一会儿再试"),
                        )
                    }
                }
            }.getOrElse { Result.Fail(NETWORK_FAIL) }
        }
}
