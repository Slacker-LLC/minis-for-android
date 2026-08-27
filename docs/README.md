# 文档索引

按「当前契约」与「归档资料」分类；当前行为以运行源码与测试为准。

## 使用与构建

| 文档 | 内容 |
|---|---|
| [`../README.md`](../README.md) | 项目入口、功能、安全边界、执行环境和源码构建说明 |
| [`../CHANGELOG.md`](../CHANGELOG.md) | 开发变更记录 |
| [`../BUILD-CN.md`](../BUILD-CN.md) | 中文完整构建步骤 |
| [`../BUILDING.md`](../BUILDING.md) | English build and troubleshooting guide |

仓库当前不维护预编译 APK、`releases/` 二进制目录或历史 GitHub Release 作为分发入口。

## 当前工程契约

| 文档 | 内容 |
|---|---|
| [`SECURITY.md`](SECURITY.md) | 安全设计、已知缺口与 hardening 要求 |
| [`EXECUTION-ENVIRONMENT.md`](EXECUTION-ENVIRONMENT.md) | Ubuntu 24.04 chroot + minisd Root Broker 当前执行环境 |
| [`DEVELOPMENT-STATUS.md`](DEVELOPMENT-STATUS.md) | 当前已交付、风险和高优先级问题 |
| [`语音对话子项目说明.md`](语音对话子项目说明.md) | 语音输入、ASR、模型请求、TTS 和现有入口 |
| [`specs/minis-url-scheme.md`](specs/minis-url-scheme.md) | `minis://` URL 方案说明 |
| [`specs/debug-server-api.md`](specs/debug-server-api.md) | DebugServer API 参考，以源码与 `rpc.discover` 为最终依据 |

## 归档资料

| 文档 | 内容 |
|---|---|
| [`archive/ios/`](archive/ios/) | iOS/iSH 历史设计资料；当前项目 Android-only |
| [`archive/xiaomi-15-system-linux-eval.md`](archive/xiaomi-15-system-linux-eval.md) | 某型号手机系统级 Linux 移植评估，不是 App 安装指南 |
| [`../README-upstream.md`](../README-upstream.md) | 上游 OpenMinis README 存档，用于许可证与谱系参考 |

## 真实性规则

```text
源码与测试
  > 当前工程契约
  > README / CHANGELOG
  > archive / upstream 历史资料
```

- 当前远程工具面是 MCP Server；旧 Web Remote 已删除；
- 当前 Linux userspace 是 Ubuntu chroot；Alpine + PRoot 已退出主架构；
- Root、Shizuku/AXManager/Sui、Accessibility 是独立能力；
- Debug 构建、本地 APK 产物与未来 production release 必须分开描述；
- 归档文档不得作为当前实现依据。
