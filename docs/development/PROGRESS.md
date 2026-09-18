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

| GitHub 发现与安装 | `skill.inspect_github`（别名 `skills_inspect_github`）把 ref 解析成 commit，列出仓库里所有含 `SKILL.md` 的目录（仓库根目录的 SKILL.md 不算），可 `path` 收窄，上限 200 并如实报告截断，返回可供钉住的 commitSha；`skill.install_github`（别名 `skills_install_from_github`）在该 commit 上取 SKILL.md，走既有事务安装，再用**同一个 commit** 下载兄弟文件并二次提交，装出来的技能不会是两个版本的混合 | `45d222d0` | ✅ `SkillSourcePolicyTest`（12 例，纯解析/拒绝路径） |
| 安装的失败关闭 | 安装永不覆盖：同名 id 返回 `SKILL_CONFLICT` 并说明是内置还是用户技能，指向 Settings → Skills 更新；不执行技能内脚本；私有仓库、未知 ref、API 限流、缺 SKILL.md、frontmatter 不可用、事务被拒各有独立原因；仓库解析拒绝非 github 主机、绝对路径与 `..` | `45d222d0` | ✅ 上述用例 + 🟡 三次网络调用（commits / git·trees / raw）宿主未覆盖 |

✅ 的定义：该分支上 `:app:compileDebugKotlin` + `:app:testDebugUnitTest` 通过（1940 个用例 = 上一项后的 1928 + 12，0 失败）。未做：`skills_list_curated`（Eta 的 openai/skills 精选目录，需要额外约定目录清单）；安装的替换/更新流（本仓库已有 UI 侧 `updateFromURL`，模型侧只做新建）；真机未验证：模型在真实会话里发现并读取技能、真实 GitHub 仓库的发现/安装端到端、大技能正文的截断观感、限流下的报错文案。

**Phase 3 GUI 动作：系统面板** — 同一分支 `codex/eta-phase3-skills-tools`：

| 项 | 内容 | 提交 | 验证 |
|---|---|---|---|
| 系统面板动作 | `android_ui` 此前只有 `back`/`home`，而 guest 的 `android-a11y-cli` 已支持 `RECENTS`/`NOTIFICATIONS`、Eta 也有等价的 `open_system_panel`——模型要拉通知栏只能退回坐标手势。现在 `recents`/`notifications`/`quick_settings` 与 back/home 走同一条全局动作路径（含既有证据语义） | `bd12dcdd` | ✅ `UiGlobalActionsTest`（5 例） |
| 单一事实来源 | 线名、结果里的标签、以及每个动作对应的平台常量集中在一个枚举里，schema、执行器与轨迹都读它，广告出来的动作列表不会与真正派发的分支漂移；Eta 的拼写（`notification`/`quicksettings`/`settings`）作为别名解析，其它一律 `INVALID_ACTION` 拒绝 | `bd12dcdd` | ✅ 别名与拒绝用例 |

✅ 的定义：该分支上 `:app:compileDebugKotlin` + `:app:testDebugUnitTest` 通过（1945 个用例 = 上一项后的 1940 + 5，0 失败）。未做（Eta GUI 动作清单里本仓库确实还缺的）：按名称搜索已安装应用（`search_apps`）、`wait_for_package`；其余（启动应用、open URI、等待文本、元素定位变体）本仓库分别由 `android_app launch`、`android.intent.send`、`android_ui wait` 与 generation+ref 覆盖。真机未验证：通知栏/快捷设置在各类 ROM 上的实际展开与证据读数。

**Phase 3 GUI 动作：应用检索与前台等待** — 同一分支 `codex/eta-phase3-skills-tools`：

| 项 | 内容 | 提交 | 验证 |
|---|---|---|---|
| 应用检索 | `android_app` 此前无法把显示名换成包名，模型要打开「备忘录」只能猜 `com.example.notes`。新增 `action=search`：在 App 可见的启动器活动里按名称或包名匹配（大小写不敏感），按名称排序、包名做平局裁决，默认 20 行、上限 50；结果报告可见启动器数量并写明范围（非启动器/隐藏包在这里看不到，需要授权后的 `pm` 查询） | `ec532059` | ✅ `AppSearchPolicyTest`（5 例） |
| 前台等待 | `android_ui` 只能等文本出现/消失，没法确认某个包真的到了前台——而启动应用之后要的正是这个。新增 `action=wait_for_package`：轮询前台窗口直到目标成为（`appear`）或不再是（`disappear`）前台应用；读不到前台时两个方向都不算命中，超时返回 `observedVisibility` 与 `visibilityUnknown`，而不是声称目标没出现 | `ec532059` | ✅ `PackageWaitPolicyTest`（4 例） |

✅ 的定义：该分支上 `:app:compileDebugKotlin` + `:app:testDebugUnitTest` 通过（1954 个用例 = 上一项后的 1945 + 9，0 失败）。至此 Eta GUI 动作清单在能力上齐了：启动应用=`android_app launch`、按名检索=`android_app search`、系统面板=`android_ui` 的 back/home/recents/notifications/quick_settings、open URI=`android.intent.send`、等待文本/等待包=`android_ui wait`/`wait_for_package`、定位变体=generation+ref 与 textFilter/resourceIdFilter。真机未验证：启动器可见范围在各 ROM 上的实际数量、`wait_for_package` 在系统繁忙/分屏下的读数、检索结果的排序观感。

