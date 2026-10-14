# 读图工具的Vision路由、URI授权与元数据静态审查

日期：2026-10-06。本仓固定HEAD `d64e7c06d8159be90737dd9ba03dd9bd5855f339`。新增 **3项确认缺陷：P2两项、P3一项**；全文读取4文件、738行。只记录问题，未改产品源码，未运行测试、构建、模拟器、设备、Root、云或模型请求。旧报告不改。

## RI01 / P2：Vision Group描述调用断开，非视觉主模型读图只拿到元数据

[ReadImageTool.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/ReadImageTool.kt:25) 的工具描述承诺：主模型没有原生视觉时，图片送给配置的Vision Group并返回文字描述，还可通过prompt指定关注内容。工具实际仅解析path/tool_title，没有读取prompt，也没有调用VisionGroupResolver；第158行成功返回路径/尺寸元数据和JPEG像素。

生产注册 [MinisApp.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/MinisApp.kt:779) 将LinuxReadImageHandler以read_image alias注册。[ToolRegistry.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/runtime/ToolRegistry.kt:403) 直接调用ReadImageTool.execute。[ChatViewModel.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:9825) 命中registry就直接返回ToolExecutor结果；第9567行把其文字/图片存入ToolResult，没有补充Vision描述调用。

main/java完整引用检索中，VisionGroupResolver.describe没有生产调用方，framedDescription/failureText也只有定义。isConfigured与noVisionImagePlaceholder仍被ChatViewModel调用：第9909行提示非视觉主模型去read_image获取描述，但提示指向的实际入口已无描述步骤。

可达静态反例：当前主模型是无image input的OpenAI兼容模型，vision slot有有效视觉模型，read_image读取合法小图片成功。OpenAIProvider [OpenAIProvider.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/openai/OpenAIProvider.kt:2151) 只在supportsImages为true时序列化ToolResult像素；无视觉模型仅收到第2144行元数据文本，没有视觉组调用、没有OCR或文字描述。工具显示成功却没有实现其对非视觉模型的承诺。这里不说原生视觉模型也必然失败，不重复已有Gemini PW03工具图片序列化缺口。

固定上游ChatViewModel在14063—14087行仍有executeReadImageTool包装：先读取，原生视觉直接返回，否则调用VisionGroupResolver.describe并传customPrompt。本地更换为统一registry后未保留该运行步骤。方向：在现有执行/模型能力边界接回描述流程，沿用已存在的resolver，不重建一套Provider或恢复上游PRoot文件读取。

## RI02 / P2：已有单图URI读取授权仍被额外的相册权限门槛挡住

ImageSourcePolicy接受content://media，包括已有测试第72行的Photo Picker URI。[ReadImageTool.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/ReadImageTool.kt:188) copyMediaImage在openInputStream之前无条件检查mediaPermission；API33+只认READ_MEDIA_IMAGES，未检查现有单URI授权。权限请求不获GRANTED或再次check仍不成功，第195行直接返回拒绝。

[Android官方权限说明](https://developer.android.com/privacy-and-security/minimize-permission-requests)明确Photo Picker无需运行时相册权限，系统会授予用户选中媒体URI的读取权限。因此“能读这个URI”和“拥有READ_MEDIA_IMAGES”并非等价。

静态反例：App通过Photo Picker获得一个仍有效的content://media/picker/...图片URI读取授权，READ_MEDIA_IMAGES未获授予；调用工具读该URI，用户拒绝额外相册权限。代码在尝试已授权URI之前返回permission_denied。该URI单独授权本来允许读取，工具却要求更广权限才能使用。前提明确是现有有效URI grant，不声称知道任意picker字符串就能打开，也不要求绕过过期或拒绝的URI授权。

方向：尊重已获单URI权限，按授权实际尝试读取；只有确实缺少所需访问能力时走适当权限/导出流程。没有弹权限框、读取相册或修改权限。

## RI03 / P3：相册图片的文件大小被写成压缩后JPEG大小

MediaContentUri分支 [ReadImageTool.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/ReadImageTool.kt:65) 将info设为空JSONObject；copyMediaImage虽计算total，只返回null/错误，没有把源文件大小传回。第153—155行重新压缩图片到JPEG，[ReadImageTool.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/ReadImageTool.kt:157) 元数据却用info.optLong(size,imageBytes.size)输出bytes。

因此guest文件分支取源文件info.size，而相册分支必然取本次压缩JPEG字节数。对于尺寸/格式/质量不同的源图片，工具同样展示原始宽高，却配上推理副本大小；用户按schema承诺的file size理解便得到错误数据。方向：记录实际读取源字节数，或将此数字明确标为processed image bytes，避免两种来源不同口径。没有生成图片或测量用户文件。

## 来源解码、资源预算及未计数边界

ImageSourcePolicy第64行URLDecoder仍把字面加号变空格：minis://shared/C++.png或file:///var/minis/shared/C++.png指向错误文件。此前IF01记录同类问题在图片fetcher，本轮确认另一独立helper入口也有同根因，不单独加一条缺陷总数；原始Linux路径字符串未经decode，不受这一转换影响。ImageSourcePolicyTest未覆盖加号。

ReadImageTool通过WorkspaceFileClient.info/readToFile/readAll访问guest，检查普通文件，限制读取MAX_FILE_BYTES；不能把file://样式参数当作直接Root host文件API。图片先bounds解码，再按最长边4000下采样、2000缩放，finally回收bitmap，已有资源控制不能忽略。cache key含session和path，但未追踪所有并发同path覆盖/失败残留场景，不捏造确定的数据串线。

VisionGroupResolver本身按slot顺序取最多3候选、单次90秒、非空描述才成功，复用usableApiKey支持免密钥兼容端点，CancellationException重抛；外围TimeoutCancellationException来源的区分仍是候选。LLMProvider.sendMessage默认temperature=null、thinkingLevel=OFF，因此不能仅凭调用没显式传参认定它使用不兼容温度/思考设置。它的错误文字“成功工具结果”是明确设计；RI01的问题在于连该已实现描述流程都没有进入。

## 固定OpenMinis与测试证据

只读 `/tmp/openminis-review-20261004` HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c` Git对象，未fetch。上游有ReadImageTool、VisionGroupResolver同路径，无ImageSourcePolicy；上游ReadImageTool第87行用file.length作为原文件大小，仍使用旧PRoot路径解析，不能直接恢复。Vision包装的差异见RI01，不用移植注释代替实际调用。

ImageSourcePolicyTest全文111行，覆盖absolute/relative、minis百分号、file host拒绝、media/picker接受、其它content和remote拒绝、空白。只是分类断言，不能证明ReadImageTool实际读取picker URI或调用Vision描述。未运行或新增测试。

## 全文覆盖与复核

| 本轮全文文件 | 行数 |
|---|---:|
| [ImageSourcePolicy.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/ImageSourcePolicy.kt) | 68 |
| [ReadImageTool.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/ReadImageTool.kt) | 246 |
| [VisionGroupResolver.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/VisionGroupResolver.kt) | 313 |
| [ImageSourcePolicyTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/tools/ImageSourcePolicyTest.kt) | 111 |

累计全文 **1199 / 1546文件、231061行**；partial13，not_registered334，excluded124。全仓通读未完成。四文件完整返回；一次宽片段输出截断后已单独重读关键生产分支，没有将截断输出算全文。

完成时校验全部1670 tracked路径SHA-256、字节数和正数文本行数，固定本仓/上游HEAD、tracked/staged空diff、此前107份报告哈希、本报告文件链接与行号、全文批次总计。只新建本报告并更新覆盖台账。
