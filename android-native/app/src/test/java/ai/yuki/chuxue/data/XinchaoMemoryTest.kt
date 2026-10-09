package ai.yuki.chuxue.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「云端记忆」读写器的**纯函数**契约（v0.61.36）。
 *
 * 网络没法单测（与 `XinchaoReportTest` / `XinchaoStateTest` 同一条纪律：把 URL 与
 * 请求体抽成纯函数，在 JVM 上钉死字段名与格式），这里钉的就是这两件事 + 响应解析。
 */
class XinchaoMemoryTest {

    @Test
    fun `读 URL —— 不带与带关键词（URL 编码）`() {
        assertEquals(
            "https://s.example/xinchao/personas/p1/memory",
            XinchaoMemoryApi.listUrl("https://s.example", "p1"),
        )
        // 尾斜杠吃掉；中文关键词要 URL 编码（不能被拼成裸中文，否则请求行非法）
        val withQ = XinchaoMemoryApi.listUrl("https://s.example/", "p1", "猫 咖啡")
        assertTrue("缺 ?q=", withQ.endsWith("/memory?q=") || withQ.contains("/memory?q="))
        assertTrue("中文未编码", withQ.contains("%E7%8C%AB"))
        assertFalse("不该出现裸空格", withQ.contains(" "))
    }

    @Test
    fun `URL —— 空参给空串（调用方据此跳过）`() {
        assertEquals("", XinchaoMemoryApi.listUrl("", "p1"))
        assertEquals("", XinchaoMemoryApi.writeUrl("https://s.example", ""))
    }

    @Test
    fun `删除 URL —— 单条与整角色`() {
        assertEquals(
            "https://s.example/xinchao/personas/p1/memory/b123",
            XinchaoMemoryApi.deleteMemoryUrl("https://s.example/", "p1", "b123"),
        )
        assertEquals(
            "https://s.example/xinchao/personas/p1",
            XinchaoMemoryApi.deletePersonaUrl("https://s.example/", "p1"),
        )
        // 缺桶 id / 缺人设 → 空串（调用方不删不该删的东西）
        assertEquals("", XinchaoMemoryApi.deleteMemoryUrl("https://s.example", "p1", ""))
        assertEquals("", XinchaoMemoryApi.deletePersonaUrl("", "p1"))
    }

    @Test
    fun `写响应里取云端桶 id —— 取不到就 null（不崩）`() {
        assertEquals("896dac8962c1", XinchaoMemoryApi.parseWriteBucketId("""{"ok":true,"bucketId":"896dac8962c1"}"""))
        assertNull(XinchaoMemoryApi.parseWriteBucketId("""{"ok":true,"bucketId":null}"""))
        assertNull(XinchaoMemoryApi.parseWriteBucketId("""{"ok":true}"""))
        assertNull(XinchaoMemoryApi.parseWriteBucketId("不是 JSON"))
        assertNull(XinchaoMemoryApi.parseWriteBucketId(null))
    }

    @Test
    fun `写 URL —— 尾斜杠不拼成双斜杠`() {
        assertEquals(
            "https://s.example/xinchao/personas/p1/memory",
            XinchaoMemoryApi.writeUrl("https://s.example/", "p1"),
        )
    }

    @Test
    fun `写请求体 —— 必填 content，可选字段空则省略`() {
        assertEquals("""{"content":"喜欢美式"}""", XinchaoMemoryApi.writeBody("喜欢美式"))
        val full = XinchaoMemoryApi.writeBody("喜欢美式", title = "习惯", category = "喜好", importance = 6)
        assertTrue(full.contains("\"content\":\"喜欢美式\""))
        assertTrue(full.contains("\"title\":\"习惯\""))
        assertTrue(full.contains("\"category\":\"喜好\""))
        assertTrue(full.contains("\"importance\":6"))
        // 空串标题/分类不写进去（沿用服务端"缺省不覆盖"的语义）
        val blank = XinchaoMemoryApi.writeBody("x", title = "  ", category = "")
        assertFalse(blank.contains("title"))
        assertFalse(blank.contains("category"))
    }

    @Test
    fun `importance 夹到 1 到 10`() {
        assertTrue(XinchaoMemoryApi.writeBody("x", importance = 99).contains("\"importance\":10"))
        assertTrue(XinchaoMemoryApi.writeBody("x", importance = 0).contains("\"importance\":1"))
    }

    @Test
    fun `解析读响应 —— 取 text 字段`() {
        val raw = """{"personaId":"p1","owner":"u1","text":"Ta 记得：你养了一只猫"}"""
        assertEquals("Ta 记得：你养了一只猫", XinchaoMemoryApi.parseText(raw))
    }

    @Test
    fun `解析 —— 坏输入一律 null，不崩`() {
        assertNull(XinchaoMemoryApi.parseText(null))
        assertNull(XinchaoMemoryApi.parseText(""))
        assertNull(XinchaoMemoryApi.parseText("不是 JSON"))
        assertNull(XinchaoMemoryApi.parseText("""{"personaId":"p1"}"""))
    }

    @Test
    fun `结构化列表 URL —— 尾斜杠不拼双斜杠`() {
        assertEquals(
            "https://s.example/xinchao/personas/p1/memory/list",
            XinchaoMemoryApi.bucketListUrl("https://s.example/", "p1"),
        )
        assertEquals("", XinchaoMemoryApi.bucketListUrl("", "p1"))
    }

    @Test
    fun `解析结构化列表 —— 逐条取字段`() {
        val raw = """
            {"personaId":"p1","count":2,"buckets":[
              {"id":"b1","domain":"日常","title":"习惯","content":"爱喝美式","importance":6,"createdAt":"2026-10-06T03:20:34+08:00"},
              {"id":"b2","domain":"梦境","title":"","content":"梦到草莓","importance":0,"createdAt":null}
            ]}
        """.trimIndent()
        val list = XinchaoMemoryApi.parseBuckets(raw)!!
        assertEquals(2, list.size)
        assertEquals("b1", list[0].id)
        assertEquals("日常", list[0].domain)
        assertEquals("爱喝美式", list[0].content)
        assertEquals(6, list[0].importance)
        assertEquals("梦境", list[1].domain)
        assertEquals(0, list[1].importance)
    }

    @Test
    fun `解析结构化列表 —— 缺 id 的条目跳过，坏输入 null`() {
        // 一条好的 + 一条缺 id：坏的跳过、好的保留（不让一条坏数据毁整页）
        val raw = """{"buckets":[{"domain":"日常","content":"x"},{"id":"ok","domain":"工作","content":"y"}]}"""
        val list = XinchaoMemoryApi.parseBuckets(raw)!!
        assertEquals(1, list.size)
        assertEquals("ok", list[0].id)
        // 域缺省时给"未分类"，不显示空白
        val noDomain = XinchaoMemoryApi.parseBuckets("""{"buckets":[{"id":"z","content":"c"}]}""")!!
        assertEquals("未分类", noDomain[0].domain)
        assertNull(XinchaoMemoryApi.parseBuckets("不是 JSON"))
        assertNull(XinchaoMemoryApi.parseBuckets(null))
    }

    @Test
    fun `解析结构化列表 —— 长正文完整保留（App 侧不做二次截断）`() {
        // 服务端单条上限 4000 字符（v0.61.39 起）——App 侧必须原样收下，
        // 否则「点开看全文」永远看不到长记忆的完整正文。
        val long = "长".repeat(4200)
        val raw = """{"buckets":[{"id":"b1","domain":"日常","content":"$long"}]}"""
        val list = XinchaoMemoryApi.parseBuckets(raw)!!
        assertEquals(4200, list[0].content.length)
    }
}
