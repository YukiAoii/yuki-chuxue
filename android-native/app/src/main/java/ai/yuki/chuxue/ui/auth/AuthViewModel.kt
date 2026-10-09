package ai.yuki.chuxue.ui.auth

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ai.yuki.chuxue.data.AuthApi
import ai.yuki.chuxue.data.AuthSnapshot
import ai.yuki.chuxue.data.PasswordPolicy
import ai.yuki.chuxue.data.PersonaCrypto
import ai.yuki.chuxue.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 账号状态（开发文档 §32 的认证流程）。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * 强制登录，但**不让服务器成为 App 的单点故障**
 * ═══════════════════════════════════════════════════════════════════════════
 * 用户要求「强制检测注册登录」。这个要求有一个必须处理的副作用：
 * **服务器连不上的时候，App 会不会打不开？**
 *
 * 我的处理是分两种情况，它们的判据不同：
 *
 * | 情况 | 本地登录态 | 行为 |
 * |---|---|---|
 * | 首次使用 / 已登出 | 无 | **必须登录**（这是用户要的"强制"） |
 * | 已登录过，这次服务器不可达 | 有 | **照常进 App**，只在「我的」页提示"登录可能已过期" |
 *
 * 理由：本地那串令牌是**上次服务器亲口发的**。只要它还在，用户就有资格用这台手机上的
 * 数据 —— 而那些数据（对话、人设、记忆）本来就全在本机，与服务器毫无关系。
 * 反过来，如果因为服务器挂了就把用户锁在登录页，他会**连自己写的对话都看不到**，
 * 那是不可接受的。
 *
 * 真正需要服务器的是：**换设备**、**改昵称**、**找回密码**。那几件事失败时老实报错即可。
 *
 * ⚠️ [staleSession] 表达的就是"本地登录态可能过期了"。它**不会**踢人，
 * 只驱动一个提示 + 一个"重新登录"入口。
 */
class AuthViewModel(app: Application) : AndroidViewModel(app) {

    private val store = Store(app)

    private val _auth = MutableStateFlow(store.loadAuth())
    val auth: StateFlow<AuthSnapshot> = _auth.asStateFlow()

    /** 正在请求服务器（按钮转圈、防重复提交）。 */
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** 给用户看的中文错误。 */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /**
     * 给用户看的**中性回话**（不是错误，比如"验证码已发到邮箱"）。
     *
     * ⚠️ 为什么要有这条通道：发验证码的三种结局（真发了 / 邮件没配 / 发失败）
     * 在界面上**长得一模一样**（都是"什么都没变"），不回话用户会以为按钮没点到。
     * 三个调用方（注册、绑定邮箱、改密码）共用这一条，免得各自记一套文案。
     */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    /** 本地登录态可能已过期（凭它显示提示，**不踢人**）。 */
    private val _staleSession = MutableStateFlow(false)
    val staleSession: StateFlow<Boolean> = _staleSession.asStateFlow()

    fun dismissError() { _error.value = null }

    fun dismissNotice() { _notice.value = null }

    /**
     * 首次打开的那个选择页看过没有（v0.60.0）。
     *
     * 冷启动要据此决定"拦不拦人"。放在 VM 上而不是让 MainActivity 自己 new 一个
     * `Store` —— VM 本来就握着 store，少一处重复构造。
     */
    fun welcomeSeen(): Boolean = store.welcomeSeen()

    /** 记下选择页已经出现/已被处理过（点登录、点注册、点关闭都算）。 */
    fun markWelcomeSeen() = store.markWelcomeSeen()

    /** 老用户「补绑邮箱」提示弹过没有（v0.61.24.4：用户要求真一次性）。 */
    fun emailPromptSeen(): Boolean = store.emailPromptSeen()
    fun markEmailPromptSeen() = store.markEmailPromptSeen()

    /* ══════════════ 注册（两段式）/ 登录 / 登出 ══════════════ */

    /**
     * 注册**第一段**完成后、第二段开始前，手上握着的东西（v0.59.0 两段式）。
     *
     * ⚠️ 它**不落盘**：注册中途杀进程就要重来一遍。可以接受 ——
     *    服务端给半成品行留了 24 小时（同邮箱/同昵称回来**原地复用、uid 不变**），
     *    所以重来不会换 uid、也不会把昵称永久占住。
     */
    data class PendingRegistration(
        val uid: String,
        val token: String,
        val refresh: String,
        val nickname: String,
        val email: String,
    )

