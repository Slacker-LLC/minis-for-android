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

- 计划文档所在主工作区分支：`feat/issue-51-provision-rollback`；本地 HEAD：
  `f9205f88337a776ea88287f59a2383994708bff3`。
- 当前远端 `origin/master`：`318d4ef13380e02bff4833e5c4b277e40f62a596`。
- PR #92 已关闭；替代 PR #93–#102 已按依赖顺序合并，PR #103–#109 也已合并。
- 当前没有开放的非 Draft PR；仍开放的 Draft PR 为 #65、#67、#73、#75、#77、#78、#79、#80、#81、#82。
- PR #92 的实际 diff 包含 90 个文件，其中 89 个是可拆分的源码/配置 Hunk，另 1 个是
  `docs/archive/snapshots/pr-92-full-diff.patch` 归档产物；它混合了 HTTP、CI、runtime 自举/回滚、
  canonical 文件 RPC、外部路径和 UI 适配，不能作为一个整体直接合并。
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

目标：不要继续在当前 90 文件的大 PR 上叠加新合同；归档 Patch 不属于可拆分合同基线。

动作：

- 以最新 `master` 为基础建立新的短分支。
- 只从 #92 搬运已经验证过的逻辑，不直接整包 cherry-pick 未审查的 #92。
- 先处理与 runtime 无关的独立变更，再重新建立 runtime 和 workspace PR。
- 每个替代 PR 都必须明确保留的旧行为、删除的死路径和对应测试。

非目标：本阶段不迁移 Android applicationId/Java 包名，不修改用户数据，不关闭仍需保留的 Issue。

### 1. 重建原计划第 1 步和 #92 的独立部分

#### 1.1 HTTP framing PR

从 `853d28e679bec935c84104f0a4314ad3fa942787` 的逻辑重建独立 PR，范围只包括：

- `BoundedHttp`。
- DebugServer 的有界 HTTP 请求解析。
- MCPServer 的 loopback HTTP 读取。
- UTF-8 按字节长度计算。
- conflicting `Content-Length`、`Transfer-Encoding`、header/body 上限和截断请求的拒绝测试。

证明：Android JVM tests、lint、DebugServer/MCPServer 相关现有测试和 docs provenance checks。

截至 2026-09-02，已从 `origin/master` 建立独立 worktree `D:\\gongzuo\\minis-for-android-pr1`，分支为
`feat/pr-1-bounded-http`，提取提交 `2a3e98900aa917e68e9dd5239789212c136cc472`。该提交只包含上述 5 个文件；
`BoundedHttpTest` 6 个测试与 `MCPServerTest` 12 个测试均通过，Gradle 返回 `BUILD SUCCESSFUL`。
对应替代 PR #93 已合并到 `master`。

#### 1.2 CI trigger PR

单独评估 `24093edef2f01ba327ef36fe857882d2994f0dac`，只改变 CI 触发行为：覆盖所有 branch push，并保留
`workflow_dispatch` 手动触发入口。不得把一次性验证脚本或 runtime 行为混入这个 PR。

证明：workflow YAML 校验和一次 branch push/PR check 结果。

截至 2026-09-02，已从 `origin/master` 建立独立 worktree `D:\\gongzuo\\minis-for-android-pr2`，分支为
`feat/pr-2-ci-triggers`，提取提交 `83feb94656ebdac2ad15cb138479049ecfd24030`。
当前提交只包含 `.github/workflows/ci.yml`；本地 YAML 解析与 `git diff --check` 均通过，GitHub branch-push / PR check
已取得远程证据；对应替代 PR #94 已合并到 `master`。

#### 1.3 Runtime 自举、升级和回滚 PR

截至 2026-09-02，已从 `origin/master` 建立独立 worktree `D:\\gongzuo\\minis-for-android-pr3`，分支为
`feat/pr-3-runtime-transaction`，提取提交 `3f2ffa8c34f23b7b7d4d7e4833aaf65397419bf9`。该分支保留
`runtime.maintenance` 结构化事务、previous identity、切换阶段状态和 fail-closed 回滚；没有带入
`workspace.file`、WorkspaceFileClient 或 config proxy relocation 改动。

由于 `origin/master` 上仍有旧的 `RootfsManager` 调用，独立分支额外保留了 `RuntimeProvision` 的兼容入口，
并加入让新维护 RPC 能够独立编译和授权的 5 个必要支持 hunk（3 个 policy 文件、`MinisdClient.kt`、
`MinisdProtocol.kt`），所以该物理提交为 22 个文件；这是主合同表 19 个文件口径之外的可执行性支撑，
不是把 PR 4 的 workspace 实现混入 PR 3。

