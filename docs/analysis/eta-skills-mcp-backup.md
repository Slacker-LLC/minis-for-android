# Eta 能力对照：Skills 安装与运行、MCP 客户端协议、备份与恢复

## 参考基线与法律边界

- 参考项目 Eta（第三方 Android 系统级助手，单模块 Compose 应用）：仓库 github.com/Mangi-11/Eta，
  本地只读克隆 /tmp/eta-upstream-clone，本文固定其 HEAD 为 c15de97。其许可证为 PolyForm Noncommercial
  License 1.0.0。
- 本项目：工作树 /home/jiale/projects/minis-eta-integration，分支 codex/eta-capability-integration，
  基线 origin/main = 69e05e51。
- Eta 的 PolyForm Noncommercial 1.0.0 允许按非商业目的复制、修改与派生，因此落地实现**可以直接复用其
  源文件**，再按本项目包结构、权限模型与测试边界适配。许可条款与 Required Notice 集中放在
  `third_party/eta/LICENSE`，归属声明集中在 PROVENANCE.md 与 THIRD_PARTY_LICENSES.md，代码文件内不重复写许可头。
- 本文范围内不改变运行方式：不引入 PRoot 方案、不引入 Alpine 方案、不恢复旧特权 broker，也不把这些
  能力扩展成模型可用的通用 Root 入口；边界以 docs/contracts/02-CONSTRAINTS.md 与
  docs/contracts/04-SECURITY-CONTRACT.md 为准。

### 证据路径缩写

| 缩写 | 展开 |
|---|---|
| APP/ | src/android/app/src/main/java/com/openminis/app/ |
| TEST/ | src/android/app/src/test/java/com/openminis/app/ |
| RES/ | src/android/app/src/test/resources/ |
| ETA/ | /tmp/eta-upstream-clone/app/src/main/kotlin/io/github/mangi/eta/ |

本文所有 Minis 判断都给出 APP/、TEST/ 或 RES/ 下的 文件:行号；所有 Eta 判断都给出 ETA/ 下的 文件:行号。

写作时状态：同一工作树内有并行改动正在落地（例如 APP/mcp/client/MCPHttpTransport.kt 已经加上响应字节上限）。
所有行号按写作时的实际文件内容记录；与基线不同的行为在对应条目里单独标注为基线 69e05e51。
行号对应当前工作副本与 c15de97 克隆；克隆被更新后需要重新对齐。

---

## 1. 结论摘要

排序依据：先看“不做会出事”的部分（数据损坏、内存或磁盘失控、协议不互通），再看参考实现是否已有成套的
防御性设计可以直接转写成行为规格。

1. **Skill 安装的事务化与崩溃恢复**（缺失，价值最高）。Eta 把安装拆成“私有暂存 → 校验 → 备份旧版本 →
   写恢复日志 → 原子移动 → 登记索引 → 清日志”的可恢复流程；Minis 目前逐条目直写目标目录，中途失败只记
   一条日志继续（APP/data/repository/SkillRepository.kt:473-492），会留下半成品技能目录。
2. **Skill 归档与资源的静态校验、配额与符号链接拒绝**（缺失）。Eta 对归档字节数、解压总量、单文件、单个
   SKILL.md、条目数、路径层级、重复条目、路径穿越、符号链接分别设限并 fail-closed；Minis 的 ZIP 读取没有
   任何上限，且整包先读进内存（APP/data/repository/SkillRepository.kt:499-521、
   APP/ui/settings/SkillsManagementScreen.kt:422）。
3. **MCP 客户端报文有界读取、响应匹配与结果投影上限**（缺失或更弱）。Eta 对单次响应设 1 MiB 上限、按事件
   配对响应 id、截断错误文本，并把工具结果按 64 KiB 文本、64 KiB 结构化数据、2 MiB 图像投影且显式标记
   truncated；Minis 的 HTTP 传输直接取整个响应体（APP/mcp/client/MCPHttpTransport.kt:80），且不校验响应
   id（APP/mcp/client/MCPClientSession.kt:45-95）。
4. **MCP 协议版本协商与旧版本回退**（更弱）。Eta 先按现代协议发现，遇到 400/404/405 且不是现代 JSON-RPC
   错误码、或 -32600 时回退到旧版 initialize 会话，并在调用中遇到 404 时重建会话重试一次；Minis 对单一版本
   做严格相等判断，不等即失败（APP/mcp/client/MCPClientSession.kt:57-62）。
