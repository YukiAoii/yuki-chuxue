package ai.yuki.chuxue.ui.settings

import ai.yuki.chuxue.data.EmojiCategories
import ai.yuki.chuxue.data.ImageStore
import ai.yuki.chuxue.data.room.EmojiPackEntity
import ai.yuki.chuxue.ui.ChatViewModel
import ai.yuki.chuxue.ui.components.ImageSourceButtons
import ai.yuki.chuxue.ui.components.YukiCard
import ai.yuki.chuxue.ui.components.YukiConfirmDeleteDialog
import ai.yuki.chuxue.ui.icon.YukiIcons
import ai.yuki.chuxue.ui.theme.BrandBlueSoft
import ai.yuki.chuxue.ui.theme.CardGap
import ai.yuki.chuxue.ui.theme.DangerRose
import kotlin.random.Random
import ai.yuki.chuxue.ui.theme.FieldFill
import ai.yuki.chuxue.ui.theme.FrostLine
import ai.yuki.chuxue.ui.theme.SkyBlueDeep
import ai.yuki.chuxue.ui.theme.SnowSurface
import ai.yuki.chuxue.ui.theme.SnowWhite
import ai.yuki.chuxue.ui.theme.TextMuted
import ai.yuki.chuxue.ui.theme.TextPrimary
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 表情包管理（用户 2026-09-28，依据 `表情包参考文档.txt`）。
 *
 * ## 结构（**拆成两个入口**，用户要求「人设专属 / 全局两个入口与界面」）
 * ```
 * [←] 全局表情包                ← 入口：「设置 → 功能设置 → 表情包」里的「全局表情包」
 * [←] 「初雪」的专属表情包        ← 入口：人设编辑页 / 人设详情页的「专属表情包」行
 * [开心] [难过] [生气] [害羞] [爱意]          ← 分类（预置五个，固定）
 * ┌───┐ ┌───┐ ┌───┐
 * │ 图 │ │ 图 │ │ 图 │                        ← 网格（长按删）
 * └───┘ └───┘ └───┘
 *         [添加表情包]
 * ```
 *
 * ## 归属（方案 C：专属库 + 全局库回退）
 * 一张图要么属于某个人设、要么全局共用。抽图时按**情绪分类**回退：
 * `专属[开心] → 全局[开心] → 不发` —— 判定在 `EmojiPicker.pick`。
 *
 * ⚠️ 归属由**入口**决定（见 [EmojiScope]），这一页**不再有归属 Tab**：
 *    v0.43.0 起那行是"在哪个 Tab 上传，图就归谁"；用户 2026-10-01 要求改成
 *    「人设专属 / 全局两个入口与界面」—— 入口本身就是作用域，少一次二级选择，
 *    也就少一次"存错人"。
 *
 * ## ⚠️ 这个页面**只碰 `emoji_packs` 表**
 * 它不写 `messages`、不碰设置以外的任何东西 —— 所以加删表情包**对缓存零影响**。
 * 这一点是刻意的：文档里那句"对缓存零影响，前缀永远保持字节稳定"是这个功能的
 * 前提条件，而不是附带好处。
 *
 * ⚠️ 渲染未经真机验证（本机无 adb / 无 emulator）。
 */
/**
 * 表情包库的**作用域** —— 由**入口**决定（v0.57.0）。
 *
 * 用户 2026-10-01 要求「人设专属 / 全局两个入口与界面」：不再在一页里切 Tab，
 * 进哪个入口就是哪个库。
 */
sealed interface EmojiScope {
    /** 全局库：所有人设都能用的那一批图 */
    data object Global : EmojiScope

