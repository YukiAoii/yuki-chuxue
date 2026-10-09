"""一次性资源生成脚本（v0.58.0 换图标 + 换收款码）。

⚠️ 为什么要用脚本而不是手动导图：
   · 启动图标要 5 个密度，尺寸必须精确（48/72/96/144/192），手导容易漏或错；
   · 「不要压缩大小」这条要求落到操作上就是：**用 LANCZOS 高质量重采样 + PNG 无损保存**，
     而不是把 1440 的原图直接塞进去（那样系统缩放会更糊）。

⚠️ 源图 1440×1427 不是正方形：启动图标必须是正方形（否则会被拉伸变形）。
   这里**居中裁成正方形**（只裁掉左右各 6px，占 0.9%），而不是补边——
   补边会在图的两侧留出两条底色，看起来像没铺满。

⚠️ 收款码同样居中裁方：两张源图都近乎正方（667×682 / 819×812），
   且**图案是满幅的、没有白边**。二维码的静区（quiet zone）由卡片的白底 padding 提供，
   所以卡片样式重写时**必须保留足够的白边**，否则扫码会不灵。
"""
from PIL import Image
import os

SRC = r"C:\Users\<用户名>\Desktop\项目1\Yuki初雪"
RES = r"C:\yuki-native\app\src\main\res"


def square(im):
    """居中裁成正方形（启动图标/二维码都需要正方的画布）。"""
    w, h = im.size
    s = min(w, h)
    left, top = (w - s) // 2, (h - s) // 2
    return im.crop((left, top, left + s, top + s))


def main():
    # ── ① 启动图标：5 个密度 ──
    icon = square(Image.open(os.path.join(SRC, "1790799511505_软件图标.jpg")).convert("RGB"))
    print("icon source after crop:", icon.size)
    for density, size in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96),
                          ("xxhdpi", 144), ("xxxhdpi", 192)):
        out = os.path.join(RES, f"mipmap-{density}", "ic_launcher.png")
        icon.resize((size, size), Image.LANCZOS).convert("RGBA").save(out, "PNG", optimize=False)
        print(f"  {out}  {size}x{size}  {os.path.getsize(out)}B")

    # ── ② 应用内大图：512 的 nodpi 资源 ──
    # ⚠️ 单独出一张而不是复用 mipmap：应用内有 84dp 的用法，
    #    在 3x/4x 屏上需要 252–336px，而 xxxhdpi 只有 192px —— 复用就会被放大变糊。
    #    `drawable-nodpi` 不参与密度换算，由 Compose 直接按 dp 缩放，最清晰。
    nodpi = os.path.join(RES, "drawable-nodpi")
    os.makedirs(nodpi, exist_ok=True)
    logo = os.path.join(nodpi, "ic_app_logo.png")
    icon.resize((512, 512), Image.LANCZOS).save(logo, "PNG", optimize=False)
    print(f"  {logo}  512x512  {os.path.getsize(logo)}B")

    # ── ③ 收款码 ──
    pairs = [
        ("1790799513040_收款码1.png", "pay_wechat.png", "微信"),
        ("1790799512829_收款码2.jpg", "pay_alipay.png", "支付宝"),
    ]
    for src_name, out_name, label in pairs:
        im = square(Image.open(os.path.join(SRC, src_name)).convert("RGB"))
        out = os.path.join(RES, "drawable", out_name)
        im.save(out, "PNG", optimize=False)
        print(f"  {label}: {out}  {im.size}  {os.path.getsize(out)}B")


if __name__ == "__main__":
    main()
