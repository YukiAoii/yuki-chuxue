@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package ai.yuki.chuxue.ui.auth

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.yuki.chuxue.data.ImageStore
import ai.yuki.chuxue.data.PasswordPolicy
import ai.yuki.chuxue.ui.components.Island
import ai.yuki.chuxue.ui.components.yukiShadow
import ai.yuki.chuxue.ui.theme.BrandBlue
import ai.yuki.chuxue.ui.theme.BrandBlueSoft
import ai.yuki.chuxue.ui.theme.DangerRose
import kotlinx.coroutines.delay

/**
 * **登录 / 注册页**（v0.59.0 推翻重写，v0.60.0 按设计稿**逐项实测**对齐）。
 *
 * ## 版式依据：设计稿的实测值，不是"看着差不多"
 * `docs/design/认证界面_设计稿_v2_2026-10-02.html` 在浏览器里逐个元素量过：
 * 内容**左对齐**（x=26）、图标 68（登录）/ 52（注册）、图标→标题 20 / 18、
 * 标题 23px、标题→副标题 9、副标题→首个字段 24（登录）/ 22（注册）、
 * 字段之间 12、字段→按钮 22、按钮→文字链接 16。
 *
 * ⚠️ 上一版我犯了两个错，用户看出来了："和设计稿差别太大" ——
 *    ① 标题用了 `headlineSmall`（项目里 20sp），设计稿要 23px；
 *    ② 整屏写成**居中**，设计稿是**左对齐**。现在都按实测值改了。
 *
 * ## 注册为什么是两段
 * 用户要求的顺序是「昵称 → 邮箱 → 验证码 → 确定 → **第二屏显示 uid** → 设密码」。
 * uid 由服务端分配，客户端在设密码前拿不到它 —— 所以第一段必须先把 uid 拿下来
 * （服务端为此建一个 `status='pending'` 的占位账号）。
 *
 * ⚠️ 第一段成功后**不写入登录态**：那个账号还没密码，服务端会把它挡在所有
 *    需要登录的接口之外。真正 adopt 发生在第二段成功之后。
 *
 * ⚠️ 渲染未经真机验证（本机无 adb）。
 */
