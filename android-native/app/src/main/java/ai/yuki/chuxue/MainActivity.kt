package ai.yuki.chuxue

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.activity.compose.BackHandler
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import ai.yuki.chuxue.ui.update.UpdateDialog
import ai.yuki.chuxue.ui.update.UpdateViewModel
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import ai.yuki.chuxue.data.ProviderProfiles
import ai.yuki.chuxue.data.SCRIM_PLAIN
import ai.yuki.chuxue.ui.auth.AuthScreen
import ai.yuki.chuxue.ui.auth.AuthViewModel
import ai.yuki.chuxue.ui.auth.BindEmailPrompt
import ai.yuki.chuxue.ui.auth.BindEmailScreen
import ai.yuki.chuxue.service.ProactiveNotifier
import ai.yuki.chuxue.service.ProactiveWatchService
import ai.yuki.chuxue.ui.components.Island
import ai.yuki.chuxue.ui.auth.LegalKind
import ai.yuki.chuxue.ui.auth.LegalScreen
import ai.yuki.chuxue.ui.auth.WelcomeScreen
import ai.yuki.chuxue.ui.ChatScreen
import ai.yuki.chuxue.ui.ChatViewModel
import ai.yuki.chuxue.ui.SettingsScreen
import ai.yuki.chuxue.ui.SplashScreen
import ai.yuki.chuxue.ui.chat.ChatBackgroundScreen
import ai.yuki.chuxue.ui.chat.ChatSettingsScreen
import ai.yuki.chuxue.ui.components.DynamicIslandHost
import ai.yuki.chuxue.ui.settings.AccountSecurityScreen
import ai.yuki.chuxue.ui.settings.ApiConfigScreen
import ai.yuki.chuxue.ui.settings.ProviderGroupEditScreen
import ai.yuki.chuxue.ui.settings.CacheDiagnosticsScreen
import ai.yuki.chuxue.ui.settings.EmojiOptionsScreen
import ai.yuki.chuxue.ui.settings.EmojiPackScreen
import ai.yuki.chuxue.ui.settings.EmojiScope
import ai.yuki.chuxue.ui.settings.FeedbackScreen
import ai.yuki.chuxue.ui.settings.MemoryOptionsScreen
import ai.yuki.chuxue.ui.settings.FeatureSettingsScreen
// v0.61.57：美化设置页（全局背景 / 遮罩 / 顶栏透明度）
import ai.yuki.chuxue.ui.settings.AppearanceScreen
import ai.yuki.chuxue.ui.settings.GlobalBoardScreen
import ai.yuki.chuxue.ui.settings.SessionBoardScreen
import ai.yuki.chuxue.ui.settings.SettingsAdvancedScreen
import ai.yuki.chuxue.ui.settings.SettingsBackupScreen
import ai.yuki.chuxue.ui.settings.MemoryPersonasScreen
import ai.yuki.chuxue.ui.main.MainScreen
import ai.yuki.chuxue.ui.persona.PersonaDetailScreen
import ai.yuki.chuxue.ui.memory.MemoryManageScreen
import ai.yuki.chuxue.ui.theme.DEFAULT_SCRIM_ALPHA
import ai.yuki.chuxue.ui.theme.YukiTheme

/**
 * 单 Activity 入口 —— 全部界面由 Compose 绘制，不用 XML 布局。
 *
 * ## 关于启动屏（`installSplashScreen`）
 * `androidx.core:core-splashscreen` 提供的是**进程起来之前**那层系统窗口 ——
 * 它存在之前，冷启动会先闪一下主题底色才看到 Compose 开屏。
 *
 * 这里的用法参考了 GitHub 上的 `kibotu/androidx-splashscreen-compose`：
 * 「把它当成开屏动画的第一帧」，而不是当成需要绕开的东西。
 * 于是它**保持到数据就绪**（`setKeepOnScreenCondition`），
 * 用户不会看到"系统屏退了、内容还没好"的空档 —— 那正是列表先空后蹦的成因。
 *
 * ⚠️ `installSplashScreen()` 必须在 `super.onCreate()` **之前**调用，否则不生效。
 */
class MainActivity : ComponentActivity() {

    /**
     * 在 Activity 层拿 ViewModel，供启动屏条件使用。
     *
     * 与 Compose 里的 `viewModel()` **是同一个实例**（同一个 `ViewModelStore`），
     * 所以这里读到的 `ready` 与界面读到的是同一份状态 —— 不会出现"两个 VM 各说各话"。
     */
    private val vm: ChatViewModel by viewModels()

    /**
     * 「Ta 来找我」通知点进来时要打开的人设 id（消费在 [YukiApp]）。
     *
     * ⚠️ 放 Activity 字段而不是 `rememberSaveable`：它来自 **Intent**（onCreate / onNewIntent），
     * 是进程外送进来的事件，只能从这里过一手。
     */
    private val pendingOpenPersonaId = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        // 保持系统启动屏，直到首帧数据就绪。
        // 这让开屏那段时间**同时在做事**（恢复崩溃残留、迁移旧数据、读第一帧会话），
        // 而不是像上一版那样固定白等 1.2 秒。
        splash.setKeepOnScreenCondition { !vm.ready.value }

        super.onCreate(savedInstanceState)
        // 通知点进来（「Ta 来找我」）→ 记下人设 id，交 Compose 侧消费（见 YukiApp）。
        // 冷启动走这里；App 活着时点通知走 onNewIntent。
        pendingOpenPersonaId.value = intent?.getStringExtra(ProactiveNotifier.EXTRA_OPEN_PERSONA_ID)
        enableEdgeToEdge()
        setContent {
            // 字体大小要在**主题这一层**生效 —— 它影响全 App 的字号，不只某一页。
            // ⚠️ vm 在 Activity 层就有了（`by viewModels()`），所以这里读得到；
            //    若以后把 vm 挪进 Compose 内部，这一行会先编译不过 —— 那是好事，别绕过它。
            val settings by vm.settings.collectAsStateWithLifecycle()
            YukiTheme(fontScale = settings.fontScale) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    YukiApp(
                        pendingOpenPersonaId = pendingOpenPersonaId.value,
                        onOpenConsumed = { pendingOpenPersonaId.value = null },
                    )
                }
            }
        }
    }

    /**
     * App 还活着时点通知 → 复用这个实例（通知的 Intent 带 CLEAR_TOP|SINGLE_TOP）。
     * 读出新的人设 id 交给 Compose 侧（与冷启动同一条消费路）。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingOpenPersonaId.value = intent.getStringExtra(ProactiveNotifier.EXTRA_OPEN_PERSONA_ID)
    }
}

/** 路由表。开发文档 §46.1 的完整清单还含启动页与账号体系（依赖后端），此处只列已实现项。 */
/**
 * 主界面 Tab 的下标 —— **顺序必须与 `MainScreen` 里 `tabs` 列表的构造一致**。
 *
 * ⚠️ 它们要**跨路由**使用（设置页要请求切到「人设」），所以放在这里、
 * 而不是收在 `MainScreen` 内部。改 Tab 顺序时两处一起改。
 */
