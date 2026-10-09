package ai.yuki.chuxue.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 账号网络层 —— 开发文档 §19 的 `/api/v1/auth/` 与 `/api/v1/user/` 两组接口。
 *
 * ⚠️ 注释里**不要**写「斜杠 + 星号」的路径通配（如 `/api/v1/auth/` 后面接一个星号）：
 *    Kotlin 的块注释**可以嵌套**，那个符号会开启一层嵌套注释、把后面的结束符一起吃掉，
 *    编译直接报 `Unclosed comment`。（本项目踩过两次，第二次就是在写这条注释的时候。）
 *
 * ## 与 `DeepSeekClient` 的关系：完全平行，不复用
 * 两者都是 HTTP，但**没有一处可以共用**：不同的 baseUrl、不同的鉴权方式、
 * 不同的失败语义。上一轮的记忆提取曾经证明过"复用 SSE 客户端"是错的，
 * 这里连尝试都不必 —— 各自一个 `OkHttpClient`，互不影响。
 * （上报用的 `Telemetry` 也是同理。）
 *
 * ## 请求体与响应解析都是**纯函数**
 * 抽出来单独放（`registerBody` / `parseSession` / `parseError`），是为了在 JVM 上
 * 就能钉死字段名与错误提取 —— 网络层本身没法单测，但这两件事是它的全部内容。
 *
 * ## 失败一律带**中文人话**
 * 后端返回 `{"detail": "昵称已经被用了"}`，这里把它原样取出来给用户看。
 * 取不到才用兜底文案。**不把 HTTP 状态码或英文异常抛给用户**。
 */
/**
 * 发验证码的**结局**。
 *
 * ⚠️ [sent] 是**唯一**能决定"要不要开始读秒"的东西。
 * 后端的 2xx **不等于**邮件发出去了 —— 邮件服务没配时它会回 `ok:true` 而 `sent:false`
 * （见 `backend/main.py` 的 `_send_code`）。
 *
 * 之前这个布尔被压成了一句中文，界面于是只能按"HTTP 200 就算成功"处理，
 * 倒计时照走 —— 用户 2026-10-05 报的正是这个：**点了、邮件没来，按钮却在读秒**。
 * 现在把 [sent] 与 [message] 分开带出来：前者决定读秒，后者决定说什么。
 */
data class CodeSendResult(val sent: Boolean, val message: String)

object AuthApi {
    /** 账号资料（开发文档 §19.2 的 `user` 对象）。 */
    data class Account(
        val uid: String,
        val nickname: String,
        val avatarUrl: String? = null,
        val email: String? = null,
    )

    /** 一次成功认证的结果。 */
    data class Session(
        val token: String,
        val refresh: String,
        val account: Account,
    )

    /**
     * 结果类型。
     *
     * **不用 `Result<T>`**：那个类型把失败压成一个 `Throwable`，而这里需要的是
     * "给用户看的一句话"。用自带的 sealed interface，调用方就必须处理失败分支 ——
     * 这正是我们要的（登录失败不能静默）。
     */
    sealed interface Outcome<out T> {
        data class Ok<T>(val value: T) : Outcome<T>
        data class Fail(val message: String) : Outcome<Nothing>
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val mediaJson = "application/json; charset=utf-8".toMediaType()

    /** 短超时：账号操作是用户**在等**的事，卡住十几秒不如直接报"连不上"。 */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private const val NETWORK_FAIL = "连不上服务器。检查手机网络，或稍后再试。"

    /* ══════════════ 请求体（纯函数，可单测） ══════════════ */

    fun registerBody(nickname: String, password: String): String = buildJsonObject {
        put("nickname", nickname)
        put("password", password)
    }.toString()

    /**
     * **注册第一段**的请求体 `{nickname, email, code}`（v0.59.0 两段式注册）。
     *
     * ⚠️ 这里**没有** password —— 密码在第二段（`register/finish`）才设。
     *    这样第二屏才能拿到并显示服务端分配的 uid。
     */
    fun registerStartBody(nickname: String, email: String, code: String): String = buildJsonObject {
        put("nickname", nickname)
        put("email", email)
        put("code", code)
    }.toString()

    /** **注册第二段**的请求体 `{password, avatar_url?}`（凭第一段发的令牌调用）。 */
    fun registerFinishBody(password: String, avatarUrl: String?): String = buildJsonObject {
        put("password", password)
        if (!avatarUrl.isNullOrBlank()) put("avatar_url", avatarUrl)
    }.toString()

