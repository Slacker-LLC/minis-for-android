# 图片查看页手势切换与相册保存结果静态审查

日期：2026-10-06。固定本仓HEAD `d64e7c06d8159be90737dd9ba03dd9bd5855f339`。新增 **2项P3确认缺陷**；全文读取3文件、1023行。未修改产品源码，未运行测试、构建、模拟器、设备、Root、云或模型请求。旧报告不改。

## IV01 / P3：关闭拖动切换到双指缩放后，位移及隐藏按钮状态没有复位

[ZoomableImagePage.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/components/ZoomableImagePage.kt:110) 单指垂直移动超过slop进入CLOSE，第114行累计dragY、第115行向父页面发送非零dismissProgress。此后第二根手指按下，第86—87行直接把mode改成TRANSFORM；缩放分支仅处理scale及offsetX/Y，没有清除dragY或发送零进度。

双指全部松开后，循环结束，第121行只对mode==CLOSE执行关闭/回弹。最后mode为TRANSFORM，这个退出分支被跳过，dragY与父级dismissProgress保留。后续单击只切showChrome，双击只改scale和offset，也都不清dragY。

实际聊天附件入口 [ChatUserMessageUI.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatUserMessageUI.kt:628) 调用ImageGalleryViewer；gallery第336行接入该组件，第205/228行只有showChrome且dismissProgress==0才显示关闭和复制/分享/保存按钮。FilePreviewScreen第187行以及ChatScreen第6637行也使用同一gallery。FullscreenImageViewer第195行同样接入组件，第204/226行具有相同可见条件。

静态交互反例：未缩放图片先单指下拖约40dp（超过slop，低于120dp关闭阈值），保持第一指按下，再加入第二指捏合放大，最后双指抬起。代码路径结束在TRANSFORM，图片仍带关闭拖动位移、缩小/淡背景，操作栏持续隐藏；点击不会恢复。系统返回仍可关闭，故不说用户彻底无法退出。取消事件、回弹动画和新手势同时写dragY还有额外候选，本轮没有实测，不另计。

方向：明确CLOSE转TRANSFORM及各退出/取消分支的拖动状态收尾，保留现有手势实现，避免只根据最后mode决定复位。本轮只记录。固定OpenMinis没有ZoomableImagePage同路径；上游ImageGalleryViewer第332—381行直接使用detectTransformGestures，没有本地关闭拖动状态，不能把其旧手势代码整体恢复当成需求修复。

## IV02 / P3：相册保存失败可以报成功，异常写入还会留下未清理的记录

现役gallery [ImageGalleryViewer.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/components/ImageGalleryViewer.kt:307) loadBitmap后调用saveToGallery，依据Boolean显示成功/失败toast。公共helper [FullscreenImageViewer.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/components/FullscreenImageViewer.kt:465) 在API29+先插入IS_PENDING=1记录，再openOutputStream；第468行使用stream?.use，没有把null判为失败，也没有检查Bitmap.compress返回值。第471行取消pending但不检查update结果，第480行直接返回true。

[Android ContentResolver官方合同](https://developer.android.com/reference/android/content/ContentResolver#openOutputStream(android.net.Uri))允许openOutputStream在provider刚崩溃时返回null。[Bitmap.compress官方合同](https://developer.android.com/reference/android/graphics/Bitmap#compress(android.graphics.Bitmap.CompressFormat,%20int,%20java.io.OutputStream))以Boolean表示写入成功。因此这是需要处理的失败结果，而非只有抛异常才算失败。

静态反例：insert成功，openOutputStream返回null，随后provider恢复，update成功。没有任何像素写入，helper仍返回true，界面报已保存，空记录被发布。即使有流，compress返回false也被同样忽略。若open/write/close/update抛异常，第481行只返回false，没有按插入URI删除或清理记录，可能留下pending/部分写入条目；没有重试或恢复清理入口。这里不声称所有OEM都出现这类失败，也不把provider崩溃本身算成App错误，缺陷在于结果处理及失败清理。

同一保存按钮在loadBitmap返回null时第308行直接结束，连saveFailedMsg也不显示；FullscreenImageViewer第291—296行相同。作为保存入口失败反馈不完整的同一问题记录，不增加第三项。API26—28分支同样忽略compress Boolean，且未调媒体扫描；后者需要系统可见性证据，暂不计数。

此前MP08是FilePreviewScreen“另存文件”的SAF输出流为空仍提示成功。本轮是共享图片查看页MediaStore插入/发布，实际走gallery早返回后的现役入口，不以FilePreviewScreen被绕过的旧saveImageToGallery函数作为生产证据；两条调用链不同，修复MP08并不会解决这里。

方向：只在输出流、压缩、发布都确认成功后报告成功，失败时清理本次插入记录并显示明确反馈；保留IO调度。本轮未修改或写相册。固定OpenMinisFullscreenImageViewer第601—631行仍有同样stream?.use、不检查compress/update及异常无清理结构，这一项上游也未解决。

## 其它已读边界与未计数候选

loadBitmap及PNG编码位于IO dispatcher，不能说现有复制/保存必然在主线程压缩。share按钮有sharing标记及finally回收，并设置URI读授权与NEW_TASK；未凭缺少Activity context认定分享必然失败。save/copy没有同样的在途去重，但没有资源实测，不新增OOM条目。

copyBitmapToClipboard使用grantUriPermission("*",...)，其是否为平台有效目标包需沿AOSP实现核对，不能仅根据注释“授权所有包”认定已经做到；本轮不新增该项。缓存命名用毫秒时间戳、ImageLoader逐次创建及未显式shutdown也作为候选，没有测量泄漏或碰撞。

画廊全局dismissProgress由每页上报，新页没有主动重置；其在实际分页/复用状态中的触发尚需核验。窗口walker返回Activity.window而非显式DialogWindowProvider，外层也操作Activity系统栏；是否真实造成沉浸失效或错误恢复未取得设备证据，不直接加缺陷。未把评论中的iOS对齐说明当成运行证据。

## 全文覆盖与复核

| 新增全文文件 | 行数 |
|---|---:|
| [ImageGalleryViewer.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/components/ImageGalleryViewer.kt:1) | 359 |
| [ZoomableImagePage.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/components/ZoomableImagePage.kt:1) | 155 |
| [FullscreenImageViewer.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/components/FullscreenImageViewer.kt:1) | 509 |

FullscreenImageViewer按1—260、261—509完整阅读，其余完整单次输出；上游Gallery片段输出截断后已独立重读第332—381行。本仓未发现同名gallery/viewer专用测试，不声称全仓无其它UI覆盖。未运行或新增测试。

累计全文 **1203 / 1546文件、232421行**；partial13，not_registered330，excluded124。全仓通读尚未完成。只读固定上游本地Git对象HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，未fetch，也没有恢复旧PRoot/Alpine路径。

完成时校验全部1670 tracked路径SHA-256、字节数与正数文本行数，本仓/上游HEAD、tracked/staged空diff、此前109份报告哈希、本报告文件链接及行号、台账全文批次总计。只新建本报告并更新覆盖台账。