**Phase 3 会话级编辑：Markdown 导出** — 同一分支 `codex/eta-phase3-skills-tools`：

| 项 | 内容 | 提交 | 验证 |
|---|---|---|---|
| Markdown 导出 | 会话导出此前只有 JSON 与纯文本；纯文本把思考压进正文、把工具活动压成没有参数/结果/状态的一行。新增第三种格式：每轮一个标题、思考折进 `<details>`、工具活动逐行引用并带参数、结果与状态（ok/failed/unrecorded） | `d7a61891` | ✅ `ConversationMarkdownExporterTest`（11 例） |
| 流式与有界 | 投影是纯逻辑（`share/ConversationMarkdownExporter.kt`），按块经既有 staging→zip 管线写出，长会话仍只占一个块的内存；参数与结果截到 400 字符并带显式截断标记；归档内文件名可读（`Minis-<title>-<yyyyMMdd-HHmm>.md`，剔除路径不安全字符、标题截断） | `d7a61891` | ✅ 截断/文件名/空块用例 |
| 状态不猜 | 工具调用在**结果到达**（下一条 user 行）时才落笔，按 tool_use id 配对；没回来的调用标 `unrecorded`，不冒充成功 | `d7a61891` | 🟡 配对逻辑在 `ChatExporter` 内，宿主未覆盖（纯渲染部分已覆盖） |

✅ 的定义：该分支上 `:app:compileDebugKotlin` + `:app:testDebugUnitTest` 通过（1965 个用例 = 上一项后的 1954 + 11，0 失败），`:app:lintDebug` 无新增问题（新增字符串已补齐 8 个 locale）。真机未验证：分享面板收到 `.md` 的行为、长会话导出的观感与耗时、`<details>` 在各 Markdown 阅读器里的折叠表现。

**Phase 3 助手浮层面板（第一片：就地控制）** — 同一分支 `codex/eta-phase3-skills-tools`：

| 项 | 内容 | 提交 | 验证 |
|---|---|---|---|
| 就地停止 | 浮层胶囊此前只有 X（仅收起胶囊）：用户在其他 App 里看着任务跑，却没法取消，只能回 App 或下拉通知。现在胶囊在「可取消」时多一个停止控件，走与通知 Stop 完全相同的取消路径（先向所有活跃流广播，让各 ChatViewModel 记录正常的已取消/可恢复状态，再停前台服务）——两个入口合并为 `requestUserStop()`，状态不会分叉 | `cb941c0c` | ✅ `OverlayRunActionsTest`（4 例） |
| 单一规则的可见性 | 「是否提供停止」由 `OverlayRunActions.offersStop` 一处判定（有工具在执行，或流仍打开）；刚结束、仍在胶囊上停留的回合绝不显示停止，避免给出一个说谎的按钮 | `cb941c0c` | ✅ 用例覆盖工具运行中/仅流打开/空闲/两者同时 |

✅ 的定义：该分支上 `:app:compileDebugKotlin` + `:app:testDebugUnitTest` 通过（1969 个用例 = 上一项后的 1965 + 4，0 失败），`:app:lintDebug` 无新增问题（复用既有 `bg_service_stop_action` 文案，未新增资源）。**本项只覆盖 Eta 面板五项里的三项「就地展示/可停止/可接管」**：就地展示与点击接管（深链回会话）本仓库原本就有，本轮补的是停止。**未做**：连续追问（需要先把 agent 运行从前台 Activity 解耦成可无头驱动的 seam，目前 `runAgentLoop` 仍是 ChatViewModel 私有）与面板内的屏幕上下文。真机未验证：胶囊停止控件的点击热区与拖拽手势是否冲突、各 ROM 下停止后通知与胶囊的收起时序。

**Phase 4 起步：通知栏与有界历史** — 分支 `codex/eta-phase4-notifications`，基于 `c3645c56`：

| 项 | 内容 | 提交 | 验证 |
|---|---|---|---|
| 通知栏与有界历史 | 此前只能**发**通知（`android-notification`），读不到任何通知，于是「我错过了什么」「哪个 App 弹过我」在已授权通知访问的设备上也答不出来。新增 `notification.recent`（别名 `recent_notifications`，读当前通知栏，按时间倒序、可按包名过滤、上限 20 默认 10）与 `notification.search`（别名 `search_notification_history`，检索授权后记录的历史：7 天保留、最多 1000 条、单字段 4000 字符、关键词/包名过滤、回溯 1–168 小时默认 24、行数 1–50 默认 20） | `019d9e0c` | ✅ `NotificationHistoryPolicyTest`（6 例） |
| 监听与保留策略 | 新增 `NotificationListenerService`（授权前系统根本不绑定该服务）记录投递，并在绑定时补录当前通知，历史不会从空开始；写入时先删过期行再按上限淘汰，任何一条边界都不会被越过；关键词里的 LIKE 通配符转义，`50%` 按字面匹配 | `019d9e0c` | ✅ 上限/转义/空通知用例 |
| 隐私边界 | 通知正文按敏感工具分类（`ToolSensitivePolicy`）：实时回合看得到内容，落库的对话与恢复记录只留占位符；两个工具对 MCP 调用方 local-only；未授权时返回带设置入口的错误而不是空列表；API 26 回退到「已启用监听器」名单（那正是平台绑定依据） | `019d9e0c` | ✅ 分类与权限路径 + 🟡 服务/DB 路径需真机 |

