# Minis 七步执行计划

状态：执行基线

本文件把历史对话中确定的七步计划，结合当前仓库和开放 PR 的真实状态，整理为可执行的工作顺序。合同文件描述目标，`docs/contracts/06-CURRENT-GAPS.md` 描述现状；本文件不把未验证行为写成已完成。

## 原始七步

1. 修复 DebugServer/MCP 中文请求超时和截断请求问题。
2. 修复 broker 可用但 App 无 `su` 时 Ubuntu 无法启动的问题。
3. 拆分 Root、broker、rootfs、Ubuntu 的诊断状态。
4. 完成 canonical 存储和外部挂载安全收尾。
5. MCP 配置只由 Android App 管理，清除废弃 Ubuntu MCP 路径。
6. 重建或关闭 15 个过期 Draft PR，修复 #78、#79 CI。
7. 完整运行本地 CI 和 Root 真机验收。

## 当前基线

- 当前工作分支：`feat/issue-51-provision-rollback`。
- 当前 HEAD：`81fe92f`。
- 当前开放的非 Draft PR：#92。
- PR #92 的最新有效 CI 已通过，但 PR 仍落后 `master`，且尚未经过完整代码审查收口。
- PR #92 包含 86 个文件，混合了 HTTP、CI、runtime 自举/回滚、canonical 文件 RPC、外部路径和 UI 适配，不能作为一个整体直接合并。
- 当前有 15 个开放 PR；除 #92 外均为 Draft，其中既有过时分支，也有被后续实现部分或全部覆盖的分支。
- `docs/contracts/06-CURRENT-GAPS.md` 仍保留真机首装、升级、中断恢复、provision、SELinux、非 tmpfs、目标内核和真实 Android 工具行为等门禁。

### 已发现的 #92 阻塞

1. `src/native/minisd/src/workspace_file.rs` 先按路径检查父目录，再重新按路径执行读写、复制、移动和删除；父目录被替换为 symlink 时，特权 broker 可能越界访问 host 文件。
2. `src/android/app/src/main/java/com/openminis/app/ui/chat/StreamingMarkdownText.kt` 在 Compose `remember` 中同步调用 broker，并可能执行最多 50 MiB 的媒体传输，存在主线程卡顿或 ANR 风险。
3. `minis://attachments/*` 的无 session 渲染路径把空字符串传给 `workspace.file`，而 broker 对 session workspace 要求有效 `session_id`，导致通用 Markdown/Coil 渲染失败。
4. `RuntimeDistributionManager` 用目标 APK manifest 校验 previous rootfs。升级版本变化后，合法的旧 rootfs 可能因此不能被确认并回滚。

CI 通过不能替代上述安全、并发、升级和真机行为验证。

## 不可违反的边界

- Linux 用户数据真源固定为 `/data/adb/minis/{workspace,sessions,memory,skills,shared,home}`。
- 可替换 rootfs 不得覆盖、删除或参与替换用户数据目录。
- 新的 Root 执行必须走 minisd 结构化 RPC，不新增模型可控的 `su -c` 通道。
- 不通过关闭 SELinux、放宽策略或 debug 签名绕过问题。
- 一次 PR 只负责一条合同边界；不把包名迁移、runtime、存储和 MCP 混在一起。
- 安全实现必须有拒绝、越界、失败关闭和恢复失败测试。
- 不 force-push、不重写历史、不丢弃用户未提交改动、不整包硬合旧 Draft PR。
- SAF 授权和 raw POSIX 访问能力是两个条件，不能互相冒充。
- 外部 SAF 目录必须能够出现在 Ubuntu 的 `/var/minis/mounts/<name>`，但不能因此开放任意 host 路径或任意 mount 参数。

## 执行顺序

### 0. 建立干净合并基线

目标：不要继续在当前 86 文件的大 PR 上叠加新合同。

动作：

