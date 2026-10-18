# D-032 农村土地/劳动/债务：P0–P6 实施计划（2026-10-18）

> **状态：P0 控制方计划；未改 Java/XML/POM/测试、未跑 Maven、未提交。**
> **★ 总派单入口已迁至 `docs/superpowers/plans/2026-10-18-d032-rural-master-dispatch.md`。** 本文件保留为详细差距表/文件拆分/阅读覆盖附录；派 Agent 时以总派单文件 + 冻结契约 + 农业模板为准。
> **★ 阶段顺序已改**：大文件拆分从原 P5b 提前为 **P0.5，先于 P1**；本文旧 P5b 段落只作拆分清单参考，执行顺序以总派单 §6/§6A 为准。
> 本文件是**实现计划 + 代码差距表 + 责任区/写者/测试 owner + 经济大文件拆分方案**，不是实现架构账本。
> 冻结后的 tick 契约：`docs/superpowers/specs/2026-10-18-d032-rural-tick-contract.md`。
> 用户口径：`docs/superpowers/specs/2026-10-18-d032-economy-tick-redesign.md` §14–§45；交接：`docs/superpowers/status/2026-10-18-d032-rural-land-labor-debt-redesign-handoff.md`；审计：`docs/superpowers/reports/2026-10-18-d032-original-intent-audit.md`。
> 工作树：`/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`；HEAD：`de02aa3f`。
> **用户 2026-10-18 最新修正已吸收**：土地与商品是同一个市场，不是两个阶段；纯库存财富只加土地；低库存追加轮删除；土地按 hex、数值由工程定；生产方式归 production，经济运转归 economy；经济千行 Java 文件必须拆分。

## §1 当前基线与纪律

- 当前 HEAD 是 D-032 旧链：旧日序、旧工资抵物、旧抵押清算链、商品市场与土地市场分离。
- 上一轮完整门禁基线（**不是本轮实跑**）：`clean verify` rc=0；19/19 reactor；3572 tests / 0F / 0E / 7S；app 1017/0/0/7；frontend 412/412；18 子模块 SpotBugs 0；shaded-guard 8263 entries OK。证据在 `.superpowers/sdd/2026-10-17-d032-w5-6/`。
- 本轮没有跑 Maven；“设计已定”不等于“代码已实现”。
- 纪律：一个责任区一个写代码 owner；写者只写生产代码并过编译，不写/不跑测试；测试最后统一给独立测试 owner；同一文件同一时刻一个 owner；本树内一次只跑一个 Maven（`tools/mvn-lock.sh`）；不擅自 commit/push/merge/main sync。
- 文件拆分也按“生产代码只到编译过”执行；最终 `clean verify` 与变异自证留到 P6。

## §1.1 阅读覆盖与未读清单（2026-10-18 诚实记录）

**已完整读**：

- 根 `AGENTS.md`（主树与 side worktree 各一份，逐行）。
- `docs/agents/00`–`14` 全部（15 份；此前部分段落曾被工具输出截断，已按行补读）。
- `docs/AGENTS.md`、`.superpowers/AGENTS.md`。
- `NEXT_AGENT.md`、交接 `2026-10-18-d032-rural-land-labor-debt-redesign-handoff.md`、设计 `2026-10-18-d032-economy-tick-redesign.md`、审计 `2026-10-18-d032-original-intent-audit.md`。
- `docs/superpowers/specs/2026-10-02-architecture-design-source.md` 的 **D-032 U1–U23 全文**（541–769 行）。
- `2026-10-17-d032-production-execution-decision.md`、`2026-10-17-d032-production-runtime-boundary-decision.md`、`2026-10-17-d032-r2-shared-asset-minimal-design.md` 全文。

**只做过定点 grep/片段阅读、没有全文逐行读的 D-032 旧文档（实现前必须先读对应章节）**：

