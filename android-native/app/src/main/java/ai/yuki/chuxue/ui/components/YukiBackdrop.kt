package ai.yuki.chuxue.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * **全局浅蓝渐变背景**（v0.60.0）—— 以 `Modifier` 形式提供。
 *
 * 为什么是 Modifier 而不是包裹组件：页面的不透明底色来自 `Scaffold(containerColor)`、
 * `NavHost(modifier.background(...))` 这些**已有的 Modifier 链**。用 Modifier 形态
 * 可以直接把那一层替掉，不必去动每个页面的括号结构（少一类出错面）。
 *
 * 用法：`Modifier.fillMaxSize().yukiBackdrop()`
 */
fun Modifier.yukiBackdrop(): Modifier = drawBehind {
    val w = size.width
    val h = size.height

    // ① 底色：180deg 竖向渐变
    drawRect(Brush.verticalGradient(listOf(Color(0xFFF5F7FA), Color(0xFFE9F0FB))))

    // ② 4 个光斑：圆心/半径按设计稿换算成比例
    //    colorStops 里 0.7f 处即为透明 —— 对应 CSS 的 `transparent 70%`
    fun blob(cx: Float, cy: Float, r: Float, color: Color) {
        drawRect(
            Brush.radialGradient(
                colorStops = arrayOf(0f to color, 0.7f to Color.Transparent),
                center = Offset(w * cx, h * cy),
                radius = w * r,
            ),
        )
    }
    // b1: 260×260 @ (left -70, top -40)  → 圆心 (60,90)   半径 130
    blob(0.154f, 0.107f, 0.333f, Color(0x7378AAEB))   // 原 .30 → .45
    // b2: 200×200 @ (right -60, top 120) → 圆心 (350,220) 半径 100
    blob(0.897f, 0.261f, 0.256f, Color(0x7AA0C8FF))   // 原 .32 → .48
    // b3: 150×150 @ (left 40, bottom 150) → 圆心 (115,619) 半径 75
    blob(0.295f, 0.733f, 0.192f, Color(0x82BED7FA))   // 原 .34 → .51
    // b4: 300×300 @ (right -90, bottom -90) → 圆心 (330,784) 半径 150
    blob(0.846f, 0.929f, 0.385f, Color(0x6382B4F0))   // 原 .26 → .39
}

/**
 * 包裹形态的同一份背景（给"整页只有它一层"的场合用，如认证页的各个屏）。
 *
 * ⚠️ **别和 [yukiBackdrop] 叠加用**：光斑是半透明的，画两层会比设计稿更深。
 *    根层（NavHost）已经铺了的话，页面里就不要再铺一次。
 */
@Composable
fun YukiBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier.fillMaxSize().yukiBackdrop()) { content() }
}

/**
 * **精确阴影**：按 CSS `box-shadow` 的 offset/blur 直接画，不走 `Modifier.shadow`。
 *
 * ## 为什么不用 `Modifier.shadow`
 * `Modifier.shadow(elevation, shape)` 只能给一个 **elevation**，由平台推导出阴影 ——
 * 偏移和模糊都不可控。用户要的是设计稿里的
 * `box-shadow: 0 4px 14px rgba(32,58,99,.09)` 这种**明确写了 offset 与 blur** 的阴影，
 * 用 elevation 只能做到"差不多"，这正是他说"控件阴影没实现"的原因。
 *
 * 这里用 `Paint.setShadowLayer(blur, dx, dy, color)` —— 与 CSS 语义一一对应：
 * `offsetY` → dy、`blur` → blur、`color` → 阴影色。
 *
 * ⚠️ **一个我选择的近似**：CSS 的 blur-radius 与 Android `setShadowLayer` 的 radius
 *    并非同一标度（CSS 是高斯的标准差 ×2 口径）。这里按 **1:1 取值**（cssBlur 直接喂给
 *    setShadowLayer）。真机上如果看着偏软/偏硬，改这一个数即可。
 */
fun Modifier.yukiShadow(
    cornerRadius: Dp,
    offsetY: Dp,
    blur: Dp,
    color: Color,
) = drawBehind {
    val radiusPx = cornerRadius.toPx()
    val paint = Paint().apply {
        val fp = asFrameworkPaint()
        fp.isAntiAlias = true
        // 本体透明：只画阴影，不画一层额外的实心块（实心由调用方的 background 负责）
        fp.color = android.graphics.Color.TRANSPARENT
        fp.setShadowLayer(blur.toPx(), 0f, offsetY.toPx(), color.toArgb())
    }
    drawIntoCanvas { canvas ->
        canvas.nativeCanvas.drawRoundRect(
            0f, 0f, size.width, size.height, radiusPx, radiusPx, paint.asFrameworkPaint(),
        )
    }
}
