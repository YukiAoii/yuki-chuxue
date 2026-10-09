package ai.yuki.chuxue.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.os.Build
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ai.yuki.chuxue.BuildConfig
import ai.yuki.chuxue.data.isNonStreamMode
import ai.yuki.chuxue.data.SEND_MODE_STREAM
import ai.yuki.chuxue.data.ActiveCall
import ai.yuki.chuxue.data.Activity
import ai.yuki.chuxue.data.AppFeatures
import ai.yuki.chuxue.data.AppFeaturesApi
import ai.yuki.chuxue.data.AppSettings
import ai.yuki.chuxue.data.FreeGroupApi
import ai.yuki.chuxue.data.Backup
import ai.yuki.chuxue.data.BackupDecode
import ai.yuki.chuxue.data.BackupSnapshot
import ai.yuki.chuxue.data.CacheStats
import ai.yuki.chuxue.data.EmojiAvailability
import ai.yuki.chuxue.data.EmojiCategories
import ai.yuki.chuxue.data.EmojiPicker
import ai.yuki.chuxue.data.ImageStore
import ai.yuki.chuxue.data.IoErrors
import ai.yuki.chuxue.data.ContextCompress
import ai.yuki.chuxue.data.Balance
import ai.yuki.chuxue.data.AuthApi
import ai.yuki.chuxue.data.AutoBackupPolicy
import ai.yuki.chuxue.data.AutoBackupSettings
import ai.yuki.chuxue.data.AutoBackupStore
import ai.yuki.chuxue.data.BackupScope
import ai.yuki.chuxue.data.BoardStats
import ai.yuki.chuxue.data.BoardSummary
import ai.yuki.chuxue.data.BoardMath
import ai.yuki.chuxue.data.DataOverview
import ai.yuki.chuxue.data.ExportFormat
import ai.yuki.chuxue.data.ExportState
import ai.yuki.chuxue.data.Exporter
import ai.yuki.chuxue.data.ChatErrors
import ai.yuki.chuxue.data.ChatMessage
import ai.yuki.chuxue.data.ChatStreamEvent
import ai.yuki.chuxue.data.StreamStall
import ai.yuki.chuxue.data.DeepSeekClient
// ⚠️ 上一轮我误删过这一行（当时想删的是 RequestLog）—— ImagePolicy 在本文件里
//    有 6 处使用（图片校验），删了会直接编译不过。它必须在这里。
import ai.yuki.chuxue.data.ImagePolicy
import ai.yuki.chuxue.data.MessageEdits
import ai.yuki.chuxue.data.memory.MemoryExtractionScheduler
// v0.61.54：云端记忆人设的附录走 OB —— 复用同一注入器/检索上限，保证附录形状不变。
import ai.yuki.chuxue.data.memory.MemoryInjector
import ai.yuki.chuxue.data.memory.MemoryRepository
import ai.yuki.chuxue.data.memory.MemoryRetriever
import ai.yuki.chuxue.data.Persona
import ai.yuki.chuxue.data.PersonaCodec
import ai.yuki.chuxue.data.PersonaCrypto
import ai.yuki.chuxue.data.PersonaSummary
import ai.yuki.chuxue.data.LocalPersonaOwner
import ai.yuki.chuxue.data.PersonaSync
import ai.yuki.chuxue.data.canPushLocalPersonas
import ai.yuki.chuxue.data.classifyLocalOwner
import ai.yuki.chuxue.data.PromptEngine
import ai.yuki.chuxue.data.ProviderGroup
import ai.yuki.chuxue.data.ProviderGroups
import ai.yuki.chuxue.data.ProviderProfile
import ai.yuki.chuxue.data.ProviderProfiles
import ai.yuki.chuxue.data.SendHistory
import ai.yuki.chuxue.data.Session
import ai.yuki.chuxue.data.SessionMerge
import ai.yuki.chuxue.data.ServerConfig
import ai.yuki.chuxue.data.TranscriptText
import ai.yuki.chuxue.data.UserPersona
import ai.yuki.chuxue.data.UserPersonas
import ai.yuki.chuxue.data.UserProfile
import ai.yuki.chuxue.data.Store
import ai.yuki.chuxue.data.Telemetry
import ai.yuki.chuxue.data.ProactiveFetchPolicy
import ai.yuki.chuxue.data.ProactiveFetchReceipt
import ai.yuki.chuxue.data.ProactiveFetcher
import ai.yuki.chuxue.data.XinchaoBreathApi
import ai.yuki.chuxue.data.XinchaoContextApi
import ai.yuki.chuxue.data.XinchaoDream
import ai.yuki.chuxue.data.XinchaoIntent
import ai.yuki.chuxue.data.XinchaoIntentApi
import ai.yuki.chuxue.data.XinchaoMemoryApi
import ai.yuki.chuxue.data.XinchaoPendingApi
import ai.yuki.chuxue.data.XinchaoReport
import ai.yuki.chuxue.data.XinchaoState
import ai.yuki.chuxue.data.XinchaoStateApi
import ai.yuki.chuxue.data.room.AppDatabase
import ai.yuki.chuxue.data.room.CrashRecoveryManager
import ai.yuki.chuxue.data.room.EmojiPackEntity
import ai.yuki.chuxue.data.room.MemoryEntity
import ai.yuki.chuxue.data.room.ProviderUsage
import ai.yuki.chuxue.data.room.ProviderUsageEntity
import ai.yuki.chuxue.data.room.SessionRepository
import ai.yuki.chuxue.data.net.ResilientSseConsumer
import ai.yuki.chuxue.data.room.WalEntryEntity
import ai.yuki.chuxue.service.ProactiveNotifier
import ai.yuki.chuxue.service.StreamingForegroundService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.random.Random
import java.util.Calendar
import java.util.UUID

/**
 * 一次**请求失败**的对话化表达（H4）。
 *
 * ⚠️ 它**不进 `messages`**（用户 2026-09-28 选定的方案 B）：错误不是她真的说过的话，
 * 而 `messages` 是下一轮请求的前缀 —— 写进去会让模型看到"自己刚说过余额不足"。
 * 所以它只活在界面层：退出会话就没了，`messages` 与缓存一个字没动。
 */
