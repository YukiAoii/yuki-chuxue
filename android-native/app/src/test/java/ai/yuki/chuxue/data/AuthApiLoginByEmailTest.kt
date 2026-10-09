package ai.yuki.chuxue.data

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 邮箱验证码**登录**的请求体规格（v0.61.24.5）。
 *
 * ## 为什么这两条要钉住
 * 请求体字段名是**跟服务端对齐**的契约：写错了不会崩，只会**静默地登录失败**
 * （用户看到"账号或密码不对"这类兜底文案）。所以字段名必须由断言守着。
 *
 * ⚠️ 与「绑定邮箱」链路**方向相反**：绑定是"已注册就拒"，登录是"没注册就拒" ——
 *    所以两者用不同端点，但**发码的请求体同形**（都只有一个 `email`），
 *    这里复用了同一个纯函数，断言它别被改歪。
 */
class AuthApiLoginByEmailTest {

    @Test
    fun `用码登录请求体 —— email 与 code 两个字段都要在`() {
        val json = AuthApi.loginByEmailBody("a@b.com", "123456")
        assertTrue("缺 email：$json", json.contains("\"email\":\"a@b.com\""))
        assertTrue("缺 code：$json", json.contains("\"code\":\"123456\""))
    }

    @Test
    fun `发码请求体（复用 emailCodeBody）—— 只有一个 email 字段`() {
        val json = AuthApi.emailCodeBody("a@b.com")
        assertTrue("缺 email：$json", json.contains("\"email\":\"a@b.com\""))
        assertTrue("不该混进别的字段：$json", !json.contains("code"))
    }
}