- 以最新 `master` 为基础建立新的短分支。
- 只从 #92 搬运已经验证过的逻辑，不直接整包 cherry-pick 未审查的 #92。
- 先处理与 runtime 无关的独立变更，再重新建立 runtime 和 workspace PR。
- 每个替代 PR 都必须明确保留的旧行为、删除的死路径和对应测试。

非目标：本阶段不迁移 Android applicationId/Java 包名，不修改用户数据，不关闭仍需保留的 Issue。

### 1. 重建原计划第 1 步和 #92 的独立部分

#### 1.1 HTTP framing PR

从 `853d28e` 的逻辑重建独立 PR，范围只包括：

- `BoundedHttp`。
- DebugServer 的有界 HTTP 请求解析。
- MCPServer 的 loopback HTTP 读取。
- UTF-8 按字节长度计算。
- conflicting `Content-Length`、`Transfer-Encoding`、header/body 上限和截断请求的拒绝测试。

证明：Android JVM tests、lint、DebugServer/MCPServer 相关现有测试和 docs provenance checks。

#### 1.2 CI trigger PR

单独评估 `24093ed`，只改变 branch push 的 CI 触发行为。不得把一次性验证脚本或 runtime 行为混入这个 PR。

证明：workflow YAML 校验和一次 branch push/PR check 结果。

#### 1.3 Runtime 自举、升级和回滚 PR

保留 `runtime.maintenance` 的结构化事务闭环，但修复 previous identity 校验：

- pending 必须保存目标 identity 和 previous identity。
- previous rootfs 必须按自身保存的 release、profile、upstream、revision、archive digest 校验。
- 目标 APK manifest 只能校验目标 rootfs，不能作为 previous rootfs 的身份。
- 目标版本变化、provision 失败、keeper 启动失败时，合法 previous rootfs 必须能够回滚。
- 交换结果未知时保留 pending，不能清除状态或盲目继续。

相关代码：`RuntimeDistributionManager.kt` 的 identity、pending 和 rollback 路径，以及已有 runtime distribution tests。

证明：至少覆盖旧版本到新版本的真实 identity 差异、切换后 provision 失败、回滚成功、回滚身份不匹配时 fail-closed、pending 保留和 App 被杀后的恢复。

#### 1. Canonical `workspace.file` PR

重新实现 `573380f` 的目标，但不接受当前 pathname check 后重新 open 的安全模型：

- 优先使用 `openat2` 的 `RESOLVE_BENEATH | RESOLVE_NO_SYMLINKS | RESOLVE_NO_MAGICLINKS`。
- 对目标 Android 内核不具备 `openat2` 时，使用 dirfd + `openat` + `O_NOFOLLOW` 逐段遍历。
- 读、写、append、copy、move、delete、list、info 都必须遵守同一安全边界。
- 保持 path、大小、session、文件类型和 owner/mode 限制。
- 添加父目录 symlink-swap 和目标替换的否定测试。

同时修复两个已发现的 Android 回归：

- `StreamingMarkdownText.kt` 的媒体 staging 改为 coroutine-backed state，禁止在 Compose composition 中 `runBlocking` broker I/O。
- Markdown/Coil 的 session-scoped attachment URL 必须携带真实 session identity；无 session 的 URL 只能访问明确允许的 global scope。

证明：Rust contract tests、workspace file tests、Android JVM tests，以及覆盖媒体渲染和 sessionless/global URL 的测试。

#### 1.5 关闭旧 #92

以上替代 PR 进入当前 `master` 后，关闭 #92，并在关闭说明中链接替代 PR。保留 #92 的 CI 和审查记录，不改写其历史。

### 2. 原计划第 3 步：拆分诊断状态

单独建立 diagnostics PR，不继续扩大 runtime 事务 PR。

目标状态模型至少独立表达：

- Root probe：UID/GID、capabilities、SELinux。
- Broker：socket reachable、peer identity、policy、运行 binary identity。
- Rootfs：missing、corrupt、incompatible、healthy、unknown。
- Keeper/Ubuntu：stopped、starting、running、namespace lost、layout mismatch。
- Provision：未执行、进行中、失败、成功和 revision。

