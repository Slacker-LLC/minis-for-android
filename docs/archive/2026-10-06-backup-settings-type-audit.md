# 备份设置类型与启动恢复静态审查

日期：2026-10-06。新增 1 项 P1。只写独立报告与阅读台账，不修改产品代码、旧报告或真实偏好。

本地 HEAD `d64e7c06d8159be90737dd9ba03dd9bd5855f339`；固定 OpenMinis `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`。上游只读已有 Git 对象。未拉取、构建、运行测试、模拟器、adb、Root、设备、云任务、凭据或子 Agent；未生成、修改、导入备份包，未执行故障注入。额外只查询 Android 官方 API 文档核实类型异常。

## BS01 · P1 · 恢复允许错误偏好类型落盘，可使后续启动持续失败

[BackupAppSettings.kt:44](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupAppSettings.kt:44)白名单只记录文件与键名，未记录每个键的预期类型。[BackupAppSettings.kt:96](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupAppSettings.kt:96)根据包内 t 标签解析为 Boolean/Int/Long/Float/String；[BackupAppSettings.kt:137](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupAppSettings.kt:137)只检查能否解析以及键名是否允许。因此某值完全符合包内自报类型，但不符合真实偏好类型时，仍会进入 writes。

真实恢复 [BackupImporter.kt:1107](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupImporter.kt:1107)使用该 plan；[BackupImporter.kt:1113](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupImporter.kt:1113)按解析出的 Kotlin 类型调用对应 putBoolean/putInt/putLong/putFloat/putString，1120 行 apply，没有核对键的类型契约。1123 行还将接受数计为已恢复设置并提示重启。

明确静态反例：minis_auto_compact_prefs 的 autoCompactOnThreshold 应是 Boolean；包中把该允许键声明为字符串值，plan 不拒绝，恢复入口就调用 putString 写入该键。普通未加密备份允许包含 app_settings，不需要绕过任何 Root、远程或 manifest 认证边界。正常同版本导出会带正确类型，本项指向损坏、外部提供或格式不一致的备份在恢复时缺少必要拒绝。

[AutoCompactPrefs.kt:44](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/AutoCompactPrefs.kt:44)固定调用 getBoolean(KEY_ENABLED,false)。[Android SharedPreferences 官方说明](https://developer.android.com/reference/android/content/SharedPreferences#getBoolean(java.lang.String,%20boolean))明确：默认值处理键不存在，已有键类型不是 Boolean 时抛 ClassCastException，不会自动按缺省值修复。

[MinisApp.kt:299](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/MinisApp.kt:299)在 Application.onCreate 内直接调用 AutoCompactPrefs.prime；该调用不在 try/runCatching 中，前面的 Xposed runCatching 已结束，后面的 try 保护 NativeCrashHandler，不保护这个 prime。错误类型成功持久化后，每次后续启动都再次读取并抛异常，正常界面入口尚未可用。崩溃记录机制不等于恢复了错误偏好。这里登记为可推导的启动失败链，未宣称已在设备实测循环崩溃。

同一 schema 缺口也影响其他允许键，例如 [MemoryGlobalPrefs.kt:33](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/MemoryGlobalPrefs.kt:33)按 Boolean 读取 memory.global.enabled；不把每个键分别计数。

[BackupAppSettingsTest.kt:91](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/backup/BackupAppSettingsTest.kt:91)名为 wrong type 的测试只验证布尔标签配不可解析字符串、整数溢出和未知标签。它没有验证“合法 String 标签写入应为 Boolean 的已允许键”，因此不能证明键与类型契约已被保护。本轮测试源码只读。

固定上游 backup 目录没有 BackupAppSettings.kt，也没有同名设置恢复扩展；本项登记为本地新增扩展缺口，不声称固定 OpenMinis 同样可经此入口触发。可复核 [固定上游目录](https://github.com/OpenMinis/OpenMinis/tree/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/backup)。

修复方向记录：在现有白名单中明确每个键及前缀族的真实类型，恢复前拒绝类型不符的值，避免包内标签决定存储类型；合法数值还需按实际消费者要求核对有限性和范围。已存在错误类型时需有受控恢复路径，不能以清除全部用户数据代替修复。本轮不实现、不写测试、不改变真实设置。

## 已读边界与暂不编号的观察

完整通读 BackupAppSettings 的偏好筛选、提示词计划、定时任务计划和 document/isEmpty。权限文件与秘密文件不在白名单；未知提示词模块被拒绝；FULL 定时任务恢复为 disabled，这些已有保护不误报为缺失。

恢复通过 apply 写入与 startup cache 的关系已核实，提示重启是现有行为，不把所有缓存未即时刷新都认定为新 Bug。定时任务导出 take(200)、提示词与模块超长省略，仍需对照真实创建边界及统计，未仅凭常量另增问题。ScheduledTask.fromJson 的真实权限语义不能只由本文件注释推断，已读历史审查结论不重复。

## 覆盖与核验

本轮全文 3 个文件、478 行：

- [src/android/app/src/main/java/com/openminis/app/backup/BackupAppSettings.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupAppSettings.kt)：225 行。
- [src/android/app/src/main/java/com/openminis/app/data/MemoryGlobalPrefs.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/MemoryGlobalPrefs.kt)：38 行。
- [src/android/app/src/test/java/com/openminis/app/backup/BackupAppSettingsTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/backup/BackupAppSettingsTest.kt)：215 行。

累计全文 968 / 1546 个文件、196231 行；partial 14，not_registered 564，excluded 124。全仓通读尚未完成。AutoCompactPrefs 此前已全文，本轮复核不重复计数；Importer、Exporter 片段只记 partial，MinisApp 按既有状态保留。

1670 tracked 路径、SHA256、字节数与正整数文本行数全量核验；66 份旧报告哈希不变，HEAD 固定，tracked/staged diff 为空；新报告引用行号与台账批次总数核验。没有实际恢复或启动结果工件，本报告是静态证据。
