package ai.yuki.chuxue.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale

/**
 * 「软糖按压」—— 按下去整块缩一点，松开弹回。
 *
 * ## 为什么抽成共用基元
 * 这套反馈最早只长在底部导航栏上（v0.52.0 方案 1）。后来市场卡片、分类胶囊也要，
 * 而**各写一份的后果是每份的缩放值都不一样**（0.94 / 0.96 / 0.97），
 * 用户说不出哪里别扭，但会觉得"这几个东西不是一个 App 的"。
 *
 * ⚠️ `indication = null`（关掉涟漪）是刻意的：浅色卡片上扩散的半透明矩形会糊出一个方块。
 *    按压感由**缩放**承担 —— 这也是项目里既有的做法。
 *
 * ⚠️ 缩放值别调太狠：0.94 在宽卡片上会缩出明显的边缘抖动，0.96–0.97 才像"按下去"。
 */
@Composable
fun Modifier.yukiPress(
    scaleDown: Float = 0.965f,
    enabled: Boolean = true,
    // ⚠️ `onClick` 必须放**最后一个参数**：放前面的话 `.yukiPress { ... }` 这种尾随 lambda
    //    会落到别的参数上（Kotlin 的尾随 lambda 只认最后一个）—— 编译报的是
    //    "actual type is Function0<Unit>, but Boolean was expected"，不看签名很难反应过来。
    onClick: () -> Unit,
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val s by animateFloatAsState(
        targetValue = if (pressed && enabled) scaleDown else 1f,
        label = "yuki-press",
    )
    return this
        .scale(s)
        .clickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            onClick = onClick,
        )
}
