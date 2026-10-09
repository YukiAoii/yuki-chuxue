package ai.yuki.chuxue.data

import ai.yuki.chuxue.data.room.MemoryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「把本机记忆送到云端」的**上传计划**纯函数契约（v0.61.48）。
 *
 * ## 为什么要有它（这是修 bug，不是重构）
 * 原 `MemoryViewModel.syncLocalToCloud` 用 `allOfPersona(...)`（**不带 scope 过滤**）取全部记忆
 * 逐条上云 —— 但 `MemoryCloud.push` 的既有口径是**只推人设级**，理由是会话级记忆
 * "仅本会话可见"、推到 persona 桶会**跨会话泄漏**（v0.61.38 的修正）。
 * 手动同步这条路径漏了同一道过滤，成了那条纪律的第一个破口。
 *
 * ## 另一个问题：串行
 * 原实现 `for (m in rows) { write(...) }` 逐条串行，每条走 `App → backend → OB`（服务端过一次
 * LLM 压缩），客户端 readTimeout 20s —— N 条最坏 N×20s。分批 + 有限并发把墙钟压到 ~N/并发。
 */
class MemoryUploadPlanTest {

    private fun mem(id: String, scope: String, personaId: String = "p1") = MemoryEntity(
        id = id,
        userId = MemoryEntity.LOCAL_USER_ID,
        personaId = personaId,
        sessionId = if (scope == MemoryEntity.SCOPE_SESSION) "s1" else null,
        scope = scope,
        content = "c-$id",
        category = "其他",
        importance = 5,
        source = MemoryEntity.SOURCE_MANUAL,
        embedding = null,
        createdAt = 0L,
        lastAccessedAt = 0L,
        expiresAt = null,
    )

    /* ─────────── 可上传集合：只推人设级 ─────────── */

    @Test
    fun `只上传人设级 —— 会话级绝不上云`() {
        val rows = listOf(
            mem("a", MemoryEntity.SCOPE_PERSONA),
            mem("b", MemoryEntity.SCOPE_SESSION),
        )
        val plan = MemoryUploadPlan.uploadableOf(rows)
        assertEquals(1, plan.size)
        assertEquals("会话级被漏放了 —— 会跨会话泄漏", "a", plan.first().id)
    }

    @Test
    fun `全是会话级时上传计划为空`() {
        val rows = listOf(mem("b", MemoryEntity.SCOPE_SESSION), mem("c", MemoryEntity.SCOPE_SESSION))
        assertTrue(MemoryUploadPlan.uploadableOf(rows).isEmpty())
    }

    @Test
    fun `保持原顺序 —— 先记的先传`() {
        val rows = listOf(
            mem("a", MemoryEntity.SCOPE_PERSONA),
            mem("b", MemoryEntity.SCOPE_PERSONA),
            mem("c", MemoryEntity.SCOPE_PERSONA),
        )
        assertEquals(listOf("a", "b", "c"), MemoryUploadPlan.uploadableOf(rows).map { it.id })
    }

    /* ─────────── 分批：有限并发用 ─────────── */

    @Test
    fun `并发 3 时分出正确的批数与总条数`() {
        val rows = (1..7).map { mem("m$it", MemoryEntity.SCOPE_PERSONA) }
        val batches = MemoryUploadPlan.batchesOf(rows, concurrency = 3)
        assertEquals(3, batches.size)
        assertEquals(7, batches.sumOf { it.size })
        assertEquals("每批不超过并发数", listOf(3, 3, 1), batches.map { it.size })
    }

    @Test
    fun `空列表不分批 —— 空是必须支持的一等情形`() {
        assertTrue(MemoryUploadPlan.batchesOf(emptyList(), concurrency = 3).isEmpty())
    }

    @Test
    fun `并发数非法时退化为 1 —— 不抛异常、不除零`() {
        val rows = (1..3).map { mem("m$it", MemoryEntity.SCOPE_PERSONA) }
        listOf(0, -2).forEach { bad ->
            val batches = MemoryUploadPlan.batchesOf(rows, concurrency = bad)
            assertEquals(3, batches.size)
            assertEquals(3, batches.sumOf { it.size })
        }
    }
}
