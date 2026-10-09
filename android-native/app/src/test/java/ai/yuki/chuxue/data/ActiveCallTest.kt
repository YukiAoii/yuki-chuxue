package ai.yuki.chuxue.data

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「停下」能不能真的掐掉在飞的 HTTP（⑧）。
 *
 * ## 为什么它必须被测
 * 这条修的是**用户按了「停下」但界面要等几十秒才反应**。而"掐掉"这个动作很容易
 * 写成"看起来做了、其实没作用"（比如 cancel 的对象不是那一路调用）。
 * 所以这里不验"代码调用了 cancel"，而是验**那个 Call 真的变成已取消**——
 * 用的是真 OkHttp Call（只构造、不执行，所以不碰网络）。
 */
class ActiveCallTest {

    private fun aCall(): Call =
        OkHttpClient().newCall(Request.Builder().url("http://127.0.0.1:1/never-called").build())

    @Test
    fun `cancel 掐掉的确实是刚才跟踪的那一路调用`() {
        val call = aCall()
        ActiveCall.track(call)
        assertFalse("还没按停，不该是已取消", call.isCanceled())

        ActiveCall.cancel()

        assertTrue("按了「停下」之后，那一路 HTTP 必须是已取消的", call.isCanceled())
    }

    @Test
    fun `只掐最近那一次 —— 后一次跟踪会覆盖前一次`() {
        val older = aCall()
        val newer = aCall()
        ActiveCall.track(older)
        ActiveCall.track(newer)

        ActiveCall.cancel()

        assertTrue("最近那次该被掐掉", newer.isCanceled())
        assertFalse("更早那次已经不在飞了，不该被动", older.isCanceled())
    }

    @Test
    fun `没有在飞的时候按停不抛 —— 用户连点两下不该崩`() {
        ActiveCall.track(aCall())
        ActiveCall.cancel()
        ActiveCall.cancel() // 第二次：那一路已经是已取消状态了，仍该是个空操作
    }
}
