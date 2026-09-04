# 06 — 当前已确认缺口

本文只记录**当前 `master` 已有代码证据或可复现故障支持的修复项**。它不是理论安全清单，也不保存已经完成的旧阶段任务。

审计基线：`master` `6f10d1b3f413d37aca5c21465e8e71ef3eb12120`，2026-09-04。master 前进后必须重新核对最终代码；历史 PR 曾经合并过某项修复，不等于该修复今天仍存在。

## 已经是当前事实，不再列为缺口

- `applicationId = llc.slacker.minis`；namespace/Kotlin 包仍为 `com.openminis.app`。
- 产品 runtime 为 Root + `minisd` + Ubuntu 24.04 chroot，不使用 PRoot/Alpine 作为产品后端。
- canonical 用户数据根为 `/data/adb/minis/{workspace,sessions,memory,skills,shared,home}`。
- Ubuntu rootfs 是可替换 runtime state，用户数据不随 rootfs 事务替换。
- Guest UID/GID 的合同是真实 App identity，不是固定 `10000`。

下面只列仍需修复的确认问题。

## #182 — Release VAD 的 R8/JNI keep 缺失

当前 `proguard-rules.pro` 没有 RealTimeCutVAD JNI 回调所需的 keep 规则。Release minify 可能重命名/移除 JNI 通过名称查找的回调，造成语音检测在 Release 崩溃或失效。

修复边界：同步最窄 keep 规则，并用 Release/minified 构建验证；Debug 通过不能替代。

## #183 — `minis://` 路径解码容错不足

当前聊天文件链接解析对双重 percent encoding 与字面 `+` 的处理不够稳健，可能把合法 guest 路径解成错误路径。

修复边界：采用不把 `+` 隐式当空格的 percent decode，并在需要时根据实际存在性尝试第二次 decode；畸形 `%` 必须安全失败/降级。

## #184 — 消息删除 DB/UI 原子性回归

最终 master 的 `ChatViewModel.deleteSingleAssistantMessage` / `deleteFromMessage` 会先修改 UI、prompt/stream 状态和 memory，再异步删除 Room 数据。DAO 异常、协程取消或生命周期结束可造成 UI/memory 与数据库不一致。

修复边界：沿现有 Repository/Room 边界做 DB-first；数据库删除成功后再提交 UI、agentHistory、memory 等状态。不要为此建立两阶段提交或事件溯源框架。

## #185 — SOUL 默认内容可能覆盖用户编辑

`SoulStore.ensureExistsSuspending` 把 `WorkspaceFileClient.info` 的任意失败都折叠成“文件不存在”。如果读取因 broker、权限、网络式 IPC/运行时异常失败，而后续写入成功，可能用默认内容覆盖现有 `SOUL.md`。

修复边界：只有明确 NotFound/ENOENT 才 seed；超时、权限、broker/runtime、取消等错误不得写默认内容。若现有 RPC 支持 create-if-absent/no-overwrite，应优先复用。

## #186 — Terminal PTY 没有遵守真实 guest identity/session workspace

当前 Terminal PTY 路径仍存在固定 `--uid 10000 --gid 10000`、忽略 `sessionId`、使用全局 `/workspace` 的行为，与普通 `ubuntu.exec` 的动态 identity/session root 模型不一致。

修复边界：复用现有 minisd/runtime identity 与 session 准备能力，让同一 session 的 Terminal 与 Agent shell 看到一致 workspace；不恢复 PRoot，不新建第二套执行架构。

## #187 — ChatLink 文件 staging 可能阻塞主线程

聊天文件链接解析/staging 最终可进入 blocking broker/file copy，而调用方位于 Compose 主线程协程路径，存在 UI 卡顿/ANR 风险。

修复边界：路径解析可保持同步；实际 broker/file I/O 必须 suspend 或切到 `Dispatchers.IO`，并正确响应取消。与 #183 的路径解码逻辑分开处理。

## #188 — 粘贴内容在消息落库前被消费

部分发送路径在 `chatRepository.appendMessage` 成功前就从 `_pastedTexts` 移除已消费项。持久化失败/取消时，消息没落库但 composer 粘贴状态已丢失；生成的 staging/mediaRef 也可能留下残余。

修复边界：消息持久化成功后再消费 pasted IDs；失败/取消保留 composer 状态，并清理或延后不可达 staging 产物。覆盖主发送、queued/drain 等实际调用链。

## #189 — PTY 子进程退出后缺少稳定 reap

Native PTY 通过 `forkpty` 创建子进程，并已有 `waitpid`/`waitFor` 能力，但 Kotlin Terminal 生命周期没有稳定调用它。反复打开/关闭终端可能累积 zombie。

修复边界：在不阻塞 Main 的前提下回收对应 PID，并处理正常退出、主动关闭和重复关闭竞态。可与 #186 同模块实现，但验收边界保持独立。

## #190 — VPN 开启时 Ubuntu guest DNS 不可用

实际可复现：Android 开启 VPN 后，Ubuntu sandbox/chroot 可能没有可用 DNS，域名解析失败。核心问题是当前有效 Android/VPN resolver 没有被正确继承或在网络切换后刷新。

修复边界：优先读取并同步当前实际生效网络（含 VPN）的 DNS；无 VPN→VPN、VPN A→VPN B、VPN→无 VPN 都应刷新 guest resolver，不依赖重启 App/minisd。公共 DNS fallback 的隐私/策略权衡是独立问题，本项不通过删除 fallback 来“修复”。

## 不在本表自动升级为必修的事项

仅有理论风险、没有当前复现或属于明确产品取舍的项目，不写进“确认缺口”。例如 namespace 统一、恢复 PRoot、完整 ownership WAL、图片-only regenerate 的媒体重放架构，以及尚未证明实际影响的 FileProvider/WebView/proxy/rclone/DNS fallback 策略讨论。

它们可以保留历史讨论，但除非出现新证据或维护者明确立项，不应驱动主线重构。

## 维护规则

1. 新增本表条目必须有当前代码证据或可复现行为。
2. 修复 PR 合并后，先核对最终 `master` 实际代码/测试；确认仍保留修复后再删除条目。
3. 如果只是某个历史 PR“曾修过”，但最终 master 又回归，条目继续保留。
4. 不把设备未验证行为写成已通过；不把理论 hardening 写成已确认用户故障。
