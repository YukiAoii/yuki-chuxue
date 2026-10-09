package ai.yuki.chuxue.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ai.yuki.chuxue.data.AppSettings
import ai.yuki.chuxue.data.COMPRESS_MODE_ASK
import ai.yuki.chuxue.data.COMPRESS_MODE_AUTO
import ai.yuki.chuxue.data.COMPRESS_MODE_MANUAL
import ai.yuki.chuxue.data.Session
import ai.yuki.chuxue.ui.components.LineDivider
import ai.yuki.chuxue.ui.components.SettingsGroup
import ai.yuki.chuxue.ui.components.YukiCard
import ai.yuki.chuxue.ui.components.YukiConfirmDeleteDialog
import ai.yuki.chuxue.ui.icon.YukiIcons
import ai.yuki.chuxue.ui.theme.CardGap
import ai.yuki.chuxue.ui.theme.ChatBackgrounds
import ai.yuki.chuxue.ui.theme.DangerRose
import ai.yuki.chuxue.ui.theme.FrostLine
import ai.yuki.chuxue.ui.theme.SkyBlueDeep
import ai.yuki.chuxue.ui.theme.SnowWhite
import ai.yuki.chuxue.ui.theme.TextMuted
import ai.yuki.chuxue.ui.theme.TextPrimary
import kotlin.math.roundToInt

