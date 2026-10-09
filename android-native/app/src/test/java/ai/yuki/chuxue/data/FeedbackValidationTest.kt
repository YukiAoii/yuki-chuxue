package ai.yuki.chuxue.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 反馈的**本地校验契约**（纯函数，定义在 `data/FeedbackApi.kt`）。
 *
 * 为什么值得钉：它是"用户会不会白发一次"的唯一闸门。真正该拦的只有两件事 ——
 * **空内容**与**超长**。多拦一条（比如把正常长度的内容也拦了）会让用户
 * 写了一段话却发不出去，而那种失败**看起来像 bug**。
 *
 * ⚠️ 这些断言证明的是"我们约定怎么校验"，**证明不了后端也这么算** ——
 * 后端口径是 Python 的 `len()`。两边都按"字符数"理解；对中文一致，
 * 对 emoji 本地会偏严（UTF-16 代理对算 2），方向安全。
 */
class FeedbackValidationTest {

    private fun n(c: Char, k: Int) = buildString { repeat(k) { append(c) } }

    @Test
    fun `空内容要拦住`() {
        assertTrue(feedbackValidationError("", "") != null)
        assertTrue("只有空白也算空", feedbackValidationError("   \n\t ", "") != null)
    }

    @Test
    fun `正常内容放行`() {
        assertNull(feedbackValidationError("用着挺好，就是想提个意见", ""))
    }

    @Test
    fun `内容刚好 500 字放行、501 拦住`() {
        assertNull(feedbackValidationError(n('字', FEEDBACK_CONTENT_MAX), ""))
        val err = feedbackValidationError(n('字', FEEDBACK_CONTENT_MAX + 1), "")
        assertTrue("超长必须给一句人话，而不是静默", err != null)
        assertTrue("提示里要带上实际字数", err!!.contains("${FEEDBACK_CONTENT_MAX + 1}"))
    }

    @Test
    fun `联系方式可选，但超 100 字要拦住`() {
        assertNull("不填也得能发", feedbackValidationError("内容", ""))
        assertNull(feedbackValidationError("内容", n('a', FEEDBACK_CONTACT_MAX)))
        assertTrue(feedbackValidationError("内容", n('a', FEEDBACK_CONTACT_MAX + 1)) != null)
    }

    @Test
    fun `请求体字段名与后端约定一致`() {
        val raw = FeedbackApi.body("bug", "出问题了", "a@b.c")
        assertTrue("字段名是 kind/content/contact —— 与后端 /api/feedback 对齐", raw.contains("\"kind\""))
        assertTrue(raw.contains("\"content\""))
        assertTrue(raw.contains("\"contact\""))
    }

    @Test
    fun `能取出后端 detail 里那句话`() {
        assertEquals(
            "发得太快了，歇一分钟再试",
            FeedbackApi.parseError("""{"detail":"发得太快了，歇一分钟再试"}""", "兜底"),
        )
        assertEquals("兜底", FeedbackApi.parseError("不是 JSON", "兜底"))
        assertEquals("兜底", FeedbackApi.parseError("""{}""", "兜底"))
    }
}
