package ai.yuki.chuxue.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「我发过的话」的规格（⑤-A 第 7 条，方案 A）。
 *
 * 打分口径照天枢的 `tui/history.ts`：**前缀命中 +10、query 里每个词命中 +5**；
 * 同分保序（表本身"越新越前"，所以同分时最近说过的排前面）。
 */
class SendHistoryTest {

    /* ─────────── 记 ─────────── */

    @Test
    fun `新的一条排到最前`() {
        assertEquals(listOf("b", "a"), SendHistory.record(listOf("a"), "b"))
    }

    @Test
    fun `空串与纯空白不记 —— 否则表里会混进一堆空行`() {
        assertEquals(listOf("a"), SendHistory.record(listOf("a"), ""))
        assertEquals(listOf("a"), SendHistory.record(listOf("a"), "   "))
    }

    @Test
    fun `已经在表里的挪到最前，而不是再堆一条`() {
        // 表头是"最新"，所以刚发的那条要挪到 index 0
        assertEquals(listOf("c", "a", "b"), SendHistory.record(listOf("a", "b", "c"), "c"))
    }

    @Test
    fun `就是最近一条时不产生变化 —— 免得状态流白刷一次`() {
        val h = listOf("a", "b")
        assertTrue("同一份对象就该原样返回", SendHistory.record(h, "a") === h)
    }

    @Test
    fun `超过上限就丢掉最旧的`() {
        // ⚠️ 方向别搞反：表头（index 0）是**最新**的，所以"第 1 句"是最新，
        //    "第 1000 句"才是最旧、该被挤掉的那条。
        val full = (1..SendHistory.MAX).map { "第 $it 句" }
        val next = SendHistory.record(full, "最新的")
        assertEquals(SendHistory.MAX, next.size)
        assertEquals("最新的", next.first())
        assertTrue("最旧的那条（末尾）该被挤掉", "第 ${SendHistory.MAX} 句" !in next)
        assertTrue("最新那几条要留着", "第 1 句" in next)
    }

    @Test
    fun `前后空白会被修掉`() {
        assertEquals(listOf("喂"), SendHistory.record(emptyList(), "  喂  "))
    }

    /* ─────────── 搜 ─────────── */

    @Test
    fun `空 query 就是最近说过的那几条`() {
        val h = listOf("a", "b", "c")
        assertEquals(listOf("a", "b"), SendHistory.search(h, "", limit = 2))
        assertEquals(listOf("a", "b", "c"), SendHistory.search(h, "   ", limit = 5))
    }

    @Test
    fun `包含才算命中`() {
        val h = listOf("今天天气不错", "明天要下雨", "天气冷了")
        // ⚠️ "天气冷了"**开头**就是"天气"（+10 再 +5），所以它排在只命中的那条前面
        assertEquals(listOf("天气冷了", "今天天气不错"), SendHistory.search(h, "天气"))
    }

    @Test
    fun `多词查询要同时含这些词（AND）—— 这正是参考实现没做到的那半截`() {
        val h = listOf("带伞", "今天下雨记得带伞", "记得带伞")
        // 只含"带伞"的两条被筛掉；三词全中（且两词各 +5）的那条留下
        assertEquals(listOf("今天下雨记得带伞"), SendHistory.search(h, "下雨 带伞"))
    }

    @Test
    fun `前缀命中的排在仅包含的前面（+10 对 +0）`() {
        val h = listOf("我想吃火锅", "火锅真好吃")  // 后者不以"火锅"开头
        assertEquals(listOf("火锅真好吃", "我想吃火锅"), SendHistory.search(h, "火锅"))
    }

    @Test
    fun `多词命中会叠加（每词 +5）`() {
        val h = listOf("带伞", "今天下雨记得带伞", "记得带伞")
        // "下雨 带伞"：第二条两词都中（+5+5）；另两条只中"带伞"（+5）且不以前缀开头
        assertEquals("今天下雨记得带伞", SendHistory.search(h, "下雨 带伞").first())
    }

    @Test
    fun `同分时最近说过的排前面`() {
        val h = listOf("新的带伞", "旧的带伞")
        assertEquals(listOf("新的带伞", "旧的带伞"), SendHistory.search(h, "带伞"))
    }

    @Test
    fun `大小写不敏感`() {
        assertEquals(listOf("Hello World"), SendHistory.search(listOf("Hello World"), "hello"))
    }

    @Test
    fun `只取前 limit 条`() {
        val h = (1..50).map { "同一句话 $it" }
        assertEquals(3, SendHistory.search(h, "同一句话", limit = 3).size)
    }

    /* ─────────── 存盘 ─────────── */

    @Test
    fun `编解码往返不丢东西`() {
        val h = listOf("第一句", "第二句", "带\"引号\"的")
        assertEquals(h, SendHistory.decode(SendHistory.encode(h)))
    }

    @Test
    fun `坏数据降级成空表，不抛`() {
        assertEquals(emptyList<String>(), SendHistory.decode("不是 JSON"))
        assertEquals(emptyList<String>(), SendHistory.decode(null))
        assertEquals(emptyList<String>(), SendHistory.decode(""))
    }

    @Test
    fun `空表编出来是空数组，读回来还是空表`() {
        assertEquals(emptyList<String>(), SendHistory.decode(SendHistory.encode(emptyList())))
    }
}
