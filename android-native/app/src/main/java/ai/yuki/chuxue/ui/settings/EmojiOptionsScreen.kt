package ai.yuki.chuxue.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.yuki.chuxue.ui.ChatViewModel
import ai.yuki.chuxue.ui.icon.YukiIcons
import ai.yuki.chuxue.ui.theme.CardGap
import ai.yuki.chuxue.ui.theme.SnowWhite
import ai.yuki.chuxue.ui.theme.TextMuted
import ai.yuki.chuxue.ui.theme.TextPrimary

/**
 * **表情包设置**（v0.57.0 从「功能设置」拆出来）。
 *
 * ## 为什么拆
 * 用户 2026-10-01 要求：「功能设置页的某几组搬去设置首页做独立入口和界面」。
 * 原来「功能设置」一页装了聊天体验 / 表情包 / 记忆 / 外观 / 隐私五组 ——
 * 一个只想改字体大小的人要划过三段与字体无关的说明。拆完这一页只回答
 * 「她什么时候会发图」，另一页（[EmojiPackScreen]「全局表情包」）回答「有哪些图」。
 *
 * ⚠️ **专属表情包不在这里** —— 它跟人走（用户要求「专属的添加入口放在人设编辑界面」），
 *    入口在「人设」→ 编辑 Ta → 专属表情包。
 *
 * ⚠️ 渲染未经真机验证（本机无 adb / emulator）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmojiOptionsScreen(
    vm: ChatViewModel,
    onBack: () -> Unit,
    /** 打开全局图库（[EmojiPackScreen]）。 */
    onOpenGlobalEmoji: () -> Unit,
) {
    val s by vm.settings.collectAsStateWithLifecycle()

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
                title = { Text("表情包", style = MaterialTheme.typography.titleMedium) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(CardGap),
        ) {
            CategoryTitle("和Ta相处", "Ta 什么时候会发图、从哪个图库取")

            SettingsGroup("表情包") {
                SwitchLine(
                    title = "启用表情包",
                    desc = "角色情绪强烈时，会从你本地的图库里挑一张发出来。" +
                        "图片只在手机上，不上传，也不影响对话的缓存。",
                    checked = s.emojiEnabled,
                    onCheckedChange = { v -> vm.saveSettings { it.copy(emojiEnabled = v) } },
                )
                if (s.emojiEnabled) {
                    LineDivider()
                    ChanceLine(
                        title = "默认发送概率",
                        desc = "所有人设的出厂默认值。想给某个角色单独调，去那个人设的设置里覆盖。" +
                            "概率越低，图越难得、越像「随手发的」；调高会更容易看到图。",
                        value = s.emojiChance,
                        onCommit = { v -> vm.saveSettings { it.copy(emojiChance = v) } },
                    )
                    LineDivider()
                    NavLine(
                        title = "全局表情包",
                        desc = "所有人设都能用的图，按情绪分类。没给 Ta 加专属图时，Ta 从这里取。",
                        onClick = onOpenGlobalEmoji,
                    )
                }
            }

            Text(
                text = "想让某个角色用 Ta 自己的图：去「人设」→ 点开 Ta → 编辑 → 专属表情包。",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                modifier = Modifier.padding(horizontal = 4.dp),
            )

            Spacer(Modifier.height(16.dp))
        }
    }
}