@Composable
fun AuthScreen(
    vm: AuthViewModel,
    onAuthed: () -> Unit,
    startOnRegister: Boolean = false,
) {
    var step by rememberSaveable {
        mutableStateOf(if (startOnRegister) AuthStep.REGISTER_1 else AuthStep.LOGIN)
    }
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    // 中性回话（"验证码已发到邮箱" / "邮件服务尚未配置…"）——这是**中性**提示，不是错误
    val notice by vm.notice.collectAsStateWithLifecycle()

    // ⚠️ 系统返回的落点（用户 2026-10-02 明确）：
    //    · 重设密码 / 注册第一屏 → **回登录页**（不是回引导页，也不是退出）
    //    · 注册第二屏 → 回注册第一屏（同屏内的"上一步"）
    //    · 已经在登录页 → **交给系统**（handler 关掉，等价于"到头了"）
    //    引导页只在第一次出现，**不提供返回进入的路径**。
    BackHandler(enabled = step != AuthStep.LOGIN) {
        if (step == AuthStep.REGISTER_2) {
            vm.abandonRegistration()   // 放弃这次注册 → 顺手把占位账号撤掉
            step = AuthStep.REGISTER_1
        } else {
            step = AuthStep.LOGIN
        }
    }

    // 错误统一走灵动岛 —— 与全站一致，不再各页自己画一行红字
    LaunchedEffect(error) {
        error?.let {
            Island.error(it)
            vm.dismissError()
        }
    }

    // 回话也走同一处路由。⚠️ 没有它，发验证码的三种结局（真发了 / 邮件没配 / 发失败）
    // 在界面上长得一模一样（都是"什么都没变"），用户会以为按钮没点到。
    LaunchedEffect(notice) {
        notice?.let {
            Island.ok(it)
            vm.dismissNotice()
        }
    }

    AuthBackdrop {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AUTH_PAD),
            // 用户 2026-10-02：「所有界面元素向下一些，刚好在屏幕中间」——
            // 从「固定距顶 58dp」改成**竖向居中**。内容比屏幕矮时居中，
            // 比屏幕高时照常滚动（verticalScroll + Arrangement.Center 的既有行为）。
            verticalArrangement = Arrangement.Center,
        ) {
            // 用户 2026-10-02：「登录和注册界面再往上一点」——
            // 居中的基础上抬高一点点（尾部撑一段比顶部更高，视觉中心就往上走）。
            Spacer(Modifier.height(UP_SHIFT_DP))


            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    // 用户 2026-10-02：登录 ↔ 注册 要**平滑过渡**，不要那种"突然切界面"。
                    // 横滑会给人"翻页/跳转"的观感；交叉淡入 + 极轻的缩放才是"溶过去"。
                    // 缩放只给 0.985→1，多一点点就会有廉价感。
                    (fadeIn(tween(AUTH_SWITCH_MS, easing = FastOutSlowInEasing)) +
                        scaleIn(
                            tween(AUTH_SWITCH_MS, easing = FastOutSlowInEasing),
                            initialScale = 0.985f,
                        ))
                        .togetherWith(fadeOut(tween(AUTH_SWITCH_OUT_MS, easing = FastOutSlowInEasing)))
                },
                label = "auth-step",
            ) { current ->
                when (current) {
                    AuthStep.LOGIN -> LoginStep(
                        vm = vm,
                        busy = busy,
                        onAuthed = onAuthed,
                        onGoRegister = { step = AuthStep.REGISTER_1 },
                        onGoForgot = { step = AuthStep.FORGOT },
                        onGoEmailLogin = { step = AuthStep.EMAIL_LOGIN },
                    )
                    AuthStep.EMAIL_LOGIN -> EmailLoginStep(
                        vm = vm,
                        busy = busy,
                        onAuthed = { onAuthed() },
                        onGoPassword = { step = AuthStep.LOGIN },
                    )

                    AuthStep.REGISTER_1 -> RegisterStep1(
                        vm = vm,
                        busy = busy,
                        onBack = { step = AuthStep.LOGIN },
                        onNext = { step = AuthStep.REGISTER_2 },
                    )

                    AuthStep.REGISTER_2 -> RegisterStep2(
                        vm = vm,
                        busy = busy,
                        onBack = {
                            vm.abandonRegistration()
                            step = AuthStep.REGISTER_1
                        },
                        onAuthed = onAuthed,
                    )

                    AuthStep.FORGOT -> ForgotStep(
                        vm = vm,
                        busy = busy,
                        onBack = { step = AuthStep.LOGIN },
                    )
                }
            }
            Spacer(Modifier.height(UP_SHIFT_DP))

        }
    }
}

/**
 * 屏间切换的时长（用户 2026-10-02：登录 ↔ 注册 要**平滑过渡**，不要"突然切界面"）。
 *
 * 进场比退场长一档：旧的先淡淡退掉、新的再缓缓浮上来，眼睛跟得上；
 * 两个设成一样长反而会有"整块一起闪"的感觉。
 */
private const val AUTH_SWITCH_MS = 340
private const val AUTH_SWITCH_OUT_MS = 220

/**
 * 内容整体上移多少（顶部少一段、底部多一段 → 视觉中心上移一半）。
 * 用户 2026-10-02：「登录和注册界面再往上一点」。
 */
private val UP_SHIFT_DP = 72.dp

/** 认证页的四屏。顺序有意义 —— `REGISTER_1 → REGISTER_2` 的先后决定过渡动画方向。 */
/**
 * 邮箱格式校验（客户端第一道闸）。
 *
 * ⚠️ 刻意**不追求 RFC 完备** —— 邮箱真正的判据是"能不能收到验证码"；
 *    客户端只拦明显不是邮箱的输入，免得把合法但不常见的地址挡在外面。
 *    后端另有一道（见 `main.py` 的 EMAIL_RE），两边规则各自独立。
 *
 * @return null = 通过；否则是给用户看的提示语
 */