private const val MAIN_TAB_PERSONAS = 1

/** 冷启动该落在哪。 */
private const val MAIN_ENTER_MS = 380

internal enum class StartTarget { MAIN, WELCOME, AUTH }

/**
 * 冷启动去向 —— **纯函数，单测钉住这两条要求**（v0.60.0）。
 *
 * ## 1. 选择页只出现一次
 * 用户要求「首次打开出现，之后不再强制登录注册选择页」→ 判据是 [welcomeSeen]。
 *
 * ## 2. **没有游客通道**
 * 用户 2026-10-02 追加要求：「没有登录的用户必须登录不能到软件的真实界面」，
 * 并明确要删掉之前那套「不登录也能进主页」的游客行为。
 * 所以未登录**一律到不了 [MAIN]**：头一回去选择页，之后直接去登录页。
 *
 * ⚠️ 曾经有过一版是 `welcomeSeen -> MAIN`（未登录也放行）—— 那是游客模式，已按要求删除。
 */
internal fun startTarget(isLoggedIn: Boolean, welcomeSeen: Boolean): StartTarget = when {
    isLoggedIn -> StartTarget.MAIN
    welcomeSeen -> StartTarget.AUTH
    else -> StartTarget.WELCOME
}

private object Routes {
    /**
     * 开屏动画（§31）。
     *
     * ⚠️ 它是**起始目的地但不留在返回栈**：交棒时用
     * `popUpTo(SPLASH) { inclusive = true }` 出栈，否则用户在主界面按返回
     * 会退回开屏页 —— 那是"每次返回都重看一遍片头"的经典 bug。
     */
    const val SPLASH = "splash"

    /**
     * **登录 / 注册**（开发文档 §32 的 AuthScreen；v0.59.0 推翻重写，v0.60.0 换新视觉）。
     *
     * ⚠️ v0.60.0 起它**不再是未登录时的唯一入口**：未登录也能直接用 App
     * （BYOK + 本地优先，对话/人设/记忆都在本机）。第一次打开由 [WELCOME] 引导，
     * 之后想登录就走「我的」页那个「未登录，去登录」——按需，不拦人。
     *
     * 带一个 `step` 参数，让 [WELCOME] 能把用户直接送到"登录"或"注册"那一屏。
     */
    const val AUTH = "auth/{step}"

    fun auth(step: String) = "auth/$step"

    /** [AUTH] 的 step 取值。 */
    const val AUTH_STEP_LOGIN = "login"
    const val AUTH_STEP_REGISTER = "register"

    /**
     * **首次打开的选择页**（v0.60.0 · 用户 2026-10-02 要求）。
     *
     * 「首次打开出现，之后不再强制」—— 判据是 `Store.welcomeSeen()`（**看过没有**）。
     * 但它**不是游客入口**：未登录仍然进不了主界面，往前只有登录/注册两条路。
     */
    const val WELCOME = "welcome"

    /**
     * **使用协议 / 隐私政策 详情页**（v0.60.0）。
     *
     * `kind` = `terms` | `privacy`，从选择页那行协议里点进来，可返回。
     */
    const val LEGAL = "legal/{kind}"

    fun legal(kind: String) = "legal/$kind"

    const val LEGAL_TERMS = "terms"
    const val LEGAL_PRIVACY = "privacy"

    /**
     * **老用户补绑邮箱**（v0.60.0）。
     *
     * 早期版本注册不需要邮箱，存量里有一批 `email` 为空的人 ——
     * 而「忘记密码」现在只能靠邮箱收码，他们忘了密码就**没有任何自助找回的路**。
     * 这一屏只对这类人开放（入口在进主页的提醒弹窗 + 「我的」页那一行）。
     */
    const val BIND_EMAIL = "bind-email"

    const val MAIN = "main"
    const val CHAT = "chat"
    const val SETTINGS = "settings"

    /**
     * 记忆管理（开发文档 §42 / §46.1 的 `MemoryManageRoute`）。
     *
     * ⚠️ 2026-09-28：路由参数从 `personaId` 改成 **`sessionId`** ——
     * 记忆的归属从「人设」改成了「对话」（用户要求：同一人设新开一段对话不共享记忆）。
     * 页面本身仍需要 personaId，由这里从会话里取出来后一起传进去。
     *
     * ⚠️ 本项目的 id 是 `UUID` 前 8 位（十六进制字符），天然 URL 安全，不需要
     * `Uri.encode`。若将来改成可能含 `/` 或空格的形式，这里必须补编码 —— 否则
     * 路由会静默匹配不上，表现为"点了没反应"。
     */
    const val MEMORY = "memory/{sessionId}"

    fun memory(sessionId: String) = "memory/$sessionId"

    /**
     * 某人设的记忆页（v0.61.36）—— **不带会话**，只看该人设的人设级记忆。
     *
     * 与 [MEMORY] 目的相同（都到记忆页），差别只在有没有 `sessionId`：
     * 从"选角色"或设置进来时没有当前会话，所以走这条。
     */
    const val MEMORY_OF_PERSONA = "memoryOf/{personaId}"

    fun memoryOfPersona(personaId: String) = "memoryOf/$personaId"

    /**
     * **对话设置**（会话级）。
     *
     * ⚠️ 聊天窗口右上角进的是**这一页**，不是全局设置 —— 用户明确要求（仿微信：）
     * 全局设置仍从主界面「我的」进入。
     */
    const val CHAT_SETTINGS = "chatSettings/{sessionId}"

    /** 会话看板（缓存 / 用量 / 余额） */
    const val SESSION_BOARD = "sessionBoard/{sessionId}"

    /** 全部对话的看板（我的页入口，v0.46.0） */
    const val GLOBAL_BOARD = "globalBoard"

    /**
     * 人设详情页（v0.56.0）。
     *
     * ⚠️ 参数放在**路径里**而不是全局状态：这样"从哪来"不影响"看到谁"，
     *    返回时也不会串页（详情页唯一需要的外部输入就是人设 id）。
     */
    const val PERSONA_DETAIL = "personaDetail/{personaId}"

    fun chatSettings(sessionId: String) = "chatSettings/$sessionId"
    fun sessionBoard(sessionId: String) = "sessionBoard/$sessionId"

    /**
     * **聊天背景**（会话级）—— 从对话设置页进来。
     *
     * 独立成页（而不是在设置页里摊一排色块）：三个来源里的两个会离开本 App
     * （相册、相机），回来时独立页面比"设置页里的一小块"状态更清楚。
     */
    const val CHAT_BACKGROUND = "chatBackground/{sessionId}"

