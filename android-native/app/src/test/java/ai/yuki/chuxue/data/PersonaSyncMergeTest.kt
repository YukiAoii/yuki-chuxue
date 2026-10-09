package ai.yuki.chuxue.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「首次同步的合并」规格（v0.61.21 · fix2）。
 *
 * ## 它修的是什么（2026-10-04 真机日志确证）
 * 清数据/换设备重登后，**拉取的触发点只在聊天页**，而用户的落点是主界面 ——
 * 用户还没拉过就新建人设时，推送会把云端旧快照**覆盖成"只有新建的这一个"**。
 * 拉取点的修复在 MainActivity；这里钉的是**推送侧的最后一道防线**：
 * 在"本设备从未同步过"的场景，拉到的云端 + 本地新建 = **并集**，两边都保住。
 *
 * ## 为什么只有"从未同步过"才能合并
 * "云端 ∪ 本地"这个并集只在**本设备从未见过云端数据**时安全：
 * 此时本地缺的项 = "没拉到"，不是用户删的 —— 本地**没有删除语义**。
 * 一旦设备同步过（`syncedUid` 记住过），本地缺项就可能是用户在别处/此前删掉的，
 * 合并不当会把删掉的人设**复活**。所以：
 * · 判据用**持久化**的 syncedUid —— 进程重启不失效（重启 ≠ 首次）；
 * · 只认 **null**（本设备从未同步过任何账号）—— 换账号（syncedUid 是别的账号）
 *   也不合并：那是另一种数据交界的场景，不能拿旧账号的残留去并新账号的云端。
 */
class PersonaSyncMergeTest {

    /* ── shouldMergeFirstSync ── */

    @Test
    fun `真首次 + 云端非空 + 本地非空 —— 该合并`() {
        assertTrue(
            PersonaSync.shouldMergeFirstSync(syncedUid = null, remoteRev = 5, localCount = 1),
        )
    }

    @Test
    fun `本设备同步过 —— 不合并（本地缺项可能是用户删除，合并会复活）`() {
        assertFalse(
            PersonaSync.shouldMergeFirstSync(syncedUid = "u_me", remoteRev = 5, localCount = 1),
        )
    }

    @Test
    fun `云端为空 —— 没东西可合并，走正常上传`() {
        assertFalse(
            PersonaSync.shouldMergeFirstSync(syncedUid = null, remoteRev = 0, localCount = 1),
        )
    }

    @Test
    fun `本地为空 —— 没东西要保护，走正常下载`() {
        assertFalse(
            PersonaSync.shouldMergeFirstSync(syncedUid = null, remoteRev = 5, localCount = 0),
        )
    }

    /* ── mergeFirstSync ── */

    @Test
    fun `并集 —— 云端为主序，本地”云端没有的”追加`() {
        val cloud = listOf(
            Persona(id = "a", userNickname = "A"),
            Persona(id = "b", userNickname = "B"),
        )
        val local = listOf(Persona(id = "c", userNickname = "C"))
        val merged = PersonaSync.mergeFirstSync(cloud = cloud, local = local)
        assertEquals(listOf("a", "b", "c"), merged.map { it.id })
    }

    @Test
    fun `同 id 以云端为准 —— 本地版本不覆盖云端`() {
        val cloud = listOf(Persona(id = "a", userNickname = "云端版"))
        val local = listOf(
            Persona(id = "a", userNickname = "本地版"),
            Persona(id = "c", userNickname = "C"),
        )
        val merged = PersonaSync.mergeFirstSync(cloud = cloud, local = local)
        assertEquals(listOf("a", "c"), merged.map { it.id })
        assertEquals("云端版", merged.first { it.id == "a" }.userNickname)
    }

    @Test
    fun `空集防御 —— 任一边为空都不崩`() {
        val onlyLocal = PersonaSync.mergeFirstSync(cloud = emptyList(), local = listOf(Persona(id = "c")))
        assertEquals(listOf("c"), onlyLocal.map { it.id })
        val onlyCloud = PersonaSync.mergeFirstSync(cloud = listOf(Persona(id = "a")), local = emptyList())
        assertEquals(listOf("a"), onlyCloud.map { it.id })
        assertEquals(0, PersonaSync.mergeFirstSync(cloud = emptyList(), local = emptyList()).size)
    }
}
