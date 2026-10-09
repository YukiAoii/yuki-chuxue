package ai.yuki.chuxue.data

/**
 * 上下文压缩的**全部决策逻辑**（纯函数，可单测 —— 见 `ContextCompressTest`）。
 *
 * ## 它解决什么
 * 对话越长，历史越占 token：一头会**撞上模型上下文上限**（然后整个请求直接失败），
 * 一头是**每轮都在为早期内容重复付费**。压缩把"较早的那一整段"交给模型总结成一段摘要，
 * 之后发给模型的就变成 `摘要 + 最近若干条原文`。
 *
 * ## ⚠️ 它**绝不**碰 `messages` 表
 * 压缩只作用于**组装请求时**用到的那份 history。库里一个字不动、界面照旧显示全部 ——
 * 用户原话就是「聊天记录不能丢失」。
 *（本项目红线：`messages` 是下一轮请求前缀的一部分，改了它就从那个点全碎。）
 *
 * ## ⚠️ 代价：压缩会让缓存碎一次
 * 一旦摘要进了 history，请求前缀就与上一轮不同 → 那一次必然 miss。
 * 这是"不撞上限"的必然代价，不是实现缺陷 —— 所以压缩要**低频、可见、由用户触发**。
 *
 * ## 为什么估算而不是用真实 usage
 * DeepSeek 返回的 `prompt_tokens` 是**真实值**，但那是**请求之后**才知道的；
 * 而"现在占了百分之多少"必须在**发出之前**回答。所以这里做的是**估算** ——
 * 界面上必须写明是估算，不能假装精确。
 */
object ContextCompress {

    /**
     * 触发压缩的默认阈值：占上下文上限的 70%。
     *
     * 留 30% 余量是有原因的：一轮对话还要带上**人设前缀 + 记忆附录 + 这一轮的新回复**，
     * 它们都不在"历史"里；卡到 100% 才压，等于要求"压缩本身不占地方"。
     */
    const val DEFAULT_THRESHOLD = 0.70f

    /** 压缩后保留多少条**最近的原文**（其余交给摘要）。 */
    const val KEEP_RECENT = 30

    /* ─────────────── v0.61.46：压缩恢复链的常量 ─────────────── */

    /**
     * 摘要**输出**的 token 上限（与 `DeepSeekClient.SUMMARY_MAX_TOKENS` 同值）。
     *
     * ⚠️ 它是 [summarizeBudget] 的一半 —— 摘要请求要为自己**先占好**这份输出空间，
     * 不然"输入装到满"的摘要请求会在生成阶段被截断（或整条被拒）。
     */
    const val SUMMARY_OUTPUT_TOKENS = 800

    /** 压缩请求为自己的输入保留的安全余量比例（上限的 10%）。 */
    const val COMPRESS_SAFETY_RATIO = 0.10f

    /**
     * 发送前的**超窗警戒线**（0.95）。
     *
     * 参考实现（Tianshu `enforceContextCeiling`）用 95% 作为"再发就必然失败"的硬线：
     * 贴到它就别再试发 —— 那一次几乎必被 API 拒绝（白花钱 + 用户吓一跳），
     * 先压缩才是出路。它**不表态**于 0.7~0.95 区间（那是 [decideCompress] 的事）。
     */
    const val CEILING_RATIO = 0.95f

    /* ─────────────────── 估算 ─────────────────── */

