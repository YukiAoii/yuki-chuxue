package ai.yuki.chuxue.data.room

import ai.yuki.chuxue.data.ChatMessage
import ai.yuki.chuxue.data.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Room 迁移的映射规格（开发文档 §13.3 / §13.4）。
 *
 * 迁移过程中「数据不丢」这件事，在无设备环境下唯一能钉住的就是这一层：
 * 领域对象经实体往返之后必须**逐字段还原**。往返测试不通过的迁移，
 * 在真机上就是「用户的历史被改了」。
 */
class SessionMapperTest {

    private val sample = Session(
        id = "s1",
        personaId = "p1",
        title = "小夏 · 阿雪",
        messages = listOf(
            ChatMessage("assistant", "你来了。"),
            ChatMessage("user", "嗯，今天有点累。"),
            ChatMessage("assistant", "那先歇会儿。"),
        ),
        createdAt = 1000L,
        updatedAt = 2000L,
        totalHit = 512,
        totalMiss = 64,
    )

    private fun roundTrip(s: Session): Session =
        SessionMapper.toDomain(
            SessionMapper.sessionToEntity(s),
            SessionMapper.messagesToEntities(s.id, s.messages),
        )

    /* ─────────── 往返 ─────────── */

    @Test
    fun `会话经实体往返后逐字段还原`() {
        val back = roundTrip(sample)
        assertEquals(sample.id, back.id)
        assertEquals(sample.personaId, back.personaId)
        assertEquals(sample.title, back.title)
        assertEquals(sample.createdAt, back.createdAt)
        assertEquals(sample.updatedAt, back.updatedAt)
        assertEquals(sample.totalHit, back.totalHit)
        assertEquals(sample.totalMiss, back.totalMiss)
        assertEquals(sample.messages.size, back.messages.size)
    }

    @Test
    fun `每条消息的角色与正文逐字还原`() {
        val back = roundTrip(sample)
        sample.messages.forEachIndexed { i, original ->
            assertEquals("第 $i 条的角色变了", original.role, back.messages[i].role)
            assertEquals("第 $i 条的正文变了", original.content, back.messages[i].content)
        }
    }

    /* ─────────── 消息时间戳（聊天窗口的时间分割条靠它）─────────── */

    @Test
    fun `消息时间戳往返保留`() {
        val s = sample.copy(
            messages = listOf(
                ChatMessage("user", "早", createdAt = 1_700_000_000_000L),
                ChatMessage("assistant", "早呀", createdAt = 1_700_000_060_000L),
            ),
        )
        val back = roundTrip(s)
        assertEquals(1_700_000_000_000L, back.messages[0].createdAt)
        assertEquals(1_700_000_060_000L, back.messages[1].createdAt)
    }

    @Test
    fun `时间未知的老消息原样保留 0 —— 界面据此不画分割条`() {
        val s = sample.copy(messages = listOf(ChatMessage("user", "很久以前的话")))
        assertEquals(
            "0 必须原样往返：界面靠它区分「时间未知」与「1970 年」",
            0L,
            roundTrip(s).messages[0].createdAt,
        )
    }

    @Test
    fun `消息乱序读回后仍按 seq 还原成原顺序`() {
        val entities = SessionMapper.messagesToEntities(sample.id, sample.messages)
        val shuffled = entities.reversed() // 模拟数据库不保证返回顺序
        val back = SessionMapper.toDomain(SessionMapper.sessionToEntity(sample), shuffled)
        assertEquals(
            sample.messages.map { it.content },
            back.messages.map { it.content },
        )
    }

    /* ─────────── 主键确定性（防重复插入）─────────── */

    @Test
    fun `messageId 是确定性的`() {
        assertEquals(SessionMapper.messageId("s1", 0), SessionMapper.messageId("s1", 0))
        assertEquals(SessionMapper.messageId("s1", 3), SessionMapper.messageId("s1", 3))
    }

    @Test
    fun `不同会话或不同序号的主键不碰撞`() {
        val ids = setOf(
            SessionMapper.messageId("s1", 0),
            SessionMapper.messageId("s1", 1),
            SessionMapper.messageId("s2", 0),
        )
        assertEquals("主键必须两两不同，否则整段覆写会覆盖掉别人的消息", 3, ids.size)
    }

    @Test
    fun `同一会话重复保存产生的实体 id 完全一致`() {
        val first = SessionMapper.messagesToEntities("s1", sample.messages)
        val second = SessionMapper.messagesToEntities("s1", sample.messages)
        assertEquals(first.map { it.id }, second.map { it.id })
    }

    /* ─────────── 图片字段 ─────────── */

    @Test
    fun `无图编码为空 JSON 数组而不是空串`() {
        assertEquals("[]", SessionMapper.encodeImages(emptyList()))
    }

    @Test
    fun `图片列表往返一致`() {
        val images = listOf("data:image/jpeg;base64,AAAA", "data:image/png;base64,BBBB")
        assertEquals(images, SessionMapper.decodeImages(SessionMapper.encodeImages(images)))
    }

    @Test
    fun `带图消息往返后图片仍在`() {
        val s = sample.copy(
            messages = listOf(ChatMessage("user", "看这个", images = listOf("data:image/jpeg;base64,ZZZ"))),
        )
        val back = roundTrip(s)
        assertEquals(1, back.messages[0].images.size)
        assertEquals("data:image/jpeg;base64,ZZZ", back.messages[0].images[0])
    }

