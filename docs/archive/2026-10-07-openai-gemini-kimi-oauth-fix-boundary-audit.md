# OpenAI、Gemini与Kimi OAuth具体调用复核

日期：2026-10-07。固定远端HEAD `37158d188a190416f2b16b45dfb112ecf53cdd7b`。本轮完整读取3文件、931行，不新增重复编号。没有修改代码、编写/执行测试、构建、启动浏览器/OAuth端点/监听/模拟器、adb、Root、设备或云任务，也未读取凭据。此前162份报告和全部旧台账保留，另建本报告。

## OpenAI：共享回调修复没有自动结束具体等待者

[OpenAIOAuthManager.kt:68](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/auth/OpenAIOAuthManager.kt#L68) performLogin使用suspendCancellableCoroutine等待code，给listener传expectedState/path，取消handler会stop；没有withTimeout，也未设置onExternalCancel。共享listener现在对error/全部端口占用调用stop，但此调用方没有收到终态来resume或fail continuation。因此原OA02在OpenAI仍未闭环：授权拒绝或端口占用结束listener后，调用协程仍可等待，直到外部scope取消。不能把上一轮MCP五分钟deadline推广到这个Provider。

ProviderDetailScreen第954行与AddProviderScreen第756行仍调用OpenAIOAuthManager.login，属现役UI链；不是仅有未接线类。API静态login取得token后还写ProviderRepository API key mirror。

[OpenAIOAuthManager.kt:216](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/auth/OpenAIOAuthManager.kt#L216) exchange日志已只记录status/bodyLen，旧PA01的OpenAI成功response正文泄漏原入口已改；失败仍把responseBody放Exception，实际上层错误展示/日志需要另审，不凭成功日志改善宣布所有失败凭据永不进入诊断。authUrl日志保留state，仅redact challenge；不把state单独出现等同私钥泄漏或账户接管。

token exchange是同步OkHttp执行，网络异常最多3次并Thread.sleep退避，无协程ensureActive在保存前；取消发生在网络请求期间仍可能写token，再由withContext返回取消。OA05共享“取消后提交”类残余在此保持，不重复编号。异常后的响应close不是use/finally，body读取失败资源处理留待其它网络边界核对。

账户字段setter对null不删除；parseIdToken若缺account/plan字段不会清旧值，logout也未清辅助元数据。旧PA09仍有源码支撑，未根据未知真实claim结构宣布当前服务一定跨账号错路由。

## PA03刷新串行与合并仍不同

[KimiOAuthManager.kt:213](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/auth/KimiOAuthManager.kt#L213) Kimi先进入mutex，再loadStoredTokens作为priorExpireAt，随后立即重读freshCheck。第二个已判定要刷新的调用排队进入时，两次读通常都来自前一个刷新后的同一bundle，freshCheck并不大于priorExpireAt，于是继续发下一次请求。锁使请求串行，但不达成注释single-flight合并。旧PA03在Kimi仍在；[GeminiOAuthManager.kt:200](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/auth/GeminiOAuthManager.kt#L200) Gemini同一结构也仍在，但不能据脚手架代码推导它有现役聊天刷新压力。

Kimi validAccessToken在锁外判定5分钟window，是真实等待者进入方式。ChatViewModel多个凭据选择位置使用OAuthManager.forInstance，工厂包含Kimi；本轮仅定位调用片段，未重新全文读取ViewModel，不追加其覆盖。

## PA06/PA07/PA08的Provider差异

Gemini与Kimi失败分类都已使用OAuthRefreshPolicy.isRevoked，普通400 invalid_request、bare403或503正文提refresh_token不直接视为撤销，旧过宽错误分类原反例已改善。Claude/xAI未在本轮全文阅读，不作全部Provider修复声明。

[GeminiOAuthManager.kt:226](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/auth/GeminiOAuthManager.kt#L226) Gemini2xx只解析JSON、保留refresh字段及expiry，然后save，没有要求非空access_token，旧PA08在Gemini仍在。200空JSON会生成只有refresh_token的bundle并报SUCCESS，下次validAccessToken因缺access_token提前返回null。Kimi成功分支已有非空access_token条件，不能报成同样缺失。

两类成功刷新新增storedRefreshTokenIs，能拒绝logout或另一刷新先完成后的旧结果；但检查与保存不同步于logout/新登录，原PA07仍部分修复，不能把compare-before-write称原子CAS。Gemini明确invalid_grant失败后直接logout，没有比对本次使用的旧bundle；Kimi先compare-before-delete，但比较和删除仍是两个步骤，不能据helper存在宣称提交完全不竞争。

Kimi device flow在delay期间取消会传播；但postForm同步返回Success后直接persistLoginSuccess，保存前没有ensureActive。UI onCancel确实取消外部loginJob，不等于正在执行的同步postForm和保存已经停止。本轮沿用旧OA05生命周期类边界，不另编号。

## Gemini范围和Kimi既有保护

Gemini clientSecret仍明确placeholder，OAuthManager.forInstance不实例化Gemini；工厂实际接线不支持把文件存在等同完整Google登录产品能力。本轮不新增“占位密钥导致上线登录失败”。discoverProject/email采用HttpURLConnection，未设置显式connect/read timeout且读全文，pollOperation24次delay5秒也不等于每次网络有界；仅记录脚手架实现候选，不作为现役新问题。

Gemini validAccessToken override不读manual bearer，与基类不同；未确认产品对此Provider开放手工入口，不重复扩张旧PA10。

Kimi有设备授权deadline、pending/slow_down、denied/expired/fatal分支；postForm失效JSON回落空对象，classified success要求非空token。手工bearer优先，refresh不返回新refresh_token时保留旧值，carry device_id/last_refresh。不能因提交竞争说成完全没有终态/刷新保护。已保存登录token不含expires_in，但Kimi使用固定5分钟buffer，不沿用基类refreshLead计算，因此不因缺该字段直接报每次刷新。

## 固定OpenMinis对照

上游HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，GIT_NO_LAZY_FETCH=1，仅现有KimiOAuthManager第208—233行。上游也在mutex内才取得prior/freshCheck，PA03此结构有上游对应；不能因为注释声称coalesced就认定正确，也不据此直接替换当前实现。未fetch或访问任何账号端点。

## 全文覆盖与文件保留

| 本轮新增全文 | 行数 |
|---|---:|
| [GeminiOAuthManager.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/auth/GeminiOAuthManager.kt#L1) | 292 |
| [KimiOAuthManager.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/auth/KimiOAuthManager.kt#L1) | 309 |
| [OpenAIOAuthManager.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/auth/OpenAIOAuthManager.kt#L1) | 330 |

OpenAI分1—175/176—330，Gemini分1—155/156—292，Kimi分1—165/166—309。Kimi后半初次混合搜索截断已独立补读完整；其它Provider、UI/Factory/VM仅搜索与片段，不升级全文。

新台账：[2026-10-07-code-review-coverage-37158d18-provider-oauth.json](/home/jiale/projects/minis-for-android/docs/archive/2026-10-07-code-review-coverage-37158d18-provider-oauth.json)。累计全文 **1264 / 1652文件、207291行**；partial4、not_registered384、excluded124，tracked1776。全库通读未完成。

交付核验旧162份报告及所有旧台账hash、固定本地/远端/上游HEAD、tracked/staged空diff、1776快照blob/hash/bytes/lines和证据行号。只新建本文与本轮台账。
