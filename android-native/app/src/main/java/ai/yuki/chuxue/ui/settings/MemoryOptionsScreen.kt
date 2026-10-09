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
 * **记忆设置**（v0.57.0 从「功能设置」拆出来）。
 *
 * ## 为什么拆
 * 用户 2026-10-01 要求：「功能设置页的某几组搬去设置首页做独立入口和界面」。
 * 「记忆」原来跟"聊天怎么呈现""字体多大"挤在同一页 —— 而它既不是聊天观感、
 * 也不是这台设备的外观，本来就该独立。
 *
 * ## 这一页与「管理记忆」的分工
 * 这里管**要不要记**（开关）；点「管理记忆」进去管**记了什么、删哪条** ——
 * 先进 [MemoryPersonasScreen] 选一个角色，再看该角色记得的事。
 *
 * ⚠️ 2026-10-06：记忆 2026-10-05 起是**人设级**（跨对话共享），不再"属于单段对话"，
 * 所以旧的"先选一段对话"那一步已退役（换成选角色）。
 *
 * ⚠️ 渲染未经真机验证（本机无 adb / emulator）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryOptionsScreen(
    vm: ChatViewModel,
    onBack: () -> Unit,
    /** 打开「管理记忆」（挑一段对话看/改记忆）。 */
    onOpenMemories: () -> Unit,
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
                title = { Text("记忆", style = MaterialTheme.typography.titleMedium) },
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
            CategoryTitle("Ta记得你", "Ta记住的事，以及怎么管")

            SettingsGroup("记忆") {
                SwitchLine(
                    title = "自动记住聊天里的事",
                    desc = "会偶尔回头总结你们的对话，把值得长期记住的事记下来" +
                        "（比如你的喜好、最近在忙什么）。这会少量消耗你的额度。",
                    checked = s.autoMemoryEnabled,
                    onCheckedChange = { v -> vm.saveSettings { it.copy(autoMemoryEnabled = v) } },
                )
                LineDivider()
                // ⚠️ 2026-10-06：记忆已是**人设级**（跨对话共享，2026-10-05 拍板），
                //    旧的「属于单段对话、先选一段对话」文案与入口已退役 —— 这里改成"选角色"。
                NavLine(
                    title = "管理记忆",
                    desc = "记忆属于**角色**（跨对话共享）—— 点进去选一个角色，看 Ta 记得你什么。",
                    onClick = onOpenMemories,
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}
