# 小米真机实测报告

> 报告类型：2026-09-12 审计工作树的阶段性真机回归记录，不是“全部设备验收完成”声明。
>
> 记录日期：2026-09-12

## 1. 测试对象

| 项目 | 实际值 |
|---|---|
| 分支 | `refactor/direct-ubuntu-runtime` |
| 代码基线 | `b89f117989e77188194c332da4c5a539b1a1f519` |
| 真机序列号 | `f0e2ff6f` |
| 设备 | Xiaomi `24129PN74C` |
| 系统 | Android 17 / HyperOS 4，SDK 37 |
| 页面大小 | 16 KiB |
| 应用包 | `llc.slacker.minis` |
| APK | Debug，`versionCode=39`，`versionName=1.01-beta.2` |
| 设备 App UID/GID | `10418:10418`（动态值，不写死） |
| Provider | 通过软件界面添加的 MiMo，模型 `mimo-v2.5` |

MiMo 凭据只在应用界面配置，未写入源码、测试夹具、日志或本报告。

测试期间包含基线后的工作树改动，不能把上表基线 SHA 当成每个测试 APK 的精确源码标识。审计结果随后提交为 `53dada42`，并通过 `422cc29f` 合入 `main`；合并后宿主测试与 Debug 构建通过，但未重新执行全套真机矩阵。

## 2. 宿主与 APK 检查

| 检查 | 结果 |
|---|---|
| `./gradlew test --no-daemon` | 通过 |
| `./gradlew assembleDebug --no-daemon` | 通过 |
| `./gradlew :app:assembleDebugAndroidTest --no-daemon` | APK 构建通过 |
| `./gradlew :app:minifyReleaseWithR8 -x requireReleaseSigning --no-daemon` | 通过 |
| `python3 scripts/check_docs_provenance.py` | 通过 |
| `python3 scripts/test_docs_provenance.py` | 18 tests 通过 |
| `bash scripts/check-runtime-package-boundary.sh` | 通过 |
| `python3 scripts/check_build_cleanup.py` | 通过 |
| `git diff --check` | 通过 |
| 当前 Debug APK `adb install -r -d` | 安装成功，保留应用数据 |

完整生产 `assembleRelease` 尚未通过签名门禁：当前环境没有 release keystore；没有用 Debug 签名冒充 Release 验收。

以上是分阶段获得的证据，不表示每项都针对最后一次 CLI 改动重新运行；尤其较早的 R8 通过不能替代最终代码的完整签名 Release 验证。

## 3. 已在小米机执行并通过

### App 启动与数据保留

- 当前 Debug APK 已通过 `adb install -r -d` 推送并启动；
- 强停 `llc.slacker.minis` 后重新启动，冷启动成功；
- 覆盖安装后既有会话和界面中的 `mimo-v2.5` 配置仍在，证明本次安装没有清除 App 私有数据；
- 最近一次启动和 Terminal 回归的 Logcat 未发现应用 `FATAL EXCEPTION`、`AndroidRuntime` 或 Minis 异常。

### MiMo 服务商

- 在应用设置界面添加服务商并选择 `mimo-v2.5`；
- 发送文本请求，流式回复成功完成；
- 独立请求 `mimo-v2.5-tts` 成功；
- UI 的“朗读”按钮另外观察到走小米系统 TTS，这是系统朗读链路证据，不冒充 MiMo TTS Provider 证据。

### Direct Ubuntu 终端

- 从应用菜单进入 `Minis Shell`，首次和冷启动后均得到干净提示符：`minis@localhost:/$`；
- `id` 返回动态 App 身份：`uid=10418(minis) gid=10418(minis) groups=10418(minis)`；
- `pwd` 返回 `/`；
- 在 Guest 内完成临时文件创建、`/etc/hostname` 复制、读取和删除；
- 正常关闭 Terminal 后，对应的 `bash -l` 进程消失；
- 连续关闭/重开 Terminal 后仍得到干净提示符；
- Guest shell 执行受控自杀场景 `kill -9 0` 后，Terminal 返回主界面，未留下 `bash -l`；随后再次进入 Terminal 成功。

### 网络命令边界

- 在旧 APK 的 Guest 内执行 `ping doubao.com` 得到 `bash: ping: command not found`；
- 当前分支已将 `iputils-ping` 加入基础 provision 包，并把 `/usr/bin/ping` 加入 readiness probe；已有 rootfs 若缺少该命令，会重新执行一次受控 provision；
- 最新 Debug APK 已重新安装到这台小米机；Guest 内 `command -v ping` 返回 `/usr/bin/ping`；
- 真机执行 `ping -c 1 -W 5 doubao.com` 成功：解析到 `223.109.53.89`，`1 packets transmitted, 1 received, 0% packet loss`，RTT 约 `92.135 ms`；
- `curl -I --max-time 10 https://doubao.com` 或 `curl -v --max-time 10 https://doubao.com` 仍可先分别检查 HTTP 连通性和 DNS/连接过程；这项改动没有扩大 Root 权限范围。

上述结果证明当前 APK 的 Guest shell 基本启动、UID/GID、workspace 文件 I/O、关闭回收和单 shell 异常退出路径在这台小米机上可用；不等于 Root 授权、VPN/BPF 或全部 OEM 生命周期矩阵已经完成。

### 本报告后的源码回归（待重连小米机）

