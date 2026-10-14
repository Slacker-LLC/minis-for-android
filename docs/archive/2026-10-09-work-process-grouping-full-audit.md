# 工作过程分组、状态与已有测试全文审查

2026-10-09，固定主线3527ad8165f11c0f98d8a30f96098fc9280faa94。只做静态审查，旧212份报告和全部台账不覆盖；未改产品源码、编写/执行测试、构建或使用设备/模拟器/Root/云资源。本轮无新增独立确认缺陷。

## WP01、WP02现版根因仍在

WorkProcess.kt:39—58分类集合未包含linux.file.write/edit；含点号的名字在search/browser/image分支之前归MCP。现役ToolRegistry.kt:264、271仍定义这两个名字，AndroidSystemTools.kt:364仍定义android.web.search。因此旧WP01显示分类问题保持，不重复编号，不称实际执行路由变成MCP。

fileChangeSummary仍不看toolStatus或执行回执，仅正则抽path、old_string/new_string/content计算行数。ChatWorkProcessUI.kt:243仍调用它；失败/取消的参数也可生成改动量，批量edits参数缺口仍是旧WP02的同源问题。toolArgString分步replace未解决字面反斜杠和Unicode解码边界。真正工具执行不使用此helper，不能扩展成文件实际被它改坏。

## 旧缓存候选已改变

ChatWorkProcessUI.kt:91现在remember(process.blocks,process.turnLive,process.messageUpdatedAtMs,process.messageCreatedAtMs)。旧报告只按blocks缓存可能遗漏时间戳变化的候选，不再按原样成立。这里只确认缓存依赖补齐；FlatChatItem.WorkProcessRow的外层equals及真实UI发布是其它边界，未由一行remember宣称全部耗时问题修复。FC01用户起点传播遗漏仍见前批报告。

## 当前分组与状态规则

buildAssistantTurnEntries按连续可见thinking/tool聚合，普通text/info终止run并原位保留；hidden thinking不创建/填充run，PER_TOOL保留每个原block。process ID锚定首block，后续增长不改key。只有最后run携带消息clock和turnLive，较早run按步骤自己的计时显示。

WorkProcess运行状态包括在途工具和turnLive，完成后duration优先消息时间差，回退首工具start到最后工具end。最后工具失败才作为unrecoveredFailure，后续成功视作恢复；运行时不展示failureReason，但summary仍可能携带failedStepNumber，实际UI优先级需消费者，不误称失败结果被删除。

failureSummary取首非空行再title/name，并限长；负maxChars会使take失败，但生产使用默认120，未发现可控调用来源，不能单凭人工负参数登记运行缺陷。tally排除reasoning/other并按数量排序，文件摘要只能代表参数计划的限制见WP02。

## 已有测试范围

WorkProcessGroupingTest全文266行，覆盖分组边界、隐藏thinking、PER_TOOL、key稳定、空run拒绝、运行状态、failure编号/截断、turnLive、后续成功恢复及时间差、notePreview。测试真实调用分组/summary，未涵盖现役点号工具分类、失败文件计划摘要、完整Compose状态或设备滚动。仅阅读，未执行。

WorkProcess完整392行无截断；测试首次与rg组合输出截断在后续检索，170—266已独立补读，确保全部测试行可见。其它registry/UI仅核对定位及片段，不增加全文覆盖。

## 覆盖与交付

| 全文文件 | 行数 |
|---|---:|
| src/android/app/src/main/java/com/openminis/app/ui/chat/WorkProcess.kt | 392 |
| src/android/app/src/test/java/com/openminis/app/ui/chat/WorkProcessGroupingTest.kt | 266 |

两个现版文件完整读取。新增全文2文件、658行；已全文重读0文件。其它rg定位不计全文。

累计全文1330 / 1758文件，202632行；partial 4，未登记424。全库通读未完成。[新台账](/home/jiale/projects/minis-for-android/docs/archive/2026-10-09-code-review-coverage-3527ad81-work-process.json)。

校验旧212报告/全部旧台账hash、HEAD/origin/main、全快照Git blob/SHA-256/字节/行数、tracked/staged空diff。只新增报告与台账。
