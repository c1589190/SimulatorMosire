# unit-ext T10 关账报告（端到端判据 + 挂账 a~l 收口）

> 分支 `ue/t10`，基线 `349887d`（T9 合并后的 HEAD）。实现提交见 §八。
> ★ 本报告里的钟点一律由 `git log` 从提交哈希解析（可核验）；正文不写"我读的钟"。

## 〇、一句话

把 T9 声明但未修的**三处静默面**收口（GUI 写白名单 / `PAYLOAD_HINTS` / `Shell` 的 `participant=1`），
把挂账 **a~l 逐项处置**（修 6 项 / 明确裁定 4 项 / 范围声明 1 项 / 本报告 1 项），
新增 **1 个端到端类（6 条，判据 #1/#5/#6/#7/#8/#12/#15 逐值）** + **2 个针对性护栏**（T10-a 溢出 / T10-d 悬空引用）+
**2 条派生式断言**（T10-i 工作台写面 / T10-j 提示表完整性）。
`./mvnw clean verify` **第 1 次尝试即绿**（rc=0，47.5s），**10 个变异体全杀、0 存活**。

## 一、挂账 a~l 逐项处置

> ★ 台账（HANDOFF §四）的编号是 **a~j + l**，**没有 k**（跳号）；下表逐项对号，k 记"无对应条目"。

