# OpenMinis Pet 1.12-pet.10（Latest）

## Unreleased · 1.12-pet.11-SNAPSHOT

- Web Remote 新增「Agent 工作台」：网页可编辑/启停当前会话的目标、计划和待办，
  查看后台任务输出并停止运行中任务；手机 App 与网页仍复用同一状态源。
- Web Remote 的工具导航和卡片层级重新整理为更紧凑的三栏控制台体验。
- 安全修复：分享附件限额与后台复制、OAuth loopback + state 校验、远程登录按客户端
  限流、会话工作区 canonical 路径校验，以及禁用系统自动备份。
- 手机与网页端均增加一次性危险操作审批卡片。

> 本次构建产物为 debug APK，仅适合开发/自用测试；当前 release variant 使用 debug
> keystore 签名，不能作为正式公开分发包。

本次验证构建：`OpenMinis-Pet-pet.11-SNAPSHOT-arm64-debug.apk`（51,482,404 bytes）

```text
SHA-256: 04174dc0a48026e9fbb39c1f83acfb38ad47a6075a65ee4af95d6717f538325b
```

---

OpenMinis 官方项目的非官方分支，只做了一点二次创作。
Agent、PRoot 沙盒、模型接入、整个 App 骨架全部是原作者的功劳。有问题请在本仓库反馈，
不要去打扰上游维护者。

基线：官方 1.12（versionCode 24）。只做 Android，iOS 相关代码已从本分支移除。
本版：versionCode 31 / `1.12-pet.10`，相对 pet.1 累计 9 轮迭代。

## 下载

`OpenMinis-Pet-1.12-pet.10-arm64-debug.apk` · 约 50 MB · 仅 arm64-v8a

SHA-256:

```
54a100557de611807895e57033835804aa017ed9bbaf3668ea9e59eae0523e43
```

applicationId 是 `dev.openminispet.android`，不会覆盖官方版，两个可以同时装。

## 加了什么

### 一、桌面宠物

导入 ZIP 宠物包（`pet.json` + `spritesheet.webp`）。点一下就能聊天，回答显示在
气泡里并写进 App 的会话历史；空闲时自己巡游、久置贴边隐藏；Agent 跑任务时状态
同步到宠物身上；熄屏后动画和巡游全部暂停。语音复用 App 自己的 Voice 配置。

### 二、子代理

`subagent` 工具：主 Agent 可以把独立的子任务委派出去。子代理在自己的会话里跑，
拿到隔离的上下文和完整工具链；委派深度默认 3 层、单任务超时默认 10 分钟，
并且这两项现在可在手机「设置 → Agent Runtime → 子代理委派限制」与网页
「模型 → 代理设置」里配置（深度 1–5、超时 1–30 分钟）。失败时保留子代理已产出
的部分文本附在错误信息后——既不谎报成功，也不让已完成的工作凭空消失。

### 三、编码可靠性增强（Pi 风格）

- `FileEditEngine`：一次多处编辑、重叠即拒绝、保守 fuzzy match、保留 BOM 与
  CRLF、返回 diff
- `FileMutationQueue` + `FileRevision`：同文件写入串行化，编辑前 SHA-256 校验版本
- `ShellOutputTruncator`：大输出只在上下文留 2000 行 / 50 KiB，完整内容落盘到
  `/var/minis/offloads/tools/`，截断按 Unicode 码点回退不会砍断 emoji

不是完整 Pi 克隆：Persistent PRoot Shell 仍是 OpenMinis 原来那套，会话分支导航、
扩展系统这类大型改造刻意没做——那会变成重写核心，以后难跟官方合并。

### 四、Web 远程控制（全面重写）

浏览器里管手机上的 Agent，和原生界面共享同一个 Session、Agent Loop 和
Persistent Shell：

- Markdown 渲染（marked + DOMPurify），回复逐段增长的流式观感
- 会话新建/改名/删除、切换模型、压缩上下文、Token 用量
- 模型管理：供应商与状态、模型列表、模型组与默认组/子组（主代理/子代理）、
  可测试连接、刷新模型
- **技能 / 记忆（含 SOUL 人格）/ MCP / 定时任务** 四个管理页签：列表、启停、
  删除、记忆在线编辑、定时任务一键「立即运行」
- **跨会话全文搜索**：`chat.search`，按会话分组、每会话最多 3 条命中
- **模型提问卡片**：模型调用 `ask_user_question` 时暂停回合，网页弹卡回答
  （单选/多选/自定义/跳过），答案回到模型
- **目标 / 待办 / 计划 / 产出文件条**：与手机端同一份状态源，目标可编辑，
  产出文件可点击打开
- **消息反馈 👍/👎**、**附件上传**（图片/文档随消息发送）
- **权限预设**（Workspace Write / Danger Full Access）：默认 Workspace Write 模式下网页文件写入/编辑仅限 `/var/minis/workspace`，Full Access 放开全部路径
- 文件浏览编辑、Shell
- **布局按 DeepSeek Harness 设计系统重写**：三栏骨架、侧边栏导航、sticky
  输入 Dock、胶囊 Tab、Toast、命令菜单、骨架屏、微交互动画，亮暗自动跟随
  （`--dsw-alias-*` / `--dsw-specific-*` token，取自 DeepSeek 公开前端）
