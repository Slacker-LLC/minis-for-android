# 配置内置字段、审计与环境变量审查

日期：2026-10-05。本地源码：`d64e7c06d8159be90737dd9ba03dd9bd5855f339`。OpenMinis 对照固定为 `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，不代表远端最新版本。

本轮继续全仓静态审阅，单独保存这份报告。只写报告和阅读台账，没有修改产品代码，没有运行测试、构建、模拟器、adb、Root、登录流程或云任务。

## 本轮结论

工具归属不只是把命令放进 guest。配置工具仍需要 App 的持久化、确认与会话身份边界；当前实现却有几个断点：确认时与执行时的 session 可以不同，设置写入 DB 后运行中的 Agent 不读取，隐私设置存在但脱敏调用已经退出现役路径。

这与 [论文对照报告](/home/jiale/projects/minis-for-android/docs/archive/2026-10-05-harness-paper-review.md) 的检查点相符：主循环或工具表面积的结构整理，不能替代具体失败路径的核对。论文没有评测 Minis，也没有证明把所有 App tool handler 搬到沙箱就能修复以下问题。

| 编号 | 优先级 | 新发现 |
|---|---|---|
| CB01 | P1 | 隐私模式的环境变量脱敏器没有现役执行调用方 |
| CB02 | P1 | session 配置在确认、执行和撤销之间不固定目标会话 |
| CB03 | P2 | CLI 写思考等级只更新 DB，下一轮仍用 ViewModel 旧值 |
| CB04 | P2 | session 思考等级读写支持 MAX/ULTRA，schema 却拒绝 |
| CB05 | P2 | 手拼模型绑定 JSON 不能正确表达带引号或反斜杠的现役 entry ID |
| CB06 | P2 | CLI 修改语言不执行语言应用动作，当前界面继续用旧语言 |
| CB07 | P2 | Live Updates 的动态权限被注册表固定为启动时状态 |
| CB08 | P2 | 回复后自动聚焦开关有 UI 和 CLI，没有运行时读取 |
| EV01 | P1 | 环境变量多行／非 ASCII 值保存时被静默改写 |
| EV02 | P1 | 环境变量编辑异步读取可以覆盖新输入，加载前保存可清空旧值 |
| EV03 | P1 | 环境变量元数据保存／加载失败未阻止成功返回和后续覆盖 |
| EV04 | P2 | 环境变量 CRUD 缺少业务级串行化，备注更新也会重写秘密值 |

P1/P2 是处理优先级，不是漏洞评级。EV04 为源码确认的可达并发风险，未做并发压力或真机复现；其余条目也均不宣称已经在设备上复现。

## CB01 — 隐私模式没有接入现役 shell 结果链

[EnvVarPrivacyStore.kt:26](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/EnvVarPrivacyStore.kt:26) 默认开启；[EnvironmentVariablesScreen.kt:105](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/EnvironmentVariablesScreen.kt:105) 提供开关；[ConfigBuiltins.kt:652](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/config/ConfigBuiltins.kt:652) 对模型承诺 shell 输出里的环境变量会被遮蔽。脱敏算法本身保留在 [EnvVarRedactor.kt:79](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/EnvVarRedactor.kt:79)。

但现役路径是：

1. [MinisApp.kt:717](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/MinisApp.kt:717) 将 `shell_execute` 注册为 LinuxShellHandler 的别名。
2. [ExecutionCoordinator.kt:108](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/ExecutionCoordinator.kt:108) 注入环境变量，122 行仅做终端字符清理和截断。
3. [ToolRegistry.kt:260](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/runtime/ToolRegistry.kt:260) 原样返回 result.output；[ToolResultBudget.kt:34](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/runtime/ToolResultBudget.kt:34) 仅控制体积／落盘。
4. [ChatViewModel.kt:9487](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:9487) 仅追加循环警告，9570 行把内容放进 ToolResult，9616 行加入模型历史。

全仓 `redactIfEnabled` 搜索只命中函数定义；EnvVarRedactor 的其它生产引用仅为 Application 初始化和说明。开启开关后执行打印一个超过 4 字符的 canary 环境变量，按这条链仍会把原值交给模型。此处没有要求猜测、窃取或使用真实凭据。

固定上游 [ChatViewModel.kt:14335](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt#L14335) 仍调用脱敏器。本地替换旧 shell dispatch 后漏掉这层行为，是现役路径回退；不应为恢复它重新引入旧 broker／PRoot。

方向与待执行验收：在现有结果进入模型的边界恢复隐私策略，同时核对溢出预览与重新读取路径。使用虚构 canary，分别记录 UI 可见结果、模型收到的 ToolResult、开关 OFF 的结果；长输出也不能绕过承诺。短值过滤是算法的明确策略，本项不把它另计缺陷。

## CB02 — 确认和执行没有绑定同一个 session

[ConfigBuiltins.kt:130](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/config/ConfigBuiltins.kt:130)、146、161、171 行分别在读旧值和写新值时重新读取 `ChatViewModelStore.activeSessionId`。该值由 [ChatScreen.kt:634](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatScreen.kt:634) 在界面挂载时设置，离开界面时清空；[ChatViewModelStore.kt:86](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModelStore.kt:86) 明确将它定义为前台聊天。

写入流程在 [ConfigBridge.kt:421](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/config/ConfigBridge.kt:421) 读取旧值，538 行等待确认，657 行才调用 writer。Resolved 中保存了 field 和数值，没有固定目标 session。由此存在三类结果：

- 请求排队时读到 A 的旧值；轮到确认前打开 B，批准后 writer 修改 B。审计 old 来自 A，实际修改的是 B。
- 后台 Agent A 调用 `session.*`，当用户正在看 B 时，目标是 B；没有前台聊天则失败。源码确实有这个前台语义，本项把它记录为与后台 runtime 调用身份不一致的接口缺口，不把两个 session 当作两个独立 OS 安全主体。
- [ConfigBridge.kt:936](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/config/ConfigBridge.kt:936) 撤销只重新解析字段路径并写 oldValue，没有恢复原记录的目标会话；离开聊天后撤销可能报无 active session，在另一个聊天撤销则可能写入那个聊天。

CLI 请求本来有 request.sessionId；[ConfigOffloadHandler.kt:134](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/ConfigOffloadHandler.kt:134) 将它传给文件读取，但 cmdSet 在231行又取 args 的 session，仅用作 Bridge 的审计参数，无法绑定 field 的实际目标。审计中的 sessionId 因而也不能保证就是被修改的会话。

方向与待执行验收：在现有 request/session 边界确定一次目标，确认行、writer、审计和撤销使用同一身份；若产品保留“修改前台会话”，也需在请求时捕获并明确展示目标。验收覆盖 A 请求排队→导航 B→批准、无前台聊天的后台调用、从日志撤销 A 的设置；不能靠用户不切页面维持正确性。这不同于已报告 N06 的旧按钮误批准下一请求。

## CB03 — 思考设置写入 DB，不更新运行中的 Agent

[ConfigBuiltins.kt:148](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/config/ConfigBuiltins.kt:148) 只调用 dao.updateThinkingOverride。没有调用 ViewModel 的设置方法、更新 StateFlow 或发出让 VM 重新加载该字段的事件。

相对地，UI 的 [ChatViewModel.kt:1881](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:1881) 先更新 `_thinkingLevel` 再持久化；从 DB 恢复发生于4468行的 loadSession。常规下一轮在 [ChatViewModel.kt:8307](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:8307) 仍传 `_thinkingLevel.value`。runAgentLoop 入口会重读身份和 SessionOverrides，但没有重读 thinkingOverride。进程级 VM 缓存也不会因为用户离开再进入页面就必然新建实例。

因此 CLI 和 audit 可以显示“high 已应用”，运行中的会话随后发起新一轮却继续使用旧的 off。这是持久值与实际请求值分离，不是要求在进行中的请求里强制热切换。writer 的数据流与固定上游相同。

方向与待执行验收：通过已有会话设置入口更新实际状态，或者在明确的下一轮入口刷新 durable override。记录设置前后下一轮的 Provider 请求参数、UI 和 DB，三者一致；进行中回合维持事先规定的语义。

## CB04 — MAX/ULTRA 无法经过公开 schema 写回

[ConfigBuiltins.kt:124](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/config/ConfigBuiltins.kt:124) 只有 off/low/medium/high/xhigh；208—226行的读写转换却包含 max/ultra。Bridge 在 [ConfigBridge.kt:482](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/config/ConfigBridge.kt:482) 先验证 schema，ClosureField.write 也会验证一次。

会话保存 MAX 后 get 可以返回 `max`，set 同样值却在到达 writer 前失败。原值是 MAX 的改动，之后 audit-revert 也无法把旧值写回。固定上游同样存在这个五级 schema 与七级转换的差异。这与 MT10 的 contextWindow 范围差异是不同字段及注册根因。

方向与待执行验收：注册表表达全部现役等级，再由实际模型能力限制生效值。逐级读回→原值写回，并撤销从 MAX/ULTRA 到低等级的改动；非法 token 仍需拒绝。

## CB05 — 模型 entry ID 直接插入 JSON

[ConfigBuiltins.kt:187](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/config/ConfigBuiltins.kt:187) 使用三引号字符串插入 uuid，没有 JSON 转义。当前 ID 已非只能含安全字符的随机 UUID：[ProviderConfigMapping.kt:268](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/db/ProviderConfigMapping.kt:268) 定义为 instanceId/modelId；[ProviderRepository.kt:742](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/ProviderRepository.kt:742) 将配置 ID 规范为该形式。

[ModelsCollection.kt:76](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/config/collections/ModelsCollection.kt:76) 只要求 model_id 非空，不禁止引号或反斜杠。已存在的自定义模型 ID 含双引号时，选择它会把无效 JSON 写入会话；含反斜杠时还可能被 JSON 解码为另一字符串。reader 捕获错误返回空，恢复绑定也解析不到原 entry。此处不是 shell 注入，也不需要真实模型服务接受该名称。

项目已有 [ModelBinding.kt:34](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/model/ModelBinding.kt:34) 的 encodeEntry，正常 UI 使用它。上游仍保留相同手拼代码，本地持久 ID 规则使 modelId 字符直接进入这个位置。

方向与待执行验收：复用现有 JSON 编码接口。以虚构自定义名称含引号／反斜杠／点号的 entry，确认 CLI 选择、读回与会话重载都指向同一个 ID；不需要发起付费模型请求。

## CB06 — 语言字段没有执行应用动作

[ConfigBuiltins.kt:280](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/config/ConfigBuiltins.kt:280) 是 PrefsStringField；[PrefsFields.kt:153](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/config/fields/PrefsFields.kt:153) 仅写 SharedPreferences。

UI 在 [AppearanceScreen.kt:515](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/AppearanceScreen.kt:515) 同时更新 LocaleManager.applicationLocales，旧系统则531行 recreate。MainActivity 的偏好监听 [MainActivity.kt:526](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/MainActivity.kt:526) 没有 KEY_LANGUAGE；applySavedLanguage 在启动入口调用，LocaleWrap 也仅在 attachBaseContext 处理旧系统。

通过 CLI 改语言会得到成功和新值，但当前 Activity 的资源语言继续旧值，通常要到重建才应用。固定上游 Builtins 同样仅注册 PrefsStringField。本项不重复资源缺翻译，也不因语言选择不同去改 namespace。

方向与待执行验收：复用 UI 已有应用路径，在主线程完成系统语言操作或必要的 Activity 重建。对 API 33+ 与较旧系统分别核对 CLI 返回后当前页面和新页面的语言，包含恢复“跟随系统”。

## CB07 — Live Updates 动态权限被缓存

[ConfigBuiltins.kt:477](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/config/ConfigBuiltins.kt:477) 根据注册时探测决定是普通 field 还是固定 UnavailableField。[ConfigRegistry.kt:116](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/config/ConfigRegistry.kt:116) 初始化已有实例便返回，没有重注册路径。

但 [DynamicIslandSupport.kt:14](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/service/DynamicIslandSupport.kt:14) 的能力包括可随时改变的系统授权；UI 在 [BackgroundSettingsScreen.kt:119](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/BackgroundSettingsScreen.kt:119) 每次 resume 重新探测。启动时未授权、后来授权，UI 可以用而 CLI 持续 feature_unavailable；启动时已授权、后来撤销，CLI 仍把开关当作可正常应用。

实际服务另有动态检查，因此本项不宣称可以绕过 Android 系统授权。问题是公开配置可用性长期过时。固定上游也在注册时包装且 singleton init 早退。

方向与待执行验收：在已有 field 可用性读取／写入时查询动态条件，无需为了一个条件重建整个注册表。在符合条件设备上切换 Live Updates 授权，不杀进程，核对 UI、get/set 与服务实际能力；设备行为本轮未验证。

## CB08 — 自动聚焦开关没有行为调用方

[ChatInputSettingsScreen.kt:130](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/ChatInputSettingsScreen.kt:130) 提供“回复后自动聚焦”开关；[ConfigBuiltins.kt:351](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/config/ConfigBuiltins.kt:351) 对模型说明开启后回复完成会自动弹键盘。

生产 Kotlin 中相关键的搜索只命中定义、设置页读写、配置注册和备份清单。没有回复结束或输入框的运行时读取；Builtins 342—348行也明确承认 Android 仅在新聊天时聚焦，将此字段注册为 parity。这个历史说明不能让当前 UI/CLI 的行为承诺成立。固定上游 Builtins 保留同样字段。

方向与待执行验收：根据产品需要接入已有聚焦机制，或诚实表达当前不可用。验收在已有聊天回复结束时分别开启／关闭，观察实际 focus 与键盘，保留视频及设置值；本轮未启动模拟器。

## EV01 — 输入值静默丢失字符

[EnvironmentVariablesScreen.kt:349](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/EnvironmentVariablesScreen.kt:349) 提供多行值输入，没有值字符错误提示。[EnvVarRepository.kt:62](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/EnvVarRepository.kt:62) 却仅保留 ASCII 可打印字符与 tab，add/update 在80、106行调用后仍返回 true。

例如粘贴 `line1\nline2` 保存后成为 `line1line2`；中文路径、非 ASCII 密码则被删掉对应字符。多行 PEM、证书或普通 Unicode 值会被破坏，编辑框和成功反馈不提醒实际值改变。过滤不可见控制字符的初衷不等于所有非 ASCII 和换行都无效。

固定上游 EnvVarRepository 相同，是继承的持久化缺口。方向与待执行验收：只拒绝确实不能安全传递的字符并明确告知，或者在现有转义／注入边界支持原值。使用虚构值包含中文、LF、tab、引号与反斜杠，比较输入、存储和 guest 接收的字节；非法值要明确拒绝，不能显示成功后悄悄改值。

## EV02 — 编辑值的异步加载与保存竞争

[EnvironmentVariablesScreen.kt:276](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/EnvironmentVariablesScreen.kt:276) 在编辑时先将 valueText 设为空，282—286行后台读存储，完成后无条件赋值。canSave 在294行只看名称有效／重复，不依赖读取完成；382—394行把当时的 valueText 直接提交 update。

两种可达次序：加载未完成就输入新值，晚到的读取覆盖新输入；加载未完成时只改备注并立即保存，把空值写回真实秘密。缺少 loading/loaded 状态和草稿版本保护。读取异常也没有形成禁止保存的错误状态。

固定上游 [EnvironmentVariablesScreen.kt:245](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/ui/settings/EnvironmentVariablesScreen.kt#L245) 直接在 remember 初始化里读取值，没有本地这段异步等待窗口。异步改造有性能目的，但目前缺少数据保护。本项是新的环境变量编辑调用链，不把此前记忆文件／系统提示词的类似窗口当作已经覆盖这里。

方向与待执行验收：明确初次读取状态；读失败或未完成时不得把空占位值当作原值保存，晚到结果不能覆盖已编辑草稿。未来通过公开编辑页延迟读取，用 canary 验证立即编辑、立即保存与读取失败三种次序，不使用真实凭据。

## EV03 — 元数据失败被当作成功和空仓库

[EnvVarRepository.kt:139](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/EnvVarRepository.kt:139) 用 writeText 直接覆盖元数据，151行捕获异常只写日志。add/update 先更新内存和 encrypted prefs，再调用它，最后仍返回 true；delete 同样不回滚。UI 看到 true 就关闭编辑页，CLI 删除也认为动作完成。

[EnvVarRepository.kt:156](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/EnvVarRepository.kt:156) 读到不完整／无效 JSON 时只记日志，保留初始空列表，没有“加载失败，禁止写入”状态。随后 add 会用只有新项的列表覆盖原文件。encrypted prefs 中旧值可能还存在，但元数据已丢失；allAsDict 仅枚举元数据，所以旧变量不会注入 guest。

这是明确的失败窗口，不宣称普通保存每次都会失败，也不将 SharedPreferences.apply 的常规异步机制单独计成缺陷。固定上游保留相同 writeText/catch 与加载行为。

方向与待执行验收：复用已有可靠文件写入方案，让元数据错误传播并阻止错误成功反馈；不可读状态不得自动成为可覆盖的空仓库。验证磁盘写失败、损坏元数据、失败后重启与继续新增；记录元数据／虚构 encrypted 值的一致性。不得拿修复 EncryptedPrefsFactory 代替这一层。

## EV04 — CRUD 与备注更新缺少业务事务边界

[EnvVarRepository.kt:73](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/EnvVarRepository.kt:73) 的检查、列表 read-modify-write、encrypted prefs 与元数据写入没有共同锁；update/delete 也是如此。StateFlow 的单次赋值线程安全不能保证整段业务操作原子。

UI 在 IO 协程做保存／删除，同时 guest 配置可修改备注或删除同一变量。两个操作读取同一旧列表再各自赋值，会覆盖另一操作；两个 saveMetadata 还可同时打开同一个文件。重复点击保存期间也没有 in-flight 禁用。

[EnvVarsCollection.kt:109](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/config/collections/EnvVarsCollection.kt:109) 为改备注先读取秘密，再调用 update 写回它。若期间 UI 已更新秘密，备注操作会把读取到的旧秘密写回。这里并不需要 CLI 能直接设置隐藏 value。

这是源码确认的可达并发风险，未测量触发概率，未声称做过压力复现。固定上游仓库及备注实现也保留相同操作边界；本地 UI IO 调用进一步使其能与其它写入交错。

方向与待执行验收：复用现有仓库同步边界，让业务级 read-modify-write 与落盘串行；备注只改备注，避免重新读取和提交秘密。未来交错执行 UI 改 canary 值／CLI 改备注、UI 删除／新增、快速双保存；对比最终元数据、秘密值、列表和返回状态，保留可重复的操作顺序。

## 去重、对照与证据范围

本轮不重复记为新问题：N01 的拒绝／超时审计泄漏，N02 的批次列表旧快照，N03 的集合删除撤销重建丢字段，N06 的旧确认事件；MT11 的批次错误回复以及 MT12 的权限撤销窗口。PromptStore 文件失败、键盘避让、备份格式等既有发现也没有重新计数。

上游对照使用本地固定 Git 对象：CB01 明确存在上游脱敏调用而本地漏接；EV02 上游同步初始化、本地异步改造留下窗口。其它项可见共同注册／仓库缺口；CB05 需同时考虑本地 ID 已规范为 provider/modelId 的事实，不直接把上游随机 UUID 场景当成同一可达输入。

新全文阅读 22 个文件、4,081 行：ConfigBuiltins、ConfigError、ConfigAuditEntry、ConfigAuditLog、PendingConfigChange、PrefsFields；EnvVarPrivacyStore、EnvVarRedactor、EnvVarRepository、ModelBinding、LocaleWrap、DynamicIslandSupport、ToolResultBudget、ChatViewModelStore；ConfigConfirmNotifier、ConfigAuditScreen、ConfigConfirmDialog、EnvironmentVariablesScreen；BackupEnvVarWireFormatTest、ChatConfigPrefsBindingTest、UnavailableFieldTest、ConfigConfirmationGateNotifyTest。读取已有测试只作为证据，没有执行或编写测试。

ConfigBridge、EnvVarsCollection、ToolRegistry、ProviderRepository 等复查关联链；MainActivity、AppearanceScreen、BackgroundSettingsScreen 及大型 ChatViewModel 只登记实际片段，没有冒充全文。

累计全文阅读 **329/1546 个纳入文件，69,458 行**。剩余文件继续按台账审阅，目标仍未完成。设备上的权限刷新、键盘、实际 Provider 请求与并发次序均为待验证内容。本报告及所有待执行验收说明不构成运行设备、测试或云资源的授权。
