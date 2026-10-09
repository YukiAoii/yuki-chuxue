"""「Ta 化」扫尾（v0.58.0）。

用户 2026-10-01 再次强调：「界面的文案提示用 Ta 不是她，这条规则涉及男女他她的都用 Ta」。

⚠️ 替换前先确认这些文件里**没有「其他 / 他们 / 他的」**这类词 ——
   盲replace 他→Ta 会把「其他」变成「其Ta」，那是比原文更难看的错。
   脚本里再判一次，命中就直接中止、不做任何写入。
⚠️ 保留原换行符（否则整个文件 diff 全红全绿）。
"""
import io
import os
import sys

FILES = [
    r"<构建目录>\app\src\main\java\ai\yuki\chuxue\ui\market\MarketScreen.kt",
    r"<构建目录>\app\src\main\java\ai\yuki\chuxue\ui\market\MarketDetailScreen.kt",
    r"<构建目录>\app\src\main\java\ai\yuki\chuxue\ui\market\PersonaPickSheet.kt",
    r"<构建目录>\app\src\main\java\ai\yuki\chuxue\ui\persona\PersonaDetailScreen.kt",
]

# 一旦出现这些词，说明「他」是构词成分，不能整字替换
GUARDS = ["其他", "他们", "他的", "他人", "其她"]

total = 0
for p in FILES:
    raw = io.open(p, "rb").read().decode("utf-8")
    nl = "\r\n" if "\r\n" in raw else "\n"
    text = raw.replace("\r\n", "\n")

    hit = [g for g in GUARDS if g in text]
    if hit:
        print(f"  中止：{os.path.basename(p)} 含 {hit} —— 需要人工判断，未写入")
        sys.exit(1)

    n = text.count("她") + text.count("他")
    if n == 0:
        print(f"  {os.path.basename(p):26s} 无需替换")
        continue

    text = text.replace("她", "Ta").replace("他", "Ta")
    io.open(p, "wb").write(text.replace("\n", nl).encode("utf-8"))
    total += n
    print(f"  {os.path.basename(p):26s} 替换 {n} 处")

print("合计替换", total, "处")
