package ai.yuki.chuxue.data.room

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * 长期记忆读写（开发文档 §8.2 的「强制过滤清单」，逐条落到查询里）。
 *
 * ## 为什么每条查询都把 `userId` / `personaId` 写在 WHERE 里
 * 隔离不能靠调用方自觉 —— 只要有一个调用点漏传，[MemoryEntity] 就串到别的角色身上，
 * **而且不会报错**。把两个维度做成 DAO 的**必填参数**，漏传就是编译不过，
 * 这是把文档 §8.2 的「所有查询强制带」落成类型约束，而不是注释。
 *
 * ## scope 的可见性规则（文档 §8.2）
 * - `scope = 'persona'`：本角色任何会话都可见
 * - `scope = 'session'`：只有 `sessionId` 匹配的那个会话可见
 */
@Dao
interface MemoryDao {

    /**
     * 检索候选：**这个人设**的记忆（人设级全部 + 本会话的会话级）。
     *
     * ⚠️ 2026-10-05 语义变更（用户要求，第二次）：默认人设级 —— 可见范围恢复
     * 「scope='persona' 跨会话可见」那一支，同时保留会话级（只在本会话可见）。
     * （沿革：2026-09-28 曾按要求全部会话化、移除 persona 支；两次都是需求方
     * 拍板，注释保留是为了下一位读者不会再把其中一次当成 bug 修掉。）
     *
     * `personaId` 仍然一起对 —— 换了人设而 sessionId 撞车（理论上的可能）时不串。
     *
     * 排序交给上层 [ai.yuki.chuxue.data.memory.MemoryRetriever]（它要算衰减加权），
     * 这里只负责把**可见范围**框死。
     */
    @Query(
        """
        SELECT * FROM memories
        WHERE userId = :userId AND personaId = :personaId
          AND (scope = 'persona' OR sessionId = :sessionId)
        """,
    )
    suspend fun candidatesFor(
        userId: String,
        personaId: String,
        sessionId: String,
    ): List<MemoryEntity>

    /**
     * 同作用域内的全部记忆 —— 语义去重的比对池（文档 §8.2「去重只在相同作用域内做」）。
     *
     * `scope = 'persona'` 时不看 `sessionId`；`scope = 'session'` 时必须匹配。
     * 跨作用域去重是错的：会话级的一句"我今天加班"和另一个会话的同一句话是两件独立的事。
     */
    @Query(
        """
        SELECT * FROM memories
        WHERE userId = :userId AND personaId = :personaId AND scope = :scope
          AND (:scope = 'persona' OR sessionId = :sessionId)
        """,
    )
    suspend fun sameScope(
        userId: String,
        personaId: String,
        scope: String,
        sessionId: String?,
    ): List<MemoryEntity>

    /**
     * 记忆管理页的数据源（开发文档 §42）—— 列**这个人设可见的**记忆
     * （人设级全部 + 本会话的会话级），与检索的可见范围一致。
     *
     * ⚠️ 2026-10-05：由 `observeBySession`（只认 sessionId）改回可见范围视角 ——
     * 记忆改回人设级之后，管理页要能看到"Ta 跨会话都记得什么"，否则用户管不到
     * 新写入的人设级记忆。列出的每条都带 scope，界面可据此区分展示。
     */
    @Query(
        """
        SELECT * FROM memories
        WHERE userId = :userId AND personaId = :personaId
          AND (scope = 'persona' OR sessionId = :sessionId)
        ORDER BY importance DESC, createdAt DESC
        """,
    )
    fun observeVisible(
        userId: String,
        personaId: String,
        sessionId: String,
    ): Flow<List<MemoryEntity>>

    @Upsert
    suspend fun upsert(memory: MemoryEntity)

    /**
     * 批量刷新访问时间 —— 衰减按 `lastAccessedAt` 计算，被想起来的记忆掉得慢（文档 §7.8）。
     */
    @Query("UPDATE memories SET lastAccessedAt = :at WHERE id IN (:ids)")
    suspend fun touch(ids: List<String>, at: Long)

    /** 记下/清掉这条记忆在**云端**的桶 id（v0.61.38；删除时按它对上云端那条）。 */
    @Query("UPDATE memories SET cloudBucketId = :bucketId WHERE id = :id")
    suspend fun setCloudBucketId(id: String, bucketId: String?)

