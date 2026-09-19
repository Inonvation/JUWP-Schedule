#!/usr/bin/env python3
"""课程成绩 → scripts/out/scores.json。

页面: GET /jsxsd/kscj/cjcx_list（layui JSON 接口）
入口: 教务 → 学籍成绩 → 我的成绩 → 课程成绩查询（data-id=NEW_XSD_XJCJ_WDCJ_KCCJCX）

用法:
  .venv-scraper/Scripts/python.exe scripts/fetch_scores.py [--term 2025-2026-2]

输出:
  scripts/out/scores.json         成绩 JSON（term = 查询学期；缺省查全部时 term=null）
  scripts/out/scores_raw.json     接口原始数据（排错用）

坑（2026-09-19 实测）:
  1) 数据接口不带 .do；带 .do 的 cjcx_list.do 返回「系统功能暂未开放」no-open 页。
  2) 分页参数名是 pageNum / pageSize；查询参数 kksj（开课学期，空 = 全部学期）。
  3) 成绩双字段：zcj 数值 + zcjstr 字符串（等级制成绩如「优」时 zcj 为空/非数），
     取值一律以 zcjstr 为展示口径，zcj 仅用于数值统计。
  4) kz=1 表示「请评教」：评教未完成时对应课程成绩被教务锁定，不展示分数。
"""

from __future__ import annotations

import argparse
import json
from datetime import datetime
from typing import Any

import jw_session
from jw_session import JW8080, OUT

CJCX_LIST = f"{JW8080}/jsxsd/kscj/cjcx_list"

_PAGE_SIZE = 200


def fetch_page(jw, term: str, page: int) -> dict[str, Any]:
    r = jw.get(
        CJCX_LIST,
        params={"kksj": term, "kcxz": "", "kcsx": "", "kcmc": "", "xsfs": "",
                "pageNum": page, "pageSize": _PAGE_SIZE},
        timeout=30,
    )
    if "系统功能暂未开放" in r.text:
        raise RuntimeError("教务返回「系统功能暂未开放」：成绩查询功能被校方关闭（no-open 页）")
    data = json.loads(r.text)
    if data.get("code") != 0:
        raise RuntimeError(f"接口 code={data.get('code')} msg={data.get('msg')!r}")
    return data


def to_payload(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    out: list[dict[str, Any]] = []
    for row in rows:
        kz = str(row.get("kz") or "0")
        out.append(
            {
                "term": (row.get("xnxqid") or "").strip(),
                "courseNo": (row.get("kch") or "").strip(),
                "name": (row.get("kc_mc") or "").strip(),
                "unit": (row.get("ksdw") or "").strip(),
                "credit": row.get("xf"),
                "hours": row.get("zxs"),
                "examForm": (row.get("ksfs") or "").strip(),   # 考试 / 考查
                "courseAttr": (row.get("kcsx") or "").strip(),  # 必修 / 选修
                "category": (row.get("kcxzmc") or "").strip(),  # 课程性质（通识必修课等）
                "scoreStr": (row.get("zcjstr") or "").strip(),  # 展示口径（兼容等级制）
                "score": row.get("zcj"),                        # 数值口径（等级制时为空）
                "gradePoint": row.get("jd"),
                "status": (row.get("ksxz") or "").strip(),      # 正常考试 / 补考…
                "pendingReview": kz == "1",                     # 请评教，成绩被锁定
            }
        )
    return out


def main() -> int:
    parser = argparse.ArgumentParser(description="抓取课程成绩")
    parser.add_argument("--term", help="学年学期，如 2025-2026-2；缺省抓全部学期")
    args = parser.parse_args()

    cred = jw_session.load_credentials()
    jw = jw_session.login(cred)

    term = (args.term or "").strip()
    data = fetch_page(jw, term, 1)
    rows: list[dict[str, Any]] = list(data.get("data") or [])
    total = int(data.get("count") or 0)
    page = 1
    while len(rows) < total:
        page += 1
        rows += fetch_page(jw, term, page).get("data") or []

    scores = to_payload(rows)
    terms = sorted({s["term"] for s in scores if s["term"]})
    payload = {
        "source": "jiaowu.juwp.edu.cn 强智 /jsxsd/kscj/cjcx_list",
        "term": term or None,
        "terms": terms,
        "student": cred["username"],
        "exportedAt": datetime.now().isoformat(timespec="seconds"),
        "count": len(scores),
        "scores": scores,
    }
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "scores.json").write_text(
        json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    (OUT / "scores_raw.json").write_text(
        json.dumps(rows, ensure_ascii=False, indent=2), encoding="utf-8"
    )

    scope = term if term else f"全部学期（{len(terms)} 个：{'、'.join(terms[-3:])} 等）"
    locked = sum(1 for s in scores if s["pendingReview"])
    print(f"[5] {scope}，成绩 {len(scores)} 条（count={total}，其中 {locked} 条评教未完成被锁定）")
    for s in scores[:10]:
        print(
            f"    {s['term']} {s['name']} | {s['scoreStr']}（绩点 {s['gradePoint']}）| "
            f"{s['credit']}学分 | {s['category']}"
        )
    print("[done]", OUT / "scores.json")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
