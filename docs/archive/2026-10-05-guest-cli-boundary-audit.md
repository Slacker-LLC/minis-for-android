# Minis for Android — Guest CLI、遗留接收器与原生工具新增排查

日期：2026-10-05。独立报告，未补入或修改此前六份报告。

## 范围与结论

本轮继续核对“通用工具应由沙箱与 Agent 管理、App 保留必要原生接口”的职责边界，全文阅读 guest 命令桥接、旧 offload 接收器、天气、配置、定位、播放、剪贴板、设备信息及相关公共 helper 和既有测试。

确认一个新的职责分层实例与十三项具体缺口。这里的“旧接收器仍启动”不表示 minisd、PRoot 或旧 Root broker 重新进入现役执行链；当前 Direct Ubuntu 仍通过自己的 CLI wrapper 调用 handler。问题是原 PRoot offload 协议的 App 侧 abstract socket 接收与回包路径也继续运行。

- 本地基线：`main` / `d64e7c06d8159be90737dd9ba03dd9bd5855f339`；以当前工作树为准。
- 上游：只读 Git 快照 `/tmp/openminis-review-20261004`，固定提交 `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`。按 Git 树查找对应 `sandbox/offload` 文件，忽略单纯 package 迁移后比较；没有以 sparse checkout 中的文件缺失推断上游不存在。
- 只编辑本报告和审计覆盖台账；未修改产品源码、测试、合同或旧报告。未构建、启动模拟器、运行功能测试、执行设备／Root 命令、发起网络请求或探测接收器。
- 设备权限、SELinux 与真实 Android 回调行为未实测，相关结论明确区分源码缺口与设备可达性。

| 编号 | 优先级／类型 | 新发现 |
|---|---|---|
| GH01 | P2／职责 | 天气 HTTP 查询与格式化仍固化在 App |
| GH02 | P1／入口边界 | 旧 abstract socket 接收器仍启动，缺少调用方认证 |
| GH03 | P2／生命周期 | 旧接收器无读取超时，stop 不关闭已接入 worker |
| GH04 | P2／CLI | 文件载荷允许 2 MiB，改写成参数后又受 64 KiB 限制 |
| GH05 | P2／CLI | 公共解析器将负坐标吞成选项 |
| GH06 | P2／数据 | 英制天气的英寸降水值仍标作 precipitation_mm |
| GH07 | P2／能力 | 不支持的 alerts 被做成网络查询，combined report 丢掉“不支持”说明 |
| GH08 | P2／CLI | set-batch 的 stdin 承诺没有实现 |
| GH09 | P2／资源 | 每次新定位为 provider 创建 executor，成功回调后也不关闭 |
| GH10 | P1／会话 | 播放器名称被当作文件来源聊天会话 |
| GH11 | P2／恢复 | 播放替换失败前已经销毁旧播放器 |
| GH12 | P2／数据 | 默认剪贴板读取静默截断，仍返回成功 |
| GH13 | P2／统计 | 设备存储统计重新跟随符号链接，丢掉上游修复 |
| GH14 | P3／语义 | 设备信息的 memory 字段实际是 App VM heap，未说明口径 |

<a id="gh01"></a>
## GH01 — 天气是通用网络工具，却由 App 实现整条业务链

[MinisApp.kt:876](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/MinisApp.kt:876) 注册 `android-weather`。[WeatherOffloadHandler.kt:70](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/WeatherOffloadHandler.kt:70) 调 App 内 `WeatherManager`，[WeatherManager.kt:47](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/offload/WeatherManager.kt:47) 构造 Open-Meteo HTTP 请求、解析响应；handler 再裁剪小时／每日数据、翻译天气码、组合报告。

这两份 Kotlin 共 **515 行**，主要职责是公共网络查询与业务数据处理。该查询只需要经纬度，不依赖 LocationManager、MediaStore 或 PackageManager。handler 的 Locale／SIM 判断只是给失败文案加提示，不是获取天气必须的原生能力。

