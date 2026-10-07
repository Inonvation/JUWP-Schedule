#!/usr/bin/env python3
"""培养方案「课程设置总表」→ scripts/out/curriculum.json（+ 按学期可读清单）。

页面: GET /jsxsd/pyfa/topyfamx（服务端渲染整页 HTML）
入口: 教务（强智）→ 培养方案；与手机端 jiaowuh5「标准版」「课程设置总表」同源数据。
      无参数 = 本账号对应培养方案（由会话绑定的学生决定），页面标题即「X、课程设置总表」。

字段（表头逐列）:
  体系 / 选课组 / 课程编号 / 课程名称 / 课程性质 / 课程属性 / 学分 /
  理论学时 / 实践学时 / 讲座学时 / 实验学时 / 总学时 / 开设学期
开设学期是 1–8 的建议修读学期（1-2 大一上/下、3-4 大二上/下、5-6 大三上/下、7-8 大四上/下）。

用法:
  .venv-scraper/Scripts/python.exe scripts/fetch_curriculum.py

输出:
  scripts/out/curriculum.json     课程列表（每门带 term，扁平数组，消费方自行分组）
  scripts/out/curriculum.md       按学年 / 学期的可读清单（核对用）
  scripts/out/curriculum_raw.html 页面快照（排错用）

坑（2026-10-07 实测）:
  1) 学时 5 个子列在 DOM 里 class 全同（cols-9-2-1），只靠**行内出现顺序**区分
     （理论 / 实践 / 讲座 / 实验 / 总）；解析必须按顺序取，别按 class 名找。
  2) 该页是登录即可见的套餐页面，不必也不应走 jiaowuh5（移动端 H5，443 直连不通）
     或智慧校园（微信一次性 JWT，那条链是校历排查时的死路，与教务无关）。
  3) 实践类学时可能是「2(周)」这类文本，原样保留字符串。
  4) 「开设学期」是培养方案**建议修读学期**（计划口径），不等于实际开课/选课安排。
"""

from __future__ import annotations

import argparse
import html as html_mod
import json
import re
from datetime import datetime

import jw_session
from jw_session import JW8080, OUT

TOPYFAMX = f"{JW8080}/jsxsd/pyfa/topyfamx"

# 学时子列在 DOM 里 class 相同，按出现顺序对应中文表头
_HOUR_KEYS = ("theory", "practice", "lecture", "experiment", "total")
_YEAR_LABELS = ("大一", "大二", "大三", "大四")
_TERM_LABELS = {
    1: "大一上", 2: "大一下", 3: "大二上", 4: "大二下",
    5: "大三上", 6: "大三下", 7: "大四上", 8: "大四下",
}


def _text(s: str) -> str:
    s = re.sub(r"<[^>]+>", " ", s)
    return re.sub(r"\s+", " ", html_mod.unescape(s)).strip()


def _cell(block: str, cls: str) -> str:
    m = re.search(r'<div class="[^"]*' + cls + r'"[^>]*>(.*?)</div>', block, re.S)
    return _text(m.group(1)) if m else ""


def parse_curriculum(raw_html: str) -> dict:
    """解析「三、课程设置总表」：体系（tixi-cell）与课程行（son-table-tr）按文档顺序归并。"""
    # 方案名在页首（「一、培养目标」/「二、详细说明」区），必须全页搜——正文切片里没有
    m = re.search(r'class="item-area-content-inner"[^>]*>(.*?)</p>', raw_html, re.S)
    plan = _text(m.group(1)) if m else ""
    if not plan:
        m = re.search(r'class="header-item-title"[^>]*>(.*?)</span>', raw_html, re.S)
        plan = _text(m.group(1)) if m else ""

    i = raw_html.find("三、课程设置总表")
    if i < 0:
        raise RuntimeError("页面里找不到「三、课程设置总表」——页面结构可能已变，看快照")
    body = raw_html[i:]

    events: list[tuple[int, str, str]] = []
    for m in re.finditer(r'class="tixi-cell-text"[^>]*>(.*?)</div>', body, re.S):
        events.append((m.start(), "tixi", _text(m.group(1))))
    for m in re.finditer(
        r'<div class="son-table-tr">(.*?)(?=<div class="son-table-tr">|<div class="body-table-|<footer|$)',
        body, re.S,
    ):
        events.append((m.start(), "row", m.group(1)))
    events.sort(key=lambda e: e[0])

    courses: list[dict] = []
    tixi = ""
    for _, kind, val in events:
        if kind == "tixi":
            tixi = val
            continue
        name = _cell(val, "cols-5")
        course_no = _cell(val, "cols-4")
        if not name and not course_no:
            continue  # 汇总行 / 空行
        hours_raw = [_text(x) for x in re.findall(r'cols-9-2-1"[^>]*>(.*?)</div>', val, re.S)]
        hours = dict(zip(_HOUR_KEYS, hours_raw[:5]))
        term_raw = _cell(val, "cols-10")
        try:
            term = int(term_raw)
        except ValueError:
            term = 0
        credit_raw = _cell(val, "cols-8")
        try:
            credit = float(credit_raw)
        except ValueError:
            credit = None
        courses.append(
            {
                "term": term,                                  # 1–8；0 = 未标注
                "courseNo": course_no,
                "name": name,
                "kind": _cell(val, "cols-6"),                  # 课程性质（通识必修课…）
                "attr": _cell(val, "cols-7"),                  # 课程属性（必修/选修…）
                "credit": credit,
                "hours": hours,                                # 理论/实践/讲座/实验/总，原样
                "tixi": tixi,                                  # 体系（通识限选课…）
            }
        )
    return {"plan": plan, "courses": courses}


