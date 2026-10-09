package ai.yuki.chuxue.data.memory

import ai.yuki.chuxue.data.room.MemoryDao
import ai.yuki.chuxue.data.room.afterManualEdit
import ai.yuki.chuxue.data.room.MemoryEntity
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 记忆写入与检索的规格（开发文档 §7.5 / §7.6 / §8）。
 *
 * 用内存版 [MemoryDao] 替代 Room —— 这里要钉的是**隔离规则与检索语义**这类纯逻辑，
 * 不是 SQLite 本身。真机上 Room 的行为由 schema JSON 逐字段核对覆盖（另见 `AppDatabase`）。
 */
private class FakeMemoryDao : MemoryDao {

    val rows = mutableListOf<MemoryEntity>()

    override suspend fun candidatesFor(
        userId: String,
        personaId: String,
        sessionId: String,
    ): List<MemoryEntity> = rows.filter {
        // ⚠️ 必须与真实 DAO 的 WHERE 一致（2026-10-05 起）：**人设级全部 + 本会话的会话级**。
        // fake 与真实实现一走偏，测试就等于替一个生产里不存在的语义背书。
        it.userId == userId && it.personaId == personaId &&
            (it.scope == MemoryEntity.SCOPE_PERSONA || it.sessionId == sessionId)
    }

    override suspend fun sameScope(
        userId: String,
        personaId: String,
        scope: String,
        sessionId: String?,
    ): List<MemoryEntity> {
        val snapshot = rows.filter {
            it.userId == userId && it.personaId == personaId && it.scope == scope &&
                (scope == MemoryEntity.SCOPE_PERSONA || it.sessionId == sessionId)
        }
        // ⚠️ 挂起点必须放在**读完之后**：真实的 DAO 查询会真的挂起，而"读完、还没写"
        //    这一段才是并发重复入库的窗口。
        //    放在读之前是**无效**的 —— 那样第二个协程的读会发生在第一个写之后，
        //    它自然看得见（我第一次就是这么写错的，撤掉锁测试照样绿 = 空转）。
        yield()
        return snapshot
    }

    override fun observeVisible(
        userId: String,
        personaId: String,
        sessionId: String,
    ): Flow<List<MemoryEntity>> = MutableStateFlow(
        rows.filter {
            // ⚠️ 与真实 DAO 的 WHERE 逐字对应：人设级全部 + 本会话的会话级（同 candidatesFor）。
            it.userId == userId && it.personaId == personaId &&
                (it.scope == MemoryEntity.SCOPE_PERSONA || it.sessionId == sessionId)
        },
    )

    override suspend fun upsert(memory: MemoryEntity) {
        rows.removeAll { it.id == memory.id }
        rows += memory
    }

    override suspend fun touch(ids: List<String>, at: Long) {
        for (i in rows.indices) {
            if (rows[i].id in ids) rows[i] = rows[i].copy(lastAccessedAt = at)
        }
    }

    override suspend fun delete(id: String) {
        rows.removeAll { it.id == id }
    }

    // ⚠️ v0.61.38 新增：本地记忆与**云端桶 id** 的映射（删除时要按它删云端那条）。
    //    假 DAO 必须跟着真 DAO 长 —— 漏一个 override 测试就编译不过（这次就是这么被逮住的）。
    override suspend fun setCloudBucketId(id: String, bucketId: String?) {
        for (i in rows.indices) {
            if (rows[i].id == id) rows[i] = rows[i].copy(cloudBucketId = bucketId)
        }
    }

    override suspend fun byId(id: String): MemoryEntity? = rows.firstOrNull { it.id == id }

    override suspend fun deleteByPersona(userId: String, personaId: String) {
        rows.removeAll { it.userId == userId && it.personaId == personaId }
    }

    /**
     * 「删掉这一轮自动记下的记忆」（v0.61.9）—— **WHERE 必须与真实 DAO 逐字对应**：
     * 本会话 + 指定来源（`auto_summary`）+ 创建时间不早于 `since`。
     * fake 一走偏，测试就等于替一个生产里不存在的语义背书（类注释里的纪律）。
     */
    override suspend fun deleteAutoSince(
        userId: String,
        sessionId: String,
        source: String,
        since: Long,
    ): Int {
        // ⚠️ `since > 0` 这条守卫**也必须复刻**（与真实 SQL 逐字对应）——
        //    否则单测会替一个"没有守卫的实现"背书，而真机上它会误删整会话的记忆。
        if (since <= 0L) return 0
        val hit = rows.filter {
            it.userId == userId && it.sessionId == sessionId &&
                it.source == source && it.createdAt >= since
        }
        rows.removeAll(hit)
        return hit.size
    }

