> **取代者：`docs/superpowers/status/2026-10-13-outstanding-work-survey.md` §4（台账对账：96% 已作废、仍成立 4 条已收录；2026-10-13）** —— 本文仅作历史留痕，**勿据此开工**。

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

---

### 批次 3c（外交三表 + 4 命令 `sd.*`→`gov.*` 搬进 gov；`Affiliation.Nation` 收窄为不透明串；`DecisionMakerId` 落 `util.spi`）

> 提交面：**未提交**（实现代理**不 commit**，§一.5：控制方审后提交）。工作树 107 项（88 M / 14 D / 5 ??）；
> 证据目录 `.superpowers/sdd/2026-10-12-sd-abolition-3c/evidence/`（compile / spotless / spotbugs / gov-roundtrip / faces / literal-scan / reds-scan / mutation）。
> ★ 本节**只列相对 3b-2 台账新增的部分**（§12.2：分不清"老架构老测试该删"与"新代码弄坏了"就无法归因）。

**A. 新增编译断（类型搬家，import 断）**
- **`DecisionMakerId` 从 `io.mosire.simos.sd.id` 搬到 `io.mosire.simos.util.spi`** ⇒ 测试侧 **39 个文件** import 断
  （`grep -rl '^import io.mosire.simos.sd.id.DecisionMakerId;' simos-*/src/test` = 39）。**处置：收尾期把 import 改到 `util.spi` 即可**（类型形状一字未改）。
- **`NationId` / `Nation` / `Diplomatic*` / `NationTag` 从 sd 搬到 `io.mosire.simos.gov.nation`** ⇒ 逐文件命中数见 `evidence/reds-scan.txt` A 节（最重：`app/tools/write/DiplomaticWriteToolsTest` 9 · `sd/testing/SdFixtures` 7 · `sd/codec/SdCodecTest` 5 · `sd/change/SdRoundTripTest` 5 · `app/tools/read/DiplomacyReadToolsTest` 5 …共 50 个文件）。
- **`SdState` 8 → 5**（`nations` / `diplomaticRelations` / `diplomaticEvents` 三组件消失）⇒ `sd/testing/SdFixtures`、`sd/codec/SdCodecTest`、`sd/change/SdRoundTripTest`（`hasSize(8)` 又要再改）、`sd/state/SdStateInvariantTest`、`sd/spi/{SetDiplomaticRelationHandlerTest, RecordDiplomaticEventHandlerTest, CreateNationHandlerTest}`、`app/tools/write/DiplomaticWriteToolsTest`、`app/world/CompactThreeNationsWorld` 等（逐文件计数见 `evidence/reds-scan.txt` B 节）。
- **`GovState` 2 → 5 / `GovChangeSet` 2 → 5**：1 参便利构造**保留** ⇒ `new GovState(Map.of(...))` 的 4 处仍编译（`app/logging/GovLoggingTest:314`、`app/time/Z7RemittanceBridgeTest:180`、`app/time/GovAdminSalaryBudgetZ6Test:725,962`、`app/household/GovernmentServiceLaborBridgeZ6Test:272`）；**2 参形状（offices, remittances）已不存在** ⇒ 若有测试用它需改 5 参（本批只在新护栏里用到）。★ `GovRoundTripTest` 已同批扩到 5 组件（§12.3 例外）。
- **`RegionDeleteGuard` 从 `sd.guard` 搬到 `gov.guard`** ⇒ `sd/guard/RegionDeleteGuardTest` import 断；★ 且它的 `name()` 串改为 `"gov.region-delete"` ⇒ 该测试 `:22 nameIsStable` **断言红**（处置：改新契约）。
- **`Army` 的 deprecated 4 参 `NationId` 兼容构造**整条删除（§一.11；留着 = `sd → gov` 被禁边）⇒ 用它建夹具的测试编译断（约 10 个文件：`app/access/{ScopeFixtures,GovScopeTest,DecisionMakerScopeEndToEndTest}` · `app/decision/{RealLlmUnitDecisionLoopTest,RunDecisionEndToEndTest,DecisionAgentRunnerTest,ResetConversationEndToEndTest}` · `app/tools/write/{RejectDirectiveToolTest,AdjudicateTickToolTest,DiplomaticWriteToolsTest}` · `app/gui/SdRunDecisionApiTest` · `sd/testing/SdFixtures:326` · `sd/model/SdModelTest:221`）⇒ 处置：夹具改传 `Optional.empty()`（canonical 形态）。
- **`Affiliation.Nation` 的载荷由 `NationId` 换成 `String`** ⇒ 测试里 `new Affiliation.Nation(new NationId(...))` 一类构造（36 处）编译断；`SdPayloads` 的线格式随之变化（`{"nationId":{"value":…}}` → `{"nationId":"…"}`）。

**B. 新增断言红（旧命令串 / 工具名，非编译断）**
`app/tools/SimosToolsTest`（11 处）· `app/McpCoverageTest`（10）· `app/tools/write/DiplomaticWriteToolsTest`（5）· `sd/spi/SetDiplomaticRelationHandlerTest`（3）· `sd/spi/RecordDiplomaticEventHandlerTest`（3）· `app/gui/SdDecisionMakerApiTest`（3）· `app/gui/SdDecisionScopeApiTest`（2）· `app/gui/SdDecisionDocsApiTest`（2）· `app/access/DecisionFaceGolden`（2）· `sd/spi/CreateNationHandlerTest`（1）· `sd/spi/CreateArmyHandlerTest`（1）· `app/tools/write/AdjudicateTickToolTest`（1）· `app/gui/SdDirectivesApiTest`（1）· `app/McpServerTest`（1）· `app/McpPortTopologyTest`（1）。
★ 附带一类：**决策人窄工具名**由 `sd.SetDiplomaticRelation` / `sd.RecordDiplomaticEvent` 变为 `gov.*` ⇒ 夹具里 `allowedTools` 写旧名的用例（`DiplomaticWriteToolsTest` 等）在交集里**失去该工具**（fail-closed，不是提权）。
★ 计数表（catalog 143 / 66 / 97 / 142-143 等）**本批一处未变**（4 条改名不改数）⇒ 3b-1/3b-2 台账里的那些计数红点**不受本批影响**。

**C. 旧命令串的"未注册 ⇒ Rejected"行为（本批未运行期实测）**
静态：4 条旧串在 `simos-*/src/main` 已 **0 命中**（`evidence/literal-scan.txt`）；`CommandBus` 对未注册类型走 `未注册的命令类型: …` 的 Rejected 分支（不留 revision）。⇒ **收尾期补一条端到端负向用例**（旧 `sd.CreateNation` ⇒ Rejected 且 head 不变；`gov.CreateNation` ⇒ 落盘）。

