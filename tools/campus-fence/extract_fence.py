#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""校区围栏（BikeNearby.CAMPUS_FENCE）的路网中心线提取与复核工具（DESIGN §3.9 / §4.23）。

背景：围栏四条边沿四条界路走——西=天祥大道、北=瑶湖西二路、东=瑶湖西大道、
南=瑶湖西一路。2026-09-27 首版凭官方小程序截图做相似变换配准，四条边整体偏
30~70 米（南边还把校园内部的"南缘路"当成了瑶湖西一路），被用户真机逐段指认后
改为"从高德瓦片按路面色带逐列提取中心线"才对准。规则（.agents/rules/ebike.md）：
要改顶点就重新按路网中心线提取，别目测挪——跑这个脚本。

它做四件事：
  1. 抓 z17 高德瓦片拼整图（scl=2 高清，缓存在 _tiles/，可反复跑）；
  2. 四条界路按扫描线找"路面色带"中心（黄=主干道，白=次干道），用内置期望
     折线挑正确的那条（期望值取现行围栏的边，不是先验坐标）；
  3. 解四个路口的交点，打印可直接粘进 BikeNearby.kt 的 GcjPoint 建议值；
  4. 与现行 CAMPUS_FENCE 对账（每个顶点到最近界路的距离），并把测试 fixture
     （app/src/test/resources/campus_fence_bikes_20260927.txt）的实测车辆点
     全量跑一遍点内判定，打印栏外的点。

跑法（用仓库的 .venv-scraper，自带 Pillow）：
  .\\.venv-scraper\\Scripts\\python.exe tools\\campus-fence\\extract_fence.py

