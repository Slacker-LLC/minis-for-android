# #122 加密备份包与 rclone 远程同步可行性评估

状态：RFC 评估稿。当前 PR 只落地边界、路径映射、风险和实施门槛，不声称备份功能已经实现。

## 1. 结论

这个需求可行，但必须分阶段交付。不能把外部参考提交整体移植进当前仓库后直接发布，原因有三点：

1. 当前仓库没有备份包、恢复器或 rclone 生产实现；
2. 参考实现默认从应用沙盒及其 staging 目录读取，而本项目的持久化真源是 `/data/adb/minis`，两者不是同一存储域；
3. `/data/adb/minis/rootfs` 是可替换的运行时 rootfs，不是用户数据，备份和恢复都必须排除它。

因此，本议题的评估结论是：

- `.minisbak` 可以作为跨设备、可校验、可加密的单文件包格式；
- 本项目的 exporter/importer 必须通过 `minisd` 或受控的特权 broker 访问 canonical storage，不能复制出一份应用本地真源；
- rclone 应作为可选远程传输层，在本地文件夹导出、恢复和安全门槛通过后再接入；
- #122 可以由本 RFC PR 收敛。真正的生产实现应拆成后续实现任务，并逐项通过真机验收。

## 2. 审计基线

外部参考实现绑定到完整提交：

```text
7b00d5c5610d9de9239de195e92e4e97ebe4ec87
```

该提交包含 47 个文件、约 14,041 行新增内容。来源仓库、许可证和历史来源边界以根目录 `PROVENANCE.md` 为准；这里引用的是提交内容和文件路径，不将外部产品身份当作本项目当前实现。

本评估核对了以下参考文件：

- `deps/build_rclone_android.sh`
- `src/android/app/src/main/java/com/openminis/app/backup/BackupCrypto.kt`
- `src/android/app/src/main/java/com/openminis/app/backup/BackupExporter.kt`
- `src/android/app/src/main/java/com/openminis/app/backup/BackupImporter.kt`
- `src/android/app/src/main/java/com/openminis/app/backup/BackupFormat.kt`
- `src/android/app/src/main/java/com/openminis/app/backup/BackupPackageReader.kt`
- `src/android/app/src/main/java/com/openminis/app/backup/BackupRestoreFiles.kt`
- `src/android/app/src/main/java/com/openminis/app/backup/remote/RcloneBridge.kt`
- `src/android/app/src/main/java/com/openminis/app/backup/remote/RcloneRemoteStore.kt`
- `docs/backup-streaming-package-design.md`

## 3. 当前存储合同与备份边界

`docs/contracts/03-STORAGE-CONTRACT.md` 是当前路径行为的唯一真源。备份功能必须服从以下边界：

| 数据域 | canonical path | 默认是否备份 | 实现边界 |
| --- | --- | --- | --- |
| 会话持久数据 | `/data/adb/minis/sessions` | 是 | 由 broker 提供一致性导出；包含 session 目录及其 workspace、attachments、offloads、browser 等受支持子树 |
| Memory | `/data/adb/minis/memory` | 是 | 通过 `WorkspaceFileClient` 或新的受控导出 RPC 读取 `/memory` 映射，不从应用沙盒另建副本 |
| Skills | `/data/adb/minis/skills` | 是 | 通过 `WorkspaceFileClient` 或新的受控导出 RPC 读取 `/skills` 映射；需要同时定义技能元数据的版本化导出 |
| 共享文件 | `/data/adb/minis/shared` | 可选 | 作为明确的 category 纳入，不能借用会话路径的隐式遍历 |
| 工作区 | `/data/adb/minis/workspace` | 可选 | 只有在产品明确选择后纳入；必须固定范围和大小上限 |
| 用户 home | `/data/adb/minis/home` | 否（首版） | 可在后续 schema 中增加，首版避免把运行环境误当作用户备份 |
| rootfs | `/data/adb/minis/rootfs` | 否 | 运行时镜像，可替换；严禁进入用户备份包或恢复目标 |
| 运行状态 | `/data/adb/minis/run`、`/data/adb/minis/log` | 否 | 运行时锁、日志和临时状态不进入可恢复用户数据 |
| rootfs 部署状态 | `/data/adb/minis/runtime/{staging,previous,pending.json,deployed.json}` | 否 | 属于 rootfs 发布事务，不属于备份恢复事务 |