✅ 的定义：该分支上 `:app:compileDebugKotlin` + `:app:testDebugUnitTest` 通过（1975 个用例 = 上一项后的 1969 + 6，0 失败），`:app:lintDebug` 0 error（首轮因 `isNotificationListenerAccessGranted` 需 API 27 而失败，已按 API 级别回退修掉）。真机未验证：授权后监听器是否收到通知、通知栏实时读取、1000 条/7 天淘汰在真实投递量下的行为、各 ROM（MIUI/HyperOS 等）的通知访问授权路径。

| 项 | 内容 | 提交 | 验证 |
|---|---|---|---|
| 会话历史回读 | 压缩会把旧回合换成摘要，早先指令的原话或某次工具结果可能只剩在持久记录里，而此前模型既不能搜也不能翻自己所在的会话。新增 `conversation.history`（别名 `conversation_history`）：带 `query` 时扫最多 1000 条、返回最多 20 条命中（含 message_index 与有界片段）；不带时按 `message_index`/`offset` 翻页，最多 `max_chars`（256–8000，默认 4000），并给出 `next_message_index`/`next_offset` 续读 | `4cc5c5b7` | ✅ `ConversationHistoryPolicyTest`（9 例） |
| 边界与诚实 | 会话不可由调用方指定（id 来自当前回合）；每条记录按自身 parts 渲染（正文、tool_use 名称与截断参数、tool_result 成功标志与截断输出、`[image]`），单条上限 2000 字符；整页未读完时必给续读指针而不是谎报读完；结果里写明持久记录**不含**敏感工具原文与瞬时图片 | `4cc5c5b7` | ✅ 续读/中途续读/搜不到/扫描截断用例 |

✅ 的定义：该分支上 `:app:compileDebugKotlin` + `:app:testDebugUnitTest` 通过（1984 个用例 = 上一项后的 1975 + 9，0 失败）。本项无新资源/清单改动，未跑 lint（工程矩阵里 Android 代码只要 compile+test）。真机未验证：超大会话的翻页耗时、模型在真实压缩后是否真的用它找回旧指令、工具片段截断在长会话里的可读性。

| 项 | 内容 | 提交 | 验证 |
|---|---|---|---|
| 闹钟与计时器 | `android-alarm` 早已能通过系统时钟应用排程，但只有 shell 能碰到它——模型没有可发现的工具 schema，被要求「设个 7 点闹钟」只能靠 `shell_execute` 猜 CLI 参数。新增 `android.alarm.set`（别名 `set_alarm`：本地时间 hour/minute、可选 label 与 repeat_days）、`android.alarm.timer`（别名 `set_timer`：秒数、上限 24 小时）、`android.alarm.open`（别名 `show_alarms`：把时钟应用拉到前台） | `0dbd53f4` | ✅ `AlarmToolPolicyTest`（8 例） |
| 拒绝而非改写 | 校验集中在 `AlarmToolPolicy`：小时不在 0–23、分钟不在 0–59、计时器为 0/负数/超 24 小时、重复规则既不是整周（daily）也不是周一到周五（weekdays）、星期拼写不被 CLI 接受——一律带原因拒绝。自定义星期组合明确拒绝并说明只能去时钟应用设置，因为在这里排程会静默落到错误的日子 | `0dbd53f4` | ✅ 上述用例 |

✅ 的定义：该分支上 `:app:compileDebugKotlin` + `:app:testDebugUnitTest` 通过（1992 个用例 = 上一项后的 1984 + 8，0 失败）。**与 Eta 的刻意差异**：不提供 `list_alarms` / `list_active_timers`——Android 的 Clock API 是「发完即忘」，本仓库又刻意不再保留自己的闹钟记录（T266），做出来的「列表」只可能是打开时钟应用却自称列表；改为 `android.alarm.open`，结果里如实写明它做了什么。真机未验证：各 OEM 时钟应用对 `EXTRA_SKIP_UI` 的实际处理（是否弹确认）、Android 14+ 的精确闹钟授权路径、计时器与闹钟在真实设备上的创建结果。

