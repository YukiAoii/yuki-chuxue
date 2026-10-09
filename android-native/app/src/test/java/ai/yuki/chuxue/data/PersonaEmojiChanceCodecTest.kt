package ai.yuki.chuxue.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 人设「表情包概率覆盖」的**持久化**契约。
 *
 * ## ⚠️ 为什么单独开一个文件，而不是塞进 `CodecTest`
 * `CodecTest` 里已经有两条「人设往返」断言（`人设往返：全部字段不变` /
 * `人设往返：可空字段为 null 时保持 null`），但它们**测不到这个字段** ——
 * 那两条构造 `Persona` 时没有设它，而它的默认值就是 `null`，
 * 于是"缺失 == 缺失"照样通过，**看起来是绿的**。
 *
 * 这是"加了字段但测试没跟上"的典型假绿：**如果 `PersonaCodec` 漏搬这个字段，
 * 那两条断言不会红。** 所以这里刻意用**非默认值**（0.55）走一遍往返 —— 漏了就会红。
 */
class PersonaEmojiChanceCodecTest {

    @Test
    fun `表情包概率覆盖能往返 —— 用非默认值测，漏搬就会红`() {
        val p = Persona(
            id = "p1",
            userNickname = "阿澈",
            userGender = "男",
            customPrompt = "角色名称：初雪",
            emojiChanceOverride = 0.55f,
        )
        val back = PersonaCodec.decode(PersonaCodec.encode(listOf(p)))
        assertEquals(1, back.size)
        assertEquals("覆盖值没有原样读回来", 0.55f, back[0].emojiChanceOverride!!, 0.0001f)
    }

    @Test
    fun `没设过覆盖值时读回来仍是 null —— 也就是"跟随全局"`() {
        val p = Persona(id = "p2", userNickname = "我", userGender = "女", customPrompt = "x")
        val back = PersonaCodec.decode(PersonaCodec.encode(listOf(p)))
        assertNull(
            "没设过的字段被读成了非 null，会让它不再跟随全局默认",
            back[0].emojiChanceOverride,
        )
    }

    @Test
    fun `显式的 0 与"没设过"是两件事 —— 0 必须能原样读回`() {
        // 0 = 这个角色永远不发图；null = 跟随全局。
        // 若 Codec 把 0 当成"空值"丢掉，用户会发现"我明明关了，它还在发"。
        val p = Persona(
            id = "p3",
            userNickname = "我",
            userGender = "女",
            customPrompt = "x",
            emojiChanceOverride = 0f,
        )
        val back = PersonaCodec.decode(PersonaCodec.encode(listOf(p)))
        assertEquals(0f, back[0].emojiChanceOverride!!, 0.0001f)
    }
}