必须修复：

- broker 可用但 rootfs 损坏时，不显示成统一 `Root unavailable`。
- `UbuntuRuntime.Snapshot` 的 RPC 失败不能抹掉所有之前已知状态。
- `RootfsManager` 的 health/size 诊断迁移到结构化 broker maintenance，不再增加 direct `su` 旁路。
- SELinux probe 读取失败返回 `unknown`，不能默认成 enforcing。
- Rootfs 管理 UI 保留稳定 code/detail，不只保留 `isInstalled`。

证明：broker/rootfs/keeper/provision 组合状态测试、unknown 状态测试、UI 状态映射测试和 direct `su` 路径守卫测试。

### 3. 原计划第 4 步：完成 canonical 存储收尾

先完成固定 canonical 数据，再处理外部挂载。

#### 3.1 Canonical 数据

- legacy `filesDir` migration 必须由 broker 完成或由受控迁移 RPC 完成，并返回明确结果。
- session 创建、删除和递归清理不能由 App 直接访问 `/data/adb/minis`。
- 审计 `UbuntuPaths`，禁止通用 App 代码取得 canonical host `File`。
- 处理 `MinisDocumentsProvider` 直接暴露 `/data/adb/minis` 的路径；只暴露明确允许的 scope，不能绕过 broker 的 path、identity 和 size checks。
- 保留 App-local cache 与 Linux guest persistent source 的清晰边界。
- 更新 `06-CURRENT-GAPS.md`，只删除已经通过测试关闭的条目。

#### 3.2 外部挂载产品决策

已确定：SAF 选中的外部目录必须通过 minisd 进入 Ubuntu：

```text
SAF tree URI
  -> persisted grant + raw storage capability
  -> App 重新证明后的结构化 mount.reconcile
  -> minisd 私有 mount namespace
  -> /var/minis/mounts/<name>
```

这不是简单恢复旧的 raw path map。不能把 host path、tree URI、guest path、mount flags 或 shell command 直接作为特权 RPC 参数。

### 4. 外部挂载结构化实现

建议单独建立 mount PR，使用完整快照 `mount.reconcile`，替换当前假的 `mount.prepare`。

#### 4.1 Wire contract

请求只允许包含：

- stable entry UUID。
- 严格的 mount name。
- external storage volume 标识。
- 相对 path segments。
- `ro` 或 `rw` access。

服务端根据 name 生成 `/var/minis/mounts/<name>`。拒绝 raw `host_path`、任意 `guest_path`、URI、filesystem type、任意 flags 和 command。

完整快照语义：

- `mounts: []` 表示删除全部外部挂载。
- 相同快照幂等。
- 任一 entry 校验或挂载失败，整个快照不生效。
- 返回 canonical snapshot digest 和 keeper identity。

#### 4.2 App-side authorization

`MountedFoldersStore` 保留 URI-centric 数据，不再把缓存的 `resolvedHostPath` 当授权凭据：

- 每次 reconcile 查找 exact persisted URI grant。
- 读挂载需要 persisted read grant。
- `rw` 还需要 persisted write grant、用户开关、raw write capability 和当前写探测全部通过。
- Android 11+ 需要 All Files Access；Android 10 及更低版本遵守对应 legacy storage 权限矩阵。
- 重新解析 volume 和 segments，不使用陈旧 raw path。
- 目录不存在、不可读、权限被撤销或 removable volume 被卸载时，从 desired snapshot 移除或标记 inactive，并立即 reconcile。
- 删除操作顺序为 reconcile 成功后持久化删除，最后释放 URI grant。

可从 #77 复用 `ExternalStorageAccessPolicy` 和权限矩阵测试，但不能复用 raw path snapshot、silent omission 或 App 侧 advisory read-only 作为安全边界。

#### 4.3 minisd security boundary

