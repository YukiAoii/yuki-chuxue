package ai.yuki.chuxue.data.room

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * 流式预写日志（开发文档 §9.5 / §13.9）。
 *
 * ## 它解决什么问题
 * 流式回复是**一段一段到的**。如果每块只进内存、等全部结束才落盘，
 * 那么进程在中途被杀（用户划掉、系统回收、崩溃）时，**用户等了半天的内容全没了**。
 *
 * WAL 的做法：**每收到一块就先写一行日志**。真的历史（messages 表）仍然只在
 * 流式结束后原子写入 —— 历史必须干净，日志可以脏。
 * 下次启动时，若发现某会话有「已写 WAL 但未进历史」的记录，就把它们拼起来恢复。
 *
 * ## 为什么日志可以脏、历史不能脏
 * 历史是**下一轮请求前缀**的一部分。一条半截的 assistant 消息进了历史，
 * 前缀就从那里断开，缓存从此不再命中（而用户完全不知道为什么费用涨了）。
 * 日志不进前缀，所以随便写。
 */
@Entity(
    tableName = "sse_wal",
    indices = [Index("sessionId")],
)
data class WalEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    /** 服务端的事件 id（`Last-Event-ID` 重连用），可能为空 */
    val eventId: String? = null,
    /** 第几轮对话，用于把同一轮的片段归到一起 */
    val turnIndex: Int,
    /** 这一块的正文增量 */
    val delta: String,
    /** 非空表示这一块带了结束标记 */
    val finishReason: String? = null,
    val receivedAt: Long,
    /** 是否已经写进 messages 表（恢复时只处理 false 的） */
    val persistedToHistory: Boolean = false,
)

/**
 * WAL 读写。
 *
 * ## 保留期
 * 日志只用于崩溃恢复，正常流式结束后就没用了。文档 §25.6 给的保留期是 **7 天** ——
 * 太短会漏掉「用户一周没打开 App 才恢复」的情况，太长则是白占空间。
 */
@Dao
interface WalDao {

    @Query("SELECT * FROM sse_wal WHERE sessionId = :sessionId AND persistedToHistory = 0 ORDER BY id ASC")
    suspend fun loadUnpersisted(sessionId: String): List<WalEntryEntity>

    /** 找出「有未落盘日志」的会话 —— 启动恢复时按这个清单逐个捞。 */
    @Query("SELECT DISTINCT sessionId FROM sse_wal WHERE persistedToHistory = 0")
    suspend fun sessionsWithUnpersisted(): List<String>

    /** 最近一条事件 id（断线重连时带上，服务端可续传）。 */
    @Query("SELECT eventId FROM sse_wal WHERE sessionId = :sessionId AND eventId IS NOT NULL ORDER BY id DESC LIMIT 1")
    suspend fun lastEventId(sessionId: String): String?

    @Query("SELECT COALESCE(MAX(turnIndex), -1) FROM sse_wal WHERE sessionId = :sessionId")
    suspend fun maxTurnIndex(sessionId: String): Int

    @Query("SELECT COUNT(*) FROM sse_wal")
    suspend fun count(): Int

    @androidx.room.Insert
    suspend fun insert(entry: WalEntryEntity): Long

    /** 一次回复顺利落进历史后，把这一轮的日志标记为已完成。 */
    @Query("UPDATE sse_wal SET persistedToHistory = 1 WHERE sessionId = :sessionId AND turnIndex = :turnIndex")
    suspend fun markPersisted(sessionId: String, turnIndex: Int)

    /** 清掉过期的日志（保留期见类注释）。 */
    @Query("DELETE FROM sse_wal WHERE persistedToHistory = 1 AND receivedAt < :before")
    suspend fun purgePersistedBefore(before: Long): Int
}
