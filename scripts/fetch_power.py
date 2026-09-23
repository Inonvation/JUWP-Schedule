#!/usr/bin/env python3
"""寝室电费 → scripts/out/power.json。

平台: 新开普「移动服务平台」缴费（charge.juwp.edu.cn，前端 BladeX + Vue）
链路: 学号 + 查询密码 → OAuth2 password 授权 → 电控场景（校区/楼栋/房间）→ 电表读数

接口（同域 HTTPS，全部在 charge.juwp.edu.cn）:
  POST /blade-auth/oauth/token         登录，客户端凭据 Basic charge:charge_secret（前端公开常量）
  GET  /charge/feeitem/showFeeitem     收费项目清单（**免登录**；本期只有「房间电费」）
  GET  /charge/feeitem/singleFeeitem   项目详情，sceneinfo = 本人绑定房间，view=choose 表示选房间
  POST /charge/feeitem/getThirdData    type=select 逐级取校区/楼栋/房间；type=IEC 取该房间电表数据
  GET  /charge/turnover/personal_data  充值/扣费流水（feeitemid + flag=3）

用法:
  .venv-scraper/Scripts/python.exe scripts/fetch_power.py
  .venv-scraper/Scripts/python.exe scripts/fetch_power.py --history
  .venv-scraper/Scripts/python.exe scripts/fetch_power.py --room 9A101 --building 9A

输出:
  scripts/out/power.json      拆分后的电费 JSON（房间 / 剩余电量 / 可选流水）
  scripts/out/power_raw.json  接口原始数据（排错用）

凭证: credentials.local.json 里的 username（学号）+ **powerPassword（缴费平台查询密码）**。
      查询密码与教务 password 不是一回事：实测教务密码登录本平台返回
      {"error":"unauthorized"}，别把两者混用。

坑（2026-09-23 实测）:
  1) 登录端点在**根域** /blade-auth/oauth/token，不在 /charge 下；token 有效期 3599 秒。
  2) getThirdData 少任何一个参数都返回 {"code":500,"msg":"未知异常，请联系管理员"}：
     feeitemid / type=IEC / level=3 / campus + building + room 必须一起给（只给场景三键也不行）。
  3) 电表数据在 map.showData（中文键，平台扩展功能时会加键；map.data.remark 是同一份 JSON
     字符串）。只认 showData 会漏字段，所以 typed 字段与原始 map 一起落盘。
  4) singleFeeitem.sceneinfo 里的校区名是学校旧名（南昌工程学院），房间名以 IEC 返回的 data 为准。
  5) 剩余电量单位是「度」，单价取项目的 price（当前 0.62 元/度）——折算金额仅供展示。
"""

from __future__ import annotations

import argparse
import json
from datetime import datetime
from typing import Any

import jw_session
from jw_session import OUT

BASE = "https://charge.juwp.edu.cn"
TOKEN_URL = f"{BASE}/blade-auth/oauth/token"
API = f"{BASE}/charge"

# 前端 bundle 里的 OAuth2 客户端凭据（所有人可见，非机密）
BASIC = "Basic Y2hhcmdlOmNoYXJnZV9zZWNyZXQ="
LOGIN_TYPE = "student-sno-queryPassword"

# 收费项目兜底：findFeeitem 找不到电控项目时用它（目前平台上只有这一个项目）
FALLBACK_FEEITEM = 181

_SCENE_SEP = ";"
_VALUE_SEP = "#"


class PowerError(RuntimeError):
    """登录 / 场景解析 / 读表任一环节失败。"""


def load_credentials() -> dict[str, str]:
    """学号（复用教务那份）+ 缴费平台查询密码（powerPassword）。"""
    path = jw_session.CRED
    if not path.exists():
        raise PowerError(
            f"缺少 {path}\n"
            '请复制 credentials.local.json.example 并填入学号与 "powerPassword"（该文件已 gitignore）。'
        )
    cred = json.loads(path.read_text(encoding="utf-8"))
    user = (cred.get("username") or "").strip()
    pwd = (cred.get("powerPassword") or "").strip()
    if not user or not pwd or pwd.startswith("缴费平台"):
        raise PowerError(
            'credentials.local.json 里缺 "powerPassword"。\n'
            "这是一卡通/缴费平台的**查询密码**（纯数字），不是教务 password——"
            "填教务密码登录本平台会返回 401 unauthorized。"
        )
    return {"username": user, "password": pwd}


