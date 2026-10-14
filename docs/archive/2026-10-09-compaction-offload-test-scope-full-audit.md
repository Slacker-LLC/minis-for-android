# 压缩与上下文卸载：已有测试范围静态审查

审查目标为远端主线 `3527ad8165f11c0f98d8a30f96098fc9280faa94`。本轮只读源码及已有测试，未执行测试、构建、模拟器或设备操作；仅新增报告和覆盖台账。没有新增已确认产品缺陷，不将下列测试范围局限直接判成 Bug。

## 压缩规划

`CompactionPlannerTest.kt` 覆盖无持久化消息时拒绝、旧标记锚点移动、锚点回退、压缩区间和 UI 灰显边界，包括合并气泡内的 sourceDbIds。合成历史只有普通用户与助手消息，区间数量断言不能证明真实工具调用批次、并发 Room 写入或所有最新用户轮边界。

特别需要保留其真实语义：`verifiedAnchorIndex` 在已知 ID 集合为空时信任内存索引。该测试没有区分“数据库查询失败”与“数据库实际为空”的恢复可靠性。

## 压缩执行

`CompactionExecutionTest.kt` 使用真实执行入口、假的 Provider 与内存数据库，覆盖摘要写入一次、持久化锚点回退、无法锚定时停止、摘要无缩减时拒绝、截断摘要拒绝。

数据库读取抛错时，测试明确期待信任内存锚点；标记写入抛出 disk full 时，测试明确期待 `Result.Done`。所以这些通过断言只代表内存压缩流程继续，不能作为标记已经持久化、重启后可恢复的证据。本轮没有新立缺陷编号，也没有声称重启数据丢失已经复现。

## 上下文卸载

`ContextOffloaderFailureTest.kt` 覆盖空路径返回时保留原始大文本／工具输入／图片，文本保存但图片保存失败时保留图片，以及工具 ID 后缀相同仍生成不同名称。

FakeStore 的失败通过返回空字符串模拟，没有真正写文件。它无法证明真实文件的完整写入、原子性、权限、会话路径隔离和重启可读取；也未直接覆盖 Store 抛异常。这些是后续核对生产实现的范围，并非仅凭缺少测试就认定有缺陷。合成 tool_result 历史也没有对应 tool_use，不能用于证明完整消息批次有效性。

## 覆盖与交付

| 全文文件 | 行数 |
|---|---:|
| src/android/app/src/test/java/com/openminis/app/ui/chat/CompactionPlannerTest.kt | 115 |
| src/android/app/src/test/java/com/openminis/app/ui/chat/CompactionExecutionTest.kt | 132 |
| src/android/app/src/test/java/com/openminis/app/ui/chat/ContextOffloaderFailureTest.kt | 102 |

三个现版文件完整读取。新增全文3文件、349行；已全文重读0文件。其它rg定位不计全文。

累计全文1356 / 1758文件，208219行；partial 4，未登记398。全库通读未完成。[新台账](/home/jiale/projects/minis-for-android/docs/archive/2026-10-09-code-review-coverage-3527ad81-compaction-tests.json)。

校验旧222报告/全部旧台账hash、HEAD/origin/main、全快照Git blob/SHA-256/字节/行数、tracked/staged空diff。只新增报告与台账。
