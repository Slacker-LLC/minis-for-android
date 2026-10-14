# xAI OAuth与真实存储Guard测试范围复核

日期：2026-10-07。固定远端HEAD `37158d188a190416f2b16b45dfb112ecf53cdd7b`。新增完整阅读2文件、644行。只静态审查；未修改代码、编写/执行测试、构建、启动模拟器/adb/Root/设备/云任务、登录或访问OAuth端点，未读取凭据。保留此前164份报告和全部旧台账，另建本文。本轮没有新增重复编号。

## 刷新校验与提交：PA08仍在，PA07部分改善

[XAIOAuthManager.kt:379](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/auth/XAIOAuthManager.kt#L379) refreshToken对2xx解析JSON，补旧refresh_token及expire_at，随后guard、saveTokensJson、返回true，没有非空access_token条件。200空JSON可以覆盖原完整bundle，后续凭据选择失去access token。旧PA08在xAI仍在。基类第263行新增的access_token检查不执行于这个override，不能把基类修复推广到子类。

登录exchange则在第261行明确拒绝空access_token，与刷新有差异。保留旧refresh_token、403保留bundle也确实实现，不能报告成全部响应验证和失败恢复都缺失。

[XAIOAuthManager.kt:385](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/auth/XAIOAuthManager.kt#L385) storedRefreshTokenIs检查能拒绝已经logout或轮换的旧请求结果；检查与saveTokensJson不是同一次原子提交，PA07仍部分修复。本override无刷新mutex，多个请求可能并行；旧PA03主要报告Claude/Gemini/Kimi的锁内快照合并，不把xAI不同结构强行说成同一个锁逻辑，也不无证据宣布某次真实服务轮换一定发生。

xAI继承当前基类validAccessToken：手工Bearer优先、按expires_in计算刷新提前量、过期且非撤销失败时保留bundle。旧PA04固定四小时反例和PA05 transient logout在这条调用路径已有改善。xAI失败分支未设置基类lastRefreshRevoked，401/invalid_grant也只返回false；是否需要重认证提示是实现差异，不将保留凭据直接报成新数据安全故障。

## Callback终态：有实际改善，state过滤仍有缺口

[XAIOAuthManager.kt:449](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/auth/XAIOAuthManager.kt#L449) server.onExternalCancel取消continuation，invokeOnCancellation再stop，成功callback先清取消hook再resume。共享listener遇端口失败/授权error后stop能够通知本等待者，因此不能沿用OpenAI/OpenRouter的“stop没有接线通知”的原反例。finally会reset isAuthenticating并disarm observer。

[XAIOAuthManager.kt:427](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/auth/XAIOAuthManager.kt#L427) listener未传expectedPath/expectedState；第499行事后检查同时要求state非null才拒绝，所以缺省state仍被接受。沿用OA01的具体调用方残留，PKCE仍存在，不据此宣称账号接管。xAI固定127.0.0.1:56121/callback，不能把OpenRouter的备用端口竞争套到此处。

尚无总授权deadline，dismissal detector只在观察到后台再回前台时延迟1.5秒stop。它提供一种取消途径，不能替代所有等待有界。GlobalScope延迟任务未被disarm取消，醒来读取当前loginCallbackServer；若同一manager重用并快速开始新登录，有误停新listener的候选窗口。但当前静态login每次构造新manager，UI还有isAuthenticating防重复，本轮没有确认产品调用能触发同manager重用，不新增确定问题编号。

同理isAuthenticating是实例字段、volatile检查再写并非原子锁，不能当作跨实例/跨线程互斥；当前UI主线程guard限制正常入口，未提供并发触发证据，不另报用户必现登录失败。

## 同步交换、失败日志和账户元数据

[XAIOAuthManager.kt:271](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/auth/XAIOAuthManager.kt#L271) 同步execute之后在IO块内saveTokensJson/onTokensReceived，无保存前ensureActive，取消后bundle提交沿用OA05；静态login外层API key mirror是另一个提交点。

失败日志先sanitizeBody，但Exception第258行拼入raw body。当前[ProviderDetailScreen.kt:977](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/ui/settings/ProviderDetailScreen.kt#L977) catch把e.message交给AppLogger.warning，说明失败正文存在实际诊断传播链。正文是否含凭据取决于服务响应；本轮不假设正常服务会回显token，不把成功response脱敏结论扩张成失败路径已全脱敏。无新增PA01重复编号。

accountId/email/displayName setter对null不删除，parseIdToken缺字段保留旧值，继承logout未清这些辅助字段。旧PA09在xAI的影响仍限定旧显示元数据；未发现这些字段写进xAI请求账号header，不借用OpenAI账号header的后果。

## Discovery与测试的实际边界

[XAIOAuthManager.kt:173](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/auth/XAIOAuthManager.kt#L173) discovery明确要求两个endpoint host以.x.ai结尾，evilx.ai不满足该后缀。未显式验证scheme，但请求client还有现有传输保护；没有读取整个网络保护链来证明可把token发到明文端点，不能仅由此检查报告明文泄漏。缓存endpoint直接读，属于App私有prefs；不假定远端攻击者能写缓存。id_token解码用于展示，未将未验签展示解析等同服务端认证绕过。

[OAuthRefreshGuardInstrumentedTest.kt:45](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/androidTest/java/com/openminis/app/auth/OAuthRefreshGuardInstrumentedTest.kt#L45) 设备测试的3例是原token匹配、logout后不匹配、轮换后旧值不匹配，针对真实加密存储。没有发起真实刷新HTTP，没有在guard=true与save之间插入logout/轮换，也没有验证原子提交。因此它能证明helper的顺序检查行为，不能证明PA07竞争完全解决。本轮只是完整读取测试，未运行设备或测试。

## 固定OpenMinis对照

上游HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`。GIT_NO_LAZY_FETCH=1读取现有xAI第345—405、510—610行，无fetch。上游刷新同样没有access_token非空检查，当前新增storedRefreshTokenIs有改善，不能以复制上游闭环PA08；上游dismissal的GlobalScope延迟后读取当前server也同形。上游非2xx有markNeedsReauth，而当前不同，是否采用需与当前凭据生命周期一起评估，本轮不改实现。

## 覆盖与文件保留

| 本轮新增全文 | 行数 |
|---|---:|
| [OAuthRefreshGuardInstrumentedTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/androidTest/java/com/openminis/app/auth/OAuthRefreshGuardInstrumentedTest.kt#L1) | 64 |
| [XAIOAuthManager.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/auth/XAIOAuthManager.kt#L1) | 580 |

xAI分1—200/201—400/401—580完整阅读；Guard测试1—64全文。其它caller/base只片段核对，不重复累计。

新台账：[2026-10-07-code-review-coverage-37158d18-xai-oauth.json](/home/jiale/projects/minis-for-android/docs/archive/2026-10-07-code-review-coverage-37158d18-xai-oauth.json)。累计全文 **1268 / 1652文件、208526行**；partial4、not_registered380、excluded124，tracked1776。全库通读未完成。

交付核验旧164份报告及全部旧台账hash、固定本地/远端/上游HEAD、tracked/staged空diff、1776快照blob/hash/bytes/lines和证据行号。只新建本文与本轮台账。
