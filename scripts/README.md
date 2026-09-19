# 教务爬虫脚本说明

江西水利电力大学教务（强智科技）的**课表 / 考试 / 成绩抓取脚本**说明。文档覆盖理论课表、实验课表、考试安排、课程成绩四条链路。

爬虫只在本机调试用，**App 端不跑 Python**——App 走 WebView + 注入 JS（见 [§5.3](#53-app-端-webview-注入实现要点)）。

---

## 1. 快速开始

```powershell
# 1) 首次：建虚拟环境并装依赖（在仓库根目录）
python -m venv .venv-scraper
.\.venv-scraper\Scripts\python.exe -m pip install -r scripts\requirements.txt

# 2) 填凭证
copy scripts\credentials.local.json.example scripts\credentials.local.json
#    编辑填入学号 / 密码（该文件已 gitignore，禁止提交）

# 3) 抓取（--term 均可省略；所有脚本顶层 term = 实际爬到的学期）
.\.venv-scraper\Scripts\python.exe scripts\fetch_courses.py       # 理论课表（缺省当前学期）
.\.venv-scraper\Scripts\python.exe scripts\fetch_lab_courses.py   # 实验课表（缺省当前学期）
.\.venv-scraper\Scripts\python.exe scripts\fetch_exams.py         # 考试安排（缺省当前学期）
.\.venv-scraper\Scripts\python.exe scripts\fetch_scores.py        # 课程成绩（缺省全部学期）
# 指定学期：加 --term 2025-2026-2（脚本会校验请求学期 = 教务返回学期，不一致即报错）
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

---

## 2. 脚本清单

| 文件 | 职责 | 备注 |
|------|------|------|
| `jw_session.py` | 共享登录：CAS → 教务 SSO → 会话校验 | 其余抓取脚本都依赖它 |
| `fetch_courses.py` | 学期理论课表 → JSON | 纯解析，不做登录 |
| `fetch_lab_courses.py` | 实验课表 → JSON（含周次聚合） | 纯解析，不做登录 |
| `fetch_exams.py` | 考试安排 → JSON | layui JSON 接口，不解析 HTML |
| `fetch_scores.py` | 课程成绩 → JSON | 同上；`--term` 缺省查全部学期 |
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

---

## 7. 红线

- 凭证只放 `scripts/credentials.local.json`，**已 gitignore**，禁止提交、禁止硬编码进 App 代码
- 抓取仅供本机调试；App 端默认由用户自己在 WebView 登录（代码不出现密码）；
  调课自动检测（DESIGN §4.17，默认关闭）开启后，用户显式提供的凭证加密存本机
  （`jw_credentials.xml`，已排除云备份与设备迁移），仅用于本机向教务登录
- 不刷积分、不绕过付费、不伪造官方身份
- 页面结构以 `out/` 快照为准；改解析器前先更新快照，避免对着过期结构改代码
