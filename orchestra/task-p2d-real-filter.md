# 任务：P2 真正的关键词过滤实现（架构已定，可以真正写移除逻辑了）

完整背景见 `orchestra/probe-findings.md`、`orchestra/probe-module-notes.md`。这是本项目
P2 阶段最后一份任务书——之前几轮探测已经找到了确定可行的注入点，本次要把真正的过滤逻辑
实现出来。

## 已确定的注入点（真机 smali 级证据，2026-08-21）

用 `baksmali` 反汇编 `orchestra/evidence/dex_sample/classes5.dex`，产物在
`orchestra/evidence/smali5_out/com/x/urt/ui/k0.smali`（可以直接读，不需要重新反编译）。

`com.x.urt.ui.k0` 实现 `kotlin.jvm.functions.Function1`，是 X App 主 feed 的 Compose
`LazyColumn` 内容构建 lambda（也就是 `LazyListScope.() -> Unit` 那个 lambda 本体，
`invoke(Object)` 的参数就是 `androidx.compose.foundation.lazy.n0`，即 `LazyListScope`）。
它的构造函数：

```
public synthetic constructor <init>(
  Landroidx/compose/runtime/internal/m;      // -> field a
  Lcom/x/urt/paging/f;                        // -> field b
  Lkotlinx/collections/immutable/c;           // -> field c  ★ 这就是 List<UrtTimelineItem>
  Lcom/x/urt/paging/f;                        // -> field d
  Landroidx/compose/runtime/internal/m;       // -> field e
  Landroidx/compose/foundation/lazy/w0;       // -> field f
  Lkotlin/jvm/functions/Function2;            // -> field g
  Lkotlin/jvm/functions/Function1;            // -> field h
  Lkotlin/jvm/functions/Function3;            // -> field i
  Lkotlin/jvm/functions/Function1;            // -> field j
  Lkotlin/jvm/functions/Function2;            // -> field k
  Lcom/x/performance/i;                       // -> field l
)
```

**`field c`（构造函数第 3 个形参，Xposed `param.args[2]`）就是主 feed 的
`List<UrtTimelineItem>`**。已经用 `dexdump` 确认 `kotlinx.collections.immutable.c` 是一个
**接口**（`interface="true"`），且 `implements java.util.List`（在
`orchestra/evidence` 里可以自己用同样的方法在 classes6.dex 里核实一遍，不要只信这份任务书）。

`k0.invoke()` 方法体里能看到确凿证据（`k0.smali` 第 130 行附近）：对 `field c` 调用
`Iterable.iterator()`，手写 for 循环遍历，每个元素 `check-cast` 成
`com.x.models.timelines.items.UrtTimelineItem`，再用 `instance-of` 判断是不是
`UrtTimelineModule`（Carousel 类型），不是的话就是普通条目，交给 Compose 的
`LazyListScope.g(...)`（即 `item(...)`）逐条渲染。

## 架构方案：Proxy 动态代理过滤

因为 `kotlinx.collections.immutable.c` 是接口，可以用
`java.lang.reflect.Proxy.newProxyInstance` 构造一个**过滤后的代理对象**，在
`k0` 构造函数的 **before hook** 里，把 `param.args[2]`（原始未过滤的 list）替换成这个代理，
这样构造函数执行完、把值存进 field c 之后，`k0.invoke()` 遍历到的就是过滤后的结果。

### 实现步骤

1. **加载关键词资产**：`app/src/main/assets/keywords_zpvip.txt`（184 行，已经放好，
   不要重新下载/不要改这个文件）。用 `AssetManager`（通过 hook 到的宿主 `Context` 或者
   `XposedHelpers` 能拿到的宿主 `Resources`/`AssetManager`）在模块首次加载时读一次，
   按行 split，trim，跳过空行，存成 `List<String>` 或编译好的匹配结构。
   **归一化规则**（复用 `orchestra/research-x-spam-filter.md` 里记录的 ZPVIP 思路，不用照抄
   它的 JS 实现，用 Java/Kotlin 等价实现即可）：
   - 待匹配文本和关键词都转小写。
   - 去除空白字符、零宽字符（`​-‍`、`﻿` 等）、方向控制字符
     （`‎`/`‏` 等），应对"同 城"这种拆字规避。
   - 纯包含匹配（`String.contains`），不是整词匹配——这是 ZPVIP 项目本身的设计，
     用户已经确认接受这个粒度和对应的误伤风险，不要自己加"整词匹配"之类的收紧逻辑。