    override suspend fun count(): Int = rows.size

    /** 备份导出用：仍是"这个角色的"全部记忆，只是不带 sessionId。 */
    override suspend fun allOfPersona(userId: String, personaId: String): List<MemoryEntity> =
        rows.filter { it.userId == userId && it.personaId == personaId }

    /** 迁移用：这个人设的全部会话级记忆（与真实 SQL 一致带上两道强制过滤）。 */
    override suspend fun allSessionOfPersona(userId: String, personaId: String): List<MemoryEntity> =
        rows.filter {
            it.userId == userId && it.personaId == personaId &&
                it.scope == MemoryEntity.SCOPE_SESSION
        }

    /**
     * 旧记忆计数（与真实 SQL 逐字对应：这个人设的会话级、且**不属于当前会话**）—— 迁移入口的显示判据。
     *
     * ⚠️ 排除当前会话是关键：本会话新产生的会话级记忆在本会话里看得见（没丢），
     * 不该被叫成"旧记忆"——用户报的"新开对话也提示旧会话"就是这个口径漏了当前会话。
     */
    override fun observeSessionCountOfPersona(
        userId: String,
        personaId: String,
        excludeSessionId: String,
    ): Flow<Int> =
        MutableStateFlow(
            rows.count {
                it.userId == userId && it.personaId == personaId &&
                    it.scope == MemoryEntity.SCOPE_SESSION &&
                    it.sessionId != excludeSessionId
            },
        )

    /** 数据恢复用（清空后由调用方在**同一个事务**里写回）。 */
    override suspend fun deleteAll() {
        rows.clear()
    }
}

class MemoryWriterTest {

    private val dao = FakeMemoryDao()

    private val PERSONA = "p1"
    private val SESSION = "s1"
    private val writer = MemoryWriter(dao)
    private val now = 1_700_000_000_000L

    /* ─────────── 校验：写入是隔离的唯一入口 ─────────── */

    /* ═════════ v0.61.21：用户手改过的记忆必须被保护（第二轮体检捞出来的）═════════ */

    @Test
    fun `合并时不许盖掉用户手改过的正文`() = runBlocking {
        // ⚠️ 文本对必须真的够相似（短句被长句完整包含），否则走的是"新增一条"而不是"合并" ——
        //    那这个测试就变成在验一件不相干的事。相似对的口径见同文件既有的合并测试。
        // ① 后台自动记下一条
        val auto = writer.write(
            PERSONA, MemoryEntity.SCOPE_SESSION, "用户喜欢猫", "喜好", 5,
            MemoryEntity.SOURCE_AUTO, sessionId = SESSION, now = 100L,
        )
        // ② 用户在记忆页把它改成自己的说法（编辑后来源变成 manual —— 见 afterManualEdit）
        dao.upsert(
            auto.afterManualEdit(
                content = "用户喜欢猫和狗，尤其是橘猫",
                category = "喜好",
                importance = 5,
            ),
        )
        // ③ 后台又提取到一条更重要的近似表述
        val again = writer.write(
            PERSONA, MemoryEntity.SCOPE_SESSION, "用户喜欢猫和狗", "喜好", 9,
            MemoryEntity.SOURCE_AUTO, sessionId = SESSION, now = 200L,
        )

        assertEquals("用户手改过的正文一个字都不许动", "用户喜欢猫和狗，尤其是橘猫", again.content)
        assertEquals("还是同一条（合并，不是新增）", 1, dao.rows.size)
        assertEquals("重要性照样可以抬", 9, again.importance)
    }

    @Test
    fun `没有手改过的条目，合并行为照旧`() = runBlocking {
        writer.write(
            PERSONA, MemoryEntity.SCOPE_SESSION, "用户喜欢猫", "喜好", 5,
            MemoryEntity.SOURCE_AUTO, sessionId = SESSION, now = 100L,
        )
        val again = writer.write(
            PERSONA, MemoryEntity.SCOPE_SESSION, "用户喜欢猫和狗", "喜好", 9,
            MemoryEntity.SOURCE_AUTO, sessionId = SESSION, now = 200L,
        )
        assertEquals("没人手改过，就该按原来的规则用新表述", "用户喜欢猫和狗", again.content)
        assertEquals(1, dao.rows.size)
    }