| 文档 | 读取优先级 | 用途 |
|---|---|---|
| `docs/superpowers/specs/2026-10-17-d032-production-runtime-migration-design.md` | P2/P3 前必读 | I1–I16、V1–V12、状态/接口/原子提交 |
| `docs/superpowers/specs/2026-10-17-d032-asset-occupancy-design.md` | P2/P3 前必读 | 资产占用/预算/回填；本轮只保留非租佃用途 |
| `docs/superpowers/specs/2026-10-17-d032-cooperative-production-design.md` | P3 前必读 | 多户参与、角色、经验、分配 |
| `docs/superpowers/specs/2026-10-17-d032-production-experience-choice-design.md` | P3 前必读 | 经验公式、解锁、主副业投影 |
| `docs/superpowers/specs/2026-10-17-d032-production-definition-v2-freeze.md` | P3/P5 前必读 | 角色/份额/经验/资格/支付物定义形状 |
| `docs/superpowers/specs/2026-10-17-d032-stage1-deviation-review-decision.md`、`...stage2-handoff-review-decision.md` | P3 前必读 | 已冻结的执行/批次语义 |
| `docs/superpowers/specs/2026-10-17-d032-wage-chain-design.md` | P4 前必读 | 工资链、劳动匹配、U15–U23 |
| `docs/superpowers/specs/2026-10-17-d032-wage-idle-barter-decision.md` | P4 前必读 | 0‰ 欠薪、闲置、抵物折价配置 |
| `docs/superpowers/specs/2026-10-17-d032-wage-settlement-simplification-decision.md` | P4 前必读 | 工资直付/欠债边界；其中抵物部分已被 2026-10-18 新裁定覆盖 |
| `docs/superpowers/specs/2026-10-17-d032-tick-wage-feedback-proposal.md` | P4 前必读 | U20 调价、逐 tick 博弈 |
| `docs/superpowers/specs/2026-10-17-d032-preselection-wage-feasibility-analysis.md`、`...employer-posted-wage-redesign.md`、`...distribution-wage-offer-proposal.md`、`...cooperative-production-proposal.md` | P3/P4 参考 | 历史提案；只取未被 2026-10-18 新裁定取代的部分 |
| `docs/superpowers/specs/2026-10-17-d032-production-line-replan.md`、`docs/superpowers/plans/2026-10-17-d032-production-line-replan.md`、`docs/superpowers/reports/2026-10-17-d032-*.md` | 参考 | 历史重规划/复核；不得覆盖 2026-10-18 用户最新原话 |
| `docs/superpowers/status/2026-10-17-d032-production-runtime-migration-result.md` 等旧状态 | 参考 | 代码背景；不据此开工 |

**未读的代码**：`EconomySettlement` 11127 行、`MarketSettlement` 11061 行等没有逐行读；P0 只做了 `file:line` 定点核实。P2/P3 写者必须先读本责任区涉及的类与调用方，不能拿本计划的片段当完整架构。

**结论**：此前“最开始没读到”的东西确实存在：我把交接的阅读顺序当成足够，漏了原始 D-032 U1–U23 与部分 `docs/agents` 的完整阅读，也没有在 P0 差距表里列出 `DefaultProductionModes`/`EconomySeeder` 的阶级→mode 硬映射。现在已补读并把剩余必读项写进上表；P2/P3 派单前按上表逐项确认，不再口头声称“都读过”。

## §2 代码差距表（回代码核过；行号以 HEAD `de02aa3f` 为准）

