package ai.yuki.chuxue.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 云快照里**内嵌头像**的规格（v0.61.21，Wave 2b）。
 *
 * ## 为什么头像必须进快照
 * 快照里原本只搬 `avatarPath` —— 那是**本机文件路径**（`/data/user/0/.../avatars/xxx.jpg`）。
 * 换台设备，那个路径指向一个不存在的文件：人设回来了、头像却变回兜底图。
 *
 * ## 为什么要有体积预算
 * 头像 base64 是整份快照里唯一会"几个就吃满配额"的东西。后端对人设密文有上限，
 * 一旦超限就是 **400、整份人设都传不上去** —— 拿少数几个头像换掉了全部人设能不能同步，
 * 本末倒置。超预算时宁可**少带几个头像**（退化成改动之前的行为）。
 */
class PersonaAvatarSyncTest {

    private fun persona(id: String, avatar: String? = null) =
        Persona(id = id, userNickname = "我", customPrompt = "设定", avatarPath = avatar)

    /* ─────────── 编解码 ─────────── */

    @Test
    fun `头像能跟着快照往返`() {
        val text = PersonaCodec.encode(
            listOf(persona("p1"), persona("p2")),
            avatars = mapOf("p1" to "QUJD"),
        )
        assertEquals(mapOf("p1" to "QUJD"), PersonaCodec.decodeAvatars(text))
    }

    @Test
    fun `没头像的人设 —— 本地那份 JSON 一个字节都不该多出来`() {
        // 本地存储也走这个 encode。多出 "avatarData":null 是纯噪音，
        // 而且会让"本地格式变了"这种本来不必发生的事发生。
        val plain = PersonaCodec.encode(listOf(persona("p1")))
        assertFalse(plain.contains("avatarData"))
    }

    @Test
    fun `只有被指定的人设才带头像`() {
        val text = PersonaCodec.encode(
            listOf(persona("p1"), persona("p2")),
            avatars = mapOf("p2" to "QUJD"),
        )
        assertEquals(setOf("p2"), PersonaCodec.decodeAvatars(text).keys)
    }

    @Test
    fun `坏数据降级成空表 —— 头像取不到不该让整次同步失败`() {
        assertEquals(emptyMap<String, String>(), PersonaCodec.decodeAvatars("不是 JSON"))
        assertEquals(emptyMap<String, String>(), PersonaCodec.decodeAvatars(""))
        assertEquals(emptyMap<String, String>(), PersonaCodec.decodeAvatars("""[{"id":"p1"}]"""))
    }

    @Test
    fun `人设本体照旧能解出来 —— 加了头像栏没有破坏原来那条路`() {
        val text = PersonaCodec.encode(listOf(persona("p1")), avatars = mapOf("p1" to "QUJD"))
        assertEquals(listOf("p1"), PersonaCodec.decode(text).map { it.id })
    }

    /* ─────────── 体积预算 ─────────── */

    @Test
    fun `都在预算内就全带上`() {
        val picked = PersonaSync.fitAvatars(listOf("a" to "xx", "b" to "yy"), budgetBytes = 10)
        assertEquals(mapOf("a" to "xx", "b" to "yy"), picked)
    }

    @Test
    fun `超预算的那条跳过，后面的小图照样带上`() {
        // ⚠️ 是 skip 不是 break：一张特别大的图不该连累它后面的小图
        val picked = PersonaSync.fitAvatars(
            listOf("big" to "xxxxxxxxxx", "small" to "y", "also" to "z"),
            budgetBytes = 5,
        )
        assertEquals(mapOf("small" to "y", "also" to "z"), picked)
    }

    @Test
    fun `累计到装不下就停 —— 不许把整份快照顶到后端上限之上`() {
        val picked = PersonaSync.fitAvatars(
            listOf("a" to "12345", "b" to "12345", "c" to "12345"),
            budgetBytes = 11,
        )
        assertEquals(setOf("a", "b"), picked.keys)
    }

    @Test
    fun `空头像不算体积也不算带上`() {
        val picked = PersonaSync.fitAvatars(listOf("a" to "", "b" to "x"), budgetBytes = 1)
        assertEquals(mapOf("b" to "x"), picked)
    }

    @Test
    fun `预算默认值要留得出后端那 5MB 的余量`() {
        // 密文 = 明文 × ~1.33，明文里除头像还有人设文本 —— 3MB 给头像是拍过的数字，
        // 万一有人把它往上调，这条会提醒他先算一遍。
        assertTrue(PersonaSync.AVATAR_BUDGET_BYTES <= 3 * 1024 * 1024)
    }
}
