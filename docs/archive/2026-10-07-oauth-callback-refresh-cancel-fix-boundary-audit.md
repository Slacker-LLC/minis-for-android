# OAuth回调、刷新及取消修复边界静态复核

日期：2026-10-07。固定远端HEAD `37158d188a190416f2b16b45dfb112ecf53cdd7b`。新增全文8文件、1287行。只复核旧OA/PA问题，不新增重复编号；未改产品代码、编写/执行测试、构建、启动监听/浏览器/模拟器、adb、Root、设备或云任务，未使用账号或凭据。此前161份报告和全部旧台账保留。

## 回调修复状态

| 旧项 | 当前结论 |
|---|---|
| OA01 MCP缺state/path校验 | controller现向listener传精确state/path，并在返回后再次校验 |
| OA02 授权终态不结束等待 | MCP有5分钟等待；拒绝与端口全部占用可触发取消；并非所有异常统一终态 |
| OA03 query二次解码 | 改rawQuery，先拆键值再一次form decode，原反例已修复 |
| OA04 原始code/state日志 | 请求行改用去query摘要，原直接日志入口已修复 |
| OA05 取消后写token | MCP保存前ensureActive，提交保护已改善；完整HTTP读取取消未证明 |
| OA06 redirect与listener不一致 | HTTP、loopback、显式port、path已校验，原https/无port反例被拒绝 |
| OA07 无界readLine/停止资源 | 行长/OPTIONS头数有界；已接入socket仍未由stop主动关闭 |

不是整个OAuth系统或实机登录验收。各Provider子类尚未在本轮重新全文核对，基类结论不扩张成所有Provider修复。

## 正确回调与明确终态