证明结果：Android Runtime 4 个测试类共 55 个测试通过，minisd 84 个库测试、2 个二进制测试、3 个 contract
测试、5 个 peercred 测试全部通过；`bash scripts/test-runtime-payload-verification.sh` 的篡改 broker、缺失
rootfs、非法 manifest 场景全部通过。对应替代 PR #95 已合并到 `master`。

保留 `runtime.maintenance` 的结构化事务闭环，但修复 previous identity 校验：

- pending 必须保存目标 identity 和 previous identity。
- previous rootfs 必须按自身保存的 release、profile、upstream、revision、archive digest 校验。
- 目标 APK manifest 只能校验目标 rootfs，不能作为 previous rootfs 的身份。
- 目标版本变化、provision 失败、keeper 启动失败时，合法 previous rootfs 必须能够回滚。
- 交换结果未知时保留 pending，不能清除状态或盲目继续。

相关代码：`RuntimeDistributionManager.kt` 的 identity、pending 和 rollback 路径，以及已有 runtime distribution tests。

证明：至少覆盖旧版本到新版本的真实 identity 差异、切换后 provision 失败、回滚成功、回滚身份不匹配时 fail-closed、pending 保留和 App 被杀后的恢复。

#### 1. Canonical `workspace.file` PR

重新实现 `573380feadcf8ce6edf84e8e89621cbd377f97a1` 的目标，但不接受当前 pathname check 后重新 open 的安全模型：

- 优先使用 `openat2` 的 `RESOLVE_BENEATH | RESOLVE_NO_SYMLINKS | RESOLVE_NO_MAGICLINKS`。
- 对目标 Android 内核不具备 `openat2` 时，使用 dirfd + `openat` + `O_NOFOLLOW` 逐段遍历。
- 读、写、append、copy、move、delete、list、info 都必须遵守同一安全边界。
- 保持 path、大小、session、文件类型和 owner/mode 限制。
- 添加父目录 symlink-swap 和目标替换的否定测试。

同时修复两个已发现的 Android 回归：

- `StreamingMarkdownText.kt` 的媒体 staging 改为 coroutine-backed state，禁止在 Compose composition 中 `runBlocking` broker I/O。
- Markdown/Coil 的 session-scoped attachment URL 必须携带真实 session identity；无 session 的 URL 只能访问明确允许的 global scope。

证明：Rust contract tests、workspace file tests、Android JVM tests，以及覆盖媒体渲染和 sessionless/global URL 的测试。

截至 2026-09-02，已从 `origin/master` 建立独立 worktree `D:\\gongzuo\\minis-for-android-pr4`，分支为
`feat/pr-4-workspace-file`，该分支在 PR 3 提交 `3f2ffa8c34f23b7b7d4d7e4833aaf65397419bf9` 之上提取提交
`d5d898dac265b47e165dea5c3fc7f3c6e9a90c7d`。实际提交为 23 个物理文件：主合同表的 16 个路径，加上
`dispatch.rs`、`lib.rs`、`protocol.rs`、`tests/contract.rs` 这 4 个 native 注册/契约支持文件，
`ExternalMountAccess.kt`、`FileMutationQueue.kt` 的 2 个 Android 依赖文件，以及 `UbuntuRuntime.kt` 的
会话路径检查迁移；没有把 PR 5 的媒体/Markdown 改动带入本提交。

证明结果：`MinisdProtocolTest` 21 个测试通过；`cargo test --manifest-path src/native/minisd/Cargo.toml workspace_file`
的 7 个 workspace 安全测试通过；native 全量测试中 91 个库测试、2 个二进制测试、3 个 contract 测试、5 个
peercred 测试全部通过；`git diff --check` 通过。对应替代 PR #96 已合并到 `master`。

#### 1.4 UI 异步媒体与 Markdown 路由 PR