- 登录用 PBKDF2-HMAC-SHA256（210k 轮），没设密码拒绝启动，默认只监听 127.0.0.1
- Cloudflare Tunnel 管理
- 安全边界：网页 `/api/rpc` 走白名单，放行 `provider.*` / `chat.*` /
  `skills.*` / `memory.*` / `soul.*` / `mcp.*` / `scheduled.*` / `agent.*` /
  `settings.*` 及只读诊断；`debug.tap` / `inputText` / `screenshot` /
  `writeFile` 等远程操控手机的整族仍挡在公网之外

### 五、网页端功能同步到手机 App

- 提问卡片：模型在手机上提问直接弹原生对话框回答
- 目标/待办/计划/产出条：悬浮在输入框上方，目标可编辑/暂停/清除，
  计划可进出、产出路径可复制
- 消息反馈、权限预设、子代理委派限制设置
- 聊天菜单新增「计划模式」开关
- 会话搜索、技能/记忆/SOUL/MCP/定时任务/模型组等手机端原本已有原生入口

### 六、默认数字助手

新增 `VoiceInteractionService` 支持：在「设置 → Agent Runtime → 默认数字助手」
里一键唤起系统角色选择（自动兼容 AOSP 的 `android.app.role.Assist` 与 MIUI 的
`android.app.role.ASSISTANT`）。设为默认后，长按 Home / 语音唤起会直接打开
OpenMinis Pet 主界面。当前为轻量实现：唤起即进 App，不做系统级语音会话 UI。

### 七、可靠性修复（历轮累计）

- Web Remote 冷启动/开机自动恢复（前台服务后台启动被拒的修复）
- 宠物熄屏省电、聊天窗失焦关闭、图集采样降内存
- Markdown 渲染换 marked + DOMPurify、流式增量更新
- 子代理重复工具提醒（连续 4 次相同调用自动提示）
- 构建脚本 `scripts/build-pet-apk.ps1`（Windows + WSL 双环境）

完整改动与踩坑记录见 [CHANGELOG-FORK.md](CHANGELOG-FORK.md)。

## 装了之后

1. 「设置 → 权限 → 系统权限 → 显示在其他应用上层」授权
2. 「设置 → 外观 → 桌面宠物」导入宠物包，启动
3. 想让宠物说话，先配好默认模型（Provider + API Key）
4. Web 远程控制在「设置 → Web 远程控制」，必须先设登录密码才能启动
5. 想把 App 设为默认数字助手：「设置 → Agent Runtime → 默认数字助手」

## 已知限制

- 语音识别在部分国产 ROM 上不可用：系统引擎 `isRecognitionAvailable()` 返回
  false，需要在「设置 → 语音」给 Voice Input 组绑一个云端 ASR 模型
- 宠物对话直连模型，不跑 Agent 工具链——能问答能总结，不能执行命令读写文件
- 网页端尚未覆盖：轨迹回放（需给 LLM 请求日志加 sessionId）、会话日志 ZIP
  导出、计划模式强制审批（当前为软引导）
- 默认数字助手为轻量实现：唤起打开 App，不提供系统级语音对话界面
- 只有 arm64-v8a，不支持 32 位设备和 x86 模拟器
- debug 签名（沿用上游配置），仅供自用

## License

GPL-3.0，跟随上游。移除 iSH 不改变义务——本分支是 OpenMinis 的派生作品。
对应源码即本仓库。第三方组件许可见 `THIRD_PARTY_LICENSES.md`。


---

# 1.12-pet.11-SNAPSHOT（开发版，2026-08-21）

> 未发布标记。基于 pet.10 的全部修复 + DSH 设计移植，构建产物：
> `OpenMinis-Pet-pet.11-SNAPSHOT-arm64-debug.apk`（SHA-256 133d1699d057af0bdea1732e31095ba4d470f6015848a8728781bf57d8f1d9be）

## 安全加固（审查驱动，详见 docs/DESIGN-HARDENING-2026-08-21.md）
- 凭据 fail-closed（加密存储失败绝不落明文）；DebugServer 全连接 token；Web 白名单 deny 列表
- canonical 路径守卫（symlink 逃逸拒绝）；per-IP 登录限流；LLM 日志脱敏；权限预设真实生效
- AlarmReceiver 非导出；FGS 判据统一；attachments 净化；500 不泄漏内部异常

## DeepSeek Harness 设计移植（详见 docs/DSH-DISSECTION-2026-08-21.md）
- 工具调用统一超时（AgentToolDefinition.timeoutMs + TOOL_TIMEOUT）
- LLM 统一重试（LLMRetryPolicy：指数退避 + jitter，覆盖宠物/视觉/纠错/摘要等调用点）
- **Ralph 循环**（不可变目标 + 全新子代理轮换 + 结构化交接报告）
- **会话检查点**（进程崩溃恢复注入 TOOL_OUTCOME_UNKNOWN，防重复执行副作用调用）
- 作业系统（job_output/list/kill）；Token Meter + 上下文压力提示
- 结果剪枝器 + 输出保留库 + Spill 泛化；审批 seam（危险 shell 命令执行前审批）

## 注意
- 开发版用 debug 签名与 debug 构建（含 DebugServer）；对外发布必须 assembleRelease + 正式 keystore
- 审批 seam 的手机端弹窗未实现（Web Remote RPC 已可用）
