package ai.yuki.chuxue.data

/**
 * Prompt 组装 —— 五层结构（开发文档 6.1）。
 *
 * ```
 * messages[] = [
 *   1. Global Prefix    全局规则（所有角色共享，可选）
 *   2. Frozen Prefix    人设（昵称/性别/性格/自由设定）
 *   3. History          历史消息 + 开场白（持久化后字节不变）
 *   4. Appendix Delta   约束/状态（每轮追加，位于前缀末端）
 *   5. User Input       本轮输入
 * ]
 * ```
 *
 * ══ 三条铁律（架构红线文档）══
 * 1. 变化的内容永远放尾部
 * 2. 已写入的内容不再改写，新内容只追加
 * 3. 记忆用压缩而非裁剪
 *
 * ══ 本文件绝不出现的东西 ══
 * 时间戳、会话 ID、随机数、每轮重新生成的任何值。
 * DeepSeek 缓存从第 0 个 token 起逐字节匹配，这类字节一旦进了稳定前缀，
 * 从它开始的一切缓存全部失效（实测：命中率 95% → 10%）。
 * 需要模型感知时间时，把它放进 [Appendix]（第 4 层，前缀末端）。
 */
object PromptEngine {

    /* ───────────── 第 1+2 层：Global Prefix + Frozen Prefix ───────────── */

    /**
     * 构造冻结前缀。**会话内绝不重写** —— 改它会让人设下所有会话的缓存从第 0 字节全碎。
     *
     * 结构（开发文档 2.3）：
     *   全局规则（可选） → # 关于用户 → # 角色性格（可选） → # 角色设定
     *
     * v0.61.42：`# 关于用户` 段支持**用户人设**（[UserPersona]）—— 用户自己的角色设定。
     * ⚠️ 这是三层防御里的**结构层**（见 [UserPersona] 的类注释）：正文只进这一段、
     *    并附第一人称指向条款，绝不与「# 角色设定」拼进同一段。
     * ⚠️ **没绑定用户人设时，输出与加这个参数之前逐字节一致**（老用户缓存不碎）——
     *    `UserPersonaPrefixTest` 与既有的 `PromptEngineTest` 一起钉着这条。
     */
    fun buildFrozenPrefix(
        settings: AppSettings,
        persona: Persona,
        userPersona: UserPersona? = null,
    ): String = buildString {
        // ⚠️ v0.53.0：是否使用通用设定改由**人设**决定；但要做**老用户兼容** ——
        //    字段为 null（老数据、从未设置过）时**沿用旧的全局面板开关**，
        //    于是升级后前缀一个字节都不变、缓存不碎。
        //    新建设的人设是显式 false（默认关）。见 Persona.useGlobalPrefix 的三态语义。
        val useGlobal = persona.useGlobalPrefix ?: settings.globalPrefixEnabled
        if (useGlobal && settings.globalPrefix.isNotBlank()) {
            append(settings.globalPrefix.trim())
            append("\n\n")
        }

        // ⚠️ v0.61.21：「Ta 怎么称呼你」那一栏已从人设编辑页移除，
        //    新建的人设 `userNickname` 会常年为空。原来这两行是**无条件**拼的 ——
        //    于是提示词里会留下一行**光有标签没有值**的「- 用户昵称：」：
        //    既是噪音，也可能被模型读成"用户没有名字"。
        //    改成有才写；两项都空时连「# 关于用户」这个小标题都不出现。
        //    ⚠️ 老用户填过的照旧拼进去 —— 这个值**只是不再让人填**，不是失效了。
        val nick = persona.userNickname.trim()
        val gender = persona.userGender.trim()
        // v0.61.42：绑定的用户人设正文（trim 后空 = 没绑定，判据与 UserPersonas.resolve 一致）
        val roleText = userPersona?.roleText?.trim().orEmpty()
        if (nick.isNotEmpty() || gender.isNotEmpty() || roleText.isNotEmpty()) {
            append("# 关于用户\n")
            if (nick.isNotEmpty()) append("- 用户昵称：").append(nick).append('\n')
            if (gender.isNotEmpty()) append("- 用户性别：").append(gender).append('\n')
            if (roleText.isNotEmpty()) {
                // ⚠️ 结构防御（别拆）：正文前有"这是谁"的定语、后有一人称指向条款 ——
                //    用户在里面写「你是…」是**在对你说他扮演谁**，绝不能被读成 AI 自己的人设。
                append("- 用户扮演的角色（和你对话的人是这个身份）：\n")
                append(roleText).append('\n')
                append("⚠️ 以上描述的是**用户**（和你说话的人），不是你自己 —— 不要用它来塑造你的身份或说话方式；你是谁，只由下文「角色设定」决定。\n")
            }
        }

        persona.personality?.takeIf { it.isNotBlank() }?.let {
            append("\n# 角色性格\n")
            append(it.trim()).append('\n')
        }

        append("\n# 角色设定\n")
        append(persona.customPrompt.trim()).append('\n')
    }