def _json(resp, what: str) -> dict[str, Any]:
    if resp.status_code == 401:
        raise PowerError(f"{what}：HTTP 401，学号或查询密码不对")
    if resp.status_code != 200:
        raise PowerError(f"{what}：HTTP {resp.status_code}")
    try:
        data = json.loads(resp.text)
    except ValueError as exc:
        raise PowerError(f"{what}：响应不是 JSON（{exc}）") from exc
    code = data.get("code")
    if code not in (200, "200"):
        raise PowerError(f"{what}：code={code} msg={data.get('msg')!r}")
    return data


def login(sess, user: str, password: str) -> str:
    """学号 + 查询密码换 access_token（有效期 3599 秒，脚本内一次性使用，不落盘）。"""
    r = sess.post(
        TOKEN_URL,
        data={
            "username": user,
            "password": password,
            "grant_type": "password",
            "scope": "all",
            "logintype": LOGIN_TYPE,
        },
        headers={"Authorization": BASIC, "Content-Type": "application/x-www-form-urlencoded"},
        timeout=25,
    )
    if r.status_code == 401:
        raise PowerError(
            "登录被拒（HTTP 401 unauthorized）：学号或查询密码不对。\n"
            "注意 powerPassword 是缴费平台的查询密码，填教务密码会一直 401。"
        )
    if r.status_code != 200:
        raise PowerError(f"登录失败：HTTP {r.status_code} {r.text[:200]}")
    token = json.loads(r.text).get("access_token")
    if not token:
        raise PowerError(f"登录响应里没有 access_token：{r.text[:200]}")
    return token


def _headers(token: str | None) -> dict[str, str]:
    h = {"Authorization": BASIC}
    if token:
        h["synjones-auth"] = "bearer " + token
    return h


def find_feeitem(sess, token: str) -> dict[str, Any]:
    """定位电费项目：优先 impl_interface 含 iECScene 的那条，兜底 [FALLBACK_FEEITEM]。"""
    r = sess.get(f"{API}/feeitem/showFeeitem", headers=_headers(None), timeout=25)
    items = _json(r, "取收费项目清单").get("list") or []
    for it in items:
        if "iecscene" in str(it.get("impl_interface") or "").lower():
            return it
    for it in items:
        if it.get("feeitemid") == FALLBACK_FEEITEM:
            return it
    names = "、".join(f"{it.get('feeitemid')}#{it.get('name')}" for it in items) or "（空）"
    raise PowerError(f"清单里找不到电控项目（现有：{names}）")


def parse_sceneinfo(raw: str) -> list[dict[str, str]]:
    """`campus:0#<校区>;building:12#<楼栋>;room:345#<房间>` → 有序场景键列表。"""
    scene: list[dict[str, str]] = []
    for part in (raw or "").split(_SCENE_SEP):
        if not part.strip():
            continue
        code, _, rest = part.partition(":")
        room_id, _, name = rest.partition(_VALUE_SEP)
        scene.append({"code": code.strip(), "id": room_id.strip(), "name": name.strip()})
    if not scene:
        raise PowerError("项目详情没给 sceneinfo，拿不到绑定房间")
    return scene


def feeitem_detail(sess, token: str, feeitemid: int) -> dict[str, Any]:
    r = sess.get(
        f"{API}/feeitem/singleFeeitem",
        params={"feeitemid": feeitemid},
        headers=_headers(token),
        timeout=25,
    )
    return _json(r, "取电费项目详情")


def select_level(
    sess, token: str, feeitemid: int, level: int, chosen: dict[str, str]
) -> dict[str, Any]:
    """type=select 取某一层的候选（level = 已选场景键个数）。"""
    form = {"feeitemid": str(feeitemid), "type": "select", "level": str(level), **chosen}
    r = sess.post(
        f"{API}/feeitem/getThirdData",
        data=form,
        headers={**_headers(token), "Content-Type": "application/x-www-form-urlencoded"},
        timeout=25,
    )
    return _json(r, f"取场景第 {level} 层").get("map") or {}


