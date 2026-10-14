# 助手主页浏览器池生命周期与模型选择刷新静态审查

日期：2026-10-06。本仓固定HEAD `d64e7c06d8159be90737dd9ba03dd9bd5855f339`。新增全文2文件、978行，确认1项P2与1项P3。仅静态阅读，不改产品源码、不写或运行测试、不构建/启动模拟器/adb/Root/设备/云/模型，不实例化页面、联网刷新模型或读取凭据。独立新报告，旧报告不改。

## AHP01 / P2：助手主页每次创建一个不能随页面结束的浏览器池，后台定时任务持续累积

[AssistantHomeScreen.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/home/AssistantHomeScreen.kt:149) browserTabPool通过remember直接构造BrowserTabPool(context)，无论用户有没有点击浏览网页。整个页面没有DisposableEffect/onDispose，也没有把pool交给可清理的持有者；页面组合被移除后remember本身不负责取消池内部协程。

[BrowserTabPool.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/BrowserTabPool.kt:188) 池新建独立CoroutineScope(SupervisorJob()+Dispatchers.Default)，不属于页面的rememberCoroutineScope。[BrowserTabPool.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/BrowserTabPool.kt:223) init立即启动while(isActive)循环，每60秒delay后调用该实例evictIdleTabs。全文查找没有evictionJob.cancel、evictionScope.cancel或dispose实现，相关字段是private，主页也没有可调用的关闭入口。活动的delay续体/协程工作保留调用对象，移除Compose组合不会令该独立Job自动结束。

现役入口 [AppNavigation.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/navigation/AppNavigation.kt:578) 明确调用AssistantHomeScreen；当主页是start destination，第571行onOpenSessions会popUpTo主页inclusive=true，足以产生页面销毁；从session list再次打开主页将新建pool。每一代pool都留下定时循环，即使一次也没有创建浏览器tab。若实际打开tab，WebView/下载等资源还需收尾，但本项不以这些额外资源推断测得泄漏大小。

静态可复核操作序列：进入助手主页 → 进入会话列表并移除主页 → 再进入主页，重复N次。得到N个独立周期任务，而用户只使用一个当前页面；退出/销毁Activity也没有结束这些进程内scope的代码。这里是由无限周期与缺少生命周期所有者支持的资源保留，不是“没有WebView.destroy便一定泄漏”的泛化，也不是已测OOM或具体唤醒次数。

此前BL06涉及closeTab/idle eviction移除列表而不释放WebView，本项是**页面移除后pool自己的周期Job仍存活**，无tab也成立，因此独立计数。修复方向：给pool建立现役生命周期结束操作，取消定时/子任务并按现有边界释放其页面资源；主页在实际移除时调用。保留同一主页内关闭sheet再打开的复用语义，不以releaseAllTabs（仅改inUse标记）代替销毁。本轮没有实现或启动浏览器。

## ONS01 / P3：自动刷新删除已选模型后，完成按钮仍把消失的ID写回Main槽

[OnboardingScreen.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/onboarding/OnboardingScreen.kt:305) ModelSelectionStep进入时自动对enabled实例launch(IO) refreshModels；第310行selected只是remember保存UUID的mutableStateList，不随config/modelEntries更新清理。allEntries来自实时config，因此刷新可令已勾选的行消失，selected仍保留其ID，计数与enabled=selected.isNotEmpty仍显示“可以完成”。

[OnboardingScreen.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/onboarding/OnboardingScreen.kt:445) 完成按钮直接把当前main与selected合并后调用setSlotEntries，不在提交时验证selected仍存在或仍可选择。[ProviderRepository.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/ProviderRepository.kt:1357) setSlotEntries只做distinct并保存，不校验entry存在。

具体交错：打开真实模型选择页，refresh请求尚在等待，用户勾选目录现有模型A；返回的合法目录删除A并保留其它模型。[ProviderRepository.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/ProviderRepository.kt:1244) replaceEntries会替换该实例条目，只有同baseModel.id存活的模型能沿用prior UUID。第1329—1335行正常收缩分支还会删掉原有slot中的失效引用；此时selected中的A不受清理。用户按完成又把A的UUID重新保存到Main，抵消repository这次清理。为排除suspiciousShrink保护，反例可用两个现有模型A/B、刷新只保留B，existing.size<4，确实走普通prune；无需假设空API响应被接受。

