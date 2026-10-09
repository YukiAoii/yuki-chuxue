package ai.yuki.chuxue.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * **服务端功能开关**（v0.58.0）。
 *
 * ## 用户的原话
 * 「当后台全局的通用设定开关是关闭状态的时候，编辑和创建人设界面就没有通用人设开关」。
 *
 * ## ⚠️ 两条边界
 * ① 它只决定**要不要展示**那个开关，**不改用户已有的选择** ——
 *    关掉之后，已经开了通用设定的老人设照旧生效。远程顺手改用户的设定是最招骂的实现。
 * ② 拿不到时**按"开"处理**（[DEFAULT]）。反过来的话，服务端一抖，
 *    所有人的通用设定开关就"消失"了 —— 那看起来像 App 坏了，而不是像服务端坏了。
 */
data class AppFeatures(val personaGlobalPrefix: Boolean) {
    companion object {
        /** 拉不到时的兜底：一切照旧（与这个开关存在之前的行为一致）。 */
        val DEFAULT = AppFeatures(personaGlobalPrefix = true)
    }
}

object AppFeaturesApi {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    private const val PATH = "/api/app/features"

    /** **纯函数**：解析 `GET /api/app/features`。认不出就返回 [AppFeatures.DEFAULT]。 */
    internal fun parse(raw: String?): AppFeatures {
        if (raw.isNullOrBlank()) return AppFeatures.DEFAULT
        return runCatching {
            val root = json.parseToJsonElement(raw).jsonObject
            val v = root["persona_global_prefix"]?.jsonPrimitive?.content
            AppFeatures(personaGlobalPrefix = v != "false" && v != "0")
        }.getOrElse { AppFeatures.DEFAULT }
    }

    suspend fun fetch(): AppFeatures = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(ServerConfig.url(PATH)).get().build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) AppFeatures.DEFAULT else parse(res.body?.string())
            }
        }.getOrElse { AppFeatures.DEFAULT }
    }
}
