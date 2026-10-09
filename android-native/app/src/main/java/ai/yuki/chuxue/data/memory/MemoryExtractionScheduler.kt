package ai.yuki.chuxue.data.memory

import android.content.Context
import android.util.Log
import ai.yuki.chuxue.data.ChatMessage
import ai.yuki.chuxue.data.DeepSeekClient
import ai.yuki.chuxue.data.ProviderGroups
import ai.yuki.chuxue.data.ServerConfig
import ai.yuki.chuxue.data.Store
import ai.yuki.chuxue.data.UserPersonas
import ai.yuki.chuxue.data.XinchaoMemoryApi
import ai.yuki.chuxue.data.room.AppDatabase
import ai.yuki.chuxue.data.room.MemoryEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 自动记忆提取的调度器（开发文档 §8.3）—— 让"她自己记下来"。
 *
 * ## 它每 [TICK_MS] 醒一次，然后问一串问题
 * ```
 * 用户开着自动记忆吗？ → 没开就继续睡
 * 填了 API Key 吗？    → 没填就继续睡
 * 前台正在流式吗？     → 在流就继续睡（绝不与用户的对话抢配额）
 * 距上次提取够 10 分钟吗？ → 不够就继续睡（成本护栏）
 * 有新消息吗？         → 没有就继续睡（避免重复花钱）
 * 消息够长吗（≥ 6 条）？→ 太短没有提取价值
 * ```
 * 全过了才真的发一次**非流式**请求，把结果写进记忆库。
 *
 * ## 为什么不用 WorkManager
 * 用户给的参考骨架用了 `@HiltWorker` + WorkManager。本项目**没有 Hilt、也没有
 * WorkManager 依赖**，而引入它们的理由并不充分：
 * - WorkManager 的价值是"进程死后仍调度"；而提取的语义是**聊完天顺便记一下** ——
 *   进程还活着就有机会做，进程死了下次打开 App 时补做也一样（节流状态是持久化的）。
 * - 为它多背一份依赖与构建成本（本机物理内存 2GB，构建时间是稀缺资源），不划算。
 *
 * 所以这里用**应用级协程**：[YukiApplication][ai.yuki.chuxue.YukiApplication] 起一个
 * 与进程同寿命的 scope 交给它。用户在后台放一会儿，提取就跑完了。
 *
 * ## 三条硬纪律
 * 1. **失败静默** —— 提取是附加服务。API 失败、余额不足、解析报错，全部吞掉记日志，
 *    **失败路径一个字都不弹**（见 [runOnce]）。但**成功**会通过 [onMemoriesSaved]
 *    回一句轻提示 —— 那是用户 2026-09-28 明确要的（"让她记住"这件事应当看得见），
 *    与"失败不打扰"并不冲突。
 * 2. **绝不与前台抢配额** —— 见 [shouldRun] 的 `frontendStreaming` 与
 *    [setFrontendStreaming]。
 * 3. **旁路写入** —— 提取结果只进 `memories` 表（经 [MemoryRepository]，内含去重合并），
 *    **不碰对话历史、不碰冻结前缀**。所以它既不会打断缓存，也不会让用户看到"历史被改了"。
 */
