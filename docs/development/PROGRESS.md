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

✅ 的定义：该分支上 `:app:compileDebugKotlin` + `:app:testDebugUnitTest` 通过（1992 个用例 = 上一项后的 1984 + 8，0 失败）。**与 Eta 的刻意差异**：不提供 `list_alarms` / `list_active_timers`——Android 的 Clock API 是「发完即忘」，本仓库又刻意不再保留自己的闹钟记录（T266），做出来的「列表」只可能是打开时钟应用却自称列表；改为 `android.alarm.open`，结果里如实写明它做了什么。**（更正，2026-09-18：上游其实是读时钟应用自己的数据库来列的，本仓库随后按上游落地了 `android.alarm.list`/`android.alarm.timers`，见「Phase 4 补片（三）」；上面这段「不提供列表」的结论作废。）** 真机未验证：各 OEM 时钟应用对 `EXTRA_SKIP_UI` 的实际处理（是否弹确认）、Android 14+ 的精确闹钟授权路径、计时器与闹钟在真实设备上的创建结果。

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

| 项 | 内容 | 提交 | 验证 |
|---|---|---|---|
| 剧情记忆 | 角色跨会话记住什么。按 Eta 的边界：按角色存、与现实 MEMORY.md 分离、用 SHA-256 revision 标识状态、revision 过期就拒绝写入而不是盲目合并。`CharacterMemoryDocument` 承载规则（与文件无关，因此可测）：带 query 的读返回命中行与行号、不带 query 则从某行分页并如实报告后面还有没有；追加、整段替换（用于就地修正过期事实）、清空、以及 64k 上限——超过就必须编辑而不是继续追加。`CharacterMemoryRepository` 是它外面的文件处理：每个角色一个 Markdown 文件放在 App 私有存储，经临时文件 + rename 写入，角色删除时一并删除 | `3bc1d2b3` | ✅ `CharacterMemoryDocumentTest`（12 例） |
| 工具与注入 | 两个工具读写「本会话绑定的角色」的记忆，模型点不了别的角色；每次写入都带 revision。角色块里也带上记忆：revision、按 `MemoryInjectionBudget`（与真实记忆同一套预算，而不是为角色扮演再造一个阈值）裁剪后的核心文本、以及读取其余内容的入口 | `3bc1d2b3` | ✅ `CharacterMemoryPromptTest`（5 例） |

✅ 的定义：该分支上 `:app:compileDebugKotlin` + `:app:testDebugUnitTest` 通过（2102 个用例 = 上一项后的 2085 + 17，0 失败）。失败路径：未绑定角色 → `NO_CHARACTER_BOUND`；缺 revision / 未知 mode / revision 过期 → 拒绝而不是合并；行号越界 → 拒绝；超上限 → 带原因拒绝。剧情记忆归入敏感工具（transcript 只留占位符），且对 MCP 调用方 local-only。**Phase 5（角色系统）至此功能完整**：卡模型/编解码/PNG、世界书选择与编辑、宏、能力说明、存储、会话绑定、每轮注入、剧情记忆。真机未验证：真实长对话下记忆的增长与裁剪、并发两段会话写同一角色记忆时的 revision 冲突、记忆注入后的实际观感。

## 四、Phase 6（厂商入口接管）起步 — 分支 `codex/eta-phase6-xposed`，基于 `8b9e0900`

| 项 | 内容 | 提交 | 验证 |
|---|---|---|---|
| Hook 台账与目标表 | Phase 6 先落不需要框架的那一半：模块怎么汇报自己的 hook，以及在哪些进程里才该存在。`HookInstallStatus/Entry/Report/Journal` 照 Eta 原样——每个 hook 四态（installed/missing/failed/skipped）、每组计数与一行摘要；`capture()` 会把安装期异常先记成 FAILED 再抛出，因此半装完的组不可能汇报成功，而厂商 ROM 集成只有在「装上了 / 这版 ROM 没这个目标 / 它抛了」三者可区分时才可诊断。`ModuleTargets` 是 Eta 的单一目标表（自身包 + system_server + SystemUI + 路线图点名的助手/桌面/厂商入口），外加模块在一切之前要跑的两个判定：只在自身包与声明目标里保留生命周期回调（否则设备每次启动应用都要为空进程付费），`isProcessOf` 匹配包的子进程但不误配同前缀的包 | `6ed1918c` | ✅ `HookInstallJournalTest`（4 例）+ `ModuleTargetsTest`（3 例） |

✅ 的定义：该分支上 `:app:compileDebugKotlin` + `:app:testDebugUnitTest` 通过（2109 个用例 = Phase 5 分支的 2102 + 7，0 失败）。

| 项 | 内容 | 提交 | 验证 |
|---|---|---|---|
| 作为 LSPosed 模块加载 | 台账与目标表有了，但没有任何东西声明这个模块，LSPosed 无从加载。本轮补上：`compileOnly` 的 libxposed API（102.0.0，与 Eta 同一固定版本）、`META-INF/xposed/` 声明（`module.prop` / `java_init.list` / `scope.list`）与框架实例化的入口类。`MinisXposedModule` 保留 Eta 的生命周期：先记录被加载进哪个进程，在任何与模块无关的进程里立刻 `detach()`，system_server 走自己的入口，包目标只在该包**自己的进程**里安装（从错误的进程装 hook 等于静默什么都没打）。派发问 `HookGroupRegistry` 而不是写一串厂商分支——本仓库的厂商支持一片一片长；注册表为空时模块照常加载、不碍事，并在日志里明说该目标还没有可安装的组 | `bfcea758` | ✅ 编译 + `assembleDebug` 后核对 APK 内含 `META-INF/xposed/` 三个文件且 `module.prop` 内容正确 + `HookGroupRegistryTest`（3 例） |

✅ 的定义：该分支上 `:app:compileDebugKotlin` + `:app:testDebugUnitTest` 通过（2112 个用例 = 上一项后的 2109 + 3，0 失败），`:app:assembleDebug` 通过并**在产出的 APK 里核对到 `META-INF/xposed/{module.prop,java_init.list,scope.list}`**（内容与预期一致，说明 `resources.merges` 规则有效、声明不会被 release 打包裁掉），libxposed 工件已从 Maven Central 解析成功（Gradle 缓存中可见）。`build.gradle` 用 `compileOnly`，因此 App 不会自带一份可能与已装管理器版本漂移的框架副本。**没有任何设备结论**：LSPosed 是否加载该模块、hook 是否生效均未验证——目前还没有注册任何 hook 组。

| 项 | 内容 | 提交 | 验证 |
|---|---|---|---|
| Hook 安装骨架 | 模块能加载了，但还没有任何办法登记目标和汇报结果。本轮补上 Eta 的 `HookRegistrar`/`HookSupport`/`ModuleLogger`：注册器只管注册与记账（找目标仍是功能组的活），每条路径都写台账——installed、这版 ROM 没有（missing）、抛了（failed，带异常类名）、跳过（skipped）；重复注册被拒绝而不是把两个 hook 叠到同一个方法上；`install()` 会记下组级异常但**保留已注册的部分**（半个有用的组好过没有，且报告会说明缺哪块）；框架自身的失败以 Error 形式出现，刻意不吞。`HookSupport` 是各组共用的反射习惯：查找沿父类链、ROM 上不存在的类/方法返回 null（最终记进 MISSING）、`LinkageError` 当作「这里没有」、绝不把异常抛进别人的进程；另附各组需要的便利函数（字段读取、无参调用、组件→包名、包是否安装、Activity 是否可解析）。`HookLogger` 是日志缝：入口类持有 tag 与 sink，组只写行（自动带组名前缀），消息是 lambda 所以热路径不会构造没人打印的字符串 | `b1b7e86d` | ✅ `HookSupportTest`（6 例） |

✅ 的定义：该分支上 `:app:compileDebugKotlin` + `:app:testDebugUnitTest` 通过（2118 个用例 = 上一项后的 2112 + 6，0 失败）。**没有任何设备结论**：真正跑这套骨架的是 hook 组，而目前还没有注册任何组。

| 项 | 内容 | 提交 | 验证 |
|---|---|---|---|
| 模块开关缝 | Eta 的每一组 hook 都要问一个用户开关，而开关此前没有落脚处：hook 跑在 system_server 或厂商应用进程里，读不到 App 私有设置。`ModulePrefs`（源自 Eta `config/Prefs.kt`）：框架会把模块设置的只读视图交给每个被注入的进程，入口类在加载时接上，组在拦截时用开关——所以切换开关在下一次拦截就生效，不必重启。两条规则比管道更重要：**每个开关有自己的默认值**，且「会接管按键或助手」的开关一律默认关，于是「装了但没配」的模块行为等同没装；手势条搜索路由保持默认开（把该手势接到系统自己的搜索就是这个功能本身）。设置读取失败、或该进程根本没拿到设置时，回落到默认值而不是猜——并且有日志，所以「开关没反应」有可见原因 | `ad620d73` | ✅ `ModulePrefsTest`（5 例） |

✅ 的定义：该分支上 `:app:compileDebugKotlin` + `:app:testDebugUnitTest` 通过（2123 个用例 = 上一项后的 2118 + 5，0 失败）。缝做成一方法的 reader（而不是直接吃 `SharedPreferences`），默认值与回落因此能在无 Android 运行时的情况下测到；`attachSharedPreferences` 把框架对象适配到它上面。

| 项 | 内容 | 提交 | 验证 |
|---|---|---|---|
| 第一组真 hook：HyperOS 手势条识屏 | 骨架此前无组可装。本轮落 Eta 里最小且完整的一组：HyperOS 把手势条长按交给自家语音助手，这组把**那一个请求**改接到系统自己的 contextual search。`HyperOsScreenSearchHooks` 挂在小米 `VoiceService.onStartCommand` 上，保留 Eta 对目标的谨慎：校验签名（不只是名字）、请求必须是「导航长按的识屏」（四个 intent 字段全对——这些 ROM 上同一个 assist action 承载所有助手请求）、开关必须是开、系统 contextual search 入口必须真的可用；任何一条不满足就**回落原逻辑**而不是吞掉手势，收尾用 `stopSelfResult` 只在本轮仍是最新启动时才停服务，避免丢掉中途到达的请求 | `cff4a171` | ✅ 编译 + `ScreenSearchRequestTest`（5 例）+ `LogThrottleTest`（4 例） |
| 触发与日志 | `CircleToSearchInvoker` 走 binder 直连系统服务（而不是重放 OEM 识别链），先查可用性（Google App 已装且暴露入口）、缓存每次反射结果、绝不把异常抛进别人的进程；`HookLogger` 补上节流变体给「每次手势都会走」的路径；`HookGroups` 持有「组→目标」接线，入口类只谈框架 | `cff4a171` | ✅ 节流窗口与逐键独立用例 |

✅ 的定义：该分支上 `:app:compileDebugKotlin` + `:app:testDebugUnitTest` 通过（2132 个用例 = 上一项后的 2123 + 9，0 失败）。**没有任何设备结论**：特定 HyperOS 版本是否暴露这个 VoiceService、真机手势是否触发接管、binder 调用是否落地，全部未验证——ROM 上没有目标时台账会记 MISSING 并保留原行为。真机判据：logcat 里 `[HyperOsScreenSearch]` 的台账行 + `META-INF/xposed` 声明在 APK 内（已核对）。

**Phase 6 第二组：HyperOS 电源键长按** — 同一分支 `codex/eta-phase6-xposed`：

| 项 | 内容 | 提交 | 验证 |
|---|---|---|---|
| 第二组真 hook：HyperOS 电源键长按 | 电源键长按在 HyperOS 上是一次快捷分发（`ShortCutActionsUtils.triggerFunction`），这组把它接到用户选定的助手。两个 arity 都 hook（ROM 在两个签名之间搬过），并且只认领「function **与** source 同时是助手长按」的那一次调用——同一个分发器还承载电源菜单、SOS 与其它所有快捷方式，只看 function 会把它们一起劫持。分发器的 context 从它自己的字段取；这一版 ROM 没有分发器记 SKIPPED 而不是 FAILED；任何解析不了的路径一律回落原逻辑 | `8474dd88` | ✅ `HyperOsPowerPolicyTest`（2 例） |
| 助手启动桥与三态开关 | `AssistantLaunch` 是 Eta 用 `AssistantManager` 搭的桥，按本仓库已有的声明适配：助手 Activity 本来就响应 `ACTION_ASSIST`/`ACTION_VOICE_ASSIST`，VoiceInteractionService 也注册了助手角色，所以直接拉起这个已声明的入口，不再重建 Eta 的 voice-interaction 绑定与修复机制。「打开哪个助手」做成可测的值：OEM 目标映射为 null（＝不接管），调用方据此回落。`ModulePrefs` 增加字符串设置（三选一而不是开关），同样失败安全：读不到或值不认识保持 OEM 助手；目标包没装就记节流警告并回落——绝不为了换一次没发生的启动而吞掉手势 | `8474dd88` | ✅ `AssistantLaunchTest`（6 例）+ `ModulePrefsTest` 扩充（5→7 例） |

✅ 的定义：该分支上 `:app:compileDebugKotlin` + `:app:testDebugUnitTest` 通过（2142 个用例 = 上一项后的 2132 + 10，0 失败）。**没有任何设备结论**：特定 HyperOS 版本是否暴露这个分发器、电源键快捷方式是否真的走到它、拉起的 intent 是否打开助手，全部未验证——ROM 上没有目标时台账记 MISSING/SKIPPED 并保留原行为。真机判据：logcat 里 `HyperOsPower` 前缀的台账行。

**Phase 6 第四组：Google 机型档案与资格** — 同一分支 `codex/eta-phase6-xposed`：

| 项 | 内容 | 提交 | 验证 |
|---|---|---|---|
| 第四组真 hook：让 Google App 认这台设备 | Google 的门禁是靠问平台问题来决定的——设备档案（`Build.MANUFACTURER/BRAND/MODEL/PRODUCT/DEVICE`）、`ro.opa.eligible_device` 属性、以及 `hasSystemFeature` 的 `GOOGLE_BUILD`/`GOOGLE_EXPERIENCE`；答案说「不是这台设备」时，它就把助手的那套屏幕能力（一圈即搜）拒掉。这组在模块进入的进程里把三处答案一起补齐：档案写进静态字段（先反射，平台拒绝时用 `putObjectVolatile` 就地写静态字段，因此已经读过该字段的代码看到的也是新值），属性与 feature 走 hook。Eta 的理由是这三件事同属「让 Google App 认为本机具备资格」，因此不做开关、进到哪个进程就在哪里生效——本仓库保持原样 | `49aa7ea3` | ✅ `GoogleSpoofProfileTest`（3 例） |
| 档案集中一处 | 所有名字与取值集中在 `GoogleSpoofProfile`：属性键、两个 feature、五个静态字段（Samsung SM-S928B / e3s，Eta 用来过门禁的那套身份）。hook 只做一行读数，因为这类表最容易出的错是名字写错——反射找不到就悄悄留着真值还报成功，所以字段名、大小写与顺序都被用例钉住 | `49aa7ea3` | ✅ 上述用例（属性/feature 的精确匹配与五个字段名） |
| 台账补「非 hook 变更」 | 前三组往台账里写的都是 hook；这组有一半是改静态字段，没有可拦截的调用。`HookRegistrar` 因此补 `applied()` 与 `failed()`：写得成就记一条 INSTALLED（带写入的值），写不成记 FAILED，字段在这版 ROM 上没有记 MISSING——「模块在这个进程里改过东西」不再只存在于日志行里 | `49aa7ea3` | ✅ `HookInstallJournalTest` 补 1 例（INSTALLED 行带 detail） |

✅ 的定义：该分支上 `:app:compileDebugKotlin` + `:app:testDebugUnitTest` 通过（2152 个用例 = 上一项后的 2148 + 4，0 失败）。**没有任何设备结论**：这一版 Google App 是否真的拿这些答案当门禁、写入的档案是否在它读取之前生效、`sun.misc.Unsafe` 路径在目标 Android 版本上是否被允许，全部未验证——补齐失败时台账逐条记 MISSING/FAILED 并保留平台真值。真机判据：logcat 里 `GoogleEligibility` 前缀的台账行，以及 Google 进程内 `Build.MODEL` 是否变成 `SM-S928B`。

**Phase 6 第五组：系统 contextual search 的可用性（一圈即搜的系统侧）** — 同一分支 `codex/eta-phase6-xposed`：

| 项 | 内容 | 提交 | 验证 |
|---|---|---|---|
| 第五组真 hook：把系统的 contextual search 叫起来 | 没带 contextual search 配置的 ROM 根本不启动这个系统服务，导航条也就永远调不动它——用户手里的手势最后什么也不做。这组在 system_server 里补三处：启动门（`SystemServer.deviceHasConfigString` 对 `config_defaultContextualSearchPackageName` 那个资源回答「有」）、兜底启动（`startOtherServices` 走完之后服务仍不在就自己 `startService(Class)` 拉起来；该方法先 deopt，否则被修的启动路径本身可能已经是编译好的副本，hook 根本到不了）、服务侧两个答案（`getContextualSearchPackageName()` 答 Google 包；`enforcePermission(String)` 只对 `startContextualSearch` 且调用方在名单内的一类放行）。框架的 invoker 是隐藏方法的首选路径，被拒时回落普通反射 | `6ff28d21` | ✅ `ContextualSearchCallerPolicyTest`（5 例） |
| 放行名单必须窄 | `ContextualSearchCallerPolicy` 是纯逻辑：SystemUI（导航条）与 ColorOS 的手势服务直接放行；小米桌面/全球版桌面/超级小爱要**同时**满足「手势接管开关为开」与「该包确实是系统包」——只按包名匹配等于给同名的侧载应用开后门。其余调用方一律回落平台自己的权限检查。调用方按 UID 取整包组（oneway AIDL 拿不到 PID），共享 UID 就是真实的权限边界 | `6ff28d21` | ✅ 上述用例（侧载同名拒绝、开关关闭拒绝、第三方拒绝、空包组拒绝） |

