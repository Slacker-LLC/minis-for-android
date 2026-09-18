# 开发移植进度

> 更新规则：每完成一项就更新本表（状态 + 提交 + 验证级别）。**验证级别必须诚实**：
> `compile+test` 表示跑过编译与全量单测；`compile` 表示只编译通过、没跑测试；
> `test(历史)` 表示该测试在移植时跑过、但本轮没有复跑。

## 图例

| 标记 | 含义 |
|---|---|
| ✅ | 已落地且当前分支上 `:app:compileDebugKotlin` + `:app:testDebugUnitTest` 通过 |
| 🟡 | 已落地，仅编译验证（或只有历史测试结论） |
| 🚧 | 进行中 |
| ⬜ | 未开始 |
| ⛔ | 明确排除（附理由） |

## 一、已完成（main 已包含）

**种子：Minis for Android @ 69e05e51 + Eta 移植线 24 个提交。**

| 项 | 内容 | 验证 |
|---|---|---|
| MCP 客户端响应上限 | 4 MiB 预算读取，先查 `Content-Length` 再按流分块计数，超限失败关闭 | ✅ `MCPHttpTransportBoundedResponseTest` |
| MCP `tools/list` schema | 规范字段 `inputSchema` 与 `input_schema` 双拼写兼容，规范优先 | ✅ 契约夹具测试 |
| 工具入参 schema 校验 | 标量类型、`enum`、数组元素、嵌套必填键；超 256 KiB、超 32 层、声明类型未知即拒绝 | ✅ `ToolCallValidatorTest` |
| 技能 ZIP 导入上限 | 压缩流 32 MiB / 单条目 4 MiB / 总量 16 MiB；拒绝绝对路径、`..`、反斜杠、盘符、NUL、重复条目 | ✅ `SkillArchiveReaderTest` |
| 技能安装事务 | 暂存目录 → 原子提交 → 回滚；恢复日志；跨进程文件锁 | 🟡 编译通过 |
| 技能全部读写路径入事务 | add/update/rename/delete/rescan/import 与读路径共用事务与锁；备份恢复同事务 | 🟡 编译通过 |
| 压缩批次边界与摘要门 | 只在完整工具批次间切分、保护最新用户轮、摘要须正常结束/有界/无工具调用/确实缩小 | ✅ `AgentContextCompactorTest`（修正 3 个期望后全绿） |
| 压缩 chunk 驱动调用 | `chunkForSummary` 真正驱动多次摘要调用，溢出对半受 `MAX_OVERFLOW_ATTEMPTS` 约束 | 🟡 编译通过 |
| GUI 动作证据语义 | 五态证据（含方向不符/超时/拒绝）+ 来源写进工具 JSON | 🟡 编译通过 |
| 迟到调用门 | 手势、pinch、back/home、CLI key 共用主线程调用门，超时后未启动即拒绝 | 🟡 编译通过 |
| 观察与截图窗口一致性 | 共用窗口判定；不可解析包名拒绝截取；前台三态；截断快照不当「未变化」 | 🟡 编译通过（真机窗口集合未验证） |
| 敏感工具不落盘 | 参数、结果、snapshot、检查点、上下文快照按分类表替换为占位符 | 🟡 编译通过 |
| 运行检查点与恢复对账 | 事件 + 脱敏 transcript + 上下文快照；活跃或终态未知时不判定中断 | 🟡 编译通过 |
| 记忆注入 | 窗口预算 + 截断标记 + 标题索引 + 内容 `revision` | ✅ `MemoryInjectionBudgetTest` |
| 模型失败分类与重试纪律 | 可重试/不可重试/溢出三类；副作用后不重试；失败轮思考丢弃；退避可取消 | 🟡 编译通过 |

## 二、已完成（分支，待合入 main）

**Phase 1 UI/交互轨** — 分支 `codex/eta-phase1-ui`，基于 `24ba1db`：

