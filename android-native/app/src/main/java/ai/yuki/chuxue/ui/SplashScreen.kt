package ai.yuki.chuxue.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import ai.yuki.chuxue.R
import ai.yuki.chuxue.ui.theme.BrandBlue
import ai.yuki.chuxue.ui.theme.FlatBackground
import ai.yuki.chuxue.ui.theme.SplashBackdropBottom
import ai.yuki.chuxue.ui.theme.SplashSnowInk
import ai.yuki.chuxue.ui.theme.TextMuted
import ai.yuki.chuxue.ui.theme.YukiDuration
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 开屏页 —— **v0.60.0 按 SQYU 工具箱那套重做**（用户 2026-10-02 指定参考）。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * 参考物与改写说明
 * ═══════════════════════════════════════════════════════════════════════════
 * 参考源码：`_refs/SQYU工具箱-主App源码/app/src/main/java/com/java/myapplication/ui/Splash.kt`
 * （那个 v5.31「简约高级感」版）。它的结构是"上视觉 / 下文字"的杂志版式：
 *
 * | SQYU 的元素 | 初雪这里怎么改 |
 * |---|---|
 * | 纯净浅底 + 两团漂移光晕 | 保留，色换成初雪的冷调（蓝 + 青） |
 * | 图标 118dp 圆角 30 **弹簧入场** | 保留，图标换成 `ic_app_logo` |
 * | 两圈**水波**扩散 + 一线**斜向扫光** | 原样保留 |
 * | 双色标题（品牌蓝 + 深墨） | 「**Yuki**」蓝 +「**初雪**」深墨 |
 * | 210dp **流光带**无限左→右扫 | 原样保留 |
 * | 副标 + 底部小字依次淡入 | 保留；文案换成初雪自己的 |
 * | 收尾 **scale→1.05 + 上移 + 淡出** | 保留 —— 这是"平滑交棒"的关键 |
 *
 * **初雪自己加回来的**：落雪场。雪是「初雪」的母题，SQYU 没有；
 * 而且 [SplashSnow] 的确定性由 `SplashSnowTest` 钉着，不能动。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * 门控逻辑（与"好不好看"无关，原样保留）
 * ═══════════════════════════════════════════════════════════════════════════
 * `ready && minElapsed` 两个条件都满足才交棒，外加 [SPLASH_MAX_WAIT_MS] 超时兜底。
 * 初始化若卡住，**开屏绝不能变成"App 打不开"的原因**。
 *
 * ⚠️ **交棒前先做缩放淡出再喊 onFinished**（420ms）—— 这是用户要的
 *    「开屏动画平滑过渡到首次显示的选择/登录/注册界面」。
 *    配合 MainActivity 里 SPLASH 路由的 `exitTransition = fadeOut`，两端都动，
 *    不会出现"这边还在、那边硬切"的错位。
 *
 * ⚠️ 动画观感**未经真机验证**（本机无 adb、无模拟器）。
 */
