# AI 调课

作用域：改 AI 调课的 prompt / 工具解析 / 网络客户端 / 落库路径时读。规格见 DESIGN §3.25 / §4.39 / §4.40。

## 参考来源的许可证边界

界面形态参考了 rikkahub（上游项目，本机另有克隆），但**它是 AGPL-3.0，本项目是 MIT**。
AGPL 是强 copyleft：**抄它的代码会让整个仓库被迫改证**。所以只借鉴**交互形态与结构思路**
（滚动跟随可打断、过程/结论分层折叠、附件条在输入框内顶部……），代码一律自己写——
与项目对 NcepuJw 的既有口径一致（只取事实，不抄代码）。新增代码前先想一遍这条。

## 链路与红线

粘贴调课通知（文字或**截图**）→ `AiScheduleClient.chatStream`（OpenAI 兼容
`/chat/completions`，**流式 + 工具调用**）→ `AiScheduleTool.parse` → 结果卡勾选 →
`ScheduleRepository.applyLocalChanges`（课表改动 + 日期覆盖**同一事务**）。

- **模型的 tool_call 只是提议，不是执行**。落库必须过用户逐条勾选 + 确认；任何
  「自动应用」「后台应用」都是红线（与「后台静默改课表是红线」同口径）。
- **写库走 `ScheduleRepository.applyLocalChanges`，不推调课基线**。基线语义是
  「上次与教务一致的状态」，AI 改动与用户手动改课同类，推进它会让之后
  `ScheduleSync.check` 报不出教务的真实变动。别顺手换成 `applyScheduleChanges`
  （那个是调课检测的合并路径，末尾 `refreshBaselineFromDb`）。
- **无后台调用**：只有用户点「分析」才发请求；不挂周期任务、不挂冷启动。
  **v2 不做对话**：一轮 = 改通知 →「分析 / 重新分析」，**只认用户显式发送**——
  不自动追问、不自动重试、不自动补全上下文。追问链已**整链删除**（2026-10-07 简化：
  `previousJson` / `userMessage` 的 `previous` / SYSTEM 追问段 / `requirement` 诉求段
  全部移除，`userMessage` 只放事实四段；测试同步改写）。
- 手动触发意味着**失败不重试**（额度 / 限流 / 401 分类提示即可）。
- **SSE 解析绝不因「不认识的 delta」return**（2026-10-06 修的真根因）：`readStream` 曾在
  「delta 里没有 `tool_calls`」时 `return` 掐断整个流——任何一条正文 / 思考 / finish 块
  都会触发，工具参数只收到零个分片 → 上层解析空结果 → 用户看到「发什么消息都返回无效」。
  现在全部 `continue`；`toolIndex != 0` / 别的函数名同理**跳过**而非结束
  （`AiScheduleStreamTest` 用 okio `Buffer` 直接喂 SSE 钉住）。
- **会话与图片都不落盘**：会话在 `AiChatViewModel` 内存里，图片在 `filesDir/ai_img/`
  （清空输入 / 页面销毁 / 冷启动清空）。理由是会话含课表与通知原文，落盘要加密；而应用结果本来就在 DB。

## 工具调用口径

- 工具名 `propose_schedule_changes`（`AiScheduleTool.FUNCTION_NAME`），
  `tool_choice` 指定函数名强制调用。
- **`modify` / `remove` 只认编号**（`existingIndex`，1 起始），`old` 由 App 从本机课表取。
  **绝不采信模型回抄的旧字段**——模型重抄会漂移，`ScheduleDiff.fingerprint` 靠字段全等
  匹配本机行，抄错就静默跳过。改这条前先想清楚指纹匹配的后果。
- 编号基准 = **非考试**条目，且与发给模型的清单**同源同序**（`AiChatViewModel.courses`）。
  两处若用不同的列表，编号会整体错位。
- `readback`（**只在带图时有值**：模型复述它读到的通知，供用户核对 OCR）是给 UI 用的
  字段，不参与落库；`parsePartial` 不取它——半句话的核对块比没有更糟。
  `suggestions`（追问建议）已随对话式**整链删除**（schema / 清洗 / `Result` 字段 /
  prompt 文案 / 测试，2026-10-07 简化）——模型输出里再出现它会被 `ignoreUnknownKeys`
  无害忽略，别把它加回 schema。
