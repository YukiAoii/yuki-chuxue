package ai.yuki.chuxue.ui.chat

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ai.yuki.chuxue.data.ProviderGroup
import ai.yuki.chuxue.data.ProviderGroups
import ai.yuki.chuxue.ui.theme.SkyBlueDeep
import ai.yuki.chuxue.ui.theme.SnowWhite
import ai.yuki.chuxue.ui.theme.TextMuted
import ai.yuki.chuxue.ui.theme.TextPrimary

/**
 * 「+」的第一层：这一下想干什么（v0.51.0）。
 *
 * ## 为什么要有这一层，而不是让「+」直接开模型列表
 * 「+」原本就是**发图**的入口，直接改掉会让老用户"按下去发现相册不见了"。
 * 而模型选择又确实需要挂在它上面（用户明确要求）。两层是最小的代价：
 * 一下打开两个选项，各自归位。
 *
 * ⚠️ 两项都写成"动作"（发送图片 / 选择模型），不写成"设置项"——
 * 它们在语义上都是"这一下要做什么"，而不是"某个开关的值"。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachMenuSheet(
    onPickImage: () -> Unit,
    onPickModel: () -> Unit,
    /** 「发过的话」（v0.61.21）—— 跨会话翻自己以前说过的话，点一条填进输入框。 */
    onPickHistory: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = SnowWhite,
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            AttachMenuRow("发送图片", "从相册挑一张发给Ta", onPickImage)
            HorizontalDivider(
                modifier = Modifier.padding(start = 20.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            AttachMenuRow("选择模型", "这段对话用哪个模型回答", onPickModel)
            HorizontalDivider(
                modifier = Modifier.padding(start = 20.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            AttachMenuRow("发过的话", "翻出以前说过的，改一改再发", onPickHistory)
        }
    }
}

@Composable
private fun AttachMenuRow(title: String, desc: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = desc,
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
            )
        }
    }
}

