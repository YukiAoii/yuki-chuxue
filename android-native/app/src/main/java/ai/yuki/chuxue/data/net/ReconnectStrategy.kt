package ai.yuki.chuxue.data.net

import kotlin.math.min

/**
 * 重连退避策略（开发文档 §14.5 / §25.6）。
 *
 * ## 为什么要退避
 * 网络断掉时如果立刻重试，在弱网下会变成**每秒一次**的请求风暴 ——
 * 既耗用户流量，也可能被服务端限流（429）。
 * 指数退避让等待时间随失败次数拉长，给它恢复的机会。
 *
 * ## 为什么要抖动
 * 如果每次等待都是精确的 1s / 2s / 4s，多个客户端可能在同一毫秒一起重试
 * （**惊群**）。加 0.5–1.0 倍的随机抖动把重试时刻打散。
 *
 * 抽成纯对象是为了**能单元测试**：随机的部分通过参数注入，
 * 规则本身完全确定。
 */
object ReconnectStrategy {

    /** 基础等待 */
    const val BASE_MS = 1_000L

    /** 等待上限（文档 §25.6：退避最大 30 秒） */
    const val MAX_MS = 30_000L

    /** 指数最多翻到这个次幂（1 << 5 = 32 倍，再往上就被 MAX 截住了） */
    private const val MAX_EXPONENT = 5

    /**
     * 第 [attempt] 次重试的基础等待（不含抖动）。`attempt` 从 0 开始。
     *
     * 1s → 2s → 4s → 8s → 16s → 30s（封顶）→ 30s …
     */
    fun baseDelay(attempt: Int): Long {
        val safeAttempt = attempt.coerceAtLeast(0).coerceAtMost(MAX_EXPONENT)
        val exponential = BASE_MS shl safeAttempt
        return min(exponential, MAX_MS)
    }

    /**
     * 加上抖动后的实际等待：`base × [0.5, 1.0)`。
     *
     * @param random 取值 [0, 1)，由调用方注入（测试里给固定值即可确定断言）
     */
    fun jitteredDelay(attempt: Int, random: Double): Long {
        val r = random.coerceIn(0.0, 1.0)
        val factor = 0.5 + 0.5 * r
        return (baseDelay(attempt) * factor).toLong()
    }

    /** 连续失败多少次后放弃（文档 §25.6：最大连续失败 10 次）。 */
    const val MAX_ATTEMPTS = 10

    /** 还能不能继续重试。 */
    fun shouldRetry(attempt: Int): Boolean = attempt < MAX_ATTEMPTS
}
