# 任务：P1 工程骨架 + 探测模块（只打日志，不做任何拦截）

完整背景/产品决策/技术方案见同目录 `task-current.md`（P1 章节）、
`recon-x-app.md`（真机反编译证据）、`architecture-proposal.md`（方案对比与推荐）。
**这三份文件是权威依据，写代码前务必先读**。

## 本次任务范围（严格限定，不要扩大）

只做：①Gradle/AGP 工程骨架 ②一个只打日志、绝不修改任何数据/返回值的探测 Xposed 模块。
**不要**在这次任务里写真正的关键词过滤逻辑——过滤逻辑要等探测阶段在真机上跑出结论后
（P2 阶段）才能确定精确的 hook 点，现在写会建立在错误假设上，返工成本更高。

## 工程骨架（复用现成模板，禁止从零重新踩坑）

直接参照 `~/Desktop/redreader-ai-translate-xposed/` 这个本机已验证可用的 LSPosed 模块工程
（**只读参考，不要动它的文件**）：
- Gradle/AGP 版本、`settings.gradle`、根 `build.gradle`、`app/build.gradle` 的依赖配置照抄。
- Xposed API 用本地 jar：把 `~/Desktop/redreader-ai-translate-xposed/app/libs/api-82.jar`
  复制一份到本项目 `app/libs/api-82.jar`，`compileOnly files("libs/api-82.jar")`。
  **不要引用 `api.xposed.info` 的 maven 仓**，本机网络不一定可达。
  （另一个可参照的模板：`~/Desktop/doubao-letter-longpress-voice/`，如果 redreader 项目
  某处配置有疑问可以交叉核对。）
- `app/src/main/assets/xposed_init` 写入口类全名。
- `AndroidManifest.xml` 里 `xposedmodule`/`xposeddescription`/`xposedminversion=82`/
  `xposedscope` 四个 meta-data 都要有，`xposedscope` 填 `com.twitter.android`。

## 项目具体信息
- 本项目包名（applicationId）：`dev.xspamfilter.lsposed`
- Xposed 入口类：建议 `dev.xspamfilter.lsposed.HookEntry`（implements `IXposedHookLoadPackage`）
- compileSdk/AGP/Java 版本跟 redreader 项目保持一致即可，不要自己另挑版本号。

## 探测模块要做什么

在 `HookEntry.handleLoadPackage` 里，只在 `lpparam.packageName.equals("com.twitter.android")`
时生效。用 `findClass` + `XposedHelpers.findAndHookMethod`（找不到必须
`XposedBridge.log` 报错，不允许静默 catch 吞掉）挂载以下候选点，**每个候选点独立 try 各自
hook，一个找不到不能影响其它候选点**：

1. `com.x.models.PostResult`：hook 它的 `getText()` 方法（after hook），
   log 打印：`"[XSF-PROBE] PostResult.getText() -> "` + 拿到的返回值前 50 字符
   + `System.currentTimeMillis()`。
2. `com.x.models.timelines.items.UrtTimelinePost`：hook 构造函数（如果构造函数参数太多
   不好定位，改成 hook 它所有已声明的 public 方法里名字带 `getText`/`getEntryId` 的），
   log 打印类似格式，附带 `getEntryId()` 的值（帮助后续人工关联是哪条 timeline entry）。
3. `com.twitter.model.json.timeline.urt.JsonTimelineEntry`：hook `r()` 方法（after hook），
   log 打印该 entry 的 `entryId` 字段（字段名可能是 `a`，需要你自己反编译当前真机装的
   12.17.0 apk 核实准确的字段/方法签名，**不要直接照抄 recon-x-app.md 里的字段名当成金标准，
   那是另一次反编译的记录，字段短名在同一构建里应该稳定，但方法必须自己核实一遍**）。
4. `com.twitter.model.json.timeline.urt.JsonAddEntriesInstruction`：hook `r()` 方法
   （after hook），log 打印本次解析出的 entries 数量。

反编译当前真机 apk 的方式：`orchestra/evidence/x-base-12.17.0.apk` 已经在本项目目录里
（P0 阶段 orchestrator 已经 pull 过，不需要重新连接真机/走 adb——**这次任务完全不需要碰
adb，纯本地文件操作**），用 `jadx --no-res -d <out> orchestra/evidence/x-base-12.17.0.apk`
或者针对性反编译 `orchestra/evidence/dex_sample/classes3.dex`、`classes15.dex`
（P0 已确认这两个 dex 分别含新/旧模型层的目标类）来核实精确签名。

日志统一加前缀 `[XSF-PROBE]`，方便后续 `grep` 过滤。

## 产出与验收（本次任务范围内）

1. `./gradlew assembleDebug` 本地构建成功，产出 `app/build/outputs/apk/debug/*.apk`。
2. 四个候选 hook 点全部在代码里实现（找不到目标类/方法要有清晰的 `XposedBridge.log` 报错
   路径，不要求这一步就能在真机验证到日志——真机安装/操作是 orchestrator 下一步做的事）。
3. 写一份简短 `orchestra/probe-module-notes.md`，记录：这四个候选点你实际反编译核实到的
   类名/方法签名是否与 `recon-x-app.md` 一致（有出入要明确指出，不要含糊带过）、
   以及构建过程中遇到的任何问题。
4. README.md 补一句说明："当前处于 P1 探测阶段，模块只打日志不做拦截，不是最终发布版本"。

## 编码纪律（不可违反）
- 找不到目标类/方法/字段：`XposedBridge.log` 明确报错，不允许静默 catch 吞掉、不允许用
  默认值掩盖。
- 不确定的类名/方法签名：自己反编译核实，不要凭 recon-x-app.md 的记录直接硬编码
  （那是 P0 阶段较早的反编译记录，字段短名跨方法/跨版本可能不完全一致，必须自己核实一遍）。
- 只读参考 `redreader-ai-translate-xposed`/`doubao-letter-longpress-voice`，不要修改它们。
- 这次任务不碰 adb、不碰真机，只做本地 Gradle 工程 + 静态反编译核实。
