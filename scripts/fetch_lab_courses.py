#!/usr/bin/env python3
"""实验课表 → scripts/out/lab_courses.json（字段对齐 DESIGN 4.3 + kind="lab"）。

页面: GET /jsxsd/syjx/toXskb.do
入口: 教务 → 实践实验 → 实验课表查询（菜单 data-id = NEW_XSD_PYGL_WDKB_SYKBCX）

用法:
  .venv-scraper/Scripts/python.exe scripts/fetch_lab_courses.py [--term 2025-2026-2]

输出:
  scripts/out/lab_courses.json    聚合后的实验课次（顶层 term = 实际爬到的学期）
  scripts/out/syxkb.html          页面快照（解析器回归时可当 fixture）

页面结构（2026-09-17 实测，与其他课表页完全不同，勿套用 fetch_courses 的规则）:
  table.qz-weeklyTable 表头 9 列：周次 | 节次 | 星期一 … 星期日（7 天，含周日）
  每个周次占 6 行：
    首行 9 个 td = [周次标签 rowspan=6][节次标签][星期一 … 星期日]
    其余 5 行 8 个 td = [节次标签][星期一 … 星期日]
  有课单元格 = td.qz-weeklyTable-td.qz-hasCourse.qz-mixrow

  与理论课表的两处关键差异：
    1) 没有 td[name=kbDataTd]，也没有「老师:X;时间:Y;地点:Z」这种合并文本，
       qz-hasCourse-abbrinfo 里只有地点 —— 抽取逻辑与字段正则都不可复用。
    2) 周次不在课块里，而在它所属的「周次行分组」上，必须向上找 rowspan=6 的标签单元格。
  另：tooltip 里的「节次：60304」是页面内部编码，不是真实节次，不要用它。
"""

from __future__ import annotations

import json
import re
from datetime import datetime
from pathlib import Path
from typing import Any

from bs4 import BeautifulSoup

import jw_session
from jw_session import LAB_SCHEDULE, OUT

DAY_CN = ["周一", "周二", "周三", "周四", "周五", "周六", "周日"]
BASE_TD_COUNT = 8  # 「节次标签 + 7 天」的基准列数；周次首行多一列


def parse_sections(text: str) -> tuple[int, int] | None:
    """`3-4` → (3, 4)；`11` → (11, 11)。"""
    t = text.strip()
    m = re.fullmatch(r"(\d+)\s*-\s*(\d+)", t)
    if m:
        return int(m.group(1)), int(m.group(2))
    m = re.fullmatch(r"(\d+)", t)
    if m:
        v = int(m.group(1))
        return v, v
    return None


def _cells(tr) -> list:
    tds = tr.find_all("td", recursive=False)
    return tds if tds else tr.find_all("td")