data class FailureLine(
    /** 每次显示换一个 id —— 同一条文案连着来两次，Compose 也要当成两个 item */
    val id: Long,
    /** 她说的话 */
    val spoken: String,
    /** 技术摘要（气泡里的小字） */
    val detail: String,
)

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val store = Store(app)
    private val client = DeepSeekClient()

    /**
     * 会话与消息的持久化（开发文档 §13）。
     *
     * 迁移分工：会话/消息搬到 **Room**，**设置与人设仍在 DataStore**。
     * 分两条路走是为了把这次迁移的爆炸半径压到最小 —— 万一出问题，
     * 受影响的只有会话，设置和人设那两条链路一行都没动。
     */
    private val db = AppDatabase.get(app)
    private val repo = SessionRepository(db)
    private val walDao = db.walDao()
    private val recovery = CrashRecoveryManager(db, repo)

    /**
     * 长期记忆（开发文档 §7/§8）。
     *
     * 记忆**只从附录注入** —— `appendixLines()` 的返回值交给
     * `PromptEngine.plan(memories = …)`，最终落在用户消息体的 `<appendix>` 块里，
     * 不碰冻结前缀、不碰历史。写回前缀会让该会话缓存从写入点全碎。
     */
    private val memoryRepo = MemoryRepository(db)

    /**
     * 带重连的流式消费（文档 §14.3）。
     * 只在「一个块都没收到」时重试 —— 理由见 [ResilientSseConsumer] 的类注释。
     */
    private val streamConsumer = ResilientSseConsumer(client)

    /* ─────────────── 设置 ─────────────── */

    private val _settings = MutableStateFlow(store.loadSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    fun saveSettings(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        store.saveSettings(next)
    }

    /* ─────────────── 连接分组（v0.51.0）─────────────── */

    /**
     * 全部连接分组。
     *
     * ⚠️ 首次读取会**从旧扁平配置迁移出一个「默认」分组**（见 `Store.loadGroups`）——
     * 所以这里不需要任何"要不要迁移"的判断，那是 Store 的职责。
     */
    private val _groups = MutableStateFlow(store.loadGroups())
    val groups: StateFlow<List<ProviderGroup>> = _groups.asStateFlow()

    /** 当前分组 id（空串 = 没设过，由 [activeGroup] 退回第一个）。 */
    private val _activeGroupId = MutableStateFlow(store.activeGroupId())
    val activeGroupId: StateFlow<String> = _activeGroupId.asStateFlow()

    fun saveGroups(next: List<ProviderGroup>) {
        _groups.value = next
        store.saveGroups(next)
        // 当前分组被删掉时把记住的 id 也清掉，免得它一直指向一个不存在的 id
        if (next.none { it.id == _activeGroupId.value }) {
            val fallback = next.firstOrNull()?.id.orEmpty()
            _activeGroupId.value = fallback
            store.setActiveGroupId(fallback)
        }
    }

    /* ─────────────── 服务端功能开关（v0.58.0）─────────────── */

    /**
     * 服务端下发的功能开关（目前只有"通用设定开关是否展示"）。
     *
     * ⚠️ 初值取 [AppFeatures.DEFAULT]（全开）而不是"全关"：拉取是异步的，
     *    初值若为 false，**每次冷启动的第一帧**都会看到开关消失又出现（闪一下）；
     *    取 true 的最坏情况只是"服务端已经关了、但这一帧还显示着"，用户注意不到。
     */
    private val _features = MutableStateFlow(AppFeatures.DEFAULT)
    val features: StateFlow<AppFeatures> = _features.asStateFlow()

    fun refreshFeatures() {
        viewModelScope.launch { _features.value = AppFeaturesApi.fetch() }
    }

    fun setActiveGroup(id: String) {
        _activeGroupId.value = id
        store.setActiveGroupId(id)
    }

    /**
     * 拉一次**服务端下发的免费分组**并并进本地列表（v0.58.0）。
     *
     * ## 三条刻意的选择
     * ① **失败静默**：它跑在启动路径上，与更新检查同一族语义 ——
     *    服务端挂了不该让用户的连接设置打不开。
     * ② **`null` 也要并一次**：服务端把这条关掉时，本地那条要跟着消失
     *    （见 [ProviderGroups.withManaged]），否则会留下一张发不出请求的死卡。
     * ③ **只在"一个自建分组都没有"时才把它设为当前**：用户自己配过就绝不改他的选择 ——
     *    否则升上来的老用户会被悄悄切到官方那条，账单和效果都变了却不知道为什么。
     */
    fun refreshFreeGroup() {
        viewModelScope.launch {
            // ⚠️ 三态（见 FreeGroupApi.State）：只有服务端**明确**说关掉时才摘掉本地那条。
            //    "没问到"什么都不做 —— 否则一次网络抖动/接口没部署，
            //    用户就会看到官方分组凭空消失（这正是他报的那个现象）。
            val state = FreeGroupApi.fetchState()
            val merged = ProviderGroups.withManaged(_groups.value, state)
            if (merged != _groups.value) {
                _groups.value = merged
                store.saveGroups(merged)
            }
            val onlyManaged = merged.size == 1 && merged.first().managed
            if (onlyManaged && _activeGroupId.value != ProviderGroups.MANAGED_ID) {
                setActiveGroup(ProviderGroups.MANAGED_ID)
            }

            // ── ③ 傻瓜式：当前分组"一个模型都没勾"时，自动拉模型列表、把**第一个**存下来 ──
            //
            // 用户 2026-10-02：「自动拉取模型选择模型列表的第一个进行保存……
            // 反正要求就是傻瓜式的不然用户有些不会弄」。
            //
            // 为什么只做"空的时候"：`checkedModels` 是用户挑过的，**非空就绝不覆盖** ——
            // 自动改用户挑好的模型，比让他自己配一次更糟。
            //
            // 为什么值得做：官方分组（`managed`）从服务端下发时本来就不带模型，
            // 新用户看到的是"有连接、但发不出话"，而他并不知道要先去勾一个模型。
            autoPickModelIfEmpty()
        }
    }

    /**
     * 当前分组没有可用模型时，自动拉一次模型列表并勾上第一个。
     *
     * ⚠️ 失败**静默**：它跑在启动路径上（与 [refreshFreeGroup] 同族语义），
     *    网络不通时不该弹任何东西 —— 用户自己进设置页还是能手动勾。
     */
    private suspend fun autoPickModelIfEmpty() {
        val groups = _groups.value
        val active = ProviderGroups.resolveFor(groups, _activeGroupId.value, null) ?: return
        if (active.checkedModels.isNotEmpty()) return
        if (active.baseUrl.isBlank() || active.apiKey.isBlank()) return

        val first = runCatching { DeepSeekClient().listModels(active.toSettings()) }
            .getOrNull()
            ?.firstOrNull()
            ?: return

        val next = groups.map {
            if (it.id == active.id) it.copy(checkedModels = listOf(first)) else it
        }
        _groups.value = next
        store.saveGroups(next)
    }

    /**
     * 某段会话**实际该用**的分组：会话自己选过的优先，否则用全局当前分组。
     *
     * ⚠️ 会话选的分组可能已被删除 —— [ProviderGroups.resolveFor] 会退回第一个
     * 而不是报错（否则那段对话会彻底发不出消息）。
     */
    fun groupForSession(session: Session?): ProviderGroup? =
        ProviderGroups.resolveFor(_groups.value, _activeGroupId.value, session?.providerGroupId)

    /** 某段会话实际该用的模型名：会话选过的优先，否则分组里勾选的第一个。 */
    fun modelForSession(session: Session?): String =
        ProviderGroups.resolveModel(groupForSession(session), session?.model)

    /**
     * 某段会话的**模型 API 上下文**（服务商允许的最大窗口）；`null` = 分组里没填。
     *
     * ⚠️ v0.53.0 起它**只**回答"这个模型最多能吃多少"；
     * "什么时候该压缩"看 [memoryWindowForSession] —— 两者过去是同一个值。
     */
    fun windowForSession(session: Session?): Int? = groupForSession(session)?.contextWindow

    /**
     * 某段会话的**模型记忆上下文**（压缩触发的分母）；`null` = 两处都没填。
     *
     * ⚠️ 回落链：`memoryContextWindow` → `contextWindow` → `null`（走"按模型名猜"的默认估算）。
     * 中间那一跳是**给老用户留的**：他们的分组里只有 `contextWindow`，
     * 于是升级之后**压缩时机一点不变**（与"通用设定"同一套兼容思路）。
     */
    fun memoryWindowForSession(session: Session?): Int? =
        groupForSession(session)?.effectiveMemoryWindow

    /**
     * 某段对话的**按服务商分桶**读数（v0.51.0，看板用）。
     *
     * ⚠️ 是 suspend 而不是 StateFlow：这是一次"进页面读一次"的只读查询，
     * 为它在 ViewModel 里常驻一个流没有收益（多一条要维护的生命周期）。
     */
    suspend fun providerUsageOf(sessionId: String): List<ProviderUsageEntity> =
        db.providerUsageDao().ofSession(sessionId)

    /** 全库按服务商汇总（全局看板用）。 */
    suspend fun providerUsageTotals(): List<ProviderUsageEntity> = db.providerUsageDao().totals()

    /**
     * 某段会话**实际会用的一套设置**（v0.51.0）。
     *
     * ## ⚠️ 它必须有且只有一个定义
     * 发消息 / 上下文压缩 / 记忆提取 / 余额查询**四处**问的是同一个问题。
     * 各写一份就会漂移成"聊天用 A 服务商、压缩用 B" —— 而这种半套状态
     * 只在用户的账单上表现出来。所以四处的取值全部走
     * [ProviderGroups.effective]，这里只是把三个 StateFlow 喂给它。
     *
     * ## ⚠️ 判据不能用 `settings.apiKey`
     * 分组界面启用之后，密钥落在**分组**里 —— 全局那个字段对新用户**恒为空串**。
     * 谁拿全局字段当"配没配"的判据，谁就会把"已经配好了"读成"没配"：
     * 发消息被拦、压缩不触发、记忆不提取、余额显示读不到。**四处都踩过这个坑。**
     */
    fun effectiveSettings(session: Session?): AppSettings =
        ProviderGroups.effective(
            settings = _settings.value,
            groups = _groups.value,
            activeId = _activeGroupId.value,
            sessionGroupId = session?.providerGroupId,
            sessionModel = session?.model,
        )

    /* ─────────────── 人设 ─────────────── */

    private val _personas = MutableStateFlow<List<Persona>>(emptyList())
    val personas: StateFlow<List<Persona>> = _personas.asStateFlow()

    fun personaById(id: String): Persona? = _personas.value.firstOrNull { it.id == id }

    /* ─────────────── 用户人设（v0.61.41，用户自己的角色） ─────────────── */

    private val _userPersonas = MutableStateFlow<List<UserPersona>>(emptyList())
    val userPersonas: StateFlow<List<UserPersona>> = _userPersonas.asStateFlow()

    /**
     * 某个角色当前绑定的用户人设 —— **唯一口径**（[UserPersonas.resolve]）。
     * 冻结前缀 / 上下文估算 / 记忆抽取三处都走它，避免"注入了但它没算"的不一致。
     */
    fun userPersonaFor(persona: Persona): UserPersona? =
        UserPersonas.resolve(persona, _userPersonas.value)

    fun userPersonaById(id: String?): UserPersona? =
        id?.takeIf { it.isNotBlank() }?.let { pid ->
            _userPersonas.value.firstOrNull { it.id == pid }
        }

    /**
     * 新建 / 保存一个用户人设（按 id）。`createdAt` 首次落定后不再变，`updatedAt` 每次刷新。
     *
     * ⚠️ 它**不推云同步**（v1 取舍）：用户人设本体本轮只做本地 + 备份。
     *    与它配套的绑定（`Persona.userPersonaId`）会随 personas 的既有云同步上去。
     */
    fun upsertUserPersona(u: UserPersona) {
        if (u.id.isBlank()) return
        val now = System.currentTimeMillis()
        val existing = _userPersonas.value.firstOrNull { it.id == u.id }
        val saved = if (existing == null) {
            u.copy(createdAt = if (u.createdAt > 0) u.createdAt else now, updatedAt = now)
        } else {
            u.copy(createdAt = existing.createdAt, updatedAt = now)
        }
        val updated = _userPersonas.value.filterNot { it.id == saved.id } + saved
        _userPersonas.value = updated
        store.saveUserPersonas(updated)
    }

    /**
     * 删掉一个用户人设，并把**所有引用它的角色解除绑定**。
     *
     * ⚠️ 解绑不能省：留着悬空 id 的话 [UserPersonas.resolve] 会静默给 null（行为没坏），
     *    但角色编辑页的绑定项会显示成一个不存在的名字 —— 看得见的脏数据。
     */
    fun deleteUserPersona(id: String) {
        if (id.isBlank()) return
        if (_userPersonas.value.none { it.id == id }) return
        _userPersonas.value = _userPersonas.value.filterNot { it.id == id }
        store.saveUserPersonas(_userPersonas.value)
        val bound = _personas.value.filter { it.userPersonaId == id }
        if (bound.isNotEmpty()) {
            val fixed = _personas.value.map { p ->
                if (p.userPersonaId == id) p.copy(userPersonaId = null) else p
            }
            _personas.value = fixed
            store.savePersonas(fixed)
            scheduleSyncPush()
        }
    }

    /* ── 人设同步（v0.52.0 · 端到端加密 · 方案 B）──
     *
     * 服务端只见**密文**：密钥由「用户密码 + uid」在客户端派生（`PersonaCrypto`），
     * **从不离开设备**。所以这不是云备份 —— 没有明文，拖库也拿不到任何人设。
     * ⚠️ 聊天记录与记忆**不在同步范围**（红线）：这里只搬 persona 这一份快照。
     *
     * 时机：启动时对一次；每次本地人设变更后推一次。全部**失败静默** ——
     * 同步是后台的锦上添花，不该因为一次网络失败在聊天页弹东西。
     */

    /**
     * 本次安装是否已经**确知云端人设快照的状态**（v0.61.21；fix2 扩为"拉取成功即置"）。
     *
     * 用途有二：
     * 1. 给"本地为空时敢不敢推"当判据 —— 见 [PersonaSync.canPush]；
     * 2. 让"本地有变更时先拉一次再推"每个会话只做一次（见 [scheduleSyncPush]）。
     *
     * ⚠️ 置位条件 = **拉取成功**（Download 应用 / Upload / None 都算）；**解密失败不算** ——
     *    云端内容没拿到，本地为空时依然不许推（失败方向保守）。
     * 刻意只放内存、不落盘：进程重启后它变回 false，下次拉取会重新确知。
     * 宁可多拉一次，也不要拿一个"上次是真的"的旧念头去覆盖云端。
     */
    private var personasPulled = false

    /**
     * 新用户引导弹窗该不该出现（v0.61.21）。
     *
     * 判据的**为什么**写在 [shouldShowNewUserPrompt] 的 KDoc 里（纯函数，有单测）——
     * 这里只负责把四个输入取齐。
     * ⚠️ 名字刻意与那个纯函数**不同**：同名的话成员会遮住顶层函数，调用变成递归。
     */
    fun newUserPromptVisible(): Boolean = shouldShowNewUserPrompt(
        loggedIn = store.loadAuth().isLoggedIn,
        personaCount = _personas.value.size,
        hasAnyApiKey = hasAnyApiKey(_settings.value.apiKey, _groups.value),
        seen = store.newUserPromptSeen(),
    )

    /** 关掉新用户引导弹窗 —— 点「创建」或「不用」都调它（两条路都是"弹过了"）。 */
    fun dismissNewUserPrompt() = store.markNewUserPromptSeen()

    /**
     * 只在本会话**还没成功拉过**时拉一次（v0.61.21）。
     *
     * ## 为什么不满足于"启动时拉一次"
     * 密钥是**登录那一刻**才从明文密码派生出来的（之后同设备只有 token），
     * 而启动时通常还没登录/没密钥 —— 那次 `syncPersonas()` 必然空跑。
     * 于是"换设备登录"这条路上，**一次拉取都没发生过**，本地一直是空的，
     * 随后任何一次本地变更都会把云端覆盖成近乎空。
     *
     * ⚠️ fix2（2026-10-04）：原来只挂在聊天页 —— 但重登后的落点是**主界面**，
     * 用户在主界面人设列表看到"不在"时可能一次都没进过聊天页（真机日志证实：
     * login 成功之后 0 次拉取）。现在**主界面与聊天页都挂**（MainActivity 的 MAIN
     * + ChatScreen），本函数幂等（personasPulled 一旦置真直接返回），
     * 多挂只增触发机会、不增网络请求。
     */
    fun syncPersonasIfNeeded() {
        if (personasPulled) return
        syncPersonas()
    }

    /**
     * 为还没有简介（或人设改过）的角色补一句 AI 简介（v0.61.21）。
     *
     * ## 为什么挂在启动/进聊天页
     * 用户原话：「用户启动软件的时候让 AI 生成一次就可以了」——**生成一次、长期缓存**；
     * 结果与输入指纹一起存进人设（本地 + 云端快照），所以这份钱只花一次。
     *
     * ## 三条约束，各自对着一种已知的翻车方式
     * 1. **每次启动最多补 2 个** —— 人设多的用户不该因为开一次 App 就被打一串请求；
     * 2. **失败静默** —— 它是锦上添花，不该在聊天页弹东西（同记忆提取的取舍）；
     * 3. **失败不写指纹** —— 保持"待生成"状态，下次再试。
     *    若失败也把指纹写进去，一次网络抖动就把这个角色**永久钉成"生成过了"**。
     */
    fun ensureDetailSummaries() {
        val s = effectiveSettings(activeSession())
        if (s.apiKey.isBlank() || s.baseUrl.isBlank()) return
        val left = MAX_SUMMARIES_PER_LAUNCH - summariesThisLaunch
        if (left <= 0) return
        viewModelScope.launch {
            val todo = _personas.value
                .filter { it.needsDetailSummary }
                .sortedByDescending { it.updatedAt }
                .take(left)
            for (p in todo) {
                val text = runCatching {
                    PersonaSummary.clean(client.describePersona(s, PersonaSummary.buildPrompt(p)))
                }.getOrNull() ?: continue // 失败 → 一个字都不写，保持"待生成"
                summariesThisLaunch++
                val updated = _personas.value.map {
                    if (it.id == p.id) {
                        p.copy(detailSummary = text, detailSummaryKey = p.detailSummaryFingerprint)
                    } else {
                        it
                    }
                }
                _personas.value = updated
                store.savePersonas(updated)
                scheduleSyncPush()
            }
        }
    }

    /** 每次启动最多补几个简介 —— 见 [ensureDetailSummaries] 的第 1 条约束。 */
    private val MAX_SUMMARIES_PER_LAUNCH = 2

    /** 本次进程已经补过几个（不落盘：重启重新计）。 */
    private var summariesThisLaunch = 0

    /** 启动时 / 登录后 / 进主界面 / 进聊天页调用一次：拉远端 → 谁新听谁的。 */
    fun syncPersonas() {
        val auth = store.loadAuth()
        if (!auth.isLoggedIn) return
        val key = store.loadPersonaKey() ?: return

        // ⚠️ v0.61.24.3「换账号不串号」第一道闸：先问**本地库是谁的**。
        //    · 别的账号 → **隔离**：清空本地（内存 + 磁盘）、不拉不推，并把库改绑当前账号。
        //      敢清的理由：它既然是"别的账号的"，那份数据在**原账号云端**有备份
        //      （只有同步过才会带上归属），切回去还能拉回来；反之不清，
        //      它会被当前账号的密钥推上当前账号云端 —— 那是**不可逆**的污染。
        //    · 从没绑定过（本机用户还没登录过）→ 绑定当前账号，再走正常同步。
        when (classifyLocalOwner(store.loadPersonaOwner(), auth.uid)) {
            LocalPersonaOwner.OTHER_ACCOUNT -> {
                _personas.value = emptyList()
                store.savePersonas(emptyList())
                store.savePersonasRev(0L)
                store.markPersonaOwner(auth.uid)
                personasPulled = false // 云端状态未知 → canPush 兜底：不许推
                return
            }
            LocalPersonaOwner.NO_OWNER -> store.markPersonaOwner(auth.uid)
            LocalPersonaOwner.SAME_ACCOUNT -> Unit
        }

        viewModelScope.launch {
            if (pullAndApplyPersonas(auth.token, key) == PullOutcome.NeedPush) {
                runCatching { pushPersonas(auth.token, key) }
            }
        }
    }

    /** 拉取+应用的结果（供调用方决定"要不要接着推"）。 */
    private enum class PullOutcome {
        /** 没拉成（网络失败 / 解密失败）—— 保守：什么都别推（见 [scheduleSyncPush]）。 */
        Failed,

        /** 已拉到并处理完（下载应用 / 确认无变化）—— 不需要推送。 */
        NoPush,

        /** 本地比云端新，或做了首次合并 —— 需要把本地推上去。 */
        NeedPush,
    }

    /**
     * 拉取 + 决策 + 应用（v0.61.21 · fix2）。**不推送** —— 推不推由调用方看返回值定。
     *
     * ## 与 fix1 的三处不同
     * 1. **首次同步合并**：[PersonaSync.shouldMergeFirstSync] 成立时，应用的是
     *    "云端 ∪ 本地新建"，并返回 [PullOutcome.NeedPush] 让调用方把并集推回去。
     *    这挡住的是"清数据重登后新建人设 → 把云端旧快照覆盖成近乎空"的丢数据路径。
     * 2. **状态标记扩展**：原来只有"成功下载并应用"才置 [personasPulled]；
     *    现在 **Upload / None 也置**（拉取成功 = 云端状态已知）。
     *    **解密失败不置** —— 云端内容没拿到，本地为空时仍然不许推（保守）。
     * 3. 同一条链路上把 [Store.markPersonaSyncUid] 记下来（给合并判据用）。
     */
    private suspend fun pullAndApplyPersonas(token: String, key: ByteArray): PullOutcome {
        val r = AuthApi.fetchPersona(token)
        // 拉不到就等着 —— 下一次本地变更 / 进主界面会再走一遍
        if (r !is AuthApi.Outcome.Ok) return PullOutcome.Failed
        val localCount = _personas.value.size
        val localRev = maxOf(store.loadPersonasRev(), PersonaSync.localRev(_personas.value))
        // v0.61.21 · fix2：本设备从未同步过 + 双边非空 → 合并（两边都保住）。
        if (PersonaSync.shouldMergeFirstSync(store.loadPersonaSyncUid(), r.value.rev, localCount)) {
            val json = r.value.blob?.let { PersonaCrypto.decrypt(key, it) } ?: return PullOutcome.Failed
            val cloud = runCatching { PersonaCodec.decode(json) }.getOrNull() ?: return PullOutcome.Failed
            if (!PersonaSync.shouldApplyRemote(cloud)) return PullOutcome.Failed
            val merged = PersonaSync.mergeFirstSync(
                cloud = applySnapshotAvatars(cloud, json),
                local = _personas.value,
            )
            _personas.value = merged
            store.savePersonas(merged)
            markSyncedNow()
            // 把并集推回去 —— 不然云端还是"旧的那份 + 看不到新建的"。
            return PullOutcome.NeedPush
        }
        return when (PersonaSync.decide(localRev, r.value.rev)) {
            PersonaSync.Action.Download -> {
                val json = r.value.blob?.let { PersonaCrypto.decrypt(key, it) } ?: return PullOutcome.Failed
                val remote = runCatching { PersonaCodec.decode(json) }.getOrNull()
                // ⚠️ v0.61.21：这里原来写的是 `if (!remote.isNullOrEmpty())` ——
                //    那等于把"解码失败"和"服务端就是一份合法的空快照"当成一回事。
                //    后果：用户把人设**删光**之后，这个删除永远同步不到别的设备，
                //    而且 rev 不推进、每次启动重复同一判断（永不收敛、也不报错）。
                //    判据改由 PersonaSync.shouldApplyRemote 出（有单测）。
                if (!PersonaSync.shouldApplyRemote(remote)) return PullOutcome.Failed
                val applied = applySnapshotAvatars(remote!!, json) // shouldApplyRemote 已保证非空
                _personas.value = applied
                store.savePersonas(applied)
                store.savePersonasRev(r.value.rev)
                markSyncedNow()
                PullOutcome.NoPush
            }
            PersonaSync.Action.Upload -> {
                markSyncedNow()
                PullOutcome.NeedPush
            }
            PersonaSync.Action.None -> {
                markSyncedNow()
                PullOutcome.NoPush
            }
        }
    }

    /** 拉取成功、云端状态已确知 —— 记录 [personasPulled] 与持久化的账号标记。 */
    private fun markSyncedNow() {
        personasPulled = true
        val uid = store.loadAuth().uid
        if (uid.isNotBlank()) store.markPersonaSyncUid(uid)
    }

    /**
     * 快照里内嵌的头像落回本地，并把 avatarPath 换成**本地那个路径**。
     *
     * ⚠️ v0.61.21：快照里原本那份是**本机路径**，换设备后指向不存在的文件
     *    （"人设回来了、头像没了"就是它）。落盘按**内容命名** ⇒ 同一张图任何设备、
     *    任何一次同步后都是同一个路径，头像组件不会重新解码（不闪）。
     */
    private suspend fun applySnapshotAvatars(incoming: List<Persona>, json: String): List<Persona> =
        withContext(Dispatchers.IO) {
            val embedded = PersonaCodec.decodeAvatars(json)
            incoming.map { p ->
                val bytes = embedded[p.id]
                    ?.let { d -> runCatching { Base64.decode(d, Base64.DEFAULT) }.getOrNull() }
                val path = if (bytes != null) {
                    ImageStore.writeContentAddressed(getApplication(), bytes)
                } else {
                    // 快照没带这张图（超预算 / 老快照）——
                    // **本地那份还在就留着**；只有指向不存在的文件时才清掉
                    //（那正是换设备后残留的本机路径）。
                    p.avatarPath?.takeIf { File(it).exists() }
                }
                p.copy(avatarPath = path)
            }
        }

    /** 把当前人设快照加密上传。 */
    private suspend fun pushPersonas(token: String, key: ByteArray) {
        // ⚠️ v0.61.21：**没成功拉过一次之前，本地为空就不许推**。
        //    换设备/清数据刚登录时本地是空的、云端有旧快照 —— 此时任何一次本地变更
        //    （哪怕只是新建一个人设）都会把云端那份**覆盖成近乎空**：
        //    用户报的「换设备登录后人设消失」就是它（是**真的没了**，不是看不到）。
        //    判据在 PersonaSync.canPush（有单测）。
        if (!PersonaSync.canPush(_personas.value.size, personasPulled)) return

        // ⚠️ v0.61.24.3「换账号不串号」第二道闸：**隔离态一律不许推**。
        //    第一道闸在 syncPersonas（会把隔离的库清空并改绑当前账号），这里是兜底 ——
        //    防任何路径（例如本地变更触发的 scheduleSyncPush）绕过它直接把本地推出去。
        if (!canPushLocalPersonas(store.loadPersonaOwner(), store.loadAuth().uid)) return

        // ⚠️ v0.61.21：把头像也压进去（见 PersonaCodec.encode 的注释）。
        //    读盘 + 缩放 + 压缩是重活，挪到 IO；体量由 PersonaSync.fitAvatars 封顶 ——
        //    超预算是**少带几个头像**，而不是让整份快照顶爆后端上限、全部传不上去。
        val appCtx = getApplication<Application>()
        val avatars = withContext(Dispatchers.IO) {
            PersonaSync.fitAvatars(
                _personas.value.mapNotNull { p ->
                    val path = p.avatarPath ?: return@mapNotNull null
                    val bytes = ImageStore.compressedJpegBytes(appCtx, path) ?: return@mapNotNull null
                    p.id to Base64.encodeToString(bytes, Base64.NO_WRAP)
                },
            )
        }
        val blob = PersonaCrypto.encrypt(key, PersonaCodec.encode(_personas.value, avatars)) ?: return
        val rev = maxOf(store.loadPersonasRev(), System.currentTimeMillis())
        when (AuthApi.uploadPersona(token, blob, rev)) {
            is AuthApi.Outcome.Ok -> store.savePersonasRev(rev)
            is AuthApi.Outcome.Fail -> Unit
        }
    }

    /** 本地人设刚变过 → 后台推一次（失败静默）。 */
    private fun scheduleSyncPush() {
        val auth = store.loadAuth()
        if (!auth.isLoggedIn) return
        val key = store.loadPersonaKey() ?: return
        viewModelScope.launch {
            // v0.61.21 · fix2：**没确知云端状态之前，先拉一次** ——
            // 拉不到就不推（宁可这次不同步，也不盲推；下次变更再来对账）。
            // 挡的是：新设备刚登录、拉取还没发生/失败时，一次本地变更把云端旧快照覆盖。
            // （pullAndApplyPersonas 内部已含"首次合并"——双边非空时推的是并集。）
            if (!personasPulled) {
                val r = runCatching { pullAndApplyPersonas(auth.token, key) }.getOrNull()
                if (r == null || r == PullOutcome.Failed) return@launch
            }
            runCatching { pushPersonas(auth.token, key) }
        }
    }

    /**
     * 把本地头像文件**异步**传到服务端（服务器只做兜底：本地那张永远优先）。
     *
     * ⚠️ 失败静默：头像已在本地落盘、也已显示，上传只是"换个手机还能看到"的保险。
     * ⚠️ 后端的上传接口顺手把 URL 记进账号资料，所以这里只要上传成功就双写完成。
     */
    fun uploadAvatar(localPath: String?, notifyOnFailure: Boolean = false) {
        val auth = store.loadAuth()
        val path = localPath
        if (!auth.isLoggedIn || path.isNullOrBlank()) return
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching { File(path).readBytes() }.getOrNull()
            } ?: return@launch
            val name = path.substringAfterLast('/').ifBlank { "avatar.jpg" }
            val mime = when (name.substringAfterLast('.', "").lowercase()) {
                "png" -> "image/png"
                "webp" -> "image/webp"
                "gif" -> "image/gif"
                else -> "image/jpeg"
            }
            when (val r = AuthApi.uploadAvatar(auth.token, bytes, name, mime)) {
                // 服务器已把 URL 记进账号资料；本地也存一份，供"本地没有那张图"时兜底
                is AuthApi.Outcome.Ok -> {
                    store.saveAuth(auth.copy(avatarUrl = r.value))
                    // 刚传上去的就是"服务器当前的那张"：记下来源 URL，
                    // 免得下次启动又把它当"服务器换了头像"拉一遍（白耗流量）
                    saveProfile { it.copy(avatarSourceUrl = r.value) }
                }
                // ⚠️ 用户**显式**挑头像那一次必须说话（notifyOnFailure）——
                //    静默失败正是"市场里看不到作者头像"这件事藏了这么久的根因。
                //    启动时的后台补齐不打扰用户（本地那张仍然优先，功能不受影响）。
                is AuthApi.Outcome.Fail ->
                    if (notifyOnFailure) _error.value = "头像没能同步到服务器：${r.message}（本地不受影响）"
            }
        }
    }

    /**
     * 服务器头像的**兜底**：本地没有头像文件、而账号资料里有服务器 URL 时，把它取回本地。
     *
     * ⚠️ 本地有图就**绝不覆盖** —— 用户自己传的那张永远优先（用户 2026-09-30：
     * 「优先显示用户本地自己传的，服务器只做兜底」）。
     * ⚠️ 取回后仍存成**本地文件**：显示层（`UserAvatar`）只吃 path，不必认 URL。
     */
    fun fetchAvatarIfMissing() {
        val auth = store.loadAuth()
        val profile = store.loadProfile()
        val url = auth.avatarUrl
        if (!shouldPullAvatar(auth.isLoggedIn, url, profile.avatarPath, profile.avatarSourceUrl)) return
        val target: String = url ?: return
        viewModelScope.launch {
            val bytes = AuthApi.downloadImage(ServerConfig.url(target)) ?: return@launch
            val path = withContext(Dispatchers.IO) {
                val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bmp == null) {
                    null
                } else {
                    // ⚠️ 文件名**跟着 URL 走**，不用固定名。
                    //    固定名 + 内存缓存（cached 只认路径）= 覆盖后缓存里还是旧图，
                    //    用户会看到"拉了新头像但还是旧脸"。
                    val name = "remote_" + target.hashCode().toUInt().toString(16)
                    ImageStore.saveBitmap(getApplication(), bmp, ImageStore.AVATAR_DIR, name, 512)
                }
            }
            if (!path.isNullOrBlank()) {
                val old = store.loadProfile().avatarPath
                // 旧的那张如果是**拉下来的**（不是用户自己选的），顺手清掉，别攒孤儿文件
                if (old != null && old != path && "remote_" in old) {
                    withContext(Dispatchers.IO) { ImageStore.delete(old) }
                }
                // 记下来源 URL：下次只有服务器换了头像才会重拉
                saveProfile { it.copy(avatarPath = path, avatarSourceUrl = target) }
            }
        }
    }


    /**
     * 本地有头像、而**服务器上没有**时补传一次（v0.58.x · 必做 4 的客户端根因）。
     *
     * ## 为什么必须有
     * 服务器上的头像**只在"挑头像那一刻、且已登录"才写一次**（见 [uploadAvatar]）。
     * 于是两类用户在服务器上永远是空的：
     * · **先挑头像、后登录**的 —— 那一刻 `isLoggedIn == false`，函数直接 return 了；
     * · 挑的那一刻上传失败的 —— 旧代码把失败**静默吞成 `Unit`**。
     * 而本地显示只看本地文件（[UserAvatar] 吃的是本地路径），用户**完全看不出**
     * 服务器那头没存上 —— 直到在市场上发帖，别人看到的只是一个首字兜底。
     * 这里在启动时补一次，把历史遗留补回来。
     */
    fun pushAvatarIfMissing() {
        val auth = store.loadAuth()
        val local = store.loadProfile().avatarPath
        if (!shouldPushAvatar(auth.isLoggedIn, auth.avatarUrl, local)) return
        uploadAvatar(local)
    }

    fun upsertPersona(p: Persona) {
        val now = System.currentTimeMillis()
        val existing = _personas.value.firstOrNull { it.id == p.id }
        val merged = if (existing == null) p.copy(createdAt = now, updatedAt = now)
        else p.copy(createdAt = existing.createdAt, updatedAt = now)
        _personas.value = _personas.value.filterNot { it.id == merged.id } + merged
        store.savePersonas(_personas.value)
        store.markPersonasChanged()
        scheduleSyncPush()
        // 「Ta 的状态」：开关开着时把名字登记到服务器（网页上显示用）。
        // fire-and-forget——失败静默（未登录/网络问题都不该打扰"保存人设"这个动作）。
        // ⚠️ 2026-10-06：判据加  —— 该项现在是**独立开关**，
        //    关掉就不该再往服务器登记（否则用户以为关了，云端还在收）。
        if (merged.isCloudMemory && merged.xinchaoEnabled) registerXinchaoPersona(merged)
    }

    fun deletePersona(id: String) {
        _personas.value = _personas.value.filterNot { it.id == id }
        store.savePersonas(_personas.value)
        store.markPersonasChanged()
        scheduleSyncPush()
        // 她的全部会话一并删除（消息靠外键级联清掉）
        viewModelScope.launch { repo.deleteSessionsOfPersona(id) }
        // 记忆也要一起清：人设不在 Room，没有外键可级联 —— 漏了这句就会留下
        // 一批「无主的记忆」。将来新建同名角色时，那些记忆会被当成它的。
        viewModelScope.launch { memoryRepo.forgetPersona(id) }
        // 「云端也要删」（v0.61.38）：角色在 App 里没了，云端那份（OB 记忆桶 + 心潮状态 +
        // 归属登记）也得清 —— 否则服务器上留着一个"已经不存在的角色"的全部记忆。
        // 服务端是**物理清桶**（与单条记忆的"归档"不同：角色都没了，留着没用）。
        purgeXinchaoPersona(id)
        // 她**专属**的表情包同理（v0.44.1 补）。⚠️ 表里的行与磁盘上的图都要清：
        // 只清行，私有目录会攒下一堆没人引用的图；只清文件，库里会留下一堆画不出来的路径
        //（那种症状是"抽到一张空图"，见 EmojiAvailability）。
        // ⚠️ 全局那些图不受影响 —— `byPersona` 只匹配 personaId 等于她的人。
        viewModelScope.launch {
            runCatching {
                val mine = db.emojiDao().byPersona(id)
                db.emojiDao().deleteByPersona(id)
                withContext(Dispatchers.IO) { mine.forEach { ImageStore.delete(it.id) } }
            }
        }
    }

    /**
     * 人设列表的置顶开关（用户 2026-09-28 要求）。
     *
     * ⚠️ 刻意**不走 [upsertPersona]**：那个会把 `updatedAt` 刷成"刚刚"，
     * 而置顶只是列表上的一个标记，不是"人设内容改过"。
     * ⚠️ 与 `Session.pinned`（会话列表的置顶）互不影响 —— 用户明确要求两者独立。
     */
    fun togglePersonaPin(id: String) {
        val target = _personas.value.firstOrNull { it.id == id } ?: return
        val next = !target.isPinned
        _personas.value = _personas.value.map { if (it.id == id) it.copy(isPinned = next) else it }
        store.savePersonas(_personas.value)
        store.markPersonasChanged()
        scheduleSyncPush()
    }

    /**
     * 清空某个人设的**全部对话记录**（保留人设本身）。
     *
     * ⚠️ 与 [deletePersona] 的分界：那个连人设一起删，这个只删"聊过的内容" ——
     * 用户想重新开始、又不想重新捏一遍角色时用它。
     * ⚠️ 记忆**不跟着清**：记忆是"她记得的事"，跟"聊过的记录"不是一回事
     *（要清记忆去记忆库页，那条路本来就有）。
     * ⚠️ `activeSessionId` 不必手工重算 —— init 里的 collect 会处理（与 deleteSession 同）。
     */
    fun clearPersonaSessions(personaId: String) {
        viewModelScope.launch { repo.deleteSessionsOfPersona(personaId) }
    }

    /**
     * 查一次**账户余额**（会话看板用，用户给的官方接口）。
     *
     * ⚠️ 余额是**账号级**的，而"哪个账号"由**这段会话实际用哪套密钥**决定
     *（分组优先）。拿全局那份去查，开了第三方分组的用户会看到**另一个账号**的余额 ——
     * 那比"读不到"更糟：一个错的数字会被当真。
     *
     * ⚠️ 失败时**往上抛**，由界面降级成"暂时读不到余额" ——
     * 看板是个只读页面，不该因为一次网络失败就整页报错。
     */
    suspend fun fetchBalance(sessionId: String?): Balance {
        val session = sessionId?.let { id -> _sessions.value.firstOrNull { it.id == id } }
        val s = effectiveSettings(session)
        if (s.apiKey.isBlank()) throw java.io.IOException("未填写 API Key")
        return client.balance(s)
    }

    /* ─────────────── 上下文压缩（v0.45.0） ─────────────── */

    /** 压缩进行中（弹窗里的进度条用）。⚠️ 只上屏，**绝不进请求体** */
    private val _compressing = MutableStateFlow(false)
    val compressing: StateFlow<Boolean> = _compressing.asStateFlow()

    /** 最近一次压缩的结果或失败原因（弹窗里显示一行） */
    private val _compressNote = MutableStateFlow<String?>(null)
    val compressNote: StateFlow<String?> = _compressNote.asStateFlow()

    fun dismissCompressNote() { _compressNote.value = null }

    /**
     * **聊天列表里的「压缩完成」系统提示**（v0.48.0，内存态）。
     *
     * ## 为什么是内存态而不是一条消息
     * ⚠️ `messages` 表是**下一轮请求前缀的一部分**，红线是**写入后不再改写**
     *（改它 = 缓存从该点全碎）。而"刚才压过一次"是**界面告知**，不是对话内容 ——
     * 把它写成一条消息，就等于为了提示用户而**永久污染了请求前缀**。
     * 所以它只活在内存里：退出重进就没了（那也没关系，该知道的已经知道）。
     *
     * ## 什么时候清
     * 用户发出**下一条消息**时清（`send` 里）—— 那条消息之后，这条提示已经过期了。
     */
    private val _compressNotice = MutableStateFlow<String?>(null)
    val compressNotice: StateFlow<String?> = _compressNotice.asStateFlow()

    /**
     * **「该压缩了」标志**（v0.48.0，内存态）。
     *
     * 只在 `ask` 模式下会被置 true：到阈值时**不弹模态框**（用户原话
     * 「不确定就不在弹出选择确认弹窗」），而是由上下文弹窗读到它、显示一行提示，
     * 并把手动压缩按钮高亮。
     *
     * 用户**主动发下一条消息**时清掉 —— 那时"刚才那一下的提示"已经翻篇。
     */
    private val _compressSuggested = MutableStateFlow(false)
    val compressSuggested: StateFlow<Boolean> = _compressSuggested.asStateFlow()

    fun dismissCompressSuggested() { _compressSuggested.value = false }

    /**
     * **最近一次请求**的缓存统计（v0.46.4，内存态、不落库）。
     *
     * ## 为什么需要它
     * `totalHit/totalMiss` 是累计值 —— 历史上那些"统计丢了"的请求已经**永久计入未命中**，
     * 新命中要把它们稀释回来要聊很多轮。所以**累计值看不出修复是否生效**。
     * 有了"最近一次"，一眼就能判断：命中数不为 0 就说明统计通了。
     *
     * ⚠️ 只在内存里，退出即清 —— 它是个**诊断读数**，不是账目。
     */
    private val _lastTurnStats = MutableStateFlow<Triple<Int, Int, Int>?>(null)
    val lastTurnStats: StateFlow<Triple<Int, Int, Int>?> = _lastTurnStats.asStateFlow()

    /**
     * 「最近一次」那组读数是不是**真的读数**（v0.51.0）。
     *
     * `false` = 这一轮的服务商根本没有报缓存用量（字段不在它的协议里）——
     * 界面必须改说"该服务商不提供"，而不是显示一个 0%。
     * 把"没这个数"显示成 0，用户会以为缓存全废，然后去改人设、改历史，
     * 而那些动作**真的**会把前缀弄断（越查越糟）。
     */
    private val _lastTurnReports = MutableStateFlow(true)
    val lastTurnReports: StateFlow<Boolean> = _lastTurnReports.asStateFlow()

    /**
     * **压缩触发判定**（v0.48.0）。三种模式的分叉点。
     *
     * ⚠️ 判定逻辑本身在 [ContextCompress.decideCompress]（**纯函数**）——
     * 这里只负责把状态映射成副作用。为什么这么分：这个类要 Android 环境才跑得起来，
     * 判定长在这里就等于**单测覆盖不到"唯一会花钱的那条分叉"**。
     *
     * ## 三个模式
     * - `AUTO` → 直接压（异步，**不阻断这次发送**）；
     * - `ASK` → 只置 [compressSuggested]，由上下文弹窗显示一行 ——
     *   ⚠️ **不弹模态确认框**（用户原话「不确定就不在弹出选择确认弹窗」）；
     * - `MANUAL` → 什么也不做（旧行为）。
     */
    private fun evaluateCompressTrigger(
        sessionId: String,
        session: Session,
        frozenPrefix: String,
    ) {
        val s = _settings.value
        val decision = ContextCompress.decideCompress(
            frozenPrefix = frozenPrefix,
            messages = session.messages,
            summary = session.summary,
            coveredCount = session.summaryCount,
            limitTokens = ContextCompress.contextLimit(
                effectiveSettings(session).model,
                // ⚠️ 用**记忆上下文**（不是 API 上下文）：它才是"什么时候开始压缩"的分母。
                //    回落链见 memoryWindowForSession —— 老分组只有 contextWindow 时行为不变。
                memoryWindowForSession(session),
            ),
            threshold = s.compressThreshold,
            mode = s.compressMode,
            // ⚠️ v0.61.56：不传 `reservedOutputTokens` —— 用默认值（[ContextCompress.SUMMARY_OUTPUT_TOKENS]）。
            //    触发线公式 = min(窗口×比例, 窗口−输出预留−headroom)，见 [ContextCompress.triggerLineTokens]。
            //    弹窗显示的进度条走**同一个函数**，两处不会漂移。
        )
        when (decision) {
            ContextCompress.CompressDecision.None ->
                // 没到阈值（或手动模式、或没新素材）→ 把"该压缩了"收回去，别挂着不放
                _compressSuggested.value = false
            ContextCompress.CompressDecision.Suggest ->
                // ⚠️ 这里**只置标志**，绝不弹模态框
                _compressSuggested.value = true
            ContextCompress.CompressDecision.CompressNow -> {
                if (_compressing.value) return
                _compressSuggested.value = false
                summarizeContext(sessionId)
            }
        }
    }

    /**
     * 发送被拒且**认出是「上下文超窗」**时的一次自动恢复（v0.61.46）。
     *
     * ⚠️ 为什么不受 compressMode 约束：超窗是**硬故障**，不是调优偏好 ——
     * 手动模式下也必须能修，否则用户除了进库删记录没有别的出路
     *（参考实现的措辞：an over-window request is a hard API failure,
     * not a tuning preference）。
     * 压缩本身再失败也不吞：走 `_compressNote` 如实说（见 summarizeContext 的分类文案）。
     */
    private fun maybeAutoCompressOnOverflow(sessionId: String, e: Throwable) {
        if (!ContextCompress.isContextOverflowError(e.message)) return
        if (_compressing.value) return
        _compressNote.value = "内容超出了模型能装下的长度 —— 正在自动压缩，压好再发一次就好。"
        summarizeContext(sessionId)
    }

    /**
     * **手动**触发一次上下文压缩（弹窗里的那个按钮）。
     *
     * ## 它做什么
     * 把"较早的那一整段"发给模型总结成摘要，写进 `sessions.summary`；
     * 之后组装请求时 history 就变成「摘要 + 最近若干条」（见 `ContextCompress`）。
     *
     * ## ⚠️ 它**不动** `messages`
     * 聊天记录一条不少（库里、界面上都是）—— 变的只是"发给模型的那一份"。
     * 用户原话就是「聊天记录不能丢失」。
     *
     * ## ⚠️ 代价（必须说清）
     * 摘要一旦进了 history，下一轮请求前缀就与上一轮不同 → **那一次必然 miss**。
     * 这是"不撞上下文上限"的必然代价，所以压缩是**用户手动触发**的，不偷偷做。
     */
    fun summarizeContext(sessionId: String) {
        if (_compressing.value) return
        val session = _sessions.value.firstOrNull { it.id == sessionId } ?: return
        val segment = ContextCompress.segmentToSummarize(session.messages)
        if (segment.isEmpty()) {
            _compressNote.value = "这段对话还短，不用压缩。"
            return
        }
        // ⚠️ 用**这段会话实际会用**的设置（分组优先），而不是全局那一份 ——
        //    与发消息走同一份实现。取全局的话：第三方分组会去 DeepSeek 的地址做摘要，
        //    而密钥只存在分组里的新用户会在这里被拦下、**压缩永远触发不了**。
        val s = effectiveSettings(session)
        if (s.apiKey.isBlank()) {
            _compressNote.value = "请先到「设置 → 连接设置」里填好密钥"
            return
        }
        // v0.61.46：预算与分块 —— **压缩请求自己必须先装得下**。
        // 它修的是用户报的"上下文超限之后就不能压缩了"：原来把被压缩的整段原文
        // 一次发出，会话贴到窗口时摘要请求自己也超窗 → 被拒 → 死锁。
        // limit 与触发判定同口径（记忆窗口优先，见 evaluateCompressTrigger）。
        val limit = ContextCompress.contextLimit(s.model, memoryWindowForSession(session))
        val budget = ContextCompress.summarizeBudget(limit)
        val chunks = ContextCompress.chunkSegment(segment, budget)
        _compressing.value = true
        _compressNote.value = null
        viewModelScope.launch {
            try {
                // ⚠️ 摘要请求也要带**主对话那份前缀**（v0.48.0）——
                // 这样前两段与上一轮请求逐字节相同，这次调用能吃前缀缓存。
                // 前缀在这里自己重建（与人设页/发送路径同一个函数），
                // 不额外传参 → 调用点不用改，也不会出现"两个地方各拼一份"的漂移。
                val prefix = personaById(session.personaId)
                    ?.let { PromptEngine.buildFrozenPrefix(s, it, userPersonaFor(it)) }
                    .orEmpty()

                if (chunks.size <= 1) {
                    // ── 单块路径：与 v0.48.0 完全一致（小段一次压完，吃前缀缓存）──
                    val summary = client.summarize(
                        settings = s,
                        frozenPrefix = prefix,
                        segment = segment,
                        instruction = ContextCompress.buildSummaryPrompt(segment),
                    )
                    // ⚠️ **摘要必须能用**（v0.48.0）：非空白 + 比被压缩段更短。
                    // 两条判据见 `ContextCompress.isUsableSummary` 的注释 ——
                    // 空白摘要能通过"更小"那条（0 < 原文），所以必须单独挡。
                    // 放弃时**不写任何状态**：库里、界面上一切照旧。
                    if (!ContextCompress.isUsableSummary(summary, segment)) {
                        _compressNote.value = compressFailureText(SummaryNotUsable(summary.isBlank()))
                        return@launch
                    }
                    commitSummary(sessionId, summary.trim(), segment.size, segment)
                    _compressNote.value = "已把较早的 ${segment.size} 条记录压成摘要。"
                    // 聊天列表里的系统提示（v0.48.0）：只说"发生了什么"，不给用户布置任务
                    _compressNotice.value = "已把较早的 ${segment.size} 条记录压成摘要。"
                    // 压完了就不必再提示"该压缩了"
                    _compressSuggested.value = false
                    return@launch
                }

                // ── 分块滚动路径（v0.61.46）：段太大，一次发必超窗 —— 分块逐段摘要、
                //    滚动合并（旧摘要 + 新块 → 新摘要，LibreChat 同款思路）。
                //    **每完成一块立即提交**（渐进提交）：任意时刻中断，状态都自洽
                //   （摘要覆盖 [0, covered)），已完成的部分不回退、更不丢内容。
                var merged: String? = null
                var covered = 0
                var done = 0
                var failure: Throwable? = null
                for ((index, chunk) in chunks.withIndex()) {
                    val out = try {
                        client.summarize(
                            settings = s,
                            // 第 2 块起没有公共前缀可复用（输入 = 旧摘要 + 新块）——传空
                            frozenPrefix = if (index == 0) prefix else "",
                            segment = chunk,
                            instruction = ContextCompress.buildSummaryPrompt(chunk, previousSummary = merged),
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failure = e
                        break
                    }
                    if (!ContextCompress.isUsableSummary(out, chunk, previousSummary = merged)) {
                        failure = SummaryNotUsable(out.isBlank())
                        break
                    }
                    merged = out
                    covered += chunk.size
                    done++
                    // 渐进提交：每块完成即推进（被中断也不回退已完成的进度）
                    commitSummary(sessionId, out.trim(), covered, chunk)
                }
                when {
                    done == 0 -> {
                        _compressNote.value = compressFailureText(failure)
                    }
                    done < chunks.size -> {
                        _compressNote.value =
                            "内容较多，已分 $done/${chunks.size} 步压缩 $covered 条；剩下的可以再点一次压缩继续。"
                        _compressNotice.value = "已把较早的 $covered 条记录压成摘要（还有一部分没压完）。"
                        _compressSuggested.value = true
                    }
                    else -> {
                        _compressNote.value = "已把较早的 $covered 条记录压成摘要（分了 ${chunks.size} 步完成）。"
                        _compressNotice.value = "已把较早的 $covered 条记录压成摘要。"
                        _compressSuggested.value = false
                    }
                }
            } catch (e: CancellationException) {
                // 取消不算失败：别把它当成"压缩出错"报给用户
                throw e
            } catch (e: Exception) {
                // 用户点按钮触发的，失败必须让他看见（他正盯着进度条）
                _compressNote.value = "压缩失败：${compressFailureText(e)}"
            } finally {
                _compressing.value = false
            }
        }
    }

    /**
     * 提交一次摘要结果（单块与分块路径共用）。
     *
     * ⚠️ 分块路径**每完成一块就调一次**（渐进提交）：状态在任意时刻都自洽 ——
     * 「摘要覆盖 [0, summaryCount)，原文从 summaryCount 起」，见 `buildHistory`。
     *
     * ⚠️ 同时记下**摘要覆盖到哪条**（v0.45.6）与**累计命中快照**（v0.46.1）：
     * 没有快照，"压缩之后"的命中率算不出来（压缩那一次会把当轮全额计入未命中）。
     */
    private fun commitSummary(sessionId: String, summary: String, count: Int, segment: List<ChatMessage>) {
        val upTo = segment.lastOrNull()?.createdAt ?: 0L
        patchSession(sessionId) { cur ->
            cur.copy(
                summary = summary,
                summaryUpTo = upTo,
                summaryCount = count,
                hitAtCompress = cur.totalHit,
                missAtCompress = cur.totalMiss,
            )
        }
    }

    /** 内部信号：摘要产出不可用（空 / 没变小）——与 API 异常分开，走各自的文案。 */
    private class SummaryNotUsable(val blank: Boolean) : Exception(
        if (blank) "摘要为空" else "摘要没有比原文更短",
    )

    /**
     * 压缩失败的**人话**（v0.61.46）。
     *
     * ⚠️ 关键分叉：**超窗类失败**不能走通用 400 话术（"换个说法再发？"）——
     * 换个说法没用，长度才是问题。说清"为什么 + 出路"（改窗口 / 换大窗口模型）。
     * 这正是用户报「点压缩提示被拒」时最需要看到、却从来没看到过的那句话。
     */
    private fun compressFailureText(e: Throwable?): String = when {
        e is SummaryNotUsable && e.blank ->
            "这次没拿到有效摘要，已放弃（未改动任何记录）。可以再试一次。"
        e is SummaryNotUsable ->
            "摘要没有比原文更短，已放弃这次压缩（未改动任何记录）。"
        e != null && ContextCompress.isContextOverflowError(e.message.orEmpty()) ->
            "这段内容对模型的窗口来说太长，连分步压缩也发不出去。" +
                "可以去「连接设置」里把这个分组的窗口填对（按服务商官网的数值），或换一个窗口更大的模型。"
        e != null -> ChatErrors.forException(e).spoken
        else -> "未知原因"
    }

    /* ─────────────── 本地用户资料（「我」） ─────────────── */
    private val _profile = MutableStateFlow(store.loadProfile())

    /**
     * 「我」是谁 —— 本地头像与昵称（无账号体系，见 [UserProfile] 的说明）。
     *
     * 住在 ViewModel 里的理由与草稿相同：它不是某个界面的临时状态，
     * 而是"这个人对这台设备"的资料，多处界面（我的页、聊天气泡、搜索结果）都要读。
     */
    val profile: StateFlow<UserProfile> = _profile.asStateFlow()

    /**
     * **从本地存储重新读一遍资料**（v0.60.0）。
     *
     * ## 为什么必须有这个方法
     * `_profile` 是在构造时 `store.loadProfile()` 读一次的（见上方字段初始化），
     * 之后再没有重读的路径。而注册流程里的头像（`AuthViewModel.saveLocalAvatar`）
     * 是**直接写 Store** 的 —— 于是 Store 有、内存没有，
     * 「我的」页读的是内存里那个旧值 → **注册时选的头像永远不显示**。
     * （用户 2026-10-02 报的就是这个。）
     *
     * 登录/注册成功后调一次即可。
     */
    fun reloadProfile() {
        _profile.value = store.loadProfile()
    }

    fun saveProfile(transform: (UserProfile) -> UserProfile) {
        val next = transform(_profile.value)
        _profile.value = next
        store.saveProfile(next)
    }

    /* ─────────────── 使用统计上报（可选，只发给用户自己的服务器） ─────────────── */

    private val deviceId: String by lazy { store.deviceId() }

    /**
     * 往内置的服务端发一次使用统计 —— 管理后台仪表盘的数据来源。
     *
     * ## 这是**全局默认行为，没有开关**（用户 2026-09-27 要求）
     * 用户原话：「上报使用统计删除改为静默直接上报 不需要开启关闭 这是软件的全局默认值」。
     *
     * 所以：不再看 `telemetryEnabled`（那个字段保留但不再参与判断，
     * 免得旧版本存下的 false 把上报永久关掉）。设置页里改为**一句说明** ——
     * 说明写在明面上，比藏在一个开关后面更诚实：用户真正该知道的是
     * **到底发了什么**（答案：只有计数，没有任何对话内容）。
     *
     * ## 地址来自内置常量，不读用户填的字段
     * 这与"后端地址内置到代码里"是同一条决定（见 [ServerConfig]）。
     * 唯一保留的闸门是"地址非空" —— 本地模式（没部署后端）不该有任何出网请求。
     */
    fun reportTelemetry() {
        val base = ServerConfig.BASE_URL
        if (base.isBlank()) return

        viewModelScope.launch {
            // v0.61.23 错峰：启动后随机等 20~180 秒再上报 —— 防"新版本发布时所有人同时
            // 启动"把请求堆成一坨（上报是统计类数据，晚几分钟毫无影响）。
            delay(Telemetry.staggerDelayMs())
            runCatching {
                val snapshot = Telemetry.Snapshot(
                    deviceId = deviceId,
                    // 带上登录态：后台据此把这台设备关联到账号（未登录 = 空串，匿名照旧）
                    token = store.loadAuth().token,
                    model = Build.MODEL.orEmpty(),
                    androidVersion = Build.VERSION.RELEASE.orEmpty(),
                    appVersion = BuildConfig.VERSION_NAME,
                    personaCount = _personas.value.size,
                    sessionCount = _sessions.value.size,
                    messageCount = _sessions.value.sumOf { it.messages.size },
                    memoryCount = db.memoryDao().count(),
                    hitTokens = _sessions.value.sumOf { it.totalHit },
                    missTokens = _sessions.value.sumOf { it.totalMiss },
                )
                Telemetry.report(base, snapshot).getOrThrow()
            }
        }
    }

    /* ─────────────── 「Ta 的状态」上报（v0.61.34） ─────────────── */

    /**
     * 保存人设时，把名字登记到「Ta 的状态」服务器（仅当开关开着）。
     *
     * ⚠️ fire-and-forget + 静默：登记只是"网页上显示人设名"的增强；
     *    失败（未登录 / 网络）不影响本地保存。重复保存 = 重复登记，幂等无害
     *    （服务端 register 端点可重复调用，也用于改名）。
     */
    private fun registerXinchaoPersona(persona: Persona) {
        val base = ServerConfig.BASE_URL
        if (base.isBlank()) return
        viewModelScope.launch {
            runCatching {
                XinchaoReport.register(
                    baseUrl = base,
                    token = store.loadAuth().token,
                    personaId = persona.id,
                    name = persona.displayName,
                )
            }
        }
    }

    /**
     * 把一轮对话上报给「Ta 的状态」服务器（仅当该人设开关开着）。
     *
     * ⚠️ **开关关 = 第一行就 return** —— 零网络、零序列化（验收标准：关着时零上报）。
     * ⚠️ 用户文本从历史取「最后一条 user 消息」：commitAssistant 走到上报点时，
     *    本轮 user 已在流式开始前落盘（send 内「② user 消息立即落盘」）。
     * ⚠️ 失败静默：上报是增强，绝不能影响对话本身。
     * ⚠️ exchange 格式是服务器（情绪引擎）契约 —— 组装在 [XinchaoReport.eventBody] 里。
     */
    private fun reportXinchaoTurn(sessionId: String, assistantText: String) {
        if (assistantText.isBlank()) return
        val base = ServerConfig.BASE_URL
        if (base.isBlank()) return
        val session = _sessions.value.firstOrNull { it.id == sessionId } ?: return
        val persona = personaById(session.personaId) ?: return
        // ⚠️ 2026-10-06：判据加 （该项现在是独立开关）。
        //    关掉「接入 Ta 的状态」就不该再上报对话事件 —— 否则关了个寂寞。
        if (!persona.isCloudMemory || !persona.xinchaoEnabled) return
        val userText = session.messages.lastOrNull { it.role == "user" }?.content ?: return
        if (userText.isBlank()) return
        viewModelScope.launch {
            runCatching {
                XinchaoReport.reportEvent(
                    baseUrl = base,
                    token = store.loadAuth().token,
                    personaId = persona.id,
                    eventId = UUID.randomUUID().toString().take(24),
                    userText = userText,
                    assistantText = assistantText,
                    atIso = java.time.Instant.now().toString(),
                )
            }
        }
    }

    /**
     * 删除一个角色在云端的全部痕迹（OB 记忆桶 + 心潮状态 + 归属登记）。
     *
     * fire-and-forget + 失败静默：删人设本地是同步完成的，云端清不掉不该把整个动作
     * 变成"删除失败"（personaId 是全新的，下次新建也不会复用这堆数据）。
     * 服务端会记一条 `xinchao_persona_purged` 审计。
     */
    private fun purgeXinchaoPersona(personaId: String) {
        val base = ServerConfig.BASE_URL
        if (base.isBlank()) return
        viewModelScope.launch {
            runCatching {
                XinchaoMemoryApi.deletePersona(base, store.loadAuth().token, personaId)
            }
        }
    }

    /**
     * 拉某人设的「Ta 此刻」（详情页那张卡）。仅在该人设开启接入时由界面调用。
     *
     * ⚠️ 走 [XinchaoStateApi]，复用人设列表端点的 `view`（服务端已清洗的此刻文本）——
     *    与用户侧网页 `/me/` 同一份数据源、同一套清洗（单一真源）。
     *    失败 / 未登记 / 未登录一律 `null`，界面走"空"分支（拉不到不该让详情页打不开）。
     */
    /** 心潮此刻块的缓存（personaId → (文本, 取回时刻)）—— 见 [xinchaoNoteOf] 的 TTL 理由。 */    private val xinchaoNoteCache = mutableMapOf<String, Pair<String, Long>>()

    /** 此刻块缓存有效期：5 分钟。 */
    private val xinchaoNoteTtlMs = 5 * 60 * 1000L

    /**
     * **云端记忆**人设的附录记忆行（v0.61.54，记忆彻底分轨的读侧）。
     *
     * 从 OB 取该人设的记忆（`memory/list`，与云端记忆页同一份数据），
     * 按与本地路径**同一个** [MemoryInjector.buildMemoryLines] 产出附录行 ——
     * 这样附录 `<memories>` 块的形状与分轨前**逐字节一致**（有 PromptEngineTest 钉着）。
     *
     * ## 为什么按 query 过滤
     * OB 的列表端点返回全部（最多 300 条），而附录只该带**与这句话相关**的那些
     *（与本地检索同一语义）。这里用简单子串/词命中做粗筛 —— 云端没有本地那套
     * 衰减+嵌入检索，粗筛比"把 300 条全塞进附录"好得多（后者会撑爆 token）。
     *
     * 失败一律空表：记忆是增强，不是前提。
     */
    private suspend fun cloudAppendixLines(personaId: String, userInput: String): List<String> {
        val base = ServerConfig.BASE_URL
        val token = store.loadAuth().token
        if (base.isBlank() || token.isBlank()) return emptyList()
        val buckets = XinchaoMemoryApi.fetchBuckets(base, token, personaId).orEmpty()
        if (buckets.isEmpty()) return emptyList()
        // 粗筛：与输入有 2 字以上公共子串的优先；都不命中时取最近若干条（保证"她记得你"的基本体感）
        val needle = userInput.trim()
        val relevant = if (needle.length >= 2) {
            buckets.filter { b ->
                b.content.contains(needle) ||
                    needle.windowed(2).any { b.content.contains(it) } ||
                    b.title.isNotBlank() && needle.windowed(2).any { b.title.contains(it) }
            }
        } else {
            emptyList()
        }
        val picked = (relevant.ifEmpty { buckets.sortedByDescending { it.createdAt.orEmpty() } })
            .take(MemoryRetriever.DEFAULT_MAX_RESULTS)
        // 复用同一注入器：把云端条目映射成"只带 content 的 MemoryEntity"，
        // 保证转义与总长截断（MAX_TOTAL_CHARS）与本地路径完全一致。
        return MemoryInjector.buildMemoryLines(
            picked.map { b ->
                MemoryEntity(
                    id = b.id,
                    userId = MemoryEntity.LOCAL_USER_ID,
                    personaId = personaId,
                    sessionId = null,
                    scope = MemoryEntity.SCOPE_PERSONA,
                    content = b.content,
                    category = b.domain,
                    importance = b.importance,
                    source = MemoryEntity.SOURCE_AUTO,
                    embedding = null,
                    cloudBucketId = b.id,
                    createdAt = 0L,
                    lastAccessedAt = 0L,
                    expiresAt = null,
                )
            },
        )
    }

    /**
     * 取某人设的**此刻块原文**（进对话附录），带 TTL 缓存。
     *
     * ⚠️ 只在**云端记忆**的人设上取（本地模式没有心潮）；失败一律 `null` ——
     *    附录少这一块不影响对话（与记忆检索同一条降级纪律）。
     */
    private suspend fun xinchaoNoteOf(personaId: String, sessionId: String = ""): String? {
        val now = System.currentTimeMillis()
        xinchaoNoteCache[personaId]?.let { (note, at) ->
            if (now - at < xinchaoNoteTtlMs) return note
        }
        val persona = personaById(personaId) ?: return null
        // ⚠️ 2026-10-06：判据 = 「接入 Ta 的状态」开关（用户要求该项独立开关）。
        if (!persona.isCloudMemory || !persona.xinchaoEnabled) return null
        val base = ServerConfig.BASE_URL
        val token = store.loadAuth().token
        // v0.61.53：**优先完整信封**（sections 比 /v1/now 多：含梦余韵 / 匣子提醒 / 觉察候选），
        // 取不到（还没生成 / 网络 / 旧服务端没这条路由）再回落此刻块 —— 两级降级，都不影响对话。
        val note = XinchaoContextApi.fetch(base, token, personaId, sessionId)
            ?: XinchaoStateApi.fetchModelNote(base, token, personaId)
            ?: return null
        xinchaoNoteCache[personaId] = note to now
        return note
    }

    /** 心潮「当前意图」（v0.61.52）—— 详情页「Ta 此刻」卡用。失败 `null`。 */
    suspend fun xinchaoIntentOf(personaId: String): XinchaoIntent? {
        // ⚠️ 2026-10-06：看「接入 Ta 的状态」开关（可单独关）。
        val p = personaById(personaId) ?: return null
        if (!p.isCloudMemory || !p.xinchaoEnabled) return null
        return XinchaoIntentApi.fetch(ServerConfig.BASE_URL, store.loadAuth().token, personaId)
    }

    /** 心潮「梦境余韵」（v0.61.52）—— 详情页「Ta 此刻」卡用。失败 `null`。 */
    suspend fun xinchaoDreamOf(personaId: String): XinchaoDream? {
        // ⚠️ 2026-10-06：看「接入 Ta 的状态」开关（可单独关）。
        val p = personaById(personaId) ?: return null
        if (!p.isCloudMemory || !p.xinchaoEnabled) return null
        return XinchaoBreathApi.fetch(ServerConfig.BASE_URL, store.loadAuth().token, personaId)
    }

    suspend fun xinchaoStateOf(personaId: String): XinchaoState? {
        val base = ServerConfig.BASE_URL
        if (base.isBlank()) return null
        return runCatching {
            XinchaoStateApi.fetch(base, store.loadAuth().token, personaId)
        }.getOrNull()
    }

    /* ─────────────── 数据备份（导出） ─────────────── */

    /**
     * 把当前全部数据导出到用户选定的位置（SAF）。
     *
     * ## 为什么让用户选位置，而不是自己写死一个目录
     * 写到 `getExternalFilesDir` 的话，Android 11+ 用户**根本进不去那个目录**
     *（`Android/data/...` 被系统限制），等于导出了一个用户拿不到的文件。
     * `CreateDocument` 让用户自己挑（下载目录、网盘 App、U 盘…），且不需要任何权限。
     *
     * ## 它**不碰缓存**
     * 全程只读：读数据 → 写文件。不改 `messages`、不改冻结前缀，一个字节都不动。
     *
     * ## ⚠️ 本轮只做「导出」
     * 恢复要往库里写（删旧 + 插新），而那一步在这台机器上**无法真机验证** ——
     * 写错的代价恰恰是"用户以为有备份、其实恢复出来是坏的"，比没有备份更糟。
     * 编解码已经由 `BackupTest` 的 9 条断言钉住（往返、密钥不泄露、坏文件拒绝…），
     * 写库与恢复界面留到下一轮，连同真机验证一起做。
     */
    fun exportBackupTo(uri: Uri, scope: BackupScope = BackupScope.ALL) {
        viewModelScope.launch {
            runCatching {
                val text = buildBackupJson(scope)
                withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    resolver.openOutputStream(uri)?.use { out ->
                        out.write(text.toByteArray(Charsets.UTF_8))
                        out.flush()
                    } ?: error("这个位置写不进去")
                }
            }
                .onSuccess {
                    // ⚠️ 把范围写进提示：用户导了三次（三个范围）之后，
                    //    只有这句话能告诉他手上这个文件里是什么。
                    _notice.value = "已导出「${scope.label}」（不含 API Key 与图片文件）"
                }
                .onFailure {
                    // ⚠️ 不许把 `it.message` 直接倒给用户（见 IoErrors 的注释）：
                    //    那会是一句带路径和 EACCES 的英文，用户看不懂也做不了什么。
                    _error.value = "导出失败：${IoErrors.explain(it)}"
                }
        }
    }

    /* ─────────────── 分项导出：聊天记录（v0.54.0）─────────────── */

    /**
     * **导出若干段会话**（聊天记录）。
     *
     * ## 为什么逐份写、而不是打成一个包
     * 用户要的是"一眼知道哪个文件是哪段对话"—— 打成一个 zip 就又要解压才能看。
     * 逐份写进他选的那个文件夹，文件名带会话标题，双击就能读。
     *
     * ## 进度是逐份的
     * 每写一份更新一次 [exportState]。份数少的时候进度条会跳着走，这是正常的
     *（见 `ExportState` 的注释：不做到字节级）。
     */
    fun exportSessions(ids: List<String>, format: ExportFormat, treeUri: Uri) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            _exportState.value = ExportState.Running(0, ids.size)
            val outcome = withContext(Dispatchers.IO) {
                runCatchingForTask { writeSessions(ids, format, treeUri) }
            }
            _exportState.value = outcome.fold(
                onSuccess = { r ->
                    when {
                        // ⚠️ 半成功必须说成半成功：已经落盘的那几份是**真的存在**的，
                        //    笼统说一句"失败"会让人以为一份都没出去、再导一遍。
                        r.failReason != null && r.ok > 0 ->
                            ExportState.Failed("已写出 ${r.ok} 份，之后的失败了：${r.failReason}")
                        r.failReason != null ->
                            ExportState.Failed("一份都没写出去：${r.failReason}")
                        r.ok == 0 ->
                            ExportState.Failed("没有可导出的对话")
                        else -> ExportState.Done(r.ok, "你选的那个文件夹")
                    }
                },
                onFailure = { ExportState.Failed(it.message ?: "写入失败") },
            )
        }
    }

    /** 逐份写的结果：**写成功几份** + 失败原因（`null` = 全成）。 */
    private data class WriteOutcome(val ok: Int, val failReason: String?)

    /**
     * 把选中的会话逐份写进用户选定的目录树。
     *
     * ⚠️ 中途失败时**不抛**，而是把"已经写出去几份"一起带回来 ——
     * 因为已经落盘的那几份是**真的存在**的（用户能在文件管理器里看到）。
     * 早先这里直接 `error(...)`，调用方只知道"失败"，于是状态卡说"没成功"，
     * 用户会以为一份都没写出去、再去导一遍 —— 而他手上其实已经有了几份。
     * **半成功必须说成半成功。**
     */
    private fun writeSessions(ids: List<String>, format: ExportFormat, treeUri: Uri): WriteOutcome {
        val ctx = getApplication<Application>()
        val resolver = ctx.contentResolver
        val wanted = ids.toSet()
        val picked = _sessions.value.filter { it.id in wanted }
        val personaById = _personas.value.associateBy { it.id }
        val treeDoc = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
        var ok = 0
        picked.forEachIndexed { i, s ->
            _exportState.value = ExportState.Running(i, picked.size)
            val who = displayNameOf(personaById[s.personaId])
            val body = Exporter.chat(s, personaById[s.personaId], format)
            val name = Exporter.fileNameFor(
                prefix = "与${who}的对话",
                suffix = s.title.ifBlank { "未命名" },
                at = System.currentTimeMillis(),
                format = format,
            )
            val uri = DocumentsContract.createDocument(resolver, treeDoc, format.mime, name)
                ?: return WriteOutcome(ok, "在这个文件夹里建不出文件（可能没有写入权限）")
            val stream = resolver.openOutputStream(uri)
                ?: return WriteOutcome(ok, "写入被系统拒绝")
            stream.use { out ->
                out.write(body.toByteArray(Charsets.UTF_8))
                out.flush()
            }
            ok++
        }
        _exportState.value = ExportState.Running(picked.size, picked.size)
        return WriteOutcome(ok, null)
    }

    /**
     * 人设显示名 —— **统一走 `Persona.displayName`**（v0.61.10）。
     *
     * ⚠️ 这里原本取的是"设定**首行**"，而列表读的是 `Persona.displayName`
     *（正则抓「角色名称：X」，抓不到就说"未命名角色"）—— **同一个人设在两处显示
     * 不同的名字**。现在两处共用同一条回退链（roleName → 「角色名称：X」→ 首行 → Ta），
     * 口径只有一个，不会再漂移。
     */
    private fun displayNameOf(p: Persona?): String = p?.displayName ?: "Ta"

    /**
     * **老用户兼容迁移**（v0.61.17）：把历史里"连续多条她的回复"收敛成一条。
     *
     * ## 它修的是什么
     * v0.61.16 之前 `saveSession` 只 upsert、**从不删除** —— 每次重新生成，
     * 旧回复在内存里没了、**库里却留着**。老会话于是长成
     * `[你, 她(旧), 她(旧), 她(旧), 她(新)]`。用户的原话：
     * 「一个消息重新生成了 4 次，用户用删除就比如删除 4 次，这个能解决吗」——
     * 这一步就是解决它：每组连续回复只留最后一条，前面的并入 `superseded`（内容不丢）。
     *
     * ## ⚠️ 幂等，所以每次启动都跑
     * 干净的历史一个字不动；跑多少次结果一样。**不记"迁移过没有"** ——
     * 那需要一个额外的标志位，而幂等函数不需要（少一个会漂的状态）。
     *
     * ## ⚠️ 静默失败
     * 这是修数据，不该因为一次失败就弹错误吓用户；下一次启动还会再来一遍。
     */
    private fun sanitizeSquashedReplies() {
        viewModelScope.launch {
            runCatching {
                repo.loadSessions().forEach { s ->
                    val fixed = MessageEdits.squashAssistantRuns(s.messages)
                    if (fixed.size != s.messages.size) {
                        repo.saveSession(s.copy(messages = fixed))
                    }
                }
            }
        }
    }

    /** 清掉导出临时卡片（用户看完点掉）。 */
    fun clearExportState() {
        _exportState.value = ExportState.Idle
    }

    /* ─────────── 分项导出：人设 / 记忆 / 模型配置（v0.54.0）─────────── */

    /**
     * **导出人设**（[ids] 为空 = 全量导出）。
     *
     * ⚠️ 与聊天记录不同，人设 / 记忆 / 模型配置都产出**一个文件**：
     * 它们体量小，而且"一个人设一个文件"只会让用户得到一堆碎文件。
     * 只有聊天记录才按会话拆 —— 一段长对话是要读很久的，混在一起没法看。
     */
    fun exportPersonas(ids: List<String>, format: ExportFormat, treeUri: Uri) {
        val all = _personas.value
        val picked = if (ids.isEmpty()) all else all.filter { it.id in ids.toSet() }
        if (picked.isEmpty()) return
        viewModelScope.launch {
            exportOneFile(
                prefix = if (ids.isEmpty()) "全部人设" else "人设",
                suffix = if (picked.size == 1) displayNameOf(picked.first()) else "${picked.size}个",
                body = Exporter.personas(picked, format),
                format = format,
                treeUri = treeUri,
            )
        }
    }

    /** **导出记忆库**（全部；记忆是按角色存的，导出时要带上"属于谁"）。 */
    fun exportMemories(format: ExportFormat, treeUri: Uri) {
        viewModelScope.launch {
            _exportState.value = ExportState.Running(0, 1)
            val outcome = withContext(Dispatchers.IO) {
                // ⚠️ 这里同样不能吞取消：它跑在 IO 里，若把 CancellationException
                //    收进 Result，外层的 `withContext` 会以为"正常完成"，
                //    于是状态卡永远停在"正在导出"，而活儿其实早被取消了。
                runCatchingForTask {
                    val userId = MemoryEntity.LOCAL_USER_ID
                    val names = _personas.value.associate { it.id to displayNameOf(it) }
                    val all = _personas.value.flatMap { p ->
                        runCatching { db.memoryDao().allOfPersona(userId, p.id) }
                            .getOrElse { emptyList() }
                    }
                    val body = Exporter.memories(all, { names[it] ?: "未知角色" }, format)
                    writeOneFile(body, "记忆库", "${all.size}条", format, treeUri)
                    all.size
                }
            }
            _exportState.value = outcome.fold(
                onSuccess = { ExportState.Done(it, "你选的那个文件夹") },
                onFailure = { ExportState.Failed(it.message ?: "写入失败") },
            )
        }
    }

    /**
     * **一键导出模型配置**（分组 + 地址 + 密钥 + 勾选的模型）。
     *
     * ⚠️ 这份文件**含明文密钥**（用户明确要求"包括分组 api 地址 密钥等等"）。
     *    所以它只走 JSON —— 不给 md / csv 那些"拿出去给人看"的格式，
     *    并且界面上必须写明"别乱发"。这是它与别的导出最不一样的地方。
     */
    fun exportModelConfig(uri: Uri) {
        viewModelScope.launch {
            _exportState.value = ExportState.Running(0, 1)
            val outcome = runCatchingForTask {
                val text = buildString {
                    appendLine("{")
                    appendLine("  \"format\": \"yuki-model-config-v1\",")
                    appendLine("  \"exportedAt\": ${System.currentTimeMillis()},")
                    // 复用 ProviderGroups 自己的编码（它就是分组那份 JSON 的权威形状）
                    appendLine("  \"groups\": ${ProviderGroups.encode(_groups.value)}")
                    appendLine("}")
                }
                withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    resolver.openOutputStream(uri)?.use { out ->
                        out.write(text.toByteArray(Charsets.UTF_8))
                        out.flush()
                    } ?: error("这个位置写不进去")
                }
                _groups.value.size
            }
            _exportState.value = outcome.fold(
                onSuccess = { ExportState.Done(it, "你选的那个文件") },
                onFailure = { ExportState.Failed(it.message ?: "写入失败") },
            )
        }
    }

    /** 写一份"一个文件装完"的导出（人设 / 记忆共用），并推进导出状态。 */
    private suspend fun exportOneFile(
        prefix: String,
        suffix: String,
        body: String,
        format: ExportFormat,
        treeUri: Uri,
    ) {
        _exportState.value = ExportState.Running(0, 1)
        val outcome = runCatchingForTask {
            withContext(Dispatchers.IO) { writeOneFile(body, prefix, suffix, format, treeUri) }
            1
        }
        _exportState.value = outcome.fold(
            onSuccess = { ExportState.Done(it, "你选的那个文件夹") },
            onFailure = { ExportState.Failed(it.message ?: "写入失败") },
        )
    }

    /** 往用户选定的目录树里写一个文件。 */
    private fun writeOneFile(
        body: String,
        prefix: String,
        suffix: String,
        format: ExportFormat,
        treeUri: Uri,
    ) {
        val resolver = getApplication<Application>().contentResolver
        val treeDoc = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
        val name = Exporter.fileNameFor(prefix, suffix, System.currentTimeMillis(), format)
        val uri = DocumentsContract.createDocument(resolver, treeDoc, format.mime, name)
            ?: error("在这个文件夹里建不出文件（可能没有写入权限）")
        resolver.openOutputStream(uri)?.use { out ->
            out.write(body.toByteArray(Charsets.UTF_8))
            out.flush()
        } ?: error("写入被系统拒绝")
    }

    /**
     * 组装一份备份的 JSON 文本。**只读**，不改任何数据。
     *
     * ⚠️ 记忆是**按角色逐个取**的（`allOfPersona` 强制带 personaId）——
     * 不因为"这是导出"就在隔离纪律上开破口，理由见 `MemoryDao.allOfPersona` 的注释。
     * 单个角色取失败不阻断整份备份：少一段记忆，好过整份导不出来。
     */
    private suspend fun buildBackupJson(scope: BackupScope = BackupScope.ALL): String {
        val userId = MemoryEntity.LOCAL_USER_ID
        // ⚠️ 按 scope 决定**要不要去查** —— 导出"模型配置"时不该顺手把全部记忆读一遍
        //（记忆是多角色逐个查，在角色多、记忆多的时候并不便宜）。
        val memories = if (scope.includesMemory) {
            _personas.value.flatMap { p ->
                runCatching { db.memoryDao().allOfPersona(userId, p.id) }.getOrElse { emptyList() }
            }
        } else {
            emptyList()
        }
        return Backup.encode(
            BackupSnapshot(
                appVersion = BuildConfig.VERSION_NAME,
                exportedAt = System.currentTimeMillis(),
                personas = if (scope.includesPersona) _personas.value else emptyList(),
                sessions = if (scope.includesChat) _sessions.value else emptyList(),
                memories = memories,
                settings = _settings.value,
                profile = _profile.value,
                scope = scope,
            ),
        )
    }

    /* ─────────────── 数据库快照与自动备份（v0.53.0） ─────────────── */

    /**
     * **机会式**自动备份 —— App 启动 / 回到前台时问一次"现在该不该备份"。
     *
     * ⚠️ 为什么是"机会式"而不是定时任务：本项目**没有 WorkManager 依赖**，且不打算引
     *（见 `MemoryExtractionScheduler` 的类注释：项目一贯不引文档示例里的重依赖）。
     * 代价是**App 长期不打开就不会备份** —— 这句话必须写在界面上，不能让它看起来像闹钟。
     *
     * ⚠️ 只有**真的生成了文件**才写 `lastAutoBackupAt`：失败也记时间戳的话，
     * 接下来一整个间隔（默认 24 小时）都不会再试一次。
     */
    fun runAutoBackupIfDue() {
        if (!store.loadAutoBackupEnabled()) return
        val now = System.currentTimeMillis()
        // v0.55.0：不再看时段，只看"距上次够久了没有"（开关 = 每天一份）
        val due = AutoBackupPolicy.shouldRun(
            now = now,
            lastAt = store.loadLastAutoBackupAt(),
        )
        if (!due) return

        viewModelScope.launch {
            val made = withContext(Dispatchers.IO) {
                runCatchingForTask { writeAutoBackupFile(now) }.getOrNull()
            }
            if (made != null) store.saveLastAutoBackupAt(now)
        }
    }

    /* ─────────────── 「Ta 主动来找我」取件（v0.61.40） ─────────────── */

    /**
     * **机会式**取件 —— App 启动 / 回到前台时问一次"有没有 Ta 想说的话"。
     *
     * ## 它做什么
     * 逐个人设（开了 `proactiveEnabled` 且 `xinchaoEnabled`）调
     * `GET /xinchao/personas/{id}/pending?since=<上次看到的最新时间>`；
     * 有新消息 → 弹本地通知（[ProactiveNotifier]）；任何失败静默
     *（与 telemetry / xinchao 上报同一纪律）。
     *
     * ## ⚠️ 它只是"去信箱看看"（设计红线）
     * 何时说、说什么由心潮的情绪状态机决定；这里**没有**、也不许有
     * "到点提醒 Ta 说话"的逻辑。[ProactiveFetchPolicy] 的 30 分钟守卫只防
     * "用户频繁切前后台时反复打点"，不是唤醒源。
     *
     * ## 首次取件的语义（v0.61.48 改过）
     * 从没取过（已读水位为空）→ **只弹最新一条**：原实现一条都不弹（当时的理由是不拿
     * 历史消息轰炸），但那会让"开了开关之后心潮没再产生新消息"的用户**永远零回执** ——
     * 看起来完全像功能坏了。现在既给一个"我看过了"的信号，又不把十条历史一起炸出来
     *（判据在 [ProactiveFetchReceipt]，纯函数可测）。
     */
    /**
     * 上报一次**在场时间**（v0.61.52）—— App 启动 / 回到前台时打。
     *
     * 为什么需要它：用户开着 App 但**没说话**时，心潮只看得到 `lastConversationAt` 很旧，
     * 会把这段静默误判成"长期不在"，从而触发「Ta 主动来找我」。心跳把这个判断修正回来。
     * ⚠️ 它**不带任何正文**（body 是空对象）—— 这与 [reportXinchaoTurn] 是本质区别；
     *    失败静默；只对**云端记忆**的人设发（本地模式没接心潮）。
     */
    fun reportHeartbeatIfCloud() {
        val base = ServerConfig.BASE_URL
        val token = store.loadAuth().token
        if (base.isBlank() || token.isBlank()) return
        // ⚠️ 2026-10-06：判据 = 「接入 Ta 的状态」开关（用户要求该项独立开关）。
        //    沿革：v0.61.48 用 isCloudMemory（三项绑死）—— 现在可单独关，
        //    关了就不该再上报心跳（否则用户以为关了，云端还在收）。
        val targets = _personas.value.filter { it.isCloudMemory && it.xinchaoEnabled }
        if (targets.isEmpty()) return
        viewModelScope.launch {
            for (p in targets) {
                runCatching { XinchaoReport.heartbeat(base, token, p.id) }
            }
        }
    }

    fun runProactiveFetchIfDue() {
        viewModelScope.launch {
            // 取件逻辑已抽到 [ProactiveFetcher]（v0.61.49）—— 常驻前台服务走的是同一份，
            // 保证"节流守卫 + 已读水位"只有一处口径（否则两条链会各记各的）。
            ProactiveFetcher.fetchOnce(getApplication())
        }
    }

    /**
     * 组装并写一份**全量备份**文件。**在 IO 线程调用**。
     *
     * ⚠️ 复用 [buildBackupJson] —— 自动备份与「手动导出全部数据」产出的是
     *    **同一种文件**。这是刻意的：两者都能导入回来，用户不必再去分辨
     *    "哪个备份是能恢复的"。v0.53.0 那个数据库 zip 正是因为导不回来而被换掉。
     */
    private suspend fun writeAutoBackupFile(at: Long): File? {
        val ctx = getApplication<Application>()
        val json = buildBackupJson(BackupScope.ALL)
        val f = AutoBackupStore.create(ctx, json, at) ?: return null
        AutoBackupStore.enforceLimit(ctx, store.loadAutoBackupMaxFiles())
        return f
    }

    /** 手动立刻做一次**全量备份**（内容与自动备份完全一致）。 */
    fun backupNow(onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            _exportState.value = ExportState.Running(0, 1)
            val made = withContext(Dispatchers.IO) {
                runCatchingForTask { writeAutoBackupFile(System.currentTimeMillis()) }.getOrNull()
            }
            _exportState.value = if (made != null) {
                ExportState.Done(1, "软件内的备份列表")
            } else {
                ExportState.Failed("备份写不进去（可能是空间不够）")
            }
            onDone(made != null)
        }
    }

    /** 现有备份文件（新 → 旧）。 */
    suspend fun snapshots(): List<File> =
        withContext(Dispatchers.IO) { AutoBackupStore.list(getApplication()) }

    /** 备份统计：`份数 to 总字节`。 */
    suspend fun snapshotStats(): Pair<Int, Long> = withContext(Dispatchers.IO) {
        val l = AutoBackupStore.list(getApplication())
        l.size to l.sumOf { it.length() }
    }

    /** 在软件内删掉一份备份（用户要求"所有备份都支持在软件内删除"）。 */
    fun deleteBackup(file: File, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            onDone(withContext(Dispatchers.IO) { AutoBackupStore.delete(file) })
        }
    }

    /**
     * 与 `runCatching` 的唯一区别：**不吞 `CancellationException`**。
     *
     * ⚠️ 为什么导出/备份必须用它：`kotlin.runCatching` 会把**所有** Throwable 都收进
     * `Result.failure` —— 包括协程取消时抛的那个 `CancellationException`。
     * 于是"用户退出页面 / 进程被杀 → 任务被取消"这件事会**被静默吞掉**：
     * 协程不再向上传播取消信号（结构化并发失效），调用方还会接着把状态写成
     * "完成"或"失败" —— 而它其实只是被取消了（导出的活干到一半）。
     *
     * 取消**不是失败**，它必须继续往上抛（见 `send()` 里处理 `CancellationException`
     * 的那段：那里也是同样的纪律 —— 收尾之后 `throw e`）。
     */
    private inline fun <T> runCatchingForTask(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }

    /* ─────────────── 看板（v0.54.0）─────────────── */

    /**
     * 记忆条数；**查不出来时返回 `-1`**（而不是 0）。
     *
     * ⚠️ 为什么区分 -1 与 0：`-1` = "还没查出来/查不动"，`0` = "她确实一条都没记住"。
     *    把前者显示成后者，是把一个失败说成了一件事实（"我的记忆怎么没了"）。
     */
    suspend fun memoryCountOrNegative(): Int = withContext(Dispatchers.IO) {
        runCatching { db.memoryDao().count() }.getOrDefault(-1)
    }

    /**
     * 某个人设**最近的 N 条记忆**（v0.56.0，人设详情页的快照用）。
     *
     * ⚠️ 用的是 `MemoryDao.allOfPersona` —— 它按 `userId + personaId` 查、
     *    **不带 `sessionId`**，正是"这个角色的全部记忆"那个口径
     *   （记忆仍属某一段会话，但查询维度上是按角色的 —— 见该 DAO 方法的注释）。
     *    所以这里**不需要**先列会话再逐个查。
     *
     * ⚠️ 查不出来就返回空列表（详情页那一块显示"还没有记忆"）——
     *    一条统计读不出来不该让整页打不开。
     */
    suspend fun recentMemoriesOf(personaId: String, limit: Int): List<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                db.memoryDao().allOfPersona(MemoryEntity.LOCAL_USER_ID, personaId)
                    .sortedByDescending { it.createdAt }
                    .take(limit)
                    .map { it.content }
            }.getOrDefault(emptyList())
        }
    /**
     * 看板的**全部指标**（一次算完）。
     *
     * ⚠️ 是 suspend：记忆只能查库（它们不像会话/人设那样有常驻状态流），
     *    其余几样（会话、人设、滚动统计）都在内存或偏好里，随手可取。
     *
     * ⚠️ 记忆查不出来时**降级为空**而不是抛：一个统计缺一块，不该让整页打不开。
     */
    suspend fun boardSummary(): BoardSummary = withContext(Dispatchers.IO) {
        // ⚠️ **整个聚合都放 IO**（v0.54.0 修正）：它要扫十几遍全部消息、还带两处全量排序，
        //    几千条消息时在主线程跑会顶掉好几帧 —— 而看板没有任何"必须立刻显示"的理由。
        //    早先只有记忆查询在 IO 里、聚合主体留在 Main，那是个漏掉的地方。
        BoardMath.build(
            sessions = _sessions.value,
            personas = _personas.value,
            memories = runCatching {
                _personas.value.flatMap { p ->
                    // ⚠️ 记忆是**按角色逐个查**的（`allOfPersona` 强制带 personaId）——
                    //    不因为"这是统计"就在隔离纪律上开破口。
                    db.memoryDao().allOfPersona(MemoryEntity.LOCAL_USER_ID, p.id)
                }
            }.getOrDefault(emptyList()),
            turns = BoardStats.decodeTurns(store.loadBoardTurns()),
            modelUsage = BoardStats.decodeModels(store.loadModelUsage()).values.toList(),
            now = System.currentTimeMillis(),
        )
    }

    /* ─────────── 自动备份的设置：给界面的读写（v0.53.0） ─────────── */

    /** 一次性把自动备份的设置全读出来（界面拿到的必须是同一时刻的一组值）。 */
    fun autoBackupSettings(): AutoBackupSettings = AutoBackupSettings(
        enabled = store.loadAutoBackupEnabled(),
        maxFiles = store.loadAutoBackupMaxFiles(),
        lastAt = store.loadLastAutoBackupAt(),
    )

    fun setAutoBackupEnabled(v: Boolean) = store.saveAutoBackupEnabled(v)

    fun setAutoBackupMaxFiles(v: Int) = store.saveAutoBackupMaxFiles(v)

    /**
     * **数据概览**（备份页顶部）：现在本机有多少东西。
     *
     * ⚠️ 只报"有多少"，不报"值多少钱" —— 后者不是本地能算的（同缓存诊断页的纪律）。
     * ⚠️ 是 suspend：记忆条数只能查库（`memoryDao().count()`），不像会话与人设有常驻状态。
     */
    suspend fun dataOverview(): DataOverview = DataOverview(
        sessions = _sessions.value.size,
        messages = _sessions.value.sumOf { it.messages.size },
        memories = withContext(Dispatchers.IO) {
            runCatching { db.memoryDao().count() }.getOrDefault(0)
        },
        personas = _personas.value.size,
    )

    /**
     * 从用户选定的备份文件**恢复**数据。
     *
     * ## 顺序是刻意的（每一步都在为下一步兜底）
     * 1. **读文件** —— 失败就什么都没动；
     * 2. **解析**（`Backup.decode`，**严格拒绝**：不是备份就到此为止）；
     * 3. **整理**（`Backup.sanitize`：丢掉空 id 的会话、丢掉指向不存在会话的记忆，
     *    并**如实汇报丢了几条** —— 不悄悄丢）；
     * 4. **落撤销点** —— 把当前数据导出成**同一格式**存进私有目录。**这一步成功才继续**；
     * 5. **写库** —— Room 在一个事务里；DataStore 在其后。
     *
     * ## ⚠️ 它不是原子的（跨两个存储，物理上做不到）
     * 会话/消息/记忆在 Room（有事务 ✅），人设/设置/资料在 DataStore（没有事务 ❌）。
     * 若 Room 成功而 DataStore 失败，会停在"对话是新的、人设是旧的"这种中间态。
     * 所以**第 4 步的撤销点不是可选的** —— 它是这种情况唯一的退路。界面上也写了这句话。
     *
     * ## ⚠️ 它不覆盖 API Key
     * 备份里本来就没有密钥（见 `Backup.encode`），这里再显式保留本机现值 ——
     * 恢复一份别人的备份不该把本机的 Key 冲掉。
     */
    fun importBackupFrom(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                val text = withContext(Dispatchers.IO) { readText(uri) }
                val snapshot = when (val d = Backup.decode(text)) {
                    is BackupDecode.Ok -> d.snapshot
                    is BackupDecode.Bad -> error(d.reason)
                }
                val clean = Backup.sanitize(snapshot)

                // 撤销点：**同一套导出格式**，所以用户之后能把它导回来
                val undo = buildBackupJson()
                withContext(Dispatchers.IO) { writeAutoUndo(undo) }

                applySnapshot(clean.snapshot)
                clean
            }
                .onSuccess { clean ->
                    _notice.value = buildString {
                        append("备份已导入（原数据已存为自动撤销点）")
                        if (clean.droppedAnything) {
                            append("；有 ${clean.droppedSessions} 段对话、")
                            append("${clean.droppedMemories} 条记忆内容不完整，已跳过")
                        }
                    }
                }
                .onFailure { _error.value = "导入失败：${IoErrors.explain(it)}" }
        }
    }

    /** 读一个由用户选定的文本文件。 */
    private fun readText(uri: Uri): String {
        val resolver = getApplication<Application>().contentResolver
        return resolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("这个文件读不出来")
    }

    /**
     * 把撤销点写进应用私有目录。
     *
     * 位置：`filesDir/backup/auto-undo.json`。**只留一份**，每次导入前覆盖 ——
     * 攒一堆撤销点只会让用户不知道该用哪一个。
     */
    private fun writeAutoUndo(text: String) {
        val dir = File(getApplication<Application>().filesDir, "backup")
        if (!dir.exists() && !dir.mkdirs()) error("建不了撤销点目录")
        File(dir, "auto-undo.json").writeText(text, Charsets.UTF_8)
    }

    /**
     * 把一份快照写进两个存储。
     *
     * ⚠️ **只接受「全部数据」的备份**（v0.53.0 守卫）。
     *
     * 分项文件里，范围外的段**根本没被写进去**（见 `Backup.encode`：非 ALL 不 put 对应段），
     * 读回来是空的。而下面这段逻辑是**无条件覆盖 + `replaceAll`（先 DELETE 全部再插）**——
     * 拿一份"只导了聊天记录"的文件走这条路，会**把人设、设置、记忆一起清空**。
     * 那是这个功能最不能犯的错：用户以为自己只是在"恢复聊天记录"。
     *
     * 所以这里**明确拒绝**，而不是"尽力而为"：分项导入需要按段逐个应用，
     * 那是另一套写库逻辑（放在分项入口接线时一起做）。在那之前，
     * 宁可让用户看到一句"这份备份不能这样导入"，也不能悄悄删掉他的数据。
     */
    private suspend fun applySnapshot(s: BackupSnapshot) {
        // ⚠️ v0.61.21：**分项导入补完**。
        //    原来这里对任何非 ALL 的备份直接 error（"需要按分项方式导入"），
        //    而那个入口根本不存在 —— 于是 v0.53.0 做的"分项导入导出"只有半边架子：
        //    导出端写得出分项文件，导入端一律拒收。现在按 scope 分派。
        //
        // ⚠️ 判据用的是 `BackupScope` 自己的 includesXxx（纯函数，有单测）——
        //    不要在别处另写一套 `when (scope)`，那正是这类"两处口径"出错的来源。

        // ① 「配置与人设」那一档：人设 / 用户人设 / 设置 / 我 的资料
        if (s.scope.includesPersona) {
            _personas.value = s.personas
            store.savePersonas(s.personas)

            // ⚠️ v0.61.45：用户人设与 personas **必须成对恢复** —— 漏了它，导入后
            //    所有 `userPersonaId` 都指向不存在的 id（resolve 静默给 null，
            //    前缀里的用户人设正文凭空消失且无提示）。
            //    （L2 对抗审查抓出的缺陷；本项目"漏搬"已第 N 次 —— 动这段务必成对检查。）
            _userPersonas.value = s.userPersonas
            store.saveUserPersonas(s.userPersonas)

            // ⚠️ 保留本机现有的 API Key —— 备份里没有它（见 Backup.encode）
            val next = s.settings.copy(apiKey = _settings.value.apiKey)
            _settings.value = next
            store.saveSettings(next)

            _profile.value = s.profile
            store.saveProfile(s.profile)
        }

        // ② 「聊天记录」那一档：会话 / 消息 / 服务商分桶（一个事务）
        if (s.scope.includesChat) repo.replaceChat(s.sessions)

        // ③ 「记忆库」那一档（一个事务）
        if (s.scope.includesMemory) repo.replaceMemories(s.memories)
    }

    /* ─────────────── 表情包（用户 2026-09-28） ─────────────── */

    /**
     * 表情包库的变化流 —— 管理界面直接 collect。
     *
     * ⚠️ 这些方法**只碰 `emoji_packs` 表**，一行 `messages` 都不动：
     * 图片是纯本地素材，进不了请求前缀（见 `EmojiPackEntity` 的类注释）。
     */
    fun emojiPacks(): Flow<List<EmojiPackEntity>> = db.emojiDao().observeAll()

    /**
     * 加一张表情包。
     *
     * ⚠️ 主键是**文件路径**（`ImageStore` 生成的文件名带时间戳，所以每条路径唯一）——
     * 同一张图重复加会走 `@Upsert` 幂等覆盖，而不是插出两行。
     */
    fun addEmojiPack(path: String, category: String, personaId: String? = null) {
        viewModelScope.launch {
            runCatching {
                db.emojiDao().upsert(
                    EmojiPackEntity(
                        id = path,
                        category = category,
                        createdAt = System.currentTimeMillis(),
                        // 归属（方案 C）：null = 全局（所有角色都能用），非 null = 只给她
                        personaId = personaId,
                    ),
                )
            }
        }
    }

    fun deleteEmojiPack(path: String) {
        viewModelScope.launch {
            runCatching {
                db.emojiDao().delete(path)
                // 表里的行删了，**文件也一起删** —— 否则私有目录会攒下一堆没人引用的图
                ImageStore.delete(path)
            }
        }
    }

    /**
     * 「**哪条回复**配了哪张图」—— `那条消息的 createdAt → 图片路径`。
     *
     * ## ⚠️ 为什么它是一份**内存**状态，而不是写进 `messages`
     * 因为**图片一旦进了历史，它之后的所有内容都无法命中缓存**
     *（`ChatMessage.images` 的注释里写着这条）。而且上游明确限制
     * 「图片只能出现在 user 消息里」—— AI 发的图根本不该、也不能回传给模型。
     *
     * 所以它只活在内存里，渲染时按"这条回复配了图就画在她下面"画出来。
     * **重启后消失是刻意的**，不是 bug —— 它是装饰，不是历史。
     *
     * ## ⚠️ 键为什么是 `createdAt` 而不是"最后一条"（v0.39.1 修的 bug）
     * 上一版把图绑在**最后一条消息**上，于是：她回复 → 图正常显示；
     * **用户一发新消息**，末尾就变成他自己的消息，"最后一条是 assistant"不再成立
     * → **图当场消失**。而且每轮决策覆盖同一个键，上一次的图也跟着没了。
     * 绑到"那条消息自己的 `createdAt`"之后，图就跟着那条回复走，与后来发多少条无关。
     */
    private val _emojiByMessage = MutableStateFlow<Map<Long, String>>(emptyMap())
    val emojiByMessage: StateFlow<Map<Long, String>> = _emojiByMessage.asStateFlow()

    /** 内存里最多留几张图的记录 —— 它是装饰不是数据，不该无限长。 */
    private val emojiHistoryLimit = 50

    /**
     * 决定这一轮要不要配图（在回复落库后调用）。
     *
     * 三层判断依次是：开关 → 概率（人设可覆盖全局）→ 该分类里有没有图。
     * 真正的决策全在 [EmojiPicker]（纯函数、有单测），这里只负责取数据与掷骰子。
     */
    private fun maybeAttachEmoji(sessionId: String, reply: String, at: Long) {
        viewModelScope.launch {
            runCatching {
                val s = _settings.value
                if (!s.emojiEnabled) return@runCatching

                val session = _sessions.value.firstOrNull { it.id == sessionId } ?: return@runCatching
                val persona = personaById(session.personaId)

                val all = db.emojiDao().all()
                if (all.isEmpty()) return@runCatching

                // **可用性检测**（用户 2026-09-28 补充的第四条）：
                // 库里存的是路径，而文件可能已经不在了（清过应用数据 / 手动删了 /
                // 那一次保存其实没成功）。不筛的话会抽到画不出来的图 ——
                // 用户看到的是一个**空的图片气泡**，还不知道为什么。
                // ⚠️ 文件 IO 放 IO 上下文；纯决策仍留在 EmojiPicker（可单测）。
                //
                // 同时按**归属**分成两层（方案 C）：她专属的图优先，缺了回退到全局。
                // 分组要放 IO 上下文里 —— 它的结果依赖上面那批 exists 探测。
                val (owned, global) = withContext(Dispatchers.IO) {
                    EmojiAvailability.availableByOwner(all, persona?.id) { File(it).exists() }
                }
                if (owned.isEmpty() && global.isEmpty()) return@runCatching

                val pick = EmojiPicker.pick(
                    reply = reply,
                    owned = owned,
                    global = global,
                    // 概率：人设覆盖优先，否则全局默认（用户定的混合模式）
                    chance = EmojiPicker.effectiveChance(s.emojiChance, persona?.emojiChanceOverride),
                    roll = Random.nextFloat(),
                    index = Random.nextInt(1_000_000),
                ) ?: return@runCatching

                // ⚠️ 记在"那条消息的 createdAt"上 —— 绑"最后一条"的话，用户一发新消息图就没了
                // ⚠️ **图必须由这里自己写进消息**（v0.45.6 修回归）。
                // 原来是 `commitAssistant` 在调用完本函数后**同步**去读 `_emojiByMessage` ——
                // 而本函数是 `launch` 出去的异步任务，那一刻它还没跑完，读到的永远是 null。
                // 于是每条消息的 `emojiPath` 都是空 → 渲染侧又只认这个字段 → **全局和专属的图全都不显示**。
                // 现在改为：谁拿到结果谁负责写回（只有这里知道 pick 的最终结果）。
                patchSession(sessionId) { cur ->
                    cur.copy(
                        messages = cur.messages.map { m ->
                            if (m.createdAt == at && m.emojiPath == null) m.copy(emojiPath = pick.path) else m
                        },
                    )
                }
                _emojiByMessage.value = (_emojiByMessage.value + (at to pick.path))
                    .let { m ->
                        if (m.size <= emojiHistoryLimit) {
                            m
                        } else {
                            // 超上限就把最早的那批丢掉（Map 保序，所以 drop 的是插入最早的）
                            m.entries.drop(m.size - emojiHistoryLimit)
                                .associate { it.key to it.value }
                        }
                    }
            }
        }
    }

    /* ─────────────── 会话 ─────────────── */

    private val _sessions = MutableStateFlow<List<Session>>(emptyList())
    val sessions: StateFlow<List<Session>> = _sessions.asStateFlow()

    private val _activeSessionId = MutableStateFlow("")
    val activeSessionId: StateFlow<String> = _activeSessionId.asStateFlow()

    /**
     * **已读水位**（v0.61.57，QQ 式未读提示）。
     *
     * `sessionId → 用户最后一次"看着这段对话"的时刻`。列表行据此判断
     * `session.updatedAt > lastReadAt` → 显示未读小红点。
     *
     * ## ⚠️ 为什么是内存态、不进 Room
     * 未读是**瞬时 UI 状态**，不是用户数据：
     * · 进 Room 要加一列 + 一次迁移 —— 而迁移是本项目**唯一会损坏聊天记录**的操作
     *   （见 `AppDatabase` 的迁移注释），为一个小红点付这个代价不划算；
     * · 进程重启后"全部算已读"是**保守且正确**的降级：用户刚打开 App，
     *   本来也没法区分"这些是刚才没看的"还是"昨天就没看的" ——
     *   宁可少提示，也不要凭空冒出一堆红点（那会变成噪音）。
     *
     * ⚠️ 它**只在内存里**，所以**不能**用它做"消息是否已送达"之类的持久判断。
     */
    private val lastReadAt = MutableStateFlow<Map<String, Long>>(emptyMap())

    /** 已读水位快照（列表行读它判断未读）。 */
    val readWatermarks: StateFlow<Map<String, Long>> = lastReadAt.asStateFlow()

    /**
     * 某段会话**有没有未读**（v0.61.57）。
     *
     * 判据：`updatedAt > 已读水位`。没记录过水位（刚启动 / 从没进过）→ **不算未读** ——
     * 与上面"进程重启后全部算已读"同一条保守纪律。
     */
    fun hasUnread(session: Session): Boolean {
        val mark = lastReadAt.value[session.id] ?: return false
        return session.updatedAt > mark
    }

    /** 把某段会话标记为**已读**（进对话时调）。 */
    fun markRead(sessionId: String) {
        if (sessionId.isBlank()) return
        val now = System.currentTimeMillis()
        lastReadAt.value = lastReadAt.value + (sessionId to now)
    }

    fun activeSession(): Session? = _sessions.value.firstOrNull { it.id == _activeSessionId.value }

    fun newSession(personaId: String) {
        val persona = personaById(personaId) ?: return
        val now = System.currentTimeMillis()
        val session = Session(
            id = UUID.randomUUID().toString().take(8),
            personaId = personaId,
            // ⚠️ v0.61.21：「Ta 怎么称呼你」已从编辑页移除，新建人设的这个值为空 ——
            //    照原样拼会得到一个尾巴挂着的标题「初雪 · 」。空就不拼那一截。
            //    老用户填过的照旧拼（值还在，只是不再让人填）。
            title = listOf(persona.displayName, persona.userNickname.trim())
                .filter { it.isNotBlank() }
                .joinToString(" · "),
            messages = buildList {
                persona.greeting?.takeIf { it.isNotBlank() }?.let {
                    add(ChatMessage("assistant", PromptEngine.buildGreeting(it, persona)))
                }
            },
            createdAt = now,
            updatedAt = now,
            // 思考设置**从全局复制一份** —— 之后各会话独立可改（对话设置页）。
            // 全局设置从此只是"新会话的初值"，而不是每条会话的实时开关。
            thinkingEnabled = _settings.value.thinkingEnabled,
            reasoningEffort = _settings.value.reasoningEffort,
        )
        // 列表由 Room 的 Flow 回推，这里不手工改 _sessions
        _activeSessionId.value = session.id
        // v0.61.57：新建的会话天然已读（用户就在里面）
        markRead(session.id)
        viewModelScope.launch { repo.saveSession(session) }
    }

    fun switchSession(id: String) {
        _activeSessionId.value = id
        // v0.61.57：进对话即已读 —— 未读小红点随之消失
        markRead(id)
        _pendingImages.value = emptyList()
        // 失败气泡是**这一段对话**的反馈（H4）—— 换一段就不该跟着走：
        // 它本来就只活在界面层，跟着串场只会让人以为新会话也出错了
        _errorLine.value = null
        // 同理：「压缩完成」提示与「该压缩了」都是**上一段对话**的事（v0.48.0）
        _compressNotice.value = null
        _compressSuggested.value = false
    }

    fun deleteSession(id: String) {
        // 会话与它的消息一起删（messages 表的外键是 onDelete = CASCADE）。
        // 不必手工重算 activeSessionId —— init 里的 collect 会处理。
        viewModelScope.launch { repo.deleteSession(id) }
    }

    /**
     * 改会话的某个设置（对话设置页用：免打扰 / 置顶 / 思考开关 / 思考强度 / 背景）。
     *
     * ⚠️ **不碰历史、不碰冻结前缀**，所以这一组操作**不触碰缓存**。
     * 唯一要留意的是思考开关与强度：它们进的是请求 payload（`thinking` /
     * `reasoning_effort` 参数），**不改 messages 的字节序列** —— 缓存认的是
     * messages 前缀，因此安全。
     */
    fun updateSession(id: String, transform: (Session) -> Session) {
        patchSession(id) { transform(it).copy(updatedAt = System.currentTimeMillis()) }
    }

    /**
     * 切换这一段对话用的**分组 / 模型**（v0.51.0）—— 聊天输入框「+」→「选择模型」的落点。
     *
     * ## ⚠️ 生成期间一律拒绝，返回 false
     * 用户明确要求：「锁定一个值 —— AI 在输出的时候不能切换模型」。
     *
     * 请求体其实**在发送那一刻就定死了**（`send()` 里 `requestSettings` /
     * `providerProfile` 都是一次快照，之后改会话字段影响不到已经在飞的那一轮），
     * 所以这里挡的不是"会写坏请求"，而是**一个会骗人的界面**：
     * 浮层的说明是"这段对话之后用它"，而用户正在看着一条正在生成的回复 ——
     * 他会以为**这一条**也跟着换了。它不会。
     * 与其让人对着一个当下不可能生效的开关反复点，不如直接挡住并说清原因。
     *
     * ⚠️ 空串的语义是「恢复为跟随全局」→ 写回 `null`。写成空串会让
     * `Session.model` 出现第三种状态，而 `resolveFor` 的兜底判据只认 `null`。
     *
     * @return true = 已生效；false = 正在生成，**没有改动任何状态**
     */
    fun setSessionProvider(sessionId: String, groupId: String, model: String): Boolean {
        if (_busy.value) return false
        updateSession(sessionId) {
            it.copy(
                providerGroupId = groupId.takeIf { g -> g.isNotBlank() },
                model = model.takeIf { m -> m.isNotBlank() },
            )
        }
        return true
    }

    /**
     * 删除**某一轮问答**（v0.61.14，用户要求：长按**任意**一枚她的气泡都能删）。
     *
     * 删三样东西（用户原话）：「用户发送的消息」「分段气泡输出的全部消息」
     *（= 她这一条回复；一条回复拆出的多枚气泡在数据上本就是**同一条** assistant 消息）、
     * 「这条消息的自动记忆（如果有）」。
     *
     * ## ⚠️ 记忆清理的守卫
     * 只认**紧邻在前那条用户请求**的时间戳；取不到（加时间戳之前的老消息 createdAt=0）
     * 时 SQL 里的 `:since > 0` 会让它变成"什么都不做"—— 宁可少清一条记忆，
     * 也不能误删整个会话的记忆（那是审查捞出来的真缺陷，见 MemoryDao 的 KDoc）。
     *
     * ## ⚠️ 缓存代价（界面必须告知）
     * 删**中间**的某一轮会让它之后的前缀全部失配（下一次请求按未命中计费）；
     * 删最后一轮不动前缀。之前正是因为这个代价只开放了"最新一轮"，现在按用户要求全开放，
     * 所以**菜单里的告知必须写回来**。
     */
    fun deleteExchangeAt(sessionId: String, index: Int) {
        val session = _sessions.value.firstOrNull { it.id == sessionId } ?: return
        // ⚠️ 生成中不动历史（改历史会让正在跑的这轮的前缀失配）。**但要说一句** ——
        //    静默 return 会让用户以为"点了没反应"（本项目对静默失效有专门的纪律）。
        if (_busy.value) {
            _error.value = "Ta 还在说，等这一轮说完再删"
            return
        }
        val target = session.messages.getOrNull(index) ?: return
        if (target.role != "assistant") {
            _error.value = "只能删除 Ta 的回复"
            return
        }
        // 这一轮的起点：紧邻在前那条用户请求（记忆按它之后创建）
        val since = session.messages.getOrNull(index - 1)
            ?.takeIf { it.role == "user" }?.createdAt ?: 0L
        patchSession(sessionId) { cur ->
            cur.copy(
                messages = MessageEdits.deleteExchangeAt(cur.messages, index),
                updatedAt = System.currentTimeMillis(),
            )
        }
        viewModelScope.launch {
            // ⚠️ 记忆清理是**副作用**，失败绝不能让消息删除回滚：
            //    用户要删的是消息；记忆没清掉只是"多留了一条"，比"删了消息却报错"轻得多。
            runCatching {
                db.memoryDao().deleteAutoSince(
                    userId = MemoryEntity.LOCAL_USER_ID,
                    sessionId = sessionId,
                    source = MemoryExtractionScheduler.SOURCE_AUTO,
                    since = since,
                )
            }
        }
    }

    /**
     * 重新生成：去掉尾部她的回复，用同一条用户输入再发一次。
     *
     * ⚠️ 同样改写历史（缓存代价见 [MessageEdits]）。
     *
     * **必须剥掉附录再重发**：历史里的用户消息带着当轮注入的 `<appendix>`，
     * 原样重发会让它被当成用户说的话、再注入一次 —— 内容重复，而且白花钱。
     */
    fun regenerate() {
        val session = activeSession() ?: return
        if (_busy.value) return
        if (!MessageEdits.canRegenerate(session.messages)) {
            _error.value = "没有可以重新生成的回复"
            return
        }

        // ⚠️ 消息流的规格收在 [MessageEdits.regeneratePlan]（纯函数 + 有测试，v0.61.15）：
        //    「时间线上只留**一条**她的回复」「旧回复的内容交给新回复继承（供左右按钮查看）」
        //    「那条用户请求也去掉 —— 由 send 重新追加，避免出现两条一模一样的」。
        //    用户报过两次这个形态问题，所以它现在有测试钉着：改之前先看 MessageEditsTest。
        val plan = MessageEdits.regeneratePlan(session.messages)
        if (plan == null) {
            _error.value = "这段对话里没有可以重发的消息"
            return
        }

        // ⚠️ v0.61.21：**先验"这一轮真发得出去"，再动历史**。
        //
        // 下面已经把这最后一轮问答裁掉了（`plan.trimmed`），而 `send()` 里还有自己的
        // early return（没密钥 / 没会话）。那些路径下 send 直接返回、什么都不发 ——
        // 用户会**白丢一轮对话**且没有任何新回复补回来；更糟的是 `pendingSuperseded`
        // 没被消费，会在**下一轮正常发送**时被 `commitAssistant` 认领，
        // 给那条不相干的回复挂上这一轮的旧版本。
        //
        // 这里补上 send() 剩下的那道守卫（没密钥）。加上上面已有的"有会话"与"不忙"，
        // 三条合起来就覆盖了 send() 开头全部的 early return ——
        // 也就是说：**走到下面 patchSession 的时候，这一轮必定发得出去**。
        // ⚠️ 改 send() 开头的守卫时，记得回来同步这里。
        if (effectiveSettings(session).apiKey.isBlank()) {
            _error.value = "请先到「设置 → 连接设置」里填好密钥"
            return
        }

        pendingSuperseded = plan.superseded

        patchSession(session.id) { cur ->
            cur.copy(messages = plan.trimmed, updatedAt = System.currentTimeMillis())
        }
        send(TranscriptText.stripAppendix(plan.userText))
    }

    /**
     * 改一个会话并落盘。
     *
     * 先做**本地乐观更新**（流式时每一块都要立刻上屏，不能等数据库往返），
     * 再异步写 Room。Room 的 Flow 稍后会把权威值回推，两者内容一致。
     */
    private fun patchSession(id: String, transform: (Session) -> Session) {
        val current = _sessions.value.firstOrNull { it.id == id } ?: return
        val next = transform(current)
        _sessions.value = _sessions.value.map { if (it.id == id) next else it }
        viewModelScope.launch { repo.saveSession(next) }
    }

    /* ─────────────── 待发图片 ─────────────── */

    private val _pendingImages = MutableStateFlow<List<String>>(emptyList())
    val pendingImages: StateFlow<List<String>> = _pendingImages.asStateFlow()

    private val _imageBusy = MutableStateFlow(false)
    val imageBusy: StateFlow<Boolean> = _imageBusy.asStateFlow()

    /**
     * 选了若干张图（多选）：逐张 读取 → 缩放 → 压缩 → base64 data URL。
     *
     * 为什么要自己先缩：
     * 官方会自动把图缩到约 1300×1300，但我们**先缩更省**——
     * 少传几 MB 的 base64、少等几秒，而视觉理解的效果没有区别。
     * 压到长边 ≤1280 且 JPEG 质量 85，单图通常不到 300 KB。
     */
    fun addImages(context: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _imageBusy.value = true
            _error.value = null
            try {
                // 先看还剩几个位子（用户定的上限 9 张）—— 满了一个都不读，
                // 免得白解码一堆几十 MB 的位图再全部丢掉
                val room = ImagePolicy.MAX_IMAGES - _pendingImages.value.size
                if (room <= 0) {
                    _error.value = "最多只能发 ${ImagePolicy.MAX_IMAGES} 张图，先去掉几张再加。"
                    return@launch
                }
                val picked = uris.take(room)
                // 逐张 读 → 缩放 → 压 base64。解码是几十 MB 级的开销，一律放 IO。
                val encoded = withContext(Dispatchers.IO) {
                    picked.mapNotNull { encodeImage(context, it) }
                }
                if (encoded.isEmpty()) {
                    _error.value = if (picked.size == 1) {
                        "这张图读不出来，换一张试试"
                    } else {
                        "这几张图都读不出来，换几张试试"
                    }
                    return@launch
                }
                val next = _pendingImages.value + encoded
                when (val check = ImagePolicy.check("user", next)) {
                    is ImagePolicy.Check.Ok -> _pendingImages.value = next
                    is ImagePolicy.Check.Rejected -> _error.value = check.reason
                }
                // 选超了：加进来的照常留着，但要把"多出来的没加"说清楚 ——
                // 静默丢掉几张是最坏的一种（用户以为都加上了）
                if (uris.size > room) {
                    _error.value = "一次最多 ${ImagePolicy.MAX_IMAGES} 张，" +
                        "多出来的 ${uris.size - room} 张没有加进来。"
                }
            } catch (e: Exception) {
                _error.value = "处理图片失败：${IoErrors.explain(e)}"
            } finally {
                _imageBusy.value = false
            }
        }
    }

    fun removeImage(index: Int) {
        _pendingImages.value = _pendingImages.value.filterIndexed { i, _ -> i != index }
    }

    fun clearImages() {
        _pendingImages.value = emptyList()
    }

    private fun encodeImage(context: Context, uri: Uri): String? {
        val original = context.contentResolver.openInputStream(uri).use { input ->
            BitmapFactory.decodeStream(input)
        } ?: return null

        // 缩放到长边不超过 1280
        val maxSide = 1280
        val scale = maxOf(original.width, original.height).let {
            if (it <= maxSide) 1f else maxSide.toFloat() / it
        }
        val scaled = if (scale >= 1f) original else Bitmap.createScaledBitmap(
            original,
            (original.width * scale).toInt().coerceAtLeast(1),
            (original.height * scale).toInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== original) original.recycle()

        val bytes = ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
            scaled.recycle()
            out.toByteArray()
        }

        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return "data:image/jpeg;base64,$b64"
    }

    /* ─────────────── 状态 ─────────────── */

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /**
     * 「Ta 正在做的事」—— 界面只读（见 [ai.yuki.chuxue.data.Activity]）。
     *
     * ⚠️ 放在 `_busy` **后面**：Kotlin 的属性初始化按书写顺序执行，
     *    写在前面会引用到还没初始化的 `_busy`（编译期就会拦）。
     *
     * 三个来源：这一轮在不在生成（[_busy]）、记忆提取跑没跑
     * （[MemoryExtractionScheduler.extracting]，进程内静态）、
     * 压缩跑没跑（[_compressing]）。
     */
    val activities: StateFlow<List<Activity.Kind>> = combine(
        _busy,
        _compressing,
        MemoryExtractionScheduler.extracting,
    ) { busy, compressing, extracting ->
        Activity.current(reply = busy, memory = extracting, compressing = compressing)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _streamingText = MutableStateFlow<String?>(null)

    /**
     * 流式中的正文；`null` 表示当前没有流式。
     * UI 靠它渲染「打字机」气泡 + 闪烁光标（开发文档 §36）。
     */
    val streamingText: StateFlow<String?> = _streamingText.asStateFlow()

    private val _streamingReasoning = MutableStateFlow<String?>(null)

    /**
     * 流式中的**思考过程**；`null` = 这一轮没有思考内容（或未开思考模式）。
     *
     * 与 [streamingText] 分成两个流：UI 要把思考折起来单独画、正文照常逐字蹦。
     * 混进同一个字符串就得靠标记去分割，那是自找麻烦。
     */
    val streamingReasoning: StateFlow<String?> = _streamingReasoning.asStateFlow()

    /**
     * 首字看门狗的超时（毫秒）—— 见 `send()` 里那段注释。
     *
     * 30 秒是权衡：正常网络下首字通常 1–3 秒就到；再给长，
     * 「Key 填错」这种错误就要让用户多盯着「正在输入…」更久。
     */
    private val firstByteTimeoutMs = 30_000L

    /**
     * 卡死检测的轮询间隔（毫秒）。
     *
     * 500ms 是折中：阈值 10s 下最多晚 0.5s 报警，又不至于每秒醒来很多次。
     * 看门狗**只在流式期间活着**（`finally` 里 cancel），空闲时这个循环根本不存在。
     */
    private val stallPollMs = 500L

    private val _typing = MutableStateFlow(false)

    /**
     * 「正在输入…」：请求已发出、**第一个字符还没到**。
     *
     * 这一刻最难熬（等模型开始说话），给个占位就不像卡死。
     * 首块一到就置 false —— 之后由正文自己承担「她正在说」的反馈。
     */
    val typing: StateFlow<Boolean> = _typing.asStateFlow()

    private val _stalled = MutableStateFlow(false)

    /**
     * 「她好像卡住了…」：流式期间**连续 10 秒没有任何新内容**（正文或思考都算）。
     *
     * ⚠️ 纯粹是**界面提示** —— 不写 `messages`、不进 `plan.body`、不动缓存前缀
     *（用户红线：请求体一个字节都不许改）。判定见 [StreamStall]。
     *
     * 与 [typing] 的分工：`typing` 管"请求发出、首字未到"那一小段；
     * 这一位管"**中途**停了"—— 首字到了、正文打了一半卡住，`typing` 早已 false，
     * 界面就再没有任何信号了，正是这一位要补的洞。
     */
    val stalled: StateFlow<Boolean> = _stalled.asStateFlow()

    /**
     * 最后一次收到内容的时刻（正文或思考）。`0` = 这一轮还没开始 / 已经结束。
     *
     * ⚠️ 类级字段而非 `send()` 局部量：卡死看门狗是**独立协程**（见 `send()` 里
     * 的 `stallWatchdog`），活不到 `send()` 的栈里。读写全在主线程
     *（`viewModelScope` 默认 `Main.immediate`），与 [abort] 同一条约定，不做额外同步。
     */
    private var lastTokenAt = 0L

    /** 正在进行的流式任务。重新发送前先取消旧的，避免两股流往同一个会话里灌。 */
    private var streamJob: Job? = null

    /**
     * 本轮为什么被中止；`null` = 正常结束。
     *
     * 两个写入方：首字看门狗（[Abort.Timeout]）与用户打断（[Abort.User]）。
     * 读在流协程的 `finally` 里。
     *
     * ⚠️ 它必须是**类级字段**，不能是 `send()` 的局部变量：看门狗是个独立协程，
     * 用户打断更是从别的调用点进来的（`interrupt()`），两者都活不到 `send()` 的栈里。
     * 读写全在主线程（`viewModelScope` 默认 `Main.immediate`），所以不做额外同步。
     */
    private var abort: Abort? = null

    /**
     * **重新生成时攒下的历史版本**（v0.61.11）—— 由 [regenerate] 写入、
     * [commitAssistant] 写进新生成的那条消息，**消费一次即清**。
     * 空表 = 这一轮不是重新生成（正常发送一个字都不受影响）。
     */
    private var pendingSuperseded: List<String> = emptyList()

    /** 本轮中止的两种理由 —— 它们给用户的话完全不同。 */
    private sealed interface Abort {
        /** 首字看门狗超时（[seconds] 用于生成给用户看的那句话） */
        data class Timeout(val seconds: Long) : Abort

        /** 用户主动打断：**不报错**（是他自己按的），只回一句提示 */
        data object User : Abort
    }

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /**
     * 最近一次**请求失败**的对话化表达（H4）。`null` = 现在没有。
     *
     * 与 [_error] 的分工：
     * - `_error` → 顶部灵动岛。装的是**操作类**错误（没填 Key、没选人设、
     *   图片不合规、备份失败…）—— 这些"她"没法接话，是用户自己的动作要改；
     * - `_errorLine` → 对话流里的一枚她的气泡。装的是**请求类**失败
     *   （网络不通、状态码 4xx/5xx、首字超时）—— 对话不必断，用户也能知道原因。
     *
     * ⚠️ 请求失败**不再**同时弹灵动岛：同一件事说两遍是噪音。
     */
    private val _errorLine = MutableStateFlow<FailureLine?>(null)
    val errorLine: StateFlow<FailureLine?> = _errorLine.asStateFlow()

    private var errorLineSeq = 0L

    /** 把一次失败摆成她的气泡。 */
    private fun showFailure(line: ChatErrors.Line) {
        _errorLine.value = FailureLine(++errorLineSeq, line.spoken, line.detail)
    }

    /**
     * 记一次「她动了」—— 重置卡死计时。
     *
     * 正文与思考都算：思考也是她在动，不该被当成卡住。
     */
    private fun noteActivity() {
        lastTokenAt = System.currentTimeMillis()
        if (_stalled.value) _stalled.value = false
    }

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    /**
     * 分项导出的进行状态（v0.54.0）。
     *
     * ⚠️ 它和 [_notice] 是两件事：`notice` 是"一闪而过的提示"，
     * 而这个要**在卡片里留着**（用户要求：导出状况、路径、可点击跳转
     * —— 那三样都得能看一阵子，尤其路径是要他去文件管理器里找的）。
     */
    private val _exportState = MutableStateFlow<ExportState>(ExportState.Idle)
    val exportState: StateFlow<ExportState> = _exportState.asStateFlow()

    private val _loadWarning = MutableStateFlow<String?>(null)
    val loadWarning: StateFlow<String?> = _loadWarning.asStateFlow()

    private val _ready = MutableStateFlow(false)

    /**
     * 「首帧数据已就绪」——开屏页用它决定什么时候交棒。
     *
     * ## 为什么要有这个东西
     * 上一版开屏页固定停 1200ms 就走，**不等初始化**。用户进到会话列表时，
     * 数据库还没读完 —— 列表先是空的、再"唰"地长出来。开屏那 1.2 秒明明空着，
     * 却什么也没干。现在它等这个标志。
     *
     * ## 为什么在 `collect` 里面置位、而不是在它之前
     * `repo.observeSessions()` 是冷流：**注册之后**才会去查库并回推第一帧。
     * 如果在订阅前就置位，开屏页会在数据到达前交棒 —— 那就又回到"空列表闪一下"了。
     *
     * ## 它挂了会怎样
     * 不会。开屏页有**超时兜底**（6 秒）：这个标志永远不置位，用户也会被放进去，
     * 只是可能会看到一个空列表。**开屏页绝不能变成"App 打不开"的原因。**
     */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    fun dismissError() { _error.value = null }
    fun dismissNotice() { _notice.value = null }
    fun dismissLoadWarning() { _loadWarning.value = null }

    /* ─────────────── 会话内搜索 ─────────────── */

    private val _searchOpen = MutableStateFlow(false)

    /**
     * 「查找聊天记录」是否展开。
     *
     * 为什么这个状态不住在 `ChatScreen` 里：入口有两个 —— 聊天页顶栏，以及
     * **对话设置页**（用户点名的位置）。从设置页点进来时要先 `popBackStack` 回聊天页，
     * 而那一瞬间 ChatScreen 是重建的，local state 带不过去。放 ViewModel 才能跨帧活下来。
     *
     * 关键词与过滤口径则**故意留在界面层**：它们是"打开搜索才存在"的临时状态，
     * 关掉就该忘掉，没有跨页面复用的需求。
     */
    val searchOpen: StateFlow<Boolean> = _searchOpen.asStateFlow()

    private val _pendingScrollTo = MutableStateFlow<Int?>(null)

    /**
     * 待执行的"滚到第几条"请求。`null` = 没有待办。
     *
     * ⚠️ 这里有一条必须处理的冲突：`ChatScreen` 本来就有一个
     * 「消息数变化 → 自动滚到底」的 `LaunchedEffect`。搜索定位如果也去滚列表，
     * 两者会互相打架（用户刚跳到第 3 条，一条流式增量又把他拽回底部）。
     * 之所以没打起来，是因为**跳转不改变消息数**，那个 effect 不会被触发；
     * 而这个请求由 `consumeScrollRequest()` 消费一次即清空 —— 于是重组不会
     * 反复滚动同一条。
     */
    val pendingScrollTo: StateFlow<Int?> = _pendingScrollTo.asStateFlow()

    fun openSearch() { _searchOpen.value = true }

    fun closeSearch() { _searchOpen.value = false }

    fun requestScrollTo(index: Int) { _pendingScrollTo.value = index }

    fun consumeScrollRequest() { _pendingScrollTo.value = null }

    /* ─────────────── 草稿（按会话） ─────────────── */

    private val _drafts = MutableStateFlow<Map<String, String>>(emptyMap())

    /**
     * 没发出去的输入，按会话 id 存。
     *
     * ## 为什么它必须住在 ViewModel 里（上一版那个修法只对了一半）
     * v0.15.1 把草稿改成 `rememberSaveable` 并宣称「离开聊天页再回来还在」。
     * 但 `rememberSaveable` 的状态挂在导航返回栈的**那一个 entry** 上：
     * 从聊天页进设置页再返回（entry 仍在栈里）确实保得住，
     * 而**退出到主界面再进聊天页**时 entry 已被销毁，状态随之丢失 ——
     * 用户报的正是后者：「退出去再进入就消失」。
     *
     * 根因不是"该用 rememberSaveable 却用了 remember"，而是**状态归属错了层**：
     * 草稿是「这个人对这段对话的未完成输入」，它属于**会话**，不属于某个界面实例。
     * 所以归 ViewModel（配置变更与导航进出都存活），按 sessionId 索引。
     *
     * ⚠️ 仍**不落盘**：进程被杀会丢。这是刻意的 —— 草稿是极短命的临时状态，
     * 为它加一张表或一次 DataStore 写不划算。真要做，那时它该是一张 `drafts` 表。
     */
    val drafts: StateFlow<Map<String, String>> = _drafts.asStateFlow()

    fun draftOf(sessionId: String): String = _drafts.value[sessionId].orEmpty()

    /** 空串 = 删除该键 —— 否则这个 map 会随会话数一直长。 */
    fun setDraft(sessionId: String, text: String) {
        if (sessionId.isEmpty()) return
        _drafts.value = if (text.isEmpty()) {
            _drafts.value - sessionId
        } else {
            _drafts.value + (sessionId to text)
        }
    }

    /* ─────────────── 我发过的话（v0.61.21，⑤-A 第 7 条）─────────────── */

    /**
     * **跨会话**的说话历史（「+」菜单里的「发过的话」用）。
     *
     * 与「长按气泡 → 重发」的分工：那个只在**这一段对话里**，这个跨全部会话。
     * ⚠️ 只在界面层：不进 `messages`、不进请求体；点一条只**填进输入框**，不直接发。
     */
    private val _sendHistory = MutableStateFlow(store.loadSendHistory())
    val sendHistory: StateFlow<List<String>> = _sendHistory.asStateFlow()

    /** 搜索交给纯函数（[SendHistory.search]）——界面自己调，VM 不必再包一层。 */
    private fun rememberSent(text: String) {
        val next = SendHistory.record(_sendHistory.value, text)
        // ⚠️ 引用相同 = 表没变（记的是最近那条/空串）→ 别白刷一次状态流
        if (next === _sendHistory.value) return
        _sendHistory.value = next
        // 丢了也不影响聊天，所以直接落盘、不做错误处理（`apply()` 本身是异步的）
        store.saveSendHistory(next)
    }

    /* ─────────────── 初始化 ─────────────── */

    init {
        when (val r = store.loadPersonas()) {
            is Store.Loaded.Ok -> _personas.value = r.value
            is Store.Loaded.Failed -> _loadWarning.value =
                "人设数据读取失败（原始数据已备份，未被覆盖）：${IoErrors.explain(r.cause)}"
        }
        // 用户人设（v0.61.41）—— 与 personas 同一条静默降级纪律（失败备份原始数据、不覆盖）
        when (val r = store.loadUserPersonas()) {
            is Store.Loaded.Ok -> _userPersonas.value = r.value
            is Store.Loaded.Failed -> _loadWarning.value =
                "用户人设数据读取失败（原始数据已备份，未被覆盖）：${IoErrors.explain(r.cause)}"
        }
        // 启动时对一次人设（有登录态才动）—— 端到端加密，失败静默。
        syncPersonas()
        // 服务器头像兜底：本地没图时把账号里的那张取回来（本地有就绝不覆盖）。
        fetchAvatarIfMissing()
        // 反向补齐：本地有图、服务器上没有时**补传一次**（v0.58.x）。
        // 没有这一步，"先挑头像后登录 / 那一刻上传失败"的用户在市场上永远是首字兜底。
        pushAvatarIfMissing()
        // 服务端下发的免费分组（v0.58.0）—— 静默，失败就当没有。
        refreshFreeGroup()
        // 服务端功能开关（v0.58.0）—— 同样静默，失败按"全开"。
        refreshFeatures()
        // 老用户兼容迁移（v0.61.17）：把历史里"连续多条她的回复"收敛成一条 ——
        // v0.61.16 之前每次重新生成都会在库里多留一条（用户：「重新生成 4 次就要删 4 次」）。
        // 幂等，所以每次启动跑一遍，不必记"迁移过没有"。
        sanitizeSquashedReplies()
        viewModelScope.launch {
            // ⓪ 先把上次崩溃留下的「半截回复」捞回来（文档 §9.5）。
            //    放在最前：用户一进来就该看到「她上次说的话」，而不是空着。
            runCatching { recovery.recoverIncomplete() }

            // ① 一次性迁移：把旧 DataStore 里的会话导入 Room。
            //
            // ⚠️ v0.61.21：加了"**消费过没有**"这道门。
            //    原来只靠 `importLegacyOnce` 内部判"库里有没有会话"，于是：
            //    用户**删光所有对话** → Room 变空 → 下次启动条件又成立 →
            //    那份冻结的旧快照被再导一次，**删掉的对话凭空复活**
            //   （而且旧快照字段有损，复活的会话会丢 provider/思考/压缩状态）。
            //    兼容：老用户没有这个标记 → 读到 false → 照旧导一次，行为与改动前一致。
            if (!store.legacyImported()) {
                when (val r = store.loadSessions()) {
                    is Store.Loaded.Ok -> {
                        repo.importLegacyOnce(r.value)
                        // 只有**读成功**才记账：读失败（备份已另存）时不留标记，
                        // 下次还有机会把那份损毁的快照修回来。
                        store.markLegacyImported()
                    }
                    is Store.Loaded.Failed -> _loadWarning.value =
                        "旧对话记录读取失败（原始数据已备份，未被覆盖）：${IoErrors.explain(r.cause)}"
                }
            }

            // ② 之后列表由 Room 驱动：任何写入都会自动回推到 UI，
            //    各处不必再手工刷新。
            repo.observeSessions().collect { fromDb ->
                // ⚠️ 不能直接 `_sessions.value = fromDb` —— 那是用户报的
                // 「发出去的消息消失」的**另一半原因**：乐观更新已上屏、保存还没落盘时，
                // 一条**稍旧**的数据库快照会把刚上屏的消息抹掉。
                //
                // 合并规则（保留本地更新的会话）抽在 `SessionMerge` 里，有单测钉住。
                // 它与 `SessionRepository.saveSession` 的事务修复**缺一不可**：
                // 那边修「两次写入之间被观察到」，这边修「旧快照覆盖新本地」。
                _sessions.value = SessionMerge.merge(fromDb, _sessions.value)

                if (_activeSessionId.value.isBlank() ||
                    fromDb.none { it.id == _activeSessionId.value }
                ) {
                    _activeSessionId.value = fromDb.firstOrNull()?.id ?: ""
                }

                // 首帧数据到了 → 允许开屏页交棒。
                // 放在 collect **内部**：`observeSessions()` 是冷流，订阅之后才会去查库，
                // 这才是"列表真的有内容了"的那一刻。
                if (!_ready.value) _ready.value = true
            }
        }

        // 数据就绪后上报一次使用统计（**只有用户填了服务器地址时才会真的发**）。
        // 延迟几秒：别和冷启动抢资源，也避开开屏动画那一段。
        viewModelScope.launch {
            delay(4_000)
            reportTelemetry()
        }
    }

    /* ─────────────── 发送 ─────────────── */

    fun send(text: String) {
        val trimmed = text.trim()
        val images = _pendingImages.value
        if ((trimmed.isEmpty() && images.isEmpty()) || _busy.value) return

        val session = activeSession() ?: run {
            _error.value = "请先新建一个对话（需要选择人设）"
            return
        }
        // ⚠️ 判据是**这段会话实际会用**的密钥（分组优先），不是全局那个字段 ——
        //    分组界面启用之后密钥落在分组里、全局字段对新用户恒为空串，
        //    看它会把"已经配好了"误判成"没配"，用户**根本发不出消息**。
        // ⚠️ 顺序也调了：「没有会话」判在前面 —— 没有会话时"密钥填没填"是无关的，
        //    先报那个会让用户去改一件本来没问题的事。
        val s = effectiveSettings(session)
        if (s.apiKey.isBlank()) {
            _error.value = "请先到「设置 → 连接设置」里填好密钥"
            return
        }
        val persona = personaById(session.personaId) ?: run {
            _error.value = "这个对话绑定的人设已被删除，请新建对话"
            return
        }

        // 发送前再校验一次：图片只能出现在 user 消息里
        when (val check = ImagePolicy.check("user", images)) {
            is ImagePolicy.Check.Ok -> Unit
            is ImagePolicy.Check.Rejected -> {
                _error.value = check.reason
                return
            }
        }

        val sessionId = session.id
        val frozenPrefix = PromptEngine.buildFrozenPrefix(s, persona, userPersonaFor(persona))

        // 用户发了新消息 → 上一条"压缩完成"的提示已经翻篇（v0.48.0）
        _compressNotice.value = null

        // ── v0.61.46：发前「超窗警戒线」预检 ──
        // 估算已经贴到上限的 95%：这一轮大概率会被 API 直接拒绝 —— 既白花钱、
        // 又让用户白看一次失败。先压缩、这一次不发。
        // ⚠️ 放在所有副作用（记住已发/落库/清草稿/_busy 置位）之前：
        //    早退时这一轮"没发生过"，用户输入原样留在输入框，压好再点发送即可。
        // ⚠️ 与模式无关（MANUAL 也拦）：超窗是硬故障，不是调优偏好
        //    （Tianshu 原话：an over-window request is a hard API failure）。
        val ceilingLimit = ContextCompress.contextLimit(s.model, memoryWindowForSession(session))
        val ceilingEstimate = ContextCompress.estimateSentContext(
            frozenPrefix = frozenPrefix,
            messages = session.messages,
            summary = session.summary,
            coveredCount = session.summaryCount,
            userInput = trimmed,
        )
        if (ContextCompress.shouldPauseForCeiling(ceilingEstimate, ceilingLimit)) {
            _compressNote.value = if (_compressing.value) {
                "内容快超出上限，正在压缩中 —— 压好再发一次就好。"
            } else {
                "这次要发的内容快超出模型能装下的长度了 —— 先压缩一下，压好再发一次就好。"
            }
            if (!_compressing.value) summarizeContext(sessionId)
            return
        }

        // ── 压缩触发判定（v0.48.0，用户要求"做个选项用户自己选择"）──
        // ⚠️ 用 **estimateSentContext**（把摘要算进去）而不是 estimateContext ——
        //    后者在压缩前后数值不变（压缩不动 messages），会导致"每轮重复压缩"。
        // ⚠️ **不阻断这次发送**：压缩是旁路，失败只留一句提示。
        evaluateCompressTrigger(sessionId, session, frozenPrefix)

        // 会话级的思考设置覆盖全局（全局只是「新会话的初值」，见 newSession）。
        // ⚠️ 它改的是 payload 里的 thinking / reasoning_effort 参数，**不动 messages** ——
        // 因此不会碰到缓存前缀（缓存认的是 messages 的字节序列）。
        //
        // v0.51.0：**会话级 provider（分组 + 模型）在 `s` 里已经落定** ——
        // 上面的 `effectiveSettings(session)` 就是全仓唯一的取值定义，
        // 这里只再叠上"这段会话的思考设置"。
        // ⚠️ 分组存在时 key / 地址 / 模型**三者一起**取 ——
        //    只换其中一两个会拼出"用 A 分组的地址配 B 分组的密钥"这种半套状态，
        //    而那种状态下的报错（401 / 404）跟配置错误的表象一样，极难排查。
        // ⚠️ **一个分组都没有时三个都退回全局设置** ——
        //    于是升级上来的老用户行为**逐字节不变**（他没建分组就用原来的配置）。
        val sessionGroup = groupForSession(session)
        val requestSettings = s.copy(
            thinkingEnabled = session.thinkingEnabled,
            reasoningEffort = session.reasoningEffort,
        )
        // 这一轮实际用的服务商画像（v0.51.0）—— 命中量读哪个字段、有没有余额接口，
        // 都由它决定。**在这一刻定下来**：中途改分组不会影响已经在飞的那一轮。
        val providerProfile = ProviderProfiles.resolve(requestSettings.baseUrl)

        // 「我发过的话」——记在**这一刻**：上面所有校验都过了，这一轮是真要发出去的。
        // 只记文字（纯发图没有"话"可记），空串由 SendHistory.record 自己挡掉。
        rememberSent(trimmed)

        _busy.value = true
        _error.value = null
        // 新一轮开始：上一条失败气泡退场 —— 它是"这一轮"的反馈，不是常驻记录
        _errorLine.value = null
        abort = null
        _streamingText.value = ""
        _streamingReasoning.value = null
        // 抢占位：「正在输入…」——请求已发出、首字未到
        _typing.value = true
        // 卡死计时从这一刻起算：请求已发出，还没收到任何内容
        lastTokenAt = System.currentTimeMillis()
        _stalled.value = false
        // ⚠️ 首字看门狗 —— 修用户报的「API Key 填错却一直显示正在输入、不报错」。
        //
        // 为什么需要它：SSE 流通常带 **keep-alive 心跳**，心跳会不断刷新 OkHttp 的
        // 读超时 —— 于是「服务端其实什么都没在发」也能一直挂着，界面就永远停在
        // 「正在输入…」。只有当**一个事件都没来过**时才说明对面没在说话。
        //
        // ⚠️ v0.31 它只"收提示"（关掉「正在输入…」+ 报一句原因），**不强杀流**。
        //    用户真机验出后果：提示出来了，可输入框还在转圈、气泡还挂着、
        //    新消息发不出去 —— 因为 `_busy` 只有流真正结束才复位，而流还挂着。
        //    现在它**真的把这一轮结束掉**：取消下游 collect，下面的 catch/finally
        //    立刻跑完，界面随之复位（`_busy` / `_typing` / `_streamingText` 都在 finally 里）。
        //    半开连接不必再"留着排查"——错误当场就报给用户了。
        // ⚠️ 用 viewModelScope 而不是裸 launch：这一段在协程**之外**（下面才起流式协程），
        // 裸 launch 在这里没有接收者。
        val firstByteWatchdog = StreamStall.firstByteWatchdogMs(
            baseMs = firstByteTimeoutMs,
            // ⚠️ 非流式（"整段出现"）**不起这个看门狗**（v0.61.21 修）：
            //    那里根本没有"首字"这回事 —— 整段回复是一次性回来的，
            //    30 秒一到就会把一轮**完全正常**的请求当成超时掐掉。
            nonStream = isNonStreamMode(requestSettings.sendMode),
        ).let { ms ->
            if (ms <= 0L) {
                null
            } else {
                viewModelScope.launch {
                    delay(ms)
                    if (_typing.value && _streamingText.value.isNullOrEmpty()) {
                        abort = Abort.Timeout(ms / 1000)
                        streamJob?.cancel()
                    }
                }
            }
        }
        // ⚠️ 卡死看门狗 —— 补的是首字看门狗够不着的另一段：**首字已到、中途却停了**。
        //
        // 为什么首字看门狗不管用：它只看"一个事件都没来过"，且 30 秒就强杀这一轮；
        // 而"她说到一半不说了"发生在首字之后，那时 `typing` 已是 false，
        // 界面只剩一颗不动的光标 —— 用户照样不知道是死了还是在憋大招。
        //
        // 这一位**只提醒、不强杀**（与首字看门狗不同）：长回复里偶发的一两秒停顿很常见，
        // 拿它当错误会把好的一轮打断。内容一到（正文或思考）[noteActivity] 立刻抹平标志。
        val stallWatchdog = viewModelScope.launch {
            while (true) {
                delay(stallPollMs)
                if (StreamStall.hasStalled(lastTokenAt, System.currentTimeMillis())) {
                    _stalled.value = true
                }
            }
        }

        streamJob?.cancel()
        // 流式期间起前台服务：App 退到后台也不至于被系统冻结（文档 §14.4）。
        // 起不来（例如用户没给通知权限）不影响流式本身 —— WAL 会兜住已收到的部分。
        // ⚠️ v0.61.21：这里**不能抛**。它在带 finally 的 try **之外**（那个 try 在
        //    `streamJob` 协程内部），一旦抛出去，finally 根本不会跑 ——
        //    `_busy` 会永久为 true（`send()` 开头直接 return、输入框从此锁死），
        //    上面两位看门狗协程也永久泄漏。起前台服务在权限/系统限制下是会抛的，
        //    而它本身"起不来也不影响流式"（WAL 会兜住已收到的部分）——
        //    所以这里就该吞掉，而不是让一个辅助动作把整轮聊天带下去。
        runCatching { StreamingForegroundService.start(getApplication(), "正在生成回复") }
        // 告诉后台记忆提取：前台正在用这条 API Key。
        // 前后台同一个 Key，同时发请求可能触发 DeepSeek 的并发限流，把用户这次对话直接打坏 ——
        // 后台是附加服务，绝不能影响前台（见 MemoryExtractionThrottle）。
        MemoryExtractionScheduler.setFrontendStreaming(true)
        streamJob = viewModelScope.launch {
            // ① 检索与这句话相关的长期记忆（文档 §7.5 的「检索式」分层）。
            //    结果只会进**附录**，不碰冻结前缀与历史 —— 见 架构红线文档。
            //    检索失败不阻断对话：记忆是增强，不是前提。
            //
            // ⚠️ v0.61.54：记忆**彻底分轨** —— 附录的记忆源按人设的记忆方式切换：
            //    · 云端人设 → 从 OB 检索（`XinchaoMemoryApi.fetchBuckets`，与云端记忆页同一份数据）；
            //    · 本地人设 → 从 Room 检索（原路径）。
            //    原来无论哪种模式都只读 Room —— 于是"云端只记云端"之后，
            //    对话里**一条云端记忆都看不到**（写了却用不上）。
            val memories = runCatching {
                if (personaById(session.personaId)?.isCloudMemory == true) {
                    cloudAppendixLines(session.personaId, trimmed)
                } else {
                    memoryRepo.appendixLines(
                        personaId = session.personaId,
                        sessionId = sessionId,
                        userInput = trimmed,
                    )
                }
            }.getOrElse { emptyList() }

            // 表情包的「教标签」提示 —— ⚠️ **只拼进附录**。
            // 它每轮都一样，看上去很适合放进人设或全局前缀，但那是错的：
            // 那两层是**冻结前缀**，往里面加东西会让该层之下所有会话的缓存作废。
            // 附录本来就不参与缓存命中，所以放这里是安全的（理由也写在 EmojiCategories.promptHint）。
            val emojiHint = if (_settings.value.emojiEnabled) {
                listOf(EmojiCategories.promptHint())
            } else {
                emptyList()
            }

            // 心潮「此刻块」（v0.61.52）：**给模型看的她的状态**（驱力/情绪/挂念/还在气）。
            // ⚠️ 它进**附录**（与记忆同一纪律），且带 TTL 缓存 —— 否则每轮对话都要多打一次
            //    服务器往返（本机快、线上不快），而"她此刻什么感受"并不需要秒级新鲜。
            val xinchaoNote = xinchaoNoteOf(session.personaId, sessionId)

            // 这一轮用哪种传输（v0.61.0：**设置真的管传输了**，不只是显示）
            val sendMode = requestSettings.sendMode
            val plan = PromptEngine.plan(
                settings = requestSettings,
                frozenPrefix = frozenPrefix,
                // ⚠️ 走 `ContextCompress`：没压过时它**原样返回**（不会凭空动前缀），
                // 压过时才是「摘要 + 最近若干条」。
                // ⚠️ 库里与界面上的聊天记录**一条都没少** —— 压缩只发生在这里，
                // 见 `ContextCompress` 的类注释（用户原话：聊天记录不能丢失）。
                history = ContextCompress.buildHistory(
                    session.messages, session.summary, session.summaryUpTo,
                    coveredCount = session.summaryCount,
                ),
                userText = trimmed,
                memories = memories + emojiHint,
                images = images,
                // 心潮此刻块（v0.61.52）—— 走附录，见上面取它的地方
                xinchao = xinchaoNote,
                // ⚠️ v0.61.0：`stream` **真正跟随设置**了。
                // 以前这里写死 true —— 于是设置里的"流式/一次性"只改了**显示**，
                // 传输永远是流式。用户报"两者看不出区别"，根子就在这。
                // 现在：选「非流式」→ 请求体里 `stream: false`，是**真的**非流式请求。
                stream = !isNonStreamMode(sendMode),
                // ⚠️ v0.51.0：第三方网关可能不接受 `thinking` / `reasoning_effort`
                //（DeepSeek 的扩展字段），由分组上的开关决定发不发。
                // 缺省 true = 与本开关存在之前逐字节一致。
                sendThinkingParams = sessionGroup?.sendThinkingParams ?: false,
            )
            // ⚠️ v0.53.0：这里原本会把**每一轮请求的结构**写进手机下载目录的
            //    `yuki_debug.txt`（v0.46.7 为查"命中率上不去"临时加的诊断）。
            //    用户要求删掉它 —— 理由是它"每次聊天都在写外部存储"，
            //    而且正常的聊天也在写，真正的异常反而淹没在里头。
            //    现在改成**只在崩溃/出错时**落日志：见 `CrashLog`（写在应用私有目录，
            //    不需要权限、不占用户的下载目录）。


            // ② user 消息立即落盘：它是确定的输入，不会变。
            //    而 assistant 的回复**在流式结束前绝不写入历史**（文档 §9.3）——
            //    半截消息一旦进了历史，下一轮前缀就在这里错位，缓存从此不再命中。
            //
            //    时间戳只为界面而打（聊天窗口的时间分割条）：
            //    `createdAt` **不进请求体**，所以给这条消息打戳不会动到 plan.body 的字节。
            val stampedUserMessage = plan.userMessage.copy(createdAt = System.currentTimeMillis())
            patchSession(sessionId) { cur ->
                cur.copy(
                    messages = cur.messages + stampedUserMessage,
                    title = if (cur.messages.isEmpty()) {
                        (if (trimmed.isNotBlank()) trimmed else "［图片］").take(14)
                    } else cur.title,
                    updatedAt = System.currentTimeMillis(),
                )
            }
            _pendingImages.value = emptyList()

            var received = ""
            // 思考过程单独攒。它与正文一起写进历史（供回看），但**不进 buffer、不回传模型**
            var reasoningReceived = ""
            // 思考用时：**第一块思考到达 → 第一块正文到达**，就是"她想了多久"。
            // ⚠️ 与 createdAt 同类：只上屏、不进请求体，所以多取几次也不会动缓存。
            var reasoningStartedAt = 0L
            var thinkingMs: Long? = null
            // v0.54.0（给看板）：这一轮「发出 → 首字」的耗时。
            // ⚠️ `requestSentAt` 取协程开头（请求刚发出去的那一刻），
            //    `firstByteAt` 取**正文第一块**到达时 —— 中间可能隔着整段思考，
            //    两者混起来算出来的就不是"回复耗时"了。
            val requestSentAt = System.currentTimeMillis()
            var firstByteAt = 0L
            // 本轮编号：WAL 的片段按「会话 + 轮次」归组，恢复时才能拼对
            // ⚠️ v0.61.21：同样不能抛。它在协程**带 finally 的 try 之前**——
            //    数据库一句异常就会让这一轮永远收不了尾（`_busy` 永久 true、输入框锁死）。
            //    turnIndex 拿不到就退化成 1：WAL 那条恢复链最坏是"这次记不上"，
            //    比"整个界面卡死在正在输入"轻得多。
            val turnIndex = (runCatching { walDao.maxTurnIndex(sessionId) }.getOrNull() ?: 0) + 1
            try {
                streamConsumer.events(
                    requestSettings, plan.body,
                    nonStream = isNonStreamMode(sendMode),
                ).collect { ev ->
                    when (ev) {
                        is ChatStreamEvent.Reasoning -> {
                            // 思考只进内存、只上屏 —— 流式结束时随回复一起写进历史（供回看），
                            // 但**永不回传给模型**（见 ChatMessage.reasoning 的注释）
                            if (reasoningStartedAt == 0L) {
                                reasoningStartedAt = System.currentTimeMillis()
                            }
                            reasoningReceived += ev.text
                            _streamingReasoning.value = reasoningReceived
                            // 首块到了：「正在输入…」退场（思考也算是"她开始动了"）
                            _typing.value = false
                            noteActivity()
                        }
                        is ChatStreamEvent.Delta -> {
                            // 正文第一块到达 = 她想完了 —— 这一刻定下"思考用时"
                            if (thinkingMs == null && reasoningStartedAt > 0L) {
                                thinkingMs = System.currentTimeMillis() - reasoningStartedAt
                            }
                            // v0.54.0：**同一刻**也是看板要的"首字到达"（只记第一次）
                            if (firstByteAt == 0L) firstByteAt = System.currentTimeMillis()
                            received += ev.text
                            _streamingText.value = received
                            _typing.value = false
                            noteActivity()
                            // 每块先落 WAL：此刻进程被杀，这一块也还在（文档 §9.5）。
                            // 历史（messages）仍然只在结束时写 —— 历史必须干净，日志可以脏。
                            runCatching {
                                walDao.insert(
                                    WalEntryEntity(
                                        sessionId = sessionId,
                                        turnIndex = turnIndex,
                                        delta = ev.text,
                                        receivedAt = System.currentTimeMillis(),
                                    ),
                                )
                            }
                        }
                        is ChatStreamEvent.Done -> {
                            received = ev.fullText
                            // 兜底：只思考、正文一直没来（或被中断）时也要有个数
                            if (thinkingMs == null && reasoningStartedAt > 0L) {
                                thinkingMs = System.currentTimeMillis() - reasoningStartedAt
                            }
                            commitAssistant(
                                sessionId, received, reasoningReceived, ev.cache, thinkingMs,
                                profile = providerProfile,
                                providerBaseUrl = requestSettings.baseUrl,
                                stopReason = ev.stopReason,
                                // v0.54.0（看板）：这一轮的首字耗时与模型。
                                // ⚠️ `firstByteAt == 0` 表示**没测到**（不是"瞬间"），
                                //    所以这里必须原样传 0，由看板分情况显示。
                                firstByteMs = if (firstByteAt > 0) firstByteAt - requestSentAt else 0L,
                                model = requestSettings.model,
                            )
                            walDao.markPersisted(sessionId, turnIndex)
                            _streamingText.value = null
                            _streamingReasoning.value = null
                        }
                    }
                }
            } catch (e: CancellationException) {
                // 本轮被中止（首字看门狗超时 / 用户打断）：**已经收到的内容一个字不丢**，
                // 与下面的异常路径同一条协议（文档 §9.5）。
                //
                // ⚠️ 收尾两件事必须放进 NonCancellable：这个协程此刻已是取消态，
                //    任何挂起点（`walDao.markPersisted` 是 suspend）都会立刻再抛一次取消 ——
                //    收尾就永远做不完，WAL 里留下下次启动会重复捞的残片。
                withContext(NonCancellable) {
                    if (received.isNotBlank()) {
                        if (thinkingMs == null && reasoningStartedAt > 0L) {
                            thinkingMs = System.currentTimeMillis() - reasoningStartedAt
                        }
                        commitAssistant(
                            sessionId, received, reasoningReceived, null, thinkingMs,
                            // v0.54.0：中断路径也要记 —— 首字若已到过，那就是真实耗时；没到就是 0（= 未测到）
                            firstByteMs = if (firstByteAt > 0) firstByteAt - requestSentAt else 0L,
                            model = requestSettings.model,
                        )
                    }
                    runCatching { walDao.markPersisted(sessionId, turnIndex) }
                }
                // 继续往上抛：吞掉取消会让协程的取消语义失效。
                // 下面的 finally 照样会跑，收尾不受影响。
                throw e
            } catch (e: Exception) {
                // 断在中途：把**已经收到的内容**落进历史，不让用户白等。
                // 文档 §9.5 的中断恢复协议：有 finishReason 就正常收尾，
                // 没有也保留尾巴 —— 而不是整段丢弃。
                if (received.isNotBlank()) {
                    if (thinkingMs == null && reasoningStartedAt > 0L) {
                        thinkingMs = System.currentTimeMillis() - reasoningStartedAt
                    }
                    commitAssistant(
                        sessionId, received, reasoningReceived, null, thinkingMs,
                        // v0.54.0：中断路径也要记 —— 首字若已到过，那就是真实耗时；没到就是 0（= 未测到）
                        firstByteMs = if (firstByteAt > 0) firstByteAt - requestSentAt else 0L,
                        model = requestSettings.model,
                    )
                }
                // 已进历史（或本来就没内容），WAL 标记完成，避免下次启动重复捞
                walDao.markPersisted(sessionId, turnIndex)
                // H4：失败不再只弹灵动岛 —— 换成"她能说出口的一句话"（技术摘要附在下面）。
                // ⚠️ 不走 `_error`：那样会同时弹一颗灵动岛胶囊，同一件事说两遍。
                showFailure(ChatErrors.forException(e))
                // v0.61.46：若这次被拒是「上下文超窗」—— "换个说法"救不了，压缩才是出路。
                // 自动触发一次（无视模式），并如实告知；压好后用户重发即可。
                maybeAutoCompressOnOverflow(sessionId, e)
            } finally {
                // 看门狗必须收掉：它内含 delay，留着会在下一轮误报
                // 非流式时它根本没起（见上面 `firstByteWatchdogMs` 的注释）
                firstByteWatchdog?.cancel()
                // 卡死看门狗同理；同时把标志与计时清零 —— 否则下一轮空闲时
                // `lastTokenAt` 还停在上次的时刻，会凭空报出一次"卡住"
                stallWatchdog.cancel()
                _stalled.value = false
                lastTokenAt = 0L
                _busy.value = false
                // 流式结束（无论成败）都要停掉常驻通知，不能留一个「正在生成回复」挂着
                StreamingForegroundService.stop(getApplication())
                // 释放并发护栏。放在 finally 里是必须的：异常路径也要释放，
                // 漏放会让后台提取被**永久挡住** —— 一种没有报错、只有"记忆再也不出现"的静默失效
                MemoryExtractionScheduler.setFrontendStreaming(false)
                // 「正在输入…」也必须收掉：异常路径下不能让它一直转下去（静默假象）
                _typing.value = false
                // ⚠️ 流式气泡与思考块也要收掉。`_streamingText` 停在**空串**（不是 null）
                //    会让界面一直以为"她正在说"：气泡留着、输入区转圈 —— H2 的现场之一。
                _streamingText.value = null
                _streamingReasoning.value = null
                // ⚠️ 这一轮的「待继承历史版本」也必须收掉（v0.61.12，审查捞出来的）：
                //    它唯一被消费的地方是 `commitAssistant`，而那条路在**空回复早退**等
                //    情况下根本走不到 —— 残留下来会在**下一轮正常发送**时被消费，
                //    让新消息凭空多出一份不相干的旧版本（气泡下面冒出「‹ 1/2 ›」）。
                //    放在 finally 是幂等的：成功路径已经在 commitAssistant 里消费过一次，
                //    这里清的是"没走到底"的那一轮。
                pendingSuperseded = emptyList()
                // 中止的说明放在**最后**写：它不能被上面的任何一行覆盖。
                // ⚠️ 读 `abort` 而不是在 catch 里分头处理，是因为**两条路径共用这一处**：
                //    看门狗与用户打断走的都是上面那条 CancellationException 分支。
                when (val a = abort) {
                    is Abort.User -> _notice.value = "已经打断Ta的回复"
                    is Abort.Timeout -> showFailure(ChatErrors.forFirstByteTimeout(a.seconds))
                    null -> Unit
                }
                abort = null
            }
        }
    }

    /**
     * 用户打断正在进行的回复（H3）。
     *
     * ⚠️ 界面侧**先弹二次确认**（用户要求），这里只负责真正中止 ——
     * 二次确认是界面的事，ViewModel 不该知道有没有对话框。
     *
     * 已经写出来的半截内容**不丢**：取消会走到 `send()` 里那条
     * `catch (CancellationException)` 分支，按文档 §9.5 的协议落进历史，
     * 不会让用户白等一场。
     */
    fun interrupt() {
        // 没有在跑就别动 —— 否则会把 `abort` 留成 User，污染下一轮的收尾
        if (!_busy.value) return
        abort = Abort.User
        // ⚠️ v0.61.21：光 cancel 协程不够。OkHttp 的 execute() 是**阻塞**的、
        //    也不响应协程取消 —— 只取消协程的话，界面得等底层 socket 自己返回
        //    （非流式最长 120 秒）才复位，"停下"看着像没反应。
        //    这一句让那次读**当场**抛 IOException 收场，finally 随之跑完。
        ActiveCall.cancel()
        streamJob?.cancel()
    }

    /** 把一次完整（或中断前已收到）的回复原子性写入历史。 */
    private fun commitAssistant(
        sessionId: String,
        text: String,
        reasoning: String,
        cache: CacheStats?,
        /** 思考用时（毫秒）；没开思考时是 null */
        thinkingMs: Long? = null,
        /**
         * 这一轮「发出 → 首字」的毫秒数（v0.54.0，看板用）。
         * `0` = **没测到**（被中断、或首字还没到这一轮就结束了）——
         * 与"瞬间回复"是两回事，看板必须分得清（0 会被读成后者）。
         */
        firstByteMs: Long = 0L,
        /** 这一轮实际用的模型名（v0.54.0，看板的「各模型排行」用）。 */
        model: String? = null,
        /**
         * 这一轮实际用的服务商画像（v0.51.0）。它决定命中量**读哪个字段**；
         * `null` = 按 DeepSeek 口径（等于本参数存在之前的行为）。
         */
        profile: ProviderProfile? = null,
        /**
         * 这一轮用的服务商地址（v0.51.0）—— 用于**按服务商分桶**记用量。
         * `null` = 不分桶（老行为）。
         */
        providerBaseUrl: String? = null,
        /**
         * 服务端这一轮的 `finish_reason`（`stop` / **`length`** / …）。
         *
         * ⚠️ v0.61.0 才加上：以前 `SseParser` 读到了它却只当布尔用，值被丢掉，
         * 于是"输出被截断"这件事到了用户面前只剩一句含糊的"content 为空"。
         * 天枢为此专门有 `isTruncationStopReason` 并把截断事实透传到失败结果里
         *（`src/agent/worker-repair-route.ts`）—— 这里照做。
         */
        stopReason: String? = null,
    ) {
        // ⚠️ 命中量按**这一轮的服务商**取：DeepSeek 在顶层
        //    `prompt_cache_hit_tokens`，OpenAI 系在
        //    `prompt_tokens_details.cached_tokens`。取不到（这家不报）就按 0 计入
        //    —— 但**另有一个标志**告诉界面"不是 0，是没这个数"（见 `_lastTurnReports`）。
        //    把"没报"当 0 显示，用户会以为缓存全废，然后去改人设/改历史 ——
        //    而那些动作**真的**会把前缀弄断。
        val addHit = cache?.let { c -> profile?.hitTokensOf(c) ?: c.hitTokens } ?: 0
        val addMiss = cache?.let { c -> profile?.missTokensOf(c) ?: c.missTokens } ?: 0
        // 记下这一轮的读数（含 0，好让"这轮真的没命中"也能被看见）
        _lastTurnStats.value = Triple(
            addHit,
            addMiss,
            cache?.inputTokens ?: 0,
        )

        // v0.54.0：把这一轮也记进**看板的滚动统计**（近 N 轮趋势 + 模型维度）。
        // ⚠️ 与上面的分桶同一条纪律：fire-and-forget，且**不吞取消** ——
        //    统计写失败绝不能影响这一轮的回复落盘（那是用户的内容，这只是一张图）。
        // ⚠️ 必须 launch 到 IO：这是**偏好文件的解码 + 编码 + 落盘**。
        //    早先它直接跑在调用线程上，而 `commitAssistant` 跑在流协程（Main）——
        //    等于每轮回复收尾都在主线程做两次磁盘 IO，慢机器上会顶掉一帧。
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatchingForTask {
                    val turns = BoardStats.appendTurn(
                        BoardStats.decodeTurns(store.loadBoardTurns()),
                        BoardStats.TurnStat(
                            at = System.currentTimeMillis(),
                            hit = addHit,
                            miss = addMiss,
                            firstByteMs = firstByteMs,
                        ),
                    )
                    store.saveBoardTurns(BoardStats.encodeTurns(turns))

                    // 模型维度：**只有知道这一轮用的哪个模型时才记**。
                    // 拿不到就跳过 —— 排行榜里混进一行「未知模型」还不如少一行。
                    if (!model.isNullOrBlank()) {
                        store.saveModelUsage(
                            BoardStats.encodeModels(
                                BoardStats.bumpModel(
                                    BoardStats.decodeModels(store.loadModelUsage()),
                                    providerKey = ProviderUsage.keyOf(providerBaseUrl.orEmpty()),
                                    model = model,
                                    hit = addHit,
                                    miss = addMiss,
                                ),
                            ),
                        )
                    }
                }
            }
        }
        // "真的 0" 与 "这家不报" 必须分得清 —— 界面上是两种完全不同的说法。
        // ⚠️ 判据是**观察到的**（这一轮的 usage 里到底有没有那个字段），不是
        //    协议表说的：OpenAI 系字段名虽然存在，但很多网关根本不发它。
        if (cache != null) {
            // profile 为 null 时按"有读数"处理（= 本参数存在之前的行为）
            _lastTurnReports.value = profile?.let { it.hitTokensOf(cache) != null } ?: true
            // 按服务商分桶（v0.51.0）：会话级累计值混了不同服务商，看板要说清每一家。
            // ⚠️ fire-and-forget：**统计写失败绝不能影响这一轮的回复落盘**。
            if (!providerBaseUrl.isNullOrBlank()) {
                viewModelScope.launch {
                    runCatching {
                        val key = ProviderUsage.keyOf(providerBaseUrl)
                        val cur = db.providerUsageDao().one(sessionId, key)
                        db.providerUsageDao().upsert(
                            ProviderUsageEntity(
                                sessionId = sessionId,
                                providerKey = key,
                                hitTokens = (cur?.hitTokens ?: 0) + addHit,
                                missTokens = (cur?.missTokens ?: 0) + addMiss,
                                inputTokens = (cur?.inputTokens ?: 0) + cache.inputTokens,
                                outputTokens = (cur?.outputTokens ?: 0) + cache.outputTokens,
                                requests = (cur?.requests ?: 0) + 1,
                                updatedAt = System.currentTimeMillis(),
                            ),
                        )
                    }
                }
            }
        }
        // ⚠️ 空回复**绝不能静默丢掉**。
        //
        // 这里原先是一句 `return` —— 没有历史、没有气泡、没有报错，
        // 界面看上去就像"什么都没发生"。而空回复恰恰是最需要解释的一种失败：
        // 它通常不是网络问题，而是**思考链把输出长度占满了**
        //（`PromptEngine.maxTokensFor` 的注释里有完整来龙去脉）。
        if (text.isBlank()) {
            showFailure(
                ChatErrors.forEmptyReply(
                    sawReasoning = reasoning.isNotBlank(),
                    // 把**真实原因**带进去：是截断就直说是截断，不是就别让用户白改设置
                    truncated = ChatErrors.isTruncationStopReason(stopReason),
                    detail = "content 为空；推理 " + reasoning.length + " 字" +
                        "；finish_reason=" + (stopReason ?: "（没收到）"),
                ),
            )
            return
        }
        // ⚠️ 时间戳与"图片记录"必须用**同一个**值：它是"图属于哪条消息"的键。
        // 两处各算一次 `currentTimeMillis()` 就可能差一毫秒 → 图永远配不上那条回复。
        //（这也是项目的一贯纪律：同一轮的动态值只生成一次，由同一个变量引用。）
        val at = System.currentTimeMillis()
        // 表情包：落库之后再决定要不要配图（决策是纯函数，见 EmojiPicker）
        maybeAttachEmoji(sessionId, text, at)
        // ⚠️ 把刚配上的那张图**一起写进这条消息**（v0.45.3）。
        // 它以前只活在内存表里（`_emojiByMessage`）—— 退出应用即丢、
        // 切换呈现方式也会丢（用户报的"切换之后发送的表情包也会丢失"）。
        // 落进消息之后，"哪张图属于哪条回复"才真正成为历史的一部分。
        // ⚠️ 用 `at` 取（就是上面刚写进去的那个 key），不另算时间戳。
        // ⚠️ 这里**不能**读 `_emojiByMessage`（v0.45.6 修）：
        // 配图是异步的，此刻还没跑完 —— 读到的永远是 null，
        // 于是消息里的 `emojiPath` 全空，而渲染侧只认这个字段 → 图一张都不显示。
        // 改由 `maybeAttachEmoji` 自己在拿到结果后写回。
        patchSession(sessionId) { cur ->
            cur.copy(
                messages = cur.messages + ChatMessage(
                    role = "assistant",
                    content = text,
                    // ⚠️ 呈现方式**冻结在这一刻**（v0.45.3）：之后改设置只影响新回复，
                    // 不会把已经说过的话重新排版（用户要的"不会被改变"）。
                    sendMode = _settings.value.sendMode,
                    // ⚠️ 分段气泡也**冻结在这一刻**（v0.61.6，用户要求「老消息保持原样」）：
                    // 与 sendMode 同一条纪律 —— 改设置不该重排已经说过的话。
                    splitBubbles = _settings.value.splitBubbles,
                    // 重新生成时把旧版本带上（v0.61.11）—— **消费一次即清**，
                    // 免得它黏到下一轮正常发送上
                    superseded = pendingSuperseded.also { pendingSuperseded = emptyList() },
                    // 时间戳只给界面用（时间分割条）；它不进请求体，不影响缓存
                    createdAt = at,
                    // 思考过程同理：上屏可回看，但**不回传给模型**。
                    // 空串归一成 null —— 老消息与"没思考"是同一种状态，不该有两种表示。
                    reasoning = reasoning.ifBlank { null },
                    // 思考用时。没思考就没有时长可言，一并归 null（界面据此不画"想了多久"）
                    thinkingMs = if (reasoning.isBlank()) null else thinkingMs,
                ),
                totalHit = cur.totalHit + addHit,
                totalMiss = cur.totalMiss + addMiss,
                updatedAt = System.currentTimeMillis(),
            )
        }

        // 「Ta 的状态」：开关开着的人设，把这一轮上报给服务器（见 reportXinchaoTurn）。
        // 挂在这里 = 单点覆盖 Done / 打断 / 异常三条分支（它们都经 commitAssistant），
        // 且空回复在上面已早退，天然不会空报。
        reportXinchaoTurn(sessionId, text)
    }

    fun activePersona(): Persona? = activeSession()?.let { personaById(it.personaId) }
}

