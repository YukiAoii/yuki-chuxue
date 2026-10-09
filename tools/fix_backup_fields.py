"""补上备份里被静默丢掉的 7 个字段（v0.58.0）。

## 这是深度审查查出来的一条**高严重度**问题
`data/Backup.kt` 在**导出与导入两侧**都漏了 7 个字段，于是"导出→换机→导入"之后：
- `ChatMessage.sendMode` 丢失 → 历史消息的呈现方式被改回**当前**全局设置
  （Models.kt 的注释记着用户以前报过这次回归，他会以为是新 bug）
- `ChatMessage.emojiPath` 丢失 → 历史里的表情包图没了
- `Persona.note` / `isPinned` / `emojiChanceOverride` 丢失 → 备注、置顶、表情概率全没了
- `AppSettings.compressMode` / `compressThreshold` 丢失 → 压缩的触发方式与阈值被重置

⚠️ 为什么一直没被发现：日常读写走 Room（`SessionMapper` 那侧是对的），
   只有备份这一条路漏；而且 `BackupTest` 是**假绿** —— 它做整对象相等断言，
   但样例数据里这些字段**全用默认值**，"缺失 == 缺失"照样通过。
   所以这个脚本同时要修样例数据，否则修完还是测不出来。

⚠️ 补字段是**向后兼容**的：老备份缺这些栏，解码回落到各自默认值即可，不用升 FORMAT_VERSION。
"""
import io
import os

P = r"C:\yuki-native\app\src\main\java\ai\yuki\chuxue\data\Backup.kt"

raw = io.open(P, "rb").read().decode("utf-8")
nl = "\r\n" if "\r\n" in raw else "\n"
t = raw.replace("\r\n", "\n")

EDITS = [
    # ① Persona：导出
    (
        '        put("greeting", p.greeting)\n',
        '        put("greeting", p.greeting)\n'
        '        // ⚠️ 下面三个漏过一次：导出再导入后人设的备注/置顶/表情概率全没了\n'
        '        put("note", p.note)\n'
        '        put("isPinned", p.isPinned)\n'
        '        put("emojiChanceOverride", p.emojiChanceOverride)\n',
    ),
    # ② Persona：导入（⚠️ 解码侧缩进是 8 空格，不是 12）
    (
        '        greeting = o.s("greeting"),\n',
        '        greeting = o.s("greeting"),\n'
        '        note = o.s("note").orEmpty(),\n'
        '        isPinned = o.b("isPinned") ?: false,\n'
        '        emojiChanceOverride = o.f("emojiChanceOverride"),\n',
    ),
    # ③ 消息：导出
    (
        '        put("thinkingMs", m.thinkingMs)\n',
        '        put("thinkingMs", m.thinkingMs)\n'
        '        // ⚠️ sendMode 必须**冻结在消息上**（见 Models.kt 的注释）——\n'
        '        //    漏了它就等于"改一次全局设置，历史气泡全被改写"，用户会当成新 bug。\n'
        '        put("sendMode", m.sendMode)\n'
        '        put("emojiPath", m.emojiPath)\n',
    ),
    # ④ 消息：导入（⚠️ 解码侧缩进同样是 8 空格）
    (
        '        thinkingMs = o.l("thinkingMs"),\n',
        '        thinkingMs = o.l("thinkingMs"),\n'
        '        sendMode = o.s("sendMode"),\n'
        '        emojiPath = o.s("emojiPath"),\n',
    ),
    # ⑤ 设置：导出
    (
        '        put("emojiChance", s.emojiChance)\n',
        '        put("emojiChance", s.emojiChance)\n'
        '        // ⚠️ 压缩的触发方式与阈值：漏了之后用户"压缩改成手动"的设定会悄悄退回默认\n'
        '        put("compressMode", s.compressMode)\n'
        '        put("compressThreshold", s.compressThreshold)\n',
    ),
    # ⑥ 设置：导入
    (
        '            emojiChance = o.f("emojiChance") ?: base.emojiChance,\n',
        '            emojiChance = o.f("emojiChance") ?: base.emojiChance,\n'
        '            compressMode = o.s("compressMode") ?: base.compressMode,\n'
        '            compressThreshold = o.f("compressThreshold") ?: base.compressThreshold,\n',
    ),
]

for old, new in EDITS:
    if old not in t:
        raise SystemExit("✗ 锚点没命中：" + old.strip()[:60])
    if new.strip().split("\n")[-1].strip() in t:
        print("  跳过（已存在）：", old.strip()[:40]); continue
    t = t.replace(old, new, 1)
    print("  ✓ 已补：", old.strip()[:40])

io.open(P, "wb").write(t.replace("\n", nl).encode("utf-8"))
print("Backup.kt 已更新")

# 复查
chk = io.open(P, "rb").read().decode("utf-8")
for f in ["sendMode", "emojiPath", "note", "isPinned", "emojiChanceOverride", "compressMode", "compressThreshold"]:
    print(f"  {f:22s} {chk.count(f)} 次")
