package ai.yuki.chuxue.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import ai.yuki.chuxue.ui.chat.BackgroundPreview
import ai.yuki.chuxue.ui.chat.Group
import ai.yuki.chuxue.ui.chat.SliderLine
import ai.yuki.chuxue.ui.chat.StyleLine
import ai.yuki.chuxue.ui.chat.SwitchLine
import ai.yuki.chuxue.ui.icon.YukiIcons
import ai.yuki.chuxue.ui.theme.CardGap
import ai.yuki.chuxue.ui.theme.SnowWhite
import ai.yuki.chuxue.ui.theme.TextMuted
import ai.yuki.chuxue.ui.theme.TextPrimary

/**
 * **美化设置页**（v0.61.57，用户要求「设置内增加美化设置入口和设置页」）。
 *
 * ## 它管什么
 * 全局的观感：背景图 / 遮罩（高斯模糊强度与风格）/ 顶栏透明度 / 气泡不透明度。
 * 用户原话：「用户可以设置全局自定义背景和气泡，可以设置聊天界面顶部栏的透明度」。
 *
 * ## ⚠️ 与会话级设置的关系（**会话覆盖全局**）
 * 背景与遮罩本来就有**会话级**版本（`Session.background` 等，从「对话设置 → 聊天背景」进）。
 * 这一页是**全局默认**：某段对话自己设过就以它为准，没设过才用这里的值。
 * 这与 `globalPrefixEnabled`（通用设定）是同一套思路 —— 项目里已有的模式，不新造。
 *
 * ## ⚠️ 它**不进冻结前缀**
 * 背景 / 遮罩 / 顶栏透明度都是**纯渲染**设置：`PromptEngine` 一个字都不读，
 * 所以改它们**不会**让任何会话的缓存失效（与"改人设"完全不同）。
 * 界面上不必写缓存代价提醒 —— 那是给会碎前缀的设置准备的。
 *
 * ## 关于"气泡样式"
 * 用户提到「自定义背景和气泡」。气泡的**配色**跟随主题（她=白、我=浅青），
 * 改色板要动整个主题系统（`Theme.kt`），风险与收益不成比例 —— 本轮不做。
 * 这一页给的是气泡的**不透明度**：调低让背景图从气泡里透出来。
 * 那是"自定义气泡"最直观、也最安全的那一半；配色留给主题系统。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceScreen(
    background: String?,
    scrimEnabled: Boolean,
    scrimAlpha: Float,
    scrimStyle: String,
    topBarAlpha: Float,
    /** 气泡不透明度（0..1，v0.61.57）。 */
    bubbleAlpha: Float,
    /** 点「背景图与遮罩」→ 进既有的背景选择页（那里有图库与预设）。 */
    onOpenBackground: () -> Unit,
    onSetScrim: (enabled: Boolean, alpha: Float, style: String) -> Unit,
    onSetTopBarAlpha: (Float) -> Unit,
    onSetBubbleAlpha: (Float) -> Unit,
    onBack: () -> Unit,
) {
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
                title = { Text("美化", style = MaterialTheme.typography.titleMedium) },
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
            // ── 预览：改什么都立刻在这里看到 ──
            // ⚠️ 预览放在**最上面**：这一页全是"调了才知道好不好看"的东西，
            //    把预览压在底部等于让用户来回滚着调。
            Group("预览") {
                BackgroundPreview(
                    background = background,
                    scrimEnabled = scrimEnabled,
                    scrimAlpha = scrimAlpha,
                    scrimStyle = scrimStyle,
                )
            }

            // ── 背景图 ──
            Group("背景") {
                // 复用既有的背景选择页（图库 / 预设 / 删除都在那边，不重复实现）
                NavLine(
                    title = "背景图与图库",
                    desc = if (background.isNullOrBlank()) "现在用内置默认" else "已设置",
                    onClick = onOpenBackground,
                )
            }

            // ── 遮罩（高斯模糊）──
            Group("遮罩") {
                SwitchLine(
                    title = "启用遮罩",
                    // 说清它**不是装饰** —— 用户选什么图都有看不清的时候，它是保障
                    desc = "把背景图糊掉，保证文字看得清",
                    checked = scrimEnabled,
                    onCheckedChange = { on -> onSetScrim(on, scrimAlpha, scrimStyle) },
                )
                if (scrimEnabled) {
                    SliderLine(
                        value = scrimAlpha,
                        onValueChange = { /* 拖动不落盘，见 SliderLine 的注释 */ },
                        onCommit = { v -> onSetScrim(true, v, scrimStyle) },
                    )
                    StyleLine(
                        selected = scrimStyle,
                        // ⚠️ `alpha` 是**必须的**：风格选择器上那块小预览要用它画出
                        //    "当前强度下这个风格长什么样"（否则预览与真实效果分家）
                        alpha = scrimAlpha,
                        onSelect = { s -> onSetScrim(true, scrimAlpha, s) },
                    )
                }
            }

            // ── 顶栏透明度 ──
            Group("顶栏") {
                AlphaLine(
                    title = "不透明度",
                    value = topBarAlpha,
                    hint = "越低越透（能看到背景），越高越实（字更好认）",
                    onCommit = onSetTopBarAlpha,
                )
            }

            // ── 气泡 ──
            // 用户原话：「可以设置全局自定义背景**和气泡**」。
            // 气泡的**配色**跟随主题（她=白、我=浅青）—— 改色板要动整个主题系统，
            // 风险与收益不成比例；这里给的是**透明度**：调低让背景图从气泡里透出来，
            // 那是"自定义气泡"最直观、也最安全的那一半。
            Group("气泡") {
                AlphaLine(
                    title = "不透明度",
                    value = bubbleAlpha,
                    hint = "调低让背景从气泡里透出来；太低会影响文字可读性",
                    // ⚠️ 下限 0.35 而不是 0：全透的气泡里文字直接压在背景图上会读不清
                    //    （同遮罩那条教训）。用户想要"很透"仍有余地，但不会透到不可用。
                    range = 0.35f..1f,
                    onCommit = onSetBubbleAlpha,
                )
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * 一条「不透明度」滑块（v0.61.57）。
 *
 * 顶栏与气泡共用 —— 两者都是"0..1 的不透明度"、都要同一套拖动纪律，
 * 差别只在文案与取值范围，那两样由参数给。
 *
 * ⚠️ 与 [SliderLine]（遮罩浓度）同一套"拖动不落盘、松手才写"的纪律 ——
 *    `Slider` 每像素回调一次，每次都落盘的话一次拖动就是几十次写。
 * ⚠️ 不复用 [SliderLine]：那个的标签写死了"遮罩浓度"，复用会让界面出现
 *    两个"遮罩浓度"。
 */
@Composable
private fun AlphaLine(
    title: String,
    value: Float,
    hint: String,
    onCommit: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float> = 0f..1f,
) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shown = dragging ?: value
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${(shown * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = TextMuted,
            )
        }
        Slider(
            value = shown.coerceIn(range.start, range.endInclusive),
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let(onCommit)
                dragging = null
            },
            valueRange = range,
        )
        Text(
            // 说清两个极端各是什么 —— 用户拉到端点发现"看不清"时不该以为是坏了
            hint,
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
        )
    }
}
