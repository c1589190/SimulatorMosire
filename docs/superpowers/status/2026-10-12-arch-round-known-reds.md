# 架构调整期「已知红点台账」（2026-10-12 起）

> **为什么要有这本**：用户 2026-10-12 重申 §三.0「**测试环节放到最后**，反正这是一轮总的架构调整」
> ⇒ 批次之间**不再插测试批**，每批只跑 `compile` + `spotless:check` + `spotbugs:check`（静态门禁）。
> 但架构大改会**成批**让既有测试编译断/断言红 ⇒ **必须逐批记账**，否则最后的统一测试阶段**无法归因**
> （分不清"老架构老测试该删"与"新代码弄坏了"）。
>
> **判据**：**护栏当场修**（`GovRoundTripTest` / `ArmyRecordCombatRevisionTest` 这类防静默漂移的）；
> **旧测试攒台账**（钉旧机制的、夹具喂错面的）。

## 已结清（不算红点）

| 批 | 提交 | 内容 | 处置 |
|---|---|---|---|
| 1.7 | `f3458cea` | 9 个测试文件因 `GmOnlyCommand` 删除而**编译断** + 3 个用例因 `sd.` 前缀规则删除而**红** | ✅ **已在同批结清**（2026-10-12）：9 处改 `isNotInstanceOf(DmUsableCommand.class)`；3 个用例迁移到新契约（夹具改喂派生面、自指保护改端到端验证），`DM_EXPECTED` 一字未放宽 |

## 待最后测试阶段处理

### 批次 2（hex 属性表，`HexCell` 加 `areas` 组件）—— 预期红（断言级，非编译级）
`test-compile` 实测 rc=0（0 ERROR）⇒ 下面全是**断言**红，不是编译断。

| # | file:line | 钉的旧形状 | 处置方向 |
|---|---|---|---|
| 1 | `simos-map/src/test/.../HexCellTest.java:49-52` | `hasSize(1)` + `containsExactly("height")` | 改成 `[height, areas]`（保留 `doesNotContain("terrain","edgeTags","riverMask")` 的判别力） |
| 2 | `simos-map/src/test/.../RegressionGuardsTest.java:168-172`（L2） | `containsExactly("height")` | 同上（该条本意"无 terrain/无连通性"仍成立） |
| 3 | `simos-app/src/test/.../tools/SimosToolsTest.java:985` | `hasSize(142)` | ⇒ **143**（新增 `map.SetHexArea`） |
| 4 | 同上 `:1026` | `hasSize(166)` | ⇒ **167**（GM 桶读+写） |
| 5 | 同上 `:1143` | `hasSize(70)` | ⇒ **71**（窄写类扫描） |
| 6 | 同上 `:1850` | `hasSize(176)` | ⇒ **177**（`*Tool.java` NAME 扫描） |
| 7 | 同上 `:1154` | `NARROW_WRITE_NAMES` 名单 | 补 `map.SetHexArea`（68⇒69） |
| 8 | `simos-app/src/test/.../McpCoverageTest.java:147` | `EXPECTED_COMMAND_TYPES`(142) | ⇒ **143** |
| 9 | 同上 `:577` | `MINIMAL_PAYLOADS` | 补 `map.SetHexArea` 的最小可成功载荷（该表要求每个 catalog type 都有能**真提交成功**的载荷） |

**本批新增能力尚无测试**（"涂地形不抹编辑值" / "未赋值读地形默认" / "增量基数是读出值" / "GM-only 不动 DM 面"）⇒ 收尾期按规划 §4 批次 2 的五条负向判据补。

### 未核项（如实）
`AdjudicateTickToolTest:931 hasSize(97)` 本批未核（97 = 实现 `CommandTargets` 的 handler 数；新命令实现了它 ⇒ **预期 98**，收尾期实测）。

## 格式门禁修复（2026-10-12，控制方执行）

批次 1 / 1.6a 的**收工门禁只有 `compile`**（未跑 spotless）⇒ 基线积累了 **65 个格式违规文件**。
控制方 `spotless:apply` 全仓一次 ⇒ `spotless:check` **17/17 rc=0**。
**零逻辑证明**：去掉注释后 **65 个文件的代码 token 逐字节相同**（Python 注释剥离器，处理字符串/字符字面量）；
差异全在 javadoc 折行位置与类声明折行。
★ 教训（已入规划 §11）：**格式门禁属"提交前必跑"**；`spotless` 第一处 FAILURE 后其余模块全 SKIPPED ⇒ 修完必须重跑直到全绿。

### 批次 3a（`ga` 首次持状态：搬 `decisionPackets`+`mergedEffectPlans` 两组件，4 条命令 `sd.*`→`ga.*`）