    private val _pendingReg = MutableStateFlow<PendingRegistration?>(null)

    /** 注册第二屏要用：展示 uid、以及调 `register/finish` 的令牌。 */
    val pendingReg: StateFlow<PendingRegistration?> = _pendingReg.asStateFlow()

    /**
     * 注册**第一段**：昵称 + 邮箱 + 验证码 → 拿到 uid 与令牌，进第二屏。
     *
     * ⚠️ **刻意不调用 `adopt()`** —— 这个账号还没设密码（服务端 `status='pending'`，
     *    任何需要登录的接口都会 401）。一旦 adopt，冷启动那处
     *    `if (isLoggedIn) MAIN else AUTH` 就会把一个没有密码的账号路由进主界面。
     */
    fun registerStart(nickname: String, email: String, code: String, onStarted: () -> Unit) {
        if (_busy.value) return
        val nick = nickname.trim()
        val mail = email.trim().lowercase()
        when {
            nick.isEmpty() -> { _error.value = "请填写昵称"; return }
            nick.length > 20 -> { _error.value = "昵称最多 20 个字"; return }
            mail.isEmpty() -> { _error.value = "请填写邮箱"; return }
            "@" !in mail -> { _error.value = "邮箱格式不对"; return }
            code.isBlank() -> { _error.value = "请填写验证码"; return }
        }

        _busy.value = true
        _error.value = null
        viewModelScope.launch {
            when (val r = AuthApi.registerStart(nick, mail, code.trim())) {
                is AuthApi.Outcome.Ok -> {
                    _pendingReg.value = PendingRegistration(
                        uid = r.value.account.uid,
                        token = r.value.token,
                        refresh = r.value.refresh,
                        nickname = nick,
                        email = mail,
                    )
                    onStarted()
                }
                is AuthApi.Outcome.Fail -> _error.value = r.message
            }
            _busy.value = false
        }
    }

    /**
     * 注册**第二段**：设密码（+ 可选头像）→ 账号激活。
     *
     * 到这里才 `adopt()` 并缓存人设密钥 —— 密码是在这一步才知道的，
     * 而人设密钥是用「密码 + uid」派生的（见 [cachePersonaKey]）。
     */
    fun registerFinish(password: String, avatarUrl: String?, onSuccess: () -> Unit) {
        if (_busy.value) return
        val pending = _pendingReg.value
        if (pending == null) {
            _error.value = "注册信息丢了，请重新开始"
            return
        }
        // 先做本地校验，省一次往返；服务端仍会再校验一遍（它才是权威）
        val pwErr = PasswordPolicy.errorOf(password)
        if (pwErr != null) { _error.value = pwErr; return }

        _busy.value = true
        _error.value = null
        viewModelScope.launch {
            when (val r = AuthApi.registerFinish(pending.token, password, avatarUrl)) {
                is AuthApi.Outcome.Ok -> {
                    // finish 只返回 {uid, user}，令牌沿用第一段那一对
                    adoptSession(pending, r.value)
                    cachePersonaKey(password, r.value.uid)
                    _pendingReg.value = null
                    onSuccess()
                }
                is AuthApi.Outcome.Fail -> _error.value = r.message
            }
            _busy.value = false
        }
    }

    /** 放弃这次注册（用户从第二屏退回去）。 */
    /**
     * **放弃注册**（v0.60.0）。
     *
     * 除了清掉本地的待注册态，还要**告诉服务端把那个占位账号删掉** ——
     * 用户 2026-10-02：「中途退出就不给他创建账号」。
     *
     * ⚠️ 用 `viewModelScope.launch` 发出去就走，不等结果、失败静默：
     *    用户已经离开这一页了，不该因为一次清理请求失败把他拦回来。
     *    （服务端本来也有 24 小时回收兜底，这次只是"提前一点"。）
     */
    private val _emailTaken = MutableStateFlow(false)

    /** 这个邮箱已被别的账号注册（注册页边输边提示用）。 */
    val emailTaken: StateFlow<Boolean> = _emailTaken.asStateFlow()

