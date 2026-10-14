# 通知、提醒与旧闹钟迁移静态审计

日期：2026-10-05。当前提交：`d64e7c06d8159be90737dd9ba03dd9bd5855f339`。固定 openminis 对照：`b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`（`/tmp/openminis-review-20261004`）。

本轮新增 12 项静态发现，另建报告；仅写本报告和阅读台账，没有修改产品代码、旧报告，没有运行构建、测试、模拟器、设备、Root 或云任务。P2 表示迁移、取消、持久化、权限显示或参数语义错误。并发、系统投递和 OEM 行为均未做设备复现。

| 编号 | 问题 |
|---|---|
| ND01 | 旧重复闹钟迁移到系统时钟时丢掉重复星期 |
| ND02 | 迁移不取消旧重复 PendingIntent，可留下无法通过现役命令管理的提醒 |
| ND03 | 过期条目也算迁移失败，整批保留并反复提交已成功的条目 |
| ND04 | 延迟通知有持久记录，但重启后没有重新注册系统闹钟 |
| ND05 | pending 清掉尚未投递的到期记录，随后 cancel 不能取消系统闹钟 |
| ND06 | 通知注册表整表读改写没有协调，多个命令及 receiver 可互相覆盖 |
| ND07 | 重用通知 ID 时系统闹钟替换、注册表却追加，留下重复和假待办 |
| ND08 | 两套通知监听器独立授权，旧路径按包名检查会误报可用 |
| ND09 | 七天历史保留期只在新通知写入时清理，没有通知就不实际过期 |
| ND10 | 权限页重置后开关仍保留旧值，可显示已拒绝但实际恢复允许 |
| ND11 | CLI 计时器缺少平台上限并使用 Int 乘法，可溢出成错误时长 |
| ND12 | CLI 接受绝对 ISO 闹钟时间，却只传小时和分钟，丢掉指定日期 |

## ND01 — 迁移丢掉重复模式

