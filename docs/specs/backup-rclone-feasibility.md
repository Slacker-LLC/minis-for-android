# #122 加密备份包与 rclone 远程同步可行性评估

状态：RFC 评估稿。本文只定义备份/恢复边界、路径映射、风险与实施门槛，不声称备份功能已经实现。当前 runtime/storage 事实以最终目标分支源码与 `docs/contracts/03-STORAGE-CONTRACT.md` 为准。

## 1. 结论

需求可行，但必须服从 direct Ubuntu 的现役存储模型：

1. 活跃 guest 用户数据由 App 私有目录持有并从 `Context.filesDir` 派生；
2. `/data/adb/minis/rootfs` 是 Root-owned、可替换运行时，不是用户数据，必须排除；
3. 历史 `/data/adb/minis/{workspace,sessions,memory,skills,shared,home,mcp-servers}` 只是 legacy migration source，不能作为新备份实现的 canonical source；
4. 备份 exporter/importer 应在 Android App 的受控存储层工作，不应为了备份重新建立特权 broker、Root RPC 或第二份持久化真源；
5. rclone 只作为可选传输层，在本地包格式、恢复事务与安全边界验证后接入。

`.minisbak` 可以作为跨设备、可校验、可加密的单文件包格式。真正的生产实现应拆成独立任务并逐项通过单元、集成和真机验收。

## 2. 审计基线

外部参考实现绑定到完整提交：

```text
7b00d5c5610d9de9239de195e92e4e97ebe4ec87
```

该提交包含约 47 个文件、14,041 行新增内容。来源仓库、许可证和历史来源边界以根目录 `PROVENANCE.md` 为准；这里只参考实现思路，不把外部产品身份或旧路径假设当作本项目当前事实。

参考范围包括备份 crypto/format/export/import、remote rclone bridge/store、`deps/build_rclone_android.sh` 和 streaming package 设计。

## 3. 当前存储合同与备份边界

`docs/contracts/03-STORAGE-CONTRACT.md` 是路径行为合同。实际 host 绝对路径从 `Context.filesDir` 派生，不作为跨安装固定常量；下表使用逻辑 backing 名称。

| 数据域 | 当前 canonical backing | 默认是否备份 | 实现边界 |
| --- | --- | --- | --- |
| Session 数据 | `<filesDir>/minis-sessions/<session_id>/...` | 是 | 包含 workspace、attachments、offloads、browser 等受支持子树；需要一致性快照 |
| Global memory | `<filesDir>/minis-global/memory` | 是 | 由 App 存储层直接读取并保持 guest `/memory` 语义 |
| Global skills | `<filesDir>/minis-global/skills` | 是 | 数据与本地元数据版本必须一致 |
| Shared | `<filesDir>/minis-global/shared` | 可选 | 明确 category、用户选择、大小和路径上限 |
| MCP server 数据 | `<filesDir>/minis-global/mcp-servers` | 后续 | 凭据/token 不得明文进入 manifest |
| Global workspace | `<filesDir>/minis/workspace` | 可选 | 仅在产品明确选择后纳入 |
| Home | `<filesDir>/minis/home` | 首版否 | 避免把运行环境状态误当用户备份 |
| Rootfs | `/data/adb/minis/rootfs` | 否 | 可替换运行时；严禁恢复进用户数据事务 |
| Legacy Root-owned 用户目录 | `/data/adb/minis/{workspace,sessions,memory,skills,shared,home,mcp-servers}` | 否 | 只允许现有一次性迁移逻辑读取，备份功能不得重新依赖 |
| Cache / staging | App cache 等 | 否 | 临时事务空间，不是持久真源 |

### 3.1 逻辑 category 映射

| 逻辑 category | 首版建议 | 必须补齐 |
| --- | --- | --- |
| `CHATS` | Session 可恢复数据 | session schema、附件引用、目录元数据、一致性快照 |
| `MEMORY` | global memory | guest/host 映射、版本与冲突策略 |
| `SKILLS` | global skills | 目录内容、启用状态与版本兼容 |
| `SHARED_FILES` | 可选 shared | 用户选择、大小限制和白名单 |
| `PROVIDERS` | 仅用户确认的非凭据配置；凭据单独加密 | provider schema、密钥轮换、导入确认 |
| `MCP_SERVERS` | 后续纳入 | token/私钥与普通配置分离 |
| `ENVIRONMENT_VARIABLES` | 后续纳入 | 敏感变量默认不导出 |

