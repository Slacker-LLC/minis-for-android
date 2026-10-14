# Minis for Android — 网络代理、runtime 载荷与部署检查排查

日期：2026-10-05。本轮独立报告，旧报告保持原样。

## 范围与结论

完整阅读新增 19 个文件、3399 行：Rust 网络代理及 Cargo 配置、rootfs／代理构建和验证脚本、清理 guard 与既有测试、两个 Gradle 构建文件、ProGuard 配置和 CI workflow。另重新完整核对已登记的 RootNetworkProxy.kt，阅读相关合同及旧报告，避免重复统计。

基线仍为 `d64e7c06d8159be90737dd9ba03dd9bd5855f339`；上游固定提交为本地只读快照 `/tmp/openminis-review-20261004` 的 `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`。上游提交树没有本轮同名 RootNetworkProxy／root-network-proxy／Ubuntu payload 文件；这仅限定这些实现的对照范围，不代表上游没有网络或部署功能，也不建议恢复其 PRoot 架构。

现役 helper 固定监听 `127.0.0.1:18787`，有随机 token 认证、首请求重复认证头拒绝、普通私网目标拒绝和明确的 Fake-IP 例外。启动端要求自己持有的子进程发出 READY，不把任意占端口进程当成受信代理。没有发现 minisd 通用 Root broker、PRoot/Alpine 双栈恢复。构建 guard 中的旧字符串主要用于负向检查，不算生产恢复证据。

新确认的问题集中在代理对首请求之后的处理、连接预算、HTTP 字节与目标一致性、DNS 关联验证，以及手工部署和发布检查。网络 helper 负责 Android 出站兼容与凭证边界，其生命周期由 App 管理属于现役职责；此前记录的开发工具策略下沉仍见 AD01，不在这里重复计数。

| 编号 | 优先级 | 问题 |
|---|---|---|
| NP01 | P1 | HTTP 复用连接只处理首请求，后续代理认证头原样送到 origin |
| NP02 | P2 | 单次读超时没有覆盖请求总时间，连接建立／转发阶段缺少应用预算 |
| NP03 | P2 | 逐字节转 char 再编码，改写非 ASCII HTTP 头字节 |
| NP04 | P2 | absolute-form 改成 origin-form 后，仍转发旧 Host |
| NP05 | P2 | DNS 响应匹配 transaction ID，却不匹配查询名称 |
| NP06 | P1 | 手工安装脚本在载荷验证前清空旧 rootfs，并原地解压 |
| NP07 | P2 | Android CI 汇总不包含网络代理质量和 rootfs 验证结果 |
| NP08 | P2 | Release 检查漏认项目自己的 debug 证书 |

以下都是源码证据与静态推导。没有运行构建、测试、网络复现、模拟器、adb／Root／设备命令，没有读取实际凭据或使用云资源。仅新增本报告、更新阅读台账；未修改产品代码、测试、合同和旧报告。建议验收全部未执行。

## NP01 — 首请求后原样转发，认证头过滤只生效一次

[main.rs:319](/home/jiale/projects/minis-for-android/src/native/root-network-proxy/src/main.rs:319) 正确消费并校验首个请求的 Proxy-Authorization，不将该头放入转发 head。但 [main.rs:339](/home/jiale/projects/minis-for-android/src/native/root-network-proxy/src/main.rs:339) 把 BufReader 预读剩余字节直接发送给同一个 upstream，之后调用 [pump:623](/home/jiale/projects/minis-for-android/src/native/root-network-proxy/src/main.rs:623) 原样双向复制，HTTP 分支也如此，没有第二次消息解析。

**触发条件：** 客户端使用 plain HTTP 代理，在同一 TCP 连接发送第二个正常请求，并再次携带代理认证头。第二个请求不再经过校验／头过滤；无论它已被预读还是稍后到达，Proxy-Authorization 都会发给首个请求连接的 origin。该认证值编码的是 App 进程级代理 token，不能作为普通源站请求头外发。若第二个请求是另一 authority，也仍沿用首个 upstream，而非按新目标重新建立连接。

这不是“首请求无需认证”：无 token 或错误 token 的首请求仍被 407 拒绝。CONNECT 成功后转发隧道字节是正确的职责，缺陷针对未建立 CONNECT 的普通 HTTP 分支。本轮没有捕获真实客户端泄漏，也没有证明远程源站能直连手机 loopback；远程持有该值和在设备本地可达代理是不同条件。