| # | 目标能力 | 当前代码事实 | 处置 | 批次 |
|---|---|---|---|---|
| G1 | Social 先，经济后；用上一 tick 事实 | 当前是**经济先、Social 后**：`PopulationEconomyTimeParticipant.java:664-705` 注释与调用、`:803-805` Social 才 `settleOneTick` | 改顺序；Social 结算前移到 `stepper.step(day)` 之前 | P1 |
| G2 | 单槽 `PendingFamineFacts`，只留上一 tick | 当前经济缺口 `drainPendingHouseholdGaps()` 后**同 tick 立即**交给 Social：`PopulationEconomyTimeParticipant.java:705-706,803-805`；无单槽字段 | 新增 app 侧单槽内存；t 结束存、t+1 开头取；不落盘 | P1 |
| G3 | 债务清算在市场轮最前面 | 当前市场在 `EconomySettlement.java:2171-2182`；还债在其后 `:2904-2909` | 把债务清算前移到市场轮开头；M2 先于 M3/M4 | P2 |
| G4 | 单家户两轮生产方式计算（预期最大化 → 实际最大化） | 当前只有一遍：投入/劳动队列在 `EconomySettlement.java:1634-1705`，生产在 `:1751-1752`，市场在 `:2171-2182`；第二遍不存在，也没有“同一户在两个信息集上重算目标”的契约 | 新增瞬态 `ExpectedProductionPlan`/`ActualProductionPlan`；M3 第一轮、M4 市场、M6 第二轮；两轮共用 `positionNet/positionYieldPerLabor`，第二轮只换信息集 | P3 |
| G5 | 雇佣关系第二遍锁死 | 当前 `ProductionRoster`/劳动配额每 tick 由 `LaborQueueSettlement` 重排；无“第一遍锁、第二遍只读”契约 | 复用 `ProductionRoster`/劳动配额作持久落点；M3 产锁，M6 只读 | P3 |
| G6 | 非雇佣小生产第二遍可重排 | 当前没有第一遍/第二遍之分；`LaborQueueSettlement` 每 tick 整套排队 | 抽出非雇佣小生产候选重排；失败位置转 `UNEMPLOYED/IDLE` | P3 |
| G7 | 本轮新买土地本轮不用 | 当前无土地市场，无法表达“本轮取得” | M4 土地订单成交后登记本轮取得；M3/M6/M7 都排除 | P2/P3 |
| G8 | 土地与商品是**同一个市场** | 当前 `MarketSettlement` 只处理商品订单；`MarketTable.java:89-94` 价格键 = `CommodityId`；`OwnershipStake.java:39-47` 是资产账；没有统一订单 | 商品/土地进同一订单簿/同一撮合器；土地订单引用土地块/hex，不进普通库存；删除“独立土地市场阶段”的设计与代码落点 | P2 |
| G9 | 土地数量/质量按 hex；土地价格按市场区一个价；0 合法 | 当前 `MarketTable` 没有土地价；`OwnershipStake` 有 location 但无价格 | `MarketTable` 增加区级 `landPrice`（empty=未定价，0=合法）；hex 只决定数量/质量/规模 | P2/P3 |
| G10 | 土地块稳定身份、可转移 | `OwnershipStake.idOf` 把 owner/operator/kind 编进 id：`OwnershipStake.java:147-197`；转移若改 owner 会改 id | P2 约束设计书选定稳定土地块身份/份额语义；不得靠旧 id 蒙混 | P2 |
| G11 | 债务直接拿土地抵，不挂市场 | 当前 `repayDebts` 只处理货币/商品：`EconomySettlement.java:7729-7915`；无土地腿 | 在 M2 增加土地直接转移；整块、不找零；按所属市场区地价 | P2 |
| G12 | 还债顺序按债权人财富降序 | 当前按债务共同价值升序：`EconomySettlement.java:7830,8289-8318` | 改为债权人纯库存财富降序 + 稳定 tie-break | P2 |
| G13 | 没有 Pledge/抵押/担保 | 当前有 `Pledge.java:36-67`、`EconomyLiquidationSettlement.java:117,544,1445` 自动质押/清算路径 | 删除 `Pledge` 状态/命令/Codec/清算分支；至少从农村主链隔离；Alpha 下优先整块删除 | P2/P5 |
| G14 | 债务本金只用货币 | `DebtUnit` 仍允许 `Commodity`：`DebtUnit.java:26-55`；当前租金/欠薪资本化可走商品本金 | 新农村主链只写 `DebtUnit.Money`；把商品欠款在形成时折成货币；删除/收口 `DebtUnit.Commodity` 写路径 | P2/P4 |
| G15 | 工资第一次只付货币 | `WageSettlement.java:309-363` 现金后直接 `payBarter`；`:368-377` 再成债 | 删除工资抵物腿；现金付到用完；未付直接成普通工资债 | P4 |
| G16 | 工资债是普通债 | 当前已写 `DebtSource.WAGE_ARREARS`（`WageSettlement.java:368-377`），但带 0‰ 专用条款；同日还款排除在 `EconomySettlement.java:7814-7821` | 保留普通债形状；M2 同池处理；同日排除改为“M8 在 M2 后”的结构性顺序 | P4 |
| G17 | 工资锚 = 市场区 × 生产方式 × 位置 | `WagePostingKey` 键 = `(unit, role)`：`WagePostingKey.java:12-22`；`WageDefaultKey` 键 = `(plan, role, marketZone)`；`ProductionWageBook.java:154-170` | 改/新增工资锚键；U20 调价接入市场轮；旧 unit×role posting 退出农村主链 | P4 |
| G18 | U20 调价真正进入 tick | `ProductionWageBook.adjustedPostings` 在 `:267`；main 里无调用者（审计 A1） | 市场轮读取实际劳动/需求并写回下一轮锚；测试要真实多 tick | P4 |
| G19 | 家户按纯库存财富选位置/排队 | 当前 `LaborQueueSettlement.java:314-315` 按 `HouseholdId` 升序；`SettlementOrderBook.java:203-215` 市场用净值（资产−负债） | 新增纯库存财富 `钱+货物+土地` 序；不扣债务；工具/作坊不计 | P3 |
| G20 | 纯库存财富含土地、不含债务 | `PrimaryModeRanking.inventoryMilli` 只算本格币+货物：`:225-265`；`SettlementOrderBook` 用净值 | 抽 `PureInventoryWealthBook`；生产位置/市场买方共用 | P3 |
| G21 | 市场轮只每 5 tick | `MarketSettlement.triggerFor` 还有关账日保底和低库存追加：`:1396-1421`；`MARKET_RESTOCK_INTERVAL_DAYS=5` 在 `:126` | 只保留 `day % 5 == 0`；删除 `LOW_GRAIN_STOCK`/`CYCLE_CLOSE` 额外轮 | P2 |
| G22 | 删除 35 天生活储备 | `MARKET_LIFE_RESERVE_DAYS = 5+30`：`MarketSettlement.java:132-144`；买/卖保留在 `:10074-10089` | 市场轮买盘按 5 tick 基础消费 + 生产投入；删除 30 天安全库存口径 | P2/P3 |
| G23 | 生产成本需求与外部注入需求分开 | 当前 `HouseholdDemand` 是外部注入：`HouseholdDemand.java:47-59`；市场内仍混用 `demandParts`：`MarketSettlement.java:1382-1392`；生产投入由 necessary inputs 另算 | 命名/表分开：`ProductionInputDemand` vs `ExternalDemand`；禁止再把 `demandParts` 当生产投入 | P3 |
| G24 | 农村默认无租佃/rent/lease | 当前 `AssetRightKind.TENANCY`、`LaborSource.TENANT/SERF`、`ExpectedProfitBook` 的 rent 分支、`EconomySeeder` 的佃耕 unit/300‰ 租 | 本批农村默认路径删除；保留 `ProductionRules` 通用类型但不得继续默认走 rent/tenancy | P5 |
| G25 | 无硬编码地主/中农/贫农生产方式标签 | `ProductionRole` 仍有 `RelationToMeans`/`LaborRole`；`EconomySeeder` 大量按 landlord/tenant 槽位建 unit：`EconomySeeder.java:1773-1820,5964-6022` | 生产位置由连续变量/利润选择；`SocialClassId` 只作读数/阶层投影，不作生产方式硬标签 | P5 |
| G26 | 跨 hex 耕种必须本地雇农 | 当前无土地市场与跨 hex 自耕拒绝判据；`OwnershipStake.location` 有地点但未用于该门 | M3/M6 加“地主不能用自家劳动跨 hex 耕”拒绝；必须雇土地所在 hex 雇农 | P3/P5 |
| G27 | 生产方式归 production，经济运转归 economy | 当前生产定义在 production（`IndustryDefinitionView.java:71-93`），但 economy 仍承担大量生产编排/租金/资产处置；app 也有编排逻辑 | 保持 production 的定义/实例/经验/产出权威；economy 只做市场、债务、工资、劳动匹配、土地所有权、迁移；app 只编排/桥接 | P2–P5 |
| G28 | 生产定义进步（未实现） | 用户已说“目前貌似没做出来，放着吧” | 本批不做；不阻塞农村 tick | 不做 |
| G29 | 经济大 Java 文件拆分 | 18 个 economy main 文件 ≥1000 行 + `EconomySeeder` 6089 行；最大 `EconomySettlement` 11127、`MarketSettlement` 11061（见 §5） | 机械拆分 + 终态文件大小守卫；P0.5 先拆全部 >1000 行文件（含 EconomySeeder） | P0.5 |
| G30 | 无级自耕—雇佣农业模板 | 原则只在设计 §20.3/§20.4 与交接 §2.3；**没有可执行模板**。当前 `DefaultProductionModes.java:26-45` 仍分 `tenancy_*`/`wage_farm`/`family_farm`；`EconomySeeder.java:1772-1774,1795-1823` 仍按 poor/middle/rich/landlord 槽位映射 mode；`:5964-6044` 仍生成租佃 unit | 先落 `docs/superpowers/specs/2026-10-18-d032-unified-agricultural-mode-template.md`；P3/P5 按单一 `grain_farm` 模板实现，阶级标签只做投影 | P3/P5 |

