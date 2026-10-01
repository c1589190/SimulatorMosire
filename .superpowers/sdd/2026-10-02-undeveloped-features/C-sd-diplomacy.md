# C 线：外交/附庸/决策人/事件自动化 —— 代码级缺口调查（2026-10-02）

- 仓库：`/home/cna/SimulatorMosire`，HEAD `aff48be9`，分支 `refactor/class-first-economy`。
- 世界：`/home/cna/simos-testspace/worlds/dashu-v2`；只读 GET 读数时 `main=150 / tick=120`（世界日志快照为 head 126，说明期间有别的会话推过 revision；世界事实以本次 GET 为准）。
- 纪律：未改任何代码/文档/世界数据，未 `git commit`，未跑 Maven/构建/测试；只写了本报告。世界侧只做 HTTP GET（`/api/state`、`/api/resolve`、`/api/map/regions/summary`、`/api/sd/decision-makers`、`.../scope`），未调 MCP 写接口。
- 证据格式：`相对仓库根:行号`。凡"没有 X"的结论，均回注册面/状态树/源码词界搜索核过；不确定的进「未核实项」。

## 0. 先更正清单/世界日志里三处与代码不符的机制描述（AGENTS.md §四）

1. **"`sd.RegisterEffect` 的效果不会随 `simos.advance` 自动执行，必须 GM 显式调 `sd.AdjudicateTick`"——只对跨模块动作成立。**
   `simos.advance` 内部会跑 `SdTimeParticipant`：逐日求值 trigger，达标即 `PLANNED→FIRED`，并**内联执行** `Action.PutInfo` / `Action.SetStage`
   （`simos-sd/.../time/SdTimeParticipant.java:94-120`、`:164-211`）。`sd.AdjudicateTick` **完全不处理 effects**：它只按 tick 取 Directive
   （`simos-app/.../tools/write/AdjudicateTickTool.java:452-466`），主流程只编 commands + flips + info（`:366-399`）。
   真正不自动的只有 `Action.EnqueueUnitCommand`（跨模块命令）与 `Action.RecordCasualties`（静默落 `default -> {}`，`:210`）。
2. **"`simos.state.resolve` 对 `sd:nation/...` 未返回候选"——地址语法用错了。**
   canonical 是 `sd:nation.大蜀`（点号），实测 GET `/api/resolve?address=sd:nation.大蜀` 返回 `Nation` 候选；`sd:nation/大蜀`（斜杠）返回空。
   解析器只按 `:` 切段、并在「未加引号的 `.` 紧跟 `[`」处断开（`simos-util/.../address/AddressParser.java:14-35`、`:66-89`），`/` 不是分隔符。
   解析面本身认识 nation/army（`simos-sd/.../resolve/SdResolver.java:65-88`），MCP GM 上下文 sd 是 `unlimited()`（`simos-app/.../Shell.java:1031-1062`），
   `simos.state.resolve` 又走 `ToolSupport.subjectVisible` 的 `sd` 分支（`simos-app/.../tools/ToolSupport.java:281-330`）。
   真正缺的是 **Nation/Army 清单与详情读口**（读工具清单 `SimosToolSource.java:585-650` 里没有）。
3. **"NationScope（若有）"——已有且已注册。**
   `DecisionScopeFunctions.defaults()` 三个内置实现含 `NationScope.INSTANCE`（`simos-app/.../access/DecisionScopeFunctions.java:46-53`）；
   实现见 `NationScope.java:64-80`。但当前世界 **46 个 DM 全是 Gov 归属**（GET `/api/sd/decision-makers`），没有 Nation/Army 归属 DM。

---

## C1. 外交关系领域缺失

- **结论：确认缺失（宣战/停战/和平/同盟/关系值/最后通牒/最后期限/屈服都不存在）；只有"Info + 军事占领 + GM 裁决式交战"的部分替代。**
- **证据：**
  - 状态树只有 10 个组件，无任何关系表：`simos-sd/.../state/SdState.java:53-63`（nations/armies/combats/combatStates/decisionMakers/directives/effects/verdicts/lossRecords/info）。
  - `Nation` 只有 4 个字段：`record Nation(NationId id, String name, RegionId homeRegion, int adminBudgetPerTick)`（`Nation.java:16`）；
    `adminBudgetPerTick` 全仓**无消费方**（grep 只命中模型/handler/catalog/Worldgen 写入，`WorldgenInitializeTool.java:1036` 恒写 0）。
  - `sd.CreateNation` 载荷 `{nationId,name,homeRegionId,adminBudgetPerTick}`（`CreateNationHandler.java:44-47`）；行为只有两条写校验 + 写 `nations` 一条：
    id 已存在拒（`:48-50`）、homeRegion 必须存在且 tag 以 `nation:` 开头（`:51-59`）、然后 `withNations`（`:60-62`）。
    **不建 GOV/DM、不写 map、不改 Region tag**；tag 只判前缀，**不判 `tagFor(nationId)` 相等**（`NationTag.java:21-23`），所以 homeRegion 可以挂着别国 tag。
  - `nation:` tag 是地图侧归属的唯一来源：`HexOwner.nationsOf` 逐 Region 取 `region.meta().tag()` 再 `nationIdOf(tag)`（`simos-app/.../access/HexOwner.java:58-68`）；
    `RegionDeleteGuard` 也只按 tag 前缀与 `Nation.homeRegion` 挡删（`simos-sd/.../guard/RegionDeleteGuard.java:56-68`）。
  - 命令面：世界 `command_catalog.txt`（单行 JSON）共 **78 个 types**，无任何 war/peace/alliance/ultimatum 类；仓库注册面同样没有
    （`Shell.java:520-570` 的 handler 清单；`simos-*/src/main/java` 对 `\b(vassal|suzerain|tribute)\b` 与 `朝贡|附庸|宗主|纳贡|宣战|停战|同盟|最后通牒|屈服` 词界搜索 **0 命中**）。
  - `sd.PutInfo` 是感知层：写进 `SdChangeSet.info`，类注明写"写的是感知层，ground truth 仍由各领域模块持有"（`PutInfoHandler.java:35-37`）；
    `SdInfoEntry` 同口径（"影响领域计算的字段不许走 INFO"，`SdInfoEntry.java:19-23`）。
  - `sd.CreateCombat/AddCombatStage/SetStageOutcomeTable/CommitCombatOutcome/RecordCasualties` 是 GM/窄工具面的裁决式交战
    （`CatalogTool.java:334-360` 的 payload hints）；阶段 transition 由 `SdTimeParticipant.java:122-157` 依据 `exit` 自动推进，但**选定 outcome/casualties 仍要人下命令**，没有宣战/战争状态/和约结算。
