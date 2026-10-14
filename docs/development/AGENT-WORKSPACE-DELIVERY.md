# 工作现场与交付：M4

> 2026-10-14 当前整体行为见 [M 系列整体接线](M-SERIES-INTEGRATION.md)。本页保留此前按日期记录的切片与证据，不能把旧 APK/重复轮次当作本轮源码已通过。

## 存储统计已接入

文件页总量按 Root-owned Shell rootfs、已安装应用和应用数据展示。应用统计复用 Android StorageStatsManager 查询自己的包/用户/volume，不新增权限或 Root 入口；共享资料、技能、记忆、用户目录、保留的会话数据与缓存均由原生应用数据统计覆盖。缓存已包含在 dataBytes 中，不能再相加。[Android 官方统计说明](https://developer.android.com/reference/android/app/usage/StorageStats)。

数据库、当前会话和共享目录等作为应用数据明细展示，不与总量再次相加。目录明细使用现有 WorkspaceFileClient。会话工作区顶层 attachments/offloads/browser 是独立根的别名，sessionTreeSize 只计算一次；真正嵌套的同名目录仍计量。文件总览、会话详情和 app.info 的会话计量复用这一实现。未使用的 Room 草稿计为零，不为统计创建 Agent owner 或 guest 目录。

Root 探测不可用、超时、非零退出或无法解析时保留空值，界面显示“无法测量”。总量缺项时显示已测量下界，不把探测失败当零或声称完整总量。此处仍仅测量 Shell rootfs；不扫描用户授权的外部挂载目录。

AgentStorageE2ETest 从真实模型文件写入、Room、四类 session 根、共享/技能/记忆/用户目录及 Android 原生包统计验证 8 场景。只使用 UUID 专属文件并核查清理成功。工件、实际重复轮次和构建范围见 [.local/test-runs/agent-m4-20261013/RESULTS.md](../../.local/test-runs/agent-m4-20261013/RESULTS.md)。原生页面截图来自真实已安装 Debug 包，不是绘制的示意图。

## 持久成果引用已接入

成果引用直接投影既有任务 completionEvidence 的路径、SHA-256、大小、验收时间与真实来源 session，不新增数据库表或全目录索引。完成任务时来源由执行器提供，不接收模型声明的 session。清除当前 Goal 后仍能分页查看历史成果；旧证据仅使用保留的完成负责人，缺失来源时不猜测当前会话或发起会话。

文件总览与会话文件详情已有成果入口。点击先用现有 WorkspaceFileClient 核验，再打开来源会话中的文件所在目录；同名文件不会从另一个会话替代。内容变化会提示，文件或来源会话删除会显示不可访问，历史完成状态与证据保持原样。不会为读取成果创建 Agent owner 或替代目录。

AgentArtifactsE2ETest 验证真实模型工具调用、Room、工作区与历史证据，10 场景连续 3 轮通过；另回归存储、领域 Provider、工具结果、执行管线与任务循环共 67 项。此切片只证明实际数据链路与编译接线；后续清理切片另从真实 MainActivity 点击成果并核对来源目录。冻结源码、日志和可重复交付文件见 [.local/test-runs/agent-m4-artifacts-20261013/RESULTS.md](../../.local/test-runs/agent-m4-artifacts-20261013/RESULTS.md)。Release 正式打包被既有签名检查拒绝；该冻结源码的独立 R8 与 Debug/Release Lint 已通过，不能当成正式签名安装包。

## 会话分类清理已接入

会话详情按输入附件、工作成果、工具输出、浏览器数据预览字节数，确认框默认只选择浏览器数据。输入包含现有 MediaStore 的日期目录；工作成果计量与删除跳过独立根的顶层别名，嵌套工程同名目录仍属于工作成果。确认后暂停该会话非终态目标，独立调用现有 ExecutionCoordinator.sessionDidTerminate 停止对应 runtime（后台作业不要求聊天 owner 存在），再复用 AgentSessionStore 的关闭/等待边界，等待旧回合收尾后清理所选分类并重新测量。会话和历史证据保留；删除所引用的工作成果后显示不可访问。结束提示记录逻辑文件字节数，不把它说成平台已回收的物理磁盘块。

真实权限拒绝复现了文件信息 API 吞错并把不可读目录当作空目录的问题。SecureFileAccess.info/infoOrNull 现在仅把不存在返回为空，权限与 I/O 错误传给调用方；详情分类、会话列表和总览保留未知标记，不显示完整的零。MediaStore 的统计与删除共用不跟随链接的日期目录遍历，不触碰链接指向的其它会话或全局数据。

AgentCleanupE2ETest 包含真实模型写入/验收、Room、媒体、Android 文件权限、延迟取消收尾写入及再次聊天；另用真实 App UID Android shell 子进程接入现有 ShellJobs/JobRegistry，核对无聊天 owner 时目标进程停止、另一会话进程仍可写入。此进程证据不等于 Root guest 进程组验收。AgentStorageUiE2ETest 从既有 storage deep link 进入真实 MainActivity，点击会话、确认默认分类、验证保留成果并点击打开来源目录；用 Android 平台 UiAutomation 记录截图，不新增生产路由或测试依赖。最终重复轮次、阶段失败与构建结果见 [.local/test-runs/agent-m4-cleanup-20261013/RESULTS.md](../../.local/test-runs/agent-m4-cleanup-20261013/RESULTS.md)。浏览器分类只覆盖现有 session guest browser 根，不代表清空整个 WebView profile 或所有 Android 应用缓存。

## 任务上下文、记忆来源与按需技能

持久任务、验收条件、计划、进度及完成证据每次 Provider 请求从 Room 读取，不依赖压缩摘要；陈旧摘要不得替换当前任务，owner 重建须保留原未知执行检查点。资料指纹表达核验时的事实，不能将历史 SHA 宣称为当前文件内容。

记忆仍复用唯一 MemoryRepository。写入、撤销、替换、删除及编辑器条件保存共用同步边界，避免并发成功记录相互覆盖。新记录保存独立 entry ID、实际 session 来源及 task_fact/user_preference 背景类型；相同正文也按身份及原文条件编辑/撤销，未知旧来源不猜测。深处关键词匹配携带所属记录元数据，页面首行预览跳过注释。GLOBAL.md 保持用户维护/Agent 只读，关闭记忆的会话拒绝写入并排除自动注入和模型工具；近期日志按实际上下文窗口复用既有 MemoryInjectionBudget 限制长行。

技能披露保留三层选择并遵守既有 20 项元数据预算，bundled 也不突破该名额；正文通过共享 file_read 按需读取。成功读取主 SKILL.md 复用已有使用计数，/skills 别名同样识别，子目录同名文件或空预览不增加主技能使用。测试使用专属技能和记忆记录，并在结束后清理。

任务上下文 9 场景、记忆 11 场景各连续 10 轮通过；另有独立动作核验和共享执行回归，最终同一套主/测试 APK 共 376 次场景执行通过，Release R8 与 Debug/Release Lint 通过。本次端到端报告、源码/安装包指纹和保留的阶段失败见 [.local/test-runs/agent-m2-verification-20261013/RESULTS.md](../../.local/test-runs/agent-m2-verification-20261013/RESULTS.md)，不以早期编译或阶段失败代替最终证据。

## 后续必需工作

共享资料交接、成员专属工作上下文、备份恢复及外部目录交付仍须继续核对实际调用链与闭环验收；上述切片不代表 M4 或 M5 全部完成。真实 Root guest 进程清理、OEM/SAF 行为仍需相应设备验收。
