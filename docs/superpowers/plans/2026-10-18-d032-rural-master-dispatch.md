# D-032 农村经济重写：总派单文件（Master Dispatch，2026-10-18）

> **状态：P0 已完成；P1–P6 未实现、未跑 Maven、未提交、未改 Java/XML/POM/测试。**
> **本文件 = 唯一总派单入口。** 未来派 Agent、未来我恢复工作、控制方验收，都先看本文件。
> 详细差距表与大文件拆分清单仍保留在 `docs/superpowers/plans/2026-10-18-d032-rural-land-labor-debt-p0-p6-plan.md`（下称“P0 计划”）；本文件负责把“每个阶段怎么改、按什么架构、读哪些文档、交给谁”统一说清。
> 冻结契约：`docs/superpowers/specs/2026-10-18-d032-rural-tick-contract.md`。
> 农业模板：`docs/superpowers/specs/2026-10-18-d032-unified-agricultural-mode-template.md`。
> 工作树：`/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`；HEAD：`de02aa3f`。
> **权威顺序**：用户原话 > 用户设计原稿 D-032 U1–U23 > 最新落盘文档 > 根 AGENTS > 旧文档。发生冲突先停手报告，不得由实现 Agent 自裁。

## §0 怎么用本文件

### 0.1 派单 Agent 的最小启动提示词

把下面这段连同“本阶段任务书”一起给 Agent：

```text
你是 SimulatorMosire 侧线 worktree
/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module
的一名实现 Agent。先完整读：
1) AGENTS.md 与 docs/agents/00–15 全部；
2) docs/superpowers/specs/2026-10-19-d032-rural-runtime-target-design.md（D-032 目标运行序列；先读它再读本文件）；
3) docs/superpowers/plans/2026-10-18-d032-rural-master-dispatch.md（本文件）的 §0–§4 + 你负责的阶段；
4) docs/superpowers/specs/2026-10-18-d032-rural-tick-contract.md 的相关 §；
5) 本阶段“必读文档”表列出的旧 D-032 文档对应章节。
6) 加载 `.agents/skills/target-runtime-design/SKILL.md`；你写中期设定/impl-ledger 也必须按该技能。

纪律：
- 只写生产代码，写到 `tools/mvn-lock.sh -pl <module> -am -DskipTests compile` 编译通过；
- 不写/不跑测试，不 commit/push/merge/stash，不碰主树；
- 只改任务书允许的文件；看到别人的半成品不要接手；
- 如果发现设计与用户原话/五条铁律/模块边界冲突：立即停手报告，不得自行改口径；
- 报告必须列出：改动文件、编译结果、会改变数值行为的清单、受影响硬编码、可能失效的旧测试、未完成/未验证项。
```

### 0.2 未来我恢复工作时先看什么

1. 本文件 §1 文档地图；
2. 本文件 §2 用户已定口径；
3. 本文件 §6 阶段总表，看当前停在哪个阶段；
4. 对应阶段的“必读文档”“架构设计”“代码调整”“文件 owner”“验收判据”；
5. `git status --short --branch` 与 `git log --oneline -5`；
6. 如果上次有未提交改动：先读 diff，不要 assume 它是半成品，也不要覆盖；按 §10 交接规则处理。

## §1 文档地图（每份文档管什么）

| 文档 | 角色 | 什么时候必读 |
|---|---|---|
| 本文件 `2026-10-18-d032-rural-master-dispatch.md` | **唯一总派单入口**：阶段、架构、owner、验收、提示词 | 每次开工 |
| `docs/superpowers/specs/2026-10-19-d032-rural-runtime-target-design.md` | **D-032 目标运行序列标准文档**：实际模块代码逻辑顺序、状态所有权、任务嵌入/影响、裁定→阶段映射、日志/验收锚点 | 每次开工、所有子设计/派单 |
| `docs/agents/15-十四-目标运行设计与子设计嵌入.md` + `.agents/skills/target-runtime-design/SKILL.md` | **目标运行设计先行**的项目规则与技能：子设计嵌入/影响、模块/类型细化规范、责任区子 Agent 中期设定 | 每次开工、写派单/设计/中期设定 |
| `docs/superpowers/specs/2026-10-18-d032-rural-tick-contract.md` | 冻结 tick 顺序/读写边界/不变量/两阶段决策 | 每次开工、P1–P6 |
| `docs/superpowers/specs/2026-10-18-d032-p2-unified-market-debt-constraint.md` | P2 约束设计书：市场节奏、地价单位/土地块身份、统一市场订单、纯库存财富、Pledge 删除、批次与验收拆分 | P2 |
| `docs/superpowers/specs/2026-10-18-d032-p3-two-pass-production-constraint.md` | P3 约束设计书：MarketIntent/市场装配拆分、两遍计划、EmploymentLock、roster probe、新土地排除、跨 hex、验收批次 | P3 |
| `docs/superpowers/specs/2026-10-18-d032-p4-wage-cash-anchor-constraint.md` | P4 约束设计书：工资现金/普通债/删 barter、WageAnchor/Key/WageDemand、U20 观测与批次 | P4 |
| `docs/superpowers/specs/2026-10-18-d032-unified-agricultural-mode-template.md` | 无级自耕—雇佣农业模板 | P3/P5 |
| `docs/superpowers/specs/2026-10-18-d032-economy-tick-redesign.md` | 2026-10-18 设计正文与用户逐轮原话 | 每次开工，至少 §14–§45 |
| `docs/superpowers/status/2026-10-18-d032-rural-land-labor-debt-redesign-handoff.md` | 上一段交接与当前冻结候选 | 每次开工 |
| `docs/superpowers/reports/2026-10-18-d032-original-intent-audit.md` | 当前实现审计 A1–A5、独立复现 | P3/P4 |
| `docs/superpowers/plans/2026-10-18-d032-rural-land-labor-debt-p0-p6-plan.md` | 详细差距表、文件拆分清单、阅读覆盖 | P0 附录；P0.5/P2/P3 |
| `docs/superpowers/specs/2026-10-02-architecture-design-source.md` D-032 U1–U23（541–769 行） | 用户设计原稿：生产/经验/工资/职责边界最高权威 | 每次开工、P3/P4 |
| `docs/superpowers/specs/2026-10-17-d032-production-execution-decision.md` | D4：production 提供预计/实际产出与状态变化 | P2/P3 |
| `docs/superpowers/specs/2026-10-17-d032-production-runtime-boundary-decision.md` | 生产状态归属与调用边界 | P2/P3 |
| `docs/superpowers/specs/2026-10-17-d032-r2-shared-asset-minimal-design.md` | R2 共享资产不重复占用（最小 D4） | P2/P3 |
| `docs/superpowers/specs/2026-10-17-d032-production-runtime-migration-design.md` | I1–I16、V1–V12、状态/接口/原子提交 | P2/P3 前必读全文 |
| `docs/superpowers/specs/2026-10-17-d032-asset-occupancy-design.md` | 资产占用/预算/回填/跨周期释放 | P2/P3 前必读全文 |
| `docs/superpowers/specs/2026-10-17-d032-cooperative-production-design.md` | 多户参与、角色、经验、分配 | P3 前必读 |
| `docs/superpowers/specs/2026-10-17-d032-production-experience-choice-design.md` | 经验公式、解锁、主副业投影 | P3 前必读 |
| `docs/superpowers/specs/2026-10-17-d032-production-definition-v2-freeze.md` | 角色/份额/经验/资格/支付物定义形状 | P3/P5 前必读 |
| `docs/superpowers/specs/2026-10-17-d032-stage1-deviation-review-decision.md`、`...stage2-handoff-review-decision.md` | 已冻结的执行/批次/零半提交语义 | P3 前必读 |
| `docs/superpowers/specs/2026-10-17-d032-wage-chain-design.md` | 工资链、劳动匹配、U15–U23 | P4 前必读 |
| `docs/superpowers/specs/2026-10-17-d032-wage-idle-barter-decision.md` | 0‰ 欠薪、闲置、抵物折价配置 | P4 前必读 |
| `docs/superpowers/specs/2026-10-17-d032-wage-settlement-simplification-decision.md` | 工资直付/欠债边界；抵物部分已被 2026-10-18 新裁定覆盖 | P4 前必读 |
| `docs/superpowers/specs/2026-10-17-d032-tick-wage-feedback-proposal.md` | U20 调价、逐 tick 博弈 | P4 前必读 |
| `docs/superpowers/specs/2026-10-17-d032-preselection-wage-feasibility-analysis.md`、`...employer-posted-wage-redesign.md`、`...distribution-wage-offer-proposal.md`、`...cooperative-production-proposal.md` | 历史提案，只取未被 2026-10-18 新裁定取代的部分 | P3/P4 参考 |
| `docs/superpowers/specs/2026-10-17-d032-production-line-replan.md`、`docs/superpowers/plans/2026-10-17-d032-production-line-replan.md`、`docs/superpowers/reports/2026-10-17-d032-*.md` | 历史重规划/复核，不得覆盖最新用户原话 | 参考 |
| `docs/superpowers/status/2026-10-17-d032-production-runtime-migration-result.md` 等旧状态 | 代码背景，不据此开工 | 参考 |

## §2 用户已定口径（实现不得改写）

| # | 口径 | 来源 |
|---|---|---|
| U1 | Social 先于经济；上一 tick 饥荒/缺口事实只留单槽内存，不落盘、不进 Codec/ChangeSet | 设计 §7/§9/§10.1 |
| U2 | 市场轮最前面先清算全部债务 | 设计 §26/§28 |
| U3 | 统一市场：商品订单与土地订单进**同一订单簿/同一撮合器**、同轮同结算；土地不是独立市场阶段 | 用户 2026-10-18 回复；设计 §17/§18.1/§23/§24 |
| U4 | 两遍生产：第一遍预期（生成市场意向、锁雇佣）；第二遍实际（实际库存后重算非雇佣小生产）；第二轮不产生市场买卖 | 设计 §26/§28/§29 |
| U5 | 单家户两轮都以本户净收益最大化为目标；两轮共用 `positionNet/positionYieldPerLabor`；第二轮只换信息集 | 设计 §30/§31；tick contract §3.1.1 |
| U6 | 本轮新买/抵债取得的土地本轮不可用于生产，下一市场轮才进入预期编排 | 设计 §21/§30/§31/§32 |
| U7 | 土地是 `AssetKind.LAND` 的生产能力资产；production 用 `capacityPerUnit` 维护，不进入普通家户货物库存 | 设计 §23/§24；`IndustryDefinitionView` |
| U8 | 土地**数量/质量按 hex**；土地**价格按市场区一个价**；数值由工程标定；每个 hex 最大农业规模不得超过可耕土地×质量系数 | 用户 2026-10-18 回复；设计 §44/§45 |
| U9 | 债务本金只用货币；库存只是还债介质；没有商品本金债 | 设计 §1/§38；用户 §37 |
| U10 | 没有抵押/担保/特定资产优先受偿；删除 `Pledge`；土地在还债环节直接抵债、整块不找零 | 用户 §39/§40/§42/§43/§45 |
| U11 | 工资第一次只付货币；现金付到用完；未付成普通家户货币债；不拿库存抵工资 | 用户 §35/§37/§38 |
| U12 | 还债顺序按债权人纯库存财富降序；穷债权人可能拿不到，债务继续挂账 | 用户 §37/§38.2 |
| U13 | 纯库存财富 = 钱 + 货物 + 土地；不扣债务；工具/作坊不计入 | 用户 2026-10-18 回复；设计 §21/§22 |
| U14 | 只有每 5 tick 一结的市场轮；删除低库存追加轮/关账日额外开市 | 用户 2026-10-18 回复；设计 §1/§12/§13 |
| U15 | 取消地主/富农/中农/贫农作为生产方式硬标签；单一无级自耕—雇佣农业模板；阶级标签只做投影 | 设计 §20.3/§20.4；农业模板 §1–§7 |
| U16 | 生产方式定义/配方/经验/预计实际产出/状态变化归 production；家户间经济关系、市场、债务、工资归 economy；app 只编排/桥接 | 用户 2026-10-18 回复；D-032/U8；production-boundary 裁决 |
| U17 | 经济模块千行 Java 文件必须拆分；终态每个 main Java 文件 ≤1000 行 | 用户 2026-10-18 回复；P0 计划 §5 |
| U18 | 生产方式的进步/升级暂不做；不阻塞本轮农村 tick | 用户 2026-10-18 回复 |

## §2A 架构责任（不是裁决清单）

下面不是再抄一遍用户裁决，而是这些裁决要求架构**必须承担什么责任**。P0.5–P6 的实现和验收都按这份责任模型判断，而不是按“某条 doc 有没有写”机械打勾。

### 2A.1 十条架构责任

