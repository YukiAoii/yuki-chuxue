package ai.yuki.chuxue.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.UUID

/**
 * 服务商类型（v0.51.0）。
 *
 * ## ⚠️ Phase 1 只实现 [OPENAI_COMPAT]
 * [ANTHROPIC] 与 [GEMINI] 是**预留值**：它们的请求体、SSE 格式、认证头都与
 * OpenAI 兼容族不同（见 `_refs/Operit` 的 ClaudeProvider / GeminiProvider，
 * 那两家各写了一整套流解析）。一次全做会把"支持大多数第三方"这件正事拖住 ——
 * 而市面上绝大多数第三方（通义 / 智谱 / Kimi / SiliconFlow / GLM / 豆包 /
 * OpenRouter / 各种中转）**都是 OpenAI 兼容**的。
 *
 * ⚠️ 预留它们的价值在于**数据结构现在就不必再改**：将来加实现时，
 * 已存的用户数据不用迁移。
 */
object ProviderType {
    const val OPENAI_COMPAT = "openai_compat"
    const val ANTHROPIC = "anthropic"
    const val GEMINI = "gemini"

    /** 界面上能选、且**真的能跑**的类型。 */
    val IMPLEMENTED = listOf(OPENAI_COMPAT)

    /** 界面上展示的名字。 */
    fun label(type: String): String = when (type) {
        OPENAI_COMPAT -> "OpenAI 兼容"
        ANTHROPIC -> "Anthropic（暂不支持）"
        GEMINI -> "Gemini（暂不支持）"
        else -> type
    }

    fun isImplemented(type: String): Boolean = type in IMPLEMENTED
}

/**
 * 一个**连接分组**（v0.51.0）—— 用户说的「分组」。
 *
 * ## 它解决什么
 * 原来是**一组全局配置**（`AppSettings` 的 apiKey / baseUrl / model 三项）。
 * 想同时用「DeepSeek 官方」和「某个中转」就得反复改同一组值 ——
 * 而且改完就回不去了（旧值被覆盖）。
 * 分组之后：每个分组各存一套配置 + 各自勾选的模型，互不干扰。
 *
 * ## 为什么勾选的是**模型子集**而不是整个列表
 * 拉取会返回几十上百个模型（很多是embedding / 语音 / 旧版），
 * 全列进聊天里的选择菜单会没法用。用户勾的那几个才是"我会用的"。
 *
 * ## ⚠️ apiKey 是**明文**，与既有 `AppSettings.apiKey` 同一存法
 * 这里没有额外加密 —— 不是疏忽，是**不制造虚假的安全感**：
 * 同一台设备上，同一个密钥在 `AppSettings` 里本来就是明文（SharedPreferences），
 * 对一个密钥用两种存法只会让人以为"分组里的更安全"。
 * 真正的边界是设备本身（数据只在手机本地，见项目定位）。
 */
