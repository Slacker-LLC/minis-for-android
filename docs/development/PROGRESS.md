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

## 二、已完成（分支 codex/eta-phase1-ui，待合入 main）

分支基于 `24ba1db`；下列四项均已落地，验证级别见最后一列。

| 项 | 内容 | 提交 | 验证 |
|---|---|---|---|
| Phase 1-1 工作过程收束 | 连续 thinking + tool 块收束为一条可展开行（扳手或运行中工具图标 + 「正在执行第 N 步 · 工具」/「已完成 N 个步骤」/「第 N 步失败 · 原因」）；运行中自动展开、结束自动折叠、手动点击优先；`stepsPresentation = grouped`（默认）/ `perTool` 保留原逐条 pill 与长按菜单 | `34a6026` | ✅ |
| Phase 1-2 助手首页 | 问候语 + 2×2 可配置快捷卡（分析当前屏幕 / 打开微信 / 浏览网页 / 查看内存压力）；入口挂在会话列表顶部且列表仍为主面；可设为启动页（独立开关，不改 `launch_session` 既有语义） | `955cfa8` | ✅ |
| Phase 1-3 流式观感 | 未闭合 Markdown 虚拟补齐投影（只用于渲染、终态校验投影输入等于原文、非追加输入重建基线、投影会丢已确认内容时失败关闭）、并发解析管线、显现进度单调钳制；压缩点内联标记与冻结块缓存沿用既有实现，未改 | `da0729b` | ✅ |
| 归属登记 | `THIRD_PARTY_LICENSES.md` 增补 5 个 Eta 移植模块（工作过程分组、流式投影、解析管线、复位记账、显现算法） | `bd2f983` | 文档：`test_docs_provenance.py` + `check_docs_provenance.py` 通过 |

✅ 的定义：该分支上 `:app:compileDebugKotlin` + `:app:testDebugUnitTest` 通过（1841 个用例 = 既有 1786 基线 + 新增 55 个纯逻辑用例）。
新增用例覆盖：分组边界（text/info/media 断组、隐藏思考不建行、空组拒绝）、失败原因取值与截断、`stepsPresentation` 解码回退；未闭合围栏/链接/表格候选与非法分隔符、终态投影校验、非追加重建基线；显现进度钳制与回退禁止、字素边界（组合音标/ZWJ/旗帜/CRLF）、帧预算；解析管线跳过被取代目标与改写丢弃；助手首页动作编解码与问候时段。

## 三、待办阶段（顺序与规格见 `docs/analysis/eta-port-program.md`）

| 阶段 | 内容 | 来源 |
|---|---|---|
| Phase 2 底层 AI | Responses 完整支持（opaque output 回放、服务端 `web_search`、引用格式化）、工具能力投影、屏幕观察契约、文件视觉、请求头与请求体合并 | Eta `agent/model/*` |
| Phase 3 数字助手 | 助手浮层面板、GUI 动作补齐、Skills 暴露给模型、会话级编辑 | Eta `agent/voice`、`agent/overlay`、`agent/tool` |
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
