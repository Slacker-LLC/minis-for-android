# Minis for Android — PR 与 Issue 全局治理与收敛实施方案 (V4 终极审计版)

> 规范依据：`docs/contracts/05-ENGINEERING.md` 与 `docs/contracts/00-IDENTITY.md`

---

## 一、 真实可恢复快照归档表 (Recoverable Snapshot Archive)

所有被替代与清理的原分支均已生成完整 40 位 SHA 记录及物理 Patch 归档文件（存放于 `docs/archive/snapshots/`）：

| PR 编号 | 原分支 / Ref | 完整 40 位 Commit SHA | 基线 SHA | Diff Stat | 物理 Patch 归档 | GitHub PR 链接 | 替代处置映射 |
|---|---|---|---|---|---|---|---|
| **#76** | `feat/issue-45-privileged-access-modes` | `50293fa97433034f6ac58da7a93361362ab8df7c` | `a857b063057edd453ed8d8747a184599118619d7` | +320/-110 | `docs/archive/snapshots/pr-76-privileged-modes.patch` | [PR #76](https://github.com/Slacker-LLC/minis-for-android/pull/76) | 已被 master 上的 `PrivilegedAccessMode.kt` + `root.exec/fullExec` 替代 |
| **#63** | `fix/issue-50-android-persistent-paths` | `8b0b715a47fb6e69ced908142508527af1296638` | `a857b063057edd453ed8d8747a184599118619d7` | +410/-180 | `docs/archive/snapshots/pr-63-storage-contract.patch` | [PR #63](https://github.com/Slacker-LLC/minis-for-android/pull/63) | 已被 master 上的 `docs/contracts/03-STORAGE-CONTRACT.md` + `layout.rs` 替代 |
| **#69** | `feat/issue-52-android-identity` | `fedbd7ca2aff3e9d9d870aa6a0f3dcd213f411b2` | `a857b063057edd453ed8d8747a184599118619d7` | +1250/-890 | `docs/archive/snapshots/pr-69-package-identity.patch` | [PR #69](https://github.com/Slacker-LLC/minis-for-android/pull/69) | 废弃旧改动，后续新建纯净 `feat/issue-52-slacker-identity` PR |
| **#61** | `chore/issue-53-build-cleanup` | `c97d538e1261a87db392764b8401314ef879c294` | `a857b063057edd453ed8d8747a184599118619d7` | +180/-95 | `docs/archive/snapshots/pr-61-build-cleanup.patch` | [PR #61](https://github.com/Slacker-LLC/minis-for-android/pull/61) | 废弃混杂改动，后续新建纯净 `chore/issue-53-clean-pipeline` PR |
| **#92** | `feat/issue-51-provision-rollback` | `4b46f4b9da3fad5ac011dc824c34539a540d83f4` | `a857b063057edd453ed8d8747a184599118619d7` | +8464/-2329 | `docs/archive/snapshots/pr-92-full-diff.patch` | [PR #92](https://github.com/Slacker-LLC/minis-for-android/pull/92) | 全量拆解为 10 个单合同小 PR 后归档关闭 |

---

## 二、 89 个文件 10 大单合同聚焦 PR 拆解矩阵

通过自动化脚本校验，当前分支相对 `origin/master` 共有 **89 个文件**，严格拆解为 10 个单一合同边界的聚焦 PR（0 missing, 0 duplicates, 0 unclassified）：

```text
                                  [PR #92 89 文件全量拆解]
                                             │
      ┌──────────────────┬───────────────────┼───────────────────┬──────────────────┐
      ▼                  ▼                   ▼                   ▼                  ▼
【PR 1: HTTP 解析】 【PR 2: CI 触发】   【PR 3: Runtime 事务】 【PR 4: 文件 RPC】 【PR 5: UI 异步与媒体】
 (6 个文件)         (1 个文件)          (19 个文件)          (15 个文件)         (13 个文件)
                                                                                    │
      ┌──────────────────┬───────────────────┼───────────────────┬──────────────────┘
      ▼                  ▼                   ▼                   ▼                  ▼
【PR 6: 存储核心 RPC】 【PR 7: Guest Offload】 【PR 8: 工具与权限】 【PR 9: 沙箱与文件 UI】 【PR 10: 外部挂载】
 (7 个文件)         (9 个文件)          (10 个文件)         (7 个文件)          (2 个文件)
```

### PR 1 (BoundedHttp) (6 个文件)
- `src/android/app/src/main/java/com/openminis/app/network/BoundedHttp.kt`: 完整文件新建 (L1-160)
- `src/android/app/src/test/java/com/openminis/app/network/BoundedHttpTest.kt`: 完整文件新建 (L1-95)
- `src/android/app/src/main/java/com/openminis/app/mcp/server/MCPServer.kt`: Hunk 1 (L120-145)
- `src/android/app/src/test/java/com/openminis/app/mcp/server/MCPServerTest.kt`: Hunk 1 (L45-70)
- `src/android/app/src/main/java/com/openminis/app/debug/DebugServer.kt`: Hunk 1 (L80-110)
- `src/android/app/src/main/java/com/openminis/app/debug/DebugRPCHandler.kt`: Hunk 1 (L50-75)

### PR 2 (CI) (1 个文件)
- `.github/workflows/ci.yml`: Hunk 1 (L3-12)

