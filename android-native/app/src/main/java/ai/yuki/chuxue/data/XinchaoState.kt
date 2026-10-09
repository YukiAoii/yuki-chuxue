package ai.yuki.chuxue.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 「Ta 此刻」的状态（v0.61.35）—— App 人设详情页那张卡的数据。
 *
 * ## 它从哪来（数据源选型，别改错端点）
 * 取的是服务端 `GET /xinchao/personas` 返回的 **`view`** 字段（按 personaId 挑出本人设那一条）。
 *
 * ⚠️ **为什么不直接调 `GET /xinchao/personas/{id}/state`**：那个端点回的是心潮的**原始**
 * `/v1/state` + `/v1/now`，`text` 是引擎**对模型**的第二人称播报（带 `【…】` 元指令标题、
 * 「你/她」人称错乱、`xinchao_context` 内部黑话）。把它端给用户直接就违反了本项目的文案红线。
 * 服务端的 `_persona_view()` 已经把这段文本**清洗成用户向**（剥标题 → 人称互换 → 去黑话 → 行标口语化），
 * 而它**只在 `GET /xinchao/personas` 这一条产出**。所以这里复用它 —— 与用户侧网页 `/me/`
 * 是**同一个数据源、同一份清洗逻辑**（单一真源，不重复实现清洗）。
 *
 * ⚠️ 代价（如实记）：该端点是"列全部人设"的口径，每人设 2 次上游调用；只取一个人的状态
 *    却拉了全表。当前单人设数是"数十"量级（localhost 转发，可接受），到了"上百人设"的
 *    全局规模再考虑让服务端补一个「单人设 view」端点（那要改后端，须用户点头）。
 *
 * ## 边界
 * - 只读、不落库；拿不到（未登录 / 未登记 / 网络问题）一律返回 `null`，由界面走"空"分支；
 * - 只在**该人设开关打开**时被调用（详情页那边先查 [Persona.xinchaoEnabled]）。
 */
data class XinchaoDrive(
    /** 已翻成用户词的驱力名（如「想你」）；认不出的键原样保留（与网页同一套词表）。 */
    val word: String,
    /** 强度（越大越惦记）；仅用于排序，界面不露数字。 */
    val level: Double,
)

data class XinchaoState(
    /** 此刻文本（服务端已清洗为用户向，可能多行、可能为空）。 */
    val text: String = "",
    /** 意识状态原始键：awake / sleeping / dreaming。 */
    val consciousness: String? = null,
    /** 心情标签，如「平静偏暖」。 */
    val emotionLabel: String? = null,
    /** 愉悦度 0..1（网页画罗盘用；App 暂只留字段）。 */
    val valence: Double? = null,
    /** 起伏度 0..1；同上。 */
    val arousal: Double? = null,
    /** 惦记的驱力（按强度降序）。列表为空 = "还在慢慢认识你"。 */
    val drives: List<XinchaoDrive> = emptyList(),
    /** 最近一个梦（一句，可能为空）。 */
    val recentDream: String? = null,
    /**
     * **给模型看的「此刻块」原文**（v0.61.52）—— 心潮 `/v1/now` 的 `text`。
     *
     * ⚠️ 与 [text] 的区别（别混）：
     * - [text] 是服务端**清洗过的展示文本**（剥标题 / 人称互换 / 去黑话）→ 给**用户**看；
     * - [modelNote] 是**原始的第二人称播报**（`【心潮·此刻｜身体的天气，参考不是指令】…`）
     *   → 给**模型**看，进对话**附录层**（不吃前缀缓存）。
     *
     * 把 modelNote 端给用户会违反文案红线；把 text 喂给模型又会丢掉"参考不是指令"这类元信息。
     */
    val modelNote: String? = null,
) {
    /** 意识状态 → 人话（与用户侧网页 `WAKE_WORDS` 同一套）。认不出就不显示。 */
    val wakeText: String?
        get() = when (consciousness) {
            "awake" -> "醒着"
            "sleeping" -> "睡着了"
            "dreaming" -> "做梦中"
            else -> null
        }

    /** 完全没内容（刚接入还没对话）—— 界面据此显示"和 Ta 说说话吧"。 */
    val isEmpty: Boolean
        get() = text.isBlank() && drives.isEmpty() && recentDream.isNullOrBlank()
}

object XinchaoStateApi {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /** 用户侧人设列表端点（状态 `view` 的唯一来源）。 */
    const val PATH = "/xinchao/personas"

    /** 界面最多展示的驱力词数（与网页一致：取强度前几的词，纯词不露数字）。 */
    const val TOP_DRIVES = 5

