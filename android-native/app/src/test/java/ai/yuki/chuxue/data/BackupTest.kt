package ai.yuki.chuxue.data

import ai.yuki.chuxue.data.room.MemoryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份编解码的契约。
 *
 * ## 这一组测试为什么是"备份功能"里最重要的一段
 * 「备份」的失败长相很难看：文件导出成功、用户以为稳了，**恢复时才发现少了半截**。
 * 而恢复那一步（往库里写）在本机没法真机验证 —— 所以**编解码的往返**就是
 * 这里唯一能拿证据的环节：**导出的东西必须一个字段不差地读回来**。
 *
 * 每个字段都得在样例里出现一次，否则"漏了个字段"这种最典型的错就抓不到。
 */
class BackupTest {

    /** 一份**把每个字段都填上**的样例 —— 刻意不用默认值，默认值会让漏字段看不出来。 */
    private fun sample(apiKey: String = "") = BackupSnapshot(
        appVersion = "0.33.0",
        exportedAt = 1_759_000_000_000L,
        personas = listOf(
            Persona(
                id = "p1",
                userNickname = "阿澈",
                userGender = "男",
                personality = "温柔、话少",
                customPrompt = "角色名称：初雪\n年龄：22\n职业：咖啡师",
                avatarPath = "/data/user/0/ai.yuki.chuxue/files/avatars/p1_111.jpg",
                greeting = "你来了。",
                // ⚠️ v0.61.21：roleName 以前**没进备份**（Codec 搬了它、Backup 没搬），
                //    导出再导入后角色名会掉回「角色名称：X」/首行/「Ta」兜底。
                roleName = "小雪",
                // ⚠️ 这三个以前没设，于是"备份漏搬运它们"测不出来（假绿）
                note = "这是我自己看的备注",
                isPinned = true,
                emojiChanceOverride = 0.33f,
                // v0.61.34：「Ta 的状态」开关也必须在样例里出现一次（同一条纪律：
                // 默认值会让"Backup 漏搬这一栏"测不出来）
                xinchaoEnabled = true,
                // v0.61.40：「Ta 主动来找我」同理（Backup 的 encode/decode 是**独立的一处**，
                // Codec 绿了不代表它绿 —— 历史踩过多次）
                proactiveEnabled = true,
                // v0.61.41：用户人设绑定同理（同一个坑的第四次预防）
                userPersonaId = "up1",
                createdAt = 1_700_000_000_000L,
                updatedAt = 1_700_000_001_000L,
            ),
            // 第二个刻意留空可选字段：确认 null 能原样往返（不是被写成空串）
            Persona(id = "p2", userNickname = "我", userGender = "女", customPrompt = "角色名称：L"),
        ),
        sessions = listOf(
            Session(
                id = "s1",
                personaId = "p1",
                title = "初雪 · 阿澈",
                messages = listOf(
                    ChatMessage(
                        role = "assistant",
                        content = "你来了。",
                        createdAt = 1_700_000_002_000L,
                    ),
                    ChatMessage(
                        role = "user",
                        content = "嗯，今天很累。",
                        images = listOf("data:image/jpeg;base64,AAAABBBB"),
                        // ⚠️ sendMode 必须冻结在消息上；emojiPath 是"退出重进丢表情包"的修复
                        sendMode = SEND_MODE_INSTANT,
                        emojiPath = "/data/user/0/ai.yuki.chuxue/files/emoji/a.png",
                        // ⚠️ v0.61.21：这两个以前**没进备份**。splitBubbles 落了 Room（v0.61.6）、
                        //    superseded 也落了（v0.61.11），但 Backup 没搬 ——
                        //    恢复后连发被合并、重新生成的历史版本全没。
                        splitBubbles = false,
                        superseded = listOf("旧版本：他今天话少。"),
                        createdAt = 1_700_000_003_000L,
                    ),
                    ChatMessage(
                        role = "assistant",
                        content = "那就先坐下。",
                        createdAt = 1_700_000_004_000L,
                        reasoning = "他今天话少，先别问。",
                        thinkingMs = 4200L,
                    ),
                ),
                createdAt = 1_700_000_000_000L,
                updatedAt = 1_700_000_005_000L,
                totalHit = 12_345,
                totalMiss = 678,
                muted = true,
                pinned = true,
                background = "file:/data/user/0/ai.yuki.chuxue/files/backgrounds/bg_9.jpg",
                thinkingEnabled = false,
                reasoningEffort = "max",
                scrimEnabled = false,
                scrimAlpha = 0.72f,
                scrimStyle = "liquid",
                // ⚠️ v0.51.0 新增字段必须用**非默认值**参与往返 ——
                //    本项目栽过"缺失==缺失照样绿"的假绿（见 HANDOFF 坑 #5）：
                //    若两边都不设，断言会通过，而真实用户的值其实在备份里丢了。
                providerGroupId = "grp-9",
                model = "qwen-max",
                // ⚠️ v0.61.21：压缩状态以前**整组没进备份** —— 恢复后压缩切点丢失，
                //    下一轮会把完整历史重发（前缀断一次、多花钱），看板口径也丢。
                summary = "前面聊过：他养了一只叫团子的猫。",
                summaryUpTo = 1234L,
                summaryCount = 3,
                hitAtCompress = 111,
                missAtCompress = 222,
            ),
        ),
        memories = listOf(
            MemoryEntity(
                id = "m1",
                userId = MemoryEntity.LOCAL_USER_ID,
                personaId = "p1",
                sessionId = "s1",
                scope = MemoryEntity.SCOPE_SESSION,
                content = "他养了一只叫团子的猫",
                category = "喜好",
                importance = 8,
                source = "auto_summary",
                embedding = null,
                createdAt = 1_700_000_006_000L,
                lastAccessedAt = 1_700_000_007_000L,
                expiresAt = null,
            ),
        ),
        settings = AppSettings(
            apiKey = apiKey,
            baseUrl = "https://api.deepseek.com",
            model = "deepseek-flash",
            thinkingEnabled = false,
            reasoningEffort = "low",
            globalPrefixEnabled = false,
            globalPrefix = "# 全局规则\n- 别承认自己是 AI",
            autoMemoryEnabled = false,
            thinkingCollapseEnabled = false,
            serverUrl = "https://example.test/yuki",
            telemetryEnabled = false,
            typeSpeed = TYPE_SPEED_FAST,
            sendMode = SEND_MODE_INSTANT,
            enterToSend = false,
            // 刻意用非默认值：默认值会让"漏搬运这个字段"这种错看不出来
            fontScale = FONT_SCALE_XLARGE,
            compressMode = COMPRESS_MODE_MANUAL,
            compressThreshold = 0.42f,
            // ⚠️ v0.61.21：以前没进备份，恢复一份 ALL 备份会把它静默重置为默认 true
            splitBubbles = false,
        ),
        profile = UserProfile(
            nickname = "阿澈",
            avatarPath = "/data/user/0/ai.yuki.chuxue/files/avatars/me.jpg",
            // ⚠️ v0.61.21：以前没进备份 —— 恢复后下次启动会判"本地来源≠服务器"
            //    并重拉头像、覆盖本机文件
            avatarSourceUrl = "https://cdn.example.test/me.jpg",
        ),
        // v0.61.41：用户人设（新实体）也必须在样例里出现 —— 默认空表会让
        // "备份漏搬 userPersonas" 永远绿（与上面 xinchaoEnabled 同一条纪律）
        userPersonas = listOf(
            UserPersona(
                id = "up1",
                name = "快刀",
                roleText = "江湖上人称「快刀」的刀客，说话利落、讲义气。",
                note = "给初雪用",
                createdAt = 1_700_000_010_000L,
                updatedAt = 1_700_000_011_000L,
            ),
        ),
    )

