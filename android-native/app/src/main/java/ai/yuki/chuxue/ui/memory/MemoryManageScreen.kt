package ai.yuki.chuxue.ui.memory

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ai.yuki.chuxue.data.MemorySearch
import ai.yuki.chuxue.data.XinchaoBucket
import ai.yuki.chuxue.data.memory.MemoryFilter
import ai.yuki.chuxue.data.memory.MemoryListQuery
import ai.yuki.chuxue.data.room.MemoryEntity
import ai.yuki.chuxue.ui.TimeLabels
import ai.yuki.chuxue.ui.components.Island
import ai.yuki.chuxue.ui.components.YukiCard
import ai.yuki.chuxue.ui.components.YukiConfirmDeleteDialog
import ai.yuki.chuxue.ui.components.YukiDialog
import ai.yuki.chuxue.ui.components.YukiTextField
import ai.yuki.chuxue.ui.icon.YukiIcons
import ai.yuki.chuxue.ui.theme.CardCorner
import ai.yuki.chuxue.ui.theme.CardGap
import ai.yuki.chuxue.ui.theme.DangerRose
import ai.yuki.chuxue.ui.theme.FieldCorner
import ai.yuki.chuxue.ui.theme.FieldFill
import ai.yuki.chuxue.ui.theme.FrostLine
import ai.yuki.chuxue.ui.theme.IceCyanSoft
import ai.yuki.chuxue.ui.theme.SkyBlueDeep
import ai.yuki.chuxue.ui.theme.SnowSurface
import ai.yuki.chuxue.ui.theme.SnowSurfaceDim
import ai.yuki.chuxue.ui.theme.SnowWhite
import ai.yuki.chuxue.ui.theme.TextMuted
import ai.yuki.chuxue.ui.theme.TextPrimary
import ai.yuki.chuxue.ui.theme.WarnAmber
import ai.yuki.chuxue.ui.theme.WarnAmberBg

