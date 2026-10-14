# ColorOS 记忆桥接结果预算与失败状态审查

日期：2026-10-06。固定 HEAD `d64e7c06d8159be90737dd9ba03dd9bd5855f339`。本轮新增 **2 项 P2**；全文阅读 9 个文件、1254 行。只读源码及既有测试，独立新报告，旧报告不变。未改产品代码，未运行测试、模拟器、Root、设备、LSPosed 或云任务，未访问真实厂商数据库或凭据。

## CM01 / P2：地点结果只限行数，正常长字段结果可超过响应上限而整批失败

[ColorOsMemoryDatabaseQuery.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/xposed/aimemory/ColorOsMemoryDatabaseQuery.kt:155) 的 searchPlaces 允许最多 30 行，在 [ColorOsMemoryDatabaseQuery.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/xposed/aimemory/ColorOsMemoryDatabaseQuery.kt:186) 直接将全部 currentRow 加入 JSONArray。currentRow 仅把单个字符串限制为 4000 个字符（[ColorOsMemoryDatabaseQuery.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/xposed/aimemory/ColorOsMemoryDatabaseQuery.kt:259)），没有整批 UTF-8 字节预算。

相对地，普通记忆/订单搜索在 [ColorOsMemoryDatabaseQuery.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/xposed/aimemory/ColorOsMemoryDatabaseQuery.kt:132) 按 240 KiB 总结果预算裁剪 details 或停止追加；地点路径没有复用这一控制。桥接 [ColorOsMemoryBridgeProtocol.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/xposed/aimemory/ColorOsMemoryBridgeProtocol.kt:58) 却硬性拒绝超过 320 KiB 的响应。

静态反例：受支持 memory_address 表中有 30 条地点，每条 insight 为 4000 个常见中文字符；仅 insight 文本就是 30×4000×3=360000 字节，超过 327680 字节，尚未计算 JSON 和其它字段。请求 limit=30 完全符合工具 schema；无需超长 query、超限参数或恶意请求。数据库 schema/字段长度为本代码支持范围内的假设样本，不声称用户真实数据库已经有此内容。

[ColorOsMemoryHooks.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/xposed/aimemory/ColorOsMemoryHooks.kt:128) 的 response 捕获编码超限，改回 COLOROS_MEMORY_HOOK_RESULT_TOO_LARGE，因而整个成功查询的 items 丢失，不能像记忆搜索那样返回预算内条目与 truncated。协议仍有效拒绝大响应，不将此问题描述成绕过响应大小边界；缺口是生产者预算与协议不一致造成正常结果失败。还会在判断前构造完整大 JSON，结果上限不是该路径的分配上限。

修复方向：地点结果复用实际 UTF-8 总预算，在封装前裁剪并标注 truncated，确保响应头与 JSON 开销也纳入预算。没有实现或生成数据库样本。

## CM02 / P2：桥接业务错误被标为工具成功

[ColorOsMemoryTools.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/ColorOsMemoryTools.kt:126) 成功解码桥接 envelope 后，在 [ColorOsMemoryTools.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/ColorOsMemoryTools.kt:134) 无条件返回 ToolExecutionResult(decoded,true)。decodeShellResponse 只核对 envelope/version/大小，不判断 JSON 的 ok 或错误 code。

厂商侧会正常返回小的错误 JSON：[ColorOsMemoryHooks.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/xposed/aimemory/ColorOsMemoryHooks.kt:111) 数据库不存在、请求无效、调用方拒绝或数据库查询失败，及 CM01 的结果过大；查询器也返回结构不支持错误。这些响应可以经过正确 envelope、shell 命令正常结束后被 App 解码。传输成功并不能证明查询成功。

