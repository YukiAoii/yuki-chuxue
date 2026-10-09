package ai.yuki.chuxue.data.memory

import ai.yuki.chuxue.data.ChatMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 模型返回的一条记忆（字段与 Prompt 里约定的 JSON 一一对应）。
 *
 * `importance` 保留**模型那边的量程 1–5**，映射到项目量程是
 * [MemoryExtraction.importanceToProject] 的事 —— 别在这里提前换算，
 * 否则"模型说了几"这件事就丢了，排查提取质量时无从下手。
 */
data class ExtractedMemory(
    val scope: String,
    val category: String,
    val content: String,
    val importance: Int,
)

/**
 * 后台记忆提取的**纯逻辑**（开发文档 §8.3 的落地）。
 *
 * ## 这条链路在做什么
 * 对话结束后，让主模型回头看一眼刚才聊了什么，把"值得长期记住的事"提出来，
 * 结构化后写进记忆库。于是用户不必亲手去记忆页添加，她也"自己记得下来"。
 *
 * ## 三个刻意的设计
 * 1. **戴上工作手套**：提取用的 Prompt 里明确写了"你是后台记忆提取器、不是聊天角色、
 *    不要有任何感情色彩"。不写这句，模型会用角色的口吻写记忆（"主人今天说……"），
 *    那些带情绪的字进库之后会污染后续注入。
 * 2. **硬编码、用户不可见**：这是软件内部的工程细节，不该让用户去配。
 * 3. **全部是纯函数**：Prompt 拼接、响应解析、词表映射都不碰网络与数据库 ——
 *    于是"提取"这条链路里最容易错的部分，能在无设备、无 API Key 的环境里被完整钉死。
 *
 * ## 与用户给的参考方案的差异（实测依据）
 * 参考方案里的 `EmbeddingService` 向量去重**在本项目不存在**（sqlite-vec 与 BGE 模型
 * 均实测不可得）。所以去重改由 [MemoryWriter] 的文本相似度承担；
 * 本文件只负责"把模型的输出变成项目能用的数据"。
 */
object MemoryExtraction {

    /** 送进提取上下文的最近消息条数。太少会缺语境，太多是白烧 token。 */
    const val HISTORY_LIMIT = 20

    /** 单条消息进入提取上下文前的截断长度。 */
    const val MAX_MESSAGE_CHARS = 400

    /**
     * 「已经记过的事」最多列出多少条（v0.61.54）。
     *
     * 只列最近这么多条：列全了会把 prompt 撑大（每条一次提取都要带上，是常驻成本），
     * 而"最近的记忆"正是最可能被重复提取的那批。40 条 × 每条约 20 字 ≈ 800 字，可接受。
     */
    const val EXISTING_MEMORY_LIMIT = 40

    /** 模型没给 importance 时的默认值（模型量程 1–5）。 */
    const val DEFAULT_IMPORTANCE = 3

    /** 提取请求的 max_tokens —— 提取只输出一个小数组，给多了纯属浪费。 */
    const val MAX_TOKENS = 512

    const val SCOPE_PERSONA = "persona"
    const val SCOPE_SESSION = "session"

    const val DEFAULT_CATEGORY = "其他"

    /**
     * 分类词表：模型按 Prompt 用英文输出，界面用中文显示。
     *
     * ⚠️ 映射放在**代码里**而不是让 Prompt 直接输出中文：Prompt 是给模型看的契约，
     * 改它要重新评估模型行为；而"界面怎么显示"是本项目自己的事。两条腿分开走，
     * 换界面文案时不必动 Prompt。
     */
    private val CATEGORY_MAP = mapOf(
        "preference" to "喜好",
        "event" to "经历",
        "relationship" to "约定",
        "fact" to "其他",
    )

    /**
     * 提取用的系统指令 —— **硬编码在软件里，用户不可见**。
     *
     * 第 5 条是参考方案里没有的：明确允许"什么都不记"。
     * 不写它，模型面对一段寒暄也会硬凑出几条，把噪声塞进记忆库。
     */
    private val INSTRUCTION = """
        # 角色设定
        你现在是一个后台记忆提取器。你绝对不是一个聊天角色，不要有任何感情色彩。

        # 任务
        从以下对话记录中提取值得长期记住的信息，输出严格合法的 JSON 数组。

        # 规则
        1. 判断主语：主语是「用户」→ scope 为 "persona"；主语是「我们 / 你和我」→ scope 为 "session"。
        2. 分类：preference(偏好) / event(事件) / relationship(关系) / fact(事实)。
        3. 重要度 importance 取 1-5。只提取稳定、明确的信息（如「用户是程序员」）；
           拒绝玩笑、临时状态（如「用户今天没吃饭」）以及当前问题的直接回答。
        4. 输出格式必须完全匹配，不要用 Markdown 代码块包裹：
        [{"scope": "persona", "category": "preference", "content": "用户喜欢喝美式咖啡", "importance": 4}]
        5. 若没有任何值得记住的信息，输出 []。

        # 对话记录
    """.trimIndent()

