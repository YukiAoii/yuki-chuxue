package ai.yuki.chuxue.ui

/**
 * 开屏的雪花场 —— **纯函数，不碰 Compose**，所以在 JVM 上就能钉死（见 `SplashSnowTest`）。
 *
 * ## 为什么要把雪花算成纯函数
 * 飘雪是这一版开屏的主视觉。它的参数（每片雪的横向位置、半径、速度、相位、透明度）
 * 一旦写成"在 Canvas 里现算"，就只能靠肉眼看对不对 —— 而本项目的视觉缺陷
 * **从来不在参数上，都在观感上**，肉眼看参数恰恰是最不可靠的一环。
 *
 * 抽出来之后，"雪花不会卡住 / 不会跑到屏外 / 每次启动是同一片"这些**可以机器判定**的
 * 部分就交给测试，人只需要判断"好不好看"。这是本项目第三次用这个分法
 * （前两次是 `ChatBackground` 与 `MessageSearch`）。
 */
object SplashSnow {

    /**
     * 一片雪花。所有量都归一化到与屏幕无关的尺度，绘制时才乘上实际像素。
     */
    data class Flake(
        /** 横向位置，0..1（占屏宽的比例） */
        val x: Float,
        /** 半径，dp */
        val radius: Float,
        /** 下落速度倍率 —— 各片不同，雪花才不会"整片一起落"（那看起来像下雨） */
        val speed: Float,
        /** 初始相位，0..1；决定这一片是刚从天上进来、还是已经落了一半 */
        val phase: Float,
        /** 不透明度 */
        val alpha: Float,
    )

    /** 默认片数。多了像暴雪，少了看不出在下雪。 */
    const val COUNT = 18

    /**
     * 生成一片雪花场。
     *
     * ⚠️ **确定性**：用固定种子的 xorshift，所以每次冷启动的雪花布局是**同一片**。
     * 开屏是"这个 App 的一张脸"，不该每次长得不一样；这同时也是它可以被单测钉住的前提。
     */
    fun field(count: Int = COUNT): List<Flake> {
        if (count <= 0) return emptyList()
        var state = SEED
        fun next(): Float {
            state = state xor (state shl 13)
            state = state xor (state ushr 17)
            state = state xor (state shl 5)
            // 取高 24 位 → [0,1)
            return (state ushr 8).toFloat() / (1 shl 24).toFloat()
        }
        return List(count) {
            Flake(
                x = next(),
                radius = MIN_RADIUS + next() * (MAX_RADIUS - MIN_RADIUS),
                speed = MIN_SPEED + next() * (MAX_SPEED - MIN_SPEED),
                phase = next(),
                alpha = MIN_ALPHA + next() * (MAX_ALPHA - MIN_ALPHA),
            )
        }
    }

    /**
     * 某片雪花在时刻 [t] 的纵向位置（0..1）。
     *
     * [t] 是**循环时间**：每过 1 就重来一轮 —— 所以调用方可以把一个无限动画的进度
     * 直接喂进来，不必自己管回绕。结果是取模后的正值（负数时间也不会算出负位置）。
     */
    fun yOf(flake: Flake, t: Float): Float {
        val raw = t * flake.speed + flake.phase
        return ((raw % 1f) + 1f) % 1f
    }

    private const val SEED = 0x5F3759DF
    private const val MIN_RADIUS = 1.2f
    private const val MAX_RADIUS = 3.2f
    private const val MIN_SPEED = 0.55f
    private const val MAX_SPEED = 1.15f
    private const val MIN_ALPHA = 0.22f
    private const val MAX_ALPHA = 0.62f
}