5. **tools/list 工具 schema 字段兼容与 required 投影**（更弱）。Minis 只读取 snake_case 的 input_schema
   （APP/mcp/client/MCPClientCodec.kt:117），不投影顶层 required，同时本项目服务端也输出 input_schema
   （APP/data/model/AgentToolDefinition.kt:66）；自连通成立，但按 camelCase 输出的外部服务器会整体丢参数
   schema。此项是本组里改动最小、收益最直接的一项。
6. **备份恢复的预检与失败语义**（部分更弱，但整体已相当）。Minis 的打包格式、清单 MAC、密文完整性校验、
   路径包含校验与暂存换名都已强于 Eta；缺的是跨记录引用完整性预检、整体失败的“未完成恢复”信号，以及恢复
   后触发本地仓库重载。

已相当、无需改造的部分见第 2 节：备份包格式与加密、MCP 本地服务端（令牌与确认队列）、技能在提示词中的按
使用频率排序与会话级开关。

---

## 2. 逐项对照表

| 能力 | Eta 做法（行为概述） | Minis 现状（文件:行号） | 差距判定 | 建议做法 |
|---|---|---|---|---|
| Skill 安装事务 | 归档先落 skills 目录外的私有工作区，校验通过才移入正式目录；批量替换前先备份旧目录，失败时回滚（ETA/agent/skill/SkillPackageInstaller.kt:30-40,250-300） | 逐条目直写目标 guest 目录，单条目失败仅记日志继续（APP/data/repository/SkillRepository.kt:473-492） | 缺失 | 引入“暂存 + 备份 + 恢复日志 + 原子移动”的安装事务，失败必须整体回滚或显式报告未完成 |
| 安装崩溃恢复 | 提交期写 pending-install.json，逐条记录原目标是否存在、备份是否完成、新目标是否提交与索引快照；日志原子写（ETA/agent/skill/SkillRecoveryJournal.kt:8-21,254-330） | 无恢复日志，也无重启后修复路径（APP/data/repository/SkillRepository.kt:453-495） | 缺失 | 落地恢复日志与首次访问前的恢复流程；日志无法恢复时拒绝新安装（fail-closed） |
| 并发安装互斥 | skills 目录同级私有锁文件加跨进程文件锁，同线程可重入；持锁先做恢复再执行（ETA/agent/skill/SkillMutationLock.kt:18-76） | 仅加载路径有互斥（APP/data/repository/SkillRepository.kt:87,1197），导入与更新无互斥 | 缺失 | 安装、更新、删除共用一把互斥，冲突时串行而不是并发写 |
| 替换与冲突语义 | 内置 id 与不可完整复制的目标不可替换；替换必须携带已确认的 skill id 与归档 SHA-256，重读不一致即拒绝（ETA/agent/skill/SkillPackageInstaller.kt:68-88,236-256） | 同名技能就地替换，无确认也无归档摘要绑定（APP/data/repository/SkillRepository.kt:403-425） | 更弱 | 同名导入先返回冲突结果并携带归档摘要；用户确认后的替换校验摘要一致 |
| ZIP 结构校验 | 条目 2048、层级 16、单文件 32 MiB、解压总量 128 MiB、归档 32 MiB、SKILL.md 512 KiB；NFC 加小写碰撞键查重复条目与文件目录冲突（ETA/agent/skill/SkillPackageInstaller.kt:15-21,442-521） | 无任何上限；整包解压进内存列表（APP/data/repository/SkillRepository.kt:499-521） | 缺失 | 改为流式读取并加配额、重复与冲突检测；超限返回明确错误码 |
| 路径与符号链接 | 拒绝绝对路径、反斜杠、NUL、控制字符、点段、超长段；写入前做 canonical 包含校验；遍历与删除不跟随符号链接（ETA/agent/skill/SkillPackageInstaller.kt:564-597、ETA/agent/skill/SkillMutationLock.kt:139-152） | 仅按路径段校验（APP/data/repository/SkillRepository.kt:1491-1495）；guest 侧无 canonical 与符号链接校验；备份恢复路径另有 canonical 包含校验（APP/backup/BackupRestoreFiles.kt:135-160） | 缺失（guest 侧） | 在 guest 写入侧增加“目标位于技能根内且路径组件不是符号链接”的校验；无法证明时拒绝写入 |
| 技能资源读取 | 资源列表与读取同样有上限（文本 512 KiB、条数 512、深度 16），二进制与超限分别返回独立错误码，并先检查符号链接组件（ETA/agent/skill/SkillResourceReader.kt:10-13,79-95,137-148） | 有 guest 文件列举与逐个读取（APP/data/repository/SkillRepository.kt:757-770,831-845），无条数与大小上限 | 缺失 | 给技能文件浏览与读取加同等级上限与拒绝路径，避免超大资源拖垮界面或上下文 |
| GitHub 安装来源 | 只接受 github.com 与 www.github.com 的 HTTPS 仓库、tree 或 blob，禁自定义端口与凭据；不跟随重定向；元数据 1 MiB、tree 8 MiB、归档 32 MiB、候选 200 个；缓存 24 小时清理（ETA/agent/skill/PublicGitHubSkillSource.kt:119-172,189-190,522-526） | 解析 GitHub URL 后按 raw 与 contents API 递归下载，无主机白名单、无大小上限，递归深度硬编码 5（APP/data/repository/SkillRepository.kt:845-960,1046-1120） | 更弱 | 收敛到“HTTPS 加主机白名单、不跟随重定向、响应上限、候选上限”，并把下载绑定到已确认的分支或提交 |
| MCP 响应边界 | 单次响应 1 MiB 上限并逐块计数；SSE 按行有界读取，只接受匹配 id 的事件；错误文本截断到 200 字符（ETA/agent/mcp/McpHttpClient.kt:418-470,492-493） | 基线为 HTTP 全量读入（基线 69e05e51 的 APP/mcp/client/MCPHttpTransport.kt:80）；SSE 只反向扫描最后一行花括号开头的内容（APP/mcp/client/MCPHttpTransport.kt:108-120）；无 id 校验。写作时已有并行改动为该文件补上 4 MiB 响应上限，但 SSE 事件匹配与 id 校验仍缺 | 缺失（部分已被并行改动覆盖） | 加增量计数与 id 和事件匹配；超限即失败，不静默截断 JSON |
| MCP 版本协商 | 先现代协议，失败时按状态码与 JSON-RPC 码回退旧版；支持 2024-11-05、2025-03-26、2025-06-18；旧版会话 404 时重建重试一次，关闭时删除会话；每页 TTL 汇总为过期时间（ETA/agent/mcp/McpHttpClient.kt:51-146,208-240） | 单版本严格相等，不等即抛错（APP/mcp/client/MCPClientSession.kt:57-62）；404 与 405 视为致命（APP/mcp/client/MCPHttpTransport.kt:87-89）；不发协议版本头 | 更弱 | 版本协商加有限回退与会话释放；本地服务端按请求版本回应或明确拒绝（APP/mcp/server/MCPCodec.kt:91-102 目前固定回同一版本） |
| 工具 schema 字段 | 读取 camelCase 的 inputSchema，附带只读、破坏性、幂等、开放世界注解，标题 160 字符与描述 2000 字符截断；支持把 x-mcp-header 属性映射为请求头（ETA/agent/mcp/McpHttpClient.kt:243-258、ETA/agent/mcp/McpToolHeaders.kt:14-74） | 只读 snake_case 的 input_schema（APP/mcp/client/MCPClientCodec.kt:117），不保留注解，不投影顶层 required（APP/tools/runtime/MCPToolHandler.kt:68-85） | 更弱 | 双读两种键名（含空与非对象回退），补齐 required、注解与截断上限；既有说明见 docs/specs/external-mcp-tools-list-contract.md |
| 工具结果投影 | 每轮冻结工具目录（上限 64 个），结果按 64 KiB 文本、64 KiB 结构化、2 MiB 图像投影，超限标记 truncated，跳过项计数 omitted_items（ETA/agent/mcp/McpRunContext.kt:53-98,160-250） | parseCallResult 拼接全部文本内容，无上限也无标记（APP/mcp/client/MCPClientCodec.kt:130-155）；工具在重连时全局注册（APP/mcp/client/MCPProvider.kt:90-175） | 缺失 | 结果进模型前做同量级的有界投影并保留标记；运行中冻结工具集合 |
| 备份包格式与校验 | 单一 JSON 文档加 format 与 schemaVersion（接受 1 到 2），导入前做整体引用完整性校验（ETA/data/repository/EtaBackupRepository.kt:19-46,146-230） | minisbak/1 包、明文清单、成员 SHA-256、清单 MAC、AES-256-GCM 加密（APP/backup/BackupFormat.kt:104-125,215-290、APP/backup/BackupCrypto.kt:52-91） | 已相当（部分更强） | 不重写格式，只吸收其预检与失败语义 |
| 恢复安全 | 数据库事务内替换并补偿已写文件，失败时回滚记忆文件；导入后修复内置提供商与选择项（ETA/data/repository/EtaBackupRepository.kt:74-140） | 写入前 canonical 包含校验与暂存换名（APP/backup/BackupRestoreFiles.kt:76-160）；按类别隔离失败并计数（APP/backup/BackupImporter.kt:196-232） | 部分更弱 | 补跨记录预检、未完成恢复信号与恢复后重载 |
| 本地 MCP 服务端 | 无对应实现（Eta 只做客户端） | 仅监听 127.0.0.1、每请求 bearer、令牌作用域、确认队列绑定调用方与方法和参数摘要、连接数上限（APP/mcp/server/MCPServer.kt:44-60,150-190,240-330、APP/mcp/server/ConfirmQueue.kt:83-200） | 不该移植（本项目更强） | 保持现状，只做第 4 项里的版本回应一致性 |
| 技能提示词披露 | 索引条目含正文与附属目录标记，按需加载正文（ETA/agent/skill/SkillModels.kt:30-40、ETA/agent/skill/SkillRuntime.kt:600-640） | 按使用频率与会话开关选前 20 个名称与描述注入，正文按需读取（APP/data/repository/SkillRepository.kt:260-330） | 已相当 | 不改造排序策略，仅确保事务化安装不改变现有评分字段 |

