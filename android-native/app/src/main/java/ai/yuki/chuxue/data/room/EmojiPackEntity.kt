package ai.yuki.chuxue.data.room

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 表情包表（用户 2026-09-28 要求，依据 `表情包参考文档.txt`）。
 *
 * ## 它解决什么
 * 让 AI 在聊天里"发图"：AI 只在文字里输出**情绪标签**（如 `[开心]`），
 * App 拿标签去本地图库里**随机抽一张**，作为图片气泡显示在回复后面。
 *
 * ## ⚠️ 表结构为什么这么简单（两个字段就够）
 * - [category] 用**字符串**而不是外键表：分类本来就是自由文本。
 *   单独建一张分类表 = 多一张表、多一次迁移、多一次 JOIN，
 *   而收益只是"分类名不会拼错" —— 不划算。
 *   分类名由 [EmojiCategories.DEFAULTS] 提供（**那一份是唯一来源**：
 *   v0.43.1 起不再支持用户新建分类，理由见 `EmojiCategories` 的类注释）。
 * - [id] 直接用**图片的本地路径**：路径由 `ImageStore` 生成（文件名带时间戳），
 *   天然唯一且**确定性** —— 于是重复添加同一张图会走 `@Upsert` 幂等覆盖，
 *   而不是插出两行。这与 `MessageEntity` 用「会话id#序号」当主键是同一条道理。
 *
 * ## ⚠️ 它**不碰**任何与缓存有关的东西
 * 这张表只被"抽图"和"管理界面"读，**永不进请求体**。
 * 抽到的图也只在本机显示（见 `ChatViewModel` 里的本地图片气泡）——
 * 理由：DeepSeek 明确限制「图片只能出现在 user 消息里」，而且历史里一旦有图，
 * 它**之后的内容全部无法命中缓存**。所以 AI 发的图**不进 `messages`**。
 */
@Entity(tableName = "emoji_packs", indices = [Index("category"), Index("personaId")])
data class EmojiPackEntity(
    /** 图片的本地绝对路径，同时充当主键（见类注释） */
    @PrimaryKey val id: String,
    /** 情绪分类名（取值见 [EmojiCategories.DEFAULTS]；历史数据里可能还有已撤销的自定义分类） */
    val category: String,
    val createdAt: Long,
    /**
     * 归属（方案 C，v0.43.0）：`null` = **全局**（所有角色都能用）；
     * 非 null = 只属于这一个人设。
     *
     * 抽图时的回退是**按情绪分类**做的：`专属[开心] → 全局[开心] → 不发`。
     * 回退不能做成"整库回退"，理由见 [ai.yuki.chuxue.data.EmojiPicker.pick]。
     *
     * ⚠️ 可空、且**不给 `@ColumnInfo(defaultValue = …)`** —— 项目纪律：
     * 只有非空列才需要 DEFAULT，而 DEFAULT 会触发 Room × kotlinx-serialization
     * 那个 KSP 类加载器地雷。老数据由 `MIGRATION_7_8` 统一落成 `null`（= 全局），
     * 也就是"升级后所有既有图对每个人设都可见"，不丢图、不改行为。
     *
     * ⚠️ 它声明在**末尾**：既有构造点都靠位置参数传前三个字段，
     * 插在中间会把 `createdAt` 挤到别的位置上。
     */
    val personaId: String? = null,
)
