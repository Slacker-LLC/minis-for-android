# rclone 配置、TLS 与凭据生命周期静态审查

日期：2026-10-06。新增 2 项 P2。只新建报告和更新台账，不修改产品代码、旧报告或真实配置。

本地 HEAD `d64e7c06d8159be90737dd9ba03dd9bd5855f339`；OpenMinis 固定对照 `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`。只读已有上游对象与本机缓存的 rclone v1.75.0 源码；没有安装、下载、构建、运行测试、模拟器、adb、Root、设备、云任务、凭据或子 Agent。没有连接服务器、读取实际 rclone 配置、调用 RPC、改 TLS 或删除目的地。

## RS01 · P2 · 一个目的地允许不校验证书，会放宽其它目的地

[RcloneRemoteStore.kt:71](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/remote/RcloneRemoteStore.kt:71)将 allowInsecureTLS 作为每个服务器的选项，但 [RcloneRemoteStore.kt:204](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/remote/RcloneRemoteStore.kt:204)使用 remotes.any 将整个 rclone 全局选项置为 true，包括 enabled=false 的目的地。[RcloneBridge.kt:139](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/remote/RcloneBridge.kt:139)写入 options/set 的 main.InsecureSkipVerify，未给具体 fs 或调用设置作用域。

不是仅凭命名推测：固定依赖的 [fs/rc/config.go:188](/home/jiale/go/pkg/mod/github.com/rclone/rclone@v1.75.0/fs/rc/config.go:188)把 options 写入注册对象；fs/config.go:690 将 main 注册为 globalConfig。[backend/webdav/webdav.go:499](/home/jiale/go/pkg/mod/github.com/rclone/rclone@v1.75.0/backend/webdav/webdav.go:499)创建 fshttp.NewClient；[fs/fshttp/http.go:320](/home/jiale/go/pkg/mod/github.com/rclone/rclone@v1.75.0/fs/fshttp/http.go:320)用当前 config 构造 transport，[fs/fshttp/http.go:242](/home/jiale/go/pkg/mod/github.com/rclone/rclone@v1.75.0/fs/fshttp/http.go:242)直接把 ci.InsecureSkipVerify 放进 tls.Config。该构造没有再查看本 App Remote.allowInsecureTLS=false。

具体条件：已有 WebDAV A（允许不校验证书，可处于 disabled）和 WebDAV B（默认要求校验证书）。[BackupViewModel.kt:553](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/backup/BackupViewModel.kt:553)选出实际启用目的地，但555行 syncToRclone 仍检查全部 remotes，设置全局 true。B 在此状态下创建 HTTP client，也跳过证书验证。即使 B 的开关保持 false，仍不能保护 B 的新 transport。这项不依赖两个上传并发。

[RcloneDestinationsViewModel.kt:103](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/backup/RcloneDestinationsViewModel.kt:103)连接候选服务器时也写全局选项，允许候选或任何已有目的地决定当前状态。取消候选只清 browse/pendingSecret，没有自动回滚该全局开关；后续 sync 能再设置，但不能将它解释为每个请求隔离。

影响限定为采用这个 HTTP/TLS transport 的连接，不扩大到 SMB/SFTP 或所有协议。用户只同意某个服务器允许自签名，却可能让其它 HTTPS 目的地接受无法验证身份的证书；需存在对应网络拦截条件才产生实际冒充风险。本轮未搭建网络或证书实验，也不声称修改全局选项会立即重写所有已存在 transport。