def resolve_room(
    sess, token: str, feeitemid: int, room_name: str, building_hint: str | None
) -> list[dict[str, str]]:
    """按名字找房间：逐级 select，命中就停。返回有序场景键（campus/building/room）。"""
    want_room = room_name.strip().upper()
    want_building = (building_hint or "").strip().upper()
    level0 = select_level(sess, token, feeitemid, 0, {})
    codes = [x["code"] for x in (level0.get("total") or [])]
    if len(codes) < 3:
        raise PowerError(f"场景层级异常：{level0.get('total')!r}")
    campus_key, building_key, room_key = codes[0], codes[1], codes[2]

    for campus in level0.get("data") or []:
        chosen = {campus_key: campus["value"]}
        buildings = select_level(sess, token, feeitemid, 1, chosen).get("data") or []
        for building in buildings:
            if want_building and building["name"].strip().upper() != want_building:
                continue
            chosen_b = {**chosen, building_key: building["value"]}
            rooms = select_level(sess, token, feeitemid, 2, chosen_b).get("data") or []
            for room in rooms:
                if room["name"].strip().upper() == want_room:
                    return [
                        {"code": campus_key, "id": campus["value"], "name": campus["name"]},
                        {"code": building_key, "id": building["value"], "name": building["name"]},
                        {"code": room_key, "id": room["value"], "name": room["name"]},
                    ]
    hint = f"（限定了楼栋 {building_hint}）" if building_hint else ""
    raise PowerError(f"没找到房间 {room_name}{hint}，确认名字拼写（形如 1A101）")


def query_meter(sess, token: str, feeitemid: int, scene: list[dict[str, str]]) -> dict[str, Any]:
    """type=IEC 取该房间电表数据。"""
    form: dict[str, str] = {
        "feeitemid": str(feeitemid),
        "type": "IEC",
        "level": str(len(scene)),
        **{s["code"]: s["id"] for s in scene},
    }
    r = sess.post(
        f"{API}/feeitem/getThirdData",
        data=form,
        headers={**_headers(token), "Content-Type": "application/x-www-form-urlencoded"},
        timeout=25,
    )
    return _json(r, "读电表").get("map") or {}


def query_history(sess, token: str, feeitemid: int) -> list[dict[str, Any]]:
    """电费充值/扣费流水（正数=充值）。"""
    r = sess.get(
        f"{API}/turnover/personal_data",
        params={"feeitemid": feeitemid, "flag": 3},
        headers=_headers(token),
        timeout=30,
    )
    rows = _json(r, "取电费流水").get("list") or []
    return sorted(rows, key=lambda x: str(x.get("createdate") or ""))


def _pick_remain(show_data: dict[str, Any]) -> tuple[float | None, str]:
    """从 showData 里取剩余电量：优先键含「剩余电量」，其次含「电量」/「余额」。"""
    for key in show_data:
        if "剩余电量" in key:
            return _to_float(show_data[key]), key
    for key in show_data:
        if "电量" in key or "余额" in key:
            return _to_float(show_data[key]), key
    return None, ""


def _to_float(value: Any) -> float | None:
    try:
        return float(str(value).strip())
    except (TypeError, ValueError):
        return None


def to_payload(
    feeitem: dict[str, Any],
    scene: list[dict[str, str]],
    meter: dict[str, Any],
    history: list[dict[str, Any]] | None,
) -> dict[str, Any]:
    show_data = meter.get("showData") or {}
    room_data = meter.get("data") or {}
    remain, remain_key = _pick_remain(show_data)
    price = _to_float(feeitem.get("price"))

    def scene_name(code: str) -> str:
        return next((s["name"] for s in scene if s["code"] == code), "")

    payload: dict[str, Any] = {
        "source": "charge.juwp.edu.cn 缴费平台 /charge/feeitem/getThirdData",
        "exportedAt": datetime.now().isoformat(timespec="seconds"),
        "feeitem": {
            "id": feeitem.get("feeitemid"),
            "name": feeitem.get("name"),
            "price": price,
            "unit": feeitem.get("billing_unit"),
            "feetype": ((feeitem.get("feetypeBean") or {}).get("name") or "").strip(),
        },
        "room": {
            "campus": room_data.get("campus") or scene_name("campus"),
            "building": room_data.get("building") or scene_name("building"),
            "room": room_data.get("room") or scene_name("room"),
            "campusId": room_data.get("campusid"),
            "buildingId": room_data.get("buildingid"),
            "roomId": room_data.get("roomid"),
        },
        "meter": {
            "remain": remain,
            "remainUnit": feeitem.get("billing_unit"),
            "remainYuan": round(remain * price, 2) if remain is not None and price else None,
            "remainField": remain_key,
            "fields": show_data,
            "raw": room_data,
        },
    }
    if history is not None:
        records = [
            {
                "turnoverId": row.get("turnoverid"),
                "date": row.get("createdate"),
                "month": row.get("feerange"),
                "amount": _to_float(row.get("tranamt")),
                "room": row.get("abstracts"),
                "payId": row.get("payid"),
                "refund": bool(row.get("refund_flag")),
            }
            for row in history
        ]
        monthly: dict[str, dict[str, Any]] = {}
        for rec in records:
            key = rec["month"] or (rec["date"] or "")[:7]
            slot = monthly.setdefault(key, {"month": key, "amount": 0.0, "count": 0})
            slot["amount"] = round(slot["amount"] + (rec["amount"] or 0.0), 2)
            slot["count"] += 1
        payload["history"] = records
        payload["monthly"] = [monthly[k] for k in sorted(monthly)]
    return payload


