# HTML 预览与保留 Web App 链路静态审计

日期：2026-10-06。项目 HEAD：`d64e7c06d8159be90737dd9ba03dd9bd5855f339`。

本轮新增 **5 项现役预览问题（1 项 P1、3 项 P2、1 项 P3）**，另记 **6 项关闭入口中的保留风险**。两组不合并成现役问题总数。仅阅读源码、历史报告与平台文档；没有改产品代码、运行构建或测试、启动模拟器、连接设备或调用云资源。

上游对照固定为 [OpenMinis/OpenMinis](https://github.com/OpenMinis/OpenMinis/tree/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c)，提交 `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`。上游代码只作为对照，不能覆盖本地当前事实。

## 可达性与职责边界

现役 HTML 预览从 [ChatScreen.kt:2297](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatScreen.kt:2297) 的 HTML 文件动作进入；[2204](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatScreen.kt:2204) 构造 `file://`，由 [2208](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatScreen.kt:2208) 创建 WebViewHolder。预览弹层与全屏共享这个 WebView。它在 Android App 内渲染，不能把 guest 文件路径校验当成浏览器后续请求的权限边界。

Web App 添加入口当前关闭：

- [ChatScreen.kt:5017](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatScreen.kt:5017)：附件菜单使用 `false && isHtmlAttachment`，长按入口第 4928 行也关闭。
- [FileBrowserScreen.kt:459](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/sandbox/FileBrowserScreen.kt:459)：文件浏览器菜单使用 `false && isHtml`。
- [WebPreviewBottomSheet.kt:90](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/preview/WebPreviewBottomSheet.kt:90)：`WEBAPP_PIN_ENTRY_ENABLED=false`，第 213 行据此隐藏预览中的固定入口。

保留的 [WebAppActivity:405](/home/jiale/projects/minis-for-android/src/android/app/src/main/AndroidManifest.xml:405) 仍导出、没有独立 process 配置。但它先在 [WebAppActivity.kt:141](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/webapp/WebAppActivity.kt:141) 获取 shortcut ID，再在第 150 行查询数据库记录；不是传入任意路径就能打开文件。下述 WA 风险需要已有有效记录或将来恢复入口，不能写成正常 UI 现在就能创建并触发。

## HP01 — P1：预览 HTML 脚本获得过宽的宿主文件访问

**证据：** [WebViewHolder.kt:50](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/preview/WebViewHolder.kt:50) 创建宿主 WebView；第 51 行启用 JavaScript，第 53 行启用文件访问，第 60、62 行显式启用 `allowFileAccessFromFileURLs` 与 `allowUniversalAccessFromFileURLs`。整个 client 没有针对文件子请求的目录限制。

**问题链：** Agent 输出的 HTML 经 guest 文件 API 取出 → App 用 file URL 渲染 → 页面脚本的后续读取使用 WebView 的宿主文件访问策略。初始 HTML 来源在 guest 内，不会把宿主文件请求重新送回 session 文件授权。源码注释将 guest 输出直接视为可信内容，无法证明 HTML 内的脚本受到 App 控制。

[Android 官方说明](https://developer.android.com/privacy-and-security/risks/webview-unsafe-file-inclusion) 明确指出这组配置允许脚本访问 App 可访问的本地文件，包括 App 自有私有数据。这里确认的是错误的权限配置与现役调用链；未执行读取敏感文件或外发数据的验证。它不等于获得 Root，也不等于能读取其它 UID 的私有文件。

**上游：** [固定版本 WebViewHolder.kt:68](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/ui/preview/WebViewHolder.kt#L68) 同样启用这组设置，属于共有问题。把 PRoot 注释换成 guest 并没有改变浏览器权限。

**处理方向：** 复用已有资源加载模式，对预览资源建立明确的根目录与 session 边界，关闭 file URL 脚本的广域访问。后续 E2E 使用无敏感内容的宿主私有 canary，验证预览目录外读取被拒绝，同时验证合法相对资源仍能加载。仅记录验收方向，未执行。

## HP02 — P2：JS 对话框在正常创建路径中全部走取消分支

**证据：** [ChatScreen.kt:2198](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatScreen.kt:2198) 取 `context.applicationContext`，第 2208、2246 行都用它创建 holder；[WebViewHolder.kt:50](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/preview/WebViewHolder.kt:50) 直接传给 WebView，没有可切换 Context 的 wrapper。

而 [onJsAlert:197](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/preview/WebViewHolder.kt:197)、[onJsConfirm:213](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/preview/WebViewHolder.kt:213)、[onJsPrompt:231](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/preview/WebViewHolder.kt:231) 都要求 `view.context is Activity`，否则立即 cancel 并声称已处理。

**根因：** 把 View 挂到 Activity 的界面不会把构造时的 Application Context 换成 Activity。[Android 16 的 View 源码](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-16.0.0_r1/core/java/android/view/View.java#L5489) 在构造时保存 `mContext`，`getContext()` 返回这个字段。源码中“挂载后就是 Activity”的说明不成立。

**影响：** 正常 HTML 预览中 alert 不展示，confirm 得到取消，prompt 得到取消结果。依赖用户确认或输入的页面功能无法完成。不是 JS 永久阻塞：代码确实结算了 JsResult，只是用户没有选择机会。

**上游：** [固定版本第 232 行](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/ui/preview/WebViewHolder.kt#L232) 有相同假设与取消分支。

**处理方向：** 显示对话框时显式获得当前有效的 UI 宿主，并处理弹层/全屏转换与销毁。后续 E2E 分别核对三种对话框的确定、取消与返回值；未执行。

## HP03 — P2：页面自动导航被当成用户主动打开外部 App

**证据：** [WebViewHolder.kt:114](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/preview/WebViewHolder.kt:114) 的导航回调无条件传 `Origin.USER_INITIATED`，没有检查 `request.hasGesture()` 或主框架身份。

[BrowserExternalSchemeHandler.kt:121](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/browser/BrowserExternalSchemeHandler.kt:121) 因这个身份允许未知 scheme，第 241–246 行构造 ACTION_VIEW 并启动 Activity。`intent:` 还走第 194 行的 Intent 解析路径。

**触发与影响：** 页面脚本或导航重定向在没有用户点击的情况下请求外部 scheme，仍被赋予“用户明确点击”的身份，可以拉起处理该 scheme 的应用。可见 WebView 不代表其中每次导航都是人的意图。这一调用链丢掉了统一 handler 区分调用来源的目的。

[平台请求 API](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-16.0.0_r1/core/java/android/webkit/WebResourceRequest.java#L45) 提供手势信息，但也说明部分真实手势链可能返回 false；修复不能简单牺牲合法用户流程。应明确自动导航与实际用户动作的授权规则。外部 Activity 是否存在、具体页面重定向是否抵达回调，仍需设备验证；本轮未拉起其它 App，更未执行拨号、发信或购买动作。

**上游：** [固定版本第 151 行](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/ui/preview/WebViewHolder.kt#L151) 也无条件传这个身份。

**后续验收：** 用无副作用的测试接收端区分用户点击与页面自动跳转，保留导航来源和实际 Activity 启动次数；未执行。

## HP04 — P2：渲染进程退出没有隔离为可恢复的预览故障

**证据：** [WebViewHolder.kt:99](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/preview/WebViewHolder.kt:99) 的 client 没有覆盖 `onRenderProcessGone`，holder 也没有通知所有者替换失效 WebView 的状态；[ChatScreen.kt:2206](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatScreen.kt:2206) 仅按 URL 是否变化决定重建。

**影响：** 渲染进程崩溃或被系统回收时，默认回调返回 false。按 [平台源码合同](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-16.0.0_r1/core/java/android/webkit/WebViewClient.java#L544)，这种未处理退出会导致宿主应用崩溃或被终止。损失范围可能从当前预览扩大到聊天与同进程运行中的工作。本轮没有诱发 renderer 故障；设备回收策略与实际丢失的状态未实测。

**上游已处理：** [固定版本 WebViewHolder.kt:124](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/ui/preview/WebViewHolder.kt#L124) 销毁旧 holder、设置 `isDead` 并返回已处理；[共享处理器第 55 行](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/ui/webview/WebViewRenderProcess.kt#L55) 返回 true。本地没有这一恢复链。

**处理方向：** 退出时清理对应实例、报告失效并让所有者重建；不能只返回 true 后继续复用死 WebView。平台提示同一 renderer 可能影响多个 WebView，完整修复应核对所有相关 client。后续 E2E 保留宿主存活、旧实例释放、重新打开预览及聊天状态证据；未执行。

## HP05 — P3：切回移动模式没有恢复初始 viewport 设置

[WebViewHolder.kt:79](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/preview/WebViewHolder.kt:79) 初始化移动模式时，`useWideViewPort` 与 `loadWithOverviewMode` 都是 true。用户通过 [WebPreviewBottomSheet.kt:208](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/preview/WebPreviewBottomSheet.kt:208) 切到桌面，再切回移动时，[WebViewHolder.kt:353](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/preview/WebViewHolder.kt:353) 把两者都设成 false 并 reload。

**影响边界：** UI 同样显示移动模式，首次打开与往返切换后的 viewport 行为却不同。初始化为 true 正是源码为 viewport 布局增加的处理，切换路径撤销了它。实际页面是否出现空白、具体缩放差异需要渲染证据，本轮不宣称已复现空白。

**上游：** [固定版本第 388 行](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/ui/preview/WebViewHolder.kt#L388) 有相同的不对称恢复。

**后续验收：** 同一 HTML 首次移动显示与移动→桌面→移动的 viewport/缩放设置及画面应一致；未执行。

## WA：关闭入口中的保留风险

以下六项不计入上面的五项现役问题；恢复功能时须重新验证调用链。

### WA01 — Cookie 隔离设置实际改动全 App WebView

[WebAppActivity.kt:293](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/webapp/WebAppActivity.kt:293) 调 singleton 的 `setAcceptCookie(false)`，而现役 [WebViewHolder.kt:94](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/preview/WebViewHolder.kt:94) 和 [BrowserUseManager.kt:111](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/BrowserUseManager.kt:111) 都设 true。[平台文档](https://developer.android.com/reference/android/webkit/CookieManager#setAcceptCookie(boolean)) 定义前者影响 App 的所有 WebView；third-party 设置才是针对传入实例。

有效 Web App 打开时可能改变已创建浏览器的 cookie 行为，随后创建预览又会反向改变 Web App 的设置。[销毁:536](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/webapp/WebAppActivity.kt:536) 没有恢复，但简单恢复也不能解决并存实例相互覆盖。固定上游第 294 行同样设 false。这里只确认全局策略冲突，不宣称 cookie 已被删除。

### WA02 — 不同 Web App 共用 origin 与资源目录

[WebAppActivity.kt:485](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/webapp/WebAppActivity.kt:485) 开启 JS 和 DOM storage；[532](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/webapp/WebAppActivity.kt:532) 把每个 HTML 都加载为同一 `https://appassets.androidplatform.net/<filename>`。文件名中的 UUID 不改变 origin。[Web Storage 标准](https://html.spec.whatwg.org/multipage/webstorage.html#dom-localstorage) 按 origin 提供 localStorage，因而两个有效 Web App 可读取或覆盖相同存储键。

另外 [WebAppPathResolver.kt:104](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/webapp/WebAppPathResolver.kt:104) 把不同 shortcut 的 HTML 都放进 `cache/webapp`；[WebAppActivity.kt:500](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/webapp/WebAppActivity.kt:500) 暴露的是当前文件的整个父目录。知道另一缓存文件名时，没有每个 shortcut 的目录边界。本轮没有证明可列目录、猜出 UUID 或读取 Room 数据；Activity 的 file access 设置实际是关闭的，不能与 HP01 混为一谈。固定上游也用同一 origin，本地 guest staging 新增了共同缓存目录。

### WA03 — 确认之前写入 guest，取消和失败没有对应回收

[AddToHomeSheet.kt:152](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/webapp/AddToHomeSheet.kt:152) 一进入附件表单就复制到 `cache/webapp-source/<id>`，第 174 行立即写 guest attachments；用户还没按确认。[取消:371](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/webapp/AddToHomeSheet.kt:371) 仅关闭表单，第 184 行失败分支也没有删除可能已写出的局部缓存。

确认后的第 399 行又先持久化实体，pin 返回 false 后第 432 行同样只关闭表单。本链没有清理这些来源文件/实体的补偿。缓存可能以后被系统清除，guest 文件可能随 session 删除；不能据此称永不回收，但取消一次已经留下本不应提交的持久副作用。单文件预算也不能限制重复打开/取消的累计占用。

### WA04 — 提交固定请求就提示“成功”

[ShortcutPinner.kt:54](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/webapp/ShortcutPinner.kt:54) 用空结果回调返回 requestPinShortcut 的 Boolean；[AddToHomeSheet.kt:425](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/webapp/AddToHomeSheet.kt:425) 收到 true 就提示 pin success。

[平台文档](https://developer.android.com/reference/android/content/pm/ShortcutManager#requestPinShortcut(android.content.pm.ShortcutInfo,%20android.content.IntentSender)) 明确区分请求返回与用户实际同意，用户拒绝时也不会回调。这里可能提前报成功并保留未固定记录。`WebPreviewShortcut` 的文案只是“已请求”，不把正确的另一实现一起计为此缺陷。建议将用户可见状态与请求/完成阶段一致，验证同意、拒绝与 launcher 不支持；未执行。

### WA05 — guest staging 只复制 HTML，原目录相对资源丢失

[WebAppPathResolver.kt:107](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/webapp/WebAppPathResolver.kt:107) 只读取选定文件，并重命名到共同缓存。WebView 的资源根由 [WebAppActivity.kt:503](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/webapp/WebAppActivity.kt:503) 设为缓存父目录，而非 guest 源目录。原目录中 `style.css`、`app.js`、图片或其它 HTML 不会随这次读取出现；依赖它们的多文件网页缺少资源。

内嵌全部资源的单文件 HTML 不受此问题影响。不能把关闭 file access 本身当作根因；问题是 guest 文件桥接没有维护资源目录语义。后续验收应覆盖合法兄弟资源、嵌套目录和越界引用。此前 MP04 是图片画廊扫描扁平缓存，与本项 HTML 资源加载不同。

### WA06 — 预设图标选择没有进入最终位图

[AddToHomeSheet.kt:309](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/webapp/AddToHomeSheet.kt:309) 展示不同的 IconPreset，用户选择会保存 preset 名。但 [542](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/webapp/AddToHomeSheet.kt:542) 的最终绘制分支完全不读 `choice.preset`，总是绘制标题首字母。同一标题选 Globe、Book 等最后得到相同图标。后续验收比较选择项与最终 launcher 图标位图；未执行。

## 去重、排除与待核对

1. **旧解码问题的新调用点，不新增问题计数。** [WebPreviewBottomSheet.kt:459](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/preview/WebPreviewBottomSheet.kt:459) 在已经解码的 `Uri.path` 上再 URLDecoder.decode；`demo+1.html` 会被改成 `demo 1.html`，百分号可能二次解码或在 runCatching 内失败。弹层第 206 行、[全屏第 229 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/preview/WebPreviewFullscreenScreen.kt:229) 都调用它。属于此前 HK02 已记录的同类解码错误，这里只补充具体入口，不修改旧报告。[Uri 文档](https://developer.android.com/reference/android/net/Uri#getPath()) 明确说明 path 已解码；固定上游第 476 行相同。
2. **撤销普通快捷方式“切换目标不刷新”的怀疑。** Activity 虽没有 onNewIntent，但 [ShortcutPinner.kt:43](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/webapp/ShortcutPinner.kt:43) 设置 NEW_TASK 与 CLEAR_TASK，不能只凭 singleInstance 就断言正常 pinned shortcut 会复用旧页面。没有把这条猜测列成问题。
3. WebAppActivity 限制外部导航，并不承诺拒绝所有外部子资源网络请求；没有把两者混为一谈，也没有新增“offline 被突破”的结论。
4. HTML 图标解析的相对路径、图片解码预算及 inferScope 对现役 mount 注册方式的兼容仍需进一步调用方核对，未单凭孤立函数把它们计为现役缺陷。

## 全文覆盖

本轮新增全文阅读 **10 个文件、3,090 行**：

| 文件（相对 `src/android/app/src/main/java/com/openminis/app/`） | 行数 |
|---|---:|
| webapp/AddToHomeSheet.kt | 560 |
| webapp/ShortcutPinner.kt | 60 |
| webapp/WebAppActivity.kt | 553 |
| webapp/WebAppIconExtractor.kt | 54 |
| webapp/WebAppPathResolver.kt | 124 |
| ui/preview/WebViewHolder.kt | 437 |
| ui/preview/WebPreviewShortcut.kt | 200 |
| ui/preview/WebPreviewBottomSheet.kt | 487 |
| ui/preview/WebPreviewFullscreenScreen.kt | 357 |
| ui/browser/BrowserExternalSchemeHandler.kt | 258 |

ChatScreen、Manifest、BrowserUseManager、数据库 repository 与旧报告仅作相关片段复核，不重复计入本轮全文。上游片段和平台文档也不计入本地覆盖。

累计全文 **691 / 1,546 个纳入文件（44.7%）、143,018 行**；部分阅读 13 个，尚无全文登记 842 个。全库 tracked 1,670 个，排除 124 个。以 [覆盖台账](/home/jiale/projects/minis-for-android/docs/archive/2026-10-05-code-review-coverage.json) 的 SHA-256、行数和完整读取范围为准，不把全文覆盖等同于运行验证。

交付核对：tracked 文件路径与 SHA-256、HEAD、tracked/staged diff、43 份既有报告哈希以及本报告本地引用的存在与行号范围均复核。本轮只新增本报告并更新覆盖台账，未修改既有报告或产品代码。
