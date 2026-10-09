package ai.yuki.chuxue.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 老配置迁移成「默认分组」的规格。
 *
 * ## 它修的是什么
 * `sendThinkingParams` 这个字段有**两个相反的默认值**：
 * · 构造函数的默认是 `false`（新建分组不默认发思考参数，用户 2026-10-02 的要求）；
 * · **反序列化**缺字段时的默认是 `true`（`ProviderGroup.kt` 那处 `getOrElse { true }`）——
 *   它专门为"存这个字段之前写下的老分组"兜底，否则升级用户**静默失去思考能力**。
 *
 * 而 `migrateLegacy` 走的是**构造函数**、没传这个参数 —— 于是它造出来的"默认"分组是 `false`。
 * 一条本该"与本字段存在之前逐字节一致"的迁移路径，反而把思考能力关掉了。
 * 项目测试只覆盖了解码那条路，没覆盖迁移这条 —— 这个洞就是这么留下的。
 */
class ProviderGroupsMigrateTest {

    private val legacy = AppSettings(
        apiKey = "sk-old",
        baseUrl = "https://api.deepseek.com",
        model = "deepseek-chat",
    )

    @Test
    fun `迁移出来的默认分组要发思考参数 —— 否则升级用户静默失去思考能力`() {
        val group = ProviderGroups.migrateLegacy(legacy, now = 1L).single()
        assertTrue(
            "migrateLegacy 造出来的分组必须是 true（= 与 sendThinkingParams 存在之前的行为一致）",
            group.sendThinkingParams,
        )
    }

    @Test
    fun `迁移出来的分组会带上旧的地址密钥与模型`() {
        val group = ProviderGroups.migrateLegacy(legacy, now = 1L).single()
        assertEquals("https://api.deepseek.com", group.baseUrl)
        assertEquals("sk-old", group.apiKey)
        assertEquals(listOf("deepseek-chat"), group.checkedModels)
    }

    @Test
    fun `没有旧配置时不造分组 —— 新装用户该看到"还没有分组"`() {
        val empty = AppSettings()
        assertEquals(emptyList<ProviderGroup>(), ProviderGroups.migrateLegacy(empty, now = 1L))
    }

    @Test
    fun `解码缺字段仍然是 true —— 这条护栏不能被迁移那处改动带歪`() {
        // 老分组（存这个字段之前写下的）反序列化时必须保住 true
        val raw = ProviderGroups.encode(listOf(
            ProviderGroup(id = "g", name = "旧的", baseUrl = "https://x/v1", apiKey = "sk"),
        )).replace(",\"sendThinkingParams\":false", "")
        assertEquals(true, ProviderGroups.decode(raw).single().sendThinkingParams)
    }
}