/**
 * 选模型 / 切分组（v0.51.0）—— 聊天输入框「+」打开的浮层。
 *
 * ## 它回答用户的两句话
 * - 「勾选的模型在聊天会话窗口的输入框**加号**点击之后可以选择调用
 *   这个分组下勾选了的哪个模型」
 * - 「聊天途中**可以切换**为选择的分组的模型，也可以**切换分组**然后再选择哪个模型」
 *
 * 两件事放在**同一层**里做，因为它们本来就是一件事：先定分组、再定模型。
 * 分成两个入口会让"我只是想换个模型"变成两次点击两次等待。
 *
 * ## ⚠️ 只列**勾选过的**模型，不列拉取到的全部
 * 拉取一次可能回几十上百个（含 embedding / 语音 / 旧版），全列出来没法用。
 * 想加模型去连接设置里勾 —— 那里有搜索框（超过 5 个时）。
 *
 * ## ⚠️ 上拉到半屏
 * `rememberModalBottomSheetState()` 默认就是 PartiallyExpanded 起步、可上拉展开，
 * 用户要的"上拉到半屏"由它提供，不需要自己算高度。
 *
 * ⚠️ 渲染未经真机验证（本机无设备）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerSheet(
    groups: List<ProviderGroup>,
    /** 当前**会话**选中的分组 id（null = 跟着全局） */
    selectedGroupId: String?,
    /** 当前**会话**选中的模型（null = 用分组里勾选的第一个） */
    selectedModel: String?,
    /** 全局当前分组 id —— 会话没选过时用它兜底，好让"当前"标记指对地方 */
    globalGroupId: String,
    onPick: (groupId: String, model: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()

    // 用户在浮层里临时切换的分组 —— 未点确认前不写回会话。
    // ⚠️ 初始值：会话选过就用它，否则用全局当前分组（那个才是"这次实际在用"的）
    var browsingGroupId by remember {
        mutableStateOf(
            selectedGroupId?.takeIf { id -> groups.any { it.id == id } }
                ?: globalGroupId.takeIf { id -> groups.any { it.id == id } }
                ?: groups.firstOrNull()?.id.orEmpty(),
        )
    }
    var keyword by remember { mutableStateOf("") }

    val browsing = groups.firstOrNull { it.id == browsingGroupId }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SnowWhite,
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                text = "选择模型",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 4.dp),
            )
            Text(
                text = "这段对话之后用它。想换可选项，去「连接设置 → 分组」里勾。",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
            )

            // ── 分组切换（只有一个分组时不显示，免得白占一行）──
            if (groups.size > 1) {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    groups.forEach { g ->
                        val on = g.id == browsingGroupId
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(50))
                                .background(
                                    if (on) SkyBlueDeep.copy(alpha = 0.12f)
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                )
                                .clickable {
                                    browsingGroupId = g.id
                                    keyword = ""   // 换分组就清搜索词 —— 上一个分组的词在这里没意义
                                }
                                .padding(horizontal = 14.dp, vertical = 7.dp),
                        ) {
                            Text(
                                text = g.name,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (on) SkyBlueDeep else TextMuted,
                                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            val all = browsing?.checkedModels.orEmpty()

            if (browsing == null || all.isEmpty()) {
                // 空态要说清**去哪儿加**，而不是只说"没有"
                Text(
                    text = if (browsing == null) {
                        "还没有连接分组。先去「设置 → 连接设置」建一个。"
                    } else {
                        "这个分组还没勾选模型。去「连接设置 → 这个分组」里勾几个吧。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                )
                return@Column
            }

            // ── 搜索框：**超过 5 个才出现**（用户明确要求）──
            // 少于 5 个时给搜索框是纯粹的噪声 —— 一屏能看全的东西不需要找。
            if (all.size > 5) {
                OutlinedTextField(
                    value = keyword,
                    onValueChange = { keyword = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    placeholder = { Text("搜索模型（共 ${all.size} 个）") },
                )
                Spacer(Modifier.height(8.dp))
            }

            val shown = remember(all, keyword, browsing) {
                if (keyword.isBlank()) {
                    all
                } else {
                    // ⚠️ 显示名和真名**都要能搜到** —— 用户看到的是显示名，
                    //    按他看到的字搜却搜不到，是最容易被骂的那种小坑。
                    all.filter {
                        it.contains(keyword, ignoreCase = true) ||
                            ProviderGroups.labelOf(browsing, it).contains(keyword, ignoreCase = true)
                    }
                }
            }

            if (shown.isEmpty()) {
                Text(
                    text = "没有匹配「$keyword」的模型",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                )
                return@Column
            }

            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                items(shown, key = { it }) { name ->
                    // "当前" 标记：只有**这个分组的这个模型**同时是会话在用的那个才算
                    val isCurrent = browsingGroupId == (selectedGroupId ?: globalGroupId) &&
                        name == (selectedModel ?: all.firstOrNull())

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(browsingGroupId, name) }
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            // ⚠️ 画的是**显示名**（后端配的），但发出去的仍是 `name`（真名）——
                            //    见上面 `onPick(browsingGroupId, name)`：改显示名不会动请求。
                            text = ProviderGroups.labelOf(browsing, name),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isCurrent) SkyBlueDeep else TextPrimary,
                            fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.weight(1f),
                        )
                        if (isCurrent) {
                            Text(
                                "当前",
                                style = MaterialTheme.typography.labelSmall,
                                color = SkyBlueDeep,
                            )
                        }
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 20.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            // 「跟随全局」只在会话**真的**选过东西时才有意义 ——
            // 没选过时它和"点第一个"没有区别，摆出来只会让人以为漏了什么
            if (selectedGroupId != null || selectedModel != null) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick("", "") }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                ) {
                    Text(
                        text = "恢复为跟随全局",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextMuted,
                    )
                }
            }
        }
    }
}
