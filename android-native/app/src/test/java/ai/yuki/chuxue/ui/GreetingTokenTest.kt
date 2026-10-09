package ai.yuki.chuxue.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 开场白变量快捷添加的契约（纯函数 `insertGreetingToken`，定义在 `ui/persona/PersonaScreen.kt`）。
 *
 * ⚠️ 包名是 `ai.yuki.chuxue.ui` —— 与 `PersonaScreen.kt` 自己的包声明一致
 *（那一页早于 `ui/persona/` 这个目录）。测试与源同包，才拿得到 `internal` 的符号。
 *
 * 钉两件事：
 *   1. **追加语义** —— 点一下，变量出现在末尾；
 *   2. **不重复堆叠** —— 同一个变量点两下，不该得到两个一样的占位符。
 * 另外钉住变量清单的**字面量**：它与 `PromptEngine` 的替换逐字对应，
 * 改了这里不改那里，用户点了按钮也换不出东西 —— 而那种坏法是**静默**的。
 */
class GreetingTokenTest {

    @Test
    fun `空开场白点一下得到该变量`() {
        assertEquals("{user_nickname}", insertGreetingToken("", "{user_nickname}"))
    }

    @Test
    fun `追加到已有文字末尾`() {
        assertEquals("你好{user_nickname}", insertGreetingToken("你好", "{user_nickname}"))
    }

    @Test
    fun `同一个变量点两下不重复堆叠`() {
        val once = insertGreetingToken("你好", "{persona_name}")
        assertEquals("你好{persona_name}", once)
        assertEquals("你好{persona_name}", insertGreetingToken(once, "{persona_name}"))
    }

    @Test
    fun `可以依次插入多个不同的变量`() {
        var g = ""
        GREETING_TOKENS.forEach { g = insertGreetingToken(g, it.token) }
        assertEquals("{user_nickname}{user_gender}{persona_name}", g)
    }

    @Test
    fun `变量清单与 PromptEngine 的字面量一致`() {
        // 这三个字面量必须与 PromptEngine 里 .replace(...) 的三行逐字相同：
        // PromptEngine.kt 的 {user_nickname} / {user_gender} / {persona_name}
        assertEquals(
            listOf("{user_nickname}", "{user_gender}", "{persona_name}"),
            GREETING_TOKENS.map { it.token },
        )
    }

    @Test
    fun `清单里每个变量都有按钮文案与解释`() {
        assertTrue(
            "没有解释的变量用户不敢用",
            GREETING_TOKENS.all { it.label.isNotBlank() && it.means.isNotBlank() },
        )
    }
}
