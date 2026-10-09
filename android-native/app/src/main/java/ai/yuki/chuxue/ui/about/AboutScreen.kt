package ai.yuki.chuxue.ui.about

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ai.yuki.chuxue.BuildConfig
import ai.yuki.chuxue.R
import ai.yuki.chuxue.data.AboutContent
import ai.yuki.chuxue.ui.icon.YukiIcons
import ai.yuki.chuxue.ui.theme.BrandBlue
import ai.yuki.chuxue.ui.theme.BrandBlueDeep
import ai.yuki.chuxue.ui.theme.FrostLine
import ai.yuki.chuxue.ui.theme.SkyBlueDeep
import ai.yuki.chuxue.ui.theme.SnowWhite
import ai.yuki.chuxue.ui.theme.TextMuted
import ai.yuki.chuxue.ui.theme.TextPrimary
import ai.yuki.chuxue.ui.theme.TextSubtle
import ai.yuki.chuxue.ui.components.YukiCard
import ai.yuki.chuxue.ui.update.ChangelogDialog

/* ═══════════════════════════════════════════════════════════════════════════
   「关于」页（v0.50.5 重做）
   ═══════════════════════════════════════════════════════════════════════════

   为什么整页重写
   --------------
   用户 2026-09-30：「关于页你直接按照 SQYU 工具箱的关于页来做吧」、
   「更新日志和检测更新的卡片直接把它的搬过来用包括点击之后的弹窗样式」、
   「作者卡片肯定是要不一样的 要有个实时效果和不一样的尊贵感」、
   「支持改为赞助 请作者喝奶茶 然后这个赞助卡片也要有差异化 不明显的差异化」、
   「致谢的天枢改为 Tianshu-harness」。

   从 `_refs/SQYU工具箱-主App源码`（用户自研、授权可借鉴）取的是**手法**不是配色：
   分节标题、Hero 扫光、作者卡的星点/流光动效、行式入口 + 「查看 ›」胶囊、
   全宽渐变按钮。SQYU 的作者卡是**黑金**，而用户在上一轮明确说过黑金
   「和 Yuki 不太符合」—— 所以这里全部换成**品牌蓝**主导，
   只保留黑金那套**动效结构**（光晕 + 星点 + 扫光）。

   ⚠️ 全页不使用 emoji（产品硬要求）。
   ⚠️ 渲染未经真机验证（本机无设备）。
   ⚠️ 本页**零网络请求**：文本全在 `AboutContent`（内置常量），
      图片全是 `R.drawable`/`R.mipmap`。唯一的网络行为是用户主动点「更新日志」。
   ═══════════════════════════════════════════════════════════════════════════ */

/**
 * 「关于」页入口。
 *
 * 拆到独立文件是因为它原本长在 `ui/main/MainTabs.kt`（那一个文件 1200+ 行、
 * 装着消息/市场/我的/关于四个 Tab）—— 单文件继续膨胀会越来越难改。
 * `MainTabs` 现在只保留一行转发。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutTab(onCheckUpdate: () -> Unit = {}) {
    var showChangelog by remember { mutableStateOf(false) }

    Scaffold(
        // 透明底：背景由 MainScreen 那一层统一给（Tab 内再铺一层会盖掉它）
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("关于", style = MaterialTheme.typography.headlineSmall) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(4.dp))
            HeroCard()

            Spacer(Modifier.height(20.dp))
            SectionLabel("软件介绍")
            IntroCard()

            Spacer(Modifier.height(20.dp))
            SectionLabel("作者")
            AuthorCard()

            Spacer(Modifier.height(20.dp))
            SectionLabel("赞助")
            SupportCard()

            Spacer(Modifier.height(20.dp))
            SectionLabel("版本与声明")
            VersionStatementCard(onOpenChangelog = { showChangelog = true })

            Spacer(Modifier.height(20.dp))
            SectionLabel("版本信息")
            VersionInfoCard(version = BuildConfig.VERSION_NAME, onCheckUpdate = onCheckUpdate)

            Spacer(Modifier.height(20.dp))
            SectionLabel("致谢")
            AckCard()

            Spacer(Modifier.height(24.dp))
            Text(
                text = AboutContent.COPYRIGHT,
                style = MaterialTheme.typography.labelSmall,
                color = TextSubtle,
                textAlign = TextAlign.Center,
            )
            // 让出悬浮导航栏
            Spacer(Modifier.height(56.dp))
        }

        if (showChangelog) {
            ChangelogDialog(onDismiss = { showChangelog = false })
        }
    }
}

/* ═══════════════════════════ Hero ═══════════════════════════ */

