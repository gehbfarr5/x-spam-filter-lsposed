# P1 探测模块静态核验记录

日期：2026-08-21

- 本次改动：在 `UrtTimelinePost.getEntryId()` 探测回调中增加进程生命周期内一次性的完整调用栈打印，用于定位 entries 列表组装点；其它探测点保持不变。

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

## P2 serializer.deserialize 探测点（2026-08-21）

- 本次新增只读 after-hook：
  `com.x.models.timelines.items.UrtTimelinePost$$serializer#deserialize(Decoder)`。回调从
  `param.getResult()` 读取新反序列化出的 `UrtTimelinePost`，调用其原始 `getText()` 和
  `getEntryId()`，记录 `[XSF-PROBE] serializer.deserialize() -> <正文前 50 个 Unicode
  code point> entryId=<...>`；不改参数、返回值、异常、对象字段或集合。
- jadx 1.5.5 能产出目标类，但把具体实现显示成 `m451deserialize(Decoder)`，并标注它与 bridge
  method 合并；这个 `m451deserialize` 是 jadx 为消除 Java 源码中“仅返回类型不同”的冲突而生成
  的展示名，不是 DEX 里的运行时方法名。
- Android SDK 36 `dexdump -e` 确认 DEX 中实际同时存在两个方法：
  `public final deserialize(Decoder): UrtTimelinePost`，以及
  `public bridge synthetic deserialize(Decoder): Object`。模块枚举声明方法并只 hook 前一个具体
  返回类型，避免 bridge 转发到具体实现时产生双份探测日志；若目标类或这一精确方法不存在，
  `installProbe` 会写出带 `[XSF-PROBE] FAILED to install hook` 的明确错误、候选签名和完整异常。
- 读取正文和 entryId 时使用 Xposed API 82 已提供的 `invokeOriginalMethod`，避免主动读取再次进入
  现有 `UrtTimelinePost.getText()` / `getEntryId()` 探测回调，从而污染两类日志的计数对比。
- 复用 / 适配 / 避免：复用现有 `installProbe`、`preview`、`logAfterResult` 与异常日志路径；适配
  kotlinx.serialization 生成的协变返回值 + bridge 双签名；避免 `hookAllMethods`、避免 hook bridge、
  避免 Compose State/List 改写，也没有加入关键词资产或任何过滤/占位对象逻辑。
- 遵守本轮硬约束，只做文件修改与静态 diff 审阅；未执行 Gradle 构建、自测、ADB 或真机操作，
  `assembleDebug` 与四场景日志验证均留给 orchestrator。

## P2 UrtTimelinePost 构造函数探测点（2026-08-21）

- 本次新增两个相互独立安装的只读 after-hook，均使用
  `XposedHelpers.findAndHookConstructor`：公开主构造函数记录
  `[XSF-PROBE] UrtTimelinePost.<init>() -> <正文前 50 个 Unicode code point>
  entryId=<...>`，Kotlin 默认参数桥接构造函数记录不同前缀
  `UrtTimelinePost.<init:bridge>() -> ...`。回调从构造完成后的 `param.thisObject` 调用原始
  `getText()` 与 `getEntryId()`；不修改参数、对象字段、异常或返回状态，也不对两个构造函数
  可能产生的重复命中做去重。
- 从
  `orchestra/evidence/jadx_classes3_out/sources/com/x/models/timelines/items/UrtTimelinePost.java`
  第 544 行逐项核实的公开主构造函数源码语义签名是：
  `(com.x.models.PostResult, long, java.lang.String, com.x.models.SocialContext,
  com.x.models.TimelinePromotedMetadata, com.x.models.PrerollMetadata,
  com.x.models.ClientEventInfo, com.x.models.PostDisplayType,
  com.x.models.HostingModuleMetadata, java.util.List, java.lang.String,
  com.x.models.timelines.items.TimelinePostFacepile)`。其中 `PostDisplayType.java` 明确标注该展示名由
  Kotlin metadata 从真实 DEX 类型 `com.x.models.h1` 重命名；`dexdump -e` 复核目标构造函数描述符后，
  `findAndHookConstructor` 使用的精确运行时参数签名为
  `(com.x.models.PostResult, long, java.lang.String, com.x.models.SocialContext,
  com.x.models.TimelinePromotedMetadata, com.x.models.PrerollMetadata,
  com.x.models.ClientEventInfo, com.x.models.h1, com.x.models.HostingModuleMetadata,
  java.util.List, java.lang.String, com.x.models.timelines.items.TimelinePostFacepile)`。
