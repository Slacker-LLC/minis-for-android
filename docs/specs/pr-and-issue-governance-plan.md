# Minis for Android — PR 与 Issue 全局治理与收敛实施方案 (V3 审计级完整版)

> 规范依据：`docs/contracts/05-ENGINEERING.md` 与 `docs/contracts/00-IDENTITY.md`

---

## 历史与分支安全快照归档表 (Snapshot Archive)

在执行任何分支淘汰或清理前，记录原分支 Head Commit、Diff Stat 与替代映射：

| PR 编号 | 原分支名称 | Head Commit SHA | Diff Stat | 核心提交记录 | 替代处置映射 |
|---|---|---|---|---|---|
| **#76** | `feat/issue-45-privileged-access-modes` | `50293fa` | +320/-110 | `security: add Standard and Full Access privileged modes` | 已被 master 上的 `PrivilegedAccessMode.kt` + `root.exec/fullExec` 替代 |
| **#63** | `fix/issue-50-android-persistent-paths` | `8b0b715` | +410/-180 | `fix(storage): persist Linux guest data under /data/adb/minis` | 已被 master 上的 `docs/contracts/03-STORAGE-CONTRACT.md` + `layout.rs` 替代 |
| **#69** | `feat/issue-52-android-identity` | `fedbd7c` | +1250/-890 | `refactor: migrate Android application identity` | 废弃旧改动，后续新建纯净 `feat/issue-52-slacker-identity` PR |
| **#61** | `chore/issue-53-build-cleanup` | `c97d538` | +180/-95 | `chore(build): clean legacy build paths for #53` | 废弃混杂改动，后续新建纯净 `chore/issue-53-clean-pipeline` PR |
| **#92** | `feat/issue-51-provision-rollback` | `83527df` | +8464/-2329 | 89 个文件的多模块巨石变更 | 全量拆解为 10 个单合同小 PR 后归档关闭 |

---

## 89 个文件全量无遗漏拆解矩阵（大 PR #92 分解方案）

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
- `src/android/app/src/main/java/com/openminis/app/network/BoundedHttp.kt`
- `src/android/app/src/test/java/com/openminis/app/network/BoundedHttpTest.kt`
- `src/android/app/src/main/java/com/openminis/app/mcp/server/MCPServer.kt`
- `src/android/app/src/test/java/com/openminis/app/mcp/server/MCPServerTest.kt`
- `src/android/app/src/main/java/com/openminis/app/debug/DebugServer.kt`
- `src/android/app/src/main/java/com/openminis/app/debug/DebugRPCHandler.kt`

### PR 2 (CI) (1 个文件)
- `.github/workflows/ci.yml`

### PR 3 (Runtime Maintenance & Rollback) (19 个文件)
- `src/native/minisd/src/runtime_maintenance.rs`
- `src/native/minisd/src/layout.rs`
- `src/native/minisd/src/lib.rs`
- `src/native/minisd/src/main.rs`
- `src/native/minisd/src/protocol.rs`
- `src/native/minisd/src/dispatch.rs`
- `src/native/minisd/src/state.rs`
- `src/native/minisd/tests/contract.rs`
- `scripts/verify-runtime-payload.sh`
- `scripts/test-runtime-payload-verification.sh`
- `src/android/app/src/main/java/com/openminis/app/runtime/distribution/RuntimeDistributionManager.kt`
- `src/android/app/src/main/java/com/openminis/app/runtime/distribution/RuntimePayloadVerifier.kt`
- `src/android/app/src/main/java/com/openminis/app/runtime/ubuntu/RootfsHealth.kt`
- `src/android/app/src/main/java/com/openminis/app/runtime/ubuntu/RuntimeProvision.kt`
- `src/android/app/src/main/java/com/openminis/app/runtime/ubuntu/UbuntuRuntime.kt`
- `src/android/app/src/test/java/com/openminis/app/runtime/distribution/RuntimeDistributionManagerTest.kt`
- `src/android/app/src/test/java/com/openminis/app/runtime/distribution/RuntimePayloadVerifierTest.kt`
- `src/android/app/src/test/java/com/openminis/app/runtime/ubuntu/RuntimeProvisionTest.kt`
- `src/android/app/src/test/java/com/openminis/app/runtime/ubuntu/UbuntuRuntimeRecoveryTest.kt`

