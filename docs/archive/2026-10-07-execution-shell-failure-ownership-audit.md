# 执行协调、持久shell异常清理与Agent入口审查

日期：2026-10-07。固定远端HEAD `37158d188a190416f2b16b45dfb112ecf53cdd7b`。本轮新增全文3文件、977行；新增ECR01一项。仅静态审查，没有改代码、创建/执行测试、构建、模拟器、adb、Root、设备、真实guest命令或云任务。此前155份报告与所有旧台账不覆盖，留给Claude处理。

## ECR01 · P2 · 旧请求的异常清理可能停止同会话的新shell

[ExecutionCoordinator.kt:97](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/runtime/ExecutionCoordinator.kt#L97) execute在session.mutex.withLock中检查代次、准备runtime/环境并执行。[ExecutionCoordinator.kt:136](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/runtime/ExecutionCoordinator.kt#L136) Exception catch在withLock外，锁已经释放；第137行无条件shells.remove(sessionId)?.stop()，没有记录本次拥有的shell实例，也没有重取同一个session锁。lastInjectedKeys也按会话ID无条件删除。

明确条件性交错：同会话A是Stop前排队请求，捕获旧generation；Stop递增代次并清旧shell；B是Stop后新请求，捕获新generation。A取得mutex，checkGeneration因旧代次抛Exception；withLock释放mutex。A执行catch前线程被调度挂起，B取得mutex，创建/发布新的shell S2并开始自己的命令；A恢复，在锁外按同一sessionId删除并stop S2。B会遭遇shell stopped/中断，尽管它属于有效的Stop后请求。相同问题也可发生在旧请求其它Exception清理与新请求重新启动之间。

这个时序不需要假设shell权限突破或Root命令恶意；根因是清理的资源身份从“失败请求拥有的实例”变成“当时这个ID对应的任意实例”。普通CancellationException分支仅rethrow，不经过此catch，不能泛称所有取消都会触发。真正用户Stop本来允许停止旧请求；这里额外影响的是Stop后的新请求。静态可达但未实机复现，不捏造调度概率。

现役[ToolRegistry.kt:324](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/tools/runtime/ToolRegistry.kt#L324) linux.shell以及第402行另一执行入口、DebugRPCHandler第1218行均调用该coordinator；coordinator本身用每会话mutex接受排队请求，并专门注释支持Stop后重建shell。不能以所有上层调用永远串行作为未验证假设排除问题。

方向记录：异常清理必须和本次拥有资源绑定，或保持同会话生命周期协调边界；[ExecutionCoordinator.kt:203](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/runtime/ExecutionCoordinator.kt#L203) 启动失败已经用shells.remove(sessionId,shell)按实例匹配，是可复用的现有模式。但A在取得shell之前检查代次失败的情况也需处理，不能简单凭空引用某个新shell。只记录，不实施、不运行竞争测试。

与旧TR09不同：TR09是TerminalSession registry对同对象新run注销，本项是ExecutionCoordinator的不同shell实例，在异常分支锁外清理。原报告检索没有同一结论，单独编号。

## 已有停止与输出保护：不把已实现的边界报成缺失

完整阅读coordinator319行：每session状态、activeExecutions、generation、terminated和全局stopGeneration；排队前捕获代次，启动前/后均检查；启动实例先发布使Stop可触达，startup Throwable清理；session终止待active归零后移除状态。不能声称Stop完全没有失效代次，也不能把所有session状态都永久泄漏。ECR01正是这些保护之外的锁外catch缺口。

RootPersistentShell463行完整阅读：commandLock串行命令和环境广播；closed禁止显式Stop后的同实例重启；ProcessBuilder结果先发布再检查closed，取消/启动失败清进程组；timeout或Cancellation停止shell。Root/chroot实际UID/capability由prepareLaunch链处理，本轮未全文复核launcher，未凭这里的注释宣称设备降权已验收。

输出Pending用100000字符head/tail，BoundedLineReader每行100000字符、8192读取块，reader持续排空；异常退出保留8192字符诊断。RootPersistentShellOutputTest已有full记录，本轮完整复读60行，检查head/tail、长行继续排空、未终止末行、signal映射，不验证进程组kill或并发Stop。信号由128+N推断，是Unix惯例，不能把程序主动exit137当成已证实遭SIGKILL。

reader回调接收原始line，最终模型结果由coordinator.sanitize→redact→truncate，不能以最终结果脱敏证明所有callback消费也已脱敏；实际callback接线需后续闭环，本轮不另计。旧TSF01终端OSC贪婪过滤保持原报告，不重复新问题。

## 广播与AgentRunner的范围

旧TR01的coordinator广播入口已改变：[ExecutionCoordinator.kt:290](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/runtime/ExecutionCoordinator.kt#L290) 明确不更新interactive terminal，时区广播只对persistent shell调用applyEnvironment。RootPersistentShell.commandLock防止广播export写入正在执行命令的stdin，因此不能继续按旧coordinator→PTY输入链断言原入口存在。MinisApp第1094/1108行仍触发广播，但当前目标已不是交互终端。没有重新审查所有TerminalSession外部调用，修复结论限定这个旧生产入口。

broadcastProxyChange过滤env为proxy keys，却将lastInjectedKeys的全部旧键交给applyEnvironment；后者会unset不在新env的键。普通execute下一轮会恢复完整用户env，但广播与execute中applyEnvironment/executeCommand之间不是同一个原子段，非代理变量是否在主命令前被清走以及lastInjectedKeys覆盖需后续可达时序核验，暂作候选，不另报所有环境变量永久丢失。

AgentRunner195行完整阅读：仍是HeadlessChatRunner→ChatViewModel适配入口，prompt/retry/compact/cancel/events等透传，没有独立engine。PromptResult.completed要求Completed且非timeout；streamExited是独立字段，不能以completed推断所有cleanup结束。waitForSettle等待isStreaming降false，不等于awaitStreamExit；调用者若把二者当同一保证需逐一检查。本轮没有再次将旧“loop还在ViewModel”架构债新编号。

## 固定上游对照

固定上游HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，GIT_NO_LAZY_FETCH=1，只读已有对象，无fetch。对应文件在sandbox/ExecutionCoordinator.kt，含FreshProcessShell和PRootKernel、TerminalSession.broadcastProxy调用。其执行架构不等于当前Direct Ubuntu coordinator，不能直接替换来解决ECR01，也不凭同名类认定同一异常清理问题。广播旧链有上游对应，当前移除PTY广播以本仓代码为准。

## 全文覆盖登记

| 本轮新增全文文件 | 行数 |
|---|---:|
| [AgentRunner.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/agent/AgentRunner.kt#L1) | 195 |
| [ExecutionCoordinator.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/runtime/ExecutionCoordinator.kt#L1) | 319 |
| [RootPersistentShell.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/runtime/ubuntu/RootPersistentShell.kt#L1) | 463 |

coordinator/AgentRunner完整输出；RootPersistentShell分1—240、241—463完整阅读，无截断。其它调用者仅看片段，不升级全文。

新台账：[2026-10-07-code-review-coverage-37158d18-execution-ownership.json](/home/jiale/projects/minis-for-android/docs/archive/2026-10-07-code-review-coverage-37158d18-execution-ownership.json)。累计全文 **1226 / 1652文件、199226行**；partial4、not_registered422、excluded124，tracked1776。全库通读未完成。

交付核验旧155份报告与全部旧台账hash、固定本地/远端/上游HEAD、tracked/staged空diff、1776快照Git blob/hash/bytes/lines及证据行号。只新建本报告与本轮新台账。
