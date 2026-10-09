package ai.yuki.chuxue.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 底栏 Tab 过渡的**不变量**（v0.61.0）。
 *
 * 用户报的现象：「点底部导航切换时，**上一个按钮会有类似重影的感觉**」。
 * 机制是：`animateColorAsState` 默认 300ms，旧药丸淡出、新药丸淡入**同时进行**，
 * 于是两颗在同一段时间里各半可见 → 看上去就是旧位置还留着一层影子。
 *
 * 这几条测试钉的是**修好之后**必须一直成立的事：
 * 离场**不允许有动画**。谁哪天把它改回 300，这里会红。
 */
class NavTabMotionTest {

    @Test
    fun `离场必须是 0 —— 新旧同时半可见就是重影`() {
        assertEquals(
            "离场时长必须为 0：只要 >0，旧药丸就会和新药丸同时半可见（重影）",
            0, NavTabMotion.leaveMs,
        )
    }

    @Test
    fun `进场要有缓动，而且短到不拖沓`() {
        assertTrue("进场应该有缓动（>0）", NavTabMotion.enterMs > 0)
        assertTrue("进场别超过 260ms，不然切换显得拖", NavTabMotion.enterMs <= 260)
    }

    @Test
    fun `按下反馈不能被离场的 0 波及`() {
        // 未选中但按下时，走的仍是「有缓动」的那一档 —— 按下该有的实感不能省
        assertEquals(NavTabMotion.enterMs, navSpecMs(selected = false, pressed = true))
    }

    @Test
    fun `选中与未选中各自的时长正确`() {
        assertEquals(NavTabMotion.enterMs, navSpecMs(selected = true, pressed = false))
        assertEquals(NavTabMotion.leaveMs, navSpecMs(selected = false, pressed = false))
    }
}
