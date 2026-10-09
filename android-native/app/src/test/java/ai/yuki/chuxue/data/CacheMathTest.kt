package ai.yuki.chuxue.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 缓存计价机制的契约。
 *
 * ⚠️ 这一组测试的作用**不是**"证明实现对"，而是把两条实测结论钉住，
 * 免得将来有人"顺手简化"成看起来更整齐的公式：
 *
 * 1. 命中量是 **128 的整数倍**（块对齐）；
 * 2. **末尾那个完整块不计入** —— 漏掉这一条会让预期值永远大 128，
 *    在界面上表现为"永远比实际多命中一块"。
 *
 * 两条都是本项目用直连 API 对照实验测出来的（见 `CacheMath` 的 KDoc），
 * 不是从官方文档推的。改它们之前请先重做实验。
 */
class CacheMathTest {

    /* ─────────── 块对齐（实测结论 1） ─────────── */

    @Test
    fun `块粒度就是 128`() {
        // 这个数不是可调参数，是实测出来的服务端行为
        assertEquals(128, CacheMath.BLOCK)
    }

    @Test
    fun `命中量应当是 128 的整数倍`() {
        assertTrue(CacheMath.isBlockAligned(128))
        assertTrue(CacheMath.isBlockAligned(10624))   // 真机实测过的值
        assertTrue(CacheMath.isBlockAligned(512))
    }

    @Test
    fun `不是整块就不是合法命中量`() {
        assertFalse(CacheMath.isBlockAligned(100))
        assertFalse(CacheMath.isBlockAligned(127))
        assertFalse(CacheMath.isBlockAligned(129))
    }

    @Test
    fun `0 不算对齐 —— 它是没有命中，不是命中 0 块`() {
        assertFalse(CacheMath.isBlockAligned(0))
        assertFalse(CacheMath.isBlockAligned(-128))
    }

    @Test
    fun `块数按向下取整`() {
        assertEquals(0, CacheMath.blockCount(127))
        assertEquals(1, CacheMath.blockCount(128))
        assertEquals(1, CacheMath.blockCount(255))
        assertEquals(2, CacheMath.blockCount(256))
        assertEquals(85, CacheMath.blockCount(10968))   // 真机那一轮的输入
    }

    /* ─────────── 末尾块不计入（实测结论 2） ─────────── */

    @Test
    fun `283 只能命中 128 —— 交接文档里写死的那一组`() {
        // ⌊283/128⌋ = 2 块；末尾那块不计入 → (2-1)×128 = 128
        assertEquals(128, CacheMath.expectHitUpperBound(283))
    }

    @Test
    fun `不足两块时一块都命不中`() {
        // 只有 1 块（甚至不到 1 块）时，"末尾那块不计入"把唯一那块也扣掉了
        assertEquals(0, CacheMath.expectHitUpperBound(128))
        assertEquals(0, CacheMath.expectHitUpperBound(255))
        assertEquals(0, CacheMath.expectHitUpperBound(0))
    }

    @Test
    fun `刚好两块命中一块 —— 边界正是差额所在`() {
        assertEquals(128, CacheMath.expectHitUpperBound(256))
        assertEquals(128, CacheMath.expectHitUpperBound(383))
        assertEquals(256, CacheMath.expectHitUpperBound(384))
    }

    @Test
    fun `预期值永远比输入小一块 —— 少扣那一块是这里最容易写错的地方`() {
        for (n in listOf(283, 1000, 5510, 10968, 20000)) {
            val expect = CacheMath.expectHitUpperBound(n)
            val blocks = n / 128
            assertEquals("输入 $n 应命中 ${blocks - 1} 块", (blocks - 1) * 128, expect)
            assertTrue("预期命中不该超过输入本身", expect < n)
        }
    }

    /* ─────────── 命中率 ─────────── */

    @Test
    fun `命中率按已计费的部分算`() {
        assertEquals(0.5, CacheMath.hitRatio(100, 100), 1e-9)
        assertEquals(0.0, CacheMath.hitRatio(0, 500), 1e-9)
        assertEquals(1.0, CacheMath.hitRatio(500, 0), 1e-9)
    }

    @Test
    fun `没有请求时返回 0 而不是崩`() {
        assertEquals(0.0, CacheMath.hitRatio(0, 0), 1e-9)
        assertEquals(0.0, CacheMath.hitRatio(-5, -5), 1e-9)
    }

    /* ─────────── 短输入的解释 ─────────── */

    @Test
    fun `输入很小时给一句解释，说明这不是缓存失效`() {
        val note = CacheMath.shortInputNote(283)
        assertTrue("应提到 283：$note", note!!.contains("283"))
        assertTrue("应点明这不是失效：$note", note.contains("不是缓存失效"))
        assertTrue("应提到块：$note", note.contains("128"))
    }

    @Test
    fun `输入够大就不再解释 —— 免得每轮都说一遍废话`() {
        assertNull(CacheMath.shortInputNote(CacheMath.BLOCK * 4))
        assertNull(CacheMath.shortInputNote(10968))
        assertNull(CacheMath.shortInputNote(0))
    }

    /* ─────────── 由命中反推上一轮发出多少 ─────────── */

    @Test
    fun `命中 10624 反推出上一轮发了 84 块那一档`() {
        // 真机实测的那一组：命中 10624（83 块）→ 上一轮必须是 84 块
        val range = CacheMath.previousInputRange(10624)
        assertEquals(84 * 128, range!!.first)
        assertEquals(84 * 128 + 127, range.last)
        assertEquals(CacheMath.BLOCK, range.last - range.first + 1)
    }

    @Test
    fun `反推结果代回公式必须吻合 —— 自洽性检查`() {
        for (hit in listOf(128, 512, 10624, 20480)) {
            val range = CacheMath.previousInputRange(hit)!!
            // 区间里任何一个数代回"预期命中"，都应当等于这个命中量
            for (prev in listOf(range.first, range.first + 64, range.last)) {
                assertEquals("prev=$prev 应命中 $hit", hit, CacheMath.expectHitUpperBound(prev))
            }
        }
    }

    @Test
    fun `命中量不是整块时反推不出来 —— 宁可返回 null 也不要编一个数`() {
        assertNull(CacheMath.previousInputRange(100))
        assertNull(CacheMath.previousInputRange(0))
        assertNull(CacheMath.previousInputRange(-128))
    }
}