/**
 * 记忆管理界面（开发文档 §42）。
 *
 * ## 这一页存在的理由
 * 记忆库的引擎（§7/§8）本身不产生记忆 —— 它只负责"存好、找对、按规矩注入"。
 * 而文档 §8.3 的自动提取需要额外调 LLM（花用户的 API 额度），且质量只能在真机上评估。
 * 所以这一版先把**手动入口**做出来：你能亲口告诉她一件事，她从此记得。
 * 这条路零 API 成本，且让整条链路（写入 → 检索 → 注入附录 → 她提起）可以被完整验证一次。
 *
 * ## 界面上的三个诚实说明
 * 1. **手动新增的默认是「角色记忆」**：跨对话共享（2026-10-05 用户拍板改回）。
 *    会话级的剧情记忆另有来路 —— AI 从对话里按主语分流提取（"我们/你和我"的事），
 *    以及「迁移」把存量会话级记忆升上来；这一页的「＋」不产生会话级。
 * 2. **她不会立刻"说出口"**：记忆进的是**附录**，只在话题相关时才被检索到并注入 ——
 *    这正是它不吃缓存、又不让她"串味"的原因。
 * 3. **迁移是用户可点的**：只在还有会话级旧记忆时出现，升级完成后自动消失。
 *
 * ⚠️ 本页的视觉**未经真机验证**（本机无模拟器、无设备）。形状与对比度按 §29 的
 * 蓝白清冷水彩标定，但渲染结果需要你看真机 —— 尤其是重要性滑块的可见性。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryManageScreen(
    personaId: String,
    /** 可选：带上就看"本会话的会话级条目"；空串=只看人设级（从人设/设置进来的路径）。 */
    sessionId: String = "",
    /** 该人设是否已接入云端（`Persona.xinchaoEnabled`）—— 决定「云端记忆」页签能不能用。 */
    cloudEnabled: Boolean = false,
    onBack: () -> Unit,
    vm: MemoryViewModel = viewModel(),
) {
    LaunchedEffect(personaId, sessionId, cloudEnabled) { vm.bind(personaId, sessionId, cloudEnabled) }

    // v0.61.49 收尾：**只显示一份**（用户 2026-10-06 说的"串记忆" = 同一条记忆在
    // 「本机记忆」和「云端记忆」两个页签各显示一份、形态还不同）。
    // 云端记忆 → 只看云端（本机那份降级成**不显示**的加速快照）；
    // 本地记忆 → 只看本机（没有云端可言）。
    var tab by remember(personaId, cloudEnabled) {
        mutableStateOf(if (cloudEnabled) MEMORY_TAB_CLOUD else MEMORY_TAB_LOCAL)
    }

    // 云端模式进来时拉一次（原来只在"点页签"时拉；页签没了，改在这里）
    LaunchedEffect(personaId, cloudEnabled) {
        if (cloudEnabled) vm.loadCloud()
    }

    val all by vm.memories.collectAsStateWithLifecycle()
    val legacySessionCount by vm.legacySessionCount.collectAsStateWithLifecycle()
    val migrateMessage by vm.migrateMessage.collectAsStateWithLifecycle()
    val migrateError by vm.migrateError.collectAsStateWithLifecycle()

    // 迁移结果走全局提示（灵动岛）—— 成功 / 失败分色，与其它页面的一次性提示同一条通道。
    // ⚠️ 用完即清：StateFlow 的值不清的话，下次回到本页会重新弹一遍（同一条消息只该播一次）。
    LaunchedEffect(migrateMessage) {
        migrateMessage?.let {
            Island.ok(it)
            vm.clearMigrateMessage()
        }
    }
    LaunchedEffect(migrateError) {
        migrateError?.let {
            Island.error(it)
            vm.clearMigrateError()
        }
    }
    // 「同步到云端」的结果同样走灵动岛（用完即清）。
    val syncMessage by vm.syncMessage.collectAsStateWithLifecycle()
    LaunchedEffect(syncMessage) {
        syncMessage?.let {
            Island.ok(it)
            vm.clearSyncMessage()
        }
    }
    // 云端删除的结果（v0.61.39）：成功/失败分色。删除是显式动作 —— 失败必须看得见。
    val deleteMessage by vm.deleteMessage.collectAsStateWithLifecycle()
    LaunchedEffect(deleteMessage) {
        deleteMessage?.let {
            Island.ok(it)
            vm.clearDeleteMessage()
        }
    }
    val deleteError by vm.deleteError.collectAsStateWithLifecycle()
    LaunchedEffect(deleteError) {
        deleteError?.let {
            Island.error(it)
            vm.clearDeleteError()
        }
    }

    var filter by remember { mutableStateOf(MemoryFilter.ALL) }
    var pendingDelete by remember { mutableStateOf<MemoryEntity?>(null) }
    /** 搜索关键词（v0.49.0）。空 = 不过滤 —— 与 `MessageSearch` 的语义相反，见 `MemorySearch` */
    var keyword by remember { mutableStateOf("") }

    // 云端记忆（v0.61.39）：待确认删除的桶（null=没开） / 正在看全文的桶（null=没开）
    var pendingCloudDelete by remember { mutableStateOf<XinchaoBucket?>(null) }
    var fullBucket by remember { mutableStateOf<XinchaoBucket?>(null) }

    // 对话框状态：editing != null 即打开；target == null 表示新增
    var editing by remember { mutableStateOf<MemoryDraft?>(null) }
    var editingTarget by remember { mutableStateOf<MemoryEntity?>(null) }

    // ⚠️ 两步派生，顺序不能合并：先按档位过滤，再按关键词搜。
    //    各自 remember 各自的 key —— 改关键词不该让"按档位过滤"重算，
    //    记忆条数多起来时那是白费的。
    val filtered = remember(all, filter) { MemoryListQuery.apply(all, filter) }
    val shown = remember(filtered, keyword) { MemorySearch.query(filtered, keyword) }

    fun openEditor(target: MemoryEntity?) {
        editingTarget = target
        editing = target?.let { MemoryDraft.from(it) } ?: MemoryDraft()
    }

    fun closeEditor() {
        editing = null
        editingTarget = null
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
                    IconButton(onClick = onBack) {
                        Icon(YukiIcons.Back, contentDescription = "返回")
                    }
                },
                title = { Text("记忆", style = MaterialTheme.typography.titleMedium) },
                actions = {
                    IconButton(onClick = { openEditor(null) }) {
                        Icon(YukiIcons.Add, contentDescription = "添加记忆", tint = SkyBlueDeep)
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // 页签行已移除（v0.61.49）：两种记忆现在**二选一**，不再"两个都摆着"。
            if (tab == MEMORY_TAB_CLOUD) {
                CloudMemoryPane(
                    vm = vm,
                    onOpen = { fullBucket = it },
                    onDelete = { pendingCloudDelete = it },
                )
                return@Column
            }
            MemoryExplainer()

            // 旧记忆迁移入口 —— 显示判据用**跨全部会话**的会话级计数，不是当前列表：
            // 列表只含本会话的会话级条目，从一段新对话进来时看不到旧的，入口会跟着
            // 消失 —— 而那正是最需要迁移入口的时刻。计数归零后这条自己消失。
            if (legacySessionCount > 0) {
                MigrationNotice(
                    count = legacySessionCount,
                    onMigrate = { vm.migrateLegacyMemories() },
                )
            }

            FilterRow(
                current = filter,
                counts = MemoryFilter.entries.associateWith { f ->
                    all.count { f.accepts(it) }
                },
                onSelect = { filter = it },
            )

            MemorySearchBar(
                value = keyword,
                onChange = { keyword = it },
            )

            if (shown.isEmpty()) {
                EmptyMemoryHint(
                    filter = filter,
                    hasAnyAtAll = all.isNotEmpty(),
                    // ⚠️ 必须区分"没有记忆"与"没搜到"：都显示同一句话的话，
                    //    用户会以为自己的记忆丢了（这个顾虑由 `filtered` 判，不是 `all`）
                    searching = keyword.isNotBlank(),
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    // 间距走设计系统的 `CardGap`，不各页各写一个数。
                    // 扁平风靠留白 + 阴影分层，卡片一挤，那 2dp 阴影就白给了。
                    verticalArrangement = Arrangement.spacedBy(CardGap),
                ) {
                    items(shown, key = { it.id }) { memory ->
                        MemoryCard(
                            memory = memory,
                            onEdit = { openEditor(memory) },
                            onDelete = { pendingDelete = memory },
                        )
                    }
                }
            }
        }
    }

    editing?.let { draft ->
        MemoryEditorDialog(
            initial = draft,
            isNew = editingTarget == null,
            onSave = { d ->
                val target = editingTarget
                if (target == null) vm.remember(d) else vm.edit(target, d)
                closeEditor()
            },
            onDismiss = { closeEditor() },
        )
    }

    pendingDelete?.let { target ->
        YukiConfirmDeleteDialog(
            title = "删掉这条记忆？",
            message = "「${target.content.take(30)}」删掉之后，就不会再想起这件事了。",
            onConfirm = {
                vm.forget(target.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }

    // 云端删除的确认（v0.61.39）——文案如实：删掉后「平时不会再想起」，
    // 不承诺"彻底抹除一切痕迹"（服务端语义 = OB 官方归档 + 抹掉档案文件）。
    pendingCloudDelete?.let { b ->
        YukiConfirmDeleteDialog(
            title = "从云端删掉这条？",
            message = "「${b.title.ifBlank { b.content }.take(30)}」删掉之后，" +
                "Ta 平时不会再想起它，云端列表里也不会再出现。这个操作不能撤销。",
            onConfirm = {
                vm.deleteCloudBucket(b.id)
                pendingCloudDelete = null
            },
            onDismiss = { pendingCloudDelete = null },
        )
    }

    fullBucket?.let { b ->
        CloudBucketFullDialog(bucket = b, onDismiss = { fullBucket = null })
    }
}

/* ═══════════════════ 说明条 ═══════════════════ */

/**
 * 顶部说明 —— 告诉用户记忆是怎么起作用的。
 *
 * 这不是凑数的文案：用户会直觉以为"写了记忆她就每轮都提"，实际是**相关时才注入**。
 * 不解释清楚，用户会以为记忆没生效。
 */
@Composable
private fun MemoryExplainer() {
    // 说明块**刻意不套 YukiCard**：这一页除了说明就是一列记忆卡片 ——
    // 若说明块也长成白卡片，用户分不清「哪一条是可管理的条目」。
    // 浅色实底是它与条目卡片唯一的区别，别为了"统一"把这条区别抹掉。
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        color = IceCyanSoft,
        shape = RoundedCornerShape(FieldCorner),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    YukiIcons.Book,
                    contentDescription = null,
                    tint = SkyBlueDeep,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "记得的事",
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(8.dp))
            // ⚠️ 上一版这段里用 Markdown 的加粗星号包住了「话题相关时」——
            // Compose 的 `Text` **不解析 Markdown**，那两个星号被原样画在了用户眼前。
            // （人设编辑页的缓存警告踩过同一个坑，这是第二处。）
            Text(
                "你写在这里的事会一直留着，并在话题相关时被想起来 —— " +
                    "不是每句都提，所以不会显得刻意。",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                // ⚠️ 2026-10-05 语义改回（用户拍板，沿革见 MemoryScopeTest）：记忆默认人设级、
                // 跨对话共享；会话级作为少数能力保留，卡片上标「本会话」。
                // 这句必须说清两件事 —— 否则：① 用户以为记忆还绑在单次对话上、不敢用；
                // ② 看到「本会话」标签不知道是什么意思。
                "记得的事跟着这个角色走 —— 换一段对话也还在。只属于当前对话的，会标着「本会话」。",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
            )
        }
    }
}