- 模型幻觉的防御是**逐条丢弃**，不是整批失败：越界编号 / 缺必填字段 / 非法取值 /
  修改前后指纹一致 / 与已生效的日期安排等价 → 进 `Result.rejected`，其余条目照常产出。
  `AiScheduleToolTest` 钉住。
- 模型没走 tool_call 时的兜底：先从正文里 `extractJson` 抽一段配对完整的 JSON 再解析；
  再不行显示原文 + 复制，**永不空屏**。

## 三层防线总览（2026-10-07 定稿；判断「加不加东西」都看它）

功能定位一句话：**把学校用「日期语言」写的调课/调休通知，翻译成本机课表的一次可撤销编辑。**
模型是翻译器、App 是编辑器、用户是审批人。三条公理，历史上每个 bug 都能对回其中一条：

- **A1 模型不可信**（抄错 / 漏 / 幻觉）→ 引用式协议（编号引用，旧值本机取）+ 逐条校验
  丢弃（且丢弃**可见**）。别让模型复述整份课表——未改动的行也要抄，抄错就是假差异，
  抄漏就是静默删除。
- **A2 课表是「周次×星期」、通知是「日期」** → 「日期覆盖」是两套语言之间的换算层，
  不是可删的复杂度。模型做判断需要的三份事实 = 课程清单 + 学期日历 + **已生效的
  日期安排**（【已生效的日期安排】段），缺一份就会出现「同一通知重发仍报差异」这类现象。
- **A3 写入必须过差异预览 + 用户确认 + 可撤销**。任何「自动应用」都是红线。

**纪律**：prompt 里只允许放**事实**（原始数据），禁止再加描述性段落；每个新概念必须
只有一处定义（如「已生效」判据 = `ScheduleOverrides.sameEffect`，prompt 段 / 解析去重 /
写入幂等三处调用同一份）；给 prompt / 工具 schema 加字段前先对照 A1-A3 想一遍，
想不出对应哪条公理就不要加。

## DeepSeek 专有字段（最容易踩的坑）

- DeepSeek 的 `thinking` **默认开启**，而指定函数名的 `tool_choice` 在思考模式下直接
  返回 **400**（官方文档 Tool Calls 节）。所以请求体必须带
  `thinking: {"type": "disabled"}`。
- 该字段**只对 `baseUrl` 含 `deepseek` 的端点发**——其它 OpenAI 兼容服务不认识它，
  发了可能被判非法参数。用户可自定义地址，这个判断不能省。
- 要换成「深度思考」档就得把 `tool_choice` 降回 `auto`（思考模式不支持强制命名）——
  **2026-10-06 已按这条实现**：档位 ≠ 关闭时走 `auto` + prompt 要求调工具，
  正文 JSON 由 `extractJson` 兜底。预填模型名是 `deepseek-flash`（`AiScheduleConfig`；**2026-10-06 口径回正**——`deepseek-flash`（V4.1-Flash，2026-09-10 发布）才是现行官方名，`deepseek-v4-flash` 是已退役模型的 **legacy 别名**（上游仍接受、由 V4.1-Flash 服务）；读到 legacy 别名在 `AiScheduleConfig.normalizeModel` 迁到现行默认（含上一版误改落盘的值），
  可改、可从上游 `/models` 取。

## 凭证与隐私

- **API Key 只落 `AiCredentialStore`**（`secure_ai`，EncryptedSharedPreferences + Keystore
  自愈），**不进 DataStore**；`secure_ai.xml` 必须同时登记进 `backup_rules.xml` 与
  `data_extraction_rules.xml` 的 cloud / device-transfer **三段**（漏一段 = Key 上云）。
- `AiScheduleClient` **无任何日志拦截器**（对齐一卡通 / 电费 / 快趣 / U净）；派生只覆盖
  超时，不动 `AppHttp` 的共享件。
- 教师名脱敏默认开（`AiSchedulePrompt.maskedTeachers`，按姓名排序给稳定编号）；
  首次使用先弹隐私确认（`ai_privacy_ack`），确认前不发请求。
- 发送内容只有：当前课表（课程名 / 时间 / 周次 / 地点 / 教师名或脱敏名）、通知原文、
  诉求。**不带**学号、姓名、其它学期数据。

## 呈现：单页工作单（2026-10-07 v2 重构，用户口径定稿）

