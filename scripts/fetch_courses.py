#!/usr/bin/env python3
"""学期理论课表 → scripts/out/courses.json（字段对齐 DESIGN 4.3）。

页面: GET /jsxsd/xskb/xskb_list.do?viweType=0   （强智 newL 模板）

用法:
  .venv-scraper/Scripts/python.exe scripts/fetch_courses.py

输出:
  scripts/out/courses.json        对齐 DESIGN 4.3 的课表 JSON
  scripts/out/courses_raw.json    解析中间产物（含原始 detail 文本，便于排错）
  scripts/out/xskb_vt0.html       页面快照（解析器回归时可当 fixture）

登录与代理注意事项见 jw_session.py 文件头。本脚本只负责"解析"。
"""

from __future__ import annotations

import re
from datetime import datetime
from typing import Any

from bs4 import BeautifulSoup

import jw_session
from jw_session import JW8080, OUT

SCHEDULE_URL = f"{JW8080}/jsxsd/xskb/xskb_list.do?viweType=0"


def parse_weeks(text: str) -> set[int]:
    """解析周次串，如 `1-10` / `1,3,5` / `1-4,6`。"""
    weeks: set[int] = set()
    if not text:
        return weeks
    text = re.sub(r"周次?$", "", text.strip())
    for part in re.split(r"[,，]", text):
        part = part.strip()
        if not part:
            continue
        m = re.fullmatch(r"(\d+)\s*-\s*(\d+)", part)
        if m:
            a, b = int(m.group(1)), int(m.group(2))
            if 1 <= a <= b <= 40:
                weeks.update(range(a, b + 1))
            continue
        m = re.fullmatch(r"(\d+)", part)
        if m:
            v = int(m.group(1))
            if 1 <= v <= 40:
                weeks.add(v)
            continue
        for n in re.findall(r"\d+", part):  # 兜底：从脏 token 里抠数字
            v = int(n)
            if 1 <= v <= 40:
                weeks.add(v)
    return weeks


def parse_detail_span(text: str) -> dict[str, str]:
    """解析 `老师:X;时间:1-10周[1-2节];地点:教学北大楼(北B102)`。"""
    text = re.sub(r"\s+", "", text)
    out = {"teacher": "", "weeks": "", "sections": "", "position": ""}
    m = re.search(r"老师[:：]([^;；]*)", text)
    if m:
        out["teacher"] = m.group(1).strip()
    m = re.search(r"时间[:：]([^;；\[]*)", text)
    if m:
        out["weeks"] = m.group(1).strip()
    m = re.search(r"\[(\d+)\s*-\s*(\d+)\s*节\]", text)
    if m:
        out["sections"] = f"{m.group(1)}-{m.group(2)}"
    m = re.search(r"地点[:：](.+)$", text)
    if m:
        out["position"] = m.group(1).strip("；;")
    return out


