#!/usr/bin/env python3
"""一次会话，顺序抓全套教务数据 → scripts/out/*.json。

背景:每个 fetch_*.py 单独跑都会走一遍登录；本入口按顺序逐个调用它们，
会话经 jw_session_state.json 跨进程复用——只有会话真失效时才重新账密登录一次
（第一个脚本登录后，后续脚本各花 1 个探针请求直接复用）。

用法:
  .venv-scraper/Scripts/python.exe scripts/fetch_all.py [--with-power]

顺序与口径:
  courses → lab_courses → exams → scores → textbooks → selections
  （--with-power 追加电费；电费凭证独立，token 走同一文件的本地缓存）

学期口径都是「教务当前学期」；要指定学期请单独跑对应脚本。
某一项失败不中断后续，结尾按退出码汇总——全部成功返回 0，否则 1。
脚本之间留 1–2 秒间隔，别让六份抓取挤在一个瞬间。
"""

from __future__ import annotations

import argparse
import random
import subprocess
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent

STEPS = [
    ("理论课表", "fetch_courses.py"),
    ("实验课表", "fetch_lab_courses.py"),
    ("考试安排", "fetch_exams.py"),
    ("课程成绩", "fetch_scores.py"),
    ("学生教材", "fetch_textbooks.py"),
    ("选课记录", "fetch_selections.py"),
]
POWER_STEP = ("寝室电费", "fetch_power.py")


def main() -> int:
    parser = argparse.ArgumentParser(description="一次会话顺序抓全套教务数据")
    parser.add_argument("--with-power", action="store_true", help="追加寝室电费（独立凭证）")
    args = parser.parse_args()

    steps = STEPS + ([POWER_STEP] if args.with_power else [])
    results: list[tuple[str, int, float]] = []
    for i, (name, script) in enumerate(steps):
        if i:
            time.sleep(random.uniform(1.0, 2.0))
        print(f"\n===== {name}（{script}）=====", flush=True)
        started = time.monotonic()
        proc = subprocess.run([sys.executable, str(HERE / script)], cwd=str(HERE))
        results.append((name, proc.returncode, time.monotonic() - started))

    print("\n===== 汇总 =====")
    failed = 0
    for name, code, seconds in results:
        mark = "OK  " if code == 0 else f"FAIL({code})"
        failed += code != 0
        print(f"  [{mark}] {name}  {seconds:.1f}s")
    print("[done]" if not failed else f"[done] {failed} 项失败")
    return 0 if not failed else 1


if __name__ == "__main__":
    raise SystemExit(main())