| # | 架构责任 | 必须保证 | 明确不负责 |
|---|---|---|---|
| R1 | **单一事实权威** | 每个事实只有一个可写 owner：人口/劳动在 Social，生产定义/实例/经验/预计实际物理产出在 production，土地所有权/市场/债务/工资/家户选择在 economy，账户在 actor。任何第二份可写副本 = 架构失败。 | 不要求“所有代码都放一个模块”；允许只读投影/临时索引，但不得成为第二写入口。 |
| R2 | **时间因果** | Social 先；债务最先；第一轮预期在统一市场前；第二轮实际在市场和消费后；第二轮不下单；同 tick 的写入同 revision 原子提交。 | 不允许用“后一阶段再补”掩盖顺序错误；不允许把失败当成功继续。 |
| R3 | **家户是经济决策主体** | 单家户在两轮信息集上最大化本户可支配净收益：预期轮生成订单/雇佣锁，实际轮按实际库存重算非雇佣部分；两轮同一利润公式；雇佣锁不可改。 | 不由 production 决定“这家户该选什么”；也不由 economy 复制 production 的物理公式。 |
| R4 | **生产技术归 production** | production 定义生产方式/配方/角色/经验/土地与劳动需求，并对给定家户条件和投入给出预计/实际产出、进度、经验与生产状态变化；预计与实际共享同一公式/条件求值。 | production 不定义价格、需求、市场、债务、工资、地租、阶级标签或家户间分配。 |
| R5 | **经济运转归 economy** | economy 负责家户选择、统一市场（商品+生产能力资产订单）、价格、工资锚、劳动匹配、债务、土地所有权、迁移、阶级投影；所有经济关系在同一 revision 收口。 | economy 不得复制生产配方/经验公式；不得直接写 production 内部状态。 |
| R6 | **统一市场** | 商品与土地订单进**同一订单簿/同一撮合器**、同轮同结算；土地订单是生产能力资产变体，不进普通货物库存；共享预算/冻结；新土地本轮不用；没有独立租约/抵押。 | 不是“同一 tick 各开一次商品市场和土地市场”；也不是把土地塞进 `HouseholdGoods`。 |
| R7 | **土地是生产能力资产** | `LAND` 是 `AssetKind` 的一档，由 `capacityPerUnit` 表达；有 hex/面积/质量/owner/operator；可交易、可抵债；**价格按市场区一个价**，hex 只决定面积/质量/供给量；0 价格合法；每 hex 最大农业规模可核。 | 土地不是消耗原料，不是第二个生产系统，不是 Pledge/抵押品。 |
| R8 | **债务与工资的可执行性** | 债务本金只用货币；库存/土地只是还债介质；债权人按纯库存财富降序受偿；工资第一次只付货币，未付成普通货币债；没有抵押/担保/特定资产优先受偿。 | 不新增核销/催收/价格机制；不把工资未付静默成 0；不恢复抵物工资。 |
| R9 | **可重放/可审计/可维护** | 所有状态走 ChangeSet/Revision；完整状态往返；确定性排序；日志 INFO/DEBUG/TRACE；经济 main Java 文件 ≤1000 行；文件拆分先行。 | 不以“构建绿”代替行为验收；不以拆文件改变行为/公开 API。 |
| R10 | **阶级标签只是投影** | 地主/富农/中农/贫农/雇农只作为结算后读数；生产选择只看土地/劳动/资金/经验/价格/利润。 | 不允许 `view.stratum()` 或 `SocialClassId` 进入 mode/role/候选过滤。 |

### 2A.2 这些责任落到代码上的硬形态

- **production 的接口**：定义/配方/角色/经验 + `probe`/`probeRoster`/`executeDay`；只接收不可变条件/投入，返回预计/实际结果；不读 `EconomyData`。
- **economy 的接口**：两阶段计划、统一市场订单/撮合、土地所有权、债务、工资锚、劳动匹配；只读 production contract，不碰内部实现。
- **app 的接口**：日循环编排、Social/economy/actor/production 的事实桥接、账户会话、提案汇合；不实现领域公式。
- **状态边界**：预期/实际计划是同 tick 瞬态；雇佣锁/最终 roster 才落持久态；土地/商品/债务/工资锚落 economy；生产实例/经验/进度落 production；账户落 actor。
- **文件边界**：P0.5 先把 `simos-economy` 的千行文件与 `EconomySeeder` 拆成职责类，之后的功能改动才落在小文件上；拆分不是最后清扫。

### 2A.3 架构责任的三条用户裁定（2026-10-18）

| # | 问题 | 用户裁定 | 架构后果 |
|---|---|---|---|
| 1 | 统一市场内部结构 | **同一订单簿/同一撮合器** | 商品订单与土地订单共享预算、冻结、撮合和结算；土地只是订单变体，不得做两个匹配器。 |
| 2 | 土地价格作用域 | **每个市场区一个土地价格；hex 只决定面积/质量/供给量** | `MarketTable` 存区级 `landPrice`（`OptionalLong`：empty=未定价，0=合法）；不得做 hex 级独立价格表。 |
| 3 | 生产计划/名册归属 | **economy 拥有计划/名册**；production 只拥有定义/配方/经验/实例物理状态与预计/实际产出计算 | economy 负责选业、雇佣锁、roster、劳动分配；production 不得维护“选中的计划”作为第二权威。 |

> 这三条已关闭，P2/P3 可以据此派单。

## §3 当前代码基线（只写已核实事实）

- 当前日循环是经济先、Social 后：`PopulationEconomyTimeParticipant.java:664-705,803-805`。
- 当前债务偿还发生在市场之后：市场 `EconomySettlement.java:2171-2182`，还债 `:2904-2909`。
- 当前只有一遍生产：投入/劳动队列 `:1634-1705`，生产 `:1751-1752`，市场 `:2171-2182`。
- 当前工资是现金→抵物→欠薪债：`WageSettlement.java:309-377`。
- 当前 `Pledge`/自动质押/清算仍在：`Pledge.java:36-67`、`EconomyLiquidationSettlement.java:117,544,1445`。
- 当前 `DebtUnit` 仍允许 `Commodity`：`DebtUnit.java:26-55`。
- 当前市场有低库存追加轮和关账日额外开市：`MarketSettlement.java:1396-1421`。
- 当前市场还有 35 天生活储备：`MarketSettlement.java:132-144,10074-10089`。
- 当前阶级→mode 硬映射：`DefaultProductionModes.java:26-45`；`EconomySeeder.java:1772-1774,1795-1823,5964-6044`。
- 当前 `EconomySettlement` 11127 行、`MarketSettlement` 11061 行（`wc -l`）。

## §4 目标架构总览

### 4.1 模块边界

```text
Social
  人口/出生死亡/年龄/劳动能力/基础需求
    ↓ 只读投影 + 单槽 PendingFamineFacts
app 组合根
  只做日循环编排、跨切片桥接、账户会话、提案汇合
    ↓
economy
  家户决策（两轮）、统一市场（商品+生产能力资产订单）、劳动匹配、工资、债务、土地所有权、迁移
    ↓ 只读契约 + 实际投入/劳动事实
production
  生产方式定义/配方/角色/经验/预计与实际产出/生产实例推进
    ↓
actor / Social / 其他持账方
  账户/人口/政府状态落账
```

### 4.2 状态与权威

| 数据 | 权威 | 说明 |
|---|---|---|
| 生产方式定义、配方、角色、经验规则、生产实例、进度、实际投入/产出、经验/解锁 | production | 不复制到 economy |
| `OwnershipStake`（含 LAND）、土地所有权/占用、`AssetOccupancyGrant`、`InventoryReservation` | economy | 土地/资产账；LAND 不进 `HouseholdGoods` |
| 统一市场订单、价格、工资锚、债务、迁移、家户选择、劳动匹配 | economy | 有唯一写口/ChangeSet |
| 商品/货币账户 | actor | economy 通过 `AccountSession` 工作副本，最终同 revision 落回 |
| 人口/生死/劳动能力/基础需求 | Social | economy 只读投影 |
| `PendingFamineFacts` | app 单槽内存 | 不落盘、不进 ChangeSet/Codec |

### 4.3 tick 序列（完整版）

```text
每个 tick：
  Social 用上一 tick PendingFamineFacts 结算
  → 刷新 economy 的 composition/labor/naturalNeeds
  → 非市场日：每日消费 → 已锁定位置实际确认 → 生产 → 工资 → 存 PendingFamineFacts
  → 市场日：
      债务清算
      → 第一轮预期生产编排（订单意向 + 雇佣锁）
      → 统一市场（商品 + 土地订单同轮）
      → 每日消费
      → 第二轮实际生产编排（雇佣锁死；非雇佣小生产重排）
      → 生产执行
      → 工资结算
      → 存 PendingFamineFacts
```

### 4.4 统一市场

- 商品订单与土地订单进**同一订单簿/同一撮合器**、同一市场轮、同一结算阶段。
- 土地订单引用 `AssetShareId`/土地块和 hex；不引用 `HouseholdGoods`。
- 同一买方的商品与土地订单共用一份可花货币/冻结预算；基础消费/生产投入订单优先于土地投资订单，稳定 tie-break 可复现。
- 土地数量/质量按 hex；土地价格按市场区一个价；0 合法；未定价与 0 分开；价格每市场轮按 α 调整。
- 土地块整块转移；本轮新买/抵债取得土地本轮不可用。
- 没有独立租约/租地关系；没有 `Pledge`。

### 4.5 两阶段单家户决策

- 第一轮：预期库存/土地 + 预期价格/工资 → 选择生产方式/位置/规模、自家劳动、雇工需求、商品/土地买卖意向 → `ExpectedProductionPlan` + 雇佣锁。
- 第二轮：实际库存/货币/土地 + 实际劳动预算 + 雇佣锁 → 重算非雇佣部分；新买土地不可用；不得下单；失败位置转无业 → `ActualProductionPlan`。
- 两轮共用 `positionNet = expectedIncome − directInputCost − wageCost` 和 `positionYieldPerLabor = positionNet / laborMilli`；不得写第二套“实际版利润公式”。

### 4.6 无级农业模板

- 一个 `grain_farm` mode；角色 `operator`（产品份额/剩余） + `hired_laborer`（MONEY_WAGE）。
- `LAND` 是 `capacityPerUnit`；种子是 `cycleInputPerUnit`。
- 有地家户都可自耕/雇工；无地户只能受雇或买地；没有 rent/tenancy；没有按阶级拆 mode。
- 地主/富农/中农/贫农/雇农只做结算后的投影，不进入生产方式选择。

### 4.7 大文件拆分终态

- `simos-economy/src/main/java/**/*.java` 每个文件 ≤1000 行。
- 先拆 `EconomySettlement`/`MarketSettlement`，再拆 `EconomyData`/Codec/Payloads，再拆其余 >1000 行文件。
- 详见 §9 与 P0 计划 §5。

## §5 目标代码蓝图（语义级，不锁死类名）

```text
simos-economy/.../time/
  EconomySettlement               // 只留日序门面/入口
  EconomyDayPipeline              // 按 SettlementStage 顺序执行 phase
  ConsumptionSettlement           // 每日基础消费
  ProductionInputSettlement       // 生产投入/同格争用
  ProductionRunSettlement         // 生产执行/产出入库
  DebtSettlement                  // 债务清算/直接土地抵债/利息
  AccountTransferSettlement       // applyTransfer/冻结/发行落账
  PopulationWritebackSettlement   // 人口/劳动回写
  ExpectedProductionPlan          // 瞬态：第一轮结果
  ActualProductionPlan            // 瞬态：第二轮结果
  ExpectedPlanSettlement          // 第一轮单家户最大化
  ActualPlanSettlement            // 第二轮单家户最大化
  PureInventoryWealthBook         // 钱+货物+土地，不含工具/作坊、不扣债
  MarketSettlement                // 统一市场门面
  MarketOrderPlanner              // 商品+土地订单意向
  MarketMatcher                   // 区内/跨区撮合
  MarketFreezeBook                // 商品/货币/资产订单冻结
  MarketPricer                    // 商品与土地都按市场区价；hex 只决定土地量/质量
  MarketMoneyBook                 // 货币市场/付款币
  MarketShipmentBook              // 在途/到货
  MarketTaxBook                   // 口岸税/采购优先
  MarketReadoutBook               // 市场统计/未成交
  LaborCandidateBook              // 候选位置/资格
  LaborQueuePlanBook              // 两轮排序/分配
  LaborQueueWritebackBook         // 配额/名册写回
  Arbitration?/WageAnchorBook      // 工资锚（U20）
  WageSettlement                  // 只付货币 + 普通工资债
  ProductionProspectBook          // 物理试算 + 预计收益
  WageProspectBook                // 工资成本/收入
  MerchantProspectBook            // 跑商历史分支
  RosterIncomeBook                // 逐户角色所得

simos-production/.../contract/
  ProductionRuntime.probe/probeRoster/executeDay
  ProductionRosterProbeRequest/Result
  ProductionExecutionRequest/Result

simos-app/.../time/
  PopulationEconomyTimeParticipant
  PendingFamineFacts
```

**边界**：上面的类名是实现建议；Agent 可按自然接缝调整，但不得改变本文件 §4 的数据流/权威/顺序。每次调整在 `impl-ledger.md` 记录。

## §6 阶段总表

