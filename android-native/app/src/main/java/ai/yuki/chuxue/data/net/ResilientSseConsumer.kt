package ai.yuki.chuxue.data.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import ai.yuki.chuxue.data.ApiHttpException
import ai.yuki.chuxue.data.AppSettings
import ai.yuki.chuxue.data.ChatStreamEvent
import ai.yuki.chuxue.data.DeepSeekClient
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlin.random.Random

/**
 * 网络状态监听（开发文档 §14.3）。
 *
 * 用途：知道「现在有没有网」，从而把「网络断了」和「服务端拒绝」区分开 ——
 * 断网值得退避重试，401 重试一万次也没用。
 */
class ConnectivityMonitor(context: Context) {

    private val cm = context
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /** 在线 / 离线变化（去重，只在真正翻转时发一次）。 */
    val online: Flow<Boolean> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(true)
            }

            override fun onLost(network: Network) {
                trySend(false)
            }

            override fun onUnavailable() {
                trySend(false)
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        runCatching { cm.registerNetworkCallback(request, callback) }
        trySend(isOnline())
        awaitClose { runCatching { cm.unregisterNetworkCallback(callback) } }
    }.distinctUntilChanged()

    fun isOnline(): Boolean {
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}

/**
 * 带重连的 SSE 消费（开发文档 §14.3）。
 *
 * ## 只在「一个块都没收到」时重连 —— 这条纪律很重要
 * 重连意味着**从头再发一次请求**（我们没有服务端 `Last-Event-ID` 续传的保证）。
 * 如果已经吐出去一部分内容，重连就会**把那些内容再吐一遍** ——
 * 用户看到的是「她说话卡了一下，然后把前半句重复了一遍」。
 *
 * 所以：
 * - 还没收到任何内容 → 安全重试（用户什么都没看到，重试无感）
 * - 已经收到内容 → **不重试**，把错误抛上去；上层按 §9.5 保留已收到的部分
 *
 * 宁可让用户看到「说了一半断了」，也不要让他看到「同一句说了两遍」——
 * 前者是网络的事，后者像是她出了问题。
 */
class ResilientSseConsumer(
    private val client: DeepSeekClient,
    /** 每次准备重试前的回调（用于 UI 提示与测试观察） */
    private val onRetry: (suspend (attempt: Int, delayMs: Long) -> Unit)? = null,
) {

    fun events(
        settings: AppSettings,
        body: String,
        /**
         * **真·非流式**（v0.61.0）：一次 `chat/completions` 拿完整响应，
         * 再把它**合成**成"一个 Delta + 一个 Done"发出去。
         *
         * ## 为什么合成、而不是让上层走另一条路
         * 上层（`ChatViewModel`）那套消费逻辑 —— 思考计时、首字耗时、WAL 分片、
         * 空回复判定、落库 —— 是**与传输方式无关**的。让它只为"一次而不是 N 次投递"
         * 再分岔一遍，等于把同一套逻辑维护两份，迟早有一份漏改。
         * 这里合成，**上面一行都不用动**。
         *
         * ⚠️ 与流式**唯一**的真实差异是观感：非流式要等模型全写完才出第一个字。
         *    这是用户选这个模式时**明确要的**（"整段一次出现"），不是缺陷。
         */
        nonStream: Boolean = false,
    ): Flow<ChatStreamEvent> = flow {
        if (nonStream) {
            val outcome = client.chat(settings, body)
            outcome.reasoning?.let { emit(ChatStreamEvent.Reasoning(it)) }
            if (outcome.text.isNotBlank()) emit(ChatStreamEvent.Delta(outcome.text))
            emit(ChatStreamEvent.Done(outcome.text, outcome.cache, outcome.stopReason))
            return@flow
        }
        var attempt = 0
        while (true) {
            var receivedAny = false
            try {
                client.streamChat(settings, body).collect { ev ->
                    receivedAny = true
                    emit(ev)
                }
                return@flow // 正常结束
            } catch (e: Exception) {
                // ⚠️ **确定性失败不重试**（v0.44.1 修）。
                //
                // 原来这里对所有异常一视同仁地重连，包括 401 / 402 / 400 / 422 ——
                // 而那些是"换个时间也一模一样"的结果。后果不是"多试几次"，而是：
                // 明明 API **秒级**就返回了错误码，用户却要等半分钟，
                // 期间一个事件都收不到（界面停在"正在输入"），最后等来的是首字看门狗
                // 报的一句"服务器没有回应" —— 与真实原因（Key 错/余额不足）毫无关系。
                //
                // ⚠️ 429 与 5xx **仍然重试**（`isRetryable`）：那两类等一会儿确实可能成功。
                if (e is ApiHttpException && !e.isRetryable) throw e

                if (receivedAny || !ReconnectStrategy.shouldRetry(attempt)) throw e

                val delayMs = ReconnectStrategy.jitteredDelay(attempt, Random.nextDouble())
                onRetry?.invoke(attempt, delayMs)
                delay(delayMs)
                attempt++
            }
        }
    }
}
