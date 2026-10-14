# Debug CLI参数转发与guest边界测试静态审查

日期：2026-10-06。本仓固定HEAD `d64e7c06d8159be90737dd9ba03dd9bd5855f339`。新增全文7文件、778行，确认1项P3。只静态阅读，不改产品源码、不编写或运行测试、不构建/启动模拟器/adb/Root/设备/云/模型，不连接DebugServer、不读取实际debug token或其它凭据。独立新报告，旧报告不改。

## DCL01 / P3：minis-debug位置参数转发先吞掉下游选项，帮助示例执行的是另一个命令

[DebugOffloadHandler.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/DebugOffloadHandler.kt:41) handle把整个request.argv.drop(1)交给OffloadArgs，然后第104行buildRpcCall只用args.positional.drop(1)作为rest。[OffloadArgs.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/OffloadArgs.kt:35) 通用parser把--key=value、--key value、-a等收进values/flags，并从positional移除；它不知道哪些选项属于下游命令。

最小反例正是本文件帮助的 `minis-debug exec uname -a`（第348行）：request.argv为minis-debug/exec/uname/-a，OffloadArgs.positional只剩exec/uname，rest只有uname。[DebugOffloadHandler.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/DebugOffloadHandler.kt:145) 用rest.joinToString构造debug.shellExecute.command，实际RPC得到uname而不是uname -a。[DebugRPCHandler.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/debug/DebugRPCHandler.kt:1208) handleShellExecute把这个command直接交给ExecutionCoordinator；后续没有找回被丢弃的flag。这里只推导最终command参数，不实际执行uname或任何shell。

同一根因影响shizuku/model-use位置参数方式：例如minis-debug shizuku package list --user 10，--user与10被外层values吸收；[DebugOffloadHandler.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/DebugOffloadHandler.kt:180) buildExecForwarder的默认分支只能构造package/list。[DebugRPCHandler.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/debug/DebugRPCHandler.kt:1532) 真正接收到args array后原样prepend android-shizuku-cli并交handler，无法恢复原user选项。这里不依赖该下游操作成功来证明转发丢失。

显式--args JSON分支保留数组（第181—188行），因此该方式能绕开本项；call --params也可直接构造RPC。不能据绕开方式存在声称帮助承诺的argv转发正确。--help按wrapper合同返回顶层帮助是刻意行为，与其它下游选项丢失区分。

[MinisApp.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/MinisApp.kt:914) BuildConfig.DEBUG条件内注册此handler；Release不注册，不把问题扩大到Release的现役Root launcher或通用生产工具，也不称为任意权限绕过。修复方向：先识别wrapper子命令/自身参数，对透传部分保留原始argv和参数边界；不能只把某几个常见下游flag加入BOOLEAN_FLAGS，因为boolean flag同样从positional移除。未改实现。

## Debug传输及暂不计数候选

请求固定127.0.0.1:5321，connect/read timeout30秒，try/finally关闭socket，读取App私有token文件并放X-Minis-Token。本轮只看代码，没有读该文件。内容以UTF-8 byte长度发送，HTTP response读取确切Content-Length；短读EOF会抛异常并返回非零。

response parser自己没有header/body硬上限、忽略status line且无Content-Length时读到EOF；现役DebugServer提供Content-Length并用RPC error envelope，故未以假设畸形/冒充本地服务直接确认真实内存耗尽或认证成功。外层exit只判断RPC error，不检查result内业务exit_code/exitCode，这是其注释声明的RPC层语义；未把它单独当新缺陷。

exec用空格拼回shell字符串，独立argv的空格/quote字面值是否保真仍需明确command...合同；JSON call能给完整command。没有以这一候选额外增加编号，也不将raw debug shell能力当恢复minisd Root broker。buildExecForwarder的JSONArray只验证JSON数组语法，RPC的arr.optString会转换非字符串值，这是输入严格性候选，未以此声称新增执行权限。

