# Minis for Android — PR 与 Issue 全局治理与收敛实施方案 (V2)

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
| **#92** | `feat/issue-51-provision-rollback` | `02b90f8` | +8464/-2329 | 86 个文件的多模块巨石变更 | 全量拆解为 6 个独立 PR 后归档关闭 |

---

## 86 个文件全量拆解矩阵（大 PR #92 分解方案）

将 `feat/issue-51-provision-rollback` 分支的 86 个变更文件严格拆解为 6 个高内聚的小 PR，**确保不丢弃任何有效代码**：

```text
                                  [PR #92 86 文件全量拆解]
                                             │
      ┌──────────────────┬───────────────────┼───────────────────┬──────────────────┐
      ▼                  ▼                   ▼                   ▼                  ▼
【PR 1: HTTP 解析】 【PR 2: CI 触发】   【PR 3: Runtime 事务】 【PR 4: 文件 RPC】 【PR 5: UI 异步与媒体】
 (6 个文件)         (1 个文件)          (18 个文件)          (14 个文件)         (12 个文件)
                                                                                    │
                                                                                    ▼
                                                                           【PR 6: 存储管理改造】
                                                                            (35 个文件)
```

### 1. PR 1: `fix(http): frame loopback HTTP bodies by byte length and bound headers` (6 个文件)
- `src/android/app/src/main/java/com/openminis/app/network/BoundedHttp.kt` [NEW]
- `src/android/app/src/test/java/com/openminis/app/network/BoundedHttpTest.kt` [NEW]
- `src/android/app/src/main/java/com/openminis/app/mcp/server/MCPServer.kt`
- `src/android/app/src/test/java/com/openminis/app/mcp/server/MCPServerTest.kt`
- `src/android/app/src/main/java/com/openminis/app/debug/DebugServer.kt`
- `src/android/app/src/main/java/com/openminis/app/debug/DebugRPCHandler.kt`

### 2. PR 2: `ci: run checks on every branch push` (1 个文件)
- `.github/workflows/ci.yml`

### 3. PR 3: `feat(runtime): transactional rootfs deployment with robust rollback and pending recovery` (18 个文件)
- `src/native/minisd/src/runtime_maintenance.rs` [NEW]
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
- `src/android/app/src/test/java/com/openminis/app/runtime/ubuntu/UbuntuRuntimeRecoveryTest.kt`

### 4. PR 4: `feat(security): atomic dirfd-relative workspace file operations to prevent TOCTOU symlink escape` (14 个文件)
- `src/native/minisd/src/workspace_file.rs` [NEW - 采用 dirfd/openat2 重构]
- `src/native/minisd/policy.app.json`
- `src/native/minisd/policy.default.json`
- `src/native/minisd/src/config_proxy.rs`
- `src/native/minisd/src/ipc_exec.rs`
- `src/native/minisd/src/ubuntu.rs`
- `src/android/app/src/main/assets/minisd-policy.json`
- `src/android/app/src/main/java/com/openminis/app/runtime/minisd/WorkspaceFileClient.kt` [NEW]
- `src/android/app/src/main/java/com/openminis/app/runtime/minisd/MinisdClient.kt`
- `src/android/app/src/main/java/com/openminis/app/runtime/minisd/MinisdProtocol.kt`
- `src/android/app/src/main/java/com/openminis/app/tools/LinuxFileOpsTools.kt`
- `src/android/app/src/main/java/com/openminis/app/tools/FileReadTool.kt`
- `src/android/app/src/main/java/com/openminis/app/tools/FileWriteTool.kt`
- `src/android/app/src/main/java/com/openminis/app/tools/FileEditTool.kt`

### 5. PR 5: `feat(ui): async guest media loading and session-scoped attachment routing` (12 个文件)
- `src/android/app/src/main/java/com/openminis/app/ui/MinisImageFetcher.kt`
- `src/android/app/src/main/java/com/openminis/app/ui/chat/ChatLinkResolver.kt`
- `src/android/app/src/main/java/com/openminis/app/ui/chat/StreamingMarkdownText.kt` (消除 `readToFileBlocking`)
- `src/android/app/src/main/java/com/openminis/app/ui/markdown/MarkdownText.kt`
- `src/android/app/src/main/java/com/openminis/app/ui/chat/ChatScreen.kt`
- `src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt`
- `src/android/app/src/main/java/com/openminis/app/ui/navigation/AppNavigation.kt`
- `src/android/app/src/main/java/com/openminis/app/webapp/AddToHomeSheet.kt`
- `src/android/app/src/main/java/com/openminis/app/webapp/WebAppPathResolver.kt`
- `src/android/app/src/main/res/xml/file_provider_paths.xml`
- `docs/specs/ui-architecture-issues.md`
- `docs/minis-seven-step-execution-plan.md`

