package ai.yuki.chuxue.ui

import ai.yuki.chuxue.data.TYPE_SPEED_FAST
import ai.yuki.chuxue.data.TYPE_SPEED_NORMAL
import ai.yuki.chuxue.data.TYPE_SPEED_OFF
import ai.yuki.chuxue.data.TYPE_SPEED_SLOW

/**
 * 打字机节奏 —— 用户要求的「打字速度像人：不要匀速蹦字，要有快有慢、偶尔停顿」。
 *
 * ## 为什么它是纯函数
 * "像人"听着主观，其实是**可测的**：只要把「第 i 个字符之后该停多久」写成确定性函数，
 * 就能钉住三条 —— 节奏有变化（不是匀速）、标点处更久、越界不崩。
 * 反之若把 `delay(...)` 直接写在 Composable 里，这三条就只能盯着屏幕数秒表，
 * 既不可靠也不可复现。
 *
 * ## 为什么用确定性伪随机，而不是 `Random`
 * 同一句话两次渲染出**同样的节奏**：测试能断言，用户重看时也不会觉得"这次又不一样"。
 * 真随机会让「节奏有变化」这条断言变成概率游戏。
 *
 * ## 三个让它"像人"的细节
 * 1. **基础速度有抖动**（35–90ms/字）—— 人手打字的间隔从来不齐；
 * 2. **标点后停得更久**（150–270ms）—— 像一句话说完换口气；
 * 3. **每 12 字有一次"想一下"的机会**（+150–400ms）—— 真人会偶尔卡顿。
 *
 * ⚠️ 它只影响**显示**：网络收流、历史写入、缓存前缀全都不受影响。
 */
object Typewriter {

    /** 这些字符后停得久一些（读起来像一句话的收束）。 */
    private const val HEAVY_PUNCT = "，。！？；：、,.!?;:…—"

    /** 每多少个字符给一次"想一下"的机会。 */
    private const val PAUSE_EVERY = 12

    /**
     * 第 [index] 个字符显示完之后，等多久再显示下一个（毫秒）。
     *
     * @param seed 同一段文本用同一个 seed → 节奏可复现
     */
    fun delayMillis(
        text: String,
        index: Int,
        seed: Int,
        /** 速度档位，见 [ai.yuki.chuxue.data.TYPE_SPEED_NORMAL]。默认值让旧调用点不受影响。 */
        speed: Int = TYPE_SPEED_NORMAL,
    ): Long {
        if (text.isEmpty()) return 60L

        val i = index.coerceIn(0, text.length - 1)
        val ch = text[i]
        val r = random01(i, seed)

        val base = when {
            ch == '\n' -> 90L + (r * 60).toLong()
            HEAVY_PUNCT.indexOf(ch) >= 0 -> 150L + (r * 120).toLong()
            ch == ' ' -> 20L + (r * 25).toLong()
            else -> 35L + (r * 55).toLong()
        }

        // 偶尔停一下 —— 像人在想下一句
        val pause = if (i % PAUSE_EVERY == PAUSE_EVERY - 1 && r > 0.55) {
            150L + (r * 250).toLong()
        } else {
            0L
        }

        // 上限兜底：万一某处算得过久，整段会像卡死（**乘完倍率再兜**，
        // 否则"慢"这一档会被上限吃掉，用户感觉不到变慢）
        return ((base + pause) * factor(speed)).toLong().coerceAtMost(MAX_DELAY_MS)
    }

    /**
     * 档位 → 时间倍率。
     *
     * [TYPE_SPEED_OFF] 返回 **0**：调用方看到 0 就应当**整段直接上屏**，
     * 而不是「每字等 0 毫秒地循环一遍」—— 那会白跑一整个文本长度的次数。
     */
    fun factor(speed: Int): Double = when (speed) {
        TYPE_SPEED_OFF -> 0.0
        TYPE_SPEED_SLOW -> 1.8
        TYPE_SPEED_FAST -> 0.55
        else -> 1.0
    }

    /** 单字最长间隔 —— 超过它就不像打字，像网络卡了。 */
    const val MAX_DELAY_MS = 900L

    /** 确定性伪随机 ∈ [0,1)：同一 (index, seed) 永远得到同一个值。 */
    private fun random01(index: Int, seed: Int): Double {
        var x = (index * 1103515245 + seed * 12345) and 0x7FFFFFFF
        x = ((x xor (x shr 16)) * 0x45d9f3b) and 0x7FFFFFFF
        return (x % 1000) / 1000.0
    }
}
