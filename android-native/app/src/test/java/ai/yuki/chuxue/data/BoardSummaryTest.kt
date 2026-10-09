package ai.yuki.chuxue.data

import ai.yuki.chuxue.data.room.MemoryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 看板聚合口径的测试（v0.54.0）。
 *
 * ⚠️ 这一组钉的全是"**口径**"而不是"算术"：连续天数从哪天算、连着聊多久算断、
 * 什么才算一次"突变"、没测到的耗时算不算进平均。这些定义一旦漂移，
 * 看板上的数字会**看起来完全合理**但悄悄错掉 —— 那比崩溃更难发现。
 */
class BoardSummaryTest {

    private val DAY = 24 * 60 * 60 * 1000L
    private val MIN = 60 * 1000L

    /** 用一个固定的"现在"，避免测试依赖真实时钟。 */
    private val now = BoardMath.startOfDay(1_700_000_000_000L) + 12 * 60 * MIN // 当天中午

    private fun persona(id: String, name: String) = Persona(
        id = id,
        userNickname = "阿澈",
        userGender = "保密",
        customPrompt = "$name\n设定正文",
    )

    private fun msg(role: String, at: Long) = ChatMessage(role = role, content = "x", createdAt = at)

    private fun session(id: String, personaId: String, at: Long, n: Int) = Session(
        id = id,
        personaId = personaId,
        title = "对话$id",
        messages = (0 until n).map { msg(if (it % 2 == 0) "user" else "assistant", at + it * MIN) },
        createdAt = at,
        updatedAt = at + n * MIN,
    )

    /* ─────────── 连续天数 ─────────── */

    @Test
    fun `连续三天的消息 → 连续 3 天`() {
        val day = BoardMath.startOfDay(now)
        val times = listOf(day + MIN, day - DAY + MIN, day - 2 * DAY + MIN)
        assertEquals(3, BoardMath.streakDays(times, day))
    }

    @Test
    fun `今天没聊 → 0 —— 链条已经断了，不该从昨天算起`() {
        val day = BoardMath.startOfDay(now)
        val times = listOf(day - DAY + MIN, day - 2 * DAY + MIN)
        assertEquals(0, BoardMath.streakDays(times, day))
    }

    @Test
    fun `中间断过 → 只数到断点`() {
        val day = BoardMath.startOfDay(now)
        val times = listOf(day + MIN, day - DAY + MIN, day - 5 * DAY + MIN)
        assertEquals(2, BoardMath.streakDays(times, day))
    }

    @Test
    fun `没有消息 → 0`() {
        assertEquals(0, BoardMath.streakDays(emptyList(), BoardMath.startOfDay(now)))
    }

    /* ─────────── 连着聊多久 ─────────── */

    @Test
    fun `十分钟内来回十条 → 十分钟`() {
        val base = now - 3 * 60 * MIN
        val times = (0 until 10).map { base + it * MIN }
        assertEquals(9, BoardMath.longestChatMinutes(times))
    }

    @Test
    fun `隔夜的两段各算各的 —— 不把中间那十几个小时算进「聊了多久」`() {
        val first = listOf(now - 20 * 60 * MIN, now - 20 * 60 * MIN + 5 * MIN)   // 5 分钟
        val second = listOf(now - 2 * 60 * MIN, now - 2 * 60 * MIN + 3 * MIN)    // 3 分钟
        val longest = BoardMath.longestChatMinutes(first + second)
        // 取**最长的那一段**：第一段 5 分钟。
        // ⚠️ 不是两段相加（8 分钟），更不是从昨晚到现在的 20 小时 ——
        //    后者正是这个函数存在的理由。
        assertEquals(5, longest)
    }

    @Test
    fun `不足两条 → 0`() {
        assertEquals(0, BoardMath.longestChatMinutes(listOf(now)))
        assertEquals(0, BoardMath.longestChatMinutes(emptyList()))
    }

    /* ─────────── 突变 ─────────── */

    @Test
    fun `从高命中塌到几乎为零 → 算一次突变`() {
        val turns = listOf(
            BoardStats.TurnStat(1, hit = 90, miss = 10),  // 90%
            BoardStats.TurnStat(2, hit = 1, miss = 99),   // 1%
        )
        assertEquals(1, BoardMath.spikeCount(turns))
    }

    @Test
    fun `只是回落不算突变`() {
        val turns = listOf(
            BoardStats.TurnStat(1, hit = 90, miss = 10),  // 90%
            BoardStats.TurnStat(2, hit = 50, miss = 50),  // 50%
        )
        assertEquals(0, BoardMath.spikeCount(turns))
    }

