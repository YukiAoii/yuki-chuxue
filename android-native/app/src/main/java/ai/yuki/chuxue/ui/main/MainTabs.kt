package ai.yuki.chuxue.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.produceState
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.yuki.chuxue.R
import ai.yuki.chuxue.ui.theme.DangerRose
import ai.yuki.chuxue.ui.theme.TextMuted
import ai.yuki.chuxue.ui.theme.TextPrimary
import ai.yuki.chuxue.ui.theme.TextSubtle
import ai.yuki.chuxue.data.ImageStore
import ai.yuki.chuxue.data.Session
import ai.yuki.chuxue.data.UserProfile
import ai.yuki.chuxue.ui.ChatViewModel
import ai.yuki.chuxue.ui.NewUserWelcomeDialog
import ai.yuki.chuxue.ui.auth.AuthViewModel
import ai.yuki.chuxue.ui.PersonaPickerDialog
import ai.yuki.chuxue.ui.TimeLabels
import ai.yuki.chuxue.ui.components.EmptyHint
import ai.yuki.chuxue.ui.components.ImageSourceButtons
import ai.yuki.chuxue.ui.components.UserAvatar
import ai.yuki.chuxue.ui.components.YukiAvatar
import ai.yuki.chuxue.ui.components.LineDivider
import ai.yuki.chuxue.ui.components.YukiCard
import ai.yuki.chuxue.ui.components.YukiConfirmDeleteDialog
import ai.yuki.chuxue.ui.components.YukiDialog
import ai.yuki.chuxue.ui.icon.YukiIcons
import ai.yuki.chuxue.ui.theme.BrandBlue
import ai.yuki.chuxue.ui.theme.BrandBlueDeep
import ai.yuki.chuxue.ui.theme.BrandBlueSoft
import ai.yuki.chuxue.ui.theme.CardCorner
import ai.yuki.chuxue.ui.theme.CardGap
import ai.yuki.chuxue.ui.theme.FieldFill
import ai.yuki.chuxue.ui.theme.FrostLine
import ai.yuki.chuxue.ui.theme.SkyBlueDeep
import ai.yuki.chuxue.ui.theme.NavSpaceForContent
import ai.yuki.chuxue.ui.theme.SnowWhite
import ai.yuki.chuxue.ui.theme.YukiCardSpec
import androidx.compose.foundation.BorderStroke

