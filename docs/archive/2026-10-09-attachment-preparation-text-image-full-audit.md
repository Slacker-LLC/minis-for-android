# 附件准备、文本内联与请求图片预算全文审查

2026-10-09，固定主线 `3527ad8165f11c0f98d8a30f96098fc9280faa94`。旧201份报告和所有旧台账保持原内容。只静态阅读；未改产品代码、编写/运行测试、构建、模拟器、adb、Root、设备、云资源、凭据或Provider请求。

## 文本附件正文确实进入请求

InlineTextAttachment按MIME或扩展名判断文本，UTF-8读取有每文件60000字符、整批120000字符预算；读取最多maxChars×4字节，NUL头部不内联，截断带文件大小及继续读取提示。UserAttachmentPreparer将正文写进user-attached-files块，替换正文里的结束块标签，保留完整上传文件作为工具读取来源。

ChatViewModelSend115—143和540—558真实将prepared.attachedFilesXml加为AgentContentPart.Text；不是仅新建无人调用helper。普通发送和排队合并均已有接线，所以旧“文本附件只有元数据”条件改善。二进制仍为元数据是明确设计，不能把PDF/APK没有直接内联当此次文本修复失败。

读取按UTF-8处理，不探测编码；UTF-16含NUL会回落元数据，非UTF-8可能出现替换字符。先记录格式适配范围，不把支持全部编码当未提出的需求。预算按UTF-16字符数计，不等于token或整个请求体预算。超过预算的文件仍可通过上传路径访问，不声称完整内容被删除。

## 旧IB02仍存在：拆文件没有修复图片关联

原[图片预算报告](/home/jiale/projects/minis-for-android/docs/archive/2026-10-06-image-budget-wire-size-attachment-index-audit.md)已编号IB02。本轮不重复新增问题。

ImageBudget160—199仍在某图片导致累计超限时continue，后续较小图片可能保留；UserAttachmentPreparer预算处理用keptBytes.mapIndexed的新idx取imageParts[idx]，再把URI/name/mediaRef/path列表仅从尾部裁剪。保留集不是前缀时，像素与路径/历史元数据仍可能错位。这是同一个旧根因的最新生产位置，不因代码抽取就宣布已修。

此外上传失败的图片仍保留ImagePart，但imageUploadPaths只追加成功项；两个已核对发送入口又用imageUploadPaths.getOrNull(idx)重建ImageData，没有直接使用part.linuxPath。因此“首图上传失败、后图成功”的同族条件也仍存在。不宣称所有少量正常图片会错位，不在设备生成图片/请求。

imageMediaRefs没有随图片预算裁剪，nonImageNames在上传成功前就追加；这些列表不对齐的后续UI影响需要具体消费者，不能只看数组长度另报数据丢失。resize非PNG编码成JPEG却原MIME仍传ImagePart的格式问题也须核对Provider实际编码，保留为候选，不替旧IB02再编号。

## 请求预算和测试的实际边界

RequestImageBudget真实处理ImageData及ToolResult内图片，超限时用文本占位；ToolResult保留结构和原正文，清图片字节及MIME。无linuxPath才尝试spillover，写失败得到无路径占位；此失败家族已在旧报告，未重复新增。真实AgentLoop515—530在Provider调用前使用该helper。

RequestImageBudgetTest四例直接调用真实helper，验证无图/未超限同一列表实例、保护最新图片、ToolResult保留结构；都是已有路径输入，没有覆盖spillover落盘失败或真实Provider wire请求。UserAttachmentPreparerTest只调用uniqueUploadFileName/buildMediaRefPartJson，不调用prepare、Android ContentResolver或图片预算裁剪。InlineTextAttachmentTest验证真实宿主文件读取、字符上限、NUL、零预算、结束块标签和MIME/扩展名；不验证整个发送和持久化。未运行任何测试。

## 覆盖

| 全文文件 | 行数 |
|---|---:|
| src/android/app/src/main/java/com/openminis/app/ui/chat/UserAttachmentPreparer.kt | 390 |
| src/android/app/src/main/java/com/openminis/app/ui/chat/InlineTextAttachment.kt | 67 |
| src/android/app/src/main/java/com/openminis/app/ui/chat/RequestImageBudget.kt | 145 |
| src/android/app/src/test/java/com/openminis/app/ui/chat/UserAttachmentPreparerTest.kt | 53 |
| src/android/app/src/test/java/com/openminis/app/ui/chat/InlineTextAttachmentTest.kt | 58 |
| src/android/app/src/test/java/com/openminis/app/ui/chat/RequestImageBudgetTest.kt | 75 |

UserAttachmentPreparer按1—210和211—390完整阅读，其余五文件完整输出无截断。新增全文6文件、788行，重读0文件。Send/AgentLoop仅片段，不冒充全文。

累计全文1299 / 1758文件，194363行；partial 7，未登记452。全库通读未完成。[新台账](/home/jiale/projects/minis-for-android/docs/archive/2026-10-09-code-review-coverage-3527ad81-attachments.json)。

交付校验旧201份报告/全部旧台账hash、HEAD/origin/main、全快照Git blob/SHA-256/字节/行数、tracked/staged diff为空。只新增报告与台账。
