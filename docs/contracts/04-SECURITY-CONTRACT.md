# 04 — 安全合同

本项目是高权限 Agent：Root、Accessibility、包管理、MCP、凭证与设备控制都是独立安全边界。

## 调用方隔离

本地 Agent、MCP 调用方、Android 服务、Direct Ubuntu 内部 Root 基础设施和网络兼容 helper 是不同调用方/能力域。UI 勾选不是安全边界；执行入口必须再次检查授权。

未知工具默认拒绝。仅本地工具不得暴露给 MCP。

## Root

- 当前不存在生产 Root broker / 通用 Root RPC。
- `DirectRootRunner` 是 internal launcher，直接脚本入口只服务于 App 构造的受控基础设施；本地 Agent 的 Root 请求走结构化 `root.shell`，由可信 Android system executable 解析、argv 边界、超时/输出上限和进程清理统一约束。
- `root.shell` 是 local-only：本地 Agent 可以使用它完成需要 Root 的 Android 工具动作，MCP 不可见且不可调用；它不接受 `command` 字符串，不提供 host 文件或通用 RPC 面。
- `root.shell` 的 tool 不得是会执行其它代码的程序：shell、`su` 类前端、`toybox`/`busybox` 等多合一程序、`env`/`xargs`/`nohup`/`nsenter` 等启动器、解释器，以及 `find -exec`、`tar --to-command`、`sqlite3 .shell`、`ip netns exec` 这类执行命令的参数。否则数组形式的 argv 照样等于 raw Root 命令。这是在可信路径与 argv 边界之上的拒绝清单，不代表其余 Root 工具无副作用。
- `DirectRootRunner` 的内部脚本动作限于确实需要权限的 rootfs 探测/修复、namespace/bind/chroot、受控 legacy 数据迁移及同类明确基础设施需求；本地 Agent 的 `root.shell` 只走结构化 Android tool/argv 合同，不得被扩展成 raw shell、host 文件或通用 RPC。
- 例外之一：`minis-apt`（guest CLI，经 `AptOffloadHandler`）。guest 以 App UID 运行、无法使用 apt，因此由 App 代为在 rootfs 的一次性 mount namespace 内以 Root 执行 `apt-get update|install|remove`。Agent 只给出包名，命令全部由 App 构造（`AptCommandPolicy`）：只接受 `^[a-z0-9][a-z0-9+.-]{0,62}$` 形式的纯包名（拒绝选项、版本/发行版指定、路径、shell 字符），每次最多 20 个；`remove` 先用 apt 的模拟结果检查，若会连带移除运行时必需的包（`UbuntuProvisioner.BASE_PACKAGES` 等）则在移除前拒绝；同一时间只运行一个；走与其它 guest CLI 相同的权限开关（`apt_cli`，设置里可关闭）；只影响 Ubuntu 环境，不触及 Android 系统与宿主文件。它不是通用 Root 执行入口，不接受原始命令。
- 例外之二：`android-root-cli`（guest CLI，经 `RootCliOffloadHandler` 与 `RootProcess`）。它以 root 运行 Android 系统工具：`package`/`permission`/`activity`/`display`/`settings`/`user`/`network`/`input`/`notification`/`file`/`device` 等整理过的子命令（参数向量由 App 构造），外加 `exec`——把剩余参数拼成一条命令交给 root 的 `sh -c`。`exec` 是有意给本地 Agent 的原始 root shell：2026-10 起项目只走 Root，按维护者的决定给 Agent 尽量大的权限，只防止把手机弄坏；此前同一能力是经 Shizuku 的 `android-shizuku-cli`（Shizuku 以 Root 启动时同样是 uid 0）。约束：只能从 guest shell 调用；受 `root_cli` 权限开关控制，默认关闭；su 缺失或被拒时直接返回 `SERVICE_NOT_RUNNING` / `PERMISSION_DENIED`，不运行任何命令。设备完整性保护（拦截写分区、系统分区、`/data` 系统数据、卸载核心系统包等）尚未实现，见 Issue #182；远程 MCP 调用方经 `linux.shell` 也能到达它：这是维护者接受的设计——每次调用都要用户在手机上单独批准（一次性 confirm，与「全自动」模式无关，不会被它跳过），且 `root_cli` 开关默认关闭。未经批准的远程请求拿不到 root shell。
- 设备完整性保护（Issue #182，默认开启，只有用户能在设置 → 权限里关闭）：来自 Agent 或 guest CLI 的特权命令（`root.shell`、`android-root-cli` 全部子命令含 `exec`、`android.app` 等 Android 工具，即所有经 `PrivilegedCommandRunner` / `RootProcess.run` 的调用）统一经 `ProtectedRoot`。只拦"最坏结果不是重启能解决"的操作：写 `/dev/block`、写系统分区（`/system` `/system_ext` `/vendor` `/product` `/odm` `/apex`）、写 `/data/system*` `/data/misc*` `/data/vendor*` `/data/property` `/data/apex` `/data/unencrypted` `/metadata` `/data/adb`（`/data/adb/minis` 除外）、删除或改名 `/data` 等顶层节点、卸载/停用/清除/挂起/隐藏核心系统包、恢复出厂、删除用户 0、把 `device_provisioned` / `user_setup_complete` 置 0。读取、其他 app 数据、`/sdcard`、非核心预装应用、kill 进程、网络、sysctl、重启一律放开。被拦时不弹窗、不走一次性审批，固定返回 `DEVICE_PROTECTED: <类别>: <目标>` 与退出码 77，并写审计日志（设置页可见）。两道防线：`DeviceIntegrityPolicy` 在执行前按命令词判定（含 `sh -c` / `su -c` / `env` / `find -exec` 解包、`cd` 后的相对路径、`..`/`//`/大小写/`/proc/*/root` 归一化）；`ProtectedView` 让命令在一次性 mount namespace 里运行（受保护目录只读 bind、`/dev/block` 换成空 `nodev` tmpfs、`su` 路径换成桩、bounding set 去掉 `CAP_SYS_ADMIN`/`CAP_MKNOD`/`CAP_SYS_MODULE`/`CAP_SYS_RAWIO`），变量、`$(…)`、脚本文件里的目标由它兜底。视图建不起来（缺 `unshare` 或 rootfs 里的 `setpriv`）时命令不执行，返回 `DEVICE_PROTECTION_UNAVAILABLE`，失败关闭。分区读取走单独的只读导出命令 `android-root-cli partition read`，由 App 构造 `dd`，不经视图。存储水位（`/data` 剩余低于 `max(3 GB, 5%)`）：冻结 guest shell 进程组、拒绝新的批量写命令（`cp`/`dd`/`tar`/`unzip` 等，不含 `rm`），发通知，空间恢复 25% 余量后自动继续，不删除任何东西。App 自己构造的基础设施（`DirectRootRunner`：rootfs、namespace、bind、网络 helper、无障碍修复）不经此处。不防刻意绕过：原始 `service call` 直接发 binder 事务不受前端检查。
- 虚拟屏的 root 服务（`VirtualScreenRootService`，由 libsu 经 `su` 以 `app_process` 拉起）是专用 binder 接口：只暴露 `IVirtualScreenService` 里固定的虚拟屏操作（建屏、启动应用、点按输入、截图、帧流），不提供命令、文件或通用 RPC，也不监听网络或 socket；binder 只交回本 App。
- Guest shell 进入 chroot 后必须通过 `setpriv` 切到真实 App UID/GID，清空 supplementary groups 与 Linux capabilities。
- Root-provider identity 只是可用性信号；uid=0 不代表 SELinux、mount、namespace 或 capability 一定允许操作。

