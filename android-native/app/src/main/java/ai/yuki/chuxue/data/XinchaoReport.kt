package ai.yuki.chuxue.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 「Ta 的状态」上报器（v0.61.34，用户 2026-10-06 拍板的功能）。
 *
 * ## 它是什么
 * 当人设开关 [Persona.xinchaoEnabled] 打开时（调用方负责查这个开关）：
 *  · [register]    —— 保存人设时把人设名登记到服务器（网页上显示名字用，可重复调）；
 *  · [reportEvent] —— 每轮对话完成后上报 {用户的话, Ta 的回复}，服务器用它算 Ta 的状态。
 *
 * ## 三条边界（与 [Telemetry] 同一套纪律）
 * 1. **只发给 [ServerConfig.BASE_URL]**（编译期内置的自家服务器）——地址为空时零出网；
 * 2. **开关关 = 零上报**：调用方先查 [Persona.xinchaoEnabled] 再调本对象；
 * 3. **失败一律静默**：上报是增强，网络错误不该打扰对话（Result 返回，调用方不处理也不会崩）。
 *
 * ## 隐私边界（与 Telemetry 不同，要写清楚）
 * 这里**会**发对话文本 —— 这是功能本身（服务器要有内容才能算出 Ta 的状态）。
 * 触发条件只有一条：**用户在编辑页主动打开了开关**；没开的人设一个字都不出设备。
 * 鉴权用登录态 token（[Store.loadAuth]）；未登录时服务器会 401（静默失败，无副作用）。
 *
 * ## ⚠️ exchange 的格式是**服务器（情绪引擎）的契约**，不是用户文案
 * 引擎按「她说：… 他回：…」切分双方发言（interaction-rules.js 的 splitExchange），
 * 并据此自动分类（实测不带 interactionType 也能 applied）。
 * 这里的「她/他」是引擎的既有词汇 —— 不要按 App 的 Ta 化规范去改，改了它解析不到。
 */
object XinchaoReport {

    private val mediaJson = "application/json; charset=utf-8".toMediaType()

    /** 独立客户端：低配超时；fire-and-forget 类请求，不拖累聊天（与 Telemetry 同款思路）。 */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    /* ─────────────── 纯函数（JVM 可测） ─────────────── */

    /** 登记端点 URL；base 或 personaId 为空 → 空串（调用方直接跳过）。 */
    fun registerUrl(baseUrl: String, personaId: String): String =
        if (baseUrl.isBlank() || personaId.isBlank()) ""
        else "${baseUrl.trimEnd('/')}/xinchao/personas/$personaId/register"

    /** 事件端点 URL；同上。 */
    fun eventUrl(baseUrl: String, personaId: String): String =
        if (baseUrl.isBlank() || personaId.isBlank()) ""
        else "${baseUrl.trimEnd('/')}/xinchao/personas/$personaId/event"

    /**
     * **在场时间**（心跳）端点 URL（v0.61.52）；同上。
     *
     * 与 [eventUrl] 的区别：事件会带**对话正文**（`exchange`），心跳**一个字都不带** ——
     * 它只刷新"这个人此刻在不在"。App 启动 / 回前台时打一次。
     */
    fun heartbeatUrl(baseUrl: String, personaId: String): String =
        if (baseUrl.isBlank() || personaId.isBlank()) ""
        else "${baseUrl.trimEnd('/')}/xinchao/personas/$personaId/heartbeat"

    /** register 请求体：{"name": "..."}（名字允许空串——服务端容忍）。 */
    fun registerBody(name: String): String = buildJsonObject {
        put("name", name)
    }.toString()

    /**
     * event 请求体。
     *
     * exchange 格式见文件头（引擎契约）：
     *   `她说：{用户的话}\n他回：{Ta 的话}`。
     * 换行只是可读性，引擎会把空白压平；两个标签缺一不可。
     */
    fun eventBody(eventId: String, userText: String, assistantText: String, atIso: String): String =
        buildJsonObject {
            put("eventId", eventId)
            put("exchange", "她说：$userText\n他回：$assistantText")
            put("at", atIso)
        }.toString()

    /* ─────────────── 发送（失败静默，Result 由调用方决定忽略） ─────────────── */

    suspend fun register(baseUrl: String, token: String, personaId: String, name: String): Result<Unit> =
        post(registerUrl(baseUrl, personaId), token, registerBody(name))

    suspend fun reportEvent(
        baseUrl: String,
        token: String,
        personaId: String,
        eventId: String,
        userText: String,
        assistantText: String,
        atIso: String,
    ): Result<Unit> =
        post(eventUrl(baseUrl, personaId), token, eventBody(eventId, userText, assistantText, atIso))

    /**
     * 上报一次**在场时间**（v0.61.52）—— App 在「启动 / 回到前台」时打。
     *
     * 为什么需要它：用户开着 App 但**没说话**时，心潮那边只看得到 `lastConversationAt` 很旧，
     * 会把这段静默误判成"长期不在"，从而触发「Ta 主动来找我」。心跳把这个判断修正回来。
     * **不带任何正文**（body 是空对象）—— 这就是它和 [reportEvent] 的本质区别。
     */
    suspend fun heartbeat(baseUrl: String, token: String, personaId: String): Result<Unit> =
        post(heartbeatUrl(baseUrl, personaId), token, "{}")

    private suspend fun post(url: String, token: String, body: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            if (url.isEmpty() || token.isBlank()) {
                return@withContext Result.failure(IllegalStateException("未配置地址或未登录"))
            }
            runCatching {
                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $token")
                    .post(body.toRequestBody(mediaJson))
                    .build()
                client.newCall(request).execute().use { res ->
                    if (!res.isSuccessful) error("上报失败：HTTP ${res.code}")
                }
            }
        }
}
