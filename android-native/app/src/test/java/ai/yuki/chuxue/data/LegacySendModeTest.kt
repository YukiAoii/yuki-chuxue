package ai.yuki.chuxue.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 老用户的「发送方式」该读成什么。
 *
 * ## 它修的是什么（与 `baseUrl` 同型的一处老用户兼容缺陷）
 * 改版前 `sendMode` 存的是**发送方式**：`"enter"`（回车发送）/ `"button"`（点按钮发送）。
 * 改版后 `sendMode` 被重做成**呈现方式**（`stream/instant`），发送方式挪到**新键**
 * `enterToSend`，而新键的缺省是 `true`（回车发送）。
 *
 * 问题是：**没有任何迁移代码**（全仓 grep 不见 `"enter"` / `"button"` 的老值）。
 * 于是当初**显式选了「按钮发送」**的用户，升级后 `enterToSend` 读到缺省 `true`——
 * 他改过的那项设置**被静默改掉了**。
 *
 * （范围要说清：旧版**默认**就是回车发送，所以只有显式选过"按钮"的人受影响。
 *  不能把它说成"所有老用户都被改了"。）
 *
 * ## 判据
 * · `enterToSend` 这个键**存在** → 以它为准（用户在新版设过，或迁移已经跑过）；
 * · 键**不存在**（老用户）→ 按老 `sendMode` 的原值决定：`"button"` → false，其余 → true。
 *
 * ⚠️ 这是**只读判断**、不写盘：新用户与老用户都适用，且天然幂等
 * （用户一保存设置，新键就存在了，这条分支从此不再相关）。
 */
class LegacySendModeTest {

    @Test
    fun `老用户显式选了"按钮发送" —— 不能被静默改成回车发送`() {
        assertFalse(resolveEnterToSend(stored = null, rawSendMode = "button"))
    }

    @Test
    fun `老用户是默认的"回车发送" —— 照旧`() {
        assertTrue(resolveEnterToSend(stored = null, rawSendMode = "enter"))
    }

    @Test
    fun `老用户从没存过这个键 —— 旧版默认就是回车发送`() {
        assertTrue(resolveEnterToSend(stored = null, rawSendMode = null))
    }

    @Test
    fun `新键存在时以它为准 —— 用户在新版改过就该听他的`() {
        assertFalse(resolveEnterToSend(stored = false, rawSendMode = "enter"))
        assertTrue(resolveEnterToSend(stored = true, rawSendMode = "button"))
    }

    @Test
    fun `新旧值同时在也听新键 —— 迁移跑过之后不该被老值拽回去`() {
        // 迁移只读不写，所以"新键存在 + 老值还在"是常态；此时老值必须被忽略
        assertFalse(resolveEnterToSend(stored = false, rawSendMode = "button"))
    }
}