| 编号 | 处置 | 证据 / 理由 |
|---|---|---|
| **T10-a**（`RelativeOffset` 静默溢出） | ✅ **修** | `appliedTo` 改 `Math.addExact`，溢出抛可读 `IllegalArgumentException`（**唯一叠加点**，handler 与 codec 路径同受保护）。P2 的"无范围约束"指偏移本身不设界，**不**等于"叠加可溢出 int"。判据 `RelativeOffsetTest.appliedToRejectsIntOverflowInsteadOfSilentlyWrapping`（双向：溢出拒 + 不溢出的大值仍过）；变异 **t10m1 KILLED** |
| **T10-b**（13 处 token-only 载荷断言） | ✅ **修** | `UnitCommandHandlersTest.everyHandlerRejectsMalformedPayload` 里 **11 处** `contains("token")` 收紧为**完整载荷层消息前缀**（`字段 <名> 必须是…`），使"载荷层真的响了"成为可判别命题（原 token 断言在域层兜底消息也含该 token 时**判不出是哪一层**）。判据 = 该用例本身；变异 **t10r-t4m9 KILLED**（红点落在 `everyHandlerRejectsMalformedPayload:677`） |
| **T10-c**（`new UnitState(units)` 1 参兼容构造器是否删） | ⚖️ **裁定：保留** | ★ **删除会毁掉两个变异体**：`t7m4`（`UnitTimeParticipant` 的"改回 1 参构造器"）与 T5 site-5 的 mutant 都以**该构造器存在**为前提，删掉后变异体**无法编译**（按纪律 = VOID），即"1 参构造器是静默丢链的靶子"这条**护栏失去可重跑性**。现状的护栏是**变异自证过的**：T5-U2 六处站点各配守卫（t5m04/05/06/08/09），T7 又加 `rejoinTickKeepsCommandChains`（t7m4 同时打红它）。⇒ 保留并**以既有守卫为判据**；"删构造器换编译期报错"的收益 < 毁掉两轮已关账变异证据的代价。**这不是遗漏，是权衡后的裁定** |
| **T10-d**（`disband` 后悬空 `rejoinTarget`） | ✅ **修（判据侧）+ 裁定接受该状态** | 运行期口径本已安全（`effectivePosition` 对不存在 id 返空 ⇒ `rejoinRoute` 走公共空出口）；**补上它一直缺的判据**：新 `UnitRejoinDanglingTest.danglingRejoinTargetAfterDisbandIsSafeAndObservable`（两半都钉：引用**不清** + 不回归不抛）。**不在 `disband` 里清引用**——那会改 T3/T4 已关账 op 族、牵动约 40 个既有变异轮，收益低于代价。变异 **t10m4 KILLED** |
| **T10-e**（回归 vs 在途普通路线的交互） | ⚖️ **裁定：未定策略（不记为"已实现"）** | spec §二.2 / P8 只说"每 tick 重规划"，**没说与在途路线的关系**；现状是**回归行程替换在途行程**（`UnitTimeParticipant` 第二趟写新 `Movement`）。凭空定策略 = 发明需求 ⇒ 如实记为**未定策略**，**不写测试去钉**（钉了会误导成"已裁决"）。**依据**：T7-G5 原判 + spec §九"仍无上游依据就记未定" |
| **T10-f**（`UnitPayloads` 类注"十六个"） | ✅ **修** | `十六个 → 二十个`（T8 后实为 20）。同族：`UnitCommandHandlersTest` 方法注"十六个 handler"一并改为"二十个"。纯注释、零行为 |
| **T10-g**（spec §八 20 条实测值 + 变异汇总 + 未能核实 + 报告） | ✅ **本报告 §三/§四/§六** | 逐条给数字（非"通过/不通过"）；#17 记一处 **spec 陈旧**（见 §三） |
| **T10-h**（`Shell` 日志硬编码 `participant=1`） | ✅ **修** | participant 改由**清单**注册、条数取 `participants.size()`；日志改 `participant={}`。★ **诚实披露：无测试断言该日志**（捕获 SLF4J 成本高），故**不是变异可杀的护栏**，而是"**按构造正确**"的修复——验证方式 = `git grep 'participant=1'` 只剩注释里的历史说明、生产格式串已是 `participant={}`。残留：将来若有人再写死，**无测试会红**（接受，因值已平凡可导） |
| **T10-i**（GUI 写白名单静默面） | ⚖️ **裁定 + 派生式断言** | ★ **只读取证结论**：工作台唯一写路径是 `app.writeCommand`（`app.js`），`map.js` 的 `unit.*` 写**恰是白名单那 6 条**；`unit.RenameUnit`/`unit.PlaceAt` 只由**调试页** `unit.js` 经 `SimosApi.submitCommand` **直发**（绕开白名单）⇒ **有意不列，不是漏**。T9 新注册的 12 条在工作台**无 UI 入口**（unit-ext 是 MCP/agent-only，见 T10-l）⇒ 同样**有意不列**。**白名单 = 工作台实际写面，不是后端注册面**。⇒ **不改白名单**，改为在 `modes.test.cjs` 加**派生式静态扫描断言** `workbench-write-calls-are-all-whitelisted`（扫 `webui/*.js` 里全部 `writeCommand("<type>"` 字面量，逐条要求至少一个模式放行）⇒ 将来新增工作台写命令**忘了加白名单就红**；变异 **t10m3 KILLED**（删 `unit.PlanRoute` 白名单项 ⇒ 前端门禁 `fail=2`） |
| **T10-j**（`PAYLOAD_HINTS` 只有 8 条） | ✅ **修** | 补全 **30 条**（原 8 + unit 12 + map 6 + sd 4），并把 `getOrDefault(type, "")` 的**静默兜底**改为**构造期强制**：任何已注册 type 缺提示 ⇒ `CatalogTool` 构造器抛、点名该 type（"缺项处理三级谱"的 ① 抛）。判据 = 新增 `SimosToolsTest.catalogRejectsACommandTypeWithoutAPayloadHint`（故意违规用例）；变异 **t10m2 KILLED**（删一条提示 ⇒ 构造期拒绝 + `Shell.start` 失败） |
| **T10-l**（unit-ext 在 GUI/API 面不可见） | 📌 **范围声明（非缺口）** | 依 spec 对前端/API 暴露**零规定** ⇒ 不判为缺口。**合并 T10-i 结论**：**T9+T10 做完后，unit-ext 是 MCP/agent-only 的特性**——GUI 既看不到 `UnitStatus`/`rejoinTarget`/`commandChains`，也发不出新命令（白名单 fail-closed）。**交付时不得把"全绿"读成"界面上能用"** |

## 二、三处静默面（T9 只声明未修）收口小结

