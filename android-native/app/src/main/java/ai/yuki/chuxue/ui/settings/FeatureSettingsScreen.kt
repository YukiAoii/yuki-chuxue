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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.yuki.chuxue.data.FONT_SCALE_LARGE
import ai.yuki.chuxue.data.FONT_SCALE_STANDARD
import ai.yuki.chuxue.data.FONT_SCALE_XLARGE
import ai.yuki.chuxue.data.SEND_MODE_INSTANT
import ai.yuki.chuxue.data.SEND_MODE_STREAM
import ai.yuki.chuxue.data.TYPE_SPEED_FAST
import ai.yuki.chuxue.data.TYPE_SPEED_NORMAL
import ai.yuki.chuxue.data.TYPE_SPEED_SLOW
import ai.yuki.chuxue.ui.ChatViewModel
import ai.yuki.chuxue.ui.icon.YukiIcons
import ai.yuki.chuxue.ui.theme.BrandBlueSoft
import ai.yuki.chuxue.ui.theme.CardGap
import ai.yuki.chuxue.ui.theme.FieldFill
import ai.yuki.chuxue.ui.theme.SkyBlueDeep
import ai.yuki.chuxue.ui.theme.SnowWhite
import ai.yuki.chuxue.ui.theme.TextMuted
import ai.yuki.chuxue.ui.theme.TextPrimary
import kotlin.math.roundToInt

