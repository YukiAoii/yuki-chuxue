package ai.yuki.chuxue.data.memory

import ai.yuki.chuxue.data.room.MemoryDao
import ai.yuki.chuxue.data.room.MemoryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 记忆检索（开发文档 §7.5 的检索式分层 + §8.2 的强制过滤）。
 *
 * ## 三个乘数
 * ```
 * 排序分 = 相关度(query, 记忆) × 有效权重(重要性, 衰减)
 * ```
 * - **相关度**：与当前这句话有多沾边（[TextSimilarity]）。
 * - **有效权重**：这条记忆本身有多"重"、以及多久没被想起（[MemoryDecay]）。
 *
 * 只有相关度过 [RELEVANCE_THRESHOLD] 的才进入排序 —— **宁可不注入，也不注入不相关的**。
 * 理由：记忆注入走的是**附录**（`PromptEngine.buildAppendix`）。附录每轮都是新字节、
 * 本来就不吃缓存，但它**照样花 token**。塞进一堆无关记忆，等于每轮白烧钱，
 * 还会把角色的注意力从当前话题上带偏。
 *
 * ## 视野由 DAO 框死，不在这里做
 * 「能看到哪些记忆」是隔离问题，全部落在 [MemoryDao.candidatesFor] 的 WHERE 里
 * （persona 级无条件可见 / session 级必须 `sessionId` 匹配）。检索层只负责**排序与截断**，
 * 不参与可见性判断 —— 可见性一旦散落在多层，就迟早有一层漏掉。
 *
 * ## 命中即刷新
 * 被检索到的记忆会刷新 `lastAccessedAt`（[MemoryDao.touch]），从而在衰减里掉得更慢 ——
 * 「常被想起的事记得更牢」，这也是文档 §7.8 用 `lastAccessedAt` 而非 `createdAt` 的原因。
 */
class MemoryRetriever(private val dao: MemoryDao) {

    companion object {
        /**
         * 相关度下限。低于它就判为"与当前话题无关"，不注入。
         *
         * 取 0.15 的依据：共享一个 2 字词、而 query 本身不长时，相关度落在 0.2–0.3；
         * 只共享一个常用单字时按定义就是 0（[TextSimilarity.MIN_MATCH_LEN]）。
         * 0.15 卡在"至少共享一个两字片段、且该片段在 query 里占得上一席之地"附近。
         */
        const val RELEVANCE_THRESHOLD = 0.15

        /** 每次注入上限 5 条（文档 §25.6「记忆检索上限 5 条」）。 */
        const val DEFAULT_MAX_RESULTS = 5
    }

    /**
     * 纯函数排序：**不碰数据库、不读时钟**，因此可以在单测里直接喂候选列表断言结果。
     *
     * 排序键依次为：加权分 → 重要性 → id。最后一级用 id 是为了**确定性** ——
     * 加权分与重要性都相等时，若不指定末级，结果的先后就取决于 DAO 的返回顺序，
     * 同一份数据两次查询可能得到不同排列，测试会偶发假红。
     */
    fun rank(
        candidates: List<MemoryEntity>,
        userInput: String,
        now: Long,
        maxResults: Int = DEFAULT_MAX_RESULTS,
    ): List<MemoryEntity> =
        candidates
            .mapNotNull { memory ->
                val relevance = TextSimilarity.relevance(userInput, memory.content)
                if (relevance < RELEVANCE_THRESHOLD) {
                    null
                } else {
                    val weight = MemoryDecay.effectiveWeight(
                        importance = memory.importance,
                        lastAccessedAt = memory.lastAccessedAt,
                        now = now,
                    )
                    memory to relevance * weight
                }
            }
            .sortedWith(
                compareByDescending<Pair<MemoryEntity, Double>> { it.second }
                    .thenByDescending { it.first.importance }
                    .thenBy { it.first.id },
            )
            .take(maxResults.coerceAtLeast(0))
            .map { it.first }

    /**
     * 检索本轮应当注入附录的记忆。
     *
     * @param sessionId 当前会话 —— session 级记忆的可见性凭据，必传。
     */
    suspend fun retrieve(
        personaId: String,
        sessionId: String,
        userInput: String,
        maxResults: Int = DEFAULT_MAX_RESULTS,
        now: Long = System.currentTimeMillis(),
        userId: String = MemoryEntity.LOCAL_USER_ID,
    ): List<MemoryEntity> = withContext(Dispatchers.IO) {
        if (userInput.isBlank()) return@withContext emptyList()
        val candidates = dao.candidatesFor(userId, personaId, sessionId)
        if (candidates.isEmpty()) return@withContext emptyList()

        val picked = rank(candidates, userInput, now, maxResults)
        if (picked.isNotEmpty()) {
            dao.touch(picked.map { it.id }, now)
        }
        picked
    }
}
