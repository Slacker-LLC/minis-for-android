# 特殊权限、一键授权与就绪检查全文复核

2026-10-09，固定主线`3527ad8165f11c0f98d8a30f96098fc9280faa94`。旧202份报告及所有台账保留，仅新增本文/台账。未改产品代码、编写或运行测试、构建、启动模拟器/设备、Root或云资源，也没有实际授权或读取凭据。

## 旧一键授权范围缺口已改善

原2026-10-07-autonomy-permission-grants-audit的候选指出，仅dangerous与all-files/overlay的计划未包含其它特殊访问。现在SPECIAL_APPOPS合入SpecialAccess枚举：安装来源、精确闹钟、使用统计、全屏通知、写系统设置；exemptionPlan还加电池优化白名单与Data Saver白名单。旧缺失条件不能继续照搬。

plan使用结构化argv，只取App manifest声明权限，校验package/permission普通标识并去重；grantAll固定settings session、rootOnly及15秒单命令预算。没有模型输入原始shell脚本。一键授权还调用Agent侧allowAll，关闭则resetAll但不撤销Android授权，这是现役明确行为，不按“两个侧面必须同时撤销”自行扩张需求。

成功计数来自PrivilegedCommandRunner返回，不逐项重查系统授权；UI随后epoch刷新allGranted实际状态。命令退出成功与目标权限真正可用需要实际Android/OEM验证，本文不宣称全部appops在全部设备生效。exemptionPlan uid取真实Process.myUid，未写死10000。

## 就绪检查的实际口径

SpecialAccess按最低SDK决定是否适用，分别查询PackageManager、AlarmManager、AppOps、NotificationManager及Settings.System。Data Saver关闭或App豁免均算exempt。特殊项全部可选；REQUIRED仍Root、AllFiles、Background、Notifications四项。缺Shizuku/辅助服务时仍显示总览ready是既定产品规则，不重新报错。

工具关闭时不列对应Android授予项；location允许fine/coarse任一，photos允许全部媒体或所选媒体。它仅证明该就绪卡片口径，不能证明每个具体工具接受相同URI范围。rememberReadiness在resume及root状态改变时重查；检查弹窗是一次快照，点击修复会先关闭再跳设置，旧“回到同一弹窗仍旧状态”反例不成立。

## 保留候选，不夸大为新确认故障

OneTapAuthorizeSection的checked只remember(epoch)，不监听resume、Android权限撤销或其它页面Agent开关；需要真实页面保持composition的回程证据，先记外部状态失鲜候选。busy置true后grantAll/allowAll没有try/finally，异常或取消可能越过busy=false；实际Runner异常合同及导航scope取消行为需要继续核对，不直接声称每次失败都会永久锁开关。

SettingsReadiness.startSettings仍吞跳转异常无反馈，属于旧就绪报告已有候选。dataSaver probe异常默认true是乐观状态；明确系统服务失败样本未验证，不为了静态审查启动设备。所有Root/SELinux/OEM实际可用性均未验证。

## 已有测试范围

RuntimePermissionGranterTest全文覆盖纯plan的dangerous/特殊appops、标识拒绝、去重及package/uid白名单命令；不执行授权、不验证各API的actual status。SettingsReadinessTest覆盖枚举顺序、四项required、可选不计attention、省略不适用项；不调用真实probe、系统Intent或Compose生命周期。本轮未运行测试，也不因测试缺一个情景就立产品Bug。

| 全文文件 | 行数 |
|---|---:|
| src/android/app/src/main/java/com/openminis/app/permissions/SpecialAccess.kt | 83 |
| src/android/app/src/main/java/com/openminis/app/permissions/RuntimePermissionGranter.kt | 113 |
| src/android/app/src/main/java/com/openminis/app/ui/settings/OneTapAuthorizeSection.kt | 71 |
| src/android/app/src/main/java/com/openminis/app/ui/settings/SettingsReadiness.kt | 385 |
| src/android/app/src/test/java/com/openminis/app/permissions/RuntimePermissionGranterTest.kt | 97 |
| src/android/app/src/test/java/com/openminis/app/ui/settings/SettingsReadinessTest.kt | 75 |

六文件已完整输出；组合测试输出出现截断后，两份测试均单独重新完整读取。新增全文5文件、749行；已有全文重读1文件、75行不重复计数。

累计全文1304 / 1758文件、195112行；partial 7，未登记447。全库通读未完成。[新台账](/home/jiale/projects/minis-for-android/docs/archive/2026-10-09-code-review-coverage-3527ad81-special-access.json)。

交付校验旧202报告及全部旧台账hash、固定HEAD/origin/main、全快照Git blob/SHA-256/字节/行数、tracked/staged空diff。只新增报告及台账。
