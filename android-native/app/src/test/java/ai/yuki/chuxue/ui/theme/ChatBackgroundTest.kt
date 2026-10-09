package ai.yuki.chuxue.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 聊天背景预设的规格（对话设置页 →「聊天背景」）。
 *
 * ## 这个测试真正在守什么
 * 背景是**整套 UI 里唯一"大面积铺在气泡后面"的颜色**。项目为"浅底上白元素隐形"
 * 已经踩过两次坑（液态玻璃的白色液滴、低 alpha 胶囊），两次都是**参数核对与几何单测
 * 都发现不了、只有真机才暴露**的对比度问题。
 *
 * 本机没有设备，所以这里把那条经验换成**可计算的数值约束**：
 * 背景的每一个色标都必须足够浅（亮度下限），且与纯白**看得出差别**（差值下限）。
 * - 太深 → 白气泡、白顶栏沉进背景里；
 * - 与纯白无差别 → 用户换了背景却看不出换了，等于功能失效。
 *
 * 它证明不了"真机上好看"，但能挡住"一眼就是错的"那两类。
 */
class ChatBackgroundTest {

    /** sRGB 相对亮度的简写（0 = 黑，1 = 白）。只用来看"够不够浅"，不追求色度学精确。 */
    private fun luma(c: Color): Double =
        0.2126 * c.red + 0.7152 * c.green + 0.0722 * c.blue

    private val white = Color(0xFFFFFFFF)

    /* ══════════════ 解析 ══════════════ */

    @Test
    fun `null 表示跟随主题默认`() {
        assertEquals(ChatBackground.DEFAULT, ChatBackgrounds.of(null))
    }

    @Test
    fun `空串按默认处理`() {
        assertEquals(ChatBackground.DEFAULT, ChatBackgrounds.of(""))
    }

    @Test
    fun `已知标识解析成对应预设`() {
        assertEquals(ChatBackground.MIST, ChatBackgrounds.of(ChatBackground.MIST.id))
    }

    @Test
    fun `未知标识回落到默认而不是崩掉`() {
        // 老版本存下的标识、或将来被删掉的预设：宁可显示默认底，也不能打不开聊天页
        assertEquals(ChatBackground.DEFAULT, ChatBackgrounds.of("早已不存在的背景"))
    }

    /* ══════════════ 预设本身的形状 ══════════════ */

    @Test
    fun `默认是纯色而不是渐变`() {
        assertEquals(1, ChatBackground.DEFAULT.gradient.size)
    }

    @Test
    fun `其余预设都是两端渐变`() {
        ChatBackgrounds.presets
            .filter { it != ChatBackground.DEFAULT }
            .forEach { assertEquals(it.name, 2, it.gradient.size) }
    }

    @Test
    fun `只有默认的标识是空 —— 其余必须可持久化`() {
        assertTrue(ChatBackground.DEFAULT.id == null || ChatBackground.DEFAULT.id!!.isEmpty())
        ChatBackgrounds.presets
            .filter { it != ChatBackground.DEFAULT }
            .forEach { assertTrue("${it.name} 需要一个非空标识", !it.id.isNullOrEmpty()) }
    }

    @Test
    fun `标识与显示名各自唯一`() {
        val ids = ChatBackgrounds.presets.mapNotNull { it.id }
        assertEquals(ids.size, ids.toSet().size)
        val labels = ChatBackgrounds.presets.map { it.label }
        assertEquals(labels.size, labels.toSet().size)
    }

    @Test
    fun `预设都有显示名`() {
        ChatBackgrounds.presets.forEach { assertTrue(it.name, it.label.isNotBlank()) }
    }

    /* ══════════════ 对比度（无法真机验证 → 换成数值约束） ══════════════ */

    @Test
    fun `每个色标都足够浅 —— 白气泡与白顶栏才立得住`() {
        ChatBackgrounds.presets.forEach { bg ->
            bg.gradient.forEach { c ->
                assertTrue(
                    "${bg.label} 的色标太深了（亮度 ${luma(c)}）：白气泡会沉进背景里",
                    luma(c) >= 0.90,
                )
            }
        }
    }

    @Test
    fun `每个背景都与纯白看得出差别 —— 否则等于没换`() {
        ChatBackgrounds.presets.forEach { bg ->
            val maxGap = bg.gradient.maxOf { whiteGap(it) }
            assertTrue(
                "${bg.label} 与纯白几乎无差别（最大差 ${maxGap}）：用户会以为没生效",
                maxGap >= 0.02,
            )
        }
    }

    private fun whiteGap(c: Color): Double = Math.abs(luma(white) - luma(c))

    /* ══════════════ 自定义图片背景（一个字段要表达两种东西） ══════════════ */

    @Test
    fun `file 前缀的标识被认成自定义图片`() {
        assertTrue(ChatBackgrounds.isCustomImage("file:/data/x/bg.jpg"))
        assertFalse(ChatBackgrounds.isCustomImage("mist"))
        assertFalse("null 是默认，不是图片", ChatBackgrounds.isCustomImage(null))
    }

    @Test
    fun `路径与标识互为逆运算`() {
        val path = "/data/user/0/ai.yuki.chuxue/files/backgrounds/bg_1.jpg"
        assertEquals(path, ChatBackgrounds.customPathOf(ChatBackgrounds.customId(path)))
    }

    @Test
    fun `内置预设不会被误认成图片`() {
        ChatBackgrounds.presets.forEach {
            assertFalse(it.name, ChatBackgrounds.isCustomImage(it.id))
        }
    }

    @Test
    fun `自定义图片落不到任何预设 —— 分辨它只能靠 isCustomImage`() {
        // 这条钉的是一个容易踩空的地方：图片标识走 of() 会**静默**给出默认，
        // 若界面只用 of() 判断，用户设的图会被无声地换成默认底且没人发现
        assertEquals(ChatBackground.DEFAULT, ChatBackgrounds.of("file:/x/y.jpg"))
        assertTrue(ChatBackgrounds.isCustomImage("file:/x/y.jpg"))
    }

    @Test
    fun `描述文案三种情形各自正确`() {
        assertEquals(ChatBackground.DEFAULT.label, ChatBackgrounds.summaryOf(null))
        assertEquals(ChatBackground.MIST.label, ChatBackgrounds.summaryOf("mist"))
        assertEquals("自定义图片", ChatBackgrounds.summaryOf("file:/x/y.jpg"))
    }

    @Test
    fun `未知标识的描述回落到默认 —— 不显示一个空壳`() {
        assertEquals(ChatBackground.DEFAULT.label, ChatBackgrounds.summaryOf("早已删除的预设"))
    }

    @Test
    fun `内置标识都带上自己的名字 —— 不会与 file 前缀撞车`() {
        ChatBackgrounds.presets.mapNotNull { it.id }.forEach {
            assertFalse(it, it.startsWith(ChatBackgrounds.CUSTOM_PREFIX))
        }
    }
}
