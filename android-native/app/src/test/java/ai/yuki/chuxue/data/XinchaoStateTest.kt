package ai.yuki.chuxue.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「Ta 此刻」状态解析的**纯函数**契约（v0.61.35）。
 *
 * 网络没法单测（与 `XinchaoReportTest` / `AppFeaturesParseTest` 同一条纪律：
 * 把响应解析抽成纯函数，在 JVM 上钉死字段名与挑选逻辑），这里钉的就是解析。
 *
 * 样本照**真实的** `GET /xinchao/personas` 响应裁剪 —— 尤其 `view.text` 是服务端
 * 已清洗过的用户向文本（不是心潮原始 /v1/now）。
 */
class XinchaoStateTest {

    private val raw = """
        {"personas":[
          {"personaId":"p1","name":"初雪","online":true,"view":{
            "text":"心情：平静偏暖，有点起伏；刚才被安抚\n另外：昨夜有梦。细节都在记忆里",
            "consciousness":"sleeping",
            "emotion":{"label":"平静","valence":0.63,"arousal":0.39},
            "drives":{"possess":0.7,"monitor":0.5,"share":0.3,"boredom":0.1},
            "recentDreams":[{"dream":"梦到草莓"}]
          }},
          {"personaId":"p2","name":"阿雪儿","view":{"text":"醒着"}}
        ]}
    """.trimIndent()

    @Test
    fun `挑出对应人设 —— 字段逐条对上`() {
        val s = XinchaoStateApi.parse(raw, "p1")!!
        assertTrue("清洗后的此刻文本要原样带出来", s.text.contains("平静偏暖"))
        assertEquals("平静", s.emotionLabel)
        assertEquals(0.63, s.valence!!, 0.0001)
        assertEquals("梦到草莓", s.recentDream)
        assertEquals("睡着了", s.wakeText)
    }

    @Test
    fun `驱力 —— 按强度降序、键翻成用户词`() {
        val s = XinchaoStateApi.parse(raw, "p1")!!
        assertEquals(4, s.drives.size)
        // 0.7 > 0.5 > 0.3 > 0.1 —— possess 最惦记，排在第一个
        assertEquals("想你", s.drives[0].word)
        assertEquals(0.7, s.drives[0].level, 0.0001)
        assertEquals("牵挂", s.drives[1].word)
    }

    @Test
    fun `认不出的驱力键原样保留 —— 不丢信息`() {
        val j = """{"personas":[{"personaId":"x","view":{"drives":{"brand_new":0.9}}}]}"""
        assertEquals("brand_new", XinchaoStateApi.parse(j, "x")!!.drives[0].word)
    }

    @Test
    fun `多人设里只挑本人设 —— 别人设的数据不串进来`() {
        val s = XinchaoStateApi.parse(raw, "p2")!!
        assertEquals("醒着", s.text)
        assertTrue("p2 没有 drives，不该捡到 p1 的", s.drives.isEmpty())
        assertNull("p2 没有梦", s.recentDream)
    }

    @Test
    fun `列表里没有这个人设 —— 返回 null`() {
        assertNull(XinchaoStateApi.parse(raw, "不存在"))
    }

    @Test
    fun `坏输入一律 null —— 不崩`() {
        assertNull(XinchaoStateApi.parse(null, "p1"))
        assertNull(XinchaoStateApi.parse("", "p1"))
        assertNull(XinchaoStateApi.parse("不是 JSON", "p1"))
        assertNull(XinchaoStateApi.parse("[]", "p1"))
        assertNull(XinchaoStateApi.parse("""{"personas":[]}""", "p1"))
        assertNull("空 personaId 不打无意义匹配", XinchaoStateApi.parse(raw, ""))
    }

    @Test
    fun `接入但还没聊过 —— isEmpty 为真`() {
        val j = """{"personas":[{"personaId":"x","view":{"text":"","consciousness":"awake"}}]}"""
        val s = XinchaoStateApi.parse(j, "x")!!
        assertTrue(s.isEmpty)
        assertEquals("醒着", s.wakeText)
    }

    @Test
    fun `有内容就不是 isEmpty`() {
        assertFalse(XinchaoStateApi.parse(raw, "p1")!!.isEmpty)
    }
}