---

## 3. 可移植规格

以下 6 项按摘要顺序展开，每项给出输入输出、状态机或不变量、失败与拒绝路径、边界上限、落地位置与单元测试
清单。数值取自 Eta 的实现常量，落地时可按本项目实测调整，但必须先有上限再谈调参。

### 3.1 Skill 安装事务化与崩溃恢复

输入输出：

- 输入：可重读的归档来源（本地流或已下载的 GitHub 归档文件）、目标技能 id 集合、是否允许替换、替换时
  已确认的目标 id 与归档摘要。
- 输出：密封三态结果——成功（含已安装条目）、冲突（含每个 id 的现有来源、是否允许替换、归档摘要）、失败
  （含错误码与恢复是否未完成）。

状态机与不变量：

1. 归档先落到技能根目录之外的私有工作区，工作区名与顺序固定，且必须是普通目录而不是符号链接。
2. 校验全部通过后才进入提交段，提交段一旦开始不再接受取消。
3. 提交段顺序固定：写恢复日志、备份旧目录、原子移动新目录、登记索引、清日志。
4. 恢复日志每条记录必须包含技能 id、原目标是否已存在、备份是否完成、新目标是否已提交、索引旧状态快照。
5. 不变量：正式目录中要么是旧的完整技能目录，要么是新的完整技能目录，不出现混合状态；日志被删除时文件
   系统与索引已经同时恢复。