[OAuthCallbackServer.kt:40](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/auth/OAuthCallbackServer.kt#L40) parseQuery从原始百分号query拆分，再解码每个key/value；[OAuthCallbackServer.kt:174](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/auth/OAuthCallbackServer.kt#L174) 调用uri.rawQuery。带编码加号、&、%的原OA03解码顺序错误已改变，重复key保留首项。无效percent项跳过而其它合法项保留。

[OAuthCallbackServer.kt:128](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/auth/OAuthCallbackServer.kt#L128) 请求日志只使用loggableRequestLine去query；完整code/state不再直接写入这条日志。测试canary验证正常GET、OPTIONS、超长输入不会含query秘密。本结论不覆盖所有异常堆栈或OAuth错误参数文字，也不将有限JSON字段脱敏当任意自由文本识别。

MCP controller配置expectedState/Path，错误/缺state不会消费code或关闭监听。error=access_denied且state正确会stop并触发onExternalCancel；所有候选端口BindException失败也stop。5分钟withTimeout覆盖回调等待，而非整个后续token exchange。

controller构造authUrl在wait try之外，CustomTab启动错误也不在统一Failed catch内；“Never throws”注释不是所有错误终态保证。已有OA02异常收口类残余继续记录，不另编号。redirectProblem只要求port>0，没有65535上限；超过上限的bind异常进入listener外层catch而非all-ports stop，因此仍可能等到总超时。默认端口不触发此例。

## 读取与停止的残余

readBoundedLine逐字符限制8192字符，OPTIONS最多64行；超限抛IOException，不是读完再take。测试用100000字符StringReader和真实listener超长输入，验证后续正确回调仍可处理；本轮只读测试，未创建socket。

[OAuthCallbackServer.kt:218](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/auth/OAuthCallbackServer.kt#L218) stop只关监听serverSocket，已接受socket是循环局部变量，读行期间不能被stop直接关闭。30秒/10秒单次timeout与总deadline都不代表取消即释放已接入阻塞IO，旧OA07的停止资源边界仍未收口。

start在线程内异步bind，stop与bind发布没有共同锁；boundPort在绑定成功前仍是初始port。旧PA02 fallback端口时序不能凭这次readLine修复宣布解决。具体Provider是否读取fallback及实际错误路径需下一轮核对。

OPTIONS的trustedHosts仍用origin.contains匹配xAI域名，不是URI精确host比较。这个事实本轮只作待核对语义，未把CORS文本匹配直接升级为账号接管或LAN开放；listener仍loopback，state和PKCE仍生效。

## MCP exchange取消的实际保护

[MCPOAuthController.kt:154](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/mcp/oauth/MCPOAuthController.kt#L154) Request.Builder放进try，无效token endpoint现在返回Failed。executeCancellable使用enqueue，等待响应头期间cancel会call.cancel；收到response后继续同步body.string，那个suspend continuation已完成，不证明body阶段取消立即传到call。响应body需要自己的资源边界，不能只凭函数名宣称完整取消。

[MCPOAuthController.kt:169](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/mcp/oauth/MCPOAuthController.kt#L169) 保存前ensureActive能阻止已经取消的协程把晚到token写入，这修复原OA05明确的取消先发生、响应后到再无条件保存路径。ensureActive与setTokens仍是两个步骤，不能据此证明取消与提交原子；本轮没有做调度或端点延迟实验。

HTTP正文没有在此设字节预算，返回失败正文前200字符只限制最终消息，不限制body.string临时读取。与已有网络正文预算问题属于同类边界，未作为本轮新编号。

## 基类刷新修复与PA07残余

OAuthManager456行完整读取：手工bearer优先、加密存储、绝对expiry导入保留、camel字段映射、PKCE/state、refresh与validAccessToken。base startLogin仍缺state时放行并GlobalScope exchange，但src中调用搜索仅找到它的定义；不把未确认生产调用入口写成现役新增绕过。

[OAuthRefreshPolicy.kt:37](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/auth/OAuthRefreshPolicy.kt#L37) refreshLead为四分之一生命周期，5分钟—4小时夹限；一小时token变成提前15分钟，原PA04每次刷新反例已修复。临时refresh失败不logout，只有明确revoked且access已过期才清凭据，基类原PA05暂时故障删除路径已改善。Policy只按明确error/type/code和400—403判定，不搜索正文refresh_token字样；Provider子类接线仅搜索定位，未完整复核PA06所有分支。

[OAuthManager.kt:264](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/auth/OAuthManager.kt#L264) 基类refresh成功要求非空access_token，200空JSON不会覆盖旧bundle，原PA08基类分支已修复。是否所有子类也检查需分别阅读。

[OAuthManager.kt:274](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/auth/OAuthManager.kt#L274) 保存前storedRefreshTokenIs读当前存储；能挡住logout先完成、响应后到的旧路径。但这不是原子compare-and-swap：检查true之后、saveTokens之前可以发生logout，然后旧refresh再次写入；logout本身与此检查没有共同锁/版本。旧PA07部分修复，不能把注释“CAS guard”当原子提交证明。失败分支lastRefreshRevoked与validAccessToken的logout也未携带本次旧bundle身份，期间换账号是否影响新凭据还需闭环。记录原问题残余，不新编号。

## 已有测试范围与上游

CallbackServerTest159行包含真实loopback监听用例（编码code、错误state、拒绝、端口占用、超长请求）；本轮未执行任何监听测试。它不覆盖stop时慢请求socket或stop-before-bind窗口。CallbackLogLine30、RefreshPolicy58、MCPRedirect31行是纯helper验证，不测试整体取消提交、基类refresh并发或所有Provider成功登录。

固定OpenMinisHEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，GIT_NO_LAZY_FETCH=1，只读已存在CallbackServer的OPTIONS片段；上游同样origin.contains并readLine，没有借此证明上游更安全或本地所有问题来自移植。未fetch、未访问授权端点。

## 全文覆盖

| 本轮新增全文 | 行数 |
|---|---:|
| [OAuthCallbackServer.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/auth/OAuthCallbackServer.kt#L1) | 236 |
| [OAuthManager.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/auth/OAuthManager.kt#L1) | 456 |
| [OAuthRefreshPolicy.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/auth/OAuthRefreshPolicy.kt#L1) | 42 |
| [MCPOAuthController.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/mcp/oauth/MCPOAuthController.kt#L1) | 275 |
| [OAuthCallbackLogLineTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/test/java/com/openminis/app/auth/OAuthCallbackLogLineTest.kt#L1) | 30 |
| [OAuthCallbackServerTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/test/java/com/openminis/app/auth/OAuthCallbackServerTest.kt#L1) | 159 |
| [OAuthRefreshPolicyTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/test/java/com/openminis/app/auth/OAuthRefreshPolicyTest.kt#L1) | 58 |
| [MCPOAuthRedirectTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/test/java/com/openminis/app/mcp/oauth/MCPOAuthRedirectTest.kt#L1) | 31 |

Manager分1—225/226—456，MCP controller分1—150/151—275，CallbackServer/Policy及四测试全文读取无截断。旧报告定位搜索曾截断，不作全文证据；Provider子类仅搜索，不升级覆盖。

新台账：[2026-10-07-code-review-coverage-37158d18-oauth-callback.json](/home/jiale/projects/minis-for-android/docs/archive/2026-10-07-code-review-coverage-37158d18-oauth-callback.json)。累计全文 **1261 / 1652文件、206360行**；partial4、not_registered387、excluded124，tracked1776。全库通读未完成。

交付校验此前161份报告与所有旧台账hash、固定本地/远端/上游HEAD、tracked/staged空diff、1776快照blob/hash/bytes/lines及证据行号。只新建本文和本轮台账，旧文件留给Claude。
