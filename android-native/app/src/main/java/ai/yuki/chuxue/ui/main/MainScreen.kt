package ai.yuki.chuxue.ui.main

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import ai.yuki.chuxue.ui.ChatViewModel
import ai.yuki.chuxue.ui.about.AboutTab
import ai.yuki.chuxue.ui.auth.AuthViewModel
import ai.yuki.chuxue.ui.components.FlatBottomBar
import ai.yuki.chuxue.ui.PersonaScreen
import ai.yuki.chuxue.ui.components.liquidglass.BottomTabItem
import ai.yuki.chuxue.ui.icon.YukiIcons
import ai.yuki.chuxue.ui.theme.FlatBackground
import ai.yuki.chuxue.ui.theme.YukiDuration
import ai.yuki.chuxue.ui.theme.YukiMotion

/**
 * 主界面框架（开发文档 §34）。
 *
 * 五个 Tab（消息 / 人设 / 市场 / 我的 / 关于，§28.2）**不走导航库**，
 * 用 state 切换 —— 文档 §34.2 与 §46.1 都明确要求：主界面内切 Tab 不该进回退栈。
 *
 * 底部导航栏是**悬浮**的（§45.1「胶囊悬浮在内容之上」），所以内容区要主动
 * 让出底部空间，否则列表最后一项会被玻璃遮住。
 */
