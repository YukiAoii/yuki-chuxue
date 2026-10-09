package ai.yuki.chuxue.data.memory

import kotlin.math.pow

/**
 * 记忆衰减（开发文档 §7.8）。
 *
 * ```
 * effectiveWeight = importance × 0.5 ^ (距上次被想起的天数 / 半衰期)
 * ```
 *
 * ## 为什么必须有衰减
 * 长期记忆产品最隐蔽的失效方式是**无限膨胀**：聊到第 100 天，库里几千条记忆，
 * 检索池越来越大、噪声越来越多，真正重要的那条被淹没。衰减让「很久没被想起」
 * 的记忆权重自然下沉，从而**不需要删除**也能被新记忆挤下去 ——
 * 这比"定期清理"温和得多，也不会让角色突兀地忘掉什么。
 *
 * ## 为什么是"上次被访问"而不是"创建时间"
 * 按创建时间衰减 = 越老的记忆越没价值，这不符合人的记忆：**常被想起的事记一辈子**
 * （文档 §7.8 的 `lastAccessedAt`）。所以 [MemoryRetriever] 每次命中都会刷新它。
 *
 * ⚠️ 这是纯函数：不读时钟、不碰数据库，`now` 由调用方传入 —— 因此「一万天后掉到多少」
 * 能在单测里精确断言，而不是"跑起来看看"。
 */
object MemoryDecay {

    /** 半衰期 30 天（文档 §25.6「记忆半衰期 30 天」）。 */
    const val DEFAULT_HALF_LIFE_DAYS = 30.0

    private const val MS_PER_DAY = 1000.0 * 60.0 * 60.0 * 24.0

    /**
     * 记忆的当前有效权重 = 重要性 × 衰减系数。
     *
     * `lastAccessedAt` 在未来（时钟回拨）时按 0 天处理，不让衰减算出大于 1 的增益。
     */
    fun effectiveWeight(
        importance: Int,
        lastAccessedAt: Long,
        now: Long,
        halfLifeDays: Double = DEFAULT_HALF_LIFE_DAYS,
    ): Double {
        val days = (now - lastAccessedAt).coerceAtLeast(0L) / MS_PER_DAY
        val halfLife = halfLifeDays.coerceAtLeast(0.001)
        val decay = 0.5.pow(days / halfLife)
        return importance * decay
    }
}