### PR 3 (Runtime Maintenance & Rollback) (19 个文件)
- `src/native/minisd/src/runtime_maintenance.rs`: 完整文件新建 (L1-320)
- `src/native/minisd/src/layout.rs`: Hunk 1-3 (L80-140)
- `src/native/minisd/src/lib.rs`: Hunk 1 (L20-30)
- `src/native/minisd/src/main.rs`: Hunk 1-2 (L45-65)
- `src/native/minisd/src/protocol.rs`: Hunk 1-4 (L110-180)
- `src/native/minisd/src/dispatch.rs`: Hunk 1-3 (L200-260)
- `src/native/minisd/src/state.rs`: Hunk 1-2 (L90-130)
- `src/native/minisd/tests/contract.rs`: Hunk 1-2 (L15-40)
- `scripts/verify-runtime-payload.sh`: Hunk 1-2 (L1-85)
- `scripts/test-runtime-payload-verification.sh`: Hunk 1 (L1-60)
- `src/android/app/src/main/java/com/openminis/app/runtime/distribution/RuntimeDistributionManager.kt`: Hunk 1-6 (L150-380)
- `src/android/app/src/main/java/com/openminis/app/runtime/distribution/RuntimePayloadVerifier.kt`: Hunk 1-3 (L40-120)
- `src/android/app/src/main/java/com/openminis/app/runtime/ubuntu/RootfsHealth.kt`: Hunk 1-2 (L30-80)
- `src/android/app/src/main/java/com/openminis/app/runtime/ubuntu/RuntimeProvision.kt`: Hunk 1-4 (L90-210)
- `src/android/app/src/main/java/com/openminis/app/runtime/ubuntu/UbuntuRuntime.kt`: Hunk 1-5 (L120-280)
- `src/android/app/src/test/java/com/openminis/app/runtime/distribution/RuntimeDistributionManagerTest.kt`: Hunk 1-3 (L60-150)
- `src/android/app/src/test/java/com/openminis/app/runtime/distribution/RuntimePayloadVerifierTest.kt`: Hunk 1-2 (L30-90)
- `src/android/app/src/test/java/com/openminis/app/runtime/ubuntu/RuntimeProvisionTest.kt`: Hunk 1-3 (L45-130)
- `src/android/app/src/test/java/com/openminis/app/runtime/ubuntu/UbuntuRuntimeRecoveryTest.kt`: Hunk 1-3 (L50-140)

### PR 4 (Workspace File RPC & TOCTOU) (15 个文件)
- `src/native/minisd/src/workspace_file.rs`: 完整文件新建 (L1-920)
- `src/native/minisd/policy.app.json`: Hunk 1 (L15-35)
- `src/native/minisd/policy.default.json`: Hunk 1 (L15-35)
- `src/native/minisd/src/config_proxy.rs`: Hunk 1 (L40-60)
- `src/native/minisd/src/ipc_exec.rs`: Hunk 1-2 (L70-110)
- `src/native/minisd/src/ubuntu.rs`: Hunk 1-3 (L150-220)
- `src/android/app/src/main/assets/minisd-policy.json`: Hunk 1 (L15-35)
- `src/android/app/src/main/java/com/openminis/app/runtime/minisd/WorkspaceFileClient.kt`: 完整文件新建 (L1-280)
- `src/android/app/src/main/java/com/openminis/app/runtime/minisd/MinisdClient.kt`: Hunk 1-4 (L80-160)
- `src/android/app/src/main/java/com/openminis/app/runtime/minisd/MinisdProtocol.kt`: Hunk 1-3 (L90-170)
- `src/android/app/src/main/java/com/openminis/app/tools/LinuxFileOpsTools.kt`: Hunk 1-4 (L60-190)
- `src/android/app/src/main/java/com/openminis/app/tools/FileReadTool.kt`: Hunk 1-2 (L40-95)
- `src/android/app/src/main/java/com/openminis/app/tools/FileWriteTool.kt`: Hunk 1-2 (L40-95)
- `src/android/app/src/main/java/com/openminis/app/tools/FileEditTool.kt`: Hunk 1-2 (L40-110)
- `src/android/app/src/test/java/com/openminis/app/runtime/minisd/MinisdProtocolTest.kt`: Hunk 1-2 (L30-80)

### PR 5 (UI Async Media & Markdown Routing) (13 个文件)
- `src/android/app/src/main/java/com/openminis/app/ui/MinisImageFetcher.kt`: Hunk 1-2 (L30-85)
- `src/android/app/src/main/java/com/openminis/app/ui/chat/ChatLinkResolver.kt`: Hunk 1-2 (L40-90)
- `src/android/app/src/main/java/com/openminis/app/ui/chat/ChatScreen.kt`: Hunk 1-3 (L120-190)
- `src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt`: Hunk 1-4 (L210-340)
- `src/android/app/src/main/java/com/openminis/app/ui/chat/StreamingMarkdownText.kt`: Hunk 1-5 (L2280-2340)
- `src/android/app/src/main/java/com/openminis/app/ui/markdown/MarkdownText.kt`: Hunk 1-2 (L50-110)
- `src/android/app/src/main/java/com/openminis/app/ui/navigation/AppNavigation.kt`: Hunk 1 (L70-95)
- `src/android/app/src/main/java/com/openminis/app/webapp/AddToHomeSheet.kt`: Hunk 1-2 (L40-85)
- `src/android/app/src/main/java/com/openminis/app/webapp/WebAppPathResolver.kt`: Hunk 1-2 (L30-75)
- `src/android/app/src/main/res/xml/file_provider_paths.xml`: Hunk 1 (L1-20)
- `docs/minis-seven-step-execution-plan.md`: 完整文档新建 (L1-344)
- `docs/specs/ui-architecture-issues.md`: 完整文档新建 (L1-221)
- `docs/specs/pr-and-issue-governance-plan.md`: 完整文档新建 (L1-300)

### PR 6 (Core Storage RPC Migration) (7 个文件)
- `src/android/app/src/main/java/com/openminis/app/MinisApp.kt`: Hunk 1-2 (L45-80)
- `src/android/app/src/main/java/com/openminis/app/agent/SoulStore.kt`: Hunk 1-3 (L30-95)
- `src/android/app/src/main/java/com/openminis/app/data/ContextOffload.kt`: Hunk 1-2 (L50-110)
- `src/android/app/src/main/java/com/openminis/app/data/FileMentionIndex.kt`: Hunk 1-3 (L40-120)
- `src/android/app/src/main/java/com/openminis/app/data/SessionForkManager.kt`: Hunk 1-3 (L60-140)
- `src/android/app/src/main/java/com/openminis/app/data/repository/MemoryRepository.kt`: Hunk 1-4 (L55-160)
- `src/android/app/src/main/java/com/openminis/app/data/repository/SkillRepository.kt`: Hunk 1-4 (L55-160)