**D. 本批新增行为尚无测试（收尾期按规划 §18.2 的判据补）**
① `Affiliation.Nation` 的不透明引用：**读不到归属 ⇒ deny-all**（`NationScope` 四命名空间 `none()`）的负向用例；
② `NationAffiliations.resolve` 的裁剪（解析不了 / 国家不存在的引用被丢弃）；
③ `gov.DeleteNation` 经 `NationAffiliationLookup` 端口的"决策人绑定"守卫（端口在 gov、实现在 app）；
④ `gov.*` 四条命令的落盘与 `GovChangeSet` 的外交三组件（正向）；
⑤ `RegionDeleteGuard` 迁 gov 后的 `name()` 与真值表；
⑥ 工具面资源命名空间仍写 `sd` 的债（见下 E）。

**E. 已知债（如实记，本批**有意**未做）**
1. **工具面的资源声明仍写 `sd` 命名空间**（`SdCreateNationTool` 等的 `SD_NAMESPACE_WRITE` / `SD_READ` / `decisionWriteResources`）：新增 `GOV_NAMESPACE` 要动 **`simos-ga-spi/**`（本批禁碰）** ⇒ 留给批次 5（gov 命令面）。运行时影响为零（GM 对 sd unlimited；DM 侧声明与迁移前逐字相同），但"资源面正名"未做。
2. **`GovPayloads` 是模块内第三份载荷解析**（sd/ga/gov 各一份）：本仓既有形制即"每模块一份 `XxxPayloads`"，本批按同形制落最小子集；若要收口，属"线格式一处声明"的另一次决定。
3. **javadoc 债 1 处（本批写权外）**：`simos-social/src/main/java/io/mosire/simos/social/spi/CreateCityHandler.java:34` 仍以 `sd.CreateNation` 作对比（**纯注释、零行为**）。
4. **`SdSnapshots.map(...)` 成了死代码**（唯一调用者 `CreateNationHandler` 搬走）：本批**保留**（删它会再增加若干条测试编译断）；随 sd 3d/3e 退役一起删。
5. **`gov.DeleteNation` 的 `targetPaths` 仍走 `ResourcePaths.sd("nation", …)`**：它返回**不含命名空间**的路径，命名空间由 `CommandTargets` 默认实现从命令类型第一段取 ⇒ 实际是 `gov:nation/<id>`（行为正确）；util 侧没有 `gov(...)` 助手，本批文件所有权只允许往 `util/spi` 放 `DecisionMakerId`。

---

### 批次 3d-1（军队身份 `armies` + `Army`/`ArmyId` 搬进 army；2 条命令 `sd.*`→`army.*`；`Affiliation.Army` 收窄为不透明串）

> 提交面：**未提交**（实现代理**不 commit**，§一.5：控制方审后提交）。工作树 53 项（41 M / 4 D / 8 ??）。
> ★ 本节**只列相对 3c 台账新增的部分**（§12.2：分不清"老架构老测试该删"与"新代码弄坏了"就无法归因）。
> ★ 与 3c 的**结构性同构**（三表/两表 + handler + 类型搬走、`Affiliation` 某支收窄为 `String`、裁定方在新模块 + 端口在模块/实现在 app）⇒ 下面的归类也照 3c 的分节。

**A. 新增编译断（类型/构造器/包搬家）**
| # | 形态 | 命中（test 侧，实测 grep） | 处置方向 |
|---|---|---|---|
| A1 | **`ArmyData` 构造器 1 参 → 2 参**（组件 `combats` → `armies` + `combats`） | `simos-army/src/test/.../ArmyDataTest.java:20,33,36,46,80,81,97`（8 处）+ `army/testing/ArmyFixtures.java:73` | `new ArmyData(combats)` ⇒ `new ArmyData(Map.of(), combats)`（`null` 那处 ⇒ `new ArmyData(null, null)`，语义正是"旧档两个键都缺"） |
| A2 | **`ArmyId`/`Army` 从 `io.mosire.simos.sd.id`/`sd.model` 搬到 `io.mosire.simos.army`** ⇒ import 断 | **19 个文件**：`app/access/{DecisionMakerScopeEndToEndTest,GovScopeTest,ScopeFixtures}` · `app/decision/{DecisionAgentRunnerTest,RealLlmUnitDecisionLoopTest,ResetConversationEndToEndTest,RunDecisionEndToEndTest}` · `app/gui/SdRunDecisionApiTest` · `app/tools/write/{AdjudicateTickToolTest,DiplomaticWriteToolsTest,GovPayToolD5Test,GovToolsZ6Test,RejectDirectiveToolTest}` · `ga/change/GaRoundTripTest` · `sd/model/{SdIdTest,SdModelTest}` · `sd/spi/CreateArmyHandlerTest` · `sd/state/SdStateInvariantTest` · `sd/testing/SdFixtures` ·（`app/query/RedactingQueryServiceTest:416` 用的是内联全限定名，同族） | 收尾期把 import 改到 `io.mosire.simos.army`（类型形状一字未改） |
| A3 | **`Affiliation.Army(ArmyId)` → `Affiliation.Army(String)`** ⇒ 构造断 + `armyId().value()` 断 | 12 处（`ScopeFixtures:218` · `DecisionMakerScopeEndToEndTest:767` · `RealLlmUnitDecisionLoopTest:1380` · `DecisionAgentRunnerTest:146` · `GovPayToolD5Test:489` · `GovToolsZ6Test:1573` · `AdjudicateTickToolTest:1395` · `DiplomaticWriteToolsTest:337` · `RedactingQueryServiceTest:416` · `GaRoundTripTest:334,696` …） | `new Affiliation.Army(new ArmyId(x))` ⇒ `new Affiliation.Army(x)`；`a.armyId().value()` ⇒ `a.armyId()`。★ 线格式随之变化（`{"armyId":{"value":…}}` → `{"armyId":"…"}`） |
| A4 | **`SdState` 5 → 4 / `SdChangeSet` 5 → 4**（`armies` 组件消失） | `sd/testing/SdFixtures`（12 参构造器 + `withArmies` + `.armies()`）· `sd/change/SdRoundTripTest` · `sd/codec/SdCodecTest` · `sd/state/SdStateInvariantTest` · `sd/model/SdModelTest` · `sd/spi/{RecordDiplomaticEventHandlerTest,SetDiplomaticRelationHandlerTest,ResetDecisionMakerConversationHandlerTest,CreateArmyHandlerTest}` · `app/access/{ScopeFixtures,DecisionMakerScopeEndToEndTest,GovScopeTest}` · `app/decision/{DecisionAgentRunnerTest,RealLlmUnitDecisionLoopTest,ResetConversationEndToEndTest,RunDecisionEndToEndTest}` · `app/gui/SdRunDecisionApiTest` · `app/tools/write/{AdjudicateTickToolTest,DiplomaticWriteToolsTest,RejectDirectiveToolTest}` · `app/world/CompactThreeNationsWorld` | 这些文件的 `.armies()`/`withArmies(...)` 调用改走 `ArmyData`（army 切片）；★ **`SdFixtures` 是 sd 侧多数断的共同源头**（同 3b-1 台账 A 节的同一处） |
| A5 | **两条 handler 搬包**：`simos-sd.spi.{CreateArmyHandler,SetArmyMasterGovHandler}` → `simos-army.spi.*` | `sd/spi/CreateArmyHandlerTest`（整类）· `sd/spi/CreateDecisionMakerHandlerTest`（`Affiliation.Army` 夹具） | 收尾期把 handler 测试迁到 `simos-army`（或改 import）；`ArmyDataTest`/`ArmyFixtures` 与它同属"3d-1 的两条新增 A" |

