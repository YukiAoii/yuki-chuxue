package ai.yuki.chuxue.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.random.Random
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 使用统计上报 —— 管理后台仪表盘的数据来源。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * 这是个**可选**行为，而且是**只发给用户自己的服务器**
 * ═══════════════════════════════════════════════════════════════════════════
 * 它存在的唯一理由：后台的「设备与数据」页要显示"有多少台设备在用、用了多少"——
 * 这些数字**只能由客户端提供**（服务端没有别的渠道知道）。
 *
 * 三条边界，缺一不可：
 *
 * 1. **只发给 `AppSettings.serverUrl` 指向的地址** —— 那是用户自己填的服务器。
 *    不填 → 这个模块**一行网络代码都不会执行**。
 * 2. **可关**：设置里的开关（[AppSettings.telemetryEnabled]）。
 * 3. **绝不包含任何对话内容**。上报的只有**计数**（几段会话、几条消息、多少 token），
 *    没有正文、没有人设文本、没有记忆内容。看下面 [TelemetrySnapshot] 的字段就知道了 ——
 *    它连一个自由文本字段都没有（"机型"是系统属性）。
 *
 * ⚠️ 失败**一律静默**：上报是增强，不该用一次网络错误打扰用户。这一点与
 * `DeepSeekClient` 的失败处理正好相反（那里的失败必须让人看见 —— 那是用户在等的事）。
 *
 * ⚠️ 服务端的 `/api/app/report` **不校验账号令牌**：绝大多数用户不注册，
 * 而统计不该以"必须注册"为前提。代价是任何人都能往这张表里写数据 ——
 * 自用场景可以接受（最坏情况是仪表盘数字偏大，不影响任何功能）。
 */
object Telemetry {

    /**
     * 上报的内容。
     *
     * **刻意全是计数与系统属性** —— 没有任何用户输入的自由文本。
     * 加字段时请守住这条：一旦这里出现"会话标题""人设内容"之类，
     * 这个模块就从"统计"变成了"上传聊天记录"，性质完全不同。
     */
    data class Snapshot(
        val deviceId: String,
        /**
         * 登录态 access token（v0.61.23）。
         *
         * 后台用它把"设备"与"账号"对上（服务端 `user_devices`）。
         * ⚠️ 未登录 / 没注册的用户传空串 —— 服务端按匿名设备收，老行为一字不变
         *    （这就是"兼容老用户"在客户端这一侧的样子）。
         * ⚠️ 它是**凭据**、不是用户内容：与 api_key 同性质，且只发给用户自己的服务器。
         */
        val token: String = "",
        val model: String = "",
        val androidVersion: String = "",
        val appVersion: String = "",
        val personaCount: Int = 0,
        val sessionCount: Int = 0,
        val messageCount: Int = 0,
        val memoryCount: Int = 0,
        val hitTokens: Int = 0,
        val missTokens: Int = 0,
    )

    /**
     * 把 [baseUrl] 规范化成上报端点。
     *
     * 用户在设置里手填地址，末尾有没有斜杠、有没有多余的空白都是常态 ——
     * 这里一次处理掉，别让"少打一个斜杠"变成"上报悄悄不工作"。
     * （纯函数，有单测。）
     */
    fun reportUrl(baseUrl: String): String {
        val base = baseUrl.trim().trimEnd('/')
        if (base.isEmpty()) return ""
        return "$base/api/app/report"
    }

    /* ── 错峰（v0.61.23）──
     *
     * 为什么需要它：上报挂在"App 启动"上。发一次新版本，几万台设备可能在同一分钟内先后
     * 启动，请求就会堆成一小段峰值砸向服务器（用户原话："1w 个用户一起上报服务器不卡死了"）。
     * 让每台设备在启动后**随机等一会儿**，峰值就被摊平。
     *
     * 区间取得偏大（20s~180s）：上报是统计，晚三分钟毫无影响；窗口越宽，摊得越平。
     */
    const val STAGGER_MIN_MS = 20_000L
    const val STAGGER_MAX_MS = 180_000L

    /** 本次上报前该等多久（纯函数，有单测）：[STAGGER_MIN_MS, STAGGER_MAX_MS] 内均匀随机。 */
    fun staggerDelayMs(random: Random = Random.Default): Long =
        random.nextLong(STAGGER_MIN_MS, STAGGER_MAX_MS + 1)

    /** 请求体 JSON。纯函数，单测钉住字段名 —— 字段名改了服务端就收不到。 */
    fun toJson(s: Snapshot): String = buildJsonObject {
        put("device_id", s.deviceId)
        // 始终输出（空串也发）：服务端"有没有这个字段"会分叉出两条解析路，
        // 固定给一个空串，它只有一条路（空 → 匿名）。
        put("token", s.token)
        put("model", s.model)
        put("android_version", s.androidVersion)
        put("app_version", s.appVersion)
        put("persona_count", s.personaCount)
        put("session_count", s.sessionCount)
        put("message_count", s.messageCount)
        put("memory_count", s.memoryCount)
        put("hit_tokens", s.hitTokens)
        put("miss_tokens", s.missTokens)
    }.toString()

    /** 上报专用客户端：低频、短超时，不与对话流共用连接池（上报慢不该拖累聊天）。 */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 发一次上报。
     *
     * @return 成功/失败 —— 调用方**不需要**处理失败（静默即可），
     *         返回它只是为了"想测的时候能测"。
     */
    suspend fun report(baseUrl: String, snapshot: Snapshot): Result<Unit> = withContext(Dispatchers.IO) {
        val url = reportUrl(baseUrl)
        if (url.isEmpty()) return@withContext Result.failure(IllegalArgumentException("未配置服务器地址"))

        runCatching {
            val body = toJson(snapshot).toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder().url(url).post(body).build()
            client.newCall(request).execute().use { res ->
                if (!res.isSuccessful) {
                    error("上报失败：HTTP ${res.code}")
                }
            }
        }
    }
}