✅ 的定义：该分支上 `:app:compileDebugKotlin` + `:app:testDebugUnitTest` 通过（2157 个用例 = 上一项后的 2152 + 5，0 失败）。**没有任何设备结论**：某一版 ROM 是否有这个服务类、`startService(Class)` 是否真能把它拉起来、手势的调用方是否真的走到 `enforcePermission`、Google App 是否暴露搜索入口，全部未验证；没有目标时台账记 MISSING/SKIPPED，服务没起来只记警告并保留 ROM 原行为。真机判据：logcat 里 `ContextualSearch` 前缀的台账行 + 手势触发后服务是否从「未启动」变成可用。

**Phase 6 第六组：无障碍保活后端** — 同一分支 `codex/eta-phase6-xposed`：

| 项 | 内容 | 提交 | 验证 |
|---|---|---|---|
| 第六组：system_server 里的无障碍保活 | Minis 的 GUI 工具全部走自己的无障碍服务，而 OEM ROM 随时可以把它禁用或丢掉。这组把 Eta 的整套答案搬过来：用户开启保护后，后端只维护**本应用自己的组件**与总开关——服务列表是读-改-写，别人的条目原样保留、顺序不变；服务掉线后重绑，重启次数受 `AccessibilityRepairLimiter` 限制、退避按 `AccessibilityRestoreBackoff` 逐级升到上限后按稳定窗口复位，掉线成环不会变成死循环 | `11370a7b` | ✅ `AccessibilityServiceEnforcerTest`（10 例，Eta 原测试移植） |
| 接线与线程 | 后端在 `startOtherServices` 尾部接入（方法先 deopt），跑在 Android 自己的 `BackgroundThread` 上——不新建线程、不用定时器、不做周期轮询；system context 两级解析（对象字段/方法 → `ActivityThread.getSystemContext`）。拿不到上下文或 Handler 就只记警告、不装后端 | `11370a7b` | ✅ 上述用例覆盖组件匹配/短名去重/顺序保留/近似名不误配 |
| 开关与控制契约 | 保护开关就是平台自己的设置（`minis_accessibility_protection_enabled`），**默认关**；App 只能通过带 signature 权限的广播请求（协议版本 + 发送者 UID + 有序广播三者同时校验）与只应答 system UID 的健康 provider 询问后端，协议本身不赋予 App 写设置的能力。`xposed/LogSafety.kt`（`safeLogType`/`toSafeLogToken`）随该组移植，异常消息与用户内容不进日志 | `11370a7b` | ✅ `LogSafetyTest`（3 例）+ 协议/健康检查用例 |

✅ 的定义：该分支上 `:app:compileDebugKotlin` + `:app:testDebugUnitTest` 通过（2170 个用例 = 上一项后的 2157 + 13，0 失败）。**没有任何设备结论**：各 ROM 的设置布局、direct-boot 用户、包变化广播的真实时序、重绑是否足以把服务拉回来、App 侧控制请求是否被接受，全部未验证。**未做**：App 侧还没接控制通道（发广播的开关与「增强设置页」），所以这组目前是后端就位、默认关闭。真机判据：logcat 里 `AccessibilityProtection` 前缀的台账行 + 打开开关后服务列表里本应用的组件是否被恢复。

**Phase 6 第七组：无障碍保护的 App 侧** — 同一分支 `codex/eta-phase6-xposed`：

| 项 | 内容 | 提交 | 验证 |
|---|---|---|---|
| App 侧控制客户端 | 后端上一组落了地，但没人能打开它。这组补 App 的一半：`AccessibilityProtectionClient` 只负责请求——Secure 设置始终归后端；发的是寻址到系统包的**有序广播**，只认显式确认（APPLIED），被拒绝记 REJECTED，其余一律 UNAVAILABLE——包括 Android 14 以下：那里接收方根本无法知道广播是谁发的，后端宁可拒绝也不猜（两端在同一判断上对齐）。App 本地留一份「上次确认值」，因为该设置在某些版本上 App 读不到，开关不能凭空编状态 | `c8aad30f` | ✅ `AccessibilityProtectionClientTest`（2 例） |
| 健康 Provider 与清单 | `MinisAccessibilityHealthProvider` 只应答 system UID（平台侧还有 `MANAGE_ACCESSIBILITY` 把门），复核协议版本与方法名，答案只有 connected/disconnected 一个词——来自本应用自己的服务实例，不含节点/窗口/用户内容。清单新增签名级权限 `llc.slacker.minis.permission.CONTROL_ACCESSIBILITY_PROTECTION`（声明 + 自持）与 provider（authority 与协议常量对齐） | `c8aad30f` | ✅ 上述用例 + lint 0 error |
| 设置项 | 系统权限页新增「模块保护」一节：开关调用客户端；后端不在时（UNAVAILABLE）开关回到真实状态、副标题写明「模块未安装或未启用」，不谎报成功。字符串补齐 8 个 locale | `c8aad30f` | ✅ `:app:lintDebug` 0 error |

✅ 的定义：该分支上 `:app:compileDebugKotlin` + `:app:testDebugUnitTest` + `:app:lintDebug` 通过（2172 个用例 = 上一项后的 2170 + 2，0 失败；lint 0 error、141 条既有 warning）。**适配说明**：Eta 面向 Android 37 一代 ROM，本仓库 minSdk 26——因此身份开关与带 options 的广播在 34 以下走旧路径、发送者 UID 只在 34+ 读取（更低版本由同一套校验拒绝），包管理器查询保留旧的 int 标志重载。**没有任何设备结论**：后端接收器是否已注册、有序广播能否到达 system_server、自声明签名权限是否按预期授予、provider 的答案与平台判断是否一致，全部未验证。**未做**：`requestRecoveryBlocking`（工具路径的「现在修一下」入口）已随客户端移植但还没有调用方，等把恢复路径接进来。

**Phase 6 第八组：热词自愈** — 同一分支 `codex/eta-phase6-xposed`：

| 项 | 内容 | 提交 | 验证 |
|---|---|---|---|
| 第八组真 hook：熄屏后的热词自愈 | 有些 ROM 在熄屏时把助手的软件热词检测拆掉、之后不再拉回来——而那正是唤醒词最该可用的时候。这组盯 `PhoneWindowManager` 的 `screenTurnedOff`/`screenTurnedOn`：确认设备真的处于非交互状态后，通过启动时捕获的 voice interaction manager 服务把**平台自己已建好的**那条软件热词会话重新开始监听。谨慎之处正是移植重点：只认 0 号屏、8 秒冷却窗口内只修一次、最多三次（1.2 s/1.4 s 间隔）、每次重读开关（排队期间可能被关掉）、每次都重新确认非交互、亮屏取消待办、所有返回路径都过 generation 计数（过期尝试不可能恢复别人的会话）；只有 Google 自己的助手有这条会话，开关默认关 | `b1cf5b7a` | ✅ `HotwordSelfHealPolicyTest`（4 例） |
| 热词桥 | `AssistantHotword` 是 Eta `AssistantManager` 的热词那一半：`VoiceInteractionManagerService.onBootPhase` 时捕获 `mServiceStub`；恢复时沿 `mImpl → mComponent`（必须是 Google 包）→ `mHotwordDetectionConnection.mDetectorSessions` 找 `SoftwareTrustedHotwordDetectorSession`，用平台自己的 `mSoftwareCallback` 调 `startListeningFromMicLocked`——重启已有会话而不是新建一个。失败只在最后一次尝试时记节流日志 | `b1cf5b7a` | ✅ 上述用例（显示号/冷却边界/重试预算/非 Google 组件拒绝） |

✅ 的定义：该分支上 `:app:compileDebugKotlin` + `:app:testDebugUnitTest` 通过（2176 个用例 = 上一项后的 2172 + 4，0 失败）。**没有任何设备结论**：某一版 ROM 是否真的在熄屏时拆掉检测、那条会话是否就是 Google 的、`startListeningFromMicLocked` 是否仍是这个签名、修完唤醒词是否真的能唤醒，全部未验证；没有目标时台账记 MISSING/SKIPPED 并保留原行为。**未做**：Eta `AssistantManager` 的其余部分（助手选择、角色设置、会话展示）没有随此片移植。真机判据：logcat 里 `HotwordSelfHeal` 前缀的台账行 + 熄屏后再喊唤醒词是否响应。

**Phase 6 第九组：ColorOS 记忆只读桥** — 同一分支 `codex/eta-phase6-xposed`：

| 项 | 内容 | 提交 | 验证 |
|---|---|---|---|
| 第九组真 hook：ColorOS 记忆只读桥 | 小布记忆的数据库属于另一个 App，从外面读不到；模块本来就在它的进程里，于是挂在它自己的 `DataShareProvider.call` 上：只应答本模块自己的方法名（其余一律回落原逻辑），调用方必须是 root（App 侧走本仓库既有的结构化 `root.shell` 路径），数据库只读打开，协议两个方向都有字节上限，结果过大时如实返回 `COLOROS_MEMORY_HOOK_RESULT_TOO_LARGE`，不截断冒充完整 | `b0ddb87d` | ✅ `ColorOsMemoryBridgeProtocolTest`（4 例，含外来信封拒绝） |
| 查询层与协议 | `ColorOsMemoryDatabaseQuery` 是上游整文件搬过来的：表/列按需探测、字符串有界、LIKE 通配转义、SQLite 保留列名加引号。`ColorOsMemoryBridgeProtocol` 保留上游形状：base64url + 版本号 + 封闭操作集（search/orders/places），8 KiB / 320 KiB 上限，`buildRootCommand` 由协议自己拼（固定 URI、固定方法名、参数只允许 base64url 字母表），查询原文不会进 shell | `b0ddb87d` | ✅ 上述用例（往返/命令形状/保留列名/非法信封） |

✅ 的定义：该分支上 `:app:compileDebugKotlin` + `:app:testDebugUnitTest` 通过（2174 个用例 = 上一项后的 2170 + 4，0 失败）。**没有任何设备结论**：某一版 OPlus 记忆应用是否有这个 provider 与这套表结构、root 调用能否到达、数据库文件是否可读，全部未验证——没有目标时台账记 MISSING 并保留原行为。**未做**：App 侧工具（search/orders/places 与无模块时的快照回退）留到下一片，因此这组目前只有桥的后端半侧。真机判据：logcat 里 `ColorOsMemory` 前缀的台账行 + root 调 `content call` 是否拿到 JSON。

**Phase 6 第十组：ColorOS 记忆工具（App 侧）** — 同一分支 `codex/eta-phase6-xposed`：

| 项 | 内容 | 提交 | 验证 |
|---|---|---|---|
| 三个工具接上桥 | `android.coloros.memory` / `android.coloros.orders` / `android.coloros.places`（别名沿用上游拼写 `search_coloros_memories` / `search_personal_orders` / `search_saved_places`）把上一组的桥接到模型：请求由协议编码后走本仓库结构化的特权通道——**一个 `content call` argv**（固定 URI、固定方法名、base64url 参数），解码信封后把 JSON 交给模型。三个规范名都进了 `ToolSensitivePolicy` 敏感表（Eta 原表里本来就有那三个别名），落库 transcript 只留占位符 | `0d9d3d19` | ✅ `ColorOsMemoryQueryPolicyTest`（3 例） |
| 边界与失败关闭 | 关键词上限 200 字符（超长**拒绝**而不截断）、行数 1–30 默认 10（钳制）、编码失败即拒；root 不可用 → `COLOROS_MEMORY_ROOT_UNAVAILABLE`、`content` 不是受信工具 → `COLOROS_MEMORY_TOOL_UNAVAILABLE`、超时 → `COLOROS_MEMORY_TIMEOUT`、没有回答 → `COLOROS_MEMORY_BRIDGE_UNAVAILABLE`（带 exit_code）、回答不是本桥信封 → `COLOROS_MEMORY_BRIDGE_UNANSWERED`。每个错误都带原因，不返回空列表冒充「没查到」 | `0d9d3d19` | ✅ 上述用例 |
| 刻意没移植的回落 | Eta 为「没装模块」的设备备了 root 快照回落：一段复合 shell 脚本把数据库（含 -wal/-shm/-journal）拷进缓存再直接查。本仓库的特权面是 argv 制（受信工具 basename + 参数，不接受 raw command），复合脚本正是它拒绝的东西，所以这条回落不落；没装模块时工具如实报「模块未安装/未生效」 | `0d9d3d19` | — |

✅ 的定义：该分支上 `:app:compileDebugKotlin` + `:app:testDebugUnitTest` 通过（2177 个用例 = 上一项后的 2174 + 3，0 失败）。**没有任何设备结论**：某一版 OPlus 记忆应用是否应答这个 method、root 调用能否到达 provider、它的表结构返回什么，全部未验证。真机判据：`android.coloros.memory` 调用返回的 JSON 是数据还是某个带原因的 `COLOROS_MEMORY_*` 错误码。

**Phase 6 第十一组：ColorOS 双指识屏 → Circle to Search** — 同一分支 `codex/eta-phase6-xposed`：

| 项 | 内容 | 提交 | 验证 |
|---|---|---|---|
| 第十一组真 hook | ColorOS 把所有 direct-service 手势都送到同一个 `CollectInfoActivity.M(Intent)`，这组只认领**真正是双指识屏**的那一条：`directExt`（intent extra 拿不到时再问 `CollectionStartInfo.getDirectExt()`）解析成 JSON 后必须 `fingerTrigger=true` 且 `touchInfo.fingerCount=2`，其余一律回落原逻辑。命中后交给已移植的 `CircleToSearchInvoker` 走系统 contextual search，成功才 `finishAndRemoveTask` 收掉 ColorOS 卡片；入口不可用或触发失败就**回落原双指识屏**——绝不为一次没发生的搜索吞掉手势。开关沿用 `double_finger_circle_to_search`（默认关，它替换设备已有手势），1 秒去重窗口把同一手势的重复上报吞掉 | `c7be04f7` | ✅ `ColorDirectTriggerPolicyTest`（3 例） |

✅ 的定义：该分支上 `:app:compileDebugKotlin` + `:app:testDebugUnitTest` 通过（2180 个用例 = 上一项后的 2177 + 3，0 失败）。**没有任何设备结论**：某一版 ColorOS 是否有这个 Activity 与这套 payload 形状、JSON 键是否还在、转场覆盖是否生效，全部未验证——没有目标时台账记 MISSING 并保留原手势。真机判据：logcat 里 `ColorDirect` 前缀的台账行 + 双指手势后是否出现系统一圈即搜。

**Phase 6 第十二组：ColorOS 便签/录音/摘要检索** — 同一分支 `codex/eta-phase6-xposed`：

| 项 | 内容 | 提交 | 验证 |
|---|---|---|---|
| 三个个人数据检索 | `android.coloros.notes` / `android.coloros.recordings` / `android.coloros.recording_summaries`（别名 `search_coloros_notes` / `search_coloros_recordings` / `search_recording_summaries`）照上游走厂商应用自己的 provider（`com.nearme.note/rich_notes`、`com.coloros.soundrecorder.provider/records` 与 `/summary`），经本仓库结构化的特权通道发出一**一个 `content query` argv**（无 shell 文本）——这些 provider 本应用本来读不到。三个规范名已进 `ToolSensitivePolicy` 敏感表（别名本来就在 Eta 原表里） | `bba72496` | ✅ `PersonalDataContentParserTest`（2 例，上游测试移植）+ `PersonalDataQueryPolicyTest`（4 例） |
| 解析与边界 | `PersonalDataContentParser` 是上游整段搬来的，两条性质正是它的价值：provider 异常（stderr 里的 `Error while accessing provider:` / `IllegalArgumentException` / `SecurityException`）判为**失败并给出错误码**，绝不冒充「没查到」；行按**请求的列**切分，值里的逗号不会把一条记录切碎。关键词要进别人的 SQL，因此 `\` `%` `_` `'` 都做转义、按列大小写不敏感匹配；关键词超 200 字符**拒绝**（不截断），行数 1–30 默认 10 | `bba72496` | ✅ 上述用例（含逗号正文与通配符转义） |
| 没做 | Eta 同文件的 `search_qq_chat_images`/`search_wechat_chat_images`（读 QQ/微信私有缓存目录）仍在「待拍板」里，没有随本片落地 | — | — |

✅ 的定义：该分支上 `:app:compileDebugKotlin` + `:app:testDebugUnitTest` 通过（2186 个用例 = 上一项后的 2180 + 6，0 失败）。**没有任何设备结论**：某一版 ColorOS 是否暴露这些 provider、root 调用能否到达、列返回什么，全部未验证；失败时工具给出带原因的错误码而不是空列表。真机判据：三个工具调用返回的 JSON 是数据还是某个带原因的 `PERSONAL_DATA_*` 错误码。

**Phase 6 第十三组：HyperOS 桌面导航条长按 → Circle to Search** — 同一分支 `codex/eta-phase6-xposed`：

| 项 | 内容 | 提交 | 验证 |
|---|---|---|---|
| 三个入口只装一个 | HyperOS 把同一个手势发布在多个桌面辅助类里，某一版 ROM 只带其中若干；这组按上游顺序找第一个匹配的入口装上，其余不动——装两个会在嵌套调用里把同一次搜索触发两遍（`NavStubGestureEventManager.handleLongPressEvent` → `CircleToSearchHelper.invokeOmni` → `NavBarEventHelper.onLongPress`）。三者都走同一个 `HyperOsSearchTrigger`（沿用 `gesture_bar_circle_to_search` 开关、缺 context 或系统搜索入口不可用就回落原生行为），并按返回类型给回答：void 给 null、boolean 给 true | `b5a325af` | ✅ `HyperOsGesturePolicyTest`（3 例） |
| 旧版导航视图 | 对「长按检测在自己手里」的那一版桌面，改看 `NavStubView.onTouchEvent`：每个视图一个检测器（一次只持一个手势、只弱引用视图、不在延迟任务里持有 hook chain），只有真正被接管的那次手势会把事件改成 CANCEL 结束桌面自己的手势流，并在手势 pending 时压掉 recents 预启动；若该视图已经带有来历不明的长按检测（`mCheckLongPress`）就让给 ROM，不与之竞争 | `b5a325af` | ✅ 上述用例（void/boolean/其它返回类型的接管回答） |

