package ai.yuki.chuxue.ui.settings

import ai.yuki.chuxue.data.Balance
import ai.yuki.chuxue.data.ContextCompress
import ai.yuki.chuxue.data.ProviderProfiles
import ai.yuki.chuxue.data.formatHitPercent
import ai.yuki.chuxue.data.room.ProviderUsage
import ai.yuki.chuxue.data.room.ProviderUsageEntity
import ai.yuki.chuxue.ui.ChatViewModel
import ai.yuki.chuxue.ui.components.YukiCard
import ai.yuki.chuxue.ui.icon.YukiIcons
import ai.yuki.chuxue.ui.theme.DangerRose
import ai.yuki.chuxue.ui.theme.FrostLine
import ai.yuki.chuxue.ui.theme.SnowSurface
import ai.yuki.chuxue.ui.theme.SnowWhite
import ai.yuki.chuxue.ui.theme.TextMuted
import ai.yuki.chuxue.ui.theme.TextPrimary
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import ai.yuki.chuxue.ui.theme.SkyBlueDeep
import ai.yuki.chuxue.ui.theme.WarnAmber
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.width
import ai.yuki.chuxue.ui.theme.AuroraViolet
import ai.yuki.chuxue.ui.theme.IceCyan
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * **会话看板**（用户 2026-09-29 要求）。
 *
 * 入口在**每段对话的对话设置**里 —— 它回答的是"这一段聊得怎么样"：
 * 花了多少、省了多少、还有多少钱。
 *
 * ## ⚠️ 余额是**账号级**的，不是这段对话的
 * 所以余额那一栏单独标注了「账户」前缀。入口在会话里、而数据是全账号的，
 * 不标注的话用户会以为"这段对话还剩 110 元"。
 *
 * ## ⚠️ 余额拉不到时**只降级那一行**
 * 看板是只读页，一次网络失败不该让整页报错（那会连本地统计都看不到）。
 *
 * ⚠️ 页面上的数字**只上屏、绝不进请求体**。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionBoardScreen(
    vm: ChatViewModel,
    sessionId: String,
    onBack: () -> Unit,
) {
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val session = remember(sessions, sessionId) { sessions.firstOrNull { it.id == sessionId } }

    /**
     * 余额查的是「**这段对话实际用的那个账号**」的余额（v0.51.0：分组优先）。
     *
     * ⚠️ 判据不能看全局 `settings.apiKey` —— 分组界面启用之后密钥落在**分组**里，
     *    全局那个字段对新用户**恒为空串**，于是"配好了"会被读成"没配"、
     *    余额永远显示"读不到"。而更糟的是反过来：拿全局那份去查，
     *    开了第三方分组的用户会看到**另一个账号**的余额 —— 一个错的数字会被当真。
     */
    val balanceKey = remember(settings, session) { vm.effectiveSettings(session).apiKey }

    /**
     * 这家服务商**有没有余额查询接口**（v0.51.0）。
     *
     * ⚠️ `GET /user/balance` 是 **DeepSeek 专有**的。没有这个判据的话，第三方分组
     * 会去撞一个必然 404 的地址，然后把结果显示成"暂时读不到余额" ——
     * 用户会去反复检查网络和密钥，而那个接口**永远不可能存在**。
     */
    val hasBalanceApi = remember(settings, session) {
        ProviderProfiles.resolve(vm.effectiveSettings(session).baseUrl).balancePath != null
    }

    // 按服务商分桶的读数（v0.51.0）—— 进页面读一次，不是常驻流（见 VM 的注释）
    val usageRows by produceState(initialValue = emptyList<ProviderUsageEntity>(), sessionId) {
        value = runCatching { vm.providerUsageOf(sessionId) }.getOrDefault(emptyList())
    }

    var balance by remember { mutableStateOf<Balance?>(null) }
    var balanceFailed by remember { mutableStateOf(false) }
    var balanceLoading by remember { mutableStateOf(true) }

    LaunchedEffect(sessionId, balanceKey, hasBalanceApi) {
        if (!hasBalanceApi) {
            // ⚠️ 该服务商压根**没有**这个接口（`balancePath == null`）——
            //    既不该去撞一个必然 404 的地址，也不该说"暂时读不到"：
            //    那会让用户以为是自己的网络问题，反复重试一个不存在的东西。
            balanceLoading = false
            balanceFailed = true
            return@LaunchedEffect
        }
        if (balanceKey.isBlank()) {
            balanceLoading = false
            balanceFailed = true
            return@LaunchedEffect
        }
        balanceLoading = true
        balanceFailed = false
        runCatching { vm.fetchBalance(sessionId) }
            .onSuccess { balance = it }
            // ⚠️ 只把这一行标成读不到，页面其余部分照常显示
            .onFailure { balanceFailed = true }
        balanceLoading = false
    }

    val hit = session?.totalHit ?: 0
    val miss = session?.totalMiss ?: 0
    val billed = hit + miss
    // ⚠️ 显示走**共用**格式化（v0.48.0）：整数除法会把 99.6% 说成 100%，
    //    而用户正是拿这个数判断"省钱开关有没有生效"。
    // ⚠️ 数值仍单独留着 —— 弧形仪表盘的填充比例、变色的阈值判定都要用它，
    //    不能拿字符串去算。
    val rateText = if (billed > 0) formatHitPercent(hit, billed) else "—"
    val rateValue = if (billed > 0) hit.toFloat() / billed else 0f
    // ⚠️ **压缩之后**的命中率（v0.46.1）：累计值会被压缩那一次的大 miss 拖住，
    // 减掉快照才是"这段时间真实的表现"。没有快照（老数据）就显示 0 表示不可用。
    val sinceHit = hit - (session?.hitAtCompress ?: 0)
    val sinceMiss = miss - (session?.missAtCompress ?: 0)
    val sinceBilled = sinceHit + sinceMiss
    val sinceText = if (sinceBilled > 0) formatHitPercent(sinceHit, sinceBilled) else "—"
    // -1 保留"没有快照"这个语义（调用处据此决定显不显示这一块）
    val sinceRate = if (sinceBilled > 0) (sinceHit * 100 / sinceBilled) else -1
    val used = session?.messages?.let { ContextCompress.estimateTokens(it) } ?: 0
    // ── 专业化看板要的三块（v0.46.0，全部用现有数据，不新增表）──
    // ① 上下文构成：人设 + 历史 + 摘要（各自估算，用来画堆叠条）
    val personaTokens = ContextCompress.estimateTokens(
        session?.personaId?.let { pid -> vm.personaById(pid)?.customPrompt.orEmpty() }.orEmpty(),
    )
    val summaryTokens = ContextCompress.estimateTokens(session?.summary.orEmpty())
    val historyTokens = (used - personaTokens - summaryTokens).coerceAtLeast(0)
    // ② 是否压缩过（有切点 = 压过）
    val compressed = (session?.summaryUpTo ?: 0L) > 0L

    Scaffold(
        containerColor = SnowWhite,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SnowSurface,
                    titleContentColor = TextPrimary,
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(YukiIcons.Back, contentDescription = "返回")
                    }
                },
                title = { Text("这段对话的看板", style = MaterialTheme.typography.titleMedium) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── 缓存与用量（本地已有，随时可看）──
            // ── 命中率：弧形仪表盘（用户要求，v0.45.7）──
            YukiCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("缓存命中率", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(14.dp))
                    GaugeArc(
                        // ⚠️ 没有请求时画 0 而不是满格 —— 满格会让人以为"命中很好"
                        value = rateValue,
                        centerText = rateText,
                    )
                    // ── 压缩之后的命中率（有快照才显示）──
                    if (sinceRate >= 0) {
                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider(color = FrostLine)
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "压缩之后",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = TextPrimary,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "上面那个是累计值 —— 压缩那一次会全额计入未命中，把累计拖住很多轮",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextMuted,
                                )
                            }
                            Text(
                                "$sinceText",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (sinceRate >= 80) SkyBlueDeep else WarnAmber,
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        if (billed > 0) "命中率越高越省钱；首轮 0% 是正常的。" else "这段对话还没有过请求。",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                    )
                }
            }

            // ── 明细 ──
            YukiCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("用量明细", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    BoardRow("累计命中", "$hit tokens")
                    BoardDivider()
                    BoardRow("累计未命中", "$miss tokens")
                    BoardDivider()
                    BoardRow("估算占用", ContextCompress.formatTokens(used))
                }
            }

            // ── 按服务商分桶（v0.51.0）──
            // ⚠️ 为什么要有这一块：这段对话可能**中途换过服务商/模型**，而上面那个
            //    「缓存命中率」是按会话累加的 —— 它混了不同服务商的读数，而各家的
            //    命中字段、粒度、起步门槛都不一样，混出来的数**谁都不对应**。
            //    这里把每一家单独列出来，并且只在真的跨了多家时才多花一块版面。
            if (usageRows.isNotEmpty()) {
                val mixed = ProviderUsage.isMixed(usageRows.map { it.providerKey })
                YukiCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "按服务商",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(6.dp))
                        if (mixed) {
                            Text(
                                "这段对话用过多个服务商 —— 上面那个累计命中率是混合值，谁都不对应。" +
                                    "分开看才准：",
                                style = MaterialTheme.typography.labelSmall,
                                color = WarnAmber,
                            )
                            Spacer(Modifier.height(6.dp))
                        }
                        usageRows.forEachIndexed { i, row ->
                            if (i > 0) BoardDivider()
                            // ⚠️ 桶里存的 hit/miss **已经是按这一家口径取过的值**
                            //   （累加时走的就是 profile.hitTokensOf/missTokensOf）——
                            //    这里绝不能再"反推"一次：对 OpenAI 系会读到 null → 0，
                            //    把一个好好的命中率显示成 0%。
                            val prof = ProviderProfiles.resolve(row.providerKey)
                            BoardRow(
                                label = row.providerKey,
                                value = ProviderUsage.describeText(
                                    hit = row.hitTokens,
                                    miss = row.missTokens,
                                    requests = row.requests,
                                    reports = prof.reportsCachedTokens,
                                ) + " · ${row.requests} 次",
                            )
                        }
                    }
                }
            }

            // ── 余额（账号级，需联网）──
            // ── Token 去向（用户给的「Token Stats」卡）──
            // 回答"钱花在哪了"：缓存读便宜、未缓存输入贵、输出最贵。
            YukiCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Token 去向", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(10.dp))
                    if (billed == 0) {
                        Text("这段对话还没有过请求。", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
                    } else {
                        // 环形：命中(蓝) 与 未命中(橙) 的占比
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            DonutChart(
                                slices = listOf(
                                    Pair(hit.toFloat(), SkyBlueDeep),
                                    Pair(miss.toFloat(), WarnAmber),
                                ),
                            )
                            Spacer(Modifier.width(16.dp))
                            Column {
                                LegendRow(SkyBlueDeep, "缓存命中", "$hit")
                                Spacer(Modifier.height(6.dp))
                                LegendRow(WarnAmber, "未缓存输入", "$miss")
                            }
                        }
                    }
                }
            }

            // ── 上下文构成（用户给的「Current Context」卡）──
            // 六色堆叠条太重，这里收敛成三色 —— 对话里真正影响费用的就是这三样。
            YukiCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("上下文构成", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "现在发给Ta的那一份由哪几块组成（估算）",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                    )
                    Spacer(Modifier.height(12.dp))
                    StackedBar(
                        listOf(
                            Triple("人设", personaTokens, SkyBlueDeep),
                            Triple("摘要", summaryTokens, AuroraViolet),
                            Triple("对话", historyTokens, IceCyan),
                        ),
                    )
                    Spacer(Modifier.height(10.dp))
                    LegendRow(SkyBlueDeep, "人设", ContextCompress.formatTokens(personaTokens))
                    Spacer(Modifier.height(4.dp))
                    if (summaryTokens > 0) {
                        LegendRow(AuroraViolet, "摘要", ContextCompress.formatTokens(summaryTokens))
                        Spacer(Modifier.height(4.dp))
                    }
                    LegendRow(IceCyan, "对话", ContextCompress.formatTokens(historyTokens))
                }
            }

            // ── 事件记录（用户给的「Context Events」卡）──
            // 只记**会影响前缀**的那几件事 —— 那是"上下文为什么变贵"的答案。
            YukiCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("上下文事件", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(10.dp))
                    if (compressed) {
                        BoardRow("压缩", "已压缩过；之后的请求带摘要")
                        BoardDivider()
                        BoardRow("影响", "压缩那一次缓存会失效，之后恢复正常")
                    } else {
                        Text("还没有压缩过。", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "聊得越长，人设与历史占得越多；接近上限时压缩可以把早期内容换成摘要。",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                        )
                    }
                }
            }


            YukiCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("账户余额", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        // ⚠️ 这句不能省：入口在会话里、数据是全账号的
                        "这是你 DeepSeek 账号的余额，不是这一段对话的。",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                    )
                    Spacer(Modifier.height(10.dp))
                    when {
                        balanceLoading -> Text("正在读取…", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
                        balanceFailed -> Text(
                            // ⚠️ 两种失败要分开说：这家**没有**这个接口 ≠ 这次没读到。
                            //    说成"暂时读不到"会让用户去查网络和密钥 —— 查一辈子也没用。
                            if (!hasBalanceApi) {
                                "这个服务商不提供余额查询。"
                            } else {
                                "暂时读不到余额。"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (hasBalanceApi) DangerRose else TextMuted,
                        )
                        balance != null -> {
                            BoardRow("可用", balance!!.summary)
                            BoardDivider()
                            BoardRow("其中赠金", balance!!.infos.joinToString(" / ") { it.granted }.ifBlank { "—" })
                            BoardDivider()
                            BoardRow("其中充值", balance!!.infos.joinToString(" / ") { it.toppedUp }.ifBlank { "—" })
                            if (!balance!!.isAvailable) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "账户已无可用余额，请求会被拒绝。",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = DangerRose,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BoardRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = TextMuted)
        Spacer(Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
    }
}

@Composable
private fun BoardDivider() = HorizontalDivider(color = FrostLine)

/**
 * **弧形仪表盘**（用户要的"仪表盘弧形圈那种"）。
 *
 * ## 为什么用 200° 的开口弧而不是整圆
 * 整圆看不出"起点在哪"，而命中率是**有方向**的量（越高越好）——
 * 开口朝下、从左下扫到右下，视觉上天然就是"表盘"。
 *
 * ⚠️ 用 `drawArc` 自己画而不是 ProgressIndicator：这里要**开口 200° + 两段底色对比**，
 * 那个组件画的是整圆，改不了开口角。
 */
@Composable
private fun GaugeArc(value: Float, centerText: String) {
    val v = value.coerceIn(0f, 1f)
    val color = when {
        v >= 0.8f -> SkyBlueDeep
        v >= 0.5f -> WarnAmber
        else -> DangerRose
    }
    Box(contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(150.dp)) {
            val stroke = 13.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            // 从左下（200°）顺时针扫 200°，开口朝下
            drawArc(
                topLeft = Offset(inset, inset),
                size = arcSize,
                startAngle = 170f,
                sweepAngle = 200f,
                useCenter = false,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
                color = FrostLine,
            )
            drawArc(
                topLeft = Offset(inset, inset),
                size = arcSize,
                startAngle = 170f,
                sweepAngle = 200f * v,
                useCenter = false,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
                color = color,
            )
        }
        Text(
            text = centerText,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
        )
    }
}

/** 图例一行：色块 + 名称 + 数值。 */
@Composable
private fun LegendRow(color: Color, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(7.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = TextMuted)
        Spacer(Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.labelMedium, color = TextPrimary)
    }
}

