package ai.yuki.chuxue.data.room

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 按服务商分桶里**与 Android 无关**的那部分（v0.51.0）。
 *
 * 为什么单拎出来测：这三件事判断错了，用户看到的就是一个**骗人的数字** ——
 * · 同一家被归一化成两个 key → 命中率被劈成两半，看着像"缓存时好时坏"；
 * · "没报"被当成 0 → 用户以为缓存废了，去改人设改历史（真的会把前缀弄断）；
 * · 混合口径没标出来 → 那个百分比谁都不对应，而用户以为它对应某一家。
 * 这些在 UI 层根本测不到，所以判据必须是纯函数 + 单测。
 */
class ProviderUsageTest {

    /* ─────────── 归一化：同一家必须落进同一个桶 ─────────── */

    @Test
    fun `同一家地址的写法差异要归一到同一个 key`() {
        val a = ProviderUsage.keyOf("https://api.deepseek.com/v1")
        assertEquals(a, ProviderUsage.keyOf("https://api.deepseek.com/v1/"))
        assertEquals(a, ProviderUsage.keyOf("  https://api.deepseek.com/v1  "))
        assertEquals(a, ProviderUsage.keyOf("https://API.DeepSeek.com/v1"))
    }

    @Test
    fun `路径大小写不动 —— 有些网关的路径确实区分`() {
        assertTrue(
            ProviderUsage.keyOf("https://relay.example.com/V1") !=
                ProviderUsage.keyOf("https://relay.example.com/v1"),
        )
    }

    @Test
    fun `换地址就是换桶 —— 缓存归属认地址，不认分组名`() {
        assertTrue(
            ProviderUsage.keyOf("https://a.example.com/v1") !=
                ProviderUsage.keyOf("https://b.example.com/v1"),
        )
    }

    @Test
    fun `没填地址时不崩，给一个可读的占位`() {
        assertEquals("未配置", ProviderUsage.keyOf(""))
        assertEquals("未配置", ProviderUsage.keyOf("   "))
    }

    /* ─────────── 混合口径 ─────────── */

    @Test
    fun `只有一家时不算混合`() {
        assertTrue(!ProviderUsage.isMixed(listOf("api.deepseek.com")))
        assertTrue(!ProviderUsage.isMixed(listOf("api.deepseek.com", "api.deepseek.com")))
        assertTrue(!ProviderUsage.isMixed(emptyList()))
    }

    @Test
    fun `跨了两家就算混合 —— 界面必须把这件事说出来`() {
        assertTrue(ProviderUsage.isMixed(listOf("api.deepseek.com", "relay.example.com")))
    }

    /* ─────────── 一行读数怎么说 ─────────── */

    @Test
    fun `没报这个数就说"未提供"，绝不显示 0%`() {
        val t = ProviderUsage.describeText(hit = 0, miss = 0, requests = 12, reports = false)
        assertTrue("不该出现百分比：$t", !t.contains("%"))
        assertTrue(t.contains("未提供"))
    }

    @Test
    fun `一次请求都没有时说"尚无请求"`() {
        assertEquals("尚无请求", ProviderUsage.describeText(hit = 0, miss = 0, requests = 0, reports = true))
    }

    @Test
    fun `有读数时给百分比`() {
        assertEquals("命中 75%", ProviderUsage.describeText(hit = 768, miss = 256, requests = 3, reports = true))
    }

    @Test
    fun `报了协议但一条读数都没有 —— 仍算"未提供"，不是 0%`() {
        // 这是最容易被写错的一档：requests>0 但 hit+miss==0 表示"这家报了 usage
        // 却没有缓存字段"，与"真的 0 命中"是两件事。
        val t = ProviderUsage.describeText(hit = 0, miss = 0, requests = 5, reports = true)
        assertTrue("不该出现百分比：$t", !t.contains("%"))
        assertTrue(t.contains("未提供"))
    }
}
