#!/usr/bin/env python3
"""教务会话：CAS 统一认证 → 强智教务 SSO。由 fetch_courses / fetch_lab_courses 共用。

登录链路（2026-09 实测）:
  [1] 门户落地拿 TGC
      GET  http://portal.juwp.edu.cn/cas/login_portal
      → GET https://eapp2.juwp.edu.cn:9443/cas/login?service=<portal>/cas/login_portal
      → POST 同一 URL（username / password / execution / _eventId=submit）→ 302
  [2] 教务 SSO
      预热 https://jiaowu.juwp.edu.cn:81/        ← 必须，用于拿 bzb_njw，缺了教务不认
      → GET cas/login?service=http://jiaowu.juwp.edu.cn/sso.jsp
          注意：service 不能带 :81 / :8080，带了会 500
      → 跟 302 链：sso.jsp?ticket=… → sso.jsp → :8080/jsxsd/xk/LoginToXk?method=jwxt&ticket1=…
      → 落到 :8080/jsxsd/framework/xsMainV.htmlx

必须直连（2026-09-17 定位的故障根因）:
  shell 环境可能被注入 HTTP_PROXY / HTTPS_PROXY（本机实测为 IDE 的本地代理
  http://127.0.0.1:12892），requests 默认读取这两个变量。
  教务对代理出口与直连出口区别对待：走代理时 SSO 落点 /jsxsd/xk/LoginToXk
  返回 404 通用错误页，xsMainV 退回 860 字节的"用户没有登录"，
  表现为"同一请求时而 200 时而 404"，极易误判成教务故障。
  故本模块所有 Session 显式 trust_env = False。

会话复用（2026-10-04）:
  每个脚本各自 login() 意味着连跑几个脚本就是连环完整登录（每次 ≈11 个请求），
  CAS 已知会因此触发验证码。load_or_login 把 CAS / 教务的会话 cookie 持久化到
  scripts/out/jw_session_state.json（已 gitignore，含会话 cookie，与凭证同级敏感），
  按三级降级取会话，让账密 POST 只在会话真正全灭时发生：
    [a] 教务会话仍活                → 探针 1 个请求直接复用
    [b] 教务会话失效但 CAS TGC 还活 → 免账密，向 CAS 换新教务票（≈5 个请求）
    [c] 都失效                      → 完整账密登录并覆盖保存
  fetch_transcript 用 load_or_login_ptwork（复用 CAS TGC 换签章票，跳过教务）；
  fetch_power 的 OAuth token（3599 秒）经 load_power_token / save_power_token 存同一文件。
  循环类请求（翻页 / 多学期 / 枚举）之间调 polite_pause 留随机间隔——0 间隔连发
  是最像机器的请求节奏。

用法:
  import jw_session
  cred = jw_session.load_credentials()
  jw = jw_session.load_or_login(cred)
  html = jw_session.get_html(jw, url, must_contain="个人课表")
"""

from __future__ import annotations

import json
import random
import re
import time
from datetime import datetime
from http.cookiejar import Cookie, CookieJar
from pathlib import Path
from typing import Any, Callable
from urllib.parse import quote, urljoin

import requests
from bs4 import BeautifulSoup

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "scripts" / "out"
CRED = ROOT / "scripts" / "credentials.local.json"
STATE_FILE = OUT / "jw_session_state.json"

CAS = "https://eapp2.juwp.edu.cn:9443"
PORTAL = "http://portal.juwp.edu.cn"
JW81 = "https://jiaowu.juwp.edu.cn:81"
JW8080 = "http://jiaowu.juwp.edu.cn:8080"
JW_SSO_SERVICE = "http://jiaowu.juwp.edu.cn/sso.jsp"
# 签章管理系统（教务处成绩单出单与盖章）。只有 HTTP：443 实测连接超时。
# 门户应用「电子签章成绩单」指向的就是下面这个 CAS 地址。
PTWORK = "http://jwxyxx.juwp.edu.cn"
PTWORK_SSO_SERVICE = f"{PTWORK}/ptwork/cas"
STUDENT_HOME = f"{JW8080}/jsxsd/framework/xsMainV.htmlx"
LAB_SCHEDULE = f"{JW8080}/jsxsd/syjx/toXskb.do"
THEORY_SCHEDULE = f"{JW8080}/jsxsd/xskb/xskb_list.do?viweType=0"

UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
)

_REDIRECTS = (301, 302, 303, 307, 308)
_NOT_LOGGED = "用户没有登录"
_HOME_MIN_BYTES = 20000  # 正常主页 ~150KB；退回登录提示时仅 860B

# 缴费平台 token 服务端 3599 秒，本地按 3000 秒回收，避免拿临期 token 撞 401
_POWER_TOKEN_REUSE_S = 3000


class JwLoginError(RuntimeError):
    """登录 / SSO / 会话校验任一环节失败。"""


def new_session() -> requests.Session:
    """直连 session：不读环境代理（见文件头）。"""
    s = requests.Session()
    s.headers["User-Agent"] = UA
    s.trust_env = False
    return s


def load_credentials() -> dict[str, str]:
    if not CRED.exists():
        raise JwLoginError(
            f"缺少 {CRED}\n"
            "请复制 credentials.local.json.example 并填入学号/密码（该文件已 gitignore）。"
        )
    cred = json.loads(CRED.read_text(encoding="utf-8"))
    user = (cred.get("username") or "").strip()
    pwd = (cred.get("password") or "")
    if not user or not pwd or user.startswith("你的"):
        raise JwLoginError("credentials.local.json 里的 username / password 还没填。")
    return {"username": user, "password": pwd}


# ---------------------------------------------------------------------------
# 会话持久化：cookie（含 domain/path/secure）以 JSON 存 scripts/out/，跨进程复用
# ---------------------------------------------------------------------------

def _jar_items(jar) -> list[dict[str, Any]]:
    return [
        {"name": c.name, "value": c.value, "domain": c.domain,
         "path": c.path, "secure": c.secure}
        for c in jar
    ]


def _jar_from_items(items: list[dict[str, Any]] | None) -> CookieJar:
    jar = CookieJar()
    for it in items or []:
        domain = it.get("domain") or ""
        jar.set_cookie(Cookie(
            0, it.get("name") or "", it.get("value") or "",
            None, False,
            domain, bool(domain), domain.startswith("."),
            it.get("path") or "/", True,
            bool(it.get("secure")), None, True, None, None, {},
        ))
    return jar


def _session_from(items: list[dict[str, Any]] | None) -> requests.Session:
    s = new_session()
    s.cookies.update(_jar_from_items(items))
    return s


def _load_state() -> dict[str, Any]:
    try:
        return json.loads(STATE_FILE.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}


def _save_state(**patch: Any) -> None:
    """合并写回：cas/jw/power 各字段独立更新，互不覆盖。"""
    state = _load_state()
    state.update(patch)
    state["savedAt"] = datetime.now().isoformat(timespec="seconds")
    OUT.mkdir(parents=True, exist_ok=True)
    STATE_FILE.write_text(
        json.dumps(state, ensure_ascii=False, indent=2), encoding="utf-8"
    )


def load_power_token() -> str | None:
    """缴费平台 OAuth token：本地缓存且未到保守期限才复用。"""
    cached = _load_state().get("power") or {}
    token, ts = cached.get("token"), cached.get("ts") or 0
    if token and (time.time() - float(ts)) < _POWER_TOKEN_REUSE_S:
        return str(token)
    return None


def save_power_token(token: str) -> None:
    _save_state(power={"token": token, "ts": int(time.time())})


def polite_pause(lo: float = 0.4, hi: float = 1.2) -> None:
    """翻页 / 多学期 / 枚举循环之间的随机小间隔。失败不重试的纪律不变，
    这里只是把「必然要发的请求」彼此错开。"""
    time.sleep(random.uniform(lo, hi))


# ---------------------------------------------------------------------------
# 登录链（不变）
# ---------------------------------------------------------------------------

def _follow(s: requests.Session, url: str, limit: int = 15) -> str:
    """手动跟随重定向并返回最终 URL（allow_redirects=True 会丢掉中间落点，不便诊断）。"""
    for _ in range(limit):
        r = s.get(url, timeout=25, allow_redirects=False)
        loc = r.headers.get("Location")
        if r.status_code in _REDIRECTS and loc:
            url = urljoin(str(r.url), loc)
            continue
        return str(r.url)
    raise JwLoginError("重定向次数过多，链路可能已变")