    /**
     * 估算一段文本的 token 数。
     *
     * ## 口径（刻意保守）
     * DeepSeek 用的是 BPE，中文大致 **1 字 ≈ 1 token**，英文约 **4 字符 ≈ 1 token**。
     * 这里按"中日韩字符各算 1、其余字符每 4 个算 1"累加。
     *
     * ⚠️ 它**是估算**：真实 token 数只有 API 的 `usage` 才知道。
     * 所以这个数只用来画占用率、判断"该不该压"，**不**用来报账。
     * ⚠️ 宁可略微**高估**：高估顶多让压缩早一点发生（安全侧），
     * 低估会让请求在客户端以为还早的时候撞上限（失败侧）。
     */
    fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 0
        var cjk = 0
        var other = 0
        text.forEach { ch ->
            // 中日韩统一表意文字 + 全角标点：这些在 BPE 里基本一字符一 token
            if (ch.code in 0x4E00..0x9FFF || ch.code in 0x3000..0x303F || ch.code in 0xFF00..0xFFEF) {
                cjk++
            } else {
                other++
            }
        }
        return cjk + (other + 3) / 4
    }

    /** 整段历史的估算 token（把每条的内容与思考过程都算上）。 */
    fun estimateTokens(messages: List<ChatMessage>): Int =
        messages.sumOf { estimateTokens(it.content) + estimateTokens(it.reasoning.orEmpty()) }

    /**
     * 该不该压缩。
     *
     * @param limitTokens 当前模型的上下文上限（由调用方按模型给，不是硬编码在这里）
     */
    fun shouldCompress(
        messages: List<ChatMessage>,
        limitTokens: Int,
        threshold: Float = DEFAULT_THRESHOLD,
    ): Boolean {
        if (limitTokens <= 0) return false
        return estimateTokens(messages) >= (limitTokens * threshold).toInt()
    }

    /**
     * 占用率（0..1，供进度条用）。上限非法时返回 0 而不是崩。
     */
    fun usageRatio(
        messages: List<ChatMessage>,
        limitTokens: Int,
    ): Float {
        if (limitTokens <= 0) return 0f
        return (estimateTokens(messages).toFloat() / limitTokens).coerceIn(0f, 1f)
    }

    /* ─────────────────── v0.61.46：压缩恢复链 ─────────────────── */

    /**
     * 摘要请求的**输入预算**：这次调用最多能把多少 token 的原文装进 messages。
     *
     * ## 它修的是什么（用户报的真实故障）
     * 原来做摘要时把**被压缩的一整段原文**一次性发出去。会话本身贴到模型窗口时，
     * 那个摘要请求**自己也会超窗** —— API 拒掉 → 压缩失败 → 会话卡死
     *（用户原话：超限之后就不能压缩了）。
     *
     * 预算 = 上限 − 输出预留（[SUMMARY_OUTPUT_TOKENS]）− 安全余量（上限的 10%）。
     * 超预算的段由 [chunkSegment] 切块解决，**压缩请求永远装得下**。
     *
     * ⚠️ 这是理论预算；估算误差（[estimateTokens] 低估）由 10% 余量兜。
     * 上限非法（≤ 输出 + 余量）→ 0，调用方据此退回单块旧路径。
     */
    fun summarizeBudget(limitTokens: Int): Int {
        if (limitTokens <= 0) return 0
        val safety = (limitTokens * COMPRESS_SAFETY_RATIO).toInt()
        return (limitTokens - SUMMARY_OUTPUT_TOKENS - safety).coerceAtLeast(0)
    }

    /**
     * **压缩触发线**（v0.61.56）—— 公式的**单一真源**（弹窗显示与判定共用它）。
     *
     * ```
     * 触发线 = min( 窗口 × 比例 , 窗口 − 输出预留 − headroom )
     * ```
     *
     * ## 为什么取 min（对齐 deepseek-harness 的 `resolveCompactSpec`）
     * 原实现只有 `窗口 × 比例` —— **没扣输出预留**。后果：窗口被输出占满时，
     * 压缩还没触发就已经超窗（用户看到"发不出去"而不是"该压缩了"）。
     * harness 的公式同样取 min，两个下界：
     *   · `W × thresholdRatio` —— 比例下界（用户能调的旋钮）；
     *   · `W − O − headroom`   —— **容量下界**（保证给输出留够地方）。
     *
     * ⚠️ headroom 复用 [COMPRESS_SAFETY_RATIO]（窗口的 10%）—— 与 [summarizeBudget]
     *    同一条口径。**不另造常量**：两处各写一份就会漂移成
     *    "压缩请求装得下、触发判定却不认"。
     *
     * ⚠️ 返回值**至少为 1**（窗口极小时 `W−O−headroom` 可能 ≤ 0）：
     *    返回 0 会让 `used >= 0` 恒真 → 每一轮都压缩（比不触发糟得多）。
     *    极小窗口下"保守地总是提示"比"永远不提示"安全 —— 用户至少能看见。
     *
     * @param limitTokens 窗口（[contextLimit] 的结果）
     * @param threshold 用户调的阈值比例（会被夹到 0.1~1）
     * @param reservedOutputTokens 给模型输出预留的 token
     */
    fun triggerLineTokens(
        limitTokens: Int,
        threshold: Float,
        reservedOutputTokens: Int = SUMMARY_OUTPUT_TOKENS,
    ): Int {
        if (limitTokens <= 0) return 0
        val ratio = threshold.coerceIn(0.1f, 1f)
        val byRatio = (limitTokens * ratio).toInt()
        val headroom = (limitTokens * COMPRESS_SAFETY_RATIO).toInt()
        val byCapacity = limitTokens - reservedOutputTokens - headroom
        return minOf(byRatio, byCapacity).coerceAtLeast(1)
    }

    /**
     * 把要压缩的段按**轮边界**切成预算内的块（v0.61.46，分块滚动摘要的第一步）。
     *
     * ## 规矩（每条都有代价，别改）
     * 1. **不拆轮也不拆消息**：块边界只落在「一条 user 开新轮」的地方。
     *    拆轮会让摘要看到"只有问没有答"的残段；拆消息则直接改写了内容。
     * 2. **每块 ≤ 预算**（[budgetTokens]）—— 除非**单独一轮自己就超预算**
     *   （比如用户粘贴了一整篇长文）：那种轮独占一块，超没超窗交给执行层如实报错；
     *    这里**绝不**为了"凑进预算"去拆它。
     * 3. 空段给空表；预算非法（≤ 0）退回单块（= 旧路径，不硬切）。
     *
     * ⚠️ `chunks.flatten() == segment`（不丢不重）——任何时刻中断，
     * 已完成的部分都可安全提交（见 ViewModel 的渐进提交）。
     */
    fun chunkSegment(segment: List<ChatMessage>, budgetTokens: Int): List<List<ChatMessage>> {
        if (segment.isEmpty()) return emptyList()
        if (budgetTokens <= 0) return listOf(segment)

        // ① 按轮切分：每条 user 开新轮；段首的前置消息（如开场白）归入第一轮
        val rounds = mutableListOf<MutableList<ChatMessage>>()
        for (m in segment) {
            if (m.role == USER || rounds.isEmpty()) rounds += mutableListOf(m)
            else rounds.last() += m
        }
        // ② 贪心装块：装入一轮后若超预算，下一轮起新块
        val chunks = mutableListOf<List<ChatMessage>>()
        var current = mutableListOf<ChatMessage>()
        var acc = 0
        for (r in rounds) {
            val rt = estimateTokens(r)
            if (acc + rt > budgetTokens && current.isNotEmpty()) {
                chunks += current
                current = mutableListOf()
                acc = 0
            }
            current += r
            acc += rt
        }
        if (current.isNotEmpty()) chunks += current
        return chunks
    }

    /**
     * 这段 API 回执是不是「**上下文超窗**」（v0.61.46）。
     *
     * ## 为什么要认它
     * 1. **错误文案**：400 的通用话术是「换个说法再发？」—— 对超窗完全是误导
     *   （换个说法没用，历史长度才是问题）。认出来才能说人话、给出路；
     * 2. **自动恢复**：发送被拒认出超窗 → 自动触发压缩（见 ChatViewModel）。
     *
     * ⚠️ 关键词表按各大服务商的实际措辞收集；`rate limit exceeded` 这类**不许**命中
     *（限流不是超窗）——判据用"特征短语"而不是单个 "limit" 单词。
     */
    fun isContextOverflowError(detail: String?): Boolean {
        val d = detail?.lowercase() ?: return false
        return CONTEXT_OVERFLOW_MARKERS.any { d.contains(it) }
    }

    private val CONTEXT_OVERFLOW_MARKERS = listOf(
        "maximum context length",
        "context length exceeded",
        "context window exceeded",
        "prompt is too long",
        "reduce the length of the messages",
        "too many tokens",
    )

    /**
     * 这次发送是不是已经贴到**超窗警戒线**（[CEILING_RATIO]）—— 到了就先压、别发
     * （那一次几乎必被拒，白花钱）。
     *
     * @param estimatedSent 估算的发送量（[estimateSentContext] 的口径）
     */
    fun shouldPauseForCeiling(estimatedSent: Int, limitTokens: Int): Boolean {
        if (limitTokens <= 0) return false
        return estimatedSent >= (limitTokens * CEILING_RATIO).toInt()
    }

    /* ─────────────────── 切分 ─────────────────── */

    /**
     * 挑出"**要交给模型总结的那一段**"：保留最近 [keepRecent] 条，其余的就是它。
     *
     * ## ⚠️ 轮边界对齐（这条最容易写错）
     * 切点必须落在**一条 user 消息**上 —— 否则会出现"总结里只有 ask 没有 answer"
     * 或者"摘要的结尾是半轮对话"。人类的"一轮"就是一条 user + 它后面的 assistant。
     *
     * 做法：先按条数切，再**往后挪**到最近的那条 user 上。
     * ⚠️ 往后挪而不是往前挪：往前挪会让"要保留的"少一条，而那条是被摘要吃掉的 ——
     * 挪错方向会导致**有一条消息既不在摘要里、也不在原文里**（静默丢内容）。
     *
     * @return 需要被总结的消息；没有可压的返回空列表
     */
    fun segmentToSummarize(
        messages: List<ChatMessage>,
        keepRecent: Int = KEEP_RECENT,
    ): List<ChatMessage> {
        if (messages.size <= keepRecent) return emptyList()
        val rawCut = messages.size - keepRecent
        // 从 rawCut 往后找第一条 user —— 它就是"保留段"的开头
        val cut = (rawCut until messages.size).firstOrNull { messages[it].role == USER } ?: return emptyList()
        return messages.subList(0, cut).toList()
    }

    /**
     * 压缩后**真正发给模型**的 history。
     *
     * - 没有摘要（还没压过）→ 原样返回全部；
     * - 有摘要、有 `summaryCount`（可信切点）→ `[摘要块] + 切点之后的全部`；
     * - 有摘要但没有 `summaryCount` → `[摘要块] + 全部原文`。
     *   ⚠️ **绝不按条数截断、也绝不按 `createdAt` 过滤**：
     *   前者是滑动窗口（前缀每轮变，v0.47.1），后者会静默丢内容（v0.47.2）。
     *   两条退路都已删除，只保留"可信切点 / 全量"两种形态。
     *
     * ## 摘要为什么挂在 `assistant` 侧
     * 语义上它是"她记得的、之前聊过的内容"：模型会把"自己说过的话"当既成事实，
     * 而不是当成一条需要执行的用户指令。
     * ⚠️ 不能挂 `user` 侧 —— 模型会以为用户又提了一遍（v0.46.6 试过，已回滚）。
     *
     * ## ⚠️ 这里曾是 v0.47.0 的"缓存修复"，**已回滚**（v0.47.1）
     * 当时改成 `system` 并由 `PromptEngine.plan` 合并，理由是
     * "`system → assistant` 违反对话序列协议 → 前缀缓存建不起来 → 只命中开头 512"。
     * **该理由被实测推翻**（直连 DeepSeek API，相同内容与长度做对照）：
     *   · `system,assistant,user` → prompt 1371，命中 1152
     *   · `system(合并),user`     → prompt 1368，命中 1152
     * 命中**完全相同**：序列形状对前缀缓存没有影响。依据不成立的改动不该留。
     *
     * ⚠️ 摘要**没有时间戳**（`createdAt = 0`）—— 它是"记忆"不是"某条消息"，
     * 带了时间反而会让"今天/昨天"的分割条算错。
     */
    fun buildHistory(
        messages: List<ChatMessage>,
        summary: String?,
        /**
         * 摘要覆盖到哪条（`Session.summaryUpTo`）。
         *
         * ⚠️ **v0.47.2 起不再用作切点** —— 它建立在 `createdAt` 上，
         * 而**老消息的 `createdAt` 是 0**（加该字段之前存下的），
         * `filter { createdAt > upTo }` 会把它们整批静默丢掉。
         * 真机证据：用户截图显示输入只剩 **5510** token、命中仅 **512**，
         * 与 `progress.md` 记载的这条缺陷逐字吻合。
         * 切点**只认** [coveredCount]。参数保留是为了不改调用方签名。
         */
        upTo: Long = 0L,
        keepRecent: Int = KEEP_RECENT,
        /** 摘要覆盖的**条数**（`Session.summaryCount`）；**唯一可信的切点** */
        coveredCount: Int = 0,
    ): List<ChatMessage> {
        if (summary.isNullOrBlank()) return messages
        // 取值只有两种合法形态：**可信切点之后**，或**全量**。详见下方 `when` 的注释。
        val recent = when {
            // ⚠️ **唯一可信的切点是"条数"** —— 它不依赖任何其它字段。
            coveredCount > 0 -> messages.drop(coveredCount.coerceIn(0, messages.size))
            // ⚠️⚠️ **其余情况一律返回全部原文**（v0.47.2 收敛）。
            //
            // 这里曾有两个"更省 token"的退路，**都会静默丢内容或让前缀漂移**：
            //
            // ① `upTo > 0L -> messages.filter { it.createdAt > upTo }`（v0.47.2 删）
            //    `createdAt` **对老消息是 0**（加该字段之前存下的），
            //    于是 `filter` 会把它们**整批过滤掉** —— 历史静默消失，用户看不见。
            //    真机证据：用户截图显示"输入共 **5510** token"，与
            //    `项目改动记录` 记载的「createdAt 为 0 时 filter 会清空历史……
            //    导致输入只发 5510 token」**逐字吻合**，且当时命中只有 **512**（9.3%）。
            //    同一账号在同一天稍后输入 10968、命中 10624（96.9%）—— 差别就是这一支。
            //
            // ② `else -> messages.takeLast(keepRecent)`（v0.47.1 删）
            //    滑动窗口：每加一条新消息窗口整体右移，**窗口第一条就变** →
            //    缓存按前缀匹配 → 命中量**卡死不再增长**。
            //    实测对照（同规模、同递增方式，只改取值方式）：
            //      · 滑动窗口 `takeLast(30)`：prompt 1700→1704→1708，命中 **640 → 640** = 37%
            //      · 固定切点 `drop(30)`    ：prompt 2360→2424→2488，命中 2176 → 2304 = 92%
            //
            // 共同点：**都试图用"不可靠的信息"去截断历史**。截断一旦错，
            // 代价不是"多花点 token"，而是**内容真的丢了**（红线：不许有消息两头都不在）
            // 外加**缓存前缀每轮漂移**。
            //
            // 全量的代价：摘要与原文有一段冗余（多了摘要覆盖过的那些 token），
            // 但那点冗余远小于"每轮把剩余部分全额按未命中计费"。
            // 想要真正省 token 的正解是**重新压缩一次** —— 那时会写入 `summaryCount`，
            // 从此走上面那条可信的固定切点分支。
            else -> messages
        }
        // ⚠️ `keepRecent` 在这里已不再使用（它只对 `segmentToSummarize` 有意义）——
        // 保留参数是为了不改调用方签名。任何"按条数截断"的取值都会滑动，
        // 而滑动 = 前缀每轮变 = 缓存全废。
        // ⚠️ **摘要在 `assistant` 侧**（v0.47.1 回滚后的结论，原设计理由仍然成立）：
        // 语义上它就是"她记得的、之前聊过的内容"，模型会把"自己说过的话"当既成事实。
        //
        // 三条路都已被实际检验过：
        // - **`assistant` 侧**：实测与"并入 system"命中**完全相同**（1371→1152 vs 1368→1152）
        //   —— 序列形状对前缀缓存没有影响，见类注释里的对照实验。
        // - `user` 侧：模型会把摘要当成**用户新提的指令**（v0.46.6 试过，已回滚）。
        // - `system` 侧（v0.47.0 试过）：命中无差异，且改动基于已被推翻的根因 → **已回滚**。
        return listOf(
            ChatMessage(
                role = "assistant",
                content = SUMMARY_PREFIX + summary,
            ),
        ) + recent
    }

    /**
     * 摘要消息的前缀。
     *
     * ⚠️ 必须让模型一眼看出这是"压缩过的旧内容"，否则它会把摘要当成刚发生的事 ——
     * 表现是"她突然重提很久以前的话题，像刚说过一样"。
     */
    const val SUMMARY_PREFIX = "［以下是更早之前的对话摘要，供你参考；不是刚刚发生的事］\n"

    /**
     * 让模型做摘要时用的提示词（纯函数，可单测）。
     *
     * ## 为什么要求"保事实"而不是"写得好"
     * 这段摘要之后要**替掉几十条原文** —— 库里虽然还在，但模型再也看不到它们了。
     * 所以丢掉的每一条事实都是**真的丢了**（表现为"她忘了你上周说过的事"）。
     * 宁可啰嗦、宁可长，也不要为了通顺把内容磨平。
     *
     * ## 为什么不要思考过程
     * `reasoning` 是她的内部独白，不是对话内容。塞进摘要的输入里有两个坏处：
     * 摘要变长（压缩的意义打折扣）、以及**可能把"她想过的"写成"她说过的"** ——
     * 那会让之后的行为与历史对不上。
     */
    fun buildSummaryPrompt(segment: List<ChatMessage>, previousSummary: String? = null): String {
        val prev = previousSummary?.trim().orEmpty()
        // ⚠️ 旧分支（无旧摘要）**逐字节保持**原来的文本 —— 老路径行为不变（有测试钉着）。
        if (prev.isEmpty()) {
            return buildString {
                append("请把下面这段对话压缩成一段摘要，供之后继续对话时参考。\n")
                append("要求：\n")
                append("1. 保留事实：称呼、约定、承诺、重要事件、情绪转折、还没做完的事；\n")
                append("2. 删掉寒暄与重复表述，但**不要**编造没有出现过的内容；\n")
                append("3. 用第三人称叙述（不要用「我」「你」）；\n")
                append("4. 直接输出摘要本身，不要加任何前后缀、不要加标题。\n\n")
                append("── 对话开始 ──\n")
                segment.forEach { m ->
                    // ⚠️ 2026-10-05：AI 方标签由「她：」改「Ta：」（用户规范：文案不预设人设性别，
                    //    与人设摘要 prompt「指代这个角色时用 Ta」对齐）—— 摘要会进后续上下文，
                    //    模型会按标签的风格叙述；写死"她"在男性人设下就是错的。
                    append(if (m.role == USER) "用户：" else "Ta：")
                    append(m.content)
                    append('\n')
                }
                append("── 对话结束 ──\n")
            }
        }
        // v0.61.46：滚动合并分支 —— 分块压缩的第 2 块起走这里（旧摘要 + 新一段对话 → 新摘要）。
        // 参考 LibreChat 的滚动摘要模板（Current summary / New lines / New summary）。
        return buildString {
            append("请把下面两份材料**合并**压缩成一段新摘要，供之后继续对话时参考。\n")
            append("要求：\n")
            append("1. **不要丢**「已有摘要」里的事实：称呼、约定、承诺、重要事件、情绪转折、还没做完的事；\n")
            append("2. 把「新对话」里值得长期记住的内容补充进去，删掉寒暄与重复表述；\n")
            append("3. 用第三人称叙述（不要用「我」「你」）；\n")
            append("4. 直接输出合并后的摘要本身，不要加标题、不要写「合并后」这类字样。\n\n")
            append("── 已有摘要 ──\n")
            append(prev)
            append('\n')
            append("── 新对话开始 ──\n")
            segment.forEach { m ->
                append(if (m.role == USER) "用户：" else "Ta：")
                append(m.content)
                append('\n')
            }
            append("── 新对话结束 ──\n")
        }
    }

    /**
     * 按模型名给上下文上限（token）。
     *
     * ⚠️ 这是**保守的默认值**：DeepSeek 的 `deepseek-chat` 系是 64K。
     * 取保守值的方向是"宁可早一点提示该压缩"，而不是"以为还早、结果撞上限"——
     * 后者会让整个请求直接失败（用户看到的是她突然不理人）。
     * 将来若要接别的模型，这里是最该被做成可配置项的地方。
     */
    /**
     * **认不出模型时的窗口假设**。
     *
     * ⚠️ 它是**假设**，不是知识。参考实现把这个坑写在函数注释里
     *（`_refs/Tianshu/src/api/provider-profile.ts`：`contextWindow` 必须来自解析出的
     *  模型配置 —— 原先静默回退 128K，让 1M 模型继承了过早的压缩档）。
     *
     * 本项目没有模型目录，暂时只能估；但**必须让界面说得出"这是估的"**
     * —— [contextLimitKnown] 就是给那句话用的。
     * 后果要说清：第三方的小窗口模型（8K / 32K）落在这个假设下会"该压不压"，
     * 一路聊到撞服务商上限、请求报错。
     */
    const val DEFAULT_CONTEXT_LIMIT = 128_000

    /**
     * 这个模型的窗口**认得出**吗 —— 还是只能落到 [DEFAULT_CONTEXT_LIMIT] 上估。
     *
     * `false` 时界面必须把窗口标成"按默认估算"，不许当成确定值展示。
     * 用户在分组里**填过**窗口（[override]）就算认得出 —— 那是事实，不是猜测。
     */
    fun contextLimitKnown(model: String, override: Int? = null): Boolean =
        (override != null && override > 0) ||
            model.contains("deepseek", ignoreCase = true) ||
            // 长上下文档（用户给的例子是 1M）
            model.contains("1m", ignoreCase = true) ||
            model.contains("long", ignoreCase = true)

    /**
     * 这个模型的上下文窗口。
     *
     * 优先级：**用户填的** > 模型名里能读出来的 > [DEFAULT_CONTEXT_LIMIT]。
     *
     * ⚠️ 为什么用户填的排第一：窗口是压缩触发判定的分母，而"某个模型窗口多大"
     * 是用户手上的知识（服务商官网页写着）。我们的名字规则只是事后推测 ——
     * 猜测不该盖过事实。参考实现也是这么排的（"explicit user-config override
     * still wins over this provider default"）。
     */
    fun contextLimit(model: String, override: Int? = null): Int {
        override?.takeIf { it > 0 }?.let { return it }
        return if (model.contains("1m", ignoreCase = true) || model.contains("long", ignoreCase = true)) {
            1_000_000
        } else {
            // DeepSeek 的推理档与通用档都按 128K 计（用户口径）
            DEFAULT_CONTEXT_LIMIT
        }
    }

    /**
     * 短格式：`501K` / `1.0M`（用户给的弹窗样例用的是这种）。
     *
     * ⚠️ 千位四舍五入到整数 K，百万位保留一位小数 —— 与样例 `501K / 1.0M` 一致。
     * 写 `501.3K` 只会让这行更长、更难扫。
     */
    fun formatShort(n: Int): String = when {
        n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
        n >= 1000 -> "${(n + 500) / 1000}K"
        else -> "$n"
    }

    /**
     * **这一轮真正会发出去多少 token**（v0.48.0）—— 与 [estimateContext] 的关键差别是
     * **它先把摘要算进去**。
     *
     * ## ⚠️ 为什么必须有它（不然后果是"每轮都重复压缩"）
     * 压缩**不动 `messages`**（库里一字不改，那是红线）。于是 [estimateContext] 拿到的
     * 占用率在压缩**前后完全一样** —— 按它做触发判定，会出现：
     *
     * 1. 占用到阈值 → 压缩；
     * 2. 下一轮再算，占用**还是那么高**（因为历史没删） → **又压一次**；
     * 3. 而 `segmentToSummarize` 每次都从同一个位置切 → **反复总结同一段**。
     *
     * 用户看到的是"每发一条就压一次、每次都花钱"，而占用率纹丝不动。
     * 所以触发判定与"距压缩"进度条都必须用**这个**函数：它反映的是
     * `摘要 + 切点之后` 那份**实际要发的内容**。
     *
     * ⚠️ 没压缩过时（`summary` 为空）它与 [estimateContext] **逐字节等价** —— 老会话行为不变。
     */
    fun estimateSentContext(
        frozenPrefix: String,
        messages: List<ChatMessage>,
        summary: String? = null,
        coveredCount: Int = 0,
        appendix: List<String> = emptyList(),
        userInput: String = "",
    ): Int = estimateContext(
        frozenPrefix = frozenPrefix,
        messages = buildHistory(messages, summary, coveredCount = coveredCount),
        appendix = appendix,
        userInput = userInput,
    )

    /**
     * **还有没有"值得再压一次"的新内容**（v0.48.0）。
     *
     * ## 它挡的是什么
     * [segmentToSummarize] 只看 `messages` 与 `keepRecent`，**完全不知道**上一次压到哪 ——
     * 所以它会一次次返回**同一段**。压缩过的部分再压一遍：白花钱、摘要还会越来越糊
     *（把"对上文摘要的总结"再总结一次）。
     *
     * 判据：这一轮切出的段里，**超出已覆盖条数的部分**才是新素材。
     * 已经全被覆盖（`segment.size <= coveredCount`）→ 没有新东西可压 → 返回 false。
     */
    fun hasNewMaterialToCompress(
        messages: List<ChatMessage>,
        coveredCount: Int,
        keepRecent: Int = KEEP_RECENT,
    ): Boolean {
        val segment = segmentToSummarize(messages, keepRecent)
        return segment.isNotEmpty() && segment.size > coveredCount.coerceAtLeast(0)
    }

    /**
     * **这份摘要能不能用**（v0.48.0）。
     *
     * ## 两条判据，各挡一类事故
     * 1. **不能是空白** —— 模型偶尔会返回空串或只有空白。空摘要一旦写进库，
     *    那一段历史就**真的没了**（模型从此看不到，用户也看不出来，因为界面上还在）——
     *    这正是红线「不能有消息两头都不在」要防的事。
     *    ⚠️ 注意：空白摘要**能通过"更小"那条判据**（0 < 原文），所以必须单独挡。
     * 2. **必须比被压缩段更小** —— 采纳 `deepseek-harness`
     *    （`compaction-basic/src/region.ts`：`framedSummaryTokenCount >=
     *    shadowedRouteTokenCount` 就拒绝）。摘要比原文还长的话，"压缩"反而
     *    让**之后每一轮**都更贵，而且这次调用本身还花了钱。
     *
     * ⚠️ 抽成纯函数是为了**测试与生产走同一条件**：
     * 各写一遍会漂移，而漂移的那一天正是它该拦住事故的那天。
     *
     * @return true = 可以用；false = 放弃这次压缩（**不写任何状态**，原样留着）
     */
    fun isUsableSummary(
        summary: String?,
        segment: List<ChatMessage>,
        /**
         * 滚动合并时的旧摘要（v0.61.46）。判据随之变为「新摘要 < 旧摘要 + 新块」——
         * 合并必须让总量变小，否则是"假压缩"（越滚越大）。
         */
        previousSummary: String? = null,
    ): Boolean {
        if (summary.isNullOrBlank()) return false
        val baseline = estimateTokens(previousSummary?.trim().orEmpty()) + estimateTokens(segment)
        return estimateTokens(summary) < baseline
    }

    /**
     * 触发判定的结果（v0.48.0）。三选一，没有"其它"这种含糊态。
     *
     * ⚠️ 做成**密封接口**而不是 `String`/`Int`：分支只有三种，
     * 让编译器帮着穷尽检查，比"传个字符串自己记得处理三种"可靠。
     */
    sealed interface CompressDecision {
        /** 什么都不做：没到阈值，或用户选了手动。 */
        data object None : CompressDecision

        /** 到阈值了、但只该**提示**（`ask` 模式）。⚠️ 界面层**不得**据此弹模态框。 */
        data object Suggest : CompressDecision

        /** 到阈值了且该**直接压**（`auto` 模式，且有新素材）。 */
        data object CompressNow : CompressDecision
    }

    /**
     * **该不该/该怎么样压缩**（v0.48.0）—— 三种模式的分叉全在这一个纯函数里。
     *
     * ## 为什么下沉到 data 层
     * 它原本会长在 `ChatViewModel.send()` 里，而那里要 Android 环境（`AndroidViewModel` +
     * Room）才跑得起来 —— 于是这条**唯一会花钱的分叉**就成了单测覆盖不到的死角。
     * 抽成纯函数后，三种模式 + 边界条件都能在 JVM 单测里钉住。
     *
     * ## 判定口径（很容易写错的一处）
     * 用 [estimateSentContext]（**把摘要算进去**），不是 [estimateContext]。
     * 后者在压缩前后数值**完全一样**（压缩不动 `messages`）→ `AUTO` 会每轮重复压。
     *
     * ## 触发线公式（v0.61.56 改，对齐 deepseek-harness 的 compaction-basic）
     *
     * ```
     * 触发线 = min( 窗口 × 比例 , 窗口 − 输出预留 − headroom )
     * ```
     *
     * ⚠️ **为什么取 min（这是本次修复的核心）**：
     *   改之前是 `窗口 × 比例` —— 它**没扣输出预留**。窗口被输出占满时，
     *   压缩还没触发就已经超窗了（用户看到的是"发不出去"，而不是"该压缩了"）。
     *   harness 的公式（`resolveCompactSpec`）同样取 min，两个下界：
     *     · `W × thresholdRatio` —— 比例下界（用户能调的那个旋钮）；
     *     · `W − O − headroom`    —— **容量下界**（保证给输出留够地方）。
     *   取小者 = 两个约束都满足。
     *
     * ⚠️ headroom 用 [COMPRESS_SAFETY_RATIO]（窗口的 10%）—— 与 [summarizeBudget]
     *    同一条口径（那里也是"上限的 10% 安全余量"）。**不另造一个常量**：
     *    两处若各写一份，调了一处就会漂移成"压缩请求装得下、但触发判定不认"。
     *
     * ## 两道护栏
     * 1. `auto` 模式下若**没有新素材**（[hasNewMaterialToCompress]）→ 返回 [CompressDecision.None]，
     *    否则会对同一段反复总结（白花钱，且摘要越压越糊）；
     * 2. 阈值非法（`limitTokens <= 0`）→ 一律 [CompressDecision.None]（不崩）。
     *
     * @param mode 见 [COMPRESS_MODE_AUTO] / [COMPRESS_MODE_ASK] / [COMPRESS_MODE_MANUAL]
     * @param reservedOutputTokens 这一轮请求给**模型输出**预留的 token（见 [triggerLineTokens]）
     */
    fun decideCompress(
        frozenPrefix: String,
        messages: List<ChatMessage>,
        summary: String?,
        coveredCount: Int,
        limitTokens: Int,
        threshold: Float,
        mode: String,
        reservedOutputTokens: Int = SUMMARY_OUTPUT_TOKENS,
    ): CompressDecision {
        if (limitTokens <= 0) return CompressDecision.None
        val used = estimateSentContext(
            frozenPrefix = frozenPrefix,
            messages = messages,
            summary = summary,
            coveredCount = coveredCount,
        )
        val line = triggerLineTokens(limitTokens, threshold, reservedOutputTokens)
        if (used < line) return CompressDecision.None

        return when (mode) {
            COMPRESS_MODE_MANUAL -> CompressDecision.None
            COMPRESS_MODE_ASK -> CompressDecision.Suggest
            else -> if (hasNewMaterialToCompress(messages, coveredCount)) {
                CompressDecision.CompressNow
            } else {
                // 到阈值了但没新东西可压 —— 不触发，也不提示（提示了用户点了也是白跑）
                CompressDecision.None
            }
        }
    }

    /**
     * **这一段对话现在占了多少**（用户给的口径）：
     * 人设（Frozen Prefix） + 历史 + 本轮动态附录 + 本轮用户输入。
     *
     * ⚠️ 四个部分都要算：只算历史会**明显低报** —— 人设那一段是每轮都发出去的，
     * 长人设本身就占掉几千 token（用户看到百分比偏低、于是撞上限时毫无准备）。
     * ⚠️ 全部是**估算**（字数换算），不发请求、不花钱 —— 弹窗里写明了。
     */
    fun estimateContext(
        frozenPrefix: String,
        messages: List<ChatMessage>,
        appendix: List<String> = emptyList(),
        userInput: String = "",
    ): Int =
        estimateTokens(frozenPrefix) +
            estimateTokens(messages) +
            appendix.sumOf { estimateTokens(it) } +
            estimateTokens(userInput)

    /**
     * 把 token 数写成给人看的短格式：`31.2K token`。
     *
     * ⚠️ 一千以下直接给数字（`860 token`）—— 写 `0.86K` 反而要换算一次。
     */
    fun formatTokens(n: Int): String = when {
        n >= 1000 -> "%.1fK token".format(n / 1000.0)
        else -> "$n token"
    }

    private const val USER = "user"
}