    fun chatBackground(sessionId: String) = "chatBackground/$sessionId"

    /**
     * 设置被拆成三页（用户要求：不要再塞在同一个页面里）。
     *
     * `SETTINGS` 本身变成**菜单** —— 它只负责"去哪"，不承载任何配置项。
     * 这样每一页的读者单一：连接设置偶尔配一次、功能设置偶尔调一次、
     * 人设是主界面 Tab（经常用）。
     */
    const val SETTINGS_API = "settingsApi"
    const val SETTINGS_FEATURE = "settingsFeature"

    /**
     * **美化设置**（v0.61.57，用户要求「设置内增加美化设置入口和设置页」）。
     *
     * 全局观感：背景图 / 遮罩（高斯模糊强度与风格）/ 顶栏透明度。
     * ⚠️ 与 [CHAT_BACKGROUND]（会话级）是**两层**：会话设过以会话为准，
     *    没设过才用这一页的全局值（与 `globalPrefixEnabled` 同一套思路）。
     */
    const val SETTINGS_APPEARANCE = "settingsAppearance"

    /**
     * **全局背景选择**（v0.61.57）—— 从美化页进来。
     *
     * 复用会话级那一页（[CHAT_BACKGROUND] / `ChatBackgroundScreen`）的实现，
     * 只是读写落到全局设置而不是某段会话。
     */
    const val GLOBAL_BACKGROUND = "globalBackground"

    /**
     * **连接分组详情**（v0.51.0）：新建 / 编辑一个连接分组。
     *
     * ⚠️ `groupId` 为 `"new"` 表示新建 —— 它不是真 id（真 id 是 UUID 前 8 位
     * **十六进制**字符，见 `ProviderGroups.newId`），所以不会撞上。
     * ⚠️ 与 `Routes.MEMORY` 同一条：本项目 id 天然 URL 安全，不必 `Uri.encode`；
     * 将来若改成可能含 `/` 的形式，这里必须补编码，否则路由**静默匹配不上**。
     */
    const val SETTINGS_PROVIDER_GROUP = "settingsProviderGroup/{groupId}"

    fun providerGroup(groupId: String) = "settingsProviderGroup/$groupId"

    /** 账号安全：绑定邮箱 / 用邮箱改密码（邮箱的实际用途，见 `AccountSecurityScreen`）。 */
    const val SETTINGS_ACCOUNT = "settingsAccount"

    /** 数据备份（v0.50.5 从「功能设置」拆出）：导出 / 导入。 */
    const val SETTINGS_BACKUP = "settingsBackup"

    /** 高级设定（v0.50.5 从「功能设置」拆出）：通用设定 + 缓存诊断入口。 */
    const val SETTINGS_ADVANCED = "settingsAdvanced"

    /** 缓存诊断（文档 §43）：命中率与 token 用量。 */
    const val SETTINGS_DIAGNOSTICS = "settingsDiagnostics"

    /** 表情包管理（用户 2026-09-28）：按情绪分类放图。 */
    const val SETTINGS_EMOJI = "settingsEmoji"

    /** 表情包**设置**（开关 / 概率 / 全局图库入口）—— v0.57.0 从功能设置拆出。 */
    const val SETTINGS_EMOJI_OPTIONS = "settingsEmojiOptions"

    /** 记忆**设置**（自动记住开关 + 管理记忆入口）—— v0.57.0 从功能设置拆出。 */
    const val SETTINGS_MEMORY_OPTIONS = "settingsMemoryOptions"

    /** 反馈（说点什么）—— 「我的」页的入口，v0.57.0。 */
    const val FEEDBACK = "feedback"

    /**
     * 某人设的**专属表情包**库（v0.57.0）。
     *
     * 与 [SETTINGS_EMOJI]（全局库）是用户要求的「两个入口与界面」——
     * 入口在人设编辑页 / 人设详情页的「专属表情包」行。
     */
    const val PERSONA_EMOJI = "personaEmoji/{personaId}"

    /** 记忆管理入口：**先选一个角色**（记忆 2026-10-05 起属于人设，跨对话共享）。 */
    const val SETTINGS_MEMORY = "settingsMemory"
}

/**
 * 应用内导航。
 *
 * ## 返回栈交给 NavHost（而不是自己维护一个 route 变量）
 * 上一版用 `var route by remember` 手写页面切换，导致两个真实缺陷：
 * - 系统返回键**无人处理** —— 在聊天页/设置页按返回会直接退出 App；
 * - `remember` 不跨进程重建 —— 旋屏后弹回主界面。
 * NavHost 原生解决这两件事：返回键自动 `popBackStack`，栈内容可保存恢复。
 *
 * ## Tab 仍不走导航（§34.2 / §46.1）
 * 主界面内切 Tab 不该进回退栈，所以那五个 Tab 由 [MainScreen] 内部用 state 管。
 */