@Composable
fun SplashScreen(
    /** 初始化是否完成（由 `ChatViewModel.ready` 驱动） */
    ready: Boolean,
    onFinished: () -> Unit,
) {
    // 雪花场只生成一次 —— 布局在整段开屏里保持稳定（确定性由 SplashSnowTest 钉住）
    val flakes = remember { SplashSnow.field() }
    val snowT by rememberInfiniteTransition(label = "snow").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = SNOW_CYCLE_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "snowT",
    )

    // ── 光晕漂移（背景在两团之间缓缓呼吸，画面不会变成一张静止的图）──
    val drift = rememberInfiniteTransition(label = "drift")
    val dx by drift.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(6200, easing = LinearEasing), RepeatMode.Reverse),
        label = "dx",
    )
    val dy by drift.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(7600, easing = LinearEasing), RepeatMode.Reverse),
        label = "dy",
    )
    /**
     * **呼吸** —— 0→1 慢循环，用来给图标背后那团光晕做明暗。
     * 入场时间轴只播一次，播完画面就"死"了；这一条是**持续**的，
     * 所以就算初始化拖到 5 秒，开屏也不会变成一张静止的图。
     */
    val breath by drift.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breath",
    )

    /** 流光带的位置（无限左→右扫）。 */
    val shim by drift.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart),
        label = "shim",
    )

    // ── 入场时间轴（≈2.2s，对齐 SQYU 的节奏）──
    val bgAlpha = remember { Animatable(0f) }
    val iconAlpha = remember { Animatable(0f) }
    val iconScale = remember { Animatable(0.82f) }
    val ripple1 = remember { Animatable(0f) }
    val ripple2 = remember { Animatable(0f) }
    val scanX = remember { Animatable(0f) }
    val titleAlpha = remember { Animatable(0f) }
    val subAlpha = remember { Animatable(0f) }
    val footAlpha = remember { Animatable(0f) }
    /** 交棒用的整体缩放淡出：0→1。 */
    val zoomOut = remember { Animatable(0f) }

    var minElapsed by remember { mutableStateOf(false) }
    var timedOut by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        bgAlpha.animateTo(1f, tween(320))
        iconAlpha.animateTo(1f, tween(240))
        // 弹簧入场：SQYU 用的是 MediumBouncy/MediumLow —— 有回弹但不轻浮
        iconScale.animateTo(
            1f,
            spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        )
        launch { ripple1.animateTo(1f, tween(780, easing = FastOutSlowInEasing)) }
        launch { ripple2.animateTo(1f, tween(620, delayMillis = 260, easing = FastOutSlowInEasing)) }
        launch { scanX.animateTo(1f, tween(1100, delayMillis = 620, easing = FastOutSlowInEasing)) }

        delay(140)
        titleAlpha.animateTo(1f, tween(160))
        subAlpha.animateTo(1f, tween(360, delayMillis = 200))
        footAlpha.animateTo(1f, tween(380, delayMillis = 260))

        // 两个门控计时器
        launch { delay(YukiDuration.Splash.toLong()); minElapsed = true }
        launch { delay(SPLASH_MAX_WAIT_MS); timedOut = true }
    }

    // ── 交棒：条件满足 → **先缩放淡出** → 再 onFinished ──
    LaunchedEffect(ready, minElapsed, timedOut) {
        if (!((ready && minElapsed) || timedOut)) return@LaunchedEffect
        zoomOut.animateTo(1f, tween(420, easing = FastOutSlowInEasing))
        onFinished()
    }

    Box(
        Modifier
            .fillMaxSize()
            // 交棒：整体轻微放大 + 上移 + 淡出（SQYU 的收尾手法）
            .graphicsLayer {
                alpha = 1f - zoomOut.value
                scaleX = 1f + zoomOut.value * 0.05f
                scaleY = 1f + zoomOut.value * 0.05f
                translationY = -zoomOut.value * 26f
            }
            .background(Brush.verticalGradient(listOf(FlatBackground, SplashBackdropBottom)))
            .graphicsLayer { alpha = bgAlpha.value },
    ) {
        // ① 落雪 —— 初雪的母题（SQYU 没有这一层）
        Canvas(Modifier.fillMaxSize()) {
            flakes.forEach { f ->
                drawCircle(
                    color = SplashSnowInk.copy(alpha = f.alpha),
                    radius = f.radius.dp.toPx(),
                    center = Offset(
                        x = f.x * size.width,
                        y = SplashSnow.yOf(f, snowT) * size.height,
                    ),
                )
            }
        }

        // ② 两团极淡光晕（左上蓝 / 右下青），随 drift 缓缓漂
        Box(
            Modifier
                .align(Alignment.TopStart)
                .padding(top = 72.dp)
                .size(340.dp)
                .graphicsLayer {
                    translationX = -80.dp.toPx() + dx * 46f
                    translationY = dy * 30f
                }
                .background(
                    Brush.radialGradient(listOf(BrandBlue.copy(alpha = 0.12f), Color.Transparent)),
                    CircleShape,
                ),
        )
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 110.dp)
                .size(360.dp)
                .graphicsLayer {
                    translationX = 90.dp.toPx() - dx * 38f
                    translationY = -dy * 26f
                }
                .background(
                    Brush.radialGradient(listOf(Color(0x1459D6B2), Color.Transparent)),
                    CircleShape,
                ),
        )

        // ③ 上视觉 + 下文字
        Column(
            Modifier
                .align(Alignment.Center)
                .offsetY(-30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 视觉核心：水波 + 扫光 + 主图标，三层叠在同一个中心
            Box(Modifier.size(190.dp), contentAlignment = Alignment.Center) {
                // 水波第一圈
                Box(
                    Modifier
                        .size(160.dp)
                        .graphicsLayer {
                            val p = ripple1.value
                            scaleX = 0.82f + p * 0.5f
                            scaleY = 0.82f + p * 0.5f
                            alpha = (1f - p) * 0.45f
                        }
                        .border(1.3.dp, BrandBlue.copy(alpha = 0.40f), CircleShape),
                )
                // 水波第二圈（延迟 260ms 扩散，形成"涟漪"）
                Box(
                    Modifier
                        .size(160.dp)
                        .graphicsLayer {
                            val p = ripple2.value
                            scaleX = 0.9f + p * 0.9f
                            scaleY = 0.9f + p * 0.9f
                            alpha = (1f - p) * 0.30f
                        }
                        .border(1.dp, BrandBlue.copy(alpha = 0.25f), CircleShape),
                )
                // 斜向扫光：一线白光划过图标区
                Box(
                    Modifier
                        .size(190.dp)
                        .graphicsLayer {
                            val p = scanX.value
                            translationX = lerp(-170.dp.toPx(), 170.dp.toPx(), p)
                            rotationZ = -24f
                            alpha = p * 0.55f
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(width = 60.dp, height = 220.dp)
                            .background(
                                Brush.linearGradient(
                                    listOf(Color.Transparent, Color(0x2EFFFFFF), Color.Transparent),
                                ),
                            ),
                    )
                }
                // 呼吸光晕：图标后面一团品牌色柔光，缓慢明暗 —— 让"静态图标"活起来。
                // ⚠️ 放在主图标**之前**：Compose 里先画的在下层。
                Box(
                    Modifier
                        .size(156.dp)
                        .graphicsLayer {
                            val p = breath
                            scaleX = 0.94f + p * 0.12f
                            scaleY = 0.94f + p * 0.12f
                            alpha = 0.16f + p * 0.24f
                        }
                        .background(
                            Brush.radialGradient(
                                listOf(BrandBlue.copy(alpha = 0.55f), Color.Transparent),
                            ),
                            CircleShape,
                        ),
                )

                // 主图标：无底座，直接悬浮；弹簧入场
                Image(
                    painter = painterResource(R.drawable.ic_app_logo),
                    contentDescription = null,
                    modifier = Modifier
                        .size(118.dp)
                        .clip(RoundedCornerShape(30.dp))
                        .graphicsLayer {
                            alpha = iconAlpha.value
                            scaleX = iconScale.value
                            scaleY = iconScale.value
                        },
                )
            }

            Spacer(Modifier.height(52.dp))

            // 双色标题：「Yuki」品牌蓝 +「初雪」深墨（照 SQYU 的双色手法）
            Row(
                Modifier.graphicsLayer {
                    alpha = titleAlpha.value
                    scaleX = 0.92f + titleAlpha.value * 0.08f
                    scaleY = 0.92f + titleAlpha.value * 0.08f
                    translationY = (1f - titleAlpha.value) * 10.dp.toPx()
                },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Yuki", fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 3.sp, color = BrandBlue)
                Text("初雪", fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 10.sp, color = Color(0xFF181B2E))
            }

            Spacer(Modifier.height(14.dp))
            // 流光带：底条 + 一段高光无限左→右扫过
            Box(
                Modifier
                    .width(210.dp)
                    .height(2.dp)
                    .clip(CircleShape)
                    .background(BrandBlue.copy(alpha = 0.06f)),
            ) {
                Box(
                    Modifier
                        .width(70.dp)
                        .height(2.dp)
                        .clip(CircleShape)
                        .graphicsLayer { translationX = shim * 210.dp.toPx() - 70.dp.toPx() }
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color.Transparent, BrandBlue.copy(alpha = 0.53f), Color.Transparent),
                            ),
                        ),
                )
            }

            Spacer(Modifier.height(18.dp))
            Text(
                text = "Ta记得你说过的每一句话",
                fontSize = 12.sp,
                letterSpacing = 2.sp,
                color = TextMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.graphicsLayer { alpha = subAlpha.value },
            )
        }

        // ④ 底部：版本 + 状态
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 40.dp)
                .graphicsLayer { alpha = footAlpha.value },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 「正在准备…」只在"动画放完了但还没就绪"时出现 —— 否则它是多余的噪音
            Text(
                text = if (minElapsed && !ready) "正在准备…" else "",
                fontSize = 10.5.sp,
                color = TextMuted,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 雪一轮飘完的时间。够慢才像雪，快了像雨。 */
private const val SNOW_CYCLE_MS = 11_000

/**
 * 开屏最多停留多久（毫秒）。
 *
 * 到点无条件进主界面 —— 宁可让用户看到一个还没填满的列表，
 * 也不能让开屏页变成"App 打不开"的原因。
 */
private const val SPLASH_MAX_WAIT_MS = 6_000L

/** 纵向微调（`offset` 的 import 已按需裁掉，这里用 graphicsLayer 的等价写法）。 */
private fun Modifier.offsetY(dy: androidx.compose.ui.unit.Dp): Modifier =
    this.graphicsLayer { translationY = dy.toPx() }
