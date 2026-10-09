package ai.yuki.chuxue.data

import ai.yuki.chuxue.data.room.MemoryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导出编码器的测试（v0.54.0）。
 *
 * ⚠️ 为什么这组值得写厚一点：导出的产物是**要离开这个 App 的**——
 * 它会被发给人、丢进 Excel、用浏览器打开。在 App 里"看起来对"没用，
 * 真正会坏的是**别人那边的解析**：CSV 少转义一个引号就错列、
 * HTML 少转义一个 `<` 就吃掉半段正文。这些在 JVM 上一行断言就能钉死。
 */
class ExporterTest {

    private val persona = Persona(
        id = "p1",
        userNickname = "阿澈",
        userGender = "保密",
        customPrompt = "角色名称：初雪\n她是个安静的姑娘",
    )

    private fun session(vararg msgs: ChatMessage) = Session(
        id = "s1",
        personaId = "p1",
        title = "雨天/咖啡",
        messages = msgs.toList(),
        createdAt = 1_700_000_000_000L,
        updatedAt = 1_700_000_100_000L,
    )

    private fun msg(role: String, content: String, images: List<String> = emptyList()) =
        ChatMessage(role = role, content = content, images = images, createdAt = 1_700_000_050_000L)

    /* ─────────── 文件名 ─────────── */

    @Test
    fun `文件名一眼能认出是什么 —— 且剔掉非法字符`() {
        val n = Exporter.fileNameFor(
            prefix = "与初雪的对话",
            suffix = "雨天/咖啡",
            at = 1_700_000_000_000L,
            format = ExportFormat.MARKDOWN,
        )
        assertTrue("标题里的斜杠必须被换掉，否则有些系统直接建不出文件：$n", !n.contains("/"))
        assertTrue(n.startsWith("与初雪的对话"))
        assertTrue(n.endsWith(".md"))
        assertTrue("要有时间戳，否则导两次分不清哪个新：$n", Regex("""\d{8}-\d{4}""").containsMatchIn(n))
    }

    @Test
    fun `标题为空时给个兜底名字`() {
        val n = Exporter.fileNameFor("", "", 1_700_000_000_000L, ExportFormat.JSON)
        assertTrue(n.isNotBlank())
        assertTrue(n.endsWith(".json"))
    }

    /* ─────────── 图片占位 ─────────── */

    @Test
    fun `图片一律换成占位词 —— 不带路径也不用 base64`() {
        val s = session(msg("user", "", images = listOf("/data/x.jpg")), msg("assistant", "看到了"))
        val txt = Exporter.chat(s, persona, ExportFormat.TEXT)
        assertTrue("要有 [图片] 这个占位：$txt", txt.contains(Exporter.IMAGE_PLACEHOLDER))
        assertFalse("绝不该把本地路径写进导出：$txt", txt.contains("/data/x.jpg"))
    }

    @Test
    fun `有正文又有图时，两个都要在`() {
        val s = session(msg("user", "你看这个", images = listOf("/a.jpg")))
        val txt = Exporter.chat(s, persona, ExportFormat.TEXT)
        assertTrue(txt.contains("你看这个"))
        assertTrue(txt.contains(Exporter.IMAGE_PLACEHOLDER))
    }

    /* ─────────── CSV（最容易坏的那个） ─────────── */

    @Test
    fun `CSV 的逗号被转义 —— 否则 Excel 里会错列`() {
        // ⚠️ 用**半角**逗号：CSV 的分隔符只认它。全角「，」在 CSV 里是普通字符，
        //    拿它来测会得到"没转义"的假失败（我第一版就写错了）。
        val cell = Exporter.csvCell("他说,走吧")
        assertEquals("\"他说,走吧\"", cell)
    }

    @Test
    fun `CSV 的引号被翻倍 —— 这是 RFC 的写法`() {
        assertEquals("\"他说\"\"好\"\"\"", Exporter.csvCell("他说\"好\""))
    }

    @Test
    fun `CSV 的换行被包进引号里`() {
        assertEquals("\"第一行\n第二行\"", Exporter.csvCell("第一行\n第二行"))
    }

    @Test
    fun `CSV 普通内容不加引号`() {
        assertEquals("普通内容", Exporter.csvCell("普通内容"))
    }

