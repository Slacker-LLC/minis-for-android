# CSV、最新请求与浮层位置：已有测试全文审查

目标主线 `3527ad8165f11c0f98d8a30f96098fc9280faa94`。只读三个已有测试，未执行解析、UI或测试；只新增报告及台账，没有新增已确认缺陷。

## CSV解析

CsvParseTest 调用真实parseCsv和StringReader，覆盖带换行引号字段、引号内分隔符及双引号转义、CRLF及无末尾换行、TSV、按记录而非物理行计上限、恰好上限不截断、空输入和空行。未闭合引号明确接受剩余文本为一个字段，这是测试确定的宽松策略，不擅自改成拒绝需求。

这些用例不覆盖真实文件读取失败、编码/BOM、超大单字段、异常max参数或UI表格呈现。行数上限不能单独证明每个字段的内存预算。生产解析和输入大小边界需要结合实际加载链，不凭缺用例新立性能Bug。

## 最新请求

LatestRequestTest 用顺序next/isCurrent检查旧票据失效；“较慢加载后完成”的标题实际上没有异步加载、协程、发布状态或取消。它证明票据纯函数预期，不证明各调用方都在所有UI状态写入前检查票据，也不证明底层线程安全。真实错误、loading清理和finally状态写入仍需按调用链核对。

## 浮层位置

OverlayPositionTest 调用真实clampOverlayPosition，覆盖屏内保留、横竖屏尺寸缩小后夹紧、窗口大于屏幕回原点、负坐标归零。测试中的宽高为人工值，未覆盖状态栏/导航栏/刘海insets、键盘、显示缩放、多显示器或旋转时WindowManager真实更新。不能从位置函数断言推出所有设备上的浮层可见性已验证。

本轮只登记三个文件的完整阅读，不将这些已有测试标记为执行通过。

## 覆盖与交付

| 全文文件 | 行数 |
|---|---:|
| src/android/app/src/test/java/com/openminis/app/ui/sandbox/CsvParseTest.kt | 71 |
| src/android/app/src/test/java/com/openminis/app/ui/sandbox/LatestRequestTest.kt | 32 |
| src/android/app/src/test/java/com/openminis/app/service/OverlayPositionTest.kt | 32 |

三个现版文件完整读取。新增全文3文件、135行；已全文重读0文件。其它rg定位不计全文。

累计全文1398 / 1758文件，211416行；partial 4，未登记356。全库通读未完成。[新台账](/home/jiale/projects/minis-for-android/docs/archive/2026-10-09-code-review-coverage-3527ad81-csv-overlay-tests.json)。

校验旧237报告/全部旧台账hash、HEAD/origin/main、全快照Git blob/SHA-256/字节/行数、tracked/staged空diff。只新增报告与台账。