    @Test
    fun `手改过的自动记忆，不会被「删掉这一轮」连带删掉`() = runBlocking {
        val auto = writer.write(
            PERSONA, MemoryEntity.SCOPE_SESSION, "用户喜欢猫", "喜好", 5,
            MemoryEntity.SOURCE_AUTO, sessionId = SESSION, now = 100L,
        )
        writer.write(
            PERSONA, MemoryEntity.SCOPE_SESSION, "今天很累", "经历", 5,
            MemoryEntity.SOURCE_AUTO, sessionId = SESSION, now = 110L,
        )
        // 用户手改其中一条 —— 它就不再是"自动提取的"了
        dao.upsert(
            auto.afterManualEdit(content = "用户喜欢猫和狗", category = "喜好", importance = 6),
        )

        val deleted = dao.deleteAutoSince(
            userId = MemoryEntity.LOCAL_USER_ID,
            sessionId = SESSION,
            source = MemoryEntity.SOURCE_AUTO,
            since = 50L,
        )

        assertEquals("只该删掉没被动过的那条", 1, deleted)
        assertEquals(1, dao.rows.size)
        assertEquals("手改过的必须活着", auto.id, dao.rows.single().id)
    }

    @Test
    fun `并发写同一条近似记忆 —— 只留一条，不许重复入库`() = runBlocking {
        // ⚠️ 这个测试要的是"读-判-写三步之间被插队"的窗口。单线程调度器下协程只在
        //    挂起点交错，所以 fake 的 sameScope 里刻意 yield 一下（模拟真实 DAO 那次
        //    真会挂起的查询）—— 没有它，这个测试会在"没有锁"的实现上假绿。
        val a = async {
            writer.write(
                PERSONA, MemoryEntity.SCOPE_SESSION, "喜欢猫", "喜好", 5,
                MemoryEntity.SOURCE_AUTO, sessionId = SESSION, now = 100L,
            )
        }
        val b = async {
            writer.write(
                PERSONA, MemoryEntity.SCOPE_SESSION, "喜欢猫", "喜好", 5,
                MemoryEntity.SOURCE_AUTO, sessionId = SESSION, now = 101L,
            )
        }
        a.await()
        b.await()

        assertEquals("同一条记忆被并发写两次，只能留一条", 1, dao.rows.size)
    }