- **现状可用 workaround：**
  1. 立场/宣战/和约记 `sd.PutInfo`（地址 `sd:nation.<id>` 或 region 地址，key 自定），再以 `sd.IssueDirective`（`unit.*`）或 GM 窄写执行军事动作；
  2. 最后期限可用 `sd.RegisterEffect(SCHEDULED, at_or_after_tick)` + `Action.PutInfo` **自动**在到期日盖一条"期限已到"信息（sd 内动作，见 C6）；
  3. 交战用 `sd.CreateCombat`+stage+outcome table 表达，伤亡用 `sd.RecordCasualties`；占领用 `map.UpdateRegion`（tag/annexedBy）+`unit.SetJurisdiction`+GOV。
  边界：这些都不产生可判定状态，没有"谁和谁处于什么关系、关系值多少、谁该屈服"的读/写口；`annexedBy` 字段也只有显示（`ApiViews.java:5074`），无规则消费方。
- **若实现（建议）：**
  - 新 sd 组件 `relations: Map<RelationId, DiplomaticRelation>`；`DiplomaticRelation(RelationId id, NationId a, NationId b, DiplomaticStatus status, int relationValue, long sinceTick, Optional<Ultimatum> ultimatum)`；
    `Ultimatum(demand, deadlineTick, onAcceptStatus, onRejectStatus)`。状态枚举至少 WAR/PEACE/ALLIANCE/TRUCE/ULTIMATUM/VASSAL（附庸可复用 C2 的关系类型）。
  - 命令：`sd.SetDiplomaticRelation`（`{relationId,fromNationId,toNationId,status,relationValue?}`）、`sd.IssueUltimatum`（`{ultimatumId,fromNationId,toNationId,demand,deadlineTick}`）、
    `sd.RespondUltimatum`（`{ultimatumId,decision:"accept"|"reject",reason?}`）。命名/信封对齐现有 `sd.*` 固定窄工具风格。
  - **关键结构约束**：`DirectiveWhitelist` 构造期**排除所有 `sd.*`**（`DirectiveWhitelist.java:31-46`），`IssueDirectiveHandler` 也把 `sd.` 自指具名拒（`IssueDirectiveHandler.java:174-181`）。
    ⇒ 任何新 `sd.*` 外交命令**不可能**被 DM 直接嵌进 `sd.IssueDirective`。要 DM 能提出宣战/纳贡/屈服，二选一：
    (a) 新增一条 DM 专属窄工具 `sd.IssueDiplomaticAction`（app/tools/write，敏感写走审批，像 `IssueDirectiveTool` 那样只写自己那块 sd 资源）；推荐；
    (b) 在自指禁令上开一个**具名安全清单**（只放形状封闭、无递归的 `sd.SetDiplomaticRelation`/`sd.RespondUltimatum`），须另写"为什么这些不自指递归"的守卫。
  - 落点文件：`simos-sd/src/main/java/io/mosire/simos/sd/model/`（新 record/枚举）、`state/SdState.java`、`change/SdChangeSet.java`、`codec/SdCodec.java:73-84`（新 Id 的 key deserializer）、
    `spi/` 新 handler；`SdState.requireReferentialIntegrity`（`:290-321`）加"两端 Nation 必须存在"。
  - 铁律 5：`SdState` 加第 11 组件 ⇒ 同批加 `SdChangeSet` 字段 + `between/apply/isEmpty`（`SdChangeSet.java:41-101`）+ Codec 键注册；`SdRoundTripTest` 反射枚举会当场红，这是护栏不是障碍。
  - handler 注册：`Shell.java:520-570`；catalog hint：`CatalogTool.java:295-360`；catalog types 会随注册自动生成（`Shell.java:565-575`）。
  - MCP：GM 窄写进 `SimosToolSource.addGmWrites`（`:540` 段）；DM 工具进 `SimosToolSource.addDecisionAgentWrites` + `DecisionCallerFactory.WHITELIST`（`:87-114`，两处必须同源）。
  - 前端/API：GUI 加 `/api/sd/relations` 只读视图，和 MCP 共用 `ApiViews`；地图图层按 tag/relation 着色。读口先做 GM-only（同 `simos.sd.decision-makers` 的底牌口径）。
- **依赖与风险：** 需要先定"关系放 sd.Nation 之间还是 GOV 之间"（现行世界外交主体是 Nation，行政主体是 GOV，二者只靠 tag/命名弱关联）；`sd.*` 自指禁令必须先解决；关系值/期限要有明确的时间语义（1 tick = 1 天）。
- **未核实项：** 未验证 `annexedBy` 在 GUI 之外是否有隐藏读者；未穷举 100+ 工具的全部 payload 字段（只按词界与注册面核）。

---

## C2. 称臣纳贡/附庸与朝贡关系

- **结论：部分具备——没有附庸/宗主/朝贡领域模型；但"GOV 上下级 + 国库上缴 + 税/徭役"三块现有能力可以拼出一个不带义务/自治/期限语义的替代结构。**
- **证据：**
  - 附庸/朝贡领域模型确认缺失：SdState 10 组件无关系表（`SdState.java:53-63`）；`Army` 阶段 12 起连 `NationId` 都去掉了（`Army.java:13-23,32-33`）；词界搜索 0 命中（见 C1）。
  - 已有替代一：GOV 层级。`GovFormation` 含 `Optional<UnitId> superiorGov` 与 `GovLevel`（`simos-unit/.../GovFormation.java:31-32`）；
    `unit.SetGovSuperior` 可改上级，校验存在/是 GOV/不自指/不成环（`SetGovSuperiorHandler.java:47-59`，窄工具 `UnitSetGovSuperiorTool.java:21`，**非 GmOnly**，可嵌令）。
  - 已有替代二：国库上缴原语。`actor.RemitGovTreasury` 支持 **grain/cloth/money** 三种资源的 GOV→GOV 原子转移，金额非负、至少一项 >0、源账必须存在、余额−冻结不足即整条拒
    （`RemitGovTreasuryHandler.java:27-59`、`:92-103`）；它**明确非 GmOnly**，可被省份决策人嵌进 `sd.IssueDirective`（类注 `:57-59`）。
    DM 经裁决时还有更严格的一层：源必须是自己的 GOV、目标必须是源的 `superiorGov`、源/目标坐标必须等于当刻有效位置（`AdjudicateTickTool.java:536-585`）。
  - 已有替代三：税/徭役。`unit.SetTaxRate`（`[0,1000]‰`）与 `unit.SetJurisdiction` 的辖区/单命令 levy 上限（`Jurisdiction.java:40-45`；`UnitOperations.java:382-425`）；
    人力可通过 `simos.gov.dispatchTeam`（GOV 出人建纯人员单位，`GovDispatchTeamTool.java:30-47`）+ `simos.gov.absorbUnit`（吸收进另一 GOV，`GovAbsorbUnitTool.java:30-46`）两跳近似——两条工具各自一条 revision，**不原子、不守恒成一条**。
  - 没有的：义务/自治度/期限/违约/拒贡/册封废立/否决外交/驻军权；也没有 Nation↔GOV 的正式隶属（只有 `Army.masterGovUnitId` 与 `Nation.homeRegion` tag 两条弱链）。
