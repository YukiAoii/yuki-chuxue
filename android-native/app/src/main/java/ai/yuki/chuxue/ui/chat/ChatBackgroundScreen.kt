package ai.yuki.chuxue.ui.chat

import ai.yuki.chuxue.data.ImageStore
import ai.yuki.chuxue.ui.components.ChatBackgroundSurface
import ai.yuki.chuxue.ui.components.ImageSourceButtons
import ai.yuki.chuxue.ui.components.TranslucentPanel
import ai.yuki.chuxue.ui.components.LineDivider
import ai.yuki.chuxue.ui.components.YukiCard
import ai.yuki.chuxue.ui.components.YukiConfirmDeleteDialog
import ai.yuki.chuxue.ui.icon.YukiIcons
import ai.yuki.chuxue.ui.theme.BrandBlueSoft
import ai.yuki.chuxue.ui.theme.CardCorner
import ai.yuki.chuxue.ui.theme.CardGap
import ai.yuki.chuxue.ui.theme.ChatBackgrounds
import ai.yuki.chuxue.ui.theme.DangerRose
import ai.yuki.chuxue.ui.theme.FieldFill
import ai.yuki.chuxue.ui.theme.FrostLine
import ai.yuki.chuxue.ui.theme.IceCyanSoft
import ai.yuki.chuxue.ui.theme.InputPanelCorner
import ai.yuki.chuxue.ui.theme.PANEL_ALPHA
import ai.yuki.chuxue.ui.theme.ScrimPreviewBackdrop
import ai.yuki.chuxue.ui.theme.ScrimStyle
import ai.yuki.chuxue.ui.theme.SkyBlueDeep
import ai.yuki.chuxue.ui.theme.SnowSurface
import ai.yuki.chuxue.ui.theme.SnowWhite
import ai.yuki.chuxue.ui.theme.TextMuted
import ai.yuki.chuxue.ui.theme.TextPrimary
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 聊天背景设置页 —— 三件事：**挑底**、**调遮罩**、**看预览**。
 *
 * ## 为什么它是独立一页（而不是对话设置页里的一排色块）
 * 三种来源里有两种会离开本 App（相册、相机）。放在设置页里做，用户点一下就要
 * 在系统界面之间来回跳，而设置页本身很短 —— 跳回来时位置、滚动、状态全要重来。
 * 独立一页的边界更清楚：进来只为"挑一张底"。
 *
 * ## 图片为什么要「复制进来」
 * 相册 Uri 的读取授权是**临时的**。直接存 Uri，等用户清理一次相册，
 * 背景就变空白了 —— 而且看不出来是坏了（就是一片默认色）。所以一律
 * 解码→缩放→写进应用私有目录，库里只存本地路径。详见 [ImageStore]。
 *
 * ## 预览为什么必须和聊天页画同一个东西
 * 预览是这块改动**唯一能在真机上自查**的手段（本机无设备）。所以它不能是
 * "另画的示意图"，而必须调聊天页同一个 [ChatBackgroundSurface] ——
 * 否则预览好看、真进去难看，那比没有预览更糟。
 *
 * ⚠️ 渲染与相机/相册的真实往返均未经真机验证（本机无设备）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatBackgroundScreen(
    background: String?,
    scrimEnabled: Boolean,
    scrimAlpha: Float,
    scrimStyle: String,
    onSetBackground: (String?) -> Unit,
    onSetScrim: (enabled: Boolean, alpha: Float, style: String) -> Unit,
    onBack: () -> Unit,
) {
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    /** 待确认删除的**图片路径**（null = 没有待确认的）。删当前背景与删图库里的图共用这一个入口。 */
    var pendingRemove by remember { mutableStateOf<String?>(null) }

    /**
     * 图库的刷新计数。
     *
     * ⚠️ 为什么需要它：图库的重扫原本只挂在 `background` 上，于是**删掉一张
     * "不是当前在用"的图之后，列表不会刷新** —— 那张图还摆在那儿（点它没反应，
     * 因为它指向的文件已经没了）。删任何一张都要能立刻从列表里消失，所以删除成功后
     * 把这个计数 +1，让图库重扫。
     */
    var galleryVersion by remember { mutableIntStateOf(0) }

    val isImage = ChatBackgrounds.isCustomImage(background)
    val currentPath = ChatBackgrounds.customPathOf(background)

    /**
     * 挑了一张新图。
     *
     * ⚠️ **不再把上一张删掉**（用户 2026-09-28 要求完善背景功能，缺的正是"图库"）：
     * 挑过的图应该留着，想换回去不必重新进相册翻。删图有独立入口 ——
     * 「我的图库」里每张右上角那个 ×。
     *
     * 代价是私有目录会攒图（一张 100–300 KB）。所以界面上必须**一眼看出哪张在用**，
     * 且删图要两步（点 × → 确认）—— 不让人手滑删掉挑过的图。
     */
    fun adopt(path: String) {
        onSetBackground(ChatBackgrounds.customId(path))
    }

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
                title = { Text("聊天背景", style = MaterialTheme.typography.titleMedium) },
                actions = {
                    if (working) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = SkyBlueDeep,
                        )
                        Spacer(Modifier.size(16.dp))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(CardGap),
        ) {
            error?.let { msg ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .clickable { error = null },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = DangerRose,
                        modifier = Modifier.weight(1f),
                    )
                    Text("知道了", style = MaterialTheme.typography.labelMedium, color = DangerRose)
                }
            }

            /* ── ① 预览：改了立刻看得见，不必退回聊天页 ── */
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Text(
                    text = "预览",
                    style = MaterialTheme.typography.labelMedium,
                    color = SkyBlueDeep,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                )
                BackgroundPreview(
                    background = background,
                    scrimEnabled = scrimEnabled,
                    scrimAlpha = scrimAlpha,
                    scrimStyle = scrimStyle,
                )
            }

            /* ── ② 遮罩（只对自定义图片有效）── */
            Group("背景遮罩") {
                if (!isImage) {
                    Text(
                        text = "遮罩只作用在自定义图片上 —— 内置背景本身就是浅色渐变，" +
                            "不需要压。选一张自己的图就能看到它。",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    )
                } else {
                    SwitchLine(
                        title = "开启遮罩",
                        desc = "把图压淡一点，保证白气泡上的字一定看得清。关掉就是原图直铺。",
                        checked = scrimEnabled,
                        onCheckedChange = { v -> onSetScrim(v, scrimAlpha, scrimStyle) },
                    )
                    if (scrimEnabled) {
                        LineDivider()
                        SliderLine(
                            value = scrimAlpha,
                            onValueChange = { v -> onSetScrim(scrimEnabled, v, scrimStyle) },
                            onCommit = { v -> onSetScrim(scrimEnabled, v, scrimStyle) },
                        )
                        LineDivider()
                        StyleLine(
                            selected = scrimStyle,
                            alpha = scrimAlpha,
                            onSelect = { id -> onSetScrim(scrimEnabled, scrimAlpha, id) },
                        )
                    }
                }
            }

            /* ── ③ 自定义图片：挑一张 / 删掉现在这张 ── */
            Group("自定义图片") {
                ImageSourceButtons(
                    dir = ImageStore.BACKGROUND_DIR,
                    namePrefix = "bg",
                    maxSide = 1440,
                    onPicked = { adopt(it) },
                    onError = { error = it },
                    onBusyChange = { working = it },
                    galleryDesc = "挑一张图片作为这段对话的背景",
                )
                if (isImage) {
                    LineDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { pendingRemove = currentPath }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "删除这张背景图",
                            style = MaterialTheme.typography.bodyLarge,
                            color = DangerRose,
                        )
                    }
                }
            }

            /* ── ③·五 我的图库：挑过的图留在这儿，点一下就能换回去 ── */
            Group("我的图库") {
                GalleryGrid(
                    current = background,
                    version = galleryVersion,
                    onPick = { path -> onSetBackground(ChatBackgrounds.customId(path)) },
                    onDelete = { path -> pendingRemove = path },
                )
            }

            /* ── ④ 内置背景 ── */
            Group("系统背景") {
                Column(Modifier.padding(16.dp)) {
                    // 一行最多 4 个：52dp 色块 + 14dp 间距在 360dp 屏上正好放得下
                    ChatBackgrounds.presets.chunked(4).forEachIndexed { rowIndex, row ->
                        if (rowIndex > 0) Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            row.forEach { bg ->
                                SwatchItem(
                                    label = bg.label,
                                    gradient = bg.gradient,
                                    // 自定义图片时**所有预设都不算选中** ——
                                    // 否则会同时高亮"默认"与"自定义图片"，看起来像两个都选了
                                    selected = !isImage && bg.id == background,
                                    onClick = { onSetBackground(bg.id) },
                                )
                            }
                        }
                    }
                }
            }

            Text(
                text = "自定义图片会复制进应用内部，相册里删掉原图也不影响。",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                modifier = Modifier.padding(horizontal = 20.dp),
            )

            Spacer(Modifier.height(16.dp))
        }
    }

    pendingRemove?.let { path ->
        val isCurrent = path == currentPath
        YukiConfirmDeleteDialog(
            title = "删掉这张图？",
            message = if (isCurrent) {
                "这是这段对话正在用的背景，删掉后会回到默认背景。" +
                    "应用内的这份副本会被删除（相册里的原图不动）。"
            } else {
                "这张图会从图库中移除（相册里的原图不动）。"
            },
            onConfirm = {
                ImageStore.delete(path)
                // 删的正好是当前在用的那张 → 同时清掉会话里的指向，
                // 否则库里会一直留一个指向不存在文件的死路径
                if (isCurrent) onSetBackground(null)
                // 删完就重扫图库：删"非当前"那张时 `current` 没变，
                // 光靠它当 key 列表不会刷新（那张图会留在原地、点它没反应）
                galleryVersion++
                pendingRemove = null
            },
            onDismiss = { pendingRemove = null },
        )
    }
}

