# 设置层级、Shizuku授权反馈与关于页静态审查

日期：2026-10-06。本仓固定HEAD `d64e7c06d8159be90737dd9ba03dd9bd5855f339`。新增全文6文件、1372行，确认1项P3。只静态阅读，不改产品源码、不编写或运行测试、不构建/启动模拟器/adb/Root/设备/云/模型，不打开实际授权对话框、manager、网页或更新检查，不读取凭据。独立新报告，旧报告不改。

## SHP01 / P3：不能再次弹出授权框时，页面继续承诺弹框，点击只写日志

[ShizukuPermissionScreen.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/ShizukuPermissionScreen.kt:142) NEED_PERMISSION分支始终使用shizuku_grant_btn/shizuku_grant_subtitle；中文资源 [strings.xml](/home/jiale/projects/minis-for-android/src/android/app/src/main/res/values-zh/strings.xml:512) 明写“弹出授权对话框”。第143行onClick调用ShizukuManager.requestPermission，没有检查返回值、显示失败提示或区分需转到manager的情况。

真实后端 [ShizukuBackend.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/offload/ShizukuBackend.kt:161) requestPermission在shouldShowRequestPermissionRationale()为true时只向AppLogger写“permission permanently denied — user must enable in the manager app”，然后return，不调用Shizuku.requestPermission。外层 [ShizukuManager.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/offload/ShizukuManager.kt:90) 在binder存活时先调用这个返回Unit的函数，随后无条件return true；该true也不是“授权框确实发出”的证明，UI还将它完全忽略。

静态条件反例：binder存活、未授权，且该API条件为true。snapshot保持NEED_PERMISSION；用户点击承诺弹框的授权行，后端只记日志，没有任何页面状态、toast、dialog或指向manager的说明。反复点击仍走同一路径。这里以项目自身明确的提前返回分支为依据，不假称已经在具体Shizuku/Sui/OEM设备上复现永久拒绝，也不将该API名称单独当平台语义证明。

非Sui页面虽然另有“打开管理器”入口，第151—155行没有告知当前必须在那里修改授权；Sui页面隐藏该入口，当前NEED_PERMISSION section也没有额外footer引导。因此确认的是授权失败/转人工路径的反馈缺失，不是绕过权限、不授权也执行成功或用户永远无法到其他应用修改权限。

[AppNavigation.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/navigation/AppNavigation.kt:1406) 现役SHIZUKU路由调用此页；SYSTEM分类第365行提供入口，导航第688行传入真实Routes.SHIZUKU回调。此页不是死代码。修复方向：后端返回能区分请求发出、需要manager操作、binder失效与请求失败的结果，页面根据结果给出明确操作指引；不要仅以binder存活返回“成功”。本轮不修改实现。

## Shizuku状态与VScreen自检边界

ShizukuPermissionScreen收集snapshot，并在进入时refresh；NOT_INSTALLED有Shizuku/AXManager/Sui安装入口，NOT_RUNNING有manager和复查入口，NEED_PERMISSION区分Sui无launcher，READY展示版本/uid与固定命令分类。backend listener会对binder收到/死亡和请求授权结果重算snapshot，不把“页面只有一次LaunchedEffect”直接推成所有状态永不更新。

[ShizukuBackend.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/offload/ShizukuBackend.kt:101) READY由binderAlive、permissionGranted决定，并非VScreen service probe。[VirtualScreenClient.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/vscreen/VirtualScreenClient.kt:104) runProbe在manager ready后另行requireRemote与binder.probe，并保存probe/device fingerprint；不能要求通用Shizuku授权页的READY替代虚拟屏自检，也不能据这页静态能力列表证明所有OEM命令实际成功。

[ShizukuOffloadHandler.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/ShizukuOffloadHandler.kt:48) help/version不要求Shizuku，service单独处理；其它命令先OffloadGate再refresh/READY检查，之后按分类dispatch。页面12分类与package/permission/activity/display/settings/user/network/input/notification/file/device/service主要分组对应；handler另有exec，所以不把固定列表称为完整命令枚举。该列表是功能介绍，不是逐项动态权限探测结果。既有android开发工具ownership报告中的Root/Shizuku backend声明问题不重复计数。

## 设置首页、分类路由与已有测试

