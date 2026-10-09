"""让 BackupTest 的样例真正覆盖那 7 个字段（v0.58.0）。

## 为什么必须改测试，而不是只改 Backup.kt
`BackupTest` 做的是**整对象相等**断言 —— 看起来能抓住"漏搬运字段"。
但样例里这 7 个字段**全是默认值**，于是「两边都缺」也相等，测试照样绿。
所以只修 `Backup.kt` 的话，下次谁再漏一个字段，仍然测不出来。

⚠️ 有趣的是这个测试的注释里**已经写着这条原则**（"刻意用非默认值：默认值会让'漏搬运
这个字段'这种错看不出来"），作者也确实给 fontScale 之类设了非默认值 —— 只是漏了这 7 个。
"""
import io

P = r"<构建目录>\app\src\test\java\ai\yuki\chuxue\data\BackupTest.kt"

raw = io.open(P, "rb").read().decode("utf-8")
nl = "\r\n" if "\r\n" in raw else "\n"
t = raw.replace("\r\n", "\n")

EDITS = [
    # ① 人设样例：补 note / isPinned / emojiChanceOverride
    (
        '                greeting = "你来了。",\n',
        '                greeting = "你来了。",\n'
        '                // ⚠️ 这三个以前没设，于是"备份漏搬运它们"测不出来（假绿）\n'
        '                note = "这是我自己看的备注",\n'
        '                isPinned = true,\n'
        '                emojiChanceOverride = 0.33f,\n',
    ),
    # ② 消息样例：补 sendMode / emojiPath（挂在带图片的那条 user 消息上）
    (
        '                        images = listOf("data:image/jpeg;base64,AAAABBBB"),\n',
        '                        images = listOf("data:image/jpeg;base64,AAAABBBB"),\n'
        '                        // ⚠️ sendMode 必须冻结在消息上；emojiPath 是"退出重进丢表情包"的修复\n'
        '                        sendMode = SEND_MODE_WAIFU,\n'
        '                        emojiPath = "/data/user/0/ai.yuki.chuxue/files/emoji/a.png",\n',
    ),
    # ③ 设置样例：补 compressMode / compressThreshold
    (
        '            // 刻意用非默认值：默认值会让"漏搬运这个字段"这种错看不出来\n            fontScale = FONT_SCALE_XLARGE,\n',
        '            // 刻意用非默认值：默认值会让"漏搬运这个字段"这种错看不出来\n'
        '            fontScale = FONT_SCALE_XLARGE,\n'
        '            compressMode = COMPRESS_MODE_MANUAL,\n'
        '            compressThreshold = 0.42f,\n',
    ),
]

for old, new in EDITS:
    if old not in t:
        raise SystemExit("✗ 锚点没命中：" + old.strip()[:60])
    t = t.replace(old, new, 1)
    print("  ✓", old.strip().split("\n")[-1][:50])

io.open(P, "wb").write(t.replace("\n", nl).encode("utf-8"))
print("BackupTest.kt 样例已补全")
