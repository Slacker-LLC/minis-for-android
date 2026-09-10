## 摘要

<!-- 改了什么、为什么。对照哪条 docs/contracts。 -->

## 范围

- [ ] 仅文档 / 合同
- [ ] Android app/runtime
- [ ] Provider / 模型
- [ ] Android 原生工具
- [ ] MCP
- [ ] Direct Ubuntu / Root 基础设施 / Root 网络代理
- [ ] 语音 / 助手 / overlay
- [ ] 构建 / CI / 发布

## 合同

- [ ] 已读 `AGENTS.md` 与相关 `docs/contracts/*`
- [ ] 未把 `06-CURRENT-GAPS.md` 的历史基线写成当前事实
- [ ] 未把旧 `/data/adb/minis/{workspace,sessions,memory,skills,shared,home}` 重新作为现役用户数据真源
- [ ] 未恢复旧 broker、PRoot/Alpine 双栈或模型可控 Root command/RPC

## 验证

<!-- 实际跑过的命令 -->

- [ ] `python3 scripts/test_docs_provenance.py` 与 `python3 scripts/check_docs_provenance.py`（改文档时）
- [ ] `python3 scripts/test_build_cleanup_guard.py` 与 `python3 scripts/check_build_cleanup.py`（改 runtime/build 时）
- [ ] `bash scripts/check-runtime-package-boundary.sh`（改 runtime 边界时）
- [ ] 相关 Android unit tests
- [ ] `:app:lintDebug`（改了 Android 源/资源）
- [ ] Root network proxy Rust fmt / Clippy / tests（改了 `src/native/root-network-proxy/`）

## 安全

- [ ] 无密钥 / token / 签名材料进仓库
- [ ] 未新增模型/Agent/MCP 可控的 `su -c` / `DirectRootRunner` 通道
- [ ] Guest 仍以真实 App UID/GID 运行并清空 supplementary groups/capabilities
- [ ] Root 网络代理仍固定 loopback 且无命令/文件/通用 RPC 面
- [ ] 路径 / 输出 / payload 校验仍然 fail-closed
- [ ] 中文合同已随行为更新
- [ ] GPL 与第三方声明仍在
