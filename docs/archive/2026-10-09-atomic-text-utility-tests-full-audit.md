# 原子文本与工具函数：现版全文审查

目标主线 `3527ad8165f11c0f98d8a30f96098fc9280faa94`。仅静态阅读，未运行shell引用测试、写目标业务文件或计算测试摘要；只新增报告及台账。没有新增已确认缺陷，不重复审计helper重复问题。

## 原子文本写入范围

AtomicText 先写固定同目录filename.tmp，再以REPLACE_EXISTING/ATOMIC_MOVE提交；异常删除tmp并返回false。调用定位只有BackupHistory.persist，局部核对它是实例Synchronized，保存成功才替换cache。因此单实例正常调用受到锁保护，不能直接认定并发写同tmp已在生产发生。

不同BackupHistory实例同文件并不共享这个实例锁，固定tmp路径的竞争需结合实际实例创建与调用者核实；保留候选，不新立数据丢失编号。函数没有显式同步文件/目录到持久存储，不能将原子rename理解为断电后持久性已验证；本轮不构造强杀或断电场景。没有目标父目录时失败符合已读BackupHistory测试的预期。

## shell引用

ShellQuote 单引号包裹并处理内部单引号，已读实现符合一整个POSIX word的构造。ShellQuoteTest 设计为宿主/bin/sh实际printf往返，包含空串、空白、引号、命令替换、分隔符、变量、换行、中文以及marker未创建；不支持/bin/sh时跳过。本轮只阅读，没有启动该shell。

测试先readBytes再waitFor(10秒)，后者不是对前面阻塞读取的完整超时；这属于测试失败诊断边界，不新增产品命令超时缺陷。引用本身不能替代命令选项校验、参数语义或session执行边界，也不判断全部调用方已经统一引用。

## 摘要测试

Sha256Test 验证abc及空串已知摘要、UTF-8一致性、200KiB流跨块与文件内容变更。真实文件测试在finally删除临时文件，但没有短读异常/并发修改/特殊文件用例。它不证明业务签名或内容版本一致性，且本轮未运行。Sha256实现不在本轮新增全文列表，不以测试阅读冒充实现阅读。

## 覆盖与交付

| 全文文件 | 行数 |
|---|---:|
| src/android/app/src/main/java/com/openminis/app/util/AtomicText.kt | 21 |
| src/android/app/src/main/java/com/openminis/app/util/ShellQuote.kt | 12 |
| src/android/app/src/test/java/com/openminis/app/util/ShellQuoteTest.kt | 56 |
| src/android/app/src/test/java/com/openminis/app/util/Sha256Test.kt | 41 |

四个现版文件完整读取。新增全文4文件、130行；已全文重读0文件。其它rg定位不计全文。

累计全文1412 / 1758文件，212179行；partial 4，未登记342。全库通读未完成。[新台账](/home/jiale/projects/minis-for-android/docs/archive/2026-10-09-code-review-coverage-3527ad81-atomic-utils.json)。

校验旧242报告/全部旧台账hash、HEAD/origin/main、全快照Git blob/SHA-256/字节/行数、tracked/staged空diff。只新增报告与台账。
