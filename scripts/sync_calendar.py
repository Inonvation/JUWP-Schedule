#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""校历镜像同步：教务处官网（免登录公网）→ 仓库镜像 docs-public/calendar/（第 10 个正式脚本）。

背景（2026-10-07 定案）：校历在学校侧查过三条入口——
  ① 智慧校园移动端（m.juwp.edu.cn）：发现链路绑微信一次性 JWT，走不通；
  ② PC 门户（portal.juwp.edu.cn）：日历是个人日历，返回空数据；
  ③ **教务处官网通知公告（jw.juwp.edu.cn）：免登录、公网发布高清原图** ← 本脚本走这条。
App 侧仍拉免登录的仓库镜像（不请求学校站点），本脚本只负责把镜像维护自动化。

流程：
  1) 扫通知公告列表（`/xxgk/tzgg.htm` 及翻页 `tzgg/N.htm`），找「20XX-20XX学年校历」文章；
  2) 取文章内高清原图（泛微 CMS：优先 `orisrc`，回落 `__local` 的 src）；
  3) 与 `docs-public/calendar/index.json` 比对（学年 + 文件名 + 内容 sha256）——没变静默退出；
  4) 变了才：落图 → 改写 index.json → `git add/commit/push`（只动 docs-public/calendar/）。

