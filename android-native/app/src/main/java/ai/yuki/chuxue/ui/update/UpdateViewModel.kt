package ai.yuki.chuxue.ui.update

import ai.yuki.chuxue.BuildConfig
import ai.yuki.chuxue.data.ApkDownloader
import ai.yuki.chuxue.data.ApkInstaller
import ai.yuki.chuxue.data.RemoteRelease
import ai.yuki.chuxue.data.UpdateApi
import ai.yuki.chuxue.data.UpdateDecision
import ai.yuki.chuxue.data.VersionPolicy
import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * 应用内更新的状态机（v0.50.0）。
 *
 * ## 为什么用 `AndroidViewModel`
 * 下载要写 `cacheDir`、安装要用 `FileProvider` —— 两者都需要 Context。
 * 与 `ChatViewModel` 同道（它也是 `AndroidViewModel`，因为要 Room 与文件）。
 *
 * ## 状态机
 * ```
 * Idle ──check──> Checking ──> Available(decision)   ← 有更新，等用户点
 *                    │                │
 *                    └──> Idle        └──download──> Downloading(p) ──> Ready(file)
 *                                                        │                  │
 *                                                        └─> Failed(msg)    └─> installIntent()
 * ```
 *
 * ## ⚠️ 检查失败**必须静默**
 * 更新检查在启动路径上。没网、后端挂了、用户在不稳定的移动网络 ——
 * 这些都不该让用户看到任何东西。**"检查更新失败"是一句没有信息量的打扰**：
 * 用户既不能做什么，也不在乎。只有**他主动点**「检查更新」时才需要反馈
 *（那时他正等着结果，静默反而像坏了）—— 见 [check] 的 `manual` 参数。
 */
class UpdateViewModel(app: Application) : AndroidViewModel(app) {

    sealed interface State {
        /** 什么都没发生 */
        data object Idle : State

        /** 正在查（只有手动检查时才需要上屏） */
        data object Checking : State

        /**
         * **已是最新**（只有手动检查时才会出现）。
         *
         * ⚠️ 它必须是**独立状态**，不能复用 [Failed]。
         * 复用会让界面把"你已经是最新版"渲染成失败弹窗 ——
         * 标题「更新没有完成」、按钮「重试 / 退出应用」——
         * 用户 2026-09-30 报的「弹窗容器顶部是更新没有完成、
         * 下面的却显示已经是最新版」正是这条。
         * **"什么都没发生"和"出错了"是两件事，不该共用一个状态。**
         */
        data object Latest : State

        /** 有更新，等用户决定 */
        data class Available(val release: RemoteRelease, val decision: UpdateDecision) : State

        /** 正在下载 */
        data class Downloading(val progress: ApkDownloader.Progress) : State

        /** 下好了，可以安装 */
        data class Ready(val file: File) : State

        /**
         * 失败（只有用户主动操作时才会出现）。
         *
         * ⚠️ [retryable] 是**修 bug 加的**（2026-09-30）：`download()` 原来只认
         * `State.Available`，于是「下载失败」之后点弹窗上的「重试」会命中
         * `as? State.Available ?: return` —— 直接返回，**那颗按钮其实是个装饰**。
         * 带上"当时要下的那个版本"，重试才真的能重来。
         * 「无下载地址」这类**不可重试**的失败传 null。
         */
        data class Failed(
            val message: String,
            /**
             * 这次失败要不要**拦住用户**（只有强制更新才拦）。
             *
             * ⚠️ 它决定弹窗给不给「退出应用」：强制更新失败时那是**出路**
             * （"不能关"与"出不去"是两件事，见 `UpdateDialog` 的类注释）；
             * 而**非强制**时给「退出应用」很荒谬 —— 用户只是点了一下「检查更新」，
             * 凭什么要退出应用？（用户 2026-09-30 报的就是这条）
             */
            val forced: Boolean = false,
            val retryable: RemoteRelease? = null,
        ) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * 提示过哪些版本 —— **跨启动**记忆（照 SQYU 工具箱的做法）。
     *
     * ⚠️ 原来是个内存变量（`dismissedFor`），**重启就丢** ——
     * 于是每次打开 App 都会为同一个版本弹一次窗，用户烦。
     * 现在落进 SharedPreferences：同一版本提示过一次就不再打扰。
     *
     * ⚠️ 这不等于"用户再也没机会更新"：关于页的「检查更新」是**手动入口**，
     * 手动检查**永远显示**结果（见 [runCheck] 的 `manual` 分支）。
     * "启动时静默提醒一次 + 随时可手动查" 正是本项目选定的更新策略。
     */
    private val prefs by lazy {
        getApplication<Application>().getSharedPreferences("yuki_update", Context.MODE_PRIVATE)
    }

    private var promptedCode: Int
        get() = prefs.getInt(KEY_PROMPTED_CODE, -1)
        set(v) { prefs.edit().putInt(KEY_PROMPTED_CODE, v).apply() }

    // 更新日志不在这里 —— 它由「关于」页自取（`UpdateApi.changelog()`）：
    // 那是纯展示，与"要不要更新"的状态机没有耦合，没必要跨三层传参。

