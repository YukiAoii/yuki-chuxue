package ai.yuki.chuxue.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「Ta 来找我」取件接口的**纯函数**契约（v0.61.40）。
 *
 * 网络没法单测（与 `XinchaoMemoryTest` / `XinchaoReportTest` 同一条纪律：
 * 把 URL 拼装与响应解析抽成纯函数，在 JVM 上钉死格式）。
 */
class XinchaoPendingTest {

    @Test
    fun `取件 URL —— 不带 since 与带 since（ISO 串里的加号必须转义）`() {
        assertEquals(
            "https://s.example/xinchao/personas/p1/pending",
            XinchaoPendingApi.pendingUrl("https://s.example", "p1"),
        )
        // ⚠️ ISO 时间串里的 `+08:00` 那个加号：不编码的话服务端会把它读成空格，
        //    since 比对直接失效（消息会重弹/漏弹）—— 这条测试就是钉这个。
        val withSince = XinchaoPendingApi.pendingUrl(
            "https://s.example/", "p1", "2026-10-06T03:20:34+08:00",
        )
        assertTrue("缺 ?since=", withSince.contains("?since="))
        assertTrue("加号没被编码", withSince.contains("%2B"))
        assertFalse("出现了裸加号（会被读成空格）", withSince.substringAfter("?").contains("+"))
    }

    @Test
    fun `取件 URL —— 空参给空串（调用方据此跳过）`() {
        assertEquals("", XinchaoPendingApi.pendingUrl("", "p1"))
        assertEquals("", XinchaoPendingApi.pendingUrl("https://s.example", ""))
    }

    @Test
    fun `解析响应 —— 逐条取 at kind message`() {
        val raw = """
            {"personaId":"p1","owner":"u1","messages":[
              {"at":"2026-10-06T03:20:34+08:00","kind":"autonomous","message":"团子又趴键盘上了。"},
              {"at":"2026-10-06T05:00:00+08:00","kind":"dream_push","message":"我梦到草莓了。"}
            ]}
        """.trimIndent()
        val list = XinchaoPendingApi.parse(raw)!!
        assertEquals(2, list.size)
        assertEquals("2026-10-06T03:20:34+08:00", list[0].at)
        assertEquals("autonomous", list[0].kind)
        assertEquals("团子又趴键盘上了。", list[0].message)
        assertEquals("我梦到草莓了。", list[1].message)
    }

    @Test
    fun `解析响应 —— 空 message 的条目跳过，坏输入 null，空列表给空表`() {
        val raw = """{"messages":[{"at":"t1","kind":"k","message":""},{"at":"t2","kind":"k","message":"在"}]}"""
        assertEquals(1, XinchaoPendingApi.parse(raw)!!.size)
        assertNull(XinchaoPendingApi.parse("不是 JSON"))
        assertNull(XinchaoPendingApi.parse(null))
        assertNull("messages 不是数组应判认不出", XinchaoPendingApi.parse("""{"messages":"x"}"""))
        assertEquals(
            0,
            XinchaoPendingApi.parse("""{"personaId":"p1","messages":[]}""")!!.size,
        )
    }
}
