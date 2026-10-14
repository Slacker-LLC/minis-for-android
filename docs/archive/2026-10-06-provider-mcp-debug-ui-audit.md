# Provider／MCP 调试配置与聊天状态界面审计

日期：2026-10-06。项目 HEAD：d64e7c06d8159be90737dd9ba03dd9bd5855f339。

本轮新增 **6 项静态确认问题（4 项 P2、2 项 P3）**，完成 7 个文件的全文阅读，合计 2257 行。只新增本报告并更新覆盖台账；不修改产品和旧报告，不运行测试、构建、模拟器、adb、真实 RPC、设备或云任务，不读取凭据。以下反例和坐标推导未做设备复现。

上一轮是实际进展：完成了 8 个文件全文阅读并产生新的调用链证据。本轮开始重新核对当前 HEAD、全部跟踪文件摘要和 diff 后再继续。调试 RPC 已有令牌校验，以下讨论授权请求的语义与失败处理，不推断未授权调用。

| 编号 | 优先级 | 问题 |
|---|---|---|
| CP01 | P2 | MCP 补丁将类型错误的 args／env／headers 当空值保存 |
| CP02 | P2 | 槽位更新校验失败前已经修改回退策略 |
| CP03 | P2 | Provider 凭据的 null／错误类型被转换成字符串，覆盖有效凭据 |
| CP04 | P2 | 模型刷新返回 false 被 RPC 忽略，失败与成功未变无法区分 |
| CP05 | P3 | hasCredential 在读取与修改响应中采用不同判据 |
| CP06 | P3 | 调试节点混用屏幕和窗口坐标，高亮在窗口有偏移时错位 |

## CP01：错误 MCP 补丁清空真实配置

[McpRpcMethods.kt:149](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/debug/McpRpcMethods.kt:149) 用 has 判断字段是否要更新，随后对 args 使用 optJSONArray，对 env／headers 使用 optJSONObject。失败得到 null，辅助函数 [第 172 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/debug/McpRpcMethods.kt:172) 和第 179 行把 null 转为空 Map／List。

[DebugMethodRegistry.kt:1063](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/debug/DebugMethodRegistry.kt:1063) 明确 args 是字符串数组，env／headers 是 object；它们没有声明可用 null 清除。现役分发未根据 registry 做完整类型校验。

**反例：** 服务 S 已有认证 headers，发送 `mcp.update {serverId:"S",headers:"错误类型"}`，传入的不是合法空对象，处理却成功保存 headers={}。args 为字符串会丢掉启动参数，env 为数组会丢掉环境变量；显式 null 也被静默清空。[MCPRepository.update:313](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/MCPRepository.kt:313) 发布这份替换配置并触发保存、重载，配置错误不只停留在返回值中。

应在修改前校验整个补丁，保留“省略”和“明确合法空集合”的区别。旧 DQ02 记录的是待办替换入口，本项确认的是 MCP 配置补丁的另一个真实损失路径；不把已有 MCP 落盘吞错 MC09 再计一次。

## CP02：失败的槽位请求已有部分提交

[ProviderMutationMethods.kt:493](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/debug/ProviderMutationMethods.kt:493) 先解析 fallbackTrigger 并调用 setFallbackTrigger；之后才检查 slot 名、entryIds 的数组形状和每个模型的能力。[ProviderRepository.kt:1377](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/ProviderRepository.kt:1377) 当场修改工作副本并调用 saveConfig，没有等待槽位校验，也没有失败回滚。

**反例：** 当前策略 default，请求 `{fallbackTrigger:"always",slot:"main-typo",entryIds:[]}`。RPC 返回 Unknown model slot，但回退策略已变 always。有效槽位配不兼容模型时也一样。由于 [接口声明:675](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/debug/DebugMethodRegistry.kt:675) 允许两者组合，客户端不能把“报错”理解为请求未生效。

应先完成全部校验，再在已有配置提交边界保存组合变更。这里没有要求恢复旧模型 group 双实现；问题属于本地现役固定槽位接口的提交次序。

## CP03：凭据错误类型被保存为字符串

[ProviderMutationMethods.kt:173](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/debug/ProviderMutationMethods.kt:173) 对 apiKey／oauthToken 用 optString，空字符串才删除，否则 saveApiKey。没有 isNull 或严格 String 检查。

