package ai.yuki.chuxue.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.yuki.chuxue.data.BoardSummary
import ai.yuki.chuxue.data.BoardStats
import ai.yuki.chuxue.data.formatHitPercent
import ai.yuki.chuxue.ui.ChatViewModel
import ai.yuki.chuxue.ui.components.LineDivider
import ai.yuki.chuxue.ui.components.SettingsGroup
import ai.yuki.chuxue.ui.icon.YukiIcons
import ai.yuki.chuxue.ui.theme.CardGap
import ai.yuki.chuxue.ui.theme.DangerRose
import ai.yuki.chuxue.ui.theme.SkyBlueDeep
import ai.yuki.chuxue.ui.theme.SnowWhite
import ai.yuki.chuxue.ui.theme.SuccessMint
import ai.yuki.chuxue.ui.theme.TextMuted
import ai.yuki.chuxue.ui.theme.TextPrimary
import ai.yuki.chuxue.ui.theme.WarnAmber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/**
 * 全部对话的看板（v0.46.0 起；v0.54.0 **重做**）。
 *
 * ## 这一版的三分区（按用户 2026-09-30 的要求）
 * 1. **顶部**：四格结论 —— 今日消息 / 累计命中率 / 省下多少（估算）/ 连续多少天；
 * 2. **中部**：两张图 —— 命中率趋势（近 20 轮折线）、最近 7 天消息量（柱状）；
 * 3. **底部**：三张榜 —— 人设排行、模型排行（带分组标签与进度条）、最近记住的，
 *    外加一张「健康状态」把几个不太好归类的数收在一起。
 *
 * ## ⚠️ 三样东西是"从这一版才开始记的"
 * **近 20 轮趋势 / 每模型用量 / 首字耗时** —— 老用户打开会看到它们偏少甚至为空。
 * 那不是坏了，是**还没有数据**。所以界面上凡是可能没数据的格子，
 * 一律说"还没有数据"，**绝不用 0 顶替**（0 会被读成"瞬间回复"/"缓存全废"）。
 *
 * ## ⚠️ 金额带「估算」两个字
 * 单价是服务商随时会调的东西，这个数只给量级感。项目一贯不显示金额，
 * 这里破例是因为用户明确要求"累计节省金额（对比无缓存原价）"。
 *
 * ## ⚠️ 亲密度不做（用户 2026-09-30 明确指示）
 * 别照着别人的看板文档把它补回来。
 *
 * ⚠️ 渲染未经真机验证（本机无设备）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlobalBoardScreen(vm: ChatViewModel, onBack: () -> Unit) {
    // ⚠️ 必须包住：`produceState` 的 producer 抛异常 = **这个协程的未捕获异常**，
    //    而它跑在 Compose 的作用域里 —— 那是崩溃级的。
    //    看板只是把本地几个数加了一遍，没有"非崩不可"的理由：
    //    算不出来就显示空态（下面的 `s == null` 分支）比把用户带崩强得多。
    val summary by produceState<BoardSummary?>(initialValue = null) {
        value = runCatching { vm.boardSummary() }.getOrNull()
    }

    Scaffold(
        containerColor = SnowWhite,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SnowWhite,
                    titleContentColor = TextPrimary,
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(YukiIcons.Back, contentDescription = "返回")
                    }
                },
                title = { Text("全部对话的看板", style = MaterialTheme.typography.titleMedium) },
            )
        },
    ) { padding ->
        val s = summary
        if (s == null) {
            // 加载中：一句话就够，不放转圈 —— 这个页面是从本地读的，通常一瞬间
            Column(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("正在算…", color = TextMuted, style = MaterialTheme.typography.bodyMedium)
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(CardGap),
        ) {
            /* ─────── ① 顶部：四个结论 ─────── */
            SettingsGroup("一眼看过去") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Kpi("今日消息", "${s.todayMessages}", Modifier.weight(1f))
                    Kpi(
                        label = "累计命中率",
                        value = s.totalHitRatio?.let { formatHitPercent(s.totalHit, s.billed) } ?: "—",
                        modifier = Modifier.weight(1f),
                        accent = s.totalHitRatio?.let { ratioColor(it) },
                    )
                }
                LineDivider()
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Kpi(
                        label = "省下（估算）",
                        value = "¥%.2f".format(s.savedYuan),
                        modifier = Modifier.weight(1f),
                    )
                    Kpi("连续聊了", if (s.streakDays > 0) "${s.streakDays} 天" else "还没开始", Modifier.weight(1f))
                }
                LineDivider()
                Text(
                    text = "「省下」是估算：按缓存命中与未命中的单价差算的，单价随时会变，只看量级就行。",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }

            /* ─────── ② 中部：两张图 ─────── */
            SettingsGroup("缓存命中率的最近走势") {
                if (s.turns.count { it.hitRatio != null } < 2) {
                    EmptyHint(
                        "还没有足够的数据。近 20 轮的走势是从这个版本才开始记的 —— " +
                            "再聊几轮就会画出来。",
                    )
                } else {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                        RatioTrendLine(s.turns)
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "每一段 = 一轮。纵轴 0–100%。" +
                                if (s.spikeCount > 0) {
                                    "检测到 ${s.spikeCount} 次突然掉零（多半是改过人设或压缩过）。"
                                } else {
                                    ""
                                },
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                        )
                    }
                }
            }

            SettingsGroup("最近 7 天的消息量") {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    DailyBars(s.dailyMessages)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth()) {
                        Text("7 天前", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                        Spacer(Modifier.weight(1f))
                        Text("今天", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        // ⚠️ 说"最近 7 天 / 30 天"，不说"本周 / 本月"：
                        //    算的是**滚动窗口**（今天往前数），不是自然周/自然月。
                        //    15 号写"本月 200 条"会被读成"这月才过一半就 200 条"，
                        //    而它其实是"最近 30 天 200 条"。数字没错，是**说法错了**。
                        text = "最近 7 天 ${s.weekMessages} 条 · 最近 30 天 ${s.monthMessages} 条 · " +
                            "平均每段对话 %.1f 条".format(s.avgTurnsPerSession),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                    )
                }
            }

            /* ─────── ③ 底部：三张榜 + 健康 ─────── */
            SettingsGroup("最常聊的对话") {
                if (s.topSessions.isEmpty()) {
                    EmptyHint("还没有对话。")
                } else {
                    s.topSessions.forEachIndexed { i, it ->
                        if (i > 0) LineDivider()
                        BoardRow(
                            title = it.title,
                            subtitle = "${it.personaName} · ${it.messages} 条",
                        )
                    }
                }
            }

            SettingsGroup("角色排行") {
                if (s.personaShared.isEmpty()) {
                    EmptyHint("还没有可以排行的角色。")
                } else {
                    s.personaShared.take(5).forEachIndexed { i, p ->
                        if (i > 0) LineDivider()
                        PersonaRankRow(p)
                    }
                    LineDivider()
                    Text(
                        text = "共 ${s.personaCount} 个角色，最近 7 天聊过 ${s.activePersonaCount} 个" +
                            (s.recentPersonaName?.let { "，最近最常找的是「$it」" } ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }

            SettingsGroup("各模型的用量") {
                if (s.modelUsage.isEmpty()) {
                    EmptyHint(
                        "还没有按模型统计过。这份统计从这个版本才开始记 —— 聊几轮之后就有了。",
                    )
                } else {
                    val totalBilled = s.modelUsage.sumOf { it.billed }.coerceAtLeast(1)
                    s.modelUsage.take(8).forEachIndexed { i, u ->
                        if (i > 0) LineDivider()
                        ModelUsageRow(u, totalBilled)
                    }
                    LineDivider()
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text("总输入", style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
                        Spacer(Modifier.weight(1f))
                        Text(
                            "命中 ${short(s.totalHit)} · 未命中 ${short(s.totalMiss)}",
                            style = MaterialTheme.typography.bodyLarge,
                            color = TextMuted,
                        )
                    }
                }
            }

            SettingsGroup("Ta 最近记住的") {
                if (s.latestMemories.isEmpty()) {
                    EmptyHint("还没有记忆。聊到值得记的事，Ta 会自己记下来。")
                } else {
                    s.latestMemories.forEachIndexed { i, m ->
                        if (i > 0) LineDivider()
                        Text(
                            text = m,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                }
            }

            SettingsGroup("健康状态") {
                StatLine("记忆条数", "${s.memoryCount}")
                LineDivider()
                StatLine(
                    "记忆分布",
                    s.memoryByScope.entries.joinToString(" · ") { (k, v) -> "${scopeName(k)} $v" }
                        .ifBlank { "—" },
                )
                LineDivider()
                StatLine("今日新增记忆", "${s.todayNewMemories}")
                LineDivider()
                StatLine("记忆平均重要度", if (s.memoryCount > 0) "%.1f".format(s.avgImportance) else "—")
                LineDivider()
                StatLine(
                    "最长连着聊",
                    if (s.longestChatMinutes > 0) "${s.longestChatMinutes} 分钟" else "—",
                )
                LineDivider()
                StatLine(
                    "平均回复耗时",
                    // ⚠️ null = **还没测到**，不是"0 毫秒" —— 后者会被读成"瞬间回复"
                    s.avgFirstByteMs?.let { "%.1f 秒".format(it / 1000.0) } ?: "还没有数据",
                )
                LineDivider()
                StatLine("命中率突然掉零", if (s.spikeCount > 0) "${s.spikeCount} 次" else "没有过")
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

/* ─────────────── 图表（Canvas 手绘，不引第三方库）─────────────── */

/** 命中率折线：每轮一个点，纵轴 0–1。 */
@Composable
private fun RatioTrendLine(turns: List<BoardStats.TurnStat>) {
    val points = turns.map { it.hitRatio }
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(96.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
    ) {
        val n = points.size
        if (n < 2) return@Canvas
        val stepX = size.width / (n - 1)
        var prev: Offset? = null
        points.forEachIndexed { i, r ->
            if (r == null) {
                prev = null   // 断点：这一轮没有计费数据，线在这里断开（而不是画到 0）
                return@forEachIndexed
            }
            val y = size.height * (1f - r.toFloat().coerceIn(0f, 1f))
            val cur = Offset(i * stepX, y)
            prev?.let { p ->
                drawLine(
                    color = SkyBlueDeep,
                    start = p,
                    end = cur,
                    strokeWidth = 3f,
                )
            }
            drawCircle(color = SkyBlueDeep, radius = 3.5f, center = cur)
            prev = cur
        }
    }
}

/** 最近 7 天的柱状图。 */
@Composable
private fun DailyBars(values: List<Int>) {
    val maxV = max(1, values.maxOrNull() ?: 1)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(80.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
    ) {
        if (values.isEmpty()) return@Canvas
        val gap = 6f
        val barW = (size.width - gap * (values.size + 1)) / values.size
        values.forEachIndexed { i, v ->
            val h = size.height * (v.toFloat() / maxV).coerceIn(0f, 1f)
            val left = gap + i * (barW + gap)
            drawRect(
                color = if (i == values.lastIndex) SkyBlueDeep else SkyBlueDeep.copy(alpha = 0.45f),
                topLeft = Offset(left, size.height - h),
                size = androidx.compose.ui.geometry.Size(barW, h),
            )
        }
    }
}

/* ─────────────── 行 ─────────────── */

@Composable
private fun Kpi(label: String, value: String, modifier: Modifier = Modifier, accent: Color? = null) {
    Column(modifier) {
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            color = accent ?: TextPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextMuted)
    }
}

@Composable
private fun BoardRow(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(subtitle, style = MaterialTheme.typography.labelSmall, color = TextMuted)
    }
}

@Composable
private fun PersonaRankRow(p: BoardSummary.PersonaBrief) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                p.name,
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${(p.share * 100).toInt()}%",
                style = MaterialTheme.typography.bodyLarge,
                color = SkyBlueDeep,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(4.dp))
        RatioBar(p.share.toFloat(), SkyBlueDeep)
        Spacer(Modifier.height(4.dp))
        Text(
            text = buildString {
                append("${p.messages} 条")
                if (p.since > 0) {
                    append(" · 认识于 ")
                    append(SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(p.since)))
                }
            },
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
        )
    }
}

