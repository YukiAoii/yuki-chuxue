package ai.yuki.chuxue.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.animation.animateContentSize
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.yuki.chuxue.data.Activity
import ai.yuki.chuxue.data.AppNotice
import ai.yuki.chuxue.data.PromptEngine
// v0.61.54：切换模型提示要显示**用户看到的名字**（免费分组下后端会改 label），
// 走 `ProviderGroups.labelOf` —— 与浮层里显示的同一个口径。
import ai.yuki.chuxue.data.ProviderGroups
import ai.yuki.chuxue.data.ContextCompress
import ai.yuki.chuxue.data.ChatMessage
import ai.yuki.chuxue.data.ImagePolicy
import ai.yuki.chuxue.data.Persona
import ai.yuki.chuxue.data.SCRIM_PLAIN
import ai.yuki.chuxue.data.SEND_MODE_INSTANT
// v0.61.57：主动消息（Ta 主动找你）在渲染上要能区分 —— 见 MessageBubble 里的标识
import ai.yuki.chuxue.data.SEND_MODE_PROACTIVE
import ai.yuki.chuxue.data.SEND_MODE_STREAM
import ai.yuki.chuxue.data.Session
import ai.yuki.chuxue.data.TYPE_SPEED_NORMAL
import ai.yuki.chuxue.data.TYPE_SPEED_OFF
import ai.yuki.chuxue.data.MarkdownLite
import ai.yuki.chuxue.data.MessageEdits
import ai.yuki.chuxue.ui.components.ChatBackgroundSurface
import ai.yuki.chuxue.ui.components.YukiAvatar
import ai.yuki.chuxue.data.COMPRESS_MODE_ASK
import ai.yuki.chuxue.data.COMPRESS_MODE_AUTO
import ai.yuki.chuxue.data.COMPRESS_MODE_MANUAL
import ai.yuki.chuxue.data.EmojiCategories
import ai.yuki.chuxue.data.formatHitPercent
import ai.yuki.chuxue.data.ImageStore
import ai.yuki.chuxue.ui.components.RoundIconButton
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ai.yuki.chuxue.ui.components.TranslucentPanel
import ai.yuki.chuxue.ui.components.VoiceInputButton
import ai.yuki.chuxue.ui.components.YukiDialog
import ai.yuki.chuxue.ui.theme.FieldFill
import ai.yuki.chuxue.ui.theme.YukiCardSpec
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import ai.yuki.chuxue.data.TranscriptText
import ai.yuki.chuxue.ui.icon.YukiIcons
import ai.yuki.chuxue.ui.theme.DangerRose
import ai.yuki.chuxue.ui.theme.DangerRoseBg
import ai.yuki.chuxue.ui.theme.FrostLine
import ai.yuki.chuxue.ui.theme.IceCyanSoft
import ai.yuki.chuxue.ui.theme.InputPanelCorner
import ai.yuki.chuxue.ui.theme.PANEL_ALPHA
import ai.yuki.chuxue.ui.theme.SnowSurface
import ai.yuki.chuxue.ui.theme.SnowWhite
import ai.yuki.chuxue.ui.theme.SkyBlue
import ai.yuki.chuxue.ui.theme.BrandBlue
import ai.yuki.chuxue.ui.theme.SkyBlueDeep
import ai.yuki.chuxue.ui.theme.SuccessMint
import ai.yuki.chuxue.ui.theme.TextMuted
import ai.yuki.chuxue.ui.theme.TextPrimary
import ai.yuki.chuxue.ui.theme.TextSecondary
import ai.yuki.chuxue.ui.theme.WarnAmber
import ai.yuki.chuxue.ui.theme.WarnAmberBg
import ai.yuki.chuxue.ui.theme.YukiDuration
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ai.yuki.chuxue.data.DateIndex
import ai.yuki.chuxue.data.MessageSearch
import ai.yuki.chuxue.data.SearchHit
import ai.yuki.chuxue.data.SearchScope
import ai.yuki.chuxue.data.UserProfile
import ai.yuki.chuxue.ui.chat.AttachMenuSheet
import ai.yuki.chuxue.ui.chat.SendHistorySheet
import ai.yuki.chuxue.ui.chat.ModelPickerSheet
import ai.yuki.chuxue.ui.components.Island
import ai.yuki.chuxue.ui.components.UserAvatar
import androidx.compose.ui.text.style.TextOverflow
import ai.yuki.chuxue.ui.theme.BrandBlueSoft
import ai.yuki.chuxue.ui.theme.DEFAULT_SCRIM_ALPHA
import ai.yuki.chuxue.ui.theme.assistantBubbleShape
import ai.yuki.chuxue.ui.theme.userBubbleShape
import kotlinx.coroutines.launch

/**
 * 聊天输入框最多长到几行。
 *
 * 到顶之后 `BasicTextField` 自己**内部滚动**，面板高度不再增加 ——
 * 这样它既不会被长文本撑满屏幕，也不会把上面的消息列表顶掉。
 * 5 行 ≈ 120dp，和微信/QQ 的手感接近。
 * 用户 2026-10-02：「默认 1 行，随内容增高，到上限后输入框内部滚动」。
 */