- **现状可用 workaround（当前世界"称臣纳贡"最小可跑通版）：**
  1. 用 `unit.SetGovSuperior` 把附庸中央 GOV 的上级指为宗主中央 GOV（当前世界的 `大蜀北谷占领区-gov` 已经上级 `大蜀-gov-central`，等于已经有一个 GOV 级"臣属"样本）；
  2. 用 `simos.gov.remit`（GM，任意两 GOV）或附庸自己的 `actor.RemitGovTreasury` 令（DM，限自己→superiorGov）按期上缴粮/布/银；
  3. 人力朝贡用 `dispatchTeam` + `absorbUnit` 两跳；
  4. 用 `sd.PutInfo` 在两国地址记"称臣/朝贡"叙事 + 期限；周期可用 `sd.RegisterEffect` 的 `at_or_after_tick` + `EnqueueUnitCommand(actor.RemitGovTreasury)` 近似，但**跨模块命令当前不随 advance 落盘**（见 C6），所以"自动朝贡"仍缺最后一脚。
  边界：GOV 隶属会进入 `GovTerritory`/`NationSummary` 的显示聚合，但**不进入任何授权判定**（`GovScope.java:31-33`）；它不产生"附庸"语义，也不给宗主任何对附庸 GOV 的处置权。
- **若实现（6 类缺口逐条落点）：**
  1. **附庸关系实体**：最小可行 = 不做新实体，直接采用 GOV 级 `superiorGov` + 一个新 `sd.VassalRelation`（`vassalNationId,suzerainNationId,autonomyPerMille,obligation,tributeTerms,fromTick,untilTick`）。
     落在 `simos-sd/model` + `SdState` 第 11 组件 + `SdChangeSet`/`SdCodec`（iron law 5 全套）。若只想解锁当前世界，**最小子集是 GOV 级 `superiorGov`，零领域改动**。
  2. **朝贡支付**：粮/布/银直接用 `actor.RemitGovTreasury`；周期/上限/违约/拒贡需要新的 `TributeTerm`（周期天数、逐资源上限、宽限）与一个"到期执行"路径（`sd.RegisterEffect` 的 SCHEDULED 已能定时，缺 drain，见 C6）。人力朝贡需要新的"人口/编制转移"原语或在 app 层把 dispatch+absorb 合成一条批工具。
  3. **宗主权利**：现行 GOV 层级**不授予**任何跨 GOV 权限（`GovScope` 只授本级 jurisdiction，`GovScope.java:99-126`）；要"抽调兵/设税/驻军/否决策封"必须：(a) 关系实体 + (b) 在范围函数里加一条"宗主对附庸 GOV 的显式授权"（新 scope 或扩 GovScope），否则只能 GM 代做（`simos.gov.remit`、`unit.SetTaxRate` GM 窄写、`unit.PlaceAt`、`simos.gov.createOffice` 等）。
  4. **决策人接口**：受 C1 同一条 `sd.*` 自指禁令限制——新 `sd.*` 附庸/贡赋命令不能嵌令。最小方案：新 DM 窄工具 `sd.IssueDiplomaticAction`（见 C1），或让"纳贡"继续只走已可嵌的 `actor.RemitGovTreasury`（这条已通）。
  5. **GM 工具**：新窄写工具放 `simos-app/.../tools/write/`，在 `SimosToolSource.addGmWrites`（`:540` 段）注册；组合动作（建关系 + 设上级 + 首期上缴 + PutInfo）建议照 `GovCreateOfficeTool` 用 `submitBatch` 一条 revision；catalog hint 同步 `CatalogTool.java:295-360`。
  6. **读口/GUI**：先补 C3 的 `simos.sd.nations/armies`，再加 `simos.sd.relations`（GM-only 首版）；GUI 地图按"宗主/附庸/占领"分层着色，tag `Nation`（占领）与 `nation:<id>`（国家）必须区分（当前世界两者并存，见 C5 世界事实）。
- **最小可行子集（解锁当前世界大蜀国策）：** GOV 级 `superiorGov`（现成工具）+ `simos.gov.remit`/DM 上缴令（现成）+ `sd.PutInfo` 叙事 + 补 C6 的 cross-module drain（让 SCHEDULED 朝贡真落盘）。这套不需要新领域实体，能表达"称臣/按期纳贡"，但**表达不了自治度、义务、违约、宗主权利**——那些要等第 2/3 批。
- **依赖与风险：** 若把附庸关系放进 `sd.Nation` 而行政仍走 GOV，就会出现"关系说 A 附庸 B、GOV 层级说另一套"的双真相；必须先裁定外交主体与行政主体的绑定（例如给 `Nation` 加 `centralGovUnitId`）。GOV 层级改动会影响 `NationSummary`/`GovTerritory` 的显示聚合。
- **未核实项：** `GovTerritory`/`NationSummary` 当前在 main 里**没有生产调用方**（只被测试调用，grep main 仅注释命中）——若作为附庸读口要先把它们接上；未验证 `simos.gov.remit` 的 DM 令在宗主方向上的完整往返（本轮只读，没跑）。

---

## C3. `sd.Nation` / `sd.Army` MCP 读口缺失

- **结论：部分具备（有 `simos.state.resolve` 单点解析，且对 GM 有效；缺 Nation/Army 清单与详情读口；GUI 也没有 nation/army 视图；清单里的"resolve 无候选"是地址语法误判）。**
- **证据：**
  - 解析面支持 nation/army：`SdResolver.java:65-88` 的 `case "nation"/"army"` → `rootOnly(..., state.nations()/armies()::containsKey, ...)`；canonical 形态 `sd:nation.<id>` / `sd:army.<id>`（`:33-37`）。
  - MCP 读口 `simos.state.resolve` 走 `QueryService.resolve` 后逐候选过 `ToolSupport.subjectVisible`（`StateResolveTool.java:60-84`）；`sd` 分支要求 `allows(READ, ResourceId.of("sd","<kind>/<id>"))`（`ToolSupport.java:317-330`）。
    GM/MCP 上下文 sd=unlimited（`Shell.java:1031-1062`，MCP 服务用 `gmCaller()`：`:799-806`），所以正确语法的 `sd:nation.大蜀` 应当可见；实测 GUI 无 `as=` 的 `/api/resolve` 返回候选。
  - **AddressParser 不以 `/` 分段**（`:14-35`、`:66-89`），所以 `sd:nation/大蜀` 会落成"根主体缺 kind"，返回空候选——这正是"未返回候选"的根因。`StateResolveTool.description()` 只举了 `map:Map1:[1,1] / unit:u-1`（`:43-45`），没有 sd 示例，容易诱导模型写斜杠。
  - 读工具清单没有 nation/army：`SimosToolSource.readTools`（`:585-650`）里只有 `simos.state.resolve`、`simos.sd.decision-makers`、`simos.sd.decision-maker`、`simos.sd.directives`、`simos.sd.combats`、`simos.sd.verdicts` 等。
  - `simos.sd.decision-makers` **只列 DM**：`DecisionMakersTool` 描述即"决策人清单"（`DecisionMakersTool.java:28-71`），底层 `SdQueryService.listDecisionMakers` 只遍历 `sd.decisionMakers()`（`SdQueryService.java:110-121`）；它顺带解析 affiliation 的 Nation/Army 显示名（`:227-273`），但没有独立清单。
  - GUI 也没有 `/api/sd/nations|armies`：`GuiServer.GET_ROUTES`（`:269-299`）只有 `/api/sd/decision-makers|directives|verdicts|combats|decision-results|decision-docs`。
  - 附带发现（真缺口/越权面）：GUI `/api/resolve?as=<dm>` **只做字段级 redaction、不做资源范围过滤**（`GuiServer.java:577-584` + `:755-765`），实测以别国 DM 视角仍返回 `sd:nation.大蜀` 候选；MCP 侧有 `subjectVisible` 过滤（DM 的 sd 范围只有 `decision-maker/<自己>`，`DecisionCallerFactory.java:248-262`）。同一读口两边语义不一致。
