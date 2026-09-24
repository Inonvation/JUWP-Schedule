# 单测清单

作用域：找「某个行为被哪个测试钉住」时读。跑法与结果汇总见 `AGENTS.md`「常用命令」。

现数（2026-09-24 实测）：**80 个测试类 / 807 个 `@Test`**。改动后现查，不要手改这个数字：

```powershell
(Get-ChildItem -Recurse app\src\test -Filter *.kt).Count
(Get-ChildItem -Recurse app\src\test -Filter *.kt | Select-String -Pattern '@Test').Count
```

新增测试放 `app/src/test/java/edu/jxslu/schedule/`，类名以 `Test` 结尾。

## 清单

`ScheduleCalculatorTest`、`TimeSlotRulesTest`、`TimeSlotScheduleTest`（作息不变量）、
`WeekGridLayoutTest`（网格几何）、`QiangzhiScheduleParserTest`、`SyjxScheduleParserTest`、
`CasSessionTest`（会话编排：可信期/闸门/验证码不计数/并发只登一次/回灌续期）、
`CasLoginClassifierTest`（CAS 应答四分类：302 成功/凭证错/验证码/认不出的 200 不当凭证错）、
`LoginGateRulesTest`（防锁号闸门：窗口/计数/停用/可信期）、
`LoginStateRulesTest`（状态卡三档：凭证/网页会话/停用优先级）、
`CookieBridgeTest`（cookie 拼接：hostOnly 不写 Domain/Secure/HttpOnly/Path）、
`MemoryCookieJarTest`（分桶/快照/回灌/路径匹配）、`ProfileSyncRulesTest`（学籍卡补抓闸门：班级空/今日试过/隔日重试）、`TokenFreshnessTest`（落盘 token 新鲜度：过期/时钟回拨）、
`OneClickImportTest`（一键导入：页面形态判定/合成/警示文案/脏字段容错）、
`ImportJsonShapeTest`、`TodayStateTest`、`ParseWeeksInputTest`、`QiekjSignTest`、
`CourseTweakTest`（调课规划：拆分/覆盖/交换/同格去重）、`TodayBoundaryTest`（小组件边界闹钟时刻）、
`WidgetModelTest`（小组件：尺寸分档/行数预算/**明日接棒**/周网格列序与去重叠/旧 JSON 兼容）、
`ExamMapperTest`（考试→课条目映射，含历史学期估算）、
`ExamScheduleParserTest` / `ScoreParserTest`（注入 fetch JSON 解析）、`ScoreCalculatorTest`（学期/学年汇总）、
`ScoreGroupsTest`（成绩学年分组与年级标签）、
`ShortcutsTest`（快捷方式：拉起口径/表单校验/预设表/JSON 兜底/列表操作）、
`JwHttpSessionTest`（检测登录链路：重定向解析参数顺序、IPv4 优先 DNS）、
`EbikeQrTest`（共享单车出码：URL 拼装/车号校验/BitMatrix 参数/最近车号序列化）、
`EbikeFreeRideTest`（免费时长提醒：提醒点/下一个未过点/迟到窗口/去重键带起点/通知 id 不撞号）、
`WechatRentNoticeTest`（精确倒计时：只认微信包名/关键词命中先享后付/窗口两端与越界）、
`YktKeyboardTest`（校园卡键盘：字形 MD5 表/双射硬校验/密文构造/协议自检）、
`YktModelsTest`（一卡通响应解析：BOM 剥离/错误码/CARD 账户提取）、
`MarkdownParserTest`（Markdown 子集：块/行内/嵌套/未闭合按字面回退/中文数字混排）、
`MarkdownEditTest`（编辑器：列表续行全分支/选区包裹/`$` 自动配对/图片插入）、
`MathTexTest`（LaTeX 子集：支持清单逐条解析/排版几何/超范围回退 null）、
`NoteExcerptTest`（笔记摘要提取 + 正文 img 引用收集与移除）、
`HomeworkCenterTest`（作业排序：逾期→今天→未来→无截止 / 汇总 / 截止文案 /
`homeworkDisplayTitle` 摘要剥离：前缀/包边/空行/兜底）、
`HomeworkReminderTest`（作业提醒点与有效期窗口 / 越窗跳过 / 去重键）、
`CourseRemarkTest`（课程备注搬运：mergeKey 匹配/kid 区分/新行不覆盖/多行同 key）、
`ScheduleExporterTest`（日历/CSV 事件展开）、`ReminderPlannerTest`（提醒时刻与有效期窗口）、
`CalendarSyncDefaultsTest`（日历提醒档位表）、`TimetablePrefsDefaultsTest`（显示偏好默认值契约）、
`GridFontDecouplingTest` / `GridFontScaleTest`（课表字号解耦与收敛）、
`PanelSnapTest`（面板高度吸附）、`CompactPositionTest`（地点压缩）、
`SubpageStackTest`（二级页离开位置：链增删/重建不重复/活窗口门控）、
`JsStringDecodeTest`（evaluateJavascript 返回值解码）、`JwImportDiagnosisTest`（导入失败诊断契约）、
`QiekjModelsTest`（胖乖响应包脏数据容错）、`YktPayCodeTest`（付款码矩阵参数）、
`YktRechargeSignTest`（充值下单签名）、`YktTurnoverSyncerTest`（流水增量同步纯逻辑）
、`ScheduleBackgroundTest`（背景图：默认值/模糊档位到解码尺寸/文件名白名单）
、`XgUrlsTest`（学工：域判定不退化成子串匹配/状态条三档/统一认证入口钉子）
、`BikeNearbyTest`（附近单车：响应容错/聚簇/距离/状态推导）、
`KqcxBikeClientTest`（失败分类：超时与网络不可达不能混）、
`Gcj02Test`（WGS84→GCJ-02：境外不偏移/境内量级/相对距离不变）、
`PowerModelsTest`（电费响应解析：项目/读数/流水 + 500 与 401 外壳 + 剩余电量键回退）、
`PowerUsageTest`（用电统计：差分/充值折算/退款扣回/单价缺失两分支/算不出的段跳过/跨天均摊/周月桶/房间过滤）、
`LifeFeedTest`（一卡通与电费流水混排：排序/限量/同刻稳定/解析失败沉底）、
`DisplayPrefsDefaultsTest`（生活页默认开 + 既有开关默认值契约）、
`StartPageTest`（启动页：显示名/选项顺序/生活页关掉时不列/落回今日/脏值回退）、
`PowerClientUrlTest`（缴费页/账单页深链形态与 feeitemid 钉子）、
`BalanceAlertTest`（余额提醒：档位表/夹取/下标/文案、电费元换算三态、阈值边界、每日闸门）
、`TranscriptParsingTest`（盖章成绩单：应答解析与脏数据容错/学期归并/分页换算/魔数/失败归类/表单字段/文件命名）
、`TranscriptClientTest`（导出编排：翻页收学期/MAX_PAGES 兜底/令牌回传/三条失败路径/网络错分类）
、`TranscriptHistoryTest`（最近导出：`.part` 半成品不进列表/标签反推/同秒稳定排序/保留 10 份裁边/文案/文件名越界防护）、`AccountMaskTest`（学号遮罩：保留前后各两位/恰好六位/短于六位与空值返回 null）
、`CalendarSyncerPickTest`（日历同步挑目标日历：可写优先/隐藏让位给可见/只读跳过）
、`JwAutoLoginTest`（自动填表脚本生成：字段定位与提交、原生 setter、特殊字符转义）
、`PowerBillTest`（电费账单按月归集：按缴费日期归档/充值退款分列/倒序/月窗口跨年）
、`PowerPayTest`（电费充值：订单·渠道·密码挑战解析、密文映射、签名、订单失效文案）
、`StudentCardTest`（学籍卡解析：姓名班级学号，缺项返回 null）
、`YktArrivalTest`（充值到账判定：余额涨满订单额/多卡与缺基线不判定）
、`YktPayWatchTest`（付款码消费监听：水位之后命中/收入不算/同批不重复）
、`YktSyncGateTest`（流水同步闸门：进页间隔十分钟量级）
等 **80 个测试类 / 807 个用例**（2026-09-24 实测）。
