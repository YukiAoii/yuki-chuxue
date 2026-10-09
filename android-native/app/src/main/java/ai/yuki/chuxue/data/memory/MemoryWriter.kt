package ai.yuki.chuxue.data.memory

import ai.yuki.chuxue.data.room.MemoryDao
import ai.yuki.chuxue.data.room.MemoryEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * 记忆写入（开发文档 §7.6 + §8.2 的强制过滤清单）。
 *
 * ## 这里守的是什么
 * 写入是**唯一**能破坏记忆隔离的入口 —— 检索再严谨，只要写错了一层的归属，
 * 记忆就已经串了。所以三条校验都放在这里，且**失败即抛，绝不静默降级**：
 *
 * | 校验 | 依据 | 违反后果 |
 * |---|---|---|
 * | `scope` 只能是 persona / session | §7.6 | 未知作用域的可见性无从判定 |
 * | `scope=session` 必须带 `sessionId` | §8.2 | 会话级记忆会变成"谁都能看见" |
 * | `content` 非空 | — | 空记忆会污染检索池且永远匹配不到 |
 *
 * ## 去重只在相同作用域内做（文档 §8.2）
 * persona 级的一句话说给所有会话听，session 级的一句只属于那一次对话。
 * 跨作用域合并会把「对所有人说的话」和「只对这一次说的话」搅在一起 ——
 * 所以 [MemoryDao.sameScope] 才要求带上 `scope`（和会话级时的 `sessionId`）。
 *
 * ## 合并语义（文档 §7.6）
 * 命中重复时不新建，而是**就地更新**：重要性取两者较大值，内容取**重要性更高**的那一份
 * （更"重要"的表述优先保留），并刷新访问时间。记忆条目因此不会随重复提取而膨胀。
 */
class MemoryWriter(private val dao: MemoryDao) {

    companion object {
        /**
         * 语义去重阈值（文档 §25.6「语义去重阈值 0.9」）。
         *
         * 文档原文针对的是**余弦相似度**。这里用的是 [TextSimilarity] 的降级度量，
         * 但阈值沿用同一个数字：两条记忆在这个度量下达到 0.9，意味着它们几乎是同一句话。
         * 阈值再低会误合并（"喜欢猫"与"喜欢狗"不该合并），再高会漏合并（措辞微调就重复入库）。
         */
        const val DUPLICATE_THRESHOLD = 0.9

        /** 重要性取值域 0–10，越界直接夹紧而不是抛 —— 调用方给错范围不该让写入失败。 */
        const val MIN_IMPORTANCE = 0
        const val MAX_IMPORTANCE = 10

        /**
         * 写路径的**进程内串行锁**（v0.61.21）。
         *
         * ## 它修的是什么
         * [write] 的去重是"**读同域 → 算相似度 → 写**"三步，中间没有事务也没有锁。
         * 而两条写路径分属不同协程、不同 `MemoryWriter` 实例（后台自动提取一个、
         * 记忆页手动编辑一个），它们完全可能在读与写之间交错 ——
         * 各自都看不到对方那条，于是**同一条记忆入两份**（UUID 不同，去重再也认不出）。
         *
         * ⚠️ 用 **companion 级**的锁而不是实例级：`MemoryWriter` 是每个 `MemoryRepository`
         *    造一个，而仓库在这个 App 里不止一个（聊天页 / 记忆页 / 后台提取各一份）。
         *    实例级的锁拦不住它们。
         * ⚠️ 为什么不是 Room 事务：相似度是在 Kotlin 里算的，SQL 表达不了，
         *    真做成事务得把去重整段挪进 DAO —— 那是更大的改动（记在遗留里）。
         */
        private val writeLock = Mutex()

        /**
         * 合并时该用谁的正文（v0.61.21）。
         *
         * ⚠️ **用户手改过的一律不覆盖**：来源不再是 [MemoryEntity.SOURCE_AUTO] 就说明
         * 那句话是用户自己写的（或在记忆页改过的）—— 后台不该用自动文本把它盖掉。
         * 剩下的才是原来的规则：新的更重要的表述优先，否则只抬重要性、保住原文。
         */
        internal fun mergedContent(
            existing: MemoryEntity,
            incoming: String,
            incomingImportance: Int,
        ): String = when {
            existing.source != MemoryEntity.SOURCE_AUTO -> existing.content
            incomingImportance > existing.importance -> incoming
            else -> existing.content
        }
    }

