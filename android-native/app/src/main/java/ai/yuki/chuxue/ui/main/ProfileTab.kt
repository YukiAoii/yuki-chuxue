package ai.yuki.chuxue.ui.main

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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.yuki.chuxue.ui.auth.AuthViewModel
import ai.yuki.chuxue.ui.ChatViewModel
import ai.yuki.chuxue.ui.theme.NavSpaceForContent
import ai.yuki.chuxue.ui.components.UserAvatar
import ai.yuki.chuxue.ui.components.YukiConfirmDeleteDialog
import ai.yuki.chuxue.ui.icon.YukiIcons
import ai.yuki.chuxue.ui.theme.BrandBlue
import ai.yuki.chuxue.ui.theme.BrandBlueDeep
import ai.yuki.chuxue.ui.theme.SnowWhite
import ai.yuki.chuxue.ui.theme.TextMuted
import ai.yuki.chuxue.ui.theme.TextPrimary

/**
 * 「我的」页（v0.55.0 **推翻重做**）。
 *
 * ## 这一版换了什么
 * 上一版是"一张张白卡片竖着排"，读起来像设置项清单。这一版按**信息层级**重排：
 * 1. **身份** —— 一张带**光晕与弧光**的品牌渐变卡（视觉焦点，回答"这是谁"）；
 * 2. **数据仪表** —— 四格数字带图标底盘（回答"我攒了多少"）；
 * 3. **入口** —— 三行进阶入口，每行一个**彩色图标底盘** + 一句"点进去能做什么"；
 * 4. **账号** —— 退出登录（唯一破坏性操作，刻意压低存在感）。
 *
 * ## ⚠️ 视觉上的三处刻意选择
 * - **光晕用 Canvas 画**而不是放图片：图片要进 APK、要适配深浅色，而两个半透明圆
 *   在任何屏幕上都不会糊、也不会拖慢首帧。
 * - **图标底盘用品牌色的低透明度**而不是实色：实色图标在浅色卡片上会"跳"出来跟正文抢注意力。
 * - **Token 用量卡已删**（用户要求：那类数字都在「全部对话的看板」看）——
 *   这一页的定位是"我这个人"，不是"我的账本"。
 *
 * ⚠️ 渲染未经真机验证（本机无设备）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileTab(
    vm: ChatViewModel,
    authVm: AuthViewModel,
    onOpenSettings: () -> Unit,
    /** 直达「数据备份」（v0.54.0 起从这里直达 —— 导出/备份功能都在那儿） */
    onOpenBackup: () -> Unit,
    onLoggedOut: () -> Unit,
    /** 打开「全部对话的看板」（v0.46.0） */
    onOpenGlobalBoard: () -> Unit,
    /** 打开「说点什么」（反馈，v0.57.0）—— 后端 `POST /api/feedback`，与官网表单同源 */
    onOpenFeedback: () -> Unit,
    /**
     * 未登录时点头像卡上那个胶囊 → 去登录（v0.60.0）。
     * 选择页只在首次打开出现，之后要靠这个入口按需进来。
     */
    onOpenLogin: () -> Unit = {},
    /** 老用户补绑邮箱（只在「已登录 + 邮箱为空」时有入口） */
    onOpenBindEmail: () -> Unit = {},
) {
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val personas by vm.personas.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val auth by authVm.auth.collectAsStateWithLifecycle()
    val stale by authVm.staleSession.collectAsStateWithLifecycle()

    var editing by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }

    val messageCount = sessions.sumOf { it.messages.size }
    // ⚠️ 记忆条数要查库 —— 但它只是这一页的一格数字，用 produceState 异步取，
    //    取不到就是 0（这一页不因为一个统计读不出来而打不开）。
    val memoryCount by androidx.compose.runtime.produceState(initialValue = -1) {
        value = vm.memoryCountOrNegative()
    }

    Scaffold(
        // 透明底：背景由 MainScreen 的根 Box 提供。涂上 SnowWhite 会差一点点色，切 Tab 时能看出接缝。
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("我的", style = MaterialTheme.typography.headlineSmall) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
                // ⚠️ 顶栏这里**不能**再放设置齿轮（v0.42.0 删过）：同功能两个入口
                //    会让人怀疑它们是不是两件事。设置现在只有下面「全部设置」那一个入口。
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                // 让出悬浮导航栏（是留白，不是截断内容区）
                .padding(bottom = NavSpaceForContent)
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(8.dp))

            /* ── ① 身份：品牌渐变 + 光晕 + 弧光 ── */
            ProfileHero(
                displayName = auth.displayName,
                uid = auth.uid,
                avatarPath = profile.avatarPath,
                onEdit = { editing = true },
                onOpenLogin = onOpenLogin,
            )

            /* ── 老用户补绑邮箱（用户 2026-10-02）──
             *
             * 判据：**登录态在、但邮箱为空** —— 只有早期版本注册的人会命中
             * （现在的注册第一段就强制验证邮箱，新用户走不到这里）。
             * 绑上之后这一行**自动消失**，所以它是个"临时入口"。
             */
            // 用户 2026-10-02 追加：「目前没有换绑邮箱入口和界面」——
            // 所以这一行**两种状态都显示**：
            //   未绑 → 「绑定邮箱」（补绑）   已绑 → 「换绑邮箱」（把旧的换成新的）
            // 只有没登录时才不出现。
            if (auth.isLoggedIn) {
                val bound = auth.email.orEmpty()
                val rebind = bound.isNotBlank()
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(BrandBlue.copy(alpha = 0.10f))
                        .clickable(onClick = onOpenBindEmail)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = if (rebind) "换绑邮箱" else "绑定邮箱",
                            style = MaterialTheme.typography.labelLarge,
                            color = TextPrimary,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = if (rebind) {
                                "当前：$bound"
                            } else {
                                "没绑邮箱的话，忘记密码就找不回来了。"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                        )
                    }
                    Text(
                        text = if (rebind) "去换绑 ›" else "去绑定 ›",
                        style = MaterialTheme.typography.labelMedium,
                        color = BrandBlue,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            /* ── 登录可能过期（**不踢人**，见 AuthViewModel）── */
            if (stale) {
                Spacer(Modifier.height(12.dp))
                SoftNotice(
                    title = "登录可能已过期",
                    body = "连不上服务器，或登录凭证已失效。你仍然可以正常聊天 —— " +
                        "对话、人设、记忆都在本机。换设备或改昵称时才需要重新登录。",
                )
            }

            Spacer(Modifier.height(18.dp))

            /* ── ② 数据仪表：四格 ── */
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StatTile("对话", "${sessions.size}", YukiIcons.ChatBubble, Modifier.weight(1f))
                StatTile("消息", "$messageCount", YukiIcons.Send, Modifier.weight(1f))
                StatTile("人设", "${personas.size}", YukiIcons.Person, Modifier.weight(1f))
                StatTile(
                    label = "记忆",
                    // -1 = 还在查；显示 "—" 而不是 0（0 会被读成"一条都没有"）
                    value = if (memoryCount < 0) "—" else "$memoryCount",
                    icon = YukiIcons.Book,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(18.dp))

            /* ── ③ 入口：三行，各带彩色图标底盘 ── */
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)),
            ) {
                EntryRow(
                    title = "全部对话的看板",
                    desc = "聊了多少、缓存命中怎么样、钱省在哪儿",
                    icon = YukiIcons.Conversations,
                    tint = BrandBlue,
                    onClick = onOpenGlobalBoard,
                )
                EntryRow(
                    title = "数据备份",
                    desc = "导出聊天记录 / 人设 / 记忆 / 模型配置；也有自动备份",
                    icon = YukiIcons.Book,
                    tint = Color(0xFF2FA37C),
                    onClick = onOpenBackup,
                )
                EntryRow(
                    // v0.57.0：反馈入口（用户要求「「我的」加反馈入口，对接后端」）。
                    // ⚠️ 放在「全部设置」之前：它是**一次性的动作**，不是又一个设置入口 ——
                    //    排在"设置"上面才不会又被当成一个要进去翻的地方。
                    title = "说点什么",
                    desc = "用着别扭、缺了什么，或者只是想夸一句",
                    icon = YukiIcons.Send,
                    tint = Color(0xFFD08A3E),
                    onClick = onOpenFeedback,
                )
                EntryRow(
                    title = "全部设置",
                    desc = "连接、功能、关于",
                    icon = YukiIcons.Settings,
                    tint = Color(0xFF7C6BD6),
                    onClick = onOpenSettings,
                    last = true,
                )
            }

            Spacer(Modifier.height(18.dp))

            /* ── ④ 账号：唯一的破坏性操作，刻意压低存在感 ── */
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { confirmLogout = true }
                        .padding(horizontal = 16.dp, vertical = 15.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        YukiIcons.Close,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "退出登录",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "数据留在本机",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (editing) {
        ProfileEditDialog(
            currentNickname = auth.nickname,
            avatarPath = profile.avatarPath,
            onSaveNickname = { nick, done -> authVm.updateNickname(nick, done) },
            onSetAvatar = { path ->
                vm.saveProfile { it.copy(avatarPath = path) }
                // 本地立即生效；同时异步传一份到服务器（市场里要靠它显示作者头像）。
                // ⚠️ notifyOnFailure = true：**用户显式挑头像这一次必须说话** ——
                //    静默失败正是"市场里看不到作者头像"藏了这么久的原因。
                vm.uploadAvatar(path, notifyOnFailure = true)
            },
            onDismiss = { editing = false },
        )
    }

    if (confirmLogout) {
        YukiConfirmDeleteDialog(
            title = "退出登录？",
            message = "退出后需要重新登录才能换设备与改昵称。" +
                "你的对话、人设、记忆和 API Key 都留在本机，不会被删除。",
            confirmText = "退出",
            onConfirm = {
                confirmLogout = false
                authVm.logout()
                onLoggedOut()
            },
            onDismiss = { confirmLogout = false },
        )
    }
}

