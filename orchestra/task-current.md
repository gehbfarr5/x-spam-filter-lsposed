# x-spam-filter-lsposed — 总任务书

## 目标
用 LSPosed 模块给**官方** X (Twitter) Android App 加「按关键词屏蔽推文」功能，
灵感/关键词来源参考 https://github.com/ZPVIP/x-spam-filter。

## 产品决策（已与用户确认，不要再问）
| 项 | 决定 |
|---|---|
| 屏蔽区域 | 主时间线(Home/For You/Following) + 搜索结果页 + 评论区/回复 + 通知页，四区域统一生效，不做独立开关 |
| 命中处理 | 完全隐藏（从列表移除该 item，不折叠不展开） |
| 关键词来源 | 一次性复制固化 x-spam-filter 的关键词列表到本地文件，不做远程同步 |
| 测试环境 | OnePlus 15 真机（PLK110，序列号 3B166Q00SX000000，已 root + LSPosed） |
| GitHub | 新建 `gehbfarr5/x-spam-filter-lsposed`，public |

## 当前阶段：P0 调研（架构决策未定，禁止跳过直接写 hook 代码）

X App 是否混淆、真实包名/版本、可用的 hook 点，都还没有实测证据，
在没有证据前**禁止**凭猜测/记忆硬编码任何类名方法名（编码纪律②，参考
RedReader/TikTok 项目的教训：混淆名会随版本轮换，必须先反编译核实）。

### P0-a：x-spam-filter 仓库调研（GitHub 类，外包 github-solution-research）
需要弄清楚：
1. 关键词列表具体在哪个文件、什么格式（纯文本/正则/JSON/分类）。
2. 许可证是什么，能否直接复制关键词到本项目，需不需要署名/保留 license。
3. 该项目自己的过滤逻辑思路（大小写处理、多语言、误判规避）有没有可参考的经验，
   即使不复用它的实现方式，也要看看它踩过什么坑。
4. 项目是否还在维护、star/issue 情况，判断关键词列表的可信度和时效性。

### P0-b：X App 实机侦察（本地操作，orchestrator 直接跑，不进 Codex 沙箱）
真机 pull 当前安装的 X App APK，jadx 反编译，核实：
1. 真实包名、versionCode、版本号。
2. 是否混淆（有没有 `-dontobfuscate` 痕迹、类名字段名可读性）。
3. 时间线/搜索/评论/通知四个场景，UI 层 Adapter/ViewHolder 或数据模型层有没有
   语义稳定的候选 hook 点（例如某个 Tweet/Status 数据类的 text 字段访问路径）。
4. 网络层响应是否有更稳定的过滤时机（例如统一走某个 JSON 反序列化后的模型类，
   可能比多个 UI Adapter 更少变、更集中）。

产出落 `orchestra/evidence/`（jadx 反编译产物太大不进 git，只在报告里摘录关键片段）
和 `orchestra/recon-x-app.md`（结论摘要）。

### P0 产出物
- `orchestra/research-x-spam-filter.md`（github-solution-research 产出）
- `orchestra/recon-x-app.md`（真机侦察结论）
- 基于以上两份证据，orchestrator 给出 **≥2 条技术方案对比**（例如 Hook UI 层
  Adapter/ViewHolder vs Hook 网络层 JSON 解析后过滤），写入
  `orchestra/architecture-proposal.md`，并推荐一条，说明理由（稳定性/维护成本/
  对 App 更新的抗性/许可证与署名要求）。

**P0 完成后必须把 `architecture-proposal.md` 拿给用户确认，用户点头选定方案后，
才能进入 P1 实现阶段（写 task-current.md 的 P1 部分、真正开始写 hook 代码）。**

## 编码纪律
1. 复用优先：能力所及范围内参考 x-spam-filter 现成关键词列表和踩坑经验；
   工程骨架可参考本机既有 LSPosed 模块模板（`redreader-ai-translate-xposed`、
   `tiktok-post-blocker` 等）里验证过的 Gradle/AGP/xposed_init 配置。
2. **第一性原理，禁代码级兜底**：hook 找不到目标类/方法/字段时必须
   `XposedBridge.log` 明确报出并让功能可见地失效，不允许静默降级/吞异常。
3. 非琐碎决策先给 ≥2 方案对比（本任务书已要求 P0 产出方案对比）。
4. 不确定就去反编译/查文档核实，不凭记忆或猜测硬编码。

## P1（用户已确认，2026-08-21）：工程骨架 + 探测模块

**产品决策补充**：关键词范围用户已确认"184 条全部照收，接受误伤风险"，不做人工筛选/分区域
词表。技术方案用户已确认采用方案 A（Hook 数据模型/JSON 解析层）。

### P1 目标
不直接写真正的过滤逻辑。先建工程骨架 + 一个**只打日志、不做任何拦截**的探测模块，
在真机上挂到 `architecture-proposal.md` 方案 A 涉及的候选类上，验证：
1. 时间线主页/搜索/评论区/通知这四个场景，运行时实际各自触发的是新模型层
   （`com.x.models.timelines.items.Urt*`，kotlinx.serialization）还是旧模型层
   （`com.twitter.model.json.timeline.urt.Json*`，LoganSquare），或者两者都有。
2. 找到"entries 列表已经解析完成、即将交给 UI 层"的精确注入点（类名+方法签名）。

