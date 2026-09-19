# 项目说明（minis-eta）

> 本文件说明**这个仓库现在是什么**。产品行为边界以 `docs/contracts/` 为准；移植规范见
> `docs/development/PORTING.md`，逐片进度见 `docs/development/PROGRESS.md`，本轮交接快照见
> `docs/development/HANDOFF-2026-09-19.md`，本地化规则见 `docs/I18N.md`。
>
> 快照日期：2026-09-20。文中的数字来自当时分支源码与构建产物，改动后请以复跑结果为准。

## 这是什么

**Minis for Android** 与 **Eta 能力移植线**合并后的仓库，产品是面向**已 Root Android 设备**的
AI Agent Runtime：原生 Android App 负责 Agent 循环、会话、工具、Provider、MCP、技能与数据真源，
需要 Linux 命令时进入 App 自有的 **Ubuntu 24.04 direct chroot**（与 Android 共用内核，不是虚拟机）。

当前工作线是 **`codex/eta-phase6-xposed`**（远端 `Slacker-LLC/minis-eta`）。`main` 保留移植开始前的
种子线；能力移植按阶段分支交付（见「来源与谱系」），当前线是它们的累计与后续收敛结果。

```text
Android app（:app，applicationId llc.slacker.eta）
├─ Agent 循环 / 工具 / 子代理 / 会话（Room）/ 计划与检查点
├─ Provider（Chat Completions / Responses / Anthropic / Gemini / 各家兼容）
├─ MCP 客户端 + 设备内 MCP 服务、Skills、长期记忆、定时任务
├─ 无障碍 GUI 控制、Shizuku offload、结构化 root.shell、浏览器（WebView + 动作执行）
├─ 语音（VoiceInteractionService / 识别与合成 / 通话）、宠物助手、角色扮演（角色卡 + 世界书）
├─ Xposed / LSPosed 系统增强（无障碍保活、电源键接管、厂商记忆等）
└─ ExecutionCoordinator → UbuntuKernel / DirectRootRunner
   → su → setsid → unshare -m → bind mounts → chroot
   → setpriv(real App UID/GID, clear groups/caps) → Ubuntu 24.04 bash
```

## 代码与功能面（当前源码快照）

| 包（`src/android/app/src/main/java/com/openminis/app/`） | Kotlin 文件 | 职责 |
|---|---|---|
| `ui/` | 198 | Compose 界面与设计系统（聊天、设置、沙箱、会话、媒体、Markdown） |
| `tools/` | 115 | Agent 工具与工具运行时（含 `android-*` CLI 桥） |
| `data/` | 87 | Room、仓库层、模型与配置真源 |
| `runtime/` | 52 | ExecutionCoordinator、Ubuntu direct chroot、guest CLI offload |
| `xposed/` | 43 | 系统增强模块（无障碍保活、电源键、厂商记忆等） |
| `provider/` | 38 | 各 Provider 协议、推理参数与线格式 |
| `speech/` | 27 | 语音识别、纠错、语言包 |
| `mcp/` | 17 | MCP 客户端与设备内服务端 |
| `agent/` | 17 | Agent 循环、检查点、上下文预算 |
| `roleplay/` | 16 | 角色卡解析、世界书、会话绑定 |
| `browser/` | 15 | 内置浏览器、标签池、动作执行 |
| `pet/` | 13 | 桌面宠物（浮窗、小窗对话、语音） |
| 其余 | — | `backup/`、`scheduled/`、`notification(s)/`、`accessibility/`、`offload/`、`remote/`、`webapp/`、`terminal/`、`voicecall/` 等 |

合计 `src/android/` 主源码 **860** 个 Kotlin 文件、**348** 个测试文件（`:app:testDebugUnitTest` 当前
**2432 例 0 失败**），默认语言资源 **2297** 条字符串（8 个语言目录，见 `docs/I18N.md`）。

## 来源与谱系

| 内容 | 来源 |
|---|---|
| 仓库种子 | `Slacker-LLC/minis-for-android` @ `69e05e51`（GPL-3.0 主体） |
| Eta 参考源码 | `Mangi-11/Eta` @ `c15de97`（PolyForm Noncommercial 1.0.0） |
| 本仓库远端 | `Slacker-LLC/minis-eta` |
| 法律归属 | `PROVENANCE.md`、`THIRD_PARTY_LICENSES.md`、`third_party/eta/LICENSE` |

能力移植按阶段分支交付，全部从 `main` 出发：

| 分支 | 主题 | `main..分支` 提交数 |
|---|---|---|
| `codex/eta-phase1-ui` | 工作过程行、助手首页、流式投影 | 5 |
| `codex/eta-phase2-provider-passthrough` | Provider 透传与推理参数 | 18 |
| `codex/eta-phase3-skills-tools` | Skills 事务、工具与 MCP | 30 |
| `codex/eta-phase4-notifications` | 通知与后台 | 42 |
| `codex/eta-phase5-roleplay` | 角色卡与世界书 | 66 |
| `codex/system-prompt-modules` | 系统提示词模块化 | 8 |
| **`codex/eta-phase6-xposed`（当前线）** | 阶段内容累计落地 + Xposed 系统增强 + 收敛、MCP 实测、本地化 | **221** |

阶段分支之间不互为祖先；当前线在自己的提交历史里重新落地了 phase 1–5 的内容，再叠加 phase 6 与
后续收敛（HEAD 与远端一致，2026-09-20 为 `f58adf39`）。

## 许可

