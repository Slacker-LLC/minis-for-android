# 工具报告、恢复分页与grep：已有测试全文审查

目标主线 `3527ad8165f11c0f98d8a30f96098fc9280faa94`。仅静态通读三个已有测试，未运行测试、Agent或搜索工具；只新增报告和台账，没有新增已确认缺陷。

## Ralph报告

RalphReportParsingTest 使用真实lastJsonObject/parseReport，覆盖字符串内花括号、最后JSON对象获胜、缺对象拒绝、summary2000字符边界和带截短标记、未知状态/空摘要/过大handoff拒绝。PromptResult.completed只接受大小写精确Completed且timedOut=false，对Busy/Cancelled/NeedsAttention等拒绝。

这里区分的是执行回合结束与解析报告有效，不能证明模型声称complete对应任务验收已通过。没有真实子Agent、checkpoint、取消交错或外部证据核查；字符串测试不能替代工具整体收敛性验证。

## 恢复分页

BotRecoveryPagingTest 调真实forEachPage，但fetcher为整数列表，不是Room查询。75项覆盖三页；64项覆盖两个满页加空页。回调不修改行时游标仍推进，针对重复首批死循环有直接函数证据。

延迟测试检查四次非null、排序非下降、0/5拒绝，没有实际等待、递送失败、协程取消或重启计数持久化。“growing pauses”断言允许相等延迟，不保证严格增长。真实created_at/id复合排序、恢复时新增行和失败投递仍需按生产调用链验证，不新立缺陷。

## grep目标

GrepTargetTest 为grepTargetError传入人工JSON：file/dir允许、不存在报错、symlink拒绝。这证明目标分类helper不把缺失装成成功空结果，但未调用WorkspaceFileClient或实际grep。类型与exists的真实来源、链接解析、访问拒绝、命令exitCode和输出截断不在这些用例范围。

测试路径/workspace只是helper参数，不能据此认定生产执行绕过session workspace；应按真实调用链判断。本轮未运行现有测试。

## 覆盖与交付

| 全文文件 | 行数 |
|---|---:|
| src/android/app/src/test/java/com/openminis/app/tools/RalphReportParsingTest.kt | 73 |
| src/android/app/src/test/java/com/openminis/app/tools/BotRecoveryPagingTest.kt | 46 |
| src/android/app/src/test/java/com/openminis/app/tools/GrepTargetTest.kt | 26 |

三个现版文件完整读取。新增全文3文件、145行；已全文重读0文件。其它rg定位不计全文。

累计全文1405 / 1758文件，211952行；partial 4，未登记349。全库通读未完成。[新台账](/home/jiale/projects/minis-for-android/docs/archive/2026-10-09-code-review-coverage-3527ad81-tool-report-tests.json)。

校验旧240报告/全部旧台账hash、HEAD/origin/main、全快照Git blob/SHA-256/字节/行数、tracked/staged空diff。只新增报告与台账。
