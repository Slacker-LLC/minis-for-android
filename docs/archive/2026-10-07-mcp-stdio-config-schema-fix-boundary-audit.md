# MCP客户端 STDIO、连接配置与schema修复边界

日期：2026-10-07。固定远端HEAD `37158d188a190416f2b16b45dfb112ecf53cdd7b`。新增全文6文件、939行。没有新增缺陷编号，复核旧MC01/02/03/07及F26/29/30/31的当前实现。仅静态审查，没有修改代码、编写/执行测试、构建、启动MCP进程或模拟器、adb、设备、Root、云任务或访问账号。旧159份报告及全部旧台账保留。

## 原STDIO握手和串行帧边界已有修复

[MCPStdioTransport.kt:111](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/mcp/client/MCPStdioTransport.kt#L111) 写完通知后检查id，通知直接返回空JSONObject，不启动读取线程。旧MC01“不回应initialized导致握手等待”反例已修复。正常请求在[MCPStdioTransport.kt:94](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/mcp/client/MCPStdioTransport.kt#L94) requestLock中执行exchange，锁覆盖写入及响应读取；只接受相同id且带result/error的帧，其余服务通知/请求和其它id被跳过。旧MC02共享reader交叉读帧及把通知当结果的路径已经修复。

MCPStdioFramingTest完整84行，以脚本服务发送通知、错误id、正确结果，分别检查通知不读、匹配id、8个并发send。源码调用真实transport，但本轮未运行脚本或测试。并发用例由测试自己提供不同id，不验证MCPClientSession的id分配。

[MCPClientSession.kt:140](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/mcp/client/MCPClientSession.kt#L140) session仍nextId++，字段第29行没有原子或锁保护；STDIO帧锁是在构造request之后进入。不能以transport串行化宣称整个session并发id已修复。该残余沿用旧MC02边界，不另计，也不声称串行STDIO一定拿错结果。

## MC03和MC07仍在

[MCPStdioTransport.kt:66](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/mcp/client/MCPStdioTransport.kt#L66) stderr过长异常被catch后skipToEndOfLine并返回null，随后?:break结束整个循环。限制一条诊断日志仍会关闭后续stderr读取，旧MC03保持。stdout过长则关闭整个transport，二者语义应区分。现有framing测试没有超长stderr案例。

[MCPToolHandler.kt:39](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/tools/runtime/MCPToolHandler.kt#L39) handler构造AgentToolDefinition未传required，schemaToParams仅保留type/description和字符串enum，不映射items、properties、nested required。旧MC07远端必填/嵌套参数合同丢失仍在；不能把ClientCodec已保留inputSchema等同最终模型收到它。结果spill/prune是在调用后处理文本，不能恢复请求schema丢失。

handler每次execute先检查该configId在session的开关，避免旧工具列表绕过关闭状态；取消异常向上传播，transport异常映射为failureKind。非JSON参数回落空对象，是否被共同validator提前拒绝需结合实际调用闭环，本轮不另报必定发送畸形请求。

## 环境引用和OAuth接线：缺失入口已补齐，长连接更新仍需区分

MCPConnectionConfig.expand支持url/header值/command/args/env值中的双美元引用，缺变量明确拒绝，单美元保留。[MCPProvider.kt:144](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/mcp/client/MCPProvider.kt#L144) connectOne调用展开值，并从App上下文取得OAuth token，再构造session。旧F30完全不展开环境引用和F31完全不注入token的原路径已改变。

resolveToken在到期前60秒刷新，保留或轮换refresh token；无expiresAt则直接用，刷新失败拒绝连接并提示重新登录。MCPConnectionConfigTest107行读取生产expand/resolveToken，验证引用、缺变量、鲜活token、refresh保存与失败；MockWebServer未运行，没有真实账号/网络请求。

刷新只发生在建立连接时，不是每次callTool；已连接session持有当时bearer token，后续失效的恢复不能由这里证明。并且connectOne将resolved给session、却把原cfg给accessToken，resource派生仍基于原url，带变量URL与OAuth组合需后续核验。当前不凭未验证服务要求另增缺陷。

刷新HTTP用同步execute/body.string；Provider的协程45秒加载预算不等同可随取消立即中断该同步调用。实际读取20秒socket超时、连续响应与重载取消的行为需明确区分。日志只记录server id和错误message，正常路径不写回展开配置；不能因此泛称所有异常信息永远不包含敏感值。

## F29仍是宿主进程；Provider重载与会话开关的现状

[MCPStdioTransport.kt:48](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/mcp/client/MCPStdioTransport.kt#L48) 仍直接ProcessBuilder(command+args)，不是ExecutionCoordinator/Ubuntu session。因此旧F29“本地MCP命令位于Android宿主而非guest”的架构事实仍在。没有把标准Android平台桥接工具全部归类为必须搬迁，也没有重新引入minisd/PRoot。用户希望开发工具由沙箱Agent管理，这条实际调用链应保留为待处理证据。

start只检查process非null，取消请求close销毁整个共享server；其它会话随后调用会失败，Provider.connected状态只在连接/重载更新。共享连接的取消影响范围需要独立产品语义核对，未强行新编号。close先destroy进程再关reader，避免先关被阻塞reader而永远无法到destroy；对子孙进程或持有管道的进程，没有以直接Process销毁宣布完整进程组回收。

Provider300行完整阅读：reloadGeneration、registryLock、取消旧reload、四并发/每连接45秒预算；旧generation不提交新连接，失败关闭session。工具重名由ToolRegistry.register拒绝，status含skipped数；新增连接异常回滚该批注册。不是“所有远端tool无条件覆盖旧handler”。

[MCPProvider.kt:228](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/mcp/client/MCPProvider.kt#L228) 按configId查询session开关；offeredInSession投影工具列表，handler执行时再检查。因此旧F26“开关没有执行门”原入口已改善。仅全球enabled服务参与连接；session override能否启动全球未连接服务需repository语义进一步核对，不推导所有override都保证可用。

sanitizeId对替换过的id追加原id的短digest，改变了简单空格/下划线碰撞；远端tool的wire-name冲突又由registry拒绝。没有凭24bit摘要宣称数学上无任何碰撞，也不以理论碰撞增报实际故障。

## 固定OpenMinis对照

上游HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，GIT_NO_LAZY_FETCH=1，只读已有daemon.py第116—183行。其handshake发送initialized为notify=True，list/call共享_lock，_read_reply匹配id，与本地修复采用相同的基本边界。上游代码位于旧guest资产树，本地当前为Android原生client；不能直接把整套旧资产恢复来处理F29，也不能据此宣称本地stderr、schema或OAuth已全部修好。

## 全文覆盖与完整性

| 本轮新增全文 | 行数 |
|---|---:|
| [MCPConnectionConfig.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/mcp/client/MCPConnectionConfig.kt#L1) | 116 |
| [MCPProvider.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/mcp/client/MCPProvider.kt#L1) | 300 |
| [MCPStdioTransport.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/mcp/client/MCPStdioTransport.kt#L1) | 202 |
| [MCPToolHandler.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/tools/runtime/MCPToolHandler.kt#L1) | 130 |
| [MCPConnectionConfigTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/test/java/com/openminis/app/mcp/client/MCPConnectionConfigTest.kt#L1) | 107 |
| [MCPStdioFramingTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/test/java/com/openminis/app/mcp/client/MCPStdioFramingTest.kt#L1) | 84 |

Provider分1—170/171—300，其余五文件完整读取，输出无截断。Session、Repository、合同及上游仅相关片段，不升级全文覆盖。

新台账：[2026-10-07-code-review-coverage-37158d18-mcp-client.json](/home/jiale/projects/minis-for-android/docs/archive/2026-10-07-code-review-coverage-37158d18-mcp-client.json)。累计全文 **1248 / 1652文件、204062行**；partial4、not_registered400、excluded124，tracked1776。全库通读仍未完成。

交付校验此前159份报告与全部旧台账hash、固定本地/远端/上游HEAD、tracked/staged空diff、1776快照blob/hash/bytes/lines及证据行号。只新建本报告和新台账，旧文件交给Claude处理。
