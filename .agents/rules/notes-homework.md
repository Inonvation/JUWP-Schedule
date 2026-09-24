# 笔记 · 作业 · Markdown · 课程备注

作用域：改笔记/课件、作业、自研 Markdown 与 TeX 渲染、课程备注搬运时读。规格见 DESIGN §3.11 / §4.20。

## 课程备注属于课程行

- 课程备注（DESIGN §4.3）属于**课程行**（同一门课的不同时段各写各的），存储在 `courses.remark`；
  覆盖导入（`replaceAllCourses`）会重建课程行，
  **必须**经 `domain/courseRemarksCarriedOver` 按 `Course.mergeKey()` 把备注搬回来——
  少了这一步的表现是「导入一次备注全没了」。`mergeKey` 的唯一实现在 `domain/Models.kt`，
  不要再往仓库里加第二份（导入去重、导入统计与备注搬运共用它）。

## 笔记·课件与作业的归属键

- 笔记·课件与作业（DESIGN §3.11/§4.20）归属键 = **课程名原样字符串**，不带 courseId、不带
  timetableId：课程行 id 在覆盖导入/调课/撤销里会被重建，绑 id 必丢数据；换课表后旧内容仍应可查。
  改归属口径先读 §4.20「归属」的取舍段。

## Markdown 与公式都是自研子集

- Markdown 渲染是**自研子集**（`domain/Markdown.kt` + `ui/common/MarkdownView.kt`）、公式是
  **自研 TeX 子集**（`domain/MathTex.kt` + `ui/common/MathTex` 绘制）：超范围语法**原样显示源码**，
  **不许**为了"好看"引入第三方渲染/公式库（体积与 `--offline` 构建是硬约束）。支持清单见 DESIGN §4.20。

## 编辑器自动补全只有一条口径

- 编辑器自动补全（列表续行 / `$` 补全 / 选区包裹）**只有一条口径**：`domain/MarkdownEdit.kt`
  纯函数，UI 只负责把结果应用回 `TextFieldValue`；不要在 Composable 里另写续行判断。

## Markdown 段落图片分块只有一条口径

- Markdown 段落的图片分块**只有一条口径**：`domain/Markdown.splitParagraph`——一行里除空白文本外
  只有一张图片即按块级画真图，否则留行内（可点「[图片]」标签）。单换行不构成新段落，
  「一次插两张图」与「图紧跟文字后面」在 AST 里都是同一个 `Paragraph`；**不要**只按
  「整段唯一节点是图片」判断（2026-09-24 的 bug：那样连图会渲染成一串蓝标签）。
  作业列表摘要（`homeworkDisplayTitle`）同口径：行内图片引用整条剥掉，剥完为空的图片行跳过。

## 笔记/作业的图片

- 笔记/作业的图片只走系统 Photo Picker（`PickVisualMedia`）+ 应用私有目录，**不申请相册权限**；
  正文引用形如 `![](img:文件名)`，删引用要同时清理文件（`data/repo/AttachmentStore.kt`）。