### 6. PR 6: `refactor(sandbox): migrate file browser and guest tools to canonical workspace RPC` (35 个文件)
- 沙箱界面：`FileBrowserScreen.kt`, `FileBrowserViewModel.kt`, `FilePreviewScreen.kt`, `RootfsManagementViewModel.kt`, `MirrorSettingsScreen.kt`, `StorageManagementScreen.kt`, `RootfsManager.kt`
- 核心存储：`SoulStore.kt`, `MemoryRepository.kt`, `SkillRepository.kt`, `ContextOffload.kt`, `FileMentionIndex.kt`, `SessionForkManager.kt`, `MinisApp.kt`
- Guest Offload 处理器：`BrowserUseOffloadHandler.kt`, `ConfigOffloadHandler.kt`, `ModelUseOffloadHandler.kt`, `PhotosOffloadHandler.kt`, `SpeechOffloadHandler.kt`, `MediaPlayerManager.kt`, `BrowserTabPool.kt`, `BrowserUseManager.kt`, `ImageBudget.kt`
- 工具与权限：`AndroidApkInspector.kt`, `AndroidCapabilityResolver.kt`, `AndroidLogManager.kt`, `AndroidUiController.kt`, `AndroidAgentTools.kt`, `ExternalMountAccess.kt` [NEW], `ReadImageTool.kt`, `SessionPermissionStore.kt`, `ToolRegistry.kt`, `FileMutationQueue.kt`
- 文档：`docs/contracts/06-CURRENT-GAPS.md`

---

## PR 依赖有向无环图（DAG）与合入路线

```text
Master ([master 分支])
  ├──► PR 1: BoundedHttp ──► PR 3: Runtime 事务与回滚 ──► PR 4: 防 TOCTOU 文件 RPC
  │                                                            ├──► PR 5: UI 异步媒体 & 附件 ──► [关闭旧大 PR #92]
  │                                                            └──► PR 6: 沙箱与存储 RPC 迁移 ──► [关闭旧大 PR #92]
  ├──► PR 2: CI Triggers
  └──► PR #78: MCP 遗留清理 ──► PR #79: MCP App-only 热重载 ──► PR #75: 鉴权 Pre-exec Marker ──► PR #82: MCP Server UI

[关闭旧大 PR #92] ──► 独立 PR: llc.slacker.minis 包名迁移
```

---

## 18 个 Issue 闭环矩阵与四维门槛

关闭任何 Issue 必须同时满足：**代码 PR 合并 + 本地测试全绿 + 远端 CI 绿灯 + 真机/契约证据**。

