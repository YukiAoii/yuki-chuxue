package ai.yuki.chuxue.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「服务端那份人设该不该应用」的规格。
 *
 * ## 它修的是什么（本轮已确证的真 bug）
 * `ChatViewModel` 的同步下行原来写的是：
 * ```
 * val remote = json?.let { runCatching { PersonaCodec.decode(it) }.getOrNull() }
 * if (!remote.isNullOrEmpty()) { 应用 + 推进 rev }
 * ```
 * `isNullOrEmpty()` 把**两种完全不同的空**合并了：
 * · `null`      = 解密/解码失败 → 该跳过 ✓
 * · `emptyList` = **服务端就是一份合法的空快照** → **必须应用** ✗
 *
 * 后果：用户在 A 设备上**把人设删光**，这个删除**永远同步不到 B 设备**；
 * 而且 `savePersonasRev` 不推进，B 每次启动都重复同一判断，**永不收敛、也不报错**。
 *
 * ## 判据
 * 只有 `null`（失败）才跳过；**空表是合法值**，要照样应用、照样推进 rev。
 */
class PersonaSyncApplyTest {

    @Test
    fun `解码失败（null）—— 跳过，别把本地人设清空`() {
        assertFalse(PersonaSync.shouldApplyRemote(null))
    }

    @Test
    fun `服务端就是空表 —— 必须应用，这正是"删光人设"的同步方式`() {
        assertTrue(PersonaSync.shouldApplyRemote(emptyList()))
    }

    @Test
    fun `正常快照 —— 当然要应用`() {
        assertTrue(PersonaSync.shouldApplyRemote(listOf(Persona(id = "p1", userNickname = "我"))))
    }
}