**B. 新增断言红（旧命令串 / 地址族 / 工具资源面；非编译断）**
| # | file:line | 钉的旧形状 | 处置方向 |
|---|---|---|---|
| B1 | `app/tools/AccessPathsTest.java:55` | `ToolSupport.resourceSd("army","a1").fullId() == "sd:army/a1"` | ★ 本批**有意未改**：`ResourcePaths.sd(kind,id)` 今天仍被决策人/决策包三处生产路径用（`DecisionCallerFactory:330,335` · `AbstractNarrowWriteTool:181`）⇒ 它不是"军队专属"，删不得。该断言钉的是"sd 路径语法对任意 kind 都成立"，与新家无关；若收尾期要正名，属批次 5（gov 命令面）同族的资源面收口 |
| B2 | `sd/resolve/SdResolverTest.java:34`（`one("sd:army.a1").typeName() == "Army"`） | `sd:army.<id>` 地址族已被**整支删除**（用户已批的不可逆点，先例 = 3c 对 `sd:nation.<id>`） | 改新契约：军队身份读面 = `army:army.<id>`（`ArmyResolver`）；旧地址 ⇒ 空候选（不是抛） |
| B3 | `app/tools/write/AdjudicateTickToolTest`（`samples` / 清单里的 `sd.CreateArmy`、`sd.SetArmyMasterGov` 串） | 两条命令改名 `army.*` | 收尾期改新串；★ **`hasSize(66)` 与 `hasSize(97)` 不受影响**（2 条改名不改数；两条 handler 本就不实现 `CommandTargets`、也不打 `DmUsableCommand`） |
| B4 | `app/McpCoverageTest` / `app/tools/SimosToolsTest` / `app/tools/read/CatalogTool` 相关用例里的 `sd.CreateArmy`/`sd.SetArmyMasterGov` 串 | 同上 | 同上；★ 工具 **NAME 常量一字未改**（`SdCreateArmyTool.NAME` / `SdSetArmyMasterGovTool.NAME` 仍是 `sd.*`）⇒ 工具面/白名单/MCP 覆盖表的**名称**那一维零变化 |
| B5 | `army` 侧的 `AssignArmyGovToolTest`（若断言 `ga.PutInfo` 的 `address`） | 本批把该工具发出的行动记录地址由 `sd:army.<id>` 改成 `army:army.<id>`（`AssignArmyGovPlan.armyAddress` 改走 `ArmyAddresses.armyCanonical`） | ★ **有意的可观测变化**：旧地址在新解析器下已不可能被认领（sd 侧那支已删）⇒ 留着它等于发一个查不到的地址。改新契约 |

★ 计数表（catalog 143 / 66 / 97 / 142-143 等）**本批一处未变**（2 条改名不改数）⇒ 3b-1/3b-2/3c 台账里的那些计数红点**不受本批影响**。
★ **代码侧不做端到端的"旧串 ⇒ Rejected"实测**（本批门禁不含 `test`，§12.1）：静态依据 = 4 条旧串在 `simos-*/src/main` 已 **0 命中带引号字面量**（残留 2 处全是工具 `NAME` 常量，那是**工具名**不是命令类型），而 `CommandBus` 对未注册类型走 `未注册的命令类型: …` 的 `Rejected` 分支（不留 revision）。⇒ **收尾期补一条端到端负向用例**（旧 `sd.CreateArmy` ⇒ Rejected 且 head 不变；`army.CreateArmy` ⇒ 落盘 + `army` 切片的 `armies` 恰 1 条）。

**C. 本批新增行为尚无测试（收尾期按本批判据补）**
① `Affiliation.Army` 的不透明引用：**读不到归属 ⇒ deny-all**（`ArmyScope` 四命名空间 `none()`）的负向用例（自称串解释不了 / 军队不存在两种入口）；
② `ArmyAffiliations.resolve` 的裁剪（解析不了 / 军队不存在的引用被丢弃）与 `AppArmyAffiliations.of` 的"自称 × 裁定"唯一拼装点；
③ `army:army.<id>` 的解析（存在 ⇒ `Army` 主体、不存在 / 空白 id ⇒ **空候选不是抛**）与 `ToolSupport.subjectVisible` 的新分支（按 root 单位所在格判、无有效位置 ⇒ 不可见）；
④ `army.CreateArmy` / `army.SetArmyMasterGov` 的正向落盘（`ArmyChangeSet` 的 `armies` 组件）与旧串 `Rejected`；
⑤ `ArmyData` 两个组件的构造期不变量（键=值内 id 两条）与 `ArmyCodec` 两个键的读侧注册（漏 `ArmyId` 则 `armies` **非空即解码炸**，空表测不到）；
⑥ `ga.CreateDecisionMaker` 给不存在的军队 id：从"当场具名拒"变"建成 + 该决策人 army 面 deny-all"（见 D1，方向 fail-closed）。

**D. 已知债（如实记，本批**有意**未做）**
1. **`army` 两条命令的工具面资源声明仍写 `sd` 命名空间**（`SdCreateArmyTool` / `SdSetArmyMasterGovTool` 的 `SD_NAMESPACE_WRITE` / `sdNamespaceWriteResources()`；而这两条命令的 handler 只产 `ArmyChangeSet` ⇒ **实际写的是 `army` 命名空间**）：★ 技术上加这个口子**只需动 `simos-ga/src/main`**（基类加一对 `ARMY_NAMESPACE_WRITE` / `armyNamespaceWriteResources()`，形制与 army 模块自己的 `AbstractNarrowWriteTool:54-60` 逐字相同，**不必碰 `simos-ga-spi`**）。**本批仍选择不动**，理由是避免在「军队身份」这一批里同时改两条：① 资源**声明**（`resources()`，审批面可见）；② 资源**断言**（`writeResources()`，权限判定）——两者一起改，一旦某条 GM 路径的声明面与断言面不同步，失效方向是「该拒的放行」。本批红线是「2 条改名不改数、四面逐条不变」，动它就要额外证明 GM 面零变化 ⇒ 属同 3c §E.1 的「资源面正名」，**留给批次 5 一并收口**（那时连带 gov / 军队 / 战斗族的资源面一起正名）。运行时影响：GM 侧 `sd` 与 `army` 都是 `unlimited()`（`Shell#gmPermissionSet`），DM 侧两条命令本就不可用 ⇒ **零行为变化**。
2. **`AffiliationTargets` 的 Army 那一路从"创建期具名拒"变为"放行 + 读面 deny-all"**（见 C⑥）：3c 已对 Nation 做过同一处置（台账 3c §D3 追认），本批逐字沿用；乙第 4 步在 army 自己的 `army.Assign*DecisionMaker` 命令里把存在性校验补回。
3. **javadoc 债 3 处（本批写权外）**：`simos-social/src/main/.../CreateCityHandler.java`（3c §E.3 已记的那处）之外，本批**没有新增**写权外的旧串（`simos-sd` 内 8 处"历史叙述"式 `sd.CreateArmy`/`sd.SetArmyMasterGov` 提及已逐条改读 `army.*` 或明确标注为"迁入前叫 sd.*"的留痕）。

