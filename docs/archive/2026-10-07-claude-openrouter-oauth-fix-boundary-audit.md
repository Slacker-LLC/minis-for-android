# Claude与OpenRouter OAuth修复边界复核

日期：2026-10-07。固定远端HEAD `37158d188a190416f2b16b45dfb112ecf53cdd7b`，不把本地旧源码当成当前生产事实。本轮新增完整静态阅读2文件、591行。只新建本文及新台账；此前163份报告、全部旧台账原样保留给Claude。没有修改代码、执行测试/构建、启动模拟器/adb/Root/设备/云任务，未进行实际OAuth登录、访问账号端点或读取凭据。

## 当前结论

本轮没有新增问题编号。旧PA01的OpenRouter授权材料日志入口已修；PA02备用端口读取、PA03刷新合并、PA08成功响应缺token以及PA10手工Bearer仍有对应源码。共享callback改进未自动覆盖每个具体Provider的等待终态和参数校验。

## OpenRouter：日志原反例已修，端口与终态仍有残留

[OpenRouterOAuthManager.kt:157](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/auth/OpenRouterOAuthManager.kt#L157) exchange日志只记录code/verifier长度，不再记录JSON请求正文；成功response也只记长度。旧PA01在这个具体入口已修。PKCE challenge仍用于URL/日志，它是公开挑战值，不能将其等同verifier或活跃API key。失败分支将原始responseBody放入Exception，上层是否展示或记录敏感失败内容尚未闭环；不能由正常日志改善推出所有失败输出均脱敏。

[OpenRouterOAuthManager.kt:68](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/auth/OpenRouterOAuthManager.kt#L68) server.start后立即读取server.boundPort，随后据此生成callback_url。共享OAuthCallbackServer第88—104行在后台线程绑定后才改boundPort，初值是主端口3000。因此3000被占用而回落3001/3002时，浏览器仍可能得到3000。这仍是旧PA02，不能因存在fallback列表或新增日志脱敏就宣布已修。

[OpenRouterOAuthManager.kt:61](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/auth/OpenRouterOAuthManager.kt#L61) 具体login的suspendCancellableCoroutine没有deadline，也没有设置onExternalCancel；只设置外部协程取消时stop。共享server在拒绝/端口失败等stop后不能通过本调用方通知continuation，旧OA02的无限等待残留仍在。构造callback未设置expectedPath；没有为OpenRouter拼装state参数，不能在未核实该授权协议要求前简单移植Claude的state结论。

取消提交必须区分保存位置：OpenRouter的ProviderRepository.saveApiKey在withContext返回之后，第107行。同步exchange可能继续完成，但取消的withContext返回边界会阻止外层正常继续；本轮不认定它与在IO块内保存token的Claude/OpenAI具有完全相同的取消后写入链。response.body.string会关闭所读响应体，不能仅因未显式response.close就认定每个正常请求泄漏连接；异常资源路径需另行验证。

## Claude：刷新分类已改善，刷新结果与选择链未闭环

[ClaudeOAuthManager.kt:335](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/auth/ClaudeOAuthManager.kt#L335) 已使用OAuthRefreshPolicy.isRevoked，替代旧的宽泛状态码/正文子串撤销判断。普通失败提到refresh_token不再当然触发logout，PA06原反例已改善。TRANSIENT保留凭据、仍有效旧token可用，不能描述为所有网络错误都会清账号。

[ClaudeOAuthManager.kt:287](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/auth/ClaudeOAuthManager.kt#L287) mutex内才读取stored和priorExpireAt，紧接着重读freshCheck。排队调用拿到锁后，两个快照通常同属前一次刷新后的存储，比较不会识别自己排队前的旧token，仍可继续刷新。互斥确实存在，但旧PA03的请求合并缺口仍在；不另报并发完全无保护。

[ClaudeOAuthManager.kt:314](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/auth/ClaudeOAuthManager.kt#L314) 成功刷新2xx分支没有检查非空access_token就保存JSON并返回SUCCESS。缺字段响应可覆盖原来完整bundle，后续validAccessToken因没有access_token返回null。PA08仍在。与此不同，登录exchangeCodeJson第243行已要求非空access_token，不能把刷新缺口扩大成所有成功登录均无token校验。

[ClaudeOAuthManager.kt:323](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/auth/ClaudeOAuthManager.kt#L323) storedRefreshTokenIs能丢弃存储已经改变的旧刷新结果，这是PA07的实际改善；检查和save仍非原子操作，logout/新登录未与其共用完整提交锁。invalid_grant分支直接logout，未比对失败请求使用的旧token，仍可清掉请求期间更新的凭据。沿用PA07部分修复状态，不新增重复竞争编号。

[ClaudeOAuthManager.kt:364](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/auth/ClaudeOAuthManager.kt#L364) validAccessToken override仍直接读动态bundle，未采用基类manual bearer优先规则。当前[ProviderFactory.kt:36](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/provider/ProviderFactory.kt#L36) Anthropic分支也直接使用传入apiKey，未像OpenAI/xAI分支读取manual覆盖。因此旧PA10的两个关键缺口未改变。手工编辑UI的实际入口此前已审，此次只核对当前选择实现；不以这两段推导所有版本都能执行Claude授权。

## Claude登录回调与构建能力边界

[ClaudeOAuthManager.kt:151](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/auth/ClaudeOAuthManager.kt#L151) performLogin首先requireCustomizationAvailable，buildAuthorizationUrl、静态login和exchangeCodeJson也有明确gate。公开构建缺能力时先拒绝，不应将后续代码缺口写成所有公开APK都能触发。

能力允许的流程在第164行创建listener时未传expectedPath/expectedState；第197行事后判断是state非null且不等于保存值才拒绝，缺省state仍被允许。等待没有deadline/onExternalCancel，与上述OA02同族；状态/path过滤沿用旧OA01调用方覆盖记录。PKCE仍存在，不据此宣称可直接盗取账号。

exchange同步执行后第259行在IO块内保存token，保存前无ensureActive，取消期间网络成功仍可能提交bundle。沿用OA05的取消提交边界；静态login外层API key mirror与内部bundle不是同一个提交点。

## OpenMinis对照

固定上游HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，仅以GIT_NO_LAZY_FETCH=1读取现有OpenRouterOAuthManager第55—115行，没有fetch。上游同样start后立即读boundPort，PA02不能靠原样照搬解决；上游exchange前另有OAuthForegroundGate.awaitForeground，而当前没有。Android后台网络是否影响当前设备需真实设备证据，用户未授权设备执行，本轮不把上游差异直接报成当前必现故障。

## 覆盖与保留

| 新增全文文件 | 行数 |
|---|---:|
| [ClaudeOAuthManager.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/auth/ClaudeOAuthManager.kt#L1) | 406 |
| [OpenRouterOAuthManager.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/auth/OpenRouterOAuthManager.kt#L1) | 185 |

Claude完整覆盖1—406，OpenRouter完整覆盖1—185。Factory/shared callback和旧报告只片段核对，不重复累计全文。

新台账：[2026-10-07-code-review-coverage-37158d18-claude-openrouter.json](/home/jiale/projects/minis-for-android/docs/archive/2026-10-07-code-review-coverage-37158d18-claude-openrouter.json)。累计全文 **1266 / 1652文件、207882行**；partial4、not_registered382、excluded124，tracked1776。全库通读仍未完成。

交付核验此前163份报告和全部旧台账hash、固定本地/远端/上游HEAD、tracked/staged空diff、1776快照blob/hash/bytes/lines及当前证据行号。只新建本报告和本轮台账。
