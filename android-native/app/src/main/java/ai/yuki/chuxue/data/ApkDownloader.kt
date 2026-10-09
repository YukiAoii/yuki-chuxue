package ai.yuki.chuxue.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 下载新版本 APK（v0.50.0）。
 *
 * ## 落盘位置
 * `cacheDir/updates/yuki-<versionCode>.apk`
 *
 * ⚠️ 这个目录与 `res/xml/file_paths.xml` 里的 `<cache-path name="updates"
 * path="updates/" />` **必须一致**。不一致的后果是
 * `FileProvider.getUriForFile` 抛 `IllegalArgumentException`（"Failed to find
 * configured root"）—— 而那是在**下载完成之后**才炸，用户已经等了几分钟。
 *
 * 文件名带 `versionCode`（不是 `latest.apk`）：
 * 换版本后旧文件不会与新文件混淆，也便于"已下载过同一版本就别再下"的判断。
 *
 * ## ⚠️ 完整性：只认字节数
 * 写完后比对**文件实际大小 == 服务端给的 `apkSize`**，不等则**删掉并报错**。
 *
 * 为什么不做哈希校验：服务端没提供哈希字段，加一个就要动后端表结构与发布流程。
 * 字节数能挡住**绝大多数真实故障**（连接中断、磁盘写满、代理截断），
 * 而它们正是"留下半截 APK"的全部常见原因。真正恶意的中间人篡改
 * 不是字节数能挡的 —— 但那需要 HTTPS 被攻破，超出本功能的范围（且 App 本来就
 * 是 HTTPS 直连，不经过任何代理层）。**这个取舍要写清楚，不能假装它很安全。**
 *
 * ## 为什么不做前台服务
 * 下载通常在用户**正盯着更新弹窗**时进行（他刚点了「立即更新」），
 * 应用在前台。若中途退到后台，系统可能杀掉下载 —— 这时重新进入会
 * **从头再来或复用已下部分**（见 `existingFor`）。用前台服务要新起通知渠道、
 * 处理 Android 14 的 `foregroundServiceType` 限制，成本明显高于收益。
 */
object ApkDownloader {

    /** 下载进度：0..1；[done] 为真时 [fraction] 恒为 1。 */
    data class Progress(
        val downloaded: Long,
        val total: Long,
        val done: Boolean = false,
    ) {
        val fraction: Float
            get() = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else 0f
    }

    /** 下载结果。 */
    sealed interface Result {
        data class Ok(val file: File) : Result

        /** 失败原因已经是**给人看的**中文（UI 直接显示，不必再翻译） */
        data class Failed(val message: String) : Result
    }

    // ⚠️ 超时按下载场景给：连接 10s，**读 30s**（不是 8s）——
    //    读超时是"两次数据之间的最大间隔"，不是总时长。
    //    用接口那套 8s 会在网络稍慢时误判为失败。
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** 本 App 存放更新包的目录（与 file_paths.xml 的 `updates/` 对应）。 */
    fun dir(context: Context): File = File(context.cacheDir, "updates").apply { mkdirs() }

    fun fileFor(context: Context, versionCode: Int): File =
        File(dir(context), "yuki-$versionCode.apk")

    /**
     * 已下载且**大小正确**的同版本包；没有则 null。
     *
     * ⚠️ 判据里带字节数校验（不是"文件存在就算"）：
     * 上次中断留下的半截文件若被当成"已下载好"，安装会失败且原因难查。
     */
    fun existingFor(context: Context, versionCode: Int, expectedSize: Long): File? {
        val f = fileFor(context, versionCode)
        return f.takeIf { it.isFile && expectedSize > 0 && it.length() == expectedSize }
    }

    /**
     * 下载到本地。**不抛异常** —— 失败用 [Result.Failed] 表达（含中文原因）。
     *
     * @param url   **绝对**地址（[RemoteRelease.apkUrl] 是相对路径，调用方需先拼）
     * @param expectedSize 服务端给的字节数，用于完整性校验
     */
    suspend fun download(
        context: Context,
        versionCode: Int,
        url: String,
        expectedSize: Long,
        onProgress: (Progress) -> Unit = {},
    ): Result = withContext(Dispatchers.IO) {
        // 已有完整包 → 直接复用（用户重复点「立即更新」不该重下 13MB）
        existingFor(context, versionCode, expectedSize)?.let { return@withContext Result.Ok(it) }

        val target = fileFor(context, versionCode)
        // 先清掉可能存在的半截文件，避免与本次写入混在一起
        target.delete()

        val tmp = File(dir(context), target.name + ".part")
        try {
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) {
                    return@withContext Result.Failed("服务器返回 ${res.code}，下载失败")
                }
                val body = res.body ?: return@withContext Result.Failed("服务器没有返回内容")
                val total = if (expectedSize > 0) expectedSize else body.contentLength()

                var written = 0L
                body.byteStream().use { input ->
                    tmp.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            output.write(buf, 0, n)
                            written += n
                            onProgress(Progress(written, total))
                        }
                        output.flush()
                    }
                }

                // ⚠️ 完整性：大小不符就**丢弃**，绝不把半截包交给安装器
                if (expectedSize > 0 && written != expectedSize) {
                    tmp.delete()
                    return@withContext Result.Failed(
                        "下载不完整（收到 $written 字节，应为 $expectedSize）。请重试。",
                    )
                }
                if (!tmp.renameTo(target)) {
                    // rename 失败时退化为复制 —— 极少数文件系统会这样，
                    // 但那不该让整个更新失败
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
                onProgress(Progress(written, total, done = true))
                Result.Ok(target)
            }
        } catch (e: Exception) {
            tmp.delete()
            Result.Failed("下载中断：${e.message ?: "网络异常"}")
        }
    }

    /**
     * 把一个 APK 相对地址拼成绝对地址。
     *
     * ⚠️ 后端存的是**相对路径**（它不硬编码自己的域名），
     * 由客户端统一拼上 `ServerConfig.BASE_URL`。纯函数，便于单测。
     */
    fun absoluteUrl(path: String): String = when {
        path.isBlank() -> ""
        path.startsWith("http") -> path
        else -> ServerConfig.BASE_URL + path
    }
}
