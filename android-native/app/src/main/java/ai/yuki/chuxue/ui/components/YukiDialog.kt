package ai.yuki.chuxue.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import ai.yuki.chuxue.ui.theme.BrandBlue
import ai.yuki.chuxue.ui.theme.BrandViolet
import ai.yuki.chuxue.ui.theme.FlatCard
import ai.yuki.chuxue.ui.theme.SheetCorner

/**
 * 自定义对话框（开发文档 §29.5）。
 *
 * 为什么不用 `androidx.compose.material3.AlertDialog`：文档 §28.1 / §47.3 / §48.3
 * 明确要求「所有对话框、选择器、输入框均为 Compose 自定义」，不得沿用 Material 的
 * 默认观感 —— 那是「标准 Android 应用」的脸，与「初雪」的水彩气质冲突。
 *
 * 视觉：20dp 圆角、表面色底、主色实心确认键 + 文字取消键。
 */
@Composable
fun YukiDialog(
    title: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmText: String = "确认",
    dismissText: String = "取消",
    /** 破坏性操作（删除）：确认键改用错误色，避免误触 */
    destructive: Boolean = false,
    /**
     * 点弹窗外 / 按返回键时要做什么。**默认与 [onDismiss] 相同** ——
     * 多数弹窗里"取消键"与"点外面"本来就是同一个意思。
     *
     * ⚠️ 需要区分两者时才传它：比如"「继续对话」要进聊天，但误触窗外只该把弹窗关掉"。
     *    不区分的话，一次误触就会被当成"用户选了继续"。
     */
    onDismissRequest: (() -> Unit)? = null,
    content: @Composable () -> Unit = {},
) {
    Dialog(onDismissRequest = onDismissRequest ?: onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // 扁平风：**白色卡片 + 明显阴影**。弹窗要比普通卡片更"浮"，
                // 否则它会和背后的卡片糊在同一层 —— 而扁平风又没有描边可以借力。
                .shadow(20.dp, RoundedCornerShape(20.dp))
                .clip(RoundedCornerShape(20.dp))
                .background(FlatCard)
                .padding(24.dp),
        ) {
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                )

                Spacer(Modifier.height(12.dp))
                content()

                Spacer(Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(
                            dismissText,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = onConfirm,
                        shape = RoundedCornerShape(12.dp),
                        // 主操作走**品牌渐变**（用户规范：全局按钮用 #4262FF → #8E3BFF）；
                        // 破坏性操作保持实色红 —— 删除键不该长得和"确认"一样好看，
                        // 那本身就是个危险信号。
                        colors = if (destructive) {
                            ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError,
                            )
                        } else {
                            ButtonDefaults.buttonColors(
                                containerColor = Color.Transparent,
                                contentColor = Color.White,
                            )
                        },
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp),
                        modifier = if (destructive) {
                            Modifier
                        } else {
                            Modifier.background(
                                brush = Brush.linearGradient(listOf(BrandBlue, BrandViolet)),
                                shape = RoundedCornerShape(12.dp),
                            )
                        },
                    ) {
                        Text(confirmText)
                    }
                }
            }
        }
    }
}

/** 二次确认的删除对话框 —— 破坏性操作走这里，别处不要直接调 `deleteXxx`。 */
@Composable
fun YukiConfirmDeleteDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmText: String = "删除",
) {
    YukiDialog(
        title = title,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        confirmText = confirmText,
        dismissText = "取消",
        destructive = true,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
