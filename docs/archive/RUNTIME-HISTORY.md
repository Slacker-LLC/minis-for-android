# Runtime 迁移历史

> 历史参考，不定义当前实现。现役行为见[架构合同](../contracts/01-ARCHITECTURE.md)、[存储合同](../contracts/03-STORAGE-CONTRACT.md)与[执行环境](../EXECUTION-ENVIRONMENT.md)。

运行时经历了旧 Alpine/PRoot、Ubuntu chroot + 特权 broker，再到 App-owned Direct Ubuntu 三个阶段。当前生产不恢复旧双栈或通用 broker；Guest 以实际 App UID/GID 运行，用户数据从 App 私有目录派生，Root-owned rootfs 可独立替换。网络 HTTP/CONNECT helper 是独立兼容组件，不是旧 broker 的替身。

2026-09-12 审计提交 `53dada42` 经 `422cc29f` 合入 main。文档整理删除了五份旧 PR patch 副本（#61、#63、#69、#76、#92）及已被合同替代的七步计划；可通过 Git 历史恢复，不删除法律来源、独立历史决策或实测报告。

## 历史 Issue 队列快照（不是当前开放状态）

以下编号来自 2026-09-10 的队列记录，本次未重新查询远端开放/关闭状态，也不代表每项缺陷在 `422cc29f` 仍存在。处理前必须核对当前源码与 Issue：

### 安全 / 数据完整性

- #230 OAuth 回调与 token 响应日志泄露风险；
- #231 备份恢复读取端缺少资源上限；
- #232 加密备份允许未认证的额外 payload；
- #233 旧闹钟迁移 idempotency 问题。

### Android / Chat / 文件 / 终端

- #229 进程恢复时文件浏览/预览 Holder 丢失；
- #184 消息删除 DB-first 一致性；
- #185 SOUL.md 默认写入与读取失败区分；
- #186 PTY UID/GID 与 session workspace；
- #187 文件链接 staging 主线程 I/O；
- #188 粘贴内容提交前消费；
- #189 PTY child reap/zombie；
- #190 VPN 下 Ubuntu DNS/网络切换；
- #183 `minis://` 双编码与 `+` 解码；
- #182 Release VAD JNI/R8 兼容。

### UI / 维护性

- #192 Root 权限模式页面导航入口；
- #216 ProviderRepository 同步 `runBlocking` 持久化；
- #217 ChatViewModel 职责拆分；
- #218 ChatScreen / StreamingMarkdownText 拆分；
- #223 rclone AAR 构建前 16 KiB/ABI 校验建议。

其中部分 Issue 的正文仍引用旧 runtime/broker 术语，或描述的是早于 PR #235 的代码。处理前必须先对最终当前源码重新审计；如果问题已被后续提交解决，应关闭/更新 Issue，而不是照旧正文重复实现。

法律来源只维护在 [PROVENANCE.md](../../PROVENANCE.md)。