| 项 | 内容 | 提交 | 验证 |
|---|---|---|---|
| 设备环境 | Eta 的 `get_current_context` 存在，是为了让模型把「明天 7 点」换算成时钟时间、并在动手前知道设备在哪。Minis 的能力散在几个工具里（`android.time` 只给格式化时间，`android.location.get` 会强制定位且可能弹权限），没有任何一个回答「现在是什么状况」，而且 `android.time` 连星期与 UTC 偏移都没有。新增 `android.context`（别名 `get_current_context`）：时间环境（带偏移的 ISO、时区、设备语言的星期 + 英文短名、语言标签、UTC 偏移分钟、DST 标志）、屏幕是否点亮、电量与充电、网络传输类型与是否已验证、无障碍连接时的前台应用、最近一次已知位置 | `491267db` | ✅ `DeviceContextPolicyTest`（5 例，固定时钟与时区） |
| 不弹窗不强定位 | 位置块只读「已启用 provider 的最近已知位置」，且仅在 `ACCESS_FINE/COARSE` 已授权时读；否则返回 `permission_not_granted` 并指向 `android.location.get` 去取真实定位。前台应用读不到时如实报 `accessibility_not_connected` 或 `unknown`，不做猜测 | `491267db` | ✅ 时间环境确定性用例 + 🟡 设备读取需真机 |

✅ 的定义：该分支上 `:app:compileDebugKotlin` + `:app:testDebugUnitTest` 通过（1997 个用例 = 上一项后的 1992 + 5，0 失败），`:app:lintDebug` 0 error（`getLastKnownLocation` 的权限判定通过了 lint 的 MissingPermission 检查路径）。真机未验证：各 ROM 上最近已知位置的可得性、前台应用在无无障碍时的报错路径、电量/网络在飞行模式与省电模式下的读数。

| 项 | 内容 | 提交 | 验证 |
|---|---|---|---|
| 媒体检索 | Eta 的个人上下文工具能按名称搜照片、视频与音频；本仓库的 MediaStore 表面只列照片（视频其实在底层 handler 里支持，但没有工具能触达），也没有任何按名称找文件的办法。新增 `android.media.audio`（别名 `search_audio`）：列出音频并带 content URI、时长与艺术家/专辑，不播放、不读音频字节；`android.media.images` 增加 `media_type`（photo/video）与 `query` 关键词，因此可以先把名字解析成 content URI 再交给 `read_image` | `d70477a2` | ✅ `MediaQueryPolicyTest`（6 例） |
| 过滤与列单位 | 列表查询新增 `--type audio` 与 `--query`：过滤条件三种类型共用 `name LIKE ?`，通配符转义（搜 `50%` 按字面匹配）。音频的 `DATE_ADDED` 是秒、照片/视频的 `DATE_TAKEN` 是毫秒，这个差异留在 handler 内部，不泄漏到工具层 | `d70477a2` | ✅ 上限/转义/类型校验用例 |

✅ 的定义：该分支上 `:app:compileDebugKotlin` + `:app:testDebugUnitTest` 通过（2003 个用例 = 上一项后的 1997 + 6，0 失败）。本项无资源/清单改动，未跑 lint。**未做**：Eta 的 `search_recordings`（ColorOS 录音）与 `search_files`/`search_downloads`（文档与下载记录）——前者是厂商私有目录、后者需要另一套 MediaStore.Files 查询与 MIME 归类，留待确认是否要做。真机未验证：各 ROM 上 MediaStore 音频的可检索范围（部分 ROM 只索引系统媒体库）、`--query` 在中文文件名上的行为、视频列表与 `read_image` 的配合。

| 项 | 内容 | 提交 | 验证 |
|---|---|---|---|
| 文档与录音检索 | 又两项 Eta 的个人上下文检索，都走本仓库既有的 handler 而不是新查询通道：`android.media.files`（别名 `search_files`）列文档——文件名、mime、相对路径、大小与 content URI，关键词同时匹配**文件名或路径**（与 Eta 的可搜索列一致），`media_type=0` 把媒体行挡在文档列表之外；`android.media.audio` 增加 `recordings_only`，即 Eta 的 `search_recordings`：用同一个 relative_path 子句把音频收敛到录音目录 | `12881c5f` | ✅ `MediaQueryPolicyTest`（9 例） |
| 多列过滤 | 多列过滤由 `MediaQueryPolicy.anyColumnFilter` 生成：转义通配符并把绑定参数按列重复——占位符数量必须与 SQLite 调用完全对应，用例把这一点钉住 | `12881c5f` | ✅ 占位符重复与转义用例 |

✅ 的定义：该分支上 `:app:compileDebugKotlin` + `:app:testDebugUnitTest` 通过（2006 个用例 = 上一项后的 2003 + 3，0 失败）。**未做**：`search_downloads`——Eta 读的是 `content://downloads/my_downloads`（仅本应用自己的下载记录，对本 App 近乎恒空）与 `all_downloads`（需要系统权限），两个都不值得做成工具，留待确认；`search_coloros_*`（笔记/录音）与 QQ/微信聊天图片缓存属于厂商私有目录，跨 ROM 不可复现，且会给 Root 增加新的第三方数据读取面，属于要先拍板的范围变更。真机未验证：文档列表在各 ROM 上的可见范围（Android 11+ 可见性、SAF 与 MediaStore.Files 的差别）、录音目录命名差异、大文件量下的查询耗时。

## 三、Phase 5（角色系统）起步 — 分支 `codex/eta-phase5-roleplay`，基于 `f1bb9454`