data class ProviderGroup(
    val id: String,
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val providerType: String = ProviderType.OPENAI_COMPAT,
    /** 用户勾选过的模型名（顺序即展示顺序）。⚠️ 存的是**真名**（发给服务商的那个）。 */
    val checkedModels: List<String> = emptyList(),
    /**
     * **模型的显示名**：`真名 → 给用户看的名字`（v0.61.20）。
     *
     * ## 为什么要有它（用户 2026-10-04 问出来的那个坑）
     * `checkedModels` 里的名字有两个用途：**显示**给用户看、**发出去**给服务商认。
     * 合成一个字段时，"后台把名字改好看点"= "请求时发那个改过的名字"—— 服务商不认，直接失败。
     * 拆开之后：
     * · `checkedModels` 永远是真名 —— **请求的字节不变**；
     * · 这张表只管显示 —— 改它一个字节都不会影响任何请求。
     *
     * ⚠️ 只对**服务端下发的托管分组**填写（免费分组）；用户自己建的分组没人给他配显示名。
     * 空表 / 查不到 = 显示真名本身，行为与本字段存在之前一致。
     */
    val modelLabels: Map<String, String> = emptyMap(),
    val createdAt: Long = 0L,
    /**
     * **是否发送 DeepSeek 那套思考参数**（`thinking` / `reasoning_effort`，v0.51.0）。
     *
     * ## 为什么这是一个**开关**而不是按域名猜
     * `thinking` / `reasoning_effort` 是 DeepSeek 的扩展字段。严格一些的网关
     *（OpenAI 本体、通义、智谱等）收到**不认识的顶层字段**会直接 400 ——
     * 用户的体验是"地址和密钥都对，就是报错"。
     *
     * 但反过来"认不出来就不发"更糟：用**中转 DeepSeek**的用户（域名不含 deepseek）
     * 会**静默**失去思考能力 —— 回答变差，而界面上没有任何提示。
     * 本项目的纪律是「宁可大声失败，不要安静咽下」，所以：
     *
     * · 默认 `true` = **与本字段存在之前的行为逐字节一致**（升级用户不受影响）；
     * · 第三方报参数错误时，用户在分组详情页关掉它即可；
     * · 请求 400 时界面会给一句**指到这个开关**的话。
     */
    // 用户 2026-10-02：「发送思考参数默认关闭」——**新建**的分组不再默认发
    // thinking / reasoning_effort。
    // ⚠️ 反序列化那处（`getOrElse { true }`）**不动** —— 那是给「存这个字段之前
    //    写下的老分组」用的兼容护栏，改它会让升级上来的人静默失去思考能力。
    val sendThinkingParams: Boolean = false,
    /**
     * **这一组模型的上下文窗口**（token 数）；`null` = 没填，按默认估算。
     *
     * ## 为什么知识放在分组上、而不是写死在代码里
     * 窗口决定压缩触发判定的**分母** —— 填错的两个后果都很实在：
     * · 填太大 → 该压不压，一路聊到撞服务商上限、请求报错；
     * · 填太小 → 不该压猛压，每一轮都在白花压缩的钱。
     *
     * 而"某个模型窗口多大"是**用户手上的知识**（服务商官网页写着），不是我们能猜的：
     * 参考实现（`_refs/Tianshu`）把这层写成"`contextWindow` 必须来自解析出的模型配置"，
     * 它自己维护一张模型目录；本项目没有目录，最短的等价物就是**让用户填一个数**。
     *
     * ⚠️ 用户显式填的值**优先于**按模型名猜出来的档（名字里带 `1m`/`long` → 1M）。
     * 猜是我们的事后推测，填是事实。
     */
    val contextWindow: Int? = null,
    /**
     * **模型记忆上下文**（token 数）—— 决定"什么时候开始压缩"；`null` = 没填。
     *
     * ## 与 [contextWindow] 的分工（用户 2026-09-30 要求拆成两个）
     *
     * | | 是什么 | 填错的后果 |
     * |---|---|---|
     * | [contextWindow]（**模型 API 上下文**） | 服务商真正允许的最大窗口 | 填太大 → 聊到撞上限、请求直接报错 |
     * | [memoryContextWindow]（**模型记忆上下文**） | 你希望模型"记着"多少 —— 压缩触发的分母 | 填太大 → 该压不压；填太小 → 猛压、白花压缩钱 |
     *
     * ⚠️ **不填就回落到 [contextWindow]**（老分组只有前者）—— 于是升级上来的用户
     * **压缩时机一点不变**。两者都空，才走"按模型名猜"的默认估算。
     *
     * ⚠️ 它**不进 messages**（纯本地配置），拆这个**不影响前缀缓存**；
     * 但它决定**压缩时机**，而压缩本身会让那一次请求的前缀断一次（既有行为）。
     */
    val memoryContextWindow: Int? = null,
    /**
     * **这个分组由服务器托管**（v0.58.0，如「Yuki初雪Pro」）。
     *
     * 托管分组的三条硬规则（都在界面层实现，见 `ProviderGroupEditScreen`）：
     * ① **不可删除**；② 名称 / 地址 / 密钥**不显示、不可改**；③ 用户仍可拉模型、调上下文。
     *
     * ⚠️ 密钥**仍然存在本地** —— 否则发不出请求。所以这里的"不可见"是**界面上的不可见**，
     *    不是密码学意义上的保密（与 `apiKey` 明文存的理由同源：真正的边界是设备本身）。
     *    把它说成"看不到"就够了，不要声称"取不出来"。
     */
    val managed: Boolean = false,
    /**
     * 服务端给的**一句话说明**（只有托管分组有）。空串 = 没给，界面用兜底文案。
     *
     * ⚠️ 跟着分组存，而不是单开一个 StateFlow：它是**这一条分组的一部分**，
     *    分开存会出现"分组还在、说明丢了"或者更糟的"换了分组、说明没换"。
     */
    val notice: String = "",
) {
    /**
     * 拿这个分组造一份 `AppSettings`，好复用**既有**的拉取/测试/发消息实现。
     *
     * ⚠️ 这是本设计的省力点：`DeepSeekClient.listModels` / `testConnection`
     * 本来就接受 `AppSettings`（它只是 "baseUrl + key + model" 的载体），
     * 所以分组**不需要**任何新的网络代码。
     *
     * @param model 具体用哪个模型（没勾过就留空 —— 拉取与测试都不需要它）
     */
    fun toSettings(model: String = checkedModels.firstOrNull().orEmpty()): AppSettings =
        AppSettings(
            apiKey = apiKey,
            baseUrl = baseUrl,
            model = model,
        )

    /** 展示用：这个分组现在有几个可用模型。 */
    val modelCount: Int get() = checkedModels.size

    /**
     * **压缩判定该用哪个窗口**：记忆上下文 → API 上下文 → `null`（走"按模型名猜"的默认）。
     *
     * ⚠️ 中间那一跳是**给老用户留的**：老分组只有 `contextWindow`，
     * 于是升级后**压缩时机一点不变**。抽成属性，是为了能在 JVM 上直接钉死这条回落链。
     */
    val effectiveMemoryWindow: Int? get() = memoryContextWindow ?: contextWindow
}

