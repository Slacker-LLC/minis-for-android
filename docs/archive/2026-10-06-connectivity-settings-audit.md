# 网络连接、设备状态与系统设置工具审计

日期：2026-10-06。项目提交：`d64e7c06d8159be90737dd9ba03dd9bd5855f339`。只做静态审计，另建报告；不修改产品代码、旧报告及合同，不运行构建、测试、模拟器、adb、Root、设备或云任务，不读取凭据，也不扫描真实无线网络或修改设备设置。

新增 **4 项 P2 问题**。本轮新增全文覆盖 7 个文件、765 行；AndroidConnectivityTools 从 partial 提升为 full，其余 6 个首次登记全文。累计全文 **625/1,546 个纳入范围的文件、127,920 行**，另有 13 个 partial、908 个未登记；124 个排除项。全库排查仍未完成。以下反例为代码与平台合同支持的静态推导，尚无设备复现。

## 问题索引

| 编号 | 问题 | 影响 |
|---|---|---|
| NC01 | Wi-Fi 把扫描已发起当成结果已更新 | 旧缓存可能被标为非缓存，误导周边网络判断 |
| NC02 | 经典蓝牙发现接收器标为 NOT_EXPORTED | 来自独立蓝牙 UID 的发现广播可能无法送达 |
| NC03 | 并发经典蓝牙扫描没有共享生命周期管理 | 短扫描清理时取消长扫描使用的系统发现过程 |
| NC04 | 设置类型由当前值是否像整数推断 | 合法字符串设置从纯数字改为文字时被错误拒绝 |

## 现役链路

[MinisApp.kt:670](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/MinisApp.kt:670) 注册 Wi-Fi info/scan 和蓝牙 status，[685 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/MinisApp.kt:685) 注册 paired/scan，[675 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/MinisApp.kt:675) 注册 device.state，[696 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/MinisApp.kt:696) 注册 settings.get/set。这些是现役工具，不是仅存在于历史目录的代码。

Wi-Fi 和蓝牙查询使用普通 Android API，设备开关与设置写入另走受控 PrivilegedCommandRunner。此次不把“代码位于 App 本体”重复计作新问题，也不把正常 Android API 的使用本身视为职责错误。

## NC01 — scan_started 不能证明缓存已经更新

[wifiScan:122](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/AndroidConnectivityTools.kt:122) 调用 startScan；返回 true 后只 delay 一段固定时间，再读取 scanResults。没有监听扫描完成，也不读取完成广播的 EXTRA_RESULTS_UPDATED；输出 [135 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/AndroidConnectivityTools.kt:135) 将 cached_results 固定设为 `!started`。