| 项 | 内容 | 提交 | 验证 |
|---|---|---|---|
| 角色卡模型 | Eta 的角色系统从「卡」开始：一份 Tavern V2/V3 JSON，通常以 Base64 藏在 PNG 的 `tEXt` 块里。本仓库此前没有任何角色系统，所以先落基础。`CharacterCard` 保留解析出来的整棵树（本版未实现的字段在导入导出间原样存活），并暴露提示词需要的那些字段：名称、描述、性格、场景、开场白与备选开场白、示例对话、系统提示与历史后指令、标签、extensions、character_book、深度备注 | `a5926cf9` | ✅ `CharacterCardCodecTest`（10 例） |
| 编解码与校验 | `CharacterCardCodec`：16 MiB 上限、严格 UTF-8、只承认 `chara_card_v2`/`chara_card_v3`，旧版 V1 归一化成 V2 形状且顶层镜像字段同步；世界书在落库前校验（scan_depth/token_budget 为非负整数、recursive_scanning 为布尔、条目必须是对象且 keys/content/开关/顺序类型正确）。**与 Eta 的唯一差异**：显式 JSON null 视为缺席——真实卡片常把空字段写成 null | `a5926cf9` | ✅ 含旧版归一化、null 容忍、规范拒绝、世界书拒绝用例 |
| PNG 承载 | `CharacterCardPng`：读之前逐块校验（长度、CRC、IHDR 几何、颜色类型与位深、必须有 IDAT、IEND 必须为空），`ccv3` 优先于 `chara`，V3 损坏时报错而**不**静默回退到旧副本；写入时先剔除旧卡块，再在 IEND 前插入新的 V2 与 V3 两份，32 MiB 上限 | `a5926cf9` | ✅ `CharacterCardPngTest`（9 例，自建分块 PNG） |

✅ 的定义：该分支上 `:app:compileDebugKotlin` + `:app:testDebugUnitTest` 通过（2025 个用例 = Phase 4 分支的 2006 + 19，0 失败）。**未做**：世界书触发/递归注入与草稿编辑、宏展开（`{{char}}`/`{{user}}`）、兼容性警告（未支持宏/HTML/正则扩展）、角色存储与界面、导入导出入口。真机未验证：真实酒馆角色卡（含 V3 与 character_book）的往返、大体量卡片的解码耗时、PNG 写出后在各图片查看器中的正常显示。

| 项 | 内容 | 提交 | 验证 |
|---|---|---|---|
| 世界书触发 | 卡里的 `character_book` 此前没人读。现在按 Eta 的规则落成纯逻辑：带未支持条件的条目**报告并跳过**，绝不降级成无条件触发（正则键或概率门若变成「总是」，就会注入作者没要的设定）；扫描窗口是最近 `scan_depth` 条消息；`selective` 条目还必须命中一个 secondary key；递归扫描可以让一个已命中条目暴露下一个（循环只在命中集合变大时继续，因此有界）；token 预算按「候选加入后重新测量整个投影」来判定，超大条目被丢弃而不是撑爆窗口 | `eabd266e` | ✅ `CharacterWorldbookTest`（11 例） |
| 插入位置与能力判定 | 位置 0 落在角色块之前、1 之后，**没有 position 的条目落在角色块之后**（与 Eta 同一默认）；`CharacterWorldbookSupport` 逐项列出本版不实现的条件（正则键、装饰器、特殊插入位、selectiveLogic、概率、分组、时序、扩展匹配开关、生成类型、脚本自动化），同一判定同时用于导入时的能力说明与每轮投影，所以卡片不会「检查时一个样、使用时另一个样」 | `eabd266e` | ✅ 条件清单与位置/顺序用例 |

✅ 的定义：该分支上 `:app:compileDebugKotlin` + `:app:testDebugUnitTest` 通过（2036 个用例 = 上一项后的 2025 + 11，0 失败）。真机未验证：与真实角色卡的配合（含大量条目时的扫描耗时）、递归扫描在真实卡片上的层数、预算裁剪在长会话中的表现。

| 项 | 内容 | 提交 | 验证 |
|---|---|---|---|
| 宏展开 | `CharacterMacros`：`{{char}}`/`{{user}}`/`{{bot}}` 与 `<USER>`/`<CHAR>`/`<BOT>` 两种拼写、卡片自身字段、日期/时间/星期、`newline`/`noop`。只有已知宏会被展开——未知宏原样保留；`{{// …}}` 是注释并消失；自引用宏在第一次重复处停下而不是死循环；嵌套有深度上限；整段展开有 2 MiB 上限，病态卡片无法无限分配。时钟与 locale 作为参数传入，所以日期/时间/星期可测，且星期跟随设备语言 | `e44138c0` | ✅ `CharacterMacrosTest`（10 例） |
| 兼容性说明 | `CharacterCardCompatibility` 列出「卡片要、本版不做」的事项：未实现宏、HTML/脚本标记、正则与脚本扩展、不可用的深度备注、使用未支持条件的世界书条目（带计数）、附带资源、群聊专用开场白。**数据在任何情况下都保留**——这些警告存在是为了把差异讲清楚，而不是静默执行或丢弃。Eta 为自家界面返回中文散文，本移植返回稳定 code + 英文 detail，便于界面后续本地化 | `e44138c0` | ✅ `CharacterCardCompatibilityTest`（7 例） |

