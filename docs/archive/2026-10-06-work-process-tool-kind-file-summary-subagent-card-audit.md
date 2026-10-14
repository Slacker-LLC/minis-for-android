# 工作过程分类、文件变更摘要与子Agent卡片静态审查

日期：2026-10-06。固定本仓HEAD `d64e7c06d8159be90737dd9ba03dd9bd5855f339`。新增全文7文件、1269行，确认2项显示问题。仅静态阅读，没有修改产品源码、运行测试/构建/模拟器/adb/Root/设备/云/模型或子Agent，没有读取凭据。新建独立报告，旧报告不改。

## WP01 / P3：现役内置工具被点号规则误归为MCP

[WorkProcess.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/WorkProcess.kt:39) 文件读写分类集合只有file_read/file_write/file_edit与linux.read/write/edit等旧名。第52行把任何contains(".")归MCP，且在search/browser/image等分支之前。

真实定义 [ToolRegistry.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/runtime/ToolRegistry.kt:192) LinuxFileWriteHandler名称为linux.file.write，第199—202行编辑为linux.file.edit，分别派发本地FileWriteTool/FileEditTool，不是MCP。两者不匹配旧集合或write_file/edit_file，最终命中点号MCP。[AndroidSystemTools.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/AndroidSystemTools.kt:364) android.web.search同样在到达SEARCH分支前归MCP。不能据命名规范中有点号判断实现来源。

真实UI [ChatWorkProcessUI.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatWorkProcessUI.kt:173) 按summary.tallies绘制汇总，第326行映射MCP标签。于是一次linux.file.edit会计为MCP调用，而非文件修改；android.web.search不会计搜索。这只影响展示分类与数量口径，不改变实际执行权限或路由，没有声称本地文件工具被远程执行。

WorkProcessSummaryTest第38—45行只测试旧file_*、browser_use、web_search等名字，第49行测试真实MCP形状，没有现役linux.file.*与android.web.*；存在这些测试不能证明当前分类正确。未运行。

方向：复用现役工具身份/定义来分类，至少补齐真实内置名称并让MCP识别有明确来源边界。不能简单把search优先于MCP，导致mcp.docs.search反过来被误判普通搜索。

## WP02 / P3：文件改动摘要不检查执行结果，且不解析现役批量参数

[WorkProcess.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/WorkProcess.kt:229) fileChangeSummary只查block.kind、toolName、path与old_string/new_string/content，不查toolStatus或工具结果。[ChatWorkProcessUI.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatWorkProcessUI.kt:222) 在每个工具行后无条件调用，新增量第238—240行用绿色加号。

可复核的真实失败路径：linux.file.edit使用仍受支持的old_string/new_string，带明确非空path和一行新文本，在workspace-write权限下编辑越出允许目录的路径。[FileEditTool.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/FileEditTool.kt:55) 在读取和修改文件之前返回success=false，第65行readonly mount拒绝同样无修改。ChatViewModel第9521—9523行把失败结果映射FAILED，但fileChangeSummary仍由参数返回+1/−1，并在UI显示。失败工具本身仍有错误状态，本项不说错误被完全隐藏；问题是旁边的“文件变更量”没有区分未执行计划与已落盘修改。

同一helper还有现役格式缺口：FileEditTool第33行推荐edits[]，每项old_text/new_text；WorkProcess第239、243行仅解析旧old_string/new_string。成功批量编辑不会产出正确摘要（通常old/new都是空，返回null）。这作为同一文件摘要的数据源缺口记录，不另加第三个编号。不能只新增数组解析然后继续把失败参数当成果。

toolArgString采用正则与逐次replace解码，会把JSON中原本表示字面反斜杠+n的内容误解成换行，也不解Unicode escape；它还可能匹配嵌套同名字段。这里属于同一摘要可信度边界，不扩展成实际文件损坏：真正执行使用JSONObject/编辑引擎，没有复用此helper写文件。

