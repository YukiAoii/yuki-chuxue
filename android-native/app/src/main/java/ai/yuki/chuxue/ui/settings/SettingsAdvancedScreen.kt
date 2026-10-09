package ai.yuki.chuxue.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.yuki.chuxue.data.DEFAULT_GLOBAL_PREFIX
import ai.yuki.chuxue.ui.ChatViewModel
import ai.yuki.chuxue.ui.icon.YukiIcons
import ai.yuki.chuxue.ui.theme.CardGap
import ai.yuki.chuxue.ui.theme.SkyBlueDeep
import ai.yuki.chuxue.ui.theme.SnowWhite
import ai.yuki.chuxue.ui.theme.TextMuted
import ai.yuki.chuxue.ui.theme.TextPrimary

/**
 * **高级设定**（v0.50.5 从「功能设置」拆出）。
 *
 * ## 为什么它单独一页，而不是留在功能设置里
 * 用户原话：「把功能设置再分出去几个放到设置列表做几个入口和专门的界面」。
 *
 * 这一页装的都是**改错会有代价**的东西：
 * - 通用设定会进入**每一个**对话的开头，改动会让已存在的对话成本上升；
 * - 缓存诊断是唯一会持续影响花费的指标所在。
 *
 * 它们在功能设置页里和"回复怎么出现"这种日常开关挤在一起，
 * 结果是**天天用的和一辈子不碰的没有边界** —— 分类标题能缓解一点，
 * 但缓解不了"新人滚到这一屏会以为必须看懂"。独立成页 + 页首一句警告，
 * 才真正把"这不是给你准备的"表达出来。
 *
 * ⚠️ 渲染未经真机验证（本机无设备）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsAdvancedScreen(
    vm: ChatViewModel,
    onBack: () -> Unit,
    onOpenCacheDiagnostics: () -> Unit,
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
                title = { Text("高级设定", style = MaterialTheme.typography.titleMedium) },
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
            CategoryTitle("改之前先看清说明", "这几项会影响所有对话，不确定就别动")

            SettingsGroup("通用设定") {
                Text(
                    text = "一套「可共享的行为规则」。「默认不开启」 —— 要用到哪个角色，" +
                        "去「人设 → 编辑 → 通用设定」里单独打开" +
                        "（打开会让那个角色的已有对话缓存失效一次，费用略升）。",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
                LineDivider()
                SwitchLine(
                    title = "默认使用通用设定",
                    // ⚠️ 这个开关**只作用于「从未单独设置过」的角色**（= 升级前就有的老数据）。
                    //    它是升级前行为的延续，保证老用户的前缀一个字节不变、缓存不碎。
                    //    新建角色一律显式关，所以新人不受它影响。
                    desc = "只影响「升级前创建、且还没单独设置过」的角色 —— " +
                        "让它们继续像以前一样使用通用设定。新建的角色默认关。",
                    checked = s.globalPrefixEnabled,
                    onCheckedChange = { v -> vm.saveSettings { it.copy(globalPrefixEnabled = v) } },
                )
                LineDivider()
                OutlinedTextField(
                    value = s.globalPrefix,
                    onValueChange = { v -> vm.saveSettings { it.copy(globalPrefix = v) } },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    minLines = 5,
                    shape = RoundedCornerShape(12.dp),
                    label = { Text("内容") },
                )
                Text(
                    text = "改动这里会影响所有对话的开头部分，之前的对话费用会略微上升。" +
                        "不确定就别改。",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
                if (s.globalPrefix != DEFAULT_GLOBAL_PREFIX) {
                    LineDivider()
                    Text(
                        text = "恢复默认设定",
                        style = MaterialTheme.typography.bodyLarge,
                        color = SkyBlueDeep,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { vm.saveSettings { it.copy(globalPrefix = DEFAULT_GLOBAL_PREFIX) } }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                    )
                }
            }

            SettingsGroup("诊断") {
                NavLine(
                    title = "缓存诊断",
                    desc = "看看缓存命中率与 token 用量 —— 它是唯一会持续影响花费的指标。",
                    onClick = onOpenCacheDiagnostics,
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}