internal fun emailError(email: String): String? {
    val e = email.trim()
    if (e.isEmpty()) return null
    if (e.any { it.isWhitespace() }) return "邮箱里不能有空格"
    val at = e.indexOf("@")
    if (at < 0) return "邮箱里要有 @"
    if (e.indexOf("@", at + 1) >= 0) return "邮箱里只能有一个 @"
    val local = e.substring(0, at)
    val domain = e.substring(at + 1)
    if (local.isEmpty()) return "邮箱 @ 前面缺内容"
    if (domain.isEmpty() || "." !in domain) return "邮箱 @ 后面要有域名，比如 example.com"
    if (domain.startsWith(".") || domain.endsWith(".")) return "域名两边不能是点"
    if (domain.startsWith("-") || domain.endsWith("-")) return "域名两边不能是横杠"
    return null
}

private enum class AuthStep { LOGIN, EMAIL_LOGIN, REGISTER_1, REGISTER_2, FORGOT }

/* ═══════════════════════ 公共件 ═══════════════════════ */

/** 次级文字键（"去注册""返回"这类）。设计稿实测 13.5px。 */
@Composable
private fun TextLink(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        fontSize = 13.5.sp,
        fontWeight = FontWeight(650),
        // 用户 2026-10-02：「注册新账号和忘记密码用深黑色粗体，别用蓝色」
        color = AuthInk,
        modifier = Modifier
            .combinedClickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/**
 * 验证码输入 + 「获取验证码」（**受控**：`code` 抬给调用方，提交时连它一起送走）。
 *
 * 设计稿实测：两格**等高 64**，输入框占剩余宽度，按钮约 104 宽。
 * 倒计时是**本地**的（60 秒）—— 后端也有一道 60 秒重发限制，这里先拦住。
 */
@Composable
internal fun CodeInput(
    /** 「获取验证码」能不能点 —— 由**调用方**判定（注册看邮箱、找回看 UID，判据不同）。 */
    targetReady: Boolean,
    code: String,
    onCode: (String) -> Unit,
    /**
     * 点「获取验证码」时调用。
     *
     * ⚠️ 参数是一个**"真发出去了"的回调**：只有后端确认邮件已发出（`sent: true`）
     * 才会被调用，调用方据此才开始读秒。
     *
     * **点击本身不许开始读秒** —— 用户 2026-10-05 报的就是这个：
     * 点了、邮件没来，按钮却已经在倒数了。回话（发没发成）走
     * `AuthViewModel.notice` 那条通道，不从这里出。
     */
    onSend: (onSent: () -> Unit) -> Unit,
) {
    var countdown by remember { mutableIntStateOf(0) }
    LaunchedEffect(countdown) {
        if (countdown > 0) {
            delay(1_000)
            countdown -= 1
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            AuthField(
                value = code,
                onValueChange = { onCode(it.filter { c -> c.isDigit() }.take(6)) },
                label = "验证码",
                placeholder = "6 位数字",
            )
        }
        Spacer(Modifier.width(10.dp))
        val canSend = countdown == 0 && targetReady
        val counting = countdown > 0
        val cbInteraction = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                // ⚠️ **固定宽度**：文字会在「获取验证码」和「60s」之间变，
                //    不锁宽度的话按钮读秒时会缩一下（用户 2026-10-02 报的问题）。设计稿实测 104。
                .width(104.dp)
                .height(64.dp)
                .pressScale(cbInteraction)
                // 用户 2026-10-02：「增加悬浮效果，外层包裹的蓝色删了」——
                // 蓝色描边去掉，改成**阴影托浮**：可点时有微弱投影，读秒时收回。
                .yukiShadow(
                    cornerRadius = 14.dp, offsetY = 3.dp, blur = 10.dp,
                    color = if (counting) Color(0x00000000) else Color(0x1A203A63),
                )
                .clip(RoundedCornerShape(14.dp))
                // 两种状态一眼能看出"能不能点"：
                //   可点 = 纯白  ｜  读秒 = 浅灰实底 +「60s 后可重发」
                .background(if (counting) Color(0xFFEFF2F7) else Color.White)
                .clickable(
                    interactionSource = cbInteraction,
                    indication = null,
                    enabled = canSend,
                ) {
                    // ⚠️ 这里**不动** countdown：要等"真发出去了"的回调（见 onSend 的注释）。
                    //    在点击时就倒计数的旧写法，会让用户对着一个读秒的按钮
                    //    等一封永远不来的邮件。
                    onSend { countdown = 60 }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (counting) "${countdown}s 后可重发" else "获取验证码",
                fontSize = if (counting) 11.sp else 13.5.sp,
                fontWeight = FontWeight(650),
                color = when {
                    counting -> Color(0xFFA8AFB8)
                    canSend -> BrandBlue
                    else -> Color(0xFFB4BAC3)
                },
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        }
    }
}

/* ═══════════════════════ ③ 登录 ═══════════════════════ */

@Composable
private fun LoginStep(
    vm: AuthViewModel,
    busy: Boolean,
    onAuthed: () -> Unit,
    onGoRegister: () -> Unit,
    onGoForgot: () -> Unit,
    onGoEmailLogin: () -> Unit,
) {
    var account by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    val canSubmit = account.isNotBlank() && password.isNotBlank() && !busy

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        // 用户要求（2026-10-02）：登录页**布局居中**
        AuthHeading(
            title = "欢迎回来",
            subtitle = "登录以使用完整功能",
            markSize = 68.dp, markCorner = 19.dp, titleSize = 23.sp, markGap = 20.dp,
            center = true,
        )

        Spacer(Modifier.height(24.dp))
        AuthField(
            value = account,
            onValueChange = { account = it },
            // v0.60.0：**只认 UID**（用户要求「登录只能用 uid 不能用昵称」）
            label = "UID",
            placeholder = "注册时发给你的那串数字",
        )
        Spacer(Modifier.height(AUTH_FIELD_GAP))
        AuthField(
            value = password,
            onValueChange = { password = it },
            label = "密码",
            placeholder = "登录用的密码",
            isPassword = true,
        )

        Spacer(Modifier.height(22.dp))
        AuthPrimaryButton("进入初雪", enabled = canSubmit, loading = busy) {
            vm.login(account, password) { onAuthed() }
        }
        Spacer(Modifier.height(14.dp))
        // v0.61.24.5：登录方式切换（用户 2026-10-05 要求）。
        // 全 App 没有 Tab 组件，所以沿用既有的「文字链」形态 ——
        // 与下方注册 / 忘记密码同一套观感，不引入新的切换控件。
        TextLink("用邮箱验证码登录") { onGoEmailLogin() }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 设计稿原文是「没有账号？注册新账号 · 忘记密码」—— 前缀不能省
            Text("没有账号？", fontSize = 13.5.sp, color = AuthSubInk)
            TextLink("注册新账号") { onGoRegister() }
            Text("·", fontSize = 13.5.sp, color = AuthSubInk)
            TextLink("忘记密码") { onGoForgot() }
        }
    }
}