- **现状可用 workaround：** 用 canonical 点号地址 `simos.state.resolve("sd:nation.大蜀")` / `sd:army.<armyId>` 确认身份存在；再从 `simos.sd.decision-makers` 的 affiliation 解析字段间接看 Nation/Army 显示名；地块归属用 `simos.map.hex` 的 `nation` 字段（tag 派生）。查不到 name/homeRegion/adminBudget/rootUnit/masterGov 的独立详情。
- **若实现（最小改动面 + 共享视图）：**
  1. `SdQueryService` 加 `listNations(QueryTarget)` / `listArmies(QueryTarget)`（直接遍历已 import 的 `SdState.nations()/armies()`，同 `listDecisionMakers` 的排序与 fail-closed 口径）。
  2. `ApiViews` 加 `nations(List<NationInfo>)` / `armies(List<ArmyInfo>)`（GUI 与 MCP 共用一份形状，遵守 AGENTS §8.3"GUI 与 MCP 共用 ApiViews"）。
  3. 新读工具 `SdNationsTool` / `SdArmiesTool`（`simos-app/.../tools/read/`），在 `SimosToolSource.readTools` 注册；首版标 `GmOnlyRead`（理由：DM 的 sd 范围只有自己，若做四桶共享必须逐条按 `unitVisible`/`regionVisible` 过滤，否则会越过 DM 范围）。
  4. 可选 GUI 路由 `GET /api/sd/nations|armies`（与工具共用 ApiViews；GUI 无 `as=` 语义，或沿用 `rejectAs` fail-closed 口径）。
  5. 测试面：AGENTS §8.3 点名的 `SimosToolsTest`/`McpServerTest`/`McpPortTopologyTest` 三处工具名单断言要加两条；`SdQueryService`/`ApiViews` 的用例按其既有风格补（本报告不写测试）。
  6. 顺手修 `StateResolveTool.description()`，补 `sd:nation.大蜀` / `sd:army.<id>` 示例，并明确"斜杠不是分隔符"。
- **依赖与风险：** `ApiViews` 现在是 5000+ 行的大文件（grep 行号已到 5188+），新增视图要保持"同资源的两个形状"不再生。
- **未核实项：** 未直接经 MCP 5727 调 `simos.state.resolve`（只做代码路径 + GUI GET 佐证）；MCP 运行的 jar 是 `7577a399`，与当前工作树 `aff48be9` 可能不同——读口差异需在实际部署版本上复核。

---

## C4. `sd.DeleteNation` 缺失

- **结论：确认缺失。`sd.CreateNation` 对已存在 id 明确拒绝，但没有任何删除 Nation 的命令/工具，也没有级联策略。**
- **证据：**
  - `CreateNationHandler.java:48-50`：`if (base.nations().containsKey(id)) return Rejected("国家已存在: " + id)`。
  - 命令注册面没有 `sd.DeleteNation`（`Shell.java:520-570` 只有 CreateNation/CreateArmy/SetArmyMasterGov/CreateDecisionMaker/DeleteDecisionMaker/PutInfo/…）；世界 `command_catalog.txt` 78 types 同上。
  - 删除一个 Nation 会牵动（但都没有现成级联）：
    - DM：`Affiliation.Nation` 进 `DecisionMaker`（`Affiliation.java:29-37`），创建期强校验 Nation 存在（`CreateDecisionMakerHandler.java:83-87`），但 **`SdState` 构造期不校验 affiliation 目标存在**（`requireReferentialIntegrity` 只管 directive/effect/verdict/combat/loss，`SdState.java:290-356`）；
    - Scope：`NationScope` **不查 sd.nations**，只按 `nation:<id>` tag 扫 region（`NationScope.java:64-80`）⇒ 删 Nation 后悬空 DM 仍按 tag 看得见地盘；
    - 地图：`HexOwner.nationsOf` 也只认 tag（`HexOwner.java:58-68`）⇒ 地图归属不会因删 Nation 而消失；
    - 保护方向相反：`RegionDeleteGuard` 用 `Nation.homeRegion` 挡删 region（`RegionDeleteGuard.java:60-68`），删 Nation 反而"解锁"；
    - 文档：`SdInfoEntry.affiliations` 允许挂 Nation（`PutInfoHandler.java:78`、`:101-112`），删除后同样悬空；没有 DeleteInfo/UpdateInfo 命令。
  - 级联先例是"具名拒绝、不静默级联"：`DeleteDecisionMakerHandler` 只删身份，若仍被 Directive 引用就拒并点名（`:26-38`、`:60-71`）；`RegionClearStructuresPlan` 也预告这种批会整批零 revision（`:620-634`）。
- **现状可用 workaround：** 无删除命令；只能"冻结"（`sd.PutInfo` 标记、删/改 `nation:` tag 让地图归属消失，再 `RegionDeleteGuard` 不再保护 region）；`sd.DeleteDecisionMaker` 可删无 directive 历史的 DM。
- **若实现（建议）：**
  - 命令 `sd.DeleteNation`，载荷 `{nationId}`（首版）；handler 标 `GmOnlyCommand`（与 `DeleteDecisionMakerHandler` 同制），放 `simos-sd/.../spi/`；注册 `Shell.java:520-570`；catalog hint `CatalogTool.java:308`。
  - 校验顺序：nation 存在（否则具名拒）→ 仍被 DM affiliation 引用 ⇒ 拒并点名（先处置 DM）→ 仍被 `sd.info` affiliation 引用 ⇒ 拒（没有 info 删除/改写面）→ 目标仍是任何带 `nation:<id>` tag 的 region 的归属/`homeRegion` ⇒ 拒，指路先 `map.UpdateRegion` 清 tag。
  - **不推荐首版做级联**：级联删 DM 会撞 `DeleteDecisionMaker` 的 directive 历史守卫；级联改 map tag 需要跨模块写（sd 不能写 map，铁律 3）；完整级联要 app 层复合工具 + 新"Info 改挂/删除"能力，成本远大于收益。
  - 窄工具：`SdDeleteNationTool`（app/tools/write，GM 桶，`addGmWrites`）；`command.submit` 也能用（handler 注册后自动进 catalog types）。
  - 铁律 5：Nation 本身已在 10 组件里，删除只动 `nations` 一条 ⇒ 无新增组件；若 C1/C2 的关系实体引用了 Nation，删除校验必须一并覆盖（否则关系悬空）。
  - 前端/API：GUI 区域/国家面板加"删除"入口（或仅 MCP）；`ApiViews` 不需要新形状（清单是 C3 的）。
