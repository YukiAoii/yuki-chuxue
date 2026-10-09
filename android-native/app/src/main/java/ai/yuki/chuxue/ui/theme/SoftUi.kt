package ai.yuki.chuxue.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 轻拟物卡片（Soft UI）的规范数值 —— 本项目 UI 定调的**一半**
 * （另一半是液态玻璃，见 `ui/components/liquidglass/`）。
 *
 * ## 一句话说清这套规范要什么
 * 卡片应该像**一片薄薄的、半透明的玻璃或玉石**，浮在纸上一点点 ——
 * 而不是一张白纸，也不是一块被"挤出来"的凸起。三件事共同做到这点：
 *
 * | 要素 | 值 | 作用 |
 * |---|---|---|
 * | 圆角 | 16dp（设计系统的 [CardCorner]） | 大圆角是"轻"的前提 |
 * | 阴影 | **极浅** 2dp | 浮起感，但绝不"实体挤出" |
 * | 描边 | **0.5dp / alpha 0x0D** | 精致感与材质感的来源 |
 * | 渐变 | 顶部 0.92 → 底部 0.55 | 模拟水彩纸的微弱明暗 |
 *
 * ## ⚠️ 一次已记录的规范修正
 * 本项目最初的 UI 理解是「**完全不用 elevation**，只用描边替代阴影」。
 * 用户随后给出精确参数：**要阴影，但极浅（`elevation = 2.dp`）**。
 * 所以这里 `Elevation = 2.dp` 是**被修正过的**结论，不是遗漏 —— 别再删掉它。
 *
 * ## ⚠️ 描边色的已知局限
 * [BORDER_COLOR_ARGB] 是用户按**浅色主题**指定的（墨蓝 #1A2A3A 的 5% alpha）。
 * 深色模式下这个颜色会不可见 —— 与文档 §45 那套"按深色背景给的玻璃参数"
 * 恰好是同一类问题、方向相反。**将来做深色模式时必须另标定**，不要直接照搬。
 * （本项目当前只做浅色，深色板在 `Color.kt` 里但未启用。）
 */
object YukiCardSpec {

    /** 卡片圆角 —— 直接取自设计系统，避免各页各写一个数。 */
    val CornerRadius: Dp = CardCorner

    /** 极浅阴影：有浮起感，但不"实体挤出"。上限由 `SoftUiSpecTest` 钉住。 */
    val Elevation: Dp = 2.dp

    /**
     * 描边宽度：**0** —— 扁平风不用描边。
     *
     * 用户 2026-09-27 明确要求「去掉生硬的灰色描边，改用极浅的柔和阴影」。
     * 字段保留而不删，是为了让「曾经有过描边」这件事留在代码里：将来若有人想加回来，
     * 会先看到这条注释和 `FlatCardThemeTest` 里那条断言。
     */
    val BorderWidth: Dp = 0.dp

    /**
     * 描边色。扁平风下**不再使用**（[BorderWidth] 为 0），保留常量只为兼容
     * 仍在引用它的旧代码路径。
     */
    const val BORDER_COLOR_ARGB: Int = 0x0D1A2A3A

    /** 输入框圆角 —— 与设计系统的输入框令牌同源，避免各页各写一个数 */
    val FieldCornerRadius: Dp = FieldCorner

    val BorderColor: Color get() = Color(BORDER_COLOR_ARGB)

    /**
     * ⚠️ 这里原来有两个常量：`GRADIENT_TOP_ALPHA = 0.92f` / `GRADIENT_BOTTOM_ALPHA = 0.55f`，
     * 配合 [ai.yuki.chuxue.ui.components.YukiCard] 的**对角渐变**使用。
     *
     * **已删除**（2026-09-27）。原因：那个渐变在浅灰背景上表现为
     * **卡片右侧一条竖向色带** —— 用户的原话是「很多按钮卡片的底部都有个
     * 白色的长方形的条，很丑」。0.92 与 0.55 叠在同一背景上只差三四个色阶，
     * 人眼分辨不出"光的方向"，只分辨得出"右边一块颜色不一样"。
     *
     * 卡片现在用**纯色**。要"纸的层次"请用描边或阴影，不要用大面积低对比的透明度差。
     * `SoftUiSpecTest` 有一条反射断言钉住"这两个常量不许回来"。
     */
}

/**
 * 药丸导航栏的**选中态底色**：极浅的主色，不是深色填充。
 *
 * 为什么不做深色填充：整套语言是「白卡片 + 蓝紫强调」，底栏里突然出现一块饱和蓝，
 * 会把它变成整屏最重的元素 —— 那是旧液态玻璃的做法（靠高对比取胜）。
 * 浅底 + 主色图标/文字，才是这套风格里「选中」的表达。
 */
val NavSelectedFill: Color = BrandBlueSoft

