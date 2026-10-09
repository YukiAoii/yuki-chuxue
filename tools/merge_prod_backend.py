"""把生产 YukiServer/main.py 里「仓库没有」的三块搬进仓库版（v0.58.0 部署前置）。

## 为什么必须合并、不能覆盖
线上后端**不是**仓库 backend/ 的旧副本 —— 它有仓库里没有的东西：
  · `user_blobs` 表 + `PersonaBlobIn` + `GET/PUT /api/v1/user/persona`（v0.52.0 人设端到端加密同步）
  · `POST /api/v1/user/avatar/upload`（用户侧头像上传）
这些是在 YukiServer 那台机器上直接开发的，**从未回灌到仓库**。

反过来仓库有线上没有的：市场（4 表 + 7 接口）、免费分组、功能开关、`/api/v1/upload/image`、反馈。

⚠️ 所以直接 `cp 仓库/main.py YukiServer/` 会把用户的人设同步功能**删掉** ——
   用户手机上的人设就再也传不上去了。这个脚本做的是**合并**：
   以仓库版为底，把生产侧那三块按内容锚点插进去。

⚠️ 锚点用内容而不是行号：两边行号早就对不上（差异 800+ 行）。
"""
import io
import os
import sys

REPO = r"C:\Users\<用户名>\Desktop\项目1\Yuki初雪\backend\main.py"
PROD = r"C:\Users\<用户名>\Desktop\YukiServer\main.py"


def read(p):
    raw = io.open(p, "rb").read().decode("utf-8")
    nl = "\r\n" if "\r\n" in raw else "\n"
    return raw.replace("\r\n", "\n"), nl


prod, _ = read(PROD)
prod_lines = prod.split("\n")


def slice_lines(a, b):
    """取生产文件的 [a, b] 行（1-based，含两端）。"""
    return "\n".join(prod_lines[a - 1:b])


# ── 生产侧三块（行号取自本机实测的 grep，前面已逐块核对过内容）──
BLOCK_TABLE = slice_lines(251, 262)      # user_blobs 的注释 + CREATE TABLE
BLOCK_MODEL = slice_lines(679, 688)      # class PersonaBlobIn
BLOCK_APIS = slice_lines(1923, 1994)     # 人设密文读写 + 头像上传

for name, blk in (("user_blobs 表", BLOCK_TABLE), ("PersonaBlobIn", BLOCK_MODEL), ("人设接口", BLOCK_APIS)):
    if not blk.strip():
        print(f"✗ 生产侧「{name}」取到空内容 —— 行号漂了，中止", file=sys.stderr)
        sys.exit(1)
    print(f"  {name}: 取到 {len(blk.splitlines())} 行")

if "CREATE TABLE IF NOT EXISTS user_blobs" not in BLOCK_TABLE:
    print("✗ user_blobs 块不对", file=sys.stderr); sys.exit(1)
if "class PersonaBlobIn" not in BLOCK_MODEL:
    print("✗ PersonaBlobIn 块不对", file=sys.stderr); sys.exit(1)
if "user/persona" not in BLOCK_APIS or "user/avatar/upload" not in BLOCK_APIS:
    print("✗ 接口块不对", file=sys.stderr); sys.exit(1)

# ── 仓库侧：插进去 ──
repo, nl = read(REPO)

# ① 表：插在 comment_likes 那段之后（同一个 SCHEMA 字符串里，顺序不影响建表）
anchor1 = "CREATE TABLE IF NOT EXISTS comment_likes ("
i1 = repo.index(anchor1)
end1 = repo.index(");", i1) + len(");")
repo = repo[:end1] + "\n\n" + BLOCK_TABLE + repo[end1:]

# ② 模型：插在 ProfilePatchIn 之后
anchor2 = "class ProfilePatchIn(BaseModel):\n    nickname: str | None = None\n    avatar_url: str | None = None\n"
i2 = repo.index(anchor2) + len(anchor2)
repo = repo[:i2] + "\n\n" + BLOCK_MODEL + repo[i2:]

# ③ 接口：插在 user_set_avatar 之后（与 /user/password 之间）
anchor3 = 'def user_set_avatar(body: ProfilePatchIn, user_id: str = Depends(require_user)) -> dict[str, Any]:'
i3 = repo.index(anchor3)
end3 = repo.index('\n\n\n', i3)
repo = repo[:end3] + "\n\n" + BLOCK_APIS.rstrip("\n") + repo[end3:]

io.open(REPO, "wb").write(repo.replace("\n", nl).encode("utf-8"))
print("已合并写入仓库版 main.py")
