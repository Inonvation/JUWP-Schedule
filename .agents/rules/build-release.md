# 构建 · R8 · 签名 · 包名

作用域：改 `proguard-rules.pro`、改 `build.gradle.kts` 的 release 配置、发版、装包、排查「只有 release 才炸」时读。
版本号等工程实况见 `AGENTS.md`「工程实况」，跑测试与汇总用例数的口径见 `AGENTS.md`「常用命令」，
测试类清单见 `tests.md`。

## 改混淆规则后必须装 release 包冒烟

约束：动过混淆规则或 release 构建配置，必须装 release 包冒到真实网络路径，编译通过不算数。
理由：R8 的问题不在编译期暴露。

- **release 自 2026-09-21 起开启 R8（`isMinifyEnabled` + `isShrinkResources`）**：体积 17.9MB → 3.6MB。
  混淆规则改动（`proguard-rules.pro`）后**必须装 release 包冒烟**，且要冒到真实网络路径
  （胖乖开水这类 Retrofit 接口）——R8 的问题不在编译期暴露。已踩的三个坑：
  1. Tink 引用的 errorprone 注解、KeysDownloader 的可选依赖缺失 → `-dontwarn` 收口；
     **不要**写成 `-keep class com.google.crypto.tink.**`，那会把缺口一起保住；
  2. **只被泛型签名引用的模型类被整类删除**（2026-09-21 开水接口的真实根因，别再按"签名被剥"查）：
     R8 静态分析看不到使用者（Retrofit/序列化都走运行期反射），把 `data/qiekj` 的模型类
     （如占位类 `EmptyData`）与 `EmptyData$Companion`、`EmptyDataSerializer.INSTANCE` 删掉，
     于是 `ApiEnvelope<EmptyData>` 的签名实参退化成 `Object`，调用时抛
     `Unable to create converter for ApiEnvelope<java.lang.Object> for method …`；
     **只有用到被删类型的接口会炸**，其余接口正常——所以它看起来像"某个功能坏了"而不是"混淆炸了"。
     修法 = 整包 keep（`proguard-rules.pro` 里 `-keep class edu.jxslu.schedule.data.qiekj.**`）。
     定位手法：release 临时加 `-printusage`，报告里**没有冒号的行**就是被整类删除的类；
     DataStore/课表那几条 JSON 链是编译期 serializer，不受影响——**别只测它们就以为序列化没事**；
  3. 冒烟要覆盖「开了混淆才走到的分支」：`-printusage/-printmapping` 只在本地临时加（用完删），
     它们会把路径写进仓库文件。

## wrapper 缓存

- 换机/重装后若 wrapper 重复下载：把 Gradle 8.10.2 解压版拷进
  `~/.gradle/wrapper/dists/gradle-8.10.2-bin/<distributionUrl 的 MD5-base36>/`，
  补一个空的 `gradle-8.10.2-bin.zip.ok`、删掉 `.part`。**不要**改 `distributionUrl`（哈希变则缓存对不上）。

## 包名与两个独立应用

约束：release 包名 `edu.jxslu.schedule`，debug 加 `.debug` 后缀，两者签名不同。
理由：同包名会互相覆盖安装，数据全丢。
启动 debug 包必须写全限定名，`adb` 命令见 `AGENTS.md`「装真机」。

## 提交与推送

约束：提交信息精简，且不带个人信息。
理由：仓库公开，历史推上去就改不动；信息越少，出事的面越小。

- **作者身份**：`git config --local user.email` 已是 noreply 地址，不要改成真实邮箱。
  **在 GitHub 网页上改文件提交会写入账号当前绑定的邮箱**（历史上就漏过一次），
  去 Settings → Emails 勾「Keep my email addresses private」并开
  「Block command line pushes that expose my email」。
- **提交信息**：标题一行（`feat(范围): …` / `fix(范围): …` / `docs: …`），正文只写必要的
  「为什么」与影响面，不复述 diff、不写流水账。多个不相关的改动拆成多笔。
  历史里有长篇正文，那是旧习惯，不必照抄。
- **不带个人信息**：提交信息、代码、注释、测试 fixture、文档示例里不出现真实学号、姓名、
  手机号、寝室房号、真实邮箱。测试数据一律虚构（`示例学院` / `24示例专业01` / `9A101`）。
- **推送**：完成一批就推，不要攒。攒着会让 CI 少跑、冲突面变大，也容易漏推。
