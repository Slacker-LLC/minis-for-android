# Shizuku与Ubuntu降权：已有测试全文审查

目标主线 `3527ad8165f11c0f98d8a30f96098fc9280faa94`。仅静态通读已有测试，未启动shell、Shizuku、Root、chroot或设备；只新增报告及台账。没有新增已确认缺陷。

## 参数测试范围

ShizukuInputTextTest 验证空格变为%s，单引号、双引号、&和中文保留。它只测试编码函数，没有执行Android input或验证shell引用，不证明中文在真实IME输入正确。原文已含%s、换行、制表符等布局未覆盖，但不凭缺例认定注入。

ShizukuPackageUidTest 对失败exitCode、空输出、未知包以及UID0/999拒绝，接受10234与1000。测试名说低于App范围拒绝，断言实际上允许系统UID1000；不能用标题推断实现只接受应用UID。这是否满足调用权限边界需结合实际工具，不新立越权结论。合成dump没有验证不同Android用户、OEM字段变化或包身份精确关联。

## 降权字符串与真实行为

UbuntuKernelPrivilegeDropTest 调用真实命令构建函数，验证交互入口保留forkpty终端、不使用setsid，非交互入口使用setsid；guest命令带真实示例UID/GID、clear-groups、所有capability集合清空与no-new-privs，并断言后者在env之前。身份脚本不写死10000，UID和GID不同亦被保留。

这些字符串断言不能证明设备setpriv成功、实际CapEff/CapBnd为零、SELinux/OEM行为或进程组清理正确。部分顺序断言借助indexOf，真正设备结果必须另外获取；本轮没有执行。

## 历史迁移是受控兼容动作

测试仍包含legacy迁移脚本，符合一次性旧Root-owned数据迁移用途，不能仅因存在legacy测试就认定PRoot或旧broker恢复生产。

命令断言覆盖目标父目录、软链拒绝、cp -a -n不覆盖、进度标记与重试跳过。最后一例实际设计为宿主shell执行LEGACY_LINK_CHECK片段，将Android find路径替换成宿主find：允许来源原有同目标链接，拒绝新植入或改向链接。不是完整Root迁移；源链接可存在与禁止目标新植入链接的策略不同。符号链接不支持时可跳过，测试存在不代表所有CI都执行了该断言。

本轮仅阅读这段ProcessBuilder代码，未运行。完整拷贝中断、进度落盘、并发文件变化及最终ownership需要按生产调用继续核对。

## 覆盖与交付

| 全文文件 | 行数 |
|---|---:|
| src/android/app/src/test/java/com/openminis/app/runtime/guest/ShizukuInputTextTest.kt | 18 |
| src/android/app/src/test/java/com/openminis/app/runtime/guest/ShizukuPackageUidTest.kt | 38 |
| src/android/app/src/test/java/com/openminis/app/runtime/ubuntu/UbuntuKernelPrivilegeDropTest.kt | 128 |

三个现版文件完整读取。新增全文3文件、184行；已全文重读0文件。其它rg定位不计全文。

累计全文1389 / 1758文件，210744行；partial 4，未登记365。全库通读未完成。[新台账](/home/jiale/projects/minis-for-android/docs/archive/2026-10-09-code-review-coverage-3527ad81-privilege-tests.json)。

校验旧234报告/全部旧台账hash、HEAD/origin/main、全快照Git blob/SHA-256/字节/行数、tracked/staged空diff。只新增报告与台账。