失败与拒绝路径（fail-closed 否定用例）：

- 安装中途进程被杀：下次进入技能事务时先发现恢复日志并回滚，回滚完成前任何新安装都返回“需恢复”并拒绝。
- 并发安装同一 id：第二个调用串行等待或直接返回冲突，不允许两个事务同时移动同一目录。
- 恢复日志存在但备份缺失，或状态自相矛盾（记录原目标存在却无备份）：拒绝继续，保留现场并上报。
- 归档重读后与已确认摘要不一致：拒绝替换。
- 目标目录含符号链接或非常规文件：拒绝替换（无法用普通复制完整恢复）。
- 归档不含技能、含多个技能、选中路径不是技能根、嵌套技能：分别返回独立错误码。

边界上限：归档 32 MiB、解压总量 128 MiB、单文件 32 MiB、单个 SKILL.md 512 KiB、条目 2048、路径层级 16、
单段 255 字符；恢复日志 256 KiB、日志条目 2048；技能 id 1 到 64 字符，小写字母数字与单连字符。

落地位置：

- 新增事务与恢复模块：APP/data/skill/，或与现有仓库同包的 APP/data/repository/SkillInstallTransaction.kt。
- 接入点：APP/data/repository/SkillRepository.kt:453-495（本地归档）、同一文件 598-660（URL 与 GitHub
  更新）、191-203（删除）、836-845（单文件写入）。
- 结果模型与错误码与现有返回类型并列新增，收敛 UI 分支前先保留既有 null 语义
  （APP/ui/settings/SkillsManagementScreen.kt:437-446）。

单元测试清单（JVM，临时目录加假技能根）：