/**
 * 顶部品牌渐变卡 —— 全页的视觉锚点。
 *
 * ## 与 v0.50.5 版的差别（v0.52.0 重构）
 * 上一版把「检查更新 / 更新日志」两颗操作胶囊压在卡上，与下面
 * 「版本与声明」的更新日志入口、「版本信息」的检查更新按钮**重复**了。
 * 用户 2026-09-30：「关于页的检测更新和更新日志入口都做成只有独立的一个…
 * 把关于页顶部卡片的（按钮）删了」—— 所以这里**不再承载任何操作**。
 *
 * 取而代之的形态照 SQYU 关于页的 Hero：**横向**（图标 + 名称/版本）
 * 加一行**特性标签胶囊**（[AboutContent.HERO_TAGS]）——
 * 卡片回答"这是谁、它有什么"，操作交给下面的分节。
 *
 * ## 实时效果是什么
 * 一道**半透明冰蓝光带**沿对角线无限循环掠过（`infiniteTransition` + `LinearEasing`），
 * 外加两团静态光晕做底，再叠一层白半透明描边让卡片在浅色页面上"浮"起来。
 * 光带用冰蓝而不是白 —— 白在浅蓝渐变上几乎看不见。
 */
@Composable
private fun HeroCard() {
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.linearGradient(listOf(BrandBlue, BrandBlueDeep))),
    ) {
        // 白色流光描边：浅色页面上给卡片一条"浮起来"的边界（SQYU 手法，色改品牌白）
        Box(
            Modifier
                .matchParentSize()
                .clip(shape)
                .border(
                    width = 1.2.dp,
                    brush = Brush.linearGradient(
                        listOf(
                            SnowWhite.copy(alpha = 0.30f),
                            SnowWhite.copy(alpha = 0.06f),
                            SnowWhite.copy(alpha = 0.18f),
                        ),
                    ),
                    shape = shape,
                ),
        )
        // 实时扫光（周期 3.4s，线性匀速 —— 用 Ease 会看出"一顿一顿"）
        val sweep by rememberInfiniteTransition(label = "aboutHero")
            .animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 3400, easing = LinearEasing),
                ),
                label = "aboutSweep",
            )
        Canvas(Modifier.matchParentSize()) {
            // 两团静态光晕：给渐变一点"体积"，免得是一块平板
            drawCircle(
                Color.White.copy(alpha = 0.08f),
                radius = size.height * 0.95f,
                center = Offset(size.width * 0.88f, size.height * 0.08f),
            )
            drawCircle(
                Color.White.copy(alpha = 0.05f),
                radius = size.height * 0.6f,
                center = Offset(size.width * 0.68f, size.height * 0.92f),
            )
            // 掠过的光带（-25% → 125%）
            val cx = size.width * (-0.25f + 1.5f * sweep)
            val r = size.width * 0.38f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.16f),
                        Color(0xFF9FD8FF).copy(alpha = 0.06f),
                        Color.Transparent,
                    ),
                    center = Offset(cx, size.height * 0.4f),
                    radius = r,
                ),
                radius = r,
                center = Offset(cx, size.height * 0.4f),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 22.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 图标托一层白色半透明底 —— 让方图标在渐变上"立"起来
                Box(
                    Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(SnowWhite.copy(alpha = 0.14f))
                        .padding(2.dp),
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_app_logo),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(18.dp)),
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Yuki 初雪",
                        style = MaterialTheme.typography.headlineSmall,
                        color = SnowWhite,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(7.dp))
                    // 版本徽章（半透明白胶囊 —— 实色会在渐变上再叠一块不相关的颜色）
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(7.dp))
                            .background(SnowWhite.copy(alpha = 0.22f))
                            .padding(horizontal = 9.dp, vertical = 3.dp),
                    ) {
                        Text(
                            text = "v${BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.labelSmall,
                            color = SnowWhite,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(
                text = AboutContent.TAGLINE,
                style = MaterialTheme.typography.bodySmall,
                // 白字降亮度做层次 —— 渐变底上不能用灰字（会脏）
                color = SnowWhite.copy(alpha = 0.92f),
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                AboutContent.HERO_TAGS.forEach { tag ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(SnowWhite.copy(alpha = 0.16f))
                            .border(0.7.dp, SnowWhite.copy(alpha = 0.22f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = tag,
                            style = MaterialTheme.typography.labelSmall,
                            color = SnowWhite,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}

/* ═══════════════════════ 分节标题 ═══════════════════════ */

/**
 * 分节标签：左侧一小段品牌渐变短条 + 标题。
 *
 * 一屏里全是文字块时，分节靠这 3dp 的彩条就能立住（SQYU 的关于页也是这个作用）。
 */
@Composable
private fun SectionLabel(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 3.dp, height = 13.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Brush.verticalGradient(listOf(BrandBlue, BrandBlueDeep))),
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = SkyBlueDeep,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/* ═══════════════════════ 软件介绍 ═══════════════════════ */

/**
 * 介绍卡：一句定位 + **可展开**的功能清单。
 *
 * ## 为什么做成"可展开"而不是一次全铺
 * 用户要的是 SQYU 那种"短标题 + 想看再展开"的格式。
 * 一屏塞七条功能说明，会把这一页变成需要滚很久的说明书；
 * 而绝大多数人只想知道"这软件是干嘛的"—— 那 [AboutContent.INTRO] 一句就够了。
 */
@Composable
private fun IntroCard() {
    var expanded by remember { mutableStateOf(false) }

    YukiCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = AboutContent.INTRO,
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary,
            )
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(BrandBlue.copy(alpha = 0.35f), Color.Transparent),
                        ),
                    ),
            )
            Spacer(Modifier.height(10.dp))

            if (expanded) {
                AboutContent.FEATURES.forEach { (title, desc) ->
                    FeatureRow(title, desc)
                }
                Spacer(Modifier.height(4.dp))
            }

            // 展开 / 收起（浅品牌底的一条，横幅感 —— 比纯文字链接更明显）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(BrandBlue.copy(alpha = 0.08f), BrandBlueDeep.copy(alpha = 0.07f)),
                        ),
                    )
                    .clickable { expanded = !expanded }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (expanded) "收起" else "展开全部功能介绍",
                    style = MaterialTheme.typography.labelMedium,
                    color = SkyBlueDeep,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/** 一条功能：小圆点 + 标题 + 说明。 */