**不做对话**（用户口径：「不要做成对话的形式，保持简洁低调实用」）。v2 按
「太乱、不统一、排版丑、交互怪」的反馈整页重构，文件拆三件：
`ui/ai/AiChatScreen.kt`（壳 + 贴底动作条 + 弹层）/ `AiNoticeCard.kt` / `AiProposalCard.kt`。

- **① 通知卡**：`AppCard`；「添加截图 / 粘贴图片 / 清空」≥44dp 文字按钮；卡头状态词是
  唯一发送状态（未分析不显示 / 已分析 H:mm / 内容有改动（`noticeDirty()` 纯函数）/
  分析失败）。**不要**再引入「已发送的通知」第二份展示。清空只清输入，不动提案与回执。
- **② 结果区**：失败卡（`AiFailCard`）或提案卡。提案卡默认展开：头行（徽章 + 结论 +
  依据）→ 读图核对 → 统计条（全页唯一计数处）→ 明细两组（空组不渲染；行解剖对齐共享
  `ScheduleChangeCard`）→ 拒绝项（HugeIcons，非 emoji）→ 思考过程 → 全宽预览。
- **③ 贴底动作条**由 `aiBarSpec()` 纯函数唯一映射：分析 / 停止 / 重试（+ 次按钮
  「重新分析」，输入改过时用当前输入）/ 应用所选（N）+ 次按钮「重新分析」/ 应用中… /
  分析新通知。**空结果主按钮换「重新分析」**——不给死路的禁用「应用所选（0）」。
  改映射先改 `AiWorkflowTest`。
- **事务进行中 `phase = APPLYING`**：底栏变「应用中…」且不可点——apply 的防重入不只靠
  `applying` 布尔，`analyze()` / `retry()` 也靠这个相位被挡（否则事务在飞时点「重新分析」
  会让终态变成 READY+折叠）。`undo` 侧同理有 `undoing` 闸，且**先扣闸再进第一个挂起点**
  （先查课表再扣闸会被双击的第二下挤进来）。
- **折叠只在应用成功后**（摘要卡：绿点 + 已应用 N 处 + 时间 + 「撤销」直达）。
  **READY 不许折叠**——勾选是主战场（首版在结果就绪时折叠，被用户打回）。
- **撤销不进底栏**，只跟回执行走：卡内页脚 + 「上次应用」条（`AiReceiptRow` 共用）。
  **回执跨轮保留**：新一轮分析不清 `receipt`（`proposalSeq` 判归属），防重入闸也按它判
  ——别改回「receipt != null 就拒绝应用」。跨会话的撤销见下面「调课记录」节。
- **失败留在卡里**（不自动消失、不弹 toast、不空屏）：卡内给「去配置 / 去掉图片重发 /
  去开启图片识别」+ 已解析条数的一行说明（`totalCount > 0` 时，「分析未完成，不可应用」）；
  **重试在底栏**（首版两处都有重试）。
- **「去掉图片重发」= 把输入区的截图也移除**（`retryWithoutImages`），「本会话不再自动带
  通知截图」靠输入区诚实兑现——不要藏一个「挂着却不发」的过滤器（旧版 `imagesDisabled`
  写了没人读，就是反过来踩的坑）。
- **一处都没落库不算应用**：全被指纹挡掉时状态留在 READY、提示条数（`AiNothingApplied`）
  ——折叠成「已应用 0 处」是误导。
- **动效**：结果区按段出现 / 消失（骨架 / 统计 / 明细 / 预览）、折叠摘要 ↔ 展开体、
  思考过程展开、回执行 / 「上次应用」条、失败卡 ↔ 提案卡 Crossfade——统一
  `AnimatedVisibility`（220–300ms），别加新动画库。
- **思考过程默认折叠成一行**，展开后**必须限高**（`heightIn(max = 132.dp)`）+ 内部滚动。
- **预览全宽单栏** + 分段控件「应用前 / 应用后」（默认应用后）；周次 = 勾选改动涉及的
  第一周 ∪ 覆盖周（`previewWeeksOf()` 纯函数）——**别回落恒第 1 周**，那样改动与第 1 周
  无关时前后两栏长得一模一样。`changedCellSet` / `SchedulePreviewGrid` 是唯一渲染口径。