private const val INPUT_MAX_LINES = 5


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    vm: ChatViewModel,
    onOpenSettings: () -> Unit,
    /** 打开**这段对话**的设置页（右上角齿轮）—— 不是全局设置 */
    onOpenChatSettings: (String) -> Unit,
    onOpenPersonas: () -> Unit,
    onBack: (() -> Unit)? = null,
) {
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val activeId by vm.activeSessionId.collectAsStateWithLifecycle()
    val personas by vm.personas.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    // 请求失败的对话化表达（H4）—— 它是**界面态**，不写 messages（见 ChatViewModel.errorLine）
    val errorLine by vm.errorLine.collectAsStateWithLifecycle()
    val loadWarning by vm.loadWarning.collectAsStateWithLifecycle()
    val pending by vm.pendingImages.collectAsStateWithLifecycle()
    val imageBusy by vm.imageBusy.collectAsStateWithLifecycle()
    val streaming by vm.streamingText.collectAsStateWithLifecycle()
    // 每条消息旁显示的她：人设名 + 人设头像（没设头像时组件回落自绘雪晶）
    val personaName = vm.activePersona()?.displayName.orEmpty()
    // 「我」的本地资料：头像与昵称 —— 聊天气泡右侧、搜索结果都要用
    val profile by vm.profile.collectAsStateWithLifecycle()
    // 连接分组（v0.51.0）：下面算 `effective` 要用 —— 密钥与模型都住在分组里
    val groups by vm.groups.collectAsStateWithLifecycle()
    val activeGroupId by vm.activeGroupId.collectAsStateWithLifecycle()
    // 流式中的思考过程（与正文分两个流，UI 才能把思考单独折起来）
    val streamingReasoning by vm.streamingReasoning.collectAsStateWithLifecycle()
    // 「哪条回复配了哪张表情包」—— 内存状态，不进历史（见 ChatViewModel.emojiByMessage）
    val emojiByMessage by vm.emojiByMessage.collectAsStateWithLifecycle()
    // 「正在输入…」：请求已发出、首字未到（最像卡死的那一刻）
    val typing by vm.typing.collectAsStateWithLifecycle()
    // 「她好像卡住了…」：流式期间连续 10 秒没有新内容（见 ChatViewModel.stalled）
    val stalled by vm.stalled.collectAsStateWithLifecycle()
    // 「Ta 正在做的事」——后台还在跑什么（回你 / 记事 / 整理）
    val activities by vm.activities.collectAsStateWithLifecycle()
    // 「我发过的话」——「+」菜单里的第三项（v0.61.21）
    val sendHistory by vm.sendHistory.collectAsStateWithLifecycle()

    // 人设云同步：进聊天页时拉一次（若本会话还没成功拉过）。
    // ⚠️ v0.61.21：不能只在启动时拉 —— 密钥是**登录那一刻**才从明文密码派生出来的，
    //    而启动时通常还没登录/没密钥，那次必然空跑。换设备登录这条路上，
    //    少了这一句就等于**一次都没拉过**，本地一直是空的，
    //    随后任何一次本地变更都会把云端人设覆盖成近乎空。
    LaunchedEffect(Unit) { vm.syncPersonasIfNeeded() }

    // 人设的 AI 简介：进聊天页时补一次（每次最多两个，生成过就永不重复 —— 见 ensureDetailSummaries）
    LaunchedEffect(Unit) { vm.ensureDetailSummaries() }
    /** 「该压缩了」标志（v0.48.0）。`ask` 模式下由 ViewModel 置位，**不弹模态框**。 */
    val compressSuggested by vm.compressSuggested.collectAsStateWithLifecycle()
    /** 压缩完成的系统提示（v0.48.0）。内存态，**不写 messages**（那会污染请求前缀）。 */
    val compressNotice by vm.compressNotice.collectAsStateWithLifecycle()

    // 长按菜单里的「复制」
    val clipboard = LocalClipboardManager.current
    // 会话内搜索：是否展开由 ViewModel 持有（对话设置页也要能打开它），
    // 关键词与过滤口径则在搜索层内部 —— 关掉就该忘掉。
    val searchOpen by vm.searchOpen.collectAsStateWithLifecycle()
    val pendingScroll by vm.pendingScrollTo.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val active = sessions.firstOrNull { it.id == activeId }
    val persona = active?.let { p -> personas.firstOrNull { it.id == p.personaId } }

    /**
     * 这段对话**实际会用**的那一套值（v0.51.0）。
     *
     * ⚠️ 界面里凡是回答"我在用哪个模型 / 配没配密钥 / 上下文多大"的地方，
     *    读的都必须是它 —— 不是全局 `settings`。分组界面启用之后密钥与模型落在
     *    **分组**里，全局那两个字段对新用户**恒为空串 / 恒是默认模型**：
     *    读全局会把顶栏写成"deepseek-flash"而实际在用别的服务商，
     *    也会把"已经配好了"读成"没配"（空状态催用户去填一个填过的东西）。
     *
     * ⚠️ 取值逻辑与发消息/摘要/记忆/余额**同一份实现**
     *（`ChatViewModel.effectiveSettings` → `ProviderGroups.effective`）。
     */
    // ⚠️ 是 `=` 而不是 `by`：`remember { AppSettings }` 返回的是**值本身**，
    //    不是 `State` —— `by` 只对 `mutableStateOf` / `collectAsStateWithLifecycle` 那类成立。
    val effective = remember(settings, active, activeId, groups, activeGroupId) {
        vm.effectiveSettings(active)
    }

    // 草稿：住在 ViewModel 里、按会话索引。
    //
    // 上一版是 `rememberSaveable`，注释写着"离开聊天页再回来还在"。那句话只对了一半：
    // 它的状态挂在导航返回栈的**那一个 entry** 上 —— 进设置页再返回保得住，
    // 但**退出到主界面再进聊天页**时 entry 已销毁，草稿随之消失（用户报的正是这个）。
    // 根因是状态归属错了层：草稿属于**会话**，不属于某个界面实例。
    val drafts by vm.drafts.collectAsStateWithLifecycle()
    val draftKey = active?.id.orEmpty()
    val input = drafts[draftKey].orEmpty()
    // 长按菜单作用于哪一条消息（下标）；null = 没打开
    var menuFor by remember { mutableStateOf<Int?>(null) }
    // 被长按的那一枚气泡的文本（v0.61.5）—— 复制按**枚**走；null = 整条（用户自己的消息）
    var menuBubbleText by remember { mutableStateOf<String?>(null) }
    // 待确认删除的那条（她的回复的**绝对**下标）；null = 没有待确认的删除。
    // ⚠️ 用户要求删除必须**二次确认**（v0.61.14）—— 删的是一整轮，不可撤销。
    var pendingDelete by remember { mutableStateOf<Int?>(null) }
    // 正在预览的图片（data URL）；null = 没开预览（v0.61.14，用户要求图片可点开看）
    var previewImage by remember { mutableStateOf<String?>(null) }

    // ⚠️ **切会话时把这些"带下标的临时状态"清掉**（v0.61.14，编译前自审抓到的）：
    //    `pendingDelete` 存的是**绝对下标**，而它只在"当时那个会话"里有意义 ——
    //    弹着确认框去切会话，再点「删除」就会删到**另一个会话**的那一条（真实事故）。
    //    `menuFor` / `menuBubbleText` / `previewImage` 同理：它们都属于"刚才那段对话"。
    LaunchedEffect(activeId) {
        pendingDelete = null
        previewImage = null
        menuFor = null
        menuBubbleText = null
    }
    var showNewSessionPicker by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // 系统照片选择器（**多选**，最多 ImagePolicy.MAX_IMAGES 张）：不需要任何存储权限
    // ⚠️ 用 PickMultipleVisualMedia 而不是 PickVisualMedia —— 后者只会带回一张，
    //    用户点完多张也只有第一张有反应。它要求 maxItems ≥ 2。
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(ImagePolicy.MAX_IMAGES),
    ) { uris: List<Uri> ->
        vm.addImages(context, uris)
    }

    // 滚动跟随。
    //
    // ⚠️ 「进入对话」这一帧必须**瞬时定位**，不能走动画。
    //    原来的写法无条件 `animateScrollToItem(last)`：LazyColumn 首帧从索引 0（顶部）
    //    开始布局，紧接着动画滑到底 —— 用户看到的就是「列表自己从最上方滚下来」。
    //    微信的行为是**进入即停在最新一条**，中间没有这个过程。
    //    所以首帧用 `scrollToItem`（无动画），之后新消息 / 流式增量才用 `animateScrollToItem`。
    //
    //    `key = activeId`：切换会话时也要回到"瞬时定位"，同样不该看到滚动过程。
    var firstScrollDone by remember(activeId) { mutableStateOf(false) }

    /**
     * 键盘当前占掉的高度（0 = 没弹起）。
     *
     * ⚠️ 用**高度**而不是"有没有弹起"这个布尔（v0.44.3 修）：键盘弹起是一段约 250ms 的
     * 动画，期间列表可用高度一直在变。只盯布尔翻转的话，滚动会在布局**落定之前**就执行完，
     * 结果正是用户报的"有时跟得上、有时差一点"（概率不跟随）。
     */
    val imeHeight = WindowInsets.ime.getBottom(LocalDensity.current)

    // 这一段对话渲染多少条（往上翻会变大）。
    // ⚠️ 它只管**渲染**：发给模型的仍是 `Session.messages` 全量 —— 见 `ChatWindow` 的类注释。
    var loaded by remember(activeId) { mutableIntStateOf(ChatWindow.PAGE) }

    // ⚠️ 分页之后"长度变了"不再等于"她说了新话"（往上加载更早的记录**也会**让长度变大）。
    // 所以记下上一次的总数，把两者分开判 —— 不分的话，用户每翻一次旧记录就被拽回底部。
    val totalCount = active?.messages?.size ?: 0
    var prevTotal by remember(activeId) { mutableIntStateOf(totalCount) }

    /**
     * **实时**读一次"用户是否在底部"。
     *
     * 为什么不用上面那个 `nearBottom` 状态：它是个 `derivedStateOf`，
     * 在组合期取值。而 `onLoaded` 是**图片解码之后**才回调的 ——
     * 那时用户可能已经滑走了，用组合期的旧快照会误判成"还在底部"，
     * 于是照样把他拽回去。所以这里在**调用那一刻**重新读。
     */
    val atBottomNow: () -> Boolean = {
        val info = listState.layoutInfo
        val total = info.totalItemsCount
        total == 0 || (info.visibleItemsInfo.lastOrNull()?.index ?: -1) >= total - 2
    }

    // 「用户此刻离底部有多远」—— 任何自动跟随之前都必须先问它。
    // ⚠️ 少了这一维，"列表变长"就等于"她想让我看最新一条"，
    //    于是用户一往上翻历史就被拽回底部（用户报的正是这个）。
    val nearBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val total = info.totalItemsCount
            total == 0 || (info.visibleItemsInfo.lastOrNull()?.index ?: -1) >= total - 2
        }
    }

    // 「这一次必须让他看见」—— 自己发消息时置起，被消费一次即清。
    var forceFollow by remember(activeId) { mutableStateOf(false) }

    /**
     * 用户的手指是不是正**按着**列表（v0.61.7）。
     *
     * 用户原话：「不和用户做竞争 —— AI 在输出、用户这个时候滑动了屏幕或者点击了一下，
     * 就不跟随了」。流式跟随每几十毫秒就可能执行一次，所以这一维必须在**按下**那一刻
     * 就生效（不能等滚动真的开始）。
     *
     * ⚠️ 它只**观察**指针事件（`PointerEventPass.Initial`、不消费），
     *    LazyColumn 自己的滚动手势一个字都没改。
     */
    var userTouchingList by remember { mutableStateOf(false) }

    LaunchedEffect(totalCount, streaming != null, activeId, loaded) {
        val appended = ChatWindow.shouldFollowToBottom(prevTotal, totalCount, appendedAtEnd = true)
        prevTotal = totalCount
        // ⚠️ **必须是窗口内的下标**（v0.45.3 修崩溃）。
        // 列表只渲染 `visible` 项，而这里原来用的是全量 `lastIndex` ——
        // 消息一超过 50 条就滚到列表外面，抛 IndexOutOfBounds → **一进会话就崩**。
        // 用户报的"0.45.2 点开 AI 会话软件崩溃"就是这个：
        // v0.45.0 的分页改了列表长度，却没把这里的下标口径一起改过来。
        val visible = ChatWindow.visibleCount(totalCount, loaded)
        val last = (visible - 1) + (if (streaming != null) 1 else 0)
        if (last < 0) return@LaunchedEffect

        // ⚠️ 判据收在纯函数里，**键盘不再是它的输入**。
        //    原来是内联的 `imeHeight == 0` 合取项：只要键盘弹着，那道"不跟随"
        //    的闸门就失效，往上加载更早、甚至键盘动画让 imeHeight 抖一下，
        //    都会被无条件滚到底 —— 用户报的"无法翻阅历史"就是这条。
        val follow = ChatWindow.decideFollowToBottom(
            firstScrollDone = firstScrollDone,
            appendedAtEnd = appended,
            streaming = streaming != null,
            nearBottom = nearBottom,
            forced = forceFollow,
        )
        forceFollow = false          // 一次性信号：消费掉，别留给下一次
        if (!follow) return@LaunchedEffect

        runCatching {
            if (firstScrollDone) listState.animateScrollToItem(last) else listState.scrollToItem(last)
        }
        firstScrollDone = true
    }

    // ── 流式跟随（v0.61.7）：AI 输出时把最新内容带进视野 ──
    //
    // ⚠️ key 必须是**内容长度**，不能是"有没有流式气泡"：流式**过程中**后者不变，
    //    effect 根本不会被唤起 —— 这就是「AI 输出不跟随」的根因
    //（判据一直躺在 `ChatWindow` 里，却没有任何时机去问它）。
    //    思考内容的增长同样算"她在输出"，所以两条都进 key。
    // ⚠️ 用 `scrollToItem`（瞬时）而不是 animate：每块增量都会来一次，
    //    动画互相打断/排队，观感反而像卡顿；瞬时贴底才是"内容往上顶"的跟随感。
    // ⚠️ 用户意图优先：手指按着 / 正在滚动 / 已翻离底部 → 一律让位
    //（判据见 `ChatWindow.shouldFollowStreaming`，与 [decideFollowToBottom] 同源纪律）。
    LaunchedEffect(streaming?.length, streamingReasoning?.length) {
        if (streaming == null && streamingReasoning == null) return@LaunchedEffect
        val follow = ChatWindow.shouldFollowStreaming(
            nearBottom = nearBottom,
            userTouching = userTouchingList,
            scrollInProgress = listState.isScrollInProgress,
        )
        if (!follow) return@LaunchedEffect
        runCatching {
            listState.scrollToItem((listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
        }
    }

    // 键盘弹起时的画面吸附（v0.44.3 的原意；v0.50.x 收紧了它的触发面）。
    //
    // ⚠️ 这段原来长在上面那个 effect 里、判据是 `imeHeight > 0`，于是：
    //    ① 它跟着 `imeHeight` 这个**原始像素值**当 key —— 键盘动画期间每帧都在变，
    //       等于每抖一次就重跑、每跑一次就滚到底；
    //    ② 它绕开了"用户在不在底部"的判断。
    //    现在改成：只在**键盘开合翻转**的那一刻触发一次，且人本来就在底部才吸。
    val imeOpen = imeHeight > 0
    LaunchedEffect(imeOpen) {
        if (!imeOpen || !nearBottom) return@LaunchedEffect
        delay(320)   // 等键盘那段动画跑完、布局落定，再吸附一次
        val visible = ChatWindow.visibleCount(totalCount, loaded)
        val last = (visible - 1) + (if (streaming != null) 1 else 0)
        if (last >= 0) runCatching { listState.animateScrollToItem(last) }
    }

    // 搜索命中 → 滚到那一条，并短暂强调，否则用户只知道"跳过去了"却找不到。
    //
    // ⚠️ 与上面那条"自动滚到底"的冲突在这里被拆开：跳转**不改变消息数**，
    //    所以那个 effect 不会被唤起；而这条请求被消费一次即清空（`consumeScrollRequest`），
    //    重组不会反复把列表拽回去。
    var flashed by remember { mutableStateOf<Int?>(null) }

    // H3：打断的二次确认。状态**声明在这里**而不是交给下面那个窗口 ——
    // 输入区的「停止」钮要先把标志置起来，而局部变量必须先声明后使用。
    var confirmInterrupt by remember { mutableStateOf(false) }

    /* ── 附件菜单与模型选择（v0.51.0）──
     * 「+」不再**直接**弹相册：它现在是"我要做点什么"的入口（发图 / 换模型）。
     * 用户原话：「勾选的模型在聊天会话窗口的输入框**加号**点击之后可以选择调用
     * 这个分组下勾选了的哪个模型」。
     */
    var showAttachMenu by remember { mutableStateOf(false) }
    /** 「发过的话」抽屉（v0.61.21，⑤-A 第 7 条）。 */
    var showHistory by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }
    /** 上下文弹窗开着没有（v0.45.0） */
    var showContextSheet by remember { mutableStateOf(false) }
    // 回复一结束，确认窗**自己消失**（用户明确要求：她都说完了还问"要不要打断"很荒唐）
    LaunchedEffect(busy) { if (!busy) confirmInterrupt = false }

    LaunchedEffect(pendingScroll) {
        val target = pendingScroll ?: return@LaunchedEffect
        // ⚠️ 搜索跳转用的是**全量索引**（搜索是在全部消息里找的），而列表现在只渲染窗口 ——
        // 窗口不够大就跳不到那条上（表现是"点了搜索结果，跳到了别的地方"）。
        // 用户主动跳转，多渲染一点完全可以接受，所以这里直接把窗口放开。
        loaded = Int.MAX_VALUE
        runCatching { listState.animateScrollToItem(target) }
        vm.consumeScrollRequest()
        flashed = target
        delay(1600)
        flashed = null
    }

    // ⚠️ 这三条**不再走灵动岛**（v0.61.21 —— 反转了上一轮的做法）。
    //
    // 上一轮把它们从"输入框上方的常驻条（要点「知道了」才消失）"改成"顶部浮出的胶囊"，
    // 理由是"临时提示统一走灵动岛"。用户 2026-10-05 又提出来：**重要提醒不该一闪就没** ——
    // 没看清就消失了，也没法回头再看。现在它们画进**对话流**里（见下面的 `AppNoticeCard`），
    // 带一个关闭按钮，用户自己决定什么时候关。
    //
    // 业务逻辑一个字没改：状态仍归 ViewModel 持有，这里只是换了呈现方式；
    // 关闭时调它自己的 `dismissXxx()`（那三个函数本来就有，是给"常驻条"那版用的）。
    val appNotice = remember(error, loadWarning, notice) {
        AppNotice.of(error, loadWarning, notice)
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            SessionDrawer(
                sessions = sessions,
                personas = personas,
                drafts = drafts,
                activeId = activeId,
                onSelect = { id -> vm.switchSession(id); scope.launch { drawerState.close() } },
                onDelete = vm::deleteSession,
                onNew = { scope.launch { drawerState.close() }; showNewSessionPicker = true },
                onOpenPersonas = { scope.launch { drawerState.close() }; onOpenPersonas() },
            )
        },
    ) {
        // ⚠️ v0.61.26：整幅内容的**录制层** —— 顶栏拿它做高斯模糊底（毛玻璃）。
        //    Compose 1.7 起 `rememberGraphicsLayer` 可用（本项目 BOM 2024.10.01）。
        val contentLayer = rememberGraphicsLayer()

        Scaffold(
            // v0.61.26：顶栏改「毛玻璃」观感 —— 理由见下面 TopAppBar 的注释。
            // 页面本身透明，让 ChatBackgroundSurface 铺满整屏（顶栏后面也是它）。
            containerColor = Color.Transparent,
            topBar = {
                // ⚠️ v0.61.26「毛玻璃」：顶栏现在**盖在内容之上**，把它下方的**所有内容**
                //    （背景 + 滚过顶栏底下的消息）做高斯模糊，再叠一层半透明白提升可读性。
                //    用户原话：「模糊底部的所有内容」。
                //
                // 实现：内容层绘制时把整幅画面**录进 `contentLayer`**（见下面内容 Box 的
                // `drawWithContent`），这里在顶栏背后重绘那份录制、并用 `Modifier.blur` 雾化它。
                // ⚠️ `Modifier.blur` 在 **Android 12 以下自动降级**（no-op，不崩）——
                //    那些机型看到的是"半透明顶栏 + 内容透出"，仍是可用的观感。
                Box(Modifier.fillMaxWidth()) {
                    Box(
                        Modifier
                            .matchParentSize()
                            .clipToBounds()
                            // ⚠️ v0.61.32（用户：「我要的是能看到文字轮廓且不清晰的」）：
                            //    半径 **32 → 10dp** —— 本轮最关键的一个数。
                            //    40dp 高的顶栏上，32dp 的模糊**足以把一切糊成纯色**，
                            //    于是"什么都看不见"，用户感觉就是"不透 / 像块白板"。
                            //    10dp 才是 iOS 毛玻璃那个量级（≈ CSS `blur(10px)`）：
                            //    文字化成**看不清的可辨色块、轮廓仍在**；滚动时还能看出它在动
                            //    （每帧重放内容层，所以"跟随刷新"是天然的）。
                            .blur(10.dp)
                            .drawWithContent { drawLayer(contentLayer) },
                    )
                    // ⚠️ v0.61.57：**可调的顶栏白底**（用户要求「可以设置聊天界面顶部栏的透明度」）。
                    //    改之前顶栏**没有独立底色** —— 只有模糊层，文字直接压在模糊内容上，
                    //    遇到花哨背景会读不清。这一层就是为可读性加的白，浓度由用户调：
                    //      · 0   → 几乎全透（只看得到模糊内容，字可能难认）；
                    //      · 1   → 接近不透明（等于把毛玻璃关掉）。
                    //    ⚠️ 默认 0.72 不是 1.0：用户当初要的就是"能看到文字轮廓且不清晰的"
                    //      那种观感（见上面 v0.61.32 的注释），全不透等于把这个功能关了。
                    //    ⚠️ 它**盖在模糊层之上**（顺序：先模糊、再铺白）—— 反过来白底也会被糊掉。
                    Box(
                        Modifier
                            .matchParentSize()
                            .background(Color.White.copy(alpha = settings.topBarAlpha.coerceIn(0f, 1f))),
                    )
                    // ⚠️ v0.61.29（用户问题 1/2/3/5）：**不再用 TopAppBar 的三个槽位** ——
                    //    槽位的左右内边距由 material3 写死，"返回 ↔ 标题"与"按钮 ↔ 按钮"
                    //    的间距没法对齐（问题 3）；标题区也被槽位的高度规则推得不居中（问题 2）。
                    //    改成自己排一行 Row：间距、对齐、尺寸全部可控。
                    // ⚠️ v0.61.31（用户：「顶部栏角色名是在居中的位置啊」）：
                    //    容器从 Row 改成 **Box** —— 标题要用 `align(Center)` 做**屏幕绝对居中**，
                    //    而不是夹在左右控件之间（右边是"三合一胶囊"、左边只有一个返回键，
                    //    夹在中间排必然偏）。左右控件各自 `align(CenterStart / CenterEnd)`。
                    Box(
                        Modifier
                            .fillMaxWidth()
                            // 原来由 TopAppBar 代劳的状态栏留白，自绘要自己补
                            .statusBarsPadding()
                            .height(60.dp)
                            .padding(horizontal = 12.dp),
                    ) {
                        // 左侧：返回（圆形磨砂 + 透明立体）—— 靠左锚定
                        Row(Modifier.align(Alignment.CenterStart)) {
                        if (onBack != null) {
                            FrostIconButton(
                                onClick = onBack,
                                contentLayer = contentLayer,
                                contentDescription = "返回",
                                icon = YukiIcons.Back,
                            )
                        } else {
                            FrostIconButton(
                                onClick = { scope.launch { drawerState.open() } },
                                contentLayer = contentLayer,
                                contentDescription = "对话列表",
                                icon = YukiIcons.Conversations,
                            )
                        }
                        }
                        // ⚠️ v0.61.31（用户：「顶部栏角色名是在居中的位置啊」）：
                        //    标题**屏幕绝对居中**（`align(Center)`），不再夹在左右控件之间 ——
                        //    右边是"三合一胶囊"、左边只有一个返回键，夹在中间排一定偏。
                        Column(
                            Modifier
                                .align(Alignment.Center)
                                // 给长名字留出两侧控件的位置，避免压到按钮上
                                .padding(horizontal = 98.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = persona?.displayName ?: "还没有对话",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            // ⚠️ v0.61.21：这一行原来显示**模型名**。
                            //    用户要求改成「Ta 正在做的事」—— 模型名从顶栏撤掉
                            //（它在「+ → 选择模型」里看得见；而且它是"设置"，
                            //  不是"她此刻在干什么"，两件事挤在一行里互相拖累）。
                            //    搬到顶栏的理由：**这件事说的是"现在"**，
                            //    而放在消息流里会随着消息滚走 —— 正在发生的事该固定住。
                            ActivityLine(activities)
                        }
                        // 右侧：三个功能键（同一块胶囊磨砂 + 透明立体）—— 靠右锚定
                        Row(Modifier.align(Alignment.CenterEnd)) {
                        // ── 上下文占用（v0.45.1 重排）──
                        // ⚠️ 它显示的是**估算**（真实 token 要请求回来才知道），弹窗里写明了。
                        // v0.48.0：改用 estimateSentContext（把摘要算进去），
                        // 与弹窗里那条进度条、以及触发判定**同一个口径** ——
                        // 三处各算各的就会出现"环说 80%、弹窗说 45%"。
                        // ⚠️ 用**记忆上下文**（不是 API 上下文）：这条环说的是"离压缩还有多远"，
                        //    必须与压缩判定（`evaluateCompressTrigger`）同口径 ——
                        //    否则又会出现"环说 80%、弹窗说 45%"那种三处各算各的。
                        val ctxLimit = ContextCompress.contextLimit(
                            effective.model,
                            vm.memoryWindowForSession(active),
                        )
                        val ctxUsed = ContextCompress.estimateSentContext(
                            frozenPrefix = persona?.let {
                                PromptEngine.buildFrozenPrefix(settings, it, vm.userPersonaFor(it))
                            }.orEmpty(),
                            messages = active?.messages.orEmpty(),
                            summary = active?.summary,
                            coveredCount = active?.summaryCount ?: 0,
                        )
                        val ctxRatio =
                            if (ctxLimit > 0) (ctxUsed.toFloat() / ctxLimit) else 0f
                        // 三个功能键共用**一块**胶囊磨砂底板（用户：「三个功能按钮是在一块的胶囊啊」）
                        FrostPillGroup(contentLayer = contentLayer) {
                            FrostPillItem(
                                onClick = { showContextSheet = true },
                                contentDescription = "上下文占用",
                            ) { ContextRing(ratio = ctxRatio) }
                            // 查找聊天记录：与对话设置页的那一行是同一个入口。
                            // 顶栏留一个，是因为"翻找某句话"远比"改这段对话的设置"高频 ——
                            // 都塞进设置页会让常用动作变成两步。
                            FrostPillItem(
                                onClick = { if (active != null) vm.openSearch() },
                                contentDescription = "查找聊天记录",
                                icon = YukiIcons.Search,
                            )
                            // ⚠️ 「新建对话」**从顶栏删掉**了（v0.45.1 重排）。
                            // 理由：抽屉里本来就有「＋ 新建对话」，而顶栏是**这一段对话**的地盘 ——
                            // 在每段对话的门口再放一扇"开新对话"的门，只会让顶栏挤、
                            // 也让"我现在在哪"变得模糊。四个图标里它是最该走的那个。
                            //
                            // ⚠️ 这个齿轮**必须留着** —— 它是「对话设置」的**唯一入口**
                            // （免打扰 / 置顶 / 聊天背景 / 删除记录都在那一页）。
                            FrostPillItem(
                                onClick = { active?.let { onOpenChatSettings(it.id) } },
                                contentDescription = "这段对话的设置",
                                icon = YukiIcons.Settings,
                            )
                        }
                        }
                    }
                }
            },
        ) { padding ->
            // ⚠️ `imePadding` 放在**这一层**（v0.44.1 修）：
            // 原先只有输入层自己带 `imePadding()` —— 键盘弹起时上移的只有输入框，
            // **消息列表原地不动**，于是最新的几条被键盘盖住，用户得手动往上滑才能看见。
            // 提到共同父级之后，"列表 + 输入层"作为一个整体让出键盘空间，
            // 自动滚到底的那套逻辑自然就把最新消息**吸附到键盘上沿**（微信/QQ 的手感）。
            // ⚠️ v0.61.26：`padding` 的 **top 置 0** —— 内容必须能**穿过顶栏**，
            //    否则"顶栏下方"什么都没有，模糊无从谈起（这是毛玻璃的必要前提）。
            //    底部仍照旧让出（输入框 / 导航条）。
            Box(
                Modifier
                    .fillMaxSize()
                    .imePadding()
                    .padding(
                        PaddingValues(top = 0.dp, bottom = padding.calculateBottomPadding()),
                    )
                    // ⚠️ 把整幅画面**录一份**给顶栏当模糊底（见 topBar 里那个 blur 层）。
                    .drawWithContent {
                        contentLayer.record { this@drawWithContent.drawContent() }
                        drawLayer(contentLayer)
                    },
            ) {
                // 聊天背景（对话设置页可选）。`background = null` 走默认纯色，
                // 与全局背景同值 —— 存量会话因此是**零变化**路径：
                // 加这个功能之前截的图和之后逐像素相同。
                //
                // ⚠️ v0.61.57：**两层回落**（用户要求「设置全局自定义背景」）。
                //    会话设过 → 用会话的；没设过 → 用**全局美化页**的值；全局也没设 → 内置默认。
                //    `?:` 链就是这条回落，与 `globalPrefixEnabled`（通用设定）同一套思路。
                ChatBackgroundSurface(
                    background = active?.background ?: settings.background,
                    scrimEnabled = active?.scrimEnabled ?: settings.scrimEnabled,
                    scrimAlpha = active?.scrimAlpha ?: settings.scrimAlpha,
                    scrimStyle = active?.scrimStyle ?: settings.scrimStyle,
                )

                // ⚠️ 界面只渲染**窗口**里的那些条（`ChatWindow` 里解释了为什么不能拿它当历史）。
                // 想让聊天记录"少渲染几条"是**性能**诉求；而 `active.messages` 本身仍是全量，
                // 所以搜索、预览、发给模型的内容都不受这里影响。
                val allMessages = active?.messages.orEmpty()
                val msgs = remember(allMessages, loaded) {
                    val n = ChatWindow.visibleCount(allMessages.size, loaded)
                    if (n >= allMessages.size) allMessages else allMessages.takeLast(n)
                }
                // 表情包重写（v0.61.23）：列表按**行**渲染 —— 每条消息一行，
                // 带表情包的消息后面多发一行（表情包独立测量，不再把消息行撑高）。
                val rows = remember(msgs) { buildChatRows(msgs) }

                /** 还有更早的记录没放出来 —— 顶部那条加载指示器据此显示/隐藏 */
                // ⚠️ 判据走 `ChatWindow` 的纯函数（v0.50.5）：界面与单测引用同一个定义
                val hasEarlier = ChatWindow.showEarlierIndicator(allMessages.size, loaded)

                /**
                 * 「正在加载更早的记录」——只在**手动**触发的那 1 秒里为真（v0.51.0）。
                 *
                 * 自动加载（滚到顶）不置它：那条路径是用户自己滑出来的，
                 * 指示条已经在屏幕上，再把它变成"加载中"只会多一次闪烁。
                 */
                var loadingEarlier by remember(activeId) { mutableStateOf(false) }
                if (msgs.isEmpty() && streaming == null && errorLine == null) {
                    EmptyState(
                        // ⚠️ 判据是**实际会用**的密钥（分组优先）—— 看全局字段会把
                        //    "密钥已经在分组里填好了"读成"没配"，然后催用户去填第二遍。
                        configured = effective.apiKey.isNotBlank(),
                        hasPersona = personas.isNotEmpty(),
                        personaCount = personas.size,
                        onCreate = { showNewSessionPicker = true },
                        onOpenPersonas = onOpenPersonas,
                        onOpenSettings = onOpenSettings,
                    )
                } else {
                    // 滑到最顶 → 再放一批更早的记录出来。
                    // ⚠️ 必须放在 LazyColumn **外面**：`LazyColumn { }` 的 lambda 是
                    // `LazyListScope`（不是 @Composable），`remember` / `LaunchedEffect`
                    // 只能待在组合上下文里。
                    // ⚠️ 放完要按"前面多了多少条"补偿滚动位置，否则画面会往下跳一屏
                    //（用户明明没动、内容却突然窜一屏，是分页最容易被骂的坑）。
                    val atTop by remember {
                        // 加载条自己占第 0 项，所以"在顶部"是 index ≤ 1
                        derivedStateOf { listState.firstVisibleItemIndex <= 1 }
                    }
                    // ── 「再放一批更早的记录」**只有这一份实现**（v0.51.0）──
                    // 自动（滚到顶）与手动（点一下指示条）都走它。两份实现迟早会长歪，
                    // 而它俩必须产出**同一个锚点补偿** —— 否则"点一下"与"滑到顶"
                    // 会把画面停在不同位置（那正是历史上前几轮栽过的坑）。
                    val loadEarlierNow: suspend () -> Unit = {
                        val anchorBefore = listState.firstVisibleItemIndex
                        // ⚠️ 不能用"loaded 前后"相减：`msgs` 是派生值，effect 里读到的是
                        // 启动那一刻的旧值。这里直接算纯函数，得到"这次前面会多出多少条"。
                        val added = ChatWindow.visibleCount(allMessages.size, loaded + ChatWindow.PAGE) -
                            ChatWindow.visibleCount(allMessages.size, loaded)
                        // ⚠️ 头部那个「加载更早」指示器是**条件存在**的，所以锚点补偿
                        // 必须把它的增减算进去 —— 否则加载到"全部放出来了"那一刻，
                        // 指示器消失、后面每条位置 -1，画面会偏一条（用户报的"往回弹的迹象"）。
                        val headerBefore = if (hasEarlier) 1 else 0
                        val newLoaded = loaded + ChatWindow.PAGE
                        val headerAfter =
                            if (allMessages.size > ChatWindow.visibleCount(allMessages.size, newLoaded)) 1 else 0
                        loaded = newLoaded
                        delay(32) // 等一帧让新窗口真正生效，再把锚点放回去
                        runCatching {
                            listState.scrollToItem(
                                ChatWindow.positionAfterPrepend(
                                    firstVisiblePositionBefore = anchorBefore,
                                    headerBefore = headerBefore,
                                    addedBefore = added,
                                    headerAfter = headerAfter,
                                ),
                            )
                        }
                    }
                    LaunchedEffect(atTop, hasEarlier) {
                        if (atTop && hasEarlier) loadEarlierNow()
                    }

                    // 「现在」用来把时间戳渲染成「今天 14:30 / 昨天 14:30」这类文案。
                    // key 挂在消息数上：新消息一到就重取基准，避免文案停在旧时刻。
                    val now = remember(msgs.size) { System.currentTimeMillis() }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            // 手指按下/抬起 → 记进 `userTouchingList`（流式跟随的先决条件）。
                            // ⚠️ Initial pass 只**观察**、不消费 —— 滚动手势一个字没改。
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val e = awaitPointerEvent(PointerEventPass.Initial)
                                        userTouchingList = e.changes.any { it.pressed }
                                    }
                                }
                            },
                        // 底部留出悬浮输入区的高度 —— 否则最后一条消息会被输入区压住。
                        // 具体数值见 [INPUT_LAYER_RESERVED_HEIGHT] 的注释（用户嫌 148 太空）
                        // ⚠️ v0.61.27：`top` 必须**加上顶栏高度**（`padding.calculateTopPadding()`）。
                        //    内容 Box 那边把 `padding.top` 置 0 了（毛玻璃需要内容**穿过**顶栏），
                        //    于是列表从 y=0 起 —— 首条消息正好被顶栏压住（用户报的 bug）。
                        //    把顶栏高度补进**列表自己的 contentPadding**：
                        //    起始位置落在顶栏下方 ✓，而滚动时内容照样从顶栏底下穿过（毛玻璃照旧）。
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 16.dp + padding.calculateTopPadding(),
                            bottom = INPUT_LAYER_RESERVED_HEIGHT,
                        ),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        // ── 更早的记录 ──
                        // ⚠️ 这里只能写 `item(...)`：`LazyColumn { }` 的 lambda 属于
                        // `LazyListScope`，**不是** @Composable 上下文 —— `remember` /
                        // `LaunchedEffect` 搁进来会报 "can only happen from a @Composable function"。
                        // 所以触发加载的那段逻辑放在 LazyColumn **外面**。
                        if (hasEarlier) {
                            item(key = "loading-earlier") {
                                LoadingEarlierRow(
                                    // 手动兜底（v0.51.0）：自动加载依赖"滚到顶"这件事
                                    // 被布局信息判出来，而极端情况下它可能判不出来 ——
                                    // 给一条**能点**的路，用户就永远拿得到更早的记录。
                                    onLoadEarlier = {
                                        if (!loadingEarlier) {
                                            loadingEarlier = true
                                            scope.launch {
                                                try {
                                                    // ⚠️ 用户要求「下滑刷新 1 秒出更多旧内容」。
                                                    //    本地数据是**瞬时**的 —— 不加这一下，
                                                    //    指示条闪一下就没，用户看不到任何反馈，
                                                    //    会以为"点了没反应"。
                                                    //    这是**最短展示时长**，不是固定延迟：
                                                    //    真正耗时的部分（磁盘 / 查询）本来就超过
                                                    //    1 秒时，它不会再往上叠加等待。
                                                    delay(EARLIER_LOADING_MIN_MS)
                                                    loadEarlierNow()
                                                } finally {
                                                    loadingEarlier = false
                                                }
                                            }
                                        }
                                    },
                                    loading = loadingEarlier,
                                )
                            }
                        }
                        // ⚠️ **必须有稳定 key**（2026-09-29）。
                        //    没有它时 LazyColumn 只能按**位置**认 item，而顶部那个
                        //    「加载更早」指示器是条件存在的 —— 它一消失，后面每条消息的
                        //    位置整体 -1，列表就认为"每一条都换了" →
                        //    全部重新组合（滑动卡顿）+ 滚动位置错乱（概率性回弹）。
                        //    用**绝对下标**做 key：本项目 messages 只追加不改写，
                        //    所以这个下标永不变（见 ChatWindow.absoluteMessageIndex）。
                        items(
                            items = rows,
                            key = { row ->
                                // 行 key：消息用 "msg-"、它的表情包用 "emoji-"，都挂**绝对下标**
                                //（下标永不变，见 ChatWindow.absoluteMessageIndex）
                                val abs = ChatWindow.absoluteMessageIndex(
                                    total = allMessages.size,
                                    windowSize = msgs.size,
                                    indexInWindow = row.msgIndex,
                                )
                                if (row.isEmoji) "emoji-$abs" else "msg-$abs"
                            },
                        ) { row ->
                            // ⚠️ 每行要么是一条消息、要么是"跟在消息后面的表情包"（见 ChatWindow.ChatRow）。
                            //    表情包独立成行之后，图解码只撑高它自己那一行 —— 消息行纹丝不动。
                            val msg = msgs.getOrNull(row.msgIndex)
                            if (row.isEmoji && msg != null) {
                                // 表情包**独立成行**（v0.61.23，用户 2026-10-05）：
                                // 左侧是她的头像（36 + 8 = 44dp，与消息列的「头像 + 间距」同宽 →
                                // 图气泡与她的文字气泡**左边缘对齐**），右边一枚固定上限的图气泡。
                                EmojiRow(
                                    path = msg.emojiPath.orEmpty(),
                                    avatarPath = persona?.avatarPath,
                                    onLoaded = {
                                        // ⚠️ 与原来的口径一致：先问"用户此刻在不在底部"，
                                        //    在底部才贴底（往上翻历史时不把人拽回来）。
                                        if (ChatWindow.shouldStickAfterGrow(
                                                nearBottom = atBottomNow(),
                                                imeOpen = imeHeight > 0,
                                            )
                                        ) {
                                            scope.launch {
                                                runCatching {
                                                    listState.animateScrollToItem(
                                                        (listState.layoutInfo.totalItemsCount - 1)
                                                            .coerceAtLeast(0),
                                                    )
                                                }
                                            }
                                        }
                                    },
                                )
                            } else if (msg != null) {
                            // 下面是原来的消息行渲染（`index` / `msg` 的语义与旧代码完全一致）
                            val index = row.msgIndex
                            // ⚠️ **窗口下标 → 绝对下标**（v0.61.13 修）：
                            //    列表只渲染最近 N 条（`msgs = allMessages.takeLast(n)`），而
                            //    菜单、搜索高亮、删除/重生成的判据**全都按全量 messages 定位**。
                            //    把窗口下标当全量用会取到**另一条**消息 —— 用户报的
                            //    「长按最新一条没有删除和重新生成」就是它：窗口下标永远不等于
                            //    全量的 `lastIndex`，所以那两个菜单项恒不出现。
                            val absIndex = ChatWindow.absoluteMessageIndex(
                                total = allMessages.size,
                                windowSize = msgs.size,
                                indexInWindow = index,
                            )
                            // ── 分页边界分割线（v0.51.0）──
                            // 用户：「每次下滑到分页加载的地方的时候做个分割线」。
                            // 它只在**窗口第一条**的上方出现，且只在"上面还有更早的"时候 ——
                            // 也就是把"你翻到这一页的边界了"这件事**画出来**。
                            // ⚠️ 它顺带回答了另一个疑问「为什么看不到更多了」：
                            //    再往上就是当前这一页的尽头，加载更早的记录会接着接上。
                            if (index == 0 && hasEarlier) {
                                EarlierBoundaryLine()
                            }
                            // 时间分割条（仿微信）：只在间隔够久时插，
                            // 连续对话不插 —— 否则聊天记录会被切得支离破碎
                            //
                            // ⚠️ 文案收进 `remember`（v0.50.5）：`forDivider` 每次都要构造
                            //    Instant / ZonedDateTime / LocalDate 再格式化，此前每帧重算。
                            val prevAt = msgs.getOrNull(index - 1)?.createdAt ?: 0L
                            val dividerText = remember(prevAt, msg.createdAt, now) {
                                if (TimeLabels.needsDivider(prevAt, msg.createdAt)) {
                                    TimeLabels.forDivider(msg.createdAt, now)
                                } else {
                                    null
                                }
                            }
                            if (dividerText != null) {
                                TimeDivider(dividerText)
                            }
                            MessageBubble(
                                msg = msg,
                                autoCollapse = settings.thinkingCollapseEnabled,
                                personaName = personaName,
                                personaAvatar = persona?.avatarPath,
                                userProfile = profile,
                                // 搜索刚跳过来的那一条：短暂加一圈主色，帮用户找到它
                                highlighted = flashed == absIndex,
                                // ⚠️ 用**这条消息自己**的呈现方式（v0.45.3），不是当前的全局设置。
                                // 老消息没有这个字段（null）→ 归一为流式（normalizeSendMode）。
                                // ⚠️ v0.61.4：流式消息（含老消息）统一按「分段气泡」设置拆枚 ——
                                //    这是渲染层观感，消息内容与请求体一个字节不动。
                                sendMode = msg.sendMode ?: SEND_MODE_STREAM,
                                // v0.61.57：气泡不透明度走全局美化设置（默认 1f = 零变化）
                                bubbleAlpha = settings.bubbleAlpha,
                                // 展开思考气泡后滚到底（v0.61.5，用户要求）
                                onReasoningExpand = {
                                    scope.launch {
                                        runCatching {
                                            listState.animateScrollToItem(
                                                (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0),
                                            )
                                        }
                                    }
                                },
                                // 点图片标签 → 看大图（v0.61.14）
                                onImagePreview = { previewImage = it },
                                // 长按出菜单（复制 / 删除 / 重新生成）。
                                // ⚠️ 参数是**被按的那一枚气泡**的文本（v0.61.5）—— 复制按枚走
                                onLongPress = { bubbleText ->
                                    menuFor = absIndex
                                    menuBubbleText = bubbleText
                                },
                            )

                            // 表情包已经从"贴在这条消息里"改成**独立一行**（v0.61.23）——
                            // 渲染在 `items(rows)` 的另一行里，这里什么都不用画。
                            // 数据没动：`emojiPath` 仍挂在这条消息上（不进请求体）。
                            }
                        }
                        // 流式气泡：打字机 + 闪烁光标（文档 §36）。
                        // 它只在内存里，**流式结束才写入历史** —— 见 ChatViewModel.commitAssistant
                        streaming?.let { text ->
                            item(key = "streaming") {
                                // ⚠️ 头像**不在这里包**（v0.44.2）：它已经收进 `AssistantBubbleStack`，
                                // 由它给**每一枚**气泡画一个。包在这一层只能给整段画一个，
                                // 连发拆成多枚时就露馅了（用户拿截图指出）。
                                StreamingBubble(
                                    content = text,
                                    reasoning = streamingReasoning,
                                    autoCollapse = settings.thinkingCollapseEnabled,
                                    waiting = typing,
                                    stalled = stalled,
                                    typeSpeed = settings.typeSpeed,
                                    sendMode = settings.sendMode,
                                    splitBubbles = settings.splitBubbles,
                                    personaAvatar = persona?.avatarPath,
                                    // v0.61.57：气泡不透明度（默认 1f = 零变化）
                                    bubbleAlpha = settings.bubbleAlpha,
                                    // 展开思考气泡后滚到底（v0.61.5，用户要求）
                                    onReasoningExpand = {
                                        scope.launch {
                                            runCatching {
                                                listState.animateScrollToItem(
                                                    (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0),
                                                )
                                            }
                                        }
                                    },
                                )
                            }
                        }

                        // 失败的对话化表达（H4）：她是说话的**人**，不该只剩一颗胶囊报错。
                        // ⚠️ 它不进 messages（用户选定的方案 B）——只活在界面层，
                        //    退出会话即消失，于是缓存前缀与模型上下文一个字没动。
                        errorLine?.let { line ->
                            item(key = "failure-${line.id}") {
                                FailureBubble(
                                    spoken = line.spoken,
                                    detail = line.detail,
                                    personaAvatar = persona?.avatarPath,
                                )
                            }
                        }

                        // 压缩完成的系统提示（v0.48.0）：与时间分割条**同一种居中胶囊**，
                        // 因为它是同一类东西 —— "不是谁说的话，是一件事发生了"。
                        // ⚠️ 它**不进 messages**（那会是缓存前缀的一部分，红线不许改）：
                        //    纯粹界面层，退出会话即消失。
                        compressNotice?.let { notice ->
                            item(key = "compress-notice") {
                                CompressNoticeRow(text = notice)
                            }
                        }

                        // ⚠️ v0.61.21：「Ta 正在做的事」**已搬到顶栏**（见 ActivityLine）。
                        //    理由：它说的是"现在"，而放在这里会随消息滚走 ——
                        //    用户往上翻两下，"她正在回你"就看不见了。

                        // 出错 / 数据警告 / 一般提示 —— 画在对话流里的**可关闭提醒卡**（v0.61.21）。
                        // ⚠️ 同样**不进 messages**：它是界面层的东西，退出会话即消失，
                        //    请求体一个字节都不受影响（缓存红线）。
                        // 放在压缩提示之后、底部锚之前 —— 也就是"最新发生的事"那一端。
                        appNotice?.let { item ->
                            item(key = "app-notice") {
                                AppNoticeCard(
                                    item = item,
                                    onClose = {
                                        when (item.kind) {
                                            AppNotice.Kind.ERROR -> vm.dismissError()
                                            AppNotice.Kind.WARN -> vm.dismissLoadWarning()
                                            AppNotice.Kind.INFO -> vm.dismissNotice()
                                        }
                                    },
                                )
                            }
                        }

                        // ── **底部锚**（v0.61.13）──
                        // ## 它修的是「流式输出时界面一跳一跳」
                        // `listState.scrollToItem(i)` 的语义是「让第 i 项的**顶部**对齐视口顶部」，
                        // 而"跟随到底"要的是「**停在最底部**」。没有锚的时候，滚到最后一项只是把
                        // **它的顶部**顶到视口顶 —— 流式正文一长，每一块增量都滚一次，观感就是
                        // 内容「向上冲一下 → 停 → 再冲」= 用户报的一跳一跳（**布局抖动，不是卡顿**）。
                        // 末尾放一个 1dp 的锚：滚到它就等于**精确贴底**，此后再长高时底部不动、
                        // 文字自然向上顶，那种"冲一下"就没了。
                        // ⚠️ 所有"滚到底"的地方统一用 `totalItemsCount - 1`（= 本项）即可，
                        //    不必再各自推算"最后一条消息在哪"。
                        item(key = "bottom-anchor") {
                            Spacer(Modifier.height(1.dp))
                        }
                    }
                }

                // ── 悬浮的液态玻璃输入区（用户规范第 3 条）──
                //    它**不是** Scaffold 的 bottomBar：bottomBar 是独立区域，其下方是窗口背景，
                //    透不出聊天记录。只有叠在消息列表之上，滑动时才会透出下层内容的"残影" ——
                //    这正是用户要的"空间层次感"。
                ChatInputLayer(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    input = input,
                    onInputChange = { vm.setDraft(draftKey, it) },
                    pending = pending,
                    onRemoveImage = { vm.removeImage(it) },
                    onImagePreview = { previewImage = it },
                    onPickImage = {
                        // 复用函数顶部注册的 launcher —— launcher 必须稳定注册，
                        // 不能在条件分支里 remember，否则重组时会重新注册并丢失结果
                        pickImage.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    // 「+」先开一个两层的小菜单（发图 / 换模型）——见 showAttachMenu 的渲染
                    onOpenAttachMenu = { showAttachMenu = true },
                    onSend = {
                        // 自己发的消息，无论此刻翻到哪儿都必须让她出现
                        forceFollow = true
                        vm.send(input)
                        vm.setDraft(draftKey, "")
                    },
                    // H3：按「停止」**不直接打断** —— 先弹二次确认（窗在下面）。
                    // 打断会把已经生成的部分就此定型，是不该被误触的动作。
                    onInterrupt = { confirmInterrupt = true },
                    busy = busy,
                    imageBusy = imageBusy,
                    canAttach = active != null,
                    placeholder = when {
                        // 同上：分组里的密钥也算"配好了"
                        effective.apiKey.isBlank() -> "先到设置里填 API Key"
                        active == null -> "先新建一个对话"
                        else -> "说点什么…"
                    },
                    // ⚠️ 这是**另一件事**：回车发送还是按钮发送。
                    // 与「回复的呈现方式」无关 —— 我第一版把这两件事混成了一个设置项。
                    enterToSend = settings.enterToSend,
                )
            }
        }
    }

    // 上下文弹窗（点顶栏小圆条弹出来）
    if (showContextSheet) {
        val ctxSession = active
        if (ctxSession != null) {
            ContextSheet(
                vm = vm,
                sessionId = ctxSession.id,
                // 上下文占用按这个模型的窗口算 —— 必须是与实际请求同一个模型
                model = effective.model,
                // 进度条 / 占用率按**记忆上下文**算（与压缩判定同口径）
                windowOverride = vm.memoryWindowForSession(active),
                // 另附「模型 API 上限」——它回答的是另一个问题："这模型最多能吃多少"
                apiWindowOverride = vm.windowForSession(active),
                messages = ctxSession.messages,
                frozenPrefix = persona?.let {
                    PromptEngine.buildFrozenPrefix(settings, it, vm.userPersonaFor(it))
                }.orEmpty(),
                hitTokens = ctxSession.totalHit,
                missTokens = ctxSession.totalMiss,
                hasSummary = !ctxSession.summary.isNullOrBlank(),
                summary = ctxSession.summary,
                coveredCount = ctxSession.summaryCount,
                compressMode = settings.compressMode,
                compressThreshold = settings.compressThreshold,
                compressSuggested = compressSuggested,
                onDismiss = { showContextSheet = false },
            )
        }
    }

    if (confirmInterrupt) {
        YukiDialog(
            title = "打断Ta的回复？",
            confirmText = "打断",
            // 「取消」不说"取消"：这一屏的问题是"她还在说，你要不要叫停"，
            // 另一个选择本来就是**让她说完**
            dismissText = "让Ta说完",
            destructive = true,
            onConfirm = {
                confirmInterrupt = false
                vm.interrupt()
            },
            onDismiss = { confirmInterrupt = false },
        ) {
            Text(
                text = "已经写出来的那部分会保留下来，Ta不会从头再说一遍。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    /* ── 附件菜单（「+」的第一层）──
     * 只两件事：发图、换模型。用户原话是把模型选择挂在「加号」上，
     * 而发图本来就是它的老职责 —— 两者都留着，用一层菜单分开。 */
    if (showAttachMenu) {
        AttachMenuSheet(
            onPickImage = {
                showAttachMenu = false
                pickImage.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
            onPickModel = {
                showAttachMenu = false
                // ⚠️ 生成中不开浮层：开了也只能点、点了也白点（见 `setSessionProvider`）。
                //    挡在这里比挡在浮层里好 —— 用户少走两步，而且立刻知道原因。
                if (busy) {
                    Island.warn("正在回答，等这一轮结束再换模型")
                } else {
                    showModelPicker = true
                }
            },
            onPickHistory = {
                showAttachMenu = false
                showHistory = true
            },
            onDismiss = { showAttachMenu = false },
        )
    }

    /* ── 「发过的话」（第三层，v0.61.21）── */
    if (showHistory) {
        SendHistorySheet(
            history = sendHistory,
            onPick = { text ->
                // ⚠️ **只填不发**：用户找旧话多半是要改一改再发（换个称呼、补一句）。
                //    直接发出去意味着点错就收不回来 —— 而这条一旦进了请求体就真的收不回。
                vm.setDraft(draftKey, text)
                showHistory = false
            },
            onDismiss = { showHistory = false },
        )
    }

    /* ── 选模型 / 切分组（第二层）── */
    if (showModelPicker) {
        ModelPickerSheet(
            // 与上面的 `effective` 读的是**同一份**分组状态 —— 不再取第二遍
            //（两份就会漂移：浮层说 A、顶栏说 B）
            groups = groups,
            // ⚠️ 传的是**会话自己**的字段（null = 跟着全局），不是"解析之后的当前分组"——
            //    浮层要靠这个 null 判断"要不要显示『恢复为跟随全局』"
            selectedGroupId = active?.providerGroupId,
            selectedModel = active?.model,
            globalGroupId = activeGroupId,
            onPick = { groupId, model ->
                val session = active
                // 切之前先记住"现在这套值" —— 用来判断这次切换会不会让缓存作废
                val beforeE = session?.let { vm.effectiveSettings(it) }
                // ⚠️ 生成中拒绝（用户要求「AI 在输出的时候不能切换模型」）。
                //    挡的是**会骗人的界面**，不是"会写坏请求" —— 请求体在 send() 那一刻
                //    就已经快照好了，正在生成的那条永远是旧模型答的。
                if (session != null && vm.setSessionProvider(session.id, groupId, model)) {
                    showModelPicker = false
                    // ⚠️ v0.61.54 修两个用户报的问题：
                    // 1. **提示的是上一个模型** —— 原来传 `session`（切换**前**的快照）给
                    //    `effectiveSettings`，它读的是旧 providerGroupId/model，于是提示滞后一轮。
                    //    这里改成用**切换后的新值**构造（与 setSessionProvider 写进去的一致）。
                    // 2. **免费分组下显示后端改的名称** —— 界面显示走 `labelOf()`（后端 label），
                    //    而 `after.model` 是**真名**（发出去的字节）。提示要给用户看的名字，
                    //    所以这里也走 `labelOf`，与他在浮层里选的那个名字一致。
                    val afterSession = session.copy(
                        providerGroupId = groupId.takeIf { it.isNotBlank() },
                        model = model.takeIf { it.isNotBlank() },
                    )
                    val after = vm.effectiveSettings(afterSession)
                    // 显示名：免费分组/后端改名时用 label，普通分组回落真名（labelOf 内部处理）。
                    // ⚠️ 按 baseUrl 找分组 —— AppSettings 里没有 providerGroupId 字段
                    //（它只有生效后的三件套），而分组与地址是一一对应的。
                    val afterGroup = groups.firstOrNull { it.baseUrl == after.baseUrl }
                    val shownModel = ProviderGroups.labelOf(afterGroup, after.model)
                    // ⚠️ v0.51.0：切换服务商/模型会让**前缀缓存作废** —— 新服务商那里
                    //    没有这段历史的缓存，新模型也是另一套缓存命名空间。
                    //    代价落在"下一轮按未命中计费"上，而这条信息用户看不到会以为
                    //    "怎么突然变贵了"。金额一律不编（单价随时会变），只说发生了什么。
                    when {
                        beforeE != null && beforeE.baseUrl != after.baseUrl -> Island.warn(
                            "已切到别的服务商。这段历史在那边没有缓存，下一轮按未命中计费，" +
                                "之后才会重新命中。",
                        )
                        beforeE != null && beforeE.model != after.model -> Island.warn(
                            "已换模型 $shownModel。新模型要重新建立缓存，下一轮按未命中计费。",
                        )
                        model.isBlank() -> Island.ok("已恢复为跟随全局")
                        else -> Island.ok("这段对话之后用 $shownModel")
                    }
                } else {
                    showModelPicker = false
                    if (session != null) Island.warn("正在回答，等这一轮结束再换模型")
                }
            },
            onDismiss = { showModelPicker = false },
        )
    }

    if (showNewSessionPicker) {
        PersonaPickerDialog(
            personas = personas,
            onPick = { id -> vm.newSession(id); showNewSessionPicker = false },
            onDismiss = { showNewSessionPicker = false },
            onCreatePersona = { showNewSessionPicker = false; onOpenPersonas() },
        )
    }

    // 会话内搜索：全屏层。
    //
    // 为什么用 Dialog 而不是把面板塞进上面的布局：它必须盖住**顶栏与输入区**，
    // 而这两块分别在 Scaffold 的 topBar 与内容 Box 里 —— 塞进任何一处都盖不全。
    // Dialog 自带独立窗口，天然在最上层，也不用改 ChatScreen 的既有层级。
    if (searchOpen) {
        SearchOverlay(
            messages = active?.messages.orEmpty(),
            personaName = personaName,
            personaAvatar = persona?.avatarPath,
            userProfile = profile,
            onPick = { index ->
                vm.closeSearch()
                vm.requestScrollTo(index)
            },
            onClose = vm::closeSearch,
        )
    }

    // 长按菜单。下标可能在等待期间失效（列表刷新过），所以每次重新取而不是记住那条消息。
    menuFor?.let { index ->
        val target = active?.messages?.getOrNull(index)
        if (target == null) {
            menuFor = null
            menuBubbleText = null
        } else {
            MessageActionsDialog(
                isAssistant = target.role == "assistant",
                // ⚠️ 「重新生成」也只对**最新一条她的回复**出现（v0.61.11，用户要求）。
                //    原来只判"历史尾部是她"，于是长按中间某条也会给出「重新生成」——
                //    点了却发现动的是最后一条（误导）。
                //
                // ⚠️ v0.61.57 修用户报的 bug：「AI 主动发的消息会被计为最近一条消息的
                //    重新生成的内容」。根因 = 这两个判据**只看了"是不是最后一条"**，
                //    没排除**主动消息**（`sendMode == PROACTIVE`）。
                //    Ta 主动发来的消息天然在尾部 → 长按它会出现「重新生成」，
                //    而点下去重生成的是**上一条用户消息的回复** —— 用户看到的就是
                //    "主动消息变成了重新生成的内容"。
                //    ⚠️ 主动消息没有配对的 user 消息，**本来就不该参与"最新一轮"** ——
                //    这与 `MessageEdits` 里那套跳过逻辑是同一条规则，两处必须一致。
                canRegenerate = target.role == "assistant" &&
                    !MessageEdits.isProactive(active?.messages.orEmpty(), index) &&
                    index == (active?.messages?.lastIndex ?: -1),
                // ⚠️ 「删除」**只对最新一条她的回复**开放（v0.61.17，用户改了口径：
                //    「直接让用户禁止删除历史消息不就好了，删除最新的消息需要二次确认」）。
                //    理由除了用户要的"简单"，还有一条硬的：删**中间**那一轮会断它之后的前缀
                //    （缓存代价）。历史消息仍可长按 —— 复制与重新生成照旧，只是没有「删除」。
                // ⚠️ 同样排除主动消息（v0.61.57）：它不是"某一轮问答"，删它会留下断裂的历史。
                canDelete = target.role == "assistant" &&
                    !MessageEdits.isProactive(active?.messages.orEmpty(), index) &&
                    index == (active?.messages?.lastIndex ?: -1),
                onCopy = {
                    // ⚠️ v0.61.5：长按的是某一枚气泡时，复制的是**那一枚**
                    //（用户要求「每个分段气泡的消息独立出来，可以单独复制那个气泡的消息」）；
                    //    没有具体枚（用户自己的消息）才退回整条。
                    clipboard.setText(
                        AnnotatedString(
                            menuBubbleText ?: TranscriptText.stripAppendix(target.content),
                        ),
                    )
                    menuFor = null
                    menuBubbleText = null
                },
                onDelete = {
                    // ⚠️ 不直接删 —— 先关菜单、记下待删下标，弹**二次确认**（用户要求，v0.61.14）
                    pendingDelete = index
                    menuFor = null
                    menuBubbleText = null
                },
                onRegenerate = {
                    vm.regenerate()
                    menuFor = null
                    menuBubbleText = null
                },
                onDismiss = {
                    menuFor = null
                    menuBubbleText = null
                },
            )
        }
    }

    /* ── 删除的二次确认（v0.61.14，用户要求）──
     * 删的是一整轮（她的回复 + 你的请求 + 这一轮自动记下的记忆），**不可撤销** ——
     * 必须先问一次。文案按"删的是不是最新一轮"分叉，把缓存代价说准：
     * 删最后一轮不动前缀，删中间的会断它之后的前缀。
     */
    pendingDelete?.let { target ->
        YukiDialog(
            title = "删除这一轮？",
            destructive = true,
            confirmText = "删除",
            onConfirm = {
                active?.let { vm.deleteExchangeAt(it.id, target) }
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        ) {
            // ⚠️ 删除已收窄到**只有最新一轮**（v0.61.17），所以只讲一件事：删的是哪三样。
            //    原来那句"这不是最新一轮 —— 会断缓存…"的分支已成死代码，一并去掉。
            Text(
                text = "会删掉 Ta 的这条回复、你发的那条请求，以及这一轮自动记下的记忆。\n" +
                    "删的是最新一轮，前面的历史不受影响。",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
        }
    }

    // 图片全屏预览（v0.61.14）：**已发出的图**与**输入框待发的图**共用这一个入口
    previewImage?.let { url ->
        ImagePreviewDialog(dataUrl = url, onDismiss = { previewImage = null })
    }
}

/* ═══════════════════════ 打字节奏与「正在输入」 ═══════════════════════ */

/** 打字节奏的 seed：固定值让同一段回复的节奏可复现（见 [Typewriter] 的注释）。 */
private const val TYPEWRITER_SEED = 20260927

/**
 * 逐字放出的正文。
 *
 * ## 为什么需要它
 * 网络是按块到达的（一块可能好几个字），直接显示就是「一次蹦一截」。
 * 用户要的是「像人在打字」：**有快有慢、标点处停顿**。
 * 节奏由 [Typewriter] 决定（纯函数、有单测），这里只负责按它推进。
 *
 * ## 两个易错点
 * 1. **不能把 text 当成 LaunchedEffect 的 key** —— 每来一块就重启，
 *    打字进度会被反复清零（表现为"字卡住不动"）。所以循环只启动一次，
 *    用 [rememberUpdatedState] 读最新的全文。
 * 2. **追不上时不能跳过** —— 内容到达速度可能比打字快（长回复尤其明显），
 *    这里是"一直追"，而不是"追不上就一次性显示"，否则又回到老问题。
 */
@Composable
private fun TypewriterText(
    text: String,
    modifier: Modifier = Modifier,
    speed: Int = TYPE_SPEED_NORMAL,
) {
    val latest by rememberUpdatedState(text)
    // 档位也走 rememberUpdatedState：把它放进 LaunchedEffect 的 key 会让每次改档位
    // 都重启循环、把 shown 清零 —— 已经打出来的字会**重打一遍**
    val speedNow by rememberUpdatedState(speed)
    var shown by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            val target = latest
            if (shown < target.length) {
                val wait = Typewriter.delayMillis(
                    target, shown, seed = TYPEWRITER_SEED, speed = speedNow,
                )
                if (wait <= 0L) {
                    // 关闭打字机：**整段直接上屏** ——
                    // 而不是「每字等 0 毫秒」地把整段长度空转一遍
                    shown = target.length
                } else {
                    delay(wait)
                    shown++
                }
            } else {
                delay(24L) // 等下一块
            }
        }
    }

    Text(
        text = latest.take(shown),
        style = MaterialTheme.typography.bodyLarge,
        color = TextPrimary,
        modifier = modifier,
    )
}

/**
 * 「正在输入…」——请求已发出、第一个字还没到的这段时间。
 *
 * 没有它，用户看到的是一个空气泡：不知道是网络卡了还是她没回。
 * 三个点循环跳动是「正在打字」的通用约定。
 */
@Composable
private fun TypingIndicator() {
    val transition = rememberInfiniteTransition(label = "typing")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 4f,
        animationSpec = infiniteRepeatable(tween(1600), RepeatMode.Restart),
        label = "typing-phase",
    )
    Text(
        text = "正在输入" + "·".repeat(phase.toInt().coerceIn(0, 3)),
        style = MaterialTheme.typography.bodyMedium,
        color = TextMuted,
    )
}

/* ═══════════════════════ 思考过程（可折叠） ═══════════════════════ */

/**
 * 一条回复的**思考过程**（`reasoning_content`）—— 独立成一枚「思考气泡」（v0.61.5）。
 *
 * ## 为什么它现在独立成一枚气泡
 * 用户要求：「思考的消息单独做成一个独立的气泡，不和主消息在一起」。此前它挂在正文
 * **第一枚气泡内部的顶部**（`leading`）—— 思考一长，用户看到的是"一大坨灰字 + 正文"，
 * 既不像"好几条消息"，也读不出"她在想什么、想了多久"。
 *
 * ## 与正文气泡的差异化（用户要求"做出差异化"）
 * - **底色不同**：`surfaceVariant` 灰底（正文气泡是白色 [SnowSurface] + 淡边框）
 * - **无边框、无阴影**（正文气泡有 1dp 阴影）、**形状不同**（全圆角 vs 正文带墙角）
 * - 正文用 `bodySmall` + 次要色；标题行带雪花图标
 * - ⚠️ **不响应长按** —— 思考不是"消息"，不提供复制（用户要求）
 *
 * ## 动态行为
 * - **流式中只显示最近三行**（用户要求）：思考动辄几百行，全量铺开会把聊天页撑爆，
 *   而这三行恰好是"她此刻在想什么"；想看全文等结束后点开；
 * - 流式中标题「正在想…」；结束后「思考过程 · 想了 X 秒」，收起态提示「点击展开」；
 * - 点一下切换展开/收起；**展开时回调 [onExpanded]**（外层据此滚到底，用户要求）。
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun ReasoningBubble(
    reasoning: String,
    streaming: Boolean,
    autoCollapse: Boolean,
    /** Ta 的人设头像（与正文气泡同一枚 —— "Ta 在想"也是 Ta 的一部分） */
    personaAvatar: String? = null,
    /** 思考用时（毫秒）。null = 没量到（正在流式 / 老消息 / 这次没思考） */
    thinkingMs: Long? = null,
    /** 用户点开时回调（外层滚到底） */
    onExpanded: () -> Unit = {},
    /**
     * 长按（v0.61.15）—— 与正文气泡**同一个菜单**（删除 / 重新生成）。
     * null = 不支持长按（流式气泡不需要：那时本来就不能改历史）。
     */
    onLongPress: (() -> Unit)? = null,
) {
    // null = 用户尚未手动干预；key 用 reasoning，换内容即重置
    var userExpanded by remember(reasoning) { mutableStateOf<Boolean?>(null) }
    val expanded = if (streaming) true else (userExpanded ?: !autoCollapse)

    // 流式中只看最近几行（见 KDoc）；结束后展开看全文
    val tail = ThinkingTail.of(reasoning)
    val shown = if (streaming) tail.text else reasoning
    // 流式中被省掉的逻辑行数（> 0 时在正文上方给一行「… 上方省略 N 行」）
    val omitted = if (streaming) tail.omitted else 0

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        YukiAvatar(size = 36.dp, path = personaAvatar)
        Spacer(Modifier.width(8.dp))
        Surface(
            // ⚠️ 用 `combinedClickable` 而不是 Surface 自带的 `onClick`（v0.61.15）：
            //    用户报「开了思考模式后，长按删除**没有任何效果**」——
            //    因为他长按的多半是**思考气泡**，而这里原来只有"点击展开"，
            //    长按既没有回调、事件还被消费掉，手势就消失了。
            //    现在长按 = 出那条消息的菜单（删除 / 重新生成），与正文气泡一致。
            //    波纹仍是 Material 的：`clip` 在 `combinedClickable` 之前，被圆角裁住。
            modifier = (if (streaming) {
                // ⚠️ 流式期间**固定高宽**（v0.61.8）：气泡先占好位置、内容在里面长 ——
                //    高度/宽度不跟着每块增量变，列表就不会抖。
                Modifier
                    .width(REASONING_BUBBLE_MAX_WIDTH)
                    .height(REASONING_LIVE_HEIGHT)
            } else {
                // 结束后恢复自适应：收起态只有标题行、展开态随内容
                Modifier.widthIn(max = REASONING_BUBBLE_MAX_WIDTH)
            })
                .clip(RoundedCornerShape(16.dp))
                .combinedClickable(
                    // 流式中不让点（正在长的思考收起来没有意义）；结束后点一下切展开/收起
                    enabled = !streaming,
                    onClick = {
                        val next = !expanded
                        userExpanded = next
                        if (next) onExpanded()
                    },
                    onLongClick = onLongPress,
                ),
            // 差异化：全圆角、灰底、无边框、无阴影（正文气泡是白底 + 边框 + 1dp 阴影）
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            ) {
                ReasoningHeader(
                    streaming = streaming,
                    expanded = expanded,
                    thinkingMs = thinkingMs,
                    lineCount = ThinkingTail.lineCount(reasoning),
                )

                if (expanded) {
                    Spacer(Modifier.height(6.dp))
                    // 「… 上方省略 N 行」—— 截断了就把这件事说出来，
                    // 免得用户以为上面那几行被吞了（用户点名的观感问题）。
                    if (omitted > 0) {
                        Text(
                            text = "… 上方省略 $omitted 行",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                        )
                        Spacer(Modifier.height(2.dp))
                    }
                    Text(
                        text = shown,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )
                }
            }
        }
    }
}

