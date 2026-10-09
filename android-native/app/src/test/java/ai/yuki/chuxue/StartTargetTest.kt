package ai.yuki.chuxue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 冷启动去向（v0.60.0）。
 *
 * ## 这组测试在守两条要求
 * 1. **选择页只出现一次**（用户：「首次打开出现，之后不再强制登录注册选择页」）
 * 2. **没有游客通道**（用户 2026-10-02 追加：「没有登录的用户必须登录不能到软件的真实界面」）
 *
 * ⚠️ 第 2 条是**推翻**了中间那版「未登录也放进主页」的游客行为 ——
 *    所以这里有一条测试专门盯着它：未登录**任何组合**都拿不到 [StartTarget.MAIN]。
 *
 * 纯函数，不碰 Android、不碰磁盘。
 */
class StartTargetTest {

    @Test
    fun `首次打开且未登录 → 去选择页`() {
        assertEquals(StartTarget.WELCOME, startTarget(isLoggedIn = false, welcomeSeen = false))
    }

    @Test
    fun `选择页看过之后 仍未登录 → 去登录页 而不是主页`() {
        // ⚠️ 本次要求的核心：没有游客通道。
        //    这条如果变成 MAIN，就是把游客状态又放回来了。
        assertEquals(StartTarget.AUTH, startTarget(isLoggedIn = false, welcomeSeen = true))
    }

    @Test
    fun `未登录在任何情况下都到不了主界面`() {
        for (seen in listOf(false, true)) {
            assertNotEquals(
                "未登录 + welcomeSeen=$seen 不该进主界面（那是游客模式，已删除）",
                StartTarget.MAIN,
                startTarget(isLoggedIn = false, welcomeSeen = seen),
            )
        }
    }

    @Test
    fun `已登录 → 直接进主页 不看引导`() {
        // 老用户升级上来：本地有登录态，不该被新引导拦一次
        assertEquals(StartTarget.MAIN, startTarget(isLoggedIn = true, welcomeSeen = false))
        assertEquals(StartTarget.MAIN, startTarget(isLoggedIn = true, welcomeSeen = true))
    }

    @Test
    fun `选择页只可能出现一次`() {
        // 走一遍「首次打开 → 去登录 → 再打开」：第二次不再出现选择页，而是登录页
        val first = startTarget(isLoggedIn = false, welcomeSeen = false)
        assertEquals(StartTarget.WELCOME, first)
        // 记下 markWelcomeSeen() 之后：
        val second = startTarget(isLoggedIn = false, welcomeSeen = true)
        assertEquals(StartTarget.AUTH, second)
    }
}
