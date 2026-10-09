"""把底部导航栏的「市场」占位换成真的市场（v0.58.0）。

⚠️ 为什么要脚本：要把 MainTabs.kt 里整段市场占位（第 266–389 行，含 MarketTab +
   MarketSkeletonGrid + MarketSkeletonCard + SkeletonBar 四个函数）整块换掉。
   手写 edit_file 的 old_string 要逐字复现 120 行，任何一处空白不同就会失败；
   脚本按**内容锚点**取区间，且保留原换行符（否则整个文件 diff 全红全绿）。

⚠️ 骨架屏（Skeleton）在占位页里是私有的、现在搬去了 `ui/market/MarketScreen.kt`，
   所以这里整段删掉，不留死代码。
"""
import io

P = r"C:\yuki-native\app\src\main\java\ai\yuki\chuxue\ui\main\MainTabs.kt"

raw = io.open(P, "rb").read().decode("utf-8")
nl = "\r\n" if "\r\n" in raw else "\n"
text = raw.replace("\r\n", "\n")

START = "/* ═══════════════════════ 市场（文档 §38，需后端 → 占位）═══════════════════════ */"
END = "/* ═══════════════════════ 我的（文档 §39）═══════════════════════ */"

i = text.index(START)
j = text.index(END, i)

NEW = '''/* ═══════════════════════ 市场（v0.58.0：真接口已接）═══════════════════════ */

/**
 * 底部导航栏第 3 个 Tab。
 *
 * ## 位置是既定的
 * 用户 2026-10-01：「应该在底部导航栏，不是在人设顶部栏」。
 * 这个位早就留着（原占位页写着"市场还没开放"）——**一个功能该在它既定的位置上长出来**，
 * 而不是另找地方挂一个入口。所以这一版把占位换成真的，人设页顶栏那个入口删掉。
 *
 * ⚠️ 具体内容全在 `ui/market/MarketScreen.kt`（`MarketTabContent`）：
 *    这一层只负责"它是第几个 Tab"，不掺业务 —— 与其它 Tab 的分工一致。
 */
@Composable
fun MarketTab(
    vm: ChatViewModel,
    authVm: AuthViewModel,
    onOpenDetail: (String) -> Unit,
    onOpenUpload: () -> Unit,
) {
    MarketTabContent(
        vm = vm,
        authVm = authVm,
        onOpenDetail = onOpenDetail,
        onOpenUpload = onOpenUpload,
    )
}

'''

text = text[:i] + NEW + text[j:]
io.open(P, "wb").write(text.replace("\n", nl).encode("utf-8"))
print("patched:", P)
