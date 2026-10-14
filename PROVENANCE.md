# Source Provenance

法律与来源记录。不是产品说明，不是架构合同，也不是持续同步政策。

## Lineage

Minis for Android 含有从开源项目 **OpenMinis** 衍生的代码：

- repository: https://github.com/OpenMinis/OpenMinis
- identifier: `OpenMinis/OpenMinis`
- license at derivation: GPL-3.0
- website: https://openminis.app

本仓库由 Slacker-LLC 独立维护。产品行为以本仓库源码、测试与中文合同为准。

**没有**把 OpenMinis 变更持续同步进本仓库的政策。若再从任何外部项目导入代码，该变更必须标明来源、保留声明、满足许可证，并适配当前架构。

## Selective imports from OpenMinis

本仓库按需、手工地从 OpenMinis 引入个别改动，并适配当前架构；不是持续同步。每项都记录来源 tag，代码保留原有版权头。

- OpenMinis `1.14`（tag `1.14`，`b4c0661`，GPL-3.0）
  - 长按用户消息的「压缩以上内容」入口与确认框（`[T-android-compact-above]`）；`compactBefore` 本仓库早已具备，只引入入口。
  - 回复 Token 用量的读取与格式（`ChatTokenUsage`、`[T-android-usage-capsule-time]`）；展示位置改为回复长按菜单底部，不引入其「点空白处」的手势与聊天列表渲染改动。
  - 子代理（`SubAgentDefinition` / `SubAgentRoster` 的规范化规则、`subagent_task` 工具合同与文案、回调文案，`[T-sub-agents-v1]`）：花名册规则、工具 schema 与回调文案取自上游；运行时与注册表按本仓库的 `AgentRunner` 重写，模型绑定由「模型组」改为模型条目，工具沿用本仓库既有名称 `subagent`。差异记录在 `docs/development/SUB-AGENTS.md`。
  - 工具调用与结果的配对（`ToolPairing`、`ResponsesToolPairing`，`[T-android-responses-tool-id-normalize]`、`[T-android-responses-orphan-tool-output]`）：两个文件原样取自上游；`OutgoingHistory.dropOrphanedToolParts` 与 `ToolBatchRepair` 按上游 `ChatViewModel` 的同名逻辑改为按 `ToolPairing.key` 比较，Responses 请求体在写入 `input` 前经过 `ResponsesToolPairing.sanitize`。测试按本仓库的结构改写。
  - Claude 登录（`[T-android-oauth-foreground-exchange]`、OpenMinis#360）：`ClaudeCliMimicryHeaders`（Claude CLI 客户端指纹，OAuth 对话与 token 请求共用）、`OAuthForegroundGate`（前台才交换授权码）、token 端点 `claude.ai`、对话流中途 `error` 帧、Claude 5 的 `thinking` 关闭编码与版本解析修正；登录页的进度与失败提示取自上游登录界面。
- Termux `8629e632`（<https://github.com/termux/termux-app>，仅 `terminal-emulator` / `terminal-view` 两个库，Apache-2.0）
  - 终端字符宽度表（`WcWidth.kt`，表来自 `WcWidth.java`，查找逻辑用 Kotlin 重写）；窗口大小变化时保住光标行、软换行拼接复制、列数变化重排等行为参照其 `TerminalBuffer.java` 在本仓库重新实现。许可文本见 `third_party/termux/LICENSE`。
- CLIProxyAPI `3de4e248`（<https://github.com/router-for-me/CLIProxyAPI>，MIT）
  - Antigravity 登录与请求格式的参照：OAuth 客户端 ID/密钥与 scope、`loadCodeAssist`/`onboardUser` 的 `ANTIGRAVITY` 元数据、Cloud Code 请求信封与各模型族规则、客户端版本下限（`AntigravityWire`、`AntigravityOAuthManager`）。常量为 Antigravity IDE 公开发布的值。

## Referenced projects

以下外部项目为本仓库提供行为参考，并且**其部分代码已被移植进来**：

- Eta — repository: https://github.com/Mangi-11/Eta
  - license: PolyForm Noncommercial License 1.0.0（非商业许可）
  - license text: [third_party/eta/LICENSE](third_party/eta/LICENSE)
    （含该许可要求的 `Required Notice: Copyright © 2026 蛮吉 (Mangi-11).`）
  - 移植来源 commit：`c15de97`。作者保留著作权；这些文件按 PolyForm Noncommercial 1.0.0
    使用与分发，不代表作者对本仓库其他部分作出 GPL 授权。
  - 移植范围与逐项清单见 `THIRD_PARTY_LICENSES.md`；同一处集中声明，代码文件内不重复写许可头。
  - 使用与分发必须满足该许可：仅限非商业用途，且随附上述许可条款与 Required Notice。
  - 逐项对照见 `docs/analysis/`。

- ShadowAuto — repository: https://github.com/android-notes/ShadowAuto
  - pinned source commit: `611e5eb0e1c94befda3c17c97438912dfd30e8a8`
  - license at derivation: Apache-2.0; license text: [third_party/shadowauto/LICENSE](third_party/shadowauto/LICENSE)
  - Adapted files are listed in `THIRD_PARTY_LICENSES.md`. The pinned upstream tree has no separate `NOTICE`
    file and the migrated source files have no file-level copyright headers; each adapted file records its
    original path, pinned commit, Apache-2.0 notice, and the fact that it was modified. No copyright owner
    has been inferred from the Git commit author.
  - The port is limited to the privileged-process support for a virtual display (first hosted in a Shizuku shell
    UserService, since 2026-10 in a libsu root service), display-targeted UI/input,
    IME policy, clipboard bridge, and bounded screenshots; upstream Main/automation/socket/video/OCR/root paths
    are not imported.

## Obligations

继续按 GPL-3.0 分发。架构或包名变化不消除原著作权与许可证义务。再分发修改后的二进制时，按 GPL-3.0 提供对应源码并保留版权与许可声明。

不要删除衍生源文件中的版权头。

See [LICENSE](LICENSE), [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md), [CONTRIBUTORS.md](CONTRIBUTORS.md).

历史执行模型（已废弃）见 [docs/archive/RUNTIME-HISTORY.md](docs/archive/RUNTIME-HISTORY.md)。现行合同见 [docs/contracts/](docs/contracts/00-IDENTITY.md)。

## Design-only reference: OpenBot

- repository: https://github.com/nightly-labs/openbot
- inspected commit: `a5c182367b24f34cd8334bbeeaac3e45d86420b0` (2026-10-12)
- repository license: PolyForm Noncommercial License 1.0.0; required notice: Copyright 2026 Norbert Bodziony.
- inspected areas: persistent mailbox/drain scheduling, task owner and execution threads, queue/steer controls, voice transcription, agent identity.
- 本轮仅用于设计对照，未将 OpenBot 源码或资源并入产品；实际语音/Bot 改动复用本仓库已有 Kotlin 实现。未来直接源码移植须逐项确认适用许可并另列文件/代码范围，不能将其视为已取得 GPL 授权。