固定上游 [RcloneRemoteStore.kt:246](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/backup/remote/RcloneRemoteStore.kt#L246)相同 any+全局设置。上轮只列待核，本轮由实际依赖实现确认，共享缺口。

修复方向记录：使信任选择与目标连接一致，优先可信 CA/证书身份校验；如只能使用全局选项，需要在真实 transport/调用边界实现有效隔离与恢复，不能只在 UI 保留 per-remote Boolean。要核验拒绝不可信证书及允许目标例外两条路径，本轮不修改或执行。

## RS02 · P2 · 临时配置会落盘，删除目的地没有清除 rclone 凭据副本

[RcloneRemoteStore.kt:197](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/remote/RcloneRemoteStore.kt:197)设置 cacheDir/rclone-ephemeral.conf；随后215行 config/create 提交包含密码或 S3 key 的参数，opt 只声明 nonInteractive。注释称这是 in-memory 配置、不会产生另一份秘密，但这个 RPC 的实际行为不是仅内存。

[go.mod:5](/home/jiale/projects/minis-for-android/deps/rclone-mobile/go.mod:5)固定 rclone v1.75.0；[gomobile.go:31](/home/jiale/projects/minis-for-android/deps/rclone-mobile/gomobile/gomobile.go:31)直接调用它的 librclone.RPC。缓存源码 [librclone/librclone/librclone.go:39](/home/jiale/go/pkg/mod/github.com/rclone/rclone@v1.75.0/librclone/librclone/librclone.go:39)安装 configfile 存储；[fs/config/rc.go:210](/home/jiale/go/pkg/mod/github.com/rclone/rclone@v1.75.0/fs/config/rc.go:210)调用 CreateRemote，fs/config/config.go:652 转 UpdateRemote，[fs/config/config.go:625](/home/jiale/go/pkg/mod/github.com/rclone/rclone@v1.75.0/fs/config/config.go:625)调用 SaveConfig。[fs/config/configfile/configfile.go:105](/home/jiale/go/pkg/mod/github.com/rclone/rclone@v1.75.0/fs/config/configfile/configfile.go:105)以配置路径提交文件；[fs/config/crypt.go:218](/home/jiale/go/pkg/mod/github.com/rclone/rclone@v1.75.0/fs/config/crypt.go:218)在没有 configKey 时直接复制序列化配置，不加密。App 没有设置 rclone 配置加密密钥的现役调用。

密码参数的 obscure 是可逆表示，S3 secret_access_key 本来就按原值传递（[RcloneRemoteStore.kt:340](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/remote/RcloneRemoteStore.kt:340)）。因此正常同步可在缓存文件留下独立于 Keystore-backed secrets 的可恢复凭据副本。缓存目录是 App 私有的，不据此声称普通其它 App 能直接读取；缺口是承诺的秘密存储和删除生命周期不成立。cacheDir 命名也不意味着文件只活在内存。

[RcloneRemoteStore.kt:158](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/remote/RcloneRemoteStore.kt:158)删除目的地仅改 SharedPreferences 列表并删除 secrets key，没有 config/delete、清理临时配置文件或清除 rclone 已加载的该 remote。后续 sync 只创建仍在列表的远程，不删除已移除的 section；文件与进程内 rclone 配置可以继续保留旧凭据，直到额外的清理/缓存回收等事件。本轮不把“不再出现在备份列表”误报为继续自动上传，只记录数据副本仍在。

固定上游 [RcloneRemoteStore.kt:200](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/backup/remote/RcloneRemoteStore.kt#L200)同样只删列表和 secrets；239/261行同样 setpath+config/create。共享缺口，设备文件存在与内容未实测。

修复方向记录：核对 rclone 真正可用的内存配置/不持久化路径；如需要临时配置文件，必须明确其保护、清理和删除同步语义，不能依赖 cacheDir 自动回收。删除 remote 要同步清理 library 配置与相关副本，错误需反馈。不要为了验收读取真实密码，本轮未做清理或验证操作。

## 其他全文阅读与测试边界

RemoteStore 的 join/listRoot 保留 SFTP 绝对根已有实现，S3 明文参数与密码 obscure 区分已实现，不重复历史已修复问题。配置保存的两份 SharedPreferences、异常吞掉与初始化状态仍可进一步沿真实失败路径核验，不因出现 apply 就机械新增问题。

全文读完 BackendCatalog 的五种字段定义、secret 标记与路径规则；两份新增阅读测试覆盖这些规则和构造出的参数，没有覆盖两个真实目的地的 TLS 隔离、config/create 落盘或 remove 后的 rclone section。上轮 TLS 测试也只覆盖开关序列化与单 config 参数。本轮只阅读现有测试，不执行。

## 覆盖与核验

本轮全文 7 个文件、1039 行：

- [deps/rclone-mobile/go.mod](/home/jiale/projects/minis-for-android/deps/rclone-mobile/go.mod)：124 行。
- [deps/rclone-mobile/gomobile/gomobile.go](/home/jiale/projects/minis-for-android/deps/rclone-mobile/gomobile/gomobile.go)：34 行。
- [deps/rclone-mobile/librclone/librclone.go](/home/jiale/projects/minis-for-android/deps/rclone-mobile/librclone/librclone.go)：62 行。
- [src/android/app/src/main/java/com/openminis/app/backup/remote/RcloneBackendCatalog.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/remote/RcloneBackendCatalog.kt)：138 行。
- [src/android/app/src/main/java/com/openminis/app/backup/remote/RcloneRemoteStore.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/remote/RcloneRemoteStore.kt)：370 行。
- [src/android/app/src/test/java/com/openminis/app/backup/RcloneBackendCatalogFieldsTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/backup/RcloneBackendCatalogFieldsTest.kt)：82 行。
- [src/android/app/src/test/java/com/openminis/app/backup/RcloneRemoteConfigTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/backup/RcloneRemoteConfigTest.kt)：229 行。

累计全文 983 / 1546 个文件、199081 行；partial 15，not_registered 548，excluded 124。全仓通读尚未完成。RemoteStore 从 partial 补齐全文；其它调用方片段不计全文。外部 Go 缓存只读关键片段，不纳入仓库全文数量。

外部证据摘要（固定 v1.75.0；用于复核本轮源码，不代表执行过该代码）：

- fs/rc/config.go SHA256 `795c0a213c27e1c6fd8835230387a1fdc5f96ef0ffc25bd0bbea25456ccf5e5b`。
- fs/config/rc.go SHA256 `4517554507b67191237c54deac54432ddcefb022ce535acffd66be1b358d697c`。
- fs/config/config.go SHA256 `d602ea89967ae0ccf50a73a68a9042cf697f8c2f4faae1d9b3cd5bf22bc0d738`。
- fs/config/configfile/configfile.go SHA256 `45d5aa647da982bb7435631926bbba09c1e7021571dc5fb5d5da7c9d9989a66f`。
- fs/config/crypt.go SHA256 `b70e6c49b2b08d9067fecc6665a7272002efbac56db4322da3a9ae9abd681069`。
- fs/fshttp/http.go SHA256 `86d5dada7b280f960c7cfa795678b4bfdf4b5f39ee2e5ffeddd3f6404aedd3dd`。
- backend/webdav/webdav.go SHA256 `4f8b148580db6dfbb34d70000f2fcf046591dd767d9f4e2ee566289845b964f2`。
- librclone/librclone/librclone.go SHA256 `03038c8fd7d632a31aeba961817a9824651363d7958bd54bb6fa74c863f2ad17`。

1670 tracked 路径、SHA256、字节数与正整数文本行数重新核验；69 份旧报告摘要不变，HEAD 固定，tracked/staged diff 为空。新报告本地及缓存引用行号、台账批次总数核验；全文输出均完整，没有截断冒计。没有真实网络、配置文件或删除后状态证据。
