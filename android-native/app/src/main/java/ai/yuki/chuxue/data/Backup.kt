package ai.yuki.chuxue.data

import ai.yuki.chuxue.data.room.MemoryEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 一次数据备份的全部内容。
 *
 * 它把散在三处的数据收拢成**一个对象**：人设（DataStore）、对话与记忆（Room）、
 * 设置与「我」的资料（DataStore）。
 */
data class BackupSnapshot(
    val appVersion: String,
    val exportedAt: Long,
    val personas: List<Persona>,
    /**
     * 用户人设（v0.61.41）—— 用户自己的角色。
     *
     * ⚠️ 与人设**同组**（[BackupScope.includesPersona]）：丢了它，人设里
     * `userPersonaId` 的绑定会指向不存在的 id。默认空表 —— 老快照没有这一栏，
     * 也不影响任何既有构造点。
     */
    val userPersonas: List<UserPersona> = emptyList(),
    val sessions: List<Session>,
    val memories: List<MemoryEntity>,
    /** ⚠️ 这里的 [AppSettings.apiKey] **恒为空串** —— 备份里不放密钥，见 [Backup.encode] */
    val settings: AppSettings,
    val profile: UserProfile,
    /**
     * 这份快照的**范围**（v0.53.0）。
     *
     * ⚠️ 它跟着快照走、而不是另设一个参数：**导入时要靠它决定"替换哪几样"** ——
     * 用户导出的只是聊天记录，导入时就不该把人设与设置也换掉。让用户再选一次是危险的
     *（选错 = 把没被备份的那部分清空）。
     */
    val scope: BackupScope = BackupScope.ALL,
)

/**
 * 一次导出 / 导入的**范围**（v0.53.0，用户要求「分项导入导出」）。
 *
 * ⚠️ 分项的意义不是"少占地方"，而是**风险隔离**：只想挪聊天记录的人，
 * 不该被迫连带覆盖人设与设置；只想改模型配置的人，更不该碰聊天记录。
 */
enum class BackupScope {
    ALL,
    CHAT,
    MEMORY,
    CONFIG;

    val includesChat: Boolean get() = this == ALL || this == CHAT
    val includesMemory: Boolean get() = this == ALL || this == MEMORY

    /** 人设与「我」的资料都归"配置" —— 它们不是聊天内容，也不随对话删除。 */
    val includesPersona: Boolean get() = this == ALL || this == CONFIG
    val includesConfig: Boolean get() = this == ALL || this == CONFIG

    /** 给用户看的名字（界面直接用，避免各处自己拼文案）。 */
    val label: String
        get() = when (this) {
            ALL -> "全部数据"
            CHAT -> "聊天记录"
            MEMORY -> "记忆库"
            CONFIG -> "模型配置与人设"
        }

    /** 文件名里用的短标识。 */
    val slug: String
        get() = when (this) {
            ALL -> "all"
            CHAT -> "chat"
            MEMORY -> "memory"
            CONFIG -> "config"
        }

    companion object {
        /** 从字符串还原；认不出来一律当 [ALL]（老备份没有这个字段）。 */
        fun fromName(raw: String?): BackupScope =
            entries.firstOrNull { it.name == raw } ?: ALL
    }
}

/** [Backup.decode] 的结果。用密封类而不是 `Result`：坏在哪一条，要能说清楚。 */
sealed interface BackupDecode {
    data class Ok(val snapshot: BackupSnapshot) : BackupDecode

    /** 文件坏了 / 不是本 App 的备份。文案直接可以展示给用户。 */
    data class Bad(val reason: String) : BackupDecode
}

