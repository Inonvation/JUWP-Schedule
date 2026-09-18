# JUWP Schedule（水贝贝）

江西水利电力大学课表 Android App。

**非学校官方应用**，详见文末免责声明。

## 你会得到什么

- 周课表 / 今日课表（11 小节网格、左右滑切换周次、时刻线、多课表）
- 教务系统登录导入（强智，已打通理论课表 + 实验课表）
- 课表 JSON 导入导出（字段对齐拾光课程表，便于互通）
- 胖乖生活：一键开水、余额查询、订单记录

## 环境要求

| 项 | 要求 |
|----|------|
| JDK | 17+ |
| Android Studio | Koala / Ladybug 及以上推荐 |
| Android SDK | Platform **35**，Build-Tools 35.x |
| Gradle | Wrapper 自带 **8.10.2** |
| Kotlin | **2.1.21** |
| minSdk / targetSdk | 26 / 35 |
| applicationId | `edu.jxslu.schedule` |
| 应用显示名 | 水贝贝 |

## 用 Android Studio 打开

1. 安装 [Android Studio](https://developer.android.com/studio) 与 JDK 17
2. **Open** → 选择本仓库根目录
3. 等待 Gradle Sync（首次会下载依赖，需能访问 Google / Maven Central / JitPack）
4. 选择设备或模拟器 → Run `app`

命令行构建（已装 Android SDK 时）：

```powershell
.\gradlew.bat :app:assembleDebug
# APK: app\build\outputs\apk\debug\app-debug.apk
```

若本机 `local.properties` 不存在，请按本机 SDK 路径自建：

```properties
sdk.dir=C\:\\Users\\<你>\\AppData\\Local\\Android\\Sdk
```

## 工程结构

```
app/src/main/java/edu/jxslu/schedule/
  MainActivity.kt           # 底部导航：今日 / 课表 / 我的
  SubpageActivity.kt        # 二级页容器（课表管理/设置/开水等）
  JwImportActivity.kt       # 教务 WebView 导入
  JuwApplication.kt         # ensureDefaults（节次/学期；课表不预置）
  Graph.kt                  # 单例 Repository 装配
  domain/                   # Course / TimeSlot / SemesterConfig / ScheduleCalculator（纯逻辑，可 JVM 测）
  data/local/               # Room：courses / time_slots / semester_config / timetables
  data/repo/                # ScheduleRepository + JSON 导入校验
  data/prefs/               # DataStore 显示偏好
  data/jw/                  # JwUrls + 强智理论课表 / 实验课表解析器
  data/qiekj/               # 胖乖生活 API（登录/开水/余额/订单）
  ui/today|week|me|water|jwvw|timetable|common|theme/
scripts/                    # 教务爬虫（Python，本机调试用，见 scripts/README.md）
DESIGN.md · PROMPTS.md · AGENTS.md · LICENSE
```

## 里程碑（见 DESIGN.md）

| 阶段 | 内容 | 状态 |
|------|------|------|
| P0 | 规划文档 | 完成 |
| P1 | 工程脚手架 3 Tab | 完成 |
| P2 | Room + 今日/周课表 UI | 完成 |
| P3 | JSON 导入导出 + 学期设置 | 完成 |
| P4 | 胖乖生活（登录/开水/余额/订单） | 已实现，待真机验证 |
| P5 | 强智教务 WebView 导入 | 完成 |
| P5b | 实验课表导入 | 完成 |
| P6 | 打磨 | 进行中 |

课表默认为空，不预置样例；请在「我的 → 打开教务 WebView」从教务导入学期课表。

作息表（11 小节、每节 40 分钟）见 DESIGN §3.5；课表按**小节**建网格，`startSection/endSection` 就是小节号。

单元测试：

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

## 发版

发版流程见 `.agents/skills/publish-release/SKILL.md`。对外产出的 APK 使用正式 keystore 签名，
`release.jks` 与 `keystore.properties` 不入库；缺失时构建自动回退 debug 签名（仅供本地调试）。

## 免责声明

本项目为学生自用学习项目，**非学校官方应用**，与江西水利电力大学无任何隶属或合作关系。

- 登录教务、调用胖乖生活接口等行为均模拟正常客户端操作，存在账号与接口变更风险，使用后果自负。
- 应用不上传任何数据到第三方服务器；账号密码仅由用户在 WebView 中手动输入，凭证不写入代码仓库。
- 严禁将本项目用于刷积分、绕过付费或任何伪造官方身份的用途。

## 参考

- 拾光课程表：https://github.com/XingHeYuZhuan/shiguangschedule
- 胖乖参考实现：https://github.com/Inonvation/light-life
- HugeIcons Compose：`com.github.rikkahub:hugeicons-compose`（JitPack）

## License

[MIT](LICENSE)
