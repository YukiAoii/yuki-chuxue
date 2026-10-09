package ai.yuki.chuxue.ui.components

import ai.yuki.chuxue.data.ImageStore
import ai.yuki.chuxue.data.SCRIM_PLAIN
import ai.yuki.chuxue.ui.theme.ChatBackgrounds
import ai.yuki.chuxue.ui.theme.DEFAULT_SCRIM_ALPHA
import ai.yuki.chuxue.ui.theme.ScrimStyle
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
// v0.61.57：背景遮罩改真高斯模糊 —— 用 `Modifier.blur`（Android 12 以下自动降级为 no-op）
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 聊天背景层 —— 垫在消息列表与输入区之下的那层色底（对话设置页可选）。
 *
 * ## 为什么是"垫一层"而不是给每个气泡上色
 * 背景属于**页面**，不属于任何一条消息。给气泡染色会让"她说的"与"我说的"
 * 这套既有区分失效，而且一改背景就要重标定气泡配色 —— 那正是本项目
 * 「浅底上白元素透明元素隐形」栽过的坑。垫一层底，代价为零，气泡一个字节都不用动。
 *
 * ## 默认路径是零变化
 * `background = null` → [ChatBackgrounds.of] 给回默认，色值与全局背景
 * （`SnowWhite`）完全相同 —— 存量会话因此与加这个功能之前**逐像素相同**。
 *
 * ## ⚠️ 为什么它被抽到 `ui/components` 而不是留在聊天页里
 * **聊天页与背景设置页的预览必须画出同一个东西**。此前只在聊天页有一份，
 * 加预览时就必然要写第二份 —— 而"两处各写一段 when"正是本项目反复吃亏的地方
 *（设置页说晨雾、选择页说默认）。所以这里只有一份实现，两处都调它。
 *
 * ⚠️ 渲染未经真机验证（本机无 adb / emulator）。
 */
/**
 * 默认背景渐变的**终点色**（v0.61.31 加深：0xFFEDF2F9 → 0xFFD8E4F4）。
 *
 * ⚠️ 它必须**看得出是冷色** —— 否则顶栏模糊出来仍是一片白，
 *    那正是用户反复反馈的"不够透 / 看不出磨砂"。
 */
private val FROST_TINT = Color(0xFFD8E4F4)

/** 默认底那层雪点的随机种子 —— **固定值**，保证每次绘制的点位一致（不闪、不重排）。 */
private const val FROST_DOTS_SEED = 20261005