/**
 * 数据备份的**编解码**（开发文档 §24 风险清单里的「用户数据丢失」）。
 *
 * ## 为什么是 JSON，而不是复制 `yuki.db`
 * 复制数据库文件需要一次**一致性快照**（先 `wal_checkpoint` 或 `VACUUM INTO`），
 * 而且那个文件只有**同一个 schema 版本**才打得开 —— 用户升级一次 App，
 * 备份就恢复不回去了，而他并不知道。
 *
 * JSON 是**应用层**的表示，好处有三个：
 * 1. **跨版本**：不认识的字段忽略、缺的字段用默认值，旧备份在新版本照样能读；
 * 2. **可读**：用户自己打开就能看出"我的对话都在" —— 这本身就是"备份是真的"的证明；
 * 3. **可测**：编解码是纯函数，能在 JVM 上跑往返测试。这一条最关键 ——
 *    备份**唯一能被验证的环节**就是它（见下）。
 *
 * ## ⚠️ 密钥不进备份
 * [AppSettings.apiKey] 在导出时被**替换成空串**。理由：备份文件会被用户
 * 放进网盘、发给自己、拷到 U 盘 —— 让一个"数据备份"顺带泄露 API Key，
 * 是这一类功能最容易犯、后果最贵的错误。恢复时保留本机现有的 Key。
 *
 * ## ⚠️ 图片文件不进备份
 * 头像与背景图是 `filesDir` 下的 JPEG，备份里只有它们的**路径**。
 * 同设备恢复时路径仍然有效；换设备则找不到文件，界面会回落到默认图
 *（`ImageStore.load` 拿不到就返回 null，不崩）。
 *
 * ## ⚠️ 本轮只做到「导出」
 * **恢复**要往库里写（删旧 + 插新），而那一步在这台机器上**无法真机验证** ——
 * 写错的代价恰恰是"用户以为有备份、其实恢复出来是坏的"，比没有备份更糟。
 * 所以：编解码（能测的部分）先交付并测透，写库与界面下一轮做。
 */
object Backup {

    /**
     * 格式版本。
     *
     * 只在**不兼容**地改动结构时才 +1（改名、改语义）。
     * 单纯加字段不用动它 —— 读的时候缺字段会走默认值。
     */
    const val FORMAT_VERSION = 1

    /** 导出文件建议的文件名前缀（界面拼日期用）。 */
    const val FILE_PREFIX = "yuki-backup"

