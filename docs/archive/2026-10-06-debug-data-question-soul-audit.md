# 调试数据 RPC、用户问题卡片与 SOUL 存储审计

日期：2026-10-06。项目 HEAD：d64e7c06d8159be90737dd9ba03dd9bd5855f339。

本轮新增 **9 项静态确认问题（8 项 P2、1 项 P3）**，新增全文阅读 18 个文件、2588 行。只新增本报告并更新覆盖台账；不修改产品和旧报告，不运行测试、构建、模拟器、adb、真实 RPC 或云任务，不读取凭据。

例子与交错是源码推导，未实际复现。调试数据接口的现役调用入口是 DebugServer→DebugRPCHandler；DebugServer 只在 Debug 启动、dispatch 前校验令牌。本报告不把已授权数据修改接口描述成未授权攻击入口，也不根据“Web Remote”注释推断已经存在另一条生产服务器链路。

## DQ01 — P2：Agent 设置缺少必填参数时仍覆盖现有值

[AgentRpcMethods.kt:91](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/debug/AgentRpcMethods.kt:91) 对缺少／不可转成整数的 maxDepth 和 timeoutMinutes 使用 DEFAULT_MAX_DEPTH、DEFAULT_TIMEOUT_MINUTES，随后把两者一起保存。

[DebugMethodRegistry.kt:1269](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/debug/DebugMethodRegistry.kt:1269) 明确声明这两个字段 required=true，但 [DebugRPCHandler.kt:256](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/debug/DebugRPCHandler.kt:256) 直接分发，不按 registry 校验 required/type。

**反例：** 当前 timeoutMinutes=30，发 `agent.settings.set {maxDepth:2}` 会返回 ok 并将 timeoutMinutes 改为 10；发空对象则重置两个值。这里的契约是两个必填字段，不是补丁更新；应拒绝缺参，而不能悄悄回默认。类似的 required active/mode 缺参也分别被 goal.setActive 的 true 和 plan.set 的 off 默认值替代，归入同一 RPC 必填字段不落地问题，未拆编号。未运行 RPC。

## DQ02 — P2：待办替换把错误列表当成合法空列表，清掉已有内容

[AgentStateRpcMethods.kt:54](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/debug/AgentStateRpcMethods.kt:54) 先建立空 items；optJSONArray 失败便不填内容，数组中的非 JSONObject 也直接 continue，最后仍调用 todoReplace。

必填完整数组声明见 [DebugMethodRegistry.kt:1339](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/debug/DebugMethodRegistry.kt:1339)。底层 [AgentStateStore.kt:119](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/AgentStateStore.kt:119) 对空列表删除原 session 的待办。

已有待办时，省略 items、items=null、items 为字符串或 `["bad row"]` 都能触发清空并返回正常结果；混合合法／非法行则静默保存不完整列表。应在任何修改前校验整个替换集合，区分明确的 `[]` 与无效输入。这是 RPC 自身的拒绝边界，旧 PV01 的 Provider 数组修复错误并未修复或验证这条入口；Provider 症状不再另计。后续应保存拒绝前后的完整待办快照，本轮未执行。

## DQ03 — P2：权限预设的清除契约与事件投影不一致