    /* ───────────── 第 4 层：Appendix Delta ───────────── */

    /**
     * 附录内容：**每轮可变**，因此只能放在前缀末端。
     *
     * 注意这里的时间只用于让模型感知「现在」，**不参与任何前缀稳定性判断**：
     * 它每次都是新的，所以它后面的内容（即用户输入）本来就不在缓存范围内。
     * 关键纪律：附录里出现的东西，**绝不能**同时出现在 [buildFrozenPrefix] 或历史里。
     */
    fun buildAppendix(
        constraints: List<String> = emptyList(),
        memories: List<String> = emptyList(),
        now: String? = null,
        /**
         * **心潮的「此刻块」原文**（v0.61.52）——「她此刻什么感受」（驱力/情绪/挂念/还在气）。
         *
         * ⚠️ 它走**附录**而不是冻结前缀，理由与记忆完全相同：这是**每轮都可能变**的东西，
         *    进前缀会让该会话的缓存每轮全碎。附录不吃缓存、只花 token。
         */
        xinchao: String? = null,
    ): String {
        val parts = mutableListOf<String>()
        if (now != null) parts += "<time>$now</time>"
        if (constraints.isNotEmpty()) {
            parts += "<constraints>"
            constraints.forEach { parts += "  <constraint>$it</constraint>" }
            parts += "</constraints>"
        }
        // 心潮此刻块：**给模型看她自己的状态**（原文自带【…】元指令标题，本来就是写给模型的）
        if (!xinchao.isNullOrBlank()) {
            parts += "<xinchao>"
            xinchao.trim().lineSequence().forEach { parts += "  $it" }
            parts += "</xinchao>"
        }
        if (memories.isNotEmpty()) {
            parts += "<memories>"
            memories.forEach { parts += "  <memory>$it</memory>" }
            parts += "</memories>"
        }
        if (parts.isEmpty()) return ""
        return parts.joinToString("\n", prefix = "<appendix>\n", postfix = "\n</appendix>")
    }

    /* ───────────── 请求计划 ───────────── */

    /**
     * 一次请求的完整产物：请求体 + 应当写入历史的那条用户消息。
     *
     * ══ 为什么必须绑在一起返回 ══
     * 二者要求**逐字节一致**：请求里发出去的 user 消息，必须原样成为历史里那一条。
     * 分开构造时只要参数有一处不同（旧实现就漏传了召回记忆），
     * 历史里存的内容就与发出去的不同 → 下一轮前缀错位 → 缓存失效。
     * 共用同一个 [ChatMessage] 实例，从结构上杜绝漂移。
     */
    data class Plan(
        val body: String,
        val userMessage: ChatMessage,
        /** 诊断用：实际发出的 messages（不参与请求体） */
        val debugMessages: List<Map<String, Any?>> = emptyList(),
    )