@Composable
private fun FeatureRow(title: String, desc: String) {
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .padding(top = 5.dp)
                .size(6.dp)
                .clip(CircleShape)
                .background(SkyBlueDeep),
        )
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(1.dp))
            Text(
                text = desc,
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
            )
        }
    }
}

/* ═══════════════════════ 作者卡 ═══════════════════════ */

/**
 * 作者卡：**品牌深蓝底 + 实时动效**（不用 SQYU 的黑金）。
 *
 * ## 实时效果
 * - **星点闪烁**：6 个点各自相位错开，亮度按正弦呼吸；
 * - **斜向流光**：一道浅蓝光带 5.2s 掠过一次。
 *
 * 两者都挂在同一个 `rememberInfiniteTransition` 上 —— 一张卡里跑两个无限动画，
 * 分开建过渡会让它们"各转各的"，看起来不整齐。
 */
@Composable
private fun AuthorCard() {
    val fx = rememberInfiniteTransition(label = "authorFx")
    val t by fx.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 5200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "authorSweep",
    )
    val glow by fx.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1700),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "authorGlow",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF16204A), Color(0xFF233066), Color(0xFF16204A))))
            .border(0.9.dp, BrandBlue.copy(alpha = 0.55f), RoundedCornerShape(20.dp)),
    ) {
        Canvas(Modifier.matchParentSize()) {
            // 角落光晕
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(BrandBlue.copy(alpha = 0.22f), Color.Transparent),
                    center = Offset(size.width * 0.9f, size.height * 0.08f),
                    radius = size.width * 0.5f,
                ),
                radius = size.width * 0.5f,
                center = Offset(size.width * 0.9f, size.height * 0.08f),
            )
            // 斜向流光
            val sweepX = size.width * (t * 1.6f - 0.3f)
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(Color.Transparent, Color(0x33B6E0FF), Color.Transparent),
                    start = Offset(sweepX - 130f, 0f),
                    end = Offset(sweepX + 130f, size.height),
                ),
            )
            // 星点闪烁（相位错开）
            val pts = listOf(
                0.08f to 0.18f, 0.22f to 0.72f, 0.55f to 0.12f,
                0.78f to 0.55f, 0.90f to 0.30f, 0.40f to 0.85f,
            )
            pts.forEachIndexed { i, p ->
                val phase = (t + i * 0.16f) % 1f
                val alpha = (0.15f + 0.75f * kotlin.math.abs(kotlin.math.sin((phase * Math.PI).toDouble()))).toFloat()
                drawCircle(
                    Color(0xFF9FD8FF).copy(alpha = alpha * 0.7f),
                    radius = 2.2f,
                    center = Offset(size.width * p.first, size.height * p.second),
                )
            }
        }

        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 雪晶自绘（呼吸发光）—— 与开屏同源的品牌记号
                Canvas(Modifier.size(18.dp).graphicsLayer { alpha = 0.72f + 0.28f * glow }) {
                    val s = size.minDimension
                    val st = s * 0.10f
                    val c = Color(0xFF9FD8FF)
                    for (i in 0 until 3) {
                        val angle = (i * 60.0) * Math.PI / 180.0
                        val dx = (kotlin.math.cos(angle) * s * 0.42f).toFloat()
                        val dy = (kotlin.math.sin(angle) * s * 0.42f).toFloat()
                        drawLine(
                            c,
                            start = Offset(s * 0.5f - dx, s * 0.5f - dy),
                            end = Offset(s * 0.5f + dx, s * 0.5f + dy),
                            strokeWidth = st,
                            cap = StrokeCap.Round,
                        )
                    }
                }
                Spacer(Modifier.width(7.dp))
                Text(
                    text = "Yuki 初雪",
                    color = Color(0xFFCFE6FF),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color.Transparent, BrandBlue.copy(alpha = 0.5f), Color.Transparent),
                        ),
                    ),
            )
            Spacer(Modifier.height(12.dp))

            AuthorLine(
                avatar = R.drawable.about_author,
                name = AboutContent.AUTHOR_NAME,
                role = AboutContent.AUTHOR_ROLE,
                sign = AboutContent.AUTHOR_SIGN,
            )
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(BrandBlue.copy(alpha = 0.20f)))
            Spacer(Modifier.height(10.dp))
            AuthorLine(
                avatar = R.drawable.about_author2,
                name = AboutContent.AUTHOR2_NAME,
                role = AboutContent.AUTHOR2_ROLE,
                sign = AboutContent.AUTHOR2_SIGN,
            )
        }
    }
}