✅ 的定义：该分支上 `:app:compileDebugKotlin` + `:app:testDebugUnitTest` 通过（2189 个用例 = 上一项后的 2186 + 3，0 失败）。**没有任何设备结论**：某一版桌面是否有这些类与这条手势路径、系统搜索入口是否可解析、CANCEL 是否真的结束桌面自己的事件流，全部未验证——没有目标时台账记 MISSING/SKIPPED 并保留原手势。真机判据：logcat 里 `HyperOsLauncher` 前缀的台账行 + 长按导航条是否出现系统一圈即搜（开关打开时）。

**Phase 6 第十四组：ColorOS SystemUI 的 OCR 长按 → Circle to Search** — 同一分支 `codex/eta-phase6-xposed`：

| 项 | 内容 | 提交 | 验证 |
|---|---|---|---|
| 导航条 OCR 长按 | ColorOS 的导航条长按是 SystemUI 里的屏幕 OCR；这组改挂 `OplusOcrScreenBusiness.onLongPressed()`，把这次手势交给系统 contextual search——但只在**开关为开、表面能给出 context、系统搜索入口真的可解析**时；任何一条不满足就跑 ROM 原来的 OCR。接管成功时用 SystemUI 自己的 `VibrationHelper.vibrateCustomized` 重放 ROM 原有的长按震动（效果 id 与查找顺序都是常量），拿不到助手只记节流警告、不吞手势 | `b6e94b65` | ✅ `SystemUiOcrPolicyTest`（2 例） |
| 查找表作为值 | context 先按访问器 `getContext()` 找，再按 `context`/`mContext`/`mOcrContext` 三个成员依次找；顺序与震动效果 id 都被用例钉住——这类查找表一旦与目标改名不同步，表现是「接管从不触发」而不是报错 | `b6e94b65` | ✅ 上述用例 |

✅ 的定义：该分支上 `:app:compileDebugKotlin` + `:app:testDebugUnitTest` 通过（2191 个用例 = 上一项后的 2189 + 2，0 失败）。**没有任何设备结论**：某一版 ColorOS SystemUI 是否有这个 OCR 表面、它的 context 成员是否仍叫这些名字、VibrationHelper 是否存在，全部未验证——没有目标时台账记 MISSING 并保留原 OCR。**说明**：该目标类只存在于 OPlus/ColorOS 的 SystemUI，小米设备上会直接记 MISSING。真机判据：logcat 里 `SystemUI` 前缀的台账行 + 导航条长按是否出现系统一圈即搜（开关打开时）。

**Phase 6 第十五组：模块化无障碍修复接入恢复流程** — 同一分支 `codex/eta-phase6-xposed`：

| 项 | 内容 | 提交 | 验证 |
|---|---|---|---|
| 恢复流程先问模块 | 第七组的 App 侧客户端里 `requestRecoveryBlocking` 此前没有调用方——授权被 force-stop 抹掉时，应用仍然只会弹「用 Shizuku 修 / 去设置」。现在 `ensureGrantOrPrompt` 在弹窗之前先**无声地**请模块后端修（后端就在 system_server 里，既不需要 Shizuku 也不需要用户点一下）：开关开着才问（关着就别白白等一个超时）、请求在 IO 线程发出（客户端拒绝阻塞主线程）、**只有显式 APPLIED 才算修好**，UNAVAILABLE/REJECTED 一律回落到原来的提示路径——绝不谎报一个没写成的授权 | `7d03b6b2` | ✅ `AccessibilityRecoveryManagerTest` 补 1 例（三种状态） |
| 成功后的收尾 | 与 Shizuku 路径同一套：等真正的重绑信号（`onServiceConnected`）最多 5 秒、`invalidateCache()` 后重读撤销状态，日志里给出是否重绑（授权写入本身才是完成判据） | `7d03b6b2` | ✅ 上述用例 |

✅ 的定义：该分支上 `:app:compileDebugKotlin` + `:app:testDebugUnitTest` 通过（2192 个用例 = 上一项后的 2191 + 1，0 失败）。**没有任何设备结论**：授权被抹掉时后端接收器是否已注册、有序广播能否到达 system_server，均未验证——失败只是回落到原有提示路径，不会卡住工具调用。真机判据：force-stop 之后首次 a11y 工具调用是否不再弹 Shizuku 提示（开关打开时）。

**Phase 4 补片：只回验证码的短信工具** — 同一分支 `codex/eta-phase6-xposed`：

| 项 | 内容 | 提交 | 验证 |
|---|---|---|---|
| `read_sms_code` | 登录流程要的是六个数字，不是那条短信。规则照上游且比「找个数字」严得多：正文里必须出现**点明这是验证码的词**（中文几种说法 + `verification code`/`one-time password`/`otp`），取**离该词最近**的 4–8 位数字串（真实短信里第一个数字往往是金额、百分比或电话号码）；只返回 code/sender/timestamp，正文不出这个函数。读取走本仓库自己的短信路径（本应用已持有 READ_SMS，`android.sms.read` 查的就是同一个 provider），窗口 1–1440 分钟默认 10、最多回 10 条。工具注册名 `android.sms.code`、别名 `read_sms_code`，并进敏感表（一次性验证码不该留在 transcript 里） | `d0548894` | ✅ `SmsCodeExtractionPolicyTest`（5 例：中英文、就近取值、无上下文词拒绝、非 4–8 位拒绝、窗口钳制） |

✅ 的定义：该分支上 `:app:compileDebugKotlin` + `:app:testDebugUnitTest` 通过（2197 个用例 = 上一项后的 2192 + 5，0 失败）。**没有任何设备结论**：某些 ROM 仅凭 READ_SMS 读不到 provider（还需要默认短信应用角色），未验证；被拒时工具返回 provider 自己的拒绝信息而不是空列表。

**Phase 4 补片（二）：设备开关与 App 冻结** — 同一分支 `codex/eta-phase6-xposed`：

| 项 | 内容 | 提交 | 验证 |
|---|---|---|---|
| `set_device_state` → `android.device.state` | 上游用一条 root 命令直接开关 Wi-Fi/蓝牙（`svc wifi enable|disable`、`cmd bluetooth_manager enable|disable`）；这里把同两条命令写成 **argv** 走结构化特权通道，目标先校验、未知目标直接拒绝（Wi-Fi 与蓝牙是两个系统服务，猜错就是静默切错无线电）；回答是共享的命令结果（backend/exitCode/stdout/stderr/timedOut）+ 请求内容，而不是一个光秃秃的布尔 | `83a0551e` | ✅ `DeviceStatePolicyTest`（4 例） |
| `app_state_control` → `android_app` 的 `freeze`/`unfreeze` | 上游三个动作里 `force_stop` 本仓库早有（`android_app stop`），这次补上另外两个：`pm disable-user`（冻结，保留数据但不能再运行）与 `pm enable`（解冻）。三条命令统一收进一个纯策略 `AppStatePolicy`，`stop` 也改用它——同一命令只有一处实现；包名走既有的 `requirePackageName` 校验；风险级别定为 **DESTRUCTIVE**（需一次性审批）：冻结会改写别的包的启用状态，选错目标是系统应用就会一直用到解冻 | `bb350a4b` | ✅ `AppStatePolicyTest`（4 例） |

✅ 的定义：该分支上 `:app:compileDebugKotlin` + `:app:testDebugUnitTest` 通过（2205 个用例 = 上一项后的 2197 + 4 + 4，0 失败）。**没有任何设备结论**：某些 ROM 是否允许 `pm disable-user` 经特权通道动系统包、`svc wifi`/`cmd bluetooth_manager` 是否被接受，均未验证；工具如实返回 exit code 与 stderr。

**Phase 4 补片（三）：闹钟与计时器列表** — 同一分支 `codex/eta-phase6-xposed`：

| 项 | 内容 | 提交 | 验证 |
|---|---|---|---|
| `list_alarms` / `list_active_timers` | 早前那片闹钟工具把这两个列成「刻意差异」，理由是「Clock API 发完即忘、列表只可能是打开应用自称列表」。上游其实有真的列表：**读时钟应用自己的数据库**（`/data/user_de/{user}/com.coloros.alarmclock/databases/alarms.db`），因为「现在设了什么」只存在于那个应用里。本片按上游落地：工具名 `android.alarm.list` / `android.alarm.timers`，别名沿用上游拼写，挂进 `AlarmTools` 家族 | `c839a6a6` | ✅ `PrivateDatabaseRulesTest`（5 例） |
| 快照按 argv 拆成多步 | 必需的区别：上游把整个拷贝写成一段复合 shell 脚本，本仓库的特权面是 argv，所以同样的步骤拆成独立命令——`stat -c %s` 量大小、`readlink` 拒绝「读到的不是刚量过的那个文件」的软链、`cp` 复制；数据库与每个 SQLite 边车（`-wal`/`-shm`/`-journal`）各来一遍。边车不存在是正常的，**被拒绝的边车会让整次快照失败**（与上游 exit 25/26 同义）；快照用完即删，路径/表/列全部写死在代码里，调用方输入到不了那里 | `c839a6a6` | ✅ 上述用例（大小上限、软链判定、列要求、行数钳制、LIKE 转义） |
| 查询与 schema 容忍 | 照上游：`alarms` 表要求 `_id/hour/minutes/enabled`，`timer_schedule` 表要求 `_id/duration/state`；结构不符答 `CLOCK_SCHEMA_UNSUPPORTED` 而不是猜；只查询表里真实存在的列（列少一版就少返回一列，不整条失败）；字段长度有界，行数 1–50 默认 20 | `c839a6a6` | ✅ 上述用例 |

✅ 的定义：该分支上 `:app:compileDebugKotlin` + `:app:testDebugUnitTest` 通过（2210 个用例 = 上一项后的 2205 + 5，0 失败）。**没有任何设备结论**：某一版 ROM 是否允许 root 把这个数据库拷进应用缓存、应用随后能否读那个 root 建的文件（SELinux 标签），全部未验证；每条失败路径都给出带错误码的答复而不是空列表。**文档更正**：闹钟那片「刻意差异」的说明已在本节作废，并在工具 KDoc 里改成现在的行为。真机判据：两个列表工具返回数据，还是 `CLOCK_DATA_UNAVAILABLE`/`CLOCK_SCHEMA_UNSUPPORTED`。

**Phase 4 补片（四）：剪贴板历史与健康摘要** — 同一分支 `codex/eta-phase6-xposed`：

| 项 | 内容 | 提交 | 验证 |
|---|---|---|---|
| 快照抽成共享件 | 上一片的快照机制从时钟工具里抽成 `PrivateDatabaseSnapshot`（路径模板 + 大小上限 + 错误码都参数化），三处数据库共用一套：`stat` 量大小、`readlink` 拒软链、`cp` 复制、边车逐个处理、用完即删、每条失败路径都带错误码；行读取（列探测/按存在的列投影/字段有界/统一信封）抽成 `DatabaseRows`，同样三处共用 | `390f5857` | ✅ 既有 `PrivateDatabaseRulesTest` 覆盖判定规则；新增 `HealthSummaryPolicyTest`（3 例） |
| 剪贴板历史 | `android.clipboard.history`（别名 `search_clipboard_history`）：读当前输入法的剪贴板库（`com.sohu.inputmethod.sogouoem/databases/clipboard_db` 的 `CLIPBOARD_ITEM` 表，要求 `TIME`/`CONTENT`），关键词走 `CONTENT LIKE ? ESCAPE '\' COLLATE NOCASE`（通配符先转义、按字面匹配），最新在前、1–50 默认 20；取不到答 `CLIPBOARD_HISTORY_UNAVAILABLE`，结构不符答 `CLIPBOARD_SCHEMA_UNSUPPORTED` | `390f5857` | ✅ 上述用例（含 LIKE 转义） |
| 健康摘要 | `android.health.summary`（别名 `get_health_summary`）：读 Health Connect 的库（`/data/system_ce/{user}/healthconnect/healthconnect.db`，上限 256 MiB），按窗口（1–30 天默认 7）给步数/睡眠/运动/心率/最新体重/最新血氧的**汇总**——不返回原始测量序列；体重从库里存的克换算成千克；缺表或缺列的项**直接不出现**（不是 0，也不是猜测） | `390f5857` | ✅ `HealthSummaryPolicyTest`（窗口钳制、cutoff、克→千克） |

✅ 的定义：该分支上 `:app:compileDebugKotlin` + `:app:testDebugUnitTest` 通过（2213 个用例 = 上一项后的 2210 + 3，0 失败）。**没有任何设备结论**：某一版 ROM 是否装着这个输入法并有那个库、Health Connect 是否把数据放在该路径、root 能否把两者拷进应用缓存，全部未验证；失败一律给错误码而不是空列表。真机判据：两个工具返回数据，还是 `CLIPBOARD_*`/`HEALTH_DATA_UNAVAILABLE` 之类的错误码。

**Phase 4 补片（五）：QQ/微信聊天图片缓存** — 同一分支 `codex/eta-phase6-xposed`：

| 项 | 内容 | 提交 | 验证 |
|---|---|---|---|
| 两个聊天图片工具 | `android.chat_images.qq` / `android.chat_images.wechat`（别名 `search_qq_chat_images` / `search_wechat_chat_images`）：扫两个应用**自己的缓存目录**（QQ 的 `chatimg`/`chatraw`/`chatthumb` 三棵树，微信的 `image/`），只列路径、类型、时间、大小——不读图片内容；大小上限沿用图片编码器的 12 MiB（超出的候选不进列表），行数 1–30 默认 10，关键词按路径小写包含匹配。之前挂在「待拍板」里，本轮按上游落地 | `4d8de6e5` | ✅ `ChatImagePolicyTest`（5 例） |
| argv 化的扫描 | 上游是一条 shell 管道（`test -d && find … \| sort -rn \| head`）；这里把 `find` 连同路径过滤、`-size`、`-printf '%T@\|%s\|%p'` 作为**一个 argv** 发出去（分组括号也是 argv），排序与截断在 Kotlin 里做——等价于 `sort -rn`（mtime 是首个字段）；行解析只接受**确实在被扫目录之下**的路径（`dir-backup/` 这种同前缀目录不算），时间/大小解析不出的行直接丢 | `4d8de6e5` | ✅ 上述用例（argv 形状、行解析、越界路径拒绝、QQ 三种树命名） |

✅ 的定义：该分支上 `:app:compileDebugKotlin` + `:app:testDebugUnitTest` 通过（2218 个用例 = 上一项后的 2213 + 5，0 失败）。**没有任何设备结论**：某一版 ROM 是否允许特权通道扫这些缓存目录、该版 toybox `find` 是否支持 `-printf`，均未验证——扫不动就返回带错误码的答复；工具描述里也写明「读取图片本身取决于运行时能否到达那条路径」。真机判据：两个工具返回缓存行，还是 `QQ_CHAT_IMAGES_UNAVAILABLE`/`WECHAT_CHAT_IMAGES_UNAVAILABLE`。

**Phase 4 补片（六）：下载记录 + 查询路径归位** — 同一分支 `codex/eta-phase6-xposed`：

| 项 | 内容 | 提交 | 验证 |
|---|---|---|---|
| `search_downloads` | `android.downloads.search`（别名 `search_downloads`）：走 Download provider 给普通应用的那个视图 `content://downloads/my_downloads`（URI 用上游的），按 title/description 关键词过滤、最新在前、1–30 默认 10。**诚实边界写进工具描述**：这个视图只包含本应用自己创建的下载记录，别人的下载看不到——早前把它记成「不值得做」，现在按上游补齐并把限制写明 | `b2d98596` | ✅ 复用既有 `PersonalDataQueryPolicyTest`（关键词转义与边界）；本片无新纯逻辑 |
| 查询路径归位 | 上游这几条个人数据检索共用**同一个** `query(...)` 助手；本仓库此前把它放在 `ColorOsPersonalDataTools` 里（当初只有 ColorOS 三个工具用它）。现在提成 `PersonalDataQueryTools.query(...)`，ColorOS 三个工具与下载工具共用一份实现——同一个轮子不再有两处 | `b2d98596` | ✅ 编译 + 全量单测 |

✅ 的定义：该分支上 `:app:compileDebugKotlin` + `:app:testDebugUnitTest` 通过（2218 个用例 = 上一项后的 2218 + 0，0 失败）。**没有任何设备结论**：某一版 ROM 的 Download provider 是否经特权通道应答，未验证；被拒时返回带错误码的失败而不是空列表。真机判据：`android.downloads.search` 返回本应用的下载记录或一个带原因的 `PERSONAL_DATA_*` 错误码。

**Phase 6 第十六组：模块设置页 + 两处「自己造的轮子」对齐上游** — 同一分支 `codex/eta-phase6-xposed`：

