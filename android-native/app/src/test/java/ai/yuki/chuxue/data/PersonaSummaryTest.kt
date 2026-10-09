package ai.yuki.chuxue.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「AI 填人设详情」的规格（v0.61.21）。
 *
 * ## 为什么它值得有测试
 * 这段逻辑原本会是"拼个提示词、把回来的字贴到界面上"—— 那等于**永远没人验过**
 *（本机无设备、无 Compose 测试基建）。抽成 [PersonaSummary] 之后，
 * "提示词怎么拼""脏输出怎么清""什么时候该重生成"全都钉得住。
 *
 * ## 三条约束各自对着一种翻车方式
 * 提示词那三条要求不是凑数的，每一条都对着模型**已知会犯**的毛病；
 * 清洗那几条对着的是**真的会显示在界面上**的脏东西（标题符号、开场白、换行）。
 */
class PersonaSummaryTest {

    private val p = Persona(
        id = "p1",
        roleName = "小雪",
        userGender = "女",
        personality = "外冷内热",
        customPrompt = "咖啡师，话少，喜欢在雨天发呆。",
    )

    /* ─────────── 提示词 ─────────── */

    @Test
    fun `提示词带上人设内容 —— 不然模型无从写起`() {
        val prompt = PersonaSummary.buildPrompt(p)
        assertTrue(prompt.contains("小雪"))
        assertTrue(prompt.contains("外冷内热"))
        assertTrue(prompt.contains("咖啡师"))
    }

    @Test
    fun `提示词明确要求只输出正文 —— 模型爱加"好的，这是…"这种开场白`() {
        val prompt = PersonaSummary.buildPrompt(p)
        assertTrue(prompt.contains("只输出介绍本身"))
    }

    @Test
    fun `提示词要求不要复述字段名 —— 否则等于把参数表换成散文`() {
        assertTrue(PersonaSummary.buildPrompt(p).contains("不要复述"))
    }

    @Test
    fun `提示词要求用 Ta 指代 —— 人设千奇百怪，不能替用户认定性别`() {
        assertTrue(PersonaSummary.buildPrompt(p).contains("Ta"))
    }

    @Test
    fun `空字段不拼进提示词 —— 不留"名字："这种空标签`() {
        val bare = Persona(id = "p2", customPrompt = "只有设定")
        val prompt = PersonaSummary.buildPrompt(bare)
        assertFalse(prompt.contains("名字："))
        assertFalse(prompt.contains("性格："))
        assertTrue(prompt.contains("只有设定"))
    }

    @Test
    fun `额度写得很小 —— 用户明确说过"别写太多消耗"`() {
        assertTrue(PersonaSummary.MAX_TOKENS <= 300)
    }

    /* ─────────── 清洗 ─────────── */

    @Test
    fun `去掉首尾成对的引号`() {
        assertEquals("她很安静", PersonaSummary.clean("\"她很安静\""))
        assertEquals("她很安静", PersonaSummary.clean("「她很安静」"))
        assertEquals("她很安静", PersonaSummary.clean("“她很安静”"))
    }

    @Test
    fun `去掉 markdown 标记与标题符号`() {
        assertEquals("她很安静", PersonaSummary.clean("**她很安静**"))
        assertEquals("她很安静", PersonaSummary.clean("## 她很安静"))
    }

    @Test
    fun `换行压成空格 —— 详情页那是一行文字，不是一篇文章`() {
        assertEquals("她很安静 喜欢雨天", PersonaSummary.clean("她很安静\n\n喜欢雨天"))
    }

    @Test
    fun `空产出返回 null —— 调用方靠它保持"待生成"`() {
        assertNull(PersonaSummary.clean(null))
        assertNull(PersonaSummary.clean(""))
        assertNull(PersonaSummary.clean("   \n  "))
        assertNull(PersonaSummary.clean("\"\""))
    }

    @Test
    fun `正常产出原样留下`() {
        assertNotNull(PersonaSummary.clean("她是咖啡师，话少，喜欢在雨天发呆。"))
    }

    /* ─────────── 什么时候该重新生成 ─────────── */

    @Test
    fun `从没生成过 —— 要生成`() {
        assertTrue(p.needsDetailSummary)
    }

    @Test
    fun `生成过且人设没改 —— 不再生成（这份钱只花一次）`() {
        val done = p.copy(
            detailSummary = "她是咖啡师。",
            detailSummaryKey = p.detailSummaryFingerprint,
        )
        assertFalse(done.needsDetailSummary)
    }

    @Test
    fun `人设内容改了 —— 那句简介过期了，要重新生成`() {
        val done = p.copy(
            detailSummary = "她是咖啡师。",
            detailSummaryKey = p.detailSummaryFingerprint,
        )
        assertTrue(done.copy(customPrompt = "换了个完全不同的设定").needsDetailSummary)
    }

    @Test
    fun `改头像或备注或置顶不该触发重新生成 —— 那些不影响那句话`() {
        val done = p.copy(
            detailSummary = "她是咖啡师。",
            detailSummaryKey = p.detailSummaryFingerprint,
        )
        assertFalse(done.copy(avatarPath = "/x/y.jpg").needsDetailSummary)
        assertFalse(done.copy(note = "自己看的备注").needsDetailSummary)
        assertFalse(done.copy(isPinned = true).needsDetailSummary)
    }

    @Test
    fun `有简介但指纹缺失（老数据或半途中断）—— 仍算待生成，不能白信它`() {
        val half = p.copy(detailSummary = "她是咖啡师。", detailSummaryKey = null)
        assertTrue(half.needsDetailSummary)
    }
}
