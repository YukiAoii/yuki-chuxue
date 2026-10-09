package ai.yuki.chuxue.data

import ai.yuki.chuxue.data.room.EmojiPackEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 表情包可用性检测的契约（用户补充的第四条：文件损坏/不在了要能跳过）。
 *
 * `exists` 是注入的，所以这些断言在 JVM 上就能跑 —— 不必真去动文件系统。
 */
class EmojiAvailabilityTest {

    private fun pack(id: String, category: String, owner: String? = null) =
        EmojiPackEntity(id = id, category = category, createdAt = 0L, personaId = owner)

    private val mix = listOf(
        pack("/e/a.jpg", "开心"),
        pack("/e/broken.jpg", "开心"),
        pack("/e/c.jpg", "爱意"),
        pack("/e/also-broken.jpg", "爱意"),
    )

    /** 只有这两个"文件还在"。 */
    private val exists: (String) -> Boolean = { it == "/e/a.jpg" || it == "/e/c.jpg" }

    @Test
    fun `坏文件被筛掉，好的留下`() {
        val out = EmojiAvailability.filterAvailable(mix, exists)
        assertEquals(listOf("/e/a.jpg", "/e/c.jpg"), out.map { it.id })
    }

    @Test
    fun `按分类分组时也只剩可用的`() {
        val map = EmojiAvailability.availableByCategory(mix, exists)
        assertEquals(listOf("/e/a.jpg"), map["开心"])
        assertEquals(listOf("/e/c.jpg"), map["爱意"])
    }

    @Test
    fun `整类全是坏文件时，这个分类不出现在结果里`() {
        // 症状差异很重要：留一个空分类进去，EmojiPicker 还得再判一次；
        // 直接从数据形状里去掉，"没有可用图"这件事就不必下游再猜
        val allBad = listOf(pack("/e/x.jpg", "难过"), pack("/e/y.jpg", "难过"))
        val map = EmojiAvailability.availableByCategory(allBad) { false }
        assertTrue("全坏的分类不该留在 map 里", map.isEmpty())
    }

    @Test
    fun `一个都不剩时结果是空的（而不是崩）`() {
        val map = EmojiAvailability.availableByCategory(mix) { false }
        assertTrue(map.isEmpty())
        // 这个形状喂给 EmojiPicker.pick 会直接返回 null = 不发图（静默降级，不报错）
        assertEquals(null, EmojiPicker.pick("开心 [开心]", emptyMap(), map, 1f, 0f, 0))
    }

    /* ─────────── 按归属分两层（方案 C）─────────── */

    @Test
    fun `专属与全局分成两层，各自按分类分组`() {
        val packs = listOf(
            pack("/e/g1.jpg", "开心"),
            pack("/e/m1.jpg", "开心", owner = "p1"),
            pack("/e/m2.jpg", "难过", owner = "p1"),
            pack("/e/other.jpg", "开心", owner = "p2"),
        )
        val (owned, global) = EmojiAvailability.availableByOwner(packs, "p1") { true }

        assertEquals(listOf("/e/m1.jpg"), owned["开心"])
        assertEquals(listOf("/e/m2.jpg"), owned["难过"])
        assertEquals(listOf("/e/g1.jpg"), global["开心"])
        assertNull("别人设的图不该落进任何一层", global["难过"])
        assertTrue("别人设的图不该混进专属层", owned.values.flatten().none { it == "/e/other.jpg" })
    }

    @Test
    fun `没有当前人设时只剩全局一层（而不是报错或空库）`() {
        val packs = listOf(pack("/e/g1.jpg", "开心"), pack("/e/m1.jpg", "开心", owner = "p1"))
        val (owned, global) = EmojiAvailability.availableByOwner(packs, null) { true }

        assertTrue(owned.isEmpty())
        assertEquals(listOf("/e/g1.jpg"), global["开心"])
    }

    @Test
    fun `坏文件在两层里都会被筛掉`() {
        val packs = listOf(
            pack("/e/good.jpg", "开心"),
            pack("/e/bad.jpg", "开心", owner = "p1"),
        )
        val (owned, global) = EmojiAvailability.availableByOwner(packs, "p1") { it == "/e/good.jpg" }

        assertTrue("专属层那张是坏文件，这一层应当为空", owned.isEmpty())
        assertEquals(listOf("/e/good.jpg"), global["开心"])
    }

    @Test
    fun `exists 抛异常时当作不可用，而不是让整轮发图崩掉`() {
        // 文件系统偶尔会抛（权限、路径过长、被占用）。一次探测失败不该毁掉整条回复。
        val out = EmojiAvailability.filterAvailable(mix) { p ->
            if (p == "/e/a.jpg") true else error("boom")
        }
        assertEquals(listOf("/e/a.jpg"), out.map { it.id })
    }

    @Test
    fun `空库进空库出`() {
        assertTrue(EmojiAvailability.availableByCategory(emptyList()) { true }.isEmpty())
    }

    @Test
    fun `同类里混着好坏时，抽到的永远是好的 —— 这就是用户场景的端到端`() {
        // 她那句话要「开心」，而这一分类里三张图坏了一张（用户清数据/手删）。
        // 筛完之后无论随机落在哪个 index，都该拿到画得出来的那张 ——
        // 这正是"可用性检测"要防的那个空气泡。
        val withBroken = listOf(
            pack("/e/good1.jpg", "开心"),
            pack("/e/broken.jpg", "开心"),
            pack("/e/good2.jpg", "开心"),
        )
        val available = EmojiAvailability.availableByCategory(withBroken) { it != "/e/broken.jpg" }

        // 遍历所有可能的 index，模拟"抽到哪一张"的随机性
        val got = (0..5).mapNotNull { i ->
            EmojiPicker.pick(
                "好开心 [开心]",
                emptyMap(),
                available,
                chance = 1f,
                roll = 0f,
                index = i,
            )?.path
        }.toSet()

        assertEquals(setOf("/e/good1.jpg", "/e/good2.jpg"), got)
        assertTrue("坏图不该出现在任何一次抽取里", got.none { it == "/e/broken.jpg" })
    }
}
