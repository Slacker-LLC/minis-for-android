# MCP 服务端与 OAuth：权限变更、生命周期和回调审计

日期：2026-10-05。当前 HEAD：`d64e7c06d8159be90737dd9ba03dd9bd5855f339`。

本轮新建独立报告。只读生产源码、测试源码、固定 OpenMinis git 对象和协议文档；未改产品代码，未运行测试、构建、模拟器或设备操作。下列结论均为静态调用链证据，不是设备复现或第三方服务受影响的声明。

## 新发现

| 编号 | 优先级 | 问题 |
|---|---|---|
| MS01 | P1 | 轮换设置页令牌重置用户选定的工具范围 |
| MS02 | P1 | 正文读取前取得的 Bearer/scope 快照在执行前不重新校验 |
| MS03 | P2 | 停止服务不关闭已接受的阻塞 socket |
| MS04 | P2 | 快速结束的连接 Job 可能永久留在跟踪集合 |
| MS05 | P2 | 监督循环在一次重绑失败后不再重试 |
| MS06 | P2 | 通知返回 HTTP 200，且按方法前缀判断通知 |
| OA01 | P2 | MCP OAuth 的 state 校验可缺省，且发生在监听器关闭之后 |
| OA02 | P2 | 拒绝授权、端口占用等终态没有结束等待，所谓五分钟超时未实现 |
| OA03 | P2 | 回调先解码整个 query，再拆分和解码参数，破坏授权码 |
| OA04 | P2 | OAuth 原始回调 URL 中的 code/state 进入日志 |
| OA05 | P2 | 取消授权不取消同步 token 交换，晚到结果仍可落盘 |
| OA06 | P2 | 自定义 redirect URI 可通过校验却指向实际未监听的协议或端口 |
| OA07 | P2 | OAuth 回调 request line 和 OPTIONS headers 没有长度/总量边界 |

P1 表示权限授权语义存在具体缺口；P2 表示条件触发的可靠性、协议或数据处理问题。并未发现这些入口重新启用 minisd/PRoot，也未把问题描述为未经认证的远程 Root 执行。

## MS01 — 令牌轮换会改变权限范围

**证据：** [MCPServerExposureSection.kt:93](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/MCPServerExposureSection.kt:93) 将“生成”和“轮换”都交给 `createOrRotateManagedToken()`。用户在同页工具选择框通过 `updateManagedTokenScope()` 限定范围；但 [MCPServerManager.kt:136](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/mcp/server/MCPServerManager.kt:136) 每次都重新计算全部具体、MCP_ALLOWED 工具作为 safeScope，未读取旧令牌的 scope。

**触发：** 先把设置页令牌收窄到一个工具，再点击 Rotate。新令牌恢复全部默认直接允许工具；原来主动取消的工具重新进入授权范围。原来选中的 MCP_CONFIRM 工具则可能被移除。这个问题不依赖并发或异常配置。

**边界：** 中央权限表的 LOCAL_ONLY/MCP_DENIED 仍拒绝，新增范围也限于 MCP_ALLOWED；不是放开所有 Root 或任意工具。但中央“允许对 MCP 提供”的集合不等于用户给这枚令牌选择的集合。

**修复方向与端到端验收：** 首次生成可以设置安全默认；轮换只改变凭据，保留仍有效的用户 scope，移除失效项时明确反馈。保存轮换前后 scope、tools/list 和未选工具被拒绝的记录。现有 ExposureContractTest 只检查第二枚令牌值变化和 ID 不变，未检查 scope 保留。

## MS02 — 执行使用正文到达前的授权快照

**证据：** [MCPServer.kt:159](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/mcp/server/MCPServer.kt:159) 根据请求头查 TokenStore，保存 tokenRecord；之后读取完整正文再 dispatch。`:248` 的 gate 使用这份 tokenRecord.scope。执行器在 [ToolRegistry.kt:143](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/runtime/ToolRegistry.kt:143) 重查中央工具级别，但不查 TokenStore 当前凭据或当前 scope；[ToolPermissionManager.kt:209](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/runtime/ToolPermissionManager.kt:209) 只根据 `mcp:` 调用方类别选静态等级。