| 项 | 内容 | 提交 | 验证 |
|---|---|---|---|
| 模块设置页 | 此前所有 hook 组都从模块偏好里读开关，但这些开关只能改偏好文件——App 没有入口。本轮补上设置页（挂在「系统权限」旁边）与它下面的 `ModuleSettingsStore`：写的就是框架交给被注入进程的**同一个偏好组**，键与默认值都取自 `ModulePrefs`，所以界面写不出模块不会读的开关，没写过的值回落到模块自己的默认（每个接管默认都是关）。只展示**已落地**的开关（手势条识屏、双指识屏、热词自愈、电源键助手三选一）——给还不存在的功能做开关等于许一个模块兑现不了的承诺 | `19000ea8` | ✅ 编译 + 全量单测 + `:app:lintDebug`（0 error）；字符串补齐 8 个 locale |
| 模块入口对齐上游 | 对着上游 `ModuleMain` 复核我自己的注册表，找到两处真实差距：①上游有些目标**只装在包自己的主进程**（桌面 / SystemUI / 记忆应用），其余（Google / ColorDirect / Breeno / 小爱）才匹配子进程；②上游用 `isFirstPackage` 保证**每进程只装一次**。本仓库此前对所有目标都用「进程属于该包」，也没有一次性保护——SystemUI 的 hook 可能装进它某个子进程（那里根本没有这个表面），桌面则可能把整组 hook 叠第二份。现在 `ModuleTargets.MAIN_PROCESS_TARGETS` + `installsInProcess` 写明这个划分，入口类对每个目标每进程只装一次，生命周期回调过滤同规则（这也正是上游的理由：模块装不进 hook 的进程不该为它付费） | `3ccc22b0` | ✅ `ModuleTargetsTest` 补 2 例（主进程/子进程、回调过滤） |
| 电源键启动对齐上游 | 我此前只搬了「活动意图」那一半。上游的启动半侧还有两条：**先确认自己的 App 真是系统助手角色持有者**（`AssistantManager.isAssistantConfigured`，读 assistant 安全设置）才打开它，以及**先问目标是否应答该 action**（`PowerHooks` 的 `resolvesActivity`）；Gemini 会依次试 `ACTION_ASSIST` 与 `ACTION_VOICE_COMMAND`。三条都补进 `AssistantLaunch`——于是「选了 Minis 但角色还没给它」时保持系统行为，而不是劫持电源键 | `3ccc22b0` | ✅ `AssistantLaunchTest` 更新（action 列表、Gemini 组件、角色门） |

✅ 的定义：该分支上 `:app:compileDebugKotlin` + `:app:testDebugUnitTest` 通过（2220 个用例 = 上一项后的 2218 + 2），`:app:lintDebug`/`:app:lintRelease` 均 0 error。**没有任何设备结论**：框架是否把该偏好组交给某一版 ROM 上的被注入进程、assistant 安全设置对 hook 进程是否可读、桌面是否应答这两个 action，均未验证。

**全量检查（最近一次，2026-09-19 重跑）**：`:app:compileDebugKotlin` + `:app:testDebugUnitTest`（2314 例 0 失败）；`:app:lintDebug` 与 `:app:lintRelease` 均 0 error（145 条 warning、5 条 hint，全是既有类别）；`:app:assembleDebug` 通过（APK 82.3 MiB），产物内含 `META-INF/xposed/{java_init.list,module.prop,scope.list}`；`verify-android-16k.sh`（24 个 native 库 16 KB 对齐）；`test_pty_bridge.py`；Rust 代理 `cargo fmt --check` / `clippy -D warnings` / `cargo test`（9 例）；`check-runtime-package-boundary.sh`、`check_build_cleanup.py` 与其 14 例守卫测试、`test_docs_provenance.py`（18 例）、`test_browser_js_syntax.py`（13 个注入脚本经 `node --check`）——全绿。**跑不了的两项（附原因）**：`:app:assembleRelease` 停在仓库自己的 `requireReleaseSigning`（要求生产签名环境变量，且明确禁止用 debug 签名——Release Kotlin 编译与 R8 本身已跑过并通过）；`verify-runtime-payload.sh` 找不到 `assets/minis-runtime/ubuntu-arm64-rootfs.tar.gz`，因为本环境没有构建 rootfs dist（该 payload 在本仓库是可选的，只有 `MINIS_REQUIRE_RUNTIME_PAYLOAD=1` 时才强制）。

**同轮附带的小修（`069f4509`）**：全量 lint 在上一片的粘贴助手上报了两条 `InlinedApi` warning（`ClipDescription.EXTRA_IS_SENSITIVE` 是 API 33 字段，而它命名的标记从 Android 13 起就被读取）。改用 `ClipboardRestorePolicy` 里已经被测试钉住的那个键常量：各 API 级别行为一致、字符串只剩一处真相，warning 归零。

**Phase 6 第十七组：ColorOS 电源键（OPlus 消息路径）** — 同一分支 `codex/eta-phase6-xposed`：

| 项 | 内容 | 提交 | 验证 |
|---|---|---|---|
| 复核对上游发现的缺口 | 上一轮把上游 `hook/system/PowerHooks.kt` 与 HyperOS 那条逐行比过之后确认：它是**ColorOS 自己的电源键路径**，不是 HyperOS 的另一个写法。这些 ROM 上长按电源键不是桌面发起的调用，而是**窗口管理器给自己的 speech handler 发的一条消息**；这组挂在 `PhoneWindowManagerExtImpl$OplusSpeechHandler.handleMessage` 上，只认领那条 assist 消息（`what=0x3F3`）并交给 `AssistantLaunch` 打开用户选的助手；其它消息、OEM 目标、解析不到窗口管理器、启动失败，一律回落 ROM | `bb655df0` | ✅ `PowerKeyPolicyTest`（2 例：消息 id、去重窗口） |
| 两条让手感不变的上游规则 | ①接管成功时**重放 ROM 自己的长按震感**（`getWrapper().performHapticFeedback(0, "Speech - Long Press")`，拿不到只记节流警告）；②**1 秒去重窗口**内第二次按下直接吞掉，不会把助手开两次。消息 id、震感常量与窗口都收在 `PowerKeyPolicy` 里 | `bb655df0` | ✅ 上述用例 |
| 刻意没搬的上游两半 | 上游先试 `AssistantManager.showAssistantSession`（自己的 voice-interaction 会话）再退回活动意图，并在成功后**后台修复默认助理配置**（`scheduleAssistantRecovery`）。这两半都属于 Eta 的助手选择/角色机制——正是本仓库不新建的那条通道，所以这里只保留活动意图那半（`AssistantLaunch`，已在上一轮补齐角色门与 action 解析），文件 KDoc 里写明 | `bb655df0` | — |

✅ 的定义：该分支上 `:app:compileDebugKotlin` + `:app:testDebugUnitTest` 通过（2222 个用例 = 上一项后的 2220 + 2），`:app:lintDebug` 0 error。**没有任何设备结论**：某一版 ColorOS 是否有这个 handler 与这个消息 id、能否从 handler 解析到窗口管理器、wrapper 是否暴露 `performHapticFeedback`，全部未验证——没有 handler 时台账记 SKIPPED 并保留原行为。

**Phase 2 收尾：服务端联网搜索开关** — 同一分支 `codex/eta-phase6-xposed`：

| 项 | 内容 | 提交 | 验证 |
|---|---|---|---|
| 按模型条目开启服务端搜索 | Phase 2 的最后一项遗留（当年结论是「调用方可用 `extra_body` 自带 tools，是否需要专门开关待定」）。上游的形态是**按模型条目**存开关，请求里加一条 `{"type":"web_search"}`。本仓库条目本来就把用户决策放在 `overrides_json`，而该字段注释就写明「加可选字段对旧 JSON 反序列化安全」，所以这一项**不需要数据库迁移**：`ModelOverrides.hostedWebSearch`（可选）+ `LLMModel.hostedWebSearch`（默认关）+ `ModelEntry.model` 折叠；界面在「模型条目 → 能力」区加一行开关，按既有约定「与基模型不同才落盘」；导出给 Linux 运行时的 overrides 镜像同步补字段 | `e9b9a827` | ✅ `HostedWebSearchPolicyTest`（5 例） |
| 只进 Responses、不重复、不动默认 | 上游只在 Responses 请求里加这条（Chat Completions 没有该工具）。合并规则抽成 `HostedWebSearchPolicy.apply`：**关 = 原样返回同一个数组**（所有现有条目的请求逐字节不变）、开 = 已存在则不重复、否则在副本上追加——绝不原地改动调用方数组。请求侧把原来的 `if (tools.isNotEmpty())` 改成「托管工具 + 服务端搜索合并后再判断」，于是没有任何托管工具时也能只带这一条 | `e9b9a827` | ✅ 上述用例（关/开/空数组/已存在/不改原数组） |

✅ 的定义：该分支上 `:app:compileDebugKotlin` + `:app:testDebugUnitTest` 通过（2227 个用例 = 上一项后的 2222 + 5），`:app:lintDebug` 0 error（字符串补齐 8 个 locale）。**没有任何设备结论**：某个 relay 是否真的接受这条 hosted tool、以及不支持该工具的 provider 上开关的表现，均未验证。**未做**：上游还会把 `response.web_search_call.{in_progress,searching,completed,failed}` 四个事件渲染成一条「网页搜索」工具行；来源本身已经通过引用格式化渲染出来，这一步留作后续。

**Phase 2 收尾（二）：服务端工具的活动行** — 同一分支 `codex/eta-phase6-xposed`：

| 项 | 内容 | 提交 | 验证 |
|---|---|---|---|
| 服务端工具的活动行 | 上一项开关的配套：上游把 `response.<kind>_call.<phase>` 渲染成独立一行，否则「用了服务端搜索」的回合在问答之间什么都看不到。`HostedCallEventPolicy` 把上游读法变成值：相位对应（`in_progress`/`searching`→开始，`completed`→完成，`failed`→失败）、**未知相位不渲染错行**、id 选择顺序（事件名 → item → 确定性回退）、以及「一次调用只报一次开始与一次结束、只有结束没有开始时两条都补」的账本（与上游那张 map 同义） | `cd602827` | ✅ `HostedCallEventPolicyTest`（5 例） |
| 渲染接法 | provider 从策略发 `LLMStreamChunk.HostedToolActivity`，聊天层把它追加为**一条 info 行**——它永远不会变成 `tool_use` 块，所以没有任何路径会去「执行」一个本应用没跑过的工具。**一处刻意适配**：上游把行的中文名硬编码在 provider 里，本仓库让 chunk 只带类型 token（`web_search`/`file_search`/`code_interpreter`/`computer`/`image_generation`/`mcp`），由 UI 取字符串（8 个 locale），不认识的类型也有通用行 | `cd602827` | ✅ 上述用例 + `:app:lintDebug` 0 error |

✅ 的定义：该分支上 `:app:compileDebugKotlin` + `:app:testDebugUnitTest` 通过（2232 个用例 = 上一项后的 2227 + 5），`:app:lintDebug` 0 error。**没有任何设备结论**：真实流进行中这一行的位置观感、以及根本不发这些事件的 relay 的表现均未验证——不发这些事件时产生的行与改动前完全一致。

**Phase 3 补片：按选区输入文本（`input_text`）** — 同一分支 `codex/eta-phase6-xposed`：

| 项 | 内容 | 提交 | 验证 |
|---|---|---|---|
| 按选区输入 | 上游 GUI 动作清单里我们还缺的那一个：`set_text` 只能写整个值，没有办法在字段已有内容上追加。`input_text` 用字段**自己的文本与选区**（UTF-16 偏移）重建写入后的值与光标，写下去并把光标留在插入点之后——这才是「在这里打字」，也是已有内容的搜索框需要的动作 | `9d7e31be` | ✅ `TextEditPlannerTest`（4 例） |
| 上游的拒绝判据一并搬来 | 重建一个我们读不到的字段的值，等于悄悄覆盖用户打过的字，所以上游的门留着：密码字段或不肯交出文本的字段答 `TEXT_CONTENT_UNAVAILABLE`，报不出可用光标/选区的答 `TEXT_SELECTION_UNAVAILABLE`，两者都提示改用 `set_text` 发完整值；不可能成立的选区只在**空字段**上被容忍（那里只有一个插入点）。服务侧补上上游写法的光标那半：`setNodeText(node, text, cursor)` 写完再放光标，放之前先 `refresh`（过期节点会把它早就不带的文本的选区接过去）；原来的整体写入改为以「光标在末尾」委托，与上游同形 | `9d7e31be` | ✅ 上述用例 + 结果里区分 `ACTION_SET_TEXT_AND_SELECTION`/`ACTION_SET_TEXT` |

| `paste_text` 与剪贴板卫生 | 上游 `paste_text` = `input_text` + 「目标直接拒绝写入时改走粘贴」——有些编辑器只认粘贴。重读它时翻出**两个我们旧写法没有的细节，价值比这个动作本身更大**：①临时剪贴项要标 **sensitive**，否则文本会出现在剪贴板预览里、也会被其它读剪贴板的应用拿到；②**只有剪贴板里还是我们那条**（按标签判断）才还原原来内容——用户中途复制了别的东西，就不能用旧快照盖掉，而本仓库原来的回退正是无条件还原（真实缺陷）。两者收进 `ClipboardRestorePolicy`（标签格式、还原判据、平台敏感标记）并有用例；一次「借用-归还」助手现在同时服务 `set_text` 的回退与 `paste_text`，剪贴板只有一处被碰 | `7998463a` | ✅ `ClipboardRestorePolicyTest`（3 例） |

| `ime_enter` 与写入上限 | 上游 `press_key` 的 ENTER 支路：在**持有输入焦点**的输入框上按字段自己的 IME 动作（搜索/完成/发送），提交搜索框或消息不用去猜哪个按钮——需要 Android 11，更老的平台带原因拒绝而不是静默无操作。写入上限一并搬：插入 ≤1000 字符且不得为空，整值 ≤4000 且**可以**为空（清空字段就是写空值），上游是在碰设备之前就拒绝 | `9964e554` | ✅ `TextInputBoundsPolicyTest`（2 例） |
| 刻意没搬的其余部分 | 上游 `press_key` 还把 BACK/HOME/RECENTS/PASTE/NOTIFICATIONS/QUICK_SETTINGS 统一成一个动作并带 root `input keyevent` 回退；本仓库这些面板/按键本来就是各自的动作、且宁可拒绝也不接第二套实现（第二实现＝两条真相），所以只取 ENTER 那支。`clear_text` 是上游的 `replace_text("")`，本仓库 `set_text` 传空值即等价，不再加重复动作 | `9964e554` | — |

| 剪贴板读取/写入上限 | 上游两端都有界，我们两端都没有：**读**会把用户刚复制的任何东西（可能是一整份文档）原样倒进模型上下文；**写**不限长度。数值照搬：读最多交 8000 字符**并明说被截断**，写最多 20000 且**在改动剪贴板之前就拒绝**（截断写入等于对「用户让我复制的东西」撒谎）。规则收在 `ClipboardBoundsPolicy` 并有用例；处理器是唯一施加处——原样文本形态保持管道依赖的纯文本（有界），`--json` 形态给出 `text/chars/truncated`，且 `android.clipboard` 的读取改为走 JSON 形态，于是模型能被告知「你拿到的不是整条剪贴板」 | `a143104a` | ✅ `ClipboardBoundsPolicyTest`（3 例） |

| 等待文本的匹配模式 | 上游 `wait_for_text` 有四种匹配方式——`contains`（默认，**大小写不敏感**）、`exact`、`prefix`、`regex`——本仓库的 `wait` 只会第一种，需要等整段标签或正则的调用方只能自己去轮询 observe。`UiWaitPolicy` 照搬上游规则（含容易被「顺手改好」的那条：exact/prefix **按原样**比较，默认才忽略大小写；正则编译失败就是**不匹配**而不是让等待失败）。`wait` 增加 `match` 与 `include_desc`（默认 true，同上游），结果里带上所用模式与轮询次数；**未知模式直接拒绝并列出可选值**，而不是像上游那样静默折进默认（这是本片唯一刻意差异——把 typo 变成另一种搜索会让人查不出问题） | `3a179f67` | ✅ `UiWaitPolicyTest`（4 例） |

✅ 的定义：该分支上 `:app:compileDebugKotlin` + `:app:testDebugUnitTest` 通过（2248 个用例 = 上一项后的 2244 + 4）。**没有任何设备结论**：某一屏把文字摆在哪几个节点上未验证；决定比较方式的是模式，遍历范围与之前一致（text 与可选 contentDescription，深度 30）。**说明**：本片只改代码与工具 schema，未动资源/清单，按验证矩阵未复跑 lint。

**自造轮子复核（二）：有界拷贝的两点对齐 + 一处漏网** — 同一分支 `codex/eta-phase6-xposed`：

| 项 | 内容 | 提交 | 验证 |
|---|---|---|---|
| 我们的 `BoundedStreams` vs 上游 `BoundedFileCopy` | 本仓库早就有有界拷贝助手，所以轮子在——但与上游差在**快乐路径之后**的两点：①上游**每块之间检查取消**并抛 `InterruptedIOException`，我们没有：从慢速 provider 拷大文件时，就算调用方取消了工作，拷贝仍会跑到对方文件的尽头；②上游抛**可区分的** `TooLargeException`，调用方能说清是"源太大"还是"流坏了"，我们只抛带消息的 IOException。两点都补进助手（异常保持是 IOException 子类，既有 catch 不受影响） | `62eaffec` | ✅ `BoundedStreamsTest` 补 2 例（类型区分、取消即停） |
| 漏网的一处无界导入 | 把全部 `openInputStream` 调用点扫了一遍找同类缺陷，抓到一处仍然无界：Add to Home Screen 的网页源文件是裸 `input.copyTo(out)` 直接写进缓存，用户误选一个大归档就会灌满缓存目录。现在按 8 MiB 预算拷贝，拒绝时带上原因。其余导入点早已有界（分享接收器的带预算拷贝、技能/角色卡/Provider 导入、宠物包解包、`read_image` 的 50 MiB） | `62eaffec` | ✅ 上述用例 + 全量单测 |