/**
 * 对话设置页 —— 聊天窗口右上角进入（不是全局设置，也不是人设页）。
 *
 * ## 为什么思考开关放在这里而不是留在全局设置
 * 「思考模式」的开与关是**逐会话的体验取舍**：和她深聊时想看她怎么想，
 * 随口闲聊时只想要快、想要省。全局设置从此只承担一个职责 ——
 * **新会话的初值**（见 `ChatViewModel.newSession`）。
 *
 * ## 布局：一组设置 = 一张卡片
 *
 * 上一版的每一项都各自铺一块浅色底、彼此紧贴、且**顶到屏幕两边**：
 * 组标题有 16dp 缩进而卡片没有，于是标题与卡片左边缘错位；
 * 几十个浅色块连成一片，扁平风赖以为生的"留白分层"就没了 —— 看起来"很乱"。
 *
 * 现在改成现代设置页的通行结构：
 * **一个分组 = 一张 [YukiCard]，组内用分隔线切行，组与组之间留 [CardGap]**。
 * 卡片自己带阴影与圆角，组内行不画背景 —— 层次来自"卡片浮在纯色背景上"。
 *
 * ## 这里有一条要交代给用户的代价
 * 「删除聊天记录」是**不可逆**的，而且它连带把这段对话的缓存一起作废，
 * 下一轮会重新建立缓存（费用上去再下来）。所以它被放在最后、
 * 并且走项目的二次确认对话框，不与普通开关混在一起。
 *
 * ⚠️ 渲染未经真机验证（本机无设备）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatSettingsScreen(
    session: Session?,
    onBack: () -> Unit,
    onUpdate: ((Session) -> Session) -> Unit,
    onDelete: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenBackground: () -> Unit,
    onOpenMemory: () -> Unit,
    /** 打开「这段对话的看板」（缓存 / 用量 / 余额）*/
    onOpenBoard: () -> Unit,
    /**
     * 全局设置。压缩模式与阈值存在这里（**不随会话走**）——
     * "到点了要不要自动压"是用户对整台 App 的偏好，不是某段对话的属性。
     */
    settings: AppSettings,
    /** 改全局设置（走与其它设置同一套落盘路径） */
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
    /**
     * 她**正在生成回复**（v0.61.5）。
     *
     * 用户要求：「在模型输入运行期间不可以切换模型、不可以切换思考模式和思考强度」——
     * 生成中改这些，会让这一轮实际发的参数与界面显示的不一致。
     * （模型切换的锁在聊天页已有；这里补上「思考」组两个控件的锁。）
     */
    busy: Boolean = false,
) {
    var pendingDelete by remember { mutableStateOf(false) }

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
                title = { Text("对话设置", style = MaterialTheme.typography.titleMedium) },
            )
        },
    ) { padding ->
        if (session == null) {
            // 会话可能刚被删掉 —— 不崩，给一句话
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("这段对话已经不在了", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
            // 组间距 = 卡片间距（`CardGap`）。扁平风靠留白 + 阴影分层，
            // 组一挤，那 2dp 阴影就失去意义。
            verticalArrangement = Arrangement.spacedBy(CardGap),
        ) {
            SettingsGroup("思考") {
                SwitchLine(
                    title = "开启思考模式",
                    desc = "只对这段对话生效。开着时你能看到回答背后的思考过程，但更慢也更贵。",
                    checked = session.thinkingEnabled,
                    onCheckedChange = { v -> onUpdate { it.copy(thinkingEnabled = v) } },
                    enabled = !busy,
                )
                if (session.thinkingEnabled) {
                    LineDivider()
                    EffortLine(
                        selected = session.reasoningEffort,
                        onSelect = { level -> onUpdate { it.copy(reasoningEffort = level) } },
                        enabled = !busy,
                    )
                }
            }

            SettingsGroup("对话") {
                NavLine(
                    title = "查找聊天记录",
                    desc = "在这段对话里搜关键词，点结果跳过去。",
                    onClick = onOpenSearch,
                )
                LineDivider()
                SwitchLine(
                    title = "消息免打扰",
                    desc = "不会主动用通知打扰你。对话本身不受影响。",
                    checked = session.muted,
                    onCheckedChange = { v -> onUpdate { it.copy(muted = v) } },
                )
                LineDivider()
                SwitchLine(
                    title = "置顶聊天",
                    desc = "排在其他会话前面。",
                    checked = session.pinned,
                    onCheckedChange = { v -> onUpdate { it.copy(pinned = v) } },
                )
            }

            SettingsGroup("记忆") {
                NavLine(
                    title = "管理记忆",
                    // 描述与人设编辑页的入口**逐字一致** —— 两处通向同一个页面，
                    // 说法不同会让用户以为它们管的是两件事。
                    desc = "写下你希望被一直记得的事。记忆只在相关时出现，改动它不影响对话缓存。",
                    onClick = onOpenMemory,
                )
                NavLine(
                    title = "看板",
                    // 说明里点明"余额是账号的"—— 入口在会话里，不写清会让人误会
                    desc = "这段对话的缓存命中率、用量与花费；余额是账号级的。",
                    onClick = onOpenBoard,
                )
            }

            SettingsGroup("上下文") {
                ModeLine(
                    selected = settings.compressMode,
                    onSelect = { mode -> onUpdateSettings { it.copy(compressMode = mode) } },
                )
                LineDivider()
                ThresholdLine(
                    threshold = settings.compressThreshold,
                    onSelect = { v -> onUpdateSettings { it.copy(compressThreshold = v) } },
                )
            }

            SettingsGroup("外观") {
                NavLine(
                    title = "聊天背景",
                    // 描述与背景选择页共用同一个口径（ChatBackgrounds.summaryOf）——
                    // 两处各写一段 when 就会出现"设置页说晨雾、选择页说默认"
                    desc = ChatBackgrounds.summaryOf(session.background),
                    onClick = onOpenBackground,
                )
            }

            SettingsGroup("危险操作") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { pendingDelete = true }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    Text(
                        "删除聊天记录",
                        style = MaterialTheme.typography.bodyLarge,
                        color = DangerRose,
                    )
                }
                Text(
                    "这段对话与里面全部消息都会被删除，无法恢复；" +
                        "它的缓存也会一并作废（下一轮重新建立，费用先升后降）。",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }

    if (pendingDelete) {
        YukiConfirmDeleteDialog(
            title = "删除这段对话？",
            message = "「${session?.title.orEmpty()}」以及里面的全部消息都会被删除，无法恢复。",
            onConfirm = {
                pendingDelete = false
                onDelete()
            },
            onDismiss = { pendingDelete = false },
        )
    }
}

/* ═══════════════ 分组内的行 ═══════════════ */
/* 分组骨架（SettingsGroup / LineDivider）已抽到 `ui/components/SettingsGroup.kt`
   —— 人设编辑页是第二个消费方，共用的规则说明写在那里。 */

@Composable
private fun SwitchLine(
    title: String,
    desc: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    /** false = 锁住（变灰 + 点了没反应）。生成中锁思考模式用 —— 见 [ChatSettingsScreen] 的 busy */
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
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

@Composable
private fun NavLine(title: String, desc: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
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

/**
 * 思考强度：**滑块**（用户 2026-09-28 要求，此前是三个药丸芯片）。
 *
 * ## 为什么滑块与三档不冲突
 * 强度是**离散**的（`low` / `high` / `max`）—— 滑块只是它们的另一种表达：
 * `steps = 1` 让滑块只有三个停靠点，拖过去就"咔"在一档上，不会停在中间。
 *
 * ## ⚠️ 拖动过程不落盘
 * `Slider` 每像素都会回调，而 `onUpdate` 每次都会写一次 Room。拖动中直接落盘，
 * 一次拖动就是几十次写库。所以拖动只改**本地显示值**，**松手才提交一次**
 *（与「聊天背景」页的遮罩浓度滑块同一套做法）。
 *
 * ## ⚠️ 它不动缓存
 * 改的是请求里的 `reasoning_effort` 参数，**不碰 `messages`** ——
 * 缓存认的是 messages 的字节序列，所以调它不会让这段对话的前缀失效。
 */
@Composable
private fun EffortLine(selected: String, onSelect: (String) -> Unit, enabled: Boolean = true) {
    val levels = listOf("low", "high", "max")
    val labels = listOf("低", "高", "最高")
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shown = dragging ?: levels.indexOf(selected).coerceAtLeast(0).toFloat()

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "思考强度",
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) TextPrimary else TextMuted,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = labels[shown.roundToInt().coerceIn(0, labels.lastIndex)],
                style = MaterialTheme.typography.labelMedium,
                color = SkyBlueDeep,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Slider(
            value = shown,
            enabled = enabled,
            // 拖动只改本地值 —— 预览（这里的档名）跟着动，但不写库
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { v ->
                    onSelect(levels[v.roundToInt().coerceIn(0, levels.lastIndex)])
                }
                dragging = null
            },
            valueRange = 0f..2f,
            steps = 1,
        )
        Row(Modifier.fillMaxWidth()) {
            labels.forEachIndexed { index, label ->
                if (index > 0) Spacer(Modifier.weight(1f))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                )
            }
        }
    }
}

