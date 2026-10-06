# JUWP Schedule（水贝贝）

江西水利电力大学校园生活 App —— 从教务一键导入课表，成绩及考试安排。顺手把学生每天要开的那些应用、网页和小程序收进一个应用里。

[![Android CI](https://github.com/Inonvation/JUWP-Schedule/actions/workflows/android-ci.yml/badge.svg)](https://github.com/Inonvation/JUWP-Schedule/actions/workflows/android-ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Latest Release](https://img.shields.io/github/v/release/Inonvation/JUWP-Schedule)](https://github.com/Inonvation/JUWP-Schedule/releases/latest)

<p align="center">
  <img src="screenshots/today.jpg" width="32%" alt="今日页">
  <img src="screenshots/week.jpg" width="32%" alt="课表页">
  <img src="screenshots/life.jpg" width="32%" alt="生活页">
</p>

> **非学校官方应用**，学生自用学习项目，详见文末[免责声明](#免责声明)。

## 这是什么

面向江西水利电力大学学生的 Android 校园生活 App。支持课表查询、期末考试安排、成绩查询与出分提醒、选课等；以及水宝宝一卡通、寝室电费查询与充值、胖乖生活开水与趣智校园热水澡、快趣出行、U净洗衣房等功能。

免费、无广告、无内购；**不上传任何数据**，账号凭证只留在本机。

## 功能一览

| 模块 | 有什么 |
|------|--------|
| 课表 | 教务一键导入（理论 + 实验）、教材查看、调课检测、上课提醒、日历同步、自定义背景 |
| 成绩 · 考试 · 学业 | 全学期成绩与绩点、学业达成四维度、出分与考试变动提醒、盖章成绩单 |
| 选课（beta） | 选课记录与轮次、轮次提醒、预选清单、应用内选课中心 |
| 笔记 · 作业 | Markdown + TeX 笔记、按课程分组作业与截止提醒 |
| 生活 | 一卡通余额 / 付款码 / 流水、寝室电费查看与充值 |
| 开水 · 热水 | 胖乖开水、趣智校园热水（余额 / 充值 / 账单） |
| 出行 | 快趣出行：地图找车、扫码、开锁还车、免费时长提醒 |
| 洗衣 | U净：空闲看板、扫码下单、支付宝支付、完成提醒、历史账单 |
| 校园服务 | 校历、学工表单（宿舍报修 / 请假）、取快递快捷方式 |
| 消息中心 | 考试 / 成绩 / 调课 / 选课 / 余额变动留痕，系统通知被吞也看得到 |
| 桌面小组件 | 课表、校园卡·电费、开水（固定 2×2） |
| 备份 | 课表 / 学期 / 作息 / 成绩 / 学业 JSON 导出导入 |

## 下载

从 **[Releases](https://github.com/Inonvation/JUWP-Schedule/releases/latest)** 下载最新正式包，资产名 `JUWP-Schedule-<版本号>.apk`。

- 应用完全免费、无广告、无内购；**只从本仓库的 Release 下载**，其他渠道流传的安装包与本项目无关。
- 每个版本的更新内容写在该 Release 的说明里。

## 怎么用

| 要做什么 | 去哪里 |
|----------|--------|
| 导入课表 / 成绩 / 考试 | 我的 → 教务，或对应页面内导入 |
| 成绩 / 学业 / 笔记 / 作业 / 选课 | 我的 → 学习 |
| 调课检测 | 我的 → 课表 → 调课检测（默认关） |
| 盖章成绩单 | 我的 → 教务 → 导出盖章成绩单 |
| 桌面小组件 / 上课提醒 / 日历同步 | 我的 → 提醒与桌面 |
| 主题配色 / 生活页开关 / 通知强提醒 | 我的 → 通用设置 |
| 快趣出行 / 趣智校园 / U净 / 校历 | 我的 → 校园服务 |
| 扫一扫 / 校园卡付款码 / 消息中心 | 今日页顶栏「+」下拉 |
| 免责声明 / 充值风险提醒 / 开源许可 | 我的 → 关于 |

## 数据与隐私

- 应用**不向任何第三方服务器上传数据**；所有内容留在本机。
- 教务与校园卡凭证使用 Android Keystore 加密存储，**已排除云备份**，也从不写入代码仓库。
- 保存凭证只为一件事：登录过期时自动续登，省去反复手输；随时可在「我的」退出登录并清除。
- 崩溃日志只落本机，可在「我的 → 关于」自行导出。

## 开发

环境：JDK 17 · Android SDK 35 · Gradle Wrapper 8.10.2（随仓库配置，minSdk 26 / targetSdk 35）。

```bash
./gradlew.bat :app:testDebugUnitTest :app:assembleDebug   # 单测 + Debug 包
./gradlew.bat :app:assembleRelease                        # R8 + 签名
```

架构、教务解析、WebView 导入与换校适配见 **[DEVELOPER.md](DEVELOPER.md)**；工程实况、协作约定与红线见 **[AGENTS.md](AGENTS.md)**；爬虫脚本见 **[scripts/README.md](scripts/README.md)**。CI（单测 + Debug/Release 组装）配置在 `.github/workflows/android-ci.yml`。

### 关于换学校适配

本项目只适配作者所在的江西水利电力大学，**暂无适配其他学校的计划**。如果你想给自己学校做一个定制版，欢迎 fork 本仓库，按 [DEVELOPER.md](DEVELOPER.md) 的「换一所学校：适配指南」替换教务地址、页面结构与品牌信息即可；登录链路、各系统协议、WebView 注入与构建坑等更详细的实现，也都在那份文档里。

## 参考与致谢

上游项目与参考实现：

- [拾光课程表](https://github.com/XingHeYuZhuan/shiguangschedule)：课表 JSON 互通格式
- [light-life](https://github.com/Inonvation/light-life)：胖乖生活（开水）参考实现
- [kqcx-bikemap](https://github.com/dlkz/kqcx-bikemap)：快趣出行车辆接口参考
- [linyu](https://github.com/yehu-imei/linyu) / [quzhi-lite](https://github.com/wzk-chi/quzhi-lite)：趣智校园接口参考

用到的开源库：

- HugeIcons Compose：`com.github.rikkahub:hugeicons-compose`（JitPack）
- 地图瓦片：osmdroid 6.1.18（`org.osmdroid:osmdroid-android`）
- 桌面小组件：Glance `androidx.glance:glance-appwidget` 1.2.0

## 免责声明

本项目为学生自用学习项目，**非学校官方应用**，与江西水利电力大学无任何隶属或合作关系。

- 登录教务、调用胖乖生活、趣智校园、快趣出行、U净与校园卡一卡通等接口，均模拟正常客户端操作，存在账号与接口变更风险，使用后果自负。
- 校园卡付款码**等同现金**：仅限本人使用，请妥善保管好隐私凭据等信息，请勿截图或分享给他人，泄露可能被盗刷；因启用相关功能造成的损失，开发者本人概不负责。
- 严禁将本项目用于刷积分、绕过付费或任何伪造官方身份的用途。
- 禁止在学校（含江水及其他任何学校）内售卖该软件及其脚本，一经发现将永久删除此仓库；
- 严禁将本项目用于攻击学校网站、爬取售卖敏感数据、逆向学校官网或教务系统等违规违法行为。
- 项目仅供学习交流使用；如因不正当使用造成任何损失，本人概不负责。如有侵权，请联系删除。

感谢使用。

## License

[MIT](LICENSE)
