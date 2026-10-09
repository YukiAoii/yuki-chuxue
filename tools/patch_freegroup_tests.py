"""把免费分组的测试迁到三态 API（v0.58.0）。

`ProviderGroups.withManaged` 的第二参从 `FreeGroup?` 变成 `FreeGroupApi.State` ——
"关掉"与"没问到"必须在类型上分开（那正是用户报的 bug 的修法）。
"""
import io
import os

BASE = r"<构建目录>\app\src\test\java\ai\yuki\chuxue\data"

HELPER = '''
/** 测试里少打几个字：明确可用的那一种状态。 */
private fun ok(g: FreeGroup) = FreeGroupApi.State.Ok(g)
'''

# ── ① FreeGroupManagedTest：把 free / null 换成三态 ──
p1 = os.path.join(BASE, "FreeGroupManagedTest.kt")
raw = io.open(p1, "rb").read().decode("utf-8")
nl = "\r\n" if "\r\n" in raw else "\n"
t = raw.replace("\r\n", "\n")

reps1 = [
    ("withManaged(\n            emptyList(),\n            free,\n        )", "withManaged(\n            emptyList(),\n            ok(free),\n        )"),
    ("withManaged(emptyList(), free)", "withManaged(emptyList(), ok(free))"),
    ("withManaged(listOf(mine()), free)", "withManaged(listOf(mine()), ok(free))"),
    ("withManaged(first, free.copy(apiKey = \"sk-new\"))", "withManaged(first, ok(free.copy(apiKey = \"sk-new\")))"),
    ("withManaged(withIt, null)", "withManaged(withIt, FreeGroupApi.State.Disabled)"),
    ("withManaged(listOf(managed), free)", "withManaged(listOf(managed), ok(free))"),
]
for a, b in reps1:
    if a in t:
        t = t.replace(a, b)
    else:
        print("  [warn] 未命中:", a.replace("\n", " ")[:60])

if "private fun ok(g: FreeGroup)" not in t:
    t = t.replace("class FreeGroupManagedTest {", "class FreeGroupManagedTest {" + HELPER, 1)

io.open(p1, "wb").write(t.replace("\n", nl).encode("utf-8"))
print("patched", os.path.basename(p1))

# ── ② FreeGroupTristateTest：删掉那行绕来绕去的断言，末条改成真的测 Unreachable ──
p2 = os.path.join(BASE, "FreeGroupTristateTest.kt")
raw = io.open(p2, "rb").read().decode("utf-8")
nl = "\r\n" if "\r\n" in raw else "\n"
t = raw.replace("\r\n", "\n")

t = t.replace(
    '        assertNull("空响应", FreeGroupApi.parseState(null).let { if (it is FreeGroupApi.State.Disabled) null else it })\n',
    "",
)
t = t.replace("import org.junit.Assert.assertNull\n", "")

old_last = t[t.index("    @Test\n    fun `Unreachable 时 withManaged 保持原样"):]
new_last = '''    @Test
    fun `Unreachable 时 withManaged 原样返回（不增不减）`() {
        val free = FreeGroup("Yuki初雪Pro", "https://api.your-llm-provider.example.com:18443/v1", "sk-free", "官方提供")
        val withIt = ProviderGroups.withManaged(emptyList(), FreeGroupApi.State.Ok(free))
        assertEquals(1, withIt.size)

        // ⚠️ 这一条就是用户报的 bug 的护栏：没问到 = **保持原样**。
        //    改回"null 就摘掉"的话，一次网络抖动就能让官方分组凭空消失。
        assertEquals(
            "没问到不该动本地数据",
            withIt,
            ProviderGroups.withManaged(withIt, FreeGroupApi.State.Unreachable),
        )

        // 对照：服务端**明确**说关掉时才摘
        assertEquals(
            "明确关掉才摘",
            0,
            ProviderGroups.withManaged(withIt, FreeGroupApi.State.Disabled).size,
        )
    }
}
'''
t = t.replace(old_last, new_last)
io.open(p2, "wb").write(t.replace("\n", nl).encode("utf-8"))
print("patched", os.path.basename(p2))