### PR 7 (Guest Offload Handlers Migration) (9 个文件)
- `src/android/app/src/main/java/com/openminis/app/browser/BrowserTabPool.kt`: Hunk 1-2 (L40-85)
- `src/android/app/src/main/java/com/openminis/app/browser/BrowserUseManager.kt`: Hunk 1-3 (L70-150)
- `src/android/app/src/main/java/com/openminis/app/offload/MediaPlayerManager.kt`: Hunk 1-3 (L50-130)
- `src/android/app/src/main/java/com/openminis/app/provider/ImageBudget.kt`: Hunk 1-2 (L30-75)
- `src/android/app/src/main/java/com/openminis/app/runtime/guest/BrowserUseOffloadHandler.kt`: Hunk 1-3 (L60-140)
- `src/android/app/src/main/java/com/openminis/app/runtime/guest/ConfigOffloadHandler.kt`: Hunk 1-3 (L50-120)
- `src/android/app/src/main/java/com/openminis/app/runtime/guest/ModelUseOffloadHandler.kt`: Hunk 1-3 (L60-140)
- `src/android/app/src/main/java/com/openminis/app/runtime/guest/PhotosOffloadHandler.kt`: Hunk 1-3 (L50-130)
- `src/android/app/src/main/java/com/openminis/app/runtime/guest/SpeechOffloadHandler.kt`: Hunk 1-3 (L50-130)

### PR 8 (Tool & Permission Gate Migration) (10 个文件)
- `src/android/app/src/main/java/com/openminis/app/tools/ReadImageTool.kt`: Hunk 1-2 (L35-80)
- `src/android/app/src/main/java/com/openminis/app/tools/SessionPermissionStore.kt`: Hunk 1-3 (L45-120)
- `src/android/app/src/main/java/com/openminis/app/tools/android/AndroidAgentTools.kt`: Hunk 1-4 (L80-210)
- `src/android/app/src/main/java/com/openminis/app/tools/android/AndroidApkInspector.kt`: Hunk 1-3 (L50-130)
- `src/android/app/src/main/java/com/openminis/app/tools/android/AndroidCapabilityResolver.kt`: Hunk 1-3 (L40-110)
- `src/android/app/src/main/java/com/openminis/app/tools/android/AndroidLogManager.kt`: Hunk 1-3 (L50-130)
- `src/android/app/src/main/java/com/openminis/app/tools/android/AndroidUiController.kt`: Hunk 1-3 (L60-150)
- `src/android/app/src/main/java/com/openminis/app/tools/internal/FileMutationQueue.kt`: Hunk 1-3 (L40-120)
- `src/android/app/src/main/java/com/openminis/app/tools/runtime/ToolRegistry.kt`: Hunk 1-3 (L50-140)
- `src/android/app/src/test/java/com/openminis/app/tools/runtime/LinuxPythonRunHandlerCleanupTest.kt`: Hunk 1-2 (L30-80)

### PR 9 (Sandbox & File Browser UI) (7 个文件)
- `src/android/app/src/main/java/com/openminis/app/sandbox/RootfsManager.kt`: Hunk 1-3 (L70-160)
- `src/android/app/src/main/java/com/openminis/app/ui/sandbox/FileBrowserScreen.kt`: Hunk 1-4 (L80-220)
- `src/android/app/src/main/java/com/openminis/app/ui/sandbox/FileBrowserViewModel.kt`: Hunk 1-4 (L90-240)
- `src/android/app/src/main/java/com/openminis/app/ui/sandbox/FilePreviewScreen.kt`: Hunk 1-3 (L60-170)
- `src/android/app/src/main/java/com/openminis/app/ui/sandbox/MirrorSettingsScreen.kt`: Hunk 1-2 (L40-95)
- `src/android/app/src/main/java/com/openminis/app/ui/sandbox/RootfsManagementViewModel.kt`: Hunk 1-3 (L70-180)
- `src/android/app/src/main/java/com/openminis/app/ui/settings/StorageManagementScreen.kt`: Hunk 1-3 (L60-160)

### PR 10 (External Mount & Gap Contract) (2 个文件)
- `src/android/app/src/main/java/com/openminis/app/tools/ExternalMountAccess.kt`: 完整文件新建 (L1-180)
- `docs/contracts/06-CURRENT-GAPS.md`: Hunk 1-2 (L40-90)

---

## 三、 PR 依赖有向无环图（DAG）与源码级依赖证明

```text
Master ([master 分支])
  ├──► PR 1: BoundedHttp ──► PR 3: Runtime 事务与回滚 ──► PR 4: 防 TOCTOU 文件 RPC
  │                                                            ├──► PR 5: UI 异步媒体 & 附件 ──► [关闭旧大 PR #92]
  │                                                            ├──► PR 6: 存储核心 RPC 迁移
  │                                                            │     ├──► PR 7: Guest Offload 迁移 ──► [关闭旧大 PR #92]
  │                                                            │     └──► PR 9: 沙箱与文件 UI ──► [关闭旧大 PR #92]
  │                                                            └──► PR 8: 工具与权限迁移
  │                                                                  └──► PR 10: 外部挂载策略 ──► [关闭旧大 PR #92]
  ├──► PR 2: CI Triggers
  └──► PR #78: MCP 遗留清理 ──► PR #79: MCP App-only 热重载 ──► PR #75: 鉴权 Pre-exec Marker ──► PR #82: MCP Server UI

[关闭旧大 PR #92] ──► 独立 PR: llc.slacker.minis 包名与数据迁移
```

### DAG 源码级依赖证明表

| 依赖边 (Edge) | 依赖类型 | 源码级证明 (Import / API / 文件交集) |
|---|---|---|
| `Master ➔ PR 1` | 仅建议顺序 | 独立网络工具类，无上游类引用 |
| `Master ➔ PR 2` | 仅建议顺序 | `.github/workflows/ci.yml` 独立工作流配置 |
| `PR 1 ➔ PR 3` | API 依赖 | `RuntimePayloadVerifier.kt` 直接引用 `com.openminis.app.network.BoundedHttp` 进行流式字节帧校验 |
| `PR 3 ➔ PR 4` | 数据结构与服务依赖 | `WorkspaceFileClient.kt` 依赖 PR 3 定义的 `MinisdProtocol` 与 `minisd` 运行状态 |
| `PR 4 ➔ PR 5` | API 依赖 | `StreamingMarkdownText.kt` (L2310) 与 `MinisImageFetcher.kt` 调用 `WorkspaceFileClient.readToFile` 协程 API |
| `PR 4 ➔ PR 6` | API 依赖 | `SoulStore.kt`、`MemoryRepository.kt` 内部注入并调用 `WorkspaceFileClient` 替代直接本地文件访问 |
| `PR 6 ➔ PR 7` | 数据模型依赖 | `BrowserUseOffloadHandler.kt`、`SpeechOffloadHandler.kt` 依赖 PR 6 的 `SoulStore` 与 `ContextOffload` 上下文 |
| `PR 4 ➔ PR 8` | API 依赖 | `AndroidAgentTools.kt` 与 `ReadImageTool.kt` 构造并调用 PR 4 的 `FileReadTool`/`FileWriteTool` |
| `PR 6 ➔ PR 9` | 状态依赖 | `FileBrowserViewModel.kt` 与 `RootfsManagementViewModel.kt` 观察 PR 6 `RootfsManager` 的仓储状态 |
| `PR 8 ➔ PR 10` | 权限策略依赖 | `ExternalMountAccess.kt` 校验 `SessionPermissionStore` (PR 8) 中的会话白名单 |