/* ═══════════════════════ ③b 登录 · 邮箱验证码（v0.61.24.5）═══════════════════════ */

/**
 * 邮箱验证码登录。
 *
 * ## 它要解决两件事
 * 1. **忘了 UID 也能进** —— 用注册时绑定的邮箱收码；
 * 2. **人设密钥**：
 *    ⚠️ 端到端加密的密钥是「密码 + uid」派生出来的，而验证码登录**没有明文密码** →
 *       本机若还没有密钥，登录成功后**要先请用户设一次密码**（用户 2026-10-05 选的方案 (a)），
 *       否则云端人设在本机解不开（表现就是"人设同步不过来"）。
 */
@Composable
private fun EmailLoginStep(
    vm: AuthViewModel,
    busy: Boolean,
    onAuthed: () -> Unit,
    onGoPassword: () -> Unit,
) {
    var email by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    // 验证码已通过、但本机还没有同步密钥 → 切成"设密码"这一屏
    var needKey by rememberSaveable { mutableStateOf(false) }
    var keyPw by rememberSaveable { mutableStateOf("") }

    if (needKey) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            AuthHeading(
                title = "再设一个同步密码",
                subtitle = "它用来加密你的人设；换设备时用它把人设带回来",
                markSize = 68.dp, markCorner = 19.dp, titleSize = 23.sp, markGap = 20.dp,
                center = true,
            )
            Spacer(Modifier.height(24.dp))
            AuthField(
                value = keyPw,
                onValueChange = { keyPw = it },
                label = "同步密码",
                placeholder = "至少 6 位，自己记得住就行",
                isPassword = true,
            )
            Spacer(Modifier.height(22.dp))
            AuthPrimaryButton("完成", enabled = keyPw.trim().length >= 6 && !busy, loading = busy) {
                vm.setSyncPassword(keyPw) { onAuthed() }
            }
            Spacer(Modifier.height(14.dp))
            TextLink("返回登录") { onGoPassword() }
        }
        return
    }

    val canSubmit = email.contains("@") && code.length == 6 && !busy
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        AuthHeading(
            title = "邮箱验证码登录",
            subtitle = "用注册时绑定的邮箱收码，不用记 UID",
            markSize = 68.dp, markCorner = 19.dp, titleSize = 23.sp, markGap = 20.dp,
            center = true,
        )
        Spacer(Modifier.height(24.dp))
        AuthField(
            value = email,
            onValueChange = { email = it },
            label = "邮箱",
            placeholder = "注册时绑定的邮箱",
        )
        Spacer(Modifier.height(AUTH_FIELD_GAP))
        // ⚠️ 与注册页同一个读秒语义：只有后端真把码发出去了才 onSent() 开始倒计时。
        CodeInput(
            targetReady = email.contains("@"),
            code = code,
            onCode = { code = it },
            onSend = { onSent -> vm.sendLoginCode(email) { onSent() } },
        )
        Spacer(Modifier.height(22.dp))
        AuthPrimaryButton("进入初雪", enabled = canSubmit, loading = busy) {
            vm.loginByEmail(email, code, onNeedSetKey = { needKey = true }, onSuccess = { onAuthed() })
        }
        Spacer(Modifier.height(14.dp))
        TextLink("用 UID 和密码登录") { onGoPassword() }
    }
}