例如数据库文件不存在，provider 返回 ok=false、COLOROS_MEMORY_DATABASE_MISSING；App 的结构化 success 却是 true。[ToolExecutionResult.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/ToolExecutionResult.kt:13) 定义该字段为工具结果状态；这里不能把它解释成查询 ok。错误内容仍在 output 中，Agent 能阅读错误，本项不声称错误消息全部隐藏或输出伪造了 items。确认的是结构化状态与业务结果不一致，三种 ColorOS 查询都共享 query 入口。

这与 CM01 独立：极小的缺库错误也触发，不需要大结果。已有 App 端 timeout、非零退出码、unavailable 和错误 envelope 会返回 false，不能称所有错误都被当成功。修复方向：解析并传播业务 ok，在失败时保留原 code/message，异常或不符合 JSON 合同的响应也要明确失败。本轮未修改代码。

## 其余边界及已有测试

生产注册在 MinisApp 605—608，三个 handler 由 ColorOsMemoryTools.handlers 注册并带 alias，不是只有未调用 helper。App 使用 PrivilegedCommandRunner 的固定 content basename/argv；provider hook 先核对特定 method，再验证 Binder.getCallingUid 为 0，并以 OPEN_READONLY 打开固定数据库。未发现本轮需要新增的任意 SQL/命令拼接缺陷：参数通过 selectionArgs，标识符来自已声明 schema 列并引用。没有执行 Root shell。

ColorDirect 的两指策略检查 fingerTrigger 与 fingerCount，默认开关关闭；dedup 窗口基于 uptime，成功触发后才更新 lastHandled。方法签名及 Activity 收尾是否完全符合厂商当前实现需要 ROM 证据，本轮不以反射方法存在就宣称设备功能正常。

三个已有测试只静态阅读，覆盖协议 round-trip、固定命令形状、错误 envelope 拒绝、identifier 转义、两指/去重判断、query 长度与 limit。它们未覆盖地点整批字节预算或业务 ok=false→ToolExecutionResult.success；没有运行或新增测试。

固定 OpenMinis `/tmp/openminis-review-20261004`，HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，只读 Git tree 未找到本轮 ColorOS memory/direct 同路径实现，未作共享缺陷判断。未 fetch；Eta 移植出处只作为源声明，不当作本轮上游源码核验。

## 全文覆盖与复核

| 本轮全文文件 | 行数 |
|---|---:|
| [ColorOsMemoryTools.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/ColorOsMemoryTools.kt) | 231 |
| [ColorOsMemoryBridgeProtocol.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/xposed/aimemory/ColorOsMemoryBridgeProtocol.kt) | 106 |
| [ColorOsMemoryDatabaseQuery.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/xposed/aimemory/ColorOsMemoryDatabaseQuery.kt) | 404 |
| [ColorOsMemoryHooks.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/xposed/aimemory/ColorOsMemoryHooks.kt) | 142 |
| [ColorDirectHooks.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/xposed/colordirect/ColorDirectHooks.kt) | 150 |
| [ColorDirectTriggerPolicy.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/xposed/colordirect/ColorDirectTriggerPolicy.kt) | 43 |
| [ColorOsMemoryQueryPolicyTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/tools/ColorOsMemoryQueryPolicyTest.kt) | 47 |
| [ColorOsMemoryBridgeProtocolTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/xposed/aimemory/ColorOsMemoryBridgeProtocolTest.kt) | 68 |
| [ColorDirectTriggerPolicyTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/xposed/colordirect/ColorDirectTriggerPolicyTest.kt) | 63 |

DatabaseQuery 按 1—205、206—404 干净读取；其余文件完整读取。ToolExecutionResult 与 MinisApp 此前已 full，只复核，不重复计数。

累计全文 **1051 / 1546 个文件、212463 行**；partial 13，not_registered 482，excluded 124。全仓通读尚未完成。

完成时核对全部 1670 tracked 路径、SHA-256、字节数和正数文本行数，本仓/固定上游 HEAD、tracked/staged 空 diff，此前 79 份报告哈希，本报告文件链接及行号，以及台账全文与批次总计。只写新报告与覆盖台账。
