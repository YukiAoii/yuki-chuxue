package ai.yuki.chuxue.ui.persona

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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.yuki.chuxue.data.Persona
import ai.yuki.chuxue.data.XinchaoDream
import ai.yuki.chuxue.data.XinchaoIntent
import ai.yuki.chuxue.data.XinchaoState
// ⚠️ `PersonaEditor` 在 **`ai.yuki.chuxue.ui`** 包（`PersonaScreen.kt` 的包声明是与文件名不一致的
//    历史遗留），不是 `ui.persona` —— 所以这里必须显式 import。
import ai.yuki.chuxue.ui.PersonaEditor
import ai.yuki.chuxue.ui.ChatViewModel
import ai.yuki.chuxue.ui.components.SettingsGroup
import ai.yuki.chuxue.ui.components.UserAvatar
import ai.yuki.chuxue.ui.icon.YukiIcons
import ai.yuki.chuxue.ui.theme.BrandBlue
import ai.yuki.chuxue.ui.theme.BrandBlueDeep
import ai.yuki.chuxue.ui.theme.CardGap
import ai.yuki.chuxue.ui.theme.OutlineLight
import ai.yuki.chuxue.ui.theme.SnowWhite
import ai.yuki.chuxue.ui.theme.TextMuted
import ai.yuki.chuxue.ui.theme.TextPrimary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * **人设详情页**（v0.56.0，用户要求新增）。
 *
 * ## 为什么要有这一页
 * 用户原话："人设列表点击人设不是直接进入人设编辑界面而是人设详情界面"。
 *
 * 那个改动是对的，理由比"多一个页面"更深：**"编辑"和"相处"是两件事**。
 * 以前点一下人设就掉进一堆输入框里 —— 用户想看"Ta是谁、我们聊过什么"时，
 * 迎面而来的是"角色设定""思考强度""是否使用通用设定"。详情页把这两件事分开：
 * 这一页是**看**（Ta是谁、我们多久了、Ta记得什么），编辑是另一页（要点进去）。
 *
 * ## 关系锚点（用户说这段"极度增强沉浸感"）
 * "相遇于 2026 年 9 月 29 日 · 已经一起度过 12 天"。
 *
 * ⚠️ **起算点是"第一次真正对话那天"，不是人设创建那天** —— 用户让我自己定，我选后者（第一次对话）。
 *    理由：建了人设却从没聊过，显示"已共度 5 天"很怪 —— 那 5 天里"Ta"并不存在。
 *    **没有人设会话时这一行完全不显示**（而不是显示 0 天）。
 *
 * ## 记忆快照
 * 用户要求"不用点进去，直接展示最近提取的 3 条记忆"。记忆是**按会话**存的，
 * 所以这里的口径是：**跨该人设的全部会话，取最近 3 条**。
 *
 * ⚠️ 渲染未经真机验证（本机无设备）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonaDetailScreen(
    vm: ChatViewModel,
    personaId: String,
    onBack: () -> Unit,
    /** 进入对话：有会话 → 跳最近一次；没有 → 由上层新建。参数是人设 id */
    onEnterChat: (String) -> Unit,
    /** 打开记忆库（需要会话 id —— 调用方负责挑）；参数是会话 id */
    onOpenMemory: (String) -> Unit,
    /** 「专属表情包」→ 该人设的专属库（v0.57.0；参数是人设 id，与 [personaId] 相同） */
    onOpenPersonaEmoji: (String) -> Unit = {},
) {
    val personas by vm.personas.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle()

    // 「编辑人设」在这一页内部完成（`PersonaEditor` 是同一个包里的 internal 组件）——
    // 不让它变成一次导航往返：用户从详情点编辑、改完自然应该**回到详情**看到结果。
    var editing by remember { mutableStateOf<Persona?>(null) }

    val persona = personas.firstOrNull { it.id == personaId }

    // 这个人设的全部会话（最近一次在最前）
    val mine = remember(sessions, personaId) {
        sessions.filter { it.personaId == personaId }.sortedByDescending { it.updatedAt }
    }
    val latest = mine.firstOrNull()

    // 记忆快照：跨该人设全部会话取最近 3 条（异步，取不到就是空）
    val memories by produceState(initialValue = emptyList<String>(), personaId) {
        value = vm.recentMemoriesOf(personaId, 3)
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
                title = { Text("人设", style = MaterialTheme.typography.titleMedium) },
                actions = {
                    IconButton(onClick = { editing = persona }) {
                        Icon(YukiIcons.Pencil, contentDescription = "编辑人设")
                    }
                },
            )
        },
    ) { padding ->
        if (persona == null) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("这个人设找不到了。", color = TextMuted, style = MaterialTheme.typography.bodyMedium)
            }
            return@Scaffold
        }

        // ⚠️ 统一走 `Persona.displayName`（v0.61.10）—— 这里原本**又抄了一份**"设定首行"
        //    的口径，于是同一个人设在列表 / 会话标题 / 本页三处可能显示三个不同的名字。
        //    口径只留一个（回退链见 Persona.displayName）。
        val displayName = persona.displayName

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(CardGap),
        ) {
            Spacer(Modifier.height(4.dp))

            /* ① 封面 + 压边头像 + 名字 + 签名位（v0.58.0 照 QQ 好友资料页重排）*/
            val since = mine.minOfOrNull { it.createdAt }?.takeIf { it > 0 }
            PersonaCover(
                persona = persona,
                displayName = displayName,
                since = since,
                turns = mine.size,
            )

            /* ② 快捷动作行：QQ 资料页最上面那一排圆按钮。
                  放在这里而不是塞进卡片 —— 见Ta的第一件事就是"说话"，
                  滚动之前必须够得到。 */
            QuickActions(
                primary = if (latest != null) "继续和 Ta 说话" else "开始和 Ta 说话",
                onChat = { onEnterChat(personaId) },
                onEmoji = { onOpenPersonaEmoji(personaId) },
                onMemory = latest?.let { s -> { onOpenMemory(s.id) } },
            )

            /* ③ 资料卡：像好友资料那样一条条列出来（左标签 / 右内容）。
                  ⚠️ 这些值以前散在 hero、锚点卡、文件名里各说各的；
                     收成一张"资料"卡之后，用户找"Ta 怎么称呼我"只需要看一个地方。 */
            // AI 为 Ta 写的一小段简介（v0.61.21，用户要的"让 AI 填详情页内容"）。
            // ⚠️ **没有就不显示**：不能因为还没生成成功、或生成失败就留一块空的。
            // ⚠️ 它只给界面看，**不进提示词** —— 见 Persona.detailSummary 的 KDoc。
            persona.detailSummary?.takeIf { it.isNotBlank() }?.let { summary ->
                SettingsGroup("关于 Ta") {
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }

            // 「Ta 此刻」：接入「Ta 的状态」后，在 App 里也能看到 Ta 现在的心情（v0.61.35）。
            // ⚠️ 卡位放在「关于 Ta」（Ta 是谁）与「资料」（你们相处）之间 —— 先认人、
            //    再看神情、再翻记录，与详情页"看"的叙事顺序一致。
            // ⚠️ 未接入（开关没开）时**整块不出现**：它是"接入之后才有的东西"，没接入却摆一块
            //    空的反而像坏了（开关在创建 / 编辑人设页，那里的话已说明开了在哪能看到）。
            if (persona.isCloudMemory) {
                var loaded by remember(personaId) { mutableStateOf(false) }
                val state by produceState<XinchaoState?>(initialValue = null, personaId) {
                    value = vm.xinchaoStateOf(personaId)
                    loaded = true
                }
                // v0.61.52：与状态同一批拉（都只在这张卡上用），失败各自 null、互不影响
                val intent by produceState<XinchaoIntent?>(initialValue = null, personaId) {
                    value = vm.xinchaoIntentOf(personaId)
                }
                val dream by produceState<XinchaoDream?>(initialValue = null, personaId) {
                    value = vm.xinchaoDreamOf(personaId)
                }
                XinchaoNowCard(state, loaded, intent, dream)
            }

            SettingsGroup("资料") {
                // ⚠️ v0.61.21：**去掉了「Ta 怎么称呼你」那一行**（用户要求）。
                //    字段本身还在（老用户的值照旧进提示词），只是不再摆在这里 ——
                //    详情页该讲"Ta 是谁"，而"Ta 怎么称呼你"讲的是你。
                InfoRow("你的性别", persona.userGender.ifBlank { "（没填）" })
                if (since != null) {
                    InfoDivider()
                    InfoRow("相遇于", fullDate(since))
                    InfoDivider()
                    InfoRow("一起走过", if (daysSince(since) <= 0) "从今天开始" else "${daysSince(since)} 天")
                    InfoDivider()
                    // ⚠️ v0.61.21：由「N 段对话」改成**聊过多少条消息**（用户要求）。
                    //    「段」是**我这边**的容器概念（会话），用户关心的是"我们说了多少话"。
                    //    条数直接从已加载的会话里数 —— 这条流本来就带 messages
                    //  （见 SessionRepository.observeSessions 的注释），不发新查询。
                    //    口径：这个角色下**所有会话里的所有消息**（含开场白那句，
                    //    它确实是一条消息；不另做筛选，免得这个数字要靠解释才懂）。
                    InfoRow("聊过", "${mine.sumOf { it.messages.size }} 条消息")
                }
                if (persona.note.isNotBlank()) {
                    InfoDivider()
                    InfoRow("备注（只有你看得到）", persona.note)
                }
            }

            /* ④ 记忆 */
            SettingsGroup("Ta记得的") {
                if (memories.isEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            YukiIcons.Snowflake,
                            contentDescription = null,
                            tint = BrandBlue.copy(alpha = 0.45f),
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "还没有记忆。聊到值得记的事，Ta会自己记下来。",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                        )
                    }
                    return@SettingsGroup
                }
                memories.forEachIndexed { i, m ->
                    if (i > 0) {
                        Box(
                            Modifier
                                .padding(start = 16.dp)
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(OutlineLight),
                        )
                    }
                    Text(
                        text = m,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
                if (latest != null) {
                    TextButton(onClick = { onOpenMemory(latest.id) }) {
                        Text("打开记忆库", color = BrandBlue)
                    }
                }
            }

            /* ⑤ 会话（Ta也可能有好几段） */
            if (mine.size > 1) {
                SettingsGroup("聊过的") {
                    mine.take(5).forEachIndexed { i, s ->
                        if (i > 0) {
                            Box(
                                Modifier
                                    .padding(start = 16.dp)
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(OutlineLight),
                            )
                        }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onOpenMemory(s.id) }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    s.title.ifBlank { "未命名对话" },
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "${s.messages.size} 条 · ${date(s.updatedAt)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextMuted,
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
                }
            }

            /* ⑥ 编辑入口（顶栏有个铅笔，这里再给一个带字的 —— 图标按钮认不出的人不少） */
            SettingsGroup("这个人设") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { editing = persona }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        YukiIcons.Pencil,
                        contentDescription = null,
                        tint = BrandBlue,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "编辑人设",
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    Text("改设定 / 头像 / 备注", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                }
                Box(
                    Modifier
                        .padding(start = 16.dp)
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(OutlineLight),
                )
                // 专属表情包（v0.57.0）：两个入口之一 —— 另一个在设置里的「全局表情包」。
                // 放在详情页也合理：用户在这里看"Ta是谁"，顺手也能管Ta专属的图。
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpenPersonaEmoji(personaId) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        YukiIcons.Image,
                        contentDescription = null,
                        tint = BrandBlue,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "专属表情包",
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    Text("只给 Ta 用的图", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
    // 「编辑人设」：改完**回到详情**（而不是退到列表）—— 用户改的就是这里看到的东西
    if (editing != null) {
        PersonaEditor(
            initial = editing!!,
            isNew = false,
            // 老数据（useGlobalPrefix == null）显示**实际生效值** —— 见 PersonaEditor 的注释
            globalPrefixFallback = vm.settings.value.globalPrefixEnabled,
            // 服务端开关决定「通用设定」那一块显不显示（v0.58.0）
            globalPrefixAvailable = vm.features.value.personaGlobalPrefix,
            onSave = {
                vm.upsertPersona(it)
                editing = null
            },
            onCancel = { editing = null },
            // 详情页里打开的编辑器，「专属表情包」同样能直达（v0.57.0）
            onOpenEmoji = onOpenPersonaEmoji,
        )
    }
}

/* ─────────────── 封面（v0.58.0：照 QQ 好友资料页重排） ─────────────── */

/**
 * 封面横幅 + **压在横幅下边缘的头像** + 名字 + 签名位（备注）。
 *
 * ## 为什么改成这个样子
 * 用户要「类似在 QQ 中查看好友的样式的布局」。旧版是一个圆角大蓝块里居中塞头像、
 * 名字、备注胶囊 —— 三样东西挤在一块底色里，读起来像一张名片，不像"一个人"。
 * 现在拆成：**横幅在上、头像压边、名字在下**，与 QQ/微信的好友资料页同构。
 *
 * ⚠️ 压边用「横幅底部留 46dp 不给画 + 头像 `align(BottomCenter)`」实现，
 *    **不用 `offset` 负位移** —— offset 只是视觉平移，布局空间还占着，
 *    下面会凭空多出一段空白（这种"说不清哪里不对"的空白最难查）。
 */
@Composable
private fun PersonaCover(persona: Persona, displayName: String, since: Long?, turns: Int) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 46.dp)
                    .height(128.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Brush.linearGradient(listOf(BrandBlue, BrandBlueDeep))),
            ) {
                // 两团半透明圆造空间感（与「我的」页同一手法，不引图片资源）
                Canvas(Modifier.matchParentSize()) {
                    drawCircle(
                        color = Color.White.copy(alpha = 0.15f),
                        radius = size.minDimension * 0.40f,
                        center = Offset(size.width * 0.88f, size.height * 0.10f),
                    )
                    drawCircle(
                        color = Color.Black.copy(alpha = 0.10f),
                        radius = size.minDimension * 0.52f,
                        center = Offset(size.width * 0.06f, size.height * 1.06f),
                    )
                    // 几粒"初雪"：静态小点（不是动画）——给横幅一点天气，不抢主体。
                    // 全部手画、不引图片资源（项目一贯手法；深浅屏幕上都是同一支透明白）。
                    val snow = listOf(
                        0.16f to 0.30f, 0.30f to 0.74f, 0.55f to 0.22f,
                        0.72f to 0.60f, 0.87f to 0.36f, 0.44f to 0.86f,
                    )
                    snow.forEachIndexed { i, (fx, fy) ->
                        drawCircle(
                            color = Color.White.copy(alpha = if (i % 2 == 0) 0.32f else 0.18f),
                            radius = size.minDimension * (if (i % 3 == 0) 0.016f else 0.010f),
                            center = Offset(size.width * fx, size.height * fy),
                        )
                    }
                }

                // 签名位 = 备注。放在封面上而不是清单里：QQ 上那儿就是"个性签名"，
                // 一眼就知道"这句是写给Ta旁边那个名字的"。
                if (persona.note.isNotBlank()) {
                    Box(
                        Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 16.dp, bottom = 12.dp)
                            .clip(RoundedCornerShape(50))
                            .background(SnowWhite.copy(alpha = 0.20f))
                            .padding(horizontal = 12.dp, vertical = 5.dp),
                    ) {
                        Text(
                            text = "备注 · ${persona.note}",
                            style = MaterialTheme.typography.labelMedium,
                            color = SnowWhite,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                if (since != null) {
                    Text(
                        text = "一起 $turns 段",
                        style = MaterialTheme.typography.labelSmall,
                        color = SnowWhite.copy(alpha = 0.88f),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 16.dp, bottom = 14.dp),
                    )
                }
            }

            // 头像：压住横幅的下边缘。外面套一圈白，做出"扣出来压上去"的层次
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .size(92.dp)
                    .clip(CircleShape)
                    .background(SnowWhite)
                    .padding(4.dp),
            ) {
                UserAvatar(size = 84.dp, path = persona.avatarPath, fallbackText = displayName)
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            text = displayName,
            style = MaterialTheme.typography.titleLarge,
            color = TextPrimary,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (since != null) {
            Spacer(Modifier.height(3.dp))
            Text(
                text = "相遇于 ${fullDate(since)}",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
            )
        }
    }
}

