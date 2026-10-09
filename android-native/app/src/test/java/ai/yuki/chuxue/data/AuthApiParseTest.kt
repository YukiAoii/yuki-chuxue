package ai.yuki.chuxue.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 账号 JSON 的**解析规格**（v0.61.24.4）。
 *
 * ## 它守的是什么（用户 2026-10-05 报的 bug）
 * 老用户（服务端库里 `email` 是 NULL）**永远看不到「补绑邮箱」提示**。
 * 根因不在提示的判据，而在**解析这一层**：
 * `jsonPrimitive.content` 对 JSON 的 `null` 返回的是**字符串 `"null"`**（不是 Kotlin null），
 * 于是 `email.isNullOrBlank()` **恒为假** —— 提示自然不出现；
 * `avatarUrl` 吃同一个亏（头像地址变成 `"null"` 字符串）。
 *
 * ⚠️ 这类 bug 的特征是**静默**：不崩、不报错，只是某个功能"从不发生"。
 *    只有把「JSON null → Kotlin null」钉在断言里，才挡得住它复发。
 *
 * ⚠️ `parseAccount(raw, fromUserObject = true)` 默认从 `{"user": {...}}` 里取账号。
 */
class AuthApiParseTest {

    private fun account(json: String): AuthApi.Account? =
        (AuthApi.parseAccount(json) as? AuthApi.Outcome.Ok)?.value

    @Test
    fun `email 是 JSON null —— 必须解析成 Kotlin null（不是字符串 null）`() {
        val a = account("""{"user":{"uid":"10114","nickname":"某人","avatarUrl":null,"email":null}}""")
        assertNull(
            "JSON null 被读成字符串 \"null\" 的话，isNullOrBlank() 永远为假 → 补绑邮箱提示永不出现",
            a?.email,
        )
    }

    @Test
    fun `avatarUrl 是 JSON null —— 同样是 Kotlin null`() {
        val a = account("""{"user":{"uid":"10114","nickname":"某人","avatarUrl":null,"email":"a@b.com"}}""")
        assertNull("否则头像组件会去加载一个字面量叫 null 的地址", a?.avatarUrl)
    }

    @Test
    fun `正常值照常解析（别把修 null 修成了丢字段）`() {
        val a = account(
            """{"user":{"uid":"10114","nickname":"某人","avatarUrl":"https://x/y.png","email":"a@b.com"}}""",
        )
        assertEquals("10114", a?.uid)
        assertEquals("某人", a?.nickname)
        assertEquals("https://x/y.png", a?.avatarUrl)
        assertEquals("a@b.com", a?.email)
    }
}