**按用户目标的归属：** 查询、裁剪、天气码转换和报告格式适合沙箱脚本／工具／技能，Agent 按需要维护；若用户需要当前位置，App 提供受控定位接口即可。现役 CLI 的名称与 PATH 位置在 guest，不代表策略已在 guest 执行。修改天气后端和格式仍要修改、发布 App。

**上游：** 固定上游已有同样的 App 侧天气实现；本地另加英制参数。因此这是延续了上游职责分配的问题，不能说全部由本地新增。没有把 OkHttpClient 重复创建或 iOS 注释噪音再计为本轮发现。

<a id="gh02"></a>
## GH02 — 旧协议接收器仍在生产启动，且没有认证调用方

**当前两条路径：**

```text
现役 Direct Ubuntu CLI
  → GuestCommandBridge：loopback + 随机 token
  → NativeOffloadServer.getHandler：仅复用 handler 注册表
  → 各 Android handler

同时在 App 启动时：
  → NativeOffloadServer.start
  → abstract unix socket + 旧 little-endian 请求／tmp-file 回包
  → 同一组 Android handler
```

[MinisApp.kt:921](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/MinisApp.kt:921) 无条件启动旧接收器；[NativeOffload.kt:128](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/NativeOffload.kt:128) 绑定 `native-offload-${applicationId}`。[GuestCommandBridge.kt:213](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/GuestCommandBridge.kt:213) 只需要它的 handler 注册表，并不经过这只 socket。

[NativeOffload.kt:269](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/NativeOffload.kt:269) 直接读取 magic、version、客户端提供的 pid、argv、env、cwd；没有 token 验证，也未读取／校验 LocalSocket 的 peer credentials。[NativeOffload.kt:302](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/NativeOffload.kt:302) 的 sessionId 同样来自客户端 env，只有语法检查。magic 和版本只是协议识别，不能证明调用者身份。

作为对照，[GuestCommandBridge.kt:301](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/GuestCommandBridge.kt:301) 对现役 TCP 路径校验随机 token。旧端点可直接进入同一 handler，并不经过该 token 检查；handler 自己已有的 OffloadGate 或系统权限检查仍可能拒绝动作，不能声称所有调用都因此获准。

**现役消费者核对：** 本轮读取当前 wrapper 全文，并检索当前 Android/runtime/native 构建路径，没有找到向这只 abstract socket 发送旧协议的生产 guest shim；找到的 crash handler 字符串只是线程名注释。固定上游的同名文件开头明确描述 PRoot `native_offload` extension 协议，本地改了目录、描述、数据 backing 和边界预算，但仍保留 listener。

**影响与证据边界：** App 侧身份认证缺口成立。能否由另一个 Android UID 实际连接、能调用到哪种设备能力，依赖目标 SELinux／授权配置，未测试。不能写成“所有 App 已能控制手机”。同 UID 的 guest 本来也不具备强隔离；这里不把 chroot 宣称成安全容器。

**处理方向：** 先确认是否存在明确兼容消费者；没有时应停用并移除旧接收／回包路径，保留现役注册表和必要原生接口。有消费者则必须独立证明调用方认证与身份绑定。仅建议，未实施。

<a id="gh03"></a>
## GH03 — 旧接收器的容量上限没有配套超时和 shutdown

[NativeOffload.kt:240](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/NativeOffload.kt:240) 把并发限制为 16，但 [NativeOffload.kt:274](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/NativeOffload.kt:274) 的 readFully 读取没有 socket timeout／请求总 deadline。客户端保持连接却不发送完整请求，worker 会一直等，持有容量槽位。

[NativeOffload.kt:226](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/NativeOffload.kt:226) 的 stop 只关闭监听 socket，未保存或关闭已接入客户端，未中断或等待 worker。因此未完成请求可以在 stop 后继续等待；同一进程再次 start 仍共用未释放的 limiter。停止监听不等于停止已接入处理。

