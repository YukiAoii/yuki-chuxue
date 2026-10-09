package ai.yuki.chuxue.ui.components.liquidglass

import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 底部导航栏的一个 Tab（开发文档 §26.4.1 / §49.4.1）。
 *
 * [selectedIcon] / [unselectedIcon] 由调用方给出。**本项目的做法**是两态传同一个
 * 图标对象，靠 tint 透明度与缩放区分（§45.3.3 / §45.2）——因为 [ai.yuki.chuxue.ui.icon.YukiIcons]
 * 是一套统一描边的自绘图标，做填充版反而破坏视觉一致性。字段保留，将来若要
 * 填充/描边两态，直接换图标即可，组件不必改。
 */
data class BottomTabItem(
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
)