/* ═══════════════ 我的图库 ═══════════════ */

/**
 * 挑过的图都在这儿。
 *
 * ## 它解决什么
 * 在此之前，换一张背景图就**把上一张删了** —— 用户想换回去只能重新进相册翻。
 * 现在每张挑过的图都留在应用私有目录里，这里一屏摆出来，点一下就换。
 *
 * ## 刷新时机：`current` 或 `version` 任一变化就重扫
 * 选图 / 切图会让 `Session.background` 变（`current` 变）；删图则让 `version` 变。
 * 扫一个几十文件的目录很便宜，比"维护一个需要手动失效的缓存"简单得多，
 * 也不会出现「刚选的图没出现在图库里」或者「删掉的图还摆在那儿」这种自相矛盾。
 *
 * ## 一格多大、一行几个
 * 60dp 一格拉 4 个（60×4 + 12×3 = 276dp），在 360dp 屏上留足了两边 16dp 的边距。
 * 缩略图**必须**用 [ImageStore.loadThumb]：背景图长边 1440px，直接解码一张 8 MB，
 * 一屏十几张就会把内存吃穿。
 */
@Composable
internal fun GalleryGrid(
    current: String?,
    /** 图库刷新计数 —— 删除成功后 +1（删"非当前"那张时 `current` 不变，光靠它刷不出来） */
    version: Int,
    onPick: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    val context = LocalContext.current
    val currentPath = ChatBackgrounds.customPathOf(current)

    // 两个 key 都会触发重扫：`current` 管"选了/换了哪张"，`version` 管"删了哪张"
    val shots by produceState(initialValue = emptyList<String>(), current, version) {
        value = withContext(Dispatchers.IO) {
            ImageStore.list(context, ImageStore.BACKGROUND_DIR)
        }
    }

    if (shots.isEmpty()) {
        Text(
            text = "还没挑过图片。上面「自定义图片」里选一张，它会留在这里，以后点一下就能换回去。",
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        )
        return
    }

    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "存了 ${shots.size} 张。点一下换成它，右上角的 × 删掉。",
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
        )
        shots.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { path ->
                    GalleryCell(
                        path = path,
                        selected = path == currentPath,
                        onPick = { onPick(path) },
                        onDelete = { onDelete(path) },
                    )
                }
                // 不足一行的补齐，让每格宽度一样（否则最后一行会被拉宽）
                repeat(4 - row.size) { Spacer(Modifier.width(60.dp)) }
            }
        }
    }
}