[GuestCommandBridge.kt:77](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/GuestCommandBridge.kt:77) 的现役桥接已有 worker socket 跟踪与关闭，且请求头阶段设有 [GuestCommandBridge.kt:295](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/GuestCommandBridge.kt:295) 的读取 timeout；两条传输的生命周期要求不一致。

**上游：** 原上游也缺读取 timeout 与已接入连接的 stop 管理，本地增加 16 槽位后仍没有补齐回收。已读 NativeOffloadResourceTest 只验证计数、字节预算和输出截断，不覆盖真实阻塞读取或 shutdown。

**后续验收建议：** 在明确授权的设备环境中验证半包、保持连接、stop／start、请求中途断开等否定路径；检查 socket、worker 与 limiter 都被回收。若 GH02 选择删除旧路径，无需再维护它的第二套生命周期。未执行。

<a id="gh04"></a>
## GH04 — --file 的 2 MiB 传输承诺被 64 KiB 参数预算覆盖

[GuestCommandBridge.kt:504](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/GuestCommandBridge.kt:504) 的 wrapper 允许 `minis-config --file` 文件最多 2 MiB；接收端也以 MAX_FILE_BYTES 接收。随后 [GuestCommandBridge.kt:338](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/GuestCommandBridge.kt:338) 调 rewriteFileArgument，将文件文本替换进 argv；[GuestCommandBridge.kt:217](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/GuestCommandBridge.kt:217) 的 dispatch 又要求每个参数不超过 64 KiB。

因此一个超过 65,536 字节、但小于 2 MiB 的合法 JSON 文件，虽通过文件传输检查，仍在 handler 调用前报 `invalid argument`。这不是配置字段自身的验证拒绝。

**既有测试限制：** GuestCommandBridgeTest 分别测试小文件改写与 dispatch，没有覆盖“大文件 → 改写 → dispatch”的实际组合。

**方向与后续验收：** 文件载荷应保持独立语义和一致预算；若只支持 64 KiB，就必须一致地声明、提前拒绝。验证阈值两侧的合法载荷，并核对配置、确认弹层及审计是否按同一限制处理。未执行。

<a id="gh05"></a>
## GH05 — 公共参数解析把有效负坐标当作短选项

[OffloadArgs.kt:47](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/OffloadArgs.kt:47) 看到下一个 token 以 `-` 开头时，不把它视为当前长选项的值；[OffloadArgs.kt:54](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/OffloadArgs.kt:54) 又把负数本身当作短 flag。

例如：

```text
android-weather current --lat -33.86 --lon 151.21
android-location geocode --lat 37.77 --lon -122.42
android-weather -33.86 151.21
```

前两条得不到所需的 lat／lon 值；第三条丢掉位置参数中的负纬度。[WeatherOffloadHandler.kt:56](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/WeatherOffloadHandler.kt:56)、[LocationOffloadHandler.kt:84](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/LocationOffloadHandler.kt:84) 的消费者随后报坐标缺失。这些是合法经纬度，不是边界之外的输入。`--lat=-33.86` 可以绕开该解析问题，但文档展示的常规形式失效，legacy positional 也失效。

**上游：** OffloadArgs 忽略 package 路径后与固定上游逐行一致，问题共有。其它消费同解析器的带负数／以连字符开头文本也应核对，不能只修天气调用点。

**后续验收建议：** 南／北纬、东／西经、长选项空格值、等号值与位置参数都应得到相同坐标。仅静态推导，未运行 CLI。

<a id="gh06"></a>
## GH06 — 英制降水仍标成毫米，部分切片也丢掉单位

[WeatherManager.kt:50](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/offload/WeatherManager.kt:50) 的 imperial 请求明确传 `precipitation_unit=inch`。[WeatherOffloadHandler.kt:103](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/WeatherOffloadHandler.kt:103) 将响应原数值直接放到 `precipitation_mm`，没有单位转换。

