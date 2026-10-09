package ai.yuki.chuxue.data.memory

import ai.yuki.chuxue.data.room.AppDatabase
import ai.yuki.chuxue.data.room.MemoryDao
import ai.yuki.chuxue.data.room.MemoryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * 记忆仓库 —— ViewModel 的唯一入口（开发文档 §7.5/§7.6/§7.7 的组装点）。
 *
 * 把 [MemoryWriter]（写）、[MemoryRetriever]（读）、[MemoryInjector]（转附录文本）
 * 三者收在一个门面上，让调用方（[ai.yuki.chuxue.ui.ChatViewModel]）不必知道检索细节 ——
 * 它只需要问一句「这一轮该带上哪些记忆」，得到的是可以直接交给
 * `PromptEngine.plan(memories = …)` 的字符串列表。
 *
 * ## 记忆**只走附录**（架构铁律，`架构红线文档`）
 * [appendixLines] 的返回值唯一的合法去处是 `PromptEngine.plan(memories = …)` ——
 * 它最终渲染进**用户消息体里的 `<appendix>` 块**（前缀末端）。
 * **绝不能**把它拼进冻结前缀或历史：那会让该会话的缓存从拼接点起全部失效，
 * 而且是延迟暴露（几轮之后才表现为命中率骤降），排查起来极痛。
 *
 * ## 为什么连 `userId` 都不在对外签名里
 * BYOK 单用户下它恒为 [MemoryEntity.LOCAL_USER_ID]。对外暴露只会让每个调用点
 * 都有机会传错 —— 隔离维度应当**在数据层固定**，而不是散给上层决定。
 */
class MemoryRepository(private val dao: MemoryDao) {

    /** 生产路径的便捷入口（测试用主构造直接给内存版 [MemoryDao]）。 */
    constructor(db: AppDatabase) : this(db.memoryDao())

    private val writer = MemoryWriter(dao)
    private val retriever = MemoryRetriever(dao)

    /**
     * 当前可见的记忆（记忆管理页 §42 的数据源）= 人设级全部 + 本会话的会话级。
     *
     * ⚠️ 2026-10-05：可见范围随「默认人设级」一起恢复（用户拍板）——此前
     * （2026-09-28 起）它只列本会话。每条都带 scope，界面可区分展示。
     */
    fun observe(personaId: String, sessionId: String): Flow<List<MemoryEntity>> =
        dao.observeVisible(MemoryEntity.LOCAL_USER_ID, personaId, sessionId)

    /**
     * 记住一件事 —— **默认属于这个人设**（跨会话共享）。
     *
     * ⚠️ 2026-10-05：`scope` 参数**恢复**（默认 [MemoryEntity.SCOPE_PERSONA]）。
     * 沿革：2026-09-28 用户曾要求「记忆只存在于当前对话」，当时把参数整个拿掉；
     * 现在用户拍板改回「默认人设级、保留会话级能力」——AI 提取时按主语分流
     * （"关于你的事"→ persona；"我们/你和我之间的事"→ session），手动添加默认 persona。
     * 需要会话级记忆的调用方（提取调度器等）**显式**传 `scope = SCOPE_SESSION`。
     *
     * `sessionId` 仍必填（调用方都在会话上下文里），但在 persona 级下不落库
     * （[MemoryWriter] 会忽略它）—— 这同时保证"删会话不会级联删掉人设记忆"。
     */
    suspend fun remember(
        personaId: String,
        sessionId: String,
        content: String,
        category: String = "其他",
        importance: Int = 5,
        source: String = "manual",
        scope: String = MemoryEntity.SCOPE_PERSONA,
    ): MemoryEntity = writer.write(
        personaId = personaId,
        scope = scope,
        content = content,
        category = category,
        importance = importance,
        source = source,
        sessionId = if (scope == MemoryEntity.SCOPE_SESSION) sessionId else null,
    )

    /**
     * 把这个人设的全部**会话级**记忆升级为人设级（用户可点的迁移，不是静默迁移）。
     *
     * 逐条经 [MemoryWriter] 写入（含同域去重合并），成功一条删一条原条 ——
     * 中途失败可重入（再点一次继续处理剩余的）。返回处理条数。
     */
    suspend fun migrateSessionToPersona(personaId: String, currentSessionId: String? = null): Int =
        writer.migrateSessionToPersona(personaId, excludeSessionId = currentSessionId)

    /**
     * 这个人设的会话级记忆条数（**排除当前会话**）——「迁移」入口的显示判据。
     *
     * ⚠️ 判据与动作**刻意不同源**：迁移动作 [migrateSessionToPersona] 仍处理**全部**会话级
     *（含当前会话的，一次到位）；而这里排除了当前会话 —— 本会话看得见的记忆没丢，不该提示。
     */
    fun sessionCount(personaId: String, currentSessionId: String): Flow<Int> =
        dao.observeSessionCountOfPersona(MemoryEntity.LOCAL_USER_ID, personaId, currentSessionId)


    /** 检索本轮应注入的记忆（已按「相关度 × 衰减权重」排好序，并刷新了命中时间）。 */
    suspend fun recall(
        personaId: String,
        sessionId: String,
        userInput: String,
        maxResults: Int = MemoryRetriever.DEFAULT_MAX_RESULTS,
    ): List<MemoryEntity> = retriever.retrieve(
        personaId = personaId,
        sessionId = sessionId,
        userInput = userInput,
        maxResults = maxResults,
    )

    /**
     * 本轮要注入附录的记忆文本行。
     *
     * 空列表 = 本轮不注入。**空列表是必须支持的一等情形**：
     * `PromptEngine.buildAppendix` 在 `memories` 为空时不产出 `<memories>` 块，
     * 于是请求体字节与「记忆库上线前」**逐字节相同** —— 存量会话的缓存不受任何影响。
     */
    suspend fun appendixLines(
        personaId: String,
        sessionId: String,
        userInput: String,
        maxResults: Int = MemoryRetriever.DEFAULT_MAX_RESULTS,
    ): List<String> {
        val hit = recall(personaId, sessionId, userInput, maxResults)
        return MemoryInjector.buildMemoryLines(hit)
    }

    /**
     * 就地更新一条记忆（改正文 / 分类 / 重要性）。
     *
     * ## 为什么不复用 [remember]
     * `remember` 会做**同作用域去重**，那对「用户明确在改这一条」是错的：
     * 他把「喜欢猫」改成「喜欢猫和狗」，不该被去重逻辑合并回原样，也不该新建一条。
     * 编辑的语义是**改这一行**，所以直接 upsert 同一个 id。
     *
     * 调用方用 `copy(...)` 构造新值即可，未传的字段（`createdAt`、`lastAccessedAt`、
     * `scope`、`sessionId`）自动保留 —— 尤其 **`lastAccessedAt` 不该被刷新**：
     * 编辑不是「想起」，不该重置衰减计时。
     */
    suspend fun update(memory: MemoryEntity) = withContext(Dispatchers.IO) { dao.upsert(memory) }

    suspend fun forget(id: String) = withContext(Dispatchers.IO) { dao.delete(id) }

    /** 删人设时连带清掉它的记忆 —— 人设不在 Room，没有外键可级联，必须显式调用。 */
    suspend fun forgetPersona(personaId: String) = withContext(Dispatchers.IO) {
        dao.deleteByPersona(MemoryEntity.LOCAL_USER_ID, personaId)
    }

    suspend fun count(): Int = withContext(Dispatchers.IO) { dao.count() }
}
