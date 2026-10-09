package ai.yuki.chuxue.data

import ai.yuki.chuxue.data.room.MemoryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 记忆搜索的契约（v0.49.0）。
 *
 * ## ⚠️ 最要紧的一条：空关键词的行为与 `MessageSearch` **相反**
 * - 对话搜索：空词 → **空表**（否则一打开搜索页就刷出几百条）；
 * - 记忆搜索：空词 → **全部**（这一页本来就默认列全部，搜索框只是"过滤"）。
 *
 * 两个场景的正确行为不同，所以**不能**抽成一个共用函数 ——
 * 合并那天必然有一边变错。这条断言就是钉住这个分歧的。
 */
class MemorySearchTest {

    private fun mem(
        content: String,
        category: String = "喜好",
        id: String = content,
    ) = MemoryEntity(
        id = id,
        userId = MemoryEntity.LOCAL_USER_ID,
        personaId = "p1",
        sessionId = "s1",
        scope = MemoryEntity.SCOPE_SESSION,
        content = content,
        category = category,
        importance = 5,
        source = "manual",
        embedding = null,
        createdAt = 1000L,
        lastAccessedAt = 1000L,
        expiresAt = null,
    )

    private val all = listOf(
        mem("我喜欢在雨天听钢琴", "喜好"),
        mem("我们约定过一起去海边", "约定"),
        mem("用户最近在学做饭", "经历"),
        mem("She likes jazz music", "喜好"),
    )

    /* ─────────── 空关键词：与对话搜索相反 ─────────── */

    @Test
    fun `空关键词返回全部 —— 记忆页默认就是列全部`() {
        assertEquals(all, MemorySearch.query(all, ""))
        // 纯空白等价于空
        assertEquals(all, MemorySearch.query(all, "   "))
    }

    @Test
    fun `⚠️ 空关键词**不**返回空表 —— 这正是与 MessageSearch 的分歧点`() {
        // 显式对照：对话搜索空词返回空表（那边注释写了理由：一打开就刷几百条），
        // 记忆搜索空词必须返回全部。任何"统一两者"的重构都会让这条红。
        assertFalse(
            "记忆搜索空词不能返回空表（那是对话搜索的行为）",
            MemorySearch.query(all, "").isEmpty(),
        )
        assertEquals(all.size, MemorySearch.query(all, "").size)
    }

    /* ─────────── 正文匹配 ─────────── */

    @Test
    fun `按正文搜到`() {
        val hit = MemorySearch.query(all, "钢琴")
        assertEquals(1, hit.size)
        assertEquals("我喜欢在雨天听钢琴", hit.first().content)
    }

    @Test
    fun `多个命中都返回`() {
        // "我" 出现在两条正文里
        val hit = MemorySearch.query(all, "我")
        assertTrue("应当至少命中两条", hit.size >= 2)
    }

    @Test
    fun `搜不到时返回空表 —— 界面据此显示"没找到匹配的记忆"`() {
        assertTrue(MemorySearch.query(all, "量子力学").isEmpty())
    }

    /* ─────────── 分类匹配（容易被漏掉的一半） ─────────── */

    @Test
    fun `按分类搜到 —— 分类标签不能在搜索时形同虚设`() {
        val hit = MemorySearch.query(all, "约定")
        assertEquals(1, hit.size)
        assertEquals("我们约定过一起去海边", hit.first().content)
    }

    @Test
    fun `分类命中与正文命中是并集`() {
        // "喜好" 是两条的分类，但它们正文里都没有"喜好"两个字
        val hit = MemorySearch.query(all, "喜好")
        assertEquals(2, hit.size)
        assertTrue(hit.all { it.category == "喜好" })
    }

    /* ─────────── 大小写与顺序 ─────────── */

    @Test
    fun `大小写不敏感 —— 搜 jazz 与 Jazz 结果相同`() {
        assertEquals(
            MemorySearch.query(all, "jazz").map { it.id },
            MemorySearch.query(all, "Jazz").map { it.id },
        )
        assertEquals(1, MemorySearch.query(all, "JAZZ").size)
    }

    @Test
    fun `保持传入顺序 —— 搜索不该顺手改变排序`() {
        val hit = MemorySearch.query(all, "我")
        val expected = all.filter { it.content.contains("我") }.map { it.id }
        assertEquals("搜索是过滤，不是排序", expected, hit.map { it.id })
    }

    /* ─────────── 边界 ─────────── */

    @Test
    fun `空列表搜什么都不崩`() {
        assertEquals(emptyList<MemoryEntity>(), MemorySearch.query(emptyList(), "钢琴"))
        assertEquals(emptyList<MemoryEntity>(), MemorySearch.query(emptyList(), ""))
    }

    @Test
    fun `关键词两端空白被去掉`() {
        assertEquals(
            MemorySearch.query(all, "钢琴").map { it.id },
            MemorySearch.query(all, "  钢琴  ").map { it.id },
        )
    }

    @Test
    fun `matches 单独可用 —— 界面按条判断时不依赖整表`() {
        val m = mem("我喜欢在雨天听钢琴", "喜好")
        assertTrue(MemorySearch.matches(m, "钢琴"))
        assertTrue(MemorySearch.matches(m, "喜好"))
        assertFalse(MemorySearch.matches(m, "做饭"))
        // 空词对单条也算通过（与 query 的口径一致）
        assertTrue(MemorySearch.matches(m, ""))
    }
}