| 阶段 | 目标 | 写者 owner | 测试 owner | 状态 |
|---|---|---|---|---|
| P0 | 契约、差距表、总派单、拆分方案 | 控制方 | —— | **已完成（文档）** |
| P0.5 | 经济大文件拆分先行（所有 >1000 行 main Java） | W6 `economy-file-split` | T1 | **A 完成（EconomySettlement 818 行）；B 完成（MarketSettlement 532 行）；两批后 economy 452/0/0/0 绿；C1 完成（EconomyData 850 行，452/0/0/0 绿）；C2 完成（Codec 143、Payloads 705，452/0/0/0 绿）；D 完成（EconomySeeder 941 行，app 1017/0/0/7 绿）；E1 完成（MigrationPolicy 765 / Settlement 757；N23 行号审计迁移为唯一消息扫描；economy 452/0/0/0 绿）；E2 完成（ExpectedProfitBook 487 / LaborQueueSettlement 200；economy 452/0/0/0 绿）；E3a 完成（MerchantCapacityPool 642 / Liquidation 369；economy 452/0/0/0 绿）；E3b 完成（Entry 411 / Enterprise 266；economy 452/0/0/0 绿）；E4 完成（GM 324 / FX 442 / AOB 109；economy 452/0/0/0 绿）；E5 完成（Reference 167 / HouseholdClassRule 276；economy 452/0/0/0 绿）** |
| P1 | Social 先 + 单槽 `PendingFamineFacts` | W1 `app-tick` | T1 | **完成（app 1017/0/0/7、reactor 3572/0/0/7 绿）** |
| P2 | 统一市场（商品+土地）+ 债务最先 + 删 Pledge | W2 `economy-market-debt` | T1 | **完成（P2a–P2f；economy 452/0/0/0、app 1017/0/0/7、reactor 3572/0/0/7 绿）** |
| P3 | 两阶段单家户生产 + 雇佣锁 + 纯库存财富 | W3 `economy-production-tick` | T1 | **P3a–P3c3 完成；P3e 已修 5 tick/零劳动/WageDemand target/roster-only fallback/lock过滤/全链路日志；P3d 土地意向已生成，`MarketLandMatcher` 已并入 `MarketMatcher`，runner 只调 `matchAll` 一次；economy/app 编译绿；测试待 T1** |
| P4 | 工资现金支付 + 普通债 + U20 工资锚 | W4 `economy-wage` | T1 | **P4a/P4b1/P4c1/P4c2 完成，reactor 3574/0/0/7 绿；P4c3 旧工资表/命令/fallback 已删除且 economy/app 编译绿（34 组件）；P4d production barter schema 已清理，production/app 编译绿；P5 `grain_farm`/Seeder/P2fix 已完成；T1 测试迁移进行中** |
| P5 | 农村范围收口 + 无级农业模板 + Seeder | W5 `rural-scope` | T1 | **P5a/P5b1/P5b2 完成：单一 grain_farm、逐户 LAND/unit/roster、19 anchors、landPrice=10、19 hired participants；day1-9 真链跑通；P2fix 后 day10 抵地引用清理完成、12 tick 全程提交；T1 测试迁移进行中** |
| P6 | 独立测试、变异自证、全量门禁 | T1 `verification` | T1 | 未开始 |

> 任一时刻只允许一个写代码 owner；上一阶段编译/既有回归通过、控制方验收后才进入下一阶段。**P0.5 必须先于 P1 全部完成**；不允许边改行为边拆文件。

## §6A P0.5 — 经济大文件拆分先行

### 6A.1 终态

- `simos-economy/src/main/java/**/*.java` 每个文件 ≤1000 行（含注释，保守口径）。
- `simos-app/.../world/EconomySeeder.java`（6089 行）也必须在 P0.5 先拆；否则 P5 的农村 Seeder 改动仍会落回巨大文件。
- 不按行数硬切；按职责拆；拆分不改行为/公式/状态形状/公开 API 语义。
- 每个文件拆分后立即编译；不叠加第二个未编译的抽取。

### 6A.2 拆分清单

| 现文件 | 行数 | 目标拆分 |
|---|---:|---|
| `time/EconomySettlement.java` | 11127 | P0.5 拆；终态只留日序门面 ≤900：`ConsumptionSettlement`、`ProductionInputSettlement`、`ProductionRunSettlement`、`ProductionOrganizationSettlement`、`PopulationWritebackSettlement`、`DebtSettlement`、`AccountTransferSettlement`、`SettlementLogSupport`、`SettlementMath` |
| `time/MarketSettlement.java` | 11061 | P0.5 拆；终态只留市场门面 ≤1000：`MarketOrderPlanner`、`MarketMatcher`、`MarketFreezeBook`、`MarketPricer`、`MarketMoneyBook`、`MarketShipmentBook`、`MarketTaxBook`、`MarketReadoutBook`、`MarketIndexes` |
| `EconomyData.java` | 4068 | P0.5 拆：`EconomyData` 记录 + 规范构造；`EconomyDataInvariants`；`EconomyDataTransforms`；`EconomyDataQueries` |
| `time/ModeMigrationPolicy.java` | 3001 | P0.5 拆：`ModeMigrationCandidateBook`、`ModeMigrationScoreBook`、`ModeMigrationRules`、`ModeMigrationAssetPolicy` |
| `spi/EconomyPayloads.java` | 2836 | P0.5 拆：`EconomyHouseholdPayloads`、`EconomyMarketPayloads`、`EconomyProductionPayloads`、`EconomyDebtPayloads`、`EconomyPolicyPayloads` |
| `time/ExpectedProfitBook.java` | 2769 | P0.5 拆；终态 `ProductionProspectBook`、`WageProspectBook`、`MerchantProspectBook`、`RosterIncomeBook`、`ProspectArithmetic` |
| `time/ModeMigrationSettlement.java` | 2682 | `MigrationPopulationMove`、`MigrationAssetMove`、`MigrationDebtMove`、`MigrationExperienceMove`、`ModeMigrationSettlement` 门面 |
| `codec/EconomyCodec.java` | 2143 | `EconomyCodec` 门面 + `EconomyCoreCodecs`、`EconomyMarketCodecs`、`EconomyDebtCodecs`、`EconomyProductionCodecs` |
| `time/LaborQueueSettlement.java` | 2105 | P0.5 拆；终态 `LaborCandidateBook`、`LaborQueuePlanBook`、`LaborQueueWritebackBook`、`ArbitrageLaborBook` |
| `time/MerchantCapacityPool.java` | 1754 | `MerchantCapacityPool`、`MerchantFreightQuoteBook`、`MerchantCapacityMath` |
| `time/EconomyLiquidationSettlement.java` | 1667 | P0.5 拆出 `DebtStressBook`、`CrisisSignalBook`；P2 再删 Pledge/抵押分支；class slide 已在 `ClassSlideSettlement` |
| `time/EconomyEntrySettlement.java` | 1634 | `EntryCandidateBook`、`EntryEvaluationBook`、`EntryExecutionBook` |
| `time/EconomyEnterpriseSettlement.java` | 1624 | `EnterpriseOrganizationBook`、`EnterpriseStatusBook` |
| `spi/EconomyGmAdjustments.java` | 1474 | `GmHouseholdAdjustments`、`GmMarketAdjustments`、`GmProductionAdjustments` |
| `time/FxSettlement.java` | 1343 | `FxQuoteBook`、`FxMatchBook`、`FxSettlement` 门面 |
| `time/AssetOccupancyBook.java` | 1326 | `AssetOccupancyBook` 门面 + `AssetOccupancyAllocator` + `AssetOccupancyReadout`（复用已有 `AssetOccupancyBudget/Allocation`） |
| `EconomyReferenceValidation.java` | 1297 | `HouseholdReferenceValidation`、`MarketReferenceValidation`、`ProductionReferenceValidation`、`DebtReferenceValidation` |
| `time/HouseholdClassRule.java` | 1022 | `HouseholdClassRule` + `ClassProjectionBook` |
| `simos-app/.../world/EconomySeeder.java` | 6089 | P0.5 拆：`EconomySeeder` 门面 + `RuralHouseholdSeeder`、`LandOwnershipSeeder`、`IndustrySeeder`、`ProductionRuntimeSeeder`、`MarketSeeder`、`WorldBalanceSeeder` |

### 6A.3 拆分执行规则

1. 先对目标文件记 `wc -l` + `sha256sum`；拆分不改行为。
2. 优先抽 package-private 静态类；原类保留同名入口委托。
3. 每抽一组编译：
   - economy：`tools/mvn-lock.sh -pl simos-economy -am -DskipTests compile`
   - app：`tools/mvn-lock.sh -pl simos-app -am -DskipTests compile`
   失败先回滚该组，不叠加第二组。
4. **行为不变重构的回归门禁**：每抽完一个结构组，控制方跑受影响模块的**现有测试**（不新写测试）：
   - economy：`tools/mvn-lock.sh -pl simos-economy -am test`
   - app：`tools/mvn-lock.sh -pl simos-app -am test`
   这是 P0.5 的拆分安全网，不替代 P6 的新测试/变异自证。
5. 不把内部实现升成跨模块 public API；不放宽模块边界。
6. P6 加机械守卫：任何 `simos-economy/src/main/java/**/*.java` 或 `simos-app/.../world/EconomySeeder.java` >1000 行 ⇒ 红；守卫上线前用 `find ... | xargs wc -l | sort -nr` 出全表。
7. 拆分顺序：先 `EconomySettlement`/`MarketSettlement`（最大、最常改）→ `EconomyData`/`EconomyCodec`/`EconomyPayloads` → `EconomySeeder` → 其余 >1000 行文件。`EconomySeeder` 必须在 P5 改它之前拆完。

### 6A.4 派单提示词（复制给 W6）

```text
你是 W6 economy-file-split。工作树 /home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module。
先读 master-dispatch §0–§4 + §6A、P0 计划 §5、AGENTS §一.5/§三/§四。
责任区：P0.5 经济大文件拆分先行。只做行为不变的机械抽取；不改公式/状态形状/公开 API 语义；不 commit/push；不新写测试。
顺序：EconomySettlement/MarketSettlement → EconomyData/Codec/Payloads → EconomySeeder → 其余 >1000 行文件。
每抽一组编译：economy `tools/mvn-lock.sh -pl simos-economy -am -DskipTests compile`；app `tools/mvn-lock.sh -pl simos-app -am -DskipTests compile`。
每组后控制方跑受影响模块现有测试；失败先回滚该组。
报告：各文件拆分前/后行数、编译结果、现有测试回归结果、行为不变声明、未完成/未验证项。
```

### 6A.5 P0.5 分批执行（一次只派一个写者）

P0.5 整体过大，不得把 19 个文件塞给一个代理。按“可独立编译、可独立回归”的层顺序派单；每批完成并跑完现有测试后，才允许下一批开工：

| 批次 | owner | 范围 | 完成判据 |
|---|---|---|---|
| P0.5-A1 | W6-A | `EconomySettlement` 第一批：抽出独立静态记录/小工具/消费与投入辅助，保留同名入口委托 | `simos-economy -am` 编译过 + 现有 economy 测试过 + 行数下降 |
| P0.5-A2 | W6-A 续 | `EconomySettlement` 第二批：抽出生产/劳动/人口/账务辅助，继续保留入口委托 | 同上，终态 `EconomySettlement ≤1000` |
| P0.5-B1 | W6-B | `MarketSettlement` 第一批：抽出订单计划/索引/冻结/定价 | 编译 + 现有测试 + 行数下降 |
| P0.5-B2 | W6-B 续 | `MarketSettlement` 第二批：抽出撮合/运输/税/读数 | 同上，终态 `MarketSettlement ≤1000` |
| P0.5-C | W6-C | `EconomyData`、`EconomyCodec`、`EconomyPayloads` | 三文件各自约 ≤1000，编译 + 现有测试 |
| P0.5-D | W6-D | `EconomySeeder` | 门面 ≤1000，编译 + app 现有测试 |
| P0.5-E | W6-E | 其余 >1000 行的 economy 文件（含迁移/工资/清算/FX/占用等） | 全表无 >1000，编译 + 现有测试 |

**批次交接规则**：

- 同一文件同一时刻只有一个写者；A1 完成后 A2 才可接手同一文件。
- 每批只做机械抽取：不改公式、不改状态形状、不改公开 API 语义、不删分支。
- 每批结束必须记录 `wc -l` 前后表与 `sha256sum`；控制方跑现有测试后才批准下一批。
- 若某批无法在不改行为的前提下收敛到目标，报 `BLOCKED` 并给最小额外范围，不得用“先改行为再拆”绕过。



## §7 P1 — Social 先 + 单槽事实

### 7.1 必读

- 本文件 §2/§4/§7；
- tick contract §3.1/§3.2 M0–M1/§3.3/§4/§5 不变量 1–2；
- 设计 §7/§9/§10.1；
- `docs/agents/02-一-并发.md` 的责任区/测试纪律；
- `PopulationEconomyTimeParticipant.java` 全文日循环；`PopulationUnitTimeParticipant.java`。

### 7.2 架构设计

新增 app 侧瞬态：

```java
record PendingFamineFacts(long sourceTick, FamineMortalityInput input) {
  static PendingFamineFacts empty();
}
```

- 字段挂 `PopulationEconomyTimeParticipant`（同一 Shell 生命周期跨 `simulateWorld` 保留）。
- 每个 day：
  1. 取上一份 `pendingFamineFacts`；
  2. `HouseholdBook.settleOneTick(currentSocial, day, ..., pending.input())`；
  3. 回写人口 delta、刷新 composition/labor/naturalNeeds；
  4. `productionRuntime.beginTick(day)`；
  5. `stepper.step(day)`；
  6. drain gaps → `FamineMortalityBridge.apply` → 生成 `nextPendingFacts`（局部变量）；
  7. 完成 gov/税等当前阶段、`finish()`、成功落账后，才把字段换成 `nextPendingFacts`；
  8. 失败抛异常时保留上一份有效事实，不覆盖。
