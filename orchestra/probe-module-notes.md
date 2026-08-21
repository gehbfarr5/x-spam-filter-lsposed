# P1 探测模块静态核验记录

日期：2026-08-21

## 核验输入与方法

- 当前 APK：`orchestra/evidence/x-base-12.17.0.apk`，SHA-256
  `5e9c8e4bbb525a2140292f8f96ab6cc0af403dfcd07667b8678aebb44fb52354`。
- 新模型目标来自 `classes3.dex`，旧模型目标来自 `classes15.dex`。
- 使用 jadx 1.5.5 对这两个 dex 重新执行 `--no-res` 反编译，并用 Android SDK 36 的
  `dexdump -e` 复核运行时类、字段和方法描述符；没有直接把
  `recon-x-app.md` 的短字段名当成最终签名。

## 四个候选点

1. `com.x.models.PostResult#getText(): java.lang.String`
   - 类与方法名和 `recon-x-app.md` 一致。
   - 当前 dex 中 `PostResult` 的精确类型是 `public abstract interface`，`getText()` 是
     `public abstract synthetic`，不是可执行的普通数据类 getter。这一点比侦察报告里
     “正文容器”的表述更精确，也意味着 Xposed 运行时可能拒绝直接 hook 抽象方法；模块会把
     安装失败及完整异常写入 `[XSF-PROBE]` 日志，不会静默降级。

2. `com.x.models.timelines.items.UrtTimelinePost`
   - 类名与 `recon-x-app.md` 一致；精确确认了两个可执行的 public 无参方法：
     `getText(): java.lang.String`、`getEntryId(): java.lang.String`。
   - `getText()` 确实直接委托给内部 `postResult.getText()`。
   - 当前类有三个 public 构造函数形式，参数很长；dex 中部分真实运行时类型仍是短名
     `com.x.models.h1`、`kotlinx.serialization.internal.k2`，而 jadx 会依据 Kotlin metadata
     把 `h1` 显示成 `PostDisplayType`。因此按任务允许的备选方案，模块不硬编码构造函数，改为
     枚举并分别 hook 声明在该类上的 public `getText()`/`getEntryId()`。

3. `com.twitter.model.json.timeline.urt.JsonTimelineEntry#r(): java.lang.Object`
   - 类名、无参 `r()`、以及 `a` 为 `entryId` 字段均与 `recon-x-app.md` 一致。
   - 精确 dex 签名的返回类型因泛型擦除是 `java.lang.Object`；jadx 会把语义返回类型显示为
     `com.twitter.model.timeline.urt.a2`。字段 `a` 的运行时类型是 `java.lang.String`。

4. `com.twitter.model.json.timeline.urt.JsonAddEntriesInstruction#r(): java.lang.Object`
   - 类名、无参 `r()`、以及 `a` 承载 entries 均与 `recon-x-app.md` 一致。
   - 精确 dex 返回类型同样是 `java.lang.Object`；字段 `a` 的运行时类型是原始
     `java.util.ArrayList`。探测回调只读取其 `size()`，不会修改列表或返回值。

## 工程与问题记录

- Gradle/AGP/Kotlin/Java 基线直接复用本机已验证的 RedReader 模板：Gradle 8.9、
  AGP 8.7.3、Kotlin 2.0.21、compile/target SDK 36、Java 17；`xposedminversion` 按本任务要求
  使用 82（RedReader 当前清单是 93，Doubao 模板复核为 82）。
- Xposed API 复制自 RedReader 模板的本地 `api-82.jar`，SHA-256
  `f48c635f1c7469fdec0e00ad2ea0b7a6b2f5b55065784a35b7ca3a84615e8e25`；没有配置
  `api.xposed.info` Maven 仓库。
- jadx 对完整 dex 的非目标类分别报告 17/34 个反编译错误，但四个目标类均成功产出；上述
  签名已再用 `dexdump` 直接复核，未依赖出错类的伪源码。
- 根据任务末尾“不要运行自测”的硬约束，本次没有执行 `./gradlew assembleDebug`，因此没有
  声称构建通过，也没有生成本轮 APK。后续 orchestrator 需要执行该验收命令。
