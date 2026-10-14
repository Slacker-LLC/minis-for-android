# Android能力声明与虚拟屏幕观察边界

日期：2026-10-07。固定远端HEAD `0b96f7aa2a46c313b896a6f66501586e7caba81a`。完整静态阅读2文件、530行，另复核已全文登记的观察registry测试和执行调用片段。旧180份报告及全部覆盖台账原样保留。未修改代码、编写或运行测试、调用能力探测/截图/屏幕动作、构建或操作模拟器/adb/Root/设备/云资源。无新增足够证据支持的独立问题编号。

## AD08能力报告与执行后端仍不一致

[AndroidCapabilityResolver.kt:58](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/tools/android/AndroidCapabilityResolver.kt#L58) privilegedAvailable仍为root authorized或Shizuku READY。debug.logcat/dumpsys/meminfo据此宣称AVAILABLE，logcat还把backend标为shizuku。

当前[PrivilegedCommandRunner.kt:414](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/tools/android/PrivilegedCommandRunner.kt#L414) run仍只校验argv、解析trusted tool path，然后DirectRootRunner.runArgv，返回ROOT或NONE；未新增Shizuku执行分支。故“仅Shizuku READY、Root不可用”时，能力可宣称完整特权诊断，但实际执行走Root失败后可能降到普通App读取。沿用AD08，不重复编号。

enum保留SHIZUKU不等于已有执行实现。能力报告中的privilegedShell.shizuku是在描述服务状态，单独这一字段不是错误；问题是将该状态扩展成当前调试命令可走的后端。

## guest开发能力没有被伪装为已安装工具链

executionFacts使用UbuntuRuntime快照，java/gradle/SDK/NDK/cmake标为需guest probe的PARTIAL或runtime未起时UNAVAILABLE。没有在此读取App私有SDK并声称生产工具链可用。Root能力位仅作为PARTIAL，再声明操作/SELinux探测尚需进行；uid0不等于所有操作获准这一边界明确存在。

本报告没有调用su或任何探测。root快照未探测时给出active_root_probe下一步提示是产品描述，不能当作本轮授权实际执行探测。

packageVisibility的queryAllPackages固定false及queryIntentActivities计数、runtime.running快照真实性均为进一步候选；未对照所有manifest/实际快照更新链，不新增判断错误编号。

## 虚拟屏幕ref的现役保护

[VirtualScreenObservationRegistry.kt:69](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/tools/android/VirtualScreenObservationRegistry.kt#L69) observe产生generation，在entries保存session/display/targets，同时安装UiGenerationFence。目标ref来自正index；locate先取本generation，再fence.lookup验证session、display、TTL及ref成员。clearSession删除entries，使后续locate拒绝；clearDisplay还清fence。不能因clearSession不清fence就认定ref仍能执行，因为入口首先要求entries存在。

[VirtualScreenUiBackend.kt:116](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/tools/android/VirtualScreenUiBackend.kt#L116) 动作实际调用locate，将内部locator与scanTruncated传给service，不把模型提供的ref直接当坐标。registry负责scope/lifetime/membership，live节点重新验证在service；只读registry不能独立证明整个Binder动作已通过或没有设备竞争。

观测默认cap120、夹1—500、最多保留8代、TTL5分钟。locator不出现在model-facing targets中；目标输出可保留label/bounds/actions，空label用viewId尾部名称。含敏感文本的完整脱敏取决于上游dump策略，本轮未仅凭registry没有再脱敏就另报泄漏。

## 扫描截断与输出截断不能混同

scanTruncated来自全局/窗口truncated，表示服务未读完；outputTruncated表示输出cap导致部分已读取节点未展示。fence安装和Found只传播前者。未展示ref没有注册，因此不能靠猜uN动作。普通时钟或banner变化不使全部ref失效，live locator校验仍是动作层职责。

packageFilter在registry只过滤targets；windows summary、includeTree和texts未应用它。后续需核对过滤参数的产品语义，当前不能宣称它是敏感数据或权限过滤器。文本过滤也不等于所有树字段均过滤。重复index/path、bounds int加法与坏形状输入是候选，未补足现役服务输出证据，不强行新增。

## 既有测试只证明它实际覆盖的策略

VirtualScreenObservationRegistryTest134行本轮单独完整重读，但同hash已全文登记，不重复累加。十例覆盖session/display、未知generation/ref、clear、output cap不标scan截断、service/window截断传播、隐藏locator、空label资源名、layout warnings以及fingerprint变化。

没有真实client/service、TTL时钟推进、8代淘汰、混包过滤、节点动作/设备截图的端到端证据。首次合并输出截断，后来单独读取无截断补足；未运行或改测试。

## OpenMinis与覆盖

上游HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，GIT_NO_LAZY_FETCH=1查Git树，无fetch；没有同路径AndroidCapabilityResolver/VirtualScreenObservationRegistry。只证明同名实现不可逐行对照，不证明上游没有等价能力，更不以旧PRoot实现替代Direct Ubuntu。

| 本轮新增全文 | 行数 |
|---|---:|
| [AndroidCapabilityResolver.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/tools/android/AndroidCapabilityResolver.kt#L1) | 268 |
| [VirtualScreenObservationRegistry.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/tools/android/VirtualScreenObservationRegistry.kt#L1) | 262 |

两文件首尾完整读取无截断，其它文件只是复核，不新增全文数。

新台账：[2026-10-07-code-review-coverage-0b96f7aa-capability-observation.json](/home/jiale/projects/minis-for-android/docs/archive/2026-10-07-code-review-coverage-0b96f7aa-capability-observation.json)。累计全文 **1325 / 1655文件、221739行**；partial5、not_registered325、excluded124，tracked1779。全库通读未完成。

交付校验旧180份报告及全部旧台账hash、固定本地/远端/上游HEAD、tracked/staged空diff、1779快照Git blob/hash/bytes/lines与证据行号。仅新增本文与台账。
