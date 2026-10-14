# 会话Store、聊天链接与退出时空会话清理审阅

日期：2026-10-07，固定远端HEAD `a94f98c97d0c478eff8530e9e7758d3b93216b83`。独立新报告和台账，保留旧189份报告及全部旧台账。新增全文5文件、636行，追加5文件调用链片段；无代码修改、测试编写/执行、构建、模拟器、adb/Root/设备或云资源操作。

本轮没有新增独立编号：核实F01已修的缓存归属，以及DB09同类竞态在页面退出入口的残留。旧报告文件保持原文，本报告明确限定此前“已修”的适用范围。

## DB09方向残留：启动清理已修，页面退出仍先数再删

[ChatRepository.kt:153](https://github.com/Slacker-LLC/minis-for-android/blob/a94f98c97d0c478eff8530e9e7758d3b93216b83/src/android/app/src/main/java/com/openminis/app/data/repository/ChatRepository.kt#L153)pruneEmptyDrafts使用[ChatDao.kt:90](https://github.com/Slacker-LLC/minis-for-android/blob/a94f98c97d0c478eff8530e9e7758d3b93216b83/src/android/app/src/main/java/com/openminis/app/data/db/ChatDao.kt#L90)deleteSessionIfUnusedDraft，在同一DELETE里核对无消息、无标题/文件夹/Bot/source/pin。旧DB09启动清理的确定根因已修；不撤回该具体入口的修复结论。

但[ChatScreen.kt:666](https://github.com/Slacker-LLC/minis-for-android/blob/a94f98c97d0c478eff8530e9e7758d3b93216b83/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatScreen.kt#L666)页面dispose调用cleanupIfEmptyOnExit。[ChatViewModel.kt:10194](https://github.com/Slacker-LLC/minis-for-android/blob/a94f98c97d0c478eff8530e9e7758d3b93216b83/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt#L10194)该方法只在启动IO coroutine前检查streaming/附件，随后读session、读messageCount；count为0便[ChatViewModel.kt:10214](https://github.com/Slacker-LLC/minis-for-android/blob/a94f98c97d0c478eff8530e9e7758d3b93216b83/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt#L10214)调用普通chatRepository.deleteSession，没有提交时“仍为空”的条件。

静态交错：页面离开时检查未streaming，清理读到messageCount=0；相同会话被重新打开或收到后台首条消息，保存先提交；旧清理随后执行无条件deleteSession，级联删除新消息并触发外部收尾。提前检查streaming不能保护后来开始的回合；提前检查count不能保护后来的数据库提交。Bot/委派身份有显式保护，反例限定普通会话，不声称所有后台Bot都会遭遇删除。

这是相同“空候选变成有内容后仍删除”的问题方向，不新开重复编号。原DB09的启动入口已经闭环，产品退出入口尚未闭环；此前报告将修复概括为“草稿清理”时应读作已核实的Repository入口，不能推及所有UI清理。

记录方向：提交删除时重新保证业务空闲/为空条件，并只在确实删除后释放Store及外部资源；复用现有DAO/Repository边界，不能直接套启动专用条件而忽略退出清理允许的会话类型。后续授权验收在页面退出count之后暂停、保存首条消息再恢复清理，记录两事务顺序、最终会话/消息/目录/VM状态；本轮未执行。

固定OpenMinis[ChatViewModel.kt:16930](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt#L16930)也先数再进入SessionDeleter，但本轮只读调用处，未审完其deleteTree条件，故不能宣布上游已修或同样必现，也不能移植旧存储路径作答案。

## F01缓存归属已收敛，删除完整性仍有独立边界

[HeadlessChatRunner.kt:48](https://github.com/Slacker-LLC/minis-for-android/blob/a94f98c97d0c478eff8530e9e7758d3b93216b83/src/android/app/src/main/java/com/openminis/app/debug/HeadlessChatRunner.kt#L48)providerFor现在每次取ChatViewModelStore.ownerFor，不再另持session→ViewModelProvider缓存。[HeadlessChatRunner.kt:789](https://github.com/Slacker-LLC/minis-for-android/blob/a94f98c97d0c478eff8530e9e7758d3b93216b83/src/android/app/src/main/java/com/openminis/app/debug/HeadlessChatRunner.kt#L789)forget直接调用Store.release，[AgentRunner.kt:155](https://github.com/Slacker-LLC/minis-for-android/blob/a94f98c97d0c478eff8530e9e7758d3b93216b83/src/android/app/src/main/java/com/openminis/app/agent/AgentRunner.kt#L155)AgentRunner再转发同一入口。

会话列表删除[SessionListViewModel.kt:395](https://github.com/Slacker-LLC/minis-for-android/blob/a94f98c97d0c478eff8530e9e7758d3b93216b83/src/android/app/src/main/java/com/openminis/app/ui/sessions/SessionListViewModel.kt#L395)和RPC删除[ChatMutationMethods.kt:423](https://github.com/Slacker-LLC/minis-for-android/blob/a94f98c97d0c478eff8530e9e7758d3b93216b83/src/android/app/src/main/java/com/openminis/app/debug/ChatMutationMethods.kt#L423)均最终释放该Store。release解析draft别名、删除指向canonical的别名、清ViewModelStore。因此旧F01“两缓存各清一半”的具体原因已处理。

Store.release本身没有数据库/目录删除职责；RPC先cancel再删，列表直接删后释放。这里没有验证cancel已完全退出，也没有审核整个会话资源树和所有删除入口，不把缓存修复扩大为删除生命周期全部完成。

## Store的实际持有行为

每session一个ViewModelStore，页面退出通常保留VM，使Agent任务可继续。draft promotion把Store移到真实session ID并保留draft别名；新页面查draft或真实ID获得同一个Store。clearActiveSession使用StateFlow.compareAndSet，旧页面退出不会抹掉新页面的不同session claim。

mountedSessionId保留页面原始ID，activeSessionId通过别名解析。release没有主动清空activeSessionIdInternal；删掉正在显示的会话后前台ID如何退出，需结合完整导航和挂载生命周期，暂列候选，不据此新增确定配置越界。

Store没有按idle状态淘汰已访问VM，ownerFor/rename/release有同步；部分alias读取以及pendingTransfer消费没有同样锁。当前转移调用位于UI主线程，不能仅凭Volatile读写就宣称生产并发重复消费。rename目标覆盖/别名链需要真实可达的重复promotion证据，不因通用接口可传任意ID强行立项。

Move-to的stash有目标session匹配、别名解析和300秒TTL，不能重报“任意会话抢走转移”。接收先清目标普通附件、拼接文字、加入源附件；源保存stash后清文字/附件再导航。旧粘贴缓冲遗漏问题不在此处重新计数。单槽覆盖、导航失败与过期恢复仍需整个产品流证据。

## 聊天链接的guest/host边界

[ChatLinkResolver.kt:55](https://github.com/Slacker-LLC/minis-for-android/blob/a94f98c97d0c478eff8530e9e7758d3b93216b83/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatLinkResolver.kt#L55)canonical guest路径先用WorkspaceFileClient按当前session staging；对应ChatScreen实际传viewModel.currentSessionId及context。工作区、附件、offloads/browser等session路径缺session时拒绝stage，不回退canonical guest到全局bindMounts。SAF mounts等其它路径仍走RuntimePathRegistry。

百分号解码保留字面+，先解析一次，只有无法找到时再尝试第二次；query先剥离再解码，编码的问号仍可成为文件名。minis已识别deep link交由MainActivity，其它外部scheme交ExternalSchemeHandler；不是所有链接都进WebView。

file://分支使用host File，未通过guest staging；绝对host路径可读范围仍取决于App权限，不能把这个UI点击入口等同模型file_read授权。本轮没有完整HTML/预览/分享调用链证据，不直接计为凭据外泄或Root越权。

guest缓存名含session+路径SHA-256及清洗后的basename，每次点击重新readToFile，不是“命中缓存就不刷新”。本类没有清理chat-link-media或总缓存预算；实际App缓存清理与文件大小限制需其它调用方核对，不仅凭目录存在宣称无限磁盘消耗。取消重新抛出，其它staging错误降级继续路由；没有把普通IO失败当guest读取成功。

## 三份已有测试的证据边界

ChatViewModelStoreTest58行3例覆盖创建/复用/释放、未知释放不创建、draft重命名及按真实ID释放。它不调用真正VM、删除数据库、页面dispose或并发首条消息，不能证明DB09退出入口安全。

FallbackEntryIdentityTest86行4例只对私有Entry数据及list.find说明modelId有歧义、entryId稳定、排序不影响和保存正确ID。它没有调用生产fallback或持久化；本轮另读[ChatViewModel.kt:7470](https://github.com/Slacker-LLC/minis-for-android/blob/a94f98c97d0c478eff8530e9e7758d3b93216b83/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt#L7470)真实fallback按nextCandidate.entryId查条目并保存绑定，不能单靠这个自建测试模型证明生产入口全覆盖。

InternalBridgeFilterTest65行6例调用ChatMessage.isInternalBridge，覆盖新/旧精确文本、trim、非assistant拒绝、普通内容与仅提及interruption不误判。它测试真实classifier，但不执行VM发布/UI sink；这些边界不等于端到端消息隐藏验证。全部测试只阅读，未运行或新增。

## 全文与覆盖

| 本轮全文 | 行数 |
|---|---:|
| [ChatLinkResolver.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/a94f98c97d0c478eff8530e9e7758d3b93216b83/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatLinkResolver.kt#L1) | 215 |
| [ChatViewModelStore.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/a94f98c97d0c478eff8530e9e7758d3b93216b83/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModelStore.kt#L1) | 212 |
| [ChatViewModelStoreTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/a94f98c97d0c478eff8530e9e7758d3b93216b83/src/android/app/src/test/java/com/openminis/app/ui/chat/ChatViewModelStoreTest.kt#L1) | 58 |
| [FallbackEntryIdentityTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/a94f98c97d0c478eff8530e9e7758d3b93216b83/src/android/app/src/test/java/com/openminis/app/ui/chat/FallbackEntryIdentityTest.kt#L1) | 86 |
| [InternalBridgeFilterTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/a94f98c97d0c478eff8530e9e7758d3b93216b83/src/android/app/src/test/java/com/openminis/app/ui/chat/InternalBridgeFilterTest.kt#L1) | 65 |

片段：ChatScreen630—709、2268—2315、6178—6220；ChatViewModel3080—3140、7450—7487、10188—10229；SessionListViewModel379—420；ChatMutationMethods412—430；HeadlessChatRunner45—72、785—793。组合输出截断处已分别补读，不把这些大文件计为全文。Repository/DAO/AgentRunner已有全文，仅复核相关片段。

上游只读固定HEAD`b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，本轮读取Store445—526及VM16924—16952，不fetch，不计本库覆盖，也不把其额外缓存池管理整体照搬。

新台账：[2026-10-07-code-review-coverage-a94f98c9-chat-store-links.json](/home/jiale/projects/minis-for-android/docs/archive/2026-10-07-code-review-coverage-a94f98c9-chat-store-links.json)。累计全文 **1365 / 1662文件、227599行**；partial13、尚未登记284、excluded124。全库通读未完成。

交付校验旧189份报告/全部旧台账hash、本地/固定远端/上游HEAD、tracked/staged代码diff、1786快照blob/SHA-256/字节/行数及引用行号。仅新增本报告与台账。
