# 模型目录维护、原生验收与退役运行时文档检查静态审查

日期：2026-10-06。本仓固定HEAD `d64e7c06d8159be90737dd9ba03dd9bd5855f339`。新增全文13文件、1604行，确认1项P3文档检查漏检。只读源码与已有测试，不改产品代码、不执行任何被审脚本或测试、不构建/启动模拟器/adb/Root/设备/云/模型，不下载目录或读取凭据。独立报告，旧报告不改。

## DPG01 / P3：无关否定词可以放行明确的现役minisd/PRoot描述

[check_docs_provenance.py](/home/jiale/projects/minis-for-android/scripts/check_docs_provenance.py:185) is_negative_mention仅检查整行是否包含任意NEGATIVE_CONTEXT_KEYWORDS；其中no空格、without、旧、历史、非等都可匹配。第200—209行对于退役runtime和旧Root-owned用户数据标签，只要整行这个函数为true就continue，不验证否定词是否修饰被禁止的声明。

静态反例：一个active Markdown文件包含英文行“minisd is the active production broker; no retry is needed.”。minisd命中BANNED_PATTERNS，但no空格来自“无需重试”，没有否定minisd现役身份；检查器却把它作为negative mention跳过。中文“minisd 是现役 broker，不需要重试”未必匹配其列出的中文否定词，所以此报告以精确英文形状为可复核反例，不把所有“不”字自动视为匹配。

同样，“PRoot is the active backend; without retries.”会因without放行。它们是同一个作用域过宽的例外，不分别计数。这里只证明特定行不能被该检查识别，不声称向真实文档写入了这些句子、也不声称整仓check_tree其它要求都通过。

真实CI入口 [ci.yml](/home/jiale/projects/minis-for-android/.github/workflows/ci.yml:105) 的docs-provenance job先运行测试，再执行该checker。test_docs_provenance验证无否定词的active minisd拒绝及真实退役句子允许，没有包含“现役声明+无关否定”组合，所以现有测试不能证明语义边界。

本项是文档保障能力的漏检，不能拿来证明生产链重新出现minisd/PRoot。方向：把否定关联到具体runtime声明/句法范围，或收紧到明确退役表述；保留真正的迁移/历史描述能力，不直接禁止所有退役名称。未改实现或添加测试。

## 目录更新与思考规则测试

update_models_dev.sh全文17行：curl带fail/location，临时文件通过json.tool语法校验后mv替换，EXIT trap清理；下载或JSON语法失败不会先覆盖目标。校验没有模型目录schema检查、缺下载上限/重试，当前未证明真实服务返回合法但错误形状，不把潜在上游变化计为已确认目录损坏。未执行curl、json.tool或mv。

ThinkingLevelCatalogSnapshotTest全文68行：确实从bundledDocument读取staticModels，加指定IDs，验证模型集合和catalog ceiling；对未知模型HIGH fallback有明确断言。它固定catalog快照，不证明线上vendor当前接受所有等级，注释日期/模型名不作为当前服务验证。ModelRulesTestFixtures实际读model-rules资产，已做相关源复核，不假称测试已运行。

XAIEmptyEffortTiersTest全文146行：调用真实ThinkingRuleResolver，有非xAI、silent catalog、真实tier、矛盾标记、unified gateway与nonreasoning否定/控制例。真实resolver第422行只在isXAI&&!unified&&declaresNoEffortTiers&&!declaresEffort时省略，范围与测试设计对应。注释中2090条目录数字不是本轮统计，未验证线上模型、不扩大为所有relay都应省略参数。

## APK/Bundle与发布脚本边界

verify-android-16k.sh全文110行：先zipalign -P16，再用ELF32/64、大小端解析PT_LOAD align，每个存在ABI要求4份基本JNI库，拒绝无库、坏header、无LOAD或不满足0x4000对齐。test-android-16k.py全文51行抽取真实heredoc代码，构造两个ABI与4K/missing-binding负例；不执行shell zipalign，因此不能称其通过等于完整APK16K验收。

ABI集合从APK已有库推导，缺失整个x86_64目录不会被“每个已有ABI缺binding”检查发现；Gradle abiFilters声明arm64-v8a/x86_64。具体发布是否允许单ABI产物需产物契约，保留coverage缺口而不直接报发布失败。ELF gate只测LOAD align，不是完整ELF loader正确性证明，未用人为损坏ELF制造真实产品故障。

verify-android-bundle.sh全文28行：validate后build-apks universal，签名参数通过0700临时目录/umask077内的password文件传入，trap清理，再抽universal.apk走16K gate。没有执行java/bundletool、读取签名变量或生成APK。未提供release签名时bundletool可用开发签名，此脚本重点验证生成APK结构，正式签名校验另由verify-android-release处理，不能把它误作正式发布验签替代。