class MemoryExtractionScheduler(
    // 存成属性（v0.61.37）：自动上云要拿它构造 Store；原来只是构造参数、方法里够不着。
    private val appContext: Context,
    private val scope: CoroutineScope,
    /**
     * 真的写入记忆之后回调（参数：这一次记下的条数）。
     *
     * 「她自己记下了一件事」是这个 App 的核心体验之一，让它可见比悄悄发生更好。
     *
     * 默认空实现：测试与不关心提示的调用方不必传。
     */
    private val onMemoriesSaved: (Int) -> Unit = {},
    /**
     * 提取**失败**时的回调。
     *
     * ⚠️ 2026-09-28 用户追加要求：「像 API 报错、余额不足也提示」。
     * 这两类失败花的是**用户自己的额度**，静默掉会让「她怎么不记事」变成一个谜。
     *
     * 但**不能每次都弹**：提取每 60 秒醒一次，Key 若失效就会变成提醒轰炸 ——
     * 所以 [notifyFailure] 里按异常类型做了去重（[FAILURE_NOTICE_COOLDOWN_MS]）。
     * 至于「哪些失败值得打断用户」由回调方决定（网络抖动不值得）。
     */
    private val onExtractionFailed: (Throwable) -> Unit = {},
) {

    private val store = Store(appContext)
    private val db = AppDatabase.get(appContext)
    private val client = DeepSeekClient()
    private val memories = MemoryRepository(db)

    /** 启动周期心跳。重复调用是安全的（幂等，只影响是否会多一个协程）。 */
    fun start() {
        scope.launch {
            while (isActive) {
                delay(TICK_MS)
                runOnce()
            }
        }
    }

    /**
     * 跑一轮提取。**任何异常都在这里被吞掉** —— 这是"静默失败"的实现点。
     *
     * 为什么不往上抛：调用方是应用级 scope 里的一个循环，抛出去要么让循环死掉
     * （此后再也不提取），要么变成崩溃。两者都比"这次没提取成"更糟。
     */
    suspend fun runOnce() {
        // 置位给界面看（「Ta 正在做的事」清单）。⚠️ 用 finally 兜住 ——
        // 任何一条提前返回/异常路径漏掉复位，界面就会永远挂着一句"正在记下你说的事"。
        setExtracting(true)
        try {
            runCatching { attempt() }
                .onFailure { e ->
                    Log.d(TAG, "记忆提取本轮跳过：${e.javaClass.simpleName} ${e.message}")
                    notifyFailure(e)
                }
        } finally {
            setExtracting(false)
        }
    }

    /* ── 失败提示的去重 ── */

    private var lastFailureKind: String? = null
    private var lastFailureAt = 0L

    /**
     * 同一类失败在 [FAILURE_NOTICE_COOLDOWN_MS] 内只上报一次。
     *
     * 没有这一层，一个失效的 Key 会让用户每 10 分钟被弹一次同样的提醒 ——
     * 到那时用户的第一反应是卸载，而不是去设置里改 Key。
     */
    private fun notifyFailure(e: Throwable) {
        val kind = e.javaClass.simpleName
        val now = System.currentTimeMillis()
        if (kind == lastFailureKind && now - lastFailureAt < FAILURE_NOTICE_COOLDOWN_MS) return
        lastFailureKind = kind
        lastFailureAt = now
        onExtractionFailed(e)
    }

    /**
     * 取「这个人设当前绑定的用户人设正文」（v0.61.44）—— 未绑定 / 读失败一律空串。
     *
     * 与 `personaName` 的读取同一条纪律：宁可没有，绝不猜。
     * 判据走 [UserPersonas.resolve] —— 与冻结前缀 / 上下文估算**同一个口径**
     *（口径分裂就会出现"前缀注入了用户人设、提取却没被告知"的不一致）。
     */
    private fun userPersonaTextFor(personaId: String): String = runCatching {
        val persona = when (val loaded = store.loadPersonas()) {
            is Store.Loaded.Ok -> loaded.value.firstOrNull { it.id == personaId }
            else -> null
        }
        val users = when (val loaded = store.loadUserPersonas()) {
            is Store.Loaded.Ok -> loaded.value
            else -> emptyList()
        }
        persona?.let { UserPersonas.resolve(it, users)?.roleText }.orEmpty()
    }.getOrElse { "" }

    /**
     * 该人设**已经记过的事**（v0.61.54）—— 喂给提取器，让它别重复提取。
     *
     * 与 [userPersonaTextFor] 同一条读取纪律：**读失败给空表**（= 与加这个参数之前
     * 逐字节一致，不会因读失败而改变行为）。
     *
     * ⚠️ 按记忆方式取不同来源（彻底分轨）：云端人设走 `memory/list`（OB 桶的结构化视图）；
     *    本地人设读 Room。读不到就空表 —— 去重是增强，不该因为读失败而挡住提取。
     */
    private suspend fun existingMemoriesFor(personaId: String): List<String> = runCatching {
        val persona = when (val loaded = store.loadPersonas()) {
            is Store.Loaded.Ok -> loaded.value.firstOrNull { it.id == personaId }
            else -> null
        }
        if (persona?.isCloudMemory == true) {
            val base = ServerConfig.BASE_URL
            val token = store.loadAuth().token
            if (base.isBlank() || token.isBlank()) {
                emptyList()
            } else {
                XinchaoMemoryApi.fetchBuckets(base, token, personaId)
                    .orEmpty()
                    .sortedByDescending { it.createdAt.orEmpty() }
                    .take(MemoryExtraction.EXISTING_MEMORY_LIMIT)
                    .map { it.content }
            }
        } else {
            // 本地：读 Room 里该人设的记忆（按最近命中排序 —— 字段名是 lastAccessedAt，
            // 见 MemoryEntity 的注释：衰减按它算）
            db.memoryDao().allOfPersona(MemoryEntity.LOCAL_USER_ID, personaId)
                .sortedByDescending { it.lastAccessedAt }
                .take(MemoryExtraction.EXISTING_MEMORY_LIMIT)
                .map { it.content }
        }
    }.getOrElse { emptyList() }

    private suspend fun attempt() {
        val now = System.currentTimeMillis()

        // ⚠️ 显式取「最近更新」的那条，而不是 firstOrNull()：
        // 会话列表查询现在把**置顶**排在最前，用首个会挑到置顶会话而非刚聊过的那个，
        // 于是提取的永远是同一个会话。
        val session = db.sessionDao().all().maxByOrNull { it.updatedAt } ?: return

        // ⚠️ v0.51.0：用**这段会话实际会用**的设置（分组优先）—— 与发消息同一条取值路径
        //    （`ProviderGroups.effective`）。取全局那一份的话，密钥只存在分组里的用户
        //    会被判成 hasApiKey=false，记忆提取**静默停掉** ——
        //    用户看不到任何提示，只会觉得"她不记事了"。
        val settings = ProviderGroups.effective(
            settings = store.loadSettings(),
            groups = store.loadGroups(),
            activeId = store.activeGroupId(),
            sessionGroupId = session.providerGroupId,
            sessionModel = session.model,
        )

        val history = db.messageDao().bySession(session.id)

        val shouldRun = MemoryExtractionThrottle.shouldRun(
            enabled = settings.autoMemoryEnabled,
            hasApiKey = settings.apiKey.isNotBlank(),
            frontendStreaming = isFrontendStreaming,
            now = now,
            lastExtractAt = store.lastExtractAt(),
            latestSessionUpdatedAt = session.updatedAt,
            lastExtractedSessionAt = store.lastExtractedSessionAt(),
        )
        if (!shouldRun) return

        // 太短的对话没有可提取的东西，还会白花一次调用
        if (history.size < MIN_MESSAGES) return

        val raw = client.extractMemories(
            settings = settings,
            prompt = MemoryExtraction.buildPrompt(
                history.map { ChatMessage(it.role, it.content) },
                // ⚠️ v0.61.21：必须把**这个人设自己的名字**传进去。
                //    这里原来不传，而 buildPrompt 里写死了品牌名"初雪" ——
                //    于是提取出的记忆全是"初雪怎么怎么了"（用户原话：
                //    「记得都是初雪怎么怎么了 —— 初雪是我的品牌名啊」）。
                //    取不到（人设刚被删 / 存储还没写）就传空串，让 buildPrompt 回退成「Ta」——
                //    宁可叫"Ta"，也**绝不**再让它变回品牌名。
                personaName = runCatching {
                    // ⚠️ `loadPersonas()` 返回的是 `Loaded<...>` 包装（读失败是一种状态），
                    //    不是裸 List —— 读失败就当"取不到名字"，交给 buildPrompt 回退成「Ta」。
                    when (val loaded = store.loadPersonas()) {
                        is Store.Loaded.Ok -> loaded.value
                            .firstOrNull { it.id == session.personaId }
                            ?.displayName
                            .orEmpty()
                        else -> ""
                    }
                }.getOrElse { "" },
                // v0.61.44：告诉提取器「用户当前在扮演谁」—— 否则用户人设里的内容
                // 会被记混成"Ta 自己的事"。与 personaName 同一条读取纪律（失败给空串）。
                userPersonaText = userPersonaTextFor(session.personaId),
                // ⚠️ v0.61.54：把**已经记过的事**喂给提取器 —— 这是「云端记忆重复记」的根因治疗。
                //    字面去重分不开同义改写与近义不同事实（实测见 MemoryExtraction.buildPrompt），
                //    只有让模型看到"已经记过什么"才能从源头少产出重复。
                //    读失败给空表（= 与加这个参数之前逐字节一致，不会因读失败而改变行为）。
                existingMemories = existingMemoriesFor(session.personaId),
            ),
        )
        val extracted = MemoryExtraction.parse(raw)

        // ⚠️ 2026-10-06 重写（审查发现的真缺陷）：**先把本地全部写定、再 best-effort 上云**。
        //    原写法是"每条写完就地推"——上云是**挂起**网络调用（读超时 20s）且被 await 在写循环里；
        //    它一旦在网络之外的前段抛异常，就会中断 forEach：**后面的本地写被跳过**、
        //    而且下面的 markExtracted 也走不到 → 下一 tick 判定"会话有新内容"**整批重抽**
        //    （重复烧用户自己的 API 额度）。现在本地写与标记先落定，上云独立成一段。
        // ⚠️ v0.61.54：记忆**彻底分轨** —— 先判这个会话的人设是不是云端记忆。
        //    · 云端模式：提取结果**直接推 OB**（本地 Room 不写）—— 用户要求
        //      「云端记忆只有自动记忆到 OB 系统」；
        //    · 本地模式：只写本地 Room（不推云）。
        val persona = runCatching {
            when (val loaded = store.loadPersonas()) {
                is Store.Loaded.Ok -> loaded.value.firstOrNull { it.id == session.personaId }
                else -> null
            }
        }.getOrNull()
        val isCloud = persona?.isCloudMemory == true

        if (isCloud) {
            // ── 云端模式：只写 OB（本地 Room 不写）──
            // 用户要求「云端记忆只有自动记忆到 OB 系统」。失败静默（记忆上云是增强，
            // 不该打扰用户；下轮提取会再试）。
            val base = ServerConfig.BASE_URL
            val token = store.loadAuth().token
            if (base.isNotBlank() && token.isNotBlank()) {
                extracted.forEach { memory ->
                    runCatching {
                        XinchaoMemoryApi.write(
                            baseUrl = base,
                            token = token,
                            personaId = session.personaId,
                            content = memory.content,
                            category = MemoryExtraction.categoryToDisplay(memory.category),
                            importance = MemoryExtraction.importanceToProject(memory.importance),
                        )
                    }
                }
            }
        } else {
            // ── 本地模式：只写本地 Room（不推云）──
            extracted.forEach { memory ->
                // 走 MemoryRepository（内含同作用域去重）：重复提取出的同一件事会被合并，
                // 而不是让记忆库随时间无限膨胀。
                //
                // ⚠️ 2026-10-05：恢复透传 `memory.scope`（用户拍板：默认人设级、保留会话级分流）。
                // 提取器的分流判据见 MemoryExtraction.buildPrompt 的规则 1：
                // 「主语是『用户』→ persona；『我们 / 你和我』→ session」。
                // 沿革：2026-09-28 曾按要求全部落本会话；两次都是需求方拍板。
                memories.remember(
                    personaId = session.personaId,
                    sessionId = session.id,
                    content = memory.content,
                    category = MemoryExtraction.categoryToDisplay(memory.category),
                    importance = MemoryExtraction.importanceToProject(memory.importance),
                    source = SOURCE_AUTO,
                    scope = memory.scope,
                )
            }
        }

        // 标记放在最后：这一次真的跑完了（哪怕提取出 0 条，也算跑过 —— 否则会反复重试同一批）
        store.markExtracted(now)
        store.markExtractedSessionAt(session.updatedAt)

        if (extracted.isNotEmpty()) onMemoriesSaved(extracted.size)
        Log.d(TAG, "记忆提取完成：${extracted.size} 条（会话 ${session.id}）")
    }

    companion object {
        private const val TAG = "MemoryExtraction"

        /** 心跳间隔。60 秒足够及时，也不至于频繁唤醒。 */
        private const val TICK_MS = 60 * 1000L

        /** 少于这么多条消息的会话不提取 —— 没有足够上下文，只会产出噪声记忆。 */
        const val MIN_MESSAGES = 6

        /**
         * 提取写入的记忆来源标记（文档 §8.3 用同一个词）。
         *
         * ⚠️ v0.61.21：值收拢到 [MemoryEntity.SOURCE_AUTO] —— 这个来源标记不只是个标签，
         * 它决定了两件特权（可被自动合并覆盖正文、「删掉这一轮」时会被连带删除）。
         * 字面量散在两处，迟早有一处改了、另一处没跟上，而后果是**用户的记忆被误删**。
         */
        const val SOURCE_AUTO = MemoryEntity.SOURCE_AUTO

        /**
         * 同类失败提示的最小间隔 —— 见 [notifyFailure]。
         *
         * ⚠️ 2026-09-28 从 30 分钟缩到 **5 分钟**：用户反馈「等 10 分钟太久了，
         * 只要报错就提示原因」。提取自身的节流是 10 分钟（那是**省钱**护栏，不能动），
         * 所以把去重压到 5 分钟 = 实际效果是「每次失败都会告诉你」——
         * 既满足"即时"，又不会在同一分钟内连弹。
         */
        private const val FAILURE_NOTICE_COOLDOWN_MS = 5 * 60 * 1000L

        /**
         * 前台是否正在流式输出。
         *
         * ⚠️ 这是**并发护栏**，由 [ai.yuki.chuxue.ui.ChatViewModel] 在发送前后置位。
         * 用进程内静态变量而不是持久化：这个状态只在"此刻"有意义，
         * 进程重启后前台必然没在流式。
         */
        @Volatile
        private var frontendStreaming = false

        val isFrontendStreaming: Boolean get() = frontendStreaming

        fun setFrontendStreaming(value: Boolean) {
            frontendStreaming = value
        }

        /**
         * 现在是否正在跑一轮提取 —— **给界面看的**（「Ta 正在做的事」清单）。
         *
         * ⚠️ 与 [frontendStreaming] 同一条理由：它只在"此刻"有意义，
         *    进程重启后必然没在跑，所以用进程内静态、不持久化。
         * ⚠️ 用 `StateFlow` 而不是 `@Volatile var`：这个值要驱动**重组**，
         *    普通变量改了界面不会知道。
         */
        private val _extracting = MutableStateFlow(false)
        val extracting: StateFlow<Boolean> = _extracting.asStateFlow()

        internal fun setExtracting(value: Boolean) {
            _extracting.value = value
        }
    }
}