@Composable
fun ChatBackgroundSurface(
    background: String?,
    modifier: Modifier = Modifier,
    /** 遮罩开关。只对**自定义图片**有意义 —— 预设背景本身就是浅色 */
    scrimEnabled: Boolean = true,
    scrimAlpha: Float = DEFAULT_SCRIM_ALPHA,
    scrimStyle: String = SCRIM_PLAIN,
) {
    val path = ChatBackgrounds.customPathOf(background)
    if (path != null) {
        // 自定义图片：解码要读文件 → 放 IO 线程。解码失败一律回落到默认底，
        // **不把"图片没了"变成一片黑或一次崩溃**（用户可能清过应用数据）。
        val bitmap by produceState<ImageBitmap?>(initialValue = null, path) {
            value = withContext(Dispatchers.IO) {
                ImageStore.load(path)?.asImageBitmap()
            }
        }
        val bmp = bitmap
        if (bmp != null) {
            Box(modifier.fillMaxSize()) {
                Image(
                    bitmap = bmp,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                if (scrimEnabled) {
                    ScrimOverlay(alpha = scrimAlpha, style = ScrimStyle.of(scrimStyle))
                }
            }
            return
        }
    }

    // 内置预设 / 默认（也兜住"图片解不出来"）
    val bg = ChatBackgrounds.of(background)
    // ⚠️ v0.61.29（用户问题 4）：**单色也要铺一层极淡的纵向渐变**。
    //
    // ## 为什么必须动背景（这是数学问题，不是审美问题）
    // 顶栏的通透感**全部**来自"对下方内容做高斯模糊"；而**纯色被模糊之后还是同一个颜色** ——
    // 无论 blur 调到多大，屏幕上都不会有任何变化，于是顶栏看上去仍像一块白底。
    // 只有让背景本身带一点**极轻微的明暗差**，模糊之后才会显出"柔和的朦胧过渡"。
    //
    // ⚠️ 代价（如实记）：这**打破**了"默认背景与 SnowWhite 逐像素相同"这条既有约定 ——
    //    存量会话的背景会从纯白变成"白 → 极浅蓝灰"的渐变。
    //    幅度取在"肉眼几乎注意不到、但模糊后能辨出层次"（终点色比起点暗约 4%）。
    val brush = if (bg.gradient.size == 1) {
        val base = bg.gradient.first()
        // ⚠️ v0.61.31（用户：「还是不够透」）：渐变**加深到看得出色调**。
        //    顶栏的通透感只能来自"底下有东西可雾化" —— 纯白/近白背景模糊出来还是白，
        //    所以默认底必须**看得出冷色**（初雪主题的淡蓝），模糊后才有玻璃感。
        Brush.verticalGradient(listOf(base, lerp(base, FROST_TINT, 0.95f)))
    } else {
        Brush.verticalGradient(bg.gradient)
    }
    Box(modifier.fillMaxSize().background(brush)) {
        // ⚠️ v0.61.30（用户：「你这版做的也不透啊」）：
        //    **默认底上必须叠一层看得见的纹理**，否则顶栏的磨砂永远出不来 ——
        //    顶栏的通透感来自"对下方内容高斯模糊"，而**模糊一块白/极淡渐变，出来还是白**，
        //    顶栏看上去就是一块白板（用户说的"不透"）。
        //    加了这层雪点后，模糊区域才会显出"玻璃下面有东西"的层次。
        //
        // ⚠️ 用**固定种子的随机**：位置每次绘制都一样，不会闪、也不会每帧重排。
        // ⚠️ 幅度克制：点数与大小按"能看出纹理、但不抢文字"来定（1024px 宽下约 140 个点）。
        Canvas(Modifier.fillMaxSize()) {
            val rnd = kotlin.random.Random(FROST_DOTS_SEED)
            repeat(140) {
                val x = rnd.nextFloat() * size.width
                val y = rnd.nextFloat() * size.height
                val radius = 1.1.dp.toPx() + rnd.nextFloat() * 2.1.dp.toPx()
                drawCircle(
                    color = Color.White.copy(alpha = 0.55f + rnd.nextFloat() * 0.40f),
                    radius = radius,
                    center = Offset(x, y),
                )
            }
        }
    }
}

/**
 * 背景图上的一层遮罩。
 *
 * 它的**唯一职责是保住可读性**：用户选的图什么亮度都有，白气泡 + 深色文字直接铺上去
 * 会有读不了的时候。所以它不是装饰，而是"无论选什么图都还能用"的保障 ——
 * 也因此它不该被做成容易误关的东西（界面上的说明就是这么写的）。
 *
 * ## ⚠️ v0.61.57：从"半透明色"改成**真高斯模糊**（用户要求）
 * 用户原话：「聊天界面的背景设置的背景遮罩改为高斯模糊，遮罩浓度改为高斯模糊程度」。
 *
 * 改之前：`scrimAlpha` 是**白色蒙版的浓度** —— 调高只是把图压暗/压白，
 * 图本身的细节还在（花哨的图照样干扰阅读）。
 * 改之后：`scrimAlpha` 是**模糊程度** —— 调高把图真正糊掉，细节消失、只剩色块，
 * 文字自然就清楚了（与顶栏毛玻璃同一个手法，见 `ChatScreen` 的 `contentLayer`）。
 *
 * ⚠️ 保留一层**很淡**的白蒙版（`SCRIM_TINT_MAX`）—— 纯模糊遇到高对比图
 *    （比如黑白条纹）仍会干扰阅读；一点点提亮是最后一道保险。
 * ⚠️ `Modifier.blur` 在 **Android 12 以下自动降级为 no-op**（不崩）——
 *    那些机型退化成"只有那层淡蒙版"，仍是可用的观感（与顶栏毛玻璃同一条降级纪律）。
 */
@Composable
fun ScrimOverlay(
    alpha: Float,
    style: ScrimStyle,
    modifier: Modifier = Modifier,
) {
    // 模糊半径：0 时**不施加 blur**（省一层离屏渲染 —— 关掉遮罩的用户不该白付这份开销）
    val radius = style.blurRadius(alpha)
    Box(
        modifier
            .fillMaxSize()
            // ⚠️ 顺序要紧：**先模糊、再铺色**。反过来的话那层色也会被自己模糊掉，
            //    边缘会糊出屏幕外（看起来像"四周发虚"）。
            .then(if (radius > 0f) Modifier.blur(radius.dp) else Modifier)
            // 画笔来自 [ScrimStyle.brush] —— 与「遮罩风格」选择器上的小预览是**同一份**，
            // 这样"选的时候看到的"与"聊天页真的铺上去的"不会分家
            .background(style.brush(alpha)),
    )
}
