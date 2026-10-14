# 外挂目录、文件提及索引与存储辅助链路静态审计

日期：2026-10-06。本库 HEAD：`d64e7c06d8159be90737dd9ba03dd9bd5855f339`。本轮仅阅读源码、已有测试及固定上游对象，核对 Android 官方授权语义；只新增本报告、更新覆盖台账，没有修改产品代码，没有运行构建、测试、模拟器、adb、Root、设备或云任务。

本轮新全文阅读 **12 个文件、2,436 行**。累计全文阅读 **681／1546 个纳入范围文件，139,928 行，44.0%**；13 个片段阅读，852 个尚未登记全文。124 个排除项仍在全库清单中。详见[覆盖台账](/home/jiale/projects/minis-for-android/docs/archive/2026-10-05-code-review-coverage.json)。全库阅读尚未完成，以下均为静态证据，不代表现场复现。

| 编号 | 优先级 | 新确认问题 |
|---|---|---|
| MI01 | P2 | 挂载配置写盘失败被吞掉，提交仍返回成功 |
| MI02 | P2 | 同一 URI 挂成两个名字后，删一个会撤掉另一个依赖的授权 |
| MI03 | P2 | 取消或失败的添加流程留下永久 URI 授权，可能挤掉正常授权 |
| MI04 | P2 | 详情页忽略改名、权限变更、卸载的失败返回，直接退出 |
| MI05 | P2 | 部分扫描失败仍被当成有效缓存，10 分钟内不再重试 |
| MI06 | P3 | 5,000 条全局预算被逐根重复发放，实际可收集五倍条目 |
| MI07 | P3 | 旧扫描退出会清掉新扫描的运行标志 |
| MI08 | P3 | 不可用挂载在列表仍显示为“只读” |

## MI01 · P2 · 内存提交成功不代表配置已保存

[MountedFoldersStore.kt:429](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/MountedFoldersStore.kt:429) 先调用 runtime 的候选校验，436 行发布新 entries，437 行调用 saveToDisk，随后无条件返回 true。[481 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/MountedFoldersStore.kt:481) 的保存函数把 writeText 放在 runCatching 中，但上层没有检查 Result。

当写盘因空间不足、目录不可写或 IO 错误失败，add 仍返回 Added，rename/setUserAllowWrite/remove 仍可能返回 true。内存与重启后加载的磁盘状态不同。例如原配置允许写入，用户关闭写入；内存先变为锁定，写盘失败却返回成功，重启若读到完整旧文件，userAllowWrite 又变回 true。此项只描述有旧文件保留时的条件链，不声称每次写盘失败都会恢复旧版。

还有相同持久化边界的失败窗口：直接 writeText 会截断目标，写到中途终止可留下不完整 JSON；[loadFromDisk:472](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/MountedFoldersStore.kt:472) 再吞掉解析失败，列表默认为空。两种情况都说明“提交成功”的持久化证据不足，合并为一项。

方向与后续验收：使用项目已有的可靠持久化模式，使磁盘失败明确参与提交结果，协调 runtime、内存及磁盘状态；不只给 writeText 外再包 catch。经正常添加、锁定、改名入口制造有界保存失败及提交中断，保存响应、配置摘要、重启列表及真实有效写入策略。本轮未执行。

## MI02 · P2 · 释放授权的单位是 URI，不是挂载条目

[add:134](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/MountedFoldersStore.kt:134) 只拒绝重名，没有拒绝相同 treeUri 或记录共享引用。因此同一树可以添加为 A、B 两个不同名字。[remove:225](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/MountedFoldersStore.kt:225) 在删 A 后，无条件按 A.treeUri 释放读写持久授权，未检查 after 中的 B 是否仍依赖该 URI。

