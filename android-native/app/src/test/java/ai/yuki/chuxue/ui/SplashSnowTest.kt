package ai.yuki.chuxue.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 开屏雪花场的契约（新开屏的纯逻辑部分）。
 *
 * 这些断言钉三件事：
 *   1. **确定性** —— 同样入参永远得到同一片雪花场。开屏是"这个 App 的一张脸"，
 *      不该每次冷启动长得不一样；
 *   2. **值域** —— 位置 / 半径 / 速度 / 相位 / 透明度都落在可绘制的范围内，
 *      不会出现 NaN、负数或"永远在屏幕外"；
 *   3. **循环** —— 纵向位置对任意时刻都在 [0,1)，雪花不会卡在天上或停在屏外。
 *
 * ⚠️ 这些断言证明的是"雪花场的数学对不对"，**证明不了开屏好看**。
 * 观感只能看真机 —— 本项目三次视觉缺陷全部只在真机暴露。
 */
class SplashSnowTest {

    @Test
    fun `同一个 count 每次得到同一片雪花场`() {
        assertEquals(SplashSnow.field(12), SplashSnow.field(12))
    }

    @Test
    fun `数量与请求一致`() {
        assertEquals(0, SplashSnow.field(0).size)
        assertEquals(1, SplashSnow.field(1).size)
        assertEquals(7, SplashSnow.field(7).size)
        assertEquals(SplashSnow.COUNT, SplashSnow.field().size)
    }

    @Test
    fun `每片雪花的各项参数都在可绘制范围内`() {
        SplashSnow.field(40).forEach { f ->
            assertTrue("x 应在 [0,1)：${f.x}", f.x >= 0f && f.x < 1f)
            assertTrue("radius 应为正：${f.radius}", f.radius > 0f)
            assertTrue("speed 应为正：${f.speed}", f.speed > 0f)
            assertTrue("phase 应在 [0,1)：${f.phase}", f.phase >= 0f && f.phase < 1f)
            assertTrue("alpha 应在 (0,1]：${f.alpha}", f.alpha > 0f && f.alpha <= 1f)
        }
    }

    @Test
    fun `纵向位置对任意时刻都落在 0 到 1 之间`() {
        val flake = SplashSnow.field(1).first()
        listOf(-3f, -0.5f, 0f, 0.25f, 0.5f, 0.99f, 1f, 2.5f, 100f).forEach { t ->
            val y = SplashSnow.yOf(flake, t)
            assertTrue("t=$t 时 y 应在 [0,1)：$y", y >= 0f && y < 1f)
        }
    }

    @Test
    fun `时间推进会让雪花往下走`() {
        // 自己构造一片，不依赖 field 的随机结果 —— 断言才稳
        val flake = SplashSnow.Flake(x = 0.5f, radius = 2f, speed = 1f, phase = 0f, alpha = 0.5f)
        val a = SplashSnow.yOf(flake, 0.10f)
        val b = SplashSnow.yOf(flake, 0.12f)
        assertTrue("雪花应随时间下移：$a -> $b", b > a)
    }

    @Test
    fun `落到底会从顶上回来`() {
        val flake = SplashSnow.Flake(x = 0.5f, radius = 2f, speed = 1f, phase = 0.9f, alpha = 0.5f)
        assertTrue("t=0 时应已接近底部：${SplashSnow.yOf(flake, 0f)}", SplashSnow.yOf(flake, 0f) > 0.85f)
        assertTrue("再过一会儿应已回绕到顶部：${SplashSnow.yOf(flake, 0.2f)}", SplashSnow.yOf(flake, 0.2f) < 0.2f)
    }

    @Test
    fun `雪花的速度分多档`() {
        val speeds = SplashSnow.field(24).map { it.speed }.distinct()
        assertTrue("速度应分档，否则整片雪一起落，像雨不像雪：$speeds", speeds.size >= 3)
    }
}
