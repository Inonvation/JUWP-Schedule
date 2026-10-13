# 百宝箱（图片水印 · 图片拼接 · 菜鸟批量查）

作用域：改 `ui/toolbox/`、`domain/ToolboxImage`、工具页的分享 / 保存出口、今日页「+」里
百宝箱那一行时读。规格见 DESIGN §3.30 / §4.47。菜鸟批量查（非图片工具）的口径
见本文件末节。

## 预览即所得（两条渲染入口共享一套参数）

- **所有尺寸只按图片宽度取比例**（`domain/ToolboxImage`）：字号、边距、底块、平铺块。
  图像高只参与「贴底」锚点计算。预览（长边 ≤ 1200）与成品（长边 ≤ 2600）各自解码一遍，
  用**同一支** `ToolboxRenderer` + 同一组参数渲染——预览什么样、存下来就是什么样。
- **不要给预览另写一套口径**，也不要引入绝对像素（dp/sp/固定 px）当几何量：一旦有
  「预览里看着合适、成品偏了」的路径，这条纪律就已经破了。
- `domain/ToolboxImage` 是纯函数，改动必须同步 `ToolboxImageTest`（字号比例 / 密度三档 =
  尾部空行 / 底块锚点与夹取 / 拼接取宽与总高 / 像素上限 / alpha 换算）。

## 水印：平铺块先扩边再旋转

- 文字 → `StaticLayout`（行距保持 1.0）→ 平铺单元 → `BitmapShader + TileMode.REPEAT`。
  **密度档位 = 平铺单元尾部加空行**，不要改成调行距——行距一改，块内两行文字自己的
  间距也跟着变。
- **旋转要先按包围盒扩边**（`rotateWithBounds`）：直接旋原尺寸块会在 45° 附近把字啃掉边
  （「一个木函」原版就是这个毛病，别学回去）。

## 拼接：内存三条纪律

1. **先读尺寸再解码**：`readImageBounds`（`inJustDecodeBounds`）拿全部尺寸，零像素内存；
2. **流式渲染**（`ToolboxRenderer.stitchStreaming`）：要一张、画一张、丢一张，
   **不要**退回「先全部解码成 List<Bitmap> 再画」——九张截屏同时解码就是上百 MB；
3. **上限两条**：总像素 > 2400 万按面积比开方等比缩宽（`stitchFitTargetWidth`），
   > 800 万换 `RGB_565`。目标宽取**最大源宽**（小图不放大），不做上下裁边。

## 智能拼接（2026-10-12，口径拆自 PPIICC）

- **默认开**（2026-10-12 用户口径：要 PPIICC 那套）。它**只适合「同一页面连续滚动」的
  一批截图**：一组不相关的图会被「公共头尾 / 重叠」识别裁掉真实内容。选择行下的说明
  必须写明这条适用边界，并保留「直接」一档作为出口。
- **精修（`RefineRadius`）别删**：真机那对 1080×2400 截图实测，分数地形是尖的——
  真对齐平均逐行差异 8.5、错一行 14.9、错两行 27.9；而「从大往小第一个达标」会选中
  错一行甚至错几十行的候选（档位越松越离谱，宽松档实测错 38 行）。所以找到达标候选后
  必须在 ±16 行里按**平均差异最小**重挑。`StitchScanTest` 的「精修把差一两行的候选
  拉回真对齐」用「随机行 + 纵向 3 行模糊」造同款地形钉住它（去掉精修这条会挂）。
- **接缝位置只看绝对对齐**：头高与重叠怎么分不重要，成品接缝 = `prevBottom - scroll`。
  头检测偏大时重叠会相应偏小，两者相加不变——调头/重叠时别被单项数字带偏。
- 三件事，都在 `domain/StitchScan.kt`（纯逻辑，`StitchScanTest` 钉住）：
  ① **公共头 / 尾** = 所有图对连续相同行的**最小值**（保守，宁可少裁不多裁）；
  ② **滚动重叠** = 从最大可能往小搜、对每个候选**逐行**验证，命中取最大；
  ③ 找不到重叠记 0 → 退回硬接（原版同款兜底）。
- **状态栏行不参与比对**：时钟 / 电量每张不同，会打断公共带的连续匹配。
  调用方把系统状态栏高度按 `statusBarRows` 传进去，`scan` 把它**直接计入头高**、
  从其后才开始验证（与 PPIICC 的 `h()` 同口径）。
- **前提：所有图同宽**（`StitchScan.sameWidth`，容差 2%），**高度可以不同**。
  PPIICC 要求宽高都相同是因为它按像素拷贝；我们按矩形绘制，没有这个约束
  （2026-10-12 用户报「刚刚能拼的，提示尺寸不一致」——上一版照抄了「宽高都相同」，
  把「系统长截图 / 裁过的图 + 普通截图混拼」这个常态挡在门外了）。宽度真的不同
  （不同设备/分辨率）仍退回直接拼接，并**把宽度打进提示**（`1080 / 1200`）便于诊断。