### PR 4 (Workspace File RPC & TOCTOU) (15 个文件)
- `src/native/minisd/src/workspace_file.rs`
- `src/native/minisd/policy.app.json`
- `src/native/minisd/policy.default.json`
- `src/native/minisd/src/config_proxy.rs`
- `src/native/minisd/src/ipc_exec.rs`
- `src/native/minisd/src/ubuntu.rs`
- `src/android/app/src/main/assets/minisd-policy.json`
- `src/android/app/src/main/java/com/openminis/app/runtime/minisd/WorkspaceFileClient.kt`
- `src/android/app/src/main/java/com/openminis/app/runtime/minisd/MinisdClient.kt`
- `src/android/app/src/main/java/com/openminis/app/runtime/minisd/MinisdProtocol.kt`
- `src/android/app/src/main/java/com/openminis/app/tools/LinuxFileOpsTools.kt`
- `src/android/app/src/main/java/com/openminis/app/tools/FileReadTool.kt`
- `src/android/app/src/main/java/com/openminis/app/tools/FileWriteTool.kt`
- `src/android/app/src/main/java/com/openminis/app/tools/FileEditTool.kt`
- `src/android/app/src/test/java/com/openminis/app/runtime/minisd/MinisdProtocolTest.kt`

### PR 5 (UI Async Media & Markdown Routing) (13 个文件)
- `src/android/app/src/main/java/com/openminis/app/ui/MinisImageFetcher.kt`
- `src/android/app/src/main/java/com/openminis/app/ui/chat/ChatLinkResolver.kt`
- `src/android/app/src/main/java/com/openminis/app/ui/chat/ChatScreen.kt`
- `src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt`
- `src/android/app/src/main/java/com/openminis/app/ui/chat/StreamingMarkdownText.kt`
- `src/android/app/src/main/java/com/openminis/app/ui/markdown/MarkdownText.kt`
- `src/android/app/src/main/java/com/openminis/app/ui/navigation/AppNavigation.kt`
- `src/android/app/src/main/java/com/openminis/app/webapp/AddToHomeSheet.kt`
- `src/android/app/src/main/java/com/openminis/app/webapp/WebAppPathResolver.kt`
- `src/android/app/src/main/res/xml/file_provider_paths.xml`
- `docs/minis-seven-step-execution-plan.md`
- `docs/specs/ui-architecture-issues.md`
- `docs/specs/pr-and-issue-governance-plan.md`

### PR 6 (Core Storage RPC Migration) (7 个文件)
- `src/android/app/src/main/java/com/openminis/app/MinisApp.kt`
- `src/android/app/src/main/java/com/openminis/app/agent/SoulStore.kt`
- `src/android/app/src/main/java/com/openminis/app/data/ContextOffload.kt`
- `src/android/app/src/main/java/com/openminis/app/data/FileMentionIndex.kt`
- `src/android/app/src/main/java/com/openminis/app/data/SessionForkManager.kt`
- `src/android/app/src/main/java/com/openminis/app/data/repository/MemoryRepository.kt`
- `src/android/app/src/main/java/com/openminis/app/data/repository/SkillRepository.kt`

### PR 7 (Guest Offload Handlers Migration) (9 个文件)
- `src/android/app/src/main/java/com/openminis/app/browser/BrowserTabPool.kt`
- `src/android/app/src/main/java/com/openminis/app/browser/BrowserUseManager.kt`
- `src/android/app/src/main/java/com/openminis/app/offload/MediaPlayerManager.kt`
- `src/android/app/src/main/java/com/openminis/app/provider/ImageBudget.kt`
- `src/android/app/src/main/java/com/openminis/app/runtime/guest/BrowserUseOffloadHandler.kt`
- `src/android/app/src/main/java/com/openminis/app/runtime/guest/ConfigOffloadHandler.kt`
- `src/android/app/src/main/java/com/openminis/app/runtime/guest/ModelUseOffloadHandler.kt`
- `src/android/app/src/main/java/com/openminis/app/runtime/guest/PhotosOffloadHandler.kt`
- `src/android/app/src/main/java/com/openminis/app/runtime/guest/SpeechOffloadHandler.kt`

### PR 8 (Tool & Permission Gate Migration) (10 个文件)
- `src/android/app/src/main/java/com/openminis/app/tools/ReadImageTool.kt`
- `src/android/app/src/main/java/com/openminis/app/tools/SessionPermissionStore.kt`
- `src/android/app/src/main/java/com/openminis/app/tools/android/AndroidAgentTools.kt`
- `src/android/app/src/main/java/com/openminis/app/tools/android/AndroidApkInspector.kt`
- `src/android/app/src/main/java/com/openminis/app/tools/android/AndroidCapabilityResolver.kt`
- `src/android/app/src/main/java/com/openminis/app/tools/android/AndroidLogManager.kt`
- `src/android/app/src/main/java/com/openminis/app/tools/android/AndroidUiController.kt`
- `src/android/app/src/main/java/com/openminis/app/tools/internal/FileMutationQueue.kt`
- `src/android/app/src/main/java/com/openminis/app/tools/runtime/ToolRegistry.kt`
- `src/android/app/src/test/java/com/openminis/app/tools/runtime/LinuxPythonRunHandlerCleanupTest.kt`

