#!/usr/bin/env python3
"""考试安排 → scripts/out/exams.json。

页面: GET /jsxsd/xsks/xsksap_list（layui JSON 接口）
入口: 教务 → 考试报名 → 我的考试 → 考试安排查询（data-id=NEW_XSD_KSBM_WDKS_KSAPCX）

用法:
  .venv-scraper/Scripts/python.exe scripts/fetch_exams.py [--term 2025-2026-2]

输出:
  scripts/out/exams.json          考试安排 JSON（顶层 term = 实际爬取的学期）
  scripts/out/exams_raw.json      接口原始数据（排错用）

坑（2026-09-19 实测）:
  1) 数据接口不带 .do；/jsxsd/xsks/xsksap_query 是壳页（layui 表格，url 在
     <table data-url> 属性里）。带 .do 的同名地址返回「系统功能暂未开放」no-open 页，
     不要混淆「功能被校方关闭」与「本学期暂无考试安排」（后者 code=0 count=0）。
  2) 分页参数名是 pageNum / pageSize（window.initQzTable 自定义），不是 page/limit。
  3) 考试时间 kssj 是单一字符串 "2026-05-18 08:30~09:55"，本脚本拆出 date/startTime/endTime。
  4) 考试安排由教务考前数周录入，平时查询 count=0 属正常。
"""

from __future__ import annotations

import argparse
import json
import re
from datetime import datetime
from typing import Any

from bs4 import BeautifulSoup

import jw_session
from jw_session import JW8080, OUT

KSAP_QUERY = f"{JW8080}/jsxsd/xsks/xsksap_query"
KSAP_LIST = f"{JW8080}/jsxsd/xsks/xsksap_list"

_PAGE_SIZE = 200  # 单学期考试远小于 200，一页拿全；仍按 count 兜底翻页

_TIME_RE = re.compile(
    r"(\d{4}-\d{2}-\d{2})\s+(\d{2}:\d{2})\s*[~～\-—]\s*(\d{2}:\d{2})"
)


def current_term(jw) -> str | None:
    """取考试安排查询页学期下拉的默认选中项（即教务眼中的当前学期）。"""
    r = jw.get(KSAP_QUERY, timeout=30)
    soup = BeautifulSoup(r.text, "html.parser")
    sel = soup.select_one("select#xnxqid")
    opt = sel.find("option", selected=True) if sel else None
    return opt.get_text(strip=True) if opt else None


def fetch_page(jw, term: str, page: int) -> dict[str, Any]:
    r = jw.get(
        KSAP_LIST,
        params={"xnxqid": term, "xqlb": "", "pageNum": page, "pageSize": _PAGE_SIZE},
        timeout=30,
    )
    if "系统功能暂未开放" in r.text:
        raise RuntimeError("教务返回「系统功能暂未开放」：考试查询功能被校方关闭（no-open 页）")
    data = json.loads(r.text)
    if data.get("code") != 0:
        raise RuntimeError(f"接口 code={data.get('code')} msg={data.get('msg')!r}")
    return data


def parse_time(raw: str) -> dict[str, str]:
    m = _TIME_RE.search(raw or "")
    if not m:
        return {"date": "", "startTime": "", "endTime": ""}
    return {"date": m.group(1), "startTime": m.group(2), "endTime": m.group(3)}


def to_payload(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    out: list[dict[str, Any]] = []
    for row in rows:
        t = parse_time(str(row.get("kssj") or ""))
        out.append(
            {
                "courseNo": (row.get("kch") or "").strip(),
                "name": (row.get("kskcmc") or "").strip(),
                "teacher": (row.get("jsxm") or "").strip(),
                "room": (row.get("js_mc") or "").strip(),
                "campus": (row.get("ksxq") or row.get("xqmc") or "").strip(),
                "date": t["date"],
                "startTime": t["startTime"],
                "endTime": t["endTime"],
                "timeRaw": (row.get("kssj") or "").strip(),
                "seatNo": str(row.get("zwh") or "").strip(),
                "sessionNo": (row.get("ksccmc") or "").strip(),
            }
        )
    return out


def main() -> int:
    parser = argparse.ArgumentParser(description="抓取考试安排")
    parser.add_argument("--term", help="学年学期，如 2025-2026-2；缺省取教务当前学期")
    args = parser.parse_args()

    cred = jw_session.load_credentials()
    jw = jw_session.login(cred)

    term = (args.term or "").strip() or current_term(jw) or ""
    if not term:
        raise RuntimeError("取不到学期：请用 --term 显式指定，如 --term 2026-2027-1")

    data = fetch_page(jw, term, 1)
    rows: list[dict[str, Any]] = list(data.get("data") or [])
    total = int(data.get("count") or 0)
    page = 1
    while len(rows) < total:
        page += 1
        rows += fetch_page(jw, term, page).get("data") or []

    exams = to_payload(rows)
    payload = {
        "source": "jiaowu.juwp.edu.cn 强智 /jsxsd/xsks/xsksap_list",
        "term": term,
        "student": cred["username"],
        "exportedAt": datetime.now().isoformat(timespec="seconds"),
        "count": len(exams),
        "exams": exams,
    }
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "exams.json").write_text(
        json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    (OUT / "exams_raw.json").write_text(
        json.dumps(rows, ensure_ascii=False, indent=2), encoding="utf-8"
    )

    print(f"[5] 学期 {term}，考试 {len(exams)} 场（count={total}）")
    for e in exams[:10]:
        print(
            f"    {e['date']} {e['startTime']}~{e['endTime']} {e['name']} | "
            f"{e['room']} | 座位{e['seatNo'] or '—'}"
        )
    if not exams:
        print("    （本学期暂无考试安排：教务通常考前数周才录入）")
    print("[done]", OUT / "exams.json")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