/* ─────────────── 快捷动作行 ─────────────── */

/**
 * 并排的动作块：说话 / 表情包 / 记忆。
 *
 * ⚠️ 「记忆」只在**真的有会话**时才给：记忆是按会话存的，没有会话就没有记忆库可开 ——
 *    给一个点了只能弹"还没有"的按钮，不如不给。
 * ⚠️ 「说话」占两格宽度：它是这一页的主动作，与另外两个一样宽会分不清主次。
 */
@Composable
private fun QuickActions(
    primary: String,
    onChat: () -> Unit,
    onEmoji: () -> Unit,
    onMemory: (() -> Unit)?,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        QuickAction(primary, YukiIcons.ChatBubble, onChat, Modifier.weight(2f), filled = true)
        QuickAction("表情包", YukiIcons.Image, onEmoji, Modifier.weight(1f))
        onMemory?.let { QuickAction("记忆", YukiIcons.Snowflake, it, Modifier.weight(1f)) }
    }
}

@Composable
private fun QuickAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (filled) BrandBlue else MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(vertical = 13.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (filled) SnowWhite else BrandBlue,
            modifier = Modifier.size(17.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (filled) SnowWhite else TextPrimary,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/* ─────────────── 资料行 ─────────────── */

/**
 * 资料卡里的一行：左标签 / 右内容。
 *
 * ⚠️ 标签列**固定宽度**（112dp）：不固定的话每一行的冒号位置都不一样，
 *    一眼看过去是"七零八落"而不是"一张表格"。
 */
@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = TextMuted,
            modifier = Modifier.width(112.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = TextPrimary,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun InfoDivider() {
    Box(
        Modifier
            .padding(start = 16.dp)
            .fillMaxWidth()
            .height(1.dp)
            .background(OutlineLight),
    )
}

/* ─────────────── 小工具 ─────────────── */

private const val DAY_MS = 24 * 60 * 60 * 1000L

/** 从那天到今天过了几天（按本地日切）。当天 = 0。 */
private fun daysSince(from: Long): Int {
    val a = startOfDay(from)
    val b = startOfDay(System.currentTimeMillis())
    return ((b - a) / DAY_MS).toInt()
}

private fun startOfDay(t: Long): Long = java.util.Calendar.getInstance().apply {
    timeInMillis = t
    set(java.util.Calendar.HOUR_OF_DAY, 0)
    set(java.util.Calendar.MINUTE, 0)
    set(java.util.Calendar.SECOND, 0)
    set(java.util.Calendar.MILLISECOND, 0)
}.timeInMillis

private fun fullDate(at: Long): String =
    SimpleDateFormat("yyyy 年 M 月 d 日", Locale.getDefault()).format(Date(at))

private fun date(at: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(at))


/* ─────────────── 「Ta 此刻」卡（v0.61.35） ─────────────── */

/**
 * 详情页「Ta 此刻」卡。
 *
 * 数据来自 [ChatViewModel.xinchaoStateOf] —— 与用户侧网页 `/me/` **同源**
 *（服务端人设列表端点的 `view`，文本已由服务端清洗成用户向，App 不重复清洗）。
 *
 * 三态：[loaded] 为 false 是"正在读"；读完仍 [state] 为空是"还没聊过"；有值才渲染内容。
 * ⚠️ 拉不到 / 没内容都只显示一行灰字，**不报错、不空白** —— 这块是增强信息。
 */
@Composable
private fun XinchaoNowCard(
    state: XinchaoState?,
    loaded: Boolean,
    // v0.61.52：这两条此前没接（心潮有、App 没拉）
    intent: XinchaoIntent? = null,
    dream: XinchaoDream? = null,
) {
    SettingsGroup("Ta 此刻") {
        if (!loaded) {
            MutedNowLine("正在读取 Ta 此刻的心情…")
            return@SettingsGroup
        }
        val s = state
        if (s == null || s.isEmpty) {
            MutedNowLine("还没有 Ta 的消息 —— 和 Ta 说说话吧")
            return@SettingsGroup
        }
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            // 状态徽标：睡着了 / 醒着 · 心情：平静
            val badge = listOfNotNull(s.wakeText, s.emotionLabel?.let { "心情：$it" }).joinToString(" · ")
            if (badge.isNotBlank()) {
                Text(badge, style = MaterialTheme.typography.labelSmall, color = TextMuted)
                Spacer(Modifier.height(6.dp))
            }
            if (s.text.isNotBlank()) {
                Text(s.text, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
            }
            if (s.drives.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Ta 现在心里最惦记的：",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    s.drives.take(4).forEach { d -> NowPill(d.word) }
                }
            }
            if (!s.recentDream.isNullOrBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "最近的梦：${s.recentDream}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // v0.61.52：当前意图 —— "她现在最想干嘛"（心潮 /v1/intent）
            if (intent != null && intent.word.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "此刻最想：${intent.word}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                )
            }
            // v0.61.52：梦醒后还剩的一点感觉（/v1/breath-context 的 residue）
            // ⚠️ 只显示 residue（"醒来时指尖还留着一点奶油似的黏"），**不显示 summary**
            //    ——后者是"几乎没意识到在做梦"那种元叙述，是写给模型的。
            if (!dream?.residue.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    dream.residue,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun MutedNowLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = TextMuted,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
    )
}

@Composable
private fun NowPill(word: String) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(BrandBlue.copy(alpha = 0.10f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(word, style = MaterialTheme.typography.labelMedium, color = BrandBlueDeep)
    }
}
