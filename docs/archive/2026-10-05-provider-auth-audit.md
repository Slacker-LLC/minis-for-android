# Provider 认证审计：日志、刷新与登出边界

日期：2026-10-05。目标源码：`d64e7c06d8159be90737dd9ba03dd9bd5855f339`。

本报告独立于此前所有报告。只做静态源码审计及固定上游快照对照；没有修改产品代码、运行测试、构建、模拟器、设备命令或访问账户服务。下面的触发顺序为静态推导，验收方案均未执行。

## 与论文及此前报告的关系

[Harness Engineering §15.6](https://arxiv.org/html/2609.00006v1#S15.SS6) 明确限定为源码结构研究，未测量运行速度；§16 的简单循环与延迟工具加载建议不能证明某个产品的恢复、持久化或权限实现正确。原聊天的架构判断已见[论文对照报告](/home/jiale/projects/minis-for-android/docs/archive/2026-10-05-harness-paper-review.md)，不重复计数。

此前 OA01–OA07 是共享 callback 与 MCP OAuth 的问题。本轮全文读到 Claude、OpenAI、OpenRouter、xAI、Kimi 等调用方后，确认一些相同缺口覆盖更多流程，见末尾说明；以下新增项针对不同的日志入口、token 刷新及用户设置行为。

## 新发现

| 编号 | 级别 | 问题 |
|---|---|---|
| PA01 | P1 | OpenAI 原始 token 响应和 OpenRouter 完整 code／verifier 写入日志 |
| PA02 | P2 | OpenRouter 未等监听绑定完成就读取 fallback 端口 |
| PA03 | P2 | 刷新互斥锁串行执行，却没有合并等待者的刷新请求 |
| PA04 | P2 | 四小时刷新窗口使短有效期 token 每次读取都刷新 |
| PA05 | P2 | 已过期 token 刷新遇到暂时故障便删除长期凭据 |
| PA06 | P2 | Claude 将含 refresh_token 的普通失败正文误判为授权失效 |
| PA07 | P1 | 用户登出后，在途刷新仍能重新写入 OAuth 凭据 |
| PA08 | P2 | 缺 access_token 的 2xx 刷新响应覆盖原凭据并报告成功 |
| PA09 | P2 | 登出不清账户元数据，再登录可能沿用旧账户请求头 |
| PA10 | P2 | Claude 手工 Bearer 设置没有进入实际凭据选择链 |
| PA11 | P2 | 导出回调 Activity 未校验运行时目标，能转发非 OAuth loopback GET |

### PA01 — 新的凭据日志入口

[OpenAIOAuthManager.kt:218](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/auth/OpenAIOAuthManager.kt:218) 将 exchange response 前 500 字符直接交给 AppLogger.info，成功路径也记录。截断长度不是脱敏：token 若位于响应前部，会留下原文或前缀；本轮没有实际登录，不声称所有 token 都能在 500 字符内完整出现。

[OpenRouterOAuthManager.kt:158](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/auth/OpenRouterOAuthManager.kt:158) 记录完整 JSON 请求，包含 code 与完整 code_verifier；后续又单独记录 code。它已隐藏成功响应中的 API key，却遗漏请求端。与 OA04 仅记录 callback code/state 不同，这里将 PKCE verifier 一并保存。现有 logcat 收集器可把 Log.d 行写入 App 文件日志；没有证据证明普通第三方 App 可直接读取这些文件，也不声称已经发生账户接管。

方向：在最初记录位置删除凭据内容，仅保留状态、长度和失败类别。验收：受控授权端点使用 canary code、verifier、access/refresh token，完整走成功与失败，再搜索 logcat、文件日志和诊断导出包；保存无匹配的记录，不使用真实账户秘密。

### PA02 — fallback 端口读取早于绑定结果

[OpenRouterOAuthManager.kt:67](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/auth/OpenRouterOAuthManager.kt:67) 调用 server.start 后立刻读取 server.boundPort 并构造 callback_url。[OAuthCallbackServer.kt:42](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/auth/OAuthCallbackServer.kt:42) 在另一个线程绑定，boundPort 初始值为 3000，随后才可能改为 3001 或 3002。

触发：3000 被占用，调用线程先取到默认值，监听线程随后成功绑定 3001。浏览器仍被告知回调 3000，本流程收不到 code。该 fallback 不是可靠的已完成绑定结果；没有测量实际竞态频率。

方向：使用真实绑定完成结果构造授权 URL，失败必须结束等待。验收：预占首端口，记录 listener 端口与授权 URL 一致，完成回调；再占满所有端口，记录明确失败和界面恢复。

### PA03 — 请求合并检查在锁内才取得旧快照

[ClaudeOAuthManager.kt:285](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/auth/ClaudeOAuthManager.kt:285)、[GeminiOAuthManager.kt:198](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/auth/GeminiOAuthManager.kt:198) 与 [KimiOAuthManager.kt:215](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/auth/KimiOAuthManager.kt:215) 在 mutex.withLock 内先 loadStoredTokens，再立即重读 expire_at。等待者取得锁时，第一位调用者的新 token 已经写入；它的 priorExpireAt 和 freshCheck 都来自更新后的存储，通常相等，因此不会跳过第二次 HTTP 请求。

互斥锁确实防止这些刷新同时执行，不能说完全没有并发保护。但多个 validAccessToken 调用先在锁外判定需刷新，会依次继续刷新，增加延迟、请求和轮换次数。Gemini 的当前工厂没有生产聊天刷新接线，本项对其只认定实现缺口；Claude/Kimi 有实际调用方。

方向：在现有边界保留触发版本或在锁内重判当前 token 是否仍需刷新；区分预刷新与服务明确拒绝后强制刷新。验收：多个会话同时触发预刷新，受控端点阻塞第一条请求再放行，记录只发生一次刷新及所有调用取得同一有效版本。

### PA04 — 短有效期 token 在每次使用时刷新

[OAuthManager.kt:281](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/auth/OAuthManager.kt:281) 只要距离到期小于四小时就刷新；OpenAI 使用该基类，xAI 也继承 validAccessToken 并覆盖刷新请求。

静态例子：服务返回 expires_in=3600，刚保存的新 token 距到期也小于四小时。下次读取仍命中条件，刷新后的 token 仍然满足条件，之后每次读取都继续刷新。这里没有断言当前真实服务一定使用一小时有效期。

方向：根据 token 有效期设合理的提前量，复用已有五分钟窗口或受控比例，不在每次正常调用前增加认证请求。验收：受控端点发行一小时 token，连续执行实际模型请求，记录有效期内不重复刷新、进入窗口才刷新。

### PA05 — 暂时故障导致凭据丢失

[OAuthManager.kt:284](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/auth/OAuthManager.kt:284) 对 refreshToken=false 不区分原因，只要原 access token 已过期就 logout；基类 refreshToken 遇到超时、断网、5xx 同样返回 false。

因此，过期时短暂网络异常会把仍可能有效的 refresh token 删除，网络恢复后无法再凭原授权恢复。xAI 的 [XAIOAuthManager.kt:366](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/auth/XAIOAuthManager.kt:366) 在 403 分支明确保留 token，但其基类调用方会在已过期时再次 logout，抵消该保留处理。

方向：当前请求不能继续时返回明确错误，临时失败保留长期凭据；明确授权拒绝与临时故障分别处理。验收：access token 已过期时让刷新端点先超时／503／403，恢复正常后重试；保存凭据存在性与恢复成功证据，不记录 token 本文。

### PA06 — 错误正文子串触发破坏性清理

[ClaudeOAuthManager.kt:335](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/auth/ClaudeOAuthManager.kt:335) 将所有 400/401/403 或正文中任意 refresh_token 子串视为 INVALID_GRANT，随后 logout。

静态示意：HTTP 503，正文为 error=temporarily_unavailable、error_description=refresh_token service unavailable；并非授权撤销，也会因字段名出现而删除凭据。Gemini/Kimi 虽没有这个宽子串，也将所有指定 4xx 归到授权失效，需要分别判断明确 error 类别。RFC 的 invalid_request、invalid_client 与 invalid_grant 含义不同，不能只靠 HTTP 400 推断用户授权被撤销。[RFC 6749 §5.2](https://www.rfc-editor.org/rfc/rfc6749.html#section-5.2)

方向：解析明确 error 字段及状态，不搜索自由文本决定删除；是否需要重认证与是否删除凭据分开。验收：503 描述带 refresh_token、400 invalid_request、明确 invalid_grant 等受控响应，记录各自终态和凭据保留策略。

### PA07 — 登出没有使在途写入失效

[OAuthManager.kt:301](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/auth/OAuthManager.kt:301) logout 直接删两项偏好，没有取消或使在途刷新版本失效。刷新成功分支随后仍无条件 saveTokens／saveOAuthString；例如 [KimiOAuthManager.kt:251](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/auth/KimiOAuthManager.kt:251)。

真实入口 [ProviderDetailScreen.kt:919](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/ProviderDetailScreen.kt:919) 同时删 OAuth 存储及 API key mirror，但与其他会话已经发出的刷新没有共同提交边界。

触发顺序：刷新读旧 refresh token 并发请求 → 用户 Sign Out → 成功响应回来 → 刷新重新写入凭据。后台 ChatViewModel 的更新分支还可能把新 token 重新写入 API key mirror。用户界面已经显示退出，授权数据却恢复。本项是明确登出与其他任务的刷新竞争，区别于 OA05 取消一个登录 coroutine 后的 exchange 写入。

方向：登出、切换身份与刷新提交共享版本检查／现有同步边界，旧任务不能恢复已清除身份；失败回复也不能删掉期间完成的新登录。验收：端点延迟成功与失败回复，期间退出或登录另一账号，记录旧任务不能写回或删除新凭据。

### PA08 — 不完整成功回复覆盖可恢复凭据

[OAuthManager.kt:257](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/auth/OAuthManager.kt:257)、[ClaudeOAuthManager.kt:318](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/auth/ClaudeOAuthManager.kt:318)、[GeminiOAuthManager.kt:238](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/auth/GeminiOAuthManager.kt:238) 与 [XAIOAuthManager.kt:377](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/auth/XAIOAuthManager.kt:377) 只凭 2xx 和可解析 JSON 保存并返回成功，未要求非空 access_token。

例如 200 {} 会覆盖原 bundle，仅保留补入的 refresh token。之后 validAccessToken 先因 access_token 缺失返回 null，甚至不再进入刷新，原本可恢复的凭据进入不可用状态。没有证据表明真实服务已返回这种正文；这是响应异常时的失败处理缺口。Kimi 的 classified refresh 已检查非空 access_token，不能误报为同样缺失。

方向：验证必需字段后才整体替换；异常响应保留原 bundle 并报失败。验收：有效 JSON 缺字段、空字段与正常返回走实际认证入口，记录原状态保持与失败提示，不将不完整响应显示为成功。

### PA09 — 旧账户元数据沿用

logout 只删除 token/manual bearer，没有删除 oauth_account_id、plan_type 等辅助键。[OpenAIOAuthManager.kt:144](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/auth/OpenAIOAuthManager.kt:144) 的 setter 对 null 不执行删除；新登录缺 id_token 或无法提取字段时，旧值继续存在。

[ProviderFactory.kt:72](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/ProviderFactory.kt:72) 把旧 accountId 传入 Provider，[OpenAIProvider.kt:2472](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/openai/OpenAIProvider.kt:2472) 再写入 Chatgpt-Account-Id 请求头。账号 B 的 bearer 可以配上 A 的 account header，可能导致拒绝或错误选择；没有跨账户请求成功证据。xAI 的可选账户字段同样不在 logout 中清除，其影响目前限定为旧元数据。

方向：身份切换时清除旧身份字段；新 bundle 与元数据作为一致身份更新。验收：A 登录并退出，B 返回缺可选元数据的响应，记录后续 header 不带 A 的标识。

### PA10 — Claude 的 Manual Bearer 设置被绕过

[ProviderDetailScreen.kt:240](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/ProviderDetailScreen.kt:240) 对 OAuth 实例提供手工 token 编辑，保存只调用 manager.saveManualBearerToken，不写 API key mirror。

[ClaudeOAuthManager.kt:363](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/auth/ClaudeOAuthManager.kt:363) 覆盖 validAccessToken 时未读 manual bearer，直接读取动态 bundle；[ProviderFactory.kt:36](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/ProviderFactory.kt:36) 的 Anthropic 分支也直接使用传入 apiKey，没有 OpenAI/xAI/Kimi 的手工覆盖分支。ChatViewModel 预刷新又调用这个 override。

结果：界面显示 Configured，已有旧 key 时仍使用旧凭据，没有动态 bundle 时这个 manager 返回 null。此项不把所有发行版本认定为可执行 Claude 登录：公开构建的 customization capability gate 是明确存在的保护；要在支持该能力的构建／有效配置中验证实际请求。

方向：复用既有凭据选择顺序，确保展示、路由、手工覆盖与刷新一致。验收：保存 canary 手工 bearer，走受支持的自定义 Anthropic 端点，记录 Authorization 使用新值，且没有动态刷新；删除覆盖后恢复原选择规则。

### PA11 — 回调转发目标未受运行时校验

[AndroidManifest.xml:421](/home/jiale/projects/minis-for-android/src/android/app/src/main/AndroidManifest.xml:421) 将 OAuthRedirectActivity 导出。[OAuthRedirectActivity.kt:33](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/auth/OAuthRedirectActivity.kt:33) 只检查存在 code，随后将 URI 的端口、路径、query 转成 127.0.0.1 HTTP GET，未检查对应的活动登录、scheme/host、合法回调路径或端口。

Manifest filter 不能代替运行时参数校验；Android 明确说明显式 Intent 可直达目标，不受其 filter 限制。[Android Intent 文档](https://developer.android.com/guide/components/intents-filters)

在设备允许该 Activity 被启动的条件下，另一 App 可提供非 OAuth 端口和路径，触发以 Minis 身份发出的 loopback GET。影响依赖目标服务及 Android 后台启动限制；固定 loopback、五秒超时、没有将响应正文返回外部，不能据此推导远程任意访问或 Root 执行。

方向：转发只允许当前授权尝试的既定 callback 元组，拒绝非活动登录及错误参数；不能单靠 filter 或 code 存在判断。验收：受控本地 canary endpoint 与外部测试 App 验证非法目标不收到请求、合法浏览器回调仍可完成，保留请求计数。

## 固定 OpenMinis 对照

仅只读本地 git 对象快照 `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，未拉取、运行或使用上游设备结论：

- [上游 OpenRouterOAuthManager](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/auth/OpenRouterOAuthManager.kt#L158) 已改为脱敏 code/verifier；本地仍记录完整请求。上游 OpenAI 原始响应日志仍相同，不能把两种入口统称已修。
- [上游 OAuthManager](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/auth/OAuthManager.kt#L291) 已区分拒绝并标记重认证，已过期且临时刷新失败时保留凭据；本地仍直接 logout。
- [上游 ClaudeOAuthManager](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/auth/ClaudeOAuthManager.kt#L345) 已采用结构化错误分类并标记重认证，替代本地宽子串及清空路径。该快照的刷新合并快照仍在锁内，PA03 不能直接照搬上游解决。
- 上游 OpenRouter 仍是异步 start 后立即读 boundPort，PA02 属于共享缺口。其余项未逐项确认上游是否已修，不泛化上游状态。

## 已确认但不重复计为新增

Claude/xAI 的 callback 等待仍未使用 expectedPath/expectedState 完成前置筛选，且回调后检查允许缺省 state；OpenAI 则已给 shared server 传入二者。缺省筛选、拒绝无终态、query 解码、callback 日志与同步 exchange 的取消提交问题沿用此前 OA 项。

Kimi device flow 已有授权 deadline、pending/slow_down 分类、拒绝与过期终态、HTTPS verification URL 校验以及 invalid-grant 的 compare-before-delete helper。其两个现有测试分别覆盖 capability gate 和纯 device-flow 分类，不能据此认定完整登录、取消、刷新和登出都通过；本轮只读测试源码。

Gemini/Antigravity 存在导入或脚手架用途，不能仅凭 manager 文件就认定完整生产登录已接通。Antigravity 工厂明确拒绝不支持的 provider，Google client secret 明确为 placeholder，不按已启用功能报“登录失败”。OpenAI account claim 实际服务结构、Android Custom Tab 前台／网络行为均没有设备证据，不列成已复现结论。

## 阅读记录

新增全文阅读 13 个文件、4,304 行：OAuthManager、OpenRouterOAuthManager、OAuthRedirectActivity、OpenAIOAuthManager、ClaudeOAuthManager、AntigravityOAuthManager、GeminiOAuthManager、KimiDeviceFlow、KimiOAuthManager、XAIOAuthManager、ClaudeOAuthCapabilityTest、KimiDeviceFlowTest、ProviderDetailScreen。ProviderFactory 和共享 callback helper 已计入此前全文覆盖，本轮重读不重复累计。

大文件 ProviderRepository、OpenAIProvider、ChatViewModel 与 Manifest 仅登记本轮确实读取的片段。累计全文覆盖 261/1546；全仓审计仍未完成。只新增本报告并更新阅读台账，此前 18 份报告保持原样。

