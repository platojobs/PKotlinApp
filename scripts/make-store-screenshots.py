#!/usr/bin/env python3
"""
把 docs/screenshots 里的真实界面截图排成 Google Play 商店截图。

规格：1080×1920（16:9），共 6 张；只使用仓库中已有的真实截图，
不合成、不伪造任何界面或功能。文案只描述对应截图里能看到的内容。

用法：
  python3 scripts/make-store-screenshots.py
输出：
  docs/screenshots/play/*.png
"""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "docs" / "screenshots"
OUT = SRC / "play"

W, H = 1080, 1920
CAPTION_FONT = "/System/Library/Fonts/Supplemental/Arial Unicode.ttf"

# (源截图, 输出文件名, 文案)。文案只对应截图中实际可见的界面。
SHOTS = [
    ("home.png", "01-home.png", "首页 · 设备卡片与实时读数"),
    ("device_detail.png", "02-device-detail.png", "设备详情 · 历史曲线与阈值告警"),
    ("scenes.png", "03-scenes.png", "智能 · 本机场景一键执行"),
    ("products.png", "04-products.png", "产品 · 型号与 GATT 服务"),
    ("profile_household.png", "05-household.png", "我的 · 家庭与房间"),
    ("profile_scan_logs.png", "06-scan-logs.png", "我的 · 扫描日志"),
]

BG_TOP = (232, 241, 240)
BG_BOTTOM = (248, 251, 251)
TEXT = (13, 59, 54)
ACCENT = (32, 122, 108)


def font(size: int) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(CAPTION_FONT, size)


def gradient() -> Image.Image:
    canvas = Image.new("RGB", (W, H), BG_TOP)
    draw = ImageDraw.Draw(canvas)
    for y in range(H):
        ratio = y / (H - 1)
        color = tuple(
            round(top + (bottom - top) * ratio)
            for top, bottom in zip(BG_TOP, BG_BOTTOM)
        )
        draw.line([(0, y), (W, y)], fill=color)
    # 角落装饰圆，避免纯色背景显得空。
    deco = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(deco)
    d.ellipse([-260, -320, 460, 400], fill=ACCENT + (18,))
    d.ellipse([W - 340, H - 420, W + 260, H + 180], fill=ACCENT + (14,))
    return Image.alpha_composite(canvas.convert("RGBA"), deco).convert("RGB")


def rounded_mask(size: tuple[int, int], radius: int) -> Image.Image:
    mask = Image.new("L", size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, size[0] - 1, size[1] - 1], radius, fill=255)
    return mask


def compose(src: Path, caption: str) -> Image.Image:
    shot = Image.open(src).convert("RGB")
    scale = min(1320 / shot.width, 1560 / shot.height)
    size = (round(shot.width * scale), round(shot.height * scale))
    shot = shot.resize(size, Image.LANCZOS)

    canvas = gradient()
    draw = ImageDraw.Draw(canvas)

    # 顶部标题
    caption_font = font(54)
    bbox = draw.textbbox((0, 0), caption, font=caption_font)
    draw.text(((W - (bbox[2] - bbox[0])) / 2, 96), caption, font=caption_font, fill=TEXT)

    x = (W - size[0]) // 2
    y = 240 + max(0, (1560 - size[1]) // 2)

    # 卡片阴影
    shadow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    ImageDraw.Draw(shadow).rounded_rectangle(
        [x + 6, y + 18, x + size[0] + 6, y + size[1] + 18], 40, fill=(13, 59, 54, 46)
    )
    shadow = shadow.filter(ImageFilter.GaussianBlur(26))
    canvas = Image.alpha_composite(canvas.convert("RGBA"), shadow)

    # 白色卡片 + 真实截图
    card = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    ImageDraw.Draw(card).rounded_rectangle(
        [x - 10, y - 10, x + size[0] + 10, y + size[1] + 10], 40, fill=(255, 255, 255, 255)
    )
    canvas = Image.alpha_composite(canvas, card)

    mask = rounded_mask(size, 30)
    canvas.paste(shot, (x, y), mask)
    return canvas.convert("RGB")


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    for src_name, out_name, caption in SHOTS:
        src = SRC / src_name
        if not src.is_file():
            raise SystemExit(f"缺少源截图：{src}")
        image = compose(src, caption)
        target = OUT / out_name
        image.save(target, "PNG", optimize=True)
        print(f"{target.relative_to(ROOT)} {image.width}x{image.height}")
    print(f"完成：{len(SHOTS)} 张 {W}x{H} 商店截图")


if __name__ == "__main__":
    main()
