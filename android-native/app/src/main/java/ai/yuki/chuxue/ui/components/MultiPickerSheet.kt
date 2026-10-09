package ai.yuki.chuxue.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ai.yuki.chuxue.ui.theme.SnowWhite
import ai.yuki.chuxue.ui.theme.SkyBlueDeep
import ai.yuki.chuxue.ui.theme.TextMuted
import ai.yuki.chuxue.ui.theme.TextPrimary

/**
 * 一条**可勾选的条目**（给 [MultiPickerSheet] 用）。
 *
 * ⚠️ 副标题不是装饰：会话列表里可能有五个"新的对话"、人设列表里可能有同名角色，
 * 只给标题的话用户根本认不出该勾哪个。谁提供列表，谁负责把"怎么区分它们"放进来。
 */
data class PickItem(
    val id: String,
    val title: String,
    /** 那一行小字（消息数 / 属于谁 / 记录时间…）。可为空。 */
    val subtitle: String = "",
)

/**
 * **通用多选器**（v0.54.0，用户要求：导出聊天记录的那个弹窗要通用给所有备份功能）。
 *
 * ## 为什么做成一个通用组件而不是三份
 * 聊天记录 / 人设 / 记忆库都要"列出可选项 → 勾选 → 确认"，
 * 三份实现必然漂移（搜索阈值、空态文案、全选行为各写各的）。
 * 这里只认 [PickItem]，谁用谁把数据转成它。
 *
 * ## ⚠️ 它**只在软件内渲染**（用户明确要求"不是系统级"）
 * 半屏抽屉，不弹系统选择器 —— 系统选择器只能单选、也拿不到我们想显示的信息
 *（消息数、属于谁）。
 *
 * ## ⚠️ 搜索框**超过 [searchThreshold] 条才出现**
 * 沿用 `ModelPickerSheet` 的规矩（那里是 5 个）：一屏能看全的列表给搜索框是纯噪声。
 * 用户这里要求的是"超过 10 个会话支持搜索"。
 *
 * ## ⚠️ 确认按钮在**底部且常驻**
 * 勾选状态是这一层的局部状态，用户看不见"已经勾了几个"就会反复上下滑。
 * 所以底部那行同时承担两件事：显示已选条数 + 提供确认。
 *
 * ⚠️ 渲染未经真机验证（本机无设备）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiPickerSheet(
    /** 标题，如「导出聊天记录」 */
    title: String,
    /** 一句话说明这一步在做什么 */
    hint: String,
    items: List<PickItem>,
    /** 超过几条才给搜索框 */
    searchThreshold: Int = 10,
    /** 确认按钮上的字 */
    confirmText: String = "下一步",
    onConfirm: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    var keyword by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf(setOf<String>()) }

    val shown = remember(items, keyword) {
        if (keyword.isBlank()) items
        else items.filter {
            it.title.contains(keyword, ignoreCase = true) ||
                it.subtitle.contains(keyword, ignoreCase = true)
        }
    }
    val allPicked = items.isNotEmpty() && picked.size == items.size

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SnowWhite,
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 2.dp),
            )
            Text(
                text = hint,
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
            )

            if (items.isEmpty()) {
                // 空态说清"现在是空的"，而不是留一块白板让人怀疑加载失败
                Text(
                    text = "这里还没有可以选的东西。",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
                )
                return@Column
            }

            Spacer(Modifier.height(10.dp))

            // ── 全选 / 清空 ──
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { picked = if (allPicked) emptySet() else items.map { it.id }.toSet() }) {
                    Text(if (allPicked) "清空" else "全选", color = SkyBlueDeep)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = "共 ${items.size} 项",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }

            // ── 搜索框：超过阈值才出现（见类注释）──
            if (items.size > searchThreshold) {
                OutlinedTextField(
                    value = keyword,
                    onValueChange = { keyword = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    placeholder = { Text("搜索（共 ${items.size} 项）") },
                )
                Spacer(Modifier.height(6.dp))
            }

            if (shown.isEmpty()) {
                Text(
                    text = "没有匹配「$keyword」的条目",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                )
                return@Column
            }

            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 340.dp)) {
                items(shown, key = { it.id }) { item ->
                    val on = item.id in picked
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                picked = if (on) picked - item.id else picked + item.id
                            }
                            .padding(start = 12.dp, end = 20.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = on, onCheckedChange = null)
                        Spacer(Modifier.size(6.dp))
                        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                            Text(
                                text = item.title.ifBlank { "未命名" },
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (on) SkyBlueDeep else TextPrimary,
                                maxLines = 1,
                            )
                            if (item.subtitle.isNotBlank()) {
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = item.subtitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextMuted,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 20.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }

            // ── 底部：已选条数 + 确认（常驻，见类注释）──
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = if (picked.isEmpty()) "还没选" else "已选 ${picked.size} 项",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (picked.isEmpty()) TextMuted else SkyBlueDeep,
                    modifier = Modifier.padding(start = 8.dp),
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("取消", color = TextMuted) }
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (picked.isEmpty()) MaterialTheme.colorScheme.surfaceVariant else SkyBlueDeep)
                        .clickable(enabled = picked.isNotEmpty()) {
                            onConfirm(items.filter { it.id in picked }.map { it.id })
                        }
                        .padding(horizontal = 18.dp, vertical = 9.dp),
                ) {
                    Text(
                        text = confirmText,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (picked.isEmpty()) TextMuted else SnowWhite,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}