def parse_courses(html: str) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    soup = BeautifulSoup(html, "html.parser")  # 强智页面双 doctype，lxml 会丢节点
    meta: dict[str, Any] = {"term": None}

    for opt in soup.select("select#xnxq01id option"):
        if opt.has_attr("selected"):
            meta["term"] = opt.get_text(strip=True)
            break

    items: list[dict[str, Any]] = []
    # 根因：星期只能从课程所在 <td> 在 <tr> 里的列序推（第 0 列是节次标签，第 1–7 列是周一至周日）。
    # li 上的 qz-hasCourse-N 在强智 newL 模板里恒为 1（模板把它当「有课」样式用），不能当星期来源。
    # 列号需累加 colspan，并用 carry 记录 rowspan 的跨行占用：
    # 强智在「一天内连续两大节上同一门课」时会合并单元格，不补偏移的话该行之后的星期会整体前移。
    carry: dict[int, int] = {}
    for tr in soup.select("tbody tr"):
        for key in list(carry):
            carry[key] -= 1
            if carry[key] <= 0:
                del carry[key]
        col = 0
        for td in tr.find_all("td", recursive=False):
            while carry.get(col, 0) > 0:
                col += 1
            rowspan = int(td.get("rowspan") or 1)
            colspan = int(td.get("colspan") or 1)
            if rowspan > 1:
                carry[col] = rowspan
            if td.get("name") == "kbDataTd" and 1 <= col <= 7:
                for li in td.select("li.courselists-item"):
                    title_el = li.select_one("div.qz-hasCourse-title")
                    if not title_el:
                        continue
                    name = title_el.get_text(strip=True)
                    if not name:
                        continue
                    detail = li.select_one("span.qz-hasCourse-abbrinfo")
                    raw = detail.get_text(" ", strip=True) if detail else ""
                    parsed = parse_detail_span(raw)
                    start = end = None
                    if "-" in parsed["sections"]:
                        a, b = parsed["sections"].split("-", 1)
                        start, end = int(a), int(b)
                    items.append(
                        {
                            "name": name,
                            "teacher": parsed["teacher"],
                            "position": parsed["position"],
                            "day": col,
                            "startSection": start or 0,
                            "endSection": end or start or 0,
                            "weeks": sorted(parse_weeks(parsed["weeks"])),
                            "weeks_raw": parsed["weeks"],
                            "detail_raw": raw,
                        }
                    )
            col += colspan
    return items, meta


def assign_colors(items: list[dict[str, Any]], palette: int = 12) -> dict[str, int]:
    """按课程名排序后的名次取色：稳定、可复现，12 门以内不撞色。

    不能用 hash(name)：Python 字符串哈希每个进程都带随机盐，
    同一份课表两次运行会得到不同颜色（旧实现就是这个 bug）。
    """
    names = sorted({it["name"] for it in items if it["name"].strip()})
    return {name: index % palette for index, name in enumerate(names)}


def to_design(items: list[dict[str, Any]]) -> list[dict[str, Any]]:
    out: list[dict[str, Any]] = []
    colors = assign_colors(items)
    seen: set[tuple] = set()
    for it in items:
        key = (
            it["name"], it["day"], it["startSection"], it["endSection"],
            it["teacher"], tuple(it["weeks"]),
        )
        if key in seen:
            continue
        seen.add(key)
        out.append(
            {
                "id": 0,
                "name": it["name"],
                "teacher": it["teacher"],
                "position": it["position"],
                "day": it["day"],
                "startSection": it["startSection"],
                "endSection": it["endSection"],
                "weeks": it["weeks"],
                "isCustomTime": False,
                "customStartTime": None,
                "customEndTime": None,
                "colorIndex": colors.get(it["name"], 0),
            }
        )
    for i, c in enumerate(out, 1):
        c["id"] = i
    return out


def main() -> int:
    import json

    cred = jw_session.load_credentials()
    jw = jw_session.login(cred)

    html = jw_session.get_html(
        jw, SCHEDULE_URL, must_contain="个人课表", save=OUT / "xskb_vt0.html"
    )
    print("[4] 课表 HTML", len(html), "bytes")

    items, meta = parse_courses(html)
    courses = to_design(items)
    payload = {
        "source": "jiaowu.juwp.edu.cn 强智 /jsxsd/xskb/xskb_list.do?viweType=0",
        "term": meta.get("term"),
        "student": cred["username"],
        "exportedAt": datetime.now().isoformat(timespec="seconds"),
        "count": len(courses),
        "courses": courses,
    }
    (OUT / "courses.json").write_text(
        json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    (OUT / "courses_raw.json").write_text(
        json.dumps(items, ensure_ascii=False, indent=2), encoding="utf-8"
    )

    print("[5] 课程数", len(courses), "学期", meta.get("term"))
    for c in courses[:10]:
        print(
            f"    周{c['day']} 第{c['startSection']}-{c['endSection']}节 {c['name']} | "
            f"{c['teacher']} | {c['position']} | 周{c['weeks'][:5]}..."
        )
    print("[done]", OUT / "courses.json")
    return 0 if courses else 2


if __name__ == "__main__":
    raise SystemExit(main())