- `EconomyOwnershipTimeParticipant` 是退役备用路径：仍可 drain-and-drop，不实现 Social；不扩展它。

### 7.3 代码调整

- 改 `PopulationEconomyTimeParticipant` 日循环顺序；删除“经济后再用同日 gap 调用 Social”的旧段。
- 新增 `PendingFamineFacts`（app 包内，包内可见）。
- 必要更新 `PopulationUnitTimeParticipant` 只读转发/测试入口。
- 不改 `EconomyData`/Codec/ChangeSet（单槽不是持久状态）。

### 7.4 文件所有权

- 允许（app）：`simos-app/src/main/java/io/mosire/simos/app/time/PopulationEconomyTimeParticipant.java`、`PopulationUnitTimeParticipant.java`、app 侧新 `PendingFamineFacts.java`。
- 允许（economy，仅窄写回 API）：`simos-economy/src/main/java/io/mosire/simos/economy/time/EconomyDayStepper.java`、`PopulationWritebackSettlement.java`。仅允许新增 `package-private`/必要 public 的**人口-only 写回**与**生死流水-only 写回**方法，用于把 M0 的 Social 生死事实在 `stepper.step(day)` 前落到人口行、在 step 后补记生死流水；不得改既有公式/状态形状/持久字段。
- 禁止：`simos-social/src/main/**`、测试文件（P6 才写测试）、其他 economy 生产文件。

### 7.5 验收判据

- 首 tick 空事实；不伪造死亡。
- `advance(0,30)` / `advance(0,60)` / `advance(0,30)+advance(30,60)` 逐日结果相同。
- 失败 advance 不覆盖上一份有效事实。
- tick t 的经济看不到 tick t 的出生/死亡；只看到 M0 后活家户。
- 经济侧没有新增 `PendingFamineFacts` 持久组件/Codec 字段。

### 7.6 派单提示词（复制给 W1）

```text
你是 W1 app-tick。工作树 /home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module。
先读 master-dispatch §0–§4 + §7、tick contract §3.1–§3.3/§4/§5 不变量 1–2、设计 §7/§9/§10.1，以及 PopulationEconomyTimeParticipant 全文。
责任区：P1 Social 先 + 单槽 PendingFamineFacts。
只改允许文件；只写生产代码，写到 `tools/mvn-lock.sh -pl simos-app -am -DskipTests compile` 通过；不写/不跑测试；不 commit/push；不碰主树。
发现设计与用户原话冲突立即停手报告。报告改动文件、编译结果、会改变数值行为的清单、未完成/未验证项。
```

## §8 P2 — 统一市场（商品+土地）+ 债务最先 + 删 Pledge

### 8.1 必读

- 本文件 §2/§4/§5/§8；
- tick contract §3.2 M2/M3/M4、§4、§5 不变量 3–6/8–9/15/17–18；
- 设计 §17/§18/§23–§32/§37–§45；
- `production-runtime-boundary-decision.md`、`production-execution-decision.md`、`r2-shared-asset-minimal-design.md`、`production-runtime-migration-design.md` 相关章节、`asset-occupancy-design.md` 相关章节；
- 当前 `MarketSettlement`/`EconomySettlement` 债务段/`Pledge`/`DebtContractBook`/`OwnershipStake`/`MarketTable` 全文。

### 8.2 架构设计

**A. 土地身份**

- `OwnershipStake.id` 改为**稳定资产块 id**：id 只由 `(industry, asset, location, sequence)` 派生，不把 owner/operator/kind 编进 id；所有权转移改 owner/operator，id 不变。
- `OwnershipStake` 仍是 LAND 数量/产权唯一权威；LAND 不进 `HouseholdGoods`。
- 面积沿用千分亩；质量用世界数据/hex 系数（若 P2 设计书选择给 `OwnershipStake` 加 `qualityPerMille`，必须全表默认 1000 并做 codec/roundtrip；不得另建第二本土地账）。

**B. 市场区土地价格**

- `MarketTable` 增加 `OptionalLong landPrice`：`empty` = 未定价，`0` = 明确免费，二者分开。
- 土地数量/质量按 hex；价格按市场区一个价；市场区内的 hex 共用该价。
- 土地价格调整沿用现有 `adaptiveNextPrice` 的 α 口径，跨市场轮生效；不在单轮内无限试价。

**C. 统一市场**

- 商品订单继续用现有 `BuyOrder/SellOrder`（`CommodityId`）。
- 新增资产订单变体：引用 `AssetShareId`、整块数量、限价、支付币、预算；商品与土地订单进**同一订单簿/同一撮合器**，共用预算/冻结/撮合/结算。
- 共享预算/冻结：同一户商品+土地订单不得双重支付；基础消费/生产投入先于土地投资；稳定 tie-break。
- 现金不足且有成交 ⇒ 普通货币债（沿用现有 deferred payment 机制，不另造信用路径）。
- 土地订单成交只改 `OwnershipStake.owner/operator`，不搬商品库存；买方本轮取得集合记入会话瞬态 `landAcquiredThisRound`。
- 卖地中断：M6 重查所有权；失去土地的位置失败、劳动转无业，土地荒置到下一轮。

**D. 债务清算**

- 新增 `DebtSettlement`（从 `EconomySettlement` 抽离），顺序：
  1. 取纯库存财富快照（不含工具/作坊、不扣债）；
  2. 债务人 `HouseholdId` 升序；
  3. 同一债务人的债权人按纯库存财富降序 → creditor id → debt id；
  4. 受偿：货币 → 库存（低估值优先）→ 土地（按所属市场区价、整块、不找零）；
  5. 债权人全灭：`DebtStatus.FORGIVEN`，土地留债务人；
  6. 余债继续挂账，不强制出售、不伪造买家、不新增 Pledge。
- `DebtUnit` 只保留 Money：删除 `DebtUnit.Commodity` 写路径；租金/欠薪等实物欠款在形成时按当地价折成货币。
- 删除 `Pledge` 组件/命令/Codec/UI/清算分支；`EconomyLiquidationSettlement` 中非 Pledge 的 class-slide/crisis 逻辑按需抽到 `DebtStressBook`/`CrisisSignalBook`，不得保留自动质押。

**E. 市场轮**

- `MarketTrigger` 只保留 `PERIODIC`（`day % 5 == 0`）与 `NONE`；删除 `LOW_GRAIN_STOCK`/`CYCLE_CLOSE` 与 `lowGrainStock`。
- 删除 35 天生活储备：卖/买保留按“到下一市场轮的 5 tick 基础消费 + 生产投入”；保留 `MARKET_RESTOCK_INTERVAL_DAYS=5`，删除 30 天安全库存常量或归零。

**F. 文件拆分**

- `MarketSettlement` 拆成 §5 的订单/撮合/冻结/定价/货币/运输/税/读数类；`EconomySettlement` 抽出 `DebtSettlement`。
- 每个抽取步骤编译通过；不许一次性大爆炸。

### 8.3 代码调整清单

**保留/复用**：
- `DebtContractBook`、`DebtContract`、`DebtTerms`、`DebtStatus`（改为 Money only）。
- `AccountSession`、冻结/转账落账、`SettlementOrderBook`（净值仍可用于 class slide，但市场排序改纯库存财富）。
- `MarketSettlement` 的商品撮合/冻结/运输能力。
- `OwnershipStake`、`AssetOccupancyGrant`、`InventoryReservation`（LAND 只作资产，不作普通库存）。

**新增**：
- `DebtSettlement`、`DebtLandTransfer`/审计记录。
- `MarketTable.landPrice`（`OptionalLong`，empty=未定价，0=合法）。
- 统一市场资产订单类型（economy 内部/contract，按 P2 设计书）。
- `PureInventoryWealthBook`（P3 完整用；P2 债务排序先用）。
- `landAcquiredThisRound` 会话瞬态集合。

**改/删**：
- `MarketSettlement.triggerFor` 删追加轮。
- `MARKET_LIFE_RESERVE_DAYS` 删 30 天安全库存。
- `Pledge`、`PledgeId`、`EconomyLiquidationSettlement` 抵押分支。
- `DebtUnit.Commodity`。
- `SettlementOrderBook` 市场排序：净值 → 纯库存财富（class slide 仍用净值）。
- `EconomyData` 删 `pledges` 组件/withPledges/validation；ChangeSet/Codec/roundtrip 同步。

### 8.4 文件所有权

- 允许：`simos-economy/src/main/java/io/mosire/simos/economy/time/**`、`model/**`、`change/**`、`codec/**`、`economy/EconomyData.java`、`EconomyReferenceValidation.java`、`simos-economy-api` 的 debt/asset/market 契约（若必须）、`simos-app` 只读装配/视图（若 Pledge/DebtUnit/OwnershipStake 引用清理需要）。
- **控制方批准的两处例外**：① P2b/P2c/P2d 允许机械修改 `simos-app/.../world/EconomySeeder.java`、`EconomySeederConditions.java`、`ProductionPayloadSeeder.java`、`TestConditions.java` 及其他直接引用被删类型的 app world 文件；只许删除 Pledge/DebtUnit.Commodity/旧 `OwnershipStake.idOf` 的编译残留，**不得改初始土地分布、生产方式、种子数值、租佃默认或任何 P5 玩法**；② P2 的 Seeder 改动在 P5 会被再次审查，不改变 P5 owner。
- 禁止：`simos-production` 生产公式；`simos-social`；测试；P5 的农村默认/生产模板/初始分布。
- 批次边界见 `2026-10-18-d032-p2-unified-market-debt-constraint.md` §2；一次只允许一个写者，同一 `EconomySettlement`/`MarketSettlement` 不得并行。

### 8.5 验收判据

- 市场轮只 `day%5==0`；第 3 天低库存不再开市。
- 商品+土地进同一订单簿/同一撮合器同轮完成；土地订单不进 `HouseholdGoods`；无双记。
- 土地价格按市场区一个价；hex 只决定数量/质量；0 地价合法；未定价与 0 分开；无价不成交、不伪造。
- 债务最先；债权人财富降序；货币→库存→土地；整块不找零；余债继续。
- 土地价值>债务/土地价值<债务/无债权人/债权人全灭四条边界。
- 本轮取得土地标记 `landAcquiredThisRound` 存在且不落盘；P2 只验标记/产权行为，**M3/M6/M7 的不可用与卖地中断下沉 P3 验收**。
- 无 Pledge 读写；无 `DebtUnit.Commodity` 新写。
- 无 35 天储备。
- `MarketSettlement`/`EconomySettlement` 已拆分，P2 只保证每文件 ≤1000 行、编译通过。

### 8.6 派单提示词（复制给 W2）

```text
你是 W2 economy-market-debt。工作树 /home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module。
先读 master-dispatch §0–§4 + §8、P2 约束设计书 `2026-10-18-d032-p2-unified-market-debt-constraint.md` 全文、tick contract §3.2 M2/M3/M4 与 §5 不变量 3–6/8–9/15/17–18、设计 §17/§18/§23–§32/§37–§45，以及 §8.1 列出的旧 D-032 文档章节。
责任区：P2 统一市场（商品+土地）+ 债务最先 + 删 Pledge。
按 P2 约束设计书 §2 的 P2a→P2f 顺序执行；每批编译绿后才进下一批。
只写生产代码，写到 `tools/mvn-lock.sh -pl simos-economy -am -DskipTests compile` / app 批次写到 `-pl simos-app -am` 通过；不写/不跑测试；不 commit/push；只改允许文件。
发现设计冲突停手报告；不得重开 Pledge/抵押/商品债。
报告改动文件、编译结果、会改变数值行为的清单、受影响硬编码、可能失效旧测试、未完成/未验证项。
```

## §9 P3 — 两阶段单家户生产 + 雇佣锁 + 纯库存财富

### 9.1 必读

- 本文件 §2/§4/§5/§9、tick contract §3.1.1/§3.2 M3/M6/§5；
- 农业模板 §1–§7；
- 设计 §19–§32；
- 原始 D-032 U1–U23（尤其 U4/U6/U8/U13–U18）；
- `cooperative-production-design.md`、`production-experience-choice-design.md`、`production-definition-v2-freeze.md`、`stage1/stage2` 决策、审计 A1–A5；
- 当前 `ExpectedProfitBook`、`LaborQueueSettlement/Book`、`ProductionRoster`、`ProductionRuntime` 全文。

### 9.2 架构设计

**A. 两阶段对象（瞬态）**

```java
record ExpectedProductionPlan(
    long day,
    Map<HouseholdId, HouseholdProductionPlan> byHousehold,
    Map<WageAnchorKey, WageDemand> wageDemand,
    List<EmploymentLock> employmentLocks,
    List<MarketIntent> marketIntents);

record ActualProductionPlan(
    long day,
    Map<HouseholdId, HouseholdProductionPlan> byHousehold,
    Map<ProductionUnitId, ActualUnitPlan> byUnit);

record HouseholdProductionPlan(HouseholdId household, List<PositionPlan> positions, long expectedNet, long laborBudget);
record PositionPlan(IndustryId plan, ProductionRoleId role, ProductionLocation location, long scale, long selfLabor, long hiredLabor);
record EmploymentLock(HouseholdId employer, HouseholdId worker, ProductionRoleId role, long laborMilli, long wagePerLaborMilli, CurrencyId currency);
```

