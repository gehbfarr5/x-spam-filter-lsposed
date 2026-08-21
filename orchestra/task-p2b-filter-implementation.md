# 任务：P2 真正的关键词过滤实现（需要迭代式真机验证，不是一次性写完）

完整背景见 `orchestra/probe-findings.md`、`orchestra/recon-x-app.md`、
`orchestra/architecture-proposal.md`。本文件是在那些基础上，orchestrator 又做了一轮真机
调用栈追踪后的最新结论和任务安排。

## 本轮新增证据（2026-08-21，真机调用栈追踪）

在 `UrtTimelinePost.getEntryId()` 加了一个"进程内只打印一次完整调用栈"的探测点（已实现，
见 `HookEntry.java` 的 `ENTRY_ID_STACK_TRACE_DUMPED`），真机抓到的关键帧（从下往上）：

```
... android.view.ViewRootImpl.performTraversals / draw ...
androidx.compose.ui.node.* (Compose 渲染管线)
androidx.compose.foundation.lazy.layout.* (LazyColumn/LazyList 内部)
androidx.compose.foundation.lazy.n.<init>
com.x.urt.ui.k0.invoke   <-- 这是 LazyColumn `items(list, key = { it.entryId })` 的 key lambda
Vector_.getEntryId(Dobby)  <-- 我们的 hook 落点
```

**结论 1（确认）**：主 feed 的渲染是标准 Jetpack Compose `LazyColumn`/`LazyList`，
`com.x.urt.ui.k0` 是给每个 item 算 key 用的 lambda（`key = { it.entryId }` 模式）。
这条链路证明了 `UrtTimelinePost.getEntryId()` 会在 Compose 计算 key 时对**每一个**
列表项触发（不管是否已经滚动到可见区域），这也是它命中率高达 2600+ 次的原因。

**结论 2（重要，改变原计划）**：这条调用栈往上追全是 Compose 内部机制
（`androidx.compose.runtime.snapshots.*`、`androidx.compose.runtime.n0.getValue` 等
Compose State/Snapshot 读取样板代码），**说明 List 在到达这里之前已经被包进了 Compose
State（`State<List<UrtTimelineItem>>` 或类似），不是一个能安全就地 `removeIf` 的裸
`java.util.List`**。原计划"找到 List 组装点、就地过滤"这条路径，继续往上追探测成本会越来越
高（每往上一层都要面对新的混淆 lambda 类），收益递减。

**已探明但暂不建议采用的候选**：`com.x.urt.ui.module` 包下有几个实现
`kotlin.jvm.functions.Function3` 的类（`c`/`e`/`f`/`i`/`j`/`m`），构造函数会捕获一个
`java.util.List` 类型的 final 字段。但结合包名（`.module`）和 `UrtTimelineModule`
（Carousel/多条目聚合模块）的既有证据，这大概率是 Carousel 专用的渲染逻辑，不是主 feed
的通用入口，不建议作为主要突破口，除非其它方向都验证失败再回头试它。

## P2 推荐方向：改从 kotlinx.serialization 反序列化层下手，而不是 Compose UI 层

**核心思路**：`UrtTimelinePost` 是 `kotlinx.serialization` 生成的 data class，
它的伴生对象方法 `Companion.serializer()` 返回 `UrtTimelinePost$$serializer.INSTANCE`
——**这个类名是 kotlinx.serialization 编译器约定生成的，不会被 R8 改名**（我们已经在
`UrtTimelinePost.java` 源码里实测确认了这个精确类名和调用方式）。这比追 Compose UI 层的
匿名 lambda 链路稳定得多。

`UrtTimelinePost$$serializer` 应该有一个 `deserialize(Decoder)` 方法，每解析一个 JSON
对象成一个 `UrtTimelinePost` 实例就会调用一次——这是"单个 entry 从 JSON 变成 Kotlin
对象"的精确时刻，早于 Compose 层，也早于任何 List 组装。

### 本任务要做的事（分两步，第一步纯探测，第二步才是真正过滤）

**第一步：反编译确认 `UrtTimelinePost$$serializer` 的真实方法签名。**
用 jadx 反编译 `orchestra/evidence/dex_sample/classes3.dex`（`UrtTimelinePost` 定义所在的
dex），找到 `UrtTimelinePost$$serializer.java`（如果 jadx 因为泛型/桥接方法生成失败，改用
`dexdump -l xml` 查看它的方法列表和签名，参考 orchestrator 之前用过的方法：
`awk`/`grep` 在 xml 里定位 `<class name="UrtTimelinePost$$serializer"` 附近的
`<method>` 元素）。确认它有一个返回 `UrtTimelinePost` 或 `java.lang.Object`
（因为实现的是 `KSerializer<T>` 接口，桥接方法可能擦除成 Object）的 `deserialize` 方法。

**第二步：改探测模块（不要动之前四个候选点的代码，新增一个）**，在
`UrtTimelinePost$$serializer` 的 `deserialize` 方法上挂一个 **after hook**：
- 从 `param.getResult()` 拿到刚解析出的 `UrtTimelinePost` 实例，反射调用它的
  `getText()`（这个我们已经验证过存在且可靠返回真实正文）。
- **本次先只打日志，不做任何过滤**（`[XSF-PROBE] serializer.deserialize() -> <前50字符> entryId=...`），
  目的是先用真机验证：①这个 hook 点确实会在四个场景（时间线/搜索/评论/通知）都稳定触发
  ②触发次数是否等于 `getEntryId()` 的触发次数量级（如果显著更少，说明可能被缓存/去重，
  这个点就不适合做过滤判断的唯一依据）。

**先不要在这一轮任务里实现"真正移除/替换返回值"的逻辑**——那需要先看这一步的真机日志结果
再决定安全的做法（可选方案包括：`param.setResult()` 替换成一个"文本为空、媒体为空"的安全占位
对象；或者证实这个方法调用点确实早于任何 List 组装，此时反而更适合回去找调用方追 List——
这个决策留到看到真机日志之后，不要在没有证据的情况下先写"移除逻辑"，写了大概率要返工。

## 验收（本次任务范围）
1. `./gradlew assembleDebug` 构建成功。
2. 新增的 `deserialize` 探测点日志格式清晰、加了 `[XSF-PROBE]` 前缀，方便 grep。
3. 不确定的方法签名要写清楚在 `orchestra/probe-module-notes.md` 里追加记录（不要重写整份
   文档，追加一段说明这次新加了什么、反编译时遇到什么情况）。
4. 不要运行自测/不要碰 adb/不要连真机——这些是 orchestrator 下一步的事。

## 编码纪律
- 找不到 `UrtTimelinePost$$serializer` 类或 `deserialize` 方法：`XposedBridge.log`
  明确报错，不允许猜一个方法名硬编码上去。
- 不要在这一轮任务里顺便实现真正的过滤/移除逻辑，范围就是"新增一个只打日志的探测点"。
- 关键词资产（184 条 ZPVIP 词表 + Apache-2.0 许可证文件）**这次也不要加**，等 hook 点
  确定下来再一起做，避免做了以后因为 hook 点不对又要重新调整挂载位置和数据结构。