### PR 9 (Sandbox & File Browser UI) (7 个文件)
- `src/android/app/src/main/java/com/openminis/app/sandbox/RootfsManager.kt`
- `src/android/app/src/main/java/com/openminis/app/ui/sandbox/FileBrowserScreen.kt`
- `src/android/app/src/main/java/com/openminis/app/ui/sandbox/FileBrowserViewModel.kt`
- `src/android/app/src/main/java/com/openminis/app/ui/sandbox/FilePreviewScreen.kt`
- `src/android/app/src/main/java/com/openminis/app/ui/sandbox/MirrorSettingsScreen.kt`
- `src/android/app/src/main/java/com/openminis/app/ui/sandbox/RootfsManagementViewModel.kt`
- `src/android/app/src/main/java/com/openminis/app/ui/settings/StorageManagementScreen.kt`

### PR 10 (External Mount & Gap Contract) (2 个文件)
- `src/android/app/src/main/java/com/openminis/app/tools/ExternalMountAccess.kt`
- `docs/contracts/06-CURRENT-GAPS.md`

---

## PR 依赖有向无环图（DAG）与依赖原因证明

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

### DAG 依赖原因证明表

| 依赖边 (Edge) | 依赖类型 | 证明与代码依据 |
|---|---|---|
| `Master ➔ PR 1` | 仅建议顺序 | 独立网络工具，无上游代码依赖 |
| `Master ➔ PR 2` | 仅建议顺序 | 独立 CI 配置，无代码耦合 |
| `PR 1 ➔ PR 3` | API 依赖 | `RuntimeDistributionManager` 与 `RuntimePayloadVerifier` 依赖 `BoundedHttp` 进行流式字节校验 |
| `PR 3 ➔ PR 4` | 数据格式/状态依赖 | `WorkspaceFileClient` 依赖 PR 3 中的 `MinisdProtocol` 与 Broker 维护状态 |
| `PR 4 ➔ PR 5` | API 依赖 | `StreamingMarkdownText` 媒体加载调用 `WorkspaceFileClient.readToFile` 异步协程 API |
| `PR 4 ➔ PR 6` | API 依赖 | `SoulStore`/`MemoryRepository` 等核心仓储依赖 `WorkspaceFileClient` 文件读写 RPC |
| `PR 6 ➔ PR 7` | 共享模型依赖 | Guest Offload 处理器依赖 PR 6 的 `SoulStore`/`ContextOffload` 会话模型 |
| `PR 4 ➔ PR 8` | API 依赖 | 工具层直接调用 PR 4 提供的底层 `FileReadTool`/`FileWriteTool` 接口 |
| `PR 6 ➔ PR 9` | 状态依赖 | 沙箱 UI 依赖 PR 6 的 `RootfsManager` 与持久化目录状态 |
| `PR 8 ➔ PR 10` | 权限策略依赖 | External Mount 挂载策略依赖 PR 8 的 `SessionPermissionStore` 进行权限校验 |

---

## 18 个 Issue 闭环矩阵与四维门槛

关闭任何 Issue 必须同时满足：**代码 PR 合并 + 本地测试全绿 + 远端 CI 绿灯 + 真机/契约证据**。

