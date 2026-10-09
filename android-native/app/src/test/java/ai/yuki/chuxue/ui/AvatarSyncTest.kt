package ai.yuki.chuxue.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 头像**补传**的判定（v0.58.x · 必做 4 的客户端那一半根因）。
 *
 * ## 这条判定在守什么
 * 服务器上的头像**只在"挑头像那一刻、且已登录"才写一次**。于是：
 * · **先挑头像、后登录**的 —— 那一刻没登录，直接 return 了；
 * · 挑的那一刻上传失败的 —— 旧代码把失败静默吞成 `Unit`。
 * 这两类用户服务器上永远是空的，而本地显示只看本地文件，**看不出**区别 ——
 * 直到他在市场发帖，别人看到的是一个首字兜底（线上真实发生过）。
 *
 * [shouldPushAvatar] 就是"启动时该不该补传一次"的判据：三个条件缺一不可。
 */
class AvatarSyncTest {

    @Test
    fun `已登录 + 服务器没有 + 本地有 → 补传`() {
        assertTrue(shouldPushAvatar(isLoggedIn = true, serverUrl = null, localPath = "/files/avatars/a.jpg"))
        assertTrue(shouldPushAvatar(isLoggedIn = true, serverUrl = "", localPath = "/files/avatars/a.jpg"))
    }

    @Test
    fun `没登录就不补传`() {
        // 没令牌传上去也只会 401；等登录后再补
        assertFalse(shouldPushAvatar(isLoggedIn = false, serverUrl = null, localPath = "/files/avatars/a.jpg"))
    }

    @Test
    fun `服务器已经有了就不重复传`() {
        assertFalse(
            shouldPushAvatar(
                isLoggedIn = true,
                serverUrl = "/uploads/avatars/20261001_ab.jpg",
                localPath = "/files/avatars/a.jpg",
            )
        )
    }

    @Test
    fun `本地没有图就没得传`() {
        assertFalse(shouldPushAvatar(isLoggedIn = true, serverUrl = null, localPath = null))
        assertFalse(shouldPushAvatar(isLoggedIn = true, serverUrl = null, localPath = ""))
    }

    /* ══════════════ 拉取方向：shouldPullAvatar ══════════════ */

    @Test
    fun `没登录或服务器没图 → 不拉`() {
        assertFalse(
            shouldPullAvatar(isLoggedIn = false, serverUrl = "/uploads/a.jpg", localPath = null, localSourceUrl = null)
        )
        assertFalse(
            shouldPullAvatar(isLoggedIn = true, serverUrl = null, localPath = null, localSourceUrl = null)
        )
        assertFalse(
            shouldPullAvatar(isLoggedIn = true, serverUrl = "", localPath = null, localSourceUrl = null)
        )
    }

    @Test
    fun `换设备重登（本地是空的）→ 必须拉`() {
        // 这正是用户报的那件事：新设备 / 清了数据之后本地没有那张图
        assertTrue(
            shouldPullAvatar(
                isLoggedIn = true,
                serverUrl = "/uploads/avatars/a.jpg",
                localPath = null,
                localSourceUrl = null,
            )
        )
    }

    @Test
    fun `本地是用户自己选的、没从服务器拉过 → 不覆盖`() {
        // 项目纪律：用户自己传的那张永远优先，服务器只做兜底
        assertFalse(
            shouldPullAvatar(
                isLoggedIn = true,
                serverUrl = "/uploads/avatars/a.jpg",
                localPath = "/files/avatars/local_pick.jpg",
                localSourceUrl = null,
            )
        )
    }

    @Test
    fun `本地就是服务器当前那张 → 不重复拉`() {
        assertFalse(
            shouldPullAvatar(
                isLoggedIn = true,
                serverUrl = "/uploads/avatars/a.jpg",
                localPath = "/files/avatars/remote_1a2b3c.jpg",
                localSourceUrl = "/uploads/avatars/a.jpg",
            )
        )
    }

    @Test
    fun `别的设备换了头像 → 必须重拉`() {
        // ⚠️ 这条是修 bug 的核心：原判据只看"本地有没有图"，本地有就永不重拉，
        //    于是别处换了头像这台永远显示旧的。
        assertTrue(
            shouldPullAvatar(
                isLoggedIn = true,
                serverUrl = "/uploads/avatars/NEW.jpg",
                localPath = "/files/avatars/remote_1a2b3c.jpg",
                localSourceUrl = "/uploads/avatars/OLD.jpg",
            )
        )
    }

    @Test
    fun `拉回来之后就不再拉（判据是幂等的）`() {
        // 拉到新图后 avatarSourceUrl 会被写成新 URL，此时判据必须转 false，
        // 否则每次启动都会白拉一遍。
        assertFalse(
            shouldPullAvatar(
                isLoggedIn = true,
                serverUrl = "/uploads/avatars/NEW.jpg",
                localPath = "/files/avatars/remote_NEW.jpg",
                localSourceUrl = "/uploads/avatars/NEW.jpg",
            )
        )
    }
}