/**
 * **功能设置页**（用户要求：从设置页里拆出来）。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * 这里**没有**思考模式开关（这是刻意的）
 * ═══════════════════════════════════════════════════════════════════════════
 * 用户 2026-09-27 反馈：「思考模式开关我感觉是冲突了因为对话设置和全局设置界面
 * 都有一个开关」。
 *
 * 这个观察是对的，而且根因是**同一个设置出现在两个作用域里**：
 *   · 对话设置页的开关 = 「**这段对话**要不要思考」
 *   · 全局设置页的开关 = 「**以后新建的对话**默认要不要思考」
 * 两者都叫"思考模式"，用户当然会觉得冲突 —— 他改了一个，另一个没动。
 *
 * **解法：全局那个直接删掉，只留对话里的。** 新会话的初值改为固定为「开」
 * （聊天类产品里，用户想省钱时会去关某一轮，而不是希望默认关闭）。
 * 少一个开关，就少一处"为什么这里和那里不一样"。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * 上报：**没有开关，也不该有**（用户要求）
 * ═══════════════════════════════════════════════════════════════════════════
 * 用户要求：「上报使用统计删除改为静默直接上报 不需要开启关闭这是软件的全局默认值」。
 *
 * 所以这里只留一句**说明**（不是开关）：让用户知道发生了什么、发了什么、没发什么。
 * 说明写在明面上比藏在开关后面更诚实 —— 开关会让用户以为"关了就完全不出网"，
 * 而他真正需要知道的是**到底发了什么**（答案是：只有计数，没有对话内容）。
 *
 * ⚠️ 渲染未经真机验证（本机无设备）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeatureSettingsScreen(
    vm: ChatViewModel,
    onBack: () -> Unit,
) {
    val s by vm.settings.collectAsStateWithLifecycle()

    // ⚠️ 导出/导入备份的 launcher 与二次确认**已搬到 `SettingsBackupScreen`**（v0.50.5）——
    //    连同它们原来那几条注释。这一页现在只管"聊天怎么呈现"。

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
                title = { Text("功能设置", style = MaterialTheme.typography.titleMedium) },
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
            // ── 分类标题 ──
            //
            // 用户 2026-09-29："设置页现在入口杂乱你自己分类一下"。
            // 原来是 8 个分组平铺一页，要滚很久才找得到"字体大小"。
            // 现在按**用户什么时候会想改它**分四类（不是按功能的技术归属 ——
            // "这属于隐私还是数据"对用户没有意义，他只想找到那个开关）。
            CategoryTitle("和Ta相处", "聊天怎么呈现 —— 天天会碰到的那几个")
            SettingsGroup("聊天体验") {
                SwitchLine(
                    title = "思考内容默认收起",
                    desc = "思考的过程会折起来，想看的时候点一下就能展开。" +
                        "关掉的话，思考内容会一直显示。",
                    checked = s.thinkingCollapseEnabled,
                    onCheckedChange = { v -> vm.saveSettings { it.copy(thinkingCollapseEnabled = v) } },
                )
                LineDivider()
                ChoiceLine(
                    title = "回复怎么出现",
                    desc = "「逐字出现」：Ta 一边说一边往外冒字，第一句来得最快；" +
                        "「整段出现」：等 Ta 全写完再一起给你（读长回复更完整，但要多等一会儿）。",
                    // ⚠️ 选项名换成**用户能听懂的话**（v0.61.14，用户要求）：
                    //    「流式 / 非流式」是技术词 —— 用户看不懂，还容易跟下面的「分段气泡」混。
                    options = listOf(
                        SEND_MODE_STREAM to "逐字出现",
                        SEND_MODE_INSTANT to "整段出现",
                    ),
                    selected = s.sendMode,
                    onSelect = { v -> vm.saveSettings { it.copy(sendMode = v) } },
                )
                // ⚠️ **只有「逐字出现」才给这个开关**（v0.61.14，用户要求）：
                //    「整段出现」时她的话本就是一整段落地的，拆分无从谈起 ——
                //    留着开关只会让人以为"我开了怎么没效果"。
                if (s.sendMode == SEND_MODE_STREAM) {
                    LineDivider()
                    SwitchLine(
                        title = "分段气泡",
                        desc = "Ta 的一条回复按句子拆成连续几枚小气泡，像真人连发消息那样" +
                            "（含代码块的回复不拆）。关掉则整段一枚。",
                        checked = s.splitBubbles,
                        onCheckedChange = { v -> vm.saveSettings { it.copy(splitBubbles = v) } },
                    )
                }
                LineDivider()
                ChoiceLine(
                    title = "打字速度",
                    desc = "逐字冒出来的快慢（「整段出现」时用不上 —— 那时 Ta 一次说完）。" +
                        "只影响观感 —— 收流、历史、费用都不受它影响。",
                    options = listOf(
                        TYPE_SPEED_SLOW to "慢",
                        TYPE_SPEED_NORMAL to "标准",
                        TYPE_SPEED_FAST to "快",
                    ),
                    selected = s.typeSpeed,
                    onSelect = { v -> vm.saveSettings { it.copy(typeSpeed = v) } },
                )
                LineDivider()
                SwitchLine(
                    title = "回车直接发送",
                    desc = "关掉之后回车是换行，只能按右侧按钮发。" +
                        "中文输入法用回车选词，觉得总误发就关掉它。",
                    checked = s.enterToSend,
                    onCheckedChange = { v -> vm.saveSettings { it.copy(enterToSend = v) } },
                )
            }

            /* ── 体验 ── */
            CategoryTitle("这台设备", "外观、隐私、数据都在这一台手机上")
            SettingsGroup("外观") {
                ChoiceLine(
                    title = "字体大小",
                    // 说清"只放大文字"：用户真正会担心的是"会不会把界面撑乱"
                    desc = "只放大文字，间距和图标不动 —— 所以不会把布局撑乱。" +
                        "想连按钮一起放大，用系统的字体设置。",
                    options = listOf(
                        FONT_SCALE_STANDARD to "标准",
                        FONT_SCALE_LARGE to "大",
                        FONT_SCALE_XLARGE to "特大",
                    ),
                    selected = s.fontScale,
                    onSelect = { v -> vm.saveSettings { it.copy(fontScale = v) } },
                )
            }

            SettingsGroup("隐私") {
                Text(
                    text = "你的对话、人设、记忆全部只存在这台手机上，我们看不到。" +
                        "密钥也不会上传。",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextPrimary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
                LineDivider()
                Text(
                    text = "App 会匿名上报使用次数（装了多少人、聊了多少条、缓存省了多少），" +
                        "用来了解整体使用情况。上报内容里不含任何对话文字、" +
                        "人设内容或密钥。",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }

            // ⚠️ v0.50.5：原来这里有「数据」（导出/导入）与「高级」（缓存诊断、
            //    通用设定）两组 —— 它们已按用户要求**拆成独立页**，
            //    入口移到设置首页（见 `SettingsBackupScreen` / `SettingsAdvancedScreen`）。
            //    用户原话：「把功能设置再分出去几个放到设置列表做几个入口和专门的界面」。

            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * 分类标题 —— 比 [SettingsGroup] 的组标题**高一层**。
 *
 * ## 为什么要分两层标题
 * 用户 2026-09-29："设置页现在入口杂乱你自己分类一下"。
 * 原来是 8 个组平铺一页，滚动很久才找得到"字体大小"。
 * 现在按**用户什么时候会想改它**分成四类（和她相处 / 她记得你 / 这台设备 / 高级）。
 *
 * 层次靠三件事拉开，缺一样都会和组标题糊在一起：
 * 1. **字号更大**（`titleSmall` vs 组标题的 `labelMedium`）；
 * 2. **颜色更深**（`TextPrimary` vs 组标题的品牌蓝）——
 *    同一页里两个蓝色标题会读成同级；
 * 3. **上方留白更多** —— 它是"新一章"的起手，不是某一组的附属说明。
 *
 * @param desc 一句话说清这一类装的是什么，让用户不必逐组扫
 */
@Composable
internal fun CategoryTitle(title: String, desc: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, top = 14.dp, bottom = 10.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = TextPrimary,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = desc,
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
        )
    }
}

