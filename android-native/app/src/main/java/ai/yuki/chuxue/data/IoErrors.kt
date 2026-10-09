package ai.yuki.chuxue.data

import java.io.FileNotFoundException
import java.io.IOException

/**
 * 把 IO 异常翻成**一句人话**。
 *
 * ## 为什么需要它
 * 导出 / 导入 / 读图片这几条路上，代码原来直接把 `e.message` 贴到屏幕上，于是
 * 用户会看到：
 *
 * ```
 * 导出失败：/storage/emulated/0/Yuki/x.json: open failed: EACCES (Permission denied)
 * ```
 *
 * 那是对**写这个 App 的人**说的话。用户要的是"多半因为什么 + 我该做什么"，
 * 而这句话两样都没有。
 *
 * ## 判据（可测）
 * 1. 说清**多半是什么原因** —— 存储满了 / 没权限 / 文件没了，这三条覆盖了绝大多数；
 * 2. **绝不把异常原文倒出来** —— 类名、路径、`ENOSPC` 这类词一个都不许出现
 *   （这条是可断言的硬指标，见 `IoErrorsTest`）。
 *
 * ⚠️ 与 [ChatErrors] 的分工：那个管**聊天请求**失败（要画成她的气泡，所以是一整句话），
 * 这个管**本地文件**操作失败（只是顶上一闪的提示，所以是半句话，由调用方补"导出失败："）。
 */
object IoErrors {

    /** @param e 任意异常；认不出来也**一定**返回一句人话。 */
    fun explain(e: Throwable): String {
        // 只看 message 里有没有那几个底层错误码 —— 认出来之后**不**把它带出去
        val raw = e.message.orEmpty().lowercase()
        return when {
            e is FileNotFoundException ->
                "找不到那个文件（可能被移动或删掉了）"

            e is SecurityException || raw.contains("eacces") || raw.contains("permission denied") ->
                "没有权限动那个位置"

            // 这两条排在最前，因为它们是**用户能立刻去处理**的原因：
            // 清点空间、换个位置。其它原因说了也只能"再试一次"。
            raw.contains("enospc") || raw.contains("no space") || raw.contains("space left") ->
                "手机存储空间不够了"

            e is IOException ->
                "读写没成功，可能是空间或者权限的问题"

            else ->
                "出了点状况，再试一次？"
        }
    }
}