方向：摘要应来自成功执行回执/实际diff；无法证实落盘时标明计划或省略，兼容现役批量参数。isCreation只由write且oldText为空推断，本轮UI未展示该字段，不将其另算真实“覆盖被写成新建”的故障。

## 工作状态、时长与子Agent卡片核对

WorkProcess.full按1—200、201—368读取，分组在GROUPED下把最后工作块以前的中间叙述合入process，末尾答复单独保留；hidden thinking被过滤，没有把隐藏思考全部吞入可见工作摘要。running工具优先早前failure，失败原因仍在展开panel，符合明示规则，不新增“失败被运行遮盖”缺陷。

WorkProcessRowView第89行remember仅以process.blocks为key，而summary也读messageCreatedAtMs/UpdatedAtMs；仅时间戳变化、blocks相等时可能沿用旧时长。需要实际Room/UI更新时间序列，保留候选。runningElapsedSec参数目前不参与workStatusText运行文本，是固定“正在工作”展示策略，不宣称必然违反用户要求。

SubAgentCardModel全文44行只delegate带job_id产生卡片；status/steer/cancel/resume普通工具行，error/纯文本拒绝。实际ChatToolDetailUI第1107—1110行接入SubAgentCard，ChatScreen第4085行提供cancel/resume/steer/open-session回调，不是未接线组件。

SubAgentCard全文218行优先registry状态，缺job回退statusAtCall。interrupted只看真实job.state，缺job但历史statusAtCall=interrupted不会有Resume；是否应允许缺job恢复需核对registry持久恢复，不把不存在job直接视为可恢复。历史running回退会有Stop/Steer但没有实时计时，这属于缺registry的候选，未重复此前子Agent恢复/取消报告。

steer点击立即清空输入，没有等待操作回执；失败时draft可能丢失，实际回调返回Unit，需用户操作失败时序/反馈边界，暂不新增确定问题。卡片任务只显示8行，子会话入口提供其它查看路径，不能说完整任务永久丢失。状态芯片用固定颜色，其对比不同于主题token，未实测无障碍。

ChatStepRow全文121行透传click/longclick，title权重、note单行、chevron固定16dp；实际触摸范围与RTL布局尚未验证，未据min38dp断言不可操作。SubAgentCardModelTest全文53行只测parser/终态，不能证明真实registry/导航/操作结果；WorkProcessSummaryTest全文132行不覆盖WP02摘要。均未运行。

## 固定OpenMinis对照

只读 `/tmp/openminis-review-20261004` Git对象HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，未fetch。其tree没有WorkProcess、ChatWorkProcessUI、ChatSubAgentCard三份同路径文件。因此不能用无同路径文件声称上游没有工作流程或子Agent，也没有上游同函数作为这两项修复模板；本轮以本地真实工具名和执行回执为证据。

## 覆盖与复核

| 新增全文文件 | 行数 |
|---|---:|
| [SubAgentCardModel.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/SubAgentCardModel.kt:1) | 44 |
| [ChatSubAgentCard.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatSubAgentCard.kt:1) | 218 |
| [ChatStepRow.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatStepRow.kt:1) | 121 |
| [WorkProcess.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/WorkProcess.kt:1) | 368 |
| [ChatWorkProcessUI.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatWorkProcessUI.kt:1) | 333 |
| [SubAgentCardModelTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/ui/chat/SubAgentCardModelTest.kt:1) | 53 |
| [WorkProcessSummaryTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/ui/chat/WorkProcessSummaryTest.kt:1) | 132 |

新增全文输出完整；一次相关片段合并输出截断，FileEditTool拒绝写入路径及registry定义已独立重读，未升级片段文件为full。

累计全文 **1276 / 1546文件、244184行**；partial13，not_registered257，excluded124。全仓通读尚未完成。完成时核验全部1670 tracked路径SHA-256、字节数、正数文本行数，本仓/上游固定HEAD、tracked/staged空diff、此前120份报告哈希、新报告链接行号与全文批次总计。只新建本报告并更新台账。