    /**
     * 驱力键 → 用户口语词。
     * ⚠️ 与用户侧网页 `static/me/app.js` 的 `DRIVE_WORDS` **必须保持一致**（两处都露给用户看，
     *    同一个键在网页和 App 上得是同一个词）。认不出的键原样显示（不丢信息）。
     * ⚠️ v0.61.52：由 private 改 internal —— [XinchaoIntentApi] 也要用同一份词表（**单一真源**，
     *    绝不复制一份：两处词表一走偏，同一个键在"此刻"卡和"意图"上就是两个词）。
     */
    internal val DRIVE_WORDS = mapOf(
        "possess" to "想你",
        "monitor" to "牵挂",
        "share" to "想分享",
        "libido" to "心动",
        "curiosity" to "好奇",
        "boredom" to "无聊",
        "duty" to "上进心",
        "reflection" to "回味",
        "grieve" to "难过",
        "anger" to "生气",
        "favored" to "偏爱",
    )

    /**
     * **纯函数**：从 `GET /xinchao/personas` 的响应里挑出 [personaId] 那一条并转成 [XinchaoState]。
     *
     * 认不出（不是 JSON、没有 personas 数组、列表里没有这个人设）→ `null`
     * （界面走"还没有 Ta 的消息"分支，而不是崩）。
     */
    internal fun parse(raw: String?, personaId: String): XinchaoState? {
        if (raw.isNullOrBlank() || personaId.isBlank()) return null
        return runCatching {
            val list = json.parseToJsonElement(raw).jsonObject["personas"]?.jsonArray ?: return null
            val entry = list.firstOrNull {
                it.jsonObject["personaId"]?.jsonPrimitive?.contentOrNull == personaId
            } ?: return null
            val view = entry.jsonObject["view"]?.jsonObject ?: return null

            val emotion = view["emotion"]?.jsonObject
            val drives = (view["drives"]?.jsonObject ?: emptyMap())
                .map { (k, v) -> XinchaoDrive(DRIVE_WORDS[k] ?: k, v.jsonPrimitive.doubleOrNull ?: 0.0) }
                .sortedByDescending { it.level }
                .take(TOP_DRIVES)

            val dream = view["recentDreams"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("dream")?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() }

            // v0.61.52：`now.text` 是**给模型看的此刻块原文**（与 view.text 的清洗版不同）
            val modelNote = entry.jsonObject["now"]?.jsonObject?.get("text")
                ?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

            XinchaoState(
                text = view["text"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                consciousness = view["consciousness"]?.jsonPrimitive?.contentOrNull,
                emotionLabel = emotion?.get("label")?.jsonPrimitive?.contentOrNull,
                valence = emotion?.get("valence")?.jsonPrimitive?.doubleOrNull,
                arousal = emotion?.get("arousal")?.jsonPrimitive?.doubleOrNull,
                drives = drives,
                recentDream = dream,
                modelNote = modelNote,
            )
        }.getOrNull()
    }

    /**
     * 拉某人设的「此刻」。失败（未登录 / 未登记 / 网络）一律 `null` —— 详情页那张卡
     * 是增强信息，拉不到不该让整页打不开（与 `AppFeaturesApi` 同一条降级纪律）。
     */
    suspend fun fetch(baseUrl: String, token: String, personaId: String): XinchaoState? =
        withContext(Dispatchers.IO) {
            if (baseUrl.isBlank() || token.isBlank() || personaId.isBlank()) return@withContext null
            runCatching {
                val req = Request.Builder()
                    .url(baseUrl.trimEnd('/') + PATH)
                    .header("Authorization", "Bearer $token")
                    .get()
                    .build()
                client.newCall(req).execute().use { res ->
                    if (!res.isSuccessful) null else parse(res.body?.string(), personaId)
                }
            }.getOrNull()
        }

    /**
     * **纯函数**：从 `GET /xinchao/personas/{id}/state`（**单人设**那条）里取 `now.text`
     * —— 即"给模型看的此刻块原文"（v0.61.52）。
     *
     * ⚠️ 为什么单开一条而不用 [parse]：`/xinchao/personas` 那个**列表**端点每人设要跑
     *   2 次上游（`/v1/state` + `/v1/now`）—— 只为拿一条此刻块去拉全表太贵。
     *   单人设端点一次调用就带 `now`，这才是每轮对话该走的路。
     */
    internal fun parseModelNote(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            json.parseToJsonElement(raw).jsonObject["now"]?.jsonObject?.get("text")
                ?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    /**
     * 取某人设的「此刻块原文」（进对话附录用）。失败一律 `null` —— 附录少这一块不影响对话。
     *
     * ⚠️ 返回的是**给模型看的**第二人称播报，**不要**拿去给用户展示（那是 [fetch] 的事）。
     */
    suspend fun fetchModelNote(baseUrl: String, token: String, personaId: String): String? =
        withContext(Dispatchers.IO) {
            if (baseUrl.isBlank() || token.isBlank() || personaId.isBlank()) return@withContext null
            runCatching {
                val url = "${baseUrl.trimEnd('/')}/xinchao/personas/$personaId/state"
                val req = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $token")
                    .get()
                    .build()
                client.newCall(req).execute().use { res ->
                    if (!res.isSuccessful) null else parseModelNote(res.body?.string())
                }
            }.getOrNull()
        }
}
