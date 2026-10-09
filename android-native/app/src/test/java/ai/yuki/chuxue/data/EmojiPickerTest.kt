package ai.yuki.chuxue.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 表情包决策逻辑的契约。
 *
 * 这一段是**唯一能在本机验证**的部分（真机要等模型真的输出标签、还要反复试概率），
 * 所以三条分支都要钉住：有没有标签 / 有没有对应图 / 概率过没过。
 */
class EmojiPickerTest {

    private val pool = mapOf(
        "开心" to listOf("/emoji/a.jpg", "/emoji/b.jpg"),
        "爱意" to listOf("/emoji/c.jpg"),
    )

    private fun pick(
        reply: String,
        chance: Float = 1f,
        roll: Float = 0f,
        index: Int = 0,
        owned: Map<String, List<String>> = emptyMap(),
        global: Map<String, List<String>> = pool,
    ) = EmojiPicker.pick(reply, owned, global, chance, roll, index)

    /* ─────────── 分支一：有没有标签 ─────────── */

    @Test
    fun `回复里没有标签就不发`() {
        assertNull(pick("今天天气不错，出去走走吧。"))
    }

    @Test
    fun `方括号里不是情绪词就不发 —— 别把正文里的括号当标签`() {
        // 模型偶尔会输出 [1] [注] 这类非情绪方括号；把它们当标签会抽错图
        assertNull(pick("第 [1] 点是这样 [注] 补充一下。"))
    }

    /* ─────────── 分支二：有没有对应图 ─────────── */

    @Test
    fun `标签认得但没有对应图就不发`() {
        assertNull(pick("好生气呀 [生气]"))
    }

    @Test
    fun `有多个标签时跳到第一个有图的`() {
        // 「生气」没图，「爱意」有 —— 应当用后者，而不是白白消耗这次概率
        val r = pick("哼 [生气] 但还是想抱抱你 [爱意]", index = 0)
        assertEquals("爱意", r?.tag)
        assertEquals("/emoji/c.jpg", r?.path)
    }

    @Test
    fun `图库整个为空就不发`() {
        assertNull(pick("好开心 [开心]", global = emptyMap()))
    }

    /* ─────────── 分支三：概率 ─────────── */

    @Test
    fun `概率没过就不发`() {
        assertNull(pick("好开心 [开心]", chance = 0.3f, roll = 0.9f))
    }

    @Test
    fun `概率过了就发`() {
        assertEquals("开心", pick("好开心 [开心]", chance = 0.3f, roll = 0.1f)?.tag)
    }

    @Test
    fun `概率为 0 时永远不发（roll 取 0 也不发）`() {
        assertNull(pick("好开心 [开心]", chance = 0f, roll = 0f))
    }

    @Test
    fun `概率越界会被夹住而不是恒真或恒假`() {
        assertEquals("开心", pick("好开心 [开心]", chance = 2f, roll = 0.99f)?.tag)
        assertNull(pick("好开心 [开心]", chance = -1f, roll = 0f))
    }

    /* ─────────── 抽第几张 ─────────── */

    @Test
    fun `按 index 抽到指定的那张`() {
        assertEquals("/emoji/b.jpg", pick("开心 [开心]", index = 1)?.path)
    }

    @Test
    fun `index 越界会取模回绕，不会崩`() {
        assertEquals("/emoji/a.jpg", pick("开心 [开心]", index = 2)?.path)
        assertEquals("/emoji/b.jpg", pick("开心 [开心]", index = 5)?.path)
    }

    @Test
    fun `index 为负也安全`() {
        // 调用方理论上不会传负，但取模那类越界在真机上是直接崩，这里挡一道。
        // ⚠️ 期望值是 b.jpg 而不是 a.jpg —— `Math.floorMod(-1, 2) == 1`，
        // 也就是"往前绕一位"，不是"夹到 0"。这条断言我一开始写反了，是测试把它纠正过来的。
        assertEquals("/emoji/b.jpg", pick("开心 [开心]", index = -1)?.path)
    }

    /* ─────────── 归属：专属优先，缺了才回退全局 ─────────── */

    /**
     * 「这个角色专属」的那一层。
     *
     * ⚠️ 回退必须做在**情绪分类这一层**（`专属[开心] → 全局[开心] → 不发`），
     * 不是"整库回退" —— 否则专属库里有图但没有「难过」时，用户说难过她就**一张也发不出**，
     * 而全局库里明明有。这一组断言钉的就是这条边界。
     */
    private val ownedPool = mapOf(
        "开心" to listOf("/emoji/mine-1.jpg", "/emoji/mine-2.jpg"),
    )