/** 图库里的一格：缩略图 + 选中描边 + 右上角删除。 */
@Composable
internal fun GalleryCell(
    path: String,
    selected: Boolean,
    onPick: () -> Unit,
    onDelete: () -> Unit,
) {
    val thumb by produceState<ImageBitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) { ImageStore.loadThumb(path)?.asImageBitmap() }
    }
    val shape = RoundedCornerShape(10.dp)

    Box(Modifier.size(60.dp)) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(shape)
                .background(FieldFill)
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) SkyBlueDeep else FrostLine,
                    shape = shape,
                )
                .clickable(onClick = onPick),
        ) {
            thumb?.let { bmp ->
                Image(
                    bitmap = bmp,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(shape),
                )
            }
        }
        IconButton(
            onClick = onDelete,
            modifier = Modifier.size(22.dp).align(Alignment.TopEnd),
        ) {
            Icon(
                YukiIcons.Close,
                contentDescription = "删掉这张图",
                tint = DangerRose,
                modifier = Modifier.size(13.dp),
            )
        }
    }
}

/* ═══════════════ 预览 ═══════════════ */

/**
 * 一小块"聊天窗口的缩影"：背景 + 遮罩 + 一条白气泡 + 输入框。
 *
 * ⚠️ 背景与遮罩走的是聊天页**同一个** [ChatBackgroundSurface]（这是刻意的：
 * 预览与真实渲染必须是同一份实现，否则预览就是在骗人）。
 * 气泡与输入框只是示意，写死在这里。
 */
