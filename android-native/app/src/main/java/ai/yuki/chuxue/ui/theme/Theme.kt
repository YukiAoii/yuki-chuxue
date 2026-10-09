package ai.yuki.chuxue.ui.theme

import ai.yuki.chuxue.data.AppSettings
import ai.yuki.chuxue.data.FONT_SCALE_LARGE
import ai.yuki.chuxue.data.FONT_SCALE_STANDARD
import ai.yuki.chuxue.data.FONT_SCALE_XLARGE
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/**
 * 「Yuki 初雪」主题装配（开发文档 §29 / §47.1）。
 *
 * 全应用统一走 [YukiTheme]，组件内不硬编码颜色、字号、圆角、时长。
 *
 * ## 深色模式现状
 * 深色配色（[YukiDarkColors]）已按文档 §29.1 的 Dark 值定义好，但 [YukiTheme] 的
 * `darkTheme` 默认 **false**：v0.5.0 的页面仍大量使用硬编码浅色（旧名别名），
 * 此刻跟随系统会破相。待各页面按文档重写（走 colorScheme）后再改为 `isSystemInDarkTheme()`。
 */

private val YukiLightColors = lightColorScheme(
    primary = PrimaryBlue,
    onPrimary = SurfaceLight,
    primaryContainer = PrimarySoftLight,
    onPrimaryContainer = PrimaryInk,

    secondary = PrimaryBlueLight,
    onSecondary = SurfaceLight,
    secondaryContainer = SurfaceVariantLight,
    onSecondaryContainer = TextPrimaryLight,

    tertiary = PrimaryBlueDark,
    onTertiary = SurfaceLight,

    background = WatercolorBgLight,
    onBackground = TextPrimaryLight,
    surface = SurfaceLight,
    onSurface = TextPrimaryLight,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = TextSecondaryLight,

    outline = OutlineLight,
    outlineVariant = OutlineLight,

    error = ErrorColor,
    onError = SurfaceLight,
    errorContainer = ErrorSoftLight,
    onErrorContainer = ErrorColor,
)

private val YukiDarkColors = darkColorScheme(
    primary = PrimaryBlueLight,
    onPrimary = WatercolorBgDark,
    primaryContainer = PrimarySoftDark,
    onPrimaryContainer = TextPrimaryDark,

    secondary = PrimaryBlue,
    onSecondary = WatercolorBgDark,
    secondaryContainer = SurfaceVariantDark,
    onSecondaryContainer = TextPrimaryDark,

    tertiary = PrimaryBlue,
    onTertiary = WatercolorBgDark,

    background = WatercolorBgDark,
    onBackground = TextPrimaryDark,
    surface = SurfaceDark,
    onSurface = TextPrimaryDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = TextSecondaryDark,

    outline = OutlineDark,
    outlineVariant = OutlineDark,

    error = ErrorColor,
    onError = WatercolorBgDark,
    errorContainer = ErrorSoftDark,
    onErrorContainer = ErrorColor,
)

/**
 * 字体缩放的合法区间。
 *
 * 界面只给 [FONT_SCALE_STANDARD] / [FONT_SCALE_LARGE] / [FONT_SCALE_XLARGE] 三档，
 * 但这个值是从磁盘读的 —— 夹一道是**防脏数据**（手改过 SharedPreferences、
 * 或将来某个版本写坏了），别让一个 `0f` 把全 App 的字缩成一个点。
 */
private const val MIN_FONT_SCALE = 0.85f
private const val MAX_FONT_SCALE = 1.5f

@Composable
fun YukiTheme(
    darkTheme: Boolean = false, // 见文件头「深色模式现状」
    /**
     * 字体缩放（「设置 → 功能设置 → 外观」可调，开发文档 §41）。
     *
     * ⚠️ 只动 `fontScale`、**不动 `density`**：字号跟着缩放，而所有 dp
     *（间距 / 圆角 / 图标尺寸 / 卡片高度）原样不变 —— 所以放大字体不会把布局撑乱。
     * 这就是"字体大小"该有的语义，也是这个做法存在的理由。
     *
     * ⚠️ 与**系统**字体缩放**相乘**：系统已经调大的用户，这里再调大是叠加的
     *（见 [AppSettings.fontScale] 的注释）。
     */
    fontScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    val base = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(
            density = base.density,
            fontScale = base.fontScale * fontScale.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE),
        ),
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) YukiDarkColors else YukiLightColors,
            typography = YukiTypography,
            shapes = YukiShapes,
            content = content,
        )
    }
}