### 2.1 审计 A1–A5 的处置

| 审计项 | 当前事实 | 本计划处置 |
|---|---|---|
| A1 U20 调价无日循环调用者 | `ProductionWageBook.java:267` 定义，main 无调用者；审计 §2 A1 | P4 接入市场轮；真实多 tick 验证 |
| A2 合作试算用 organizer 经验、实际用出工者经验 | `ExpectedProfitBook.java:2396-2418`；`ProductionRuntimeEngine.java:894-899`；审计 §2 A2 | P3 统一 probe/execute 的逐参与户条件；不复制公式 |
| A3 零劳动地主 + posting 被预计器判 labor-short | `ExpectedProfitBook.java:2397-2405`；审计 §2 A3 | P3 从 organizer-only 名册出发，用 posting target 作需求边界；不塞假 worker |
| A4 逐户角色所得未进入选业排序 | `LaborQueueSettlement.java:574-590`；`LaborQueueBook.java:361-423`；审计 §2 A4 | P3/P4 用本户预计所得减本户负担替换整池收益入口 |
| A5 抵物按初始库存排序、不按逐笔余额重排 | `WageSettlement.java:309-311,350`；审计 §2 A5 | P4 删除整个工资抵物腿后本项自然消失；不得把旧 A5 测试留成“仍支持抵物” |

## §3 责任区、写者与测试 owner

