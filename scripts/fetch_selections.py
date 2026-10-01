#!/usr/bin/env python3
"""选课（选课日志 + 选课轮次）→ scripts/out/selections.json。

链路（2026-09-30 实测，**都不带 .do**，layui JSON 接口）:
  选课结果壳页: GET /jsxsd/xkgl/loadXsxkjgList?lx=xkrz                ← 学期下拉在这里
  选课结果数据: GET /jsxsd/xkgl/loadXsxkjgList?lx=xkrz&type=list&xnxqid=<学期>&pageNum=&pageSize=
  选课轮次:     GET /jsxsd/xsxk/xklc_list_data
入口: 教务 → 培养管理 → 选课管理 → 选课结果查询（data-id=NEW_XSD_PYGL_XKGL_XSXKJGCX）
      教务 → 培养管理 → 选课管理 → 学生选课中心（data-id=NEW_XSD_PYGL_XKGL_NXSXKZX）

用法:
  .venv-scraper/Scripts/python.exe scripts/fetch_selections.py
  .venv-scraper/Scripts/python.exe scripts/fetch_selections.py --term 2026-2027-1
  .venv-scraper/Scripts/python.exe scripts/fetch_selections.py --rounds-only

默认抓「教务当前学期 + 紧邻下一学期」（选课常发生在学期末选下学期，光抓当前学期
会漏掉「已选下学期」）。--term 可重复指定。

红线（改脚本前先读）:
  1) 全部只读 GET；`/jsxsd/xkgl/Xsxkjg_tk.do`（申请退课）与 `/jsxsd/xsxk/mzlist.do`
     （免责声明查询）都是 POST，脚本**绝不触碰**，App 也只在 WebView 里由教务页面自己调。
  2) 轮次接口在非选课期返回 count=0 是**正常空态**（选课季才放出轮次），不是失败。
  3) `xksj` 时间字符串的格式在选课期才有样本：本脚本原样输出 `timeText`，
     不猜测解析——App 侧有容错解析，但同样"解析不了就不排提醒"。

输出:
  scripts/out/selections.json   选课记录（按学期分组）+ 轮次 + 学期下拉全量
  scripts/out/selections_raw.json  接口原始数据（排错用）
"""

from __future__ import annotations

import argparse
import json
from datetime import datetime
from typing import Any

from bs4 import BeautifulSoup

import jw_session
from jw_session import JW8080, OUT

SHELL = f"{JW8080}/jsxsd/xkgl/loadXsxkjgList?lx=xkrz"
LIST = f"{JW8080}/jsxsd/xkgl/loadXsxkjgList"
ROUNDS = f"{JW8080}/jsxsd/xsxk/xklc_list_data"

_PAGE_SIZE = 200  # 一学期选课几十条封顶，一页拿全；仍按 count 兜底翻页


def shell_terms(jw) -> tuple[str | None, list[str]]:
    """选课日志壳页：返回 (默认选中学期, 下拉全量学期)。"""
    r = jw.get(SHELL, timeout=30)
    soup = BeautifulSoup(r.text, "html.parser")
    sel = soup.select_one("select#xnxqid")
    if sel is None:
        return None, []
    terms = [o.get_text(strip=True) for o in sel.find_all("option") if o.get_text(strip=True)]
    opt = sel.find("option", selected=True)
    return (opt.get_text(strip=True) if opt else None), terms


def next_term(term: str) -> str | None:
    """2026-2027-1 → 2026-2027-2；2026-2027-2 → 2027-2028-1。"""
    parts = term.strip().split("-")
    if len(parts) != 3 or parts[2] not in ("1", "2") or not parts[0].isdigit() or not parts[1].isdigit():
        return None
    y1, y2 = int(parts[0]), int(parts[1])
    return f"{y1}-{y2}-2" if parts[2] == "1" else f"{y1 + 1}-{y2 + 1}-1"


def fetch_result_page(jw, term: str, page: int) -> dict[str, Any]:
    r = jw.get(
        LIST,
        params={"lx": "xkrz", "type": "list", "xnxqid": term, "pageNum": page, "pageSize": _PAGE_SIZE},
        timeout=30,
    )
    if "系统功能暂未开放" in r.text:
        raise RuntimeError("教务返回「系统功能暂未开放」：选课查询被校方关闭（no-open 页）")
    data = json.loads(r.text)
    # code 有数字（结果接口）与字符串（轮次接口）两种形态：统一按字符串比
    if str(data.get("code")) != "0":
        raise RuntimeError(f"接口 code={data.get('code')!r} msg={data.get('msg')!r}")
    return data