- 全新安装成功：正式目录出现完整技能，恢复日志被清除，索引登记一次。
- 替换安装成功：旧目录内容被完整替换，备份目录不残留。
- 提交段中途注入失败：正式目录恢复为旧内容，索引恢复旧状态，返回失败且未完成标志为假。
- 回滚本身失败（模拟移动或删除抛错）：返回失败且未完成标志为真，恢复日志保留。
- 并发两个安装事务：串行执行，最终状态一致，无混合目录。
- 目标为符号链接目录，或目标含符号链接子项：拒绝替换。
- 恢复日志版本不支持、大小超限、条目超限、id 非法：分别拒绝。

### 3.2 Skill 归档与资源的静态校验、配额与符号链接拒绝

输入输出：

- 输入：归档字节流或已落地归档文件、技能根目录、相对资源路径。
- 输出：候选技能列表，或明确错误码（归档过大、条目过多、条目过大、解压总量过大、层级过深、路径不安全、
  重复条目、无技能、多技能、元数据非法、资源过大、二进制资源、资源过多）。

状态机与不变量：

1. 逐条目流式校验并累计计数，任一上限在读取过程中即触发失败，不先解压完再检查。
2. 每个条目路径归一化后必须可证明落在暂存根目录内；碰撞键用 Unicode 归一化加小写比较，重复条目与文件
   目录冲突都要拒绝。
3. 技能名称与描述必须存在并符合长度与字符集约束，描述为空视为非法。
4. 资源读取前先检查路径各组件是否为符号链接；文本资源必须是严格 UTF-8，二进制与超限分别返回独立错误码。

失败与拒绝路径：

- 路径含绝对路径、反斜杠、NUL、控制字符、点段、空段或超长段：拒绝整包。
- 归档出现指向暂存目录之外的相对路径或符号链接条目：拒绝整包，不写入任何条目。
- 压缩包声明大小与实际不符、目录条目携带数据、空归档：分别返回独立错误码。
- 解压总量或条目数超限：中止并清理暂存目录，不保留部分结果。

边界上限：与 3.1 同一组常量；资源侧另设文本 512 KiB、资源条数 512、深度 16。

落地位置：

- 归档校验：APP/data/repository/SkillRepository.kt:453-521（替换现有 readZipEntries）。
- 路径校验：APP/data/repository/SkillRepository.kt:1486-1495。
- 入口上限：APP/ui/settings/SkillsManagementScreen.kt:409-450 目前把整个文件读进内存，需改成先限长再判断
  类型（可复用现有 4 字节魔数判断，同一文件 433-440）。
- 资源读取：APP/data/repository/SkillRepository.kt:757-845。

单元测试清单：

- 正常归档（根 SKILL.md 与一层目录两种布局）导入成功。
- 条目数、单文件、解压总量、层级任一超限：返回对应错误码，目标目录无残留。
- 含点段、绝对路径、反斜杠、NUL 的条目：整包拒绝。
- 重复条目、文件与目录同名冲突、Unicode 归一化与大小写变体：拒绝。
- 缺少名称或描述、名称含大写或下划线或多点：元数据校验拒绝。
- 符号链接条目与符号链接中间路径：拒绝且不写入链接目标。
- 资源读取：超限、非 UTF-8、含控制字符分别返回独立错误码。

### 3.3 MCP 客户端报文有界读取、响应匹配与结果投影上限

输入输出：

- 输入：一个 JSON-RPC 帧（含自增 id）、目标服务器配置与凭证、服务器返回的响应体（JSON 或 SSE）。
- 输出：解析后的响应对象、带类型的传输失败、或不可恢复的协议错误；工具结果投影为有界的文本与图像载荷。

状态机与不变量：

1. 请求 id 单调递增且不复用；响应必须携带被等待的 id 才被接受，否则视为协议错误。
2. 响应体在读取过程中累计字节，超过上限立即失败；SSE 只处理完整事件，事件内取第一条与等待 id 匹配的数据行。
3. 任何缓存或重试都不得重放已产生副作用的调用。
4. 工具结果进入模型上下文前必须经过上限投影，超限部分丢弃并显式标记，不静默截断 JSON。

失败与拒绝路径：

- 响应超过 1 MiB、SSE 单行超限、期望响应但正文为空：分别返回带类型的失败。
- 响应 id 不匹配、id 缺失、SSE 中没有匹配事件：协议错误，不重试。
- HTTP 非 2xx：保留状态码与截断后的错误摘要；正文非法 JSON 时仍以状态码失败。
- 工具结果含不支持的内容类型（例如音频或二进制资源）：计数跳过，不拼接成不可读文本。

