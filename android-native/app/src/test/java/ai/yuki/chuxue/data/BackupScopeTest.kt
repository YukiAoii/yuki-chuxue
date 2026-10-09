package ai.yuki.chuxue.data

import ai.yuki.chuxue.data.room.MemoryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分项备份的规格（v0.53.0 提出、v0.61.21 补完）。
 *
 * ## 为什么这一组测试非要有
 * 分项的全部价值是**风险隔离**：只想挪聊天记录的人，不该被迫连带覆盖人设与设置。
 * 而"只带自己那一段"这件事，**一旦 encode 漏判，后果是导入端静默清掉别的域** ——
 * 用户想挪 3 段对话，结果人设没了。所以这里逐段钉住。
 *
 * 另一半是**分项文件必须能被自己的解码器读回来**（而且不被"空壳备份"那道守卫误拦）——
 * 补完之前，导出端写得出分项文件、导入端一律拒收，那套功能等于只有半边架子。
 */
class BackupScopeTest {

    private fun snapshot(scope: BackupScope) = BackupSnapshot(
        appVersion = "0.61.21",
        exportedAt = 1_759_000_000_000L,
        personas = listOf(Persona(id = "p1", userNickname = "我", roleName = "小雪")),
        sessions = listOf(
            Session(
                id = "s1",
                personaId = "p1",
                title = "初雪",
                messages = listOf(ChatMessage(role = "user", content = "在吗")),
            ),
        ),
        memories = listOf(
            MemoryEntity(
                id = "m1",
                userId = MemoryEntity.LOCAL_USER_ID,
                personaId = "p1",
                sessionId = "s1",
                scope = MemoryEntity.SCOPE_SESSION,
                content = "她喜欢猫",
                category = "喜好",
                importance = 5,
                source = "manual",
                embedding = null,
                createdAt = 1_700_000_000_000L,
                lastAccessedAt = 1_700_000_000_000L,
                expiresAt = null,
            ),
        ),
        settings = AppSettings(baseUrl = "https://api.deepseek.com", model = "m"),
        profile = UserProfile(nickname = "阿澈"),
        scope = scope,
    )

    /* ─────────── 每一档该带哪几段 ─────────── */

    @Test
    fun `四档的作用域判据 —— 导入端的分派全靠它`() {
        assertTrue(BackupScope.ALL.includesChat && BackupScope.ALL.includesMemory)
        assertTrue(BackupScope.ALL.includesPersona && BackupScope.ALL.includesConfig)

        assertTrue(BackupScope.CHAT.includesChat)
        assertFalse("聊天记录档不许碰记忆", BackupScope.CHAT.includesMemory)
        assertFalse("聊天记录档不许碰人设与设置", BackupScope.CHAT.includesPersona)

        assertTrue(BackupScope.MEMORY.includesMemory)
        assertFalse(BackupScope.MEMORY.includesChat)
        assertFalse(BackupScope.MEMORY.includesPersona)

        assertTrue(BackupScope.CONFIG.includesPersona)
        assertTrue(BackupScope.CONFIG.includesConfig)
        assertFalse("配置档不许碰聊天记录", BackupScope.CONFIG.includesChat)
        assertFalse("配置档不许碰记忆", BackupScope.CONFIG.includesMemory)
    }

    /* ─────────── 文件里真的只有那一段 ─────────── */

    @Test
    fun `聊天记录档只写 sessions —— 人设与设置一个字节都不出现`() {
        val text = Backup.encode(snapshot(BackupScope.CHAT))
        assertTrue("该带的那段要有", text.contains("\"sessions\""))
        assertFalse("不该带人设", text.contains("\"personas\""))
        assertFalse("不该带记忆", text.contains("\"memories\""))
        assertFalse("不该带设置", text.contains("\"settings\""))
        assertFalse("不该带资料", text.contains("\"profile\""))
    }

    @Test
    fun `配置档只写 settings 与 profile`() {
        val text = Backup.encode(snapshot(BackupScope.CONFIG))
        assertTrue(text.contains("\"settings\""))
        assertTrue(text.contains("\"profile\""))
        assertFalse(text.contains("\"sessions\""))
        assertFalse(text.contains("\"memories\""))
    }

    @Test
    fun `记忆档只写 memories`() {
        val text = Backup.encode(snapshot(BackupScope.MEMORY))
        assertTrue(text.contains("\"memories\""))
        assertFalse(text.contains("\"sessions\""))
        assertFalse(text.contains("\"personas\""))
    }

    /* ─────────── 写得出来，就得读得回去 ─────────── */

    @Test
    fun `每一档写出来都能被自己的解码器读回来，而且范围不丢`() {
        BackupScope.entries.forEach { scope ->
            val decoded = Backup.decode(Backup.encode(snapshot(scope)))
            assertTrue("「${scope.label}」应当能解码，实际：$decoded", decoded is BackupDecode.Ok)
            assertEquals(scope, (decoded as BackupDecode.Ok).snapshot.scope)
        }
    }

    @Test
    fun `分项文件不会被「空壳备份」那道守卫误拦`() {
        // 那道守卫只针对 scope=ALL 却一个内容段都没有的文件；
        // 分项文件本来就"只有一段"，不该被它当成空壳。
        listOf(BackupScope.CHAT, BackupScope.MEMORY, BackupScope.CONFIG).forEach { scope ->
            assertTrue(
                "「${scope.label}」被误拦了",
                Backup.decode(Backup.encode(snapshot(scope))) is BackupDecode.Ok,
            )
        }
    }

    @Test
    fun `分项文件经 sanitize 后范围仍然是它自己`() {
        BackupScope.entries.forEach { scope ->
            val ok = Backup.decode(Backup.encode(snapshot(scope))) as BackupDecode.Ok
            assertEquals(scope, Backup.sanitize(ok.snapshot).snapshot.scope)
        }
    }
}
