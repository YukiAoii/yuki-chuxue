package ai.yuki.chuxue.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.yuki.chuxue.data.Persona
import ai.yuki.chuxue.ui.components.YukiCard
import ai.yuki.chuxue.ui.icon.YukiIcons
import ai.yuki.chuxue.ui.theme.CardGap
import ai.yuki.chuxue.ui.theme.SnowWhite
import ai.yuki.chuxue.ui.theme.TextMuted
import ai.yuki.chuxue.ui.theme.TextPrimary

/**
 * 「记忆管理」的入口页 —— **先选一个角色，再看 Ta 记得你什么**（v0.61.36）。
 *
 * ## 为什么是"选角色"而不是旧的"选一段对话"
 * 记忆 2026-10-05 起是**人设级**（跨对话共享），不再属于单段对话。
 * 于是旧的 `MemorySessionsScreen`（先选一段对话）那一步**已退役** ——
 * 设置页是全局的、没有"当前会话"，但**可以选角色**（这符合人设级口径）。
 * 选完进入该角色的记忆页（`MemoryManageScreen(personaId, sessionId="")`，只看人设级）。
 *
 * ## 与"从人设详情进"的关系
 * 两条路都到同一个记忆页：一条是"我已经知道要管谁的记忆了"（从人设详情/长按直接进），
 * 一条是"我从设置里想挑一个看看"（本页）。本页只解决后者。
 *
 * ⚠️ 渲染未经真机验证（本机无 adb / 模拟器）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryPersonasScreen(
    personas: List<Persona>,
    onOpenPersona: (String) -> Unit,
    onBack: () -> Unit,
) {
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
                title = { Text("管理记忆", style = MaterialTheme.typography.titleMedium) },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(
                text = "记忆属于角色（跨对话共享）—— 同一个角色的每一段对话都看得到。" +
                    "选一个角色，看看 Ta 记得你什么。",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )

            if (personas.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "还没有角色",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextMuted,
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(CardGap),
                ) {
                    items(personas, key = { it.id }) { p ->
                        YukiCard(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { onOpenPersona(p.id) },
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = p.displayName,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = TextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        // 有 AI 写的简介就显示它，没有就退回备注，再没有就一句通用说明。
                                        text = p.detailSummary?.takeIf { it.isNotBlank() }
                                            ?: p.note.takeIf { it.isNotBlank() }
                                            ?: "看看 Ta 记得你什么",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextMuted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Spacer(Modifier.size(12.dp))
                                Icon(
                                    YukiIcons.ChevronRight,
                                    contentDescription = null,
                                    tint = TextMuted,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