- 匹配一律归一到**同一工作宽度**（`StitchSmart.WorkWidth = 480`）：解码目标按
  「长边 × 宽高比」算（长截图不这么算会解出 135 宽再放大 3 倍），再精确缩到工作宽。
  结论按**每张图自己的纵向比例** `scaleY = 原高 / 工作高` 折算回原图坐标，
  别再用「原宽 / 工作宽」一个系数套全部（高度不同时那是错的）；
  **出图仍是原分辨率**（`stitchStreaming` 的 `keeps`）。
- **容差三档**（`Tolerance.Strict/Normal/Loose` = 每样点平均色差 90/150/220，0..765 量纲）：
  原版是「一行抽样像素方差和 vs 4000」，这里折算成人话便于测与调。**声明顺序 = 界面
  选项顺序**（严格 / 标准 / 宽松），`StitchScanTest` 钉住。
- **匹配的三道保险**（2026-10-12 第二轮加固，改 `findOverlap` 前先读）：
  ① **快筛抽行**（`FastVerifyStride = 4`）——先隔行比，累计平均超阈值即弃，
     挡掉绝大多数错位候选，只有活下来的才做全行精验（提速的关键，别退回全行逐候选）；
  ② **坏行预算**——精验看**带内平均**达标，且超阈值的行不超过 `max(1, length/20)`：
     真实截图偶尔有一行不同（光标闪、图没加载完），旧的「每行都必须达标」会把真重叠枪毙；
  ③ **纹理守卫**（`minBandVariation`）——命中带行内纹理低于阈值（大片纯色）时判歧义、
     返回 0 退回硬接。全白页任意偏移都"匹配"，宁留重复不可裁内容。
  另：小于 `minOverlapRows`（16 行）的重叠不当真，几行的巧合对齐比没识别到更危险。
- 已知边界：大片纯色 / 空白页的内容**天然不可区分**，最大重叠可能整段命中——
  这是算法固有歧义（PPIICC 一样），出口是切「直接拼接」或收紧容差，别在算法里硬堵。
- 匹配成本是 O(候选数 × 命中前的首行比对)，错位候选第一行就 break，所以很快；
  但「首行全同」的候选会跑满内层循环，长空白页会明显变慢——先测再动。

## 出口只有两条

- 保存：`ui/common/BitmapSave.saveBitmapToGallery`（MediaStore `Pictures/水贝贝`）；
- 分享：`Graph.toolboxShareStore`（`ScheduleShareStore` + `toolbox_share` 目录，
  FileProvider 根在 `res/xml/file_paths.xml`）→ 系统分享面板。
- 格式 **JPEG q92**（`ToolboxExport`）：产物是截图 / 照片，PNG 体积放大一个量级。
  要改格式先想清楚这一点，并一并改文件名后缀。
- 分享的拉起方式与课表导出逐字对齐（`Intent.createChooser` + `FLAG_GRANT_READ_URI_PERMISSION`），
  不套 `WindowTransitions.startActivityOutsideApp`——那是给跨 task 的外部页面用的。

## 两遍解码的判空陷阱（2026-10-12 真机踩过）

- `inJustDecodeBounds = true` 时 **`BitmapFactory.decodeStream` 必定返回 null**（只量尺寸、
  不产图）。所以**不能**把它的返回值串进 `?:` / `takeIf` 之类的判空链：
  `openInputStream(uri)?.use { decodeStream(it, null, bounds) } ?: return null`
  会把「量到了尺寸」误判成「流打不开」，整条两遍解码永远返回 null——各图片工具全读不出图。
- 正确形态（`ui/common/DecodeSampled.kt` 的 `decodeUriSampled` / `readImageBounds`）：
  **流单独取出来判空**，`use { decodeStream(...) }` 只当语句用，成败看 `bounds.outWidth`。
- 解码失败**不许静默**：预览 / 缩略图读到 null 时必须发提示，否则用户只看到空白预览，
  报障也只能说「读不出来」。

## 平铺块尺寸取自文字实际渲染宽度（2026-10-12 真机 abort 的真因）

- **`Layout.getWidth()` 返回的是构造时传进去的宽度，不是最长行的宽度。** 水印的平铺块
  原先拿 `layout.width` 当位图宽，而 `StaticLayout` 为了「不换行」传的是 `Int.MAX_VALUE`，
  于是成了 `Bitmap.createBitmap(2147483647, h, ARGB_8888)`：原生 `SkBitmap::setInfo`
  失败、colorType 留成未知，`LOG_ALWAYS_FATAL("unknown bitmap configuration")`
  **直接 abort 整个进程**（不是抛异常）。宽度只能现算：布局宽取最宽行的
  `paint.measureText`，块宽再取 `layout.getLineWidth(i)` 的最大值
  （`ToolboxRenderer.watermark`）。
