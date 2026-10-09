package ai.yuki.chuxue.data

import ai.yuki.chuxue.data.room.MemoryEntity
import java.util.Calendar

/**
 * 看板要的全部**指标**（v0.54.0）。
 *
 * ## ⚠️ 每个"可能没有数据"的字段都是可空的（或带 `?` 语义），不是 0
 * 看板最容易犯的错是**把"没有数据"画成 0**：首字耗时 0 会被读成"瞬间回复"、
 * 命中率 0 会被读成"缓存全废"。所以这里凡是可能没数据的，一律用 `null` 或
 * 独立的 `has…` 标志 —— 界面才有机会说"还没有数据"。
 *
 * ## ⚠️ 亲密度**不做**（2026-09-30 用户明确指示）
 * "亲密度不用做，那是很久之后的事了，现在不考虑"。
 * 所以这里没有那个字段 —— 别看着别人的看板文档把它补回来。
 *
 * ## ⚠️ 金额是**估算**
 * [savedYuan] 按当前 [BoardMath.CACHE_SAVING_PER_MILLION] 算，界面必须写明"估算"。
 * 项目一贯不显示金额（单价随时会变），这里破例是因为用户明确要求
 * "累计节省金额（对比无缓存原价）"—— 但**必须带着"估算"两个字出现**。
 */
data class BoardSummary(
    /* ── 顶部 ── */
    /** 今日消息数（user + assistant 都算） */
    val todayMessages: Int,
    /** 累计命中率（0..1）；`null` = 一次都没计费过 */
    val totalHitRatio: Double?,
    /** 估算省下的钱（元）—— 见类注释 */
    val savedYuan: Double,
    /** 连续聊天的天数（含今天；今天没聊就是 0） */
    val streakDays: Int,

    /* ── 对话维度 ── */
    val weekMessages: Int,
    val monthMessages: Int,
    /**
     * 最近 7 天**逐日**的消息数，**从最早到今天**（柱状图从左往右画）。
     *
     * ⚠️ 顺序是刻意定的：调用方拿到就能直接按 index 画，
     *    不必再去关心"哪一头是今天"——那正是最容易画反的地方。
     */
    val dailyMessages: List<Int>,
    val avgTurnsPerSession: Double,
    val topSessions: List<SessionBrief>,
    /** 最长连续聊天（分钟）—— 相邻消息间隔不超过 30 分钟算"连着聊" */
    val longestChatMinutes: Int,
    /** 平均首字耗时（毫秒）；`null` = **还没测到过**（不是 0） */
    val avgFirstByteMs: Long?,

    /* ── 角色维度 ── */
    val personaCount: Int,
    /** 最近 7 天有消息的人设数 */
    val activePersonaCount: Int,
    val personaShared: List<PersonaBrief>,
    /** 最近 7 天聊得最多的那个人设名；`null` = 最近没聊 */
    val recentPersonaName: String?,

    /* ── 记忆维度 ── */
    val memoryCount: Int,
    /** 作用域 → 条数（`persona` / `session`） */
    val memoryByScope: Map<String, Int>,
    val todayNewMemories: Int,
    /** 最近 3 条记忆（新的在前） */
    val latestMemories: List<String>,
    val avgImportance: Double,

    /* ── 成本与缓存 ── */
    val totalHit: Int,
    val totalMiss: Int,
    /** 近 N 轮的原始序列（画折线用） */
    val turns: List<BoardStats.TurnStat>,
    /** 前缀突变次数：某轮命中率从 ≥70% 掉到 <10% */
    val spikeCount: Int,
    /** 各「服务商 + 模型」的用量（排行用） */
    val modelUsage: List<BoardStats.ModelUsage>,
) {
    /** 某一轮的简要（活跃会话排行）。 */
    data class SessionBrief(val id: String, val title: String, val messages: Int, val personaName: String)

    /** 某个人设的占比（角色排行）。 */
    data class PersonaBrief(val name: String, val messages: Int, val share: Double, val since: Long)

    /** 计费总量。 */
    val billed: Int get() = totalHit + totalMiss
}

