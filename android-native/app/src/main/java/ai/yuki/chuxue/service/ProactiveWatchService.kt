package ai.yuki.chuxue.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import ai.yuki.chuxue.MainActivity
import ai.yuki.chuxue.R
import ai.yuki.chuxue.data.ProactiveFetcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 「Ta 主动来找我」的**后台常驻取件**（v0.61.49，用户 2026-10-06 要求）。
 *
 * ## 它解决什么
 * 原来的取件是**机会式**的（App 启动 / 回前台）—— App 在后台时永远不取件，
 * 于是"Ta 想说话了"这件事只有等你打开 App 才知道。这个服务让取件在后台也发生。
 *
 * ## ⚠️ 代价（用户已知，别在文案里粉饰）
 * Android 不允许"悄悄常驻"：起前台服务**必须**挂一条常驻通知（本服务是 `yuki_watch` 渠道，
 * IMPORTANCE_LOW + 静默，不带角标）。用户会一直在通知栏看到它。
 * 另外 **Android 15 起 `dataSync` 类型的前台服务有每日时限**（约 6 小时/24 小时），
 * 超时会被系统停止 —— 在那种机型上这个服务只能"尽量活着"。
 *
 * ## 设计红线（与 [ProactiveFetcher] 一致）
 * 它只做"过一会儿去信箱看看"：**何时说、说什么由心潮的情绪状态机决定**。
 * 这里的定时循环**不是**"到点提醒 Ta 说话"，只是"多久去信箱看一次"。
 */
class ProactiveWatchService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                loop?.cancel()
                loop = null
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startForegroundCompat()
                startLoop()
            }
        }
        // 常驻语义：被系统杀掉后尽量重来（拿不到通知权限时会失败，那是预期的降级）
        return START_STICKY
    }

    override fun onDestroy() {
        loop?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    /**
     * **Android 15+ 的 `dataSync` 前台服务每日时限**：到点系统回调这里。
     * 不覆写的话，服务会因"超时未自行退出"被判 ANR（审查列为 HIGH）。
     * 收尾就是**干净地退出** —— 常驻是增强，被系统收走时不该留下一个僵尸通知。
     */
    override fun onTimeout(startId: Int) {
        loop?.cancel()
        loop = null
        stopForegroundCompat()
        stopSelf()
    }

    private fun startLoop() {
        if (loop?.isActive == true) return
        loop = scope.launch {
            while (isActive) {
                // 失败静默（未登录 / 没网 / 服务端 5xx）—— 取件本身不该让服务崩掉
                runCatching { ProactiveFetcher.fetchOnce(this@ProactiveWatchService) }
                delay(INTERVAL_MS)
            }
        }
    }

    /* ─────────── 常驻通知 ─────────── */

    private fun startForegroundCompat() {
        ensureChannel()
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Ta 在后台等你")
            .setContentText("有新的话会立刻提醒你")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .build()
        // ⚠️ v0.61.50 修（审查列为 HIGH）：`startForeground` 失败时（拿不到通知权限 /
        //    该类型不被允许）原来只静默吞掉 —— 服务会停在"已启动但没有前台通知"的非法状态，
        //    轻则被系统杀、重则 ANR。**失败就干净退出**（常驻是增强，不是前提）。
        val ok = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, n)
            }
        }.isSuccess
        if (!ok) {
            loop?.cancel()
            loop = null
            stopSelf()
        }
    }

    private fun stopForegroundCompat() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "后台在线", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Ta 在后台等你时显示的常驻提示"
                setShowBadge(false)
            },
        )
    }

    companion object {
        private const val CHANNEL_ID = "yuki_watch"
        private const val NOTIFICATION_ID = 4002

        /** 取件间隔。
         *  ⚠️ v0.61.50 修：原为 15 分钟，而 `ProactiveFetchPolicy` 的节流是 **30 分钟** ——
         *  两轮里必有一轮被守卫挡掉、纯空转唤醒（审查指出）。现在与节流对齐，不白耗电。 */
        private const val INTERVAL_MS = 30 * 60 * 1000L

        const val ACTION_START = "ai.yuki.chuxue.action.WATCH_START"
        const val ACTION_STOP = "ai.yuki.chuxue.action.WATCH_STOP"

        /**
         * 按状态**幂等地**启停（App 启动时调一次即可）。
         *
         * `shouldRun` 由调用方判定（有云端人设 + 已登录 + 通知可用）。
         * 任何异常都不该影响 App 本身 —— 常驻是增强，不是前提。
         */
        fun sync(context: Context, shouldRun: Boolean) {
            runCatching {
                val intent = Intent(context, ProactiveWatchService::class.java).apply {
                    action = if (shouldRun) ACTION_START else ACTION_STOP
                }
                if (shouldRun && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(context, intent)
                } else {
                    context.startService(intent)
                }
            }
        }
    }
}
