# 调试截图生命周期：现版全文审查

目标主线 `3527ad8165f11c0f98d8a30f96098fc9280faa94`。仅静态阅读，不截图、不运行测试或调用RPC；只新增报告和台账。

## DSC01：已修分支与剩余交接候选

[旧DSC01报告](/home/jiale/projects/minis-for-android/docs/archive/2026-10-07-config-session-mcp-rpc-screenshot-full-audit.md)的PixelCopy失败目标未回收已改善：现版失败回调先recycle目标，再生成fallback；回调时cont不活跃，成功目标也会recycle。压缩已放在try/finally内回收返回bitmap。因此不能再用旧失败分支和压缩异常描述现状。

仍需核对的交接窗口：cont.isActive检查后resume与协程实际取得bitmap之间可取消，当前没有显式交接取消清理；withContext(Main)返回给外层capture之前也可能未消费。源代码没有证明这两个窗口资源已妥善释放，但本轮不执行竞态，不宣称永久泄漏或设备OOM。沿用DSC01的取消交接候选，不新增编号。PixelCopy.request同步抛异常和fallback draw异常也没有本层完整回收证据，实际发生条件待验证。

## 缓存边界

add/list/get/clearAll通过同一对象同步锁保护队列，最多16条；ID由AtomicInteger分配。上限限制条数不是总PNG字节，也不限制并行capture的临时bitmap。list返回队列快照，但Entry及pngBytes引用仍共享；没有调用方修改数组的实证，不新立损坏缺陷。clear与正在进行的capture交错后后者仍可能add，应结合clear的产品语义，不直接称清理失效。

调用端静态定位scale限制0.05—1.0；不把其他Browser任意viewport问题复制成无约束截图尺寸。未验证真实Window、PixelCopy或canvas回退。

## 聊天投影测试

ChatMessageProjectionTest 覆盖mediaRef及旧附件kind识别、includeTools=false过滤toolUse/toolResult/tool_call并保留普通与bare字符串、true时保留数量。true用例只断言长度，不比较原对象或全部字段，不能据标题untouched证明深层完全不变。它不是截图测试，也没有真实数据库RPC投影和权限证据；本轮只阅读已有断言。

## 覆盖与交付

| 全文文件 | 行数 |
|---|---:|
| src/android/app/src/main/java/com/openminis/app/debug/DebugScreenshotRing.kt | 121 |
| src/android/app/src/test/java/com/openminis/app/debug/ChatMessageProjectionTest.kt | 34 |

两个现版文件完整读取。新增全文2文件、155行；已全文重读0文件。其它rg定位不计全文。

累计全文1402 / 1758文件，211807行；partial 4，未登记352。全库通读未完成。[新台账](/home/jiale/projects/minis-for-android/docs/archive/2026-10-09-code-review-coverage-3527ad81-debug-screenshot.json)。

校验旧239报告/全部旧台账hash、HEAD/origin/main、全快照Git blob/SHA-256/字节/行数、tracked/staged空diff。只新增报告与台账。
