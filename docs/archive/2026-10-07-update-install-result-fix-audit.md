# 更新安装结果与恢复流程远端复核

日期：2026-10-07。固定远端 `37158d188a190416f2b16b45dfb112ecf53cdd7b`。本轮新增全文4文件、773行；新增URI01一项，复核旧UR01/UR02。仅静态审查，没有改产品源码、编写或运行测试、构建、模拟器、adb、设备、Root或云任务，没有调用releases API、下载或安装APK。此前149份报告及全部旧覆盖台账保持原样，供Claude处理。

## URI01 · P3 · 调试安装接口在安装器启动失败时仍返回 launched

真实分发 [DebugRPCHandler.kt:107](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/debug/DebugRPCHandler.kt#L107) 将debug.update.install交给handleUpdateInstall。[DebugRPCHandler.kt:1329](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/debug/DebugRPCHandler.kt#L1329) 先检查path非空、文件存在及canInstall，然后在try内调用installApk，忽略其Boolean结果，无条件返回status=launched。

[UpdateChecker.kt:461](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/data/UpdateChecker.kt#L461) installApk自己捕获FileProvider或startActivity的Exception并返回false。因此这些失败不会到外层catch，调用者得到的依然是launched。可达输入包括存在但位于FileProvider未配置根目录的文件；还包括合法路径但系统启动安装器抛异常的分支。无需假设文件损坏后必然启动失败：这里确认的是内部已报告false后外层仍报告成功的确定逻辑。

静态顺序：RPC接受存在文件 → installApk遭遇启动异常，记录日志并返回false → handler丢弃false → 返回launched。附带can_install字段只表示权限查询结果，不能纠正实际启动状态。与普通UI [CheckUpdateSection.kt:218](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/ui/settings/CheckUpdateSection.kt#L218) 相比，UI读取ok并在false时显示启动失败，调试入口缺少同样判断。影响是调试/端到端自动化误判安装器已启动；不声称APK已安装、获得额外权限或存在远程公开接口。

方向记录：沿用已有Boolean返回值区分成功与失败，失败返回错误状态；不新增安装管理抽象。本轮只记录。原149份报告检索没有相同编号/结论，现有测试目录检索没有直接覆盖handleUpdateInstall或debug.update.install的用例；没有执行验证。

## 旧问题复核：UR01/UR02仍存在

原报告：[更新下载与恢复安装生命周期审查](/home/jiale/projects/minis-for-android/docs/archive/2026-10-06-update-resume-lifecycle-audit.md)，保持不变。

| 旧编号 | 固定远端状态与证据 |
|---|---|
| UR01 恢复时主线程全量APK哈希 | 仍在。ON_RESUME同步调用resumablePendingFile，再经verify进入Sha256.hex(file)；提取公用helper没有改变执行线程 |
| UR02 组合退出未注销监听 | 仍在。LaunchedEffect内addObserver，无removeObserver/onDispose/finally清理；不扩大为所有导航都永久泄漏 |

[CheckUpdateSection.kt:94](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/ui/settings/CheckUpdateSection.kt#L94) 注册回调第99行同步校验；[UpdateChecker.kt:449](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/data/UpdateChecker.kt#L449) 调verify；[PendingUpdateStore.kt:132](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/data/PendingUpdateStore.kt#L132) 进行hash；[Sha256.kt:18](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/util/Sha256.kt#L18) 持续读取到EOF，第30行file.inputStream().use在调用线程执行。download本身在IO dispatcher不意味着resume校验也在IO。这里未测设备耗时，不推断必然ANR。

## 版本、渠道、下载与恢复的核对范围

normalizeTag保留pet计数及dev/beta阶段，compareVersions按base再stage排序；稳定版排除GitHub prerelease标记及dev/beta，预发布本地版本可见预发布与正式版。UpdateCheckerVersionTest全文复读，但它在上一台账已full，不重复计数。新全文VersionSchemeTest比较构建versionCode和文档列出的版本排序；测试列表不涵盖所有可能字符串，不将阅读当成测试通过。

getPending注释称拒绝不再更新的目标，但真实版本检查由resumablePendingFile第445行完成；因此不以单一函数未比较版本报旧APK错误安装。超过24小时清pending，verify检查存在、记录长度及可选hash；hash来自下载后的本地文件，证明本地保存后的一致性，不独立证明release内容/签名。系统安装器仍承担安装验证。成功启动后清pending、用户取消后重新下载是明确现有策略，未另报隐性丢失。

download仍按版本命名并直接写最终文件；UI传版本，DebugRPC下载不传版本而用默认文件名。同步execute/读循环未主动绑定协程取消，catch Exception包含取消类异常；但是withContext返回也有取消语义，不能仅凭catch认定页面取消后所有下载都会成功回UI。并发写同名文件、未知长度EOF与release声明长度未比对、可选patch缺位与0不等价、超大计数toInt异常仍作为候选，尚不另编号。没有读取实际pending偏好或APK。

## 固定OpenMinis对照

只读固定上游HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c` 已有Git对象，设置GIT_NO_LAZY_FETCH=1，没有fetch。上游DebugRPCHandler第1051—1066行同样忽略installApk结果并返回launched；CheckUpdateSection第94—110行同样同步校验并注册未注销observer。URI01和旧生命周期缺口均有上游对应逻辑，不归因为本地Direct Ubuntu架构变更。上游片段不计本仓全文覆盖。

## 新交付与覆盖

| 新增全文文件 | 行数 |
|---|---:|
| [PendingUpdateStore.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/data/PendingUpdateStore.kt#L1) | 141 |
| [UpdateChecker.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/data/UpdateChecker.kt#L1) | 530 |
| [Sha256.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/util/Sha256.kt#L1) | 39 |
| [VersionSchemeTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/test/java/com/openminis/app/data/VersionSchemeTest.kt#L1) | 63 |

UpdateChecker分1—270、271—530完整阅读；其余三文件完整阅读无截断。已full的CheckUpdateSection和UpdateCheckerVersionTest再次全文复核；DebugRPCHandler仅查看分发与更新方法片段，不升级其全文状态。

新台账：[2026-10-07-code-review-coverage-37158d18-update-checker.json](/home/jiale/projects/minis-for-android/docs/archive/2026-10-07-code-review-coverage-37158d18-update-checker.json)。累计全文 **1200 / 1652文件、193654行**；partial4、not_registered448、excluded124，tracked1776。全库通读尚未完成。

交付前核对旧149份报告与所有旧台账哈希、固定本地/远端/上游HEAD、tracked/staged空diff、1776快照Git blob/hash/bytes/lines及报告证据行号。仅新建本报告与本轮新台账。
