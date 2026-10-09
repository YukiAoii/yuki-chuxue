package ai.yuki.chuxue.ui.auth

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.yuki.chuxue.ui.components.YukiBackdrop
import ai.yuki.chuxue.ui.components.yukiShadow
import ai.yuki.chuxue.R
import ai.yuki.chuxue.ui.theme.BrandBlue
import ai.yuki.chuxue.ui.theme.FlatBackground
import ai.yuki.chuxue.ui.theme.SplashBackdropBottom

/**
 * 认证相关界面的**共用视觉底座**（v0.60.0）。
 *
 * ## ⚠️ 这里所有数值都是**从设计稿量出来的**，不是"看着差不多"
 * 前几轮我犯过一个错：设计稿是自己定的规格，落 Compose 时却凭记忆写
 * （标题用 `MaterialTheme.typography.headlineSmall` —— 项目里它只有 **20sp**，
 * 而设计稿要 **27px**；对齐也写成了居中，设计稿是**左对齐**）。
 * 结果用户看到"和设计稿差别太大"。
 *
 * 现在这些数字来自对 `docs/design/认证界面_设计稿_v2_2026-10-02.html` 的**实测**
 * （浏览器里逐个元素读 `getComputedStyle` + `getBoundingClientRect`）：
 *
 * | 元素 | 设计稿实测 |
 * |---|---|
 * | 内容横向位置 | **左对齐**，x = 26（= 容器左右内边距） |
 * | 标题 | 欢迎页 27px/750，其余 23px/750，字距 -0.4，色 #171717 |
 * | 副标题 | 13.5px/400，行高 23，色 #8A9099 |
 * | 主按钮 | 高 52、圆角 16、#171717（禁用 #C9CFD8），文字 15.5px/650 |
 * | 次级按钮 | 高 52、圆角 16、白底 + 1px 描边，文字 15.5px/650 |
 * | 输入框 | **高 64**、圆角 14，标签 11.5px #B4BAC3，值 15px |
 * | 分组灰字 | 11.5px/650，色 #7A828D |
 * | 协议行 | 勾选框 17，文字 12.5px #8A9099，距上一元素 16 |
 *
 * ## 为什么不用 `MaterialTheme.typography.*`
 * 需求是"1:1 还原设计稿"。项目的 token（headlineSmall=20sp、bodySmall=13sp…）
 * 是按全站阅读节奏定的，与设计稿的这几个尺寸对不上。**照设计稿写死字号**，
 * 不引 token —— 这样设计稿一改，这里改一个数字就对得上。
 */

/* ═══════════════ 色板（全部取自设计稿） ═══════════════ */

/**
 * 主按钮的**纯黑**。
 *
 * ⚠️ 用户 2026-10-02 更正过：设计稿的按钮是 `#111111`，不是我原先写的 `#171717`。
 */
internal val AuthInk = Color(0xFF111111)

/** 副标题、说明文字的灰。 */
internal val AuthSubInk = Color(0xFF8A9099)

/** 更淡一档的灰：输入框标签、提示行。 */
internal val AuthFaintInk = Color(0xFFB4BAC3)

/** 输入框占位文字（比标签再淡一点）。 */
private val AuthPlaceholderInk = Color(0xFFC3C9D2)

/** 分组灰字。 */
private val AuthGroupInk = Color(0xFF7A828D)

/** 白卡（输入框、次级按钮、协议纸面）。 */
private val AuthCardWhite = Color(0xFFFFFFFF)

/** 卡片描边。 */
private val AuthHairline = Color(0x14000000)

/** 主按钮禁用态。 */
private val AuthInkDisabled = Color(0xFFC9CFD8)

/* ═══════════════ 尺寸（全部取自设计稿） ═══════════════ */

/** 内容左右内边距：设计稿里所有元素都从 x=26 开始。 */
internal val AUTH_PAD = 26.dp

/** 主/次按钮与输入框之间的常用间距（设计稿里字段之间是 12）。 */
internal val AUTH_FIELD_GAP = 12.dp

/* ═══════════════ 背景 ═══════════════ */

/**
 * 认证页背景 —— 直接委托给**全局**的 [YukiBackdrop]。
 *
 * 背景规格以 `docs/design/认证界面_设计稿_2026-10-02.html`（v1）为准（用户 2026-10-02 指定），
 * 1:1 实现放在 [YukiBackdrop] 里，所有页面共用同一份。
 */
@Composable
internal fun AuthBackdrop(content: @Composable () -> Unit) {
    // 认证页**自己**铺这层背景（规格见 ui/components/YukiBackdrop.kt）。
    // ⚠️ 不要再往 MainActivity 的 NavHost 上铺一遍 —— 那会连「登录后的页面」一起改掉；
    //    用户明确只让改界面 UI、不让动 App 内已有的观感（2026-10-02 已因此还原过一次）。
    YukiBackdrop { content() }
}

/* ═══════════════ 品牌标记 ═══════════════ */