| 项 | 内容 | 提交 | 验证 |
|---|---|---|---|
| Phase 1-1 工作过程收束 | 连续 thinking + tool 块收束为一条可展开行（扳手或运行中工具图标 + 「正在执行第 N 步 · 工具」/「已完成 N 个步骤」/「第 N 步失败 · 原因」）；运行中自动展开、结束自动折叠、手动点击优先；`stepsPresentation = grouped`（默认）/ `perTool` 保留原逐条 pill 与长按菜单 | `f776b88` | ✅ |
| Phase 1-2 助手首页 | 问候语 + 2×2 可配置快捷卡（分析当前屏幕 / 打开微信 / 浏览网页 / 查看内存压力）；入口挂在会话列表顶部且列表仍为主面；可设为启动页（独立开关，不改 `launch_session` 既有语义） | `5b2face` | ✅ |
| Phase 1-3 流式观感 | 未闭合 Markdown 虚拟补齐投影（只用于渲染、终态校验投影输入等于原文、非追加输入重建基线、投影会丢已确认内容时失败关闭）、并发解析管线、显现进度单调钳制；压缩点内联标记与冻结块缓存沿用既有实现，未改 | `2544774` | ✅ |
| 归属登记 | `THIRD_PARTY_LICENSES.md` 增补 5 个 Eta 移植模块（工作过程分组、流式投影、解析管线、复位记账、显现算法） | `5b8adfc` | 文档：`test_docs_provenance.py` + `check_docs_provenance.py` 通过 |

✅ 的定义：该分支上 `:app:compileDebugKotlin` + `:app:testDebugUnitTest` 通过（1841 个用例 = 既有 1786 基线 + 新增 55 个纯逻辑用例）。
新增用例覆盖：分组边界（text/info/media 断组、隐藏思考不建行、空组拒绝）、失败原因取值与截断、`stepsPresentation` 解码回退；未闭合围栏/链接/表格候选与非法分隔符、终态投影校验、非追加重建基线；显现进度钳制与回退禁止、字素边界（组合音标/ZWJ/旗帜/CRLF）、帧预算；解析管线跳过被取代目标与改写丢弃；助手首页动作编解码与问候时段。


**系统提示词模块化与自定义输入框** — 分支 `codex/system-prompt-modules`，基于 `6091b9b`；从 minis-for-android `a113ad1e` 移植（cherry-pick 零冲突 + 本地适配）：

| 项 | 内容 | 提交 | 验证 |
|---|---|---|---|
| 自定义系统提示词 | 设置 → 系统提示词 变为自由文本框（类似 Codex custom instructions）：存 `<filesDir>/system_prompt/custom.md`，注入在 SOUL 身份层之后、所有内置模块之前，带优先级声明；留空不注入，组装结果与改造前逐字节一致（两份 legacy fixture 锁定） | `8f92e88` | ✅ + lint |
| 静态提示词模块化 | 原 `ChatViewModel.buildSystemPrompt()` 里约 170 行硬编码整段删除，默认文案改为 `assets/prompts/<id>.md` 一文件一节（16 节）；新包 `com.openminis.app.prompt`（Registry / Store / Composer / CustomPromptStore / AgentSystemPrompt）；per-turn 片段（skills / MCP / 记忆 / runtime context）保持原样 | `8f92e88` | ✅ + lint |
| 内置模块二级页 | 设置 → 系统提示词 → 内置提示词模块：逐节编辑 / 关闭 / 恢复默认，显示 id、运行时门控、覆盖状态；UI 订阅 store 的 StateFlow | `8f92e88` | ✅ + lint |
| 配置与深链 | `minis-config` 读写 `prompt.custom`、`prompt.modules`、`prompt.<id>.text`、`prompt.<id>.enabled`；`minis://settings/system-prompt` 打开输入框 | `8f92e88` | ✅ + lint |

本仓库验证(全绿)：`:app:compileDebugKotlin` + `:app:testDebugUnitTest`(1856 用例 0 失败) + `:app:lintDebug` + `:app:lintRelease`(0 error) + `:app:assembleDebug`。