/* ═══════════════════ 迁移旧记忆 ═══════════════════ */

/**
 * 旧记忆迁移入口（2026-10-05）。
 *
 * 「会话级 → 人设级」改造上线后，老用户的存量记忆仍是会话级（只在当初那段对话里记得）。
 * 这里是把它们升级为人设级的**用户可点**入口 —— 不做静默迁移（用户拍板）。
 *
 * 为什么整条只在还有旧记忆时渲染：升级是一次性动作，完成即计数归零、这条自己消失；
 * 常驻的话，对从没有会话级记忆的用户就是一块没有意义的提示。
 * （判据的口径见 [MemoryViewModel.legacySessionCount]。）
 */
@Composable
private fun MigrationNotice(count: Int, onMigrate: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        // 暖色底 = 「有一件待办」。与「重要 ≥ 8」的暖色标签同源，区别于纯信息态的说明块。
        color = WarnAmberBg,
        shape = RoundedCornerShape(FieldCorner),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "有 $count 条记忆留在别的对话里",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "升级之后，Ta 在每段对话里都会记得。",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                )
            }
            Spacer(Modifier.width(10.dp))
            UpgradeButton(onClick = onMigrate)
        }
    }
}

/**
 * 「升级」行内按钮。
 *
 * 自绘而不是 Material 的 `TextButton`：与本页的筛选芯片同一套纪律 ——
 * 浅底 + 深色描边 + 深色字，不依赖 colorScheme 的映射（见 [YukiFilterChip] 的注释）。
 */
