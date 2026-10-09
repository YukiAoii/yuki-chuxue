package ai.yuki.chuxue.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「用户消息气泡里混进后台注入的附录」这个缺陷的守卫。
 *
 * ## 缺陷现场
 * 用户输入「那你知道我喜欢什么吗」，发出后气泡里显示的却是：
 * ```
 * <appendix>
 * <memories>
 *   <memory>用户喜欢喝热可可，要加两颗棉花糖、不加肉桂粉</memory>
 *   …
 * </memories>
 * </appendix>
 * 那你知道我喜欢什么吗
 * ```
 * 原因：`PromptEngine.plan()` 把附录拼进了 `userMessage.content`，
 * 而 ViewModel 把**同一个实例**发往 API、写进历史、又拿去上屏 ——
 * 于是"给模型看的记忆"被原样呈现给了用户。
 *
 * ## 为什么不改数据、只改显示
 * 附录进历史是**刻意的**：它保证「本轮请求的字节」与「下一轮从历史读出的字节」一致，
 * 否则每轮都会在最近一条用户消息处断开，缓存命中率会塌。
 * 所以修在显示层 —— 数据不动，缓存行为不变。
 */
class TranscriptTextTest {

    private val appendix =
        "<appendix>\n<memories>\n" +
            "  <memory>用户喜欢热可可</memory>\n" +
            "</memories>\n</appendix>"

    @Test
    fun `剥掉后台注入的附录，只留用户真正说的话`() {
        assertEquals(
            "那你知道我喜欢什么吗",
            TranscriptText.stripAppendix("$appendix\n那你知道我喜欢什么吗"),
        )
    }

    @Test
    fun `没有附录的普通消息原样返回`() {
        assertEquals("你好呀", TranscriptText.stripAppendix("你好呀"))
    }

    @Test
    fun `只有附录、没有正文时返回空串`() {
        assertEquals("", TranscriptText.stripAppendix(appendix))
    }

    @Test
    fun `结构不完整时不猜、原样返回`() {
        val broken = "<appendix>\n<memories>没有收尾"
        assertEquals(
            "宁可显示难看，也不要凭猜测截断用户的内容",
            broken,
            TranscriptText.stripAppendix(broken),
        )
    }

    @Test
    fun `正文里出现 appendix 字样不被误伤 —— 只有开头是标签才剥`() {
        val text = "我很好奇 appendix 这个词是什么意思"
        assertEquals(text, TranscriptText.stripAppendix(text))
    }

    @Test
    fun `剥完清理前后空白`() {
        assertEquals("正文", TranscriptText.stripAppendix("$appendix\n\n  正文  "))
    }

    @Test
    fun `多段附录内容也能整体剥掉`() {
        val two = "$appendix\n$appendix\n真正的话"
        assertEquals("真正的话", TranscriptText.stripAppendix(two))
    }
}