| 静默面 | 修前 | 修后 | 可杀它的变异体 |
|---|---|---|---|
| GUI 写白名单（`modes.js`） | 逐条精确匹配 + fail-closed，新增写命令**静默拒**且无测试红 | 白名单语义**裁定为"工作台写面"** + `workbench-write-calls-are-all-whitelisted` 静态扫描守卫 | t10m3 KILLED |
| `CatalogTool.PAYLOAD_HINTS` | `getOrDefault(type, "")` 缺项**静默填空串**（T9 后 22/30 空） | 30 条补全 + 构造期**拒绝**缺项 | t10m2 KILLED |
| `Shell` 日志 `participant=1` | 硬编码字面量，将来加第二个 participant **静默说谎** | 由 `participants.size()` 数出来 | 无（见 T10-h 披露） |

★ **通则（T9 归纳、T10 落实）**：`modes.js writes` / `PAYLOAD_HINTS` / `McpCoverageTest` expected 表 /
`gate-contract` 的 `REQUIRED_FILES` 都属"**声明式清单不随注册面自动延伸**"。
本轮对 `PAYLOAD_HINTS` 与写白名单各配了**源头对齐的断言**（前者构造期强制、后者静态扫描）；
`McpCoverageTest`/`SimosToolsTest` 的 expected 表仍靠 T9 的强判据（`catalog == 全仓 30 个实现的 type() 集合`）兜底。

## 三、spec §八 20 条判据 —— 逐条实测值

> 数值一律取自**测试源码里的断言字面量**或**本轮 e2e 的实测**；全量门禁绿 ⇒ 下列用例全部通过。

