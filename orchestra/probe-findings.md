# P1 探测结论（真机实测，2026-08-21）

方法：orchestrator 直接用 adb（网络连接 `127.0.0.1:15555`，已核对 `ro.serialno=3B166Q00SX000000`/
`ro.product.model=PLK110`）安装 Codex 构建的探测 APK，用 LSPosed CLI（Vector CLI 1.0）启用模块、
设置 scope 为 `com.twitter.android`，强杀重启 X App 后依次手动操作四个场景，每个场景操作前后
分别抓取 `/data/adb/lspd/log/modules_*.log` 做 diff，避免不同场景的日志互相污染。

## 决定性结论：四个场景全部走新模型层，旧模型层零命中

| 场景 | 操作方式 | `UrtTimelinePost.getEntryId()` | `UrtTimelinePost.getText()` | 旧 LoganSquare 层 |
|---|---|---|---|---|
| 主时间线（为你推荐 Tab） | App 冷启动直接落地 | 命中（数十次，`tweet-<id>` 格式） | 未命中 | 0 |
| 评论/回复（推文详情页） | 点开一条推文进入详情页并下滑 | 命中（`conversationthread-<id>-tweet-<id>` 格式） | **命中 18 次**（均为详情页那条"主推文"，同一 entryId） | 0 |
| 搜索结果（热门 Tab） | Explore 页搜索关键词 `news` | 命中（200 次） | 未命中 | 0 |
| 通知（全部 Tab） | 点通知底部 Tab | 命中（192 次） | 未命中 | 0 |
| **全量累计** | 整个探测过程 | **2631 次** | **18 次** | **0 次** |

**这是本次 P1 最重要的发现**：`com.twitter.model.json.timeline.urt.JsonTimelineEntry#r()` 和
`JsonAddEntriesInstruction#r()`（旧的 LoganSquare JSON 解析层）在四个场景的任何操作中
**一次都没有被触发**。12.17.0 版本的这四个场景**全部**统一走新模型层
`com.x.models.timelines.items.UrtTimelinePost`（kotlinx.serialization）。

**对 P2 的影响**：可以完全放弃对旧模型层的兼容工作，architecture-proposal.md 里"新旧链路可能都
要挂"的顾虑已被真机证据排除，P2 的 hook 目标范围可以大幅收窄，只聚焦新模型层。

## 需要在 P2 解决的问题：`getText()` 不会在列表渲染时被动触发

`UrtTimelinePost.getEntryId()` 在所有四个场景、每一条 entry（不管是否滚动到可见区域）都可靠
触发——这是 App 用于 RecyclerView/Compose LazyColumn 的 diff/key 计算（`items(list, key =
{ it.entryId })` 这种模式），**几乎可以确定每条数据都会走一次 `getEntryId()`，不依赖它是否被
渲染成文字**。

但 `getText()` 只在"推文详情页里那条被完整展开渲染的主推文"上被观察到调用，**主时间线/搜索/
通知/评论列表里的普通列表项，即使已经滚动到屏幕可见区域，也没有观察到 `getText()` 被调用**。
可能的原因（未证实，供 P2 参考）：
- 列表项的正文渲染走的是另一条代码路径（比如通过 Compose 的某个中间 ViewState/UI Model 转换，
  而不是直接调用 `UrtTimelinePost.getText()`）；
- 或者是虚拟化/懒加载导致我操作节奏没有精确捕捉到那个瞬间。

**给 P2 的关键结论：不要指望"拦截 App 调用 getText() 的返回值"作为过滤时机，因为它在列表场景
下不可靠触发。** 更稳妥的架构是：**不做"被动拦截"，改成"主动拉取"**——在拿到
`List<UrtTimelinePost>`（entries 列表）刚组装完成、还没交给 UI 的那个时间点，由我们自己的代码
主动对列表里每一项调用 `getText()`（不管 App 自己会不会调用），检查关键词命中，命中就把该项
从 `List` 里移除。`getEntryId()` 的高可靠触发率恰好说明"entries 已经是完整 List 形态"这个
时机是真实存在且必经的，P2 第一件事就是要顺着这个线索反向定位"是谁构建了这个 List 并往下传"——
可以给 `UrtTimelinePost` 的构造函数也挂一个只打印调用栈（`Thread.currentThread()
.getStackTrace()` 或 `new Throwable().printStackTrace()`）的探测 hook，构造函数被调用的堆栈
往上看几层，大概率就能看到组装 List 的那个方法。

## 探测过程中的其它记录
- `com.x.models.PostResult#getText()` **无法直接 hook**：真机反编译确认它是
  `public abstract interface` 的抽象方法（不是可执行的具体方法），LSPosed 尝试 hook 会抛
  `IllegalArgumentException: Cannot hook abstract methods`。探测模块已经按预案改为 hook
  `UrtTimelinePost` 自己的 `getText()`/`getEntryId()`，这个决定被证明是对的——不需要再回头
  找 `PostResult` 的具体实现类，因为 `UrtTimelinePost.getText()` 本身就是真实被调用（至少在
  详情页场景下）且能拿到正确文本的入口。
- LSPosed CLI（`su -c '/data/adb/lspd/cli ...'`）全程可脚本化：`modules enable`/
  `scope add`/`modules ls`/`scope ls` 都正常，装机+启用+设 scope 全程无需手动点手机，
  符合 memory 里记录的经验。
- 日志证据落在 `/data/adb/lspd/log/modules_*.log`（vector 桥），不是标准 logcat，
  和 TikTok/RedReader 项目的经验一致。

## 探测模块后续处理
探测完成后已通过 LSPosed CLI 把 `dev.xspamfilter.lsposed` 设回 `disabled`，避免探测日志在
用户日常使用 X App 时持续产生噪音；P2 开发验证阶段需要时再重新 enable。
