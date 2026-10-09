package ai.yuki.chuxue.data.room

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * 会话表读写。
 *
 * 用 `Flow` 暴露列表，让 UI 自动跟随数据库变化 —— 这样「流式结束后写入历史」
 * 会**自动**反映到列表与聊天页，不需要手工通知刷新。
 */
@Dao
interface SessionDao {

    /**
     * 会话列表：**置顶优先**，再按最近更新排序。
     *
     * ⚠️ 只改查询、不改表结构 —— 排序不进 schema，所以这次调整不涉及迁移。
     */
    @Query("SELECT * FROM sessions ORDER BY pinned DESC, updatedAt DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions ORDER BY updatedAt DESC")
    suspend fun all(): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): SessionEntity?

    @Upsert
    suspend fun upsert(session: SessionEntity)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun delete(id: String)

    /**
     * 删人设时连带删它的全部会话。
     * `messages` 表有 `onDelete = CASCADE` 外键，消息会跟着一起清掉 ——
     * 这正是当初选外键级联而不是手工两处删除的原因：漏一处就是脏数据。
     */
    @Query("DELETE FROM sessions WHERE personaId = :personaId")
    suspend fun deleteByPersona(personaId: String)

    /**
     * 这个人的全部会话 id（v0.51.0）。
     *
     * ⚠️ 存在的唯一理由：`provider_usage` 表**没有挂外键**（建表迁移刻意不动
     * 已有表，挂不上 CASCADE），所以删会话/删人设时得**先按 id 清它**。
     * 不这么做的话，被删掉的对话会在全局看板上留一行无主的分桶数据。
     */
    @Query("SELECT id FROM sessions WHERE personaId = :personaId")
    suspend fun idsOfPersona(personaId: String): List<String>

    @Query("SELECT COUNT(*) FROM sessions")
    suspend fun count(): Int

    /**
     * 清空会话表（消息靠外键 `CASCADE` 一起清掉）。
     *
     * ⚠️ **只给「数据恢复」用**：它把用户现有的对话整个抹掉。
     * 调用方必须做到两件事，缺一就是删库：
     * 1. **先落一份撤销点**（把当前数据导出成同一格式的 JSON）；
     * 2. **在同一个事务里紧接着写回导入的数据**（见 [SessionRepository.replaceAll]）——
     *    事务保证"清空成功但写入失败"会被整体回滚。
     */
    @Query("DELETE FROM sessions")
    suspend fun deleteAll()
}

/** 消息表读写。 */
@Dao
interface MessageDao {

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY seq ASC")
    suspend fun bySession(sessionId: String): List<MessageEntity>

    @Upsert
    suspend fun upsertAll(messages: List<MessageEntity>)

    @Query("SELECT COALESCE(MAX(seq), -1) FROM messages WHERE sessionId = :sessionId")
    suspend fun maxSeq(sessionId: String): Int

    /**
     * 清空消息表。
     *
     * ⚠️ 删 `sessions` 时外键 `CASCADE` 也会把消息带走 —— 这里**再显式删一次**
     * 是有意的：数据恢复那条路上不该依赖隐式行为，否则哪天有人动了外键定义，
     * 症状会是"导入之后旧消息还在"。**只给数据恢复用**，且必须与写回同事务。
     */
    @Query("DELETE FROM messages")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM messages")
    suspend fun count(): Int

    /**
     * 删掉这个会话里**不在 [keepIds] 中**的消息（v0.61.16）。
     *
     * ⚠️ 这条是「删除 / 重新生成不生效」的正主：`saveSession` 原来只有 `upsertAll` ——
     * 它只插入/更新，**从不删除**。于是被删掉的那条仍留在库里，Room 的 Flow 随即把
     * 完整数据推回界面 → 现象就是"删除后消息又回来了""重新生成后旧回复还在"，
     * 而模型那侧也照样能看到它（用户报的"AI 还能复述"）。
     *
     * ⚠️ 调用方**必须**先保证 [keepIds] 非空。实测（内存 sqlite3）：SQLite **容忍**
     * `NOT IN ()` 并把它当**空集合** —— 也就是会**删光**。那恰好是想要的语义，但它是
     * **非标准**行为（别的 SQL 方言直接报语法错），所以调用方仍走显式的 [deleteBySession]
     * 分支，不把正确性押在"SQLite 恰好容忍"上。
     */
    @Query("DELETE FROM messages WHERE sessionId = :sessionId AND id NOT IN (:keepIds)")
    suspend fun deleteNotIn(sessionId: String, keepIds: List<String>)

    /** 删掉一个会话的全部消息（消息被删空时用 —— 那条路上"没有要保留的 id"）。 */
    @Query("DELETE FROM messages WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)
}
