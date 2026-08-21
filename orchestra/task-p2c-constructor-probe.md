# 任务：改探测 UrtTimelinePost 的公开构造函数（上一个假设已被真机证伪）

完整背景见 `orchestra/probe-findings.md`、`orchestra/probe-module-notes.md`
（"P2 serializer.deserialize 探测点"一节）。本文件记录最新一轮真机验证结果和下一步方向。

## 上一轮假设已被证伪（重要，别重复犯）

上一版实现了 `UrtTimelinePost$$serializer#deserialize(Decoder)` 的 after-hook，
真机验证（orchestrator 直接跑）：
- hook 确认成功安装在具体实现方法上（日志有 `hook installed exact method`，方法签名正确，
  不是 bridge 方法）。
- App 强杀重启（拿到全新进程）、时间线正常渲染（`getEntryId()` 同批测试里稳定命中
  近 3000 次）、还手动大幅滑动触发分页加载更多内容——**`serializer.deserialize()` 全程
  0 次命中**。

**结论**：`UrtTimelinePost$$serializer.deserialize()` 这条路径在真实网络请求/分页加载时
根本没有被调用。合理推测：X App 的 GraphQL 响应解析用的是专门代码生成的映射逻辑
（参考 P0 阶段见过的 `gen.twitter.strato.graphql.timelines.*` 命名空间），直接调用
`UrtTimelinePost` 的**公开构造函数**来构造对象，完全绕开了 kotlinx.serialization 的
`KSerializer`/`$$serializer` 机制（那套机制大概率只用于别的用途，比如本地缓存/持久化的
序列化，不用于处理实时网络响应）。

## 本次任务：探测 UrtTimelinePost 的构造函数

`UrtTimelinePost` 反编译源码（`orchestra/evidence/jadx_classes3_out/sources/com/x/models/timelines/items/UrtTimelinePost.java`，
可以直接读，不需要重新反编译）里能看到三个构造函数：
1. **公开主构造函数**（约第 544 行）：
   `public UrtTimelinePost(PostResult postResult, long j, String str, SocialContext socialContext, TimelinePromotedMetadata timelinePromotedMetadata, PrerollMetadata prerollMetadata, ClientEventInfo clientEventInfo, PostDisplayType postDisplayType, HostingModuleMetadata hostingModuleMetadata, List list, String str2, TimelinePostFacepile timelinePostFacepile)`
   —— 这是最可能被"直接构造对象"的调用方使用的构造函数，本次任务**优先挂这个**。
2. **默认参数桥接构造函数**（约第 562 行，`synthetic`，内部 `this(...)` 委托给 #1）：
   参数末尾多了 `int i, DefaultConstructorMarker defaultConstructorMarker`。
3. **序列化专用构造函数**（约第 108 行，`synthetic`，参数以 `int i` 开头）：只被
   `$$serializer.deserialize()` 用，上一轮已经证明这条路径不会被触发，**这次不用管它**。

### 实现要求
- 用 `XposedHelpers.findAndHookConstructor` 挂构造函数 #1（按上面列出的精确参数类型顺序，
  从 jadx 反编译源码核实，不要凭这份任务书里的文字描述直接抄——万一转录有误会导致
  `findAndHookConstructor` 直接找不到方法抛异常，务必自己去读一遍源文件核实参数类型列表）。
  用 **after hook**：从 `param.thisObject`（构造完成后的 `this`）反射调用 `getText()`
  和 `getEntryId()`，打印 `[XSF-PROBE] UrtTimelinePost.<init>() -> <前50字符> entryId=...`。
- 同时也挂构造函数 #2（默认参数桥接版本，参数列表是 #1 加上 `int, DefaultConstructorMarker`），
  同样 after hook 记录日志但加不同前缀（比如 `UrtTimelinePost.<init:bridge>() -> `），
  这样后续看日志能区分是哪个构造函数被调用的、调用比例如何。
- 如果 #1 和 #2 都命中（因为 #2 会 `this(...)` 委托到 #1，理论上一次外部调用可能同时触发
  两个日志），**不用去重、不用特殊处理**，先如实记录，交给 orchestrator 在真机日志里自己
  判断实际调用比例和触发关系——不要自己猜测该不该去重就先加逻辑，避免没有证据支撑的
  过度设计。
- 找不到目标构造函数：`XposedBridge.log` 明确报错并打印期望的参数类型列表和
  `getDeclaredConstructors()` 枚举出的实际候选签名，方便排查是不是参数类型顺序/类型抄错了。
- **保留**上一轮已经实现的 `serializer.deserialize()` 探测点代码（虽然验证结果是 0 命中，
  但这是有价值的负面证据，留着以防将来某个场景真的会走这条路径；不要删除已有代码）。
- 其它已有探测点（`getEntryId`/`getText`/`JsonTimelineEntry`/`JsonAddEntriesInstruction`）
  都不要动。

### 追加记录
在 `orchestra/probe-module-notes.md` 追加一段，写清楚：这次新增了构造函数探测、
两个构造函数的精确参数签名（从源码核实后的准确版本）、以及上一轮 serializer.deserialize()
被真机验证为 0 命中这件事本身要不要保留代码/为什么保留。

## 验收
1. `./gradlew assembleDebug` 构建成功。
2. 不要运行自测/不要碰 adb/不要连真机，这些是 orchestrator 下一步的事。
3. 仍然不要实现真正的过滤/移除逻辑，也不要加关键词资产——这个决策要等这次真机验证完，
   确认构造函数确实是可靠触发点之后才能定。

## 编码纪律
- 构造函数参数类型列表必须从 `UrtTimelinePost.java` 源码逐个核对着写，不许凭记忆/凭
  这份任务书的转录直接抄（转录可能有疏漏）。
- 不确定就报错，不允许猜一个参数列表蒙混过关（`findAndHookConstructor` 参数不对会直接
  找不到方法，必须让错误信息清晰可诊断）。