### 3.1 参考 category 到本项目的映射

参考实现的 `CHATS`、`MEMORY`、`SKILLS`、`SHARED_FILES`、`PROVIDERS`、`MCP_SERVERS` 和 `ENVIRONMENT_VARIABLES` 不能原样映射。建议采用以下适配层：

| 逻辑 category | 本项目首版处理 | 必须补齐的适配 |
| --- | --- | --- |
| `CHATS` | 覆盖 sessions 的可恢复用户数据 | broker 快照边界、session schema、附件引用和目录元数据 |
| `MEMORY` | 覆盖 `/data/adb/minis/memory` | guest path 到 host path 的受控映射、权限恢复策略 |
| `SKILLS` | 覆盖 `/data/adb/minis/skills` | 技能目录与本地元数据的一致性、启用状态和版本兼容 |
| `SHARED_FILES` | 可选纳入 `/data/adb/minis/shared` | 明确用户选择、大小限制和路径白名单 |
| `PROVIDERS` | 只导出经用户确认的非凭据配置；凭据单独加密 | provider schema、密钥轮换、导入前确认 |
| `MCP_SERVERS` | 后续纳入 | 禁止把 token、私钥和可逆配置明文写入 manifest |
| `ENVIRONMENT_VARIABLES` | 后续纳入 | 逐项标记敏感变量，默认不导出敏感值 |

当前代码中的 `UbuntuPaths.kt`、`MemoryRepository.kt`、`SkillRepository.kt` 和 `StorageRpcMethods.kt` 已经体现 guest path 与 `/data/adb/minis` 的分层。备份实现应复用这些 seam；如果现有 RPC 不能提供一致性目录导出，应新增一个最小、带权限检查和审计日志的导出接口，而不是在 App 层绕过 broker。

## 4. 冷备份协议

“用户发起导出”必须是一个可证明的一致性边界，而不是遍历过程中随时读取正在变化的目录。

### 4.1 导出状态机

```text
requested
  -> acquire shared export/restore lock
  -> quiesce agent writes and minisd
  -> validate free space and destination
  -> enumerate canonical data through broker
  -> build manifest and package
  -> verify local size/hash
  -> publish complete .minisbak
  -> release lock and resume runtime
```

要求：

1. exporter 与 importer 共用进程级互斥；同一时间只允许一个备份或恢复事务；
2. 在快照点暂停会话写入以及会修改 `sessions`、`memory`、`skills` 的 broker 操作；
3. 先检查 staging 和目标磁盘空间，再开始复制；
4. 遍历顺序稳定，路径使用逻辑相对路径，拒绝绝对路径、`..`、源 symlink 和越界解析；
5. 每个条目记录 category、相对路径、大小、模式、必要的 owner 信息、内容 hash 和 schema 版本；
6. 生成过程使用临时文件，完成后执行 flush/fsync，再以原子方式发布最终文件名；中断不得留下一个看起来完整的包；
7. 包完成后再恢复 runtime，并记录事务结果、快照时间点和失败原因。

### 4.2 包格式复用边界

建议复用参考实现的 `minisbak/1` 格式及其 reader/writer 测试，不复制其路径假设：

- 扩展名：`.minisbak`；MIME：`application/x-minisbak`；
- manifest 明确格式版本、产品 schema、category、设备/实例兼容性和每个文件的完整性信息；
- 大型 JSONL 数据按固定大小分片，当前参考设计的分片上限为 64 MiB；
- 先压缩、后加密；完整性 hash 针对密文数据；
- 加密采用流式分段和路径绑定的 AAD，避免把整个包一次性载入内存；参考实现使用 4 MiB 分段；
- reader/writer 必须覆盖 Zip64、长路径、空目录、权限元数据、截断包、重复条目和恶意压缩比；
- manifest 和恢复救援元数据即使按格式保持可读，也不得包含凭据、token、私钥或敏感变量值；
- 不在当前 PR 重新发明包格式，也不把参考实现的应用路径、UI 或 provider 业务逻辑一并带入。