def parse_lab_courses(html: str) -> tuple[list[dict[str, Any]], list[dict[str, Any]], dict[str, Any]]:
    """返回 (原始课块, 聚合后的课次, 元信息)。"""
    soup = BeautifulSoup(html, "html.parser")
    table = soup.select_one("table.qz-weeklyTable")
    if table is None:
        raise ValueError("未找到 table.qz-weeklyTable，页面结构可能已变")

    meta: dict[str, Any] = {"term": None}
    for opt in soup.select("select#xnxq01id option"):
        if opt.has_attr("selected"):
            meta["term"] = opt.get_text(strip=True)
            break

    tbody = table.select_one("tbody.qz-weeklyTable-thbody") or table.select_one("tbody")
    if tbody is None:
        raise ValueError("未找到课表 tbody")

    raw: list[dict[str, Any]] = []
    week: int | None = None
    for tr in tbody.find_all("tr"):
        tds = _cells(tr)
        if not tds:
            continue

        # 周次：只在每个周次块的首行出现（首列带 rowspan 的标签）
        first = tds[0]
        if first.get("rowspan") and "qz-weeklyTable-label" in (first.get("class") or []):
            txt = first.get_text(strip=True)
            week = int(txt) if txt.isdigit() else None

        # 节次标签：周次首行在 idx 1，其余行在 idx 0
        sec = parse_sections(tds[1 if len(tds) == BASE_TD_COUNT + 1 else 0].get_text(strip=True))

        for idx, td in enumerate(tds):
            if "qz-hasCourse" not in (td.get("class") or []):
                continue
            # 列序 → 星期：多出的那一列是周次标签，故按行形态右对齐
            day = idx - (len(tds) - BASE_TD_COUNT)
            if not (1 <= day <= 7) or week is None or sec is None:
                continue
            for li in td.select("li.courselists-item"):
                title = li.select_one(".qz-hasCourse-title")
                pos = li.select_one(".qz-hasCourse-detailitem")
                name = title.get_text(" ", strip=True) if title else ""
                if not name:
                    continue
                raw.append(
                    {
                        "name": name,
                        "position": pos.get_text(" ", strip=True) if pos else "",
                        "day": day,
                        "week": week,
                        "startSection": sec[0],
                        "endSection": sec[1],
                    }
                )

    # 聚合：同一门课会在每个有课的周次各出现一块，合并为一个 weeks 并集。
    # 键必须含 position：工程训练按批次分周，同名课可能在不同实训室
    # （实测「机械制造基础A」分布在 212 / 105 / 403 三个实训室）。
    merged: dict[tuple, set[int]] = {}
    for r in raw:
        key = (r["name"], r["day"], r["startSection"], r["endSection"], r["position"])
        merged.setdefault(key, set()).add(r["week"])

    colors: dict[str, int] = {}
    for name in sorted({k[0] for k in merged}):
        colors[name] = len(colors) % 12

    courses: list[dict[str, Any]] = []
    for key, weeks in sorted(merged.items(), key=lambda kv: (kv[0][1], kv[0][2], kv[0][0])):
        name, day, start, end, pos = key
        courses.append(
            {
                "id": len(courses) + 1,
                "name": name,
                "teacher": "",  # 实验课表页不提供教师字段
                "position": pos,
                "day": day,
                "startSection": start,
                "endSection": end,
                "weeks": sorted(weeks),
                "isCustomTime": False,
                "customStartTime": None,
                "customEndTime": None,
                "colorIndex": colors.get(name, 0),
                "kind": "lab",
            }
        )
    return raw, courses, meta


def main() -> int:
    import argparse

    parser = argparse.ArgumentParser(description="抓取实验课表")
    parser.add_argument("--term", help="学年学期，如 2025-2026-2；缺省取教务当前学期")
    args = parser.parse_args()

    cred = jw_session.load_credentials()
    jw = jw_session.login(cred)

    url = LAB_SCHEDULE if not args.term else f"{LAB_SCHEDULE}?xnxq01id={args.term}"
    html = jw_session.get_html(jw, url, must_contain="实验课表", save=OUT / "syxkb.html")
    print("[4] 实验课表 HTML", len(html), "bytes")

    raw, courses, meta = parse_lab_courses(html)
    got = meta.get("term")
    # 口径一致性：教务忽略未知学期参数时下拉仍停在当前学期，输出口径必须等于实际爬到的学期
    if args.term and got != args.term:
        raise RuntimeError(
            f"请求学期 {args.term} 与教务返回学期 {got} 不一致（学期参数可能未被接受）"
        )
    payload = {
        "source": "jiaowu.juwp.edu.cn 强智 /jsxsd/syjx/toXskb.do",
        "term": meta.get("term"),
        "student": cred["username"],
        "exportedAt": datetime.now().isoformat(timespec="seconds"),
        "count": len(courses),
        "rawBlockCount": len(raw),
        "note": "实验课表不提供教师字段，teacher 恒为空串",
        "courses": courses,
    }
    (OUT / "lab_courses.json").write_text(
        json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    (OUT / "lab_courses_raw.json").write_text(
        json.dumps(raw, ensure_ascii=False, indent=2), encoding="utf-8"
    )

    print(f"[5] 原始课块 {len(raw)} → 聚合 {len(courses)} 条，学期 {meta.get('term')}")
    for c in courses:
        print(
            f"    {DAY_CN[c['day'] - 1]} 第{c['startSection']}-{c['endSection']}节 "
            f"{c['name']} | {c['position']} | 周{c['weeks']}"
        )
    print("[done]", OUT / "lab_courses.json")
    return 0 if courses else 2


if __name__ == "__main__":
    raise SystemExit(main())