| # | 判据 | 实测值 / 落点 |
|---|---|---|
| 1 | 多属 | **2 条链**：e2e `oneUnitCanBelongToTwoCommandChains` ⇒ `commandChains.size()==2`、`c-1`/`c-2` 的 members 都含 `u-1`；codec 往返 `UnitCodecTest` 含 2 链快照 |
| 2 | Formation 无环 | `UnitStateTest.cycleAcrossUnitsThrowsAtConstruction` ⇒ 构造期抛、消息含"**成环**"；`legalReparentAcrossTimeIsNotACycle` 反证合法跨时改编不误报 |
| 3 | attached ⊕ offset | `UnitStateTest.attachedChildWithoutPositionAddsOffsetToParentPosition` ⇒ `H22 ⊕ (2,−1)`；`UnitCommandHandlersTest.setFormationOffsetAppliesAndClears` ⇒ 父位 `(1,1)` ⊕ `(1,0)` = **`(2,1)`**、清偏移回 `H11` |
| 4 | detached 不回退 | `UnitStateTest.detachedChildWithoutPositionDoesNotFallBackToParent` ⇒ `effectivePosition` **空**、父自身仍 `H22` |
| 5 | 同格合体 | e2e `mergeFormationRequiresTheSameHex` ⇒ 不同格（u-2@(1,2) vs u-1@(1,1)）**Rejected** 且 head 不动；同格（u-3@(1,1)）**Committed**、`parent==u-1`、`attached==true` |
| 6 | 子树迁移整体性 | e2e `reparentSubtreeAppendsAParentSegmentToEveryDescendant` ⇒ root `u-3` 与后代 `u-4` 的 parent 段**各 2 段**、`u-3.parent==u-1`、`u-4.parent==u-3` |
| 7 | 稀疏路点 | e2e `planSparseRouteExpandsNonAdjacentWaypointsIntoAPerHexPath` ⇒ waypoints `[H11,H13]`（非相邻）展开成 path **`[H11,H12,H13]`（长 3）** |
| 8 | 回归随动 | e2e `rejoinEndpointFollowsTheTargetsCurrentPosition` ⇒ 目标移到 `(1,3)` 后回归行程终点 = **`H13`**（非旧格 `H12`）、`position` 不瞬移（仍 `H11`） |
| 9 | 三态速度 | `UnitStatusTest.factorsAreTheExactPerMilleValues` ⇒ **1000 / 500 / 250**；`theThreeFactorsAreDistinct…` |
| 10 | 三态正交 | `UnitStatus` 与 `MovementStatus` 是**两个独立枚举**（`UnitStatusTest` 只判三态定义）；★ **无专门"不混"用例**——如实记为结构性成立、判据弱（见 §六） |
| 11 | 在途不回溯 | `UnitMovesTest.speedChangeAfterDepartureDoesNotChangeTheResult` + `mobilityChangeAfterDepartureDoesNotChangeTheResult` ⇒ 出发后改速度/机动性，`evaluate` 结果不变 |
| 12 | 战损 delta | e2e `casualtiesSubtractIncrementallyAndRollBackToThePreBattleValue` ⇒ `100 + (−30) = `**`70`**（非 30、非覆写） |
| 13 | 战损上界 | `UnitOperationsTest.applyCasualtiesRejectsPositiveDeltasAndOutOfRangeAmounts` ⇒ `−101` **拒**（"超出当前值"）、`−100` ⇒ **0**；装备逐项 `−51` 拒 |
| 14 | 装备双轨 | e2e ⇒ `{步枪: 40, 炮: 4}`（只扣提及键）；`UnitOperationsTest.applyCasualtiesLeavesUnmentionedEquipmentKeysUntouched` |
| 15 | 时间线恢复 | e2e ⇒ 回退 `(main,1)` 读到 **member 100 / 步枪 50 / 炮 4**；`UnitCasualtyRevisionTest` 双向（战前/战后各一 revision） |
| 16 | 事件无明文 | `UnitCasualtyRevisionTest.receivedEventCarriesOnlyTheDigestNotTheDeltaPlaintext` ⇒ 载荷含 `"payloadDigest":"sha256:[0-9a-f]{32}"`、**不含** delta 明文 |
| 17 | 往返 | `UnitRoundTripTest` 绿（新组件全进 `UnitChangeSet`）；`ArchitectureGuardsTest` 的 `ChangeSet` 实现者 **`containsExactly` 5 个**（World/Map/Sd/Social/Unit）。★★ **spec §八 #17 写"计数仍 4"是陈旧值**——SDSimos A3 加了 `SdChangeSet` 成为第 5 个，**与 unit-ext 无关**，如实更正 |
| 18 | 命令注册与形状 | `SimosToolsTest.catalogCoversEveryCommandHandlerImplementation` ⇒ **30**（catalog == 全仓 `*Handler.java` 的 `type()` 集合）；`McpCoverageTest` ⇒ 30 条逐类 `committed`（`main@2..31`）；`type()` 形状 `<namespace>.<Command>` 构造期校验；`unit` namespace **恰一 participant**（`TimeAdvance` 对重复 namespace 构造期抛） |
| 19 | codec 键往返 | `UnitCodecTest` ⇒ 链键得还是 `CommandChainId`（`isInstanceOf(CommandChainId.class)`）、`containsOnlyKeys(c-1,c-2)`；T5 的 m3（键值改坏）**KILLED**；★ 删键反序列化器**不红**（单 String record 上非承重，T5-L3 等价变异体） |
| 20 | sd 边界 | **out-of-scope**（R7）：sd 的 `SdCommandDrain` 未实现、App 无 drain；unit 侧只保证"不认识 sd、无跨模块直写"——**由 `bannedDependencies` 在构建期把守**（铁律 3 的结构化），不在本计划 |

## 四、变异汇总（10 轮 / 10 杀 / 0 存活）

装置：`t10-evidence/mutants/mut-round.sh`（照 T9 的十道门禁；参数化 PL/SEL/runner；白名单 = manifest 里登记的全部 target；
逐字节 `cp` 还原；日志自指四个 md5；⑩ 按**片段 + 方向**自证，含 `count` 方向）。
**全部在 `spotless:apply` 之后的最终字节上重跑**（先跑一次 apply 前，apply 后重新生成变异体 + 基线再全跑，两遍结果一致）。

