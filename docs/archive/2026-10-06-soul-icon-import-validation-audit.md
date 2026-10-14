# Soul 图标导入、URL 与 Emoji 校验静态审查

日期：2026-10-06。本轮确认 3 项新问题：2 项 P2、1 项 P3。新建独立报告，更新阅读台账；不修改产品代码和旧报告。

本地 HEAD：`d64e7c06d8159be90737dd9ba03dd9bd5855f339`；OpenMinis 固定对照：`b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`。只读既有上游 Git 对象，没有拉取或执行上游。没有构建、执行测试、模拟器、adb、Root、设备、云任务、账号或凭据操作，也没有子 Agent。触发顺序均为静态推演，未声称设备复现。

## SI01 · P2 · 图标先完整解码再缩图，96 像素输出没有限制输入内存

[图片选择回调](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/SoulSettingsScreen.kt:142)直接 BitmapFactory.decodeStream，不提供 bounds-only 检查或采样 options；随后才调用 [encode](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/agent/SoulIcon.kt:68)裁方形、缩到 96 像素并压 PNG。[64 KiB 检查](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/agent/SoulIcon.kt:96)发生在 PNG 编码和 base64 生成后，只控制最终存储字符串。

配置图片入口 [resolveSoulIconImage](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/config/ConfigBuiltins.kt:790)同样直接 decodeByteArray，然后 encode。文件读取已有 [50 MiB 上限](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/files/WorkspaceFileClient.kt:23)，这不是完全无限制的文件读取；但压缩字节上限不约束解码后的像素数量。10000×10000 的四字节像素缓冲本身约 381 MiB，还未包含原图字节、裁剪与缩放中间对象。是否实际造成 OOM 取决于图片格式、解码配置和设备堆预算，本轮没有运行测量。

用户选择或通过配置导入高分辨率、压缩体积不大的图片时，会在检查最终图标大小前发生大额分配。IO 线程可以减少主线程阻塞，不能消除内存分配；runCatching 可将部分失败变成“不可读”，也不能把输入限制提前。现有两份 SoulIcon 测试主要验证字符串、分类和序列化，没有验证真实 Bitmap 解码预算，本轮未执行测试。

固定上游 [SoulSettingsScreen.kt:140](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/ui/settings/SoulSettingsScreen.kt#L140)也直接 decodeStream，本项至少在图片选择路径是共享缺口，不归因于 Direct Ubuntu 迁移。

修复方向记录：复用平台 bounds-only 解码与采样，在完整像素分配前确定输出需求和输入预算；最终字符串上限继续保留。这里只记录，不修改图片处理或运行资源测试。

## SI02 · P2 · 编码 minis:// 图片地址没有解码为文件名

[地址分类](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/agent/SoulIcon.kt:148)仅删除 minis:// 前缀、拼接 /var/minis/，没有解析或还原百分号编码。[配置 writer](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/config/ConfigBuiltins.kt:782)把该 path 原样传给 WorkspaceFileClient；[requirePath](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/files/WorkspaceFileClient.kt:246)再交给 UbuntuPaths。

[会话路径解析](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/ubuntu/UbuntuPaths.kt:208)提取 rest 后只做 secureComponents；[组件分割](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/ubuntu/UbuntuPaths.kt:309)不会 URL 解码。因此在正确的前台会话中已有 /var/minis/attachments/my icon.png 时，soul.icon 传入 minis://attachments/my%20icon.png 会尝试读取字面文件名 my%20icon.png，通常报找不到文件。中文文件名同样受影响。若恰好存在带字面百分号的另一个文件，还可能选中与 URL 表示不同的文件。

[默认 URL 提示词](/home/jiale/projects/minis-for-android/src/android/app/src/main/assets/prompts/paths.minisUrl.md:8)要求 minis:// 地址百分号编码，因此这个失败形式是产品正常推荐的输入。原始 Linux 路径带真实空格可作为替代方式；不把它误报为所有图片路径都失败。本项也不重复旧 CB02 的前台 session 绑定问题，反例前提就是目标会话正确。

固定上游 [SoulIcon.kt:170](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/agent/SoulIcon.kt#L170)使用相同前缀拼接。只确认共享地址分类缺口，不据此声称已完整审阅上游所有文件读取行为。

修复方向记录：复用项目现有 minis 资源 URL 解析方式，明确一次解码的边界，再交给现有安全路径校验；不得通过关闭 traversal 校验来兼容编码。

## SI03 · P3 · 普通字母附加变体选择符会被接受为 Emoji

[isEmojiGlyph](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/agent/SoulIcon.kt:271)在多码点分支，只要任一码点是 FE0F、20E3 或 200D 就返回 true，没有核对基字符。[graphemeClusters](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/agent/SoulIcon.kt:362)会把 FE0F 吸收到前一个基字符的 cluster。

静态反例为普通字母 A 后接 U+FE0F，即源码字符串 A\uFE0F。它被分成一个 cluster，isEmojiGlyph 返回 true；[配置分支](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/config/ConfigBuiltins.kt:869)因此把它原样存成 icon，而不是按无效输入拒绝。UI 的 [每键规范化](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/SoulSettingsScreen.kt:649)也保留它。字母加变体选择符不能仅凭标记出现就成为所承诺的 Emoji 图标。

现有测试拒绝普通字母、数字和 CJK，也验证有效 keycap/ZWJ/flag，却没有覆盖非 Emoji 基字符附加 FE0F 的情况。固定上游 [SoulIcon.kt:293](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/agent/SoulIcon.kt#L293)有同类“含标记即 true”分支。本项是共享输入校验缺口，不是文件权限绕过，也不声称会损坏整个 SOUL.md。

修复方向记录：标记需要和允许的基字符/序列配对；继续保留已支持的合法 flag、keycap、肤色和 ZWJ 图标，不以拒绝全部组合 Emoji 简化需求。

## 阅读及核验边界

SOUL 既有 DQ05 元数据转义、DQ09 初始化竞态，以及旧 UI 页面的返回保护问题未重复计数。SoulSettingsScreen 已有 dirty 返回确认，本轮没有把它误报为缺失。远程图片 URL 明确被拒绝，也没有进行网络导入。

本轮新增完整阅读 4 个文件、1562 行：

- [src/android/app/src/main/java/com/openminis/app/agent/SoulIcon.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/agent/SoulIcon.kt)：408 行。
- [src/android/app/src/main/java/com/openminis/app/ui/settings/SoulSettingsScreen.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/SoulSettingsScreen.kt)：722 行。
- [src/android/app/src/test/java/com/openminis/app/agent/SoulIconConfigEdgeTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/agent/SoulIconConfigEdgeTest.kt)：206 行。
- [src/android/app/src/test/java/com/openminis/app/agent/SoulIconTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/agent/SoulIconTest.kt)：226 行。

累计全文 930 / 1546 个文件、189464 行；partial 12，not_registered 604，排除项 124。全仓通读尚未完成。

交叉复核既有全文文件 ConfigBuiltins 742—915、WorkspaceFileClient 1—110/215—255、UbuntuPaths 90—175/194—237/303—350、ChatSplitScaffold 765—792。SoulSettingsScreen 241—480 的合并输出曾截断，已单独完整补读后才登记 full。固定上游使用既有 Git show 核对三个相关片段，未拉取和执行。

所有 1670 个跟踪路径、SHA256、字节数和正整数文本行数重新核对；60 份旧报告摘要不变；HEAD 固定，tracked/staged 产品 diff 为空。新报告引用文件与行号、阅读批次与总计数均核验。以上不代表测试通过或设备复现。