因此返回 1 英寸时，JSON 的字段含义却是 1 毫米。current 的温度与风速保留了单位，但小时／每日切片没有保留相应 units 对象，单独使用这些结果也缺少完整的单位说明。

**上游：** 固定上游只请求 metric；英制参数是本地新增，切片字段没有一起调整。这里根据本地请求参数与未经转换的字段映射判断，不声称已经实际调用 API 测量。

**后续验收建议：** metric／imperial 的所有 current、hourly、daily、report 数值与单位必须相符，优先保留来源单位或做显式转换。未执行。

<a id="gh07"></a>
## GH07 — unsupported alerts 被误当成普通网络结果

[WeatherOffloadHandler.kt:70](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/WeatherOffloadHandler.kt:70) 在所有 subcommand 分支之前联网；`alerts` 最终却只返回 [WeatherOffloadHandler.kt:170](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/WeatherOffloadHandler.kt:170) 的固定“不支持”说明，不使用下载的数据。

两个后果：

1. 查询一个始终不支持的能力，仍要求 forecast 网络成功；网络失败时只收到 weather_fetch_failed，未得到实际能力状态。
2. [WeatherOffloadHandler.kt:185](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/WeatherOffloadHandler.kt:185) 的 combined report 仅保留 alerts 空数组，丢掉 alertsBlock 的 note。输出无法区分“没有告警”与“当前后端不提供告警”；单独 alerts 的说明虽然还在，并未进入 combined report。

**上游：** 固定上游相同分支也先联网并在 report 丢掉说明，属于共有缺陷。

**方向与后续验收：** 不支持的能力应稳定返回 unsupported 状态，combined report 保留该来源限制；断网时也不应把能力查询变成无关的 forecast 错误。未执行。

<a id="gh08"></a>
## GH08 — set-batch 帮助说读 stdin，实际上只接受参数

[ConfigOffloadHandler.kt:94](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/ConfigOffloadHandler.kt:94) 的 help 宣称 set-batch 从 stdin 读取 JSON array；[ConfigOffloadHandler.kt:286](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/ConfigOffloadHandler.kt:286) 实际只读位置参数或 `--batch`，完全不读 request.stdin。

现役 [GuestCommandBridge.kt:470](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/GuestCommandBridge.kt:470) 的 wrapper 也只为 `minis-model-use run` 收集 stdin。因此：

```text
cat batch.json | minis-config set-batch
```

会报“expects a JSON array argument”，不是按帮助完成批量配置。位置参数及 `--batch` 仍有实现，本项不声称整个批量配置不可用。

**上游：** 上游 help 与 handler 也不一致；本地协议已有 stdin 字段，却未把此功能接入。

**方向与后续验收：** 统一 help、wrapper 和 handler 输入契约；检查管道、空输入、非法 JSON、超限 batch，以及拒绝确认的路径。未执行。

<a id="gh09"></a>
## GH09 — 新定位的 executor 创建后从不关闭

[LocationOffloadHandler.kt:230](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/LocationOffloadHandler.kt:230) 每个 provider 的 getCurrentLocation 调用都传一个新建的 `Executors.newSingleThreadExecutor()`。函数未保存 executor，也没有 shutdown／shutdownNow。成功回调后，这个 executor 可以留下等待工作的线程；重复进入 fresh-fix 路径会重复产生。

超时分支取消 CancellationSignal 不能替代关闭已经启动的 executor。异常／中断分支也没有统一 finally 清理 signal 与 executor。缓存位置命中时不进入该分支，本项不声称每次 current 都新增线程。

**上游：** 固定上游已有相同创建语句，无相应关闭，问题共有。

**方向与后续验收：** 使用已有可管理的执行线程或确保每次请求完整收尾；覆盖 fresh 成功、provider 返回 null、超时、调用方取消和多 provider 回退，并记录线程数及迟到回调。未执行。

