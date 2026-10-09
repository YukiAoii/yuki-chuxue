package ai.yuki.chuxue.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import ai.yuki.chuxue.service.ProactiveNotifier
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.material3.Slider
import kotlin.math.roundToInt
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.yuki.chuxue.data.ImageStore
import ai.yuki.chuxue.data.Persona
import ai.yuki.chuxue.data.UserPersona
import ai.yuki.chuxue.data.EmojiCategories
import ai.yuki.chuxue.ui.TimeLabels
import ai.yuki.chuxue.ui.settings.SwitchLine
import ai.yuki.chuxue.ui.components.EmptyHint
import ai.yuki.chuxue.ui.components.ImageSourceButtons
import ai.yuki.chuxue.ui.components.LineDivider
import ai.yuki.chuxue.ui.components.SettingsGroup
import ai.yuki.chuxue.ui.components.YukiAvatar
import ai.yuki.chuxue.ui.components.YukiCard
import ai.yuki.chuxue.ui.components.YukiConfirmDeleteDialog
import ai.yuki.chuxue.ui.components.YukiDialog
import ai.yuki.chuxue.ui.components.YukiTextField
import ai.yuki.chuxue.ui.icon.YukiIcons
import ai.yuki.chuxue.ui.theme.BrandBlue
import ai.yuki.chuxue.ui.theme.BrandBlueDeep
import ai.yuki.chuxue.ui.theme.DangerRose
import ai.yuki.chuxue.ui.theme.FrostLine
import ai.yuki.chuxue.ui.theme.CardGap
import ai.yuki.chuxue.ui.theme.FieldCorner
import ai.yuki.chuxue.ui.theme.FieldFill
import ai.yuki.chuxue.ui.theme.IceCyanSoft
import ai.yuki.chuxue.ui.theme.OutlineLight
import ai.yuki.chuxue.ui.theme.SkyBlueDeep
import ai.yuki.chuxue.ui.theme.NavSpaceForContent
import ai.yuki.chuxue.ui.theme.SnowSurface
import ai.yuki.chuxue.ui.theme.SnowWhite
import ai.yuki.chuxue.ui.theme.TextMuted
import ai.yuki.chuxue.ui.theme.TextPrimary
import ai.yuki.chuxue.ui.theme.WarnAmber
import ai.yuki.chuxue.ui.theme.WarnAmberBg
import java.util.UUID