- 同一源码第 562 行的默认参数桥接构造函数精确参数签名是上述 12 个参数之后再追加：
  `(int, kotlin.jvm.internal.DefaultConstructorMarker)`；完整顺序因此为
  `(com.x.models.PostResult, long, java.lang.String, com.x.models.SocialContext,
  com.x.models.TimelinePromotedMetadata, com.x.models.PrerollMetadata,
  com.x.models.ClientEventInfo, com.x.models.h1,
  com.x.models.HostingModuleMetadata, java.util.List, java.lang.String,
  com.x.models.timelines.items.TimelinePostFacepile, int,
  kotlin.jvm.internal.DefaultConstructorMarker)`；这里同样使用 `PostDisplayType` 的真实运行时类型名。
  序列化专用的 `int` 开头构造函数不在本轮 hook 范围内。
- 若任一精确构造函数无法安装，模块会明确打印该构造函数的期望参数类型列表，并枚举
  `UrtTimelinePost.getDeclaredConstructors()` 返回的全部实际候选签名；两个安装流程彼此独立，
  一个失败不会阻止另一个尝试安装。
- 上一轮真机在全新 X App 进程、正常时间线渲染和大幅滑动触发分页加载的条件下，已确认具体
  非 bridge 的 `UrtTimelinePost$$serializer#deserialize(Decoder)` hook 安装成功但全程 **0 次命中**，
  而同批 `getEntryId()` 近 3000 次命中。这是“实时 GraphQL 响应未走 kotlinx.serialization
  反序列化路径”的重要负面证据。原 serializer 探测代码继续保留：它不会参与过滤，且能观察
  将来本地缓存、持久化或其它场景是否重新进入该路径，避免丢失已建立的对照探测点。
- 复用 / 适配 / 避免：复用现有 `installProbe`、Unicode code point 预览、原始 getter 调用和
  统一异常日志；适配两个构造函数的精确运行时参数签名及独立失败诊断；避免序列化专用构造函数、
  命中去重、过滤/移除逻辑和关键词资产。
- 本轮尝试执行 `./gradlew assembleDebug`，但当前 Codex 沙箱在任何 Gradle task 开始前先后禁止写入
  用户级 wrapper 锁文件、禁止 Gradle 单次 daemon 与文件锁监听器绑定本地 socket；因此这里不能
  诚实声称 `assembleDebug` 通过。作为受限环境内的最小编译核验，`HookEntry.java` 已使用
  `app/libs/api-82.jar` 完成独立 `javac` 编译。未运行自测，未使用 ADB，也未连接真机；orchestrator
  仍需在沙箱外执行原验收命令。

## P2 主时间线真实关键词过滤（2026-08-21）

- 本次保留了 `PostResult.getText()`、`UrtTimelinePost.getText()` / `getEntryId()`、两个
  `UrtTimelinePost` 构造函数、`UrtTimelinePost$$serializer.deserialize()` 以及旧模型层两个点的
  全部已有探测代码；在其后新增独立的 `[XSF-FILTER]` 必需 hook，没有删除或替换探测路径。
- 关键词只从模块资产 `app/src/main/assets/keywords_zpvip.txt` 读取。入口现在同时实现
  `IXposedHookZygoteInit`，保存 Xposed 提供的模块 APK 路径；目标包安装过滤 hook 时通过
  `XModuleResources.createInstance(modulePath, null).getAssets()` 打开该资产，并在进程内只加载一次。
  逐行 `trim`、跳过空行；普通关键词与待匹配正文都使用 `Locale.ROOT` 小写化，再删除 Unicode
  whitespace/space 以及全部 `Character.FORMAT` 字符（覆盖软连字符、零宽字符、BOM 和方向控制字符），
  最终执行纯 `String.contains` 包含匹配。归一化后为空的非空资产行会直接报错，避免空关键词命中
  所有正文；归一化后的重复词按原顺序去重。
- 新 hook 精确锁定 `com.x.urt.ui.k0` 的 12 参数 synthetic 构造函数，并在 before 阶段校验
  `param.args[2]` 同时是 `kotlinx.collections.immutable.c` 和 `java.util.List`。遍历顶层列表时，
  非 `UrtTimelinePost` 元素原样保留；`UrtTimelinePost` 使用已核实的 public 无参 `getText()`，通过
  `XposedBridge.invokeOriginalMethod` 主动读取正文，命中任一归一化关键词的元素不写入新的
  `ArrayList`。这避免主动读取重新进入保留的 getter 探测 hook，也让最终移除不依赖宿主 UI 是否
  自己调用 `getText()`。