Android 持久授权按调用方与 URI 保存；再次 take 同一 URI 会更新授权时间，并不创建一份属于 B 的挂载引用。release 会撤掉这个 URI 的持久模式。该语义见 [ContentResolver 官方定义](https://developer.android.com/reference/android/content/ContentResolver) 与 [AOSP URI 授权管理实现](https://android.googlesource.com/platform/prebuilts/fullsdk/sources/android-30/+/refs/heads/androidx-activity-release/com/android/server/uri/UriGrantsManagerService.java)。

删 A 的候选校验发生在 release 之前，B 当时仍有授权，所以可通过。随后 B 留在 Store，下一次 [validateMountEntries:303](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/MountedFoldersStore.kt:303) 会拒绝缺少 read grant；[UbuntuKernel.kt:565](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/ubuntu/UbuntuKernel.kt:565) 在新 shell 启动前校验整个挂载集合，可能阻止启动，直到前台刷新把 B 标为 inactive 或重新授权。已有非持久临时授权不等于仍拥有 required persisted grant。

方向与后续验收：按 URI 的实际剩余使用者决定是否释放，或在明确产品策略下拒绝重复树；不能仅按名字计数。正常添加同一树两次，再卸载其中一项，保存授权列表、剩余 Store 条目和新 shell 的准备结果；剩余合法挂载应可继续使用。本轮未执行。

## MI03 · P2 · 提前持久化的 picker 授权没有失败清理

[MountedFoldersScreen.kt:189](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/MountedFoldersScreen.kt:189) 在显示命名/写入选项之前就调用 handlePickerResult；[MountedFoldersStore.kt:528](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/MountedFoldersStore.kt:528) 已取得持久读写授权。但 [sheet 的 onDismiss:257](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/MountedFoldersScreen.kt:257) 只清 pendingPickedUri；添加被拒绝时，[267 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/MountedFoldersScreen.kt:267) 也只清 pending。只有成功条目的 remove 会 release，取消及拒绝流程没有 Store 条目可供日后移除。

因此选不同目录后取消命名、或重复触发添加失败，可不断积累不在挂载列表中的授权。Android 30 的官方 AOSP 实现上限为 128 条，超限时按持久时间裁掉旧授权，见 [MAX_PERSISTED_URI_GRANTS / maybePrunePersistedUriGrants](https://android.googlesource.com/platform/prebuilts/fullsdk/sources/android-30/+/refs/heads/androidx-activity-release/com/android/server/uri/UriGrantsManagerService.java)。这是版本明确的影响推导：废弃授权可以挤掉正常挂载或其它功能的旧授权；不把 128 当成所有 Android/OEM 的统一上限，也没有声称已在用户设备达到阈值。

方向与后续验收：跟踪本次流程新增的授权，取消/拒绝时释放不再使用的部分；必须保留已有成功挂载及其它功能共用的授权，不能盲目释放整个 URI。通过真实 picker 分别取消、重名拒绝、提交失败及正常成功，保存前后授权差集和挂载列表。批量验证应在隔离、有界环境进行，本轮未执行。

## MI04 · P2 · 保存失败与部分成功在 UI 中没有区别

[MountDetailScreen.kt:109](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/MountDetailScreen.kt:109) 顺序调用 rename 与 setUserAllowWrite，但不检查任何 Boolean，111 行直接 onBack。[202 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/MountDetailScreen.kt:202) 卸载也忽略 remove 的 false 并退出。

无需磁盘故障即可触发：已有挂载 A、B，用户把 A 改名为 B；UI 的名字格式检查通过，Store 的 [238 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/MountedFoldersStore.kt:238) 返回 false，页面仍退回。如果同时改变写入开关，改名失败后权限变更还可能成功，这次“保存”只应用一部分。runtime 候选校验拒绝权限变更或卸载时，也没有失败反馈，用户不能确认请求是否实际生效。

MI01 是 Store 把失败当成功；本项是 Store 已明确返回 false，UI 仍忽略，修好持久化也不会自动修好它。

方向与后续验收：按结果保留页面、反馈失败并核对组合变更的整体语义；优先复用现有 Store 与交互组件。正常详情页覆盖重名、改名加锁、候选拒绝和卸载失败，保存操作请求、返回值、页面状态与最终条目。本轮未执行。

## MI05 · P2 · 局部读失败冻结成十分钟的“新鲜”索引

[FileMentionIndex.kt:103](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/FileMentionIndex.kt:103) 在扫描开始前更新 lastScanSessionId/lastScanAt；[101 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/FileMentionIndex.kt:101) 后续只要同 session、TTL 内且 entries 非空就返回。[layerEntries:205](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/FileMentionIndex.kt:205) 先添加根条目；[scanGuestDir:258](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/FileMentionIndex.kt:258) 的 list 异常被转为 null，再跳出该目录，没有记录部分失败或重试需求。

具体序列：根 info 成功，随后读取 workspace 或某子目录时发生瞬时 IO 失败。根或此前扫描出的其它条目让 entries 非空，失败目录里的合法文件未收录；即使 IO 已恢复，重新打开 @ 菜单仍命中缓存，默认十分钟内不重试。正常 UI 的唯一刷新入口 [ChatViewModelMentionExt.kt:129](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModelMentionExt.kt:129) 只调用 refreshIfNeeded，没有接到强制重扫。

同一个缓存也不包含挂载集合的版本：当前闭包每次重扫能看到新 mounts，但在十分钟缓存命中时根本不会调用闭包。新挂载、改名、移除后的候选可能继续缺失或残留。此项是索引新鲜度，不声称缓存能够绕过底层授权检查。

方向与后续验收：区分完整扫描、部分失败与正在扫描，按失败和根集合变化使对应缓存失效；仍可分层发布已有结果，不必阻塞全部候选。经真实菜单在受控 IO 失败恢复后重开，并在 TTL 内添加/改名/移除挂载，保存扫描状态、根集合版本和候选列表。本轮未执行。

## MI06 · P3 · 名为全局的预算并非全局

[FileMentionIndex.kt:220](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/FileMentionIndex.kt:220) 每个根都获得完整 GLOBAL_SCAN_BUDGET=5000，workspace、attachments、shared、skills、memory 五个根之间没有扣减。每根各有至少 5000 个可收录条目时，collected 可含 25000 个子项加根项；这不是一个无限扫描证明，而是五倍预算的明确上界差异。

直到 [154 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/FileMentionIndex.kt:154) 才以全部 collected.size 减少 mount 预算，此时可已为负，mount 子文件全部不扫描、只留下 mount 根。发布时多次 associateBy/sort，过滤时又遍历完整候选集，额外条目实际参与内存及排序工作；未运行性能测量，不声称实际卡顿或 OOM。

方向与后续验收：明确真正的总预算与各层分配，给未扫描范围可见状态，避免把漏掉候选解释为无匹配。构造五根有界文件集，经真实扫描保存每层计数、总计数、截断状态和外挂目录结果。本轮未执行。

## MI07 · P3 · 旧任务 finally 能覆盖新任务的扫描状态

[refreshIfNeeded:106](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/FileMentionIndex.kt:106) 换 token 后取消旧 Job 并启动新 Job；publish 已两次校验 token，避免旧候选覆盖新候选。但 [scan 的 finally:185](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/FileMentionIndex.kt:185) 无条件把全局 isScanning 设为 false。

允许的交错是新扫描 B 已设置 true，旧 A 才在取消后进入 finally，B 尚未完成而标志变 false。初次扫描还没发布条目时，快速关闭重开菜单可再次发起扫描；不必依赖未确认的跨 session ViewModel 复用。本项仅确认状态竞争，不把旧结果覆盖作为发现，因为 publish 已有 generation 保护。

方向与后续验收：状态发布与清理均归当前 token 所有；验证连续触发重扫/取消时标志对应当前任务。保存任务 token、起止与观察到的状态时间线。本轮未执行。

## MI08 · P3 · 不可用挂载被标成只读挂载

[MountedFoldersStore.kt:271](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/MountedFoldersStore.kt:271) 在 URI 授权丢失、目录消失或卷不可用时把 entry.isActive 设为 false，isWritable 也随之为 false。实际 runtime 与 [索引:98](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/RuntimePathRegistry.kt:98) 都跳过 inactive 条目；但 [MountedFoldersScreen.kt:464](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/MountedFoldersScreen.kt:464) 的 badge 只检查 isWritable/userAllowWrite，不检查 isActive，列表依旧显示正常 Linux 路径及“只读”。

因此“还能读取、不能写”与“当前根本未挂载”被显示成同一种状态。方向：用 Store 已有 isActive 表达不可用和恢复动作，不另造一套权限状态。后续通过正常界面覆盖可读只读、用户锁定、授权撤销及卷恢复，保存 badge、条目和实际 namespace 的对应证据。本轮未执行。

## OpenMinis 对照与边界

上游固定为本机已有 `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，只读取 Git 对象，没有 fetch 或执行。片段不计入本库全文数。

- MI01：上游 [MountedFoldersStore.kt:341](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/data/MountedFoldersStore.kt#L341) 同样用 writeText/runCatching，调用方没有检查结果；本地新增候选校验及 mount 锁解决了另一类变更窗口，但没有解决保存失败。
- MI02：上游 [remove:174](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/data/MountedFoldersStore.kt#L174) 同样按 URI 释放而没有共享引用检查。本地更严格的 grant 再验证会让遗留条目明确失败关闭；这不能代替正确管理引用。
- MI03：上游 [picker:153](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/ui/settings/MountedFoldersScreen.kt#L153) 也提前持久化；308 行 dismiss 只清 pending，失败流程也没有 release。
- MI04：上游 [MountDetailScreen.kt:108](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/ui/settings/MountDetailScreen.kt#L108) 同样忽略保存 Boolean 后退出。
- MI05–MI07：上游 [FileMentionIndex.kt:95](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/data/FileMentionIndex.kt#L95) 有同样的时间/非空缓存门槛，206 行逐根发放预算，179 行无 token 的 finally 清理。其普通 File 扫描也在 listFiles=null 时继续，已有根项可让部分失败仍被缓存。本地 guest scanner 改了访问方式，但保留这些状态/预算问题。
- MI08：本地新增 isActive 并跳过失效项，旧式只读 badge 没有同时适配。没有把上游仅存 writable 的旧状态模型当成已支持相同 inactive 语义。

没有把 PRoot 上游路径推荐回现役生产栈。没有重复计数此前已全文检查的 LinuxFileOpsTools 搜索/目录截断、文件替换、修改锁等问题，也没有检查用户排除的注释噪音与基础设施重复。

## 全文覆盖与后续线索

本轮全文：FileMentionIndex、MountedFoldersStore、MountedFoldersScreen、MountDetailScreen、LinuxProvider、BoundedStreams，以及 MountedFoldersStoreVolumeIdTest、SafeFileTreeTest、AppSearchPolicyTest、LinuxProviderTest、LinuxPythonRunHandlerCleanupTest、BoundedStreamsTest。六份现有测试只阅读源码，没有创建或执行测试。路径、行数、全文范围与 SHA-256 全部登记台账。

LinuxProvider 明确让 App-owned linux.file.* 在 Ubuntu 未启动时可用，恢复失败后交给具体 handler 报启动原因；与现役架构相符，没有仅凭“跳过 availability”认定越权。SafeFileTree 的已有否定测试覆盖删除链接时不跟随目标。AppSearchPolicyTest 覆盖筛选、稳定排序及限额；未找到本轮可确认的新缺陷。

BoundedStreams 的字节预算不相信 declared size，但取消只检查线程 interrupted；一般 coroutine 取消是否能停止具体阻塞调用，需要继续核对真实调用链。InputStream.read 返回 0 被当成结束也保留为线索，尚未证明现役来源会在非零请求长度下如此返回，不计数。本轮没有运行导入或网络下载。

仅片段复核 ChatViewModel、ChatViewModelMentionExt、MinisApp、RuntimePathRegistry、UbuntuKernel，不增加它们的全文覆盖。与上游及平台的差异均按已读片段限定，不作“上游全部已修复”或“设备必然如此”的结论。
