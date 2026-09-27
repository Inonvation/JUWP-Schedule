#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""从方形图标源图（白底蓝色日历 + 水滴）生成自适应图标前景层 PNG。

用法（仓库根目录）：
    .venv-scraper\\Scripts\\python.exe tools\\app-icon\\make_foreground.py
    # 可选：--src 换源图、--glyph-dp 换图形大小、--out 换输出根目录
    # 控制台是 GBK 时中文输出会花屏，前面加 PYTHONIOENCODING=utf-8

为什么要有这个脚本：`res/drawable-*/ic_launcher_foreground.png` 是位图，不是矢量
（源图是蓝色渐变，复刻成 VectorDrawable 要手写渐变节点，收益不抵风险）。位图没法在
仓库里"看见"生成的规则，源图大小或图形尺寸一变就得整体重导，所以把提取与缩放口径
固化成脚本，连同源图一起入库。

几何口径（决定图形在桌面上的大小，改前先读）：
    * 层画布 = 108dp，框架绘制时把前景/背景各放大 1.5 倍再按遮罩裁切，
      所以 108dp 画布中间 72dp 才是"可见区"，外边 18dp 是留给视差/效果的余量。
    * 图形（墨迹外接框）在画布里固定 51dp，占可见区 51/72 ≈ 71%，与源图"图形占
      方块 70%"一致（源图方块 x19..615、图形 x106..523）。
    * 上限由遮罩决定：官方口径是图形留在画布中间直径 66dp 的圆内（半径 33dp）。
      源图图形的外接圆 = 260px / 422px 边长 × 图形边长，51dp 时 ≈ 31.5dp，
      换算到遮罩坐标（× 1.5）≈ 47dp，仍在 Pixel 圆遮罩半径 54dp 之内，日历角
      不会被切；再大就顶出安全圆。

提取口径：源图是白底上的蓝色图形，按"蓝度 = B - R"估不透明度，再按白底反预乘还原
原色 —— 这样前景叠回白底能逐像素还原源图（实测最大偏差 12/255，仅出现在被砍掉的
阴影雾上）。低于 CUT 的一律判为背景，用来清掉源图里那层淡淡的投影，否则它会变成
一层糊在图标上的半透明蓝晕，monochrome（主题图标）下尤其明显。
"""

import argparse
import os

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))

CANVAS_DP = 108.0   # 自适应图标前景层画布尺寸
GLYPH_DP = 51.0     # 图形墨迹外接框在画布里的目标边长（上限见 MAX_GLYPH_DP）
MAX_GLYPH_DP = 51.0  # 再大墨迹外接圆会顶出官方 66dp 安全圆
BLUE_DIV = 64.0     # 蓝度归一化基准：源图里最浅的墨色 B - R = 64
CUT = 0.10          # 低于此不透明度视为背景
INK = 0.35          # 判定墨迹的不透明度阈值（只用来取外接框）
PAD = 6             # 外接框外扩像素，保住图形边缘的抗锯齿
DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}


def extract(src):
    """白底反预乘提取：返回 (RGBA 图, 墨迹宽, 墨迹高)。"""
    a = np.asarray(Image.open(src).convert("RGB")).astype(np.float64)
    blue = a[:, :, 2] - a[:, :, 0]
    alpha = np.clip(blue / BLUE_DIV, 0.0, 1.0)
    alpha = np.clip((alpha - CUT) / (1.0 - CUT), 0.0, 1.0)
    with np.errstate(divide="ignore", invalid="ignore"):
        rgb = np.where(alpha[..., None] > 1e-6,
                       (a - (1.0 - alpha[..., None]) * 255.0) / np.maximum(alpha[..., None], 1e-6),
                       0.0)
    rgba = np.dstack([np.clip(rgb, 0, 255), alpha * 255.0]).round().astype(np.uint8)
    im = Image.fromarray(rgba, "RGBA")
    ys, xs = np.where(alpha > INK)
    box = (max(0, xs.min() - PAD), max(0, ys.min() - PAD),
           min(im.width, xs.max() + 1 + PAD), min(im.height, ys.max() + 1 + PAD))
    return im.crop(box), xs.max() - xs.min() + 1, ys.max() - ys.min() + 1


def resize_pma(im, size):
    """预乘 alpha 的 LANCZOS 缩放：直接缩 RGBA 会让半透明边缘渗出白边。"""
    a = np.asarray(im).astype(np.float32) / 255.0
    pm = np.concatenate([a[..., :3] * a[..., 3:4], a[..., 3:4]], axis=2)
    out = [np.asarray(Image.fromarray(pm[..., i], "F").resize(size, Image.LANCZOS),
                      dtype=np.float32) for i in range(4)]
    pm = np.stack(out, axis=2)
    al = np.clip(pm[..., 3:4], 0.0, 1.0)
    with np.errstate(divide="ignore", invalid="ignore"):
        rgb = np.where(al > 1e-6, pm[..., :3] / np.maximum(al, 1e-6), 0.0)
    out = np.concatenate([np.clip(rgb, 0, 1), al], axis=2)
    return Image.fromarray((out * 255.0).round().astype(np.uint8), "RGBA")


def foreground(glyph, ink_w, ink_h, glyph_dp, scale):
    side = int(round(CANVAS_DP * scale))
    s = (glyph_dp * scale) / max(ink_w, ink_h)
    g = resize_pma(glyph, (max(1, round(glyph.width * s)), max(1, round(glyph.height * s))))
    fg = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    fg.paste(g, ((side - g.width) // 2, (side - g.height) // 2), g)
    return fg


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--src", default=os.path.join(HERE, "source.png"), help="源图（默认同目录 source.png）")
    ap.add_argument("--res", default=os.path.join(REPO, "app", "src", "main", "res"), help="res 目录")
    ap.add_argument("--glyph-dp", type=float, default=GLYPH_DP, help="图形外接框边长（dp），上限约 51")
    args = ap.parse_args()

    if args.glyph_dp > MAX_GLYPH_DP + 1e-9:
        raise SystemExit(f"glyph-dp 超过 {MAX_GLYPH_DP:g}dp，墨迹外接圆会顶出安全圆，日历角有被圆遮罩切掉的风险")

    glyph, ink_w, ink_h = extract(args.src)
    print(f"源图 {args.src}\n墨迹外接框 {ink_w}x{ink_h}px，裁剪后 {glyph.size}，图形 {args.glyph_dp}dp")
    for name, scale in DENSITIES.items():
        d = os.path.join(args.res, "drawable-" + name)
        os.makedirs(d, exist_ok=True)
        fg = foreground(glyph, ink_w, ink_h, args.glyph_dp, scale)
        p = os.path.join(d, "ic_launcher_foreground.png")
        fg.save(p)
        print(f"  {p} {fg.size[0]}x{fg.size[1]}")


if __name__ == "__main__":
    main()
