package ai.yuki.chuxue.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Markdown 基础渲染的解析规格（只做加粗 / 斜体 / 行内代码 / 代码块）。
 *
 * ## 为什么做成纯函数
 * 模型很爱输出 `**强调**` 与 ``` 代码块 —— 不渲染的话，用户看到的是满屏星号。
 * 但这东西**边界极多**（未闭合怎么办、代码块里要不要解析、星号当乘号用怎么办），
 * 在真机上一条条试代价太高。做成纯解析 + 中间表示，就能在这里穷举钉死。
 *
 * ## 原则（与 TranscriptText 一致）
 * **拿不准就原样显示。** 宁可让用户看到一个星号，也不要吃掉他真正的内容。
 */
class MarkdownLiteTest {

    private fun spans(text: String) = MarkdownLite.parse(text)
        .filterIsInstance<MarkdownLite.Block.Paragraph>()
        .flatMap { it.spans }

    private fun plain(text: String) = spans(text).map { it.text }

    /* ══════════════ 行内 ══════════════ */

    @Test
    fun `纯文本产出一个无样式片段`() {
        val s = spans("你好呀")
        assertEquals(1, s.size)
        assertEquals("你好呀", s[0].text)
        assertTrue("无标记就不该带任何样式", s[0].styles.isEmpty())
    }

    @Test
    fun `双星号是粗体`() {
        val s = spans("我很**想你**今天")
        assertEquals(listOf("我很", "想你", "今天"), s.map { it.text })
        assertEquals(setOf(MarkdownLite.Style.BOLD), s[1].styles)
    }

    @Test
    fun `单星号与下划线都是斜体`() {
        assertEquals(setOf(MarkdownLite.Style.ITALIC), spans("*嗯*")[0].styles)
        assertEquals(setOf(MarkdownLite.Style.ITALIC), spans("_嗯_")[0].styles)
    }

    @Test
    fun `反引号是行内代码`() {
        val s = spans("她说 `你好` 了")
        assertEquals(listOf("她说 ", "你好", " 了"), s.map { it.text })
        assertEquals(setOf(MarkdownLite.Style.CODE), s[1].styles)
    }

    @Test
    fun `未闭合的标记原样保留`() {
        assertEquals(listOf("**没闭合"), plain("**没闭合"))
        assertEquals(listOf("`也没闭合"), plain("`也没闭合"))
        assertEquals(listOf("*就一个星号"), plain("*就一个星号"))
    }

    @Test
    fun `星号当乘号用时不被吃掉`() {
        assertEquals(listOf("2*3=6"), plain("2*3=6"))
    }

    @Test
    fun `多个标记混在一句里`() {
        val s = spans("**粗**和*斜*还有`码`")
        assertEquals(listOf("粗", "和", "斜", "还有", "码"), s.map { it.text })
        assertEquals(setOf(MarkdownLite.Style.BOLD), s[0].styles)
        assertEquals(setOf(MarkdownLite.Style.ITALIC), s[2].styles)
        assertEquals(setOf(MarkdownLite.Style.CODE), s[4].styles)
    }

    /* ══════════════ 代码块 ══════════════ */

    @Test
    fun `三重围栏是代码块，内部不解析行内标记`() {
        val blocks = MarkdownLite.parse("```kotlin\nval a = **1**\n```")
        assertEquals(1, blocks.size)
        val code = blocks[0] as MarkdownLite.Block.Code
        assertEquals("val a = **1**", code.code.trim())
        assertEquals("kotlin", code.lang)
    }

    @Test
    fun `没有语言标注的代码块也能解析`() {
        val blocks = MarkdownLite.parse("```\n小段代码\n```")
        val code = blocks[0] as MarkdownLite.Block.Code
        assertEquals("小段代码", code.code.trim())
        assertEquals(null, code.lang)
    }

    @Test
    fun `普通文本与代码块混排`() {
        val blocks = MarkdownLite.parse("看这个：\n```\nx = 1\n```\n懂了吗")
        assertEquals(3, blocks.size)
        assertTrue(blocks[0] is MarkdownLite.Block.Paragraph)
        assertTrue(blocks[1] is MarkdownLite.Block.Code)
        assertTrue(blocks[2] is MarkdownLite.Block.Paragraph)
    }

    @Test
    fun `未闭合的代码块按普通段落处理 —— 不吞掉后半段`() {
        val blocks = MarkdownLite.parse("```\n没有收尾")
        assertEquals(1, blocks.size)
        assertTrue(
            "未闭合的围栏不该把内容变成代码块（那会吞掉标记解析）",
            blocks[0] is MarkdownLite.Block.Paragraph,
        )
    }

    /* ══════════════ 边界 ══════════════ */

    @Test
    fun `空文本不崩且产出空列表`() {
        assertTrue(MarkdownLite.parse("").isEmpty())
    }

    @Test
    fun `换行保留在段落里`() {
        val blocks = MarkdownLite.parse("第一行\n第二行")
        val text = (blocks[0] as MarkdownLite.Block.Paragraph).spans.joinToString("") { it.text }
        assertEquals("第一行\n第二行", text)
    }
}
