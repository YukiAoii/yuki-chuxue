"""把「编辑分组」页的 ①②③ 三块包进 `if (managed) ... else ...`（v0.58.0）。

⚠️ 为什么要用脚本而不是手改：
   这需要给约 70 行**整块重排缩进**。手改很容易漏一行，
   而漏掉的那一行会掉到 `else` 外面 —— **编译照样通过**，
   但托管分组页会出现地址/密钥输入框（正是这次要杜绝的东西）。
   脚本按**内容锚点**定位（不按行号，前面的编辑不会让它错位），
   并且**保留原换行符**（否则整个文件的 diff 会变成全红全绿，看不出真正的改动）。
"""
import io

P = r"<构建目录>\app\src\main\java\ai\yuki\chuxue\ui\settings\ProviderGroupEditScreen.kt"

raw = io.open(P, "rb").read().decode("utf-8")
nl = "\r\n" if "\r\n" in raw else "\n"
text = raw.replace("\r\n", "\n")

# ── ① 补 import（本文件原本没有 BrandBlue）──
if "import ai.yuki.chuxue.ui.theme.BrandBlue" not in text:
    anchor = "import ai.yuki.chuxue.ui.theme.TextPrimary"
    text = text.replace(anchor, "import ai.yuki.chuxue.ui.theme.BrandBlue\n" + anchor, 1)

# ── ② 把 ①名称 / ②服务地址 / ③密钥 包起来 ──
START = "            /* ── ① 名称 ── */\n"
END = '            SettingsGroup("④ 挑要用的模型") {'

i = text.index(START)
j = text.index(END, i)
block = text[i:j]
indented = "\n".join(("    " + ln) if ln.strip() else ln for ln in block.split("\n"))

head = (
    "            // ⚠️ 托管分组（服务端下发的「Yuki初雪Pro」）**这三块根本不渲染**：\n"
    "            //    名称只读，地址与密钥连框都没有 —— 用户没有任何理由看到一个不属于他的密钥，\n"
    "            //    而「只读输入框」仍然把密钥摆在屏幕上、「禁用」也仍然能选中复制。\n"
    "            if (managed) {\n"
    "                ManagedGroupNotice(\n"
    "                    name = existing?.name.orEmpty(),\n"
    "                    notice = existing?.notice.orEmpty(),\n"
    "                )\n"
    "            } else {\n"
)
tail = "            }\n\n"
text = text[:i] + head + indented + tail + text[j:]

# ── ③ 追加说明卡组件 ──
NOTICE = '''

/**
 * 托管分组（「Yuki初雪Pro」）在编辑页顶部的说明卡，替代原本的①名称/②地址/③密钥。
 *
 * ⚠️ 这里**只显示名字**，不显示地址与密钥 —— 那不是用户的东西，
 *    摆出来只会让他以为自己该去核对它。
 */
@Composable
private fun ManagedGroupNotice(name: String, notice: String) {
    SettingsGroup("官方提供") {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(BrandBlue.copy(alpha = 0.12f))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = "免费",
                        style = MaterialTheme.typography.labelSmall,
                        color = BrandBlue,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = name.ifBlank { "Yuki初雪Pro" },
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = notice.ifBlank { "地址与密钥由服务器维护 —— 不需要填写，也看不到。" },
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "你只需要挑模型、调上下文参数，然后测一下能不能用。",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
            )
        }
    }
}
'''
text = text.rstrip("\n") + "\n" + NOTICE

io.open(P, "wb").write(text.replace("\n", nl).encode("utf-8"))
print("patched:", P)
