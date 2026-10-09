package ai.yuki.chuxue.ui.memory

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ai.yuki.chuxue.data.MemoryCloud
import ai.yuki.chuxue.data.MemoryUploadPlan
import ai.yuki.chuxue.data.ServerConfig
import ai.yuki.chuxue.data.Store
import ai.yuki.chuxue.data.XinchaoBucket
import ai.yuki.chuxue.data.XinchaoMemoryApi
import ai.yuki.chuxue.data.memory.MemoryRepository
import ai.yuki.chuxue.data.room.AppDatabase
import ai.yuki.chuxue.data.room.MemoryEntity
import ai.yuki.chuxue.data.room.afterManualEdit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 记忆管理页的状态与操作（开发文档 §42）。
 *
 * ## 为什么独立于 [ai.yuki.chuxue.ui.ChatViewModel]
 * `ChatViewModel` 已经在管会话、人设、设置、图片、流式、WAL 六件事，再加记忆列表
 * 只会让它的构造函数与 `init` 更重，而且记忆页**只在被打开时需要** —— 挂在聊天
 * ViewModel 上意味着每个会话都白建一条记忆流。职责分开后，这页的数据生命周期
 * 由它自己的 [viewModelScope] 说了算。
 *
 * ## personaId 走 [bind] 而不是构造参数
 * 导航到本页时才知道人设 id，而 ViewModel 由 `viewModel()` 创建、拿不到参数。
 * 用 [bind] 绑定后由 [flatMapLatest] 切换数据源 —— 顺带解决了「同页换人设」
 * （不重新创建 ViewModel 也能换到新的人设流）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MemoryViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = MemoryRepository(AppDatabase.get(app))

    /** 本页绑定的一对键：记忆以**人设**为主归属，`sessionId` 只用来**顺带**看本会话的会话级条目。 */
    private data class Binding(val personaId: String, val sessionId: String) {
        /**
         * ⚠️ 2026-10-06：判据由「personaId 且 sessionId 都非空」放宽为**只要 personaId**。
         * 原因：记忆 2026-10-05 起默认**人设级**（跨会话共享），从人设进来时没有"当前会话"
         * 这个概念（`sessionId` 为空串）。而 `MemoryDao.observeVisible` 的 WHERE 是
         * `scope='persona' OR sessionId=:sessionId` —— sessionId 为空串时不匹配任何会话级条目，
         * 正好等于「只看这个人设的人设级记忆」，正是我们要的。旧判据会让"从人设进"看到空页。
         */
        val isUsable: Boolean get() = personaId.isNotBlank()
    }

    private val binding = MutableStateFlow(Binding("", ""))

    /**
     * 当前**这段对话**的记忆。绑定不完整时给空表而不是抛错 ——
     * 页面可能在 `bind` 之前先组合一帧，不该让那一帧炸掉。
     *
     * `WhileSubscribed(5_000)`：页面退到后台 5 秒后停掉数据库订阅，
     * 免得不看这一页时还挂着一条 Room Flow。
     */
    val memories: StateFlow<List<MemoryEntity>> = binding
        .flatMapLatest { b ->
            if (!b.isUsable) flowOf(emptyList()) else repo.observe(b.personaId, b.sessionId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 绑定要管理的**人设**（`sessionId` 可选，空串=只看人设级、不看某段会话的会话级）。
     *
     * ⚠️ 沿革：2026-09-28 曾改成 `bind(personaId, sessionId)`（记忆属于会话）；
     * 2026-10-05 起记忆改回**人设级**，于是从人设进来时 `sessionId` 允许为空。
     */
    fun bind(personaId: String, sessionId: String = "", cloudEnabled: Boolean = false) {
        val next = Binding(personaId, sessionId)
        if (binding.value != next) {
            binding.value = next
            _cloudText.value = null // 换绑后云端块要重新拉
            _cloudBuckets.value = null
        }
        this.cloudEnabled = cloudEnabled
    }

    /** 当前绑定的人设 id（界面判断"能不能添加"用）。 */
    val currentPersonaId: String get() = binding.value.personaId

    /**
     * 新增一条记忆 —— 默认**属于这个人设**（跨会话共享）。
     *
     * ⚠️ 2026-10-05：恢复默认人设级（用户拍板）。沿革：2026-09-28 曾按要求改为
     * "只能加会话记忆"；两次都是需求方拍板。`sessionId` 仍从 [bind] 带进来，
     * 但人设级下不落库（[MemoryRepository.remember] 里处理）。
     */
    fun remember(draft: MemoryDraft) {
        if (!draft.isUsable) return
        val b = binding.value
        if (!b.isUsable) return
        viewModelScope.launch {
            // ⚠️ v0.61.54：记忆**彻底分轨** —— 云端模式的手记**不写本地 Room**，
            //    直接上云；本地模式才写 Room（云端不推）。由 [cloudEnabled] 决定。
            if (cloudEnabled) {
                val base = ServerConfig.BASE_URL
                val token = store.loadAuth().token
                if (base.isBlank() || token.isBlank()) return@launch
                val result = runCatching {
                    XinchaoMemoryApi.write(
                        baseUrl = base,
                        token = token,
                        personaId = b.personaId,
                        content = draft.normalizedContent,
                        title = null,
                        category = draft.category.ifBlank { null },
                        importance = draft.importance,
                    )
                }.getOrNull()
                if (result?.isFailure == true) {
                    _syncMessage.value = "没网记不上 —— 云端模式的手记直接存在云端，稍后再试一次"
                }
                return@launch
            }
            val saved = repo.remember(
                personaId = b.personaId,
                sessionId = b.sessionId,
                content = draft.normalizedContent,
                category = draft.category,
                importance = draft.importance,
                source = SOURCE_MANUAL,
            )
            // 本地模式：只写 Room。不再推云（v0.61.54 分轨）。
        }
    }

    /** 就地改一条记忆。`createdAt` / `lastAccessedAt` / `scope` 由 `copy` 原样保留。 */
    fun edit(existing: MemoryEntity, draft: MemoryDraft) {
        if (!draft.isUsable) return
        viewModelScope.launch {
            // ⚠️ v0.61.21：用 afterManualEdit 而不是裸 copy —— 它会**把来源改成 manual**。
            //    只改正文不改来源的话，一条被用户亲手改过的自动记忆在系统眼里仍是
            //    "后台记的"：会被后续的自动合并盖掉正文，也会被「删除最后一轮」连带删掉。
            repo.update(
                existing.afterManualEdit(
                    content = draft.normalizedContent,
                    category = draft.category,
                    importance = draft.importance,
                ),
            )
        }
    }

    /**
     * 删一条记忆：**本地 + 云端**（v0.61.38 之前只删本地 —— 云端会留下"Ta 还能想起、
     * 但你在 App 里已经看不到"的幽灵）。
     *
     * ⚠️ 顺序不能反：**先读那一行**（拿它的 `cloudBucketId`）再删本地 —— 反过来的话
     *    行已经没了、云端那条就永远对不上号了。
     * ⚠️ 用户显式删除，云端失败要**看得见**（走提示），不能像自动上云那样静默。
     */
    fun forget(id: String) {
        viewModelScope.launch {
            val row = runCatching { appDb.memoryDao().byId(id) }.getOrNull()
            repo.forget(id)
            if (row != null) forgetOnCloud(row)
        }
    }

    /** 云端那条的删除（按本地记下的桶 id）。没有 id（没上过云）就跳过。 */
    private suspend fun forgetOnCloud(row: MemoryEntity) {
        val bucketId = row.cloudBucketId ?: return
        val base = ServerConfig.BASE_URL
        val token = store.loadAuth().token
        if (base.isBlank() || token.isBlank()) return
        val result = runCatching {
            XinchaoMemoryApi.deleteMemory(base, token, row.personaId, bucketId)
        }.getOrNull()
        if (result?.isFailure == true) {
            _syncMessage.value = "本地已删掉；云端那条没删成功，稍后再试一次"
        }
    }

    /* ─────────── 一键迁移（2026-10-05：会话级旧记忆 → 人设级） ─────────── */

    /**
     * 这个人设还留着的**会话级**记忆条数（**不含本会话**）——
     * 界面据此决定「迁移」入口是否出现、显示几条。
     *
     * ⚠️ 为什么排除本会话（2026-10-06 修误报）：本会话新产生的会话级记忆在本会话里
     * 看得见、没丢，不该被叫成"旧记忆"。旧口径把它算进去，于是新开一段对话、聊出
     * 几条会话级记忆后，入口立刻又冒出来（用户报的"新开对话也提示旧会话"）。
     *
     * ⚠️ 判据不能用 [memories] 列表数：列表只含本会话的会话级条目，从一段新对话
     * 进来时看不到旧的，入口就消失了 —— 而那正是最需要迁移入口的时刻。
     */
    val legacySessionCount: StateFlow<Int> = binding
        .flatMapLatest { b ->
            if (b.personaId.isBlank()) flowOf(0) else repo.sessionCount(b.personaId, b.sessionId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** 迁移成功的一行提示（界面弹 Island 后调用 [clearMigrateMessage] 清掉）。 */
    private val _migrateMessage = MutableStateFlow<String?>(null)
    val migrateMessage: StateFlow<String?> = _migrateMessage.asStateFlow()

    /** 迁移失败的一行提示 —— 与成功分开两条流，界面才能按 ok / error 分色展示。 */
    private val _migrateError = MutableStateFlow<String?>(null)
    val migrateError: StateFlow<String?> = _migrateError.asStateFlow()

    /**
     * 把这个人设的**会话级**旧记忆升级为人设级 —— 用户可点，不是静默迁移。
     * 逐条经 [MemoryRepository.migrateSessionToPersona]（含去重合并），可重入。
     */
    fun migrateLegacyMemories() {
        val personaId = binding.value.personaId
        if (personaId.isBlank()) return
        viewModelScope.launch {
            val result = runCatching {
                // v0.61.50：动作与入口判据**同源** —— 都排除当前会话（否则显示 N 条、实际升 M 条）
                repo.migrateSessionToPersona(personaId, binding.value.sessionId)
            }
            result.fold(
                onSuccess = { n ->
                    _migrateMessage.value =
                        if (n > 0) "已把 $n 条记忆升为人设级 —— Ta 以后都会记得"
                        else "没有需要升级的记忆"
                },
                onFailure = { e ->
                    _migrateError.value = "升级失败：${e.message ?: "未知原因"}"
                },
            )
        }
    }

    fun clearMigrateMessage() {
        _migrateMessage.value = null
    }

    fun clearMigrateError() {
        _migrateError.value = null
    }

    companion object {
        const val SOURCE_MANUAL = "manual"
    }

    /* ─────────── 云端记忆（OB 记忆大脑，v0.61.36） ─────────── */

    private val appDb = AppDatabase.get(app)
    private val store = Store(app)

    /** 该人设是否已接入云端（由界面从 `Persona.xinchaoEnabled` 传进来）。 */
    private var cloudEnabled: Boolean = false

    /** 云端记忆文本：`null` = 还没拉过 / 拉不到。（**检索**路径用，见 [loadCloud]） */
    private val _cloudText = MutableStateFlow<String?>(null)
    val cloudText: StateFlow<String?> = _cloudText.asStateFlow()

    /** 结构化云端记忆（**无关键词**时的默认视图，按域分组用）。`null` = 没拉过 / 拉不到。 */
    private val _cloudBuckets = MutableStateFlow<List<XinchaoBucket>?>(null)
    val cloudBuckets: StateFlow<List<XinchaoBucket>?> = _cloudBuckets.asStateFlow()

    private val _cloudLoading = MutableStateFlow(false)
    val cloudLoading: StateFlow<Boolean> = _cloudLoading.asStateFlow()

    private val _cloudError = MutableStateFlow<String?>(null)
    val cloudError: StateFlow<String?> = _cloudError.asStateFlow()

    /** 「同步到云端」的一行结果（界面弹 Island 后清掉）。 */
    private val _syncMessage = MutableStateFlow<String?>(null)
    val syncMessage: StateFlow<String?> = _syncMessage.asStateFlow()

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    /**
     * 拉该人设的云端记忆。
     *
     * - **无关键词** → 拉**结构化列表**（[cloudBuckets]，按域分组渲染）；
     * - **有关键词** → 走 OB 语义检索（[cloudText]，返回文本）。
     * 未接入 / 拉不到 → 置空并给一句提示。
     */
    fun loadCloud(query: String = "") {
        val personaId = binding.value.personaId
        if (personaId.isBlank() || !cloudEnabled) {
            _cloudText.value = null
            _cloudBuckets.value = null
            return
        }
        viewModelScope.launch {
            _cloudLoading.value = true
            _cloudError.value = null
            val base = ServerConfig.BASE_URL
            val token = store.loadAuth().token
            val q = query.trim()
            if (q.isEmpty()) {
                val buckets = runCatching {
                    XinchaoMemoryApi.fetchBuckets(base, token, personaId)
                }.getOrNull()
                _cloudBuckets.value = buckets
                _cloudText.value = null
                _cloudLoading.value = false
                if (buckets == null) _cloudError.value = "暂时读不到云端记忆（没联网，或还没同步过）"
            } else {
                val text = runCatching {
                    XinchaoMemoryApi.fetchText(base, token, personaId, q)
                }.getOrNull()
                _cloudText.value = text
                _cloudBuckets.value = null
                _cloudLoading.value = false
                if (text == null) _cloudError.value = "暂时读不到云端记忆（没联网，或还没同步过）"
            }
        }
    }

    /** 同步进度（已处理 / 总数）；`null` = 不在同步中（界面据此显示 k/N）。 */
    private val _syncProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val syncProgress: StateFlow<Pair<Int, Int>?> = _syncProgress.asStateFlow()

    /**
     * 把这个人设的**本机记忆**送到云端（用户可点，非静默）。
     *
     * ## v0.61.48 的两处修正（都是子代理审查查出来的真问题）
     * 1. **只传人设级**：原来取 `allOfPersona`（不带 scope 过滤）→ **会话级记忆也被推上云**，
     *    与 [MemoryCloud.push] 只推人设级的口径相悖（跨会话泄漏）。过滤收在
     *    [MemoryUploadPlan.uploadableOf]（纯函数、有测试）。
     * 2. **分批并发**：原来逐条串行，每条走 `App → YukiServer → OB`（服务端过一次 LLM 压缩），
     *    客户端 readTimeout 20s → N 条最坏 N×20s。现在每批 3 条并发、批间串行，
     *    并把 **k/N 进度**吐给界面（原来只有一句静态文案，看起来像卡死）。
     */
    fun syncLocalToCloud() {
        val personaId = binding.value.personaId
        if (personaId.isBlank()) return
        if (!cloudEnabled) {
            _syncMessage.value = "这个人设不是云端记忆，不需要同步"
            return
        }
        if (_syncing.value) return
        // ⚠️ v0.61.50 修（审查发现的竞态）：守卫与置位**都要在 launch 之前**。
        //    原来 `_syncing.value = true` 在 launch 内、且在第一个挂起点（allOfPersona）之后 ——
        //    两次快速点击都能通过守卫，同一批记忆被重复推上云。
        _syncing.value = true
        viewModelScope.launch {
            try {
                val base = ServerConfig.BASE_URL
                val token = store.loadAuth().token
                if (base.isBlank() || token.isBlank()) {
                    _syncMessage.value = "未登录，无法同步"
                    return@launch
                }
                val rows = runCatching {
                    appDb.memoryDao().allOfPersona(MemoryEntity.LOCAL_USER_ID, personaId)
                }.getOrDefault(emptyList())
                // ⚠️ 只传**人设级** —— 会话级推到 persona 桶会跨会话泄漏
                val todo = MemoryUploadPlan.uploadableOf(rows)
                if (todo.isEmpty()) {
                    _syncMessage.value = "本机还没有可同步的记忆"
                    return@launch
                }
                var ok = 0
                var done = 0
                _syncProgress.value = 0 to todo.size
                for (batch in MemoryUploadPlan.batchesOf(todo)) {
                    val results = batch.map { m ->
                        async {
                            runCatching {
                                XinchaoMemoryApi.write(
                                    base, token, personaId, m.content,
                                    category = m.category, importance = m.importance,
                                )
                            }.getOrNull()?.isSuccess == true
                        }
                    }.awaitAll()
                    ok += results.count { it }
                    done += batch.size
                    _syncProgress.value = done to todo.size
                }
                // 部分失败要看得见（审查指出：10 条里失败 5 条时原来也报"已把 5 条送到云端"）
                _syncMessage.value = when {
                    ok == 0 -> "同步失败，稍后再试"
                    ok < todo.size -> "已把 $ok 条送到云端；${todo.size - ok} 条没成功，稍后再试"
                    else -> "已把 $ok 条记忆送到云端"
                }
                if (ok > 0) loadCloud()
            } finally {
                // ⚠️ 必须 finally：协程被取消时也要落地，否则 _syncing 卡在 true、按钮永久禁用
                _syncing.value = false
                _syncProgress.value = null
            }
        }
    }

    fun clearSyncMessage() {
        _syncMessage.value = null
    }

    /* ─────────── 云端记忆：删除某一条（v0.61.39，用户显式动作不许静默） ─────────── */

    /** 「云端记忆」页删除某一条的**成功**提示（界面弹 Island 后清掉）。 */
    private val _deleteMessage = MutableStateFlow<String?>(null)
    val deleteMessage: StateFlow<String?> = _deleteMessage.asStateFlow()

    /** 删除**失败**提示 —— 与成功分开两条流，界面才能按 ok / error 分色（删除不许静默）。 */
    private val _deleteError = MutableStateFlow<String?>(null)
    val deleteError: StateFlow<String?> = _deleteError.asStateFlow()

    /**
     * 从**云端**删掉某一条记忆（用户显式动作，按 OB 桶 id）。
     *
     * 服务端语义 = OB 官方归档（从日常召回里消失）+ 抹掉档案文件。所以文案如实写
     * 「不会再想起」，**不**承诺"彻底抹除一切痕迹"。成功后刷新云端列表；
     * 失败必须可见 —— 删除是显式动作，不能像自动上云那样静默。
     */
    fun deleteCloudBucket(bucketId: String) {
        val personaId = binding.value.personaId
        if (personaId.isBlank() || bucketId.isBlank()) return
        viewModelScope.launch {
            val base = ServerConfig.BASE_URL
            val token = store.loadAuth().token
            if (base.isBlank() || token.isBlank()) {
                _deleteError.value = "未登录，删不了云端的记忆"
                return@launch
            }
            val result = runCatching {
                XinchaoMemoryApi.deleteMemory(base, token, personaId, bucketId)
            }.getOrNull()
            if (result?.isSuccess == true) {
                _deleteMessage.value = "已从云端删掉这条记忆"
                loadCloud()
            } else {
                _deleteError.value = "云端删除失败，稍后再试一次"
            }
        }
    }

    fun clearDeleteMessage() {
        _deleteMessage.value = null
    }

    fun clearDeleteError() {
        _deleteError.value = null
    }
}