✅ 的定义：该分支上 `:app:compileDebugKotlin` + `:app:testDebugUnitTest` 通过（2250 个用例 = 上一项后的 2248 + 2）。**没有任何设备结论**：某个 provider 的流在拷贝中途被打断时如何表现未验证；拷贝会停下并说明，这是调用方依赖的行为。

**浏览器结果的载荷闸门与分页读取** — 同一分支 `codex/eta-phase6-xposed`：

| 项 | 内容 | 提交 | 验证 |
|---|---|---|---|
| 12 KiB 载荷闸门 | 上游给模型看的浏览器结果封顶 12 KiB，用一条确定的阶梯裁：先丢尾部的 `elements` 行（记下剩几条），再按 `safePrefixEnd` 把文本字段切成**不劈开代理对**的前缀并写上续读偏移，最后还超就塌成只留身份字段的最小信封。本仓库此前两级都没有——各动作在页面脚本里各切各的（10000/15000 字符），而没走这两条路径的结果（`execute_js` 的裸返回、收集行列表、backbone 转储）**完全没有上界**。搬过来时补了两处：文本级的一半（裸文本从来不是 JSON 信封，用同一条预算与同一条代理对规则），以及把分页键加进最小信封白名单（否则被裁的读取会丢掉续读偏移，等于断头路） | `8ea484a0` | ✅ `BrowserPayloadLimiterTest`（13 例：阶梯两条腿、代理对、最小信封、`serialize` 契约、`boundText` 标记与预算下限） |
| 分页读取 | 上游 `readPage` 的 `offset`（0–200000）与 `max_chars`（256–12000，默认 8000）照搬；页面脚本按同一套算术把文档（上限 200000 字符）切成窗口，返回 `text_length`/`returned_chars`/`offset`/`next_offset`/`truncated`/`source_truncated`。模型看到的结果头因此从「Text (10000 chars)」变成「Text (chars 0-8000 of 34821; next_offset=8000)」或「… end of document」——此前被裁的页面和短页面长得一模一样，模型没法知道还有下文 | `8ea484a0` | ✅ `BrowserTextWindowPolicyTest`（10 例：两端钳制、中段/末段/越界/空文档窗口、三种表头） |
| 闸门只装一处 | 上游是每个动作都过 `toolResult()`；本仓库的浏览器结果此前有多个出口（JS 评估三条、骨架、cookie、标签页）。现在 `BrowserTabPool.execute` 是唯一出口（动作本体拆成 `executeUnbounded`），标签页列举与不产信封的文本路径和页面读取走同一条预算；JSON 路径另在评估处先过阶梯，所以结构化结果优先按行丢、而不是被盲目切头 | `8ea484a0` | ✅ 上述用例 + 全量单测 |

✅ 的定义：该分支上 `:app:compileDebugKotlin` + `:app:testDebugUnitTest` 通过（2273 个用例 = 上一项后的 2250 + 23）与 `:app:lintDebug`（0 error）。**没有任何设备结论**：真实页面 200000 字符 `innerText` 的开销、重 DOM 上窗口算术经 WebView 桥的稳定性、以及模型会不会照着 `next_offset` 续读，都未验证。

**get_readable 走上游的 Markdown 采集器（含共享 DOM 前导脚本）** — 同一分支 `codex/eta-phase6-xposed`：

| 项 | 内容 | 提交 | 验证 |
|---|---|---|---|
| 共享 DOM 前导脚本 | 上游把 DOM 助手集中在一个前导里（`wrap(body)` 把动作体拼进同一作用域）：有界字段/URL/选择器助手、可见性判定（hidden / inert / aria-hidden / display / visibility / clip / opacity 0 / 视口外）、绝对 URL 解析、选择器生成，以及按**节点数 + 截止时间 + 字符数**三重上界的可见文本采集。本仓库此前每个动作各写一段内联 JS，可见性只看 `display`，也没有节点/时间预算 | `a5e657d5` | ✅ `BrowserDomScriptsTest`（9 例） |
| readable 输出 Markdown | 上游的 `readable` 不是「取第一个匹配容器 + 空格归一」：它先按**可见文本长度给候选打分**（最大的那个当正文），再用发射器输出 Markdown——标题、列表（有序带编号）、链接 `[label](href)`、图片、表格（≤60 行 × 12 列）、代码块与引用，并且跳过 nav/form/button 这类镶边；整棵树受节点 8000、750 ms 截止与文档 200000 字符约束。本仓库此前拿到的是压成一整段的纯文本，链接的 href 全部丢失，正文不在候选表里的页面会读到导航栏 | `a5e657d5` | ✅ 上述用例 |
| 注入脚本的语法守卫（新增） | 注入脚本是 Kotlin 原始字符串，Kotlin 编译器看不见里面的语法——少个括号只会在真机上表现为「动作静默失败」。新增 `scripts/test_browser_js_syntax.py`：把两个浏览器源文件里的脚本抽出来、按编译器的方式解开模板、逐个交给 `node --check`（没装 node 就跳过并说明），已接进 CI 的 unit tests 作业 | `a5e657d5` | ✅ 本机 node 22：13 个脚本全过；注入一处语法错误时守卫退出 1（反向验证过） |

✅ 的定义：该分支上 `:app:compileDebugKotlin` + `:app:testDebugUnitTest` 通过（2282 个用例 = 上一项后的 2273 + 9）与 `:app:lintDebug`（0 error）。**没有任何设备结论**：真实页面上发射器产出的 Markdown 长什么样、重 DOM 上三重上界够不够、以及按字数打分会不会选错正文容器，都未验证。

**find_elements 交出选择器而不是标签** — 同一分支 `codex/eta-phase6-xposed`：

| 项 | 内容 | 提交 | 验证 |
|---|---|---|---|
| 每行都是一个 `describe()` | 我们此前每行只有 tag + 80 字符文本 + href、**必须**给选择器、最多 20 行：模型能看到写着「登录」的按钮，却没有名字可用，后续点击只能拿文本去猜选择器，而 20 行还会截掉控件密集页面的尾巴。上游的 `findElements` 对每个活元素跑共享的 `describe()`：**验证过唯一性**的选择器（唯一就直接给，否则退到有界的 `nth-of-type` 路径）、role / aria-label / placeholder / type、以及视口像素盒子。选择器现在**可以省略**——省略就是列页面上的可交互元素（上游默认表 `a,button,input,textarea,select,[role=button],[role=link],[contenteditable],[tabindex]`） | `11d40229` | ✅ `BrowserElementListFormatterTest`（10 例）+ `BrowserDomScriptsTest` 增 3 例 |
| 边界照上游 | 扫描上限 3000 个匹配、16 行、500 ms 截止，`truncated` 时不装作列全了（行尾写明「页面匹配到的比这里多，扫描提前停了」）。行的排版收在纯函数 `BrowserElementListFormatter` 里并单独有用例：缺字段就当缺（不会印出字符串 `null`）、行内文本无法从自己的引号里逃出来、旧的 `{count, elements:[{rect}]}` 形状（`execute_js` 自建信封）仍然能排版 | `11d40229` | ✅ 上述用例 + 全量单测 |

✅ 的定义：该分支上 `:app:compileDebugKotlin` + `:app:testDebugUnitTest` 通过（2295 个用例 = 上一项后的 2282 + 13）与 `:app:lintDebug`（0 error）。**没有任何设备结论**：生成的选择器在页面跳转后是否仍然唯一、以及几千个匹配的页面上 500 ms 扫描预算的实际表现，都未验证。

**wait_for_selector：等指定的元素，而不是等「页面看起来不动了」** — 同一分支 `codex/eta-phase6-xposed`：

| 项 | 内容 | 提交 | 验证 |
|---|---|---|---|
| 新动作 | 我们此前只有 `wait_for_dom_stable`（两次采样 body 长度相同就宣布稳定）——在点一下才渲染的页面上这是猜：转圈时变更会暂停，而下一步要点的元素可能还没出现。上游等的是调用方**点名**的东西：`wait_for_selector` 每 250 ms 问一次页面侧 `selectorState`，直到出现**可见**匹配（只检查前 2000 个匹配，所以折叠菜单里那份隐藏的不算数），默认 5 s、钳制 0.5–30 s，并回报匹配是否 `enabled`（马上要点的控件是禁用状态，值得当面说清） | `51591354` | ✅ `BrowserSelectorWaitPolicyTest`（5 例）+ `BrowserDomScriptsTest` 增 2 例 |
| 失败关闭与边界 | 非法选择器在**第一次轮询**就失败（页面侧 `querySelectorAll` 抛错、包装层把错误交回来），不会白等满预算；结果文案与预算收在 `BrowserSelectorWaitPolicy`（数值是上游的，措辞是本仓库的）。`selectorState` 本体是上游的，进共享前导脚本 | `51591354` | ✅ 上述用例 + `scripts/test_browser_js_syntax.py`（14 个脚本） |
| 顺带修掉的自造缺陷 | 本片暴露出我们自己的一处描述错误：工具 schema 告诉模型 `wait_for_dom_stable` 的 `timeout` 单位是**秒**，而动作按**毫秒**钳制——模型填 `timeout: 10` 实际只等了 1 秒。schema 与参数表都改成毫秒，并接受上游的 `timeout_ms` 拼写（guest CLI 同步加了 `--timeout-ms`） | `51591354` | ✅ 编译 + 全量单测 |

✅ 的定义：该分支上 `:app:compileDebugKotlin` + `:app:testDebugUnitTest` 通过（2302 个用例 = 上一项后的 2295 + 7）与 `:app:lintDebug`（0 error）。**没有任何设备结论**：页面正在导航时 250 ms 轮询的实际表现、以及「可见但尚未绘制」的元素在这台 WebView 上会不会被算作可见，都未验证。

**click / type 点名目标，不能写就拒绝** — 同一分支 `codex/eta-phase6-xposed`：

| 项 | 内容 | 提交 | 验证 |
|---|---|---|---|
| 目标解析收在一处 | 我们此前 click 与 type 各自 `document.querySelector`，坐标路径另写一份 `elementFromPoint`；上游的 `resolveTarget(selector, x, y)` 一个入口两种寻址，找不到就抛类型化错误。现在三个动作（含 hover）共用它，选择器按 JSON 转义后传入而不是拼进单引号字符串 | `7c99bef6` | ✅ `BrowserDomScriptsTest` 增 6 例 |
| 事件带真实坐标、先滚进视口 | 我们的事件一直是 `clientX/clientY = 0`——读坐标的框架（画布、拖拽面、地图）会把这种点击当成噪声丢掉；而且目标在下折叠时直接在视口外派发事件。现在先 `scrollIntoView({block:'center'})` 再按元素中心派发（事件序列仍是我们那套，多点 mouseover/enter/leave/out） | `7c99bef6` | ✅ 上述用例 |
| 不能点的拒绝，不能写的拒绝 | 禁用/惰性/隐藏的目标用上游的 `enabled()` 判据**拒绝**而不是报告「已点击」；type 补上上游的 `editable()`（readonly、disabled、inert、不收文本的 input 类型）——这正是「Typed 7 chars into #search」这句话成不成立的分界：老代码会往只读或禁用字段里写值、把事件发完、然后报告成功，而页面一个事件都没理 | `7c99bef6` | ✅ 上述用例 |
| 命中报告与 submit | click / type / hover 的结果带上与 `find_elements` 同一形状的 `describe()` 对象（`detail()` 复用同一个排版函数），点错节点在对话记录里看得见；type 增加上游的 `submit`（有表单走 `form.requestSubmit()`，没有就在字段里敲 Enter）。原生 setter、逐键事件与 Angular/Vue 兼容层仍是我们那套 | `7c99bef6` | ✅ 上述用例 + 全量单测 |

✅ 的定义：该分支上 `:app:compileDebugKotlin` + `:app:testDebugUnitTest` 通过（2307 个用例 = 上一项后的 2302 + 5，另有一例在自查中删掉）与 `:app:lintDebug`（0 error）。**没有任何设备结论**：带坐标的事件能否取悦真正依赖它们的框架、以及真实页面的提交处理器如何对待 `requestSubmit`，都未验证。

**自己标签页的历史，三个动作** — 同一分支 `codex/eta-phase6-xposed`：

| 项 | 内容 | 提交 | 验证 |
|---|---|---|---|
| `go_back` / `go_forward` / `reload` | 上游把三者当动作；我们的枚举里一个都没有——模型顺着链接点进去之后想回头，只能重新 navigate 一个凭记忆的 URL（记错就彻底走丢），而标签页自己的前进/后退状态只有浏览器面板能用。现在三个都是动作，并且**等落地的页面**再回话（navigate 与 reload 本来就在等） | `dafa3b58` | ✅ `BrowserHistoryPolicyTest`（4 例） |
| 没有历史就拒绝 | 没有上一页时返回带原因的失败（`Cannot go back: this tab has no earlier page in its history.`），而不是我们自己 UI helper 那种静默 no-op——从工具结果看回来，no-op 和「页面变了」长得一模一样 | `dafa3b58` | ✅ 上述用例 |
| 顺手收掉重复的等待 | `navigate`、`reloadAndWait`、`loadBlankPage` 各自带一份「建 deferred → 挂超时 → await → 清标志」，新动作会让它变成第四份。现在共用 `awaitNavigation(label, trigger)`；超时日志也统一（此前只有 navigate 会记下是哪个导航超时），`loadBlankPage` 不再要求调用方在主线程 | `dafa3b58` | ✅ 编译 + 全量单测 |

✅ 的定义：该分支上 `:app:compileDebugKotlin` + `:app:testDebugUnitTest` 通过（2311 个用例 = 上一项后的 2307 + 4）与 `:app:lintDebug`（0 error）。**没有任何设备结论**：落在缓存页上的历史移动会不会在等待超时前报出 `onPageFinished`、以及被拦截的 `minis://` 页面 reload 的表现，都未验证。

**scroll 的位置证据与 page_info 的语言** — 同一分支 `codex/eta-phase6-xposed`：

| 项 | 内容 | 提交 | 验证 |
|---|---|---|---|
| `before` / `after` 与可见性检查 | 我们的滚动结果只报落点，「到底动没动」得靠模型记住上一次读数；上游报起始与结束两个位置。点名元素现在必须可见（选择器命中 `display:none` 的节点此前会「滚了个寂寞」还报成功）。**我们自己的内层滚动容器搜索保留**——app 型页面滚的是 div，只滚 window 会静默无效——并改为用 `selectorFor` 报出它选了哪个容器 | `ca3a78e9` | ✅ `BrowserDomScriptsTest` 增 3 例 |
| 语言与 canonical | `get_page_info` 补上游的 `language` 与 `canonical_url`，并把 `scroll_x` 补齐；保留我们的 `ready_state` / `forms` / `links` / `images`，内容尺寸**取真实值**而不套上游的 200000 px 上限（被截断的数字调用方看不出来） | `ca3a78e9` | ✅ 上述用例 |
| 守卫自身的缺陷 | 两个脚本搬进共享前导脚本时，守卫抓到了新 scroll 体里的**嵌套 Kotlin 模板**被自己的非贪婪正则替换成垃圾——这正是它存在的意义。守卫改为按花括号配平替换模板，并重新做了反向验证（注入语法错误仍然失败） | `ca3a78e9` | ✅ 13 个脚本解析通过 + 注入式反向验证 |

✅ 的定义：该分支上 `:app:compileDebugKotlin` + `:app:testDebugUnitTest` 通过（2314 个用例 = 上一项后的 2311 + 3）与 `:app:lintDebug`（0 error），另有 `node --check`（13 脚本）、文档溯源与构建清理守卫全绿。**没有任何设备结论**：内层容器搜索在真实 app 型页面上是否挑到用户会滚的那个元素、以及页面平滑滚动时 `before/after` 是否仍然可读，都未验证。

**read_image：模型可以不要那张图** — 同一分支 `codex/eta-phase6-xposed`：

| 项 | 内容 | 提交 | 验证 |
|---|---|---|---|
| `read_image`（默认 true） | 上游的 `screenshot` 有这个入参：截图是否附进模型上下文。我们此前**一律附**——只想拿页面几何的调用方、以及并不想看图的点击（本仓库在视觉类动作后会自动补一张截图）都要为那张图付上下文。现在 `read_image=false` 时图仍然落盘给用户（缩略图、artifact、`minis://` 路径都在），模型只拿元数据；它同样覆盖视觉类动作后自动补的那张，因为那才是附图的大头 | `97742f8e` | ✅ `BrowserActionInputTest`（5 例：默认值、显式 false、随点击生效、`timeout_ms`/`timeout` 两种拼写、未知动作不臆造） |
| 顺手删掉一条死路 | `ChatViewModel.executeBrowserUse` 与其 `BrowserToolResult` 全仓无调用方，而且是**绕开工具路径**的第二个浏览器入口（不落 artifact、不走无视觉模型占位、不认这些新参数）。工程合同要求「替换旧实现后删除死路径」，故删除 | `97742f8e` | ✅ 编译 + 全量单测 |

✅ 的定义：该分支上 `:app:compileDebugKotlin` + `:app:testDebugUnitTest` 通过（2319 个用例 = 上一项后的 2314 + 5）与 `:app:lintDebug`（0 error），另有浏览器 JS、构建清理与文档溯源守卫全绿。**没有任何设备结论**：`read_image=false` 时聊天气泡里的缩略图在真机上是否仍然可用，未验证。

**get_text 与 get_readable 用同一个采集器** — 同一分支 `codex/eta-phase6-xposed`：

