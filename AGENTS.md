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
6. 不要平行开一批 Draft 同时改 runtime。下一轮代码只允许按 `03-STORAGE-CONTRACT.md` 对齐存储真源。
7. 安全改动必须有否定用例（拒绝、越界、失败关闭），不能只有成功路径。
8. 不要删除源文件版权头，不要改 `LICENSE` 为非 GPL。法律归属只收缩到 `PROVENANCE.md`，不能假装原创。

## 现行代码身份（尚未迁移）

Gradle 里仍是旧 `applicationId` 与旧 Java 包名，详见 `docs/contracts/06-CURRENT-GAPS.md`。文档与新代码注释使用 Minis / `llc.slacker.minis` 作为目标身份。

## 验证

文档：

```bash
python3 scripts/test_docs_provenance.py
python3 scripts/check_docs_provenance.py
```

其它命令见 `docs/contracts/05-ENGINEERING.md` 与 `CONTRIBUTING.zh-CN.md`。