/* ─────────────── ① 身份卡 ─────────────── */

/**
 * 品牌渐变身份卡。
 *
 * ⚠️ 光晕是 **Canvas 画的**（两个半透明圆），不是图片：
 * 图片要进 APK、要适配深浅色、还要担心拉伸；两个圆在任何分辨率下都不糊。
 */
@Composable
private fun ProfileHero(
    displayName: String,
    uid: String,
    avatarPath: String?,
    onEdit: () -> Unit,
    /** 未登录时点那个胶囊 → 去登录（v0.60.0 的按需入口） */
    onOpenLogin: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Brush.linearGradient(listOf(BrandBlue, BrandBlueDeep))),
    ) {
        // 光晕：右上一个小亮斑、左下一个大暗斑 —— 让纯色渐变有"空间感"
        Canvas(Modifier.matchParentSize()) {
            drawCircle(
                color = Color.White.copy(alpha = 0.16f),
                radius = size.minDimension * 0.42f,
                center = Offset(size.width * 0.86f, size.height * 0.12f),
            )
            drawCircle(
                color = Color.Black.copy(alpha = 0.10f),
                radius = size.minDimension * 0.55f,
                center = Offset(size.width * 0.08f, size.height * 1.04f),
            )
            // 一道弧光：把"卡片"和"纯色块"区分开
            drawArc(
                color = Color.White.copy(alpha = 0.10f),
                startAngle = 200f,
                sweepAngle = 110f,
                useCenter = false,
                topLeft = Offset(-size.width * 0.15f, -size.height * 0.55f),
                size = Size(size.width * 1.3f, size.height * 1.5f),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 18f),
            )
        }

        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 头像外一圈白环：放在渐变上时，没有环的头像会和背景糊在一起
            Box(
                Modifier
                    .size(74.dp)
                    .clip(CircleShape)
                    .background(SnowWhite.copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center,
            ) {
                UserAvatar(
                    size = 66.dp,
                    path = avatarPath,
                    fallbackText = displayName,
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleLarge,
                    color = SnowWhite,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // v0.60.0：未登录时这个胶囊就是**按需登录入口** ——
                    // 那个「首次打开才出现」的选择页看过之后就不会再自动弹了，
                    // 想登录得有个稳定的地方进来，就是这里（用户要求"按需做"）。
                    if (uid.isNotBlank()) {
                        Pill("UID $uid")
                    } else {
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(50))
                                .background(SnowWhite.copy(alpha = 0.28f))
                                .clickable(onClick = onOpenLogin)
                                .padding(horizontal = 11.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = "未登录，去登录",
                                style = MaterialTheme.typography.labelSmall,
                                color = SnowWhite,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(SnowWhite.copy(alpha = 0.20f))
                    .clickable(onClick = onEdit)
                    .padding(horizontal = 15.dp, vertical = 8.dp),
            ) {
                Text(
                    text = "编辑",
                    style = MaterialTheme.typography.labelMedium,
                    color = SnowWhite,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/** 渐变卡上的半透明白胶囊。 */
@Composable
private fun Pill(text: String) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(SnowWhite.copy(alpha = 0.20f))
            .padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = SnowWhite)
    }
}

/* ─────────────── ② 数据格 ─────────────── */

/**
 * 一格数字。
 *
 * ⚠️ 图标放在**低透明度的品牌色底**上，而不是实色：
 * 实色小图标在浅色卡片上会"跳"出来跟数字抢注意力，而这里数字才是主角。
 */
@Composable
private fun StatTile(
    label: String,
    value: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(BrandBlue.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = BrandBlue,
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextMuted)
    }
}

/* ─────────────── ③ 入口行 ─────────────── */

/**
 * 一行入口：**彩色图标底盘** + 标题 + 一句"点进去能做什么" + 箭头。
 *
 * ⚠️ 每行换一个色调（蓝 / 绿 / 紫）：三个入口长得一模一样时，
 * 用户只能靠读文字来区分；颜色让他**扫一眼**就能定位。
 */
@Composable
private fun EntryRow(
    title: String,
    desc: String,
    icon: ImageVector,
    tint: Color,
    onClick: () -> Unit,
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
                Text(title, style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
                if (desc.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(desc, style = MaterialTheme.typography.labelSmall, color = TextMuted)
                }
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

/* ─────────────── 小件 ─────────────── */

/** 一句不刺眼的提示（用于"登录可能过期"这类不需要打断的事）。 */
@Composable
private fun SoftNotice(title: String, body: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f))
            .padding(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            YukiIcons.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(18.dp).offset(y = 2.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                body,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f),
            )
        }
    }
}