- **依赖与风险：** 删除语义必须用户裁定——"删 Nation 是否等于放弃对 tag 领土的主张"在现行模型里答案是否定的（归属来自 map tag）。若不显式说明，GM 删完 Nation 会看到地图仍显示其国名归属（因为 tag 还在），这是最容易误解的一处。
- **未核实项：** 未在真实世界执行/空跑删除（只读）；未验证 `map.UpdateRegion` 清 tag 后 `RegionDeleteGuard`/`HexOwner`/GUI 的联动表现（代码路径明确，但没有运行样本）。

---

## C5. 中央政府 DM 看不见全国（视野/范围函数）

- **结论：部分具备。现行 Gov 归属中央 DM 确实只直辖首都圈（当前世界 7 格）；但 NationScope 已存在并可零领域改动地建 Nation 归属 DM，能覆盖 `nation:<id>` 大区（大蜀 323 格）。清单把"有 NationScope"写成"若有"、把 resolve 说成无候选，都是过时/误判；占领区 tag=`Nation` 确实进不了 NationScope。**
- **证据（代码）：**
  - `GovScope` 的可见范围 = 自己所在格 + `Unit.jurisdiction` 的 **taxRatePerMilleByRegion key 集**（`GovScope.java:99-126`）；沿 `superiorGov` 聚合下级 GOV 的 `GovTerritory` **只用于显示、明令不得进授权判定**（`GovScope.java:31-33`；`GovTerritory.java:24-25,48`；`NationSummary.java:24-29`）。
  - `NationScope` 扫 `GameMap.regions()`，取 `region.meta().tag()` **逐字等于** `nation:<nationId>` 的 region，给 region 级 map 前缀 + 逐格 social 前缀 + 落在国内的单位（`NationScope.java:64-99`）。
    map 命名空间**只给 region 前缀、不给逐 hex 前缀**（`:70-80`），而 `GovScope` 明确补了逐格 hex（`GovScope.java:121-125`）——因此 Nation DM 对"目标声明含 hex 路径"的命令（如 `map.UpdateRegion` 的 `targetPaths`）会在资源判定上被拒；读侧走 `ToolSupport.hexVisible`（hex 或所属 region 可见即可，`ToolSupport.java:260-274`）所以读得到。
  - `ArmyScope` = `Army.rootUnit` 的当刻有效位置 + `visionRadius` 圈（`ArmyScope.java:62-99`）；半径来自 unit 字段 `Unit.visionRadius`，默认 `DEFAULT_VISION_RADIUS = 1`（`Unit.java:64`，构造器 `:165`），当前无 `unit.SetVisionRadius` 命令（catalog/handler 均无；`UnitOperations` 只在拷贝时原样带过）。
  - `sd.SetDecisionMakerAccess` 只能收紧：`AccessLimit` 是"与范围函数现算结果做交集"（`AccessLimit.java:11-16`），装配点 `DecisionCallerFactory.resourceScopesFor(...).narrowTo(toResourceScopes(dm.accessLimit()))`（`DecisionCallerFactory.java:248-262`）。
  - 三个内置范围函数确实都注册了：`DecisionScopeFunctions.defaults()`（`DecisionScopeFunctions.java:46-53`）；且 `resourceScopesFor` 会把 sd 命名空间**覆盖成** `decision-maker/<自己>`（`:256-261`）⇒ DM 无法用 `simos.state.resolve` 读别国 Nation。
- **证据（当前世界，GET 实测）：**
  - `/api/sd/decision-makers/大蜀-gov-central-dm/scope`：visible.regionIds=`[大蜀__CAP]`、hexCount=7、unitIds 3 个；西陵中央 DM 同样 7 格（`以西陵__CAP`）。
  - `/api/map/regions/summary`：region `大蜀`=323 hex tag=`nation:大蜀`；`西陵`=126 hex tag=`nation:西陵`；`大蜀北谷占领区`=82 hex tag=`Nation`（**不是** `nation:大蜀`）。
  - checkpoint `store/checkpoints/main/100.json` 的 map 层：`大蜀` region 的 323 格覆盖 `大蜀__CAP` 全部 7 格与 `大蜀__P*` 并集 316 格；占领区 82 格与 `大蜀` region **0 交集**。
  - `/api/sd/decision-makers`：46 个 DM 全 `affiliation.kind=gov`，没有 Nation/Army 归属 DM。
- **现状可用 workaround（按代价从低到高）：**
  1. **新建 Nation 归属 DM（零领域改动，立刻可做）**：`sd.CreateDecisionMaker {id, affiliation:{kind:"nation",id:"大蜀"}, allowedTools:[...], cadence}`。大蜀 Nation 已存在（GET resolve 可证），Nation DM 将看到 `nation:大蜀` 的 323 格核心区（含首都圈与各省，因为大 region 覆盖它们），但看不到 82 格占领区；且 actor 命名空间显式 `none()`（不能发 `actor.RemitGovTreasury`），sd 命名空间只有自己。
     - 想看占领区：`map.UpdateRegion` 把 `大蜀北谷占领区` 的 tag 从 `Nation` 改成 `nation:大蜀`（meta 是整体替换，四键给全，`MapUpdateRegionTool.java:12-14`），或新建一个覆盖 82 格的 overlay region 并打 `nation:大蜀` tag（region 重叠允许，`UpdateRegionHandler.java:35-45`）。
     - 想管钱：GOV 侧 DM 仍需保留（或接受 GM 代发 `simos.gov.remit`）。
  2. **扩大 GOV jurisdiction（一条命令，但改税基语义）**：`unit.SetJurisdiction(大蜀-gov-central, regions=[大蜀,大蜀北谷占领区])`。GovScope 立刻看到全部 region/hex/单位，并保留 actor 上缴能力。
     代价：`JurisdictionDailyTax`（`JurisdictionDailyTax.java:133-147`）与 class-first 日结算（`ClassFirstPopulationEconomyTimeParticipant.java:435-438`）会按 key 集收税；`GovRecruitPlan`/`GovApplyStaffingTool`/`LevyRegionPlan` 的辖区口径也会变；新增 region 的税率初始 0（保留区域旧值，`UnitOperations.java:410-413`），需事后把新增 region 税率钉 0 以减轻双收。中央与省 GOV 辖区重叠是合法数据但会模糊"谁收税"。
  3. **改范围函数（长期正确，代码改动）**：给 NationScope 补逐格 map 前缀（对齐 GovScope 的修复动因）、并让它把"占领区/次级 region"按显式归属纳入（需要 `Nation`↔region/GOV 的正式归属，而不是现在只认一个精确 tag）；或新增 `RealmScope`。代价：授权面代码 + 测试（`NationScopeTest`/`ScopeFenceTest`），且要守住"显示派生不得进授权"（`GovTerritory.java:24-25`）。
  4. 不要试图用 `sd.SetDecisionMakerAccess` 放大：它只做交集（代码如上）。
