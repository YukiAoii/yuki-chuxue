package ai.yuki.chuxue.ui.auth

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.yuki.chuxue.ui.components.SettingsGroup
import ai.yuki.chuxue.ui.components.YukiTextField
import ai.yuki.chuxue.ui.icon.YukiIcons
import ai.yuki.chuxue.ui.components.Island
import ai.yuki.chuxue.ui.theme.BrandBlue
import ai.yuki.chuxue.ui.theme.BrandBlueDeep
import ai.yuki.chuxue.ui.theme.SnowWhite
import ai.yuki.chuxue.ui.theme.TextMuted
import ai.yuki.chuxue.ui.theme.TextPrimary
import kotlinx.coroutines.delay

/**
 * **补绑 / 换绑邮箱**（v0.61.0）。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * 为什么需要这一屏
 * ═══════════════════════════════════════════════════════════════════════════
 * 早期版本的注册流程**没有邮箱**这一步，所以存量用户里有一批 `email` 为空。
 * 而「忘记密码」现在靠邮箱收验证码 —— 不绑，这些人忘了密码就**没有任何
 * 自助找回的路**。这不是体验问题，是账号能不能拿回来的问题。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * 这一版重做了什么（用户 2026-10-02）
 * ═══════════════════════════════════════════════════════════════════════════
 * 上一版借用的是**认证页**那套视觉（全屏渐变背景 + 居中大标题）。但这一屏是
 * **登录之后**才进的，用户此刻已经在软件里面了 —— 再给他一个"登录页"，观感上
 * 像被踢出去了。
 *
 * 现在改成**软件内的页面风格**，与「设置 → 账号安全」那一族完全一致：
 *   `Scaffold`（SnowWhite 底）+ `TopAppBar`（返回 + 标题）+ `SettingsGroup` 卡片
 *   + `YukiTextField` + 同一款渐变主按钮。
 *
 * 左侧返回、右侧滚动、分组卡片 —— 用户不需要重新学一遍怎么用。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * 同一屏干两件事
 * ═══════════════════════════════════════════════════════════════════════════
 * [currentEmail] 有值 = **换绑**（把旧的换成新的）；空 = **补绑**。
 * 表单完全一样，只有文案和按钮名不同 —— 没必要复制一份实现。
 *
 * ⚠️ 渲染未经真机验证（本机无 adb）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BindEmailScreen(
    vm: AuthViewModel,
    onBack: () -> Unit,
    onDone: () -> Unit,
    /** 当前已绑的邮箱；没有就传 null。有值 → 换绑，空 → 补绑。 */
    currentEmail: String? = null,
) {
    val rebind = !currentEmail.isNullOrBlank()
    val title = if (rebind) "换绑邮箱" else "绑定邮箱"

    var email by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var countdown by rememberSaveable { mutableIntStateOf(0) }
    val busy by vm.busy.collectAsStateWithLifecycle()
    val taken by vm.emailTaken.collectAsStateWithLifecycle()
    // ⚠️ 这个页面原来**一个错误、一句回话都不显示**：发验证码的三种结局
    //    （真发了 / 邮件没配 / 发失败）在界面上长得一模一样，用户根本看不出到底发没发出去。
    //    两条通道都接上。
    val error by vm.error.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()

    LaunchedEffect(error) {
        error?.let {
            Island.error(it)
            vm.dismissError()
        }
    }
    LaunchedEffect(notice) {
        notice?.let {
            Island.ok(it)
            vm.dismissNotice()
        }
    }

    LaunchedEffect(countdown) {
        if (countdown > 0) {
            delay(1000)
            countdown--
        }
    }

    // 与注册页同一套实时查重：已注册过的邮箱不能再绑到**别的**账号上
    LaunchedEffect(email) {
        if (email.isBlank()) {
            vm.checkEmailTaken("")
        } else {
            delay(500)
            vm.checkEmailTaken(email)
        }
    }

    val mailErr = emailError(email)
    val mailMsg = when {
        mailErr != null -> mailErr
        taken -> "这个邮箱已经绑到别的账号上了，换一个"
        else -> null
    }
    val canSubmit = mailMsg == null && email.isNotBlank() && code.length >= 4 && !busy
    val canSend = countdown == 0 && email.isNotBlank() && mailMsg == null

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
                title = { Text(title, style = MaterialTheme.typography.titleMedium) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
        ) {
            SettingsGroup("说明") {
                Text(
                    text = if (rebind) {
                        "当前绑的是 $currentEmail。换成新的之后，找回密码就用新的那个了。"
                    } else {
                        "绑上邮箱之后才能用邮箱找回密码，换设备也认得出你。" +
                            "你的账号是早期版本注册的，当时没有留邮箱。"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMuted,
                    modifier = Modifier.padding(16.dp),
                )
            }

            Spacer(Modifier.height(6.dp))

            SettingsGroup("邮箱") {
                Column(Modifier.padding(16.dp)) {
                    YukiTextField(
                        value = email,
                        onValueChange = { email = it.trim() },
                        label = "邮箱地址",
                        placeholder = "例如 xiaoxue@qq.com",
                        isError = mailMsg != null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (mailMsg != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = mailMsg,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFE5484D),
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            SettingsGroup("验证码") {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            YukiTextField(
                                value = code,
                                onValueChange = { code = it.filter { c -> c.isDigit() }.take(8) },
                                label = "收到的验证码",
                                placeholder = "6 位数字",
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        // 固定宽度：文字在「获取验证码」和「60s」之间变，不锁宽度按钮会缩
                        Box(
                            modifier = Modifier
                                .width(104.dp)
                                .height(48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (canSend) BrandBlue.copy(alpha = 0.12f) else Color(0xFFEFF2F7))
                                .clickable(enabled = canSend) {
                                    // ⚠️ 不在点击时开始读秒：等"真发出去了"的回调。
                                    //    点就倒数的旧写法会让用户对着读秒按钮等一封不来的邮件。
                                    vm.sendEmailCode(email) { countdown = 60 }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = if (countdown > 0) "${countdown}s 后可重发" else "获取验证码",
                                fontSize = if (countdown > 0) 11.sp else 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (canSend) BrandBlue else TextMuted,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

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
                        vm.bindEmail(email, code) { ok -> if (ok) onDone() }
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(20.dp).width(20.dp),
                        color = SnowWhite,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(
                        text = if (rebind) "换绑" else "绑定",
                        style = MaterialTheme.typography.titleSmall,
                        color = SnowWhite,
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "以后再说",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextMuted,
                    modifier = Modifier
                        .clickable(onClick = onBack)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