DNS 失败时现在会用同一个服务器再试 TCP；图片导出现在直接写入 Terminal 能看到的 `/var/minis/offloads/...`。代码和单测已通过，但这两项还没在小米机上重测。HTTPS 测 IP 时保留域名，不关闭证书检查。

### Guest CLI / Android 工具命令面

旧 APK 的问题不是 Android handler 不存在，而是 Direct Ubuntu 的 `/usr/local/bin` 没有对应入口；因此终端里只能看到 `minis-config` 和 `minis-model-use`，文档中声明的其它 CLI 无法被 shell 找到。当前 APK 已通过 `GuestCommandBridge` 在启动/恢复 Ubuntu 时安装动态 PATH wrapper，并修复了 wrapper 与 App bridge 之间换行协议被写成字面量 `\\n` 的问题。

在本机当前 APK 的 Terminal 中，以下 `command -v` 均返回 `/usr/local/bin/<命令>`：

```text
android-alarm          android-calendar       android-clipboard
android-contacts       android-device         android-location
android-notification   android-open           android-photos
android-player         android-speak          android-speech
android-weather        android-a11y-cli       android-shizuku-cli
minis-browser-use      minis-scheduled        minis-sessions-cli
minis-config           minis-model-use        minis-open
xdg-open               sensible-browser       www-browser
x-www-browser          gnome-open             kde-open
minis-debug            # Debug APK only
```

无副作用的实际调用结果：

- `android-device --help` 返回完整帮助文本；
- `android-device info` 返回小米设备的 JSON（厂商、型号、Android 版本、SDK、ABI、处理器数量等）；
- `minis-config --help` 返回配置 CLI 帮助和确认/退出码说明；
- `android-a11y-cli --help`、`android-shizuku-cli --version`、`minis-sessions-cli --help`、`minis-scheduled --help` 已执行，未出现 bridge 无输出、shell 崩溃或权限越界；
- 本轮没有执行闹钟/日历写入、通知发送、照片删除、Shizuku `exec`、无障碍点击或定时任务增删等有副作用操作。

这证明“命令入口存在 → loopback bridge 能收到请求 → 对应 Android handler 能返回结果”这条链在真实小米机上已经打通；各工具依赖的系统授权仍按各自 handler 返回真实状态，不因为命令已安装就自动授予权限。

## 4. 旧包与旧进程污染说明

这台手机上仍并存旧包 `com.openminis.app` 和当前包 `llc.slacker.minis`。设备进程中观察到的：

```text
u0_a425 ... libproot.so ... /data/user/0/com.openminis.app/files/alpine-rootfs
```

属于旧包的旧 PRoot 会话，不是当前 `llc.slacker.minis` Debug APK 的生产输入。当前源码和 APK 的生产路径没有 PRoot、`minisd`、`default_mount` 或旧 broker；旧包没有在本轮擅自卸载、清数据或强停，以避免误伤用户数据和 Root 常驻状态。

因此，设备进程列表不能直接作为当前源码架构判断依据。若要清理这部分设备污染，必须另行明确选择“仅停止旧包”还是“卸载旧包并删除其数据”。

## 5. 尚未完成、不能写成通过

| 项目 | 当前状态 |
|---|---|
| KernelSU Root 授权拒绝分支 | 未完成 |
| KernelSU Root 授权允许分支的独立留证 | 未完成；App 内 Direct Root 结果不能用 `adb shell su` 代替证明 |
| 手机重启后恢复 | 未完成 |
| 真正的版本升级并保留数据 | 未完成；本轮 `-r` 是同版本覆盖安装，不是版本升级矩阵 |
| 多个并发 Terminal session | 未完成；已测连续重开，不等于并发 |
| Root shell crash / proxy crash | 未完成；已测 Guest shell 自杀，未主动杀 Root helper |
| VPN、DNS、TUN、BPF、Fake-IP、guest `curl`/`apt` | 未完成 |
| HyperOS instrumentation 真机执行 | 未完成；系统拒绝安装测试 APK |
| Bot、Pi、Remote、Android tools、文件上传、语音、Web/App 同步的完整矩阵 | 未完成或只有局部证据，不能统一宣称通过 |

`adb shell su` 返回 `su: inaccessible or not found` 只说明 ADB shell 入口不可用，不能反推应用内部 KernelSU-mediated Direct Root 路径失败。

## 6. 可复核命令

```bash
adb -s f0e2ff6f devices -l
adb -s f0e2ff6f install -r -d src/android/app/build/outputs/apk/debug/app-debug.apk
adb -s f0e2ff6f shell am force-stop llc.slacker.minis
adb -s f0e2ff6f shell am start -W -n llc.slacker.minis/com.openminis.app.MainActivity
adb -s f0e2ff6f logcat -d -t 1000
adb -s f0e2ff6f shell ps -A -o USER,PID,PPID,NAME,ARGS
```

源码/构建对账见 [`UPSTREAM-COMPARISON.md`](UPSTREAM-COMPARISON.md)，当前未完成项以 [`contracts/06-CURRENT-GAPS.md`](contracts/06-CURRENT-GAPS.md) 为准。

## 7. 结论

当前结论是：**宿主构建通过；小米机上的当前包已安装并能启动；MiMo 文本/TTS、Direct Ubuntu Terminal、动态 Guest 身份、文件 I/O、连续重开和单 shell 异常回收通过。**

不能写成“Root、网络、重启、升级、多 session 和全部自定义功能已完成真机验收”；这些仍是明确的下一批设备测试项。
