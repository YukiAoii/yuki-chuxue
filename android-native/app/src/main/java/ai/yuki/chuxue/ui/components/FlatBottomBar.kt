package ai.yuki.chuxue.ui.components

import ai.yuki.chuxue.ui.components.liquidglass.BottomTabItem
import ai.yuki.chuxue.ui.theme.BrandBlue
import ai.yuki.chuxue.ui.theme.NAV_PANEL_ALPHA
import ai.yuki.chuxue.ui.theme.NavBottomMargin
import ai.yuki.chuxue.ui.theme.NavHorizontalMargin
import ai.yuki.chuxue.ui.theme.NavPanelBase
import ai.yuki.chuxue.ui.theme.NavPanelCorner
import ai.yuki.chuxue.ui.theme.NavPillHeight
import ai.yuki.chuxue.ui.theme.NavSelectedFill
import ai.yuki.chuxue.ui.theme.TextSubtle
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/** 选中时图标放大一点点（1.12×）—— 只做"轻过渡"，不碰弹簧。 */
/**
 * 底栏 Tab 的动画时长。
 *
 * ⚠️ **[leaveMs] 必须是 0** —— 这是「重影」的根：
 * 旧药丸如果也做淡出，新旧两颗会在同一段时间里**各半可见**，
 * 用户看到的就是"上一个按钮还留着一层影子"。
 *
 * 用户 2026-10-02：「上一个按钮会有类似重影的感觉……改成平滑过渡无这种类似重影的」。
 *
 * ⚠️ 按下（[PRESS_SCALE] / [PressFill]）**不走这个 0** —— 那是另一条交互，
 * 该有的缓动不能省。见 [navSpecMs]。
 */
internal object NavTabMotion {
    /** 进场：新选中的那颗淡入 + 图标放大。 */
    const val enterMs = 200
    /** 离场：原来选中的那颗 —— **必须 0**，否则就是重影。 */
    const val leaveMs = 0
}

/**
 * 某个状态切换该用多长的动画。
 *
 * @param selected 切换后是不是选中态
 * @param pressed  切换后是不是按下态
 */
internal fun navSpecMs(selected: Boolean, pressed: Boolean): Int = when {
    // 进场、按下反馈：都要缓动
    selected || pressed -> NavTabMotion.enterMs
    // 离场（既没选中也没按下）：**立刻**，不留残影
    else -> NavTabMotion.leaveMs
}

private const val SELECTED_ICON_SCALE = 1.12f

/**
 * 按下时整颗药丸的缩放（v0.52.0 方案 1「软糖按压」）。
 *
 * 原来按下**零反馈**（`indication = null` 把涟漪也关了）；这里补一个"按下去"的实感 ——
 * 缩放 + 未选中时叠一层淡蓝（[PressFill]）。不引入弹簧、不引入 blur。
 */
private const val PRESS_SCALE = 0.96f

/** 按下时叠在药丸上的淡蓝（未选中态）—— 与选中态 [NavSelectedFill] 区分开。 */
private val PressFill = BrandBlue.copy(alpha = 0.10f)

/**
 * 悬浮底部导航栏 —— **半透明面板材质**（用户 2026-09-28 第三次定调）。
 *
 * ## 外观沿革（四个版本，别改回去）
 * 1. **液态玻璃**：Canvas 自绘玻璃体 + 液滴指示器 + 弹簧回弹 + 呼吸脉动。
 *    它按**深色玻璃 + 白高光**配的，在浅色主题上踩过两次坑（液滴隐形、胶囊不可见），
 *    而且贵（每帧重绘 + 手势状态机）。
 * 2. **扁平白药丸**（2026-09-27）：白卡片 + 阴影 + 浅色药丸选中态。干净、便宜，
 *    但用户随后觉得单薄。
 * 3. **不透明"柔和卡片"**（v0.35.0）：径向渐变 + 阴影 + 高光描边。
 * 4. **半透明面板**（本版）：在第 3 版的基础上把底**做成半透明**。
 *    ⚠️ 用户原话是「必须有半透明的效果」——这是**他的要求**，不是回退到第 1 版：
 *    第 1 版的"玻璃拟态"他明确否过（要模糊、要让底层糊出来），这一版**不模糊**。
 *
 * ## 与输入区的差异化（用户要求"同一材质但要有差异化"）
 * ⚠️ v0.37.1 起**暖色已去掉**（用户原话「底部导航栏也是高斯模糊不要暖色」），
 * 所以差异化现在只剩**圆角**与**厚度**两处：
 *
 * | | 圆角 | 不透明度 |
 * |---|---|---|
 * | 聊天输入区 | 36dp | 0.62 |
 * | **这里** | 28dp | **0.72（更实）** |
 *
 * 底栏比输入区实，是因为它常驻在**不变的背景**上、又要承载图标与文字；
 * 输入区则要透出滚动中的聊天记录 —— 同一材质，厚度不同。
 *
 * ## 交互规则（未变）
 * 选中态是浅色药丸（[NavSelectedFill]）+ 主色 [BrandBlue]；未选中只显示图标。
 * 过渡只做**颜色与缩放**两条 —— 液态玻璃时代那套弹簧滑动已经删掉了，
 * 它在浅色主题上既不划算也不好看。
 *
 * ⚠️ 渲染未经真机验证（本机无设备）。
 */
