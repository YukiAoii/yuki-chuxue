package ai.yuki.chuxue.data

import ai.yuki.chuxue.data.memory.MemoryExtraction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 带 HTTP 状态码的请求失败。
 *
 * ## 为什么要专门一个类型
 * 原先失败一律是 `IOException("认证失败（401）：API Key 无效。…")` —— 状态码被
 * **压进了一句话里**。界面要按码给不同说法（H4：错误码对话化）就只能去解析字符串，
 * 那是把结构信息扔掉再捡回来。
 *
 * `status` 让 [ChatErrors.forStatus] 有码可依；`message` 仍是原来那句技术文案，
 * 作为气泡里的小字摘要。它仍是 `IOException`，所以既有的
 * `catch (e: IOException)` 语义一个字没变。
 */
class ApiHttpException(val status: Int, message: String) : IOException(message) {

    /**
     * 换个时间重试，结果会不会不一样？
     *
     * - `429`（速率上限）与 `5xx`（服务端抖动/繁忙）→ **会**，等一会儿确实可能成功；
     * - `400 / 401 / 402 / 403 / 404 / 422` → **不会**。请求体不合法、Key 不对、
     *   余额不足、地址写错——这些再试一万次都是同一个结果。
     *
     * ⚠️ 这个判断是给 `ResilientSseConsumer` 用的：它原本把所有异常都当成
     * "网络断了可以重连"，于是 401 被反复重试，**把秒级的错误响应拖成半分钟白等**，
     * 期间 UI 收不到任何事件 —— 用户看到的就是"她一直在输入"（v0.44.1 修）。
     */
    val isRetryable: Boolean get() = status == 429 || status in 500..599
}

/**
 * DeepSeek 直连客户端。
 *
 * 与旧 Web 版的关键差异：**没有代理层**。旧版把请求发到 `/__proxy`，
 * 那是开发服务器的中间件，打进 APK 后根本不存在 —— 这正是当时「填了 key
 * 也调不通」的根因。原生 OkHttp 直连，不存在这个问题。
 */
