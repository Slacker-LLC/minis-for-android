# 表格选择与滚动缓存：已有测试全文审查

目标主线 `3527ad8165f11c0f98d8a30f96098fc9280faa94`。仅静态阅读，未启动UI或执行测试；只新增报告及台账。没有新增已确认产品缺陷。

## 选择用例没有调用生产选择器

AtomicCellSelectionTest 自己实现 atomicBounds 和 sentenceBounds，注释称镜像生产SelectionController。所有断言实际调用测试私有函数，覆盖标点／版本号／CJK／空白／空格裁切／普通句子。即使生产beginSelectionWord不再走atomic分支，这些用例仍可能通过，不能用它们证明生产表格长按已正确。

“press offset无关”用例遍历offset却从未把offset传给atomicBounds，每次计算相同结果。这只显示该测试私有函数没有offset参数，不验证真实按压位置、表格单元边界、选择注册或Compose手势。记录测试证据局限，不把它等同于生产选择已坏，也不新增镜像单测。

## 滚动缓存测试范围

TableHScrollStatesTest 调用真实stateFor，断言相同字符串键复用实例、不同消息/分片键不同、不断重新访问hot键时能经受200项插入。它没有修改offset、流式重解析、live→frozen迁移或实际表格布局；所以只能支持给定键的缓存行为，不能证明生产迁移前后始终使用同键。

表格键在测试里也由私有helper拼接，没有调用生产键生成；headers用竖线连接的唯一性与分片稳定性需要核对真实调用方。热键保留断言不能证明LRU实际淘汰或总容量上限，因为它没检查冷键重新创建和缓存数量。全局缓存没有测试前后清空，执行隔离需另看运行配置。

本轮没有运行已有测试。继续以生产调用链为修复是否闭合的依据，而不是只依据这些用例名称。

## 覆盖与交付

| 全文文件 | 行数 |
|---|---:|
| src/android/app/src/test/java/com/openminis/app/ui/chat/AtomicCellSelectionTest.kt | 108 |
| src/android/app/src/test/java/com/openminis/app/ui/chat/TableHScrollStatesTest.kt | 47 |

两个现版文件完整读取。新增全文2文件、155行；已全文重读0文件。其它rg定位不计全文。

累计全文1416 / 1758文件，212485行；partial 4，未登记338。全库通读未完成。[新台账](/home/jiale/projects/minis-for-android/docs/archive/2026-10-09-code-review-coverage-3527ad81-table-tests.json)。

校验旧244报告/全部旧台账hash、HEAD/origin/main、全快照Git blob/SHA-256/字节/行数、tracked/staged空diff。只新增报告与台账。
