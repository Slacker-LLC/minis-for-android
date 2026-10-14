# 宠物聊天凭据与空回复静态审查

日期：2026-10-06。固定 HEAD `d64e7c06d8159be90737dd9ba03dd9bd5855f339`。本轮确认 **2 项新入口缺陷（P2、P3 各一项）**，全文阅读 2 文件、558 行。只排查记录，未修改源码，未运行测试、构建、模拟器、设备、Root 或云任务；旧报告不改。

## PC01 / P2：宠物聊天拒绝已受支持的免密钥兼容模型

[PetChatEngine.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/pet/PetChatEngine.kt:76) 直接 loadApiKey；结果 null 就报“这个模型还没配置 API Key”，在读取实例和建立 Provider 之前停止。这不同于正常聊天的 [ChatViewModel.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:5003)，后者使用 usableApiKey(instance)。

现役 [ProviderConfig.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/model/ProviderConfig.kt:284) 明确实现 allowsEmptyAPIKey：API-key 模式、有 customBaseURL 的 OpenAI/Anthropic 兼容实例允许无 key。[ProviderRepository.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/ProviderRepository.kt:2395) 的 usableApiKey 会在此种实例无保存 key 时返回空字符串，而不是 null。

静态反例：用户配置免鉴权的自定义兼容服务，手动添加并选为主模型，没有保存 apikey 记录。正常聊天可以通过凭据路由；宠物 resolveEntry 选中同一模型，但第77行必然提前失败，不会发出该次模型请求，也未进入 persistUserTurn。这里不声称任意私人服务必定网络可达或正常聊天必定成功；确认的是宠物为合法配置错误设置了不可跨越的 key 门槛。若已显式保存空字符串，loadApiKey 返回非 null，则不触发该反例。

既有 CF10 是 ProviderRepository 的模型目录刷新入口，本项是 PetChatEngine 实际问答入口；两处调用及用户结果不同，不能用“目录刷新问题已记录”认为宠物入口已核验。方向：复用现有实例凭据语义，不要求用户补一个无意义的假 key。未访问真实端点或凭据。

## PC02 / P3：清理后空回复仍被当成成功并写入会话

[PetChatEngine.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/pet/PetChatEngine.kt:114) 只校验模型原始 text.trim 非空。随后 [PetChatEngine.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/pet/PetChatEngine.kt:208) condense 删除完整代码块、Markdown 标记并压平空白，没有保证处理结果仍非空。第117—130行把 condense 返回值记进短期 history、持久化 assistant，并返回 Result.success。

静态反例：模型仅输出一个完整围栏代码块，例如三个反引号包住 hello。原始内容非空，前置检查通过；condense 的代码块正则将整段替换为空白，trim 后为零字符。[PetOverlayService.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/pet/PetOverlayService.kt:271) 仍执行成功分支，窗口 [PetChatWindowView.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/pet/PetChatWindowView.kt:257) 添加一个只有角色标签、没有正文的回复；没有小窗时气泡也拿到空字符串。[PetChatEngine.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/pet/PetChatEngine.kt:170) 同时追加空 text part，普通会话记录不能解释本次失败，下一次请求 history 中还携带空 assistant。

系统提示词要求不写代码块，但 prompt 不是输入校验，代码自己也声明 condense 要约束不遵守指令的模型。本项不主张每个模型都会输出围栏，不把正常字符串截短算作缺陷。方向：在最终展示/入库前验证非空，给出明确失败或保留可读内容；仍复用现有 Result/UI 错误处理。没有发送真实请求或新增测试。

## 取消、持久化和窗口证据边界

PetChatEngine 明确直接调 Provider，不进入 Agent tools；问答先尽力保存 user，再发请求，成功保存经过压缩的 reply。保存失败日志后继续，CancellationException 重新抛出。history 仅保留最近3对成功问答，是内存状态，没有从长寿命“桌面宠物”会话重载；不据此擅自把短上下文设计定性为数据丢失。

服务第258行 cancel 旧 askJob，再启动新任务；输入窗口 setBusy 只禁用发送按钮，IME action 仍可提交并取消旧任务。这里有明确“superseded by a newer one”取消设计注释，不单凭按钮和 IME 差别宣称一定产生并发乱序。同步 Provider I/O 的实际取消时间和持久化竞争要结合执行实现，暂不新增该类确认缺陷。

窗口最多30条消息，输入最多4个可见行而不是字符上限；没有以 maxLines 推断请求体有长度限制。关闭窗口不等同取消 ask，完成结果可能转为气泡。服务设置了 SOFT_INPUT_ADJUST_RESIZE，本轮未验证悬浮窗口/IME/OEM的真实布局，不把用户原先记忆文件页缺键盘避让推广到这个窗口。

PetChatEngine condense 按 code point 截断，未从源码捏造 emoji 代理对被切断；简化 Markdown 可能删除内容中的下划线，需产品展示合同才能进一步定级。没有将所有信息丢失都重复计算成 PC02。

## 固定 OpenMinis 对照

只读本地 `/tmp/openminis-review-20261004` HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，未 fetch。Git 树没有本轮 pet 目录同路径代码，不能提供上游直接修复模板，也不声称两个问题来源于 minisd、PRoot 或 iOS。此次调用属于 Android App 内直接 Provider 问答，未触及 guest 执行链。

检索现有 src/test 与 src/androidTest 没有 PetChatEngine/PetChatWindowView 的直接引用；这只是测试文本覆盖证据，不证明整个 UI 没有任何间接测试。此次未运行或编写测试。

## 全文覆盖与复核

| 本轮全文文件 | 行数 |
|---|---:|
| [PetChatEngine.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/pet/PetChatEngine.kt) | 245 |
| [PetChatWindowView.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/pet/PetChatWindowView.kt) | 313 |

累计全文 **1176 / 1546 个文件、226676 行**；partial 13，not_registered 357，excluded 124。全仓通读仍未完成。两份文件分别完整读取，合并工具输出无截断；PetOverlayService 只复核调用片段，不登记全文。

完成时校验全部1670 tracked路径 SHA-256、字节数和正数文本行数，固定本仓/上游 HEAD、tracked/staged 空 diff、此前100份报告哈希、本报告文件链接与行号、覆盖批次总计。只新建本报告并更新覆盖台账。