    /**
     * 构造提取请求的用户内容：固定指令 + 最近若干条对话。
     *
     * 对话取 [HISTORY_LIMIT] 条、每条截到 [MAX_MESSAGE_CHARS] —— 两道闸都是为了
     * **别让一次后台提取变成一笔大开销**：它在花用户自己的 API 额度。
     *
     * @param personaName 这个人设的展示名（`Persona.displayName`）。
     *   ⚠️ v0.61.21 修：这里原来**写死成品牌名**（`else "初雪"`），
     *   于是送给模型的对话记录里 AI 的每句话都署名「初雪」——
     *   提炼出来的记忆全是"初雪怎么怎么了"（用户原话：
     *   「记得都是初雪怎么怎么了 —— 初雪是我的品牌名啊」）。
     *   空名字退回「Ta」：既不写空标签，也**绝不**回落到品牌名。
     */
    fun buildPrompt(
        history: List<ChatMessage>,
        personaName: String = "",
        /**
         * 用户当前绑定的用户人设正文（v0.61.44）。空串 = 没绑定 ——
         * 输出与加这个参数之前**逐字节一致**（`MemoryExtractionUserPersonaTest` 钉着这条）。
         */
        userPersonaText: String = "",
        /**
         * **已经记过的记忆**（v0.61.54）。空表 = 不传 ——
         * 输出与加这个参数之前**逐字节一致**（同 [userPersonaText] 的纪律）。
         *
         * ## 为什么要有它
         * 用户报「云端记忆会重复记很多相同记忆」——同一件事被反复提取成
         * 「用户在上大学 / 用户是一名大学生 / 用户是大学生」。
         * ⚠️ 后端的字面去重**治不了这个**：实测这三条的相似度只有 0.571~0.857，
         * 而「用户喜欢猫」vs「用户喜欢狗」却有 0.800 —— 纯字面判据分不开
         * 「同义改写」与「差一个字但语义不同」，低阈值会**误删不同记忆**。
         * 所以根因在这里治：把已有记忆喂给模型，让它**自己别重复提取**。
         */
        existingMemories: List<String> = emptyList(),
    ): String {
        val speaker = personaName.trim().ifBlank { "Ta" }
        val dialogue = history
            .takeLast(HISTORY_LIMIT)
            .mapNotNull { message ->
                val text = message.content.trim()
                if (text.isEmpty()) return@mapNotNull null
                val clipped = if (text.length > MAX_MESSAGE_CHARS) {
                    text.take(MAX_MESSAGE_CHARS) + "…"
                } else {
                    text
                }
                // ⚠️ 用户那一侧保持「用户」：指令里的 scope 规则就是按"主语是用户还是我们"
                //    判定的，改掉它等于把分类依据拆了。
                val who = if (message.role == "user") "用户" else speaker
                "$who: $clipped"
            }
        // v0.61.44：用户背景块（只在绑定了用户人设时出现）—— 让提取器知道
        // 「用户」此刻在扮演谁。⚠️ 用户可能在这里写「你是…」（他在对模型描述
        // 自己扮演谁），所以必须带指向条款，防止被误读成 AI 自己的设定。
        val userBackground = userPersonaText.trim().takeIf { it.isNotEmpty() }?.let { text ->
            "\n# 用户背景（对话中的「用户」正在扮演的角色）\n" +
                text + "\n" +
                "⚠️ 以上是**用户**（和你对话的人）的角色设定，不是你的 —— " +
                "提取记忆时「用户」仍然指这个人，不要把上面的内容记成你自己的事。\n"
        }.orEmpty()
        // v0.61.54：已有记忆块（只在有已记条目时出现）—— 让提取器**自己别重复提取**。
        // ⚠️ 这是「云端记忆重复记」的根因治疗：字面去重分不开同义改写与近义不同事实
        //    （实测见 [buildPrompt] 的 existingMemories 注释），只有让模型看到"已经记过什么"
        //    才能从源头少产出重复。条数上限见 [EXISTING_MEMORY_LIMIT]。
        val existingBlock = existingMemories
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(EXISTING_MEMORY_LIMIT)
            .takeIf { it.isNotEmpty() }
            ?.let { list ->
                "\n# 已经记过的事（不要重复提取）\n" +
                    list.joinToString("\n") { "- $it" } + "\n" +
                    "⚠️ 上面这些**已经记过了**。本次只提取上面**没有**的新信息；" +
                    "同一件事换个说法（如「用户在上大学」与「用户是一名大学生」）也算重复，" +
                    "不要输出。没有新信息就输出空数组 `[]`。\n"
            }.orEmpty()
        return INSTRUCTION + "\n" + userBackground + existingBlock + dialogue.joinToString("\n")
    }