> **总纪律**：同一时刻只有一个写代码 owner；同一文件只在一个责任区里改；写者只到 `-DskipTests compile`；测试统一在 P6 由独立测试 owner 做；控制方不写生产代码。

### 3.1 责任区与顺序

| 阶段 | 责任区 | 写者 owner | 主要文件（允许动） | 禁止碰 | 交付/编译门槛 |
|---|---|---|---|---|---|
| P0 | tick 契约 + 差距表 + 拆分方案 | 控制方 | 本文件、tick contract | 所有 Java/XML/POM/测试 | 文档落盘；不跑 Maven |
| P1 | Social 先 + 单槽事实 | W1 `app-tick` | `simos-app/.../time/PopulationEconomyTimeParticipant.java`、`PopulationUnitTimeParticipant.java`、app 侧新 `PendingFamineFacts` 类型、必要的 app 测试夹具（仅 P6 写测试） | `EconomySettlement`/`MarketSettlement`/生产公式 | `mvn-lock -pl simos-app -am -DskipTests compile` rc=0 |
| P2 | 统一市场 + 土地 + 债务最先 + 删 Pledge | W2 `economy-market-debt` | `simos-economy` 的 `MarketSettlement`/新市场拆分类、`EconomySettlement` 的债务段（抽到 `DebtSettlement`）、`DebtContractBook`/`DebtUnit`/`Pledge`/`EconomyLiquidationSettlement`/`EconomyData`/ChangeSet/Codec 的必要形状、`OwnershipStake`/`MarketTable` 的 land quote 扩展 | 不在本阶段改工资锚、两遍生产编排、Seeder | 编译 rc=0；P2 约束设计书先落盘；文件拆分目标见 §5 |
| P3 | 两遍生产编排 + 土地需求/hex + 雇佣锁 | W3 `economy-production-tick` | `EconomySettlement`（编排骨架）、`ExpectedProfitBook`、`LaborQueueSettlement`/`LaborQueueBook`、纯库存财富新类、必要的 `simos-production` contract（只读/试算接口） | 不改 `WageSettlement` 支付算法；不改 Seeder；不写测试 | 编译 rc=0；P3 约束设计书先落盘；`EconomySettlement` 已拆到可维护规模 |
| P4 | 工资现金支付 + 普通债 + U20 工资锚 | W4 `economy-wage` | `WageSettlement.java`、`ProductionWageBook.java`、`WagePostingKey`/新锚键、`WageSettlementReadout`、工资相关 `EconomyData` 表/Codec | 不改市场/土地/生产编排；不写测试 | 编译 rc=0；旧抵物腿删除；真实 tick 调用点由 W3 保留 |
| P5 | 农村范围收口 + Seeder/租佃/跨 hex | W5 `rural-scope` | `simos-app/.../world/EconomySeeder.java`、`DefaultProductionModes`、`EconomyReferenceValidation`、农村默认生产定义/种子数据、必要的 economy 只读适配 | 不改已冻结的 M2/M3/M4/M8 顺序；不写测试 | 编译 rc=0；无租佃/rent 默认；初始土地按 hex |
| P0.5 | 经济大文件拆分先行 | W6 `economy-file-split` | 见 §5 的 >1000 行文件 + `EconomySeeder`；只做机械抽取/包内移类 | 不改行为、不改用户语义、不写测试 | 每个抽取步骤编译 rc=0 + 现有回归；终态 ≤1000 行 |
| P6 | 测试 + 变异自证 + 全量门禁 | T1 `verification`（独立测试 owner） | `simos-*/src/test`、必要测试夹具；不改生产代码 | 不改生产代码/契约/口径 | 按本计划验收判据写测试；全量 `clean verify`；关键项变异自证；报告“未做/未验证” |

### 3.2 文件所有权交接

- `EconomySettlement.java` 是共享热点：P2（W2）先抽 `DebtSettlement` 并交回骨架；P3（W3）独占改编排；P4（W4）不得再改它，只在 P3 已留出的调用点写 `WageSettlement` 自身。
- `MarketSettlement.java` 只归 W2；P3 不直接改它，只通过 W2 拆出的市场计划/结算读口接。
- `ExpectedProfitBook.java`/`LaborQueueSettlement.java` 只归 W3；W4 不得顺手改。
- `EconomySeeder.java` 只归 W5；W2/W3/W4 不得为了夹具改它。
- P0.5 文件拆分在每个文件交接后由 W6 独占；P0.5 必须先于 P1；如果 P6 测试 owner 发现需要生产代码修复，先回到控制方重开对应责任区，不得测试 owner 直接改 main。
- 每次任务书必须写：约束设计书路径 + 关键形状原样复述 + 允许写文件 + 禁止碰文件 + 只编译不测试 + 不 commit。

