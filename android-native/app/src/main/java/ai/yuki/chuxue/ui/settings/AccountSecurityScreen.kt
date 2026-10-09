package ai.yuki.chuxue.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.yuki.chuxue.data.PasswordPolicy
import ai.yuki.chuxue.ui.auth.AuthViewModel
import ai.yuki.chuxue.ui.components.LineDivider
import ai.yuki.chuxue.ui.components.SettingsGroup
import ai.yuki.chuxue.ui.components.YukiTextField
import ai.yuki.chuxue.ui.icon.YukiIcons
import ai.yuki.chuxue.ui.theme.BrandBlue
import ai.yuki.chuxue.ui.theme.BrandBlueDeep
import ai.yuki.chuxue.ui.theme.DangerRose
import ai.yuki.chuxue.ui.theme.SkyBlueDeep
import ai.yuki.chuxue.ui.theme.SnowWhite
import ai.yuki.chuxue.ui.theme.TextMuted
import ai.yuki.chuxue.ui.theme.TextPrimary
import kotlinx.coroutines.delay

/**
 * 账号安全 —— **邮箱的实际用途**（用户原话：「邮箱没有实际作用…增加实际功能」）。
 *
 * ## 这一页为什么存在
 * 上一版注册第三步能绑邮箱，但绑完之后那个邮箱**什么也不干** ——
 * 不能找回密码、不能改密码，纯粹是个展示字段。这一页把它的用途接上：
 *
 * | 状态 | 这一页显示 |
 * |---|---|
 * | 已绑邮箱 | **用邮箱改密码**（验证码 + 新密码） |
 * | 未绑邮箱 | **绑定邮箱**（同一个验证码流程，绑完就能改密码） |
 *
 * ## 一个必须说在前面的代价
 * 后端在改密码成功后会把该用户的**全部令牌删掉**（`backend/main.py` 的 password/reset）。
 * 这是对的 —— 密码变了，别处的旧会话就该失效；但对**正在用 App 的你**意味着
 * 「改完之后自己也得上重新登录」。所以成功后 [onPasswordChanged] 会被调用，
 * 由上层把人送回登录页；界面上也把这句话写在按钮上方。
 *
 * ⚠️ 渲染未经真机验证（本机无 adb / 模拟器）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSecurityScreen(
    authVm: AuthViewModel,
    onBack: () -> Unit,
    /** 密码改好了 —— 本地登录态已被清掉，上层应当把人送回登录页。 */
    onPasswordChanged: () -> Unit,
) {
    val auth by authVm.auth.collectAsStateWithLifecycle()
    val busy by authVm.busy.collectAsStateWithLifecycle()
    val error by authVm.error.collectAsStateWithLifecycle()

    // ⚠️ 邮箱用**本地登录态里那个**，不让用户手打。
    // 这不是省事：手打的话，用户可以填一个**别人的**邮箱去收验证码，
    // 而那个验证码根本改不了他的密码（后端按邮箱找账号），
    // 于是用户会得到一句看不懂的「这个邮箱没有绑定过账号」。
    val boundEmail = auth.email.orEmpty()
    // ⚠️ 找回/改密码现在按 **UID** 定位（v0.60.0）—— 服务端拿 uid 反查绑定邮箱。
    //    这里以前传的是 boundEmail；改用 UID 后如果还传邮箱，会被「UID 是一串数字」挡掉。
    val myUid = auth.uid.orEmpty()
    val hasEmail = boundEmail.isNotBlank()

    var emailInput by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var newPassword by rememberSaveable { mutableStateOf("") }
    var countdown by rememberSaveable { mutableStateOf(0) }
    var notice by remember { mutableStateOf<String?>(null) }
    // 发验证码的回话统一由 ViewModel 出（它就一条通道，三个入口共用）；
    // 这里只把它接进页面自己的那一行提示里。
    val sendNotice by authVm.notice.collectAsStateWithLifecycle()
    LaunchedEffect(sendNotice) {
        sendNotice?.let {
            notice = it
            authVm.dismissNotice()
        }
    }

    LaunchedEffect(countdown) {
        if (countdown > 0) {
            delay(1000)
            countdown--
        }
    }

    Scaffold(
        containerColor = SnowWhite,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SnowWhite,
                    titleContentColor = TextPrimary,
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(YukiIcons.Back, contentDescription = "返回")
                    }
                },
                title = { Text("账号安全", style = MaterialTheme.typography.titleMedium) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SettingsGroup(if (hasEmail) "你的邮箱" else "还没有邮箱") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        YukiIcons.Person,
                        contentDescription = null,
                        tint = SkyBlueDeep,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = if (hasEmail) boundEmail else "未绑定",
                            style = MaterialTheme.typography.bodyLarge,
                            color = TextPrimary,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = if (hasEmail) {
                                "忘了密码时，它是唯一的凭据。"
                            } else {
                                "绑一个邮箱，忘了密码时才有办法自助找回。"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                        )
                    }
                }
            }

            SettingsGroup(if (hasEmail) "用邮箱改密码" else "绑定邮箱") {
                if (!hasEmail) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                        YukiTextField(
                            value = emailInput,
                            onValueChange = { emailInput = it },
                            label = "邮箱",
                            placeholder = "例如 you@example.com",
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    LineDivider()
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Box(Modifier.weight(1f)) {
                        YukiTextField(
                            value = code,
                            onValueChange = { code = it },
                            label = "验证码",
                            placeholder = "邮件里那 6 位",
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    val target = if (hasEmail) boundEmail else emailInput.trim()
                    val canSend = !busy && target.isNotBlank() && countdown == 0
                    TextButton(
                        onClick = {
                            notice = null
                            // ⚠️ 只有真发出去了 ViewModel 才会叫这个回调 —— 读秒由此开始
                            val onSent: () -> Unit = { countdown = 60 }
                            if (hasEmail) authVm.sendPasswordCode(myUid, onSent)
                            else authVm.sendEmailCode(target, onSent)
                        },
                        enabled = canSend,
                    ) {
                        Text(
                            text = if (countdown > 0) "${countdown}s" else "发送",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (canSend) SkyBlueDeep else TextMuted,
                        )
                    }
                }

                if (hasEmail) {
                    LineDivider()
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                        YukiTextField(
                            value = newPassword,
                            onValueChange = { newPassword = it },
                            label = "新密码",
                            placeholder = "至少 6 位",
                            isPassword = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            notice?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = SkyBlueDeep,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }

            error?.let { msg ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .clickable { authVm.dismissError() },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = DangerRose,
                        modifier = Modifier.weight(1f),
                    )
                    Text("知道了", style = MaterialTheme.typography.labelMedium, color = DangerRose)
                }
            }

            if (hasEmail) {
                Text(
                    text = "改完之后，包括这台设备在内的所有登录都会失效 —— 你需要用新密码重新登录。",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }

            val canSubmit = !busy && code.isNotBlank() && when {
                // 与 PasswordPolicy 统一（原来写 6，和 MIN=8 冲突）
                hasEmail -> PasswordPolicy.errorOf(newPassword) == null
                else -> emailInput.isNotBlank()
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(50.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (canSubmit) Brush.horizontalGradient(listOf(BrandBlue, BrandBlueDeep))
                        else Brush.horizontalGradient(
                            listOf(TextMuted.copy(alpha = 0.25f), TextMuted.copy(alpha = 0.25f)),
                        ),
                    )
                    .clickable(enabled = canSubmit) {
                        notice = null
                        if (hasEmail) {
                            authVm.resetPassword(myUid, code, newPassword) { ok ->
                                if (ok) onPasswordChanged()
                            }
                        } else {
                            authVm.bindEmail(emailInput, code) { ok ->
                                if (ok) notice = "邮箱绑定成功 —— 现在可以用它改密码了"
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text(
                        text = if (hasEmail) "改密码" else "绑定邮箱",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}