/**
 * 分组的**存取与迁移**（v0.51.0）。
 *
 * ## 为什么存 SharedPreferences 而不是新建 Room 表
 * 分组是**配置**，不是用户数据 —— 它的读写都是"整份替换"，
 * 没有查询、没有关联、没有分页。为它加一张表就要加一次 Room 迁移，
 * 而迁移是**唯一会损坏用户聊天记录**的操作（本项目红线）。
 * 用一次迁移的代价换一个 KV 就能装下的东西，不划算。
 *
 * ⚠️ 对比：**会话级的模型选择**（`Session.model`）必须进 Room ——
 * 它跟着会话走、要参与备份/恢复，那是用户数据。
 *
 * ## 迁移：旧扁平配置 → 一个默认分组
 * 升上来的用户，`AppSettings` 里已经有一套 apiKey/baseUrl/model。
 * 不迁移的话，他们打开连接设置会看到**空的** —— 而聊天还在用旧配置，
 * 两边对不上。所以首次读取时若"没有分组但有旧配置"，就用旧配置造一个
 * 「默认」分组（并把它标记为当前）。
 */
object ProviderGroups {

    /** 语言无关的编解码 —— 存的是数组，形状见 [encode]。 */
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 默认分组在迁移时的名字。 */
    const val DEFAULT_GROUP_NAME = "默认"

    /**
     * **托管分组的固定 id**（v0.58.0）—— 服务端下发的那个「Yuki初雪Pro」。
     *
     * ⚠️ 必须是**固定常量**而不是每次 `newId()`：否则每次启动都会新加一条，
     *    用户的连接设置里会堆出一排同名的「Yuki初雪Pro」。
     */
    const val MANAGED_ID = "yuki-pro"