/**
 * 人设管理 —— 独立页面（用户要求：人设不该塞在 API 配置里）。
 *
 * 设计理念（开发文档 2.1）：**最小必填 + 最大自由**。
 * 只强制昵称与性别（每轮都要用来称呼用户），其余全写在一个自由编辑框里。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonaScreen(
    vm: ChatViewModel,
    onBack: (() -> Unit)? = null,
    /**
     * 长按菜单里的「开始新对话」。
     *
     * 本页不知道"聊天页"在哪（它是主界面的一个 Tab，切 Tab 是上层的事），
     * 所以这里只喊一声、由 `MainScreen` 接住。默认空实现 = 这一项不出现。
     */
    onStartChat: (String) -> Unit = {},
    /**
     * 长按菜单里的「记忆库」。
     *
     * ⚠️ 记忆是**按会话**存的（用户 2026-09-28 明确"记忆只属于单段对话"），不是按人设 ——
     * 所以这一项也交给上层：它挑出该人设最近的一段会话再跳过去。
     */
    onOpenMemory: (String) -> Unit = {},
    /**
     * **点一下人设卡 → 进详情页**（v0.56.0，用户要求）。
     *
     * ⚠️ 以前是直接进编辑界面 —— 用户想"看看她是谁、我们聊过什么"时，
     *    迎面而来的是"角色设定""思考强度"一堆输入框。"看"和"改"该分开。
     */
    onOpenDetail: (String) -> Unit = {},
    /**
     * 「专属表情包」→ 那个人设的专属表情包库（v0.57.0，用户要求「两个入口」）。
     *
     * 入口在编辑页的「专属表情包」行；路由由上层（`MainScreen` → `MainActivity`）负责 ——
     * 与 `onStartChat` / `onOpenMemory` 同一个道理：本页不认识路由。
     */
    onOpenPersonaEmoji: (String) -> Unit = {},
) {
    val personas by vm.personas.collectAsStateWithLifecycle()
    // 分组依据是「最近聊过」—— 那要看会话。人设本身没有"最近使用时间"这类字段，
    // 而会话里的 personaId 是现成的依据（不必新增字段，也就没有迁移代价）。
    val sessions by vm.sessions.collectAsStateWithLifecycle()

    /** 人设 → 最后一次聊它的时间。既用于分组，也用于卡片上那行时间。 */
    val lastChatAt = remember(sessions) {
        sessions.groupBy { it.personaId }.mapValues { (_, list) -> list.maxOf { it.updatedAt } }
    }

    /** 人设 → 她最后说的那句（卡片副标题用）。空串 = 还没聊过。 */
    val lastPreview = remember(sessions) {
        sessions.groupBy { it.personaId }
            .mapValues { (_, list) -> list.maxByOrNull { it.updatedAt }?.preview.orEmpty() }
    }

    // ── 「我（扮演）」段（v0.61.43）──
    val userPersonas by vm.userPersonas.collectAsStateWithLifecycle()
    /** 顶部两段：0 = Ta 们（AI 角色），1 = 我（用户自己的角色）。 */
    var segment by remember { mutableStateOf(0) }
    /** 正在编辑的用户人设（null = 不在编辑）。 */
    var editingUserPersona by remember { mutableStateOf<UserPersona?>(null) }

    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Persona?>(null) }
    var pendingDelete by remember { mutableStateOf<Persona?>(null) }
    /** 长按哪张卡弹出的操作面板（null = 没开） */
    var menuFor by remember { mutableStateOf<Persona?>(null) }
    /** 待确认「清空对话记录」的人设 */
    var pendingClear by remember { mutableStateOf<Persona?>(null) }
    /** 排序：true = 按最近聊过，false = 按创建时间 */
    var sortByRecent by remember { mutableStateOf(true) }

    // 编辑中按系统返回键 → 退回人设列表，而不是退出 App。
    // 本页是主界面的 Tab（onBack == null），没有父级可代劳，必须自己接住返回键。
    BackHandler(enabled = editing != null) { editing = null }

    // ⚠️ 2026-10-06 修用户报的 bug：「新建/编辑我的角色时按返回键直接退出到桌面」。
    //    根因 = 上面那个 BackHandler 只覆盖了 **AI 角色**编辑（`editing`），
    //    「我的角色」编辑器（`editingUserPersona`）**没有对应的 BackHandler** ——
    //    于是按返回时 `enabled=false`，落到系统默认行为（退出 Activity）。
    //    ⚠️ 两条 BackHandler 的 enabled 必须互斥：同时为 true 时 Compose 只跑
    //    最后注册的那个，会让另一个编辑器的返回失效。
    BackHandler(enabled = editingUserPersona != null) { editingUserPersona = null }

    // ── 「我（扮演）」的编辑屏（v0.61.43）──
    editingUserPersona?.let { ed ->
        UserPersonaEditor(
            initial = ed,
            isNew = userPersonas.none { it.id == ed.id },
            onSave = {
                vm.upsertUserPersona(it)
                editingUserPersona = null
            },
            onCancel = { editingUserPersona = null },
            onDelete = {
                vm.deleteUserPersona(ed.id)
                editingUserPersona = null
            },
        )
        return
    }

    if (editing != null) {
        PersonaEditor(
            initial = editing!!,
            isNew = personas.none { it.id == editing!!.id },
            // 老数据（useGlobalPrefix == null）在界面上要显示**实际生效值** ——
            // 那就是设置里的全局开关。显示成"关"会让老用户以为通用设定失效了。
            globalPrefixFallback = vm.settings.value.globalPrefixEnabled,
            // 服务端开关决定「通用设定」那一块显不显示（v0.58.0）
            globalPrefixAvailable = vm.features.value.personaGlobalPrefix,
            // 「Ta 面前的我」选择器的数据源（v0.61.43）
            userPersonas = userPersonas,
            onSave = {
                vm.upsertPersona(it)
                editing = null
            },
            onCancel = { editing = null },
            // 编辑页的「专属表情包」行 → 专属库（v0.57.0）
            onOpenEmoji = onOpenPersonaEmoji,
        )
        return
    }

    Scaffold(
        containerColor = SnowWhite,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SnowSurface,
                    titleContentColor = TextPrimary,
                ),
                navigationIcon = {
                    // Tab 模式（onBack == null）不显示返回键：主界面切 Tab 不进回退栈
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(YukiIcons.Back, contentDescription = "返回")
                        }
                    }
                },
                title = { Text("人设", style = MaterialTheme.typography.titleMedium) },
                actions = {
                    IconButton(
                        onClick = {
                            // 「+」按当前段新建对应的东西（v0.61.43）
                            if (segment == 1) editingUserPersona = blankUserPersona()
                            else editing = blankPersona()
                        },
                    ) {
                        Icon(
                            YukiIcons.Add,
                            contentDescription = if (segment == 1) "新建我的角色" else "新建人设",
                            tint = SkyBlueDeep,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // 顶部两段（v0.61.43）：「Ta 们」是 AI 角色、「我」是用户自己的角色。
            // 分开摆的理由：两个列表的心智不同（"Ta 是谁" vs "我是谁"），
            // 混在一屏里会互相抢注意力。
            PersonaSegmentRow(selected = segment, onSelect = { segment = it })
            if (segment == 1) {
                UserPersonaPane(
                    list = userPersonas,
                    personas = personas,
                    onEdit = { editingUserPersona = it },
                    onNew = { editingUserPersona = blankUserPersona() },
                )
                return@Column
            }
            if (personas.isEmpty()) {
                // 与消息列表共用同一个空态组件（components.EmptyHint）——
                // 同一个东西两处各画一份必然漂移（项目已有这条教训）
                EmptyHint(
                    icon = YukiIcons.Person,
                    title = "还没有人设",
                    desc = "人设决定角色是谁。创建一个，就能开始对话。",
                    actionLabel = "创建人设",
                    onAction = { editing = blankPersona() },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                // 好友列表式的排布 + **搜索** + **分组**（用户要求：
                // 「人设列表界面更像好友列表 有分组 有搜索 和消息列表做出差异化」）。
                //
                // ## 与消息列表的差异是刻意的
                // 会话列表回答的是"哪段对话有新消息" —— 所以它必须有时间、预览、未读点。
                // 人设列表回答的是"**她是谁**" —— 所以卡片更大、有分组标题、可以搜。
                // 两者混同的代价是：用户得在两堆长得一样的行里找**不同**的东西。
                PersonaSearchBar(
                    query = query,
                    onQueryChange = { query = it },
                    onClear = { query = "" },
                )

                // 排序开关（用户要求"允许按最后聊天时间或创建时间排序"）。
                // ⚠️ 它不是筛选：两种排序下**人都在**，只是先后不同 —— 所以做成一行小切换，
                //    而不是第二排 Tab（那会让人误以为"切了就少了人"）。
                SortToggle(byRecent = sortByRecent, onToggle = { sortByRecent = !sortByRecent })

                val filtered = remember(personas, query) { filterPersonas(personas, query) }
                val sections = remember(filtered, lastChatAt, sortByRecent) {
                    buildPersonaSections(filtered, lastChatAt, sortByRecent)
                }

                if (filtered.isEmpty()) {
                    EmptyHint(
                        icon = YukiIcons.Search,
                        title = "没有找到「$query」",
                        desc = "换个词试试 —— 名字、称呼、角色设定都能搜。",
                        actionLabel = "清除搜索",
                        onAction = { query = "" },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 8.dp,
                            // ⚠️ 让出悬浮导航栏 —— 是"让出"而不是"截断"（与消息列表同一口径）。
                            //    原来只写 vertical = 8.dp，底部留白不够：滚到底后最后一张卡
                            //    正落在悬浮胶囊底下，点不到（用户 2026-10-05 报）。
                            //    92dp 的来历见 NavSpaceForContent（胶囊高 56 + 距底 12 + 余量）。
                            bottom = 8.dp + NavSpaceForContent,
                        ),
                        verticalArrangement = Arrangement.spacedBy(CardGap),
                    ) {
                        sections.forEach { section ->
                            item(key = "group-${section.title}") {
                                Column {
                                    PersonaGroupHeader(section.title)
                                    Spacer(Modifier.height(8.dp))
                                    // v0.61.21（用户 2026-10-05）：「每个人设之间的间隔合并、但要能区分边界」——
                                    // 组内所有行收进**同一张卡片**，行与行之间不再留缝，改用一条细线分界
                                    // （微信 / QQ 的联系人列表就是这个样子）。
                                    // 组与组之间仍有 CardGap 的间隔（分组标题把它们分开）。
                                    YukiCard(modifier = Modifier.fillMaxWidth()) {
                                        section.items.forEachIndexed { i, p ->
                                            PersonaRow(
                                                persona = p,
                                                lastChatAt = lastChatAt[p.id],
                                                lastPreview = lastPreview[p.id].orEmpty(),
                                                onClick = { onOpenDetail(p.id) },
                                                onLongClick = { menuFor = p },
                                            )
                                            // 最后一行后面不画线：边界是"行与行之间"的事
                                            if (i != section.items.lastIndex) LineDivider()
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { p ->
        YukiConfirmDeleteDialog(
            title = "删除人设？",
            message = "「${p.displayName}」以及它的全部对话都会一并删除，无法恢复。",
            onConfirm = {
                vm.deletePersona(p.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }

    // 长按卡片 → 底部操作面板（用户 2026-09-28 要求）。
    // ⚠️ 长按以前直接是"删除"（一点就弹确认框）。现在它改成**菜单**：
    //    删除这种不可逆的动作不该是"长按即执行"的第一个结果。
    menuFor?.let { p ->
        PersonaActionSheet(
            persona = p,
            hasSessions = lastChatAt.containsKey(p.id),
            onDismiss = { menuFor = null },
            onStartChat = { menuFor = null; onStartChat(p.id) },
            onEdit = { menuFor = null; editing = p },
            onOpenMemory = { menuFor = null; onOpenMemory(p.id) },
            onClear = { menuFor = null; pendingClear = p },
            onTogglePin = { vm.togglePersonaPin(p.id); menuFor = null },
            onDelete = { menuFor = null; pendingDelete = p },
        )
    }

    // 清空对话记录也是**破坏性**的，所以同样要一次确认（复用删除人设那套组件与口径）
    pendingClear?.let { p ->
        YukiConfirmDeleteDialog(
            title = "清空和「${p.displayName}」的对话？",
            message = "聊过的内容会全部删除，Ta会忘掉这些。人设本身留着，可以重新开始。",
            onConfirm = {
                vm.clearPersonaSessions(p.id)
                pendingClear = null
            },
            onDismiss = { pendingClear = null },
        )
    }
}

/* ═══════════════════ 搜索与分组 ═══════════════════ */

/**
 * 人设搜索（**纯函数**，可在 JVM 单测里断言 —— 见 `PersonaFilterTest`）。
 *
 * ## 为什么搜的是这几栏
 * 用户找人的依据不止名字：他可能记得"那个说自己开咖啡店的"，
 * 或者"那个管我叫老板的"。所以名字、称呼、角色设定、性格都进检索。
 *
 * ## 为什么不做拼音 / 模糊匹配
 * 项目里没有拼音库，为搜索引一个不划算；中文人设名做子串匹配已经够用 ——
 * 用户记得的是「小夏」，不会记得「xiao xia」。
 */
internal fun filterPersonas(all: List<Persona>, query: String): List<Persona> {
    val q = query.trim()
    if (q.isEmpty()) return all
    return all.filter { p ->
        p.displayName.contains(q, ignoreCase = true) ||
            p.userNickname.contains(q, ignoreCase = true) ||
            p.customPrompt.contains(q, ignoreCase = true) ||
            p.personality.orEmpty().contains(q, ignoreCase = true)
    }
}

/** 列表分组：一个标题 + 它底下的人。 */
internal data class PersonaSection(val title: String, val items: List<Persona>)

/**
 * 人设列表的**排序与分组**（纯函数，可在 JVM 单测里断言 —— 见 `PersonaSectionTest`）。
 *
 * ## 分组
 * 「置顶」独立成一组、排最前（用户要求：人设的置顶与会话列表的置顶**互不影响**）；
 * 其余仍按"聊过 / 没聊过"分开 —— 与加这个功能之前的口径一致。
 *
 * ## 排序
 * [byRecentChat] 为 true：按"最后一次聊她的时间"倒序（没聊过的垫底）；
 * 为 false：按人设**创建时间**倒序。
 *
 * ⚠️ 排序在**分组之前**做：先整体排好再切进各组，组内自然有序 ——
 *    反过来做（先分组再各自排序）会让"置顶"那一组内部顺序变得不可预测。
 */
internal fun buildPersonaSections(
    all: List<Persona>,
    lastChatAt: Map<String, Long>,
    byRecentChat: Boolean,
): List<PersonaSection> {
    val sorted = if (byRecentChat) {
        all.sortedByDescending { lastChatAt[it.id] ?: 0L }
    } else {
        all.sortedByDescending { it.createdAt }
    }

    val (pinned, rest) = sorted.partition { it.isPinned }
    val (recent, others) = rest.partition { lastChatAt.containsKey(it.id) }

    return buildList {
        if (pinned.isNotEmpty()) add(PersonaSection("置顶", pinned))
        if (recent.isNotEmpty()) add(PersonaSection("最近聊过", recent))
        if (others.isNotEmpty()) {
            // 只剩一组时别再绕：直接叫「全部人设」（原来就是这么处理的）
            val title = if (pinned.isEmpty() && recent.isEmpty()) "全部人设" else "其他"
            add(PersonaSection(title, others))
        }
    }
}

/**
 * 排序切换（用户要求"允许按最后聊天时间或创建时间排序"）。
 *
 * ⚠️ 做成**一行小字切换**、而不是第二排 Tab：排序不是筛选 ——
 * 两种顺序下**人都在**，用 Tab 的样式会让人误以为"切过去就少了一批人"。
 */
@Composable
private fun SortToggle(byRecent: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.weight(1f))
        Text(
            text = "按" + (if (byRecent) "最近聊过" else "创建时间") + "排序",
            style = MaterialTheme.typography.labelSmall,
            color = SkyBlueDeep,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .clickable(onClick = onToggle)
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/**
 * 长按卡片弹出的**底部操作面板**（用户 2026-09-28 要求）。
 *
 * ## 为什么是底部面板，不是对话框
 * 这里要列的是"对这个人能做的几件事"，是给人**扫读**的清单：
 * 面板从屏幕下沿升起、贴着拇指最近的位置，一列读下来比读一个居中弹窗快。
 *
 * ## 为什么删除/清空还额外要一次确认
 * 面板本身只是"列出能做的事"；而这两个动作不可逆 ——
 * 列在同一个面板里不代表它们该有同样的阻力。
 *
 * ⚠️ 用 Material3 的 `ModalBottomSheet`（连接设置页已经在用它），不是 Android 原生弹窗 ——
 * 满足项目"所有弹层都是 Compose 自绘"的要求。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PersonaActionSheet(
    persona: Persona,
    /** 她有没有聊过的记录 —— 没有就不给「清空」这一项（点了也没东西可清） */
    hasSessions: Boolean,
    onDismiss: () -> Unit,
    onStartChat: () -> Unit,
    onEdit: () -> Unit,
    onOpenMemory: () -> Unit,
    onClear: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SnowSurface,
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            // 抬头：这是**谁**的面板。少了它，下面几项"对谁生效"要靠记忆
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                YukiAvatar(size = 36.dp, path = persona.avatarPath)
                Spacer(Modifier.width(12.dp))
                Text(
                    text = persona.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            HorizontalDivider(color = FrostLine)

            SheetAction("开始新对话", onStartChat)
            SheetAction("编辑人设", onEdit)
            // 没聊过 → 既没有记忆可看、也没有记录可清：两项都不出现
            //（列出来却点了没反应，比不列出来更让人困惑）
            if (hasSessions) {
                SheetAction("记忆库", onOpenMemory)
                SheetAction("清空对话记录", onClear, danger = true)
            }
            SheetAction(if (persona.isPinned) "取消置顶" else "置顶", onTogglePin)
            SheetAction("删除人设", onDelete, danger = true)
        }
    }
}

/** 操作面板里的一行。 */
@Composable
private fun SheetAction(label: String, onClick: () -> Unit, danger: Boolean = false) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodyLarge,
        color = if (danger) DangerRose else TextPrimary,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
    )
}

/** 分组标题。比卡片更轻 —— 它是**结构**，不是内容。 */
@Composable
private fun PersonaGroupHeader(title: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
    ) {
        // 一个小色条把"组"钉住：长列表滚动时纯文字标题会被糊掉，色条是稳定的锚点
        Box(
            Modifier
                .size(width = 3.dp, height = 12.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(BrandBlue),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = TextMuted,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * 内联搜索栏。
 *
 * 刻意**不套 `YukiTextField`**：那个组件自带"标签在上方"的形态，适合**表单字段**；
 * 搜索框是**工具条**，多出来的那一行标签在这里纯属浪费。
 * 输入内核仍是 `BasicTextField` —— 输入法、选区、光标的行为全部保留。
 */
@Composable
private fun PersonaSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
) {
    // 聚焦态：输入时描一圈主色边（原来只有一层浅底，"正在输入"看不出来）
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(FieldCorner))
            .background(FieldFill)
            .border(
                width = 1.dp,
                color = if (focused) BrandBlue else OutlineLight,
                shape = RoundedCornerShape(FieldCorner),
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            YukiIcons.Search,
            contentDescription = null,
            tint = TextMuted,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    "搜名字、称呼或角色设定",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMuted,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary),
                cursorBrush = SolidColor(SkyBlueDeep),
                modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
            )
        }
        if (query.isNotEmpty()) {
            IconButton(onClick = onClear, modifier = Modifier.size(22.dp)) {
                Icon(
                    YukiIcons.Close,
                    contentDescription = "清除搜索",
                    tint = TextMuted,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

/* ═══════════════════ 列表行 ═══════════════════ */

/**
 * 列表里的一行（v0.61.21 用户 2026-10-05 要求：组内"间隔合并、边界区分"）。
 *
 * ⚠️ 手势（点击 / 长按）从外层卡片挪到了**这一行**身上 ——
 *    组卡现在是一个**容器**（`YukiCard` 包住整组、内部用 `LineDivider` 分行），
 *    容器不接手势，否则整块都会响应最上面那一行。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PersonaRow(
    persona: Persona,
    /** 最后一次聊这个人的时间；null = 还没聊过 */
    lastChatAt: Long?,
    /** 她最后说的那句话（还没聊过时为空） */
    lastPreview: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // ⚠️ 长按不是"直接删除"，而是打开操作面板（用户 2026-09-28 要求）。
            //    删除不可逆，不该是"长按"的第一个结果；何况面板里还有另外五件事要做。
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 置顶：头像右上角一个小蓝点 —— 列表长了以后，没有它找不到"我钉住的是谁"
        Box {
            YukiAvatar(size = 46.dp, path = persona.avatarPath)
            if (persona.isPinned) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(12.dp)
                        .background(SkyBlueDeep, CircleShape),
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = persona.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!persona.isUsable) {
                    Spacer(Modifier.size(8.dp))
                    Text(
                        "信息不全",
                        style = MaterialTheme.typography.labelSmall,
                        color = WarnAmber,
                        modifier = Modifier
                            .background(WarnAmberBg, RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                // 时间靠右：一眼看出"多久没聊了"。
                // ⚠️ 复用 `TimeLabels.forList`（会话列表那一个）——粒度完全一样
                //（今天显示时刻 / 昨天 / 周几 / 日期），再写一个只会多一处边界要维护。
                lastChatAt?.let { at ->
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = TimeLabels.forList(at, System.currentTimeMillis()),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                    )
                }
            }

            Spacer(Modifier.height(3.dp))

            // 副标题**仍然只有一行**（上一版的教训：三行文字会把"这是一个人"的感觉压没）。
            // ⚠️ v0.58.0：**有备注时优先显示备注** —— 用户 2026-10-01 指出备注就是
            //    用来"分清谁是谁"的，而这里正是需要分清的地方；
            //    前面加「备注 ·」是为了让它一眼区别于"她说的最后一句"（否则用户会以为
            //    那也是 Ta 说的话）。没有备注时才退回原来的两条（最后一句 / 怎么称呼你）。
            Text(
                text = when {
                    persona.note.isNotBlank() -> "备注 · ${persona.note}"
                    lastPreview.isNotBlank() -> lastPreview
                    // ⚠️ v0.61.21：那一栏已从编辑页移除，新建人设常为空 ——
                    //    原来会显示成「还没聊过 · Ta称呼你「（未填）」」，像是在催他填一个
                    //    已经不存在的地方。空就只说"还没聊过"。
                    else -> persona.userNickname.trim().takeIf { it.isNotBlank() }
                        ?.let { "还没聊过 · Ta称呼你「$it」" }
                        ?: "还没聊过"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (persona.note.isNotBlank()) SkyBlueDeep else TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Icon(
            YukiIcons.ChevronRight,
            contentDescription = null,
            tint = TextMuted,
            modifier = Modifier.size(18.dp),
        )
    }
}

/* ═══════════════════ 编辑器 ═══════════════════ */


@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PersonaEditor(
    initial: Persona,
    isNew: Boolean,
    /** 老数据（[Persona.useGlobalPrefix] == null）时，界面显示的**实际生效值** */
    globalPrefixFallback: Boolean,
    /**
     * **要不要展示「通用设定」那一块**（v0.58.0）。
     *
     * ⚠️ 由服务端开关决定（`GET /api/app/features`）：后台关掉时整块不出现。
     *    默认 true = 与这个开关存在之前的行为一致（拉不到就照常显示）。
     */
    globalPrefixAvailable: Boolean = true,
    onSave: (Persona) -> Unit,
    onCancel: () -> Unit,
    /**
     * 「Ta 面前的我」选择器的数据源（v0.61.43）。
     * 默认空表 = 选择器里只有「不用」一项 —— 其它调用点不传也照常渲染。
     */
    userPersonas: List<UserPersona> = emptyList(),
    /** 「专属表情包」→ 那个人设的专属库（v0.57.0）。默认空实现 = 这一项不出现。 */
    onOpenEmoji: (String) -> Unit = {},
) {
    // ⚠️ 状态必须声明在**编辑器内部**（v0.45.8 踩过）：
    // 放到外层 `PersonaScreen` 上，这里会报 "Unresolved reference" —— 那是另一个 composable。
    var p by remember { mutableStateOf(initial) }

    // —— 「Ta 主动来找我」的通知权限（v0.61.40）——
    // 开关打开时尝试请求（Android 13+）；当前发不出通知时（权限被拒 / 系统里关掉），
    // 开关下方出现一行**如实**说明 —— 拿不到就说拿不到，不假装。
    val context = LocalContext.current
    var notificationsBlocked by remember { mutableStateOf(!ProactiveNotifier.canNotify(context)) }
    // 用户去系统设置里开完通知回来，提示行要自己消失 —— 回前台时重查一次
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationsBlocked = !ProactiveNotifier.canNotify(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // ⚠️ v0.61.50：这里原有「点开关时请求通知权限」的一条链（`askNotificationIfNeeded`
    //    + `RequestPermission` launcher）。P2 之后三项开关不再由用户点（云端模式固定开、
    //    本地模式不显示那组），那条链失去触发者 → 审查判为死代码，已删。
    //    权限引导现在只走 `NotificationWantedLine` 的「跳系统设置」。

    Scaffold(
        containerColor = SnowWhite,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SnowSurface,
                    titleContentColor = TextPrimary,
                ),
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(YukiIcons.Back, contentDescription = if (isNew) "放弃" else "取消")
                    }
                },
                title = { Text(if (isNew) "创建人设" else "编辑人设") },
                actions = {
                    TextButton(
                        onClick = { onSave(p) },
                        // ⚠️ v0.61.21：判据从 `isUsable` 换成 `canSave`。
                        //    原来三项（昵称/性别/角色设定）缺一，这颗按钮就是灰的 ——
                        //    用户填完了角色设定却存不下来，而按钮**不解释为什么**。
                        //    而且「Ta 怎么称呼你」即将被移除，届时 `isUsable` 恒为 false，
                        //    这颗按钮会**永久禁用**：谁都建不出人设。
                        // v0.61.48：新建时**必须先选「记忆方式」** —— 选后不可改，
                        // 所以不能替用户默认一个（默认错了他就得重建人设）。
                        enabled = p.canSave && (!isNew || p.memoryMode != null),
                    ) {
                        // 「创建」/「保存」用词不同是刻意的：两屏在按下这一下的语义上就该不一样
                        //（一个是"让 Ta 诞生"，一个是"改完存回去"）。以前两屏都写"保存"。
                        Text(if (isNew) "创建" else "保存")
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
                // 让出悬浮导航栏（见 [NavSpaceForContent]）—— 是"让出"而不是"截断"
                .padding(bottom = NavSpaceForContent)
                .padding(vertical = 8.dp),
            // 组间距 = 卡片间距（`CardGap`）。扁平风靠留白 + 阴影分层，
            // 组一挤，那 2dp 阴影就失去意义 —— 整页糊成一片，
            // 这正是「对话设置页很乱」当初的成因，人设编辑页是同一种病。
            verticalArrangement = Arrangement.spacedBy(CardGap),
        ) {
            // 两屏的差异从第一眼就开始：新建是"迎进来"（只交代需要几步），
            // 编辑是"认出谁"（在改哪个角色 + 一句缓存代价的提醒）。
            if (isNew) {
                NewPersonaIntro()
            } else {
                EditingPersonaHeader(name = p.displayName)
                CacheNotice()
            }

            // ⚠️ 2026-10-06 UI 重写：分组顺序改为「先 Ta 后我」——
            //    用户来这儿创建的是 **Ta**，所以头像/名字/设定在最前；
            //    「我（扮演）」是"你以什么身份跟 Ta 相处"，放 Ta 之后。
            SettingsGroup("Ta 是谁") {
                AvatarSection(
                    path = p.avatarPath,
                    personaId = p.id,
                    onChange = { p = p.copy(avatarPath = it) },
                )
                LineDivider()
                PField(
                    // ⚠️ 「角色名称」单独成一项（v0.61.10，用户要求）：
                    //    以前它**必须写在角色设定第一行**（下面那句提示语教的），
                    //    列表再从设定里正则抓「角色名称：X」——现在不必了。
                    //    它只是**展示名/备注**：真正"她是谁"仍由下面的设定决定。
                    label = "名字",
                    value = p.roleName,
                    hint = "列表和标题里显示它",
                    onChange = { p = p.copy(roleName = it) },
                )
                LineDivider()
                // 「性格」「开场白」是**进阶项**：新建时先不给 ——
                // 第一次建人设不该一次面对五个字段。添加页回答"怎么最快开始"，
                // 编辑页回答"怎么调细"，两屏的差异就落在这里。
                if (!isNew) {
                    PField(
                        label = "性格",
                        value = p.personality.orEmpty(),
                        hint = "留空也行，Ta 会更随性",
                        onChange = { p = p.copy(personality = it.ifBlank { null }) },
                    )
                    LineDivider()
                }
                PField(
                    label = if (isNew) "设定（必填）" else "设定",
                    value = p.customPrompt,
                    minLines = 10,
                    // 小白要的只有一句"这里写什么" —— **不再要求第一行写名字**（v0.61.10）
                    hint = "Ta 是谁、怎么说话，都写这儿",
                    onChange = { p = p.copy(customPrompt = it) },
                )
                if (!isNew) {
                    LineDivider()
                    // ⚠️ v0.58.0 补上。用户 2026-10-01 指出：「不是让你加给人设的备注吗，
                    //    而是**用户能分清**的你忘了？」——
                    //    这个字段在数据层与详情页早就有，但**编辑界面里一直没有输入框**，
                    //    于是它永远是空串：一个永远填不了的字段等于没有。
                    // ⚠️ 文案要说明"给谁看"：它是**写给自己的**（用来分清谁是谁），
                    //    不是设定的一部分 —— 不写清的话用户会把它当成"角色的备注"去写性格。
                    PField(
                        label = "备注（只有你看得到）",
                        value = p.note,
                        hint = "帮你分清谁是谁。Ta 看不到",
                        onChange = { p = p.copy(note = it) },
                    )
                }
            }

            if (!isNew) {
                SettingsGroup("开场白") {
                    PField(
                        label = "新对话时 Ta 说的第一句",
                        value = p.greeting.orEmpty(),
                        minLines = 3,
                        hint = "留空就让 Ta 自己开口",
                        onChange = { p = p.copy(greeting = it.ifBlank { null }) },
                    )
                    GreetingTokenRow(
                        onInsert = { token ->
                            p = p.copy(greeting = insertGreetingToken(p.greeting.orEmpty(), token))
                        },
                    )
                }
            }

            // 「我（扮演）」：你以什么身份跟 Ta 相处。
            // ⚠️ 2026-10-06：原「关于你」组并入这里 —— 昵称/性别两个输入框都已移除
            //    （用户要求"让用户直接在他的设定里写"），这一组只剩"挑一个我"。
            SettingsGroup("我（扮演）") {
                // 「Ta 面前的我」（v0.61.43）：给这个角色挑一个用户人设。
                // 副文案如实带缓存代价 —— 换 / 解绑都会让该角色的前缀从改动处断开。
                var pickUserPersona by remember { mutableStateOf(false) }
                val boundUserPersona = userPersonas.firstOrNull { it.id == p.userPersonaId }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { pickUserPersona = true }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Ta 面前的我",
                            style = MaterialTheme.typography.bodyLarge,
                            color = TextPrimary,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            boundUserPersona?.let {
                                "现在：${it.name.ifBlank { "未命名" }}"
                            } ?: "不填就是现在的你",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "选择",
                        style = MaterialTheme.typography.labelMedium,
                        color = SkyBlueDeep,
                    )
                }
                if (pickUserPersona) {
                    UserPersonaPickerDialog(
                        all = userPersonas,
                        current = p.userPersonaId,
                        onPick = { id ->
                            p = p.copy(userPersonaId = id)
                            pickUserPersona = false
                        },
                        onDismiss = { pickUserPersona = false },
                    )
                }
            }

            // 表情包、通用设定都是**进阶项**：新建时整块不给 ——
            // 第一次建人设不该一次面对七个字段。
            if (!isNew) {
                SettingsGroup("表情包") {
                    // ── 她的专属表情包（v0.57.0：改成**独立的专属库界面**）──
                    // 用户 2026-10-01 要求「人设专属 / 全局两个入口与界面」：
                    // 这一行是**专属库的入口** —— 点进去就是只属于 Ta 的那批图。
                    // ⚠️ 以前这里是一块"展开式说明"，让用户自己去「设置 → 表情包管理」切 Tab；
                    //    那是把入口藏在别处（用户得先知道"有那个地方"才找得到）。现在入口就在这里。
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenEmoji(p.id) }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("专属表情包", style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "只给 Ta 用的图 —— 没加的话，Ta 会用全局那一份",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Icon(
                            YukiIcons.ChevronRight,
                            contentDescription = null,
                            tint = TextMuted,
                            modifier = Modifier.size(18.dp),
                        )
                    }

                    LineDivider()

                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "表情包发送概率",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = TextPrimary,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    // 把"现在到底用的是哪个值"写在明面上：
                                    // 用户改完很容易忘了自己设过 —— 而那会表现为"怎么不按我设的发"
                                    if (p.emojiChanceOverride == null) "当前：跟随全局默认" else "当前：只对Ta生效",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextMuted,
                                )
                            }
                            Text(
                                text = if (p.emojiChanceOverride == null) "自定义" else "恢复默认",
                                style = MaterialTheme.typography.labelLarge,
                                color = SkyBlueDeep,
                                modifier = Modifier
                                    .clickable {
                                        // 从"跟随全局"切到"自定义"时给一个起点值，而不是 0 ——
                                        // 0 是"永远不发图"，那与"我还没调"是两件事，会让人以为坏了
                                        p = p.copy(
                                            emojiChanceOverride =
                                                if (p.emojiChanceOverride == null) 0.3f else null,
                                        )
                                    }
                                    .padding(8.dp),
                            )
                        }
                        p.emojiChanceOverride?.let { ov ->
                            Slider(
                                value = ov,
                                onValueChange = { v -> p = p.copy(emojiChanceOverride = v) },
                                valueRange = 0f..1f,
                            )
                            Text(
                                text = "${(ov * 100).roundToInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted,
                            )
                        }
                    }
                }

            }

            // 「记忆方式」（v0.61.48，用户 2026-10-06 拍板）：
            //   · **创建时二选一、选后不可改**（编辑页只读展示）；
            //   · 云端三项功能（接入 Ta 的状态 / 记忆上云 / Ta 主动来找我）**都依赖云端**，
            //     所以**一个选择决定三项** —— 云端 = 三项固定开；本地 = 三项全关。
            SettingsGroup("记忆方式") {
                // v0.61.50 修（审查发现的自洽性断裂）：判据必须是 `== LOCAL` 而不是 `!isCloudMemory` ——
                // 后者在 `memoryMode == null`（还没选）时也为真，于是"本地"被画成**已选中**，
                // 而同屏的创建按钮却禁用并提示"先选一个"。两处对"选了没"给出相反结论。
                if (isNew || p.memoryMode == null) {
                    MemoryModeRow(
                        title = "本地记忆",
                        desc = "只存在这台手机上。",
                        selected = p.memoryMode == Persona.MEMORY_MODE_LOCAL,
                        onClick = { p = p.copy(memoryMode = Persona.MEMORY_MODE_LOCAL) },
                    )
                    LineDivider()
                    MemoryModeRow(
                        title = "云端记忆",
                        desc = "记忆上云，可用 Ta 的状态与主动消息。",
                        selected = p.isCloudMemory,
                        onClick = { p = p.copy(memoryMode = Persona.MEMORY_MODE_CLOUD) },
                    )
                    if (p.memoryMode == null) {
                        Text(
                            if (isNew) "选一个，之后不能改"
                            else "还没定过，现在选一次（不能改）",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }
                } else {
                    Text(
                        if (p.isCloudMemory) "云端记忆" else "本地记忆",
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextPrimary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                    Text(
                        "创建时定下的，不能改",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                        modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp),
                    )
                }
            }

            // 「Ta 的状态」与「Ta 主动来找我」：**各自独立开关**（用户 2026-10-06 拍板）。
            // ⚠️ 沿革：
            //   · v0.61.48 曾把三项（状态 / 记忆上云 / 主动）绑死在「记忆方式」上、不可单独关；
            //   · 2026-10-06 用户改口：「记忆上云」在选云端时就等于默认开启，**不用单独做按钮**；
            //     而「Ta 的状态」「Ta 主动来找我」要**单独做开关**。
            //   所以这里只剩两项，且都可单独切换。本地记忆下两项不适用（原理上就没云端可接）。
            SettingsGroup("Ta 的状态") {
                if (p.isCloudMemory) {
                    SwitchLine(
                        title = "接入 Ta 的状态",
                        desc = "看到 Ta 此刻的心情。",
                        checked = p.xinchaoEnabled,
                        onCheckedChange = { p = p.copy(xinchaoEnabled = it) },
                    )
                    LineDivider()
                    SwitchLine(
                        title = "Ta 主动来找我",
                        desc = "Ta 想你了会给你发消息。",
                        checked = p.proactiveEnabled,
                        onCheckedChange = { p = p.copy(proactiveEnabled = it) },
                    )
                    if (p.proactiveEnabled && notificationsBlocked) {
                        NotificationWantedLine(onOpenSettings = { openNotificationSettings(context) })
                    }
                } else {
                    Text(
                        if (p.memoryMode == null) "选「云端记忆」才有这两项"
                        else "本地记忆不含云端功能",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }

            // ⚠️ 通用设定**新建时也要有**（用户 2026-10-01 明确要求：「通用设定是在添加
            //    创建人设的时候也有开关的」）—— 它决定"要不要把那套共同规则也发给她"，
            //    是建人设时就会做的决定；藏进编辑页等于新建的人根本不知道有这回事。
            // ⚠️ 但它归**服务端开关**管：后台关掉时整块不出现（用户要求）。
            //    这里只决定"显示与否" —— 已经开了通用设定的老人设照旧生效，不远程改用户的设定。
            if (globalPrefixAvailable) {
                SettingsGroup("通用设定") {
                    SwitchLine(
                        title = "使用通用设定",
                        desc = "把「高级设定 → 通用设定」里那套共同规则也发给这个角色。默认关。",
                        // 老数据是 null（没设过）→ 显示它**实际**是否生效，而不是一律显示"关"
                        checked = p.useGlobalPrefix ?: globalPrefixFallback,
                        // 用户一动就写显式值（从此这个人设不再吃全局默认）
                        onCheckedChange = { p = p.copy(useGlobalPrefix = it) },
                    )
                    LineDivider()
                    Text(
                        // 原来这条是三行、还带着"通用设定适合多个角色共享同一套约束"那类
                        // 给维护者看的取舍说明。用户只需知道一件事：开或关都有一次缓存代价。
                        text = "开或关，都会让 Ta 的已有对话缓存失效一次。",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }

            if (isNew) {
                // 新建屏收尾：把"以后还能加什么"说清楚，用户就不会在这一页恋战。
                Text(
                    "性格、开场白、专属表情包、通用设定 —— 建好之后在详情页点「编辑人设」随时加。",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

/* ═══════════════════ 顶部两段（v0.61.43） ═══════════════════ */

/**
 * 「Ta 们 ｜ 我（扮演）」分段控 —— 与记忆页的本机/云端两个 pill 同一套样式，
 * 保证全 App 的"两段切换"长得一模一样。
 */
@Composable
private fun PersonaSegmentRow(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SegmentPill("Ta 们", selected == 0, Modifier.weight(1f)) { onSelect(0) }
        SegmentPill("我（扮演）", selected == 1, Modifier.weight(1f)) { onSelect(1) }
    }
}

@Composable
private fun SegmentPill(label: String, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(if (active) SkyBlueDeep else FieldFill)
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (active) SnowWhite else TextPrimary,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/* ─────────────── 新建 / 编辑 两屏的抬头 ─────────────── */

/**
 * 新建人设的**欢迎头**（渐变卡）。
 *
 * 它替掉了原来一句干巴巴的小字 —— 目的不变（先交代"要填多少"，免得用户怕
 * "这一页要填到什么时候"），但做成一张卡之后，新建屏从第一眼起就和编辑屏不是同一样东西。
 *
 * ⚠️ 渲染未经真机验证（本机无设备）。
 */
@Composable
private fun NewPersonaIntro() {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Brush.linearGradient(listOf(BrandBlue, BrandBlueDeep))),
        ) {
            // 两团半透明圆造空间感 —— 与「我的」页 / 人设详情页同一个手法（图片要进包、要适配深浅色）
            Canvas(Modifier.matchParentSize()) {
                drawCircle(
                    color = Color.White.copy(alpha = 0.16f),
                    radius = size.minDimension * 0.38f,
                    center = Offset(size.width * 0.86f, size.height * 0.12f),
                )
                drawCircle(
                    color = Color.Black.copy(alpha = 0.12f),
                    radius = size.minDimension * 0.46f,
                    center = Offset(size.width * 0.08f, size.height * 1.04f),
                )
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 18.dp)) {
                Text(
                    "创建一个人设",
                    style = MaterialTheme.typography.titleMedium,
                    color = SnowWhite,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "填好称呼、性别、角色设定，就能开始聊 —— 其他以后随时改。",
                    style = MaterialTheme.typography.labelSmall,
                    color = SnowWhite.copy(alpha = 0.88f),
                )
            }
        }
    }
}

/**
 * 编辑人设的**身份头**：一眼知道"我在改谁"。
 *
 * 编辑页可能是从列表点进来的，也可能是从详情页点进来的 —— 顶上一直挂着名字，
 * 就不用回头确认"刚才点的是哪一个"。
 */
@Composable
private fun EditingPersonaHeader(name: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("正在编辑", style = MaterialTheme.typography.labelSmall, color = TextMuted)
        Spacer(Modifier.width(8.dp))
        Text(
            "「$name」",
            style = MaterialTheme.typography.titleSmall,
            color = TextPrimary,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 编辑屏的缓存提醒。
 *
 * ⚠️ 原来这条写的是「修改人设文字会让全部已有对话的缓存前缀失效（下一轮费用暂时上升）」——
 *    那是**给维护者**的理由，不是给用户的提示。用户要做的事只有一件：改完开一段新对话。
 *    （机制写在 `Persona` 的类注释里：除 avatarPath 外所有字段都进 Frozen Prefix。）
 */
@Composable
private fun CacheNotice() {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        color = WarnAmberBg,
        shape = RoundedCornerShape(12.dp),
    ) {
        Text(
            "改完开一段新对话，效果最好。",
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = WarnAmber,
        )
    }
}

/* ─────────────── 开场白变量 ─────────────── */

/** 开场白里能用的一个变量。[token] 是写进文本的字面量，由 `PromptEngine` 替换。 */
internal data class GreetingToken(val token: String, val label: String, val means: String)

/**
 * 开场白变量清单。
 *
 * ⚠️ `token` 必须与 `PromptEngine` 里那三行 `.replace(...)` 逐字一致
 *（`{user_nickname}` / `{user_gender}` / `{persona_name}`）。改这里不改那里，
 *  用户点了按钮也换不出东西 —— 而那种坏法是**静默**的（开场白里原样显示占位符，不报错）。
 */
internal val GREETING_TOKENS = listOf(
    GreetingToken("{user_nickname}", "称呼", "Ta对你的称呼"),
    GreetingToken("{user_gender}", "性别", "你的性别"),
    GreetingToken("{persona_name}", "角色名", "角色的名字"),
)

/**
 * 把变量**追加**到开场白末尾（纯函数，可在 JVM 单测里断言 —— 见 `GreetingTokenTest`）。
 *
 * ## 为什么是"追加"而不是"插到光标处"
 * `YukiTextField` 用 `BasicTextField`，没有向外暴露选区 —— 要拿光标位置得改这个共享组件
 *（记忆管理页也在用它）。开场白通常就一两句，追加到末尾、再自己挪一下，
 * 代价远低于为这一处去动共享输入框。
 *
 * ⚠️ 已经含有该变量的**不重复追加**：连点两下同一个 chip 不该得到两个一样的占位符。
 */
internal fun insertGreetingToken(greeting: String, token: String): String =
    if (greeting.contains(token)) greeting else greeting + token

/**
 * 开场白变量快捷条。
 *
 * ## 为什么要有它
 * 用户要求"开场白参数可快捷添加并解释"。以前只有一行 hint 把三个变量名摊在那里让用户**手打**
 *（还得连花括号一起打对）—— 打错一个字符就换不出来，而且**不会有任何报错**，
 * 只是开场白里原样显示给用户看。点一下就能插，这条路才走得通。
 */
@Composable
private fun GreetingTokenRow(onInsert: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GREETING_TOKENS.forEach { t ->
                GreetingTokenChip(label = t.label) { onInsert(t.token) }
            }
        }
        Spacer(Modifier.height(8.dp))
        // 光给按钮不解释"换了什么"，用户不敢点 —— 这行把每个变量是什么意思说清楚
        Text(
            text = GREETING_TOKENS.joinToString("，") { "${it.token} 是${it.means}" },
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
        )
    }
}

/** 快捷条里的一个变量 chip。 */
@Composable
private fun GreetingTokenChip(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(IceCyanSoft)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            YukiIcons.Add,
            contentDescription = null,
            tint = SkyBlueDeep,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = SkyBlueDeep)
    }
}

/**
 * 卡片内的一条输入行。
 *
 * ## 为什么从 `OutlinedTextField` 换成 YukiTextField
 * 文档 §28.1 / §47.3 要求「所有输入框、对话框、选择器均为 Compose 自定义」。
 * Material 的 `OutlinedTextField` 是「标准 Android 应用」的脸 —— 方框轮廓 + 悬浮标签。
 * 而**人设编辑页是全项目输入框最密集的一页**（5 个字段），它继续用 Material 输入框，
 * 等于这套视觉在这里整页破功。`YukiTextField` 是本项目对「字段」这件事的唯一实现
 * （文档 §37.2 的人设编辑页示例用的就是它），换过去之后，
 * 人设页与记忆页的字段长得是同一个东西。
 *
 * ## 为什么 hint 留在这里、不塞进 `YukiTextField`
 * `YukiTextField` 是共享组件（记忆管理页也在用），改它等于改所有消费方。
 * 而这几条 hint 讲的是「这个字段在缓存上的代价」（例如开场白会写进历史），
 * 属于**页面语境**而不是控件能力 —— 放在控件里反而会让下一个使用者困惑。
 */
@Composable
private fun PField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    hint: String? = null,
    minLines: Int = 1,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        YukiTextField(
            value = value,
            onValueChange = onChange,
            label = label,
            minLines = minLines,
            modifier = Modifier.fillMaxWidth(),
        )
        if (hint != null) {
            Spacer(Modifier.height(6.dp))
            Text(hint, style = MaterialTheme.typography.labelSmall, color = TextMuted)
        }
    }
}

/*
 * ⚠️ 这里原来有一个「管理记忆」入口行（MemoryEntryRow）。
 * 2026-09-28 按用户要求**移走**：记忆的归属从「人设」改成了「对话」——
 * 同一人设新开一段对话**不共享**记忆。既然记忆属于会话，入口就该在
 * **对话设置页**（ChatSettingsScreen 的「记忆」分组），而不是人设编辑页：
 * 放在人设页会让用户以为它是跨会话的。
 */

/* ═══════════════════ 新建对话时选人设 ═══════════════════ */

// ⚠️ 弹窗的搜索逻辑在 `PersonaPickerFilter.kt`（纯函数 + 有单测）。
//    它原本写在下面这个 Composable 里 —— 而那等于永远没人验过
//   （本机没有设备、也没有 Compose 测试基建）。

@Composable
fun PersonaPickerDialog(
    personas: List<Persona>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    onCreatePersona: () -> Unit,
) {
    YukiDialog(
        title = "选择人设",
        onConfirm = onCreatePersona,
        onDismiss = onDismiss,
        confirmText = "去创建",
        dismissText = "取消",
    ) {
        // ── 以下为对话框内容 ──
            if (personas.isEmpty()) {
                Text("还没有人设。先创建一个 —— 是谁，由你决定。")
            } else {
                // ⚠️ v0.61.21：人设一多，原来那个「不滚动的 Column」会把对话框按钮挤出屏幕。
                //    用户要求「超过 5 个就支持搜索和滑动」—— 两条一起加。
                var keyword by remember { mutableStateOf("") }
                val shown = remember(personas, keyword) { filterPersonasForPicker(personas, keyword) }

                Column(
                    // ⚠️ **只在装不下时才限高滚动**：人设少的时候让对话框自然收缩，
                    //    否则平白多出一块空白，反而显得没内容。
                    modifier = if (personas.size > 5) {
                        Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())
                    } else {
                        Modifier
                    },
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (personas.size > 5) {
                        OutlinedTextField(
                            value = keyword,
                            onValueChange = { keyword = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp),
                            placeholder = { Text("搜名字或设定") },
                        )
                        Spacer(Modifier.height(2.dp))
                    }
                    // ⚠️ v0.61.21：**不再过滤「信息不全」的人设**。
                    //
                    // 原来这里是 `personas.filter { it.isUsable }`，而 `isUsable` 要求
                    // `userNickname` / `userGender` / `customPrompt` **三项全非空** ——
                    // 缺任何一项，那一行就**根本不可点**，点下去毫无反应、也不报错。
                    // 用户报的「弹窗里点人设不会创建会话」就是它（不是 newSession 的问题：
                    // 只要 id 对得上，newSession 照常建会话）。
                    //
                    // 更要命的是它挡着下一步：**「Ta 怎么称呼你」这一栏即将去掉**
                    //（那样 userNickname 会常年为空），届时 `isUsable` 对**新建的人设**
                    // 一律为 false —— 谁点都没反应。
                    //
                    // 判据本来就该松：这个弹窗的职责是"选一个人开始聊"，
                    // 而不是"审核人设填完没有"。信息不全最多是开场白薄一点，不该拦人。
                    if (shown.isEmpty()) {
                        Text(
                            "没有匹配「$keyword」的人设",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                        )
                    }
                    shown.forEach { p ->
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable { onPick(p.id) },
                            shape = RoundedCornerShape(10.dp),
                            color = IceCyanSoft,
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // ⚠️ v0.61.21：**展示人设头像**（用户原话「弹窗里的内容也美化布局一下
                                //    要展示人设头像」）。光有名字时，几个名字一接近就得逐个点开
                                //    才知道谁是谁；头像一句话都不用说。
                                YukiAvatar(size = 36.dp, path = p.avatarPath)
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(
                                        p.displayName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = TextPrimary,
                                    )
                                    Text(
                                        // 「Ta 怎么称呼你」正在被移除，所以副标题不再依赖它：
                                        // 填过就照旧显示，没填就不显示（而不是显示"称呼你为 " 这种半截话）
                                        p.userNickname.takeIf { it.isNotBlank() }
                                            ?.let { "称呼你为 $it" }
                                            ?: "点一下就开始聊",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextMuted,
                                    )
                                }
                            }
                        }
                    }
                    val unfinished = personas.count { !it.isUsable }
                    if (unfinished > 0) {
                        Text(
                            // 从"补齐后才能使用"改成提示 —— 因为现在已经能用了
                            "有 $unfinished 个人设还没填详细设定。照样可以开始聊，之后在「人设」里补也行。",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                        )
                    }
                }
            }
    }
}

private fun blankPersona() = Persona(
    id = UUID.randomUUID().toString().take(8),
    // ⚠️ 新建的人设**显式**写 false（默认关）—— 这是"新用户默认不用通用设定"的保证。
    //    老数据是 null（不写这一栏），走「沿用全局开关」的兼容路径。见 Persona.useGlobalPrefix。
    useGlobalPrefix = false,
    createdAt = System.currentTimeMillis(),
    updatedAt = System.currentTimeMillis(),
)

/* ═══════════════════ 人设头像 ═══════════════════ */

/**
 * 人设头像（可选）。
 *
 * ## 为什么它值得占一块地方
 * 她是这段关系的"那张脸"。默认的雪晶虽然属于品牌，但每个角色都长一样 ——
 * 用户自定义的头像一设上，会话列表、每条消息旁边、搜索结果里全都会跟着变，
 * 这是**一处改动、全站受益**最明显的地方。
 *
 * ## 与缓存的关系（这里有一条反直觉的事）
 * 改人设的**文字**会让该人设所有会话的缓存前缀从第 0 字节失效；
 * 但改**头像不会** —— `PromptEngine` 只读昵称/性别/性格/自由设定，
 * `avatarPath` 根本不进冻结前缀。所以这里可以放心地"随时换"，
 * 界面上也这么告诉用户（见下面的说明文案）。
 *
 * ⚠️ 渲染未经真机验证（本机无设备）。
 */
@Composable
private fun AvatarSection(
    path: String?,
    personaId: String,
    onChange: (String?) -> Unit,
) {
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // 本区块只产出**卡片内容** —— 标题与卡片由调用处的 `SettingsGroup("人设头像")` 提供。
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            YukiAvatar(size = 64.dp, path = path)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "角色长什么样",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    // 这句话是给用户的定心丸：头像不动缓存，随便换
                    "头像不进请求、不影响缓存，随时可以换。",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                )
            }
        }

        ImageSourceButtons(
            dir = ImageStore.AVATAR_DIR,
            namePrefix = "persona_$personaId",
            maxSide = 512,
            onPicked = { newPath ->
                // 换图即删旧图，否则私有目录里会攒下一堆没人引用的文件
                ImageStore.delete(path)
                onChange(newPath)
            },
            onError = { error = it },
            onBusyChange = { busy = it },
            galleryDesc = "从相册挑一张",
        )

        // 忙 / 错提示挪进了卡片里。上一版它在卡片**外**，于是"点了没反应"的反馈
        // 出现在与操作无关的位置；现在它贴在触发它的那两个按钮正下方。
        if (busy || error != null) {
            LineDivider()
            Text(
                text = error ?: "正在处理图片…",
                style = MaterialTheme.typography.labelSmall,
                color = if (error != null) DangerRose else TextMuted,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }

        if (path != null) {
            LineDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        ImageStore.delete(path)
                        onChange(null)
                    }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Text(
                    "移除头像",
                    style = MaterialTheme.typography.bodyLarge,
                    color = DangerRose,
                )
            }
        }
    }
}