/**
 * 一行**概率**（0..1）滑块。
 *
 * ⚠️ 拖动过程**不落盘**：`Slider` 每像素都回调，直接 `saveSettings` 会让一次拖动写几十次
 * DataStore。所以拖动只改本地显示值，**松手才提交一次**（与对话设置页的遮罩浓度同款做法）。
 */
@Composable
internal fun ChanceLine(
    title: String,
    desc: String,
    value: Float,
    onCommit: (Float) -> Unit,
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
                color = SkyBlueDeep,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(desc, style = MaterialTheme.typography.labelSmall, color = TextMuted)
        Slider(
            value = shown,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let(onCommit)
                dragging = null
            },
            valueRange = 0f..1f,
        )
    }
}

/**
 * 单行「多选一」——横排芯片。
 *
 * 与 [SwitchLine] 的分工：开关只能表达**是 / 否**，撑不起三个以上的档位
 * （「关闭 / 慢 / 标准 / 快」用四个开关会疯）。芯片是"在几个里挑一个"的通用表达，
 * 与搜索层的过滤芯片、对话设置页的思考强度同款。
 */
@Composable
internal fun <T> ChoiceLine(
    title: String,
    desc: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
        Spacer(Modifier.height(2.dp))
        Text(desc, style = MaterialTheme.typography.labelSmall, color = TextMuted)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (value, label) ->
                val on = value == selected
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (on) BrandBlueSoft else FieldFill,
                    modifier = Modifier.clickable { onSelect(value) },
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (on) SkyBlueDeep else TextMuted,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                    )
                }
            }
        }
    }
}

/**
 * 可点击的导航行（带右箭头）。
 *
 * 与 [SwitchLine] / [ChoiceLine] 的区别：那两个是**改值**，这个是**去别处**。
 * 形状上留一个箭头，用户一眼能分清"点一下会变"和"点一下会跳"。
 */
@Composable
internal fun NavLine(title: String, desc: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
            Spacer(Modifier.height(2.dp))
            Text(desc, style = MaterialTheme.typography.labelSmall, color = TextMuted)
        }
        Spacer(Modifier.size(12.dp))
        Icon(
            YukiIcons.ChevronRight,
            contentDescription = null,
            tint = TextMuted,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
internal fun SwitchLine(
    title: String,
    desc: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    // v0.61.48：云端记忆模式下三项固定为开、**不可单独关**（用户 2026-10-06 拍板）。
    // 默认 true，所有既有调用方行为不变。
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            // v0.61.50：禁用时标题一并置灰 —— 否则"标题看着能点、只有开关颗灰"（审查指出）
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) TextPrimary else TextMuted,
            )
            Spacer(Modifier.height(2.dp))
            Text(desc, style = MaterialTheme.typography.labelSmall, color = TextMuted)
        }
        Spacer(Modifier.size(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}
