# 今日页 · 课表网格 · 作息表

作用域：改今日页结构、课表网格几何、课程时间口径、课程配色、作息表时读。规格见 DESIGN §3.3 / §3.5。

## 课程时间只有一条口径

- 课程时间**只有一条口径**：`ScheduleCalculator.courseStartMinutes` / `courseEndMinutes`（自定义时间课以 custom 字段为准）。
  排序、行内时刻、倒计时、进度条、`coursePhase`/`nextCourse`/`dayLastEndMinutes` 全部走它，不要再自己写 `isCustomTime` 分支。

## 今日页结构：焦点卡 + 单时间轴

- 今日页结构：**焦点卡（正在上/下一节）+ 单时间轴**；焦点课从列表里剔除（`TodayUiState.listCourses`），
  同一节课不得两处出现；节次号只在焦点卡出现一次。改版前先读 DESIGN §3.3。

## 可见星期序列

- 可见星期序列以 `ScheduleCalculator.visibleDays` 为唯一来源、`columnOf` 取列下标；
  不要用 `day - 1` 当列号（隐藏周六但显示周日时会错位）。

## 今日页底部固定区 TodayBottomDock

- 今日页底部固定区（快捷方式网格 → **快趣出行码整行卡** → 开水卡）是 `TodayBottomDock`，
  **钉在滚动区下方**、不进 `LazyColumn`；三态（加载/空/有课）共用同一份，别只改一处。
  **水宝宝一卡通卡已于 2026-09-24 自今日页移除**（能力收进生活页，DESIGN §3.13）——
  别按旧描述把它加回来；出行卡右侧「附近单车 ›」直达 `EBIKE_MAP`，右侧动作文本走
  `CardSideActionText`（与开水卡右侧余额同一组件）。整块内容可折叠（把手「江水生活」，
  展开态存 `DisplayPrefs.todayDockExpanded`，默认展开），**折叠动画只做高度、锚点必须选 Top**
  ——锚 Bottom 或再叠一层 slide 都会变形，两个失败版本记在 DESIGN §3.3，别重复试。
  改版前先读 DESIGN §3.3。

## 课程配色

- 课程配色不按课程名哈希取（12 桶内必然撞色），走 `ScheduleCalculator.colorIndexesBySortedName` / `nextColorIndex`。

## 作息表结构版本

- 作息表结构版本存在 DataStore（`DisplayPrefsStore.slotSchemaVersion`）；改作息要同时调 `DefaultData.SLOT_SCHEMA_VERSION` 并给迁移。
