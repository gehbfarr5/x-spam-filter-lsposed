# X (Twitter) Android App 实机侦察报告

日期：2026-08-21
方法：orchestrator（Claude 主会话，非 Codex 沙箱，遵守 MOBILE_ROUTING.md 硬约束）直接用
真机 `adb pull` + `jadx`/`dexdump` 反编译，未经猜测/记忆，均为静态代码实测证据。

## 基本信息
- 设备：OnePlus 15 PLK110 / 序列号 `3B166Q00SX000000`（网络 ADB `127.0.0.1:15555`，
  已核对 `ro.serialno`/`ro.product.model` 一致；未插 USB，一次性 pull 操作可接受）。
- 包名：`com.twitter.android`
- 版本：`versionName=12.17.0-release.0`，`versionCode=312170000`
- `minSdk=28 targetSdk=35`，`lastUpdateTime=2026-08-19`
- base.apk 大小 196.8MB，含 **17 个 classes*.dex**（总 169MB），典型的多 dex 大型应用
- 证据文件位置：`orchestra/evidence/`（`x-base-12.17.0.apk` 原始包、`dex_sample/` 解压的
  17 个 dex、`jadx_classes3_out/` `jadx_classes15_out/` 部分反编译产物）。**jadx 产物体积大，
  不进 git**，只在本报告摘录关键片段。

## 混淆程度结论

**不是"全局无差别混淆"，而是"分层混淆"**：
- **包路径基本未混淆**：`com.twitter.*`、`com.x.*`（新命名空间）下有 1000+ 完整可读的包路径。
- **业务逻辑类的类名/方法名大量被 R8 压缩成单字母/短标识符**（如 `f`、`g`、`x`、`z`、`di`、`k`、`n0`、
  `q1` 等），符合预期的 R8 release 混淆。
- **两类"框架强制保留"的代码，名字/字段名基本完整可读**，这是本次侦察最重要的发现：
  1. **新数据模型层**：`com.x.models.timelines.items.Urt*` 系列，用 **kotlinx.serialization**
     （`@kotlinx.serialization.j` + 生成的 `$$serializer` 类）。因为序列化框架依赖
     `Companion.serializer()` 和构造函数结构，R8 必须保留这些 data class 的字段名/getter，
     否则序列化会直接失效。
  2. **旧数据模型层**：`com.twitter.model.json.timeline.urt.Json*` 系列，用
     **LoganSquare**（`@JsonObject` + `@JsonField(name = "...")` 注解处理器生成解析代码），
     同理，`@JsonField(name=...)` 显式声明了 JSON key 字符串，类名也因反射发现机制被保留。
  3. 一些 Dagger/Hilt 生成的 DI Graph 类、`ViewModel`/`Fragment`/`Component` 后缀的顶层类
     也大概率被 keep 规则保留（如 `FollowingTimelineComponent`、`ForYouTimelineComponent`、
     `TweetView`/`TweetViewViewModel`），但这类不如上面两套模型层可靠——不是本次决策的主锚点。

**结论**：UI 层（Adapter/ViewHolder/短名类）是脆弱的、会随每次发版轮换的（就像 TikTok 项目的经验）；
**数据模型/JSON 解析层是稳定的、跨版本抗性强的**，这是选 hook 点的核心依据。

## 关键类清单（均已实测存在于 12.17.0 dex 中，附出处 dex 文件）

### 新模型层（kotlinx.serialization，Kotlin data class）
| 类 | 所在 dex | 关键点 |
|---|---|---|
| `com.x.models.timelines.items.UrtTimelinePost` | classes3.dex（定义）| **实现 `UrtTimelineItem` 和内部混淆接口 `g1`**（Kotlin `@Metadata` 里保留了真实全名 `com.x.models.Post`，见下方说明）。`getText()` 直接 `return this.postResult.getText()` |
| `com.x.models.PostResult` | 引用于同上 | 真正的正文容器，`getText()` 未混淆 getter |
| `com.x.models.timelines.items.UrtTimelineItem` | classes3.dex | 顶层接口，`UrtTimelinePost`/`UrtTimelineModule`/`UrtTimelineUser`/`UrtTimelineCursor` 等都实现它 |
| `com.x.models.timelines.items.UrtTimelineModule` | classes3.dex | 持有 `List<UrtTimelineModuleItem>`（`items`/`innerContent` 字段），用于"多条目聚合模块"（如 Carousel） |
| `com.x.models.timelines.items.UrtNotification` | 全量 dex 抽样 | **通知页复用同一套 Urt* 模型**，未见独立的通知专属数据类 |
| `gen.twitter.strato.graphql.timelines.timeline_keys.TimelineKey_*SearchTimelineJsonAdapter`（Latest/Top/People/Media/Photos/Videos/List/Follow/CommunityLatest 共 9 个）| 全量 dex 抽样 | **搜索结果的 9 个子 Tab 全部复用 Timeline 抽象**，说明搜索页也走同一套模型 |

