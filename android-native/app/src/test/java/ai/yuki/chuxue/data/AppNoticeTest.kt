package ai.yuki.chuxue.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「对话里那条提醒该显示哪一句」的规格。
 *
 * ## 背景（为什么会有这个函数）
 * 这三条通道（`_error` / `_loadWarning` / `_notice`）原来都走**顶部一闪而过的胶囊**：
 * 用户没看清就没了，也没法回头再看。现在改成**画在对话里的提醒卡**（带关闭按钮）。
 *
 * 画在对话里就意味着**同一时刻只该有一条**（否则会叠好几张，还把消息挤下去），
 * 所以这里定死优先级：**出错 > 数据警告 > 一般提示**。
 * 出错优先级最高是因为它多半要用户做点什么（去填密钥 / 去设置里看），
 * 另外两条更多是"知道一下"。
 *
 * ⚠️ 它只管**界面**：这三条本来就不写 `messages`、不进请求体（缓存红线），
 *    这个函数也不碰它们。
 */
class AppNoticeTest {

    @Test
    fun `三条都空 —— 什么都不该显示`() {
        assertNull(AppNotice.of(null, null, null))
        assertNull(AppNotice.of("", "   ", ""))
    }

    @Test
    fun `出错优先于警告与提示`() {
        val item = AppNotice.of("先到设置里填好密钥", "旧记录读取失败", "已导出")
        assertEquals(AppNotice.Kind.ERROR, item?.kind)
        assertEquals("先到设置里填好密钥", item?.text)
    }

    @Test
    fun `没有出错时，警告优先于提示`() {
        val item = AppNotice.of(null, "旧记录读取失败", "已导出")
        assertEquals(AppNotice.Kind.WARN, item?.kind)
        assertEquals("旧记录读取失败", item?.text)
    }

    @Test
    fun `只剩提示时就显示提示`() {
        val item = AppNotice.of(null, null, "已导出「聊天记录」")
        assertEquals(AppNotice.Kind.INFO, item?.kind)
    }

    @Test
    fun `只有空白串的那一条不算数 —— 否则会弹出一张空卡`() {
        val item = AppNotice.of("  ", null, "已导出")
        assertEquals(AppNotice.Kind.INFO, item?.kind)
    }

    @Test
    fun `三种类型互不相等 —— 关闭时要按它决定关掉哪一条`() {
        assertTrue(AppNotice.Kind.ERROR != AppNotice.Kind.WARN)
        assertTrue(AppNotice.Kind.WARN != AppNotice.Kind.INFO)
        assertEquals(3, AppNotice.Kind.entries.size)
    }
}
