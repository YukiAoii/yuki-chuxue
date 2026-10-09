package ai.yuki.chuxue.data

/**
 * 服务端下发的一个版本（`GET /api/app/version` 的 `latest` 字段）。
 *
 * ⚠️ **判据只认 [versionCode]**（整数），[versionName] 仅用于展示。
 *
 * 这不是洁癖：按字符串比 `"0.10.0"` 与 `"0.9.0"`，字典序会得出 `0.10 < 0.9`，
 * 而语义相反。本项目历史真发生过 `versionName` 与 `versionCode` 不同步
 *（见 `项目交接记录` 第八条"版本号不一致"），一旦拿名字去比就会误判"没有新版本"，
 * 而用户明明收不到更新。
 */
data class RemoteRelease(
    val versionCode: Int,
    val versionName: String,
    /** 更新日志（纯文本，客户端按行渲染） */
    val notes: String,
    /** 服务端显式标记的强制更新 */
    val force: Boolean,
    /** 低于此 versionCode 一律不可用（0 = 未设置） */
    val minSupportedCode: Int,
    /** APK 相对地址，如 `/d/yuki.apk`（客户端拼 `ServerConfig.BASE_URL`） */
    val apkUrl: String,
    /** APK 字节数 —— 下载完整性校验用 */
    val apkSize: Long,
) {
    /** 服务端给的地址是否可用（空地址不能进入下载流程） */
    val hasDownload: Boolean get() = apkUrl.isNotBlank()
}

/**
 * 更新日志里的一条（`GET /api/app/changelog` 的 `items[]`）。
 *
 * ## 与 [RemoteRelease] 的关系
 * [RemoteRelease] 回答"**要不要**更新"（只有最新一版，且带下载地址）；
 * 这一条回答"**都更新过什么**"（列历史，纯展示）。
 *
 * ⚠️ 它**没有** `apkUrl` —— 后端刻意不回传（见后端 `app_changelog` 的注释）：
 * 这一页只展示日志，下载地址属于更新流程，少一个字段就少一处入口。
 *
 * ⚠️ 后端只列**装得出来**的版本（没绑好包的不出现）。
 * 否则用户会看到"0.50.2 修了什么什么"却根本升不到那一版。
 */
data class ChangelogEntry(
    val versionCode: Int,
    val versionName: String,
    val notes: String,
    val force: Boolean,
    /** `published_at`，服务端给的 ISO 串。客户端只做展示，不做解析。 */
    val publishedAt: String,
)

/**
 * 一次更新检查的结论。
 *
 * @param hasUpdate 有更新可用
 * @param force     **必须**更新（用户不能跳过）
 * @param reason    为什么是这个结论 —— 只用于日志/排查，不上屏
 */
data class UpdateDecision(
    val hasUpdate: Boolean,
    val force: Boolean,
    val reason: String = "",
) {
    companion object {
        val NONE = UpdateDecision(hasUpdate = false, force = false, reason = "已是最新")
    }
}

/**
 * 更新判定（v0.50.0，**纯函数**）。
 *
 * ## 为什么下沉到 data 层、做成纯函数
 * 与压缩触发同一套路：判定若长在 `ViewModel` 里（要 Android 环境），
 * 那条**唯一会弹窗打断用户**的分叉就单测覆盖不到。抽出来之后，
 * 每个边界都能在 JVM 上钉死。
 *
 * ## 判定规则（与后端 `app_version` 必须一致）
 * ```
 * hasUpdate = latest != null && latest.versionCode > localCode
 * force     = hasUpdate && (latest.force || localCode < latest.minSupportedCode)
 * ```
 *
 * ## ⚠️ 三条硬护栏（都是"会把用户坑死"的场景）
 *
 * 1. **地址为空时强制降级为"不强制"** —— 否则用户被锁在一个不能关的弹窗里，
 *    而点「立即更新」又拿不到东西。**能关掉的旧版本**永远好过**出不去的新版本**。
 * 2. **`apkSize <= 0` 同样降级** —— 没有大小就无法校验下载完整性；
 *    装上一个半截的包会让安装失败且报错含糊。
 * 3. **服务端版本比本地旧时不提示**（回滚 / 测试环境）—— 只认 `>`，不是 `!=`。
 *
 * ## 它**不**负责的事
 * 不判断网络、不重试、不弹窗 —— 它只回答"该不该更新、要不要强制"。
 */
object VersionPolicy {

    fun decide(localCode: Int, latest: RemoteRelease?): UpdateDecision {
        if (latest == null) return UpdateDecision.NONE

        // 护栏 3：只认"服务端更新"，不是"不同"
        if (latest.versionCode <= localCode) {
            return UpdateDecision(hasUpdate = false, force = false, reason = "已是最新")
        }

        val wanted = latest.force || (localCode > 0 && localCode < latest.minSupportedCode)

        // 护栏 1 + 2：拿不到可用的包，就不该把用户锁住
        if (!latest.hasDownload) {
            return UpdateDecision(
                hasUpdate = true,
                force = false,
                reason = if (wanted) "本应强制，但服务端没给下载地址 → 降级为非强制" else "有更新",
            )
        }
        if (latest.apkSize <= 0L) {
            return UpdateDecision(
                hasUpdate = true,
                force = false,
                reason = if (wanted) "本应强制，但缺 apk_size 无法校验完整性 → 降级为非强制" else "有更新",
            )
        }

        return UpdateDecision(
            hasUpdate = true,
            force = wanted,
            reason = when {
                latest.force -> "服务端标记强制"
                localCode < latest.minSupportedCode ->
                    "本地 $localCode 低于最低支持 ${latest.minSupportedCode}"
                else -> "有更新"
            },
        )
    }
}