class DeepSeekClient(
    private val client: OkHttpClient = defaultClient(),
    /**
     * **短请求专用**的客户端 —— 「拉取模型」「测试连通」用它。
     *
     * ⚠️ 为什么不复用 [client]：那一个的 `readTimeout` 是 **120 秒**（要托住流式回复，
     *    那是"两次数据到达之间"的上限）。短请求挂上去的后果是**用户点了「测试」，
     *    界面能沉默两分钟** —— 观感就是"按钮坏了、不会响应"。
     *    用户 2026-10-01 报的「测 DeepSeek 官网不会响应」正是这个观感。
     */
    private val shortClient: OkHttpClient = shortCallClient(),
) {

    /* ─────────────────────────── 对话 ─────────────────────────── */

    suspend fun chat(settings: AppSettings, requestBody: String): ChatOutcome =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(endpoint(settings, "chat/completions"))
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer ${settings.apiKey}")
                .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            execute(request) { raw -> parseResponse(raw) }
        }

    /**
     * 流式对话（开发文档 §9）。
     *
     * 逐块吐出正文增量；结束时发一个 [ChatStreamEvent.Done]（恰好一次）。
     *
     * ## 关于超时
     * 复用同一个 OkHttpClient：`readTimeout` 是**两次数据到达之间的**间隔上限，
     * 不是整段回复的总时长 —— 长回复不会因为「写太久」被截断。
     * 文档 §25.5 明确警告：**不要设 callTimeout**，那会误杀正在输出的流式连接。
     *
     * ## 断流不丢内容
     * `readUtf8Line()` 返回 null（EOF）时，把**已收到的内容**作为 Done 发出 ——
     * 弱网下服务端可能直接断开而不发 `[DONE]`，那些内容一个字都不该丢。
     */
    fun streamChat(settings: AppSettings, requestBody: String): Flow<ChatStreamEvent> = flow {
        val request = Request.Builder()
            .url(endpoint(settings, "chat/completions"))
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .addHeader("Authorization", "Bearer ${settings.apiKey}")
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        // ⚠️ v0.61.21：把这一路 Call 交出去，用户按「停下」时才能**立刻**掐断它。
        //    注意**不能**在这里 clear —— 流式的 response body 是在 execute() 返回
        //    之后才一段段读的，clear 早了就等于"按停只能停到第一块之前"。
        //    详见 ActiveCall 的注释（留着旧引用的代价是零）。
        val call = client.newCall(request)
        ActiveCall.track(call)
        val response = try {
            call.execute()
        } catch (e: IOException) {
            throw IOException("连接失败：${e.message}", e)
        }

        response.use { resp ->
            if (!resp.isSuccessful) {
                val body = resp.body?.string().orEmpty()
                throw ApiHttpException(resp.code, describeHttpError(resp.code, body))
            }

            val source = resp.body?.source() ?: throw IOException("响应没有正文")
            val buffer = StringBuilder()
            // 这一轮里最后一次见到的统计（见上面 Done 处的注释）
            var lastCache: CacheStats? = null

            while (true) {
                val line = source.readUtf8Line() ?: break
                when (val chunk = SseParser.parseLine(line)) {
                    is SseChunk.Delta -> {
                        buffer.append(chunk.text)
                        // ⚠️ 正文块里可能**同时带着** usage（v0.46.3）——
                        // 只更新 Done 分支的话，这种块里的统计就丢了。
                        chunk.usage?.let { lastCache = it }
                        emit(ChatStreamEvent.Delta(chunk.text))
                    }
                    // 思考过程原样上抛，**不进 buffer** ——
                    // buffer 是「正文」，它决定写进历史的内容与下一轮的前缀。
                    is SseChunk.Reasoning -> {
                        emit(ChatStreamEvent.Reasoning(chunk.text))
                    }
                    is SseChunk.Done -> {
                        chunk.cache?.let { lastCache = it }
                        // ⚠️ 用**这一整轮里最后一次见到的** cache，而不是"当前这个 chunk"的
                        //（v0.46.2）：usage 只出现在中间某一块，之后任何一次迭代都可能把它冲掉。
                        emit(ChatStreamEvent.Done(buffer.toString(), lastCache ?: chunk.cache, chunk.reason))
                        return@flow
                    }
                    SseChunk.Ignore -> Unit
                }
            }

            // EOF 却没等到 [DONE]：交付已收到的部分
            // ⚠️ 兜底也要带上这一轮已经收到的统计（v0.46.3）：
            // 流里可能**始终没有**独立的 usage 块（它跟着正文一起来了），
            // 那时这里若传 null，整轮的命中统计就白丢了。
            emit(ChatStreamEvent.Done(buffer.toString(), lastCache))
        }
    }.flowOn(Dispatchers.IO)

    /* ─────────────────────── 拉取模型列表 ─────────────────────── */

    /**
     * 拉取当前可用的模型列表。
     *
     * DeepSeek 提供 OpenAI 兼容的 `GET /models`，形如：
     *   {"object":"list","data":[{"id":"deepseek-chat",...}, ...]}
     * 这样用户不必手敲模型名 —— 模型名会随官方更新变化，手敲容易失效。
     */
    suspend fun listModels(settings: AppSettings): List<String> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(endpoint(settings, "models"))
            .addHeader("Authorization", "Bearer ${settings.apiKey}")
            .get()
            .build()

        executeOn(shortClient, request) { raw -> parseModelList(raw) }
    }

    /** 独立出来便于单测：解析 OpenAI 兼容的模型列表格式 */
    internal fun parseModelList(raw: String): List<String> {
        val root = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: throw IOException("模型列表不是合法 JSON：${raw.take(200)}")

        val data = root["data"]?.jsonArray
            ?: throw IOException("响应里没有 data 字段：${raw.take(200)}")

        val ids = data.mapNotNull { el ->
            runCatching { el.jsonObject["id"]?.jsonPrimitive?.content }.getOrNull()
        }.filter { it.isNotBlank() }

        if (ids.isEmpty()) throw IOException("模型列表为空")
        return ids.sorted()
    }

    /* ─────────────────────── 连通性测试 ─────────────────────── */

    /**
     * 连通性测试：发一个**极小**的请求，尽量少消耗用户额度（max_tokens=4）。
     * 返回模型的一句回复，作为「确实通了」的证据（而不只是「没报错」）。
     */
    suspend fun testConnection(settings: AppSettings): String = withContext(Dispatchers.IO) {
        if (settings.apiKey.isBlank()) throw IOException("未填写 API Key")

        val body = StableJson.encode(
            linkedMapOf<String, Any?>(
                "model" to settings.model,
                "messages" to listOf(mapOf("role" to "user", "content" to "hi")),
                "max_tokens" to 4,
                "stream" to false,
            ),
        )

        val request = Request.Builder()
            .url(endpoint(settings, "chat/completions"))
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer ${settings.apiKey}")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        executeOn(shortClient, request) { raw -> parseResponse(raw).text }
            .ifBlank { "连接正常" }
    }

    /* ─────────────────── 后台记忆提取（独立于 SSE）─────────────────── */

    /**
     * 记忆提取专用调用（开发文档 §8.3）。
     *
     * ## 为什么单独开一个方法，而不是复用 [streamChat]
     * 用户给的约束里最要紧的一条：**绝不要复用前台的流式接口**。理由三条，每条都真实：
     * 1. **形态不同** —— 提取要的是一次完整的 JSON；走流式反而要额外拼接。
     * 2. **失败语义不同** —— 提取失败必须**安静**（后台附加服务，不能打扰用户），
     *    流式失败要立刻告诉用户。
     * 3. **资源不同** —— 流式会起前台服务、占常驻通知；提取什么都不该占。
     *
     * 复用的是**更底下那一层**：同一个 `OkHttpClient`（连接池与超时策略），
     * 以及可测的 [parseResponse]。
     *
     * ## 两处省钱
     * - `stream = false` + 低 `max_tokens` —— 提取只输出一个小数组；
     * - `thinking = disabled` —— 提取是结构化任务，思考链纯属浪费：
     *   多花 token、多等几秒，而结果不会更好。
     */
    suspend fun extractMemories(settings: AppSettings, prompt: String): String =
        withContext(Dispatchers.IO) {
            val body = StableJson.encode(
                linkedMapOf<String, Any?>(
                    "model" to settings.model,
                    "messages" to listOf(mapOf("role" to "user", "content" to prompt)),
                    "max_tokens" to MemoryExtraction.MAX_TOKENS,
                    "stream" to false,
                    "thinking" to linkedMapOf<String, Any?>("type" to "disabled"),
                ),
            )

            val request = Request.Builder()
                .url(endpoint(settings, "chat/completions"))
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer ${settings.apiKey}")
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            execute(request) { raw -> parseResponse(raw).text }
        }

    /**
     * 为一个人设生成一小段简介（v0.61.21）—— 详情页展示用。
     *
     * 形态与 [extractMemories] 完全一样（非流式、**低 max_tokens**、**关思考**）：
     * 用户明确要求过「别写太多消耗」，而思考链对"写一两句话"纯属多花钱多等。
     * 失败**往上抛**，由调用方决定怎么处置（它是在启动时后台跑的，那里会静默）。
     */
    suspend fun describePersona(settings: AppSettings, prompt: String): String =
        withContext(Dispatchers.IO) {
            val body = StableJson.encode(
                linkedMapOf<String, Any?>(
                    "model" to settings.model,
                    "messages" to listOf(mapOf("role" to "user", "content" to prompt)),
                    "max_tokens" to PersonaSummary.MAX_TOKENS,
                    "stream" to false,
                    "thinking" to linkedMapOf<String, Any?>("type" to "disabled"),
                ),
            )

            val request = Request.Builder()
                .url(endpoint(settings, "chat/completions"))
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer ${settings.apiKey}")
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            execute(request) { raw -> parseResponse(raw).text }
        }

    /**
     * 上下文压缩用的摘要调用（v0.45.0，v0.48.0 **改为复用前缀**）。
     *
     * ## 与 [extractMemories] 的异同
     * 形态一样（非流式、低 `max_tokens`、关思考 —— 摘要不需要思考链，纯属多花钱多等）；
     * 但**失败语义完全不同**：提取是后台附加服务，失败要**安静**；
     * 而这个是**用户点按钮触发的**，他正盯着进度条，失败必须报出来。
     * 所以这里不做任何"吞掉异常"的处理，直接往上抛。
     *
     * ## ⚠️ v0.48.0：为什么要带 `frozenPrefix` 与整个 `segment`
     * 旧实现只发**一条孤立的 user 消息**（`[user(总结要求+原文)]`）——
     * 那段前缀与主对话**没有任何公共部分**，于是这次调用**一分钱缓存都吃不到**，
     * 全文按未命中计费。而摘要请求的输入可不小（被压的那一整段）。
     *
     * 现在改成「**主对话刚发过的那份前缀** + 末尾一条指令」：
     * ```
     * [system(人设)] + [被压缩的那一段原文…] + [user(总结指令)]
     * ```
     * 好处是**前两段与上一轮请求逐字节相同** → 命中前缀缓存（约 1/10 价）。
     * 这就是 `deepseek-harness` 的做法（`compaction-basic/src/region.ts`
     * 的 `buildSummarizationInput` 拼 `[system, ...regionMessages]`，
     * 再把指令追加成最后一条 user）。
     *
     * ⚠️ 指令**必须在最后**：变化的内容永远放尾部，前面那两段才能稳定命中。
     */
    suspend fun summarize(
        settings: AppSettings,
        frozenPrefix: String,
        segment: List<ChatMessage>,
        instruction: String,
    ): String =
        withContext(Dispatchers.IO) {
            // 前两段与主对话同构（system 人设 + 原文），最后才是变化的那条指令。
            // ⚠️ v0.61.46：分块滚动时**第 2 块起**没有可复用的前缀（输入是"旧摘要+新块"，
            //    与主对话没有公共部分）——空前缀时**不塞 system 消息**（塞个空的既是噪音、又白占字节）。
            val messages = buildList<Map<String, Any?>> {
                if (frozenPrefix.isNotBlank()) {
                    add(mapOf("role" to "system", "content" to frozenPrefix))
                }
                segment.forEach { add(PromptEngine.messageToMap(it)) }
                add(mapOf("role" to "user", "content" to instruction))
            }
            val body = StableJson.encode(
                linkedMapOf<String, Any?>(
                    "model" to settings.model,
                    "messages" to messages,
                    "max_tokens" to SUMMARY_MAX_TOKENS,
                    "stream" to false,
                    "thinking" to linkedMapOf<String, Any?>("type" to "disabled"),
                ),
            )

            val request = Request.Builder()
                .url(endpoint(settings, "chat/completions"))
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer ${settings.apiKey}")
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            execute(request) { raw -> parseResponse(raw).text }
        }

    /**
     * 查询**账户余额**（用户给的官方接口 `GET /user/balance`）。
     *
     * ⚠️ 这个接口在**域名根**下，**不在 `/v1` 里** ——
     * 用 [endpoint] 会补成 `/v1/user/balance`，那是 404（官方文档给的是
     * `https://api.deepseek.com/user/balance`）。所以这里单独拼一次。
     *
     * ⚠️ 它与"这段对话用了多少"无关：余额是**账号级**的，
     * 界面必须标注清楚（否则用户以为"这段对话还剩这么多"）。
     */
    suspend fun balance(settings: AppSettings): Balance = withContext(Dispatchers.IO) {
        // ⚠️ 路径由**服务商画像**给出（v0.51.0）：`/user/balance` 是 DeepSeek 专有的，
        //    别的服务商没有这条接口。为 null 就直接说清 —— 而不是去撞一个必然 404 的
        //    地址、再让界面把它翻译成"暂时读不到余额"（用户会去查网络，而查不出东西）。
        val path = ProviderProfiles.resolve(settings.baseUrl).balancePath
            ?: throw IOException("这个服务商不提供余额查询")
        val base = normalizeBaseUrl(settings.baseUrl).removeSuffix("/v1")
        val request = Request.Builder()
            .url("$base/$path")
            .addHeader("Authorization", "Bearer ${settings.apiKey}")
            .get()
            .build()
        execute(request) { BalanceParser.parse(it) }
    }

    /* ─────────────────────────── 内部 ─────────────────────────── */

    private fun endpoint(settings: AppSettings, path: String): String =
        "${normalizeBaseUrl(settings.baseUrl)}/$path"

    private inline fun <T> execute(request: Request, parse: (String) -> T): T =
        executeOn(client, request, parse)

    /** 同 [execute]，但可指定客户端（短请求走 [shortClient]，见它的注释）。 */
    private inline fun <T> executeOn(
        http: OkHttpClient,
        request: Request,
        parse: (String) -> T,
    ): T {
        try {
            val call = http.newCall(request)
            // 同样交给 ActiveCall（余额/模型列表这类请求也要能被"停下"打断）
            ActiveCall.track(call)
            call.execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw ApiHttpException(response.code, describeHttpError(response.code, body))
                }
                return parse(body)
            }
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("请求失败：${e.message}", e)
        }
    }

    private fun parseResponse(raw: String): ChatOutcome {
        val root = try {
            Json.parseToJsonElement(raw).jsonObject
        } catch (e: Exception) {
            throw IOException("响应不是合法 JSON：${raw.take(200)}")
        }

        root["error"]?.let { err ->
            val msg = runCatching { err.jsonObject["message"]?.jsonPrimitive?.content }.getOrNull()
            throw IOException(msg ?: "DeepSeek 返回了错误")
        }

        val first = runCatching { root["choices"]?.jsonArray?.firstOrNull()?.jsonObject }.getOrNull()
        val content = runCatching {
            first?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content
        }.getOrNull()
        // 非流式响应里思考藏在 message.reasoning_content —— 与流式的 delta 同源
        val reasoning = runCatching {
            first?.get("message")?.jsonObject?.get("reasoning_content")?.jsonPrimitive?.content
        }.getOrNull()
        val finishReason = runCatching {
            first?.get("finish_reason")?.jsonPrimitive?.contentOrNull
        }.getOrNull()

        if (content.isNullOrEmpty()) {
            throw IOException("模型返回了空内容（可能是内容策略拦截或模型名有误）")
        }

        val usage = runCatching { root["usage"]?.jsonObject }.getOrNull()
        fun field(name: String): Int =
            runCatching { usage?.get(name)?.jsonPrimitive?.intOrNull }.getOrNull() ?: 0

        return ChatOutcome(
            text = content,
            reasoning = reasoning?.takeIf { it.isNotBlank() },
            stopReason = finishReason,
            cache = CacheStats(
                hitTokens = field("prompt_cache_hit_tokens"),
                missTokens = field("prompt_cache_miss_tokens"),
                inputTokens = field("prompt_tokens"),
                // 与 `SseParser.parseUsage` 同一处加法式读取（v0.51.0）：
                // 顺手取 OpenAI 系的嵌套命中量，**不改**上面三个字段。
                nestedHitTokens = runCatching {
                    usage?.get("prompt_tokens_details")
                        ?.jsonObject
                        ?.get("cached_tokens")
                        ?.jsonPrimitive
                        ?.intOrNull
                }.getOrNull(),
            ),
        )
    }

    /** 把 HTTP 错误翻译成人能看懂的一句话 */
    private fun describeHttpError(status: Int, raw: String): String {
        var detail = raw.take(300)
        runCatching {
            val err = Json.parseToJsonElement(raw).jsonObject["error"]
            val msg = when {
                err == null -> null
                err is kotlinx.serialization.json.JsonPrimitive -> err.content
                else -> err.jsonObject["message"]?.jsonPrimitive?.content
            }
            if (!msg.isNullOrBlank()) detail = msg
        }

        // 文案按 DeepSeek 官方的错误码表（用户 2026-09-28 提供）对齐：
        // 400 格式错误 / 401 认证失败 / 402 余额不足 / 422 参数错误 /
        // 429 速率上限 / 500 服务器故障 / 503 服务器繁忙。
        return when (status) {
            400 -> "请求格式错误（400）：$detail"
            401, 403 -> "认证失败（$status）：API Key 无效。$detail"
            402 -> "余额不足（402）：请为 DeepSeek 账户充值。$detail"
            404 -> "接口或模型不存在（404）：检查 API 地址与模型名。$detail"
            422 -> "参数错误（422）：$detail"
            429 -> "请求速率达到上限（429）：$detail"
            503 -> "服务器繁忙（503）：稍后重试。$detail"
            in 500..599 -> "服务器故障（$status）：稍后重试。$detail"
            else -> "请求失败（$status）：$detail"
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /**
         * 摘要最多这么多 token。
         *
         * ⚠️ 摘要本身也必须省 —— 它是要**常驻**在每一轮请求里的（压缩后它替掉了整段历史），
         * 给到几千 token 就成了"用一种贵换另一种贵"。800 够写下几十轮对话的事实要点。
         */
        /**
         * 摘要的输出上限。
         *
         * ⚠️ v0.61.46：单一真源移到 [ContextCompress.SUMMARY_OUTPUT_TOKENS] ——
         * 压缩预算把它当"输出预留"从窗口里扣掉，两处不一致会让预算失真。
         */
        private const val SUMMARY_MAX_TOKENS = ContextCompress.SUMMARY_OUTPUT_TOKENS

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        /**
         * 短请求（拉模型 / 测试连通）专用 —— 超时必须**远小于** [defaultClient]。
         *
         * ⚠️ 那两个动作是"点一下、等一个确定答案"，用户正盯着看。用 120s 的 readTimeout
         *    会在地址写错或被墙时让界面**沉默两分钟**，结论只有一个：按钮看起来坏了。
         *    20 秒足够一次正常往返，失败也来得及说人话。
         */
        fun shortCallClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()

        /**
         * 规范化 API 端点 —— 用户只需填到域名，路径自动补全。
         *
         * 接受并统一以下写法：
         *   api.deepseek.com              → https://api.deepseek.com/v1
         *   https://api.deepseek.com      → https://api.deepseek.com/v1
         *   https://api.deepseek.com/v1   → 原样
         *   https://api.deepseek.com/v1/  → 去掉尾斜杠
         *   https://xxx/openai/v1         → 原样（已含版本段）
         */
        fun normalizeBaseUrl(input: String): String {
            var s = input.trim()
            if (s.isEmpty()) return DEFAULT_BASE_URL
            if (!s.startsWith("http://") && !s.startsWith("https://")) {
                s = "https://$s"
            }
            s = s.trimEnd('/')
            // 已含版本段则不重复添加
            if (!Regex("""/v\d+$""").containsMatchIn(s)) s += "/v1"
            return s
        }
    }
}
