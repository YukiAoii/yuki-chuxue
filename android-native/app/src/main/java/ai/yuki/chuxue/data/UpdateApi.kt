package ai.yuki.chuxue.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 检测更新（v0.50.0）—— 读后端 `GET /api/app/version`。
 *
 * ## 与 AuthApi 的关系
 * 走的是同一套范式（独立 OkHttpClient + `withContext(Dispatchers.IO)` +
 * `runCatching`），但**取舍相反**：
 * 更新检查是**附加信息**，失败不该让用户看到任何东西 —— 所以
 * **失败一律返回 null，不抛异常、不报错**。用户没网时打开 App
 * 不该弹一个"检查更新失败"。
 *
 * ## 超时取 6s/8s
 * 它在**启动路径**上。用账号那套 10s/15s 会让冷启动多等一截，
 * 而更新检查晚几秒知道完全没关系。
 *
 * ## 端点前缀是 `/api`，不是 `/api/v1`
 * ⚠️ 后端有三套前缀：`/api/admin/…`（后台）、`/api/v1/…`（账号）、
 * **`/api/app/…`（客户端公开信息，无需鉴权）**。更新检查属于第三类
 * —— 它在启动时调用，那时用户可能还没登录，要求令牌等于这个功能
 * 对大多数（不注册的）用户不可用。
 *
 * ⚠️ 上面这几行**不能写成带星号的通配路径**（如 `/api/admin/` 后跟 `*`）：
 * Kotlin 的块注释**会嵌套**，注释里出现「斜杠 + 星号」会开启一层嵌套注释
 * 并吃掉 KDoc 的结束符，报 `Unclosed comment`。
 * 本项目在别处已经踩过两次（见 `项目交接记录` 坑 #14），这是第三次。
 */
object UpdateApi {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    /** 客户端公开信息接口的前缀（与后端的 `API_PREFIX` 对应）。 */
    private const val APP_API = "/api/app"

    /**
     * **纯函数**：解析 `GET /api/app/version` 的响应。
     *
     * 抽成纯函数是为了能在 JVM 上测 —— 网络那一层测不了（本机无设备），
     * 但"字段名改了""`latest` 是 null""返回畸形 JSON"这些**最常见也最容易静默出错**
     * 的情况可以全部钉死。
     *
     * @return `null` = 服务端没有版本信息（首次部署）**或**响应不可解析。
     *         两种情况调用方都该"什么都不做"，所以**不必区分**。
     */
    fun parseRelease(raw: String): RemoteRelease? = runCatching {
        val root = json.parseToJsonElement(raw).jsonObject
        // ⚠️ `latest` 可能是 JSON null（后端在库里没有版本行时就是这么返回的），
        //    也可能是字段缺失。两者都该得到 null，而不是抛异常。
        val latest = root["latest"]?.takeIf { it !is kotlinx.serialization.json.JsonNull }
            ?.jsonObject ?: return@runCatching null

        val code = latest["version_code"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: return@runCatching null
        RemoteRelease(
            versionCode = code,
            versionName = latest["version_name"]?.jsonPrimitive?.content.orEmpty(),
            notes = latest["notes"]?.jsonPrimitive?.content.orEmpty(),
            // ⚠️ 这里取的是**版本行自己的** force 标记，不是服务端算好的那个顶层 force。
            //    因为 VersionPolicy 会用本机 localCode 重新算一遍（含 minSupportedCode），
            //    两者口径一致；取行内值可避免"服务端算了、客户端又信了"的双重依赖。
            force = latest["force"]?.jsonPrimitive?.content == "true" ||
                latest["force"]?.jsonPrimitive?.content == "1",
            minSupportedCode = latest["min_supported_code"]?.jsonPrimitive?.content
                ?.toIntOrNull() ?: 0,
            apkUrl = latest["apk_url"]?.jsonPrimitive?.content.orEmpty(),
            apkSize = latest["apk_size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
        )
    }.getOrNull()

    /**
     * 查一次最新版本。**失败返回 null**（不抛）——
     * 调用方据此"什么都不做"，这就是更新检查该有的降级方式。
     *
     * @param localCode 本机 `BuildConfig.VERSION_CODE`。带上它服务端能顺手下发 force，
     *                  客户端仍会用 [VersionPolicy] 独立复算一次。
     */
    /**
     * **纯函数**：解析 `GET /api/app/changelog` 的响应。
     *
     * 与 [parseRelease] 同一个理由抽成纯函数：网络层在本机测不了，
     * 但"字段改名""items 缺失""混进一条缺版本的脏数据"这些最容易静默出错的情况
     * 可以在 JVM 上钉死。
     *
     * ⚠️ 单条脏数据**跳过**而不是整批失败：日志是展示性的，
     * 一条解析不了不该让用户看不到全部历史。
     */
    fun parseChangelog(raw: String): List<ChangelogEntry> = runCatching {
        val root = json.parseToJsonElement(raw).jsonObject
        val arr = root["items"]?.jsonArray ?: return@runCatching emptyList()
        arr.mapNotNull { el ->
            val o = el.jsonObject
            val code = o["version_code"]?.jsonPrimitive?.content?.toIntOrNull()
                ?: return@mapNotNull null
            ChangelogEntry(
                versionCode = code,
                versionName = o["version_name"]?.jsonPrimitive?.content.orEmpty(),
                notes = o["notes"]?.jsonPrimitive?.content.orEmpty(),
                force = o["force"]?.jsonPrimitive?.content == "true" ||
                    o["force"]?.jsonPrimitive?.content == "1",
                publishedAt = o["published_at"]?.jsonPrimitive?.content.orEmpty(),
            )
        }
    }.getOrElse { emptyList() }

    /**
     * 拉取更新日志（「关于」页的更新日志入口用）。
     *
     * ⚠️ 失败返回**空列表**而不是抛：这一页是"用户想看看更新过什么"，
     * 拿不到就显示"暂时取不到"，不该把弹窗变成一个错误页。
     */
    suspend fun changelog(): List<ChangelogEntry> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(ServerConfig.BASE_URL + APP_API + "/changelog").get().build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@use emptyList()
                parseChangelog(res.body?.string().orEmpty())
            }
        }.getOrElse { emptyList() }
    }

    /**
     * 查一次最新版本。**失败返回 null**（不抛）——
     * 调用方据此"什么都不做"，这就是更新检查该有的降级方式。
     *
     * @param localCode 本机 `BuildConfig.VERSION_CODE`。带上它服务端能顺手下发 force，
     *                  客户端仍会用 [VersionPolicy] 独立复算一次。
     */
    suspend fun check(localCode: Int): RemoteRelease? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url(ServerConfig.url("$APP_API/version?code=$localCode"))
                .get()
                .build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@withContext null
                parseRelease(res.body?.string().orEmpty())
            }
        }.getOrElse { null }
    }
}