def main() -> int:
    parser = argparse.ArgumentParser(description="抓取寝室电费（剩余电量 / 充值流水）")
    parser.add_argument("--room", help="按名字查别的房间（默认本人绑定房间），形如 1A101")
    parser.add_argument("--building", help="配合 --room 限定楼栋，形如 1A（可省，省了逐个楼栋找）")
    parser.add_argument("--history", action="store_true", help="附带电费充值流水与月度汇总")
    args = parser.parse_args()

    cred = load_credentials()
    sess = jw_session.new_session()

    token = login(sess, cred["username"], cred["password"])
    print(f"[1] 登录 OK（学号 {cred['username']}，token 有效期 3599 秒）")

    feeitem = find_feeitem(sess, token)
    feeitemid = int(feeitem["feeitemid"])
    print(
        f"[2] 项目「{feeitem['name']}」feeitemid={feeitemid}"
        f"（{feeitem.get('price')} 元/{feeitem.get('billing_unit')}）"
    )

    detail = feeitem_detail(sess, token, feeitemid)
    if args.room:
        scene = resolve_room(sess, token, feeitemid, args.room, args.building)
        origin = "--room 指定"
    else:
        scene = parse_sceneinfo(detail.get("sceneinfo") or "")
        origin = "本人绑定"
    # 场景首项是校区，名字取自平台旧名（南昌工程学院），这里只报楼栋 + 房间
    where = " ".join(s["name"] for s in scene[-2:])
    print(f"[3] 房间 {where}（{origin}）")

    meter = query_meter(sess, token, feeitemid, scene)
    payload = to_payload(feeitem, scene, meter, None)
    remain = payload["meter"]["remain"]
    if remain is None:
        print(f"[4] 电表已读，但没认出剩余电量字段：{payload['meter']['fields']}")
    else:
        print(
            f"[4] {payload['room']['campus']} {payload['room']['building']} {payload['room']['room']}："
            f"{payload['meter']['remainField']} {remain} {payload['meter']['remainUnit']}"
            f"（≈ {payload['meter']['remainYuan']} 元）"
        )

    raw: dict[str, Any] = {"singleFeeitem": detail, "meter": meter}
    if args.history:
        history = query_history(sess, token, feeitemid)
        payload = to_payload(feeitem, scene, meter, history)
        total = round(sum(r["amount"] or 0.0 for r in payload["history"] if r["amount"]), 2)
        span = (
            f"{payload['history'][0]['date'][:10]} ~ {payload['history'][-1]['date'][:10]}"
            if payload["history"]
            else "无记录"
        )
        print(f"[5] 电费流水 {len(payload['history'])} 条（{span}），合计 {total} 元")
        raw["turnover"] = [
            {
                "turnoverid": row.get("turnoverid"),
                "feeitemid": row.get("feeitemid"),
                "payid": row.get("payid"),
                "feerange": row.get("feerange"),
                "tranamt": row.get("tranamt"),
                "createdate": row.get("createdate"),
                "abstracts": row.get("abstracts"),
            }
            for row in history
        ]

    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "power.json").write_text(
        json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    (OUT / "power_raw.json").write_text(
        json.dumps(raw, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print("[done]", OUT / "power.json")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
