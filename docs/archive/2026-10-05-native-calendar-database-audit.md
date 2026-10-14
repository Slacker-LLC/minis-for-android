# 原生日历与私有数据库读取审计

日期：2026-10-05。当前项目提交：`d64e7c06d8159be90737dd9ba03dd9bd5855f339`。本轮只做静态审计，独立新建报告，不修改产品代码和旧报告，不运行构建、测试、模拟器、adb、Root、设备或云任务，不读取凭据或真实用户数据库。

新增 **8 项 P2 问题**。全文新增 13 个文件、2,308 行，累计 611/1,546 个纳入范围的文件、126,184 行。全库排查仍未完成。下面的条件和反例来自源码及平台合同；未把静态推导描述成真机复现。

## 问题索引

| 编号 | 问题 | 影响 |
|---|---|---|
| ND01 | 查询日历 Events，未展开重复事件 | 后续 occurrence 不可见，重复会议时段可被报为空闲 |
| ND02 | 全天事件只换时区，未转换起止日期 | 存储不满足 UTC 午夜约束，日期可错位或写入被拒绝 |
| ND03 | 提醒插入/替换失败被吞掉 | 旧提醒可能丢失，结果仍声称提醒已设置 |
| ND04 | 指定日历未匹配时自动换目标 | 新事件写错账号，更新可把原事件移动到默认日历 |
| ND05 | 日期解析宽松，非法 end 又用一小时缺省替代 | 错误时间变成真实日程写入 |
| ND06 | 每次数据库读取清理全部同前缀快照 | 并发读取相互删除正在使用的数据库/sidecar |
| ND07 | sidecar 检查失败被归类为文件不存在 | 不完整快照可能被当作成功结果 |
| ND08 | 健康 schema 不支持或查询失败仍返回成功摘要 | 无法读取被掩盖为空数据或部分数据 |

## 现役调用与旧文件边界

[CalendarOffloadHandler](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/CalendarOffloadHandler.kt)是原生 CLI 注册及结构化日历工具实际使用的实现。[AndroidSystemTools.kt:162](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/AndroidSystemTools.kt:162)、[181 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/AndroidSystemTools.kt:181)、[203 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/AndroidSystemTools.kt:203)分别将读取、创建、更新转交该 handler。

健康和剪贴板历史的现役入口在 [MinisApp.kt:679](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/MinisApp.kt:679)注册 PrivateDatabaseTools；它使用受控参数的 PrivilegedCommandRunner 复制固定路径后，以只读 SQLite 查询。ClockDatabaseTools 也复用这条快照链。以下并未断言 Root 权限绕过，也没有执行复制命令。

本轮完整读取 CalendarManager.kt、HealthManager.kt 并搜索 main 源码调用，未发现两者的生产引用。旧 HealthManager 的占位返回不算现役健康工具故障，也不据此直接删除文件。旧报告已把它们记作无直接引用的候选，本轮不重复计数。

## ND01 — 重复日程没有展开

日历 list 的 [156 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/CalendarOffloadHandler.kt:156)按 Events.DTSTART 落在窗口内筛选，然后 [162 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/CalendarOffloadHandler.kt:162)查询 Events.CONTENT_URI。freebusy 的 [418 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/CalendarOffloadHandler.kt:418)虽然采用时间重叠筛选，仍查询原始 Events 表，未处理 RRULE/RDATE/DURATION，也没有查询 Instances。

