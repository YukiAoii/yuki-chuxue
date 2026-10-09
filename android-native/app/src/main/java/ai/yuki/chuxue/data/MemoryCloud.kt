package ai.yuki.chuxue.data

import android.content.Context
import ai.yuki.chuxue.data.room.AppDatabase
import ai.yuki.chuxue.data.room.MemoryEntity

/**
 * 「记忆上云」的统一入口（v0.61.37；v0.61.38 重写为**落库后推送 + 记回桶 id**）。
 *
 * ## 它解决什么
 * 新记忆产生时有**两个**来源：自动提取（[ai.yuki.chuxue.data.memory.MemoryExtractionScheduler]）
 * 与用户手记（[ai.yuki.chuxue.ui.memory.MemoryViewModel]）。两处都要"若该角色开了记忆上云，
 * 就把这条送到云端 OB" —— 判断开关、取登录态、拼请求的逻辑**只写这一份**，
 * 免得两处的门禁慢慢长歪（同一条记忆一处上云一处没上，是那种说不清哪里不对的 bug）。
 *
 * ## v0.61.38 的两处修正（都是审查发现的真缺陷）
 * 1. **改成传"已落库的实体"**（原来传裸 content）：本地写入会做同域去重合并，
 *    推的必须是**合并后**的正文（否则本地 1 条、云端 2 条）；顺带拿到本地行 id，
 *    把云端返回的桶 id 记回那一行 —— 删除时才有映射可用。
 * 2. **只推人设级**：`scope='session'` 的记忆"仅本会话可见"，推到 persona 桶会**跨会话泄漏**。
 *
 * ## 边界（与 [XinchaoReport] 同一套纪律）
 * - **开关关 = 零网络**（第一道就返回；本地模式 → 零网络）；
 * - **失败静默**：上云是增强，网络错误不该影响"记住这件事"本身；
 * - **依附云端模式**（[Persona.isCloudMemory]）：本地模式的人设没有桶，云端页也读不到，就不推。
 */
object MemoryCloud {

    /**
     * **纯函数**门禁：该人设要不要把记忆推上云。
     *
     * v0.61.48 起判据 = **「记忆方式」是不是云端**（[Persona.isCloudMemory]）。
     * 用户 2026-10-06 拍板：云端三项功能都依赖云端，一个选择决定三项，
     * 不再各自读 `cloudMemoryEnabled` / `xinchaoEnabled`（那两个字段保留只为备份/回显）。
     * 人设为 null（已删/读不到）→ 不推。
     * ⚠️ 老数据 `memoryMode` 为 `null` → 本地 → 恒 false → **零网络**
     *（用户明确决定"老数据一律归本地、三项关闭"——有意变更，非"零变化"）。
     */
    fun shouldPush(persona: Persona?): Boolean = persona?.isCloudMemory == true

    /**
     * 把一条**已经落库**的记忆推上云；成功时把云端桶 id 记回本地那一行（删除时要用）。
     *
     * 调用方只负责"本地写已成功"；这里再判开关与人设级 scope。任何失败都静默返回。
     */
    suspend fun push(context: Context, personaId: String, entity: MemoryEntity) {
        if (entity.content.isBlank()) return
        // 只有**人设级**才上云：会话级"仅本会话可见"，推上去会跨会话泄漏（见文件头 v0.61.38 修正 2）
        if (entity.scope != MemoryEntity.SCOPE_PERSONA) return
        val store = Store(context)
        val persona = when (val loaded = store.loadPersonas()) {
            is Store.Loaded.Ok -> loaded.value.firstOrNull { it.id == personaId }
            else -> null
        } ?: return
        // 开关：记忆上云关着 → 零网络（老用户默认 false，行为与加这个功能之前一致）
        if (!shouldPush(persona)) return
        val base = ServerConfig.BASE_URL
        val token = store.loadAuth().token
        if (base.isBlank() || token.isBlank()) return
        val bucketId = runCatching {
            XinchaoMemoryApi.write(
                baseUrl = base,
                token = token,
                personaId = personaId,
                content = entity.content,
                category = entity.category.ifBlank { null },
                importance = entity.importance,
            )
        }.getOrNull()?.getOrNull() ?: return
        // 记下云端桶 id —— 删除这条时要按它删（记不上也不影响"已经上云"这件事）
        runCatching { AppDatabase.get(context).memoryDao().setCloudBucketId(entity.id, bucketId) }
    }
}
