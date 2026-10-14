# 聊天列表转换与流式overlay全文审查

2026-10-09，固定主线3527ad8165f11c0f98d8a30f96098fc9280faa94。ChatFlatItems 941行分1—320、321—640、641—941完整阅读无截断。旧211报告与台账不覆盖。未改产品代码、运行测试/构建或使用设备、模拟器、Root、云资源。

## 分段构建候选：冻结前缀改变了向后观察范围

上批未确认的等价性问题已找到具体来源。ChatScreenFlatItems的冻结构建传msgs.take(splitIdx)，实时后缀传完整merged与fromIndex。ChatFlatItems.kt:808附近的isLastAssistantTurn却检查idx后全部消息是否没有assistant，决定最后text保留rawFragments还是coalesce。

例如完整列表含历史助手A（两个短非fence段落）、用户U、当前stream助手B；splitIdx在B。冻结输入[A,U]，A后没有assistant，A保留两个raw片段；完整输入[A,U,B]，A后有B，A使用coalesce。StreamingMarkdownTextContext.kt:499—524确认两个短非fence片段可合成一行。因此源码注释“rows只依赖更早消息、row-for-row相同”不能作为事实。

这证明转换结果和keys可能不同；stream overlay清空后splitIdx变大，冻结完整输入可能合并原A片段。是否真的造成当前界面可见跳动、哪种视口锚定受影响，仍需完整scroll/render拥有者及设备证据。先列候选，不另登记确认P2，也不重复把旧stream-end重排描述认作本轮复现。记录方向：完整列表提供邻接事实、限制输出前缀，或明确统一末轮判断；只记录交Claude。

## FC01仍存在，FC02仍需按入口分别核对

turnStartedAtMs初始化0后全文没有赋值，user分支直接continue，buildAssistantTurnEntries永远回退assistant.createdAtMs。旧FC01“整轮用时遗漏用户到助手开始间隔”仍存在；只补现版证据，不新增编号。若后续修复，fromIndex后缀必须恢复前置用户起点，不能只在本段user分支赋值。

ChatViewModelSend当前没有createdAtMs/updatedAtMs赋值匹配，与旧FC02实时时间对象缺口一致；这只是已全文发送文件的字段核对，未新审查所有assistant生成/恢复入口，不扩大为全部时间永远缺失。时间divider仍要求createdAtMs>0，数据库真实保存时间与UI字段应分开判断。

## 其它正常行为及未闭环候选

mergeStreamingOverlay只copy有delta的消息、不改原列表，空stream返回原列表；builder保留system信息、跳过phantom空助手、区分分组/单工具、尾thinking、最后取消工具重试和错误/action行。去重key通过改写messageId加后缀实现，极端重复输入的点击身份仍需生产重复来源验证，不凭防御分支新报正常消息操作失效。

AssistantMarkdownBlock/Legacy以及WorkProcessRow仍用长度等廉价比较，旧等长改写候选未新增生产闭环，保持候选。hasVisibleContent的null thinking snapshot默认true而分组显示用currentThinkingEnabled，旧隐藏thinking候选也未补齐恢复/继续流来源，不新增重复问题。

本轮新增确认问题0，保留旧FC01并补充一个分段构建候选。Markdown coalesce只读499—545片段，未计额外全文。测试仅查找既有覆盖，不执行或新增。

## 覆盖与交付

| 全文文件 | 行数 |
|---|---:|
| src/android/app/src/main/java/com/openminis/app/ui/chat/ChatFlatItems.kt | 941 |

一个现版文件完整读取。新增全文1文件、941行；已全文重读0文件。其它rg定位不计全文。

累计全文1328 / 1758文件，201974行；partial 4，未登记426。全库通读未完成。[新台账](/home/jiale/projects/minis-for-android/docs/archive/2026-10-09-code-review-coverage-3527ad81-flat-items.json)。

校验旧211报告/全部旧台账hash、HEAD/origin/main、全快照Git blob/SHA-256/字节/行数、tracked/staged空diff。只新增报告与台账。