/**
 * 思考气泡**流式期间**的固定高度（v0.61.8）。
 *
 * 用户要求：「先输出**固定高宽**的消息气泡，然后在气泡里输出实时输出的思考内容」。
 * 根因就在高度上：气泡原来按内容自适应 —— 思考每来一块，「最近三行」的行数与宽度
 * 都可能变，列表项高度跟着抖，看起来就是**界面闪烁**（还连累滚动位置）。
 * 钉住高宽之后，气泡先占好位置、内容在里面长，列表一个像素都不动。
 *
 * ⚠️ 高度按**最满**的那种内容预留：标题行 + 「… 上方省略 N 行」+ 尾部 3 行正文
 *（13sp 的标题 16dp + 6dp 间距 + 省略行 19dp + 2dp 间距 + 3×19dp 正文 + 上下内边距 18dp = 118dp）。
 * 没截断时底部会多出一点留白 —— 这是刻意的：宁可略空，
 * 也不要让「省略提示」把最新那行挤出可视区（那正是用户要盯的东西）。
 */
private val REASONING_LIVE_HEIGHT = 120.dp

/** 思考气泡的宽度上限（比正文气泡略窄，也是差异化的一部分）。 */
private val REASONING_BUBBLE_MAX_WIDTH = 288.dp

/** 思考块的标题行（雪花 + 文案）—— [ReasoningBubble] 与 [ReasoningInline] 共用。 */
@Composable
private fun ReasoningHeader(
    streaming: Boolean,
    expanded: Boolean,
    thinkingMs: Long?,
    /** 思考的有效行数（忽略空行）—— 结束态在标题里带上「· N 行」；0 = 不显示 */
    lineCount: Int = 0,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            YukiIcons.Snowflake,
            contentDescription = null,
            tint = SkyBlueDeep.copy(alpha = 0.75f),
            modifier = Modifier.size(13.dp),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = when {
                streaming -> "正在想…"
                // 「想了多久、想了多少」都一并说出来：用户拿它判断这几秒值不值、要不要点开看全文
                expanded -> "思考过程${thinkingSuffix(thinkingMs)}${lineSuffix(lineCount)}"
                else -> "思考过程${thinkingSuffix(thinkingMs)}${lineSuffix(lineCount)}（点击展开）"
            },
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
        )
    }
}