@Composable
private fun UpgradeButton(onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(SnowWhite)
            .border(1.dp, WarnAmber, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(
            text = "升级",
            style = MaterialTheme.typography.labelMedium,
            color = WarnAmber,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/* ═══════════════════ 过滤 ═══════════════════ */

@Composable
private fun FilterRow(
    current: MemoryFilter,
    counts: Map<MemoryFilter, Int>,
    onSelect: (MemoryFilter) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MemoryFilter.entries.forEach { f ->
            YukiFilterChip(
                label = "${f.label} ${counts[f] ?: 0}",
                selected = f == current,
                onClick = { onSelect(f) },
            )
        }
    }
}

/**
 * 自绘筛选芯片。
 *
 * ⚠️ 刻意**不用 Material 的 `FilterChip`**（文档 §28.1 要求选择器自定义），
 * 也刻意**不用"深底白字"**做选中态：文档 §45 的玻璃参数就是按深色背景给的，
 * 在浅色主题上踩过两次坑（液滴隐形、胶囊不可见）。这里改走
 * **浅蓝底 + 深蓝字 + 深蓝描边** —— 在雪白背景上对比度一定够，不依赖 colorScheme 的映射。
 */
@Composable
private fun YukiFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(if (selected) IceCyanSoft else SnowSurfaceDim)
            .border(
                width = 1.dp,
                color = if (selected) SkyBlueDeep else FrostLine,
                shape = shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) SkyBlueDeep else TextMuted,
        )
    }
}

