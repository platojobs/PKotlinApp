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

# 六张图的设备框统一宽度：以 1080 宽源截图缩放到 695px，外框再加 10px 白边；
# 圆角固定为外框 40px / 屏幕 30px，避免 03/04/05 裁短后与 02/06 宽度不一致。
FRAME_WIDTH = 695
FRAME_MAX_HEIGHT = 1560

# (源截图, 输出文件名, 文案, 底部裁剪高度)。文案只写该截图里实际可见的元素：
# home 只有今日概览与设备状态（无设备卡片）；device_detail 只有曲线与汇总（无阈值告警）；
# products 只有设备类型目录（无 GATT 服务表）；profile_household 只有家庭与成员行（无房间行）。
# 裁剪高度用于去掉底部被 Tab 条切断的半句/半张卡片；None 表示保留完整截图。
SHOTS = [
    ("home.png", "01-home.png", "首页 · 今日概览与设备状态", None),
    ("device_detail.png", "02-device-detail.png", "设备详情 · 历史曲线与 24 小时汇总", None),
    ("scenes.png", "03-scenes.png", "智能 · 本机场景一键执行", 2000),
    ("products.png", "04-products.png", "产品 · 设备类型目录", 2000),
    ("profile_household.png", "05-household.png", "我的 · 家庭与成员", 2040),
    ("profile_scan_logs.png", "06-scan-logs.png", "我的 · 扫描日志", None),
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


def compose(src: Path, caption: str, crop_bottom=None) -> Image.Image:
    shot = Image.open(src).convert("RGB")
    if crop_bottom is not None:
        shot = shot.crop((0, 0, shot.width, crop_bottom))
    scale = FRAME_WIDTH / shot.width
    if round(shot.height * scale) > FRAME_MAX_HEIGHT:
        scale = FRAME_MAX_HEIGHT / shot.height
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


def fit_font(text: str, max_width: int, start: int) -> ImageFont.FreeTypeFont:
    size = start
    while size > 12:
        candidate = font(size)
        bbox = ImageDraw.Draw(Image.new("RGB", (1, 1))).textbbox((0, 0), text, font=candidate)
        if bbox[2] - bbox[0] <= max_width:
            return candidate
        size -= 1
    return font(size)


def make_feature_graphic() -> Image.Image:
    """Google Play Feature Graphic：1024×500，只含应用名、真实卖点与设计令牌配色。"""
    width, height = 1024, 500
    # 设计令牌（app/src/main/res/values/colors.xml）
    surface = (255, 255, 255)
    chart_band = (232, 239, 251)
    primary = (23, 92, 211)
    line_2 = (122, 90, 248)
    text_primary = (16, 24, 40)
    text_secondary = (71, 84, 103)
    text_tertiary = (95, 106, 125)

    canvas = Image.new("RGB", (width, height), surface)
    draw = ImageDraw.Draw(canvas)
    for y in range(height):
        ratio = y / (height - 1)
        draw.line(
            [(0, y), (width, y)],
            fill=tuple(round(a + (b - a) * ratio) for a, b in zip(surface, chart_band)),
        )

    # 右侧：抽象信号柱 + 弧线（不使用任何界面截图，避免虚构 UI）
    layer = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    for radius in (150, 196, 242):
        d.arc([724 - radius, 352 - radius, 724 + radius, 352 + radius], 270, 360,
              fill=primary + (46,), width=3)
    baseline = 352
    for index, bar_h in enumerate((66, 98, 130, 162, 196)):
        x = 724 + index * 58
        color = line_2 if index == 4 else primary
        d.rounded_rectangle([x, baseline - bar_h, x + 34, baseline], 12, fill=color + (255,))
    canvas = Image.alpha_composite(canvas.convert("RGBA"), layer).convert("RGB")
    draw = ImageDraw.Draw(canvas)

    # 左侧：标签、应用名、真实卖点
    draw.rounded_rectangle([72, 74, 320, 124], 24, fill=chart_band)
    draw.text((92, 84), "BLE · 本机优先", font=font(23), fill=primary)
    draw.text((72, 158), "BLE 设备数据面板",
              font=fit_font("BLE 设备数据面板", 640, 60), fill=text_primary)
    draw.text((72, 252), "扫描连接 BLE 设备",
              font=fit_font("扫描连接 BLE 设备", 640, 33), fill=text_secondary)
    draw.text((72, 298), "实时读数与本地告警",
              font=fit_font("实时读数与本地告警", 640, 33), fill=text_secondary)
    draw.line([(72, 380), (676, 380)], fill=(208, 213, 221), width=2)
    draw.text((72, 402), "无需账号密码 · 本地优先，支持匿名同步",
              font=fit_font("无需账号密码 · 本地优先，支持匿名同步", 640, 24), fill=text_tertiary)
    return canvas


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    for src_name, out_name, caption, crop_bottom in SHOTS:
        src = SRC / src_name
        if not src.is_file():
            raise SystemExit(f"缺少源截图：{src}")
        image = compose(src, caption, crop_bottom)
        target = OUT / out_name
        image.save(target, "PNG", optimize=True)
        print(f"{target.relative_to(ROOT)} {image.width}x{image.height}")
    graphic = make_feature_graphic()
    graphic_target = OUT / "feature-graphic.png"
    graphic.save(graphic_target, "PNG", optimize=True)
    print(f"{graphic_target.relative_to(ROOT)} {graphic.width}x{graphic.height}")
    print(f"完成：{len(SHOTS)} 张 {W}x{H} 商店截图 + 1 张 1024x500 Feature Graphic")


if __name__ == "__main__":
    main()