    /**
     * 解析模型的响应。
     *
     * **这个函数的职责是"尽量读出东西"，不是"严格校验"** —— 它面对的是一个
     * 会时不时加 Markdown 围栏、加一句"好的，我提取到以下记忆"的模型。
     * 所以：先剥围栏、再截取第一个 `[` 到最后一个 `]`、然后逐项尽力而为。
     *
     * 任何一步失败都返回**空表**，绝不抛异常：提取是附加服务，
     * 一次解析失败不该让用户的对话出任何问题（见 [MemoryExtractionScheduler] 的静默失败）。
     */
    fun parse(raw: String): List<ExtractedMemory> {
        val cleaned = raw.replace("```json", "").replace("```", "").trim()
        val arrayText = sliceJsonArray(cleaned) ?: return emptyList()

        return runCatching {
            Json.parseToJsonElement(arrayText).jsonArray.mapNotNull { element ->
                val obj = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
                val content = obj["content"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (content.isEmpty()) return@mapNotNull null

                ExtractedMemory(
                    scope = normalizeScope(obj["scope"]?.jsonPrimitive?.contentOrNull),
                    category = obj["category"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    content = content,
                    importance = (obj["importance"]?.jsonPrimitive?.intOrNull ?: DEFAULT_IMPORTANCE)
                        .coerceIn(1, 5),
                )
            }
        }.getOrElse { emptyList() }
    }

    /** 分类：模型英文 → 界面中文；未知一律兜到"其他"，不让生词进库。 */
    fun categoryToDisplay(raw: String): String =
        CATEGORY_MAP[raw.trim().lowercase()] ?: DEFAULT_CATEGORY

    /**
     * 重要性：模型的 1–5 → 项目的 0–10。
     *
     * ⚠️ 必须**放大**而不是照抄：项目里 10 才是"最重要"，照抄会让模型认定的
     * "最重要"只到量程的一半，接下来所有按重要性排序的地方都会失真。
     */
    fun importanceToProject(raw: Int): Int = raw.coerceIn(1, 5) * 2

    /**
     * 作用域兜底：只认 `session`，其余一律当 `persona`。
     *
     * 为什么偏向 persona：`session` 的记忆**必须绑定 sessionId**，一旦模型返回怪值
     * 而我们又当它是 session，这条记忆就会写入失败（或被挂到错误的会话上）。
     * 宁可记成"关于用户的事"（跨会话有效），也不要记错归属。
     */
    fun normalizeScope(raw: String?): String =
        if (raw?.trim()?.lowercase() == SCOPE_SESSION) SCOPE_SESSION else SCOPE_PERSONA

    /** 从可能夹带解释文字的响应里，截出最外层的 JSON 数组。 */
    private fun sliceJsonArray(text: String): String? {
        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        if (start < 0 || end <= start) return null
        return text.substring(start, end + 1)
    }
}

/**
 * 提取的**守卫**（成本护栏）—— 纯函数，所以每条守卫都能被单测钉住。
 *
 * ## 为什么这几条一条都不能少
 * 提取是**花用户钱**的后台动作，缺哪条都有真实损失：
 *
 * | 守卫 | 缺了会怎样 |
 * |---|---|
 * | `enabled` | 用户明确关掉了，后台还在偷偷调用 |
 * | `hasApiKey` | 空 Key 反复打无效请求 |
 * | `frontendStreaming` | **前后台同一个 Key**，并发请求可能触发限流，让用户正在进行的对话当场报错 |
 * | 时间间隔 | 连续聊天时每次心跳都提取一次，账单翻倍 |
 * | 会话有更新 | 没有新内容还重复提取同一批消息 —— 纯浪费 |
 *
 * `frontendStreaming` 那条是用户明确点名的：「因为前后台用的是同一个 Key，
 * 如果用户在疯狂发消息，后台 Worker 突然也发起请求，可能会触发 DeepSeek 的并发限流，
 * 导致用户前台报错」。**后台服务绝不能把前台弄坏** —— 这条优先级高于"提取要及时"。
 */
object MemoryExtractionThrottle {

    /**
     * 两次提取之间的最小间隔：10 分钟。
     *
     * 为什么按时间而不是按轮数：文档 §25.6 写的是"自动摘要触发 20 轮"，
     * 但按轮数需要一个"上次提取到第几轮"的游标（本项目没有，加它要动表结构）；
     * 按时间更简单，对用户也更可预期。10 分钟大约是一段正常聊天的长度 ——
     * 既不频繁到费钱，也不至于让记忆迟迟不来。
     */
    const val MIN_INTERVAL_MS = 10 * 60 * 1000L

    /**
     * 现在该不该跑一次提取。
     *
     * @param latestSessionUpdatedAt 所有会话里最近一次更新的时间（判断"有没有新内容"）
     * @param lastExtractedSessionAt 上次提取时见到的那个值
     */
    fun shouldRun(
        enabled: Boolean,
        hasApiKey: Boolean,
        frontendStreaming: Boolean,
        now: Long,
        lastExtractAt: Long,
        latestSessionUpdatedAt: Long,
        lastExtractedSessionAt: Long,
        minIntervalMs: Long = MIN_INTERVAL_MS,
    ): Boolean {
        if (!enabled) return false
        if (!hasApiKey) return false
        if (frontendStreaming) return false
        if (now - lastExtractAt < minIntervalMs) return false
        if (latestSessionUpdatedAt <= lastExtractedSessionAt) return false
        return true
    }
}
