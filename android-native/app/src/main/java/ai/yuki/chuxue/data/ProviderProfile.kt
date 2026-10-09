package ai.yuki.chuxue.data

/**
 * 缓存的**机制类型**（v0.51.0）。
 *
 * 形状取自 `_refs/Tianshu/src/api/provider.ts` 的 `prefixCacheStrategy` 三态枚举 ——
 * 那是个已经被多个 provider 验证过的最小划分。
 */
object CacheKind {
    /**
     * **服务端隐式前缀缓存（精确匹配）**：不发任何额外字段，服务端自己按前缀匹配。
     * DeepSeek 与绝大多数国产厂商都是这一类。对它的唯一要求是"前缀稳定"。
     *
     * ⚠️ 命名对照：参考实现（`_refs/Tianshu/src/api/provider-profile.ts` 的 `PROFILES`）
     * 把这一档叫 `exact-prefix` —— 两者是同一个东西。下面 [PARTIAL_PREFIX] 的区分
     * 就是从那张表学来的（v0.51.0 订正：原先把所有非 DeepSeek 都归到这一档，是**错的**）。
     */
    const val IMPLICIT_PREFIX = "implicit_prefix"

    /**
     * **部分前缀缓存**：同样不需要额外字段，但**只认部分前缀**
     *（OpenAI 系官方口径：≥1024 token 才起步、按 128 粒度对齐）。
     *
     * ⚠️ 与 [IMPLICIT_PREFIX] 的区别不在"有没有缓存"，而在**起点与粒度** ——
     * 拿精确匹配那一档的参数去解释它，会得出"短输入命中率低是块粒度所致"这种
     * 对不上号的结论。本项目**没有实测过**这一档，数值全部标"未实测"。
     */
    const val PARTIAL_PREFIX = "partial_prefix"

    /**
     * **显式断点**：要在请求体里插 `cache_control` 标记（Anthropic / Google / 通义
     * 官方端点）。Phase 2 —— 见 `ProviderType.ANTHROPIC` 的注释。
     */
    const val EXPLICIT_BREAKPOINT = "explicit_breakpoint"

    /** 没有前缀缓存。**不该有优化**，也就别去优化（kimi / minimax 等）。 */
    const val NONE = "none"
}

/**
 * usage 里**命中量**在哪个字段（v0.51.0）。
 *
 * ⚠️ 这是各家唯一必须"分叉"的地方：请求形状可以统一（都是 OpenAI 兼容），
 * 但**读回执**的字段名各家不同。写死一个会让非 DeepSeek 的命中率永远显示 0 ——
 * 而"显示 0"比"不显示"更糟（用户会以为缓存坏了）。
 */
object CachedTokenField {
    /** DeepSeek：`usage.prompt_cache_hit_tokens`（顶层，本项目一直在读）。 */
    const val DEEPSEEK_TOP_LEVEL = "prompt_cache_hit_tokens"

    /** OpenAI 系：`usage.prompt_tokens_details.cached_tokens`（嵌在对象里）。 */
    const val OPENAI_NESTED = "prompt_tokens_details.cached_tokens"

    /** 没得读 —— 界面应显示"该服务商不报缓存"，而不是 0。 */
    const val NONE = ""
}

/**
 * 一个服务商的**缓存画像**（v0.51.0）。
 *
 * ## 为什么要这张表（而不是 if-else）
 * 形状取自 `_refs/Tianshu/src/api/provider-profile.ts` 的 `PROFILES`：
 * 把"哪家怎么缓存"做成**数据**，消费点（缓存诊断、压缩策略）读表即可，
 * 不需要在每个调用点写 `when (provider)`。将来加一家只改这张表。
 *
 * ## ⚠️⚠️ 数值的可信度**分两档**，别混用
 *
 * | 来源 | 可信度 |
 * |---|---|
 * | DeepSeek 那行 | **本项目实测** —— `CacheMath.BLOCK = 128`，有 7 组真机数据吻合（见 项目交接记录） |
 * | 其余各家 | **配置表，不是实测** —— 抄自 Tianshu 的 `provider-profile.ts` |
 *
 * ⚠️ 顺带记一处**两个项目的结论相反**：Tianshu 的 profile 表写 DeepSeek 粒度 **64**，
 * 而本项目实测是 **128**（`CacheMath` 的 7 组数据）。
 * **以本项目实测为准**（用户也明确说"deepseek 的不变"）——
 * 这不是"谁对谁错"，而是**两家测的可能不是同一个东西**；
 * 但既然本地有可复现的数据，就用本地数据。
 *
 * @param cacheKind        见 [CacheKind]
 * @param minCacheTokens   低于它**不可能**命中。用来告诉用户"这次太短，不是缓存坏了"
 * @param cacheGranularity 命中量按多少 token 对齐（用于诊断页的块对齐检查）
 * @param cachedTokenField 见 [CachedTokenField]
 * @param contextWindow    上下文窗口（0 = 未知；未知时**不猜**）
 */