- **归属（用户 2026-10-18 已裁定 3A）**：预期/实际计划、雇佣锁、`ProductionRoster`、劳动分配归 economy；production 只返回物理预计/实际产出、进度/经验/生产状态变化。
- 两轮都用同一 `positionNet/positionYieldPerLabor` 算子。
- 第一轮只写瞬态 + `landAcquiredThisRound` 排除；不写 inventory/money/land/debt/production instance。
- 第二轮写持久 `ProductionRoster`/劳动承诺/实际计划（同 revision）。

**B. 纯库存财富**

- `PureInventoryWealthBook`：
  - 钱：所有币种按市场区 `CurrencyValuation` 折成本区 numeraire；
  - 货物：按本区价格；
  - 土地：按所属市场区当前地价；hex 只决定面积/质量；
  - 不扣债务；工具/作坊不计入。
- 生产位置排序和市场买方排序共用它。
- `SettlementOrderBook` 的净值保留给 class slide；市场排序改纯库存财富。

**C. 土地/生产位置选择**

- 有地家户：在自己的土地/hex 上按 `grain_farm` 模板选择生产位置；跨 hex 自耕拒绝，必须雇本地雇农。
- 无地户：只生成 `hired_laborer` 供给/无业。
- 第一轮家户序：纯库存财富降序 → household id；每家户按单位劳动净收益贪心；劳动预算完为止。
- 土地买盘按 hex：`desired land − current usable land`；只有正净收益才挂；数值工程定。
- 雇佣锁：第一轮确定；M6 只读。

**D. 第二轮回填**

- 取实际库存/货币/土地 + 实际劳动预算 + 雇佣锁。
- 非雇佣小生产按同公式重排；失败位置转无业。
- 第二轮不得下单、不得新增信用、不得改工资锚/雇佣锁。
- 实际库存不足：锁定雇佣仍投入；雇主工资现金不足 → 普通工资债（P4 支付）。

**E. 生产运行时契约**

- 现 `ProductionRuntime.probe(ProductionProbeRequest)` 只支持一个家户条件，不能正确处理合作名册逐户经验；
- P3 需在 `simos-production/contract` 增加**名册/逐参与户 probe**：
  - 输入：plan、`Map<HouseholdId, HouseholdProductionConditions>`、participant role/labor、assetOccupancy、carry、attributes；
  - 输出：scale/output/input/efficiency，与实际 `executeDay` 同一公式；
  - 不修改状态、不涨经验；
- 零劳动 organizer + posting target：probe 用 `targetLabor` 作需求边界，不塞假 worker；fix audit A3。
- PRODUCT_SHARE 选业排序改用 `rosterIncomes` 本户预计所得减本户负担；fix audit A4。
- 两阶段都没算出的新雇佣位置，第二轮不得临时加选。

### 9.3 代码调整清单

**新增**：
- `ExpectedProductionPlan`/`ActualProductionPlan`/`HouseholdProductionPlan`/`PositionPlan`/`EmploymentLock`/`MarketIntent`（同 tick 瞬态）。
- `ExpectedPlanSettlement`、`ActualPlanSettlement`（或等价 phase）。
- `PureInventoryWealthBook`。
- `ProductionProspectBook`/`WageProspectBook`/`RosterIncomeBook`（从 `ExpectedProfitBook` 拆）。
- `LaborCandidateBook`/`LaborQueuePlanBook`/`LaborQueueWritebackBook`（从 `LaborQueueSettlement` 拆）。
- `ProductionRosterProbeRequest/Result`（production.contract）。

**改/删**：
- `EconomySettlement` 只留编排门面；两轮计划/执行抽到 phase。
- `LaborQueueSettlement` 的“每 tick 全量排队”改为：第一轮预期 + 第二轮实际；中间日按锁定计划确认。
- `ExpectedProfitBook` 拆；删除 landlord/dependent 过滤、rent 分支；用农业模板。
- `SettlementOrderBook` 市场排序改纯库存财富；净值保留 class slide。
- `SettlementStage` 枚举按新日序重排/改名（M0–M9）；旧阶段名不得留假语义。
- `ProductionRuntime.probe` 旧单户入口保留给单户窄测试；生产/候选/名册走新 roster probe。

### 9.4 文件所有权

- 允许：`simos-economy/time/**`（`EconomySettlement`、`ExpectedProfitBook`、`LaborQueue*`、`SettlementOrderBook`、`SettlementStage`、新纯函数）、`simos-production/contract/**`（仅 probe/execute 契约）、必要的 `simos-economy/change/**`/`codec/**`（若瞬态不持久则不碰）。
- 禁止：`WageSettlement` 支付算法（P4）、`EconomySeeder`（P5）、测试、POM/XML。

### 9.5 验收判据

- 第一轮/第二轮都按本户净收益最大化；两轮同一公式；
- 第一轮订单意向进入统一市场；第二轮不产生订单；
- 雇佣锁：M6 不改雇主/雇工/份额/工资；受雇方仍投入；
- 有雇佣+小生产并存；雇佣方库存不足仍锁关系；
- 新买/抵债土地本轮不用；卖地中断；
- 纯库存财富不扣债、不含工具/作坊；
- 跨 hex 自耕拒绝、本地雇农成功；
- 审计 A2/A3/A4 的真实行为测试通过（P6 写测试）。

### 9.6 派单提示词（复制给 W3）

```text
你是 W3 economy-production-tick。工作树 /home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module。
先读 master-dispatch §0–§4 + §9、tick contract §3.1.1/§3.2 M3/M6/§5、农业模板 §1–§7、设计 §19–§32、原始 D-032 U1–U23，以及 §9.1 列出的旧 D-032 文档与审计 A1–A5。
责任区：P3 两阶段单家户生产 + 雇佣锁 + 纯库存财富 + 生产 roster probe。
只写生产代码，写到 `tools/mvn-lock.sh -pl simos-economy,simos-production -am -DskipTests compile` 通过；不写/不跑测试；不 commit/push；只改允许文件。
必须遵守：第一轮预期/第二轮实际同一目标函数；第二轮不下单、不改雇佣锁、不用新土地。
报告改动文件、编译结果、数值行为变化、受影响硬编码、旧测试可能失效项、未完成/未验证项。
```

### 9.7 P3d — 土地意向与统一市场字面收口（2026-10-19 复核新增）

> 复核依据：`docs/superpowers/reports/2026-10-19-d032-original-design-reaudit.md` §3.1/§3.2。
> 这不是新玩法：用户已定“土地可买卖/无地户可买地/同一订单簿同一撮合器”。本批只补齐已定口径。

**目标**
- M3 除商品 intent 外，生成**整块 LAND intent**，且需求必须来自生产位置，不是拍脑袋：
  - `PositionPlan.landQuantityMilliMu` 从 production probe 的 `assetRequired().get(LAND)`（或等价 `scale × capacityPerUnit[LAND]`）写入；不得再固定为 0；
  - 对已选生产位置：所需土地 > 当前可用/已占土地 ⇒ 生成 LAND BUY；本轮未使用的自有 LAND 份额 ⇒ 生成 LAND SELL；
  - 对无地/少地但有劳动、有现金且能算出正预期农业净收益的家户：按该 hex 的可用农业生产方案生成一个“最小整块”LAND BUY 需求；具体是借用该 hex 的候选生产 unit/roster 估算，**不得**复制 production 配方公式到 economy；
  - 卖出/买入都按 hex 生成，价格用该 hex 所属市场区的 `MarketTable.landPrice`；priority=2，土地只吃商品冻结后的剩余预算；不得重复冻结/双付；
  - 本轮新买/抵债土地仍记 `landAcquiredThisRound`，M3/M6/M7 排除，下一市场轮才可用。
- 统一市场字面收口：商品与土地订单进入**同一订单序列/同一匹配入口**，按订单变体分派商品钱货腿或土地产权腿；不得保留“商品 matcher + 独立土地 matcher”的两个匹配器结构。`MarketRoundRunner` 只能调一个统一 matcher 入口，土地私有子步骤也只能是该入口内部的分派。
- 保持商品既有算法、价格、冻结、运输、税、延迟付款数值不变；土地撮合失败继续具名 INFO，不伪造成交。
- P5 的初始土地/锚/模板不在本批；P4d barter schema 不在本批。

**允许文件（writer 一次写完，编译到绿）**
- `simos-economy/src/main/java/io/mosire/simos/economy/time/**` 中 `MarketIntent`、`ProductionPlanBridge`、`SettlementDayExpectedProductionPipeline`、`MarketRoundCore`、`MarketRoundRunner`、`MarketSettlement`、`MarketOrderPlanner`、`MarketLand*`、`MarketTradeExecutor` 等直接相关文件；
- 必要的 economy `model/**` 只读形状（若统一订单需要新内部订单类型）；不改 Codec/ChangeSet/持久状态形状，土地订单仍是轮内瞬态。
- 禁止：测试、POM/XML、P5 Seeder、P4 工资/债务语义、production 公式、主树。

**验收**
- 正常市场日不再只生成 COMMODITY；存在可卖块/可买户时生成 LAND intent；
- 商品+土地在同一订单序列/同一匹配入口处理；共享预算/冻结；无第二套土地市场阶段；
- 本题交付只要求 economy/app 编译绿 + 静态审计；行为/回归测试由 T1 在 P4d/P5 后统一迁移执行。

### 9.8 P3e — daily plan correctness（2026-10-19 复核新增，先于小世界）

> 复核依据：`docs/superpowers/reports/2026-10-19-d032-original-design-reaudit.md` §3.3/§3.4/§3.6/§3.9/§3.10/§3.11。
> 这些是已定口径的实现 bug，不是新玩法。

**修**
1. **基础消费只准备到下一市场轮**：`ProductionPlanBridge.marketIntents` 的 naturalNeeds/可售自留量改用 `MarketConstants.MARKET_RESTOCK_INTERVAL_DAYS`（=5），不得再用生产周期 `cycleDaysByHousehold`（Seeder 当前 120）。生产投入仍按 probe 的本周期需求，不能混用。
2. **零劳动 organizer/产品份额位置可入选**：M3/M6 对 `selfLaborMilliPerDay()==0` 但 `positionNet>0` 的位置，允许“不消耗本户劳动预算”地入选；不得再无条件 `continue`。同一 unit/role 不重复。
3. **WageDemand target 用 roster 实际计划雇工**：`PositionPlan.hiredLaborMilliPerDay` 不能两个分支都写 0；organizer 位置应记录其 roster 中非 organizer、MONEY_WAGE 角色的计划劳动合计（per day）。`wageDemandsOf`/M3 的 organizerWageCost 优先用它；只有确实没有计划雇工时才回退标准劳动/周期天数。目标=0 仍不生成 WageDemand。
4. **legacy fallback 判据改成 roster 存在性**：只有生产 roster 真正为空才走旧链；有 roster 但因缺锚/缺地/负收益而没有入选 unit，应在新链中合法地“无生产/无业”，不得回退旧链。
5. **实际不可行的雇佣锁不得 materialize**：M6 先按 `actualBySlot`/正收益/预算/unit 存活过滤出 `actualValidLocks`，再写 labor commitments，并让 `ActualProductionPlan.locks` 与该集合一致。
6. **日志口径**：`D032_P3_EXPECTED_PLAN` 的 `households` 改成真实家户数（不是 unit 数）；至少补 `D032_TICK_CHAIN` 或等价逐户/逐 stage 的 INFO 汇总，便于小世界全链路诊断。

**边界**
- 不改工资现金/欠薪债、U20 调价公式、P2 债务顺序、土地价格/身份、P4d barter、P5 Seeder。
- `ProductionPlanBridge`/`MarketRoundCore` 仍须 ≤1000 行；必要时先机械抽取 `MarketIntentBook`/`WageQuoteBridge` 等职责类，再改行为。
- 只写生产代码；economy/app `-DskipTests compile` 绿；不写/不跑测试；不 commit/push。

## §10 P4 — 工资现金支付 + 普通债 + U20 工资锚

### 10.1 必读

- 本文件 §2/§4/§5/§10；
- tick contract §3.2 M8/M9/§5 不变量 11–14；
- 设计 §33–§38；
- 原始 D-032 U15–U23；
- `wage-chain-design.md`、`wage-idle-barter-decision.md`、`wage-settlement-simplification-decision.md`、`tick-wage-feedback-proposal.md` 中未被 2026-10-18 新裁定取代的部分；
- 当前 `WageSettlement`、`ProductionWageBook`、`WagePostingKey`、`ProductionWagePosting`、`ProductionWageDefault`、`WageSettlementReadout` 全文。

### 10.2 架构设计

**A. 工资锚（U20）**

- 新状态：`ProductionWageAnchor`：
  - key = `(MarketZoneId, IndustryId, ProductionRoleId)`（市场区 × 生产方式 × 位置）；
  - 字段：currency、wagePerLaborMilli、shortageStreak、satisfiedStreak、lastAdjustedTick、lastObservedMatched、lastObservedTarget、lastAdjustmentDelta；
  - 0 合法；缺报价 = empty，不拿 0 冒充缺失。
