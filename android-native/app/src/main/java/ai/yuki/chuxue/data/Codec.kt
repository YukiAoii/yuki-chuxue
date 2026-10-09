package ai.yuki.chuxue.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/**
 * 会话与人设的编解码 —— **纯函数，无平台依赖**，可直接单元测试。
 *
 * ══ 为什么必须把它从 Store 里抽出来 ══
 * 旧实现把编解码埋在一个依赖 Context 的类里，且解码失败时这样写：
 *
 *     return runCatching { decodeAll(raw) }.getOrElse { emptyList() }
 *
 * 后果：**解析一旦失败，应用看到的是「一个会话都没有」，而不是「解析出错」。**
 * 这正是用户报「关闭 App 后对话全部丢失」的头号嫌疑 —— 数据可能还在文件里，
 * 只是解码失败被静默吞掉了。
 *
 * 因此本文件的纪律是：**解码失败必须抛异常**，由上层决定是重试、备份还是报错，
 * 绝不静默返回空集合。
 */
object SessionCodec {

    fun encode(sessions: List<Session>): String = StableJson.encode(
        sessions.map { s ->
            linkedMapOf<String, Any?>(
                "id" to s.id,
                "personaId" to s.personaId,
                "title" to s.title,
                "createdAt" to s.createdAt,
                "updatedAt" to s.updatedAt,
                "totalHit" to s.totalHit,
                "totalMiss" to s.totalMiss,
                "messages" to s.messages.map { m ->
                    linkedMapOf<String, Any?>(
                        "role" to m.role,
                        "content" to m.content,
                        // 空列表不写字段：保持与旧数据格式兼容，也避免无谓的字节
                        "images" to m.images.takeIf { it.isNotEmpty() },
                    )
                },
            )
        },
    )

    /** @throws IllegalArgumentException 当 JSON 结构不符合预期（**故意不吞掉**） */
    fun decode(raw: String): List<Session> {
        if (raw.isBlank()) return emptyList()
        val arr = Json.parseToJsonElement(raw).jsonArray
        return arr.map { decodeOne(it) }
    }

    private fun decodeOne(el: JsonElement): Session {
        val o = el.jsonObject
        return Session(
            id = o.requireString("id"),
            personaId = o["personaId"]?.jsonPrimitive?.content ?: "",
            title = o["title"]?.jsonPrimitive?.content ?: "对话",
            createdAt = o["createdAt"]?.jsonPrimitive?.longOrNull ?: 0L,
            updatedAt = o["updatedAt"]?.jsonPrimitive?.longOrNull ?: 0L,
            totalHit = o["totalHit"]?.jsonPrimitive?.intOrNull ?: 0,
            totalMiss = o["totalMiss"]?.jsonPrimitive?.intOrNull ?: 0,
            messages = o["messages"]?.jsonArray?.map { m ->
                val mo = m.jsonObject
                ChatMessage(
                    role = mo.requireString("role"),
                    content = mo.requireString("content"),
                    images = mo["images"]?.jsonArray?.mapNotNull { img ->
                        runCatching { img.jsonPrimitive.content }.getOrNull()
                    } ?: emptyList(),
                )
            } ?: emptyList(),
        )
    }
}

/** 人设的编解码，纪律同上：失败抛异常。 */
object PersonaCodec {

