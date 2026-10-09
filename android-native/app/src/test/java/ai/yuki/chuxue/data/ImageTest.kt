package ai.yuki.chuxue.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图片支持测试。
 *
 * ══ 最要命的一条 ══
 * `无图时请求体必须逐字节不变`。
 * 加图片功能时最容易犯的错是「干脆所有消息都用块数组」——
 * 那会让**所有已有会话的缓存前缀与之前不一致**，命中率直接归零，
 * 而且用户完全不知道为什么费用突然涨了。
 */
class ImageTest {

    private val settings = AppSettings(apiKey = "sk-test", globalPrefixEnabled = false)
    private val persona = Persona(
        id = "p1", userNickname = "小明", userGender = "男",
        customPrompt = "角色名称：初雪",
    )
    private val frozen = PromptEngine.buildFrozenPrefix(settings, persona)

    private val jpeg = "data:image/jpeg;base64," + "A".repeat(1000)
    private val png = "data:image/png;base64," + "B".repeat(1000)

    private fun contentOfRole(body: String, role: String, index: Int = 0) =
        Json.parseToJsonElement(body).jsonObject["messages"]!!.jsonArray
            .filter { it.jsonObject["role"]!!.jsonPrimitive.content == role }[index]
            .jsonObject["content"]!!

    /* ══════════════ 无图路径：字节必须与之前完全一致 ══════════════ */

    @Test
    fun `无图时 content 是纯字符串，不是数组`() {
        val body = PromptEngine.plan(settings, frozen, emptyList(), "你好").body
        val content = contentOfRole(body, "user")
        assertTrue(
            "无图必须是字符串 —— 套上数组会改变字节，让所有历史会话的缓存前缀失配",
            content is kotlinx.serialization.json.JsonPrimitive,
        )
        assertEquals("你好", content.jsonPrimitive.content)
    }

    @Test
    fun `无图时历史消息也是纯字符串`() {
        val history = listOf(ChatMessage("user", "a"), ChatMessage("assistant", "b"))
        val body = PromptEngine.plan(settings, frozen, history, "c").body
        val msgs = Json.parseToJsonElement(body).jsonObject["messages"]!!.jsonArray
        msgs.forEach { m ->
            assertTrue(
                "角色「${m.jsonObject["role"]!!.jsonPrimitive.content}」的 content 应为字符串",
                m.jsonObject["content"] is kotlinx.serialization.json.JsonPrimitive,
            )
        }
    }

