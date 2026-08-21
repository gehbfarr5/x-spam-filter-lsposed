# ZPVIP/x-spam-filter 调研

> 调研快照：2026-08-21（Asia/Shanghai）。目标仓库：[`ZPVIP/x-spam-filter`](https://github.com/ZPVIP/x-spam-filter)。除非特别说明，源码证据固定到当时 `main` 的最新提交 [`9cf7cc189ad0855f1d531f50dea75d18a9669ffb`](https://github.com/ZPVIP/x-spam-filter/commit/9cf7cc189ad0855f1d531f50dea75d18a9669ffb)，避免后续 `main` 漂移。

## 结论先行

- **可以把 ZPVIP 的内置词库作为种子数据复用，但不宜不加审查地直接作为“全时间线硬屏蔽”规则。** 它是为“推文详情页里的垃圾回复”调出来的；代码明确排除主推文。列表里有 `男大`、`女仆`、`御姐`、`超可爱`、`纯情` 等宽泛短词，放大到普通时间线很容易误伤——这是基于词面和作用域差异的风险推断，仓库没有 issue 数据可以验证真实误报率。
- ZPVIP 自己维护的列表在根目录 [`keywords.txt`](https://github.com/ZPVIP/x-spam-filter/blob/9cf7cc189ad0855f1d531f50dea75d18a9669ffb/keywords.txt)，当前 **184 条、184 条唯一值、0 条正则**。格式是 UTF-8 纯文本，一行一条，无 JSON/YAML，无分类字段。
- 扩展实际还会在线同步第二份 [`amahteru/x-comment-blocker/keywords.txt`](https://github.com/amahteru/x-comment-blocker/blob/7233b40bdf5665b08036350f891133ede2271912/keywords.txt)。该社区源在本次快照有 **755 个非空行：3 个分类标题、716 个普通字符串、36 个正则**；它不是 ZPVIP 仓库的 Apache-2.0 内容，而是另一个 **MIT** 项目，复制时必须单独保留其许可证和版权声明。
- ZPVIP 仓库采用 **Apache License 2.0**。复制其 `keywords.txt` 到公开 LSPosed 项目是允许的，但发布时应附 Apache-2.0 全文、保留适用的归属/声明；若改过列表，应显著标明修改。仓库根目录未见 `NOTICE` 文件，所以当前没有额外 NOTICE 内容需要转抄。
- 可信度判断：**适合作为候选词种子，不足以当作经过验证的基准数据集。** 仓库只有 6 次提交、1 位贡献者、23 stars、0 forks、0 open issues、0 closed issues、无 release/tag；创建到最近更新只覆盖 2026-08-12 至 2026-08-16，无法推断长期维护节奏，也没有误报讨论或评测集。

## 研究范围与方法

本地目标是 Android 官方 X App 的 LSPosed 模块；上游则是 Chromium Manifest V3 浏览器扩展。这意味着可直接复用的是**数据、归一化策略、白名单和可解释命中设计**，DOM 选择器、`MutationObserver` 等浏览器实现不能直接搬到 Android。

按 `github-solution-research` 方法先尝试了只读 `gh repo view`、`gh api`（树、README、许可证）以及 issue 搜索；当前沙箱阻止 `gh` 连接本机代理 `127.0.0.1:7897`，并非 GitHub 鉴权/限流。之后使用已登录 Chrome 直接读取 GitHub 仓库、blob、commit、history 和 issue 页面完成核验。没有使用子代理：只有一个明确主仓库和一个代码声明的上游数据源，拆分搜索会重复证据。

## 1. 关键词列表位置、格式与内容

### 1.1 ZPVIP 内置词库

| 项目 | 结论 | 证据 |
| --- | --- | --- |
| 文件 | 仓库根目录 `keywords.txt` | [`keywords.txt`](https://github.com/ZPVIP/x-spam-filter/blob/9cf7cc189ad0855f1d531f50dea75d18a9669ffb/keywords.txt) |
| 格式 | 纯文本，一行一条；加载器 trim、跳过空行、按原文去重 | [`src/shared.js` L123-L134](https://github.com/ZPVIP/x-spam-filter/blob/9cf7cc189ad0855f1d531f50dea75d18a9669ffb/src/shared.js#L123-L134) |
| 规模 | 184 个非空行、184 个唯一值 | [`keywords.txt` L1-L184](https://github.com/ZPVIP/x-spam-filter/blob/9cf7cc189ad0855f1d531f50dea75d18a9669ffb/keywords.txt#L1-L184) |
| 规则类型 | 当前 184 条全部是普通字符串；没有 `/pattern/flags` 条目 | 同上；正则语法解析见 [`src/shared.js` L63-L66](https://github.com/ZPVIP/x-spam-filter/blob/9cf7cc189ad0855f1d531f50dea75d18a9669ffb/src/shared.js#L63-L66) |
| 分类 | **没有结构化分类，也没有注释标题** | 同上 |
| 语言 | 主要是中文/中英混写，另有 9 条纯英文短语；没有语言标签 | [`keywords.txt` L141-L149](https://github.com/ZPVIP/x-spam-filter/blob/9cf7cc189ad0855f1d531f50dea75d18a9669ffb/keywords.txt#L141-L149) |

前 40 条原文样例（直接摘自 [`keywords.txt` L1-L40](https://github.com/ZPVIP/x-spam-filter/blob/9cf7cc189ad0855f1d531f50dea75d18a9669ffb/keywords.txt#L1-L40)）：

```text
线下吗
哥哥线下
弟弟线下
dd线下
玩的dd
同城线下
线下约见
线下对接
线下牵线
线下固炮
线下sao货
无偿线下
私信约线下
真实约见
同城会面
见同城
同城的哥哥
同城附近
同城约爱
同城约p
同城约炮
同城速约
同城速配
同城炮友
同城无偿
同城上门
同城丄门
同城男单
同城男大
附近牵线
附近好友约
附近的有没
附近资源
离得近的
万达广场附近
火车站附近
约个同城
约p吗
线下面
出来玩吗
```

列表从词面上大致覆盖线下约见/色情引流、私信和主页导流、网盘/资源群、博彩或收益承诺等，但这只是人工归纳，**不是仓库提供的分类**。不应据此声称每条都有类别、严重度或人工审核标签。

完整获取方式：

- 固定快照 raw URL：<https://raw.githubusercontent.com/ZPVIP/x-spam-filter/9cf7cc189ad0855f1d531f50dea75d18a9669ffb/keywords.txt>
- 随 `main` 更新的 raw URL：<https://raw.githubusercontent.com/ZPVIP/x-spam-filter/main/keywords.txt>
- 推荐固化时记录 SHA、下载日期和许可证，不要只保存一个无法追溯的裸文本文件。

### 1.2 扩展实际使用的外部社区词库

ZPVIP 代码定义了两个远端源：内置源指向本仓库，社区源指向 `amahteru/x-comment-blocker`；见 [`src/shared.js` L153-L171](https://github.com/ZPVIP/x-spam-filter/blob/9cf7cc189ad0855f1d531f50dea75d18a9669ffb/src/shared.js#L153-L171)。更新日志说明添加社区源时，从 ZPVIP 内置表删除了 74 个重复项，只留下上游当时没有的 183 条；见 [`CHANGELOG.md` L27-L44](https://github.com/ZPVIP/x-spam-filter/blob/9cf7cc189ad0855f1d531f50dea75d18a9669ffb/CHANGELOG.md#L27-L44)。后来 ZPVIP 又增加 1 条，成为当前 184 条。

社区源在本次调研时固定到 [`7233b40bdf5665b08036350f891133ede2271912`](https://github.com/amahteru/x-comment-blocker/commit/7233b40bdf5665b08036350f891133ede2271912)：

- 755 个非空且互异的行；
- 3 个标题：第 1 行 `# [常规屏蔽词]`、第 450 行 `# [仇恨用语]`、第 534 行 `# [用户名]`；
- 716 个普通字符串、36 个 `/pattern/flags` 正则；
- ZPVIP 的解析器只跳过空行，不跳过 `#` 注释，因此这 3 个标题也会被当作几乎永远不命中的普通关键词；证据见 [`XSF_parseKeywordText`](https://github.com/ZPVIP/x-spam-filter/blob/9cf7cc189ad0855f1d531f50dea75d18a9669ffb/src/shared.js#L123-L134)。

社区源前 30 行原文样例（直接摘自固定快照）：

```text
# [常规屏蔽词]
无偿
万达广场
附近的
男大弟弟
寻男大
固泡
比她骚
sao货
骚货
没她好看
她好涩
比她好看
没她骚
比她sao
没她sao
就她的主页能打
第一骚
想玩点刺激的
想被w套入
刷了半天的X
找个搭子
无🚪线下
dd个线下的
无任何套路
有弟弟想
来个真人
真实资源
同城
免费破处
```

完整获取：

- 固定快照：<https://raw.githubusercontent.com/amahteru/x-comment-blocker/7233b40bdf5665b08036350f891133ede2271912/keywords.txt>
- 浮动 `main`：<https://raw.githubusercontent.com/amahteru/x-comment-blocker/main/keywords.txt>

按 ZPVIP 的默认归一化规则比较，本次快照中两份列表没有原文完全重复项，但 `求主人`、`主人快来` 会与社区源含零宽字符的同形项归一化为相同规则。184 + 755 个原始非空行最终约为 **937 个编译匹配器**（减去这 2 个归一化重复项；其中仍包含 3 个标题伪规则）。这说明后续自己固化时应先按目标归一化算法去重，而不只是原文 `Set` 去重。

## 2. 许可证与复制条件

### 2.1 ZPVIP 内置列表：Apache-2.0

仓库有明确 [`LICENSE`](https://github.com/ZPVIP/x-spam-filter/blob/9cf7cc189ad0855f1d531f50dea75d18a9669ffb/LICENSE)，是标准 **Apache License 2.0**，不是“未声明许可证”。关键条款：

- 第 2 节授予复制、修改、再许可和分发权：[`LICENSE` L66-L73](https://github.com/ZPVIP/x-spam-filter/blob/9cf7cc189ad0855f1d531f50dea75d18a9669ffb/LICENSE#L66-L73)。
- 再分发必须给接收者一份许可证；修改过的文件必须显著说明已修改；源码形式的衍生作品要保留适用的版权、专利、商标和归属声明：[`LICENSE` L89-L105](https://github.com/ZPVIP/x-spam-filter/blob/9cf7cc189ad0855f1d531f50dea75d18a9669ffb/LICENSE#L89-L105)。
- 如果上游含 `NOTICE` 才需传播其中的归属内容：[`LICENSE` L106-L122](https://github.com/ZPVIP/x-spam-filter/blob/9cf7cc189ad0855f1d531f50dea75d18a9669ffb/LICENSE#L106-L122)。本次核对仓库根目录未见 `NOTICE`。
- 可以为自己的修改或衍生项目整体采用不同/附加条款，但对上游 Work 的使用和分发仍须满足 Apache-2.0：[`LICENSE` L123-L129](https://github.com/ZPVIP/x-spam-filter/blob/9cf7cc189ad0855f1d531f50dea75d18a9669ffb/LICENSE#L123-L129)。

对公开 LSPosed 项目的保守落地方式：

1. 原样固化 ZPVIP 列表时，把 Apache-2.0 全文一并放进发布源码/包的第三方许可证目录，并在 `THIRD_PARTY_NOTICES.md` 写明来源仓库、源文件、固定 SHA 和下载日期。
2. 若增删或合并过列表，显著注明“modified from ZPVIP/x-spam-filter keywords.txt”，并记录修改；如果数据解析器允许注释，可放在文件头，否则用与该资产一起分发的元数据/sidecar，并在发布前确认其满足“modified files carry prominent notices”的要求。
3. 不要暗示 X、ZPVIP 或上游作者为模块背书；Apache-2.0 不授予商标使用权（[`LICENSE` L138-L142](https://github.com/ZPVIP/x-spam-filter/blob/9cf7cc189ad0855f1d531f50dea75d18a9669ffb/LICENSE#L138-L142)）。

### 2.2 社区列表：MIT，必须单独处理

`amahteru/x-comment-blocker` 的 [`LICENSE`](https://github.com/amahteru/x-comment-blocker/blob/7233b40bdf5665b08036350f891133ede2271912/LICENSE) 是 MIT，版权行为 `Copyright (c) 2026 Ethan Zhou`。复制该社区列表或其“实质部分”时，应在所有副本/实质部分中保留该版权声明、MIT 许可声明和免责条款。

**不能因为 ZPVIP 的代码是 Apache-2.0，就把从 `amahteru/x-comment-blocker` 下载的 755 行也当成 Apache-2.0。** 最简单的合规做法是让两份资产保持可区分，并在第三方许可证清单中分别列出 Apache-2.0 与 MIT。以上为工程合规建议，不代替针对具体发布方式的法律意见。

## 3. 项目如何过滤，以及可借鉴的经验

### 3.1 平台与作用范围

这是 **Chromium Manifest V3 浏览器扩展**，不是 userscript。清单版本为 1.3.0，只注入 `x.com` / `twitter.com`，权限只有 `storage` 和用于同步词库的 `raw.githubusercontent.com` host permission；见 [`manifest.json` L1-L29](https://github.com/ZPVIP/x-spam-filter/blob/9cf7cc189ad0855f1d531f50dea75d18a9669ffb/manifest.json#L1-L29)。仓库 README 也链接了 Chrome Web Store。

过滤范围非常保守：只有 URL 符合 `/用户名/status/数字` 才激活，并通过当前 status id 加“页面第一条 article”兜底来排除主推文；见 [`src/content.js` L213-L237](https://github.com/ZPVIP/x-spam-filter/blob/9cf7cc189ad0855f1d531f50dea75d18a9669ffb/src/content.js#L213-L237)。实际判定只对非主推文执行，并可同时匹配正文、昵称、@用户名；见 [`src/content.js` L499-L520](https://github.com/ZPVIP/x-spam-filter/blob/9cf7cc189ad0855f1d531f50dea75d18a9669ffb/src/content.js#L499-L520)。

### 3.2 匹配语义

- **普通关键词是包含匹配，不是整词/完全相等匹配。** 关键词被转义后合并成正则 alternation，再对整段归一化文本执行 `exec`；没有自动加词边界或 `^...$`。见 [`src/content.js` L108-L180](https://github.com/ZPVIP/x-spam-filter/blob/9cf7cc189ad0855f1d531f50dea75d18a9669ffb/src/content.js#L108-L180)。
- 默认 **不区分大小写**；普通词和正文都 `toLowerCase()`。默认还删除空白、软连字符、零宽字符和方向控制字符，可识别 `同 城 约` 之类的拆字规避；见 [`src/shared.js` L53-L80](https://github.com/ZPVIP/x-spam-filter/blob/9cf7cc189ad0855f1d531f50dea75d18a9669ffb/src/shared.js#L53-L80)。这也会让普通关键词跨空格/换行拼接匹配，召回率提高但可能增加误报。
- `/正则/flags` 单独编译。正则保留自身的大小写与空白语义，只移除零宽/方向控制字符；代码主动删掉 `g`、`y` flag，避免 `RegExp.lastIndex` 导致间歇性漏判。无效正则会在编译阶段被忽略并输出 warning；见 [`src/content.js` L126-L141](https://github.com/ZPVIP/x-spam-filter/blob/9cf7cc189ad0855f1d531f50dea75d18a9669ffb/src/content.js#L126-L141)。
- 普通词按长度降序，再每 400 条合并成一个大正则；自定义正则独立复用。见 [`src/content.js` L39-L44](https://github.com/ZPVIP/x-spam-filter/blob/9cf7cc189ad0855f1d531f50dea75d18a9669ffb/src/content.js#L39-L44) 和 [`L150-L161`](https://github.com/ZPVIP/x-spam-filter/blob/9cf7cc189ad0855f1d531f50dea75d18a9669ffb/src/content.js#L150-L161)。

正则与普通词的取舍经验：稳定且足够具体的短语适合普通包含匹配；需要结构、上下文、数量/日期格式、词间距离或全串约束时才使用正则。不要用非常泛的单词做全时间线包含匹配；若必须保留，应增加作用域、严重度或多信号门槛。

### 3.3 误伤控制与可解释性

上游没有用更复杂的分类器降低误报，而是用产品机制让误伤**可见、可撤销、局部化**：

- 默认“变透明”而非强制删除，也支持隐藏；
- 只过滤回复，不动主推文；
- 命中的原词在正文/昵称/用户名中红色高亮；
- 白名单停用的是具体规则，不是整条推文；
- 内置、社区、用户三种规则源均可被白名单覆盖；
- 同一回复可能命中多条，白名单一条后仍可能被另一条拦截。

这些行为在 [`README.md` L43-L55](https://github.com/ZPVIP/x-spam-filter/blob/9cf7cc189ad0855f1d531f50dea75d18a9669ffb/README.md#L43-L55) 与 [`L143-L181`](https://github.com/ZPVIP/x-spam-filter/blob/9cf7cc189ad0855f1d531f50dea75d18a9669ffb/README.md#L143-L181) 有明确说明。更新日志还记录了一个真实实现坑：X 会把正文切成多个 `span`，emoji 是 `<img alt>`，所以高亮必须先把子树展平成整段文本，匹配后再映射回 DOM；见 [`CHANGELOG.md` L5-L25](https://github.com/ZPVIP/x-spam-filter/blob/9cf7cc189ad0855f1d531f50dea75d18a9669ffb/CHANGELOG.md#L5-L25)。这个 DOM 细节不适用于原生 Android，但“按完整语义文本匹配，再映射到 UI 命中范围”的原则值得保留。

### 3.4 性能与动态内容

浏览器端通过 `MutationObserver` 只收集新增/变化的 tweet article，优先在 `requestAnimationFrame` 批处理，后台 tab 另用 300 ms timer 兜底；突发超过 800 条 mutation 时才全量扫描。见 [`src/content.js` L541-L610](https://github.com/ZPVIP/x-spam-filter/blob/9cf7cc189ad0855f1d531f50dea75d18a9669ffb/src/content.js#L541-L610)。判定结果存在以 DOM article 为键的 `WeakMap`，正文+昵称+配置 generation 不变则跳过重算；见 [`src/content.js` L71-L83](https://github.com/ZPVIP/x-spam-filter/blob/9cf7cc189ad0855f1d531f50dea75d18a9669ffb/src/content.js#L71-L83) 和 [`L501-L520`](https://github.com/ZPVIP/x-spam-filter/blob/9cf7cc189ad0855f1d531f50dea75d18a9669ffb/src/content.js#L501-L520)。

### 3.5 对 LSPosed 模块的 reuse / adapt / avoid

| 决策 | 内容 |
| --- | --- |
| **Reuse** | 固定 SHA 的词库资产；普通词/正则分流；大小写与不可见字符归一化；按归一化值去重；逐规则白名单；显示命中原因；词库更新保留旧快照和回滚能力。 |
| **Adapt** | 把浏览器的 DOM article/text/name 抽取替换为 X Android 当前版本的数据模型或视图绑定点；正文、显示名、handle 分开配置；默认先只作用于回复或低风险“变淡/折叠”模式；为规则增加来源、类别、严重度和启用开关。 |
| **Avoid** | 不复制 `MutationObserver`/CSS/WeakMap 的具体架构；不在没有版本映射和回归样本时对所有时间线硬隐藏；不运行时静默跟随远端 `main`；不把 ZPVIP 与社区列表混成一个失去来源和许可证边界的文件；不把泛化短词当作高置信硬拦截。 |

值得特别借鉴的更新策略是“下载失败保留旧快照”：远端内容有 2 MiB 上限、拒绝 HTML 错误页、拒绝空列表；见 [`src/shared.js` L173-L198](https://github.com/ZPVIP/x-spam-filter/blob/9cf7cc189ad0855f1d531f50dea75d18a9669ffb/src/shared.js#L173-L198)。但其社区词库自动拉取只发生在首次安装/从未成功同步时，失败后靠设置页手动同步；见 [`src/background.js` L1-L36](https://github.com/ZPVIP/x-spam-filter/blob/9cf7cc189ad0855f1d531f50dea75d18a9669ffb/src/background.js#L1-L36)。LSPosed 版本若内置固化资产，更适合“显式版本 + 签名/校验和 + 用户确认更新 + 可回滚”，而不是开机静默追随 `main`。

## 4. 可信度与时效性

### 4.1 项目基本面

| 指标 | 2026-08-21 快照 | 证据/解读 |
| --- | ---: | --- |
| Stars | 23 | [仓库主页](https://github.com/ZPVIP/x-spam-filter)；只能作为很弱的成熟度信号 |
| Forks | 0 | 同上 |
| 主要语言 | JavaScript（主页显示 71.8%） | 同上；浏览器扩展 |
| 许可证 | Apache-2.0 | [`LICENSE`](https://github.com/ZPVIP/x-spam-filter/blob/9cf7cc189ad0855f1d531f50dea75d18a9669ffb/LICENSE) |
| 提交数 | 6 | [全部提交](https://github.com/ZPVIP/x-spam-filter/commits/main/) |
| 贡献者 | 1 | 仓库主页 |
| 最新提交 | 2026-08-16 04:45 GMT+8，`9cf7cc1` | [`Add new keyword to keywords.txt`](https://github.com/ZPVIP/x-spam-filter/commit/9cf7cc189ad0855f1d531f50dea75d18a9669ffb) |
| Open issues | 0 | [open issue 查询](https://github.com/ZPVIP/x-spam-filter/issues?q=is%3Aissue%20state%3Aopen) |
| Closed issues | 0 | [closed issue 查询](https://github.com/ZPVIP/x-spam-filter/issues?q=is%3Aissue%20state%3Aclosed) |
| Releases / tags | 无 release、0 tags | 仓库主页 |

### 4.2 词库更新频率

- 2026-08-14 的 [`4fdfe1e`](https://github.com/ZPVIP/x-spam-filter/commit/4fdfe1ec1bf3792606242f53f1f3123a32fa1e5c) 为 `keywords.txt` 增加 183 行，并接入社区源。
- 2026-08-16 的 [`9cf7cc1`](https://github.com/ZPVIP/x-spam-filter/commit/9cf7cc189ad0855f1d531f50dea75d18a9669ffb) 再增加 1 行 `只进入身体`；diff 明确是 `+1`。
- 仓库最早提交是 2026-08-12，观察窗口只有 4 天。能确认“初期有快速迭代”，**不能据此推断周更/月更或长期维护承诺**。

外部社区源明显更活跃：仓库主页在快照时显示 332 stars、13 forks、893 commits，词库同一天刚在 [`7233b40`](https://github.com/amahteru/x-comment-blocker/commit/7233b40bdf5665b08036350f891133ede2271912) 更新，而且 2026-08-20 至 21 有多次 `keywords.txt` 提交（[文件历史](https://github.com/amahteru/x-comment-blocker/commits/main/keywords.txt)）。活跃意味着更及时，也意味着 `main` 高度可变；如果我们发布可复现 APK，应固定 SHA 后人工审阅，而不是构建时永远取最新。

### 4.3 误报、过时讨论与证据缺口

目标仓库 issue 页显示 **open 0 / closed 0**，导航中也没有启用 Discussions 的证据。因此：

- 未找到“关键词过时”“误报”“漏报”类 issue；
- 未找到维护者给出的精确率/召回率、标注语料、自动测试或误报统计；
- “没有相关 issue”不能解读为“没有误报”，更可能是项目过新、使用者反馈尚少；
- README 的白名单、高亮命中和默认变淡模式表明作者预期误伤会发生，但这是产品设计证据，不是误报率数据。

## 最终建议

建议路线是 **adapt，而不是 wholesale copy**：

1. 先固定 ZPVIP `9cf7cc1` 的 184 条作为“候选种子”，单独保留 Apache-2.0 来源元数据。
2. 是否引入社区 755 行另做决定；若引入，固定 `7233b40`、保留 MIT 版权/许可证，解析时真正跳过 `#` 标题并按归一化值去重。
3. 首版不要把所有规则都设为全时间线硬屏蔽。至少分为“高置信短语/正则”和“泛化短词”，后者默认只折叠或仅在回复区启用。
4. 在 Android 层保留可解释命中、逐规则白名单、来源/版本显示、关闭即恢复和回滚旧词库；这些比浏览器 DOM 实现本身更值得复用。
5. 建立自己的最小验收集：真实垃圾回复、正常中文推文、昵称/handle、混合英文、空格/零宽规避、emoji、同一文本多规则命中。上游没有这类证据，所以在该验收集通过前，可靠性结论应标为 **中低置信度**。

**综合判断：** 许可证清楚、文件格式简单、匹配与误伤控制思路值得借鉴；但项目年龄、维护者规模和反馈数据都太少。把列表当作可追溯的 seed 很合适，把它当作无需复核的生产黑名单则证据不足。
