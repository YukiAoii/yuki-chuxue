package ai.yuki.chuxue.ui

import ai.yuki.chuxue.data.ProviderGroup
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 新用户引导弹窗「该不该出现」的规格（v0.61.21）。
 *
 * ## 用户要的那个弹窗（原话）
 * 「做个新用户注册登录进来之后有个弹窗，提示就说"检测到你是新注册用户，
 * 如果你没有 API，请点击弹窗按钮的创建，我将为你自动拉取模型一键配置好，
 * 你直接创建人设就好啦"。也要带功能，要有个「不用」的按钮 ——
 * 因为有些用户自己是有服务地址和 api 的，让他们进行选择。」
 *
 * ## 这一组测试真正在防什么
 * 防的是**弹窗糊错人**：
 * · 糊到老用户脸上 → 他明明一切正常，却被问"要不要帮你配 API"（最尴尬的一种）；
 * · 糊到自己配过 API 的人脸上 → 等于劝他把自己的配置换成官方的，**他最不想要这个**；
 * · 关不掉 → 点完「创建」拿到 key 之后，判据重算仍然为真，弹窗再冒出来。
 */
class NewUserPromptTest {

    private fun group(key: String) =
        ProviderGroup(id = "g", name = "分组", baseUrl = "https://x", apiKey = key)

    @Test
    fun `刚登录、一个人设都没有、没有 key、没弹过 —— 该弹`() {
        assertTrue(
            shouldShowNewUserPrompt(
                loggedIn = true, personaCount = 0, hasAnyApiKey = false, seen = false,
            ),
        )
    }

    @Test
    fun `老用户不该被弹 —— 他一定有人设，这一条就把他挡住了`() {
        assertFalse(
            shouldShowNewUserPrompt(
                loggedIn = true, personaCount = 3, hasAnyApiKey = false, seen = false,
            ),
        )
    }

    @Test
    fun `自己配过 API 的人不该被弹 —— 他不需要"我帮你配"`() {
        assertFalse(
            shouldShowNewUserPrompt(
                loggedIn = true, personaCount = 0, hasAnyApiKey = true, seen = false,
            ),
        )
    }

    @Test
    fun `没登录不弹`() {
        assertFalse(
            shouldShowNewUserPrompt(
                loggedIn = false, personaCount = 0, hasAnyApiKey = false, seen = false,
            ),
        )
    }

    @Test
    fun `弹过一次就不再弹 —— 点「不用」也是这个语义`() {
        assertFalse(
            shouldShowNewUserPrompt(
                loggedIn = true, personaCount = 0, hasAnyApiKey = false, seen = true,
            ),
        )
    }

    /* ─────────── 「手上有没有 key」必须**两个来源**都看 ─────────── */

    @Test
    fun `免费分组的 key 在分组里，不在全局设置里 —— 只看全局会判错`() {
        // 刚点完「创建」的用户：全局 settings.apiKey 是空的，key 在免费分组上。
        // 若这里判成"没有 key"，弹窗点完了还会再冒出来（糊在他脸上关不掉）。
        assertTrue(hasAnyApiKey(settingsApiKey = "", groups = listOf(group("sk-abc"))))
    }

    @Test
    fun `自己填在全局设置里的 key 也算数`() {
        assertTrue(hasAnyApiKey(settingsApiKey = "sk-mine", groups = emptyList()))
    }

    @Test
    fun `两处都没有才算没有`() {
        assertFalse(hasAnyApiKey(settingsApiKey = "", groups = listOf(group(""))))
        assertFalse(hasAnyApiKey(settingsApiKey = null, groups = emptyList()))
    }
}