/**
 * 品牌标记 = **软件图标本身**（用户 2026-10-02 指定："品牌用软件图标"）。
 *
 * 圆角方形（squircle）而不是圆形 —— 与系统启动器里那颗图标形状一致。
 * 设计稿实测尺寸：欢迎页 96（圆角 26）/ 登录 68（19）/ 注册 52（15）。
 */
@Composable
internal fun AuthBrandMark(size: Dp, corner: Dp) {
    Image(
        painter = painterResource(R.drawable.ic_app_logo),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(size)
            .yukiShadow(
    cornerRadius = corner, offsetY = 14.dp, blur = 30.dp,
    color = Color(0x47604089),   // .icon: 0 14px 30px rgba(64,90,137,.28)
)
            .clip(RoundedCornerShape(corner))
            .background(AuthCardWhite),
    )
}

/* ═══════════════ 标题组 ═══════════════ */

/**
 * 一屏的标题组：品牌标记 + 标题 + 副标题。**左对齐**（设计稿实测 x=26）。
 *
 * @param titleSize 欢迎页 27sp；其余 23sp（设计稿实测）
 * @param markGap 图标底到标题顶：欢迎页 24，登录 20，注册 18（设计稿实测）
 */
@Composable
internal fun AuthHeading(
    title: String,
    subtitle: String,
    markSize: Dp,
    markCorner: Dp,
    titleSize: androidx.compose.ui.unit.TextUnit,
    markGap: Dp,
    /** 居中？（用户 2026-10-02：登录页与注册第一步居中；选择页仍左对齐） */
    center: Boolean = false,
) {
    // ⚠️ logo **独立一层、锚在左边** —— 用户 2026-10-02：「logo 图标位置和设计稿不一样」。
    //    设计稿里 logo 与文字块一样是贴着左边距的（x=26），不是居中。
    //    这里用 fillMaxWidth 的 Box 把它钉在 Start，父级的 CenterHorizontally 就带不动它。
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        AuthBrandMark(size = markSize, corner = markCorner)
    }
    Spacer(Modifier.height(markGap))
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (center) Alignment.CenterHorizontally else Alignment.Start,
    ) {
    Text(
        text = title,
        fontSize = titleSize,
        lineHeight = titleSize * 1.37f,
        fontWeight = FontWeight.W700,
        letterSpacing = (-0.4).sp,
        color = AuthInk,
    )
    Spacer(Modifier.height(9.dp))
    Text(
        text = subtitle,
        fontSize = 13.5.sp,
        lineHeight = 23.sp,
        color = AuthSubInk,
        textAlign = if (center) TextAlign.Center else TextAlign.Start,
    )
    }
}

/* ═══════════════ 按钮 ═══════════════ */

/** 按压反馈：按下缩到 0.97，松开弹回（用户要求"各种按钮都增加按压效果"）。 */
@Composable
internal fun Modifier.pressScale(interaction: MutableInteractionSource): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = tween(90),
        label = "pressScale",
    )
    return this.graphicsLayer { scaleX = scale; scaleY = scale }
}

/**
 * 主行动键：近黑哑光 + 小圆角。禁用时换灰蓝。
 *
 * @param loading 正在请求（登录/注册/重设密码）。此时：
 *   · 保持**深色**（不是变灰）—— 灰掉会让人以为"点不动/出错了"；
 *   · 左侧转一个小圈。
 *   用户 2026-10-02：「点击登录的按钮之后增加加载效果」。
 */
@Composable
internal fun AuthPrimaryButton(
    text: String,
    enabled: Boolean = true,
    loading: Boolean = false,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .pressScale(interaction)
            .yukiShadow(
                cornerRadius = 16.dp, offsetY = 10.dp, blur = 24.dp,
                color = if (enabled && !loading) Color(0x33171717) else Color(0x00000000),
            )
            .clip(RoundedCornerShape(16.dp))
            .background(if (enabled || loading) AuthInk else AuthInkDisabled)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled && !loading,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = Color.White,
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(9.dp))
            }
            Text(text = text, fontSize = 15.5.sp, fontWeight = FontWeight(650), color = Color.White)
        }
    }
}

/** 次级行动键：极浅白底 + 细描边。 */
@Composable
internal fun AuthSecondaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .pressScale(interaction)
            .clip(RoundedCornerShape(16.dp))
            .background(AuthCardWhite)
            .border(1.dp, AuthHairline, RoundedCornerShape(16.dp))
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 15.5.sp,
            fontWeight = FontWeight(650),
            color = if (enabled) AuthInk else Color(0xFFA8AFB8),
        )
    }
}

/* ═══════════════ 分组灰字 / 提示 ═══════════════ */

/**
 * 字段上方那行小字（「你的 UID」「头像（可选）」「设置密码」）。
 *
 * ⚠️ 刻意**不用** `SettingsGroup` —— 它自带左右 16dp 内边距，
 * 放进认证页会比设计稿整体缩进一层。
 */
@Composable
internal fun AuthGroupLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        fontSize = 11.5.sp,
        fontWeight = FontWeight(650),
        letterSpacing = 0.7.sp,
        color = AuthGroupInk,
        modifier = modifier.fillMaxWidth(),
    )
}

