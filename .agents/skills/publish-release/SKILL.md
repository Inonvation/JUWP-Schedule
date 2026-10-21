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
- **短句平铺，不嵌套**（2026-10-20 用户口径）：一条一个变化，单条尽量不超过 25 字；
  不要写列表套列表，也不要把一条堆成三四个分句。同属一个功能的改动可以合并成一条，但必须保持短。
- **开发期修掉的 bug 不进日志**：某功能首次发布前、开发过程中修掉的自身 bug（用户从未遇到）
  不写进日志——日志只列用户能感知的变化。
- BUG 修复与 UI 调整尽量合并同类项，**总条目 4–8 条**。
- 不出现技术名词（不要说 Retrofit / Room / KSP / 迁移 / 重构 等），
  写"修复切换周次时课程块错位"而不是"修复 columnOf 下标越界"。
- 纯中文（1.0.0 起各版本均为纯中文），格式：

```markdown
更新内容：

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

若推送失败（连接被重置、`Failed to connect to 127.0.0.1 port <端口>` 等）：

- **禁止私自开关代理**：电脑有时连的是校园网，校园网禁止挂代理；开不开代理只由用户决定。
- **先尝试直连推送**：`git -c http.proxy= -c https.proxy= push origin main`（临时清掉代理配置重试，不改全局配置；`Failed to connect to 127.0.0.1` 报错即 git 配置指向了已关闭的本地代理，这条命令同样能绕过）。网络抖动常见，直连失败可重试 1–2 次再放弃。
- 直连仍失败 → 停下告知用户，提醒**确认代理（端口 19191）已开启**，等用户确认后再带代理重推：
  `git -c http.proxy=http://127.0.0.1:19191 -c https.proxy=http://127.0.0.1:19191 push origin main`
  （这不是私自开代理——禁止的是启动代理进程，指向用户已开启的代理没有问题。）
- `gh` 的网络走自身认证链路，不受 git `http.proxy` 配置影响，不要用 `gh` 代替 git 推送提交

推送成功后再进入 Step 7。

## Step 7 · 创建 Release

标签与标题都用版本号（不带 `v`，不带应用名），正文用中文日志 + 免责声明。
预发布在命令末尾加 `--prerelease`；它只是 GitHub 侧标记，**不改 `latest.json`**（见 Step 7.5）。

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

## Step 7.5 · 更新 App 内「检查更新」清单（docs-public/update/latest.json）

**只有正式发布才更新这份清单。** 预发布（`gh release create --prerelease`）不改
`latest.json`，保持上一正式版——App 不读 GitHub 的 prerelease 标记，清单一旦指向预发布，
所有客户端都会被提示升级。预发布转正式时，再按本节更新并提交。

App 的「检查更新」（DESIGN §3.3）**不请求 GitHub API**：版本、日志、下载链接全部来自
仓库镜像 `docs-public/update/latest.json`（App 端 `RepoMirror` 多源轮询：gh-proxy 加速 →
jsdelivr → raw 兜底）。发版时必须
同步更新，否则用户检查永远停在旧版本：

```json
{
  "version": "<新版本号>",
  "apk": "JUWP-Schedule-<版本号>.apk",
  "sha256": "<Step 7 回读的 digest，去掉 sha256: 前缀、小写>",
  "notes": "<Step 4 用户已确认的更新日志原文>",
  "mirrorPrefix": "<沿用现值，除非该镜像已确认失效>"
}
```

- 字段含义与校验规则在 `domain/UpdateCheck.kt`（版本号三段数字、APK 名 ASCII 白名单、
  mirrorPrefix 必须 https）。`version` 必须与 tag 一致——App 用它直接拼
  `releases/download/<version>/<apk>` 直链。
- `mirrorPrefix` 是公共第三方镜像（gh-proxy 类）的前缀，**可以随时单独改、不用发版**；
  用户装机后实测不通就改这一个值。留空 = App 只出「GitHub 下载」出口。
- 提交随 Step 6 一起推（tag 指向的提交里必须带着新清单）。

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
- 禁止私自开关代理（校园网禁止挂代理）；推送失败先直连重试，仍失败才提醒用户确认代理已开启（端口 19191），再带代理重试
- 禁止把 `F:\JUWP-schedule` 之外的本地路径写进日志或 Release 正文