data class ProviderProfile(
    val cacheKind: String,
    val minCacheTokens: Int,
    val cacheGranularity: Int,
    val cachedTokenField: String,
    /**
     * 这一档的**粒度与起点**是"本项目实测"还是"抄来的配置表"。
     *
     * ⚠️ 只有 DeepSeek 那行是 true（`CacheMath.BLOCK`，7 组真机数据吻合）。
     * 其余的数值来自参考实现的画像表 —— **不是实测**，界面必须如实标注，
     * 不许把抄来的数说成测出来的。
     */
    val measured: Boolean = false,
    /**
     * **余额查询路径**（相对 baseUrl，不含 `/v1` 段）；`null` = 这家没有这个接口。
     *
     * ⚠️ 这是本项目最容易被误当成通用接口的东西：`GET /user/balance` 是
     * **DeepSeek 专有**的。把别的服务商的地址塞给它只会 404 —— 而界面上那句
     * "暂时读不到余额"会让用户以为是自己网络的问题，去反复重试一个**永远不可能有**的接口。
     * 所以这里必须能回答"这家到底有没有这个接口"，界面据此改说人话。
     */
    val balancePath: String? = null,
) {
    /**
     * **当前实现下**这家有没有可用的前缀缓存。
     *
     * ⚠️ 需要显式断点的档（Anthropic / 通义官方等）算 **false**：本项目**不发**
     * `cache_control`（Phase 2 才做），所以对用户而言现在就是拿不到缓存。
     * 写成 true 会让界面按"有缓存"去解释一个永远为 0 的数。
     */
    val hasPrefixCache: Boolean
        get() = cacheKind == CacheKind.IMPLICIT_PREFIX || cacheKind == CacheKind.PARTIAL_PREFIX

    val reportsCachedTokens: Boolean get() = cachedTokenField.isNotBlank()

    /**
     * ⚠️ **刻意没有 `contextWindow` 字段**（v0.51.0 订正）。
     *
     * 我第一版把它放在这里（DeepSeek 128K、其余 0=未知），看着无害，其实是**放错了层**。
     * 参考实现把这个教训写在函数注释里（`_refs/Tianshu/src/api/provider-profile.ts`）：
     *
     *   > `contextWindow` must come from the resolved model config — the previous silent
     *   > 128K fallback made 1M models (DeepSeek V4) inherit premature compaction tiers
     *   > whenever a caller forgot to plumb the window through.
     *
     * 同一个服务商内部窗口就能差一个数量级（8K / 128K / 1M），按服务商存一个数
     * 必然对其中一部分模型是错的 —— 而"窗口"正是压缩触发判定的分母，错了就是
     * 要么该压不压（撞上限报错），要么不该压猛压（白花钱）。
     * 所以窗口归**模型**，见 [ContextCompress.contextLimit]。
     */

    /**
     * 从一次 usage 里取出**这家口径的**命中量；`null` = 这家没报（不等于 0）。
     *
     * ## ⚠️ 为什么"没报"必须与"报了 0"分开
     * 界面上把"没报"当 0 显示，用户看到的是"缓存全废了" —— 于是去改人设、改历史，
     * 越改越糟（那些动作**真的**会把前缀弄断）。而真相是"这家不报这个数"。
     * 本项目已经吃过一次同类亏：`totalHit == 0` 被写成"检查人设/全局规则是否被改动"。
     */
    fun hitTokensOf(stats: CacheStats): Int? = when (cachedTokenField) {
        CachedTokenField.DEEPSEEK_TOP_LEVEL -> stats.hitTokens
        CachedTokenField.OPENAI_NESTED -> stats.nestedHitTokens
        else -> null
    }

    /** 同上的未命中口径；`null` = 这家没报。 */
    fun missTokensOf(stats: CacheStats): Int? = when (cachedTokenField) {
        CachedTokenField.DEEPSEEK_TOP_LEVEL -> stats.missTokens
        // OpenAI 系不单列 miss：`prompt_tokens` 是**含缓存**的总输入，减一下就有
        CachedTokenField.OPENAI_NESTED ->
            stats.nestedHitTokens?.let { (stats.inputTokens - it).coerceAtLeast(0) }
        else -> null
    }

    /**
     * 一段对话**该显示什么缓存读数**（v0.51.0）。
     *
     * ## 三种"没有数字"必须分开说
     * 它们在前端长得一模一样（都是"没有命中率"），而用户要做的事**完全不同**：
     *
     * | 情况 | 用户该做什么 |
     * |---|---|
     * | 这家不做前缀缓存 | 什么都不用做 —— 命中率对这家没有意义，**别去优化** |
     * | 有缓存但没报用量 | 也不用做 —— 只是这个数拿不到，**别去"修"** |
     * | 有读数 | 看数、按需调整 |
     *
     * 把前两种显示成 `0%`，用户会去改人设、改历史找原因 —— 而那些动作**真的**
     * 会把前缀弄断（越查越糟）。所以这里是纯函数、有单测，各界面共用一份措辞。
     */
    fun cacheLineFor(session: Session): String {
        val billed = session.totalHit + session.totalMiss
        return when {
            cacheKind == CacheKind.NONE -> "该服务商不做前缀缓存"
            // 要显式断点才算缓存，而本项目还没发 —— 说清是"没做"而不是"没命中"
            cacheKind == CacheKind.EXPLICIT_BREAKPOINT -> "该服务商需要显式缓存标记（本版未实现）"
            billed > 0 -> session.cacheSummary
            // 聊过至少一轮却没有读数 = 这家报了协议但没给这个字段
            session.messages.any { it.role == "assistant" } -> "该服务商未提供缓存用量"
            else -> "尚无请求"
        }
    }
}

