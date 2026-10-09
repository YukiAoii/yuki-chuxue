package ai.yuki.chuxue.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `GET /api/app/version` 的解析契约（v0.50.0）。
 *
 * ## 为什么这批断言重要
 * 网络那一层在本机**测不了**（无 adb/设备），但解析层能测，而它恰好是
 * **最容易静默出错**的地方：字段名写错、`latest` 是 JSON null、响应被截断
 * —— 这些都不会崩，只会让"更新永远不提示"，而用户以为"就是没有新版"。
 *
 * ⚠️ 这里的 JSON 样例**逐字取自后端 `app_version` 的实际返回结构**
 * （见 `backend/main.py` 的 `app_version`）—— 不是我自己编的字段名。
 */
class UpdateApiParseTest {

    @Test
    fun `正常响应能解析出全部字段`() {
        val raw = """
            {"latest":{"version_code":71,"version_name":"0.50.0",
            "notes":"1. 应用内更新\n2. 修了些问题","force":0,"min_supported_code":0,
            "apk_url":"/d/yuki.apk","apk_size":13800000,"published_at":"2026-09-29T12:00:00Z"},
            "force":false,"min_supported_code":0}
        """.trimIndent()
        val r = UpdateApi.parseRelease(raw)
        assertTrue("应当解析成功", r != null)
        assertEquals(71, r!!.versionCode)
        assertEquals("0.50.0", r.versionName)
        assertTrue("更新日志要带换行", r.notes.contains("\n"))
        assertFalse(r.force)
        assertEquals(0, r.minSupportedCode)
        assertEquals("/d/yuki.apk", r.apkUrl)
        assertEquals(13_800_000L, r.apkSize)
    }

    @Test
    fun `⚠️ latest 为 JSON null 时返回 null —— 首次部署的正常状态`() {
        // 后端在库里没有版本行时就是这么返回的（见 app_version 的 row is None 分支）。
        // 这必须是"什么都不做"，不能是异常。
        val raw = """{"latest":null,"force":false,"min_supported_code":0}"""
        assertNull("latest=null 应当解析为 null，而不是崩", UpdateApi.parseRelease(raw))
    }

    @Test
    fun `缺 latest 字段时返回 null`() {
        assertNull(UpdateApi.parseRelease("""{"force":false}"""))
    }

    @Test
    fun `缺 version_code 时返回 null —— 没有版本号就无法比较`() {
        val raw = """{"latest":{"version_name":"0.50.0","notes":"x"}}"""
        assertNull("version_code 是判据本身，缺了就只能放弃", UpdateApi.parseRelease(raw))
    }

    @Test
    fun `version_code 不是数字时返回 null`() {
        assertNull(UpdateApi.parseRelease("""{"latest":{"version_code":"abc"}}"""))
    }

    @Test
    fun `畸形 JSON 不崩 —— 返回 null`() {
        assertNull(UpdateApi.parseRelease("这不是 json"))
        assertNull(UpdateApi.parseRelease(""))
        assertNull(UpdateApi.parseRelease("{断掉的"))
        assertNull(UpdateApi.parseRelease("[]"))
    }

    @Test
    fun `多余字段被忽略 —— 后端加字段不该让旧客户端解析失败`() {
        val raw = """
            {"latest":{"version_code":71,"version_name":"0.50.0","notes":"x",
            "force":0,"min_supported_code":0,"apk_url":"/d/yuki.apk","apk_size":100,
            "published_at":"2026-09-29","brand_new_field":"whatever"},
            "force":false,"min_supported_code":0,"another_new":123}
        """.trimIndent()
        val r = UpdateApi.parseRelease(raw)
        assertTrue("加字段不该破坏解析", r != null)
        assertEquals(71, r!!.versionCode)
    }

    /* ─────────── force 的两种编码 ─────────── */

    @Test
    fun `force 为 true 时解析为真`() {
        val r = UpdateApi.parseRelease("""{"latest":{"version_code":71,"force":true}}""")
        assertTrue(r!!.force)
    }

    @Test
    fun `⚠️ force 为数字 1 时也算真 —— SQLite 存的是 INTEGER`() {
        // 后端表里 force 是 INTEGER，某些序列化路径会给出 1 而不是 true。
        // 只认 `true` 的话，强制更新会静默失效 —— 那正是最不该失效的一条。
        val r = UpdateApi.parseRelease("""{"latest":{"version_code":71,"force":1}}""")
        assertTrue("数字 1 必须也算强制", r!!.force)
    }

    @Test
    fun `force 为 false 或 0 时解析为假`() {
        assertFalse(UpdateApi.parseRelease("""{"latest":{"version_code":71,"force":false}}""")!!.force)
        assertFalse(UpdateApi.parseRelease("""{"latest":{"version_code":71,"force":0}}""")!!.force)
    }

    /* ─────────── 缺省值的语义 ─────────── */

    @Test
    fun `缺 apk_url 时 hasDownload 为假 —— 判定层据此降级为非强制`() {
        val r = UpdateApi.parseRelease("""{"latest":{"version_code":71,"apk_size":100}}""")
        assertFalse("没有地址就不能下载", r!!.hasDownload)
    }

    @Test
    fun `缺 apk_size 时给 0（而不是崩）`() {
        val r = UpdateApi.parseRelease("""{"latest":{"version_code":71,"apk_url":"/d/yuki.apk"}}""")
        assertEquals(0L, r!!.apkSize)
    }

    @Test
    fun `缺 notes 时给空串 —— 没有更新日志也是合法的`() {
        val r = UpdateApi.parseRelease("""{"latest":{"version_code":71}}""")
        assertEquals("", r!!.notes)
    }
}
