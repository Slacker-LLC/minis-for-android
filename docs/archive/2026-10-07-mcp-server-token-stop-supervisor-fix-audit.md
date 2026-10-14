# MCP服务端令牌、停止与监督恢复修复复核

日期：2026-10-07。固定远端HEAD `37158d188a190416f2b16b45dfb112ecf53cdd7b`。本轮新增全文5文件、1011行；不新增重复缺陷编号，复核旧MS01—MS06。仅静态阅读，没有修改产品代码、创建/运行测试、启动服务器/端口/模拟器、构建、adb、Root、设备或云任务。此前160份报告与旧台账全部保留。

## 修复状态与边界

| 旧项 | 当前结论 |
|---|---|
| MS01 轮换丢失用户scope | 保留仍有效旧scope；全部已失效时仍回落默认，需区分 |
| MS02 正文前授权快照 | handleToolCall入口已重新读取当前令牌/scope，原慢body反例被挡住 |
| MS03 停止未关已接入socket | 仍存在 |
| MS04 connection Job先执行后登记 | 仍存在 |
| MS05 绑定失败后不继续重试 | 仍存在 |
| MS06 通知分类/HTTP状态 | 仍按method前缀分类，通知仍返回空200 |

这些是源码证据，不是实际设备或第三方MCP客户端验收。

## 令牌修复的实际位置

