package ai.yuki.chuxue.data

/**
 * 「Ta 主动来找我」取件的**节流判定**（纯函数，可单测；v0.61.40）。
 *
 * ## 为什么是"机会式"而不是定时任务
 * 项目**没有 WorkManager 依赖**、也不打算引（见 [AutoBackupPolicy] 的类注释：
 * 2 核 2G 的目标机型扛不住重依赖）。触发点 = App 启动 / 回到前台时问一次
 * "现在该不该去信箱看看"；节流窗口防止反复打点（用户频繁切前后台时）。
 *
 * ## ⚠️ 它只是"取件心跳"，不是"唤醒源"
 * 心潮作者原话：「装了心潮就不需要闹钟唤醒了，叫醒他的只有情绪变化 / 梦境 /
 * 念头涌现 / 驱力变化」。所以这里的节流**只影响"多久去信箱看一次"** ——
 * 何时说、说什么完全由心潮的情绪状态机决定，接入侧不许有闹钟逻辑。
 */
object ProactiveFetchPolicy {

    /** 默认间隔：30 分钟（取件是"去信箱看看"，不是实时管道；取太勤白耗电和服务器）。 */
    const val DEFAULT_INTERVAL_MINUTES = 30

    /**
     * 现在该不该去取件。
     *
     * @param now 当前时刻（毫秒）
     * @param lastAt 上次取件时刻；**0 = 从没取过 → 直接该取**（新开开关的用户不用等一个间隔）
     * @param intervalMinutes 两次之间至少隔几分钟（默认 [DEFAULT_INTERVAL_MINUTES]）
     */
    fun shouldFetch(
        now: Long,
        lastAt: Long,
        intervalMinutes: Int = DEFAULT_INTERVAL_MINUTES,
    ): Boolean {
        if (lastAt <= 0) return true
        // 时钟回拨（lastAt 在未来）时 now - lastAt 为负 → 不取，也不会炸
        return now - lastAt >= intervalMinutes * 60_000L
    }
}
