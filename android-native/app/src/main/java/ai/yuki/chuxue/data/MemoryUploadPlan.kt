package ai.yuki.chuxue.data

import ai.yuki.chuxue.data.room.MemoryEntity

/**
 * 「把本机记忆送到云端」的**上传计划**（纯函数，可单测；v0.61.48）。
 *
 * 管两件事：**该传哪些**（口径）与**怎么分批**（性能）。两条都曾被写歪：
 * - **口径**：手动同步用不带 scope 过滤的全量查询 → **会话级记忆上云**（跨会话泄漏）。
 *   既有纪律是"只推人设级"（见 [MemoryCloud.push] 的 v0.61.38 修正），这条路径漏了同一道闸。
 * - **性能**：逐条串行 → N 条最坏 N×20s（每条走 `App → YukiServer → OB`，服务端过一次 LLM 压缩）。
 */
object MemoryUploadPlan {

    /**
     * 默认并发路数。
     *
     * 取 3 而不是更高：服务端**每条都要过一次 OB 侧 LLM 压缩**（秒级~十几秒），
     * 并发太高会把 OB 压到排队，反而更慢、还可能触发服务端超时。
     */
    const val DEFAULT_CONCURRENCY = 3

    /**
     * **该上传哪些** —— 只留**人设级**。
     *
     * ⚠️ 会话级（`scope='session'`）语义是"仅本会话可见"，推到 persona 桶会**跨会话泄漏**。
     * 这与 [MemoryCloud.push] 是同一条纪律；这里漏掉就是那条纪律的第一个破口。
     */
    fun uploadableOf(rows: List<MemoryEntity>): List<MemoryEntity> =
        rows.filter { it.scope == MemoryEntity.SCOPE_PERSONA }

    /**
     * **怎么分批** —— 每批至多 [concurrency] 条；调用方并发跑批、批内串行。
     *
     * `concurrency <= 0` **退化为 1**（不抛异常、不除零）：调用方的配置错误
     * 不该让整次同步失败 —— 慢一点也比全坏强。
     *
     * 空列表 → 空批（**空是必须支持的一等情形**：本机没有可同步的记忆时不该发任何请求）。
     */
    fun batchesOf(
        rows: List<MemoryEntity>,
        concurrency: Int = DEFAULT_CONCURRENCY,
    ): List<List<MemoryEntity>> {
        if (rows.isEmpty()) return emptyList()
        return rows.chunked(concurrency.coerceAtLeast(1))
    }
}