/* ═══════════════════ 列表项 ═══════════════════ */

@Composable
private fun MemoryCard(
    memory: MemoryEntity,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    // 「现在」只取一次：同一次组合里所有条目共用同一个基准。
    // 否则一条说"今天"、紧挨着的一条说"昨天"（差别只是它晚算了几毫秒、跨过了午夜）。
    val now = remember { System.currentTimeMillis() }
    YukiCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp)) {
            Text(
                text = memory.content,
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary,
            )

            Spacer(Modifier.height(10.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                MetaTag(memory.category)
                Spacer(Modifier.width(6.dp))
                MetaTag(
                    text = "重要 ${memory.importance}",
                    // 重要度决定她多容易想起它 —— 到 8 以上就用暖色标出来。
                    // 口径与编辑对话框里「重要性 ≥ 8 变暖色」保持一致。
                    highlight = memory.importance >= HIGH_IMPORTANCE,
                )
                if (memory.scope == MemoryEntity.SCOPE_SESSION) {
                    Spacer(Modifier.width(6.dp))
                    MetaTag("本会话")
                }

                Spacer(Modifier.weight(1f))

                IconButton(onClick = onEdit) {
                    Icon(
                        YukiIcons.Pencil,
                        contentDescription = "编辑",
                        tint = TextMuted,
                        modifier = Modifier.size(16.dp),
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        YukiIcons.Delete,
                        contentDescription = "删除",
                        tint = TextMuted,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            // ── 时间（v0.49.0）──
            // 「什么时候记下的」+「最近有没有被想起来」。
            // 后者是**这条记忆在不在起作用**的唯一可见证据 ——
            // 只看创建时间的话，用户没法知道"她到底用没用过"。
            // ⚠️ **单独一行**，不挤在标签行里：那一行末尾有编辑/删除两个按钮，
            //    时间跟它们抢宽度，窄屏上会被压没。
            // ⚠️ 时间未知（老数据 createdAt=0）时 forMemory 返回空串，这里就不画，
            //    而不是画一个 1970 年（与 TimeLabels 的既有纪律一致）。
            val timeText = TimeLabels.forMemory(
                createdAt = memory.createdAt,
                lastAccessedAt = memory.lastAccessedAt,
                now = now,
            )
            if (timeText.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = timeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                )
            }
        }
    }
}

@Composable
private fun MetaTag(text: String, highlight: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = if (highlight) WarnAmber else TextMuted,
        modifier = Modifier
            .background(
                color = if (highlight) WarnAmberBg else SnowSurfaceDim,
                shape = RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** 「重要」到这个数就值得被看见。与编辑对话框里的暖色阈值同一个数。 */
private const val HIGH_IMPORTANCE = 8

/* ═══════════════════ 空状态 ═══════════════════ */

/**
 * 搜索框（v0.49.0）。
 *
 * ## 为什么不用 `YukiTextField`
 * 那个是为**多行长文本**设计的（记忆正文、人设设定），带标签行与字数统计。
 * 搜索是**单行、即时生效**的输入，套那套外壳会显得很重（一个搜索框占三行高）。
 *
 * ## 为什么用 `BasicTextField` 自绘而不是 `OutlinedTextField`
 * 与本页其它控件一致：底色走 `FieldFill`、圆角走 `FieldCorner`，
 * 不引入 material 的描边与浮动标签（那套观感与扁平卡片风不搭）。
 *
 * ⚠️ 占位文案要**明确提到"分类"**：用户不一定知道分类也算搜索范围，
 * 不写出来就不会有人去试。
 */
@Composable
private fun MemorySearchBar(value: String, onChange: (String) -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        color = FieldFill,
        shape = RoundedCornerShape(FieldCorner),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Icon(
                YukiIcons.Search,
                contentDescription = null,
                tint = TextMuted,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) {
                if (value.isEmpty()) {
                    Text(
                        "搜内容或分类，比如「喜好」",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextMuted,
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary),
                    cursorBrush = SolidColor(SkyBlueDeep),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            // 清除按钮只在有内容时出现 —— 常驻会给"空搜索框"多一个无处可去的图标
            if (value.isNotEmpty()) {
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = { onChange("") }, modifier = Modifier.size(22.dp)) {
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
}

@Composable
private fun EmptyMemoryHint(
    filter: MemoryFilter,
    hasAnyAtAll: Boolean,
    /** 是否因为**搜索**才空（v0.49.0）—— 空态文案必须区分"没记忆"与"没搜到" */
    searching: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            YukiIcons.Book,
            contentDescription = null,
            tint = SkyBlueDeep.copy(alpha = 0.5f),
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(12.dp))
        // ⚠️ 搜索无结果**必须**与"还没有记忆"分开说：两句话长得一样时，
        //    用户会以为自己的记忆丢了 —— 那是这一页最不该造成的误会。
        Text(
            text = when {
                searching -> "没找到匹配的记忆"
                hasAnyAtAll -> "这一类里还没有"
                else -> "还没有记住什么"
            },
            style = MaterialTheme.typography.titleSmall,
            color = TextPrimary,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = when {
                searching -> "换个词试试 —— 内容与分类都会搜。"
                hasAnyAtAll -> "换个筛选档看看。"
                else ->
                    "点右上角「＋」写下关于你的事 —— 比如「我喜欢在雨天听钢琴」。\n" +
                        "会一直记得，并在合适的时候提起来。"
            },
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
        )
    }
}

/* ═══════════════════ 编辑器 ═══════════════════ */

@Composable
private fun MemoryEditorDialog(
    initial: MemoryDraft,
    isNew: Boolean,
    onSave: (MemoryDraft) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf(initial) }

    YukiDialog(
        title = if (isNew) "记住一件事" else "改这条记忆",
        onConfirm = { onSave(draft) },
        onDismiss = onDismiss,
        confirmText = "保存",
    ) {
        Column {
            YukiTextField(
                value = draft.content,
                onValueChange = { draft = draft.copy(content = it) },
                label = "内容",
                placeholder = "希望记住什么？例如：我养了一只叫团子的猫",
                minLines = 3,
                isError = draft.isOverLimit,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${draft.normalizedContent.length} / ${MemoryDraft.MAX_CONTENT_CHARS}",
                style = MaterialTheme.typography.labelSmall,
                color = if (draft.isOverLimit) DangerRose else TextMuted,
            )

            Spacer(Modifier.height(12.dp))
            Text(
                "分类",
                style = MaterialTheme.typography.labelMedium,
                color = TextPrimary,
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MemoryDraft.CATEGORIES.forEach { c ->
                    YukiFilterChip(
                        label = c,
                        selected = draft.category == c,
                        onClick = { draft = draft.copy(category = c) },
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "重要性",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextPrimary,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "${draft.importance} / 10",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (draft.importance >= 8) WarnAmber else SkyBlueDeep,
                )
            }
            Slider(
                value = draft.importance.toFloat(),
                onValueChange = { draft = draft.copy(importance = it.toInt().coerceIn(0, 10)) },
                valueRange = 0f..10f,
                steps = 9,
                colors = SliderDefaults.colors(
                    thumbColor = SkyBlueDeep,
                    activeTrackColor = SkyBlueDeep,
                    inactiveTrackColor = SnowSurfaceDim,
                ),
            )
            Text(
                "越重要，越容易被想起来（也越不容易被淡忘）。",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
            )

            Spacer(Modifier.height(8.dp))
            Text(
                "记住的是「角色记忆」—— 对所有对话都有效。",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
            )
        }
    }
}


/* ─────────────── 本机 / 云端 页签（v0.61.36） ─────────────── */

private const val MEMORY_TAB_LOCAL = 0
private const val MEMORY_TAB_CLOUD = 1

/**
 * 记忆页顶部两个页签：**本机记忆**（手机里的 Room）/ **云端记忆**（服务器上 OB 记忆大脑）。
 *
 * 为什么分两个而不是合并：它们是**两套系统**（本机=简单检索、云端=语义/做梦），
 * 合并会出现"同一条记忆显示两次"的困惑。分开摆，用户一眼知道自己在看哪一份。
 */
@Composable
private fun MemoryTabs(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MemoryTabPill("本机记忆", selected == MEMORY_TAB_LOCAL, Modifier.weight(1f)) {
            onSelect(MEMORY_TAB_LOCAL)
        }
        MemoryTabPill("云端记忆", selected == MEMORY_TAB_CLOUD, Modifier.weight(1f)) {
            onSelect(MEMORY_TAB_CLOUD)
        }
    }
}

@Composable
private fun MemoryTabPill(label: String, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
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

/**
 * 「云端记忆」页签内容：读该人设的 OB 记忆 + 把本机记忆送上去。
 *
 * ⚠️ 只在人设开了「接入 Ta 的状态」时才有意义（[MemoryViewModel.loadCloud] 内部会拦）。
 * ⚠️ 进 OB 的记忆在服务器上是明文的（OB 要做语义检索），与"人设快照是密文"不同 —— 文案别含糊。
 */
@Composable
private fun CloudMemoryPane(
    vm: MemoryViewModel,
    onOpen: (XinchaoBucket) -> Unit,
    onDelete: (XinchaoBucket) -> Unit,
) {
    val text by vm.cloudText.collectAsStateWithLifecycle()
    val loading by vm.cloudLoading.collectAsStateWithLifecycle()
    val error by vm.cloudError.collectAsStateWithLifecycle()
    val syncing by vm.syncing.collectAsStateWithLifecycle()
    val progress by vm.syncProgress.collectAsStateWithLifecycle()
    val buckets by vm.cloudBuckets.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Text(
            "这里是 Ta 在「记忆大脑」里的记忆（存在云端，能语义检索、会做梦）。" +
                "手机里那份点上面的「本机记忆」看。",
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
            modifier = Modifier.padding(vertical = 8.dp),
        )

        MemorySearchBar(value = query, onChange = { query = it })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(
                "搜索云端",
                style = MaterialTheme.typography.labelLarge,
                color = SkyBlueDeep,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { vm.loadCloud(query) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }

        Spacer(Modifier.height(6.dp))
        Text(
            when {
                // v0.61.48：分批并发后给 k/N —— 原来只有一句静态文案，看起来像卡死
                syncing && progress != null -> "正在搬到云端 ${progress!!.first}/${progress!!.second}…"
                syncing -> "正在把本机记忆搬到云端…"
                // ⚠️ 2026-10-06 改文案（用户：「记忆上云在选云端时就等于默认开启，
                //    不用单独做个按钮」）—— 人设页那个「记忆上云」**开关**已删。
                //    这里留的是**一次性搬家**入口（把分轨前的历史本机记忆送上去），
                //    不是开关 —— 所以文案要写成"搬"，避免被读成"要不要开记忆上云"。
                else -> "↑ 把本机的旧记忆搬到云端"
            },
            style = MaterialTheme.typography.bodyLarge,
            color = if (syncing) TextMuted else SkyBlueDeep,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(FieldFill)
                .clickable(enabled = !syncing) { vm.syncLocalToCloud() }
                .padding(vertical = 14.dp),
        )

        Spacer(Modifier.height(14.dp))
        when {
            loading -> Text(
                "正在读取云端记忆…",
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted,
            )
            !error.isNullOrBlank() -> Text(
                error!!,
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted,
            )
            // 无关键词 → 结构化列表，按**域**分组
            buckets != null -> {
                if (buckets!!.isEmpty()) {
                    Text(
                        "云端还没有 Ta 的记忆。点上面的按钮把本机旧记忆搬过来。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextMuted,
                    )
                } else {
                    CloudBucketList(buckets!!, onOpen = onOpen, onDelete = onDelete)
                }
            }
            // 有关键词 → OB 语义检索的文本
            !text.isNullOrBlank() -> Text(
                text!!,
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary,
            )
            else -> Text(
                "在下面搜云端，或点上面的按钮搬旧记忆。",
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted,
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}


/* ─────────────── 云端记忆：分域列表（v0.61.37） ─────────────── */

/** 每条云端记忆最多显示几个正文行（列表要能扫，不是全文阅览器）。 */
private const val BUCKET_CONTENT_MAX_LINES = 3

/**
 * 把结构化云端记忆**按域分组**渲染（日常 / 工作 / 居家 / 梦境…）。
 *
 * ⚠️ 用普通 Column 而不是 LazyColumn：本页外层已经是 `verticalScroll`，
 *    LazyColumn 套在里面会因"无限高度"崩溃。
 * ⚠️ 每条正文截断（[BUCKET_CONTENT_MAX_LINES] 行）—— 云端是"浏览"，
 *    点卡片看全文是 [CloudBucketFullDialog]（v0.61.39 起）。
 */
@Composable
private fun CloudBucketList(
    buckets: List<XinchaoBucket>,
    onOpen: (XinchaoBucket) -> Unit,
    onDelete: (XinchaoBucket) -> Unit,
) {
    val byDomain = buckets.groupBy { it.domain }
    byDomain.forEach { (domain, items) ->
        Text(
            "· $domain（${items.size}）",
            style = MaterialTheme.typography.labelLarge,
            color = SkyBlueDeep,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
        )
        items.forEach { b ->
            CloudBucketCard(b, onOpen = onOpen, onDelete = onDelete)
        }
    }
}

/**
 * 一条云端记忆卡（v0.61.39：整卡可点**看全文**；右侧删除按钮走二次确认）。
 *
 * 「点开看全文」提示只在正文**真的被截断**时出现 —— 判据是真实排版结果
 * （`hasVisualOverflow`），不是拿字符数猜；内容短的卡片下面不会多一行废话。
 */
@Composable
private fun CloudBucketCard(
    b: XinchaoBucket,
    onOpen: (XinchaoBucket) -> Unit,
    onDelete: (XinchaoBucket) -> Unit,
) {
    // 正文是否被 [BUCKET_CONTENT_MAX_LINES] 行截断（只在变化时写状态，避免每帧重组）
    var clipped by remember(b.id) { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(CardCorner))
            .background(FieldFill)
            .clickable { onOpen(b) }
            .padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                val head = listOfNotNull(
                    b.title.takeIf { it.isNotBlank() },
                    b.importance.takeIf { it > 0 }?.let { "重要性 $it" },
                    b.createdAt?.take(10),
                ).joinToString(" · ")
                if (head.isNotBlank()) {
                    Text(head, style = MaterialTheme.typography.labelSmall, color = TextMuted)
                    Spacer(Modifier.height(4.dp))
                }
                Text(
                    b.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary,
                    maxLines = BUCKET_CONTENT_MAX_LINES,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { layout ->
                        if (clipped != layout.hasVisualOverflow) clipped = layout.hasVisualOverflow
                    },
                )
                if (clipped) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "点开看全文",
                        style = MaterialTheme.typography.labelSmall,
                        color = SkyBlueDeep,
                    )
                }
            }
            // 删除按钮在右侧、与正文垂直居中 —— 点它不会触发整卡的"看全文"（子级消费点击事件）
            IconButton(onClick = { onDelete(b) }) {
                Icon(
                    YukiIcons.Delete,
                    contentDescription = "从云端删除",
                    tint = TextMuted,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/**
 * 「看全文」弹窗（v0.61.39）—— 云端卡片点开后显示这条记忆的**完整正文**。
 *
 * 正文长度上限由服务端保证（`_OB_LIST_CONTENT_MAX` = 4000 字符），这里只做滚动、
 * 不再自行截断。单出口（「知道了」）—— 同 [ChangelogDialog]：这一页没有"操作"。
 */
@Composable
private fun CloudBucketFullDialog(bucket: XinchaoBucket, onDismiss: () -> Unit) {
    YukiDialog(
        title = bucket.title.ifBlank { "这条记忆" },
        onConfirm = onDismiss,
        onDismiss = onDismiss,
        confirmText = "知道了",
        // 只需一个出口：这里没有"操作"，关掉就是唯一动作
        dismissText = "",
        content = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                val meta = listOfNotNull(
                    bucket.domain.takeIf { it.isNotBlank() },
                    bucket.importance.takeIf { it > 0 }?.let { "重要性 $it" },
                    bucket.createdAt?.take(10),
                ).joinToString(" · ")
                if (meta.isNotBlank()) {
                    Text(meta, style = MaterialTheme.typography.labelSmall, color = TextMuted)
                    Spacer(Modifier.height(8.dp))
                }
                Text(
                    bucket.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary,
                )
            }
        },
    )
}
