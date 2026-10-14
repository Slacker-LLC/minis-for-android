# 后台设置偏好同步修复与通知切换边界复核

日期：2026-10-07。固定远端HEAD `37158d188a190416f2b16b45dfb112ecf53cdd7b`。完整静态阅读3文件、565行，只新建本文及台账，保留此前166份报告和全部旧台账。无代码修改、测试编写/执行、构建、模拟器/adb/Root/设备/云任务。本轮没有新增确定问题编号，不把测试存在当成设备验证通过。

## BG01、BG02原反例已修

当前[ConfigBuiltins.kt:477](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/config/ConfigBuiltins.kt#L477) background.notifications使用background_settings/taskNotificationsEnabled，default=true，与仓库第24行及常量第111行一致，旧另写background_notifications_enabled导致消费者忽略的BG01原反例已修。

[BackgroundSettingsRepository.kt:94](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/data/repository/BackgroundSettingsRepository.kt#L94) prefsListener对taskNotificationsEnabled、backgroundOverlayEnabled、dynamicIslandEnabled分别重读prefs更新StateFlow，第107行注册，listener由字段强引用持有。CLI直接写正确prefs不再需要重启repo才能更新flow，旧BG02原反例已修。setter本身也直接更新flow；apply的普通异步落盘不能单独当作失败证据。

UI第94行收集这三个flow，服务第368行合并dynamicIslandEnabled，通知消费者此前已核对。此次没有重新全文阅读大型服务，不升级其覆盖。

background.enhanced在ConfigBuiltins第459行改为UnavailableField，并明确描述没有实际作用；旧BG03“不起作用却可正常写”的反馈原反例已有阻断。没有新增后台引擎，不因此报成仍假装提供开关。

## BG04降级通知刷新仍不对称

[AgentForegroundService.kt:442](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/service/AgentForegroundService.kt#L442) dynamic-island active分支在活跃session存在时调用refreshOngoingNotification，第455行。关闭开关后转入普通overlay分支，本次核对427—498行仍无对称的立即通知重建；后续需要其它服务/状态更新触发。沿用BG04，不重复编号。

仓库流已同步只证明服务得到新开关值，不能证明所有通知呈现立即完成降级。UI能够保存开关、服务能够隐藏浮窗与普通通知立即重建是不同证据边界。

## UI能力、权限与监听范围

[BackgroundSettingsScreen.kt:177](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/ui/settings/BackgroundSettingsScreen.kt#L177) overlay开关表示持久用户意图；未获SYSTEM_ALERT_WINDOW时跳系统设置并保存true，回前台重新探测权限，页内提供permission-needed文案。这个设计不等同绕过系统权限，服务仍要hasOverlayPermission。

dynamic岛UI第207行checked是enabled与capable合取，capability不足时禁用并显示unsupported。后台页ON_RESUME重测电池、overlay、dynamic能力；CLI capability仍在ConfigBuiltins第512行注册时决定，旧CB07的能力快照差异仍有源码支撑，是否后续重建registry需完整生命周期证据，不能由UI重新探测推断CLI已动态重探。

[BackgroundSettingsScreen.kt:120](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/ui/settings/BackgroundSettingsScreen.kt#L120) LaunchedEffect添加LifecycleEventObserver，没有removeObserver/onDispose。组合退出本身没有显式注销；owner销毁与组合退出是不同时间点。此前UR02在更新页面有同模式，本轮记录另一个调用点候选，不宣称任意导航都永久泄漏，也不新增计数。是否造成重复回调需核对实际owner复用与重入次序。

repo listener不注销，但MinisApp第965行初始化App持有repo，长期listener与App生命周期一致，不凭这个事实报成生产内存泄漏。测试每例创建repo没有显式注销；SharedPreferences弱listener与GC影响另需证据，未夸大。

## 设备测试实际覆盖与保留现场

[BackgroundSettingsPrefsSyncTest.kt:45](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/androidTest/java/com/openminis/app/data/repository/BackgroundSettingsPrefsSyncTest.kt#L45) 三例分别是直接prefs写通知键同步flow、直接prefs写dynamic键true/false同步flow、repo自己的overlay setter同步prefs/flow。awaitValue轮询最多约3秒，不验证真实CLI字段路由、前台服务、通知重建、系统权限或OEM后台行为。

setUp保存prefs.all并clear，tearDown清空并只恢复Boolean/Int。当前仓库键仅Boolean/Int，与已知schema一致，不能因没有恢复未知类型就断言当前用户配置被丢；若后续扩展schema需同步维护。测试自身会改变真实background_settings，相关App消费者可能响应，但本轮未执行，未发生本会话中的设备设置修改。

未覆盖外部写overlay键的listener分支、remove/clear全部偏好以及后台开关与权限撤销交错，不由现有3例推断这些场景通过。线程轮询使用墙钟是测试细节，没有时钟变更实测，不新增运行时问题。

## 固定OpenMinis对照

上游HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，GIT_NO_LAZY_FETCH=1读取现有BackgroundSettingsRepository第78—96行及尾部，无fetch。该文件结尾直接companion object，没有当前新增prefsListener注册路径；当前修复是对上游仅setter同步结构的实际改善。上游读取片段只作对照，不声称完整复审上游工程。

## 全文覆盖及不覆盖旧文件

| 本轮新增全文 | 行数 |
|---|---:|
| [BackgroundSettingsPrefsSyncTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/androidTest/java/com/openminis/app/data/repository/BackgroundSettingsPrefsSyncTest.kt#L1) | 73 |
| [BackgroundSettingsRepository.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/data/repository/BackgroundSettingsRepository.kt#L1) | 117 |
| [BackgroundSettingsScreen.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/ui/settings/BackgroundSettingsScreen.kt#L1) | 375 |

Repository1—117、设备测试1—73全文；UI分1—200/201—375。ConfigBuiltins、服务、MinisApp仅关联片段，不追加全文。

新台账：[2026-10-07-code-review-coverage-37158d18-bg-settings.json](/home/jiale/projects/minis-for-android/docs/archive/2026-10-07-code-review-coverage-37158d18-bg-settings.json)。累计全文 **1274 / 1652文件、209821行**；partial4、not_registered374、excluded124，tracked1776。全库通读未完成。

交付验证旧166份报告与全部旧台账hash、固定本地/远端/上游HEAD、tracked/staged空diff、1776快照blob/hash/bytes/lines、当前证据行号。只新建本文和本轮台账。