备份实现应复用现有 Repository、`UbuntuPaths` 和 Android 存储 seam；如果需要一致性目录快照，应新增最小 App-owned snapshot/export 接口，而不是让 Root 读取用户数据或恢复旧特权 IPC。

## 4. 冷备份协议

用户发起导出必须形成可证明的一致性边界，而不是在目录持续变化时无锁遍历。

### 4.1 导出状态机

```text
requested
  → acquire shared export/restore lock
  → quiesce writes for selected categories
  → validate free space and destination
  → enumerate App-owned canonical data
  → build manifest and package
  → verify local size/hash
  → fsync + atomically publish complete .minisbak
  → release lock and resume writes
```

要求：

1. exporter/importer 共用互斥；同一时间只允许一个备份或恢复事务；
2. 快照点暂停会修改目标 session/memory/skills 等 category 的写入；
3. 开始前检查 staging 与目标空间；
4. 遍历顺序稳定，只使用逻辑相对路径，拒绝绝对路径、`..`、NUL、源 symlink 和 canonical escape；
5. 每个条目记录 category、相对路径、大小、必要模式、内容 hash 和 schema 版本；
6. 生成到临时文件，完成后 flush/fsync，再原子发布最终文件；
7. 结束后恢复写入，并记录事务结果和失败原因。

### 4.2 包格式

建议复用参考实现的 `minisbak/1` reader/writer 思路，而不复制旧路径假设：

- 扩展名 `.minisbak`，MIME `application/x-minisbak`；
- manifest 明确格式版本、产品 schema、category、兼容性与完整性信息；
- 大型 JSONL 按固定上限分片；
- 先压缩后加密；完整性校验与分段 AAD 明确绑定路径/category；
- reader/writer 覆盖 Zip64、长路径、空目录、截断、重复条目、恶意压缩比和大小上限；
- manifest/救援元数据不得包含 API key、OAuth token、私钥或敏感环境变量值。

## 5. 恢复协议与回滚

恢复比导出风险更高。首要不变量是：失败不能破坏 `/data/adb/minis/rootfs`，也不能把半完成的新用户数据伪装为完整恢复。

### 5.1 恢复阶段

1. 获取同一互斥并 quiesce 目标 category 的写入；
2. 写入前完整验证 manifest、schema、产品身份、空间、条目数量、总大小；
3. 对每个路径执行 traversal、symlink、重复条目、类型冲突和大小上限检查；
4. 解密/解压/校验后写入 App-owned staging；staging 不得成为新真源；
5. 每个 category 全部验证完成后再提交，并保留旧数据直至提交成功；
6. 提交失败、进程崩溃或断电时，根据事务日志删除未提交 staging 或恢复旧 category；
7. 目标文件最终由当前 App identity 持有，不信任备份包携带的旧 UID/GID；
8. 恢复完成后通过 Repository/session/path probes 与最小 guest smoke test验证可读性；失败必须进入可观察的恢复失败状态。

### 5.2 禁止行为

- 不得把备份直接解压到 `/data/adb/minis`；
- 不得恢复 `/data/adb/minis/rootfs`；
- 不得把 legacy Root-owned 用户目录重新定义为 canonical destination；
- 不得先删除原 category 再复制新目录来假装原子替换；
- 不得信任包内 UID/GID；
- 不得通过 `DirectRootRunner`、`su -c` 或新增 Root RPC 执行用户数据备份/恢复；
- 不得在 App 主线程执行阻塞式 rclone 调用。

## 6. rclone 远程传输可行性

rclone 适合作为传输层，不承担备份一致性、加密或恢复事务语义。

### 6.1 集成和体积

参考构建脚本通过 gomobile 生成 Android `.aar`，当前工程也把 rclone AAR 作为独立 Android 构建依赖。生产接入仍需：

