# 后台Shell作业与FIFO输入生命周期审查

2026-10-09，固定主线 `3527ad8165f11c0f98d8a30f96098fc9280faa94`。旧199份报告和所有旧台账保留。本轮仅静态阅读及查Linux官方man-pages，没有改产品代码、编写或运行测试、构建、模拟器、adb、Root、设备、云资源或凭据使用。

## JSI01 / P2：关闭等待打开的FIFO后，新写端没有纳入收尾

[JobStdin.kt:37](https://github.com/Slacker-LLC/minis-for-android/blob/3527ad8165f11c0f98d8a30f96098fc9280faa94/src/android/app/src/main/java/com/openminis/app/runtime/JobStdin.kt#L37) opener直接把FileOutputStream赋给out，再释放opened latch；没有在打开后检查closed。[JobStdin.kt:76](https://github.com/Slacker-LLC/minis-for-android/blob/3527ad8165f11c0f98d8a30f96098fc9280faa94/src/android/app/src/main/java/com/openminis/app/runtime/JobStdin.kt#L76) close先closeLocked（closed=true，并关闭当时的out），再为了唤醒等待的writer打开并立即关闭自己的读端。

明确交错：create的writer尚在等待读端 → closeLocked看到out=null → close打开自己的读端唤醒writer → writer成功打开并把新stream赋给out → latch释放/join返回 → close删除FIFO文件并返回。新stream创建发生在唯一closeLocked之后，close后半段不再关闭它，opener也不根据closed清理它。

结果：一次close没有保证已打开资源释放，后续write因closed=true拒绝输入；该写端只能依靠其它重复stop/close或后续GC清理。这里不声称永久泄漏：ShellJobs的取消和finally可能再次stop，使某些路径被第二次close修补，但自然结束finally的一次stop不能保证这一点。删除FIFO路径不等于关闭其已打开描述符。

真实接线是[ExecutionCoordinator.kt:172](https://github.com/Slacker-LLC/minis-for-android/blob/3527ad8165f11c0f98d8a30f96098fc9280faa94/src/android/app/src/main/java/com/openminis/app/runtime/ExecutionCoordinator.kt#L172)创建session私有/tmp管道、传入wrapper，stop调用stdin.close；不存在“没有生产调用方”的免责。修复记录方向：打开成功与关闭状态的资源交接要在同一同步边界处理，closed之后打开的stream立即清理，不能依赖重复close。没有实施。

## JSI02 / P2：close的自唤醒读端会在竞态下阻塞

同一close函数在opened.count>0时用阻塞FileInputStream(host)打开读端，然后才join(500)。[Linux FIFO手册](https://www.man7.org/linux/man-pages/man7/fifo.7.html)说明普通FIFO打开需要另一端参与。

明确交错：guest曾打开读端让writer的FileOutputStream成功，writer已赋值out但尚未执行finally countDown；guest短命令结束关闭读端；closeLocked关闭已经赋值的out；close此时仍看到latch count>0，于是打开FileInputStream。唯一opener的写端已经被关闭，且opener后面只会countDown，不会再打开新writer。该FileInputStream可一直等待写端，代码无法走到join(500)或host.delete。

因此500毫秒不是close整体的上限。这不是把普通read等待误报为故障，而是收尾过程自己打开了一个没有未来写端的阻塞读端。JobRegistry.kill在标记KILLED后同步调用canceller，ShellJobs先process.stop再coroutine.cancel；Production stop同步进入stdin.close。若发生上述交错，状态已KILLED但取消调用或finally收尾不能结束。没有在设备上制造竞态，不声称每次停止必然卡住或主线程必然ANR。

记录方向：自唤醒不得引入无界阻塞，结合打开/关闭状态设计有界资源交接；不要仅扩大join时间。与JSI01共享类但失败性质不同：一项是新资源逃过close，另一项是close不能返回。

## 输入背压与已有保护

JobStdin.write仅给opened.await设置10秒预算，stream.write/flush在对象monitor内是阻塞I/O，无写入截止或非阻塞设置。[Linux pipe手册](https://www.man7.org/linux/man-pages/man7/pipe.7.html)明确满管道的阻塞写会等待消费。因此进程保留读端但停止消费时，输入调用可能超过schema的30秒合作式超时；Dispatchers.IO不把Java阻塞写自动变成可取消I/O。作为后续链路验收候选记录，不另报“kill必然死锁”：Production stop先杀shell/进程组，读端真正消失通常可释放写入，不能忽略这步。

ShellJobs输入查找运行作业并在sessionId非null时拒绝其它session；null调用者按现有接口允许全局管理，不能仅凭该条件宣称guest工作区逃逸。create失败回落无FIFO执行是明确行为。wrapper路径由UUID及固定/tmp前缀生成，不能用其未通用shellQuote推断现实可达路径注入。

作业限制为每session6、全局16，timeout限幅1秒到24小时；自然成功/失败/超时回写registry；finally stop并移除entry；kill回调绑定真实process而不只是改标签。ShellBackground返回success=true表示创建成功，正文带终态/退出原因；不把“进程后来失败”与“创建失败”混成同一种状态缺陷。

## 旧F22已修的范围，RUNNING淘汰候选仍在

旧[Android日志/作业审查](/home/jiale/projects/minis-for-android/docs/archive/2026-10-07-android-log-apk-job-fix-boundary-audit.md)已有trimOldest移除RUNNING及canceller而不取消worker的候选，本轮不重新编号。

JobRegistry全文确认普通kill真实调用canceller，F22原“只改标签”条件已修。trimOldest仍按最早startedAt淘汰，不区分终态。ShellJobs新增生产影响：registry条目被淘汰后，running统计不再算它，下一次start还会删除对应entries；killSession/killAll随后也无法从entries定位该作业。实际worker仍可执行到自身deadline/finally，而新增任务可能不再受这部分活动数计数。需要足够其它JobRegistry.start让总项数超过100；这是有条件交错，未制造实际序列，不把所有ShellJobs取消路径报坏。保留在旧候选家族，交给后续修复核验。

## 测试及覆盖

JobStdinTest88行的真实宿主FIFO例验证按序输入/EOF、无reader早停、wrapper回落；没有强制暂停在out赋值和latch释放之间，也没有断言早停后新stream描述符实际关闭。无reader用例只断言文件删除不能证明JSI01无资源残留。

ShellJobsTest220行使用FakeProcess和真实Registry，覆盖输出、成功/失败/超时结果、启动异常、kill、session/全局stop、session上限、释放槽位、游标和输入归属。FakeProcess不创建真实guest/FIFO；这些测试不能覆盖JSI01/02、真机Root进程组或进程死亡后残留。本轮只阅读，未运行。测试中timeout是FakeProcess返回timedOut，不是实际等2小时证明deadline执行。

| 全文文件 | 行数 |
|---|---:|
| src/android/app/src/main/java/com/openminis/app/runtime/JobStdin.kt | 101 |
| src/android/app/src/main/java/com/openminis/app/runtime/ShellJobs.kt | 142 |
| src/android/app/src/main/java/com/openminis/app/tools/runtime/ShellBackground.kt | 54 |
| src/android/app/src/main/java/com/openminis/app/tools/JobRegistry.kt | 199 |
| src/android/app/src/main/java/com/openminis/app/tools/JobTools.kt | 190 |
| src/android/app/src/test/java/com/openminis/app/runtime/JobStdinTest.kt | 88 |
| src/android/app/src/test/java/com/openminis/app/runtime/ShellJobsTest.kt | 220 |

全文输出已补齐：JobTools初次组合输出截断，随后1—105及100—末尾补读；rg、ExecutionCoordinator/RootPersistentShell片段不算全文。新增全文7文件、994行；重读已有全文0文件。

累计全文1289 / 1758文件，192868行；partial 4，未登记465。全库通读仍未完成。[新台账](/home/jiale/projects/minis-for-android/docs/archive/2026-10-09-code-review-coverage-3527ad81-shell-jobs.json)。

交付核验：旧199报告/所有旧台账hash、当前HEAD和origin/main、新版全部快照SHA-256/字节/行数与Git blob一致、tracked/staged diff为空。仅新增本文和新版台账。