/* ═══════════════════════ 消息（文档 §35）═══════════════════════ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageListTab(
    vm: ChatViewModel,
    onOpenChat: (String) -> Unit,
    onOpenSettings: () -> Unit,
    /** 空态里「去创建人设」的动作 —— 由 MainScreen 负责切到「人设」Tab（v0.61.21 美化轮）。 */
    onCreatePersona: () -> Unit = {},
) {
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val personas by vm.personas.collectAsStateWithLifecycle()
    // 未发送的草稿（内存态，见 ChatViewModel.drafts）—— 列表预览要优先显示它（见 Session.previewWith）
    val drafts by vm.drafts.collectAsStateWithLifecycle()
    // ⚠️ v0.61.57：已读水位（QQ 式未读小红点）。**必须 collect** ——
    //    它变化时要让列表行重组，否则红点不会消失（见下面 SessionRow 的注释）。
    val readMarks by vm.readWatermarks.collectAsStateWithLifecycle()
    var showPicker by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Session?>(null) }

    // 新用户引导弹窗（v0.61.21）—— 判据见 `shouldShowNewUserPrompt` 的 KDoc（纯函数，有单测）。
    // ⚠️ 用 `remember` 取**一次**：判定依赖"有没有 key"，而点「创建」后会立刻拉来 key ——
    //    若每次重组都重算，弹窗会在点完「创建」之后**又自己冒出来**。
    var newUserPrompt by remember { mutableStateOf(vm.newUserPromptVisible()) }

    // 列表里的相对时间（今天 / 昨天 / 周三）需要一个「现在」当基准。
    // key 挂在 sessions.size 上：列表一变就重取，免得页面停留久了文案停在旧时刻。
    val now = remember(sessions.size) { System.currentTimeMillis() }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("消息", style = MaterialTheme.typography.headlineSmall) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
                actions = {
                    // ⚠️ 这里原来是一个**裸加号图标**和一个**设置齿轮**，两个都改了：
                    //
                    // ① 加号 → 带文字的「新对话」按钮。
                    //    用户 2026-09-27：「点击之后的选择人设创建的态简约了
                    //    而且用户不会知道这个是干嘛的」。他说得对 —— 一个孤零零的 ＋
                    //    挂在顶栏右侧，没人猜得出它是要开始一段新对话。
                    //    加上两个字，零成本解决。
                    //
                    // ② 齿轮 → **删除**。用户：「再把顶部栏的设置入口按钮删除 太多余了」。
                    //    全局设置的入口已经在「我的」页顶栏右上角（那是它该在的地方），
                    //    这里再来一个就是重复。
                    // ⚠️ v0.61.21 美化：从"裸文字按钮"换成**实心药丸**。
                    //    它是这一页**唯一的主操作**，而裸 TextButton 在浅色顶栏里
                    //    几乎看不出边界 —— 与用户说的"纯白背景下看不到边界"是同一个病。
                    //    只用既有色板（SkyBlueDeep + 白字）与既有圆角语言，不引入新颜色。
                    //    ⚠️ 图标**不再单独指定 tint**：让它跟着 contentColor 走，
                    //       否则就是蓝底上的蓝图标（等于看不见）。
                    TextButton(
                        onClick = { showPicker = true },
                        shape = RoundedCornerShape(50),
                        colors = ButtonDefaults.textButtonColors(
                            containerColor = SkyBlueDeep,
                            contentColor = SnowWhite,
                        ),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        modifier = Modifier.padding(end = 6.dp),
                    ) {
                        Icon(
                            YukiIcons.Add,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "新对话",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (sessions.isEmpty()) {
            // 文案与动作的配对见 `messageEmptyState`（纯函数，有单测）：
            // 没有人设 → 引导去创建；有人设 → 引导开始新对话。
            val empty = messageEmptyState(hasPersonas = personas.isNotEmpty())
            EmptyHint(
                icon = YukiIcons.ChatBubble,
                title = empty.title,
                desc = empty.desc,
                actionLabel = empty.actionLabel,
                onAction = {
                    when (empty.action) {
                        MessageEmptyState.Action.CreatePersona -> onCreatePersona()
                        MessageEmptyState.Action.NewChat -> showPicker = true
                    }
                },
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    // 让出悬浮导航栏：列表能滚到胶囊下方，半透明才有东西可透
                    bottom = 8.dp + NavSpaceForContent,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(sessions, key = { it.id }) { s ->
                    val persona = personas.firstOrNull { it.id == s.personaId }
                    SessionRow(
                        title = persona?.displayName ?: s.title,
                        preview = s.previewWith(drafts[s.id]),
                        timeText = TimeLabels.forList(s.updatedAt, now),
                        avatarPath = persona?.avatarPath,
                        onClick = { onOpenChat(s.id) },
                        // 删除改成长按：常驻的垃圾桶按钮把每一行都变成「待删除」的样子，
                        // 而它其实很少被用到 —— 微信也是这么处理的。
                        onLongClick = { pendingDelete = s },
                        // ⚠️ v0.61.57：QQ 式未读小红点。
                        //    读 `readWatermarks` 让它在**已读水位变化时重组** ——
                        //    只调 `vm.hasUnread(s)` 的话，水位更新不会触发列表重绘
                        //    （红点会赖着不走，直到别的状态变化把这一行带着重画）。
                        unread = readMarks[s.id]?.let { s.updatedAt > it } ?: false,
                    )
                }
            }
        }
    }

    // 新用户引导弹窗（v0.61.21）——放在最前面：它是新用户进来看到的**第一个**东西
    if (newUserPrompt) {
        NewUserWelcomeDialog(
            onCreate = {
                // 「自动拉取模型一键配置好」就是它：拉免费分组 → 设为当前 → 勾上第一个模型。
                // ⚠️ 它**不替用户建人设** —— 用户原话「你直接创建人设就好啦」。
                vm.refreshFreeGroup()
                vm.dismissNewUserPrompt()
                newUserPrompt = false
            },
            onSkip = {
                // 「我自己有服务地址和 API」——只记"弹过了"，**一个配置都不碰**。
                vm.dismissNewUserPrompt()
                newUserPrompt = false
            },
        )
    }

    if (showPicker) {
        PersonaPickerDialog(
            personas = personas,
            onPick = { id ->
                vm.newSession(id)
                showPicker = false
            },
            onDismiss = { showPicker = false },
            onCreatePersona = { showPicker = false },
        )
    }

    // 删除必须二次确认：直接删是一按就没，用户会来不及反应
    pendingDelete?.let { target ->
        YukiConfirmDeleteDialog(
            title = "删除这段对话？",
            message = "「${target.title}」以及里面的全部消息都会被删除，无法恢复。",
            onConfirm = {
                vm.deleteSession(target.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

/**
 * 消息列表「空态」的文案与动作（v0.61.21 · 美化轮）。
 *
 * 抽成纯函数，是为了让"文案与动作必须配对"这条**逻辑**能被单测钉住
 * （见 `MessageEmptyStateTest`）：没有人设时给「开始新对话」是死路（点了没人可选），
 * 有人设时还引导"去创建角色"是绕路。
 */
internal data class MessageEmptyState(
    val title: String,
    val desc: String,
    val actionLabel: String,
    val action: Action,
) {
    enum class Action { CreatePersona, NewChat }
}

internal fun messageEmptyState(hasPersonas: Boolean): MessageEmptyState =
    if (hasPersonas) {
        MessageEmptyState(
            title = "还没有对话",
            desc = "挑一个角色，开始你们的第一段对话",
            actionLabel = "开始新对话",
            action = MessageEmptyState.Action.NewChat,
        )
    } else {
        MessageEmptyState(
            title = "还没有对话",
            desc = "先去「人设」创建一个角色 —— 是谁，由你决定",
            actionLabel = "去创建人设",
            action = MessageEmptyState.Action.CreatePersona,
        )
    }

/**
 * 会话行。
 *
 * ⚠️ **这里刻意不显示缓存命中率**：命中率是开发者指标，普通用户看到「命中 70%」
 * 只会困惑（「什么命中？游戏吗？」），还会挤占列表空间。要看请去「缓存诊断」页
 * （文档 §43）。真机反馈明确指出过这一点。
 */
@Composable
private fun SessionRow(
    title: String,
    preview: String,
    timeText: String,
    avatarPath: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    /**
     * 有没有未读（v0.61.57，QQ 式小红点）。
     *
     * ⚠️ 默认 false —— 其它调用点（预览、测试）不传也照常渲染，不会凭空冒红点。
     */
    unread: Boolean = false,
) {
    YukiCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        onLongClick = onLongClick,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 头像 + 未读小红点（叠在右上角，仿 QQ/微信）
            Box {
                YukiAvatar(size = 44.dp, path = avatarPath)
                if (unread) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 2.dp, y = (-2).dp)
                            .size(10.dp)
                            .background(DangerRose, CircleShape)
                            // 描一圈底色，让它从头像上"浮"起来（不描边会糊进深色头像里）
                            .border(1.5.dp, SnowWhite, CircleShape),
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        // 占满剩余宽度，把时间挤到最右（仿微信）
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = timeText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/* ═══════════════════════ 我的（文档 §39）═══════════════════════ */

/**
 * 「我的」页（开发文档 §39）。
 *
 * ## 与文档的差异（刻意的）
 * §39 的版本围绕**账号**设计：头像来自 `UserEntity.avatarUrl`、下面有「我发布的人设」、
 * Token 卡片里有「节省金额 / 剩余余额」。这些都要后端（§32 认证流程）。
 *
 * 本版是**后端落地前**的形态：头像与昵称改成**纯本地资料**（[UserProfile]），
 * 保留真正算得出来的那几个数字（命中 / 未命中 / 命中率）。
 * 金额类指标**刻意不做** —— 它需要一张可靠的价目表，而价格会变；
 * 与其显示一个可能过期的估算，不如等把单价做成可配置项再算。
 * 完整设计见 `docs/后端与我的页设计_v1.0.md`。
 */
/**
 * 「我的」页（开发文档 §39 + §32.2 的账号展示）。
 *
 * ## 为什么做成"抖音那种我的页"
 * 用户原话：「我的界面模仿抖音的那种我的页面」「不是说要有 uid 吗，
 * 要显示用户头像 uid 昵称等等等等吗」。
 *
 * 那套结构的价值不在好看，而在**层次清楚**：
 *   ① 顶部是**身份**（头像 + 昵称 + UID）—— 一眼知道"这是我"
 *   ② 中间是**数据**（几个数字）—— 一眼知道"我在这里有多少东西"
 *   ③ 下面是**功能**（分组列表）—— 要找的东西永远在固定位置
 *
 * ## 与文档 §39 的四处差异（都有理由）
 * · 「Token 用量」**只留算得出来的**（命中 / 未命中 / 命中率）。
 *   §39 还要「节省金额 / 剩余余额」—— 那需要一张会变的价目表，显示过期估算更糟。
 * · 删掉「我发布的人设」—— 那是市场功能（§38），没上市场前它永远是空列表。
 * · §39 用 emoji（♡ / 👁）展示点赞与浏览 —— 本项目**禁止 emoji**。
 * · 删掉「ID: {uid}」的裸展示，改成「UID 10001」并在编辑资料里可复制。
 *
 * ⚠️ 渲染未经真机验证（本机无设备）。
 */

/**
 * 编辑资料。
 *
 * ⚠️ 昵称与头像的**去向不同**，界面文案要说清：
 * · **昵称**改到服务器（它是账号属性，换设备要跟过去）
 * · **头像**只在本机（本轮没有图片托管 —— 见交付说明；不写成"上传"以免误导）
 *
 * 这与开发文档 §32.2 的隔离设计一致：账号昵称归账号，头像归账号，
 * 而"角色怎么称呼你"是**人设**上的字段（`Persona.userNickname`），与这里无关。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProfileEditDialog(
    currentNickname: String,
    avatarPath: String?,
    onSaveNickname: (String, (String?) -> Unit) -> Unit,
    onSetAvatar: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var nick by remember { mutableStateOf(currentNickname) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    YukiDialog(
        title = "我的资料",
        onConfirm = {
            onSaveNickname(nick) { msg ->
                if (msg == null) onDismiss() else error = msg
            }
        },
        onDismiss = onDismiss,
        confirmText = "确定",
        dismissText = "取消",
    ) {
        Column {
            // ── ① 头像卡片：头像 + 「从相册选择」**同一张卡里**（用户 2026-10-02 指定版式）──
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                UserAvatar(size = 54.dp, path = avatarPath, fallbackText = nick)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("头像", style = MaterialTheme.typography.labelLarge)
                }
                // ⚠️ 只能**重新上传**，不提供"移除" —— 用户 2026-10-02：
                //    「不能移除头像，只能重新上传」。所以这里没有移除按钮，
                //    要换就再选一张（选中会覆盖旧文件）。
                Box {
                    ImageSourceButtons(
                        dir = ImageStore.AVATAR_DIR,
                        namePrefix = "user",
                        maxSide = 512,
                        onPicked = { newPath ->
                            // 换新图前把旧文件删掉，避免私有目录里越积越多
                            ImageStore.delete(avatarPath)
                            onSetAvatar(newPath)
                        },
                        onError = { error = it },
                        onBusyChange = { busy = it },
                        galleryTitle = "从相册选择",
                        galleryDesc = "挑一张新的",
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            if (busy) {
                Text(
                    "正在处理图片…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ── ② 修改昵称 ──
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = nick,
                onValueChange = { nick = it },
                label = { Text("昵称") },
                placeholder = { Text("别人看到你叫什么") },
                singleLine = true,
                isError = nick.length > 20,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            // 用户 2026-10-02：删掉「昵称存在服务器上，换设备登录后还在。」
            // 和「头像只存在这台手机上」—— 这两句是实现细节，不该出现在用户界面里。

            error?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/* ═══════════════════════ 关于 ═══════════════════════
 *
 * ⚠️ 整页已于 v0.50.5 **搬到 `ai.yuki.chuxue.ui.about.AboutScreen.kt`**。
 * 放在这里的那一版（AboutTab / AboutContentColumn / HeroAction / AuthorRow /
 * DonateQr / SectionTitle / AckRow，共约 340 行）全部删除 ——
 * 本文件当时已 1200+ 行、装着四个 Tab，继续膨胀只会越来越难改。
 *
 * 新实现按用户要求重做了整页（Hero 扫光 / 可展开功能列表 / 作者卡实时动效 /
 * 赞助卡差异化 / Tianshu-harness 致谢改名）。理由写在那边文件的头注释里。
 */


/* ═══════════════════════ 复用小组件 ═══════════════════════ */


@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        YukiCard(modifier = Modifier.fillMaxWidth()) {
            content()
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun NavRow(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    /**
     * 一句"点进去能做什么"（v0.54.0 加）。
     *
     * ⚠️ 只有标题的入口列表，要用户**自己回忆**每项是什么 —— 而「数据备份」
     * 这种不常点的地方，回忆不起来就只能一个个点进去看，那是最没效率的探索方式。
     */
    desc: String = "",
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (desc.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    desc,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Icon(YukiIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}
