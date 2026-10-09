package ai.yuki.chuxue.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「消息会不会凭空消失」这条不变量的守卫。
 *
 * 对应真实缺陷：聊天时用户发的消息偶尔消失（AI 回复了，气泡却没了）。
 * 两个成因里，这个文件守的是「旧快照覆盖新本地」那一半；
 * 另一半（两次 DB 写入之间被观察到）在 `SessionRepository.saveSession` 的事务里。
 */
class SessionMergeTest {

    private fun session(id: String, updatedAt: Long, text: String = "") = Session(
        id = id,
        personaId = "p1",
        title = id,
        messages = if (text.isEmpty()) emptyList() else listOf(ChatMessage("user", text)),
        createdAt = 0L,
        updatedAt = updatedAt,
    )

    @Test
    fun `数据库比本地新时采用数据库版本`() {
        val merged = SessionMerge.merge(
            fromDb = listOf(session("s1", 200, "来自数据库")),
            local = listOf(session("s1", 100, "本地旧值")),
        )
        assertEquals("来自数据库", merged[0].messages[0].content)
    }

    @Test
    fun `本地比数据库新时保留本地 —— 刚上屏的消息不被旧快照抹掉`() {
        val merged = SessionMerge.merge(
            // 稍旧的快照：数据库里还没有那条消息
            fromDb = listOf(session("s1", 100)),
            // 乐观更新：消息已上屏、保存尚未落盘
            local = listOf(session("s1", 200, "刚发出去的消息")),
        )
        assertEquals(1, merged[0].messages.size)
        assertEquals("刚发出去的消息", merged[0].messages[0].content)
    }

    @Test
    fun `本地没有的会话原样采用数据库版本`() {
        val merged = SessionMerge.merge(
            fromDb = listOf(session("s1", 100, "a"), session("s2", 100, "b")),
            local = listOf(session("s1", 50)),
        )
        assertEquals("合并的条数必须与数据库一致（本地有缺项不代表会话被删）", 2, merged.size)
        assertEquals("b", merged[1].messages[0].content)
    }

    @Test
    fun `时间戳相同时采用数据库版本 —— 不让本地旧值赖着不走`() {
        val merged = SessionMerge.merge(
            fromDb = listOf(session("s1", 100, "数据库")),
            local = listOf(session("s1", 100, "本地")),
        )
        assertEquals("数据库", merged[0].messages[0].content)
    }

    @Test
    fun `合并结果的条数与数据库一致`() {
        val merged = SessionMerge.merge(
            fromDb = listOf(session("s1", 1), session("s2", 2), session("s3", 3)),
            local = listOf(session("s1", 9)),
        )
        assertEquals("本地多出来的会话不该让列表变长", 3, merged.size)
    }

    @Test
    fun `两边都空时不崩`() {
        assertTrue(SessionMerge.merge(emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun `数据库为空而本地非空时返回空 —— 以数据库为准（会话可能已被删）`() {
        assertTrue(SessionMerge.merge(emptyList(), listOf(session("s1", 9))).isEmpty())
    }
}
