# 虚拟屏 UserService、目标定位与显示生命周期静态审计

日期：2026-10-05。当前源码基线：`d64e7c06d8159be90737dd9ba03dd9bd5855f339`。本轮单独归档，旧报告保持原内容。

本轮新增 7 项问题，均为 P2。重点是目标定位不能证明唯一却继续执行、启动超时没有覆盖输出读取，以及观察不完整时错误确认“已消失”。这里只完成静态排查与报告，没有修代码，没有运行构建、测试、模拟器、adb、Root 或设备任务。

## 问题索引

| 编号 | 问题 | 触发与影响 |
|---|---|---|
| VS01 | 启动进程先无限读取输出，再检查超时 | 子进程不关闭输出时，超时不可达，服务锁被持续占用 |
| VS02 | 实时目标搜索截断后仍确认唯一 | 旧路径失效、大树中有重复身份时，可能操作错误节点 |
| VS03 | 设置页接受服务拒绝的尺寸 | 合法设置可保存，下一次开屏却失败 |
| VS04 | 重复开屏缓存请求尺寸而非真实尺寸 | 持续服务已有屏幕时，坐标说明和转换依据可能错误 |
| VS05 | Agent Home 与手动 Home 执行不同逻辑 | Agent 发送系统 HOME 键，未进入已实现的 Minis 虚拟桌面 |
| VS06 | 不完整或失败的观察被当成消失证据 | 文本/应用等待可提前成功，向 Agent 返回错误条件结果 |
| VS07 | 预览订阅只绑定 LIVE 布尔值 | 快速换屏或订阅失败后，页面持续 LIVE，却不再收到帧 |

## VS01 — 启动的超时没有覆盖最容易阻塞的读取

[VirtualScreenUserService.kt:389](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/vscreen/service/VirtualScreenUserService.kt:389)用校验后的组件构造 `cmd activity start-activity` 参数向量。问题不在命令拼接：395 行先用 `readText()` 等到整个输出流 EOF，396 行才调用带超时的 `waitFor()`。读取本身没有期限和字节上限。

如果系统启动请求或子进程卡住且保持输出管道打开，执行永远到不了超时及 `destroyForcibly()`。读取抛错时也没有统一的进程清理 finally。不能仅因存在 `waitFor(timeout)` 就认定这条链路有界。

[launch:200](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/vscreen/service/VirtualScreenUserService.kt:200)、开屏和 Home 都在同一个服务锁内调用启动逻辑；dump、release 和 destroy 也需要该锁。因此影响会扩展到整个虚拟屏服务。客户端 [execute:379](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/vscreen/VirtualScreenClient.kt:379)只是到期取消本地 Future，AIDL 没有取消正在运行的启动请求的接口，不能把客户端报超时当成服务端进程已退出。

处理方向：在现有受控启动器中让读取、等待和清理共享同一截止时间，限制输出，失败时结束子进程并回收资源。不要增加通用命令服务。此项是当前 VScreen launcher 的读取顺序问题，与旧报告 DC13 的 ShizukuOffloadHandler 输出上限问题分属不同执行链。

## VS02 — 当前树搜索没有传递截断状态，唯一匹配可能是假唯一

[UiNodeUtils.locate:100](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/vscreen/service/internal/UiNodeUtils.kt:100)把之前观察的 `snapshotTruncated` 交给共享定位策略，但候选来自当前实时搜索。[matchAll:123](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/vscreen/service/internal/UiNodeUtils.kt:123)最多访问 2,000 个节点；达到上限后，队列仍非空也直接返回已找到的列表，没有返回当前搜索是否完整。

[UiRefResolutionPolicy.kt:64](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/UiRefResolutionPolicy.kt:64)只根据传入的旧观察截断标记决定能否相信唯一性，再以 `candidateCount() == 1` 接受候选。当前搜索截断无法影响这一判断。

具体条件：旧观察完整；目标随后移动，原路径身份不匹配；当前树增长至超过 2,000 个可遍历节点；没有平台 uniqueId 的两个按钮具有相同窗口、包名、类、资源 ID、文本和描述；第一个在预算内，第二个在预算外。搜索返回一个候选，服务以 `unique_identity` 接受它并执行 click/set_text/input_text。代码不能证明这是原目标或唯一目标。API 33 以下 [uniqueIdOf:157](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/vscreen/service/internal/UiNodeUtils.kt:157)明确使用空 uniqueId，所以条件有实际支持平台。