- **现象会骗人**：进程 abort 后 SubpageStack 会把恢复的页面放回上层，用户报的是
  「选完图自动跳回上一页」。这类「页面自己退回去」先看 `adb logcat -b crash` 的
  `Abort message` 与 backtrace（这次是 `Bitmap_creator → allocateHeapBitmap`），
  别先查导航——2026-10-12 第一轮照 config 改过一轮，白改。

## 位图 config 仍走白名单（防患，不是上面那次的病因）

- 两道闸都保留：① 解码端钉死 `inPreferredConfig = ARGB_8888`（`ui/common/DecodeSampled.kt`
  两个解码口都钉）；② 出图端 `ToolboxRenderer.safeCopyConfig` 白名单
  （只放行 ARGB_8888 / RGB_565，HARDWARE 的 `getConfig()` 返回 null 也落回退）。
  目的只是别把 10-bit / HDR 照片解出的 `RGBA_F16` / `RGBA_1010102` 这类非常规 config
  漏给 `createBitmap`。

## 加新工具时的固定动作

图片工具走全 4 步；**非图片工具**（如菜鸟批量查）只做 1–2 步，3–4 步不适用——
纯逻辑按领域另起文件（`domain/CainiaoQuery`），不要为凑格式硬塞 `ToolboxImage` 那套。

1. `ui/toolbox/` 加一个页面（骨架照抄现有页：预览卡 → 参数卡 → 动作行）；
2. `SubpageScreen` 加一个值 + `SubpageActivity` 加路由 + `ToolboxScreen` 加一张卡；
3. 排版口径进 `domain/ToolboxImage` 并补测试；渲染进 `ToolboxRenderer`；
4. 出口直接用 `ToolboxExport`（保存 / 分享），不要再写一套。

## 菜鸟批量查（非图片工具，2026-10-12；入口 2026-10-13 移出百宝箱）

- **入口在生活页「取快递」弹窗**（「取快递」段末尾的整宽行），不在百宝箱首页（2026-10-13 移出）；
  页面与 `SubpageScreen.TOOLBOX_CAINIAO` 路由不变，改入口别动这两处。规则见
  `.agents/rules/life-power.md`。
- 链接模板只在 `domain/CainiaoQuery` 一处：
  `cainiao://startapp/search_alert?querySourceId=68719476739&prePath=Pasteboard&content=<单号逗号分隔>`。
  它走菜鸟导出的三方路由（`OpenGuoGuoUrlActivity`）转内部路由，**改任何一段前先在
  真机重验**（搜索页预填 →「去批量查询」→ 添加包裹页全部查完 →「添加到包裹列表」）。
  通道哪天收紧：换链接或整体下线，`CainiaoQuery` 是唯一改动点。
- 拉起复用 `ShortcutLauncher`（二级页拉起外部应用要换静止过渡，它有现成处理）；
  未装菜鸟先判 `getLaunchIntentForPackage` 给明确提示。
- 输入清洗在 `CainiaoQuery.parseInput`：全角折半角 → 只留 [A-Za-z0-9-] → 8~40 位且
  含数字才算单号（否则计入忽略）→ 大小写不敏感去重 → 上限 20；返回 `ParseResult`
  （numbers + duplicates / ignored / overflow 三个计数，页面的计数行照它报数）。
  判据在 `CainiaoQueryTest` 里钉着，动它先想清楚粘贴场景：「运单号：JT…」要能洗出
  单号，「您有1个包裹」这类要挡住，丢弃的东西必须报数、不静默。
- 最近提交存 `cainiao_query_history`（`DisplayPrefsStore.updateCainiaoQueryHistory`，
  同值跳写、脏 JSON 回空表）；页面不做网络请求、不碰菜鸟账号。

## 页面骨架的两条注意

- **`produceState` 的类型安全重载最多 5 个 key**：参数超过 5 个就收成一个 `List` key
  （列表按结构比较，语义等价）——直接传 6 个 key 编译不过。
- 各工具页都要 `BackHandler(enabled = busy) {}`（导出中不接受退出）与
  `pinnedStatusBars()` 顶栏（页面会拉起相册与分享面板）。
- 主操作 / 出口按钮走 `ToolboxActionBar` **冻结在屏幕底部**（`surfaceContainer` 底 +
  `navigationBarsPadding`，挂在 `Scaffold` 的 `bottomBar`），内容区只放预览 / 参数 / 列表——
  重要按钮不随内容滚走。`busy` 时传 `progress = true` 在按钮上方加进度线。