/**
 * 作者卡里的一行：圆形头像 + 名字 + 职责标签 + 签名。
 *
 * ⚠️ 圆形由这里的 [CircleShape] 裁剪负责，**不依赖图片自带的透明通道** ——
 * 第二作者那张原图 alpha 全是 255（完全不透明），
 * 若指望"图片本身是圆的"就会画成方头像。
 */
@Composable
private fun AuthorLine(avatar: Int, name: String, role: String, sign: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(
            painter = painterResource(avatar),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(54.dp)
                .clip(CircleShape)
                .border(1.2.dp, BrandBlue.copy(alpha = 0.6f), CircleShape),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleSmall,
                    color = Color(0xFFEAF3FF),
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(7.dp))
                // 职责标签：两位作者**都要有**（用户点名要求给 YukiAoi 也加上）
                Box(
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0x33B6E0FF))
                        .border(0.6.dp, Color(0x66B6E0FF), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                ) {
                    Text(
                        text = role,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF9FD8FF),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(
                text = sign,
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xB3DCE9F7),
            )
        }
    }
}

/* ═══════════════════════ 赞助卡 ═══════════════════════ */

/**
 * 赞助卡（原「支持」）。
 *
 * ## 差异化 —— 但是"不明显的差异化"（用户原话）
 * 作者卡是**深蓝底 + 冷色动效**；这张改成**浅暖底 + 暖色描边**。
 * 差别刚好够"一眼分得清这是两件事"，又不至于抢走作者卡的重量 ——
 * 支援永远是次要动作，它不该比"谁做的"更响。
 */