    /**
     * @param avatars `personaId → base64(小 JPEG)`（v0.61.21）。
     *   ⚠️ **只给云端快照用**：本地存储传空表，于是本地那份 JSON **逐字节不变**
     *  （没有头像的人设不会多出 `"avatarData":null` 这种噪音）。
     *   为什么头像要进快照：快照里原本只搬 `avatarPath`，那是**本机文件路径** ——
     *   换台设备指向一个不存在的文件，头像必然丢。
     */
    fun encode(personas: List<Persona>, avatars: Map<String, String> = emptyMap()): String = StableJson.encode(
        personas.map { p ->
            linkedMapOf<String, Any?>(
                "id" to p.id,
                "userNickname" to p.userNickname,
                "userGender" to p.userGender,
                "personality" to p.personality,
                "customPrompt" to p.customPrompt,
                // 角色名称（v0.61.10）—— 单独填的展示名。
                // ⚠️ 不进**人设前缀**（PromptEngine 的人设段落不读它），但它会经开场白里的
                //    `{persona_name}` 影响**新建会话**的首条消息 —— 详见 Persona.roleName 的
                //    KDoc（别把这里写成"哪儿都不进"）
                "roleName" to p.roleName,
                "avatarPath" to p.avatarPath,
                // AI 生成的详情页简介与其输入指纹（v0.61.21）——
                // ⚠️ 必须跟着人设一起存/同步：不存的话每次启动都要重新花钱生成一遍
                "detailSummary" to p.detailSummary,
                "detailSummaryKey" to p.detailSummaryKey,
                "greeting" to p.greeting,
                // 可空的 Float：`StableJson` 与本表本来就吃得下 null
                //（personality / avatarPath / greeting 都是 String?），所以直接塞。
                "emojiChanceOverride" to p.emojiChanceOverride,
                // 是否使用「通用设定」（全局前缀）—— v0.53.0，默认关
                "useGlobalPrefix" to p.useGlobalPrefix,
                // 备注（v0.56.0）—— **纯粹给人看的**，不进任何提示词、不影响缓存
                "note" to p.note,
                // 人设列表的置顶（与会话列表的 `Session.pinned` 是两件事）
                "isPinned" to p.isPinned,
                // 「Ta 的状态」开关（默认关；老数据缺栏 → 读成 false，零上报）
                "xinchaoEnabled" to p.xinchaoEnabled,
                "cloudMemoryEnabled" to p.cloudMemoryEnabled,
                // 「Ta 主动来找我」（v0.61.40）—— 依附 xinchaoEnabled，默认关
                "proactiveEnabled" to p.proactiveEnabled,
                // 「记忆方式」（v0.61.48）—— local / cloud / null（老数据 = 本地，三项云端功能关）
                "memoryMode" to p.memoryMode,
                // 绑定的用户人设（v0.61.41）—— null = 没绑定
                "userPersonaId" to p.userPersonaId,
                "createdAt" to p.createdAt,
                "updatedAt" to p.updatedAt,
            ).apply {
                // 只有真带头像时才多这一栏 —— 没头像的人设本地 JSON 因此一个字节都不变
                avatars[p.id]?.let { put("avatarData", it) }
            }
        },
    )

    /**
     * 只取快照里内嵌的头像（`id → base64`）。
     *
     * ⚠️ 刻意**单独一个函数**，而不是让 [decode] 返回一个复合体：那会动到所有既有调用方
     *（本地存储、备份导出、测试），而它们根本不需要头像。
     * 坏数据一律降级成空表 —— 头像拿不到最多是显示兜底图，不该让整次同步失败。
     */
    fun decodeAvatars(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return runCatching {
            Json.parseToJsonElement(raw).jsonArray.mapNotNull { el ->
                val o = el.jsonObject
                // ⚠️ 用 `.content`（与同文件其它取值一致），不用 `contentOrNull`
                //    —— 后者在本项目这个 kotlinx-serialization 版本里没被引入。
                //    `.content` 对 JsonNull 会抛，所以先显式挡掉这两种情形。
                val idEl = o["id"]?.takeIf { it !is JsonNull } ?: return@mapNotNull null
                val dataEl = o["avatarData"]?.takeIf { it !is JsonNull } ?: return@mapNotNull null
                val id = idEl.jsonPrimitive.content
                val data = dataEl.jsonPrimitive.content
                if (id.isBlank() || data.isBlank()) return@mapNotNull null
                id to data
            }.toMap()
        }.getOrElse { emptyMap() }
    }

    /** @throws IllegalArgumentException 当 JSON 结构不符合预期 */
    fun decode(raw: String): List<Persona> {
        if (raw.isBlank()) return emptyList()
        val arr = Json.parseToJsonElement(raw).jsonArray
        return arr.map { decodeOne(it) }
    }