本仓库相对来源的适配：保留本分支已在用的记忆预算调用（`loadGlobalMemoryFragmentAsync(effectiveContextWindowTokens())`）；新增 `values-zh-rTW` 译文（源仓库无此语言）；补齐 Phase 1 字符串在 de/fr/ja/ko/ru/zh-rTW 的翻译（清掉 43 个 lint MissingTranslation error）；修掉 2 个 AppNavigation 的 Compose lint error；`docs/contracts/06-CURRENT-GAPS.md` 里真机结论标注为「在来源仓库 `a113ad1e` 上完成，本仓库未复跑」。

**Phase 2-5 Provider 自定义请求头与请求体** — 分支 `codex/eta-phase2-provider-passthrough`，基于 `fcccb587`：

| 项 | 内容 | 提交 | 验证 |
|---|---|---|---|
| 自定义请求头统一过滤 | `extra_headers`（标准 chat）、图片 passthrough、`passthrough.headers` 三个入口共用 `provider/CustomHeaderPolicy.kt`：协议头（`Host`/`Content-Length`/`Connection`/`Transfer-Encoding`/`Content-Encoding`/`Accept-Encoding`/`Expect`/`Keep-Alive`/`Proxy-Connection`/`Upgrade`）与凭据头（`Authorization`/`x-api-key`/`anthropic-version`）一律丢弃并给出警告；名称按 RFC 7230 token 字符集校验，值限可打印 ASCII 或 tab，大小写不敏感重名后者胜（先前那个被移除而不是一起发出去） | `61e0f8fe` | ✅ `CustomHeaderPolicyTest`（10 例，含全部拒绝路径） |
| 请求头值日志脱敏 | 原始 passthrough 的 `headerOverrides` 日志改为输出脱敏后的 `名=值`，`authorization`/`x-api-key`/`api-key` 记为 `***` | `61e0f8fe` | ✅ `CustomHeaderPolicyTest` |
| 请求体递归合并 | chat/responses `extra_body`、`images/generations` passthrough、`passthrough.body`（merge 模式）统一走 `provider/RequestBodyMerge.kt`：两侧都是对象时逐字段递归，数组整体替换，标量与 null 覆盖；`model` 仍由 App 最后钉住，调用方改不了路由 | `61e0f8fe` | ✅ `RequestBodyMergeTest`（8 例，含深层递归、对象被标量替换、调用方后续改写不影响已合并对象） |
| 归属登记 | `THIRD_PARTY_LICENSES.md` 的 ported-modules 表增补 `CustomHeaderPolicy.kt`、`RequestBodyMerge.kt` 两行 | `61e0f8fe` | 文档：`test_docs_provenance.py` + `check_docs_provenance.py` 通过 |

✅ 的定义：该分支上 `:app:compileDebugKotlin` + `:app:testDebugUnitTest` 通过（1874 个用例 = 分支既有 1856 + 新增 18，0 失败）。行为变化只有一处需要在真机复核：调用方此前可以用 `extra_headers` 顶掉 App 自己的凭据或协议头，现在失败关闭（头被丢弃 + 结果 JSON 的 `warnings` 里出现原因）；其余自定义头与既有请求体字段语义不变。

**Phase 2-2 Responses 引用格式化** — 同一分支 `codex/eta-phase2-provider-passthrough`：

| 项 | 内容 | 提交 | 验证 |
|---|---|---|---|
| `url_citation` 渲染 | Responses 流此前把 `response.output_text.annotation.added` 当未知事件丢掉，带搜索来源的回答没有任何出处；现在按 Eta 规则渲染：只收 http(s)、同一 URL 只编号一次、编号按首次出现顺序、URL 中的 `>` 转义为 `%3E`、无标题回退 `来源 N` | `8041a065` | ✅ `ResponsesCitationFormatterTest`（13 例） |
| 降级路径 | 与 Eta 的唯一差异（流式架构决定）：Eta 在完整文本上格式化，可在任意偏移插入角标；Minis 的文本只追加、已上屏和已入库的增量无法改写，因此只有「结束位置正好等于当前流头」的引用就地插入，其余一律降级为末尾来源列表——即 Eta 对失效偏移用的同一种降级 | `8041a065` | ✅ 上述用例覆盖就地/降级/重复 URL/非法偏移/非 http/只输出一次 |

