# 调试RPC宿主与目录：现版全文审查

目标主线 `3527ad8165f11c0f98d8a30f96098fc9280faa94`。只读源码，不调用RPC、Shizuku或配置接口；只新增报告及台账。没有新增已确认缺陷。

## 拆分后的当前结构

DebugRPCHandler.kt 当前主要持有Context、当前Activity弱引用、崩溃日志文件名正则和RPCException；真正分派已在扩展文件DebugRPCHandlerDispatch.kt。不能从这个宿主类没有dispatch函数就认定整个RPC不可用，也不把空白行当新问题。

DebugMethodRegistry 合并三个baseMethods列表，并仅在BuildConfig.DEBUG时添加四个直接执行调试方法。ParamSpec编码name/type/required/description及非null default，方法编码returns和example；discover额外返回版本、build、设备与数量。这只是发现目录，不是参数验证或鉴权实现。

## Release拒绝确实存在于分派

局部读取DebugRPCHandlerDispatch第240—287行：debug.shizuku.exec、debug.modelUse.exec、debug.sessions.exec、debug.minisConfig.exec各自先检查BuildConfig.DEBUG，否则抛-32601。所以不能只看到目录中的直接执行方法或skipConfirmation描述就宣布Release暴露了这些入口。

此处是源码保护证据，没有构建Release、验证R8或调用设备端口。分派局部阅读不新增全文登记。BASE_METHODS另有常规Agent接口，不能将整个调试服务概括成只在Debug存在；服务监听、身份验证与每个常规方法边界需结合已审及后续代码。

## 清单准确性边界

方法目录是静态描述，required/type不会在此自动强制。四个方法的args/command替代规则及minisConfig各subcommand参数仍需处理函数验证。description中的默认skipConfirmation=true没有同时写入ParamSpec.default，调用方不能只读机器default字段获知它；本轮仅记录信息表达差异，不新立绕过缺陷。

methods和example保存JSON对象引用，discover复用example而非深拷贝；是否有进程内调用方修改它需核对实际使用，不将可能的可变引用直接认定目录被篡改。当前Activity为弱引用可减少持有Activity，但也不独立证明截图线程和生命周期正确。

## 覆盖与交付

| 全文文件 | 行数 |
|---|---:|
| src/android/app/src/main/java/com/openminis/app/debug/DebugRPCHandler.kt | 87 |
| src/android/app/src/main/java/com/openminis/app/debug/DebugMethodRegistry.kt | 149 |

两个现版文件完整读取。新增全文2文件、236行；已全文重读0文件。其它rg定位不计全文。

累计全文1400 / 1758文件，211652行；partial 4，未登记354。全库通读未完成。[新台账](/home/jiale/projects/minis-for-android/docs/archive/2026-10-09-code-review-coverage-3527ad81-debug-catalog.json)。

校验旧238报告/全部旧台账hash、HEAD/origin/main、全快照Git blob/SHA-256/字节/行数、tracked/staged空diff。只新增报告与台账。
