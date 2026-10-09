package ai.yuki.chuxue.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 「Yuki 初雪」色彩系统 —— **现代扁平卡片风**（用户 2026-09-27 定调）。
 *
 * ## 这是一次整语言替换，不是调色
 * 原先的水彩 / 液态玻璃依赖「半透明的白 + 低 alpha 叠加」，在浅色底上因此踩过两次坑
 * （液滴隐形、胶囊不可见）—— 因为那套配方是按**深色背景**给的。
 *
 * 扁平风换了一条更稳的路：**实色底 + 阴影分层**，不靠透明度做层次。
 * 这样不论背景深浅，卡片与容器的边界都立得住。
 *
 * 用户给定的值（第一版）：背景 `#F5F7FA`、主色 `#4262FF → #8E3BFF`、副文字 `#8A8F99`、
 * 卡片纯白、输入框底 `#F0F2F5`。
 *
 * ⚠️ **2026-09-27 修正**：用户反馈「整体 ui 别用紫色，很降档次，我要的是简约中不失美感」。
 * 于是主色右移为 `#2F6BFF`（色相 223°，纯蓝）、渐变终点由紫 `#8E3BFF` 改为深蓝 `#1B4FD8`，
 * 并清掉三处紫色派生色（`PrimaryBlueDark` / `PrimaryBlueLight` / `AuroraVioletSoft`）。
 * 「薄紫」那个背景预设一并换成了中性灰蓝。**紫色不再是这套视觉的一部分。**
 *
 * ## 旧名为什么保留（这不是历史包袱，是本轮能"不动 View 层级"的关键）
 * v0.5.0 起的组件大量引用旧名（`SnowWhite` / `SkyBlue` / `FrostLine` …）。
 * 本轮**不去改那些组件**，而是让旧名**指向新色板** —— 一处改、全局跟随。
 * `DesignTokensTest` 钉住「别名与新值同值」，防止将来有人只改一边。
 */

/* ═══════════════ 扁平风色板 —— 唯一的事实来源 ═══════════════ */

/** 全局背景：极浅冷灰白 */
val FlatBackground = Color(0xFFF5F7FA)

/** 卡片：纯白 */
val FlatCard = Color(0xFFFFFFFF)

/** 输入框 / 次级容器底：浅灰 */
val FieldFill = Color(0xFFF0F2F5)

/**
 * 品牌主色（唯一强调色）：明快蓝。
 *
 * ⚠️ 2026-09-27 用户反馈：「整体 ui 别用紫色，很降档次，我要的是简约中不失美感」。
 * 原值 `#4262FF` 色相 230°，已经压在蓝紫交界上；现右移到 `#2F6BFF`（色相 223°），
 * 落回纯蓝区间。`FlatCardThemeTest` 有一条断言把「主色与强调色的色相 < 260°」钉住 ——
 * 紫色在 265° 左右，所以这是**改不回去的**（除非连那条断言一起改，那会是个显式决定）。
 */
val BrandBlue = Color(0xFF2F6BFF)

/** 主色深档：渐变终点、深色强调。**取代原来的紫色 `#8E3BFF`**。 */
val BrandBlueDeep = Color(0xFF1B4FD8)

/** 主色浅档（次级强调）。原值 `#7B8CFF` 同样偏紫，一并右移。 */
val BrandBlueLight = Color(0xFF6E9BFF)

/** 主色的极浅档：选中态药丸底、浅色强调块 */
val BrandBlueSoft = Color(0xFFE8ECFF)

/**
 * ⚠️ **已废弃的名称**：紫色不再属于这套视觉。
 *
 * 保留它只为不破坏仍在引用的两处（开屏标题渐变、对话框按钮渐变）——
 * 它们现在拿到的是 [BrandBlueDeep]（同色系深蓝），代码一行没改而结果已经正确。
 * **新代码请直接用 [BrandBlueDeep]。**
 */
val BrandViolet = BrandBlueDeep

/** 副文字：中灰 */
val TextSubtle = Color(0xFF8A8F99)

/* ═══════════════ 「柔和卡片」材质（v0.35.0）═══════════════ */

