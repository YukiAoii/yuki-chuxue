package ai.yuki.chuxue.ui.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.yuki.chuxue.data.DeepSeekClient
import ai.yuki.chuxue.data.ProviderGroup
import ai.yuki.chuxue.data.ProviderGroups
import ai.yuki.chuxue.data.ProviderType
import ai.yuki.chuxue.ui.ChatViewModel
import ai.yuki.chuxue.ui.components.Island
import ai.yuki.chuxue.ui.components.YukiCard
import ai.yuki.chuxue.ui.components.YukiConfirmDeleteDialog
import ai.yuki.chuxue.ui.icon.YukiIcons
import ai.yuki.chuxue.ui.theme.BrandBlueSoft
import ai.yuki.chuxue.ui.theme.CardGap
import ai.yuki.chuxue.ui.theme.DangerRose
import ai.yuki.chuxue.ui.theme.SkyBlueDeep
import ai.yuki.chuxue.ui.theme.SnowWhite
import ai.yuki.chuxue.ui.theme.TextMuted
import ai.yuki.chuxue.ui.theme.TextPrimary

/**
 * **连接设置**（v0.51.0 由「一套配置」改成「分组列表」）。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * 为什么改成列表（这是本轮最大的一件事）
 * ═══════════════════════════════════════════════════════════════════════════
 * 用户要「支持第三方 API（不只 DeepSeek）」。数据层早就做好了
 * （`ProviderGroup` + `Store.loadGroups` + `ChatViewModel.groups`），
 * 但**界面一直没接** —— 结果是分组只能靠"旧配置自动迁移"产生一个，
 * 用户既建不了第二个、也改不了地址和密钥，`ProviderGroups` 那一整套等于骨架。
 *
 * ⚠️ 这一页**只负责"有哪些分组"**，字段在 [ProviderGroupEditScreen]。
 * 理由与 v0.50.5 拆设置页同一条：一页一个读者。
 *
 * ## ⚠️ v0.57.0：把「设为当前 / 删除」收到这一页（用户要求）
 * 用户原话：「设置当前分组和删除分组**改到连接设置界面**」。这两个动作作用于
 * **列表上的某一行**，不属于"编辑这一个分组"的详情页 —— 放这儿才顺。
 *
 * ⚠️ 但**不能做成行内两个按钮**：一行里塞两个可点区域（进详情 / 切当前）在
 * 56dp 的高度里必然误触，而误触的后果不对称（本来只想看看，结果把全局默认
 * 分组换掉了）。所以做成**长按 → 底部操作面板**（与「人设」页长按卡片同款）。
 *
 * ⚠️ 渲染未经真机验证（本机无 adb / emulator）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiConfigScreen(
    vm: ChatViewModel,
    onBack: () -> Unit,
    /** 打开某个分组（[NEW_GROUP_ID] 表示新建）。 */
    onOpenGroup: (String) -> Unit,
) {
    val groups by vm.groups.collectAsStateWithLifecycle()
    val activeId by vm.activeGroupId.collectAsStateWithLifecycle()

    // 进这一页就拉一次免费分组：后台刚改过配置的话，用户不必重启 App 才看到。
    // 失败静默（见 ChatViewModel.refreshFreeGroup）—— 服务端挂了不该让这一页打不开。
    LaunchedEffect(Unit) { vm.refreshFreeGroup() }

    // "当前分组"取解析后的结果：记住的 id 可能指向一个已被删掉的分组，
    // `resolveActive` 会退回第一个而不是让列表上一个"当前"都不显示。
    val active = ProviderGroups.resolveActive(groups, activeId)

    /** 长按哪一行弹出的操作面板（null = 没开） */
    var menuFor by remember { mutableStateOf<ProviderGroup?>(null) }
    /** 待确认删除的分组 */
    var pendingDelete by remember { mutableStateOf<ProviderGroup?>(null) }

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
                actions = {
                    TextButton(onClick = { onOpenGroup(NEW_GROUP_ID) }) {
                        Text("新建", fontWeight = FontWeight.SemiBold)
                    }
                },
                title = { Text("连接设置", style = MaterialTheme.typography.titleMedium) },
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
            // ── 正在用（v0.57.0）──
            // 用户说这一页"过于空旷"。空的地方补**有用的**东西，不是补装饰：
            // 进来最想问的一句是"我现在到底在用哪一份"，那就把它顶在最上面。
            active?.let { a ->
                SettingsGroup("正在用") {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = a.name,
                                style = MaterialTheme.typography.titleSmall,
                                color = TextPrimary,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${a.modelCount} 个模型",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted,
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            // ⚠️ 托管分组的地址**不显示**（用户要求「地址和key不可见」）——
                            //    「只显示主机名」那条规则对它不适用：主机名同样是地址的一部分。
                            text = if (a.managed) {
                                "官方免费提供 · 地址由服务器维护"
                            } else {
                                DeepSeekClient.normalizeBaseUrl(a.baseUrl)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (a.modelCount == 0) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "⚠ 这一份还没勾选模型 —— 聊天里选不到它。点它进去加。",
                                style = MaterialTheme.typography.labelSmall,
                                color = DangerRose,
                            )
                        }
                    }
                }
            }

            // ⚠️ v0.58.0：按**归属**分成两段，而不是混在一个「全部分组」里。
            //    用户要的「差异化卡片样式」在这里落到结构上：官方那条与自己的那些
            //    不是同一类东西（一条不能删、不能改；另一条全归自己），
            //    混在一起时那两条差异只能靠读小字才看得出来。
            val official = groups.filter { it.managed }
            val mine = groups.filterNot { it.managed }

            if (official.isNotEmpty()) {
                SettingsGroup("官方免费") {
                    official.forEachIndexed { index, group ->
                        if (index > 0) LineDivider()
                        GroupRow(
                            group = group,
                            isActive = group.id == active?.id,
                            onOpen = { onOpenGroup(group.id) },
                            onLongClick = { menuFor = group },
                        )
                    }
                }
            }

            SettingsGroup(if (official.isEmpty()) "全部分组" else "我自己的分组") {
                if (mine.isEmpty()) {
                    // 正常路径下到不了这里（升级会迁出「默认」、新建至少留一个），
                    // 但 `ProviderGroups.decode` 对坏数据是**降级为空表**，
                    // 所以这个空态必须说清"下一步做什么"，而不是只显示"没有"。
                    Text(
                        text = "还没有自己加过分组。点右上角「新建」，填上服务商给你的地址和密钥 —— " +
                            "上面的官方分组不影响你自己建的那几个。",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                    )
                } else {
                    mine.forEachIndexed { index, group ->
                        if (index > 0) LineDivider()
                        GroupRow(
                            group = group,
                            isActive = group.id == active?.id,
                            onOpen = { onOpenGroup(group.id) },
                            onLongClick = { menuFor = group },
                        )
                    }
                }
            }

            Text(
                text = "点一行进去改；长按可以「设为当前」或「删除」。",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                modifier = Modifier.padding(horizontal = 4.dp),
            )

            Spacer(Modifier.height(16.dp))
        }
    }

    // 长按 → 底部操作面板。
    // ⚠️ 用 Material3 的 `ModalBottomSheet`（人设页 / 背景页已在用），
    //    不是 Android 原生弹窗 —— 满足项目"所有弹层都是 Compose 自绘"的要求。
    menuFor?.let { g ->
        ProviderActionSheet(
            group = g,
            isActive = g.id == active?.id,
            // ⚠️ 托管分组**永远不能删**（用户要求「不可删除」）——
            //    它由服务端维护：本地删掉之后要等下次启动才会重新下发，
            //    中间那段时间用户会以为"免费分组不见了"。
            canDelete = groups.size > 1 && !g.managed,
            onDismiss = { menuFor = null },
            onSetActive = {
                vm.setActiveGroup(g.id)
                Island.ok("已设为当前分组")
                menuFor = null
            },
            onDelete = { menuFor = null; pendingDelete = g },
        )
    }

    // 删除是**破坏性**的，额外要一次确认（与"设为当前"同样是刻意的阻力差）
    pendingDelete?.let { g ->
        YukiConfirmDeleteDialog(
            title = "删除「${g.name}」？",
            message = "这个分组会从列表里消失。用它聊过的对话会自动改回用当前分组" +
                "（对话记录本身不动）。",
            onConfirm = {
                vm.saveGroups(groups.filterNot { it.id == g.id })
                Island.ok("已删除")
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

/**
 * 长按一行弹出的**底部操作面板**。
 *
 * ## 为什么是面板、不是行内按钮
 * 见 [ApiConfigScreen] 的类注释：两个可点区域挤在一行里必然误触，
 * 而"误触删掉一个分组"和"误触点进详情"的代价完全不对称。
 *
 * ## 为什么删除还额外要一次确认
 * 面板只是"列出能做的事"；它不该让不可逆的动作与可逆的动作有同样的阻力。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderActionSheet(
    group: ProviderGroup,
    isActive: Boolean,
    canDelete: Boolean,
    onDismiss: () -> Unit,
    onSetActive: () -> Unit,
    onDelete: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SnowWhite,
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                text = group.name,
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            if (isActive) {
                // 已经是当前 —— 不画一个点了没用的死按钮，直接说明
                Text(
                    text = "它已经是「当前分组」。",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp),
                )
            } else {
                SheetAction("设为当前分组", onSetActive)
            }

            if (canDelete) {
                SheetAction("删除这个分组", onDelete, danger = true)
            } else {
                // ⚠️ 不画一个"点了只报警告"的死按钮：直接说明为什么没有删除。
                //    两种"不能删"的原因不同，所以文案也不同 ——
                //    只说"不能删"会让用户以为自己做错了什么。
                Text(
                    text = if (group.managed) {
                        "官方免费分组不能删除。它由服务器维护 —— 不想用它，" +
                            "在上面把自己新建的分组设为当前就行。"
                    } else {
                        "这是唯一的分组，不能删 —— 想换服务商，点它进去改地址和密钥就行。"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp),
                )
            }
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

/**
 * 分组列表的一行 = **一个目的地**（点进去改配置，长按出操作面板）。
 *
 * ⚠️ 这一行**没有**右侧的 `ChevronRight`：右边已经有"当前"标记了，
 * 再挂一个箭头会让右侧挤成三个元素。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupRow(
    group: ProviderGroup,
    isActive: Boolean,
    onOpen: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onOpen, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = group.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // fill=false：只占名字那么宽，让「官方」标紧跟在名字后面而不是被推到最右
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (group.managed) {
                    Spacer(Modifier.size(6.dp))
                    ManagedBadge()
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                // 「几个模型」是这一行最要紧的信息：0 个 = 这份分组还不能在聊天里选到
                text = "${ProviderType.label(group.providerType)} · ${group.modelCount} 个模型",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
            )
            Spacer(Modifier.height(1.dp))
            Text(
                // ⚠️ 托管分组**不显示地址**（用户要求「地址和key不可见」）；
                //    其余分组露主机名，为的是一眼分清"哪一份是官方、哪一份是中转"。
                text = if (group.managed) {
                    "地址与密钥由服务器维护"
                } else {
                    DeepSeekClient.normalizeBaseUrl(group.baseUrl)
                },
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (isActive) {
            Spacer(Modifier.size(12.dp))
            Text(
                text = "当前",
                style = MaterialTheme.typography.labelSmall,
                color = SkyBlueDeep,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(BrandBlueSoft)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
    }
}

/**
 * 「官方」小标 —— 托管分组在列表里的身份标记。
 *
 * ⚠️ 它必须**看得见**：那一条不能删、进去也看不到地址与密钥，
 *    没有一个显式的来源标记，用户只会觉得"这条怎么跟别的不一样"。
 */
@Composable
private fun ManagedBadge() {
    Text(
        text = "官方",
        style = MaterialTheme.typography.labelSmall,
        color = SkyBlueDeep,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(BrandBlueSoft)
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

/** 设置页统一的分组卡片（与对话设置页同款：一组一张卡）。 */
@Composable
internal fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = SkyBlueDeep,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )
        YukiCard(Modifier.fillMaxWidth()) {
            Column { content() }
        }
    }
}

/** 卡片内的分隔线（左侧缩进，与行内文字对齐）。 */
@Composable
internal fun LineDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}