/**
 * 要不要把**本地头像**补传到服务器（**纯函数**，单测钉住）。
 *
 * 三个条件缺一不可：已登录、服务器上还没有、本地确实有一张。
 * ⚠️ 这是「市场里看不到作者头像」的**客户端那一半根因** ——
 *    详见 [ChatViewModel.pushAvatarIfMissing] 的注释。
 */
internal fun shouldPushAvatar(isLoggedIn: Boolean, serverUrl: String?, localPath: String?): Boolean =
    isLoggedIn && serverUrl.isNullOrBlank() && !localPath.isNullOrBlank()

/**
 * 要不要把**服务器头像**拉回本地（v0.59.0）。
 *
 * 四个条件联合判断，缺一不可：已登录、服务器上有、本地这张**不是它**。
 *
 * ⚠️ "本地这张不是它"这一条是修 bug 的关键：原判据只看"本地有没有图"，
 *    于是**在别的设备换了头像，这台永远看不到**（本地有图就不再拉）。
 *    现在拿本地的来源 URL 跟服务器当前 URL 比 —— 不一致才拉。
 *
 * 纯函数（不碰网络/磁盘），单测直接钉它。
 */
internal fun shouldPullAvatar(
    isLoggedIn: Boolean,
    serverUrl: String?,
    localPath: String?,
    localSourceUrl: String?,
): Boolean {
    if (!isLoggedIn || serverUrl.isNullOrBlank()) return false
    // 本地没图 → 必须拉
    if (localPath.isNullOrBlank()) return true
    // 本地有图但**不是从服务器来的**（用户自己选的）→ 尊重用户那张，不覆盖
    if (localSourceUrl.isNullOrBlank()) return false
    // 本地这张是从服务器来的，但服务器已经换了 → 重拉
    return localSourceUrl != serverUrl
}