    /**
     * 输出长度上限（`max_tokens`）。
     *
     * ⚠️ **思考模式下，思考链与正文共用这份预算。**
     * 官方为此把默认值从 8K 抬到了 64K（见 `memory/api-deepseek.md` 的「思考模式」）。
     * 而这里原本**写死 2048** —— 等于主动把一个 64K 的默认压到 2048，
     * 思考一长，正文就一个 token 都分不到：请求成功、流正常收尾、`content` 是空的。
     * 用户报的「发了推动剧情，她完全不回复」正落在这一格。
     *
     * 所以按模式分开给：开着思考时留足"先想再写"的余量，关掉思考时用不着那么多。
     * 值仍**远低于** 64K —— `max_tokens` 是上限不是计费单位，
     * 但把上限抬到很大等于放弃对失控输出的兜底。
     *
     * ⚠️ 它**不进 messages**，因此与缓存前缀无关（缓存认的是 messages 的字节序列）。
     */
    fun maxTokensFor(thinkingEnabled: Boolean, reasoningEffort: String = "high"): Int {
        // ⚠️ v0.61.1：**不再按 `thinkingEnabled` 分档** —— 那个分档基于一个**错误假设**：
        //    "不发 thinking / reasoning_effort，模型就不会推理"。
        //
        // 实测打脸（your-llm-provider.example.com / deepseek-flash，**一个思考参数都没发**）：
        //    finish_reason      = 'length'
        //    message.content    = ''                    ← 正文一个 token 都没分到
        //    message.reasoning_content = '我们需要回答用户中文"说三个字"…'
        // 模型**自己**推理了，把 max_tokens 独吞。
        //
        // 所以：「关思考」只能保证**我们不发那个参数**，保证不了**模型不想**。
        // 既然拦不住推理，预算就得一律留足 —— 与发不发 thinking 参数无关。
        // （`thinkingEnabled` 形参保留：调用方语义未变，且将来若要按"用户明确关掉"
        //   做差异化，钩子还在。）
        //
        // 分档借鉴天枢 `_refs/Tianshu/src/api/factory.ts` 的 budgetMap
        // （它把推理预算按 maxTokens 的比例切：medium≈30%、high≈60%）：
        // OpenAI 兼容族没有独立的推理预算字段，那就**反过来把总量抬上去**，
        // 让"推理占比"回到安全区间。对不上的值按 high 档保守处理。
        return when (reasoningEffort.lowercase()) {
            "low" -> 8192
            "max" -> 32768
            else -> 16384
        }
    }

    fun plan(
        settings: AppSettings,
        frozenPrefix: String,
        history: List<ChatMessage>,
        userText: String,
        constraints: List<String> = emptyList(),
        memories: List<String> = emptyList(),
        now: String? = null,
        images: List<String> = emptyList(),
        /** 心潮「此刻块」原文（v0.61.52）—— 见 [buildAppendix] 的同名参数。 */
        xinchao: String? = null,
        /**
         * 是否流式。**只影响 `stream` 一个字段**，messages 的字节完全不变 ——
         * `stream` 是传输方式，不进 token 序列，因此对缓存命中没有任何影响。
         * （对照：`messages` 里任何一个字节变化都会让前缀从那里断开。）
         */
        stream: Boolean = false,
        /**
         * 是否发送 `thinking` / `reasoning_effort`（v0.51.0）。
         *
         * ⚠️ 默认 `true` 是**刻意的**：它必须与这个参数存在之前的行为逐字节相同。
         * 关闭后请求体里**一个字都不带**这两项 —— 那些网关就不会 400。
         */
        // ⚠️ 这个默认值**保持 true** —— 它只是调用方没传时的兜底，不是用户可见的设置。
        //    ChatViewModel 永远显式传值（来自分组），所以它对用户行为没有影响；
        //    而 PromptEngineTest 钉着「默认照旧发送思考参数 —— DeepSeek 路径逐字节不变」。
        //    用户要的「默认关闭」是**新建分组**的默认，那已经在 ProviderGroup 那边改了。
        sendThinkingParams: Boolean = true,
    ): Plan {
        // 只在这里拼一次本轮内容，请求体与历史共用
        val userContent = buildString {
            val appendix = buildAppendix(constraints, memories, now, xinchao)
            if (appendix.isNotEmpty()) {
                append(appendix).append('\n')
            }
            append(userText)
        }
        val userMessage = ChatMessage(role = "user", content = userContent, images = images)

        val messages = ArrayList<Map<String, Any?>>(history.size + 2)
        messages += mapOf("role" to "system", "content" to frozenPrefix)
        for (m in history) messages += messageToMap(m)
        messages += messageToMap(userMessage)

        val payload = linkedMapOf<String, Any?>(
            "model" to settings.model,
            "messages" to messages,
            "max_tokens" to maxTokensFor(settings.thinkingEnabled, settings.reasoningEffort),
            "stream" to stream,
        )

        /**
         * 思考模式开关。
         * 注意：**思考模式下 temperature 不生效**（官方文档 3），
         * 所以这里干脆不传 temperature，避免给出「设了但没用」的错觉。
         *
         * ⚠️ v0.51.0：整块受 [sendThinkingParams] 控制。`thinking` / `reasoning_effort`
         * 是 **DeepSeek 的扩展字段** —— 严格些的网关（OpenAI 本体 / 通义 / 智谱）
         * 收到不认识的顶层字段会**直接 400**，表现是"地址和密钥都对却报错"。
         * 用户可在分组详情页关掉它（默认开 = 与本开关存在之前逐字节一致）。
         */
        if (sendThinkingParams) {
            if (settings.thinkingEnabled) {
                payload["thinking"] = linkedMapOf<String, Any?>("type" to "enabled")
                payload["reasoning_effort"] = settings.reasoningEffort
            } else {
                payload["thinking"] = linkedMapOf<String, Any?>("type" to "disabled")
            }
        }

        return Plan(
            body = StableJson.encode(payload),
            userMessage = userMessage,
            // 诊断用：把**实际发出的结构**带出去，由上层写进日志（v0.46.7）。
            // ⚠️ 它不参与请求体，只是让"发出去的是什么"这件事有据可查。
            debugMessages = messages,
        )
    }