    @Test
    fun `坏 JSON 的图片字段不抛异常_退化为空表`() {
        // 一条坏数据不该让整个会话读不出来
        assertTrue(SessionMapper.decodeImages("{不是数组").isEmpty())
        assertTrue(SessionMapper.decodeImages("").isEmpty())
        assertTrue(SessionMapper.decodeImages("null").isEmpty())
    }

    /* ─────────── 主键撞车：这就是「保存必须原子」的根据 ─────────── */

    /**
     * 把「索引式主键对错位极度敏感」这件事钉在这里。
     *
     * 真实事故（用户报「发出去的消息消失」）：`saveSession` 过去是**两次独立写入**，
     * 而 `observeSessions` 的 Flow 由 sessions 表驱动、却读 messages 表 ——
     * 中间态被观察到，本地状态就少了一条消息。**接下来的保存会把剩下的消息
     * 按新的下标重新编号**，于是 `会话id#N` 这个主键被一条**内容不同**的消息占用，
     * `@Upsert` 直接覆盖 —— 数据库里那条真实消息就没了。
     *
     * 所以这条测试不是描述理想行为，而是把**脆弱性**留证：
     * 若有人把 `SessionRepository.saveSession` 的事务或写入锁去掉，这里会提醒他代价是什么。
     *
     * ⚠️ 它测的是主键方案本身，**不是**事务的运行时行为 ——
     * 后者要跑真实 Room（本机无 Android 运行时，只能真机验证）。
     */
    @Test
    fun `本地状态错位时主键会撞车，同一个 id 下是另一条消息`() {
        val full = listOf(
            ChatMessage("user", "第一条"),
            ChatMessage("assistant", "第二条"),
            ChatMessage("user", "第三条"),
        )
        // 模拟竞态后的本地状态：中间那条丢了
        val missing = listOf(
            ChatMessage("user", "第一条"),
            ChatMessage("user", "第三条"),
        )

        val idFull = SessionMapper.messagesToEntities("s1", full).map { it.id }
        val idMissing = SessionMapper.messagesToEntities("s1", missing).map { it.id }

        assertEquals(
            "两条不同的消息算出了同一个主键 —— 覆盖就发生在这一点上",
            idFull[1], idMissing[1],
        )
        assertNotEquals(
            "同一个主键下内容却不同，upsert 会把真实的那条冲掉",
            full[1].content, missing[1].content,
        )
    }

    /* ─────────── 思考过程（reasoning）─────────── */

    @Test
    fun `思考过程往返保留`() {
        val s = sample.copy(
            messages = listOf(
                ChatMessage("assistant", "答案是 42", reasoning = "先看题目…再算一遍"),
            ),
        )
        assertEquals("先看题目…再算一遍", roundTrip(s).messages[0].reasoning)
    }

    @Test
    fun `没有思考时往返仍是 null —— 界面据此不画折叠块`() {
        val s = sample.copy(messages = listOf(ChatMessage("assistant", "嗯")))
        assertEquals(null, roundTrip(s).messages[0].reasoning)
    }

    @Test
    fun `思考过程不影响正文往返`() {
        val s = sample.copy(
            messages = listOf(ChatMessage("assistant", "正文", reasoning = "思考")),
        )
        val back = roundTrip(s)
        assertEquals("正文", back.messages[0].content)
        assertEquals("思考", back.messages[0].reasoning)
    }

    /* ─────────── 会话级设置（免打扰 / 置顶 / 背景 / 思考开关）─────────── */

    @Test
    fun `会话设置字段往返保留`() {
        val s = sample.copy(
            muted = true,
            pinned = true,
            background = "sakura",
            thinkingEnabled = false,
            reasoningEffort = "max",
        )
        val back = roundTrip(s)
        assertEquals(true, back.muted)
        assertEquals(true, back.pinned)
        assertEquals("sakura", back.background)
        assertEquals(false, back.thinkingEnabled)
        assertEquals("max", back.reasoningEffort)
    }

    @Test
    fun `默认值 = 不打扰、不置顶、无背景、思考开、强度 high`() {
        val back = roundTrip(sample)
        assertEquals(false, back.muted)
        assertEquals(false, back.pinned)
        assertEquals(null, back.background)
        assertEquals(true, back.thinkingEnabled)
        assertEquals("high", back.reasoningEffort)
    }
}


/**
 * **历史版本**（v0.61.11）的落库往返 —— 单独一个类，不打扰上面那份大 fixture。
 *
 * 重新生成会把旧回复推进 `superseded`（用户要求：「只是**本地留了**、不进缓存，
 * 仅供复制查看」）。这里钉的是**它真的落库了** —— 重启之后还能在气泡下方切回去看。
 */
class SessionMapperSupersededTest {

    private fun roundTrip(messages: List<ChatMessage>): List<ChatMessage> {
        val session = Session(id = "s1", personaId = "p1", title = "t", messages = messages)
        return SessionMapper.toDomain(
            SessionMapper.sessionToEntity(session),
            SessionMapper.messagesToEntities(session.id, session.messages),
        ).messages
    }

    @Test
    fun `历史版本往返保留 —— 重新生成留下的旧版不会丢`() {
        val back = roundTrip(
            listOf(
                ChatMessage(
                    role = "assistant",
                    content = "当前版本",
                    superseded = listOf("第一版", "第二版"),
                ),
            ),
        )
        assertEquals(listOf("第一版", "第二版"), back[0].superseded)
        assertEquals("当前版本", back[0].content)
    }

    @Test
    fun `没有历史版本时往返仍是空表 —— 老消息一个字节不受影响`() {
        val back = roundTrip(listOf(ChatMessage("assistant", "只有一版")))
        assertTrue(back[0].superseded.isEmpty())
    }
}