| Issue 编号 | 标题 | 代码依据 (Commit/PR) | 本地测试命令与结果 | 远端 CI 状态 | 真机/契约审计证据 | 处置状态 |
|---|---|---|---|---|---|---|
| **#50** | `[P0][Storage] Ubuntu workspace lands on tmpfs_data...` | `a857b06` (PR #90) / `8b0b715` | `cargo test --manifest-path src/native/minisd/Cargo.toml layout::tests::persistent_layout_matches_issue_50_contract` (PASS) | CI Run #138 绿灯 | `docs/contracts/03-STORAGE-CONTRACT.md` 落实 `/data/adb/minis` (0700 App UID/GID)，严格拒绝 tmpfs | ✅ 已正式关闭 |
| **#45** | `[Security/UX] Add Standard Mode and Full Access Mode...` | `a857b06` (PR #90) / `50293fa` | `cargo test --manifest-path src/native/minisd/Cargo.toml dispatch::tests::full_exec_confirmation_is_exact_and_one_shot` (PASS) | CI Run #138 绿灯 | `docs/contracts/04-SECURITY-CONTRACT.md` 落实 `root.exec` 白名单 + `root.fullExec` 单次确认票 + UI 警示条 | ✅ 已正式关闭 |
| **#56** | `[P1][Docs/Provenance] Separate current documentation...` | 待 PR #78 | `python scripts/test_docs_provenance.py` (17 tests PASS) | 待 PR #78 CI | `PROVENANCE.md` 与 `docs/contracts/00-IDENTITY.md` 规范隔离 | 🟡 待 PR #78 合入后关闭 |
| **#43** | `[P0][Runtime] Ubuntu/minisd recovery is incomplete...` | 待 PR 3 (Runtime 事务) | `RuntimeDistributionManagerTest.kt` (PASS) | 待 PR 3 CI | 待真机验证 kill 进程后自动回滚至 previous 槽位 | 🔒 **严禁提前关**，待 PR 3 合入后关闭 |
| **#55** | `[P1][Storage] Re-audit legacy external-storage permissions...` | 待 PR 10 (外部挂载) | `StoragePermissionAuditTest.kt` | 待 PR 10 CI | SAF 外部挂载 `/var/minis/mounts/` 隔离矩阵 | 🟡 待 PR 10 实施后关闭 |
| **#53** | `[P1][Build Cleanup] Remove obsolete build pipeline...` | 待独立构建清理 PR | `./gradlew assembleDebug` | 待构建 PR CI | 移除过时 Makefile 与旧脚本 | 🟡 待构建 PR 实施后关闭 |
| **#44** | `[P1][Cleanup][Runtime] Purge obsolete sandbox remnants...` | 待 PR #78 (MCP 遗留清理) | `python scripts/check_docs_provenance.py` (PASS) | 待 PR #78 CI | 清理 `default_mount` 与旧 MCP 资产 | 🟡 随 PR #78 合并关闭 |
| **#52** | `[P0][Identity] Migrate Android package/application identity...` | 待独立身份迁移 PR | `./gradlew testDebugUnitTest` | 待身份 PR CI | 迁移为 `llc.slacker.minis`，执行独立导入迁移 | 🔵 独立立项实施 |
| **#40, #39** | `[Question] MCP 相关技术问题` | 已在 `docs/specs/` 与 PR #79 规范化 | N/A | N/A | 技术结论已在文档中明确 | 🟢 回复后关闭 |
| **#35, #34, #32, #31, #30, #28, #12, #11** | 其他特性与调研 Issue | 待对应 PR 实施 | 对应单元测试 | 待 CI 绿灯 | 依据各模块独立合同推进 | 🔵 保持 Open 跟踪 |

---

## 包名身份迁移与数据迁移测试规范（从 `dev.openminispet.android` 到 `llc.slacker.minis`）

因为包名（`applicationId`）从 `dev.openminispet.android` 切换为 `llc.slacker.minis` 属于**全新应用身份**（非普通同一应用签名升级覆盖），必须执行以下测试规范：

```text
[旧包: dev.openminispet.android] ──► [导出数据备份包 .zip] ──► [新包: llc.slacker.minis] ──► [Root 授权切换属主 UID] ──► [验证 Authority/DeepLink]
```

1. **旧包数据备份与导出（Export/Backup）**：
   - 旧包 `dev.openminispet.android` 在设置页触发“导出数据”，将 `/data/adb/minis` 中的 sessions、memory、skills 导出为备份包至共享存储或 SAF URI；
2. **新包全新安装（Clean Install）**：
   - 新包 `llc.slacker.minis` 独立安装于测试机，验证包名解析正确，无 UID 冲突；
3. **显式数据迁移与属主切换（Explicit Import/Migration）**：
   - 新包首次启动检测旧包数据，通过 Root 权限执行原子属主转移：`chown -R <new_app_uid>:<new_app_gid> /data/adb/minis`，并同步 sessions 元数据；
4. **系统组件契约验证**：
   - **Provider Authority**：验证 `llc.slacker.minis.fileprovider` 正确注册与解析；
   - **Deep Link**：验证 `minis://` URL Scheme 正确唤起新应用；
   - **系统角色绑定**：验证默认语音助手（Default Assistant）与无障碍服务绑定至新包名；
5. **失败回滚保护（Fail-Closed Rollback）**：
   - 模拟迁移中途断电或权限中断，验证旧包 `/data/adb/minis` 数据无损，新包回退至未初始化状态。

---

## 89 个文件拆分验收表 (Split Acceptance Table)

