package ai.yuki.chuxue.data.room

import ai.yuki.chuxue.data.Session
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 会话仓储（开发文档 §13）。
 *
 * 把 Room 的实体读写包成领域对象（[Session]），让 ViewModel 不必知道表结构。
 *
 * ## 为什么整个会话一次性读写，而不是增量
 * 本项目对会话内容的纪律是 **「只追加、不改写」**（`ChatMessage` 的契约）。
 * 既然历史不可变，保存时整段覆写就是安全的 —— 而且比算 diff 简单得多、
 * 也更不容易出错。唯一要保证的是 [SessionMapper.messageId] 的确定性，
 * 否则整段覆写会插出重复行。
 */
class SessionRepository(private val db: AppDatabase) {

    private val sessionDao = db.sessionDao()
    private val messageDao = db.messageDao()
    private val providerUsageDao = db.providerUsageDao()

    /**
     * 写入串行化锁。
     *
     * `viewModelScope.launch { repo.saveSession(next) }` 这种写法会让**多次保存并发**执行，
     * 而 SQLite 只保证「每个事务原子」，不保证「提交顺序 = 调用顺序」。
     * 于是可能出现：后发起的保存先提交、先发起的后提交 —— DB 最终落回**旧版本**。
     * 加锁把提交顺序钉成调用顺序，配合 [AppDatabase] 的事务一起解决问题。
     */
    private val writeLock = Mutex()

    /* ─────────── 读 ─────────── */

    /** 观察全部会话（按最近更新排序），UI 直接 collect 即可跟随数据库变化。 */
    fun observeSessions(): Flow<List<Session>> =
        sessionDao.observeAll().map { entities ->
            entities.map { e -> SessionMapper.toDomain(e, messageDao.bySession(e.id)) }
        }

    suspend fun loadSessions(): List<Session> = withContext(Dispatchers.IO) {
        sessionDao.all().map { e -> SessionMapper.toDomain(e, messageDao.bySession(e.id)) }
    }

    suspend fun hasAnySession(): Boolean = withContext(Dispatchers.IO) {
        sessionDao.count() > 0
    }

    /* ─────────── 写 ─────────── */

    /**
     * 整体保存一个会话（含全部消息）。消息 id 确定性 → 幂等覆盖，不会重复插入。
     *
     * ## ⚠️ 这里必须是**一个事务**（曾因此「吞对话」）
     * 早先的写法是两次独立写入：
     * ```
     * sessionDao.upsert(session)       // ① 先写 sessions
     * messageDao.upsertAll(messages)   // ② 再写 messages
     * ```
     * 而 [observeSessions] 的 Flow **由 sessions 表驱动、却去读 messages 表**。
     * 于是这个竞态真实发生过：
     *
     * 1. 用户发消息 → ViewModel 乐观更新（消息立刻上屏）→ 异步保存
     * 2. 保存执行 ① → sessions 表变化 → Room 发出失效通知，Flow 重查
     * 3. 重查时 ② **还没执行** → 读到的 messages 仍是旧的 → 回推一份**不含新消息**的列表
     * 4. ViewModel 无条件用 Flow 的值覆盖本地状态 → **消息从界面上消失**
     * 5. 随后 ② 提交，但 sessions 表不再变化 → Flow 不再发射 → 界面**永久**停在错误状态
     *
     * 这正是用户报的「发出去的消息消失（但 AI 回复了）」，而且是**有概率**的 ——
     * 它取决于 ①② 之间是否被观察到。时序再差一点，错位后的 seq 还会与 DB 里
     * 那条消息的主键撞车（`会话id#序号`），把"界面丢"升级成"**真的丢**"。
     *
     * 修法只有一条：两次写入放进**同一个事务**。事务提交前 SQLite 不发失效通知，
     * Flow 只会在能看到**完整数据**之后才重查。
     *
     * [writeLock] 解决的是另一半：让**多次**保存的提交顺序等于调用顺序
     * （见该字段的注释）。
     */
    suspend fun saveSession(session: Session) = withContext(Dispatchers.IO) {
        writeLock.withLock {
            db.withTransaction {
                sessionDao.upsert(SessionMapper.sessionToEntity(session))
                val entities = SessionMapper.messagesToEntities(session.id, session.messages)
                // ⚠️ **先把"这次保存里不再存在的消息"删掉**（v0.61.16，本轮的关键修复）：
                //    原来只有 `upsertAll` —— 它只插入/更新，**从不删除**。于是"删掉一条消息"
                //    或"重新生成替掉旧回复"之后，被删的那条**仍留在库里**；Room 的 Flow
                //    随即把完整数据推回界面 —— 现象就是：
                //      · 删除后消息又回来了（用户："删除只会界面跳一下，消息还在"）
                //      · 重新生成后旧回复还在（用户："保留了原消息"）
                //      · 模型那侧也照样看得到它（用户："问 AI 他也能复述"）
                //    一个根因、三个现象。这里按**权威状态**收敛：库里最终只留 session.messages。
                if (entities.isEmpty()) {
                    // 消息被删空：没有"要保留的 id" → 显式全删
                    //（实测 SQLite 容忍 `NOT IN ()` 当空集合，但那是非标准行为，不押它）
                    messageDao.deleteBySession(session.id)
                } else {
                    messageDao.deleteNotIn(session.id, entities.map { it.id })
                    messageDao.upsertAll(entities)
                }
            }
        }
    }

