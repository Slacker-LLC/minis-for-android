# 文件预览 PDF 渲染与源路径审查

日期：2026-10-06。本仓固定 HEAD `d64e7c06d8159be90737dd9ba03dd9bd5855f339`。本轮新增 **1 项 P2、1 项 P3**；补齐 FilePreviewScreen 全文 1218 行。仅静态审查，不修改产品代码，不运行测试、构建、模拟器或设备命令，不占用云资源。旧报告保持原文。

## FP01 / P2：PDF 在显示首屏前批量持有最多 50 页完整位图

[FilePreviewScreen.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/sandbox/FilePreviewScreen.kt:605) 的 PdfPreview 在 IO 协程中循环渲染 min(pageCount,50) 页，每页固定 targetW=1600，按原始高宽比求 targetH，再以 ARGB_8888 创建位图（[FilePreviewScreen.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/sandbox/FilePreviewScreen.kt:618)）。所有位图加入 out；整轮结束后才设置 pages=out。后面的 LazyColumn 只延迟 Image 的组合，没有延迟前面的渲染或分配。

按 [Android ARGB_8888 官方说明](https://developer.android.com/reference/android/graphics/Bitmap.Config#ARGB_8888)，每像素使用 4 字节。静态算例：50 页常见纵向纸张，渲染尺寸约 1600×2263，单是像素合计 1600×2263×4×50=724160000 字节，约 690.6 MiB；不含 PDF 解码器、UI、模型上下文等其他内存。这是源码尺寸计算，不是设备峰值实测。PDF 的压缩文件长度和 guest 50 MiB 读取上限并不能约束解码后的像素总量。

页面高度也没有像素上限，长条页面会进一步放大单页开销。渲染前面的全部页面期间 UI 保持 loading，首屏不能提前显示。catch(Exception) 不是内存预算，也不能保证 OutOfMemoryError 进入外部打开 fallback。Manifest 的 largeHeap=true 不能证明当前设备有足够可用空间；本轮不声称所有 50 页 PDF 必然崩溃。

修复方向：按可见页和有限预取渲染，约束单页及缓存总像素/字节，并在页面离开时取消尚未完成的工作。后续设备验收应记录普通多页、长条页面、滚动和离开时的首屏时间、存活位图总量与峰值内存；本轮未制作 PDF 或执行验收。

## FP02 / P3：guest 文件的“复制绝对路径”复制的是 Android 缓存副本

[FileBrowserViewModel.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/sandbox/FileBrowserViewModel.kt:538) 创建 guest FileItem 时，把真实 guestPath 与 Android cache File 分开保存。[FileBrowserViewModel.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/sandbox/FileBrowserViewModel.kt:550) 的缓存路径是 cacheDir/guest-file-browser/digest-safeName，只是预览副本。

预览页标题在 [FilePreviewScreen.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/sandbox/FilePreviewScreen.kt:212) 已优先使用 guestPath；但更多菜单“复制绝对路径”在 [FilePreviewScreen.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/sandbox/FilePreviewScreen.kt:308) 始终复制 item.file.absolutePath，元数据 Path 在 [FilePreviewScreen.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/sandbox/FilePreviewScreen.kt:1025) 也使用缓存路径。

静态反例：用户从 session 浏览器打开 /var/minis/workspace/report.txt，更多菜单复制出 /data/user/.../cache/guest-file-browser/<摘要>-report.txt。该值既不是原始文件在 guest 内的路径，也不表达 session 工作区；用于 Agent 的 guest shell 或后续文件定位会指向错误位置。真实文件未被删除，复制也没有外发，本项仅是来源/路径语义错误。

这不同于旧 MP04 的画廊跨目录取缓存图片：同一个普通文本文件、不进入图片画廊也能触发。修复方向：有 guestPath 时使用真实 guest 路径，同时保留其 session 语义；只在普通 host 文件场景返回 host 路径。

## 固定 OpenMinis 对照与去重

只读已有 `/tmp/openminis-review-20261004` Git 对象，固定 HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，没有 fetch。

- FP01：上游 FilePreviewScreen.kt 570—598 同样先渲染最多 50 页、宽 1600、全部加入 out 后设置 pages，属于共享缺口。
- FP02：上游元数据同样使用 item.file.absolutePath（1222），但其 FileItem 为真实 host 文件。当前本地新增 guest 缓存适配后，该假设不成立，不能将错误 guest 路径结果原样归因于上游。

此前 memory-preview 报告 MP04—MP08 已记录画廊缓存混用、Markdown 静默截断、先完整 readBytes 再裁剪与主线程打印、CSV 物理行拆分、另存输出流为 null 仍成功；此次全文复核不重复计数。ImagePreview 完整解码及 saveImageToGallery 位于前置图片画廊 return 之后，不拿被绕过的实现新增图片失败问题。FilePreviewScreen 本身没有文件编辑保存入口，未把其它编辑页的问题套到此页。

## 覆盖与复核

本轮全文：[FilePreviewScreen.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/sandbox/FilePreviewScreen.kt)，1218 行，按 1—240、241—490、491—740、741—980、981—1218 分段干净读取。关联 Browser、Manifest 与旧报告只复核必要证据，未重复计入全文。没有运行任何已有测试，也没有新增测试。

累计全文 **1004 / 1546 个文件、207813 行**；partial 13，not_registered 529，excluded 124。全仓通读尚未完成。

完成时核对全部 1670 tracked 路径、SHA-256、字节数和正数文本行数，固定本仓与上游 HEAD、tracked/staged 空 diff，此前 75 份报告哈希，报告文件链接及行号，以及台账与批次总计。没有访问用户真实文档或凭据。
