#!/usr/bin/env python3
"""S115 E2E fixture 复壮 · OCR 样图生成器（纯测试资产，不涉产品代码）。

背景（S114 §5.1 已定性）：``ocr_kb_e2e_test`` 的默认样图 ``pivotos-tmp/ocr-sample-s95.png``
是 S95（Windows 会话）时代产物，**本机零命中**（``pivotos-tmp/*.png`` 为空）——fixture 丢失 ≠ 代码回归。

复壮口径：**不让 fixture 再以文件形态存在**。
    样图内容（``LINES``）在脚本内以常量固化，运行时用 Pillow 渲染成 PNG 写
    ``pivotos-tmp/ocr-sample-s115.png``；文件缺失/损坏时按需重生成。
    这样即便 pivotos-tmp 再次被清空（它不在任何 git 仓内，四个仓都不跟踪），
    E2E 也能自愈，不会第三次复现「样图丢失」。

小图优先：``SMALL_SIZE`` 为 S96 K2（tess4j Invalid memory access）口径下的最小验证图，
裂隙排查时可用 ``python3 ocr_sample.py --small`` 先验证原生链路再上全尺寸图。
"""
import os
import sys

from PIL import Image, ImageDraw, ImageFont

# 样图内已知文案（与 ocr_kb_e2e_test 的 KEYWORDS / SEARCH_QUERY 对齐）
LINES = [
    "PivotOS 旧系统迁移功能",
    "枢盘 智能迁移平台 迁移计划",
    "扫描件 OCR 入库与检索测试",
]
KEYWORDS_HINT = ["PivotOS", "枢盘", "迁移"]  # ≥1 命中即判定 OCR 生效（脚本口径）

DEFAULT_OUT = os.path.normpath(os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "..", "..", "..", "pivotos-tmp", "ocr-sample-s115.png"))

FONT_CANDIDATES = [
    "/System/Library/Fonts/PingFang.ttc",
    "/System/Library/Fonts/Supplemental/Songti.ttc",
    "/System/Library/Fonts/Supplemental/Arial Unicode.ttf",
    "/System/Library/Fonts/Supplemental/Heiti TC.ttc",
    "/usr/share/fonts/truetype/arphic/uming.ttc",
    "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
]


def pick_font(size):
    for path in FONT_CANDIDATES:
        if os.path.exists(path):
            try:
                return ImageFont.truetype(path, size)
            except OSError:
                continue
    return ImageFont.load_default(size)


def render(path=DEFAULT_OUT, width=1200, line_height=96, font_size=64, lines=None):
    """渲染白底黑字样图并落盘，返回文件路径。"""
    lines = lines or LINES
    height = line_height * len(lines) + 60
    img = Image.new("RGB", (width, height), "white")
    draw = ImageDraw.Draw(img)
    font = pick_font(font_size)
    y = 30
    for line in lines:
        draw.text((40, y), line, fill="black", font=font)
        y += line_height
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path, "PNG")
    return path


def ensure_sample(path=DEFAULT_OUT):
    """样图存在且可正常解码则直接返回，否则重新渲染（fixture 自愈）。"""
    if os.path.exists(path):
        try:
            with Image.open(path) as im:
                im.load()
            return path
        except Exception:  # noqa: BLE001 —— 损坏即重建
            pass
    return render(path)


if __name__ == "__main__":
    if "--small" in sys.argv:
        # S96 K2 口径：小图先验 OCR 原生链路，通过后再上全尺寸图
        small_lines = ["PivotOS"]
        out = render(DEFAULT_OUT.replace(".png", "-small.png"),
                     width=400, line_height=70, font_size=48, lines=small_lines)
    else:
        out = ensure_sample()
    print(out)