    /**
     * 给系统文件选择器当默认值的文件名，例如 `yuki-backup-20260928-0431.json`。
     *
     * 带时间戳是必要的：用户可能导很多次，同名会让系统自动加 `(1)`，
     * 而"哪个是最新的"就变得要靠人眼比字符串。
     */
    fun suggestedFileName(at: Long, scope: BackupScope = BackupScope.ALL): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(at))
        // ⚠️ 文件名里带**范围**：用户可能在同一分钟内导三个范围（聊天/记忆/配置），
        //    不带范围的话会得到三个 `yuki-backup-20260930-1430.json`，
        //    系统自动加 (1)(2) 之后谁也认不出哪个是哪个。
        return "$FILE_PREFIX-${scope.slug}-$stamp.json"
    }

    /** 缩进过的人类可读格式：备份是给人看的，"打开就能读懂"是它可信的一半。 */
    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    /* ─────────────── 编码 ─────────────── */

    fun encode(s: BackupSnapshot): String = json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("formatVersion", FORMAT_VERSION)
            // v0.53.0：把**范围**写进文件 —— 导入端据此决定"替换哪几样"。
            // 老文件没有这一栏 → 读成 ALL（= 老行为：整份替换）。
            put("scope", s.scope.name)
            put("appVersion", s.appVersion)
            put("exportedAt", s.exportedAt)
            // ⚠️ 只写这次范围内那几段。**缺段在 decode 侧是合法形态**（读成空），
            //    所以分项文件与"全部"文件共用同一条解析路径，不必两套。
            if (s.scope.includesChat) {
                putJsonArray("sessions") { s.sessions.forEach { add(sessionJson(it)) } }
            }
            if (s.scope.includesMemory) {
                putJsonArray("memories") { s.memories.forEach { add(memoryJson(it)) } }
            }
            if (s.scope.includesPersona) {
                putJsonArray("personas") { s.personas.forEach { add(personaJson(it)) } }
                // v0.61.41：用户人设跟着人设走（同一风险域 —— 丢了绑定会指向不存在的 id）
                putJsonArray("userPersonas") { s.userPersonas.forEach { add(userPersonaJson(it)) } }
            }
            if (s.scope.includesConfig) {
                put("settings", settingsJson(s.settings))
                put("profile", buildJsonObject {
                    put("nickname", s.profile.nickname)
                    put("avatarPath", s.profile.avatarPath)
                    // ⚠️ v0.61.21：漏了它 → 恢复后下次启动会判"本地来源≠服务器"
                    //    并重拉头像、覆盖本机那份文件。
                    put("avatarSourceUrl", s.profile.avatarSourceUrl)
                })
            }
        },
    )

    private fun personaJson(p: Persona) = buildJsonObject {
        put("id", p.id)
        put("userNickname", p.userNickname)
        // v0.61.21：AI 生成的简介与指纹 —— 不搬的话恢复备份后要重新花钱生成
        put("detailSummary", p.detailSummary)
        put("detailSummaryKey", p.detailSummaryKey)
        put("userGender", p.userGender)
        put("personality", p.personality)
        put("customPrompt", p.customPrompt)
        put("avatarPath", p.avatarPath)
        put("greeting", p.greeting)
        // ⚠️ v0.61.21：roleName 也漏过一次 —— `Codec.kt` 的 PersonaCodec 搬了它，
        //    这里没搬。导出再导入后角色名会掉回「角色名称：X」/首行/「Ta」兜底。
        put("roleName", p.roleName)
        // ⚠️ 下面三个漏过一次：导出再导入后人设的备注/置顶/表情概率全没了
        put("note", p.note)
        put("isPinned", p.isPinned)
        put("xinchaoEnabled", p.xinchaoEnabled)
        put("cloudMemoryEnabled", p.cloudMemoryEnabled)
        // 「Ta 主动来找我」（v0.61.40）—— 漏搬 = 导出再导入后开关丢失
        put("proactiveEnabled", p.proactiveEnabled)
        // 「记忆方式」（v0.61.48）—— 漏搬 = 导出再导入后记忆方式丢失（选后不可改，丢了就回不去）
        put("memoryMode", p.memoryMode)
        // 绑定的用户人设（v0.61.41）—— 与下面的 userPersonas 段要**成对**搬运才完整
        put("userPersonaId", p.userPersonaId)
        put("emojiChanceOverride", p.emojiChanceOverride)
        put("useGlobalPrefix", p.useGlobalPrefix)
        put("createdAt", p.createdAt)
        put("updatedAt", p.updatedAt)
    }

    private fun sessionJson(s: Session) = buildJsonObject {
        put("id", s.id)
        put("personaId", s.personaId)
        put("title", s.title)
        put("createdAt", s.createdAt)
        put("updatedAt", s.updatedAt)
        put("totalHit", s.totalHit)
        put("totalMiss", s.totalMiss)
        put("muted", s.muted)
        put("pinned", s.pinned)
        put("background", s.background)
        put("thinkingEnabled", s.thinkingEnabled)
        put("reasoningEffort", s.reasoningEffort)
        put("scrimEnabled", s.scrimEnabled)
        put("scrimAlpha", s.scrimAlpha)
        put("scrimStyle", s.scrimStyle)
        // 会话级 provider（v0.51.0）。两者都要备 —— 它们是**用户配过的东西**，
        // 丢了会表现为"恢复备份后，某几段对话的模型选择没了"。
        //
        // ⚠️ `providerGroupId` 指向的是 SharedPreferences 里的分组，而**分组本身不在备份里**
        //    （它是配置，且含 apiKey —— 与 settingsJson 有意不写 apiKey 同一取舍）。
        //    所以导入到**另一台设备**时这个 id 会指向不存在的分组 —— 那是**安全的**：
        //    `ProviderGroups.resolveActive` 找不到就退回第一个分组，不报错。
        put("providerGroupId", s.providerGroupId)
        put("model", s.model)
        // ⚠️ v0.61.21：压缩状态**整组**以前没进备份 —— 恢复后压缩切点丢了，
        //    下一轮会把完整历史重发（前缀断一次、多花钱），看板口径也跟着丢。
        put("summary", s.summary)
        put("summaryUpTo", s.summaryUpTo)
        put("summaryCount", s.summaryCount)
        put("hitAtCompress", s.hitAtCompress)
        put("missAtCompress", s.missAtCompress)
        putJsonArray("messages") { s.messages.forEach { add(messageJson(it)) } }
    }

    private fun messageJson(m: ChatMessage) = buildJsonObject {
        put("role", m.role)
        put("content", m.content)
        putJsonArray("images") { m.images.forEach { add(JsonPrimitive(it)) } }
        put("createdAt", m.createdAt)
        put("reasoning", m.reasoning)
        put("thinkingMs", m.thinkingMs)
        // ⚠️ sendMode 必须**冻结在消息上**（见 Models.kt 的注释）——
        //    漏了它就等于"改一次全局设置，历史气泡全被改写"，用户会当成新 bug。
        put("sendMode", m.sendMode)
        put("emojiPath", m.emojiPath)
        // ⚠️ v0.61.21 补：这两个都**已经落了 Room**（v0.61.6 / v0.61.11），
        //    但备份没搬 —— 恢复后连发被合并、重新生成的历史版本全没。
        put("splitBubbles", m.splitBubbles)
        putJsonArray("superseded") { m.superseded.forEach { add(JsonPrimitive(it)) } }
    }

    private fun memoryJson(m: MemoryEntity) = buildJsonObject {
        put("id", m.id)
        put("userId", m.userId)
        put("personaId", m.personaId)
        put("sessionId", m.sessionId)
        put("scope", m.scope)
        put("content", m.content)
        put("category", m.category)
        put("importance", m.importance)
        put("source", m.source)
        put("createdAt", m.createdAt)
        put("lastAccessedAt", m.lastAccessedAt)
        // embedding / expiresAt 当前恒为 null（见 MemoryEntity 的类注释），不进备份
    }

    private fun settingsJson(s: AppSettings) = buildJsonObject {
        // ⚠️ apiKey **有意不写**。备份会被拷来拷去，密钥不该跟着走。
        put("baseUrl", s.baseUrl)
        put("model", s.model)
        put("thinkingEnabled", s.thinkingEnabled)
        put("reasoningEffort", s.reasoningEffort)
        put("globalPrefixEnabled", s.globalPrefixEnabled)
        put("globalPrefix", s.globalPrefix)
        put("autoMemoryEnabled", s.autoMemoryEnabled)
        put("thinkingCollapseEnabled", s.thinkingCollapseEnabled)
        put("serverUrl", s.serverUrl)
        put("telemetryEnabled", s.telemetryEnabled)
        put("typeSpeed", s.typeSpeed)
        put("sendMode", s.sendMode)
        put("enterToSend", s.enterToSend)
        put("fontScale", s.fontScale)
        put("emojiEnabled", s.emojiEnabled)
        put("emojiChance", s.emojiChance)
        // ⚠️ 压缩的触发方式与阈值：漏了之后用户"压缩改成手动"的设定会悄悄退回默认
        put("compressMode", s.compressMode)
        put("compressThreshold", s.compressThreshold)
        // ⚠️ v0.61.21：以前没进备份 —— 恢复一份 ALL 备份会把它静默重置为默认 true，
        //    用户"关掉连发"的设置会自己打开。
        put("splitBubbles", s.splitBubbles)
    }

    /* ─────────────── 解码 ─────────────── */

    /**
     * 解析一份备份。
     *
     * **任一环节不明白就整体拒绝**（不是"跳过坏的那条"）—— 因为它的下游是"写回数据库"，
     * 而"导入了一半"是比"导入失败"更难收拾的状态。
     */
    fun decode(text: String): BackupDecode {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }
            .getOrElse { return BackupDecode.Bad("这不是有效的 JSON 文件（${it.message}）") }

        val version = root.i("formatVersion")
            ?: return BackupDecode.Bad(
                "文件里没有 formatVersion —— 它可能不是「Yuki 初雪」导出的备份。",
            )
        if (version > FORMAT_VERSION) {
            return BackupDecode.Bad(
                "这份备份来自更新的版本（格式 $version，本机只认到 $FORMAT_VERSION）。请先升级 App。",
            )
        }

        // ⚠️ v0.61.21：**空壳文件要拦下**。
        //
        // 「缺某一段」是**合法**形态（分项文件只写自己那几段；将来加字段时旧备份也缺段），
        // 有一条老测试专门钉着"缺字段的旧备份走默认值"，所以这里**不能**要求段段齐全。
        //
        // 但"scope=ALL 却**一个内容段都没有**"只可能来自截断/手改的文件 ——
        // 而 applySnapshot 对 ALL 是**无条件整份替换**（先 DELETE 全表）。
        // 也就是说：`{"formatVersion":1}` 这种文件会让用户"恢复"成一片空白，**还不报错**。
        // 宁可说"这份备份不完整"，也不能让一次导入把数据静默清空。
        val declaredScope = BackupScope.fromName(root.s("scope"))
        val contentSections = listOf("sessions", "memories", "personas", "settings", "profile")
        val present = contentSections.count { root.containsKey(it) }
        if (declaredScope == BackupScope.ALL && present == 0) {
            return BackupDecode.Bad(
                "这份备份里没有任何数据段（只有格式版本）—— 它多半是在传输中被截断的文件。" +
                    "为了不覆盖你本机现有的数据，这次导入没有执行。",
            )
        }

        return runCatching {
            BackupDecode.Ok(
                BackupSnapshot(
                    appVersion = root.s("appVersion").orEmpty(),
                    exportedAt = root.l("exportedAt") ?: 0L,
                    personas = root.arr("personas").map { persona(it.jsonObject) },
                    // 用户人设（v0.61.41）：老备份缺这一栏 → 空表（合法形态，同其它缺段）
                    userPersonas = root.arr("userPersonas").map { userPersona(it.jsonObject) },
                    sessions = root.arr("sessions").map { session(it.jsonObject) },
                    memories = root.arr("memories").map { memory(it.jsonObject) },
                    settings = settings(root["settings"]?.jsonObject),
                    profile = root["profile"]?.jsonObject?.let { p ->
                        UserProfile(
                            nickname = p.s("nickname").orEmpty(),
                            avatarPath = p.s("avatarPath"),
                            avatarSourceUrl = p.s("avatarSourceUrl"),
                        )
                    } ?: UserProfile(),
                    // 老备份没这一栏 → ALL（整份替换，与它们导出时的事实一致）
                    scope = BackupScope.fromName(root.s("scope")),
                ),
            )
        }.getOrElse { return BackupDecode.Bad("备份内容有问题：${it.message}") }
    }

    /**
     * 把一份**来自外部文件**的备份整理成"可以安全写进库"的形状。
     *
     * ## 为什么必须有这一步（不是客套的防御性编程）
     * 备份文件是用户从文件管理器里选的 JSON —— 它可能被手改过、可能来自别的版本、
     * 也可能是别人给的。其中两类内容会**直接让写入失败或数据错乱**：
     *
     * 1. **`id` 为空的会话** —— `sessions.id` 是主键，两条空 id 会撞车，
     *    而 `@Upsert` 是静默覆盖：两条会话只剩一条，用户不会收到任何提示；
     * 2. **指向不存在会话的记忆** —— `memories.sessionId` 有外键约束指向 `sessions`，
     *    指向不存在的行会让**整个事务**抛异常。事务回滚意味着
     *    "用户点了导入、转一圈、什么都没发生"，而且看不懂为什么。
     *
     * 处理方式是**丢掉坏的、留下好的**，不是整体拒绝：一份 99% 完好的备份因为一条脏数据
     * 而完全不能用，对用户是更难接受的失败。
     *
     * ⚠️ 注意与 [decode] 的分工：那里是**严格拒绝**（判断"这文件根本不是备份"），
     * 这里是**宽进严出**（确认是备份之后，把脏条目拣出去并**如实汇报数量**）。
     */
    fun sanitize(s: BackupSnapshot): Sanitized {
        // ⚠️ v0.61.21：**非空但重复**的 id 在 `replaceAll`（@Upsert）下同样会撞车、
        //    静默覆盖 —— 原来只过滤了"空 id"，KDoc 却把风险归因于撞车。
        //    改成按 id 去重（保留第一条），于是"同 id 两条"不再有一条被悄悄吃掉。
        val seen = HashSet<String>()
        val sessions = s.sessions.filter { it.id.isNotBlank() && seen.add(it.id) }
        val sessionIds = sessions.map { it.id }.toSet()
        val memories = s.memories.filter { m ->
            m.id.isNotBlank() &&
                m.personaId.isNotBlank() &&
                // 允许 null（理论上的跨会话记忆），但**不允许指向一个不存在的会话**
                (m.sessionId == null || m.sessionId in sessionIds)
        }
        return Sanitized(
            snapshot = s.copy(sessions = sessions, memories = memories),
            droppedSessions = s.sessions.size - sessions.size,
            droppedMemories = s.memories.size - memories.size,
        )
    }

    /** [sanitize] 的结果：整理后的快照 + **丢了几条**（要如实告诉用户，不能悄悄丢）。 */
    data class Sanitized(
        val snapshot: BackupSnapshot,
        val droppedSessions: Int,
        val droppedMemories: Int,
    ) {
        val droppedAnything: Boolean get() = droppedSessions > 0 || droppedMemories > 0
    }

    private fun persona(o: JsonObject) = Persona(
        id = o.s("id").orEmpty(),
            userNickname = o.s("userNickname").orEmpty(),
            detailSummary = o.s("detailSummary")?.takeIf { it.isNotBlank() },
            detailSummaryKey = o.s("detailSummaryKey")?.takeIf { it.isNotBlank() },
        userGender = o.s("userGender").orEmpty(),
        personality = o.s("personality"),
        customPrompt = o.s("customPrompt").orEmpty(),
        avatarPath = o.s("avatarPath"),
        greeting = o.s("greeting"),
        roleName = o.s("roleName").orEmpty(),
        note = o.s("note").orEmpty(),
        isPinned = o.b("isPinned") ?: false,
        xinchaoEnabled = o.b("xinchaoEnabled") ?: false,
        cloudMemoryEnabled = o.b("cloudMemoryEnabled") ?: false,
        // 「Ta 主动来找我」：老备份里没有这一栏 → false（不取件）
        proactiveEnabled = o.b("proactiveEnabled") ?: false,
        // 「记忆方式」（v0.61.48）：老备份缺栏 → null（= 本地）
        memoryMode = o.s("memoryMode")?.takeIf { it.isNotBlank() },
        // 绑定的用户人设：缺栏 / null / 空串 → null（= 没绑定）
        userPersonaId = o.s("userPersonaId")?.takeIf { it.isNotBlank() },
        emojiChanceOverride = o.f("emojiChanceOverride"),
        // ⚠️ 不给默认值：老备份里没有这一栏 → null → 回落 settings.globalPrefixEnabled
        //（= 导出时的行为）。见 Persona.useGlobalPrefix 的三态语义。
        useGlobalPrefix = o.b("useGlobalPrefix"),
        createdAt = o.l("createdAt") ?: 0L,
        updatedAt = o.l("updatedAt") ?: 0L,
    )

    private fun userPersonaJson(u: UserPersona) = buildJsonObject {
        put("id", u.id)
        put("name", u.name)
        put("roleText", u.roleText)
        put("note", u.note)
        put("createdAt", u.createdAt)
        put("updatedAt", u.updatedAt)
    }

    private fun userPersona(o: JsonObject) = UserPersona(
        id = o.s("id").orEmpty(),
        name = o.s("name").orEmpty(),
        roleText = o.s("roleText").orEmpty(),
        note = o.s("note").orEmpty(),
        createdAt = o.l("createdAt") ?: 0L,
        updatedAt = o.l("updatedAt") ?: 0L,
    )

    private fun session(o: JsonObject) = Session(
        id = o.s("id").orEmpty(),
        personaId = o.s("personaId").orEmpty(),
        title = o.s("title").orEmpty().ifBlank { "新的对话" },
        messages = o.arr("messages").map { message(it.jsonObject) },
        createdAt = o.l("createdAt") ?: 0L,
        updatedAt = o.l("updatedAt") ?: 0L,
        totalHit = o.i("totalHit") ?: 0,
        totalMiss = o.i("totalMiss") ?: 0,
        muted = o.b("muted") ?: false,
        pinned = o.b("pinned") ?: false,
        background = o.s("background"),
        thinkingEnabled = o.b("thinkingEnabled") ?: true,
        reasoningEffort = o.s("reasoningEffort") ?: "high",
        scrimEnabled = o.b("scrimEnabled") ?: true,
        scrimAlpha = o.f("scrimAlpha") ?: 0.55f,
        scrimStyle = o.s("scrimStyle") ?: "plain",
        // v0.51.0：照旧读不到就是 null（= 跟着全局），与"从没设过"同一语义。
        // ⚠️ **不使用 `?: ""` 兜底** —— 空串会让"没选过"与"选了空模型"混为一谈。
        providerGroupId = o.s("providerGroupId"),
        model = o.s("model"),
        // v0.61.21：压缩状态（缺字段 → 默认值，与本字段存在之前的导入行为一致）
        summary = o.s("summary"),
        summaryUpTo = o.l("summaryUpTo") ?: 0L,
        summaryCount = o.i("summaryCount") ?: 0,
        hitAtCompress = o.i("hitAtCompress") ?: 0,
        missAtCompress = o.i("missAtCompress") ?: 0,
    )

    private fun message(o: JsonObject) = ChatMessage(
        role = o.s("role").orEmpty(),
        content = o.s("content").orEmpty(),
        images = o.arr("images").mapNotNull { it.asStringOrNull() },
        createdAt = o.l("createdAt") ?: 0L,
        reasoning = o.s("reasoning"),
        thinkingMs = o.l("thinkingMs"),
        sendMode = o.s("sendMode"),
        emojiPath = o.s("emojiPath"),
        // v0.61.21：缺字段 → null/空表，语义与"该字段存在之前的消息"一致
        splitBubbles = o.b("splitBubbles"),
        superseded = o.arr("superseded").mapNotNull { it.asStringOrNull() },
    )

    private fun memory(o: JsonObject) = MemoryEntity(
        id = o.s("id").orEmpty(),
        userId = o.s("userId").orEmpty().ifBlank { MemoryEntity.LOCAL_USER_ID },
        personaId = o.s("personaId").orEmpty(),
        sessionId = o.s("sessionId"),
        scope = o.s("scope").orEmpty().ifBlank { MemoryEntity.SCOPE_SESSION },
        content = o.s("content").orEmpty(),
        category = o.s("category").orEmpty(),
        importance = o.i("importance") ?: 5,
        source = o.s("source").orEmpty().ifBlank { "manual" },
        embedding = null,
        createdAt = o.l("createdAt") ?: 0L,
        lastAccessedAt = o.l("lastAccessedAt") ?: (o.l("createdAt") ?: 0L),
        expiresAt = null,
    )

    private fun settings(o: JsonObject?): AppSettings {
        // 没有 settings 段（或段里什么都没有）就给一份默认 —— 备份只带了对话也是合法的
        if (o == null) return AppSettings()
        val base = AppSettings()
        return base.copy(
            // ⚠️ apiKey 不读：一律留空，恢复时沿用本机现有的密钥
            apiKey = "",
            baseUrl = o.s("baseUrl") ?: base.baseUrl,
            model = o.s("model") ?: base.model,
            thinkingEnabled = o.b("thinkingEnabled") ?: base.thinkingEnabled,
            reasoningEffort = o.s("reasoningEffort") ?: base.reasoningEffort,
            globalPrefixEnabled = o.b("globalPrefixEnabled") ?: base.globalPrefixEnabled,
            globalPrefix = o.s("globalPrefix") ?: base.globalPrefix,
            autoMemoryEnabled = o.b("autoMemoryEnabled") ?: base.autoMemoryEnabled,
            thinkingCollapseEnabled = o.b("thinkingCollapseEnabled") ?: base.thinkingCollapseEnabled,
            serverUrl = o.s("serverUrl") ?: base.serverUrl,
            telemetryEnabled = o.b("telemetryEnabled") ?: base.telemetryEnabled,
            typeSpeed = o.i("typeSpeed") ?: base.typeSpeed,
            sendMode = o.s("sendMode") ?: base.sendMode,
            enterToSend = o.b("enterToSend") ?: base.enterToSend,
            fontScale = o.f("fontScale") ?: base.fontScale,
            emojiEnabled = o.b("emojiEnabled") ?: base.emojiEnabled,
            emojiChance = o.f("emojiChance") ?: base.emojiChance,
            compressMode = o.s("compressMode") ?: base.compressMode,
            compressThreshold = o.f("compressThreshold") ?: base.compressThreshold,
            // v0.61.21：缺字段 → base（= 老备份导入后与"本字段存在之前"一致）
            splitBubbles = o.b("splitBubbles") ?: base.splitBubbles,
        )
    }

    /* ─────────────── 读 JSON 的小工具 ───────────────
       全部**拿不到就返回 null**，不抛异常 —— 缺字段是"旧备份"的正常形态，
       该由各自的默认值兜住，而不是让整个文件解析失败。 */

    private fun JsonObject.prim(k: String): JsonPrimitive? =
        (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }

    private fun JsonObject.s(k: String): String? = prim(k)?.contentOrNull

    private fun JsonObject.i(k: String): Int? = s(k)?.toIntOrNull()

    private fun JsonObject.l(k: String): Long? = s(k)?.toLongOrNull()

    private fun JsonObject.f(k: String): Float? = s(k)?.toFloatOrNull()

    private fun JsonObject.b(k: String): Boolean? = s(k)?.toBooleanStrictOrNull()

    private fun JsonObject.arr(k: String): List<JsonElement> = (this[k] as? JsonArray) ?: emptyList()

    private fun JsonElement.asStringOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull
}