- 雇主需求：`WageDemand`（unit/role/targetLabor）由 M3 预期计划产生，瞬态；工资锚由市场轮统一调，不由每个主导者自定。
- 删除/退役 `WagePostingKey` 的 `(unit, role)` 语义与 `ProductionWagePosting` 的每 unit 工资；`ProductionWageDefault` 不再作为第二报价权威。
- 每市场轮（`day%5==0`）在 M8 后：
  - 读本市场轮 M3 的 target 与 M8 实际 matched labor；
  - 调 next anchor（缺工快速累加加价，招满后缓慢降价；参数可配置）；
  - 本 tick 已发生的 due 不被新报价追溯。
- 非市场日不调工资锚。

**B. 工资支付**

- `WageSettlement.settle`：
  - 只收 actual labor receipts；
  - due 用 anchor quote × actual labor + carry；
  - 同一雇主多义务按现有稳定 `OBLIGATION_ORDER` 付到现金用完；
  - 不调用 `payBarter`/`WagePayableInventoryLookup`；
  - 未付余额 `DebtContractBook.upsert(payer, worker, DebtUnit.Money(currency), wageArrearsDefault(), WAGE_ARREARS, arrears, day, dueCycle)`；
  - 不再需要 same-day wage debt exclusion（M8 在 M2 后，结构上不可能同日再还）。
- 还债阶段（P2 M2）允许库存/土地；工资债和其他债同池，按债权人纯库存财富序。
- 保留 0‰ 工资债默认（U23），除非用户另裁。

**C. 删除旧抵物**

- 删除 `WageSettlement` 的 `InventoryBudget`/`barterOrder`/`payBarter`/`WageSettlementReadout` 的 barter 字段；
- 删除 `WagePayableInventoryLookup` 从工资支付路径的注入（如其他读口仍用，保留但不得工资支付调用）；
- 删除 `BarterDiscountPolicy` 进入工资链的路径；
- P4 不重新引入商品本金债。

### 10.3 代码调整清单

**新增**：
- `ProductionWageAnchor`/`WageAnchorKey`/`WageObservation`/`WagePricingPolicy`（可复用/改现 `ProductionWageBook` 的 policy）。
- `WageAnchorBook`（市场轮调价）。

**改/删**：
- `WageSettlement`：只付货币；删除抵物。
- `ProductionWageBook`：改锚读写；U20 `adjustedPostings` 改为 `adjustedAnchors` 并接真实市场轮调用。
- `WagePostingKey`/`ProductionWagePosting`/`ProductionWageDefault`：删除或退出农村主链（Alpha 不兼容旧档）。
- `WageSettlementReadout`：删除 barter 栏；新增 anchor/matched/target 读数。
- `EconomyData`/ChangeSet/Codec：工资锚组件替换旧两表；往返测试同步（P6）。
- `EconomySettlement`：M8 后调用 anchor adjust（只 market day）。

### 10.4 文件所有权

- 允许：`simos-economy/time/WageSettlement.java`、`ProductionWageBook.java`、`model/ProductionWage*`、`model/Wage*Key*`、`WageSettlementReadout*`、必要的 `EconomyData`/ChangeSet/Codec 工资组件、`EconomySettlement` 仅加 M8 后调价调用点。
- 禁止：P2 债务/市场逻辑、P3 两阶段计划、`EconomySeeder`、测试。

### 10.5 验收判据

- 只付货币；现金不足部分支付/完全无现金；不拿库存抵工资。
- 未付额成普通 `WAGE_ARREARS` 货币债；下一市场轮 M2 同池处理。
- 0 工资不特判；U20 真实多 tick：缺工加价、招满降价、本 tick 工资不被追改。
- 工资锚键 = 市场区×生产方式×位置；同一锚跨雇主共享。
- 第二轮/锁定雇佣不受工资锚更新影响。
- 删除抵物后，审计 A5 自然消失，旧 A5 测试不得保留“仍支持抵物”。

### 10.6 派单提示词（复制给 W4）

```text
你是 W4 economy-wage。工作树 /home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module。
先读 master-dispatch §0–§4 + §10、tick contract §3.2 M8/M9/§5 不变量 11–14、设计 §33–§38、原始 D-032 U15–U23，以及 §10.1 列出的工资链文档未被新裁定覆盖的部分。
责任区：P4 工资现金支付 + 普通债 + U20 工资锚。
只写生产代码，写到 `tools/mvn-lock.sh -pl simos-economy -am -DskipTests compile` 通过；不写/不跑测试；不 commit/push；只改允许文件。
不得重新引入工资抵物/库存抵押/预付；不得改 P3 两轮计划；不得改 P2 债务排序。
报告改动文件、编译结果、数值行为变化、旧测试可能失效项、未完成/未验证项。
```

## §11 P5 — 农村范围收口 + 无级农业模板 + Seeder

### 11.1 必读

- 本文件 §2/§4/§4.6/§11；
- 农业模板全文；
- 设计 §19–§25/§30–§32；
- 原始 D-032 U1–U18；
- `production-definition-v2-freeze.md` 中角色/份额/资格/支付物形状；
- `EconomySeeder.java`、`DefaultProductionModes.java`、`ExpectedProfitBook`（rent/landlord 分支）、`ProductionRules`、`AssetRightKind` 相关代码。

### 11.2 架构设计

**A. 单一 `grain_farm` mode**

- `DefaultProductionModes`：
  - 删除 `TENANCY_FIXED_KIND`/`TENANCY_SHARE`/`TENANCY_CASH`/`WAGE_FARM`/`FAMILY_FARM` 作为农村默认 mode；
  - 新增/保留一个 `GRAIN_FARM` mode，角色 `operator`（PRODUCT_SHARE/剩余） + `hired_laborer`（MONEY_WAGE）；
  - 位置 id 不再按阶级拆；`classPositions` 只保留读数用/或使用 continuous projection。
- `EconomySeeder`：
  - `productionRuntimePositionId` 不再 `poor/middle/rich/landlord → 不同 mode`；
  - 有地户默认 `GRAIN_FARM.operator`；无地户默认 `GRAIN_FARM.hired_laborer`（或 displaced，按有无劳动能力）；
  - 删除 `farmTenantUnit`/租佃 unit/300‰ rent；
  - `ProductionRules` 农村默认 `rules=[]`、`LaborSource.SELF`/`WAGE`，无 `TENANT/SERF`；
  - 初始土地按 hex 逐户差异分配，landless 可存在；Σ 每 hex 土地 = 可分配土地。
- `ExpectedProfitBook`/`LaborQueue*`：
  - 删除按 `currentPosition`/`landlord`/`dependent` 的过滤；
  - 删除 rent 分支/`relationFor` 的 rent 默认；
  - 选业只看 `grain_farm` 模板的净收益。
- `SocialClassId` 可保留为 UI/结算后投影；任何生产选择不得读 `view.stratum()`/`SocialClassId`。

**B. 跨 hex**

- 土地有 hex；本户劳动只能用于本户所在 hex 的土地；跨 hex 土地必须匹配当地 `hired_laborer`。
- 生产实例位置/劳动贡献位置校验：自身劳动贡献的 household hex 必须等于 unit/land hex；否则业务拒绝 `cross-hex-self-labor`。
- 土地购买无运费；市场区边界不变。

**C. 文件拆分**

- P5 写者只改 Seeder/definitions/policy；不顺手改 `EconomySettlement`/`MarketSettlement`（P2/P3 已完成）。

### 11.3 代码调整清单

**改/删**：
- `DefaultProductionModes.java`：合并农村 mode，删 tenancy/wage/family 三档；保留 `merchant`/`displaced` 等其他 mode。
- `EconomySeeder.java`：删阶级→mode 映射、租佃 unit、rent 规则；新增 `grain_farm` primary/secondary unit；初始土地按 hex。
- `ExpectedProfitBook.java`：删 rent/landlord/dependent 分支（已拆到 P3 的 Prospect books）。
- `ModeMigrationSettlement.java`：删 `TENANCY_FIXED_KIND`/`WAGE_FARM` 特殊迁移；统一 `GRAIN_FARM`。
- `LaborSourcePolicy`/`ProductionRules`：农村默认不再有 TENANT/SERF/rent；类型可保留给其他制度。
- `EconomyReferenceValidation`：角色/位置/规则校验适配单一模板。
- `SocialClassId`/`HouseholdClassRule`：只做投影；不得作为选择输入。

### 11.4 文件所有权

- 允许：`simos-economy/model/DefaultProductionModes.java`、`simos-app/world/EconomySeeder.java`、`simos-economy/time/ExpectedProfitBook` 的 rent 残留、`ModeMigrationSettlement` 的 tenancy 分支、`EconomyReferenceValidation`、`ProductionRules`/`LaborSourcePolicy` 的农村默认、必要的 `SocialClassId` 读口（不改选择）。
- 禁止：P2/P3/P4 已冻结的市场/债务/两阶段/工资实现；测试；POM/XML。

### 11.5 验收判据

- 同一 `grain_farm` mode 下，大地少劳户雇工、小地多劳户自耕；不是两个 mode。
- 改阶级投影不改生产选择；改土地/劳动/资金才改选择。
- 无地户不能自耕、可被雇；买地后下一轮可组织。
- 无 tenancy/rent/lease；`ProductionRules` 农村默认无 rent。
- 跨 hex 自耕拒绝；本地雇农成功。
- 初始土地 Σ hex 守恒；0 元公共土地只测试预设。
- `SocialClassId` 不进入选业键。

### 11.6 派单提示词（复制给 W5）

```text
你是 W5 rural-scope。工作树 /home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module。
先读 master-dispatch §0–§4 + §11、农业模板全文、设计 §19–§25/§30–§32、原始 D-032 U1–U18，以及 §11.1 列出的定义/Seeder 文档。
责任区：P5 农村范围收口 + 单一 grain_farm 模板 + Seeder/租佃清理。
只写生产代码，写到 `tools/mvn-lock.sh -pl simos-economy,simos-app -am -DskipTests compile` 通过；不写/不跑测试；不 commit/push；只改允许文件。
不得按阶级拆 mode；不得保留 rent/tenancy 默认；阶级标签只做投影。
报告改动文件、编译结果、数值行为变化、旧测试可能失效项、未完成/未验证项。
```

### 11.7 P5b — 完整新数据结构小世界的 Seeder 接线

> P5a 只收口 mode/角色/旧 rent/阶级映射；P5b 才播种初始土地、unit、roster、锚、地价。用户已确认：测试迁移完后直接做一个“完整新数据结构的小世界”跑真实链路。

**目标**
- `grain_farm` 是唯一农村生产 mode；有地户默认 operator，无地户可作为 hired_laborer 参与他人农场，也可保留零土地 unit 等待下一轮买地后组织。
- 初始土地：
  - 每个农村 hex 的可耕地总量 = `LandOwnershipSeeder`/`IndustrySeeder` 的 LAND capacity；
  - 逐农村家户生成 LAND `OwnershipStake`（owner=operator=家户），数量按确定性工程权重分配；Σ = 该 hex 可分配土地；
  - 允许 landless 家户土地=0；不得用 ESTATE/TENANCY/rent 关系；
  - 标准预设土地有差异；0 元公共土地只作测试预设。
- unit/roster：
  - 每个有地农村家户在所在 hex 有稳定 `grain_farm` unit（operator=家户 actor）；
  - 无地家户也保留一个零土地 `grain_farm` unit（下一轮买地后可组织），**不伪造**其土地/劳动；
  - 单元 roster 至少 organizer/operator；有真实雇工关系时才写 hired_laborer participant，不得塞假 worker；
  - 多角色 unit 必须显式 `productionRosters`，不能依赖单角色自动补。
- 初始工资锚：
  - 对每个实际 `grain_farm` 产业 id + `hired_laborer` role，按市场区写 `wageAnchors[]`；
  - currency = 市场区 legalTender；wage 由工程标定；缺锚不得用 0 冒充；
- 初始地价：
  - 每个市场区 `MarketTable.landPrice` 给工程默认（可为 0；0 与 empty 分开）；没有地价则不生成土地订单；
- 初始 `demands` 可以为空（本 Seeder 直接建 unit/roster，不靠 entry）；若后续要 entry，再单独立项。
- 不改 P2/P3d/P4 语义；不改测试/POM；economy/app 编译绿；所有 economy main Java ≤1000。

## §13 P6 — 独立测试、变异自证、全量门禁

### 13.1 测试 owner 任务

- 按本文件/tick contract/农业模板的判据写测试，不按代码反推。
- 不改生产代码；不改用户口径；发现设计/实现/夹具冲突先报告。
- 测试至少覆盖每阶段验收判据的正向+负向；多 tick 长跑按 AGENTS 用 `tools/multitick-sim.sh`，不进程序化测试。

### 13.2 必测清单

**P1**
- `advance` 分段等价；单槽事实；失败不覆盖；首 tick 空事实；Social 先。

**P2**
- 只 `day%5==0` 开市；商品+土地同轮；土地不进普通库存；0/未定价；债务最先；债权人财富序；货币→库存→土地；整块不找零；债权人全灭；本轮土地不可用；卖地中断；无 Pledge；无 35 天储备。