- `mount.reconcile` 只允许真实 App UID 的 peer；不得因为 peer UID 为 0 就放行。
- 该方法只允许 App socket，不得经过 `su --call` fallback。
- App socket 使用 private owner/mode；校验真实 peer credentials，不信任 client claim。
- primary storage root 根据 App user 派生；removable volume 只接受严格 UUID。
- 拒绝 `/data`、`/proc`、alias path、绝对 relative path、`.`、`..`、NUL、过长 segment、symlink source 和 symlink destination。
- source 使用 held dirfd 逐段打开，保持最终 FD 到 bind 完成，避免 validate-then-reopen TOCTOU。
- external bind 非 recursive，使用 `nosuid,nodev,noexec`；`ro` 必须由 kernel mount attribute/flag 强制。
- 不能验证只读时返回 `MOUNT_RO_UNSUPPORTED`，不能降级为可写。

#### 4.4 Keeper lifecycle

- `mount.reconcile`、`ubuntu.start/stop`、runtime maintenance 和 guest exec 共享 lifecycle lock。
- 准备 replacement keeper，完整建立 fixed mounts 和 external mounts 后再发布。
- replacement 失败时旧 keeper 保持不变。
- replacement READY 后原子发布 digest/PID，再回收旧 keeper。
- broker/keeper 重启后外部 mount 标记为 unverified；App 未重新 attestation 前拒绝 guest exec。
- App force-stop、keeper loss、设备重启后只能通过重新授权的 snapshot 恢复。

证明：Rust protocol/authorization/namespace tests、mount flags tests、replacement failure preservation tests，以及 Root 真机 namespace/mountinfo 验收。

### 5. 原计划第 5 步：MCP App-only

#### 5.1 先清理死路径

重建 #78，删除整个未被当前 Ubuntu runtime 消费的 `default_mount` MCP 遗留资产，并修正 provenance audit 文案。不得把旧 `minis-mcp-cli` 重新包装为现行产品路径。

移除或改正：

- `/var/minis/mcp-servers` bind registration。
- Repository 和 prompt 中的 guest CLI source-of-truth 描述。
- 各语言资源中关于 `minis-mcp-cli` 和 `/var/minis/mcp-servers/servers.json` 的旧文案。
- 仅为假定 guest CLI 存在而保留的 reload 逻辑。

#### 5.2 重建 #79

- App-private MCP config 是唯一配置真源。
- 配置写入采用原子替换，失败必须向调用方返回失败。
- add/update/delete/enable/import 只在有效持久化后触发一次有界 `MCPProvider.reload()`。
- no-op 写入不触发 reload。
- 旧 reload generation 不能重新注册过期 tools。
- 明确实现或移除 `$$VAR`、per-server timeout 和 OAuth token 语义。
- export 不包含 OAuth secret/token。

#79 的 hot reload 行为可独立于 mount PR 合并，但必须基于当前 master 重建，不能原样合并旧构造器冲突。

证明：现有 MCP JVM tests 扩展 config location、atomic failure、mutation count、reload generation、placeholder、OAuth injection 和 secret export 测试。

### 6. 原计划第 6 步：开放 PR 处置

处理原则：重建有价值的小 PR，关闭已被替代或无法安全合并的旧分支。

| PR | 处置 |
|---|---|
| #92 | 按本文件第 1 节拆分重建，随后关闭旧 PR |
| #78 | 修正文档 provenance 后重建并优先合并 |
| #79 | 修复构造器/基线冲突后重建并优先合并 |
| #77 | 只吸收权限矩阵和 fail-closed 检查，待 mount RPC 后重建 |
| #75 | runtime 新基线稳定后重建 authenticated pre-exec marker |
| #82 | runtime 和 MCP client 基线稳定后重建 MCP server UI |
| #80 | 新协议稳定后重建 transport profiling，并补 device trace |
| #81 | 等启动链稳定后取得真实 cold-start trace，再决定保留或关闭 |
| #73 | 在 #78 之后按当前 provenance 规则重建 |
| #67 | 按当前 FGS 合同重建独立 Android PR |
| #65 | 按当前 provider capability 模型重建独立 Android PR |
| #61 | 按当前 build pipeline 重建 cleanup PR，不混入 identity |
| #76 | 已被后续 privileged-access 实现替代，关闭并重新评估 Issue #45 |
| #63 | 范围与历史均已过时，关闭；Issue #50 以小 PR 和真机证据继续 |
| #69 | 范围失控且目标包名不符合冻结合同，关闭后另建 identity-only PR |