/**
 * 服务商画像表 + 按 baseUrl 判定（v0.51.0）。
 */
object ProviderProfiles {

    /**
     * DeepSeek —— **本项目既有行为的完整写照，一个数都没改**。
     *
     * - 粒度 128：`CacheMath.BLOCK`（7 组实测）
     * - 最小命中：一个块（实测 283 token 的输入命中了 128 —— 说明**没有** 1024 那种门槛）
     * - 命中字段：`prompt_cache_hit_tokens`（`SseParser.kt:131` 一直在读）
     */
    val DEEPSEEK = ProviderProfile(
        cacheKind = CacheKind.IMPLICIT_PREFIX,
        minCacheTokens = CacheMath.BLOCK,
        cacheGranularity = CacheMath.BLOCK,
        cachedTokenField = CachedTokenField.DEEPSEEK_TOP_LEVEL,
        // 唯一一行"实测"：粒度 128 有 7 组真机数据吻合（见 `CacheMath.BLOCK`）
        measured = true,
        balancePath = "user/balance",
    )

    /**
     * OpenAI 兼容族的**保守默认**。
     *
     * ⚠️ **这些数值没有实测依据**，抄自 Tianshu 的 `provider-profile.ts`
     * （openai: `partial-prefix` / min 1024 / granularity 128）。
     * 它们的用途是**别做过分乐观的承诺**：如果实际门槛比 1024 低，
     * 我们最多是"少报了一点"；反过来（以为没门槛、实际有）会让用户
     * 看到命中率 0 却不知道原因。
     *
     * 实施探针时的正确做法：拿真实 key 发两次同样的长请求，看第二次的
     * `usage.prompt_tokens_details.cached_tokens` —— **验不了就保持保守**。
     */
    val OPENAI_COMPAT = ProviderProfile(
        // ⚠️ v0.51.0 订正：原先写成 IMPLICIT_PREFIX（= 精确匹配），是**分类错误**。
        //    参考实现把 openai 系单列成 `partial-prefix`（只认部分前缀、有 1024 起步门槛），
        //    与 DeepSeek 的 `exact-prefix` 不是一回事。
        cacheKind = CacheKind.PARTIAL_PREFIX,
        minCacheTokens = 1024,
        cacheGranularity = CacheMath.BLOCK,
        cachedTokenField = CachedTokenField.OPENAI_NESTED,
        // 抄来的，不是测出来的
        measured = false,
        // 余额没有跨家通用的接口 —— `null` 让界面说"该服务商不提供"，
        // 而不是去撞一个必然 404 的 `/user/balance`
        balancePath = null,
    )

    /**
     * **需要显式断点才有缓存**的厂商（Anthropic / Google / 通义官方端点）。
     *
     * ⚠️ 本项目**不发** `cache_control`（那是 Phase 2）—— 所以对用户而言这一档
     * 的实际含义是"**现在拿不到缓存**"，界面必须这么说，而不是显示一个 0% 让
     * 用户去查人设。分类依据：参考实现的 `explicit-breakpoint` 档（未实测）。
     */
    val EXPLICIT_ONLY = ProviderProfile(
        cacheKind = CacheKind.EXPLICIT_BREAKPOINT,
        minCacheTokens = 1024,
        cacheGranularity = CacheMath.BLOCK,
        cachedTokenField = CachedTokenField.NONE,
        measured = false,
        balancePath = null,
    )