def portal_cas_login(s: requests.Session, account: str, password: str) -> None:
    """[1] 统一认证登录，把 TGC 写进 session。"""
    service = f"{PORTAL}/cas/login_portal"
    s.get(service, timeout=20, allow_redirects=True)
    login_url = f"{CAS}/cas/login?service={quote(service, safe='')}"
    r1 = s.get(login_url, timeout=25)
    m = re.search(r'name="execution" value="([^"]+)"', r1.text)
    if not m:
        raise JwLoginError("CAS 登录页缺少 execution 字段（页面结构可能已变）")
    r2 = s.post(
        login_url,
        data={
            "username": account,
            "password": password,
            "execution": m.group(1),
            "_eventId": "submit",
            "geolocation": "",
            "submit": "LOGIN",
        },
        timeout=25,
        allow_redirects=False,
    )
    loc = r2.headers.get("Location")
    if not loc:
        raise JwLoginError(f"CAS 登录失败 HTTP {r2.status_code}（账号密码错误、或触发验证码）")
    _follow(s, loc)
    print("[1] 统一认证 OK")


def sso_jiaowu(cas: requests.Session) -> requests.Session:
    """[2] 用 CAS ticket 换教务会话。返回已登录的教务 session。"""
    jw = new_session()
    jw.get(f"{JW81}/", timeout=20)  # 预热，拿 bzb_njw

    login_url = f"{CAS}/cas/login?service={quote(JW_SSO_SERVICE, safe='')}"
    r = cas.get(login_url, timeout=25, allow_redirects=False)
    loc = r.headers.get("Location")
    if not loc:
        raise JwLoginError(f"取不到 SSO ticket：HTTP {r.status_code}")

    final = _follow(jw, loc)
    if "jsxsd" not in final and "xsMainV" not in final:
        raise JwLoginError(f"SSO 未进入教务，落点 {final}")
    print("[2] 教务 SSO OK ->", final)
    return jw


def verify(jw: requests.Session) -> None:
    """确认会话真的进了教务，而不是"看着成功、其实掉回登录页"。"""
    r = jw.get(STUDENT_HOME, timeout=25)
    if _NOT_LOGGED in r.text or len(r.content) < _HOME_MIN_BYTES:
        raise JwLoginError(
            "教务会话无效（已退回登录页）。\n"
            "若本机设置了 HTTP_PROXY / HTTPS_PROXY，请确认 Session 的 trust_env 为 False。"
        )


def sso_ptwork(cas: requests.Session) -> requests.Session:
    """[2'] 用 CAS ticket 换签章系统会话，返回持有 `sid` 的 session。

    与 [sso_jiaowu] 同构，两处不同：
      - service 用 `/ptwork/cas`（签章系统自己的 CAS 回调，落地 mainIndex）；
      - **不需要预热**：`bzb_njw` 是教务域的怪癖，签章系统只认 ticket。

    `sid` 是该域唯一的 cookie，后面两个接口都靠它鉴权。
    """
    pt = new_session()
    login_url = f"{CAS}/cas/login?service={quote(PTWORK_SSO_SERVICE, safe='')}"
    r = cas.get(login_url, timeout=25, allow_redirects=False)
    loc = r.headers.get("Location")
    if not loc:
        raise JwLoginError(f"取不到签章系统 SSO ticket：HTTP {r.status_code}")
    final = _follow(pt, loc)
    if "ptwork" not in final:
        raise JwLoginError(f"SSO 未进入签章系统，落点 {final}")
    if not any(c.name == "sid" for c in pt.cookies):
        raise JwLoginError(f"签章系统没发 sid cookie，落点 {final}")
    print("[2'] 签章系统 SSO OK ->", final)
    return pt


def login(cred: dict[str, str] | None = None) -> requests.Session:
    """一步登录：CAS → 教务 SSO → 校验。返回可用 session。

    不落盘；常规入口用 load_or_login（跨进程复用会话，少打 CAS）。
    """
    cred = cred or load_credentials()
    cas = new_session()
    portal_cas_login(cas, cred["username"], cred["password"])
    jw = sso_jiaowu(cas)
    verify(jw)
    print("[3] 会话校验通过（xsMainV 正常）")
    return jw


# ---------------------------------------------------------------------------
# 复用入口
# ---------------------------------------------------------------------------

