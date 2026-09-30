# 05 — 工程规范

## 怎么判断“当前事实”

```text
最终目标分支源码与测试 → 当前实现事实
AGENTS.md + docs/contracts/* → 长期行为边界
06-CURRENT-GAPS.md → 当前确认缺口 / 待设备验收 / tracker 状态说明
README / 专题 docs → 面向读者说明
历史 Issue/PR/计划/archive → 历史证据，不是当前事实
```

合同与代码冲突时先核对实现与维护者意图：代码是缺陷就修代码并记录 gap；合同过时就更新合同，不要把正确代码改回旧阶段。

## 分支与 PR

- 默认基于最新 `main`；stacked PR 按明确 base/head 工作。
- 一次 PR 只解决一个问题或一条可独立验收边界。
- 不 force-push、不重写历史，除非维护者对该具体操作明确授权。
- 合并前核对最终 diff；合并后验证最终目标分支，不只看 PR 页面曾显示通过。
- 迁移/reference 分支在 PR 合并后继续产生的新提交不会自动进入 `main`；需要单独合并时必须明确处理。

## 当前 Android 身份

- `applicationId = llc.slacker.minis`；
- `namespace = com.openminis.app`；
- 两者不同不是 bug；全库 package 重命名必须作为独立任务。

## Runtime 边界

- 产品 runtime：Android App-owned Direct Ubuntu 24.04 chroot；
- 不恢复旧 broker、PRoot/Alpine 兼容层或双运行时；
- 用户数据：App-owned backing；Root-owned 持久 runtime 主要是 `/data/adb/minis/rootfs` 等明确基础设施；
- UID/GID 动态取得，guest 通过 `setpriv` 降权并清空 groups/capabilities；禁止固定 `10000`；
- Session 相关入口保持 session workspace 语义；
- Root launcher 的原始脚本入口只执行 App 构造的基础设施脚本；本地 Agent 的 Root 能力使用结构化 `root.shell`（tool basename + argv），通过可信路径、参数/超时/输出和进程清理边界，且保持 local-only、MCP 不可见。不得新增 raw command、Root RPC 或通用 broker。

## VScreen 与例程权限档位

- VScreen 仅在 Shizuku 协议 UserService 自检通过后启用。该服务的身份可以是 shell（2000，adb 启动的 Shizuku）或 root（0，以 Root 启动的 Shizuku / Sui），由用户自己选择如何运行 Shizuku；App 不自行通过 `su`/`DirectRootRunner` 启动任何 VScreen 进程，也不得以 KernelSU 直连、LSPosed、视频/OCR 或未经认证的 socket 作为后备路径。其它 uid 一律拒绝。拉起应用走系统的 `cmd activity start-activity`（参数向量由已校验的包名/组件构造，不经 shell）。
- 物理屏与虚拟屏的 UI observation 均不得序列化 Android 标记为 password 节点的 text、content description 或 hint；仅可保留字段结构及 password 标记。
- `ScheduledTask` 权限档位保存在现有 JSON 存储中，不新增或迁移 Room schema。新建例程默认为 `READ_ONLY`；旧 JSON 缺字段时保留既有 `FULL` 行为，未知/畸形档位按 `READ_ONLY` 处理。
- 档位仅由 `ScheduledAgentRunner` 对正在运行的例程 session 建立；不同 session 的委派目标和唤醒回合不继承该档位，继续由 F3 既有无人值守策略保护；若共用同一 `sessionId`，所有回合共享最严格的活跃档位，denial preview 目前也按 session 收集而非按 turn 归属。
- `READ_ONLY` 的 shell 采用纯函数 allowlist，MCP 与任意代码/包安装工具拒绝；文件写工具及 shell 输出仅可写入该 session 的 `/var/minis/offloads`（Guest `/tmp` 别名），并在运行时校验解析后的目标仍位于 session 目录内。
- `FULL` 需在原生例程编辑器确认后才能新建或提权。档位不提供网络出口隔离；浏览器、网络工具及 `FULL` 命令仍受现有应用权限约束，但网络请求没有此处新增的系统级拦截。

## 网络边界

- 网络代理和 Root/chroot 分开设计；HTTP/CONNECT 代理协议本身不依赖 Root。
- 当前 helper 可因 Android UID/VPN/BPF 出站兼容以特权身份运行，但不能扩展成通用 Root 服务。
- 网络 helper 的协议级 host tests 不能替代真实 Android VPN/DNS/BPF/Fake-IP 验收。

## 外部实现参考

其它实现只能按功能选择性参考，不能整体替换本仓库已经明确分叉的 runtime/storage/PTY/network 架构。共享 UI、Chat、Provider、Markdown、Voice 等修复可以在核对本仓库实际代码后移植；Root/runtime 方案必须服从本仓库 contracts。

## 文档

- 行为或长期边界改变时，同步更新对应中文合同。
- 历史 Issue/PR 实施文档必须明确是历史，不能承担动态状态列表职责。
- `06-CURRENT-GAPS.md` 只写重新核验后的当前状态；旧审计快照应留在 Git 历史/archive，而不是长期堆在“当前缺口”正文里。
- 法律来源只在 `PROVENANCE.md` 维护。
- “Root 网络代理”这类实现名称不得被误写为架构耦合关系；必须区分协议能力和部署身份。

## 验证（按改动范围取最小充分集）

文档：

```bash
python3 scripts/test_docs_provenance.py
python3 scripts/check_docs_provenance.py
```

Runtime boundary / payload：

```bash
python3 scripts/test_build_cleanup_guard.py
python3 scripts/check_build_cleanup.py
bash scripts/test-build-ubuntu-rootfs-verification.sh
bash scripts/test-runtime-payload-verification.sh
bash scripts/check-runtime-package-boundary.sh
```

网络 helper：

```bash
cargo fmt --manifest-path src/native/root-network-proxy/Cargo.toml --all -- --check
cargo clippy --locked --manifest-path src/native/root-network-proxy/Cargo.toml --all-targets -- -D warnings
cargo test --locked --manifest-path src/native/root-network-proxy/Cargo.toml
bash scripts/build-root-network-proxy-android.sh
bash scripts/verify-root-network-proxy.sh dist
```

Android：

```bash
cd src/android
./gradlew :app:compileDebugKotlin --no-daemon
./gradlew :app:testDebugUnitTest --no-daemon
./gradlew :app:lintDebug --no-daemon
./gradlew :app:lintRelease --no-daemon
```

VScreen/V4 的 JVM 安全回归至少覆盖 `ScheduledReadOnlyPolicyTest`、`ScheduledTaskPermissionTierJsonTest`、
`ScheduledTaskTierMutationPolicyTest` 与 `OffloadPermissionManagerScheduledTierTest`。这些宿主测试只证明解析/权限策略，不能替代 Shizuku UserService 和 OEM 显示行为的真机验收。

Release/R8/JNI 敏感改动必须跑对应 Release 检查；Debug 不能替代。Root、mount、SELinux、VPN/DNS/BPF/Fake-IP、OEM 生命周期等设备行为，只有明确真机实测后才能声称通过。

## Agent 工作方式

- 先读当前目标分支代码、测试和相关合同；
- 优先最小修复，不为理论风险自动建立大型基础设施；
- 替换旧实现后删除死路径，不保留无需求兼容 shim；
- 已修问题如果回归，按当前代码重新修复并补最窄回归测试。