边界上限：响应 1 MiB；错误摘要 200 字符；工具名 128 个、分页 8 页；模型侧投影 64 KiB 文本、64 KiB 结构化、
2 MiB 图像，单轮工具 64 个。传输超时沿用现有 15 秒连接、60 秒读写设置
（APP/mcp/client/MCPHttpTransport.kt:33-41）。

落地位置：

- 有界读取与 SSE 匹配：APP/mcp/client/MCPHttpTransport.kt:42-120（基线行号；写作时该文件已并行加入响应字节上限，SSE 匹配仍待做）。
- id 生成与响应校验：APP/mcp/client/MCPClientSession.kt:20,71-95。
- 工具结果投影：APP/mcp/client/MCPClientCodec.kt:130-155 与 APP/tools/runtime/MCPToolHandler.kt:34-66。
- 运行级工具快照：APP/mcp/client/MCPProvider.kt:90-175。

单元测试清单：

- 超限响应（声明长度与实际长度两种）返回响应过大，连接被关闭。
- SSE 多事件、目标事件不在首位、含注释行与多行 data 字段：只在匹配 id 时返回。
- 响应 id 不匹配或缺失：协议错误，且不触发重试。
- 工具结果含超长文本、超量结构化数据、超大图像：返回带 truncated 与跳过计数的载荷。
- 结果含未支持内容类型：计入跳过数量，不进入文本。

### 3.4 MCP 协议版本协商与旧版本回退

输入输出：

- 输入：服务器配置（协议模式可为仅现代、仅旧版或自动）与凭证。
- 输出：协商后的协议版本、工具清单（含由每页 TTL 汇总的过期时间），或明确的兼容性失败。

状态机与不变量：

1. 自动模式先尝试现代协议，仅在状态码属于 400、404、405 且 JSON-RPC 错误码不属于现代错误集合，或返回
   -32600 时才回退旧版；其余错误直接上报。
2. 旧版会话必须先 initialize 再发送 notifications/initialized；服务器返回的协议版本必须落在受支持集合内。
3. 旧版会话在调用中收到 404 时允许丢弃会话、重新初始化并重试一次，其他错误不重试。
4. 关闭时释放旧版会话；现代协议不使用会话 id 语义。
5. 每页返回的 TTL 取最小值作为工具目录过期时间，过期后重新发现，不无限信任缓存。

失败与拒绝路径：

- 服务器返回不支持的协议版本：明确失败，不降级到未声明能力。
- 回退后仍失败：返回原始错误，不循环重试。
- 协议版本不匹配时不得复用上一会话的工具清单。
- 仅旧版模式而服务器不支持：直接失败，不做静默发现。

边界上限：受支持旧版本集合固定枚举（2024-11-05、2025-03-26、2025-06-18）；发现阶段最多 8 页、最多
128 个工具；单次会话重试上限 1 次。

落地位置：

- 客户端会话与协商：APP/mcp/client/MCPClientSession.kt:45-95。
- 传输层错误分类：APP/mcp/client/MCPHttpTransport.kt:83-95（目前 404 与 405 直接视为致命，且失败后不尝试回退）。
- 配置模型：APP/data/repository/MCPRepository.kt:56-90（新增协议模式字段需保持旧 servers.json 可读）。
- 本地服务端回应一致性：APP/mcp/server/MCPCodec.kt:91-102、APP/mcp/server/MCPServer.kt:199。

单元测试清单：

- 现代协议成功：只发出一次发现请求，记录协商版本。
- 服务器返回 404、405 或 -32600：回退旧版并完成 initialize 加 notifications/initialized。
- 服务器返回 500 或 -32601：不回退，直接失败。
- 旧版调用中途 404：重建会话并重试一次；第二次 404 直接失败。
- 服务器返回不支持的协议版本：失败且不使用其工具清单。
- TTL 缺失、为 0 或为负：按无缓存处理。

### 3.5 tools/list 的 schema 字段兼容与 required 投影

输入输出：

- 输入：tools/list 页面 JSON。
- 输出：工具条目（名称、描述、输入 schema 原样对象、可选注解），以及可用的 schema 键名。

状态机与不变量：

1. 解析时同时接受 inputSchema 与 input_schema；两者都存在时以 camelCase 优先并保证结果确定。
2. 名称为空或非字符串的条目跳过并计数，不让整页失败。
3. 投影到本地工具定义时保留 required、enum、属性类型与描述；类型缺失回退为字符串。
4. 输出侧字段名必须与真实客户端生态一致；若保留 snake_case，至少同时输出两种键名。

失败与拒绝路径：

