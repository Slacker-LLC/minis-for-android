# Web Remote RPC 扩展：技能 / 记忆 / MCP / 定时任务

> 日期：2026-08-20
> 范围：OpenMinis Pet 分叉仓库（`OpenMinis-pet-pi-web-v2-source-2026-08-20`）
> 状态：代码与文档已完成，编译 / 出包 / 提交待最终确认

## 1. 背景与目标

Web Remote（`app/src/main/assets/remote/`）此前只通过 `/api/rpc` 暴露
`provider.*`、`chat.*`、`rpc.discover` 三组能力，网页能管模型、看会话，
但技能、记忆、MCP 服务器、定时任务仍然只能回到手机上操作。

本次改动把这些能力也接到 `/api/rpc`：

- 复用 App 已有的 `DebugRPCHandler` 分发器，不新增 REST 路由；
- 新增四组 handler，覆盖技能、记忆 / 人格（SOUL）、MCP、定时任务；
- `rpc.discover` 同步注册全部新方法，客户端可以自省；
- Web Remote 白名单放行新家族，同时**仍然拒绝**可远程操控手机的
  `debug.tap` / `debug.writeFile` / `debug.shellExecute` 等危险方法；
- 前端右侧工具面板新增 技能 / 记忆 / MCP / 定时任务 四个标签页。

## 2. 后端改动

### 2.1 新增文件

| 文件 | 覆盖方法族 |
|---|---|
| `src/android/app/src/main/java/com/openminis/app/debug/SkillRpcMethods.kt` | `skills.*` |
| `src/android/app/src/main/java/com/openminis/app/debug/MemoryRpcMethods.kt` | `memory.*`、`soul.*` |
| `src/android/app/src/main/java/com/openminis/app/debug/McpRpcMethods.kt` | `mcp.*` |
| `src/android/app/src/main/java/com/openminis/app/debug/ScheduledTaskRpcMethods.kt` | `scheduled.*` |

### 2.2 方法清单

#### 技能 `skills.*`

| 方法 | 参数 | 返回 |
|---|---|---|
| `skills.list` | 无 | `{skills:[{id,name,description,version,importSource,isEnabled,installedAt,updatedAt,useCount}]}` |
| `skills.get` | `skillId` | 列表项字段 + `body` |
| `skills.toggle` | `skillId`, `enabled` | `{ok:true}` |
| `skills.delete` | `skillId` | `{ok:true}` |

说明：创建 / 导入技能仍只在设备端进行，网页只读列表、开关、删除。

#### 记忆 `memory.*`

| 方法 | 参数 | 返回 |
|---|---|---|
| `memory.files.list` | 无 | `{files:[{name,isGlobal,modifiedDate,fileSize,preview}]}` |
| `memory.files.read` | `name` | `{name,content,isGlobal}` |
| `memory.files.write` | `name`, `content` | `{ok:true}` |
| `memory.files.delete` | `name` | `{ok:true}`（`GLOBAL.md` 不可删） |
| `memory.globalToggle` | 无 | `{enabled}` |
| `memory.setGlobalEnabled` | `enabled` | `{ok:true,enabled}` |

安全：`name` 参数拒绝 `/` 与 `..`，从入口堵住路径穿越。

#### 人格（SOUL）`soul.*`

| 方法 | 参数 | 返回 |
|---|---|---|
| `soul.get` | 无 | `{name,style,lang,body}` |
| `soul.save` | `name` / `style` / `lang` / `body`（均可选） | `{ok:true}` |

`soul.save` 只更新传入的字段，缺省字段保留原值。

#### MCP `mcp.*`

| 方法 | 参数 | 返回 |
|---|---|---|
| `mcp.list` | 无 | `{servers:[{id,note,enabled,url,command,args,env,headers,startupTimeoutSeconds,createdAt}]}` |
| `mcp.toggle` | `serverId`, `enabled` | `{ok:true}` |
| `mcp.delete` | `serverId` | `{ok:true}` |

说明：创建 / 导入 MCP 服务器仍只在设备端进行；`env` / `headers` 在
`mcp.list` 中会返回键值，前端只展示键名，不下发密钥值。

#### 定时任务 `scheduled.*`

| 方法 | 参数 | 返回 |
|---|---|---|
| `scheduled.list` | 无 | `{tasks:[task.toJson()]}` |
| `scheduled.toggle` | `taskId`, `enabled` | `{ok:true}` |
| `scheduled.delete` | `taskId` | `{ok:true}` |
| `scheduled.run` | `taskId` | `{ok:true}`（异步触发，立即返回） |

