package ai.yuki.chuxue.data

/**
 * 自动备份的**判定**（纯函数，可单测；v0.53.0）。
 *
 * ## ⚠️ 为什么不用 WorkManager（这条是本设计的最大取舍）
 * 参考实现 Operit 用 `WorkManager` 的 24 小时周期任务 + 首次延迟到 03:00，
 * 好处是**进程死了也能被系统唤醒**。但本项目**没有 WorkManager 依赖**，
 * 而且明确不打算引（见 `MemoryExtractionScheduler` 的类注释：项目一贯不引文档示例里的重依赖，
 * 2 核 2G 的目标机型也扛不住）。
 *
 * 所以这里的模型是**机会式**的：App 被打开（或回到前台）时问一次"现在该不该备份"——
 * - 距上次不足 [DEFAULT_INTERVAL_HOURS] 小时 → 不跑（节流）；
 * - 当前小时不在用户设的时段内 → 不跑；
 * - 都满足 → 跑一次。
 *
 * **代价必须说清：App 长期不打开，备份就不会发生。** 界面上的说明文案照实写，
 * 不能让它看起来像"闹钟"。
 *
 * ## 为什么判定要抽成纯函数
 * "什么时候会触发"是这类功能唯一容易出错、又最难复现的地方
 *（跨午夜时段、刚备份过、时段边界）。抽出来之后每个边界都能在 JVM 上钉死。
 */
object AutoBackupPolicy {

    /** 默认间隔：24 小时。 */
    const val DEFAULT_INTERVAL_HOURS = 24

    /** 默认时段：凌晨 3 点起、6 点止（`[3, 6)`）。 */
    const val DEFAULT_WINDOW_START = 3
    const val DEFAULT_WINDOW_END = 6

    /** 默认最多保留几份。 */
    const val DEFAULT_MAX_FILES = 10

    const val MIN_MAX_FILES = 1
    const val MAX_MAX_FILES = 100

    /**
     * 现在该不该跑一次自动备份。
     *
     * ## ⚠️ v0.55.0：**不再有时段**
     * 用户要求把"允许备份的时段"那个设置**整个去掉**，换成一个开关 ——
     * 开关的含义就是「每天备份一份」。所以现在只剩一条判据：
     * **距上次够久了没有**。
     *
     * 那个时段设置原本的用处是"别在我玩手机的时候备份"，但它带来两个真实的坏处：
     * 1. 用户为了让它跑，得先把时间调对 —— 而多数人根本不会回来调；
     * 2. 调错了（起止填反、忘了改）就**永远不会备份**，而这一点从界面上看不出来。
     * 备份本身很轻（一份 JSON），挑时段的收益不值这两个代价。
     *
     * @param now 当前时刻（毫秒）
     * @param lastAt 上次自动备份的时刻；**0 = 从未备份过**
     * @param intervalHours 两次之间至少隔几小时（默认 24 = 一天一份）
     */
    fun shouldRun(
        now: Long,
        lastAt: Long,
        intervalHours: Int = DEFAULT_INTERVAL_HOURS,
    ): Boolean {
        // ① 节流：还没到间隔就不跑。⚠️ lastAt <= 0（从没备份过）不参与节流 ——
        //    否则新装的用户要等满 24 小时才会有第一份自动备份。
        if (lastAt > 0 && now - lastAt < intervalHours * 3_600_000L) return false
        // ② v0.55.0 起**没有时段判据**：够久了就跑，什么时候都行
        return true
    }

    /**
     * [hour] 是否落在 `[start, end)` 里。
     *
     * ⚠️ 支持**跨午夜**（`start > end`，例如 23 点到 6 点）——
     * 不处理的话，用户设一个"晚上 11 点以后"会被判成"永远不跑"，
     * 而那种错误在真机上只会表现为"自动备份怎么从来没跑过"。
     *
     * ⚠️ `start == end` 视为**全天**（而不是"空区间"）：用户把两个值调成一样，
     * 想表达的显然是"什么时候都行"。
     */
    fun inWindow(hour: Int, start: Int, end: Int): Boolean {
        val h = ((hour % 24) + 24) % 24
        val s = ((start % 24) + 24) % 24
        val e = ((end % 24) + 24) % 24
        if (s == e) return true
        return if (s < e) h in s until e else (h >= s || h < e)
    }

    /**
     * 超出份数上限时需要删掉几份（**删最旧的**，由调用方排序后执行）。
     *
     * ⚠️ 上限先 `coerceIn` 再算 —— 用户填 0 或 -5 时应当是"至少留 1 份"，
     * 而不是"把备份全删了"。（真删光的代价是：他以为有备份，其实一份不剩。）
     */
    fun excessCount(fileCount: Int, maxFiles: Int): Int =
        (fileCount - maxFiles.coerceIn(MIN_MAX_FILES, MAX_MAX_FILES)).coerceAtLeast(0)
}

/**
 * 自动备份的**当前设置**（一次性读给界面用）。
 *
 * ⚠️ 做成一个不可变快照、而不是让界面自己去 `Store` 里逐个读：界面拿到的必须是
 * **同一时刻的一组值** —— 否则用户改时段时可能看到"起 3 点、止 6 点"里的两个数
 * 来自不同的两次读取。
 */
data class AutoBackupSettings(
    /** 自动备份是否开启 */
    val enabled: Boolean = true,
    /** 最多保留几份 */
    val maxFiles: Int = AutoBackupPolicy.DEFAULT_MAX_FILES,
    /** 上次自动备份的时刻；0 = 从没备份过 */
    val lastAt: Long = 0L,
) {
    /**
     * 距上次够久了没有（够久 = 该跑下一次了）。
     *
     * ⚠️ v0.55.0：时段设置已取消，开关的语义就是**每天一份**（间隔 24 小时）。
     *    界面拿它说"下次大概什么时候"，而不是去讲一个已经不存在的"时段"。
     */
    fun dueBy(now: Long, intervalHours: Int = AutoBackupPolicy.DEFAULT_INTERVAL_HOURS): Boolean =
        lastAt <= 0 || now - lastAt >= intervalHours * 3_600_000L
}