- 固定 rclone-mobile、Go、NDK 和 ABI 参数；
- CI 记录 AAR hash/ABI/体积并验证可重建；
- 保持 arm64-v8a 与 x86_64 构建边界；
- 不用删除完整性或安全校验换体积。

### 6.2 凭据和调用模型

- WebDAV、SMB、SFTP、S3、FTP 等 remote 配置由 Android 安全存储管理；
- 临时 rclone config 只在受控 cache 生命周期存在并及时清理；
- 禁止交互式 OAuth 阻塞 UI；
- `Gomobile.rcloneRPC` 视为阻塞调用，必须在 worker 上执行，长任务需超时/取消/状态；
- 首版上传完整、自包含 `.minisbak`，远端先临时名，完成后再发布；
- 不能假设所有 remote 都支持服务端 hash；至少保留本地密文 hash、字节数和完成状态；
- 断点续传首版可退回完整文件重新上传，只有能力与一致性测试充分后再加分片协议。

推荐顺序仍是先本地 SAF 导出/导入，再加 remote，以隔离包格式/恢复事务与网络故障。

## 7. 威胁模型和恢复不变量

| 风险 | 必须满足的控制 |
| --- | --- |
| 路径穿越 | 逻辑相对路径、拒绝 `..`/NUL/symlink、canonical containment |
| 压缩炸弹/空间耗尽 | manifest 总大小与条目上限、预检空间、解压限额 |
| 包截断/替换 | manifest、hash、分段 AAD、原子最终发布 |
| 凭据泄露 | Android 安全存储、临时 config、日志脱敏、manifest 不含明文 secret |
| 并发写入 | shared lock、quiesce、事务状态 |
| 断电半恢复 | staging、fsync、category 提交记录、旧数据保留/回滚 |
| 恢复破坏 runtime | rootfs 明确排除，恢复前后 probes |
| 新设备身份不同 | 使用当前 App identity，不信任包内 owner |
| 远端假成功 | 临时远端对象、大小/hash 记录、完成后发布 |

事务日志至少要回答：开始时间、schema、各 category 的验证/提交状态、旧数据位置、失败后的清理/回滚动作。日志不得包含凭据。

## 8. 分阶段实施与验收门槛

| 阶段 | 交付物 | 完成条件 |
| --- | --- | --- |
| 0 | 本 RFC、路径映射、排除清单、风险基线 | 合同审阅通过；不声称功能已实现 |
| 1 | `minisbak/1` reader/writer 与跨平台测试 | Zip64、截断、重复条目、长路径、hash、加密流测试通过 |
| 2 | App-owned exporter、sessions/memory/skills 映射、本地目标 | synthetic fixture + rooted Android 导出/再读通过；无第二真源 |
| 3 | 加密包、分阶段 restore、事务日志和可逆回滚 | kill/空间不足/校验失败均可安全退出并恢复 |
| 4 | 可选 rclone remote store | ABI/体积/可重建、超时取消、临时凭据、上传验证和网络故障测试通过 |
| 5 | UI、状态可观测性、恢复帮助、发布门禁 | category、进度、失败原因、恢复点、最终验证可见 |

### 当前 RFC 验收状态

| 检查项 | 本 RFC 的结论 | 生产实现状态 |
| --- | --- | --- |
| 当前 storage 路径映射 | 已按 direct runtime 更新 | 以源码为准 |
| rootfs 排除 | 硬门槛 | 以实现测试为准 |
| 包格式/加密边界 | 已评估 | NOT YET（本 RFC 不声称实现） |
| App-owned 一致性导出 | 需要明确 snapshot seam | NOT YET（本 RFC 不声称实现） |
| 崩溃/断电可逆恢复 | 已定义协议 | NOT YET（本 RFC 不声称实现） |
| rclone transport | 已识别集成边界 | 构建存在不等于备份功能已实现 |
| 真机权限/恢复 smoke test | 必须验证 | NOT YET |

## 9. 本 RFC 的交付边界

本文不改变 Android 代码、存储路径、APK、远程凭据或设备状态。它只把 #122 的 feasibility 边界与当前 direct Ubuntu/App-owned storage 对齐。

后续生产实现应拆为独立 PR；任何阶段未通过对应门槛，都不能把“RFC 完成”描述成“备份功能可用”。
