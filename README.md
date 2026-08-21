# x-spam-filter-lsposed

LSPosed 模块：给官方 X (Twitter) Android App 加"按关键词屏蔽推文"功能。关键词种子来自
[ZPVIP/x-spam-filter](https://github.com/ZPVIP/x-spam-filter)（Apache-2.0，见
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)）。

## 功能

- 屏蔽区域：主时间线（为你推荐/正在关注）、搜索结果、通知、评论/回复——四个场景统一生效，
  已在真机上逐一验证（详见 `orchestra/probe-findings.md`、`orchestra/probe-module-notes.md`）。
- 命中处理：完全从列表移除，不折叠、不留空白占位。
- 关键词：`app/src/main/assets/keywords_zpvip.txt`，184 条，一次性固化，不做远程同步。
- 匹配规则：小写归一化 + 去除空白/零宽/方向控制字符 + 子串包含匹配（不是整词匹配），
  与上游 ZPVIP 项目思路一致。

## 已知限制

- 只过滤顶层的普通推文条目；`UrtTimelineModule`（Carousel/多条目聚合模块）内部的子条目
  这一版不处理。
- 关键词列表本身较宽泛，可能误伤正常推文——这是用户已知并接受的取舍（词表未做人工筛选）。
- Hook 点（`com.x.urt.ui.k0` 构造函数）是从 X App 12.17.0 (versionCode 312170000) 真机
  反编译定位的，X App 更新后类名可能变化，届时需要重新反编译核实。

## 技术方案

Hook `com.x.urt.ui.k0`（Compose `LazyColumn` 主 feed 内容构建 lambda）的构造函数，
在 before-hook 里把第 3 个参数（`kotlinx.collections.immutable.c`，实现 `java.util.List`
的不可变列表，即 `List<UrtTimelineItem>`）替换成一个 `java.lang.reflect.Proxy` 动态代理，
代理内部持有过滤后的列表。完整推导过程和真机验证记录见 `orchestra/` 目录下的调研/探测文档。

## 开发状态

P2 核心过滤功能已完成并通过真机验证（受控测试关键词，四个场景均确认命中/移除生效）。
不是"探测阶段"了，可以正常安装使用。

## 构建

```
./gradlew assembleDebug
```

Xposed API 用本地 `app/libs/api-82.jar`（不依赖 `api.xposed.info` Maven 仓）。
