# 任务：给探测模块加一次性完整调用栈打印，定位 entries 列表组装点

背景：`orchestra/probe-findings.md` 已经确认四个场景（时间线/搜索/评论/通知）都统一命中
`com.x.models.timelines.items.UrtTimelinePost.getEntryId()`（累计 2631 次），这是 App 用来做
RecyclerView/LazyColumn diff/key 计算的必经调用。现在需要顺着这个线索，找到"谁持有
`List<UrtTimelinePost>`（或者更上层的 `List<UrtTimelineItem>`）并把它往下传"的那个方法/类，
这就是 P2 真正要 hook 的注入点（在这里我们要主动遍历 List、按关键词过滤、移除命中项）。

## 本次任务范围（小而精确，不要顺带做别的）

修改 `app/src/main/java/dev/xspamfilter/lsposed/HookEntry.java`：
在现有 `hookUrtTimelinePostGetters` 方法里，`getEntryId()` 的回调分支加一段**只在进程生命周期内
触发一次**的诊断逻辑（用一个 `static volatile boolean` 或 `AtomicBoolean` 做 once-guard，
不要每次调用都打印，会刷屏）：

```java
if (stackTraceDumped.compareAndSet(false, true)) {
    StringWriter sw = new StringWriter();
    new Throwable("XSF-PROBE stacktrace capture").printStackTrace(new PrintWriter(sw));
    for (String line : sw.toString().split("\\r?\\n")) {
        XposedBridge.log(TAG + " [STACKTRACE] " + line);
    }
}
```
（示意代码，具体命名/写法你可以自行调整，只要满足"进程生命周期内只打印一次完整调用栈"
这个要求，且不能影响其它探测点的正常行为，不能修改任何返回值。）

其它三个候选点（`PostResult.getText()`、`JsonTimelineEntry.r()`、`JsonAddEntriesInstruction.r()`）
不用动，保持原样即可（`probe-findings.md` 已确认后两者在四个场景里完全不触发，留着当兜底观察，
不需要为它们也加堆栈打印）。

## 验收
1. `./gradlew assembleDebug` 构建成功。
2. 不要运行自测/不要碰 adb/不要连真机——按既有硬约束，构建验证和真机验证都是 orchestrator
   下一步的事。
3. 简单更新 `orchestra/probe-module-notes.md`，补一句说明这次改动的内容（不需要重写整份文档）。

## 编码纪律
- 只做上述这一件事，不要顺便"顺手"改动其它探测点的逻辑或格式。
- 不确定 Java 语法/API（比如 `AtomicBoolean` 的 import）要写对，不要留编译不过的代码。
