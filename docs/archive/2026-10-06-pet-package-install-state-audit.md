# 桌面宠物包安装与选择状态静态审查

日期：2026-10-06。固定本仓 HEAD `d64e7c06d8159be90737dd9ba03dd9bd5855f339`。本轮新增 **1 项 P2 确认缺陷**，全文阅读 5 个文件、547 行。只记录问题，未改产品源码，未运行测试、构建、模拟器、设备、Root 或云任务。旧报告保持不变。

## PI01 / P2：替换安装中断后无恢复，临时目录还会冒充已安装宠物

入口 [PetControlActivity.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/pet/PetControlActivity.kt:32) 通过用户选取 ZIP 在 IO dispatcher 调用 importZip。[PetPackageManager.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/pet/PetPackageManager.kt:75) 复制并验证新包后，[PetPackageManager.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/pet/PetPackageManager.kt:82) 先将原正式目录改名为隐藏 backup，随后第85行将 staging 改名为正式目录。两个 rename 之间没有持久事务记录或启动恢复；普通异常 catch 中的回滚不能覆盖进程被杀或设备重启。

静态反例：已有选中的 id=cat，导入同 id 新包；第82行成功后、85行之前进程终止。磁盘保留 `.cat.backup-UUID` 和 `.cat.staging-UUID`，正式 `pets/cat` 不存在。偏好仍为 cat；[PetPackageManager.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/pet/PetPackageManager.kt:24) selected 只查正式路径，因此返回 null。[PetControlActivity.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/pet/PetControlActivity.kt:80) 开启入口会提示先选择宠物；服务 [PetOverlayService.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/pet/PetOverlayService.kt:556) 同样依赖 selected，没有从 backup 恢复。

与此同时，[PetPackageManager.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/pet/PetPackageManager.kt:18) listInstalled 不排除隐藏 backup/staging，而是对所有子目录执行 loadDirectory；两个目录都通过过合法包校验，可能同时出现在设置列表。[PetControlActivity.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/pet/PetControlActivity.kt:93) 点击列表项只保存 manifest.id，仍然查不存在的正式 `pets/cat`，不会选中该列表项实际所在目录。用户看到可选条目，却无法靠点击恢复。这不是仅仅遗留磁盘垃圾，也不意味着 ZIP 资源已经物理丢失：旧包仍可能在 backup 内。

相关失败清理也不完整：第75—77行 copyTree/验证在内层 try 之前，失败只进入外层 finally 删除 cache staging，filesDir/pets 内 installStaging 不在此清理范围；第86行 backup.deleteRecursively 和第89行回滚 rename 的返回值未检查。它们可能进一步遗留隐藏目录或隐藏回滚失败，归入同一安装状态管理缺口，不重复计数。

修复方向：定义可在下次启动/枚举前恢复的安装状态；保证正式目录恢复或完成替换，检查回滚与清理结果，并只将正式安装目录列为可选项。本轮没有实现修复、导入真实包或模拟进程终止。

## 其他代码核验与未计数候选

PetModels 的 id 限定为 1—64 字符字母数字/下划线/连字符，不能直接构造目录穿越；sprite 禁止绝对路径与 .. 段，safeChild 再按 canonical Path 边界检查。文件解压限制为64个文件、单文件24MiB、累计32MiB；atlas 网格/帧/时长有范围限制，图片只解码尺寸校验，并限制1600万像素。没有将这些措施误写为整套导入流程已具备崩溃一致性。

目录 ZIP entry 在第39行直接跳过，没有纳入文件数量和显式字节预算；底层 ZipInputStream 在前进时如何处理目录附带负载、是否需要额外输入/时长限制，尚未完成 Android 平台实现对照，本轮不以此新增确认缺陷。

PetPreferences 的尺度/速度范围是0.5—2；异常 NaN、恢复偏好类型等边界仍需追踪实际来源，不把孤立非法 Float 当作现役滑杆必然产生的输入。普通偏好类型恢复问题已有备份审查记录，不重复统计。

PetBridge 的 enabled 检查、同状态500ms节流及前台服务启动异常捕获已阅读。墙上时钟回拨、失败启动后仍更新节流时间是候选；未凭该代码推断设备上永久无法启动或 Agent 主循环崩溃。PetControlActivity 的导入在 IO dispatcher；refresh 中的目录/图片尺寸枚举在界面生命周期执行，但没有性能测量，不虚构卡顿或 ANR。

## OpenMinis 对照和范围

只读 `/tmp/openminis-review-20261004` 固定 HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c` Git 树，未 fetch。上游没有本轮 pet 目录同路径代码，因此不声称 PI01 继承自上游，也没有以旧 minisd/PRoot 路径解释这套 App 私有目录安装流程。源码中的 Codex-compatible 仅是包格式声明，不能推出 Android 实现已经满足其他产品的安装恢复合同。

PetOverlayService 只复核 selected 调用片段，尚未全文登记；聊天引擎、悬浮窗动画与生命周期仍待继续通读。本轮没有调用宠物插件或控制桌面宠物。

## 全文覆盖与复核

| 本轮全文文件 | 行数 |
|---|---:|
| [PetBridge.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/pet/PetBridge.kt) | 64 |
| [PetControlActivity.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/pet/PetControlActivity.kt) | 164 |
| [PetModels.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/pet/PetModels.kt) | 114 |
| [PetPackageManager.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/pet/PetPackageManager.kt) | 143 |
| [PetPreferences.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/pet/PetPreferences.kt) | 62 |

累计全文 **1174 / 1546 个文件、226118 行**；partial 13，not_registered 359，excluded 124。全仓通读尚未完成。首次合并输出截断的四份文件均已逐文件重新全文读取；本报告不把片段检索登记为全文。

完成时核对全部1670 tracked路径 SHA-256、字节数和正数文本行数，本仓及上游固定 HEAD、tracked/staged 空 diff、此前99份报告哈希、本报告文件链接及行号、台账批次总计。只新建本报告并更新覆盖台账。
