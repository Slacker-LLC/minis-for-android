# 工具轮次并发调度全文审查

2026-10-09，固定主线`3527ad8165f11c0f98d8a30f96098fc9280faa94`。旧203报告和所有台账保持原样，单独新建本文和台账。只静态阅读，无产品代码修改、测试编写/运行、构建、模拟器/设备/Root/云资源或凭据使用。

## 确认的调度行为

ToolConcurrency以canonical名称白名单判断并行安全；file读/列/检索、web查询、部分设备查询在名单，shell、写入、浏览器、UI操作及未知工具默认独占。别名先解析registry canonical；不能把model-facing名字未直接列在集合就判为全部串行。

ToolRound每个调用先在stateLock下prepare，未提供、chat-only、截断写、loop detector及preflight拒绝在slot写合成错误，不执行。parallel调用async启动；exclusive调用等全部先前inFlight结束，再直接execute，后续read直到exclusive结束后才入场。拒绝的exclusive不会额外等待，符合没有执行内容不占独占锁的设计。

slot按原始调用index保存；全部完成后按slots顺序添加resultParts，完成次序不会改变模型tool_result顺序。fold与prepare由同一Mutex保护；onExecuting在真正host.executeTool前运行，取消/失败仍保留已发生执行的标记。host异常向coroutineScope传播并取消同scope其它子协程，未以supervisor吞错。

## 边界与候选

这里的独占仅覆盖同一ToolRound，不是整个App/不同session的全局锁；后台shell作业也明确独立。不能由此推导其它session不能同时写shared目录。并行名单依赖实际handler行为；“只读”不意味着完全没有缓存、UI进度或权限提示副作用，需继续审查各handler共享状态。

execute把共享allToolBlocks交给host，并且运行在stateLock外。fold自己的同步不能证明host内部流式更新也同步；本轮只有VM宿主转发rg定位，没有把大型tool执行实现算全文，也没有凭接口直接登记数据竞争。loop check发生在调用启动前，前几个并行调用可能还没有record结果；是否会突破既有重复调用策略需结合ToolLoopDetector窗口与真实batch，保留候选。

running计数在execute开始增加；prepare先更新SessionActivityTracker为RUNNING。独占调用prepare可能在等待先前read结束前已更新全局状态，先前最后一个fold又能clearToolRunning，而独占execute开始没有重新updateToolStatus。存在状态时序候选，需要核对overlay实际显示规则和宿主进度更新，不宣布工具真执行被丢失。

截断写拒绝仍仅name=file_write/file_edit，canonical实际是否在其它层归一化需要完整入口链，沿用旧ToolRound报告候选，不新报漏洞。参数修复/preflight日志仍可能记录rawTail/lastChunk原文，不能把execute/fold里的ToolSensitivePolicy脱敏扩大为所有日志已脱敏；敏感日志家族已有记录，不重复编号。

## 已有测试与实际限制

ToolRoundParallelTest139行六例调用真实ToolRound及SlowHost：读重叠、read-write-read屏障、shell/未知串行、拒绝保留顺序、onExecuting计数、一个并行调用抛错使其它delay取消。它检验调度算法和结构化取消，FakeHost只delay并返回结果，不测试实际文件操作、共享UI块流式更新、真实Provider批次、Android权限提示或阻塞系统I/O停止。

异常用例只断言慢调用没有end日志；不能将其外推为Root/网络进程都已实际停止。未运行测试，未声称绿灯或端到端通过。固定上游对象此前用于参考，本文没有读取新上游调度源码，也不拿注释中的Codex描述作为独立架构证明。

| 全文文件 | 行数 |
|---|---:|
| src/android/app/src/main/java/com/openminis/app/tools/runtime/ToolConcurrency.kt | 31 |
| src/android/app/src/main/java/com/openminis/app/ui/chat/ToolRound.kt | 515 |
| src/android/app/src/test/java/com/openminis/app/ui/chat/ToolRoundParallelTest.kt | 139 |

ToolRound1—260、261—515完整分段阅读，其余两文件完整输出无截断。新增全文3文件、685行；重读0文件。未把rg定位宿主算全文。

累计全文1307 / 1758文件，195797行；partial 7，未登记444。全库通读未完成。[新台账](/home/jiale/projects/minis-for-android/docs/archive/2026-10-09-code-review-coverage-3527ad81-tool-parallel.json)。

交付校验旧203报告/全部旧台账hash、HEAD/origin/main、全部快照Git blob/SHA-256/字节/行数、tracked/staged空diff，仅新增报告和台账。