## 5. 恢复协议与回滚

恢复风险高于导出。恢复器的首要不变量是：任何失败都不能破坏正在运行的 rootfs，也不能把一半的新用户数据伪装成完整恢复。

### 5.1 恢复阶段

1. 获取与 exporter 相同的互斥锁，停止会修改 canonical storage 的 runtime；
2. 在写入前完整读取 manifest，检查格式/schema、产品身份、目标架构、空间、条目数量和总大小；
3. 对每个路径执行 traversal、symlink、重复条目、类型冲突和大小上限检查；
4. 解密、解压、校验密文/明文关联 hash，把结果写入 broker 管理的临时事务空间；临时空间不得成为新的持久化真源；
5. 先恢复可独立校验的目录和记录，再提交 category；提交动作由 broker 使用 FD-relative 操作完成，不能依赖路径重解析；
6. 每个 category 只有在全部条目校验完成后才原子提交；保留旧数据直到事务成功；
7. 提交失败、进程崩溃或断电时，根据事务日志删除未提交 staging，或按日志反向恢复已提交 category；
8. 恢复完成后重新建立必要的 owner/mode，owner 必须来自当前设备真实 App UID/GID 和安全策略，不能写死 UID `10000`；
9. 重启 `minisd`，执行 storage probe、会话读取和最小 guest smoke test；任何 probe 失败都必须进入可观察的恢复失败状态。

### 5.2 不能做的事

- 不能把备份包直接解压到 `/data/adb/minis` 后覆盖现有目录；
- 不能恢复 `/data/adb/minis/rootfs`、`run`、`log` 或 rootfs 发布事务文件；
- 不能通过先删除原目录、再复制新目录来假装原子替换；
- 不能仅凭 manifest 中携带的 UID/GID 给新设备设置 owner；
- 不能把应用私有数据库中未导出的旧路径当成 canonical storage 的回退真源；
- 不能在 App 主线程调用阻塞式 rclone RPC。

## 6. rclone 远程传输可行性

rclone 适合作为传输层，不应承担备份一致性、加密或恢复事务语义。可行，但需要先满足以下门槛：

### 6.1 集成和体积

参考构建脚本通过 gomobile 生成 Android `.aar`，产物位于 `deps/build/rclone/rclone.aar`。接入前必须：

- 固定 rclone-mobile 的源码版本、Go 版本、NDK 版本和各 ABI 构建参数；
- CI 记录 AAR 的 hash、ABI、未压缩/压缩体积，并设置 APK 增量预算；
- 构建守卫确认 rclone 只进入目标 variant，不污染不需要远程同步的包；
- 若体积超预算，评估可选 feature 或独立分发，不以删除校验和安全能力换体积；
- 产物可重建，不能依赖开发机临时生成的二进制。

### 6.2 凭据和调用模型

- WebDAV、SMB、SFTP、S3、FTP 等 remote 配置由 Android 安全存储管理；
- rclone 配置文件只在 cache 中短暂生成，使用后立即清理，不把可逆的 obscured 密码持久化到普通配置；
- 禁止交互式 OAuth 阻塞 UI；首次接入要明确授权、超时和撤销路径；
- `Gomobile.rcloneRPC` 视为阻塞调用，必须在 worker 上执行；长任务使用异步 job、轮询、超时和取消；
- 首版上传一个完整、自包含的 `.minisbak`，远程端按临时名写入，完成本地 hash/size 校验后再发布；
- 远端 hash 能力并不普遍，尤其不能把 WebDAV 的服务端 hash 当成通用验收条件；远端能力不足时至少保留本地密文 hash、字节数和上传完成状态；
- 断点续传首版以重新执行完整文件上传为基线，只有在服务端能力和一致性测试充分后才引入分片协议。

