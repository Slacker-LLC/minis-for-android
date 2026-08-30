# 贡献指南

权威贡献规则是本文件。英文 [CONTRIBUTING.md](CONTRIBUTING.md) 只是指针。

先读 [AGENTS.md](AGENTS.md) 与 [docs/contracts/](docs/contracts/00-IDENTITY.md)。导入外部代码时读 [PROVENANCE.md](PROVENANCE.md)，保留许可证与著作权声明。

## 报缺陷

尽量包含：Android 版本、`versionName`/`versionCode`、机型与 ROM、Root 方案、复现步骤、期望与实际、相关 Provider、脱敏日志。

不要贴 API Key、OAuth、MCP token、签名材料、无关私人数据。

## Pull Request

基于 `master`。一次 PR 只碰一条合同。存储合同落地前不要平行 Draft 改 runtime。

必须遵守：

1. App 是应用/数据库/工具权限/审批的权威；Linux 持久化真源固定在 `/data/adb/minis`，由 `minisd` 在 mount namespace 建立前准备。
2. 新工具走现有 Tool Registry 与权限/结果模型。
3. Root 走 `minisd`；禁止新增把模型输出送进 `su -c` 的路径。
4. 副作用操作保留审批、checkpoint、恢复语义。
5. 能力靠探测，不靠名字或「看起来是 root」。
6. 安全路径 fail-closed。
7. 行为变更先改中文合同，再改代码与测试。
8. 保留 GPL 与第三方义务；不要删版权头。

## Runtime

执行链：`minisd` → mount namespace + bind + chroot → Ubuntu 24.04。

持久化输入：`/data/adb/minis/workspace`、`sessions`、`memory`、`skills`、`shared`、`home`。不要引入替代 backing。不要全局关 SELinux。

MCP 必须进入同一套工具注册与权限。普通 Android API、无障碍、Shizuku、Root 是不同能力。

## 验证

```bash
python3 scripts/test_docs_provenance.py
python3 scripts/check_docs_provenance.py
```

Android / Rust / rootfs 命令见 [05-ENGINEERING.md](docs/contracts/05-ENGINEERING.md)。
