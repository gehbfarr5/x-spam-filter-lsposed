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

## 已知经验（供后续阶段参考，避免重复踩坑）
- declaredFields/declaredMethods 不含继承成员，注意类层级。
- RecyclerView cell 复用场景下，绑定数据要按当前 bound 内容实时判断，
  不能用过期缓存的 key（TikTok 双击评论项目吃过这个亏）。
- LSPosed 日志会因多进程缓冲丢行，不能把"日志缺失"当"行为没发生"的证据；
  vector 桥的日志证据看 `/data/adb/lspd/log/`。
- LSPosed CLI（`su -c '/data/adb/lspd/cli ...'`）可脚本化装机+启用模块+设 scope，
  减少手动点手机。
