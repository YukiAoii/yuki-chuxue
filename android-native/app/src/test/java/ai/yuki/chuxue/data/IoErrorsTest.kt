package ai.yuki.chuxue.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException

/**
 * 「IO 出错时该对用户说什么」的规格。
 *
 * ## 为什么要有它
 * 导出 / 导入 / 读图片这三条路原来直接把 `e.message` 贴给用户，于是屏幕上会出现
 * 类似 `FileNotFoundException: /storage/emulated/0/Yuki/x.json: open failed: EACCES`
 * 这种句子 —— 那是对**写这个 App 的人**说的话，不是对用户说的。
 * 天枢那边同一条：连接失败给的是「无法连接天枢账号服务：<原因>」，
 * 原因是**翻过的**，不是原始异常（`tui/account-login.ts`）。
 *
 * ## 判据
 * 一句人话要说清"多半是什么原因"，而且**不能出现异常类名**。
 * 后者是可断言的硬指标，所以下面钉住了它。
 */
class IoErrorsTest {

    /** 任何异常类名都不该出现在给用户看的句子里。 */
    private val forbidden = listOf(
        "Exception", "Error", "java.", "android.", "IOException", "null", "ENOSPC", "EACCES",
    )

    private fun assertHuman(text: String) {
        assertTrue("不能说空话", text.isNotBlank())
        forbidden.forEach { bad ->
            assertFalse("这句里漏了技术词「$bad」：$text", text.contains(bad))
        }
    }

    @Test
    fun `找不到文件 —— 说成"被移动或删了"`() {
        val text = IoErrors.explain(FileNotFoundException("/storage/emulated/0/a.json"))
        assertHuman(text)
        assertTrue("要给出可能的原因：$text", text.contains("找") || text.contains("移动"))
    }

    @Test
    fun `存储满了 —— 这是最该被点名的原因（用户能立刻去清）`() {
        val text = IoErrors.explain(IOException("write failed: ENOSPC (No space left on device)"))
        assertHuman(text)
        assertTrue("要点出空间：$text", text.contains("空间") || text.contains("满"))
    }

    @Test
    fun `没权限 —— 也要点出来`() {
        val text = IoErrors.explain(IOException("open failed: EACCES (Permission denied)"))
        assertHuman(text)
        assertTrue("要点出权限：$text", text.contains("权限"))
    }

    @Test
    fun `认不出来的 IO 异常也不许把原文倒出来`() {
        val text = IoErrors.explain(IOException("weird low-level thing happened"))
        assertHuman(text)
    }

    @Test
    fun `连 message 都是 null 也不崩、也不出现 null 字样`() {
        val text = IoErrors.explain(IOException())
        assertHuman(text)
    }

    @Test
    fun `完全陌生的异常类型 —— 兜底也是人话`() {
        assertHuman(IoErrors.explain(IllegalStateException("state=3")))
    }
}