/* ═══════════════════════ ④ 注册 · 第一屏 ═══════════════════════ */

@Composable
private fun RegisterStep1(
    vm: AuthViewModel,
    busy: Boolean,
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    var nickname by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    val mailErr = emailError(email)
    // 用户 2026-10-02：「邮箱已被注册直接在输入的时候检测，会提示"已被注册换邮箱"」
    // ⚠️ 500ms 防抖：边打字边发请求会把后端打爆，也会让提示一直闪。
    val taken by vm.emailTaken.collectAsStateWithLifecycle()
    LaunchedEffect(email) {
        if (email.isBlank()) {
            vm.checkEmailTaken("")
        } else {
            delay(500)
            vm.checkEmailTaken(email)
        }
    }
    val mailMsg = when {
        mailErr != null -> mailErr
        taken -> "这个邮箱已经注册过了，换一个"
        else -> null
    }
    val canSubmit = nickname.isNotBlank() && mailMsg == null && email.isNotBlank() &&
        code.length >= 4 && !busy

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        // 用户要求（2026-10-02）：登录页与注册第一步**布局居中**
        AuthHeading(
            title = "创建账号",
            subtitle = "第 1 步，共 2 步 · 先验证邮箱",
            markSize = 52.dp, markCorner = 15.dp, titleSize = 23.sp, markGap = 18.dp,
            center = true,
        )

        Spacer(Modifier.height(22.dp))
        AuthField(
            value = nickname,
            onValueChange = { nickname = it.take(20) },
            label = "昵称",
            placeholder = "只用于显示，登录要用 UID",
        )
        Spacer(Modifier.height(AUTH_FIELD_GAP))
        AuthField(
            value = email,
            onValueChange = { email = it.trim() },
            label = "邮箱",
            placeholder = "例如 xiaoxue@qq.com",
            isError = mailMsg != null,
        )
        if (mailMsg != null) {
            Spacer(Modifier.height(6.dp))
            AuthHint(mailMsg, color = DangerRose)
        }
        Spacer(Modifier.height(AUTH_FIELD_GAP))
        CodeInput(
            targetReady = email.isNotBlank() && mailMsg == null,
            code = code,
            onCode = { code = it },
        ) { onSent ->
            // 只有真发出去了，ViewModel 才会叫 onSent —— 倒计时由此开始
            vm.sendEmailCode(email) { onSent() }
        }

        Spacer(Modifier.height(22.dp))
        AuthPrimaryButton("确定", enabled = canSubmit, loading = busy) {
            vm.registerStart(nickname, email, code) { onNext() }
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            TextLink("已经有账号了？去登录") { onBack() }
        }
    }
}

