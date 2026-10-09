package ai.yuki.chuxue.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ai.yuki.chuxue.ui.theme.BrandBlue
import ai.yuki.chuxue.ui.theme.BrandBlueSoft
import ai.yuki.chuxue.ui.theme.SkyBlueDeep
import ai.yuki.chuxue.ui.theme.SnowWhite
import ai.yuki.chuxue.ui.theme.TextMuted
import ai.yuki.chuxue.ui.theme.TextPrimary

/**
 * 空态提示（列表 / 页面没有内容时）—— 全 App 共用一份。
 *
 * ## 为什么提到 components
 * 原先只有 `MainTabs.kt` 里一个私有版本，人设列表又手写了另一个"裸文字"版本 ——
 * 同一个东西两处各画一份必然漂移（这条教训项目里已有：聊天背景
 * `components/ChatBackgroundSurface.kt` 就是这么收口成一份的）。
 *
 * ## v0.61.21 美化
 * · 图标从"裸线框"改成**浅色圆底 + 主色图标** —— 裸图标在浅背景上偏弱，
 *   加一层圆底后它是空页里唯一的"点"（扁平风的层次用明度差，不叠透明度）；
 * · 颜色用项目色板常量（原来是间接的 colorScheme 映射，深浅主题一换就跑偏）；
 * · 可选**主操作按钮**：空页只写"你应该去点右上角"是说明书，直接给按钮才是出口。
 */
@Composable
internal fun EmptyHint(
    icon: ImageVector,
    title: String,
    desc: String,
    modifier: Modifier = Modifier,
    /** 主操作按钮的文字；空串 = 不显示按钮 */
    actionLabel: String = "",
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(84.dp)
                .clip(CircleShape)
                .background(BrandBlueSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = BrandBlue,
                modifier = Modifier.size(38.dp),
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            desc,
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted,
            textAlign = TextAlign.Center,
        )
        if (actionLabel.isNotBlank() && onAction != null) {
            Spacer(Modifier.height(20.dp))
            TextButton(
                onClick = onAction,
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.textButtonColors(
                    containerColor = SkyBlueDeep,
                    contentColor = SnowWhite,
                ),
                contentPadding = PaddingValues(horizontal = 22.dp, vertical = 10.dp),
            ) {
                Text(
                    actionLabel,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