    @Test
    fun `CSV 有表头且每行三列`() {
        val s = session(msg("user", "你好"), msg("assistant", "在的"))
        val csv = Exporter.chat(s, persona, ExportFormat.CSV)
        val lines = csv.trim().split("\n")
        assertEquals("时间,角色,内容", lines[0])
        assertEquals(3, lines.size)
        assertTrue("每行的逗号分隔不该因为内容里的逗号而变多", lines[1].split(",").size >= 3)
    }

    /* ─────────── HTML 转义 ─────────── */

    @Test
    fun `HTML 会转义尖括号 —— 否则正文里的标签会吃掉内容`() {
        val s = session(msg("user", "看这个 <b>粗体</b> 和 <script>alert(1)</script>"))
        val html = Exporter.chat(s, persona, ExportFormat.HTML)
        assertTrue("原始标签必须被转义", html.contains("&lt;b&gt;"))
        assertFalse("绝不能把用户的文本原样塞进 HTML", html.contains("<script>alert(1)</script>"))
    }

    @Test
    fun `HTML 是完整文档且带样式`() {
        val html = Exporter.chat(session(msg("user", "hi")), persona, ExportFormat.HTML)
        assertTrue(html.startsWith("<!DOCTYPE html>"))
        assertTrue(html.contains("</html>"))
        assertTrue("要内联样式（外链一断就变裸文本）", html.contains("<style>"))
    }

    /* ─────────── 五种格式都能产出 ─────────── */

    @Test
    fun `五种格式都产出非空且带上正文`() {
        val s = session(msg("user", "在吗"), msg("assistant", "在的"))
        ExportFormat.entries.forEach { f ->
            val out = Exporter.chat(s, persona, f)
            assertTrue("$f 不该是空的", out.isNotBlank())
            assertTrue("$f 里该有正文", out.contains("在的"))
        }
    }

    @Test
    fun `Markdown 与纯文本用「我 · 人设名」而不是内部的 user assistant`() {
        val s = session(msg("user", "在吗"), msg("assistant", "在的"))
        val md = Exporter.chat(s, persona, ExportFormat.MARKDOWN)
        assertTrue(md.contains("我"))
        assertTrue("对方要显示成人设名：$md", md.contains("初雪"))
        assertFalse("不该把内部 role 名漏给用户", md.contains("assistant"))
    }

    @Test
    fun `人设没了也照样导得出来 —— 显示成「Ta」而不是崩掉`() {
        val out = Exporter.chat(session(msg("user", "hi")), null, ExportFormat.MARKDOWN)
        assertTrue(out.contains("Ta"))
    }

    /* ─────────── 人设 / 记忆 ─────────── */

    @Test
    fun `人设导出的 CSV 有表头 —— 含换行的字段被包进引号（会跨行，这是对的）`() {
        val csv = Exporter.personas(listOf(persona), ExportFormat.CSV)
        assertTrue(csv.startsWith("名称,"))
        // ⚠️ **不要**断言"只有两行"：这份人设的 customPrompt 里有换行，
        //    按 RFC 它必须跨行（整格被引号包住）。断言行数等于把一个正确行为钉成错的
        //   —— 我第一版就是这么写的，于是"测试失败"其实是我错它不是。
        assertTrue("含换行的字段要被引号包起来", csv.contains("\"角色名称：初雪"))
    }

    @Test
    fun `人设的 JSON 标了格式名 —— 将来导入要靠它认`() {
        val json = Exporter.personas(listOf(persona), ExportFormat.JSON)
        assertTrue(json.contains("yuki-personas-v1"))
        assertTrue(json.contains("初雪"))
    }

    @Test
    fun `记忆导出带上属于谁`() {
        val m = MemoryEntity(
            id = "m1",
            userId = MemoryEntity.LOCAL_USER_ID,
            personaId = "p1",
            sessionId = null,
            scope = MemoryEntity.SCOPE_PERSONA,
            content = "她喜欢下雨天",
            category = "preference",
            importance = 7,
            source = "auto",
            embedding = null,
            createdAt = 1_700_000_000_000L,
            lastAccessedAt = 1_700_000_000_000L,
            expiresAt = null,
        )
        val md = Exporter.memories(listOf(m), { "初雪" }, ExportFormat.MARKDOWN)
        assertTrue(md.contains("她喜欢下雨天"))
        assertTrue(md.contains("初雪"))
    }
}