推荐传输顺序：先支持本地 SAF 文件夹导出/导入，再增加 rclone remote。这样可以把包格式、恢复事务和权限问题与网络故障隔离。

## 7. 威胁模型和恢复不变量

| 风险 | 必须满足的控制 |
| --- | --- |
| 恶意包路径穿越 | 逻辑相对路径校验、拒绝 `..`、拒绝 symlink、FD-relative 写入 |
| 压缩炸弹或空间耗尽 | manifest 总大小/条目数上限、导入前空间检查、解压限额 |
| 包被截断或替换 | manifest、密文 hash、分段 AAD、最终文件原子发布 |
| 远程凭据泄露 | Android 安全存储、临时 config、日志脱敏、禁止明文 secrets 进入 manifest |
| 导出/恢复并发写入 | 共享锁、quiesce、事务状态和可恢复日志 |
| 断电造成半恢复 | staging、fsync、category 提交记录、旧数据保留和逆向回滚 |
| 恢复破坏运行时 | rootfs/run/log/runtime 明确排除，恢复前后 probe |
| 新设备权限不匹配 | 使用当前真实 UID/GID 和 SELinux 规则重建 owner，不信任包内 owner |
| 远端上传假成功 | 临时远端对象、大小/本地 hash 记录、完成后发布 |

恢复事务日志至少要能回答：事务何时开始、快照来自哪个 schema、每个 category 是否已校验、是否已提交、旧数据在哪里、失败后下一步是清理还是回滚。日志本身不能包含凭据，且必须在关键状态变更后 flush/fsync。

## 8. 分阶段实施与验收门槛

| 阶段 | 交付物 | 完成条件 |
| --- | --- | --- |
| 0 | 本 RFC、路径映射、排除清单、体积与风险基线 | 合同审阅通过；没有声称功能已实现 |
| 1 | `minisbak/1` reader/writer 与跨平台测试 | Zip64、截断、重复条目、长路径、hash、加密流测试通过 |
| 2 | broker-backed exporter、sessions/memory/skills 映射、本地文件夹目标 | synthetic fixture 和 rooted Android 真机导出/再读通过；无应用本地重复真源 |
| 3 | 加密包、分阶段 restore、事务日志和可逆回滚 | 人为断电/kill、空间不足、校验失败、权限不匹配均可安全退出并恢复 |
| 4 | 可选 rclone AAR 和 remote store | ABI/体积/可重建守卫、超时取消、临时凭据、上传验证和网络故障测试通过 |
| 5 | UI、状态可观测性、恢复帮助和发布门禁 | 用户能看到 category、进度、失败原因、恢复点和最终验证结果 |

### 当前验收状态

| 检查项 | 本 RFC 的结论 | 生产实现状态 |
| --- | --- | --- |
| canonical storage 路径映射 | 已定义 | NOT YET |
| rootfs/runtime 排除 | 已定义为硬门槛 | NOT YET |
| 参考包格式和加密边界 | 已评估 | NOT YET |
| broker 一致性导出 | 需要新增/扩展 seam | NOT YET |
| 本地导出与导入 | 未实现 | NOT YET |
| 崩溃/断电可逆恢复 | 已定义协议 | NOT YET |
| rclone 构建与体积 | 已识别方案 | NOT YET |
| 真机权限、SELinux、重启 probe | 未验证 | NOT YET |

## 9. 本 PR 的交付边界

本 PR 只新增这份评估文档，不改变 Android 代码、存储路径、APK、远程凭据或设备状态。它完成的是 #122 要求的 feasibility/RFC 评估：明确哪些内容可以复用、哪些必须适配、哪些目录绝不能触碰，以及后续实现如何进入可验证的阶段。

合并后，生产实现应拆成独立 PR，并沿阶段 1 → 2 → 3 → 4 → 5 顺序推进；任何阶段未通过对应门槛，都不能把“文档评估完成”描述成“备份功能可用”。
