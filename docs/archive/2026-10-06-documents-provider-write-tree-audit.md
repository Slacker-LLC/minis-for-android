# 系统文档提供器：写入持久化、创建与目录授权审查

日期：2026-10-06。固定 HEAD `d64e7c06d8159be90737dd9ba03dd9bd5855f339`。本轮新增 **2 项 P1、2 项 P2**，全文阅读 2 个文件、452 行。只做静态排查；未改产品代码、未运行测试/构建/模拟器/设备或云任务，未访问真实用户文件。独立新报告，旧报告不改。

## DP01 / P1：第一次 fsync 后继续写入，返回成功但不会再次持久化

[MinisDocumentsProvider.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/providers/MinisDocumentsProvider.kt:277) 的 committed 从 false 初始化。onFsync 在 [MinisDocumentsProvider.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/providers/MinisDocumentsProvider.kt:323) 调用 commit，成功写回后 [MinisDocumentsProvider.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/providers/MinisDocumentsProvider.kt:352) 将它设为 true；[MinisDocumentsProvider.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/providers/MinisDocumentsProvider.kt:340) 此后直接跳过所有 commit。[MinisDocumentsProvider.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/providers/MinisDocumentsProvider.kt:304) 的 onWrite 仍修改 localAccess 并返回 size，没有把 committed 重置为 false。

静态序列：以 rw 打开 shared 文件；写 A；fsync 将 A 写回 guest；再写 B，onWrite 返回完整 size；再次 fsync 和关闭都因 committed=true 不写回。[MinisDocumentsProvider.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/providers/MinisDocumentsProvider.kt:334) 关闭还删除本地临时文件，最终目标只有 A，B 丢失。即使客户端能从当前描述符读到 B，重开目标也只会看到 A。该序列没有异常或拒绝，不是只在磁盘失败时发生。