**P3**
- 单家户两轮最大化；第一轮订单/第二轮不下单；雇佣锁；有雇佣+小生产；实际库存不足；新土地不用；跨 hex 自耕拒绝；纯库存财富不扣债/不含工具；审计 A2/A3/A4。

**P4**
- 只付货币；部分/完全无现金；不抵物；普通工资债；下一轮 M2 同池；U20 多 tick 加价/降价；锚键共享；0 工资不特判。

**P5**
- 单一 `grain_farm`；阶级投影不改选择；无地户受雇/买地；无 rent/tenancy；跨 hex 本地雇农；初始土地守恒。

**P0.5**
- 文件行数守卫 ≤1000；拆分前后行为不变；公开 API 不扩大。

### 13.3 变异自证（关键项）

- 守恒式：商品/货币/土地/债务守恒。
- 不丢失：`OwnershipStake` 转移不丢 id/owner/operator；土地整块转移。
- 静默付 0：工资未付必须成债，不得静默 0。
- 断粮/缺口：Social 先与 PendingFamineFacts 分段等价。
- 两轮决策：把第二轮改成“执行第一轮计划”⇒ 应有判别用例红；把雇佣锁放开 ⇒ 红。
- 统一市场：把土地拆回独立阶段 ⇒ 订单/轮次测试红。

### 13.4 门禁

```bash
cd /home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module
tools/mvn-lock.sh -pl simos-app -am test
tools/mvn-lock.sh -pl simos-app -am clean verify
tools/gate.sh
```

- 写者全停手后才跑全量门禁；服务在跑时用 `./mvnw test` 不动 jar。
- 证据落 `.superpowers/sdd/<topic>/evidence/`；报告写“未做/未验证”清单。

## §14 文件所有权与交接表

| 文件/目录 | P0.5 | P1 | P2 | P3 | P4 | P5 | P6 |
|---|---|---|---|---|---|---|---|
| `simos-app/.../PopulationEconomyTimeParticipant.java` | — | W1 | — | — | — | — | T1 只读 |
| `PendingFamineFacts.java`（app 新） | — | W1 | — | — | — | — | T1 只读 |
| `simos-economy/time/MarketSettlement.java` | W6 拆 | — | W2 | — | — | — | T1 只读 |
| `simos-economy/time/EconomySettlement.java` | W6 拆 | — | W2（债务段） | W3（编排） | W4 仅调用点 | — | T1 只读 |
| `DebtSettlement.java`（新） | — | — | W2 | — | — | — | T1 只读 |
| `Pledge.java`/`PledgeId`/清算 | — | — | W2 删 | — | — | — | T1 只读 |
| `ExpectedPlanSettlement`/`ActualPlanSettlement`（新） | — | — | — | W3 | — | — | T1 只读 |
| `ExpectedProfitBook.java` | W6 拆 | — | — | W3 | — | — | T1 只读 |
| `LaborQueueSettlement.java` | W6 拆 | — | — | W3 | — | — | T1 只读 |
| `WageSettlement.java`/`ProductionWageBook` | W6 拆 | — | — | — | W4 | — | T1 只读 |
| `DefaultProductionModes.java` | — | — | — | — | — | W5 | T1 只读 |
| `EconomySeeder.java`（app） | 优先拆 | — | — | — | — | W5 | T1 只读 |
| `simos-production/contract/**` | — | — | 只读 | W3（probe/execute 契约） | — | — | T1 只读 |
| `simos-*/src/test/**` | — | — | — | — | — | — | T1 独占 |
| `pom.xml`/`tools/` | — | — | — | — | — | — | 控制方显式授权才动 |

规则：
- 同一行同一阶段只能有一个写者；共享文件按 P2→P3→P4 顺序交接。
- 不允许 P2/P3/P4 并行改 `EconomySettlement`/`MarketSettlement`。
- 每个写者在 `.superpowers/sdd/<topic>/impl-ledger.md` 写：调查结论、实现架构、关键判断、偏离记录。
- 控制方每阶段验收后才把状态从“未开始”改为“编译过/待测试”。

## §15 风险与停手条件

| 风险 | 处置 |
|---|---|
| `OwnershipStake` 为所有资产共用，改 id 派生会影响非土地资产 | P2 约束设计书先列全调用点；Alpha 不兼容旧档；不做旧 id 兼容；找不到自洽形状 ⇒ BLOCKED，不是 DONE |
| 市场区一个土地价掩盖 hex 质量差异 | 用 hex 面积/质量系数调整可经营规模/估值量，不另造 hex 价表；P2 设计书冻结 `MarketTable.landPrice`、codec、roundtrip |
| 商品+土地共享预算/冻结与既有市场信用冲突 | 复用现有 deferred payment/冻结路径；不另造第二套信用；P2 先做最小同轮变体，再做优化 |
| 两轮计划引入持久状态 | 除雇佣锁/最终 roster 外，预期/实际计划保持同 tick 瞬态；不得落 `EconomyData`/Codec |
| 工资锚重构影响旧 `WagePosting` 测试/读口 | P4 删除旧主链；P6 迁移测试；旧 A5 不得保留“仍支持抵物” |
| 单一 `grain_farm` 与旧 Seeder/迁移/阶级投影冲突 | P5 只改 Seeder/定义/默认；不碰 P2–P4 实现；阶级标签只做投影 |
| 大文件拆分破坏行为 | P0.5 只做机械抽取；每组编译+现有测试回归；P6 加文件大小守卫；不在拆分时改公式 |
| 多 tick 长跑误当门禁 | 按 AGENTS 用 `tools/multitick-sim.sh` 独立世界跑，不进 `clean verify` |

**停手条件**：
- 与用户原话/五条铁律/模块边界冲突；
- 需要新增/修改跨模块公开 API 但 P0 文档没有冻结；
- 需要动 POM/enforcer/权限/账户落账路径而任务书未授权；
- 发现实际代码与本文 `file:line` 事实冲突，且影响设计；
- 找不到无重复占用的共享资产方案。
停手报告必须给：为什么做不到、需要的最小额外范围、是否有 fail-closed 安全降级方案。

## §16 当前状态与下一步

- **P0 已完成**：总派单、tick contract、农业模板、差距表、文件拆分方案、阅读覆盖清单。
- **未做**：没有改 Java/XML/POM/测试；没有跑 Maven；没有提交/推送；没有动主树。
- **P0.5 已完成**：economy 无 >1000 行 main Java；`EconomySeeder` 941 行；已有 economy 452/0/0/0、app 1017/0/0/7 绿；Spotless economy/app 0 needs changes。
- **P1 完成**：Social 先用上一 tick `PendingFamineFacts`；app 1017/0/0/7、reactor 3572/0/0/7 绿。
- **P2a 完成**：市场只 `day%5==0` 开市；35 天/30 天安全库存删除；5 tick 保留；economy 452/0/0/0 绿。
- **P2b1 完成**：`MarketTable.landPrice`（OptionalLong：empty=未定价、0=合法）；economy 452/0/0/0 绿。
- **P2b2 完成**：`landBlockIdOf` + `transferLandBlock`；LAND id 不含 owner/operator；economy 452/0/0/0、app 1017/0/0/7 绿。
- **P2c 完成**：`DebtUnit` Money-only；商品欠款按活动市场区价折货币，无价 unresolved；economy 452/0/0/0、app 1017/0/0/7 绿。
- **P2d 完成**：Pledge/抵押配置/自动质押清算全删；组件 37→35；app 3572/0/0/7 绿。
- **P2e 完成**：市场日 M2 债务最先；纯库存财富序；货币→库存→土地整块抵债；`landAcquiredThisRound` 瞬态标记；app 1017/0/0/7 绿（reactor 3572/0/0/7）。
- **P2 完成**：统一市场执行层、债务最先、Pledge 删除、Money-only 债、土地抵债与地价；app 1017/0/0/7、reactor 3572/0/0/7 绿。P3 的生产侧土地需求与“新土地本轮不用”仍待 P3。
- **P3 约束设计书已落盘**：`docs/superpowers/specs/2026-10-18-d032-p3-two-pass-production-constraint.md`；**P3a 完成**：production roster probe 增量；production/app compile 绿。**P3.0 完成**：`prepareMarketRound`/`executeMarketRound` + MarketIntent；旧 `settleMarket` 行为不变；app 1017/0/0/7 绿。**P3b 完成**：商品+土地买方共用纯库存财富快照；生产资产预算排除新土地；app 1017/0/0/7、reactor 3572/0/0/7 绿。**P3c1 完成**：EmploymentLock/PositionEconomics/Expected/Actual plan 类型；economy/app compile 绿。**P3c2 完成**：`prepareProductionBase`/`drawInputsAndPlanLabor`/`settleConsumption` 拆分；旧 `prepareConsumption` 同序；app 1017/0/0/7、reactor 3572/0/0/7 绿。当前执行 **P3c3 两遍日序 + EmploymentLock + roster probe/A2–A4 + 土地 intent**。
- **P4c2 完成**：市场日 M8 后按 M3 target/M8 matched 聚合观测并调 U20 锚；逐义务 readout 带锚键；app 增锚级只读列表；app 1017/0/0/7、reactor 3574/0/0/7 绿（2026-10-19）。
- **P3e 完成**：5 tick 基础消费、零劳动 organizer 入选、WageDemand target 改用 roster 实际雇工、legacy fallback 只按 roster 存在性、M6 不可行锁过滤、`D032_TICK_CHAIN`；economy/app 编译绿（2026-10-19）。
- **P5a 完成**：农村目录只余 grain_farm/handicraft/merchant/displaced；grain_farm=operator+hired_laborer；农村四槽→operator，landless→displaced；farm LAND 不再 300‰ rent；economy/app 编译绿（2026-10-19）。
- **P5b2 完成**：`hired_laborer.standardLabor=120000/周期（1000/日）`；SmallWorld 19 条 wageAnchors（single-region×farm@q_r×hired_laborer，wage=20）、19 条真实 hired_laborer roster participant（planned=5160/日）、landPrice=10；day1-9 M3→lock→M8→M8b 真链，day5 target=matched=98040；economy/app 编译绿（2026-10-19）。
- **P5b1 完成**：每农村 hex 一个 grain_farm plan，每农村家户一个 unit；LAND 权重 100/250/300/350，流民 0；Σ=本格容量；SmallWorld 19 plans/95 units/95 rosters/95 LAND stakes，总 land 54,758,400；economy/app 编译绿（2026-10-19）。
- **P4d 完成**：`MoneyWage` 无字段、Barter* 删除、旧 payload barterDiscount 按未知键 fail-closed；production/app 编译绿（2026-10-19）。
- **P3d 代码完成（测试待迁移）**：`PositionPlan.landQuantityMilliMu` 从 production probe 的 `assetRequired[LAND]` 写入；`MarketLandIntentBook` 生成 LAND BUY/SELL；`MarketRoundRunner.clearOncePerCycle` 只调 `MarketMatcher.matchAll` 一次；`MarketLandMatcher` 已并入 `MarketMatcher`，全仓 main 旧引用清零；商品/土地仍同一 `MarketRound`、同轮、同预算/冻结（2026-10-19）。
- **P4c3 代码完成（测试待迁移）**：删除旧 `productionWageDefaults`/`productionWagePostings`、旧两条工资命令与 seed/codec 表、报价 fallback 注入链；工资报价改为锚唯一权威，缺锚不得按 0 或旧表兜底；组件 36→34；economy/app `-DskipTests compile` 均 BUILD SUCCESS。P4d（production barter schema）待做。
- **2026-10-19 原始设计复核**：P3 仍缺 M3 LAND intent（正常 tick 无土地买卖订单），且统一市场是“同一 clearOncePerCycle + 独立商品/土地 matcher”而非字面同一订单簿/同一撮合器；新增 P3d 补齐。报告：`docs/superpowers/reports/2026-10-19-d032-original-design-reaudit.md`。
- **P2fix 完成**：M2 抵地后从债务人组织 assetSources 摘除 transferred share，删除对应 AssetOccupancyGrant，并防御性清理 roster rightsStakes；真 Shell 12 tick 全程提交（FINAL_REV=13，REF_CONSISTENT），economy/app 编译绿（2026-10-19）。
- **P2 前必读**：本文件 §8.1 列出的 production-runtime-migration / boundary / execution / R2 / asset-occupancy 文档对应章节；未读不得派单。
- **未来我查阅**：先看本文件 §6 状态表；若状态被改，必须有对应编译结果/报告；不接受口头“已完成”。


## §17 用户原话附录（逐字；派单门槛）
> 以下逐字抄录自 `docs/superpowers/specs/2026-10-18-d032-economy-tick-redesign.md` 的用户原话小节；不改写、不缩写、不意译。补充回复按同文档/`2026-10-18-d032-rural-tick-contract.md` 已转引的原话照录。

