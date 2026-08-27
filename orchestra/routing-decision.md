# Routing Decision — x-spam-filter-lsposed (P0 调研阶段)

日期：2026-08-21

## ① 判档理由
**复杂任务档** → `codex -m gpt-5.6-sol`，推理强度 `xhigh`。

- 新建完整子系统：Xposed hook + 关键词过滤内核 + 4 个不同 UI/数据场景，运行在
  第三方宿主进程内（X App 而非 TikTok/RedReader 那种已有本机经验的宿主），
  混淆程度未知，需要先逆向才能定方案，技术不确定性和风险都偏高。
- 参考同类先例：TikTok 双击评论/Post Blocker 项目涉及混淆逆向+运行时特征匹配，
  当时也是按高难度处理；本项目目标宿主比 RedReader（未混淆、有本机踩过的坑）
  更陌生，理应至少同等或更高档位。

**Verifier**：全阶段用 Opus（新增子系统 + 进程内注入 + 未知混淆程度，风险高）。

## ② 研究外包决策
**P0-a（x-spam-filter 仓库调研）→ `[skill: github-solution-research]`**
GitHub 类找现成资料（关键词列表位置/格式/许可证/维护状态），走 Codex 额度，
不碰 Antigravity 配额，是典型适用场景。

**P0-b（X App 实机侦察：adb pull APK + jadx 反编译）→ `[skip，orchestrator 直接跑]`**
理由：MOBILE_ROUTING.md 硬约束——adb 对真机的操作不能通过 Codex 沙箱驱动
（socket 权限会被拒，历史上反复失败）；这类操作必须由 orchestrator（Claude 主
会话，非沙箱）直接执行，Codex 只处理已经落地的静态文件（如果后续需要深度阅读
jadx 产物，可以另外派一次"只读文件、不碰 adb"的 Codex 任务）。

同项目 `.lock` 不支持并发，P0-a 与 P0-b 已确认按顺序串行执行，不需要 git worktree
隔离。

## ③ 安全与边界
- 项目目录 `~/Desktop/x-spam-filter-lsposed`，不在 home 根跑执行器。
- Codex 一律 `-m gpt-5.6-sol -s workspace-write -C <项目> xhigh`。
- 执行器只改文件不 commit，落库由 orchestrator 串行经 git。
- 真机操作前核验 `ro.product.model=PLK110` 且 `ro.serialno=3B166Q00SX000000`，
  优先 USB ADB（用户偏好，见本机 memory）。
- jadx 反编译产物体积大，不进 git；只把关键代码片段摘录进 `recon-x-app.md`。
- P0 结束（架构方案定稿）前，不写任何 hook 代码，不建 GitHub 远程仓库的正式
  release 内容（本地 git repo 可以先建，远程仓库何时创建视用户确认节奏而定）。

---

# Routing Decision — P3 X App 更新修复（2026-08-27）

## ① 判档理由
**省额度档** → `codex -m gpt-5.6-luna`，推理强度 `high`。

- X App 12.17.0→12.19.1 升级导致 hook 点混淆产物类名重新分配（`k0`→`n0`，
  `com.x.performance.i`→`com.x.performance.g`），orchestrator 已经用 baksmali
  在真机现装的新版 APK 上逐字段核实完毕，证据精确到"改哪个常量数组的第几个
  元素、改成什么值"，不存在需要 Codex 自己判断/设计的空间。
- 属于 ROUTING 四档表里"明确琐碎小改"的典型场景：改动面窄（一个文件、两处
  字符串常量）、无架构决策、无新增依赖、有确定性证据支撑，用 sol·xhigh 反而
  是浪费额度。
- 降档不降验收标准：这是全项目唯一的真正过滤 hook，Verifier 仍要求真机四场景
  （主时间线/搜索/通知/评论区）用受控关键词完整回归，而不是只看构建通过。

## ② 研究外包决策
**不外包** —— 定位新类名需要读本地 adb 拉取的 APK + baksmali 反汇编产物，
这类"本地二进制/smali 逐字节核实"不是 github-solution-research（找现成方案/
issue）或 orch-research.sh（广义 web 调研）能覆盖的任务形态，且已经由
orchestrator 直接用真机日志错误信息 + baksmali 定位完成，不需要再起一轮调研。

## ③ 安全与边界
- 真机操作（拉取新版 APK、后续验证四场景）仍由 orchestrator 直接执行，不进
  Codex 沙箱，遵循 MOBILE_ROUTING.md 硬约束。
- 新版 APK（`orchestra/evidence/x-new-*.apk`）、解包 dex、baksmali 产物体积大，
  不进 git，只把关键证据摘录进任务书和 `probe-module-notes.md`。
- Codex 本轮任务边界严格限定为字符串常量替换，任务书已明确要求不改动
  `FilteredImmutableListHandler`/Proxy 逻辑、不加"双类名兼容"之类的过度设计。