def load_or_login(cred: dict[str, str] | None = None) -> requests.Session:
    """优先复用持久化会话，按 [复用教务 → CAS 换票 → 完整登录] 三级降级。"""
    state = _load_state()
    if state.get("jw"):
        jw = _session_from(state["jw"])
        try:
            verify(jw)
            print(f"[0] 复用本地教务会话（{state.get('savedAt')} 保存）")
            return jw
        except JwLoginError:
            print("[0] 本地教务会话已失效")
    if state.get("cas"):
        cas = _session_from(state["cas"])
        try:
            jw = sso_jiaowu(cas)
            verify(jw)
            _save_state(cas=_jar_items(cas.cookies), jw=_jar_items(jw.cookies))
            print("[0] CAS 会话仍有效，已免账密换新教务票")
            return jw
        except JwLoginError:
            print("[0] CAS 会话也已失效，走完整登录")
    cred = cred or load_credentials()
    cas = new_session()
    portal_cas_login(cas, cred["username"], cred["password"])
    jw = sso_jiaowu(cas)
    verify(jw)
    _save_state(cas=_jar_items(cas.cookies), jw=_jar_items(jw.cookies))
    print("[0] 登录成功，会话已缓存到", STATE_FILE.name)
    return jw


def load_or_login_ptwork(cred: dict[str, str] | None = None) -> requests.Session:
    """签章系统会话：优先复用 CAS TGC 直接换 ptwork 票，TGC 失效才重新账密登录。"""
    state = _load_state()
    if state.get("cas"):
        cas = _session_from(state["cas"])
        try:
            pt = sso_ptwork(cas)
            print("[0] 复用本地 CAS 会话换签章票")
            return pt
        except JwLoginError:
            print("[0] 本地 CAS 会话已失效，重新登录")
    cred = cred or load_credentials()
    cas = new_session()
    portal_cas_login(cas, cred["username"], cred["password"])
    pt = sso_ptwork(cas)
    _save_state(cas=_jar_items(cas.cookies))
    return pt


# ---------------------------------------------------------------------------
# 公共取数件：壳页学期下拉、layui 翻页、页面 GET
# ---------------------------------------------------------------------------

def shell_terms(jw: requests.Session, url: str) -> tuple[str | None, list[str]]:
    """教务壳页的学期下拉（select#xnxqid）：返回 (默认选中项, 全量选项)。

    考试 / 教材 / 选课三个壳页同构；课表页的下拉 id 是 xnxq01id，不适用。
    """
    r = jw.get(url, timeout=30)
    soup = BeautifulSoup(r.text, "html.parser")
    sel = soup.select_one("select#xnxqid")
    if sel is None:
        return None, []
    terms = [o.get_text(strip=True) for o in sel.find_all("option") if o.get_text(strip=True)]
    opt = sel.find("option", selected=True)
    return (opt.get_text(strip=True) if opt else None), terms


def fetch_layui_pages(
    first: dict[str, Any], next_page: Callable[[int], dict[str, Any]]
) -> list[dict[str, Any]]:
    """layui JSON 接口的通用翻页：以第一页为起点，按 count 兜底翻满，页间 polite_pause。

    next_page(page) 由调用方拼参数（各接口的查询键不同），返回该页完整 JSON dict。
    """
    rows: list[dict[str, Any]] = list(first.get("data") or [])
    total = int(first.get("count") or 0)
    page = 1
    while len(rows) < total:
        page += 1
        polite_pause()
        rows += list(next_page(page).get("data") or [])
    return rows


def get_html(
    jw: requests.Session,
    url: str,
    must_contain: str | None = None,
    save: Path | None = None,
) -> str:
    """取页面；先判登录页再判标记文本（否则会话失效会被误报成「结构变了」）。"""
    r = jw.get(url, timeout=30)
    if r.status_code != 200:
        raise JwLoginError(f"{url} 返回 HTTP {r.status_code}")
    if _NOT_LOGGED in r.text:
        raise JwLoginError(
            "教务会话已失效（页面退回登录提示）。重跑脚本即可——"
            "load_or_login 的探针会识别失效并自动重新登录。"
        )
    if must_contain and must_contain not in r.text:
        raise JwLoginError(
            f"{url} 未包含标记 {must_contain!r}（len={len(r.content)}），页面结构可能已变"
        )
    if save is not None:
        save.parent.mkdir(parents=True, exist_ok=True)
        save.write_text(r.text, encoding="utf-8", errors="replace")
    return r.text
