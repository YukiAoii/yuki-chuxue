package ai.yuki.chuxue.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.yuki.chuxue.ui.icon.YukiIcons
import ai.yuki.chuxue.ui.theme.DangerRose
import ai.yuki.chuxue.ui.theme.SuccessMint
import ai.yuki.chuxue.ui.theme.TextSubtle
import ai.yuki.chuxue.ui.theme.WarnAmber
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 灵动岛式临时提示（用户 2026-09-27 要求）。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * 为什么要有它
 * ═══════════════════════════════════════════════════════════════════════════
 * 之前各页面的临时提示是**各写各的**：聊天页在输入框上方铺一条 Banner、
 * 对话框在内部显示红字、设置页又是另一种样式。同一个"出错了"，
 * 在不同界面上长得完全不同 —— 用户要学三遍。
 *
 * 现在收敛到一处：**顶部悬浮的一枚胶囊**，出现 → 停留 → 淡出。
 * 它不占布局（浮在所有界面之上）、不打断操作、位置固定（用户知道去哪看）。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * 位置：**顶部居中**（用户明确排除了底部与侧边）
 * ═══════════════════════════════════════════════════════════════════════════
 * 选顶部是因为三条：
 *   ① 用户的视线在**输入区与内容之间**来回，顶部是唯一"顺带能扫到"的位置；
 *   ② 底部已经有输入框与悬浮导航栏（再挤一条会互相遮挡）；
 *   ③ 侧边会挡住消息气泡。
 * 用 `statusBarsPadding` 让它落在状态栏之下 —— 否则会被系统图标盖住。
 *
 * ## 与"固定提示"的区别（这条很重要）
 * 用户的话是「所有的**临时性**提示都路由到灵动岛」。
 * 所以**固定说明**（如"你的对话只存在本机"这种常驻解释）仍然留在页面里 ——
 * 它不会被自动关掉，放进会自动消失的胶囊里反而看不见。
 */
object Island {

    enum class Kind { Info, Ok, Warn, Error }

    data class Msg(
        /** 每次显示都换一个 id —— 同一条文案连点两次也要重新播一遍动画 */
        val id: Long,
        val text: String,
        val kind: Kind,
    )

    private val _current = MutableStateFlow<Msg?>(null)
    val current: StateFlow<Msg?> = _current.asStateFlow()

    private var counter = 0L

    fun show(text: String, kind: Kind = Kind.Info) {
        if (text.isBlank()) return
        _current.value = Msg(++counter, text, kind)
    }

    fun ok(text: String) = show(text, Kind.Ok)
    fun warn(text: String) = show(text, Kind.Warn)
    fun error(text: String) = show(text, Kind.Error)

    fun dismiss() { _current.value = null }

    /** 停留时长。够读完一句话，又不至于挡着不走。 */
    const val DURATION_MS = 2600L
}

/**
 * 提示宿主 —— 挂在**根布局最上层**（`MainActivity` 的 `Box` 里，NavHost 之后）。
 *
 * 放在根上的理由：提示必须能跨页面存活。挂在某个页面里的话，
 * 用户在提示消失前切页，它就被一起销毁了。
 */
@Composable
fun DynamicIslandHost(modifier: Modifier = Modifier) {
    val msg by Island.current.collectAsStateWithLifecycle()

    // 退场动画期间仍然需要内容 —— `msg` 在 `visible` 变 false 的那一刻已经是 null，
    // 直接用它会被渲染成空、退场时闪一下。所以留一份"显示用"的副本。
    //
    // ⚠️ 由 `LaunchedEffect` 更新，而**不是**在组合函数体里赋值 ——
    // 后者是副作用，会在重组时反复触发。也不用文件级的可变变量（那是全局状态，
    // 多个宿主实例会互相干扰）。
    var shown by remember { mutableStateOf<Island.Msg?>(null) }
    LaunchedEffect(msg) { if (msg != null) shown = msg }

    LaunchedEffect(msg?.id) {
        if (msg != null) {
            delay(Island.DURATION_MS)
            // 只有"还是这一条"时才收 —— 否则会把刚弹出来的新提示一起关掉
            if (Island.current.value?.id == msg?.id) Island.dismiss()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        AnimatedVisibility(
            visible = msg != null,
            enter = slideInVertically(tween(240)) { -it } + fadeIn(tween(240)),
            exit = slideOutVertically(tween(200)) { -it } + fadeOut(tween(200)),
        ) {
            shown?.let { IslandPill(it) { Island.dismiss() } }
        }
    }
}

@Composable
private fun IslandPill(msg: Island.Msg, onDismiss: () -> Unit) {
    val (accent, icon) = when (msg.kind) {
        Island.Kind.Info -> TextSubtle to YukiIcons.Info
        Island.Kind.Ok -> SuccessMint to YukiIcons.Snowflake
        Island.Kind.Warn -> WarnAmber to YukiIcons.Warning
        Island.Kind.Error -> DangerRose to YukiIcons.Warning
    }

    Row(
        modifier = Modifier
            .padding(top = 8.dp)
            .widthIn(max = 340.dp)
            // 深色胶囊 + 浅色字：与页面的浅色卡片形成对比，一眼就知道"这是浮层、
            // 不是页面的一部分"。灵动岛的语言就是"深色、悬浮、短暂"。
            .shadow(
                elevation = 12.dp,
                shape = RoundedCornerShape(50),
                ambientColor = Color.Black.copy(alpha = 0.18f),
                spotColor = Color.Black.copy(alpha = 0.24f),
            )
            .clip(RoundedCornerShape(50))
            .background(IslandBg)
            .clickable(onClick = onDismiss)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = msg.text,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** 胶囊底色：近黑的蓝调，而不是纯灰 —— 与品牌色同源。 */
private val IslandBg = Color(0xFF161C2B)