Android [Events 合同](https://developer.android.com/reference/android/provider/CalendarContract.Events)明确重复事件只返回一个事件行；[Instances](https://developer.android.com/reference/android/provider/CalendarContract.Instances)才表示每次 occurrence 的时间。原始事件行不是查询窗口内的已展开日程。

静态反例：一条上个月开始、每周重复的会议，DTSTART 是首次会议时间，RRULE 持续覆盖本周。list 查本周不会选中它；freebusy 也不能从原始起止字段推导本周 occurrence，可能给出可用时段。本例不要求本 App 支持创建重复事件：系统或其它日历应用已创建的事件就会触发。

方向：按窗口查询平台已展开的 occurrence，再执行筛选、排序和空闲计算；保留事件 ID 与 occurrence 时间的区别。不要将 freebusy 当前可合并普通重叠事件的算法理解为已支持 recurrence。全天事件被 freebusy 显式跳过是当前策略，本轮不另计这一策略问题。

## ND02 — 全天事件没有满足存储时间语义

[create:245](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/CalendarOffloadHandler.kt:245)使用通用 parseDate。无 offset 的日期按设备本地时区解析；DTSTART/DTEND 使用原始毫秒值。[262 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/CalendarOffloadHandler.kt:262)置 ALL_DAY=1，随后仅把 EVENT_TIMEZONE 改为 UTC，并未将本地日期转换为 UTC 午夜，也未给全天缺省 end 建立完整日期边界。

Android [Events 写入约束](https://developer.android.com/reference/android/provider/CalendarContract.Events)要求全天事件使用 UTC 和午夜边界。只改时区字符串不会改变时间戳。示例：Asia/Shanghai 下 --all-day --start=2026-10-06，start 为 10 月 5 日 16:00Z；没有 end 时又加一小时，end 为 17:00Z。两者都不是 UTC 午夜，不能表达用户指定的一整天。

具体 Provider 可能拒绝、归一化或产生日期错位，本轮不声称所有 OEM 行为一致。结构化 [android.calendar.create:422](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/AndroidSystemTools.kt:422)同样转交 --all-day，所以这不是只有手工 CLI 才能到达的错误。

上游已有 putAllDay/allDayStoredRange：以本地日期意图计算日期边界，再存为 UTC 午夜和排他 end。当前没有这层转换。方向是复用平台语义和现有日期数据流；不能只把 EVENT_TIMEZONE 改成 UTC。

## ND03 — 提醒失败仍被报告为已设置

[insertReminder:479](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/CalendarOffloadHandler.kt:479)返回 Unit，忽略 insert 返回的 null，异常也只记日志。[replaceReminder:493](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/CalendarOffloadHandler.kt:493)先删除原提醒，再独立插入，删除或插入没有共享事务或恢复状态。

创建后的 [285 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/CalendarOffloadHandler.kt:285)直接把请求的 alarm_minutes_before 写入成功结果；更新 [347 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/CalendarOffloadHandler.kt:347)只要 alarm 参数存在就标 updated=true。它们没有证据证明提醒行已提交。

反例：已有 15 分钟提醒，更新为 30 分钟；删除旧行成功，插入返回 null/抛错。事件可能仍存在，但提醒已经消失，调用却退出 0、updated=true、alarm_minutes_before=30。若删除失败而新插入成功，还可能保留多条提醒。负数 alarm 在 CLI 更新中不执行写入，却仍因参数非 null 标为 updated，属于同一状态反馈缺口。

方向：按真实提交结果构造反馈，区分事件成功与提醒失败；替换提醒尽量使用 Provider 可支持的原子批处理或明确恢复/部分失败。无需因提醒失败盲目重建已经成功创建的事件。

## ND04 — 显式日历目标静默退回默认值

[resolveCalendarId:561](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/CalendarOffloadHandler.kt:561)对 --calendar 做 contains 匹配，取第一项；没有匹配则记录日志并自动 pickWritableCalendar。--calendar-id 只解析数字，没有验证它属于可写目录。update 的 [328 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/CalendarOffloadHandler.kt:328)将返回 ID 写入 CALENDAR_ID。

有默认个人日历和工作日历，用户传 --calendar=Work-Typo。create 会落到自动选出的个人账号；update 则可把原事件从工作日历移动到个人日历，即使真正目的是改标题并保持指定账号。相近名称 Work/Workout 或同名多账号也可能先匹配到错误项。日志不是面向调用方的明确目标变更反馈。

本项限于 CLI 的显式写目标；结构化 create 目前不提供 calendar_name 参数，不把它误写成结构化 schema 已有此选项。list 的子串筛选本身可以合理存在，写入目标应采用不同语义。

固定上游已实现 resolveTarget/CalPick，未找到或歧义时返回候选和退出 2、不写入；只在完全没提供目标时自动选择。方向是保留“未指定”与“指定但无效”的区别，不能把自动兼容视为授权换账号。

## ND05 — 错误日期变成有效写入

[parseDate:713](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/CalendarOffloadHandler.kt:713)没有设置 isLenient=false，也不检查字符串被完整消费。[DateFormat 的主文档](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/text/DateFormat.html)说明默认解析宽松，parse(String) 可以仅使用字符串前缀。于是非法月份/日期可滚到其它日期，尾随垃圾也可能被忽略。

另一个确定分支在 [create:247](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/CalendarOffloadHandler.kt:247)：显式 --end=not-a-date 解析为 null，与缺省 end 合并，再替换为 start+1h。用户要求的结束时间无法解析，却创建了一小时事件。update 对解析失败已有显式错误，create 没有复用相同校验。

静态反例：--start=2026-13-01 被宽松日期格式解释为下一年日期；或合法 start 与 --end=not-a-date 配对，进入成功写入。未运行 Java/Android 解析测试，不能把具体异常输入的全部归一化细节说成已实测，但默认宽松设置和 end 的空值分支足以证明缺口。

上游使用 isLenient=false 和 ParsePosition 全长度检查，还明确拒绝显式非法 end。方向：在任何写入前得到严格完整的日期结果，缺省值只用于真正未提供的参数。剩余 end<=start 与相对 offset 溢出也需后续验收，不在本轮另拆编号。

## ND06 — 清理函数删除正在使用的快照

[PrivateDatabaseSnapshot.create:72](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/PrivateDatabaseSnapshot.kt:72)每次开始先调用 cleanupStaleSnapshots。[155 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/PrivateDatabaseSnapshot.kt:155)对 cacheDir 下所有 minis-private-db- 前缀文件直接删除，没有年龄、活跃集合、调用 ID 或生命周期锁。文件名虽然随机，但全部属于同一个清理前缀。

可达交错：会话 A 创建 temp 文件并复制数据库；在 A 打开 SQLite 之前，会话 B 开始任何同类读取并删除 A 的 main/sidecar。A 随后可能打开失败并返回 unavailable；如果 main 已打开、WAL 尚需访问，也可能丢失其配套文件。A 在 create/read 中存在多个 suspend privileged 调用，没有整体互斥。[ToolExecutor:167](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/runtime/ToolRegistry.kt:167)直接派发 handler，未给整条快照生命周期加全局锁。

两个会话交错的条件在架构上允许，未制造设备并发复现。本项不声称每次删除都让已打开句柄立即失效；明确反例采用复制完成、openDatabase 尚未开始的窗口。

方向：每个调用清理自己的工件，清理残留时识别活跃快照并使用真实陈旧条件；必要同步应覆盖完整生命周期，不能只锁住某一条 cp。

## ND07 — 读取 sidecar 失败被当作正常缺失

[copyIfCopyable:106](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/PrivateDatabaseSnapshot.kt:106)用 fileSize==null 表示源文件不存在。[fileSize:126](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/PrivateDatabaseSnapshot.kt:126)却会在任何 privileged 结果不成功时返回 null，也在输出无法解析时返回 null，没有区分 ENOENT、超时、拒绝访问或命令失败。

main 文件复制检查要求 true，所以这里失败会终止；但 sidecar 的 [82 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/PrivateDatabaseSnapshot.kt:82)将 false 视作正常缺失并继续。反例是主库可复制、实际存在 -wal 且含最近提交、检查 -wal 的 stat 失败。create 仍返回快照，查询有可能只读到已 checkpoint 的旧数据，或失败成不可用；失败关闭没有保障。

SQLite 的 [WAL 合同](https://www.sqlite.org/wal.html#the_wal_file)明确 WAL 属于持久数据库状态，复制/移动时须与主库保留；丢开 WAL 可以丢失已提交事务或破坏数据库。只检查文件名是否存在不足以把所有检测失败判作“没有 WAL”。

本项以 sidecar 检测结果分类为已确认根因，不声称本轮看到真实 WAL，也不把 -shm 缺失一概认定为损坏。方向：确定缺失才允许跳过，未知/拒绝/超时/坏输出均使本次快照失败；动态主库与 WAL 分步复制的一致性另需明确方案，不能以复制每个文件成功证明同一事务快照。

## ND08 — 不支持的健康 schema 被返回为成功

[PrivateDatabaseTools.healthSummary:98](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/PrivateDatabaseTools.kt:98)尝试各个聚合，结果为 null 就不写对应字段。最后 [158 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/PrivateDatabaseTools.kt:158)无条件 ok=true，没有判断至少一个 schema 被支持或查询是否成功。

[DatabaseRows.aggregatePair:84](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/DatabaseRows.kt:84)对表不存在和任何 query 异常统一返回 null；latestMeasurement 也将缺列、无行和查询失败合并。心率路径有更具体的 hasColumns，但也无法标识哪些类型根本不支持。

静态反例：快照是能正常打开的 SQLite 数据库，当前版本使用不同表/列名，没有本工具认识的表。工具返回 ok=true、window_days=N、summary={}，PrivateDatabaseSnapshot [59 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/PrivateDatabaseSnapshot.kt:59)据此标 success=true。部分聚合缺列/错误也会形成没有错误说明的部分摘要。调用方不能区分“没记录”和“实际没读懂数据”。

这不是旧 HealthManager 的 stub：现役工具已注册，并有真实查询实现。其开头注释承诺未知 schema 返回 code，实际健康分支未做到；剪贴板历史和 ClockDatabaseTools 已有 SCHEMA_UNSUPPORTED 的拒绝形态可复用。

方向：明确数据类型的 supported/empty/error 状态，全不支持时返回可识别错误，部分失败时保留成功摘要并标注缺失原因。不能用成功空对象替代 schema 兼容性证据。

## 固定 openminis 对照

固定提交 `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`。仅从 `/tmp/openminis-review-20261004` 读取已有源码对象，不拉取、不运行上游。CalendarOffloadHandler 位于上游 sandbox/offload，目录名称不等于要求本项目恢复 PRoot 架构。

- 上游 list/freebusy 的已读路径仍查询 Events.CONTENT_URI；ND01 是共同缺口。上游 reminder helpers 同样忽略 null/吞异常，ND03 也未解决。
- 上游 944–965 行定义 putAllDay，存 UTC 午夜和排他 end；创建路径 288–325 行应用该转换。ND02 有具体上游修复。
- 上游 690–746 行用 resolveTarget 区分未请求、找到、无效，并处理缺失和歧义；ND04 的静默写错日历已有上游拒绝路径。
- 上游 276–287 行拒绝非法 end，1053–1075 行严格解析并检查完整消费；ND05 有明确修复未移植。
- 固定上游 main 源目录 tree 查询未返回本项目这组 PrivateDatabaseSnapshot/Tools/Rows/Rules 文件。ND06–ND08 不推断为上游同名故障，只按当前独立实现记录；代码注释中的 Eta 来源不代替对 Eta 实际实现的审计。

## 排除项与未完成验证

AndroidWeatherTools 的坐标范围检查、城市解析失败和当前定位失败均已读取，不把捕获地理编码异常直接升级为新问题。HealthSummaryPolicy 的窗口范围和克/千克换算只按实现阅读，本轮没有依据真实 OEM schema 验证这些字段单位，不能声称跨版本健康兼容通过。

健康 SQL 使用 end_time>=cutoff 并求整段时长，跨窗口记录可计入窗口外部分；需要明确“记录结束于窗口”与“窗口内总时长”的产品语义后再独立定级，本轮不另计。Clock 的 schema 检查与 ORDER BY 字段依赖也留待相关入口进一步核对。源码固定的表名、路径与参数化 LIKE 不构成任意 SQL/路径输入，本轮不作这种扩张判断。

没有运行或编写任何测试。已读 PrivateDatabaseRulesTest、HealthSummaryPolicyTest 只验证局部策略函数，不覆盖快照并发、sidecar 故障、真实 Provider 事务和未知 schema 状态。建议后续授权的端到端验收保留：

| 场景 | 可重复工件 |
|---|---|
| 重复会议覆盖查询窗口 | 事件 RRULE、Instances 结果、CLI list/freebusy；不误报为空闲 |
| UTC+8 全天日程、默认 end、已有全天事件更新 | 输入、实际 Provider 行、日历展示日期；存储遵守午夜边界 |
| 提醒 insert/delete 故障 | 旧/新提醒行、事件行、退出码、部分失败信息 |
| 日历拼写错误、同名账号、无效 ID | 可写候选、调用结果、前后事件归属；错误时不写入 |
| 非法日期/尾随垃圾/非法 end | 错误输出与 Provider 写计数；校验失败不创建日程 |
| 同时读取健康、时钟、剪贴板快照 | 生命周期事件、每份文件摘要、返回状态；互不清理活跃工件 |
| 存在 WAL 但检测失败 | main/WAL 摘要、命令故障分类、结果；未知完整性不得报告成功 |
| 未知/部分 schema | 表列清单、摘要与失败状态；空数据与不支持可区分 |

## 全文覆盖登记

新增完整逐行读取以下文件；长文件按连续分段读完，截断片段另行补读：

| 文件（Android 源目录下） | 行数 |
|---|---:|
| main/java/com/openminis/app/offload/CalendarManager.kt | 133 |
| main/java/com/openminis/app/offload/HealthManager.kt | 51 |
| main/java/com/openminis/app/runtime/guest/CalendarOffloadHandler.kt | 752 |
| main/java/com/openminis/app/tools/AndroidSystemTools.kt | 523 |
| main/java/com/openminis/app/tools/AndroidWeatherTools.kt | 93 |
| main/java/com/openminis/app/tools/ClockDatabaseTools.kt | 111 |
| main/java/com/openminis/app/tools/DatabaseRows.kt | 109 |
| main/java/com/openminis/app/tools/HealthSummaryPolicy.kt | 22 |
| main/java/com/openminis/app/tools/PrivateDatabaseRules.kt | 35 |
| main/java/com/openminis/app/tools/PrivateDatabaseSnapshot.kt | 172 |
| main/java/com/openminis/app/tools/PrivateDatabaseTools.kt | 207 |
| test/java/com/openminis/app/tools/HealthSummaryPolicyTest.kt | 35 |
| test/java/com/openminis/app/tools/PrivateDatabaseRulesTest.kt | 65 |
| **合计** | **2,308** |

ToolRegistry、PrivilegedCommandRunner、MinisApp 的片段仅作为生产调用证据，不计新增全文；上游和平台文档同样不计本项目覆盖。

台账：[2026-10-05-code-review-coverage.json](/home/jiale/projects/minis-for-android/docs/archive/2026-10-05-code-review-coverage.json)。tracked 1,670、纳入范围 1,546；全文 611、部分 15、尚无全文登记 920、排除 124；全文累计 126,184 行。哈希变更须重新审计，覆盖记录不代表修复或运行验证通过。