/**
 * **环形图**：看「占比」。
 *
 * ⚠️ 每段末尾留 2° 空隙：不留的话相邻两段会糊成一片，看不出分界。
 * ⚠️ 全是 0 时用 `coerceAtLeast` 兜住，避免除零。
 */
@Composable
private fun DonutChart(slices: List<Pair<Float, Color>>) {
    val total = slices.sumOf { it.first.toDouble() }.toFloat().coerceAtLeast(0.0001f)
    Box(contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(92.dp)) {
            val stroke = 16.dp.toPx()
            var start = -90f
            slices.forEach { (v, c) ->
                val sweep = 360f * (v / total)
                if (sweep > 0f) {
                    drawArc(
                        color = c,
                        startAngle = start,
                        sweepAngle = (sweep - 2f).coerceAtLeast(0.5f),
                        useCenter = false,
                        topLeft = Offset(stroke / 2, stroke / 2),
                        size = Size(size.width - stroke, size.height - stroke),
                        style = Stroke(width = stroke, cap = StrokeCap.Butt),
                    )
                }
                start += sweep
            }
        }
    }
}

/** **堆叠条**：一眼看出上下文被谁占着。 */
@Composable
private fun StackedBar(parts: List<Triple<String, Int, Color>>) {
    val total = parts.sumOf { it.second }.coerceAtLeast(1)
    Row(
        Modifier
            .fillMaxWidth()
            .height(18.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(FrostLine),
    ) {
        parts.forEach { (_, v, c) ->
            if (v > 0) {
                Box(Modifier.weight(v.toFloat() / total).fillMaxHeight().background(c))
            }
        }
    }
}
