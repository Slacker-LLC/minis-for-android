# 配置调用上下文、MCP调试接口与截图缓存全文审查

日期：2026-10-07，固定远端`ea620e9ec51a246ce76cdec45a8d43109462f472`。新增全文7文件、630行；已全文RpcParams41行重读不重复计数。旧197份报告及全部旧台账保留，独立新建本文和台账。未改产品代码、编写/执行测试、构建、RPC/模型/工具请求、截取屏幕、模拟器/adb/Root/设备/云资源或凭据读取。

## DSC01 / P3：截图失败回退没有主动回收PixelCopy目标位图

原[聊天RPC报告](/home/jiale/projects/minis-for-android/docs/archive/2026-10-06-chat-rpc-mutations-audit.md)已经记录失败/取消分支位图回收候选，本轮全文核对实际代码和调用范围，首次编号DSC01，不能把原候选另算一份同根因缺陷。

[DebugScreenshotRing.kt:75](https://github.com/Slacker-LLC/minis-for-android/blob/ea620e9ec51a246ce76cdec45a8d43109462f472/src/android/app/src/main/java/com/openminis/app/debug/DebugScreenshotRing.kt#L75)captureBitmap先创建ARGB_8888目标bitmap再请求PixelCopy；[DebugScreenshotRing.kt:82](https://github.com/Slacker-LLC/minis-for-android/blob/ea620e9ec51a246ce76cdec45a8d43109462f472/src/android/app/src/main/java/com/openminis/app/debug/DebugScreenshotRing.kt#L82)失败回调直接canvasCapture并resume新bitmap，没有recycle已创建但不再使用的PixelCopy目标。capture后续只compress/recycle被返回的bitmap，第一张没有经过这条清理链。

PixelCopy返回非SUCCESS时，这个分支明确会分配两张目标尺寸位图，第一张的像素资源释放要等后续GC/运行时回收，没有与捕获结束组成明确资源生命周期。ring最多16项限制的是保存PNG记录数，不限制这些尚未回收的临时像素分配。这里不声称永久泄漏或一定OOM，也不捏造PixelCopy失败实际发生过。

取消时同样没有取消清理注册，成功resume的bitmap若未被下游capture消费，capture里的recycle也不保证到达；compress并非try/finally，压缩异常时也不主动回收。它们是同一bitmap拥有者交接需要处理的分支，不另计多个缺陷。

[DebugRPCHandler.kt:1477](https://github.com/Slacker-LLC/minis-for-android/blob/ea620e9ec51a246ce76cdec45a8d43109462f472/src/android/app/src/main/java/com/openminis/app/debug/DebugRPCHandler.kt#L1477)现役debug.screenshot.capture先require活跃Activity，scale限制0.05—1.0再进入该对象。所以不是未接线类，也不能把其它Browser任意viewport尺度问题复制成此处无尺寸限制。ring只在进程内保存，get有显式id/最后一条，未发现持久数据库缓存被误当截图真源。

记录方向：明确PixelCopy目标、fallback目标和交给调用者位图的拥有者及交接，在失败/取消/压缩异常下主动释放未交接资源；优先现有协程/bitmap清理方式，不引入另一个截图服务。未来授权端到端验收重复受控PixelCopy失败及取消，保留请求结果、资源分配/释放和截图摘要；本轮没有运行或编写测试。

## 配置调用session：同期读写包裹真实，异步范围不扩张

ConfigCallSession35行使用ThreadLocal，同步with保存旧值、try/finally恢复，明确只保证当前线程同步block。targetSession优先调用session并解析persisted id，无session回落activeSession。

ConfigBridge现役read以及审计读调用with(sessionId)，[ConfigBridge.kt:665](https://github.com/Slacker-LLC/minis-for-android/blob/ea620e9ec51a246ce76cdec45a8d43109462f472/src/android/app/src/main/java/com/openminis/app/config/ConfigBridge.kt#L665)写field也同样包裹。当前block内调用ConfigField同步read/write，不是让ThreadLocal跨挂起点漂移；不能只看到ThreadLocal就报所有协程都会串会话。实际持久写若field自己另开异步工作仍须查field具体实现，本文不替代ConfigBuiltins全文。

ConfigCallSessionTest50行5例真实调用helper，验证调用者优先、无session回落、无目标、抛错恢复和嵌套恢复；只改Store的activeSession。它不测试审批等待、真实会话重命名/删除、跨线程执行或ViewModel/Room写入，不称端到端成功。

## ConfigRegistry：带点ID与发布顺序

resolveField先找静态字段及普通三段collection路径，失败再用首/末点分割，允许ID本身含点。ConfigChildPathTest62行4例调用真实splitChildPath/Registry，FakeCollection核对inst/mimo-v2.5、缺字段/ID、非法分隔路径，证明该解析层改善，不能替代所有真实collection/schema写入。

registry初始化完整registerInto后才发布INSTANCE，再启动guest桥；线程先看到已发布实例不等于看到半初始化map。fields/collections为普通可变map且register公开，当前启动后修改是否真实发生要按调用者核对，不据“没有同步”就报生产必崩。soul.body只改变面向agent描述，不能替代实际字段验证器。

ConfigThinkingTokensTest38行4例检验所有ThinkingLevel token往返、reader token都在write schema集合、unknown/case拒绝及JSON引号反斜杠保留。最后一例直接构造JSONObject，不调用生产model绑定写方法；不能把它当任意绑定入口已验证。未运行这些测试。

## MCP补丁CP01已改善，其它返回语义保持既有范围

McpRpcMethods183行对args/env/headers使用RpcParams严格stringList/stringMap；值存在但类型错、数组含非字符串、object值不是字符串均在修改前抛-32602。省略字段继承current，合法空数组/对象可清除。旧CP01“错误类型转空集合覆盖配置”主要条件已修。

create/update检查恰一transport、ID格式及重复/存在，使用同一MCPRepository，没有Web配置第二真源；importUrl通过SafeRemoteImporter带1MiB文本预算。读配置返回env/headers是已授权管理接口语义，不把返回配置本身宣布未授权泄露；实际访问控制需上层DebugServer既有边界。

toggle仍optBoolean(enabled,true)而非严格boolean，present错误类型会取默认值；optionalString仍optString可转换错误类型。它们是现役参数校验观察，不把严格集合修复扩张成所有参数已严格，也不重新登记CP01清空集合反例。

add/update返回Boolean表示内存配置操作是否接受，save原有落盘错误语义仍归MC09；toggle/delete返回ok不提供持久成功证据。并发create检查和Repository.add的覆盖、update读取旧快照后覆盖别的修改是候选，未实际并发RPC或造型异常请求，不额外立项。

更新transport时oauth=current.oauth保留，没有在此清除旧OAuth状态；是否其它Repository/client按endpoint重设需更完整链证据，不能仅凭copy字段断言凭据外发。import返回原配置字段但不含oauth，各响应字段差异也不等同协议隐私缺陷。

## 上游与交付覆盖

本轮不取回新上游对象、不运行上游；此前固定OpenMinis树缺McpRpcMethods同名模块已记录，只作为参考限制，不推导它没有其它管理接口。源码注释中的iOS描述不计本轮缺陷，遵守Claude负责的噪音整理范围。

| 新增全文 | 行数 |
|---|---:|
| [ConfigCallSession.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/ea620e9ec51a246ce76cdec45a8d43109462f472/src/android/app/src/main/java/com/openminis/app/config/ConfigCallSession.kt#L1) | 35 |
| [ConfigRegistry.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/ea620e9ec51a246ce76cdec45a8d43109462f472/src/android/app/src/main/java/com/openminis/app/config/ConfigRegistry.kt#L1) | 153 |
| [DebugScreenshotRing.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/ea620e9ec51a246ce76cdec45a8d43109462f472/src/android/app/src/main/java/com/openminis/app/debug/DebugScreenshotRing.kt#L1) | 109 |
| [McpRpcMethods.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/ea620e9ec51a246ce76cdec45a8d43109462f472/src/android/app/src/main/java/com/openminis/app/debug/McpRpcMethods.kt#L1) | 183 |
| [ConfigCallSessionTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/ea620e9ec51a246ce76cdec45a8d43109462f472/src/android/app/src/test/java/com/openminis/app/config/ConfigCallSessionTest.kt#L1) | 50 |
| [ConfigChildPathTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/ea620e9ec51a246ce76cdec45a8d43109462f472/src/android/app/src/test/java/com/openminis/app/config/ConfigChildPathTest.kt#L1) | 62 |
| [ConfigThinkingTokensTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/ea620e9ec51a246ce76cdec45a8d43109462f472/src/android/app/src/test/java/com/openminis/app/config/ConfigThinkingTokensTest.kt#L1) | 38 |

七文件完整输出无截断，RpcParams重读41行。相关Bridge仅647—674、DebugRPCHandler仅1472—1511登记partial；rg定位不算全文，未假装这两个大文件已通读。

新台账：[2026-10-07-code-review-coverage-ea620e9e-config-debug.json](/home/jiale/projects/minis-for-android/docs/archive/2026-10-07-code-review-coverage-ea620e9e-config-debug.json)。累计全文 **1403 / 1664文件、236914行**；partial12、尚未登记249、excluded124，tracked1788。全库通读尚未完成。

交付校验旧197份报告/全部旧台账hash、本地/远端/上游HEAD、tracked/staged代码diff、1788快照blob/hash/字节/行数及本报告引用行号。只新增本文和新版台账。
