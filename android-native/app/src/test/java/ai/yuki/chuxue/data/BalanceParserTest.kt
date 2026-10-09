package ai.yuki.chuxue.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 余额解析契约（纯函数）。
 *
 * ⚠️ 用官方的响应样例，并且**必须**覆盖 `is_available=false` ——
 * 那是"余额为 0、请求会被拒"的信号，解析错会让用户在撞上 402 之前毫不知情。
 */
class BalanceParserTest {

    private val sample = """
        {"is_available":true,"balance_infos":[
          {"currency":"CNY","total_balance":"110.00","granted_balance":"10.00","topped_up_balance":"100.00"}]}
    """.trimIndent()

    @Test
    fun `按官方样例解析出余额与币种`() {
        val b = BalanceParser.parse(sample)
        assertTrue(b.isAvailable)
        assertEquals(1, b.infos.size)
        assertEquals("CNY", b.infos[0].currency)
        assertEquals("110.00", b.infos[0].total)
        assertEquals("10.00", b.infos[0].granted)
        assertEquals("100.00", b.infos[0].toppedUp)
    }

    @Test
    fun `余额为 0 时 is_available 为假 —— 这是撞 402 之前的唯一信号`() {
        val b = BalanceParser.parse("""{"is_available":false,"balance_infos":[]}""")
        assertFalse(b.isAvailable)
    }

    @Test
    fun `多币种各自成行`() {
        val raw = """{"is_available":true,"balance_infos":[
            {"currency":"CNY","total_balance":"1.00","granted_balance":"0","topped_up_balance":"1.00"},
            {"currency":"USD","total_balance":"2.00","granted_balance":"0","topped_up_balance":"2.00"}]}"""
        val b = BalanceParser.parse(raw)
        assertEquals(2, b.infos.size)
        assertTrue("多币种用 / 连接：${b.summary}", b.summary.contains("/"))
    }

    @Test
    fun `缺字段不会崩 —— 空串好过一个假数字`() {
        val b = BalanceParser.parse("""{"is_available":true,"balance_infos":[{"currency":"CNY"}]}""")
        assertEquals("", b.infos[0].total)
    }

    @Test
    fun `结构不对时抛错，让调用方降级成"暂时读不到"`() {
        val e = runCatching { BalanceParser.parse("<html>502</html>") }.exceptionOrNull()
        assertTrue("应当抛异常而不是给个假余额", e != null)
    }
}
