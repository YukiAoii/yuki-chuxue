package ai.yuki.chuxue.ui.auth

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ai.yuki.chuxue.ui.components.YukiDialog

/**
 * **老用户补绑邮箱的提醒**（v0.60.0 · 用户 2026-10-02 要求）。
 *
 * ## 谁会看到它
 * 登录态存在、但 `email` 为空的人 —— 也就是**早期版本注册的老用户**：
 * 那时候注册只要昵称 + 密码，没有邮箱这一步（`AuthViewModel` 的注册现在是两段式，
 * 第一段就强制验证邮箱，所以**新用户不会走到这里**）。
 *
 * ## 为什么值得打断一次
 * 「忘记密码」从 v0.60.0 起**只能靠邮箱收验证码**。没绑邮箱的用户一旦忘密码，
 * 没有任何自助找回的路 —— 这不是体验问题，是**账号能不能拿回来**的问题。
 * 所以值得在进主页时提醒一次。
 *
 * ## 提醒的节奏
 * **每次启动最多一次**（由调用方的 `rememberSaveable` 控制），不是"永久只弹一次"。
 * 用户点「以后再说」只是**这一次**推迟 —— 下次打开还会看到。
 * 这是刻意的：可以烦一点，但不能让一个能救账号的提醒被永久关掉。
 *
 * ⚠️ 渲染未经真机验证（本机无 adb）。
 */
@Composable
fun BindEmailPrompt(
    onGoBind: () -> Unit,
    onLater: () -> Unit,
) {
    YukiDialog(
        title = "还没绑邮箱",
        onConfirm = onGoBind,
        onDismiss = onLater,
        confirmText = "去绑定",
        dismissText = "以后再说",
    ) {
        Column {
            Text(
                text = "你的账号是在早期版本注册的，当时没有留邮箱。",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "绑一个吧 —— 不绑的话，万一忘记密码就只能重新注册了。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