    @Test
    fun `专属有这一类的图时，只用专属的`() {
        val r = EmojiPicker.pick("开心 [开心]", ownedPool, pool, 1f, 0f, 0)
        assertEquals("/emoji/mine-1.jpg", r?.path)
    }

    @Test
    fun `专属没有这一类时才回退到全局`() {
        // 「爱意」只在全局有
        val r = EmojiPicker.pick("好想你 [爱意]", ownedPool, pool, 1f, 0f, 0)
        assertEquals("爱意", r?.tag)
        assertEquals("/emoji/c.jpg", r?.path)
    }

    @Test
    fun `专属命中时不把全局的图混进来`() {
        // 抽 4 次（index 0..3）：专属只有 2 张，若实现把两层并成一个列表就会出现全局路径
        val paths = (0..3).map {
            EmojiPicker.pick("开心 [开心]", ownedPool, pool, 1f, 0f, it)?.path
        }
        assertTrue("不该抽到全局的图：$paths", paths.all { it?.startsWith("/emoji/mine-") == true })
    }

    @Test
    fun `两层都没有这一类就不发`() {
        assertNull(EmojiPicker.pick("好生气 [生气]", ownedPool, pool, 1f, 0f, 0))
    }

    @Test
    fun `专属整个为空时等于纯全局库`() {
        val r = EmojiPicker.pick("开心 [开心]", emptyMap(), pool, 1f, 0f, 0)
        assertEquals("/emoji/a.jpg", r?.path)
    }

    @Test
    fun `专属这一类的图全坏了也要能回退到全局`() {
        // 空列表 = 这类专属图一个可用的都没有（可用性检测筛完就是这个形状）
        val brokenOwned = mapOf("开心" to emptyList<String>())
        val r = EmojiPicker.pick("开心 [开心]", brokenOwned, pool, 1f, 0f, 0)
        assertEquals("/emoji/a.jpg", r?.path)
    }

    @Test
    fun `第一个标签命中专属时，后面的标签不再参与`() {
        // 「开心」专属有、「爱意」只有全局 —— 先命中谁就是谁
        val r = EmojiPicker.pick("[开心] 也想你 [爱意]", ownedPool, pool, 1f, 0f, 0)
        assertEquals("开心", r?.tag)
    }

    /* ─────────── 人设覆盖 ─────────── */

    @Test
    fun `人设覆盖优先于全局默认`() {
        assertEquals(0.5f, EmojiPicker.effectiveChance(global = 0.3f, personaOverride = 0.5f))
    }

    @Test
    fun `没人设覆盖就用全局默认`() {
        assertEquals(0.3f, EmojiPicker.effectiveChance(global = 0.3f, personaOverride = null))
    }

    @Test
    fun `覆盖值也会被夹到 0 到 1`() {
        assertEquals(1f, EmojiPicker.effectiveChance(0.3f, 9f))
        assertEquals(0f, EmojiPicker.effectiveChance(0.3f, -9f))
    }

    /* ─────────── 摘标签 ─────────── */

    @Test
    fun `认得的标签从正文里摘掉`() {
        val known = EmojiCategories.DEFAULTS
        assertEquals("好开心呀", EmojiCategories.stripTags("好开心呀 [开心]", known))
    }

    @Test
    fun `不认得的方括号原样保留 —— 否则会吃掉正文`() {
        val known = EmojiCategories.DEFAULTS
        assertEquals("第 [1] 点", EmojiCategories.stripTags("第 [1] 点", known))
    }

    @Test
    fun `标签在句中也能摘掉`() {
        val known = EmojiCategories.DEFAULTS
        val out = EmojiCategories.stripTags("我 [开心] 好开心", known)
        assertTrue("摘完不该留下多余空格：'$out'", !out.contains("  "))
    }

    @Test
    fun `预置分类与提示词里的标签名必须一致`() {
        // ⚠️ 这条钉的是"两侧共用一份契约"：分类名同时是界面选项和发给模型的标签名。
        // 改了这里却忘了改提示词，症状是"功能开着但一张图都不发"，而且不报错。
        assertTrue(
            "预置分类不应为空，否则提示词里没有可用的标签名",
            EmojiCategories.DEFAULTS.isNotEmpty(),
        )
        EmojiCategories.DEFAULTS.forEach { c ->
            assertTrue("分类名「$c」里不该有空格或括号（它要写进提示词的 [ ] 里）",
                c.none { it.isWhitespace() || it == '[' || it == ']' })
        }
    }
}
