package ai.yuki.chuxue.ui.update

import ai.yuki.chuxue.BuildConfig
import ai.yuki.chuxue.data.RemoteRelease
import ai.yuki.chuxue.ui.components.YukiDialog
import ai.yuki.chuxue.ui.theme.CardGap
import ai.yuki.chuxue.ui.theme.FrostLine
import ai.yuki.chuxue.ui.theme.SkyBlueDeep
import ai.yuki.chuxue.ui.theme.TextMuted
import ai.yuki.chuxue.ui.theme.TextPrimary
import ai.yuki.chuxue.ui.theme.WarnAmber
import ai.yuki.chuxue.R
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 更新弹窗（v0.50.0）。
 *
 * ## 两种形态
 * - **非强制**：可取消（「稍后」），用户能继续用旧版；
 * - **强制**：**拦返回键**、不显示「稍后」，只能更新。
 *
 * ## ⚠️ 强制态必须留一条出路（这是本文件最重要的设计决定）
 * "不能关"和"出不去"是两件事。如果强制态只有「立即更新」一个按钮，而下载又
 * 一直失败（用户网络极差 / 服务端挂了），用户就**彻底用不了这个 App** ——
 * 而那比"用旧版本"严重得多。
 *
 * 所以：
 * - 下载**失败**时 → 显示失败原因 + 「重试」+ **「先退出应用」**；
 * - 任何形态下都**不禁用系统返回**去"躲"（[BackHandler] 只是拦住返回手势，
 *   不改变"必须有可见出路"这条约束）。
 *
 * ## 为什么用 `YukiDialog`
 * 全局统一（见 `ui/components/YukiDialog.kt` 的注释：禁用 Material 默认观感）。
 * 自定义内容走它的 `content` 槽。
 */
@Composable
fun UpdateDialog(
    state: UpdateViewModel.State,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
    onInstall: () -> Unit,
    onRetry: () -> Unit,
    onExitApp: () -> Unit,
    /** 未开启「安装未知应用」时的引导（由调用方提供，因为它要 startActivity） */
    onOpenInstallSettings: () -> Unit,
    canInstall: Boolean,
) {
    when (state) {
        // ⚠️ 手动「检查更新」时**必须有反馈**（v0.50.5 补）。
        //    此前这一支落到 `else -> Unit` —— 用户点了按钮，屏幕上什么都不发生，
        //    比转个圈还糟（转圈至少说明"在查"）。
        is UpdateViewModel.State.Checking -> {
            YukiDialog(
                title = "正在检查更新",
                onConfirm = {},
                onDismiss = onDismiss,
                confirmText = "",
                dismissText = "",
                content = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            strokeWidth = 2.6.dp,
                            color = SkyBlueDeep,
                        )
                    }
                },
            )
        }

        is UpdateViewModel.State.Available -> {
            val forced = state.decision.force
            // ⚠️ 强制态拦返回键 —— 但**只在「有出路」的前提下**。
            //    这里的出路是「立即更新」本身可用（地址与大小都齐全，判定层已保证）。
            if (forced) BackHandler { /* 吞掉返回：强制更新时不让手势关掉 */ }

            YukiDialog(
                title = if (forced) "需要更新后才能继续" else "发现新版本",
                onConfirm = onDownload,
                onDismiss = onDismiss,
                confirmText = "立即更新",
                // 强制态不显示「取消」—— 但见下面的「退出应用」兜底
                dismissText = if (forced) "" else "稍后",
                content = { ReleaseNotes(state.release, showForceHint = forced) },
            )
        }

        is UpdateViewModel.State.Downloading -> {
            BackHandler { /* 下载中不让返回 —— 中断会留下半截文件 */ }
            YukiDialog(
                title = "正在下载",
                onConfirm = {},
                onDismiss = {},
                confirmText = "",
                dismissText = "",
                content = { DownloadProgress(state.progress) },
            )
        }

        is UpdateViewModel.State.Ready -> {
            YukiDialog(
                title = "下载完成",
                onConfirm = if (canInstall) onInstall else onOpenInstallSettings,
                onDismiss = onDismiss,
                confirmText = if (canInstall) "安装" else "去开启安装权限",
                dismissText = "稍后",
                content = {
                    Column {
                        Text(
                            "点「安装」后，系统会弹出安装界面。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary,
                        )
                        if (!canInstall) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                // ⚠️ 每个用户**第一次**更新都要做一次，所以要写成
                                //    "正常步骤"而不是"出错了"——见 ApkInstaller 的注释
                                "系统需要你先允许本应用安装应用。这是 Android 的安全设置，只需设置一次。",
                                style = MaterialTheme.typography.labelSmall,
                                color = WarnAmber,
                            )
                        }
                    }
                },
            )
        }

        is UpdateViewModel.State.Latest -> {
            // ⚠️ 「已是最新」是一个**独立状态**，不是失败（v0.50.5 修）。
            //    它必须长得像"一切正常"：绿色对勾 + 一句话 + 单个「知道了」。
            //    此前它被塞进 Failed，于是同一屏里标题是「更新没有完成」、
            //    正文却说"已经是最新版本"，还配着「重试 / 退出应用」——
            //    用户 2026-09-30 报的就是这个自相矛盾。
            YukiDialog(
                title = "已是最新版本",
                onConfirm = onDismiss,
                onDismiss = onDismiss,
                confirmText = "知道了",
                dismissText = "",
                content = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        SuccessBadge()
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "当前版本 v${BuildConfig.VERSION_NAME}，已经是最新版了。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary,
                        )
                    }
                },
            )
        }

        is UpdateViewModel.State.Failed -> {
            // ⚠️ 这一支是"出路"：失败了必须能走，而不是卡在弹窗里。
            //    但「退出应用」**只在强制更新失败时**才算出路（v0.50.5 修）——
            //    非强制时用户只是点了一下「检查更新」，让他退出应用是荒谬的。
            val canRetry = state.retryable != null
            YukiDialog(
                title = "更新没有完成",
                onConfirm = if (canRetry) onRetry else onDismiss,
                onDismiss = if (state.forced && canRetry) onExitApp else onDismiss,
                confirmText = if (canRetry) "重试" else "知道了",
                dismissText = if (state.forced && canRetry) "退出应用" else "",
                content = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        FailBadge()
                        Spacer(Modifier.height(12.dp))
                        Text(
                            state.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary,
                        )
                    }
                },
            )
        }

        else -> Unit
    }
}

