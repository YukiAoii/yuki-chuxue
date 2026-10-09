package ai.yuki.chuxue.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.yuki.chuxue.ui.theme.TextPrimary

/** 两份文书。 */
enum class LegalKind { TERMS, PRIVACY }

/** 两份文书对外声称的「最后更新」。改条款时**记得改这里** —— 界面上会照实显示。 */
private const val UPDATED_AT = "2026-10-02"

/** 纸张内的大标题。 */
private val LegalKind.docTitle: String get() = if (this == LegalKind.TERMS) "使用协议" else "隐私政策"

/**
 * **使用协议 / 隐私政策 详情页**（v0.60.0）。
 *
 * 从「首次打开的选择页」那行协议里点进来，看完可以返回。
 *
 * ## ⚠️ 正文需要你（用户）过一遍
 * 项目里原先**没有任何协议正文**（grep 过：代码、官网静态页、后端都没有），
 * 所以这段文字是**我按这个 App 的实际行为写的**，不是法律意见：
 * - 写的都是能对得上代码的事实（本地优先、BYOK 直连、人设端到端加密、
 *   服务器只存账号与可关闭的统计）
 * - 但**条款是否够用、要不要补免责/管辖**之类，得你自己定
 * - 要改就改下面的常量，界面会跟着变
 */
@Composable
fun LegalScreen(kind: LegalKind, onBack: () -> Unit) {
    val title = if (kind == LegalKind.TERMS) "使用协议" else "隐私政策"
    val sections = if (kind == LegalKind.TERMS) TERMS_SECTIONS else PRIVACY_SECTIONS

    AuthBackdrop {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 26.dp),
        ) {
            Spacer(Modifier.height(14.dp))
            // 顶部：返回 + 标题（设计稿实测：圆钮 34×34 @x=26、标题 16px/700）
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color(0xCCFFFFFF))
                        .clickable(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "‹",
                        fontSize = 16.sp,
                        color = Color(0xFF5A6472),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.W700,
                    color = Color(0xFF171717),
                )
            }

            Spacer(Modifier.height(14.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xB8FFFFFF))
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 17.dp, vertical = 19.dp),
            ) {
                // 文档头：大字标题 + 更新日期 + 分隔线（原来直接就是第一条，太突兀）
                Text(kind.docTitle, fontSize = 22.sp, fontWeight = FontWeight(750), color = Color(0xFF111111))
                Spacer(Modifier.height(6.dp))
                Text("最后更新：$UPDATED_AT", fontSize = 11.5.sp, color = Color(0xFF9AA1B0))
                Spacer(Modifier.height(14.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Color(0x14203A63)),
                )
                Spacer(Modifier.height(4.dp))
                // 一条条排下来，每条前面一个淡品牌色序号 —— 长文里比纯小标题好定位
                sections.forEachIndexed { idx, (heading, body) ->
                    Section(index = idx + 1, heading = heading, body = body)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "读完可以按左上角返回。",
                    fontSize = 11.5.sp,
                    color = Color(0xFF9AA1B0),
                )
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}

/** 一个小节：标题 + 正文（设计稿实测：小节 14px/700、正文 12.5px 行高 1.85）。 */
@Composable
private fun ColumnScope.Section(index: Int, heading: String, body: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // 序号：淡品牌色小胶囊，比"一、二、"更容易一眼扫到
        Box(
            Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(Color(0x1A2F6BFF)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = index.toString().padStart(2, '0'),
                fontSize = 10.sp,
                fontWeight = FontWeight(700),
                color = Color(0xFF2F6BFF),
            )
        }
        Spacer(Modifier.width(9.dp))
        Text(
            text = heading.removePrefix("一、").removePrefix("二、").removePrefix("三、")
                .removePrefix("四、").removePrefix("五、").removePrefix("六、"),
            fontSize = 14.sp,
            fontWeight = FontWeight(700),
            color = Color(0xFF111111),
        )
    }
    Spacer(Modifier.height(7.dp))
    Text(
        text = body,
        fontSize = 12.5.sp,
        lineHeight = 23.sp,
        color = Color(0xFF4A5260),
        modifier = Modifier.padding(start = 29.dp),
    )
    Spacer(Modifier.height(14.dp))
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Color(0x0A203A63)),
    )
    Spacer(Modifier.height(14.dp))
}

/** 使用协议正文。改这里 = 改界面。 */
private val TERMS_SECTIONS = listOf(
    "一、这份协议是什么" to
        "你正在使用「Yuki 初雪」（下称\"本应用\"）。继续使用即表示你接受本协议。",
    "二、你的账号" to
        "本应用需要账号才能使用。你需要提供昵称、邮箱与密码；请妥善保管密码，" +
        "并记住你的 UID —— 它是找回账号的依据。",
    "三、你的内容" to
        "对话、人设、记忆保存在你的手机上。若你配置了自己的模型服务商 API Key，" +
        "对话内容直接发往该服务商，不经过本应用的服务器。",
    "四、请勿做的事" to
        "不要用本应用生成违法内容，不要攻击或滥用服务器，不要尝试越权访问他人数据。",
    "五、服务可能变化" to
        "本应用仍在开发中，功能可能调整或中断。我们会尽量提前说明。",
    "六、联系我们" to
        "有问题请通过应用内「说点什么」反馈。",
)

/** 隐私政策正文。改这里 = 改界面。 */
private val PRIVACY_SECTIONS = listOf(
    "一、什么留在你手机上" to
        "对话记录、人设、记忆、设置、以及你填的 API Key —— 全部存在本机。" +
        "这些内容不会上传到我们的服务器。",
    "二、什么会传到服务器" to
        "只有这些：账号信息（昵称、邮箱、密码的加盐哈希、UID、头像）、" +
        "你主动提交的反馈、以及可在设置里关闭的设备与用量统计。",
    "三、人设同步是端到端加密的" to
        "换设备同步人设时，内容在你的手机上加密后才上传，服务器只存放密文，看不到明文。",
    "四、对话内容去哪了" to
        "本应用不代理模型请求：对话直接发往你自己配置的服务商，适用那家的隐私政策。",
    "五、我们不做什么" to
        "不卖你的数据，不投放广告，不读取你的对话内容。",
    "六、你可以要求删除" to
        "想注销账号或删除服务器上的数据，请通过应用内反馈联系我们。",
)