    /**
     * 查一次邮箱是否已被占用（v0.60.0）。
     *
     * ⚠️ **防抖由调用方负责**（界面里 `LaunchedEffect(email) { delay(500); ... }`）——
     *    放在这里做会让"输入"和"查询"耦合在一起，测试也不好写。
     * ⚠️ 查不动（网络失败）时不提示，别误伤。
     */
    suspend fun checkEmailTaken(email: String) {
        val e = email.trim()
        if (e.isEmpty() || "@" !in e) {
            _emailTaken.value = false
            return
        }
        _emailTaken.value = when (val r = AuthApi.emailExists(e)) {
            is AuthApi.Outcome.Ok -> r.value
            is AuthApi.Outcome.Fail -> false
        }
    }

    fun abandonRegistration() {
        val token = _pendingReg.value?.token
        _pendingReg.value = null
        if (token.isNullOrBlank()) return
        viewModelScope.launch { runCatching { AuthApi.registerCancel(token) } }
    }

    /**
     * 注册第二屏选的头像 **只落本地文件 + 本地资料**（v0.59.0）。
     *
     * ⚠️ 为什么注册中**不**传服务器：那一刻账号还没设密码（`status='pending'`），
     *    服务端的 `require_user` 会把它的所有请求挡成 401 —— 这是**刻意的护栏**
     *    （否则一个没设密码的占位账号就能传图、改资料）。
     *    所以头像走**本地优先**：激活之后由 ChatViewModel 的 `pushAvatarIfMissing`
     *    在启动时补传一份到服务器。这与全站"本地那张永远优先、服务器只做兜底"一致。
     */
    fun saveLocalAvatar(path: String) {
        store.saveProfile(store.loadProfile().copy(avatarPath = path))
    }

    fun register(nickname: String, password: String, onSuccess: () -> Unit) {
        if (_busy.value) return
        val nick = nickname.trim()
        val pwErr = PasswordPolicy.errorOf(password)
        if (pwErr != null) { _error.value = pwErr; return }
        when {
            nick.isEmpty() -> { _error.value = "请填写昵称"; return }
            nick.length > 20 -> { _error.value = "昵称最多 20 个字"; return }
        }

        _busy.value = true
        _error.value = null
        viewModelScope.launch {
            when (val r = AuthApi.register(nick, password)) {
                is AuthApi.Outcome.Ok -> {
                    adopt(r.value)
                    cachePersonaKey(password, r.value.account.uid)
                    onSuccess()
                }
                is AuthApi.Outcome.Fail -> _error.value = r.message
            }
            _busy.value = false
        }
    }

    fun login(account: String, password: String, onSuccess: () -> Unit) {
        if (_busy.value) return
        val acc = account.trim()
        when {
            acc.isEmpty() -> { _error.value = "请填写 UID"; return }
            password.isEmpty() -> { _error.value = "请填写密码"; return }
        }

        _busy.value = true
        _error.value = null
        viewModelScope.launch {
            when (val r = AuthApi.login(acc, password)) {
                is AuthApi.Outcome.Ok -> {
                    adopt(r.value)
                    cachePersonaKey(password, r.value.account.uid)
                    onSuccess()
                }
                is AuthApi.Outcome.Fail -> _error.value = r.message
            }
            _busy.value = false
        }
    }

    /**
     * 登出。
     *
     * **本地一律清干净，哪怕服务器请求失败** —— 用户点了登出，就必须真的登出。
     * 服务器那边删不掉令牌只是它自己的账没对上，不能因此把用户留在登录态里。
     */
    fun logout() {
        val token = _auth.value.token
        _auth.value = AuthSnapshot()
        store.clearAuth()
        // 人设同步密钥与 uid 绑定：登出后本地不再有账号，留着它只会是残留。
        store.clearPersonaKey()
        _staleSession.value = false
        viewModelScope.launch { runCatching { AuthApi.logout(token) } }
    }

    /* ══════════════ 资料 ══════════════ */