@Composable
private fun YukiApp(
    /** 「Ta 来找我」通知点进来时要打开的人设 id（null = 普通启动）。 */
    pendingOpenPersonaId: String? = null,
    /** 深链消费完（导航了 / 或确认无处可去）后清 Activity 层的值。 */
    onOpenConsumed: () -> Unit = {},
) {
    val vm: ChatViewModel = viewModel()
    val authVm: AuthViewModel = viewModel()
    val updateVm: UpdateViewModel = viewModel()
    val nav = rememberNavController()
    val context = LocalContext.current

    /**
     * 跨路由的「切到主界面哪个 Tab」请求（v0.53.0）。
     *
     * ⚠️ 状态放在这里而不是 `MainScreen` 内部：设置页是**独立路由**，
     * 要从它请求切 Tab，请求必须活在**两者之上**。用户 2026-09-30 报的
     * 「点人设与角色入口跳回我的界面」就是缺了这一层（只 popBackStack、没切 Tab）。
     */
    // ⚠️ 用 rememberSaveable 而不是 remember（补丁）：进程被系统回收后重建时，
    //    「请切到人设 Tab」这个请求不该丢 —— 丢了的表现就是"点了没反应"。
    var requestedTab by rememberSaveable { mutableStateOf<Int?>(null) }

    // 启动时**在后台**核一遍登录态：不阻塞任何界面，失败也不踢人。
    // 放在这里（而不是某个页面里）是因为它与页面无关，只与"这次启动"有关。
    LaunchedEffect(Unit) { authVm.verifyOnLaunch() }
    // 自动备份的**唯一机会点**（v0.53.0）：App 每次起来问一次"现在该不该备份"。
    // ⚠️ 项目没有 WorkManager，所以只有这一处触发 —— 事件语义见 AutoBackupPolicy 的类注释。
    LaunchedEffect(Unit) { vm.runAutoBackupIfDue() }

    // 「Ta 来找我」（v0.61.40）：启动 / 回前台各问一次"有没有 Ta 想说的话"（机会式 +
    // 30 分钟节流，见 ProactiveFetchPolicy）。⚠️ 这不是闹钟 —— 何时说、说什么由心潮的
    // 情绪状态机决定，这里只是"去信箱看看"。
    // ⚠️ 两个触发点缺一不可：LaunchedEffect 覆盖冷启动（observer 注册常晚于首次
    //    ON_START、收不到它）；ON_START observer 覆盖回前台。重复命中由守卫拦
    //（时间戳先同步落盘，见 runProactiveFetchIfDue）。
    LaunchedEffect(Unit) { vm.runProactiveFetchIfDue() }
    // 在场时间（v0.61.52）：冷启动时告诉心潮"人来了" —— **不带任何正文**，
    // 只刷新在场时间。没有它，用户开着 App 没说话时会被当成"长期不在"。
    LaunchedEffect(Unit) { vm.reportHeartbeatIfCloud() }

    // 「Ta 主动来找我」的**后台常驻**（v0.61.49，用户 2026-10-06 要求）：
    // 有云端人设 + 通知可用 → 起前台服务（幂等），于是 App 在后台也能取件、弹通知。
    // ⚠️ 代价：用户会一直看到一条常驻通知（Android 硬性要求，藏不掉）。
    //    拿不到通知权限时服务起不来 —— 那是预期降级（前台服务本就必须挂通知）。
    val watchPersonas by vm.personas.collectAsStateWithLifecycle()
    val watchContext = LocalContext.current
    LaunchedEffect(watchPersonas) {
        ProactiveWatchService.sync(
            watchContext,
            // ⚠️ 2026-10-06：判据 = 「Ta 主动来找我」开关（用户要求该项独立开关）。
            //    沿革：v0.61.48 曾用 isCloudMemory（三项绑死）—— 现在可单独关，
            //    所以监听服务必须跟着这个开关起停，否则关了还在后台轮询。
            shouldRun = watchPersonas.any { it.isCloudMemory && it.proactiveEnabled } &&
                ProactiveNotifier.canNotify(watchContext),
        )
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                vm.runProactiveFetchIfDue()
                // 回前台同样报一次在场（切走再回来 = "人又在了"）
                vm.reportHeartbeatIfCloud()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    /** 开屏导航是否已完成 —— 通知深链要等它，否则会和开屏页的 navigate 抢路由。 */
    var startupDone by remember { mutableStateOf(false) }

    // 通知点进来 → 进那个人设的**最近一段会话**（没有就新建）—— 与详情页「进入对话」同一条路。
    // ⚠️ 未登录不导航：`startTarget` 把未登录用户拦在主界面之外，这里不能绕过那道门，
    //    只把请求消费掉（否则它会一直挂着）。
    LaunchedEffect(pendingOpenPersonaId, startupDone) {
        val pid = pendingOpenPersonaId ?: return@LaunchedEffect
        if (!startupDone) return@LaunchedEffect
        onOpenConsumed()
        if (!authVm.auth.value.isLoggedIn) return@LaunchedEffect
        if (vm.personaById(pid) == null) return@LaunchedEffect // 人设已被删：无处可去
        val latest = vm.sessions.value.filter { it.personaId == pid }.maxByOrNull { it.updatedAt }
        val target = latest?.id ?: run {
            vm.newSession(pid)
            vm.activeSessionId.value
        }
        if (target.isNotBlank()) {
            vm.switchSession(target)
            nav.navigate(Routes.CHAT)
        }
    }

    // 启动时**在后台**查一次更新（v0.50.0，用户选定「启动静默检查」）。
    // ⚠️ 与 verifyOnLaunch **并列**而不是替换它 —— 两件事互不依赖。
    // ⚠️ 失败静默（见 UpdateViewModel 的类注释）：更新检查不该打扰用户。
    LaunchedEffect(Unit) { updateVm.checkOnLaunch() }

    // 更新弹窗挂在 NavHost 之外，和灵动岛同一层 —— 这样它在任何页面之上都能弹出来。
    val updateState by updateVm.state.collectAsStateWithLifecycle()
    UpdateDialog(
        state = updateState,
        onDownload = { updateVm.download() },
        onDismiss = { updateVm.dismiss() },
        onRetry = { updateVm.download() },
        onInstall = {
            val intent = updateVm.installIntent()
            if (intent != null) {
                runCatching { context.startActivity(intent) }
            }
            updateVm.reset()
        },
        // ⚠️ 「退出应用」是强制更新失败时的**最后一条出路** —— 没有它，
        //    下载永久失败的用户会彻底用不了 App（见 UpdateDialog 的注释）
        onExitApp = {
            updateVm.reset()
            (context as? Activity)?.finish()
        },
        onOpenInstallSettings = {
            runCatching { context.startActivity(updateVm.settingsIntent()) }
        },
        canInstall = updateVm.canInstall(),
    )

    // 灵动岛提示浮在**所有页面之上**（用户要求：所有临时提示都走它）。
    // 挂在 NavHost 外面而不是某一页里 —— 否则用户在提示消失前切页，它会被一起销毁。
    Box(Modifier.fillMaxSize()) {
        NavHost(
        navController = nav,
        startDestination = Routes.SPLASH,
        // ① 不透明底：切换动画期间两个页面会同时半透明，叠加 alpha 不足 1，
        //    按比例透出下层 —— 真机反馈的「黑框一闪」就是这么来的。
        //    给 NavHost 自己铺一层主题背景，任何一帧透出的都是正确颜色。
        // ② 显式动画：nav 默认 fadeIn/fadeOut 各 700ms，半透明期太长、缝隙更明显。
        //    压到 350/200ms，并用设计系统的缓动曲线。
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        // 切换动画**故意留空**。
        //
        // 上一版这里用了 350ms / 200ms 的淡入淡出，真机上看到「一个半透明的方块
        // 一闪而过（约 0.3–0.5 秒后自动消失）」—— 因为动画期间新旧两页**同时半透明**，
        // 叠加 alpha 不足 1，透出下层，在矩形内容区上就是个半透明方块。
        //
        // 页面切换本来就是「跳一下」的语义：瞬时切换既没有这个瑕疵，也没有损失观感。
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None },
    ) {
        composable(
            Routes.SPLASH,
            // ① 开屏交棒：淡出而不是硬切（用户要求"平滑过渡到首次显示的选择/登录/注册界面"）
            exitTransition = { fadeOut(tween(220)) },
        ) {
            // 开屏**等初始化**：「动画放完」与「首帧数据就绪」两个条件都满足才交棒。
            // 这让那 1.2 秒从"白等"变成"预加载"，用户进主界面时列表已经有内容了。
            // （开屏页内部还有 6 秒超时兜底 —— 初始化挂了也不会把用户锁在这里。）
            val ready by vm.ready.collectAsStateWithLifecycle()
            SplashScreen(
                ready = ready,
                onFinished = {
                    // 开屏之后的第一件事：看**本地**有没有登录态。
                    //
                    // ⚠️ 这里**不等服务器**（不调 /me 再决定去哪）—— 那会让冷启动多等一个
                    //    网络往返，而且服务器不通时用户会被卡在开屏页。
                    //    `verifyOnLaunch()` 已经在后台跑了，它只负责更新资料与标记"可能过期"，
                    //    不参与"能不能进"的判断。
                    // v0.60.0：**没有游客通道** —— 未登录一律到不了主界面。
                    //   已登录        → 主页
                    //   选择页看过了  → 登录页（仍要登录）
                    //   头一回        → 选择页（只在这一次出现）
                    val target = when (startTarget(authVm.auth.value.isLoggedIn, authVm.welcomeSeen())) {
                        StartTarget.MAIN -> Routes.MAIN
                        StartTarget.WELCOME -> Routes.WELCOME
                        StartTarget.AUTH -> Routes.auth(Routes.AUTH_STEP_LOGIN)
                    }
                    nav.navigate(target) {
                        // 出栈：开屏页不该留在返回栈里
                        popUpTo(Routes.SPLASH) { inclusive = true }
                    }
                    // 「Ta 来找我」的深链等在这里：开屏导航完成之前不消费（见上面的 LaunchedEffect）
                    startupDone = true
                },
            )
        }

        // 认证 → 主页：**平滑过渡**（v0.59.0 用户要求「点击确定一个平滑过渡动画到个人信息主页」）。
        // ⚠️ NavHost 默认把过渡全设成了 None（见上面的 `EnterTransition.None`），
        //    所以这一条路必须在**这两个路由上单独配**，否则就是硬切一下。
        // 首次打开的选择页（v0.60.0）。**只出现一次**，且**没有游客出口** ——
        // 用户 2026-10-02：「没有登录的用户必须登录不能到软件的真实界面」。
        composable(
            Routes.WELCOME,
            enterTransition = { fadeIn(tween(260)) },
            exitTransition = { fadeOut(tween(220)) },
        ) {
            WelcomeScreen(
                onLogin = {
                    authVm.markWelcomeSeen()
                    nav.navigate(Routes.auth(Routes.AUTH_STEP_LOGIN))
                },
                onRegister = {
                    authVm.markWelcomeSeen()
                    nav.navigate(Routes.auth(Routes.AUTH_STEP_REGISTER))
                },
                onOpenTerms = { nav.navigate(Routes.legal(Routes.LEGAL_TERMS)) },
                onOpenPrivacy = { nav.navigate(Routes.legal(Routes.LEGAL_PRIVACY)) },
            )
        }

        // 使用协议 / 隐私政策详情页：从选择页点进来，看完返回（不落栈底）
        composable(
            Routes.LEGAL,
            enterTransition = { fadeIn(tween(200)) + slideInHorizontally(tween(240)) { it / 8 } },
            exitTransition = { fadeOut(tween(180)) + slideOutHorizontally(tween(240)) { it / 8 } },
        ) { entry ->
            LegalScreen(
                kind = if (entry.arguments?.getString("kind") == Routes.LEGAL_PRIVACY) {
                    LegalKind.PRIVACY
                } else {
                    LegalKind.TERMS
                },
                onBack = { nav.popBackStack() },
            )
        }

        composable(
            Routes.AUTH,
            // 退出登录回到这里时淡入，不闪
            enterTransition = { fadeIn(tween(240)) },
            exitTransition = {
                fadeOut(tween(260)) + slideOutHorizontally(tween(300)) { -it / 10 }
            },
        ) { entry ->
            // ⚠️ 返回行为改到 AuthScreen 内部处理了（v0.60.0）：
            //    用户要求「引导页只在第一次出现，不能靠返回回到引导页」，
            //    而且「重设密码 / 注册 返回的应该是登录页」—— 那需要知道当前在第几步，
            //    step 是 AuthScreen 的内部状态，所以 BackHandler 得放在那里。
            AuthScreen(
                vm = authVm,
                // 从选择页点「注册新账号」进来的话，直接落在注册那一屏
                startOnRegister = entry.arguments?.getString("step") == Routes.AUTH_STEP_REGISTER,
                onAuthed = {
                    // ⚠️ 这里三件事缺一不可（用户 2026-10-02 报的两个头像问题）：
                    // ① 重新读本地资料 —— 注册时选的头像只写进了 Store，而「我的」页
                    //    读的是 ChatViewModel 内存里的 _profile（构造时读的旧值）→ 不重读就永远不显示；
                    // ② 拉服务器的头像 —— fetchAvatarIfMissing 只在 ChatViewModel.init 调过一次，
                    //    换设备/清数据后启动时还没登录，那次提前 return 了；
                    // ③ 反向补传 —— 注册时选的头像只在本地，得推上去别的设备才看得到。
                    vm.reloadProfile()
                    vm.fetchAvatarIfMissing()
                    vm.pushAvatarIfMissing()
                    nav.navigate(Routes.MAIN) {
                        // 登录成功后把登录页出栈 —— 否则用户按返回会回到登录页
                        popUpTo(Routes.AUTH) { inclusive = true }
                    }
                },
            )
        }

        composable(
            Routes.BIND_EMAIL,
            enterTransition = { fadeIn(tween(240)) + slideInHorizontally(tween(260)) { it / 10 } },
            exitTransition = { fadeOut(tween(200)) + slideOutHorizontally(tween(240)) { it / 10 } },
        ) { entry ->
            val ctx = LocalContext.current
            // 把「当前绑的是哪个」传进去 —— 有值就是**换绑**，空就是**补绑**
            // （同一屏，只有文案不同；后端 auth_email_bind 本来就是 UPDATE）
            val myEmail by authVm.auth.collectAsStateWithLifecycle()
            BindEmailScreen(
                vm = authVm,
                currentEmail = myEmail.email,
                onBack = { nav.popBackStack() },
                onDone = {
                    // 绑好了 → 回上一屏，并用灵动岛给一次明确反馈
                    Island.ok("邮箱绑定成功")
                    nav.popBackStack()
                },
            )
            // 绑完顺手把「我的」页要用的资料重读一遍（邮箱是那儿显示的一项）
            LaunchedEffect(Unit) { vm.reloadProfile() }
        }

        composable(
            Routes.MAIN,
            // 登录/注册完成 → 主页淡入 + 轻微上浮 + 一点点"推近"。
            // 用户 2026-10-02：「登录成功跳转动画」——原来只有淡入+位移，
            // 加一层 0.97→1 的缩放，观感上是"到站落定"而不是"换了一页"。
            enterTransition = {
                fadeIn(tween(MAIN_ENTER_MS, easing = FastOutSlowInEasing)) +
                    scaleIn(tween(MAIN_ENTER_MS, easing = FastOutSlowInEasing), initialScale = 0.97f) +
                    slideInHorizontally(tween(MAIN_ENTER_MS, easing = FastOutSlowInEasing)) { it / 12 }
            },
            exitTransition = { fadeOut(tween(220)) },
        ) {
            // 老用户补绑邮箱的提醒（用户 2026-10-02 提；2026-10-05 改口径）。
            // 判据：**登录态在、但邮箱为空**（只有早期版本注册的人会命中；
            // 现在的注册第一段就强制验证邮箱，新用户走不到这里）。
            // ⚠️ v0.61.24.4：按用户要求改成**真一次性** —— 以前只靠 `rememberSaveable`，
            //    每次冷启动都会再弹；现在**弹过就落盘**，之后不再弹。
            val authState by authVm.auth.collectAsStateWithLifecycle()
            if (authState.isLoggedIn && authState.email.isNullOrBlank() && !authVm.emailPromptSeen()) {
                BindEmailPrompt(
                    onGoBind = {
                        authVm.markEmailPromptSeen()
                        nav.navigate(Routes.BIND_EMAIL)
                    },
                    // 「以后再说」= 这次先不看，但**也算弹过了**（不再打扰）。
                    onLater = { authVm.markEmailPromptSeen() },
                )
            }
            // 人设云同步：**进主界面就拉一次**（v0.61.21 · fix2）。
            // ⚠️ 为什么不能只挂聊天页（ChatScreen 里那次保留、兼作重试）：清数据/换设备
            //    重登后的落点是这里 —— 用户在主界面人设 Tab 看到"人设不在"时可能一次都没
            //    进过聊天页 ⇒ 拉取一次都没发生（2026-10-04 真机日志：login 成功后 0 次
            //    GET persona）。本调用幂等（拉过一次即短路），多挂不增请求。
            LaunchedEffect(Unit) { vm.syncPersonasIfNeeded() }
            MainScreen(
                vm = vm,
                authVm = authVm,
                onOpenChat = { id ->
                    vm.switchSession(id)
                    nav.navigate(Routes.CHAT)
                },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                // v0.54.0：「我的」页的「数据备份」直达 —— 以前它只能从设置里绕一层进去
                onOpenBackup = { nav.navigate(Routes.SETTINGS_BACKUP) },
                onOpenGlobalBoard = { nav.navigate(Routes.GLOBAL_BOARD) },
                // 「我的」页的反馈入口（v0.57.0）
                onOpenFeedback = { nav.navigate(Routes.FEEDBACK) },
                // v0.60.0：未登录时「我的」页那个胶囊 → 按需去登录
                onOpenLogin = { nav.navigate(Routes.auth(Routes.AUTH_STEP_LOGIN)) },
                // 「我的」页那一行「绑定邮箱」的落点（只对未绑邮箱的老用户显示）
                onOpenBindEmail = { nav.navigate(Routes.BIND_EMAIL) },
                onLoggedOut = {
                    nav.navigate(Routes.auth(Routes.AUTH_STEP_LOGIN)) {
                        popUpTo(0) { inclusive = true }
                    }
                },
                // 人设长按菜单 → 记忆库：与上面 `onOpenMemory` 那条路同目的地
                onOpenMemory = { sessionId -> nav.navigate(Routes.memory(sessionId)) },
                // 点人设卡 → 详情页（v0.56.0）
                onOpenPersonaDetail = { personaId ->
                    nav.navigate(Routes.PERSONA_DETAIL.replace("{personaId}", personaId))
                },
                // 人设专属表情包（v0.57.0）—— 从人设编辑页点「专属表情包」进来
                onOpenPersonaEmoji = { personaId ->
                    nav.navigate(Routes.PERSONA_EMOJI.replace("{personaId}", personaId))
                },
                // 「关于」页的手动检查更新（v0.50.0）。
                // ⚠️ 走 `checkManually()` 而不是 `checkOnLaunch()`：
                //    前者失败会**明确告诉用户为什么**（他正等着结果），
                //    后者失败静默（启动路径不该打扰）。
                onCheckUpdate = { updateVm.checkManually() },
                requestedTab = requestedTab,
                onRequestedTabConsumed = { requestedTab = null },
            )
        }

        // 会话看板：缓存 / 用量 / 余额（入口在对话设置页）
        composable(Routes.GLOBAL_BOARD) {
            GlobalBoardScreen(vm = vm, onBack = { nav.popBackStack() })
        }

        /**
         * 人设详情页（v0.56.0）。
         *
         * 三个出口都在这里接：
         * · 进入对话 —— 有会话就跳那一段；没有就新建一段（走与「开始新对话」同一条路）
         * · 编辑人设 —— **在详情页内部完成**（不往这里走，见 PersonaDetailScreen）
         * · 记忆库 —— 传那一段会话的 id 给已有的记忆页路由
         */
        composable(Routes.PERSONA_DETAIL) { entry ->
            val personaId = entry.arguments?.getString("personaId").orEmpty()
            PersonaDetailScreen(
                vm = vm,
                personaId = personaId,
                onBack = { nav.popBackStack() },
                onEnterChat = { pid ->
                    // ⚠️ 优先跳**已有的最近一段**；一段都没有才新建 ——
                    //    用户点「继续和她说话」时，期望的是接上次，不是又开一段。
                    val latest = vm.sessions.value
                        .filter { it.personaId == pid }
                        .maxByOrNull { it.updatedAt }
                    val target = latest?.id ?: run {
                        vm.newSession(pid)
                        vm.activeSessionId.value
                    }
                    if (target.isNotBlank()) {
                        // ⚠️ 与「开始新对话」走同一条路：先切到这个会话，再进聊天页。
                        //    `Routes.CHAT` **不带参数** —— 聊天页读的是 `activeSessionId`。
                        vm.switchSession(target)
                        nav.navigate(Routes.CHAT)
                    }
                },
                onOpenMemory = { sessionId -> nav.navigate(Routes.memory(sessionId)) },
                // 人设专属表情包（v0.57.0）：两个入口之一，见 EmojiPackScreen 的 EmojiScope
                onOpenPersonaEmoji = { pid ->
                    nav.navigate(Routes.PERSONA_EMOJI.replace("{personaId}", pid))
                },
            )
        }

        composable(Routes.SESSION_BOARD) { entry ->
            val sessionId = entry.arguments?.getString("sessionId").orEmpty()
            SessionBoardScreen(
                vm = vm,
                sessionId = sessionId,
                onBack = { nav.popBackStack() },
            )
        }

        composable(Routes.MEMORY) { entry ->
            val sessionId = entry.arguments?.getString("sessionId").orEmpty()
            val sessions by vm.sessions.collectAsStateWithLifecycle()
            val personas by vm.personas.collectAsStateWithLifecycle()
            val personaId = sessions.firstOrNull { it.id == sessionId }?.personaId.orEmpty()
            MemoryManageScreen(
                // 记忆默认按**人设**归属（跨对话，2026-10-05 起），会话级记忆再按 sessionId
                // 隔离 —— 列表要按「人设级全部 + 本会话的会话级」取，所以两个键一起传下去。
                personaId = personaId,
                sessionId = sessionId,
                cloudEnabled = personas.firstOrNull { it.id == personaId }?.isCloudMemory == true,
                onBack = { nav.popBackStack() },
            )
        }

        composable(Routes.MEMORY_OF_PERSONA) { entry ->
            val personaId = entry.arguments?.getString("personaId").orEmpty()
            val personas by vm.personas.collectAsStateWithLifecycle()
            MemoryManageScreen(
                // 不带会话：只看这个人设的人设级记忆（记忆默认就是人设级）
                personaId = personaId,
                cloudEnabled = personas.firstOrNull { it.id == personaId }?.isCloudMemory == true,
                onBack = { nav.popBackStack() },
            )
        }

        composable(Routes.CHAT) {
            ChatScreen(
                vm = vm,
                // 「没配 Key」的引导仍然指向全局设置（那里才填得了 Key）
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                // 右上角齿轮进的是**这段对话**的设置页
                onOpenChatSettings = { sessionId ->
                    nav.navigate(Routes.chatSettings(sessionId))
                },
                // 人设已是主界面 Tab：直接回根，别在栈里叠一堆副本
                onOpenPersonas = { nav.popBackStack(Routes.MAIN, inclusive = false) },
                onBack = { nav.popBackStack() },
            )
        }

        composable(Routes.CHAT_SETTINGS) { entry ->
            val sessionId = entry.arguments?.getString("sessionId").orEmpty()
            val sessions by vm.sessions.collectAsStateWithLifecycle()
            // 压缩模式 / 阈值是**全局设置**（不随会话走）—— 见 ChatSettingsScreen 的参数注释
            val globalSettings by vm.settings.collectAsStateWithLifecycle()
            // 生成中要把「思考」组锁住（用户要求：运行期间不能改思考模式/强度）
            val busy by vm.busy.collectAsStateWithLifecycle()
            ChatSettingsScreen(
                session = sessions.firstOrNull { it.id == sessionId },
                onBack = { nav.popBackStack() },
                onUpdate = { transform -> vm.updateSession(sessionId, transform) },
                settings = globalSettings,
                busy = busy,
                onUpdateSettings = { transform -> vm.saveSettings(transform) },
                onDelete = {
                    vm.deleteSession(sessionId)
                    nav.popBackStack()
                },
                onOpenBoard = { nav.navigate(Routes.sessionBoard(sessionId)) },
                // 搜索属于「这段对话的内容」，不属于设置页：先把设置页退掉、回到聊天页，
                // 再由那边把搜索层打开。顺序不能反 —— popBackStack 会让 ChatScreen 重建，
                // 所以搜索状态必须住在 ViewModel 里（这正是它没放界面层的原因）。
                onOpenSearch = {
                    vm.openSearch()
                    nav.popBackStack()
                },
                onOpenBackground = { nav.navigate(Routes.chatBackground(sessionId)) },
                // 记忆挂在**会话**上（2026-09-28 起：同一人设新开对话不共享记忆），
                // 所以路由直接用它，不必再绕人设。
                onOpenMemory = { nav.navigate(Routes.memory(sessionId)) },
            )
        }

        composable(Routes.CHAT_BACKGROUND) { entry ->
            val sessionId = entry.arguments?.getString("sessionId").orEmpty()
            val sessions by vm.sessions.collectAsStateWithLifecycle()
            // 背景与遮罩都挂在**这一段对话**上（会话级设置），所以直接取整条会话
            val session = sessions.firstOrNull { it.id == sessionId }
            ChatBackgroundScreen(
                background = session?.background,
                scrimEnabled = session?.scrimEnabled ?: true,
                scrimAlpha = session?.scrimAlpha ?: DEFAULT_SCRIM_ALPHA,
                scrimStyle = session?.scrimStyle ?: SCRIM_PLAIN,
                // 走既有的 updateSession：它只改会话设置，**不碰历史、不碰冻结前缀**，
                // 所以换背景与调遮罩都不动缓存（与免打扰/置顶同一类操作）。
                onSetBackground = { id ->
                    vm.updateSession(sessionId) { it.copy(background = id) }
                },
                onSetScrim = { enabled, alpha, style ->
                    vm.updateSession(sessionId) {
                        it.copy(scrimEnabled = enabled, scrimAlpha = alpha, scrimStyle = style)
                    }
                },
                onBack = { nav.popBackStack() },
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { nav.popBackStack() },
                // 人设是主界面的一个 Tab：回根 + **请求切到那个 Tab**。
                // ⚠️ 只 popBackStack、不切 Tab 的话，回来会停在"进设置前那一个"
                //（从「我的」进来 → 回来还是「我的」）—— 2026-09-30 用户报的 bug。
                onOpenPersonas = {
                    requestedTab = MAIN_TAB_PERSONAS
                    nav.popBackStack(Routes.MAIN, inclusive = false)
                },
                onOpenFeature = { nav.navigate(Routes.SETTINGS_FEATURE) },
                // v0.61.57：美化（全局背景 / 遮罩 / 顶栏透明度）
                onOpenAppearance = { nav.navigate(Routes.SETTINGS_APPEARANCE) },
                // v0.57.0：从「功能设置」拆出来的两组，各自独立成页
                onOpenEmojiOptions = { nav.navigate(Routes.SETTINGS_EMOJI_OPTIONS) },
                onOpenMemoryOptions = { nav.navigate(Routes.SETTINGS_MEMORY_OPTIONS) },
                onOpenBackup = { nav.navigate(Routes.SETTINGS_BACKUP) },
                onOpenAdvanced = { nav.navigate(Routes.SETTINGS_ADVANCED) },
                onOpenApi = { nav.navigate(Routes.SETTINGS_API) },
                onOpenAccount = { nav.navigate(Routes.SETTINGS_ACCOUNT) },
            )
        }

        composable(Routes.SETTINGS_API) {
            ApiConfigScreen(
                vm = vm,
                onBack = { nav.popBackStack() },
                onOpenGroup = { groupId -> nav.navigate(Routes.providerGroup(groupId)) },
            )
        }

        composable(Routes.SETTINGS_PROVIDER_GROUP) { entry ->
            ProviderGroupEditScreen(
                vm = vm,
                groupId = entry.arguments?.getString("groupId").orEmpty(),
                onBack = { nav.popBackStack() },
            )
        }

        composable(Routes.SETTINGS_FEATURE) {
            // v0.57.0：它原来还接 onOpenMemories / onOpenEmojiPacks ——
            // 那两组已各自独立成页（见下面的 SETTINGS_*_OPTIONS）。
            FeatureSettingsScreen(vm = vm, onBack = { nav.popBackStack() })
        }

        // v0.61.57：美化设置（全局观感）。
        // ⚠️ 背景选择复用**会话级**那一页（`ChatBackgroundScreen`）—— 它有完整的图库 /
        //    预设 / 删除实现，重写一份必然漂移。这里传一个"全局会话 id"给它：
        //    全局设置的读写走 `vm.settings` 而不是 `updateSession`，
        //    所以下面用 `onSetBackground` / `onSetScrim` 直接改全局设置。
        composable(Routes.SETTINGS_APPEARANCE) {
            val settings by vm.settings.collectAsStateWithLifecycle()
            AppearanceScreen(
                background = settings.background,
                scrimEnabled = settings.scrimEnabled,
                scrimAlpha = settings.scrimAlpha,
                scrimStyle = settings.scrimStyle,
                topBarAlpha = settings.topBarAlpha,
                bubbleAlpha = settings.bubbleAlpha,
                onOpenBackground = { nav.navigate(Routes.GLOBAL_BACKGROUND) },
                onSetScrim = { enabled, alpha, style ->
                    vm.saveSettings {
                        it.copy(scrimEnabled = enabled, scrimAlpha = alpha, scrimStyle = style)
                    }
                },
                onSetTopBarAlpha = { v -> vm.saveSettings { it.copy(topBarAlpha = v) } },
                onSetBubbleAlpha = { v -> vm.saveSettings { it.copy(bubbleAlpha = v) } },
                onBack = { nav.popBackStack() },
            )
        }

        // v0.61.57：**全局**背景选择 —— 复用会话级那一页（图库 / 预设 / 删除都在那边），
        // 只把读写从 `updateSession` 换成 `saveSettings`。
        // ⚠️ 这是"两层设置"的第二层：会话设过以会话为准，没设过用这里的全局值。
        composable(Routes.GLOBAL_BACKGROUND) {
            val settings by vm.settings.collectAsStateWithLifecycle()
            ChatBackgroundScreen(
                background = settings.background,
                scrimEnabled = settings.scrimEnabled,
                scrimAlpha = settings.scrimAlpha,
                scrimStyle = settings.scrimStyle,
                onSetBackground = { id -> vm.saveSettings { it.copy(background = id) } },
                onSetScrim = { enabled, alpha, style ->
                    vm.saveSettings {
                        it.copy(scrimEnabled = enabled, scrimAlpha = alpha, scrimStyle = style)
                    }
                },
                onBack = { nav.popBackStack() },
            )
        }

        composable(Routes.SETTINGS_EMOJI_OPTIONS) {
            EmojiOptionsScreen(
                vm = vm,
                onBack = { nav.popBackStack() },
                onOpenGlobalEmoji = { nav.navigate(Routes.SETTINGS_EMOJI) },
            )
        }

        composable(Routes.SETTINGS_MEMORY_OPTIONS) {
            MemoryOptionsScreen(
                vm = vm,
                onBack = { nav.popBackStack() },
                onOpenMemories = { nav.navigate(Routes.SETTINGS_MEMORY) },
            )
        }

        composable(Routes.FEEDBACK) {
            // 已登录就把邮箱预填进联系方式 —— 用户不必再手打一遍自己账号
            // （没登录/没绑邮箱就是空，表单本来也不要求填）
            val auth by authVm.auth.collectAsStateWithLifecycle()
            FeedbackScreen(
                prefillContact = auth.email.orEmpty().ifBlank { auth.nickname },
                onBack = { nav.popBackStack() },
            )
        }

        composable(Routes.SETTINGS_BACKUP) {
            SettingsBackupScreen(vm = vm, onBack = { nav.popBackStack() })
        }

        composable(Routes.SETTINGS_ADVANCED) {
            SettingsAdvancedScreen(
                vm = vm,
                onBack = { nav.popBackStack() },
                onOpenCacheDiagnostics = { nav.navigate(Routes.SETTINGS_DIAGNOSTICS) },
            )
        }

        composable(Routes.SETTINGS_EMOJI) {
            // **全局库**（用户要求的「两个入口」之一）：入口 = 设置 → 功能设置 → 表情包
            EmojiPackScreen(vm = vm, onBack = { nav.popBackStack() }, scope = EmojiScope.Global)
        }

        composable(Routes.PERSONA_EMOJI) { entry ->
            val personaId = entry.arguments?.getString("personaId").orEmpty()
            // **专属库**（另一个入口）：入口 = 人设编辑页 / 人设详情页的「专属表情包」行
            EmojiPackScreen(
                vm = vm,
                onBack = { nav.popBackStack() },
                scope = EmojiScope.Persona(personaId),
            )
        }

        composable(Routes.SETTINGS_DIAGNOSTICS) {
            val sessions by vm.sessions.collectAsStateWithLifecycle()
            // ⚠️ 把「最近一次」的读数也传进去（v0.50.x）：
            //    只给累计值的话，诊断页答不了"现在到底怎么样" ——
            //    累计会被历史上没命中的请求长期拖住，聊半天也稀释不回来。
            val lastTurn by vm.lastTurnStats.collectAsStateWithLifecycle()
            CacheDiagnosticsScreen(
                sessions = sessions,
                lastTurn = lastTurn,
                // 命中率"没有数字"的三种含义要靠服务商画像分开（v0.51.0）——
                // 画像只认 baseUrl，所以在这里算好了传给屏幕
                providerOf = { s -> ProviderProfiles.resolve(vm.effectiveSettings(s).baseUrl) },
                onBack = { nav.popBackStack() },
            )
        }

        composable(Routes.SETTINGS_MEMORY) {
            val personas by vm.personas.collectAsStateWithLifecycle()
            MemoryPersonasScreen(
                personas = personas,
                // 选定角色后进该角色的记忆页（不带会话 → 只看人设级记忆）
                onOpenPersona = { personaId -> nav.navigate(Routes.memoryOfPersona(personaId)) },
                onBack = { nav.popBackStack() },
            )
        }

        composable(Routes.SETTINGS_ACCOUNT) {
            AccountSecurityScreen(
                authVm = authVm,
                onBack = { nav.popBackStack() },
                // 改密码后服务器把该用户的令牌全删了 —— 本地登录态已在 VM 里清掉，
                // 这里把人送回登录页并清栈。不这么做的话，App 会拿着一个已作废的令牌
                // 在各个页面之间持续 401，用户只会看到"到处都连不上服务器"。
                onPasswordChanged = {
                    nav.navigate(Routes.AUTH) {
                        popUpTo(0) { inclusive = true }
                    }
                },
            )
        }
    }

        // 灵动岛：浮在 NavHost 之上，跨页面存活
        DynamicIslandHost()
    }
}