截至 2026-09-02，已从 PR 4 提交 `d5d898dac265b47e165dea5c3fc7f3c6e9a90c7d` 建立独立 worktree
`D:\\gongzuo\\minis-for-android-pr5`，分支为 `feat/pr-5-ui-async-media`，提取提交
`2f86268f1467e087243aa77922a587af86076b2b`。原始 PR 5 名义 13 个文件中，3 个文档仍由本治理工作树维护，
`ChatViewModel.kt` 的存储迁移归入 PR 6，`AppNavigation.kt` 的文件浏览路由归入 PR 9；所以当前可独立编译的
PR 5 提交实际为 8 个 UI/路由文件。媒体 staging 已改为 `produceState + Dispatchers.IO`，链接解析改为
协程路由，session-scoped `minis://` 不再在 Compose composition 中执行 blocking broker I/O。

证明结果：Android 全量 JVM 测试 136 个测试套件、1067 个测试通过，失败 0、错误 0、跳过 0；提交前后的
`git diff --check` 均通过。对应替代 PR #97 已合并到 `master`。

#### 1.5 核心存储 RPC 迁移 PR

截至 2026-09-02，已从 PR 5 提交 `2f86268f1467e087243aa77922a587af86076b2b` 建立独立 worktree
`D:\\gongzuo\\minis-for-android-pr6`，分支为 `feat/pr-6-core-storage-rpc`，提取提交
`1b4b342427a1226a646a3d5db4416ea9daf41f5d`。名义 7 个存储文件之外，独立可编译提交额外包含
`ChatViewModel.kt` 的 offload/mention 消费者支持和 `AndroidUiController.kt` 的 suspend 截图支持，实际为
9 个物理文件；`ContextOffload.toolsDir` 仅作为尚未迁移的诊断 spill 兼容入口保留，canonical offload 已经
通过 `WorkspaceFileClient` 写入，混入的 `MediaPlayerManager.init` 已剔除。

证明结果：Android 全量 JVM 测试 136 个测试套件、1067 个测试通过，失败 0、错误 0、跳过 0；提交前后的
`git diff --check` 均通过。对应替代 PR #98 已合并到 `master`。

#### 1.6 Guest Offload 迁移 PR

截至 2026-09-02，已从 PR 6 提交 `1b4b342427a1226a646a3d5db4416ea9daf41f5d` 建立独立 worktree
`D:\\gongzuo\\minis-for-android-pr7`，分支为 `feat/pr-7-guest-offload`，提取提交
`a5ea1451d29e93b4f89bec2e05eef857cc6bcb9e`。原始 PR 7 的 9 个主合同文件之外，独立可编译提交额外包含
`ChatViewModel.kt` 的图片预算溢出消费者支持；由于 `ImageBudget.ensureSpillover` 已改为通过
`WorkspaceFileClient` 写入 guest 路径，调用方同步改为 suspend，实际为 10 个物理文件。

证明结果：Android 全量 JVM 测试 136 个测试套件、1067 个测试通过，失败 0、错误 0、跳过 0；提交前后的
`git diff --check` 均通过。对应替代 PR #99 已合并到 `master`。

#### 1.7 工具与权限门控迁移 PR

截至 2026-09-02，已从 PR 7 提交 `a5ea1451d29e93b4f89bec2e05eef857cc6bcb9e` 建立独立 worktree
`D:\\gongzuo\\minis-for-android-pr8`，分支为 `feat/pr-8-tool-permission-gate`，提取提交
`5dd59acaf9eb6bcc4f2166dca8f7252ce4f72c81`。原始 PR 8 的 10 个主合同路径中，`FileMutationQueue.kt` 的
支持改动已在 PR 6 提前落地，因此本次独立提交实际为 9 个物理文件；工具调用、读取图片、Android 能力/日志/
APK 检查、权限门控和清理测试均按 PR 8 边界提取。

证明结果：Android 全量 JVM 测试 136 个测试套件、1067 个测试通过，失败 0、错误 0、跳过 0；提交前后的
`git diff --check` 均通过。对应替代 PR #100 已合并到 `master`。

#### 1.8 沙箱与文件浏览 UI 迁移 PR

截至 2026-09-02，已从 PR 8 提交 `5dd59acaf9eb6bcc4f2166dca8f7252ce4f72c81` 建立独立 worktree
`D:\\gongzuo\\minis-for-android-pr9`，分支为 `feat/pr-9-sandbox-file-browser`，提取提交
`1fadb1e6d14508a38e3f1c3af6b5fbf0244aaded`。原始 PR 9 的 7 个主合同路径中，只有 4 个路径相对前序基线
产生差异；为使新的浏览器回调签名和 guest 路由可独立编译，额外加入 `AppNavigation.kt` 的共享目录/会话
目录路由支持，实际为 5 个物理文件。