| 项 | 内容 | 提交 | 验证 |
|---|---|---|---|
| 两条读路径合一 | `get_text` 此前读 `document.body.innerText`——「什么算可见文本」交给浏览器渲染器决定；而 `get_readable` 早已走移植过来的共享采集器（按可见性过滤文本节点、跳过 script/style/noscript/template/svg/canvas/iframe、12000 节点与截止时间）。同一个页面两次读法可能给出不同结果。现在 `get_text` 也走采集器，保留它已有的分窗契约（`offset`/`max_chars`、`text_length`、`returned_chars`、`next_offset`、`truncated`、`source_truncated`），并照上游报出 `visited_nodes` 与所用选择器；标题保留，因为我们的结果是散文而不是信封 | `6d852c42` | ✅ `BrowserDomScriptsTest` 增 2 例（并断言不再出现 `innerText`） |
| 死代码 | `BrowserUseJS` 只剩它自己的脚本（`getBackbone`、`fetch`）：`getText` 移走后 `jsQuote` 失去最后一个调用方，一并删除；选择器字面量改由共享前导脚本 JSON 转义（上游的规则） | `6d852c42` | ✅ 编译 + 全量单测 |

✅ 的定义：该分支上 `:app:compileDebugKotlin` + `:app:testDebugUnitTest` 通过（2321 个用例 = 上一项后的 2319 + 2）与 `:app:lintDebug`（0 error），另有浏览器 JS 守卫（12 个脚本）。**没有任何设备结论**：采集器的输出与 `innerText` 在真实页面上差多少（相邻文本节点之间的空格是要盯的那一处），未验证。

**增强设置页 + Root 状态** — 同一分支 `codex/eta-phase6-xposed`：

| 项 | 内容 | 提交 | 验证 |
|---|---|---|---|
| `RootAccess`（上游 `agent/device/RootAccess.kt` 移植） | 本仓库此前**知道**怎么找 `su`、怎么经由它执行，但没有任何地方**记住**结论——每个需要它的界面要么自己再探一次、要么假设。状态机（UNKNOWN/UNAVAILABLE/NOT_GRANTED/GRANTED/DENIED/TIMED_OUT）、「只问一次」策略（`explicit \|\| !attempted \|\| wasGranted`）与探测实现照搬，但探测走**本仓库自己的 `DirectRootRunner`**（同一条 root 路径，不做第二套）。一处刻意差异：`initialize` 只查 su 是否存在，除非上一次探测拿到过授权才静默复探——上游在首次启动就请求 root，本仓库让运行时启动（或用户在页面点「检测」）来触发弹窗 | `222f404c` | ✅ `RootAccessPolicyTest`（10 例：策略真值表、uid 0/非 0、超时、启动失败、静默成功不臆断、只有 GRANTED 算授权） |
| 增强设置页 | Phase 6 路线图里一直未落地的那一页：状态区（Root 状态 + 检测按钮、无障碍保活、模块开关计数，各自链到拥有该开关的页面）+「需要 Root 的能力」「需要模块的能力」两节说明。**刻意不显示「模块已连接」**——模块跑在别的进程里、只读偏好，应用没有把手去问，猜一个状态就是编造 | `222f404c` | ✅ 编译 + `lintDebug`（0 error、未新增 warning）+ 8 个 locale 补齐 27 条字符串 |
| 无障碍判定的唯一副本 | 权限页与新页面都要「无障碍服务是否开启」，此前是权限页里的私有函数；提到 `MinisAccessibilityService.isEnabled`，两页不可能给出不同答案 | `222f404c` | ✅ 上述验证 |

✅ 的定义：该分支上 `:app:compileDebugKotlin` + `:app:testDebugUnitTest` 通过（2331 个用例 = 上一项后的 2321 + 10）与 `:app:lintDebug`（0 error，145 warning / 5 hint 与改前一致）。**没有任何设备结论**：已授权设备上的静默复探会不会真的不弹窗、su 弹窗与后台探测如何交互、以及没有 su 的设备上这一页的观感，都未验证。

**MCP：参数声明为请求头（`x-mcp-header`）** — 同一分支 `codex/eta-phase6-xposed`：

| 项 | 内容 | 提交 | 验证 |
|---|---|---|---|
| 参数→请求头映射 | 现代 MCP schema 允许把某个工具参数标注为「以 HTTP 头传递」（`x-mcp-header`），这是服务器要求把密钥放在带外的方式。本仓库此前**完全不读这个扩展**，要求它的服务器永远收不到值。移植上游 `McpToolHeaders`：递归走 `properties`、只认 string/integer/boolean、头名必须匹配 token 字符集、整数要求精确且在 ±2^53-1 内（`1.5` 与超界值跳过而不是四舍五入）、类型不符跳过、缺参或 JSON null 跳过；值以 `Mcp-Param-` 前缀发出，非可打印 ASCII（或首尾带空白、或本身长得像包装）套 `=?base64?...?=` | `b3047921` | ✅ `McpToolHeadersTest`（12 例：前缀、未标注、布尔与精确整数、越界/分数、类型不符、缺参与 null、嵌套路径、非法头名与不支持类型、ASCII 直通、包装与再包装、解码回原文） |
| 接线与优先级 | 会话在 `tools/list` 时按 schema 建一次绑定表，`tools/call` 前抽取后交给传输；HTTP 传输把参数头加在协议头与 `Authorization` **之前**（工具参数顶不掉会话凭据——上游同样的次序），stdio 传输忽略它们 | `b3047921` | ✅ `MCPHttpTransportParamHeaderTest`（2 例，MockWebServer 断言落点与优先级） |

✅ 的定义：该分支上 `:app:compileDebugKotlin` + `:app:testDebugUnitTest` 通过（2343 个用例 = 上一项后的 2331 + 12）与 `:app:lintDebug`（0 error，145 warning / 5 hint 与改前一致）。**没有任何设备结论**：要求 `x-mcp-header` 的真实服务器如何对待包装形式、以及没有被要求时收到 `Mcp-Param-` 前缀头会不会拒绝，都未验证。

**工具 wire 名的防碰撞（自查出来的缺陷）** — 同一分支 `codex/eta-phase6-xposed`：

| 项 | 内容 | 提交 | 验证 |
|---|---|---|---|
| 截断不再让两个工具同名 | 发给 provider 的 `tools[].name` 必须匹配 `^[a-zA-Z0-9_-]{1,64}$`，本仓库早已据此把 `mcp.<server>.<tool>` 的点换成下划线并截到 64 字符。**截断本身**是漏洞：两个前 64 字符相同的工具（例如同一服务器上两个很长的远端工具名）会拿到**同一个** wire 名，而本地 registry 正是用 wire 名反查工具——模型的调用会被静默派发到后注册的那个工具。现在超长名以**规范名的 SHA-256 前 4 字节**结尾（上游 `modelToolName` 出于同样理由也用 digest 尾缀），短名一字不变 | `633b02b6` | ✅ `AgentToolDefinitionApiNameTest`（7 例：短名不动、点变下划线、超长恰为 64 且合法、共享前缀的两个长名不再相同、同输入稳定、dispatch 仍认规范名与 wire 名） |
| 纯符号名 | 只由标点构成的名字（`。。。`）会 sanitize 成 `___`：合法，但完全无法区分两个工具。这类名字改用 `tool_<digest>`，同样确定性 | `633b02b6` | ✅ 上述用例 |

✅ 的定义：该分支上 `:app:compileDebugKotlin` + `:app:testDebugUnitTest` 通过（2350 个用例 = 上一项后的 2343 + 7）与 `:app:lintDebug`（0 error，145 warning / 5 hint 与改前一致）。**设备结论**：无（这是命名算术，wire 名本来就被 provider 接受）。

**MCP 工具 schema 的上界（自查出来的缺口）** — 同一分支 `codex/eta-phase6-xposed`：

| 项 | 内容 | 提交 | 验证 |
|---|---|---|---|
| 单工具与整次列表两道闸 | 远端 `inputSchema` 是**不可信输入**，而本仓库会把它打进**每一次** provider 请求：此前只有「单个回复 4 MiB」这一道闸，一次回复就能塞进近 4 MiB 的 schema，再乘上最多 50 页的分页——坏掉或恶意的服务器于是能让我们在内存与下一个请求里扛着几 MB 的 JSON。现在每个 schema 有单件上限（64 KiB），一次 `tools/list` 保留的 schema 总量另有上限（1 MiB）；**放不下的工具保留但不带 schema**（仍可调用，只是无类型——这是 MCP 服务器接受的状态），既不硬扛也不从列表里消失 | `753dcecc` | ✅ `McpToolSchemaBoundsTest`（8 例：单件放行/超限丢弃/空与缺失、解析时保留工具丢 schema、总量预算放行到满、超额不消耗预算、零长度不占额、默认值与既有 4 MiB/256 工具的刻度相容） |
| 与上游的差异 | 上游同样上界了回复（1 MiB）、页数（8）与工具数（128），但 **schema 本体没有上界**——这一半是本仓库自己的缺口，不属移植；数值按本仓库既有刻度（4 MiB 回复、256 工具）取 | `753dcecc` | ✅ 上述用例 + 全量单测 |

✅ 的定义：该分支上 `:app:compileDebugKotlin` + `:app:testDebugUnitTest` 通过（2358 个用例 = 上一项后的 2350 + 8）与 `:app:lintDebug`（0 error，145 warning / 5 hint 与改前一致）。**没有任何设备结论**：真实 schema 就有那么大的服务器，面对「被以无类型形式提供」会如何反应，未验证。

**MCP 工具结果不再能灌满上下文（自查出来的缺口）** — 同一分支 `codex/eta-phase6-xposed`：

| 项 | 内容 | 提交 | 验证 |
|---|---|---|---|
| 超限结果落盘、给指针 | MCP 工具结果此前直接进消息历史，头上只有传输层的「单回复 4 MiB」——那不是上下文预算，一个话多的服务器就能把几 MB 文本塞进下一个 provider 请求。现在超限结果经 `ContextOffload.spillIfOversized` 落到会话的 offloads 目录（与日志工具同一条路），模型拿到**头尾预览 + `/var/minis/offloads` 路径**，可以用 `file_read` 带 offset/limit 回去读全文，而不是拿到一截无法追回的正文 | `6dfb8046` | ✅ `MCPToolResultBoundsTest`（5 例：落盘结果用其预览、小结果原样、落盘失败时用带省略标记的裁剪预览、未落盘的 SpillResult 被忽略、内联预算远低于传输上限） |
| 补上 `SpillPolicy` 缺失的接线 | `SpillPolicy`（随 Minis for Android 导入的 DeepSeek Harness `dsh-spill-policy` 契约，**不是 Eta**）早已在仓库里，但**全仓只有一句工具描述提到它**，没有任何调用方——也就是说这条策略此前没人真正执行。本片把 MCP 这条路径接上；其余工具路径（shell/日志等）本来就有各自的有界输出 | `6dfb8046` | ✅ 上述用例 + 全量单测 |

✅ 的定义：该分支上 `:app:compileDebugKotlin` + `:app:testDebugUnitTest` 通过（2363 个用例 = 上一项后的 2358 + 5）与 `:app:lintDebug`（0 error，145 warning / 5 hint 与改前一致）。**没有任何设备结论**：真实远端服务器返回超大结果时，落盘路径（写入会话 offloads 目录）在设备上的表现未验证。

**MCP stdio 的行上限改成「读的时候就上界」（自查出来的缺口）** — 同一分支 `codex/eta-phase6-xposed`：

| 项 | 内容 | 提交 | 验证 |
|---|---|---|---|
| 64 KiB 从「事后检查」变成「过程上界」 | `MCPStdioTransport` 原来在 `BufferedReader.readLine()` **之后**才检查行长——可是 readLine 会先把整行读进内存，所以这个上限只是装饰：本地服务器（可能坏掉、也可能不可信）发一行就能让进程花掉几百 MB。现在读取走 `MCPBoundedLineReader`：边读边卡上限、保留 `readLine` 的 CRLF 与空行语义、连「一直没有换行的洪流」也会被拒；被灌爆的 stdout 帧直接关掉整条传输（杀进程）而不是试图在行中间重新同步；stderr 用同一个读取器配更小的上限，超限就跳到下一个换行（有界），既不放大日志也不让服务器卡在满管上 | `e737fb4e` | ✅ `MCPBoundedLineReaderTest`（8 例：逐行与 EOF、末行无换行、CRLF、空行、恰好到限、超限即拒且报来源与数值、无换行的 5 MB 洪流、跳过后仍能接着读） |

✅ 的定义：该分支上 `:app:compileDebugKotlin` + `:app:testDebugUnitTest` 通过（2371 个用例 = 上一项后的 2363 + 8）与 `:app:lintDebug`（0 error，145 warning / 5 hint 与改前一致）。**没有任何设备结论**：真实 stdio MCP 服务器在帧被这样拒绝后的行为（传输已关闭，下次使用由 provider 的 reload 重连）未验证。

**MCP 协议头与一条死路（自查）** — 同一分支 `codex/eta-phase6-xposed`：

| 项 | 内容 | 提交 | 验证 |
|---|---|---|---|
| 补上 `MCP-Protocol-Version` 等路由头 | 流式 HTTP 规范要求 `initialize` 之后**每个请求**都带 `MCP-Protocol-Version`，上游也确实每次都带；我们此前只发 `Mcp-Session-Id`，严格执行规范的服务器会直接拒掉 `tools/list`/`tools/call`。现在会话把协商到的版本交给传输，随请求发出，并一并带上 `Mcp-Method` 与 `Mcp-Name`（名字走 `x-mcp-header` 那套同一个头编码器，非 ASCII 工具名按规范的 base64 形式发出）；这些头**加在调用方头之后**，工具参数顶不掉协议路由——上游的次序也是这个道理 | `fd4f2198` | ✅ `MCPHttpTransportParamHeaderTest` 增 2 例（初始化前不带版本、设置后逐请求携带且 `Mcp-Method`/`Mcp-Name` 落位；非 ASCII 工具名被包装成 base64 并可解回） |
| 删掉无人调用的第二套派发 | `MCPProvider.callRemoteTool` 与只为它存在的 `context` 字段/入参：全仓没有任何调用方（真正的派发走 `ToolRegistry` → `MCPToolHandler`），留着是一条没人维护的并行路径 | `fd4f2198` | ✅ 编译 + 全量单测 |

✅ 的定义：该分支上 `:app:compileDebugKotlin` + `:app:testDebugUnitTest` 通过（2373 个用例 = 上一项后的 2371 + 2）与 `:app:lintDebug`（0 error，145 warning / 5 hint 与改前一致）。**没有任何设备结论**：严格要求路由头的服务器，在我们多带了它自己客户端不会带的头时如何反应，未验证。

**工具结果的唯一上界（自查出来的缺口）** — 同一分支 `codex/eta-phase6-xposed`：

| 项 | 内容 | 提交 | 验证 |
|---|---|---|---|
| 派发收口处加一道闸 | 此前每个工具各自负责「别印太多」：浏览器有载荷闸门、MCP 处理器会落盘、shell 走自己的 retainer——但**一个只是印得多的工具**（数据库转储、日志读取、guest 里 `cat` 一个文件）会原样进 transcript、再原样进下一次 provider 请求。上游把这件事收在**一处**（runtime wire 上每条结果封 64 000 字符）；本仓库的等价收口是 registry 派发——结果正是在那里从「工具私事」变成「消息部件」。现在 `ToolResultBudget` 就在那里：50 KiB 以内原样通过，超了经 `ContextOffload` 落盘（与日志工具、MCP 处理器同一条路）给模型「预览 + `/var/minis/offloads` 路径」以便 `file_read` 回读，落盘失败退到 `ToolResultPruner` 的「头 + 省略标记 + 尾」。自己已经有界的工具远在预算之下，不会重复落盘 | `a17b51b5` | ✅ `ToolResultBudgetTest`（5 例：预算内同一对象返回、恰好到预算不落盘、超限用落盘预览且保留其它字段、落盘失败用带标记的裁剪预览、落盘报告未落盘时同样退到裁剪预览） |
| 顺带修正的测试常识 | 预算是**UTF-8 字节**：第一版用例用 18 000 个 ASCII 字符当「超大」，离 50 KiB 还远，根本走不到落盘分支——测试当场把这个误解暴露出来（两例失败），改成按字节构造后才真正覆盖 | `a17b51b5` | ✅ 上述用例 |

✅ 的定义：该分支上 `:app:compileDebugKotlin` + `:app:testDebugUnitTest` 通过（2378 个用例 = 上一项后的 2373 + 5）与 `:app:lintDebug`（0 error，145 warning / 5 hint 与改前一致）。**没有任何设备结论**：真实超大工具结果走落盘路径（写入会话 offloads 目录）在设备上的表现未验证。

## 四点五、收敛：可安装 APK 与模拟器验证（2026-09-19）

目标改为「收敛成完整可用的 APK + 模拟器与真机测试」之后，本节的证据都是**构建产物与设备实测**，不是源码推断。

