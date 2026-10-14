# Bot 模型解析与子代理名称：现版全文审查

目标主线 `3527ad8165f11c0f98d8a30f96098fc9280faa94`。仅静态阅读，未运行测试、模型请求或工具；仅新增报告与台账。

## 模型解析保护

BotModelResolver 先取可见文本输出条目。明确绑定 Entry 时，条目不可见、实例不存在、实例禁用或无凭据均返回 null，不静默选其他模型。非 Entry 绑定回到 main 可用文本条目。这里只确认该解析函数，不据此概括所有 Provider 后续执行行为。

CallableModels 按 entryId 去重、限制30项；重名 modelId 用 provider/model 区分，再用 entryId 前缀消歧。匹配只忽略大小写和外围空白，拒绝模糊匹配。

## 旧重排候选：已改善，仍有共同前缀残留

[旧报告候选一](/home/jiale/projects/minis-for-android/docs/archive/2026-10-07-remote-9e523652-subagent-named-model-audit.md)描述了顺序编号的不稳定映射。现版已换成 entryId 前缀，不能继续把旧 #n 实现当现状。

但 CallableModels.kt 第54—60行从4字符开始，只有碰到已经占用的名称才延长当前条目的前缀。例如同 provider P、model m，entryId 为 abcd1111 和 abcd2222：前者先遍历得到 P/m#abcd，后者得到 P/m#abcd2；反向顺序则 abcd2222 获得 P/m#abcd，abcd1111 获得 P/m#abcd1。相同短名称仍能指向另一 entry。这是源码逐步推导，没有执行重现程序。

已有重排测试使用11112222与33334444，前四字符不同，因而不能验证该碰撞条件。保留为旧候选的明确残留，不新增已确认运行时误选、权限绕过或错误计费编号；需要进一步核对请求期间映射冻结与配置变更调用链。修复方向是让每个碰撞组采用与顺序无关的唯一前缀长度，而不是仅后到条目加长。

## 其他未闭合边界

名称生成只对重复 base 检查 used。原始 modelId 若恰好与另一个条目生成的限定名称相同，也需要核对实际模型条目输入约束，不能凭字符串算法局部可能性直接认定现有配置已经碰撞。

本轮完整阅读 CallableModelsTest，覆盖一般重名、不同前缀重排、列表上限、摘要和精确匹配；未执行测试。AppSubAgentPort 仅定位 callableModels 调用，不计新增全文覆盖。

## 覆盖与交付

| 全文文件 | 行数 |
|---|---:|
| src/android/app/src/main/java/com/openminis/app/agent/BotModelResolver.kt | 27 |
| src/android/app/src/main/java/com/openminis/app/agent/subagents/CallableModels.kt | 81 |
| src/android/app/src/test/java/com/openminis/app/agent/subagents/CallableModelsTest.kt | 72 |

三个现版文件完整读取。新增全文3文件、180行；已全文重读0文件。其它rg定位不计全文。

累计全文1359 / 1758文件，208399行；partial 4，未登记395。全库通读未完成。[新台账](/home/jiale/projects/minis-for-android/docs/archive/2026-10-09-code-review-coverage-3527ad81-callable-models.json)。

校验旧223报告/全部旧台账hash、HEAD/origin/main、全快照Git blob/SHA-256/字节/行数、tracked/staged空diff。只新增报告与台账。
