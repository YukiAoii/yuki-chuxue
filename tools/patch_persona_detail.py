"""人设详情页重排（v0.58.0）——把 Hero + 锚点卡换成 QQ 好友资料页式的三件套。

⚠️ 为什么要脚本：要从 `/* ─── 身份 Hero ─── */` 一直换到 `/* ─── 小工具 ─── */` 之前，
   中间包含**两个** composable（PersonaHero 与 AnchorCard），共约 95 行。
   手写 edit_file 的 old_string 容易在某个空行/缩进上对不上；
   脚本按**内容锚点**取区间，并且**保留原换行符**（否则整个文件 diff 全红全绿）。
"""
import io

P = r"C:\yuki-native\app\src\main\java\ai\yuki\chuxue\ui\persona\PersonaDetailScreen.kt"

raw = io.open(P, "rb").read().decode("utf-8")
nl = "\r\n" if "\r\n" in raw else "\n"
text = raw.replace("\r\n", "\n")

START = "/* ─────────────── 身份 Hero ─────────────── */"
END = "/* ─────────────── 小工具 ─────────────── */"

i = text.index(START)
j = text.index(END, i)

NEW = '''/* ─────────────── 封面（v0.58.0：照 QQ 好友资料页重排） ─────────────── */

/**
 * 封面横幅 + **压在横幅下边缘的头像** + 名字 + 签名位（备注）。
 *
 * ## 为什么改成这个样子
 * 用户要「类似在 QQ 中查看好友的样式的布局」。旧版是一个圆角大蓝块里居中塞头像、
 * 名字、备注胶囊 —— 三样东西挤在一块底色里，读起来像一张名片，不像"一个人"。
 * 现在拆成：**横幅在上、头像压边、名字在下**，与 QQ/微信的好友资料页同构。
 *
 * ⚠️ 压边用「横幅底部留 46dp 不给画 + 头像 `align(BottomCenter)`」实现，
 *    **不用 `offset` 负位移** —— offset 只是视觉平移，布局空间还占着，
 *    下面会凭空多出一段空白（这种"说不清哪里不对"的空白最难查）。
 */
@Composable
private fun PersonaCover(persona: Persona, displayName: String, since: Long?, turns: Int) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 46.dp)
                    .height(128.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Brush.linearGradient(listOf(BrandBlue, BrandBlueDeep))),
            ) {
                // 两团半透明圆造空间感（与「我的」页同一手法，不引图片资源）
                Canvas(Modifier.matchParentSize()) {
                    drawCircle(
                        color = Color.White.copy(alpha = 0.15f),
                        radius = size.minDimension * 0.40f,
                        center = Offset(size.width * 0.88f, size.height * 0.10f),
                    )
                    drawCircle(
                        color = Color.Black.copy(alpha = 0.10f),
                        radius = size.minDimension * 0.52f,
                        center = Offset(size.width * 0.06f, size.height * 1.06f),
                    )
                }

                // 签名位 = 备注。放在封面上而不是清单里：QQ 上那儿就是"个性签名"，
                // 一眼就知道"这句是写给她旁边那个名字的"。
                if (persona.note.isNotBlank()) {
                    Box(
                        Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 16.dp, bottom = 12.dp)
                            .clip(RoundedCornerShape(50))
                            .background(SnowWhite.copy(alpha = 0.20f))
                            .padding(horizontal = 12.dp, vertical = 5.dp),
                    ) {
                        Text(
                            text = "备注 · ${persona.note}",
                            style = MaterialTheme.typography.labelMedium,
                            color = SnowWhite,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                if (since != null) {
                    Text(
                        text = "一起 $turns 段",
                        style = MaterialTheme.typography.labelSmall,
                        color = SnowWhite.copy(alpha = 0.88f),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 16.dp, bottom = 14.dp),
                    )
                }
            }

            // 头像：压住横幅的下边缘。外面套一圈白，做出"扣出来压上去"的层次
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .size(92.dp)
                    .clip(CircleShape)
                    .background(SnowWhite)
                    .padding(4.dp),
            ) {
                UserAvatar(size = 84.dp, path = persona.avatarPath, fallbackText = displayName)
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            text = displayName,
            style = MaterialTheme.typography.titleLarge,
            color = TextPrimary,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (since != null) {
            Spacer(Modifier.height(3.dp))
            Text(
                text = "相遇于 ${fullDate(since)}",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
            )
        }
    }
}

/* ─────────────── 快捷动作行 ─────────────── */

/**
 * 并排的动作块：说话 / 表情包 / 记忆。
 *
 * ⚠️ 「记忆」只在**真的有会话**时才给：记忆是按会话存的，没有会话就没有记忆库可开 ——
 *    给一个点了只能弹"还没有"的按钮，不如不给。
 * ⚠️ 「说话」占两格宽度：它是这一页的主动作，与另外两个一样宽会分不清主次。
 */
@Composable
private fun QuickActions(
    primary: String,
    onChat: () -> Unit,
    onEmoji: () -> Unit,
    onMemory: (() -> Unit)?,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        QuickAction(primary, YukiIcons.ChatBubble, onChat, Modifier.weight(2f), filled = true)
        QuickAction("表情包", YukiIcons.Image, onEmoji, Modifier.weight(1f))
        onMemory?.let { QuickAction("记忆", YukiIcons.Snowflake, it, Modifier.weight(1f)) }
    }
}

@Composable
private fun QuickAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (filled) BrandBlue else MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(vertical = 13.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (filled) SnowWhite else BrandBlue,
            modifier = Modifier.size(17.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (filled) SnowWhite else TextPrimary,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/* ─────────────── 资料行 ─────────────── */

/**
 * 资料卡里的一行：左标签 / 右内容。
 *
 * ⚠️ 标签列**固定宽度**（112dp）：不固定的话每一行的冒号位置都不一样，
 *    一眼看过去是"七零八落"而不是"一张表格"。
 */
@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = TextMuted,
            modifier = Modifier.width(112.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = TextPrimary,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun InfoDivider() {
    Box(
        Modifier
            .padding(start = 16.dp)
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    )
}

'''

text = text[:i] + NEW + text[j:]

io.open(P, "wb").write(text.replace("\n", nl).encode("utf-8"))
print("patched:", P)