    @Test
    fun `往返之后每一个字段都还在`() {
        val encoded = Backup.encode(sample())
        val decoded = Backup.decode(encoded)
        assertTrue("解码应当成功，实际：$decoded", decoded is BackupDecode.Ok)
        // 整对象比对：任何一个字段漏进/漏出都会红 —— 这正是这一组测试存在的理由
        assertEquals(sample(), (decoded as BackupDecode.Ok).snapshot)
    }

    /* ─────────── v0.61.21 体检抓出来的两处真缺陷 ─────────── */

    @Test
    fun `scope=ALL 但内容段缺失的备份必须被拒 —— 否则会静默清库`() {
        // 极端输入：只有 formatVersion。decode 若判它 Ok(scope=ALL)，
        // ChatViewModel.applySnapshot 就会无条件覆盖人设/设置/资料并 repo.replaceAll
        //（先 DELETE 全表）—— 用户"恢复一个坏文件"等于把数据全删了，而且**不报错**。
        val decoded = Backup.decode("""{"formatVersion":1}""")
        assertTrue("这种文件必须被拒，实际：$decoded", decoded is BackupDecode.Bad)
    }

    @Test
    fun `旧备份（没有 scope 字段）仍然照常能读 —— 别把兼容性修没了`() {
        // v0.53.0 之前的文件没有 scope，也没有 appVersion/exportedAt 之外的东西保证，
        // 但它们**确实是整份导出**，所以该能读进来
        val full = Backup.encode(sample()).replace("\"scope\":\"ALL\",", "")
        assertTrue("去掉 scope 的老文件应当照常解码", Backup.decode(full) is BackupDecode.Ok)
    }