- **依赖与风险：** Nation DM 与现有 Gov 中央 DM 是两套身份；若同时存在，外交/行政会分裂成两个"中央"（除非删除旧 DM，而 `DeleteDecisionMaker` 又被 directive 历史挡）。NationScope 不校验 Nation 存在，所以"先改 tag 再看"也会给悬空 DM 生效（见 C4）。
- **未核实项：** 未实际创建 Nation DM（只读）；NationScope 的 323 格数字由 region 汇总+checkpoint 推导，未逐格核对 head 150 的 region 内容（head 150 无 checkpoint，只有 head 150 的 region 汇总读口）。

---

## C6. 无自动事件/紧急响应触发器

- **结论：部分具备（且清单的描述偏旧）。触发器/状态机的一半已经存在：advance 会自动跑 sd 效果与战斗阶段 exit；缺的是 DM 自动运行、跨模块效果落盘、事件/通知队列，以及"自动裁决"。**
- **证据（`simos.advance` 到底做什么）：**
  - MCP 工具 `AdvanceTool` 与 GUI `/api/advance` 都只构造 `AdvanceTime` 并 `core.submit`（`AdvanceTool.java:103-121`；`GuiServer.java:1421-1437`）。
  - `TimeAdvance` 依次调每个 `TimeParticipant.simulateWorld`（`simos-core/.../advance/TimeAdvance.java:218-224`）；装配的参与者只有 unit / sd / class-first 经济（`Shell.java:633-640`）。**不跑 DM/LLM、不跑 `sd.AdjudicateTick`、不审批**。
  - `SdTimeParticipant` 在推进内部**逐日**：effect `PLANNED|COMMITTED` 且 trigger 命中 ⇒ `FIRED`，并内联执行 `PutInfo`/`SetStage`；同时按 `CombatStage.exit` 自动推进阶段（`SdTimeParticipant.java:94-120`、`:122-157`、`:164-211`）。
- **证据（缺口）：**
  - 跨模块 `Action.EnqueueUnitCommand` 只被 `SdCommandDrain` 消费（`SdCommandDrain.java:46-88`）；而 `Shell.advanceAndDrain` **在生产代码与测试里都没有调用方**（grep `advanceAndDrain|drainAfterAdvance` 只命中 `Shell.java:1121-1132` 的定义与 `SdCommandDrain` 自身，加 4 处 test 直调）；两条生产 advance 路径都不 drain。⇒ SCHEDULED 效果会 `FIRED`，但 `unit.*`/`actor.*` 入队命令永不下发（除非有人手动调 `shell.sdCommandDrain().drainAfterAdvance(branch)`）。
  - `Action.RecordCasualties` 没有任何执行者：`RegisterEffectHandler` 接受它并只校验引用的 LossRecord 存在（`:120-124`），`SdTimeParticipant.applyAction` 的 switch 只处理 PutInfo/SetStage，其余落 `default -> {}`（`:210`）⇒ **FIRED 后静默无动作**。
  - `EffectKind` 只被原样拷贝（`SdTimeParticipant.java:112`），没有任何分支消费：ON_CALL/BE_PREPARED/BRANCH/SEQUEL 是死标签；`EffectStatus.COMMITTED/EXPIRED` 没有任何写入方（grep 只命中读取判断），状态机不完整。
  - 事件模型不存在（对 DM 而言）：core 的 `EventTypes` 是 7 个冻结审计事件（`simos-core/.../observe/EventTypes.java:24-61`），没有消费者/通知投递；没有优先级队列/紧急事件实体（`PriorityQueue` 只命中寻路）。DM 每轮只读世界状态；`sd.PutInfo`（可带 `tags=[dmId]`/`affiliations`）是当前最接近"通知"的通道，由 `DecisionDocsTool`/`DecisionResultsTool` 按 tag 读。
  - "自动筛选 DM"被明令禁止：`RunDecisionMakersTool` 类注写"不做自动筛选/自动派出（用户 2026-10-01 裁定 8）"，`due` 只展示（`:46-67`、`:100-103`）。⇒ 自动响应不只是没实现，还与现行用户裁定冲突。
- **现状可用 workaround（清单里的手工编排成立）：** n tick 出令 → `sd.AdjudicateTick(n)` → `simos.advance(n→n+1)`（sd 效果自动 FIRED，PutInfo/SetStage 自动落；跨模块命令不落）→ `simos.sd.run-decision-makers(显式名单, preview=false)`（同时刻快照、逐人真 LLM）→ GM 在 GUI/审批面批准各自 `sd.IssueDirective` → `sd.AdjudicateTick(n+1)`。免税恢复税率这类 `EnqueueUnitCommand` 效果目前**必须再手工**把命令抄出来提交，或接受"effect 只停在 FIRED"。
- **若实现（最小自动化组件）：**
  1. **把 drain 接回生产路径**：让 MCP `AdvanceTool` 与 GUI advance 走一个"advance+drain"门面（`Shell.advanceAndDrain` 已有，但只返回 drain 结果、要改成返回 advance+drain 组合）；或在 app 加一条 GM 工具 `simos.advanceAndDrain`。这是"定时朝贡/到期恢复税率"的**最小必要修复**。
  2. **补 action 执行**：`RecordCasualties` 要么实现（把 LossRecord 挂进对应 CombatState.losses），要么在 `RegisterEffectHandler` 命令期直接拒——当前"接受但不执行"是最坏形态。
  3. **"n+1 紧急回应"自动触发**：新的 app 层 `DecisionDueRunner`（after-advance hook）：用现有 `SdQueryService.pending()` 的 due 公式（`SdQueryService.java:338-370`）选人，写一条带 tags/affiliations 的"事件"InfoEntry，然后按 `RunDecisionMakersTool` 的同款两步走（触发批 + 逐轮）跑 DM。依赖：必须重裁 2026-10-01 裁定 8（否则与自动筛选禁令冲突），并定"DM 出令是否仍需人工批准"。
  4. **真正的事件模型**（若"条件反射/优先级"是硬需求）：新 sd 组件 `events` + 触发器扩展 + 消费游标；这是比 1-3 大一个量级的新设计。