坐标系：瓦片、车辆坐标、围栏顶点全是 GCJ-02，不涉及任何转换。
瓦片接口是灰色用法（不接官方 SDK、不申请 key）；接口失败时多试几次或换 wprd0X。
"""

import io
import math
import re
import urllib.request
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[2]
CACHE_DIR = Path(__file__).resolve().parent / "_tiles"
PREVIEW_PATH = CACHE_DIR / "preview_fence.png"

Z = 17
# 覆盖四条界路的范围（含两端路口）：lat_min, lng_min, lat_max, lng_max
BBOX = (28.6826, 116.0195, 28.6988, 116.0410)
TILE_PX = 512  # scl=2 高清瓦片
URL = ("https://wprd0{idx}.is.autonavi.com/appmaptile"
       "?x={x}&y={y}&z={z}&lang=zh_cn&size=1&scl=2&style=7")

M_PER_DEG_LAT = 110574.0
M_PER_DEG_LNG = 97644.0  # 28.69°N 处 1 经度 ≈ 97.6 公里


# ---------------------------------------------------------------- 瓦片与坐标
def tile_xy(lat, lng, z):
    n = 2 ** z
    x = int((lng + 180.0) / 360.0 * n)
    y = int((1.0 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2.0 * n)
    return x, y


def lat_top(y, z):
    return math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * y / (2 ** z)))))


def ensure_mosaic():
    """抓瓦片拼整图；返回 (Image, geo)，geo 用于像素<->经纬度换算。"""
    CACHE_DIR.mkdir(parents=True, exist_ok=True)
    lat_min, lng_min, lat_max, lng_max = BBOX
    x0, y0 = tile_xy(lat_max, lng_min, Z)
    x1, y1 = tile_xy(lat_min, lng_max, Z)
    xs = list(range(x0, x1 + 1))
    ys = list(range(y0, y1 + 1))
    geo = {
        "left": xs[0] / (2 ** Z) * 360 - 180,
        "px_per_lng": 360.0 / (2 ** Z) / TILE_PX,
        "rows_top": [lat_top(y, Z) for y in ys],
        "rows_bottom": [lat_top(y + 1, Z) for y in ys],
    }
    canvas = Image.new("RGB", (TILE_PX * len(xs), TILE_PX * len(ys)), "white")
    for ix, x in enumerate(xs):
        for iy, y in enumerate(ys):
            f = CACHE_DIR / f"z{Z}_{x}_{y}.png"
            if not f.exists():
                req = urllib.request.Request(
                    URL.format(idx=1 + (ix + iy) % 4, x=x, y=y, z=Z),
                    headers={"User-Agent": "edu.jxslu.schedule/campus-fence-tool"})
                f.write_bytes(urllib.request.urlopen(req, timeout=25).read())
                print(f"  下载 {f.name}")
            canvas.paste(Image.open(io.BytesIO(f.read_bytes())).convert("RGB"),
                         (ix * TILE_PX, iy * TILE_PX))
    return canvas, geo


def to_px(geo, lat, lng):
    px = (lng - geo["left"]) / geo["px_per_lng"]
    for i, (t, b) in enumerate(zip(geo["rows_top"], geo["rows_bottom"])):
        if b <= lat <= t:
            return px, i * TILE_PX + (t - lat) / (t - b) * TILE_PX
    return px, 0.0 if lat > geo["rows_top"][0] else len(geo["rows_top"]) * TILE_PX


def to_geo(geo, x, y):
    lng = geo["left"] + x * geo["px_per_lng"]
    row = min(max(int(y // TILE_PX), 0), len(geo["rows_top"]) - 1)
    t, b = geo["rows_top"][row], geo["rows_bottom"][row]
    lat = t - (y - row * TILE_PX) / TILE_PX * (t - b)
    return lat, lng


def dist_m(lat1, lng1, lat2, lng2):
    return math.hypot((lat1 - lat2) * M_PER_DEG_LAT, (lng1 - lng2) * M_PER_DEG_LNG)


def polyline_dist_m(lat, lng, poly):
    best = 1e18
    for p, q in zip(poly, poly[1:]):
        ax, ay = p[1] * M_PER_DEG_LNG, p[0] * M_PER_DEG_LAT
        bx, by = q[1] * M_PER_DEG_LNG, q[0] * M_PER_DEG_LAT
        px, py = lng * M_PER_DEG_LNG, lat * M_PER_DEG_LAT
        dx, dy = bx - ax, by - ay
        if dx == 0 and dy == 0:
            t = 0.0
        else:
            t = max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)))
        best = min(best, math.hypot(px - (ax + t * dx), py - (ay + t * dy)))
    return best


# ---------------------------------------------------------------- 色带判定
def is_yellow(c):
    r, g, b = c[:3]
    return r > 232 and 185 < g < 253 and 60 < b < 215 and (r - b) > 60 and (g - b) > 35


def is_road_white(c):
    r, g, b = c[:3]
    return r > 244 and g > 240 and b > 228 and abs(r - g) < 9 and 0 <= (g - b) <= 30


PREDS = {"yellow": is_yellow, "roadwhite": is_road_white}


def runs_on_line(img, horizontal, fixed, a_px, b_px, pred, max_w):
    """在一条扫描线上找满足谓词的连通段；horizontal=True 表示固定 y 扫 x。"""
    out = []
    i = a_px
    while i < b_px:
        if pred(img.getpixel((i, fixed) if horizontal else (fixed, i))):
            j = i
            while j + 1 < b_px and pred(img.getpixel((j + 1, fixed) if horizontal else (fixed, j + 1))):
                j += 1
            w = j - i + 1
            if 4 <= w <= max_w:
                out.append((i, j))
            i = j + 1
        else:
            i += 1
    return out


# ---------------------------------------------------------------- 四条界路
# 期望折线：取现行围栏的边（(lat, lng)）。改顶点前先跑一遍，把差异打印出来再决定。
EXPECTED = {
    "天祥大道": [(28.6860, 116.02352), (28.6870, 116.02393), (28.6882, 116.02442),
                 (28.6894, 116.02493), (28.6906, 116.02537), (28.6918, 116.02585),
                 (28.6930, 116.02632), (28.6940, 116.02674), (28.6948, 116.02700),
                 (28.6960, 116.02751)],
    "瑶湖西二路": [(28.69604, 116.02751), (28.69581, 116.02880), (28.69548, 116.03000),
                   (28.69515, 116.03120), (28.69486, 116.03240), (28.69450, 116.03360),
                   (28.69416, 116.03480), (28.69388, 116.03600), (28.69344, 116.03752)],
    "瑶湖西大道": [(28.69344, 116.03752), (28.69240, 116.03732), (28.69120, 116.03720),
                   (28.68960, 116.03729), (28.68800, 116.03759), (28.68640, 116.03782),
                   (28.68520, 116.03801), (28.68440, 116.03813), (28.68393, 116.03821)],
    "瑶湖西一路": [(28.68393, 116.03821), (28.68401, 116.03700), (28.68417, 116.03460),
                   (28.68440, 116.03220), (28.68462, 116.03000), (28.68478, 116.02860),
                   (28.68500, 116.02520), (28.68504, 116.02388)],
}

# 色带中心 -> 围栏所贴"路中心线"的标定偏移（纬度，度）。2026-09-27 校准：
# 北边扫到的白带是路侧带，路中心在其以北约 14 米（对照路口红绿灯位置验证过）；
# 其余三条直接用色带中心，残差 ≤ 7 米。
OFFSET_LAT = {"天祥大道": 0.0, "瑶湖西二路": +0.00013, "瑶湖西大道": 0.0, "瑶湖西一路": +0.00003}

# 双幅路（带中分带的黄线主干道）在同一条扫描线上会出现两条色带，出现间隔小于
# 该值就按"两幅的中点"取心（米）；0 = 不合并。
MERGE_M = {"天祥大道": 60.0, "瑶湖西大道": 60.0, "瑶湖西二路": 0.0, "瑶湖西一路": 0.0}

ROADS = {
    "天祥大道":   dict(pred="yellow",    axis="row", win=(116.0210, 116.0295), max_w=90,
                       scanlines=[round(28.6862 + i * 0.0012, 6) for i in range(9)]),
    "瑶湖西二路": dict(pred="roadwhite", axis="col", win=(28.6932, 28.6962), max_w=80,
                       scanlines=[round(116.0276 + i * 0.0012, 6) for i in range(9)]),
    "瑶湖西大道": dict(pred="yellow",    axis="row", win=(116.0355, 116.0402), max_w=90,
                       scanlines=[round(28.6832 + i * 0.0008, 6) for i in range(15)]),
    "瑶湖西一路": dict(pred="roadwhite", axis="col", win=(28.6832, 28.6857), max_w=80,
                       scanlines=[round(116.0240 + i * 0.0012, 6) for i in range(12)]),
}


def interp(poly, kind, want):
    """折线插值：kind='lat' 按纬度找经度；kind='lng' 按经度找纬度。poly 为 (lat, lng)。
    超出两端时按**端点线段的方向线性外推**——扫描线不进入路口/环岛（那里一个色带
    糊一大片），交点要靠两段外推线去解。"""
    pts = sorted(poly, key=lambda p: p[0] if kind == "lat" else p[1])
    key = (lambda p: p[0]) if kind == "lat" else (lambda p: p[1])
    val = (lambda p: p[1]) if kind == "lat" else (lambda p: p[0])

    def line_extrapolate(p, q):
        k0, k1 = key(p), key(q)
        t = 0.0 if k1 == k0 else (want - k0) / (k1 - k0)
        return val(p) + t * (val(q) - val(p))

    if want <= key(pts[0]) and len(pts) >= 2:
        return line_extrapolate(pts[0], pts[1])
    for p, q in zip(pts, pts[1:]):
        if key(p) <= want <= key(q):
            t = 0 if key(q) == key(p) else (want - key(p)) / (key(q) - key(p))
            return val(p) + t * (val(q) - val(p))
    if want >= key(pts[-1]) and len(pts) >= 2:
        return line_extrapolate(pts[-2], pts[-1])
    return val(pts[-1])


def extract_road(img, geo, name):
    cfg = ROADS[name]
    expected = EXPECTED[name]
    points = []
    for v in cfg["scanlines"]:
        if cfg["axis"] == "row":
            x0, y = to_px(geo, v, cfg["win"][0])
            x1, _ = to_px(geo, v, cfg["win"][1])
            runs = runs_on_line(img, True, int(round(y)), int(round(x0)), int(round(x1)),
                                PREDS[cfg["pred"]], cfg["max_w"])
            want = interp(expected, "lat", v)
            center = lambda r: to_geo(geo, (r[0] + r[1]) / 2, 0)[1]
            pos = lambda r: (v, center(r))
        else:
            x, y1 = to_px(geo, cfg["win"][0], v)
            _, y0 = to_px(geo, cfg["win"][1], v)
            runs = runs_on_line(img, False, int(round(x)), int(round(y0)), int(round(y1)),
                                PREDS[cfg["pred"]], cfg["max_w"])
            want = interp(expected, "lng", v)
            center = lambda r: to_geo(geo, 0, (r[0] + r[1]) / 2)[0] + OFFSET_LAT[name]
            pos = lambda r: (center(r), v)
        if not runs:
            print(f"  ! {name} @ {round(v, 5)}: 扫描线没有色带，跳过")
            continue
        best = min(runs, key=lambda r: abs(center(r) - want))
        lo, hi = best
        if MERGE_M[name] > 0:
            for r in runs:
                if r is best:
                    continue
                rp, bp = pos(r), pos(best)
                if dist_m(rp[0], rp[1], bp[0], bp[1]) <= MERGE_M[name]:
                    lo, hi = min(lo, r[0]), max(hi, r[1])
        if cfg["axis"] == "row":
            lat, lng = v, to_geo(geo, (lo + hi) / 2, 0)[1]
        else:
            lat = to_geo(geo, 0, (lo + hi) / 2)[0] + OFFSET_LAT[name]
            lng = v
        points.append((round(lat, 6), round(lng, 6)))
        note = f"（{len(runs)} 条色带取期望最近）" if len(runs) > 1 else ""
        print(f"  {name} @ {round(v, 5)}: ({lat:.6f}, {lng:.6f}){note}")
    return points


def intersect(lat_lng_road, lng_lat_road, guess):
    """两折线交点：不动点迭代（a 按纬度取经度，b 按经度取纬度）。"""
    lat, lng = guess
    for _ in range(8):
        lng = interp(lat_lng_road, "lat", lat)
        lat = interp(lng_lat_road, "lng", lng)
    return round(lat, 6), round(lng, 6)


def parse_fence():
    src = (ROOT / "app/src/main/java/edu/jxslu/schedule/domain/BikeNearby.kt").read_text(encoding="utf-8")
    block = re.search(r"CAMPUS_FENCE: List<GcjPoint> = listOf\((.*?)\n    \)", src, re.S)
    if not block:
        raise SystemExit("没在 BikeNearby.kt 里找到 CAMPUS_FENCE 列表")
    return [(float(a), float(b)) for a, b in re.findall(r"GcjPoint\(([\d.]+), ([\d.]+)\)", block.group(1))]


def pip(lat, lng, poly):
    inside = False
    j = len(poly) - 1
    for i in range(len(poly)):
        (alat, alng), (blat, blng) = poly[i], poly[j]
        if (alng > lng) != (blng > lng):
            x = (blat - alat) * (lng - alng) / (blng - alng) + alat
            if lat < x:
                inside = not inside
        j = i
    return inside


def read_fixture():
    f = ROOT / "app/src/test/resources/campus_fence_bikes_20260927.txt"
    pts = []
    for line in f.read_text(encoding="utf-8").splitlines():
        s = line.strip()
        if s and not s.startswith("#"):
            a, b = s.split()
            pts.append((float(a), float(b)))
    return pts


def main():
    print("[1/4] 抓取 / 复用瓦片，拼 z17 高清底图 ...")
    img, geo = ensure_mosaic()
    print(f"  底图 {img.size[0]}x{img.size[1]}（缓存于 {CACHE_DIR}）")

    print("[2/4] 按路面色带提取四条界路中心线 ...")
    roads = {name: extract_road(img, geo, name) for name in ROADS}

    print("[3/4] 四个路口（相邻界路交点）建议值：")
    nw = intersect(roads["天祥大道"], roads["瑶湖西二路"], (28.6960, 116.0275))
    ne = intersect(roads["瑶湖西大道"], roads["瑶湖西二路"], (28.6934, 116.0375))
    se = intersect(roads["瑶湖西大道"], roads["瑶湖西一路"], (28.6839, 116.0382))
    sw = intersect(roads["天祥大道"], roads["瑶湖西一路"], (28.6852, 116.0237))
    print(f"    西北 · 天祥大道×瑶湖西二路：GcjPoint({nw[0]:.6f}, {nw[1]:.6f}),")
    print(f"    东北 · 瑶湖西二路×瑶湖西大道：GcjPoint({ne[0]:.6f}, {ne[1]:.6f}),")
    print(f"    东南 · 瑶湖西一路×瑶湖西大道：GcjPoint({se[0]:.6f}, {se[1]:.6f}),")
    print(f"    西南（环岛区，仅参考；围栏实际在环岛两侧各取一枚顶点）：({sw[0]:.6f}, {sw[1]:.6f})")

    print("[4/4] 对账现行围栏 ...")
    fence = parse_fence()
    # 阈值 25 米：四个角点落在路口/环岛里，"取在路口的哪一侧"本身有 ±20 米级的
    # 自由度，中段顶点才是真正该贴死在路上的（应 ≲10 米）。
    print(f"  CAMPUS_FENCE 现行 {len(fence)} 个顶点，到最近界路的距离：")
    warn = 0
    for i, (lat, lng) in enumerate(fence, 1):
        name, d = min(((n, polyline_dist_m(lat, lng, p)) for n, p in roads.items()),
                      key=lambda kv: kv[1])
        flag = "  <<< 偏" if d > 25 else ""
        warn += 1 if d > 25 else 0
        print(f"    #{i:02d} ({lat:.6f}, {lng:.6f}) -> 最近「{name}」 {d:.0f} 米{flag}")
    fixture = read_fixture()
    outside = [p for p in fixture if not pip(p[0], p[1], fence)]
    print(f"  测试 fixture 实测车辆 {len(fixture)} 点：栏外 {len(outside)} 个 {outside if outside else ''}")

    print("渲染预览图 ...")
    scale = 0.5
    prev = img.resize((int(img.width * scale), int(img.height * scale)), Image.LANCZOS)
    d = ImageDraw.Draw(prev, "RGBA")

    def tp(lat, lng):
        x, y = to_px(geo, lat, lng)
        return x * scale, y * scale

    for name, poly in roads.items():
        d.line([tp(la, ln) for la, ln in poly], fill=(30, 90, 220, 255), width=3)
    pts = [tp(la, ln) for la, ln in fence]
    d.line(pts + [pts[0]], fill=(220, 30, 30, 255), width=3)
    for la, ln in fixture:
        x, y = tp(la, ln)
        d.ellipse([x - 3, y - 3, x + 3, y + 3], fill=(0, 130, 0, 255))
    for lat, lng in (nw, ne, se):
        x, y = tp(lat, lng)
        d.ellipse([x - 7, y - 7, x + 7, y + 7], outline=(200, 0, 200, 255), width=3)
    prev.save(PREVIEW_PATH)
    print(f"  已保存 {PREVIEW_PATH}")
    print()
    print(f"结论：顶点距界路 >25 米的 {warn} 个；fixture 栏外 {len(outside)} 个。")


if __name__ == "__main__":
    main()
