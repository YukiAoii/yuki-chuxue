package ai.yuki.chuxue.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * **首次打开的选择页**（v0.60.0）。
 *
 * ## 版式：逐项对齐设计稿实测值
 * `docs/design/认证界面_设计稿_v2_2026-10-02.html` 里这一屏量出来是：
 * 内容**左对齐**（x=26，不是居中）、图标 96/圆角 26、图标→标题 24、
 * 标题 27px、标题→副标题 9、副标题→按钮 30、主次按钮之间 11、
 * 次按钮→协议行 16；整块在竖向上**居中**（上 237 / 下 238）。
 *
 * ## 三条行为要求（用户 2026-10-02）
 * 1. **只在第一次打开出现** —— 判据是 `Store.welcomeSeen()`。
 * 2. **必须勾选协议才能登录或注册** —— 未勾选时两个按钮都是禁用态。
 * 3. **没有游客通道** —— 用户要求「没有登录的用户必须登录不能到软件的真实界面」。
 *    所以这页**没有 ×**（× 唯一合理的去向是"先不用"，那条路已经不存在了），
 *    往前只有两个出口：登录、注册。
 *
 * ## 文案（用户指定，逐字）
 * 标题「欢迎使用Yuki初雪」｜副标题「于人于物，细水长流弥足珍贵。」
 *
 * ⚠️ 渲染未经真机验证（本机无 adb）。
 */
@Composable
fun WelcomeScreen(
    onLogin: () -> Unit,
    onRegister: () -> Unit,
    onOpenTerms: () -> Unit,
    onOpenPrivacy: () -> Unit,
) {
    // 勾选状态**不持久化**：每次冷启动都要重新同意一次。
    // 协议会变，用户每次都该有机会看见它。
    var agreed by rememberSaveable { mutableStateOf(false) }

    AuthBackdrop {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = AUTH_PAD),
            // ⚠️ 竖向居中是设计稿的行为；**横向不居中** —— 设计稿里所有元素都从 x=26 起
            verticalArrangement = Arrangement.Center,
        ) {
            AuthBrandMark(size = 96.dp, corner = 26.dp)

            Spacer(Modifier.height(24.dp))
            Text(
                text = "欢迎使用Yuki初雪",
                fontSize = 27.sp,
                lineHeight = 37.sp,
                fontWeight = FontWeight.W700,
                letterSpacing = (-0.4).sp,
                color = AuthInk,
            )

            Spacer(Modifier.height(9.dp))
            Text(
                text = "于人于物，细水长流弥足珍贵。",
                fontSize = 13.5.sp,
                lineHeight = 23.sp,
                color = AuthSubInk,
            )

            Spacer(Modifier.height(30.dp))
            // 未勾选协议 → 两个入口都不可点（用户要求"必须勾选才能进行登录或者注册"）
            AuthPrimaryButton("登录", enabled = agreed, onClick = onLogin)
            Spacer(Modifier.height(11.dp))
            AuthSecondaryButton("注册新账号", enabled = agreed, onClick = onRegister)

            Spacer(Modifier.height(16.dp))
            AuthAgreementRow(
                checked = agreed,
                onCheckedChange = { agreed = it },
                onOpenTerms = onOpenTerms,
                onOpenPrivacy = onOpenPrivacy,
            )
        }
    }
}
