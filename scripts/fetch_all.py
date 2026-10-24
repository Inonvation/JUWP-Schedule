#!/usr/bin/env python3
"""一次会话，顺序抓全套教务数据 → scripts/out/*.json。

背景:每个 fetch_*.py 单独跑都会走一遍登录；本入口按顺序逐个调用它们，
会话经 jw_session_state.json 跨进程复用——只有会话真失效时才重新账密登录一次
（第一个脚本登录后，后续脚本各花 1 个探针请求直接复用）。

用法:
  .venv-scraper/Scripts/python.exe scripts/fetch_all.py
  .venv-scraper/Scripts/python.exe scripts/fetch_all.py --only exams,scores
  .venv-scraper/Scripts/python.exe scripts/fetch_all.py --skip-fresh 600
  .venv-scraper/Scripts/python.exe scripts/fetch_all.py --with-power

顺序与口径:
  courses → lab → exams → scores → textbooks → selections → curriculum
  （--with-power 追加电费；电费凭证独立，token 走同一文件的本地缓存）

流程参数:
  --only / --skip   按 key 或中文名挑选步骤；--skip-fresh 让最近 N 秒内已产出的步骤跳过
                    （失败后重跑只补失败项）。步骤仍是**串行**：不做并发，避免 CAS
                    验证码与校方接口限流。
  --pause           步骤间随机等待的基准秒数（默认 0.5，实际 0.6–1.4 倍抖动）。

学期口径都是「教务当前学期」（curriculum 为培养方案全量，无学期参数）；
要指定学期请单独跑对应脚本。
某一项失败不中断后续，结尾按退出码汇总——全部成功返回 0，否则 1。
"""

from __future__ import annotations

import argparse
import random
import subprocess
import sys
import time
from dataclasses import dataclass
from pathlib import Path

HERE = Path(__file__).resolve().parent
OUT = HERE / "out"


@dataclass(frozen=True)
class Step:
    key: str
    name: str
    script: str
    output: str


STEPS = [
    Step("courses", "理论课表", "fetch_courses.py", "courses.json"),
    Step("lab", "实验课表", "fetch_lab_courses.py", "lab_courses.json"),
    Step("exams", "考试安排", "fetch_exams.py", "exams.json"),
    Step("scores", "课程成绩", "fetch_scores.py", "scores.json"),
    Step("textbooks", "学生教材", "fetch_textbooks.py", "textbooks.json"),
    Step("selections", "选课记录", "fetch_selections.py", "selections.json"),
    Step("curriculum", "培养方案", "fetch_curriculum.py", "curriculum.json"),
]
POWER_STEP = Step("power", "寝室电费", "fetch_power.py", "power.json")


def _split(raw: str | None) -> set[str]:
    return {part.strip().lower() for part in (raw or "").split(",") if part.strip()}


def _matches(step: Step, names: set[str]) -> bool:
    return step.key in names or step.name in names


def _fresh_for(step: Step, seconds: int) -> int | None:
    """产出文件比 seconds 秒新时返回年龄（秒），否则 None。"""
    if seconds <= 0:
        return None
    path = OUT / step.output
    try:
        age = time.time() - path.stat().st_mtime
    except OSError:
        return None
    return int(age) if age < seconds else None


def main() -> int:
    parser = argparse.ArgumentParser(description="一次会话顺序抓全套教务数据")
    parser.add_argument("--with-power", action="store_true", help="追加寝室电费（独立凭证）")
    parser.add_argument("--only", help="只跑这些步骤（key 或中文名，逗号分隔）")
    parser.add_argument("--skip", help="跳过这些步骤（key 或中文名，逗号分隔）")
    parser.add_argument(
        "--skip-fresh",
        type=int,
        default=0,
        metavar="SECONDS",
        help="产出 JSON 比 N 秒新就跳过（失败后重跑只补失败项；默认 0=不跳）",
    )
    parser.add_argument(
        "--pause",
        type=float,
        default=0.5,
        metavar="SECONDS",
        help="步骤间随机等待基准秒数（默认 0.5；0=不等待）",
    )
    args = parser.parse_args()

    steps = STEPS + ([POWER_STEP] if args.with_power else [])
    only = _split(args.only)
    skip = _split(args.skip)
    if only:
        steps = [s for s in steps if _matches(s, only)]
    if skip:
        steps = [s for s in steps if not _matches(s, skip)]
    if not steps:
        hint = "；电费步骤需要同时加 --with-power" if (
            "power" in only or "寝室电费" in only
        ) else ""
        print(f"[done] 没有匹配的步骤（检查 --only / --skip{hint}）", file=sys.stderr)
        return 1

    results: list[tuple[str, str, float]] = []
    started_all = time.monotonic()
    ran = 0
    for step in steps:
        fresh = _fresh_for(step, args.skip_fresh)
        if fresh is not None:
            print(f"\n===== {step.name}（{step.script}）===== 跳过：{step.output} 已在 {fresh}s 前更新")
            results.append((step.name, "SKIP", 0.0))
            continue
        if ran and args.pause > 0:
            time.sleep(random.uniform(args.pause * 0.6, args.pause * 1.4))
        ran += 1
        print(f"\n===== {step.name}（{step.script}）=====", flush=True)
        started = time.monotonic()
        proc = subprocess.run([sys.executable, str(HERE / step.script)], cwd=str(HERE))
        seconds = time.monotonic() - started
        mark = "OK" if proc.returncode == 0 else f"FAIL({proc.returncode})"
        results.append((step.name, mark, seconds))

    total = time.monotonic() - started_all
    print("\n===== 汇总 =====")
    failed = 0
    for name, mark, seconds in results:
        failed += mark.startswith("FAIL")
        cost = "" if mark == "SKIP" else f"  {seconds:.1f}s"
        print(f"  [{mark:<8}] {name}{cost}")
    print(f"[done] 总耗时 {total:.1f}s" + (f"，{failed} 项失败" if failed else ""))
    return 0 if not failed else 1


if __name__ == "__main__":
    raise SystemExit(main())
