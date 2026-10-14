# 备份目的地页面与连接保存状态全文审查

2026-10-09，固定主线3527ad8165f11c0f98d8a30f96098fc9280faa94。页面566行分1—285、286—566完整阅读；ViewModel全文阅读。旧215报告及全部台账不覆盖。未改产品代码、执行测试、构建、远程存储连接、设备、Root或云操作。

## 旧新建目录问题仍在

createFolder同步置_busy=true，再创建远程目录、刷新父目录并发布b.copy(entries)。随后调用navigateTo(newPath)，后者首部_busy检查直接return；finally才清busy。页面“使用此文件夹”仍提交b.path，故旧新建后不自动进入的报告根因未改变。目录会在父列表中出现，用户可手动进入，不把它称为目录创建失败或无法选择。

## 保存成功与失败的界面衔接

backup模式连接后进入folder picker；restore模式成功后saveConnected跳过目录选择。connectAndBrowse在finally清忙后才调用onConnected，避免清掉后续保存的忙状态。savePending成功更新列表、清browse/pendingSecret再回调；失败保留页面并写_error，FolderBrowser在成功回调后才退出。不能继续声称当前保存成功和失败都完全没有反馈。

持久store.add后syncToRclone失败的事务一致性仍依赖store实现，当前catch不做rollback，沿旧备份配置报告处理；本轮不由UI局部再确认一套重复问题。列表的enable/remove调用不受页面busy禁用，是否与共享remote同步存在竞争需存储拥有者，不凭按钮启用就登记生产故障。

## 表单与TLS边界

必填名字UI允许字母数字/-/_；其它连接字段由catalog渲染，secret遮盖。连接时复制fieldValues，忙时文本/取消/连接按钮禁用，但backend下拉本身没有busy禁用，可在连接中切换并清字段。请求已有快照，是否造成用户误认结果需产品交互验证，保持候选。

allowInsecureTLS在表单remember，证书拒绝flag在VM；切backend只清fieldValues，不清这个opt-in。VM存在clearCertificateRejection但该表单编辑回调未调用。用户更换服务器后旧例外的适用范围值得继续核对；全局setInsecureTLS的跨remote作用域已经在旧报告记录，不重复把同根因登记为新项。

取消pending只清browse和pendingSecret，不从rclone删除ephemeral配置，也不重置全局TLS；旧remote秘密/生命周期边界继续按原报告。这里没有执行连接或读取真实凭据。

## 页面其它处理

表单复用SettingsScaffold的滚动容器，不另嵌纵向滚动。路径展示、上级/子目录导航、空目录状态、创建对话框与错误消息均接现有VM。新建对话框确认立即关闭，失败反馈回到页面_error；不把关闭对话框当操作成功的证明。

本轮新增确认问题0，旧目录导航问题补现版证据。页面partial已补全文；ViewModel若此前full按重读计，不重复增加覆盖。全部读取无截断，测试及网络操作未运行。

## 覆盖与交付

| 全文文件 | 行数 |
|---|---:|
| src/android/app/src/main/java/com/openminis/app/ui/settings/backup/RcloneDestinationsScreen.kt | 566 |
| src/android/app/src/main/java/com/openminis/app/ui/settings/backup/RcloneDestinationsViewModel.kt | 286 |

两个现版文件完整读取。新增全文1文件、566行；已全文重读1文件。其它rg定位不计全文。

累计全文1336 / 1758文件，204517行；partial 3，未登记419。全库通读未完成。[新台账](/home/jiale/projects/minis-for-android/docs/archive/2026-10-09-code-review-coverage-3527ad81-rclone-screen.json)。

校验旧215报告/全部旧台账hash、HEAD/origin/main、全快照Git blob/SHA-256/字节/行数、tracked/staged空diff。只新增报告与台账。
