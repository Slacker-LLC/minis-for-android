# 项目说明（minis-eta）

> 本文件说明**这个仓库是什么**。产品行为边界仍以 `docs/contracts/` 为准，移植顺序见
> `docs/analysis/eta-port-program.md`，进度见 `docs/development/PROGRESS.md`。

## 这是什么

本仓库是 **Minis for Android**（面向已 Root Android 设备的 AI Agent Runtime）与 **Eta**
（Mangi-11/Eta，面向 Android 的系统级 AI 助手）能力合并后的新起点。目标是在保留 Minis 现有
架构与安全边界的前提下，把 Eta 的独有能力（对话可读性、系统助手体验、个人上下文检索、角色系统、
厂商入口接管等）按差集移植进来。

```text
Android app（:app）
├─ Agent 循环 / 工具 / 子代理 / 会话（Room）
├─ Provider（Chat Completions / Responses / Anthropic / 各家兼容）
├─ MCP 客户端 + 服务端、Skills、长期记忆
├─ 无障碍 GUI 控制、Shizuku offload、结构化 root.shell
├─ 语音助手角色（VoiceInteractionService）、宠物助手、通话自动化
└─ ExecutionCoordinator → UbuntuKernel / DirectRootRunner
   → su → setsid → unshare -m → bind mounts → chroot
   → setpriv(real App UID/GID, clear groups/caps) → Ubuntu 24.04 bash
```

## 来源与谱系

| 内容 | 来源 |
|---|---|
| 仓库种子 | `Slacker-LLC/minis-for-android` @ `69e05e51`（GPL-3.0 主体） |
| Eta 能力移植线 | 分支 `codex/eta-capability-integration` 的 24 个提交（tip `5b625f0d`） |
| Eta 参考源码 | `Mangi-11/Eta` @ `c15de97`（PolyForm Noncommercial 1.0.0） |
| 新仓库远端 | `Slacker-LLC/minis-eta`（私有；可改名/转公开） |

## 许可

- 仓库主体继续按 **GPL-3.0** 分发，不变更既有授权。
- 移植自 Eta 的模块按 **PolyForm Noncommercial 1.0.0** 使用与分发（仅限非商业用途，须随附许可条款
  与 Required Notice）；这部分不纳入本仓库的 GPL-3.0 授权范围。
- 归属与逐项清单集中在 `PROVENANCE.md`、`THIRD_PARTY_LICENSES.md` 与 `third_party/eta/LICENSE`；
  **代码文件内不逐文件写许可头**。

## 仓库结构

| 路径 | 内容 |
|---|---|
| `src/android/` | Android 应用（`applicationId=llc.slacker.minis`，namespace `com.openminis.app`） |
| `src/native/` | Root 原生组件（root network proxy） |
| `src/shared/` | 跨环境辅助资产（bashism 等） |
| `docs/contracts/` | 长期行为边界（中文合同，先读） |
| `docs/analysis/` | Eta 对照分析与移植计划 |
| `docs/development/` | 开发移植规范与进度 |
| `scripts/` | 构建、校验、payload 脚本 |

## 构建与验证

```bash
# 文档一致性
python3 scripts/test_docs_provenance.py && python3 scripts/check_docs_provenance.py

# Android（全机器串行：同一时刻只允许一个 Gradle）
cd src/android
flock /tmp/minis-gradle.lock ./gradlew :app:compileDebugKotlin --no-daemon --max-workers=1
flock /tmp/minis-gradle.lock ./gradlew :app:testDebugUnitTest --no-daemon --max-workers=1
flock /tmp/minis-gradle.lock ./gradlew :app:lintDebug :app:lintRelease --no-daemon --max-workers=1
```

- 构建需要 `src/android/app/libs/rclone.aar`（被 gitignore）。缺失时从
  `/home/jiale/projects/minis-for-android/src/android/app/libs/rclone.aar` 复制，**不要提交**。
- Gradle wrapper 8.11.1；`compileSdk 36 / targetSdk 35 / minSdk 26`。
- 完整验证矩阵见 `docs/contracts/05-ENGINEERING.md`。

## 安全与行为边界（摘要，细节以合同为准）

- 产品 runtime 是 Android App 自有的 **Ubuntu 24.04 direct chroot**；不恢复 PRoot/Alpine 双栈。
- Root 只执行 App 构造的受控基础设施动作；本地 Agent 的 Root 能力走结构化 `root.shell`
  （tool + args，local-only、有界、MCP 不可见），不接受 raw command 或通用 Root RPC。
- 敏感工具的原始参数与结果不进入持久 transcript、运行检查点或上下文快照，已按工具分类脱敏。
- 数据/存储/网络/密钥边界见 `docs/contracts/03-STORAGE-CONTRACT.md` 与 `04-SECURITY-CONTRACT.md`。

## 文档地图

| 想看什么 | 去哪里 |
|---|---|
| 产品入口与构建 | `README.md` / `README.zh-CN.md` / `BUILDING.md` |
| 长期行为边界 | `docs/contracts/`（00 身份 → 05 工程 → 06 当前缺口） |
| 移植规范（怎么移植） | `docs/development/PORTING.md` |
| 移植进度（做到哪了） | `docs/development/PROGRESS.md` |
| 资料与参考资料索引 | `docs/REFERENCES.md` |
| Eta 对照分析与阶段计划 | `docs/analysis/` |
| 法律来源 | `PROVENANCE.md` / `THIRD_PARTY_LICENSES.md` |

## 真机验证边界

仓库内的宿主构建、单测与脚本结论**不能替代设备验证**。Root、SELinux、OEM 生命周期、LSPosed、
VPN/DNS/BPF、无障碍窗口集合等结论只有真机实测后才能声称通过；未验证项集中在
`docs/development/PROGRESS.md` 的「未验证清单」。