**触发：** 请求头已被接受、正文尚未到齐时，用户在设置中移除某个工具权限；随后正文到齐，旧范围仍可通过执行 gate。令牌轮换也不停止运行中的服务，因此旧 Bearer 已取得的请求快照可以继续进入 dispatch。若撤销某枚令牌但仍有其他令牌，Manager 也不会因这次撤销关闭整个服务。

**影响与限制：** 这里确认的是权限撤销后才进入执行的请求仍使用旧授权，不是要求倒带已经执行的操作。最后一枚令牌撤销会调用 stop，其取消行为另见 MS03；不据此断言所有取消中的 handler 都会继续执行。

**修复方向与端到端验收：** 保留必要的早期认证，再在 dispatch/执行前以当前凭据状态验证 token 身份及 scope，定义撤销与开始执行的明确边界。受控客户端分阶段发送请求，期间收窄 scope/轮换凭据，保存变更时间、拒绝结果和工具执行计数。需覆盖确认票据的重发路径。

## MS03 — 取消协程没有关闭阻塞请求 socket

**证据：** [MCPServer.kt:115](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/mcp/server/MCPServer.kt:115) 关闭监听 socket、取消 connectionJobs 并清空集合，未保存或关闭已接受的 client socket。请求读取使用同步 InputStream；client 只在 `handleConnection` 的 finally 中关闭。协程取消需要代码配合，不能自动终止任意阻塞 I/O。[Kotlin 取消说明](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/with-timeout.html)

**触发与影响：** 连接停在 request head/body 读取时关闭 MCP。UI/Manager 已显示停止，但该连接和 I/O 协程仍等待输入或 socket 的 30 秒单次读超时；持续有字节到达时，结束还可能推迟。已有字节上限不等于停止动作立即释放资源。重新创建服务也无法通过新实例回收旧实例的 socket。

**修复方向与端到端验收：** 服务实例拥有并主动关闭它接受的连接，关闭再等待处理退出；协程取消同样连接到真实 I/O 资源。保留停止前后的已接受连接、读取退出时间与重启连接结果。原有 ServerTest 测试 teardown 调用 stop，但没有观察挂起读取被立即终止。

## MS04 — 完成回调与注册连接 Job 存在先后竞态

**证据：** [MCPServer.kt:96](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/mcp/server/MCPServer.kt:96) 先 `scope.launch`，child finally 从集合 remove 变量 job，父代码在 launch 返回后才 add。child 可在 job 尚未赋值时结束；也可在 add 前已经执行 remove。两种时序都会随后把一个已完成 Job 加入集合，且没有第二次完成清理。

**影响：** 快速完成的请求，如 401/404，可能逐渐留下已结束 Job 的强引用，直到 stop 才清空。16 个连接 slot 只限制正在 handle 的连接，不能限制这个历史集合。不声称每条请求均泄漏或本轮测到了内存增长。

**修复方向与端到端验收：** 先登记再允许执行，或采用能够处理“登记时已经完成”的完成通知；避免依赖一个启动后才赋值的可变 job。用真实短请求持续连接，保存活动/已完成 Job 数及停止后的清理记录。

## MS05 — 首次恢复绑定失败后监督循环停在空轮询

**证据：** [MCPServerManager.kt:237](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/mcp/server/MCPServerManager.kt:237) 在 `running == false` 时直接 continue。服务原本运行、后来停止时，监督器会尝试重启；一旦 `startServerInstance()` 绑定失败，`:220` 将 running 设为 false。接下来的循环就永远跳过重试。

**触发与影响：** 首次恢复时端口暂时不可用，即便随后释放，也不会继续使用剩余恢复次数，只保留一个周期唤醒的 supervisor。所谓最多五次重启的 gate 没有保证实际状态机能走到第五次。

**修复方向与端到端验收：** 区分用户停止意图与一次启动失败，按有界恢复策略推进；放弃时终止监督任务并清楚更新状态。真实运行、模拟退出、一次端口冲突、解除冲突后观察后续恢复；保存绑定日志和最终状态。现有 ManagerTest 只测试 shouldRestart 的整数范围。

## MS06 — 通知分类与 HTTP 接收语义不一致