/**
 * 压缩触发模式：三选一（v0.48.0，用户要求"做个选项用户自己选择"）。
 *
 * ## 三档各是什么
 * - **自动**：占用到阈值就压，压完在聊天界面留一条系统提示；
 * - **询问**（默认）：到阈值**只提示**（上下文弹窗里一行 + 高亮手动按钮），
 *   ⚠️ **不弹模态确认框** —— 用户原话「不确定就不在弹出选择确认弹窗」；
 * - **手动**：什么都不做，纯靠用户自己点（= 旧行为）。
 *
 * ## ⚠️ 它是一条"会花钱"的设置，描述必须说清
 * 压缩是**唯一会主动让缓存失效**的动作：摘要一进 history，下一轮请求前缀全变，
 * 那一次全额按未命中计费。所以三档的说明按代价从大到小排，且默认档是最保守的"询问"。
 *
 * 与 [EffortLine] 同一套做法：**拖动不落盘**，松手才提交一次（`steps = 1` → 三个停靠点）。
 */
@Composable
private fun ModeLine(selected: String, onSelect: (String) -> Unit) {
    val modes = listOf(COMPRESS_MODE_AUTO, COMPRESS_MODE_ASK, COMPRESS_MODE_MANUAL)
    val labels = listOf("自动", "询问", "手动")
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shown = dragging ?: modes.indexOf(selected).coerceAtLeast(0).toFloat()

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "压缩触发",
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = labels[shown.roundToInt().coerceIn(0, labels.lastIndex)],
                style = MaterialTheme.typography.labelMedium,
                color = SkyBlueDeep,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Slider(
            value = shown,
            // 拖动只改本地值 —— 预览（档名）跟着动，但不写库
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { v ->
                    onSelect(modes[v.roundToInt().coerceIn(0, modes.lastIndex)])
                }
                dragging = null
            },
            valueRange = 0f..2f,
            steps = 1,
        )
        Row(Modifier.fillMaxWidth()) {
            labels.forEachIndexed { index, label ->
                if (index > 0) Spacer(Modifier.weight(1f))
                Text(label, style = MaterialTheme.typography.labelSmall, color = TextMuted)
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = when (modes[shown.roundToInt().coerceIn(0, modes.lastIndex)]) {
                COMPRESS_MODE_AUTO -> "到阈值就自动压缩，压完在聊天里告诉你。压缩那一次缓存会重建（费用先升后降）。"
                COMPRESS_MODE_MANUAL -> "不提示也不自动，只有你自己在上下文弹窗里点「压缩更早的记录」。"
                else -> "到阈值只在上下文弹窗里提示你（不弹窗打断），要不要压由你决定。"
            },
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
        )
    }
}