/* ═══════════════ 更新日志 ═══════════════ */

/**
 * 更新弹窗的正文：**居中的软件图标 → 版本号 → 一句状态 → 更新日志正文**。
 *
 * ## 形态来自用户给的参考图（2026-09-29）
 * 用户拿了两张别的软件的更新弹窗截图，要求"借鉴排版"。参考图把更新弹窗拆成
 * 两个屏（先"检测到新版本"、再"更新日志"）。这里**合成一个**——
 * 本项目的用户只有一位，为了看一行日志多点一次没有收益；一屏给全更实用。
 *
 * ⚠️ 参考图上还有一行**发布日期**，这里**没做**：`RemoteRelease` 里没有日期字段，
 * 而为了一个装饰性文字去改接口协议不划算。要加的话应当连同后端 `/api/app/version`
 * 一起加，属独立决定。
 *
 * ⚠️ 按**行**渲染，不解析 Markdown —— Compose 的 `Text` 不解析 Markdown
 * （本项目为此修过 5 处，见 `项目交接记录` 坑 #13）。发布方写的 `- 第一条`
 * 会原样显示，这是**预期**行为，不是漏了渲染。
 */
@Composable
private fun ReleaseNotes(release: RemoteRelease, showForceHint: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // ① 图标居中 —— 与开屏、关于页同一张，一眼认出"是它在更新"
        Image(
            painter = painterResource(R.drawable.ic_app_logo),
            contentDescription = null,
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(16.dp)),
        )
        Spacer(Modifier.height(12.dp))

        // ② 版本号
        Text(
            "版本 ${release.versionName}",
            style = MaterialTheme.typography.titleSmall,
            color = SkyBlueDeep,
            fontWeight = FontWeight.SemiBold,
        )

        // ③ 一行状态：强制说明优先，其次是安装包大小（让用户对要下多少有数）
        Spacer(Modifier.height(6.dp))
        Text(
            if (showForceHint) {
                "这一版必须更新后才能继续使用"
            } else if (release.apkSize > 0) {
                "安装包 %.1f MB".format(release.apkSize / 1024.0 / 1024.0)
            } else {
                "有新版本可用"
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (showForceHint) WarnAmber else TextMuted,
        )

        // ④ 更新日志正文（左对齐；可能很长 → 限高 + 可滚动，别把弹窗顶出屏幕）
        Spacer(Modifier.height(14.dp))
        if (release.notes.isBlank()) {
            Text(
                "这次没有写更新说明。",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                release.notes.lines().forEach { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary,
                    )
                }
            }
        }
    }
}

