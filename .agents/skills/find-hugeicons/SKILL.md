---
name: find-hugeicons
description: Use this skill when the user wants to find or search HugeIcons by keyword, or when you need to look up what icon names are available in the HugeIcons library before using them in Compose code.
---

# 查 HugeIcons 图标名

从 Gradle 缓存里的 HugeIcons 依赖 JAR 检索可用图标。**图标名不许猜，必须先在 JAR 里查到。**

## 何时用

- 用户要求按关键词找图标（"找个搜索图标"、"设置用什么图标"）
- 你准备写 Compose 图标代码、要确认图标名是否真实存在
- 想知道某个概念下有哪些图标可选

## 图标来源

本项目依赖 `com.github.rikkahub:hugeicons-compose:1.4`（JitPack，`isTransitive = false`）。
JAR 在 Gradle 缓存中，**不要在源码工程里找**。

## 检索步骤

### 1. 找 JAR

```powershell
$jar = Get-ChildItem "$env:USERPROFILE\.gradle\caches" -Recurse -Filter "classes.jar" -ErrorAction SilentlyContinue |
       Where-Object { $_.FullName -like "*hugeicons-compose-1.4*" } |
       Select-Object -First 1 -ExpandProperty FullName
$jar
```

### 2. 按关键词检索

用 JDK 自带的 `jar.exe`（优先从 `JAVA_HOME` 取，取不到再退回已知安装路径）：

```powershell
$jarTool = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin\jar.exe" } else { "C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot\bin\jar.exe" }
if (-not (Test-Path $jarTool)) { $jarTool = (Get-Command jar.exe -ErrorAction SilentlyContinue).Source }
& $jarTool -tf $jar |
  Select-String "settings" | Select-String "stroke/.*Kt.class" |
  ForEach-Object { ($_ -replace '.*me/rerere/hugeicons/stroke/', '') -replace 'Kt\.class', '' }
```

换关键词就改 `Select-String "settings"` 里的词，例如 `water`、`calendar`、`arrow`、`search`。

### 3. 结果处理

- 把命中的图标名列表给用户，并同时给出 import 路径与用法：

```kotlin
import me.rerere.hugeicons.stroke.Settings01

Icon(HugeIcons.Settings01, contentDescription = null)
```

## 约束

- 图标名是 PascalCase（如 `GlobalSearch`、`Settings03`、`GlassWater`）。
- 全部图标在 `stroke` 包下：`import me.rerere.hugeicons.stroke.*`。
- 本仓库**禁止 emoji 当功能图标**（见 `AGENTS.md` 硬性禁止）。
- 不要升级依赖版本：`isTransitive = false` 是刻意为之，打开传递依赖会拉进需 compileSdk 36 的
  `androidx.core` 1.17，导致 AGP 8.7 / compileSdk 35 编不过。
- 若缓存里有多份同版本 JAR（不同 Gradle 版本目录各一份），取任意一份即可，内容相同。
