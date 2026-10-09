package ai.yuki.chuxue.data

import android.content.Context
import android.os.Build
import ai.yuki.chuxue.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * **崩溃与错误的日志**（v0.53.0）。
 *
 * ## 它取代了什么，为什么
 * 上一版是那个 `RequestLog`（v0.46.7，已删）：**每轮聊天**都把请求结构追加到
 * **手机下载目录**的 `yuki_debug.txt`。那是 v0.46.7 为查"命中率上不去"临时加的诊断，
 * 代价有三条：
 * 1. **每次聊天都在写外部存储** —— 为一个一次性问题付长期成本；
 * 2. **落在下载目录**（公开位置）—— 虽然只写结构不写正文，但那仍是最不该放日志的地方；
 * 3. **它没有"出事"这个概念** —— 正常聊天也在写，真正的异常反而淹没在里头。
 *
 * 用户 2026-09-30：「把…输出到手机下载目录的 yuki_bug 删了，改为触发崩溃和 bug 的报错日志」。
 * 于是改成：**平时一个字节都不写，只有崩了 / 出错了才落一份**，
 * 并且落在**应用私有目录**（`filesDir/crashes/`）—— 不需要任何权限，也不占用户的下载目录。
 *
 * ## 记什么
 * 时间、应用版本、Android 版本与机型、异常类型与 message、完整堆栈（含 `caused by`）。
 * **不主动**收集用户对话内容 —— 我们的代码里没有一处会把聊天记录写进来。
 *
 * ⚠️ v0.61.21 更正一句过强的承诺：**异常 message 里可能夹着服务端的响应正文**。
 *    因为 `DeepSeekClient.describeHttpError` 会把响应体截取 300 字塞进异常 message
 *   （`raw.take(300)`），而这类 message 会被原样落进这里。若某个兼容端点回显了请求内容，
 *    那段回显就可能跟着进日志。
 *    实际暴露面很窄：日志落在**应用私有目录**、最多 10 份、**不会自动上传**，
 *    只有用户主动把文件发给别人时才会外流。所以这里保留 message（丢掉它，日志就失去
 *    排查价值），但**不要把"不含任何用户内容"当成承诺**。
 *    要真做到零风险，得从 `DeepSeekClient` 那头不把响应正文写进异常 —— 那是另一处取舍。
 *
 * ## ⚠️ 它自己绝不能抛
 * 崩溃处理器里再抛异常 = 崩溃信息彻底丢失，而且会把系统原本的处理流程搞乱。
 * 所以全程 `runCatching`，写不进去就静默放弃。
 */
object CrashLog {

    const val DIR = "crashes"
    private const val PREFIX = "crash-"
    private const val EXT = ".txt"

    /** 最多留几份 —— 崩溃日志只在排查时有用，积压没有意义。 */
    const val MAX_FILES = 10

    // ⚠️ 这里**不放共享的 `SimpleDateFormat` 实例** —— 它本身不是线程安全的，
    //    而崩溃可能发生在任意线程（甚至几个线程同时崩）。共享实例在并发下会产出
    //    错乱字符串、甚至抛异常；而这个异常会被 `record` 外层的 runCatching 吞掉，
    //    结果是**这一份崩溃日志直接丢失** —— 恰恰是最不该发生的事。
    //    代价只是每次格式化多建一个对象，在崩溃这种频率下完全无所谓。
    private const val FILE_STAMP_PATTERN = "yyyyMMdd-HHmmss"
    private const val HUMAN_STAMP_PATTERN = "yyyy-MM-dd HH:mm:ss"

    private fun fileStamp(at: Long): String =
        SimpleDateFormat(FILE_STAMP_PATTERN, Locale.US).format(Date(at))

    private fun humanStamp(at: Long): String =
        SimpleDateFormat(HUMAN_STAMP_PATTERN, Locale.US).format(Date(at))

    fun dir(context: Context): File = File(context.filesDir, DIR)

