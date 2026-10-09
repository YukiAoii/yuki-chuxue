package ai.yuki.chuxue.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「通用设定」三态在**持久化层**的往返测试（v0.53.0）。
 *
 * ## 为什么单独钉这一组
 * [Persona.useGlobalPrefix] 是 `Boolean?`，三态各自含义完全不同：
 * - `null` = 老数据、从未设置过 → **沿用全局开关**（升级前行为，前缀不变、缓存不碎）；
 * - `true` / `false` = 用户显式选过。
 *
 * 而"存下去再读回来还是同一个值"是这条兼容路径的**硬不变量**：
 * 一旦 `null` 在往返中被物化成 `false`，**老用户升级后前缀就会变一次**，
 * 每个会话都要按未命中重算一次缓存 —— 这正是本轮要避免的事。
 *
 * 这一组全部是纯函数，JVM 上即可完整验证（不需要设备）。
 */
class GlobalPrefixCompatTest {

    private fun persona(v: Boolean?) = Persona(
        id = "p1",
        userNickname = "阿岚",
        userGender = "女",
        customPrompt = "角色名称：初雪",
        useGlobalPrefix = v,
        createdAt = 1L,
        updatedAt = 2L,
    )

    /* ── Codec（SharedPreferences 里那份 JSON） ── */

    @Test
    fun `Codec 往返 —— null 保持 null，不被物化成 false`() {
        val back = PersonaCodec.decode(PersonaCodec.encode(listOf(persona(null)))).single()
        assertNull(
            "null 必须原样保留：物化成 false 会让老用户从「沿用全局」变成「显式关」，前缀就变了",
            back.useGlobalPrefix,
        )
    }

    @Test
    fun `Codec 往返 —— true 与 false 各自保留`() {
        val t = PersonaCodec.decode(PersonaCodec.encode(listOf(persona(true)))).single()
        val f = PersonaCodec.decode(PersonaCodec.encode(listOf(persona(false)))).single()
        assertEquals(true, t.useGlobalPrefix)
        assertEquals(false, f.useGlobalPrefix)
    }

    @Test
    fun `Codec 读老 JSON（没有这一栏）→ null`() {
        // 模拟升级前存下的那份人设：一个字都没有 useGlobalPrefix
        val legacy = """[{"id":"old1","userNickname":"甲","userGender":"女",""" +
            """"customPrompt":"角色名称：初雪","createdAt":1,"updatedAt":2}]"""
        val p = PersonaCodec.decode(legacy).single()
        assertNull("老数据必须读成 null（= 沿用全局开关）", p.useGlobalPrefix)
        assertEquals("old1", p.id)
    }

    /* ── Backup（导出/导入那份 JSON） ── */

    @Test
    fun `Backup 往返 —— 三态各自保留`() {
        listOf(null, true, false).forEach { v ->
            val snapshot = BackupSnapshot(
                appVersion = "0.53.0",
                exportedAt = 1L,
                personas = listOf(persona(v)),
                sessions = emptyList(),
                memories = emptyList(),
                settings = AppSettings(),
                profile = UserProfile(),
            )
            val decoded = Backup.decode(Backup.encode(snapshot))
            val got = (decoded as? BackupDecode.Ok)
                ?.snapshot?.personas?.single()?.useGlobalPrefix
            assertEquals("Backup 往返把 $v 弄丢了", v, got)
        }
    }

    @Test
    fun `Backup 读老备份（人设没有这一栏）→ null`() {
        val legacyJson = """
            {"formatVersion":1,"appVersion":"0.52.0","exportedAt":1,
             "personas":[{"id":"p1","userNickname":"甲","userGender":"女",
                         "customPrompt":"角色名称：初雪","createdAt":1,"updatedAt":2}],
             "sessions":[],"memories":[]}
        """.trimIndent()
        val decoded = Backup.decode(legacyJson) as BackupDecode.Ok
        assertNull(
            "老备份导入后必须仍是 null —— 否则导入到本机时会把既有前缀改掉",
            decoded.snapshot.personas.single().useGlobalPrefix,
        )
    }

    /* ── 判定（往返的最终消费者） ── */

    @Test
    fun `Codec 往返后，判定结果与往返前一致`() {
        val s = AppSettings().copy(globalPrefixEnabled = true, globalPrefix = "# 全局规则")
        listOf(null, true, false).forEach { v ->
            val before = PromptEngine.buildFrozenPrefix(s, persona(v))
            val after = PromptEngine.buildFrozenPrefix(
                s,
                PersonaCodec.decode(PersonaCodec.encode(listOf(persona(v)))).single(),
            )
            assertEquals("往返后前缀必须逐字节相同（v=$v）", before, after)
            assertFalse(before.isBlank())
        }
    }
}