/**
 * 把原始数据算成看板要的指标（v0.54.0）。
 *
 * ⚠️ **纯函数**：不碰 Context、不碰数据库、不读时钟（`now` 由调用方传）。
 * 这一点是刻意的 —— 看板的口径（"本周"从哪天算、"连续"怎么判）是最容易
 * 各页面各算一套的地方，抽成纯函数才能在 JVM 上把每个口径钉死。
 */
object BoardMath {

    /**
     * 缓存带来的**单价差**（每百万 token 的元数）。
     *
     * ⚠️ 写死一个数是**有意的取舍**：单价是服务商随时会调的东西，
     * 而这个值只用来给"省了多少"一个量级感。界面上必须标「估算」，
     * 并且这个常量要**能一眼找到、能改**（而不是散在计算公式里）。
     *
     * 口径：DeepSeek 输入的缓存命中价 ¥0.5/百万、未命中 ¥2/百万 → 差 ¥1.5/百万。
     */
    const val CACHE_SAVING_PER_MILLION = 1.5

    /** "连着聊"的判定：相邻两条消息间隔不超过这么久，就算同一段。 */
    private const val CONTINUOUS_GAP_MS = 30 * 60 * 1000L

    /** 命中率"塌了"的判据：前一轮 ≥ 70% 而这一轮 < 10%。 */
    private const val SPIKE_HIGH = 0.70
    private const val SPIKE_LOW = 0.10

    private const val DAY_MS = 24 * 60 * 60 * 1000L

    fun build(
        sessions: List<Session>,
        personas: List<Persona>,
        memories: List<MemoryEntity>,
        turns: List<BoardStats.TurnStat>,
        modelUsage: List<BoardStats.ModelUsage>,
        now: Long,
    ): BoardSummary {
        val allMessages = sessions.flatMap { s -> s.messages.map { s to it } }
        val dayStart = startOfDay(now)
        val weekStart = dayStart - 6 * DAY_MS
        val monthStart = dayStart - 29 * DAY_MS
        val sevenDaysAgo = now - 7 * DAY_MS

        val today = allMessages.count { it.second.createdAt >= dayStart }
        val week = allMessages.count { it.second.createdAt >= weekStart }
        val month = allMessages.count { it.second.createdAt >= monthStart }
        // 最近 7 天逐日（柱状图要）—— 从左（最早）到右（今天）
        val daily = (6 downTo 0).map { back ->
            val from = dayStart - back * DAY_MS
            allMessages.count { it.second.createdAt in from until (from + DAY_MS) }
        }

        val totalHit = sessions.sumOf { it.totalHit }
        val totalMiss = sessions.sumOf { it.totalMiss }
        val billed = totalHit + totalMiss

        val personaName = { id: String ->
            personas.firstOrNull { it.id == id }
                ?.customPrompt?.substringBefore('\n')?.trim()?.takeIf { it.isNotBlank() }
                ?: "未命名角色"
        }

        val messagesByPersona = allMessages.groupingBy { it.first.personaId }.eachCount()
        val recentByPersona = allMessages
            .filter { it.second.createdAt >= sevenDaysAgo }
            .groupingBy { it.first.personaId }
            .eachCount()
        // 每个角色**最早**出现在哪段对话里（"在一起多久了"）。
        // ⚠️ 预先算一次，而不是在下面按角色逐个 `sessions.filter{...}` ——
        //    那样是 O(人设 × 会话)，人设一多就二次放大（审查指出）。
        val firstSeenByPersona = sessions
            .groupBy { it.personaId }
            .mapValues { (_, list) -> list.minOf { it.createdAt } }

        return BoardSummary(
            todayMessages = today,
            totalHitRatio = if (billed <= 0) null else totalHit.toDouble() / billed,
            savedYuan = totalHit / 1_000_000.0 * CACHE_SAVING_PER_MILLION,
            streakDays = streakDays(allMessages.map { it.second.createdAt }, dayStart),
            weekMessages = week,
            monthMessages = month,
            dailyMessages = daily,
            avgTurnsPerSession = if (sessions.isEmpty()) 0.0
            else allMessages.size.toDouble() / sessions.size,
            topSessions = sessions
                .sortedByDescending { it.messages.size }
                .take(3)
                .map {
                    BoardSummary.SessionBrief(
                        id = it.id,
                        title = it.title.ifBlank { "未命名对话" },
                        messages = it.messages.size,
                        personaName = personaName(it.personaId),
                    )
                },
            longestChatMinutes = longestChatMinutes(allMessages.map { it.second.createdAt }),
            // ⚠️ 只统计**真的测到过**的那些轮（firstByteMs > 0）——
            //    把没测到的当 0 平均进去，会把耗时算得比实际快，那是假的好消息
            avgFirstByteMs = turns.map { it.firstByteMs }.filter { it > 0 }
                .takeIf { it.isNotEmpty() }?.average()?.toLong(),
            personaCount = personas.size,
            activePersonaCount = recentByPersona.size,
            personaShared = messagesByPersona.entries
                .sortedByDescending { it.value }
                .map { (pid, n) ->
                    BoardSummary.PersonaBrief(
                        name = personaName(pid),
                        messages = n,
                        share = if (allMessages.isEmpty()) 0.0 else n.toDouble() / allMessages.size,
                        since = firstSeenByPersona[pid] ?: 0L,
                    )
                },
            recentPersonaName = recentByPersona.maxByOrNull { it.value }?.key?.let(personaName),
            memoryCount = memories.size,
            memoryByScope = memories.groupingBy { it.scope }.eachCount(),
            todayNewMemories = memories.count { it.createdAt >= dayStart },
            latestMemories = memories.sortedByDescending { it.createdAt }.take(3).map { it.content },
            avgImportance = if (memories.isEmpty()) 0.0
            else memories.sumOf { it.importance }.toDouble() / memories.size,
            totalHit = totalHit,
            totalMiss = totalMiss,
            turns = turns,
            spikeCount = spikeCount(turns),
            modelUsage = modelUsage.sortedByDescending { it.billed },
        )
    }