---

### 批次 3b-4（`effects` 整族搬进 `ga`：`SdState` 1→0 / `GaState` 6→7 / 2 条命令 `sd.*`→`ga.*` / 开 `ga → map+unit`）

> 提交面：**未提交**（实现代理**不 commit**，§一.5：控制方审后提交）。工作树 38 项（28 M / 9 D / 10 ??，其中 1 ?? 是重建的 `GaRoundTripTest`）。
> 证据目录 `.superpowers/sdd/2026-10-12-sd-abolition-3b4/evidence/`（compile / spotless / spotbugs / validate / validate-negative / faces / literal-scan / reds-scan / mutation / sd-empty-codec-smoke）。
> ★★ **本节的"新增"基准如实说明**：本台账的**最后一节是 3d-1**（**3d-2 那一批从未追加进本台账**——它的证据在
> `.superpowers/sdd/2026-10-12-sd-abolition-3d2/`，口径见规划 §21）。故本节相对 **3d-1 节 + 规划 §21（3d-2）** 记账。
> ★ 方法说明（为什么是静态推理而不是机械 A/B）：`-fae test-compile` 实测 baseline 与 head 的 sd 模块报错**都恰好 100 处**
> （javac `-Xmaxerrs` 默认上限）⇒ 机械 diff 被截断、不可用。改用"基线里该文件是否已断"的静态分类（脚本按 import 是否在
> 基线树里可解析判定），清单见 `evidence/reds-scan.txt`。

**A. 编译断（★ 本批**新增**的，3d-2 时未断 —— 只有 2 个文件）**
| # | file | 形态 | 处置方向 |
|---|---|---|---|
| A1 | `simos-app/src/test/.../sd/SdCommandDrainTest.java:12,15,122,124` | `import io.mosire.simos.sd.spi.RegisterEffectHandler` + `sd.time.SdTimeParticipant` ⇒ **类搬家**；`new RegisterEffectHandler(...)` / `new SdTimeParticipant(MAP_ID)` | import 改 `io.mosire.simos.ga.spi` / `io.mosire.simos.ga.effect`；`new GaTimeParticipant(MAP_ID)`；★ 夹具还要补 **ga 切片**（drain 现在读 ga，`evidence/reds-scan.txt` 的 `gaOf` 断言） |
| A2 | `simos-app/src/test/.../sd/SdTimeParticipantWiringTest.java:11,52,53` | 同上（整类的被测对象搬走） | 迁到 `simos-ga` 或改 import + 类名（`GaTimeParticipant`）；★ 它的"同 namespace 重复注册 ⇒ 抛"用例语义不变（参与者身份仍是单值） |

**B. 编译断（本批**新增断点**，但这些文件在 3d-2 就已因更早的类型搬家而断 —— `reds-scan.txt` B 节）**
`simos-app/.../logging/SdLoggingTest.java`（`Action`/`Effect`/`EffectKind`/`EffectStatus`/`Trigger`/`SdTimeParticipant` 6 条 import）·
`simos-app/.../sd/SdCombatEndToEndTest.java`（`SdTimeParticipant`）· `simos-sd/.../testing/SdFixtures.java`（5 条效果 import + `effect(...)`/`E1` 夹具）·
`simos-sd/.../time/SdTimeParticipantTest.java`（5 条效果 import + `new SdTimeParticipant(...)`）· `simos-sd/.../state/SdStateInvariantTest.java`（`Trigger` import）·
`simos-sd/.../spi/SdCombatHandlersTest.java`（`Trigger` import）。
**处置**：§一.10「老架构老测试、新架构新测试」⇒ 收尾期**删/迁移**（`effects` 已不在 sd）：sd 侧的效果用例迁到
`simos-ga`（`GaTimeParticipant` + `GaState.effects`），或按新契约重写。

**C. 编译断/断言红：`SdState` 1→0 的成员（`effects()` / `withEffects(...)` / 构造器元数）**
`SdFixtures:129,183-185` · `SdTimeParticipantTest:67,75,124,127,140,158,240` · `SdRoundTripTest:263` · `SdStateInvariantTest` ·
`SdCodecTest`（键集/`withEffects`） · `IssueDirectiveHandlerTest:229` · `SetDiplomaticRelationHandlerTest:188` ·
`RecordDiplomaticEventHandlerTest:185` · `ResetDecisionMakerConversationHandlerTest:106` · `SdLoggingTest:362` ·
`SdCommandDrainTest:209`。
**处置**：`effects` 的读写一律改 `GaState`（`withEffects`/`effects()` 同名同形，只换家）；`SdChangeSet` 的组件数断言改 **0**。

**D. 断言红：两条旧命令串（7 个 app 测试文件，共 17 处）**
`DecisionAgentRunnerTest` · `SimosToolsTest` · `GovPolicyIntoEconomyZ6Test` · `McpServerTest` · `SdCommandDrainTest` ·
`McpPortTopologyTest` · `McpCoverageTest`（`"sd.RegisterEffect"` / `"sd.CancelEffect"`）。
**处置**：收尾期**改为新串**（不是删）；★ 计数表（catalog 138 / 66 / 97 / 142-143）**本批一处未变**（2 条改名不改数）
⇒ 3b-1/3b-2/3c/3d-1/3d-2 台账里那些计数红点**不受本批影响**。

**E. 断言红：`sd:effect.<id>` 地址族（本批删除该支）**
`SdResolverTest:38`（`one("sd:effect.e1").typeName()=="Effect"`）· `SdTimeParticipantTest:89,90`（`proposal.reads()/writes()`
断言 `sd:effect.e1`）。
**处置**：`sd:effect.<id>` ⇒ **空候选**（新契约）；读写声明串换成 `ga:effect.<id>` / `ga:info.<key>`（本批只有命名空间变，
结构一字未改）。★ **本批不补 `ga:effect.<id>` 解析器**（全仓零生产读者按地址取 effects）——见账本 §4.2，留 3e 裁定。