SettingsScreen全文320行，SettingsCategoryScreen全文457行。根分组实际为models/agent/chat/appearance和files/runtime/system/data，FILES的inRoot默认true。分类文件注释仍称FILES隐藏，与实际enum/rootGroups不符；属于用户明确让其他Agent处理的注释噪音，本轮不新报或改注释。

CHAT/APPEARANCE/FILES在SettingsCategoryScreen内为Unit，但 [AppNavigation.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/navigation/AppNavigation.kt:666) 上方导航按category分派专门页面，剩余分类才进入该组件；不能把Unit分支脱离真实导航判为空白设置页。MODELS/AGENT/RUNTIME/SYSTEM/DATA回调实际接线，关于页、团队、例程、权限、rootfs、环境变量均可追到Routes，未因函数参数默认空lambda就认定生产入口无响应。

SettingsCategoryTest全文47行，调用真实enum/rootGroups/parse，检查顺序、FILES在根、所有inRoot集合、唯一key和legacy assistant/about映射、未知key拒绝；测试不是运行导航，所以不能称这些断言已验证所有按钮路由。未执行测试。

SubagentLimitsDialog对输入做digit过滤、长度限制，再用默认值与范围clamp保存；SubagentLimits的范围为depth1—5、timeout1—30分钟。空输入回默认值是当前明确行为，不直接当崩溃。现役子Agent新旧handler与这些prefs的全链语义仍需进一步对照，不能仅依据设置标题声称所有委派都遵守同一限制。未修改或打开对话框。

## 关于页与Skill行

AboutScreen全文158行：launcher adaptive drawable通过PackageManager转192px bitmap，版本来自BuildConfig；repo/第三方许可/GPL链接调用openExternalUrl，URL从现役AppLinks的Slacker-LLC/minis-for-android派生；CheckUpdateSection实际接入。没有访问链接、查询最新release或判远端文件当前可用，也没有读取/改写版权或许可证。

SkillRowItem全文135行：Switch与可选row-click分开，来源badge映射穷尽URL/FILE/BUNDLED/SESSION；[SessionSkillsSheet.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/SessionSkillsSheet.kt:175) 实际复用row与session override回调。SkillsManagementScreen第293行复用sourceIconAndColor，但没有调用SkillRowItem本身；因此文件注释“两个页面共享行”不能当调用事实。没有把辅助组件只被一个页面用认定功能缺陷，不为未来复用加抽象。

## 固定OpenMinis对照

只读 `/tmp/openminis-review-20261004` 固定HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，未fetch。上游ShizukuBackend第161—169行同样在rationale分支仅写日志return；ShizukuPermissionScreen第140—160行同样忽略requestPermission返回值。上游NEED_PERMISSION还设置grant_footer/sui footer，本地这一section没有footer；footer存在也不等于会根据这次提前返回更新提示，未把其存在当SHP01已完全解决的证明。

本轮对照权限UI片段，不恢复任何旧broker/PRoot/Alpine双栈，也不拿历史注释覆盖现役源码事实。

## 覆盖与完整性复核

| 新增全文文件 | 行数 |
|---|---:|
| [AboutScreen.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/AboutScreen.kt:1) | 158 |
| [SettingsCategoryScreen.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/SettingsCategoryScreen.kt:1) | 457 |
| [SettingsScreen.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/SettingsScreen.kt:1) | 320 |
| [ShizukuPermissionScreen.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/ShizukuPermissionScreen.kt:1) | 255 |
| [SkillRowItem.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/SkillRowItem.kt:1) | 135 |
| [SettingsCategoryTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/ui/settings/SettingsCategoryTest.kt:1) | 47 |

SettingsCategoryScreen分1—240、241—457完整读取，其余5文件全文完整；相关backend/导航/CLI/资源/调用方只片段复核，不重复全文计数。一次混合证据查询输出截断，正文涉及的handler与导航片段随后单独重读，不以截断区间升级覆盖。

累计全文 **1327 / 1546文件、257179行**；partial13、not_registered206、excluded124，全仓通读尚未完成。完成时核验全部1670 tracked路径SHA-256/字节数/正数文本行数、两仓固定HEAD、tracked/staged空diff、此前131份报告哈希、新报告绝对链接与行号、全文批次总计。只新建本报告和更新覆盖台账。
