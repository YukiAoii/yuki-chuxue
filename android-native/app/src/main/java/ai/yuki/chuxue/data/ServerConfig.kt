package ai.yuki.chuxue.data

import ai.yuki.chuxue.BuildConfig

/**
 * 后端地址 —— **编译期内置，用户看不到也改不了**。
 *
 * ## 为什么不让用户填
 * 用户 2026-09-27 的要求：「软件里不能让用户手动填写后端地址，直接内置到软件代码里」。
 * 这个决定是对的，理由比"少一个输入框"更实在：
 *
 * 1. **那个地址不是用户的决定**。它由部署方（你）拥有，用户填错了只会得到
 *    "连不上"，而他自己无法诊断 —— 这是一个纯负担的输入项。
 * 2. **账号体系要一致**。注册/登录发生在哪台服务器，决定了 uid 属于哪个账号池；
 *    让每个用户填不同的地址，等于每台手机一个孤立账号，跨设备同步也就不成立了。
 * 3. 普通用户（公益分发的对象）根本不知道"后端地址"是什么。
 *
 * 需要改地址时改 `app/build.gradle.kts` 里的 `SERVER_BASE_URL` —— 那是它唯一的来源，
 * 改完重新出包。这样"App 指向哪台服务器"是一个**可追溯的构建事实**，
 * 而不是散落在无数台手机上的一个字符串。
 *
 * ⚠️ 与本项目的 BYOK 定位不冲突：DeepSeek 的 API Key 仍然是用户自己的、只存本机；
 *    这个地址只用于**账号与公告**，对话流量永远直连 DeepSeek，不经过它。
 */
object ServerConfig {

    /** 例：`https://example.com:11445`（不带末尾斜杠） */
    val BASE_URL: String = BuildConfig.SERVER_BASE_URL.trimEnd('/')

    /** 开发文档 §19 的账号接口前缀。 */
    val API_V1: String = "$BASE_URL/api/v1"

    /** 拼一个绝对地址。传入的 path 需以 `/` 开头。 */
    fun url(path: String): String = BASE_URL + path
}