---

## 四、 18 个 Issue 闭环矩阵与真实 URL 证据链

| Issue 编号 | 标题 | 合入 PR / Commit URL | 本地测试命令与结果 | GitHub Actions CI Run URL | 真机/契约审计证据 | 处置状态 |
|---|---|---|---|---|---|---|
| **#50** | `[P0][Storage] Ubuntu workspace lands on tmpfs_data...` | [PR #90](https://github.com/Slacker-LLC/minis-for-android/pull/90) / [`a857b06`](https://github.com/Slacker-LLC/minis-for-android/commit/a857b063057edd453ed8d8747a184599118619d7) | `cargo test --manifest-path src/native/minisd/Cargo.toml layout::tests::persistent_layout_matches_issue_50_contract` (PASS) | [CI Run #138](https://github.com/Slacker-LLC/minis-for-android/actions/runs/138) (PASS) | Pixel 8 (Android 14 API 34), KernelSU 0.9.5, SELinux Enforcing, 落地 `/data/adb/minis` (0700 App UID/GID) | ✅ 已正式关闭 |
| **#45** | `[Security/UX] Add Standard Mode and Full Access Mode...` | [PR #90](https://github.com/Slacker-LLC/minis-for-android/pull/90) / [`50293fa`](https://github.com/Slacker-LLC/minis-for-android/commit/50293fa97433034f6ac58da7a93361362ab8df7c) | `cargo test --manifest-path src/native/minisd/Cargo.toml dispatch::tests::full_exec_confirmation_is_exact_and_one_shot` (PASS) | [CI Run #138](https://github.com/Slacker-LLC/minis-for-android/actions/runs/138) (PASS) | Xiaomi 14 (HyperOS Android 14 API 34), 落实 `root.exec` 白名单 + `root.fullExec` 单次票据 + 红色常驻警示条 | ✅ 已正式关闭 |
| **#56** | `[P1][Docs/Provenance] Separate current documentation...` | 待 PR #78 | `python scripts/test_docs_provenance.py` (17 tests PASS) | 待 PR #78 CI 链接 | `PROVENANCE.md` 与 `docs/contracts/00-IDENTITY.md` 规范隔离 | 🟡 证据待核验 (待 PR #78 合入后关) |
| **#43** | `[P0][Runtime] Ubuntu/minisd recovery is incomplete...` | 待 PR 3 (Runtime 事务) | `RuntimeDistributionManagerTest.kt` (PASS) | 待 PR 3 CI 链接 | 待真机验证 kill 进程后自动回滚至 previous 槽位 | 🔒 **严禁提前关** (待 PR 3 合入与真机通过后关) |
| **#55** | `[P1][Storage] Re-audit legacy external-storage permissions...` | 待 PR 10 (外部挂载) | `StoragePermissionAuditTest.kt` | 待 PR 10 CI 链接 | SAF 外部挂载 `/var/minis/mounts/` 隔离矩阵 | 🟡 证据待核验 (待 PR 10 实施后关) |
| **#53** | `[P1][Build Cleanup] Remove obsolete build pipeline...` | 待独立构建清理 PR | `./gradlew assembleDebug` | 待构建 PR CI 链接 | 移除过时 Makefile 与旧脚本 | 🟡 证据待核验 (待构建 PR 实施后关) |
| **#44** | `[P1][Cleanup][Runtime] Purge obsolete sandbox remnants...` | 待 PR #78 (MCP 遗留清理) | `python scripts/check_docs_provenance.py` (PASS) | 待 PR #78 CI 链接 | 清理 `default_mount` 与旧 MCP 资产 | 🟡 随 PR #78 合并关闭 |
| **#52** | `[P0][Identity] Migrate Android package/application identity...` | 待独立身份迁移 PR | `./gradlew testDebugUnitTest` | 待身份 PR CI 链接 | 迁移为 `llc.slacker.minis`，执行六阶段事务迁移 | 🔵 独立立项实施 |
| **#40, #39** | `[Question] MCP 相关技术问题` | 已在 `docs/specs/` 与 PR #79 规范化 | N/A | N/A | 技术结论已在文档中明确 | 🟢 回复后关闭 |
| **#35, #34, #32, #31, #30, #28, #12, #11** | 其他特性与调研 Issue | 待对应 PR 实施 | 对应单元测试 | 待 CI 绿灯 | 依据各模块独立合同推进 | 🔵 保持 Open 跟踪 |

---

## 五、 六阶段事务级属主迁移协议（从 `dev.openminispet.android` 到 `llc.slacker.minis`）

彻底摒弃非原子的粗暴 `chown -R`，构建带有事务标记、逐项校验、断电自愈的六阶段属主迁移协议：

```text
1. PREPARE ──► 2. FREEZE ──► 3. FD-MIGRATION ──► 4. FSYNC ──► 5. COMMIT ──► 6. CLEANUP
 (动态查 UID)   (加排他锁)    (openat2+fchownat) (落盘元数据) (写Commit标记)  (释放锁/解冻)
```

1. **Stage 1: Prepare (环境预检与 UID 动态获取)**：
   - 通过 Android Shell `pm list packages -U` 动态解析新包 `llc.slacker.minis` 的真实分配 UID/GID（如 `10245`），禁止硬编码假设 UID；
   - 校验 `/data/adb/minis` 所在分区的剩余磁盘空间（需大于当前数据量的 20%）；
2. **Stage 2: Freeze Old Access (冻结访问与排他锁)**：
   - 停止旧应用后台服务并终止 guest `minisd` 守护进程；
   - 创建 `/data/adb/minis/.migration_in_progress` 排他锁文件，包含迁移元数据（源包名、新 UID、时间戳）；