[MCPServerManager.kt:147](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/mcp/server/MCPServerManager.kt#L147) createOrRotateManagedToken调用rotatedScope，旧scope与仍可提供工具取交集，有剩余就保留。原“只选一个工具、轮换却变回全默认”反例已修复，用户选定CONFIRM工具也保留。[MCPServerManager.kt:167](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/mcp/server/MCPServerManager.kt#L167) 当全部旧工具失效、空scope或首次生成时返回默认。测试明确包含这个回落，因此不能泛称轮换绝不增加工具；此残余属于旧MS01产品范围语义，不新编号。

[MCPServer.kt:235](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/mcp/server/MCPServer.kt#L235) handleToolCall从TokenStore按id重新取记录并比对secret，再用当前scope；已撤销或已轮换会返回permission_denied。早期头部认证仍保留，后补这一步关闭旧MS02正文延迟期间撤销窗口。

MCPServerLiveTokenTest46行读取真实handleToolCall，在接受头部后的快照与当前store不一致时验证撤销、轮换、缩scope三种拒绝。测试传null Context，确保拒绝在执行前，但未发送真实分阶段HTTP请求，也不验证实际工具次数；本轮未运行。

该检查之后仍可能进入UbuntuRuntime.ensureReady等挂起阶段，第300行附近才消费确认、后续才执行。后续权限变化不会由本次入口检查再次读取；不要把一次重查写成执行之前任何时间撤销都绝对即时生效。本轮限定修复原慢body反例，不扩张成无竞态证明。

## MS03、MS04、MS05仍未改变

[MCPServer.kt:121](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/mcp/server/MCPServer.kt#L121) stop关闭listener、取消/清connectionJobs，没有持有并关闭已accept的client socket。BoundedHttpRequestReader是同步读取，client只在handleConnection finally关闭。用户停止时阻塞读仍可继续至输入/超时，旧MS03保持；有字节上限和30秒socket timeout不等于stop主动终止读取。未做真实socket复现。

[MCPServer.kt:102](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/mcp/server/MCPServer.kt#L102) 仍先scope.launch，再把返回job加入connectionJobs；child finally使用启动后才赋值的可变job移除。快速结束可以发生在赋值或add之前，随后把已完成Job留在集合，旧MS04保持。connection cap为16，但不限制这类已完成登记对象总量。

[MCPServerManager.kt:246](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/mcp/server/MCPServerManager.kt#L246) supervisor看到server不再isRunning时尝试恢复；一次startServerInstance绑定失败会将manager.running=false，后续if(!running)continue使剩余重试不再推进。只测试shouldRestart 0—4允许、5拒绝不验证状态机能进入下一次重试。旧MS05保持。服务稳定活过一个轮询后consecutiveRestarts归零，是当前策略，不将每三秒归零误报成所有崩溃都无限循环。

Manager的start/stop未共用锁，MCPServer自身start/stop是实例锁；并发实例发布与停止需后续真实调用来源核验，本轮不另报未闭环竞态。

## 确认票据的已有保护与范围

ConfirmQueue192行全文：synchronized issue/consume/approve/reject/sweep；issue先sweep到期项，capacity64限定pending；used/rejected保留到TTL以便拒绝重放。caller、method和完整规范JSON摘要绑定票据，object key排序、array保留顺序；未批准返回PENDING，正确批准只能成功消费一次。token轮换沿用id不会使旧票据自动执行：工具入口还要用当前secret认证。

票据容量限制针对pending，used/rejected数量是TTL窗口中已消费/拒绝的量，不是固定64总数；是否需要额外总预算留作资源候选。clock主要由默认系统时钟驱动，不把测试注入clock与方法默认now差异描述成现役所有票据马上过期。

Server先canonicalize工具别名，再进行token scope、confirm、execute。summary递归隐藏匹配的token/password/secret等字段名；这不是对任意自由文本中的密钥做通用识别，不能据此声明所有确认文案都绝无敏感值。此次只记录已实现边界，不扩大脱敏承诺。

ConfirmQueue.shared由构造器更新；stop未清它。是否旧通知在新服务实例下误路由需要按通知/重启生命周期进一步核对；不凭一个静态全局引用强行报已批准越权。旧票据随机UUID，在新queue找不到也不会直接OK。

## MS06及接口边界

[MCPServer.kt:181](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/mcp/server/MCPServer.kt#L181) response为空时仍sendHttp200空body；dispatch未知方法按MCPCodec.isNotification(method)决定不回复。Codec第114行仍仅检查notifications/前缀，旧MS06形态/HTTP状态问题保持。没有重新查询协议网页或运行客户端，沿用旧报告的规范证据；本轮只是验证相关源码仍未变。

Server固定loopback127.0.0.1；每请求有Bearer认证、Central tools权限和scope检查；无token时manager拒绝启动。ConnectionSlots16以及BoundedHttp的长度/UTF-8限制已使用，不能将停止清理缺口说成没有任何输入预算或远程LAN开放。

服务所有工具执行仍使用sessionId=mcp，不随token隔离workspace；本轮没有把已明确的共享服务会话语义认定为新增安全问题。tool execution是suspend调用，未额外造第二套agent loop。

## 测试与OpenMinis对照

ManagerTest93行仅验证fail-closed、空实例stop/status、shouldRestart数值门以及rotatedScope纯函数。没有真实Context、监听端口、活跃socket、监督恢复或scope变化后tools/list；不能将测试名称当实机证据。测试只阅读、未执行。

固定上游HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，GIT_NO_LAZY_FETCH=1，现有Git树没有本轮mcp/server三模块同路径。未fetch、未恢复旧guest/broker能力，无法提供同实现生命周期对照，也不推导上游完全没有MCP服务端能力。

## 全文覆盖

| 本轮新增全文 | 行数 |
|---|---:|
| [ConfirmQueue.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/mcp/server/ConfirmQueue.kt#L1) | 192 |
| [MCPServer.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/mcp/server/MCPServer.kt#L1) | 389 |
| [MCPServerManager.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/mcp/server/MCPServerManager.kt#L1) | 291 |
| [MCPServerLiveTokenTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/test/java/com/openminis/app/mcp/server/MCPServerLiveTokenTest.kt#L1) | 46 |
| [MCPServerManagerTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/test/java/com/openminis/app/mcp/server/MCPServerManagerTest.kt#L1) | 93 |

Server分1—190/191—389，Manager分1—170/171—291，其余三文件全文读取，无截断。Codec仅定位相关字段，不升级全文。

新台账：[2026-10-07-code-review-coverage-37158d18-mcp-server.json](/home/jiale/projects/minis-for-android/docs/archive/2026-10-07-code-review-coverage-37158d18-mcp-server.json)。累计全文 **1253 / 1652文件、205073行**；partial4、not_registered395、excluded124，tracked1776。全库通读仍未完成。

交付校验旧160份报告和所有旧台账hash、固定本地/远端/上游HEAD、tracked/staged空diff、1776快照blob/hash/bytes/lines及证据行号。只新建本报告和本轮台账。