✅ 的定义：该分支上 `:app:compileDebugKotlin` + `:app:testDebugUnitTest` 通过（1887 个用例 = 上一项后的 1874 + 13，0 失败）。未做的部分：服务端 `web_search` 开关（默认关）本项没有新增——当前调用方仍可经 `extra_body` 自带 `tools`，是否需要专门开关待定；真机观感（来源列表排版、链接可点）未验证。

**Phase 2-1 Responses output item 回放** — 同一分支 `codex/eta-phase2-provider-passthrough`：

| 项 | 内容 | 提交 | 验证 |
|---|---|---|---|
| 不透明 output item 捕获 | `response.output_item.done` 里的 `reasoning` 项若带 `encrypted_content`，随 `LLMStreamChunk.ProviderOutputItem` 交给 agent loop；不带加密内容的不捕获，保证不发这类项的 relay 请求形状与改动前完全一致 | `69d7f2b4` | ✅ `ResponsesOpaqueItemReplayTest`（5 例） |
| 回合内逐字回放 | 捕获项挂在内存里的 assistant 轮（`LLMMessage.providerOutputItems`），Responses 请求体在重建的正文与 `function_call` 之前按原位置逐字回放；`store:false` 下工具回合之间不再丢失模型的加密思维链 | `69d7f2b4` | ✅ 线上请求体断言：`message:user → reasoning → function_call → function_call_output` |
| 生命周期与失败路径 | 字段只存在于内存（Room 映射不读不写，重载会话回退到重建形状，与 Eta `ResponsesEphemeralState` 一致）；流失败重试前清空捕获项，废弃的那次尝试不会被回放；非法 JSON 项跳过 | `69d7f2b4` | ✅ 用例覆盖无捕获项/非法 JSON/无加密内容三条路径 |

✅ 的定义：该分支上 `:app:compileDebugKotlin` + `:app:testDebugUnitTest` 通过（1892 个用例 = 上一项后的 1887 + 5，0 失败）。未做：Eta 是把**全部** output items 逐字回放并跳过重建；这里只回放正文与 `function_call` 无法重建的那一项（reasoning/encrypted），其余仍按既有重建路径生成（`function_call` 的 `id`/`call_id` 本来就逐字保留）。Codex OAuth 真机链路（加密思维链是否真的被接受）未验证。

**Phase 2-4 UI 坐标空间契约** — 同一分支 `codex/eta-phase2-provider-passthrough`：

| 项 | 内容 | 提交 | 验证 |
|---|---|---|---|
| 坐标空间 | `android_ui screenshot` 默认按 `scale=0.5` 缩放，但 `click`/`long_press`/`scroll` 一直把 `x`/`y` 当设备像素直接下发——模型照着截图上读到的位置点下去，实际只走了一半距离（静默点错，事后也查不出，因为轨迹里只有下发的数字）。现在新增 `coordinateSpace` 契约（Eta 在 schema、执行器、轨迹之间共享同一套定义）：`screenshot`（默认）= 最近一次截图像素，按该次采集的几何换算；`screen` = 真实设备像素，原样下发 | `5509b963` | ✅ `UiCoordinateSpaceTest`（14 例） |
| 轨迹与降级 | 结果 JSON 记下 `coordinateSpace`、请求值与下发值；`scroll` 的锚点与两个方向增量走同一契约；截图动作记录 frame | `5509b963` | ✅ 上述用例 |
| 失败关闭 | 没有可用截图 → `COORDINATE_SPACE_UNAVAILABLE`；截图后屏幕尺寸变了 → `SCREENSHOT_FRAME_STALE`；点落在图像之外 → `COORDINATE_OUT_OF_SCREENSHOT`，消息里直接给出「重新截图」或 `coordinateSpace=screen`；全部拒绝而不是猜测。`screen` 空间不受图像边界约束 | `5509b963` | ✅ 用例覆盖四条拒绝路径与边界点 |