| 项 | 结果 | 证据 |
|---|---|---|
| 构建 | `:app:assembleDebug` 通过，产物 `src/android/app/build/outputs/apk/debug/app-debug.apk`，**84 275 371 字节（80.4 MiB）**，包名 `llc.slacker.eta`（与正式 Minis 并存安装） | 构建日志 + `ls -la` |
| 16 KB 对齐 | `scripts/verify-android-16k.sh` 通过：**24 个 native 库**全部 16 KB 对齐 | 脚本输出 |
| 模拟器安装与启动 | Android **16（API 36, x86_64）** 模拟器 `openime-review-api36` 上 `adb install -r -t` **Success**；`am start MainActivity` 启动后 `dumpsys window` 焦点为本应用，**crash buffer 为空、无 FATAL、无本包名下的 E 级日志** | adb 输出 + logcat 扫描 |
| 界面实测（截图留档在 /tmp，仓库不存二进制） | ① 聊天主界面：输入框占位「Message Minis (@ to mention files)」、模型选择器「选择模型」、麦克风/发送按钮、IME 正常唤起；输入框占位符轮播生效（截到第二句提示）——Phase 1 的轮播在真机上确实在转。② 设置页：`minis://settings/...` 深链直接打开设置。③ **新做的「系统增强」页**：Root 行显示「No su binary on this device」+ Check 按钮（模拟器无 su，初始化探测如实回报），无障碍保活行「Off — nothing re-binds the service」，模块开关行「1/3」（与 `ModulePrefs.BOOLEAN_DEFAULTS` 里手势条默认开启一致），下方「WHAT NEEDS ROOT」分区正常渲染。④ 点 Check：状态不变、无崩溃——无 su 时探测在 `findSu()` 处短路，符合设计 | `/tmp/emulator-minis-start.png`、`/tmp/emulator-enhance-2.png`、`/tmp/emulator-enhance-3.png`、`/tmp/emulator-enhance-4.png` |
| 代码检查（同一提交上重跑） | `:app:testDebugUnitTest` **2385 例 0 失败**；`:app:lintDebug` **0 error**（145 warning / 5 hint，与改前一致） | 本轮输出 |
| 真机 | **未完成**：`adb devices` 只有模拟器，重启 adb server 与 `adb mdns services`（只发现模拟器自身的 10.0.2.16:5555）都没有物理设备。手机接上后可直接复用本节步骤：`adb install -r -t <apk>` → `am start` → logcat 崩溃扫描 → 逐页截图 | adb 输出 |
| Release APK | **本环境做不出**：仓库自己的 `requireReleaseSigning` 要求 `RELEASE_*` 生产签名凭据且**明确禁止**回退 debug 签名；`scripts/verify-android-release.sh` 还会拒绝任何 CN=Android Debug 的产物。需要你提供签名环境变量（或密钥库）才能产出并验证 release 包；在此之前可安装可用产物是上面的 debug APK | `build.gradle.kts`、`verify-android-release.sh` |

### 真机一轮（2026-09-19，小米 24129PN74C / HyperOS / Android 37 / KernelSU Next）

手机接上后按上表步骤跑，**抓到两个真问题并当场修掉**，另外补上了 runtime payload：

| 项 | 结果 |
|---|---|
| 启动崩溃（升级安装） | 首次安装即 **ACRA 抓到 `IllegalStateException: Migration didn't properly handle: characters`**：`MIGRATION_20_21` 建了 `index_characters_updated_at`，而 `CharacterEntity` 没声明该索引 → Room 校验迁移后的库失败、应用起不来。**模拟器全新安装看不到**（走建表不跑迁移）。已在实体上声明该索引（并发导出的 `schemas/…/22.json`），重装后无 ACRA、无迁移错误、焦点回到本应用（`f40ec5ed` 前一片：`4d3eefde`） |
| root 探测在真机上的误解 | 修复崩溃后，「系统增强」页仍显示「这台设备没有 su」——但手机装了 KernelSU Next。从**应用自己的 UID** 实测：`/system/bin/sh` 能执行、`/system/bin/su` 是 ENOENT（管理器对未授权应用隐藏 su），所以「没有 su」对本进程为真、对用户毫无帮助。新增 `RootManagerDetector`（KernelSU Next / KernelSU / APatch / Magisk，走清单 `<queries>` 按名查询），Root 行改为「su 对本应用不可见；本机装有 KernelSU Next——请在它的应用列表里允许本应用（或关闭 su 隐藏）」。你在管理器里授权后，同一台机器上 `run-as llc.slacker.eta /system/bin/su -c 'id -u'` 返回 **0**、页面即可检测到授权（`f40ec5ed`） |
| runtime payload | 此前 APK **没有** `assets/minis-runtime/…`（`dist/` 为空），Linux 运行时起不来。已跑 `scripts/build-runtime-payload.sh`：Ubuntu Base **24.04.3**（上游 sha256 与仓库 pin 双向校验）→ 叠加 minis 目录布局 → 确定性打包，产物 `dist/ubuntu-arm64-rootfs.tar.gz` **28 902 272 字节**，`verify-runtime-payload.sh dist` 通过；另跑 `scripts/build-root-network-proxy-android.sh` 补回代理 payload（NDK 28.2.13676358 / aarch64-linux-android / API 26） |
| 完整 APK | `:app:assembleDebug` 后 `app-debug.apk` = **115 219 575 字节（109.9 MiB）**，内含 `assets/minis-runtime/ubuntu-arm64-rootfs.tar.gz`（内嵌 sha256 与 manifest 一致）、25 个 native 库全部 16 KB 对齐（`verify-android-16k.sh`）、`verify-runtime-payload.sh <apk>` 通过；真机 `adb install -r -t` **Success**、启动无崩溃 |
| 模拟器对照 | API 36（x86_64）模拟器同一 APK 安装/启动/设置页/系统增强页均正常；**升级与全新安装两条路径现在都有证据** |
### guest 运行时在真机上跑通（2026-09-19）

| 项 | 结果 |
|---|---|
| 终端入口缺失（真机发现） | 手机上**没有任何办法打开终端**：设置页只声明了 `onTerminalClick` 而没渲染对应的行，会话菜单没有该项，`minis://terminal` 深链也没有任何流程会产生——于是能启动 Linux 运行时的只剩「agent 调用需要它的工具」，而没配 provider 的用户走不到。已在 `AGENT 运行时` 分区顶部补上「Minis Shell」一行（副标题 8 语言），装到手机后入口出现（`9e9c0d93`） |
| 运行时实跑证据 | 点该行后终端打开并且 **guest 真的起来了**：提示符 `minis@localhost:/$`，在终端里执行 `id` → `uid=10186(minis) gid=10186(minis) groups=10186(minis)`（**App 真实 UID、supplementary groups 已清空**，与合同一致），`uname -a` → `Linux localhost 6.6.118-android15-… aarch64`（chroot 共享宿主内核），PTY 桥 `libpty_bridge.so` 装载 |
| 一处诚实的边界 | 这次启动用的是设备上**已存在的共享 runtime state**：`/data/adb/minis/rootfs` 601 MB、时间戳 2026-09-09/13（另一款安装早先 provision 的），不是本次 APK 内 payload 现场解包的产物。按合同该目录就是 Root-owned、可替换的运行时状态，所以复用是设计行为；**本 APK 的 payload 解包路径尚未单独验证**（需要把该目录挪开再启动，会动到另一款应用的运行时状态，故留待你确认后做） |
### 真机逐页扫描（2026-09-19，同一台手机）

用 uiautomator 逐行点开设置里的每个入口，每次点击后重抓界面文本并检查 **crash 缓冲**：

| 入口 | 打开后看到 | 崩溃 |
|---|---|---|
| 管理提供商 | 「未配置 AI 服务商」（诚实的空状态） | 无 |
| 模型组 | 「暂无模型分组」 | 无 |
| Token 用量 | 「总用量」 | 无 |
| 桌面宠物 | 「还没有选择宠物」 | 无 |
| 技能 | Search skills | 无 |
| Characters | Characters | 无 |
| 人格 | 「预览」 | 无 |
| 系统提示词 | 「自定义系统提示词」 | 无 |
| 记忆 | 「默认」 | 无 |
| MCP 集成 | 「EXPOSE MINIS」 | 无 |
| 子代理委派限制 | 自身页面 | 无 |
| 模块设置 | 「手势」 | 无 |
| 系统增强 | 「状态」 | 无 |
| 日志 | 「权限」 | 无 |
| 存储 | 自身页面 | 无 |
| 共享文件夹 | 「共享」 | 无 |
| 挂载外部文件夹 | 「未授予『所有文件访问权限』」——缺权限时如实说明，而不是装作能用 | 无 |
| 关于 Minis | 「Minis for Android」 | 无 |
| Minis Shell（本轮新增入口） | 终端 + guest 运行时（见上一节） | 无 |
| 备份与恢复 | 「恢复」 | 无 |
| 权限 | 自身页面 | 无 |
| 内置浏览器（会话菜单 →「打开浏览器」） | 浏览器底部面板打开：会话标签「Example Domain」、`1/3`、地址栏；WebView 进程启动（`com.google.android.webview:sandboxed_process0`），在地址栏输入 `example.com` 回车后**页面真的加载出来**（标题 Example Domain、`https://example.com/`、正文渲染完整） | 无 |

**crash 缓冲在整个扫描过程中保持为空**（每轮开始都单独清 `-b crash`，第一轮那批「CRASH」是清缓冲方式不对造成的误报，已修正后重跑）。设置里 19 个入口全部点开过；界面标题是用 uiautomator 抓文本得出的，个别条目因抓取时序显示成上一页的标题（例如「权限」那行）——**崩溃判定只依赖 crash 缓冲与 logcat，不依赖标题文本**。

**共享 rootfs 复用的结论（读代码得出，不是猜）**：`RootfsManager.checkHealth`/`evaluateProbeOutput`/`validateMetadata` 判定「健康」的条件是**布局完整 + 元数据兼容**（distro=ubuntu、24.04.x、arch=arm64、profile=base、`upstream_sha256` 是合法 64 位十六进制），并要求 `bin/sh` 或 `bin/bash` 可执行；它**不要求**该 rootfs 与当前 APK 的 payload 逐字节相同。也就是说：别的安装（或别的 App）早先 provision 出来的 rootfs 只要兼容就会被复用，这正符合合同里「`/data/adb/minis/rootfs` 是 Root-owned、可替换的 runtime state」。payload 解包路径因此不是「没生效」，而是「本轮没被触发」。

| 剩余待测 | 请求链路（新装实例还没有 provider 配置）；release 签名包（需要 `RELEASE_*`）；payload 解包路径（需要把共享 rootfs 挪开，会动到另一款应用的运行时状态，等你确认）；设置里最后两页（备份与恢复 / 权限） |

### 真机上的调试 RPC 测试面（2026-09-19，本轮新增用法）

App 自带调试 JSON-RPC（`DebugServer`，`127.0.0.1:5321`，仅 debug 构建启动），本轮把它当成**没有模型也能驱动工具层**的仪器用起来，方法：

```
TOKEN=$(adb shell run-as llc.slacker.eta cat files/debug_server_token)   # 只读，别回显
adb forward tcp:5321 tcp:5321
curl -H "X-Minis-Token: $TOKEN" -H 'Content-Type: application/json' \
     -d '{"jsonrpc":"2.0","id":1,"method":"debug.appInfo","params":{}}' http://127.0.0.1:5321/
```

（鉴权要求每个连接都带 token，loopback 也不例外——这是仓库自己的设计；token 每次安装生成，文档也写明用 `run-as` 读。）

| 调用 | 结果 |
|---|---|
| `debug.appInfo` | `sdkVersion 37`、`androidVersion 17`、设备 24129PN74C、`filesDir` 341 MB |
| `debug.permissions.list` | 工具权限表（calendar/location/clipboard… 的默认与当前级别） |
| `debug.mcp.status` | `running:false, configured:false, port:18789` |
| `debug.shellExecute`（`id; uname -m; head -1 /etc/os-release`） | **`uid=10186(minis) gid=10186(minis) groups=10186(minis)`、`aarch64`、`PRETTY_NAME="Ubuntu 24.04.3 LTS"`** —— guest 运行时经**工具路径**（不只是终端 UI）确认 |
| `debug.shellExecute`（200 000 字符输出） | 回来的 JSON 只有 **50 097 字符**：这条原始路径上也有 ~50 KiB 上界，不会把大盘输出整个塞回调用方 |
| `debug.shellExecute`（中文 + `uname -m`） | `你好\naarch64`：UTF-8 过桥无损 |
| guest CLI `minis-browser-use navigate --url https://example.com`（`/usr/local/bin/`） | 导航成功、`tab_id: 0`、viewport 412x914、自动截图落到 `/var/minis/browser/` |
| `debug.browser.listTabs` / `pageInfo` | tab 0 / url / title / selected；pageInfo 里是**本轮移植的新字段**：`language: en`、`canonical_url: null`、`ready_state: complete`、`content_width/height`、`scroll_x/y` |
| `debug.browser.getText` | **分窗表头生效**：`Text (chars 0-127 of 127; end of document):` + 采集器文本（Example Domain/正文/Learn more），走的是共享可见文本采集器而不是 `innerText` |
| `debug.browser.getReadable` | **Markdown 生效**：`# Example Domain` 标题 + 段落 + `[Learn more](https://iana.org/domains/example)` 链接 |
| `debug.browser.executeJS`（`return document.title + ' | links=' + document.links.length;`） | `Example Domain | links=1`（脚本必须显式 `return`，这是该动作的既有契约） |
| `debug.browser.screenshot` | 返回 base64 JPEG |

**结论**：这一轮移植/对齐过的浏览器层（page_info 字段、`get_text` 分窗、`get_readable` Markdown + 链接、载荷/结果上界）在真机上是**端到端可复现**的，不是只在单测里成立。后续设备测试优先用这套 RPC 面，UI 点击仅用于验证界面本身。

### 真机：guest CLI 清点、网络抓取、UI 树与 **MCP 服务端**（2026-09-19）

| 调用 | 结果 |
|---|---|
| `ls /usr/local/bin` | 24 个 guest CLI：`android-a11y-cli / android-alarm / android-calendar / android-clipboard / android-contacts / android-device / android-location / android-notification / android-open / android-photos / android-player / android-shizuku-cli / android-speak / android-speech / android-weather / minis-browser-use / minis-config / minis-debug / minis-model-use / minis-open / minis-scheduled / minis-sessions-cli` 与 `xdg-open` 等价物 |
| `debug.fetch {url, maxBytes}` | 通（两个后端各报一次状态：`httpurlconn_status 200`、`okhttp_status 200`，并回 DNS 与代理判定） |
| `debug.viewTree {maxDepth:3}` | 返回实时视图树（DecorView 1200×2670 起） |
| **MCP 服务端** | `debug.mcp.start` 无凭据 → `{started:false}`（失败关闭）；配 token 后 `{started:true}`、`running:true`；`POST 127.0.0.1:18789/mcp` 完成 `initialize`（`2025-06-18`、`minis 0.1.0`）与 `tools/list`：**58 个工具**；无 Bearer → `401`；需要审批的工具返回 `-32001 confirm_required`（票 + 120 s），用**规范名** `android.context` 批准后，带票原样重试 → `200、isError:false`，返回 `battery/foreground/location/network/ok/screen/time` 等字段。客户端契约（端点路径、票的位置、方法与参数绑定）已写进 `docs/issue-34-mcp-server-exposure.md` 的「Device evidence」一节 |
| 凭据清理 | 测试用的 token 通过 `run-as` 把 `shared_prefs/minis_mcp_prefs.xml` 还原为 `<map />` 清掉（设备状态复原） |

**设备测试带出的一处报错质量缺陷（已修 + 真机复验）** — `5cd19e1c`：

| 项 | 内容 |
|---|---|
| 现象 | 经 MCP 调 `linux_file_list {"path": "/workspace"}` 得到 `Error: list failed: mcp`——「mcp」是 MCP 服务端给工具传的 **sessionId**，而该会话的目录还不存在；`SecureFileAccess.withParent` 抛出的 `java.nio.file.NoSuchFileException("mcp")` 的 `getMessage()` 恰好只有那个目录名，于是整句报错只有这个裸名，调用方（模型或脚本）无从判断发生了什么 |
| 修法 | 目录遍历把「组件缺失」翻译成 `Failure("NOT_FOUND", "path is not available in the guest namespace: <走过的路径>")`、「组件不是目录」翻译成 `NOT_DIR`——与 `deleteEntryIfPresent` 已有的写法一致；有界路径的既有报错（`BAD_PARAMS: path is outside the Minis guest namespace or unavailable: .`）不受影响 |
| 真机复验 | 同一调用：修复前 `list failed: mcp` → 修复后 `list failed: NOT_FOUND: path is not available in the guest namespace: mcp`；`--limit/--offset` 等参数与边界用例不变 |
| 验证口径 | `:app:compileDebugKotlin` + `:app:testDebugUnitTest`（**2390 例 0 失败**，含 `SecureFileAccessTest` 新增用例）+ `:app:assembleDebug` 后装机复跑；本片未动 UI/资源/清单，按验证矩阵未跑 lint |

### 真机：guest 只读 CLI 与其余调试面（2026-09-19 续）

| 调用 | 结果 |
|---|---|
| `android-device info / battery / storage` | 三项都 exit 0 并返回结构化 JSON：`info` 给出 android_version/board/brand/device/hardware/manufacturer/model/product/sdk_level 与内存；`battery` 给出 charging/health/level_percent/power_source/status/temperature_celsius；`storage` 给出 app_cache_mb/app_data_mb/internal_free|total|used_gb 与 note |
| `android-clipboard` 往返 | `set --text <marker>` → `Copied 26 characters to clipboard.`；`get` 回读 26 字符且含 marker；**`get --json` 返回 `{"text":…,"chars":26,"truncated":false}`——正是本分支剪贴板有界那一片引入的形状**；最后 `clear` 把剪贴板恢复为空（设备状态复原） |
| `minis-sessions-cli list` | `{ok:true, count:0, sessions:[]}`（新装实例，如实为空） |
| `minis-config list` | 明确报错 + 用法：`invalid_args / Unknown subcommand 'list'. Use --help.`（不吞掉错误） |
| `debug.logs.list` | 2 个文件：`launch-beacon.log`（384 B）与 **`crash-2026-09-19_03-41-14.log`（30 840 B）**——那是修复前的迁移崩溃，应用把它留档了 ✓；`debug.screenshot.list` → `{count:0}` |
| 权限门控（观察，未改） | `android-notification list`、`android-calendar list --today`、`android-location current` 三项都因**设备上等待用户授权的对话框**而在 120 s 超时结束；其中日历是系统运行时权限弹窗、定位是应用自己的说明弹窗（“Minis needs location permission…，CANCEL / OPEN SETTINGS”）。**我没有替你批准任何个人数据授权**，而是点了「拒绝 / CANCEL」走失败关闭路径。可改进点（未做，属产品取舍）：调用方最终只看到 `command timed out after 120000ms`，没有任何线索说明「屏幕上有个权限弹窗在等你」——对交互式用户是正常等待，对无人看守的调用则容易变成盲目重试 |

