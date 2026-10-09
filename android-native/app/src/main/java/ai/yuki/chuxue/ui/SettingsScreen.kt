package ai.yuki.chuxue.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ai.yuki.chuxue.ui.components.YukiCard
import ai.yuki.chuxue.ui.icon.YukiIcons
import ai.yuki.chuxue.ui.theme.CardGap
import ai.yuki.chuxue.ui.theme.SkyBlueDeep
import ai.yuki.chuxue.ui.theme.SnowWhite
import ai.yuki.chuxue.ui.theme.TextMuted
import ai.yuki.chuxue.ui.theme.TextPrimary

/**
 * **设置**（用户 2026-09-27 要求：推翻重构，拆成三个界面）。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * 为什么不再把所有东西塞在一页
 * ═══════════════════════════════════════════════════════════════════════════
 * 上一版把「密钥 / 模型 / 思考 / 记忆 / 隐私 / 全局规则」全铺在同一页上，
 * 一路滚到底。问题不是"内容多"，而是**它们的读者不同**：
 *
 *   · 「连接设置」是**偶尔配一次**的东西（换密钥、换模型），配完就不再打开；
 *   · 「功能设置」是**偶尔调一次**的偏好；
 *   · 「人设与角色」是**经常用**的内容。
 *
 * 三类东西混在一页，结果是每次只想改一个开关，都要划过一堆看不懂的配置项。
 * 拆开之后：这一页只负责"去哪"，每一页只负责"改什么"。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * 文案：这里是**入口**，不是说明书
 * ═══════════════════════════════════════════════════════════════════════════
 * 用户要求「所有设置页面的固定提示都是面向用户的，把用户当做傻子」。
 * 所以每一行的副标题都只回答"**点进去能做什么**"，不解释技术概念 ——
 * 技术细节留给各自那一页（那里有上下文）。
 *
 * ⚠️ 渲染未经真机验证（本机无设备）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenPersonas: () -> Unit,
    onOpenFeature: () -> Unit,
    /** 美化设置页（v0.61.57）—— 背景图 / 气泡 / 顶栏透明度。 */
    onOpenAppearance: () -> Unit,
    /** v0.57.0：从「功能设置」拆出来的两组，各自独立成页 */
    onOpenEmojiOptions: () -> Unit,
    onOpenMemoryOptions: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenAdvanced: () -> Unit,
    onOpenApi: () -> Unit,
    onOpenAccount: () -> Unit,
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
                title = { Text("设置", style = MaterialTheme.typography.titleMedium) },
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
            MenuCard {
                MenuRow(
                    icon = YukiIcons.Person,
                    title = "人设与角色",
                    desc = "创建Ta、修改Ta的性格和说话方式",
                    onClick = onOpenPersonas,
                )
                MenuDivider()
                MenuRow(
                    icon = YukiIcons.Snowflake,
                    title = "功能设置",
                    // ⚠️ 副标题必须跟着内容变：数据备份 / 高级设定（v0.50.5）与
                    //    表情包 / 记忆（v0.57.0）都已拆成独立页 —— 再写它们，
                    //    用户就会在一个不存在的入口里找。
                    desc = "聊天怎么呈现、字体、隐私",
                    onClick = onOpenFeature,
                )
                MenuDivider()
                // ⚠️ v0.61.57 新增：美化（用户要求「设置内增加美化设置入口和设置页」）。
                //    它**独立成页**而不是塞进「功能设置」—— 那一页已经很长，
                //    而美化是一类**会反复回来调**的东西（换图、调模糊），
                //    埋在二级页里每次要多点一次。
                MenuRow(
                    icon = YukiIcons.Image,
                    title = "美化",
                    desc = "背景图、气泡样式、顶栏透明度",
                    onClick = onOpenAppearance,
                )
                MenuDivider()
                // v0.57.0：从「功能设置」再拆两组出来（用户要求
                // 「功能设置页的某几组搬去设置首页做独立入口和界面」）。
                MenuRow(
                    icon = YukiIcons.Image,
                    title = "表情包",
                    desc = "Ta 什么时候发图、用哪一套图",
                    onClick = onOpenEmojiOptions,
                )
                MenuDivider()
                MenuRow(
                    icon = YukiIcons.ChatBubble,
                    title = "记忆",
                    desc = "要不要自动记住、记了什么",
                    onClick = onOpenMemoryOptions,
                )
                MenuDivider()
                // v0.50.5 新增两个入口：把「功能设置」里那两组常改/少改的东西分出来。
                // 用户原话：「把功能设置再分出去几个放到设置列表做几个入口和专门的界面」。
                MenuRow(
                    icon = YukiIcons.Book,
                    title = "数据备份",
                    desc = "导出 / 导入这台设备上的全部数据",
                    onClick = onOpenBackup,
                )
                MenuDivider()
                MenuRow(
                    icon = YukiIcons.Info,
                    title = "高级设定",
                    desc = "通用设定与缓存诊断 —— 新人不用碰",
                    onClick = onOpenAdvanced,
                )
                MenuDivider()
                MenuRow(
                    icon = YukiIcons.Settings,
                    title = "连接设置",
                    // v0.51.0：这一页从「一套密钥 + 一个模型」变成了「分组列表」，
                    // 副标题得跟着改 —— 写"密钥和模型"会让用户以为点进去只有一个表单。
                    desc = "密钥、地址和模型分组 —— 配一次就好",
                    onClick = onOpenApi,
                )
                MenuDivider()
                MenuRow(
                    icon = YukiIcons.AccountCircle,
                    title = "账号安全",
                    desc = "绑定邮箱、用邮箱改密码",
                    onClick = onOpenAccount,
                )
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun MenuCard(content: @Composable () -> Unit) {
    YukiCard(Modifier.fillMaxWidth()) {
        Column { content() }
    }
}

@Composable
private fun MenuDivider() {
    androidx.compose.material3.HorizontalDivider(
        modifier = Modifier.padding(start = 56.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
private fun MenuRow(
    icon: ImageVector,
    title: String,
    desc: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = SkyBlueDeep,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.size(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(2.dp))
            Text(desc, style = MaterialTheme.typography.labelSmall, color = TextMuted)
        }
        Icon(
            YukiIcons.ChevronRight,
            contentDescription = null,
            tint = TextMuted,
            modifier = Modifier.size(18.dp),
        )
    }
}
