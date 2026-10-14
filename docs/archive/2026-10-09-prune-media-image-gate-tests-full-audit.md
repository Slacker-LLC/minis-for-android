# 技能清理、媒体删除与图片门控：已有测试全文审查

目标主线 `3527ad8165f11c0f98d8a30f96098fc9280faa94`。只读三个已有测试，未运行测试或删除任何技能、媒体、注册真实工具；仅新增报告和台账。没有新增已确认缺陷。

## 技能孤儿清理

SkillOrphanPruneTest 对真实判定helper注入参数：迁移未完成不清理、bundled不清理、目录读取失败null不当空目录、有SKILL.md保留、只有目录而缺SKILL.md判孤儿。它没有真实guest目录、Room条目和清理执行，不证明短暂文件消失或SKILL.md读权限失败时不会误删。null列表拒绝清理是具体保护，不能扩成所有I/O错误均已区分。

## 会话媒体删除

DeleteSessionMediaTest 使用临时文件树调用真实删除函数，覆盖跨日期同会话删除与其他会话内容保留、不存在根成功、目录不能删除返回false并保留文件。权限失败例若当前用户能忽略目录权限，则Assume跳过；不能单凭用例存在宣布所有宿主都验证失败分支。

没有符号链接、异常sessionId、列目录失败、部分文件删除后失败或并发写入场景。测试中失败返回false也不能证明UI调用方一定向用户展示错误。本轮不执行测试，不实际删除文件。

## 图片工具暴露门控

ReadImageGateTest 注册LinuxReadImageHandler及read_image别名，再调用真实AgentTools.makeAgentTools，检查纯文本且无vision配置时两种名称都不暴露、有图像输入或vision配置时仅一次、CORE最小工具集不暴露。

该测试验证工具列表而非执行权限。vision=true是布尔注入，不验证真实Vision槽可用凭据、模型在线与图像转换；也不证明远程直接按工具名调用会被同样拒绝。全局ToolRegistry在Before注册、After注销，没有快照恢复已有同名handler；对测试隔离的影响需核对执行配置，不新立生产缺陷。

三个测试的断言与真实端到端验证分别记录，未把缺少用例直接认定产品Bug。

## 覆盖与交付

| 全文文件 | 行数 |
|---|---:|
| src/android/app/src/test/java/com/openminis/app/data/repository/SkillOrphanPruneTest.kt | 28 |
| src/android/app/src/test/java/com/openminis/app/ui/settings/DeleteSessionMediaTest.kt | 52 |
| src/android/app/src/test/java/com/openminis/app/tools/ReadImageGateTest.kt | 60 |

三个现版文件完整读取。新增全文3文件、140行；已全文重读0文件。其它rg定位不计全文。

累计全文1392 / 1758文件，210884行；partial 4，未登记362。全库通读未完成。[新台账](/home/jiale/projects/minis-for-android/docs/archive/2026-10-09-code-review-coverage-3527ad81-prune-gate-tests.json)。

校验旧235报告/全部旧台账hash、HEAD/origin/main、全快照Git blob/SHA-256/字节/行数、tracked/staged空diff。只新增报告与台账。
