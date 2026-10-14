# Job等待、审批、目标和工具播报静态审查

日期：2026-10-06。本仓固定HEAD `d64e7c06d8159be90737dd9ba03dd9bd5855f339`。新增 **1项P3确认缺陷**，全文读取5文件、570行。只记录，未改产品源码，未运行测试、构建、模拟器、设备、Root、云或模型任务；旧报告不改。

## JW01 / P3：零等待预算关闭内部deadline，不能及时返回当前输出

[JobTools.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/JobTools.kt:99) 将 timeout_ms 约束为非负；当wait=true且timeout_ms>0才设置deadline，否则为0。第105—109行只在deadline>0时检查是否到期，因此模型传wait=true、timeout_ms=0，会持续每100ms轮询RUNNING job，直到任务终止、被淘汰或调用协程取消。

这与schema中的“Maximum time to wait in milliseconds”及超时返回当前输出的语义不符：零毫秒不是无限预算。负数也被coerceAtLeast(0)转换成相同无内部deadline行为。静态反例：现有RUNNING job尚需10秒，调用system.jobs的action=job_output、wait=true、timeout_ms=0；不会立即给出当前输出，而是在任务10秒后终止才返回。无需依赖墙上时钟回拨或整数溢出。

现役 [ToolRegistry.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/runtime/ToolRegistry.kt:531) 将system.jobs参数传入同一execute；该handler schema第523行同样描述最大等待毫秒，外层definition第528行设120秒。正常Agent调用 [ChatViewModel.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:9795) 有协作超时包裹；因此本项不声称正常Agent一定永久挂起。若job持续超过外层时限，调用返回第9803行TOOL_TIMEOUT错误，亦不同于内部等待到期返回当前输出和RUNNING状态。具体运行时agentTools是否保留该definition取决于当前工具暴露，不能把120秒断言为所有RPC入口都固定相同。

方向：明确零预算行为并实现立即返回，或拒绝零/负值；使用已有有界等待机制，避免把一个最大等待参数意外解释成无界。未运行实际job或新增测试。

## 审批与目标入口核验

ApprovalSeam policy仅接受ask/never，never直接拒绝；request使用120秒withTimeoutOrNull等待并在finally删除pending。answer先用条件remove取得一次回答资格再完成deferred，重复回答返回false。调用协程取消会清理pending；cancelForSession则完成false，最终被显示为rejected而非cancelled。后者是结果分类候选，尚未找到生产cancelForSession调用，不直接当作用户取消流程已接线的事实。

现役 [ToolRegistry.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/runtime/ToolRegistry.kt:154) 本地确认调用此seam，非local返回confirm_required；未获allowed-once不继续执行。summary来自参数截取，审计日志仍包含summary，相关敏感参数审查已有原报告，不借本轮重复统计。pendingFor(null)返回全部属于当前实现，远程权限必须由上层入口证明，不能仅凭此方法泛化远程越权。

GoalTools按session保存，缺少session拒绝；空文本清除goal；create已有goal、update无goal分别拒绝。optString对缺少goal得到空文本，clear发生在existence检查之前，属于需要明确schema条件的候选。system.goal允许action而goal非必填，现有空文本清除语义不能仅凭“参数未传”定为确定误删除。F17已记录legacy独立工具名与canonical action路由不一致，本轮只核对调用，不新增同一条故障。

JobTools明确把sessionId留作未使用参数，注册表设计为进程全局；没有凭全局列表自行推断guest workspace被绕过。job_kill仅更新JobRegistry状态，F22已有取消不终止真实watch的记录，未重复计算。

## 工具播报与测试

ToolSpeech有title优先、Han决定中英文、部分JSON失败降级、shell/browser参数截断40字符。fileName仅取最后路径分量，没有继续clip；标题空白时部分长文件名可能超过注释所述40字符限制。当前ChatScreen第5911行确实调用announcement，但真正toolName可能为canonical名称，后者进入generic分支；未在本轮补齐可达旧file_read无标题生产输入，所以此项暂不新增确认缺陷。

ToolSpeechTest全文160行，验证title、参数fallback、语种、未知工具、长command、部分JSON与尾slash。长文件名没有直接断言；测试中的apk/旧路径是字符串fixture，没有执行命令，也不能证明Alpine重新成为生产runtime。所有测试仅静态阅读，未运行。

## 固定OpenMinis对照

只读 `/tmp/openminis-review-20261004` 固定HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，未fetch。Git树有ToolSpeech同路径，85—101行同样在fileName分支不clip；没有本轮ApprovalSeam、JobTools、GoalTools同路径代码。不从Harness移植注释推断上游实现完全等价，不恢复旧minisd/PRoot链路。

## 全文覆盖与复核

| 本轮全文文件 | 行数 |
|---|---:|
| [ToolSpeech.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/ToolSpeech.kt) | 101 |
| [ApprovalSeam.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/ApprovalSeam.kt) | 121 |
| [GoalTools.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/GoalTools.kt) | 46 |
| [JobTools.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/JobTools.kt) | 142 |
| [ToolSpeechTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/speech/ToolSpeechTest.kt) | 160 |

累计全文 **1188 / 1546文件、229472行**；partial13，not_registered345，excluded124。全仓通读未完成。五个文件返回均完整；ToolRegistry、ChatScreen、ChatViewModel、JobRegistry只是已覆盖文件片段复核，不重复登记全文。

完成时验证全部1670 tracked路径SHA-256、字节数与正数文本行数，本仓/上游固定HEAD、tracked/staged空diff、此前104份报告哈希、本报告文件链接和行号、台账批次总计。只新建本报告并更新覆盖台账。