| Issue 编号 | 标题 | 核心解决依据与关联 PR | 门槛达成状态 | 处置动作 |
|---|---|---|---|---|
| **#50** | `[P0][Storage] Ubuntu workspace lands on tmpfs_data...` | `docs/contracts/03-STORAGE-CONTRACT.md` + `layout.rs` 拒绝 tmpfs | 🟢 四维全部满足 | **已附证据正式关闭** |
| **#45** | `[Security/UX] Add Standard Mode and Full Access Mode...` | `docs/contracts/04-SECURITY-CONTRACT.md` + `PrivilegedAccessMode.kt` + `root.exec` | 🟢 四维全部满足 | **已附证据正式关闭** |
| **#56** | `[P1][Docs/Provenance] Separate current documentation...` | `PROVENANCE.md` + `docs/contracts/00-IDENTITY.md` + PR #78 | 🟡 待 PR #78 合入 | 待 PR #78 合入 master 后关闭 |
| **#43** | `[P0][Runtime] Ubuntu/minisd recovery is incomplete...` | `RuntimeDistributionManager.kt` + PR 3 (Runtime 事务) | 🟡 待 PR 3 合入 | **严禁提前关**，待 PR 3 合并后关闭 |
| **#55** | `[P1][Storage] Re-audit legacy external-storage permissions...` | `ExternalMountAccess.kt` + SAF `/var/minis/mounts/` 挂载方案 | 🟡 进行中 | 待外部挂载 PR 实施后关闭 |
| **#53** | `[P1][Build Cleanup] Remove obsolete build pipeline...` | 独立构建清理 PR（替代旧 PR #61） | 🟡 待提交 | 待构建清理 PR 实施后关闭 |
| **#44** | `[P1][Cleanup][Runtime] Purge obsolete sandbox remnants...` | PR #78 (MCP 遗留清理) | 🟡 待 PR #78 合入 | 随 PR #78 合并关闭 |
| **#52** | `[P0][Identity] Migrate Android package/application identity...` | 独立 `feat/issue-52-slacker-identity` PR（目标 `llc.slacker.minis`） | 🔵 独立立项 | 待完整身份迁移 PR 完成后关闭 |
| **#40** | `[Question] LOCAL_ONLY 策略的判定依据` | `docs/specs/external-mcp-tools-list-contract.md` | 🟢 已有规范结论 | 回复技术结论后标记 Answered 关闭 |
| **#39** | `[Question] McpRpcMethods 的长期支持意向` | PR #79 (MCP 热重载实现) | 🟡 待 PR #79 合入 | 随 PR #79 补充说明后关闭 |
| **#35** | `[UI][Session] Advanced settings UI` | 后续 Session UI 重构 | 🔵 待排期 | 纳入 UI 治理里程碑 |
| **#34** | `[Feature Request] Expose Minis as an MCP server` | PR #82 (MCP Server UI) | 🔵 待排期 | 待 PR #82 实施 |
| **#32** | `[Feature][Session] Per-session agent overrides` | Session Override 合同 | 🔵 待排期 | 待排期推进 |
| **#31, #30, #28** | `[Investigation] Memory / Cold-start / Transport Profiling` | PR #80, PR #81 分析跟踪 | 🔵 调研基线 | 保持 Open，待真机 Trace 补充 |
| **#12** | `[P1][build] Make unavailable provider customization fail explicitly` | PR #65 (Provider Capabilities) | 🟡 待重整 PR | 待重整后实施关闭 |
| **#11** | `[P1][compatibility] Stop using mediaPlayback FGS type` | PR #67 (FGS Lifecycle) | 🟡 待重整 PR | 待重整后实施关闭 |

---

## 全面真机验收与失败路径（Negative Paths）测试矩阵

| 验证模块 | 测试项与具体用例 | 预期行为（必须满足） | 验证手段 |
|---|---|---|---|
| **Rootfs 升级与回滚** | 新旧版本 upstream/release 不一致时的回滚测试 | 升级中途模拟 kill 进程，重启后必须能基于旧版本自身的元数据成功回滚到 previous 槽位，不报错 | 真机模拟注入故障 |
| **文件 RPC 安全防护** | 父目录并发 Symlink-Swap 攻击测试 (TOCTOU) | 特权 Broker 在读写/删除时，若父目录被替换为宿主软链接，必须立即拦截报错，严禁越界操作宿主文件 | Rust 自动化渗透用例 |
| **HTTP 安全边界** | 超大 Payload (10MB+) 与畸形 Header 注入 | `BoundedHttp` 返回 413 Payload Too Large / 400 Bad Request，连接立即断开，不发生内存溢出 | JVM 单元测试与网络注入 |
| **Markdown 附件分流** | 无 Session 上下文的 `minis://attachments/*` 预览 | 优雅降级并返回占位图，或重定向至公共 Scope，禁止抛出 `BAD_PARAMS` 异常 | UI 测试与手动预览 |
| **包名身份迁移覆盖** | 从 `dev.openminispet.android` 升级至 `llc.slacker.minis` | 验证 Provider Authority 派生正确、Deep Link 路由正常、FileProvider 正常拉起、旧数据导出或平滑迁移 | 真机覆盖安装测试 |
| **SELinux 兼容性** | 在 SELinux Enforcing 模式下运行 Rootfs 与 minisd | `minisd` 正常建立 Mount Namespace 与 chroot，无 SELinux AVC 拒绝日志阻断 | `adb shell dmesg \| grep avc` |