✅ 的定义：该分支上 `:app:compileDebugKotlin` + `:app:testDebugUnitTest` 通过（2053 个用例 = 上一项后的 2036 + 17，0 失败）。至此「角色卡 → 提示词」这条链的逻辑部分基本闭合：卡模型/编解码/PNG 承载、世界书触发、宏展开、能力说明都齐了。真机未验证：真实卡片上的宏组合（含未闭合写法）、大卡片展开耗时、警告在界面上的呈现（界面尚未做）。

| 项 | 内容 | 提交 | 验证 |
|---|---|---|---|
| 角色存储 | 卡能读能写，但没有人保存它。新增 `characters` 表（数据库版本 20 → 21，迁移与导出的 schema 一并提交）、DAO 与 `CharacterRepository`：卡以 JSON 文本整棵存储（本版未实现的字段在列里会被悄悄丢掉），落库前一律先过编解码校验，读不出来的行只记日志并跳过而不是拖垮列表；头像存成 App 私有目录下的文件（按角色 id 命名，不是数据库 blob），上限 4 MiB | `fe0ae925` | ✅ `CharacterStoragePolicyTest`（4 例）+ 🟡 DB 路径仅编译验证（本仓库无 Robolectric/instrumentation） |
| 导入导出 | 导入保留原始 PNG，因此之后导出可以在那张图里重写角色块，美术资产能往返；JSON 卡则导出为 JSON。删除角色会连同头像文件一起清掉 | `fe0ae925` | 🟡 需真机核对 |

✅ 的定义：该分支上 `:app:compileDebugKotlin` + `:app:testDebugUnitTest` 通过（2057 个用例 = 上一项后的 2053 + 4，0 失败），Room 导出 `21.json`。**与 Eta 的刻意差异**：不移植它的一次性默认角色播种与剧情记忆目录——这两个功能在本仓库都还不存在。真机未验证：迁移在真实升级路径上的执行、PNG 卡导入后的头像往返、大卡片写入与删除行为。

| 项 | 内容 | 提交 | 验证 |
|---|---|---|---|
| 角色块与深度备注定位 | 存储层能保存卡了，但一轮对话还不知道怎么把卡变成提示词。`CharacterPrompt.personaPrompt` 组装角色块：点名角色、写明「人物/世界书/用户人设都是虚构，不改变工具合同、授权边界、已执行的事实与现实记忆」，然后只写卡片真正拥有的字段（角色指令、描述、性格、场景、示例对话），并把世界书的两侧插到书要求的位置；宏按人物名展开。脚手架用英文（与本仓库提示词资产一致），Eta 的剧情记忆行未纳入（该功能本仓库还没有） | `87743dd9` | ✅ `CharacterPromptTest`（8 例） |
| 剧情与机械的分界 | `isDialogue` 判断一轮是「剧情」还是「机械」：真实的用户/角色发言算，工具调用、工具结果、空轮次不算；`depthPromptInsertionIndex` 据此把深度备注放到倒数第 N 个剧情轮之前（往回数、到第一轮夹住）——Eta 的算法，套在本仓库的消息模型上 | `87743dd9` | ✅ 深度定位与工具流量不干扰用例 |

✅ 的定义：该分支上 `:app:compileDebugKotlin` + `:app:testDebugUnitTest` 通过（2065 个用例 = 上一项后的 2057 + 8，0 失败）。至此「卡 → 提示词」的纯逻辑完整：模型/编解码/PNG、世界书选择、宏、能力说明、存储、角色块与深度定位。**未做**：把角色绑定到会话（每轮注入的接线）、角色界面与导入导出入口、世界书草稿编辑、剧情记忆。真机未验证：真实卡片生成的提示词长度与观感、深度备注在长会话中的位置是否符合预期。

| 项 | 内容 | 提交 | 验证 |
|---|---|---|---|
| 会话角色绑定 | 卡片能存、角色块能拼，但两者没接上会话。新增 `sessions.roleplay_json` 列（数据库版本 21 → 22，迁移与导出 schema 一并提交）、绑定载荷的编解码，以及 `CharacterRepository` 的 bind/unbind/读取/解析。载荷里带**卡片快照**——这是这个类型的要点：之后角色被编辑或删除，会话仍保留它实际写作时的设定，而不是悄悄退化成普通聊天 | `3073ecda` | ✅ `CharacterBindingTest`（4 例）+ 🟡 DB 路径仅编译验证 |
| 解析顺序与降级 | 解析按 Eta 的顺序：角色还在时**用现存卡片**（因此编辑会作用于已绑定它的会话），角色已删则回退快照；载荷缺失、空白、非 JSON、缺快照或缺名字一律视为「无绑定」，损坏的行退化成普通聊天而不是空角色 | `3073ecda` | ✅ 往返、默认人名、不可用载荷用例 |