    fun newId(): String = UUID.randomUUID().toString().take(8)

    /**
     * 编码成 JSON 数组。
     *
     * 手写而不是 `@Serializable`：本项目**没有任何** `@Serializable` 数据类
     * （grep 全仓 0 命中），引入它意味着多一个编译器插件参与构建 ——
     * 为一个 KV 不值得。而且手写能把"字段缺省怎么办"写死在这里。
     */
    fun encode(groups: List<ProviderGroup>): String =
        buildJsonArray {
            groups.forEach { g ->
                add(
                    buildJsonObject {
                        put("id", g.id)
                        put("name", g.name)
                        put("baseUrl", g.baseUrl)
                        put("apiKey", g.apiKey)
                        put("providerType", g.providerType)
                        putJsonArray("checkedModels") { g.checkedModels.forEach { add(it) } }
                        // 空表不写（老分组/自建分组本就没有）—— 少写一个字段就少一份解码风险
                        if (g.modelLabels.isNotEmpty()) {
                            putJsonObject("modelLabels") {
                                g.modelLabels.forEach { (k, v) -> put(k, v) }
                            }
                        }
                        put("createdAt", g.createdAt)
                        put("sendThinkingParams", g.sendThinkingParams)
                        g.contextWindow?.let { put("contextWindow", it) }
                        g.memoryContextWindow?.let { put("memoryContextWindow", it) }
                        put("managed", g.managed)
                        if (g.notice.isNotBlank()) put("notice", g.notice)
                    },
                )
            }
        }.toString()

