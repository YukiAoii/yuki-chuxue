package ai.yuki.chuxue.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ai.yuki.chuxue.data.Store

/**
 * 开机自启「后台常驻」（v0.61.50）。
 *
 * ## 为什么需要它
 * 审查指出：`ProactiveWatchService` 原来只有 `MainActivity` 一个启动入口 ——
 * **设备重启后、用户没打开 App 之前，服务永远不会起**，"后台常驻"名不副实。
 *
 * ## 它做什么
 * 收到 `BOOT_COMPLETED` 后，按**与 App 启动时同一套判据**决定起不起：
 * 有云端人设（`isCloudMemory`）+ 通知可用。判据复用 [ProactiveWatchService.sync]，
 * 不在这里重写一遍。
 *
 * ⚠️ `BroadcastReceiver.onReceive` 必须在**主线程、且很快返回** ——
 * `Store` 是本地文件读（同步、毫秒级），这里直接读没问题；不做任何网络。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        runCatching {
            val personas = when (val loaded = Store(context).loadPersonas()) {
                is Store.Loaded.Ok -> loaded.value
                else -> return
            }
            ProactiveWatchService.sync(
                context,
                // ⚠️ 2026-10-06：判据 = 「Ta 主动来找我」开关（与 MainActivity 同一口径）。
                shouldRun = personas.any { it.isCloudMemory && it.proactiveEnabled } &&
                    ProactiveNotifier.canNotify(context),
            )
        }
    }
}
