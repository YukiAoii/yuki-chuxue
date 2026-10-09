package ai.yuki.chuxue.ui

import ai.yuki.chuxue.data.TYPE_SPEED_FAST
import ai.yuki.chuxue.data.TYPE_SPEED_NORMAL
import ai.yuki.chuxue.data.TYPE_SPEED_OFF
import ai.yuki.chuxue.data.TYPE_SPEED_SLOW
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 打字机节奏的规格 —— 用户要求的「打字速度像人：有快有慢、偶尔停顿」。
 *
 * ## 为什么这是纯函数
 * "像人"听起来主观，但它其实是**可测的**：只要把「第 i 个字符该停多久」写成
 * 确定性函数，就能钉住三件事 —— 节奏有变化（不是匀速）、标点处更久、越界不崩。
 * 反之若把 delay 直接写在 Composable 里，这三条就只能靠盯着屏幕
 * 数秒表，而那既不可靠也不可复现。
 *
 * ## 为什么用确定性伪随机而不是 Random
 * 同一句话两次渲染出**同样的节奏**：测试能断言、用户重看时不会觉得"这次又不一样"。
 * 真随机会让"节奏有变化"这条断言变成概率游戏。
 */
class TypewriterTest {

    private fun delays(text: String, seed: Int) =
        text.indices.map { Typewriter.delayMillis(text, it, seed) }

    @Test
    fun `同样的输入产出同样的节奏 —— 可复现`() {
        val text = "你好呀，今天过得怎么样？"
        assertEquals(delays(text, seed = 42), delays(text, seed = 42))
    }

    @Test
    fun `不同 seed 给出不同的节奏`() {
        val text = "你好呀今天过得怎么样啊朋友"
        assertTrue(
            "两个 seed 不该给出完全相同的序列",
            delays(text, 1) != delays(text, 2),
        )
    }

    @Test
    fun `标点处停顿比普通字久`() {
        val normal = Typewriter.delayMillis("好好", 0, seed = 7)
        val punct = Typewriter.delayMillis("好。", 1, seed = 7)
        assertTrue("句号后应停得更久：normal=$normal punct=$punct", punct > normal)
    }

    @Test
    fun `换行处也要停一下`() {
        val normal = Typewriter.delayMillis("好好", 0, seed = 5)
        val newline = Typewriter.delayMillis("好\n", 1, seed = 5)
        assertTrue("newline=$newline normal=$normal", newline > normal)
    }

    @Test
    fun `每个间隔落在合理区间 —— 不能快到看不见，也不能慢到像卡死`() {
        val text = "你好，世界！abc 123\n换行"
        text.indices.forEach { i ->
            val d = Typewriter.delayMillis(text, i, seed = 3)
            assertTrue("第 $i 个字符的间隔 $d 越界（应 10..900）", d in 10..900)
        }
    }

    @Test
    fun `节奏确实有变化 —— 不是匀速蹦字`() {
        val text = "这是一句足够长的普通中文句子用来观察节奏变化"
        val d = delays(text, seed = 9)
        assertTrue(
            "若所有间隔都相同就是匀速，那正是用户明确不要的：$d",
            d.distinct().size > 3,
        )
    }

    @Test
    fun `空文本与越界索引都不崩`() {
        assertTrue(Typewriter.delayMillis("", 0, seed = 1) > 0)
        assertTrue(Typewriter.delayMillis("短", 99, seed = 1) > 0)
        assertTrue(Typewriter.delayMillis("短", -5, seed = 1) > 0)
    }

    /* ─────────── 速度档位（设置项，2026-09-28 新增）─────────── */

    @Test
    fun `档位越慢总耗时越长`() {
        val text = "她记得你说过的每一句话"
        fun total(speed: Int) = text.indices.sumOf {
            Typewriter.delayMillis(text, it, seed = 11, speed = speed)
        }
        val fast = total(TYPE_SPEED_FAST)
        val normal = total(TYPE_SPEED_NORMAL)
        val slow = total(TYPE_SPEED_SLOW)
        assertTrue("快档应短于标准：$fast vs $normal", fast < normal)
        assertTrue("标准应短于慢档：$normal vs $slow", normal < slow)
    }

    @Test
    fun `关闭档一律返回零 —— 调用方据此整段直接上屏`() {
        val text = "她记得你说过的每一句话"
        text.indices.forEach { i ->
            assertEquals(
                "关闭档不该有任何等待，否则调用方会白空转一整段长度",
                0L,
                Typewriter.delayMillis(text, i, seed = 11, speed = TYPE_SPEED_OFF),
            )
        }
    }

    @Test
    fun `慢档也不会越过单字上限`() {
        val text = "这是一句足够长的普通中文句子用来观察慢档会不会顶破上限"
        text.indices.forEach { i ->
            val d = Typewriter.delayMillis(text, i, seed = 7, speed = TYPE_SPEED_SLOW)
            assertTrue("慢档的单字间隔仍受 MAX_DELAY_MS 约束：$d", d <= Typewriter.MAX_DELAY_MS)
        }
    }

    @Test
    fun `档位不破坏确定性`() {
        val text = "你好呀，今天过得怎么样？"
        text.indices.forEach { i ->
            assertEquals(
                Typewriter.delayMillis(text, i, seed = 42, speed = TYPE_SPEED_SLOW),
                Typewriter.delayMillis(text, i, seed = 42, speed = TYPE_SPEED_SLOW),
            )
        }
    }
}
