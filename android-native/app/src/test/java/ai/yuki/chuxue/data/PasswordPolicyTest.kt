package ai.yuki.chuxue.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 密码格式判据 —— **与后端 `password_error` 同源**（v0.59.0）。
 *
 * ## 这组测试在守什么
 * 客户端这份只为"打字时就告诉你哪里不对"（体验），**后端才是权威**。
 * 所以两边的规则必须一致 —— 这里的用例是照着后端那组抄的，
 * 任何一边改了规则，另一边的测试应当立刻红。
 *
 * ⚠️ 白名单**不是防 SQL 注入**（本项目所有 SQL 都是参数化查询，引号与注入无关），
 *    它只是"别让用户设出自己都打不出来的字符"。
 */
class PasswordPolicyTest {

    @Test
    fun `太短被拒`() {
        assertNotNull(PasswordPolicy.errorOf("Ab1!"))
    }

    @Test
    fun `太长被拒`() {
        assertNotNull(PasswordPolicy.errorOf("Ab1!" + "a".repeat(61)))
    }

    @Test
    fun `只有一类字符被拒`() {
        assertNotNull("只有小写应当被拒", PasswordPolicy.errorOf("abcdefgh"))
        assertNotNull("只有数字应当被拒", PasswordPolicy.errorOf("12345678"))
    }

    @Test
    fun `引号反斜杠空格中文都被拒`() {
        for (bad in listOf("Ab1!'xyz", "Ab1!\"xyz", "Ab1!\\xyz", "Ab1! xyz", "Ab1!中文x")) {
            assertNotNull("应当拒绝：$bad", PasswordPolicy.errorOf(bad))
        }
    }

    @Test
    fun `合法密码通过`() {
        // v0.60.0：用户要求「密码格式只支持数字和英文」—— 符号不再合法。
        for (ok in listOf("Abcdefg1", "aB3kLm9x", "ZZZZZZZZ1")) {
            assertNull("应当通过：$ok", PasswordPolicy.errorOf(ok))
        }
    }

    @Test
    fun `符号密码被拒`() {
        // 钉住新契约：以前这些是合法样例，现在必须被拒（只卡"设置新密码"，
        // 老用户带符号的密码照样能登录 —— 登录不走这套校验）。
        for (bad in listOf("Ab1!\$xyz", "Abc def1", "abc_1234")) {
            assertNotNull("应当被拒：$bad", PasswordPolicy.errorOf(bad))
        }
    }

    @Test
    fun `输入过程中的提示不该一边打字一边报错`() {
        // 还没输够长度时不报错（不然刚敲第一个字符就红一片，很烦）
        assertNull(PasswordPolicy.errorWhileTyping(""))
        assertNull(PasswordPolicy.errorWhileTyping("Ab1"))
        // 够长了就正常按规则报
        assertNotNull(PasswordPolicy.errorWhileTyping("abcdefgh"))
    }

    @Test
    fun `边界值 8 与 64 都算合规长度`() {
        // 8 位、两类：刚好合规
        assertNull(PasswordPolicy.errorOf("Abcde123"))
        // 64 位：刚好合规
        assertNull(PasswordPolicy.errorOf("Ab1" + "c".repeat(61)))
        // 7 位 / 65 位：拒
        assertNotNull(PasswordPolicy.errorOf("Abcde12"))
        assertNotNull(PasswordPolicy.errorOf("Ab1" + "c".repeat(62)))
    }

    @Test
    fun `提示文案里带着允许的符号集`() {
        // 用户要看得到到底允许哪些符号，否则只能瞎试
        assertEquals(true, PasswordPolicy.HINT.contains(PasswordPolicy.SYMBOLS))
    }
}
