package ai.yuki.chuxue.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「记忆上云」门禁的纯函数契约。
 *
 * v0.61.48 起判据改为**看「记忆方式」**（[Persona.isCloudMemory]），不再看
 * `cloudMemoryEnabled` / `xinchaoEnabled` 两个开关 —— 因为"云端三项功能都依赖云端"
 *（上报 /event、上云 /memory、取件 /pending），一个选择决定三项（用户 2026-10-06 拍板）。
 * 网络调用本身没法单测（同 [XinchaoReportTest] 的纪律）。
 */
class MemoryCloudGateTest {

    private fun persona(mode: String?) = Persona(
        id = "p1",
        userGender = "男",
        customPrompt = "角色名称：初雪",
        memoryMode = mode,
    )

    @Test
    fun `云端模式才推`() {
        assertTrue(MemoryCloud.shouldPush(persona(Persona.MEMORY_MODE_CLOUD)))
    }

    @Test
    fun `字段值不影响判据 —— 判据只看记忆方式`() {
        // 云端模式：即使两个开关字段是 false（例如刚切成云端、旧字段还没写），也照推
        assertTrue(
            MemoryCloud.shouldPush(
                persona(Persona.MEMORY_MODE_CLOUD).copy(
                    xinchaoEnabled = false,
                    cloudMemoryEnabled = false,
                ),
            ),
        )
    }

    @Test
    fun `本地模式不推`() {
        assertFalse(MemoryCloud.shouldPush(persona(Persona.MEMORY_MODE_LOCAL)))
    }

    @Test
    fun `老数据（memoryMode 为 null）视为本地 —— 不推`() {
        assertFalse(MemoryCloud.shouldPush(persona(null)))
        assertFalse(MemoryCloud.shouldPush(null))
        // 默认构造：memoryMode 为 null
        assertFalse(MemoryCloud.shouldPush(Persona(id = "x", userGender = "女", customPrompt = "y")))
    }

    @Test
    fun `老数据即使以前开着两个开关 —— 也不再推（有意变更）`() {
        assertFalse(
            "老数据归本地：xinchaoEnabled/cloudMemoryEnabled 的历史值不再被读",
            MemoryCloud.shouldPush(
                Persona(
                    id = "old",
                    userGender = "女",
                    customPrompt = "y",
                    xinchaoEnabled = true,
                    cloudMemoryEnabled = true,
                ),
            ),
        )
    }

    /* ══════════ 彻底分轨（v0.61.54）══════════ */

    /**
     * 分轨的**单一判据**就是 `shouldPush`：它 true = 走云端（只写 OB），false = 走本地（只写 Room）。
     *
     * 用户原话：「云端记云端的记忆、本地记本地的记忆；云端记忆只有自动记忆到 OB 系统，
     * 本地只记到本地」。分轨后**不再有"两边都写"的中间态** ——
     * 自动提取、用户手记、附录读取三处都问同一个判据。
     */
    @Test
    fun `云端模式 —— 走云端（只写 OB，本地不写）`() {
        assertTrue(MemoryCloud.shouldPush(persona(Persona.MEMORY_MODE_CLOUD)))
    }

    @Test
    fun `本地模式 —— 走本地（只写 Room，云端不写）`() {
        assertFalse(MemoryCloud.shouldPush(persona(Persona.MEMORY_MODE_LOCAL)))
    }

    @Test
    fun `分轨是二选一 —— 同一个判据不会同时为真与假`() {
        // 云端与本地互斥：cloud 为 true 时 local 必为 false，反之亦然。
        // 这条钉住"没有中间态"（分轨前是"云端为母本 + 本地留快照"的双写）。
        val cloud = persona(Persona.MEMORY_MODE_CLOUD)
        val local = persona(Persona.MEMORY_MODE_LOCAL)
        assertTrue(cloud.isCloudMemory)
        assertFalse(local.isCloudMemory)
        assertNotEquals(cloud.isCloudMemory, local.isCloudMemory)
    }
}