候选挂载点（来自 `recon-x-app.md`，须先用 jadx 反编译确认在当前真机版本 12.17.0 dex 里
的精确方法签名，不能直接照抄本报告里的旧版本推断）：
- 新模型层：`com.x.models.timelines.items.UrtTimelinePost` 的构造函数/`getText()`
  实际指向的 `com.x.models.PostResult.getText()`。
- 旧模型层：`com.twitter.model.json.timeline.urt.JsonTimelineEntry.r()`（把 JSON 转成
  内部业务对象的统一入口）、`com.twitter.model.json.timeline.urt.JsonAddEntriesInstruction.r()`。

探测模块要求：
- hook 上述候选方法，`XposedBridge.log` 打印：命中的类全名、方法签名、拿到的正文文本前
  50 字符、以及从堆栈或参数能获取到的"当前是哪个页面/请求"的线索（如果实在拿不到页面来源，
  至少要能通过"用户手动切换到某个页面再操作"配合日志时间戳人工关联）。
- **绝不修改任何返回值/绝不移除任何数据**，纯观察，避免探测阶段把 App 弄崩或产生难以判断
  是探测代码问题还是过滤逻辑问题的混淆结果。
- 找不到目标类/方法时必须 log 报错并让该 hook 明确失效（不允许吞异常）。

### 工程骨架
- 参照 `~/Desktop/redreader-ai-translate-xposed/` 或
  `~/Desktop/doubao-letter-longpress-voice/` 的 Gradle/AGP/`api-82.jar`/`xposed_init`
  配置直接复用，不要重新踩一遍 Xposed API 依赖、AGP 版本这些坑。
- 包名建议 `dev.xspamfilter.lsposed`（或类似，避免和 x-spam-filter 浏览器扩展的包名/命名空间
  混淆），`xposedscope` 填 `com.twitter.android`。
- README 需要说明这是探测阶段（P1 probe），不是最终发布版本，避免用户或未来协作者误装到生产。

### P1 验收标准（探测阶段，不是最终功能验收）
1. `./gradlew assembleDebug` 构建成功产出 APK。
2. 真机（PLK110 / 3B166Q00SX000000）能安装、LSPosed 里能启用、scope 正确限定
   `com.twitter.android`。
3. orchestrator 手动操作四个场景后，`/data/adb/lspd/log/` 或
   `XposedBridge.log`（视 vector 桥日志落点）里能看到探测日志，日志内容足以回答上面
   "P1 目标"的两个问题。
4. 产出 `orchestra/probe-findings.md`，写清楚四个场景各自命中的类/方法，供下一阶段
   （真正的过滤逻辑实现）直接引用，不需要重新反编译。

**P1 完成、探测结论明确后，才进入 P2：写真正的关键词过滤 hook（在探测确认的精确注入点上
实现"命中关键词则从 entries 列表移除该条目"），P2 任务书到时候再补。**

## P3（2026-08-27）：X App 更新到 12.19.1 后的 hook 点修复

P2 完成并全场景验证通过后（见 `probe-findings.md`），X App 从 12.17.0 自动升级到
**12.19.1-release.0** (versionCode 312191000)，模块 fail-loud 报错失效。真机日志
定位到根因：R8 混淆产物类名 `com.x.urt.ui.k0` 在新构建里被重新分配给了另一个
无关的 2 参数类，同时 `com.x.performance.i` 类型消失。orchestrator 已直接拉取
真机现装的新版 base.apk，用 baksmali 重新定位到新类 `com.x.urt.ui.n0`
（架构、其余 11 个构造参数类型、`kotlinx.collections.immutable.c` 接口名均未变，
仅 `com.x.performance.i`→`com.x.performance.g`），详见
`orchestra/task-p3-app-update-fix.md`。

本轮判档：**省额度档**（`gpt-5.6-luna`·high）——证据已经精确到"改哪个文件的哪两个
字符串"，属于"明确琐碎小改"，不需要 sol·xhigh 的重逆向/重设计投入。Verifier 仍用
Sonnet + 真机四场景回归验证（不因为改动小就降低验收标准）。

### P3 验收标准
1. `./gradlew assembleDebug` 构建成功。
2. 真机重装/重启 X App 后，`/data/adb/lspd/log/` 里不再出现
   `FAILED to install exact com.x.urt.ui.* constructor hook`，能看到
   `hook installed exact constructor: com.x.urt.ui.n0(...)` 和
   `main-feed list filtered: original=N kept=N removed=N` 日志。
3. 用受控测试关键词在四个场景（主时间线/搜索/通知/评论区）里各验证一次命中后
   完全移除，然后恢复生产 184 词表，确认正常内容不受影响、无崩溃。
4. `orchestra/probe-module-notes.md` 追加本次版本升级+修复记录。

## 已知经验（供后续阶段参考，避免重复踩坑）
- declaredFields/declaredMethods 不含继承成员，注意类层级。
- RecyclerView cell 复用场景下，绑定数据要按当前 bound 内容实时判断，
  不能用过期缓存的 key（TikTok 双击评论项目吃过这个亏）。
- LSPosed 日志会因多进程缓冲丢行，不能把"日志缺失"当"行为没发生"的证据；
  vector 桥的日志证据看 `/data/adb/lspd/log/`。
- LSPosed CLI（`su -c '/data/adb/lspd/cli ...'`）可脚本化装机+启用模块+设 scope，
  减少手动点手机。
