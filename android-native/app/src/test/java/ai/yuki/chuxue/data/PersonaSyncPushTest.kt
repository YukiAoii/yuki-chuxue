package ai.yuki.chuxue.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「本地这份能不能推上去覆盖云端」的规格（v0.61.21，Wave 2）。
 *
 * ## 它防的是一次真实的数据丢失
 * 换设备 / 清数据之后刚登录时，本地人设是空的、云端有旧快照。用户只要**新建一个人设**
 * 就会触发推送 —— 推上去的是"本地这份"（只有刚建的那一个），
 * 于是**云端的旧人设被一份近乎空的快照覆盖掉**。
 * 用户报的「换设备登录后人设消失」就是它：不是新设备看不到，是真的没了。
 *
 * ## 为什么是这两条判据
 * 难点在于："本地为空"**既可能是**"我还没拉过、不知道云端有什么"，
 * **也可能是**"我真的把人设全删了、这个删除要同步出去"。
 * 两者靠**有没有成功拉过一次**区分 —— 这是唯一能把它们分开的信息。
 */
class PersonaSyncPushTest {

    @Test
    fun `本地有人设 —— 正常推`() {
        assertTrue(PersonaSync.canPush(localCount = 3, pulledOnce = true))
        assertTrue(PersonaSync.canPush(localCount = 3, pulledOnce = false))
    }

    @Test
    fun `本地空、还没拉过 —— 不许推（这一推就是盲目覆盖云端）`() {
        assertFalse(PersonaSync.canPush(localCount = 0, pulledOnce = false))
    }

    @Test
    fun `本地空、但已经确认过云端也是空的 —— 可以推`() {
        // 「我把人设删光了」这件事必须能同步出去（同一条链路上的另一半）
        assertTrue(PersonaSync.canPush(localCount = 0, pulledOnce = true))
    }

    @Test
    fun `一个人设都没建过的全新用户 —— 不该因此把云端清空`() {
        // 最危险的那一格：全新安装（还没拉）+ 本地为空。
        // 若这里返回 true，任何一次误触发的推送都会把一个老账号的云端人设清掉。
        assertFalse(PersonaSync.canPush(localCount = 0, pulledOnce = false))
    }
}
