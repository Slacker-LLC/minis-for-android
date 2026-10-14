# 退役 Shell 兼容设施与构建残留静态审查

日期：2026-10-06。本轮记录 1 项新的 P3 清理遗漏。只新建报告、更新覆盖台账；没有修改产品代码和旧报告。

本地 HEAD：`d64e7c06d8159be90737dd9ba03dd9bd5855f339`；OpenMinis 固定对照：`b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`。只读既有上游对象，没有 fetch 或执行上游。没有运行测试、构建、模拟器、adb、Root、设备或云任务，没有读取凭据或启动子 Agent。

## BR01 · P3 · 已退役的 BusyBox/Alpine 检测设施仍被构建打包

[BashismDetector](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/agent/shell/BashismDetector.kt:20)及 [BashismReminder](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/agent/shell/BashismReminder.kt:9)服务于 BusyBox ash 不支持 Bash 语法时的安装、切换及失败提示。[共享规则](/home/jiale/projects/minis-for-android/src/shared/bashism/bashism_rules.json:3)明确以 Alpine 3.21 / BusyBox 1.37 为验证环境；提醒正文仍声称命令由 busybox sh 执行。

对本地 src/android/app/src/main 全树搜索这两个符号，除定义与它们相互引用外，没有调用方。ensureLoaded/detect/build 的生产外部调用已经消失。测试仍直接调用 helper，所以本项不是“文件完全不可编译”或“测试没有内容”。现役 [UbuntuKernel](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/ubuntu/UbuntuKernel.kt:625)直接选择 /bin/bash，架构合同也明确 Alpine/PRoot 不再属于生产路径。

但 [copyBashismRules 构建任务](/home/jiale/projects/minis-for-android/src/android/app/build.gradle.kts:351)仍将 bashism_rules.json 和 bashism_test_vectors.json 复制到 src/main/assets/bashism；[mergeAssets](/home/jiale/projects/minis-for-android/src/android/app/build.gradle.kts:432)和 [preBuild](/home/jiale/projects/minis-for-android/src/android/app/build.gradle.kts:459)仍依赖它。测试向量因此也进入正常资产合并输入，而生产没有消费者。即便 Release R8 能移除无调用的 Kotlin 类，这些资产复制步骤也不会因此自动取消。

这不是只有注释过时：退役能力仍占用源码、构建任务、APK 资产输入和维护测试。没有运行构建，本轮不主张已经测得 APK 体积变化，也不主张现在会自动切回 Alpine 或恢复 PRoot。

固定上游 [ChatViewModel.kt:14238](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt#L14238)仍在 shell 执行前加载、检测并调用 OnDemandBash；14261 行构造失败提醒。那时这些模块确有调用方。本地迁移到 Direct Ubuntu 后移除了调用，构建及 Android helper 未同步收尾。上游事实用既有 Git show/grep 核对。

修复方向记录：在 Android 范围内统一清理无消费者的 helper、相应测试和资产复制依赖，并核对打包输入。共享目录的 Swift 验证器可能仍服务 iOS；不能仅凭 Android 无调用就删掉所有平台共享资源，更不应重新接入 BusyBox/Alpine 双栈。本轮仅记录，未执行删除。

## 补读结果与边界

完整阅读检测器的规则加载、heredoc 跳过、逐行正则、时间熔断及提醒清洗；完整阅读共享规则、向量和 Swift 验证器。检测器内潜在解析边界不作为现役运行故障计数，原因是当前没有生产调用。测试源码存在不代表本轮执行过测试。

[TitlePromptLocale.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/TitlePromptLocale.kt:29)也已完整补读。自动标题路径 [ChatViewModel.kt:11236](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:11236)和手动路径 [SessionListViewModel.kt:814](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/sessions/SessionListViewModel.kt:814)均调用语言 helper；没有足够证据把语言映射列成新问题。既有标题生成竞态不重复计算。

本轮不是重复注释噪音、复制 helper 或 Lint 基线清理，也没有修改 minisd/proot 或原生 Android 工具。它记录的是迁移完成后仍活跃于构建输入的具体旧设施。

## 全文覆盖及核验

新增全文 7 个文件、682 行：

- [src/android/app/src/main/java/com/openminis/app/agent/shell/BashismDetector.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/agent/shell/BashismDetector.kt)：171 行。
- [src/android/app/src/main/java/com/openminis/app/agent/shell/BashismReminder.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/agent/shell/BashismReminder.kt)：55 行。
- [src/android/app/src/main/java/com/openminis/app/ui/chat/TitlePromptLocale.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/TitlePromptLocale.kt)：81 行。
- [src/android/app/src/test/java/com/openminis/app/agent/shell/BashismDetectorTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/agent/shell/BashismDetectorTest.kt)：77 行。
- [src/shared/bashism/bashism_rules.json](/home/jiale/projects/minis-for-android/src/shared/bashism/bashism_rules.json)：160 行。
- [src/shared/bashism/bashism_test_vectors.json](/home/jiale/projects/minis-for-android/src/shared/bashism/bashism_test_vectors.json)：68 行。
- [src/shared/bashism/validate_detector.swift](/home/jiale/projects/minis-for-android/src/shared/bashism/validate_detector.swift)：70 行。

累计全文 926 / 1546 个文件、187902 行；partial 12，not_registered 608，排除项 124。全仓通读尚未完成。

交叉复核已全文记录的 build.gradle.kts 328—375、428—435、450—464，UbuntuKernel 614—632，SessionListViewModel 790—825；ChatViewModel 新片段 11212—11250 在台账记为 partial 范围，不冒充全文。该片段合并输出曾截断，已单独重读。固定上游 shell 调用链读取 14225—14270 行；标题 helper 仅作对照，不用其截断输出声称新增完整覆盖。

重新核对所有 1670 个跟踪路径及 SHA256、字节数、正整数文本行数；59 份旧报告摘要未变；HEAD 固定，tracked/staged 产品 diff 为空。报告引用文件与行号核验，台账批次计数与总数核验。本轮没有任何运行时复现证据。
