# 图片 URI 解析与显示尺寸上限审查

日期：2026-10-06。固定 HEAD `d64e7c06d8159be90737dd9ba03dd9bd5855f339`。本轮新增 **1 项 P2**；全文阅读 4 个文件、464 行。静态审查，未改产品源码，未运行任何测试、构建、模拟器、设备、Root 或云任务，未创建或解码大图片。独立报告，旧报告不改。

## IF01 / P2：将 URI 路径按表单解码，带加号的图片读取错误文件

[MinisImageFetcher.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/MinisImageFetcher.kt:36) 去掉 minis:// 和 query 后，直接 java.net.URLDecoder.decode。[MinisImageFetcher.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/MinisImageFetcher.kt:137) 的缓存 key 路径也使用同一解码器。[Java URLDecoder 官方说明](https://docs.oracle.com/en/java/javase/24/docs/api/java.base/java/net/URLDecoder.html)规定该 API 解码 application/x-www-form-urlencoded，并将加号转换为空格。URI 路径中的字面加号不能按表单空格解释。

静态输入 minis://shared/C++.png 会解析为 /var/minis/shared/C  .png，而不是 /var/minis/shared/C++.png。随后第 50 行为错误路径计算 digest，第 54 行 WorkspaceFileClient.readToFile 读取错误目标；mtime key 也查询相同错误路径。只有真实 C++.png 时请求失败；同时存在 C  .png 时，原 URI 显示的是另一文件。不是哈希碰撞、不需要跨 session 路径逃逸，也不是指向 Root 所有文件。%2B 正确解成加号，不属于该反例；本项针对合法的字面加号路径。

现役 [MinisApp.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/MinisApp.kt:1224) 注册 String/Uri 两种 fetcher 及 mtime keyer。[MarkdownText.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/markdown/MarkdownText.kt:768) 将 Markdown 图片 block.url 直接交 AsyncImage，没有先把字面加号编码为 %2B。MarkdownText 的媒体 resolver 第 692 行，以及 StreamingMarkdownText 的 session resolver [StreamingMarkdownText.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/StreamingMarkdownText.kt:2408) 也用 URLDecoder 解析 minis 路径，存在同一解码选择问题，只作为同一项的调用范围记录，不逐个计数。

旧 HK02 记录的是导航参数已由 Navigation 解码后再次 URLDecoder 的重复解码；这里是图片路径第一次解码选择了表单 API，既有导航问题的修复并不能自动覆盖 fetcher。本轮没有重复登记导航故障或 MP04 缓存画廊混入其它会话问题。

修复方向：复用 URI 路径的百分号解码规则，保证 +、%2B、%20 与字面百分号的编码约定明确；fetch/key/resolver 共用一致语义。只记录，未创建真实图片、访问用户文件或编写测试。

## 显示上限与已有测试核对

DisplayBitmapLimits 的 request size 为 4096×4096，precision 为 INEXACT；现役 [StreamingMarkdownText.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/StreamingMarkdownText.kt:2081) 在聊天 Markdown 的 ImageRequest 中应用它。普通 MarkdownText 图片的 heightIn(max=360.dp) 与聊天纵向布局不同，不能仅因没有调用同一 helper 就推断它必然按原图解码或已经 OOM。

77 行 JVM 测试主要验证常量与 4 bytes/pixel 算术、报告大图超限和小图尺寸，没有执行真实 decoder。160 行 instrumented 测试构造 3000×17920 PNG，通过 Coil 分别 capped/uncapped 解码，再用 RenderNode recording canvas 尝试绘制。它实际会申请大 bitmap，未运行，不能把源码中的测试说明当作当前设备验证结果。

instrumented 的 drawsWithoutThrowing 捕获任意 Throwable，仅用 Boolean 区分，未验证 uncapped 失败一定为目标 Canvas 大图异常；测试 fixture 本身也可因分配失败中断。它覆盖一个 PNG 和测试设备 decoder，不证明所有 GIF、硬件 bitmap、颜色配置、OEM GPU 都符合“总是 drawable”的注释。上述是验证证据的限制，不单独计生产故障，也没有新建测试。

Fetcher staging 使用 WorkspaceFileClient → SecureFileAccess，先复制到临时文件再替换目标；MAX_FILE_BYTES 限制源文件字节数，与解码后的像素内存是不同边界。mtime key 中 runBlocking(IO) 是否阻塞 UI 还要核对 Coil 实际调用线程，不仅凭 helper 名称就计主线程 IO。相同目标的并发 staging、mtime 原值相同的替换与缓存键语义都是后续候选，未凭可能性宣称缓存必然污染。

Fetcher 明确拒绝没有会话上下文的 session 根，且 WorkspaceFileClient 对真实路径仍检查，不把字符串前缀判断单独当作访问控制实现或证明越界。聊天 renderer 有带 session 的独立 staging 路径，不能将该 fetcher 的拒绝扩大成全部会话图片均不可显示。

## 固定 OpenMinis 对照

只读 `/tmp/openminis-review-20261004` HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，未 fetch。上游 MinisImageFetcher 的 resolve 第 189 行同样用 URLDecoder 解路径，保留字面加号转空格条件；它另有 session 参数、直接 session 文件与跨 session fallback，边界与本地不同。不建议为修复文件名恢复跨会话兜底或旧 runtime。

本轮只比较上游有关解析片段，不将上游整个 decoder/缓存行为认定已通过，也不把上游文件计入本仓覆盖。

## 全文覆盖与复核

| 本轮全文文件 | 行数 |
|---|---:|
| [DisplayBitmapLimitsInstrumentedTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/androidTest/java/com/openminis/app/ui/DisplayBitmapLimitsInstrumentedTest.kt) | 160 |
| [DisplayBitmapLimits.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/DisplayBitmapLimits.kt) | 69 |
| [MinisImageFetcher.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/MinisImageFetcher.kt) | 158 |
| [DisplayBitmapLimitsTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/ui/DisplayBitmapLimitsTest.kt) | 77 |

累计全文 **1147 / 1546 个文件、224121 行**；partial 13，not_registered 386，excluded 124。全仓通读尚未完成。已有测试合计 237 行全文静态阅读；调用方截断片段已单独重读，已 full 文件不重复计数。

完成时核对全部 1670 tracked 路径 SHA-256、字节数与正数文本行数，固定本仓/上游 HEAD、tracked/staged 空 diff、此前 95 份报告哈希、本报告文件链接/行号和批次全文总计。只新建本报告并更新覆盖台账，未使用设备或云资源。