- `classes6.dex` 的 `dexdump -e` 复核结果为：`kotlinx.collections.immutable.c` 是
  `PUBLIC INTERFACE ABSTRACT`，直接实现 `java.util.List`、`kotlinx.collections.immutable.b` 与
  `KMappedMarker`；它唯一额外声明的方法是协变返回自身接口的 `subList(int,int): c`。过滤结果因此
  使用 `Proxy.newProxyInstance` 重新实现该接口：`size/get/iterator/isEmpty/contains/indexOf/
  lastIndexOf/toArray/listIterator/forEach/spliterator/stream` 等标准只读方法委托过滤后的列表，
  `equals/hashCode/toString` 同样按过滤后内容计算。协变 `subList` 不能直接返回 JDK 的普通
  `ArrayList.SubList`，所以会为过滤后的子列表再包一层相同 Proxy，保持返回类型契约。
- 任何未覆盖方法都会以 `[XSF-FILTER] encountered unexpected immutable-list method` 明确记录，
  再反射转发到对应的原始未过滤对象；反射目标抛出的原始 cause 会从
  `InvocationTargetException` 解包后继续抛出，不用 `null` 或默认值掩盖。类、参数类型、精确构造函数、
  `getText()`、资产、列表形态或 Proxy 创建/过滤任一步失败都会输出完整错误；安装失败继续向 Xposed
  抛出，构造 before 回调失败则对该构造调用设置异常，明确拒绝悄悄用原始列表继续渲染。
- 已知限制严格保持在已有证据边界：这里只过滤 `k0` 收到的顶层普通 `UrtTimelinePost`；
  `UrtTimelineModule`（Carousel）本身及其内部 `getItems()` 子条目不处理。当前 `k0` 只由主时间线
  真机调用栈与 smali 证实；搜索结果页、通知页和评论区是否复用它尚无实机证据，本次没有猜测或
  增加其它页面类。
- 本轮按验收要求只尝试 `assembleDebug`，未运行任何自测、ADB 或真机操作。直接执行 wrapper 时，
  沙箱先拒绝写入 `~/.gradle` 的 wrapper 锁；改用已缓存 Gradle 8.9、可写临时 user home 和只读依赖
  缓存后，Gradle 仍在任何项目配置/task 之前因文件锁通信器绑定本地 socket 被拒绝，故本环境不能
  诚实声称 `assembleDebug` 通过。作为不执行测试的编译核验，当前完整 `HookEntry.java` 已用
  Android SDK 36 `android.jar` 与 `app/libs/api-82.jar` 通过 Java 17 `javac`；orchestrator 仍需在
  沙箱外运行原始 `./gradlew assembleDebug` 验收。

## P3 X App 12.19.1 R8 类名重新分配（2026-08-27）

- X App 从 12.17.0 升级到 12.19.1 后，R8 混淆产物重新分配了类名：主时间线 Compose
  lambda 从 `com.x.urt.ui.k0` 变为 `com.x.urt.ui.n0`，最后一个构造参数类型从
  `com.x.performance.i` 变为 `com.x.performance.g`；`kotlinx.collections.immutable.c`
  接口及其余参数顺序保持不变。
- 本次只替换了 `HookEntry.java` 中对应的字符串常量及相关日志文案，过滤器、Proxy、
  构造函数解析和整体架构均未改变。

### 真机验收（orchestrator 直接执行，2026-08-27）

- `./gradlew assembleDebug` 沙箱外构建成功。
- 真机（PLK110 / 3B166Q00SX000000，WiFi ADB）重装后模块 `enabled`，scope 正确限定
  `com.twitter.android`；强杀重启后日志确认
  `hook installed exact constructor: com.x.urt.ui.n0(...)`，不再出现
  `ClassNotFoundException: com$x$performance$i`。
- 四个场景逐一在真机上操作并用日志核实同一个 hook 点均被触发：
  - 主时间线（切换"正在关注" tab 触发 Compose 重建）：`original=28 kept=27 removed=1`——
    生产 184 词表在真实时间线内容上命中了一条，端到端证明过滤链路真实生效
    （不是靠人工注入测试词，是生产词表的真实命中）。
  - 搜索结果页（搜索 "news"）：`original=14`/`original=21` 均被过滤器处理。
  - 通知页：`original=6`/`original=5` 被过滤器处理。
  - 评论区/回复列表（点开一条推文详情页）：过滤器同样触发。
- 稳定性：全程 X App 进程未崩溃（pid 存活），本轮会话日志里除了历史遗留、非致命的
  探测型 hook（`PostResult#getText()`、`UrtTimelinePost` 构造函数探测——这些是早期
  P2 阶段已经证明的死路径，保留作负面证据，不影响生产过滤器）以外，没有新增
  FAILED/异常。
- 结论：修复完整生效，四场景恢复正常，架构未变，只是 R8 混淆产物类名跟随版本号
  重新定位。