    @Test
    fun `session 作用域必须带 sessionId`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                writer.write("p1", MemoryEntity.SCOPE_SESSION, "今晚要加班", "其他", 5, "manual")
            }
        }
        assertTrue(e.message!!.contains("sessionId"))
    }

    @Test
    fun `非法 scope 被拒`() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { writer.write("p1", "global", "内容", "其他", 5, "manual") }
        }
    }

    @Test
    fun `空内容被拒`() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { writer.write("p1", MemoryEntity.SCOPE_PERSONA, "   ", "其他", 5, "manual") }
        }
    }

    @Test
    fun `重要性越界被夹紧而不是写入失败`() {
        val high = runBlocking {
            writer.write("p1", MemoryEntity.SCOPE_PERSONA, "非常重要的事", "其他", 99, "manual", now = now)
        }
        assertEquals(MemoryWriter.MAX_IMPORTANCE, high.importance)

        val low = runBlocking {
            writer.write("p1", MemoryEntity.SCOPE_PERSONA, "无关紧要", "其他", -5, "manual", now = now)
        }
        assertEquals(MemoryWriter.MIN_IMPORTANCE, low.importance)
    }

    /* ─────────── 作用域归属 ─────────── */

    @Test
    fun `persona 级记忆不携带 sessionId`() {
        val m = runBlocking {
            writer.write(
                "p1", MemoryEntity.SCOPE_PERSONA, "用户喜欢猫",
                "喜好", 5, "manual", sessionId = "s1", now = now,
            )
        }
        assertNull("persona 级属于角色而非某次对话，不该带 sessionId", m.sessionId)
    }

    @Test
    fun `session 级记忆记录 sessionId`() {
        val m = runBlocking {
            writer.write(
                "p1", MemoryEntity.SCOPE_SESSION, "今天说好周末去看展",
                "约定", 5, "manual", sessionId = "s1", now = now,
            )
        }
        assertEquals("s1", m.sessionId)
    }

    /* ─────────── 去重（文档 §7.6 / §8.2）─────────── */

    @Test
    fun `同作用域内的近似记忆合并而非新增`() {
        runBlocking {
            writer.write("p1", MemoryEntity.SCOPE_PERSONA, "用户喜欢猫", "喜好", 5, "manual", now = now)
            val second = writer.write(
                "p1", MemoryEntity.SCOPE_PERSONA, "用户喜欢猫",
                "喜好", 7, "manual", now = now + 1000,
            )
            assertEquals("重复提取不该让记忆膨胀", 1, dao.rows.size)
            assertEquals("重要性取较大值", 7, second.importance)
        }
    }

    @Test
    fun `合并时保留重要性更高的那一份表述`() {
        runBlocking {
            writer.write("p1", MemoryEntity.SCOPE_PERSONA, "用户喜欢猫", "喜好", 8, "manual", now = now)
            // 相似度够高（公共片段「用户喜欢猫」占满较短一方），但重要性更低
            val merged = writer.write(
                "p1", MemoryEntity.SCOPE_PERSONA, "用户喜欢猫和狗",
                "喜好", 2, "manual", now = now,
            )
            assertEquals("低重要性的新表述不该覆盖高重要性的旧表述", "用户喜欢猫", merged.content)
            assertEquals(8, merged.importance)
        }
    }

    @Test
    fun `表述接近但确实不同的事不合并`() {
        runBlocking {
            writer.write("p1", MemoryEntity.SCOPE_PERSONA, "用户喜欢猫", "喜好", 5, "manual", now = now)
            writer.write("p1", MemoryEntity.SCOPE_PERSONA, "用户喜欢狗", "喜好", 5, "manual", now = now)
            assertEquals("「喜欢猫」与「喜欢狗」是两件事，不能合并", 2, dao.rows.size)
        }
    }

    @Test
    fun `跨作用域不合并`() {
        runBlocking {
            writer.write("p1", MemoryEntity.SCOPE_PERSONA, "用户喜欢猫", "喜好", 5, "manual", now = now)
            writer.write(
                "p1", MemoryEntity.SCOPE_SESSION, "用户喜欢猫",
                "喜好", 5, "manual", sessionId = "s1", now = now,
            )
            assertEquals(
                "一条说给所有会话、一条只属于本次对话，不能互相合并",
                2, dao.rows.size,
            )
        }
    }

    @Test
    fun `不同会话的 session 级记忆互不合并`() {
        runBlocking {
            writer.write("p1", MemoryEntity.SCOPE_SESSION, "用户喜欢猫", "喜好", 5, "manual", sessionId = "s1", now = now)
            writer.write("p1", MemoryEntity.SCOPE_SESSION, "用户喜欢猫", "喜好", 5, "manual", sessionId = "s2", now = now)
            assertEquals(2, dao.rows.size)
        }
    }

    @Test
    fun `不同角色的同名记忆互不合并`() {
        runBlocking {
            writer.write("p1", MemoryEntity.SCOPE_PERSONA, "用户喜欢猫", "喜好", 5, "manual", now = now)
            writer.write("p2", MemoryEntity.SCOPE_PERSONA, "用户喜欢猫", "喜好", 5, "manual", now = now)
            assertEquals("隔离第二层：personaId 不同就是两条独立记忆", 2, dao.rows.size)
        }
    }
}

class MemoryRetrieverTest {

    private val dao = FakeMemoryDao()
    private val retriever = MemoryRetriever(dao)
    private val now = 1_700_000_000_000L
    private val day = 86_400_000L

    /**
     * 造一条记忆。
     *
     * ⚠️ 2026-09-28：默认 scope 从 persona 改成 **session**、默认挂在 `s1`
     * （与 [recall] 的默认会话一致）—— 记忆的归属已改成「对话」，
     * 而 [recall] 只查本会话。默认值不改的话，每个用例都会种进去一条
     * **检索不到**的记忆，整组测试会集体假红。
     */
    private fun seed(
        content: String,
        personaId: String = "p1",
        scope: String = MemoryEntity.SCOPE_SESSION,
        sessionId: String? = "s1",
        importance: Int = 5,
        lastAccessedAt: Long = now,
        id: String = content,
    ) {
        dao.rows += MemoryEntity(
            id = id,
            userId = MemoryEntity.LOCAL_USER_ID,
            personaId = personaId,
            sessionId = if (scope == MemoryEntity.SCOPE_SESSION) sessionId else null,
            scope = scope,
            content = content,
            category = "其他",
            importance = importance,
            source = "manual",
            embedding = null,
            createdAt = lastAccessedAt,
            lastAccessedAt = lastAccessedAt,
            expiresAt = null,
        )
    }