@Composable
private fun SupportCard() {
    val warmLine = Color(0xFFE6C79C)
    val warmBg = Color(0xFFFDF9F3)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(warmBg)
            .border(1.dp, warmLine, RoundedCornerShape(18.dp)),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 奶茶杯自绘（暖色）—— 用画的而不是 emoji（产品禁用 emoji）
                Canvas(Modifier.size(16.dp)) {
                    val s = size.minDimension
                    val c = Color(0xFFC9924E)
                    val st = s * 0.10f
                    // 杯身（上宽下窄的梯形 ≈ 两笔）
                    drawLine(c, Offset(s * 0.22f, s * 0.30f), Offset(s * 0.30f, s * 0.86f), strokeWidth = st, cap = StrokeCap.Round)
                    drawLine(c, Offset(s * 0.78f, s * 0.30f), Offset(s * 0.70f, s * 0.86f), strokeWidth = st, cap = StrokeCap.Round)
                    drawLine(c, Offset(s * 0.30f, s * 0.86f), Offset(s * 0.70f, s * 0.86f), strokeWidth = st, cap = StrokeCap.Round)
                    drawLine(c, Offset(s * 0.22f, s * 0.30f), Offset(s * 0.78f, s * 0.30f), strokeWidth = st, cap = StrokeCap.Round)
                    // 吸管
                    drawLine(c, Offset(s * 0.56f, s * 0.08f), Offset(s * 0.50f, s * 0.34f), strokeWidth = st * 0.85f, cap = StrokeCap.Round)
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "请作者喝奶茶",
                    style = MaterialTheme.typography.titleSmall,
                    color = Color(0xFF8A5A20),
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = AboutContent.SUPPORT_LEAD,
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF9A7440),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DonateQrRows()
            }
        }
    }
}

/**
 * 一张收款码的**全部差异**（两张只差文案、资源、品牌强调色）。
 *
 * ⚠️ 抽成一个 data class 而不是给 [DonateQr] 塞四个参数：两张卡的差别是"数据"，
 *    以后加第三种收款方式（比如云闪付）只需要再加一个常量，不必碰渲染代码。
 */
private data class DonateTarget(
    val label: String,
    val hint: String,
    val resId: Int,
    val accent: Color,
)

/** 微信绿 / 支付宝蓝 —— 只用在小圆点与描边上，不铺满卡片（否则会和这一节的暖色底打架）。 */
private val DONATE_WECHAT = DonateTarget("微信", "微信扫一扫", R.drawable.pay_wechat, Color(0xFF07C160))
private val DONATE_ALIPAY = DonateTarget("支付宝", "支付宝扫一扫", R.drawable.pay_alipay, Color(0xFF1677FF))