[Android Wi-Fi 扫描流程](https://developer.android.com/develop/connectivity/wifi/wifi-scan)明确 startScan 与读取结果是异步过程；当前扫描未完成或未成功时，getScanResults 仍可能返回上一次结果。有效完成广播才提供更新状态。因此“请求已接受”和“结果属于新扫描”是两件事。

明确反例：缓存包含之前地点的 AP；当前调用传 timeout_ms=0，startScan 返回 true，但扫描尚未完成。代码立即读取旧列表，输出 scan_started=true、cached_results=false。timeout_ms=0 被当前 coerceIn(0,8000) 接受，不需要假设异常输入；默认 3000ms 或最大 8000ms 也不能证明异步扫描在等待期内完成。后续扫描失败同样没有反馈到 cached_results。

方向：将发起、完成、更新和超时分开；以有界的完成通知及结果更新时间提供新鲜度证据，允许返回缓存，但不能以 startScan 的布尔值断定其新鲜。无证据时应标为未确认，而不是断言非缓存。现有 startScan=false 时承认缓存的分支本身合理。

## NC02 — 蓝牙发现广播被接收器导出标志排除

经典蓝牙结果唯一入口为 [receiver:217](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/AndroidConnectivityTools.kt:217)，只处理 BluetoothDevice.ACTION_FOUND。注册的 [245 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/AndroidConnectivityTools.kt:245) 使用 ContextCompat.RECEIVER_NOT_EXPORTED，随后 startDiscovery。

Android [广播接收规则](https://developer.android.com/develop/background-work/background-tasks/broadcasts?hl=en)特别指出：蓝牙等框架组件可能运行于独立的高权限 App UID；NOT_EXPORTED 可以接收部分系统广播，却不能接收这些 UID 发出的广播。对这样的设备，扫描可以已经发起，而 ACTION_FOUND 仍无法到达当前接收器，经典设备结果缺失。

本项不声称全部 Android/OEM 都必然失败，也不声称 BLE callback 使用同一广播链。BLE 回调不经过这个 receiver；仍能看见 BLE 设备并不能证明 Classic 发现正常。输出中 classic_started=true 也仅证明 startDiscovery 返回 true。

方向：按真实发送方选择适当接收标志，并保持 action、合法设备对象及系统广播来源的校验边界；不能为接收发现结果而扩大为任意外部控制广播入口。需分别验收 Classic-only 与 BLE 设备，保留启动、广播和结果证据。未执行设备验收。

## NC03 — 每次扫描独立清理同一个系统发现过程

bluetoothScan 每次创建本地 hits/receiver/callback，在 [247 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/AndroidConnectivityTools.kt:247) 调用同一 adapter.startDiscovery；等待各自 duration 后，[256 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/AndroidConnectivityTools.kt:256) 只要本次 classicStarted 为 true 就调用 adapter.cancelDiscovery。没有跨调用的锁、共享扫描订阅或活跃引用计数。synchronized(hits) 只保护本次调用的结果集合。

[BluetoothAdapter 合同](https://developer.android.com/reference/android/bluetooth/BluetoothAdapter)说明 discovery 由系统服务运行，cancelDiscovery 取消当前设备发现过程；它不是由每个 receiver 私有持有的扫描句柄。BLE 的 stopScan(callback) 与这个全局 Classic 取消动作不能按同一所有权理解。

并发可达证据：[MCPServer.kt:97](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/mcp/server/MCPServer.kt:97) 为不同连接建立独立协程；经过 token scope 与确认后，[324 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/mcp/server/MCPServer.kt:324) 调用 ToolExecutor。[ToolPermissionManager.kt:70](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/runtime/ToolPermissionManager.kt:70) 将 bluetooth.scan 设为 MCP_CONFIRM，并非禁用远程执行。[ToolRegistry.kt:167](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/runtime/ToolRegistry.kt:167) 直接路由执行，没有扫描互斥；[MinisApp.kt:818](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/MinisApp.kt:818) 的 Android PrefixProvider 也是透传。

条件交错：A 的扫描已成功启动并等待 2 秒；1 秒后 B 开始长扫描，在 A 的系统发现过程活跃期间等待结果。A 的 finally 取消系统 discovery，B 虽然仍在 delay，却不再获得该轮 Classic 发现。此反例不要求 B 的 startDiscovery 必须返回 true；它注册了自己的接收器并消费同一个活跃发现过程即可。若平台重复发起会替换发现过程，A 清理当前过程同样可能取消 B 使用的过程。具体返回与广播顺序需设备核验。

这是两个均已获授权的调用相互干扰，不是权限绕过；也不声称停止 Classic 一定停止 B 的独立 BLE callback。NC02 与本项独立：在 Classic 广播能够送达的平台，或者修正接收标志后，扫描生命周期冲突仍存在。

方向：明确 Classic discovery 的共享所有权，序列化不兼容的扫描，或共享发现并让各调用独立订阅，最后一个使用者结束才按归属清理。需要有界等待、取消和异常清理，不能用取消任意其它调用的扫描来完成自己的收尾。

## NC04 — 当前字符串内容不是设置项类型

[AndroidSettingsOps.set:48](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/AndroidSettingsTools.kt:48) 读取旧值；只要旧值满足整数 regex，新值不是整数就拒绝。判定没有使用 key 或其平台类型。`isInteger` 的 [107 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/AndroidSettingsTools.kt:107) 只检查文本形状。

反例：Global 的 device_name 当前是合法名称 `1234`，请求设置为 `Jiale Phone`。工具把旧名称识别为整数并在任何写入尝试之前拒绝。Android [Settings.Global.DEVICE_NAME](https://developer.android.com/reference/android/provider/Settings.Global#DEVICE_NAME)定义的是设备名称，没有“名称一旦全是数字就只能永久使用数字”的类型语义。通用 settings.set 的 schema 本来也接受字符串。

此守卫想保护 screen_brightness 等整数设置，目的合理，但把任意旧值推断成类型会同时误拒绝合法字符串；对不存在的已知整数 key 又完全不能验证类型。后者作为设计限制说明，不另拆一项。本轮只用 device_name 的确定分支计数，不推测每个 OEM 设置的类型与取值范围。

方向：只对已知具有数字语义的 key 使用其具体规则；未知 key 不应仅凭当前值推断类型。保持现有长度、namespace/key 校验与写后核对。[writeResult:122](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/AndroidSettingsTools.kt:122) 已返回 valueAfter 和 verified，不能将 verified=false 误写成没有任何失败证据。

现有 [AndroidSettingsTypeGuardTest.kt:16](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/tools/AndroidSettingsTypeGuardTest.kt:16) 只验证整数 predicate；[AndroidSettingsOpsTest.kt:10](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/tools/AndroidSettingsOpsTest.kt:10) 验证非法 namespace/key。它们没有证明按旧值推断设置类型是正确的，也未覆盖纯数字设备名改为文字。本轮未执行测试。

## 未计作新问题的线索

- DeviceStatePolicy 对未知 target 返回 null，命令以 argv 构造，没有从 target 生成任意 shell。不能仅因使用特权 runner 就断言新增通用 Root 入口。
- AndroidDeviceStateHandler 对 enabled 使用 optBoolean，单看 helper 会担心 null 被当成 false。但本地聊天的 [preflight:319](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:319) 拒绝 required null，[347 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:347) 校验声明类型；设备开关未在 MCP 开放表中列出。本轮没有足够证据将畸形 enabled 作为普通模型调用已可达的误关机/断网缺陷，不计数。
- 权限请求在查询前发生；缺少权限会返回明确错误，不把无线权限缺失误判成扫描列表为空。此前后台权限交互问题不重复计数。
- 蓝牙结果中存在 ble_error。不能仅以 BLE 启动是异步过程，就忽略此错误字段而声称所有扫描故障均被静默隐藏。

## OpenMinis 对照与全文记录

只读固定上游：`b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`。树清单未列出本轮三个 Android 实现的同名文件，检索发现的是 iOS NativeOffloads/BluetoothOffload.h/.m。本轮不拿 iOS 平台行为替代 Android API 合同，也不推断上游没有任何等价功能。未执行 fetch 或上游代码。上述四项由当前实现和 Android 主文档支持。

| 本轮全文文件 | 行数 | 原状态 |
|---|---:|---|
| [AndroidConnectivityTools.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/AndroidConnectivityTools.kt) | 391 | partial |
| [AndroidSettingsTools.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/AndroidSettingsTools.kt) | 180 | not_registered |
| [AndroidDeviceStateTools.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/AndroidDeviceStateTools.kt) | 67 | not_registered |
| [DeviceStatePolicy.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/DeviceStatePolicy.kt) | 27 | not_registered |
| [DeviceStatePolicyTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/tools/DeviceStatePolicyTest.kt) | 53 | not_registered |
| [AndroidSettingsOpsTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/tools/AndroidSettingsOpsTest.kt) | 17 | not_registered |
| [AndroidSettingsTypeGuardTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/tools/AndroidSettingsTypeGuardTest.kt) | 30 | not_registered |
| 合计 | 765 | 7 个文件 |

上述文件均完成全文读取，长文件分段且输出未截断。已有全文记录的 ToolProvider 本轮再次全文复核，不重复增加累计文件/行数；ToolRegistry、ToolCallValidator、MCPServer、ChatViewModel、ToolPermissionManager、MinisApp 的复核片段也不算新增全文。覆盖记录见 [台账](/home/jiale/projects/minis-for-android/docs/archive/2026-10-05-code-review-coverage.json)。

交付核对：1,670 个受跟踪文件清单与 SHA-256、HEAD 保持一致，tracked diff 为空；37 份旧 Markdown 报告哈希不变。本轮只新建本报告、更新覆盖台账。报告本地引用通过文件存在与行号检查。未运行任何产品验证；Android/OEM 广播 UID、无线扫描时序、并发发现返回行为和具体设置写入仍没有真机证据。
