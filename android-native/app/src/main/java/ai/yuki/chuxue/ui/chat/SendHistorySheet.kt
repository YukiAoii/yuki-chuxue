package ai.yuki.chuxue.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.yuki.chuxue.data.SendHistory
import ai.yuki.chuxue.ui.theme.SnowWhite
import ai.yuki.chuxue.ui.theme.TextMuted
import ai.yuki.chuxue.ui.theme.TextPrimary

/**
 * 「发过的话」抽屉（v0.61.21，⑤-A 第 7 条 / 方案 A）。
 *
 * ## 为什么是"填进输入框"而不是"直接发"
 * 用户找一句旧话，多半是要**改一改再发**（换个称呼、补一句）。直接发出去意味着
 * 一旦点错就给 Ta 发了一句不合时宜的话，而且这条进了请求体就收不回来。
 * 填进输入框还留了一个"反悔"的机会 —— 那里本来就有草稿机制。
 *
 * ## 搜索是纯函数
 * 打分口径在 [SendHistory.search]（前缀 +10、词命中 +5），这里只管画。
 * 所以"搜出来的顺序对不对"是单测管的事，不靠肉眼看界面。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendHistorySheet(
    history: List<String>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    var keyword by remember { mutableStateOf("") }
    val shown = remember(history, keyword) { SendHistory.search(history, keyword) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SnowWhite,
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                text = "发过的话",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp),
            )
            Text(
                text = if (history.isEmpty()) {
                    "还没有发过话。"
                } else {
                    "共 ${history.size} 条。点一条会填进输入框，不会直接发出去。"
                },
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 12.dp),
            )

            if (history.isEmpty()) return@Column

            // 搜索框：**超过 5 条才出现** —— 与模型选择那个抽屉同一条规矩
            //（一屏能看全的东西不需要找）。
            if (history.size > 5) {
                OutlinedTextField(
                    value = keyword,
                    onValueChange = { keyword = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    placeholder = { Text("搜索说过的内容") },
                )
                Spacer(Modifier.height(8.dp))
            }

            if (shown.isEmpty()) {
                Text(
                    text = "没有匹配「$keyword」的",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                )
                return@Column
            }

            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                items(shown, key = { it }) { text ->
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary,
                        // 长话最多两行 —— 这里是在"翻旧账"，不是重读全文
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(text) }
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                    )
                }
            }
        }
    }
}