✅ 的定义：该分支上 `:app:compileDebugKotlin` + `:app:testDebugUnitTest` 通过（1906 个用例 = 上一项后的 1892 + 14，0 失败）。**这一项有唯一一处显式行为变化**：默认空间从「隐式的设备像素」变成「截图空间」。因此按 `observe` 的节点 bounds 或 `originalWidth/Height` 直接传 `x`/`y` 的调用方必须显式写 `coordinateSpace=screen`（若超出缩放后图像范围会被拒绝并提示，只有落在图像范围内的这种调用会被静默换算）。真机未验证：缩放截图下的实际点击落点、旋屏后的 frame 失效判断、系统繁忙时 `resources.displayMetrics` 与截图尺寸是否始终一致。

**Phase 2-3 文件视觉（read_image 直读相册）** — 同一分支 `codex/eta-phase2-provider-passthrough`：

| 项 | 内容 | 提交 | 验证 |
|---|---|---|---|
| 图片来源分类 | `android.media.images` 给的是 `content://media` URI，而 `read_image` 只认 guest 路径，看一张相册照片必须先 `android-photos export` 再读导出文件（不带 `--size` 还会导出缩放副本，模型可能分析的不是原图）。现在 `path` 由一处 `ImageSourcePolicy` 分类：guest 路径 / `minis://` / `file://` 照旧解析成工作区路径，`content://media` 直读 | `4e5f9df5` | ✅ `ImageSourcePolicyTest`（12 例） |
| 直读实现 | MediaStore URI 走 App 自己的 resolver：按 API 级别检查 `READ_MEDIA_IMAGES` / `READ_EXTERNAL_STORAGE`（缺失时走既有应用内授权弹窗并复检）、`image/*` 类型校验、与 guest 路径同一个 50 MiB 上限和同一个缓存槽，后续解码/缩放/元数据仍是一条代码路径 | `4e5f9df5` | 🟡 编译通过（resolver 路径需真机或 instrumentation，宿主单测覆盖的是分类逻辑） |
| 失败关闭 | 空路径、带 host 的 `file://`、其它 authority 的 `content://`（提示 `android-photos export`）、远程 URL 全部带原因拒绝；非图片类型与超限流各自报错；授权被拒/超时同时给出权限名与导出替代路径 | `4e5f9df5` | ✅ 上述用例 |

✅ 的定义：该分支上 `:app:compileDebugKotlin` + `:app:testDebugUnitTest` 通过（1918 个用例 = 上一项后的 1906 + 12，0 失败）。真机未验证：真实相册 URI（含 photopicker）的读取、授权弹窗与后台 service 场景下能否拿到结果、超大原图的读取上限行为。

**Phase 3 起步：Skills 暴露给模型（只读面）** — 分支 `codex/eta-phase3-skills-tools`，基于 `82009103`：

| 项 | 内容 | 提交 | 验证 |
|---|---|---|---|
| 技能发现 | 模型此前只能在提示词里看到最多 20 条技能条目，读全文只能猜 `/var/minis/skills/<id>/SKILL.md` 再用文件/shell 工具打开。新增 `skill.list`（别名 `skills_list`）：id/名称/启用状态/路径/描述，支持关键词过滤与 1–200 条上限（默认 50），并报告被上限截掉多少条 | `7741677c` | ✅ `SkillToolPolicyTest`（10 例） |
| 技能正文与资源 | `skill.read`（别名 `skills_read`）按 id/名称/SKILL.md 路径读全文，512–64000 字符（默认 16000）并显式标注截断；`skill.read_resource`（别名 `skills_read_resource`）读技能内的有界 UTF-8 文本资源（如 `references/guide.md`） | `7741677c` | ✅ 上述用例 |
| 复用而非新建通道 | 读路径全部走既有 `SkillRepository`，共用其跨进程变更锁与路径守卫；工具只加边界与 id/名称/路径解析，不写、不装、不执行；资源读失败时列出该技能实际包含的文件 | `7741677c` | ✅ 路径拒绝用例（空/绝对/反斜杠/控制字符/`..`/超长） |

✅ 的定义：该分支上 `:app:compileDebugKotlin` + `:app:testDebugUnitTest` 通过（1928 个用例 = Phase 2 分支的 1918 + 10，0 失败）。未做：`skills_list_curated` / `skills_inspect_github` / `skills_install_from_github`（安装面仍走既有事务，尚未暴露给模型）；真机未验证：模型在真实会话里发现并读取技能、禁用技能在列表里的可见性、大技能正文的截断观感。