[Android ProxyFileDescriptorCallback 契约](https://developer.android.com/reference/android/os/ProxyFileDescriptorCallback#onFsync())要求 onFsync 将此前写入的数据持久化；它并不是写入生命周期的终结回调。这个状态机把“本轮已同步”误当成“永远不再变更”。仅最后关闭、不曾中途 fsync 的单次写入不受此具体条件影响。没有执行文件写入或设备复现。

修复方向：用 dirty 状态或写入代际追踪每轮变更，后续成功写入必须使它待提交；同时检查 fsync/关闭实际错误，不用一次性 latch 表示完整文件生命周期。

## DP02 / P1：创建同名文档会直接清空已有文件

[MinisDocumentsProvider.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/providers/MinisDocumentsProvider.kt:216) 直接拼接 parent/name；非目录分支 [MinisDocumentsProvider.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/providers/MinisDocumentsProvider.kt:221) 调用 writeBytes 空数组，没有检查目标是否已经存在。[WorkspaceFileClient.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/files/WorkspaceFileClient.kt:76) 委托 SecureFileAccess.writeBytes；[SecureFileAccess.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/files/SecureFileAccess.kt:113) 调用 writeAtomic，[SecureFileAccess.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/files/SecureFileAccess.kt:349) 删除已有目标并移入空临时文件。这是替换语义，不是独占创建。

静态反例：shared/a.txt 已有内容，外部客户端在同一目录请求 createDocument 的 displayName=a.txt；provider 返回已有 documentId，却已将原文件改为空，即使客户端随后取消、不写正文也已丢数据。对同名目录请求用文件 MIME 也可能触发底层替换目录，另见此前 BF05 的通用替换行为；本项以普通同名文件为充分证据，不重复泛化底层问题。

[DocumentsProvider 的 createDocument 契约](https://developer.android.com/reference/android/provider/DocumentsProvider#createDocument(java.lang.String,java.lang.String,java.lang.String))要求创建新文档/身份，允许为避免冲突调整显示名称。这里并没有“覆盖现有文档”的显式请求。系统 UI 可能预先处理某些重名情形，但 provider 仍直接处理所有合法授权客户端请求，不能据此认为入口安全。

修复方向：保证创建不会替换既有数据；拒绝冲突或选择唯一名称，仍须处理创建时竞争。项目的 uniqueChildPath 已存在，但它仅列首 500 项，且检查与写入分离，不能直接当独占创建保证。未修改任何实现。

## DP03 / P2：声明支持目录关系检查，却没有实现 isChildDocument

[MinisDocumentsProvider.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/providers/MinisDocumentsProvider.kt:102) 返回 Root.FLAG_SUPPORTS_IS_CHILD；整个类没有 override isChildDocument，也没有其它继承中间层（直接继承 DocumentsProvider）。路径合法检查 isSafeDocumentId 只验证允许的顶层与名称，不能替代 parent/descendant 的判定。

[Android isChildDocument 文档](https://developer.android.com/reference/android/provider/DocumentsProvider#isChildDocument(java.lang.String,java.lang.String))明确要求 provider 实现该方法以支持 ACTION_OPEN_DOCUMENT_TREE。此实现因而没有兑现目录选择/后代访问所需的能力，目录 tree URI 下的子文档访问不能凭 queryChildDocuments 成功就认定可用。这里不声称所有单文件 ACTION_OPEN_DOCUMENT 均失败，也不声称它形成越权；缺口方向是授权功能不可用，未验证具体 ROM 的错误界面。

修复方向：依据已限定的虚拟 document ID 实现真正的目录后代关系，保持完整路径组件边界与共享目录范围；能力标记与实现一致。未新增代码或测试。

## DP04 / P2：目录只列首 500 项，剩余文件无法从提供器发现

[MinisDocumentsProvider.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/providers/MinisDocumentsProvider.kt:152) 固定 list(null,parentPath,500,0)，仅遍历该页。[WorkspaceFileClient.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/files/WorkspaceFileClient.kt:175) 返回 next_offset，[WorkspaceFileClient.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/files/WorkspaceFileClient.kt:203) 已有 listAll 分页模式；provider 没有读取 next_offset、没有下一页参数/加载标记或后续补全。

[SecureFileAccess.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/files/SecureFileAccess.kt:282) 的列表按名称排序后 drop/take，因此 501 个合法可见子文件会确定只返回前 500 项；系统重新查询仍从 offset=0 开始，无法显示第 501 项。被名称校验排除的条目还会消耗当前页名额，但不是隐私文件过滤本身的错误。

[queryChildDocuments 契约](https://developer.android.com/reference/android/provider/DocumentsProvider#queryChildDocuments(java.lang.String,java.lang.String%5B%5D,java.lang.String))将它定义为目录直接子文档查询，额外内容需明确加载并通知重查。这里不是公开的“只显示 500 项”产品限制，也没有这种反馈。问题是发现遗漏，不能说磁盘文件已经被删除；知道直接 URI 的单文件访问是另一条路径。

修复方向：复用现有分页读取，保持输出预算与可见性提示，避免把内部传输页大小当完整目录。未创建测试文件或运行验证。

## 安全边界、已有测试与不计数候选

Manifest 的 provider 以 applicationId 派生 authority、要求 MANAGE_DOCUMENTS 并支持 URI 授权；不能据 exported=true 宣称任意应用能直接读整个私有目录。ID 拒绝点名、空组件、反斜线、控制字符和越界 scope；memory/skills 在打开、创建、删除、重命名路径均有只读判断。数据经现役 WorkspaceFileClient/SecureFileAccess，不直接打开旧 Root-owned backing。

Proxy 描述符关闭时提交失败被日志吞掉并删除唯一暂存副本；未单独新增一项与 DP01 重叠的丢写报告，后续应在同一持久化修复中检查失败回执与可恢复暂存。多描述符、guest 同时修改的快照覆盖候选尚未展开；不把路径安全当并发数据完整性保证。

已有 MinisDocumentsProviderTest 的 23 行只验证合法 ID 与拒绝路径，不覆盖创建冲突、连续 fsync 后写入、目录授权或分页；本轮全文读完，未运行或编写测试。

## 固定 OpenMinis 对照

只读 `/tmp/openminis-review-20261004`，HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，未 fetch。

- DP01：上游 openDocument 返回 ParcelFileDescriptor.open，未采用本地 Proxy 暂存/committed 状态机，不能归为相同 bug，也不建议恢复旧宿主直开路径。
- DP02：上游 createNewFile 在同名已存在时不清空文件，但仍返回原 ID，不完全符合“新文档”语义；它没有本地空数组替换带来的确定清空回归，不能把上游直接复制视作完整修复。
- DP03：上游同样声明 FLAG_SUPPORTS_IS_CHILD，完整类没有 isChildDocument，固定版本也保留缺口。
- DP04：上游遍历 parent.listFiles，未固定截成 500 项；旧宿主文件访问不能照搬，现役 client 已有分页可复用。

首次 Proxy API 与 AOSP 源码访问失败，之后成功获取 Android 官方 API 文档并核对相应方法；不把失败页面用作证据。只按已读源码与有效 API 契约下结论。

## 全文覆盖与复核

| 本轮全文文件 | 行数 |
|---|---:|
| [MinisDocumentsProvider.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/providers/MinisDocumentsProvider.kt) | 429 |
| [MinisDocumentsProviderTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/providers/MinisDocumentsProviderTest.kt) | 23 |

累计全文 **1093 / 1546 个文件、217602 行**；partial 13，not_registered 440，excluded 124。全仓通读仍未完成。Provider 分 1—220、221—429 全文读取，输出无未修复截断。底层文件、Manifest 已登记全文，本轮片段核对不重复计数。

完成时核对全部 1670 tracked 路径、SHA-256、字节数与正数文本行数，固定本仓/上游 HEAD，tracked/staged 空 diff、此前 85 份报告哈希、本报告链接与行号、批次全文总计。只新建本报告并更新覆盖台账。