    /**
     * **隐式精确前缀缓存，但不是我司实测过的那家**（GLM / grok / longcat 之类）。
     *
     * 与 [DEEPSEEK] 同类机制、**参数不通用**（参考实现给的是 min 64）。
     * 本项目没有实测过这一档，所以只把它当"有缓存、但读数与口径都不保证"。
     */
    val EXACT_PREFIX_THIRD = ProviderProfile(
        cacheKind = CacheKind.IMPLICIT_PREFIX,
        minCacheTokens = 64,
        cacheGranularity = CacheMath.BLOCK,
        cachedTokenField = CachedTokenField.OPENAI_NESTED,
        measured = false,
        balancePath = null,
    )

    /** **完全没有前缀缓存**的服务商 —— 界面该说"该服务商不做前缀缓存"，命中率对它无意义。 */
    val NO_PREFIX_CACHE = ProviderProfile(
        cacheKind = CacheKind.NONE,
        minCacheTokens = 0,
        cacheGranularity = 0,
        cachedTokenField = CachedTokenField.NONE,
        measured = false,
        balancePath = null,
    )

    /**
     * 按 baseUrl 判定用哪份画像。
     *
     * ## ⚠️ 为什么按**域名**判而不是让用户选
     * 缓存行为是**服务端**决定的，不是用户的偏好 —— 让用户选只会选错。
     *
     * ## ⚠️ 中转的边界
     * 如果用户用的是「中转 DeepSeek」的地址，域名里不含 `deepseek`，
     * 会被判成 [OPENAI_COMPAT]（保守档）。**这是刻意的**：
     * 保守档只会"少报命中"，不会"谎报成 0"；反过来误判成 DeepSeek 档
     * 则可能让诊断页显示一个不对的块对齐结论。
     */
    fun resolve(baseUrl: String): ProviderProfile {
        val host = hostOf(baseUrl)
        return when {
            host.contains("deepseek") -> DEEPSEEK
            // 显式断点档：不发 cache_control 就等于没有缓存（Phase 2 才做）
            host.contains("anthropic") || host.contains("claude") ||
                host.contains("google") || host.contains("gemini") ||
                host.contains("dashscope") || host.contains("aliyun") -> EXPLICIT_ONLY
            // 隐式精确前缀，但不是实测过的那家
            host.contains("bigmodel") || host.contains("zhipu") || host.contains("glm") ||
                host.contains("x.ai") || host.contains("grok") -> EXACT_PREFIX_THIRD
            // 参考实现里明确"没有前缀缓存"的几家
            host.contains("moonshot") || host.contains("kimi") ||
                host.contains("minimax") -> NO_PREFIX_CACHE
            host.contains("openai") || host.contains("siliconflow") ||
                host.contains("volces") || host.contains("openrouter") -> OPENAI_COMPAT
            // ⚠️ 认不出来 → [OPENAI_COMPAT]，**不是** [NO_PREFIX_CACHE]。
            //    这里与参考实现**有意分叉**：那张表把未知映射成 `none`，理由是
            //    "别让未知档驱动最激进的压缩阶梯" —— 而本项目没有那种阶梯
            //    （压缩是用户手动触发的），所以选没有副作用的那边：
            //    保守档只会"少报"，不会向用户**断言**"这家不做缓存"（那是个我们
            //    根本不知道的负向事实）。而且它的 `cachedTokenField` 是嵌套字段，
            //    取不到时由 `hitTokensOf` 如实返回 null。
            else -> OPENAI_COMPAT
        }
    }

    /**
     * 取出 baseUrl 的**主机名**（小写，不含端口后的路径）。
     *
     * ## ⚠️ 为什么必须只看主机名（这行是测试逼出来的）
     * 第一版直接对**整条 URL** 做 `contains("deepseek")`，于是
     * `https://relay.example.com/deepseek/v1` —— 一条**中转**地址 ——
     * 会被判成官方 DeepSeek 档。**provider 由域名识别，不由路径识别**：
     * 路径里写着什么，跟服务端是谁没有任何关系。
     *
     * 不用 `java.net.URL` 解析：它对没有 scheme 的输入（用户可能只填
     * `api.deepseek.com/v1`）会抛 `MalformedURLException`，
     * 而这里只需要"去掉 scheme、取到第一个 `/` 之前"。
     */
    private fun hostOf(baseUrl: String): String {
        val noScheme = baseUrl.substringAfter("://", baseUrl)
        return noScheme.substringBefore('/').substringBefore('?').substringBefore('#').lowercase()
    }

    /** 按分组取画像。 */
    fun forGroup(group: ProviderGroup): ProviderProfile = resolve(group.baseUrl)
}