    private fun recall(input: String, sessionId: String = "s1", max: Int = 5) = runBlocking {
        retriever.retrieve(
            personaId = "p1", sessionId = sessionId, userInput = input,
            maxResults = max, now = now,
        )
    }

    /* ─────────── 相关性 ─────────── */

    @Test
    fun `毫不相干的记忆不被注入`() {
        seed("用户喜欢猫")
        assertTrue("宁可少注入，也不花 token 塞无关记忆", recall("今天股市怎么样").isEmpty())
    }

    @Test
    fun `相关的记忆被注入`() {
        seed("用户经常加班")
        val hit = recall("我今天又要加班了")
        assertEquals(1, hit.size)
        assertEquals("用户经常加班", hit[0].content)
    }

    @Test
    fun `纯空白输入不触发检索`() {
        seed("用户经常加班")
        assertTrue(recall("   ").isEmpty())
    }

    /* ─────────── 排序 ─────────── */

    @Test
    fun `同等相关时重要性高的排前面`() {
        // 两条与 query 的公共片段同为「用户喜欢」、长度也相同 → 相关度相等，
        // 排名差异就只可能来自重要性
        seed("用户喜欢咖啡", importance = 3, id = "low")
        seed("用户喜欢奶茶", importance = 9, id = "high")
        val hit = recall("用户喜欢什么")
        assertEquals("high", hit[0].id)
    }

    @Test
    fun `同等重要性时久未访问的排后面`() {
        seed("用户喜欢咖啡", importance = 5, lastAccessedAt = now, id = "fresh")
        seed("用户喜欢奶茶", importance = 5, lastAccessedAt = now - 180 * day, id = "stale")
        val hit = recall("用户喜欢什么")
        assertEquals("常被想起的应当更靠前", "fresh", hit[0].id)
    }

    @Test
    fun `结果不超过上限`() {
        repeat(8) { seed("用户喜欢第 $it 种咖啡", id = "m$it") }
        assertEquals(5, recall("用户喜欢咖啡").size)
        assertEquals(3, recall("用户喜欢咖啡", max = 3).size)
    }

    /* ─────────── 三层隔离（文档 §8.2）─────────── */

    @Test
    fun `别的角色的记忆检索不到`() {
        seed("用户喜欢猫", personaId = "p2")
        assertTrue(recall("用户喜欢猫").isEmpty())
    }

    @Test
    fun `session 级记忆只在所属会话可见`() {
        seed("今天说好周末去看展", scope = MemoryEntity.SCOPE_SESSION, sessionId = "s1")
        assertEquals("在本会话里应当可见", 1, recall("周末去看展", sessionId = "s1").size)
        assertTrue("换个会话就不该看见", recall("周末去看展", sessionId = "s2").isEmpty())
    }

    @Test
    fun `同一人设另开一段对话看不到本会话的记忆`() {
        // ⚠️ 2026-09-28 语义反转：这里原来是「persona 级记忆跨会话可见」。
        // 用户要求「记忆只存在于当前对话，同一人设新开对话不共享」，
        // 这条断言整个反了过来 —— 它现在是一道**防回退闸**：
        // 谁把跨会话可见性加回来，它会立刻红。
        seed("用户喜欢猫")
        assertEquals("本会话里应当看得见", 1, recall("用户喜欢猫", sessionId = "s1").size)
        assertTrue(
            "同一人设另开一段对话，不该带着上一段的记忆",
            recall("用户喜欢猫", sessionId = "s9").isEmpty(),
        )
    }

    /* ─────────── 命中即刷新 ─────────── */

    @Test
    fun `命中后刷新访问时间`() {
        seed("用户经常加班", lastAccessedAt = now - 100 * day, id = "m1")
        recall("我今天又要加班了")
        assertEquals(
            "被想起的记忆应该掉得更慢（衰减按 lastAccessedAt）",
            now,
            dao.rows.first { it.id == "m1" }.lastAccessedAt,
        )
    }

