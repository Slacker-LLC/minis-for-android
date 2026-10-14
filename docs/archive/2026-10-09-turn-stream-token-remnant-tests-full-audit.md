# 流式回合、token统计与退役描述测试全文审查

2026-10-09，固定主线3527ad8165f11c0f98d8a30f96098fc9280faa94。三文件完整输出，无截断。旧221报告和全部台账保留。只静态审查，未改产品代码、编写/运行测试、构建、模拟器、设备、Root、网络模型或云任务。本轮新增确认缺陷0。

## 流式回合测试真实范围

TurnStreamTest构造真实TurnStream、PendingAssistantTurn和ChatSessionEventEmitter，FakeHost同步onMain并记录更新/checkpoint；FakeProvider只提供monolithic属性，stream/send方法不实际调用。直接feed chunk覆盖text累积、tool streaming→pending及title、重复ID重命名、thinking结束、空opaque reasoning优先、usage context回退、finish reason/provider item保留、retry重置、monolithic/ordered文本分块、hosted tool更新与partial JSON title。

这能核对chunk reducer，不验证真实Dispatcher.Main/UI调度、Provider流生命周期或Room持久化。resetForRetry用例随后在测试里主动clear blocks，再feed；不能仅由它推断reset本身清掉所有外部block列表。生产调用者是否在对应时点清外部状态需沿loop核对，保持验证边界，不新增重复重试问题。

FakeHost.saveMedia直接error，样本没有media，所以测试不覆盖媒体落盘和清理。hosted tool只有info行，不证明本地工具执行权限。finish length被保留的断言也不等于完整AgentTurnOutcome状态机，后者见真实VM测试审查。

## Token统计断言

SessionTokenStatsCalcTest真实aggregate合并rows，最后正context胜出，坏JSON跳过，loopCount取tool-ish block和assistant消息数较大者。测试明确thinking也计tool-ish、text/info排除；它验证当前统计口径，不意味着这个值等于真正Provider请求轮数。未把统计显示直接当实际token费用或请求数证据。

## 退役描述测试不能证明执行链

SystemPromptRemnantGuardTest扫描assets/prompts的指定字符串、查静态prompt委托源码片段、AndroidAgentTools/DebugMethodRegistry描述以及MirrorCategory/catalog。它真实检查若干现役注册表，但描述中的PRoot/minisd缺失不证明所有二进制、native launcher、运行时fallback或动态prompt片段都不存在。

prompt检查有正向Ubuntu/apt及负向旧包管理器/品牌，部分匹配大小写敏感且只限制明确词组；用户override及运行时拼合不在这个assets集合。mirror的legacy alpine key映射Ubuntu是显式兼容规则，不能因这个字符串存在就称恢复Alpine运行双栈。此前实际Root/runtime审查结论仍依据执行源码，而不由本测试替代。

源码扫描只require包含AgentSystemPrompt.base并不调用composer，不能据此验收运行时最终prompt；没有执行这个测试或生成通过产物。三文件本轮登记现版全文，生产实现与消费者继续按剩余台账通读。

## 覆盖与交付

| 全文文件 | 行数 |
|---|---:|
| src/android/app/src/test/java/com/openminis/app/ui/chat/TurnStreamTest.kt | 217 |
| src/android/app/src/test/java/com/openminis/app/ui/chat/SessionTokenStatsCalcTest.kt | 48 |
| src/android/app/src/test/java/com/openminis/app/ui/chat/SystemPromptRemnantGuardTest.kt | 124 |

三个现版文件完整读取。新增全文3文件、389行；已全文重读0文件。其它rg定位不计全文。

累计全文1353 / 1758文件，207870行；partial 4，未登记401。全库通读未完成。[新台账](/home/jiale/projects/minis-for-android/docs/archive/2026-10-09-code-review-coverage-3527ad81-turn-tests.json)。

校验旧221报告/全部旧台账hash、HEAD/origin/main、全快照Git blob/SHA-256/字节/行数、tracked/staged空diff。只新增报告与台账。