## §4 P0–P6 批次内容与验收判据

### P0 — 契约/计划（当前）

- 交付：tick contract、本差距表、责任区/写者/测试 owner、经济大文件拆分方案。
- 退出：所有待裁定项已由用户 2026-10-18 回复关闭；没有代码改动；没有未登记的 BLOCKER。

### P1 — Social 先 + 单槽事实

- 新增 app 侧单槽 `PendingFamineFacts`（只保留最近一份；不落盘/不进 Codec/ChangeSet）。
- 每天开头 Social 先用上一 tick 事实结算，再刷新经济投影；每天结束把本 tick 缺口存入下一份。
- 修 `advance` 分段等价：`advance(0,30)`、`advance(0,60)`、`advance(0,30)+advance(30,60)` 逐日结果相同。
- 负向：失败 advance 不得覆盖上一份有效事实；首 tick 空事实不得伪造死亡；经济不得自行判死。

### P2 — 统一市场（商品+土地）+ 债务最先 + 删 Pledge

- 市场轮开头先 M2 债务清算，再 M3 预期编排。
- **统一市场**：商品订单和土地订单进同一订单簿/同一撮合器、同一轮同一结算；土地订单是 special order variant，不进 `HouseholdGoods`。
- 土地数量/质量按 hex；价格按市场区一个价；0 合法；每 hex 最大农业规模不得超过可耕土地×质量系数。
- 债务按债权人纯库存财富降序受偿；受偿介质货币→库存→土地；土地直接抵债、整块不找零；债权人全灭债权消灭、土地留债务人。
- 删除 `Pledge`/抵押/担保/特定资产优先受偿；删除低库存追加轮/关账日额外轮。
- 新增 `DebtSettlement` 并抽离 `EconomySettlement` 债务段；开始拆 `MarketSettlement`。
- 负向：无 Pledge 读取/写入；无土地库存双记；土地价值>债务/土地价值<债务/无债权人/债权人全灭；0 价与未定价分开；没有 35 天储备。

### P3 — 两遍生产编排 + 雇佣锁 + 纯库存财富

- **前置**：先冻结 `docs/superpowers/specs/2026-10-18-d032-unified-agricultural-mode-template.md` 的 `grain_farm` 单一模板；不得按 poor/middle/rich/landlord 拆 mode。
- **单家户两阶段决策**：按 tick contract §3.1.1，第一轮在“预期库存/价格/工资”信息集上最大化预期可支配净收益并生成订单；第二轮在市场/消费后的“实际库存”信息集上最大化实际净收益。两轮共用 `positionNet/positionYieldPerLabor`，第二轮不得重开市场、不得改锁定雇佣。
- M3 第一轮：读 M2 后实际库存/土地；家户按纯库存财富降序；同一 `grain_farm` 模板下按土地/劳动/投入/经验选择自耕或雇工规模；锁雇佣；算商品/土地买卖意向；生产投入需求与外部注入需求分表。
- M4 统一市场后 M6 第二轮：雇佣锁死；新买土地不用；自家劳动+雇工劳动按锁定份额投入；非雇佣小生产按实际库存/土地/劳动重排；失败位置转无业；不产生市场买卖。
- 土地买盘按 hex；数值工程标定；新买/抵债取得土地下一轮才可用。
- 修审计 A2/A3/A4：probe/execute 同一逐参与户事实；零劳动地主 posting 不因 probe labor 0 被误拒；本户预计所得进入排序。
- 拆 `EconomySettlement` 为编排门面 + phase classes；拆 `ExpectedProfitBook`/`LaborQueueSettlement`。
- 负向：有雇佣+小生产并存；雇佣方库存不足仍锁关系；新买土地不进本轮；卖地中断/荒置；纯库存财富不扣债、不含工具/作坊；跨 hex 自耕拒绝；**第二轮在锁定雇佣/新土地约束下重算，且不得产生市场订单**。

### P4 — 工资现金支付 + 普通债 + U20 工资锚

- 工资只付货币，稳定义务序付到现金用完；未付成普通家户货币债；不抵物；不预付。
- M2 工资债与普通债同池；允许库存/土地还债；债权人财富序。
- U20 工资锚键 = 市场区 × 生产方式 × 位置；市场轮读取实际劳动/需求并写回下一轮；本 tick 工资不被新报价追改。
- 删除 `WageSettlement` 抵物腿与相关测试夹具；同日工资债不再需要“排除集”补洞。
- 负向：现金不足部分支付/完全无现金；无库存抵工资；工资债次日进入 M2；0 工资不是特殊拒绝；U20 从 0 加价/招满降价/多 tick 观测真实生效。

### P5 — 农村范围收口

