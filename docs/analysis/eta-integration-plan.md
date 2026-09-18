# Eta 能力移植计划

本文件记录从外部参考项目 Eta（https://github.com/Mangi-11/Eta）选择性吸收能力的范围、分类与验证方式。
它不是产品说明，也不是长期合同；长期行为边界仍以 `docs/contracts/` 为准。

## 参考关系与法律边界

Eta 使用 **PolyForm Noncommercial License 1.0.0**，属于非商业许可；本仓库继续按 GPL-3.0 分发。
该许可允许**复制、修改、派生与分发**，条件是按非商业目的使用，并让接收方同时拿到许可条款与
`Required Notice`。因此本仓库**直接复用 Eta 的实现代码**，做法是：

- 直接移植需要的源文件，按本仓库的包结构、API 与测试边界适配；
- 许可条款与 Required Notice 集中放在 `third_party/eta/LICENSE`，归属与移植范围集中记录在
  `PROVENANCE.md` 与 `THIRD_PARTY_LICENSES.md`，**不在每个代码文件里重复写许可头**；
- 移植进来的模块按 PolyForm Noncommercial 1.0.0 使用与分发，不纳入本仓库的 GPL-3.0 授权范围；
  仓库其余部分仍是 GPL-3.0，不改变既有许可证。

因此使用与分发本仓库时必须同时满足两条：仓库原有部分遵循 GPL-3.0，移植自 Eta 的部分遵循
PolyForm Noncommercial 1.0.0（非商业用途）。

`src/native`、`runtime/ubuntu`、`runtime/files`、rootfs payload 与安全存储相关代码额外适用本项目
现有合同：不得为此引入 PRoot 方案、不引入 Alpine 方案、不恢复旧 broker，也不把 Root 能力扩展成
模型可用的通用 shell 入口。

## 分类口径

沿用 `docs/contracts/09-UPSTREAM-SYNC.md` 的分类方式，便于复核：

| 类别 | 含义 | 处理方式 |
|---|---|---|
| A | 纯 Kotlin/算法/协议解析，与 Root 和存储合同无关 | 按本仓库包结构独立实现，配单元测试 |
| B | 需要本地适配（生命周期、权限、Room、网络、文件工具） | 先写行为测试，再按当前调用链实现 |
| C | 与本项目合同冲突的既有实现方式 | 不吸收该实现方式，只吸收问题定义 |
| D | 依赖真机/ROM/Root 行为 | 只做可测部分，真机结论单独留存证据 |

## 已完成

- 从 `origin/main`（`69e05e51`）切出分支 `codex/eta-capability-integration`，独立工作树
  `/home/jiale/projects/minis-eta-integration`；脏工作树 `codex/audit-work` 未受影响。
- 基线可验证：`./gradlew :app:testDebugUnitTest --no-daemon --max-workers=1` 通过。

## 本分支已落地的改动