/**
 * **老消息**（v0.61.6 之前）的思考块 —— 画在**气泡内部**，保持它当时的样式。
 *
 * ⚠️ 它存在的唯一理由：用户要求「老消息保持原样」。v0.61.5 起新消息的思考是独立的
 * [ReasoningBubble]；而老消息必须保持它们当时的画法（否则就是"把已经发生过的事改写了"）。
 * 两条路径**共用** [ReasoningHeader]，文案不会两处悄悄长得不一样。
 */
@Composable
private fun ReasoningInline(
    reasoning: String,
    streaming: Boolean,
    autoCollapse: Boolean,
    thinkingMs: Long? = null,
) {
    var userExpanded by remember(reasoning) { mutableStateOf<Boolean?>(null) }
    val expanded = if (streaming) true else (userExpanded ?: !autoCollapse)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                RoundedCornerShape(12.dp),
            )
            .clickable { userExpanded = !expanded }
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        ReasoningHeader(
            streaming = streaming,
            expanded = expanded,
            thinkingMs = thinkingMs,
            lineCount = ThinkingTail.lineCount(reasoning),
        )
        if (expanded) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = reasoning,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
        }
    }
}

/** 思考用时的文案后缀；没量到就什么也不加。 */
private fun thinkingSuffix(ms: Long?): String =
    if (ms == null || ms <= 0L) "" else " · 想了 ${formatThinkingDuration(ms)}"

/**
 * 思考行数的文案后缀（「过去式」标题里那句 `· N 行`）。
 *
 * 和用时一起说，用户才知道**要不要点开看全文** —— 只想了 2 行的人不必点，
 * 想了 200 行的值得点。0 行（没思考 / 老消息没存）就什么也不加。
 */
private fun lineSuffix(lines: Int): String =
    if (lines <= 0) "" else " · $lines 行"

/**
 * 把毫秒说成人话。
 *
 * 一分钟以内给一位小数（"3.2 秒"），超过就换成"1 分 05 秒" —— 长思考在界面上
 * 变成"93.4 秒"很难一眼读出来。与 `Session.cacheSummary` 一样，格式化只在**显示层**做。
 */
private fun formatThinkingDuration(ms: Long): String {
    if (ms < 60_000L) return "%.1f 秒".format(ms / 1000.0)
    val minutes = ms / 60_000L
    val seconds = (ms % 60_000L) / 1000L
    return "%d 分 %02d 秒".format(minutes, seconds)
}

/* ═══════════════════════ 时间分割条 ═══════════════════════ */

/**
 * 消息之间的时间分割条（仿微信）。
 *
 * ## 什么时候出现
 * 只在两条消息间隔超过 [TimeLabels.DIVIDER_GAP_MS]（5 分钟）时插入 ——
 * 连续的一问一答**不插**，否则聊天记录会被切得支离破碎，反而更难读。
 *
 * ## 文案
 * 由 [TimeLabels.forDivider] 决定，随时间久远程度变粗：
 * `14:30` → `昨天 14:30` → `周三 14:30` → `9月20日 14:30`。
 * 时间戳为 0 的老消息（加字段之前存下的）**不画** —— 宁可没有时间，
 * 也不要显示一个 1970 年的荒谬时刻。
 *
 * ⚠️ 渲染未经真机验证（本机无设备）。
 */
@Composable
private fun TimeDivider(text: String) {
    if (text.isEmpty()) return
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                    RoundedCornerShape(8.dp),
                )
                .padding(horizontal = 10.dp, vertical = 3.dp),
        )
    }
}

/**
 * 压缩完成的系统提示（v0.48.0）。
 *
 * ## 为什么长这样
 * 它**不是**"她说的"也不是"我说的"，而是一件事发生了 —— 所以沿用 [TimeDivider]
 * 的居中胶囊：同一类东西用同一种样子，用户不用重新学一遍视觉语言。
 *
 * ## ⚠️ 它不进 `messages`
 * `messages` 是下一轮请求前缀的一部分，红线是**写入后不再改写**。
 * 为了一条界面提示而写进历史，等于**用它永久污染了请求前缀**（缓存从那里起全碎）。
 * 所以它只活在 `ChatViewModel._compressNotice`（内存态），退出会话即消失。
 *
 * ⚠️ 用 `SkyBlueDeep` 而非 `onSurfaceVariant` 区分于时间条：时间条说"什么时候"，
 * 这条说"发生了一件事"，配色上给一点主色即可，不必再加图标（那会喧宾夺主）。
 */
@Composable
private fun CompressNoticeRow(text: String) {
    if (text.isEmpty()) return
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = SkyBlueDeep,
            modifier = Modifier
                .background(SkyBlue.copy(alpha = 0.14f), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 3.dp),
        )
    }
}

/**
 * 对话里的**可关闭提醒卡**（v0.61.21）。
 *
 * ## 它与 [CompressNoticeRow] 的分工
 * 那条是"一件事发生了"的中性告知（一次性的、不用回应），所以是**居中小胶囊、没有按钮**；
 * 这条是**要用户看见、可能还要回应**的（先去填密钥 / 导出失败 / 数据读取失败），
 * 所以整行宽、带描边、右边一个关闭按钮。
 *
 * ## 配色按类型分
 * 出错 [DangerRose] / 数据警告 [WarnAmber] / 一般提示主色 —— 扫一眼就知道这条要不要紧。
 * 三个颜色都是项目里既有的，没有新造色。
 *
 * ⚠️ 纯界面：不写 `messages`、不进请求体。关闭只是把 ViewModel 里那个状态清掉。
 * ⚠️ 文案一律**中性**（用「Ta」而不是「她」）—— 用户的人设千奇百怪，
 *    这里说不清"是谁"，用代词也不该替用户认定性别。
 */
@Composable
private fun AppNoticeCard(item: AppNotice.Item, onClose: () -> Unit) {
    val fg = when (item.kind) {
        AppNotice.Kind.ERROR -> DangerRose
        AppNotice.Kind.WARN -> WarnAmber
        AppNotice.Kind.INFO -> SkyBlueDeep
    }
    val bg = when (item.kind) {
        AppNotice.Kind.ERROR -> DangerRoseBg
        AppNotice.Kind.WARN -> WarnAmberBg
        AppNotice.Kind.INFO -> SkyBlue.copy(alpha = 0.14f)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .border(1.dp, fg.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = item.text,
            style = MaterialTheme.typography.bodySmall,
            color = fg,
            modifier = Modifier.weight(1f),
        )
        // ⚠️ 用 `YukiIcons.Close`（项目自绘的那套描边矢量，与 `Add` 同一笔法）——
        //    不要用文字「✕」：那既不是图标、字重和尺寸也跟旁边的图标对不齐。
        IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
            Icon(
                imageVector = YukiIcons.Close,
                contentDescription = "关闭这条提醒",
                tint = fg,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/**
 * 顶栏第二行：Ta 此刻在做什么（v0.61.21）。
 *
 * ## 从消息流搬到这里，为什么
 * 它说的是**"现在"**。放在消息流里，用户往上翻两下它就滚出屏幕了 ——
 * 而"她正在回你"这种信息恰恰是你**最需要它固定住**的时候。
 * 搬到不滚动的顶栏之后，原来那条占满宽度的卡片也就没必要了。
 *
 * ## 空闲时显示"在的"而不是留空
 * 留空会让顶栏在她"开始做事"的那一瞬间**跳一下高度**，整页跟着抖。
 * 一句轻描淡写的"在的"既填住了这一行，也正好是初雪的语气。
 */
@Composable
private fun ActivityLine(items: List<Activity.Kind>) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (items.isEmpty()) {
            // ⚠️ v0.61.29（用户要求：「不显示"在的"，改成 AI 输出的时候才显示」）：
            //    空闲时**什么都不渲染**。原来常驻一行「在的」—— 它没信息量，
            //    还把标题区撑成两行、与两侧圆钮的圆心对不齐（用户报的"文字重心错位"）。
            //    现在只有 AI 真在做事（items 非空）时才出现这一行。
        } else {
            // 一枚极小的实心点 + 文案。多个动作不再竖排成列表 ——
            // 顶栏只有一行，用「·」连起来既不换行也读得顺。
            Box(
                Modifier
                    .size(5.dp)
                    .background(SkyBlueDeep, RoundedCornerShape(50)),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = items.joinToString(" · ") { it.label },
                style = MaterialTheme.typography.labelSmall,
                color = SkyBlueDeep,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/* ═══════════════════════ 悬浮输入区 ═══════════════════════ *//**
 * 列表底部为悬浮输入区让出的高度。
 *
 * ⚠️ 它必须 **≥ 输入区的实际高度**，否则最后一条消息会被输入区压住。
 *
 * ## 它是怎么算出来的（改这个数之前先照着算一遍）
 * 输入区占用 = top 留白 8 + 面板高约 56（48 的触控目标 + 上下各 4）+ bottom 留白 32 ≈ 96dp，
 * 再留 16dp 当"最后一条消息与输入框之间"的呼吸空间 → 112dp。
 *
 * 历史沿革：148dp（用户嫌太空，最后一条离输入框一大截）→ 108dp
 * → **112dp**（用户 2026-09-28 又说「输入框太低」，底部留白 18→32，让出的高度跟着回调）。
 */
private val INPUT_LAYER_RESERVED_HEIGHT = 112.dp

/**
 * 聊天输入区 —— **悬浮**在消息列表之上的面板（用户规范第 3 条）。
 *
 * ## 为什么它不在 `Scaffold.bottomBar`
 * `bottomBar` 是 Scaffold 里一个**独立区域**：内容区到它上方就结束，它下方是窗口
 * 背景。放进去怎么调都是"贴底的一条实底栏"，永远透不出聊天记录。
 *
 * 用户要的是「输入区**浮在内容之上**」：往下滑聊天记录时，它压在最底层之上。
 * 所以它不能放进 `Scaffold.bottomBar`（那是独立区域，其下方是窗口背景而非消息列表），
 * 而是挪进了内容区的 `Box`，用 `align(Alignment.BottomCenter)` 定位。
 *
 * 代价是列表要自己让出底部空间：见 [INPUT_LAYER_RESERVED_HEIGHT] 与调用处的
 * `contentPadding.bottom`。那个数字若小于本面板的实际高度，最后一条消息会被压住。
 *
 * ## 外观沿革：液态玻璃 → 扁平实色 → 玻璃 → **「柔和卡片」材质（v0.35.0）**
 * - 最早是一块**液态玻璃面板**（靠透明度做层次）；扁平风定调后换成**浅灰实色**；
 * - v0.34.0 按文档 §45.4 改成"玻璃三要素"（半透明 + 高光 + 阴影）并换了胶囊形状 ——
 *   但半透明白铺在浅灰背景上就成了**一条白条**（用户当时的原话）；
 * - **v0.35.0 用户明确否掉了那条路**：「不要背景模糊，不要玻璃拟态效果」。
 *   现在这套是**不透明的实体卡片**：径向渐变（浅灰白 + 右上暖米粉）+ 柔和外阴影
 *   + 极细高光描边。材质收在 [TranslucentPanel]，与底部导航栏**共用同一份**。
 *
 * ⚠️ 这**不是真模糊**（backdrop blur）。Compose 里做真正的背景模糊要抓下层图层，
 * 本项目那套（`LiquidGlassBottomBar` / `GlassDrawing`）早已删除。这里是"玻璃感"的
 * **近似**：半透明 + 描边 + 阴影。要真模糊得另开一轮，且只能靠真机判断值不值。
 * 输入内核仍是 `BasicTextField` + 自绘光标/占位符：文档 §28.1 要求控件自定义，
 * 不用 Material 的 `OutlinedTextField`（那套下划线与聚焦框是"标准 Android 应用"的脸）。
 *
 * ⚠️ 渲染未经真机验证（本机无设备）。
 */
@Composable
private fun ChatInputLayer(
    modifier: Modifier = Modifier,
    input: String,
    onInputChange: (String) -> Unit,
    pending: List<String>,
    onRemoveImage: (Int) -> Unit,
    /** 点待发图片 → 看大图（v0.61.14，用户要求） */
    onImagePreview: (String) -> Unit,
    onPickImage: () -> Unit,
    /**
     * 「+」按钮的动作入口（v0.51.0）。
     *
     * ⚠️ 它**不再直接**弹相册 —— 否则模型选择就没有地方放。
     * 用户明确要求模型选择挂在「加号」上（见 [ChatInputLayer] 的调用点注释）。
     */
    onOpenAttachMenu: () -> Unit,
    onSend: () -> Unit,
    /** 正在生成时按「停止」—— 界面先弹二次确认，确认后才调 `vm.interrupt()`（H3） */
    onInterrupt: () -> Unit,
    busy: Boolean,
    imageBusy: Boolean,
    canAttach: Boolean,
    placeholder: String,
    /**
     * 回车是否直接发送（设置项）。
     *
     * true：输入框**单行**、回车即发送 —— 聊天类产品的主流预期；
     * false：输入框可**多行**、回车换行，只能按右侧按钮发。
     *
     * ⚠️ 这不是外观偏好，而是**回车键归谁**：中文输入法用回车选词，
     * 两者不可兼得，所以必须让用户选。
     */
    enterToSend: Boolean = true,
) {
    // 底部留白分两种情形给：
    //   · 没键盘时 —— 32dp，明显离屏幕底一段，是一块"浮着的面板"；
    //   · 键盘弹起时 —— **仍留 12dp**。用户 2026-09-28 明确要求「调起键盘时**还是保持悬浮**」：
    //     贴到 0 会让它看起来被键盘"吞"进去、变成一条贴底栏，那块卡片的圆角与阴影就白做了。
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    val bottomGap = if (imeVisible) 12.dp else 32.dp

    Column(
        modifier = modifier
            .fillMaxWidth()
            // ⚠️ 这里**不再**带 `imePadding()`（v0.44.1）：inset 已经由聊天页的根 Box 统一让出，
            // 这一层再来一次就是让两遍 —— 输入框会比键盘高出整整一个键盘的高度。
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = bottomGap),
    ) {
        if (pending.isNotEmpty()) {
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(pending.size) { index ->
                    PendingImageChip(
                        dataUrl = pending[index],
                        onRemove = { onRemoveImage(index) },
                        onPreview = { onImagePreview(pending[index]) },
                    )
                }
            }
        }

        // 输入区外壳：**「柔和卡片」材质**（用户 2026-09-28 的第二版规格）。
        //
        // ## 与上一版最根本的区别：**不再用透明度做层次**
        // 上一版（v0.34.0）按文档 §45.4 做的是"玻璃三要素"，靠半透明让底层内容透出来。
        // 用户这次明确把那条路否掉了：「**不要背景模糊，不要玻璃拟态效果**」。
        // 现在这套是**不透明的实体卡片** —— 层次由**径向渐变 + 阴影 + 高光描边**表达。
        //
        // 顺带躲开了本项目最顽固的那一族缺陷（浅底上的半透明元素会隐形，栽过三次）：
        // **不透明的东西不会隐形。**
        //
        // 材质定义在 [TranslucentPanel]（`ui/components/`）—— 底部导航栏用的是**同一个**，
        // 靠外框圆角与暖色光晕位置做差异化。
        TranslucentPanel(corner = InputPanelCorner, alpha = PANEL_ALPHA) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                // ⚠️ 原来是 CenterVertically：输入框长高时，两侧按钮会停在**半高**位置。
                //    改成 Bottom —— 和微信/QQ 一样，按钮随输入框一起往下沉，
                //    多行时它们贴着底部，视觉重心不散。
                verticalAlignment = Alignment.Bottom,
            ) {
                /* ── 最左：圆形加号（动作入口：发图 / 换模型）── */
                RoundIconButton(
                    icon = YukiIcons.Add,
                    contentDescription = "更多",
                    enabled = !busy && !imageBusy && canAttach,
                    busy = imageBusy,
                    onClick = onOpenAttachMenu,
                )

                Spacer(Modifier.width(10.dp))

                /* ── 中间：输入框（占满剩余宽度）── */
                // ⚠️ 生成期间**输入框禁用**（用户 2026-10-04 明确：「ai 在生成期间是禁用输入和发图」）。
                //    上一轮我把这里放开过（以为"打断保留输入框内容"需要能打字）——那是我理解反了，
                //    已改回。发图那一侧（最左的「+」）本来就是 `enabled = !busy && …`，无需改动。
                BasicTextField(
                    value = input,
                    onValueChange = onInputChange,
                    enabled = !busy,
                    modifier = Modifier.weight(1f).padding(vertical = 6.dp),
                    // 背景透明：它坐在面板上，自己不该再铺一层底
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = TextPrimary),
                    cursorBrush = SolidColor(SkyBlueDeep),
                    // ⚠️ **高度恒定自适应**（用户 2026-10-02：「行为对齐微信/QQ：
                    //     默认 1 行，随内容增高，到上限后输入框内部滚动，
                    //     不撑满屏幕、不顶掉消息列表」）。
                    //
                    // 上一版写的是 `if (enterToSend) 1 else 5` —— 开了「回车发送」
                    // 就锁死单行、永远不换行也不长高。那条注释说"是回车键归谁的差异，
                    // 两者不能兼得"，**其实能兼得**：
                    //   高度 ← minLines/maxLines 管
                    //   回车归谁 ← keyboardOptions.imeAction + keyboardActions.onSend 管
                    // 两者互不干扰。所以现在**去掉这层耦合**，高度只由 [INPUT_MAX_LINES] 决定。
                    //
                    // 上限 5 行 ≈ 120dp：到顶之后 BasicTextField 自己内部滚动，
                    // 面板不再长，消息列表最多被挤掉这 5 行的高度 —— 不会被撑满。
                    minLines = 1,
                    maxLines = INPUT_MAX_LINES,
                    keyboardOptions = KeyboardOptions(
                        imeAction = if (enterToSend) ImeAction.Send else ImeAction.Default,
                    ),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            // 空消息不发（与发送钮的 enabled 判据保持同一套）
                            if (!busy && (input.isNotBlank() || pending.isNotEmpty())) onSend()
                        },
                    ),
                    decorationBox = { inner ->
                        if (input.isEmpty()) {
                            Text(
                                text = placeholder,
                                style = MaterialTheme.typography.bodyLarge,
                                // 占位文字用浅灰（用户规格）：比正文明显轻一档
                                color = TextMuted,
                            )
                        }
                        inner()
                    },
                )

                Spacer(Modifier.width(10.dp))

                /* ── 语音 ──
                   ⚠️ 设备/ROM 不支持语音识别时，这个组件**自己不画**（见 VoiceInputButton 的注释）。
                   所以在一部分机型上这个位置会是空的 —— 那是刻意的，不是布局塌了。 */
                VoiceInputButton(
                    enabled = !busy,
                    onResult = { said ->
                        // 识别结果**追加**到已有草稿后面，而不是覆盖：
                        // 用户可能已经打了一半，说一句话不该把那半句吃掉
                        onInputChange(if (input.isBlank()) said else input + said)
                    },
                )

                Spacer(Modifier.width(6.dp))

                /* ── 最右：发送 ──
                   ⚠️ 这里原来是"输入框「下方靠左」一颗小胶囊"（按上一版规格做的）。
                   用户 2026-09-28 改口：「发送按钮应该是输入框的**最右侧**才人性化」——
                   这个判断是对的：拇指从键盘上抬起后最近的位置就是右下角，
                   而左下那颗要横跨整个输入框才够得着。 */
                SendButton(
                    enabled = !busy && (input.isNotBlank() || pending.isNotEmpty()),
                    busy = busy,
                    onSend = onSend,
                    onInterrupt = onInterrupt,
                )
            }
        }
    }
}

