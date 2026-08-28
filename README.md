# x-spam-filter-lsposed

LSPosed 模块：给官方 X (Twitter) Android App 加"按关键词屏蔽推文"功能。关键词种子来自
[ZPVIP/x-spam-filter](https://github.com/ZPVIP/x-spam-filter)（Apache-2.0，见
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)）。

## 0.3.0 功能

- 屏蔽区域：主时间线（为你推荐/正在关注）、搜索结果、通知、评论/回复——四个场景统一生效，
  已在真机上逐一验证（详见 `orchestra/probe-findings.md`、`orchestra/probe-module-notes.md`）。
- 命中处理：完全从列表移除，不折叠、不留空白占位。
- Material 3 管理界面：概览、来源、规则、日志四个主入口，支持深色模式、动态配色、
  edge-to-edge 安全边距和宽屏侧边导航。
- 混合规则：普通关键词、正则表达式、多片段同时出现（ALL_OF）；统一做小写、空白、
  零宽和方向控制字符归一化。
- 规则管理：搜索、启停、自定义新增、TXT/版本化 JSON 导入导出、粘贴文本测试。
- 来源更新：内置 ZPVIP 固定快照，并提供 ZPVIP 在线词库与 x-comment-blocker 常规词库；
  也可新增任意 HTTPS 纯文本订阅。每个来源独立检查、显示规则数量及 Android 正则兼容性
  差异，再由用户确认激活。WorkManager 每 24 小时只检查更新，不会自动替换生效规则。
- 快照：每次规则变更都完整验证并生成不可变快照，可从界面回滚到最近版本。
- 诊断：Hook 心跳、拦截日志和 15 分钟遗漏诊断；日志限制为最近 7 天、最多 5,000 条。
- 热加载：X 进程先用 APK 内置规则启动，应用数据库可用后通过只接受 X 进程 UID 的
  ContentProvider 原子切换已验证快照，并通过同一受控通道写回心跳和拦截日志。

版本化 JSON 示例：

```json
{
  "version": 1,
  "rules": [
    {"type": "LITERAL", "pattern": "加微信"},
    {"type": "REGEX", "pattern": "v[x信].{2,}"},
    {"type": "ALL_OF", "parts": ["她", "骚", "好看"]}
  ]
}
```

## 已知限制

- 只过滤顶层的普通推文条目；`UrtTimelineModule`（Carousel/多条目聚合模块）内部的子条目
  这一版不处理。
- 关键词列表本身可能误伤正常推文；社区来源默认关闭，首次同步必须检查差异后激活。
- Hook 点（`com.x.urt.ui.n0` 构造函数）是从 X App 12.19.1-release.0 (versionCode
  312191000) 真机反编译定位的，X App 更新后类名可能变化，届时需要重新反编译核实
  （已发生过一次：12.17.0 的 `com.x.urt.ui.k0` 在 12.19.1 上被 R8 重新分配给了
  另一个无关类，详见 `orchestra/probe-module-notes.md` P3 记录）。

## 技术方案

Hook `com.x.urt.ui.n0`（Compose `LazyColumn` 主 feed 内容构建 lambda）的构造函数，
在 before-hook 里把第 3 个参数（`kotlinx.collections.immutable.c`，实现 `java.util.List`
的不可变列表，即 `List<UrtTimelineItem>`）替换成一个 `java.lang.reflect.Proxy` 动态代理，
代理内部持有过滤后的列表。`DynamicRuleBridge` 通过只允许 `com.twitter.android` 调用的
窄接口读取规则快照并写回日志；接口不暴露任意数据库查询或更新。完整推导过程和历史真机
验证记录见 `orchestra/` 目录下的调研/探测文档。

## 开发状态

0.1.0 的静态过滤主链与 0.2.0 的基础管理界面曾通过真机验证。0.3.0 重做了动态快照、
心跳/日志通道和多来源订阅，必须重新通过本地测试、`PLK110_API_36` 模拟器门禁及
OnePlus 15 最终验收；旧版本结论不能直接沿用。

## 构建

```
./gradlew lintDebug testDebugUnitTest assembleDebug assembleDebugAndroidTest
```

Xposed API 用本地 `app/libs/api-82.jar`（不依赖 `api.xposed.info` Maven 仓）。