def render_markdown(plan: str, courses: list[dict]) -> str:
    lines = [f"# {plan or '培养方案'}", ""]
    total_credit = sum(c["credit"] or 0 for c in courses)
    lines.append(f"共 {len(courses)} 门课 · 合计 {total_credit:g} 学分")
    lines.append("")
    for yi, label in enumerate(_YEAR_LABELS):
        t1, t2 = yi * 2 + 1, yi * 2 + 2
        sel = [c for c in courses if c["term"] in (t1, t2)]
        if not sel:
            continue
        credit = sum(c["credit"] or 0 for c in sel)
        lines.append(f"## {label}（第 {t1}–{t2} 学期，{len(sel)} 门 · {credit:g} 学分）")
        lines.append("")
        for t in (t1, t2):
            rows = [c for c in sel if c["term"] == t]
            if not rows:
                continue
            credit = sum(c["credit"] or 0 for c in rows)
            lines.append(f"### 第 {t} 学期 · {_TERM_LABELS[t]}（{len(rows)} 门 · {credit:g} 学分）")
            lines.append("")
            lines.append("| 课程编号 | 课程名称 | 课程性质 | 属性 | 学分 | 理论/实践/讲座/实验/总学时 |")
            lines.append("|----------|----------|----------|------|------|----------------------------|")
            for c in rows:
                h = c["hours"]
                hv = "/".join(h.get(k, "") or "" for k in _HOUR_KEYS)
                lines.append(
                    f"| {c['courseNo']} | {c['name']} | {c['kind']} | {c['attr']} "
                    f"| {c['credit']:g} | {hv} |"
                )
            lines.append("")
    other = [c for c in courses if c["term"] == 0]
    if other:
        lines.append("## 未标注学期")
        lines.append("")
        for c in other:
            lines.append(f"- {c['courseNo']} {c['name']}（{c['credit']} 学分）")
        lines.append("")
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(description="抓取培养方案课程设置总表")
    parser.parse_args()

    cred = jw_session.load_credentials()
    jw = jw_session.load_or_login(cred)

    raw = jw_session.get_html(
        jw, TOPYFAMX, must_contain="课程设置总表",
        save=OUT / "curriculum_raw.html",
    )
    parsed = parse_curriculum(raw)
    plan, courses = parsed["plan"], parsed["courses"]
    if not courses:
        raise RuntimeError("解析到 0 门课——页面结构可能已变，看快照")

    payload = {
        "source": "jiaowu.juwp.edu.cn 强智 /jsxsd/pyfa/topyfamx",
        "plan": plan,
        "student": cred["username"],
        "exportedAt": datetime.now().isoformat(timespec="seconds"),
        "count": len(courses),
        "creditTotal": round(sum(c["credit"] or 0 for c in courses), 2),
        "termCount": {str(t): sum(1 for c in courses if c["term"] == t) for t in range(1, 9)},
        "courses": courses,
    }
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "curriculum.json").write_text(
        json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    (OUT / "curriculum.md").write_text(render_markdown(plan, courses), encoding="utf-8")

    print(f"[5] 培养方案：{plan or '(未抓到方案名)'}")
    print(f"    {len(courses)} 门课 · 合计 {payload['creditTotal']:g} 学分")
    for yi, label in enumerate(_YEAR_LABELS):
        sel = [c for c in courses if c["term"] in (yi * 2 + 1, yi * 2 + 2)]
        if not sel:
            continue
        credit = sum(c["credit"] or 0 for c in sel)
        per_term = " / ".join(
            f"第{t}学期 {sum(1 for c in sel if c['term'] == t)} 门" for t in (yi * 2 + 1, yi * 2 + 2)
        )
        print(f"    {label}：{len(sel)} 门 · {credit:g} 学分（{per_term}）")
    print("    预览：")
    for c in courses[:6]:
        print(f"      [{c['term']}] {c['courseNo']} {c['name']} | {c['attr']} | {c['credit']:g} 学分")
    print("[done]", OUT / "curriculum.json")
    print("       ", OUT / "curriculum.md")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