/* ═══════════════ 成功徽章 ═══════════════ */

/**
 * 成功徽章：圆形绿渐变底 + 白色对勾（Canvas 自绘）。
 *
 * ## 为什么自绘
 * 本项目 `YukiIcons` 的图标是**白名单**（见 `项目交接记录` 第八条：只有
 * AccountCircle / Add / Back / … / Warning 这些，**没有对勾**）。
 * 硬塞一个不存在的图标名会编译不过；而两笔画出来的对勾比引入图标库可控得多。
 *
 * ## 为什么和「下载完成」共用一个
 * 两者都是**顺利**的语义。用户扫一眼颜色就懂（绿 = 好），
 * 不必为两种"好"设计两种绿。
 */
@Composable
private fun SuccessBadge(size: Dp = 60.dp) {
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension
        drawCircle(
            brush = Brush.linearGradient(listOf(Color(0xFF2FA36B), Color(0xFF4CC38A))),
            radius = s / 2f,
        )
        val w = s * 0.11f
        drawLine(
            Color.White,
            start = Offset(s * 0.29f, s * 0.53f),
            end = Offset(s * 0.44f, s * 0.68f),
            strokeWidth = w, cap = StrokeCap.Round,
        )
        drawLine(
            Color.White,
            start = Offset(s * 0.44f, s * 0.68f),
            end = Offset(s * 0.72f, s * 0.35f),
            strokeWidth = w, cap = StrokeCap.Round,
        )
    }
}

/* ═══════════════ 失败徽章 ═══════════════ */

/**
 * 失败徽章：圆形红渐变底 + 白色叉（Canvas 自绘）。
 *
 * 与 [SuccessBadge] 成对 —— 让"出错了"在**第一眼**就与"一切正常"分开，
 * 而不是靠读一行文字才知道（用户在弹窗上停留的时间很短）。
 */
@Composable
private fun FailBadge(size: Dp = 60.dp) {
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension
        drawCircle(
            brush = Brush.linearGradient(listOf(Color(0xFFE15B4A), Color(0xFFEF8874))),
            radius = s / 2f,
        )
        val w = s * 0.11f
        drawLine(
            Color.White,
            start = Offset(s * 0.34f, s * 0.34f),
            end = Offset(s * 0.66f, s * 0.66f),
            strokeWidth = w, cap = StrokeCap.Round,
        )
        drawLine(
            Color.White,
            start = Offset(s * 0.66f, s * 0.34f),
            end = Offset(s * 0.34f, s * 0.66f),
            strokeWidth = w, cap = StrokeCap.Round,
        )
    }
}

/* ═══════════════ 下载进度 ═══════════════ */

/**
 * 进度条 —— 与上下文弹窗里那条**同一种画法**（两层 Box），
 * 保持全局一致（那边是 `ChatScreen.kt` 的 `ProgressRow`）。
 */
@Composable
private fun DownloadProgress(p: ApkDownloaderProgress) {
    val fraction = p.fraction
    val mb = { n: Long -> "%.1f".format(n / 1024.0 / 1024.0) }
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "下载中",
                style = MaterialTheme.typography.labelMedium,
                color = TextPrimary,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${(fraction * 100).toInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = SkyBlueDeep,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier.fillMaxWidth().height(8.dp)
                .clip(RoundedCornerShape(50)).background(FrostLine),
        ) {
            Box(
                Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .height(8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(SkyBlueDeep),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            if (p.total > 0) "${mb(p.downloaded)} / ${mb(p.total)} MB" else "已下载 ${mb(p.downloaded)} MB",
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "请不要退出应用。",
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
        )
    }
}

/** 便于本文件引用（避免把 data 层的类型名写进每个签名）。 */
private typealias ApkDownloaderProgress = ai.yuki.chuxue.data.ApkDownloader.Progress