/**
 * 压缩阈值：占用到多少时算"该压缩了"。
 *
 * ## 为什么用**离散档**而不是连续滑块
 * 与 [EffortLine] 同理：用户想的是"早点压 / 晚点压"，不是"压到 0.73 这个数"。
 * 离散档还顺手解决了"拖动过程中要不要落库"——松手必落在某一档上。
 *
 * ⚠️ 阈值是 `estimateContext / 上限`（含人设 + 附录 + 本轮输入），
 * 与弹窗里那条"距自动压缩"进度条**同一个口径**，否则设置与显示会对不上。
 */
@Composable
private fun ThresholdLine(threshold: Float, onSelect: (Float) -> Unit) {
    // ⚠️ v0.61.56：档位从 0.5~0.9 扩到 **0.2~0.9**（用户报「怎么改都不会触发」）。
    //    实测（`CompressTriggerRealityProbe`）：100 轮普通对话只占 5,507 token，
    //    而旧档位最低的 0.5 配 128K 窗口 = 触发线 64,000 token ≈ **1162 轮** ——
    //    普通用户永远碰不到，于是"怎么改都没用"。
    //    新增低档后，0.2 档 ≈ 465 轮、0.3 档 ≈ 697 轮 —— 长聊用户能真正触发。
    //    ⚠️ 没改默认值（仍是 0.7）：改默认会**悄悄改变所有老用户**的压缩时机
    //    （压缩会让下一轮按未命中计费）。宁可让用户主动调，也不替他改。
    val stops = listOf(0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f, 0.9f)
    var dragging by remember { mutableStateOf<Float?>(null) }
    // 找不到就落到最接近的一档（老数据里若存了 0.75 这类值也不会崩）
    val nearest = stops.minByOrNull { kotlin.math.abs(it - threshold) } ?: 0.7f
    val shown = dragging ?: stops.indexOf(nearest).coerceAtLeast(0).toFloat()

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "压缩阈值",
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${(stops[shown.roundToInt().coerceIn(0, stops.lastIndex)] * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = SkyBlueDeep,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Slider(
            value = shown,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { v ->
                    onSelect(stops[v.roundToInt().coerceIn(0, stops.lastIndex)])
                }
                dragging = null
            },
            valueRange = 0f..(stops.size - 1).toFloat(),
            steps = stops.size - 2,
        )
        Row(Modifier.fillMaxWidth()) {
            stops.forEachIndexed { index, s ->
                if (index > 0) Spacer(Modifier.weight(1f))
                Text(
                    "${(s * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            "上下文占用到这个比例就算「该压缩了」。调低更省 token，但压缩更频繁。",
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
        )
    }
}

/**
 * 背景预设的色块预览（供背景选择页复用）。
 *
 * 色块画的就是聊天页要铺的那组色（[ChatBackgrounds] 是唯一事实来源）——
 * 于是选项与实际效果之间不存在"预览与真实渲染不一致"这种偏差。
 */
@Composable
fun BackgroundSwatch(
    gradient: List<Color>,
    selected: Boolean,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = modifier
            .size(52.dp)
            .clip(shape)
            .background(
                if (gradient.size == 1) SolidColor(gradient.first())
                else Brush.verticalGradient(gradient),
            )
            // 选中态用主色描边 + 加粗：**不打勾图标** ——
            // 一个勾在 52dp 的浅色块上很难看清，描边本身已经足够明确
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) SkyBlueDeep else FrostLine,
                shape = shape,
            ),
    )
}
