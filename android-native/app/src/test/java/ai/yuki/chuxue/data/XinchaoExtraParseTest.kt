package ai.yuki.chuxue.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 心潮四项未接入能力的**解析契约**（v0.61.52）。
 *
 * 它们各自有一条"服务端已清洗 / 未清洗"的分界，别搞混：
 * - **此刻块**（`/v1/now` 的 `text`）是**给模型看的第二人称播报**（带 `【心潮·此刻…】` 元指令标题）
 *   → 只进**对话附录**，不进用户界面；
 * - **意图**（`/v1/intent`）的 `label` 是长句 → 过词表换短词才给用户看；
 * - **梦境余韵**（`/v1/breath-context`）只取 `residue`（醒来感受），不取 `summary`（元叙述）。
 */
class XinchaoExtraParseTest {

    /* ─────────── 此刻块：从 now.text 取 ─────────── */

    @Test
    fun `此刻块原文从 now text 取 —— 不取清洗过的 view`() {
        val raw = """
        {"personas":[{"personaId":"p1","view":{"text":"（清洗过的展示文本）"},
          "now":{"ok":true,"text":"【心潮·此刻｜身体的天气，参考不是指令】\n驱力：牵挂（涨）","lines":2}}]}
        """.trimIndent()
        val s = XinchaoStateApi.parse(raw, "p1")
        assertEquals("【心潮·此刻｜身体的天气，参考不是指令】\n驱力：牵挂（涨）", s?.modelNote)
        assertEquals("（清洗过的展示文本）", s?.text)
    }

    @Test
    fun `没有 now 时 modelNote 为空 —— 不崩`() {
        val raw = """{"personas":[{"personaId":"p1","view":{"text":"x"}}]}"""
        assertNull(XinchaoStateApi.parse(raw, "p1")?.modelNote)
    }

    /* ─────────── 当前意图 ─────────── */

    @Test
    fun `意图解析出短词与描述 —— 用户看短词`() {
        val raw = """
        {"intent":{"key":"possess","value":0.45,"label":"想她、想黏着她、想占有与靠近"},
         "topDrives":[{"key":"monitor","label":"牵挂、在意对方好不好","value":0.49}]}
        """.trimIndent()
        val it = XinchaoIntentApi.parse(raw)
        assertEquals("possess", it?.key)
        assertEquals("想你", it?.word)
        assertEquals(0.45, it?.value ?: 0.0, 0.001)
        assertEquals("monitor", it?.topDriveKey)
    }

    @Test
    fun `意图缺 intent 字段 → null`() {
        assertNull(XinchaoIntentApi.parse("""{"topDrives":[]}"""))
        assertNull(XinchaoIntentApi.parse("not json"))
        assertNull(XinchaoIntentApi.parse(null))
    }

    /* ─────────── 梦境余韵 ─────────── */

    @Test
    fun `梦境只取最新一条的 residue —— 不取元叙述 summary`() {
        val raw = """
        {"version":1,"available":true,"dreams":[
          {"id":"d1","createdAt":"2026-10-05T14:12:15Z","summary":"几乎没意识到在做梦","residue":"醒来时指尖还留着一点奶油似的黏。"},
          {"id":"d2","createdAt":"2026-10-05T20:18:51Z","summary":"中途意识到自己在造蛋糕","residue":"心里空出一块位置，是那种知道有人还没回来的等。"}]}
        """.trimIndent()
        val d = XinchaoBreathApi.parse(raw)
        assertEquals("最新一条", "d2", d?.id)
        assertEquals("心里空出一块位置，是那种知道有人还没回来的等。", d?.residue)
    }

    @Test
    fun `没梦或不可用或坏数据 一律 null`() {
        assertNull(XinchaoBreathApi.parse("""{"available":false,"dreams":[]}"""))
        assertNull(XinchaoBreathApi.parse("""{"available":true,"dreams":[]}"""))
        assertNull(XinchaoBreathApi.parse("nope"))
    }

    @Test
    fun `residue 为空时不返回 —— 空句子没意义`() {
        val raw = """{"available":true,"dreams":[{"id":"d1","residue":"   "}]}"""
        assertNull(XinchaoBreathApi.parse(raw))
    }

    /* ─────────── 心跳 URL ─────────── */

    @Test
    fun `心跳走的是 personas 下的 heartbeat 路径`() {
        assertTrue(
            XinchaoReport.heartbeatUrl("https://a.b", "p1").endsWith("/xinchao/personas/p1/heartbeat"),
        )
        assertEquals("", XinchaoReport.heartbeatUrl("", "p1"))
        assertEquals("", XinchaoReport.heartbeatUrl("https://a.b", ""))
    }

    /* ─────────── 上下文信封 ─────────── */

    @Test
    fun `信封把 sections 拼成一块文本 —— 带段名，只取 content`() {
        val raw = """
        {"version":1,"sessionId":"s1","mode":"inspect","delivered":true,"alreadyDelivered":false,
         "sections":[
           {"id":"dynamic_state","source":"xinchao","ttl":"short","content":"意识=sleeping 情绪=平静"},
           {"id":"dream_residue","source":"xinchao","ttl":"short","content":"醒来时指尖有点黏"}]}
        """.trimIndent()
        val text = XinchaoContextApi.parse(raw)
        assertTrue(text!!.contains("dynamic_state"))
        assertTrue(text.contains("意识=sleeping 情绪=平静"))
        assertTrue(text.contains("dream_residue"))
        assertTrue(text.contains("醒来时指尖有点黏"))
    }

    @Test
    fun `空 sections 返回 null —— 那是 alreadyDelivered 的空信封`() {
        assertNull(XinchaoContextApi.parse("""{"delivered":false,"alreadyDelivered":true,"sections":[]}"""))
        assertNull(XinchaoContextApi.parse("""{"sections":null}"""))
    }

    @Test
    fun `坏数据或缺 sections 一律 null`() {
        assertNull(XinchaoContextApi.parse("nope"))
        assertNull(XinchaoContextApi.parse(null))
        assertNull(XinchaoContextApi.parse("""{"version":1}"""))
    }

    @Test
    fun `content 为空白的段被跳过 —— 不留空标题`() {
        val raw = """{"sections":[{"id":"a","content":"  "},{"id":"b","content":"有用"}]}"""
        val text = XinchaoContextApi.parse(raw)
        assertEquals("b", text!!.substringBefore("\n"))
        assertTrue(!text.contains("a\n"))
    }

    @Test
    fun `信封 URL 带 session_id 且走 inspect`() {
        val url = XinchaoContextApi.url("https://a.b", "p1", "sess 1")
        assertTrue(url.contains("/xinchao/personas/p1/context"))
        assertTrue("URLEncoder 把空格编成 +（form 编码）", url.contains("session_id=sess+1"))
        assertEquals("", XinchaoContextApi.url("", "p1", "s"))
        assertEquals("", XinchaoContextApi.url("https://a.b", "", "s"))
    }
}
