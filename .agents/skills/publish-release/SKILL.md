---
name: publish-release
description: Use this skill when the user asks to publish a release, cut a version, ship an update, or asks about release notes / version numbers for JUWP Schedule (水贝贝).
---

# 发布新版本（JUWP Schedule / 水贝贝）

基于 git log 生成更新日志，构建正式签名 APK，然后用 GitHub CLI 创建 Release。

**每次发版都必须走完 Step 1–7；Step 4 的日志必须等用户确认后才能继续。**

## Step 1 · 前置检查（不通过就停下报告）

```powershell
# 工作区必须干净（未提交改动会被漏进日志）
git status --porcelain
# 必须与远端同步
git fetch origin && git status -sb
```

- 有未提交改动 → 停下，先让用户决定提交还是撤销。
- 有 PII / 凭证即将入库 → 停下（本仓库是**公开仓库**；`scripts/out/`、`scripts/_archive/`、
  `docs/`、`release.jks`、`keystore.properties` 必须已被 `.gitignore` 覆盖）。

## Step 2 · 确定版本号

`versionCode` / `versionName` 定义在 `app/build.gradle.kts` 的 `defaultConfig`：

- `versionName` 遵循语义化版本 `主.次.修订`；**tag 直接用 `versionName`，不带 `v` 前缀**。
- `versionCode` 为**单调递增整数**，每次发版 +1（应用商店与系统升级判定依据，绝不能回退）。
- 发版前先改这两个字段，提交为 `chore: 版本号 X.Y.Z`。
- 版本号怎么涨：新功能 → 次版本 +1；仅修 bug → 修订 +1；不兼容改动 → 主版本 +1。

```powershell
# 查看上一个 release tag
gh release list --limit 5
```

## Step 3 · 门禁：测试与构建全绿