## 三、待办阶段（顺序与规格见 `docs/analysis/eta-port-program.md`）

| 阶段 | 内容 | 来源 |
|---|---|---|
| Phase 2 底层 AI | 服务端 `web_search` 开关、工具能力投影与终态门（请求头与请求体合并、引用格式化、Responses opaque output 回放、UI 坐标空间契约、`read_image` 直读相册已在 `codex/eta-phase2-provider-passthrough` 落地；屏幕观察的其余合同 Minis 侧本就更强，未再移植） | Eta `agent/model/*` |
| Phase 3 数字助手 | 助手浮层面板、GUI 动作补齐、会话级编辑（Skills 的只读面已在 `codex/eta-phase3-skills-tools` 落地；GitHub 发现/安装面待做） | Eta `agent/voice`、`agent/overlay`、`agent/tool` |
| Phase 4 个人上下文 | 通知历史检索、闹钟与计时器、健康摘要、媒体/录音/文件检索、聊天图片、设备环境、会话历史检索 | Eta `agent/tool/AgentPersonal*Tools.kt`、`agent/device/*` |
| Phase 5 角色系统 | 角色卡（酒馆 PNG/JSON）、世界书、剧情记忆、宏、角色界面与导入导出 | Eta `agent/roleplay/*` |
| Phase 6 厂商入口接管 | libxposed 接入 + 电源键、小布、超级小爱、一圈即搜、Google 解锁 + 无障碍保活 | Eta `hook/*`、`ModuleMain.kt` |

## 四、明确排除

| 项 | 理由 |
|---|---|
| PRoot / Alpine / 多发行版安装器（不引入） | 与「单一 Direct Ubuntu 24.04 chroot」合同冲突 |
| systemizer（把 Google App 装成系统应用） | 扩大 Root 面，超出「Root 只做受控基础设施」边界 |
| 厂商私有内部类实现（HyperOS/ColorOS 内部方法） | 跨版本不稳定，且无法在宿主复现验证 |
| 第二套跨进程 runtime 协议 / 终态 outbox | 单进程应用以 Room + ViewModel 为真源，重复实现会造成两套状态源 |
| 在线商店类分发面 | 产品定位与服务端依赖不在本仓库范围 |

## 五、未验证清单（不得据此声称设备结论）

- 技能事务在真机上的 `rename`/`fsync` 行为、进程被杀后的 journal 回滚、跨进程锁竞争。
- 无障碍窗口集合、截图包含关系、主线程门在系统繁忙时的真实时序、滚动事件在各 ROM/WebView 的一致性。
- 运行检查点在真实进程死亡（低内存回收、强停、崩溃）后的对账与 Resume 文案。
- 敏感工具脱敏的端到端验证（真实 MCP 服务 + 真实设备数据）。
- 个人数据检索（通知、位置、相册、健康）的授权与真实数据行为。
- LSPosed 作用域、电源键时序、厂商 ROM 适配与 Hook 生效状态。
- 角色卡导入导出的兼容性（酒馆格式边界）。
- Phase 1 工作过程收束：展开/折叠动画与长列表滚动表现；失败原因在真实工具错误文本下的排版。
- Phase 1 助手首页：四张卡的设备行为（微信未安装时的提示、内置浏览器、内存弹窗数值、「分析当前屏幕」在真实无障碍/截屏能力下的效果）；「设为启动页」的冷启动路径只做了代码走查。
- Phase 1 流式观感：未闭合投影与显现进度在真实模型输出上的观感、帧率与滚动跟随。
- 系统提示词模块化：本仓库未复跑真机验证（来源仓库在小米 24129PN74C Debug 包上验证过输入框保存、config 读写与模块编辑/开关/恢复默认）；`debug.llmRequests` 抓取真实请求的注入顺序同样只在来源仓库做过。
- 自定义提示词与模块覆盖不进入备份/恢复类别，换机后丢失（沿用来源仓库的已知限制）。