**下一步（收敛路径）**：① 在真机上启动 guest 运行时（终端/环境页）并观察 provision 结果；② 若你给出 `RELEASE_*` 凭据，则产出并验证 release APK；③ 每次改动后重复「单测 + lint + assembleDebug + verify-runtime-payload + 16k + 模拟器与真机冒烟」这条链。


### 升级安装的第二处崩溃：Room 身份哈希（2026-09-19，模拟器发现，两机复验） — `0b94af54`

| 项 | 结果 |
|---|---|
| 现象 | 模拟器上应用**每次启动即退出**（三次，ACRA 各自落盘）：`files/logs/crash-2026-09-19_04-51-46 / 04-51-58 / 04-59-38.log` 全是 `IllegalStateException: Room cannot verify the data integrity … Expected identity hash: f827a661e72dcc535735beed70f6f416, found: 8ab8a11f432e9c3c3fe63e2b09177772`。这不是旧崩溃没修好，而是**修复本身带出来的第二处**：`4d3eefde` 在实体上补声明索引，改变了 Room 为**第 22 版**算出的身份哈希，版本号却没动，而 `checkIdentity` 跑在迁移之前——于是「修复前构建写过的库」被「修复后的构建」永久拒绝打开 |
| 两台设备正好是两种形态 | 模拟器库是修复前**全新安装**时在 22 建的：走建表路径、`MIGRATION_20_21` 从未执行，所以身份是 `8ab8a11f` 且**物理上连索引都没有**（只有 `sqlite_autoindex_characters_1`）；真机库是修复后构建迁移出来的：身份 `f827a661`、索引在。同一个 APK，只有模拟器崩 |
| 修法 | 数据库版本 22 → 23 + `MIGRATION_22_23`（`CREATE INDEX IF NOT EXISTS index_characters_updated_at ON characters(updated_at)`）：版本差让 `checkIdentity` 让开，索引语句把两种形态收敛到同一形状（缺的建出来、已有的成 no-op），迁移后 Room 再校验声明 schema 并重写身份。导出 `schemas/…/23.json`——身份仍是 `f827a661`，声明形状没变，只有版本号变了 |
| 复验（模拟器） | 就着那台**正在崩溃循环**的库直接 `adb install -r -t`：启动干净、`mCurrentFocus` 回到 `MainActivity`、crash 缓冲为空、`files/logs/` 无新文件；库变成 `user_version=23`、身份 `f827a661`、`index_characters_updated_at` 存在 |
| 复验（真机） | 同一 APK 装到小米 24129PN74C（HyperOS / Android 37，库为 22/`f827a661` 那一形态）：启动干净、crash 缓冲为空、`user_version=23`、索引在 |
| 验证口径 | `:app:testDebugUnitTest` **2390 例 0 失败**（`DatabaseVersionGuardTest` 现在读 `23.json`）+ `:app:assembleDebug` |

### 模拟器 RPC 扫面、真机截图/搜索/检查、root 授权复验（2026-09-19，同一 APK）

| 调用 | 结果 |
|---|---|
| 模拟器 `debug.appInfo` / `debug.permissions.list` | `sdkVersion 36`、`ubuntu:false`（该机没有 root，如实上报）；14 个工具的 `defaultLevel`/`currentLevel` 全表返回 |
| 模拟器 `debug.screenshot.capture` / `list` / `clear` | `scale=0.3` → 10 588 B PNG；`list` 回 1 条；`clear` → `cleared:1` |
| 模拟器 `debug.viewTree` / `search` / `inspect` | 实时树（DecorView 1080×2400 起）；关键词「设置」4 命中；首个地址 `inspect` → `SemanticsNode role=Image description=设置` |
| 模拟器 `debug.shellExecute` | **失败关闭**：`{"output":"ubuntu unavailable: su executable not found","exit_code":1}`——没有 su 的机器不会假装 guest 可用 |
| 真机 `debug.screenshot.capture` / `get` | `scale=0.5` → 28 962 B PNG；`get` 回 38 616 字符 base64（PNG 头 `iVBORw0KGgo`）、`encoding=png` |
| 真机 `debug.search` / `inspect` | 「设置」4 命中（`SemanticsNode`），`inspect` 返回 description/role/bounds |
| 真机 root 授权复验 | KernelSU Next 里放行后，**新装的这一版**：`run-as llc.slacker.eta /system/bin/su -c 'id -u'` → **0**；同一版本经 `debug.shellExecute` 进 guest → `uid=10186(minis) gid=10186(minis) groups=10186(minis)`、`PRETTY_NAME="Ubuntu 24.04.3 LTS"`、内核 `6.6.118-android15` |
| 产物校验 | 同一 APK：`verify-runtime-payload.sh`（rootfs `06dcdf94…`）与 `verify-android-16k.sh`（25 个 native 库）通过；`:app:lintDebug` **0 error**（145 warning / 7 hint，全部改前既有） |

### 权限门控不再盲等：快速失败 + 指名道姓的错误（2026-09-19，真机与模拟器复验） — `2372ea47`

| 项 | 结果 |
|---|---|
| 之前的问题（设备发现） | `android-notification list`、`android-calendar list --today`、`android-location current` 三条都在等屏幕上的授权提示，调用方 120 s 后只看到 `command timed out after 120000ms`——门控自身允许 120 s（系统弹窗）+ 5 s（补授轮询）+ 120 s（应用内设置门），所以**调用方的命令超时总是先到**，结构化结果根本没机会返回 |
| 改法 ①：没有宿主就不问 | 弹窗只能由 MainActivity 承载：`onStart`/`onStop` 注册 `setPermissionHostAttached()`，并**删掉 ChatScreen 里那份重复的 `RequestMultiplePermissions` 启动器**（它只在聊天页被组合时存在，且用 `any` 判断「已授权」——对读写双权限的请求是错的）。没有宿主时门控立刻返回 `NO_UI` |
| 改法 ②：一条预算 | 整条交互门控（系统弹窗 + 补授轮询 + 设置门）共用 90 s 预算，落在 guest CLI 自己的 120 s 命令超时之内，调用方一定拿得到结构化结果 |
| 改法 ③：一处实现、一处文案 | `requestPermissionFlow()` + `permissionFailure()` 取代六个 handler 与两个工具各抄的一份；失败体带 `error`（`permission_required` / `permission_denied` / `permission_timeout`）、`tool`、`permissions` 和一句「用户要做什么」。没有运行时权限的能力（Notification access）保留自己的错误码，并在 `detail` 里写清它的设置入口 |
| 真机复验（前台、有人应答） | `android-calendar list --today`：系统授权框 → 点「拒绝且不再询问」→ 补授轮询 → 应用内「Calendar permission needed / CANCEL / OPEN SETTINGS」→ 点 CANCEL → CLI 及时返回 `{"error":"permission_denied","tool":"android-calendar","permissions":["android.permission.READ_CALENDAR"],"message":"The user declined …"}`，exit 77 |
| 真机复验（后台、无人应答） | 同一台机器按 HOME 后逐条调用：location / notification / calendar **各约 0.7 s** 返回 `permission_required` 并点名权限（改前是 120 s 的无信息超时） |
| 验证口径 | `:app:testDebugUnitTest` **2396 例 0 失败**（新增 `OffloadPermissionFailureTest` 6 例：各结果的失败体、能力专用码、detail、无宿主 2 s 内返回）+ `:app:lintDebug` 0 error + `:app:assembleDebug` + `verify-runtime-payload.sh` / `verify-android-16k.sh`；同一 APK 在 API 36 模拟器安装启动、调试面可用、crash 缓冲为空 |
| 设备状态变化（如实记录） | 复验时在系统弹窗上点的是「拒绝且不再询问」，所以这台小米上「日历」权限现为永久拒绝（可在系统设置里重新打开）。本轮没有替你批准任何个人数据授权 |

## 五、待办阶段（顺序与规格见 `docs/analysis/eta-port-program.md`）

| 阶段 | 内容 | 来源 |
|---|---|---|
| Phase 2 底层 AI | 已落地：请求头过滤与请求体合并、引用格式化、Responses opaque output 回放、UI 坐标空间契约、`read_image` 直读相册、服务端联网搜索开关（按条目）与它的活动行。屏幕观察的其余合同 Minis 侧本就更强，未再移植；工具能力投影与终态门在本仓库由既有 schema/证据机制覆盖 | Eta `agent/model/*` |
| Phase 3 数字助手 | 就地展示/可停止/可接管已落地；Skills 暴露给模型、GUI 动作补齐、Markdown 导出同样已落地。**连续追问与面板内屏幕上下文未落地**：无头驱动 seam 其实**已经存在**（本仓库早有 `agent/AgentRunner`：prompt/cancel/waitForSettle/sessionEvents），卡的是面板设计——上游是一套 708 行的展开式面板（26 态状态模型 + `BasicTextField` 追问输入 + 手势/震动），直接搬会替换掉本仓库现有的胶囊浮层设计（当初的分析明确要保留 Minis 的工作台风格），属于要先拍板的产品改动；若要做，最自然的形态是在现有胶囊上加密实输入（需处理 overlay 窗口的 IME/焦点） | Eta `agent/voice`、`agent/overlay`、`agent/tool` |
| Phase 4 个人上下文 | 清单已全部落地：通知历史、会话历史、闹钟/计时器（含列表）、设备环境、照片/视频/音频/文档检索、验证码读取、设备开关、App 冻结、剪贴板历史、健康摘要、QQ/微信聊天图片缓存、下载记录。其中 QQ/微信缓存与下载记录先被登记为「待拍板 / 不值得」，后来按上游补齐（限制写在各自工具描述里） | Eta `agent/tool/AgentPersonal*Tools.kt`、`agent/device/*` |
| Phase 5 角色系统 | 本阶段清单已在 `codex/eta-phase5-roleplay` 落地：角色卡模型/编解码/PNG 承载、世界书（含草稿编辑与编辑界面）、宏展开与兼容说明、存储层与迁移、会话绑定、逐轮注入、剧情记忆与记忆工具、角色库/详情界面。Eta 侧仅剩 `RoleplayMessageState`（多候选回复修订状态，23 行），本仓库的重新生成是自己那套，未移植 | Eta `agent/roleplay/*` |
| Phase 6 厂商入口接管 | 已落地：libxposed 接入、HyperOS 手势条识屏/电源键/桌面导航条长按、ColorOS SystemUI 的 OCR 长按、Google 资格补齐、系统 contextual search 的启动门与放行名单、无障碍保活（后端 + App 侧开关 + 接入恢复流程）、热词自愈、ColorOS 记忆（只读桥 + 三个工具）、ColorDirect 双指识屏、ColorOS 便签/录音/摘要检索、增强设置页（root 状态 + 模块开关聚合）。未落地：小布、超级小爱（两者都要先定「被注入进程如何驱动本 App 的 agent」这条通道，Eta 用的是它自己的跨进程 runtime 客户端，本仓库合同不做第二套 runtime 协议）、QQ/微信聊天图片（读他人私有缓存，待拍板） | Eta `hook/*`、`ModuleMain.kt` |

## 六、明确排除

| 项 | 理由 |
|---|---|
| PRoot / Alpine / 多发行版安装器（不引入） | 与「单一 Direct Ubuntu 24.04 chroot」合同冲突 |
| systemizer（把 Google App 装成系统应用） | 扩大 Root 面，超出「Root 只做受控基础设施」边界 |
| 厂商私有内部类实现（HyperOS/ColorOS 内部方法） | 跨版本不稳定，且无法在宿主复现验证 |
| Gemini 浮窗语音补偿（Eta `GoogleAppHooks` 的浮窗那一半） | 用户 2026-09-18 明确表示不需要：原先落地的版本（`e818b8cf`）连同文件、注册与归属登记一并移除；同一文件的机型档案与资格那一半保留 |
| 第二套跨进程 runtime 协议 / 终态 outbox | 单进程应用以 Room + ViewModel 为真源，重复实现会造成两套状态源 |
| 在线商店类分发面 | 产品定位与服务端依赖不在本仓库范围 |

**上游区域清点结论（2026-09-19）** — 逐目录扫过 Eta 之后，除上表明确排除者，剩余未移植文件都属于下面几类之一，**不是漏搬**，后续不要重复扫：

| 上游区域 | 结论 |
|---|---|
| `agent/terminal/*`（AnsiSgr、TerminalScreenBuffer、各种 EnvironmentInstaller/ProotCommandBuilder 等） | ① PRoot/Alpine/各发行版安装器 —— 合同明确排除；② `AnsiSgr`+`TerminalScreenBuffer`（终端**解释** SGR 并着色、472 行屏幕缓冲）—— 本仓库的终端/工具输出在**源头**就把 ANSI 剥成纯文本（`TerminalSanitizer`，模型侧也必须纯文本），要着色等于改消息存储 + UI 渲染，属产品决策而非缺能力；③ 其余（Supervisor/FileExplorer/PackageProfiles/BusyBox 等）本仓库有等价物（JobRegistry/TerminalSession/rootfs+apt/Ubuntu coreutils） |
| `agent/device/*` 余项（AgentFileReferenceGateway、RootAccess、RootCommandEnvelope、DeviceLocationProvider 等） | 文件引用网关依附 Eta 的 composer 附件 UX（本仓库的文件模型是 guest 工作区 + 共享/挂载目录）；RootAccess/RootCommandEnvelope 由 `PrivilegedCommandRunner` + argv 校验覆盖；位置由 `android.location.get` / `android.context` 覆盖 |
| `agent/tool/*` 余项（AgentImageTools、AgentBrowserToolCatalog、terminal 工具目录等） | 图像工具由 `ReadImageTool`（50 MiB 读上限 + 2000px 缩放 + JPEG q85）覆盖，编码体积由缩放天然有界，上游的 12 MiB 编码上限在此路径上是死检查；浏览器工具与终端工具是本仓库自己的 `browser_use`、guest CLI |
| `agent/runtime/*`、`agent/voice/*`、`agent/overlay/*`、`ui/*` | runtime 是 Eta 的跨进程 agent 协议（本仓库合同不做第二套）；voice/overlay 见「浮层连续追问」待拍板项；UI 是本仓库刻意保留自有设计系统 |
| `agent/media/AgentModelImageEncoder`、`AgentImageCodec` | 同上：本仓库的解码/缩放/编码是一条有界路径，不需要第二个编码器 |

**上游文件级核对（2026-09-19，机械核对，可复跑）** —— 上面那张表是逐目录的判断，这张是把 Eta 的每个 Kotlin 文件对到本仓库：

方法：`for f in $(find <eta>/app/src/main/kotlin -name '*.kt'); do basename`，逐个查本仓库有无同名文件；没有同名的，再在各文档里找该名字是否已被点名。复跑所需的上游快照是 `Mangi-11/Eta @ c15de97`（本机副本 `/tmp/eta-upstream-clone`）。

结果：上游 **389** 个 Kotlin 文件，其中 **317** 个在本仓库没有同名文件；这 317 个里 **97** 个已被文档点名，其余 **220** 个按目录归类为：`ui/*` 90（自有设计系统）、`agent/runtime` 23（跨进程协议，合同不做第二套）、`agent/terminal` 22（发行版安装器属于明确排除项，ANSI 着色属待拍板项，见上表）、`agent/model` 19 与 `data/*` 30（本仓库自己的 provider/模型/Room/仓库层，同名不同名而已）、`agent/voice` 7 与 `hook/xiaoai` 5 与 `hook/breeno` 3（待拍板）、其余为各目录零散同名不同实现者。

本轮核对新增的两条结论（都不需要移植）：

- **provider 推理参数**：上游 `ProviderReasoning`/`ReasoningCapabilityResolver` 是按厂商源码类型硬编码的请求字段映射（bailian/siliconflow/deepseek/moonshot/mimo/minimax/openrouter/stepfun/openai/custom）。本仓库这条线是**数据 + 逐实例规则**驱动：`ThinkingLevelCatalog`（models.dev 的 `reasoning_options`）、`thinking/ThinkingRuleResolver` 与 `ThinkingWireFormat`（`reasoning_effort`/`reasoning_effort_nested`/`boolean_toggle` 等线格式，已含 DashScope 的 `enable_thinking`+`thinking_budget` 一类特例）。把上游的硬编码表搬过来只会多一份真相，故不搬。
- **工具能力/需求声明**：上游 `AgentToolRequirements`/`AgentToolCapabilities`/`ToolCapabilityProjection` 是**给 UI 用的投影**（按 root/ColorOS 过滤工具卡、挑按钮动作）。本仓库没有工具卡列表，工具在调用时就带原因拒绝（例如桥不可用、参数越界），功能面等价，故不搬。
- **MCP 的运行期形态**：上游把 MCP 工具当一等模型工具，并给每次 run 冻结一份工具目录 + bearer token（`McpRunContext`/`McpRunSnapshot`），设置改动下一次 run 生效；工具目录还带 TTL 缓存（`McpServerManager.discover`）。本仓库这条线是 iOS 镜面的形态：服务在系统提示里披露 + guest `minis-mcp-cli` 发现/调用，另有一层把远端工具注册进 `ToolRegistry`（每次请求按当前注册表出 schema）。中途改设置会让后续请求看到新的工具表，最坏情况是模型调用一个刚消失的工具、拿到明确的报错，而不是状态损坏。**这一处按形态保留**；`x-mcp-header` 那一块（可独立成立的能力）已在 `b3047921` 搬过来。

**曾经未落地、现已落地**：Phase 6 的「增强设置页」（上游 `SystemEnhanceScreen`：root 状态 + 模块状态 + 各接管说明）在 `222f404c` 落地（见 §四 最后一片）。

## 七、未验证清单（不得据此声称设备结论）

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