/* ═══════════════════════ 输入区里的小零件 ═══════════════════════ */

/*
 * ⚠️ 这里原本有一个**本地**的 `RoundIconButton` —— v0.37 把它提到了
 * `ui/components/RoundIconButton.kt`：语音钮也要用同一个圆底，
 * 而"圆底写两遍"的结果迟早是一处尺寸或圆角不一样（在真机上就是"没对齐"）。
 */

/**
 * 她的**表情包气泡** —— 跟在回复下面的一张图。
 *
 * ## ⚠️ 它不是一条消息
 * 不写库、不回传模型、不进请求前缀（见 `ChatViewModel.lastEmoji` 的注释）。
 * 所以它跟回复之间是**视觉上的紧跟**，而不是数据上的从属关系 ——
 * 删除/重新生成那条回复时，图也会自然跟着变（因为它是按"最后一条她的回复"算出来的）。
 *
 * ## 对齐
 * 左边让出 44dp（头像 36 + 间距 8），与她的文字气泡左缘对齐 ——
 * 不然它会贴到屏幕最左边，看起来像另一个人的东西。
 *
 * ⚠️ 渲染未经真机验证（本机无设备）。
 */
/**
 * 顶栏那枚**上下文占用**小圆点（v0.45.0）。
 *
 * ⚠️ 刻意用"圆 + 数字"而不是进度弧：进度环依赖 material3 的 `progress` API，
 * 而它的签名在版本之间改过（`Float` → `() -> Float`），容易踩编译坑；
 * 而"占了多少"这件事，**数字比弧长更一眼看懂**。
 */
/**
 * 压缩的**二次确认**（v0.45.9，用户要求）。
 *
 * ## 为什么这个操作值得单独问一次
 * 它是**唯一会主动让缓存失效**的动作：摘要一旦进入 history，
 * 下一轮请求的前缀就与上一轮不同 —— 那一次**全额按未命中计费**。
 * 长对话里可能是几万 token 的钱，误触的代价不该由一次点击决定。
 */
@Composable
private fun ConfirmCompressDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        androidx.compose.material3.Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = SnowSurface,
            border = BorderStroke(1.dp, FrostLine),
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    "压缩更早的记录？",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "把较早的那一段总结成摘要，之后发给Ta的是「摘要 + 最近的内容」。聊天记录一条都不会少；但压缩会让下一次请求的缓存失效一次，那一次费用略高。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMuted,
                )
                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    Text(
                        "再想想",
                        style = MaterialTheme.typography.labelLarge,
                        color = TextMuted,
                        modifier = Modifier.clickable(onClick = onDismiss).padding(8.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "压缩",
                        style = MaterialTheme.typography.labelLarge,
                        color = DangerRose,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable(onClick = onConfirm).padding(8.dp),
                    )
                }
            }
        }
    }
}

/**
 * 占用率的配色（顶栏小圆环 + 上下文弹窗里两条进度条**共用**）。
 *
 * ⚠️ 三个消费者必须走这一个函数（v0.48.0 修）：此前环按**整数 `pct`** 判档、
 * 弹窗里的条按 **`ratio`** 判档，同一个占用率在环上是琥珀、在条上是蓝 ——
 * 用户看到的是"同一件事两个颜色"，会以为哪个坏了。
 */
private fun occupancyColor(ratio: Float): Color = when {
    ratio >= 0.9f -> DangerRose
    ratio >= ContextCompress.DEFAULT_THRESHOLD -> WarnAmber
    else -> SkyBlueDeep
}

/**
 * 缓存命中率的配色（与占用率**方向相反**：越高越好）。
 *
 * 分档口径写在这里而不是散在 UI 里 —— 以后调阈值只改一处。
 */
private fun hitRateColor(fraction: Float): Color = when {
    fraction >= 0.85f -> SuccessMint
    fraction >= 0.5f -> WarnAmber
    else -> DangerRose
}

/**
 * 通用进度条行（v0.48.0）：标题 + 数值 + 条 + 说明。
 *
 * ## 为什么抽出来
 * 用户要"两条进度条"（缓存命中率 / 距压缩）。两条长得一样、只差数据，
 * 各写一遍必然出现"一条有圆角一条没有"这类漂移。
 *
 * ## 画法
 * 两层 `Box`（轨道 + 填充），不依赖 material3 进度组件的签名 —— 与原来那条一致。
 * ⚠️ `fraction` 在内部 `coerceIn(0f, 1f)`，调用方传超界值也不会画出界。
 */
@Composable
private fun ProgressRow(
    label: String,
    valueText: String,
    fraction: Float,
    color: Color,
    caption: String,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = TextPrimary,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelMedium,
                color = color,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier.fillMaxWidth().height(8.dp)
                .clip(RoundedCornerShape(50)).background(FrostLine),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .height(8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(color),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(caption, style = MaterialTheme.typography.labelSmall, color = TextMuted)
    }
}

@Composable
private fun ContextRing(ratio: Float) {
    // ⚠️ v0.61.30（用户：「别只留一个环啊，百分比还是要的」）：
    //    百分比**回到顶栏**，但按钮仍是 40×40（保住左右对称）——
    //    做法是**环缩小到 26dp、百分比写在环心**（8.5sp）。
    //    ⚠️ 上一版把它整个挪进弹窗是**做过头了**：用户要的是"数字看得见"，不是"移走"。
    val pct = (ratio * 100).roundToInt()
    val color = occupancyColor(ratio)
    Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            // ⚠️ 用 material3 1.3 的**新签名** `progress = { … }`（lambda）——
            //    旧的 `progress: Float` 已废弃，两条编译期都接受、运行时表现不同，
            //    混用会让"环不动"这种问题查很久。
            progress = { ratio.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxSize(),
            strokeWidth = 2.dp,
            color = color,
            // 轨道透明：原来用 FrostLine（浅灰），在模糊底上就是一个浅色圆圈，
            // 那正是用户说的"像是带有一层白色底色"。
            trackColor = Color.Transparent,
        )
        Text(
            text = "$pct%",
            fontSize = 8.5.sp,
            lineHeight = 9.sp,
            color = color,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

/**
 * 上下文弹窗（用户要的那个：点小圆条弹出来）。
 *
 * 四件事：这段对话用了多少 token、用的哪个模型、压缩进度、**手动触发压缩**的按钮。
 *
 * ⚠️ 里面的数字全是**估算**，文案里写明了 —— 真实 token 只有 API 的 `usage` 才知道，
 * 把这个数当账单看会让用户以为我在虚报。
 * ⚠️ 这些值**只上屏、绝不进请求体**（动态值进前缀会让缓存全碎）。
 */
@Composable
private fun ContextSheet(
    vm: ChatViewModel,
    sessionId: String,
    model: String,
    /** **记忆上下文**（分组里填过就用它）；`null` = 没填，按默认估算 */
    windowOverride: Int? = null,
    /**
     * **模型 API 上下文**（服务商允许的最大窗口）；`null` = 分组里没填。
     *
     * ⚠️ v0.53.0 拆出来后，它**不参与**占用率 / 压缩条的计算 ——
     * 只用来在弹窗里回答另一个问题："这模型最多能吃多少"（超过它请求会直接报错）。
     */
    apiWindowOverride: Int? = null,
    messages: List<ChatMessage>,
    /** 人设那一段（Frozen Prefix）—— 它每轮都发出去，必须算进占用 */
    frozenPrefix: String,
    /** 这段对话累计命中的缓存 token（原来挂在顶栏，现移到此） */
    hitTokens: Int,
    /** 累计未命中 */
    missTokens: Int,
    hasSummary: Boolean,
    /** 已生成的摘要（可为空）——占用必须按"实际要发的"算，见下面的 estimateSentContext */
    summary: String?,
    /** 摘要覆盖的条数（0 = 没压过） */
    coveredCount: Int,
    /** 压缩触发模式（`AUTO`/`ASK`/`MANUAL`）—— 决定第二条进度条的说明措辞 */
    compressMode: String,
    /** 压缩阈值（0..1）—— 决定第二条进度条的满刻度位置 */
    compressThreshold: Float,
    /** 是否处于"该压缩了"状态（`ask` 模式下由 ViewModel 置位；**不弹模态框**） */
    compressSuggested: Boolean,
    onDismiss: () -> Unit,
) {
    val compressing by vm.compressing.collectAsStateWithLifecycle()

    // ⚠️ 压缩**必须先问一次**（用户要求，v0.45.9）：摘要一进 history，
    // 下一轮请求前缀就变了 —— 那一次缓存必然全碎（用户为此付钱）。
    var confirmCompress by remember { mutableStateOf(false) }
    val note by vm.compressNote.collectAsStateWithLifecycle()
    val lastTurn by vm.lastTurnStats.collectAsStateWithLifecycle()
    /** 「最近一次」那组数是不是真的读数（false = 这家服务商不报缓存用量） */
    val lastTurnReports by vm.lastTurnReports.collectAsStateWithLifecycle()

    val limit = ContextCompress.contextLimit(model, windowOverride)
    // ⚠️ 用 **estimateSentContext**（v0.48.0）：它把摘要算进去，反映"这一轮真正发多少"。
    // 用 estimateContext 的话，压缩前后数值**完全一样**（压缩不动 messages），
    // 用户会看到"压了但占用没降"，而且触发判定会每轮重复触发。
    val used = ContextCompress.estimateSentContext(
        frozenPrefix = frozenPrefix,
        messages = messages,
        summary = summary,
        coveredCount = coveredCount,
    )
    val ratio = if (limit > 0) (used.toFloat() / limit).coerceIn(0f, 1f) else 0f
    val pct = (ratio * 100).roundToInt()

    // ── 条 1：缓存命中率（v0.48.0）──
    // ⚠️ 优先用**最近一次**而不是累计值：累计值被"历史上那些没命中的请求"永久拖住，
    //    要聊很多轮才稀释得回来，看不出"现在到底怎么样"（它单独一行显示在下面）。
    val turn = lastTurn
    val hitFraction = when {
        turn != null && (turn.first + turn.second) > 0 ->
            turn.first.toFloat() / (turn.first + turn.second)
        // 还没有"最近一次"的读数 → 退回累计值；累计也是 0 → 0
        (hitTokens + missTokens) > 0 -> hitTokens.toFloat() / (hitTokens + missTokens)
        else -> 0f
    }
    val hitBasis = if (turn != null && (turn.first + turn.second) > 0) "最近一次" else "累计"

    // ── 条 2：距「该压缩了」（v0.48.0）──
    // 满刻度 = 阈值本身，所以条满 = 正好到达触发点；这样"还差多少"一眼可见。
    // ⚠️ 用 estimateContext（含人设 + 附录 + 本轮输入），**不是** usageRatio
    //   —— 后者只算历史，会明显低报（人设长的话能差几千 token）。
    // ⚠️ v0.61.56：改用 `triggerLineTokens`（**公式的单一真源**）。
    //    原来这里自己算 `limit * threshold` —— 与 `decideCompress` 的公式**各写一份**，
    //    一旦公式改了（本次就改了：加输出预留与 headroom 下界），
    //    弹窗显示的进度条就会与实际判定**对不上**（用户看到"才 60%"却已经触发压缩）。
    val compressTarget = ContextCompress.triggerLineTokens(
        limitTokens = limit,
        threshold = compressThreshold,
    ).toFloat()
    val compressRatio = if (compressTarget > 0f) (used / compressTarget).coerceIn(0f, 1f) else 0f

    val billed = hitTokens + missTokens
    // ⚠️ 数字格式走**共用**函数（v0.48.0）：别把 99.6% 说成 100%。
    //    同一个数会在弹窗 / 看板 / 列表摘要三处出现，各写一遍必然漂移。
    val hitRate = if (billed > 0) formatHitPercent(hitTokens, billed) else "—"

    // 环与两条进度条**共用同一套配色**（此前环按整数 pct、条按 ratio，同一占用率会落在不同色档）
    val barColor = occupancyColor(ratio)
    val hitBarColor = hitRateColor(hitFraction)

    Dialog(onDismissRequest = onDismiss) {
        androidx.compose.material3.Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = SnowSurface,
            border = BorderStroke(1.dp, FrostLine),
        ) {
            Column(Modifier.padding(20.dp)) {
                Text("上下文", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                // ── 原有引导语（保留）──
                Text(
                    "Ta这次能记住多少。聊得越久占得越多，占满了就压成摘要。",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                )

                Spacer(Modifier.height(14.dp))
                // ── 原有：绝对用量（大字号，视觉焦点）──
                Text(
                    text = ContextCompress.formatTokens(used),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                )
                // ── 新增（用户给的格式）：501K / 1.0M (50%) ──
                Text(
                    text = "${ContextCompress.formatShort(used)} / " +
                        "${ContextCompress.formatShort(limit)} ($pct%)" +
                        // ⚠️ v0.51.0：认不出这个模型的窗口时，那两个数是**按默认估的** ——
                        //    不标出来，用户会把它当一个确定的分母（第三方 8K 的模型
                        //    看到"用了 20%"就以为还早，其实早就该压了）。
                        //    分组里填过窗口就不会落到这里（填的就是事实）。
                        if (!ContextCompress.contextLimitKnown(model, windowOverride)) {
                            " · 记忆窗口按默认估算"
                        } else {
                            ""
                        } + if (apiWindowOverride != null) {
                            // v0.53.0：把"模型能吃多少"与"记忆装多少"分开说清 ——
                            // 这两个数过去是同一个，用户容易以为改它就是改压缩时机
                            " · 模型上限 ${ContextCompress.formatTokens(apiWindowOverride)}"
                        } else {
                            ""
                        },
                    style = MaterialTheme.typography.labelMedium,
                    color = TextMuted,
                )
                // ── 新增：缓存命中率（原挂在顶栏，按用户要求挪进弹窗）──
                Spacer(Modifier.height(2.dp))
                // ── 最近一次请求的读数（v0.46.4）──
                // ⚠️ 存在的理由是"累计值看不出修复是否生效"：
                // 历史上那些统计丢失的请求已永久计入未命中，新命中要聊很多轮才稀释得回来。
                // 这一行是**即时**的 —— 发一条消息，命中数不为 0 就说明统计通了。
                lastTurn?.let { (hitN, missN, inN) ->
                    // ⚠️ v0.51.0：非 DeepSeek 的服务商可能**根本不报**缓存用量。
                    //    那时把 0 显示成红色的"命中 0"是在报一件没发生的事 ——
                    //    用户会去改人设、改历史找原因，而那些动作**真的**会把前缀弄断。
                    if (!lastTurnReports) {
                        Text(
                            text = "最近一次：该服务商未提供缓存用量",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextMuted,
                        )
                    } else {
                        Text(
                            text = "最近一次：命中 $hitN / 未命中 $missN" +
                                if (inN > 0) "（输入共 $inN）" else "",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (hitN > 0) SkyBlueDeep else DangerRose,
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                }
                Spacer(Modifier.height(12.dp))
                // ── 条 1：缓存命中率（v0.48.0，用户要的两条之一）──
                ProgressRow(
                    label = "缓存命中率",
                    valueText = hitRate,
                    fraction = hitFraction,
                    color = hitBarColor,
                    caption = if (billed > 0) {
                        "$hitBasis：命中 $hitTokens / 共 $billed（越满越省）"
                    } else {
                        "还没发过消息，发一条就能看到"
                    },
                )

                Spacer(Modifier.height(12.dp))
                // ── 条 2：距「该压缩了」（v0.48.0，用户要的两条之二）──
                ProgressRow(
                    label = "距压缩",
                    valueText = "${(compressRatio * 100).roundToInt()}%",
                    fraction = compressRatio,
                    color = barColor,
                    // ⚠️ v0.61.56：caption 里**带上触发线的绝对 token 数**。
                    //    用户报「怎么改都不会触发」—— 根因是他看不到"触发线在哪"：
                    //    只有百分比，没有"还差多少 token / 要聊多久"。
                    //    现在每条都补一句 `触发线 X token`，调阈值时能立刻看到它变小。
                    caption = when {
                        // 已经到点了：按模式说清"接下来会发生什么"
                        compressSuggested && compressMode == COMPRESS_MODE_ASK ->
                            "已经到 ${(compressThreshold * 100).roundToInt()}% 了（${ContextCompress.formatTokens(compressTarget.toInt())}）—— 点下面的按钮压缩"
                        compressRatio >= 1f && compressMode == COMPRESS_MODE_AUTO ->
                            "已经到 ${(compressThreshold * 100).roundToInt()}% 了（${ContextCompress.formatTokens(compressTarget.toInt())}）—— 下一条消息会自动压缩"
                        compressMode == COMPRESS_MODE_AUTO ->
                            "到 ${ContextCompress.formatTokens(compressTarget.toInt())} 时自动压缩"
                        compressMode == COMPRESS_MODE_MANUAL ->
                            "已设为手动：不会自动压，也不会提示"
                        else ->
                            "到 ${ContextCompress.formatTokens(compressTarget.toInt())} 时在这里提示你"
                    },
                )

                Spacer(Modifier.height(8.dp))
                Text(
                    text = "建议上下文保持在 50% 以内，避免上下文过长导致生成变慢。",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                )

                Spacer(Modifier.height(14.dp))
                Text("模型：$model", style = MaterialTheme.typography.labelMedium, color = TextPrimary)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (hasSummary) "已压缩过：更早的内容是以摘要形式发给Ta的" else "还没有压缩过",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                )

                note?.let { n ->
                    Spacer(Modifier.height(10.dp))
                    Text(n, style = MaterialTheme.typography.labelSmall, color = SkyBlueDeep)
                }

                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 手动压缩：⚠️ 它会**改变下一轮请求的前缀**，缓存那一次必然碎 —— 所以是手动
                    // v0.48.0：`ask` 模式到阈值时**高亮**它（提示走这里，不走模态弹窗）
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(
                                when {
                                    compressing -> FieldFill
                                    compressSuggested && compressMode == COMPRESS_MODE_ASK ->
                                        SkyBlueDeep.copy(alpha = 0.18f)
                                    else -> BrandBlueSoft
                                },
                            )
                            .clickable(enabled = !compressing) { confirmCompress = true }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = if (compressing) "正在压缩…" else "压缩更早的记录",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (compressing) TextMuted else SkyBlueDeep,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        "关闭",
                        style = MaterialTheme.typography.labelLarge,
                        color = TextMuted,
                        modifier = Modifier.clickable {
                            vm.dismissCompressNote()
                            onDismiss()
                        }.padding(8.dp),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "压缩只影响发给Ta的那一份；聊天记录一条都不会丢。",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                )

                if (confirmCompress) {
                    ConfirmCompressDialog(
                        onConfirm = {
                            confirmCompress = false
                            vm.summarizeContext(sessionId)
                        },
                        onDismiss = { confirmCompress = false },
                    )
                }
            }
        }
    }
}

/**
 *
 * ⚠️ 只在**还有更早的记录**时才出现（由调用方判断）—— 常驻的话，
 * 一段刚开的对话顶上也会永远挂着一行"正在读取"，那是个假动作。
 * ⚠️ 它**不是加载动画**：本地 Room 查询快到肉眼看不见，画个转圈只会闪一下。
 * 这行的作用是把"上面还有东西"这件事说清楚（用户往上翻时知道自己在翻历史）。
 */
/**
 * 手动「加载更早」时，加载态**最短**展示多久（v0.51.0）。
 *
 * 用户的原话是「下滑刷新 **1 秒** 出更多旧内容」。之所以需要它：
 * 本地数据是**瞬时**的（不像网络请求要等），不放这一下，指示条会闪一下就没了 ——
 * 用户看不到任何反馈，只会以为"点了没反应"。
 *
 * ⚠️ 它是**下限**不是固定延迟：真正耗时的部分超过 1 秒时不再叠加。
 */
private const val EARLIER_LOADING_MIN_MS = 1_000L

/**
 * 「以上是更早的消息」边界线（v0.51.0）。
 *
 * ## 为什么是一整条线，而 TimeDivider 是胶囊
 * 两者是**不同性质**的东西：
 * - [TimeDivider] 回答「这是什么时候」—— 它是一个**标签**，所以做成胶囊贴在中间；
 * - 这条回答「内容的边界在这」—— 它是一条**界线**，所以左右各拉一条横线夹住文字。
 *
 * 用同一种形状会让两者读起来像同一件事，而"时间变了"与"这一页到头了"
 * 该被一眼区分开。
 *
 * ⚠️ 只在 `index == 0 && hasEarlier` 时出现 —— 见调用点的注释（那是"边界"的判据）。
 */
@Composable
private fun EarlierBoundaryLine() {
    val lineColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).height(1.dp).background(lineColor))
        Text(
            text = "以上是更早的消息",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Box(Modifier.weight(1f).height(1.dp).background(lineColor))
    }
}