    /** 某人设的专属库：只 Ta 用得到 */
    data class Persona(val id: String) : EmojiScope
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmojiPackScreen(
    vm: ChatViewModel,
    onBack: () -> Unit,
    /** 这一页在看**谁的**库 —— 由入口固定，见 [EmojiScope]。 */
    scope: EmojiScope,
) {
    val packs by vm.emojiPacks().collectAsStateWithLifecycle(initialValue = emptyList())
    val personas by vm.personas.collectAsStateWithLifecycle()

    /**
     * 当前归属：`null` = 全局库，非空 = 那个人设的专属库。
     *
     * ⚠️ 它**由入口固定**（[scope]），页内不再可切 —— 用户 2026-10-01 要求
     *    「人设专属 / 全局两个入口与界面」，作用域由进哪个入口决定，见 [EmojiScope]。
     */
    val owner: String? = when (scope) {
        EmojiScope.Global -> null
        is EmojiScope.Persona -> scope.id
    }

    /** 只看当前归属的图：分类计数、网格、预览、上传全都基于它。 */
    val mine = remember(packs, owner) { packs.filter { it.personaId == owner } }

    /** 当前归属的人设名（`null` = 全局）—— 只用于文案 */
    val ownerName = remember(personas, owner) { personas.firstOrNull { it.id == owner }?.displayName }

    // 分类 = 预置 + **当前归属下**已经出现过的。
    // ⚠️ 后半截是**历史遗留**：v0.43.1 起不能再新建分类，但此前建过的那些仍要留在选项里
    //    —— 否则那些分类下的图会变成"看不见、却还占着空间"。
    // ⚠️ 顺序：预置在前，自定义按首次出现的顺序接在后面 ——
    // 不用 Set 是为了保住这个顺序（SortedSet 会按字典序打乱用户的习惯）。
    val categories = remember(mine) {
        (EmojiCategories.DEFAULTS + mine.map { it.category }).distinct()
    }

    var selected by remember { mutableStateOf(EmojiCategories.DEFAULTS.first()) }
    var pendingDelete by remember { mutableStateOf<EmojiPackEntity?>(null) }
    var showAdd by remember { mutableStateOf(false) }

    /** 正在预览的图片路径（null = 没在预览） */
    var previewing by remember { mutableStateOf<String?>(null) }

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
                // 标题说清"这是谁的库" —— 两个入口进的是同一个组件，标题不分清用户会懵
                title = {
                    Text(
                        text = if (owner == null) "全局表情包" else "「${ownerName ?: "Ta"}」的专属表情包",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {

            // ── 分类 ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                categories.forEach { cat ->
                    CategoryChip(
                        label = cat,
                        count = mine.count { it.category == cat },
                        selected = cat == selected,
                        onClick = { selected = cat },
                    )
                }
                // ⚠️ 这里原来有一个「＋」新建分类（用户自定义情绪）—— v0.43.1 删掉了。
                // 自定义分类在**提示词那一侧对不上**：模型只认提示词里列出的那几个标签
                //（见 EmojiCategories.promptHint），用户新加的"得意"它永远不会输出 ——
                // 图存进去了却一辈子抽不到，是个实现不了的入口。
                // ⚠️ `categories` 仍然保留 `mine.map { it.category }`：用户此前建过的
                //    自定义分类里的图**照旧可见、可删**，不能因为删了入口就让它们消失。
            }

            // ── 网格 ──
            val inCategory = mine.filter { it.category == selected }
            if (inCategory.isEmpty()) {
                // 专属库里没有这一类、而全局里有 —— 要说清楚"她还是会发图"，
                // 否则用户会以为这个分类对她失效了（实际是回退到全局）。
                val fallingBack = owner != null &&
                    packs.any { it.personaId == null && it.category == selected }
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (fallingBack) {
                            "「$selected」里还没有专属的图。\n" +
                                "Ta会先用全局那一份 —— 想换成Ta自己的，点下面的「添加表情包」。"
                        } else {
                            "「$selected」里还没有图。\n点下面的「添加表情包」挑一张。"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextMuted,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                YukiCard(
                    Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp),
                ) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.fillMaxSize().padding(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(inCategory, key = { it.id }) { pack ->
                            EmojiCell(
                                path = pack.id,
                                onLongPress = { pendingDelete = pack },
                            )
                        }
                    }
                }
            }

            // ── 预览（用户补充第六条）──
            // 上传完能**立刻**看到它在聊天里长什么样，不必专门跑回聊天里试。
            if (inCategory.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(50))
                        .background(FieldFill)
                        .clickable { previewing = inCategory[Random.nextInt(inCategory.size)].id }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "预览效果（从「$selected」随机抽一张）",
                        style = MaterialTheme.typography.labelMedium,
                        color = SkyBlueDeep,
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            // ── 添加 ──
            Column(Modifier.padding(16.dp)) {
                if (showAdd) {
                    YukiCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(vertical = 4.dp)) {
                            Text(
                                // 归属写进标题：上传发生在当前 Tab 里，
                                // 用户得一眼看到这张图会归谁（否则最容易"存错人"）
                                text = if (owner == null) {
                                    "挑一张图加进「$selected」（所有人设都能用）"
                                } else {
                                    "挑一张图加进「$selected」（只给 ${ownerName ?: "Ta"}）"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted,
                                modifier = Modifier.padding(start = 16.dp, top = 10.dp),
                            )
                            ImageSourceButtons(
                                dir = ImageStore.EMOJI_DIR,
                                namePrefix = "emoji",
                                // 512：文档要求"自动压缩到 512px" —— 表情包是聊天里的小图，
                                // 再大只是白占空间与内存
                                maxSide = 512,
                                onPicked = { path ->
                                    // 归属 = 当前 Tab（见上面的 `owner`）
                                    vm.addEmojiPack(path, selected, owner)
                                    showAdd = false
                                },
                                onError = { showAdd = false },
                                onBusyChange = { },
                                galleryDesc = "从相册挑一张表情包",
                            )
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(50))
                            .background(BrandBlueSoft)
                            .clickable { showAdd = true }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "＋ 添加表情包",
                            style = MaterialTheme.typography.labelLarge,
                            color = SkyBlueDeep,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { pack ->
        YukiConfirmDeleteDialog(
            title = "删掉这张表情包？",
            message = "它会从「${pack.category}」里移除，应用内的副本也会删除。",
            onConfirm = {
                vm.deleteEmojiPack(pack.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }

    previewing?.let { path ->
        EmojiPreviewDialog(path = path, onDismiss = { previewing = null })
    }
}

/**
 * 预览：**按聊天里的样子**画一遍（她的气泡 + 下面那张图）。
 *
 * ⚠️ 刻意不做成"大图浏览" —— 用户想知道的是"她在聊天里发出来是什么样"，
 * 一张放大的原图回答不了这个问题（尺寸、留白、与气泡的关系都会被放大掩盖掉）。
 */
@Composable
private fun EmojiPreviewDialog(path: String, onDismiss: () -> Unit) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) { ImageStore.load(path)?.asImageBitmap() }
    }

    Dialog(onDismissRequest = onDismiss) {
        androidx.compose.material3.Surface(
            shape = RoundedCornerShape(16.dp),
            color = SnowSurface,
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "Ta在聊天里大概长这样",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                )
                Spacer(Modifier.height(12.dp))

                // 模拟：她的文字气泡 + 紧跟的图片气泡（左对齐、左下收角）
                Column(horizontalAlignment = Alignment.Start) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp))
                            .background(FieldFill)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text(
                            "……（Ta的回复）",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextMuted,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp))
                            .background(SnowWhite)
                            .border(1.dp, FrostLine, RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp))
                            .padding(3.dp),
                    ) {
                        val bmp = bitmap
                        if (bmp != null) {
                            Image(
                                bitmap = bmp,
                                contentDescription = "表情包预览",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(140.dp)
                                    .clip(RoundedCornerShape(15.dp)),
                            )
                        } else {
                            // 图读不出来时（文件被删/损坏）明确说一句，而不是给个空白
                            Box(Modifier.size(140.dp), contentAlignment = Alignment.Center) {
                                Text(
                                    "这张图读不出来了",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = DangerRose,
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                Text(
                    "知道了",
                    style = MaterialTheme.typography.labelLarge,
                    color = SkyBlueDeep,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable(onClick = onDismiss).padding(8.dp),
                )
            }
        }
    }
}

/* ═══════════════ 零件 ═══════════════ */

@Composable
private fun CategoryChip(
    label: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) BrandBlueSoft else FieldFill)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(
            text = if (count > 0) "$label $count" else label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) SkyBlueDeep else TextMuted,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/**
 * 网格里的一格。
 *
 * ⚠️ 缩略图**必须**走 `ImageStore.loadThumb`：表情包虽然压到 512px，
 * 但一屏十几张直接解码也有几十 MB（一屏 12 张 × 512×512×4 ≈ 12MB，滚动起来更糟）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EmojiCell(path: String, onLongPress: () -> Unit) {
    val thumb by produceState<ImageBitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) { ImageStore.loadThumb(path, maxSide = 240)?.asImageBitmap() }
    }
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .size(96.dp)
            .clip(shape)
            .background(FieldFill)
            .border(1.dp, FrostLine, shape)
            // 长按删除（文档：「长按图片可以删除」）。
            // 单击**不做任何事** —— 给将来的"预览大图"留位置。
            .combinedClickable(onClick = {}, onLongClick = onLongPress),
        contentAlignment = Alignment.Center,
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
}