### §1 用户原话（逐字）
```text
首先，现在你的话我听的懂了，之后每次改这种政治经济学语义浓的相关模块，请使用和我的描述类似的通用政治经济学词汇描述项目中运作
其次我们来看vs表
经济先没有任何关系，决定做了，就干，是好事，反正市场轮是5tick一结；
但是衍生问题是，单次生产，必须检查家户对应的库存，如果库存不符合一个所选生产方式的生产要求，那么就直接停止这个生产方式的对应生产，设为无业，只保留无业状态下的家户基本消耗；
生产匹配为什么要按householdid来匹配？有钱人为什么没权利先选怎么组织生产？生产方式决定中，对于工资定义的部分是有主导权概念的
以及我就好奇了，你是怎么做到给定一张生产方式表直接把所有家户填进去的，其他同台竞技的生产方式怎么办？
我记得我做过裁定，工资是需要算的吧？
根据后文，应当永远不考虑额外的“生活储备”；demandparts貌似是生产所需，这个必须保留啊——不过我又好奇了，之前我做的裁定不是，家户的需求统一归生产方式管，无业纯消耗也是一种生产方式，不额外计口粮吗？因此生活储备问题也出在这，生活储备已经作为基础口粮需求放在生产消耗计算里了；
按市场区平均成交价挂牌可以去掉，目前设计很合理
家户按富→穷在市场区购买这一项，把债务计算去掉
市场购买不足部分直接记货币债吗？也可以，等下一tick统一还债的时候，允许用货物抵扣就行，没必要多一步计算——前提是这个计算真的至少一处存在
那么，债务不允许用货物计算，只允许用不同市场区的货币计算，货币汇率见很早之前的裁定
理论上来说生产和进库存必须是同一个环节的，不过这个得看具体的生产方式的结算周期，你目前的计算其实问题不大，可以达到设计目的

——很显然，你需要重新设计许多东西，先把我的几段原话记录下来，然后好好思考一下应该怎么改，然后再用我的表述方法说一下你的修改设计，找我确认
```

### §7 2026-10-18 第二轮用户原话（逐字）
```text
1、Social先，饿死了还做买卖？
2、对，按照当前市场区汇率标准计算
3、这里每个家户可以选择的，也从单纯的生产方式，扩展为了，生产方式允许的多种生产位置；能选多少个具体的生产位置，取决于理论上干完这个主业，还有多少空下来的劳动力，然后把空下来的劳动力继续去计算，算到没法继续算，也就是贪心
4、接3，在代码层面应该是，首先判断一下如果担任生产主导者位置并且这个生产方式允许主导者雇佣其他家户，那么让家户给出对于雇佣的劳动力对应工资，允许从0开始报——报0/低价的必然结果是没人来，那么下一市场tick招工，让这个家户逐步提升工资，直到有人来；如果有人来了，就开始慢慢降工资，降到人开始走，人走了，规模不满，继续加，处于一个动态平衡；
5、是这样没错，在Simos，单个家户的生产和生活不分开计算
6、我很好奇价格是根据什么算的，这里我认为可以让整个市场算动态贪心，就是如果市场上这类产品有求无供，那么价格不断上涨，对应生产方式带来的收益也大量增加，直到有供无求，那么对应再下降，家户挂单走这个市场区贪心计算出来的价格；
4/6合起来，我原本想让生产主导者家户的劳动力估算也走市场平均，想了一下发现确实可以这么做，不过这个挂单是要根据生产方式来的——这个生产方式的哪些位置需要雇人？对应位置的工资在单个市场区被锚定，劳动力多需求少劳动力价格下降，反之上升，这样对于单个生产主导者家户也就不用自己算工资了——代价是可能会亏钱，那就走标准的生产方式收益排序来选择了；
7 是的
8 是的，其中债务可以分多种币种，fx计算有点绕，如果是不同市场区的货币，得先走fx算市场汇率，然后有的话就产生兑换需求来还债，没有的话就按照先fx、后按对面市场区的货币-实物价格还债，还不起就还不起了，累加着
9 把那部分无法进行的生产方式整个替换为无业档位然后正常走计算就行
10 对
11 对
来看一下我的分析和决定吧
```

### §9 2026-10-18 第三轮用户原话（逐字）
```text
1 为啥不保存，额不对，这里是存在内存里，还是不管是推进30tick还是60tick，推进多少tick记录多少tick的那种？我能接受前一种
2 外部注入就外部注入，命名给我命名好了啊？你自己都看不懂，很显然是要保留给其他模块的，生产投入需求表依旧需要
3 不不不，我说的0工资只是数值上的极限，不是让你真的判定=0不接，而是单个家户核算，目前市场区从事对应生产获得的报酬（可以是受雇，也可以是小农生产等），是否赚钱，如果赚钱就从事，不赚钱就不从事
4 不是，市场上没供给怎么做到买到的？我说赊购是，市场上有东西，然后家户自己买不起，才记录为债务，特么买都没得买还赊购啥？至于你说的方案A/B，应当是跨市场 tick 逐步调整
5 部分失败，相当于对应的生产劳动力投入被替换到投入进无业
6 接受
7.1 肯定是啊 7.2 对
继续思考和裁定
```

### §12 2026-10-18 第四轮用户原话（逐字）
```text
1、是的，按照市场间隔
2、生产位置重选择会导致债务重计算，因此跟着市场轮
3、肯定是单位劳动的价格啊
4、那是因为生产端错配了，给了需求没有给对应的生产方式，只要有生产方式，价格不可能趋于无限高
```

### §14 2026-10-18 第五轮用户原话（逐字）
```text
1 允许取消，老农民发现种田不挣钱跟风给田荒掉的案例也不少
2 受着
3 对，测试出现类似数值现象都很正常
以及我想补充一点，能不能加一个作为商品的土地，初始给作为生产主导者的地主位置同等劳动力下的大量土地需求，然后到中农、雇农没有土地需求只能付出劳动，目前这个套模型我认为不加入土地产权不太可能自洽了
```

### §17 2026-10-18 第六轮用户原话（逐字）
```text
不行，目前这套体系，应该有办法让土地体现出其极高的商业价值，导致就算想买也必定在市场上趋于高价吧？土地产权就是一般商品，走正常市场流程，允许在还债的时候抵押
```

### §19 2026-10-18 第七轮用户原话（逐字）
```text
我想到了一个灾难性的想法，如果单个家户根据利润模型，一股脑全选高土地需求的地主位置怎么办？市场上必定没有这么多土地，我想到的解决方案是调整市场计算顺序，单个家户按库存资产排队，先排到的家户算出预计生产方式、对应土地需求，那么单个hex的总土地预占用就被挖掉一部分，后算生产方式的家户……
也许我们压根不该把雇农和地主绑定在一起，只要一个家户手上可以被分到土地，那么这个家户就允许根据利润选择把活雇佣出去，【除非市场上劳动力耗尽】；同样，理论上所有人都可以当地主，【除非本地没有其他能被分的地了】，这样一来，就不应该把各级小农给区分开，地主这个定义也该取消掉：只有富农~有地贫农的无级滑档，和雇农；这样一来遇到了一个问题，在单个家户计算出本轮农业生产要投入多少土地之后，应当没有市场环节能变它——我本来寻思可能有什么问题，但是我转念一想，如果单个家户真的在市场tick里买到土地，那直接判之后的生产不直接用就行了，然后下一轮市场轮开始选生产方式的时候，，再把对应的库存土地产权介入计算，这样就可以了；平衡从中产生，土地规模大的人，自己投入劳动力耕耘的收益会不断小于把更多活包给雇农的成本（这其中甚至可以有经验作用，虽然它是通过经验-》产出-》市场价格这样的链路传导的），而土地规模小的家户则会优先自己劳动，因为不用付出额外的雇佣费用；
这样的设计可行吗？
```

### §21 2026-10-18 第八轮用户原话（逐字）
```text
1 对，本轮只算上一轮入库的实际库存，下一轮单个家户拿到多少土地下一轮算
2 你大可以只设置一个初始家户资产差异，然后搞一堆0元的公共土地，就看你怎么预设了，这很有意思，不过我建议你还是走标准预设——让每个家户拥有差异的财产和对应规模的土地
3 对，肯定要这么算
4 肯定要允许，要是没能力还的话，下一轮就不得不卖掉了
5 对，计算要分段
```

### §23 2026-10-18 第九轮用户原话（逐字）
```text
1 对
2 跟着测试世界的数据，这些本来就是具体的世界数据
3 必须允许，不设融资上限，就看债务起来是家户先赚到钱还是家户本身先直接消失了
4 卖家卖土地的时候不扣除自己下一轮所需要的吗？如果生产层面上的过程被中断了就中断了，地荒了很奇怪吗？
另外，单个hex的土地虽然允许被市场区内不同hex的家户购买，并且土地本身没有运费，但是如果想要耕种其他hex的土地，就必须雇佣雇农，目前暂时这么定，可以把如此代码做出实现后看看效果怎么样
目前在生产资料层面，土地本身只是一种跟着生产方式模块记的限量特殊商品，不考虑后面怎么做接口，目前只需要这么实现就行
还有决策需要做吗？设计自洽了吗？
```

### §26 2026-10-18 第十轮用户原话（逐字）
```text
1 为什么土地被卖掉的时候会有租约/占用啊？市场tick最开始做的不就包括根据预期生产需求重排库存吗？哦对，可以把清算负债放到市场tick的最前面，方便收回租约，综合下来似乎是B，但是这是一个次序问题
2、为什么会有强制出售？是家户想要买但是没有卖家吗？那就不成立
这时候我又想到了一个问题，照这么说，实际上市场tick走完后，家户实际拿到的库存可能和预期不一样，因此需要进行一轮简单的重编排，也就是根据家户目前实际能用于生产的库存，再排一次生产参与，只不过这次所有限制都要到位；
3、你设0都没问题，Simos经济模块必须应该有能力处理这种初始价格
4、先把农村做好了再重新加入工坊——说重新是因为之前也设计了好几轮了
```

### §28 2026-10-18 第十一轮用户原话（逐字）
```text
1、我的意思是整个债务清算在市场轮最开始做就行，这样土地租约虽然没有清算（其实是家户买土地，然后负债），但是效果也达成了
2、雇佣关系不允许，选择了就必须按指定份额参与雇佣关系的那一部分，包括雇佣方和受雇方；其他小生产部分允许，可以根据当前实际库存（土地、劳动力等），选择利润率最高的生产方式参加、算生产产出
```

### §30 2026-10-18 第十二轮用户原话（逐字）
```text
1、对，不保留什么租赁关系，事实上的租约已经被拆成好几个具体市场算法环节了，不需要额外设计，也不能搞额外环节
2、 A，欠工资那受雇方只能受着了，雇佣方由于不在市场轮，所以不给也不记债，但是受雇方还是得投入生产
3、土地也用，除去雇佣花费后，剩余的土地也可以用来进行小生产啊
4、什么叫没有买家，每一笔债可能没有还款的人，但是肯定有收款的对象啊，除非收款的对象全死光一遍了
```

### §31 2026-10-18 第十三轮用户原话（逐字）
```text
A2，受雇方白干
那行，新购买的土地不算做能使用的土地，这里指的是已经算为投入生产的土地，在扣掉受雇方的那一部分之后，剩下的部分可用于覆盖各种小生产；
3 几个边界，第一个是只拿对应份额，第二个如我所说，第三个也是，第四个直接消去了，都没人记账了自然不用还了
```

### §33 2026-10-18 第十四轮用户原话（逐字）
```text
1 全局取代，如果雇佣方账上没钱支付给受雇方，那就是不支付，不算欠薪债
2 直接划走，都到这个份上了，有债的出多了也只能受着了
```

### §35 2026-10-18 第十五轮用户原话（逐字）
```text
1 不行，不抵押库存
2 用完为止
3 因此也没有抵押问题
我想了一下，要不还是把账面欠薪债加回来吧
```

### §37 2026-10-18 第十六轮用户原话（逐字）
```text
2 抵押是什么？如果你说的是还债的话，定一个还债顺序就可以解决了，那就是先还给有钱的——没钱的没能力组织催收
工资债就是一般债务，家户到家户的，和其他债务一同计算，允许使用库存，我说不允许是不让你在第一次付工资的环节直接上库存
```

### §39 2026-10-18 用户纠正（逐字）
```text
？？？？谁让你加的担保机制？借债就是借债，还债就是还债，我没说的机制不要加
```

### §42 2026-10-18 用户纠正（逐字）
```text
首先，为什么这块地会被挂到市场上，它应该在最开始的还债环节就被抵走了
```

### §44 2026-10-18 土地估值口径（逐字）
```text
当前市场区的土地价格啊
```

### 2026-10-18 用户补充回复（同文档已转引）
```text
为什么土地市场和商品市场是分开的？我的原话多次提到两者是统一的……
根据hex来，数值你来定，因为这玩意能直接决定测试世界的最大农业生产规模……
粮食库存过低为什么要额外开一轮？去掉！
我只要求生产方式归生产方式模块维护、经济运转归经济总模块维护
经济模块里有很多千行大java文件，必须拆分，具体怎么拆你来定
```

### 2026-10-18 用户补充裁决（本轮恢复时原文照录）
```text
大文件拆分为什么要放在最后面，应当放在最前面，提升后续修改的整体可靠性
```

### 2026-10-18 架构责任要求（本轮恢复时原文照录）
```text
我希望你根据我的口径，总结一下这些设计所需要担负起的架构责任，而不是死板的一个一个记裁决，如果有设计责任不清楚，同样作为裁决问我
```
