package ai.yuki.chuxue.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import ai.yuki.chuxue.MainActivity
import ai.yuki.chuxue.R

/**
 * 「Ta 来找我」的本地通知（v0.61.40）。
 *
 * ## 与 StreamingForegroundService 的渠道**分开**
 * 那条 `yuki_streaming` 是流式期间的**静默常驻**提示（IMPORTANCE_LOW + setSilent）——
 * 复用的话「Ta 的消息」会跟着变静音，用户永远收不到提醒。所以单开 `yuki_proactive`。
 *
 * ## 降级（不欺骗）
 * 没通知权限（Android 13+ 未授权 / 用户在系统里关掉了通知）时**不发、也不假装发成功** ——
 * 人设编辑页负责明说"收不到"并给去系统设置的引导。
 *
 * ## 点击去向
 * 带上 personaId → MainActivity 收到后进该人设的**最近一段会话**
 *（没有会话就新建 —— 与详情页「进入对话」同一条路）。
 */
object ProactiveNotifier {

    /** 通知渠道 id（与 yuki_streaming 分开，别合并）。 */
    const val CHANNEL_ID = "yuki_proactive"

    /** 携带"点开要看哪个人设"的 extra 键。 */
    const val EXTRA_OPEN_PERSONA_ID = "proactive_persona_id"

    /**
     * 一条通知的稳定 id：同一句只会有一条通知；不同句各自堆叠。
     *
     * `(personaId + at).hashCode()`：同一句话重复取件（理论上被 since 挡住，不会发生）
     * 也只会更新同一条，而不是弹两条。
     */
    fun notificationId(personaId: String, at: String): Int = (personaId + at).hashCode()

    /**
     * 弹一条「Ta 的消息」。发送前**先查两层门禁**（运行时权限 + 系统通知开关）——
     * 发不出去就安静放弃（调用方不用 try，也不该在这里弹任何提示：
     * 提示的责任在界面的开关旁边，那里才有"去允许"的上下文）。
     */
    /**
     * 弹一条「Ta 的消息」。发送前**先查两层门禁**（运行时权限 + 系统通知开关）——
     * 发不出去就安静放弃（调用方不用 try，也不该在这里弹任何提示：
     * 提示的责任在界面的开关旁边，那里才有"去允许"的上下文）。
     *
     * @param avatarPath 人设头像的本地路径（用户 2026-10-06 要求通知里能看到**谁**在找你）。
     *   读不到 / 路径为空 → 不出大图标，照常发通知（头像不该是通知发不出去的理由）。
     */
    fun show(
        context: Context,
        personaId: String,
        personaName: String,
        message: String,
        at: String,
        avatarPath: String? = null,
    ) {
        if (!canNotify(context)) return
        runCatching {
            ensureChannel(context)
            val open = PendingIntent.getActivity(
                context,
                personaId.hashCode(),
                Intent(context, MainActivity::class.java).apply {
                    // 复用已有的 MainActivity（CLEAR_TOP + SINGLE_TOP）——
                    // App 在后台时点通知不该叠出第二个 Activity
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(EXTRA_OPEN_PERSONA_ID, personaId)
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(personaName)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(open)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            // 人设头像（用户 2026-10-06 要求：通知上要能看出是**谁**在找你）。
            // 圆形裁剪 —— 与 App 内头像的观感一致。失败一律静默降级（不放大图标）。
            loadAvatar(avatarPath)?.let { builder.setLargeIcon(it) }
            NotificationManagerCompat.from(context).notify(notificationId(personaId, at), builder.build())
        }
    }

    /**
     * 读人设头像并裁成圆形（通知大图标用）。
     *
     * ⚠️ 头像文件可能已被删除 / 损坏 / 换格式 —— 一律静默返回 null。
     *    **头像缺失不该让通知发不出去**（那是两件事，别耦在一起）。
     */
    private fun loadAvatar(path: String?): Bitmap? = path
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() }
        ?.let { src -> runCatching { circleCrop(src) }.getOrNull() ?: src }

    /** 居中裁成圆形；原图不改（返回新 Bitmap）。 */
    private fun circleCrop(src: Bitmap): Bitmap {
        val size = minOf(src.width, src.height)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val radius = size / 2f
        canvas.drawCircle(radius, radius, radius, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        val left = (src.width - size) / 2f
        val top = (src.height - size) / 2f
        canvas.drawBitmap(src, -left, -top, paint)
        return out
    }

    /**
     * 通知**现在真的能弹出来**吗（两层门禁合一）。
     *
     * 1. Android 13+ 的运行时权限（POST_NOTIFICATIONS）；
     * 2. 系统里的应用通知开关（用户可以在任何版本关掉它）。
     *
     * 界面（人设编辑页）用同一个函数决定"要不要提示收不到"，保证口径一致。
     */
    fun canNotify(context: Context): Boolean {
        val permOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        return permOk && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /** 建渠道（幂等）。名字与说明都按用户向文案。 */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Ta 的消息",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Ta 主动来找你时提醒"
                setShowBadge(true)
            },
        )
    }
}
