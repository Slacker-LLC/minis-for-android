# 更新下载与恢复安装生命周期审查

日期：2026-10-06。固定 HEAD `d64e7c06d8159be90737dd9ba03dd9bd5855f339`。本轮新增 **1 项 P2、1 项 P3**；全文阅读 4 个文件、1145 行。静态审查，未改产品源码，未运行测试、构建、模拟器、设备、Root 或云任务，也没有访问 releases API 或下载 APK。独立报告，旧报告不改。

## UR01 / P2：恢复安装在生命周期主线程回调中同步读取整个 APK 做哈希

[CheckUpdateSection.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/CheckUpdateSection.kt:95) 注册的 LifecycleEventObserver 在 ON_RESUME 中直接调用 resumablePendingFile；[UpdateChecker.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/UpdateChecker.kt:457) 再同步调用 PendingUpdateStore.verify。[PendingUpdateStore.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/PendingUpdateStore.kt:132) 在有 sha256 时重新计算，[PendingUpdateStore.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/PendingUpdateStore.kt:142) 持续读文件至 EOF，没有 dispatcher 切换、异步调度或取消边界。正常 download 在第 374 行后会计算并保存 sha256，因此这不是只有异常记录才进入的分支。

静态序列：下载成功但尚未获得安装权限，用户进入系统设置并授予权限；返回关于页触发 ON_RESUME，先全量读取 APK，再发起安装器。此期间生命周期/UI 线程执行文件 IO 和散列，页面事件处理被占用。确定的是主线程上的无界于文件大小的工作，不声称必然 ANR、不捏造 APK 大小、设备速度或实测耗时。64KiB buffer 限制单次内存，不限制该同步操作耗时；download 本身在 IO dispatcher 不代表恢复校验也在 IO。

修复方向：保留校验，但把磁盘读取/哈希放在现有异步 IO 边界，回到有效生命周期后再启动安装器，防止多个 resume 的校验/安装竞争。只记录。

## UR02 / P3：安装恢复监听注册后没有对应注销，生存期超过 Composable

[CheckUpdateSection.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/CheckUpdateSection.kt:94) 使用 LaunchedEffect 注册 observer，函数末尾只有 addObserver；全文没有 removeObserver、finally、onDispose 或挂起清理。该 effect 的代码执行完就结束，离开组合不会替已登记到 lifecycle 的普通 observer 执行注销。监听闭包仍引用 context 和本次 remember 状态。

[Android Lifecycle 官方参考](https://developer.android.com/reference/androidx/lifecycle/Lifecycle)把 addObserver 定义为注册接收 owner 状态变化，removeObserver 定义为从已注册列表移除；注册本身不绑定某个 Composable 的退出。

现役 [AppNavigation.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/navigation/AppNavigation.kt:1462) 通过导航目的地展示 AboutScreen，后者第 136 行包含 CheckUpdateSection。影响以 owner 仍存在为前提：组合退出但导航 back stack 的 owner 被保留时，旧回调不会按组合退出同步清理；同一 owner 再创建该 section 可留下多份监听和旧状态引用。若 owner 随 pop 被销毁，其生命周期不再 resume，不能据此宣称每次进出关于页都会永久累积全 Activity 泄漏。

同样不声称多个回调必定重复打开安装器：installApk 成功启动后立即清 pending 可减少正常串行重复；确认的缺口是监听生命周期没有与界面匹配。修复方向：对现有注册建立显式注销/组合退出清理，不新增另一套更新管理器。本轮未改。

## 其它核对与未计数候选

PendingUpdateStore 读取过期记录、文件长度、可选 hash 核验已全文核对。getPending 的注释称检查目标版本，但真实检查在 resumablePendingFile 第 452 行，不能仅凭注释缺失报旧版本误安装。24h 使用墙钟，回拨可延长有效期；没有因此推断恶意 APK 被安装，暂记候选。

UpdateChecker 使用当前 AppLinks 仓库、protectedHttpsBuilder、channel 筛选和最高 APK-bearing 版本选择。版本测试覆盖 pet 计数、dev/beta/final、稳定渠道过滤及已接受的 1.01 历史例外。未将显式接受的手动安装例外报成新问题。可选 patch 缺位与 0 的比较并非完全等价、极大版本计数可 toInt 抛错，是后续边界候选；没有当前生产发布数据证明触发，不另计。

download 写 filesDir/updates 按版本命名，成功后保存下载文件自身长度/哈希。该哈希保证保存后的本地一致性，不能独立证明 release 来源或 APK 签名；系统安装器仍有自己的校验，未把此处描绘为绕过 Android 签名验证。DownloadResult.Success 没有独立对 release apkSizeBytes 比较、未知长度响应正常 EOF 的完整性语义需更多服务证据。普通 HTTP 截断是否抛 IOException 不能凭代码猜测。UI 和 DebugRPC 都调用 download，并发同名写入是候选，未实际触发。

同步 OkHttp 阻塞下载不主动绑定协程 cancellation、异常处理返回 Error，以及下载进度从 IO 回写 Compose 状态，后续仍可沿取消/状态链核对；本轮未伪装设备复现或单独确认为所有离页下载都会继续完成。installApk 成功即清 pending 是当前明确的避免重复弹窗策略，用户取消后重新下载也是注释明确行为，暂不当作隐藏数据丢失。

## 固定 OpenMinis 对照

只读 `/tmp/openminis-review-20261004` HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，未 fetch。上游 CheckUpdateSection 第 94—110 行同样在 LaunchedEffect 内注册 observer，没有退出注销，且同步调用 resumablePendingFile；上游 UpdateChecker 第 432 行调用 PendingUpdateStore.verify，后者同样全量读文件哈希。两项条件在该快照也保留，本地不是通过改换 Direct Ubuntu 才引入这些 UI 生命周期问题。这里只做对应片段对照，不将上游文件计入本仓全文覆盖。

## 全文覆盖与复核

| 本轮全文文件 | 行数 |
|---|---:|
| [PendingUpdateStore.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/PendingUpdateStore.kt) | 154 |
| [UpdateChecker.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/UpdateChecker.kt) | 538 |
| [CheckUpdateSection.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/CheckUpdateSection.kt) | 352 |
| [UpdateCheckerVersionTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/data/UpdateCheckerVersionTest.kt) | 101 |

累计全文 **1131 / 1546 个文件、222097 行**；partial 13，not_registered 402，excluded 124。全仓通读尚未完成。UI 和测试初次组合输出的截断已分别重读，两个大文件分段全文阅读；已有导航/关于页只复核调用片段，不重复计数。

完成时核对全部 1670 tracked 路径 SHA-256、字节数与正数文本行数，固定本仓/上游 HEAD、tracked/staged 空 diff、此前 91 份报告哈希、本报告文件链接/行号和批次全文总计。只新建本报告并更新覆盖台账，未读取真实 pending 偏好、APK、凭据或用户数据库。