/**
 * 列表顶部那条「还有更早的记录」提示（v0.50.5 改文案；v0.51.0 改成可点）。
 *
 * ⚠️ **它原来写的是「正在读取记忆…」** —— 一句会把人带偏的话：
 * 它跟"记忆"没有任何关系，它就是"上面还有更早的消息没放出来"。
 * 用户因此说"从来没看到过任何加载提示" —— 他其实看到过，只是认不出那是加载。
 *
 * ## 它什么时候才出现
 * 由 [ChatWindow.showEarlierIndicator] 决定：只有 **总数 > 一页（50）** 时才有它。
 * 所以消息不满 51 条的会话**永远看不到**这条 —— 那不是没做，是没有更早的可加载。
 *
 * ## v0.51.0：加可点击兜底
 * 自动加载要求"滚到顶"这件事被 `firstVisibleItemIndex <= 1` 判出来；
 * 而它依赖布局信息，在**极长列表**或**首帧未定位完**的时刻可能判不出来。
 * 给一条能点的路，用户就永远有办法拿到更早的记录 —— 不必去理解手势。
 */
@Composable
private fun LoadingEarlierRow(onLoadEarlier: () -> Unit, loading: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !loading, onClick = onLoadEarlier)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (loading) "正在加载更早的消息…" else "加载更早的消息",
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
        )
    }
}

/**
 * 表情包**独立成行**（v0.61.23，用户 2026-10-05）。
 *
 * 左侧是她的头像（36dp + 8dp 间距 = 44dp —— 与 `AssistantBubbleStack` 里
 * 消息头像的「头像 + 间距」同宽，所以图气泡与她的文字气泡**左边缘对齐**），
 * 右边是一枚固定上限的图气泡。
 *
 * ## 为什么要独立成行
 * 以前表情包画在消息 item 内部，图**异步解码**会把**整条 item** 撑高 ——
 * 于是需要一串"解码完再贴一次底"的补丁，还出过"往上翻历史被拽回最新一条"。
 * 独立成行后，图只影响**自己这一行**的高度，消息行的布局纹丝不动。
 */
@Composable
private fun EmojiRow(path: String, avatarPath: String?, onLoaded: () -> Unit = {}) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        YukiAvatar(size = 36.dp, path = avatarPath)
        Spacer(Modifier.width(8.dp))
        EmojiBubble(path = path, onLoaded = onLoaded)
    }
}

@Composable
private fun EmojiBubble(path: String, onLoaded: () -> Unit = {}) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) { ImageStore.load(path)?.asImageBitmap() }
    }
    val bmp = bitmap ?: return

    // ⚠️ 解码完成 → 这条 item 的高度到此才算定下来（v0.44.4）。
    // 外面据此刻把列表重新贴到底：图片是异步加载的，"滚到底"早在它出现之前就执行完了，
    // 用户看到的就是"她发了表情包时，列表停在图出现之前的位置"。
    // ⚠️ 用 LaunchedEffect 而不是在组合体里直接调 onLoaded —— 后者是副作用，
    //    每次重组都会再触发一次。
    LaunchedEffect(path, bmp) { onLoaded() }

    // ⚠️ 对齐与头像由外面的 `EmojiRow` 负责（v0.61.23：表情包独立成行）——
    //    这里只画"一枚图气泡"本身。
    Surface(
            shape = RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp),
            color = SnowSurface,
            border = BorderStroke(1.dp, FrostLine),
            shadowElevation = 1.dp,
        ) {
            Image(
                bitmap = bmp,
                contentDescription = "表情包",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .widthIn(max = 160.dp)
                    .heightIn(max = 160.dp)
                    .padding(3.dp)
                    .clip(RoundedCornerShape(15.dp)),
            )
        }
}

/**
 * **她的失败气泡**（H4：错误码对话化）。
 *
 * ## 为什么不是一颗胶囊
 * 请求失败原先只走灵动岛：顶部飘过一行"认证失败（401）：API Key 无效…"，
 * 对话就此断在半空。可失败的对面是**她** —— 用户要看的是"她告诉我出了什么事"，
 * 而不是"应用弹了个错"。
 *
 * 所以这里画成她的气泡（同样的左对齐、同样的左下收角）：说话的是 [spoken]，
 * [detail] 是给用户自查用的技术摘要，压到最小一档字号。
 *
 * ## 两条硬约束
 * - **不写库**：它不是 `messages` 里的消息。`messages` 是下一轮请求的前缀，
 *   写进去模型就会看到"自己刚说过余额不足"（用户 2026-09-28 选定的方案 B）。
 * - **不透明底**：用 [DangerRoseBg] 实色，而不是半透明的红 ——
 *   本项目栽过三次"浅底上的半透明元素隐形"，不透明的才不会消失。
 *
 * ⚠️ 渲染未经真机验证（本机无设备）。
 */
@Composable
private fun FailureBubble(spoken: String, detail: String, personaAvatar: String?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        // 头像照历史气泡那一套（36 + 8）。
        // ⚠️ 上一版这里是 `Spacer(44.dp)`：左缘对得齐，但**没有头像** ——
        // 于是这枚气泡看起来"不像她说的"，而它的全部意义恰恰是
        // "由她告诉用户出了什么事"（用户 2026-09-28 指出）。
        YukiAvatar(size = 36.dp, path = personaAvatar)
        Spacer(Modifier.width(8.dp))
        Surface(
            modifier = Modifier.widthIn(max = 300.dp),
            shape = assistantBubbleShape(),
            color = DangerRoseBg,
            border = BorderStroke(1.dp, DangerRose.copy(alpha = 0.35f)),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(
                    text = spoken,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary,
                )
                if (detail.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                    )
                }
            }
        }
    }
}

/**
 * **发送**按钮 —— 在输入行的**最右侧**。
 *
 * ⚠️ 位置改过一次：原先它是「输入框下方靠左的一颗小胶囊」（按上一版规格做的）。
 * 用户 2026-09-28 的原话：「发送按钮应该是输入框的**最右侧**才人性化」——
 * 这个判断是对的：拇指从键盘抬起后最近的位置就是右下角，而左下那颗要横跨整个输入框。
 *
 * ⚠️ 形态仍是小胶囊（不是圆形）—— 本轮只改了**位置**这一件事。
 * 形态要不要跟着换成圆钮，等真机上看着定（不必一次改两件）。
 *
 * ⚠️ 用户规格写的是"输入框下方靠左放置小型胶囊按钮"，**没有说它是什么**。
 * 我判断它是**发送**，理由：右侧那一排要留给语音类圆钮，发送惯常待的位置被占了；
 * 而发送是这一屏最高频的动作，不该被挤到边角。
 * **若你要的是别的（比如「思考开关」），改这一个函数就够了。**
 *
 * ⚠️ 禁用态用浅灰底 + 灰字，而不是"半透明的主色"：半透明铺在浅底上会糊成一片
 *（本项目栽过三次的那族问题的另一种形态）。
 *
 * ⚠️ 它在**正在生成**时会变成「停止」（H3）：同一个位置、同一颗胶囊，
 * 底色换成不透明的浅红 [DangerRoseBg]，字面换成"停止"。
 * 而且它**不只是换个样子** —— 点它会走二次确认，不是直接打断。
 */
@Composable
private fun SendButton(
    enabled: Boolean,
    busy: Boolean,
    onSend: () -> Unit,
    /** 正在生成时按它：由界面弹二次确认后调 `vm.interrupt()`（H3） */
    onInterrupt: () -> Unit,
) {
    // ⚠️ 原来 busy 时画的是**一颗转圈**，而且不可点。用户真机验出两件事：
    //    ① 流没真正收尾时那颗圈会一直转，看起来就像"卡住了"；
    //    ② 想停停不掉 —— 屏幕上根本没有"停"这个动作。
    //    现在同一个位置换成可点的「停止」：转圈说的是"在忙"，
    //    而「停止」说的是**"在忙，且你可以叫停"** —— 后者才是这一屏的真实状态。
    Surface(
        shape = RoundedCornerShape(percent = 50),
        color = when {
            busy -> DangerRoseBg
            enabled -> SkyBlue
            else -> FieldFill
        },
        modifier = Modifier.clickable(enabled = busy || enabled) {
            if (busy) onInterrupt() else onSend()
        },
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (busy) {
                Text(
                    text = "停止",
                    style = MaterialTheme.typography.labelMedium,
                    color = DangerRose,
                )
            } else {
                Text(
                    text = "发送",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (enabled) Color.White else TextMuted,
                )
            }
        }
    }
}

/* ═══════════════════════ 待发图片 ═══════════════════════ */

@Composable
private fun PendingImageChip(
    dataUrl: String,
    onRemove: () -> Unit,
    /** 点缩略图 → 看大图（v0.61.14，用户要求「点击加号选择的图片也支持预览」） */
    onPreview: () -> Unit,
) {
    Box(Modifier.size(64.dp)) {
        Surface(
            // ⚠️ 可点 → 看大图。`clip` 放在 `clickable` **之前**，水波纹才会被圆角裁住
            //（顺序反了会看到方角水波 —— 与思考气泡那个是同一条规矩）。
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(10.dp))
                .clickable { onPreview() },
            shape = RoundedCornerShape(10.dp),
            color = IceCyanSoft,
            border = BorderStroke(1.dp, FrostLine),
        ) {
            // 显示**图本身**，不是 "JPEG" 三个字母（用户报的「只有图片格式」）
            DataUrlThumb(
                dataUrl = dataUrl,
                contentDescription = "待发送的图片",
                modifier = Modifier.fillMaxSize(),
            )
        }
        IconButton(
            onClick = onRemove,
            modifier = Modifier.size(22.dp).align(Alignment.TopEnd),
        ) {
            Icon(
                YukiIcons.Close,
                contentDescription = "移除这张图",
                tint = DangerRose,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/* ═══════════════════════ 侧边栏 ═══════════════════════ */

@Composable
private fun SessionDrawer(
    sessions: List<Session>,
    personas: List<Persona>,
    /** 会话 id → 输入框草稿（v0.61.14）：列表里要显示「[草稿] 内容」 */
    drafts: Map<String, String>,
    activeId: String,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onNew: () -> Unit,
    onOpenPersonas: () -> Unit,
) {
    ModalDrawerSheet(
        drawerContainerColor = SnowSurface,
        modifier = Modifier.widthIn(max = 300.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Yuki 初雪", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("人机恋 · 只做 DeepSeek", style = MaterialTheme.typography.labelSmall, color = TextMuted)
        }
        HorizontalDivider(color = FrostLine)

        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            TextButton(onClick = onNew) { Text("＋ 新建对话") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onOpenPersonas) { Text("人设") }
        }
        HorizontalDivider(color = FrostLine)

        if (sessions.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("还没有对话", style = MaterialTheme.typography.bodySmall, color = TextMuted)
            }
        }

        LazyColumn {
            items(sessions, key = { it.id }) { s ->
                val p = personas.firstOrNull { it.id == s.personaId }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (s.id == activeId) IceCyanSoft else SnowSurface)
                        .clickable { onSelect(s.id) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = s.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (s.id == activeId) FontWeight.SemiBold else FontWeight.Normal,
                            color = TextPrimary,
                        )
                        Text(
                            // ⚠️ 有草稿就显示「[草稿] 内容」（v0.61.14，用户要求）：
                            //    用户在输入框写了东西、没发就退出会话，列表得看得出来 ——
                            //    否则他会以为"我写的那段没了"。
                            //    ⚠️ 只在**这个会话**有草稿时替换预览（别的会话照旧显示最后一句）。
                            text = drafts[s.id].orEmpty().takeIf { it.isNotBlank() }?.let {
                                "[草稿] " + it.replace('\n', ' ').trim().take(28)
                            } ?: ((p?.displayName?.let { "$it · " } ?: "") + s.preview),
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                        )
                    }
                    Text(s.cacheSummary, style = MaterialTheme.typography.labelSmall, color = SkyBlueDeep)
                    IconButton(onClick = { onDelete(s.id) }) {
                        Icon(
                            YukiIcons.Delete, contentDescription = "删除",
                            tint = TextMuted, modifier = Modifier.size(16.dp),
                        )
                    }
                }
                HorizontalDivider(color = FrostLine.copy(alpha = 0.5f))
            }
        }
    }
}

/* ═══════════════════════ 小组件 ═══════════════════════ */

/**
 * 顶栏里的「磨砂控件」（v0.61.28，用户按设计稿要求）。
 *
 * ## 它和普通 IconButton 的区别
 * · **没有自己的纯色底**（不是"灰色圆片"）—— 底板用的就是**顶栏那份模糊**
 *   （同一个 `contentLayer`），所以滚动时控件里的纹理跟着聊天画面**实时刷新**，
 *   和顶栏连成一片，而不是一块贴上去的色块；
 * · **图标单独一层**、不受模糊影响（`Modifier.blur` 只作用在底板那层）；
 * · 热区（40~44 × 40dp）**大于图标本身**（20dp）；
 * · 圆形给「返回」，胶囊给右侧功能键 —— 两侧同一套观感。
 *
 * ⚠️ 每个控件都会把 `contentLayer` 重绘一次（顶栏一次 + 每个控件一次）。
 *    三个控件 = 四次同量级重绘，可接受（第三方 haze 库也是这么做的）。
 * ⚠️ Android 12 以下 `Modifier.blur` 是 no-op → 底板退化成"直接透出内容"，
 *    不糊但也不崩。
 */
