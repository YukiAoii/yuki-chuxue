package ai.yuki.chuxue.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话内搜索的规格（对话设置页 →「查找聊天记录」）。
 *
 * ## 这里钉住的两条不变量，都不只是"功能正确"
 *
 * 1. **搜索范围 = 用户看到的文本**
 *    历史里的用户消息带着后台注入的 `<appendix><memories>…</memories></appendix>`
 *    （`PromptEngine.plan()` 拼进去的，是**给模型看的**记忆）。如果搜索直接在原始
 *    content 上做，用户会被搜出一条自己从没说过的话 —— 而且那条"话"其实是她的记忆。
 *    所以搜索必须与气泡显示用同一套剥离逻辑（[TranscriptText.stripAppendix]）。
 *
 * 2. **高亮区间必须落在展示片段自己的坐标系里**
 *    [MessageSearch.query] 给的 snippet 可能带前后省略号，也可能是压缩过换行的。
 *    区间若按原文下标给出，UI 就会把高亮画到别的位置 —— 这种错**在真机上一眼看去
 *    只是"高亮有点歪"**，很容易被当成样式问题放过。所以下面用
 *    `snippet.substring(range)` 反查原词，把这条钉死。
 */
class MessageSearchTest {

    private fun msg(role: String, text: String) = ChatMessage(role, text)

    private val sample = listOf(
        msg("assistant", "早上好呀"),
        msg("user", "今天加班到很晚"),
        msg("assistant", "又加班？记得吃点东西"),
        msg("user", "嗯，先睡了"),
    )

    /* ══════════════ 关键词的边界 ══════════════ */

    @Test
    fun `关键词为空时返回空表`() {
        assertTrue(MessageSearch.query(sample, "").isEmpty())
    }

    @Test
    fun `关键词只有空白时返回空表`() {
        assertTrue(MessageSearch.query(sample, "   ").isEmpty())
    }

    @Test
    fun `没有任何命中时返回空表`() {
        assertTrue(MessageSearch.query(sample, "不存在的词").isEmpty())
    }

    /* ══════════════ 命中与定位 ══════════════ */

    @Test
    fun `命中的下标是它在消息列表里的真实位置`() {
        val hits = MessageSearch.query(sample, "很晚")
        assertEquals(1, hits.size)
        assertEquals(1, hits[0].index)
        assertEquals("user", hits[0].role)
    }

    @Test
    fun `命中区间指向片段里的那个词`() {
        val hits = MessageSearch.query(sample, "很晚")
        val hit = hits.single()
        val r = hit.ranges.single()
        assertEquals("很晚", hit.snippet.substring(r.first, r.last + 1))
    }

    @Test
    fun `同一句里出现多次时给出多个区间`() {
        val hits = MessageSearch.query(listOf(msg("user", "加班加班")), "加班")
        assertEquals(listOf(0..1, 2..3), hits.single().ranges)
    }

    @Test
    fun `多个区间互不重叠`() {
        val hits = MessageSearch.query(listOf(msg("user", "哈哈哈")), "哈哈")
        val ranges = hits.single().ranges
        ranges.zipWithNext().forEach { (a, b) ->
            assertTrue("相邻区间不能重叠：$a 与 $b", b.first > a.last)
        }
    }

    @Test
    fun `英文大小写不敏感`() {
        val hits = MessageSearch.query(listOf(msg("assistant", "Hello Yuki")), "yuki")
        val hit = hits.single()
        val r = hit.ranges.single()
        assertEquals("Yuki", hit.snippet.substring(r.first, r.last + 1))
    }

    @Test
    fun `命中按消息原有顺序排列`() {
        val hits = MessageSearch.query(sample, "加班")
        assertEquals(listOf(1, 2), hits.map { it.index })
    }

    /* ══════════════ 过滤范围 ══════════════ */

    @Test
    fun `只看我说的时她的话被排除`() {
        val hits = MessageSearch.query(sample, "加班", SearchScope.MINE)
        assertEquals(listOf(1), hits.map { it.index })
    }

    @Test
    fun `只看她说的时我的话被排除`() {
        val hits = MessageSearch.query(sample, "加班", SearchScope.HERS)
        assertEquals(listOf(2), hits.map { it.index })
    }

    @Test
    fun `默认范围是全部`() {
        assertEquals(
            MessageSearch.query(sample, "加班", SearchScope.ALL).map { it.index },
            MessageSearch.query(sample, "加班").map { it.index },
        )
    }

    /* ══════════════ 后台注入的附录（关键守卫） ══════════════ */

    @Test
    fun `后台注入的记忆不该被搜出来`() {
        val injected = msg(
            "user",
            "<appendix><memories>她记得你喜欢加班后吃泡面</memories></appendix>今天加班",
        )
        assertTrue(
            "附录是给模型看的，用户从没说过这句话",
            MessageSearch.query(listOf(injected), "泡面").isEmpty(),
        )
    }

    @Test
    fun `剥离附录后正文仍可被搜到`() {
        val injected = msg(
            "user",
            "<appendix><memories>她记得你喜欢加班后吃泡面</memories></appendix>今天加班",
        )
        val hits = MessageSearch.query(listOf(injected), "加班")
        assertEquals(1, hits.single().ranges.size)
        val r = hits.single().ranges.single()
        assertEquals("加班", hits.single().snippet.substring(r.first, r.last + 1))
    }

    /* ══════════════ 片段与坐标 ══════════════ */

    @Test
    fun `长消息的片段被截断并把区间平移到片段坐标系`() {
        val long = msg("user", "啊".repeat(40) + "关键词" + "啊".repeat(40))
        val hit = MessageSearch.query(listOf(long), "关键词").single()
        assertTrue("前面截断应当有省略号：${hit.snippet}", hit.snippet.startsWith("…"))
        assertTrue("后面截断应当有省略号：${hit.snippet}", hit.snippet.endsWith("…"))
        val r = hit.ranges.single()
        assertEquals("关键词", hit.snippet.substring(r.first, r.last + 1))
    }

    @Test
    fun `片段里的换行被压成空格且区间仍然对得上`() {
        val multi = msg("assistant", "第一行\n第二行里有加班\n第三行")
        val hit = MessageSearch.query(listOf(multi), "加班").single()
        assertFalse("单行展示不该留着换行", hit.snippet.contains('\n'))
        val r = hit.ranges.single()
        assertEquals("加班", hit.snippet.substring(r.first, r.last + 1))
    }

    @Test
    fun `片段不会超出原文长度`() {
        val short = msg("user", "加班")
        val hit = MessageSearch.query(listOf(short), "加班").single()
        assertEquals("加班", hit.snippet)
    }
}