- 本批只接农村土地/农业/雇农；工坊/手工业后加。
- 按 `2026-10-18-d032-unified-agricultural-mode-template.md` 落**单一 `grain_farm` 模板**：有地家户都可自耕/雇工；无地户只能受雇或买地；不再按地主/富农/中农/贫农拆 mode。
- 删除农村默认 rent/tenancy/lease 路径；`ProductionRules` 通用类型可保留，但默认不再生成租佃关系。
- 无硬编码地主/中农/贫农生产方式标签；初始分布按测试世界逐户差异财产+对应规模土地；0 元公共土地仅测试预设。
- 跨 hex：可买、无运费；耕种必须雇土地所在 hex 的当地雇农；所有者不得用自家劳动跨 hex 耕种。
- 负向：卖地中断、无买家不伪造成交；无租约；跨 hex 自耕拒绝/本地雇农成功；初始土地守恒（Σ hex 土地 = 世界土地）。

### P0.5 — 经济大文件拆分先行（旧称 P5b；顺序已提前）

- 按 §5 清单把剩余 >1000 行文件拆到 ≤1000 行；只做行为不变的机械抽取，不改语义。
- 终态加机械守卫（P6 测试/工具）：任何 `simos-economy/src/main/java/**/*.java` 超过 1000 行 ⇒ 红。
- 拆分后包内可见性保持最小；不得为了拆文件把内部实现升成跨模块 public API。

### P6 — 验证与关账

- 独立测试 owner 按本计划/契约逐条写测试，含负向用例与变异自证（守恒、身份、静默付 0、断粮）。
- 多 tick 长跑不进程序化测试；按 AGENTS 用 `tools/multitick-sim.sh` 独立世界跑。
- 最终 `tools/mvn-lock.sh -pl simos-app -am clean verify`；写者全部停手后才跑；证据落 `.superpowers/sdd/<topic>/evidence/`。
- 报告必须写“没做/没验证”的清单；不得用全绿构建替代行为验收。

## §5 经济模块千行 Java 文件拆分方案

### 5.1 终态硬约束

- `simos-economy/src/main/java/**/*.java` **每个文件 ≤1000 行（含注释；保守口径）**。
- 先按职责拆，不按行数硬切；文件行数只是守卫，不是设计目标。
- 拆分不得改变行为、不得改公开 API 语义、不得把包内实现随意升成 public。
- 拆分顺序：先拆正在被功能改造的两个万行文件（自然接缝最多），再清剩余；每抽一个类就编译一次。
- 终态守卫由 P6 测试/工具机械执行；守卫未上线前，P0.5 报告必须给出 `wc -l` 全表。

### 5.2 优先级 A：两个万行文件（P2/P3 内完成）

| 现文件 | 行数 | 目标拆分 |
|---|---:|---|
| `time/EconomySettlement.java` | 11127 | `EconomySettlement` 只留日序门面/入口（目标 ≤900）；`ConsumptionSettlement`、`ProductionInputSettlement`、`ProductionRunSettlement`、`ProductionOrganizationSettlement`、`PopulationWritebackSettlement`、`DebtSettlement`、`AccountTransferSettlement`、`SettlementLogSupport`、`SettlementMath`。 |
| `time/MarketSettlement.java` | 11061 | `MarketSettlement` 只留市场轮门面（目标 ≤1000）；`MarketOrderPlanner`、`MarketMatcher`（区内+跨区）、`MarketFreezeBook`、`MarketPricer`、`MarketMoneyBook`、`MarketShipmentBook`、`MarketTaxBook`（可与现有 `MarketTaxBook` 合并）、`MarketReadoutBook`、`MarketIndexes`。 |

### 5.3 优先级 B：P0.5 清扫剩余 >1000 行文件（含 EconomySeeder）