/**
 * 两张收款码 + 「点开大图」的弹层。
 *
 * ⚠️ 状态（正在放大哪一张）**必须放在这一层**，不能放进 [DonateQr]：
 *    那样每张卡各持一份状态，两张可以同时"被放大"，屏幕上就叠出两个弹层。
 */
@Composable
private fun DonateQrRows() {
    var zoom by remember { mutableStateOf<DonateTarget?>(null) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DonateQr(DONATE_WECHAT, Modifier.weight(1f)) { zoom = DONATE_WECHAT }
        DonateQr(DONATE_ALIPAY, Modifier.weight(1f)) { zoom = DONATE_ALIPAY }
    }

    zoom?.let { target -> QrZoomDialog(target) { zoom = null } }
}

/**
 * 一张收款码卡片。
 *
 * ## 为什么两张**并排**而不是上下堆叠
 * 用户实际只会扫其中一张。并排能把「选哪张」压缩到一眼看完；
 * 堆叠则要滚动 —— 而这一块本来就该"扫完就走"。
 *
 * ## ⚠️ 白边（二维码的静区）不能省
 * 两张码的图都是**满幅**的（图案一直画到边缘），扫码器需要一个纯白缓冲区才认得出来。
 * 所以这里三层白边叠着给：外层卡片 10dp + 图外的白底 10dp + 图与边框之间的 7dp。
 * **把它们调小会直接导致扫不出来** —— 这不是"留白好不好看"的问题。
 */
@Composable
private fun DonateQr(
    target: DonateTarget,
    modifier: Modifier = Modifier,
    onZoom: () -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.verticalGradient(listOf(Color.White, target.accent.copy(alpha = 0.05f))),
            )
            .border(1.dp, target.accent.copy(alpha = 0.20f), RoundedCornerShape(16.dp))
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 品牌标：一个小圆点 + 名字。圆点用品牌色，是这张卡唯一的"身份色"
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(target.accent))
            Spacer(Modifier.width(5.dp))
            Text(
                text = target.label,
                style = MaterialTheme.typography.labelMedium,
                color = target.accent,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(8.dp))

        Box(
            Modifier
                .fillMaxWidth()
                // ⚠️ 固定 1:1：两张图裁方后本身是正方形，给它正方容器才不会出现
                //    "白边忽宽忽窄"（Fit 会在非正方容器里留出不定量的letterbox）
                .aspectRatio(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.White)
                .border(1.dp, Color(0xFFEFE7DC), RoundedCornerShape(10.dp))
                .clickable(onClick = onZoom)
                .padding(7.dp),
        ) {
            Image(
                painter = painterResource(target.resId),
                contentDescription = "${target.label} 收款码",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }

        Spacer(Modifier.height(7.dp))
        Text(
            text = "点开可放大",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF9A7440).copy(alpha = 0.8f),
        )
    }
}

/**
 * 放大看收款码。
 *
 * ## 为什么要这个弹层
 * 卡片只有半屏宽 ≈ 145dp 的码，另一台手机要凑很近才扫得动。
 * 放大到 240dp 之后一次就能扫上 —— 这是"少让用户挪一次手"的改动。
 */
@Composable
private fun QrZoomDialog(target: DonateTarget, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(22.dp))
                .background(Color.White)
                .clickable(onClick = onDismiss)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(target.accent))
                Spacer(Modifier.width(6.dp))
                Text(
                    text = target.label,
                    style = MaterialTheme.typography.titleSmall,
                    color = target.accent,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(14.dp))
            Image(
                painter = painterResource(target.resId),
                contentDescription = "${target.label} 收款码",
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(240.dp).clip(RoundedCornerShape(12.dp)),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "${target.hint} · 点任意处关闭",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF9A7440),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/* ═══════════════ 版本与声明（更新日志入口） ═══════════════ */

/**
 * 更新日志入口行 —— 样式照搬 SQYU：图标块 + 标题/副标 + 右侧「查看 ›」胶囊。
 *
 * 用户原话：「更新日志和检测更新的卡片直接把它的搬过来用」。
 */
