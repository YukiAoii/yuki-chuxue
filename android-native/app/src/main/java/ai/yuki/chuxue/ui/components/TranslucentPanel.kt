package ai.yuki.chuxue.ui.components

import ai.yuki.chuxue.ui.theme.PanelBase
import ai.yuki.chuxue.ui.theme.PanelShadowTint
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 描边**上缘**：白高光（光从上方来）。 */
private const val EDGE_HIGHLIGHT_ALPHA = 0.75f

/**
 * 描边**下缘**：中性灰分界线。
 *
 * ⚠️ 这一条是"半透明在浅色主题上不致隐形"的**关键**：
 * 上缘的白高光叠在浅色背景上本来就看不见，只靠它，面板的下边界会消失。
 * 所以下缘用**比背景深一点**的中性灰 —— 它才是那条"看得见的边"。
 * （本项目在浅底上的半透明元素上栽过三次，这是代价换来的取值。）
 */
private const val EDGE_SHADE_ALPHA = 0.42f
// ⚠️ v0.61.21 由 0.30 提到 0.42：用户 2026-10-05「输入框再增加点立体效果，
//    这样在纯白背景下更能看到边界」。他说的"看不到边界"就是**这条下缘分界线太淡** ——
//    上缘那条白高光在白底上永远看不见（上面已经写明），所以白底场景里
//    面板能不能"立住"**全押在这一条上**。0.30 在有色背景上够用，在纯白上偏虚。

private val EDGE_WIDTH = 1.dp

/**
 * 「半透明面板」—— 聊天输入区与底部导航栏共用的那一层皮。
 *
 * ## 定调沿革（用户改过四次，每次都要照做）
 * | 版本 | 他要的 | 形态 |
 * |---|---|---|
 * | v0.34 | 「半透明**效果**」 | 玻璃三要素（半透明 + 高光 + 阴影） |
 * | v0.35 | 「不要背景模糊，不要玻璃拟态」 | **不透明**实体卡片 |
 * | v0.37 | 「必须有半透明的效果」 | 半透明 + 暖色光晕 |
 * | **v0.37.1** | **「颜色不要暖色，做半透明高斯模糊」** | **中性半透明**（**去掉了暖色**） |
 *
 * ⚠️ 本版去掉了那抹暖米粉（[ai.yuki.chuxue.ui.theme.PanelWarmTint]，现在只在色板里留着但**不再被使用**）：
 * 用户原话「颜色不要暖色」「底部导航栏也是高斯模糊不要暖色」。
 * 于是底从"径向渐变"退成**一层中性半透明色** —— 这也让下面的模糊更干净（渐变 + 模糊会脏）。
 *
 * ## ⚠️ 「高斯模糊」这一条：本组件**不负责**它
 * 真正的背景模糊（backdrop blur）没法在这个组件里做 —— 它要模糊的是**它下面的内容**，
 * 而那部分不在它的绘制范围里。做法见 `ui/components/` 下负责背景层记录的调用方，
 * 以及 `memory/ui-spec.md` 里记的代价（Compose 里做这件事要"录下层 → 模糊 → 裁到胶囊"，
 * 不是免费的）。
 *
 * ## 边界靠谁立住（半透明的核心问题）
 * 半透明面板叠在浅色背景上，最坏结果是**整块隐形**（本项目栽过三次）。
 * 所以边界**显式地**交给两样东西：
 * 1. **双色描边**：上缘白高光 + **下缘中性灰分界**（后者才是看得见的那条边 ——
 *    白高光叠在浅色背景上本来就看不见）；
 * 2. **阴影**：14dp，用**浅灰**而不是黑（黑阴影透过半透明底会在面板中间形成一层灰雾）。
 *
 * ## 实现上的一处必然
 * 底色**不能**挂 `Surface(color=)`：它只吃纯色、画不了需要知道盒子尺寸的东西。
 * 顺序也不能换：`shadow → clip → drawBehind → border`（`clip` 提前会把阴影一起裁掉）。
 *
 * ⚠️ 渲染未经真机验证（本机无 adb / 无 emulator）。
 */
@Composable
fun TranslucentPanel(
    modifier: Modifier = Modifier,
    /** 圆角 —— 输入区 36dp、底栏 28dp */
    corner: Dp,
    /** 底的不透明度（< 1 才叫半透明）—— 见 `PANEL_ALPHA` / `NAV_PANEL_ALPHA` */
    alpha: Float,
    // ⚠️ v0.61.21 由 14dp 提到 18dp：同一句"立体效果"要求。
    //    阴影是白底场景里另一条"立住"的依据 —— 抬 4dp 是在**不改变材质**的前提下
    //    把浮起感做实的最小改动（再大就会在浅灰背景上显得脏）。
    elevation: Dp = 18.dp,
    /**
     * 底的**颜色**。默认 [PanelBase]（输入区）；底栏传纯白 —— 提亮后才与背景
     * 拉开明度差，"透明"看得见（v0.52.0 方案 A「厚雾玻璃」）。
     */
    base: Color = PanelBase,
    /** 上缘白高光强度（默认 [EDGE_HIGHLIGHT_ALPHA]） */
    edgeHighlight: Float = EDGE_HIGHLIGHT_ALPHA,
    /** 下缘中性灰描边强度（默认 [EDGE_SHADE_ALPHA]，浅底上唯一看得见的那条边） */
    edgeShade: Float = EDGE_SHADE_ALPHA,
    /** 描边宽度（默认 [EDGE_WIDTH]） */
    edgeWidth: Dp = EDGE_WIDTH,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(corner)

    Box(
        modifier
            .shadow(
                elevation = elevation,
                shape = shape,
                ambientColor = PanelShadowTint,
                spotColor = PanelShadowTint,
            )
            .clip(shape)
            .drawBehind {
                // 一层中性半透明底：不带色相，只做"挡一层"和"透一点"两件事
                drawRect(color = base.copy(alpha = alpha))
            }
            .border(
                width = edgeWidth,
                brush = Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = edgeHighlight),
                        PanelShadowTint.copy(alpha = edgeShade),
                    ),
                ),
                shape = shape,
            )
            // ⚠️ 吃住点击（v0.44.3 修）。
            //
            // 面板原来只是一个 `Box` + `drawBehind` —— **背景绘制不等于可点区域**，
            // 触摸事件会从它身上穿过去落到下面的消息列表上。用户的原话是
            //「可以透过输入框点击界面」：点输入框旁边那块空白，下面的气泡会响应。
            //
            // 这里只是"占住"这块区域、什么也不做；**子组件优先**（输入框、加减号按钮
            // 照常拿到自己的事件），所以不会把输入框的聚焦抢走。
            // 用 `pointerInput` 而不是 `clickable`：后者会带一层涟漪反馈，
            // 而"点面板空白处"不该有任何视觉回应。
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        content()
    }
}