- 声明类弹层（隐私确认 / 图片拦截）用 `AlertDialog` + `DialogWindowFadeEffect`
  （ui-common 口径）；通知截图全屏看图走 `ImageViewerDialog`（`ImageSource.AiNotice`），
  不另写私有查看器。
- **`suggestions` 字段照发但 UI 不展示**（工具 schema / prompt 不动，砍掉的是追问 chips）。
- 状态模型 = 单份 `AiWorkState`（`AiChatModels.kt`）：`AiPhase` 七态 + `proposalSeq` +
  勾选集合 + 回执 + `collapsed`。新一轮分析替换结果字段但**保留 receipt**。
  追问链（previous / isFollowUp）随对话式一起砍，重试 = 同文本同图原样重发。

## 调课记录与跨会话撤销（2026-10-07，§4.39）

AI 页顶栏历史图标 → `SubpageScreen.AI_HISTORY`（`AiHistoryScreen` + `AiHistoryViewModel`）。
主页面的「撤销」只覆盖本次会话的回执，杀进程就没了；这一页是**跨会话**的长效留痕与
撤销入口。数据 = `data/repo/AiApplyHistoryStore`（`filesDir/ai_apply_history.json`，
CAP 50、新在前，用户自己的操作历史，**随备份走**——不排除）。

- **每次真正落库记一条**（`apply()` 成功后 `append`）；零落库不算（`AiNothingApplied`
  早退）。记录含撤销所需的全部信息：`appliedCourses/Overrides`、`skipped`、
  `timetableId`、`summary`（`aiHistorySummary()` 纯函数从**落库条目**取课程名）、
  存档行 `entries`、`overrideIds`。
- **撤销是栈式的**：`AiApplyHistoryStore.latestUndoable()` = 最新一条未撤销；
  撤掉它之后上一条才轮到可撤。**UI 与 VM 双处校验**同一条判据（`AiHistoryViewModel.undo`
  里也要判）。放开成「任意记录可撤」= 后来的改动压在旧快照上，半截还原。
  **课表已删除的记录不挡栈**：`latestUndoable(list, aliveIds)` 跳过它们（行上标
  「课表已删除」），否则一条撤不动的死记录会把更早的合法记录全部堵死。
- **撤销前核对目标课表还在**（`repo.timetableIds()`）：课表已删时反向写会向不存在的
  timetableId 塞孤儿行（任何界面查不到、也没人清）。三层防护一处都别拆——页面先拒绝
  并说清（两处 VM）、`undoApplied` 入口整批按跳过兜底。
- **`ScheduleCourseRow` 是留痕的存档形态**（域对象 ↔ 行的转换在本 store 文件里）：
  **行 id 与自定义时间三件套必须往返保真**——撤销按 id 删 / 覆盖，丢了 id 会撤错行、
  丢了自定义时间撤完会被清掉。它们**不进差异指纹**（`fingerprint` 不含 id / 自定义时间，
  差异比对口径照旧）。改 `of()` / `toCourse()` 前先看 `AiApplyHistoryStoreTest`。
- **主页与留痕按 `historyId` 同步**：`AiAppliedReceipt.historyId` 指向留痕 id；
  记录页撤过之后回主页，`refreshHistoryUndo()`（resumeTick）核对留痕并把会话回执标成
  已撤销——不做这步，「撤销」还亮着，再点只会撞指纹闸报一堆「跳过」。
  `undo()` 执行前也先核对留痕（跨窗口下单重入口不会重复跑事务）。

## 日期覆盖：调休 / 停课（2026-10-06，§4.40）

**这是「AI 调课答不出有用结论」的根治。** 课表是「周次 × 星期」两维的，学校的放假 /
调休通知却按**具体日期**发（「9月20日(星期日)按照9月28日(第五周周一)的教学安排进行」）。
不喂学期日历、不给表达日期安排的字段，模型只能回「没有需要改动的课程」——**别把这类
回答当成模型能力问题**，先查这两样在不在。

- **prompt 必须带 `AiSchedulePrompt.calendar(semester, today)`**：今天 + 每一周的起止日期。
  这是模型把日期换算成周次的**唯一**依据，删了调休立刻答不出。
- 工具多一路 `overrides`（`kind = follow | suspended`）：`follow` 走
  `sourceWeek` / `sourceWeekday`（照搬哪个槽位），`suspended` 是日期区间。
  两类改动在 prompt 里写死了分野，别把日期安排塞进 `changes`。
