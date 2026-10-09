package ai.yuki.chuxue.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * 设计系统不变量（开发文档 §29 / §30）。
 *
 * 这些断言不评判「好不好看」——那要靠真机。它们钉的是**结构事实**：
 * 令牌值是否与文档一致、别名是否与新色板同步、Typography 是否偷偷带上了颜色。
 * 任一条变红都意味着设计系统被意外改动，而不是「测试该改」。
 */
class DesignTokensTest {

    private val allTextStyles: List<Pair<String, TextStyle>> = listOf(
        "displaySmall" to YukiTypography.displaySmall,
        "headlineMedium" to YukiTypography.headlineMedium,
        "headlineSmall" to YukiTypography.headlineSmall,
        "titleLarge" to YukiTypography.titleLarge,
        "titleMedium" to YukiTypography.titleMedium,
        "titleSmall" to YukiTypography.titleSmall,
        "bodyLarge" to YukiTypography.bodyLarge,
        "bodyMedium" to YukiTypography.bodyMedium,
        "bodySmall" to YukiTypography.bodySmall,
        "labelLarge" to YukiTypography.labelLarge,
        "labelMedium" to YukiTypography.labelMedium,
        "labelSmall" to YukiTypography.labelSmall,
    )

    /* ─────────── 缓动曲线 ─────────── */

    @Test
    fun `三档缓动曲线均可构造_控制点在合法范围`() {
        // CubicBezierEasing 在控制点 x 越界时**构造即抛** IllegalArgumentException。
        // 这里既断言文档给定的控制点合法，也断言实际使用的常量可取到。
        CubicBezierEasing(0.4f, 0.0f, 0.2f, 1.0f)
        CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)
        CubicBezierEasing(0.4f, 0.0f, 1.0f, 1.0f)