    fun loginBody(account: String, password: String): String = buildJsonObject {
        put("account", account)
        put("password", password)
    }.toString()

    fun refreshBody(refresh: String): String = buildJsonObject {
        put("refresh", refresh)
    }.toString()

    fun nicknameBody(nickname: String): String = buildJsonObject {
        put("nickname", nickname)
    }.toString()

    /** 发验证码的请求体 `{email}`（绑定邮箱与找回密码共用，后端按 purpose 区分）。 */
    fun emailCodeBody(email: String): String = buildJsonObject {
        put("email", email)
    }.toString()

    /** 邮箱验证码**登录**的第二步请求体（v0.61.24.5）。纯函数，单测钉字段名。 */
    fun loginByEmailBody(email: String, code: String): String = buildJsonObject {
        put("email", email)
        put("code", code)
    }.toString()

    fun emailBindBody(email: String, code: String): String = buildJsonObject {
        put("email", email)
        put("code", code)
    }.toString()

    /** 改密码 / 找回密码的请求体 —— 后端两处用的是同一个契约（`PasswordResetIn`）。 */
    fun passwordResetBody(uid: String, code: String, newPassword: String): String =
        buildJsonObject {
            // v0.60.0：找回密码按 **UID** 定位（用户：「忘记密码的找回界面需要输入 UID」）。
            // 服务端会拿 uid 反查邮箱再校验验证码。
            put("uid", uid)
            put("code", code)
            put("new_password", newPassword)
        }.toString()

    /** 找回密码发码：只发 uid，邮箱由服务端反查。 */
    fun uidCodeBody(uid: String): String = buildJsonObject {
        put("uid", uid)
    }.toString()

    /* ══════════════ 响应解析（纯函数，可单测） ══════════════ */