- 覆盖是**渲染层叠加，不写 `courses` 表**（`date_overrides`，Room v19）。键统一收敛成
  **(周次, 星期)**，日期 → 槽位走 `ScheduleOverrides.slotOf`。
- **`ScheduleCalculator.coursesOnDay` 是「(周次, 星期) → 课程」的唯一入口**，覆盖只在这里
  生效。今日页 / 周网格 / 桌面小组件 / 提醒 / 日历导出全部走它——**任何一处另写
  `week in it.weeks` 过滤都会让该页与其余页口径分叉**（这是本项目反复强调的纪律）。
  `weekColumns` 是网格用的逐列版本（`day` = 列，调休借来的课画在目标列上）。
- **FOLLOW 不递归**：借来的那天取源槽位的原始课，源槽位自己停课不影响它
  （「第 5 周周一停课 + 第 4 周周日上第 5 周周一的课」是两件事，别互相抵消）。
- 无效条目一律跳过：学期外、日期不合法、follow 缺源槽位、`sourceWeekday` 不在 1..7。
  `ScheduleOverrides.MAX_RANGE_DAYS = 400` 防脏区间把摊平循环拖死。
- 写路径：`ScheduleRepository.addOverrides` / `removeOverrides` / `clearOverrides`。
  删课表与清空课程时**同步清覆盖**（`deleteTimetable` / `clearCourses` 里已挂）。
- **「已生效的安排」必须进 prompt**（2026-10-07 修）：`userMessage` 的
  【已生效的日期安排】段（`AiSchedulePrompt.overridesBrief`，空也要写「无」）——课表清单
  只含课程行，已应用的调休 / 停课对模型不可见，**同一通知重发会被原样再解析一遍、
  又报成待确认**（用户实测踩到）。解析层再兜一道：`parse/parsePartial` 收
  `existingOverrides`，与已生效安排**等价**（`ScheduleOverrides.sameEffect`：同 kind +
  同起止 + follow 同源槽位；note / id 不算）的提议进 `rejected` 并注明「已生效，无需重复」
  ——**不静默吞**。`addOverrides` 写入侧第三道幂等：等价条目不重复插。
- **同一槽位冲突的胜负 = 后应用的赢**：`plan` 摊平时后写的覆盖前面的，而 DAO 必须按
  `id ASC` 返回（写入顺序）——**别改回 `ORDER BY fromDate`**（2026-10-07 修：按日期排
  会让后应用的更早/更宽区间输给旧覆盖，胜负语义反转）。撤销删行后旧覆盖自动复活，
  这套「覆盖 + 撤销」才闭合。
- 「应用前」预览 = **现状** = 课程行 + 已生效覆盖（`existingOverrides`）；「应用后」=
  现状 + 本轮勾选（本轮覆盖旧的）。两栏都**必须**带 existing——只画本轮提案的话，
  调休应用完后「应用前」会把放假那天照常画出课，看着像「调课前」。

## 流式与思考档位（2026-10-06）

- `chatStream` 返回 `Flow<StreamEvent>`：`Reasoning`（`reasoning_content`）/ `Content` /
  `ToolArgs`（**分片**，必须累积拼完整再 `parse`）。别改回一次性响应——用户明确要「边出边显」。
- **思考档位与强制工具调用互斥**（DeepSeek）：`thinking` 默认开，而**指定函数名的
  `tool_choice` 在思考模式下返回 400**。所以档位 = 关闭 → `thinking: disabled` + 指定函数名；
  档位 = 开启 → `tool_choice: auto`，靠 prompt 要求调工具 + `extractJson` 正文兜底。
  `thinking` / `reasoning_effort` **只对 `baseUrl` 含 `deepseek` 的端点发**。
- 偏好键：`ai_thinking_effort`（none/low/high/max，默认 none）、`ai_show_reasoning`
  （默认关）。档位只在配置页改；思考过程可见性是纯展示开关。
- 流式响应的 `readTimeout` 不会触发（持续吐字节），总时长靠 `callTimeout 600s` 兜。

## 图片（2026-10-06）

调课通知十有八九是群里的一张截图，所以「粘贴图片」是主入口，选图是备选。

