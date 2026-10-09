package ai.yuki.chuxue.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ai.yuki.chuxue.ui.theme.FrostLine
import ai.yuki.chuxue.ui.theme.SkyBlueDeep

/**
 * 设置类页面共用的分组骨架：**一个分组 = 一张 [YukiCard]**。
 *
 * ## 为什么它是一个共享组件，而不是各页各写一遍
 * 这条结构最初是给「对话设置页」定的（用户反馈那一页「布局很乱」）。
 * 根因是每一项各自铺一块浅色底、彼此紧贴、且**顶到屏幕两边**：
 * 组标题有 16dp 缩进而卡片没有 → 标题与卡片左边缘错位；
 * 几十个浅色块连成一片 → 扁平风赖以为生的「留白 + 阴影分层」完全失效。
 *
 * 现在人设编辑页也要这套结构。抄第二遍的代价不是多打几十行字，
 * 而是**将来调一次 CardGap 或标题色，会有两个地方要同步改，而漏改的那处不会报错** ——
 * 只是两页悄悄长得不一样（本项目在 `ImageSourceButtons` 上已经栽过同一类事）。
 *
 * ## 规则（改了这里就是改了所有设置页）
 * - 组内**不画行背景**，只用 [LineDivider] 断行 —— 层次来自「卡片浮在纯色背景上」；
 * - 组与组之间用 `Arrangement.spacedBy(CardGap)`（由调用方的 Column 负责），
 *   卡片一挤，那 2dp 阴影就失去意义，整页糊成一片。
 *
 * ⚠️ 渲染未经真机验证（本机无 adb / emulator）。
 */
@Composable
fun SettingsGroup(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = SkyBlueDeep,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )
        YukiCard(Modifier.fillMaxWidth()) {
            Column(content = content)
        }
    }
}

/**
 * 组内分隔线。左侧缩进 16dp：与行内文字对齐，不切到卡片边缘。
 *
 * 缩进是刻意的 —— 一条从卡片最左画到最右的分隔线会把卡片切成两半，
 * 缩进后它才读作「同一个容器里的两行」。
 */
@Composable
fun LineDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 16.dp),
        color = FrostLine.copy(alpha = 0.7f),
    )
}