    /**
     * 解码。**坏数据一律降级为空列表**，不抛异常 ——
     * 它跑在设置页的启动路径上，一份坏 JSON 不该让整页打不开。
     * 单条坏记录会被跳过，其余照常读出（尽量保住用户配好的那些分组）。
     */
    fun decode(raw: String?): List<ProviderGroup> {
        if (raw.isNullOrBlank()) return emptyList()
        val arr = runCatching { json.parseToJsonElement(raw).jsonArray }.getOrNull() ?: return emptyList()
        return arr.mapNotNull { el ->
            runCatching {
                val o = el.jsonObject
                val baseUrl = o["baseUrl"]?.jsonPrimitive?.content.orEmpty()
                val apiKey = o["apiKey"]?.jsonPrimitive?.content.orEmpty()
                // ⚠️ 没有 baseUrl 的分组是**不可用**的（发不出请求）——
                //    读出来只会让用户看到一个点了就报错的卡片，不如丢掉。
                if (baseUrl.isBlank()) return@runCatching null
                ProviderGroup(
                    id = o["id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: newId(),
                    name = o["name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: "未命名",
                    baseUrl = baseUrl,
                    apiKey = apiKey,
                    providerType = o["providerType"]?.jsonPrimitive?.content
                        ?.takeIf { it.isNotBlank() } ?: ProviderType.OPENAI_COMPAT,
                    checkedModels = runCatching {
                        o["checkedModels"]!!.jsonArray.mapNotNull {
                            it.jsonPrimitive.content.takeIf { s -> s.isNotBlank() }
                        }
                    }.getOrElse { emptyList() },
                    // 缺字段（老分组 / 用户自建分组）= 空表 = 显示真名，
                    // 也就是**与本字段存在之前逐字节一致**的行为
                    modelLabels = runCatching {
                        o["modelLabels"]!!.jsonObject.mapNotNull { (k, v) ->
                            val shown = v.jsonPrimitive.content.trim()
                            if (k.isNotBlank() && shown.isNotBlank()) k to shown else null
                        }.toMap()
                    }.getOrElse { emptyMap() },
                    createdAt = runCatching { o["createdAt"]!!.jsonPrimitive.content.toLong() }
                        .getOrElse { 0L },
                    // ⚠️ 缺字段时**默认 true** —— 老分组（存这个字段之前写的）必须
                    //    保持"照常发思考参数"，否则升级上来的人会静默失去思考能力。
                    sendThinkingParams = runCatching {
                        o["sendThinkingParams"]!!.jsonPrimitive.content.toBooleanStrict()
                    }.getOrElse { true },
                    // 缺字段（老分组）= 没填 = null，走默认估算
                    contextWindow = runCatching {
                        o["contextWindow"]!!.jsonPrimitive.content.toInt()
                    }.getOrNull()?.takeIf { it > 0 },
                    // 同上；缺字段 = null = **回落到 contextWindow**（老分组压缩时机不变）
                    memoryContextWindow = runCatching {
                        o["memoryContextWindow"]!!.jsonPrimitive.content.toInt()
                    }.getOrNull()?.takeIf { it > 0 },
                    // ⚠️ 缺字段 = false —— 老分组都不是托管的；只有服务端下发的那条才会被标上
                    managed = runCatching {
                        o["managed"]!!.jsonPrimitive.content.toBooleanStrict()
                    }.getOrElse { false },
                    notice = o["notice"]?.jsonPrimitive?.content.orEmpty(),
                )
            }.getOrNull()
        }
    }

    /**
     * 旧扁平配置 → 一个「默认」分组。
     *
     * ⚠️ **没有旧配置时返回空列表**：不能凭空造一个空分组 ——
     * 新装的用户打开设置该看到"还没有分组"，而不是一张点不动、报错的卡片。
     */
    fun migrateLegacy(settings: AppSettings, now: Long): List<ProviderGroup> {
        // ⚠️ 判据只能是 **apiKey 为空**（v0.61.21 修）。
        //
        // 原来写的是 `settings.baseUrl.isBlank() && settings.apiKey.isBlank()`，
        // 而 `AppSettings.baseUrl` 的默认值是 `DEFAULT_BASE_URL`（**非空**）——
        // 于是这个条件对**全新安装**永远不成立，新用户照样会被造出一个
        // "默认"分组（密钥空、点不动），与上面这句 KDoc 的意图正好相反。
        // 换句话说：`baseUrl` 只要不是空的就说明不了任何事，它从来没空过。
        //
        // 为什么用 apiKey：这个 App 没有密钥就发不出任何请求（见 ChatViewModel 里
        // "请先到「设置 → 连接设置」里填好密钥"），所以**密钥为空 = 从没配过**。
        if (settings.apiKey.isBlank()) return emptyList()
        return listOf(
            ProviderGroup(
                id = newId(),
                name = DEFAULT_GROUP_NAME,
                baseUrl = settings.baseUrl,
                apiKey = settings.apiKey,
                providerType = ProviderType.OPENAI_COMPAT,
                // ⚠️ 必须是 **true**：这条迁移路径服务的是"存 sendThinkingParams 之前
                //    就配好的人"，字段默认 false 会让他们的思考参数**静默消失**。
                //    解码那条路的 getOrElse { true } 是同一个理由，两处口径必须一致。
                sendThinkingParams = true,
                // 旧配置里的那个 model 就是用户唯一用过的 —— 直接勾上
                checkedModels = listOfNotNull(settings.model.takeIf { it.isNotBlank() }),
                createdAt = now,
            ),
        )
    }

    /**
     * 把**服务器下发的托管分组**并进本地列表（v0.58.0）。
     *
     * ## 规则
     * · [free] 可用 → 保证列表里有且只有一条 `id = MANAGED_ID` 的托管分组，
     *   名称/地址/密钥**以服务端为准**；
     * · [free] 为 null（服务端关掉了这个功能）→ 把托管分组**摘掉** ——
     *   留在那里只会是一张点不动、发不出请求的卡片；
     * · 用户自己在这一条上的设置（勾选的模型、两个上下文数）**保留** ——
     *   服务端换密钥不该顺手清掉他挑好的模型。
     *
     * ⚠️ 托管分组**排在最后**：用户自己配的那些是他平时点的，
     *    不该被官方这条顶到最上面；没有自己的分组时它自然就是唯一那条。
     */
    fun withManaged(groups: List<ProviderGroup>, state: FreeGroupApi.State): List<ProviderGroup> {
        when (state) {
            is FreeGroupApi.State.Ok -> {
                val free = state.group
                val previous = groups.firstOrNull { it.id == MANAGED_ID }
                // ⚠️ 存进 `checkedModels` 的**必须是真名（id）** —— 那是发出去的字节。
                //    显示名走 `modelLabels`，改它永远不影响请求。
                val ids = free.models.map { it.id }
                val merged = (previous ?: ProviderGroup(
                    id = MANAGED_ID,
                    name = free.name,
                    baseUrl = free.baseUrl,
                    apiKey = free.apiKey,
                    managed = true,
                )).copy(
                    name = free.name,
                    baseUrl = free.baseUrl,
                    apiKey = free.apiKey,
                    providerType = ProviderType.OPENAI_COMPAT,
                    managed = true,
                    notice = free.notice,
                    // ⚠️ 模型清单以**后端**为准，但要保住用户仍然有效的选择（三种情形）：
                    //   · 后端**没给**清单        → 他原来勾的照旧（不能因为后端没配就清空）；
                    //   · 他勾的**还在**新清单里  → 留着（他没被影响）；
                    //   · 他勾的**已不在**新清单里（后端换了服务地址/模型）
                    //                            → 换成新清单 —— 否则他手上是一个**已经不存在的模型**，
                    //                              发出去必报错，而他完全不知道为什么。
                    //    这正是用户 2026-10-04 要的「我这边调整，他们那边不会有感觉」。
                    checkedModels = when {
                        ids.isEmpty() -> previous?.checkedModels.orEmpty()
                        else -> previous?.checkedModels
                            ?.filter { it in ids }
                            ?.takeIf { it.isNotEmpty() }
                            ?: ids
                    },
                    // ⚠️ 显示名**永远以后端为准**：它只影响界面 ——
                    //    后端改错了最坏也只是"名字难看"，不像 checkedModels 那样
                    //    牵扯"用户自己的选择"。所以这里不需要保护性合并。
                    modelLabels = free.models
                        .filter { it.label.isNotBlank() }
                        .associate { it.id to it.label },
                )
                return groups.filterNot { it.id == MANAGED_ID } + merged
            }

            FreeGroupApi.State.Disabled -> return groups.filterNot { it.id == MANAGED_ID }

            // ⚠️ **原样返回** —— 这一行就是用户报的那个 bug 的修法。
            //    "没问到"（接口没部署 / 网络抖 / 响应坏）**绝不能**当成"服务端关掉了"，
            //    否则一次抖动就能让一个已经摆在那儿的官方分组凭空消失。
            FreeGroupApi.State.Unreachable -> return groups
        }
    }

    /**
     * 取"当前分组"：优先用记住的 id，找不到就**先退回用户自己配的那个**，最后才是托管分组。
     *
     * ⚠️ 记住的 id 可能指向**已被删掉**的分组（用户删了当前分组）——
     * 这让它**静默退回**而不是报错。返回 null 只在"一个分组都没有"时。
     *
     * ⚠️ 退回顺序里"用户自己的优先"是刻意的（用户 2026-10-01：「没有自己添加过的就默认
     *    使用这个为默认的调用」）—— 反过来说：**只要他自己加过，就仍然用自己的**。
     *    否则升上来的老用户会被悄悄切到官方那条，账单和效果都变了却不知道为什么。
     */
    fun resolveActive(groups: List<ProviderGroup>, activeId: String?): ProviderGroup? =
        groups.firstOrNull { it.id == activeId }
            ?: groups.firstOrNull { !it.managed }
            ?: groups.firstOrNull()

    /**
     * 某段会话实际该用的分组：**会话自己选过的优先**，否则用全局当前分组。
     *
     * ⚠️ 会话选的分组可能已被删除 —— 那时 [resolveActive] 会退回第一个而不是报错，
     * 否则那段对话会**彻底发不出消息**（用户还看不到任何解释）。
     */
    fun resolveFor(
        groups: List<ProviderGroup>,
        activeId: String?,
        sessionGroupId: String?,
    ): ProviderGroup? =
        sessionGroupId?.let { id -> groups.firstOrNull { it.id == id } }
            ?: resolveActive(groups, activeId)

    /** 某段会话实际该用的模型名：**会话选过的优先**，否则分组里勾选的第一个。 */
    fun resolveModel(group: ProviderGroup?, sessionModel: String?): String =
        sessionModel?.takeIf { it.isNotBlank() }
            ?: group?.checkedModels?.firstOrNull().orEmpty()

    /**
     * 界面上该显示的模型名：配了显示名就用显示名，否则显示真名。
     *
     * ⚠️ **只用于显示**。任何拼进请求的地方都必须用 `checkedModels` 里的真名 ——
     *    这条是"改名字不会把请求改坏"的全部依据。
     */
    fun labelOf(group: ProviderGroup?, model: String): String =
        group?.modelLabels?.get(model)?.takeIf { it.isNotBlank() } ?: model

    /**
     * 把 provider 三件套（key / 地址 / 模型）**叠到**全局设置上。
     *
     * ## ⚠️ 为什么必须是 `copy`，不能用 [ProviderGroup.toSettings]
     * `toSettings()` 只带三个字段，其余**全部退回 `AppSettings` 的默认值**。
     * 对"拉取模型 / 测试连通"无所谓（那两件事只用三件套），
     * 但对**发消息**是灾难：用户的 `globalPrefix` / `reasoningEffort` / `compressMode`…
     * 会在不知不觉中变回默认值。更要命的是
     * `PromptEngine.buildFrozenPrefix(settings, persona)` 会因此拼出**另一个前缀** ——
     * 而前缀一变，整个会话的缓存全废（本项目最贵的一条红线）。
     *
     * ⚠️ 顺带说明它**不碰** `globalPrefixEnabled` / `globalPrefix` / `thinkingEnabled`
     * 这些字段：那些是"全局偏好"，与"用哪家服务商"是两码事。
     */
    fun applyProvider(
        settings: AppSettings,
        group: ProviderGroup?,
        model: String,
    ): AppSettings = settings.copy(
        apiKey = group?.apiKey?.takeIf { it.isNotBlank() } ?: settings.apiKey,
        baseUrl = group?.baseUrl?.takeIf { it.isNotBlank() } ?: settings.baseUrl,
        model = model.takeIf { it.isNotBlank() } ?: settings.model,
    )

    /**
     * **一步到位算出"某段会话实际会用的一套设置"** —— 全仓唯一的入口。
     *
     * ## 为什么要有这么一个"大杂烩"函数
     * 发消息 / 上下文压缩 / 记忆提取 / 余额查询**四处**都需要同一个答案。
     * 各处自己拼一份的话，它们会慢慢漂移：某天有人只改了发消息那条，
     * 于是"聊天用 A 服务商、压缩用 B"—— 而那种半套状态只在用户账单上表现出来
     *（本项目在 `buildHistory` / 前缀拼接上已经栽过两次同类跟头）。
     *
     * ⚠️ 一个分组都没有时**三个字段全部退回全局** ——
     * 这是升级上来的老用户行为**逐字节不变**的关键。
     */
    fun effective(
        settings: AppSettings,
        groups: List<ProviderGroup>,
        activeId: String?,
        sessionGroupId: String?,
        sessionModel: String?,
    ): AppSettings {
        val group = resolveFor(groups, activeId, sessionGroupId)
        return applyProvider(settings, group, resolveModel(group, sessionModel))
    }
}
