package ai.yuki.chuxue.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import ai.yuki.chuxue.ui.theme.FieldCorner

/**
 * 自定义输入框（开发文档 §29.5）。
 *
 * ## 为什么不用 `OutlinedTextField`
 * 文档 §28.1 / §47.3 / §48.3 要求「所有输入框、对话框、选择器均为 Compose 自定义」——
 * Material 的默认输入框是「标准 Android 应用」的脸：粗细一致的方框轮廓 + 悬浮标签 +
 * 右下角计数器。放在水彩底上会立刻显得是两个世界的东西。
 *
 * 这里保留 `BasicTextField` 的内核（输入法、选区、密码变换全部免费且正确），
 * 只接管**外观**：12dp 圆角、`surfaceVariant` 底（浅色下即色板里的 `FieldFill`）、
 * 1dp 描边、标签在上方而不是悬浮。
 *
 * ⚠️ 错误态的底色/描边走 `colorScheme.error*`，因此深色模式自动跟随 —— 别改成硬编码色。
 */
@Composable
fun YukiTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String = "",
    isError: Boolean = false,
    isPassword: Boolean = false,
    minLines: Int = 1,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp),
        )

        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            minLines = minLines,
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
            visualTransformation = if (isPassword) {
                PasswordVisualTransformation()
            } else {
                VisualTransformation.None
            },
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(FieldCorner))
                // ⚠️ 底色必须是 `surfaceVariant`，不能是「表面色 + 低 alpha」。
                //
                // 原值 `surface.copy(alpha = 0.8f)` 是水彩 / 液态玻璃时代的写法：
                // 那时输入框浮在浅灰**页面**底上，白 80% 还看得见边界。扁平风改版后
                // （v0.26.0）字段被放进**纯白卡片**里，白叠白之后输入区完全读不出来 ——
                // 只剩一条极浅的描边，看起来像「标签下面直接是一片白」。
                // 用 Edge headless 把同一段 CSS 渲染成图对照过：白 0.8 那一版的填充
                // 与卡片同色（空框），`#F0F2F5` 那一版才有明确的输入区。
                //
                // 选 `surfaceVariant` 而不硬编码 `FieldFill`：浅色主题下
                // `surfaceVariant` **就是** `FieldFill`（#F0F2F5，见 `Color.kt` / `Theme.kt`），
                // 而深色下它会自动切到 `SurfaceVariantDark` —— 硬编码浅灰会在深色模式里
                // 变成一块发光的白板。
                .background(
                    if (isError) {
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                )
                .border(
                    width = 1.dp,
                    color = if (isError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                    },
                    shape = RoundedCornerShape(FieldCorner),
                )
                .padding(12.dp),
            decorationBox = { innerTextField ->
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                }
                innerTextField()
            },
        )
    }
}
