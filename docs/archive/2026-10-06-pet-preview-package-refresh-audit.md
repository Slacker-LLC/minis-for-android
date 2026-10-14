# 宠物设置预览与悬浮层视图静态审查

日期：2026-10-06。固定 HEAD `d64e7c06d8159be90737dd9ba03dd9bd5855f339`。本轮新增 **1 项 P3 确认缺陷**，全文读取2文件、818行。只排查记录，未修改产品源码，未运行测试、构建、模拟器、设备、Root或云任务。旧报告不改。

## PP01 / P3：同 ID 包替换成功后，设置页仍显示旧预览

现役 [PetPackageManager.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/pet/PetPackageManager.kt:72) 以 manifest.id 作为正式安装目录，允许导入同 ID 包替换旧包。成功后 [PetControlActivity.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/pet/PetControlActivity.kt:47) 调用 refresh 和 reloadOverlay，refresh 重读 listInstalled/selected，仍保留当前设置页。

[PetControlScreen.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/pet/PetControlScreen.kt:282) 的 loadedPetId 由 remember 保存在当前组合中；第309行只有 loadedPetId != pet.manifest.id 才调用 view.loadPet。相同 ID 的 sprite、图集尺寸、动画参数更新没有失效条件。PetSpriteView 持有解码 bitmap，磁盘路径被替换不会自动刷新这个 bitmap。

静态反例：页面已预览 id=cat 的蓝色宠物；导入 id=cat、displayName改为红猫、sprite变为红色的合法包，成功替换。refresh 使新名称及 manifest 进入界面，但 ID 仍为 cat，第309行跳过解码。第321行显示红猫的新名称，图像却仍为旧的蓝猫；新动画参数也未进入预览 view。离开并重新创建预览，或选择另一 ID 再切回，可能恢复。没有把这个预览缺陷描述成正式包没有安装成功或磁盘数据丢失，也没有证明悬浮服务必定显示旧图：服务 reload 是另一条新建 view 的路径。

若包只换图片字节、manifest完全不变，refresh 的结构相等状态还可能无法使 UI 重新组合；以上反例刻意改变名称保证即使组合更新，ID门槛依然阻止预览加载，不依赖推测的重组时机。

同一缓存状态还有失败路径：第310行在 runCatching 解码之前设置 loadedPetId，解码失败后异常被吞掉，同ID后续更新仍不会重试。这里只作为同一预览加载状态缺口的证据，不另计问题；没有凭 decode失败声称进程崩溃。

方向：用包内容版本/安装修订或明确刷新事件使预览失效，只在加载成功后确认缓存状态，并呈现加载失败。保持不在每次普通重组重复解码。未实现修改或重新导入任何真实包。

## 悬浮层现役用途与残留输入路径

PetOverlayView 包含 sprite、bubble、menu 和旧 inputBar。inputBar 初始化为 GONE。main pet 目录检索 setInputVisible/focusInput 只有定义，没有服务调用；当前服务 setInputMode 构造的是独立 PetChatWindowView。因此旧 inputBar 的 IME、mic、busy、focus-loss处理不能作为当前聊天窗口运行事实。它们确实仍被初始化，但本轮没有仅凭重复组件数量新增功能故障，也未删除代码。

menu 三个入口先隐藏菜单再调用服务动作；showBubble 在空白输入时 hideBubble，替换气泡时取消旧 timeout 和 fade动画，避免旧 fade结束隐藏新内容。hideBubble 会安排140ms淡出，cancelPending 移除 timeout 并取消动画；服务 hidePet 实际调用 cancelPending。没有把普通定时消失算作内容丢失。

菜单关闭后服务 FLAG_WATCH_OUTSIDE_TOUCH 的刷新是否总同步，仍需结合全部生命周期；本轮不以某个未调用 refresh 的动作推断长期全局截获手势。外部点击回调只在菜单可见时隐藏菜单，不能据此说它读取或修改其他 App 内容。

设置页启用 switch 在 selected为空时禁用，可能与已经enabled但文件失效的恢复状态相遇；PI01 已记录安装中断与不可选临时条目，本轮不重复统计这个相关现象。slider为0.5—2并在拖动结束触发服务reload；预览卡本身固定120×130dp且没有接收scale/speed，未把它擅自定义为应该逐项呈现设置后的比例和速度。

## 固定 OpenMinis 对照与证据限制

只读本地 `/tmp/openminis-review-20261004` HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c` Git树，未fetch。上游没有本轮pet目录同路径实现，无法提供直接同路径对照。源码中旧“同窗口输入条”注释与服务当前独立聊天窗口不同；本轮按调用代码描述，不拿移植说明替代实现事实。

没有运行Compose、图片解码、模拟器或UI验收；PP01 是由成功替换入口与固定ID加载条件支持的静态反例，真实设备截图尚未采集。未使用任何宠物插件或用户资源。

## 全文覆盖与复核

| 本轮全文文件 | 行数 |
|---|---:|
| [PetControlScreen.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/pet/PetControlScreen.kt) | 467 |
| [PetOverlayView.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/pet/PetOverlayView.kt) | 351 |

累计全文 **1182 / 1546 个文件、227992 行**；partial13，not_registered351，excluded124。全仓通读未完成。全文读取两份文件的独立返回均完整；一次过宽报告检索输出截断已缩小条件重新检索，未把截断检索算作阅读覆盖。

完成时核对全部1670 tracked路径 SHA-256、字节数和正数文本行数，本仓/上游固定HEAD、tracked/staged空diff、此前102份报告哈希、新报告文件链接及行号、台账批次总计。只新建本报告并更新覆盖台账。