    private fun decodeOne(el: JsonElement): Persona {
        val o = el.jsonObject
        return Persona(
            id = o.requireString("id"),
            userNickname = o["userNickname"]?.jsonPrimitive?.content ?: "",
            userGender = o["userGender"]?.jsonPrimitive?.content ?: "",
            personality = o["personality"]?.jsonPrimitive?.content,
            customPrompt = o["customPrompt"]?.jsonPrimitive?.content ?: "",
            // 角色名称（v0.61.10）：老数据没有这一栏 → 空串 = 走老路径
            //（displayName 依次回退「角色名称：X」→ 首行 → Ta，老用户显示一个字都不变）
            roleName = o["roleName"]?.jsonPrimitive?.content ?: "",
            avatarPath = o["avatarPath"]?.jsonPrimitive?.content,
            // 老数据没有这两栏 → null = "待生成"，界面对它必须"没有就不显示"
            detailSummary = o["detailSummary"]?.jsonPrimitive?.content,
            detailSummaryKey = o["detailSummaryKey"]?.jsonPrimitive?.content,
            greeting = o["greeting"]?.jsonPrimitive?.content,
            // 缺字段、或存的本来就是 null → 都读成"没配过"（用全局默认）。
            // 用 floatOrNull 而不是 content.toFloat()：null 的 content 会给出字符串 "null"，
            // 那样 toFloat() 会抛 —— 一个空字段不该让整份人设读不出来。
            emojiChanceOverride = o["emojiChanceOverride"]?.jsonPrimitive?.floatOrNull,
            // ⚠️ **不要**给 `?: false` 兜底：老数据没有这一栏时要读成 null，
            // 那样才会回落到 settings.globalPrefixEnabled（= 升级前行为）——
            // 兜成 false 会让老用户的前缀凭空消失、缓存全碎。
            useGlobalPrefix = o["useGlobalPrefix"]?.jsonPrimitive?.booleanOrNull,
            // 备注（v0.56.0）：老数据没有这一栏 → 空串（就是"没写备注"）
            note = o["note"]?.jsonPrimitive?.content ?: "",
            // 老数据没有这一栏 → 读成 false，与加这个字段之前的行为完全一致
            isPinned = o["isPinned"]?.jsonPrimitive?.booleanOrNull ?: false,
            // 「Ta 的状态」开关：老数据缺栏读成 false（= 不开，零上报）
            xinchaoEnabled = o["xinchaoEnabled"]?.jsonPrimitive?.booleanOrNull ?: false,
            cloudMemoryEnabled = o["cloudMemoryEnabled"]?.jsonPrimitive?.booleanOrNull ?: false,
            // 「Ta 主动来找我」：老数据缺栏读成 false（= 不取件，行为与升级前一致）
            proactiveEnabled = o["proactiveEnabled"]?.jsonPrimitive?.booleanOrNull ?: false,
            // 「记忆方式」（v0.61.48）：缺栏 / null → null（= 老数据，按本地处理、三项云端功能关）
            memoryMode = o["memoryMode"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
            // 绑定的用户人设：缺栏 / null / 空串 → 一律 null（= 没绑定，不注入）
            userPersonaId = o["userPersonaId"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
            createdAt = o["createdAt"]?.jsonPrimitive?.longOrNull ?: 0L,
            updatedAt = o["updatedAt"]?.jsonPrimitive?.longOrNull ?: 0L,
        )
    }
}

/**
 * 用户人设的编解码，纪律同 [PersonaCodec]：失败抛异常
 *（由 Store 转成"读取失败 + 原始数据备份"，绝不静默覆盖用户数据）。
 */
object UserPersonaCodec {

    fun encode(list: List<UserPersona>): String = StableJson.encode(
        list.map { u ->
            linkedMapOf<String, Any?>(
                "id" to u.id,
                "name" to u.name,
                // 核心字段：进冻结前缀的自由文本（「你在这个角色里是谁」）
                "roleText" to u.roleText,
                // 备注 —— 纯粹给人看的，不进任何提示词
                "note" to u.note,
                "createdAt" to u.createdAt,
                "updatedAt" to u.updatedAt,
            )
        },
    )

    /** @throws IllegalArgumentException 当 JSON 结构不符合预期 */
    fun decode(raw: String): List<UserPersona> {
        if (raw.isBlank()) return emptyList()
        val arr = Json.parseToJsonElement(raw).jsonArray
        return arr.map { el ->
            val o = el.jsonObject
            UserPersona(
                id = o.requireString("id"),
                name = o["name"]?.jsonPrimitive?.content ?: "",
                roleText = o["roleText"]?.jsonPrimitive?.content ?: "",
                note = o["note"]?.jsonPrimitive?.content ?: "",
                createdAt = o["createdAt"]?.jsonPrimitive?.longOrNull ?: 0L,
                updatedAt = o["updatedAt"]?.jsonPrimitive?.longOrNull ?: 0L,
            )
        }
    }
}

/** 缺少必需字段时抛出明确异常，而不是悄悄给个 null 让问题往下游漂 */
private fun JsonObject.requireString(key: String): String =
    this[key]?.jsonPrimitive?.content
        ?: throw IllegalArgumentException("缺少必需字段 '$key'")