| 现文件 | 行数 | 目标拆分 |
|---|---:|---|
| `EconomyData.java` | 4068 | `EconomyData` 记录 + 规范构造；`EconomyDataInvariants`（跨表校验）；`EconomyDataTransforms`（`withX` 工厂，保留必要委托）；`EconomyDataQueries`（纯读口）。 |
| `time/ModeMigrationPolicy.java` | 3001 | `ModeMigrationCandidateBook`、`ModeMigrationScoreBook`、`ModeMigrationRules`、`ModeMigrationAssetPolicy`。 |
| `spi/EconomyPayloads.java` | 2836 | `EconomyHouseholdPayloads`、`EconomyMarketPayloads`、`EconomyProductionPayloads`、`EconomyDebtPayloads`、`EconomyPolicyPayloads`。 |
| `time/ExpectedProfitBook.java` | 2769 | `ProductionProspectBook`、`WageProspectBook`、`MerchantProspectBook`、`RosterIncomeBook`、`ProspectArithmetic`。 |
| `time/ModeMigrationSettlement.java` | 2682 | `MigrationPopulationMove`、`MigrationAssetMove`、`MigrationDebtMove`、`MigrationExperienceMove`、`ModeMigrationSettlement` 门面。 |
| `codec/EconomyCodec.java` | 2143 | `EconomyCodec` 门面 + `EconomyCoreCodecs`、`EconomyMarketCodecs`、`EconomyDebtCodecs`、`EconomyProductionCodecs`。 |
| `time/LaborQueueSettlement.java` | 2105 | `LaborCandidateBook`、`LaborQueuePlanBook`、`LaborQueueWritebackBook`、`ArbitrageLaborBook`。 |
| `time/MerchantCapacityPool.java` | 1754 | `MerchantCapacityPool`、`MerchantFreightQuoteBook`、`MerchantCapacityMath`。 |
| `time/EconomyLiquidationSettlement.java` | 1667 | P2 先删 Pledge/抵押清算主链；剩余拆 `DebtStressBook`、`CrisisSignalBook`；阶层滑落已在 `ClassSlideSettlement`，不复制。 |
| `time/EconomyEntrySettlement.java` | 1634 | `EntryCandidateBook`、`EntryEvaluationBook`、`EntryExecutionBook`。 |
| `time/EconomyEnterpriseSettlement.java` | 1624 | `EnterpriseOrganizationBook`、`EnterpriseStatusBook`。 |
| `spi/EconomyGmAdjustments.java` | 1474 | `GmHouseholdAdjustments`、`GmMarketAdjustments`、`GmProductionAdjustments`。 |
| `time/FxSettlement.java` | 1343 | `FxQuoteBook`、`FxMatchBook`、`FxSettlement` 门面。 |
| `time/AssetOccupancyBook.java` | 1326 | `AssetOccupancyBook` 门面 + `AssetOccupancyAllocator` + `AssetOccupancyReadout`（复用已有 `AssetOccupancyBudget/Allocation`）。 |
| `EconomyReferenceValidation.java` | 1297 | `HouseholdReferenceValidation`、`MarketReferenceValidation`、`ProductionReferenceValidation`、`DebtReferenceValidation`。 |
| `time/HouseholdClassRule.java` | 1022 | `HouseholdClassRule` + `ClassProjectionBook`。 |

### 5.4 拆分执行细则

1. **先建行为基线**：对要拆的文件记 `wc -l` + `sha256sum`；拆分不改行为，不改公式，不改状态形状。
2. **只移类/方法**：优先抽 package-private 静态类；原类保留同名入口委托；调用点暂不大规模改写。
3. **每抽一组编译**：`tools/mvn-lock.sh -pl simos-economy -am -DskipTests compile`；失败则先回滚该组，不叠加第二组。
4. **共享热点交接**：`EconomySettlement`/`MarketSettlement` 拆分与 P2/P3 功能改造同一 owner 完成；拆分后把门面行数写入账本。
5. **不为了行数牺牲边界**：不得把 `economy` 公式搬进 `production`，也不得把 production 公式复制进 economy；只做包内抽取。
6. **终态守卫**：P6 加一个扫描 `simos-economy/src/main/java` 的测试/脚本，任何 `.java` >1000 行即红；守卫上线前用 `find ... | xargs wc -l | sort -nr` 出全表。

## §6 命令与证据

```bash
cd /home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module

# 写者每批只做编译
tools/mvn-lock.sh -pl simos-app -am -DskipTests compile
tools/mvn-lock.sh -pl simos-economy -am -DskipTests compile

# P6 测试 owner/控制方
tools/mvn-lock.sh -pl simos-app -am test
tools/mvn-lock.sh -pl simos-app -am clean verify
tools/gate.sh
```

- `-pl` 必须带 `-am`；不要依赖 `-Dshade.skip=true`。
- 服务在跑时验代码用 `./mvnw test`，不要覆盖运行中的 jar。
- 本树内一次只跑一个 Maven；用 `tools/mvn-lock.sh`。
- 证据落 `.superpowers/sdd/<topic>/evidence/`（本机忽略目录，不进 git）。
- 不擅自 `git add -A`/commit/push/merge；只按批次显式文件提交（若用户后续授权）。

## §7 当前批次结论

- **已完成**：读全 AGENTS/交接/设计/审计并回代码核；P0 tick contract 已按用户最新修正冻结；代码差距表、责任区/写者/测试 owner、P0–P6、经济大文件拆分方案已落盘。
- **未做**：没有改 Java/XML/POM/测试；没有跑 Maven；没有提交/推送；没有动主树。
- **下一入口**：P1（W1）Social 先 + 单槽事实；之后 P2（W2）统一市场/土地/债务。每个写者开工前先读本文件 §3/§4 与对应约束设计书；没有约束设计书不得派单。