/**
 * 一行模型用量：**同名模型在不同分组要分开显示**（用户明确要求）。
 *
 * 进度条的分母是**所有模型加起来**的计费量 —— 于是每一行的长度就是它占全局的比例。
 */
@Composable
private fun ModelUsageRow(u: BoardStats.ModelUsage, totalBilled: Int) {
    val share = if (totalBilled <= 0) 0f else u.billed.toFloat() / totalBilled
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                u.model.ifBlank { "未知模型" },
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${(share * 100).toInt()}%",
                style = MaterialTheme.typography.bodyLarge,
                color = SkyBlueDeep,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(4.dp))
        RatioBar(share, SkyBlueDeep)
        Spacer(Modifier.height(4.dp))
        Text(
            // 分组标签：同名模型可能出现在多个分组里，不标出来就分不清是哪一家的额度
            text = "${short(u.billed)} · ${u.requests} 次 · ${shortProvider(u.providerKey)}",
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 一条细进度条（人设占比、模型占比共用）。 */
@Composable
private fun RatioBar(ratio: Float, color: Color) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            Modifier
                .fillMaxWidth(ratio.coerceIn(0f, 1f))
                .height(6.dp)
                .clip(RoundedCornerShape(50))
                .background(color),
        )
    }
}

@Composable
private fun StatLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
        Spacer(Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyLarge, color = TextMuted)
    }
}