- schema 不是对象或为空：按无参数处理并保留原始 JSON 供调试，不抛出整页失败。
- 工具名与服务器 id 归一化后与既有工具冲突：拒绝注册并上报，避免静默覆盖。
- 描述或名称超长：按上限截断并保留截断标记。

边界上限：标题 160 字符、描述 2000 字符、单服务器工具 128 个、单轮注入模型工具 64 个。

落地位置：

- 解析：APP/mcp/client/MCPClientCodec.kt:104-128。
- 投影：APP/tools/runtime/MCPToolHandler.kt:68-85。
- 输出侧：APP/data/model/AgentToolDefinition.kt:53-68 与 APP/mcp/server/MCPCodec.kt:103-106。
- 既有契约与未决项：docs/specs/external-mcp-tools-list-contract.md。

单元测试清单：

- 同一工具分别给 camelCase、snake_case、两者都给：三次解析得到同一参数集合。
- 顶层 required 正确投影到本地定义。
- 缺少 properties、属性非对象、类型缺失、枚举非数组：回退且不抛错。
- 名称为空、描述超长、标题超长：跳过或截断并计数。
- 名称归一化冲突：拒绝注册并保留诊断信息。
- 现有回归夹具必须同步更新：TEST/mcp/client/MCPToolsListContractFixtureTest.kt 与
  RES/mcp/tools-list-contract/repo-observed.json（当前有意锁定只读 snake_case 的行为）。

### 3.6 备份恢复的预检与失败语义

输入输出：

- 输入：已解包的备份目录、类别选择、可选口令。
- 输出：总报告（每类别的导入、更新、跳过、缺失 blob、拒绝路径、失败原因），以及整体是否处于需要恢复的状态。

状态机与不变量：

1. 顺序固定：清单与格式校验、降级检测、口令与清单认证、完整性校验、解密、逐类别恢复。
2. 预检先做跨记录引用完整性：消息引用的会话、技能索引引用的 blob、MCP 服务器文件的字段结构都在写盘前判定；
   不满足即整包拒绝，而不是写一半后再计数孤儿记录。
3. 单类别失败与其他类别隔离，但必须让用户看到哪些类别失败，且失败类别不得留下半写状态。
4. 恢复完成后，受影响的本地仓库（技能索引与 MCP 服务器配置）必须就地重载，不要求用户重启应用。

失败与拒绝路径：

- 格式主版本无法识别：直接拒绝（现有行为，APP/backup/BackupPackageReader.kt:37-54）。
- 存在加密成员但清单声明未加密：拒绝（APP/backup/BackupPackageReader.kt:73-89）。
- 完整性校验失败、口令 verifier 不匹配、清单 MAC 不匹配：在任何写入前失败
  （APP/backup/BackupPackageReader.kt:91-176）。
- 索引内容的路径逃出类别根：逐条拒绝并计数（APP/backup/BackupRestoreFiles.kt:84-88）。
- 引用的 blob 缺失：不得静默跳过，必须计数并在结束时提升为警告
  （APP/backup/BackupImporter.kt:768-772、APP/backup/BackupRestoreFiles.kt:30-50,90-102）。
- 技能类别恢复后索引未刷新：应视为失败而不是成功。

边界上限：沿用现有包上限——JSONL 单分片 64 MiB（APP/backup/BackupFormat.kt:125）、包内条目 65535
（APP/backup/BackupZip.kt:44-79）、解压成员上限与文件名包含校验（APP/backup/BackupZip.kt:320-352,406-422）。
新增预检的内存占用必须与包大小解耦：按 JSONL 逐行扫描，不整表加载。

落地位置：

- 预检：APP/backup/BackupImporter.kt:129-200（插入到遍历 ORDER 之前，现有顺序表在同一文件 988-1000）。
- 技能类别：APP/backup/BackupImporter.kt:582-592（恢复后调用技能索引重载）。
- MCP 类别：APP/backup/BackupImporter.kt:618-626（当前整文件覆盖，需要结构校验与恢复后重载，
  重载入口见 APP/data/repository/MCPRepository.kt:131-137）。
- 报告语义：APP/backup/BackupImporter.kt:52-110（新增未完成恢复字段）。

单元测试清单：

- 清单主版本高一位：拒绝且不写任何文件。
- 声明未加密但含加密成员：拒绝。
- 完整性哈希不匹配、口令错误、清单被改写：分别拒绝，且磁盘无变化。
- 消息引用不存在的会话、技能索引引用不存在的 blob：预检阶段整包拒绝，或在只读检查模式下逐条标记。
- MCP 服务器文件 JSON 非法或字段类型错误：该类别失败且不覆盖现有配置。
- 恢复成功后技能索引与 MCP 配置被重新加载（以假仓库断言调用）。