证明结果：Android 全量 JVM 测试 136 个测试套件、1067 个测试通过，失败 0、错误 0、跳过 0；提交前后的
`git diff --check` 均通过。对应替代 PR #101 已合并到 `master`。

#### 1.9 外部挂载与 gap contract PR

截至 2026-09-02，已从 PR 9 提交 `1fadb1e6d14508a38e3f1c3af6b5fbf0244aaded` 建立独立 worktree
`D:\\gongzuo\\minis-for-android-pr10`，分支为 `feat/pr-10-external-mount-gap`，提取提交
`cdabfce084aae2899661a9d2b857324f846accc1`。`ExternalMountAccess.kt` 已在 PR 4 作为编译支撑提前落地，
因此本次实际只有 `docs/contracts/06-CURRENT-GAPS.md` 这 1 个物理文件发生差异；文档同时按当前连续堆栈
修正了已迁移路径的 gap 描述。

证明结果：PR 9 基线的 Android 全量 JVM 测试 136 个测试套件、1067 个测试通过；PR 10 文档变更后的
provenance 测试 17 项通过、provenance guard 通过，`git diff --check` 通过。对应替代 PR #102 已合并到 `master`。

#### 1.10 关闭旧 #92

已完成：替代 PR #93–#102 已进入 `master`，#92 已关闭并在关闭说明中链接替代 PR；#92 的 CI 和审查记录未被改写。

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

截至 2026-09-02，诊断状态已有两个可审查切片合并。PR #103 的 head commit 为
`fb3390b6975bb6618c2874fd2dd38a1af476328a`，合并提交为
`beb098faea47dfb0a5345620fe9b99b6e9c79cfc`。已落地 Root probe 的真实字段映射和 SELinux `unknown` 语义、
UbuntuRuntime 的 stale snapshot 保留、Rootfs structured broker health 探测，以及 Root/broker/rootfs/keeper/
provision 的独立诊断模型和 UI code/detail 映射。PR #103 的 push 与 pull request 两轮 CI 均通过 Rust quality、
runtime payload、Rootfs verification、Android lint、debug/release build 和 unit tests；provenance guard 无文档改动而跳过。

PR #104（head `7a565aa5b6d8efbe33d22308c0aa104af16ceeee`，合并提交
`ac75ee9a6ab2be119aef98f0a4e5c5fa81a52aff`）继续将 rootfs size 通过固定的 broker `du -x` 读取，写入结构化
`size_bytes`，并由 `RootfsHealth`、`RuntimeDiagnostics` 和管理 UI 消费；两轮 CI 与本地 Rust/Android 目标测试均通过。

PR #105（head `e6d9945ff82ed27eb43543ce3979140462865246`，合并提交
`0c47f778277aa3bcaefd122320380a1e4198dd69`）完成 RootfsManager 生命周期收敛：安装/恢复委托给
`UbuntuRuntime.start()`，重置委托给 `UbuntuRuntime.resetRootfs()`，移除 RootfsManager 内的
`ProcessBuilder`、direct `su` staging/repair/reset 执行器；重置非 `RESET` 结果会进入失败态并向 UI
传播明确错误。两组 push/PR CI 的 Android unit tests、debug build、debug/release lint、release build、
runtime payload 和 Rootfs verification 均通过。

PR #106（head `87b8204924c318fda0e7a67153369adc18f12fb7`，合并提交
`9b2bdc69e4d216f24d370b46f0190dadd828eff6`）完成 DocumentsProvider 的 canonical 存储接入：注册
`MinisDocumentsProvider`，只暴露 global `memory`、`skills`、`shared` 三个 scope；query/create/delete/rename
全部通过 `workspace.file` broker，`openDocument` 通过 `StorageManager` proxy FD；写入使用 App cache 临时文件，
`fsync`/release 通过 broker 原子提交，不直接打开 `/data/adb/minis`。同时新增 native `workspace.file.mkdir`
和对应的路径、symlink、persistent backing 约束。证据包括 Kotlin 编译、DocumentsProvider 边界单测、完整
Rust `cargo test --locked`、`cargo fmt --check`，以及 push/PR 两组远程 CI 的 Android build、unit tests、lint、
release build、runtime payload、Rootfs verification 和 `minisd quality` 均通过；PR check 状态为 `CLEAN` 后合并。