/* ─────────────── 「Ta 主动来找我」的通知权限（v0.61.40） ─────────────── */

/**
 * 「通知没开」的如实说明 —— 只在**开关开着但通知发不出去**时出现在开关下方。
 *
 * 拿不到权限就明说收不到（用户要求），不给"看起来能收到"的假象；
 * 点这行 → 系统里的应用通知设置页。
 */
@Composable
private fun NotificationWantedLine(onOpenSettings: () -> Unit) {
    Text(
        text = "通知没开 —— Ta 来找你时收不到提醒。点这里去系统设置里允许。",
        style = MaterialTheme.typography.labelSmall,
        color = WarnAmber,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenSettings)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

/** 打开本应用在系统里的通知设置页。个别机型没有这个页面 —— 打不开就算了。 */
private fun openNotificationSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/**
 * 「记忆方式」的单选行（本地 / 云端 二选一；v0.61.48）。
 *
 * ⚠️ 只在**创建**时可选；编辑页是只读文案 —— 用户 2026-10-06 原话「选择之后不能修改」。
 * 用 `RadioButton` 而不是 `Switch`：开关表达"两件独立的事各开各的"，
 * 单选表达"互斥的一个选择" —— 这里正是后者，别混用。
 */
@Composable
private fun MemoryModeRow(
    title: String,
    desc: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
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
        RadioButton(selected = selected, onClick = onClick)
    }
}
