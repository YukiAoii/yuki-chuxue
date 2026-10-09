package ai.yuki.chuxue.data.room

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 会话表（开发文档 §13.3）。
 *
 * 为什么只放这几个字段：文档 §13.3 的 SessionEntity 还有 `model` 等字段，
 * 但本项目模型是**全局设置**（`AppSettings.model`），会话级不单独存 —— 多存一份
 * 就会出现「会话说自己用 A 模型、设置说用 B」的不一致。
 *
 * ⚠️ 缓存纪律：`messages` 一旦写入**只追加不改写**（见 MessageEntity 的注释）。
 */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: String,
    val personaId: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** 累计命中的缓存 token（用于「我的」页统计） */
    val totalHit: Int,
    /** 累计未命中的缓存 token */
    val totalMiss: Int,

    /* ── 会话级设置（v5 新增）──
       ⚠️ 全部**可空、不带 DEFAULT**，原因有两条：
       1. 非空列在 ALTER TABLE 时必须带 DEFAULT，而 `@ColumnInfo(defaultValue = …)`
          会让 Room 的 KSP 处理器去反序列化 schema bundle，与项目的
          kotlinx-serialization 版本冲突 —— 实测报 AbstractMethodError（编译器内部错误）。
       2. 可空在语义上更诚实：null = 「这条老数据从没设过」，默认值统一在
          SessionMapper（entity → 领域）落定，只有一处。
       顺带：ALTER TABLE 加可空列不需要 DEFAULT，迁移 SQL 也更干净。 */
    val muted: Boolean?,
    val pinned: Boolean?,
    val background: String?,
    val thinkingEnabled: Boolean?,
    val reasoningEffort: String?,

    /* ── 背景遮罩（v6 新增，同样全部可空无 DEFAULT）── */
    val scrimEnabled: Boolean?,
    val scrimAlpha: Float?,
    val scrimStyle: String?,

    /**
     * 上下文压缩摘要（v8→v9 新增）。
     *
     * ⚠️ **可空、不给 `@ColumnInfo(defaultValue=…)`** —— 项目纪律：
     * 只有非空列才需要 DEFAULT，而 DEFAULT 会触发 Room × kotlinx-serialization
     * 那个 KSP 类加载器地雷。老数据统一落成 `null`（= 还没压缩过），
     * 行为与加这一列之前完全一致。
     */
    val summary: String?,

    /** 摘要覆盖到的消息时间戳（0 = 没压缩过）。见 `Session.summaryUpTo` 的注释。 */
    val summaryUpTo: Long?,

    /** 压缩时刻的累计快照（见 `Session.hitAtCompress` 的注释）。 */

    /** 摘要覆盖的消息条数（见 `Session.summaryCount` 的注释）。 */
    val summaryCount: Int?,
    val hitAtCompress: Int?,
    val missAtCompress: Int?,

    /**
     * 会话级 provider（v13→v14 新增，v0.51.0「分组」功能）。
     *
     * ⚠️ **可空、不给 `@ColumnInfo(defaultValue=…)`** —— 项目纪律（见 `summary` 的注释）：
     * DEFAULT 会触发 Room × kotlinx-serialization 那个 KSP 类加载器地雷。
     * 老数据统一落成 `null`（= 用全局当前分组），行为与加这两列之前完全一致。
     */
    val providerGroupId: String?,
    val model: String?,
)

/**
 * 消息表（开发文档 §13.4）。
 *
 * ## 为什么 `images` 存 JSON 字符串而不是关联表
 * 图片是本条消息的**内联附件**（base64 data URL），没有独立查询需求，
 * 也不需要在消息之间共享。拆一张表只会让「读一条消息」变成两次查询，
 * 收益为零。用 JSON 数组字符串存，读写都是单行。
 *
 * ## seq 的作用
 * 这是消息在会话里的**顺序号**。`Room` 的查询虽可 `ORDER BY createdAt`，
 * 但同一毫秒内可能写入 user 与 assistant 两条 —— 只有显式的 `seq` 能保证
 * 「先问后答」的次序稳定。**顺序错了，历史就错了，下一轮前缀也跟着错。**
 *
 * ⚠️ 写入纪律：消息进历史后**永不修改**（`ChatMessage` 的契约）。
 * 更新一条历史消息 = 前缀从那里断开 = 缓存失效。
 */
@Entity(
    tableName = "messages",
    indices = [Index("sessionId")],
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    /** "user" | "assistant" */
    val role: String,
    val content: String,
    /** 会话内顺序号，从 0 递增 */
    val seq: Int,
    val createdAt: Long,
    /** 图片 data URL 列表的 JSON 数组；无图为 "[]" */
    val imagesJson: String,
    /**
     * 这条回复的**思考过程**（`reasoning_content`），可空。
     *
     * ⚠️ 与 `content` 不同：它**不是请求前缀的一部分**（不回传给模型）。
     * 存它只为一件事 —— 让用户事后能在聊天窗口回看"她当时是怎么想的"。
     */
    val reasoning: String?,
    /**
     * 这条回复的**思考用时**（毫秒），可空（v6 新增）。
     *
     * ⚠️ 与 [reasoning] 同类：**不是请求前缀的一部分**，存它只为在界面上
     * 显示"她想了几秒"。老消息（v6 之前）没有这个数，NULL 就是正确答案。
     */
    val thinkingMs: Long?,

    /**
     * 这条回复**生成时**用的呈现方式（v9→v10 新增）。
     * ⚠️ 冻结在消息上，渲染只认它 —— 改设置不该改写已经发生过的事。
     * 可空、**不给 DEFAULT**（项目纪律：避免 Room × serialization 的 KSP 地雷）。
     */
    val sendMode: String?,

    /**
     * 这条回复**生成时**的「分段气泡」开关（v15→v16 新增，v0.61.6）。
     * ⚠️ 与 [sendMode] 同一条纪律：冻结在消息上。
     * `null` = 这个字段出现之前的消息 —— 渲染**完全保持原样**（单枚 + 思考在气泡内）。
     */
    val splitBubbles: Boolean?,

    /**
     * **被重新生成替掉的历史版本**（v16→v17 新增，v0.61.11），JSON 字符串数组。
     * 可空、**不给 DEFAULT**（项目纪律，见 [SessionEntity.summary] 那段）；
     * `null` / `"[]"` = 没有历史版本。
     * ⚠️ **不进请求体** —— 老版本只留给用户回看与复制。
     */
    val supersededJson: String?,

    /** 这条回复配的表情包路径（v9→v10）。可空；**不进请求体**。 */
    val emojiPath: String?,
)
