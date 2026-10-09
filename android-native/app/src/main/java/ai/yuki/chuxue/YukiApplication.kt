package ai.yuki.chuxue

import android.app.Application
import ai.yuki.chuxue.data.CrashLog
import ai.yuki.chuxue.data.memory.MemoryExtractionScheduler
import ai.yuki.chuxue.ui.components.Island
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 应用入口。
 *
 * ## 它存在的唯一理由是给后台任务一个「与进程同寿命」的宿主
 * 自动记忆提取（开发文档 §8.3）需要在用户聊完天之后、**没有界面在跑的时候**醒过来做事。
 * 挂在 Activity 或 ViewModel 上都不行 —— 它们的生命周期结束得比这早得多，
 * 用户一退出聊天页提取就被取消了。
 *
 * ## 为什么不上 WorkManager
 * 见 [MemoryExtractionScheduler] 的类注释：本项目没有该依赖，而它的核心价值
 * （进程死后仍调度）对这个场景不成立 —— 提取是"聊完天顺便记一下"，
 * 下次打开 App 补做完全等效（节流状态是持久化的）。
 */
class YukiApplication : Application() {

    /**
     * 与进程同寿命的作用域。
     *
     * - [SupervisorJob]：一个后台任务失败不该牵连其他后台任务（将来还会有别的）。
     * - [Dispatchers.Default]：这里只做"调度"，具体活儿各自 `withContext` 决定线程
     *   （提取走 `Dispatchers.IO`，数据库走 Room 自己的调度器）。
     */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // 崩溃日志（v0.53.0）：装上全局未捕获异常处理器。
        // ⚠️ 放在**最前面** —— 后面任何一行崩了都该被记下来。
        //    它取代了 v0.46.7 那个「每轮聊天往手机下载目录写请求结构」的诊断日志
        //（用户 2026-09-30 要求删掉，改为只记崩溃与报错）。
        CrashLog.install(this)

        // 自动记下记忆之后给一句轻提示（用户 2026-09-28 要求：聊天界面能看见"她记住了"）。
        // 用**灵动岛**而不是页面内的 Banner：提取在后台跑，用户此刻可能在任意页面，
        // 而灵动岛挂在根布局上、跨页面都看得见 —— 这正是它存在的理由。
        MemoryExtractionScheduler(
            this,
            appScope,
            onMemoriesSaved = { n ->
                Island.ok(if (n == 1) "记住了一件事" else "记住了 $n 件事")
            },
            onExtractionFailed = { e ->
                // 只打断「用户自己能解决」的失败（余额 / Key）；网络抖动不值得报警。
                // 去重由调度器负责（同类失败 30 分钟内只报一次）
                memoryFailureHint(e.message.orEmpty())?.let { Island.warn(it) }
            },
        ).start()
    }
}

/**
 * 把提取异常翻成一句**该不该给用户看**的话；`null` = 不值得打断。
 *
 * ⚠️ 匹配靠关键词，所以它**依赖 `DeepSeekClient` 的错误文案** —— 那边改了措辞，
 * 这里会**静默地不再提示**（而不是报错）。这是有意的取舍：
 * 宁可漏提示，也不要把网络抖动也变成弹窗。
 */
private fun memoryFailureHint(message: String): String? = when {
    message.contains("余额", true) || message.contains("insufficient", true) ->
        "API 余额不足，暂时记不下新的事"
    message.contains("401", true) || message.contains("unauthorized", true) ->
        "API Key 好像失效了，记不下新的事"
    else -> null
}