`scheduled.run` 复用 `ScheduledAgentRunner.run(..., waitForCompletion = false)`，
与设备端 UI 的「立即运行」行为一致；任务最长可跑 10 分钟，RPC 不等它完成。

### 2.3 分发与注册

- `DebugRPCHandler.dispatch` 增加上述 19 个方法的 `when` 分支；
- `DebugMethodRegistry.BASE_METHODS` 补全对应的 `MethodSpec`（描述、参数、
  returns、example），`rpc.discover` 会自动返回完整目录；
- 注册表与分发器一一对应，后续新增方法必须两处同步。

### 2.7 主代理 / 子代理（补充）

Web「模型」页新增「代理设置」卡片，可分别指定：

- **主代理（Primary）**：`provider.groups.setDefault`（沿用已有方法）；
- **子代理（Sub）**：新增 `provider.groups.setSubDefault {groupId}`，传
  `null` / 空值即清除、子任务继承主代理。

`provider.groups.list` 同步补充返回 `defaultSubGroupId`，每个组增加
`isSub` 标记；前端用「主代理 / 子代理」徽标和两个下拉框完成设置，
沿用 DeepSeek 设计 token（卡片、tag、form-field）。

### 2.8 子代理委派限制（agent.settings.*）

参考 DeepSeek Harness 的 agent 旋钮，新增：

| 方法 | 参数 | 返回 |
|---|---|---|
| `agent.settings.get` | 无 | `{maxDepth, timeoutMinutes}` |
| `agent.settings.set` | `maxDepth`（1..5）、`timeoutMinutes`（1..30） | `{ok:true, maxDepth, timeoutMinutes}` |

`SubagentTool` 从硬编码改为读取 `SubagentLimits`（SharedPreferences），
Web「代理设置」卡片里的深度与超时输入即时生效；子代理仍默认继承主代理
模型组，无独立人设，与 DeepSeek「子代理加入父代理组装」的继承模型一致。
`agent.` 前缀已加入 Web Remote 白名单。

### 2.9 提问卡片与全文搜索（chat.*）

**提问卡片**：新增模型工具 `ask_user_question`，参数
`{question, options:[{label,value,recommended?}], multiple, allowCustom, timeoutMinutes}`。
工具注册进 [QuestionCenter](src/android/app/src/main/java/com/openminis/app/tools/QuestionCenter.kt)
并挂起；Web 端轮询 `chat.question.pending`、提交 `chat.question.answer`
（`selected` / `custom` / `skipped`）后回合恢复，答案作为结构化工具结果返回给模型。
超时（默认 10 分钟）或跳过都会以明确文案告知模型，避免它反复追问。

**全文搜索**：`chat.search {query, limit?, sessionId?}` → 按会话分组的命中结果。
实现复用 `ChatRepository.searchMessages`（`parts_json LIKE` 参数化 + Kotlin 侧
文本抽取），词项 AND、字面匹配、无通配符注入；每个会话最多 3 条命中，
每条 snippet 约 200 字符。

### 2.4 Web Remote 白名单

`RemoteAccessServer.RPC_ALLOWED_PREFIXES` 当前放行：

```
provider.  chat.  rpc.discover
skills.  memory.  soul.  mcp.  scheduled.
debug.logs.  debug.crash.  debug.appInfo
```

仍然拒绝：

- `debug.tap` / `debug.scroll` / `debug.inputText` / `debug.screenshot*`（远程操控屏幕）；
- `debug.ls` / `debug.readFile` / `debug.writeFile` / `debug.rawLs`（文件系统读写）；
- `debug.shellExecute` / `debug.shizuku.exec` / `debug.modelUse.exec` / `debug.sessions.exec` / `debug.minisConfig.exec`；
- `debug.browser.*`、`debug.viewTree` / `debug.inspect` 等其它调试方法。

放行 `debug.logs.*` / `debug.crash.*` / `debug.appInfo` 只因为它们是只读诊断
信息（日志文件、崩溃报告、设备与应用元数据），不构成对手机的操控能力。

> 注意：`skills.*` / `memory.*` / `soul.*` / `mcp.*` / `scheduled.*` 是**可写**
> 面（改开关、删数据、改记忆、立即触发任务）。Web Remote 可经 Cloudflare
> Tunnel 暴露到公网，因此必须靠登录密码保护；白名单注释中保留了这段提醒。