## 网络兼容 helper

`minis-root-network-proxy` 是单用途 loopback HTTP/CONNECT helper。名称中的 `root` 反映当前 Android 部署可让它以特权身份建立出站 socket，**不是协议要求，也不是 Direct Ubuntu chroot 的安全边界本身**。

- 监听固定为 `127.0.0.1:18787`；
- 仅接受 HTTP absolute-form 和 CONNECT；
- 不提供 shell、文件系统、插件、动态配置 RPC 或命令执行接口；
- 普通 loopback/private/link-local/broadcast 目标拒绝；`198.18.0.0/15` 仅作为明确 VPN Fake-IP 兼容例外；
- 请求头、并发、错误处理必须有界；
- 使用特权出站身份只能解决网络兼容，不能扩大成通用 Root 能力。

若目标设备上的 App-UID guest 能直接稳定联网，Direct Ubuntu 本身并不要求通过特权代理才能成立。

## MCP

- 默认绑定 loopback；
- Bearer 认证；
- 工具可见性按 token/调用方过滤；
- 敏感调用可要求用户确认；
- 禁止向远程调用方提供任意 Root shell 或不受限 host 文件系统。

## 网络与凭证

- 密钥不得进仓库、不得经诊断 API 返回、不得写入未脱敏日志；
- 明文 HTTP 只允许应用层显式策略允许的本地/可信源；
- DebugServer 仅 loopback 且仅 debug 构建；
- 本地网络兼容 helper 不得暴露为远程代理服务。

## Ubuntu 边界

chroot 不是 VM。guest 与 Android 共享内核。

- Root 建立必要 namespace/binds/chroot；任意 Agent/guest 代码以 App UID/GID 且无 Linux capabilities 运行；
- host/guest mounts 必须显式构造；session 数据只绑定对应 session backing；
- SAF external mounts 按 grant 权限决定 read-only/writable；
- rootfs 输入必须经过 pin/checksum/manifest 校验；rootfs repair 不得覆盖 App-owned 用户数据。

## 构建边界

runtime payload 是 rootfs-only；网络 helper 单独构建和验证。构建与发布不得重新引入旧 broker 二进制、socket、manifest 字段或 Android client/protocol 类型。负向 guard 与历史归档中允许保留旧字符串用于阻止回归和解释历史。
