# 任务：X App 更新到 12.19.1 后，重新定位 R8 混淆产物类名（纯字符串替换，架构不变）

## 背景（已由 orchestrator 在真机上完整确认，不需要你重新反编译/不需要碰 adb）

X App 从上次验证的 12.17.0 (versionCode 312170000) 更新到了 **12.19.1-release.0
(versionCode 312191000)**。模块（`dev.xspamfilter.lsposed`）在新版本上加载失败，
真机 LSPosed 日志（`/data/adb/lspd/log/modules_*.log`）里的确切报错：

```
[XSF-FILTER] FAILED to install exact com.x.urt.ui.k0 constructor hook
[XSF-FILTER] expected parameter types=(androidx.compose.runtime.internal.m, com.x.urt.paging.f,
  kotlinx.collections.immutable.c, com.x.urt.paging.f, androidx.compose.runtime.internal.m,
  androidx.compose.foundation.lazy.w0, kotlin.jvm.functions.Function2, kotlin.jvm.functions.Function1,
  kotlin.jvm.functions.Function3, kotlin.jvm.functions.Function1, kotlin.jvm.functions.Function2,
  com.x.performance.i)
[XSF-FILTER] actual declared constructor candidate: public com.x.urt.ui.k0(kotlin.jvm.functions.Function1,
  com.x.models.timelines.items.UrtTimelineItem)
[XSF-FILTER] i.osilLWovLXRSv.pyfx.w.XposedHelpers$ClassNotFoundError:
  java.lang.ClassNotFoundException: com$x$performance$i
```

原因：`com.x.urt.ui.k0` 这个 R8 混淆产生的短类名，在新构建里被重新分配给了一个
完全不同的、只有 2 个参数的类；同时 `com.x.performance.i` 这个类型本身也消失了。
这是纯粹的 R8 混淆产物在版本间重新洗牌，**不是架构失效**。

## orchestrator 已经用 baksmali 在新版 APK（真机现装的 12.19.1，
`orchestra/evidence/x-new-base.apk` / 已解包在 `orchestra/evidence/dex_new/classes6.dex`，
反汇编产物在 `orchestra/evidence/smali6_new_out/com/x/urt/ui/n0.smali`，可以直接读，
不需要你重新反编译）重新定位到了新类

新类是 **`com.x.urt.ui.n0`**（不再是 `k0`）。已核实证据：

1. `n0.smali` 的构造函数（`orchestra/evidence/smali6_new_out/com/x/urt/ui/n0.smali`
   第 1-20 行附近）：

   ```
   .class public final synthetic Lcom/x/urt/ui/n0;
   .super Ljava/lang/Object;
   .implements Lkotlin/jvm/functions/Function1;

   .field public final synthetic a:Landroidx/compose/runtime/internal/m;
   .field public final synthetic b:Lcom/x/urt/paging/f;
   .field public final synthetic c:Lkotlinx/collections/immutable/c;   ★ 还是这个接口，没变
   .field public final synthetic d:Lcom/x/urt/paging/f;
   .field public final synthetic e:Landroidx/compose/runtime/internal/m;
   .field public final synthetic f:Landroidx/compose/foundation/lazy/w0;
   ...
   ```

   构造函数完整参数类型列表（12 个，顺序和上一版逐一对应）：

   ```
   androidx.compose.runtime.internal.m
   com.x.urt.paging.f
   kotlinx.collections.immutable.c        ← 第 3 个参数，还是 List<UrtTimelineItem>，接口名没变
   com.x.urt.paging.f
   androidx.compose.runtime.internal.m
   androidx.compose.foundation.lazy.w0
   kotlin.jvm.functions.Function2
   kotlin.jvm.functions.Function1
   kotlin.jvm.functions.Function3
   kotlin.jvm.functions.Function1
   kotlin.jvm.functions.Function2
   com.x.performance.g                    ← 唯一变化：从 com.x.performance.i 变成 com.x.performance.g
   ```