App 的 [refAction:121](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/VirtualScreenUiBackend.kt:121)传递的也是旧观察标记，无法弥补服务端当前搜索信息丢失。现有 ObservationRegistry 测试验证旧截断标记传播，不覆盖实时搜索达到上限。

处理方向：保留当前搜索完整性；对没有可验证 uniqueId 的截断候选拒绝唯一身份回退，返回 stale/refused 并要求重新观察。保持搜索有界。

## VS03 — 可保存的分辨率与服务的创建边界不同

[VirtualScreenDisplaySettingsPolicy.kt:18](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/vscreen/VirtualScreenDisplaySettings.kt:18)接受宽 320–1920、高 480–2560，没有像素总量约束。[Preferences:69](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/vscreen/VirtualScreenPreferences.kt:69)以及 [设置页:166](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/VirtualScreenSettingsScreen.kt:166)均使用该策略保存。

实际 [VirtualDisplaySession.create:157](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/vscreen/service/internal/VirtualDisplaySession.kt:157)要求长宽不超过 2400，像素总量不超过 4,194,304。

两种可直接由源码算出的反例：720×2560 被设置页接受，但高度超过服务上限；1920×2400 的长宽均被设置页接受，但 4,608,000 像素超过服务上限。开屏从 [Client:127](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/vscreen/VirtualScreenClient.kt:127)读取这些配置并原样提交，用户只能在下一次开屏时遇到拒绝。

处理方向：复用同一组尺寸及面积约束，在保存时提供有效反馈；服务继续独立校验。当前尺寸策略测试只覆盖单维边界和普通有效配置，没有验证这些跨层反例。

## VS04 — 已有显示不重建，但客户端把请求值记成实际值

[UserService.createDisplay:174](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/vscreen/service/VirtualScreenUserService.kt:174)发现已有 session 就直接返回 displayId，忽略本次 width/height/dpi。这种幂等开屏本身合理。

[Client.openDisplay:128](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/vscreen/VirtualScreenClient.kt:128)却把本次请求的宽高命名为 actualWidth/actualHeight，并在 135 行写入 activeDisplaySize，没有查询服务的真实尺寸。[displaySize:70](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/vscreen/VirtualScreenClient.kt:70)优先相信缓存。

例如服务已有 720×1600 屏幕，随后 `openDisplay(1080,1920,...)` 返回同一 ID，缓存变成 1080×1920。另一个生产可达条件是 daemon 持有屏幕、App 进程重建后按保存配置再次 open；服务生命周期独立于 App，不保证现存尺寸等于这次配置。

实际调用后果可沿 [putDisplayGeometry:95](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/VirtualScreenUiBackend.kt:95)、[coordinateAction:282](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/VirtualScreenUiBackend.kt:282)以及 [screenshot:455](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/VirtualScreenUiBackend.kt:455)核对：给模型的像素范围、坐标换算及截图原始尺寸元数据都使用缓存。部分合法点击可能被服务越界拒绝，截图空间换算可能落到错误位置。后续成功的 displayInfo 查询可以刷新缓存，不能保证工具调用前发生。

处理方向：复用已有 getDisplayInfo 返回实际尺寸后再缓存，或明确拒绝与现存屏幕不一致的重开请求。不能以返回了 displayId 证明显示已按请求变更。

## VS05 — 同一个 Home 含义在两个入口发生分叉

[VirtualScreenUiBackend.kt:45](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/VirtualScreenUiBackend.kt:45)将 Agent 的 home 经 simpleAct/act 送到 [ActRunner.kt:161](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/vscreen/service/internal/ActRunner.kt:161)，只注入 KEYCODE_HOME。

手动预览页 [VirtualScreenViewer.kt:209](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/VirtualScreenViewer.kt:209)调用 client.home，最终进入 [UserService.home:307](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/vscreen/service/VirtualScreenUserService.kt:307)，通过 showHome 显式启动 Minis 的 VirtualScreenHomeActivity。这也是开屏时采用的桌面路径。

