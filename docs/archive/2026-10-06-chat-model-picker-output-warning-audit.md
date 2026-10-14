# 聊天模型选择列表与混合输出警告静态审查

日期：2026-10-06。本仓固定HEAD `d64e7c06d8159be90737dd9ba03dd9bd5855f339`。新增全文4文件、1044行，确认1项P3选择警告问题。仅静态阅读，没有改产品源码、运行测试/构建/模拟器/adb/Root/设备/云/模型，没有调用刷新或读取凭据。独立报告，旧报告不改。

## CMP01 / P3：同时支持文本的混合输出模型被归入非文本警告

真实入口 [ChatScreen.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatScreen.kt:6518) nonTextLabelFor读取outputModalities，只要包含image/audio/video任一种就返回该模态标签，没有排除同时包含text的模型。第6537—6542行根据返回值设置pendingNonTextSelection，随后弹出“该模型可能无法作为Agent使用”的non_text警告，并建议选择文本输出模型。

静态反例：合法ModelEntry的outputModalities为text、audio。列表点击后label=audio，进入非文本警告；然而 [LLMModel.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/model/LLMModel.kt:104) 现有isTextOutput明确只要规范化输出包含text即可视为文本输出。text、image同样被分类为image。该项只确认文本能力的分类错误与误导性选择确认，不以支持文本推断支持工具调用，也不保证所有混合输出模型都能运行Agent。

真实中文资源 [strings.xml](/home/jiale/projects/minis-for-android/src/android/app/src/main/res/values-zh/strings.xml:1241) 明示“建议为会话选择文本输出模型”。用户确实已选支持文本的模型时仍收到该建议。对纯image/audio/video模型警告仍有意义，不能为消除误警告直接删除整个确认边界。

数据不是不可达的虚构类型：ProviderRepository第2806行读取原生outputModalities数组，模型详情overrides与ModelEntry有效model支持模态列表；未读取用户真实配置或调用模型API。ChatScreen第6565行允许“仍要使用”，所以本项为P3警告分类，不能称模型被强制禁止或绑定永久失败。

方向：先沿用模型已有isTextOutput/规范化输出判定，区分纯非文本与文本混合输出，再根据真实Agent能力提示。不要用输入模态推导输出能力。未修改实现。

## 列表选择、刷新与Quick Test核对

ChatModelPickerSheet全文920行。当前ModelPickerSheet只显示启用instance下未隐藏entry，并按displayName或id模糊过滤；当前文件没有分组选择、思考档位或自动朗读开关，相关行为由其它调用/组件管理。本轮不凭旧import或注释补出不存在的交互。

ChatScreen第5636行菜单打开真实sheet，第6531行接入，普通选择进入ChatViewModel.selectEntry，第5074—5091行检查entry/instance/usableApiKey、创建Provider、持久binding、应用session defaults并记lastUsedEntryId。对缺少可用凭据的entry setter会静默返回，而父层仍关sheet；缺少真实不可用配置提示契约，本轮保留反馈候选，不把它重复算作此前keyless刷新/Quick Test缺陷。

后台stale刷新只在组件LaunchedEffect中发生，本轮仅读代码，没有实例化页面或触发网络。刷新失败、缺凭据及模型目录竞态已在此前Provider报告记录，不重复编号。Quick Test按entry.id key重建，防止从另一个entry沿用测试状态；QT01/QT02的凭据/voice参数问题仍由共用QuickTestSheet负责，此处不重复计数。

provider初始全部折叠，折叠summary点击明确选择代表entry、展开控制独立。搜索只过滤模型名/id，不匹配provider名字，是当前范围，不据此推导搜索损坏。搜索期间仍沿用collapse state，因此多个结果不会自动全部展示；有Show N models入口，不能说搜索命中的其它模型无法访问。

展开provider的所有entries放在单个LazyColumn item内，用Column/forEach生成，模型数量多时失去逐行懒加载。这是明确布局结构，但卡顿、内存峰值与实际目录规模需要测量，保留性能候选。固定42dp搜索框大字体可能限制空间，未做界面验证不新增截字故障。ModalityBadge使用FlowRow避免简单水平拥挤，不能只凭多个badge推断文字必然竖排。

## 绑定、思考等级与引导wrapper

ModelBindingTest全文30行，检查group/entry编码形状和缺字段/未知type fail-closed；它不验证真实session写入、删除entry后的恢复或selectEntry失败反馈，未运行。ThinkingLevelTest全文74行，未知token降级XHIGH、序列化coerce未知maxThinkingLevel为null，有已知值roundtrip；注释称“最高已知等级”与MAX/ULTRA枚举可能不一致，但既有实现显式约定XHIGH降级，本轮不按注释重写行为或重复已有thinking报告。

OnboardingModelSelectionScreen全文20行，只有ModelSelectionStep wrapper，onComplete/onBack都回调onBack，实际步骤由共用ModelSelectionStep负责；不能把wrapper没绘制模型列表误报未实现。该wrapper不运行或写测试。

## 固定OpenMinis对照

只读 `/tmp/openminis-review-20261004` Git对象HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，未fetch。上游ChatScreen第7610—7618行的nonTextLabelFor同样先匹配image/audio/video、不排除text，CMP01为同源判定缺口。其ModelPickerSheet还接受groups/selectedGroupId参数，provider展开也以section item组织；不能依据上游参数要求本地恢复旧group身份，当前绑定迁移与slot规则以本地为准。

一次合并搜索输出截断，nonTextLabelFor、本地isTextOutput与真实警告资源已分别重读；搜索到不存在的旧metadata路径后用现役ProviderRepository数组解析定位，未把路径不存在当元数据功能缺失。

## 覆盖与复核

| 新增全文文件 | 行数 |
|---|---:|
| [ChatModelPickerSheet.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatModelPickerSheet.kt:1) | 920 |
| [OnboardingModelSelectionScreen.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/onboarding/OnboardingModelSelectionScreen.kt:1) | 20 |
| [ModelBindingTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/data/ModelBindingTest.kt:1) | 30 |
| [ThinkingLevelTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/data/ThinkingLevelTest.kt:1) | 74 |

新增全文输出完整，picker分1—310、311—625、626—920读取。ChatScreen/ChatViewModel仅相关片段复核，不升级partial。

累计全文 **1299 / 1546文件、249467行**；partial13，not_registered234，excluded124。全仓通读尚未完成。完成时核验全部1670 tracked路径SHA-256、字节数、正数文本行数，本仓/上游固定HEAD、tracked/staged空diff、此前125份报告哈希、新报告链接行号与全文批次总计。只新建本报告并更新台账。