[DebugMethodRegistry.kt:1289](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/debug/DebugMethodRegistry.kt:1289) 允许 preset=null 清除。实际 [AgentRpcMethods.kt:44](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/debug/AgentRpcMethods.kt:44) 用 optString(...).ifEmpty 转为可空值，没有检查 JSON null。[Android JSONObject 源码](https://android.googlesource.com/platform/libcore/+/refs/heads/main/json/src/main/java/org/json/JSONObject.java)明确将 JSONObject.NULL 转成字符串 `"null"`，故合法的 JSON null 被 isKnownPreset 拒绝。

反而省略必填 preset 或传空字符串会清除。此时 [第 51 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/debug/AgentRpcMethods.kt:51) 把 null 投影为 workspace-write，并发 approval/policy=ask；但 [SessionPermissionStore.kt:60](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/SessionPermissionStore.kt:60) 在预设不存在时直接允许文件写入，不执行 workspace-write 的路径限制。

因此相同“清除”操作有的被错误拒绝，有的成功后把文件权限投影成更严格的模式。这里仅确认该文件写入 gate 与事件的差异，没有据此声称所有工具都完全无权限检查。应统一 null／缺失语义，并从实际默认策略派生事件。后续核对 JSON null、缺参拒绝、读取值与非 workspace 写入 gate 的结果，未执行。

## DQ04 — P3：重复设置已存在的挂载写策略返回错误

[StorageRpcMethods.kt:55](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/debug/StorageRpcMethods.kt:55) 把 setUserAllowWrite=false 视为 invalid params；底层 [MountedFoldersStore.kt:255](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/MountedFoldersStore.kt:255) 在目标存在但 allow 值已经相同时也返回 false。

客户端重试一次已经成功的 `allowWrite:false`，或者初始化时把只读挂载显式设为只读，得到错误而不是当前 mount。注册定义是“设置指定写策略”，没有规定必须发生变化。应分开不存在／持久化失败／已达到目标状态；无需为幂等设置重写文件。未运行挂载操作。

## DQ05 — P2：SOUL 元数据转义后不解码，反复保存会累积反斜杠

[SoulStore.kt:166](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/agent/SoulStore.kt:166) 的 serialize 对元数据中的反斜杠和双引号转义；[parse:125](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/agent/SoulStore.kt:125) 只去掉首尾双引号，没有逆向解码。

**反例：** 名称中的一个双引号保存后成为反斜杠加双引号，下一次读取把那个反斜杠当成名称正文；下一次 serialize 再转义它。路径样式的反斜杠也会每次读写增加。通过 [MemoryRpcMethods.kt:127](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/debug/MemoryRpcMethods.kt:127) 读取旧元数据、仅更新 body 的 soul.save 也会改写这些字段，用户不必主动修改名称。

[SoulStoreRegressionTest.kt:11](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/agent/SoulStoreRegressionTest.kt:11) 的往返测试将特殊字符放在 body，元数据不含这些字符，因此不能证明元数据可往返。本轮只读测试，没有运行或新增测试。

应使已支持的元数据序列化／解析互逆，保留原值；多行标量与未知 YAML 语法另外明确支持范围。固定上游 [parse:135](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/agent/SoulStore.kt#L135)、[escape:182](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/agent/SoulStore.kt#L182) 有相同结构。未执行往返验证。

## DQ06 — P2：取消等待答案的工具后，问题卡片继续挂在 pending 中

[AskUserQuestionTool.kt:50](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/AskUserQuestionTool.kt:50) 注册卡片，第 63 行等待独立 CompletableDeferred。只有正常超时的 answer==null 分支移除卡片，没有 finally。外层回合取消时 deferred.await 抛 CancellationException，跳过这个分支。

[QuestionCenter.kt:47](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/QuestionCenter.kt:47) 的 pendingFor 只按 sessionId 筛选，没有检查原运行是否活着。cancelForSession 函数虽存在，全库调用搜索只有声明，没有实际调用。

因此原工具已经停止，RPC 仍能列出卡片；之后对其提交答案也可返回 answered=true，实际上没有等待中的原回合可恢复。这不是简单“卡片进程内保存”的预期行为。应在工具的所有退出路径移除注册，并让取消后的答案明确失效。后续记录 run/tool/question ID、取消与 pending/answer 的结果，本轮未执行。

## DQ07 — P2：答案没有按原问题约束校验，就被当成人类选择

[QuestionRpcMethods.kt:46](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/debug/QuestionRpcMethods.kt:46) 只检查 selected/custom/skipped 至少一个存在；[QuestionCenter.kt:56](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/QuestionCenter.kt:56) 不核对 options、multiple、allowCustom，直接 complete 并移除卡片。

原问题为单选且不允许自定义、只有 A/B 两个值时，发送未提供的 C、同时选 A+B、或填 custom 都能结束问题；[AskUserQuestionTool.kt:95](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/AskUserQuestionTool.kt:95) 随后把它作为 success=true 的 User answered 返回模型。

鉴权只证明客户端能调用该接口，不能证明参数符合此卡片的回答约束。应在取到原问题后、消费它之前校验选项身份、数量与自定义许可，非法答案保持 pending；skip 语义另行明确。此处只登记问题答案的数据完整性，不把它等同于工具审批绕过。未执行答案提交。

## DQ08 — P2：跨会话搜索按消息数量截断，活跃会话挤掉其它会话

[ChatSearchRpcMethods.kt:40](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/debug/ChatSearchRpcMethods.kt:40) 为 limit 个会话取 limit×3×2 条消息，再分组。Repository 在 [ChatRepository.kt:695](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/ChatRepository.kt:695) 收满消息限额后停止，未按会话去重／取游标；RPC 没有继续扫描。

**反例：** 默认 limit=20，最新 120 条匹配都在 A，B/C 等会话也有更早的真实匹配。Repository 返回 A 的 120 条，RPC 最终只展示 A 的 3 条命中和一个会话；B/C 被完全遗漏。放大有限 over-fetch 系数不能保证不同会话结果数量。matchedCount/total 也仅描述这个截断消息前缀，没有标记更早结果尚未扫描。

这不是此前已登记的关键词窗口／SQL false-positive 搜索问题；即使每条都是干净、精确的文本命中，也会发生。应按产品的会话结果语义取数，或通过有界游标继续扫描，并把未穷尽状态传给客户端。后续保存固定 A/B/C 消息集、顺序、请求与结果，本轮未执行搜索。

## DQ09 — P2：SOUL 首次初始化仍可覆盖检查后刚保存的用户文件

[MinisApp.kt:509](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/MinisApp.kt:509) 后台初始化 SOUL。[SoulStore.kt:343](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/agent/SoulStore.kt:343) 先 info，再在确认 NOT_FOUND 后独立 writeBytes 默认内容；ensureActive 只检查协程取消，不能证明文件仍不存在。

**允许交错：** 初始化 A 检查到不存在；用户通过 soul.save 的 ensureExists B 创建文件，随后 save 写入编辑内容；A 再提交默认内容。现役 [SecureFileAccess.kt:349](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/files/SecureFileAccess.kt:349) 删除目标并提交临时文件，不提供“仅当目标不存在”条件，因此用户内容被默认内容替换。

只有读到确认的 NOT_FOUND 才播种，是已有正确防护；本项是检查与创建之间的并发缺口，不重复旧迁移流程覆盖用户数据的问题。[SoulStoreEnsureExistsTest.kt:18](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/agent/SoulStoreEnsureExistsTest.kt:18) 覆盖错误分类，但没有在 inspect 与 seed 之间插入另一写入，不能证明不会覆盖后来出现的文件。

应让初始化写入具有创建条件，且与现役写入边界协调；不能把普通覆盖写称作 create-if-missing。固定上游 [ensureExists:362](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/agent/SoulStore.kt#L362) 也先 exists 再 writeText，但使用其旧 host 存储，不建议复制旧路径。未执行初始化交错。

## 不重计与证据边界

- MemoryRpc 的 filesRead 仍通过 readFile 把读取失败映射为空，soulSave 的 load 失败也会以默认元数据、空正文作为基线。这是旧 UI03 类缺口的另一个入口，本轮不再编号。记忆编辑的日期窗口、重复正文定位与整文件覆盖仍属旧 MI/UI 报告。
- SkillRpc.importUrl 的 GitHub 分支仍走返回后后台补 sibling 的旧逻辑，局部失败仅日志记录；旧 KM07 已覆盖，本轮不重计。新增读取 SkillRepository 523–547、740–815 行片段，仍未全文。
- SafeRemoteImporter 确实限制 HTTPS/443、URL 凭据、重定向次数与读取前／读取中大小，并把公有地址检查放到连接使用的 Dns 上。198.18/15 的域名 fake-IP 与模拟器例外是显式兼容规则；本轮没有运行网络请求或验证真实代理，不能凭“非公有地址”这一名字把全部兼容规则自动判为漏洞。已读测试只覆盖地址函数，不能代替重定向／连接路径证据。
- Environment RPC 回包不包含值，只包含 hasValue；已授权调用方的读写能力与底层值清洗、并发更新另有边界。本轮没有读取任何真实环境值。Storage RPC 明确要求原生 picker，不能把 canAddFromWeb=false 当作遗漏功能。
- 固定上游完整路径清单没有 AgentRpcMethods、AgentStateRpcMethods、QuestionRpcMethods、QuestionCenter、AskUserQuestionTool、ChatSearchRpcMethods、StorageRpcMethods、SafeRemoteImporter 这些同名文件。只能说明这些 Android 同名实现不存在；未据此证明上游不存在其它语言或等价机制。SOUL 对照则检查了具体函数。
- Feedback、权限与 AgentState 接口接受不存在的 ID、无版本号全量替换，以及输出／输入类型宽松等候选行为，仍需连同真实客户端与状态生命周期审计，未为数量单独增加泛化问题。

## 后续验证工件（本轮未执行）

| 范围 | 应保留的可复核结果 |
|---|---|
| 设置／待办参数拒绝 | 当前状态、非法请求、错误码及修改前后状态一致性；明确空数组成功路径 |
| 权限清除 | null/缺参/空字符串区别、读取预设、事件投影与文件 gate 的实际结果 |
| 挂载幂等设置 | 连续相同请求、mount 快照、持久化结果 |
| SOUL 读写与初始化 | 元数据原始字节、多次往返值、初始化／编辑交错顺序与最终文件摘要 |
| 问题取消／答案拒绝 | run/tool/question ID、pending 生命周期、非法答案仍可重新作答、取消后拒绝 |
| 搜索会话覆盖 | 固定消息顺序、A 的密集命中、B/C 的较早命中、游标／截断与计数语义 |

## 全文覆盖与只读复核

| 本轮新增全文文件 | 行数 |
|---|---:|
| AgentRpcMethods.kt / AgentStateRpcMethods.kt | 144 / 124 |
| MemoryRpcMethods.kt / StorageRpcMethods.kt | 137 / 97 |
| SkillRpcMethods.kt / EnvironmentRpcMethods.kt | 162 / 89 |
| QuestionRpcMethods.kt / FeedbackRpcMethods.kt / ChatSearchRpcMethods.kt | 71 / 45 / 85 |
| SafeRemoteImporter.kt / SafeRemoteImportPolicyTest.kt | 200 / 58 |
| MemoryRepository.kt / SoulStore.kt | 513 / 485 |
| QuestionCenter.kt / SubagentLimits.kt / AskUserQuestionTool.kt | 79 / 42 / 114 |
| SoulStoreRegressionTest.kt / SoulStoreEnsureExistsTest.kt | 66 / 77 |
| 合计 | 2588 |

完整路径、哈希和全文范围见[阅读台账](/home/jiale/projects/minis-for-android/docs/archive/2026-10-05-code-review-coverage.json)。DebugMethodRegistry 只增加实际读到的片段，仍未全文；DebugRPCHandler、MinisApp、SkillRepository 也只更新片段。已登记全文的底层 Store／文件 API 不重复增加全文数。

累计全文 **758/1546 个纳入文件、159142 行**；14 个部分阅读、774 个未登记。全仓阅读目标尚未完成。

交付前复核 1670 个 tracked 路径及 SHA-256、固定 HEAD、tracked/staged diff、此前 48 份报告原哈希，以及本报告证据的本地路径／行号与固定上游引用。没有真实回合、设备、网络导入、数据修改或云资源验证，静态推导不作为实测。
