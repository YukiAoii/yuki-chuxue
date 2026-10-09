package ai.yuki.chuxue.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 压缩触发模式与阈值的**契约**（v0.48.0）。
 *
 * ## 为什么这些值得单独钉
 * 1. **默认值是最容易搞错、代价又最大的一处**。压缩是唯一会主动让缓存失效的动作
 *    （摘要一进 history → 下一轮前缀全变 → 那一次全额按未命中计费）。
 *    如果默认成 `AUTO`，等于**默认替用户决定花钱**。本项目在这类"缺省语义"上栽过：
 *    文档里写着「缺 key 时用默认值而不是 0 —— 老版本升级上来的用户应当拿到
 *    「标准速度 / 回车发送」，而 0 会被读成"关闭打字机"（那是另一种语义）」。
 * 2. 阈值必须落在滑块可选的档位区间内 —— 否则设置页一打开，滑块就停在一个
 *    "不在列表里的值"上，`indexOf` 返回 -1，显示会错位。
 *
 * ⚠️ Store 的 SharedPreferences 读写在本机测不了（测试依赖只有 junit + coroutines-test，
 * 没有 Robolectric）—— 所以这里钉的是**数据层契约**，落盘往返由真机验证。
 */
class CompressSettingsTest {

    @Test
    fun `默认压缩模式是「询问」而不是「自动」—— 不替用户决定让缓存碎一次`() {
        assertEquals(COMPRESS_MODE_ASK, AppSettings().compressMode)
        assertNotEquals(
            "默认绝不能是 auto：那等于默认替用户花钱（压缩那一次全额未命中）",
            COMPRESS_MODE_AUTO, AppSettings().compressMode,
        )
    }

    @Test
    fun `默认阈值是 0_70 —— 与 ContextCompress 的默认阈值同一口径`() {
        assertEquals(0.70f, AppSettings().compressThreshold, 0.0001f)
        assertEquals(
            "两处默认值必须一致，否则设置页显示与实际触发点对不上",
            ContextCompress.DEFAULT_THRESHOLD, AppSettings().compressThreshold, 0.0001f,
        )
    }

    @Test
    fun `三个模式常量互不相同且非空`() {
        val all = listOf(COMPRESS_MODE_AUTO, COMPRESS_MODE_ASK, COMPRESS_MODE_MANUAL)
        assertTrue("不该有空常量", all.all { it.isNotBlank() })
        assertEquals("三个模式不能重名（重名会让 when 分支静默失效）", 3, all.toSet().size)
    }

    @Test
    fun `默认阈值落在滑块可选区间内`() {
        val t = AppSettings().compressThreshold
        assertTrue("阈值低于下界", t >= COMPRESS_THRESHOLD_MIN)
        assertTrue("阈值高于上界", t <= COMPRESS_THRESHOLD_MAX)
    }

    @Test
    fun `阈值区间非退化 —— 设置页滑块才有可拖的余地`() {
        assertTrue(
            "上下界必须拉开距离",
            COMPRESS_THRESHOLD_MAX - COMPRESS_THRESHOLD_MIN > 0.1f,
        )
    }
}
