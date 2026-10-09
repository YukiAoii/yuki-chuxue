package ai.yuki.chuxue.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.yuki.chuxue.data.CacheMath
import ai.yuki.chuxue.data.ContextCompress
import ai.yuki.chuxue.data.ProviderProfile
import ai.yuki.chuxue.data.Session
import ai.yuki.chuxue.data.formatHitPercent
import ai.yuki.chuxue.ui.components.LineDivider
import ai.yuki.chuxue.ui.components.SettingsGroup
import ai.yuki.chuxue.ui.icon.YukiIcons
import ai.yuki.chuxue.ui.theme.SkyBlueDeep
import ai.yuki.chuxue.ui.theme.SnowWhite
import ai.yuki.chuxue.ui.theme.SuccessMint
import ai.yuki.chuxue.ui.theme.TextMuted
import ai.yuki.chuxue.ui.theme.TextPrimary
import ai.yuki.chuxue.ui.theme.WarnAmber

/**
 * 缓存诊断（开发文档 §43；v0.53.0 **重写**）。
 *
 * ## 为什么重写
 * 用户 2026-09-30：「缓存诊断界面也重写 目前很乱 解释的也很难懂」。
 *
 * 上一版的毛病不在内容少，而在**顺序反了**：它把计价机制
 *（128 个 token 一块、末尾那块不算、按块反推上一轮长度）放在很前面。
 * 于是一个刚打开这一页的人，得先读懂缓存怎么计价，才可能知道
 * "我现在到底好不好、要不要做点什么"。
 *
 * ## 这一版的顺序：结论 → 现象 → 怎么办 → 原理
 * 1. **一句话结论**（`VerdictCard`）：现在正常 / 偏低 / 还没数据 —— 原来完全没有这一层；
 * 2. **最近一次**：三个数 + 一句人话（为什么会是这个数）；
 * 3. **掉下来时看这里**：三种最常见原因 + "这些都是一次性的"，**可操作**；
 * 4. **累计**：带免责说明（会被历史拖住）；
 * 5. **原理**：想深究的人再往下看。
 *
 * ⚠️ **数字口径一字未改**（仍是本地估算、仍不显示金额）——
 * 这次重写动的只有"讲法"，不动账。
 *
 * ⚠️ 渲染未经真机验证（本机无 adb / 无设备）。
 *
 * @param lastTurn 最近一次的读数 `(命中, 未命中, 本轮发出)`；还没聊过时为 null。
 * @param providerOf 取某段对话实际在用的服务商画像（只认 baseUrl，所以调用方算好传进来）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CacheDiagnosticsScreen(
    sessions: List<Session>,
    lastTurn: Triple<Int, Int, Int>?,
    providerOf: (Session) -> ProviderProfile,
    onBack: () -> Unit,
) {
    val totalHit = sessions.sumOf { it.totalHit }
    val totalMiss = sessions.sumOf { it.totalMiss }

    val turn = lastTurn
    val turnBilled = if (turn != null) turn.first + turn.second else 0

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
                title = { Text("缓存诊断", style = MaterialTheme.typography.titleMedium) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            VerdictCard(turn = turn)

            /* ── ② 最近一次：三个数与一句人话 ── */
            if (turn != null && turnBilled > 0) {
                val (hit, miss, input) = turn
                SettingsGroup("最近一次") {
                    HitRateRow(label = "命中率", hit = hit, billed = hit + miss)
                    LineDivider()
                    StatRow("命中", ContextCompress.formatTokens(hit))
                    LineDivider()
                    StatRow("未命中", ContextCompress.formatTokens(miss))
                    LineDivider()
                    StatRow("本轮发出", ContextCompress.formatTokens(input))
                    LineDivider()
                    Text(
                        // 一句话人话代替原来的"块数/反推"两条术语 ——
                        // 那些移到最下面的「原理」里，给想深究的人。
                        text = plainExplain(hit = hit, input = input),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            } else {
                SettingsGroup("最近一次") {
                    Text(
                        text = "还没发过消息 —— 发一条之后这里会显示那一次的读数。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextMuted,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    )
                }
            }

            /* ── ③ 掉下来时看这里：可操作指引（上一版完全没有） ── */
            SettingsGroup("命中率掉下来了？通常是这三个原因") {
                ReasonRow("① 改过人设或角色设定", "前缀从改动处断开一次，聊几句就回来")
                LineDivider()
                ReasonRow("② 刚刚压缩过", "摘要替换了那段历史，那一次必然不命中")
                LineDivider()
                ReasonRow("③ 隔了很久没聊", "服务商侧的缓存有存活时间，过期就没了")
                LineDivider()
                Text(
                    text = "这三种都是「一次性」的：断开之后新前缀会被重新缓存，" +
                        "继续聊就会回到正常水平。真正该担心的是——「连续多轮一直都是 0」，" +
                        "那说明前缀每轮都在变（多半是有人改了什么）。",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }

            /* ── ④ 累计 ── */
            SettingsGroup("全部对话的累计") {
                HitRateRow(
                    label = "累计命中率",
                    hit = totalHit,
                    billed = totalHit + totalMiss,
                    emptyText = "还没有请求",
                )
                LineDivider()
                StatRow("命中 token", ContextCompress.formatShort(totalHit))
                LineDivider()
                StatRow("未命中 token", ContextCompress.formatShort(totalMiss))
                LineDivider()
                StatRow("对话数", sessions.size.toString())
                LineDivider()
                Text(
                    text = "这个数会被历史上那些没命中的请求长期拖住" +
                        "（每次新开一段对话，第一轮必然 0%）—— " +
                        "它偏低「不代表现在有问题」，看上一条「最近一次」更准。",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }

            /* ── ⑤ 原理：后置，给想深究的人 ── */
            if (turn != null && turnBilled > 0) {
                val (hit, _, input) = turn
                SettingsGroup("它是怎么算的（想深究再看）") {
                    Text(
                        text = "下面这套是在 DeepSeek 上「实测」出来的，别的服务商不一定一样。",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                    LineDivider()
                    StatRow(
                        label = "命中块数",
                        value = if (hit == 0) "0 块" else "${CacheMath.blockCount(hit)} 块（128/块）",
                        accent = if (hit == 0 || CacheMath.isBlockAligned(hit)) null else WarnAmber,
                    )
                    LineDivider()
                    Text(
                        text = "服务方按 128 个 token 一块缓存：一次请求里，前面与上次" +
                            "完全相同的「整块」算命中（按缓存价计费），其余按全价。" +
                            "末尾那块不算 —— 它紧接着要用来生成新内容。",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                    CacheMath.previousInputRange(hit)?.let { range ->
                        LineDivider()
                        Text(
                            text = "按块反推：上一轮发出约 " +
                                "${ContextCompress.formatTokens(range.first)} ~ " +
                                "${ContextCompress.formatTokens(range.last)}。" +
                                "能命中多少，取决于「上一轮」的长度。",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                    LineDivider()
                    Text(
                        text = "这也是这个 App 一直坚持「历史只追加、不改写」的原因 ——" +
                            "任何一次改写都会让后面所有轮次的前缀跟着变。",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                    LineDivider()
                    Text(
                        text = "这里不显示金额：单价是服务方随时会调的东西，" +
                            "写死一个数只会给你一个过时的估算。",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }

            /* ── 各段对话 ── */
            if (sessions.isNotEmpty()) {
                SettingsGroup("各段对话") {
                    sessions
                        .sortedByDescending { it.totalHit + it.totalMiss }
                        .take(20)
                        .forEachIndexed { index, s ->
                            if (index > 0) LineDivider()
                            StatRow(
                                label = s.title.ifBlank { "未命名对话" },
                                // ⚠️ 走共用的纯函数（有单测）：三种"没有数字"要分开说，
                                //    否则用户看到的是同一个 0%，而该做的事完全不同。
                                value = providerOf(s).cacheLineFor(s),
                                accent = null,
                                maxLabelLines = 1,
                            )
                        }
                }
                Text(
                    text = "只列前 20 段（按用量从多到少）。",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
        }
    }
}

/**
 * **顶部一句话结论**（v0.53.0 新增）—— 上一版没有这一层，
 * 用户得自己把几个数拼起来才能判断"我现在好不好"。
 *
 * ⚠️ 判据只有"最近一次"（没有它就说"还没数据"）：累计值会被历史拖住，
 * 拿它下结论会把正常用户吓一跳。
 */
@Composable
private fun VerdictCard(turn: Triple<Int, Int, Int>?) {
    // ⚠️ 先把命中的那个数取出来：`when` 里直接写 `turn.first` **不会**被 smartcast
    //（null 检查在另一个分支上），那是编译错误。
    val hit = turn?.first ?: 0
    val billed = if (turn != null) turn.first + turn.second else 0

    // 比例比较用整数（hit*10 vs billed*7）：避免浮点，也让 0.70/0.40 这两个阈值
    // 与 HitRateRow 里的判定保持一致
    val (title, detail, color) = when {
        turn == null || billed <= 0 ->
            Triple("还没有读数", "发一条消息，这里会立刻告诉你那一次命中得怎么样。", TextMuted)
        hit * 10 >= billed * 7 ->
            Triple("缓存工作正常", "最近这次有 ${formatHitPercent(hit, billed)} 的输入走了缓存价。", SuccessMint)
        hit * 10 >= billed * 4 ->
            Triple("命中率一般", "最近这次命中 ${formatHitPercent(hit, billed)}。往下看「掉下来时看这里」。", SkyBlueDeep)
        else ->
            Triple("最近这次几乎没命中", "先看下面「通常是这三个原因」——大多是一次性的，聊几句就回来。", WarnAmber)
    }

    SettingsGroup("现在怎么样") {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = color,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** 一条"原因 + 怎么办"。 */
@Composable
private fun ReasonRow(reason: String, what: String) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            text = reason,
            style = MaterialTheme.typography.bodyLarge,
            color = TextPrimary,
        )
        Text(
            text = what,
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/**
 * 把"最近一次"翻译成一句人话 —— 替代原来那两条术语（块数 / 反推）。
 *
 * ⚠️ 只在**输入太短**这种确有解释价值时说机制；其余情况不给术语。
 */
private fun plainExplain(hit: Int, input: Int): String {
    if (hit <= 0) {
        // 短输入是最常见、也最容易被误解成"坏了"的情况，值得给一句解释
        return CacheMath.shortInputNote(input)
            ?: "这一次没命中 —— 多半是刚改过人设、刚压缩过，或者隔太久没聊。"
    }
    val blocks = CacheMath.blockCount(hit)
    return "命中了 $blocks 块（每块 ${CacheMath.BLOCK} 个 token）。" +
        "能命中多少取决于上一轮发了多长 —— 这一轮新加的内容还没进缓存。"
}

/**
 * 命中率一行 —— 结论卡 / 最近一次 / 累计三处共用，**不各写一份格式化**。
 *
 * ⚠️ 走共用的 [formatHitPercent]（v0.48.0 R3）：它专门处理"99.6% 别说成 100%"，
 * 各处手写 `%.1f%%` 正是那个 bug 的来源。
 */
@Composable
private fun HitRateRow(
    label: String,
    hit: Int,
    billed: Int,
    emptyText: String = "还没有读数",
) {
    val ratio = CacheMath.hitRatio(hit, billed)
    StatRow(
        label = label,
        value = if (billed <= 0) emptyText else formatHitPercent(hit, billed),
        accent = when {
            billed <= 0 -> TextMuted
            ratio >= 0.70 -> SuccessMint
            ratio >= 0.40 -> SkyBlueDeep
            else -> WarnAmber
        },
    )
}

@Composable
private fun StatRow(
    label: String,
    value: String,
    accent: androidx.compose.ui.graphics.Color? = null,
    maxLabelLines: Int = Int.MAX_VALUE,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = TextPrimary,
            maxLines = maxLabelLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = accent ?: TextPrimary,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