RFC 9110 将 Proxy-Authorization 定义为相邻代理消费的认证信息，而非源站认证；这里没有合作代理链配置。[RFC 9110 §11.7.2](https://www.rfc-editor.org/rfc/rfc9110.html#section-11.7.2)

既有 [apt_style 测试:841](/home/jiale/projects/minis-for-android/src/native/root-network-proxy/src/main.rs:841) 仅发一个带 Connection: close 的请求，并主动关闭写端，因此“首请求不含认证头”的断言不能证明连接复用时仍安全。

**方向与后续验收：** 保持单用途代理边界，明确支持的 HTTP 连接策略；即使采用单请求连接，也须识别正文边界并阻止多余请求被当正文转发，单纯加 Connection: close 头不足以处理已经预读的第二条消息。分别核对同源复用、不同 authority、预读第二请求、正常 POST 正文与 CONNECT 隧道，保存源站接收的脱敏头信息。未执行。

## NP02 — 有并发上限，但缺少完整的时间与回收预算

[main.rs:273](/home/jiale/projects/minis-for-android/src/native/root-network-proxy/src/main.rs:273) 设置 30 秒单次 socket read timeout；读头循环没有整体 deadline。只要每次底层读取在超时前取得少量数据，头处理可以远超过 30 秒。认证发生在完整读头之后，等待阶段已经占用 MAX_CONCURRENT=64 的槽位。

之后 [main.rs:260](/home/jiale/projects/minis-for-android/src/native/root-network-proxy/src/main.rs:260) 对出站目标使用普通 TcpStream::connect，没有应用层 connect_timeout。DNS 中虽有 UDP/TCP 的 3 秒超时，系统解析与 getprop／dumpsys 子进程没有请求级总 deadline；不能将 DNS_TIMEOUT 等同整条代理请求预算。

[main.rs:358](/home/jiale/projects/minis-for-android/src/native/root-network-proxy/src/main.rs:358) 在转发前清除两端所有读写超时。pump 会 join 反向线程，一方向结束并不保证另一方向正在阻塞的读及时返回；只有 handle_client 返回之后才减少 active。闲置隧道、失去进展的对端或半关闭连接可以长期持有线程与槽位，且没有单请求取消句柄。停止整个 helper 能终止这些线程，但这不同于正常连接回收。

这里确认的是应用预算缺失，不声称操作系统连接超时无限，也不把持续传输的合法大文件／长连接本身当作错误。认证、64 槽位与 16 KiB 头限制仍然有效；本轮没有做容量耗尽实验。

**方向与后续验收：** 对首头、解析与建立连接设置完整预算，转发按无进展／空闲条件回收，维持合法双向流与 TCP 半关闭语义。保存超时、线程／槽位释放、恢复后正常请求及 stop 结果。未执行。

## NP03 — HTTP 头的原始字节被再编码

[main.rs:286](/home/jiale/projects/minis-for-android/src/native/root-network-proxy/src/main.rs:286) 将每个输入 byte 转成 Rust char 放进 String；[main.rs:332](/home/jiale/projects/minis-for-android/src/native/root-network-proxy/src/main.rs:332) 再用 String.as_bytes 转发。这对 ASCII 恰好可逆，对 0x80–0xff 则不可逆：一个 0xe9 会变成 Unicode U+00E9，输出为 UTF-8 的 0xc3 0xa9。原本的 UTF-8 多字节值同样会逐字节被再次编码。

因此非 ASCII 的扩展头值会改变内容和长度，可能使源站得到乱码或与客户端计算的头签名不一致。请求正文在 pump 中按字节复制，本项不等于“所有上传正文被改写”。普通 ASCII 认证头也不受这个具体转换影响。

HTTP 报文按 octets 解析，允许的 obs-text 应视为不透明数据；不应未经字段语义授权重编码。[RFC 9112 §2.2](https://www.rfc-editor.org/rfc/rfc9112.html#section-2.2)、[RFC 9110 §5.5](https://www.rfc-editor.org/rfc/rfc9110.html#section-5.5)

**后续验收：** 对 ASCII、单个高位字节、UTF-8 扩展头值分别保存客户端与受控 origin 的逐字节对照；只对确需重写的协议字段按明确规则处理。未执行。

## NP04 — 转发的 Host 没有跟随目标 URI

[parse_target:178](/home/jiale/projects/minis-for-android/src/native/root-network-proxy/src/main.rs:178) 根据 absolute-form URI 选择 host／port，并将首行改成 origin-form；头循环仅特殊处理 Proxy-Authorization，原 Host 被原样保留。

若目标 URI 为 `http://A/path`，传入 Host 为 B，代理向 A 的地址建立连接，却发送 `GET /path` 与 Host B。origin 收到 origin-form 后按 B 做虚拟主机路由，客户端指定的目标与实际处理该请求的虚拟站点发生分离。带非默认端口时也没有统一生成 Host。这里不推断能够访问被拒绝的私网 IP，连接目标仍接受现有地址检查。

RFC 9112 明确要求代理从 absolute-form 请求目标生成新的 Host，而非继续转发传入值。[RFC 9112 §3.2.2](https://www.rfc-editor.org/rfc/rfc9112.html#section-3.2.2)

既有 [main.rs:852](/home/jiale/projects/minis-for-android/src/native/root-network-proxy/src/main.rs:852) 测试的 URI 为 `http://8.8.8.8/...`，Host 却是 archive.ubuntu.com，随后断言旧 Host 仍保留。这验证了目前实现，而非正确的目标一致性。受控 connector 绕过实际连接目标用于测试，不是生产的另一个出口。

**后续验收：** 匹配与不匹配 Host、缺失／重复 Host、默认／非默认端口，核对 URI、目标地址与转发 Host 一致；保存脱敏 request head。未执行。

## NP05 — DNS parser 缺少查询名称关联

[parse_dns_a:559](/home/jiale/projects/minis-for-android/src/native/root-network-proxy/src/main.rs:559) 仅收到 response 和 expected_transaction_id，没有预期 host 参数。它检查 ID、响应标志、opcode、rcode、question 数量和 A／IN 类型，却在 [main.rs:587](/home/jiale/projects/minis-for-android/src/native/root-network-proxy/src/main.rs:587) 直接跳过 question name。answer 的 owner name 也只跳过，随后接受首个 A／IN／4 字节记录。

**明确缺口：** 对目标 A 的请求，如果收到来自当前 resolver、transaction ID 相同、但 question 为 B 的成功 A 响应，现有 parser 会接受 B 的地址。UDP 使用已 connect 的 socket、ID 优先来自随机源、私网目标仍会被后续检查，这些保护都存在；它们不能替代名称匹配。没有证明任何实际 DNS 劫持，也没有做伪造报文网络实验。

RFC 5452 的响应匹配规则包括 query name，以及已经校验的 ID、class/type 和 socket 地址属性。[RFC 5452 §9.1](https://www.rfc-editor.org/rfc/rfc5452.html#section-9.1)

**方向与后续验收：** 将预期问题绑定到 UDP 与 TCP parser；回答名称关联须兼容合法 CNAME 链，不能简单要求所有记录都等于原 host。覆盖同 ID 错名称、错误类型、正确名称大小写差异、合法 CNAME 和截断响应，保存查询及响应结构与拒绝结果。未执行。

## NP06 — 手工 rootfs 安装在验证前破坏旧状态

[install-ubuntu-rootfs.sh:24](/home/jiale/projects/minis-for-android/scripts/install-ubuntu-rootfs.sh:24) 对输入只检查文件存在；允许用户指定任意 tar 路径。后续 push 到固定临时路径，并在 [脚本:31](/home/jiale/projects/minis-for-android/scripts/install-ubuntu-rootfs.sh:31) 先删除现有 rootfs 内容，再以 Root 原地解压。脚本没有调用现有 runtime manifest／SHA-256／tar 结构验证，也没有验证推送后的字节。检查 os-release、bash 和 metadata 在解压完成后才发生。

误选 tar、损坏压缩包、磁盘空间不足或中途失败都可能让旧 runtime 已被删除，而新树不可用。tar 输入的布局／节点安全也没有经过本项目既有验证边界。这里不提供越界 tar，也不声称做过 Root 越界写入；足以确认的是未验证输入即进入破坏步骤。

**范围限制：** 这是仓库保留的手工部署脚本，不是 App 自动 rootfs 修复入口。App 用户数据由独立 backing 持有，本项没有证明正常用户 backing 被删除，也不能用它否定 App 的自动载荷校验。

**方向与后续验收：** 使用现有验证规则，在改变旧树前完成来源、摘要、布局和设备暂存校验，并有可恢复的切换阶段；不要新增通用 Root broker。仅在一次性设备／数据域对误选文件、损坏包、校验不符和安装中断进行验收，保存安装前后 rootfs 身份与恢复证据。未执行。

## NP07 — CI 汇总状态遗漏两个直接相关的 gate

[ci.yml:389](/home/jiale/projects/minis-for-android/.github/workflows/ci.yml:389) 的 Android 汇总仅 needs changes、runtime-payload、root-network-proxy-android、android-checks、android-debug-build、android-release；不包含 root-network-proxy-quality 和 rootfs-verification。汇总步骤也没有检查它们的结果。

因此 Rust clippy／cargo test 失败但 cross-build 和 APK 检查成功时，名为 Android build, test and release checks 的汇总仍可成功。rootfs 的拒绝／边界测试失败也存在相同遗漏。仅修改某些 rootfs 测试脚本时，分类可能是 rootfs=true、android=false，汇总直接报告 Android checks skipped，与该测试 job 的成功或失败无关。

**影响边界：** 单独的失败 job 仍显示失败。本轮没有读取 GitHub branch protection，不宣称目前可以合并失败 PR；如果分支另行要求这些独立 job，它们仍能阻止合并。确认的问题是该汇总本身不代表全部 runtime／代理检查通过；不重复统计旧 F07 的设备端到端覆盖不足。

**后续验收：** 在之后授权的 CI 验收中核对代理质量失败、rootfs 验证失败、正常成功与无需运行的 skip，保存 job 依赖图和汇总状态。当前仅阅读全文，没有触发 workflow 或消耗云资源。

## NP08 — 验证器只识别 Android 默认 debug DN

[verify-android-release.sh:24](/home/jiale/projects/minis-for-android/scripts/verify-android-release.sh:24) 仅以证书输出含 `CN=Android Debug` 拒绝 debug 签名；本项目 [根 build.gradle.kts:61](/home/jiale/projects/minis-for-android/src/android/build.gradle.kts:61) 创建的稳定 debug key 的 DN 为 `CN=Minis Debug, O=Slacker-LLC, C=US`。两者不匹配。

同时 [requireReleaseSigning:124](/home/jiale/projects/minis-for-android/src/android/build.gradle.kts:124) 检查 RELEASE_* 材料齐全和 keystore 文件存在，不检查它与 debug key 的身份关系。若本地误将项目 debug key 配作 release signing，正确生成的 minified release 可以越过这两处签名 gate；其它 payload／DEX 检查并不检查证书身份。

这不等于 Gradle 在缺少签名参数时会回退 debug：缺参数确实失败关闭。CI 也明确生成自己的临时 release key，本轮没有发现线上 APK 使用 debug key 的证据。确认的是“禁止 debug key”的检查遗漏了项目实际使用的 debug 身份，证书 DN 的文字匹配也不能证明生产身份。

**方向与后续验收：** 结合现有发版流程验证预期 signer 身份，明确区分临时 CI key 和生产发布 key，并拒绝项目自己的 debug signer。用现有合法证书的公开身份信息核对判断；不需要输出或读取私钥。实际构建与验签未执行。

## 既有保护、验证限制与阅读进度

rootfs 构建默认 pin、刷新 checksum 元数据、最终 archive digest 与 manifest 关联、tar 重复节点／路径／链接检查、旧 broker 字段拒绝和代理独立 artifact 都有实际实现。本轮没有把这些措施记成缺失。NP06 的入口绕过、NP07 的汇总遗漏、NP08 的 signer 判定缺口应分别处理，不能用其中一个推翻其它入口的有效检查。

19 个新全文文件的行数按 UTF-8 splitlines 计算；累计可核验全文阅读为 **112／1546 个纳入范围的文件，21730 行**，另有 4 个文件仅片段核对，1430 个尚未登记全文。124 个普通文档／许可证／二进制／批量字典另列。全库阅读任务仍未完成。

文件 SHA-256、完整行范围和批次见 [代码阅读台账](/home/jiale/projects/minis-for-android/docs/archive/2026-10-05-code-review-coverage.json)。本轮只检查报告引用与台账一致性；没有运行任何产品／宿主测试。上述建议以后若执行，应保存脱敏请求／响应、错误状态、线程或进程清理、目录身份和 CI 状态，使结论可重复复核。真实 Android Root、VPN／BPF、DNS 和父进程死亡行为仍是未验证的设备事实。
