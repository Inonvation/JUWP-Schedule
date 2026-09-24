#!/usr/bin/env python3
"""教务处电子签章成绩单 → scripts/out/transcript_<标签>.pdf。

来源不是强智教务，是**金格签章管理系统** `jwxyxx.juwp.edu.cn/ptwork/`
（门户应用「电子签章成绩单」，taskCode=dzqz）。出的是含证件照与教务处成绩专用章的
A4 成绩表，评优评先交的就是这张。

链路:
  [1] CAS 统一认证拿 TGC（jw_session.portal_cas_login）
  [2] CAS ticket 换签章系统会话（jw_session.sso_ptwork，落 mainIndex，拿到 sid）
  [3] POST /ptwork/DzqzController/ddqzcjList   取 pagePri（不透明的加密条件串）
  [4] POST /ptwork/DzqzController/printStartCj 回传 pagePri，拿 PDF 字节流

用法:
  .venv-scraper/Scripts/python.exe scripts/fetch_transcript.py --list
  .venv-scraper/Scripts/python.exe scripts/fetch_transcript.py                        # 全部学期
  .venv-scraper/Scripts/python.exe scripts/fetch_transcript.py --term 2025-2026-2
  .venv-scraper/Scripts/python.exe scripts/fetch_transcript.py --term 2025-2026-2 2024-2025-2

输出:
  scripts/out/transcript_<标签>.pdf   盖章成绩单

坑（2026-09-24 实测，App 侧同一套逻辑见 data/jw/PtworkTranscript.kt）:
  1) **dysj（= pagePri）才是权威条件，xnxq 被服务端忽略**。故意制造不一致
     （dysj 指 2025-2026-2、xnxq 传 2024-2025-1）导出，出来的仍是 2025-2026-2 的内容。
     所以只能「先列表拿 token、再带 token 出单」，不能按学期号直接拼请求。
  2) 列表 `limit` 无效，服务端固定 15 行一页；要学期清单就按 `pages` 翻。
  3) **pagePri 存在不等于有数据**：无成绩的学期照样返回 token，出单时报
     `parent.wzalert('未发现打印内容')`（79 字节 HTML）。故出单前必须用 total 当闸门。
  4) 令牌解不开时服务端返回**空白却带章**的模板 PDF（60KB、0 门课），绝不能当成果交付。
  5) 签章系统只有 HTTP 明文（443 连接超时），CAS 票据与成绩单都明文回传，校外网络下
     有被旁听的风险；本脚本不做任何规避。
  6) 系统里有**两个入口**都长着这张表单：左侧栏「电子凭证 → 电子成绩签章」
     （/DzqzController/ddqzcjFind，学期下拉 2002→2030 连续）与菜单首项「后台首页」
     （/ptwork/main，学期下拉最高只到 2022-2023-2 且 37 个旧学期重复）。接口是同一组，
     本脚本不解析页面下拉，故不受那个 bug 影响。
"""

from __future__ import annotations

import argparse
import json
from datetime import datetime
from typing import Any

import jw_session
from jw_session import OUT, PTWORK

LIST_API = f"{PTWORK}/ptwork/DzqzController/ddqzcjList"
PRINT_API = f"{PTWORK}/ptwork/DzqzController/printStartCj"

# 服务端固定 15 行一页（limit 参数实测被忽略，传 200/500 都只回 15 行）
PAGE_SIZE = 15
# 翻页上限：66 行实测 5 页；挡住 pages 异常时不至于空转
MAX_PAGES = 40
# 成绩方式：页面只开放了 1（「最好成绩」在源码里被注释掉了）
CJFS_ALL = "1"