✅ 的定义：该分支上 `:app:compileDebugKotlin` + `:app:testDebugUnitTest` 通过（2069 个用例 = 上一项后的 2065 + 4，0 失败），Room 导出 `22.json`。**未做**：每轮把角色块与世界书投影真正注入提示词（下一步，动的是 agent loop 而不是又一个 codec）、角色界面与导入导出入口、世界书草稿编辑、剧情记忆。真机未验证：迁移在真实升级路径上的执行、绑定后每轮注入的实际观感。

| 项 | 内容 | 提交 | 验证 |
|---|---|---|---|
| 每轮注入 | 绑定存在但没人读它，所以绑了角色的会话仍以普通助手身份回答。现在一次 run 解析一次绑定（角色还在用现存卡片、已删回退快照），provider 调用据此组装本轮：角色块进系统提示词，世界书与深度备注**只进这一次请求** | `ae28def1` | ✅ `RoleplayTurnProjectionTest`（8 例） |
| 投影规则 | `RoleplayTurnProjection`：世界书按本请求的对话文本解析、在窗口预算内选取，两侧落到书要求的位置；深度备注按 role 决定去处——user/assistant 插成消息（位置用 Eta 的算术），system 则并入角色块（**适配点**：本仓库的 `LLMMessage` 没有 system 角色，系统提示词是独立参数）；预算直接用上下文窗口（**适配点**：本仓库移植的 `AgentContextBudget` 刻意不含压缩触发阈值，这里再引入一个阈值就会多出第二套判据） | `ae28def1` | ✅ 命中/未命中、深度两种角色、宏展开、小窗口丢弃用例 |

✅ 的定义：该分支上 `:app:compileDebugKotlin` + `:app:testDebugUnitTest` 通过（2077 个用例 = 上一项后的 2069 + 8，0 失败）。**投影不落盘**：transcript、恢复日志与下一轮读回的历史都不变（与 Eta 对世界书/深度备注的保证一致）。**未做**：角色界面与导入导出入口（因此目前还没有 UI 能把角色绑到会话上——绑定接口已就绪）、世界书草稿编辑、剧情记忆。真机未验证：真实卡片注入后的提示词长度与观感、世界书在小窗口下的裁剪行为。

| 项 | 内容 | 提交 | 验证 |
|---|---|---|---|
| 角色库界面 | 卡能通过仓库导入、也能绑定到会话，但界面两者都够不着：既没法添加卡，也没法看有哪些、更没法把卡再取出去。设置 → 角色：列出已存卡片（头像缩略图 + 时间戳），从系统文件选择器导入（含卡的 PNG 或 JSON 卡，由读取端判定），按原样导出/分享（有美术资产导出 PNG、没有则 JSON），删除前弹确认并写明「删除不会影响什么」——已绑定该角色的会话会继续用它们保存的卡片快照工作 | `c2fde382` | ✅ 编译 + lint；界面本身无单测（🟡 需真机观感） |
| 导入边界与报错 | 导入在进入内存前就有上限（32 MiB，与 PNG 读取端同一上限），且失败时抛出的是编解码器自己的原因而不是笼统提示——「这不是一张卡」会说明是哪一部分不对。新增字符串补齐 8 个 locale，lint 未新增任何针对新界面/字符串/导航项的发现 | `c2fde382` | ✅ `:app:lintDebug` 0 error |

✅ 的定义：该分支上 `:app:compileDebugKotlin` + `:app:testDebugUnitTest` 通过（2077 个用例，0 失败），`:app:lintDebug` 0 error。**未做**：把角色绑到会话的界面入口（仓库接口已就绪，缺的是选择器与入口）、世界书草稿编辑、剧情记忆。真机未验证：文件选择器在各 ROM 上的行为、PNG/JSON 卡片导入的端到端、缩略图解码在大卡片上的开销、分享面板收到文件的行为。

| 项 | 内容 | 提交 | 验证 |
|---|---|---|---|
| 会话侧选择角色 | 库能导入卡、仓库能绑定，但会话自己的界面够不到绑定，所以一段对话实际上还成不了角色扮演。现在会话长按菜单多一项：打开角色选择器——列出已存卡片、标出当前绑定的那张，已绑定时还能回到普通聊天。选择走 `CharacterRepository`，卡片快照随绑定一起保存，因此之后编辑或删除角色都不会在用户背后把会话变成普通聊天；只有真正选择才会写入，取消不写任何东西 | `76c7f9bc` | ✅ 编译 + lint；界面无单测（🟡 需真机观感） |

✅ 的定义：该分支上 `:app:compileDebugKotlin` + `:app:testDebugUnitTest` 通过（2077 个用例，0 失败），`:app:lintDebug` 0 error（新增 3 个字符串已补齐 8 个 locale，无新增发现）。至此 Phase 5 的角色链**端到端可用**：导入卡片 → 存库 → 绑到会话 → 每轮注入角色块与世界书 → 导出/解除/删除。**未做**：世界书草稿编辑（逐条编辑/新增/禁用）、剧情记忆（跨会话的角色经历与关系）。真机未验证：选择器与长按菜单的交互、绑定后对话的实际观感（角色语气/称呼/世界书命中）、导出与解除绑定的端到端。

