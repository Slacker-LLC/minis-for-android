# 贡献指南

权威贡献规则是本文件。英文 [CONTRIBUTING.md](CONTRIBUTING.md) 只是指针。

先读 [AGENTS.md](AGENTS.md) 与 [docs/contracts/](docs/contracts/00-IDENTITY.md)。导入外部代码时读 [PROVENANCE.md](PROVENANCE.md)，保留许可证与著作权声明。

## 报缺陷

尽量包含：Android 版本、`versionName`/`versionCode`、机型与 ROM、Root 方案、复现步骤、期望与实际、相关 Provider、脱敏日志。

不要贴 API Key、OAuth、MCP token、签名材料、无关私人数据。

## Pull Request

默认基于当前 `main`。一次 PR 只解决一个明确问题或一条合同边界；如果是 stacked PR，必须明确 base/head 与依赖，不要把其它分支的改动混入。

必须遵守：

1. App 是应用/数据库/工具权限/审批的权威；Linux 持久化真源固定在 `/data/adb/minis`，由 `minisd` 在 mount namespace 建立前准备。
2. `applicationId` 当前为 `llc.slacker.minis`；namespace 当前为 `com.openminis.app`。不要把 namespace 全库重命名混进无关 PR。
3. Guest UID/GID 动态使用设备实际 App identity，禁止写死 `10000`。
4. 新工具走现有 Tool Registry 与权限/结果模型。
5. Root 走 `minisd`；禁止新增把模型输出送进 `su -c` 的路径。
6. Runtime 是 Root + `minisd` + Ubuntu 24.04；不要恢复 PRoot/Alpine 双栈。
7. Session 执行保持 session workspace/namespace 语义，不能用全局 workspace 绕过隔离。
8. 副作用操作保留审批、checkpoint、持久化与恢复语义。
9. 能力靠探测，不靠名字或“看起来是 root”。安全路径 fail-closed。
10. 行为变更同步更新相关中文合同/当前缺口与测试。
11. 保留 GPL 与第三方义务；不要删版权头。

## Runtime

执行链：`Android App → minisd → mount namespace + bind + chroot → Ubuntu 24.04`。

持久化输入：`/data/adb/minis/workspace`、`sessions`、`memory`、`skills`、`shared`、`home`。rootfs 可替换，用户数据不可随 rootfs 升级被替换。不要引入替代 backing，不要全局关闭 SELinux。

MCP 必须进入同一套工具注册与权限。普通 Android API、无障碍、Shizuku、Root 是不同能力。

## 当前事实与历史记录

判断“当前实现是什么”时，以最终 `main` 源码和测试为准；GitHub 已合并 PR、历史 Issue 文档和旧执行计划只能解释历史。确认的现状缺口写入 [`docs/contracts/06-CURRENT-GAPS.md`](docs/contracts/06-CURRENT-GAPS.md)。

## 验证

```bash
python3 scripts/test_docs_provenance.py
python3 scripts/check_docs_provenance.py
```

Android / Rust / rootfs 命令见 [05-ENGINEERING.md](docs/contracts/05-ENGINEERING.md)。