    /**
     * 从后端的错误响应里取出**给人看的那句话**。
     *
     * 后端的错误体固定是 `{"detail": "..."}`（FastAPI 的 HTTPException）。
     * 取不到就退回 [fallback] —— 绝不把原始 JSON 或状态码丢给用户。
     */
    fun parseError(raw: String, fallback: String): String = runCatching {
        json.parseToJsonElement(raw).jsonObject["detail"]?.jsonPrimitive?.content
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: fallback

    /** 解析 `{uid, user:{...}, token, refresh}`（注册/登录/刷新的响应）。 */
    fun parseSession(raw: String): Outcome<Session> = runCatching {
        val root = json.parseToJsonElement(raw).jsonObject
        val token = root["token"]?.jsonPrimitive?.content.orEmpty()
        val refresh = root["refresh"]?.jsonPrimitive?.content.orEmpty()
        if (token.isBlank()) return@runCatching null
        Session(token = token, refresh = refresh, account = accountOf(root["user"]?.jsonObject))
    }.getOrNull()?.let { Outcome.Ok(it) } ?: Outcome.Fail("服务器返回的数据看不懂，请联系开发者")

    /** 解析 `{user:{...}}`（`/me` 与资料修改的响应）。 */
    fun parseAccount(raw: String, fromUserObject: Boolean = true): Outcome<Account> = runCatching {
        val root = json.parseToJsonElement(raw).jsonObject
        val obj = if (fromUserObject) root["user"]?.jsonObject else root
        accountOf(obj)
    }.getOrNull()?.let { Outcome.Ok(it) } ?: Outcome.Fail("服务器返回的数据看不懂，请联系开发者")

    /**
     * 解析发码响应 `{ok, sent, degraded, message?}` → **给用户看的一句话**。
     *
     * 这里刻意不返回 `Outcome<Unit>`：这一步有**三种结局**，而它们在界面上长得一样
     * （都是"什么都没变"），用户会以为自己没点中。
     *   · `sent=true`     → 邮件真的发出去了；
     *   · `degraded=true` → 邮件服务没配，验证码留在管理后台（后端刻意**不报错**，
     *     否则会出现另一个项目踩过的"邮件没配好 → 注册死锁"）；
     *   · 两者都没有      → 没发出去。
     */
    fun parseSendCode(raw: String): CodeSendResult = runCatching {
        val root = json.parseToJsonElement(raw).jsonObject
        val sent = root["sent"]?.jsonPrimitive?.booleanOrNull ?: false
        val degraded = root["degraded"]?.jsonPrimitive?.booleanOrNull ?: false
        val message = root["message"]?.jsonPrimitive?.content
        CodeSendResult(
            sent = sent,
            message = when {
                sent -> "验证码已发到邮箱，去看看"
                degraded -> message ?: "邮件服务尚未配置，验证码已生成，请到管理后台查看"
                else -> "验证码没发出去，稍后再试"
            },
        )
    }.getOrDefault(CodeSendResult(sent = false, message = "验证码没发出去，稍后再试"))

    private fun accountOf(obj: kotlinx.serialization.json.JsonObject?): Account {
        // ⚠️ v0.61.24.4：必须用 contentOrNull —— 对 JSON 的 `null`，
        //    `jsonPrimitive.content` 返回的是**字符串 "null"**（不是 Kotlin null），
        //    于是"邮箱为空"的判断（isNullOrBlank）**永远为假** → 老用户的「补绑邮箱」提示
        //    从不出现（用户 2026-10-05 报的正是这个）；`avatarUrl` 同样吃这个亏。
        fun str(key: String): String? = obj?.get(key)?.jsonPrimitive?.contentOrNull
        return Account(
            uid = str("uid").orEmpty(),
            nickname = str("nickname").orEmpty(),
            avatarUrl = str("avatarUrl"),
            email = str("email"),
        )
    }

    /* ══════════════ 网络调用 ══════════════ */

    /**
     * 这次请求该带什么请求体。**GET / HEAD / DELETE 一律不带**。
     *
     * ## 它修的是一处"伪装成网络故障"的真 bug（v0.61.21，体检抓出来的）
     * 原来 `call()` 里写的是：
     * ```
     * .method(method, (body ?: "{}").toRequestBody(mediaJson))
     * ```
     * GET 的调用方传 `body = null`，于是被兜底成 `"{}"` —— **GET 带上了请求体**。
     * OkHttp 的 `HttpMethod.permitsRequestBody("GET")` 是 false，`Request.Builder.method`
     * 直接 `require` 失败抛 `IllegalArgumentException`；而它被同一行的 `runCatching`
     * 吞成 `code = -1`，调用方统一映射成「连不上服务器」——
     * **一个纯客户端错误伪装成了网络故障**，所以没人在真机上看出是代码问题。
     *
     * 受害的三处（`GET /me`、`GET /auth/email/exists`、`GET /user/persona`）见
     * `AuthApiBodyForTest` 的注释。抽成纯函数就是为了让它**能被单测钉住** ——
     * 原来那段代码藏在 `runCatching` 里，任何测试都摸不到。
     */
    fun bodyFor(method: String, body: String?): RequestBody? =
        if (method.uppercase() in REQUEST_BODY_METHODS) {
            (body ?: "{}").toRequestBody(mediaJson)
        } else {
            null
        }

    /** 与 OkHttp 的 `permitsRequestBody` 同一个集合 —— 多一个都会抛。 */
    private val REQUEST_BODY_METHODS = setOf("POST", "PUT", "PATCH")

    private suspend fun call(
        path: String,
        body: String? = null,
        token: String? = null,
        method: String = "POST",
    ): Pair<Int, String> = withContext(Dispatchers.IO) {
        runCatching {
            val builder = Request.Builder()
                .url(ServerConfig.API_V1 + path)
                .method(method, bodyFor(method, body))
            if (token != null) builder.header("Authorization", "Bearer $token")
            client.newCall(builder.build()).execute().use { res ->
                res.code to res.body?.string().orEmpty()
            }
        }.getOrElse { -1 to "" }
    }

    suspend fun register(nickname: String, password: String): Outcome<Session> {
        val (code, raw) = call("/auth/register", registerBody(nickname, password))
        return when {
            code == -1 -> Outcome.Fail(NETWORK_FAIL)
            code in 200..299 -> parseSession(raw)
            else -> Outcome.Fail(parseError(raw, "注册失败（HTTP $code）"))
        }
    }

    /**
     * **注册第一段**：昵称 + 邮箱 + 验证码 → 占位建号，拿到 **uid** 与令牌。
     *
     * 返回体与登录同形（`{uid, user, token, refresh}`），所以直接复用 [parseSession]。
     * 拿到的令牌**只能**用来调 [registerFinish] —— 服务端把还没设密码的账号
     * （`status='pending'`）挡在所有其它需要登录的接口之外。
     */
    suspend fun registerStart(nickname: String, email: String, code: String): Outcome<Session> {
        val (code2, raw) = call("/auth/register/start", registerStartBody(nickname, email, code))
        return when {
            code2 == -1 -> Outcome.Fail(NETWORK_FAIL)
            code2 in 200..299 -> parseSession(raw)
            else -> Outcome.Fail(parseError(raw, "注册失败（HTTP $code2）"))
        }
    }

    /**
     * **这个邮箱注册过没有**（v0.60.0 · 用户 2026-10-02：注册时边输边提示）。
     *
     * ⚠️ 解析失败一律当"没注册"（返回 false）—— **宁可漏报也不误报**：
     *    误报会把一个能正常注册的邮箱挡在门外，漏报只是少一次提示。
     */
    suspend fun emailExists(email: String): Outcome<Boolean> {
        val q = java.net.URLEncoder.encode(email.trim().lowercase(), "UTF-8")
        val (code, raw) = call("/auth/email/exists?email=$q", null, null, method = "GET")
        return when {
            code == -1 -> Outcome.Fail(NETWORK_FAIL)
            code in 200..299 -> Outcome.Ok(
                runCatching { org.json.JSONObject(raw).optBoolean("registered", false) }
                    .getOrDefault(false),
            )
            else -> Outcome.Fail(parseError(raw, "查不了"))
        }
    }

    /**
     * **放弃注册**：把还没设密码的占位账号删掉（v0.60.0）。
     *
     * 用户 2026-10-02：「如果用户在设置密码或者创建账号填邮箱界面之后中途退出，
     * 就不给他创建账号」。所以退出认证流程时调这个 —— 服务端只删 `status='pending'` 的行。
     *
     * ⚠️ 失败**静默**：网络不通时删不掉也没关系（服务端本来就有 24 小时回收兜底），
     *    没必要因为清理失败去打扰用户。
     */
    suspend fun registerCancel(token: String): Outcome<String> {
        val (code, raw) = call("/auth/register/cancel", "{}", token, method = "POST")
        return when {
            code == -1 -> Outcome.Fail(NETWORK_FAIL)
            code in 200..299 -> Outcome.Ok("ok")
            else -> Outcome.Fail(parseError(raw, "放弃注册失败（HTTP $code）"))
        }
    }

    /** **注册第二段**：凭第一段的令牌设密码（+ 可选头像）→ 账号激活。 */
    suspend fun registerFinish(token: String, password: String, avatarUrl: String?): Outcome<Account> {
        val (code, raw) = call(
            "/auth/register/finish",
            registerFinishBody(password, avatarUrl),
            token,
            method = "PUT",
        )
        return when {
            code == -1 -> Outcome.Fail(NETWORK_FAIL)
            code in 200..299 -> parseAccount(raw)
            else -> Outcome.Fail(parseError(raw, "设置密码失败（HTTP $code）"))
        }
    }

    suspend fun login(account: String, password: String): Outcome<Session> {
        val (code, raw) = call("/auth/login", loginBody(account, password))
        return when {
            code == -1 -> Outcome.Fail(NETWORK_FAIL)
            code in 200..299 -> parseSession(raw)
            else -> Outcome.Fail(parseError(raw, "登录失败（HTTP $code）"))
        }
    }

    suspend fun refresh(refreshToken: String): Outcome<Session> {
        val (code, raw) = call("/auth/refresh", refreshBody(refreshToken))
        return when {
            code == -1 -> Outcome.Fail(NETWORK_FAIL)
            code in 200..299 -> parseSession(raw)
            else -> Outcome.Fail(parseError(raw, "登录已过期，请重新登录"))
        }
    }

    suspend fun me(token: String): Outcome<Account> {
        val (code, raw) = call("/me", token = token, method = "GET")
        return when {
            code == -1 -> Outcome.Fail(NETWORK_FAIL)
            code in 200..299 -> parseAccount(raw)
            else -> Outcome.Fail(parseError(raw, "读取账号资料失败"))
        }
    }

    suspend fun updateNickname(token: String, nickname: String): Outcome<Account> {
        val (code, raw) = call("/user/nickname", nicknameBody(nickname), token, method = "PUT")
        return when {
            code == -1 -> Outcome.Fail(NETWORK_FAIL)
            code in 200..299 -> parseAccount(raw)
            else -> Outcome.Fail(parseError(raw, "改昵称失败"))
        }
    }

    /**
     * 发验证码（绑定邮箱 / 找回密码）。**不需要登录态**。
     *
     * 成功时 [Outcome.Ok] 里装的是 [CodeSendResult] —— **`sent` 与 `message` 是两件事**：
     * 前者决定界面要不要开始读秒，后者决定跟用户说什么。见 [parseSendCode]。
     */
    suspend fun sendEmailCode(email: String): Outcome<CodeSendResult> {
        val (httpCode, raw) = call("/auth/email/send-code", emailCodeBody(email))
        return when {
            httpCode == -1 -> Outcome.Fail(NETWORK_FAIL)
            httpCode in 200..299 -> Outcome.Ok(parseSendCode(raw))
            else -> Outcome.Fail(parseError(raw, "验证码发不出去，稍后再试"))
        }
    }

    /* ── 邮箱验证码**登录**（v0.61.24.5，用户 2026-10-05 要求）──
     *
     * 与「绑定邮箱」链路方向相反：绑定是"已注册就拒"，登录是"没注册就拒"。
     * ⚠️ 登录拿不到明文密码 → 人设同步密钥要**另外设一次密码**（用户在 App 侧完成，
     *    服务端不掺和）—— 见 `AuthViewModel.loginByEmail` 的回调。
     */

    /** 邮箱验证码登录 · 第一步：发码。 */
    suspend fun sendLoginCode(email: String): Outcome<CodeSendResult> {
        val (httpCode, raw) = call("/auth/login/email/send-code", emailCodeBody(email))
        return when {
            httpCode == -1 -> Outcome.Fail(NETWORK_FAIL)
            httpCode in 200..299 -> Outcome.Ok(parseSendCode(raw))
            else -> Outcome.Fail(parseError(raw, "验证码发不出去，稍后再试"))
        }
    }

    /** 邮箱验证码登录 · 第二步：用码换会话。 */
    suspend fun loginByEmail(email: String, code: String): Outcome<Session> {
        val (httpCode, raw) = call("/auth/login/email", loginByEmailBody(email, code))
        return when {
            httpCode == -1 -> Outcome.Fail(NETWORK_FAIL)
            httpCode in 200..299 -> parseSession(raw)
            else -> Outcome.Fail(parseError(raw, "登录失败（HTTP $httpCode）"))
        }
    }

    /**
     * 绑定邮箱（开发文档 §32.7 的注册步骤 3）。**需要登录态** ——
     * 后端这一步走 `Depends(require_user)`：先把账号建好，再把邮箱挂上去。
     * 所以它是"注册的收尾"，而不是"注册的门槛"。
     */
    suspend fun bindEmail(token: String, email: String, code: String): Outcome<Account> {
        val (httpCode, raw) = call("/auth/email/bind", emailBindBody(email, code), token)
        return when {
            httpCode == -1 -> Outcome.Fail(NETWORK_FAIL)
            httpCode in 200..299 -> parseAccount(raw)
            else -> Outcome.Fail(parseError(raw, "绑定失败"))
        }
    }

    /**
     * 发验证码 —— **改密码 / 找回密码**用（`/auth/password/send-code`）。
     *
     * 与 [sendEmailCode] 是同一个响应格式（后端 `_send_code` 只按 purpose 换文案），
     * 区别只在接口路径与用途 —— 所以 `sent` / `message` 的分工也**完全一样**。
     */
    suspend fun sendPasswordCode(uid: String): Outcome<CodeSendResult> {
        val (httpCode, raw) = call("/auth/password/send-code", uidCodeBody(uid))
        return when {
            httpCode == -1 -> Outcome.Fail(NETWORK_FAIL)
            httpCode in 200..299 -> Outcome.Ok(parseSendCode(raw))
            else -> Outcome.Fail(parseError(raw, "验证码发不出去，稍后再试"))
        }
    }

    /**
     * 用邮箱验证码改密码。**免登录** —— 登录页的「忘记密码」与设置页的「修改密码」都走它。
     *
     * ⚠️ 后端成功后会把该用户的**全部令牌删掉**（见 backend/main.py 的 password/reset）。
     * 这是对的（密码变了，别处的旧会话就该失效），但对**已登录用户**意味着
     * 「改完之后自己也得上重新登录」。界面上必须把这句话说在前面，别让用户以为掉线了。
     */
    suspend fun resetPassword(uid: String, code: String, newPassword: String): Outcome<Unit> {
        val (httpCode, raw) = call(
            "/auth/password/reset",
            passwordResetBody(uid, code, newPassword),
        )
        return when {
            httpCode == -1 -> Outcome.Fail(NETWORK_FAIL)
            httpCode in 200..299 -> Outcome.Ok(Unit)
            else -> Outcome.Fail(parseError(raw, "改密码失败"))
        }
    }

    suspend fun logout(token: String): Outcome<Unit> {
        val (code, raw) = call("/auth/logout", token = token, method = "POST")
        return if (code == -1) Outcome.Fail(NETWORK_FAIL)
        else if (code in 200..299) Outcome.Ok(Unit)
        else Outcome.Fail(parseError(raw, "登出失败"))
    }

    /* ══════════════ 人设端到端加密同步（v0.52.0） ══════════════
     *
     * ⚠️ 这里是"盲搬字节"：上传的是**客户端已经加密好**的密文，服务端不持密钥、
     *    也不解析内容。本层不做任何加解密 —— 那是 [PersonaCrypto] 的事。
     * ⚠️ 聊天记录与记忆**没有**对应接口，也不会有（红线）。
     */

    /** 服务端上的人设密文快照。`blob == null` 表示从没同步过。 */
    data class PersonaBlob(val blob: String?, val rev: Long)

    /** 上传人设密文的请求体 `{blob, rev}`（纯函数，可单测）。 */
    fun personaBody(blob: String, rev: Long): String = buildJsonObject {
        put("blob", blob)
        put("rev", rev)
    }.toString()

    /** 解析 `{ok, blob, rev}`（GET /user/persona）。 */
    fun parsePersonaBlob(raw: String): Outcome<PersonaBlob> = runCatching {
        val root = json.parseToJsonElement(raw).jsonObject
        PersonaBlob(
            blob = root["blob"]?.jsonPrimitive?.contentOrNull,
            rev = root["rev"]?.jsonPrimitive?.longOrNull ?: 0L,
        )
    }.getOrNull()?.let { Outcome.Ok(it) } ?: Outcome.Fail("服务器返回的数据看不懂")

    /** 拉取本人的设密文。 */
    suspend fun fetchPersona(token: String): Outcome<PersonaBlob> {
        val (code, raw) = call("/user/persona", token = token, method = "GET")
        return when {
            code == -1 -> Outcome.Fail(NETWORK_FAIL)
            code in 200..299 -> parsePersonaBlob(raw)
            else -> Outcome.Fail(parseError(raw, "读取人设同步数据失败"))
        }
    }

    /** 上传本人的设密文。 */
    suspend fun uploadPersona(token: String, blob: String, rev: Long): Outcome<Unit> {
        val (code, raw) = call("/user/persona", personaBody(blob, rev), token, method = "PUT")
        return when {
            code == -1 -> Outcome.Fail(NETWORK_FAIL)
            code in 200..299 -> Outcome.Ok(Unit)
            else -> Outcome.Fail(parseError(raw, "上传人设失败"))
        }
    }

    /** 上传头像图，成功返回服务端给的**相对** URL（如 `/uploads/avatars/xxx.jpg`）。 */
    suspend fun uploadAvatar(
        token: String,
        bytes: ByteArray,
        fileName: String,
        mime: String,
    ): Outcome<String> {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", fileName, bytes.toRequestBody(mime.toMediaType()))
            .build()
        val (code, raw) = callMultipart("/user/avatar/upload", body, token)
        return when {
            code == -1 -> Outcome.Fail(NETWORK_FAIL)
            code in 200..299 -> runCatching {
                json.parseToJsonElement(raw).jsonObject["url"]?.jsonPrimitive?.content
            }.getOrNull()?.takeIf { it.isNotBlank() }
                ?.let { Outcome.Ok(it) } ?: Outcome.Fail("服务器没给出头像地址")
            else -> Outcome.Fail(parseError(raw, "上传头像失败"))
        }
    }

    /** multipart 调用 —— 与 [call] 同源，只是 body 换成 [MultipartBody]。 */
    private suspend fun callMultipart(
        path: String,
        body: MultipartBody,
        token: String,
    ): Pair<Int, String> = withContext(Dispatchers.IO) {
        runCatching {
            val builder = Request.Builder()
                .url(ServerConfig.API_V1 + path)
                .post(body)
                .header("Authorization", "Bearer $token")
            client.newCall(builder.build()).execute().use { res ->
                res.code to res.body?.string().orEmpty()
            }
        }.getOrElse { -1 to "" }
    }

    /**
     * 下载一张图（**头像兜底**用：本地没有那张图时，把账号里的服务器头像取回来）。
     *
     * ⚠️ 复用同一个 `client` 的超时策略即可 —— 头像最大几百 KB；失败一律返回 null，
     * 由调用方决定"就显示默认头像"，绝不抛。
     */
    suspend fun downloadImage(url: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(Request.Builder().url(url).get().build()).execute().use { res ->
                if (!res.isSuccessful) null else res.body?.bytes()
            }
        }.getOrNull()
    }
}