    @Test
    fun `没有计费数据的那一轮不参与判定 —— 它是「没数据」不是「塌了」`() {
        val turns = listOf(
            BoardStats.TurnStat(1, hit = 90, miss = 10),
            BoardStats.TurnStat(2, hit = 0, miss = 0),    // 无数据
            BoardStats.TurnStat(3, hit = 90, miss = 10),
        )
        assertEquals(0, BoardMath.spikeCount(turns))
    }

    /* ─────────── 首字耗时 ─────────── */

    @Test
    fun `没测到的轮次不算进平均 —— 否则会把耗时算得比实际快`() {
        val turns = listOf(
            BoardStats.TurnStat(3, 1, 1, firstByteMs = 1000),
            BoardStats.TurnStat(2, 1, 1, firstByteMs = 0),     // 没测到
            BoardStats.TurnStat(1, 1, 1, firstByteMs = 3000),
        )
        val s = build(sessions = emptyList(), turns = turns)
        assertEquals("只平均 1000 与 3000", 2000L, s.avgFirstByteMs)
    }

    @Test
    fun `一次都没测到 → null 而不是 0`() {
        val turns = listOf(BoardStats.TurnStat(1, 1, 1, firstByteMs = 0))
        assertNull(build(sessions = emptyList(), turns = turns).avgFirstByteMs)
    }

    /* ─────────── 空数据不崩 ─────────── */

    @Test
    fun `全空输入也要给得出一份空看板`() {
        val s = build()
        assertEquals(0, s.todayMessages)
        assertNull("没计费过 → 命中率是 null", s.totalHitRatio)
        assertEquals(0, s.streakDays)
        assertNull(s.recentPersonaName)
        assertTrue(s.topSessions.isEmpty())
        assertTrue(s.latestMemories.isEmpty())
        assertEquals(0.0, s.avgImportance, 1e-9)
        assertEquals(0.0, s.savedYuan, 1e-9)
    }

    /* ─────────── 汇总口径 ─────────── */

    @Test
    fun `命中率与节省金额按累计值算`() {
        val sessions = listOf(
            session("s1", "p1", now - 2 * MIN, 2).copy(totalHit = 750_000, totalMiss = 250_000),
        )
        val s = build(sessions = sessions)
        assertEquals(0.75, s.totalHitRatio!!, 1e-9)
        // 75 万命中 × ¥1.5/百万 = ¥1.125
        assertEquals(1.125, s.savedYuan, 1e-9)
    }

    @Test
    fun `人设占比与最近常聊`() {
        val sessions = listOf(
            session("s1", "p1", now - 30 * MIN, 6),   // 初雪 6 条（最近）
            session("s2", "p2", now - 10 * DAY, 2),   // 另一位 2 条（不在一周内）
        )
        val s = build(
            sessions = sessions,
            personas = listOf(persona("p1", "初雪"), persona("p2", "阿岚")),
        )
        assertEquals(2, s.personaCount)
        assertEquals("只有 p1 在最近 7 天有消息", 1, s.activePersonaCount)
        assertEquals("初雪", s.recentPersonaName)
        assertEquals(0.75, s.personaShared.first().share, 1e-9)
        assertEquals("初雪", s.personaShared.first().name)
    }

    @Test
    fun `记忆的作用域分布与今日新增`() {
        val day = BoardMath.startOfDay(now)
        fun mem(id: String, scope: String, at: Long, imp: Int) = MemoryEntity(
            id = id, userId = MemoryEntity.LOCAL_USER_ID, personaId = "p1", sessionId = null,
            scope = scope, content = "内容$id", category = "c", importance = imp, source = "auto",
            embedding = null, createdAt = at, lastAccessedAt = at, expiresAt = null,
        )
        val s = build(
            memories = listOf(
                mem("m1", MemoryEntity.SCOPE_PERSONA, day + MIN, 10),
                mem("m2", MemoryEntity.SCOPE_SESSION, day + 2 * MIN, 4),
                mem("m3", MemoryEntity.SCOPE_SESSION, day - 3 * DAY, 6),
            ),
        )
        assertEquals(3, s.memoryCount)
        assertEquals(2, s.memoryByScope[MemoryEntity.SCOPE_SESSION])
        assertEquals("m1 与 m2 都在今天", 2, s.todayNewMemories)
        assertEquals(3, s.latestMemories.size)
        assertEquals((10 + 4 + 6) / 3.0, s.avgImportance, 1e-9)
    }

    /* ─────────── 转调 build ─────────── */

    private fun build(
        sessions: List<Session> = emptyList(),
        personas: List<Persona> = emptyList(),
        memories: List<MemoryEntity> = emptyList(),
        turns: List<BoardStats.TurnStat> = emptyList(),
    ) = BoardMath.build(
        sessions = sessions,
        personas = personas,
        memories = memories,
        turns = turns,
        modelUsage = emptyList(),
        now = now,
    )
}
