# 备份KDF与历史：已有测试范围全文审查

目标主线 `3527ad8165f11c0f98d8a30f96098fc9280faa94`。仅静态通读已有测试，未派生密钥、写文件或执行测试；只新增报告及台账，没有新增已确认产品缺陷。

## KDF与加密命名

BackupKdfBoundsTest 对 deriveKeys 明确断言最大整数、超上限、零和负数被拒绝；普通上限和缺省值只检查 checkedIterations，实际成功派生只用1000次。因此它能针对无界计算入口建立否定用例，但不证明完整包认证、所有算法/盐长度/Base64损坏条件或最大允许值的设备耗时。未执行这些断言。

同文件另有 BackupEncryptionOrderTest，调用真实 encryptionOrder，再以文件名集合模拟 X→X.enc。覆盖单层和多层.enc碰撞链，但没有打开或加密真实文件，不验证失败中途恢复、磁盘不足、符号链接、并发目录变化或密文可解密。不能把集合模拟写成真实包加密往返。

## 目标身份

BackupDestinationIdentityTest 直接检查 sameDestination 对kind/path相同接受、文件夹或backend变化拒绝、旧记录缺少字段拒绝。这些测试的destinationId均为nas，不模拟相同kind/path下服务器URL、账户或凭据变化；测试标题称服务器被识别，不等于所有远端身份字段已覆盖。需要按生产目标配置的真实path定义核对，本轮不直接认定误删新服务器文件。

## 历史持久化

BackupHistoryPersistenceTest 使用临时目录和真实BackupHistory：新实例读取记录、父目录缺失时upsert失败且内存不变、截断JSON隔离为.corrupt并保留、remove与clear保存。比只测纯序列化更接近真实存储路径，但仍没有进程强杀、fsync/断电、两个实例并发、重复损坏隔离文件或写到一半失败的证据。

ContextWrapper(null)用于forFile注入文件，不能据此声称Android真实Context启动和所有UI失败提示已验证。remove/clear仅成功路径，不能从upsert失败例泛化证明所有修改失败时内存都一致。具体实现已审范围与此新增测试范围分开，不凭缺测试新立Bug。

## 覆盖与交付

| 全文文件 | 行数 |
|---|---:|
| src/android/app/src/test/java/com/openminis/app/backup/BackupKdfBoundsTest.kt | 74 |
| src/android/app/src/test/java/com/openminis/app/backup/BackupDestinationIdentityTest.kt | 28 |
| src/android/app/src/test/java/com/openminis/app/backup/BackupHistoryPersistenceTest.kt | 57 |

三个现版文件完整读取。新增全文3文件、159行；已全文重读0文件。其它rg定位不计全文。

累计全文1379 / 1758文件，210032行；partial 4，未登记375。全库通读未完成。[新台账](/home/jiale/projects/minis-for-android/docs/archive/2026-10-09-code-review-coverage-3527ad81-backup-history-tests.json)。

校验旧231报告/全部旧台账hash、HEAD/origin/main、全快照Git blob/SHA-256/字节/行数、tracked/staged空diff。只新增报告与台账。
