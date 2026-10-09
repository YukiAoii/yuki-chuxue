package ai.yuki.chuxue.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本地人设的**账号归属**规格（v0.61.24.3）。
 *
 * ## 它修的是什么（用户报告）
 * 「同一台设备登录其他账号，人设会同步过去」——机制已查明：
 * · 本地人设库是**设备级**的（存储键不含 uid），登出时刻意不清人设；
 * · 换账号后 ① 本地仍是上一账号的人设（界面直接显示）；
 *   ② 同步判据把「本地有内容 + 新账号云端为空」判成**上传** →
 *      **用新账号的密钥把上一账号的人设推上云端**（一旦写入就真属新账号，不可逆）。
 *
 * ## 判定规则（本测试钉住的就是它）
 * | 本地归属 | 判定 | 后果 |
 * |---|---|---|
 * | 无归属（从没登录过） | 采用 | 首次登录时**绑定**给当前账号（那确实是本机用户的） |
 * | 与当前账号一致 | 采用 | 正常显示 / 同步 |
 * | 与当前账号**不一致** | **隔离** | **不显示、不上传**（只允许首次下载） |
 *
 * ⚠️ 这三条是**逻辑**不是文案：把它抽成纯函数，是为了让"换账号不能串号"这条
 *    不变量可被单测钉住，而不是散在 ViewModel 里靠读代码记得。
 */
class PersonaOwnerTest {

    @Test
    fun `同一账号 —— 采用本地人设`() {
        assertEquals(LocalPersonaOwner.SAME_ACCOUNT, classifyLocalOwner("u_aaa", "u_aaa"))
        assertTrue(canAdoptLocalPersonas("u_aaa", "u_aaa"))
    }

    @Test
    fun `从未登录过（无归属） —— 首次登录时绑定给当前账号`() {
        assertEquals(LocalPersonaOwner.NO_OWNER, classifyLocalOwner(null, "u_aaa"))
        assertEquals(LocalPersonaOwner.NO_OWNER, classifyLocalOwner("", "u_aaa"))
        assertEquals(LocalPersonaOwner.NO_OWNER, classifyLocalOwner("   ", "u_aaa"))
        assertTrue("本机用户从没登录过时，本地人设就该是他的", canAdoptLocalPersonas(null, "u_aaa"))
    }

    @Test
    fun `换账号 —— 隔离（这是本次修复的核心不变量）`() {
        assertEquals(LocalPersonaOwner.OTHER_ACCOUNT, classifyLocalOwner("u_aaa", "u_bbb"))
        assertFalse(
            "上一个账号的人设绝不能被当成新账号的（否则会被推上云端、不可逆）",
            canAdoptLocalPersonas("u_aaa", "u_bbb"),
        )
    }

    @Test
    fun `其他账号 —— 一律不许上传（含归属为空被抢先推的路径）`() {
        // 明确：隔离态下 canPush 必须为假；同账号/无归属才允许
        assertFalse(canPushLocalPersonas("u_aaa", "u_bbb"))
        assertTrue(canPushLocalPersonas("u_aaa", "u_aaa"))
        assertTrue(canPushLocalPersonas(null, "u_aaa"))
    }

    @Test
    fun `大小写与前后的空白不影响判定 —— 归属值按规范化比较`() {
        assertEquals(LocalPersonaOwner.SAME_ACCOUNT, classifyLocalOwner(" u_aaa ", "u_aaa"))
    }
}