### 2.5 静态资源路由

`RemoteAccessServer` 的静态资源路由新增：

```
/skills-tab.js    -> remote/skills-tab.js
/memory-tab.js    -> remote/memory-tab.js
/mcp-tab.js       -> remote/mcp-tab.js
/scheduled-tab.js -> remote/scheduled-tab.js
```

### 2.6 版本号

`src/android/app/build.gradle.kts`：

```diff
- versionName = "1.12-pet.1"
+ versionName = "1.12-pet.2"
```

## 3. 前端改动

目录：`src/android/app/src/main/assets/remote/`

### 3.1 文件

| 文件 | 改动 |
|---|---|
| `index.html` | 右侧工具面板新增 4 个 tab 按钮与 4 个 `<section>`；追加 4 个脚本引用 |
| `app.js` | 新增 `TAB_LOADERS` 懒加载注册表，tab 点击时按需加载对应页面 |
| `app.css` | tab 栏改为横向可滚动；新增卡片 / 列表 / 表单 / 预览 / 危险按钮样式 |
| `skills-tab.js` | 技能页（新增） |
| `memory-tab.js` | 记忆页 + SOUL 人设编辑（新增） |
| `mcp-tab.js` | MCP 页（新增） |
| `scheduled-tab.js` | 定时任务页（新增） |

### 3.2 功能

- 技能：列表（名称 / 描述 / 版本 / 来源 / 启用开关 / 使用次数）、查看
  SKILL.md 正文、启用 / 停用、删除（带确认）。
- 记忆：全局记忆开关；文件列表（`GLOBAL.md` 置顶，显示修改时间 / 大小 /
  首行预览）；点击文件在 textarea 中编辑并保存；删除（`GLOBAL.md` 隐藏
  删除按钮）；底部「人格 (SOUL.md)」表单，`soul.get` 载入、`soul.save` 保存。
- MCP：服务器列表（HTTP/SSE 显示 url，STDIO 显示 command + args）、启用 /
  停用、详情（env / headers 只显示键名）、删除（带确认）。
- 定时任务：任务列表（按 `ScheduledTask.toJson()` 字段渲染，含时间、重复
  规则、目标会话、最近执行记录）、启用 / 停用、删除、立即运行（提示已触发）。

### 3.3 前端安全

- 所有动态文本经 `esc()` 转义；
- 不引入 CDN / 外链，全部资源随 APK 从本地 assets 出；
- 无 inline script，兼容页面严格 CSP；
- 删除 / 立即运行前都有 `confirm`。

## 4. 配套文档与脚本

- `CHANGELOG-FORK.md`：新增「Web 端直接管理技能 / 记忆 / MCP / 定时任务」章节；
- `docs/specs/debug-server-api.md`：补充新方法契约；
- `README.md` / `BUILDING.md` / `BUILD-CN.md`：构建与说明同步；
- `src/android/gradle/wrapper/gradle-wrapper.properties`：`networkTimeout`
  调大到 600000ms，避免慢网下载 Gradle 超时；
- 计划新增 `scripts/build-pet-apk.ps1`：一键 `assembleDebug` 并把
  `app-debug.apk` 复制为 `OpenMinis-Pet-<version>-arm64-debug.apk`。

## 5. 验证状态

| 项目 | 状态 |
|---|---|
| 前端 4 个新 JS 语法（`node --check`） | 已通过 |
| 前端与后端 RPC 契约字段核对 | 已核对 |
| `DebugMethodRegistry` 与 dispatch 一一对应 | 已核对 |
| Kotlin 编译 `:app:compileDebugKotlin` | 待最终确认 |
| 完整 `assembleDebug` 出包 | 待执行 |
| APK 复制到仓库根 | 待执行 |
| git 提交 | 待执行 |

## 6. 后续注意事项

- `skills` / `memory` / `mcp` / `scheduled` 四个 handler 都是
  `MinisApp` 上的仓库 / 管理器直连，**不需要** PRoot 沙箱在线；
- 定时任务立即运行依赖 App 子系统就绪（`subsystemsReady()`），与设备端
  行为一致；App 处于安全模式或未初始化完成时会跳过本次触发；
- 新增方法时记得同步 `DebugMethodRegistry`，否则 `rpc.discover` 会缺项；
- 若把 Web Remote 暴露到公网，务必设置强密码，并留意上述可写方法族的
  影响范围。
