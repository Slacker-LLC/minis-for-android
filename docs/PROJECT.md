# 项目说明（Minis for Android）

> 本文件说明**这个项目是什么、能做什么、怎么构建与验证**。产品行为边界以 `docs/contracts/` 为准；
> 逐片开发记录见 `docs/development/PROGRESS.md`；本地化规则见 `docs/I18N.md`；文档索引见 `docs/README.md`。
>
> 快照日期：2026-09-20。文中的数字来自当时的源码与构建产物，改动后以复跑结果为准。

## 这是什么

**Minis for Android** 是面向**已 Root Android 设备**的 AI Agent Runtime，独立产品，组织 Slacker-LLC。
它把完整的 Agent 跑在设备本地：会话与计划、工具调用、Provider 接入、MCP、技能、长期记忆、语音、
定时任务、角色扮演与桌面宠物；需要 Linux 用户态时进入 App 自有的 **Ubuntu 24.04 chroot**
（与 Android 共用内核，不是虚拟机）。

产品没有自建服务端：模型请求走用户自己配置的 Provider（官方或兼容端点），会话、记忆、文件与技能
都存在设备本地，由 App 自己持有真源；Root 只用于建立受控的 Linux 运行环境与系统级能力。

```text
Android app（:app）
├─ Agent 循环 / 工具 / 子代理 / 会话（Room）/ 计划与检查点
├─ Provider（Chat Completions / Responses / Anthropic / Gemini / 各家兼容）
├─ MCP 客户端 + 设备内 MCP 服务、Skills、长期记忆、定时任务
├─ 无障碍 GUI 控制、Shizuku offload、结构化 root.shell、内置浏览器
├─ 语音（VoiceInteractionService / 识别与合成 / 通话）、角色扮演、桌面宠物
├─ Xposed / LSPosed 系统增强（无障碍保活、电源键接管、厂商记忆等）
└─ ExecutionCoordinator → UbuntuKernel / DirectRootRunner
   → su → setsid → unshare -m → bind mounts → chroot
   → setpriv(real App UID/GID, clear groups/caps) → Ubuntu 24.04 bash
```

## 产品身份

| 项 | 值 |
|---|---|
| 组织 / 域名 | Slacker-LLC / `slacker.llc` |
| 仓库 / 主线 | `Slacker-LLC/minis-eta` / `main` |
| `applicationId` | `llc.slacker.eta` |
| Android/Kotlin namespace | `com.openminis.app` |
| 版本 | `1.01-beta.2`（versionCode 39） |
| 平台 | Android 8.0+（`minSdk 26`）、`targetSdk 35`、`compileSdk 36` |
| ABI | `arm64-v8a`、`x86_64` |
| 许可 | 主体 GPL-3.0；第三方与移植模块的归属见 `PROVENANCE.md` / `THIRD_PARTY_LICENSES.md` |

`applicationId` 与代码 namespace 刻意不同：安装身份属于本产品，Kotlin 包根沿用共享的 `com.openminis.app`，
不做全库重命名。

## 能力面（当前源码快照）

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
**2432 例 0 失败**），默认语言资源 **2297** 条字符串。

## 架构与运行时边界

- App 持有会话、工具、Provider 与数据的真源；guest 用户数据从 `Context.filesDir` 派生；
- `/data/adb/minis/rootfs` 是 Root-owned、可替换的 runtime state，不是用户数据；
- `DirectRootRunner` 只执行 App 构造的受控基础设施动作（rootfs、namespace、bind、chroot、受控迁移）；
- 本地 Agent 另有结构化 `root.shell`（`tool` basename + `args`）：local-only、有界、MCP 不可见，
  不接受 raw command、宿主文件 API 或通用 RPC；
- 普通 guest 命令以设备真实 App UID/GID 运行，清空 supplementary groups 与 Linux capabilities；
- `127.0.0.1:18787` 的 HTTP/CONNECT helper 是独立的网络兼容组件，代理协议本身不依赖 Root；
- 敏感工具的原始参数与结果不进入持久 transcript、运行检查点或上下文快照，按工具分类脱敏。

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

当前基线（2026-09-20，Debug）：**2432 例 0 失败**；`:app:lintDebug` **0 error**（186 warning / 8 hint 为既有）；
`app-debug.apk` 约 **114 MB**（含 runtime payload）。

构建需要 `src/android/app/libs/rclone.aar`（被 gitignore）：缺失时从任一既有构建树复制，**不要提交**；
缺 `dist/`（runtime payload 源）同样会让打包内容不完整。

## 本地化

界面文案全部走资源，共 **8 个语言目录**：`values`（英文默认）、`values-zh`、`values-zh-rTW`、`values-ja`、
`values-ko`、`values-de`、`values-fr`、`values-ru`，当前条目 2297。新增文案必须 8 语言同步；品牌与协议名
（Minis、Shizuku、MCP、HTTP、JSON、Token 等）与 slash 命令 token 不翻译。规则、审计工具与覆盖情况见
`docs/I18N.md`（`python3 scripts/audit_strings.py`）。

## 文档地图

| 想看什么 | 去哪里 |
|---|---|
| 产品入口与构建 | `README.md` / `README.zh-CN.md` / `BUILDING.md` |
| 文档索引 | `docs/README.md` |
| 长期行为边界 | `docs/contracts/`（00 身份 → 05 工程 → 06 当前缺口） |
| 开发记录（怎么做的、做到哪了） | `docs/development/PORTING.md` / `PROGRESS.md` / `HANDOFF-*.md` |
| 本地化规则与覆盖 | `docs/I18N.md` |
| 参考资料索引 | `docs/REFERENCES.md` |
| 上游对照分析（历史） | `docs/analysis/` |
| 设备实测与工程状态 | `docs/REAL-DEVICE-TEST-REPORT.md` / `docs/DEVELOPMENT-STATUS.md` |
| 法律来源 | `PROVENANCE.md` / `THIRD_PARTY_LICENSES.md` |

## 真机验证边界

仓库内的宿主构建、单测与脚本结论**不能替代设备验证**。Root、SELinux、OEM 生命周期、LSPosed、
VPN/DNS/BPF、无障碍窗口集合等结论只有真机实测后才能声称通过；未验证项集中在
`docs/development/PROGRESS.md` 的「未验证清单」，当前缺口见 `docs/contracts/06-CURRENT-GAPS.md`。

## 许可与归属

产品主体按 **GPL-3.0** 分发；第三方组件与移植模块的许可与归属（含随附条款与 Required Notice）
集中在 `PROVENANCE.md`、`THIRD_PARTY_LICENSES.md` 与 `third_party/` 下的许可文件，代码文件内不逐文件写
许可头。