- 仓库主体按 **GPL-3.0** 分发，不变更既有授权。
- 移植自 Eta 的模块按 **PolyForm Noncommercial 1.0.0** 使用与分发（仅限非商业用途，须随附许可条款
  与 Required Notice）；这部分不纳入本仓库的 GPL-3.0 授权范围。
- 归属与逐项清单集中在 `PROVENANCE.md`、`THIRD_PARTY_LICENSES.md` 与 `third_party/eta/LICENSE`；
  **代码文件内不逐文件写许可头**。

## 仓库结构

| 路径 | 内容 |
|---|---|
| `src/android/` | Android 应用（`applicationId=llc.slacker.eta`，namespace `com.openminis.app`） |
| `src/native/` | Root 原生组件（root network proxy） |
| `src/shared/` | 跨环境辅助资产（bashism 等） |
| `docs/contracts/` | 长期行为边界（中文合同，先读） |
| `docs/analysis/` | Eta 对照分析与移植阶段计划 |
| `docs/development/` | 移植规范、进度与交接快照 |
| `docs/specs/` | 单模块契约（URL 方案、调试接口、MCP 工具列表、备份可行性） |
| `scripts/` | 构建、校验、payload、文档守卫与字符串审计脚本 |

## 构建与验证

```bash
# 文档守卫（改动文档后必跑）
python3 scripts/test_docs_provenance.py && python3 scripts/check_docs_provenance.py

# Android：全机器串行，同一时刻只允许一个 Gradle
cd src/android
flock /tmp/minis-gradle.lock ./gradlew :app:testDebugUnitTest --no-daemon --max-workers=1
flock /tmp/minis-gradle.lock ./gradlew :app:lintDebug :app:assembleDebug --no-daemon --max-workers=1

# 产物校验（runtime payload / 16 KB page）
bash scripts/verify-runtime-payload.sh  src/android/app/build/outputs/apk/debug/app-debug.apk
bash scripts/verify-android-16k.sh      src/android/app/build/outputs/apk/debug/app-debug.apk
```

当前基线（2026-09-20，Debug）：**2432 例 0 失败**；`:app:lintDebug` **0 error**（186 warning / 8 hint
为既有）；`app-debug.apk` 约 **114 MB**（含 runtime payload）。仪器化测试与真机记录见
`docs/REAL-DEVICE-TEST-REPORT.md`。

构建参数：Gradle wrapper 8.11.1；`compileSdk 36 / targetSdk 35 / minSdk 26`；ABI `arm64-v8a`、
`x86_64`；`versionName 1.01-beta.2`。

构建需要 `src/android/app/libs/rclone.aar`（被 gitignore）：缺失时从任一既有构建树复制（例如本机
另一个 worktree 的 `src/android/app/libs/rclone.aar`），**不要提交**；缺 `dist/`（runtime payload 源）
同样会导致打包内容不完整。

## 本地化

界面文案全部走资源，共 **8 个语言目录**：`values`（英文默认）、`values-zh`、`values-zh-rTW`、
`values-ja`、`values-ko`、`values-de`、`values-fr`、`values-ru`。新增文案必须 8 语言同步；品牌与协议名
（Minis、Shizuku、MCP、HTTP、JSON、Token 等）与 slash 命令 token 不翻译。规则、审计工具与当前覆盖
见 `docs/I18N.md`（`python3 scripts/audit_strings.py`）。

## 安全与行为边界（摘要，细节以合同为准）

- 产品 runtime 是 Android App 自有的 **Ubuntu 24.04 direct chroot**；不恢复旧的双栈兼容层。
- Root 只执行 App 构造的受控基础设施动作；本地 Agent 的 Root 能力走结构化 `root.shell`
  （tool + args，local-only、有界、MCP 不可见），不接受 raw command 或通用 Root RPC。
- 敏感工具的原始参数与结果不进入持久 transcript、运行检查点或上下文快照，已按工具分类脱敏。
- 数据/存储/网络/密钥边界见 `docs/contracts/03-STORAGE-CONTRACT.md` 与 `04-SECURITY-CONTRACT.md`。

## 文档地图

| 想看什么 | 去哪里 |
|---|---|
| 产品入口与构建 | `README.md` / `README.zh-CN.md` / `BUILDING.md` |
| 文档索引 | `docs/README.md` |
| 长期行为边界 | `docs/contracts/`（00 身份 → 05 工程 → 06 当前缺口） |
| 移植规范（怎么移植） | `docs/development/PORTING.md` |
| 移植进度（做到哪了） | `docs/development/PROGRESS.md` |
| 交接快照（某一天的状态） | `docs/development/HANDOFF-2026-09-19.md` |
| 本地化规则与覆盖 | `docs/I18N.md` |
| 资料与参考资料索引 | `docs/REFERENCES.md` |
| Eta 对照分析与阶段计划 | `docs/analysis/` |
| 设备实测与工程状态 | `docs/REAL-DEVICE-TEST-REPORT.md` / `docs/DEVELOPMENT-STATUS.md` |
| 法律来源 | `PROVENANCE.md` / `THIRD_PARTY_LICENSES.md` |

## 真机验证边界

仓库内的宿主构建、单测与脚本结论**不能替代设备验证**。Root、SELinux、OEM 生命周期、LSPosed、
VPN/DNS/BPF、无障碍窗口集合等结论只有真机实测后才能声称通过；未验证项集中在
`docs/development/PROGRESS.md` 的「未验证清单」，当前缺口见 `docs/contracts/06-CURRENT-GAPS.md`。