    @Test
    fun `未命中的记忆不被刷新`() {
        seed("用户喜欢猫", lastAccessedAt = now - 100 * day, id = "m1")
        recall("我今天又要加班了")
        assertEquals(now - 100 * day, dao.rows.first { it.id == "m1" }.lastAccessedAt)
    }
}

/** 删人设时记忆必须一起清 —— 人设不在 Room，没有外键可级联（开发文档 §8.2）。 */
class MemoryPersonaCleanupTest {

    @Test
    fun `按角色删除只清掉该角色的记忆`() {
        val dao = FakeMemoryDao()
        runBlocking {
            val writer = MemoryWriter(dao)
            writer.write("p1", MemoryEntity.SCOPE_PERSONA, "用户喜欢猫", "喜好", 5, "manual")
            writer.write("p2", MemoryEntity.SCOPE_PERSONA, "用户喜欢狗", "喜好", 5, "manual")
            assertNotNull(dao.rows.firstOrNull { it.personaId == "p1" })

            dao.deleteByPersona(MemoryEntity.LOCAL_USER_ID, "p1")

            assertNull("p1 的记忆应当被清空", dao.rows.firstOrNull { it.personaId == "p1" })
            assertNotNull("p2 的记忆不该被殃及", dao.rows.firstOrNull { it.personaId == "p2" })
        }
    }
}

/**
 * 「删除最新一轮时一并删掉这一轮自动记下的记忆」（v0.61.9）的规格。
 *
 * 用户要求：「被自动保存的记忆内容也要一并删除（**没有自动保存就不动记忆**）」。
 *
 * ⚠️ 记忆条目**没有 messageId** —— 归属只能按「会话 + 来源 + 时间」框。
 * 所以这里钉的就是那条边界：**手动写的、别的会话的、更早的**记忆一条都不能被误伤。
 */
class MemoryDeleteSinceTest {

    private val dao = FakeMemoryDao()

    private fun mem(
        id: String,
        sessionId: String?,
        source: String,
        createdAt: Long,
    ) = MemoryEntity(
        id = id,
        userId = MemoryEntity.LOCAL_USER_ID,
        personaId = "p1",
        sessionId = sessionId,
        scope = MemoryEntity.SCOPE_SESSION,
        content = "内容 $id",
        category = "其他",
        importance = 5,
        source = source,
        embedding = null,
        createdAt = createdAt,
        lastAccessedAt = createdAt,
        expiresAt = null,
    )

    @Test
    fun `只删这一轮之后、自动来源、本会话的记忆`() = runBlocking {
        dao.upsert(mem("a", "s1", "auto_summary", 1000L)) // 这一轮之前 → 不动
        dao.upsert(mem("b", "s1", "auto_summary", 2000L)) // 这一轮之后 → 该删
        dao.upsert(mem("c", "s1", "manual", 3000L))       // 手动写的 → 不动
        dao.upsert(mem("d", "s2", "auto_summary", 3000L)) // 别的会话 → 不动

        val removed = dao.deleteAutoSince(
            MemoryEntity.LOCAL_USER_ID,
            sessionId = "s1",
            source = "auto_summary",
            since = 1500L,
        )

        assertEquals(1, removed)
        assertEquals(listOf("a", "c", "d"), dao.rows.map { it.id }.sorted())
    }

    @Test
    fun `没有自动保存时一条都不动 —— 「没有自动保存就不动记忆」`() = runBlocking {
        // ⚠️ since 必须用**有效值**：写成 0 会被"since 不可信"的守卫提前拦掉，
        //    那样测到的是守卫、不是 source 过滤（审查指出的假绿灯）。
        dao.upsert(mem("a", "s1", "manual", 9999L))
        assertEquals(
            0,
            dao.deleteAutoSince(MemoryEntity.LOCAL_USER_ID, "s1", "auto_summary", since = 5000L),
        )
        assertEquals(1, dao.rows.size)
    }

    @Test
    fun `since 不可信时一条都不删 —— 删一轮不能清空整个会话的记忆`() = runBlocking {
        // ⚠️ 这条的来历（审查捞出来的真缺陷）：调用点对 since 有 `?: 0L` 兜底，
        //    而**加时间戳之前的老消息 `createdAt` 就是 0** ——
        //    `createdAt >= 0` 恒真 → 删一轮会把这个会话的自动记忆**全部**清掉。
        //    这条断言在修复前必然 RED（它就是那张红灯）。
        dao.upsert(mem("a", "s1", "auto_summary", 1000L))
        dao.upsert(mem("b", "s1", "auto_summary", 2000L))
        assertEquals(
            "since <= 0 只说明时间不可信，不该删任何条目",
            0,
            dao.deleteAutoSince(MemoryEntity.LOCAL_USER_ID, "s1", "auto_summary", since = 0L),
        )
        assertEquals(2, dao.rows.size)
    }
}