        assertNotNull(StandardEasing)
        assertNotNull(EmphasizedEasing)
        assertNotNull(ExitEasing)
    }

    /* ─────────── 字体 ─────────── */

    @Test
    fun `六个文档字号与行高逐一对齐`() {
        // 页面标题 20/28 SemiBold
        assertEquals(20.sp, YukiTypography.headlineSmall.fontSize)
        assertEquals(28.sp, YukiTypography.headlineSmall.lineHeight)
        // 顶部导航标题 17/24
        assertEquals(17.sp, YukiTypography.titleMedium.fontSize)
        assertEquals(24.sp, YukiTypography.titleMedium.lineHeight)
        // 消息正文 16/24
        assertEquals(16.sp, YukiTypography.bodyLarge.fontSize)
        assertEquals(24.sp, YukiTypography.bodyLarge.lineHeight)
        // 辅助文字 14/20
        assertEquals(14.sp, YukiTypography.bodyMedium.fontSize)
        assertEquals(20.sp, YukiTypography.bodyMedium.lineHeight)
        // 标签文字 12/16
        assertEquals(12.sp, YukiTypography.labelSmall.fontSize)
        assertEquals(16.sp, YukiTypography.labelSmall.lineHeight)
        // 按钮文字 16/24
        assertEquals(16.sp, YukiTypography.labelLarge.fontSize)
        assertEquals(24.sp, YukiTypography.labelLarge.lineHeight)
    }

    @Test
    fun `所有字号为正_且行高不小于字号`() {
        allTextStyles.forEach { (name, style) ->
            assertNotNull("$name 缺 fontSize", style.fontSize)
            assertNotNull("$name 缺 lineHeight", style.lineHeight)
            val size = style.fontSize!!.value
            val line = style.lineHeight!!.value
            assertEquals(
                "$name 的 lineHeight(${line}sp) 小于 fontSize(${size}sp)，行距会挤压",
                true, line >= size,
            )
        }
    }

    /**
     * 关键不变量：Typography 不得携带颜色。
     *
     * 一旦某个 style 写死了 `color = ...`，深色模式就永远显示浅色文字 ——
     * 这是最难发现的一类主题 bug（浅色下完全正常）。
     */
    @Test
    fun `Typography 一律不携带颜色_由 colorScheme 决定`() {
        allTextStyles.forEach { (name, style) ->
            assertEquals(
                "$name 携带了颜色 ${style.color}：请在调用处以 color = 显式指定，或交给 colorScheme",
                Color.Unspecified, style.color,
            )
        }
    }

    /* ─────────── 圆角 ─────────── */

    @Test
    fun `圆角系统与文档 §29_4 一致`() {
        assertEquals(18.dp, BubbleCorner)
        assertEquals(16.dp, CardCorner)
        assertEquals(12.dp, FieldCorner)
        assertEquals(12.dp, ButtonCorner)
        assertEquals(32.dp, NavBarTopCorner)
        assertEquals(28.dp, NavCapsuleCorner)
    }

    /* ─────────── 动效 ─────────── */

    @Test
    fun `动效时长与文档 §30_2 一致`() {
        assertEquals(150, YukiDuration.Micro)
        assertEquals(200, YukiDuration.MessageBubble)
        assertEquals(250, YukiDuration.ListItem)
        assertEquals(350, YukiDuration.PageTransition)
        assertEquals(600, YukiDuration.LoginTransition)
        assertEquals(1200, YukiDuration.Splash)
        assertEquals(2400, YukiDuration.GlassBreath)
    }

    /* ─────────── 色板 ─────────── */

    @Test
    fun `色板全部为不透明色`() {
        // 半透明应由组件叠加时控制，色板本身给实色 —— 否则叠两次会变淡
        listOf(
            PrimaryBlue, PrimaryBlueLight, PrimaryBlueDark,
            WatercolorBgLight, WatercolorBgDark,
            SurfaceLight, SurfaceDark,
            UserBubbleLight, AssistantBubbleLight, UserBubbleDark, AssistantBubbleDark,
            TextPrimaryLight, TextSecondaryLight, TextPrimaryDark, TextSecondaryDark,
            ErrorColor, SuccessColor, WarningColor,
        ).forEach { c ->
            assertEquals("颜色 $c 不是不透明色", 1f, c.alpha, 0.0001f)
        }
    }

    /**
     * 旧名别名必须与新色板同值 —— v0.5.0 的组件仍在用旧名，
     * 若有人只改了一边，页面颜色会与设计系统悄悄分叉。
     */
    @Test
    fun `旧名别名与新色板同值`() {
        assertEquals(WatercolorBgLight, SnowWhite)
        assertEquals(SurfaceLight, SnowSurface)
        assertEquals(SurfaceVariantLight, SnowSurfaceDim)
        assertEquals(OutlineLight, FrostLine)

        assertEquals(PrimaryBlue, SkyBlue)
        assertEquals(PrimaryBlueDark, SkyBlueDeep)
        assertEquals(PrimaryInk, IndigoInk)

        assertEquals(PrimaryBlueLight, IceCyan)
        assertEquals(PrimarySoftLight, IceCyanSoft)

        assertEquals(TextPrimaryLight, TextPrimary)
        assertEquals(TextSecondaryLight, TextSecondary)
        assertEquals(TextSecondaryLight, TextMuted)

        assertEquals(WarningColor, WarnAmber)
        assertEquals(WarningSoftLight, WarnAmberBg)
        assertEquals(ErrorColor, DangerRose)
        assertEquals(ErrorSoftLight, DangerRoseBg)
        assertEquals(SuccessColor, SuccessMint)
    }

    @Test
    fun `深浅两套文字色不相等_避免误用同一色`() {
        // 若有人把 Dark 值也写成 Light 值，深色模式会失效且不报错
        assertEquals(false, TextPrimaryLight == TextPrimaryDark)
        assertEquals(false, TextSecondaryLight == TextSecondaryDark)
        assertEquals(false, WatercolorBgLight == WatercolorBgDark)
        assertEquals(false, SurfaceLight == SurfaceDark)
    }
}