项目已经实现自己的虚拟桌面，并在服务注释中解释系统 secondary launcher/HOME 键不能完成同一行为。Agent 路径仍保留旧按键语义，绕过当前桌面入口。能够从源码确认的是两条路径行为不一致；特定 OEM 最终显示空白、旧任务还是系统界面，尚无本轮设备证据。

处理方向：让 act 的 home 复用已有 showHome 行为，同时保留 act 的 settle/新观察流程。

## VS06 — 读取不到与已消失没有区分

文本等待：[waitForText:397](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/VirtualScreenUiBackend.kt:397)取 SIMPLE dump，未检查 truncated/outputTruncated/windowError；`mode=disappear` 只要匹配函数返回 false 就成功。SIMPLE 输出限制文本长度、文本条数及目标条数；[UiBridge.fitToBudget:238](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/vscreen/service/internal/UiBridge.kt:238)还会删掉部分文本和目标以适配响应预算。被截掉的文本仍可真实存在，缺少匹配不是消失证据。

应用等待：[waitForPackage:424](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/VirtualScreenUiBackend.kt:424)将 hasPackageWindow 的 false 直接解释为消失。[UiBridge.windowsOnDisplay:291](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/vscreen/service/internal/UiBridge.kt:291)在平台读取异常时返回 emptyList 并保存 error；[hasWindowOnDisplay:248](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/vscreen/service/internal/UiBridge.kt:248)丢弃 error，只返回 any 的 false。因此即便目标应用还在显示、仅窗口读取失败，disappear 等待也可立即成功。

同类问题还影响 observation：dump 会附 windowError，App 的 [observe:80](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/VirtualScreenUiBackend.kt:80)仍标成功，ObservationRegistry 的输出没有保留 windowError/windowSource/availableDisplays。Agent 可能收到成功的空屏观察，而没有读取失败原因。

处理方向：保留“存在／确定不存在／无法确定”三种结果；只有完整、成功的必要观察才能确认消失。截断仍可用于正向证明已经看到的文本，但不能用于证明未看到的文本不存在。这不是旧报告 DC03 的物理屏逐窗口消失判断问题，本轮是独立的 VScreen 预算及窗口错误传播链。

## VS07 — 换屏后 LIVE 状态不变，帧订阅不会重建

[VirtualScreenViewer.kt:96](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/VirtualScreenViewer.kt:96)每秒读取真实 displayInfo，但 [DisposableEffect:120](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/VirtualScreenViewer.kt:120)只以 live 布尔值作为帧订阅键；onEnded 是空回调，startFrameStream 失败也被 runCatching 吞掉。

[VirtualDisplaySession.release:123](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/vscreen/service/internal/VirtualDisplaySession.kt:123)发送 onEnded 并清掉 sink；新建 session 的 sink 初始为 null。[UserService.startFrameStream:273](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/vscreen/service/VirtualScreenUserService.kt:273)只给当前 session 设置 sink，没有跨 session 的订阅注册。

条件一：预览页打开期间，屏幕 A 被关闭并在两次一秒轮询之间创建 B；轮询前后均 LIVE，effect 不重启。页面显示 B 的尺寸/状态，却仍没有 B 的帧订阅。条件二：第一次订阅失败但 displayInfo 仍成功，live 持续 true，没有重试触发。两者都可能造成画面停止更新，页面没有明确错误。

处理方向：以实际显示身份管理订阅，并处理 onEnded、订阅失败与过期订阅的退出；不要只跟踪是否存在屏幕。还要让旧订阅的异步停止不能误停新订阅，当前 stopFrameStream 没有指定显示或订阅身份。

## 对照、排除与事实边界