2. **Hook `com.x.urt.ui.k0` 的构造函数**（before hook）：
   - 读取 `param.args[2]`（原始 `kotlinx.collections.immutable.c` 实例，也是
     `java.util.List`，可以直接当 `java.util.List` 用反射/强转来 `size()`/`get(i)`）。
   - 遍历，对每个元素：
     - 如果不是 `com.x.models.timelines.items.UrtTimelinePost` 的实例（比如是
       `UrtTimelineModule`、`UrtTimelineCursor` 等其它类型），**原样保留**，不处理
       （这次只过滤顶层的普通推文条目，`UrtTimelineModule` 内部的 Carousel 子条目
       不在这次范围内，属于已知限制，写进 notes）。
     - 如果是 `UrtTimelinePost`，反射调用它的 `getText()`，归一化后跑关键词匹配，
       命中就**不放进**过滤后的列表（也就是排除掉）。
   - 把保留下来的元素放进一个新的 `java.util.ArrayList`。
   - 用 `Proxy.newProxyInstance(classLoader, new Class<?>[]{ immutableListInterfaceClass },
     handler)` 构造代理对象，`immutableListInterfaceClass` 就是
     `XposedHelpers.findClass("kotlinx.collections.immutable.c", classLoader)`。
     `InvocationHandler` 的实现：
     - 对 `java.util.List`/`java.util.Collection`/`java.lang.Iterable` 定义的标准只读方法
       （`size`、`get`、`iterator`、`isEmpty`、`contains`、`indexOf`、`lastIndexOf`、
       `toArray`、`subList`、`forEach`、`spliterator`、`stream`、`listIterator` 等）
       委托给过滤后的 `ArrayList`。
     - `equals`/`hashCode`/`toString` 也委托给过滤后的 `ArrayList`（避免用代理对象自己默认的
       identity equals/hashCode 导致奇怪的行为）。
     - **任何没预料到的方法**（比如这个接口可能有的 immutable-collection 专属 builder 方法，
       像 `add`/`removeAt`/`addAll` 这类返回新实例的方法）：**转发到原始未过滤的
       `param.args[2]` 对象**，不要抛异常、不要返回 null 兜底——这样即使调用到没覆盖的方法，
       行为最多是"这一次调用没被过滤"，不会崩溃。这个 fallback 分支要用
       `XposedBridge.log` 记一条"遇到未预期方法"的日志，方便后续发现真的用到了再补。
   - 把 `param.args[2]` 设为这个代理对象。
   - 找不到 `com.x.urt.ui.k0` 类、找不到构造函数、参数类型对不上：`XposedBridge.log`
     明确报错，不允许静默跳过。
3. **保留所有已有的探测代码**（`getEntryId`/`getText`/两个构造函数探测/
   `serializer.deserialize`/旧模型层两个点），这次新增真正过滤的 hook 不要删除/替换它们，
   并存即可，之后要不要精简是后面的事，这次先保证功能正确。

## 关于覆盖面的已知限制（如实写进 notes，不要自己加逻辑去"猜"其它场景）

`com.x.urt.ui.k0` 这次是从"主时间线"场景抓到的调用栈定位出来的，**还没有实机证据证明
搜索结果页/通知页/评论区是不是也复用同一个 `k0` 类**。这次任务范围内**不需要**你去猜/去找
其它场景专属的类——如果不是同一个类，orchestrator 会在真机测试时发现"搜索页没被过滤"，
到时候再回来追加对应场景的类（用同样的 smali 定位方法）。这次先把主时间线这条路走通、
验证机制本身可行，不要在没有证据的情况下扩大范围。

## 验收
1. `./gradlew assembleDebug` 构建成功。
2. `orchestra/probe-module-notes.md` 追加一段，说明这次加的过滤实现、Proxy 方案的具体做法、
   已知限制（Module/Carousel 内部条目不过滤、覆盖面只验证了主时间线）。
3. 仍然不要运行自测/不要碰 adb/不要连真机——orchestrator 会用真机验证过滤是否真的生效
   （目前 184 条 ZPVIP 词表未必命中当前时间线上的真实推文，orchestrator 会自己另外用一个
   受控测试词验证机制本身可行，不需要你在代码里做任何"测试模式"）。

## 编码纪律
- 第一性原理：找不到类/方法/字段/参数类型不对，必须报错，不允许 catch 后静默继续渲染
  未过滤的原始列表（那样等于过滤功能悄悄失效但看起来正常运行，是典型的"代码级兜底掩盖失败"，
  明确违反纪律）。
- 关键词列表只从 `keywords_zpvip.txt` 读，不要硬编码任何关键词到 Java 代码里。
- Proxy 的 InvocationHandler 对未覆盖方法转发原始对象，是唯一允许的"兜底"，因为那不是掩盖
  失败，是明确记录下的、有意为之的降级路径（且有日志），符合"不确定就报错/记录，不静默"的
  精神。