---

## 4. 必须真机验证的部分

宿主单元测试只能覆盖纯逻辑，下列结论必须在本项目直连 Ubuntu 运行时的真机上取证后才算成立：

1. 技能目录写入与符号链接语义：技能根 /var/minis/skills 是绑定挂载点，宿主侧
   APP/runtime/ubuntu/UbuntuPaths.kt:76-103 与 guest 视图的一致性，以及 guest 内创建符号链接后经文件桥写入的
   实际行为，都需要在设备上验证；本文提出的“路径组件不得是符号链接”校验也只能在真机上证伪或确认。
2. 安装事务中途被杀：进程被杀、应用被系统回收、设备断电后重启，恢复日志能否在下一次安装前被正确发现并回滚，
   需要真实生命周期，不能靠 JVM 测试代替。
3. 并发安装真实路径：界面导入与 Agent 侧触发同时发生时，互斥与 guest 写入是否真正串行，需要设备实测
   （本项目文件写入经 WorkspaceFileClient 桥接，APP/data/repository/SkillRepository.kt:1424-1470）。
4. 外部 MCP 服务器互通：至少一个真实第三方远端服务器验证版本协商、camelCase schema、分页与 SSE 的组合；
   本地假服务器无法证明真实生态行为。
5. MCP 本地服务端对第三方客户端：协议版本回应调整后，需要用真实客户端（含旧版协议客户端）验证 initialize
   与后续请求；通知式确认在真实 Android 通知与锁屏下的行为也必须真机验证。
6. 备份与恢复的大包和中断：多 GB 包、恢复中断、恢复后索引重载与运行中会话的交互，需要真机与真实存储
   （含外置存储与云盘目录）验证。

---

## 5. 不建议移植清单

| 项目 | 理由 |
|---|---|
| 把“安装技能”做成模型可直接调用的本地工具（含自动发现公共仓库上可安装的技能） | 参考实现把 skills_install_from_github 暴露为本地工具并配套冲突可重放能力（ETA/agent/model/AgentSkillToolCatalog.kt:154、ETA/agent/tool/PendingSkillConflictCapability.kt:1-40）。本项目技能安装会写 Guest 文件树并更新索引，属于需要用户在场确认的动作；模型触发会扩大写入面，收益不足以抵消风险。 |
| 把公共代码托管仓库作为技能的主要安装通道 | 参考实现围绕公共仓库设计了发现、候选、归档与缓存整套流程（ETA/agent/skill/PublicGitHubSkillSource.kt:205-284）。本项目已有本地归档、URL 与粘贴三种入口，先把校验与事务补齐，不扩展来源面。 |
| 参考实现的备份文档结构与角色、记忆附件格式 | 本项目 minisbak/1 已定义跨平台字段、清单 MAC、密文哈希与凭证分离（APP/backup/BackupFormat.kt:104-290）；再引入第二套结构会破坏既有兼容性。 |
| 参考实现在数据库无法迁移时直接丢弃全表的降级策略 | 参考实现在升级路径上保留了 fallbackToDestructiveMigration（ETA/data/db/EtaDatabase.kt:72）。本项目已有逐版本迁移链（APP/data/db/AppDatabase.kt:42-460），用户数据不能走丢弃路径。 |
| 参考实现的“大字段分块搬迁”手法本身 | 该手法是为把大 JSON 列搬进分块表、避免整行进 CursorWindow（ETA/data/db/HistoryPayloadMigration.kt:5-35）。本项目当前没有同类列压力，按需再评估，避免为局部症状引入额外表结构。 |
| 参考实现的固定技能子目录语义（scripts、assets、references 固定命名） | 本项目技能目录按 Guest 文件树自然组织，加固定目录语义会与既有文件列举与浏览行为冲突（APP/data/repository/SkillRepository.kt:757-770）。 |

---

## 6. 复核方式

- 文档范围检查：cd /home/jiale/projects/minis-eta-integration && python3 scripts/check_docs_provenance.py
- 领域切片回归：cd src/android && ./gradlew :app:testDebugUnitTest --no-daemon --max-workers=1
  （第 3 节新增测试落地后应覆盖对应类）。
- 行号复核：本文 APP/ 与 TEST/ 行号引用当前工作副本，ETA/ 行号引用 /tmp/eta-upstream-clone 的 c15de97。