@Composable
private fun VersionStatementCard(onOpenChangelog: () -> Unit) {
    YukiCard(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenChangelog)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(BrandBlue.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    YukiIcons.Book,
                    contentDescription = null,
                    tint = BrandBlue,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "更新日志",
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextPrimary,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "每一版改了什么，都在这里回看",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(BrandBlue.copy(alpha = 0.10f))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(
                    text = "查看 ›",
                    style = MaterialTheme.typography.labelSmall,
                    color = SkyBlueDeep,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/* ═══════════════════════ 版本信息 ═══════════════════════ */

/**
 * 版本信息卡：版本 / 特性两行 + 全宽品牌渐变「检查更新」按钮（样式照搬 SQYU）。
 *
 * ⚠️ 这是**手动**检查入口。启动时那次检查是静默的（见 `UpdateViewModel`），
 * 所以必须有一个看得见的按钮 —— 否则用户会以为"这软件从来不检查更新"。
 */
@Composable
private fun VersionInfoCard(version: String, onCheckUpdate: () -> Unit) {
    YukiCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            InfoRow("版本", "v$version · ${AboutContent.AUTHOR_NAME} · ${AboutContent.AUTHOR2_NAME}")
            Spacer(Modifier.height(6.dp))
            InfoRow("数据", "全部只存在这台手机上，不上传、不经过中间服务器")
            Spacer(Modifier.height(6.dp))
            InfoRow("许可", "Apache License 2.0 · 完整条款见 apache.org/licenses/LICENSE-2.0")

            Spacer(Modifier.height(14.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(13.dp))
                    .background(Brush.linearGradient(listOf(BrandBlueDeep, BrandBlue)))
                    .clickable(onClick = onCheckUpdate)
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "检查更新",
                    style = MaterialTheme.typography.labelLarge,
                    color = SnowWhite,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/** 「标签 · 说明」一行。 */
@Composable
private fun InfoRow(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = SkyBlueDeep,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = TextPrimary,
        )
    }
}

/* ═══════════════════════ 致谢 ═══════════════════════ */

/**
 * 致谢卡。
 *
 * ⚠️ **「天枢（Tianshu）」已按用户要求改名为 `Tianshu-harness`**（v0.50.5）——
 * 那是它作为编程 Agent 运行时的正式项目名。
 */
@Composable
private fun AckCard() {
    YukiCard(Modifier.fillMaxWidth()) {
        Column {
            AckRow("Tianshu-harness", "编程 Agent 运行时 · 前缀缓存优化机制参考", "Apache-2.0")
            HorizontalDivider(
                modifier = Modifier.padding(start = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            // ⚠️ 这一项原来标的是 Apache-2.0，**是错的** ——
            //    Operit 仓库的 LICENSE 实为 LGPL-3.0。源码公开前按 LICENSE 原文修正。
            AckRow("Operit", "角色记忆引擎 · 记忆库方案参考", "LGPL-3.0")
            HorizontalDivider(
                modifier = Modifier.padding(start = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            AckRow("deepseek-harness", "上下文压缩的区域划分与计量口径参考", "MIT")
            HorizontalDivider(
                modifier = Modifier.padding(start = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            AckRow("心潮 · 念", "角色记忆服务（独立部署，经接口调用）", "AGPL-3.0")
            HorizontalDivider(
                modifier = Modifier.padding(start = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            AckRow("MemMe", "记忆系统架构评估参考", "Apache-2.0")
            HorizontalDivider(
                modifier = Modifier.padding(start = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            AckRow("kibotu / androidx-splashscreen-compose", "启动屏与开屏动画的交互参考", "Apache-2.0")
        }
    }
}

@Composable
private fun AckRow(name: String, desc: String, license: String?) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(name, style = MaterialTheme.typography.titleSmall, color = TextPrimary)
        Spacer(Modifier.height(4.dp))
        Text(
            desc,
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted,
        )
        if (license != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                "License: $license",
                style = MaterialTheme.typography.labelSmall,
                color = TextSubtle,
            )
        }
    }
}