**F. 护栏（§12.3 例外，当场修，不攒台账）**
`simos-ga/src/test/.../GaRoundTripTest.java`：同批扩到 **7 组件**（`hasSize(7)` + `containsExactly(…,"effects")` + `mutate`/`changedOf`
两支 + 七张表非空的逐字段/JSON 往返 + `Family` 两处元数）。★ **该文件自 3d-1 起就编译不过**（台账 3d-1 §A2 已列：`sd.id.ArmyId`/
`sd.id.NationId`/`sd.id.DecisionMakerId` 三个 import 指向已搬走的类型）⇒ 本批一并修正 import 与 `Affiliation.Army(String)` 收窄
（它是本批唯一允许改的测试文件）。**结果 11/11 绿**；变异自证：`GaState` 加第 8 组件 ⇒ **红 4 处**，还原 md5 逐字节一致并重跑绿。

**G. 本批新增行为尚无测试（收尾期按规划 §18.2 / §15 的判据补）**
① `ga.RegisterEffect` 正向落盘（`GaChangeSet.effects` 非空）+ 旧串 `sd.RegisterEffect` ⇒ `Rejected` 且 **head 不变**；
② `ga.CancelEffect` 的可取消状态真值表（`PLANNED`/`COMMITTED` 可取消，`FIRED`/`CANCELLED`/`EXPIRED` 具名拒）；
③ `Trigger.UnitAtHex` 的存在性判据走 **unit 切片**（`ga → unit` 的运行时用途）；
④ `GaTimeParticipant` 逐日等价性（`advance(from, from+N)` 终态 == N 次单日 advance）与 `Action.PutInfo` 写 `GaState.info` 的
序号/tick/atRevision 三件套；⑤ `GaCodec` 第 6 个键读侧（漏 `EffectId` 则 `effects` **非空即解码炸**，空表测不到）；
⑥ `Effect` 的 sealed 多态往返（三档 `Trigger` / 两档 `Action`）与 `Action.PutInfo.value` 的标量往返边界。

**H. 已知债（如实记，本批**有意**未做）**
1. **两个效果工具的资源声明仍写 `sd` 命名空间**（`SdRegisterEffectTool` / `SdCancelEffectTool` 的 `SD_NAMESPACE_WRITE` /
   `sdNamespaceWriteResources()`；而这两条命令本批起**实际写 ga 命名空间**）：★ 技术上正名要动
   **`simos-ga-spi/ToolResources`（本批禁碰）**，且 `Shell#gmPermissionSet` 的逐命名空间表态里**没有 `ga` 档**
   ⇒ 硬改会把 GM 面推到"未表态"分支。**留给批次 5**（与 3c §E.1 / 3d-1 §D.1 合并收口）。运行时影响为零
   （两条工具只在 GM 桶；GM 侧 `sd` 是 `unlimited()`，断言只决定放行、不决定命令落哪个切片）。
2. **`sd:effect.<id>` 地址族无替代**（见 E 节）；`SdResolver` 仍要求 sd 切片存在（装配期有牙）但已不读它的任何组件。
3. **类名/包名保留**：`SdRegisterEffectTool` / `SdCancelEffectTool`（工具名不是命令类型）/ `SdCommandDrain` + 包 `app.sd`
   ⇒ 留 3e 随 sd 空壳正名。
4. **`SdLogSource.SD_TICK` 与 `SdLog.time()` 无生产者**（先例 = 3d-2 的 `SD_COMBAT`：稳定 id 不悄悄改）⇒ 随 3e 收口；
   ga 侧新增 `GaLogSource.GA_TICK` + `GaLog.time()`（表项新增，非改名）。
5. **`Directive.effects()` 外键仍只在命令期判**（`effects` 已同树 ⇒ 技术上可回构造期）：本批有意不回迁
   （会新开一个"搬迁前能构造、搬迁后当场抛"的失败面），见账本 §3.3，留 3e/后续批次复核。

---

### 批次 3b 乙收尾（归属彻底归模块 + 删 `Affiliation` 本体；3 条新命令 · catalog 138→141）

> 提交面：**未提交**（实现代理**不 commit**，§一.5：控制方审后提交）。证据目录
> `.superpowers/sdd/2026-10-12-sd-abolition-yi/evidence/`（compile / spotless / spotbugs / validate / faces-before / faces-after / faces-diff /
> grep-no-kind-enum / deny-all / reds-scan）。
> ★ 本节**只列相对 3b-4 台账新增的部分**（§12.2：分不清"老架构老测试该删"与"新代码弄坏了"就无法归因）。

**A. 新增编译断（类型/构造器/方法被删被改；`-Dmaven.test.skip=true` 下不显示，纯静态扫描）**
| # | 形态 | 命中（test 侧，实测正则扫描） | 处置方向 |
|---|---|---|---|
| A1 | **`io.mosire.simos.sd.model.Affiliation` 整型删除** | **33 个文件** import 断（含 `app/access/*`、`app/tools/*`、`sd/model/SdModelTest`、`sd/codec/SdCodecTest` …） | 收尾期按新契约重写：归属改走 `AppDecisionMakerAffiliations.claimsOf(...)`（**kind 串 + ref**）或 gov/army 自己的表 |
| A2 | **`Affiliation.Nation/Army/Gov` 构造** | **35 个文件** | 同上；★ 其中"造决策人夹具"的那一类改成"裸 `DecisionMaker` + 赋权命令" |
| A3 | **`DecisionMaker` 构造器 7 参 → 6 参（便利构造 5 → 4）** | **32 个文件** `new DecisionMaker(` | 删掉第 2 个实参（`affiliation`） |
| A4 | **`SdInfoEntry.affiliations` 类型 `Set<Affiliation>` → `Set<String>`**（不透明标签） | **11 个文件** `new SdInfoEntry(`（+ 视图断言） | 夹具改喂串（形如 `nation:n1`）；视图从 `[{kind,id}]` 变排序串数组 |
| A5 | **`ArmyData` 2 → 3 参 / `ArmyChangeSet` 2 → 3 参** | **5 个文件**（`army/ArmyDataTest`、`army/testing/ArmyFixtures`、`army/spi/ResolveCombatStageHandlerTest`…） | 第三个组件 `armyAffiliations` 补 `Map.of()`；★ **这些文件在基线就已编译断**（3d-1 §A1 + 3d-2 的 `CombatRecord` 第 8 组件），见台账 §20.1 点 4 |
| A6 | **`GovState` 5 → 7 参 / `GovChangeSet` 5 → 7 参** | **5 个文件** | 两个归属表补 `Map.of()` |
| A7 | **`DecisionScopeFunctions` 键类型 `Class<? extends Affiliation>` → `String`** | **12 个文件** | 用例改喂 kind 串（`NationAffiliations.KIND` 等） |
| A8 | **`DiplomaticWriteSignature.violation(...)` 多一个端口形参** | **2 个文件** | 传 `AppDecisionMakerAffiliations.INSTANCE` |
| A9 | **两条外交写工具构造器多一个端口形参** | **1 个文件** | 同上 |
| A10 | **`maker.affiliation()` / `dm.affiliation()` 字段访问消失** | **5 个文件** | 改走归属索引 / 各自的表 |

