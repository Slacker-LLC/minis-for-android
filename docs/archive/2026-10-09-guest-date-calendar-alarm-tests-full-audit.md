# Guest日期、日历与闹钟：已有测试全文审查

目标主线 `3527ad8165f11c0f98d8a30f96098fc9280faa94`。只读四个已有测试，未运行测试、访问日历/照片/闹钟或设备；只新增报告及台账。没有新增已确认缺陷。

## 全天事件范围

CalendarAllDayTest 使用真实 allDayStoredRange，验证UTC、上海、洛杉矶和两个极端偏移时区的单日UTC存储、包含结束日期的排他终点、非午夜输入保持当地日、倒置结束时间至少保留起始日。倒置时间是被归一化而非拒绝，不把该明确行为当无意错误。

所有输入集中2026年10月6日前后，没有跨夏令时切换、跨年或闰日边界，也没有真实CalendarProvider写入再读取。可以说明算法预期，不能宣布所有OEM全天事件显示已经验证。

## 目标与严格日期

CalendarWriteTargetTest 验证精确名称优先、唯一部分匹配、拼错/多匹配/重复名称/无效ID/空名称拒绝；没有标志返回NoneGiven让调用方选默认。writable集合是人工构造，不能证明真实权限、账户和可写日历查询正确；也没有同时传ID与名称时的冲突规则用例。

日期用例接受日期、无偏移日期时间及显式偏移，拒绝错误月份、无效日期、尾随文本。PhotosStrictDateTest 同样覆盖严格解析并额外拒绝25时和自然语言。显式偏移多数只断言非null，并不验证对应epoch；parse返回null也不能单独证明工具入口必然拒绝而不是忽略过滤器。

## 闹钟参数

AlarmArgParsingTest 验证秒/单位、无效单位、整数乘法溢出不会落入有效范围、两天解析值超过实际允许上限、HH:mm边界、近未来ISO接受与远未来/过去ISO拒绝。两天解析成功不等于系统钟工具允许两天定时，需要调用层1—86400限制。

近未来测试取真实当前时间加三小时，并使用默认时区格式化；日期标签提到next day但实际上未固定跨日。没有固定夏令时或下一次同名时刻边界，也没有系统Intent、用户确认或实际响铃证据。未运行测试，不把输入纯函数用例概括为闹钟端到端成功。

## 覆盖与交付

| 全文文件 | 行数 |
|---|---:|
| src/android/app/src/test/java/com/openminis/app/runtime/guest/CalendarAllDayTest.kt | 53 |
| src/android/app/src/test/java/com/openminis/app/runtime/guest/CalendarWriteTargetTest.kt | 85 |
| src/android/app/src/test/java/com/openminis/app/runtime/guest/PhotosStrictDateTest.kt | 27 |
| src/android/app/src/test/java/com/openminis/app/runtime/guest/AlarmArgParsingTest.kt | 72 |

四个现版文件完整读取。新增全文4文件、237行；已全文重读0文件。其它rg定位不计全文。

累计全文1383 / 1758文件，210269行；partial 4，未登记371。全库通读未完成。[新台账](/home/jiale/projects/minis-for-android/docs/archive/2026-10-09-code-review-coverage-3527ad81-guest-date-tests.json)。

校验旧232报告/全部旧台账hash、HEAD/origin/main、全快照Git blob/SHA-256/字节/行数、tracked/staged空diff。只新增报告与台账。