    /** 全部崩溃日志，**新 → 旧**。 */
    fun list(context: Context): List<File> =
        (dir(context).listFiles { f -> f.isFile && f.name.startsWith(PREFIX) } ?: emptyArray())
            .sortedByDescending { it.lastModified() }

    fun latest(context: Context): File? = list(context).firstOrNull()

    fun totalBytes(context: Context): Long = list(context).sumOf { it.length() }

    /** 用户主动清空（"崩溃日志"这种东西不该赖着不走）。 */
    fun clear(context: Context) {
        list(context).forEach { runCatching { it.delete() } }
    }

    /** 记一次崩溃 / 未捕获异常。**绝不抛。** */
    fun record(context: Context, t: Throwable, at: Long = System.currentTimeMillis()) {
        runCatching {
            dir(context).mkdirs()
            File(dir(context), PREFIX + fileStamp(at) + EXT)
                .writeText(
                    buildText(
                        t = t,
                        at = at,
                        appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                        // ⚠️ 这两项在 JVM 单测里取不到（android.os.Build 是 stub，会返回 null），
                        //    所以由调用方取好再传进来 —— 见 buildText 的注释。
                        system = runCatching {
                            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
                        }.getOrDefault("未知"),
                        device = runCatching { "${Build.MANUFACTURER} ${Build.MODEL}" }
                            .getOrDefault("未知"),
                    ),
                    Charsets.UTF_8,
                )
            enforceLimit(context)
        }
    }

    /**
     * 组装日志正文。
     *
     * ⚠️ 它是**纯函数**：环境信息全部由参数传入，自己**不碰** `Context` 与 `android.os.Build`
     * （后者在 JVM 单测里是 stub，直接读会得到 null 而不是报错 —— 那种"看着能跑、
     * 其实什么都没验"的测试比没有更糟）。
     * 这样"崩溃日志长什么样"就能在 JVM 上钉死；崩溃处理器本身测不了，
     * 但日志格式恰恰是它最容易悄悄坏掉的部分。
     */
    fun buildText(
        t: Throwable,
        at: Long,
        appVersion: String,
        system: String,
        device: String,
    ): String = buildString {
        appendLine("时间: " + humanStamp(at))
        appendLine("版本: $appVersion")
        appendLine("系统: $system")
        appendLine("机型: $device")
        appendLine()
        appendLine("异常: ${t.javaClass.name}")
        appendLine("消息: ${t.message.orEmpty()}")
        appendLine()
        appendLine(stackOf(t))
    }

    /** 完整堆栈（含 `caused by` 链）—— `Throwable.stackTraceToString()` 要 API 31，所以手写。 */
    private fun stackOf(t: Throwable): String =
        StringWriter().also { sw -> PrintWriter(sw).use { t.printStackTrace(it) } }.toString()

    private fun enforceLimit(context: Context) {
        val files = list(context)
        if (files.size <= MAX_FILES) return
        files.takeLast(files.size - MAX_FILES).forEach { runCatching { it.delete() } }
    }

    /**
     * 装上**全局未捕获异常处理器**（在 `Application.onCreate` 调一次）。
     *
     * ⚠️ 记完之后**必须把异常交还给原来的 handler** —— 不交还的话，
     * 系统的"应用已停止"流程就不会走，App 会停在半死不活的状态：
     * 用户看到的是**卡住**而不是**崩溃**，那比崩溃更难理解、更难反馈。
     */
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { record(app, throwable) }
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                // ⚠️ 没有上一个 handler 时**必须自己兜住退出** ——
                //    不杀进程的话，应用会停在一个半死不活的状态：
                //    用户看到的是「卡住」而不是「崩溃」，那比崩溃更难理解、更没法反馈
                //   （而"应用已停止"那一屏恰恰是他唯一能截图给我们的东西）。
                runCatching {
                    android.os.Process.killProcess(android.os.Process.myPid())
                    kotlin.system.exitProcess(10)
                }
            }
        }
    }
}
