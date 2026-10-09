package ai.yuki.chuxue.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 「Yuki 初雪」字体系统（开发文档 §29.3）。
 *
 * | 用途 | 字号 | 字重 | 行高 |
 * |---|---|---|---|
 * | 页面标题 | 20sp | SemiBold | 28sp |
 * | 顶部导航标题 | 17sp | Medium | 24sp |
 * | 消息正文 | 16sp | Regular | 24sp |
 * | 辅助文字 | 14sp | Regular | 20sp |
 * | 标签文字 | 12sp | Regular | 16sp |
 * | 按钮文字 | 16sp | Medium | 24sp |
 *
 * ⚠️ 这里**不指定 color**：颜色由 `MaterialTheme.colorScheme`（onSurface / onSurfaceVariant）
 * 决定，这样浅色与深色模式才能各自正确。调用处若要强调，显式传 `color =`。
 */
internal val YukiTypography = Typography(
    // ── 页面标题 20sp SemiBold / 28sp ──
    headlineSmall = TextStyle(
        fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold,
    ),
    titleLarge = TextStyle(
        fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold,
    ),

    // ── 顶部导航标题 17sp Medium / 24sp ──
    titleMedium = TextStyle(
        fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium,
    ),
    // 次级标题（列表主行、卡片标题）
    titleSmall = TextStyle(
        fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium,
    ),

    // ── 消息正文 16sp Regular / 24sp ──
    bodyLarge = TextStyle(
        fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Normal,
    ),
    // ── 辅助文字 14sp Regular / 20sp ──
    bodyMedium = TextStyle(
        fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal,
    ),
    // 更小的正文（提示、脚注）
    bodySmall = TextStyle(
        fontSize = 13.sp, lineHeight = 19.sp, fontWeight = FontWeight.Normal,
    ),

    // ── 按钮文字 16sp Medium / 24sp ──
    labelLarge = TextStyle(
        fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium,
    ),
    labelMedium = TextStyle(
        fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium,
    ),
    // ── 标签文字 12sp Regular / 16sp ──
    labelSmall = TextStyle(
        fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal,
    ),

    // ── 大标题（启动页/空状态，文档未列，按比例补齐）──
    displaySmall = TextStyle(
        fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold,
    ),
    headlineMedium = TextStyle(
        fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold,
    ),
)
