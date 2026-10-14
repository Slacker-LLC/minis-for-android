# Soul图标导入与解码：已有测试全文审查

目标主线 `3527ad8165f11c0f98d8a30f96098fc9280faa94`。只读已有测试，未解码图片、启动设备或执行测试；仅新增报告及台账，没有新增已确认缺陷。

## SI系列已有断言

SoulIconImportTest 按SI01/02/03分组：采样不把短边压到96像素以下、接近目标不采样、异常尺寸回1；minis地址百分号与UTF-8严格解码、加号保持字面、只解码一次、非法转义拒绝；变体选择符不能把普通字母数字变emoji，常见keycap/旗帜/ZWJ/肤色接受。

路径穿越例明确期待 classifySource 返回包含../的LinuxPath，目的是交给后续安全路径校验。它没有调用该安全层并验证拒绝，所以不能说分类函数本身阻止穿越，也不能反过来据分类返回认定文件API越界。采样用例对400×10000保留短边目标，但未验证超长细图的总像素预算或最终中心裁切。

## 真实解码测试范围

SoulIconDecodeInstrumentedTest 设计为Android BitmapFactory/encode真实路径，生成4000平方的单色PNG，验证压缩文件较小、解码宽度按采样计算且远低于原尺寸、encode返回Success；48平方图片不采样，随机非图片与空数组返回null。

这是已有设备测试代码，尚未运行。它不能证明所有编码格式、EXIF方向、恶意头部、读取上限、最终存储或UI显示。大例生成测试素材本身先分配全尺寸Bitmap，不能用此测试过程说产品从未分配大图；产品解码与测试造图应区分。

小图用例没有显式recycle原bitmap及decoded，大图编码是否消费decoded需看encode实现；这是测试资源清理边界，不新立生产泄漏缺陷。没有真实会话导入、文件API或并发取消证据。

本轮保留既有SI编号背景，不仅凭已有测试就宣布全部SI问题闭合；现版实现调用链与测试范围分开核对。

## 覆盖与交付

| 全文文件 | 行数 |
|---|---:|
| src/android/app/src/test/java/com/openminis/app/agent/SoulIconImportTest.kt | 97 |
| src/android/app/src/androidTest/java/com/openminis/app/agent/SoulIconDecodeInstrumentedTest.kt | 54 |

两个现版文件完整读取。新增全文2文件、151行；已全文重读0文件。其它rg定位不计全文。

累计全文1414 / 1758文件，212330行；partial 4，未登记340。全库通读未完成。[新台账](/home/jiale/projects/minis-for-android/docs/archive/2026-10-09-code-review-coverage-3527ad81-soul-icon-tests.json)。

校验旧243报告/全部旧台账hash、HEAD/origin/main、全快照Git blob/SHA-256/字节/行数、tracked/staged空diff。只新增报告与台账。
