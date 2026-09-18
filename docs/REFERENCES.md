# 资料索引（minis-eta）

本文件是**参考资料**索引：源码位置、上游链接、许可文本、工具链版本与关键源码速查。
仓库自身的文档索引见 `docs/README.md`。

## 本地资料

| 内容 | 路径 |
|---|---|
| Eta 参考源码（只读） | `/tmp/eta-upstream-clone`（`Mangi-11/Eta@ c15de97`） |
| 原 Minis 仓库工作树 | `/home/jiale/projects/minis-for-android`（其它工作树见 `git worktree list`） |
| 本项目主工作树 | 应用项目「minis eta」的本地路径（本项目目录） |
| 构建依赖（gitignored） | `src/android/app/libs/rclone.aar`，来源见 `docs/PROJECT.md` |

> `/tmp` 下的参考克隆在重启后可能消失；需要时 `git clone https://github.com/Mangi-11/Eta` 并
> `git checkout c15de97` 重建。

## 上游与远端

| 项目 | 地址 |
|---|---|
| 本仓库 | `https://github.com/Slacker-LLC/minis-eta`（私有） |
| Minis for Android | `https://github.com/Slacker-LLC/minis-for-android`（GPL-3.0） |
| Eta | `https://github.com/Mangi-11/Eta`（PolyForm Noncommercial 1.0.0） |
| Eta 历史 PR | `Slacker-LLC/minis-for-android#237`（模块化，已回退）、`#236`（Direct Ubuntu，已合并） |

> **第三条血统：DeepSeek Harness（`dsh-*` 契约）** —— 代码里有一批文件在头注释里写着
> 「Port of the DeepSeek Harness `dsh-…` contract」，例如 `tools/internal/SpillPolicy.kt`
> （`dsh-spill-policy`）、`ToolResultPruner.kt`（`dsh-compaction-tool-result-pruner`）、
> `OutputRetainer.kt`（`dsh-output-retention`）、`ContextPressure.kt`/`TokenMeter.kt`
> （`dsh-token-meter`）、`ToolCheckpointStore.kt`（`dsh-session-checkpoint-policy`）、
> `JobRegistry.kt`/`JobTools.kt`（`dsh-tool-jobs`）等。这些字样**随 Minis for Android
> 导入一起进来**（提交 `b8a0d2a5`，同样的文本也存在于 `Slacker-LLC/minis-for-android`
> 工作树），**与 Eta 无关**；本索引此前没有为它立条目，读文档时不要把 `dsh-*` 当成 Eta 的东西。
> 上游链接与许可归属尚未在此登记（Eta 的许可与归属不受影响）。

## 内部分析资料

| 文件 | 用途 |
|---|---|
| `docs/analysis/eta-port-program.md` | 本项目路线图（阶段、来源、验收） |
| `docs/analysis/eta-integration-plan.md` | 已落地改动清单与验证口径 |
| `docs/analysis/eta-agent-runtime.md` | Agent 循环、工具合同、上下文、恢复（C1–C6 规格） |
| `docs/analysis/eta-skills-mcp-backup.md` | Skills 安装事务、MCP 协议、备份恢复规格 |
| `docs/analysis/eta-ui-gui-root.md` | 流式 UI、权限健康、GUI 动作、Root 语义、窗口一致性规格 |

## Eta 关键源码速查（@ c15de97）

| 领域 | 路径 |
|---|---|
| Agent 循环与模型层 | `app/src/main/kotlin/io/github/mangi/eta/agent/model/` |
| 运行检查点与恢复 | `…/agent/runtime/AgentRunCheckpointStore.kt`、`AgentRunCheckpointRecorder.kt`、`ui/app/AgentRunRecoveryCoordinator.kt` |
| 技能事务 | `…/agent/skill/SkillPackageInstaller.kt`、`SkillRecoveryJournal.kt`、`SkillMutationLock.kt`、`SkillRuntime.kt` |
| GUI、无障碍、设备 | `…/agent/accessibility/*`、`…/agent/device/*` |
| 个人上下文工具 | `…/agent/tool/AgentPersonalContextTools.kt`、`AgentPersonalDataTools.kt`、`AgentPrivateDatabaseTools.kt` |
| 角色系统 | `…/agent/roleplay/*`（角色卡、世界书、宏、角色记忆） |
| 助手入口与浮层 | `…/agent/voice/*`、`…/agent/overlay/*` |
| Hook 与厂商入口 | `…/hook/*`（system、hyperos、xiaoai、breeno、google、aimemory、colordirect）、`…/ModuleMain.kt` |
| UI 可读性做法 | `…/ui/components/ChatMessageItem.kt`（AgentWorkProcess）、`SmoothTextReveal.kt`、`ToolChip.kt` |

## 许可文本

| 内容 | 位置 |
|---|---|
| 本仓库许可（GPL-3.0） | `LICENSE` |
| 第三方与移植清单 | `THIRD_PARTY_LICENSES.md`（含 ported-modules 表） |
| 法律来源与 Required Notice | `PROVENANCE.md` |
| Eta 许可原文 | `third_party/eta/LICENSE` |

## 工具链与版本（会漂移，以文件为准）

| 项 | 值 | 来源 |
|---|---|---|
| Gradle wrapper | 8.11.1 | `src/android/gradle/wrapper/gradle-wrapper.properties` |
| compileSdk / targetSdk / minSdk | 36 / 35 / 26 | `src/android/app/build.gradle.kts` |
| applicationId | `llc.slacker.eta` | 同上 |
| Kotlin / AGP | 见 `src/android/gradle/libs.versions.toml` | —— |
| 单测基线 | 1786 个用例（`--no-daemon --max-workers=1`） | 本仓库当前 main |