    /** 今天 0 点（**本地时区** —— 用户说的"今天"是 Ta 手表上的今天）。 */
    fun startOfDay(now: Long): Long = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /**
     * 连续聊天的天数（**从今天往回数**，断了就停）。
     *
     * ⚠️ 今天还没聊 → 0（而不是"从昨天算起"）：用户问"连续多少天"时，
     *    看的是"我还在保持吗"，昨天聊了而今天还没开口，链条**已经断了**。
     */
    fun streakDays(messageTimes: List<Long>, dayStart: Long): Int {
        if (messageTimes.isEmpty()) return 0
        val days = messageTimes.map { startOfDay(it) }.toSet()
        var streak = 0
        var cursor = dayStart
        // 今天没消息就返回 0（见注释）
        while (days.contains(cursor)) {
            streak++
            cursor -= DAY_MS
        }
        return streak
    }

    /**
     * 最长的一段"连着聊"（分钟）。
     *
     * 判据：把消息按时间排好，相邻两条间隔不超过 [CONTINUOUS_GAP_MS] 就算同一段 ——
     * 隔了一夜再回来说话，是**新的一段**，不该把中间那十几个小时算进"聊了多久"。
     */
    fun longestChatMinutes(messageTimes: List<Long>): Int {
        if (messageTimes.size < 2) return 0
        val sorted = messageTimes.sorted()
        var best = 0L
        var segStart = sorted.first()
        var prev = sorted.first()
        for (t in sorted.drop(1)) {
            if (t - prev > CONTINUOUS_GAP_MS) {
                best = maxOf(best, prev - segStart)
                segStart = t
            }
            prev = t
        }
        best = maxOf(best, prev - segStart)
        return (best / 60_000L).toInt()
    }

    /**
     * 前缀突变次数：某轮命中率从 ≥70% 掉到 <10%。
     *
     * ⚠️ 只数**相邻两轮**之间的塌陷。中间隔了好几轮的不算 ——
     *    那是慢慢滑下去的，不是"突变"。
     */
    fun spikeCount(turns: List<BoardStats.TurnStat>): Int {
        var n = 0
        for (i in 1 until turns.size) {
            val a = turns[i - 1].hitRatio ?: continue
            val b = turns[i].hitRatio ?: continue
            if (a >= SPIKE_HIGH && b < SPIKE_LOW) n++
        }
        return n
    }
}
