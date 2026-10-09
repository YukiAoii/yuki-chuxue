package ai.yuki.chuxue.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ai.yuki.chuxue.data.FEEDBACK_CONTACT_MAX
import ai.yuki.chuxue.data.FEEDBACK_CONTENT_MAX
import ai.yuki.chuxue.data.FeedbackApi
import ai.yuki.chuxue.data.feedbackValidationError
import ai.yuki.chuxue.ui.components.Island
import ai.yuki.chuxue.ui.components.YukiTextField
import ai.yuki.chuxue.ui.icon.YukiIcons
import ai.yuki.chuxue.ui.theme.BrandBlue
import ai.yuki.chuxue.ui.theme.BrandBlueDeep
import ai.yuki.chuxue.ui.theme.CardGap
import ai.yuki.chuxue.ui.theme.SnowWhite
import ai.yuki.chuxue.ui.theme.TextMuted
import ai.yuki.chuxue.ui.theme.TextPrimary
import kotlinx.coroutines.launch

/** 反馈的三种类型（值是后端认的英文，标签是给用户看的）。 */
private val FEEDBACK_KINDS = listOf(
    "bug" to "出问题了",
    "idea" to "想要新功能",
    "other" to "别的",
)

/**
 * **说点什么** —— 反馈页（v0.57.0，用户要求「「我的」加反馈入口，对接后端」）。
 *
 * ⚠️ **不需要登录**：后端 `/api/feedback` 刻意不校验令牌 —— 不注册也能用的应用，
 *    如果反馈要求登录，那这条渠道对大多数用户等于不存在。
 *    挡滥用靠后端**同 IP 限流**，不靠登录。
 *
 * ⚠️ 与官网的反馈表单打的是**同一个后端接口**（`POST /api/feedback`）：
 *    两个入口、一份数据，后台看得到全部。
 *
 * ## 为什么本地也校验一遍
 * 后端会拦（空内容 / 超长 / 限流），但那是**一次往返之后**才知道；
 * 本地先拦能立刻告诉用户，不用等网络。两边口径一致（见 [feedbackValidationError]）。
 *
 * ⚠️ 渲染未经真机验证（本机无设备）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedbackScreen(
    /** 已登录时的账号（邮箱/用户名）—— 用来**预填**联系方式，留空则不填。 */
    prefillContact: String = "",
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    var kind by remember { mutableStateOf(FEEDBACK_KINDS.first().first) }
    var content by remember { mutableStateOf("") }
    var contact by remember { mutableStateOf(prefillContact) }
    var sending by remember { mutableStateOf(false) }

    fun submit() {
        val local = feedbackValidationError(content, contact)
        if (local != null) {
            Island.warn(local)
            return
        }
        if (sending) return
        sending = true
        scope.launch {
            when (val r = FeedbackApi.send(kind, content, contact)) {
                is FeedbackApi.Result.Ok -> {
                    sending = false
                    Island.ok("收到了，谢谢你")
                    onBack()
                }
                is FeedbackApi.Result.Fail -> {
                    sending = false
                    // 后端把原因写在 detail（限流/太长），照原样给用户看
                    Island.error(r.message)
                }
            }
        }
    }

    val canSend = content.isNotBlank() && !sending

    Scaffold(
        containerColor = SnowWhite,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SnowWhite,
                    titleContentColor = TextPrimary,
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(YukiIcons.Back, contentDescription = "返回")
                    }
                },
                title = { Text("说点什么", style = MaterialTheme.typography.titleMedium) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(CardGap),
        ) {
            CategoryTitle("和我们说话", "用着别扭、缺了什么、或者只是想说声谢谢 —— 都写在这里")

            SettingsGroup("这是什么") {
                ChoiceLine(
                    title = "类型",
                    desc = "",
                    options = FEEDBACK_KINDS,
                    selected = kind,
                    onSelect = { kind = it },
                )
            }

            SettingsGroup("内容") {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    YukiTextField(
                        value = content,
                        // 硬截断到上限：与其让用户写到 700 字再被拒，不如写不进去
                        onValueChange = { content = it.take(FEEDBACK_CONTENT_MAX) },
                        label = "想法 / 问题",
                        placeholder = "越具体越好：你做了什么、看到了什么、期望是什么。",
                        minLines = 6,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth()) {
                        Spacer(Modifier.weight(1f))
                        Text(
                            "${content.length} / $FEEDBACK_CONTENT_MAX",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                        )
                    }
                }
            }

            SettingsGroup("怎么找你（可不填）") {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    YukiTextField(
                        value = contact,
                        onValueChange = { contact = it.take(FEEDBACK_CONTACT_MAX) },
                        label = "联系方式",
                        placeholder = "邮箱 / QQ / 微信，随便一个能联系上的",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        // 说清"为什么问"：不留也能发，留了才可能收到回音
                        "不留也能发。留一个的话，我们能回你一句。",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                    )
                }
            }

            // 发送键：满宽渐变 —— 与「进对话」那个主按钮同款（这一页只有这一个动作）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        if (canSend) {
                            Brush.horizontalGradient(listOf(BrandBlue, BrandBlueDeep))
                        } else {
                            Brush.horizontalGradient(
                                listOf(
                                    BrandBlue.copy(alpha = 0.35f),
                                    BrandBlueDeep.copy(alpha = 0.35f),
                                ),
                            )
                        },
                    )
                    .clickable(enabled = canSend) { submit() }
                    .padding(vertical = 15.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (sending) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = SnowWhite,
                    )
                    Spacer(Modifier.size(8.dp))
                }
                Text(
                    text = if (sending) "正在发送…" else "发出去",
                    style = MaterialTheme.typography.bodyLarge,
                    color = SnowWhite,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Text(
                text = "反馈里不要写你的 API Key —— 我们不需要它，也不需要你的聊天内容。",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                modifier = Modifier.padding(horizontal = 4.dp),
            )

            Spacer(Modifier.height(16.dp))
        }
    }
}
