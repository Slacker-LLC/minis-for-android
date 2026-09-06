# 06 — 当前已确认缺口

本文记录已核验源码与合同之间的差异，不用历史 Issue 的标题代替当前调用链证据。

核验基线：`main` `be357f3b5330baa6eea9cc158644daf0535fd87c`，2026-09-06。下列合并状态仅对应此 SHA；后续合并须重新核对最终源码和检查结果。

## 已在基线中核验的实现

- `applicationId = llc.slacker.minis`，namespace 为 `com.openminis.app`。
- Root + `minisd` + Ubuntu 24.04 chroot；canonical 数据根仍为 `/data/adb/minis/{workspace,sessions,memory,skills,shared,home}`，rootfs 是可替换运行时。
- `proguard-rules.pro` 已保留 RealTimeCutVAD JNI 类。Release 构建验证与真实语音检测是两层证据。
- `deleteFromMessage` 已采用数据库删除成功后提交 UI/历史/记忆的顺序；单条删除仍有下述缺口。

| 已合并 PR | 最终代码行为 | 已有验证及边界 |
|---|---|---|
| [#194](https://github.com/Slacker-LLC/minis-for-android/pull/194) | `SessionHistoryLoader` 经 Repository 分页恢复完整历史，去除最近 100 条截断 | 405 条历史、跨页工具配对及取消测试；没有宣称实现数据库懒加载 |
| [#195](https://github.com/Slacker-LLC/minis-for-android/pull/195) | home 初始化保留既有文件权限，bootstrap 不再递归改权 | Rust 权限回归和 Kotlin 测试；不会自动恢复旧版本已丢失的执行位 |
| [#196](https://github.com/Slacker-LLC/minis-for-android/pull/196)、[#200](https://github.com/Slacker-LLC/minis-for-android/pull/200) | 图片持久化、重载、展示；正常/中断提交去重，重试清理，OpenAI 后续请求回传图片 | 文件与请求 fixture 测试；真实账号生图未验收 |
| [#197](https://github.com/Slacker-LLC/minis-for-android/pull/197) | `minis-model-use run` 保留有界 stdin，原 guest 文件输入路径继续复用 | Bash→native→测试 Android endpoint 往返、分发拒绝用例、Rust 测试 |
| [#198](https://github.com/Slacker-LLC/minis-for-android/pull/198) | `gpt-6-astra` 目录与发现、OAuth/API Responses 路由、推理档位和请求参数约束 | OAuth fixture 和 API MockWebServer；未证明具体账号权限或真实服务调用成功 |
| [#199](https://github.com/Slacker-LLC/minis-for-android/pull/199) | Terminal 复用 runtime/session 准备和真实 UID/GID，拒绝宿主回退；PTY 单协程管理读写关闭及 reap | Kotlin 生命周期、生产 C 的 Linux JVM/子进程检查、Debug/Release CI；Android root/终端交互未验收 |
| [#201](https://github.com/Slacker-LLC/minis-for-android/pull/201) | DNS 刷新锁在读取当前 resolver 之前取得，避免旧刷新最终覆盖新配置 | 并发顺序、失败、取消测试；VPN 切换的设备行为未验收 |

以上 PR 的对应提交 CI 已通过。合并、构建和单测不替代设备验收。

## 基线中仍存在的确认缺口

### 粘贴附件的取消清理与消息部分提交

`PastedTextProcessor` 在 IO 返回时可能因取消丢失已创建文件的清理责任。三个发送入口在 `appendMessage` 抛异常时直接删除附件，而 Repository 的消息插入和摘要更新分两步完成，可能留下引用已删除文件的数据库行。

修复：[#202](https://github.com/Slacker-LLC/minis-for-android/pull/202)。准备阶段持有文件所有权；未提交才回滚附件；消息序号、内容与摘要使用既有 Room 事务，提交和粘贴状态消费共用取消边界。61 项局部测试通过；新增 Room 回滚/并发仪器测试已编译，尚未在设备执行。

### 单条助手消息删除仍先改 UI 和记忆

`deleteSingleAssistantMessage` 尚未复用 `deleteFromMessage` 的数据库提交顺序：UI 移除、记忆撤销和朗读停止发生在删除落库之前。

修复：[#203](https://github.com/Slacker-LLC/minis-for-android/pull/203)。复用 `runAfterDatabaseDelete`，失败与取消不提交这些副作用；现有 3 项提交顺序测试通过。

### 聊天文件点击遗漏异步入口

`ChatScreen` 点击仍调用同步 `ChatLinkResolver.resolve`。内部 `runBlocking(Dispatchers.IO)` 会让调用方主线程继续等待，新增但未调用的 `resolveAsync` 没有解决真实入口的阻塞。

修复：[#204](https://github.com/Slacker-LLC/minis-for-android/pull/204)。点击接入 `resolveAsync`，staging 直接使用挂起 RPC 并传播取消；原有 4 项路径测试和编译通过。

### SOUL 异步启动仍把读取故障当成缺失

`initializeAsync` 调用的 `ensureExistsSuspending` 对 `info` 失败使用 `getOrNull`，随后写默认内容。同步入口的 ENOENT 判断没有覆盖实际异步启动，且过宽的错误文本匹配会混淆目标文件缺失和 daemon/backing 不可用。

修复：[#205](https://github.com/Slacker-LLC/minis-for-android/pull/205)。两个入口共用明确缺失判断，已有条目不写、读取故障/取消传播；10 项 SOUL 测试通过。现有 RPC 没有原子 create-if-absent，本修复不保证检查与写入之间的跨进程并发编辑安全。

### 文件链接无条件二次解码可选错文件

`decodePath` 无条件二次解码 `my%2520file.txt`，即使目标 `my%20file.txt` 存在也会选成 `my file.txt`。

修复：[#206](https://github.com/Slacker-LLC/minis-for-android/pull/206)，基于 #204。一次解码后的文件优先，找不到才尝试第二次；8 项测试覆盖真实文件优先级、回退、加号、畸形 percent 和编码问号。

## 本轮集成检查

在上述基线与 #202～#206 的本地集成提交 `f7ea80989ff7dfa474bfd880f80a65fd8d6ba8b9` 上，所有修复无冲突合并；完整 Android 单元测试统计 1,640 项，其中 1,638 项通过、2 项跳过，0 失败、0 错误。Room 仪器测试编译、runtime 包边界 guard、生产 PTY C 的 Linux JVM/子进程测试均通过。该提交仅用于本地集成核验，没有将其推送或合并到 `main`。

## 待设备验收

- ChatGPT OAuth 与 API key 的真实 GPT-6 请求，以及真实生图后的停止、重试、重启恢复和后续图片问答。
- Android 终端首次启动、root 授权、session workspace 一致性和反复关闭后的进程回收。
- 无 VPN→VPN、VPN A→VPN B、VPN→无 VPN 时 guest 域名解析。
- Room 回滚仪器测试、真实文件链接 staging、删除失败和 SOUL 读取故障注入。

没有上述设备证据时，交付必须写明未验证。构建产物、fixture、CI 或宿主 Linux 测试都不能替代。

## 维护规则

1. 新条目必须有当前代码或可复现行为支持；检查实际入口，不能只确认 helper 存在。
2. 修复合并后核对最终 `main` 的代码和测试，再更新基线、移出确认缺口。
3. 不把 namespace 统一、另一套运行时、未证实的理论 hardening 自动升级为修复任务。
4. CI 按改动范围复用现有任务，保留 Release/JNI 检查和设备验证边界。
