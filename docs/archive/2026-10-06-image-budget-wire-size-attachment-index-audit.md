# 图片预算的编码体积与附件下标静态审查

日期：2026-10-06。固定本仓HEAD `d64e7c06d8159be90737dd9ba03dd9bd5855f339`。新增 **2项确认缺陷，均P2**。本轮全文读取ImageBudget.kt的337行，并沿既有partial文件ChatViewModel及已读Provider复核实际调用。未修改产品代码，未运行测试、构建、模拟器、设备、Root、云或模型请求；旧报告保持不变。

## IB01 / P2：原始图片字节预算漏算Base64扩张

[ImageBudget.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/ImageBudget.kt:179) applyMessageBudget以sized.size累计25MiB；[ImageBudget.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/ImageBudget.kt:270) planRequestBudget以min(rawSize,5MiB)累计请求预算。压缩函数默认目标也为5MiB原始字节。实际OpenAI发送边界 [OpenAIProvider.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/openai/OpenAIProvider.kt:2204) 再次压缩后，第2206行Base64.NO_WRAP编码，将字符串内联到JSON。预算函数并未计算编码后长度。

静态反例：历史有7张各3MiB、可正常解码且模型支持的图片。每张低于5MiB，compressUnderBudget第129行原样返回；请求计划认定21MiB全部可保留，但Base64每张4MiB，图片字符串合计28MiB。25MiB原始图片则约33.33MiB编码数据；JSON、文本历史和工具schema还会增加体积。这里只用编码长度公式推导，没有实际生成图片或请求网关，不声称所有Provider限制均为同一数值，也不声称28MiB一定被每家服务拒绝。

影响：用25MiB图片预算控制请求体积的策略低估实际传输量，接近网关上限时仍可能出现request too large；单图5MiB原始预算同样允许约6.67MiB编码数据。方向：确定上限口径，按实际编码长度计算累计预算，并给压缩函数传对应原始目标；不要只在报错时删消息。

固定OpenMinis同路径第35—70行已引入estimatedBase64Length及MAX_PER_IMAGE_RAW_BYTES；第506/511行消息预算、第605行请求预算按编码长度比较。上游ImageBudgetBase64Test第78行起的7×3MiB样本明确针对同一回归，本轮只读片段，不运行也不计入本仓全文数。该算术处理可以参考；并未证明上游请求的所有其它字段已有完整总预算。

## IB02 / P2：中间图片被丢弃后，后续图片绑定前一张的路径及历史元数据

[ImageBudget.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/ImageBudget.kt:179) 累计超限后执行continue，随后较小图片仍可能加入keptBytes；结果因此不一定是原数组的前缀。调用方 [ChatViewModel.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:10944) 却把keptBytes的新下标idx用于读取原imageParts[idx]，第10947行使用其linuxPath，并无条件将不相同的字节标为JPEG；第10952—10955行仅从URI、名称、mediaRef JSON、上传路径列表的尾部删除到新长度。

可达静态反例：8张图片的推理副本依次为4、4、4、4、4、4、2、0.5MiB。图片均不超过5MiB，取能被平台正常解码、边长不超过2000且文件大小为相应区间的PNG，无需靠解码失败触发；resizeImageBytes第10712行对小尺寸保留原字节。前6张共24MiB，第7张超25MiB被跳过，第8张0.5MiB仍保留。

keptBytes第7项实际是第8张PNG，调用方却拿原第7张的路径并标成JPEG；平行列表保留原第7张的URI/名称/mediaRef，删除第8张。正常发送 [ChatViewModel.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:6815) 又从imageUploadPaths按idx取路径，把第8张像素标为第7张文件。模型看到的像素、描述路径、聊天附件和重新加载后的原图出现不一致；要求read_image复查时会读取另一张。图片数量入口ChatScreen第322行上限50，8张未被数量限制排除；排队注入第6413行还会合并附件。这里不声称普通少量照片必然达到25MiB，也没有实测特定PNG压缩分布。

同一附件关联链还存在另一个无需总量超限的触发条件：第10864行只在uploadOk时向imageUploadPaths添加条目，但第10869—10873行仍保留上传失败图片的像素、URI、名称、mediaRef。若第一张guest镜像写入失败、第二张成功，列表只含第二张路径；发送入口第6816行把它绑定第一张像素，第二张反而没有路径。ImagePart本来已有逐图linuxPath，第6816行却未使用。此处作为同一关联错误的另一分支，不另外增加缺陷总数，不声称写入失败一定发生。

方向：预算保留实际原始下标或完整图片项，并据此同步所有附件字段；避免用压缩后列表的位置推断原附件，也避免成功路径列表丢失空位。本轮没有实现。固定上游ImageBudget第512—514行同样continue，ChatViewModel第15648—15659行仍有同样的mapIndexed/尾删结构，因此这一项不能照搬上游解决。

## 压缩失败及身份标识的待核对边界

compressBytes第89/94行解码失败返回原字节，第113行捕获Throwable也原样返回；压缩梯子第144行允许最小候选仍超目标。请求计划却把所有大图片都按5MiB计算。因此失败条件下还可能进一步低估体积。需要核对实际支持格式及平台失败条件，未把假设的不可解码内容当成独立正常图片生产缺陷，也未宣称压缩过程已经保证硬上限。

第109行忽略Bitmap.compress的Boolean返回，资源recycle只在正常路径；异常时资源释放及空输出仍为后续核对点，没有设备实测。ImagePartId使用identityHashCode而非消息/part位置：如果同一ByteArray在不同位置同时出现，旧位置被丢弃会使新位置也被标记，但本轮没有证明生产构造一定产生该共享对象场景，不增加第三项。GeneratedImageHistory已在旧报告读过，本轮复核不重复计数。

本轮没有重新计入CO01已有的图片落盘失败后placeholder问题，也没有恢复PRoot、Alpine或旧broker。新增问题都在App图片准备及现役请求序列化边界。

## 全文覆盖与复核

新增全文：[ImageBudget.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/ImageBudget.kt:1)，337行。全文输出完整；片段批次出现的截断已对相关Provider/resize/上游预算片段分别重读，未将截断大文件登记全文。本仓未找到ImageBudget专用测试文件，上游测试仅作差异参考。

累计全文 **1200 / 1546文件、231398行**；partial13，not_registered333，excluded124。全仓通读尚未完成。

完成时校验全部1670 tracked路径SHA-256、字节数与正数文本行数，本仓/上游固定HEAD，tracked/staged空diff，此前108份报告哈希，本报告链接和行号，以及台账全文批次总计。只新建本报告并更新覆盖台账。