- **依赖与风险：** drain 是"提交后再提交"、跨 revision，中途失败留中间态（`SdCommandDrain.java:31-33` 自认非原子）；自动跑 DM 会放大 LLM 成本与审批阻塞（见 C7 的 5 分钟超时），且世界服务里并发 run 会抢 revision。
- **未核实项：** 未运行 `advance`/效果链（只读）；`Shell.advanceAndDrain` 是否被 CLI/GUI 的某个反射/脚本入口调用未穷举（grep 全仓源码 0 命中，但世界里的旧 runner 脚本可能直接调私有面，未查 `simos-testspace/scripts`）；未验证 `RecordCasualties` 是否在别处有非 Java 消费者。

---

## C7. DM 出令审批链

- **结论：已具备等价能力（审批链完整、可观测、可裁决）；清单说的"可能停在 pending、run-decision-makers 不代批"都是事实，但不是缺口，而是设计口径。**
- **证据（路径）：**
  - DM 的 `sd.IssueDirective` 是敏感工具：`AbstractNarrowWriteTool.gate()` 对每次调用返回 `ToolGate.Ask(SENSITIVE)`（`AbstractNarrowWriteTool.java:175-176`）；`IssueDirectiveTool` 覆写资源声明为"只写自己的决策域"（`IssueDirectiveTool.java:65-83`）。
  - 决策人链 = `AutoApproveGate → ConfirmGate`：`Shell.java:683-690` 构造 `new ApprovalCoordinator(List.of(new AutoApproveGate(pendingApprovals), new ConfirmGate()), ...)`；`ConfirmGate` 永远 empty（AgentLib `ConfirmGate.java`），编排器于是把请求登记进 `PendingApprovals` 并阻塞等人，超时 = DENY；`APPROVAL_TIMEOUT = 5 分钟`（`Shell.java:212`）。
  - 谁批：GUI 审批页/`POST /api/approvals/{id}`（GUI 是透传代理，`GuiServer.java:188-189`、`:1925-1945`）；MCP `simos.gm.approve`（`GmApproveTool.java:53-161`，标敏感写、只加在 GM 桶）；待批清单 GUI `GET /api/approvals` 或 MCP `simos.gm.approvals`（`GmApprovalsTool.java:32-87`，GmOnlyRead，直接读同一份 `PendingApprovals`）。
  - `simos.sd.run-decision-makers` **不代批**：类注明写"本工具不在这里提交 sd.IssueDirective 或任何其他命令"，运行轮产出仍走既有链路与人工审批（`RunDecisionMakersTool.java:46-67`；description 同义）。
  - GM 面（MCP）自己的写走另一条链 `GmAutoApproveGate` 无脑过（`Shell.java:693-701`；`GmAutoApproveGate.java:9-27`），所以 `simos.gm.approve` 自己不会再被审批卡住。
- **现状可用 workaround（操作手册）：** GM 触发 run-decision-makers 后，必须并行在 GUI 审批页或另一条 MCP 会话里 `simos.gm.approvals` → `simos.gm.approve`；若用 GUI 的 `/api/sd/run-decision` 异步入口，审批页可同时操作。5 分钟不批即 DENY。
- **若实现（若要"自动代批"）：** 不建议把 `run-decision-makers` 改成代批（那会消掉 GM 点头这条边界，且需要改 AgentLib 链）；若要"预授权某类令"，正确落点是 **classKey 粒度**的会话级批准（`AutoApproveGate` 读 `PendingApprovals.isSessionGranted(callerKey,classKey)`，AgentLib 源码），但本仓 callerKey 是桶名 `DEFAULT`，AgentLib 对 `DEFAULT` 桶**不授予 session**（会收窄成 once，`GmAutoApproveGate.java:19-22` 注释；Shell 注释同）⇒ 目前没有安全的"DEFAULT 桶会话放行"面。要自动化，需先做 AgentLib 的身份实例级（非桶级）审批键，或用户明示接受"自动化模式下 DM 出令免审批"。
- **依赖与风险：** MCP 的 `run-decision-makers` 是同步阻塞调用，DM 的 IssueDirective 会阻塞在审批上最多 5 分钟/人；同一 MCP 客户端若无并发通道，会出现"工具调用没返回、审批也没法用同一口批"的假死。GUI 可并行，但批量 46 人时会连锁超时。
- **未核实项：** AgentLib 的实际实现读的是本机 `~/ProjectMosire/AgentLibMosire`（未在本仓，版本可能漂移）；未在真实世界跑一轮审批（只读）。

---

## C8. 多边谈判/联动裁决缺失

- **结论：部分具备。没有"多方同时谈判/条件反射"协议；但已有两个可拼的原子原语：固定 revision 的批量派发 + 同一 tick 全 DM 令的原子裁决。**
- **证据：**
  - `Directive` 是**单决策人**记录：`decisionMakerId` + `tick`（`Directive.java:28-37`）；R4 只允许同一 `(dm,tick)` 重写、末位生效（`Directive.java:18-21`；`SdState.requireAtMostOneActiveDirective` `:257`）。
  - 批量触发是"同 base revision"：`RunDecisionMakersTool` 先 `submitBatch` N 条 `sd.RunDecision`（一条 revision），再把同一个 `triggerRef.revision()` 传给每个 `runRound`（`:398`、`:505-527`、`:575-585`）。因 `DecisionAgentService.runRound` 用 `core.replay(new StateRef(branch, revision))` 固定快照（`:188`），**各 DM 看到的是同一份谈判前世界**；谁先写谁后写不会自动进入别人的上下文（除非 DM 自己刷新 revision）。
  - 联动裁决是"同 tick 一个批"：`AdjudicateTickTool` 取该 tick 所有参与令，构造 commands + per-directive status flips + 一条结果 info，`core.submitBatch` 原子落一条 revision；某条命令被拒只把它剔出批并重试收敛（`:334-405`）。⇒ 多方在同一 tick 的结果可以一起落，但**没有条件触发/响应语义**，只是共批。
  - 没有谈判/协议/事件实体：SdState 10 组件（`SdState.java:53-63`）无 session/negotiation/message；DM 的会话只在 AgentLib 的 `conversations.db` 旁路（`DecisionAgentService.java:50-51`），不互读。
