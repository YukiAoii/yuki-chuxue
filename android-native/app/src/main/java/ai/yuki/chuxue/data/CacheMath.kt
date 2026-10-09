package ai.yuki.chuxue.data

/**
 * 前缀缓存的**计价机制**（纯函数）。
 *
 * ## 它回答的问题
 * 「为什么这一轮只命中这么多？」—— 这是本项目唯一**持续影响花钱**的机制，
 * 而它对用户完全不可见。没有这一层，用户只能感觉"最近好像变贵了"。
 *
 * ## 实测规律（本项目自己测出来的，不是官方文档写的）
 * ```
 * 命中 = 128 × (⌊上一次请求的 prompt_tokens / 128⌋ − 1)
 * ```
 * 即：**按 128 token 一块对齐，且末尾的完整块不计入**。
 *
 * 数据来自 `项目改动记录` 的 v0.47.x 各节 —— 7 组直连 API 的对照实验吻合，
 * 其中两组是写死在交接文档里的：283 → 128、10624（当轮输入 10968）。
 * 复算：`⌊283/128⌋ = 2` → `(2−1)×128 = 128` ✓
 *
 * ## ⚠️ 三条使用纪律（不遵守会让这一页变成误导）
 *
 * 1. **它是经验拟合，不是协议保证。** 样本只有 7 组。服务方随时可能改粒度。
 *    所以 [expectHitUpperBound] 的返回值只能当**量级参考**，不能当预测值上屏
 *    去和真实命中做"对不上就是 bug"的比对。
 * 2. **"上一次"不是"这一次"。** 决定本轮命中的，是**上一轮**发出去的长度；
 *    本轮新增的 user 消息与附录还没进缓存。把两者混为一谈会算出一个偏大的数。
 * 3. **短输入命中率天然低**，这是块粒度导致的，**不是缺陷**。
 *    见 [shortInputNote]。
 *
 * ## 为什么它不进 [ContextCompress]
 * [ContextCompress] 管的是"**发多少**"（组装请求、压缩判定），
 * 这一层管的是"**发了之后命中多少**"（计价机制）。两件事的变更原因不同，
 * 混在一起将来改一个会误伤另一个。
 */
object CacheMath {

    /**
     * 缓存块粒度（token）。
     *
     * ⚠️ 这个数**不是可调参数**：它是实测出来的服务端行为。
     * 改它等于假装服务端换了粒度 —— 除非重新做一轮对照实验，否则别动。
     */
    const val BLOCK = 128

    /**
     * 上一次发出去 [prevInputTokens] 个 token 时，**这一轮最多能命中多少**。
     *
     * 末尾那个完整块不计入 —— 它是"紧接着要新写的那一块"，缓存里还没有。
     * 所以上轮不足 2 块（< 256）时，本轮**一块都命不中**，返回 0。
     *
     * ## ⚠️ 这是**上限**，不是预测
     * 真实命中还受"前缀有没有逐字节变化"影响：改人设、改历史、压缩过一次，
     * 都会让前缀从某处断开 → 实际命中**低于**这个上限。
     * 界面上如果要显示，措辞必须是「最多能命中」，不能是「应该命中」。
     */
    fun expectHitUpperBound(prevInputTokens: Int): Int {
        if (prevInputTokens < BLOCK * 2) return 0
        val blocks = prevInputTokens / BLOCK
        return (blocks - 1) * BLOCK
    }

    /**
     * 一个 token 数对应多少个**完整块**。
     *
     * 用途是**佐证**：真实命中的 `hitTokens` 如果不是 128 的整数倍，
     * 说明命中的不是整块 —— 那与本项目观测到的规律不符，值得怀疑。
     */
    fun blockCount(tokens: Int): Int = if (tokens <= 0) 0 else tokens / BLOCK

    /**
     * 这个数是否正好落在块边界上。
     *
     * 命中量**应当**总是 128 的整数倍（[BLOCK] 的注释）。
     * 这条判据在诊断页上做一次自检：不整除说明前提变了，
     * 比闷头显示一个百分比有用得多。
     */
    fun isBlockAligned(tokens: Int): Boolean = tokens > 0 && tokens % BLOCK == 0

    /**
     * 命中率（0..1）。分母为 0 时返回 0 而不是崩。
     *
     * ⚠️ 与 [formatHitPercent] 的分工：这里只算数，**不做"别把 99.6% 说成 100%"**
     * 那类展示取舍 —— 那是格式化层的事，两者不要混。
     */
    fun hitRatio(hitTokens: Int, missTokens: Int): Double {
        val billed = hitTokens + missTokens
        return if (billed <= 0) 0.0 else hitTokens.toDouble() / billed
    }

    /**
     * 输入很小时，给一句解释 —— 免得用户把"命中 45%"当成故障。
     *
     * 例：输入 283 → 只能命中 128 = 45%。它不是"缓存没生效"，
     * 而是 283 只够切成 2 块（256），而其中一块按规则不计入。
     *
     * @return 需要解释时返回一句话；输入已经够大（>= 4 块）时返回 null
     */
    fun shortInputNote(inputTokens: Int): String? {
        if (inputTokens <= 0 || inputTokens >= BLOCK * 4) return null
        val blocks = blockCount(inputTokens)
        return "这一轮只发出去 $inputTokens token，切成 $blocks 个 128 的块 —— " +
            "块数太少，命中率天然上不去（末尾那块还不计入）。这不是缓存失效。"
    }

    /**
     * 由命中量**反推**上一轮发出去了多少 token。
     *
     * ## 为什么反推有用
     * 决定本轮命中的是**上一轮**的长度，而这个数在 App 里没有留存 ——
     * 服务端只在响应里给 `prompt_cache_hit_tokens` / `miss_tokens`，
     * 不给"上一轮发了多少"。但命中量本身泄露了它：
     *
     * ```
     * hit = 128 × (n − 1)   →   n = hit/128 + 1
     * 上一轮输入 ∈ [n×128, n×128 + 127]
     * ```
     *
     * 例：命中 10624 → 83 块 → 上一轮发了 **10752~10879**（84 块那一档）。
     * 拿它和"这一轮实际发出"一比，就能看出这一轮新增了多少内容。
     *
     * ## ⚠️ 返回的是**区间**，不是精确值
     * 命中量只精确到块，所以只能定到 128 宽的一档。界面上写"约"。
     *
     * @return 命中量非法（<=0 或不是整块）时返回 null —— 反推不出来
     */
    fun previousInputRange(hitTokens: Int): IntRange? {
        if (!isBlockAligned(hitTokens)) return null
        val blocks = hitTokens / BLOCK + 1        // 反推的块数
        val low = blocks * BLOCK
        return low until (low + BLOCK)
    }
}