- **链路**：选图（`PickMultipleVisualMedia(maxItems = 4)`，零权限）/ 剪贴板 → 复制压缩进
  `filesDir/ai_img/`（`AiImageStore`，**会话级**：清空输入 / 页面销毁 / 冷启动清空）→ 发送时读文件
  base64 成 `data:image/jpeg;base64,…` → 请求体 `content` 升成
  `[{type:"text"},{type:"image_url",…}]`，**文本在前、图片在后**（顺序影响模型理解）。
  纯文本时 `content` 仍是字符串，与非视觉模型、老网关保持兼容。
- **强制转 JPEG**（`ImageImport.copyInto(forceJpeg = true)`）：小图走流式复制那条分支时，
  HEIC 会被命名成 `.jpg` 而内容是 HEIC（扩展名骗人），base64 出去上游必拒。
- **压缩口径比笔记附件紧**：长边 ≤1600、q80（笔记是 1920 / q88 / 1.5MB）。base64 膨胀
  约 33%，笔记那套在 4 张时会撑到 ~8MB 请求体，必然超时。单次上限 4 张。
- **EXIF 方向在 `ImageImport` 里统一转正**（2026-10-06 补，笔记附件与课表背景图一并受益）：
  `BitmapFactory` 不认 `TAG_ORIENTATION`，横拍的照片解出来是躺倒的。方向不为 1 时
  必须重编码把方向**烘进像素**，否则 App 内渲染与发给上游都是躺的。
- **能力判定探测不了**：模型名是用户手填的，上游 `/models` 也不给能力标记。所以配置页一个
  **默认关**的开关 `ai_supports_image`；`AiScheduleConfig.looksVisionModel` 只做**打勾建议**
  （`-vl` / `vision` / `gpt-4o` / `gemini` / `claude-3+` …），**绝不自动开**。
  `deepseek-flash`（V4.1-Flash）**在**建议名单里：官方 model card 标明多模态（文本 + 图片）；
  仍是「建议打勾」而非自动开——自己配的网关吃不吃图以实测为准。
- 开关关时图片按钮**仍可点**，发送前拦一次（给「去开启」这条正路，也允许强行发）。
  比直接禁用少一个死胡同，也避免「上游静默忽略图片」这种更难查的失败。
- 追问轮**默认不带图**；但**通知本体就是图片时，追问必须重发它**——否则模型不知道通知
  是什么。所以追问携带的图片 = 首轮图片 + 本轮新加的。
- `AiScheduleClient.looksLikeImageRejection(code, detail)` 只作**提示**用（决定给不给
  「去掉图片重发」），不是硬判定：4xx 且报错里出现图片/模态相关词就算。

## 撤销（2026-10-06）

- `applyLocalChanges` 会把**每条真正落库的改动**记成 `AppliedEntry`（`before` / `after`），
  并返回写入的日期覆盖行 id。撤销 = 反向事务，课表部分与覆盖行在**同一个事务**里。
- **规划与执行分开**：`domain/ScheduleUndo.plan(entries)` 是纯函数（JVM 可测，
  `ScheduleUndoTest` 6 例钉住倒序与逐类型反向），仓储只按 `Action` 落库。
  撤销是本功能唯一需要新写事务逻辑的地方，这层分离就是为了让它可测。
- **倒序**撤销：同门课被连续改两次时（先 modify 再 remove），正序撤会把中间态当终态。
- **REMOVED 的反向插回要过 `mergeKey` 闸**：用户若已把同门课加回来（新 id），
  再插一遍会出现两行同课——与写路径 ADDED 的防重同一把钥匙（`ScheduleUndoTest` 钉住）。
- 撤销**不碰基线**——与写入同语义。
- **撤销入口只跟回执行走**（摘要行右侧 / 卡内页脚 / 页面「上次应用」条，`AiReceiptRow`
  共用），**不进底栏**；回执跨轮保留（`proposalSeq` 判归属），重新分析不吞撤销入口（v2 口径）。

## 复核修复（2026-10-06 四轮，三路独立复查后逐条修）

改动都在下面这些「**看着对、其实没接线 / 会静默毁数据**」的点上。改这块前先读一遍，
它们全都是同一个毛病：**两处各写一套口径，或者承诺了却没接到渲染/落库路径上**。

- **「是不是追问」与「有没有上一轮结果」是两件事**。上一轮答空时 `previousResultJson()`
  返回 null，若据此判 `isFollowUp`，用户这一轮的追问文字会**整段丢掉**（模型对同一份通知
  再答一遍）。现在由 `AiTurn.isFollowUp` 决定（看位置），`previous` 只决定要不要附
  【上一轮结果】块。
