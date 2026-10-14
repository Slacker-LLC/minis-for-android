# 快捷键与命令身份：已有测试全文审查

目标主线 `3527ad8165f11c0f98d8a30f96098fc9280faa94`。只读两个已有测试，未发送键盘事件、运行命令或执行测试；只新增报告与台账，没有新增已确认产品缺陷。

## 快捷键用例复制决策

ChatShortcutKeyMatchTest 自己定义Action和match函数，检查Ctrl/Meta+N/F、非修饰字母、KeyUp和其他组合键拒绝。没有调用ChatSplitScaffold真实onPreviewKeyEvent，所以生产处理器退化时测试仍可能通过。Alt/Shift额外修饰、按键重复、输入法与焦点转发不在范围；不能用测试标题证明真实键盘只创建一个会话。

## 模型身份与计划

ModelIdentityAndCommandRegistryTest 的supportsReasoningOf也是测试私有实现，覆盖true/false/JSON null三态，未调用生产modelCatalogEntry。计划用例只是AgentStateStore.planGet固定sessionId的默认状态不是plan；构造config后只assertNotNull，没有执行/plan或检验真实状态迁移。不能据此证明Web或Android命令投影已同步。

## 命令目录

目录存在性与名字唯一性测试调用真实AgentCommandRegistry.baseEntries，能够针对重复名称和约定命令缺失产生断言。但“every base command has a real handler branch”只是人工handled集合包含目录名字，没有调用execute或分析分派实现。若生产execute删掉对应分支而目录保留，该断言仍可通过。

这些是测试证据与标题的差异，不据此认定生产快捷键或命令已经失效，也不新增镜像单测。真实调用链已审结果与本轮测试范围需分开使用。未运行测试。

## 覆盖与交付

| 全文文件 | 行数 |
|---|---:|
| src/android/app/src/test/java/com/openminis/app/ui/ChatShortcutKeyMatchTest.kt | 86 |
| src/android/app/src/test/java/com/openminis/app/remote/ModelIdentityAndCommandRegistryTest.kt | 112 |

两个现版文件完整读取。新增全文2文件、198行；已全文重读0文件。其它rg定位不计全文。

累计全文1418 / 1758文件，212683行；partial 4，未登记336。全库通读未完成。[新台账](/home/jiale/projects/minis-for-android/docs/archive/2026-10-09-code-review-coverage-3527ad81-command-tests.json)。

校验旧245报告/全部旧台账hash、HEAD/origin/main、全快照Git blob/SHA-256/字节/行数、tracked/staged空diff。只新增报告与台账。