@Composable
fun FlatBottomBar(
    tabs: List<BottomTabItem>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    TranslucentPanel(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = NavHorizontalMargin, vertical = NavBottomMargin),
        corner = NavPanelCorner,
        alpha = NAV_PANEL_ALPHA,
        elevation = 14.dp,
        // ── 方案 A「厚雾玻璃」（2026-09-30）：底栏单独提亮 + 描边增强 ──
        base = NavPanelBase,
        edgeHighlight = 0.85f,
        edgeShade = 0.38f,
        edgeWidth = 1.25.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(NavPillHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { index, tab ->
                NavTab(
                    tab = tab,
                    selected = index == selectedIndex,
                    onClick = { if (index != selectedIndex) onTabSelected(index) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * 一个 Tab。
 *
 * 抽成独立组件是为了让 [FlatBottomBar] 只剩"排布"一件事 ——
 * 里面这几行（选中底色 / 图标着色 / 缩放 / 关涟漪）各写五遍，迟早会有一处漏掉。
 */
@Composable
private fun NavTab(
    tab: BottomTabItem,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 过渡只做颜色与缩放：不引入弹簧、不引入滑动。
    // ⚠️ v0.52.0（方案 1「软糖按压」）：原来按下**零反馈**（indication 被关掉），
    // 这里补一个"按下去"的实感 —— 整颗药丸缩放 + 未选中时叠一层淡蓝。
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val fill by animateColorAsState(
        targetValue = when {
            selected -> NavSelectedFill
            pressed -> PressFill
            else -> Color.Transparent
        },
        // ⚠️ 离场 0ms：旧药丸必须**立刻**消失（见 [NavTabMotion]）
        animationSpec = tween(navSpecMs(selected, pressed)),
        label = "nav-fill",
    )
    val tint by animateColorAsState(
        targetValue = if (selected) BrandBlue else TextSubtle,
        animationSpec = tween(navSpecMs(selected, pressed)),
        label = "nav-tint",
    )
    val iconScale by animateFloatAsState(
        targetValue = if (selected) SELECTED_ICON_SCALE else 1f,
        animationSpec = tween(navSpecMs(selected, pressed)),
        label = "nav-scale",
    )
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) PRESS_SCALE else 1f,
        label = "nav-press",
    )

    Box(
        modifier = modifier
            .padding(horizontal = 4.dp, vertical = 8.dp)
            .scale(pressScale)
            .clip(RoundedCornerShape(percent = 50))
            .background(fill)
            .clickable(
                interactionSource = interaction,
                // 关掉涟漪：浅色药丸上扩散的半透明矩形会糊出一个方块
                //（同一个坑在水彩时代已经踩过一次）——按下反馈改由 PressFill + 缩放承担
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                contentDescription = tab.label,
                tint = tint,
                modifier = Modifier
                    .size(20.dp)
                    .graphicsLayer {
                        scaleX = iconScale
                        scaleY = iconScale
                    },
            )
            // 只有选中项显示文字 —— 五个 Tab 全带文字会挤，也不像药丸
            if (selected) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = tab.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = tint,
                )
            }
        }
    }
}