def fetch_term(jw, term: str) -> list[dict[str, Any]]:
    first = fetch_result_page(jw, term, 1)
    rows: list[dict[str, Any]] = list(first.get("data") or [])
    total = int(first.get("count") or 0)
    page = 1
    while len(rows) < total:
        page += 1
        rows += fetch_result_page(jw, term, page).get("data") or []
    return rows


def fetch_rounds(jw) -> list[dict[str, Any]]:
    """选课轮次。非选课期 count=0 是正常空态。**code 是字符串 "0"**（结果接口是数字）。"""
    r = jw.get(ROUNDS, timeout=30)
    data = json.loads(r.text)
    if str(data.get("code")) != "0":
        raise RuntimeError(f"轮次接口 code={data.get('code')!r} msg={data.get('msg')!r}")
    return list(data.get("data") or [])


def to_selection(row: dict[str, Any], term: str) -> dict[str, Any]:
    return {
        "term": term,
        "courseNo": str(row.get("kch") or "").strip(),
        "name": str(row.get("kc_mc") or "").strip(),
        "teacher": str(row.get("xm") or "").strip(),
        "credit": row.get("xf") or 0,
        "hours": row.get("zxs") or 0,
        "attribute": str(row.get("kclb_mc") or "").strip(),
        "category": str(row.get("kcxz_mc") or "").strip(),
        "className": str(row.get("ktmc") or "").strip(),
        "college": str(row.get("yx_mc") or "").strip(),
        # 教务多行字段以 <br> 分隔：这里换成 \n，与 App 侧口径一致
        "timeText": str(row.get("sksj") or "").replace("<br>", "\n").strip(),
        "placeText": str(row.get("skdd") or "").replace("<br>", "\n").strip(),
        "status": str(row.get("shzt") or "").strip(),
        "remark": str(row.get("yy") or "").strip(),
    }


def to_round(row: dict[str, Any]) -> dict[str, Any]:
    return {
        "id": str(row.get("jx0502zbid") or "").strip(),
        "term": str(row.get("xqmc") or "").strip(),
        "name": str(row.get("xklc_mc") or "").strip(),
        # 时间原文原样输出；App 侧容错解析，解析不了就不排提醒
        "timeText": str(row.get("xksj") or "").strip(),
        "canPreview": str(row.get("yxzt") or "") == "1",
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="抓取选课结果与选课轮次")
    parser.add_argument("--term", action="append", help="学年学期，可重复；缺省=当前学期+下一学期")
    parser.add_argument("--rounds-only", action="store_true", help="只抓选课轮次")
    args = parser.parse_args()

    cred = jw_session.load_credentials()
    jw = jw_session.login(cred)

    default_term, all_terms = shell_terms(jw)
    terms = [t.strip() for t in (args.term or []) if t and t.strip()]
    if not terms and not args.rounds_only:
        if not default_term:
            raise RuntimeError("取不到学期：请用 --term 显式指定，如 --term 2026-2027-1")
        terms = [default_term]
        nxt = next_term(default_term)
        if nxt and (not all_terms or nxt in all_terms):
            terms.append(nxt)

    selections: list[dict[str, Any]] = []
    raw: dict[str, Any] = {}
    if not args.rounds_only:
        for term in terms:
            rows = fetch_term(jw, term)
            raw[term] = rows
            selections += [to_selection(r, term) for r in rows if str(r.get("kc_mc") or "").strip()]
            print(f"[4] {term}：选课记录 {len(rows)} 条")

    rounds = fetch_rounds(jw)
    rounds_out = [to_round(r) for r in rounds if str(r.get("jx0502zbid") or "").strip()]
    print(f"[5] 选课轮次：{len(rounds_out)} 条{'（非选课期为空属正常）' if not rounds_out else ''}")

    payload = {
        "source": "jiaowu.juwp.edu.cn 强智 /jsxsd/xkgl/loadXsxkjgList?lx=xkrz",
        "term": default_term or "",
        "student": cred["username"],
        "exportedAt": datetime.now().isoformat(timespec="seconds"),
        "terms": all_terms,
        "count": len(selections),
        "selections": selections,
        "rounds": rounds_out,
    }
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "selections.json").write_text(
        json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    (OUT / "selections_raw.json").write_text(
        json.dumps(raw, ensure_ascii=False, indent=2), encoding="utf-8"
    )

    for s in selections[:10]:
        print(f"    {s['term']} {s['name']} | {s['attribute']}/{s['category']} | {s['credit']} 学分")
    if not selections:
        print("    （本学期暂无选课记录）")
    print("[done]", OUT / "selections.json")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