/**
 * 记忆作用域规格 —— 2026-10-05 用户拍板：默认**人设级**、保留会话级分流、可一键迁移。
 *
 * ⚠️ 这组用例替换 2026-09-28 的"记忆只存在于当前对话"规格 —— 那次是用户要求全部会话化，
 *    这一次同样是用户要求改回人设级为主。**两次都是需求方说了算**，沿革写在这里是为了
 *    下一位读者不会再把其中一次当成 bug 修掉。
 */
class MemoryScopeTest {

    private val dao = FakeMemoryDao()
    private val repo = MemoryRepository(dao)
    private val PERSONA = "p1"
    private val S1 = "s1"
    private val S2 = "s2"

    private fun mem(
        id: String,
        scope: String,
        sessionId: String?,
        personaId: String = PERSONA,
    ): MemoryEntity = MemoryEntity(
        id = id,
        userId = MemoryEntity.LOCAL_USER_ID,
        personaId = personaId,
        sessionId = sessionId,
        scope = scope,
        content = "内容$id",
        category = "其他",
        importance = 5,
        source = MemoryEntity.SOURCE_AUTO,
        embedding = null,
        createdAt = 1L,
        lastAccessedAt = 1L,
        expiresAt = null,
    )

    /* ─────────── 写入默认值：人设级 ─────────── */

    @Test
    fun `remember 默认写成人设级_不带会话归属`() = runBlocking {
        val saved = repo.remember(personaId = PERSONA, sessionId = S1, content = "用户喜欢猫")
        assertEquals(MemoryEntity.SCOPE_PERSONA, saved.scope)
        assertNull("人设级记忆不带 sessionId —— 否则删会话会把它连带删掉", saved.sessionId)
        // 且它跨会话可见（换一个会话也查得到）
        assertEquals(1, dao.candidatesFor(MemoryEntity.LOCAL_USER_ID, PERSONA, S2).size)
    }

    @Test
    fun `显式写会话级仍然可用 —— AI 分流的意思要保留`() = runBlocking {
        val saved = repo.remember(
            personaId = PERSONA,
            sessionId = S1,
            content = "今天我们说好了周末去爬山",
            scope = MemoryEntity.SCOPE_SESSION,
        )
        assertEquals(MemoryEntity.SCOPE_SESSION, saved.scope)
        assertEquals(S1, saved.sessionId)
        assertEquals("别的会话看不到它", 0, dao.candidatesFor(MemoryEntity.LOCAL_USER_ID, PERSONA, S2).size)
        assertEquals("本会话看得到", 1, dao.candidatesFor(MemoryEntity.LOCAL_USER_ID, PERSONA, S1).size)
    }

    /* ─────────── 检索可见范围：人设级跨会话 + 会话级仅本会话 ─────────── */

    @Test
    fun `检索可见范围_人设级跨会话可见_会话级只在本会话`() = runBlocking {
        dao.upsert(mem("a", MemoryEntity.SCOPE_PERSONA, null))
        dao.upsert(mem("b", MemoryEntity.SCOPE_SESSION, S1))
        dao.upsert(mem("c", MemoryEntity.SCOPE_SESSION, S2))

        val inS1 = dao.candidatesFor(MemoryEntity.LOCAL_USER_ID, PERSONA, S1).map { it.id }.toSet()
        assertEquals(setOf("a", "b"), inS1)
        val inS2 = dao.candidatesFor(MemoryEntity.LOCAL_USER_ID, PERSONA, S2).map { it.id }.toSet()
        assertEquals(setOf("a", "c"), inS2)
    }

    @Test
    fun `管理页可见范围与检索一致_人设级加本会话`() = runBlocking {
        dao.upsert(mem("a", MemoryEntity.SCOPE_PERSONA, null))
        dao.upsert(mem("b", MemoryEntity.SCOPE_SESSION, S1))
        dao.upsert(mem("c", MemoryEntity.SCOPE_SESSION, S2))

        val visible = dao.observeVisible(MemoryEntity.LOCAL_USER_ID, PERSONA, S1).first().map { it.id }.toSet()
        assertEquals(setOf("a", "b"), visible)
    }

