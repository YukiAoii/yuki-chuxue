package ai.yuki.chuxue.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.yuki.chuxue.data.AutoBackupPolicy
import ai.yuki.chuxue.data.Backup
import ai.yuki.chuxue.data.BackupScope
import ai.yuki.chuxue.data.CrashLog
import ai.yuki.chuxue.data.DataOverview
import ai.yuki.chuxue.data.ExportFormat
import ai.yuki.chuxue.data.ExportState
import ai.yuki.chuxue.ui.ChatViewModel
import ai.yuki.chuxue.ui.components.MultiPickerSheet
import ai.yuki.chuxue.ui.components.PickItem
import ai.yuki.chuxue.ui.components.SettingsGroup
import ai.yuki.chuxue.ui.components.YukiConfirmDeleteDialog
import ai.yuki.chuxue.ui.components.YukiDialog
import ai.yuki.chuxue.ui.icon.YukiIcons
import ai.yuki.chuxue.ui.theme.BrandBlue
import ai.yuki.chuxue.ui.theme.CardGap
import ai.yuki.chuxue.ui.theme.DangerRose
import ai.yuki.chuxue.ui.theme.SnowWhite
import ai.yuki.chuxue.ui.theme.SuccessMint
import ai.yuki.chuxue.ui.theme.TextMuted
import ai.yuki.chuxue.ui.theme.TextPrimary
import ai.yuki.chuxue.ui.theme.WarnAmber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 数据备份（v0.53.0 拆出；v0.55.0 **推翻重做**）。
 *
 * ## 这一版按什么顺序回答用户
 * 1. **现在有什么** —— 顶部四格数字（对话/消息/人设/记忆）；
 * 2. **怎么拿出去** —— 四个导出入口，各带**格式徽章**与图标底盘；
 * 3. **怎么拿回来** —— 一条导入，写明它是"替换"；
 * 4. **它自己会不会做** —— 自动备份（一个开关 = 每天一份）；
 * 5. **已经存了什么** —— 备份文件列表（可在软件内删）；
 * 6. **出事了怎么办** —— 崩溃日志 + 常见问题。
 *
 * ## ⚠️ 视觉上的几处刻意选择
 * - **分区标题带一个图标底盘**：一屏里有六七个分区，纯文字标题在读第二遍之前
 *   分不出哪是哪；一个色块就能让人"扫"到目标分区。
 * - **导出的四个入口各用不同色调**（蓝/绿/紫/橙）：它们长得一样时只能靠读字分辨。
 * - **格式徽章写在入口上**（"5 种格式"）：用户不需要点进去才知道有多少选择。
 * - **折叠式 FAQ**：六条问答全铺开会把"怎么导出"挤到屏幕外 —— 默认收起，
 *   点一下展开（这是本页唯一的"渐进披露"）。
 *
 * ## ⚠️ 三个 launcher 必须无条件注册在函数顶层
 * 放进条件分支会让重组时重复注册并丢结果（`ChatScreen` 选图片那个也栽过同一条）。
 *
 * ⚠️ 渲染未经真机验证（本机无设备）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsBackupScreen(vm: ChatViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val now = remember { System.currentTimeMillis() }

    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val personas by vm.personas.collectAsStateWithLifecycle()
    val exportState by vm.exportState.collectAsStateWithLifecycle()

    /* ── 导出流程的状态机 ── */
    var pickingSessions by remember { mutableStateOf(false) }
    var pickingPersonas by remember { mutableStateOf(false) }
    var personaScopeAsk by remember { mutableStateOf(false) }
    var waitingFormat by remember { mutableStateOf<ExportJob?>(null) }
    var waitingFolder by remember { mutableStateOf<Pair<ExportJob, ExportFormat>?>(null) }
    var deleting by remember { mutableStateOf<File?>(null) }
    var clearCrashes by remember { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<Uri?>(null) }
    var backupBusy by remember { mutableStateOf(false) }
    /** 备份（可恢复那套）的范围选择（v0.61.21）。 */
    var backupScopeAsk by remember { mutableStateOf(false) }
    var pendingBackupScope by remember { mutableStateOf<BackupScope?>(null) }

    /* ── 三个 launcher（无条件注册）── */
    val pickFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        val pending = waitingFolder
        waitingFolder = null
        if (uri != null && pending != null) {
            val (job, fmt) = pending
            when (job) {
                is ExportJob.Chat -> vm.exportSessions(job.ids, fmt, uri)
                is ExportJob.Personas -> vm.exportPersonas(job.ids, fmt, uri)
                ExportJob.Memories -> vm.exportMemories(fmt, uri)
            }
        }
    }
    val saveFile = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: Uri? -> uri?.let { vm.exportModelConfig(it) } }
    val pickImport = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? -> uri?.let { pendingImport = it } }
    /**
     * 备份（可恢复的那一套）的导出落点。
     *
     * ⚠️ v0.61.21：这条路以前**根本没接**（`exportBackupTo` 一个调用者都没有），
     * 而上面四个"导出"入口走的是另一套给人看的格式 —— 于是"分项备份"只有半边架子。
     */
    val saveBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: Uri? ->
        val scope = pendingBackupScope ?: BackupScope.ALL
        pendingBackupScope = null
        uri?.let { vm.exportBackupTo(it, scope) }
    }

    val overview by produceState(initialValue = DataOverview(0, 0, 0, 0)) { value = vm.dataOverview() }
    val stats by produceState(initialValue = 0 to 0L, backupBusy) { value = vm.snapshotStats() }
    var auto by remember { mutableStateOf(vm.autoBackupSettings()) }
    val crashes by produceState(initialValue = emptyList<Long>()) {
        value = CrashLog.list(ctx).map { it.lastModified() }
    }
    val backups by produceState(initialValue = emptyList<File>(), backupBusy) { value = vm.snapshots() }

    Scaffold(
        containerColor = SnowWhite,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SnowWhite,
                    titleContentColor = TextPrimary,
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(YukiIcons.Back, contentDescription = "返回") }
                },
                title = { Text("数据备份", style = MaterialTheme.typography.titleMedium) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(CardGap),
        ) {
            /* ⓪ 导出状态卡（有任务时才出现） */
            ExportStatusCard(exportState) { vm.clearExportState() }

            /* ① 现在有什么 —— 四格数字 */
            ZoneTitle("现在的数据", YukiIcons.Conversations, BrandBlue)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                NumberTile("对话", "${overview.sessions}", Modifier.weight(1f))
                NumberTile("消息", "${overview.messages}", Modifier.weight(1f))
                NumberTile("人设", "${overview.personas}", Modifier.weight(1f))
                NumberTile("记忆", "${overview.memories}", Modifier.weight(1f))
            }

            /* ② 导出 —— 四个入口 */
            ZoneTitle("导出", YukiIcons.Send, Color(0xFF2FA37C))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)),
            ) {
                ActionRow(
                    title = "导出聊天记录",
                    desc = "挑一段或多段对话，每段存成单独的文件",
                    badge = "5 种格式",
                    icon = YukiIcons.ChatBubble,
                    tint = BrandBlue,
                    onClick = { pickingSessions = true },
                )
                ActionRow(
                    title = "导出人设",
                    desc = "全部一起导，或只挑几个",
                    badge = "5 种格式",
                    icon = YukiIcons.Person,
                    tint = Color(0xFF2FA37C),
                    onClick = { personaScopeAsk = true },
                )
                ActionRow(
                    title = "导出记忆库",
                    desc = "Ta 记住的所有事，会标出属于谁",
                    badge = "5 种格式",
                    icon = YukiIcons.Book,
                    tint = Color(0xFF7C6BD6),
                    onClick = { waitingFormat = ExportJob.Memories },
                )
                ActionRow(
                    title = "导出模型配置",
                    desc = "分组、接口地址、密钥、勾选的模型 —— 一键全量",
                    badge = "含密钥",
                    badgeWarn = true,
                    icon = YukiIcons.Settings,
                    tint = Color(0xFFD98324),
                    onClick = { saveFile.launch("yuki-model-config-${stamp(now)}.json") },
                    last = true,
                )
            }
            Text(
                "导出的文件存到你在系统选择器里挑的地方（下载目录、网盘 App 都行）—— App 不替你决定。",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                modifier = Modifier.padding(horizontal = 6.dp),
            )

            /* ②′ 备份 —— **能在本 App 导回来**的那一份（v0.61.21） */
            // ⚠️ 它与上面四个入口是**两种东西**，刻意分开摆：
            //    上面那四个是"拿出去给人看"的（Markdown / 网页 / 表格…），导不回来；
            //    这一份是"用来恢复"的，格式由本 App 自己认。
            //    不分开的话，用户会以为"导出的都能导回来" —— 那正是备份里最坏的误会。
            ZoneTitle("备份（能导回来）", YukiIcons.Settings, Color(0xFF7C6BD6))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)),
            ) {
                ActionRow(
                    title = "导出备份",
                    desc = "只有这种能导回来；可以只导出某一类",
                    badge = "可恢复",
                    icon = YukiIcons.Settings,
                    tint = Color(0xFF7C6BD6),
                    onClick = { backupScopeAsk = true },
                    last = true,
                )
            }

            /* ③ 导入 */
            ZoneTitle("导入", YukiIcons.Add, Color(0xFF7C6BD6))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)),
            ) {
                ActionRow(
                    title = "用备份文件恢复",
                    desc = "替换本机数据（导入前自动存撤销点，API Key 保留）",
                    icon = YukiIcons.Back,
                    tint = Color(0xFF7C6BD6),
                    onClick = {
                        pickImport.launch(arrayOf("application/json", "text/plain", "*/*"))
                    },
                    last = true,
                )
            }

            /* ④ 自动备份 */
            ZoneTitle("自动备份", YukiIcons.Snowflake, BrandBlue)
            SettingsGroup("每天备份一份") {
                SwitchLine(
                    title = "开启自动备份",
                    desc = "打开 App 时检查一次：距上次超过一天，就再存一份全量备份。",
                    checked = auto.enabled,
                    onCheckedChange = {
                        vm.setAutoBackupEnabled(it)
                        auto = auto.copy(enabled = it)
                    },
                )
                LineDivider()
                FrequencyNote(auto.lastAt)
                LineDivider()
                MaxFilesRow(auto.maxFiles) {
                    vm.setAutoBackupMaxFiles(it)
                    auto = auto.copy(maxFiles = it)
                }
                LineDivider()
                StatLine("已存", if (stats.first > 0) "${stats.first} 份 · ${size(stats.second)}" else "还没有")
                LineDivider()
                TextButton(
                    onClick = {
                        backupBusy = true
                        vm.backupNow { backupBusy = false }
                    },
                    enabled = !backupBusy,
                ) {
                    Text(if (backupBusy) "正在备份…" else "立刻备份一次", color = BrandBlue)
                }
                Text(
                    "备份内容与「导出全部数据」完全相同 —— 它是能导入回来的 JSON。" +
                        "自动备份只在打开 App 时检查（本项目没有后台定时任务）。",
                    style = MaterialTheme.typography.labelSmall,
                    color = WarnAmber,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }

            /* ⑤ 已存的备份（可删） */
            if (backups.isNotEmpty()) {
                ZoneTitle("备份文件", YukiIcons.Book, Color(0xFF2FA37C))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)),
                ) {
                    backups.take(10).forEachIndexed { i, f ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier
                                    .size(34.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(BrandBlue.copy(alpha = 0.12f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    YukiIcons.Book,
                                    contentDescription = null,
                                    tint = BrandBlue,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(time(f.lastModified()), style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
                                Text(size(f.length()), style = MaterialTheme.typography.labelSmall, color = TextMuted)
                            }
                            TextButton(onClick = { deleting = f }) { Text("删除", color = DangerRose) }
                        }
                        if (i < minOf(backups.size, 10) - 1) {
                            Box(
                                Modifier.padding(start = 62.dp).fillMaxWidth().height(1.dp)
                                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                            )
                        }
                    }
                }
            }

            /* ⑥ 崩溃日志（有才显示） */
            if (crashes.isNotEmpty()) {
                ZoneTitle("崩溃日志", YukiIcons.Warning, WarnAmber)
                SettingsGroup("记录") {
                    StatLine("最近一次", time(crashes.first()))
                    LineDivider()
                    StatLine("共", "${crashes.size} 份")
                    LineDivider()
                    TextButton(onClick = { clearCrashes = true }) {
                        Text("清空崩溃日志", color = WarnAmber)
                    }
                    Text(
                        "App 崩了才会记（版本 / 机型 / 堆栈），不记对话内容，存在应用私有目录里。",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }

            /* ⑦ 常见问题（折叠） */
            ZoneTitle("常见问题", YukiIcons.Info, TextMuted)
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)),
            ) {
                FaqRow("备份里有什么？", "对话、人设、记忆、设置。不含 API Key（模型配置导出才含）、不含图片文件。")
                FaqRow("导入会覆盖吗？", "会。导入前自动存一个撤销点，API Key 保留。")
                FaqRow("自动备份什么时候跑？", "只在打开 App 时检查一次；没打开就不会跑。默认关闭。")
                FaqRow("导出的文件存哪了？", "存到你在系统选择器里挑的那个文件夹 —— App 不替你决定。")
                FaqRow("为什么聊天记录不带图片？", "图片是本地文件，导出后别人打不开；文件里用「[图片]」标出位置。")
                FaqRow("旧版的下载目录日志呢？", "以前聊天会往下载目录写 yuki_debug.txt，这个行为已经去掉，那份旧文件你可以直接删。", last = true)
            }

            Spacer(Modifier.height(20.dp))
        }
    }

    /* ── 弹窗与抽屉 ── */
    if (pickingSessions) {
        MultiPickerSheet(
            title = "导出哪些对话",
            hint = "可以多选；每段对话会是一个单独的文件。",
            items = sessions.sortedByDescending { it.updatedAt }.map { s ->
                PickItem(
                    id = s.id,
                    title = s.title.ifBlank { "未命名对话" },
                    subtitle = buildString {
                        append("${s.messages.size} 条")
                        personas.firstOrNull { it.id == s.personaId }?.let {
                            append(" · ")
                            append(it.customPrompt.substringBefore('\n').ifBlank { "未命名角色" })
                        }
                        append(" · ")
                        append(time(s.updatedAt))
                    },
                )
            },
            onConfirm = { ids ->
                pickingSessions = false
                if (ids.isNotEmpty()) waitingFormat = ExportJob.Chat(ids)
            },
            onDismiss = { pickingSessions = false },
        )
    }

    if (pickingPersonas) {
        MultiPickerSheet(
            title = "导出哪些人设",
            hint = "可以多选；它们会被导进同一个文件。",
            items = personas.map { p ->
                PickItem(
                    id = p.id,
                    title = p.customPrompt.substringBefore('\n').ifBlank { p.id },
                    subtitle = "称呼你为 ${p.userNickname}",
                )
            },
            onConfirm = { ids ->
                pickingPersonas = false
                if (ids.isNotEmpty()) waitingFormat = ExportJob.Personas(ids)
            },
            onDismiss = { pickingPersonas = false },
        )
    }

    if (personaScopeAsk) {
        YukiDialog(
            title = "导出人设",
            confirmText = "全部导出",
            dismissText = "只挑几个",
            onConfirm = {
                personaScopeAsk = false
                waitingFormat = ExportJob.Personas(emptyList())
            },
            onDismiss = {
                personaScopeAsk = false
                pickingPersonas = true
            },
        ) {
            Text(
                "全部导出会把所有 ${personas.size} 个人设放进一个文件；也可以只挑几个。",
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary,
            )
        }
    }

    if (backupScopeAsk) {
        YukiDialog(
            title = "导出哪些内容",
            confirmText = "取消",
            onConfirm = { backupScopeAsk = false },
            onDismiss = { backupScopeAsk = false },
        ) {
            Column {
                Text(
                    "这一份是用来恢复的，能在本 App 导回来。只导出某一类时，导入也只替换那一类 —— 别的都不动。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary,
                )
                BackupScope.entries.forEach { scope ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                backupScopeAsk = false
                                pendingBackupScope = scope
                                saveBackup.launch(
                                    Backup.suggestedFileName(System.currentTimeMillis(), scope),
                                )
                            }
                            .padding(vertical = 10.dp),
                    ) {
                        Text(
                            text = scope.label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = BrandBlue,
                        )
                        Text(
                            text = when (scope) {
                                BackupScope.ALL -> "对话、人设、记忆、设置全都要"
                                BackupScope.CHAT -> "只有对话 —— 人设、记忆、设置都不动"
                                BackupScope.MEMORY -> "只有 Ta 记住的事"
                                BackupScope.CONFIG -> "人设、接口与模型设置（不含密钥）"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                        )
                    }
                }
            }
        }
    }

    waitingFormat?.let { job ->
        YukiDialog(
            title = "导出成什么格式",
            confirmText = "取消",
            onConfirm = { waitingFormat = null },
            onDismiss = { waitingFormat = null },
        ) {
            Column(Modifier.fillMaxWidth()) {
                ExportFormat.entries.forEach { fmt ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    ) {
                        TextButton(onClick = {
                            waitingFormat = null
                            waitingFolder = job to fmt
                            pickFolder.launch(null)
                        }) {
                            Column {
                                Text(fmt.label, color = BrandBlue)
                                Text(
                                    when (fmt) {
                                        // ⚠️ v0.61.21：原来这里写「能再导入回来」，但 JSON 导出
                                        //    根本没有对应的导入路径（详见 ExportFormat.JSON 的注释）。
                                        //    备份的承诺必须是真的 —— 说能导回来，就得真能导回来。
                                        //    要恢复数据请用上面那条「整份备份」。
                                        ExportFormat.JSON -> "看结构 / 迁移数据用"
                                        ExportFormat.MARKDOWN -> "适合长文阅读"
                                        ExportFormat.HTML -> "浏览器打开，带样式"
                                        ExportFormat.TEXT -> "哪儿都能打开"
                                        ExportFormat.CSV -> "Excel / 表格软件"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextMuted,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    deleting?.let { f ->
        YukiConfirmDeleteDialog(
            title = "删掉这份备份？",
            message = "${time(f.lastModified())} · ${size(f.length())}。删了就找不回来了。",
            confirmText = "删除",
            onConfirm = {
                deleting = null
                backupBusy = !backupBusy
                vm.deleteBackup(f) { backupBusy = !backupBusy }
            },
            onDismiss = { deleting = null },
        )
    }

    if (clearCrashes) {
        YukiConfirmDeleteDialog(
            title = "清空崩溃日志？",
            message = "它们只用于排查问题，清掉后不会恢复。",
            confirmText = "清空",
            onConfirm = {
                clearCrashes = false
                CrashLog.clear(ctx)
            },
            onDismiss = { clearCrashes = false },
        )
    }

    pendingImport?.let { uri ->
        YukiConfirmDeleteDialog(
            title = "用这个备份替换本机数据？",
            message = "现有的对话、人设、记忆都会被替换（设置也会被覆盖，API Key 保留）。" +
                "导入前会自动存一个撤销点。",
            confirmText = "替换",
            onConfirm = {
                pendingImport = null
                vm.importBackupFrom(uri)
            },
            onDismiss = { pendingImport = null },
        )
    }
}

/* ─────────────── 流程任务 ─────────────── */

private sealed interface ExportJob {
    data class Chat(val ids: List<String>) : ExportJob
    /** [ids] 为空 = 全量导出 */
    data class Personas(val ids: List<String>) : ExportJob
    data object Memories : ExportJob
}

/* ─────────────── 分区标题 ─────────────── */

/**
 * 带图标底盘的分区标题。
 *
 * ⚠️ 一屏里有六七个分区，纯文字标题在读第二遍之前分不出哪是哪 ——
 *    一个色块 + 图标能让人"扫"到目标分区，而不是逐行读。
 */
@Composable
private fun ZoneTitle(text: String, icon: ImageVector, tint: Color) {
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(24.dp).clip(RoundedCornerShape(8.dp)).background(tint.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
        }
        Spacer(Modifier.width(9.dp))
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = TextPrimary,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/* ─────────────── 数字格 ─────────────── */

@Composable
private fun NumberTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextMuted)
    }
}

/* ─────────────── 入口行 ─────────────── */

/**
 * 一行入口：彩色图标底盘 + 标题 + 一句说明 + **格式徽章** + 箭头。
 *
 * ⚠️ 徽章（"5 种格式" / "含密钥"）写在入口上，用户不必点进去才知道有多少选择；
 *    而"含密钥"用警示色 —— 它是一个**提醒**，不是一个卖点。
 */
@Composable
private fun ActionRow(
    title: String,
    desc: String,
    icon: ImageVector,
    tint: Color,
    onClick: () -> Unit,
    badge: String = "",
    badgeWarn: Boolean = false,
    last: Boolean = false,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(tint.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(19.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
                    if (badge.isNotBlank()) {
                        Spacer(Modifier.width(8.dp))
                        val c = if (badgeWarn) WarnAmber else tint
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(50))
                                .background(c.copy(alpha = 0.14f))
                                .padding(horizontal = 7.dp, vertical = 2.dp),
                        ) {
                            Text(
                                badge,
                                style = MaterialTheme.typography.labelSmall,
                                color = c,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(3.dp))
                Text(desc, style = MaterialTheme.typography.labelSmall, color = TextMuted)
            }
            Icon(
                YukiIcons.ChevronRight,
                contentDescription = null,
                tint = TextMuted,
                modifier = Modifier.size(18.dp),
            )
        }
        if (!last) {
            Box(
                Modifier
                    .padding(start = 68.dp)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            )
        }
    }
}

/* ─────────────── 导出状态卡 ─────────────── */

/**
 * 导出/备份的**临时状态卡**（用户要求：成功与否、导出路径、点击跳转、进度条）。
 *
 * ⚠️ 它**不自己消失**：用户可能要照着它去文件管理器找文件，
 *    也要能回头看"刚才那个到底成没成"。看完由他点掉。
 */
@Composable
private fun ExportStatusCard(state: ExportState, onDismiss: () -> Unit) {
    if (state is ExportState.Idle) return

    val accent = when (state) {
        is ExportState.Running -> BrandBlue
        is ExportState.Done -> SuccessMint
        is ExportState.Failed -> DangerRose
        ExportState.Idle -> BrandBlue
    }

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(accent.copy(alpha = 0.10f))
            .padding(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // 左侧一道竖条：状态卡在滚动里要能被一眼认出（它和别的卡片长得不一样）
        Box(
            Modifier
                .width(3.dp)
                .height(38.dp)
                .clip(RoundedCornerShape(50))
                .background(accent),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            when (state) {
                is ExportState.Running -> {
                    Text(
                        "正在导出…（${state.done} / ${state.total}）",
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = {
                            if (state.total <= 0) 0f
                            else (state.done.toFloat() / state.total).coerceIn(0f, 1f)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        color = accent,
                    )
                }
                is ExportState.Done -> {
                    Text(
                        "完成：${state.count} 份",
                        style = MaterialTheme.typography.bodyLarge,
                        color = accent,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text("位置：${state.where}", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                }
                is ExportState.Failed -> {
                    Text(
                        "没成功",
                        style = MaterialTheme.typography.bodyLarge,
                        color = accent,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(state.reason, style = MaterialTheme.typography.labelSmall, color = TextMuted)
                }
                ExportState.Idle -> Unit
            }
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth()) {
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterEnd)) {
                    Text("知道了", color = TextMuted)
                }
            }
        }
    }
}

/* ─────────────── 小件 ─────────────── */

@Composable
private fun StatLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
        Spacer(Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyLarge, color = TextMuted)
    }
}

/**
 * 自动备份的频率说明（v0.55.0）。
 *
 * ⚠️ 这里原本是「允许备份的时段」（起止小时）。用户要求去掉它、换成一个开关 ——
 *    开关的语义就是**每天一份**。
 *
 *    那个时段设置原本的用处是"别在我玩手机的时候备份"，但它有两个真实的坏处：
 *    1. 想让它跑，用户得先把时间调对，而多数人根本不会回来调；
 *    2. 调错了（填反、忘了改）就**永远不会备份** —— 而这一点从界面上完全看不出来。
 *    备份本身很轻（一份 JSON），挑时段的收益不值这两个代价。
 */
@Composable
private fun FrequencyNote(lastAt: Long) {
    val now = System.currentTimeMillis()
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text("多久存一份", style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (lastAt <= 0) {
                "每天一份。还没有自动备份过 —— 打开 App 时就会存下第一份。"
            } else {
                val hours = (now - lastAt) / 3_600_000L
                if (hours >= 24) {
                    "每天一份。上次在 ${hours / 24} 天前 —— 下次打开 App 时就会补上。"
                } else {
                    "每天一份。上次在 ${hours} 小时前，还不到一天。"
                }
            },
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
        )
    }
}

@Composable
private fun MaxFilesRow(value: Int, onChange: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text("最多保留几份", style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Stepper(
                text = "$value 份",
                onMinus = { onChange((value - 1).coerceAtLeast(AutoBackupPolicy.MIN_MAX_FILES)) },
                onPlus = { onChange((value + 1).coerceAtMost(AutoBackupPolicy.MAX_MAX_FILES)) },
            )
        }
        Text(
            "（${AutoBackupPolicy.MIN_MAX_FILES}–${AutoBackupPolicy.MAX_MAX_FILES} 份，超了删最旧的）",
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
        )
    }
}

@Composable
private fun Stepper(text: String, onMinus: () -> Unit, onPlus: () -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onMinus) { Text("−") }
        Text(text, style = MaterialTheme.typography.bodyLarge, color = BrandBlue)
        TextButton(onClick = onPlus) { Text("+") }
    }
}

/**
 * 一条可展开的问答（本页唯一的"渐进披露"）。
 *
 * ⚠️ 六条问答全铺开会把"怎么导出"挤到屏幕外；默认收起，点一下展开。
 *    箭头跟着转 90° —— 那一下转动是"它确实开了"的即时反馈。
 */
@Composable
private fun FaqRow(q: String, a: String, last: Boolean = false) {
    var open by remember { mutableStateOf(false) }
    val angle by animateFloatAsState(if (open) 90f else 0f, label = "faqArrow")
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { open = !open }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                q,
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary,
                modifier = Modifier.weight(1f),
            )
            Icon(
                YukiIcons.ChevronRight,
                contentDescription = null,
                tint = TextMuted,
                modifier = Modifier.size(18.dp).rotate(angle),
            )
        }
        AnimatedVisibility(visible = open) {
            Text(
                a,
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
            )
        }
        if (!last) {
            Box(
                Modifier
                    .padding(start = 16.dp)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            )
        }
    }
}

private fun stamp(at: Long): String =
    SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(at))

private fun time(at: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(at))

private fun size(bytes: Long): String = when {
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> "%d KB".format(bytes / 1024)
    else -> "$bytes B"
}