**重要发现**：`UrtTimelinePost` 实现的接口在 Kotlin `@Metadata` 注解里留有真实签名
`Lcom/x/models/Post;`，但字节码引用处显示的是混淆后的 `g1`。也就是说**运行时用 Kotlin 反射
（`kotlin-reflect` 或读 `@Metadata` 的 d1/d2 字段）能反推出真实接口名**，比单纯 Java 反射更抗
混淆——这是 P1 阶段做"运行时特征匹配"时的一个可用技巧，供 Executor 参考。

### 旧模型层（LoganSquare，Java，`com.twitter.model.json.timeline.urt.*`）
| 类 | 所在 dex | 关键点 |
|---|---|---|
| `JsonAddEntriesInstruction` | classes15.dex | `@JsonField(name="entries") public ArrayList a` — **一次响应里新增的所有 entries（含推文/用户/模块等）都在这里**。`r()` 方法把原始 JSON 数组转成业务对象 `com.twitter.model.timeline.urt.instructions.n` |
| `JsonReplaceEntriesInstruction` / `JsonTerminateTimelineInstruction` / `JsonClearCacheInstruction` / `JsonPinEntryInstruction` / `JsonMarkEntriesUnreadInstruction` / `JsonShowAlertInstruction` / `JsonNavigationInstruction` / `JsonShowCoverInstruction` | 同上 | URT 协议的完整指令集，`AddEntries`/`ReplaceEntries` 是承载推文内容的两个 |
| `JsonTimelineEntry` | classes15.dex | 单条 entry 的 JSON 包装：`entryId`（字段 a）、`sortIndex`（字段 b）、`content`（字段 c，`typeConverter=a1.class`，指向 `JsonTimelineItem`/`JsonTimelineOperation`/`JsonTimelineModule` 三选一）。`r()` 方法是**从 JSON 转成内部业务对象的统一入口** |
| `JsonTimelineItem` | 同 dex | 持有 `itemContent`（字段 `a`，类型是接口 `b`），`interface b { n2 a(JsonTimelineItem, String, long, long, boolean, m0) }` 是"具体渲染成什么类型 entry"的工厂接口 |
| `JsonTimelineModule` | 同 dex | 模块类型 entry（Carousel/GridCarousel 等），内部走 `com.twitter.util.collection.p.b(...)` 批量转换 |

**关键判断**：`JsonAddEntriesInstruction`/`JsonTimelineEntry` 这条链路是**旧 URT 协议解析入口**，
类名/`@JsonField(name=...)` 字段映射稳定，但内部产出的业务对象类型（`n2`、`t2`、`y1` 等）是短名，
会随版本轮换——**hook 点应该落在"JSON 反序列化完成、entries 已经是 List 形态"的这一层，而不是往下
追到具体的短名业务类**。

## 尚未确认、需要 P1 阶段运行时验证的问题
1. **新旧两套模型层各自覆盖哪些具体场景**：目前只能确认"搜索/通知都在用新模型层的 Urt* 系列
   命名空间"，但**没有静态证据能 100% 证明时间线/评论区具体调用的是新模型层还是旧模型层**——
   12.17.0 大概率处于新旧并存的迁移期，不同页面可能落在不同实现。这需要 P1 阶段在真机挂一个
   探测性 `XposedBridge.log` hook（在两套模型的 `r()`/构造函数上打日志），实际操作时间线/搜索/
   评论/通知四个页面，用日志确认真实调用路径，静态反编译无法穷尽调用图。
2. **"完全隐藏"最合适的插入点**：是在 JSON 解析层（`JsonTimelineEntry.r()` / `UrtTimelinePost`
   构造函数）**过滤掉整个 entry**，还是在更上游"把 entries 列表交给 UI 层"的方法处过滤——
   还没有定位到那个"最终返回 `List<UrtTimelineItem>`/`List<n2>` 给 UI"的具体类。这也建议放
   P1 用运行时调用栈打印来确认，而不是继续静态穷举（穷举成本已经偏高，边际收益下降）。
3. 评论区目前只找到 `ReplyContextTimelineFragment`/`ReplyContextTimelineRetainedGraph` 等外壳类，
   没有直接证据证明评论列表内的每条回复复用的是 `UrtTimelinePost` 还是有独立的 Reply 数据类，
   需要 P1 运行时验证。