[MinisApp.kt:1186](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/MinisApp.kt:1186)迁移旧 alarm 时只传 HOUR、MINUTES、MESSAGE、SKIP_UI，没有读取旧记录的 DAILY/WEEKDAYS，也没有 EXTRA_DAYS。现役 [AlarmOffloadHandler.kt:159](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/AlarmOffloadHandler.kt:159)却已经正确将这两种重复模式转换为星期列表。平台以 EXTRA_DAYS 表达重复星期。[Android AlarmClock API](https://developer.android.com/reference/android/provider/AlarmClock)

明确前提：升级安装仍有旧 `minis_alarms_prefs / alarms_json` 中的 DAILY 或 WEEKDAYS 记录。迁移会将其提交为没有重复星期的一次性闹钟；批次成功后旧记录还会被删除。系统时钟中的新闹钟不能保留用户原来的重复语义。新建用户走现役 Clock handler，不走这个旧存储路径。

修复方向：迁移复用现有重复模式转换，并核对下一次时刻；只有实际保留原语义才能结束该条迁移。固定 openminis 的迁移函数也缺少星期转换，属于继承缺口。

## ND02 — 迁移后旧重复提醒仍可继续运行

迁移 [1151 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/MinisApp.kt:1151)没有调用 AlarmManager.cancel 或 PendingIntent.cancel。成功分支 [1206 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/MinisApp.kt:1206)只清理 JSON。旧 [AlarmOffloadManager.kt:92](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/offload/AlarmOffloadManager.kt:92)曾通过 setRepeating 注册 DAILY；旧 receiver 仍在 Manifest 注册，收到广播后 [AlarmReceiver.kt:79](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/offload/AlarmReceiver.kt:79)调用 onAlarmFired，但找不到记录只会记日志，仍继续 [115 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/offload/AlarmReceiver.kt:115)发通知。

明确前提：升级后系统中仍保留旧 DAILY PendingIntent。迁移至 Clock 后，旧 Minis 重复广播没有被撤销；清掉旧 JSON 也不会撤销已注册的系统动作。用户在 Clock 删除新闹钟，不能取消这条旧提醒。现役 [cancel/list 别名](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/AlarmOffloadHandler.kt:276)只打开 Clock，不再管理旧 ID。

这不是“旧文件看起来没删”的维护问题，而是仍可被系统回调的旧执行路径。不同安装/升级过程是否保留 token 未实测；结论限于上述前提。修复方向：按持久身份撤销旧系统动作，确认迁移交接完成后再删除旧记录。不能假定重复闹钟只再响一次。

## ND03 — 一个过期条目让整批迁移永久重试

[1166 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/MinisApp.kt:1166)和 [1169 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/MinisApp.kt:1169)遇到过期 timer/ONCE 就 `skipped++`。后面 [1197 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/MinisApp.kt:1197)又用同一个 skipped 计数表示真正提交失败；只有 skipped==0 才删除整批。

例如旧批次含一个过期一次性提醒和一个 DAILY。过期条目每次都增加 skipped；DAILY 提交成功却没有逐条标记或移除；整批一直保留，每次进程启动都再次向 Clock 提交 DAILY。真实失败与已经过期没有分开，成功记录也没有独立消费。在某些 Clock 实现中可能反复激活或更新已有闹钟；是否产生多个实体依赖实现，本轮不声称所有 OEM 都必然重复建行。

固定 openminis 是无条件清空整批；本地为避免失败丢失增加 `skipped==0`，却引入这个可达重试条件错误。历史 `RUNTIME-HISTORY.md` 提到 #233 只作检索线索，不代表问题已经解决。修复方向：逐条区分成功、真正失败和已过期，仅保留需要重试的条目；保留可核验的迁移身份。

## ND04 — 延迟通知没有开机恢复链

[NotificationOffloadHandler.kt:246](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/NotificationOffloadHandler.kt:246)向 AlarmManager 注册一次性闹钟，[261 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/NotificationOffloadHandler.kt:261)保存 id、内容、触发毫秒和 request_code。但是 [开机 receiver](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/offload/AlarmReceiver.kt:44)只恢复 ScheduledTask 和浮宠，不恢复 ScheduledNotificationStore；对该 Store 及其键的全文检索只有 notification handler 和投递 receiver 的读写，没有重排入口。

Android 会在设备重启时清除注册闹钟。[AlarmManager API](https://developer.android.com/reference/android/app/AlarmManager)因此“安排明天提醒→重启”会留下 pending 中的 JSON，实际不会再投递；超过时间后 pending 又将它清掉。修复方向：开机后从权威待办状态重新注册，定义已错过时刻的处理及投递身份，不能以偏好记录存在作为恢复成功证据。固定 openminis 的这条 Store 链也没有在本轮所读开机入口中恢复。

## ND05 — 查询会先销毁取消所需的记录

[handlePending:336](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/NotificationOffloadHandler.kt:336)先调用 sweepExpired；[Store:72](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/offload/ScheduledNotificationStore.kt:72)只比较 trigger_at_ms 与 now，时间已过就删 JSON，没有确认投递、没有取消 OS 闹钟。

setExactAndAllowWhileIdle 仍受投递频率约束，低功耗模式下间隔可能明显加长，平台文档给出约 15 分钟的情形。[AlarmManager API](https://developer.android.com/reference/android/app/AlarmManager)因此“已到请求时刻、尚未实际投递”并不矛盾。此时执行 pending 清掉条目，再执行 cancel --id，[302 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/NotificationOffloadHandler.kt:302)因为 entry 为 null 跳过 cancelAlarmFor，只取消已经显示的 notification，仍返回成功。迟到的广播 [Receiver:80](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/offload/ScheduledNotificationReceiver.kt:80)读取 extras，不要求注册表中仍有待办，继续 post。

结果是已告知取消的提醒仍可能发出；静态触发顺序确定，具体系统延迟未实测。修复方向：请求时刻已过不等于投递完成；查询不应删除尚可执行的系统动作。按 ID 取消需覆盖仍存在的系统 token，receiver 也须遵守持久取消状态。

## ND06 — 不同通知的读改写可互相丢弃

[Store.add:24](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/offload/ScheduledNotificationStore.kt:24)、remove、clear 和 sweepExpired 都独立读取/替换同一 JSON 数组，没有共享锁或事务。[桥接请求线程](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/GuestCommandBridge.kt:263)允许多个 CLI 请求并发处理；广播 receiver 又可独立 remove。

交错一：A、B 都读取旧数组，各加入自己条目，后写者覆盖前写者，但两条 OS 闹钟已经分别注册。交错二：新增 B 读取含 A 的旧数组；A 投递后 receiver 删除 A；新增 B 随后写回旧快照，又把 A 放回待办。取消/清空与新增也会发生同类覆盖。SharedPreferences 的单次替换原子性不能使整段读改写原子化。

具体后果是系统闹钟与待办表不一致，部分提醒无法通过 ID 撤销或变成假待办。旧 N04 是 ScheduledTask 的另一个 JSON 存储；本项核实的是独立的 ScheduledNotificationStore 和广播/CLI 生产调用链。修复方向：让所有实例的变更共享一致性边界，同时协调 OS 注册和持久状态，不能只换成 commit。

## ND07 — 相同 ID 的系统替换与存储追加不一致

[scheduleDeferred:232](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/NotificationOffloadHandler.kt:232)接受调用方给定 id，以相同 id.hashCode 构造同组件 PendingIntent；FLAG_UPDATE_CURRENT 更新现有 token，随后注册同身份闹钟。存储 [add](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/offload/ScheduledNotificationStore.kt:24)却只追加，不按 ID 替换。

同 ID 先安排 A、再安排 B，OS 只有后一次闹钟，pending 有两条。实际 B 投递时 [Receiver:87](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/offload/ScheduledNotificationReceiver.kt:87)只 remove 第一个匹配项，留下 B 的假待办；单 ID cancel 同样只移除一条。修复方向：明确 ID 是更新还是必须唯一，在存储和系统注册两侧执行相同规则；拒绝或原子替换，不能一边替换一边追加。

这不需要 UUID 哈希碰撞。不同 ID 使用哈希仍有旧 N05 同族问题，未另算一个新发现。

## ND08 — 包级授权不能证明所用组件有授权

Manifest 同时声明 [旧 offload 监听器](/home/jiale/projects/minis-for-android/src/android/app/src/main/AndroidManifest.xml:542)和 [新 notifications 监听器](/home/jiale/projects/minis-for-android/src/android/app/src/main/AndroidManifest.xml:682)，两者 label 都是 app_name。新 [NotificationTools:50](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/NotificationTools.kt:50)在 API 27+ 查询具体新组件；旧 [isEnabled:121](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/offload/MinisNotificationListenerService.kt:121)只要 enabled_notification_listeners 中任一组件属于本包，就返回 true。平台授权查询以 ComponentName 为参数。[NotificationManager API](https://developer.android.com/reference/android/app/NotificationManager)

仅授权新组件时，CLI 的 settings/list 判定已授权，跳过授权设置 gate，却通过旧组件的 instance 读取，返回 listener_not_connected；等再久也不能让一个没授权的组件自动连接。仅授权旧组件时，CLI 可用，新 recent/search 却要求授权。API 26 上新路径也采用包级判断，有同一组件混淆。

两个都是现役入口：[MinisApp 注册新工具](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/MinisApp.kt:566)及 [旧 CLI](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/MinisApp.kt:870)。修复方向：统一监听实例及授权来源，或至少按实际使用的组件检查、引导；不要让包级状态代替组件状态。固定 openminis 树只有旧监听器，没有这套新增的历史监听器组合。

## ND09 — 查询过滤不等于存储过期

[NotificationHistoryRepository.kt:45](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/NotificationHistoryRepository.kt:45)只在 record 事务中删除七天以前的行。search [56 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/NotificationHistoryRepository.kt:56)只加时间过滤；count、数据库打开和监听器断开也不执行清理，没有定期清理入口。

如果最后一条非空通知后没有新记录，或用户撤销监听授权，旧原始标题/内容可以在私有 SQLite 中保留超过七天。搜索限制保证工具不返回过期行，但代码声明的“self-expiring / seven days”不是实际磁盘保留行为。上限一千条仍有效；本项不声称撤销后工具还能绕过系统授权读取。现有 NotificationHistoryPolicyTest 只核对窗口常量、字段上限和 LIKE 转义，不能证明实际删除。

修复方向：在明确生命周期及清理入口落实保留期，或准确说明当前是查询窗口；同时定义授权撤销后的保存策略。验证需检查过期行的实际数据库状态。

## ND10 — 重置授权后显示的仍是 remember 快照

[OffloadPermissionScreen.kt:187](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/OffloadPermissionScreen.kt:187)执行 resetAll，只关闭确认框，没有刷新各开关。[PermissionRow:279](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/OffloadPermissionScreen.kt:279)、[AgentPolicyRow:261](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/OffloadPermissionScreen.kt:261)和 [vscreenOn:133](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/OffloadPermissionScreen.kt:133)都只在首次组合时读取权限。

[resetAll:525](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/offload/OffloadPermissionManager.kt:525)删除显式配置，getLevel 恢复默认值。例：用户把 contacts 拒绝，随后点全部重置；页面仍显示关闭，实际已恢复 attended 默认 BYPASS。相反，原本打开的 opt-in a11y/vscreen 恢复拒绝，页面仍显示开启。返回页面重新创建才能刷新，当前屏幕的权限承诺不可信。无人值守的默认敏感权限另有拒绝规则，不扩大成所有后台访问都被放开。

修复方向：UI 订阅权威权限状态，或者重置时统一失效并重读各行；总开关和子开关不能各自保留陈旧状态。上游三态下拉也使用 remember 的初始快照，本地换成两态开关没有消除这个问题。

## ND11 — CLI 没有复用已存在的时长约束

[AlarmOffloadHandler.kt:349](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/AlarmOffloadHandler.kt:349)用 Int 解析并乘 60/3600/86400，[207 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/AlarmOffloadHandler.kt:207)只要求结果为正，然后直接传 EXTRA_LENGTH。平台规定该参数为 1–86400 秒。[AlarmClock API](https://developer.android.com/reference/android/provider/AlarmClock)

CLI 的错误提示把 `2d` 列为可用简写，实际生成超过平台上限的 172800 秒；很大的合法 Int 数值又能乘法溢出，例如 `1193047h` 在 32 位运算下变成 1904 秒，仍为正而被接受，远非原请求。这里不需要运行项目即可由源码运算确定。系统 Clock 如何处理超上限值依赖实现，不能把派发成功当成准确创建。

现有 [AlarmToolPolicy.timerArgv:53](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/alarm/AlarmToolPolicy.kt:53)已经有 24 小时上限，结构化 timer 工具使用它，CLI 却绕过。这是两个入口的约束不一致。修复方向：复用同一时长校验，先用不会溢出的表示解析，再验证平台范围；拒绝无法兑现的请求。固定 openminis 的旧 CLI 有同样的 Int 乘法。

## ND12 — 接受绝对日期后静默转换成下一次 HH:MM

[parseTimeArg:324](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/AlarmOffloadHandler.kt:324)将 ISO 字符串解析为 Date，随后只返回 HOUR_OF_DAY、MINUTE，丢弃年月日。调用 [scheduleViaSystemClock:151](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/AlarmOffloadHandler.kt:151)也只传小时/分钟；结果 [buildSetEnvelope:384](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/AlarmOffloadHandler.kt:384)重新以今天/明天计算时间，而不保留输入日期。

例如用户在 10 月 5 日请求 `set --time 2026-10-20T14:00`，代码不会为 10 月 20 日建闹钟，而会提交下一次本地 14:00。帮助仍宣称接受 ISO，并给出该形式示例。这与按小时/分钟明确建一次性闹钟不同：它已经接受了一个无法兑现的日期要求。平台 SET_ALARM 只有时分与重复星期等参数，没有这段实现能表达指定年月日的参数。[AlarmClock API](https://developer.android.com/reference/android/provider/AlarmClock)

修复方向：准确限制 CLI 输入语义，对不受支持的绝对日期拒绝并指明可用提醒方式；不能静默丢掉日期。固定 openminis 的 parseTimeArg 同样只返回时分，属于继承问题。

## 其他核对与边界

- 旧 AlarmOffloadManager 的 scheduleAlarm/scheduleTimer 当前没有生产调用方；保留的真实入口是旧广播及迁移。没有把它当成现役新建闹钟路径，也没有为旧 WEEKDAYS 的未接续逻辑再计一个新问题。
- ScheduledNotificationReceiver 在 post 之前删除记录，权限变化时可能只留下失败日志；未把这一投递确认问题与 ND04/ND05 重复计数。当前没有声称所有权限拒绝都抛异常。
- 两套工具都确实需要 Android 原生权限与通知接口；本轮发现不能据此推导“所有 Android 工具都应该塞进 guest”。工具职责分层仍按此前独立报告处理。
- BackgroundInterruptionTracker 全文及全库引用检索仅找到定义，确认此前报告的无生产调用候选；不再计入新增数。SessionBadgeStore 等服务文件也完成全文阅读，未据保留枚举或注释直接认定功能故障。
- 旧 N04/N05 的根因族、BG06 的系统授权单等待槽已在已有报告记录，本轮限定新入口或独立后果，未重复统计原发现。

## 阅读与验证范围

本轮新增全文登记 26 个文件、4,416 行：通知监听/历史/工具、闹钟管理器与 CLI/工具/策略、权限页和对话框、session 授权、badge 与后台辅助，以及现有相关测试。NotificationOffloadHandler 和 OffloadPermissionScreen 从片段升级全文；本轮补齐所有行，未依赖未展开的搜索结果。另全文复核旧 AlarmReceiver、ScheduledNotificationReceiver、ScheduledNotificationStore，合计 313 行。MinisApp 仅增加 1128–1214 的迁移片段记录，不计全文。

累计全文 **486 / 1,546 个范围内文件，105,739 行**；17 个片段阅读，1,043 个尚未登记。tracked 共 1,670，排除一般文档、二进制和批量数据 124。清单、SHA 与阅读范围见[阅读台账](/home/jiale/projects/minis-for-android/docs/archive/2026-10-05-code-review-coverage.json)。全库阅读目标仍未完成。

后续端到端验证需保存：旧批次与每条迁移状态、系统 Clock/AlarmManager 实际身份和星期、重启前后待办、延迟投递后取消日志、并发写回后的 OS/注册表对应关系、两组件分别授权时结果、过期数据库行、重置后 UI 与执行行为，以及时长/绝对日期边界。只用虚构通知内容；本轮未执行这些验证，也未编写测试。