    suspend fun deleteSession(id: String) = withContext(Dispatchers.IO) {
        // messages 有 onDelete = CASCADE，会随会话一起清掉
        sessionDao.delete(id)
        // ⚠️ provider_usage（v15）**没有外键**（建表迁移刻意不动已有表，
        //    所以也挂不上 CASCADE）—— 必须在这里显式清，否则删掉的对话会在
        //    全局看板上留下一行无主的分桶数据。
        providerUsageDao.clearSession(id)
    }

    /** 删人设时连带删它的全部会话（消息靠外键级联）。 */
    suspend fun deleteSessionsOfPersona(personaId: String) = withContext(Dispatchers.IO) {
        // 分桶数据没有 CASCADE，得先按会话 id 清掉（见 deleteSession 的注释）
        sessionDao.idsOfPersona(personaId).forEach { providerUsageDao.clearSession(it) }
        sessionDao.deleteByPersona(personaId)
    }

    /**
     * **用导入的数据整体替换**本机的会话与记忆（数据恢复专用）。
     *
     * ## ⚠️ 这是本项目唯一一个"删掉用户数据"的方法
     * 调用方必须已经做过两件事，缺一不可：
     * 1. **落一份撤销点** —— `ChatViewModel` 在导入前会把当前数据导出成**同一格式**的 JSON，
     *    所以用户之后还能把它导回来；
     * 2. 确认用户**明确要替换** —— 界面二次确认，文案说清"会替换"而不是"会合并"。
     *
     * ## 为什么"清空"与"写回"必须在同一个事务里
     * 拆成两步的话，「清空成功 → 写回失败」会让用户**只剩一个空库** ——
     * 那是他最不可能接受的结果。事务保证两者要么都发生、要么都不发生。
     *
     * 三张表都显式清（messages 虽然会被 `sessions` 的外键 `CASCADE` 带走，
     * 但数据恢复这条路不该依赖隐式行为 —— 理由见 `MessageDao.deleteAll`）。
     *
     * 写入复用 [saveSession] 的单条逻辑（`@Upsert` + 确定性主键 → 幂等），
     * 但**不再嵌套事务**：Room 的 `withTransaction` 是可重入的，不过这里直接内联更清楚。
     */
    suspend fun replaceAll(sessions: List<Session>, memories: List<MemoryEntity>) =
        withContext(Dispatchers.IO) {
            writeLock.withLock {
                db.withTransaction {
                    writeChatLocked(sessions)
                    writeMemoriesLocked(memories)
                }
            }
        }

    /**
     * **只**替换聊天记录（会话 / 消息 / 服务商分桶）—— 人设、设置、记忆库一律不动。
     *
     * v0.61.21 补：分项备份（`BackupScope.CHAT`）的导入走它。
     * 分项的意义本来就是**风险隔离** —— 只想挪聊天记录的人，不该被迫连带覆盖人设与设置。
     */
    suspend fun replaceChat(sessions: List<Session>) = withContext(Dispatchers.IO) {
        writeLock.withLock {
            db.withTransaction { writeChatLocked(sessions) }
        }
    }

    /**
     * **只**替换记忆库 —— 会话、人设、设置都不动（`BackupScope.MEMORY` 的导入走它）。
     *
     * ⚠️ 记忆是**整库换掉**，而不是按会话增量合并：记忆按会话归属，而分项导入不碰会话 ——
     *    增量合并会留下"这一轮本来就该消失、但因为不在新文件里而被保留"的旧条目，
     *    导入完的记忆库会同时含新旧两套。
     */
    suspend fun replaceMemories(memories: List<MemoryEntity>) = withContext(Dispatchers.IO) {
        writeLock.withLock {
            db.withTransaction { writeMemoriesLocked(memories) }
        }
    }

    /** 调用方必须已经在事务里（[replaceAll] / [replaceChat]）。 */
    private suspend fun writeChatLocked(sessions: List<Session>) {
        messageDao.deleteAll()
        sessionDao.deleteAll()
        // ⚠️ provider_usage 也在这个事务里清 —— 它是"本机会话的统计"，
        //    会话都被替换掉了，旧的分桶行留着就是无主数据
        //   （全局看板上会凭空多出几个已经不存在的服务商）。
        db.providerUsageDao().deleteAll()

        sessions.forEach { s ->
            sessionDao.upsert(SessionMapper.sessionToEntity(s))
            if (s.messages.isNotEmpty()) {
                messageDao.upsertAll(SessionMapper.messagesToEntities(s.id, s.messages))
            }
        }
    }

    /** 调用方必须已经在事务里。 */
    private suspend fun writeMemoriesLocked(memories: List<MemoryEntity>) {
        db.memoryDao().deleteAll()
        memories.forEach { db.memoryDao().upsert(it) }
    }

    /**
     * 首次从旧存储（DataStore）导入。
     *
     * **只在库里一条会话都没有时执行** —— 否则会把用户已经用了一段时间的
     * Room 数据覆盖回旧的快照。返回导入条数，0 表示跳过。
     */
    suspend fun importLegacyOnce(legacy: List<Session>): Int = withContext(Dispatchers.IO) {
        if (legacy.isEmpty()) return@withContext 0
        if (sessionDao.count() > 0) return@withContext 0
        legacy.forEach { saveSession(it) }
        legacy.size
    }
}