    /* ─────────── 迁移：会话级 → 人设级 ─────────── */

    @Test
    fun `迁移把会话记忆升为人设级_重复合并_会话条清空`() = runBlocking {
        val w = MemoryWriter(dao)
        // 两个会话各有一条相同的话 + 已有的人设级更早一条（重要性更低）
        w.write(PERSONA, MemoryEntity.SCOPE_SESSION, "用户喜欢猫", "喜好", 5, MemoryEntity.SOURCE_AUTO, sessionId = S1, now = 100L)
        w.write(PERSONA, MemoryEntity.SCOPE_SESSION, "用户喜欢猫", "喜好", 7, MemoryEntity.SOURCE_AUTO, sessionId = S2, now = 200L)
        w.write(PERSONA, MemoryEntity.SCOPE_PERSONA, "用户喜欢猫", "喜好", 3, MemoryEntity.SOURCE_AUTO, now = 50L)

        val processed = w.migrateSessionToPersona(PERSONA)
        assertEquals("两条会话记忆都被处理", 2, processed)

        val left = dao.rows.filter { it.scope == MemoryEntity.SCOPE_SESSION }
        assertTrue("会话级条目应该清空", left.isEmpty())
        val personaRows = dao.rows.filter { it.scope == MemoryEntity.SCOPE_PERSONA }
        assertEquals("三条近似记忆合并成一条", 1, personaRows.size)
        assertEquals("重要性取最大", 7, personaRows[0].importance)
        assertNull("升级后的条目不带会话归属", personaRows[0].sessionId)
    }

    @Test
    fun `没有会话记忆时迁移是空操作`() = runBlocking {
        val w = MemoryWriter(dao)
        w.write(PERSONA, MemoryEntity.SCOPE_PERSONA, "关于用户的事", "其他", 5, MemoryEntity.SOURCE_AUTO, now = 100L)
        assertEquals(0, w.migrateSessionToPersona(PERSONA))
        assertEquals(1, dao.rows.size)
    }

    @Test
    fun `迁移不碰别的人设`() = runBlocking {
        val w = MemoryWriter(dao)
        w.write("p2", MemoryEntity.SCOPE_SESSION, "别的人设的记忆", "其他", 5, MemoryEntity.SOURCE_AUTO, sessionId = S1, now = 100L)
        assertEquals(0, w.migrateSessionToPersona(PERSONA))
        assertEquals(1, dao.rows.size)
        assertEquals("p2", dao.rows[0].personaId)
    }

    /* ─────────── 迁移入口的判据：跨全部会话的会话级计数 ─────────── */

    @Test
    fun `迁移判据_本会话的会话级不算_别的会话的算`() = runBlocking {
        // 人设级不算 —— 它本来就不需要迁移
        dao.upsert(mem("a", MemoryEntity.SCOPE_PERSONA, null))
        // 本会话（S1）的会话级不算 —— 它在当前会话里看得见，没丢，不是"旧记忆"
        dao.upsert(mem("b", MemoryEntity.SCOPE_SESSION, S1))
        // 别的人设的会话级不计数
        dao.upsert(mem("x", MemoryEntity.SCOPE_SESSION, S1, personaId = "p2"))

        assertEquals(
            "本会话的会话级 + 人设级 + 别人设 —— 一个都不算",
            0,
            dao.observeSessionCountOfPersona(MemoryEntity.LOCAL_USER_ID, PERSONA, S1).first(),
        )

        // 别的会话（S2）的会话级要算 —— 这些在当前会话里看不到（才真是"只属于某段对话"）
        dao.upsert(mem("c", MemoryEntity.SCOPE_SESSION, S2))
        assertEquals(
            "只算别的会话的会话级",
            1,
            dao.observeSessionCountOfPersona(MemoryEntity.LOCAL_USER_ID, PERSONA, S1).first(),
        )

        // 迁移后归零 —— 界面上迁移入口随之消失（判据与迁移同一个集合）
        MemoryWriter(dao).migrateSessionToPersona(PERSONA)
        assertEquals(
            0,
            dao.observeSessionCountOfPersona(MemoryEntity.LOCAL_USER_ID, PERSONA, S1).first(),
        )
    }
}