**A. 编译断（`test-compile` 实测 56 报错行 / 4 文件，全在 `simos-sd/src/test`）—— 属性：旧测试直接调用被移除的组件**
| 文件 | 报错行 | 形态 |
|---|---|---|
| `sd/change/SdRoundTripTest.java` | 16 | `cs.decisionPackets()` / `cs.mergedEffectPlans()` / `rebuilt.*` / `:60 hasSize(14)` |
| `sd/codec/SdCodecTest.java` | 22 | `withDecisionPackets(...)` / `withMergedEffectPlans(...)` / 缺键⇒空表用例 |
| `sd/state/SdStateInvariantTest.java` | 8 | `after.decisionPackets()` / 键不一致用例 |
| `sd/testing/SdFixtures.java` | 10 | `:118 new SdState(14 参)` / `mutated(base,"decisionPackets"\|"mergedEffectPlans")` |
**处置**：§一.10「老架构老测试、新架构新测试」⇒ 收尾期**删/迁移**（这些组件已不在 sd）。

**B. 编译断（`simos-app/src/test`，被 A 挡住未走到，独立于 A 必然断）**
`app/decision/RealLlmGovScenarioTest.java:557` · `app/decision/RunDecisionEndToEndTest.java:424`（均 `SdState.decisionPackets()`）。

**C. 断言红（类型串 / 清单）**
`app/McpCoverageTest.java:226,363,494`（含 4 条旧串 + "not found" 样本表）· `app/tools/SimosToolsTest.java:626` ·
`app/tools/write/AdjudicateTickToolTest.java:825,829,832`（`samples` 的 key = 旧类型串；★ `:549 hasSize(66)` **不受影响**）·
`sd/change/SdRoundTripTest.java:60 hasSize(14)` → 须 **12**（方法名同步）。

**D. 模块集断言红（新增空 `ga` 片）**
`app/world/CorridorWorldTest.java:47`（`containsExactlyInAnyOrder(...)` 须 +`"ga"`）· `app/world/T12InfoRedlineTest.java:76` 同。

**E. ★ 装配红（未实测，**收尾期主要工作量**）**
**54 个 app 测试文件手工列 codec 表**（`List.of(new MapCodec(), …, new SdCodec())`）并手工搭世界，**没有 `GaCodec` 也没有 `ga` 片**
⇒ 凡真正驱动"决策包流程 / 决策回合结算"的用例会在运行期红（`ToolSupport.gaState` 抛"状态里没有 ga 模块切片"，或 `ga.*` 命令找不到 codec）。
候选集（代理给的 14 个）：`DecisionResultsShellEndToEndTest` · `DecisionMakerScopeEndToEndTest` · `DecisionAgentRunnerTest` ·
`RealLlmUnitDecisionLoopTest` · `ResetConversationEndToEndTest` · `SdDecisionResultsApiTest` · `SdRunDecisionApiTest` ·
`DecisionDocsToolTest` · `DecisionResultsToolTest` · `StartDecisionEndToEndTest` · `AdjudicationEndToEndTest` ·
`AdjudicatorRunnerTest` · `SdCommandDrainTest` · `DecisionChannelTest`。
**补法**（代理给）：夹具加 `new GaSnapshot(...)` / `new GaCodec()` 两行。

### ★ 流程修正（2026-10-12，自批次 3a 起生效）
`test-compile` 从本批起**注定红**（sd 测试的编译断要到收尾期才清）⇒ 而 **`-DskipTests` 不跳过 test-compile**，
`package` 会卡在 sd 上、**永远到不了 spotbugs 门禁**。
⇒ **自本批起，每批的 spotbugs 门禁一律用 `-DskipTests -Dmaven.test.skip=true package spotbugs:check`**
（`maven.test.skip=true` 连测试编译一起跳）。这是**架构调整期的既定代价**（§12.2），不是放松。

### 批次 3b-1（`directives`+`verdicts` 搬 ga；3 条命令 `sd.*`→`ga.*`）

**A. 新增编译断（23 文件；相对 3a 台账再增）**
- **sd 侧 10**：`SdRoundTripTest`（`:60 hasSize(14)` 早红 + 新增 `cs.directives()/verdicts()`）· `SdResolverTest`（`new SdResolver()`）·
  `spi/{IssueDirectiveHandlerTest, SubmitVerdictHandlerTest, RunDecisionHandlerTest, StartDecisionHandlerTest,
  SetDiplomaticRelationHandlerTest, RecordDiplomaticEventHandlerTest, ResetDecisionMakerConversationHandlerTest}` ·
  `SdStateInvariantTest` · **`SdFixtures`（12 参构造器 + `withDirectives/withVerdicts` + `.directives()/.verdicts()` —— sd 侧多数断的共同源头）**
- **app 侧 13**：`SdVerdictFreezeEndToEndTest` · `decision/{RealLlmGovScenarioTest, RealLlmUnitDecisionLoopTest}` ·
  `gui/{RedactionApiTest, SdDecisionMakerApiTest}` · `llm/AdjudicationEndToEndTest` · `query/RedactingQueryServiceTest` ·
  `sd/{AdjudicatorRunnerTest, StartDecisionEndToEndTest}` · `sd/channel/DecisionChannelTest` ·
  `tools/write/{AdjudicateTickToolTest, RejectDirectiveToolTest}`