2. `n0.smali` 的 `invoke(Ljava/lang/Object;)Ljava/lang/Object;` 方法体（约第 70 行开始）
   核实过逻辑与上一版 `k0` 完全一致：对 field c 调 `Iterable.iterator()`，手写循环，
   每个元素 `check-cast` 成 `com.x.models.timelines.items.UrtTimelineItem`，
   `instance-of` 判断是不是 `com.x.models.timelines.items.UrtTimelineModule`
   （Carousel 类型走另一条分支，普通推文走 `LazyListScope` 渲染）——这就是同一个
   Compose 主 feed `LazyColumn` 内容构建 lambda，只是被 R8 重新分配了短名字。

3. `com.x.performance.g` 类型本身在新构建里确实存在（已用 `strings` 在多个新
   classes*.dex 里核实到 `Lcom/x/performance/g;` 描述符）。

**结论：架构完全不用改，`FilteredImmutableListHandler`/`Proxy` 那套逻辑不用动，
`kotlinx.collections.immutable.c` 这个接口名也没变。只需要改 `HookEntry.java`
里两处硬编码字符串。**

## 需要你做的修改（只改 `app/src/main/java/dev/xspamfilter/lsposed/HookEntry.java`）

1. 找到常量 `MAIN_FEED_LAMBDA_CONSTRUCTOR_PARAMETER_TYPES`（12 元素的 String 数组），
   把最后一个元素 `"com.x.performance.i"` 改成 `"com.x.performance.g"`。
   其余 11 个元素**原样不动**。
2. 找到 `hookMainFeedListFilter(...)` 里 `XposedHelpers.findClass("com.x.urt.ui.k0", classLoader)`
   （或等价的类名解析逻辑，可能是硬编码字符串常量也可能是内联字符串，自己在文件里
   搜 `com.x.urt.ui.k0` 定位所有出现的地方），把类名字符串改成 `"com.x.urt.ui.n0"`。
   注意搜全文件——之前遗留的探测代码（`getEntryId`/`getText`/构造函数探测等）
   **不涉及** `com.x.urt.ui.k0`，那些探测点挂的是 `UrtTimelinePost` 类，跟这次
   要改的 Compose lambda 类是两回事，不要碰、不要因为"顺手"去改动其它探测点。
3. 检查日志字符串/注释里如果原样写死了 `"com.x.urt.ui.k0"` 这个类名用于人类可读的
   日志前缀（比如 `"hook installed: com.x.urt.ui.k0 main-feed immutable List constructor
   argument"`），同步把日志文案里的 `k0` 也改成 `n0`，避免日志说谎误导后续排查
   （这是文档/日志一致性要求，不是功能性要求，但请顺手做）。
4. **不要**修改 `resolveConstructorParameterTypes`、`hookMainFeedListFilter`、
   `createMainFeedListFilterCallback`、`createImmutableListProxy`、
   `FilteredImmutableListHandler` 的任何逻辑——这次是纯字符串常量替换，逻辑代码
   一行都不需要改。如果你觉得逻辑也需要改，说明你理解错了任务范围，先停下来
   在验收报告里说明理由，不要自己扩大改动面。
5. **不要**触碰 `keywords_zpvip.txt`、`THIRD_PARTY_NOTICES.md`、`README.md`、
   `build.gradle`——这次任务范围只是 hook 点定位字符串。

## 验收
1. `./gradlew assembleDebug` 构建成功。
2. 在 `orchestra/probe-module-notes.md` 追加一段：X App 12.17.0→12.19.1 版本升级
   导致的 R8 类名重新分配（`k0`→`n0`，`com.x.performance.i`→`com.x.performance.g`），
   以及本次只改了两处字符串常量、架构不变的结论。
3. 仍然不要运行自测/不要碰 adb/不要连真机——orchestrator 会在真机上验证四个场景
   （主时间线/搜索/通知/评论区）是否恢复过滤功能。

## 编码纪律
- 这是一次纯字符串常量替换任务，范围极窄——第一性原理：不确定某处是否也需要改，
  先用 grep 在文件里搜 `k0`/`com.x.performance.i` 的所有出现位置，逐一确认后再改，
  不要漏改也不要多改。
- 不允许加任何"双重兼容"逻辑（比如同时尝试 `k0` 和 `n0` 两个类名、catch 异常后
  静默降级）——这次已经有真机 smali 级证据确认新类名是 `n0`，不是"猜测"，直接改
  成确定值即可，加兼容分支反而是没有根据的过度设计，违反纪律。
