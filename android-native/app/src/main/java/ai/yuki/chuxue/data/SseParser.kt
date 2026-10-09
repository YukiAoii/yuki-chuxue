package ai.yuki.chuxue.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * SSE 流的一行解析结果（开发文档 §9）。
 */
sealed interface SseChunk {
    /** 一段正文增量 —— 追加到流式缓冲区。 */
    /**
     * 正文增量。
     *
     * ⚠️ **同时可能带缓存统计**（v0.46.3）：DeepSeek 的最后一块**常常正文与 usage 同块**，
     * 而下面 `when` 的分支是"正文优先" —— 如果 usage 只挂在 `Done` 上，
     * 它就会**被这一分支直接丢掉**，统计永远累加不上（用户报的"命中率只降不升"报了两次，
     * 第一次是 `[DONE]` 覆盖，第二次就是这里）。
     */
    data class Delta(val text: String, val usage: CacheStats? = null) : SseChunk

    /**
     * 一段**思考过程**增量（`delta.reasoning_content`）。
     *
     * 这是「思考模式真实生效」的证据所在：早先的解析只读 `delta.content`，
     * 于是开关能打开、参数也发出去了，用户却永远看不到思考内容 ——
     * 那是**表象假开启**。把思考单独做成一种 chunk，UI 才能分开画
     * （思考折叠成一条、正文照常显示）。
     */
    data class Reasoning(val text: String) : SseChunk

    /**
     * 流结束。可能带 usage（DeepSeek 在最后一块里给出缓存统计）。
     * 由 `finish_reason` 或 `[DONE]` 触发。
     */
    /**
     * 流结束。
     *
     * @param reason 服务端给的 `finish_reason`（`stop` / **`length`** / `tool_calls` …）。
     *   ⚠️ v0.61.0 之前这里**读到了却丢掉** —— `finish_reason` 只被当作"流结束了没"的
     *   布尔用，值本身没传下去。于是"输出被长度上限截断"这件事到了用户面前
     *   只剩一句含糊的"content 为空"，没人知道真正的原因是**截断**。
     *   天枢为此专门有 `isTruncationStopReason`（`src/agent/worker-repair-route.ts`），
     *   并把截断事实一路透传到失败结果里 —— 这里照做。
     */
    data class Done(val cache: CacheStats?, val reason: String? = null) : SseChunk

    /** 与本项目无关的行：空行、注释、keep-alive、字段缺失。 */
    data object Ignore : SseChunk
}

/**
 * DeepSeek SSE 流的逐行解析（开发文档 §9 / §14）。
 *
 * ## 为什么单独抽出来
 * 流式链路里，网络与 UI 都无法在无设备环境验证，**但解析逻辑可以**。
 * 这是整条 SSE 链路上唯一能被单元测试钉死的部分，所以它必须独立、无副作用、可穷举。
 *
 * ## 协议形状（OpenAI 兼容）
 * ```
 * data: {"choices":[{"delta":{"content":"你"}}]}
 *
 * data: {"choices":[{"delta":{},"finish_reason":"stop"}]}
 *
 * data: [DONE]
 * ```
 *
 * ## 容错纪律
 * **任何解析不出来的行都返回 [SseChunk.Ignore]，绝不抛异常。**
 * 流式过程中一条坏行不该把整段回复炸掉 —— 用户已经等了半天，
 * 宁可少一个字，也不能一个字都不给。
 */
object SseParser {

    private const val DATA_PREFIX = "data:"
    const val DONE_MARKER = "[DONE]"

    /**
     * 解析 SSE 流的一行。
     *
     * @param raw 原始行（可能带 `\r`、前后空白）
     */
    fun parseLine(raw: String): SseChunk {
        val line = raw.trim()
        if (line.isEmpty()) return SseChunk.Ignore
        if (!line.startsWith(DATA_PREFIX)) return SseChunk.Ignore

        val payload = line.removePrefix(DATA_PREFIX).trim()
        if (payload.isEmpty()) return SseChunk.Ignore
        // ⚠️ `[DONE]` **仍然是结束信号** —— 流里可能根本没有带 usage 的块，
        // 那时它是唯一的终止依据（忽略它会让流永不结束）。
        // "usage 被 `[DONE]` 覆盖成空"那个 bug 在 `DeepSeekClient` 一侧修
        //（它记住整轮里最后一次非空的 cache，见那里的注释）。
        if (payload == DONE_MARKER) return SseChunk.Done(null)

        val root = runCatching { Json.parseToJsonElement(payload).jsonObject }.getOrNull()
            ?: return SseChunk.Ignore

        val choice = runCatching {
            root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
        }.getOrNull()

        val deltaObject = runCatching { choice?.get("delta")?.jsonObject }.getOrNull()

        val delta = runCatching {
            deltaObject?.get("content")?.jsonPrimitive?.contentOrNull
        }.getOrNull()

        // 思考过程与正文**同级**（官方文档 §思考模式：响应字段 reasoning_content）
        val reasoning = runCatching {
            deltaObject?.get("reasoning_content")?.jsonPrimitive?.contentOrNull
        }.getOrNull()

        val finishReason = runCatching {
            choice?.get("finish_reason")?.jsonPrimitive?.contentOrNull
        }.getOrNull()

        val usage = runCatching { root["usage"]?.jsonObject }.getOrNull()

        return when {
            // 正文优先：切换的那一块可能两者同时到达，先给正文 ——
            // 正文才是用户在等的东西，思考那半截丢了也不影响回答正确性。
            // ⚠️ 把 usage **一起带出去**（v0.46.3）：它可能和正文同块，
            // 而"正文优先"的分支顺序会让它静默消失。
            !delta.isNullOrEmpty() -> SseChunk.Delta(delta, parseUsage(usage).takeIf { usage != null })
            !reasoning.isNullOrEmpty() -> SseChunk.Reasoning(reasoning)
            finishReason != null || usage != null -> SseChunk.Done(parseUsage(usage), finishReason)
            else -> SseChunk.Ignore
        }
    }

    /** 把 usage 对象翻成 [CacheStats]；缺字段按 0 处理。 */
    private fun parseUsage(usage: kotlinx.serialization.json.JsonObject?): CacheStats? {
        if (usage == null) return null
        fun field(name: String): Int =
            runCatching { usage[name]?.jsonPrimitive?.intOrNull }.getOrNull() ?: 0
        return CacheStats(
            hitTokens = field("prompt_cache_hit_tokens"),
            missTokens = field("prompt_cache_miss_tokens"),
            inputTokens = field("prompt_tokens"),
            outputTokens = field("completion_tokens"),
            // ⚠️ v0.51.0 加法式读取：**不动**上面那几个字段（DeepSeek 的解析逐字节不变），
            //    只是顺手把 OpenAI 系的嵌套命中量也取出来。读哪个由
            //    `ProviderProfile.cachedTokenField` 决定（见 `hitTokensOf`）。
            //    取不到就是 `null`（= 这家没报），不是 0。
            nestedHitTokens = runCatching {
                usage["prompt_tokens_details"]
                    ?.jsonObject
                    ?.get("cached_tokens")
                    ?.jsonPrimitive
                    ?.intOrNull
            }.getOrNull(),
        )
    }

    /**
     * 判断流是否应当结束。
     *
     * 由 `finish_reason` / `[DONE]` 触发；**网络层的 EOF 也算结束**
     * （弱网下服务端可能直接断开而不发 `[DONE]`）。
     */
    fun isTerminal(chunk: SseChunk): Boolean = chunk is SseChunk.Done
}
