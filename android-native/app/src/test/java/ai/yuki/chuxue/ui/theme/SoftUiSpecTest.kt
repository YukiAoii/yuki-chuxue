package ai.yuki.chuxue.ui.theme

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 轻拟物卡片（Soft UI）规范的契约测试 —— 与液态玻璃（Liquid Glass）一起，
 * 构成本项目的 UI 定调：**轻拟物卡片 + 液态玻璃组件**。
 *
 * ## 为什么这些数值值得被钉住
 * 「轻拟物」全靠几个小数成立：阴影稍重 → 从"轻浮"变成"实体挤出"；
 * 描边稍粗或稍浓 → 从"玉石薄边"变成"Material 描边框"；
 * 圆角不跟设计系统走 → 卡片之间开始长得不一样。这些参数**肉眼很难量化**，
 * 但一旦被改坏会一路歪下去，所以写成断言。
 *
 * ## ⚠️ 一次已记录的规范修正（不要改回去）
 * 本文件最初的版本断言「规范不包含任何 elevation」—— 那是我对早期口径的理解。
 * 用户随后给出精确参数：**卡片要有极浅阴影 `elevation = 2.dp`**，
 * 只是"不要做成实体的挤出"。本版按此修正：**允许阴影，但上限 2dp**。
 *
 * ⚠️ 这些测试测的是**规范数值**，不是渲染结果 —— 真机观感仍需用户确认（本机无设备）。
 */
class SoftUiSpecTest {

    /* ─────────── 圆角：16–24dp，且与设计系统同源 ─────────── */

    @Test
    fun `卡片圆角落在 16 到 24dp 区间`() {
        assertTrue(
            "圆角=${YukiCardSpec.CornerRadius}，应在 16–24dp（大圆角是轻拟物的前提）",
            YukiCardSpec.CornerRadius >= 16.dp && YukiCardSpec.CornerRadius <= 24.dp,
        )
    }

    @Test
    fun `卡片圆角取自设计系统的卡片值`() {
        assertEquals(
            "卡片圆角必须来自 Shape.kt，不能各画各的",
            CardCorner,
            YukiCardSpec.CornerRadius,
        )
    }

    /* ─────────── 阴影：极浅，上限 2dp ─────────── */

    @Test
    fun `阴影极浅 —— 不超过 2dp`() {
        assertTrue(
            "elevation=${YukiCardSpec.Elevation} 超过 2dp 就会变成「实体的挤出」",
            YukiCardSpec.Elevation <= 2.dp,
        )
    }

    @Test
    fun `阴影存在但不为零`() {
        assertTrue(
            "0dp = 完全没有浮起感，卡片会像贴在背景上的色块",
            YukiCardSpec.Elevation > 0.dp,
        )
    }

    /* ─────────── 描边：0.5dp 的极细边，这是「精致感」的关键 ─────────── */

    @Test
    fun `卡片不再有描边 —— 扁平风靠阴影分层`() {
        // ⚠️ 这条断言**被用户明确改写过**：0.5dp 描边是水彩/液态玻璃时代的规范；
        // 2026-09-27 用户定调「现代扁平卡片风」时要求
        // 「去掉生硬的灰色描边，改用极浅的柔和阴影」。
        // 所以这里改成断言 0 —— 不是"测试该改"，是规范本身换了。
        assertEquals(
            "扁平风不用描边；若有人要加回来，先看这条注释与 FlatCardThemeTest",
            0.dp,
            YukiCardSpec.BorderWidth,
        )
    }

    @Test
    fun `描边颜色为 0x0D1A2A3A`() {
        assertEquals(
            "用户指定的描边色：alpha 0x0D + 墨蓝 0x1A2A3A",
            0x0D1A2A3A,
            YukiCardSpec.BORDER_COLOR_ARGB,
        )
    }

    @Test
    fun `描边 alpha 极低 —— 它是「边界」不是「边框」`() {
        val alpha = ((YukiCardSpec.BORDER_COLOR_ARGB ushr 24) and 0xFF) / 255f
        assertTrue("alpha=$alpha 太高就会变成显眼的边框", alpha <= 0.08f)
        assertTrue("alpha 必须为正，否则描边不可见", alpha > 0f)
    }

    /* ─────────── 卡片是纯色（曾用过渐变，已移除）─────────── */

    /**
     * ⚠️ 这条断言是**反向**的：它钉住"卡片不再有渐变"。
     *
     * ## 历史：这里原有 4 条断言，全部**测不出**真正的问题
     * 它们钉的是 `GRADIENT_TOP_ALPHA = 0.92` / `GRADIENT_BOTTOM_ALPHA = 0.55`
     * 这组对角渐变（两端必须不同、自上而下变淡、alpha 落在开区间、差距够小）。
     * 四条全绿 —— 而那组参数的**实际渲染效果与设计意图相反**：
     * 在浅灰背景（`#F5F7FA`）上，卡片**右侧**解析成更透的 0.55 白，
     * 表现为**一条竖向色带**。用户的反馈是
     * 「很多按钮卡片的底部都有个白色的长方形的条，很丑」。
     *
     * 它们为什么测不出：**验证的是常量之间的关系（数值对不对），
     * 而缺陷出在"这组数值画出来是什么样"**。这是本项目第二次栽在同一类事情上
     * —— 第一次是液态玻璃的白色液滴在浅色底上隐形（几何与参数测试全绿，
     * 只有看真机才发现）。
     *
     * ## 所以现在用反射断言常量不存在
     * 有人手滑把渐变加回来 → 这条立刻红。比"再写四条数值断言"有用得多。
     */
    @Test
    fun `卡片不再使用渐变 —— 常量不许回来`() {
        val gradientFields = YukiCardSpec::class.java.declaredFields
            .map { it.name }
            .filter { it.contains("GRADIENT", ignoreCase = true) }
        assertTrue(
            "YukiCardSpec 里出现了渐变常量 $gradientFields —— " +
                "它会在浅底上画出卡片右侧的色带（见 YukiCard 的注释），不要再加回来",
            gradientFields.isEmpty(),
        )
    }
}
