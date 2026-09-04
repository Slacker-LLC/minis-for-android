# Minis 七步执行计划 — 历史记录（已被取代）

状态：**不再作为当前执行基线。**

这份文件最初用于拆解一轮早期 runtime / MCP / CI / storage 工作，当时记录过特定 branch、PR、SHA、Draft 数量和阶段阻塞。那些信息只代表当时的工程上下文，不能继续作为今天 `master` 的任务清单。

## 当前应使用的入口

- 当前产品/Android 身份：`docs/contracts/00-IDENTITY.md`
- 当前 Root/minisd/Ubuntu 架构：`docs/contracts/01-ARCHITECTURE.md`
- 持久化真源：`docs/contracts/03-STORAGE-CONTRACT.md`
- 当前已确认缺口：`docs/contracts/06-CURRENT-GAPS.md`
- 属主恢复：`docs/contracts/07-OWNERSHIP-MIGRATION.md`
- 当前工程状态快照：`docs/DEVELOPMENT-STATUS.md`
- 实际工作项：GitHub Issues / Pull Requests

## 原七步的历史主题

原计划曾覆盖：

1. DebugServer/MCP HTTP framing；
2. broker/`su`/Ubuntu 启动关系；
3. Root、broker、rootfs、Ubuntu 诊断状态；
4. canonical storage 与外部挂载；
5. MCP 配置权威；
6. 旧 Draft PR/CI 清理；
7. CI 与 Root 真机验收。

这些主题后续已经被多个独立 Issue/PR、runtime contracts 和当前 master 代码吸收、替换或重新拆分。因此不再在本文件维护“当前第几步”“下一 PR”“当前 Draft 数量”等易失状态。

## 维护规则

不要从本历史计划恢复旧 branch、旧 applicationId、PRoot/Alpine 路径、固定 UID、旧 Gaps 或已完成的迁移阶段。

若需要理解某项旧决策，可查 Git 历史、对应 Issue/PR 或 `docs/archive/`；若要判断当前实现，必须读取最终 `master` 源码/测试并参考 `06-CURRENT-GAPS.md`。