/**
 * 聊天输入区与底部导航栏共用的**同一种材质**：浅灰白底 + 右上角一抹淡淡的暖米粉，
 * 靠柔和的外阴影浮起来，边缘一道极细的高光。
 *
 * ## ⚠️ 它与"玻璃拟态"是两回事 —— 用户 2026-09-28 明确划清了这条线
 * 用户原话：「**不要背景模糊，不要玻璃拟态效果**」。
 * 玻璃拟态的核心是"半透明、让底层内容透出来 + 模糊"；而这套材质是**不透明的实体卡片** ——
 * 层次由**径向渐变 + 阴影 + 高光**表达，**不由透明度表达**。
 *
 * ⚠️ 这条要求纠正了本项目一贯的路线：从水彩、液态玻璃，到 v0.34.0 的输入框，
 * 都在试图"靠透明度做层次"，并因此在浅色底上反复翻车
 *（白液滴隐形、低 alpha 胶囊不可见、白输入框叠白卡片）。**这次不走那条路。**
 *
 * ⚠️ 半透明色（那道高光描边）**不进色板** —— 沿用既有纪律：
 * 色板只放不透明色，半透明在组件叠加时逐处决定（`FlatCardThemeTest` 有断言钉住）。
 */

/** 卡片主体：浅灰白。比全局背景 [FlatBackground] 略亮一点，所以浮得起来。 */
val PanelBase = Color(0xFFF7F8FA)

/**
 * 材质底的不透明度。
 *
 * ⚠️ 它**必须先 < 1** —— 用户 2026-09-28 第三次定调：「**必须有半透明的效果**」。
 * （上一次他说的是"不要玻璃拟态"，于是 v0.35.0 做成了不透明；
 *  这一次要的是**底色带透明度**，但**不要模糊** —— 两者不是一回事。）
 *
 * ⚠️ 为什么它是**常量**而不是把 alpha 写进 [PanelBase]：
 * 沿用项目的既有纪律 —— **色板只放不透明色，半透明在组件叠加时逐处决定**。
 * 这样"这个牌子有多透"只有一处定义，改它不会连带影响任何别的色值。
 *
 * ⚠️ 取值区间是**算过的**：低于 0.45，浅色底上的面板会糊成一片、正文读不动
 *（本项目在"浅底上的半透明元素"上栽过三次）；高于 0.85，"透"就看不出来了。
 * `FlatCardThemeTest` 把这两端都钉住。
 */
const val PANEL_ALPHA = 0.62f

/**
 * 底栏用的不透明度 —— 比输入区**略高一点**。
 *
 * 两处差异化的一半靠它（另一半是圆角与底色）：底栏承载图标与文字，
 * 且它常驻在不变的背景上；输入区要透出滚动中的聊天记录。**同一材质，厚度不同。**
 *
 * ⚠️ v0.52.0（方案 A「厚雾玻璃」）：0.72 → **0.80**。用户 2026-09-30 要「有一点透明
 * 但又不透」——底栏下面主要是纯色背景（不像输入区下面有滚动内容），0.72 时"透"几乎看不见；
 * 提到 0.80 更接近"不透"、又保留一点透明感。上限 0.85 由 FlatCardThemeTest 钉住。
 */
const val NAV_PANEL_ALPHA = 0.80f

/**
 * 底栏材质的**底色**：纯白。
 *
 * ⚠️ 它与主界面背景 [FlatBackground]（#F5F7FA）差 **10/255** —— 这 10 个灰阶就是
 * "看得见的那一层"。原先进区与底栏共用 [PanelBase]（#F7F8FA），它只比背景亮 2/255，
 * 半透明叠上去等于没叠（用户看到的就是"一块和背景同色的底"，谈不上"透"）。
 * 底栏单独提亮到纯白，透明感才立得住；输入区不动，仍用 [PanelBase]。
 *
 * ⚠️ 纯白、不透明、不进半透明色板 —— 沿用「色板只放不透明色」的纪律。
 */
val NavPanelBase = Color(0xFFFFFFFF)

/** 柔和弥散的**浅灰**外阴影 —— 不是黑：黑阴影铺在浅色界面上会发脏。 */
val PanelShadowTint = Color(0xFF98A0AC)

/*
 * ⚠️ 这里原本有一个 `PanelWarmTint`（#FDF1EC，右上角那抹淡暖米粉），**v0.37.1 已删除**。
 * 用户原话：「颜色不要暖色」「底部导航栏也是高斯模糊不要暖色」。
 * 删掉而不是留着不用，是为了让后来者没法"顺手把它加回来" —— 面板现在只有中性色。
 */