release-check.sh全文67行：校验tag格式/appVersionName、main或历史release分支、clean tree、远端tip/重复tag及CHANGELOG，最后仅打印tag/push/release命令，没有实际发布。内部git fetch会更新本地远端refs，所以头注释read-only限定“不tag/push”的含义，不能把运行过当不变状态；本轮未执行。网络失败在set-e下失败关闭，不假设无网时仍会许可发布。

build-android-debug.ps1全文71行：调用wrapper后检查exit/APK存在，再Git Bash native gate；WSL fallback将整个路径ToLower在大小写敏感挂载下可能失效，但本仓gradlew.bat存在时优先前支，未证明现役正常入口会走该fallback，保留候选。PowerShell/java/Git Bash均未运行。

## Debug资源生成与配置检查

gen_debug_skill_android.sh全文152行只向src/debug/assets写skill与stdlib client。Gradle第470行stageDebugSkillAssets显式定位生成器、缺可选源时可构建。client必须token，默认localhost:5321且120秒HTTP timeout；允许用户显式指定host是开发客户端能力，不能用代码可设远端host直接认定server公开监听。client同名函数对参数dict只有类型注解，真实DebugServer参数验证需server链，已由此前debug报告核对，未读取真实token或执行生成器。

test-provider-customization.sh全文91行会备份现有properties、覆盖fixture、运行Gradle检查public/required/blank/configured形状并用trap恢复。configured输出只打印capability状态，不打印私有配置值。本轮只看脚本，不触碰provider-customization.properties，不验证实际环境密钥。并发编辑时恢复旧备份可能覆盖别人编辑，是此测试的运行隔离约束，未造新product defect。

audit_strings.py全文312行只登记覆盖。没有运行它、重新统计缺翻译/注释/lint问题或进入用户已明确让别的Agent处理的清理范围。其输出是启发式候选而非资源使用的完整证明：按字符串引用正则统计、Kotlin逐行literal扫描，不能当Android lint替代。文档guard要求的历史allowlist同样只限定文档，不证明源代码无旧runtime。

## 固定OpenMinis对照

只读固定 `/tmp/openminis-review-20261004` Git对象HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，未fetch。tree中有gen_debug_skill_android.sh/update_models_dev.sh，无本輪check_docs_provenance/test_docs_provenance/verify-android-16k/release-check同路径。尝试git show上游update_models_dev返回bad object（tree记录存在、blob本地不可读），因此不作其内容相同/不同的结论，不为补对照fetch。DPG01以本地checker与CI入口为依据。

## 覆盖与复核

| 新增全文文件 | 行数 |
|---|---:|
| [audit_strings.py](/home/jiale/projects/minis-for-android/scripts/audit_strings.py:1) | 312 |
| [build-android-debug.ps1](/home/jiale/projects/minis-for-android/scripts/build-android-debug.ps1:1) | 71 |
| [check_docs_provenance.py](/home/jiale/projects/minis-for-android/scripts/check_docs_provenance.py:1) | 283 |
| [gen_debug_skill_android.sh](/home/jiale/projects/minis-for-android/scripts/gen_debug_skill_android.sh:1) | 152 |
| [release-check.sh](/home/jiale/projects/minis-for-android/scripts/release-check.sh:1) | 67 |
| [test-android-16k.py](/home/jiale/projects/minis-for-android/scripts/test-android-16k.py:1) | 51 |
| [test-provider-customization.sh](/home/jiale/projects/minis-for-android/scripts/test-provider-customization.sh:1) | 91 |
| [test_docs_provenance.py](/home/jiale/projects/minis-for-android/scripts/test_docs_provenance.py:1) | 208 |
| [update_models_dev.sh](/home/jiale/projects/minis-for-android/scripts/update_models_dev.sh:1) | 17 |
| [verify-android-16k.sh](/home/jiale/projects/minis-for-android/scripts/verify-android-16k.sh:1) | 110 |
| [verify-android-bundle.sh](/home/jiale/projects/minis-for-android/scripts/verify-android-bundle.sh:1) | 28 |
| [ThinkingLevelCatalogSnapshotTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/provider/ThinkingLevelCatalogSnapshotTest.kt:1) | 68 |
| [XAIEmptyEffortTiersTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/provider/thinking/XAIEmptyEffortTiersTest.kt:1) | 146 |

全文输出完整；check_docs_provenance分1—170/171—283，audit_strings分1—165/166—312读取。相关Gradle/CI/resolver片段不重复全文计数。这里只运行报告/台账完整性核验，不运行任何被审测试。

累计全文 **1318 / 1546文件、253556行**；partial13，not_registered215，excluded124。全仓通读尚未完成。完成时核验全部1670 tracked路径SHA-256、字节数、正数文本行数，本仓/上游固定HEAD、tracked/staged空diff、此前128份报告哈希、新报告链接行号与全文批次总计。只新建本报告并更新台账。