<a id="gh10"></a>
## GH10 — 媒体播放 ID 被用来解析聊天 workspace

[PlayerOffloadHandler.kt:31](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/PlayerOffloadHandler.kt:31) 从位置参数取播放 id，[PlayerOffloadHandler.kt:39](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/PlayerOffloadHandler.kt:39) 调 `MediaPlayerManager.play(id, path)`，没有传 NativeOffloadRequest.sessionId。

[MediaPlayerManager.kt:85](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/offload/MediaPlayerManager.kt:85) 却把这个播放 id 用作 `WorkspaceFileClient.readToFileBlocking(sessionId, ...)` 的文件来源会话。原先自由命名的播放器 session 与聊天数据 session 成了同一个参数。

**具体序列：** 在聊天会话 A 中产生 `/workspace/song.mp3`，运行 `android-player play music /workspace/song.mp3`。工具使用 music 的 workspace，而不是 A 的 workspace：可能找不到文件；如果对应来源会话存在同路径文件，则读错音频。只有把播放 id 恰好写成 A 的 sessionId，才避开这一问题，同时限制了一个聊天下独立播放器的命名。

**上游：** PlayerOffloadHandler 忽略 package 迁移后与固定上游相同。上游 manager 使用旧全局路径解析；本地接入 per-session 文件 API 时，把旧播放器 id 直接传作数据来源，新增了该语义错配。不能通过恢复旧全局 workspace 来修复。

**方向与后续验收：** 分开播放器资源 id 与当前执行上下文的文件来源 session，来源必须保持当前聊天的 workspace 语义。两个聊天放同名不同音频，各使用自定义播放 id，核对实际播放来源及控制操作。未执行。

<a id="gh11"></a>
## GH11 — 替换播放失败，旧播放已经被销毁

[MediaPlayerManager.kt:52](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/offload/MediaPlayerManager.kt:52) 在检查新路径、读取文件、判断媒体类型及 prepare 之前，先 release 同 id 的旧播放器。任何后续路径错误、staging 失败、视频不支持或解码失败都返回 Error；原 session 还留在 map 里，但旧 player 已释放，state 已变成 STOPPED。

**后果：** 正在播放有效音频，尝试换成不存在的文件。新请求失败，却同时停止原播放，不能继续 resume 原状态。部分新播放器初始化异常也缺少显式 release，不能只关注成功替换。

**上游：** 固定上游同样先释放旧 player，因此前半项为共有缺陷；本地增加 staging 后，提前销毁之后的失败点更多。

**方向与后续验收：** 新资源准备成功后再替换；失败状态明确保持旧播放或清除失效 session，不能留下貌似仍存在却无法继续的资源。保存有效音频 → 无效替换前后的状态与播放证据。未执行。

<a id="gh12"></a>
## GH12 — 默认剪贴板文本被截断，却没有完整性提示

[ClipboardBoundsPolicy.kt:17](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/ClipboardBoundsPolicy.kt:17) 把读取上限设为 8000 字符，返回 truncated 标记。[ClipboardOffloadHandler.kt:100](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/ClipboardOffloadHandler.kt:100) 仅在显式 `--json` 时把标记写入输出；默认纯文本分支只返回前 8000 字符，exitCode 仍为 0。

因此超过上限的正文经 `android-clipboard get` 复制／存文件时，调用者得到不完整内容却没有任何截断信号。设置合理上限不是缺陷；默认输出丢掉完整性状态才是问题。也不应把警告混入正文后仍当作原文本。

**上游：** 本地新增 ClipboardBoundsPolicy 及调用，固定上游默认读取没有这层 8000 字截断。JSON 模式已有可用标记，但现有 help 还没有列出这个完整性区别。

**方向与后续验收：** 默认模式应明确拒绝、以独立状态报告或提供完整内容的受控出口；核对 8000 边界两侧、原始换行和管道写入后的长度。未执行。本项与旧报告 F39 的聊天导出工具结果 200 字截断不同。