/** 一行说明小字（密码规则、uid 提示那类）。 */
@Composable
internal fun AuthHint(text: String, modifier: Modifier = Modifier, color: Color = AuthFaintInk) {
    Text(
        text = text,
        fontSize = 11.5.sp,
        lineHeight = 18.sp,
        color = color,
        modifier = modifier.fillMaxWidth(),
    )
}

/* ═══════════════ 卡片式输入框（与设计稿 1:1） ═══════════════ */

/**
 * 认证页专用输入框：**纯白圆角矩形，直接浮在流体背景上**。
 *
 * ## 用户 2026-10-02 更正的三条（都改到了）
 * 1. **圆角 24dp**（我原先写 14 —— 那是照我自己那张设计稿量的，不是用户要的）
 * 2. **阴影是输入框自己的微弱阴影**，不是外层容器的厚重阴影
 * 3. **不套大白卡片**：整页就是「流体背景 + 悬浮输入框」，
 *    绝不把所有内容包进一个带外框的 Card 里
 *
 * 所以这里只有：白底 + 圆角 24 + 一层很淡的蓝灰投影（`shadow` 的
 * ambient/spot 都调成低透明度蓝灰，而不是默认的黑色），内部不加描边。
 *
 * ⚠️ 不用 `YukiTextField`：那是 Material 风格（label 在框内），与设计稿不是一回事。
 *    只在认证页用，不影响别处。
 */
@Composable
internal fun AuthField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String = "",
    isPassword: Boolean = false,
    isError: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .height(64.dp)
            // 精确阴影：对齐设计稿 .fld 的 `0 4px 14px rgba(32,58,99,.09)`
            .yukiShadow(
                cornerRadius = 24.dp, offsetY = 4.dp, blur = 14.dp,
                color = Color(0x17203A63),
            )
            .clip(RoundedCornerShape(24.dp))
            .background(if (isError) Color(0xFFFFF6F6) else AuthCardWhite)
            .padding(horizontal = 18.dp, vertical = 11.dp),
    ) {
        Text(
            text = label,
            fontSize = 11.5.sp,
            lineHeight = 17.sp,
            color = if (isError) Color(0xFFE5484D) else AuthFaintInk,
        )
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().heightIn(min = 20.dp)) {
            if (value.isEmpty() && placeholder.isNotEmpty()) {
                Text(
                    text = placeholder,
                    fontSize = 15.sp,
                    color = AuthPlaceholderInk,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.CenterStart),
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = true,
                textStyle = TextStyle(fontSize = 15.sp, color = AuthInk),
                visualTransformation = if (isPassword) {
                    PasswordVisualTransformation()
                } else {
                    VisualTransformation.None
                },
                cursorBrush = SolidColor(BrandBlue),
                modifier = Modifier.fillMaxWidth().align(Alignment.CenterStart),
            )
        }
    }
}

/* ═══════════════ 协议勾选 ═══════════════ */

/**
 * 「我已阅读并同意《使用协议》和《隐私政策》」—— 左边勾选框，
 * 右边整段文字里**两个书名号是可点的**，点进各自详情页。
 *
 * ## 为什么用 `LinkAnnotation` 而不是把文字拆成几个 Text
 * 拆开一旦换行就断在奇怪的位置（手机上这段很容易换行）。
 *
 * ⚠️ 勾选框**只负责勾选**，书名号只负责跳转 —— 用户点链接时不会顺手
 *    把自己没读过的协议"同意"掉。
 */
@Composable
internal fun AuthAgreementRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onOpenTerms: () -> Unit,
    onOpenPrivacy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val linkStyle = SpanStyle(color = BrandBlue, fontWeight = FontWeight(650))
    val text = buildAnnotatedString {
        append("我已阅读并同意")
        withLink(LinkAnnotation.Clickable("terms") { onOpenTerms() }) {
            withStyle(linkStyle) { append("《使用协议》") }
        }
        append("和")
        withLink(LinkAnnotation.Clickable("privacy") { onOpenPrivacy() }) {
            withStyle(linkStyle) { append("《隐私政策》") }
        }
    }

    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        // 勾选框：命中区 24dp、视觉 17dp —— 手指点得准，看着还是设计稿的大小
        // ⚠️ `indication = null` 是必须的：Compose 默认的 clickable 会画涟漪 + 焦点指示，
        //    在 24dp 这么小的方块上会**溢出到四周变成灰框**（用户 2026-10-02 报的）
        //    —— 改成"按下缩一下"的自己的反馈（用户也说了"推荐做点击效果"）。
        val cbInteraction = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .size(24.dp)
                .pressScale(cbInteraction)
                .clickable(
                    interactionSource = cbInteraction,
                    indication = null,
                    onClick = { onCheckedChange(!checked) },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(17.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (checked) BrandBlue else AuthCardWhite)
                    .border(
                        1.5.dp,
                        if (checked) BrandBlue else Color(0xFFC6CDD8),
                        RoundedCornerShape(5.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (checked) {
                    Text("✓", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.width(9.dp))
        Text(
            text = text,
            fontSize = 12.5.sp,
            lineHeight = 19.sp,
            color = AuthSubInk,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