**测试不过就不要构建、更不要发版。**

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleRelease --console=plain
```

- 汇总结果读 `app/build/test-results/testDebugUnitTest/*.xml`（看 `failures` / `errors` 属性），
  中文断言消息要按 UTF-8 读取，否则乱码。
- Gradle 成功时不打印用例数，别以为没跑。

## Step 4 · 生成更新日志（用户确认后方可继续）

```powershell
git log <上一个tag>..HEAD --oneline --no-merges
```

写作要求：

- 总结**用户能感知的变化**，不要罗列 commit。
- BUG 修复与 UI 调整尽量合并同类项，**总条目不超过 10 条**。
- 不出现技术名词（不要说 Retrofit / Room / KSP / 迁移 / 重构 等），
  写"修复切换周次时课程块错位"而不是"修复 columnOf 下标越界"。
- 中英双语，格式：

```markdown
更新内容：

- xxx
- xxx

Updates:

- xxx
- xxx
```

**生成后必须把日志贴给用户确认，得到明确同意后才能创建 Release。**

## Step 5 · 校验 APK（签名 + 版本）

```powershell
# 签名必须是我们自己的正式证书，不是 debug
$apksigner = (Get-ChildItem "$env:LOCALAPPDATA\Android\Sdk\build-tools\*\apksigner.bat" | Sort-Object FullName | Select-Object -Last 1).FullName
& $apksigner verify --print-certs app\build\outputs\apk\release\app-release.apk
```

- 证书 DN 应为 `CN=Inonvation, OU=JUWP-Schedule, ...`；
  SHA-256 指纹应为 `1b84b65210ed03da0f8761ec7b2d88ace8fe4bf498c2880aa331830f86c78a29`。
- 若显示 debug 证书或提示 `keystore.properties 缺失，release 回退 debug 签名`：
  **停止发版**，先找回 `release.jks` + `keystore.properties`（二者不入库，需用户离线备份）。

```powershell
# 版本号必须与 tag 一致
$aapt = (Get-ChildItem "$env:LOCALAPPDATA\Android\Sdk\build-tools\*\aapt2.exe" | Sort-Object FullName | Select-Object -Last 1).FullName
& $aapt dump badging app\build\outputs\apk\release\app-release.apk | Select-String "versionName"
```

**重命名 APK**（带上版本号，避免下载后分不清版本）：

```powershell
Copy-Item app\build\outputs\apk\release\app-release.apk "JUWP-Schedule-<版本号>.apk"
```

> **文件名必须用纯 ASCII**（2026-09-18 实测）：GitHub 上传资产时会**静默剥离非 ASCII 字符**，
> 上传 `水贝贝-0.1.0.apk` 会变成 `-0.1.0.apk`（即便 URL 已正确 percent-encode 也一样）。
> 用 `JUWP-Schedule-0.1.0.apk`；"水贝贝" 是应用**显示名**，只出现在 Release 日志里，不出现在标题和文件名中。

## Step 6 · 推送本地提交到远端

创建 Release 之前，本地提交必须先推送到远端；`gh release create` 创建的 tag 指向的提交必须在远端存在。

```powershell
git push origin main
```

若遇到 `unable to access 'https://github.com/...': Failed to connect to 127.0.0.1 port <端口>`：
- 说明 git 配置的 `http.proxy` / `https.proxy` 指向了已关闭的本地代理
- 先确认代理进程是否在运行；若代理已关闭，等代理恢复后重试推送
- `gh` 的网络走自身认证链路，不受 git `http.proxy` 配置影响，不要用 `gh` 代替 git 推送提交

推送成功后再进入 Step 7。

## Step 7 · 创建 Release

标签与标题都用版本号（不带 `v`，不带应用名），正文用中文日志 + 免责声明：

```powershell
gh release create <版本号> "JUWP-Schedule-<版本号>.apk" `
  --title "<版本号>" `
  --notes "<中文更新日志>

---

非江西水利电力大学官方应用，学生自用学习项目。登录教务与调用第三方接口存在账号风险，使用后果自负。"
```

- 只上传 release APK，**不要**把 `app-debug.apk` 传上去。
- 创建后必须回读确认：

```powershell
# 资产名、大小、digest 三项都要核对
gh api repos/Inonvation/JUWP-Schedule/releases/tags/<版本号> `
  --jq '.assets[] | "\(.name) | \(.size) | \(.digest)"'
```

- **用 `digest` 校验完整性**，不要依赖重新下载（本机代理对大文件会中途断流，
  下到一半的文件大小不符会误判成"上传坏了"）。digest 应等于本地
  `(Get-FileHash "JUWP-Schedule-<版本号>.apk" -Algorithm SHA256).Hash.ToLower()` 加 `sha256:` 前缀。
- 上传前先确认 APK 不早于源码：`Get-ChildItem app\src -Recurse -Filter *.kt |
  Where-Object { $_.LastWriteTime -gt (Get-Item app\build\outputs\apk\release\app-release.apk).LastWriteTime }`
  —— 有输出就说明 APK 过期，必须重新 `assembleRelease`。

## Step 8 · 发布后

- 打 tag 用 `gh release create` 已自动完成；本地 tag 与远端一致即可。
- 提醒用户：debug 包与正式包现在是**两个独立应用**（`edu.jxslu.schedule.debug` / `edu.jxslu.schedule`），
  可共存但**数据不互通**——测试期用 debug 包的用户想在新装正式包里看到自己的课表，
  需先在 debug 包「我的 → 导出」课表 JSON，装正式包后导入。
- 若用户装的是**旧 debug 包**（2026-09-18 之前的构建，仍占 `edu.jxslu.schedule`、debug 签名）：
  正式包无法覆盖安装（签名不符），需先导出课表 JSON → 卸载 → 装正式包 → 导入。

## 硬性禁止

- 禁止跳过测试或带失败测试发版
- 禁止在用户未确认更新日志前创建 Release
- 禁止上传 `release.jks` / `keystore.properties` / 任何含学号或 token 的文件
- 禁止用 debug 签名包对外分发（debug 与 release 签名不同，用户无法覆盖安装）
- 禁止复用或回退 `versionCode`
- 禁止用中文文件名上传资产（会被静默改名）
- 禁止把 `F:\JUWP-schedule` 之外的本地路径写进日志或 Release 正文
