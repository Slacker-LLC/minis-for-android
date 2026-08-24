# 文档索引

按「当前契约」与「归档资料」分类;当前行为以运行源码与测试为准。

## 使用与构建

| 文档 | 内容 |
|---|---|
| [`../README.md`](../README.md) | 项目入口、下载、功能、安全边界、执行环境说明 |
| [`../RELEASE-NOTES.md`](../RELEASE-NOTES.md) | 当前发布说明、校验值和限制 |
| [`../CHANGELOG.md`](../CHANGELOG.md) | 版本变更记录 |
| [`../BUILD-CN.md`](../BUILD-CN.md) | 中文完整构建步骤 |
| [`../BUILDING.md`](../BUILDING.md) | English build and troubleshooting guide |
| [`../releases/README.md`](../releases/README.md) | 仓库内 APK 清单与哈希 |

## 当前工程契约

| 文档 | 内容 |
|---|---|
| [`SECURITY.md`](SECURITY.md) | 安全设计:凭据、MCP 授权、审批、路径与日志边界 |
| [`EXECUTION-ENVIRONMENT.md`](EXECUTION-ENVIRONMENT.md) | 执行环境：Ubuntu 24.04 chroot（minisd）+ Root su 现状与边界（P2 已替换 PRoot/Alpine） |
| [`DEVELOPMENT-STATUS.md`](DEVELOPMENT-STATUS.md) | 当前已交付、验证和未交付项 |
| [`specs/minis-url-scheme.md`](specs/minis-url-scheme.md) | `minis://` URL 方案说明 |
| [`specs/debug-server-api.md`](specs/debug-server-api.md) | DebugServer API 参考;以 `rpc.discover` 与源码为最终依据 |

## 归档资料

| 文档 | 内容 |
|---|---|
| [`archive/ios/`](archive/ios/) | iOS/iSH 历史设计资料;本项目 Android-only,不实现 |
| [`archive/xiaomi-15-system-linux-eval.md`](archive/xiaomi-15-system-linux-eval.md) | 某型号手机系统级 Linux 移植的可行性评估;不是 App 安装指南 |
| [`../README-upstream.md`](../README-upstream.md) | 上游 OpenMinis README 存档(许可证与谱系参考) |

## 真实性规则

- 当前行为优先级:运行源码与测试 → 当前契约文档 → README/发布说明;
- 远程面只有 MCP Server(`127.0.0.1:18789`)与本地 DebugServer;Web Remote 已删除;
- PRoot/容器没有独立 kernel;当前仅实现 Ubuntu chroot;
- Root/Shizuku/Accessibility 是三条独立能力,不构成权限等级链;
- Strict schema 的空兼容响应不代表完整 Subagent、Job 或 Queue 功能已经交付;
- Debug APK、生产 release 与真机系统授权必须分开描述。