    fun updateNickname(nickname: String, onDone: (String?) -> Unit = {}) {
        val nick = nickname.trim()
        if (nick.isEmpty()) { onDone("昵称不能为空"); return }
        val token = _auth.value.token
        if (token.isBlank()) { onDone("请先登录"); return }
        // ⚠️ v0.61.21 补：这里是**唯一**缺防重入守卫的写操作（login/register/bindEmail/
        //    resetPassword 都有）。没有它，连点两下会发两条改名请求，
        //    而且 `_busy` 会被后发的那条提前复位。
        if (_busy.value) return

        _busy.value = true
        viewModelScope.launch {
            when (val r = AuthApi.updateNickname(token, nick)) {
                is AuthApi.Outcome.Ok -> {
                    val next = _auth.value.copy(nickname = r.value.nickname)
                    _auth.value = next
                    store.saveAuth(next)
                    onDone(null)
                }
                is AuthApi.Outcome.Fail -> onDone(r.message)
            }
            _busy.value = false
        }
    }

    /* ══════════════ 注册步骤 3：绑定邮箱（§32.7） ══════════════ */

    /**
     * 发验证码。
     *
     * @param onSent **只有真的把邮件发出去了**才会回调。
     *   ⚠️ 判据是后端回的 `sent`，**不是** HTTP 200 —— 邮件服务没配时后端回的是
     *   `ok:true` + `sent:false`。调用方据此才开始读秒；其余情况只回话、不回调，
     *   否则用户会对着一个倒数的按钮等一封永远不来的邮件（用户 2026-10-05 报的）。
     */
    fun sendEmailCode(email: String, onSent: () -> Unit = {}) {
        val addr = email.trim()
        when {
            addr.isBlank() -> { _error.value = "请填邮箱"; return }
            "@" !in addr -> { _error.value = "邮箱格式看着不太对"; return }
        }
        if (_busy.value) return

        _busy.value = true
        _error.value = null
        viewModelScope.launch {
            when (val r = AuthApi.sendEmailCode(addr)) {
                is AuthApi.Outcome.Ok -> {
                    // 回话归 [notice]（它就一条通道，三个调用方共用）；
                    // 读秒归 [onSent]，**且只在真发出去时**才叫。
                    _notice.value = r.value.message
                    if (r.value.sent) onSent()
                }
                is AuthApi.Outcome.Fail -> _error.value = r.message
            }
            _busy.value = false
        }
    }

    /**
     * 绑定邮箱（注册的收尾）。
     *
     * 成功后把 email 写进本地登录态 —— 「我的」页要显示它。下次冷启动
     * [verifyOnLaunch] 会拿服务器那份覆盖回来，两边同源，不会打架。
     */
    fun bindEmail(email: String, code: String, onDone: (Boolean) -> Unit) {
        val addr = email.trim()
        val c = code.trim()
        when {
            addr.isBlank() -> { _error.value = "请填邮箱"; return }
            c.isBlank() -> { _error.value = "请填验证码"; return }
        }
        val token = _auth.value.token
        if (token.isBlank()) { _error.value = "登录态丢了，请重新登录再绑"; return }
        if (_busy.value) return

        _busy.value = true
        _error.value = null
        viewModelScope.launch {
            when (val r = AuthApi.bindEmail(token, addr, c)) {
                is AuthApi.Outcome.Ok -> {
                    val next = _auth.value.copy(email = r.value.email ?: addr)
                    _auth.value = next
                    store.saveAuth(next)
                    onDone(true)
                }
                is AuthApi.Outcome.Fail -> {
                    _error.value = r.message
                    onDone(false)
                }
            }
            _busy.value = false
        }
    }

    /**
     * 发验证码 —— **改密码 / 找回密码**用。
     *
     * 与 [sendEmailCode] 唯一的区别是接口路径（后端按 purpose 区分文案）。
     * 成功照样回一句话（"验证码已发到邮箱" 或 "邮件服务没配、码在管理后台"）。
     */
    fun sendPasswordCode(uid: String, onSent: () -> Unit = {}) {
        // v0.60.0：找回密码按 UID 定位 —— 判据从"含 @"换成"全是数字"
        val u = uid.trim()
        when {
            u.isBlank() -> { _error.value = "请填 UID"; return }
            !u.all { it.isDigit() } -> { _error.value = "UID 是一串数字"; return }
        }
        if (_busy.value) return

        _busy.value = true
        _error.value = null
        viewModelScope.launch {
            when (val r = AuthApi.sendPasswordCode(u)) {
                // 与 sendEmailCode 同一套分工：回话走 notice，读秒走 onSent 且只在真发出去时
                is AuthApi.Outcome.Ok -> {
                    _notice.value = r.value.message
                    if (r.value.sent) onSent()
                }
                is AuthApi.Outcome.Fail -> _error.value = r.message
            }
            _busy.value = false
        }
    }

