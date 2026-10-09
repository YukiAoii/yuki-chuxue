package ai.yuki.chuxue.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import ai.yuki.chuxue.ui.theme.YukiCardSpec

/**
 * 轻拟物卡片（Soft UI）—— 本项目 UI 定调的一半（另一半是液态玻璃）。
 *
 * ## 为什么不用 Material 的 `Card`
 * 官方 `Card` 自带较重的阴影与固定的形状槽位，观感是"标准 Android 应用"。
 * 这里要的是**一片薄薄的、半透明的玻璃或玉石**浮在纸上一点点 —— 所以三件事都自己来：
 *
 * 1. **极浅阴影**（2dp，带主题色）—— 有浮起感，但绝不"实体挤出"；
 * 2. **微弱白色渐变**（顶部 0.92 → 底部 0.55）—— 模拟水彩纸的明暗，而不是一块死白；
 * 3. **0.5dp 极细描边**（alpha ≈ 5%）—— 用户原话：这是"精致感"和"材质感"的关键。
 *
 * 参数全部来自 [YukiCardSpec]，并由 `SoftUiSpecTest` 钉住 —— 改这里之前先看那个测试。
 *
 * ## 阴影的已知取舍
 * `.shadow()` 的 ambient/spot 都压到主题色的低透明度（沿用本项目既有结论：
 * 浅色底上**纯黑阴影几乎看不见**，早期 10dp 纯黑就因此被判为"未悬浮"）。
 * 2dp 本就极浅，再叠低透明度，效果是"几乎察觉不到的浮起"——这正是「轻」的意思。
 *
 * ## 用法
 * ```
 * YukiCard(modifier = Modifier.fillMaxWidth()) {
 *     Column(Modifier.padding(16.dp)) { ... }
 * }
 * ```
 * 内容默认按列排（[ColumnScope]）；要点击就把 `onClick` 传进来，
 * 不要自己在外面套 `clickable`（那样点击涟漪会盖住描边）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun YukiCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(YukiCardSpec.CornerRadius),
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val base = scheme.surface

    Column(
        modifier = modifier
            .shadow(
                elevation = YukiCardSpec.Elevation,
                shape = shape,
                clip = false,
                ambientColor = scheme.primary.copy(alpha = 0.30f),
                spotColor = scheme.primary.copy(alpha = 0.42f),
            )
            .clip(shape)
            // ⚠️ **纯色，不是渐变** —— 这条是被真机截图逼出来的结论，别再改回去。
            //
            // 原来这里是**对角渐变**（白 0.92 → 白 0.55，`Offset.Zero` → `Offset.Infinite`），
            // 意图是"光斜着照在一张纸上"。但在浅灰背景（`#F5F7FA`）上，卡片**右侧**
            // 解析成更透的那一端，于是每张卡片右边多出一条竖向的浅色带 ——
            // 用户的反馈原话是「很多按钮卡片的底部都有个白色的长方形的条，很丑」，
            // 他说的就是这条（"底部"是方位感上的误差，实际在右侧一整条）。
            //
            // 为什么这个渐变的**收益几乎为零**：0.92 与 0.55 叠在同一背景上只差三四个色阶。
            // 人眼在卡片尺度上分辨不出"光的方向"，只分辨得出"右边一块颜色不一样" ——
            // 于是它没带来质感，只带来了一个看起来像渲染错误的色带。
            //
            // 真要"纸的层次"，用描边或更明显的阴影 —— 不要用大面积、低对比的透明度差。
            .background(base)
            .border(
                width = YukiCardSpec.BorderWidth,
                color = YukiCardSpec.BorderColor,
                shape = shape,
            )
            .then(
                when {
                    // 长按用来承载「次要操作」（例如会话列表的长按删除）——
                    // 有了它就不必在卡片上再画一个常驻按钮，列表才能干净得像微信。
                    onClick != null && onLongClick != null ->
                        Modifier.combinedClickable(
                            onClick = onClick,
                            onLongClick = onLongClick,
                        )

                    onClick != null -> Modifier.clickable(onClick = onClick)
                    else -> Modifier
                },
            ),
        content = content,
    )
}
