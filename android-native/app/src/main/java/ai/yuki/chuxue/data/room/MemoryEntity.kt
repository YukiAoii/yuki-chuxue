package ai.yuki.chuxue.data.room

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 长期记忆表（开发文档 §7.3 / §13.6）。
 *
 * ## 三层隔离（文档 §8.1）
 * ```
 * 第一层 userId     所有记忆查询强制带
 * 第二层 personaId  所有记忆查询强制带
 * 第三层 scope      persona 级跨会话共享 / session 级仅本会话可见
 * ```
 * ⚠️ 2026-10-06 更正：本项目**已有完整账号体系**（`YukiServer` 的注册/登录/令牌，
 * 见 `backend/main.py` 的 `/auth/…` 一组接口）。但**记忆库本身仍是纯本地、按设备** ——
 * 第一层 `userId` 目前恒为 [LOCAL_USER_ID]（"local"）。保留这一层不是为了"以后可能
 * 多用户"，而是因为**隔离规则必须在数据模型上写死**：查询一旦漏带某个维度，
 * 记忆就会串到别的角色身上，而这类 bug 不会报错、只会让「她记错了人」——
 * 最难发现也最伤产品。（真要把记忆上云 / 跨设备，这一层就是对齐账号 uid 的落点。）
 *
 * ## 为什么 `personaId` 没有外键
 * 人设存在 DataStore（`Store.kt`），**不在 Room**，无法建跨存储外键。
 * 这与 `sessions` 的处理一致：删人设时由 ViewModel 显式清理（`deletePersona`），
 * 而不是靠数据库级联。
 *
 * `sessionId` 指向 Room 内的 `sessions` 表，因此可以建外键 —— `scope=session`
 * 的记忆会随会话一起清掉，不留孤儿。
 *
 * ## 两个本波未使用的字段（照文档 §7.3 预留）
 * - [embedding]：文档 §7.1 的方案是 `sqlite-vec + BGE-small-zh + ONNX` 端侧向量检索。
 *   实测该栈在本环境**不可得**（sqlite-vec 的 JitPack 坐标返回 401；BGE 模型
 *   托管在 huggingface、连接超时）。故本波检索走文档 §25.3 授权的**关键词降级**路径，
 *   该列恒为 `null`。
 * - [expiresAt]：文档 §7.9 的 TTL 遗忘机制，本波先实现「自衰减 + 手动删除」两条，
 *   TTL 留待后续。
 *
 * ⚠️ **缓存纪律**：记忆**只从附录注入**（`PromptEngine.buildAppendix`），
 * 绝不写回冻结前缀或历史。写入前缀会让该会话从写入点起全部缓存失效。
 */
@Entity(
    tableName = "memories",
    indices = [
        Index("userId"),
        Index("personaId"),
        Index("sessionId"),
        Index(value = ["userId", "personaId"]),
        Index(value = ["userId", "personaId", "scope"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class MemoryEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val personaId: String,
    val sessionId: String?,
    /** `"persona"`（跨会话共享）| `"session"`（仅本会话可见） */
    val scope: String,
    val content: String,
    /** 分类标签，用于记忆管理页分组（如 "喜好" / "经历" / "约定"） */
    val category: String,
    /** 重要性 0–10，参与检索加权与衰减 */
    val importance: Int,
    /** 来源，如 `"manual"` / `"auto_summary"` / `"selected_message"` */
    val source: String,
    /** 端侧嵌入向量（本波未接入，恒为 null —— 见类注释） */
    val embedding: ByteArray?,
    /**
     * 这条记忆在**云端**（OB 记忆大脑）的桶 id（v0.61.38）。
     *
     * 上云成功时由 [ai.yuki.chuxue.data.MemoryCloud] 回写；删除这条记忆时按它去云端删
     *（否则云端留着一个"本地已删、Ta 却还能想起"的幽灵）。`null` = 没上过云 /
     * 上云时没拿到 id（老数据、未开记忆上云的角色都是 null）。
     *
     * ⚠️ 可空、**不带 DEFAULT**（项目纪律，见 AppDatabase 的 MIGRATION_17_18）。
     */
    val cloudBucketId: String? = null,
    val createdAt: Long,
    /** 最近一次被检索命中；衰减按它计算（文档 §7.8） */
    val lastAccessedAt: Long,
    /** TTL 过期时间（本波未接入，恒为 null） */
    val expiresAt: Long?,
) {
    companion object {
        const val SCOPE_PERSONA = "persona"
        const val SCOPE_SESSION = "session"

        /**
         * 来源取值（v0.61.21 收拢到这里）。
         *
         * ⚠️ 它们**不只是标签**，而是承载了一条语义：**用户有没有亲手动过这条记忆**。
         * [SOURCE_AUTO] 是"后台替Ta记的"，于是它有两件特权：
         * 1. 合并时**可被更重要的新表述替换正文**；
         * 2. 「删掉这一轮」时**会被连带删除**（`MemoryDao.deleteAutoSince` 只认它）。
         *
         * 反过来，只要来源不是它（用户写过、改过、从消息里选过），上面两件事都**不许**发生。
         * 所以用户改一条记忆时，来源必须一并改掉 —— 见 [afterManualEdit]。
         */
        const val SOURCE_AUTO = "auto_summary"

        /** 用户亲手写的（或在记忆页改过的）。 */
        const val SOURCE_MANUAL = "manual"

        /**
         * 本地单用户标识。BYOK 模式没有账号体系，但三层隔离的**形状**必须保留 ——
         * 所有 DAO 查询都强制带它，将来真加账号时不需要回头改查询。
         */
        const val LOCAL_USER_ID = "local"
    }
}

/**
 * 用户在记忆页改过一条之后的样子（v0.61.21）。
 *
 * ## 为什么非改 `source` 不可
 * 界面上的"编辑"原来只改 content/category/importance，**来源原样保留** ——
 * 于是一条被用户亲手改过的自动记忆，在系统眼里仍然是"后台替Ta记的"：
 * 1. 下一次自动提取碰到近似内容时，**可能用自动文本把用户改的正文盖掉**；
 * 2. 用户点「删除最后一轮」时，它**会被连带删掉**。
 *
 * 用户改过的东西不该被系统当成自己的 —— 所以编辑这一步把来源改成 [MemoryEntity.SOURCE_MANUAL]，
 * 上面两件事同时失效。刻意**不动 schema**：`source` 本来就是字符串列，
 * 只是多了一种取值，老数据（只有 `manual` / `auto_summary` / `selected_message`）照常读。
 */
fun MemoryEntity.afterManualEdit(
    content: String,
    category: String,
    importance: Int,
): MemoryEntity = copy(
    content = content,
    category = category,
    importance = importance,
    source = MemoryEntity.SOURCE_MANUAL,
)