- **现状可用 workaround：** GM 用 `sd.PutInfo`（tags/affiliations）把"谈判背景/他方条件"发给多方 → `simos.sd.run-decision-makers` 同 revision 批量跑 → 各方出令（各需审批）→ `sd.AdjudicateTick(n)` 一次落盘；如要"看到对方后再表态"，可用 R4 重写：对同一 `(dm,tick)` 再跑一轮/再出令，旧版自动 `SUPERSEDED`，最后只裁最新版——这是当前唯一的"两轮谈判"近似。边界：每轮都要人批、没有自动消息、没有条件分支、没有截止窗口。
- **若实现（最小落点）：**
  1. **短程（不新增领域实体）**：加一条 GM 组合工具 `simos.sd.negotiate`：输入 `{participants[], contextInfo, tick, rounds?}`，内部顺序执行"写共享 context（PutInfo）→ RunDecision 触发→逐人跑轮→AdjudicateTick"；把"多方"做成显式编排而不是新协议。前提是 C6 的自动运行/审批策略先定。
  2. **中程（事件模型）**：新 sd 组件 `events`（`EventId, kind, source, targets, payload, createdTick, deadlineTick, status`）+ `Action.RaiseEvent` + 触发器 `EventArrived`；`AdjudicateTick` 同时消费事件。这是"条件反射/联动结果"的最小结构性组件。
  3. **审批面**：多方谈判若还要人工逐令批准，原子性只到"裁决"那一步；要原子到"提案+回应"需要审批策略变更（见 C7）。
- **依赖与风险：** `DecisionAgentRunner` 的同一轮里工具调用写 world 会串 revision；并发 `runRoundsConcurrent` 只保证各人独立、不保证顺序（`:531-570`），多方同时写同一目标会冲突；`AdjudicateTick` 的"剔命令重试"语义是"部分生效"，不是谈判协议里的全有全无。
- **未核实项：** 未跑多 DM 并发（只读）；R4 重写作为"谈判轮"没有端到端样本，语义上成立但未验证 DM 是否会在同一 tick 愿意重写。

---

## 本线建议的实现批次与优先级（按对当前世界大蜀国策的解锁程度排序）

**第 0 批（零领域改动，立刻解锁"中央看得见、读得到"；建议先做）**
1. **C3 最小读口**：`SdQueryService.listNations/listArmies` + `ApiViews.nations/armies` + `simos.sd.nations`/`simos.sd.armies`（首版 GmOnlyRead）+ `StateResolveTool` 描述补 `sd:` 示例。
2. **C5 workaround 固化**：写一页操作口径——"要全国视野就建 Nation 归属 DM（当前 323 格）；占领区要么 retag 成 `nation:大蜀`，要么加 overlay；GOV 侧 DM 继续管钱"；如选扩大 jurisdiction，先钉新增 region 税率 0。
3. **C6 第一步 drain 修复**：把 `SdCommandDrain` 接回 advance（改 `AdvanceTool`/GUI/新增 advanceAndDrain 工具），让 SCHEDULED 的 `EnqueueUnitCommand` 真能落。
   ⇒ 解锁：国策 2 的"免税到期恢复"、国策 1/3 的定时军令；解锁机制：n→n+1 编排的最后一脚。

**第 1 批（解锁"称臣纳贡"最小语义）**
4. **C2 最小子集**：先只用 GOV `superiorGov` + `actor.RemitGovTreasury` + `sd.PutInfo` 跑通 3 个月朝贡；再决定是否加 `sd.VassalRelation`（第 11 组件，iron law 5 全套 + 关系读口）。
5. **C6 第二步 DM 自动触发**：`DecisionDueRunner`（after-advance hook，按 due 选人）+ 明确的审批策略。**必须先请用户重裁 2026-10-01 裁定 8**（自动筛选禁令）。
6. **C7 运行手册**：把 5 分钟超时/并发审批写进 GM 工具描述与 docs；先不改链。

**第 2 批（解锁"逆我者亡"）**
7. **C1 外交领域**：`DiplomaticRelation` 组件 + `sd.SetDiplomaticRelation`/`sd.IssueUltimatum`/`sd.RespondUltimatum` + 新 DM 窄工具 `sd.IssueDiplomaticAction`（因为 `sd.*` 自指禁令，不能直接嵌令）+ GUI/读口。
8. **C4 `sd.DeleteNation`**：严格拒绝式（引用未清就拒），作为外交/建错国的清理工具；级联留到有"Info 改挂"能力之后。

**第 3 批（长程）**
9. **C8 事件/谈判模型**：先 `simos.sd.negotiate` 编排工具，再评估 `events` 组件；没有真实需求前不建全协议。
10. **C5 长期方案**：若 Nation DM 被采纳为外交主体，再补 Nation↔GOV 正式绑定与 NationScope 逐 hex/占领区纳入（授权面改动，单独一批）。

---

## 我没做/没验证的

- **没跑任何 Maven/构建/测试**（任务硬纪律）。所有"既有测试守护"的说法只是读到了测试文件/注释，**没有执行**；`SdRoundTripTest` 等对铁律 5 的守卫是"代码里存在"，不是"本轮验证过"。
- **没直接调 MCP 5727**。`simos.state.resolve` 的"GM 可读 sd:nation"结论来自 GUI GET + 代码路径（`gmCaller`/`subjectVisible`/`SdResolver`）；运行中的 MCP jar 是 `7577a399`，与当前工作树 `aff48be9` 可能不同版本，未交叉验证。
- **没做任何世界写**。所有世界读数（head 150 / tick 120、region tag/hexCount、DM scope、DM 条数）是读取时刻的 GET 结果；期间世界 head 从日志里的 126 变成了 150，说明有别的会话在推进，世界事实会继续变。
- **"确认缺失"的覆盖面有限**：外交词界搜索只对 `simos-*/src/main/java` 做了 word-boundary/中文 grep；`command_catalog.txt` 是运行世界快照（78 types），未与本轮源码 `Shell.java` handler 清单逐条 diff；未穷举全部 100+ MCP 工具和全部 handler 的 payload 字段。
- **未验证 AgentLib 的 ResourceScope 段边界匹配实现细节**（在依赖仓库，不在本仓）；"Nation DM 的 hex 级写目标会拒"依据的是本仓 `NationScopeTest` 的断言与本仓 `GovScope` 的对照写法，没有跑一条真实 Nation DM 指令。
- **未验证 `map.UpdateRegion` 清/改 tag 对 `HexOwner`、GUI 图层、`RegionDeleteGuard` 的联动效果**（代码路径明确，但没有运行样本）；占领区改 tag 是否会让 `map.hex.nation` 显示改变，只从 `HexOwner` 代码推导。
- **未验证 `RecordCasualties` 是否在 Java 之外有消费者**（世界脚本/运维脚本未查 `simos-testspace/scripts`）；`Shell.advanceAndDrain` 的调用者只 grep 了仓库源码。
- **未做设计裁决**：附庸关系放 sd 还是 GOV、Nation↔GOV 绑定、DeleteNation 级联策略、DM 出令自动批准、C6 自动派发是否覆盖 2026-10-01 裁定 8——这些都需要用户裁定，报告中只列了选项与代价。
- **未检查前端 JS 的审批/决策全部路径**：只 grep 了 `webui` 下 approvals/审批 命中（notifications.js/api.js/modes.js），未逐页核对所有入口。
- 本报告是唯一写入的文件；调查过程中的 `/tmp` 读取产物（`rs.json`/`dms.json`/`rs2.json`）已删除，未触碰仓库工作树。
