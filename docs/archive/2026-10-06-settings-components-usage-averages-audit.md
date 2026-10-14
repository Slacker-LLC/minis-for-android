# 设置基础组件、语音镜像详情与用量均值静态审查

日期：2026-10-06。固定本仓HEAD `d64e7c06d8159be90737dd9ba03dd9bd5855f339`。本轮新增全文8文件、1463行，确认1项统计显示问题。只做静态阅读，没有修改产品源码，没有运行测试、构建、模拟器、adb、Root、设备、云或模型请求，没有读取凭据。新建独立报告，旧报告不改。

## UA01 / P3：日均与会话均值遗漏缓存token，与总用量口径不一致

真实入口为 [AppNavigation.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/navigation/AppNavigation.kt:1442)。[UsageStatsScreen.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/UsageStatsScreen.kt:54) 将totalInput定义为普通inputTokens、cacheReadTokens和cacheCreationTokens之和。页面总输入、第225行模型概要及缓存命中率均使用该完整输入量。第153—156行分别累计三类输入，并未提前将缓存合入普通输入。

但 [UsageStatsScreen.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/UsageStatsScreen.kt:269) 日均，以及第272行会话均值，分子只有inputTokens + outputTokens，省略缓存读取和创建。这两行标签仅为“日均”“会话均值”，没有注明“仅非缓存token”。确认的是同页统计口径差异，不是缓存命中率公式错误，也不把token当人民币费用。

可复核的静态算例：同一模型、同一天、同一会话，普通输入100、缓存读取900、缓存创建0、输出100。总输入1000，模型概要1000 / 100；完整输入输出合计1100，但两项平均都显示200。缓存比重越高，差距越大。算例由现有四字段和除数直接推导，不是运行结果，未向Provider发请求。

方向：让均值使用totalInput + outputTokens，或明确标注其只统计非缓存部分。是否将日均定义为活跃日还是日历区间，是另一个产品规则：当前distinctDays明确定义为有记录的日期，本轮不把没有日历区间当另一缺陷。

固定OpenMinis对照：仅只读 `/tmp/openminis-review-20261004` Git对象HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，未fetch。上游UsageStatsScreen第347、350行使用同样省略缓存的分子，此项是继承问题；不是本地Direct Ubuntu改造产生，也不要求恢复旧runtime。

## 用量归属与测试边界

[ChatDao.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/db/ChatDao.kt:331) allUsageRecords使用LEFT JOIN、COALESCE消息快照与会话模型，hasSnapshot来自消息model_id；没有直接丢掉所有无session行。UsageStatsScreen第144—148行按provider类型、instance和model ID分组快照，旧行单独legacy key，第159行累计estimatedRows。因此不能说同名模型一定合并两个endpoint，也不能说旧会话模型变化一定给所有记录重归属。

classifyAttribution定义四种归属；UsageAttributionTest全文150行只验证helper与providerDisplayName。实际屏幕按estimatedRows显示“实测/估计”，没有调用四态helper；这属于测试与UI表达覆盖边界，未知模型仍显示unknown key，不足以另判实际归属错误。测试没有验证本轮均值分子，未运行或新增测试。

LaunchedEffect(Unit)只查询一次全量usage，并在协程恢复后解析和聚合；后台新增消息不实时更新、记录很多时可能增加主线程工作。前者需明确实时更新需求，后者没有真实耗时或卡顿证据，保留为候选，不增加确认数量。JSON解析失败被跳过，实际数据库生成合法JSON，未用任意恶意字段制造生产缺陷。

## 设置基础组件：实际入口与未计数候选

SectionDropdown全文139行：enabled控制打开菜单，但菜单已打开时条目回调不另查enabled。全main UI检索仅定义，没有生产调用方，不能把候选算作用户正在遇到的禁用绕过。

[SectionTextField.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/components/SectionTextField.kt:114) 自持TextFieldValue，外部value变化时同步内容并把光标移到末尾；同内容的selection/composition变化保留。核对AddProviderScreen第365行、GroupPickerSheet第139行都是直接写回；ModelEntryDetailScreen第208、218行是明确数字过滤，GroupPickerSheet第148行明确截断描述长度。没有据此确认普通输入每个字符都会丢光标或IME状态。外部异步格式化与composition竞争需具体时序，暂不新增缺陷，也不重复此前记忆编辑页的键盘避让问题。

[SwipeRowActions.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/components/SwipeRowActions.kt:137) 按actions列表顺序绘制按钮。实际ProviderListScreen第241—257行传Edit后Delete；与组件注释描述的“首项最近trailing edge”有差异，但尚无产品要求固定次序，不将注释误述当新运行缺陷。点击Delete走instanceToDelete确认状态，未绕过删除确认。动画关闭由协程启动后立即调用action，是否造成具体错误还需目标页面行为证明。RTL与TalkBack行为未实测，不据猜测算Bug。

SectionDesign、components.SettingsSection全文核对尺寸、Surface、padding、divider；没有确认新增行为错误。settings包还存在不同签名SettingsSection，不能只因同名认定冲突。固定上游tree包含本轮五份组件同路径；本轮未宣称所有组件实现都与上游逐行相同。

## 语音镜像详情：开关有通知链

[ShadowVoiceDetailScreen.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/ShadowVoiceDetailScreen.kt:47) collectAsState订阅配置，读取每instance的shadowDisabled；第105行调用Repository。[ProviderRepository.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/ProviderRepository.kt:2021) 写偏好后在configLock内发布新revision，触发Compose重读，因此不能仅因开关值存SharedPreferences就说开关永远不刷新。shadowVoiceProviders第2055行使用该标记过滤。

模型列表来自同instance的audio输入/输出能力，详情使用共享凭据和endpoint，QuickTestEntry只是当前选中项；本轮未执行QuickTest。instance不存在时composition直接onBack，属于可进一步检查的导航副作用候选，但未观察重复pop或确认可重入执行路径，不新增已确认事故。

上游同页第54、55行使用isVoiceInputCandidate/isVoiceOutputCandidate，本地用hasAudioInput/hasAudioOutput。这里只记录代码差异，未把所有音频模型都等同ASR/TTS或断言各模型一定测试错误；实际QuickTestSheet与工厂链仍需通读。

## 覆盖与复核

| 新增全文文件 | 行数 |
|---|---:|
| [SectionDropdown.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/components/SectionDropdown.kt:1) | 139 |
| [SectionTextField.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/components/SectionTextField.kt:1) | 176 |
| [SwipeRowActions.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/components/SwipeRowActions.kt:1) | 187 |
| [SectionDesign.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/components/SectionDesign.kt:1) | 240 |
| [SettingsSection.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/components/SettingsSection.kt:1) | 66 |
| [ShadowVoiceDetailScreen.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/ShadowVoiceDetailScreen.kt:1) | 180 |
| [UsageStatsScreen.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/UsageStatsScreen.kt:1) | 325 |
| [UsageAttributionTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/ui/settings/UsageAttributionTest.kt:1) | 150 |

上述全文输出完整。其它文件只片段核对，不升级其覆盖。累计全文 **1244 / 1546文件、238222行**；partial13，not_registered289，excluded124。全仓通读尚未完成。

完成时核验全部1670 tracked路径SHA-256、字节数和正数文本行数，本仓与上游固定HEAD、tracked/staged空diff、此前115份报告哈希、新报告文件链接行号及全文批次总计。只新增本报告并更新覆盖台账。
