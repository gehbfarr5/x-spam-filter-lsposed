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
