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

用法:
  import jw_session
  cred = jw_session.load_credentials()
  jw = jw_session.login(cred)
  html = jw_session.get_html(jw, url, must_contain="个人课表")
"""

from __future__ import annotations

import json
import re
from pathlib import Path
from urllib.parse import quote, urljoin

import requests

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "scripts" / "out"
CRED = ROOT / "scripts" / "credentials.local.json"

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
    pwd = cred.get("password") or ""
    if not user or not pwd or user.startswith("你的"):
        raise JwLoginError("credentials.local.json 里的 username / password 还没填。")
    return {"username": user, "password": pwd}


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
    """一步登录：CAS → 教务 SSO → 校验。返回可用 session。"""
    cred = cred or load_credentials()
    cas = new_session()
    portal_cas_login(cas, cred["username"], cred["password"])
    jw = sso_jiaowu(cas)
    verify(jw)
    print("[3] 会话校验通过（xsMainV 正常）")
    return jw


def get_html(
    jw: requests.Session,
    url: str,
    must_contain: str | None = None,
    save: Path | None = None,
) -> str:
    """取页面；可选断言标记文本、可选落盘快照。"""
    r = jw.get(url, timeout=30)
    if r.status_code != 200:
        raise JwLoginError(f"{url} 返回 HTTP {r.status_code}")
    if must_contain and must_contain not in r.text:
        raise JwLoginError(
            f"{url} 未包含标记 {must_contain!r}（len={len(r.content)}），页面结构可能已变"
        )
    if save is not None:
        save.parent.mkdir(parents=True, exist_ok=True)
        save.write_text(r.text, encoding="utf-8", errors="replace")
    return r.text