    /**
     * 用邮箱验证码改密码（设置页的「修改密码」与登录页的「忘记密码」共用）。
     *
     * ⚠️ 成功后**本地登录态也被清掉** —— 后端改密码时会把该用户的令牌全删
     * （见 `backend/main.py` 的 password/reset），本地再留着一个死令牌，
     * 后面每个请求都会 401。所以调用方在 [onDone] 收到 true 时必须引导重新登录。
     */
    fun resetPassword(
        uid: String,
        code: String,
        newPassword: String,
        onDone: (Boolean) -> Unit,
    ) {
        val u = uid.trim()
        val c = code.trim()
        when {
            u.isBlank() -> { _error.value = "请填 UID"; return }
            !u.all { it.isDigit() } -> { _error.value = "UID 是一串数字"; return }
            c.isBlank() -> { _error.value = "请填验证码"; return }
            // ⚠️ 原来这里写的是 `length < 6` —— 与 `PasswordPolicy.MIN = 8` **冲突**：
            //    6~7 位的密码能过这一关，再被服务端打回来，用户看到的是网络错误而不是"太短"。
            //    统一走 PasswordPolicy，两边只有一个真相。
            PasswordPolicy.errorOf(newPassword) != null ->
                { _error.value = PasswordPolicy.errorOf(newPassword); return }
        }
        if (_busy.value) return

        _busy.value = true
        _error.value = null
        viewModelScope.launch {
            when (val r = AuthApi.resetPassword(u, c, newPassword)) {
                is AuthApi.Outcome.Ok -> {
                    // 令牌已被服务器作废 —— 本地那份必须一起清
                    _auth.value = AuthSnapshot()
                    store.clearAuth()
                    // ⚠️ 密码变了 → 派生密钥也变了，旧密钥必须作废（否则解密一律失败）
                    store.clearPersonaKey()
                    _staleSession.value = false
                    onDone(true)
                }
                is AuthApi.Outcome.Fail -> {
                    _error.value = r.message
                    onDone(false)
                }
            }
            _busy.value = false
        }
    }

    /* ══════════════ 启动校验（静默，不踢人） ══════════════ */

    /**
     * App 启动时**在后台**核一遍登录态。**不阻塞任何界面**。
     *
     * 三级降级：
     *   1. `/me` 成功 → 刷新本地资料（昵称可能被你在别处改过）
     *   2. `/me` 401 → 用 refresh 换一对新令牌；成功则继续
     *   3. refresh 也失败（或网络不通）→ **保留本地登录态**，只标记 [staleSession]
     *
     * 第 3 步是关键：不踢人。理由见类注释。
     */
    fun verifyOnLaunch() {
        val current = _auth.value
        if (!current.isLoggedIn) return

        viewModelScope.launch {
            when (val r = AuthApi.me(current.token)) {
                is AuthApi.Outcome.Ok -> {
                    val next = current.copy(
                        nickname = r.value.nickname.ifBlank { current.nickname },
                        avatarUrl = r.value.avatarUrl ?: current.avatarUrl,
                        email = r.value.email,
                    )
                    _auth.value = next
                    store.saveAuth(next)
                    _staleSession.value = false
                }
                is AuthApi.Outcome.Fail -> tryRefresh(current)
            }
        }
    }

    private suspend fun tryRefresh(current: AuthSnapshot) {
        if (current.refresh.isBlank()) {
            _staleSession.value = true
            return
        }
        when (val r = AuthApi.refresh(current.refresh)) {
            is AuthApi.Outcome.Ok -> { adopt(r.value); _staleSession.value = false }
            // ⚠️ 这里**不清登录态** —— 详见类注释第 3 步
            is AuthApi.Outcome.Fail -> _staleSession.value = true
        }
    }

    /** 把一次成功的认证结果落到本地。 */
    private fun adopt(session: AuthApi.Session) {
        val next = AuthSnapshot(
            uid = session.account.uid,
            token = session.token,
            refresh = session.refresh,
            nickname = session.account.nickname,
            avatarUrl = session.account.avatarUrl,
            email = session.account.email,
        )
        _auth.value = next
        store.saveAuth(next)
        _staleSession.value = false
        _error.value = null
    }

