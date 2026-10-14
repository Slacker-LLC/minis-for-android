# 自主模式与 Android 一键授权静态复核

日期：2026-10-07。固定远端版本 `37158d188a190416f2b16b45dfb112ecf53cdd7b`。新增全文6文件、854行；没有新增已闭环的缺陷，记录两处待核对的产品语义差异。仅静态阅读，没有改产品代码、编写或运行测试、构建、启动模拟器、adb、Root、设备、云任务或授权实际权限。此前142份报告及所有旧覆盖记录保持原样。

## 自主模式的实际边界

[AutonomyStore.kt:58](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/config/AutonomyStore.kt#L58) ASK所有配置变更确认，SMART在涉及secret或非NORMAL风险时确认，FULL跳过确认。缺失或未知持久化值回落SMART，不回落FULL。Store在MinisApp第535行初始化，Settings页面collectAsState监听模式，点击选项调用setMode。

[ConfigBuiltins.kt:108](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/config/ConfigBuiltins.kt#L108) permissions.autonomy.mode注册为READONLY，writer也拒绝；[ConfigBridge.kt:537](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/config/ConfigBridge.kt#L537) 真实批量写接口使用当前模式、每项risk及auditNewValue判断确认，再进入原审计/执行路径。未发现通过这个配置字段自行修改自主模式的入口；此结论仅限所核对接口，不扩张成所有设备控制能力都无法影响App。

集合append/remove总是提供auditNewValue，因而touchesSecret会为true，即使集合负载本身没有密钥。继续核对现役五个集合的risk：Providers、Models、ThinkingRules、EnvVars、ModelSlots都为SENSITIVE，后者不支持增删。它们本来就应在SMART确认，因此本轮不把这个标记的过宽语义计成现役普通设置被错误拦截。这里的片段核对不升级集合全文覆盖。

## 两处待核对的界面语义差异

1. **一键授权的文案范围大于计划范围。** [strings.xml:780](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/res/values/strings.xml#L780) 表述为grant every permission this app declares；[RuntimePermissionGranter.kt:28](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/permissions/RuntimePermissionGranter.kt#L28) plan仅对Declared.dangerous生成pm grant，另为MANAGE_EXTERNAL_STORAGE、SYSTEM_ALERT_WINDOW生成cmd appops set。其它非dangerous声明直接跳过，没有结果项。Manifest还声明REQUEST_INSTALL_PACKAGES、REQUEST_IGNORE_BATTERY_OPTIMIZATIONS、SCHEDULE_EXACT_ALARM、PACKAGE_USAGE_STATS等，第17/42/49/53行可定位。需要核对产品是否只承诺此处支持的授权种类，以及目标Android实际protectionLevel/特殊授权状态；不能将所有声明权限都等同于可以pm grant的运行时权限。本轮没有访问设备，也没有验证这些特殊权限实际可用，所以不宣布一键授权一定成功、一定失败或存在Root越界。
2. **重置全部没有重置自主模式。** [OffloadPermissionScreen.kt:233](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/ui/settings/OffloadPermissionScreen.kt#L233) 确认按钮只调用OffloadPermissionManager.resetAll、resetEpoch++以及MinisConfigPermissionStore.setEnabled(true)。[OffloadPermissionManager.kt:525](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/offload/OffloadPermissionManager.kt#L525) resetAll仅移除toolRegistry对应level键。AutonomyStore单独保存autonomy_prefs/mode，未被调用；Full auto选择在重置后仍保留。[strings.xml:764](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/res/values/strings.xml#L764) 文案却说reset all permission preferences。若按钮定义包含自主模式，应恢复默认SMART；若只重置工具开关，应缩窄文案。未确认维护者的按钮范围，不把它升级为已证实的越权或安全绕过；系统权限也不会由这个按钮自动撤销。

## 授权结果与调用链

[RuntimePermissionGranter.kt:64](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/permissions/RuntimePermissionGranter.kt#L64) grantAll读取自身package的requestedPermissions、去重名称、限制identifier形状，逐条调用PrivilegedCommandRunner，固定sessionId/settings、operation/grant-own-permission、MUTATING、15秒、rootOnly。输入不是模型提供的raw shell字符串。[PrivilegedCommandRunner.kt:414](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/tools/android/PrivilegedCommandRunner.kt#L414) 仍经validateRootArgv和trusted Android tool path，再DirectRootRunner.runArgv。

遇unavailableReason提前返回，UI使用“Root不可用”文案；这也可能掩盖runner的BAD_PARAMS或可信工具不存在等原因，保留为错误诊断候选，不凭仅可构造的内部无效参数算真实用户路径故障。正常失败只显示权限名，成功计数是命令exitCode，不是额外读取系统授权状态；报告不把命令成功数当真机授权证明。

Settings coroutine没有try/finally复位granting，意外抛出时存在卡住候选；现有runner的普通结果错误与取消路径不能直接证明仍在可见页面的异常被触发，所以不计新项。虚拟屏master与子项用remember缓存权限，跨入口同步需结合实际活跃修改方继续闭环，也未凭局部状态缓存直接宣布失同步。

## 测试只作静态证据

AutonomyPolicyTest覆盖ASK/SMART/FULL、混合风险、secret、未知值回落。RuntimePermissionGranterTest调用生产plan，覆盖运行时权限、两种appops、名称校验与去重；没有调用grantAll、真实runner或结果状态读取。

AutonomyConfirmationInstrumentedTest调用真实ConfigBridge，动态注册两个ClosureField，覆盖普通设置SMART直通、敏感设置拒绝、ASK确认、FULL直通和只读mode拒绝；未测试重置全部、授权UI、持久化重启或真机pm/appops。类名含Instrumented不代表本轮运行过；没有启动它。

## OpenMinis对照与覆盖

固定上游 `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c` 的Git树有OffloadPermissionScreen，但这两个路径AutonomyStore.kt与RuntimePermissionGranter.kt不存在。仅核对路径存在性，没有取回缺失对象，也没有把旧权限页自动当新模块的行为基准。

| 完整读取文件 | 行数 |
|---|---:|
| [AutonomyConfirmationInstrumentedTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/androidTest/java/com/openminis/app/config/AutonomyConfirmationInstrumentedTest.kt#L1) | 122 |
| [AutonomyStore.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/config/AutonomyStore.kt#L1) | 64 |
| [RuntimePermissionGranter.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/permissions/RuntimePermissionGranter.kt#L1) | 83 |
| [OffloadPermissionScreen.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/ui/settings/OffloadPermissionScreen.kt#L1) | 469 |
| [AutonomyPolicyTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/test/java/com/openminis/app/config/AutonomyPolicyTest.kt#L1) | 44 |
| [RuntimePermissionGranterTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/test/java/com/openminis/app/permissions/RuntimePermissionGranterTest.kt#L1) | 72 |

权限页分1—240、241—469读取，其余五文件完整输出，无截断；ConfigBridge、ConfigBuiltins、MinisApp、Runner、Manager、Manifest及集合只复核相关片段，不新增全文覆盖。

新累计台账：[2026-10-07-code-review-coverage-37158d18-autonomy-permissions.json](/home/jiale/projects/minis-for-android/docs/archive/2026-10-07-code-review-coverage-37158d18-autonomy-permissions.json)。累计全文 **1157 / 1652文件、187310行**；partial4、not_registered491、excluded124，tracked1776。仍未完成全库通读。

交付检查：此前142份报告及旧台账hash、固定本地/远端/上游HEAD、tracked/staged空diff、远端1776个快照文件与Git blob一致、台账hash/bytes/lines及本报告证据行号。仅新增本文和新版覆盖记录，没有覆盖旧文件。