    @Test
    fun `sanitize 要去掉 id 重复的会话 —— 非空 id 一样会撞车`() {
        // KDoc 把风险归因于"空 id 撞车 + @Upsert 静默覆盖"，
        // 但**非空但重复**的 id 在 replaceAll（@Upsert）下同样会静默覆盖
        val one = sample().sessions[0]
        val dup = sample().copy(sessions = listOf(one, one.copy(title = "另一段但 id 一样")))
        val out = Backup.sanitize(dup)
        assertEquals("重复 id 的会话要去掉一条", 1, out.snapshot.sessions.size)
        assertEquals(1, out.droppedSessions)
    }

    @Test
    fun `可选字段的 null 原样往返而不是变成空串`() {
        val decoded = Backup.decode(Backup.encode(sample())) as BackupDecode.Ok
        val second = decoded.snapshot.personas[1]
        assertEquals(null, second.personality)
        assertEquals(null, second.greeting)
        assertEquals(null, second.avatarPath)
        assertEquals(null, decoded.snapshot.sessions[0].messages[0].reasoning)
        assertEquals(null, decoded.snapshot.sessions[0].messages[0].thinkingMs)
    }

    @Test
    fun `会话级 provider 的两个 null 原样往返 —— null 是跟着全局的语义，不能变成空串`() {
        // ⚠️ 空串与 null 在 `Session` 里是**不同**的东西：
        //    null = 从没选过（跟着全局）／空串 = 选了个空模型（不可能但不是同一回事）。
        //    兜底成空串会让判据慢慢长歪 —— 这条断言把 null 钉住。
        val s = Session(id = "s-null")
        // 用既有 sample() 的其余字段（都是必填），只把 sessions 换成一个"没选过"的会话
        val decoded = Backup.decode(Backup.encode(sample().copy(sessions = listOf(s)))) as BackupDecode.Ok
        assertEquals(null, decoded.snapshot.sessions[0].providerGroupId)
        assertEquals(null, decoded.snapshot.sessions[0].model)
    }

    @Test
    fun `API Key 不进备份`() {
        val secret = "sk-this-must-never-be-exported-1234567890"
        val encoded = Backup.encode(sample(apiKey = secret))

        assertFalse(
            "备份文件里出现了密钥 —— 它会被用户拷来拷去，这是这一类功能最贵的错误",
            encoded.contains(secret),
        )
        // 而且恢复时不该把本机现有的密钥冲掉：解出来的必须是空串
        val decoded = Backup.decode(encoded) as BackupDecode.Ok
        assertEquals("", decoded.snapshot.settings.apiKey)
    }

    @Test
    fun `不是 JSON 的文件被拒绝且说得出原因`() {
        val r = Backup.decode("这不是 json，是用户选错了文件")
        assertTrue(r is BackupDecode.Bad)
        assertTrue((r as BackupDecode.Bad).reason.isNotBlank())
    }

    @Test
    fun `缺少 formatVersion 的文件被拒绝`() {
        // 例如用户选了一份别的 App 的 JSON
        val r = Backup.decode("""{"sessions":[]}""")
        assertTrue(r is BackupDecode.Bad)
        assertTrue((r as BackupDecode.Bad).reason.contains("formatVersion"))
    }

    @Test
    fun `来自更高格式版本的备份被拒绝而不是猜着读`() {
        val r = Backup.decode("""{"formatVersion": 99, "sessions": []}""")
        assertTrue(r is BackupDecode.Bad)
        assertTrue((r as BackupDecode.Bad).reason.contains("99"))
    }

    @Test
    fun `缺字段的旧备份走默认值而不是整体失败`() {
        // 只有格式版本和一条最小会话 —— 这是"将来的版本加了字段"时旧备份的样子
        val r = Backup.decode(
            """{"formatVersion":1,"sessions":[{"id":"s1","personaId":"p1","messages":[]}]}""",
        )
        assertTrue("缺字段应当用默认值兜住，实际：$r", r is BackupDecode.Ok)
        val s = (r as BackupDecode.Ok).snapshot.sessions[0]
        assertEquals("新的对话", s.title)          // 空标题 → 默认
        assertEquals(true, s.thinkingEnabled)      // 缺失 → 默认开
        assertEquals("high", s.reasoningEffort)
        assertEquals(0.55f, s.scrimAlpha)
        assertEquals("plain", s.scrimStyle)
        assertEquals(0, s.totalHit)
    }

    @Test
    fun `内容是给人看的可读 JSON`() {
        val encoded = Backup.encode(sample())
        // 备份"看起来是对的"是它可信的一半：用户拿文本编辑器就能确认对话在里面
        assertTrue("应当带缩进（pretty print）", encoded.contains("\n"))
        assertTrue(encoded.contains("\"formatVersion\": 1"))
        assertTrue("中文应当原样保留（不转义成 \\uXXXX）", encoded.contains("他养了一只叫团子的猫"))
    }