- **`AiTurn` 快照**：`AiReplyItem.turn` 记下本轮实际发出的 文本 / 图片 / previous / 是否追问。
  重试必须**原样复用**——按「列表位置反推」会丢追问轮携带的通知截图、丢掉「这轮是追问」、
  非末尾重试还会取到更晚那轮的 previous 并把新回复追加到末尾。重试**原位替换**，不追加。
- **增量解析必须接到渲染上**。`parsePartial` 的结果此前只在 `state != STREAMING` 时渲染，
  等于每片白跑——现在流式期间也画结果卡（动作按钮显示「生成中」且不可点）。
  改渲染条件时别把这条又断掉。
- **脱敏占位名不许回写**。模型只见过「教师1」，追问轮又要求它「没提到的条目原样重发」，
  所以 `parse` 要收 `maskedTeacherNames`，等于占位名（或空串）的 teacher 当**未提供**。
  漏了这条 = 本机真实姓名被静默覆盖且无从还原。**name / position 的空串同一口径**
  （`isNotBlank` 收口）——position 进指纹，被模型回 `""` 静默清掉会以「调整」名义落库。
- **应用要防重入**。按钮在事务跑完前可点，第二次进来课表条目会被指纹挡掉、但日期覆盖是裸插
  → 同一条写两行，且回执被覆盖 → 撤销只删得掉一份而 UI 报「已回到应用前」。
  现在 = `applying` 布尔 + `phase = APPLYING` + 回执 `proposalSeq` 判归属三闸，
  `applyLocalChanges` 里再 `distinctBy` 兜一层。
- **撤销要过状态闸**。`AppliedEntry` 的 before/after 是应用那一刻的快照，应用后用户完全
  可能又手动改过——按快照硬覆盖会**静默吞掉用户的修改**。`ScheduleUndo.plan(entries, current)`
  逐条比指纹，对不上就跳过并把条数报给 UI。撤销按回执里记的 `timetableId` 落库
  （用户可能已经切了当前课表）。
- **预览与真应用共用 `ScheduleDiff.appliedCopy`**。两边各写一套时，`preview` 保留自定义时间
  而落库把它清了——用户批准的预览与结果不一致。清自定义时间的条件收窄成「时间真的变了」。
- **`CancellationException` 一律穿透**：`apply`/`undo` 的 `runCatching` 会把它变成一次静默
  失败，而事务可能已经提交（项目此前专门修过 10 处同款）。
- **落库的 launch 在 `viewModelScope`**，不挂 `rememberCoroutineScope`——点完立刻返回上一页
  会把写入取消掉（Room 事务回滚且无任何反馈）。结果用 `AiNotice` 一次性提示回吐。
- **`stop()` 必须能关 socket**。`chatStream` 是 `callbackFlow` + `enqueue`，`awaitClose`
  里 `call.cancel()`：协程取消打断不了阻塞的 `readUtf8Line()`，只有 cancel 能立刻断连接。
  用户点「停止」的意图是**别再烧额度**，停在协程层不够。
- **三类「静默成功」要拦住**：200 但不是 SSE（网关 HTML / 整体 JSON 错误页）→ 一行都解析不出
  时抛错；流中途的 `{"error":…}` 块原样抛；`finish_reason` 是 `length` / `content_filter` 抛错
  （截断后工具参数必然是半截 JSON）。
- **`tool_calls` 只认 0 号且名字对得上**：并行调用或网关注入别的函数时，不同来源的参数会交错
  拼进同一累加器，拼出来必然坏。
- **`extractJson` 取第一个能解析成 JsonObject 的片段**，不是第一个配对成功的——说明文字里的
  花括号会顶掉真 JSON。
- **`rejected` 必须在 UI 里可见**：模型给了提议但被 App 拒了时，只说「模型认为没有需要改动的
  课程」是误导。
- **图片生命周期有三条清理路径**（清空输入 / `onCleared` / 冷启动 sweep），且 `ai_img/` 与
  各 store 的 `.fallback.xml` 都在备份排除里（见 `backup_rules.xml` 注释）。
  `AiImageStore` 的 import / clear / sweep 由一把 `Mutex` 串行化，调用方另有会话代数校验。
