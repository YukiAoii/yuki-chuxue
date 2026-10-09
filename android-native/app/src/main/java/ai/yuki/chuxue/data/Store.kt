package ai.yuki.chuxue.data

import android.content.Context

/**
 * 持久化存取。
 *
 * ══ 修复「关闭 App 对话全丢」的关键设计 ══
 * 旧实现是这样写的：
 *
 *     fun load(): List<Session> {
 *         val raw = prefs.getString(KEY, null) ?: return emptyList()
 *         return runCatching { decodeAll(raw) }.getOrElse { emptyList() }   // ← 致命
 *     }
 *
 * 致命之处**不在**「解析失败时显示空列表」，而在于**连锁反应**：
 *   解析失败 → 返回空 → UI 拿到空列表 → 用户发第一条新消息 → `save(空+新消息)`
 *   → **把原本还在文件里的旧数据覆盖掉了** → 数据真正消失。
 *
 * 现在的纪律：
 *   1. 解析失败 **返回失败状态**（不是空列表），让调用方知道出事了；
 *   2. 解析失败时把**原始字符串另存到备份 key**，即使后续被覆盖也能找回；
 *   3. 只有在「确认读取成功」的前提下才允许写回。
 */
class Store(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("yuki", Context.MODE_PRIVATE)

    /* ─────────────────────────── 面向调用方的结果类型 ─────────────────────────── */

    sealed interface Loaded<out T> {
        data class Ok<T>(val value: T) : Loaded<T>

        /** 读取失败：[cause] 是原因，[backupKey] 指向被保存下来的原始数据 */
        data class Failed(val cause: Throwable, val backupKey: String?) : Loaded<Nothing>
    }

    /* ─────────────────────────── 会话 ─────────────────────────── */

    fun loadSessions(): Loaded<List<Session>> =
        loadList(K_SESSIONS, K_SESSIONS_BACKUP, SessionCodec::decode)

    // ⚠️ v0.61.21 **删掉了 `saveSessions`**：它在 main 里**一个调用者都没有**
    //    （全仓 grep 只命中定义处），也就是说 `K_SESSIONS` 那份快照早已冻结在旧格式上。
    //    留着一个"会写旧格式快照"的函数是个陷阱：谁哪天顺手调一次，
    //    就会把下面那个"删掉的对话会复活"的问题重新装回去。

    /**
     * 旧快照（`K_SESSIONS`）**消费过没有**。
     *
     * ## 它修的是什么
     * 启动时那次迁移原来只判"Room 里有没有会话"。于是用户**删光所有对话**之后，
     * Room 变空 → 下次启动又满足条件 → 那份冻结的旧快照被**再导一次** ——
     * **删掉的对话凭空复活**；而且旧快照字段有损（只搬 id/标题/时间/role/content/images），
     * 复活出来的会话还会丢掉会话级 provider、思考设置、压缩状态。
     *
     * ## 老用户兼容
     * 这个键**不存在**时读到 `false` → 照旧导一次（与改动之前的行为**完全一致**）→
     * 然后立刻写下标记 → 此后永不再导。所以升级用户不会丢数据，也不会多出一次导入。
     */
    fun legacyImported(): Boolean = prefs.getBoolean(K_LEGACY_IMPORTED, false)

    fun markLegacyImported() {
        prefs.edit().putBoolean(K_LEGACY_IMPORTED, true).apply()
    }

    /* ─────────────────────────── 人设 ─────────────────────────── */

    fun loadPersonas(): Loaded<List<Persona>> =
        loadList(K_PERSONAS, K_PERSONAS_BACKUP, PersonaCodec::decode)

    fun savePersonas(personas: List<Persona>) {
        prefs.edit().putString(K_PERSONAS, PersonaCodec.encode(personas)).apply()
    }

    /* ─────── 用户人设（v0.61.41）—— 用户在角色扮演里自己的角色 ─────── */

    fun loadUserPersonas(): Loaded<List<UserPersona>> =
        loadList(K_USER_PERSONAS, K_USER_PERSONAS_BACKUP, UserPersonaCodec::decode)

    fun saveUserPersonas(list: List<UserPersona>) {
        prefs.edit().putString(K_USER_PERSONAS, UserPersonaCodec.encode(list)).apply()
    }

    /* ─────────────────────────── 设置 ─────────────────────────── */

    fun loadSettings(): AppSettings = AppSettings(
        apiKey = prefs.getString(K_API_KEY, null).orEmpty(),
        baseUrl = prefs.getString(K_BASE_URL, null) ?: DEFAULT_BASE_URL,
        model = prefs.getString(K_MODEL, null) ?: DEFAULT_MODEL,
        thinkingEnabled = prefs.getBoolean(K_THINKING, true),
        reasoningEffort = prefs.getString(K_EFFORT, null) ?: "high",
        globalPrefixEnabled = prefs.getBoolean(K_GP_ENABLED, true),
        globalPrefix = prefs.getString(K_GP_TEXT, null) ?: DEFAULT_GLOBAL_PREFIX,
        autoMemoryEnabled = prefs.getBoolean(K_AUTO_MEMORY, true),
        thinkingCollapseEnabled = prefs.getBoolean(K_THINKING_COLLAPSE, true),
        serverUrl = prefs.getString(K_SERVER_URL, null).orEmpty(),
        telemetryEnabled = prefs.getBoolean(K_TELEMETRY, true),
        // 缺 key 时用**默认值**而不是 0 —— 老版本升级上来的用户应当拿到
        // 「标准速度 / 回车发送」，而 0 会被读成"关闭打字机"（那是另一种语义）
        typeSpeed = prefs.getInt(K_TYPE_SPEED, TYPE_SPEED_NORMAL),
        // ⚠️ 必须清洗：删掉「连发」后，老用户存的 "waifu" 若不归一，
        //    会被判成非流式（见 isNonStreamMode 的注释）—— 这就是那次故障。
        sendMode = normalizeSendMode(prefs.getString(K_SEND_MODE, null)),
        splitBubbles = prefs.getBoolean(K_SPLIT_BUBBLES, true),
        // ⚠️ v0.61.21：老用户的"发送方式"要按旧键的老值来判，否则选过「按钮发送」的人
        //    会被静默改成「回车发送」（详见 resolveEnterToSend 的注释）。
        //    ⚠️ 必须读**原始** sendMode —— 上面那行 normalizeSendMode 已经把老值洗掉了。
        enterToSend = resolveEnterToSend(
            stored = if (prefs.contains(K_ENTER_TO_SEND)) {
                prefs.getBoolean(K_ENTER_TO_SEND, true)
            } else {
                // 键不存在 = 升级上来的老用户；null 让判据去问老值
                null
            },
            rawSendMode = prefs.getString(K_SEND_MODE, null),
        ),
        // 缺 key 时给"标准"，不给 0 —— 0 会被读成"缩到看不见"（同 sendMode / typeSpeed 那条教训）
        fontScale = prefs.getFloat(K_FONT_SCALE, FONT_SCALE_STANDARD),
        emojiEnabled = prefs.getBoolean(K_EMOJI_ENABLED, true),
        // 缺 key 给 0.3 而不是 0 —— 0 会被读成"永远不发"，那与"没配过"是两件事
        emojiChance = prefs.getFloat(K_EMOJI_CHANCE, 0.3f),
        // 缺 key 给"询问"而不是"自动" —— 压缩会让缓存碎一次，
        // 默认替用户决定"自动压缩"等于默认替他花钱（同 sendMode / typeSpeed 那条教训）
        compressMode = prefs.getString(K_COMPRESS_MODE, null) ?: COMPRESS_MODE_ASK,
        compressThreshold = prefs.getFloat(K_COMPRESS_THRESHOLD, 0.70f),
        // v0.61.57 全局美化。缺 key 一律给**默认值**（不是 0）——
        // 0 在这个语境里是"全透 / 不模糊"，与"没配过"是两件事（同 sendMode / fontScale 那条教训）。
        // 背景缺 key 给 null：null 的语义正是"用内置默认渐变"。
        background = prefs.getString(K_BG, null),
        scrimEnabled = prefs.getBoolean(K_BG_SCRIM_ON, true),
        scrimAlpha = prefs.getFloat(K_BG_SCRIM_ALPHA, 0.55f),
        scrimStyle = prefs.getString(K_BG_SCRIM_STYLE, null) ?: SCRIM_PLAIN,
        topBarAlpha = prefs.getFloat(K_TOP_BAR_ALPHA, 0.72f),
        // 缺 key 给 1.0 = 不透明 = 与加这个功能之前逐像素相同（老用户零变化）
        bubbleAlpha = prefs.getFloat(K_BUBBLE_ALPHA, 1f),
    )

    fun saveSettings(s: AppSettings) {
        prefs.edit()
            .putString(K_API_KEY, s.apiKey)
            .putString(K_BASE_URL, s.baseUrl)
            .putString(K_MODEL, s.model)
            .putBoolean(K_THINKING, s.thinkingEnabled)
            .putString(K_EFFORT, s.reasoningEffort)
            .putBoolean(K_GP_ENABLED, s.globalPrefixEnabled)
            .putString(K_GP_TEXT, s.globalPrefix)
            .putBoolean(K_AUTO_MEMORY, s.autoMemoryEnabled)
            .putBoolean(K_THINKING_COLLAPSE, s.thinkingCollapseEnabled)
            .putString(K_SERVER_URL, s.serverUrl)
            .putBoolean(K_TELEMETRY, s.telemetryEnabled)
            .putInt(K_TYPE_SPEED, s.typeSpeed)
            .putString(K_SEND_MODE, s.sendMode)
            .putBoolean(K_SPLIT_BUBBLES, s.splitBubbles)
            .putBoolean(K_ENTER_TO_SEND, s.enterToSend)
            .putFloat(K_FONT_SCALE, s.fontScale)
            .putBoolean(K_EMOJI_ENABLED, s.emojiEnabled)
            .putFloat(K_EMOJI_CHANCE, s.emojiChance)
            .putString(K_COMPRESS_MODE, s.compressMode)
            .putFloat(K_COMPRESS_THRESHOLD, s.compressThreshold)
            .putString(K_BG, s.background)
            .putBoolean(K_BG_SCRIM_ON, s.scrimEnabled)
            .putFloat(K_BG_SCRIM_ALPHA, s.scrimAlpha)
            .putString(K_BG_SCRIM_STYLE, s.scrimStyle)
            .putFloat(K_TOP_BAR_ALPHA, s.topBarAlpha)
            .putFloat(K_BUBBLE_ALPHA, s.bubbleAlpha)
            .apply()
    }

    /**
     * 设备标识：**生成一次、持久化**，之后一直用它。
     *
     * 为什么不用 Android 的 `Settings.Secure.ANDROID_ID`：那个值会随恢复出厂设置、
     * 换用户而变，也涉及系统标识的读取权限边界。我们自己发一个随机串更干净，
     * 而且语义明确 —— 它只用来把**同一台设备的多次上报**归到后台的一行里。
     */
    fun deviceId(): String {
        val existing = prefs.getString(K_DEVICE_ID, null)
        if (!existing.isNullOrBlank()) return existing
        val fresh = "d_" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        prefs.edit().putString(K_DEVICE_ID, fresh).apply()
        return fresh
    }

    /* ─────────────────────── 本地用户资料（「我」是谁） ─────────────────────── */

    /**
     * 头像与昵称。
     *
     * 与 [loadSessions] / [loadPersonas] 不同，这里**不用 JSON 编解码**：
     * 两个字段而已，直接走两个 prefs key。少一层编解码就少一处"解析失败"的可能，
     * 也就不需要备份 key —— 真读不出来（类型不对），`getString` 会给默认值，
     * 结果只是回到"默认头像 + 我"，不会丢任何用户内容。
     */
    fun loadProfile(): UserProfile = UserProfile(
        nickname = prefs.getString(K_PROFILE_NAME, null).orEmpty(),
        avatarPath = prefs.getString(K_PROFILE_AVATAR, null),
        avatarSourceUrl = prefs.getString(K_PROFILE_AVATAR_SRC, null),
    )

    fun saveProfile(p: UserProfile) {
        prefs.edit()
            .putString(K_PROFILE_NAME, p.nickname)
            .putString(K_PROFILE_AVATAR, p.avatarPath)
            .putString(K_PROFILE_AVATAR_SRC, p.avatarSourceUrl)
            .apply()
    }

    /* ─────────────────── 首次打开的选择页 ─────────────────── */

    /** 那个「登录 / 注册」选择页看过没有。看过了就不再拦人（用户 2026-10-02 要求）。 */
    fun welcomeSeen(): Boolean = prefs.getBoolean(K_WELCOME_SEEN, false)

    /** 记下"这一页已经出现过" —— 用户点过登录/注册/关闭任意一个出口就调它。 */
    fun markWelcomeSeen() {
        prefs.edit().putBoolean(K_WELCOME_SEEN, true).apply()
    }

    /**
     * 老用户「补绑邮箱」提示**弹过没有**（v0.61.24.4）。
     *
     * 用户要求改成**真一次性**（原话「可能不是一次性，不是的话记得改」）——
     * 以前只靠 `rememberSaveable`，**每次冷启动都会再弹**；现在弹过即落盘、之后不再弹。
     */
    fun emailPromptSeen(): Boolean = prefs.getBoolean(K_EMAIL_PROMPT_SEEN, false)

    fun markEmailPromptSeen() {
        prefs.edit().putBoolean(K_EMAIL_PROMPT_SEEN, true).apply()
    }

    /**
     * 新用户引导弹窗（「没有 API？我帮你一键配好」）看过没有（v0.61.21）。
     *
     * ⚠️ 缺省 **false** 是有意的，而且**不会误伤老用户**：
     *    显示条件里有一条"一个人设都没有"，老用户满足不了它 ——
     *    见 [ai.yuki.chuxue.ui.shouldShowNewUserPrompt]。
     */
    fun newUserPromptSeen(): Boolean = prefs.getBoolean(K_NEW_USER_PROMPT_SEEN, false)

    /** 记下"这个弹窗已经出现过" —— 点过「创建」或「不用」都调它。 */
    fun markNewUserPromptSeen() {
        prefs.edit().putBoolean(K_NEW_USER_PROMPT_SEEN, true).apply()
    }

    /* ─────────────────────── 登录态（账号） ─────────────────────── */

    /**
     * 读登录态。
     *
     * 与 [loadSessions] / [loadPersonas] 不同，这里**不做 JSON 编解码** ——
     * 六个字段而已，直接走 prefs key。少一层编解码就少一处"解析失败"的可能；
     * 真读不出来（类型不对）也只是回到"未登录"，不会丢任何用户内容。
     */
    fun loadAuth(): AuthSnapshot = AuthSnapshot(
        uid = prefs.getString(K_AUTH_UID, null).orEmpty(),
        token = prefs.getString(K_AUTH_TOKEN, null).orEmpty(),
        refresh = prefs.getString(K_AUTH_REFRESH, null).orEmpty(),
        nickname = prefs.getString(K_AUTH_NICKNAME, null).orEmpty(),
        avatarUrl = prefs.getString(K_AUTH_AVATAR, null),
        email = prefs.getString(K_AUTH_EMAIL, null),
    )

    fun saveAuth(s: AuthSnapshot) {
        prefs.edit()
            .putString(K_AUTH_UID, s.uid)
            .putString(K_AUTH_TOKEN, s.token)
            .putString(K_AUTH_REFRESH, s.refresh)
            .putString(K_AUTH_NICKNAME, s.nickname)
            .putString(K_AUTH_AVATAR, s.avatarUrl)
            .putString(K_AUTH_EMAIL, s.email)
            .apply()
    }

    /**
     * 清登录态。
     *
     * ⚠️ **只清账号，不碰任何本地数据** —— 会话、消息、人设、记忆、API Key
     * 全部留在本机。用户登出（或换账号）不该丢掉"她记得你"。
     */
    fun clearAuth() {
        prefs.edit()
            .remove(K_AUTH_UID)
            .remove(K_AUTH_TOKEN)
            .remove(K_AUTH_REFRESH)
            .remove(K_AUTH_NICKNAME)
            .remove(K_AUTH_AVATAR)
            .remove(K_AUTH_EMAIL)
            .apply()
    }

    /* ─────────────────── 人设同步的密钥（端到端加密，只存本地） ─────────────────── */

    /**
     * 人设同步的**对称密钥**（Base64 存储）。
     *
     * ⚠️ 它是「用户密码 + uid」经 PBKDF2 派生的结果，**只存本地、从不上传** ——
     * 服务端手里只有密文（见 [PersonaCrypto] 与后端的 `/user/persona`）。
     * 登录成功后写一次，之后同设备免密也能用；换设备时重新登录、重新派生。
     *
     * ⚠️ **改密码后必须清掉它**：旧密钥解不开新密码派生的密文（解出来是 null，
     * 不会崩，但那一次同步会静默跳过）。见 [AuthViewModel.resetPassword]。
     */
    fun loadPersonaKey(): ByteArray? =
        prefs.getString(K_PERSONA_KEY, null)?.let {
            runCatching { java.util.Base64.getDecoder().decode(it) }.getOrNull()
        }

    fun savePersonaKey(key: ByteArray) {
        prefs.edit()
            .putString(K_PERSONA_KEY, java.util.Base64.getEncoder().encodeToString(key))
            .apply()
    }

    fun clearPersonaKey() {
        prefs.edit().remove(K_PERSONA_KEY).apply()
    }

    /* ─────────────────── 本地人设的账号归属（v0.61.24.3）─────────────────── */

    /**
     * 这份**本地人设库**属于哪个账号（`null` / 空 = 还没绑定过）。
     *
     * ## 为什么需要它（用户 2026-10-05 报的真 bug）
     * 人设库是**设备级**的（key 不含 uid），登出也刻意不清人设 —— 于是换账号后
     * 本地那份仍是**上一个账号**的：既会被当成当前账号的显示出来，还会被
     * **用当前账号的密钥推上云端**（一旦写入就真属新账号，**不可逆**）。
     *
     * 判据在 [ai.yuki.chuxue.data.classifyLocalOwner]（纯函数，有单测）。
     */
    fun loadPersonaOwner(): String? = prefs.getString(K_PERSONA_OWNER, null)

    /** 把本地人设**绑定**给某个账号（首次登录时绑定；隔离后改绑当前账号）。 */
    fun markPersonaOwner(uid: String) {
        if (uid.isBlank()) return
        prefs.edit().putString(K_PERSONA_OWNER, uid.trim()).apply()
    }

    /* ─────────────────── 人设同步的本地版本号 ─────────────────── */

    /**
     * 本地人设的"版本"（毫秒）。
     *
     * ⚠️ 为什么不直接用 `Persona.updatedAt` 的最大值：**置顶**（[markPersonasChanged] 也覆盖它）
     * 刻意不刷 `updatedAt`（那是"内容改过"的语义），但它确实要同步 ——
     * 所以同步需要一个**独立的**版本号。`ChatViewModel.syncPersonas` 取两者较大的那个。
     */
    fun loadPersonasRev(): Long = prefs.getLong(K_PERSONAS_REV, 0L)

    fun savePersonasRev(rev: Long) {
        prefs.edit().putLong(K_PERSONAS_REV, rev).apply()
    }

    /** 本地人设变了（新建 / 编辑 / 删除 / 置顶）→ 把版本推到当前时刻，供同步比较。 */
    fun markPersonasChanged() {
        prefs.edit().putLong(K_PERSONAS_REV, System.currentTimeMillis()).apply()
    }

    /**
     * 本设备**确知过云端状态**的账号 uid（v0.61.21 · fix2）。
     *
     * 用途只有一个：给「首次同步的合并」当判据（见 `PersonaSync.shouldMergeFirstSync`）——
     * `null` = 本设备从未与任何账号建立过云同步基线。
     * · 清数据会把它清掉 —— 那之后确实又该按"首次"对待（本地没有删除语义）；
     * · **换账号不清它**：syncedUid 非 null 时合并不生效（防拿旧账号的残留去并新账号的云端）。
     */
    fun loadPersonaSyncUid(): String? = prefs.getString(K_PERSONA_SYNC_UID, null)

    fun markPersonaSyncUid(uid: String) {
        prefs.edit().putString(K_PERSONA_SYNC_UID, uid).apply()
    }

    /* ─────────────────── 自动备份（v0.53.0） ─────────────────── */

    /**
     * 自动备份是否开启。**默认关**（v0.54.0 改；v0.53.0 曾是默认开）。
     *
     * ⚠️ 改成默认关是用户明确要求（"自动备份默认不开启"）。而且 v0.54.0 的备份内容
     *    比之前重（全量 JSON，要读一遍全部会话与记忆）—— 未经同意就周期性做这种量级的
     *    事不合适。想用的人自己打开，界面上写清了它什么时候跑、上次是什么时候。
     */
    fun loadAutoBackupEnabled(): Boolean = prefs.getBoolean(K_AUTO_BACKUP_ON, false)

    fun saveAutoBackupEnabled(v: Boolean) {
        prefs.edit().putBoolean(K_AUTO_BACKUP_ON, v).apply()
    }

    /** 最多留几份。读出来就先夹到合法区间——脏值不该流传到删除逻辑里。 */
    fun loadAutoBackupMaxFiles(): Int =
        prefs.getInt(K_AUTO_BACKUP_MAX, AutoBackupPolicy.DEFAULT_MAX_FILES)
            .coerceIn(AutoBackupPolicy.MIN_MAX_FILES, AutoBackupPolicy.MAX_MAX_FILES)

    fun saveAutoBackupMaxFiles(v: Int) {
        prefs.edit()
            .putInt(
                K_AUTO_BACKUP_MAX,
                v.coerceIn(AutoBackupPolicy.MIN_MAX_FILES, AutoBackupPolicy.MAX_MAX_FILES),
            )
            .apply()
    }

    /** 允许备份的时段（小时，0..23）。起 == 止 = 全天。 */
    fun loadAutoBackupWindowStart(): Int =
        prefs.getInt(K_AUTO_BACKUP_WIN_START, AutoBackupPolicy.DEFAULT_WINDOW_START)
            .coerceIn(0, 23)

    fun saveAutoBackupWindowStart(v: Int) {
        prefs.edit().putInt(K_AUTO_BACKUP_WIN_START, v.coerceIn(0, 23)).apply()
    }

    fun loadAutoBackupWindowEnd(): Int =
        prefs.getInt(K_AUTO_BACKUP_WIN_END, AutoBackupPolicy.DEFAULT_WINDOW_END)
            .coerceIn(0, 23)

    fun saveAutoBackupWindowEnd(v: Int) {
        prefs.edit().putInt(K_AUTO_BACKUP_WIN_END, v.coerceIn(0, 23)).apply()
    }

    /** 上次**自动**备份的时刻（0 = 从没自动备份过）。手动备份不写它。 */
    fun loadLastAutoBackupAt(): Long = prefs.getLong(K_LAST_AUTO_BACKUP, 0L)

    fun saveLastAutoBackupAt(at: Long) {
        prefs.edit().putLong(K_LAST_AUTO_BACKUP, at).apply()
    }

    /* ─────────────────── 看板的滚动统计（v0.54.0） ─────────────────── */

    /** 近 N 轮的原始 JSON；解析交给 [BoardStats]（这里只做存取的搬运）。 */
    fun loadBoardTurns(): String? = prefs.getString(K_BOARD_TURNS, null)

    fun saveBoardTurns(raw: String) {
        prefs.edit().putString(K_BOARD_TURNS, raw).apply()
    }

    /** 各「服务商 + 模型」的累计用量（原始 JSON）。 */
    fun loadModelUsage(): String? = prefs.getString(K_MODEL_USAGE, null)

    fun saveModelUsage(raw: String) {
        prefs.edit().putString(K_MODEL_USAGE, raw).apply()
    }

    /* ─────────────────────── 自动记忆提取的节流时间戳 ─────────────────────── */

    /**
     * 上次自动提取记忆的时间（毫秒；0 = 从未提取）。
     *
     * 这是**成本护栏**的一半：提取要花用户自己的 API 额度，两次之间必须有间隔
     * （另一半见 [ai.yuki.chuxue.data.memory.MemoryExtractionScheduler] 的节流判定）。
     *
     * ⚠️ 存 SharedPreferences 而不是内存变量 —— 进程重启后节流不能失效，
     * 否则用户反复开关 App 就能把提取次数刷上去，账单跟着涨。
     */
    fun lastExtractAt(): Long = prefs.getLong(K_LAST_EXTRACT, 0L)

    fun markExtracted(at: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(K_LAST_EXTRACT, at).apply()
    }

    /**
     * 上次提取时「最近会话的更新时刻」。
     *
     * 这是**第二个节流维度**，与 [lastExtractAt] 正交：
     * 一个管"多久最多跑一次"（时间），一个管"值不值得跑"（有没有新内容）。
     * 少了它，每 10 分钟就会把同一批消息再提取一遍 —— 纯浪费用户的额度。
     */
    fun lastExtractedSessionAt(): Long = prefs.getLong(K_LAST_EXTRACT_SESSION, 0L)

    fun markExtractedSessionAt(sessionUpdatedAt: Long) {
        prefs.edit().putLong(K_LAST_EXTRACT_SESSION, sessionUpdatedAt).apply()
    }

    /* ─────────── 「Ta 主动来找我」取件（v0.61.40） ─────────── */

    /**
     * 上次去云端**取件**（看 Ta 有没有主动说话）的时刻（毫秒；0 = 从没取过）。
     *
     * 这是**取件心跳**的节流阀（`ProactiveFetchPolicy`，默认 30 分钟一次）——
     * 与"Ta 要不要说话"毫无关系（那是心潮情绪状态机的职责）。
     * ⚠️ 存 SharedPreferences 而不是内存变量：进程重启后节流不能失效，
     *    否则用户反复开关 App 会反复去打服务器。
     */
    fun lastProactiveFetchAt(): Long = prefs.getLong(K_PROACTIVE_FETCH_AT, 0L)

    fun markProactiveFetchAt(at: Long) {
        prefs.edit().putLong(K_PROACTIVE_FETCH_AT, at).apply()
    }

    /**
     * 某人设**上次已经看到**的最新消息时间（ISO 串；空串 = 还没看过）。
     *
     * 取件时作为 `?since=` 传给服务端 → 只回比它新的消息。
     * ⚠️ 直接存服务端给的 ISO 串、**不解析成时间戳**：服务端自己就是按字符串
     *    `>` 比较的（`str(m.get("at")) > since`），透传才能保证两侧同一套口径。
     */
    fun lastProactiveSeenAt(personaId: String): String =
        prefs.getString(K_PROACTIVE_SEEN_PREFIX + personaId, "") ?: ""

    fun markProactiveSeenAt(personaId: String, at: String) {
        prefs.edit().putString(K_PROACTIVE_SEEN_PREFIX + personaId, at).apply()
    }

    /** 是否有过损坏数据被保存下来（供设置页提示用户） */
    fun hasCorruptBackup(): Boolean =
        prefs.contains(K_SESSIONS_BACKUP) || prefs.contains(K_PERSONAS_BACKUP)

    fun corruptBackupPreview(): String? =
        prefs.getString(K_SESSIONS_BACKUP, null)?.take(500)

    /* ─────────────────────────── 内部 ─────────────────────────── */

    private fun <T> loadList(
        key: String,
        backupKey: String,
        decode: (String) -> List<T>,
    ): Loaded<List<T>> {
        val raw = prefs.getString(key, null)
        if (raw.isNullOrBlank()) return Loaded.Ok(emptyList())

        return try {
            Loaded.Ok(decode(raw))
        } catch (e: Throwable) {
            // 关键：把原始数据另存，绝不让它在下次保存时被静默覆盖掉
            prefs.edit().putString(backupKey, raw).apply()
            Loaded.Failed(e, backupKey)
        }
    }

    /* ─────────────────── 连接分组（v0.51.0）─────────────────── */

    /**
     * 读全部连接分组。
     *
     * ## ⚠️ 首次读取时的**迁移**
     * 升上来的用户 `AppSettings` 里已经有一套 apiKey/baseUrl/model，
     * 而 `K_GROUPS` 还不存在。不迁移的话他们打开连接设置会看到**空的**，
     * 但聊天还在用旧配置 —— 两边对不上。
     *
     * 所以：**没存过分组的 key** 时，用旧配置造一个「默认」分组。
     *
     * ⚠️ 判据是 `contains(K_GROUPS)` 而不是"解码结果为空"：
     * 用户**主动删光**所有分组时存的是 `"[]"`，那时不该再给他造一个回来。
     */
    fun loadGroups(): List<ProviderGroup> {
        if (!prefs.contains(K_GROUPS)) {
            val migrated = ProviderGroups.migrateLegacy(loadSettings(), System.currentTimeMillis())
            if (migrated.isNotEmpty()) {
                saveGroups(migrated)
                prefs.edit().putString(K_ACTIVE_GROUP, migrated[0].id).apply()
            }
            return migrated
        }
        return ProviderGroups.decode(prefs.getString(K_GROUPS, null))
    }

    fun saveGroups(groups: List<ProviderGroup>) {
        prefs.edit().putString(K_GROUPS, ProviderGroups.encode(groups)).apply()
    }

    /**
     * 「我发过的话」（v0.61.21）。
     *
     * ⚠️ 用 SharedPreferences 而不是 Room：这个项目有一条硬规矩 ——
     *    **加一张表就要加一次 Room 迁移，而迁移是唯一会损坏用户聊天记录的操作**。
     *    为一份"说过什么"的清单付那个代价不划算（`ProviderGroups` 同一条理由）。
     */
    fun loadSendHistory(): List<String> = SendHistory.decode(prefs.getString(K_SEND_HISTORY, null))

    fun saveSendHistory(history: List<String>) {
        prefs.edit().putString(K_SEND_HISTORY, SendHistory.encode(history)).apply()
    }

    /** 当前分组 id（**没设过就是空串**，由 `ProviderGroups.resolveActive` 退回第一个）。 */
    fun activeGroupId(): String = prefs.getString(K_ACTIVE_GROUP, null).orEmpty()

    fun setActiveGroupId(id: String) {
        prefs.edit().putString(K_ACTIVE_GROUP, id).apply()
    }

    private companion object {
        const val K_GROUPS = "providerGroups_v1"
        const val K_ACTIVE_GROUP = "activeProviderGroupId"
        /** 「我发过的话」（v0.61.21，⑤-A 第 7 条）—— 与 [SendHistory] 配套。 */
        const val K_SEND_HISTORY = "sendHistory_v1"

        /** 旧会话快照消费过没有（v0.61.21）—— 见 [legacyImported]。 */
        const val K_LEGACY_IMPORTED = "legacySessionsImported"
        const val K_SESSIONS = "sessions_v3"
        const val K_PERSONAS = "personas_v3"
        /** 用户人设（v0.61.41）—— 与 personas 分立（它是用户自己的角色，不是 AI 角色）。 */
        const val K_USER_PERSONAS = "userPersonas_v1"
        const val K_SESSIONS_BACKUP = "sessions_corrupt_backup"
        const val K_PERSONAS_BACKUP = "personas_corrupt_backup"
        const val K_USER_PERSONAS_BACKUP = "userPersonas_corrupt_backup"

        const val K_API_KEY = "apiKey"
        const val K_BASE_URL = "baseUrl"
        const val K_MODEL = "model"
        const val K_THINKING = "thinkingEnabled"
        const val K_EFFORT = "reasoningEffort"
        const val K_GP_ENABLED = "globalPrefixEnabled"
        const val K_GP_TEXT = "globalPrefixText"

        const val K_AUTO_MEMORY = "autoMemoryEnabled"
        const val K_THINKING_COLLAPSE = "thinkingCollapseEnabled"
        const val K_SERVER_URL = "serverUrl"
        const val K_TELEMETRY = "telemetryEnabled"
        const val K_TYPE_SPEED = "typeSpeed"
        const val K_SEND_MODE = "sendMode"
        const val K_SPLIT_BUBBLES = "splitBubbles"
        const val K_ENTER_TO_SEND = "enterToSend"
        const val K_FONT_SCALE = "fontScale"
        const val K_EMOJI_ENABLED = "emojiEnabled"
        const val K_EMOJI_CHANCE = "emojiChance"
        const val K_COMPRESS_MODE = "compressMode"
        const val K_COMPRESS_THRESHOLD = "compressThreshold"

        // ── v0.61.57 全局美化（背景 / 遮罩 / 顶栏透明度）──
        // ⚠️ 这四列走的是**逐字段**读写（不是 JSON），所以每加一个字段
        //    必须同时改 loadSettings / saveSettings / 这里 —— 漏一处的表现是
        //    "设置能改、重启就丢"（编译不会报错，Kotlin 默认参数兜着呢）。
        const val K_BG = "globalBackground"
        const val K_BG_SCRIM_ON = "globalScrimEnabled"
        const val K_BG_SCRIM_ALPHA = "globalScrimAlpha"
        const val K_BG_SCRIM_STYLE = "globalScrimStyle"
        const val K_TOP_BAR_ALPHA = "topBarAlpha"
        const val K_BUBBLE_ALPHA = "bubbleAlpha"
        const val K_DEVICE_ID = "telemetryDeviceId"
        const val K_LAST_EXTRACT = "lastMemoryExtractAt"
        const val K_LAST_EXTRACT_SESSION = "lastExtractedSessionAt"
        /** 「Ta 主动来找我」取件心跳（v0.61.40）：全局节流时间 + 每人设已读水位（前缀+personaId）。 */
        const val K_PROACTIVE_FETCH_AT = "lastProactiveFetchAt"
        const val K_PROACTIVE_SEEN_PREFIX = "lastProactiveSeenAt_"
        const val K_PROFILE_NAME = "userProfileName"
        const val K_PROFILE_AVATAR = "userProfileAvatar"

        /**
         * 本地头像文件**对应的是哪个服务器 URL**（v0.59.0）。
         *
         * ## 为什么必须记这一笔
         * 之前拉取头像的判据是"本地没图才拉"，结果：**在别的设备换了头像，
         * 这台永远看不到**（本地有图 → 不再拉）。
         * 记下来源 URL 之后，判据变成"本地的来源 ≠ 服务器当前的" → 该拉才拉。
         *
         * 老数据没有这一栏 → null → 首次启动会拉一次，正好把历史遗留补齐。
         */
        const val K_PROFILE_AVATAR_SRC = "userProfileAvatarSourceUrl"

        /**
         * 首次打开的那个「登录 / 注册」选择页**是否已经出现过**（v0.60.0）。
         *
         * 用户要求：**只在第一次打开时出现，之后不再强制**。
         * 所以冷启动的判据不是"有没有登录"，而是"这一页看过没有"——
         * 看过之后即使没登录也直接进主页（这个 App 本来就该能离线用）。
         */
        const val K_WELCOME_SEEN = "welcomeSeen"
        const val K_NEW_USER_PROMPT_SEEN = "newUserPromptSeen"

        /**
         * 老用户「补绑邮箱」提示**弹过没有**（v0.61.24.4）。
         *
         * ⚠️ 缺省 false 是有意的：**没弹过就该弹**（这个提醒能救回账号，
         *    最该看见它的恰恰是"邮箱为空"的老用户）。
         */
        const val K_EMAIL_PROMPT_SEEN = "emailPromptSeen"
        const val K_AUTH_UID = "authUid"
        const val K_AUTH_TOKEN = "authToken"
        const val K_AUTH_REFRESH = "authRefresh"
        const val K_AUTH_NICKNAME = "authNickname"
        const val K_AUTH_AVATAR = "authAvatarUrl"
        const val K_AUTH_EMAIL = "authEmail"
        const val K_PERSONA_KEY = "personaSyncKey"
        const val K_PERSONAS_REV = "personasSyncRev"
        const val K_PERSONA_SYNC_UID = "personaSyncUid"

        /**
         * 这份**本地人设库**属于哪个账号（v0.61.24.3）。
         *
         * ⚠️ 与 [K_PERSONA_SYNC_UID] 不是一回事：那个记"上次**同步**的账号"（首次合并判据用），
         *    这个记"库里的**数据是谁的**"（换账号时的隔离判据用）。
         */
        const val K_PERSONA_OWNER = "personaOwnerUid"

        // ── 自动备份（v0.53.0）──
        const val K_AUTO_BACKUP_ON = "autoBackupEnabled"
        const val K_AUTO_BACKUP_MAX = "autoBackupMaxFiles"
        const val K_AUTO_BACKUP_WIN_START = "autoBackupWindowStart"
        const val K_AUTO_BACKUP_WIN_END = "autoBackupWindowEnd"
        const val K_LAST_AUTO_BACKUP = "lastAutoBackupAt"

        // ── 看板的滚动统计（v0.54.0）──
        // ⚠️ 刻意**不进 Room**：它们是可丢的观测量（趋势、每模型用量），
        //    而一次 schema 迁移的风险是"损坏用户的聊天记录"。见 BoardStats 的类注释。
        const val K_BOARD_TURNS = "boardTurns"
        const val K_MODEL_USAGE = "modelUsage"
    }
}
