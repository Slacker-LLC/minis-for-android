# MCP清单契约夹具：现版全文审查

目标主线 `3527ad8165f11c0f98d8a30f96098fc9280faa94`。只读已有测试及JSON夹具，不连接MCP、不执行工具或测试；仅新增报告与台账。没有新增已确认缺陷。

## 已有断言

MCPToolsListContractFixtureTest 调用真实parseToolsList、schemaToParams、sanitizeId及apiName。夹具覆盖snake_case与camelCase输入schema、空名称跳过并计数、缺description/schema宽松接收、missing tools数组当空页、名称清理后的固定wire值。额外内联JSON验证两种schema同时存在时camelCase优先。

测试明确声明这是仓库观察到的行为，不是上游MCP一致性套件。本轮不根据夹具注释推断当前外部协议，也不把宽松解析直接判为不合规。

## 验证边界

schemaToParams投影只覆盖属性名称、简单string和enum，夹具required被直接读取但没有执行工具时缺必需参数的拒绝断言。不能由typed parameters存在证明完整JSON Schema校验，包括嵌套对象、数组、oneOf、additionalProperties或根schema约束。

缺tools数组接受空页是明确预期，但没有rpc error envelope、损坏result、分页nextCursor重复或远端工具执行错误的用例。名称测试只对一个预期sanitize值，注释说相似清理结果的两个服务器保持区分，实际没有同时构造该对照并断言不同；digest绝对唯一也未被证明。

整个测试没有网络传输、连接认证、远端权限和执行dispatch。资源读取use关闭stream是真实测试结构，但未运行，不声称夹具测试已通过。夹具与源码都完整通读，非代码资源计入原台账范围，未缩小全库目标。

## 覆盖与交付

| 全文文件 | 行数 |
|---|---:|
| src/android/app/src/test/java/com/openminis/app/mcp/client/MCPToolsListContractFixtureTest.kt | 117 |
| src/android/app/src/test/resources/mcp/tools-list-contract/repo-observed.json | 56 |

两个现版文件完整读取。新增全文2文件、173行；已全文重读0文件。其它rg定位不计全文。

累计全文1420 / 1758文件，212856行；partial 4，未登记334。全库通读未完成。[新台账](/home/jiale/projects/minis-for-android/docs/archive/2026-10-09-code-review-coverage-3527ad81-mcp-fixture.json)。

校验旧246报告/全部旧台账hash、HEAD/origin/main、全快照Git blob/SHA-256/字节/行数、tracked/staged空diff。只新增报告与台账。
