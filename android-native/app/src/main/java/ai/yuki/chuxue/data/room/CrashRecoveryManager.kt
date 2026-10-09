package ai.yuki.chuxue.data.room

import ai.yuki.chuxue.data.ChatMessage

/**
 * WAL 片段的拼接规则（开发文档 §9.5）。
 *
 * 抽成纯对象是为了能单元测试 —— 崩溃恢复的**网络与进程行为**验证不了（需要真机），
 * 但「把一堆片段拼成一条完整回复」这个规则可以。
 */
object WalRecovery {

    /**
     * 把同一轮的多条 WAL 片段拼成完整正文。
     *
     * 顺序由 `id` 保证（DAO 里 `ORDER BY id ASC`）—— **不能按时间戳排**：
     * 同一毫秒内到达的两块，时间戳相同，顺序就随机了，而错一个字的顺序
     * 就是「她说的话被改写」。
     */
    fun joinDeltas(entries: List<WalEntryEntity>): String =
        entries.joinToString(separator = "") { it.delta }

    /** 这一轮是否收到了结束标记（有 finishReason 说明服务端正常收尾）。 */
    fun isComplete(entries: List<WalEntryEntity>): Boolean =
        entries.any { !it.finishReason.isNullOrBlank() }
}

/**
 * 崩溃恢复（开发文档 §9.5）。
 *
 * ## 恢复的是什么
 * 流式回复在结束时才写进历史（messages 表），但**每一块都先写 WAL**。
 * 进程若在中途被杀，历史里没有这条回复，WAL 里却有 —— 这里就是把它捞回来的地方。
 *
 * ## 为什么值得做
 * 对用户来说，「说了半句突然没了」比「压根没回」更糟：前者意味着**她的话被吞了**。
 * 伴侣 App 的核心是「她记得你」，而丢弃她说过的话，正好相反。
 */
class CrashRecoveryManager(
    private val db: AppDatabase,
    private val repo: SessionRepository,
) {

    private val walDao = db.walDao()

    /**
     * 启动时调用一次。返回被恢复的会话 id 列表（空表示没有需要恢复的东西）。
     */
    suspend fun recoverIncomplete(): List<String> {
        val sessionIds = walDao.sessionsWithUnpersisted()
        if (sessionIds.isEmpty()) return emptyList()

        val recovered = mutableListOf<String>()
        val allSessions = repo.loadSessions()

        sessionIds.forEach { sessionId ->
            val entries = walDao.loadUnpersisted(sessionId)
            if (entries.isEmpty()) return@forEach

            val text = WalRecovery.joinDeltas(entries)
            val turnIndexes = entries.map { it.turnIndex }.distinct()

            if (text.isNotBlank()) {
                val session = allSessions.firstOrNull { it.id == sessionId }
                if (session != null) {
                    // 把捞回来的片段作为一条 assistant 消息补进历史。
                    // 注意：这条消息一旦写入就与其它历史一样**永不修改** ——
                    // 它是「她当时确实说过的话」。
                    repo.saveSession(
                        session.copy(
                            messages = session.messages + ChatMessage(
                                role = "assistant",
                                content = text,
                                // 恢复时刻（原始时间戳没被写进 WAL，只能近似）。
                                // 它只影响聊天窗口那条分割条的位置，不影响任何缓存字节。
                                createdAt = System.currentTimeMillis(),
                            ),
                        ),
                    )
                    recovered += sessionId
                }
            }
            // 无论是否恢复成功，都要标记为已处理，否则每次启动都会重复捞
            turnIndexes.forEach { walDao.markPersisted(sessionId, it) }
        }
        return recovered
    }

    /** 清掉过期的已落盘日志（保留期 7 天，见 [WalDao]）。 */
    suspend fun purgeOld(nowMillis: Long): Int {
        val sevenDays = 7L * 24 * 60 * 60 * 1000
        return walDao.purgePersistedBefore(nowMillis - sevenDays)
    }
}
