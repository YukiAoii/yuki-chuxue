package ai.yuki.chuxue.data.room

import ai.yuki.chuxue.data.ChatMessage
import ai.yuki.chuxue.data.SCRIM_PLAIN
import ai.yuki.chuxue.data.Session
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Room 实体 ↔ 领域模型的映射（开发文档 §13.3 / §13.4）。
 *
 * 全部是**纯函数** —— 这是本次迁移里唯一能单元测试的部分，
 * 所以映射逻辑一个字都不该写在 Repository 里，全放这里。
 */
object SessionMapper {

    private val json = Json { ignoreUnknownKeys = true }

    /* ─────────── 领域 → 实体 ─────────── */

    fun sessionToEntity(s: Session): SessionEntity = SessionEntity(
        id = s.id,
        personaId = s.personaId,
        title = s.title,
        createdAt = s.createdAt,
        updatedAt = s.updatedAt,
        totalHit = s.totalHit,
        totalMiss = s.totalMiss,
        muted = s.muted,
        pinned = s.pinned,
        background = s.background,
        thinkingEnabled = s.thinkingEnabled,
        reasoningEffort = s.reasoningEffort,
        scrimEnabled = s.scrimEnabled,
        scrimAlpha = s.scrimAlpha,
        scrimStyle = s.scrimStyle,
        summary = s.summary,
        summaryUpTo = s.summaryUpTo,
        hitAtCompress = s.hitAtCompress,
        summaryCount = s.summaryCount,
        missAtCompress = s.missAtCompress,
        providerGroupId = s.providerGroupId,
        model = s.model,
    )

    fun messagesToEntities(sessionId: String, messages: List<ChatMessage>): List<MessageEntity> =
        messages.mapIndexed { index, m ->
            MessageEntity(
                id = messageId(sessionId, index),
                sessionId = sessionId,
                role = m.role,
                content = m.content,
                seq = index,
                // 消息自带的时间戳（v0.14.2 起）。
                // `messages.createdAt` 这一列本来就在 schema 里，只是过去一直写 0 ——
                // 于是聊天窗口无从画「时间分割条」。
                // ⚠️ 它只用于界面显示：请求体的构造只认 role / content / images。
                createdAt = m.createdAt,
                imagesJson = encodeImages(m.images),
                // 思考过程（可空）。与 content 不同，它不回传给模型 ——
                // 存它只为让用户事后能回看"她当时是怎么想的"。
                reasoning = m.reasoning,
                thinkingMs = m.thinkingMs,
                sendMode = m.sendMode,
                splitBubbles = m.splitBubbles,
                // 历史版本（v16→v17）—— 与 images 同型的字符串数组，编解码直接复用。
                // ⚠️ 它**不进请求体**：PromptEngine 只读 role / content / images。
                supersededJson = encodeImages(m.superseded),
                emojiPath = m.emojiPath,
            )
        }

    /* ─────────── 实体 → 领域 ─────────── */

    fun toDomain(entity: SessionEntity, messages: List<MessageEntity>): Session = Session(
        id = entity.id,
        personaId = entity.personaId,
        title = entity.title,
        messages = messages
            .sortedBy { it.seq }
            .map {
                ChatMessage(
                    role = it.role,
                    content = it.content,
                    images = decodeImages(it.imagesJson),
                    createdAt = it.createdAt,
                    reasoning = it.reasoning,
                    thinkingMs = it.thinkingMs,
                    sendMode = it.sendMode,
                    splitBubbles = it.splitBubbles,
                    // 历史版本：老数据没有这一栏 → null → 空表（"没有历史版本"）
                    superseded = decodeImages(it.supersededJson ?: "[]"),
                    emojiPath = it.emojiPath,
                )
            },
        createdAt = entity.createdAt,
        updatedAt = entity.updatedAt,
        totalHit = entity.totalHit,
        totalMiss = entity.totalMiss,
        // ⚠️ 这几列在表里是**可空**的（v5 迁移刻意不加 DEFAULT，理由见 SessionEntity 的注释）。
        // null = 「这条老数据从没设过」—— 默认值在这里落定，**只此一处**，
        // 免得散到 UI 与 ViewModel 里各判一次。
        muted = entity.muted ?: false,
        pinned = entity.pinned ?: false,
        background = entity.background,
        thinkingEnabled = entity.thinkingEnabled ?: true,
        reasoningEffort = entity.reasoningEffort ?: "high",
        // v6 三列：同样是"从没设过"= null，默认值只在这里落定一处。
        scrimEnabled = entity.scrimEnabled ?: true,
        scrimAlpha = entity.scrimAlpha ?: 0.55f,
        scrimStyle = entity.scrimStyle ?: SCRIM_PLAIN,
        // 可空列：老数据是 null = 还没压缩过（与"没配过"是同一件事）
        summary = entity.summary,
        summaryUpTo = entity.summaryUpTo ?: 0L,
        hitAtCompress = entity.hitAtCompress ?: 0,
        summaryCount = entity.summaryCount ?: 0,
        missAtCompress = entity.missAtCompress ?: 0,
        // v14 两列：`null` 就是**正确的语义**（= 跟着全局当前分组 / 分组里勾选的第一个），
        // 所以这里**不做 `?:` 兜底** —— 兜成空串会让"没选过"与"选了空模型"混为一谈。
        providerGroupId = entity.providerGroupId,
        model = entity.model,
    )

    /* ─────────── 细节 ─────────── */

    /**
     * 消息主键由「会话 id + 序号」决定，**是确定性的**。
     *
     * 这样同一条消息重复写入会走 `@Upsert` 幂等覆盖，而不是插出重复行。
     * 若改用随机 UUID，每次保存整段历史都会**重复插入全部消息**，
     * 历史长度随保存次数翻倍 —— 那是灾难性的（缓存前缀直接废掉）。
     */
    fun messageId(sessionId: String, seq: Int): String = "$sessionId#$seq"

    /** 图片列表 → JSON 数组字符串；无图存 "[]"（保证列非空，省一次 null 判断）。 */
    fun encodeImages(images: List<String>): String =
        if (images.isEmpty()) "[]" else json.encodeToString(images)

    /** JSON → 图片列表；**解析失败返回空表而不是抛异常**（一条坏数据不该让整个会话读不出来）。 */
    fun decodeImages(raw: String): List<String> =
        runCatching { json.decodeFromString<List<String>>(raw) }.getOrElse { emptyList() }
}