/* ═══════════════════════ ⑤ 注册 · 第二屏（uid + 头像 + 密码） ═══════════════════════ */

@Composable
private fun RegisterStep2(
    vm: AuthViewModel,
    busy: Boolean,
    onBack: () -> Unit,
    onAuthed: () -> Unit,
) {
    val pending by vm.pendingReg.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var password by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    var localAvatar by rememberSaveable { mutableStateOf<String?>(null) }

    // 头像走**本地优先**：注册期间账号还没密码，服务端 `require_user` 会挡掉上传
    //（那是刻意的护栏）。激活后由 ChatViewModel 的 pushAvatarIfMissing 补传服务器。
    val pickAvatar = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri != null) {
            val path = ImageStore.saveFromUri(
                context, uri, ImageStore.AVATAR_DIR, "reg_avatar", maxSide = 512,
            )
            if (path != null) {
                localAvatar = path
                vm.saveLocalAvatar(path)
            }
        }
    }

    // 头像预览：把刚选中的本地文件解出来给界面看。
    // 用户 2026-10-02：「第二步的上传的头像可预览」——没选只显示「选择」，选了要能看见。
    val avatarBmp by produceState<androidx.compose.ui.graphics.ImageBitmap?>(
        initialValue = null,
        localAvatar,
    ) {
        value = localAvatar?.let { path ->
            runCatching { android.graphics.BitmapFactory.decodeFile(path)?.asImageBitmap() }
                .getOrNull()
        }
    }

    val pwErr = PasswordPolicy.errorWhileTyping(password)
    val mismatch = confirm.isNotEmpty() && password != confirm
    val canSubmit = pending != null && password.isNotBlank() && password == confirm &&
        PasswordPolicy.errorOf(password) == null && !busy

    Column(Modifier.fillMaxWidth()) {
        AuthHeading(
            title = "设置密码",
            subtitle = "第 2 步，共 2 步 · 记住你的 UID",
            markSize = 52.dp, markCorner = 15.dp, titleSize = 23.sp, markGap = 18.dp,
        )

        Spacer(Modifier.height(20.dp))
        AuthGroupLabel("你的 UID")
        Spacer(Modifier.height(9.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color.White)
                .border(1.dp, Color(0x1F2F6BFF), RoundedCornerShape(14.dp))
                // 只读：可长按复制、**不可编辑**（用户明确要求）
                .combinedClickable(
                    onClick = {},
                    onLongClick = {
                        pending?.uid?.let { clipboard.setText(AnnotatedString(it)) }
                        Island.ok("uid 已复制")
                    },
                )
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = "你的 UID",
                    fontSize = 11.sp,
                    color = Color(0xFF8A9099),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = pending?.uid ?: "——",
                    fontSize = 26.sp,
                    fontWeight = FontWeight(750),
                    letterSpacing = 2.sp,
                    color = AuthInk,
                )
            }
            Spacer(Modifier.weight(1f))
            // 复制入口做成一个明确的按钮样式（原来只有一行小字，看不出能点）
            Box(
                Modifier
                    .clip(RoundedCornerShape(11.dp))
                    .background(BrandBlue.copy(alpha = 0.12f))
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            ) {
                Text(
                    text = "复制",
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight(650),
                    color = BrandBlue,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        AuthHint("登录与找回都要用它，先存好。")

        Spacer(Modifier.height(20.dp))
        AuthGroupLabel("头像（可选）")
        Spacer(Modifier.height(9.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(BrandBlueSoft)
                    .combinedClickable {
                        pickAvatar.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                val bmp = avatarBmp
                if (bmp != null) {
                    // 选完直接打满这张图（圆形裁切），不再只是「换」两个字
                    Image(
                        bitmap = bmp,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(CircleShape),
                    )
                } else {
                    Text(
                        text = "选择",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight(650),
                        color = BrandBlue,
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            AuthHint("不设也完全可以用。之后在「我的」里随时能换。")
        }

        Spacer(Modifier.height(20.dp))
        AuthGroupLabel("设置密码")
        Spacer(Modifier.height(9.dp))
        AuthField(
            value = password,
            onValueChange = { password = it },
            label = "密码",
            placeholder = "8-64 位",
            isPassword = true,
            isError = pwErr != null,
        )
        Spacer(Modifier.height(6.dp))
        AuthHint(
            text = pwErr ?: PasswordPolicy.HINT,
            color = if (pwErr != null) DangerRose else AuthFaintInk,
        )
        Spacer(Modifier.height(AUTH_FIELD_GAP))
        AuthField(
            value = confirm,
            onValueChange = { confirm = it },
            label = "再输一遍",
            placeholder = "两次要一致",
            isPassword = true,
            isError = mismatch,
        )
        if (mismatch) {
            Spacer(Modifier.height(6.dp))
            AuthHint("两次输入的密码不一样", color = DangerRose)
        }

        Spacer(Modifier.height(20.dp))
        AuthPrimaryButton("完成", enabled = canSubmit, loading = busy) {
            // 头像已落到本地资料；这里只提交密码（avatarUrl 传 null，见上方注释）
            vm.registerFinish(password, avatarUrl = null) { onAuthed() }
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            TextLink("返回上一步") { onBack() }
        }
    }
}

/* ═══════════════════════ ⑥ 忘记密码 ═══════════════════════ */

@Composable
private fun ForgotStep(
    vm: AuthViewModel,
    busy: Boolean,
    onBack: () -> Unit,
) {
    // v0.60.0：找回密码按 **UID** 定位（用户：「忘记密码的找回界面需要输入 UID」）。
    // 服务端拿 uid 反查绑定邮箱，再把验证码发过去 —— 用户不用记邮箱。
    var uid by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var newPassword by rememberSaveable { mutableStateOf("") }
    var done by rememberSaveable { mutableStateOf(false) }

    val uidOk = uid.isNotBlank() && uid.all { it.isDigit() }
    val pwErr = PasswordPolicy.errorWhileTyping(newPassword)
    val canSubmit = uidOk && code.length >= 4 && newPassword.isNotBlank() &&
        pwErr == null && !busy

    Column(Modifier.fillMaxWidth()) {
        if (done) {
            AuthHeading(
                title = "换好了",
                subtitle = "回到登录页，用 UID + 新密码进来",
                markSize = 52.dp, markCorner = 15.dp, titleSize = 23.sp, markGap = 18.dp,
            )
            Spacer(Modifier.height(24.dp))
            AuthPrimaryButton("回到登录") { onBack() }
        } else {
            AuthHeading(
                title = "重设密码",
                subtitle = "验证码会发到这个 UID 绑定的邮箱",
                markSize = 52.dp, markCorner = 15.dp, titleSize = 23.sp, markGap = 18.dp,
            )

            Spacer(Modifier.height(22.dp))
            AuthField(
                value = uid,
                onValueChange = { uid = it.filter { c -> c.isDigit() }.take(12) },
                label = "UID",
                placeholder = "注册时发给你的那串数字",
                isError = uid.isNotBlank() && !uidOk,
            )
            Spacer(Modifier.height(AUTH_FIELD_GAP))
            CodeInput(
                targetReady = uidOk,
                code = code,
                onCode = { code = it },
            ) { onSent ->
                vm.sendPasswordCode(uid) { onSent() }
            }
            Spacer(Modifier.height(AUTH_FIELD_GAP))
            AuthField(
                value = newPassword,
                onValueChange = { newPassword = it },
                label = "新密码",
                placeholder = "8-64 位",
                isPassword = true,
                isError = pwErr != null,
            )
            Spacer(Modifier.height(6.dp))
            AuthHint(
                text = pwErr ?: PasswordPolicy.HINT,
                color = if (pwErr != null) DangerRose else AuthFaintInk,
            )

            Spacer(Modifier.height(22.dp))
            AuthPrimaryButton("重设密码", enabled = canSubmit, loading = busy) {
                vm.resetPassword(uid, code, newPassword) { ok -> if (ok) done = true }
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                TextLink("返回登录") { onBack() }
            }
        }
    }
}