**证据：** [MCPCodec.kt:112](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/mcp/server/MCPCodec.kt:112) 按方法是否以 `notifications/` 开头判定，而 parser 没有保留“id 缺失”与“id 显式 null”的区别。Server 对 initialized 返回 null，之后 [MCPServer.kt:181](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/mcp/server/MCPServer.kt:181) 仍发送 HTTP 200 空 body。Streamable HTTP 接受通知时应返回 202、无 body。[MCP 传输规范](https://modelcontextprotocol.io/specification/2025-06-18/basic/transports#sending-messages-to-the-server)

JSON-RPC 的通知由 id 缺失决定，不依赖方法名；当前无 id 的 ping 会得到带 null ID 的 response，其他 `notifications/` 方法即使包含请求 ID 也被直接丢弃。[JSON-RPC 通知定义](https://www.jsonrpc.org/specification#notification)

**影响与边界：** 严格客户端可以因 initialized 的 HTTP 状态失败；前一轮的原生客户端恰好接受空 200，不能证明对外协议正确。本项不涉及绕过认证，认证仍先执行。

**修复方向与端到端验收：** 保留消息形态，严格区分通知、请求和畸形输入，按传输合同发送状态及回复。保存真实客户端的 initialize/initialized/ping 收发帧，检查通知没有 JSON-RPC 回复。

## OA01 — state 可以缺省，错误回调也先终止监听

**证据：** [MCPOAuthController.kt:84](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/mcp/oauth/MCPOAuthController.kt:84) 创建 callback server 时未传 expectedPath/expectedState；`:111` 仅在 state 非 null 时检查相等。授权 URL 总是带随机 state。按 OAuth 合同，响应须回传请求的原 state。[RFC 6749 §4.1.2](https://www.rfc-editor.org/rfc/rfc6749.html#section-4.1.2)

回调 server 一旦看到 code，就调用 onCode 并 stop；controller 到 finally 后才校验 state。因此缺 state 的输入可进入 token exchange，带错误 state 的输入则先关闭监听、再返回 Failed，无法继续等真正正确的回调。任意路径上的 code 同样能结束等待。

**影响与限制：** 请求绑定检查不完整，错误回调能提前结束一次登录。PKCE verifier/challenge 仍存在，不据此声称任意伪造 code 可以换到 token 或已经发生账户接管。监听已绑定 loopback，不是 LAN 公开服务。

**修复方向与端到端验收：** 使用已有 callback server 的 expectedPath/expectedState 能力，在消费回调和关闭监听前要求精确匹配；缺失或不匹配拒绝并继续等有效回调。保存错误回调被拒绝、没有 exchange、随后正确回调成功的时间线。其他调用方 OpenAIOAuthManager 已传入这两个参数，但这里只核对了相关片段，未算作全文覆盖。

## OA02 — 授权失败终态不能结束等待

**证据：** [MCPOAuthController.kt:82](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/mcp/oauth/MCPOAuthController.kt:82) 的回调等待没有 withTimeout 等五分钟预算；`:90` 的 onExternalCancel 只在某处调用 srv.stop 后才触发，不会自己感知浏览器退出。OAuthCallbackServer 仅向调用方报告 code；有 `error=access_denied` 而无 code 时，返回“Authorization complete”页面，却继续监听。绑定失败则在 [OAuthCallbackServer.kt:65](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/auth/OAuthCallbackServer.kt:65) 记录日志后结束线程，没有向挂起的 continuation 报错。

**触发与影响：** 用户在授权页拒绝、关闭 Custom Tab，或者端口已经被占用，controller 可以一直挂起。设置页的 oauthBusy 只在 authorize 返回后清除，不能在该等待中再次授权或退出登录。离开编辑界面造成协程取消可以清理，不能当作五分钟超时已实现。

还有一条明确异常路径：配置 tokenEndpoint 为非空但不可构造成 URL 的字符串，Request.Builder.url 位于 exchangeCode 的 try 外；异常能逃出所谓“Never throws”的 authorize。界面 launch 也没有 finally 清掉 busy。这属于同一授权流程的终态收口缺失。

**修复方向与端到端验收：** 连接启动结果、OAuth error、用户取消和整体 deadline 均须返回明确终态，界面 finally 恢复操作状态。分别保存拒绝、端口占用、无回调、无效端点后的结束状态、监听关闭和再授权结果。当前两个 OAuth 测试文件只验证 PKCE/config 和 form body，没有覆盖完整登录生命周期。

## OA03 — Query 解码顺序破坏授权码

**证据：** [OAuthCallbackServer.kt:120](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/auth/OAuthCallbackServer.kt:120) 使用 `URI.query`，再 split('&')，最后对值 URLDecoder.decode。URI.getQuery 已解码百分号序列；应与不解码的 rawQuery 区分。[Android URI API](https://developer.android.com/reference/java/net/URI#getQuery())

**静态示意：** 原始 `code=a%2Bb` 在 getQuery 后变为 `code=a+b`，再次 form 解码得到 `a b`；原始 `code=a%26b` 在拆分前变成 `code=a&b`，只得到 `a`。百分号本身也可能二次解码或报错。这里使用示意值，不是运行 OAuth 服务得到的实测授权码。

**影响：** 使用这些合法编码字符的服务会看到错误 code，token exchange 失败；纯字母数字 code 不受这些例子影响。该 helper 被多个 OAuth manager 共用，需要核对全部调用方。

**修复方向与端到端验收：** 从原始 query 分离键值，再按 form 编码规则各解码一次。通过完整授权服务验证包含 +、&、% 的 code，保存匿名化的原始回调、解析值及 token endpoint 收到的值，确保一一对应。

## OA04 — 原始回调凭据进入 logcat 和文件日志

**证据：** [OAuthCallbackServer.kt:80](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/auth/OAuthCallbackServer.kt:80) 将完整 requestLine 交给 Log.d，其中通常包含 code/state。文件日志开启时，[LogcatTailer.kt:66](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/logging/LogcatTailer.kt:66) 将该行直接交给 sink，[AppLogger.kt:192](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/logging/AppLogger.kt:192) 原样写到日志文件，未经过 OAuthManager.sanitizeBody。

**影响与限制：** 登录回调中的授权码和 state 可以进入诊断及导出日志。授权码短期、单次使用且本流程有 PKCE，不能把日志副本等同于已获得长期账户权限。现有 OAuthLogRedactionTest 只验证某个 JSON body 脱敏函数，并不覆盖这个直接 Log.d 入口。此项与旧 F32 的工具 intent/log 副本不同。

**修复方向与端到端验收：** 在最初记录处去掉 code/state，只记录回调路径、字段存在与验证结果。用无效 canary code/state 走成功与拒绝回调，保存 logcat、文件日志和导出包的搜索结果，不保存真实凭据。

## OA05 — 取消后 token exchange 仍可保存凭据

**证据：** [MCPOAuthController.kt:142](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/mcp/oauth/MCPOAuthController.kt:142) 同步执行 OkHttp Call，没有取消回调或活动调用跟踪；body 到达后直接 setTokens，没有检查当前 coroutine 或本次授权是否仍有效。callback 阶段的 cancellation handler 只关闭 callback server，不能取消之后的 HTTP exchange。

**触发与影响：** 已得到 code，token endpoint 延迟回答；用户关闭编辑界面，rememberCoroutineScope 被取消。IO 块里的同步调用仍可继续，在返回取消结果之前保存晚到 token。重新打开时可能显示已授权，尽管用户以为取消了流程。已有 connect/read timeout 限制部分等待，不等于取消传播。

**修复方向与端到端验收：** 将取消传到 Call，保存前确认本次授权仍有效，定义清晰的完成/取消提交边界。保存延迟响应时关闭页面的请求取消、token store 前后状态及重新打开结果；避免用一个新全局忙闲标志代替请求身份。

## OA06 — Redirect URI 与真正监听端点不一致

**证据：** [MCPOAuthController.kt:61](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/mcp/oauth/MCPOAuthController.kt:61) 只检查 host 是 localhost/127.0.0.1，未检查 scheme。没有显式端口时用 54546 启动监听，但仍将原始 redirect URI 原样发给授权及 token endpoint。

**触发：** `http://127.0.0.1/callback` 通过校验，浏览器请求默认 HTTP 80，App 却监听 54546；`https://127.0.0.1:54546/callback` 也通过校验，但 listener 是明文 HTTP，没有 TLS。两者都不可能完成预期回调，即使 OAuth 服务接受该已注册 URI。

**边界：** 默认 redirect URI 有显式端口和 HTTP scheme，未落入这些例子。IPv4/IPv6 localhost 兼容还需要设备证据，本轮不推断 getLoopbackAddress 在所有 Android 设备上的选择。

**修复方向与端到端验收：** 明确支持的 redirect 形态，发给服务的完整 URI 与实际监听协议、地址、有效端口和路径保持一致；不能静默换成另一个未注册 URI。保存非法/不支持 URI 的可见拒绝与标准配置的完整回调结果。

## OA07 — 回调入口使用无界 readLine

**证据：** [OAuthCallbackServer.kt:78](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/auth/OAuthCallbackServer.kt:78) 对 request line 使用 BufferedReader.readLine；OPTIONS 分支的每条 header 也这样读取，且不限制总行数或字节数。10 秒 socket timeout 是单次读超时，持续输入不能用它替代长度边界。stop 只关闭 listening socket，没有跟踪当前接受的 socket。

**影响与限制：** 只在授权监听期间可达的 loopback 输入，可以使该单线程读取较大字符串，或持续占据处理通道，妨碍真正回调。内存峰值和设备实际表现未测量；不宣称 LAN peer 可直接访问。中央 MCP server 已使用 BoundedHttpRequestReader，不能把其上限自动算到这个独立 OAuth listener 上。

**修复方向与端到端验收：** 优先复用项目已有有界 HTTP 读取能力，确定合理的回调请求限额、总时长及取消资源边界。验证超限、慢输入、随后正常回调和取消，保存拒绝响应与连接退出时间。禁止用“读完再 take”冒充读取前的上限。

## OpenMinis 对照与保留的正确边界

对照版本仍为本地固定 git 对象 `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，未 fetch 或执行上游代码。

- 固定上游 Android 树没有本项目这套 MCPServer/TokenStore/ConfirmQueue。MS 系列属于本地新增能力必须自行完成的边界，不能假定上游已证明正确。
- 上游 MCPOAuthController 也没有给共享 callback server 传 expected state/path，检查 state 的条件也允许 null；其等待、同步 exchange 与 redirect 处理有相同缺口。不能照搬上游当作 OA 系列已修。
- 上游 [OAuthCallbackServer.kt:104](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/auth/OAuthCallbackServer.kt#L104) 使用相同 query 解码顺序，也记录 requestLine。这里属于双方共有问题。
- 上游 [MCPOAuthController.kt:122](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/mcp/oauth/MCPOAuthController.kt#L122) 增加了前台等待，本地没有。本轮没有设备网络证据，不把上游某设备的 APP_BACKGROUND/DNS 现象写成所有 Android 的规则或本地已复现缺陷；保留为后续设备核对项。

当前正确保护包括：Server 固定 loopback、每个请求早期 Bearer 认证、无令牌拒绝启动、中央工具级别过滤、空 managed scope 拒绝、caller+method+完整参数摘要绑定的单次确认票据、确认 TTL、非导出通知 receiver、有界中央 HTTP 读取。Token scope 变更问题不等于这些保护都失效。OAuth 有随机 state、S256 PKCE，秘密存储也没有明文 fallback；本报告没有要求恢复旧 guest OAuth bridge 或 PRoot。

此前 F31 的 OAuth 凭据没有进入 MCP client 仍见[原报告](/home/jiale/projects/minis-for-android/docs/archive/2026-10-04-project-audit.md:586)。本轮 OA 系列发生在授权回调和交换本身，是独立问题；HTTP client 关闭问题 MC05 则见[上一轮报告](/home/jiale/projects/minis-for-android/docs/archive/2026-10-05-mcp-client-transport-audit.md)。

## 全文覆盖记录

新增全文阅读 **25 个文件、3,761 行**。累计 **248 / 1546 个纳入文件、49,464 行**；15 个 partial、1283 个 not_registered，全仓审阅仍未完成。

- 生产文件 16 个：mcp/server 下全部 6 个；mcp/oauth 下全部 4 个；OAuthCallbackServer.kt、McpConfirmNotifier.kt、EncryptedPrefsFactory.kt、MCPServerExposureSection.kt、BoundedHttp.kt、ToolPermissionManager.kt。
- 测试源码 9 个：mcp/server 下全部 6 个；mcp/oauth 下全部 2 个；OAuthLogRedactionTest.kt。

逐文件 SHA-256 和状态见[覆盖台账](/home/jiale/projects/minis-for-android/docs/archive/2026-10-05-code-review-coverage.json)。关联执行器、日志、编辑页、Manifest、debug 入口和其他 OAuth manager 只重读了相关片段，不扩大其全文覆盖；未执行任何测试源码。
