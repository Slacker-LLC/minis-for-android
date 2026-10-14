# 挂载存储、共享授权与详情保存修复边界

日期：2026-10-07。固定远端HEAD `0b96f7aa2a46c313b896a6f66501586e7caba81a`。全文静态阅读3文件、870行。旧178份报告及全部台账原样保留，另建本轮文件。未修改代码、编写或执行测试、扫描用户目录、获取授权、构建或操作模拟器/adb/Root/设备/云资源。无新增独立问题编号。

## MI01原写盘失败假成功已修

[MountedFoldersStore.kt:438](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/data/MountedFoldersStore.kt#L438) commitSnapshot在mount mutation gate内先运行候选callback，拒绝则false；随后检查saveToDisk，只有成功才发布entries并true。saveToDisk调用writeTextAtomically，用同目录tmp写入再ATOMIC_MOVE替换，异常清tmp并false。原先内存先发布、写盘Result被忽略的路径已经改变。

[MinisApp.kt:860](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/MinisApp.kt#L860) callback接入UbuntuRuntime.reconcileExternalMounts；[UbuntuKernel.kt:517](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/runtime/ubuntu/UbuntuKernel.kt#L517) 实际先validate候选，再停止终端与执行协调的当前命令，没有直接把candidate写成新Store。因此不能据callback先于写盘就误报“写盘失败却已发布candidate”；失败可能已停止执行，但旧Store仍保留。

原子rename不等于所有电源掉电场景已实测。磁盘完成后协程取消、多个Store实例共用固定tmp等是后续候选，本轮没有补足实际调用证据或执行设备验证，不新增故障编号。

## MI02共享URI保护已接入

[MountedFoldersStore.kt:231](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/data/MountedFoldersStore.kt#L231) remove在commit后仅当grantReleasable(after, treeUri)才release，helper检查剩余entries是否仍引用同一URI。A/B同treeURI卸载A后B还在，原无条件release窗口已修。

测试第四例直接覆盖同URI剩余使用者与全部移除，未调用Android ContentResolver；只能证明决策predicate，不是设备授权释放成功证据。release仍runCatching，失败不会恢复已删除mount；不能称每个系统授权均已立即清除。

## MI03普通取消/拒绝有补偿，添加取消仍待覆盖

[MountedFoldersScreen.kt:193](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/ui/settings/MountedFoldersScreen.kt#L193) picker先记录是否已持授权，再取授权。[MountedFoldersStore.kt:559](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/data/MountedFoldersStore.kt#L559) releaseAbandonedGrant要求newlyTaken且无人使用，保护已有授权与当前挂载。

Screen onDismiss在未addInFlight时补偿；AddResult.Rejected后也补偿并反馈。原“取消/正常拒绝只清UI”路径已修。添加scope.launch中store.add及回main补偿没有try/finally，异常或取消可绕过Rejected分支；onDismiss在addInFlight时刻意不release。因此本轮只确认正常终态，保留MI03取消/异常收拢边界，不另编号。仅有Boolean alreadyHeld也不追踪已有read授权后来新增write模式的差集，候选未升级。

## MI04错误反馈已修，组合保存仍非整体事务

[MountDetailScreen.kt:111](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/ui/settings/MountDetailScreen.kt#L111) 保存以短路AND顺序执行rename与setUserAllowWrite；rename false后不继续权限变更。false会Toast并保留页面，卸载false也反馈，原忽略Boolean并退回的案例已修。

反向的部分提交窗口仍在：rename成功已commitSnapshot并持久化新名，随后的write开关变更若callback或写盘失败返回false，页面反馈失败但新名已经保存，旧write政策仍保留。UI没有回滚rename或一个组合Store调用。沿用MI04“部分成功”边界，不把Toast当作整体事务。建议未来按明确产品保存语义验收两阶段结果；不自行改代码。

无saving gate，重复点击与移除后再保存是候选，本轮未证明实际UI事件顺序，不新增。两个编辑值remember(entry.id)，外部同id更新不自动重建本地草稿，需结合产品编辑冲突规则，不机械判错。

## 授权与路径保护真实存在

Store只接受externalstorage authority和安全volume/segments，检查persisted read及raw storage能力；POSIX目录从系统volume root重新派生，逐用户component NOFOLLOW拒绝symlink/non-directory。写权限由persisted write、raw capability、受控probe与userAllowWrite共同决定，inactive保留记录但不进入活mount集合。

不持久化host路径作为授权，不使用任意root shell，不从历史PRoot/minisd文案推断旧runtime返回生产。路径检测和真正bind间的设备文件系统竞争仍须实际launcher全链验证，不能仅凭检查描述绝对无TOCTOU。

[MountedFoldersScreen.kt:483](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/ui/settings/MountedFoldersScreen.kt#L483) 当前badge首先判断!isActive显示unavailable，旧MI08把不可用一律显示只读的分支已改变；列表只读片段，本轮不计全文。

## 现有测试与OpenMinis对照

MountedFoldersPersistenceTest四例：成功原子替换且无tmp、目标非空目录导致失败保留旧内容并清tmp、父目录不存在返回false、共享URI决策。调用生产helper，但没有实际Store/runtime callback、磁盘失败时entries一致性、UI两阶段保存、协程取消或ContentResolver集成证据。未运行或新增测试。

上游HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，GIT_NO_LAZY_FETCH=1读取既有Store192—228行，无fetch。上游rename先更新entries再save/onChange，当前先校验/写盘后发布是实际改善；不从上游旧host路径设计恢复当前已退出的权限模型。上游片段不计本地覆盖。

## 全文覆盖与保留

| 本轮新增全文 | 行数 |
|---|---:|
| [MountedFoldersStore.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/data/MountedFoldersStore.kt#L1) | 592 |
| [MountDetailScreen.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/ui/settings/MountDetailScreen.kt#L1) | 227 |
| [MountedFoldersPersistenceTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/test/java/com/openminis/app/data/MountedFoldersPersistenceTest.kt#L1) | 51 |

Store分1—300、301—592完整读取，测试全文，MountDetailScreen首次合并输出截断后单独全文重读227行；均补足完整证据。MinisApp、MountedFoldersScreen与runtime只复核片段，不升级全文。

新台账：[2026-10-07-code-review-coverage-0b96f7aa-mount-store.json](/home/jiale/projects/minis-for-android/docs/archive/2026-10-07-code-review-coverage-0b96f7aa-mount-store.json)。累计全文 **1319 / 1655文件、220380行**；partial5、not_registered331、excluded124，tracked1779。全库通读未完成。

交付校验旧178份报告及全部旧台账hash、固定本地/远端/上游HEAD、tracked/staged空diff、1779快照Git blob/hash/bytes/lines和证据行号。仅新增本文与台账。