## 六份已有测试的实际覆盖

OffloadReplySweepTest全文108行：用临时File和自己定义的eligible镜像验证prefix、isFile、TTL与all策略，并没有调用生产sweepStaleReplies。现役 [NativeOffload.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/NativeOffload.kt:177) 的prefix/isFile/cutoff判定与镜像相符，start遍历session目录和周期回复触发是另一个调用范围；不能把这些测试当真实guest pending-cat交错或服务器重启已通过。没有创建测试临时文件。

OffloadForegroundTest全文37行，调用真实backgroundLaunchBody并验证原因/action/subject；只覆盖错误体，不证明OEM后台启动拒绝检测与实际Activity启动流程。

MinisConfigPublicInterfaceTest全文33行，调用真实ConfigOffloadHandler help，检查公开help与无DebugServer/PRoot词；该fixture cwd=/workspace只用于help，不能据它证明现役session执行绕过workspace隔离，也不证明所有写操作的确认门已经测过。

ClipboardBoundsPolicyTest全文50行，调用真实read/writeRefusal，包含精确边界和超界拒绝、truncated标记；没有触碰系统剪贴板。现役ClipboardOffloadHandler第99/126行接入这些policy，但测试不覆盖平台读写授权或回调生命周期。

MediaQueryPolicyTest全文90行，调用真实clamp/type/nameFilter/anyColumnFilter，覆盖query参数化及百分号/下划线/反斜杠逃逸、多列重复参数、未知类型/空列拒绝。column由handler代码传入，不能拿helper可接受任意column参数直接声称用户SQL注入；真实PhotosOffloadHandler第106/114/323行接入。没有查询用户媒体。

BoundedHttpTest全文102行，调用生产reader/decode/writeResponse，覆盖UTF-8字节Content-Length、短body、非法UTF-8、冲突length与Transfer-Encoding拒绝，以及response字节长度。它测试network/BoundedHttp的server reader，不是本轮DebugOffloadHandler的自写response parser；不能将其header/body边界自动转移到后者。未运行测试或打开socket。

## 固定OpenMinis对照

只读 `/tmp/openminis-review-20261004` 固定HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，未fetch。上游实现位于旧sandbox/offload/DebugOffloadHandler.kt，runtime/guest同路径不存在；查tree后读取实际旧路径。第35行同样将全argv送OffloadArgs，exec同样用positional rest.joinToString，shizuku/model-use默认同样由rest构造JSONArray，因此DCL01也见于该上游片段。路径移动不表示应恢复旧PRoot/Alpine或minisd通信结构。

## 覆盖与完整性复核

| 新增全文文件 | 行数 |
|---|---:|
| [DebugOffloadHandler.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/DebugOffloadHandler.kt:1) | 358 |
| [BoundedHttpTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/network/BoundedHttpTest.kt:1) | 102 |
| [MinisConfigPublicInterfaceTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/offload/MinisConfigPublicInterfaceTest.kt:1) | 33 |
| [ClipboardBoundsPolicyTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/runtime/guest/ClipboardBoundsPolicyTest.kt:1) | 50 |
| [MediaQueryPolicyTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/runtime/guest/MediaQueryPolicyTest.kt:1) | 90 |
| [OffloadForegroundTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/runtime/guest/OffloadForegroundTest.kt:1) | 37 |
| [OffloadReplySweepTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/runtime/guest/OffloadReplySweepTest.kt:1) | 108 |

DebugOffloadHandler分1—185、186—358，六测试各自全文读取，输出完整。相关parser/server/RPC/handler仅片段复核，不重复计数。累计全文 **1336 / 1546文件、258935行**；partial13、not_registered197、excluded124，全仓通读尚未完成。

完成时核验全部1670 tracked路径SHA-256/字节数/正数文本行数、两仓固定HEAD、tracked/staged空diff、此前133份报告哈希、新报告绝对链接与行号、全文批次总计。只新增本报告并更新覆盖台账。