    /**
     * 启动时调一次。**失败静默**（见类注释）。
     *
     * ⚠️ 不阻塞：它在 `MainActivity` 的 `LaunchedEffect` 里与 `verifyOnLaunch` 并列，
     * 各跑各的，谁都不等谁。
     */
    fun checkOnLaunch() {
        viewModelScope.launch { runCheck(manual = false) }
    }

    /** 「关于」页的手动入口。**失败要有反馈**（用户正等着）。 */
    fun checkManually() {
        viewModelScope.launch { runCheck(manual = true) }
    }

    private suspend fun runCheck(manual: Boolean) {
        if (manual) _state.value = State.Checking
        val release = UpdateApi.check(BuildConfig.VERSION_CODE)
        val decision = VersionPolicy.decide(BuildConfig.VERSION_CODE, release)

        when {
            !decision.hasUpdate -> {
                // 手动检查时也要说一句"已是最新" —— 否则用户点了没反应，像坏了。
                // ⚠️ 走 State.Latest 而不是 State.Failed：见 State.Latest 的注释
                //    （用户 2026-09-30 报的"顶部说更新没有完成、下面说已是最新"就是这条）。
                _state.value = onNoUpdate(manual)
            }
            // 本次启动已经点过「稍后」→ 不再打扰（手动检查仍然显示）
            // 这个版本已经提示过 → 不再打扰（手动检查仍然显示）
            !manual && promptedCode == release?.versionCode -> _state.value = State.Idle
            release != null -> {
                promptedCode = release.versionCode
                _state.value = State.Available(release, decision)
            }
            else -> _state.value = State.Idle
        }
    }

    /** 用户点了「稍后」。 */
    fun dismiss() {
        // ⚠️ 这里不再写 `dismissedFor` —— 「已提示过」由 [promptedCode] 在
        //    `runCheck` 决定弹窗时就已经记下了。点「稍后」只是关掉当前这个窗，
        //    语义上不该额外改记忆（否则手动检查也会跟着被"记住"）。
        _state.value = State.Idle
    }

    /** 用户点了「立即更新」。 */
    fun download() {
        // ⚠️ 也必须能**从 Failed 重试**（2026-09-30）：原来只认 `State.Available`，
        //    于是失败弹窗上那颗「重试」永远命中 `?: return`、点了什么都不会发生。
        val cur = _state.value
        val release = when (cur) {
            is State.Available -> cur.release
            is State.Failed -> cur.retryable
            else -> null
        } ?: return
        // 这次失败要不要拦住用户 —— 重试时沿用上一次的判定
        val forced = when (cur) {
            is State.Available -> cur.decision.force
            is State.Failed -> cur.forced
            else -> false
        }
        val url = ApkDownloader.absoluteUrl(release.apkUrl)
        if (url.isBlank()) {
            // 地址不可用**不可重试**（重试还是同一个空地址），但要不要拦人照旧
            _state.value = State.Failed("这个版本没有提供下载地址", forced = forced)
            return
        }
        viewModelScope.launch {
            _state.value = State.Downloading(ApkDownloader.Progress(0, release.apkSize))
            val result = ApkDownloader.download(
                context = getApplication(),
                versionCode = release.versionCode,
                url = url,
                expectedSize = release.apkSize,
            ) { p -> _state.value = State.Downloading(p) }

            _state.value = when (result) {
                is ApkDownloader.Result.Ok -> State.Ready(result.file)
                // 带上 release 与 forced —— 失败弹窗上的「重试」才有东西可重来，
                // 且只在强制失败时才保留「退出应用」这条出路
                is ApkDownloader.Result.Failed ->
                    State.Failed(result.message, forced = forced, retryable = release)
            }
        }
    }

    /**
     * 造安装 Intent。**不在这里启动** —— 启动交给界面层（见 [ApkInstaller] 注释）。
     *
     * @return null 表示造不出来（文件没了 / FileProvider 配置不对），调用方据此提示
     */
    fun installIntent(): android.content.Intent? {
        val file = (_state.value as? State.Ready)?.file ?: return null
        return ApkInstaller.installIntent(getApplication(), file)
    }

    /** 是否已允许安装未知来源应用（为假时界面先引导用户去设置）。 */
    fun canInstall(): Boolean = ApkInstaller.canInstall(getApplication())

    fun settingsIntent(): android.content.Intent =
        ApkInstaller.settingsIntent(getApplication())

    /** 关掉弹窗（下载中不许关，见界面层的判断）。 */
    fun reset() {
        _state.value = State.Idle
    }

    companion object {
        private const val KEY_PROMPTED_CODE = "prompted_code"

        /**
         * **纯函数**：检查完、发现"没有更新"时，下一个状态是什么。
         *
         * 抽出来是因为它是**唯一一处会误导用户的分叉**，此前藏在 `runCheck` 里、
         * 被 `viewModelScope` 与网络调用包着，单测覆盖不到。现在 `UpdateFlowTest`
         * 可以**直接**钉住它（不需要 Application、不需要网络、不需要 Android）。
         *
         * @param manual 是不是用户**主动**点的「检查更新」。
         *   主动点 → 必须给反馈（[State.Latest]），否则用户以为坏了；
         *   启动时静默检查 → 什么都不显示（[State.Idle]）。
         */
        fun onNoUpdate(manual: Boolean): State =
            if (manual) State.Latest else State.Idle
    }
}