def list_page(pt, terms: list[str], page: int) -> dict[str, Any]:
    """取一页成绩列表。terms 为空 = 全部学期。"""
    data: list[tuple[str, str]] = [
        ("page", str(page)),
        ("limit", str(PAGE_SIZE)),
        ("sort", ""),
        ("order", ""),
        ("dysj", ""),
        ("dytype", ""),
        ("cjfs", CJFS_ALL),
    ]
    if not terms:
        data.append(("xnxq", ""))
    else:
        data.extend(("xnxq", t) for t in terms)
    r = pt.post(LIST_API, data=data, timeout=30)
    if "LoginPage" in r.url or "立即登录" in r.text:
        raise RuntimeError("签章系统会话已失效（落回登录页）")
    payload = json.loads(r.text)
    if not isinstance(payload, list) or len(payload) < 2 or str(payload[0]) != "1":
        raise RuntimeError(f"列表接口返回结构已变：{r.text[:200]!r}")
    return payload[1]


def collect_terms(pt) -> list[tuple[str, int]]:
    """翻完列表，归并出「学期 + 门数」，按学期倒序。"""
    counts: dict[str, int] = {}
    page = 1
    pages = 1
    while page <= min(pages, MAX_PAGES):
        payload = list_page(pt, [], page)
        for row in payload.get("list") or []:
            term = (row.get("XNXQID") or "").strip()
            if term:
                counts[term] = counts.get(term, 0) + 1
        pages = int(payload.get("pages") or 1)
        page += 1
    return sorted(counts.items(), reverse=True)


def export_pdf(pt, terms: list[str]) -> bytes:
    """按 terms 出单。terms 为空 = 全部学期。"""
    head = list_page(pt, terms, 1)
    total = int(head.get("total") or 0)
    if total <= 0:
        raise RuntimeError("所选学期没有成绩（列表 total=0），换个学期或去掉 --term")
    token = (head.get("pagePri") or "").strip()
    if not token:
        raise RuntimeError("列表接口没有返回导出令牌 pagePri")

    # 导出请求不传 xnxq：条件全在 dysj 里，多传只会被忽略或与 token 冲突
    r = pt.post(
        PRINT_API,
        data=[("dysj", token), ("dytype", "1"), ("cjfs", CJFS_ALL)],
        timeout=120,
    )
    body = r.content
    if not body.startswith(b"%PDF"):
        text = body.decode("utf-8", errors="replace")
        alert = ""
        if "wzalert(" in text:
            alert = text.split("wzalert(")[1].split(")")[0]
        raise RuntimeError(f"服务端没返回 PDF：{alert or text[:200]!r}")
    print(f"[5] 会话学期数 {total} 行；PDF {len(body)} 字节 "
          f"（数字签名对象 {body.count(b'/Type /Sig') + body.count(b'/Type/Sig')} 个）")
    return body


def main() -> int:
    parser = argparse.ArgumentParser(description="导出教务处电子签章成绩单（PDF）")
    parser.add_argument("--term", nargs="*", help="学年学期，可多个，如 2025-2026-2；缺省全部学期")
    parser.add_argument("--list", action="store_true", help="只列学期与门数，不出单")
    parser.add_argument("--out", help="输出文件名（默认 scripts/out/transcript_<标签>.pdf）")
    args = parser.parse_args()

    cred = jw_session.load_credentials()
    cas = jw_session.new_session()
    jw_session.portal_cas_login(cas, cred["username"], cred["password"])
    pt = jw_session.sso_ptwork(cas)

    if args.list:
        terms = collect_terms(pt)
        print(f"[5] 共 {len(terms)} 个学期有成绩")
        for term, count in terms:
            print(f"    {term}  {count} 门")
        return 0

    terms = [t.strip() for t in (args.term or []) if t.strip()]
    pdf = export_pdf(pt, terms)

    label = "_".join(terms) if terms else "all"
    target = OUT / (args.out or f"transcript_{label}.pdf")
    OUT.mkdir(parents=True, exist_ok=True)
    target.write_bytes(pdf)
    print(f"[done] {target}  ({datetime.now().isoformat(timespec='seconds')})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
