package ai.yuki.chuxue.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「现代扁平卡片风（Modern Flat Card）」的令牌契约 —— 用户 2026-09-27 定调，
 * **取代**原先的水彩 / 液态玻璃视觉。
 *
 * ## 这套测试保护什么
 * 视觉语言换了，但**不变量照旧要能被钉住**：主色是不是用户给的那两个值、
 * 背景是不是那个冷灰白、卡片是不是真的去掉了描边、留白够不够。
 * 这些都不是「好不好看」（那只能靠真机），而是**结构事实** ——
 * 改错了会红，而不是"测试该改"。
 *
 * ⚠️ 渲染结果一律**未经真机验证**（本机无设备）。
 */
class FlatCardThemeTest {

    /* ══════════ 全局色 ══════════ */

    @Test
    fun `背景是极浅冷灰白 F5F7FA`() {
        assertEquals(Color(0xFFF5F7FA), FlatBackground)
    }

    @Test
    fun `品牌主色是纯蓝`() {
        assertEquals(Color(0xFF2F6BFF), BrandBlue)
        assertEquals(Color(0xFF1B4FD8), BrandBlueDeep)
    }

    /**
     * 这条断言**反向**于它原来的版本：原先钉的是「主色两端必须是蓝与紫」，
     * 现在钉的是「一个紫色都不许有」。
     *
     * 2026-09-27 用户反馈「整体 ui 别用紫色很降档次」。原来的值：
     * 主色 `#4262FF` 色相 230°（已压在蓝紫交界）、渐变终点 `#8E3BFF` 是 265°（紫区），
     * 而**被大量使用**的 `SkyBlueDeep` 更直接是 `#6B2FE0`（283°，纯紫）——
     * 这就是"整体看着发紫"的来源。
     *
     * 把"去紫"写成不变量，是为了让下一个想加回紫色的人先撞上这里，
     * 而不是靠他自己记得看过这条反馈。
     */
    @Test
    fun `强调色一律不许落在紫区`() {
        listOf(BrandBlue, BrandBlueDeep, BrandBlueLight, BrandBlueSoft).forEach { c ->
            val h = hueOf(c)
            assertTrue("$c 的色相 ${"%.1f".format(h)}° 落在紫区（≥260°）", h < 260f)
        }
    }

    @Test
    fun `渐变两端不同 —— 否则构不成渐变`() {
        assertNotEquals(BrandBlue, BrandBlueDeep)
    }

    /** RGB → 色相（0–360°）。只用来判断"蓝还是紫"，不追求色度学精确。 */
    private fun hueOf(c: Color): Float {
        val max = maxOf(c.red, c.green, c.blue)
        val min = minOf(c.red, c.green, c.blue)
        val d = max - min
        if (d == 0f) return 0f
        val h = when (max) {
            c.red -> ((c.green - c.blue) / d) % 6f
            c.green -> (c.blue - c.red) / d + 2f
            else -> (c.red - c.green) / d + 4f
        }
        return (h * 60f + 360f) % 360f
    }

    @Test
    fun `主色确实不再是水彩时代那个 6B9BD2`() {
        // 这条钉的是「**真的换了色**」，而不是"换了个名字"——
        // 若有人把 PrimaryBlue 改回旧值，这里会红。
        // （PrimaryBlue 现在**指向** BrandBlue，所以不能拿它俩互比。）
        assertNotEquals(Color(0xFF6B9BD2), PrimaryBlue)
        assertNotEquals(Color(0xFF6B9BD2), SkyBlue)
    }

    @Test
    fun `副文字色是 8A8F99`() {
        assertEquals(Color(0xFF8A8F99), TextSubtle)
    }

    @Test
    fun `卡片纯白、输入框浅灰`() {
        assertEquals("卡片是纯白", Color(0xFFFFFFFF), FlatCard)
        assertEquals("输入框底是浅灰", Color(0xFFF0F2F5), FieldFill)
    }

    @Test
    fun `新色板全部不透明 —— 半透明应由组件叠加时控制`() {
        listOf(FlatBackground, BrandBlue, BrandViolet, TextSubtle, FlatCard, FieldFill)
            .forEach { c -> assertEquals("颜色 $c 不是不透明色", 1f, c.alpha, 0.0001f) }
    }

    /* ══════════ 卡片规范 ══════════ */

    @Test
    fun `卡片去掉了描边`() {
        assertEquals("扁平风靠阴影分层，不靠描边", 0.dp, YukiCardSpec.BorderWidth)
    }

    @Test
    fun `卡片圆角落在 16 到 20`() {
        assertTrue(
            "实际 ${YukiCardSpec.CornerRadius}，用户要求 16 或 20",
            YukiCardSpec.CornerRadius >= 16.dp && YukiCardSpec.CornerRadius <= 20.dp,
        )
    }

