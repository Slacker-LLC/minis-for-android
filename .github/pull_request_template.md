## 摘要

<!-- 改了什么、为什么。对照哪条 docs/contracts。 -->

## 范围

- [ ] 仅文档 / 合同
- [ ] Android app/runtime
- [ ] Provider / 模型
- [ ] Android 原生工具
- [ ] MCP
- [ ] `minisd` / Ubuntu
- [ ] 语音 / 助手 / overlay
- [ ] 构建 / CI / 发布

## 合同

- [ ] 已读 `AGENTS.md` 与相关 `docs/contracts/*`
- [ ] 未把 `06-CURRENT-GAPS.md` 里的未完成项写成已实现
- [ ] 未在本 PR 平行改存储真源以外的 runtime 重构（除非本 PR 就是在修 G1）

## 验证

<!-- 实际跑过的命令 -->

- [ ] `python3 scripts/test_docs_provenance.py` 与 `check_docs_provenance.py`（改文档时）
- [ ] 相关 Android unit tests
- [ ] `:app:lintDebug`（改了 Android 源/资源）
- [ ] Rust fmt / Clippy / tests（改了 `src/native/minisd/`）

## 安全

- [ ] 无密钥 / token / 签名材料进仓库
- [ ] 未新增模型可控的 `su -c` 通道
- [ ] 路径 / IPC / 输出仍然 fail-closed
- [ ] 中文合同已随行为更新
- [ ] GPL 与第三方声明仍在