    /** 按 id 取一条（删除时要读它的 cloudBucketId；列表流里那份可能已经不是最新）。 */
    @Query("SELECT * FROM memories WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): MemoryEntity?

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun delete(id: String)

    /**
     * 删人设时连带删它的全部记忆。
     * 人设不在 Room，无法用外键级联 —— 这条必须由调用方显式执行，别漏。
     */
    @Query("DELETE FROM memories WHERE userId = :userId AND personaId = :personaId")
    suspend fun deleteByPersona(userId: String, personaId: String)

    /**
     * 删掉**某一轮之后**自动提取的记忆（v0.61.9，配合"删除最后一轮"）。
     *
     * ⚠️ 记忆条目**没有 messageId** —— 归属只能按「会话 + 来源 + 时间」框：
     * 「这条用户请求之后创建、且由**自动提取**写入」的条目才删。
     * 只认 [source]（`auto_summary`）：手动写的记忆与这一轮无关，不能误伤 ——
     * 用户要求也正是「**没有自动保存就不动记忆**」。
     *
     * ## ⚠️ `:since > 0` 这条守卫是审查捞出来的（v0.61.12）
     * 调用方对"这一轮起点"的取值有个 `?: 0L` 兜底，而**加时间戳之前的老消息
     * `createdAt` 就是 0**（`ChatMessage.createdAt` 的注释里写着）——
     * 于是 `createdAt >= 0` 对任何条目都成立，会把**整个会话**的自动记忆一次清光。
     * 那不是"删这一轮"，是"删这个会话的记忆"。守卫放在 SQL 里（而不是只放在调用点），
     * 这样 fake DAO 与单测能一起把它钉住。
     *
     * @param since 被删那条用户请求的时间戳 —— "这一轮"的起点；**必须为正**
     * @return 删掉的条数
     */
    @Query(
        """
        DELETE FROM memories
        WHERE userId = :userId AND sessionId = :sessionId
          AND source = :source
          AND :since > 0 AND createdAt >= :since
        """,
    )
    suspend fun deleteAutoSince(
        userId: String,
        sessionId: String,
        source: String,
        since: Long,
    ): Int

    @Query("SELECT COUNT(*) FROM memories")
    suspend fun count(): Int

    /**
     * 某个角色的**全部**记忆（跨会话）—— 数据备份导出用。
     *
     * ⚠️ 它仍然带着 `userId` + `personaId` 两道强制过滤，只是**不带 `sessionId`**：
     * 导出的意图本来就是"全量"，而"全量"的边界依旧是**这个角色的**。
     * 真要做一条 `SELECT * FROM memories`（无过滤）才是错的 —— 那会让
     * 「所有查询强制带两层维度」这条纪律出现第一个破口，而一旦有破口，
     * 下一个调用者就会以为"原来可以不带"。
     */
    @Query("SELECT * FROM memories WHERE userId = :userId AND personaId = :personaId")
    suspend fun allOfPersona(userId: String, personaId: String): List<MemoryEntity>

    /**
     * 某个人设的全部**会话级**记忆（迁移「会话级 → 人设级」用）。
     *
     * ⚠️ 它与 [allOfPersona] 一样带 userId + personaId 两道强制过滤 ——
     * 迁移绝不能越出"这个角色的"边界。
     */
    @Query(
        """
        SELECT * FROM memories
        WHERE userId = :userId AND personaId = :personaId AND scope = 'session'
        """,
    )
    suspend fun allSessionOfPersona(userId: String, personaId: String): List<MemoryEntity>

    /**
     * 某个人设的**会话级**记忆条数（**排除当前会话**）—— 记忆页「迁移」入口的显示判据。
     *
     * ⚠️ 为什么排除当前会话（2026-10-06 修）：本会话新产生的会话级记忆在**本会话可见**
     *（见 [candidatesFor] 的 `sessionId = :sessionId`）——它没丢，不该被提示"迁移"。
     * 旧口径只按 `scope='session'` 数，于是每段新对话都把自己刚产生的记忆算成"旧记忆"，
     * 入口永不消失（用户报的"新开对话也提示旧会话"就是这个）。
     *
     * ⚠️ 为什么单独开一条 Flow 查询，而不是从 [observeVisible] 的列表里数：
     * 列表只含**本会话**的会话级条目。用户从一段新对话进来时看不到其它会话的旧记忆，
     * 从列表判定的入口会跟着消失 —— 而那正是最需要迁移入口的时刻。
     */
    @Query(
        """
        SELECT COUNT(*) FROM memories
        WHERE userId = :userId AND personaId = :personaId AND scope = 'session'
          AND sessionId <> :excludeSessionId
        """,
    )
    fun observeSessionCountOfPersona(
        userId: String,
        personaId: String,
        excludeSessionId: String,
    ): Flow<Int>

    /**
     * 清空记忆表。
     *
     * ⚠️ 与 `SessionDao.deleteAll` 同一条纪律：**只给「数据恢复」用**，
     * 且必须与写回的数据在**同一个事务**里，否则就是在删用户的东西。
     */
    @Query("DELETE FROM memories")
    suspend fun deleteAll()
}