    @Test
    fun `一条消息都不少`() {
        val decoded = Backup.decode(Backup.encode(sample())) as BackupDecode.Ok
        val messages = decoded.snapshot.sessions[0].messages
        assertEquals(3, messages.size)
        assertEquals(listOf("assistant", "user", "assistant"), messages.map { it.role })
        assertEquals(1, messages[1].images.size)
        assertEquals(4200L, messages[2].thinkingMs)
    }

    /* ─────────────── sanitize：外部文件不可信 ───────────────
     *
     * 备份是用户从文件管理器选的 JSON，可能被手改、来自别的版本、或别人给的。
     * 这一组钉的是"脏数据不会让整次导入白跑"——两条会真出事的：
     * 空 id 的会话（撞主键，@Upsert 静默覆盖）、指向不存在会话的记忆（撞外键，事务抛异常）。
     */

    @Test
    fun `整理会丢掉 id 为空的会话，并汇报丢了几条`() {
        val dirty = sample().let { s ->
            s.copy(sessions = s.sessions + s.sessions[0].copy(id = ""))
        }
        val out = Backup.sanitize(dirty)
        assertEquals("只该剩原来那一条", 1, out.snapshot.sessions.size)
        assertEquals(1, out.droppedSessions)
        assertTrue(out.droppedAnything)
    }

    @Test
    fun `整理会丢掉指向不存在会话的记忆 —— 否则外键会让整个事务回滚`() {
        val dirty = sample().let { s ->
            s.copy(
                memories = s.memories + s.memories[0].copy(id = "orphan", sessionId = "没这个会话"),
            )
        }
        val out = Backup.sanitize(dirty)
        assertEquals(1, out.snapshot.memories.size)
        // 留下的应当是**原来那条**（m1）；被丢的是指向不存在会话的 orphan
        assertEquals("m1", out.snapshot.memories[0].id)
        assertEquals(1, out.droppedMemories)
    }

    @Test
    fun `整理保留 sessionId 为 null 的记忆`() {
        val s = sample().let { it.copy(memories = it.memories + it.memories[0].copy(id = "m2", sessionId = null)) }
        val out = Backup.sanitize(s)
        assertEquals(2, out.snapshot.memories.size)
        assertEquals(0, out.droppedMemories)
    }

    @Test
    fun `干净的备份整理后一条不丢`() {
        val out = Backup.sanitize(sample())
        assertEquals(0, out.droppedSessions)
        assertEquals(0, out.droppedMemories)
        assertFalse(out.droppedAnything)
        assertEquals(sample().sessions.size, out.snapshot.sessions.size)
    }

    /* ─────────── 分项导出 / 导入（v0.53.0）─────────── */

    @Test
    fun `聊天记录分项只写 sessions`() {
        val json = Backup.encode(sample().copy(scope = BackupScope.CHAT))
        assertTrue("聊天记录该有", json.contains("\"sessions\""))
        assertFalse("记忆不该被写进去", json.contains("\"memories\""))
        assertFalse("人设不该被写进去", json.contains("\"personas\""))
        assertFalse("设置不该被写进去", json.contains("\"settings\""))
    }

    @Test
    fun `记忆库分项只写 memories`() {
        val json = Backup.encode(sample().copy(scope = BackupScope.MEMORY))
        assertTrue(json.contains("\"memories\""))
        assertFalse(json.contains("\"sessions\""))
        assertFalse(json.contains("\"personas\""))
    }

    @Test
    fun `配置分项写人设与设置，绝不带上聊天记录`() {
        val json = Backup.encode(sample().copy(scope = BackupScope.CONFIG))
        assertTrue(json.contains("\"personas\""))
        assertTrue(json.contains("\"settings\""))
        assertFalse("配置导出带上聊天记录等于把用户的对话泄进另一个文件", json.contains("\"sessions\""))
        assertFalse(json.contains("\"memories\""))
    }

    @Test
    fun `scope 随文件往返 —— 导入端靠它决定替换哪几样`() {
        BackupScope.entries.forEach { sc ->
            val back = Backup.decode(Backup.encode(sample().copy(scope = sc)))
            val ok = back as BackupDecode.Ok
            assertEquals("scope $sc 在往返里丢了", sc, ok.snapshot.scope)
        }
    }

    @Test
    fun `老备份没有 scope 字段 —— 读成 ALL（整份替换，与它导出时的事实一致）`() {
        val legacy = """{"formatVersion":1,"appVersion":"0.52.0","exportedAt":1,""" +
            """"personas":[],"sessions":[],"memories":[]}"""
        val ok = Backup.decode(legacy) as BackupDecode.Ok
        assertEquals(BackupScope.ALL, ok.snapshot.scope)
    }
}