    @Test
    fun `加图片功能后，不带图的请求体与旧格式逐字节相同`() {
        // 这是回归保护：如果哪天有人把 messageToMap 统一改成块数组，这条会红
        val body = PromptEngine.plan(settings, frozen, emptyList(), "在吗").body
        assertTrue(body.contains(""""content":"在吗""""))
        assertFalse("不应出现块数组的痕迹", body.contains(""""type":"text""""))
        assertFalse(body.contains("image_url"))
    }

    /* ══════════════ 有图路径：块数组 ══════════════ */

    @Test
    fun `有图时 content 是块数组，含 text 与 image_url 两块`() {
        val body = PromptEngine.plan(settings, frozen, emptyList(), "看看这个", images = listOf(jpeg)).body
        val content = contentOfRole(body, "user").jsonArray

        assertEquals(2, content.size)
        assertEquals("text", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("看看这个", content[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("image_url", content[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(
            jpeg,
            content[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `图片使用 detail low 以节省 token`() {
        val body = PromptEngine.plan(settings, frozen, emptyList(), "x", images = listOf(jpeg)).body
        val block = contentOfRole(body, "user").jsonArray[1].jsonObject
        assertEquals("low", block["image_url"]!!.jsonObject["detail"]!!.jsonPrimitive.content)
    }

    @Test
    fun `多张图按顺序全部带上`() {
        val body = PromptEngine.plan(settings, frozen, emptyList(), "两张", images = listOf(jpeg, png)).body
        val content = contentOfRole(body, "user").jsonArray
        assertEquals(3, content.size) // text + 2 images
        assertEquals(jpeg, content[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        assertEquals(png, content[2].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
    }

    @Test
    fun `文本为空时只发图片块`() {
        val blocks = PromptEngine.buildContentBlocks("", listOf(jpeg))
        assertEquals(1, blocks.size)
        assertEquals("image_url", blocks[0]["type"])
    }

    @Test
    fun `历史里带图的消息也会以块数组形式重发`() {
        val history = listOf(ChatMessage("user", "上一张图", images = listOf(jpeg)))
        val body = PromptEngine.plan(settings, frozen, history, "再看这张").body
        val msgs = Json.parseToJsonElement(body).jsonObject["messages"]!!.jsonArray
        // messages[0]=system, [1]=历史那条带图的 user
        val historical = msgs[1].jsonObject["content"]
        assertTrue("历史里的图必须原样重发，否则前缀不一致", historical is kotlinx.serialization.json.JsonArray)
    }

    /* ══════════════ 校验 ══════════════ */

    @Test
    fun `图片只能出现在 user 消息里`() {
        val r = ImagePolicy.check("assistant", listOf(jpeg))
        assertTrue(r is ImagePolicy.Check.Rejected)
        // 断言「提到了官方限制」而不是某个具体措辞 ——
        // 文案会改，但「因为官方返回 400 才拒绝」这个语义是稳定的
        val reason = (r as ImagePolicy.Check.Rejected).reason
        assertTrue("拒绝原因应说明这是官方限制：$reason", reason.contains("400"))
        assertTrue("拒绝原因应带上实际角色，便于排查：$reason", reason.contains("assistant"))
    }

    @Test
    fun `system 消息带图同样被拒`() {
        assertTrue(ImagePolicy.check("system", listOf(jpeg)) is ImagePolicy.Check.Rejected)
    }

    @Test
    fun `无图时不校验角色（正常消息不该被误伤）`() {
        assertEquals(ImagePolicy.Check.Ok, ImagePolicy.check("assistant", emptyList()))
    }

    @Test
    fun `四种官方格式都被接受`() {
        listOf("jpeg", "png", "gif", "webp").forEach { fmt ->
            assertTrue("$fmt 应该被接受", ImagePolicy.isSupportedFormat("data:image/$fmt;base64,AAA"))
        }
    }

    @Test
    fun `非官方格式被拒并给出可读原因`() {
        val r = ImagePolicy.check("user", listOf("data:image/bmp;base64,AAA"))
        assertTrue(r is ImagePolicy.Check.Rejected)
        assertTrue((r as ImagePolicy.Check.Rejected).reason.contains("格式"))
    }

    @Test
    fun `裸 base64 而非 data URL 会被拒（这是常见的手误）`() {
        assertFalse(ImagePolicy.isSupportedFormat("/9j/4AAQSkZJRg=="))
    }

    @Test
    fun `超过单图上限时给出带数字的可读原因`() {
        // 32 MiB 解码后 ≈ 44.7M 个 base64 字符
        val huge = "data:image/jpeg;base64," + "A".repeat(45_000_000)
        val r = ImagePolicy.check("user", listOf(huge))
        assertTrue(r is ImagePolicy.Check.Rejected)
        assertTrue((r as ImagePolicy.Check.Rejected).reason.contains("MB"))
    }

    @Test
    fun `base64 字节数估算正确`() {
        // 4 个 base64 字符 = 3 字节
        assertEquals(3L, ImagePolicy.estimateBytes("data:image/jpeg;base64,AAAA"))
        assertEquals(6L, ImagePolicy.estimateBytes("data:image/png;base64," + "A".repeat(8)))
    }

    @Test
    fun `正常大小的图片通过校验`() {
        assertEquals(ImagePolicy.Check.Ok, ImagePolicy.check("user", listOf(jpeg, png)))
    }

    /* ══════════════ 编解码往返 ══════════════ */

    @Test
    fun `带图消息编解码往返后图片不丢`() {
        val sessions = listOf(
            Session(
                id = "s1",
                messages = listOf(
                    ChatMessage("user", "看这个", images = listOf(jpeg, png)),
                    ChatMessage("assistant", "看到啦"),
                ),
            ),
        )
        val back = SessionCodec.decode(SessionCodec.encode(sessions))
        assertEquals(listOf(jpeg, png), back[0].messages[0].images)
        assertEquals(emptyList<String>(), back[0].messages[1].images)
    }

    @Test
    fun `无图消息编解码后 images 为空列表（且不写出字段）`() {
        val sessions = listOf(Session(id = "s1", messages = listOf(ChatMessage("user", "hi"))))
        val encoded = SessionCodec.encode(sessions)
        assertFalse("空图片不该写出字段，避免无谓字节", encoded.contains("images"))
        assertEquals(emptyList<String>(), SessionCodec.decode(encoded)[0].messages[0].images)
    }

    @Test
    fun `旧数据（没有 images 字段）能被正常读取`() {
        val legacy = """[{"id":"s1","messages":[{"role":"user","content":"旧消息"}]}]"""
        val back = SessionCodec.decode(legacy)
        assertEquals("旧消息", back[0].messages[0].content)
        assertEquals(emptyList<String>(), back[0].messages[0].images)
    }
}