| 轮 | 变异 | 靶文件 | 方向 | 结果 | 红点（被保护断言） |
|---|---|---|---|---|---|
| t10m1 | `appliedTo` 退回裸 `+` | `RelativeOffset.java` | delete | **KILLED** rc=1 | `RelativeOffsetTest.appliedToRejectsIntOverflowInsteadOfSilentlyWrapping:38` |
| t10m2 | 删一条 `PAYLOAD_HINTS` | `CatalogTool.java` | delete | **KILLED** rc=1 | `SimosToolsTest` 全 14 条 `startShell` 报"未登记载荷提示: [unit.ApplyCasualties]" |
| t10m3 | 白名单删 `unit.PlanRoute` | `modes.js` | delete | **KILLED** rc=1 | 前端门禁 `fail=2`，含 `not ok 39 - workbench-write-calls-are-all-whitelisted` |
| t10m4 | `disband` 顺手清悬空 `rejoinTarget` | `UnitOperations.java` | revert | **KILLED** rc=1 | `UnitRejoinDanglingTest.danglingRejoinTargetAfterDisbandIsSafeAndObservable:49` |
| t10m5 | 删 `mergeFormation` 同格校验 | `UnitOperations.java` | delete | **KILLED** rc=1 | `UnitExtensionEndToEndTest.mergeFormationRequiresTheSameHex:150`（判据 #5） |
| t10m6 | `reparentSubtree` 只改 root | `UnitOperations.java` | revert | **KILLED** rc=1 | `UnitExtensionEndToEndTest.reparentSubtreeAppendsAParentSegmentToEveryDescendant:177`（判据 #6） |
| t10r-t9m1 | 删 `SetStatus` 注册 | `Shell.java` | delete | **KILLED** rc=1 | `McpCoverageTest:249` + `SimosToolsTest:220/196`（**裁定 42 重派生 + 重跑**） |
| t10r-t9m2 | 注册移出 `List.of` | `Shell.java` | revert | **KILLED** rc=1 | 同上三条（"注册了却没进 catalog"唯一真实路径） |
| t10r-t9m3 | 注入恒不可通行 `MovementCost` | `Shell.java` | revert | **KILLED** rc=1 | `McpCoverageTest:262`（PlanSparseRoute 被拒） |
| t10r-t4m9 | `requireTextArray` 删形状校验 | `UnitPayloads.java` | count(2→1) | **KILLED** rc=1 | `UnitCommandHandlersTest.everyHandlerRejectsMalformedPayload:677`（T10-b 收紧后的断言） |

- 每轮 `compile_errors=0`、`restored==orig yes`、`report_mtime` 落本轮内（t10m3 为前端轮，以 `[frontend-gate]` 行为准）。
- ★ **裁定 42 的兑现方式**：改 `Shell.java`（T10-h）与 `UnitPayloads.java`（T10-f）⇒ **不从旧证据抄结论**，而是
  **从最终字节重新派生** t9m1/m2/m3 与 t4m9 的语义改动并重跑（旧 T9/T4 变异体是**旧快照**：t4m9 还含 T5 前的 `optionalTextArray` 缺失，
  整份套用会误删 T5 特性 ⇒ 只重放其**语义改动**）。t9m1/m2/m3 与 T9 原轮**红点逐条相同**。
- 无存活项；`RelativeOffset`/`CatalogTool`/`modes.js` 此前**无既有变异轮**（裁定 42 的"重跑"空满足）。

## 五、逐模块对差（★ 从原始日志重算，非引用文档）

- 命令：`./mvnw clean verify`；**尝试次数 = 1**（未摘后台、未被杀）。
- 证据：`t10-evidence/logs/clean-verify.attempt1.log` + `verify-rc.txt`（**rc=0**）。
- 总数（`grep -E '^\[INFO\] Tests run: …' <log> | … | paste -sd+ | bc` 实测）：**1199**
  = `util 170 / map 362 / social 45 / unit 259 / core 177 / sd 62 / app 124`。
- **8/8 模块 SUCCESS**（父 POM + 7 模块）；`BugInstance size is 0` **×7**；`[ERROR]` **0 行**；
  前端 `[frontend-gate] OK tests=90 pass=90 fail=0`。
- **delta 干净**（T9 关账 1190 = `170/362/45/257/177/62/117`）：
  - **unit 257 → 259（+2）** = `UnitRejoinDanglingTest`（1）+ `RelativeOffsetTest` 新增溢出用例（1）；
  - **app 117 → 124（+7）** = `UnitExtensionEndToEndTest`（6）+ `SimosToolsTest.catalogRejectsACommandTypeWithoutAPayloadHint`（1）；
  - `util/map/social/core/sd` **逐值不变**；前端 88 → 90（`modes.test.cjs` 新增 2 条派生式断言，`run-gate.cjs`/`gate-contract.test.cjs` 两处下界同改）。

