# 工具注册、路由、参数与权限测试静态审查

日期：2026-10-06。固定本仓HEAD `d64e7c06d8159be90737dd9ba03dd9bd5855f339`。新增全文11文件、1064行，本轮没有确认新的产品缺陷。只静态阅读测试及相关生产分支，没有编写/运行测试、启动设备/模拟器、构建、adb、Root、云或模型任务；不读取账号、凭据与真实会话。另建报告，旧报告不改。

## 覆盖的真实决策与证据边界

AppStatePolicyTest验证am force-stop与pm disable-user/enable的argv、用户作用域、动作规范化及未知动作拒绝。调用真实AppStatePolicy，不执行pm/am。生产AndroidPackageController第159/183行使用此argv；包名校验在调用方，不能因纯argv测试没覆盖包名就认定输入直接进入shell或声明设备冻结已验证。

SubAgentToolExposureTest调用真实AgentTools.makeAgentTools，验证roster schema、旧single-shot schema与child不暴露委派工具。[AgentTools.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/AgentTools.kt:72) 当前child分支与后续registry追加过滤均有对应条件。它证明schema选择测试覆盖了当前函数，不能证明子代理生命周期、取消、权限或并发会话正确；这些已有报告的执行链问题不在这里重复计数。

ToolTimeoutPolicyTest调用真实resolve，验证shell900秒上限、python300秒默认、root30秒默认/120秒上限、MCP60秒默认、declared预算不允许调用者提高以及交互工具无声明deadline。生产RootShellHandler第64行、MCPToolHandler第33行、ToolRegistry第250/306行使用该策略。策略断言不是进程组kill、超时后子进程清理或设备命令确实停止的证据。

ProviderRouterTest验证prefix分组/边界，调用ToolExecutor加FakeHandler验证LinuxProvider不可用时拒绝、reset后passthrough。TestContext只是ContextWrapper(null)，仅适合处理器不解引用Context的情况。测试@Before只ProviderRouter.reset；头注释声称两个singleton都reset与实际不符。ToolRegistry仍有其它注册内容，但本轮没有确认这会导致具体顺序依赖失败，不作为新产品缺陷。真实 [ToolProvider.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/runtime/ToolProvider.kt:58) reset仅清providers，这不是生产完整清registry接口。

ToolMigrationAliasTest注册真实LinuxFileRead/Write/EditHandler，验证canonical名、旧alias、definition与handler对象相同。没有真正执行文件读写，不能作为guest路径隔离/原子编辑证据。ToolRegistryNormalizationTest使用DummyHandler覆盖exact、wire、alias和去分隔符/case-insensitive名称；验证的是名字匹配，不是这些Android功能或root命令实际成功。

ToolRegistryTest验证名称、MCP可见性、android.app确认级别、linux.shell确认级别、动态工具进入schema与MCP wire名注销。名为“permission gate denies unknown and mcp-low”的测试实际上只assert contains(linux.shell)，并在注释说明真实executor路径另测。不能把这个方法名称当作未知工具执行拒绝的断言；本轮没有重新给已审权限链问题计数，也没有仅凭命名认定权限失败。

ToolSecurityAttackTest调用真实ToolPermissionManager.policyFor/levelFor/isDirectlyAllowed，覆盖Root local-only、路径/分隔符构造、wildcard smuggling、随机unknown、malformed和未知caller。[ToolPermissionManager.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/runtime/ToolPermissionManager.kt:197) policy使用精确键和分组wildcard，不做registry规范化；[ToolRegistry.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/runtime/ToolRegistry.kt:91) executor先canonicalName再用canonical做权限检查。因此该测试里原始拼写不命中policy，不能自动推导同样拼写在executor入口也是unknown；例如大小写root名称规范化后仍由local-only拒绝远程。没有把这两层差异误报为Root权限绕过。

ToolResultBudgetTest调用真实bounded，覆盖预算内同对象、边界不spill、超界spill预览、spill失败/未spill时prune，保留success/toolTitle。spill是lambda假返回，未落盘、未校验预览路径可重读。[ToolRegistry.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/runtime/ToolRegistry.kt:175) dispatch实际调用bounded。pruner8192字符阈值与50 KiB字节阈值不是同单位，但合法UTF-16字符串在8192 code units以内的UTF-8体积小于50 KiB，不能仅凭单位不同断言fallback会把超50 KiB原文本返回。现有测试内容为ASCII，未运行Unicode案例，也不声称该纯测试验证完整ContextOffload恢复。

ToolCallValidatorTest调用生产validator，覆盖scalar/coercion、enum、array items、nested required、未知类型拒绝、256 KiB预算、递归深度、消息回显限制；明确将null/缺失required交给preflight，并允许额外字段。[ChatViewModel.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:347) 生产在工具preflight校验链调用validator。测试没有执行真实tool helper，允许字符串化数字/数组不等于所有helper都会正确转换；这些必须结合具体参数读取闭环，不从validator测试单独推断。

## 固定OpenMinis对照

只查 `/tmp/openminis-review-20261004` 固定HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c` 的Git tree，未fetch。没有本轮查询的tools/runtime/ToolRegistry、ToolProvider、ToolCallValidator、ToolResultBudget同路径模块或ProviderRouterTest/ToolSecurityAttackTest/SubAgentToolExposureTest文件。这个结论仅是对应模块不存在；不推导上游完全没有任何工具校验/权限测试，也不将本地新测试存在等同整体架构更可靠。

测试内出现旧alias是迁移兼容断言，未发现本轮新增minisd/PRoot执行入口。不得把注册/路由FakeHandler成功解释成旧runtime已被启用。

## 新增全文覆盖

| 文件 | 行数 |
|---|---:|
| [AppStatePolicyTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/tools/AppStatePolicyTest.kt:1) | 58 |
| [SubAgentToolExposureTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/tools/SubAgentToolExposureTest.kt:1) | 47 |
| [ToolTimeoutPolicyTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/tools/ToolTimeoutPolicyTest.kt:1) | 45 |
| [ProviderRouterTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/tools/runtime/ProviderRouterTest.kt:1) | 101 |
| [TestContext.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/tools/runtime/TestContext.kt:1) | 13 |
| [ToolCallValidatorTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/tools/runtime/ToolCallValidatorTest.kt:1) | 255 |
| [ToolMigrationAliasTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/tools/runtime/ToolMigrationAliasTest.kt:1) | 55 |
| [ToolRegistryNormalizationTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/tools/runtime/ToolRegistryNormalizationTest.kt:1) | 123 |
| [ToolRegistryTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/tools/runtime/ToolRegistryTest.kt:1) | 142 |
| [ToolResultBudgetTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/tools/runtime/ToolResultBudgetTest.kt:1) | 92 |
| [ToolSecurityAttackTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/tools/runtime/ToolSecurityAttackTest.kt:1) | 133 |

前三组文件以完整cat读取，均无截断；validator测试完整255行。相关生产文件此前已有全文覆盖，本轮只核对调用片段，不重复增加计数。一次混合生产/旧报告搜索输出截断，后续相关policy、router、registry入口与pruner单独读取；截断查询不作为任何文件全文证明。最初按ProviderRouter/ToolExecutor独立文件路径查询未找到，后用rg定位到ToolProvider/ToolRegistry的内嵌对象，不从错误路径推导组件缺失。

累计全文 **1354 / 1546文件、262355行**；partial13、not_registered179、excluded124，全仓通读仍未完成。完成核验全部1670 tracked路径hash/bytes/正数文本行数、两仓HEAD、tracked/staged空diff、此前136份报告哈希、新报告绝对链接/行号与全文批次总计。只新增本报告并更新覆盖台账。