    /**
     * 写入一条记忆；若同作用域内已存在近似条目，则合并并返回合并后的实体。
     *
     * ⚠️ v0.61.21：整段跑在 [writeLock] 里 —— "读同域 → 算相似度 → 写"三步必须原子，
     * 否则两条写路径（后台提取 / 界面编辑）交错时会重复入库。见 writeLock 的注释。
     *
     * @throws IllegalArgumentException 校验不通过（见类注释的表格）。
     */
    suspend fun write(
        personaId: String,
        scope: String,
        content: String,
        category: String,
        importance: Int,
        source: String,
        sessionId: String? = null,
        userId: String = MemoryEntity.LOCAL_USER_ID,
        now: Long = System.currentTimeMillis(),
    ): MemoryEntity = writeLock.withLock {
        writeLocked(personaId, scope, content, category, importance, source, sessionId, userId, now)
    }

    /**
     * 把这个角色的全部**会话级**记忆升级为人设级（「一键迁移」，用户可点，不是静默迁移）。
     *
     * ## 为什么逐条走 [write] 而不是一条 UPDATE
     * 升级时会碰到「会话里的这句话，人设域里已有一条近似」——直接改 `scope` 列会留下
     * 两条近似的人设级记忆（检索时重复浮现）。走 [write] 就自动合并且沿用合并语义
     * （重要性取大、用户手改过的正文不被覆盖）。
     *
     * ## 可重入
     * 处理一条：写入成功 → 删原条。中途失败就停在半路，再点一次继续处理剩余的 ——
     * 不为它引入跨「读改删」的事务（同 [writeLock] 注释里的取舍）。
     *
     * @return 处理（迁移掉）的条数
     */
    suspend fun migrateSessionToPersona(
        personaId: String,
        userId: String = MemoryEntity.LOCAL_USER_ID,
        /**
         * 要**排除**的会话 id（= 发起迁移时用户所在的那一段）。
         *
         * ⚠️ v0.61.50 修（审查指出的"判据/动作分叉"）：入口显示的是「别的对话里的 N 条」
         *（判据已排除当前会话），但动作原来迁移**全部**会话级 —— 于是
         * ① 用户看到 N、点完提示 M（M > N）对不上；
         * ② 当前对话里那几条（语义是"只在这段剧情里"）被静默升成跨会话共享。
         * 现在动作与判据**同源**：都把当前会话排除在外。
         */
        excludeSessionId: String? = null,
    ): Int {
        val legacy = dao.allSessionOfPersona(userId, personaId)
            .filter { excludeSessionId.isNullOrBlank() || it.sessionId != excludeSessionId }
        var processed = 0
        for (old in legacy) {
            write(
                personaId = personaId,
                scope = MemoryEntity.SCOPE_PERSONA,
                content = old.content,
                category = old.category,
                importance = old.importance,
                source = old.source,
                userId = userId,
                now = System.currentTimeMillis(),
            )
            dao.delete(old.id)
            processed += 1
        }
        return processed
    }

    private suspend fun writeLocked(
        personaId: String,
        scope: String,
        content: String,
        category: String,
        importance: Int,
        source: String,
        sessionId: String? = null,
        userId: String = MemoryEntity.LOCAL_USER_ID,
        now: Long = System.currentTimeMillis(),
    ): MemoryEntity {
        val text = content.trim()
        require(text.isNotEmpty()) { "记忆内容不能为空" }
        require(
            scope == MemoryEntity.SCOPE_PERSONA || scope == MemoryEntity.SCOPE_SESSION,
        ) { "非法 scope：$scope" }
        require(scope != MemoryEntity.SCOPE_SESSION || sessionId != null) {
            "session 作用域的记忆必须带 sessionId"
        }

        val clippedImportance = importance.coerceIn(MIN_IMPORTANCE, MAX_IMPORTANCE)

        // 去重池：只有同作用域的记忆参与比对
        val scopeSessionId = if (scope == MemoryEntity.SCOPE_SESSION) sessionId else null
        val sameScope = dao.sameScope(userId, personaId, scope, scopeSessionId)
        val duplicate = sameScope.firstOrNull {
            TextSimilarity.similarity(it.content, text) >= DUPLICATE_THRESHOLD
        }

        if (duplicate != null) {
            val merged = duplicate.copy(
                // 更重要的表述优先 —— 若新记忆更不重要，保留原文，只抬重要性。
                // ⚠️ 但**用户手改过的永不覆盖**（v0.61.21），规则收在 mergedContent 里（有单测）。
                content = mergedContent(duplicate, text, clippedImportance),
                importance = maxOf(clippedImportance, duplicate.importance),
                lastAccessedAt = now,
            )
            dao.upsert(merged)
            return merged
        }

        val entity = MemoryEntity(
            id = UUID.randomUUID().toString(),
            userId = userId,
            personaId = personaId,
            // persona 级记忆不带 sessionId —— 它属于角色，不属于某一次对话
            sessionId = scopeSessionId,
            scope = scope,
            content = text,
            category = category,
            importance = clippedImportance,
            source = source,
            embedding = null,
            createdAt = now,
            lastAccessedAt = now,
            expiresAt = null,
        )
        dao.upsert(entity)
        return entity
    }
}