## 六、★ 我未能核实的

1. **`agentlib-mosire` 是外部依赖**（`0.1.0-SNAPSHOT`，不在本仓）⇒ 门链、MCP 传输、工具框架的**内部读不到**。
   ⇒ **"新命令是否与既有命令走完全相同的审批/传输路径"我未能核实**（**不是**"已确认无洞"）。本任务能证的只是：
   经真 `Shell`/`CommandBus`/真 store 提交、`replay` 回读；T9 能证的只是经真 MCP 客户端 + `APPROVE_ONCE` 后 `committed`。
2. **`Shell` 的 `participant={}` 日志无测试**（T10-h）：修法是"按构造正确"，**没有能杀掉"再写死"的变异体**——如实披露（§一 T10-h）。
3. **判据 #10（三态正交）判据弱**：`UnitStatus` 与 `MovementStatus` 是两个枚举，但**没有一条专门断言"不混"**的用例；
   现有证据是"类型不同 + 各自用例绿"，**不是**一条会因"混用"而红的断言。
4. **真档未上**：全部证据在**合成小图**（3 格走廊 + 4 单位）上；19441 格真档上的新命令与回归重规划**未验**。
5. **回归重规划的 A\* 代价**：`UnitTimeParticipant` 每 tick 对每个在途回归单位跑 A\*，大图代价**未测**（spec §九 / R5 仍开口）。
6. **`PlanSparseRoute` 的非相邻段**：e2e 只在 3 格走廊上展开一段；T6 单测覆盖更一般的展开，**本轮未复验**。
7. **T10-e 的"未定策略"**：现状（回归替换在途）**未写成断言**，故**无判据**——是**有意**的（钉了会误导成已裁决）。
8. **前端门禁仍不进 surefire 合计**：`tests=90` 由 `exec-maven-plugin` 独立跑，**不并进** 1199；下界是真护栏的证据是它**红过**（t10m3），不是数字变大。
9. **`PAYLOAD_HINTS` 的文本正确性**：30 条提示的**字段名**取自各 handler 的解析调用与 `MINIMAL_PAYLOADS`，但**可选性标注**（`?`）是人工判读，未逐条对拍每个 handler 的 `optional*`/`require*`。
10. **跨 JVM 字节稳定 / 旧档兼容**：未在本轮新增验证（T1 既有假设，R9/R10 延续）。

## 七、带裁定的遗留（转下一里程碑）

1. **T10-e**：回归与在途普通路线的交互 = **未定策略**；要定须先有上游依据（sd 里程碑可能给出）。
2. **T10-c**：1 参 `UnitState(Map)` 构造器**保留**；若将来要删，须**同时**重建 t7m4 / T5-site5 两个变异体（否则 VOID）。
3. **T10-l**：**unit-ext 是 MCP/agent-only**；GUI 既不显示新状态、也不发新命令。若要在界面可用，须先有 spec 依据 + 前端 UI + 白名单。
4. **判据 #17 的 spec 陈旧**：spec §八 #17 的"计数仍 4"应更正为 **5**（SdChangeSet）；本报告已记，**不改 spec**（冲突写"执行期取代说明"）。
5. **T10-h 的残留**：`Shell` 日志无断言；将来加第二个 participant 时**没有测试会红**（值已平凡可导，接受）。
6. **`CatalogTool` 构造期强制**的副作用：将来新增 handler 而忘加提示 ⇒ **`Shell` 起不来**（fail-fast）。这是**有意**（缺项不静默），但代价是"一条漏登记 = 整个 app 不启动"。
7. **T10-b 的范围**：只收紧了 `everyHandlerRejectsMalformedPayload` 内**载荷层与域层都会提到同一字段名**的那些断言；其余 token 断言若本就无歧义，未强改。

## 八、提交

- 分支 `ue/t10`，基线 `349887d`。
- **实现提交：`09e3c26`**（本报告回填该 SHA 的提交紧随其后）。