| 改动 | 类型 | 说明 | 验证 |
|---|---|---|---|
| MCP 客户端响应上限 | 安全/稳定 | `MCPHttpTransport` 之前整体读入响应；现按 4 MiB 预算读取，先查 `Content-Length` 再按流分块计数，超限失败关闭 | `MCPHttpTransportBoundedResponseTest` 4 例 |
| MCP `tools/list` 参数 schema | 互操作 | 规范字段是 `inputSchema`，只读 `input_schema` 会让符合规范的服务端丢掉全部参数 schema；现两种拼写都接受，规范优先 | 契约夹具测试更新 + 新增双拼写用例 |
| 工具入参 schema 校验 | 能力 | preflight 只查必填与空串；新增 `ToolCallValidator`，校验标量类型、`enum`、数组元素、嵌套对象必填键，超过 256 KiB / 嵌套超过 32 层 / 声明类型未知时失败关闭 | `ToolCallValidatorTest` 15 例 + preflight 接线 3 例 |
| 技能 ZIP 导入上限与回滚 | 安全/稳定 | 按压缩流 32 MiB、单条目 4 MiB、总量 16 MiB 约束，拒绝绝对路径、`..`、反斜杠、盘符、NUL、重复条目 | `SkillArchiveReaderTest` 18 例 |
| 技能安装事务 | 安全/稳定 | `SkillPackageInstaller` 暂存 → 原子提交 → 回滚，`SkillRecoveryJournal` 恢复日志，`SkillMutationLock` 跨进程文件锁，`SkillTransactionStore` guest 文件适配；`SkillRepository` 的 add/update/rename/delete/rescan/import/读路径全部走同一事务与同一把锁，恢复未完成时 fail-closed | 编译通过；本轮未跑测试 |
| 备份恢复走同一事务 | 安全/稳定 | `BackupImporter.importSkills` 原来直写 guest 目录与 skills 表；现按技能分组走 `SkillRepository.restoreFromBackup`（`SkillInstallBase.PRESERVE`），条目/路径/字节预算与回滚沿用事务 | 编译通过；本轮未跑测试 |
| 上下文压缩批次边界与摘要校验 | 能力/稳定 | `AgentContextBudget` + `AgentContextCompactor`（canSplit/planRange/chunkForSummary/validateSummary/hasReduction）；后续把 `chunkForSummary` 变成真正的多次摘要调用（上一个 chunk 的摘要作为 `previousSummary` 前传）、溢出对半改用 `MAX_OVERFLOW_ATTEMPTS`、压缩区间**起点**也回退到安全批次边界 | 特性分支上有 `AgentContextCompactorTest`；本轮集成未复跑 |
| GUI 动作证据语义与迟到调用门 | 能力/稳定 | `AndroidUiActionEvidence`（五态 + 来源写进工具 JSON）、`MainThreadCallGate` / `MainThreadCallBridge`（手势、pinch、back/home、CLI key 共用）、`set_text` 读回校验（密码字段不回读） | 编译通过；本轮未跑测试 |
| 截图与 UI 观察窗口一致性 | 安全/稳定 | `ScreenshotWindowPolicy` / `PackageWindowVisibilityResolver` 统一到 `MinisAccessibilityService.visibleWindowSet()`：observe/screenshot/ref 解析/锚点采样共用判定，不可解析包名或窗口表读不到时拒绝并给出 UI_WINDOW_* / SCREENSHOT_* 错误；截断快照不再被当成“未变化” | 编译通过；真机窗口集合未验证 |
| CLI 解析器接线 | 能力 | `FocusedWindowParser` 接入 `android-shizuku-cli activity top` 的 `dumpsys window` 兜底（新增 source/component），`AndroidDisplaySizeParser` 接入 `display list` 的逻辑分辨率（`displays` + logicalWidth/Height + sizeOverridden） | 编译通过；设备输出差异未验证 |
| 敏感工具不落盘 | 安全/隐私 | `ToolSensitivePolicy` 三个落盘点（Room transcript、运行检查点 transcript、上下文快照）替换为占位符；按注册表同款归一化匹配，并修正此前写成 `android_clipboard` 等**从未匹配任何已注册工具**的名字 | 编译通过；本轮未跑测试 |
| 运行检查点与恢复对账 | 能力/恢复 | `RunCheckpointStore` / `RunCheckpointRecorder` / `RunContextSnapshot` / `RunRecoveryCoordinator` + `ChatViewModel` 运行开始、落库、终态与加载对账接线 | 编译通过；本轮未跑测试 |
| 记忆注入预算补完 | 能力/隐私 | `MemoryInjectionBudget` 增加内容 `revision`（SHA-256），注入片段带 `revision/bytes/core_budget_chars` 头部，正文沿用窗口预算 + 截断标记 + 标题索引 | 编译通过；本轮未跑测试 |
| 模型失败分类与重试纪律 | 能力/稳定 | 新增 `LLMFailureClassifier`（可重试／不可重试／上下文溢出 + `decide()`），`LLMRetryPolicy.isRetryable` 委托给它；已产生副作用（托管工具已启动或输出已提交）后失败即终态、重试前丢弃失败轮思考、退避可取消、溢出走压缩后重试 | 编译通过；本轮未跑测试 |
| 摘要输入接入已有剪枝器 | 稳定 | `ToolResultPruner` 接入 `buildConversationTextForSummary`，避免把整段工具结果灌进摘要请求 | 编译通过；本轮未跑测试 |

本轮集成的验证口径：三个特性工作树与后续五项各自 `:app:compileDebugKotlin`（用 `flock` 串行）通过后提交，集成分支 cherry-pick 后每次都再次 `:app:compileDebugKotlin` 通过并推送（`f21526f3 → 73d4c579`）。**本轮没有运行任何单元测试**；表中带测试名的条目是它们在特性分支/基线上的历史结论，不是本轮复验。没有真机、Root、SELinux 或 OEM 生命周期结论。

## 领域对照

三个领域的逐项对照与可施工规格分别记录在：

- `docs/analysis/eta-agent-runtime.md`
- `docs/analysis/eta-skills-mcp-backup.md`
- `docs/analysis/eta-ui-gui-root.md`

## 验证

```bash
python3 scripts/check_docs_provenance.py
cd src/android && flock /tmp/minis-gradle.lock ./gradlew :app:compileDebugKotlin --no-daemon --max-workers=1
```

真机、Root、SELinux、OEM 生命周期相关结论不使用宿主测试代替。

## 后续建议顺序

已完成（本轮）：技能安装事务（含备份恢复）、GUI 动作证据与迟到调用门、截图与观察窗口一致性（代码层）、压缩批次边界与摘要校验（含 chunk 驱动调用）、敏感工具不落盘、运行检查点与恢复对账、记忆注入预算、模型失败分类与重试纪律。

仍建议继续：

1. 真机验收本轮移植项：窗口集合与截图包含关系、技能事务的 rename/fsync 行为、进程死亡后的检查点对账与 Resume 文案、设备端 `dumpsys window` / `wm size` 输出差异。
2. 敏感工具分类表按工具目录持续补录（新增工具时）；`ChatRepository.summarizeToolUse` 写入 `chat_sessions.last_message_preview` 的 100 字符预览仍未脱敏，需要产品决定是否收敛。
3. 流式 Markdown 的未闭合结构投影与启动健康状态聚合视图（与 Eta 移植无关，见 06-CURRENT-GAPS）。
