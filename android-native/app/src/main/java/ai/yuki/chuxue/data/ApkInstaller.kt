package ai.yuki.chuxue.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * 把下载好的 APK 交给系统安装器（v0.50.0）。
 *
 * ## ⚠️ 为什么必须是 `content://` 而不是 `file://`
 * `targetSdk >= 24` 起，跨应用传 `file://` URI 会抛 **`FileUriExposedException`**。
 * 下载好的 APK 在自己的缓存目录里，要把**那个文件**交给系统安装器 ——
 * 这正是被禁的行为（安装器是另一个进程，读不到我们的私有目录）。
 * 唯一合法路径是 `FileProvider.getUriForFile` + 临时读权限。
 *
 * ## 它**不**负责 startActivity
 * 这里只**造 Intent**，真正的启动在 UI 层做。原因：
 * ① 便于单测（造 Intent 可以断言，启动不能）；
 * ② 启动时机要配合界面状态（例如先关掉弹窗再启动，避免两个界面打架）。
 *
 * ## ⚠️ 三条平台约束（都来自 Android 官方行为，本机无法验证）
 * 1. **需 `REQUEST_INSTALL_PACKAGES`**（已在 AndroidManifest 声明）
 * 2. **需用户手动开启「安装未知应用」** —— 用 [canInstall] 检测，为假时引导用户去设置页。
 *    ⚠️ 这是**每个用户第一次更新时都要做一次**的动作，不是异常路径，
 *    所以引导文案要写得像"正常步骤"而不是"出错了"。
 * 3. **Android 10+ 后台启动 Activity 受限**：应用在前台时不受影响；
 *    若用户把 App 切到后台，`startActivity` 会**静默失败**（不抛异常）。
 *    所以调用方应在**用户可见时**才启动安装（我们只在弹窗上点按钮时做，满足条件）。
 */
object ApkInstaller {

    /** 本 App 的 FileProvider authority（与 AndroidManifest 的 `${applicationId}.fileprovider` 一致）。 */
    fun authority(context: Context): String = "${context.packageName}.fileprovider"

    /**
     * 是否允许安装未知来源应用。
     *
     * ⚠️ 只有 Android 8.0（API 26）起才有这个按应用维度的开关 —— 而本项目
     * `minSdk = 26`，所以**不需要** `Build.VERSION` 分支，直接用即可。
     * （写这个判断是为了让 minSdk 变化时有人能立刻发现这里需要改。）
     */
    fun canInstall(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    /**
     * 跳到「安装未知应用」的设置页。
     *
     * ⚠️ 用 `ACTION_MANAGE_UNKNOWN_APP_SOURCES` + 本应用包名 —— 这会让系统
     * **直接定位到本应用那一项**，而不是把用户丢到一串应用列表里自己找。
     */
    fun settingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:${context.packageName}"))
            // 从非 Activity 上下文启动时需要
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * 造一个"用系统安装器打开这个 APK"的 Intent。
     *
     * ⚠️ **两个 flag 都不能少**：
     * - `FLAG_GRANT_READ_URI_PERMISSION`：把该 content URI 的**临时读权限**
     *   授给安装器。不加的话安装器打开文件会 `SecurityException`。
     * - `FLAG_ACTIVITY_NEW_TASK`：调用方若用非 Activity 上下文也不会崩。
     *
     * @return 失败返回 null（文件不存在、或落在 FileProvider 未配置的目录 ——
     *         后者会抛 `IllegalArgumentException`，被 `runCatching` 兜住）
     */
    fun installIntent(context: Context, apk: File): Intent? = runCatching {
        if (!apk.isFile) return null
        val uri = FileProvider.getUriForFile(context, authority(context), apk)
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }.getOrNull()
}
