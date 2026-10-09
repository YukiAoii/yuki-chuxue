package ai.yuki.chuxue.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 思考气泡「只留尾部」的规格。
 *
 * ## 为什么抽成纯函数
 * 用户的要求里有两条容易互相打架的：**只留最近几行**（不占屏）与
 * **不许让人以为"上面那些没了"**（要不丢信息感）。把这两条落到一个
 * "取尾部、并数出被省掉几行"的纯函数上，边界（刚好三行 / 第四行 / 空行 / 换行尾）
 * 才能逐条钉住；散在 Composable 里就只能靠肉眼看气泡。
 *
 * ⚠️ 它只管**渲染**：结果只上屏，不进 `messages`、不进请求体（缓存红线）。
 */
class ThinkingTailTest {

    private fun lines(n: Int): String = (1..n).joinToString("\n") { "第 $it 行" }

    @Test
    fun `少于等于上限时原样保留，不省略`() {
        val t = ThinkingTail.of(lines(3), liveLines = 3)
        assertEquals(0, t.omitted)
        assertEquals(3, t.totalLines)
        assertEquals(lines(3), t.text)
    }

    @Test
    fun `超出上限时只留尾部，并数出省掉的行数`() {
        val t = ThinkingTail.of(lines(10), liveLines = 3)
        assertEquals(7, t.omitted)
        assertEquals(10, t.totalLines)
        assertEquals("第 8 行\n第 9 行\n第 10 行", t.text)
    }

    @Test
    fun `恰好超出一行也只省一行 —— 边界不含糊`() {
        val t = ThinkingTail.of(lines(4), liveLines = 3)
        assertEquals(1, t.omitted)
        assertEquals("第 2 行\n第 3 行\n第 4 行", t.text)
    }

    @Test
    fun `尾部换行不会算成多出来的一行`() {
        val t = ThinkingTail.of(lines(4) + "\n", liveLines = 3)
        assertEquals(1, t.omitted)
        assertEquals(4, t.totalLines)
    }

    @Test
    fun `单行思考不会被省掉`() {
        val t = ThinkingTail.of("只有一行", liveLines = 3)
        assertEquals(0, t.omitted)
        assertEquals("只有一行", t.text)
    }

    @Test
    fun `行数统计忽略空行 —— 空行不该让"想了很多"显得很唬人`() {
        assertEquals(2, ThinkingTail.lineCount("甲\n\n乙\n   \n"))
    }

    @Test
    fun `行数统计对空串给 0`() {
        assertEquals(0, ThinkingTail.lineCount(""))
        assertEquals(0, ThinkingTail.lineCount("\n\n"))
    }

    @Test
    fun `行数统计在空行不算时仍与省略口径一致`() {
        val body = ThinkingTail.of(lines(5), liveLines = 3)
        assertTrue(body.omitted > 0)
        assertFalse(body.text.contains("第 1 行"))
    }
}