★ **删除类**（本批删掉、测试侧 0 命中，但属新契约）：`sd.model.Affiliation` · `sd.spi.AffiliationTargets` ·
`gov.spi.NationAffiliationLookup` · `army.spi.ArmyAffiliationPort` · `SdPayloads.requireAffiliation` / `optionalAffiliationSet`
（`SdSnapshots.units(...)` 同批删除）。

**B. 新增断言红（报文/视图形状；非编译断）**
| # | file:line（预期） | 钉的旧形状 | 处置方向 |
|---|---|---|---|
| B1 | `app/McpCoverageTest` 的 `EXPECTED_COMMAND_TYPES` / `MINIMAL_PAYLOADS` | 计数 **138 → 141**；三条新命令各需一个能真提交成功的最小载荷 | 补三条（`decisionMakerId` + 目标键；清除型载荷只给 `decisionMakerId`） |
| B2 | `app/tools/SimosToolsTest`（`hasSize(142/143/…)` 一类） | catalog/工具面计数随之变 | 收尾期按新契约改数（★ 本批**不打** `DmUsableCommand` ⇒ 可嵌令/可入队/落点不动） |
| B3 | `app/tools/write/AdjudicateTickToolTest:549 hasSize(66)` | **不受影响**（三条新命令不进 DM 面） | — |
| B4 | `app/McpServerTest` / `McpPortTopologyTest` 的 `affiliations` 视图样本 | `[{kind,id}]` → `["nation:n1"]` | 改新契约 |
| B5 | `ga.CreateDecisionMaker` 的用例（`sd/spi/CreateDecisionMakerHandlerTest`、`app/access/*ScopeFixtures*` 等） | 载荷不再有 `affiliation`（带了 ⇒ **具名拒**） | 用例改成"建裸决策人 + 另发赋权命令" |
| B6 | `ga.PutInfo` 的用例（`sd/spi/PutInfoHandlerTest`、`app/SdPutInfoEndToEndTest`） | `affiliations` 必须是**字符串数组** | 改新契约（旧对象数组 ⇒ 具名拒） |

**C. 本批新增行为尚无测试（收尾期按判据补）**
① 三条赋权命令的幂等/改绑/清除（含"清一个本来没有的 ⇒ 空变更集、head 仍 +1"）与三条具名拒（决策人不存在 / 国家不存在 /
军队不存在 / 单位不是 GOV）；② `DecisionScopeFunctions` 的**裸决策人 ⇒ 四命名空间 deny-all** 与**未注册 kind ⇒ 抛**；
③ 多轴归属的**并集**语义（与单轴逐字段相同的对照）；④ `SdInfoEntry.affiliations` 的标签交集可见性（含"标签对不上 ⇒ 不可见"）；
⑤ `GovState`/`ArmyData` 新增组件经 `GovCodec`/`ArmyCodec` 的键读侧往返（**非空表**才验得到；三个护栏已覆盖）；
⑥ 旧载荷（`ga.CreateDecisionMaker` 带 `affiliation` / `affiliations` 给对象数组）⇒ 具名 `Rejected` 且 head 不变。

**D. 已知债（如实记，本批有意未做）**
1. `SimosSdReportTool:351` 仍按该工具自己的收件人词表拼受众标签（`nation:` / `gov:`）——标签拼法的第二个拼写点
   （既有债，收窄前同样在这里拼 `{kind}`）；失效方向 fail-closed（标签对不上 ⇒ 收不到）。
2. 删决策人**不清理** gov/army 的归属行（一条命令只写一个命名空间）：留下的是**够不着**的行（该 id 已不存在 ⇒ 没人会为它算范围）；
   若该 id 被重新创建，会继承旧归属。收口方向记在账本 §3.4（未做）。
3. 前端对 `affiliations` 视图形状变化的消费点**未逐文件核**（§17.2 的 GUI 面）；
   批内只核到 `simos-app/src/test/js` 里 `affiliation` 零命中。

---

### 批次 3e（`simos-sd` **整模块退役**：残壳整体迁 `ga`；`sd:` 地址族整体消失；模块 17 → 16）

> 提交面：**未提交**（实现代理**不 commit**，§一.5：控制方审后提交）。工作树 **186 项**（106 M / 38 D / 42 R；`git diff HEAD --stat` = 187 files / +904 / −7339）。
> 证据目录 `.superpowers/sdd/2026-10-12-sd-abolition-3e/evidence/`（compile / spotless / spotbugs / validate / archguards /
> faces-before / faces-after / faces-diff / reds-scan / testcompile / gates）。账本 `.superpowers/sdd/2026-10-12-sd-abolition-3e/impl-ledger.md`。
> ★★ **本节的"新增"基准 = 3b-4 节 + 乙收尾节**（本台账**从未追加 3c / 3d-1 / 3d-2 三节** —— 3c 只以"未提交"块的形式散在 3d-1 节上方，
> 3d-2 完全没有；口径以规划 §19/§20/§21 为准。**这是本台账第二次发现同类缺口**，收尾期按批号核对时应以此为准）。
> ★ 方法说明（为什么是静态推理而不是机械 A/B）：`-fae test-compile` 在 **simos-army 就 FAILURE 停住**（`ArmyData` 3 参的既有台账红），
> **根本走不到 `simos-app`** ⇒ 机械 diff 对本批完全不可用；`simos-app` / `simos-ga` 的红点全部由**静态扫描**给出（见 `evidence/reds-scan.txt`）。