<a id="gh13"></a>
## GH13 — 设备存储统计丢失上游的 symlink 修复

[DeviceOffloadHandler.kt:134](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/DeviceOffloadHandler.kt:134) 对 filesDir／cacheDir 求大小，[DeviceOffloadHandler.kt:139](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/DeviceOffloadHandler.kt:139) 使用 `File.walkTopDown + isFile + length`，会解析文件链接，也会沿目录链接统计。

例如同一 App 数据目录里已有目录 a，又有 b/link 指向 ../a，a 的内容会再次计入；指向目录树外的可读链接还会把其它来源算作本目录使用量。大目录／循环链接也会增加这次原生工具调用的遍历成本。没有证明越权读取或文件被外发，本项是统计来源与遍历边界错误。

**上游：** 固定上游 `sandbox/offload/DeviceOffloadHandler.kt:150` 已改为调用 `SessionStorage.directorySize`，并明确说明不应跟随 symlink；本地却重新使用原来的 walker。当前本地没有该旧 SessionStorage 文件，不能直接照搬旧 helper 的依赖，应核对现有安全文件遍历能力。

**方向与后续验收：** 使用现有路径／文件边界统计并明确不跟随链接；同一数据树加一个目录 alias 前后，真实占用统计不应翻倍。此项不同于此前 BF 报告的备份导出链接越界。未执行。

<a id="gh14"></a>
## GH14 — device info 的 memory 数值是 App 堆，而非设备 RAM

[DeviceOffloadHandler.kt:56](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/DeviceOffloadHandler.kt:56) 使用 `Runtime.getRuntime()`，[DeviceOffloadHandler.kt:72](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/DeviceOffloadHandler.kt:72) 将 totalMemory、freeMemory、maxMemory 输出为 total_memory_mb、free_memory_mb、max_memory_mb。来源是当前 App VM 的 heap 统计，未查询设备物理 RAM。

作为名为 device info 的输出及帮助里的“Model, OS, memory”，字段缺少“App heap”口径说明。Agent 若把它当作设备总内存，会得出错误的容量判断。没有证明产品界面也把这些数字显示为设备 RAM，本项限于 CLI 输出语义。

**上游：** 固定上游保留同样字段与来源，问题共有。可明确标注 heap 口径；是否增加真实设备 RAM，应由实际产品需求决定，不能以同一个字段静默换统计意义。未执行设备数值对照。

## 本轮完整阅读与全库进度

本轮新增 **20 个 Kotlin 文件全文记录，共 4032 行**。其中 18 个为上述生产 handler／manager／公共 helper，2 个为 GuestCommandBridgeTest 和 NativeOffloadResourceTest。两个测试仅阅读，没有执行，也没有新建任何测试。

另核对了 MinisApp 的注册／启动、UbuntuKernel 的 CLI 安装引用和固定上游对应片段；这些片段核对不算全文。台账见 [全库代码覆盖记录](/home/jiale/projects/minis-for-android/docs/archive/2026-10-05-code-review-coverage.json)。

累计可核验全文记录 **40 个文件、7338 行**（38 个 Kotlin、2 个默认提示词）。全库代码／测试／配置／源资源／fixture 分母仍为 1546 个，尚未完成全库阅读。台账按内容摘要固定阅读版本；下一轮优先沿剩余 guest handler 的执行与权限链继续读，而非重复扫描这些文件。

## 验证与限制

本轮复核了报告文件引用、当前源码摘要、前轮阅读版本和 tracked diff。源码没有更改。所有复现序列与设备验收建议都尚未执行，不代表修复或真机行为已通过。

上游对照来自固定 Git 对象，不来自历史 PR 的描述。未确认的跨 UID 可达性、Android/OEM 实际回调与线程数、播放器音频行为都保持未验证；没有以纯函数或宿主结果代替真机证据。

