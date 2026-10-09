package ai.yuki.chuxue.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 「Yuki 初雪」圆角系统（开发文档 §29.4）。
 *
 * | 元素 | 圆角 |
 * |---|---|
 * | 消息气泡 | 18dp |
 * | 卡片 | 16dp |
 * | 输入框 | 12dp |
 * | 按钮 | 12dp |
 * | 头像 | 圆形 |
 * | 底部导航栏 | 32dp（顶部圆角） |
 */

/* ── 语义常量：组件直接引用，避免各写各的数字 ── */
val BubbleCorner: Dp = 18.dp
val CardCorner: Dp = 16.dp
val FieldCorner: Dp = 12.dp
val ButtonCorner: Dp = 12.dp
val SheetCorner: Dp = 24.dp

/** 底部导航栏胶囊圆角（文档 45.2） */
val NavCapsuleCorner: Dp = 28.dp

/** 底部导航栏容器顶部圆角（文档 29.4） */
val NavBarTopCorner: Dp = 32.dp

/* ── 扁平风的间距与悬浮边距（用户 2026-09-27 定调）── */

/**
 * 卡片之间的留白。用户要求 12–16dp，取中值。
 *
 * ⚠️ 这个值值得单独拎出来：扁平风靠**留白 + 阴影**分层。
 * 卡片一挤，那 2dp 阴影就完全失去意义，整页糊成一片。
 */
val CardGap: Dp = 14.dp

/** 悬浮药丸导航栏的左右留边 —— 贴边就不叫悬浮 */
val NavHorizontalMargin: Dp = 16.dp

/** 悬浮药丸导航栏距屏幕底部的留边 */
val NavBottomMargin: Dp = 12.dp

/** 药丸导航栏的高度（选中态药丸要能完整包住图标 + 文字） */
val NavPillHeight: Dp = 56.dp

/** Material 形状槽位 —— 让未显式传 shape 的 M3 组件也落在设计系统里。 */
val YukiShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(FieldCorner),      // 输入框 / 按钮
    medium = RoundedCornerShape(CardCorner),      // 卡片
    large = RoundedCornerShape(BubbleCorner),     // 消息气泡
    extraLarge = RoundedCornerShape(SheetCorner), // 底部选择器
)

/* ── 「柔和卡片」材质的外壳圆角（v0.35.0）──
   两处**刻意不同**：用户要求"底部导航栏和输入框都做成这个效果，但要有差异化"。
   差异化由这两件事承担：**圆角**（这里）与**暖色光晕的位置**（`TranslucentPanel` 的 tintAnchor）。 */

/** 聊天输入区外壳的圆角（用户 2026-09-28 指定 36dp）。 */
val InputPanelCorner: Dp = 36.dp

/** 底部导航栏外壳的圆角 —— 比输入区小一档，两者摆在同一屏上时一眼能分出主次。 */
val NavPanelCorner: Dp = 28.dp

/**
 * 内容区为**悬浮**导航栏让出的底部空间（胶囊 56 + 上下各 12 + 余量）。
 *
 * ⚠️ 关键区别：它必须加在**滚动容器的 contentPadding / 末尾 padding** 上，
 * 而**不是**加在内容区的整体 `padding` 上 —— 后者是"截断"：
 * 内容会在胶囊上方就结束，胶囊下方只剩页面背景色。
 * 那就同时毁掉两件事：用户看到"底部一块白色区域"，而半透明也**没东西可透**。
 */
val NavSpaceForContent: Dp = 92.dp

/* ── 消息气泡：近说话人一侧收角（文档 36 章）── */

/** 用户气泡（靠右）：右下角收。参数序 = topStart, topEnd, bottomEnd, bottomStart */
fun userBubbleShape() = RoundedCornerShape(BubbleCorner, BubbleCorner, 4.dp, BubbleCorner)

/** 助手气泡（靠左）：左下角收。 */
fun assistantBubbleShape() = RoundedCornerShape(BubbleCorner, BubbleCorner, BubbleCorner, 4.dp)