**A. 编译断（★ 本批**新增**的：包 `io.mosire.simos.sd.*` 整体消失 / 类型搬进 `ga.*`）**
| # | 形态 | 命中（实测计数） | 处置方向 |
|---|---|---|---|
| A1 | **`import io.mosire.simos.sd.*` 全部失效**（包不存在；44 个类型改住 `io.mosire.simos.ga.{model,id,spi,channel,resolve,adjudication}`） | **74 个测试文件**（`simos-app` **73** + `simos-ga` **1**）；最重的几个：`decision/RealLlmUnitDecisionLoopTest`(18 条) · `tools/write/DiplomaticWriteToolsTest`(16) · `tools/write/AdjudicateTickToolTest`(16) · `tools/write/RejectDirectiveToolTest`(15) · `logging/SdLoggingTest`(15) · `decision/RunDecisionEndToEndTest`(15) · `llm/AdjudicationEndToEndTest`(14) · `gui/RedactionApiTest`(14) | 收尾期**机械改名 import 即可**（`io.mosire.simos.sd.` → `io.mosire.simos.ga.`；类型形状一字未改）。★ 逐文件清单见 `evidence/reds-scan.txt` A 节 |
| A2 | **`SdState` / `SdSnapshot` / `SdChangeSet` / `SdCodec` / `SdResolver` / `SdLog` / `SdLogSource` / `SdPayloads` / `SdCommandNames` / `DecisionTablesLookup` / `GaDecisionTablesLookup` 整类删除** | 71 个测试文件引用其中之一（含 A1 的子集） | ★ 这些是**旧架构本体**：`SdState`→`GaState`、`SdSnapshot`→`GaSnapshot`、`SdCodec`→`GaCodec`、`SdResolver`→`GaResolver`（构造器由 1 参变 **0 参**）、`SdPayloads`→`GaPayloads`、`SdLogSource.SD_*`→`GaLogSource.GA_*`（`SD_COMBAT`/`SD_TICK` 两档**不存在**，已随退役删除）；`SdChangeSet`/`DecisionTablesLookup`/`GaDecisionTablesLookup` **无替代**（按 §一.10 删除相关用例） |
| A3 | **`SdResolver` 构造器 1 参 → `GaResolver` 0 参**（`DecisionTablesLookup` 端口删除） | `simos-app/.../sd/*` 与 `access/*` 夹具里 `new SdResolver(...)` 的用例 | 改 `new GaResolver()` |
| A4 | **`GaRoundTripTest`（`simos-ga` 唯一护栏）import 断口从 3 条扩到 23 条** | `simos-ga/src/test/.../change/GaRoundTripTest.java`（基线即断，3d-1 起） | ★ **本批最高优先收尾项**（一行 sed：`io.mosire.simos.sd.` → `io.mosire.simos.ga.`）。本批未修的**理由**：任务书硬性纪律 1 只授权 `ArchitectureGuardsTest` 一个测试文件 ⇒ 未越权；后果 = **本批没有可用的铁律 5 变异证据**（如实记，不冒充通过） |

**B. 断言红（旧命令串 / 旧地址族 / 旧资源命名空间；非编译断）**
| # | file:line（预期） | 钉的旧形状 | 处置方向 |
|---|---|---|---|
| B1 | `sd/resolve/SdResolverTest.java:34` 一类（**文件已随模块删除**） | `one("sd:decision-maker.dm1")` / `typeName()` | 已随 A 删除；新契约 = `ga:decision-maker.<id>`（新用例收尾期补） |
| B2 | `app/.../decision/DecisionAdjudicationService` 的 D2 判决链 | subject 由 `sd:decision-maker.<id>` 改挂 **`ga:decision-maker.<id>`** | 收尾期：用例里的 subject 字面量同步改 `ga:`；★ `Breakpoints.acceptsVerdictSubject` 的 D2 支**已同批改挂**（`"sd"` → `"ga"`），两处必须逐字一致 |
| B3 | 7 处 **INFO key 前缀**：`sd:doc.` / `sd:adjudication.` / `sd:directive.` / `sd:decision.` / `sd:rejection.` / `sd:execution.` / `sd:decision-finalize.` | 全部改挂 `ga:*`（`ga.info` 表的 key 变了） | 收尾期：凡夹具里硬编码这些**字符串字面量**的用例改 `ga:`（用常量引用的**零改动**） |
| B4 | 前端 tooltip 文案（`webui/panels.js:2578,2762`） | `sd:adjudication.<tick>` / `sd:doc.<docId>` → `ga:*` | 已同批改（纯文案；JS 用例未核） |
| B5 | `app/McpCoverageTest` / `app/tools/SimosToolsTest` 的 `EXPECTED_COMMAND_TYPES` | ★ **本批一处未变**（catalog 141 / 可嵌令 66 / 可入队 66 / 落点 2 / 声明面 66，逐条对称差 = ∅） | — |

**C. ★ 本批**未**动的资源声明面（如实记，属控制方复核项）**
`ToolResources.SD_NAMESPACE = "sd"`（`simos-ga-spi`）+ `ResourcePaths.sd(...)`（`simos-util`）+ `ToolSupport.SD_NAMESPACE`（app）
共 **126 处**引用**原样保留**：它是**权限声明面**的命名空间标识（与状态切片 `sd` 同名但不同物），
正名要动 `simos-ga-spi/**` + `simos-util/**`（**本批写权外**），且"权限四面 + 声明面逐条不变"是本批红线。
★ 台账 3c §E.1 / 3d-1 §D.1 / 3b-4 §H.1 已把"资源面正名"明排给**批次 5** ⇒ 本批按既定排期执行。
运行时 GM 侧 `sd` 与 `ga` 都是 `ResourceScope.unlimited()`；**DM 侧无 `ga` 档** ⇒ 若硬改名会落"未表态"分支（失效方向可能是"该拒的放行"）—— 这正是 3d-1 §D.1 拒绝承担的风险。

**D. 本批新增行为尚无测试（收尾期按规划 §18.2 判据补）**
① 旧串零命中：`sd:decision-maker.*` / `sd:directive.*` ⇒ **空候选**（不是抛）；
② `ga:decision-maker.<id>` / `ga:directive.<id>` 正向解析（存在 ⇒ 1 候选；不存在 / 空白 id ⇒ **空候选**）；
③ **D2 判决端到端**：subject = `ga:decision-maker.<id>` ⇒ `acceptsVerdictSubject(D2, …)` 为真、判决可落 revision；
   ★ 负向：`sd:decision-maker.<id>` ⇒ 判决面**当场拒**（改挂的判别力核心）；
④ `ga` 切片缺席 / 类型不对 ⇒ `GaResolver` **抛**（装配故障，不折成空候选）；
⑤ 装配负向：**不再注册 `SdCodec`**（模块已无该类）⇒ 旧档里的 `sd` 条目在 `RichWorld.codecTable()` 处**响亮失败**（不静默丢弃）；
⑥ `GaLogSource.GA_RESOLVE` / `GaLog.resolve()` 的开关（`-Dsimos.ga.logLevel`）与事件名 `GA_RESOLVE_*`；
⑦ `ToolSupport.subjectVisible` 的 `"ga"` 支（`ga:decision-maker.<id>` 可见性按 `sd` 资源命名空间的既有前缀判，读不到 ⇒ false）。

**E. 已知债（如实记，本批**有意**未做）**
1. **资源声明面 `"sd"` 标识符未改名**（见 C 节 + 账本 §3.2/§4 D1）—— 留批次 5。
2. **`Sd*` 类名保留**（`SdInfoEntry` / `SdInfoId` / `SdInfoIds` 等住进 `io.mosire.simos.ga.model` / `ga.id`）——
   改名会把 diff 从"搬迁"扩成全仓重命名而判别力为零（模块已不存在）⇒ 记债，收尾期可评估。
3. **`GaRoundTripTest` 未修**（见 A4）—— 本批**无铁律 5 变异证据**。
4. **前端 REST 路由 `/api/sd/*` 未动**（那是 **HTTP 路由名**，不是地址族；`webui/api.js` 多处）—— 不在本批范围。
5. **`worlds/**` 未动**（本批禁碰）；`RichWorld` 只改了 app 侧的 codec 白名单与切片补法。

---

### 批次 4（权限收紧：`DmUsableCommand` 摘除 48 条）—— 预期红（**断言级**；编译级预期 0 断）