/* ═══════════════ 开屏（§31）═══════════════ */

/**
 * 开屏背景渐变的终点（顶部是 [FlatBackground]，向下过渡到它）。
 * 只是极浅的一点冷暖差 —— 让背景看起来是**通透的**，而不是一块平涂。
 */
val SplashBackdropBottom = Color(0xFFE9F0FB)

/**
 * 开屏雪花的颜色。
 *
 * ⚠️ 它**必须是这个偏灰的蓝，不能是纯白**。雪在直觉里是白的，而开屏背景是
 * `#F5F7FA` 的浅色 —— 白雪花落在浅底上等于隐形。
 * 本项目在这一点上栽过三次（白液滴、低 alpha 胶囊、白输入框叠白卡片），
 * 所以这里的取值是**算过对比的**：本色的相对亮度约 0.46，与背景（约 0.94）
 * 差了近 0.5，一定看得见。
 */
val SplashSnowInk = Color(0xFF9FB6DD)

/* ═══════════════ 主题色（文档命名，值已换成新色板） ═══════════════ */

val PrimaryBlue = BrandBlue
val PrimaryBlueLight = BrandBlueLight
val PrimaryBlueDark = BrandBlueDeep

/** 背景。名字里的 "Watercolor" 已成历史 —— 新代码请直接用 [FlatBackground]。 */
val WatercolorBgLight = FlatBackground
val WatercolorBgDark = Color(0xFF14161C)

val SurfaceLight = FlatCard
val SurfaceDark = Color(0xFF1E2028)

/* 消息气泡：用户侧用主色极浅档（扁平风的"轻"，不再靠低 alpha），助手侧纯白 */
val UserBubbleLight = BrandBlueSoft
val AssistantBubbleLight = FlatCard
val UserBubbleDark = Color(0xFF2E3550)
val AssistantBubbleDark = Color(0xFF23262F)

val TextPrimaryLight = Color(0xFF1B1D22)
val TextSecondaryLight = TextSubtle
val TextPrimaryDark = Color(0xFFE9EBF0)
val TextSecondaryDark = Color(0xFF9AA0AA)

val ErrorColor = Color(0xFFE5484D)
val SuccessColor = Color(0xFF2FA36B)
val WarningColor = Color(0xFFE08700)

/* 补充层。扁平风里卡片不再用描边，但分隔线与输入框仍需要一个极浅的中性灰 */
val OutlineLight = Color(0xFFE6E8EC)
val OutlineDark = Color(0xFF33363F)
val SurfaceVariantLight = FieldFill
val SurfaceVariantDark = Color(0xFF262932)
val PrimarySoftLight = BrandBlueSoft
val PrimarySoftDark = Color(0xFF2A3050)
val ErrorSoftLight = Color(0xFFFDECEC)
val ErrorSoftDark = Color(0xFF3A2426)
val WarningSoftLight = Color(0xFFFFF6E5)
val WarningSoftDark = Color(0xFF3A3122)
val PrimaryInk = Color(0xFF1B1D22)

/* ═══════════════ 旧名别名 —— 全部指向上面的新色板 ═══════════════ */

val SnowWhite = WatercolorBgLight
val SnowSurface = SurfaceLight
val SnowSurfaceDim = SurfaceVariantLight
val FrostLine = OutlineLight

val SkyBlue = PrimaryBlue
val SkyBlueDeep = PrimaryBlueDark
val IndigoInk = PrimaryInk
val IndigoDeep = Color(0xFF2C3E6B)

val IceCyan = PrimaryBlueLight
val IceCyanSoft = PrimarySoftLight
val AuroraViolet = BrandViolet

/** 曾经的淡紫底；紫色废弃后改为淡蓝底（值一并换掉，避免留一个"名字叫紫、看着也紫"的漏网之鱼）。 */
val AuroraVioletSoft = Color(0xFFE4ECFF)

val TextPrimary = TextPrimaryLight
val TextSecondary = TextSecondaryLight
val TextMuted = TextSecondaryLight

val WarnAmber = WarningColor
val WarnAmberBg = WarningSoftLight
val DangerRose = ErrorColor
val DangerRoseBg = ErrorSoftLight
val SuccessMint = SuccessColor
