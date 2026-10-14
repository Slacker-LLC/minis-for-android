# 最新主线与 minis-apt 参数语义审查

2026-10-09，工作区HEAD、origin/main及远端main均为 `3527ad8165f11c0f98d8a30f96098fc9280faa94`。旧198份报告及所有旧覆盖台账保持原内容，仅新增本报告和新版台账。只静态阅读、Git读取及查询Ubuntu官方手册；未修改产品源码、编写或运行测试、构建、启动模拟器/设备/云资源，也未执行apt、Root、guest命令或读取凭据。

## APT01 / P1：install 分支可通过包名动作后缀绕过保护包移除检查

[AptCommandPolicy.kt:22](https://github.com/Slacker-LLC/minis-for-android/blob/3527ad8165f11c0f98d8a30f96098fc9280faa94/src/android/app/src/main/java/com/openminis/app/runtime/ubuntu/AptCommandPolicy.kt#L22) 的正则允许末尾 `-`、`+`。parse仅检查字符及长度，然后按用户verb构造Install/Remove。比如 `install python3-` 符合当前解析条件，会生成 `apt-get ... install -y --no-install-recommends python3-`；没有经过remove分支的模拟和PROTECTED检查。

[Ubuntu 24.04 apt-get官方手册](https://manpages.ubuntu.com/manpages/noble/man8/apt-get.8.html)明确规定，包名附加减号表示移除该包，附加加号表示安装；这是apt的参数语义，不是shell注入。python3属于BASE_PACKAGES和PROTECTED，但这里保护检查仅出现在Remove分支。安装已存在的非Essential运行时依赖时，允许的字符串可能实际要求移除它及依赖者，从而破坏Ubuntu运行环境。不能依赖apt自身的Essential保护覆盖App自己定义的全部运行时必需包。

生产启动MinisApp:884注册此handler；handler先parse，再走apt_cli权限，随后在App构造的Root namespace内运行策略生成脚本。不是未接线helper。已有权限仍生效，此问题不代表绕过apt_cli拒绝，也不证明能操作Android宿主文件。成立条件为调用被允许、相关包已安装且apt能解析该操作；未在设备上执行，不声称已发生卸载。

同一根因的反向语义：`remove cowsay+`通过同一正则，apt可按安装动作处理它，违背纯remove请求。仅增加shellQuote或 `--` 不能消除包名动作后缀语义。

记录方向：按apt的实际操作语义校验请求；安装分支也要确保不移除运行时保护包（普通安装的依赖求解也可能产生移除），不能仅用字符白名单替代最终操作检查。后续修复由Claude处理，本轮没有写实现或测试。

## 已有防护和证据限制

parse拒绝未知verb、空包列表、超20项、选项/版本/路径/shell字符，去重；update不允许附带参数。handler走OffloadGate，有AtomicBoolean控制本handler同时间一个操作，DNS准备失败先返回，结果有20分钟超时和8000字符最终输出限制。最终字符串截断不等于底层进程从未积累更大的输出，相关边界仍需DirectRootRunner的实际实现证明。

[UbuntuProvisioner.kt:217](https://github.com/Slacker-LLC/minis-for-android/blob/3527ad8165f11c0f98d8a30f96098fc9280faa94/src/android/app/src/main/java/com/openminis/app/runtime/ubuntu/UbuntuProvisioner.kt#L217)清空环境并设LANG/LC_ALL=C.UTF-8，再进入私有mount namespace的rootfs；不能仅凭移除结果是英文就报locale绕过。AtomicBoolean的边界是此handler实例，不自动覆盖Provisioner或其它包管理入口。

AptCommandPolicyTest全文已有负向用例拒绝前导选项、shell字符、版本和路径，真实调用policy生成器；移除保护部分以stub替代apt-get运行生成的脚本。它检验构造与模拟结果解析，不是Ubuntu真实apt参数语义或Android Root端到端验收；没有 `python3-`/`cowsay+`动作后缀反例。本轮未运行测试。

官方手册也说明不存在的含点表达式可能触发apt-get正则回退；当前白名单允许点。先记为进一步核验候选，不宣称已在设备批量安装/移除。rootfs挂载边界、maintainer scripts及MCP/无人值守入口需要继续沿实际链路审查，不能由包名校验推出全部Root边界已安全。

## 全文覆盖及主线变更

本轮全文：

| 文件 | 行数 |
|---|---:|
| src/android/app/src/main/java/com/openminis/app/runtime/ubuntu/AptCommandPolicy.kt | 104 |
| src/android/app/src/main/java/com/openminis/app/runtime/guest/AptOffloadHandler.kt | 90 |
| src/android/app/src/main/java/com/openminis/app/runtime/guest/OffloadGate.kt | 83 |
| src/android/app/src/test/java/com/openminis/app/runtime/ubuntu/AptCommandPolicyTest.kt | 128 |
| src/android/app/src/main/java/com/openminis/app/notification/BackgroundTaskNotifier.kt | 185 |
| src/android/app/src/main/java/com/openminis/app/notification/ConfigConfirmNotifier.kt | 133 |
| src/android/app/src/main/java/com/openminis/app/power/PowerOptimizationManager.kt | 188 |
| src/android/app/src/main/java/com/openminis/app/i18n/LocaleWrap.kt | 70 |
| src/android/app/src/main/java/com/openminis/app/pet/PetBridge.kt | 62 |

通知、配置确认、电源优化、LocaleWrap、PetBridge五文件638行在前轮已完整阅读，本轮验证其SHA-256与最新主线一致后正式入账；没有将grep命中的MinisApp或GuestCommandBridge算全文。OffloadGate若此前已全文则仅重读，不重复累计。

主线相对ea620e9e新增103路径、变化177路径、移除6路径；完整列表在台账transition。大量文件已被修复或拆分，变化文件的旧阅读证据全部失效，不能沿用旧1403全文数作为新版事实。旧报告中的问题是否修复仍需逐项重新核验，这份报告没有将旧问题一概宣布继续存在。

最新累计：全文1282 / 纳入1758文件，191874行；partial 3、未登记473，excluded 127，tracked 1885。全库通读未完成。

[新版覆盖台账](/home/jiale/projects/minis-for-android/docs/archive/2026-10-09-code-review-coverage-3527ad81-apt.json)。快照逐个读取Git blob，校验Git对象SHA-1、SHA-256、字节与行数；不使用可能转换换行的archive。旧报告和台账逐份hash校验，tracked/staged源码diff为空。历史d64覆盖台账保持原有版本证据，不拿当前3527工作区去冒充其旧版本源码。