@Composable
private fun FrostIconButton(
    onClick: () -> Unit,
    contentLayer: GraphicsLayer,
    contentDescription: String? = null,
    icon: ImageVector? = null,
    /** 自定义内容（上下文占用环用它；给了它就不画 [icon]） */
    content: (@Composable () -> Unit)? = null,
) {
    // ⚠️ v0.61.29（用户问题 1/5）：**统一 40×40 圆形** —— 原来返回是 40dp 圆、
    //    右侧是 44dp 胶囊，"左右视觉重量不一致"是用户提的头号问题。
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // ① 底：顶栏那份模糊的副本（这块圆片自己的玻璃）
        Box(
            Modifier
                .matchParentSize()
                .blur(10.dp)
                .drawWithContent { drawLayer(contentLayer) },
        )
        // ② ⚠️ v0.61.31：改「iOS 液态玻璃」观感（用户要求）+ 整体**更透** ——
        //    与上一版的三点不同：
        //      · **不再通铺白渐变**（那个把整体压"实"了），只在**左上角**留一道高光；
        //      · 边缘高光**更亮**（描边 0.55 → 0.72）—— 液态玻璃的辨识度主要来自这条亮边；
        //      · 其余部分**完全透出**模糊后的背景。
        //    ⚠️ 如实说明：Apple 那层**折射/色散**（边缘把背景微微扭曲）依赖 shader（Metal），
        //        Compose 侧做不到 —— 这里能还原的是"高透明 + 亮边 + 顶部光晕"。
        //    （用 `linearGradient` 是刻意的：它默认从左上到右下，且**不含像素坐标**，
        //      换屏幕密度不会走样；`radialGradient` 得写死像素半径。）
        Box(
            Modifier
                .matchParentSize()
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(Color.White.copy(alpha = 0.24f), Color.Transparent),
                    ),
                ),
        )
        Box(
            Modifier
                .matchParentSize()
                .clip(CircleShape)
                .border(1.dp, Color.White.copy(alpha = 0.72f), CircleShape),
        )
        // ③ 图标：最上层，不受模糊与渐变影响
        if (content != null) {
            content()
        } else if (icon != null) {
            Icon(
                icon,
                contentDescription = contentDescription,
                // 深灰线性图标（用户要求）；线条粗细由图标本身决定
                tint = TextSecondary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * 顶栏右侧的**一组**功能键 —— 它们共用**一整块**胶囊磨砂底板。
 *
 * ⚠️ 用户 2026-10-30 明确：「三个功能按钮是在一块的胶囊啊」。
 *    与"三个独立圆"相比，这样做顺带解决两件事：
 * · 底纹**只重绘一次**（而不是三次），每帧少两次画面重放；
 * · 三个图标落在**同一块**玻璃上，纹理连续 —— 独立圆各画一份，接缝处能看出错位。
 */
@Composable
private fun FrostPillGroup(
    contentLayer: GraphicsLayer,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .height(40.dp)
            .clip(shape),
    ) {
        // ① 底板：整块胶囊一次模糊
        Box(
            Modifier
                .matchParentSize()
                .blur(10.dp)
                .drawWithContent { drawLayer(contentLayer) },
        )
        // ② iOS 液态玻璃 —— 与左侧返回键**完全同一套**：左上高光 + 亮边，其余全透。
        Box(
            Modifier
                .matchParentSize()
                .clip(shape)
                .background(
                    Brush.linearGradient(
                        listOf(Color.White.copy(alpha = 0.24f), Color.Transparent),
                    ),
                ),
        )
        Box(
            Modifier
                .matchParentSize()
                .clip(shape)
                .border(1.dp, Color.White.copy(alpha = 0.72f), shape),
        )
        Row(
            Modifier.height(40.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/** 胶囊里的**一个**图标键 —— 它自己没有底（底是整块胶囊的）。 */
@Composable
private fun FrostPillItem(
    onClick: () -> Unit,
    contentDescription: String? = null,
    icon: ImageVector? = null,
    /** 自定义内容（上下文占用环用它；给了它就不画 [icon]） */
    content: (@Composable () -> Unit)? = null,
) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (content != null) {
            content()
        } else if (icon != null) {
            Icon(
                icon,
                contentDescription = contentDescription,
                tint = TextSecondary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun EmptyState(
    configured: Boolean,
    hasPersona: Boolean,
    personaCount: Int,
    onCreate: () -> Unit,
    onOpenPersonas: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    // ⚠️ 用户 2026-10-05：「不应该有『初雪』二字，也不要『你有 X 个人设』那一行，
    //    『新建对话』也不能有」→ 三样全去掉，整屏重做。
    //    `personaCount` 参数保留（调用方还在传），但**不再出现在界面上**。
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 空态的视觉主体：双层圆底 + 主色图标。
        // 与 `components/EmptyHint` 同一套语言（浅圆底 + 主色图标 = 空页里唯一的"点"），
        // 但尺寸更大 —— 它独占整屏，小圆会显得这片空白没着落。
        Box(
            modifier = Modifier
                .size(104.dp)
                .clip(CircleShape)
                .background(BrandBlueSoft),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(SnowWhite),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    YukiIcons.ChatBubble,
                    contentDescription = null,
                    tint = BrandBlue,
                    modifier = Modifier.size(34.dp),
                )
            }
        }
        Spacer(Modifier.height(22.dp))

        val (title, desc) = when {
            !configured -> "还差一步：填 API Key" to "去「设置」填入你自己的 DeepSeek Key。密钥只留在这台设备上。"
            !hasPersona -> "先创建一个人设" to "人设决定角色是谁。填好昵称与角色设定后，就能开始对话。"
            // 不再暴露"你有几个人设"，也不给「新建对话」按钮 ——
            // 输入框就在这一屏下面，那才是开始对话的地方。
            else -> "可以开始了" to "在下面的输入框里，写下你们的第一句话吧"
        }
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            desc,
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted,
            textAlign = TextAlign.Center,
        )

        // 只有「缺配置 / 缺人设」这两种**必须离开本页**的情况才给按钮 ——
        // 配好之后这一屏不该再挡路（原来那个「新建对话」正是多余的）。
        val guide: Pair<String, () -> Unit>? = when {
            !configured -> "去设置" to onOpenSettings
            !hasPersona -> "创建人设" to onOpenPersonas
            else -> null
        }
        if (guide != null) {
            Spacer(Modifier.height(22.dp))
            TextButton(
                onClick = guide.second,
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.textButtonColors(
                    containerColor = SkyBlueDeep,
                    contentColor = SnowWhite,
                ),
                contentPadding = PaddingValues(horizontal = 22.dp, vertical = 10.dp),
            ) {
                Text(
                    guide.first,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/**
 * 历史气泡的最大宽度。
 *
 * ⚠️ 与流式气泡（300dp）**刻意不同** —— 这是既有的观感，本轮不顺手改它：
 * 这一轮要修的是「连发落库后塌成一枚」，宽度是另一件事，
 * 混在一起改会让真机验收无从判断差异是哪一条改动带来的。
 */
private val HISTORY_BUBBLE_MAX_WIDTH = 264.dp

/**
 * data URL（`data:image/jpeg;base64,...`）→ Bitmap。
 * 解不出来就返回 null —— 一张坏图不该让整个预览崩掉（同类先例见 `SessionMapper.decodeImages`）。
 *
 * ⚠️ **必须降采样**：`ImageStore` 的注释写得很直白 ——「这不是优化，是**别崩**」。
 * 入库时图已被压到 `maxSide` 以内，但那是"入库那一刻"的保证；老数据/异形图仍可能很大，
 * 而 `decodeByteArray` 不解采样会直接按原尺寸开内存（4000×3000 ≈ 48MB，够崩一次）。
 * 这里照 `ImageStore` 的范式：先读尺寸 → 2 的幂逼近 → 再真解。
 */
private fun dataUrlToBitmap(
    dataUrl: String,
    /** 最长边上限（px）。缩略图传小值，能省下实打实的内存与解码时间。 */
    maxSide: Int = PREVIEW_MAX_SIDE,
): android.graphics.Bitmap? = runCatching {
    val comma = dataUrl.indexOf(',')
    if (comma < 0) return null
    val bytes = android.util.Base64.decode(
        dataUrl.substring(comma + 1),
        android.util.Base64.DEFAULT,
    )
    if (bytes.isEmpty()) return null
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    android.graphics.BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
        android.graphics.BitmapFactory.Options().apply { inSampleSize = sample },
    )
}.getOrNull()

/** 预览图的最长边上限（px）。与 `ImageStore` 的入库口径同量级，够看清细节也不至于开大内存。 */
private const val PREVIEW_MAX_SIDE = 1080

/**
 * 缩略图的最长边上限（px）。
 *
 * 气泡/待发区里的图最大也就 96dp（≈ 3 倍密度下不到 300px），给 320 足够清晰，
 * 却比 1080 少解出近十倍像素 —— 一屏十几条时这是实打实的差别。
 */
private const val THUMB_MAX_SIDE = 320

/**
 * data URL 的**缩略图**（待发图片条与聊天气泡共用）。
 *
 * ## 为什么要它
 * 用户报过两处「看得见图、看不见图」：
 * · 待发区原来只画 `PNG` / `JPEG` 这样的**格式文字**（看着像个错误标签）；
 * · 发出去的图在对话里只显示「N 张图片 · 点开看」——**内容完全看不到**。
 * 两处都改成真缩略图。
 *
 * ## 为什么仍然要小心
 * 图是 base64 内联的（一条几十万字符），解码是实打实的 CPU + 内存。
 * 所以：① 解码放 IO；② 一律降采样到 [THUMB_MAX_SIDE]；③ `produceState` 以
 * `dataUrl` 为 key，同一张图在一次组合里只解一次。列表是 LazyColumn，
 * 只有可见项会组合 —— 不会把整段历史都解一遍。
 */
@Composable
private fun DataUrlThumb(
    dataUrl: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, dataUrl) {
        value = withContext(Dispatchers.IO) {
            dataUrlToBitmap(dataUrl, THUMB_MAX_SIDE)?.asImageBitmap()
        }
    }
    val image = bitmap
    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
    } else {
        // 解不出来（坏图 / 老数据）也要给个占位，别留一块空白
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                text = "图片",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
            )
        }
    }
}

/**
 * 图片**全屏预览**（v0.61.14，用户要求：「发出去的图片支持预览，点击加号选择的图片也支持预览」）。
 *
 * 交互：点画面任意处关闭 —— 看大图时多余的操作都是噪音。
 * ⚠️ 解码放 `Dispatchers.IO`（照 `YukiAvatar` 的做法）：base64 解码 + 位图解码是实打实的
 * 主线程开销，几十 KB 的图就够掉一帧。
 */
@Composable
private fun ImagePreviewDialog(dataUrl: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        val bitmap by produceState<ImageBitmap?>(initialValue = null, dataUrl) {
            value = withContext(Dispatchers.IO) { dataUrlToBitmap(dataUrl)?.asImageBitmap() }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { onDismiss() },
            contentAlignment = Alignment.Center,
        ) {
            val bmp = bitmap
            if (bmp != null) {
                Image(
                    bitmap = bmp,
                    contentDescription = "图片预览",
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Text(
                    text = "这张图读不出来",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMuted,
                )
            }
        }
    }
}

/** 缩略图最多平铺几张；其余折成「+N」。3 张 × 72dp + 间距正好塞进气泡可用宽度（236dp）。 */
private const val TAG_VISIBLE_IMAGES = 3

/**
 * 消息里的**图片缩略图**（用户气泡与她的气泡共用同一份）。
 *
 * ⚠️ 这里原来只显示一行「N 张图片 · 点开看」——用户报「发送给 ai 的图片也可以在
 *    对话界面看到图片内容」，说的就是它。现在直接平铺缩略图，点任一张看大图。
 *
 * ⚠️ 只画内容、**不回传模型**：`msg.images` 的 base64 早在发送那一刻就进了请求体
 *    （那是它该在的地方）；这里只是把同一份已存下来的数据画出来，请求体一个字节不动。
 */
@Composable
private fun ImageTag(images: List<String>, onPreview: (String) -> Unit) {
    if (images.isEmpty()) return
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        images.take(TAG_VISIBLE_IMAGES).forEach { url ->
            Surface(
                // ⚠️ `clip` 放在 `clickable` **之前**，水波纹才会被圆角裁住
                //（顺序反了会看到方角的水波，与思考气泡那个同源）。
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onPreview(url) },
                shape = RoundedCornerShape(10.dp),
                color = IceCyanSoft,
                border = BorderStroke(1.dp, FrostLine),
            ) {
                DataUrlThumb(
                    dataUrl = url,
                    contentDescription = "图片，点击看大图",
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        if (images.size > TAG_VISIBLE_IMAGES) {
            Text(
                text = "+${images.size - TAG_VISIBLE_IMAGES}",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
            )
        }
    }
}

/**
 * 一条助手回复的**气泡栈** —— 把回复画成 1..N 枚气泡。
 *
 * ## 它存在的理由（这轮修的那个缺陷）
 * 「连发」的拆句此前**只做在流式气泡那一条路径上**；落库后改由历史气泡渲染，
 * 那条路径没拆 —— 于是用户看到「她说着说着，一落库就变回一大坨」。
 * 把「拆句判定」与「外壳样式」都收进这一处，两条路径就再也分不了家。
 *
 * ## 缓存
 * 这里**只影响渲染**：气泡文本由回复正文现场切分，历史与请求体一个字节都不动 ——
 * 所以拆多枚**不会**让任何会话的缓存前缀失效。
 *
 * @param lines 气泡文本（v0.61.4 起：流式回复按句拆成几枚 —— 见 [BubbleSplit.bubbles]）
 * @param maxWidth 单枚气泡的最大宽度
 * @param leading 只挂在**第一枚**内部顶部的前置内容（思考块 / 图片标签）——
 *                它是「这一条回复」的属性，不该跟着每一枚重复
 * @param body 单枚正文的渲染方式。这是流式（打字机）与历史（Markdown）**唯一**
 *             应当不同的地方，所以做成参数而不是分支
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AssistantBubbleStack(
    lines: List<String>,
    maxWidth: Dp,
    /** 她的人设头像（没设就回落雪晶）。⚠️ **每一枚**气泡旁边都画一个 —— 理由见下 */
    personaAvatar: String? = null,
    shadowElevation: Dp = 0.dp,
    bubbleModifier: Modifier = Modifier,
    /**
     * 长按**某一枚**气泡（回传该枚文本与下标）。
     * null = 这一栈不可长按（流式气泡：还没落库，"这一条"还没有身份）。
     * ⚠️ v0.61.5 用户要求：「每个分段气泡的消息独立出来，可以单独复制那个气泡的消息」。
     */
    onBubbleLongPress: ((String, Int) -> Unit)? = null,
    leading: @Composable () -> Unit = {},
    body: @Composable (line: String, index: Int) -> Unit,
    /**
     * 气泡不透明度（0..1，v0.61.57）。
     *
     * ⚠️ 默认 1f = 不透明 = 与加这个功能之前逐像素相同 —— 老用户升级零变化。
     *    这里和 `MessageBubble` 各自持有一份（流式气泡也要跟着变，
     *    否则"发出前"和"发出后"观感会不一样）。
     */
    bubbleAlpha: Float = 1f,
) {
    Column(
        Modifier
            .fillMaxWidth()
            // ⚠️ 高度变化**加动画**（v0.61.14，用户报「每拆出一枚气泡抖一下」）：
            //    流式输出每拆出一枚，这个 Column 就**突然**高一截（头像 36dp + 间距 + 气泡），
            //    列表项高度突变 —— 视觉上就是"抖一下"。让高度变化走一小段默认动画，
            //    观感从"突跳"变成"长出来"。
            //    ⚠️ 只治**离散的**高度突变（拆枚）；打字机逐字那种连续增长本来就不明显。
            .animateContentSize(),
    ) {
        lines.forEachIndexed { index, line ->
            if (index > 0) Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.Top,
            ) {
                // ⚠️ 头像是在**这里**画的，不在调用方（v0.44.2 改）。
                // 上一版只在整条回复的左边放了**一个** —— 连发拆成四枚时，
                // 看起来像"一个头像下面挂了四句没主的话"。
                // 用户拿着截图指出：每一枚都该有头像（像四条并列的消息）。
                YukiAvatar(size = 36.dp, path = personaAvatar)
                Spacer(Modifier.width(8.dp))
                Surface(
                    modifier = bubbleModifier
                        .widthIn(max = maxWidth)
                        .then(
                            if (onBubbleLongPress != null) {
                                Modifier.combinedClickable(
                                    onClick = {},
                                    onLongClick = { onBubbleLongPress(line, index) },
                                )
                            } else {
                                Modifier
                            },
                        ),
                    // 助手气泡：靠左，左下角收 4dp（= 设计系统的 assistantBubbleShape）
                    shape = assistantBubbleShape(),
                    // v0.61.57：底色乘上用户设的不透明度（默认 1f = 与原来逐像素相同）
                    color = SnowSurface.copy(alpha = bubbleAlpha.coerceIn(0f, 1f)),
                    border = BorderStroke(1.dp, FrostLine),
                    shadowElevation = shadowElevation,
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        if (index == 0) leading()
                        body(line, index)
                    }
                }
            }
        }
    }
}

/**
 * 「‹ 2/3 ›」——**历史版本切换**（v0.61.11，用户要求）。
 *
 * 重新生成会留下旧版本（[ChatMessage.superseded]），这一行让用户在当前版与旧版之间切换。
 *
 * ⚠️ 切换是**纯本地**的：只改这一屏显示什么 —— **不动历史、不进请求体、不影响缓存**；
 * 显示旧版时会明说「历史版本」，免得用户以为消息被改回去了。
 */
@Composable
private fun VersionSwitchRow(
    count: Int,
    index: Int,
    isHistory: Boolean,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.padding(start = 44.dp, top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VersionStepButton(label = "‹", enabled = index > 0) { onSelect(index - 1) }
        Text(
            text = "${index + 1}/$count",
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
        VersionStepButton(label = "›", enabled = index < count - 1) { onSelect(index + 1) }
        if (isHistory) {
            Text(
                text = "历史版本（仅供查看 / 复制）",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

/** 版本切换的左右小按钮（[VersionSwitchRow] 用）。 */
@Composable
private fun VersionStepButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.titleMedium,
        color = if (enabled) SkyBlueDeep else TextMuted.copy(alpha = 0.35f),
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/**
 * 流式气泡（开发文档 §36）：正文逐字追加 + 末尾闪烁光标。
 *
 * 与历史气泡的唯一区别是**正文怎么画**（打字机 vs Markdown）——
 * 枚数、外形、枚间间距都与历史气泡共用 [AssistantBubbleStack]。
 */
@Composable
private fun StreamingBubble(
    content: String,
    reasoning: String?,
    autoCollapse: Boolean,
    waiting: Boolean,
    /** 连续 10 秒没有新内容（见 [ChatViewModel.stalled]）—— 气泡下方给一行「她好像卡住了…」 */
    stalled: Boolean = false,
    /** 打字速度档位（设置项）—— 一路传到 [TypewriterText] */
    typeSpeed: Int = TYPE_SPEED_NORMAL,
    /** 回复的呈现方式（设置项）：流式（真流式）/ 非流式（一次拿完整响应） */
    sendMode: String = SEND_MODE_STREAM,
    /** 分段气泡（设置项，v0.61.4）：流式回复按句拆成几枚 —— 见 [BubbleSplit] */
    splitBubbles: Boolean = true,
    /** 她的人设头像（v0.44.2）—— 拆出的每一枚旁边都要有一个 */
    personaAvatar: String? = null,
    /** 点开思考气泡时回调（外层滚到底，v0.61.5） */
    onReasoningExpand: () -> Unit = {},
    /**
     * 气泡不透明度（0..1，v0.61.57）。
     *
     * ⚠️ 默认 1f = 不透明 = 与加这个功能之前逐像素相同 —— 老用户升级零变化。
     *    流式气泡也要跟着变，否则"正在打字"和"打完落库"两种状态观感会不一样。
     */
    bubbleAlpha: Float = 1f,
) {
    // 分段气泡（v0.61.4）：流式回复按句拆成连续几枚（一句一枚，末尾那枚还在长）。
    // ⚠️ 拆句只影响渲染 —— 历史与请求体一个字节不动，缓存前缀不受影响。
    val lines: List<String> = remember(content, splitBubbles, sendMode) {
        BubbleSplit.bubbles(content, splitBubbles, sendMode)
    }

    // 一次性模式把档位压成 OFF —— `TypewriterText` 见到 0 会**整段直接上屏**
    //（而不是"每字等 0 毫秒"地把整段长度空转一遍）。
    //
    // ⚠️ 流式模式下**不再**让"打字速度 = 关闭"生效（v0.44.5 修）。
    // 用户报的"流式模式和一次性一样"就是这里：两个开关管了同一件事 ——
    // 只要"打字速度"那一档被设成关闭，选"流式"也会整段上屏，
    // 于是"流式 / 一次性"这两种呈现方式**看不出任何区别**。
    // 现在分工明确：**模式决定"怎么出现"，档位只决定"多快"**。
    // 想整段直接出现，请选"一次性" —— 那才是它的语义。
    val effectiveSpeed = when (sendMode) {
        SEND_MODE_INSTANT -> TYPE_SPEED_OFF
        else -> if (typeSpeed == TYPE_SPEED_OFF) TYPE_SPEED_NORMAL else typeSpeed
    }

    // ⚠️ 非流式：思考**跟着整条消息**（v0.61.9，用户要求）。它本来就没有"正在想"的过程感；
    //    独立成气泡的话，落库那一刻会从"独立"跳成"内嵌"（一次视觉跳变）。
    val reasonStandalone = sendMode == SEND_MODE_STREAM

    Column(Modifier.fillMaxWidth()) {
        // 思考独立成枚（只在流式）
        if (reasonStandalone && !reasoning.isNullOrBlank()) {
            ReasoningBubble(
                reasoning = reasoning,
                streaming = true,
                autoCollapse = autoCollapse,
                personaAvatar = personaAvatar,
                onExpanded = onReasoningExpand,
            )
            Spacer(Modifier.height(6.dp))
        }

        AssistantBubbleStack(
            lines = lines,
            maxWidth = 300.dp,
            // 流式期间也照常每枚带头像 —— 与历史气泡是同一个组件、同一份数据（v0.44.2）
            personaAvatar = personaAvatar,
            // v0.61.57：流式气泡也要跟着用户设的不透明度变 ——
            // 否则"正在打字"和"打完落库"两种状态观感会不一样（同一枚气泡换了个颜色）
            bubbleAlpha = bubbleAlpha,
            leading = {
                // 非流式：思考画在气泡内部（v0.61.9）
                if (!reasonStandalone) {
                    reasoning?.takeIf { it.isNotBlank() }?.let { thinking ->
                        ReasoningInline(
                            reasoning = thinking,
                            streaming = false,
                            autoCollapse = autoCollapse,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
            },
            body = { line, index ->
            val isLast = index == lines.lastIndex
            if (lines.size == 1 && waiting && content.isEmpty()) {
                // 首字未到：给个「她在打字」的交代，而不是一片空白
                TypingIndicator()
            } else {
                Row(verticalAlignment = Alignment.Bottom) {
                    if (line.isNotEmpty()) {
                        if (isLast) {
                            // 最新那枚**可能还在长**：逐字放 + 挂光标
                            TypewriterText(
                                text = line,
                                modifier = Modifier.weight(1f, fill = false),
                                speed = effectiveSpeed,
                            )
                        } else {
                            // 已经说完的句子：静态显示 —— 它已经"发出去了"
                            Text(
                                text = line,
                                style = MaterialTheme.typography.bodyLarge,
                                color = TextPrimary,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                        }
                    }
                    if (isLast) BlinkingCursor()
                }
            }
            },
        )

        // 「她好像卡住了…」—— 连续 10 秒没有新内容时，气泡下方给一行提醒。
        // ⚠️ 纯渲染：只读 [ChatViewModel.stalled]，不进任何消息体（缓存红线）。
        if (stalled) StallNotice()
    }
}

/**
 * 「她好像卡住了…」—— 流式中连续若干秒没有新内容时的提醒行。
 *
 * ## 它解决什么
 * 首字到了以后，界面上唯一的"她在动"信号就是那颗闪烁光标；光标会一直闪，
 * 内容却可能停住不动 —— 用户没法从闪烁里分辨"她在憋大招"还是"连接已经死了"。
 * 这一行把那个判断**由界面自己说出来**（阈值见 [ai.yuki.chuxue.data.StreamStall]）。
 *
 * ## 文案与配色
 * - 「她」是这软件的语境（人机恋 App，Ta 在跟你说话）——**不许**出现工程术语
 *   （"推理中 / 工具调用"那类，是另一个项目的话）；
 * - 用警告色 [WarnAmber]，与 [DangerRose]（真错误）区分：这只是"慢了一下"，
 *   不是"这一轮失败了"，所以不弹错误气泡、也不打断这一轮 —— 内容一到这行就消失。
 * - ⚠️ 它是**界面态**：不写 `messages`、不进请求体，退出会话即消失。
 */
@Composable
private fun StallNotice() {
    Text(
        text = "Ta 好像卡住了…",
        style = MaterialTheme.typography.bodySmall,
        color = WarnAmber,
        // 与气泡正文左对齐：头像 36dp + 间距 8dp = 44dp（见 [AssistantBubbleStack]）
        modifier = Modifier.padding(start = 44.dp, top = 6.dp),
    )
}

/** 闪烁光标：600ms 往复（文档 §36；时长见 [YukiDuration.CursorBlink]）。 */
@Composable
private fun BlinkingCursor() {
    val alpha by rememberInfiniteTransition(label = "cursor").animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(YukiDuration.CursorBlink),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "cursorAlpha",
    )
    Text(
        text = "▊",
        color = SkyBlue,
        modifier = Modifier
            .padding(start = 2.dp)
            .graphicsLayer { this.alpha = alpha },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    msg: ChatMessage,
    autoCollapse: Boolean,
    personaName: String,
    /** 她的人设头像路径；null → 组件回落自绘雪晶 */
    personaAvatar: String?,
    /** 「我」的资料（头像 + 昵称）—— 右侧头像与搜索结果共用同一份 */
    userProfile: UserProfile,
    /** 搜索刚跳过来的那一条：短暂强调。默认 false，不影响任何既有调用点。 */
    highlighted: Boolean = false,
    /** 回复的呈现方式（设置项）—— 决定**她的**回复画成几枚气泡 */
    sendMode: String = SEND_MODE_STREAM,
    /**
     * 气泡不透明度（0..1，v0.61.57 美化设置）。
     *
     * ⚠️ 默认 **1f = 不透明 = 与加这个功能之前逐像素相同** —— 老用户升级零变化。
     *    调低让背景图从气泡里透出来（用户要的"自定义气泡"那一半）。
     */
    bubbleAlpha: Float = 1f,
    /** 点开思考气泡时回调（外层滚到底，v0.61.5） */
    /** 点开思考气泡时回调（外层滚到底，v0.61.5） */
    onReasoningExpand: () -> Unit = {},
    /** 点图片标签 → 看大图（v0.61.14）。默认空实现：调用方不接也不会崩。 */
    onImagePreview: (String) -> Unit = {},
    /**
     * 长按出一条消息的菜单。
     * 参数 = **被按的那一枚气泡的文本**（v0.61.5 用户要求「单独复制那个气泡的消息」）；
     * null = 整条（用户自己的消息不分枚）。
     */
    onLongPress: (String?) -> Unit,
) {
    val fromUser = msg.role == "user"

    // 高亮描边对**这条消息的每一枚气泡**都成立 —— 按哪一枚都算按这条。
    // ⚠️ v0.61.5：她的每一枚气泡的长按由 [AssistantBubbleStack] 按枚挂上（带该枚文本）；
    //    用户消息不分枚，长按整条（传 null）。
    val userPressModifier = Modifier.combinedClickable(onClick = {}, onLongClick = { onLongPress(null) })
    val edgeColor = if (highlighted) SkyBlue else FrostLine
    val edgeWidth = if (highlighted) 1.5.dp else 1.dp

    // 显示前剥掉后台注入的附录（<appendix>/<memories>）—— 那是给模型看的记忆，
    // 不该出现在用户的气泡里。**只改显示**：数据与请求体一个字不动
    //（理由见 TranscriptText 类注释：附录进历史是缓存一致性的前提）。
    //
    // ⚠️ 收进 `remember`（v0.50.5）：这一步含**正则替换**，而它此前每次重组都重跑 ——
    //    滚动列表时等于每帧对窗口内每条消息各算一遍。key 就是它仅有的两个输入。
    // ── 历史版本切换（v0.61.11，用户要求）──
    // `versions` = 被替掉的旧版本（早的在前）+ 当前版本；`shownVersion` 是**纯本地 UI 状态**：
    // 切到旧版只改"这一屏看到什么"，**不动历史、不进请求体、不影响缓存**。
    val versions = remember(msg.superseded, msg.content) { msg.superseded + msg.content }
    // key 用 `versions`（= 内容或历史版本变了）→ 换一条消息/重新生成后自动回到当前版本。
    // ⚠️ 不能用 `msg.id`：领域层的 `ChatMessage` **没有主键**（主键只在 Room 实体上）。
    var shownVersion by remember(versions) { mutableIntStateOf(versions.lastIndex) }
    val shownText = versions.getOrNull(shownVersion) ?: msg.content

    val shownContent = remember(shownText, fromUser) {
        TranscriptText.stripAppendix(shownText).let {
            // 把情绪标签从**她的**正文里摘掉 —— 用户不该在气泡里看到 `[开心]` 这种记号。
            // ⚠️ 只动助手消息：用户自己打的方括号是他自己的字，不能替他删。
            if (fromUser) it else EmojiCategories.stripTags(it, EmojiCategories.DEFAULTS)
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        // ⚠️ 助手侧的头像**不再在这里画**（v0.44.2）：它移进了 `AssistantBubbleStack`，
        // 好让连发拆出的**每一枚**气泡旁边都有一个（用户拿着截图要求）。
        // 用户侧仍然在这儿画 —— 我说的整段只有一枚，不存在"每枚都要"的问题。

        Column(
            horizontalAlignment = if (fromUser) Alignment.End else Alignment.Start,
            // ⚠️ 只给用户侧限宽：助手侧整列现在是「44dp 头像 + 气泡」，
            // 再卡 `HISTORY_BUBBLE_MAX_WIDTH` 会把气泡挤窄。
            // 气泡自己的上限由 `AssistantBubbleStack` 的 `maxWidth` 管着，够了。
            modifier = if (fromUser) Modifier.widthIn(max = HISTORY_BUBBLE_MAX_WIDTH) else Modifier,
        ) {
            // 昵称只在助手侧显示：用户自己知道自己是谁，写上反而挤。
            if (!fromUser && personaName.isNotBlank()) {
                Text(
                    text = personaName,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    modifier = Modifier.padding(start = 4.dp, bottom = 3.dp),
                )
            }

            if (fromUser) {
                /* ── 我说的：整段一枚。连发是"Ta"的呈现方式，与我无关 ── */
                Surface(
                    modifier = userPressModifier,
                    shape = userBubbleShape(),
                    // v0.61.57：同助手侧，底色乘上用户设的不透明度（默认 1f = 零变化）
                    color = IceCyanSoft.copy(alpha = bubbleAlpha.coerceIn(0f, 1f)),
                    // 高亮态把描边换成主色、稍加粗。用描边而不是填充：
                    // 填充会把这条消息变成"另一种气泡"，看起来像另一种消息类型。
                    border = BorderStroke(edgeWidth, edgeColor),
                    // 轻拟物：极浅阴影。跟卡片同源（YukiCardSpec），但气泡更克制 ——
                    // 一屏十几条，阴影重了整页会发脏。
                    shadowElevation = 1.dp,
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        if (msg.hasImages) {
                            ImageTag(msg.images, onImagePreview)
                            if (shownContent.isNotBlank()) Spacer(Modifier.height(6.dp))
                        }
                        if (shownContent.isNotBlank()) MarkdownText(shownContent)
                    }
                }
            } else {
                /* ── 她说的：按 `sendMode` 画成 1..N 枚 ──
                   流式气泡在这里成形（v0.61.4 起流式模式按句拆枚 —— 见 [BubbleSplit]）
                   —— 落库后再也不会塌回一枚。
                   只影响渲染：`msg.content` 与请求体一个字节都不动。 */
                // ⚠️ 收进 `remember`：拆句要逐字符重切整段，不缓存会每次重组都重跑。
                //    key 就是它仅有的三个输入。
                // ⚠️ **老消息**（v0.61.6 之前，没有 splitBubbles 字段）**完全保持原样**
                //（用户要求）：整段一枚、思考画在气泡内部 —— 那两样都是它们当时的样子。
                // 新消息则按**它自己的**快照拆枚（改设置不影响已经说过的话）。
                val legacyBubbles = msg.splitBubbles == null
                // ⚠️ 思考画在哪（v0.61.9，用户要求「非流式输出的思考气泡还是和整条消息在一起」）：
                //   · 流式消息   → 独立成一枚思考气泡（v0.61.5）；
                //   · 非流式消息 → 跟整条消息在一起（它本来就是"整段一起出来"，
                //     拆一枚独立的思考气泡没有意义）；
                //   · 老消息     → 完全原样（思考在气泡内）。
                val reasonStandalone = !legacyBubbles && sendMode == SEND_MODE_STREAM
                // 判定收在 [BubbleSplit.forMessage]（唯一的渲染入口）——
                // 老消息恒单枚；新消息只认**它自己的**快照（当前设置改了也不回头重排）
                val bubbleLines = remember(shownContent, sendMode, msg.splitBubbles) {
                    BubbleSplit.forMessage(shownContent, msg.splitBubbles, sendMode)
                }
                Column(Modifier.fillMaxWidth()) {
                    // ⚠️ v0.61.57：**主动消息的标识**（用户报「AI 主动发的消息会被计为
                    //    最近一条消息的重新生成的内容，应该是独立消息气泡」）。
                    //    它本来就是独立消息（独立 `ChatMessage`），但**渲染上与普通回复
                    //    毫无区别** —— 用户看不出"这是 Ta 主动找我的"。
                    //    这里在气泡上方加一行小字，把它与"我发了话、Ta 回我"明确分开。
                    //    ⚠️ 纯渲染：不进 messages、不进请求体（缓存前缀一个字不动）。
                    if (msg.sendMode == SEND_MODE_PROACTIVE) {
                        Text(
                            text = "Ta 主动找你",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                            modifier = Modifier.padding(start = 44.dp, bottom = 4.dp),
                        )
                    }
                    // 思考独立成枚（只在流式消息上）
                    if (reasonStandalone) {
                        msg.reasoning?.takeIf { it.isNotBlank() }?.let { thinking ->
                            ReasoningBubble(
                                reasoning = thinking,
                                streaming = false,
                                autoCollapse = autoCollapse,
                                personaAvatar = personaAvatar,
                                thinkingMs = msg.thinkingMs,
                                onExpanded = onReasoningExpand,
                                // ⚠️ 长按思考气泡 = 长按**这条消息**（v0.61.15）：
                                //    用户报「开了思考模式后长按删除没反应」——
                                //    他按的多半就是这枚气泡。传 null = 整条（思考不属于某一枚正文）
                                onLongPress = { onLongPress(null) },
                            )
                            Spacer(Modifier.height(6.dp))
                        }
                    }

                    AssistantBubbleStack(
                        lines = bubbleLines,
                        maxWidth = HISTORY_BUBBLE_MAX_WIDTH,
                        // 她的人设头像：连发拆几枚，旁边就有几个（v0.44.2）
                        personaAvatar = personaAvatar,
                        shadowElevation = 1.dp,
                        // v0.61.57：历史气泡的不透明度（默认 1f = 零变化）
                        bubbleAlpha = bubbleAlpha,
                        // 长按按**枚**处理（v0.61.5）：复制的是被按的那一枚
                        onBubbleLongPress = { text, _ -> onLongPress(text) },
                        leading = {
                            // 非独立时思考画在**气泡内部**（老消息原样 / 非流式，v0.61.9）
                            if (!reasonStandalone) {
                                msg.reasoning?.takeIf { it.isNotBlank() }?.let { thinking ->
                                    ReasoningInline(
                                        reasoning = thinking,
                                        streaming = false,
                                        autoCollapse = autoCollapse,
                                        thinkingMs = msg.thinkingMs,
                                    )
                                    Spacer(Modifier.height(8.dp))
                                }
                            }
                            // 图片附件：显示为文件名标签（历史里的图会随消息重发，
                            // 但不在气泡里展开 base64，避免长对话把列表拖垮）
                            if (msg.hasImages) {
                                ImageTag(msg.images, onImagePreview)
                                if (shownContent.isNotBlank()) Spacer(Modifier.height(6.dp))
                            }
                        },
                        body = { line, _ ->
                            if (line.isNotBlank()) MarkdownText(line)
                        },
                    )

                    // 历史版本切换（v0.61.11，用户要求）：只在这条回复被重新生成过时出现
                    if (versions.size > 1) {
                        VersionSwitchRow(
                            count = versions.size,
                            index = shownVersion,
                            isHistory = shownVersion < versions.lastIndex,
                            onSelect = { shownVersion = it },
                        )
                    }
                }
            }
        }

        if (fromUser) {
            Spacer(Modifier.width(8.dp))
            UserAvatar(
                size = 36.dp,
                path = userProfile.avatarPath,
                fallbackText = userProfile.displayName,
            )
        }
    }
}

/* ═══════════════════════ Markdown 渲染 ═══════════════════════ */

/**
 * 把一段正文按 Markdown 基础语法渲染（加粗 / 斜体 / 行内代码 / 代码块）。
 *
 * 解析交给 [MarkdownLite]（纯函数、边界有单测），这里只做样式映射 ——
 * 于是"星号会不会被吃掉"这类问题在 JVM 上就能验完，不必等真机。
 */
@Composable
private fun MarkdownText(text: String) {
    val blocks = remember(text) { MarkdownLite.parse(text) }
    Column {
        blocks.forEachIndexed { index, block ->
            if (index > 0) Spacer(Modifier.height(6.dp))
            when (block) {
                is MarkdownLite.Block.Paragraph -> Text(
                    text = annotatedOf(block.spans),
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextPrimary,
                )
                is MarkdownLite.Block.Code -> CodeBlock(block)
            }
        }
    }
}

/** 把带样式的片段拼成 Compose 的富文本。 */
private fun annotatedOf(spans: List<MarkdownLite.Span>): AnnotatedString =
    buildAnnotatedString {
        spans.forEach { span ->
            withStyle(
                SpanStyle(
                    fontWeight = if (MarkdownLite.Style.BOLD in span.styles) {
                        FontWeight.Bold
                    } else {
                        null
                    },
                    fontStyle = if (MarkdownLite.Style.ITALIC in span.styles) {
                        FontStyle.Italic
                    } else {
                        null
                    },
                    fontFamily = if (MarkdownLite.Style.CODE in span.styles) {
                        FontFamily.Monospace
                    } else {
                        null
                    },
                ),
            ) {
                append(span.text)
            }
        }
    }

@Composable
private fun CodeBlock(block: MarkdownLite.Block.Code) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                RoundedCornerShape(10.dp),
            )
            .padding(10.dp),
    ) {
        block.lang?.let { lang ->
            Text(
                text = lang,
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
            )
            Spacer(Modifier.height(4.dp))
        }
        Text(
            text = block.code,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = TextPrimary,
        )
    }
}

/* ═══════════════════════ 长按消息菜单 ═══════════════════════ */

/**
 * 长按一条消息弹出的操作菜单。
 *
 * ## 这里有一条必须告诉用户的代价
 * 「删除」与「重新生成」都会**改写历史** —— 而历史是缓存前缀的一部分：
 * 改动点之后的前缀全部失配，下一轮按未命中计费（§11 三铁律）。
 * 所以菜单底部把这件事写明了：不阻止用户（这是常规聊天功能），
 * 但别让他事后奇怪账单为什么涨了。
 *
 * 这与文档 §3.5「改全局前缀必须警告用户」是同一条纪律。
 */
@Composable
private fun MessageActionsDialog(
    isAssistant: Boolean,
    canRegenerate: Boolean,
    /** 能不能删：只有**最新一条她的回复**为真（v0.61.9，见调用点） */
    canDelete: Boolean,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onRegenerate: () -> Unit,
    onDismiss: () -> Unit,
) {
    YukiDialog(
        title = "这条消息",
        onConfirm = onDismiss,
        onDismiss = onDismiss,
        confirmText = "关闭",
        dismissText = "取消",
    ) {
        Column {
            ActionRow("复制") { onCopy() }
            // 「重新生成」只对**她的最后一条回复**有意义 ——
            // 对用户自己的消息或中间某条回复显示它，只会让人困惑点什么都没发生
            if (isAssistant && canRegenerate) {
                ActionRow("重新生成") { onRegenerate() }
            }
            // 「删除」只对**最新一轮**出现（v0.61.9）：删掉这一轮问答（她的回复 + 你的请求）
            // 外加这一轮自动记下的记忆。尾部删除**不动缓存前缀**。
            if (canDelete) {
                ActionRow("删除这一轮", destructive = true) { onDelete() }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                // ⚠️ 文案随语义改回（v0.61.17）：删除重新收窄到**最新一轮**
                //（用户要求「直接禁止删除历史消息，删除最新的需要二次确认」）。
                text = "删除与重新生成都只动最新一轮，之前的历史一个字节不变 —— " +
                    "缓存命中不受影响。",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
            )
        }
    }
}

@Composable
private fun ActionRow(
    text: String,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (destructive) DangerRose else TextPrimary,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
    )
}

/* ═══════════════════════ 缓存状态文案 ═══════════════════════ */

/**
 * 顶栏缓存状态。必须区分三种情形，否则用户看到 0% 无法判断是「正常首轮」还是「真的坏了」：
 * DeepSeek 缓存由「请求产生 → 后续请求匹配」，**首轮必然 0 命中**。
 */
private fun cacheLine(s: Session?, configured: Boolean): String {
    if (!configured) return "未配置 API Key"
    if (s == null) return "还没有对话"
    val billed = s.totalHit + s.totalMiss
    if (billed == 0) return "尚未请求"
    val rounds = s.messages.count { it.role == "user" }
    return when {
        s.totalHit == 0 && rounds <= 1 -> "首轮请求（缓存尚未建立，0% 属正常）"
        s.totalHit == 0 -> "命中 0% · 已 $rounds 轮 —— 检查人设/全局规则是否被改动"
        else -> "缓存命中 %.0f%% · 累计省 %d tokens".format(s.totalHit * 100.0 / billed, s.totalHit)
    }
}

private fun cacheColor(s: Session?, configured: Boolean): Color {
    if (!configured || s == null) return TextMuted
    val billed = s.totalHit + s.totalMiss
    if (billed == 0) return TextMuted
    val rounds = s.messages.count { it.role == "user" }
    return when {
        s.totalHit > 0 -> SuccessMint
        rounds <= 1 -> TextMuted
        else -> WarnAmber
    }
}

/* ═══════════════════════ 会话内搜索 ═══════════════════════ */

/**
 * 「查找聊天记录」的全屏层。
 *
 * ## 命中计算为什么在这里、不在 ViewModel
 * [MessageSearch.query] 是纯函数，输入只有"消息列表 + 关键词 + 过滤口径"三个。
 * 没有需要跨页面存活的中间态，就不该往 ViewModel 里塞状态 ——
 * 那只会让"谁负责刷新"变成一个多余的约定。
 *
 * ## 关键词与过滤口径是本地状态
 * 关掉搜索就该忘掉。它们在 `remember`（而非 `rememberSaveable`）里 ——
 * 搜索是一次性的翻找动作，旋屏后从零开始比"记住上次搜的词"更符合直觉。
 *
 * ⚠️ 渲染未经真机验证（本机无 adb / emulator）。
 */
@Composable
private fun SearchOverlay(
    messages: List<ChatMessage>,
    personaName: String,
    personaAvatar: String?,
    userProfile: UserProfile,
    onPick: (Int) -> Unit,
    onClose: () -> Unit,
) {
    var keyword by remember { mutableStateOf("") }
    var scope by remember { mutableStateOf(SearchScope.ALL) }
    // 找法页签：按关键词 / 按日期（v0.51.0 把 `DateIndex` 接进来）。
    // ⚠️ 与下面的「全部 / 我说的 / 角色说的」不是一回事 —— 那是**关键词检索内部**
    //    按角色收窄，这个是换一种**找法**。所以页签在过滤芯片之上。
    var tab by remember { mutableStateOf(SearchTab.KEYWORD) }
    // 「今天 / 昨天 / 周三」的基准。取一次就够：搜索层是个短命的浮层，
    // 不值得为跨零点重算（真跨了，下次打开就是对的）。
    val now = remember { System.currentTimeMillis() }
    val hits = remember(messages, keyword, scope) {
        // ⚠️ 这里的 `messages` **必须是全量**（`session.messages`），**不是**渲染窗口 `msgs`。
        //    窗口（`ChatWindow`）只管"画多少条"；搜索必须能看到全部历史。
        //    用户 2026-09-30 明确要求分页「**不影响查找聊天记录**」。
        //    `MessageSearch` 是纯函数、有单测，但"传进来的必须是全量"这条契约
        //    没有任何单测能守住 —— 它只能靠这一行 + 这句注释。
        MessageSearch.query(messages, keyword, scope)
    }

    Dialog(
        onDismissRequest = onClose,
        // 默认的 Dialog 会被系统限制成一个窄卡片 —— 搜索要占满整屏
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = SnowWhite) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {

                /* ── 顶栏：返回 + 搜索框（日期页签下只剩"返回"）── */
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onClose) {
                        Icon(YukiIcons.Back, contentDescription = "关闭搜索", tint = TextSecondary)
                    }
                    // ⚠️ 日期页签下把搜索框**收起来**（而不是留着不响应）——
                    //    一个敲进去没反应的输入框，比没有输入框更让人困惑。
                    //    用不带花括号的 `if` 包住这个表达式，是为了不动下面整块的缩进。
                    if (tab == SearchTab.KEYWORD) Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(YukiCardSpec.FieldCornerRadius),
                        color = FieldFill,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                YukiIcons.Search,
                                contentDescription = null,
                                tint = TextMuted,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            BasicTextField(
                                value = keyword,
                                onValueChange = { keyword = it },
                                modifier = Modifier.weight(1f),
                                textStyle = MaterialTheme.typography.bodyLarge.copy(color = TextPrimary),
                                cursorBrush = SolidColor(SkyBlueDeep),
                                singleLine = true,
                                decorationBox = { inner ->
                                    if (keyword.isEmpty()) {
                                        // 提示语写清范围：这是**本段对话内**的搜索，
                                        // 不是全局搜索 —— 否则用户会奇怪为什么别的会话搜不到
                                        Text(
                                            "在这段对话里找…",
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = TextMuted,
                                        )
                                    }
                                    inner()
                                },
                            )
                            if (keyword.isNotEmpty()) {
                                IconButton(onClick = { keyword = "" }, modifier = Modifier.size(20.dp)) {
                                    Icon(
                                        YukiIcons.Close,
                                        contentDescription = "清空",
                                        tint = TextMuted,
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                }

                /* ── 找法页签：按关键词 / 按日期 ──
                   用户 2026-09-30 的原话是「查找聊天记录增加**微信那种按日期查找**」。
                   它不是"又一个过滤条件"，而是第二条**找法**：在关键词里猜
                   和"先把时间轴列出来"是两种完全不同的动作。 */
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SearchScopeChip("按关键词", tab == SearchTab.KEYWORD) {
                        tab = SearchTab.KEYWORD
                    }
                    SearchScopeChip("按日期", tab == SearchTab.DATE) {
                        tab = SearchTab.DATE
                    }
                }

                Spacer(Modifier.height(8.dp))

                /* ── 过滤：全部 / 我说的 / 她说的 ──
                   ⚠️ 只在"按关键词"下有义：日期页签里没有"谁说的"这回事。 */
                if (tab == SearchTab.KEYWORD) Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SearchScopeChip("全部", scope == SearchScope.ALL) { scope = SearchScope.ALL }
                    SearchScopeChip("我说的", scope == SearchScope.MINE) { scope = SearchScope.MINE }
                    SearchScopeChip("角色说的", scope == SearchScope.HERS) { scope = SearchScope.HERS }
                }

                Spacer(Modifier.height(8.dp))

                when {
                    // 日期页签走另一条路：不查关键词，只把"哪天聊过、各多少条"列出来。
                    // ⚠️ 喂进去的必须是**全量** messages —— 与搜索同一份契约（见上面的注释）。
                    //    `DateIndex.days` 是纯函数（12 例单测钉住跨日/时区/时间戳为 0），
                    //    但它同样守不住"传进来的必须是全量"。
                    // ⚠️ `remember(messages)`：分组只跟消息列表走，翻页/改关键词都不重算。
                    tab == SearchTab.DATE -> DayList(
                        days = remember(messages) { DateIndex.days(messages) },
                        now = now,
                        onPick = onPick,
                    )
                    keyword.isBlank() -> SearchHint("输入关键词，找这段对话里的某一句话")
                    hits.isEmpty() -> SearchHint("没有找到「${keyword.trim()}」")
                    else -> {
                        Text(
                            text = "找到 ${hits.size} 条",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                            modifier = Modifier.padding(start = 16.dp, bottom = 6.dp),
                        )
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(hits, key = { it.index }) { hit ->
                                SearchResultRow(
                                    hit = hit,
                                    personaName = personaName,
                                    personaAvatar = personaAvatar,
                                    userProfile = userProfile,
                                    onClick = { onPick(hit.index) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchScopeChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (selected) BrandBlueSoft else FieldFill,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) SkyBlueDeep else TextSecondary,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
        )
    }
}

@Composable
private fun SearchHint(text: String) {
    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = TextMuted)
    }
}

/** 搜索层的两种找法（见 `SearchOverlay` 的页签）。 */
private enum class SearchTab { KEYWORD, DATE }

/**
 * 「按日期查找」的日期列表（v0.51.0）。
 *
 * ## 它回答的是"哪天聊过"，不是"某句话在哪"
 * 一行 = 一天：`今天 / 昨天 / 周三 / 9月20日` + 多少条 + 那天第一句的摘要。
 * 点一天就跳到**那天的第一条**（[DateIndex.DayBucket.firstIndex]）。
 *
 * ## ⚠️ 跳转复用既有机制，没有新写一套
 * `onPick` 就是关键词搜索在用的那个回调（`vm.closeSearch()` + `vm.requestScrollTo`）
 * —— 高亮与滚动定位都是现成的。历史上有过"两份实现产出不同锚点补偿 →
 * 点一下和滑到顶把画面停在不同位置"的坑（见 `loadEarlierNow` 的注释），
 * 所以这里**刻意**只调那一个回调。
 *
 * ## ⚠️ 日期文案走 [TimeLabels.forDay]
 * 与 `forList` / `forDivider` 同一条纪律：**纯函数、JVM 上钉死** ——
 * "今天 / 昨天 / 一周内"这些边界在真机上要改系统时间才能穷举。
 *
 * ⚠️ 渲染未经真机验证（本机无 adb / emulator）。
 */
@Composable
private fun DayList(
    days: List<DateIndex.DayBucket>,
    now: Long,
    onPick: (Int) -> Unit,
) {
    if (days.isEmpty()) {
        // 空态说明**为什么**空：时间戳为 0 的老消息会被 DateIndex 跳过 ——
        // 只写"没有"会让用户以为这段对话是空的。
        SearchHint("这段对话还没有可用的日期记录")
        return
    }

    Text(
        text = "${days.size} 天 · 点一天跳到那天的开头",
        style = MaterialTheme.typography.labelSmall,
        color = TextMuted,
        modifier = Modifier.padding(start = 16.dp, bottom = 6.dp),
    )

    LazyColumn(Modifier.fillMaxSize()) {
        // key 用 dayKey：同一天在列表里只可能出现一次（DateIndex 按天聚合）
        items(days, key = { it.dayKey }) { day ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(day.firstIndex) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = TimeLabels.forDay(day.dayKey, now),
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (day.preview.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = day.preview,
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "${day.count} 条",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                )
            }
            HorizontalDivider(
                modifier = Modifier.padding(start = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}

/**
 * 一条搜索结果：**头像 + 名字 + 命中片段**。
 *
 * ## 为什么不是"我说的 / 她说的"这两个标签
 * 用户 2026-09-27 的原话：搜索结果「显示的不应该是我说的她说的，而是显示人设昵称和头像」。
 * 那条反馈是对的 —— 标签只说明了**角色**，而头像与昵称同时说明了**是谁**：
 * 在这段对话里被叫那个名字的是她，用户自定义的头像是她自己选的，
 * 一眼就能对上号。（用标签还能得出"她说的"这种正确但毫无信息量的结论。）
 */
@Composable
private fun SearchResultRow(
    hit: SearchHit,
    personaName: String,
    personaAvatar: String?,
    userProfile: UserProfile,
    onClick: () -> Unit,
) {
    val fromMe = hit.role == "user"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (fromMe) {
            UserAvatar(
                size = 32.dp,
                path = userProfile.avatarPath,
                fallbackText = userProfile.displayName,
            )
        } else {
            YukiAvatar(size = 32.dp, path = personaAvatar)
        }

        Spacer(Modifier.width(10.dp))

        Column(Modifier.weight(1f)) {
            Text(
                text = if (fromMe) userProfile.displayName else personaName,
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = highlightHits(hit.snippet, hit.ranges),
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary,
            )
        }
    }
    HorizontalDivider(color = FrostLine.copy(alpha = 0.5f))
}

/**
 * 给结果片段里的命中处上色。
 *
 * 区间由 [MessageSearch] 按**片段自己的坐标系**给出（可能带首尾省略号），
 * 所以这里直接用，不做任何换算 —— 一旦这里再"算一次"，两处口径就会漂。
 *
 * `coerceIn` 是防御性的：区间理论上一定落在片段内（有 `MessageSearchTest` 反查钉住），
 * 但真机上 `substring` 越界会直接崩，而崩在搜索结果行上比"高亮偏一格"严重得多。
 */
private fun highlightHits(snippet: String, ranges: List<IntRange>): AnnotatedString =
    buildAnnotatedString {
        var cursor = 0
        ranges.forEach { r ->
            val from = r.first.coerceIn(cursor, snippet.length)
            val to = (r.last + 1).coerceIn(from, snippet.length)
            append(snippet.substring(cursor, from))
            withStyle(
                SpanStyle(
                    color = SkyBlueDeep,
                    fontWeight = FontWeight.SemiBold,
                    background = BrandBlueSoft,
                ),
            ) {
                append(snippet.substring(from, to))
            }
            cursor = to
        }
        append(snippet.substring(cursor))
    }