Android 的 JSONObject.NULL 会被 optString 转成字面字符串 `"null"`，其它非空值也可能被字符串化，见 [AOSP JSONObject 的转换合同](https://android.googlesource.com/platform/libcore/+/refs/heads/main/json/src/main/java/org/json/JSONObject.java)。因此已有有效 key 的实例接收到 `{apiKey:null}` 后，保存的是四字符 null，并仍返回正常修改响应；数字、对象等错误类型也不会按 [string 参数声明:579](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/debug/DebugMethodRegistry.kt:579) 拒绝。

[ProviderRepository.saveApiKey:2373](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/ProviderRepository.kt:2373) 只规范化字符串后写偏好，不能识别原 JSON 类型。显式 `""` 清除和省略保持原值都已实现，本项不要求 null 也必须表示清除，而是要求无效类型不能破坏有效凭据。创建接口 [第 113 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/debug/ProviderMutationMethods.kt:113) 也使用同样的宽松解析。

同一更新还允许 apiKey 与 oauthToken 同时出现，两者写同一个存储槽；后写值可覆盖前写值。创建则优先 apiKey，更新优先最后处理的 oauthToken，二者语义不一致。将其合并在同一凭据参数校验问题中，不另计编号。未读取或尝试真实凭据，也未发出模型请求。

## CP04：模型刷新失败的 Boolean 没有进入 RPC 结果

[ProviderMutationMethods.modelsRefresh:426](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/debug/ProviderMutationMethods.kt:426) 调用 repo.refreshModels，但仅捕获异常，忽略 Boolean，随后返回 added／disappeared／total／durationMs。

[ProviderRepository.refreshModels:2117](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/ProviderRepository.kt:2117) 会把真实目录获取异常归成空结果；所有目录来源都未取得模型时 [第 2243 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/ProviderRepository.kt:2243) 返回 false，并保留旧列表。

**条件反例：** 已有 3 个模型，真实目录请求失败且 models.dev 没有匹配，Repository 返回 false。RPC 却返回正常 result、added=0、disappeared=0、total=3，和成功取得完全相同目录的形状一致。没有 error／refreshed 标记可区分失败、回退与成功未变化。

应保留 Repository 的真实结果，给调用方可判断的失败／未获取状态；无须把合法成功但模型集合未变当错误。旧 CF10 是免 key 的目录请求门槛，CF04 是晚到刷新写回，此处是刷新结果向 RPC 传播的独立缺口。

## CP05：同一实例的 hasCredential 前后矛盾

读取接口 [ProviderDebugMethods.kt:104](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/debug/ProviderDebugMethods.kt:104) 用 usableApiKey(inst)!=null。创建／更新响应 [ProviderMutationMethods.kt:305](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/debug/ProviderMutationMethods.kt:305) 却用 loadApiKey(inst.id)?.isNotEmpty()==true。

[usableApiKey:2395](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/ProviderRepository.kt:2395) 对允许免 key 的第三方端点返回空字符串。因此创建一个合法免 key 实例，响应的 hasCredential=false，随后 list 同一实例却变 true，配置并未变化。显式保存空字符串的实例也有相同差异。

这不是实际模型一定无法请求，路由层已有免 key 支持。问题是两个读回位置对“凭据存在／是否可使用”混用定义，调用方无法一致显示和判断。应统一字段语义，必要时分别表达真实凭据存在与有效配置可调用。

## CP06：节点 bounds 与高亮混用坐标系

[DebugInspectorMethods.viewBounds:315](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/debug/DebugInspectorMethods.kt:315) 用 getLocationOnScreen。Compose 节点则在 [第 86 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/debug/DebugInspectorMethods.kt:86)、树输出第 144 行和 inspect 第 237 行使用 boundsInWindow。[AndroidX SemanticsNode 源码](https://raw.githubusercontent.com/androidx/androidx/androidx-main/compose/ui/ui/src/commonMain/kotlin/androidx/compose/ui/semantics/SemanticsNode.kt) 明确区分相对窗口的 boundsInWindow 与相对屏幕的 positionOnScreen。

[showOverlay:332](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/debug/DebugInspectorMethods.kt:332) 对两类节点一律减去 decorView 的屏幕位置，把输入当成屏幕坐标。窗口在屏幕上有非零原点时，Compose 坐标又被扣掉一次窗口偏移；原生 View 则按正确的屏幕→decor 转换。树里两类节点共用 bounds 字段，没有坐标系标识。

**推导：** 窗口屏幕原点为 (100,200)，Compose 节点窗口位置 (30,40)，其 decor 局部位置应为 (30,40)，当前计算却得到负数再钳成 (0,0)。该输入可通过 [DebugRPCHandler.handleHighlight:1389](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/debug/DebugRPCHandler.kt:1389) 到达。窗口原点为零时可能看不出错误，未声称所有设备都会偏移。

应统一节点坐标和输出说明，并在同一坐标系内转换高亮。这是调试可视化准确性问题，不扩大成模型已点击错误目标或 Root 执行故障。

## 固定 OpenMinis 对照

只读比较 OpenMinis/OpenMinis 固定提交 b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c 的已有本地 Git 对象，没有 fetch、构建或运行上游。

- CP03：上游 [ProviderMutationMethods.kt:167](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/debug/ProviderMutationMethods.kt#L167) 也按 optString 写两个凭据字段，未解决 null／错误类型问题。
- CP04：上游 [ProviderMutationMethods.kt:433](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/debug/ProviderMutationMethods.kt#L433) 新增 forceRefresh 参数，但仍忽略返回结果。该参数不能证明本项已有修复。
- CP05：上游 [ProviderDebugMethods.kt:104](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/debug/ProviderDebugMethods.kt#L104) 也用 usableApiKey；[修改响应:309](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/debug/ProviderMutationMethods.kt#L309) 仍只判断非空已存 key，差异相同。
- CP06：固定上游 DebugInspectorMethods 与本地均为 369 行，关键坐标分支一致：[Compose 输入:86](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/debug/DebugInspectorMethods.kt#L86)、[overlay 转换:332](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/debug/DebugInspectorMethods.kt#L332)。未发现上游已补齐此处坐标边界。
- CP02 的本地固定 slot RPC 在该上游文件中没有对应 slotsSet；上游仍是 groupsCreate／groupsUpdate。不能为同步接口恢复已经退出的 group 双实现。
- 全路径检索未找到同名 McpRpcMethods.kt、ChatAgentStateUI.kt、JobRegistry.kt；只说明这三个 Android 模块没有同名对照，不据此推断上游其它语言／不同文件名没有等价能力。

## 已核对、旧问题与待确认项

- [ChatAgentStateUI.kt:74](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatAgentStateUI.kt:74) 的问题卡、[第 237 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatAgentStateUI.kt:237) 的审批卡和 [第 340 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatAgentStateUI.kt:340) 状态栏都有 ChatScreen 调用方；不是只按组件注释判断已接线。问题卡的取消清理缺口已属 DQ06，不重复计数。
- 状态栏／卡片若在同一 composition 复用而 sessionId 改变，未按 sessionId key 的 remember 会短暂带旧状态；目标编辑草稿也可能保留。还需证明导航真实复用路径和输入交错，本轮不把假设当已确认跨会话修改。
- MessageFeedbackRow 仍没有找到现役调用方；首次渲染不恢复 feedback 的问题不列为可达原生气泡故障。已确认的反馈落盘、归属问题留在 CR06／CR07。
- JobRegistry.kill 只改状态、AndroidLogManager worker 不随它停止，仍是旧 F22。本轮全文确认 trimOldest 不区分运行／终态，但实际数量、长期 watch 的淘汰交错还需进一步核对，不把“100 项 cap”本身当缺陷。当前生产 start 调用只来自有输出上限的 logcat watch，不能说所有任务任意累积无限正文。
- MCP 45 秒固定预算忽略 startupTimeoutSeconds 是旧 MC10，MCP 保存错误是旧 MC09；RPC 保留该字段并不能证明执行预算已经接通。
- Provider 导入字段遗漏、刷新竞态、免 key 目录刷新、OAuth 目录路线问题已有 CF 系列；本轮只增加以上不同入口的结果／参数证据。
- Provider.instances.test 自己构造探测请求，和真实 Provider 的 OAuth／UA／传输政策是否一致仍待完整路线比对；本轮没有发请求，不能拿普通 /models 探测代替实际聊天验证。quickTest 则复用已有 performTest，没有据方法名执行任何检查。
- DebugInspector 的 Compose 反射失败会静默少节点，snapshot 是全局最新快照，native／Compose host 遍历可能重复语义树。需要可验证的 UI 树与稳定定位对照，暂不据此新增故障编号。
- [DebugServerAuthTest.kt:19](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/debug/DebugServerAuthTest.kt:19) 有 loopback／远端、缺失／错误令牌及空 expectedToken 的否定断言。这里只读源码，不声明设备服务器验证通过，也不扩大测试覆盖范围。

## 后续验收建议（未执行）

| 场景 | 可复核工件 |
|---|---|
| MCP 错误类型、明确空集合与省略字段 | RPC 结果、配置文件前后摘要、实际启动参数／header canary |
| 组合槽位请求后项拒绝 | 回退策略、槽位与内存／持久配置前后快照 |
| Provider 错误凭据类型、双字段冲突 | 使用虚构 canary 的拒绝结果与存储前后摘要，禁止记录真实秘密 |
| 目录失败、回退成功、成功未变 | 各目录服务请求记录、Repository 返回值、RPC 状态和最终模型集 |
| 免 key 的创建／更新／读取 | 同一实例各响应与实际可调用配置状态 |
| 有窗口偏移的 native／Compose 树与高亮 | 窗口原点、节点坐标、overlay 局部坐标和截图 |

## 全文覆盖和只读复核

| 本轮完成全文文件 | 行数 |
|---|---:|
| debug/DebugInspectorMethods.kt | 369 |
| debug/McpRpcMethods.kt | 195 |
| debug/ProviderDebugMethods.kt | 355 |
| debug/ProviderMutationMethods.kt（原 partial） | 577 |
| tools/JobRegistry.kt | 124 |
| ui/chat/ChatAgentStateUI.kt（原 partial） | 572 |
| test/debug/DebugServerAuthTest.kt | 65 |
| 合计 | **2257** |

生产源码前缀为 src/android/app/src/main/java/com/openminis/app/，既有测试前缀为 src/android/app/src/test/java/com/openminis/app/。两个原 partial 文件本轮补齐全部范围，按完整文件总行数登记，与之前 partial 不重复计算全文行数。关联 Repository 已在以前轮次读完，本轮回读不重复增加计数；ChatScreen、DebugMethodRegistry、DebugRPCHandler 只登记实际补读范围。

累计全文 **773 / 1546 文件、163415 行**，partial 13，not_registered 760，excluded 124，跟踪总数 1670。全部代码阅读仍未完成。详情见[覆盖台账](/home/jiale/projects/minis-for-android/docs/archive/2026-10-05-code-review-coverage.json)。

复核项目 HEAD、全部 1670 个跟踪路径及当前 SHA-256、跟踪／暂存 diff、50 份既有报告摘要，以及本报告本地证据路径／行号和固定上游引用。旧报告保持原内容；本人工作区写入仅为新报告和覆盖台账。

本轮初始复核时跟踪 diff 为空，末尾发现外部新增两份未提交修改：scheduled/ScheduledReadOnlyPolicy.kt 增加 18 行 uniq 输出操作数限制，既有 ScheduledReadOnlyPolicyTest.kt 增加 10 行相关断言。已重新完整阅读当前 419／193 行，未编辑或运行它们。两份当前 SHA-256 分别为 `0ce2aebc92152192ae907d896cd23d2cc9a5b65f64faf8e35c9eba50ca36af99`、`2eb0ac894ae64d6dcd1a633582ec43e09c259abd4c0dcac2c0f0d5ab063f7b15`。台账保留上一内容版本信息并更新当前全文范围；全文文件数不重复增加，累计全文行数净增 28。故本轮新完成全文仍为 7 文件／2257 行，另重读修订后的 2 文件／612 行。最终跟踪 diff 仅有这两份外部修改，暂存 diff 为空；不能宣称最终整个工作区没有产品 diff。