/** 空态：说清"为什么现在是空的"，而不是留一块白板。 */
@Composable
private fun EmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = TextMuted,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
    )
}

/* ─────────────── 小工具 ─────────────── */

private fun ratioColor(ratio: Double): Color = when {
    ratio >= 0.70 -> SuccessMint
    ratio >= 0.40 -> SkyBlueDeep
    else -> WarnAmber
}

/** token 数的短写法（与别处共用同一套口径，避免"看板说 1.2M、列表说 1200000"）。 */
private fun short(n: Int): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 1_000 -> "%.0fK".format(n / 1_000.0)
    else -> "$n"
}

/**
 * 把 providerKey（归一化后的 baseUrl）缩成一个能认得出的短标签。
 *
 * ⚠️ 只取主机名：完整 URL 会带一长串路径，挤在那一行里反而认不出是哪个分组。
 * 拿不到主机名时退回原串的前一段 —— **不隐藏**，因为"哪一家"是这行的重点。
 */
private fun shortProvider(key: String): String {
    if (key.isBlank()) return "未记录服务商"
    return key.removePrefix("https://").removePrefix("http://")
        .substringBefore('/')
        .ifBlank { key }
}

private fun scopeName(scope: String): String = when (scope) {
    "persona" -> "角色级"
    "session" -> "会话级"
    else -> scope
}
