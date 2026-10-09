package ai.yuki.chuxue.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 服务器下发的**免费分组**（v0.58.0，用户要的「Yuki初雪Pro」）。
 *
 * ## 为什么它不是一个普通的本地分组
 * 用户原话：「增加一个由我后端服务器下发的动态可在后端修改的免费分组」——
 * 也就是说这个分组的地址和密钥**由我一个人在后台维护**，
 * 所有用户的客户端只是"用"它，不管理它。所以：
 * · 它不能删（删了用户就没得用了）；
 * · 它的地址/密钥**在界面上不显示、不可改**（用户改坏了只会把自己卡死，
 *   而且那是我的密钥，不该被改）；
 * · 但**模型勾选与上下文参数仍然归用户**（那是他自己的偏好）。
 */
/**
 * 免费分组里的一**条**模型。
 *
 * ## 为什么是两条字段而不是一个字符串（用户 2026-10-04 问出来的）
 * 原来只有一个名字，于是"后台改了名字"= "客户端请求时发那个改过的名字"——
 * 服务商不认识，请求直接失败。拆开之后各管一件事：
 * · [id]    —— **真正发给服务商**的那个名字，必须真实存在；
 * · [label] —— **只给用户看**的显示名，随便改，永远不影响请求；空 = 显示 [id]。
 */
data class FreeModel(val id: String, val label: String = "") {
    /** 界面上该显示的名字。 */
    val shown: String get() = label.ifBlank { id }
}

data class FreeGroup(
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val notice: String,
    /**
     * 这条免费分组**能用哪些模型**（v0.61.20）。
     *
     * ⚠️ 由**后端**给出，不是客户端"拉模型列表取第一个"：
     * 免费额度是后端出的钱、用哪家、哪个模型更划算，只有后端说了算；
     * 而"列表第一个"的顺序由服务商决定，随时会变，还多一条会失败的请求。
     *
     * 空表 = 后端没配模型 → 用户仍可在这条分组上自己勾（勾选与上下文参数本来就归用户）。
     */
    val models: List<FreeModel> = emptyList(),
)

/**
 * 拉取服务端下发的免费分组。
 *
 * ⚠️ **与更新检查同一族语义：失败一律静默返回 null**（不抛、不弹错）。
 *    它跑在启动路径上；服务端挂了不该让 App 打不开设置页。
 *    拿不到时用户看到的就是"没有那条免费分组"，其余功能照常。
 *
 * ⚠️ 不需要登录/令牌：不注册也能用的应用，这条也得能用。
 */
object FreeGroupApi {

    /**
     * 拉取结果**三态**（v0.58.0）。
     *
     * ⚠️ 这里做成三态而不是"可空"，修的正是用户报的那个 bug：
     *    「老用户看不到后端下发的免费 Pro 分组」。
     *    原来 `fetch()` 把两种完全不同的情况折叠成同一个 `null`：
     *    · 服务端**明确说关掉** → 本地那条该摘掉；
     *    · **压根没问到**（接口没部署 / 网络抖 / 响应坏）→ **绝不能动本地数据**。
     *    折叠的后果是：**一次网络抖动就能让一个已经摆在那儿的官方分组凭空消失**，
     *    用户只看到"怎么没有了"，没有任何线索。
     *
     * ⚠️ 这类 bug 只在出错的那一侧出现，正常路径永远复现不了 —— 所以必须在**类型上**分开，
     *    靠注释和调用方的自觉是不够的。
     */
    sealed interface State {
        data class Ok(val group: FreeGroup) : State

        /** 服务端**明确**说这个功能关掉了 → 本地那条该摘掉。 */
        data object Disabled : State

        /** 没问到（网络 / 404 / 坏数据 / 服务端配错）→ **不许动本地数据**。 */
        data object Unreachable : State
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private const val PATH = "/api/app/free-group"

    /**
     * **纯函数**：解析 `GET /api/app/free-group` → 三态。
     *
     * 判定规则（每条都有理由，别随手改）：
     * · 明确 `enabled:false` / `0` → [State.Disabled]；
     * · 其余拿不出完整可用配置的（空响应、坏 JSON、缺地址、缺密钥）→ [State.Unreachable]。
     *   ⚠️ 服务端**漏填密钥**算服务端配错，不是"功能关掉了"——
     *      这时候该保持现状等它配好，而不是把用户手上那条悄悄撤掉。
     */
    internal fun parseState(raw: String?): State {
        if (raw.isNullOrBlank()) return State.Unreachable
        return runCatching {
            val root = json.parseToJsonElement(raw).jsonObject
            fun str(vararg keys: String): String = keys.firstNotNullOfOrNull { k ->
                root[k]?.let { el ->
                    if (el is JsonNull) null else el.jsonPrimitive.content.takeIf { it.isNotBlank() }
                }
            }.orEmpty()

            val enabledEl = root["enabled"]
            if (enabledEl != null && enabledEl !is JsonNull) {
                val v = enabledEl.jsonPrimitive.content
                if (v == "false" || v == "0") return@runCatching State.Disabled
            }

            val baseUrl = str("base_url", "baseUrl").trim()
            val apiKey = str("api_key", "apiKey").trim()
            if (baseUrl.isBlank() || apiKey.isBlank()) return@runCatching State.Unreachable

            State.Ok(
                FreeGroup(
                    name = str("name").trim().ifBlank { "Yuki初雪Pro" },
                    baseUrl = baseUrl,
                    apiKey = apiKey,
                    notice = str("notice").trim(),
                    // ⚠️ 模型列表是**可选**的：没有它照样是可用分组（用户能自己勾），
                    //    所以这里坏数据一律降级成空表，绝不让它把整条分组判成不可用。
                    models = runCatching {
                        root["models"]?.jsonArray?.mapNotNull { parseModel(it) }
                    }.getOrNull().orEmpty(),
                ),
            )
        }.getOrElse { State.Unreachable }
    }

    /** 取字符串；`null` / JSON null / 类型不对都当空串。 */
    private fun JsonElement?.text(): String =
        (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.trim().orEmpty()

    /**
     * 一条模型项的三种写法都要能吃（后端会统一成对象，但客户端不该依赖那一点）：
     * · `{"id":"deepseek-flash","label":"闪电"}` —— 规范形；
     * · `"deepseek-flash"`                     —— 老写法，id 即显示名；
     * · `"deepseek-flash=闪电"`                 —— 后台输入框里的写法。
     *
     * 单条坏数据只丢这一条，不影响整张表。
     */
    private fun parseModel(el: JsonElement): FreeModel? = runCatching {
        val obj = el as? JsonObject
        if (obj != null) {
            val id = obj["id"].text()
            if (id.isEmpty()) null else FreeModel(id, obj["label"].text())
        } else {
            val raw = el.text()
            if (raw.isEmpty()) {
                null
            } else {
                val parts = raw.split("=", limit = 2)
                FreeModel(parts[0].trim(), parts.getOrElse(1) { "" }.trim())
            }
        }
    }.getOrNull()

    /** 拉一次。**从不抛**：任何异常都归到 [State.Unreachable]。 */
    suspend fun fetchState(): State = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(ServerConfig.url(PATH)).get().build()
            client.newCall(req).execute().use { res ->
                // ⚠️ 非 2xx（含 404：接口还没部署）→ Unreachable，**不是** Disabled
                if (!res.isSuccessful) State.Unreachable else parseState(res.body?.string())
            }
        }.getOrElse { State.Unreachable }
    }
}
