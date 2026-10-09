package ai.yuki.chuxue.data.memory

/**
 * 文本相关度 —— 开发文档 §7.1「语义检索 / 语义去重」的**关键词降级实现**。
 *
 * ## 为什么是降级，以及为什么降得起
 * 文档 §7.1 的原方案是 `sqlite-vec + BGE-small-zh(ONNX) + 余弦相似度`。实测该栈在
 * 本环境**不可得**：`sqlite-vec-android` 的 JitPack 坐标返回 `401`，
 * `BGE-small-zh` 的 ONNX 托管在 huggingface、连接超时（探针记录见交接文档）。
 * 缺了向量索引和嵌入模型两件，端侧语义检索无从谈起。
 *
 * 文档 §25.3「错误处理规范」自己给这条留了出路：
 * ```
 * ONNX 模型加载失败 → 降级为关键词检索
 * ```
 * 所以这里实现的是**文档认可的第一降级档**。它的召回质量不如向量，但：
 * 1. 零 native 依赖 —— 能在 JVM 单测里被完整钉死，而不是"交付了但无法验证"；
 * 2. 不随设备/ABI 变化 —— 同样的输入永远得到同样的分数；
 * 3. 不增加 APK 体积（向量栈约 +20MB so 与 +24MB 模型资产）。
 *
 * ## 度量选型：最长公共子串，而不是 bigram 余弦
 * 直觉上会用「字符 bigram 集合的余弦/Dice」。实测算过一遍：query
 * 「我今天加班好累」（6 个 bigram）命中记忆里的「加班」（1 个 bigram），
 * 余弦只有 `1/√(6×29) ≈ 0.076` —— **真正相关的反而被压到阈值以下**，
 * 因为长记忆的 bigram 基数把分数稀释了。
 *
 * 换用「最长公共子串长度 / 较短一方长度」，同一例得到 `2/7 ≈ 0.29`，
 * 与人的直觉一致：**它们共享一个 2 字的词**。短文本场景下这个度量更稳。
 *
 * ## 为什么要求至少 2 字重合
 * 单字重合（「我」「的」「是」「了」）几乎必然发生，用它判相关等于没有过滤。
 * 所以 [MIN_MATCH_LEN] = 2：**必须共享一个至少 2 字的连续片段**才记为相关。
 */
object TextSimilarity {

    /** 相关判定的最小公共子串长度。1 会让「我 / 的 / 是」这类常用字虚报相关。 */
    const val MIN_MATCH_LEN = 2

    /**
     * 归一化：只保留字母与数字（含 CJK 汉字），丢弃空白与标点，英文转小写。
     *
     * 丢弃标点是刻意的 —— 「加班，好累」与「加班好累」应当等价，
     * 而标点会让公共子串在标点处中断，凭空压低相关度。
     */
    fun normalize(raw: String): String = buildString(raw.length) {
        raw.forEach { c ->
            if (c.isLetterOrDigit()) append(c.lowercaseChar())
        }
    }

    /**
     * 字符级最长公共子串长度（滚动数组 DP）。
     *
     * 输入规模：query 通常 < 200 字，记忆条目通常 < 500 字 → 单次约 10⁵ 次比较，
     * 候选池是「本角色的全部记忆」（几十到几百条），量级完全可接受。
     * ⚠️ 但调用方必须保证不在主线程执行（见 [MemoryRetriever] 的 IO 派发）。
     */
    fun longestCommonSubstring(a: String, b: String): Int {
        if (a.isEmpty() || b.isEmpty()) return 0
        var prev = IntArray(b.length + 1)
        var cur = IntArray(b.length + 1)
        var best = 0
        for (i in 1..a.length) {
            val ca = a[i - 1]
            for (j in 1..b.length) {
                if (ca == b[j - 1]) {
                    val len = prev[j - 1] + 1
                    cur[j] = len
                    if (len > best) best = len
                } else {
                    cur[j] = 0
                }
            }
            val swap = prev
            prev = cur
            cur = swap
            java.util.Arrays.fill(cur, 0)
        }
        return best
    }

    /**
     * 相关度 ∈ [0, 1]：`最长公共子串 / 较短一方长度`。
     *
     * 除数是 `min(len)` 而非 `max(len)`：短 query 命中长记忆里的一个词时，
     * 不该因为「记忆比我长」而被惩罚 —— 该惩罚的是「query 里大部分内容都对不上」。
     *
     * 返回 0 表示达不到 [MIN_MATCH_LEN]，即判为不相关。
     */
    fun relevance(query: String, memory: String): Double {
        val q = normalize(query)
        val m = normalize(memory)
        if (q.isEmpty() || m.isEmpty()) return 0.0
        val lcs = longestCommonSubstring(q, m)
        if (lcs < MIN_MATCH_LEN) return 0.0
        return lcs.toDouble() / minOf(q.length, m.length)
    }

    /** 去重判定的相似度 —— 与 [relevance] 同度量，语义上「两条记忆是不是同一件事」。 */
    fun similarity(a: String, b: String): Double = relevance(a, b)
}
