# 记忆编辑冲突、读取保护与预算复核

日期：2026-10-07。固定远端HEAD `37158d188a190416f2b16b45dfb112ecf53cdd7b`。新增完整静态阅读4文件、1087行；保留此前168份报告和全部旧台账，只新建本文及台账。未修改代码、编写/执行测试、构建、模拟器/adb/Root/设备/云任务，未创建记忆样本或读取用户实际记忆。本轮沿用旧编号，不新增重复问题。

## UI03读取失败和加载前保存已阻断，加载前输入仍有窗口

[MemoryRepository.kt:364](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/data/repository/MemoryRepository.kt#L364) readFileForEdit返回Loaded/Missing/Failed，NOT_FOUND与其它错误不同；[MemoryManagementScreen.kt:310](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/ui/settings/MemoryManagementScreen.kt#L310) 编辑页仅Loaded/Missing设置opened，Failed显示load error。Save第326行要求opened非null，旧“超限/读取失败当作空文件后允许覆盖”“加载完成前保存”原反例已有阻断。

但DialogTextField第366行未设置enabled=opened!=null，也没有草稿版本检查；首次读取期间用户可输入，读取返回后第313行无条件content=read.text。晚到读取仍可覆盖输入，沿用UI03的加载时序残留。不要由Save禁用推出编辑内容同样被锁。

content/opened状态remember未以fileName作为key，LaunchedEffect(fileName)不先清opened；同组合实例换文件参数时旧baseline可能存留。真实导航是否复用该组合需核对，不将其直接升级为已确认跨文件保存。

## UI04顺序冲突已拒绝，检查到写入之间未共用锁

[MemoryRepository.kt:381](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/data/repository/MemoryRepository.kt#L381) saveFileIfUnchanged是@Synchronized，重新readFileForEdit并与编辑打开时文本/不存在状态比较。Agent在保存前已追加条目会导致返回false，UI提示changed-elsewhere；成功后opened更新到pending，连续保存有正确新baseline。这是对旧UI04顺序覆盖反例的实际修复。

但writeMemory第40行、revokeEntry、replaceEntryBody、saveFile等不取得该monitor。明确允许的剩余交错：编辑保存重读A并通过比较；Agent writeMemory读A、写入B+A；编辑随后writeGuestFile覆盖成编辑A，丢B。同一个repo也可发生，因为writer未进入@Synchronized。WorkspaceFileClient的read与write是独立调用，单次底层文件操作保护不跨越这个比较窗口。因此UI04仍部分修复，不把此方法命名/注释当成原子CAS证据。

Agent writeMemory本身read→prepend→write未按文件互斥；两个调用同读旧值后各自写整文件的候选丢更新与整文件覆盖同族。本轮没有并发运行，未测触发概率。实际写入有固定日期路径、guestPath校验、WorkspaceFileClient边界，不能描述为不受控制的host任意文件API。

## UI01键盘、UI02离开草稿和列表失败状态

记忆编辑Column仍仅fillMaxSize/padding，未添加imePadding/windowInsets避让；用户报告的UI01当前代码侧未修，不运行模拟器验证。topbar仍直接onBack，未检查未保存草稿，旧UI02在此入口仍在。

管理页listAllFiles会把listGuestFiles失败映射成空列表，但仍加入一个GLOBAL文件占位；不显示“存储读取失败”。delete动作第158行忽略Boolean返回，仅刷新列表，失败没有提示。作为既有读取/写入失败反馈类补充，不新增编号。GLOBAL不给删除按钮，仓库deleteFile也拒绝GLOBAL，保护真实存在；不将其它共享目录入口的权限问题扩大到这个正常管理页。

## MP01/MP02条目定位仍同形

[MemoryRepository.kt:456](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/data/repository/MemoryRepository.kt#L456) candidateDateStrings仅今天和前24小时，不接原记录日期；旧MP01跨多日编辑/撤销无法定位仍在。revokeEntry/replaceEntryBody遍历这两个日期，按trim正文匹配第一条，旧MP02同文条目可选错仍在。没有持久条目ID，保存timestamp注释不等于操作时使用timestamp作身份。

UIFile整文件editor新增保护不修改SessionMemorySheet条目操作归属，不能把文件保存修复扩大到MP03成功编辑后两个record副本问题；本轮未全文重读其UI/VM，不作已修判断。

## 注入/检索预算边界

[MemoryInjectionBudget.kt:24](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/data/repository/MemoryInjectionBudget.kt#L24) GLOBAL预算按contextWindow/16限定4000—32000字符，正文截断时补最多4000字符heading index并带revision，属实际内容预算。revision对原完整内容求hash，文件尾变化即使没注入正文也会改header，不能称纯注入text hash，但这是实现选择，未新增问题。

daily同步/异步路径均最多3文件、每文件200行，未按字符/字节裁长行；单行可很长。getMemory第166行先将完整entry加入results后才按累计bytes决定停止，30KiB是停止后续文件的门槛，不能作为最终返回体绝对上限。额外notes也计在输出外。此处只记录预算候选：还需结合下游ToolResultBudget/整轮context预算才能判断模型实际收到多少，不宣称上下文必然溢出。

readGuestFile仍完整readAll，受WorkspaceFileClient单文件上限约束；截断注入并不避免先读完整文件。recent30天按固定24小时递减，在DST地区日历日可能偏差，缺实际样本不新报。MemoryInjectionBudget的headingIndex先join全文再take，不将字符串输出截断等同读取内存有界。

## 现有测试覆盖范围

[MemoryEditSafetyTest.kt:35](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/test/java/com/openminis/app/data/repository/MemoryEditSafetyTest.kt#L35) 六例覆盖存在/缺失、目录与非法name读取失败、未变化保存成功、保存之前Agent已写变化则拒绝、新文件创建竞争的顺序反例、失败read不允许保存。使用测试UbuntuPaths布局和临时目录，不是本轮真实设备验证。

其“Agent meanwhile”用例在调用saveFileIfUnchanged之前已经writeText，没有在保存方法重读通过与实际write之间插入writer；不能证明共享互斥或跨文件操作原子。也未覆盖编辑页加载期间输入、键盘或返回草稿。全部仅静态读取，没有执行任何测试。

## 固定OpenMinis对照

上游HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`。GIT_NO_LAZY_FETCH=1读取现有MemoryRepository第290—318行，无fetch。其recent daily也按200行取预览，行数限制不保证字节限制；当前App-owned路径与旧上游直接文件操作不同，不能直接照搬上游替代当前路径安全边界。没有重读上游全部仓库，不声称全面修复对照。

## 全文覆盖与旧文件保留

| 新增全文 | 行数 |
|---|---:|
| [MemoryInjectionBudget.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/data/repository/MemoryInjectionBudget.kt#L1) | 60 |
| [MemoryRepository.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/data/repository/MemoryRepository.kt#L1) | 551 |
| [MemoryManagementScreen.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/ui/settings/MemoryManagementScreen.kt#L1) | 393 |
| [MemoryEditSafetyTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/test/java/com/openminis/app/data/repository/MemoryEditSafetyTest.kt#L1) | 83 |

Repository分1—195/196—380/381—551；Budget1—60、测试1—83全文；UI初次混合输出截断后已独立完整补读1—205/206—393。搜索/其它helper片段不重复计入全文。

新台账：[2026-10-07-code-review-coverage-37158d18-memory-edit.json](/home/jiale/projects/minis-for-android/docs/archive/2026-10-07-code-review-coverage-37158d18-memory-edit.json)。累计全文 **1281 / 1652文件、211516行**；partial4、not_registered367、excluded124，tracked1776。全库通读仍未完成。

交付核验旧168份报告与旧台账hash、固定本地/远端/上游HEAD、tracked/staged空diff、1776快照blob/hash/bytes/lines和证据行号。只新建本文和本轮台账。