| 项 | 内容 | 提交 | 验证 |
|---|---|---|---|
| 世界书草稿读写 | 卡能整张读写，但世界书不是整张编辑的东西：主人只改一条，期望其余每一个字节都活着。`CharacterWorldbookDraftCodec` 把 `character_book` 读成可编辑草稿再写回，规则照 Eta：**只重写真正变了的字段**，编辑器不认识的东西（正则扩展、概率门、厂商键、条目里的额外字段）原样穿过往返；条目的插入位置在现实里存在于两处（`position` 字符串与 `extensions.position` 数字），草稿读存在的那个、写回同一种形状，而不是把本来合法的卡「规范化」；清空设置是**删键**而不是写 0——那才是卡读取端理解的「未设置」 | `f421eccc` | ✅ `CharacterWorldbookDraftTest`（8 例） |

✅ 的定义：该分支上 `:app:compileDebugKotlin` + `:app:testDebugUnitTest` 通过（2085 个用例 = 上一项后的 2077 + 8，0 失败）。**未做**：世界书编辑界面本身——它需要一张「角色详情」屏，而角色库目前只有列表；这是下一步，本轮的 codec 就是它要调用的东西。真机未验证：真实卡片草稿往返后视觉字段的保真度、编辑后绑定会话的下一轮是否按新世界书触发。

| 项 | 内容 | 提交 | 验证 |
|---|---|---|---|
| 世界书编辑界面 | 草稿 codec 有了却没人调用：库只能列出卡片，进不去单张卡，于是导入的世界书只能被 agent 读、主人改不了。现在点开角色进入详情页：世界书的扫描深度、token 预算、递归开关，以及条目列表——每条有启用开关、点开编辑（名称/关键词/正文/插入侧/常驻）、删除，另有新增；改动累积在草稿里，**只有 Save 才写**，经 `CharacterWorldbookDraftCodec`，因此放弃的编辑什么也不改、保存的编辑只碰编辑器认识的字段（它不认识的部分原样穿过，见上一项） | `783e507e` | ✅ 编译 + lint；界面无单测（🟡 需真机观感） |

✅ 的定义：该分支上 `:app:compileDebugKotlin` + `:app:testDebugUnitTest` 通过（2085 个用例，0 失败），`:app:lintDebug` 0 error（新增 15 个字符串补齐 8 个 locale，无新增发现）。至此 Phase 5 只剩**剧情记忆**一项。真机未验证：详情页在长世界书（数百条）下的滚动与编辑手感、保存后已绑定会话下一轮是否按新世界书触发。

## 四、待办阶段（顺序与规格见 `docs/analysis/eta-port-program.md`）

| 阶段 | 内容 | 来源 |
|---|---|---|
| Phase 2 底层 AI | 服务端 `web_search` 开关、工具能力投影与终态门（请求头与请求体合并、引用格式化、Responses opaque output 回放、UI 坐标空间契约、`read_image` 直读相册已在 `codex/eta-phase2-provider-passthrough` 落地；屏幕观察的其余合同 Minis 侧本就更强，未再移植） | Eta `agent/model/*` |
| Phase 3 数字助手 | 助手浮层面板的剩余部分：连续追问与面板内屏幕上下文（需要先把 agent 运行解耦成可无头驱动的 seam）；就地展示/可停止/可接管已在 `codex/eta-phase3-skills-tools` 落地，Skills 暴露给模型、GUI 动作补齐、会话级编辑的 Markdown 导出同样已落地，复制/编辑/删除/重新生成本仓库原本就有 | Eta `agent/voice`、`agent/overlay`、`agent/tool` |
| Phase 4 个人上下文 | 健康摘要、QQ/微信聊天图片、下载记录检索（通知历史、会话历史、闹钟计时器、设备环境、照片/视频/音频/文档检索已在 `codex/eta-phase4-notifications` 落地；后两项涉及厂商私有目录与系统权限，待拍板） | Eta `agent/tool/AgentPersonal*Tools.kt`、`agent/device/*` |
| Phase 5 角色系统 | 剧情记忆、角色草稿编辑、角色界面与导入导出入口（角色卡模型/编解码/PNG 承载、世界书触发、宏展开、能力说明、存储层已在 `codex/eta-phase5-roleplay` 落地） | Eta `agent/roleplay/*` |
| Phase 6 厂商入口接管 | libxposed 接入 + 电源键、小布、超级小爱、一圈即搜、Google 解锁 + 无障碍保活 | Eta `hook/*`、`ModuleMain.kt` |

## 五、明确排除

| 项 | 理由 |
|---|---|
| PRoot / Alpine / 多发行版安装器（不引入） | 与「单一 Direct Ubuntu 24.04 chroot」合同冲突 |
| systemizer（把 Google App 装成系统应用） | 扩大 Root 面，超出「Root 只做受控基础设施」边界 |
| 厂商私有内部类实现（HyperOS/ColorOS 内部方法） | 跨版本不稳定，且无法在宿主复现验证 |
| 第二套跨进程 runtime 协议 / 终态 outbox | 单进程应用以 Room + ViewModel 为真源，重复实现会造成两套状态源 |
| 在线商店类分发面 | 产品定位与服务端依赖不在本仓库范围 |

## 六、未验证清单（不得据此声称设备结论）

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
