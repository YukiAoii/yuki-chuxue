package ai.yuki.chuxue.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * `AuthApi` 请求体构造的规格。
 *
 * ## 它修的是什么（体检抓出的最高级缺陷）
 * `AuthApi.call()` 原来无条件写：
 * ```
 * .method(method, (body ?: "{}").toRequestBody(mediaJson))
 * ```
 * GET 的调用方传 `body = null`，于是被兜底成 `"{}"` —— **GET 带上了请求体**。
 * OkHttp 直接抛 `IllegalArgumentException: method GET must not have a request body.`，
 * 而这个异常被同一行的 `runCatching` 吞成 `code = -1`，
 * 调用方统一映射成「连不上服务器」——**一个纯客户端错误伪装成了网络故障**。
 *
 * 受害的三处（`AuthApi.kt:274 / 337 / 451`）：
 * · `GET /me`            → 启动校验永远走 refresh 分支
 * · `GET /auth/email/exists` → "这个邮箱已注册"的提示**永不出现**
 * · `GET /user/persona`  → 人设端到端加密同步的**下行完全不工作**
 *
 * ## 判据
 * **只有 POST / PUT / PATCH 才带请求体**；GET / HEAD / DELETE 一律 `null`。
 * 这条与 OkHttp 的 `HttpMethod.permitsRequestBody` 同一个集合 ——
 * 所以这里钉的不是"我们的口味"，而是"OkHttp 不会抛异常的那一档"。
 */
class AuthApiBodyForTest {

    @Test
    fun `GET 不带请求体 —— 这是本次修复的全部`() {
        assertNull("GET 带 body 会被 OkHttp 直接拒掉", AuthApi.bodyFor("GET", null))
        assertNull("哪怕调用方手滑传了内容，GET 也不该带", AuthApi.bodyFor("GET", "{}"))
    }

    @Test
    fun `HEAD 同样不带`() {
        assertNull(AuthApi.bodyFor("HEAD", null))
    }

    @Test
    fun `POST PUT PATCH 照旧带请求体`() {
        assertNotNull(AuthApi.bodyFor("POST", "{}"))
        assertNotNull(AuthApi.bodyFor("PUT", """{"a":1}"""))
        assertNotNull(AuthApi.bodyFor("PATCH", null)) // 没传也得给个 {}，后端要能解析
    }

    @Test
    fun `方法名大小写不敏感`() {
        assertNull(AuthApi.bodyFor("get", null))
        assertNotNull(AuthApi.bodyFor("post", "{}"))
    }

    /**
     * **端到端那一步**：把 `bodyFor` 的结果真的交给 OkHttp 去构造请求。
     *
     * ⚠️ 上面几条只验到"我们返回了 null"，而**用户感受到的结果**是
     * "请求能构造出来了" —— 修复前正是在这一句 `build()` 上抛的
     *（`IllegalArgumentException: method GET must not have a request body.`），
     * 然后被吞成 -1、显示成「连不上服务器」。
     * 所以这一条才是这次修复的**验收**：三个 GET 接口当初就死在这一步。
     */
    @Test
    fun `修复后 GET 请求真的构造得出来 —— 以前死在这一步`() {
        val built = Request.Builder()
            .url("http://127.0.0.1:1/never-called")
            .method("GET", AuthApi.bodyFor("GET", null))
            .build()
        assertEquals("GET", built.method)
        assertNull("GET 请求上不该挂任何请求体", built.body)

        // 对照：修复前的那句写法（GET 也塞 "{}"）现在仍然会抛 ——
        // 说明这一条测的是真的机制，不是空转
        assertThrows(IllegalArgumentException::class.java) {
            Request.Builder()
                .url("http://127.0.0.1:1/never-called")
                .method("GET", "{}".toRequestBody("application/json".toMediaType()))
                .build()
        }
    }
}
