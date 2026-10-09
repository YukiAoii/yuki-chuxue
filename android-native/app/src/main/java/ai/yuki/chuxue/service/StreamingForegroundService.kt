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
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import ai.yuki.chuxue.MainActivity
import ai.yuki.chuxue.R

/**
 * 流式回复期间的前台服务（开发文档 §14.4）。
 *
 * ## 它解决什么问题
 * App 退到后台后，系统随时可能冻结进程 —— 正在流式的那条回复就断了。
 * 前台服务 + 一条常驻通知，让系统知道「这个进程在做用户可见的事」，从而不杀它。
 *
 * ## 为什么通知是必须的
 * Android 不允许「悄悄常驻」：起前台服务**必须**挂一条通知。
 * 这条通知本身就是对用户的交代 ——「正在生成回复」，而不是偷跑。
 *
 * ## 降级
 * 文档把前台服务标为「可降级」：拿不到权限（或用户拒绝通知）时，
 * 流式照常跑，只是后台可能被中断 —— 那种情况下 WAL 会兜住已收到的部分（§9.5）。
 */
class StreamingForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val title = intent.getStringExtra(EXTRA_TITLE) ?: "正在生成回复"
                startForegroundCompat(title)
                acquireWakeLock()
            }

            ACTION_STOP -> {
                releaseWakeLock()
                stopSelf()
            }
        }
        // 被系统杀掉后不要自动重启 —— 流式是「当下这一刻」的事，
        // 重启一个没有上下文的空服务毫无意义（恢复由 WAL 负责）。
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    /* ─────────── 通知 ─────────── */

    private fun startForegroundCompat(title: String) {
        createChannel()
        val notification = buildNotification(title)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(title: String): android.app.Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText("正在生成回复，请不要关闭应用")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "流式回复",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "回复生成期间的常驻提示"
                setShowBadge(false)
            },
        )
    }

    /* ─────────── WakeLock ─────────── */

    /**
     * 拿一个**有上限**的 WakeLock（5 分钟，与文档 §25.6 的「前台服务最长 5 分钟」一致）。
     * 不设上限的话，一旦流式异常卡死，这个锁会一直握着，把用户电量抽干。
     */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            runCatching { acquire(WAKE_LOCK_TIMEOUT_MS) }
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock ->
            runCatching { if (lock.isHeld) lock.release() }
        }
        wakeLock = null
    }

    companion object {
        private const val CHANNEL_ID = "yuki_streaming"
        private const val NOTIFICATION_ID = 4001
        private const val WAKE_LOCK_TAG = "yuki::streaming"
        private const val WAKE_LOCK_TIMEOUT_MS = 5 * 60 * 1000L

        const val ACTION_START = "ai.yuki.chuxue.action.STREAM_START"
        const val ACTION_STOP = "ai.yuki.chuxue.action.STREAM_STOP"
        const val EXTRA_TITLE = "title"

        /** 开始流式时调用。任何异常都不该影响流式本身 —— 拿不到通知权限也要能发消息。 */
        fun start(context: Context, title: String) {
            val intent = Intent(context, StreamingForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TITLE, title)
            }
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(context, intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, StreamingForegroundService::class.java).apply {
                        action = ACTION_STOP
                    },
                )
            }
        }
    }
}
