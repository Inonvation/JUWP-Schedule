#!/usr/bin/env python3
"""学生教材确认 → scripts/out/textbooks.json。

页面: GET /jsxsd/nxsjc/xsjcqr（layui JSON 接口，参数 xnxqid + pageNum/pageSize）
入口: 教务 → 教材管理 → 学生教材确认（data-id=NEW_XSD_PYGL_NJCGL_XSJCQR，
      菜单 data-src=/jsxsd/nxsjc/jccx 是壳页）

用法:
  .venv-scraper/Scripts/python.exe scripts/fetch_textbooks.py [--term 2025-2026-2]

输出:
  scripts/out/textbooks.json      教材 JSON（顶层 term = 实际爬取的学期）
  scripts/out/textbooks_raw.json  接口原始数据（排错用）

坑（2026-09-28 实测）:
  1) 与考试/成绩同一套 layui 接口：不带 .do、pageNum/pageSize、code=0、按 count 翻页。
     带参数名 `xnxqid`（同考试页，不是课表页的 xnxq01id）。
  2) 字段口径：kcmc=课程名称（与课表课名逐字一致，可直接做关联键）、jcmc=教材名称、
     jczz=主编、isbn、jcdj=定价、jcbc=版次、cbsmc=出版社、skjs=上课教师、zdzt=征订状态。
  3) 壳页里另有 /jsxsd/nxsjc/xsjcisxy.do（征订/不征订确认，POST）——那是学生确认订购的
     写操作，本脚本只读 xsjcqr，绝不触碰。
  4) 空学期参数返回 code=0 count=0；学期无教材（如小学期）属正常。
"""

from __future__ import annotations

import argparse
import json
from datetime import datetime
from typing import Any

from bs4 import BeautifulSoup

import jw_session
from jw_session import JW8080, OUT

JCQR_SHELL = f"{JW8080}/jsxsd/nxsjc/jccx"
JCQR_LIST = f"{JW8080}/jsxsd/nxsjc/xsjcqr"

_PAGE_SIZE = 200  # 单学期教材远小于 200，一页拿全；仍按 count 兜底翻页


def current_term(jw) -> str | None:
    """取学生教材确认壳页学期下拉的默认选中项（即教务眼中的当前学期）。"""
    r = jw.get(JCQR_SHELL, timeout=30)
    soup = BeautifulSoup(r.text, "html.parser")
    sel = soup.select_one("select#xnxqid")
    opt = sel.find("option", selected=True) if sel else None
    return opt.get_text(strip=True) if opt else None


def fetch_page(jw, term: str, page: int) -> dict[str, Any]:
    r = jw.get(
        JCQR_LIST,
        params={"xnxqid": term, "pageNum": page, "pageSize": _PAGE_SIZE},
        timeout=30,
    )
    if "系统功能暂未开放" in r.text:
        raise RuntimeError("教务返回「系统功能暂未开放」：教材查询功能被校方关闭（no-open 页）")
    data = json.loads(r.text)
    if data.get("code") != 0:
        raise RuntimeError(f"接口 code={data.get('code')} msg={data.get('msg')!r}")
    return data


def to_payload(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    out: list[dict[str, Any]] = []
    for row in rows:
        course = (row.get("kcmc") or "").strip()
        book = (row.get("jcmc") or "").strip()
        if not course or not book:
            continue  # 没有教材名称的行（如未定教材）不产出
        out.append(
            {
                "course": course,
                "courseNo": (row.get("kch") or "").strip(),
                "title": book,
                "author": (row.get("jczz") or "").strip(),
                "press": (row.get("cbsmc") or "").strip(),
                "edition": (row.get("jcbc") or "").strip(),
                "isbn": (row.get("isbn") or "").strip(),
                "price": (row.get("jcdj") or "").strip(),
                "teacher": (row.get("skjs") or "").strip(),
                "ordered": (row.get("zdzt") or "").strip(),
            }
        )
    return out


def main() -> int:
    parser = argparse.ArgumentParser(description="抓取学生教材确认")
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

    # 学期口径校验：接口行里带回 xnxq01id，与请求学期不一致说明教务没按参数过滤
    got_terms = {str(r.get("xnxq01id") or "").strip() for r in rows} - {""}
    if got_terms and got_terms != {term}:
        raise RuntimeError(f"请求学期 {term} 与教务返回学期 {sorted(got_terms)} 不一致")

    books = to_payload(rows)
    payload = {
        "source": "jiaowu.juwp.edu.cn 强智 /jsxsd/nxsjc/xsjcqr",
        "term": term,
        "student": cred["username"],
        "exportedAt": datetime.now().isoformat(timespec="seconds"),
        "count": len(books),
        "textbooks": books,
    }
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "textbooks.json").write_text(
        json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    (OUT / "textbooks_raw.json").write_text(
        json.dumps(rows, ensure_ascii=False, indent=2), encoding="utf-8"
    )

    print(f"[5] 学期 {term}，教材 {len(books)} 本（count={total}）")
    for b in books[:10]:
        print(f"    {b['course']} | {b['title']} | {b['author']} | {b['press']} {b['edition']}")
    if not books:
        print("    （本学期没有教材记录：征订未发布或学期无教材属正常）")
    print("[done]", OUT / "textbooks.json")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