**处置**：§一.10 删/迁移（这些 API 已不在 sd）。

**B. 新增断言红（29 文件，旧命令/工具名串；`grep` 可复现 129 处命中）**
`McpCoverageTest` · `McpPortTopologyTest` · `McpServerTest` · `SimosToolsTest` · `ToolSurfaceGapTest` · `CatalogVisibilityTest` ·
`DecisionFaceGolden` · `DecisionMakerScopeEndToEndTest` · `LlmToolNamesTest` · `RunDecisionEndToEndTest` · `DecisionAgentRunnerTest` ·
`SdDirectivesApiTest` · `SdDecisionMakerApiTest` · `SdRunDecisionApiTest` · `GovPolicyIntoEconomyZ6Test` · `SdLoggingTest` ·
`AdjudicateTickToolTest` · `RejectDirectiveToolTest` …（`sd.IssueDirective`/`sd.SubmitVerdict`/`sd.SetDirectiveStatus` 串、
`sd_IssueDirective` 线格式名、`hasSize(66/97/142/143)` 一类计数表）
**处置**：改名类 ⇒ 收尾期**改为新契约**（不是删）。

**C. ★ 过渡性缺口（规划 §16.4 已记，收口时点 = `info` 搬进 ga）**
绕过工具/渠道、**直接 `CoreSimos.submit("ga.IssueDirective", …)`** ⇒ **只落令、不落执行原文**。
受影响：`SdQueryService:156-168,192-201`（`/api/sd/directives` 的 intentInfo、决策人文档面、`ApiViews:5955-5956`）、
`VoidAdjudicationTool:237-244` 的 restore。

**D. javadoc 债（9 处旧名，在 3b-1 写权外）**
economy 6 / gov 1 / unit 1 / util 1 个文件提及旧命令名 —— 留给对应批次或收尾期统一改。
（agent/ga/sd 三模块内描述**当前**命令名的 javadoc 已同步 39 文件；**历史叙述故意保留**，如
`DecisionResultsTool:35` 的事故叙述加了"该事故发生时还叫 sd.*，留痕不改"。）

### 批次 3b-2（`decisionMakers`+`info` 搬 ga；GaState 4→6 / sd 10→8 / **8 条**命令改名）

**新增编译断（相对 3b-1）**
- **sd 侧**：`codec/SdCodecTest` · `spi/{CreateDecisionMakerHandlerTest, SetDecisionMakerAccessHandlerTest,
  SetDecisionMakerProviderHandlerTest, PutInfoHandlerTest}`（handler **搬包** ⇒ import 断）· `channel/ChannelAdmissionTest`（签名换表）·
  `time/SdTimeParticipantTest`（`simulate` → `simulateWorld`）· `state/SdStateInvariantTest` / `change/SdRoundTripTest` /
  `testing/SdFixtures` 的**新红点**（`hasSize(10)`→8、`withDecisionMakers/withInfo` 消失、构造器参数量）
- **app 侧新点名**：`SdPutInfoEndToEndTest` · `SdSetDecisionMakerAccessEndToEndTest` · `SdProviderBindingEndToEndTest` ·
  `ResetConversationEndToEndTest` · `StartDecisionEndToEndTest` · `RunDecisionEndToEndTest` · `SdRegionDeleteGuardEndToEndTest` ·
  `gui/{SdDecisionDocsApiTest, SdDecisionResultsApiTest, SdDecisionScopeApiTest, SdDecisionMakerApiTest, SdDirectivesApiTest, SdRunDecisionApiTest}`
- **unit**：`tools/write/RaiseUnitPlanTest`（`PUT_INFO_TYPE` 期望值）
- ★ **前端 JS（新的一类红，实测）**：`simos-app/src/test/js/modes.test.cjs:99`（**实测红**，钉旧命令名）；
  `write-allowlist.test.cjs` / `decision-mode.test.cjs` / `pending-signal.test.cjs` 需复核

★ **本批关闭的一条**：3b-1 台账 C 节的「§16.4 过渡性缺口」及其 3 处受影响面 —— **收尾期不再需要处理**（`IssueDirectiveHandler`
恢复"一条命令写 directives+info"，companion 机制代码级清零）。
★ **本批恢复的一条不变量**：3b-1 曾把 `Directive.decisionMakerId` 外键降级到命令期；两表回到同一棵树 ⇒ 整条回到构造期，
并有负向用例 `aDirectivePointingAtAMissingDecisionMakerIsRejectedAtConstruction` 把守。

### ★ 流程修正（再一条，自批次 3b-2 起）
前端 JS 门禁（`package` 阶段 `exec` 的 `frontend-unit-tests`）**无 skip 守卫**，而它有 1 条用例钉旧命令名 ⇒
**spotbugs 门禁的完整形态 = `-DskipTests -Dmaven.test.skip=true -Dexec.skip=true package spotbugs:check`**。
（前端 412 条属收尾测试期；`-Dexec.skip=true` 与"测试留最后"口径一致。）