@Composable
internal fun BackgroundPreview(
    background: String?,
    scrimEnabled: Boolean,
    scrimAlpha: Float,
    scrimStyle: String,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(232.dp)
            .clip(RoundedCornerShape(CardCorner)),
    ) {
        ChatBackgroundSurface(
            background = background,
            modifier = Modifier.fillMaxSize(),
            scrimEnabled = scrimEnabled,
            scrimAlpha = scrimAlpha,
            scrimStyle = scrimStyle,
        )

        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.Bottom,
        ) {
            // 一条她说的话：白气泡 + 深色字 —— 它的可读性正是遮罩要保的东西
            Surface(
                shape = RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp),
                color = SnowSurface,
                border = androidx.compose.foundation.BorderStroke(1.dp, FrostLine),
                shadowElevation = 1.dp,
                modifier = Modifier.widthIn(max = 220.dp),
            ) {
                Text(
                    text = "这样看得清吗？",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }

            Spacer(Modifier.height(8.dp))

            // 我的一条：靠右、浅青底 —— 两种气泡的底色不同，
            // 遮罩够不够要**两边一起看**才算数（只画她那条会漏掉"我说的"那种底）
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Surface(
                    shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
                    color = IceCyanSoft,
                    border = androidx.compose.foundation.BorderStroke(1.dp, FrostLine),
                    shadowElevation = 1.dp,
                    modifier = Modifier.widthIn(max = 220.dp),
                ) {
                    Text(
                        text = "看得清，就是有点淡。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // 输入框示意：**与聊天页共用同一个外壳**（[TranslucentPanel]）——
            // 预览要是另画一份，就会变成"预览好看、进去不一样"，那比没有预览更糟
            TranslucentPanel(corner = InputPanelCorner, alpha = PANEL_ALPHA) {
                Text(
                    text = "说点什么…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMuted,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                )
            }
        }
    }
}

/* ═══════════════ 分组与行 ═══════════════ */

@Composable
internal fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = SkyBlueDeep,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )
        YukiCard(Modifier.fillMaxWidth()) {
            Column(content = content)
        }
    }
}

