package ai.yuki.chuxue.ui.components

import ai.yuki.chuxue.ui.theme.FieldFill
import ai.yuki.chuxue.ui.theme.FrostLine
import ai.yuki.chuxue.ui.theme.SkyBlue
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * 圆形图标按钮 —— 聊天输入区里那一排圆钮（附件 / 语音…）共用同一个圆底。
 *
 * ## 为什么它被抽到 components（而不是留在聊天页里）
 * 输入区现在有三个圆钮（加号、语音，以及将来可能有的更多），
 * 各写一遍 `.clip(CircleShape).background(FieldFill)` 与禁用态判断，
 * 迟早会有一处圆角或尺寸长得不一样 —— 而那种差异在真机上看起来就是"没对齐"。
 *
 * ## 视觉
 * 浅灰圆底 + 主色图标，靠**底色差**成形而不是靠描边（扁平风一贯的做法）。
 * ⚠️ 它坐在 [TranslucentPanel] 的**半透明**面板上，所以这个浅灰底是"看得清"的关键之一：
 * 面板会透出底层内容，圆钮自己必须是不透明的，否则图标会跟着背景一起花。
 *
 * ⚠️ 渲染未经真机验证（本机无设备）。
 */
@Composable
fun RoundIconButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** 转圈（语音正在听的时候用） */
    busy: Boolean = false,
) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(FieldFill)
            .clickable(enabled = enabled && !busy, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = SkyBlue,
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (enabled) SkyBlue else FrostLine,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