| 原文件路径 | 目标 PR | 保留/丢弃/延期 | 归类原因 | 来源 Commit | 验证命令 |
|---|---|---|---|---|---|
| `src/android/app/src/main/java/com/openminis/app/network/BoundedHttp.kt` | **PR 1 (BoundedHttp)** | 保留 | Loopback HTTP 安全分帧工具 | `853d28e` | `./gradlew testDebugUnitTest --tests *BoundedHttpTest*` |
| `src/android/app/src/test/java/com/openminis/app/network/BoundedHttpTest.kt` | **PR 1 (BoundedHttp)** | 保留 | BoundedHttp 单元测试 | `853d28e` | `./gradlew testDebugUnitTest --tests *BoundedHttpTest*` |
| `src/android/app/src/main/java/com/openminis/app/mcp/server/MCPServer.kt` | **PR 1 (BoundedHttp)** | 保留 | 集成 BoundedHttp 解析 | `853d28e` | `./gradlew testDebugUnitTest --tests *MCPServerTest*` |
| `src/android/app/src/test/java/com/openminis/app/mcp/server/MCPServerTest.kt` | **PR 1 (BoundedHttp)** | 保留 | MCP Server 网络测试 | `853d28e` | `./gradlew testDebugUnitTest --tests *MCPServerTest*` |
| `src/android/app/src/main/java/com/openminis/app/debug/DebugServer.kt` | **PR 1 (BoundedHttp)** | 保留 | Debug HTTP 安全分帧 | `853d28e` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/debug/DebugRPCHandler.kt` | **PR 1 (BoundedHttp)** | 保留 | Debug RPC 安全分帧 | `573380f` | `./gradlew testDebugUnitTest` |
| `.github/workflows/ci.yml` | **PR 2 (CI)** | 保留 | CI 全分支 Push 触发逻辑 | `24093ed` | `git push & check GitHub Actions` |
| `src/native/minisd/src/runtime_maintenance.rs` | **PR 3 (Runtime Maintenance & Rollback)** | 保留 | minisd 维护与状态机 | `1f02ac1` | `cargo test --manifest-path src/native/minisd/Cargo.toml` |
| `src/native/minisd/src/layout.rs` | **PR 3 (Runtime Maintenance & Rollback)** | 保留 | Rootfs 布局与回滚校验 | `573380f` | `cargo test --manifest-path src/native/minisd/Cargo.toml` |
| `src/native/minisd/src/lib.rs` | **PR 3 (Runtime Maintenance & Rollback)** | 保留 | minisd 模块导出 | `1f02ac1` | `cargo test --manifest-path src/native/minisd/Cargo.toml` |
| `src/native/minisd/src/main.rs` | **PR 3 (Runtime Maintenance & Rollback)** | 保留 | minisd 入口集成 | `1f02ac1` | `cargo test --manifest-path src/native/minisd/Cargo.toml` |
| `src/native/minisd/src/protocol.rs` | **PR 3 (Runtime Maintenance & Rollback)** | 保留 | 维护协议序列化 | `1f02ac1` | `cargo test --manifest-path src/native/minisd/Cargo.toml` |
| `src/native/minisd/src/dispatch.rs` | **PR 3 (Runtime Maintenance & Rollback)** | 保留 | 维护 RPC 分发 | `1f02ac1` | `cargo test --manifest-path src/native/minisd/Cargo.toml` |
| `src/native/minisd/src/state.rs` | **PR 3 (Runtime Maintenance & Rollback)** | 保留 | 维护状态机存储 | `1f02ac1` | `cargo test --manifest-path src/native/minisd/Cargo.toml` |
| `src/native/minisd/tests/contract.rs` | **PR 3 (Runtime Maintenance & Rollback)** | 保留 | 维护契约测试 | `1f02ac1` | `cargo test --manifest-path src/native/minisd/Cargo.toml` |
| `scripts/verify-runtime-payload.sh` | **PR 3 (Runtime Maintenance & Rollback)** | 保留 | APK 运行时资产校验 | `1f02ac1` | `bash scripts/test-runtime-payload-verification.sh` |
| `scripts/test-runtime-payload-verification.sh` | **PR 3 (Runtime Maintenance & Rollback)** | 保留 | 资产校验测试脚本 | `1f02ac1` | `bash scripts/test-runtime-payload-verification.sh` |
| `src/android/app/src/main/java/com/openminis/app/runtime/distribution/RuntimeDistributionManager.kt` | **PR 3 (Runtime Maintenance & Rollback)** | 保留 | 修复 previousIdentity 回滚 | `1f02ac1` | `./gradlew testDebugUnitTest --tests *RuntimeDistributionManagerTest*` |
| `src/android/app/src/main/java/com/openminis/app/runtime/distribution/RuntimePayloadVerifier.kt` | **PR 3 (Runtime Maintenance & Rollback)** | 保留 | Payload 资产哈希验证 | `1f02ac1` | `./gradlew testDebugUnitTest --tests *RuntimePayloadVerifierTest*` |
| `src/android/app/src/main/java/com/openminis/app/runtime/ubuntu/RootfsHealth.kt` | **PR 3 (Runtime Maintenance & Rollback)** | 保留 | Rootfs 健康度判定 | `1f02ac1` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/runtime/ubuntu/RuntimeProvision.kt` | **PR 3 (Runtime Maintenance & Rollback)** | 保留 | Rootfs 自举安装逻辑 | `1f02ac1` | `./gradlew testDebugUnitTest --tests *RuntimeProvisionTest*` |
| `src/android/app/src/main/java/com/openminis/app/runtime/ubuntu/UbuntuRuntime.kt` | **PR 3 (Runtime Maintenance & Rollback)** | 保留 | Ubuntu 运行时管理 | `1f02ac1` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/test/java/com/openminis/app/runtime/distribution/RuntimeDistributionManagerTest.kt` | **PR 3 (Runtime Maintenance & Rollback)** | 保留 | 回滚单测 | `1f02ac1` | `./gradlew testDebugUnitTest --tests *RuntimeDistributionManagerTest*` |
| `src/android/app/src/test/java/com/openminis/app/runtime/distribution/RuntimePayloadVerifierTest.kt` | **PR 3 (Runtime Maintenance & Rollback)** | 保留 | 验证器单测 | `81fe92f` | `./gradlew testDebugUnitTest --tests *RuntimePayloadVerifierTest*` |
| `src/android/app/src/test/java/com/openminis/app/runtime/ubuntu/RuntimeProvisionTest.kt` | **PR 3 (Runtime Maintenance & Rollback)** | 保留 | 自举单测 | `1f02ac1` | `./gradlew testDebugUnitTest --tests *RuntimeProvisionTest*` |
| `src/android/app/src/test/java/com/openminis/app/runtime/ubuntu/UbuntuRuntimeRecoveryTest.kt` | **PR 3 (Runtime Maintenance & Rollback)** | 保留 | 恢复单测 | `eb3248b` | `./gradlew testDebugUnitTest --tests *UbuntuRuntimeRecoveryTest*` |
| `src/native/minisd/src/workspace_file.rs` | **PR 4 (Workspace File RPC & TOCTOU)** | 保留 | 采用 dirfd/openat2 重构文件 RPC | `573380f` | `cargo test --manifest-path src/native/minisd/Cargo.toml` |
| `src/native/minisd/policy.app.json` | **PR 4 (Workspace File RPC & TOCTOU)** | 保留 | App 策略配置 | `1f02ac1` | `cargo test --manifest-path src/native/minisd/Cargo.toml` |
| `src/native/minisd/policy.default.json` | **PR 4 (Workspace File RPC & TOCTOU)** | 保留 | 默认策略配置 | `1f02ac1` | `cargo test --manifest-path src/native/minisd/Cargo.toml` |
| `src/native/minisd/src/config_proxy.rs` | **PR 4 (Workspace File RPC & TOCTOU)** | 保留 | Config 代理适配 | `1f02ac1` | `cargo test --manifest-path src/native/minisd/Cargo.toml` |
| `src/native/minisd/src/ipc_exec.rs` | **PR 4 (Workspace File RPC & TOCTOU)** | 保留 | IPC Exec 适配 | `eb3248b` | `cargo test --manifest-path src/native/minisd/Cargo.toml` |
| `src/native/minisd/src/ubuntu.rs` | **PR 4 (Workspace File RPC & TOCTOU)** | 保留 | Ubuntu 文件挂载 | `eb3248b` | `cargo test --manifest-path src/native/minisd/Cargo.toml` |
| `src/android/app/src/main/assets/minisd-policy.json` | **PR 4 (Workspace File RPC & TOCTOU)** | 保留 | Android 策略资产 | `1f02ac1` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/runtime/minisd/WorkspaceFileClient.kt` | **PR 4 (Workspace File RPC & TOCTOU)** | 保留 | Workspace 文件客户端 RPC | `573380f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/runtime/minisd/MinisdClient.kt` | **PR 4 (Workspace File RPC & TOCTOU)** | 保留 | Minisd 通信客户端 | `1f02ac1` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/runtime/minisd/MinisdProtocol.kt` | **PR 4 (Workspace File RPC & TOCTOU)** | 保留 | 文件协议结构定义 | `1f02ac1` | `./gradlew testDebugUnitTest --tests *MinisdProtocolTest*` |
| `src/android/app/src/main/java/com/openminis/app/tools/LinuxFileOpsTools.kt` | **PR 4 (Workspace File RPC & TOCTOU)** | 保留 | Linux 文件工具 | `573380f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/tools/FileReadTool.kt` | **PR 4 (Workspace File RPC & TOCTOU)** | 保留 | 读文件工具 | `573380f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/tools/FileWriteTool.kt` | **PR 4 (Workspace File RPC & TOCTOU)** | 保留 | 写文件工具 | `573380f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/tools/FileEditTool.kt` | **PR 4 (Workspace File RPC & TOCTOU)** | 保留 | 编辑文件工具 | `573380f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/test/java/com/openminis/app/runtime/minisd/MinisdProtocolTest.kt` | **PR 4 (Workspace File RPC & TOCTOU)** | 保留 | 协议单测 | `1f02ac1` | `./gradlew testDebugUnitTest --tests *MinisdProtocolTest*` |
| `src/android/app/src/main/java/com/openminis/app/ui/MinisImageFetcher.kt` | **PR 5 (UI Async Media & Markdown Routing)** | 保留 | Coil 异步媒体 Fetcher | `573380f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/ui/chat/ChatLinkResolver.kt` | **PR 5 (UI Async Media & Markdown Routing)** | 保留 | Session 附件 URL 路由 | `573380f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/ui/chat/ChatScreen.kt` | **PR 5 (UI Async Media & Markdown Routing)** | 保留 | ChatScreen 路由对接 | `573380f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt` | **PR 5 (UI Async Media & Markdown Routing)** | 保留 | ChatViewModel 状态对接 | `573380f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/ui/chat/StreamingMarkdownText.kt` | **PR 5 (UI Async Media & Markdown Routing)** | 保留 | 消除 UI 主线程 readToFileBlocking | `573380f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/ui/markdown/MarkdownText.kt` | **PR 5 (UI Async Media & Markdown Routing)** | 保留 | Markdown 渲染组件对接 | `573380f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/ui/navigation/AppNavigation.kt` | **PR 5 (UI Async Media & Markdown Routing)** | 保留 | 导航层对接 | `573380f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/webapp/AddToHomeSheet.kt` | **PR 5 (UI Async Media & Markdown Routing)** | 保留 | WebApp 挂载路由 | `573380f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/webapp/WebAppPathResolver.kt` | **PR 5 (UI Async Media & Markdown Routing)** | 保留 | WebApp 路径解析 | `573380f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/res/xml/file_provider_paths.xml` | **PR 5 (UI Async Media & Markdown Routing)** | 保留 | FileProvider 路径声明 | `573380f` | `./gradlew assembleDebug` |
| `docs/minis-seven-step-execution-plan.md` | **PR 5 (UI Async Media & Markdown Routing)** | 保留 | 七步执行计划基线 | `02b90f8` | `python scripts/check_docs_provenance.py` |
| `docs/specs/ui-architecture-issues.md` | **PR 5 (UI Async Media & Markdown Routing)** | 保留 | UI 架构缺陷工程议题 | `02b90f8` | `python scripts/check_docs_provenance.py` |
| `docs/specs/pr-and-issue-governance-plan.md` | **PR 5 (UI Async Media & Markdown Routing)** | 保留 | 全局治理计划规范 | `83527df` | `python scripts/check_docs_provenance.py` |
| `src/android/app/src/main/java/com/openminis/app/MinisApp.kt` | **PR 6 (Core Storage RPC Migration)** | 保留 | App 入口初始化 | `573380f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/agent/SoulStore.kt` | **PR 6 (Core Storage RPC Migration)** | 保留 | SoulStore 迁移至 RPC | `573380f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/data/ContextOffload.kt` | **PR 6 (Core Storage RPC Migration)** | 保留 | ContextOffload 迁移至 RPC | `573380f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/data/FileMentionIndex.kt` | **PR 6 (Core Storage RPC Migration)** | 保留 | FileMentionIndex 迁移至 RPC | `573380f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/data/SessionForkManager.kt` | **PR 6 (Core Storage RPC Migration)** | 保留 | SessionForkManager 迁移至 RPC | `573380f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/data/repository/MemoryRepository.kt` | **PR 6 (Core Storage RPC Migration)** | 保留 | MemoryRepository 迁移至 RPC | `573380f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/data/repository/SkillRepository.kt` | **PR 6 (Core Storage RPC Migration)** | 保留 | SkillRepository 迁移至 RPC | `573380f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/browser/BrowserTabPool.kt` | **PR 7 (Guest Offload Handlers Migration)** | 保留 | BrowserTabPool 迁移至 RPC | `573380f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/browser/BrowserUseManager.kt` | **PR 7 (Guest Offload Handlers Migration)** | 保留 | BrowserUseManager 迁移至 RPC | `573380f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/offload/MediaPlayerManager.kt` | **PR 7 (Guest Offload Handlers Migration)** | 保留 | MediaPlayerManager 迁移至 RPC | `573380f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/provider/ImageBudget.kt` | **PR 7 (Guest Offload Handlers Migration)** | 保留 | ImageBudget 迁移至 RPC | `573380f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/runtime/guest/BrowserUseOffloadHandler.kt` | **PR 7 (Guest Offload Handlers Migration)** | 保留 | BrowserUseHandler 迁移至 RPC | `573380f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/runtime/guest/ConfigOffloadHandler.kt` | **PR 7 (Guest Offload Handlers Migration)** | 保留 | ConfigHandler 迁移至 RPC | `573380f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/runtime/guest/ModelUseOffloadHandler.kt` | **PR 7 (Guest Offload Handlers Migration)** | 保留 | ModelUseHandler 迁移至 RPC | `573380f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/runtime/guest/PhotosOffloadHandler.kt` | **PR 7 (Guest Offload Handlers Migration)** | 保留 | PhotosHandler 迁移至 RPC | `573380f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/runtime/guest/SpeechOffloadHandler.kt` | **PR 7 (Guest Offload Handlers Migration)** | 保留 | SpeechHandler 迁移至 RPC | `573380f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/tools/ReadImageTool.kt` | **PR 8 (Tool & Permission Gate Migration)** | 保留 | ReadImageTool 迁移至 RPC | `573380f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/tools/SessionPermissionStore.kt` | **PR 8 (Tool & Permission Gate Migration)** | 保留 | SessionPermission 迁移至 RPC | `573380f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/tools/android/AndroidAgentTools.kt` | **PR 8 (Tool & Permission Gate Migration)** | 保留 | AgentTools 迁移至 RPC | `573380f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/tools/android/AndroidApkInspector.kt` | **PR 8 (Tool & Permission Gate Migration)** | 保留 | ApkInspector 迁移至 RPC | `573380f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/tools/android/AndroidCapabilityResolver.kt` | **PR 8 (Tool & Permission Gate Migration)** | 保留 | CapabilityResolver 迁移至 RPC | `573380f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/tools/android/AndroidLogManager.kt` | **PR 8 (Tool & Permission Gate Migration)** | 保留 | LogManager 迁移至 RPC | `573380f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/tools/android/AndroidUiController.kt` | **PR 8 (Tool & Permission Gate Migration)** | 保留 | UiController 迁移至 RPC | `573380f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/tools/internal/FileMutationQueue.kt` | **PR 8 (Tool & Permission Gate Migration)** | 保留 | FileMutationQueue 迁移至 RPC | `573380f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/tools/runtime/ToolRegistry.kt` | **PR 8 (Tool & Permission Gate Migration)** | 保留 | ToolRegistry 迁移至 RPC | `573380f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/test/java/com/openminis/app/tools/runtime/LinuxPythonRunHandlerCleanupTest.kt` | **PR 8 (Tool & Permission Gate Migration)** | 保留 | 工具清理测试 | `573380f` | `./gradlew testDebugUnitTest --tests *LinuxPythonRunHandlerCleanupTest*` |
| `src/android/app/src/main/java/com/openminis/app/sandbox/RootfsManager.kt` | **PR 9 (Sandbox & File Browser UI)** | 保留 | RootfsManager 视图模型对接 | `eb3248b` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/ui/sandbox/FileBrowserScreen.kt` | **PR 9 (Sandbox & File Browser UI)** | 保留 | 文件浏览器 UI 对接 RPC | `573380f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/ui/sandbox/FileBrowserViewModel.kt` | **PR 9 (Sandbox & File Browser UI)** | 保留 | 文件浏览器 ViewModel 对接 RPC | `573380f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/ui/sandbox/FilePreviewScreen.kt` | **PR 9 (Sandbox & File Browser UI)** | 保留 | 文件预览 UI 对接 RPC | `573380f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/ui/sandbox/MirrorSettingsScreen.kt` | **PR 9 (Sandbox & File Browser UI)** | 保留 | 镜像设置 UI 对接 | `eb3248b` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/ui/sandbox/RootfsManagementViewModel.kt` | **PR 9 (Sandbox & File Browser UI)** | 保留 | Rootfs 视图模型对接 | `eb3248b` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/ui/settings/StorageManagementScreen.kt` | **PR 9 (Sandbox & File Browser UI)** | 保留 | 存储管理 UI 对接 | `573380f` | `./gradlew testDebugUnitTest` |
| `src/android/app/src/main/java/com/openminis/app/tools/ExternalMountAccess.kt` | **PR 10 (External Mount & Gap Contract)** | 保留 | 外部挂载权限类 | `573380f` | `./gradlew testDebugUnitTest` |
| `docs/contracts/06-CURRENT-GAPS.md` | **PR 10 (External Mount & Gap Contract)** | 保留 | 当前差距合同更新 | `1f02ac1` | `python scripts/check_docs_provenance.py` |
