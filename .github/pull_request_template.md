## 摘要

<!-- 改了什么、为什么。对照哪条 docs/contracts。 -->

## 范围

- [ ] 仅文档 / 合同
- [ ] Android app/runtime
- [ ] Provider / 模型
- [ ] Android 原生工具
- [ ] MCP
- [ ] Direct Ubuntu / rootfs / mount / privilege drop
- [ ] 网络兼容 proxy / DNS / VPN / BPF
- [ ] 语音 / 助手 / overlay
- [ ] 构建 / CI / 发布

## 合同

- [ ] 已读 `AGENTS.md` 与相关 `docs/contracts/*`
- [ ] 未把历史 Issue/PR/旧 gap 写成当前事实
- [ ] 未把旧 `/data/adb/minis/{workspace,sessions,memory,skills,shared,home,mcp-servers}` 重新作为现役用户数据真源
- [ ] 未恢复旧 broker、PRoot/Alpine 双栈或模型可控 Root command/RPC
- [ ] 未把网络代理和 Root/chroot 混写成同一权限/架构概念

## 验证

- [ ] `python3 scripts/test_docs_provenance.py` 与 `python3 scripts/check_docs_provenance.py`（改文档时）
- [ ] `python3 scripts/test_build_cleanup_guard.py` 与 `python3 scripts/check_build_cleanup.py`（改 runtime/build 时）
- [ ] `bash scripts/check-runtime-package-boundary.sh`（改 runtime 边界时）
- [ ] 相关 Android unit tests
- [ ] `:app:lintDebug` / `:app:lintRelease`（相关 Android 源/资源）
- [ ] network helper Rust fmt / Clippy / tests（改 `src/native/root-network-proxy/`）
- [ ] 真机验证（仅在声称 Root/SELinux/mount/VPN/DNS/BPF/Fake-IP/OEM 行为时）

## 安全

- [ ] 无密钥 / token / 签名材料进仓库或未脱敏日志
- [ ] 未新增模型/Agent/MCP 可控的 `su -c` / `DirectRootRunner` 通道
- [ ] `root.shell` 仍为结构化 basename + argv、本地专用且 MCP 不可见，未扩展成原始 shell 字符串或通用 Root RPC
- [ ] Guest 仍以真实 App UID/GID 运行并清空 supplementary groups/capabilities
- [ ] 网络 helper 仍固定 loopback 且无命令/文件/插件/通用 RPC 面
- [ ] 路径 / 输出 / payload 校验仍然 fail-closed
- [ ] 中文合同已随行为更新
- [ ] GPL 与第三方声明仍在
