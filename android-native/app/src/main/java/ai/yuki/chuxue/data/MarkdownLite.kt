package ai.yuki.chuxue.data

/**
 * Markdown 的**基础**渲染（只认加粗 / 斜体 / 行内代码 / 代码块）。
 *
 * ## 为什么只做"基础"
 * 模型很爱输出 `**强调**`、`` `代码` `` 和 ``` 围栏 —— 完全不渲染的话，用户看到的是
 * 满屏星号与反引号，读起来像乱码。但完整 Markdown（表格、嵌套列表、链接、图片）
 * 是个大坑：既容易与聊天气泡的排版打架，也没必要。
 * 所以只做最常出现的四种，**做对边界**比做多更重要。
 *
 * ## 为什么解析成自己的中间表示，而不是直接产出 AnnotatedString
 * 中间表示是纯数据 —— 单测能在 JVM 上把每条边界跑穿（含"未闭合怎么办"这种
 * 真机上极难构造的情况），而 Compose 渲染层只负责把样式映射成字体粗细。
 * 若直接在解析里产出 AnnotatedString，这套边界测试就得上 Android 运行时。
 *
 * ## 核心原则：**拿不准就原样显示**
 * 与 [TranscriptText] 同一条纪律 —— 宁可让用户看到一个星号，
 * 也不要吃掉他真正想说的话（模型也会输出 `2*3=6` 这种乘号）。
 */
object MarkdownLite {

    private const val FENCE = "```"

    enum class Style { BOLD, ITALIC, CODE }

    /** 一段带样式的文本。 */
    data class Span(val text: String, val styles: Set<Style> = emptySet())

    sealed interface Block {
        /** 普通段落（含行内样式） */
        data class Paragraph(val spans: List<Span>) : Block

        /** 代码块（``` 围栏），内部**不做**行内解析 */
        data class Code(val code: String, val lang: String?) : Block
    }

    /**
     * 把一段文本切成块。
     *
     * 未闭合的 ``` 围栏**按普通段落处理** —— 不然它会把后面所有内容当成代码，
     * 连带把其中的 `**` 一起吞掉（用户会以为渲染坏了）。
     */
    fun parse(text: String): List<Block> {
        if (text.isEmpty()) return emptyList()

        val blocks = mutableListOf<Block>()
        val lines = text.split('\n')
        val paragraph = StringBuilder()

        fun flushParagraph() {
            if (paragraph.isNotEmpty()) {
                blocks += Block.Paragraph(parseInline(paragraph.toString()))
                paragraph.clear()
            }
        }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.trimStart().startsWith(FENCE)) {
                val close = (i + 1 until lines.size)
                    .firstOrNull { lines[it].trimStart().startsWith(FENCE) }
                if (close != null) {
                    flushParagraph()
                    val lang = line.trim().removePrefix(FENCE).trim().ifBlank { null }
                    blocks += Block.Code(lines.subList(i + 1, close).joinToString("\n"), lang)
                    i = close + 1
                    continue
                }
                // 未闭合 → 当作普通行往下走
            }

            if (paragraph.isNotEmpty()) paragraph.append('\n')
            paragraph.append(line)
            i++
        }
        flushParagraph()
        return blocks
    }

    /**
     * 行内解析：单遍扫描，遇到能配对的标记就切一个 [Span]，否则原样落到文本里。
     *
     * 不支持嵌套（`**粗 *斜* **` 会把内层当普通文字）—— 基础渲染够用，
     * 且嵌套解析是"写起来容易、边界多得吓人"的典型。
     */
    internal fun parseInline(text: String): List<Span> {
        val spans = mutableListOf<Span>()
        val plain = StringBuilder()

        fun flushPlain() {
            if (plain.isNotEmpty()) {
                spans += Span(plain.toString())
                plain.clear()
            }
        }

        var i = 0
        while (i < text.length) {
            val ch = text[i]

            // 行内代码：`…`
            if (ch == '`') {
                val end = text.indexOf('`', i + 1)
                if (end > i + 1) {
                    flushPlain()
                    spans += Span(text.substring(i + 1, end), setOf(Style.CODE))
                    i = end + 1
                    continue
                }
            }

            // 粗体：**…**（必须先于单星号判断）
            if (text.startsWith("**", i)) {
                val end = text.indexOf("**", i + 2)
                if (end > i + 2) {
                    flushPlain()
                    spans += Span(text.substring(i + 2, end), setOf(Style.BOLD))
                    i = end + 2
                    continue
                }
            }

            // 斜体：*…*（且不是 ** 的开头）
            if (ch == '*' && !text.startsWith("**", i)) {
                val end = text.indexOf('*', i + 1)
                if (end > i + 1) {
                    flushPlain()
                    spans += Span(text.substring(i + 1, end), setOf(Style.ITALIC))
                    i = end + 1
                    continue
                }
            }

            // 斜体：_…_
            if (ch == '_') {
                val end = text.indexOf('_', i + 1)
                if (end > i + 1) {
                    flushPlain()
                    spans += Span(text.substring(i + 1, end), setOf(Style.ITALIC))
                    i = end + 1
                    continue
                }
            }

            plain.append(ch)
            i++
        }

        flushPlain()
        return spans
    }
}
