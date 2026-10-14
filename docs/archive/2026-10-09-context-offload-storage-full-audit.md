# 上下文卸载存储：现版全文审查

目标主线 `3527ad8165f11c0f98d8a30f96098fc9280faa94`。只读，不写guest文件、不运行测试或模型工具；仅新增报告和台账。

## CO02：常见后缀碰撞改善，不能称绝对唯一

旧[卸载报告](/home/jiale/projects/minis-for-android/docs/archive/2026-10-06-context-offload-prefs-tokenizer-audit.md)的 CO02 指不同完整toolId共享末12字符而覆盖。现版 shortToolId 对截断或替换的ID添加完整字符串 hashCode，普通不同前缀不再直接同名。此前通读的 ContextOffloaderFailureTest 已覆盖一个不同前缀同后缀例，不能继续将旧无hash实现当现状。

但现版仍使用32位字符串hash，没有碰撞检查或独占文件创建，不能保证唯一。静态构造：`Aa123456789012` 与 `BB123456789012` 都保留末尾 `123456789012`；字符串hash的多项式中 Aa 的两字符值为65×31+97=2112，BB为66×31+66=2112，追加相同后缀后仍相等。两者产生同一shortToolId，同session同toolName文本目标相同。这是源码与代数推导，未执行碰撞测试或写文件；不声称真实Provider已经发出该ID。沿用 CO02 的残留边界，不另立编号。

相同toolId多份内容也仍使用同目标，需结合真实part组合和调用方核实。修复方向是不可覆盖的分配或完整身份映射；仅增加另一段短hash不能证明无覆盖。

## 保存失败与溢出处理

普通文本与图片调用 WorkspaceFileClient.writeBytes，成功后才返回Linux路径；Exception被捕获，返回空字符串让调用方保留原part。图片扩展名固定由MIME映射，未知类型bin。ext参数在文本接口直接插入路径，实际调用方是否固定需要逐处验证，不据接口参数可能性认定路径越界；WorkspaceFileClient仍承担文件边界。

spillIfOversized 使用时间加UUID命名，保存失败向上传播，与普通卸载空串语义不同。先按UTF-8完整字节数判断，再保留头尾文本；提示中的 Omitted 数是原始总字节而非扣除预览后的精确遗漏量，属于提示准确性边界，不新增高优先级Bug。大文本会重复构造UTF-8字节数组，具体内存峰值未测。

这里只读存储helper；取消异常被Exception捕获是否改变整轮取消、异常日志内容和调用端恢复需要完整调用链，未升级为确定取消失败。现役调用定位包含 ContextOffloader、ToolResultBudget、MCPToolHandler、AndroidLogManager 和 AndroidDiagnoseController，定位不计全文。

## 覆盖与交付

| 全文文件 | 行数 |
|---|---:|
| src/android/app/src/main/java/com/openminis/app/data/ContextOffload.kt | 146 |

一个现版文件完整读取。新增全文1文件、146行；已全文重读0文件。其它rg定位不计全文。

累计全文1373 / 1758文件，209659行；partial 4，未登记381。全库通读未完成。[新台账](/home/jiale/projects/minis-for-android/docs/archive/2026-10-09-code-review-coverage-3527ad81-offload-storage.json)。

校验旧229报告/全部旧台账hash、HEAD/origin/main、全快照Git blob/SHA-256/字节/行数、tracked/staged空diff。只新增报告与台账。