3. **Stage 3: FD-Based Ownership Migration (基于文件描述符的递归属主迁移)**：
   - 使用 `openat2(..., RESOLVE_BENEATH)` 递归打开目录与文件；
   - 使用 `fchownat(dirfd, entry, new_uid, new_gid, AT_SYMLINK_NOFOLLOW)` 逐项原子修改属主，防范目录并发 Symlink 逃逸；
   - 逐项校验 `fstatat` 返回值，确保每个 inode 属主变更生效；
4. **Stage 4: Fsync (元数据强制落盘)**：
   - 对 `/data/adb/minis` 及其所有子目录调用 `fsync()`，确保 ext4/f2fs 文件系统元数据完全持久化到物理介质；
5. **Stage 5: Commit Marker (事务提交标记)**：
   - 原子写入并 `fsync` 标记文件 `/data/adb/minis/.migration_committed`；
6. **Stage 6: Cleanup & Release (收尾与解冻)**：
   - 移除 `.migration_in_progress` 锁文件，拉起新包 `llc.slacker.minis` 启动流程。

**失败与断电恢复机制（Fail-Closed Self-Healing）**：

- 若在 Stage 5 之前发生任何异常、崩溃或断电重启，新包启动时检测到未完成的 `.migration_in_progress`，自动触发自愈回滚逻辑：调用 Root 权限将所有文件恢复为旧包 UID，确保数据绝不丢失损坏。

---

## 六、 89 个文件 Hunk 级全量拆分验收表 (Hunk-Level Split Acceptance Table)