用法（在仓库根跑）：
  .\\.venv-scraper\\Scripts\\python.exe scripts\\sync_calendar.py                    # 全自动
  .\\.venv-scraper\\Scripts\\python.exe scripts\\sync_calendar.py --dry-run          # 只报差异
  .\\.venv-scraper\\Scripts\\python.exe scripts\\sync_calendar.py --no-push          # 落盘+提交，不推送
  .\\.venv-scraper\\Scripts\\python.exe scripts\\sync_calendar.py `
      --from-file D:\\图\\校历.png --year 2027-2028                                 # 救急：手动给图

纪律：免登录、只读页面、单次请求不重试；push 前 `pull --ff-only` 拉齐；分支不是 main 就拒绝。
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import re
import subprocess
import sys
from pathlib import Path

import requests

BASE = "https://jw.juwp.edu.cn"
UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
)
REPO = Path(__file__).resolve().parent.parent          # 仓库根（scripts/ 的上一级）
CAL_DIR = REPO / "docs-public" / "calendar"
INDEX = CAL_DIR / "index.json"
MAX_LIST_PAGES = 5          # 通知公告扫前 5 页（每周跑一次，新文章必在第一页出现）
MIN_IMAGE_WIDTH = 800       # 落盘前的分辨率下限（挡住图标/缩略图误落）


def new_session() -> requests.Session:
    # trust_env=False：本机 shell 注入了 HTTP(S)_PROXY，校园站点必须直连
    s = requests.Session()
    s.trust_env = False
    s.headers["User-Agent"] = UA
    return s


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def page_text(r: requests.Response) -> str:
    """站点 Content-Type 不带 charset（requests 会猜成 ISO-8859-1），统一显式解码。"""
    for enc in ("utf-8", "gb18030"):
        try:
            return r.content.decode(enc)
        except UnicodeDecodeError:
            continue
    return r.content.decode("utf-8", errors="replace")


def read_index() -> dict:
    if not INDEX.exists():
        return {}
    try:
        return json.loads(INDEX.read_text(encoding="utf-8"))
    except Exception:
        return {}


# ---------- 发现：通知公告里找「学年校历」文章 ----------

def find_calendar_article(s: requests.Session) -> tuple[str, str] | None:
    """扫通知公告前几页，返回 (文章绝对 URL, 学年) —— 多条时取学年最大的一条。"""
    best: tuple[str, str] | None = None
    pages = [f"{BASE}/xxgk/tzgg.htm"] + [
        f"{BASE}/xxgk/tzgg/{i}.htm" for i in range(1, MAX_LIST_PAGES)
    ]
    for page in pages:
        r = s.get(page, timeout=15)
        if r.status_code != 200:
            continue
        # 条目形如：<a href="../info/1040/10376.htm"><h3>江西水利电力大学2026-2027学年校历</h3>
        for href, title in re.findall(r'href="([^"]+)"[^>]*>\s*(?:<[^>]+>\s*)?([^<]*?20\d{2}-20\d{2}学年校历[^<]*)', page_text(r)):
            m = re.search(r"(20\d{2})-(20\d{2})学年校历", title)
            if not m:
                continue
            year = f"{m.group(1)}-{m.group(2)}"
            url = requests.compat.urljoin(page, href)
            if best is None or year > best[1]:
                best = (url, year)
    return best


def extract_image_url(s: requests.Session, article_url: str) -> str | None:
    """从文章页取高清原图 URL：优先 orisrc（泛微编辑器原图），回落 __local 的 src。"""
    r = s.get(article_url, timeout=15)
    if r.status_code != 200:
        return None
    text = page_text(r)
    m = re.search(r'orisrc="([^"]+)"', text)
    if not m:
        m = re.search(r'src="(/__local/[^"]+)"', text)
    if not m:
        return None
    return requests.compat.urljoin(article_url, m.group(1))


# ---------- 比对与落盘 ----------

def validate_and_ext(data: bytes, url: str) -> str | None:
    """校验是张像样的图并返回扩展名（png/jpg）；不合格返回 None。"""
    from PIL import Image  # 延迟导入：报错路径更清楚

    try:
        img = Image.open(io.BytesIO(data))
        if img.width < MIN_IMAGE_WIDTH:
            print(f"  ！图太小（{img.width}×{img.height}），疑似图标，拒绝落盘")
            return None
        fmt = (img.format or "").lower()
    except Exception as e:
        print(f"  ！下载内容不是可解码的图：{e}")
        return None
    if fmt in ("jpeg", "jpg") or url.lower().endswith((".jpg", ".jpeg")):
        return "jpg"
    if fmt == "png" or url.lower().endswith(".png"):
        return "png"
    print(f"  ！未知图片格式：{fmt or url}")
    return None


def apply_update(
    year: str, data: bytes, ext: str, article_or_note: str, *, dry_run: bool, push: bool
) -> int:
    """落图 + 改写 index.json + git 提交（可选推送）；dry_run 时什么都不写。"""
    target = CAL_DIR / f"calendar_{year}.{ext}"
    if dry_run:
        print(
            f"  [dry-run] 将落盘 {target.relative_to(REPO)}（{len(data)}B）、改写 index.json、"
            f"提交（{'并推送' if push else '不推送'}）——本次全部跳过"
        )
        return 0

    CAL_DIR.mkdir(parents=True, exist_ok=True)
    tmp = target.with_suffix(target.suffix + ".tmp")
    tmp.write_bytes(data)
    tmp.replace(target)

    INDEX.write_text(
        json.dumps({"year": year, "file": target.name}, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(f"  落盘：{target.relative_to(REPO)}（{len(data)}B）")
    print(f"  清单：index.json → year={year} file={target.name}")

    def git(*args: str) -> subprocess.CompletedProcess:
        return subprocess.run(
            ["git", "-C", str(REPO), *args],
            capture_output=True, text=True, encoding="utf-8", errors="replace",
        )

    branch = git("rev-parse", "--abbrev-ref", "HEAD").stdout.strip()
    if branch != "main":
        print(f"  ！当前分支是 {branch}，拒绝自动提交（只允许 main）")
        return 1
    pull = git("pull", "--ff-only")
    if pull.returncode != 0:
        print(f"  ！git pull --ff-only 失败，先人工处理：\n{pull.stderr.strip()}")
        return 1
    add = git("add", "docs-public/calendar")
    if add.returncode != 0:
        print(f"  ！git add 失败：{add.stderr.strip()}")
        return 1
    commit = git("commit", "-m", f"chore(calendar): 同步 {year} 学年校历（{article_or_note}）")
    if commit.returncode != 0:
        print(f"  ！git commit 失败：{commit.stderr.strip()}{commit.stdout.strip()}")
        return 1
    print("  已提交：" + commit.stdout.strip().splitlines()[0])
    if push:
        pushed = git("push", "origin", "main")
        if pushed.returncode != 0:
            print(f"  ！git push 失败（提交已在本地，可稍后手动推）：\n{pushed.stderr.strip()}")
            return 1
        print("  已推送 origin/main")
    return 0


# ---------- 主流程 ----------

def main() -> int:
    ap = argparse.ArgumentParser(description="校历镜像同步（教务处官网 → docs-public/calendar）")
    ap.add_argument("--dry-run", action="store_true", help="只报差异，不写文件不提交")
    ap.add_argument("--no-push", action="store_true", help="落盘并提交，但不 push")
    ap.add_argument("--from-file", metavar="PATH", help="救急：用本地图片代替官网抓取")
    ap.add_argument("--year", help="配合 --from-file 指定学年，如 2027-2028")
    args = ap.parse_args()

    cur = read_index()
    print(f"仓库镜像现状：year={cur.get('year', '（无）')} file={cur.get('file', '（无）')}")

    if args.from_file:
        if not args.year or not re.fullmatch(r"20\d{2}-20\d{2}", args.year):
            print("！--from-file 需要 --year 20XX-20XX")
            return 1
        data = Path(args.from_file).read_bytes()
        ext = validate_and_ext(data, args.from_file)
        if not ext:
            return 1
        year = args.year
    else:
        s = new_session()
        found = find_calendar_article(s)
        if not found:
            print(f"！通知公告前 {MAX_LIST_PAGES} 页没有找到「学年校历」文章（栏目可能改版）")
            return 1
        article_url, year = found
        print(f"发现文章：{year} 学年校历 -> {article_url}")
        img_url = extract_image_url(s, article_url)
        if not img_url:
            print("！文章里没有解析到图片（编辑结构可能改了）")
            return 1
        print(f"高清原图：{img_url}")
        r = s.get(img_url, timeout=60)
        if r.status_code != 200:
            print(f"！图片下载失败 http={r.status_code}")
            return 1
        data = r.content
        ext = validate_and_ext(data, img_url)
        if not ext:
            return 1
        article_or_note = "教务处官网"

    target_name = f"calendar_{year}.{ext}"
    new_sha = sha256_bytes(data)
    existing = CAL_DIR / cur.get("file", "")
    old_sha = sha256_bytes(existing.read_bytes()) if existing.is_file() else None
    print(f"目标文件：{target_name}（{len(data)}B, sha256={new_sha[:16]}…）")
    print(f"现版文件：{cur.get('file', '（无）')}（sha256={(old_sha or '（无）')[:16] if old_sha else '（无）'}…）")

    if cur.get("year") == year and cur.get("file") == target_name and old_sha == new_sha:
        print("已是最新，无需更新。")
        return 0

    print("检出变化，准备更新。")
    return apply_update(
        year, data, ext, article_or_note,
        dry_run=args.dry_run,
        push=not args.no_push,
    )


if __name__ == "__main__":
    sys.exit(main())
