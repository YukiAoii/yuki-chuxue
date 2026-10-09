package ai.yuki.chuxue.ui

import ai.yuki.chuxue.data.ProviderGroup
import ai.yuki.chuxue.ui.components.YukiAvatar
import ai.yuki.chuxue.ui.components.YukiDialog
import ai.yuki.chuxue.ui.theme.TextMuted
import ai.yuki.chuxue.ui.theme.TextPrimary
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 新用户引导弹窗**该不该出现**（v0.61.21）—— 纯函数，因此可测。
 *
 * ## 用户要的那个弹窗
 * 原话：「做个新用户注册登录进来之后有个弹窗，提示就说"检测到你是新注册用户，
 * 如果你没有 API，请点击弹窗按钮的创建，我将为你自动拉取模型一键配置好，
 * 你直接创建人设就好啦"。也要带功能，要有个「不用」的按钮 ——
 * 因为有些用户自己是有服务地址和 API 的，让他们进行选择。」
 *
 * ## 为什么判据不是"刚注册完"
 * 「注册成功那一刻」拿不到可靠信号（多设备、重装、退出再登都会经过那里），
 * 而**状态**是可靠的：**已登录 + 一个人设都没有 + 手上没有任何能用的 key + 没弹过**。
 * 这一组条件同时满足的人，只可能是"刚进来、什么都还没有"的用户。
 *
 * ⚠️ **老用户不会被误伤**：升上来的老用户一定有人设（否则他也没用过这个 App），
 *    所以哪怕 [seen] 缺省是 false，他也过不了第一条。
 *
 * ⚠️ 弹窗**不替用户建人设** —— 用户原话就是「你直接创建人设就好啦」，
 *    所以「创建」只做"拉模型 + 配好"，建人设仍是他自己的事。
 */
fun shouldShowNewUserPrompt(
    loggedIn: Boolean,
    personaCount: Int,
    /** 手上有没有**任何**能用的密钥：全局设置里的，或某个分组里的。 */
    hasAnyApiKey: Boolean,
    /** 这个弹窗以前弹过没有（点过任一按钮就算弹过）。 */
    seen: Boolean,
): Boolean = loggedIn && personaCount == 0 && !hasAnyApiKey && !seen

/**
 * 「手上有没有能用的密钥」——把两个来源并起来判。
 *
 * ⚠️ 必须**两个来源都看**：免费分组那条路的密钥在**分组**里（`ProviderGroup.apiKey`），
 *    不在全局设置里。只看全局的话，刚「一键配置」完的用户会被判成"没有 key"，
 *    于是弹窗关不掉、一直糊在他脸上。
 */
fun hasAnyApiKey(settingsApiKey: String?, groups: List<ProviderGroup>): Boolean =
    !settingsApiKey.isNullOrBlank() || groups.any { it.apiKey.isNotBlank() }

/**
 * 新用户引导弹窗（v0.61.21）。
 *
 * ## 两个按钮各是什么
 * · **「创建」** —— 调 `vm.refreshFreeGroup()`：拉免费分组 → 设为当前 → 自动勾上第一个模型。
 *   这就是用户要的"自动拉取模型一键配置好"。它**不替用户建人设** ——
 *   原话是「你直接创建人设就好啦」，所以建人设仍是他自己的事。
 * · **「不用」** —— 给他自己填地址和 Key 的出口。**不是"以后别烦我"**，
 *   而是"我自己有"；两条路都会把它标记成"弹过了"，所以不会再出现。
 *
 * ## 为什么不自动帮他配好、直接跳过这一步
 * 有些用户**本来就有**服务地址和 API Key（原话：「因为有些用户自己是有服务地址和 api 的，
 * 让他们进行选择」）。替他做主等于把他自己的配置换掉 —— 那是他最不想要的。
 *
 * ⚠️ 只写"弹过了"这一个标记，**不碰任何配置** —— 所以点哪个按钮都不会改坏他的东西。
 */
@Composable
fun NewUserWelcomeDialog(onCreate: () -> Unit, onSkip: () -> Unit) {
    YukiDialog(
        title = "先把它配好",
        confirmText = "创建",
        onConfirm = onCreate,
        dismissText = "不用",
        onDismiss = onSkip,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // 雪晶：新用户还没有人设头像，这里用 App 自己的那只 —— 比一块空白的图更像个"人"。
            YukiAvatar(size = 72.dp, path = null)
            Spacer(Modifier.height(12.dp))
            Text(
                text = "检测到你是新注册用户。如果你没有 API，点「创建」——" +
                    "我会自动拉取模型、一键配置好，你直接去创建人设就好啦。",
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "自己已经有服务地址和 API Key 的，点「不用」，" +
                    "到「设置 → 连接设置」里填你自己的那份。",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
            )
        }
    }
}