推荐顺序：

1. 关闭 #76、#63、#69。
2. 重建并处理 #78、#79；吸收 #77 的权限测试但不声称 mount 已完成。
3. 完成新的 HTTP、CI、runtime 和 workspace PR 后关闭 #92。
4. 重建 #75、#80、#82。
5. 重建 #65、#67、#61、#73；#81 等真实 trace。
6. 最后单独处理冻结目标为 `llc.slacker.minis` 的身份迁移。

所有关闭、重建和合并动作都必须在检查远端 diff、base、status checks 和 issue 关联后执行；不因旧 CI 绿色就直接合并旧 Draft。

### 7. 原计划第 7 步：完整验证

#### 7.1 每个代码 PR 的最小检查

文档：

```bash
python3 scripts/test_docs_provenance.py
python3 scripts/check_docs_provenance.py
```

Android：

```bash
cd src/android
./gradlew :app:testDebugUnitTest --no-daemon
./gradlew :app:lintDebug --no-daemon
```

minisd：

```bash
cargo fmt --manifest-path src/native/minisd/Cargo.toml --all -- --check
cargo clippy --locked --manifest-path src/native/minisd/Cargo.toml --all-targets -- -D warnings
cargo test --locked --manifest-path src/native/minisd/Cargo.toml
```

runtime/package 变更还必须运行现有 rootfs、payload、package-boundary 和 reproducibility checks。

#### 7.2 Root 真机矩阵

在 SELinux enforcing 的 Root/KernelSU arm64 设备上保存可复核证据：

- 首装：无 runtime 时安装 APK、授权 Root、安装并激活匹配 runtime。
- 升级：旧 runtime 到新 manifest，确认用户数据不变。
- 失败恢复：provision/keeper/start/switch 中断后 pending 保留、继续或回滚到正确 previous。
- Root：无授权时明确返回 `ROOT_REQUIRED`/unsupported，不伪造 Installed。
- SELinux：保持 enforcing；App、broker 和 keeper 的 UID/GID、label、访问边界符合合同。
- 持久化：`df`/mount 证明用户数据不在 tmpfs/tmpfs_data。
- 真实工具：`python3`、`git`、`curl`、Android `tar/unzip/pm` 行为符合 manifest 和合同。
- 内核：验证目标设备的 `renameat2(RENAME_EXCHANGE)` 行为。
- lifecycle：force-stop、relaunch、broker loss、keeper loss、设备重启后 lease、PID reuse 和恢复行为正确。
- session：session isolation、附件、symlink、TOCTOU 和双向文件可见性。
- 外部 mount：选择、重命名、只读/读写、删除、reconcile、撤销 grant、All Files Access 撤销、可移动存储卸载和 namespace 清理。
- mount security：shell、file tools 和 confirmed admin execution 都不能绕过 `ro,nosuid,nodev,noexec`。
- unauthorized caller：其他 UID 和 root `--call` 不能调用 `mount.reconcile`。

没有真实 Root 设备时，只能报告 JVM/Rust/build 结果，不能声称第 7 步完成。

## 完成判据

本计划只有在以下条件全部满足后才可标记完成：

- 原始七步每一步都有已合并的代码/文档变更或明确的关闭证据。
- `06-CURRENT-GAPS.md` 与实际代码和真机证据一致。
- 没有遗留 direct canonical host access、死 MCP guest 路径或未经 kernel 强制的 read-only mount 声称。
- PR backlog 已逐项重建、合并或关闭，并记录理由。
- Root 真机验收日志可复核，且没有用 CI 绿色替代 device-only 证据。