> **本批改动**：48 个 handler 删 `implements … , DmUsableCommand` + 对应 import（不动逻辑 / 命令类型 / 注册面）。
> 派生实测：catalog **141 不变** · 可嵌令 **66 → 18** · 可入队 **66 → 18** · 落点 **2 不变** · 声明面 **66 → 18**；
> 逐条对称差 = 摘除 48 / **新增 0**（权限只收紧）。`compile` / `spotless` / `spotbugs`（16/16 归零）/ `validate`（×15）全绿。
> ★ `test-compile` **未跑**（§12.1：测试环节留最后统一做）⇒ 下表为**静态推理**，逐条给依据。

**A. 编译断（预期 0）**
| 判据 | 结果 |
|---|---|
| 摘除只动 `implements` 子句 + import（无类型/签名变化） | 不产生编译断 |
| 全仓测试对 `DmUsableCommand` 的**正向** `isInstanceOf` 断言 | **0 处**（grep 实测） |
| `isNotInstanceOf(DmUsableCommand.class)` 的 6 处断言对象 | `actor.AdjustAccounts` · `economy.SetGovernmentPolicy`（2 处）· `army.{ResolveCombatStage,RecordCombat,AppendCombatStage}` · `unit.AdjustComposition` —— **都不在本批摘除面内** ⇒ 仍绿 |

**B. 断言红（预期红，真 `Shell` 装配 + 合成夹具）**
| # | file:line | 钉的旧形状 | 预期 | 处置方向 |
|---|---|---|---|---|
| 1 | `simos-app/src/test/.../tools/write/AdjudicateTickToolTest.java:548-556` | 真 `Shell`：`tool.allowedCommandTypes()` `.hasSize(66)` | **红** ⇒ **18** | 改 18；`:550-555` 的算式注释（「注册面 128 − GmOnly 15 − sd 自指 42 = 66」）是**批次 1 时代**的，整段重写 |
| 2 | 同上 `:520-546` | 「有意无目标的 10 条」`containsExactlyInAnyOrder(...)` | **红**：这 10 条**全在本批摘除面内**（`unit.CreateCommandChain` / `unit.UpdateCommandChain` / `map.SetEdge` / `map.RegisterPathwayGroup` / `economy.{AddDemand,CancelDemand,MigrateHousehold,RegisterCandidate,SetMarketPrice,TransferAssetShare}`）⇒ 缺口语义消失、`missing` 变空 | 收尾期裁定：删该断言，或改成「missing 为空（18 条白名单全部有目标）」 |
| 3 | 同上 `samples`（`:558` 起，共 **97** 条） | 逐条钉「白名单类型 + 目标路径」 | **红 38 条**（属于本批摘除面的样本清单：账本 `evidence-affected-samples.txt`） | 38 条迁成负向断言（「不在白名单 ⇒ 不可裁」）或删除 |
| 4 | `simos-app/src/test/.../access/CatalogVisibilityTest.java:57-92` | 合成面硬编码 `DM_DECLARED` = {`map.CreateRegion`, `map.UpdateRegion`, `map.DeleteRegion`, `unit.DisbandUnit`, `unit.SetStatus`}；类注自称「与生产逐条一致」 | ★ **不红但语义失真**（夹具喂合成面，不读生产 handler）—— 而这 5 条**全部**在本批摘除面内 | 收尾期把夹具换到新面（否则它继续为旧契约背书）；★ **不是**"改断言迁就实现"：这是夹具与生产脱钩 |
| 5 | `simos-app/src/test/.../sd/channel/DecisionChannelTest.java:101` | `new DirectiveWhitelist(Set.of("unit.RenameUnit", "sd.IssueDirective"))` 合成白名单 | ★ 不红但语义失真（`unit.RenameUnit` 已不在生产 DM 面） | 换成仍在面上的命令（如 `unit.PlanRoute`） |
| 6 | `simos-app/src/test/.../access/DecisionFaceGolden.java` | 金样本 = **工具面**（`roles()`） | **不红** | 零改动（本批只动命令面；132/10/34 与 44 条 DM 工具一字未动） |
| 7 | `simos-ga/src/test/.../command/CommandPermissionRulesTest.java:71` | 合成面声明 2 条 `.hasSize(2)` | **不红** | 零改动（合成面，不读生产 handler） |
| 8 | `simos-app/src/test/.../McpCoverageTest.java` / `tools/SimosToolsTest.java`（各命中 48 个被摘类型串） | catalog / MCP / 工具面计数 | **预期不红**（引用的是**类型串**，走 catalog 面；catalog 141 未变） | 收尾期实测；若某用例走 directive 面则按 #1-#3 处置 |
| 9 | `simos-app/src/test/.../decision/RealLlmGovScenarioTest.java:867,1945,1987,2844` | 3 个被摘类型串 | **预期不红**（走计划 / 直接提交路径；★ **未逐行走查**，如实记） | 收尾期实测 |

**C. 本批新增能力尚无测试（收尾期按规划 §4「批次 4」补）**
① 正向：18 条保留面**仍在**令白名单 / `RegisterEffect` 面（`PlanRoute` / `PlanSparseRoute` / `CancelRoute` 三件套是核心）；
② 负向（判别力核心）：48 条被摘命令**逐条**在 `ga.IssueDirective` 载荷 / `RegisterEffect` 入队里被**具名拒**（改前一验即红）；
③ 负向：DM 的 catalog（`CatalogVisibility`）里 48 条**消失**、18 条仍在；
④ 装配期三条审计 + 次序守卫仍成立（真 `Shell` 起得来 = 最强证据）；
⑤ 残留通道（须显式记）：`social.SubmitHouseholdWorkOrder` 仍可嵌令，且其 plan 含 `CREATE_HOUSEHOLD` / `ADD_MEMBERS` / `REMOVE_MEMBERS` / `TRANSFER_MEMBERS` / `ADJUST_POPULATION` ⇒ **DM 经工单仍能改人口**（带 reason + source 审计）——这是本批**有意保留**的设计点，不是漏网。

**D. 本批未动的面（如实记，控制方复核项）**
1. **工具面 `SimosTool.roles()` 一字未动**（派单书 ④：留给批次 5）⇒ DM 侧窄工具仍能**直接提交**被摘命令（不经可嵌令面）；本批只收窄「令里能嵌 / 效果可入队」。
2. **边界保留 12 条**未再收紧（GOV 行政 / 政策 9 + 家户经济角色配置 3）—— 若按判据 ① 的最严读法可再收紧，留给控制方 / 用户。
3. **`simos-ga/**` 的过时「66 条」文案未改**（写权外）：`RegisterEffectHandler.java:50-51` · `CommandFaces.java:22` · `DirectiveWhitelist.java` 类注。
   ★ 另有**先于本批就存在**的失真（本批未动、如实报）：`SimosToolSource.java:749` 称 `unit.AdjustComposition`「命令本身打 `DmUsableCommand`」，而它**从来不在** DM 面。