真实可达性：[ChatSplitScaffold.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/navigation/ChatSplitScaffold.kt:881) session list的选择模型动作导航到ONBOARDING_MODELS；[AppNavigation.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/navigation/AppNavigation.kt:1485) route调用OnboardingModelSelectionScreen，再进入共用ModelSelectionStep。新手Welcome/APIKey流程本身无现役调用，不能拿它证明该步骤不可达。

[ProviderRepository.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/ProviderRepository.kt:1205) availableEntries跳过不存在的ID，因此Main不是获得用户选择的A；若Main原本为空且只选A，primaryEntry返回null。[ChatViewModel.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:5055) 新聊天会回退lastUsedVisibleEntry/newestProviderNewestTextEntry，可能选另一模型；不声称整个App必然无模型或崩溃。用户选择的条目已消失后需要明确提示并阻止失效提交，而不是用成功返回页面隐藏情况。

修复方向：按实时可选entry集合协调selected，提交时在现有repository锁与最终config上校验，剔除/拒绝失效ID并给出可恢复反馈。只做UI过滤无法涵盖过滤后到提交之间的目录变化。没有联网构造目录、改变任何真实Main配置或添加测试。

## 全文其它核对

AssistantHomeScreen全文516行：actions/startPage来自StateFlow，2列布局为单卡补Spacer；action picker调用现有AssistantQuickAction.toggle/AssistantHomePrefs。最后一张卡不可删除的纯规则已在此前报告核对，不重新计数。onAnalyzeScreen在导航中仅seed pending ChatAction并创建新draft，没有立即自动发模型请求。

WeChat启动找launcher，找不到或startActivity失败会Toast；没有因同一文案承接两种原因就认定正常安装的微信一定不可见。memory pressure点击同步读取ActivityManager/Debug snapshot，无Root或guest进程聚合；该指标只描述设备/App口径，未实测主线程延迟。Greeting直接取LocalTime但无时钟订阅，跨时段刷新候选归入既有TI01时间显示更新家族，不新增编号。

OnboardingScreen全文462行：全main/java检索OnboardingScreen调用仅见定义，WelcomeStep/ApiKeyStep只被它调用。旧ApiKeyStep用固定providerType_onboarding ID，反复保存可能添加重复instance，但当前未证明有生产入口，因此不作为新产品缺陷。addInstance已自带built-in seed，随后的addEntry对非custom同实例/baseModel.id有去重，不能误报每次保存必然重复模型。

ModelSelectionStep独立可达，上方搜索输入没有imePadding；屏幕/宿主是否通过resize/insets正确避让需完整窗口证据，本轮未运行键盘，也不直接复制旧记忆编辑页UI01结论。整个provider的entries放进一个LazyColumn item内Column，巨大目录测量成本仍需性能证据，不宣称已发生ANR。

## 固定OpenMinis对照

只读 `/tmp/openminis-review-20261004` 固定HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c` Git对象，未fetch。其ui/home tree没有AssistantHomeScreen同路径，所以不能按这页逐行推上游主页有相同问题。上游BrowserTabPool第351—357行同样启动独立idle timer；查找到destroyAllTabs函数并不等于它取消timer，未把“上游有资源销毁helper”当该生命周期问题已消失的证明。

上游OnboardingScreen第215—232行也用remember selected，但没有本地的进入自动刷新；第343—352行完成创建旧ModelGroup，而非本地Main slot合并。因此不能声称上游同样能经本地这条自动刷新交错写坏Main，也不恢复已退役ModelGroup/PRoot/Alpine/minisd方案。ONS01按本仓refresh、selection和repository提交链判断。

## 覆盖与完整性复核

| 新增全文文件 | 行数 |
|---|---:|
| [AssistantHomeScreen.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/home/AssistantHomeScreen.kt:1) | 516 |
| [OnboardingScreen.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/onboarding/OnboardingScreen.kt:1) | 462 |

主页分1—260、261—516，引导分1—235、236—462完整读取，全文无截断。两次混合调用方查询出现截断，相关ProviderRepository、BrowserTabPool及导航证据随后分段重读；未因这些片段登记其全文，也未称上游整份通读。

累计全文 **1329 / 1546文件、258157行**；partial13、not_registered204、excluded124，全仓通读尚未完成。完成时核验全部1670 tracked路径SHA-256/字节数/正数文本行数、两仓固定HEAD、tracked/staged空diff、此前132份报告哈希、新报告绝对链接与行号、全文批次总计。只新建本报告并更新覆盖台账。