@Composable
internal fun SwitchLine(
    title: String,
    desc: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
            Spacer(Modifier.height(2.dp))
            Text(desc, style = MaterialTheme.typography.labelSmall, color = TextMuted)
        }
        Spacer(Modifier.size(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * 遮罩浓度。
 *
 * ⚠️ 拖动过程**不写库**：`Slider` 每像素都会调 `onValueChange`，若每次都
 * `updateSession`（它会落一次 Room），一次拖动就是几十次写盘。所以拖动只改本地
 * 显示值，**松手（`onValueChangeFinished`）才落盘一次**。
 */
@Composable
internal fun SliderLine(
    value: Float,
    onValueChange: (Float) -> Unit,
    onCommit: (Float) -> Unit,
) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shown = dragging ?: value
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "遮罩浓度",
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
            value = shown,
            onValueChange = { v ->
                dragging = v
                onValueChange(v) // 让预览跟着动，但不落盘
            },
            onValueChangeFinished = {
                dragging?.let(onCommit)
                dragging = null
            },
        )
        Text(
            "字看不清就往上调，图被压没了就往下调。",
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
        )
    }
}

/**
 * 遮罩风格：**每个选项自带一块小预览**。
 *
 * ## 为什么要带预览
 * 用户 2026-09-28 的原话里就有「各遮罩效果下的预览」。四个风格的名字
 *（浅色 / 磨砂 / 玻璃拟态 / 液态玻璃）单摆出来，用户只能靠猜 ——
 * 而"磨砂"和"玻璃拟态"在他脑子里很可能是同一个东西。
 *
 * 色块的做法：把该风格的白铺在**中灰底**上（[ScrimPreviewBackdrop]）。
 * 灰底才看得出"薄 / 厚 / 有渐变 / 有斜向高光"；铺在浅色底上四者几乎一样。
 *
 * ⚠️ 画笔来自 [ScrimStyle.brush] —— 与聊天页真实铺的那一层是**同一份实现**。
 *
 * @param alpha 用**当前浓度**画预览：这样用户拖滑块时，四个小色块会一起变深变浅，
 *              一眼看出"浓度"与"风格"是两件独立的事
 */
@Composable
internal fun StyleLine(
    selected: String,
    alpha: Float,
    onSelect: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text("遮罩风格", style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ScrimStyle.presets.forEach { style ->
                val on = style.id == selected
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (on) BrandBlueSoft else FieldFill)
                        .clickable { onSelect(style.id) }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 该风格的白，铺在中灰底上 —— 一眼看出"薄/厚/有渐变"
                    Box(
                        Modifier
                            .size(14.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(ScrimPreviewBackdrop)
                            .background(style.brush(alpha)),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = style.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (on) SkyBlueDeep else TextMuted,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            // 诚实说明：四者都不是真模糊。本机无设备，说不出哪个更好看。
            text = "四种风格的差别是「白怎么铺」——都不是真模糊。要真模糊得另做一轮。",
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
        )
    }
}

@Composable
internal fun SwatchItem(
    label: String,
    gradient: List<Color>,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        BackgroundSwatch(gradient = gradient, selected = selected)
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) SkyBlueDeep else TextMuted,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