    /* ───────────── 消息 → API 格式（图片的关键分支）───────────── */

    /**
     * 把一条消息转成 API 期望的格式。
     *
     * **有图时 `content` 必须是块数组，无图时必须是纯字符串** —— 后者是官方
     * OpenAI 兼容格式的常规形态，也是缓存命中的正常路径。
     * 绝不能在无图时也套一层数组：那会改变请求体的字节，
     * 让**所有历史会话的缓存前缀与之前不一致**，命中率直接归零。
     */
    internal fun messageToMap(m: ChatMessage): Map<String, Any?> =
        if (!m.hasImages) {
            mapOf("role" to m.role, "content" to m.content)
        } else {
            mapOf("role" to m.role, "content" to buildContentBlocks(m.content, m.images))
        }

    /**
     * 构造图片内容的块数组（官方格式见文档 2）：
     * ```json
     * [{"type":"text","text":"..."},
     *  {"type":"image_url","image_url":{"url":"data:image/jpeg;base64,...","detail":"low"}}]
     * ```
     *
     * `detail: low` 会把图片缩到 512×512 —— 伴侣场景不需要精细视觉细节，
     * 而每张图的 token 上限是 1024，用 low 更省也更快。
     */
    internal fun buildContentBlocks(text: String, images: List<String>): List<Map<String, Any?>> =
        buildList {
            if (text.isNotBlank()) add(mapOf("type" to "text", "text" to text))
            images.forEach { dataUrl ->
                add(
                    mapOf(
                        "type" to "image_url",
                        "image_url" to linkedMapOf<String, Any?>(
                            "url" to dataUrl,
                            "detail" to "low",
                        ),
                    ),
                )
            }
        }

    /* ───────────── 开场白（开发文档 4.5）───────────── */

    /**
     * 把开场白里的变量替换为实际值。
     * 开场白作为**会话创建时的首条 assistant 消息**写入历史，之后不再变化 ——
     * 因此它属于静态前缀，缓存命中，且编辑人设不影响已有会话。
     */
    fun buildGreeting(greeting: String, persona: Persona): String =
        greeting
            .replace("{user_nickname}", persona.userNickname)
            .replace("{user_gender}", persona.userGender)
            .replace("{persona_name}", persona.displayName)
}
