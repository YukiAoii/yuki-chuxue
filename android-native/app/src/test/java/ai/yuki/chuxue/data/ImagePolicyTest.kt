package ai.yuki.chuxue.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 一次能带几张图的规格（用户定的上限：**多选最多 9 张**）。
 *
 * ⚠️ 它与 `MAX_SINGLE_BYTES` / `MAX_REQUEST_BYTES` 是**三种不同的限制**，别混：
 * - 那两个是**服务端的硬约束**（超了整请求 400，是"发不出去"）；
 * - 9 张是**产品上的取舍**（用户要的上限）—— 超了只是不许再加，
 *   而不是"请求会失败"。所以它拒的是**加图**这个动作，不是发送。
 */
class ImagePolicyTest {

    /** 一张最小可用的 data URL（格式合法即可，内容不参与这些用例）。 */
    private fun img() = "data:image/jpeg;base64,AAAA"

    @Test
    fun `上限常量就是 9`() {
        assertEquals(9, ImagePolicy.MAX_IMAGES)
    }

    @Test
    fun `正好九张放行`() {
        assertEquals(ImagePolicy.Check.Ok, ImagePolicy.check("user", List(9) { img() }))
    }

    @Test
    fun `第十张被拒，且话里带上限数字`() {
        val result = ImagePolicy.check("user", List(10) { img() })
        assertTrue("十张应当被拒，实际 $result", result is ImagePolicy.Check.Rejected)
        val reason = (result as ImagePolicy.Check.Rejected).reason
        assertTrue("要说清上限是 9：$reason", reason.contains("9"))
    }

    @Test
    fun `空表照旧放行`() {
        assertEquals(ImagePolicy.Check.Ok, ImagePolicy.check("user", emptyList()))
    }
}