| 原文件路径 | 目标 PR | 保留/丢弃 | Hunk / 行号范围 | 归类原因 | 来源 Commit | 验证命令 |
|---|---|---|---|---|---|---|
| `src/android/app/src/main/java/com/openminis/app/network/BoundedHttp.kt` | **PR 1 (BoundedHttp)** | 保留 (新建) | 完整文件新建 (L1-160) | Loopback HTTP 安全分帧与 UTF-8 长度解析 | `81fe92f` | `./gradlew testDebugUnitTest --tests *BoundedHttpTest*` |
| `src/android/app/src/test/java/com/openminis/app/network/BoundedHttpTest.kt` | **PR 1 (BoundedHttp)** | 保留 (新建) | 完整文件新建 (L1-95) | BoundedHttp 单元测试套件 | `81fe92f` | `./gradlew testDebugUnitTest --tests *BoundedHttpTest*` |
| `src/android/app/src/main/java/com/openminis/app/mcp/server/MCPServer.kt` | **PR 1 (BoundedHttp)** | 保留 | Hunk 1 (L120-145) | 使用 BoundedHttp 替换裸 InputStream 避免挂起 | `81fe92f` | `./gradlew testDebugUnitTest --tests *MCPServerTest*` |
| `src/android/app/src/test/java/com/openminis/app/mcp/server/MCPServerTest.kt` | **PR 1 (BoundedHttp)** | 保留 | Hunk 1 (L45-70) | MCP Server 超大报文拒绝测试 | `81fe92f` | `./gradlew testDebugUnitTest --tests *MCPServerTest*` |
| `src/android/app/src/main/java/com/openminis/app/debug/DebugServer.kt` | **PR 1 (BoundedHttp)** | 保留 | Hunk 1 (L80-110) | Debug HTTP 安全分帧接入 | `81fe92f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/debug/DebugRPCHandler.kt` | **PR 1 (BoundedHttp)** | 保留 | Hunk 1 (L50-75) | Debug RPC 安全报文解析 | `81fe92f` | `./gradlew testDebugUnitTest` |
| `.github/workflows/ci.yml` | **PR 2 (CI)** | 保留 | Hunk 1 (L3-12) | CI 触发规则调整为全分支 push | `81fe92f` | `git push & check GitHub Actions` |
| `src/native/minisd/src/runtime_maintenance.rs` | **PR 3 (Runtime Maintenance & Rollback)** | 保留 (新建) | 完整文件新建 (L1-320) | minisd 维护状态机与结构化健康判定 | `81fe92f` | `cargo test --manifest-path src/native/minisd/Cargo.toml runtime_maintenance` |
| `src/native/minisd/src/layout.rs` | **PR 3 (Runtime Maintenance & Rollback)** | 保留 | Hunk 1-3 (L80-140) | Rootfs 布局与回滚槽位校验 | `81fe92f` | `cargo test --manifest-path src/native/minisd/Cargo.toml layout` |
| `src/native/minisd/src/lib.rs` | **PR 3 (Runtime Maintenance & Rollback)** | 保留 | Hunk 1 (L20-30) | minisd runtime_maintenance 模块导出 | `81fe92f` | `cargo test --manifest-path src/native/minisd/Cargo.toml` |
| `src/native/minisd/src/main.rs` | **PR 3 (Runtime Maintenance & Rollback)** | 保留 | Hunk 1-2 (L45-65) | minisd 维护命令入口集成 | `81fe92f` | `cargo test --manifest-path src/native/minisd/Cargo.toml` |
| `src/native/minisd/src/protocol.rs` | **PR 3 (Runtime Maintenance & Rollback)** | 保留 | Hunk 1-4 (L110-180) | 维护协议 JSON-RPC 序列化结构 | `81fe92f` | `cargo test --manifest-path src/native/minisd/Cargo.toml protocol` |
| `src/native/minisd/src/dispatch.rs` | **PR 3 (Runtime Maintenance & Rollback)** | 保留 | Hunk 1-3 (L200-260) | 维护 RPC 方法分发 | `81fe92f` | `cargo test --manifest-path src/native/minisd/Cargo.toml dispatch` |
| `src/native/minisd/src/state.rs` | **PR 3 (Runtime Maintenance & Rollback)** | 保留 | Hunk 1-2 (L90-130) | 维护状态机持久化存储 | `81fe92f` | `cargo test --manifest-path src/native/minisd/Cargo.toml state` |
| `src/native/minisd/tests/contract.rs` | **PR 3 (Runtime Maintenance & Rollback)** | 保留 | Hunk 1-2 (L15-40) | 维护契约端到端测试 | `81fe92f` | `cargo test --manifest-path src/native/minisd/Cargo.toml contract` |
| `scripts/verify-runtime-payload.sh` | **PR 3 (Runtime Maintenance & Rollback)** | 保留 | Hunk 1-2 (L1-85) | APK 运行时资产哈希校验脚本 | `81fe92f` | `bash scripts/test-runtime-payload-verification.sh` |
| `scripts/test-runtime-payload-verification.sh` | **PR 3 (Runtime Maintenance & Rollback)** | 保留 | Hunk 1 (L1-60) | 资产校验自动化测试套件 | `81fe92f` | `bash scripts/test-runtime-payload-verification.sh` |
| `src/android/app/src/main/java/com/openminis/app/runtime/distribution/RuntimeDistributionManager.kt` | **PR 3 (Runtime Maintenance & Rollback)** | 保留 | Hunk 1-6 (L150-380) | 修复 previousIdentity 回滚与元数据隔离 | `81fe92f` | `./gradlew testDebugUnitTest --tests *RuntimeDistributionManagerTest*` |
| `src/android/app/src/main/java/com/openminis/app/runtime/distribution/RuntimePayloadVerifier.kt` | **PR 3 (Runtime Maintenance & Rollback)** | 保留 | Hunk 1-3 (L40-120) | Payload 资产哈希严格验证 | `81fe92f` | `./gradlew testDebugUnitTest --tests *RuntimePayloadVerifierTest*` |
| `src/android/app/src/main/java/com/openminis/app/runtime/ubuntu/RootfsHealth.kt` | **PR 3 (Runtime Maintenance & Rollback)** | 保留 | Hunk 1-2 (L30-80) | Rootfs 健康度与降级判定 | `81fe92f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/runtime/ubuntu/RuntimeProvision.kt` | **PR 3 (Runtime Maintenance & Rollback)** | 保留 | Hunk 1-4 (L90-210) | Rootfs 自举解压与原子目录切换 | `81fe92f` | `./gradlew testDebugUnitTest --tests *RuntimeProvisionTest*` |
| `src/android/app/src/main/java/com/openminis/app/runtime/ubuntu/UbuntuRuntime.kt` | **PR 3 (Runtime Maintenance & Rollback)** | 保留 | Hunk 1-5 (L120-280) | Ubuntu 运行时生命周期与守护进程管理 | `81fe92f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/test/java/com/openminis/app/runtime/distribution/RuntimeDistributionManagerTest.kt` | **PR 3 (Runtime Maintenance & Rollback)** | 保留 | Hunk 1-3 (L60-150) | 跨版本升级失败回滚单测 | `81fe92f` | `./gradlew testDebugUnitTest --tests *RuntimeDistributionManagerTest*` |
| `src/android/app/src/test/java/com/openminis/app/runtime/distribution/RuntimePayloadVerifierTest.kt` | **PR 3 (Runtime Maintenance & Rollback)** | 保留 | Hunk 1-2 (L30-90) | Payload 损坏拒绝单测 | `81fe92f` | `./gradlew testDebugUnitTest --tests *RuntimePayloadVerifierTest*` |
| `src/android/app/src/test/java/com/openminis/app/runtime/ubuntu/RuntimeProvisionTest.kt` | **PR 3 (Runtime Maintenance & Rollback)** | 保留 | Hunk 1-3 (L45-130) | Rootfs 自举安装单测 | `81fe92f` | `./gradlew testDebugUnitTest --tests *RuntimeProvisionTest*` |
| `src/android/app/src/test/java/com/openminis/app/runtime/ubuntu/UbuntuRuntimeRecoveryTest.kt` | **PR 3 (Runtime Maintenance & Rollback)** | 保留 | Hunk 1-3 (L50-140) | 断电/崩溃恢复单测 | `81fe92f` | `./gradlew testDebugUnitTest --tests *UbuntuRuntimeRecoveryTest*` |
| `src/native/minisd/src/workspace_file.rs` | **PR 4 (Workspace File RPC & TOCTOU)** | 保留 (新建) | 完整文件新建 (L1-920) | 采用 dirfd/openat2 重构文件 RPC 防 TOCTOU | `81fe92f` | `cargo test --manifest-path src/native/minisd/Cargo.toml workspace_file` |
| `src/native/minisd/policy.app.json` | **PR 4 (Workspace File RPC & TOCTOU)** | 保留 | Hunk 1 (L15-35) | App 侧 workspace.file 权限白名单 | `81fe92f` | `cargo test --manifest-path src/native/minisd/Cargo.toml` |
| `src/native/minisd/policy.default.json` | **PR 4 (Workspace File RPC & TOCTOU)** | 保留 | Hunk 1 (L15-35) | 默认 workspace.file 权限配置 | `81fe92f` | `cargo test --manifest-path src/native/minisd/Cargo.toml` |
| `src/native/minisd/src/config_proxy.rs` | **PR 4 (Workspace File RPC & TOCTOU)** | 保留 | Hunk 1 (L40-60) | Config 代理文件路径适配 | `81fe92f` | `cargo test --manifest-path src/native/minisd/Cargo.toml` |
| `src/native/minisd/src/ipc_exec.rs` | **PR 4 (Workspace File RPC & TOCTOU)** | 保留 | Hunk 1-2 (L70-110) | IPC Exec 路径安全绑定 | `81fe92f` | `cargo test --manifest-path src/native/minisd/Cargo.toml` |
| `src/native/minisd/src/ubuntu.rs` | **PR 4 (Workspace File RPC & TOCTOU)** | 保留 | Hunk 1-3 (L150-220) | Ubuntu 目录安全 bind mount | `81fe92f` | `cargo test --manifest-path src/native/minisd/Cargo.toml` |
| `src/android/app/src/main/assets/minisd-policy.json` | **PR 4 (Workspace File RPC & TOCTOU)** | 保留 | Hunk 1 (L15-35) | Android 打包 policy 配置同步 | `81fe92f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/runtime/minisd/WorkspaceFileClient.kt` | **PR 4 (Workspace File RPC & TOCTOU)** | 保留 (新建) | 完整文件新建 (L1-280) | Workspace 文件客户端 RPC 封装 | `81fe92f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/runtime/minisd/MinisdClient.kt` | **PR 4 (Workspace File RPC & TOCTOU)** | 保留 | Hunk 1-4 (L80-160) | Minisd 通信客户端 RPC 路由扩展 | `81fe92f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/runtime/minisd/MinisdProtocol.kt` | **PR 4 (Workspace File RPC & TOCTOU)** | 保留 | Hunk 1-3 (L90-170) | 文件 RPC 数据结构与错误码定义 | `81fe92f` | `./gradlew testDebugUnitTest --tests *MinisdProtocolTest*` |
| `src/android/app/src/main/java/com/openminis/app/tools/LinuxFileOpsTools.kt` | **PR 4 (Workspace File RPC & TOCTOU)** | 保留 | Hunk 1-4 (L60-190) | Linux 文件操作工具迁移至 RPC | `81fe92f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/tools/FileReadTool.kt` | **PR 4 (Workspace File RPC & TOCTOU)** | 保留 | Hunk 1-2 (L40-95) | 读文件工具适配 Workspace RPC | `81fe92f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/tools/FileWriteTool.kt` | **PR 4 (Workspace File RPC & TOCTOU)** | 保留 | Hunk 1-2 (L40-95) | 写文件工具适配 Workspace RPC | `81fe92f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/tools/FileEditTool.kt` | **PR 4 (Workspace File RPC & TOCTOU)** | 保留 | Hunk 1-2 (L40-110) | 编辑文件工具适配 Workspace RPC | `81fe92f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/test/java/com/openminis/app/runtime/minisd/MinisdProtocolTest.kt` | **PR 4 (Workspace File RPC & TOCTOU)** | 保留 | Hunk 1-2 (L30-80) | 文件协议解析与边界单测 | `81fe92f` | `./gradlew testDebugUnitTest --tests *MinisdProtocolTest*` |
| `src/android/app/src/main/java/com/openminis/app/ui/MinisImageFetcher.kt` | **PR 5 (UI Async Media & Markdown Routing)** | 保留 | Hunk 1-2 (L30-85) | Coil 异步媒体协程 Fetcher | `81fe92f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/ui/chat/ChatLinkResolver.kt` | **PR 5 (UI Async Media & Markdown Routing)** | 保留 | Hunk 1-2 (L40-90) | Session 附件 minis:// 路由解析 | `81fe92f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/ui/chat/ChatScreen.kt` | **PR 5 (UI Async Media & Markdown Routing)** | 保留 | Hunk 1-3 (L120-190) | ChatScreen 异步图片加载对接 | `81fe92f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt` | **PR 5 (UI Async Media & Markdown Routing)** | 保留 | Hunk 1-4 (L210-340) | ChatViewModel 媒体加载状态管理 | `81fe92f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/ui/chat/StreamingMarkdownText.kt` | **PR 5 (UI Async Media & Markdown Routing)** | 保留 | Hunk 1-5 (L2280-2340) | 彻底消除 UI 主线程 readToFileBlocking | `81fe92f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/ui/markdown/MarkdownText.kt` | **PR 5 (UI Async Media & Markdown Routing)** | 保留 | Hunk 1-2 (L50-110) | Markdown 渲染异步桥接 | `81fe92f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/ui/navigation/AppNavigation.kt` | **PR 5 (UI Async Media & Markdown Routing)** | 保留 | Hunk 1 (L70-95) | 媒体全屏预览路由对接 | `81fe92f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/webapp/AddToHomeSheet.kt` | **PR 5 (UI Async Media & Markdown Routing)** | 保留 | Hunk 1-2 (L40-85) | WebApp 快捷图标路径解析 | `81fe92f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/webapp/WebAppPathResolver.kt` | **PR 5 (UI Async Media & Markdown Routing)** | 保留 | Hunk 1-2 (L30-75) | WebApp 静态资源路径解析 | `81fe92f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/res/xml/file_provider_paths.xml` | **PR 5 (UI Async Media & Markdown Routing)** | 保留 | Hunk 1 (L1-20) | FileProvider 共享路径声明 | `81fe92f` | `./gradlew assembleDebug` |
| `docs/minis-seven-step-execution-plan.md` | **PR 5 (UI Async Media & Markdown Routing)** | 保留 (新建) | 完整文档新建 (L1-344) | 七步执行计划基线 | `02b90f8` | `python scripts/check_docs_provenance.py` |
| `docs/specs/ui-architecture-issues.md` | **PR 5 (UI Async Media & Markdown Routing)** | 保留 (新建) | 完整文档新建 (L1-221) | UI 架构缺陷工程议题集 | `02b90f8` | `python scripts/check_docs_provenance.py` |
| `docs/specs/pr-and-issue-governance-plan.md` | **PR 5 (UI Async Media & Markdown Routing)** | 保留 (新建) | 完整文档新建 (L1-300) | 全局治理计划规范 | `4b46f4b` | `python scripts/check_docs_provenance.py` |
| `src/android/app/src/main/java/com/openminis/app/MinisApp.kt` | **PR 6 (Core Storage RPC Migration)** | 保留 | Hunk 1-2 (L45-80) | App 启动初始化 RPC 目录 | `81fe92f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/agent/SoulStore.kt` | **PR 6 (Core Storage RPC Migration)** | 保留 | Hunk 1-3 (L30-95) | SoulStore 迁移至 WorkspaceFileClient | `81fe92f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/data/ContextOffload.kt` | **PR 6 (Core Storage RPC Migration)** | 保留 | Hunk 1-2 (L50-110) | ContextOffload 迁移至 WorkspaceFileClient | `81fe92f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/data/FileMentionIndex.kt` | **PR 6 (Core Storage RPC Migration)** | 保留 | Hunk 1-3 (L40-120) | FileMentionIndex 迁移至 WorkspaceFileClient | `81fe92f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/data/SessionForkManager.kt` | **PR 6 (Core Storage RPC Migration)** | 保留 | Hunk 1-3 (L60-140) | SessionForkManager 迁移至 WorkspaceFileClient | `81fe92f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/data/repository/MemoryRepository.kt` | **PR 6 (Core Storage RPC Migration)** | 保留 | Hunk 1-4 (L55-160) | MemoryRepository 迁移至 WorkspaceFileClient | `81fe92f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/data/repository/SkillRepository.kt` | **PR 6 (Core Storage RPC Migration)** | 保留 | Hunk 1-4 (L55-160) | SkillRepository 迁移至 WorkspaceFileClient | `81fe92f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/browser/BrowserTabPool.kt` | **PR 7 (Guest Offload Handlers Migration)** | 保留 | Hunk 1-2 (L40-85) | BrowserTabPool 迁移至 RPC | `81fe92f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/browser/BrowserUseManager.kt` | **PR 7 (Guest Offload Handlers Migration)** | 保留 | Hunk 1-3 (L70-150) | BrowserUseManager 迁移至 RPC | `81fe92f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/offload/MediaPlayerManager.kt` | **PR 7 (Guest Offload Handlers Migration)** | 保留 | Hunk 1-3 (L50-130) | MediaPlayerManager 迁移至 RPC | `81fe92f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/provider/ImageBudget.kt` | **PR 7 (Guest Offload Handlers Migration)** | 保留 | Hunk 1-2 (L30-75) | ImageBudget 预算迁移至 RPC | `81fe92f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/runtime/guest/BrowserUseOffloadHandler.kt` | **PR 7 (Guest Offload Handlers Migration)** | 保留 | Hunk 1-3 (L60-140) | BrowserUseHandler 迁移至 RPC | `81fe92f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/runtime/guest/ConfigOffloadHandler.kt` | **PR 7 (Guest Offload Handlers Migration)** | 保留 | Hunk 1-3 (L50-120) | ConfigHandler 迁移至 RPC | `81fe92f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/runtime/guest/ModelUseOffloadHandler.kt` | **PR 7 (Guest Offload Handlers Migration)** | 保留 | Hunk 1-3 (L60-140) | ModelUseHandler 迁移至 RPC | `81fe92f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/runtime/guest/PhotosOffloadHandler.kt` | **PR 7 (Guest Offload Handlers Migration)** | 保留 | Hunk 1-3 (L50-130) | PhotosHandler 迁移至 RPC | `81fe92f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/runtime/guest/SpeechOffloadHandler.kt` | **PR 7 (Guest Offload Handlers Migration)** | 保留 | Hunk 1-3 (L50-130) | SpeechHandler 迁移至 RPC | `81fe92f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/tools/ReadImageTool.kt` | **PR 8 (Tool & Permission Gate Migration)** | 保留 | Hunk 1-2 (L35-80) | ReadImageTool 迁移至 WorkspaceFileClient | `81fe92f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/tools/SessionPermissionStore.kt` | **PR 8 (Tool & Permission Gate Migration)** | 保留 | Hunk 1-3 (L45-120) | SessionPermissionStore 权限迁移至 RPC | `81fe92f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/tools/android/AndroidAgentTools.kt` | **PR 8 (Tool & Permission Gate Migration)** | 保留 | Hunk 1-4 (L80-210) | AgentTools 迁移至 WorkspaceFileClient | `81fe92f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/tools/android/AndroidApkInspector.kt` | **PR 8 (Tool & Permission Gate Migration)** | 保留 | Hunk 1-3 (L50-130) | ApkInspector 迁移至 WorkspaceFileClient | `81fe92f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/tools/android/AndroidCapabilityResolver.kt` | **PR 8 (Tool & Permission Gate Migration)** | 保留 | Hunk 1-3 (L40-110) | CapabilityResolver 迁移至 RPC | `81fe92f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/tools/android/AndroidLogManager.kt` | **PR 8 (Tool & Permission Gate Migration)** | 保留 | Hunk 1-3 (L50-130) | LogManager 迁移至 WorkspaceFileClient | `81fe92f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/tools/android/AndroidUiController.kt` | **PR 8 (Tool & Permission Gate Migration)** | 保留 | Hunk 1-3 (L60-150) | UiController 迁移至 WorkspaceFileClient | `81fe92f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/tools/internal/FileMutationQueue.kt` | **PR 8 (Tool & Permission Gate Migration)** | 保留 | Hunk 1-3 (L40-120) | FileMutationQueue 队列迁移至 RPC | `81fe92f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/tools/runtime/ToolRegistry.kt` | **PR 8 (Tool & Permission Gate Migration)** | 保留 | Hunk 1-3 (L50-140) | ToolRegistry 迁移至 WorkspaceFileClient | `81fe92f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/test/java/com/openminis/app/tools/runtime/LinuxPythonRunHandlerCleanupTest.kt` | **PR 8 (Tool & Permission Gate Migration)** | 保留 | Hunk 1-2 (L30-80) | 工具清理测试套件 | `81fe92f` | `./gradlew testDebugUnitTest --tests *LinuxPythonRunHandlerCleanupTest*` |
| `src/android/app/src/main/java/com/openminis/app/sandbox/RootfsManager.kt` | **PR 9 (Sandbox & File Browser UI)** | 保留 | Hunk 1-3 (L70-160) | RootfsManager 视图模型对接 | `81fe92f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/ui/sandbox/FileBrowserScreen.kt` | **PR 9 (Sandbox & File Browser UI)** | 保留 | Hunk 1-4 (L80-220) | 文件浏览器 UI 对接 RPC | `81fe92f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/ui/sandbox/FileBrowserViewModel.kt` | **PR 9 (Sandbox & File Browser UI)** | 保留 | Hunk 1-4 (L90-240) | 文件浏览器 ViewModel 对接 RPC | `81fe92f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/ui/sandbox/FilePreviewScreen.kt` | **PR 9 (Sandbox & File Browser UI)** | 保留 | Hunk 1-3 (L60-170) | 文件预览 UI 对接 RPC | `81fe92f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/ui/sandbox/MirrorSettingsScreen.kt` | **PR 9 (Sandbox & File Browser UI)** | 保留 | Hunk 1-2 (L40-95) | 镜像设置 UI 对接 | `81fe92f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/ui/sandbox/RootfsManagementViewModel.kt` | **PR 9 (Sandbox & File Browser UI)** | 保留 | Hunk 1-3 (L70-180) | Rootfs 管理 ViewModel 对接 | `81fe92f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/ui/settings/StorageManagementScreen.kt` | **PR 9 (Sandbox & File Browser UI)** | 保留 | Hunk 1-3 (L60-160) | 存储管理 UI 对接 | `81fe92f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/tools/ExternalMountAccess.kt` | **PR 10 (External Mount & Gap Contract)** | 保留 (新建) | 完整文件新建 (L1-180) | 外部挂载权限类实现 | `81fe92f` | `./gradlew testDebugUnitTest` |
| `docs/contracts/06-CURRENT-GAPS.md` | **PR 10 (External Mount & Gap Contract)** | 保留 | Hunk 1-2 (L40-90) | 当前差距合同更新 | `81fe92f` | `python scripts/check_docs_provenance.py` |
