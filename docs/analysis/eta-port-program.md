# Eta 移植项目路线图（新仓库）

本仓库是 Slacker-LLC/minis-for-android（GPL-3.0）与 Eta 能力移植线（分支
`codex/eta-capability-integration`，tip `5b625f0d`）合并后的新起点：初始提交即包含 24 个移植提交
的全部成果。长期行为边界仍以 `docs/contracts/` 为准，本文件只记录开发顺序与验收口径。

许可保持原样：仓库主体 GPL-3.0，移植自 Eta 的模块按 PolyForm Noncommercial 1.0.0 使用与分发；
归属集中在 `PROVENANCE.md`、`THIRD_PARTY_LICENSES.md` 与 `third_party/eta/LICENSE`，代码文件内不加许可头。

## 一、现状结论（决定“跟谁学”）

`/tmp` 之外的对照证据见 `docs/analysis/eta-agent-runtime.md`、`eta-skills-mcp-backup.md`、
`eta-ui-gui-root.md` 与 `eta-integration-plan.md`。

- **对话可读性 → 学 Eta**：思考与工具步骤收束成一条「已完成 N 个步骤」行（默认折叠、运行中自动展开、
  点开内联面板、失败原因写在折叠行）；压缩点内联标记；逐字显现；未闭合 Markdown 投影；冻结块缓存 +
  行级异步解析。
- **工作台能力 → 保留 Minis**：会话列表优先 + 宽屏分栏、工具 pill 长按菜单（重跑/复制/停止）、详情抽屉、
  危险操作审批与提问、消息反馈、终端与模型选择、自有设计系统（Material3 + 组件库 + 玻璃质感）；
  **不引入 Miuix**，避免风格割裂与额外依赖。
- **入口 → 补一块**：助手首页（问候 + 快捷动作卡），保留会话列表优先，首页挂在列表顶部并可设为启动页。
- **两套步骤呈现都保留**：默认「工作过程」收束，设置 `stepsPresentation=perTool` 时切回逐条 pill。

## 二、分阶段实施

### Phase 0 — 基线健康（已完成）
修正 `AgentContextCompactorTest` 中三个写错的期望（分组边界、`summaryCharCap` 取值、分块预算），
使 `:app:testDebugUnitTest` 全绿；实现本身与 Eta 一致，不需要改产品代码。

### Phase 1 — UI/交互轨（最高优先）
1. **工作过程收束**：把一段连续的 thinking + tool 块分组成 `WorkProcess` 行（扳手图标 +
   「已完成 N 个步骤」+ 展开箭头）；运行中自动展开、结束自动折叠、失败原因上折叠行；点开为内联面板
   （分节显示思考与各工具结果）。设置项 `stepsPresentation = grouped | perTool`，默认 grouped；
   perTool 保持现有 pill 行为与长按菜单不变。
2. **助手首页**：问候语 + 2×2 快捷动作卡（分析当前屏幕 / 打开微信 / 浏览网页 / 查看内存压力 + 可配置），
   入口挂在会话列表顶部，可设为启动页。
3. **流式观感**：逐字显现（单调进度 + 回退钳制）、未闭合 Markdown 结构投影（虚拟补齐仅用于渲染、
   终态校验原文一致、非追加输入重建基线）、压缩点内联标记、冻结块缓存与行级异步解析。
4. 保留不动：会话列表/分栏、详情抽屉、审批与反馈、终端联动、玻璃质感与现有组件库。

### Phase 2 — 底层 AI
- Responses 完整支持：`stream:true`/`store:false`、`system` 投影到 `instructions`、工具回合间
  精确回放 output items（仅内存，不进 Room/日志/归档）、终态缺 `output` 时以流内结果收尾、
  reasoning 增量与终态不重复追加。
- 服务端 `web_search` 开关（默认关）+ `url_citation` 去重转可点引用、偏移失效降级为末尾来源列表。
- 工具能力投影与终态门（`AgentToolCatalog` 家族、`ObservationReferencePolicy`、`ToolExecutionDecision`）。
- 屏幕观察契约与文件视觉（`AgentScreenObservationContract`、`AgentFileVisionToolCatalog`、
  `AgentModelImageEncoder`）。
- Provider 细节：自定义请求头过滤（协议保留字段、名称/值校验、大小写去重、日志脱敏）与请求体递归合并。

### Phase 3 — 数字助手
- 助手浮层面板：就地展示、可停止、可接管、连续追问、屏幕上下文；助手会话不再只是拉起 Activity。
- GUI 动作补齐（作为现有 `android_ui` 的新 action）：`launch_app`、`search_apps`、
  `open_system_panel`、`open_uri`、`wait_for_package`、元素定位变体。
- Skills 暴露给模型：`skills_list/read/read_resource/list_curated/inspect_github/install_from_github`
  （安装仍走既有事务与跨进程锁）。
- 会话级编辑：复制/编辑/从某轮删除/重新生成/导出 Markdown/整体导入导出。

### Phase 4 — 个人上下文
- 可检索通知历史（7 天 / 1000 条上限）、闹钟与计时器、健康摘要、录音/媒体/文件/下载检索、聊天图片、
  设备环境与上下文、会话历史检索。
- 每个来源：独立开关 + 执行前二次检查；结果按行/字节有界；敏感来源走现有脱敏策略；受保护数据走
  结构化 `root.shell`，不新增 raw 命令。

### Phase 5 — 角色系统
- 角色卡（酒馆 PNG/JSON 兼容）、人设、世界书、剧情记忆与现实记忆分离、宏替换、角色记忆工具；
  角色库/详情/编辑/世界书界面；导入导出只含设定不含私人对话。持久化新增实体与数据库迁移。

### Phase 6 — 厂商入口接管
- 先复用现状（自有 VoiceInteractionService 助手角色）；只对现有入口覆盖不到的部分引入 libxposed
  `102.0.0`：电源键、小布、超级小爱、一圈即搜、Google/Gemini 解锁；配套模块元数据、模块入口类
  （进程过滤）、清单 meta-data、release 合并规则与接线状态账本（INSTALLED/MISSING/FAILED/SKIPPED）。
- 附带无障碍保活与增强设置页。不做：systemizer、厂商私有内部类、PRoot/Alpine 与多发行版安装器。

## 三、测试与验收

- 每阶段：`flock /tmp/minis-gradle.lock ./gradlew :app:compileDebugKotlin --no-daemon --max-workers=1`；
  PR 前追加 `:app:testDebugUnitTest` 串行跑，现有 1786 个用例必须保持全绿。
- 必配否定用例：工作过程分组边界、显现进度单调与回退钳制、未闭合 Markdown 终态一致性、opaque 回放
  不落盘、引用偏移失效降级、能力裁剪拒绝、未授权个人数据拒绝与超限截断、脱敏命中、角色卡非法输入拒绝、
  Hook 进程过滤与账本状态。
- 真机清单（不做结论）：浮层在系统手势/输入法/旋转下的行为、LSPosed 作用域与厂商 ROM、通知/位置/
  相册真实数据、角色卡导入导出、逐字显现在中低端机的帧率。

## 四、假设与默认

- 步骤默认收束、设置可切逐条；加助手首页且保留会话列表优先；沿用现有设计系统。
- 安全边界不变：Root 仅受控基础设施 + 结构化 `root.shell`，敏感数据继续脱敏，不引入 PRoot/Alpine。
- 每阶段一个 PR、可独立验收；真机/Root/SELinux/OEM 结论一律标注未验证。
