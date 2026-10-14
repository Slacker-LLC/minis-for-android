# 模型槽位管理与设备语音搜索静态审查

日期：2026-10-06。固定本仓HEAD `d64e7c06d8159be90737dd9ba03dd9bd5855f339`。新增 **1项P3确认缺陷**。全文读取4文件、1045行；未修改产品代码，未执行测试、构建、模拟器、设备、TTS初始化、云或模型请求。旧报告不改。

## MS01 / P3：搜索具体设备语音时，其总条目不匹配便隐藏整个结果组

[UnifiedModelPickerSheet.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/components/UnifiedModelPickerSheet.kt:321) 先取modalityFilter.systemEntries，再按每个总条目的model.displayName筛选；第323行只有该列表非空才创建system_card。设备TTS语音的systemVoices.filter(matches(label))位于此卡片内部第402—405行。

AUDIO_OUTPUT的系统总条目只有SystemVoiceEntries.tts，ModelEntryPicker第93行明确返回单项，其displayName是System Voice (Auto)（SystemVoiceEntries第48行）。设备语音标签由 [SystemTtsVoiceCatalog.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/SystemTtsVoiceCatalog.kt:89) 生成：当前UI语言下的locale显示名、variant及网络标记，和总条目名不同。

生产入口 [SpeechPlayerCapsule.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/voice/SpeechPlayerCapsule.kt:235) 打开VoiceOutputPickerSheet；[VoiceInputPickerSheet.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/voice/VoiceInputPickerSheet.kt:52) 传AUDIO_OUTPUT，没有关闭默认allowDeviceVoiceSelection，统一选择器第116—119行会列出设备语音。因此不是未接线的实验页面。

静态反例：设备有一条已安装中文语音，中文UI下label含“中文”；无搜索时能看见。输入“中文”，matches(System Voice (Auto))为false，systemEntries变空，整个system_card不创建，内部语音label匹配不会执行。输入只属于某个variant的关键词也同理。用户无法通过搜索找到实际存在的语音，需清空搜索重新滚动选择；并非设备未安装或TTS目录获取失败。

方向：分别筛选系统总条目和具体设备语音，任一类有结果都保留组，并使用不受搜索过滤影响的系统entry ID绑定设备语音。本轮没有修改。固定OpenMinis同文件第543—545行、第625—634行保留同样外层总条目过滤、内部语音过滤结构，问题共有。

## 槽位编辑与运行规则复核

ModelsScreen第125—131行选择主条目后，使用最新repository.config，将选中ID移到首位并保留其余候选顺序，未凭“单选picker”认定它删除所有fallback。showSlotFollow=false避免以null意外清除主slot；allowDeviceVoiceSelection=false避免在全局slot编辑中顺便改设备级voice偏好。语音面板使用默认true，两个用途的差异是明确传参。

ModelSlotDetailScreen第164—181行按Text输出、Image输入、Audio输入/输出筛候选，注入系统ASR/TTS。禁用Provider仍在slot管理页显示且可安排为候选，标注provider disabled；统一picker仅显示启用provider。该行为允许预配置，不能据此判定禁用provider必然被运行。已选ID若不在当前候选中会显示legacy unresolved，可能包括隐藏/能力变更的现存模型；需要与runtime实际解析规则核对，暂不加新缺陷。

ReorderableColumn结算调用ProviderRepository.reorderSlot，第1367—1374行在configLock下使用最新existing；reorderSlotEntries第90—92行只接受现有ID，去重并补回未列出的成员，不能把旧UI顺序提交直接说成新增/删除slot成员。toggleSlotEntry仍是读取后set的两阶段操作，外部同时修改时可能旧集合覆盖，需要实际并发来源证据，未按孤立代码计数。

系统slot-follow标记用activeVoiceSlotEntryId，而设备具体voice标记第423行仅看selectedId==sysId；follow场景可能无具体voice勾选但不等于引擎错误。语音override普通属性的响应刷新、搜索设备v.name而非label、恢复折叠状态等均为后续核对点，没有运行UI或捏造失败。

## 固定上游与测试范围

只读 `/tmp/openminis-review-20261004` Git对象HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，未fetch。其tree没有本轮ModelsScreen与ModelSlotDetailScreen同路径，有VoiceInputPickerSheet与UnifiedModelPickerSheet；不拿旧model group界面代替本地slot行为。MS01比较具体上下两层过滤代码。

本仓存在ModelSlotReorderTest，相关逻辑已在此前全文批次登记，本轮仅确认路径并复核生产helper，不重复增加全文/测试统计。未发现本轮同名picker专用测试路径，不能因此声称全仓没有任何相关覆盖。未运行或新增测试。

## 全文覆盖与复核

| 新增全文文件 | 行数 |
|---|---:|
| [ModelSlotDetailScreen.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/ModelSlotDetailScreen.kt:1) | 188 |
| [ModelsScreen.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/ModelsScreen.kt:1) | 156 |
| [UnifiedModelPickerSheet.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/components/UnifiedModelPickerSheet.kt:1) | 641 |
| [VoiceInputPickerSheet.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/voice/VoiceInputPickerSheet.kt:1) | 60 |

UnifiedModelPickerSheet按1—330、331—641完整阅读，其余完整输出。已有文件只片段复核，不重复统计。

累计全文 **1211 / 1546文件、234345行**；partial13，not_registered322，excluded124。全仓通读尚未完成。完成时核验全部1670 tracked路径SHA-256、字节数与正数文本行数，本仓/上游HEAD、tracked/staged空diff、此前111份报告哈希、新报告链接行号、全文批次总计。只新建本报告并更新台账。