    @Test
    fun `阴影极浅但存在`() {
        assertTrue("0dp = 卡片会糊在背景上", YukiCardSpec.Elevation > 0.dp)
        assertTrue(">4dp 就不是扁平了", YukiCardSpec.Elevation <= 4.dp)
    }

    @Test
    fun `卡片之间的留白达到用户要求的下限 12dp`() {
        assertTrue("实际 $CardGap，用户要求 12-16dp", CardGap >= 12.dp && CardGap <= 16.dp)
    }

    /* ══════════ 导航栏（悬浮药丸） ══════════ */

    @Test
    fun `导航栏左右与底部都留边距 —— 贴边就不叫悬浮`() {
        assertTrue(NavHorizontalMargin > 0.dp)
        assertTrue(NavBottomMargin > 0.dp)
    }

    @Test
    fun `选中态是浅色药丸，不是深色填充`() {
        assertTrue(
            "选中底色应当很浅（接近白），与「白卡片 + 蓝紫强调」的整套语言一致",
            NavSelectedFill.luminance() > 0.8f,
        )
    }

    @Test
    fun `输入框是无边框风格 —— 圆角取自输入框令牌`() {
        assertEquals(FieldCorner, YukiCardSpec.FieldCornerRadius)
    }

    /* ══════════ 「柔和卡片」材质（v0.35.0） ══════════ */

    /**
     * 用户 2026-09-28 的规格：「主体浅灰白色」；v0.37.1 追加「**不要暖色**」。
     *
     * ⚠️ 这条断言**收窄过一次**：它原来还钉着"那抹暖米粉要够淡"（阈值 0.06），
     * 但暖色本身已经被用户否掉了（`PanelWarmTint` 一并删除），
     * 于是"暖色有多淡"这件事不再有对象 —— 保留下来只钉**底够浅**与**不透明**。
     */
    @Test
    fun `面板材质：底够浅、且色值本身不透明`() {
        assertTrue("PanelBase 太暗，深色正文会读不动", PanelBase.luminance() > 0.9f)

        // 半透明不进色板：面板的"透"由组件层用 PANEL_ALPHA 决定（见 TranslucentPanel）
        listOf(PanelBase, PanelShadowTint).forEach { c ->
            assertEquals("颜色 $c 不是不透明色", 1f, c.alpha, 0.0001f)
        }
    }

    @Test
    fun `面板材质不含暖色 —— 用户要求不要暖色`() {
        // 直接看红蓝通道的差：偏暖的色，红会明显高于蓝。
        // 这样写不依赖任何"色板清单"，将来谁加一个暖色令牌，只要它进了面板材质就会被挡住。
        listOf(PanelBase, PanelShadowTint).forEach { c ->
            val r = (c.red * 255f).toInt()
            val b = (c.blue * 255f).toInt()
            assertTrue(
                "$c 偏暖（红 $r 比蓝 $b 高 ${r - b}）—— 用户明确要求面板不要暖色",
                r - b <= 12,
            )
        }
    }

    @Test
    fun `两种外壳圆角刻意不同 —— 用户要求同一材质但要有差异化`() {
        assertNotEquals(
            "输入区与底栏若圆角一样，差异化就只剩暖色位置一处了",
            InputPanelCorner,
            NavPanelCorner,
        )
    }

    /**
     * ⚠️ 用户 2026-09-28 第三次定调：「**必须有半透明的效果**」。
     *
     * （沿革：初版液态玻璃 → v0.33 白条 → v0.34 玻璃三要素 → v0.35 不透明卡片 →
     *  本版又要回半透明。**每一次都是他的明确要求**，这条断言记的是当前那一次。）
     *
     * 它钉的是"半透明"这件事**本身**：观感只能看真机，但"到底透不透"是量得出来的。
     * 两端都守：太透 → 浅色底上糊成一片、正文读不动（本项目栽过三次）；
     * 太实 → 根本看不出"透"，等于没做。
     */
    @Test
    fun `面板材质必须是半透明的`() {
        assertTrue("输入区面板不透明了 —— 用户要的是「半透明效果」", PANEL_ALPHA < 1f)
        assertTrue("底栏面板不透明了", NAV_PANEL_ALPHA < 1f)

        listOf(PANEL_ALPHA, NAV_PANEL_ALPHA).forEach { a ->
            assertTrue("$a 太透了 —— 浅色底上的正文会读不动", a >= 0.45f)
            assertTrue("$a 太实了 —— 看不出半透明，等于没做", a <= 0.85f)
        }
    }

    @Test
    fun `底栏比输入区实一点 —— 这是两处差异化的一半`() {
        assertTrue(
            "底栏常驻在不变的背景上、又承载图标文字，该比输入区实一点；" +
                "实际 输入区=$PANEL_ALPHA 底栏=$NAV_PANEL_ALPHA",
            NAV_PANEL_ALPHA > PANEL_ALPHA,
        )
    }
}