    /**
     * 注册第二段专用：`register/finish` **只回 `{uid, user}`**，不回令牌。
     *
     * 令牌是第一段（`register/start`）发的那一对，一直在 [PendingRegistration] 里握着 ——
     * 所以这里把它取回来拼成完整的登录态。`finish` 成功后不会换令牌：
     * 服务端只改 `password_hash` / `status` / `avatar_url`，`user_tokens` 一行没动。
     */
    private fun adoptSession(pending: PendingRegistration, account: AuthApi.Account) {
        val next = AuthSnapshot(
            uid = account.uid,
            token = pending.token,
            refresh = pending.refresh,
            nickname = account.nickname,
            avatarUrl = account.avatarUrl,
            email = account.email,
        )
        _auth.value = next
        store.saveAuth(next)
        _staleSession.value = false
        _error.value = null
    }

    /* ── 邮箱验证码登录（v0.61.24.5，用户 2026-10-05 要求）──
     *
     * ⚠️ 它与密码登录有一处**本质不同**：拿不到明文密码。
     *    而人设同步密钥是「密码 + uid」派生的 → 登录成功后要**请用户设一次密码**
     *    （用户选的方案 (a)），否则云端人设在本机解不开。
     */

    /** 等用户设密钥时暂存的 uid（验证码登录成功后写入）。 */
    private var pendingKeyUid: String? = null

    /** 邮箱验证码登录 · 第一步：发码（只有后端真发出去了才回调 onSent 开始读秒）。 */
    fun sendLoginCode(email: String, onSent: () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        _error.value = null
        viewModelScope.launch {
            val r = AuthApi.sendLoginCode(email.trim())
            if (r is AuthApi.Outcome.Ok) {
                _notice.value = r.value.message
                if (r.value.sent) onSent()
            } else if (r is AuthApi.Outcome.Fail) {
                _error.value = r.message
            }
            _busy.value = false
        }
    }

    /**
     * 邮箱验证码登录 · 第二步：用码换会话。
     *
     * ⚠️ 本机**还没有人设密钥**时**不直接进 App**，而是回调 `onNeedSetKey` ——
     *    让界面先请用户设一次密码（方案 (a)）。设好后 [setSyncPassword] 再调 `onSuccess`。
     */
    fun loginByEmail(
        email: String,
        code: String,
        onNeedSetKey: () -> Unit,
        onSuccess: () -> Unit,
    ) {
        if (_busy.value) return
        _busy.value = true
        _error.value = null
        viewModelScope.launch {
            val r = AuthApi.loginByEmail(email.trim(), code.trim())
            if (r is AuthApi.Outcome.Ok) {
                adopt(r.value)
                val uid = r.value.account.uid
                if (store.loadPersonaKey() == null && uid.isNotBlank()) {
                    pendingKeyUid = uid
                    onNeedSetKey()
                } else {
                    onSuccess()
                }
            } else if (r is AuthApi.Outcome.Fail) {
                _error.value = r.message
            }
            _busy.value = false
        }
    }

    /** 设一次「人设同步密码」——**只用来派生本地密钥**（不改服务端密码）。 */
    fun setSyncPassword(password: String, onSuccess: () -> Unit) {
        val uid = pendingKeyUid
        if (uid.isNullOrBlank()) {
            onSuccess()
            return
        }
        if (password.trim().length < 6) {
            _error.value = "密码至少 6 位"
            return
        }
        viewModelScope.launch {
            cachePersonaKey(password.trim(), uid)
            pendingKeyUid = null
            onSuccess()
        }
    }

    /**
     * 登录/注册成功后，用**用户刚输入的密码**派生人设同步密钥并缓存。
     *
     * ⚠️ 只在这一次能拿到明文密码（之后同设备靠 token 免密），所以必须在这里做。
     * ⚠️ PBKDF2 120000 轮约百毫秒 —— 放 `Dispatchers.Default`，别卡主线程。
     */
    private suspend fun cachePersonaKey(password: String, uid: String) {
        if (password.isBlank() || uid.isBlank()) return
        runCatching {
            val key = withContext(Dispatchers.Default) { PersonaCrypto.deriveKey(password, uid) }
            store.savePersonaKey(key)
        }
    }
}
