# rclone 桥接与目录创建导航静态审查

日期：2026-10-06。新增 1 项 P3。独立报告，只更新阅读台账；不改产品代码与旧报告。

本地 HEAD `d64e7c06d8159be90737dd9ba03dd9bd5855f339`；OpenMinis 固定对照 `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，只读既有对象，没有拉取或执行。未构建、运行测试、模拟器、adb、Root、设备、云任务、凭据或子 Agent；未连接远程服务器、调用 rclone 或创建目录。

## RB01 · P3 · 创建目录后的自动进入必被 busy 检查拦截

正常“新建文件夹”确认按钮 [RcloneDestinationsScreen.kt:553](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/backup/RcloneDestinationsScreen.kt:553)调用 vm.createFolder。[RcloneDestinationsViewModel.kt:169](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/backup/RcloneDestinationsViewModel.kt:169)先置 busy=true，再创建远程目录和刷新父目录列表；178行更新的是 b.copy(entries=entries)，仍保留旧 path。

随后 [RcloneDestinationsViewModel.kt:180](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/backup/RcloneDestinationsViewModel.kt:180)调用 navigateTo(newPath)，意图进入新建目录。但 [RcloneDestinationsViewModel.kt:150](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/backup/RcloneDestinationsViewModel.kt:150)在 busy=true 时立即返回。busy 只在这次调用之后的 finally（184行）清为 false，不存在重新调用导航的后续步骤。这是顺序控制流必然结果，不依赖网络竞争或时序概率。

影响：远程目录成功创建后界面仍停留在父目录，新增目录会出现在列表中，用户可以手动点击进入。[RcloneDestinationsScreen.kt:513](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/backup/RcloneDestinationsScreen.kt:513)“使用此文件夹”保存当前 b.path，因此若用户以为新建后已经自动进入而直接确认，会保存父目录。这里不声称路径标签被伪造或不能手动纠正；按有明确绕过方式的交互错误记为 P3。

固定上游 [RcloneDestinationsViewModel.kt:239](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/ui/settings/backup/RcloneDestinationsViewModel.kt#L239)同样在 finally 清 busy 前调用 navigateTo，209行有同一 busy 拒绝。共享缺口。

修复方向记录：在创建操作自己的流程内完成新目录列表与 path 更新，或在结束创建的 busy 生命周期后调用导航；保持防重复操作，避免提前释放 busy 后产生重入。优先复用已有 browse 状态与导航行为，不加额外层。本轮不实现、不执行目录操作或测试。

## 桥接、测试与未升级观察

全文阅读 RcloneBridge 的初始化、options/set、TLS 开关、RPC 状态检查、JSON 参数递归转换与诊断 helper。rpc 在200但输出无法解析时返回空 JSONObject，设置错误只留日志等行为仍需对照真实 Go 输出与调用方，未仅凭 fallback 登记新问题。

全文阅读 RcloneBrowser 的 list/mkdir/testConnection 与时间解析。小数秒解析使用 SSSSSSSSS 的疑点保留待核：官方 [Android SimpleDateFormat](https://developer.android.com/reference/java/text/SimpleDateFormat)与 [Java SE 文档](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/text/SimpleDateFormat.html)将 S 标为毫秒、数字宽度不定义纳秒缩放；但本轮未读到设备对应 libcore 的实际实现，不能仅凭宿主 Java 行为声称 Android 一定产生具体日期偏移。没有执行解析实验，不增加确定问题数。

TLS 全局选项与 per-remote allowInsecureTLS 的作用域、取消 pending 后的全局状态仍需沿剩余 RemoteStore/实际 backend 核验，留到后续全文阅读。不能把某个服务器主动允许自签名本身直接认定为 Bug，也不依据注释声称其它目的地已保持证书验证。

RcloneTlsTimeoutTest 只验证超时参数、模型往返及 config 参数，worst-case wait 测试是常量乘法，未实际验证 Go 调用耗时。RestoreSourceEntriesTest 明确是源码结构测试，未驱动创建目录流程。两份测试源码完整读完但未执行，不声称设备测试通过。

## 覆盖与核验

本轮全文 5 个文件、1024 行：

- [src/android/app/src/main/java/com/openminis/app/backup/remote/RcloneBridge.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/remote/RcloneBridge.kt)：218 行。
- [src/android/app/src/main/java/com/openminis/app/backup/remote/RcloneBrowser.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/remote/RcloneBrowser.kt)：118 行。
- [src/android/app/src/main/java/com/openminis/app/ui/settings/backup/RcloneDestinationsViewModel.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/backup/RcloneDestinationsViewModel.kt)：286 行。
- [src/android/app/src/test/java/com/openminis/app/backup/RcloneTlsTimeoutTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/backup/RcloneTlsTimeoutTest.kt)：207 行。
- [src/android/app/src/test/java/com/openminis/app/backup/RestoreSourceEntriesTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/backup/RestoreSourceEntriesTest.kt)：195 行。

累计全文 976 / 1546 个文件、198042 行；partial 16，not_registered 554，excluded 124。全仓通读尚未完成。RemoteStore 和 Screen 仅记录已列片段，不计全文；ViewModel 经分段阅读补齐全部286行后才登记全文。

1670 tracked 路径、SHA256、字节数与正整数文本行数全量核验；68 份旧报告摘要不变，HEAD 固定，tracked/staged diff 为空。新报告引用行号与台账批次总数核验。全文输出完整无截断；上游导航段使用该固定版本真实行号，而不是套用本地行号。没有真实远程或界面验证证据。