@Composable
fun MainScreen(
    vm: ChatViewModel,
    authVm: AuthViewModel,
    onOpenChat: (String) -> Unit,
    onOpenSettings: () -> Unit,
    /** 直达「数据备份」（v0.54.0：「我的」页新增的入口） */
    onOpenBackup: () -> Unit,
    /** 用户登出 → 回登录页（由 MainActivity 清栈） */
    onLoggedOut: () -> Unit,
    /** 打开「全部对话的看板」 */
    onOpenGlobalBoard: () -> Unit,
    /** 「我的」页的反馈入口（v0.57.0；路由由 MainActivity 负责） */
    onOpenFeedback: () -> Unit = {},
    /** 未登录时「我的」页的按需登录入口（v0.60.0；路由由 MainActivity 负责） */
    onOpenLogin: () -> Unit = {},
    /** 老用户补绑邮箱（透传给「我的」页） */
    onOpenBindEmail: () -> Unit = {},
    /** 人设长按菜单里的「记忆库」→ 打开那一段会话的记忆页（路由由 MainActivity 负责） */
    onOpenMemory: (String) -> Unit,
    /** 点人设卡 → 人设详情页（v0.56.0；路由由 MainActivity 负责） */
    onOpenPersonaDetail: (String) -> Unit,
    /** 人设编辑页的「专属表情包」→ 那个人设的专属表情包库（v0.57.0；路由由 MainActivity 负责） */
    onOpenPersonaEmoji: (String) -> Unit = {},
    /** 「关于」页的「检查更新」（v0.50.0）。启动那次检查是静默的，这是看得见的入口。 */
    onCheckUpdate: () -> Unit = {},
    /**
     * 从别处（如设置页的「人设与角色」）**请求切到的 Tab 下标**；`null` = 没有请求。
     *
     * ⚠️ 为什么需要它：设置页是**独立路由**，点「人设与角色」若只 `popBackStack` 回主界面，
     * Tab 会停在**进设置之前那一个**（从「我的」进去 → 回来还是「我的」）。
     * 用户 2026-09-30 报的「点人设与角色入口跳回我的界面」正是这条。
     */
    requestedTab: Int? = null,
    /** Tab 请求已消费 —— 调用方据此清空，否则每次重组都会再切一次。 */
    onRequestedTabConsumed: () -> Unit = {},
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }

    // 外部请求切 Tab（一次性）。key 用 requestedTab：同一个值再次请求也能触发。
    LaunchedEffect(requestedTab) {
        if (requestedTab != null) {
            tab = requestedTab
            onRequestedTabConsumed()
        }
    }
    val density = LocalDensity.current

    val tabs = remember {
        listOf(
            BottomTabItem("消息", YukiIcons.ChatBubble, YukiIcons.ChatBubble),
            BottomTabItem("人设", YukiIcons.Person, YukiIcons.Person),
            BottomTabItem("我的", YukiIcons.AccountCircle, YukiIcons.AccountCircle),
            BottomTabItem("关于", YukiIcons.Info, YukiIcons.Info),
        )
    }

    // 扁平风的背景是**纯色**：不再有渐变色斑与水彩噪点。
    // 背景只负责「干净」，层次交给卡片的阴影 —— 这也是它比水彩更稳的原因：
    // 不依赖透明度叠加，就不会出现「浅底上白元素隐形」那类问题。
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(FlatBackground),
    ) {
        AnimatedContent(
            targetState = tab,
            transitionSpec = {
                // §30.3 Tab 切换：淡入淡出 + **轻微**横向位移。
                // 用固定 8dp（文档值），不用「宽度 / 12」—— 后者在 360dp 屏上约 27dp，
                // 位移过大，观感会从「轻移」变成「整页横推」。
                val shift = with(density) { YukiMotion.TabShiftDp.dp.roundToPx() }
                // ⚠️ 离场刻意**比进场快得多**（TabLeave < TabSwitch）：
                //    动画期间新旧两棵子树同时存在，两个重页面叠着 = 掉帧。
                //    缩短离场就是缩短"双份布局 + 双份绘制"的窗口（用户报的"切换卡顿"）。
                // ⚠️ 离场也**不再做横向位移** —— 旧页面正在消失，给它加位移只是多一次
                //    布局与绘制，观感上几乎看不出来。进场保留位移（那个看得见）。
                (fadeIn(tween(YukiDuration.TabSwitch)) +
                    slideInHorizontally(tween(YukiDuration.TabSwitch)) { shift }) togetherWith
                    fadeOut(tween(YukiDuration.TabLeave))
            },
            label = "tab_content",
            // ⚠️ 这里**不能**再给 bottom padding（v0.37.1 修）。
            //
            // 原先写的是 `.padding(bottom = 92.dp)`，本意是"别让内容被胶囊遮住"，
            // 实际后果是**内容在胶囊上方就结束了** —— 胶囊下方那 92dp 里什么都没有，
            // 只剩页面背景色（`#F5F7FA`，浅到发光）。用户看到的是"底部一块白色区域把效果挡住了"。
            //
            // 而它同时造成了另一个现象：**胶囊看起来"不透明"**。
            // 因为透过半透明胶囊看到的只有一层纯浅色，跟胶囊自己几乎同色 ——
            // 既看不出"透"，也谈不上模糊（模糊一块纯色还是纯色）。
            //
            // 正确做法：内容区**占满全屏**（这样列表能滚到胶囊下面，半透明才有东西可透），
            // 而"让出底部空间"交给**各个 Tab 自己的滚动容器**去做 contentPadding ——
            // 那是"让出"，不是"截断"。
            modifier = Modifier.fillMaxSize(),
        ) { index ->
            when (index) {
                0 -> MessageListTab(
                    vm = vm,
                    onOpenChat = onOpenChat,
                    onOpenSettings = onOpenSettings,
                    // 空态里的「去创建人设」→ 切到「人设」Tab（切 Tab 是本页 state，不进导航栈）
                    onCreatePersona = { tab = 1 },
                )
                1 -> PersonaScreen(
                    vm = vm,
                    onBack = null,
                    // 「开始新对话」：建一段新会话 → 直接打开聊天页（跳过详情页）。
                    // ⚠️ `newSession` 会**同步**把新会话设为 active，所以紧接着读到的
                    //    `activeSessionId` 一定是它，不会是上一段。
                    onStartChat = { personaId ->
                        vm.newSession(personaId)
                        vm.activeSessionId.value.takeIf { it.isNotBlank() }?.let(onOpenChat)
                    },
                    // 「记忆库」：记忆是**按会话**存的（不是按人设），
                    // 所以这里先挑出该人设最近的一段，再把那段交给上层去路由。
                    onOpenMemory = { personaId ->
                        vm.sessions.value
                            .filter { it.personaId == personaId }
                            .maxByOrNull { it.updatedAt }
                            ?.let { onOpenMemory(it.id) }
                    },
                    // 点一下人设卡 → 进详情（v0.56.0）。以前这里是直接进编辑界面。
                    onOpenDetail = onOpenPersonaDetail,
                    // 编辑页里的「专属表情包」→ 那个人设的专属库（v0.57.0）
                    onOpenPersonaEmoji = onOpenPersonaEmoji,
                )
                2 -> ProfileTab(
                    vm = vm,
                    authVm = authVm,
                    onOpenSettings = onOpenSettings,
                    // v0.54.0：「我的」页新增「数据备份」入口 —— 导出/备份功能都在那一页
                    onOpenBackup = onOpenBackup,
                    onLoggedOut = onLoggedOut,
                    onOpenGlobalBoard = onOpenGlobalBoard,
                    // v0.57.0：「说点什么」反馈入口
                    onOpenFeedback = onOpenFeedback,
                    // v0.60.0：未登录时的按需登录入口
                    onOpenLogin = onOpenLogin,
                    onOpenBindEmail = onOpenBindEmail,
                )
                else -> AboutTab(onCheckUpdate = onCheckUpdate)
            }
        }

        FlatBottomBar(
            tabs = tabs,
            selectedIndex = tab,
            onTabSelected = { tab = it },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}