PR #107（head `98c9d0801b0854b5d4652abf6c782d6f46897509`，合并提交
`8e864d3c4d63baf125b5d9cdd55292748e1a6f35`）完成 canonical 存储的 legacy 生命周期收口：legacy
`filesDir` 数据通过受控 `workspace.file` migration RPC 写入固定 workspace、memory、skills、shared、home 和
session roots；目标只接受固定 target 与相对路径，拒绝 symlink/越界路径，分块写入使用 `sync_data`，完成标记使用
`create_new`、`sync_all` 和目录同步。session 删除只接受安全 session identity，由 broker 在固定 sessions root
下递归删除并同步父目录；`UbuntuRuntime` 的已运行快速路径也不再绕过迁移，并避免启动锁重入死锁。提交包含
迁移/删除路径及协议测试；本地 Linux `cargo clippy --locked --all-targets -- -D warnings`、`cargo test --locked`
（96 个库测试、2 个 main 测试、3 个契约测试、5 个 peer credential 测试）、Android 定向单测和 `git diff --check`
均通过；新 head 的 push CI `33634913801` 与 PR CI `33634919583` 均整体通过，PR check 为 `CLEAN` 后合并。

以上切片仍不等于第 3 步全部完成：尚未取得 Root 真机上的 UID/GID、SELinux、keeper lifecycle 和升级/中断恢复证据；
`RootfsManager` 的 health、size、安装和重置已迁移到 structured broker maintenance；但 `UbuntuRuntime` 的
broker bootstrap 仍保留作为 root 入口的 direct `su`，需要单独形成守卫和真实设备证据。broker 的 policy、peer
identity、运行 binary identity 也尚未形成完整的真实读取闭环。外部挂载 reconcile 已由 PR #108 完成，但仍需
补齐上述运行时证据。

### 3. 原计划第 4 步：完成 canonical 存储收尾

先完成固定 canonical 数据，再处理外部挂载。

#### 3.1 Canonical 数据

- legacy `filesDir` migration 已由 PR #107 的受控 broker RPC 完成，并返回明确的 skipped/copied/error 结果。
- session 删除和递归清理已由 PR #107 收口到 broker 固定 sessions root；App 不直接访问 `/data/adb/minis`。
- 审计 `UbuntuPaths`，禁止通用 App 代码取得 canonical host `File`。
- `MinisDocumentsProvider` 已由 PR #106 注册并改为 broker-backed；只暴露明确允许的 global scope，不能绕过 broker 的 path、identity 和 size checks。
- 保留 App-local cache 与 Linux guest persistent source 的清晰边界。
- 更新 `06-CURRENT-GAPS.md`，只删除已经通过测试关闭的条目。

PR #106 关闭了 DocumentsProvider 这一 canonical 存储切片，PR #107 又关闭了 legacy migration 与 session
删除/递归清理切片，PR #108 关闭了 external mount reconcile 源码与协议切片；第 4 步仍需等 Root 真机证据，
因此不能把 canonical 存储整体标记为完成。

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

PR #108 已使用完整快照 `mount.reconcile` 完成源码与协议实现；PR #109 随后删除无调用方的 `mount.prepare`
dispatch、协议和 policy 声明，并将旧 policy 项加入升级兼容清理表。以下合同同时作为审计清单和真机验收清单。

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

PR #108 的 head 为 `221bc6b789a717dba09102bfdb8f4485b872fbf9`，合并提交为
`13243daf7179d1fa59590d2eca8619c6feab8031`。本地 Rust `cargo fmt --all -- --check`、
`cargo clippy --locked --all-targets -- -D warnings`、`cargo test --locked`，Android
`:app:testDebugUnitTest`、`:app:assembleDebug`，以及两轮远程 CI 的 runtime payload、Rootfs verification、
minisd quality、Android unit tests、debug/release build 和 debug/release lint 均通过；PR check 为 `CLEAN` 后合并。
源码证据已完成，但 Root 真机 namespace/mountinfo、SAF grant 撤销、可移动存储卸载和 SELinux 行为仍待验收。
PR #109 的 head 为 `e03ef87950c231e73da39ab81d5a682c8d960d51`，合并提交为
`318d4ef13380e02bff4833e5c4b277e40f62a596`；本地 Rust 测试与两轮远程 CI 均通过。

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
| #92 | 已由 #93–#102、#108 与 #109 替代并关闭；保留原 PR 的 CI 和审查记录 |
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
3. 新的 HTTP、CI、runtime、workspace 和 external mount PR 已完成；#92 已关闭。
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
