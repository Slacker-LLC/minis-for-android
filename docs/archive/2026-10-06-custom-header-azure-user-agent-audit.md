# 自定义请求头、Azure 凭据与 User-Agent 审查

日期：2026-10-06。固定 HEAD `d64e7c06d8159be90737dd9ba03dd9bd5855f339`。本轮新增 **2 项 P2**；全文阅读 3 个文件、338 行。静态审查，未改产品源码，未运行测试、构建、模拟器、设备、Root 或云任务，没有请求 Provider。独立报告，旧报告不改。

## HD01 / P2：自定义请求头过滤漏掉 Azure 的 api-key，可替换实例凭据

[CustomHeaderPolicy.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/CustomHeaderPolicy.kt:26) 的 FORBIDDEN_NAMES 包含 authorization、x-api-key、anthropic-version，但没有 api-key。第 46 行的 SENSITIVE_NAMES 已包含 api-key，却只在日志脱敏使用。sanitizeWithWarnings 对合法 ASCII 的 api-key 会保留，没有警告。

现役 [ProviderFactory.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/ProviderFactory.kt:100) 将 instance.azureMode 传入 OpenAIProvider；[OpenAIProvider.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/openai/OpenAIProvider.kt:349) 的 applyKeyAuth 在 Azure 下设置 api-key=实例 token。标准 model-use 路径 [ModelUseOffloadHandler.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/ModelUseOffloadHandler.kt:346) 将解析后的 extra_headers 赋给 chatExtraHeaders，[ModelUseOffloadHandler.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/ModelUseOffloadHandler.kt:864) 使用该过滤器。[OpenAIProvider.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/openai/OpenAIProvider.kt:2515) 先 applyKeyAuth，再在第 2523 行用 header 写 chatExtraHeaders，替换同名值。

静态输入：有效 Azure 实例凭据是 fixture-instance-key，调用 envelope 带 extra_headers={"api-key":"fixture-caller-key"}。过滤器保留后者，实际 Request 的 api-key 被换成 fixture-caller-key。这些字符串仅为报告反例，不读取或输出真实凭据。rawPassthroughRequest 第 1596—1604 行也先认证再写过滤后的 userHeaders；图片第 1708—1716 行先认证再写 imageExtraHeaders，同样适用。

影响是过滤器声明的“凭据由实例持有”边界对 Azure 不一致：请求可因调用方值错误而认证失败，或者使用与所选实例不同的凭据。没有据此宣称本地 Agent 不能自行指定凭据、不把已有授权能力描述为远程提权，也不声称原 token 因覆盖而泄漏。redactForLog 已遮蔽 api-key，本项不是 MC01 的 Anthropic 目录日志问题。

[CustomHeaderPolicyTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/provider/CustomHeaderPolicyTest.kt:36) 的凭据拒绝测试只包含 Authorization、x-api-key、anthropic-version；第 142 行后的日志测试包含 api-key，但没有验证它被过滤。修复方向：按现役 Provider 的认证方式补齐受控凭据头过滤，并核对三种 envelope 与 Azure 调用，不靠脱敏替代请求边界。只记录。

## HD02 / P2：User-Agent 保存不做值校验，非法字符使后续请求在构造阶段失败

[UserAgentOverride.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/UserAgentOverride.kt:29) 对 customUserAgent 只 trim，非空就直接 header。设置页 [ProviderDetailScreen.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/ProviderDetailScreen.kt:356) 的输入 onValueChange 原样存状态；第 387 行保存时只把 blank 转 null。singleLine 不会禁止中文/emoji 等非 ASCII 字符，trim 也不会删除它们。ProviderFactory 第 95 行把保存值传入真实 Provider，请求第 2529 行调用此 helper；目录请求同样有相应调用入口。

项目依赖 OkHttp 4.12.0（build.gradle.kts 第 557 行）。[该版本 Headers.Builder.set/checkValue 官方源码](https://github.com/square/okhttp/blob/parent-4.12.0/okhttp/src/main/kotlin/okhttp3/Headers.kt#L413)要求 header 值字符为 tab 或可打印 ASCII，非法值以 require 拒绝，并在 set 前校验。

静态输入：设置 User-Agent 为 Minis/测试 并保存。applyUserAgentOverride 的 trim 得到同一非空字符串，OkHttp 因中文字拒绝，网络请求还没发出便失败。不是服务端不支持该 UA，也不是网络暂时不可达；配置持续保留，后续该 Provider 请求仍遇到相同构造失败，直到用户改回合法值。各调用捕获方式不同，本轮只确认请求构造失败，不泛化为 App 必然崩溃。

普通 extra_headers 已使用第 84 行的 printable ASCII/tab 检查，自定义 UA 却绕过该值检查，属于输入边界不一致。修复方向：在保存/导入及应用入口复用已有合法值规则，明确反馈无法发送的值；不默默编码任意 UA 或增加新网络层。未修改。

## 其它核对与 OpenMinis 对照

CustomHeaderPolicy 的协议头拒绝、名称 token 字符集、ASCII/tab 值校验、大小写去重和凭据日志遮蔽已全文核对。warnings 使用原始 header 名称而未规范换行、Cookie/Proxy-Authorization 不在 SENSITIVE_NAMES 等仍需结合实际输入及记录合同继续看；本轮不把所有普通自定义头都假定为机密，不新增泛化泄漏报告。

UserAgentOverride 的 null/blank 默认以及 OAuth 调用传 defaultUserAgent=null 保留 SDK 身份规则已核对。默认 UA 使用 Build.MODEL/RELEASE 原值，设备返回非 ASCII 时也可能触发同类值校验；没有真实 OEM 返回证据，不另计设备故障。注释称默认 OkHttp UA 已广播机型未在本轮验证，不作为隐私判断依据。

只读 `/tmp/openminis-review-20261004` HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，未 fetch。上游 UserAgentOverride 第 29—40 行同样只 trim 后写 header，保留 HD02 条件。该快照树没有 CustomHeaderPolicy 同名路径，不能据此证明上游其它过滤器已覆盖 Azure 或必须恢复旧 passthrough 行为。Eta 来源仅为源码声明，不替代本仓证据。

## 全文覆盖与复核

| 本轮全文文件 | 行数 |
|---|---:|
| [CustomHeaderPolicy.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/CustomHeaderPolicy.kt) | 111 |
| [UserAgentOverride.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/UserAgentOverride.kt) | 69 |
| [CustomHeaderPolicyTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/provider/CustomHeaderPolicyTest.kt) | 158 |

累计全文 **1140 / 1546 个文件、223202 行**；partial 13，not_registered 393，excluded 124。全仓通读尚未完成。已有测试 158 行只静态阅读，未运行；调用方已登记文件只复核片段，不重复计数。合并输出中截断的请求构造/过滤片段已单独重读；未把截断搜索当全文。

完成时核对全部 1670 tracked 路径 SHA-256、字节数与正数文本行数，固定本仓/上游 HEAD、tracked/staged 空 diff、此前 93 份报告哈希、本报告文件链接/行号和批次全文总计。只新建本报告并更新覆盖台账，未读取真实 Provider 设置、API Key、凭据或请求日志。