固定 openminis 基线为 `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，位于 `/tmp/openminis-review-20261004`。对该提交的完整 `src` tree 清单搜索 vscreen/virtualscreen/virtual.screen，没有同名模块命中。这只能说明本轮实现没有同名上游文件可逐行对照，不能证明上游没有其它显示方案，也不把任何问题直接归因为移植残留。本轮没有联网更新上游 checkout。

继续保持原先的工具归属判断：Shizuku、Binder、显示创建及 Android 输入是平台接线，应由 App 提供窄接口；Agent 工作流可以在沙箱管理。不能把 VScreen 存在于 App 就当成开发工具归属问题。受控 cmd argv 也不是旧 minisd/raw Root RPC。

已核对有效的边界：当前服务身份只接受 shell 2000/root 0，符合现役合同；对 display 0 和非当前显示有拒绝检查；没有在本模块发现 socket listener；密码节点的 text/description/hint 在本轮虚拟屏序列化中会脱敏；截图有 JPEG 字节上限。上述静态事实不代表 OEM 设备实测通过。

撤回 Bitmap 别名疑点：前期怀疑全图 createBitmap 可能返回 padded 本身，而回收造成截图失效。查到 [AOSP Bitmap 源码](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/graphics/java/android/graphics/Bitmap.java)中原对象复用要求 source 不可变；当前 padded 是 createBitmap(width,height,ARGB_8888) 创建的可变对象，因此不把该疑点列为缺陷。[Android Bitmap 官方参考](https://developer.android.com/reference/android/graphics/Bitmap)也区分了该条件。没有把通用 API 描述直接套成当前链路的已发生故障。

尚待核实的设备问题：InputBridge 的事件时间与注入节奏、物理焦点恢复反射的 OEM 行为、ImageReader 行布局兼容性，以及失败重连时真实 Binder/显示资源释放。它们未计入新增问题数。设置页关闭与 viewer 关闭的租约提示不同，只记录为后续一致性线索，没有把用户主动关闭直接定性为权限绕过。

## 待执行的端到端验收提纲

以下均未执行，也不因此启动设备或模拟器。未来获授权修复和验证时，保留同一基线下的请求、响应、显示 ID/真实尺寸、服务状态及可重复步骤作为工件。

1. 对启动进程制造持续不结束的受控故障，确认在截止时间内返回，子进程结束，release/dump 不再被锁阻塞。
2. 在无 uniqueId 的大树中移动旧目标，并将第二个同身份候选置于搜索预算外；动作应拒绝，不得点击预算内候选。保留观察与拒绝响应。
3. 设置 720×2560 和 1920×2400，保存入口与创建入口应给出一致的拒绝；合法默认配置应可正常打开。
4. 保持一个实际尺寸与下一次 open 请求不同的 daemon 显示，核对 open/observe/screenshot 的元数据和坐标转换全部来自实际尺寸。
5. 同一显示分别执行 Agent home 与手动 Home，均进入 Minis 虚拟桌面；保留动作结果和显示内容证据。
6. 将目标文本放到输出预算之外，并制造窗口读取异常；disappear 不能报告条件满足，应保持等待或明确无法确定。
7. 在预览轮询间关闭并重开虚拟屏，以及让第一次帧订阅失败；画面应正确换到新显示或显示可恢复错误，旧停止回调不得影响新订阅。

## 全文阅读覆盖

本轮新增全文阅读 **38 个文件、5,504 行**。累计 **524 / 1,546** 个纳入范围的 tracked 文件已全文阅读，另有 **18** 个只读过片段，**1,004** 个尚无全文登记；累计全文 **111,243 行**。全库 tracked 清单仍为 1,670 个，其中 124 个不属于代码/配置/源资源阅读分母。全库阅读尚未完成。

新增全文文件包括：vscreen 主实现 20 个 Kotlin 文件、2 个 AIDL 文件、5 个模块测试文件；VirtualScreenHomeActivity；VirtualScreenUiBackend、VirtualScreenObservationRegistry、VirtualScreenAppPresencePolicy 及后两者测试；AccessibilityNodeIdentity、UiRefResolutionPolicy、DeviceScreenLease；VirtualScreenSettingsScreen 与 VirtualScreenViewer。具体路径、SHA-256、行数和完整范围见[覆盖台账](/home/jiale/projects/minis-for-android/docs/archive/2026-10-05-code-review-coverage.json)。

AndroidUiController 本轮只核对 65–130 行，登记 partial；AndroidAgentTools 此前已有全文记录，本轮只是回查入口，不重复计入新增全文数。已有测试仅阅读全文，没有执行或新增测试。资料查询和上游 tree 检索均不计入本项目全文分母。
