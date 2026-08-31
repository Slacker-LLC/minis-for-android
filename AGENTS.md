# Minis for Android — Agent 宪法

中文规范是行为定义。英文 README / `docs/*.md` 技术稿是摘要或实现说明，冲突时以本文件与 `docs/contracts/` 为准。

完整权威顺序见 `docs/contracts/05-ENGINEERING.md`。

## 这是什么

独立产品：**面向已 Root Android 设备的 AI Agent Runtime**。

- 组织：Slacker-LLC
- 域名：slacker.llc
- 仓库：`Slacker-LLC/minis-for-android`
- 目标 `applicationId` / 未来 Java 包根：`llc.slacker.minis`
- 许可证：GPL-3.0（法律来源只写在 `PROVENANCE.md`，不要写进产品叙事）

不要把本项目写成任何外部项目的非官方分支、镜像或持续同步 fork。不要引入已废弃的远程桌面/隧道控制面，或已废弃的 userspace 模拟执行后端。

## 必读合同

| 文件 | 用途 |
|---|---|
| `docs/contracts/00-IDENTITY.md` | 产品身份、目标包名、GitHub About |
| `docs/contracts/01-ARCHITECTURE.md` | 目标架构与职责边界 |
| `docs/contracts/02-CONSTRAINTS.md` | 硬限制（fail-closed） |
| `docs/contracts/03-STORAGE-CONTRACT.md` | 持久化真源 |
| `docs/contracts/04-SECURITY-CONTRACT.md` | Root / MCP / 网络 / 密钥 |
| `docs/contracts/05-ENGINEERING.md` | 工程流程、PR、测试 |
| `docs/contracts/06-CURRENT-GAPS.md` | **现状**（代码尚未对齐合同的地方） |

改行为前先读对应合同。改存储/runtime 前必须读 `03` 和 `06`。

## 硬规则

1. 持久化 Linux 数据只认 `/data/adb/minis/{workspace,sessions,memory,skills,shared,home}`。禁止把 App 私有目录当 guest 真源，禁止 tmpfs 当真源。
2. 新的 Root 执行必须走 `minisd` 结构化 RPC。禁止新增「模型输出直接 `su -c`」通道。
3. 不要为了兼容去关 SELinux。不要用 debug 签名冒充 release。
4. 合同写**目标**；`06-CURRENT-GAPS.md` 写**现状**。禁止把未落地行为写成已实现。
5. 本轮不要改 Gradle `applicationId`，不要搬 Java 包目录。身份迁移是后续独立工作，目标名已经定死。
6. 不要平行开一批 Draft 同时改 runtime。存储真源已按 `03-STORAGE-CONTRACT.md` 对齐；下一优先是发行自举（G2）或堵住 `su -c` 旁路（G4），不要混在同一 PR。
7. 安全改动必须有否定用例（拒绝、越界、失败关闭），不能只有成功路径。
8. 不要删除源文件版权头，不要改 `LICENSE` 为非 GPL。法律归属只收缩到 `PROVENANCE.md`，不能假装原创。
9.特别：

“

以最小的充分变更完成当前任务。

### 编辑前

- 直接阅读相关代码、测试和配置。不要基于搜索片段或猜测工作。
- 如果需求模糊或前提未验证，在此基础上构建之前先解决它。
- 陈述一个最小计划：
  - **结果** — 请求的确切行为
  - **非目标** — 本任务不会做什么
  - **文件** — 预期变更的最小文件集
  - **证明** — 将证明变更有效的检查
- 从一条实现路径开始。只有当任务确实有独立部分时才拆分工作。

### 编辑中

- 在添加任何新内容之前，重用现有代码、辅助工具、模式和测试设置。
- 在根本原因处修复错误。不要围绕错误的假设堆叠补丁。
- 仅为本任务中的第二个真实调用者或明确需求添加抽象、适配器或配置层。
- 保留请求变更之外的行为。
- 不要为无人询问的罕见或未来情况设计。
- 删除你替换的代码。只有当兼容性是明确需求时才保留旧路径。

### 暂停并确认

只读发现始终允许。如果任务尚未授权，在以下操作前获得批准：

- 实质性扩展范围或触及无关文件
- 添加依赖、框架、服务或新测试基础设施
- 更改公共 API、模式、存储格式或线格式
- 删除或覆盖用户数据、丢弃未提交的工作、重写历史或丢弃数据
- 保留同一行为的两个实现同时存在

### 测试

- 运行最窄的现有测试，这些测试会演练变更后的行为。
- 在创建新测试文件之前，扩展最相关的现有测试。
- 仅当变更的用户可观察行为未覆盖，或用户要求添加测试时，才添加测试。
- 每个新测试必须保护明确的验收标准或回归风险。
- 不要为本任务单独回填无关覆盖率或引入测试基础设施。
- 不要使用通过的测试作为额外抽象或范围的理由。

### 如果计划增长

当工作开始添加未来使用层、工作围绕栈、无关清理，或为未说明行为添加测试时停止。重写更小的计划并确认新范围。

### 完成意味着

- 请求的行为有效且验收标准得到满足
- 相关检查通过，并报告确切的命令和结果
- 每个触及的文件都是必要的，且差异中不包含无关内容
- 没有调试代码、备份副本、死路径或临时文件残留
- 假设、限制和未验证的运行时行为被清楚陈述

”

## 现行代码身份（尚未迁移）

Gradle 里仍是旧 `applicationId` 与旧 Java 包名，详见 `docs/contracts/06-CURRENT-GAPS.md`。文档与新代码注释使用 Minis / `llc.slacker.minis` 作为目标身份。

## 验证

文档：

```bash
python3 scripts/test_docs_provenance.py
python3 scripts/check_docs_provenance.py
```

其它命令见 `docs/contracts/05-ENGINEERING.md` 与 `CONTRIBUTING.zh-CN.md`。