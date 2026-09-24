# 爬虫脚本说明

江西水利电力大学教务（强智科技）的**课表 / 考试 / 成绩抓取脚本**说明，覆盖理论课表、实验课表、考试安排、课程成绩四条链路；
另附寝室电费（新开普缴费平台，**不在教务域**，2026-09-23 打通，见 §5.5）。

爬虫只在本机调试用，**App 端不跑 Python**——App 走 WebView + 注入 JS（见 [§5.3](#53-app-端-webview-注入实现要点)）。

---

## 1. 快速开始

```powershell
# 1) 首次：建虚拟环境并装依赖（在仓库根目录）
python -m venv .venv-scraper
.\.venv-scraper\Scripts\python.exe -m pip install -r scripts\requirements.txt

# 2) 填凭证
copy scripts\credentials.local.json.example scripts\credentials.local.json
#    编辑填入学号 / 密码；跑电费脚本还要填 powerPassword（缴费平台查询密码，与教务密码不同）
#    该文件已 gitignore，禁止提交

# 3) 抓取（--term 均可省略；所有脚本顶层 term = 实际爬到的学期）
.\.venv-scraper\Scripts\python.exe scripts\fetch_courses.py       # 理论课表（缺省当前学期）
.\.venv-scraper\Scripts\python.exe scripts\fetch_lab_courses.py   # 实验课表（缺省当前学期）
.\.venv-scraper\Scripts\python.exe scripts\fetch_exams.py         # 考试安排（缺省当前学期）
.\.venv-scraper\Scripts\python.exe scripts\fetch_scores.py        # 课程成绩（缺省全部学期）
.\.venv-scraper\Scripts\python.exe scripts\fetch_power.py         # 寝室电费（非教务，无 --term；本人绑定房间剩余电量）
.\.venv-scraper\Scripts\python.exe scripts\fetch_power.py --history   # 追加电费充值流水
.\.venv-scraper\Scripts\python.exe scripts\fetch_transcript.py    # 教务处盖章成绩单 PDF（非强智教务，见 §5.6）
.\.venv-scraper\Scripts\python.exe scripts\fetch_transcript.py --list   # 只列有成绩的学期与门数
# 指定学期：加 --term 2025-2026-2（脚本会校验请求学期 = 教务返回学期，不一致即报错）
# 电费查别的房间：--room 1A101（可加 --building 1A 提速）
# 成绩单按学期：--term 2025-2026-2（可多个，合并成一张）
```

| 产出 | 内容 |
|------|------|
| `scripts/out/courses.json` | 理论课表，字段对齐 `DESIGN.md` 4.3 |
| `scripts/out/lab_courses.json` | 实验课表，同上 + `kind: "lab"` |
| `scripts/out/exams.json` | 考试安排（考试时间已拆成 date/startTime/endTime） |
| `scripts/out/scores.json` | 课程成绩（`term` + `terms` 双口径；`pendingReview` 标记评教锁定） |
| `scripts/out/xskb_vt0.html` | 理论课表页快照 |
| `scripts/out/syxkb.html` | 实验课表页快照 |
| `scripts/out/*_raw.json` / `exams_raw.json` / `scores_raw.json` | 解析中间产物（含原始文本，排错用） |
| `scripts/out/power.json` | 寝室电费（房间 / 剩余电量 / 可选充值流水与月度汇总） |
| `scripts/out/power_raw.json` | 电费接口原始数据（排错用） |
| `scripts/out/transcript_<标签>.pdf` | 教务处**盖章**成绩单（A4，含证件照、成绩专用章与数字签名） |

---

## 2. 脚本清单

| 文件 | 职责 | 备注 |
|------|------|------|
| `jw_session.py` | 共享登录：CAS → 教务 SSO → 会话校验 | 其余抓取脚本都依赖它 |
| `fetch_courses.py` | 学期理论课表 → JSON | 纯解析，不做登录 |
| `fetch_lab_courses.py` | 实验课表 → JSON（含周次聚合） | 纯解析，不做登录 |
| `fetch_exams.py` | 考试安排 → JSON | layui JSON 接口，不解析 HTML |
| `fetch_scores.py` | 课程成绩 → JSON | 同上；`--term` 缺省查全部学期 |
| `fetch_power.py` | 寝室电费 → JSON | 新开普缴费平台，学号 + 查询密码；**与教务链路无关** |
| `fetch_transcript.py` | 教务处盖章成绩单 → PDF | 金格签章系统（`jwxyxx`）；共用 CAS，但**不经过强智教务**，见 §5.6 |
| `gen_week_layout_preview.py` | 生成课表排版提案 HTML | 与爬取无关 |
| `out/` | 抓取产物与页面快照 | 快照可当解析器回归 fixture |
| `_archive/` | 历史一次性探测脚本 | **勿依赖**，仅留档溯源 |

---

## 3. 登录链路

```
[1] 门户 CAS 拿 TGC
    GET  http://portal.juwp.edu.cn/cas/login_portal
    → GET https://eapp2.juwp.edu.cn:9443/cas/login?service=<门户>/cas/login_portal
    → POST 同一 URL（username / password / execution / _eventId=submit）

[2] 教务 SSO
    预热 https://jiaowu.juwp.edu.cn:81/     ← 必须，用于拿 bzb_njw，缺了教务不认
    → GET cas/login?service=http://jiaowu.juwp.edu.cn/sso.jsp
         service 不能带 :81 / :8080，带了会 500
    → 302 链：sso.jsp?ticket=… → sso.jsp → :8080/jsxsd/xk/LoginToXk?method=jwxt&ticket1=…
    → 落到 http://jiaowu.juwp.edu.cn:8080/jsxsd/framework/xsMainV.htmlx
```

关键端口/域名：

| 项 | 值 |
|----|-----|
| CAS 统一认证 | `https://eapp2.juwp.edu.cn:9443` |
| 门户 | `http://portal.juwp.edu.cn`（HTTP；443 不通） |
| 教务登录页 | `https://jiaowu.juwp.edu.cn:81/` |
| 教务学生端 | `http://jiaowu.juwp.edu.cn:8080/jsxsd/` |
| 理论课表 | `/jsxsd/xskb/xskb_list.do?viweType=0`（学期参数 `xnxq01id`） |
| 实验课表 | `/jsxsd/syjx/toXskb.do`（学期参数 `xnxq01id`） |
| 考试安排查询 | 壳页 `/jsxsd/xsks/xsksap_query`；数据 `/jsxsd/xsks/xsksap_list`（学期参数 `xnxqid`） |
| 课程成绩查询 | 表单页 `/jsxsd/kscj/cjcx_frm`；数据 `/jsxsd/kscj/cjcx_list`（学期参数 `kksj`，空 = 全部） |

> `:81` 与 `:8080` 是**两套部署**，行为不一致（`:81/jsxsd/` 返回 404，`:8080/jsxsd/` 返回登录页）。不要混用。

---

## 4. ⚠️ 必须直连（踩过的坑，别再踩）

本机 shell 被注入代理变量：

```
HTTP_PROXY / HTTPS_PROXY / http_proxy / https_proxy = http://127.0.0.1:12892
```

`requests` **默认读取**它们。教务对代理出口与直连出口区别对待，后果是：

- SSO 落点 `/jsxsd/xk/LoginToXk` 返回 **404 通用错误页**
- `xsMainV` 退回 860 字节的「用户没有登录」
- 现象：**同一请求时而 200 时而 404**，极易误判成"教务挂了"

**对策**：所有 Session 显式 `trust_env = False`（`jw_session.new_session()` 已内置）。

- 不要绕过它自己 `requests.Session()`，否则会重新踩坑
- 临时复现对照：`env -u HTTP_PROXY -u HTTPS_PROXY -u http_proxy -u https_proxy python …`

**排查顺序**：现象自相矛盾时，先查客户端环境（代理 / DNS / 证书），再怀疑服务端。

---

## 5. 页面结构与解析规则

两个课表页**结构完全不同**，解析代码不可互相复用。

### 5.1 学期理论课表

`GET /jsxsd/xskb/xskb_list.do?viweType=0`

- 课程块：`li.courselists-item` > `div.qz-hasCourse-title`（名称）
- 详情：`span.qz-hasCourse-abbrinfo`，形如 `老师:唐刚;时间:1-10周[1-2节];地点:教学北大楼(北B102)`
- 星期：**只能按课程所在 `<td>` 在 `<tr>` 里的列序推**（第 0 列是节次标签，第 1–7 列是周一至周日）

两个必须注意的点：

1. `li` 上的 `qz-hasCourse-N` 在强智 newL 模板里**恒为 1**（模板拿它当「有课」样式用），**不能当星期来源**。
2. 列号要累加 `colspan`，并用 carry 记录 `rowspan` 的跨行占用——强智在「一天内连续两大节上同一门课」时合并单元格，不补偏移会让该行之后的星期整体前移。

### 5.2 实验课表

`GET /jsxsd/syjx/toXskb.do`（教务 → 实践实验 → 实验课表查询，菜单 `data-id = NEW_XSD_PYGL_WDKB_SYKBCX`）

- 表 `table.qz-weeklyTable`，表头 **9 列**：`周次 | 节次 | 星期一 … 星期日`（7 天，含周日）
- **每个周次占 6 行**：
  - 首行 9 个 `td` = `[周次标签 rowspan=6][节次标签][星期一 … 星期日]`
  - 其余 5 行 8 个 `td` = `[节次标签][星期一 … 星期日]`
- 有课单元格：`td.qz-weeklyTable-td.qz-hasCourse.qz-mixrow`

字段来源：

| 字段 | 来源 |
|------|------|
| 星期 | `day = idx - (len(tds) - 8)`（按行形态右对齐） |
| 周次 | 向上取所属 `tr`，再取其周次标签 `td`（`class` 含 `qz-weeklyTable-label` 且带 `rowspan`） |
| 节次 | 所属 `tr` 的节次标签 `td`（`1-2` / `7-8` / `11`） |
| 名称 | `div.qz-hasCourse-title` |
| 地点 | `div.qz-hasCourse-detailitem` |
| 教师 | **页面不提供**，留空 |

三个坑：

1. 该页**没有** `td[name=kbDataTd]`，也**没有** `老师:X;时间:Y;地点:Z` 合并文本——`abbrinfo` 里只有地点。
2. 周次**不在课块里**，在它所属的**周次行分组**上，必须向上找 `rowspan` 标签。
3. tooltip 里的「节次：60304」是**页面内部编码**，不是真实节次，不要用。

**必须聚合**：同一门课在每个有课周次各出一块（实测「机电传动控制B / 工程训练中心207」在第 1、3 周各一块）。按

```
(name, day, startSection, endSection, teacher, position)
```

聚合，`weeks` 取**并集**。聚合键**必须含 position**：工程训练按批次分周上课，同名课可能在不同实训室（实测「机械制造基础A」分布在 212 / 105 / 403）。

实测：16 个原始课块 → 聚合后 12 条。

### 5.3 App 端 WebView 注入实现要点

App 不跑 Python，改为在用户在 WebView 里自行登录后注入 JS 抽取。实验课表的关键逻辑：

```js
// 逐个周次分组扫描；week 只在首行出现，需跨行保持
(function () {
  var out = [], week = null;
  var tbody = document.querySelector('tbody.qz-weeklyTable-thbody');
  var rows = tbody.querySelectorAll('tr');
  for (var r = 0; r < rows.length; r++) {
    var tds = rows[r].querySelectorAll('td');
    var first = tds[0];
    if (first.getAttribute('rowspan') &&
        (first.className || '').indexOf('qz-weeklyTable-label') >= 0) {
      var w = (first.textContent || '').trim();
      week = /^\d+$/.test(w) ? parseInt(w, 10) : null;
    }
    var secIdx = tds.length === 9 ? 1 : 0;
    var sec = (tds[secIdx].textContent || '').trim();          // "3-4" / "11"
    for (var i = 0; i < tds.length; i++) {
      if ((tds[i].className || '').indexOf('qz-hasCourse') < 0) continue;
      var day = i - (tds.length - 8);                          // 1..7
      if (day < 1 || day > 7 || week === null) continue;
      var lis = tds[i].querySelectorAll('li.courselists-item');
      for (var k = 0; k < lis.length; k++) {
        var t = lis[k].querySelector('.qz-hasCourse-title');
        var p = lis[k].querySelector('.qz-hasCourse-detailitem');
        if (!t) continue;
        out.push({
          name: (t.textContent || '').trim(),
          position: p ? (p.textContent || '').trim() : '',
          day: day, week: week, sections: sec
        });
      }
    }
  }
  return JSON.stringify({ ok: true, items: out, url: location.href });
})()
```

拿到 `items` 后在 Kotlin 侧聚合（同键合并 `weeks`），再转 `Course(kind = CourseKind.Lab)`。设计见 `DESIGN.md` §4.8。

### 5.4 考试安排与课程成绩（2026-09-19 实测）

两者都是 **layui 表格的 JSON 接口**，GET 即可，不需要解析 HTML；入口壳页：

- 考试安排：壳页 `/jsxsd/xsks/xsksap_query`（表单 `xnxqid` + `xqlb`），数据接口写在壳页 `<table data-url>` 属性里
- 课程成绩：表单页 `/jsxsd/kscj/cjcx_frm`（`kksj` + `kcxz`/`kcsx`/`kcmc`/`xsfs`）

```
GET /jsxsd/xsks/xsksap_list?xnxqid=2025-2026-2&xqlb=&pageNum=1&pageSize=200
GET /jsxsd/kscj/cjcx_list?kksj=&kcxz=&kcsx=&kcmc=&xsfs=&pageNum=1&pageSize=200
```

三个必须注意的点：

1. **分页参数名是 `pageNum` / `pageSize`**（强智 `window.initQzTable` 自定义），用 layui 默认的
   `page/limit` 拿不到数据。响应 `{code:0, count, data:[…]}`，`count` 是总数，超出单页按 count 翻页。
2. **不带 `.do`**。`cjcx_list.do` 这类带 .do 的同名地址返回「系统功能暂未开放」no-open 页
   （校方的功能开关），与「接口正常但 count=0」是两回事，脚本里必须区分。
3. 考试时间 `kssj` 是单一字符串 `"2026-05-18 08:30~09:55"`，拆解后使用。

关键字段（强智原名 → 含义）：

| 考试安排 | 说明 | 课程成绩 | 说明 |
|----------|------|----------|------|
| `kskcmc` / `kch` | 课程名 / 编号 | `kc_mc` / `kch` | 课程名 / 编号 |
| `kssj` | 考试时间（含起止） | `zcj` / `zcjstr` | 数值成绩 / 字符串成绩（等级制以 str 为准） |
| `js_mc` | 考场 | `jd` | 绩点 |
| `ksxq` / `xqmc` | 考试校区 / 校区 | `xf` / `zxs` | 学分 / 总学时 |
| `jsxm` | 授课教师 | `ksxz` / `ksfs` | 考试性质（正常/补考）/ 考试方式（考试/考查） |
| `zwh` / `ksccmc` | 座位号 / 场次编号 | `kz` | 0=已认定；1=请评教（成绩被锁定） |
| `kw0410id` | 备注详情 id（`xsksap_bz.do?kw0410id=`） | `xnxqid` | 数据所属学期 |

数据发布节奏：考试安排由教务**考前数周才录入**，平时 `count=0` 属正常；成绩考后按批次录入，
`kz=1` 的课程在评教完成前不显示分数。两页壳页的 `select#xnxq01id`/`select#xnxqid` 选中项 = 该数据的实际学期。

#### 5.4.1 App 端同链路（WebView 注入 fetch，2026-09-19 实现）

App 与脚本走**同一组接口**，对应实现（改接口先改两处）：

| 环节 | 脚本（参考实现） | App 端 |
|------|------------------|--------|
| 登录 | `jw_session.py`（CAS→SSO，`trust_env=False`） | 用户在 WebView 自行登录，Cookie 全局 |
| 取学期 | 页面下拉 selected / `--term` | 注入 `READ_TERM_JS` 读壳页 `select#xnxqid` |
| 请求数据 | `requests.get(..., params=…)` | 注入 `FETCH_JS`：同源 `fetch(..., {credentials:'same-origin'})` 写 `window.__qzJson`，Kotlin 轮询 `evaluateJavascript` 回收（evaluateJavascript 不 await Promise） |
| 翻页 | 按 `count` 循环 | 同左（每页一次注入） |
| 解析 | `fetch_exams.py` / `fetch_scores.py` 的 `to_payload` | `ExamScheduleParser` / `ScoreParser`（样例 fixture 即脚本产物） |
| 落库 | `out/exams.json` / `out/scores.json` | 考试：`ExamMapper` → `Course(kind=Exam)` 进课表（日期→周/星期、时刻→相交节次，无法定位的跳过并提示）；成绩：`ScoreRepository.replaceTerm` 按学期整体替换 |

学期口径统一：脚本输出顶层 `term` = 实际爬取的学期；App 的考试导入走壳页下拉、成绩导入走
`kksj=''`（全部学期，按返回数据的 `xnxqid` 分组入库），与「我的 → 成绩查询」的学期 chips 对齐。

### 5.5 寝室电费（新开普缴费平台，2026-09-23 实测）

**不在教务域**，是另一套系统：新开普「移动服务平台」缴费（`charge.juwp.edu.cn`，前端 BladeX + Vue）。
登录用学号 + **缴费平台查询密码**，与教务/统一身份认证那套密码不通用（实测教务密码登录本平台返回
`{"error":"unauthorized"}`）。

```
POST https://charge.juwp.edu.cn/blade-auth/oauth/token   # 登录，access_token 有效期 3599 秒
     Authorization: Basic Y2hhcmdlOmNoYXJnZV9zZWNyZXQ=   # charge:charge_secret，前端公开常量
     username=<学号>&password=<查询密码>&grant_type=password&scope=all
     &logintype=student-sno-queryPassword                # 取值来自 GET /charge/logintype

GET  /charge/feeitem/showFeeitem                          # 收费项目清单（**免登录**）
GET  /charge/feeitem/singleFeeitem?feeitemid=181          # 详情；sceneinfo = 本人绑定房间
POST /charge/feeitem/getThirdData                         # type=select 逐级取场景；type=IEC 读电表
GET  /charge/turnover/personal_data?feeitemid=181&flag=3   # 电费充值流水
```

除登录与 `showFeeitem` 外，每个请求都带两个 header：`Authorization: Basic Y2hhcmdlOmNoYXJnZV9zZWNyZXQ=`
与 `synjones-auth: bearer <access_token>`。

平台当前只有**一个**收费项目：`feeitemid=181`「房间电费」，0.62 元/度，归属「生活缴费 / 财务处」，
`impl_interface=iECSceneServiceImpl`（电控场景，校区 → 楼栋 → 房间三级）。

`getThirdData` 有两种形态：

| 形态 | 请求参数 | 返回 |
|------|----------|------|
| 逐级取场景 | `feeitemid=181&type=select&level=<已选层数>` + 已选项（`campus`、`building`） | `map.total` = 层级定义（code/level/name），`map.data` = 该层候选 |
| 读电表 | `feeitemid=181&type=IEC&level=3&campus=0&building=0&room=14600` | `map.showData` = 电表读数（中文键），`map.data` = 房间元信息 |

四个坑：

1. **参数少一个就回 500**：`getThirdData` 缺 `feeitemid`，或只给场景三键却不给 `type=IEC`/`level`，
   一律返回 `{"code":500,"msg":"未知异常，请联系管理员"}`。别把它当成平台故障。
2. **登录端点不在 `/charge` 下**，是根域的 `/blade-auth/oauth/token`；业务接口才在 `charge.juwp.edu.cn/charge/…`。
   token 3599 秒过期，脚本一次登录一次用完，不落盘。
3. **读数在 `map.showData` 的中文键里**（`{"当前剩余电量":"55.57"}`），`map.data.remark` 是同一份
   JSON 字符串。平台加功能会加键，所以 `power.json` 除 typed 字段外还留了 `meter.fields` 原文。
4. **`sceneinfo` 里的校区名是学校旧名**（南昌工程学院），房间名以 IEC 返回的 `map.data` 为准。

App 端（水贝贝）已在生活页接入电费读数与 App 内充值（`blade-pay` 下单 + 电子账户
密码支付，DESIGN §4.24），脚本只产出 `scripts/out/power.json` 供本机查看。

### 5.6 教务处盖章成绩单（签章管理系统，2026-09-24 实测）

**不在强智教务里**。`fetch_scores.py` 抓的是成绩数据（`/jsxsd/kscj/cjcx_list`），
要出**带教务处章的正式单据**得走另一个系统：门户应用「电子签章成绩单」指向的
**金格签章管理系统** `jwxyxx.juwp.edu.cn/ptwork/`（前端 `author=yinqi`，开发者标注江西金格信安云）。

| 环节 | 请求 | 说明 |
|------|------|------|
| SSO | CAS `service=http://jwxyxx.juwp.edu.cn/ptwork/cas` | 复用 `jw_session.sso_ptwork`；**不需要预热**（`bzb_njw` 是教务域的怪癖）。落地 `mainIndex?isDd=1`，该域唯一 cookie `sid` |
| 列表 | `POST /ptwork/DzqzController/ddqzcjList` | form：`page` / `limit` / `sort` / `order` / `dysj`（空）/ `dytype`（空）/ `cjfs=1` / `xnxq`（可重复，空 = 全部学期）。应答 `[1,{ total, pages, pagePri, list:[…] }]`，`pagePri` 是不透明的加密条件串 |
| 出单 | `POST /ptwork/DzqzController/printStartCj` | form：`dysj`=上一步的 `pagePri` / `dytype=1` / `cjfs=1`。应答 `application/x-msdownload`，`filename=成绩打印表.pdf`，正文即 PDF |

四条实测口径（按直觉改会出错单）：

1. **`dysj`（pagePri）才是权威条件，`xnxq` 被服务端忽略**。故意制造不一致
   （`dysj` 指 2025-2026-2、`xnxq` 传 2024-2025-1）导出，出来仍是 2025-2026-2 的内容。
   所以只能「先列表拿令牌、再带令牌出单」，不能按学期号直接拼请求；导出请求也就不传 `xnxq`。
2. 列表 `limit` 无效，服务端固定 **15 行一页**；分页**不影响出单**（只翻出 15 行时，
   PDF 里照样是全部 16 门）。只有取学期清单才需要按 `pages` 翻。
3. `pagePri` 可重复使用，不是一次性。
4. **令牌存在不等于有数据**。无成绩的学期照样返回 token，出单时给 79 字节 HTML
   `parent.wzalert('未发现打印内容')`；令牌解不开时更糟——服务端返回一张
   **空白却带章**的模板 PDF（60KB、0 门课）。故出单前必须用 `total>0` 拦，拿到字节后还要校验 `%PDF`。

产物形态（拆包核对）：Aspose.Cells for Java v8.5.2 生成、iText 5.4.5 增量签名、
`Producer: www.tosign.cn`；A4 单页双栏，表头有院系/专业/班级/姓名/学号/年级/层次/学籍状态与证件照，
末尾「打印时间 + 学校盖章：」。章是压在「学校盖章：」上的**签章注释**（`/FT /Sig` + `/Subtype /Widget`，
`Rect[433.5 3.5 546.5 116.5]`，签名证书 CN = 江西水利电力大学），另含印章图与验签二维码。
**PDFium 系渲染器（含 pypdfium2）默认不画注释**，用它们截图自检会误判「没盖章」，要拿 WPS / Adobe 复核。

两个入口的区别（排查时容易被带偏）：左侧栏「电子凭证 → 电子成绩签章」
（`/DzqzController/ddqzcjFind`）与菜单首项「后台首页」（`/ptwork/main`）是同一套表单的两份模板、
接口完全一样；但「后台首页」那份的**学年学期下拉是坏的**——最高只到 2022-2023-2，
2020-2021-1 及更早共 37 个学期各重复一次（实测 83 项、去重后 46）。本脚本不解析页面下拉，
学期清单从接口取，故不受影响；网页上出单请走「电子凭证 → 电子成绩签章」。

红线：这份 PDF 含姓名、学号、证件照与全部成绩，**只落 `scripts/out/`（已 gitignore），不要外传、不要入公开仓库**；
签章系统只有 HTTP 明文（443 实测连接超时），CAS 票据与成绩单都明文回传，脚本不做任何规避。

---

## 6. 故障排查

| 症状 | 原因 | 处理 |
|------|------|------|
| CAS 失败、停在登录页 | 账号密码错 / 触发验证码 | 浏览器登录一次确认；检查 `credentials.local.json` |
| SSO 后「用户没有登录」（860B） | **走了代理** | 确认 `trust_env = False`；或剥离代理变量复现对照 |
| SSO 未进入教务 / 重定向过多 | service 参数带端口 | `service` 必须是 `http://jiaowu.juwp.edu.cn/sso.jsp`（不带 `:81` / `:8080`） |
| 课表页无「个人课表」标记 | 页面结构变更 | 重新抓快照，对比 §5 的类名与列序 |
| 考试/成绩接口返回「系统功能暂未开放」 | 用了带 `.do` 的地址，或校方关闭了功能 | 改用不带 `.do` 的接口地址；仍 no-open 则是校方侧开关，等开放 |
| 考试/成绩接口返回空但 len 也异常小 | 分页参数用了 `page/limit` | 改成 `pageNum` / `pageSize`（§5.4） |
| 课程全部堆在周一 | 用了 `qz-hasCourse-N` 当星期 | 改回按 `<td>` 列序 + carry |
| 周次解析为空 | 详情文本格式变化 | 看 `out/*_raw.json` 里的 `detail_raw` / `weeks_raw` |
| 电费登录返回 `{"error":"unauthorized"}` | 用了教务密码 | 填缴费平台查询密码（`powerPassword`），两套密码不通用（§5.5） |
| 电费 `getThirdData` 返回 `{"code":500,"msg":"未知异常…"}` | 参数不全 | 按 §5.5 的表格给全 `feeitemid` / `type` / `level` / 场景三键 |
| `blade-pay` `paystep=0` 返回 `{"code":500,"msg":"未知异常…"}` | 账号名下未支付订单堆积（平台不自动清） | `GET /charge/order/personal_data?paystatus=0` 列出后逐单 `POST /charge/order/deleteOrder`（**JSON body**）清理 |
| `paystep=2` 报「密码错误」 | 6 位消费密码输错（它是食堂 POS 支付密码，不是登录密码） | 用正确的消费密码重试；连续错误会锁，去一卡通 App/网页端重置 |
| 手上链接里的 `token=` 回 401 未授权 | 该 token 已过期（无 `exp` 声明，靠服务端会话） | 每次现登一次拿新 token，别复用旧链接 |

---

## 7. 红线

- 凭证只放 `scripts/credentials.local.json`，**已 gitignore**，禁止提交、禁止硬编码进 App 代码
- 缴费平台的**查询密码**（键 `powerPassword`）与教务 `password` 是两套，同样只放这份凭证文件，
  禁止写进 App 代码、文档或示例
- 抓取仅供本机调试；App 端默认由用户自己在 WebView 登录（代码不出现密码）；
  调课自动检测（DESIGN §4.17，默认关闭）开启后，用户显式提供的凭证加密存本机
  （`jw_credentials.xml`，已排除云备份与设备迁移），仅用于本机向教务登录
- 不刷积分、不绕过付费、不伪造官方身份
- 页面结构以 `out/` 快照为准；改解析器前先更新快照，避免对着过期结构改代码
