package ai.yuki.chuxue.ui.update

import ai.yuki.chuxue.data.ChangelogEntry
import ai.yuki.chuxue.data.UpdateApi
import ai.yuki.chuxue.ui.components.YukiDialog
import ai.yuki.chuxue.ui.theme.BrandBlue
import ai.yuki.chuxue.ui.theme.SkyBlueDeep
import ai.yuki.chuxue.ui.theme.TextMuted
import ai.yuki.chuxue.ui.theme.TextPrimary
import ai.yuki.chuxue.ui.theme.WarnAmber
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 更新日志 —— 「关于」页那个入口点开的弹窗。
 *
 * ## 为什么要有它（用户 2026-09-29 要求）
 * 在此之前，"更新日志"只在**检测到新版本**时出现一次（见 [UpdateDialog]）。
 * 用户想回看"上上个版本改了什么"没有任何入口。这个弹窗补上那件事。
 *
 * ## 数据从哪来
 * `GET /api/app/changelog`（公开、无需鉴权）—— 后端只列**装得出来**的版本，
 * 判据与更新接口同源。所以这里不会出现"能看到日志、却升不到那一版"。
 *
 * ## ⚠️ 它是"关于页自取"，不走 `UpdateViewModel`
 * 这一页是纯展示，与"要不要更新"的状态机没有耦合。
 *
 * ## v0.50.5：改成**每版一张卡**
 * 用户要求「更新日志…弹窗样式直接把（SQYU 的）搬过来用」。
 * SQYU 那条列表把每个版本画成**一张有底色的圆角卡**，最新一版单独高亮，
 * 卡与卡之间用带文字的横线分隔（"更早版本"）。照此重排 ——
 * 原来是一路平铺的文字，扫一眼分不清"哪段属于哪一版"。
 *
 * ## ⚠️ 拿不到就说拿不到
 * 网络失败拿到**空列表**，界面显示"暂时取不到"——不是错误页。
 */
@Composable
fun ChangelogDialog(onDismiss: () -> Unit) {
    var loading by remember { mutableStateOf(true) }
    var entries by remember { mutableStateOf<List<ChangelogEntry>?>(null) }

    LaunchedEffect(Unit) {
        entries = UpdateApi.changelog()
        loading = false
    }

    YukiDialog(
        title = "更新日志",
        onConfirm = onDismiss,
        onDismiss = onDismiss,
        confirmText = "知道了",
        // 只需一个出口：这一页没有"操作"，关掉就是唯一动作
        dismissText = "",
        content = {
            when {
                loading -> Text(
                    "正在加载…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMuted,
                )

                entries.isNullOrEmpty() -> Text(
                    "暂时取不到更新日志（网络不通，或服务端还没发布过版本）。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMuted,
                )

                else -> Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    entries!!.forEachIndexed { index, e ->
                        ChangelogCard(e, isLatest = index == 0)
                        if (index != entries!!.lastIndex) {
                            VersionBreakLabel(if (index == 0) "更早版本" else "历史版本")
                        }
                    }
                }
            }
        },
    )
}

/**
 * 一条版本记录 —— **一张卡**：版本徽章 + 标题 + 日期 → 正文。
 *
 * ⚠️ 正文按**行**渲染，不解析 Markdown（Compose 的 `Text` 不解析 Markdown，
 * 本项目为此修过 5 处，见 `HANDOFF.md` 坑 #13）。
 * 发布方写的 `- 第一条` 会原样显示，这是**预期**行为。
 */
@Composable
private fun ChangelogCard(e: ChangelogEntry, isLatest: Boolean) {
    // 最新版用品牌色调高亮（"这次更新的是它"）；历史版是中性底，不抢眼
    val bg = if (isLatest) BrandBlue.copy(alpha = 0.06f) else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.035f)
    val line = if (isLatest) BrandBlue.copy(alpha = 0.30f) else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.06f)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .border(0.8.dp, line, RoundedCornerShape(14.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isLatest) BrandBlue.copy(alpha = 0.15f) else SkyBlueDeep.copy(alpha = 0.12f))
                    .padding(horizontal = 7.dp, vertical = 2.dp),
            ) {
                Text(
                    text = "v${e.versionName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isLatest) BrandBlue else SkyBlueDeep,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.width(7.dp))
            if (isLatest) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(BrandBlue.copy(alpha = 0.15f))
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                ) {
                    Text(
                        text = "本次更新",
                        style = MaterialTheme.typography.labelSmall,
                        color = BrandBlue,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.width(7.dp))
            }
            if (e.force) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(WarnAmber.copy(alpha = 0.12f))
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                ) {
                    Text(
                        text = "强制",
                        style = MaterialTheme.typography.labelSmall,
                        color = WarnAmber,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            // 日期只截前 10 位（`2026-09-29T…` → `2026-09-29`）。
            // 服务端给的是 ISO 串，这一页不做解析、不换算时区 —— 只展示。
            if (e.publishedAt.length >= 10) {
                Text(
                    text = e.publishedAt.take(10),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                )
            }
        }
        Spacer(Modifier.height(7.dp))
        if (e.notes.isBlank()) {
            Text(
                text = "（这一版没有写更新说明）",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
            )
        } else {
            Text(
                text = e.notes,
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary,
            )
        }
    }
}

/** 两版之间的分隔横线 + 中间一行小字（"更早版本" / "历史版本"）。 */
@Composable
private fun VersionBreakLabel(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f)),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted.copy(alpha = 0.9f),
        )
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f)),
        )
    }
}
