# QQ/微信图片缓存扫描与下载记录工具审查

日期：2026-10-06。固定 HEAD `d64e7c06d8159be90737dd9ba03dd9bd5855f339`。本轮新增 **1 项 P2、1 项 P3**；全文阅读 4 个文件、409 行。未改产品源码，未运行测试/构建/模拟器/Root/设备/云任务，未读取 QQ/微信缓存、下载记录或真实图片。独立报告，旧报告不改。

## CI01 / P2：扫描输出先截断、后排序，不能保证返回最新图片

[ChatImagePolicy.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/ChatImagePolicy.kt:41) 只调用 find 输出时间/大小/路径，不在系统端排序。[ChatImageTools.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/ChatImageTools.kt:111) 收到全部 stdout 后才在 Kotlin sortedByDescending，再按关键词和 limit 截取。它依赖 stdout 是完整扫描结果。

实际生产 runner 是 PrivilegedCommandRunner → DirectRootRunner.runArgv。[DirectRootRunner.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/ubuntu/DirectRootRunner.kt:77) 的 stdout 保留上限是 1,048,576 字符；[DirectRootRunner.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/ubuntu/DirectRootRunner.kt:265) 的 BoundedText 满后忽略后续行，已保留前缀末尾还可能是半行。没有把输出截断状态传到 AndroidCommandResult。

静态反例：足够多匹配文件令输出超过该上限；扫描前缀中都是较旧图片，最新图片位于之后被忽略的目录项。find 在 15 秒内正常完成且 exit=0，ChatImageTools 仍 success=true，只对旧前缀排序并宣称 newest。关键词仅匹配被丢弃的新路径时，items 甚至为零。扫描顺序没有按 mtime 排列，不能把前缀当 top-K。

影响是排序/检索遗漏，不能说缓存文件被删除。1 MiB 保留限制是必要边界，问题是排序发生在截断之后而未反馈不完整；不建议取消输出限制。之前 Direct Runtime 报告的长行分配缺口是底层内存问题，本项是该边界导致的业务结果错误，不重复计数底层问题。未测量真实缓存大小、排序或设备耗时。

修复方向：在有界收集时保留真正的 top-K，或采用受控可复用的分页/扫描基础设施；输出不完整必须显式反馈，不能把局部排序包装为全局最新。未新增 shell pipeline 或通用 Root RPC。

## CI02 / P3：truncated 由无关候选数和等于 limit 推测，不能表示真实遗漏

[ChatImageTools.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/ChatImageTools.kt:125) 使用 candidates.size>=120 或 items.size==limit；MAX_CANDIDATES 只在该判断出现，没有限制实际扫描/解析候选数量。

反例一：恰好 5 项匹配，limit=5，没有额外匹配项，仍 truncated=true。反例二：完整扫描 120 项，关键词只匹配其中 1 项，limit=30，已返回全部匹配项，仍因无关候选总数达到 120 标为 truncated。它无法回答是否还有未返回的匹配结果。

这条结果元数据也没有直接连接 CI01 的 stdout 截断信息。不能据 candidates 数量保证检测到了真实输出截断。修复方向：在匹配集合上用 limit+1 判断还有结果，并单列扫描不完整/输出截断；不要把“最多候选”常量注释当实际执行上限。未修改代码。

## 其它核对与未计数候选

两个图片 handler 在 MinisApp 中实际注册，QQ 过滤 chatraw/chatimg/chatthumb，微信过滤 image。调用 argv 而非 shell 字符串，目录和过滤模式固定；路径解析要求扫描目录加斜线前缀。不能把它说成任意 raw root command 注入入口。读取图片仍需另一个文件访问工具，缓存路径不是已复制到会话 attachments 的 guest 路径。

修改时间在 policy.row 中转为 Long 秒，亚秒排序丢失；同秒图片的先后只保持扫描原顺序，是精度候选，不另计与 CI01 重叠的“最新”问题。row 不再次校验 size 的负数/上限，也未校验换行分隔文件名，实际输出来源为受控 find；没有证明这些候选扩大文件读取权限，暂不计。

DownloadsTools 使用 downloads/my_downloads，并在描述中限定本 App 创建的记录；通过已有 PersonalDataQueryTools 查询与预算，不宣称它能搜索其它 App 的全部下载。这些限制与描述一致，本轮没有新增下载工具缺陷。

[AOSP Toybox 固定源码 8324c714 的 find](https://android.googlesource.com/platform/external/toybox/%2B/8324c7148a4f34b790c5a9fa42663569ba1e538f/toys/posix/find.c)包含 printf 与 T@，所以不能把“Android find 必然不支持 printf”列为问题。最初 android16-release 路径访问失败后，搜索核对到该有效固定源码；它不证明每个 OEM 版本都相同，没有运行工具探测。

已有 ChatImagePolicyTest 全文 90 行，只检查 argv tokens、行解析、目录前缀拒绝和分类，没有覆盖结果排序前截断、关键词后的剩余数或真实系统 find。未运行或新增测试。

## 固定 OpenMinis 对照

只读 `/tmp/openminis-review-20261004` HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c` 的完整 tools 树，未见 ChatImage/Downloads/PersonalData 同名实现，未 fetch。不据此断言上游没有任何图片或下载功能，也不归为共享缺陷。Eta pipeline 来源仅是代码注释，本轮没有独立核实其实际边界或排序正确性。

## 全文覆盖与复核

| 本轮全文文件 | 行数 |
|---|---:|
| [ChatImagePolicy.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/ChatImagePolicy.kt) | 77 |
| [ChatImageTools.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/ChatImageTools.kt) | 177 |
| [DownloadsTools.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/DownloadsTools.kt) | 65 |
| [ChatImagePolicyTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/tools/ChatImagePolicyTest.kt) | 90 |

累计全文 **1097 / 1546 个文件、218011 行**；partial 13，not_registered 436，excluded 124。全仓通读尚未完成。四个文件均干净全文读取。DirectRootRunner/PrivilegedCommandRunner/MinisApp 已 full，本轮片段核对不重复统计；ReadImageTool 只片段查询，不新增覆盖。较宽引用检索输出有截断，仅用来定位，关键 runner/排序片段已独立重读。

完成时核对全部 1670 tracked 路径、SHA-256、字节数与正数文本行数，固定本仓/上游 HEAD、tracked/staged 空 diff、此前 86 份报告哈希、本报告链接/行号和批次全文总计。只新建本报告并更新覆盖台账。
