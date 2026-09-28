# 经济系统演化四目标：分阶段开发计划（2026-09-29）

> **输入**：`docs/superpowers/reports/2026-09-29-economy-evolution-r4-code-facts.md`（当前 HEAD 代码事实与缺口表）、
> `docs/superpowers/reports/2026-09-28-economy-evolution-boundary.md`（只读边界调查）、
> `docs/superpowers/plans/2026-09-28-r3-semantic-closure-decisions.md`（R3 有效决策单）、
> `docs/superpowers/plans/2026-09-28-pre-modern-economy-development-plan.md`（S2/S3 设计意图，**其中假设已过期处以本计划为准**）。
> **HEAD**：`6170760f`（R3B.1 仅编译、未接线生产）；工作树干净。
> **总约束**：不实现资本体、国际经贸、完整 GOV、自动发明技术或复杂个人决策 Agent；
> 跨地区人口迁移留到四目标之后的独立阶段，但稳定身份/债务/资产处理不得堵死它；
> **不重做**已存在的 `ProducerCostBook` / `OperatorCondition` / 稳定 `HouseholdId` / `ClassRow.view` 写回。
> **裁定原则（用户已授权）**：一般实现选择由控制方按本计划裁定；只有实质改变模拟目标才反馈；每完成一片必须报告
> **实际观察到的现象、证据、与计划的差异、剩余阻断点**。

---

## 0. 执行纪律与证据协议

1. **一个切片 = 一个写代码代理**（AGENTS.md §一.5）：只写生产代码、写到 `-DskipTests … compile` 过；不写/改测试；
   不跑 `test/verify`；不 `git commit`。控制方审 diff、跑冒烟、提交。
2. **测试与变异自证留 V**（AGENTS.md §三.0）：开发期每片只做“编译 + 定初态冒烟 + 守恒读数”；JUnit/变异在 V 统一补。
3. **每片冒烟**：从**固定 tick0（或最近可比的固定 tick）store 复制**，用 MCP 客户端
   `.superpowers/sdd/2026-09-26-year-one-simulation/simos_mcp.py` 走 `simos.worldgen.initialize` / `simos.command.submit` /
   `simos.advance`；每片 ≤3 分钟；记录《片报告》到 `.superpowers/sdd/2026-09-29-economy-evolution-r4/<slice>.md`。
   旧世界 90 tick 基准可从 tick30 store 复制（与既有 R3 口径一致），新能力另用 `dryRun` 小夹具。
4. **每片必须核对的守恒式**（详见 §4）：人口（Membership×social）、商品、货币、土地/资产份额、债权债务；
   多线程：至少 1 vs 8 线程跑同一初态，领域读数逐值比；里程碑片加 1/4/8。
5. **命令面同步**：新增命令必须同时注册 `Shell` handler、`CatalogTool.PAYLOAD_HINTS`（构造期无缺项）与权限目标表；
   新增状态组件必须同步 `EconomyData` / `EconomyChangeSet` / `EconomyCodec` / 往返反射守卫。
6. **旧档迁移与新增状态分离**：旧档逐条搬运事实；新世界播种规则另行配置；两者不得混写（R3 决策单 §0.2）。
7. **命名**：本计划的切片 = `B.2/B.3/B.4`（收口 R3 语义底座）+ `E1..E4`（四项演化目标）+ `V`（最终验收）。

---

## 1. 切片依赖与总览

```
B.2 ProductionUnit/AssetShare 接线
  └─→ B.3 多 unit 新世界播种 + 资产转移/收束命令 + c1 孤儿债对账
        └─→ B.4 阶层纯派生收口
              ├─→ E1 衰退/退出/家户后果
              └─→ E2 GM 需求 → 预设采用 ──→ E3 经验积累
                                      └─→ E4 梯度消费 ──→ 反馈链复验（接回 E1/E2）
V 最终验收（所有切片之后一次性：JUnit/变异/1-4-8/全年/守恒/迁移）
```

| 切片 | 目标 | 依赖 | 完成后的可观察现象 |
|---|---|---|---|
| **B.2** | `ProductionUnit` 成为生产结算主体；规模读 `AssetShare`；关系键改 unit | R3B.1 | 同格/同产业两个 unit 各自投入、进度、产出、账户；旧档单 unit 行为逐值等价 |
| **B.3** | 新世界播种庄园自营+佃耕+家户自用等多 unit；资产/劳动可合法转移；孤儿债清零 | B.2 | 同一 hex 至少两个 unit 各有 progress/产出账；资产份额变化先于产出归属变化；`Σ行引用==债务表` |
| **B.4** | 分类只读事实、不再迁就槽位 | B.3 | 同一状态重放分类一致；无“为标签改 participationPerMille/劳动”路径；无富农就输出 0 |
| **E1** | 旧生产方式衰退 → 经营者/家户不同后果；退出时资产/劳动/债务/库存有去向 | B.3 | 持续滞销的 unit 缩产/停业/退出；佃耕地回到 owner、劳动配额释放；自用维生户不被判破产 |
| **E2** | GM 注入需求 → 合条件主体采用预设；无可行预设则需求未满足 | B.3 | `AddDemand` D+1 见订单；合条件家户形成 TRIALING unit；buildDays 前无产出；新旧并存 |
| **E3** | 实际生产实践积累经验；阶层变化不抹经验 | B.2/E2 | 实际劳动者得经验，纯收租者不得；转业/阶层变化后经验仍在；地区规模有界渐近 |
| **E4** | 收入+需求层次 → 人均需求 → 家户聚合 → 真实订单/消费 | E2 | 同人口不同所得形成不同消费组合；基本缺口与改善缺口分开；消费变化反馈到进入/竞争/退出 |
| **V** | 最终验收 | 全部 | JUnit/变异、1/4/8、全年耗时+峰值内存、守恒、旧档迁移、场景清单 |

---

## 2. 切片详细计划

### B.2 ProductionUnit 与 AssetShare 生产接线（R3B.2）

**修改模块/文件**
- `simos-economy-api`：`id/ProductionUnitId` 已有；新增 `ProductionUnit` 只在 economy（不新增模块）。
- `simos-economy`：新 `model/ProductionUnit.java`；`EconomyData` 增第 14 组件 `units: Map<ProductionUnitId, ProductionUnit>`；
  `change/EconomyChangeSet`、`codec/EconomyCodec`（新键反序列化器 + 旧档迁移）、`EconomyData` 守卫；
  `api/relation/ProductionRelation` 的 `activity` 由 `IndustryId` 改 `ProductionUnitId`（含 `RegimeRelations`、所有构造点）；
  `time/EconomySettlement`（规模、投入、劳动、收获、关系分配、operator conditions 键）、`time/MarketSettlement`
  （参与者、必要投入、自留、卖单归属）、`time/EconomyStateBuilder`、`time/MarketReadout`、`time/HouseholdClassRule`、
  `time/OperatorSettlement`、`time/StressPolicy`、`migrate/LegacyHouseholdMigration`、`spi/EconomyPayloads`、
  `spi/EconomySeedHandler`；`simos-app`：`world/EconomySeeder`、`gui/ApiViews`、`tools/read/CatalogTool` 文案、
  app 侧经济装配（`OwnershipBooks` / `PopulationEconomyTimeParticipant` 如涉及 unit）。

**状态与契约**
```text
ProductionUnit(
  ProductionUnitId id,              // idOf(industry, operator)：unit-<industry>-<operator.kind>-<operator.id>，不含 '.'
  IndustryId industry,              // 引用技术模板
  ActorRef operator,                // 实际经营者；投入/产出/关系都归它
  String modeKey,                   // E3 用；旧档 = industry.id().value()，候选档 = candidateId+"@"+version
  long progressDays,
  long cycleLaborMilli,
  Map<CommodityId, Long> cycleInputUsedMilli)
```
- `usableAssets` **纯派生**（`ProductionUnitBook.usableAssets(unit, assetShares)`）：
  `Σ AssetShare{industry==unit.industry && operator==unit.operator}.quantity` 按 `AssetKind` 汇总；
  **不存第二份**。
- 规模：`unitCapacityScale = min over capacityPerUnit[k] of usableAssets[k]/capacityPerUnit[k]`（缺资产 = 0）；
  `plannedScale = unitCapacityScale × StressPolicy.plannedScalePerMille(unitCondition)`。
- `Industry` 只留**模板**：`id/name/regime/cycleDays/capacityPerUnit/dailyInputPerUnit/laborPerUnit/outputPerUnit/
  cycleInputPerUnit/slots/allocation`。移除 `operator/progressDays/capacity/cycleLaborMilli/cycleInputUsedMilli`
  （旧档与新载荷的旧键由迁移器/载荷解析器转成 unit + AssetShare，不是“并存两份真相”）。
- `ProductionRelation.activity` 改 `ProductionUnitId`；守卫：relation.operator == unit.operator（跨表）。
- `operatorConditions` 键由 `IndustryId` 改 `ProductionUnitId`（形状不变；旧档从旧键一对一映射到默认 unit）。

**核心算法**
1. 结算按 `units` 遍历：逐 unit 现扣投入（relation.inputSupplier，缺省 unit.operator）、累计 `cycleInputUsedMilli`、
   推进 `progressDays`、收获时产出归 `unit.operator`、按 relation 规则分成/给养/地租（规则表逐 unit）。
2. 劳动：`LaborAllocation.activity` 对旧档仍可读旧 industry 串；迁移时改写成默认 unit id；新分配一律落 unit id；
   `Σ allocation(lot) ≤ laborSupply` 不变量不变。
3. 市场：`Participant` 的产业集合改 unit 集合；必要投入按 unit 的 plannedScale 汇总到 supplier；
   卖单归属 = unit.operator 的账户；同一 operator 多 unit 时按 unit 序确定性累加。
4. 读口：每个 unit 一行（operator/mode/assets/progress/condition），保留 industry 模板行；旧读口字段名兼容一版。

**旧档迁移**
- 每个旧 `Industry` → 一条默认 `ProductionUnit(id=idOf(industry, industry.operator), modeKey=industry.id().value(),
  progress/cycleLabor/inputUsed 原样)`；旧 `relations[industryId]` → `relations[unitId]`；
  旧 `operatorConditions[industryId]` → `[unitId]`；旧 `Industry.capacity` 已由 R3B.1 物化成 `AssetShare`
  （owner=operator），无份额时先补齐再删旧字段。
- `EconomyCodec`：旧形状（relation key = industry id、industry 带 operator/capacity/progress）读入后先重建 unit/份额，
  再构造新 `EconomyData`；迁移幂等（有 units 则不再迁）。

**验收（领域）**
- 旧档可加载；单 unit 世界在固定 90 tick 冒烟中，人口/商品/货币/债务/成交/产出与 HEAD 逐值等价（允许读口命名差异）。
- tick0 `Σ AssetShare(industry, asset) == 旧 capacity`；`unitCapacityScale == 旧 capacityScaleOf`。
- 手工构造“同 hex 同 industry、两个 unit（不同 operator）”的夹具：两 unit 各自 progress/产出账/关系分账；
  `Σ 产出 == 两 unit 之和`，账户守恒；一条 unit 退出不影响另一条。
- **明确不做**：不引入资产市场、不自动转移份额、不改撮合价格口径。

---

### B.3 多 unit 新世界播种、资产命令与 c1 孤儿债（R3B.3）

**新世界播种（`EconomySeeder`，确定性、显式常量）**
- 农业：旧 LAND capacity 拆成（最大余数法、按稳定 id 排序，逐值守恒）：
  1. **庄园自营 unit**：`ESTATE:farm@q_r` operator，`OWNED` 份额 `ESTATE_DEMESNE_PER_MILLE`（默认 300‰）；
  2. **佃耕 unit**：其余 LAND 分给本格农村家户（`owner=ESTATE actor`、`operator=家户 actor`、`kind=TENANCY`），
     每户一条 unit + 一条 `ProductionRelation(laborSource=TENANT，地租规则来自制度模板)`；
  3. **家户自用 unit**：从旧 capacity 中再留 `HOUSEHOLD_SELF_PLOT_PER_MILLE`（默认 100‰，含在佃耕份额内）作为
     家户 `OWNED` 小块自营地，用于“卖不掉也能自用维生”的验收，不额外造地。
- 手工业：WORKSHOP/TOOL 容量按同一规则拆成作坊自营 unit + 匠户 TENCY/OWNED unit；纺线/织布同理，
  `weave` 这类旧 operator 不再独吞整格产能。
- **守恒**：任一 `(industry, asset)` 的 `Σ AssetShare.quantity` 必须等于播种前该产业 capacity；拆不出整数时余数归 estate own 份额。
- 只为**已有人口行**创建 unit，不新建家户、不凭空造资产；没有可用劳动/人口的份额只登记为空闲份额。

**命令（GM/事件）**
| 命令 | 载荷 | 语义 |
|---|---|---|
| `economy.TransferAssetShare` | `shareId, toOwner?, toOperator?, quantity, kind?` | 拆分/转移实物份额；`Σ quantity` 逐值守恒；需给出剩余/目标主体；确定性新 id；不动商品/货币 |
| `economy.CloseProductionUnit` | `unitId, reason` | 显式停业入口（验收/测试用）：走 E1 的退出处置；幂等 |
| `economy.ReclassifyHousehold` | `householdId, newView, reason` | 只写 `ClassRow.view`；不改劳动/资产/账户（B.4 后只允许 GM 显式校验） |

**c1 孤儿债对账**
- 新 `migrate/DebtReferenceReconciler`：以**债务表为权威**重建每条 `ClassRow.debts` 引用（按 debtor 分组、canonical 排序）；
  creditor 行也必须存在；principal 总和逐值不变；无法归属（debtor/creditor 行缺失）⇒ **fail-closed 具名报错**，
  不静默核销、不转给第三人。旧档迁移路径末尾调用一次；正常状态每次 `EconomyData` 构造只做 O(1) 一致性抽查。

**验收（领域）**
- 新世界播种后同 hex ≥2 个 unit；每个 unit 有独立 progress/`cycleInputUsedMilli`/产出账；`Σ 份额 == 旧 capacity`。
- 资产变化先于产出归属变化（先改 operator/份额 → 下一周期才按新 unit 结算）。
- c1 孤儿债为 0；`Σ(ClassRow.debts 引用) == 债务表`；principal 守恒；迁移幂等。
- 90 tick 冒烟：无负余额、无守恒漂移、无异常；与 B.2 同初态在“单 unit 世界”行为不变。

---

### B.4 阶层纯派生收口（R3B.4）

- 删除 `HouseholdClassRule` 的 `feasibleStratum` / `slotCapFallback` 分支；`Industry.slots` 只作为**生产方式内部角色/劳动配置**，
  不再是 `ClassRow.view` 上限；派生结果只写 `ClassRow.view`（`withView` 已保证不动事实）。
- 审计：`participationPerMille`、`LaborAllocation`、`AssetShare`、账户、债务在分类路径只读；
  搜索并删除“为空缺阶层造行/改配额”的路径。
- 验收：同一资产/劳动/分成/债务状态重复分类逐值一致；无富农证据时输出 0 个富农；
  改关系/资产后先看到账户/份额/劳动变化，再看到标签变化；1/4/8 线程分类结果逐值一致。

---

### E1 旧生产方式衰退、经营者/家户后果与退出处置

**依赖**：B.2/B.3（unit、份额、多主体）。**不新增生产方式选择**（那是 E2）。

**状态与命令**
- 复用 `operatorConditions`（键 = unit）：保留既有 ACTIVE→OVERSUPPLIED→CONTRACTING→INDEBTED→SUSPENDED→EXITED 状态机，
  触发面全部改为 unit 口径；`StressPolicy` 阈值集中配置。
- 新增第 15 组件 `livelihoods: Map<HouseholdId, HouseholdLivelihood>`：
  ```text
  HouseholdLivelihood(HouseholdId id, LivelihoodStatus status, long stressCycles,
                      long selfProvisionCoverageMilli, String lastReason)
  enum LivelihoodStatus { SELF_PROVISION, TENANT, SERF, WAGE, MIXED, DESTITUTE, OPERATOR_EXITED }
  ```
  每关账周期由**事实**派生后持久化：自用产出+库存对基本口粮/下期投入的覆盖率、劳动配额、有效雇佣关系、债务压力。
  旧档缺键 ⇒ 首周期派生，不凭空写死。
- 新增只读 `ExitDisposition` 读数组件（不落盘）：unit、household、退回份额、释放劳动、偿债/违约、保留库存。

**operator → 经济家户解析（新增唯一拼写点 `EconomicHouseholdResolver`）**
```text
resolve(unit):
  ① operator 本身是家户 actor → 该 HouseholdId；
  ② relation.residualOwner / inputSupplier 是 ToHousehold → 该 HouseholdId；
  ③ unit 份额的 AssetShare.owner 是家户 actor → 该 HouseholdId；
  ④ 否则 Optional.empty()（ESTATE/WORKSHOP 聚合主体：不伪造家户、不强行借债）。
```
债务压力、家户后果按解析结果；解析不到的主体仍可缩产/停业/退出（走资产/劳动处置），但不产生虚假家户债务。

**退出处置（扩展 `settleOperatorExits`，顺序固定）**
1. **劳动释放**：删除 `activity == unit.id` 的 `LaborAllocation`（旧档按 actor+activity 兼容匹配）；
   `laborSupply` 不动，释放量计入 `ExitDisposition`；这直接消灭“退出后劳动永久留在原经营者名下”。
2. **资产退回/转移**：对该 unit 的每条 `AssetShare`：
   - `owner != operator`（TENANCY）：把 `operator` 改回 `owner`，`owner/quantity` 不变，份额变为空闲可再租；
   - `owner == operator`：份额按事实留在 owner 名下（它本来就是 owner 的），但不再有 active unit，读口显示空闲；
   - 不允许“operator 退出却仍是份额 operator”的任何残留。
3. **库存/货币**：先按既有规则偿还该经济家户的债务（不足才 `defaulted=true`）；剩余库存/货币留在原主体账，
   可被消费/再投资，不没收、不蒸发。
4. **状态**：`EXITED`；unit 的 `progressDays` 保留作审计，不参与结算；需要重开只能由 E2 新建 unit（重新 progress=0）。
5. **“自用维生 ≠ 破产”硬门**：`SUSPENDED→EXITED` 必须同时满足
   `selfProvisionCoverageMilli < CANNOT_REPRODUCE_THRESHOLD` **且** 债务/生存压力连续超阈值；
   只有滞销但库存/自用产出可覆盖基本口粮与下期投入的 unit，最高只能到 `OVERSUPPLIED/CONTRACTING`，不得退出。
6. **人口**：任何退出/破产/失业都不直接写死；死亡只经 `PopulationDynamics` 的生理压力路径（现有口径）。

**家户后果**
- 经营者退出后，关联家户按 `Membership`/劳动配额/自有小块地继续自用：覆盖率够 ⇒ `SELF_PROVISION`，
  `unmetNeed` 基本项为 0，不得判破产；没地、没雇主、没可迁移去处 ⇒ `DESTITUTE`，基本缺口进入既有饿死/借粮链，
  但不直接命令死亡。
- 读口按“unit 状态 × 关联家户 livelihood × 基本缺口”三个维度展示，便于观察“经营者与家户不同后果”。

**验收（领域，受控场景）**
- 场景 A：对一个佃耕 unit 制造持续需求塌陷（撤需求/高价竞争者），观察
  `ACTIVE→OVERSUPPLIED→CONTRACTING`；若仍可自用维生，**不退出**且家户 `SELF_PROVISION`。
- 场景 B：再叠加投入不足/债务压力到不可再生产，观察 `INDEBTED→SUSPENDED→EXITED`；退出后：
  土地 operator 回到 owner、劳动配额释放、库存去向有记录、债务按规则偿还/违约，人口不变。
- 场景 C：同一 owner 的两个 unit，一个退出一个存续：资产/劳动/产出互不串账。
- 守恒：人口、商品、货币、资产份额、债务本金在各步骤逐值守恒；`Σ allocation ≤ laborSupply` 保持。

---

### E2 GM 注入需求 → 预设实际采用

**依赖**：B.3（unit、份额、可转移使用权）；**第二项目标先只吃 GM 显式需求**；E4 接通后再接内生消费需求。

**状态与命令**
- 第 16 组件 `demands: Map<DemandId, DemandEntry>`：
  ```text
  DemandEntry(DemandId id, DemandScope scope, Optional<HouseholdId> household, Optional<HexCoord> hex,
              CommodityId commodity, DemandKind kind, DemandUnit unit, long quantityPerCycle,
              long createdDay, long expiresDay, int priority, String source)
  enum DemandScope { HOUSEHOLD, HEX }   // GM 注入常用 HEX；E4 可写 HOUSEHOLD
  enum DemandUnit { TOTAL, PER_CAPITA }
  ```
  `HEX` 范围的需求按本格家户人口最大余数法摊到户；订单生成前现算缺口，不落第二份。
- 第 17 组件 `candidates: Map<CandidateId, ProductionCandidate>`：
  ```text
  ProductionCandidate(CandidateId id, int version, CommodityId output,
                      Map<CommodityId,Long> outputPerUnit, Map<CommodityId,Long> inputPerUnit,
                      Map<AssetKind,Long> requiredAssets, long laborPerUnit, long buildDays,
                      RegimeId regime, LaborSource laborSource, Set<RightKind> acceptedRightKinds,
                      Optional<ActorRef> assetSource, int trialRiskPerMille, String modeKey)
  ```
  `modeKey = candidateId+"@"+version`；修订 = 新 version 新行，**旧 unit 保持旧 modeKey**，不悄悄改存档中运行的关系。
- 命令：`economy.SetMarketPrice`（hex+commodity+price；无市场行时可创建 Silver 计价空市场，不造商品/货币）、
  `economy.AddDemand`、`economy.CancelDemand`、`economy.RegisterCandidate`；全部同步 `Shell` + `PAYLOAD_HINTS`；
  GM 权限走既有命令目标/权限层（普通家户不得发明配方）。

**订单生成（改 `MarketSettlement.ordersFor`，保留旧口径）**
```text
desiredQty(household, commodity, day):
  baseline = 旧粮/布 35 天生活保留缺口（无 DemandBook/Profile 时逐值等价旧路径）
  demand   = Σ 有效 demands：HEX 按人口摊、HOUSEHOLD 直接；按 expires/priority 过滤
  gap      = max(0, baseline + demand − onHand − incoming − ownProductionKept)
affordable = min(spendableMoney 按 priority 切分后的预算, gap×price 上限) / landedPrice
if market 无价 → 不生成订单，读口记 PRICE_MISSING；if gap==0 → STOCK_SUFFICIENT；if affordable==0 → NO_BUDGET
```
- `naturalNeeds` 写“当日人均口径×人口+demand 当日份额”，`effectiveDemand` 写实际生成的买量（从本片起成为行为输入）；
  未满足的 demand 保留原条目并给具名原因。

**进入结算（新 `time/EconomyEntrySettlement`，纯函数意向 + 执行）**
```text
planEntries(economy, markets, day):        // 确定性顺序：householdId → candidateId
  signals = 有效 HEX/HOUSEHOLD 需求（剩余周期 ≥ buildDays + cycleDays）或持续短缺
  对每个有闲置劳动/失去生计/可调拨资产的家户：
    feasibility:
      ① 资产：家户自有 OWNED/TENANCY 份额 ≥ requiredAssets×trialScale；或 candidate.assetSource 的份额当前空闲（operator==owner）
         ——只改 operator/权利性质，不新建实物；无可用份额 ⇒ 不可行
      ② 劳动：availableLabor ≥ laborPerUnit×trialScale，且不超过家户可用劳动配置比例（默认 ≤1/3）
      ③ 投入：自有库存 + 市场可买（预算内）+ relation.inputSupplier 承诺 ≥ inputPerUnit×trialScale
      ④ 生计：自留 basicNeed×(buildDays+cycleDays+1) 后仍有 score ≥ 配置阈值
         score = 可观察价×预期产出 − 投入参考价成本 − 自留口粮（无价商品按 PRICE_MISSING 保守剔除）
      emit EntryIntent(household, candidate, trialScale, useRightPlan, inputSource, expectedDay)
执行:
  重新校验 → 建 ProductionUnit(status=TRIALING, progress=0) + relation(来自 candidate regime) +
  资产 operator 变更（空闲份额）→ 投入按现有现扣语义；不足则不半开工，未用投入退回，损耗具名记账，unit=ABANDONED、份额 operator 还原
  buildDays 前无产出；第一次实际收获时 TRIALING→ACTIVE
```
- **不允许**每期遍历全部预设自动切到预测收益最高者；原有能维生的经营者默认延续旧 unit；只有合条件的新主体/失业主体/
  闲置主体进入；新旧方式通过实际生产、成交、再生产条件逐步竞争（E1 状态机）。
- 无可行预设 ⇒ demand 保持未满足，读口给 `UNMET_NO_FEASIBLE_PRESET`（**不得**凭空发明技术或强行分配资产）。

**旧档迁移**：`demands`/`candidates` 缺键 ⇒ 空表；老粮/布行为走 baseline；旧档不因缺价失败；
`SetMarketPrice` 不改旧价格（除显式命令）。

**验收（领域）**
- GM 注入 `wool` 需求 + 价格 + 候选预设后：D+1 订单可见；合条件家户出现 TRIALING unit；`buildDays` 前零产出；
  到收获周期后产出/成交/账户变化可见；人口/商品/货币/份额守恒。
- 无可行预设（缺资产/劳动/投入/生计）⇒ 需求维持未满足，订单可见但无进入。
- 新旧并存：旧 unit 继续生产；新 unit 以试产规模出现；竞争通过市场成交与 E1 状态机发生，不通过全局 ROI 切换。

---

### E3 实际生产实践积累经验

**依赖**：B.2（unit 的 modeKey/实际劳动/实际产出）+ E2（新预设 modeKey）。可先在旧 mode 上验证“纯收租不得经验”。

**状态**
- 第 18 组件 `experiences: Map<ExperienceId, ProductionExperience>`：
  ```text
  ProductionExperience(ExperienceId id, HouseholdId household, String modeKey,
                       long points, long lastCycle)
  ExperienceId = "exp-<household>-<sanitizedModeKey>"（不含 '.'；稳定主体 + 稳定生产方式）
  ```
- 经验挂**稳定家户**，不挂 `ClassRow.view`/阶层槽位；新档空表 = 零经验（不伪造）。

**计提算法（只在收获日、只用实际发生量）**
```text
actualLabor   = 本周期该 unit 实际到位/使用的劳动（配额扣减后，同结算口径）
actualOutput  = 本周期实际完成并计提的净产出（按产出商品逐项，不跨商品相加）
if actualOutput == 0 或 actualLabor == 0: 不计提          // 挂单/计划产量/时间空转一律不得经验
raw = actualLabor / LABOR_PER_POINT + Σ actualOutput[c] / OUTPUT_PER_POINT[c]   // 逐商品分别折算
regionFactor = 1000 + min(REGION_BONUS_MAX_PER_MILLE,
                          actualRegionModeOutput / REGION_OUTPUT_REFERENCE × BONUS_RATE_PER_MILLE)  // 有界
gain = raw × regionFactor / 1000
gain = gain × max(0, CAP_POINTS − current) / CAP_POINTS        // 递减；到顶为 0
按“实际出劳动的家户”分摊（LaborAllocation.household + relation.laborSource；无配额的自营归 unit 经济家户）
owner/纯收租/只持资产不参与者 = 0
```
- 预设内效率增量：`bonusPerMille = min(MAX_BONUS_PER_MILLE, points / POINTS_PER_BONUS_PER_MILLE)`，
  只作用于同 mode 的 unit 产能/劳动效率，有上限、递减，不触发新工艺/新商品/关系质变。
- GM 读口：按**当前** `ClassRow.view.stratum × modeKey` 聚合展示（阶层是可变视图），同时给逐家户原值与 mode/version；
  同一家户换阶层/转业后 points 不变；新 mode 从 0 开始。
- 拆分/迁移：家户拆分时经验按 `Membership.count` 最大余数法拆分（总量守恒、不复制）；
  迁移/转业/unit 退出不改经验归属；GM 新预设登记不迁移旧经验。
- 禁止：按计划产量、挂单量、空闲时间、资产持有量计提；不同商品物理量直接相加。

**旧档迁移**：无 experiences ⇒ 空表；已有 unit 的 modeKey 在 B.2 迁移时写好；后续从 0 积累。

**验收（领域）**
- 佃农/自耕农实际劳动并完成收获 ⇒ 得经验；纯收租地主、只出资产者 ⇒ 0。
- 家户从 tenant 变 wage/landless 后 points 不丢；同状态重复结算不重复计提（每周期一次）。
- 拆分守恒；地区规模大 yield 增益大但封顶；同一 unit 连续生产增益递减；无产出/未卖出/空闲周期零增长。
- mode 修订后旧 unit 继续旧 mode，新进入者用新 mode，经验不串。

---

### E4 收入与需求层次的梯度消费

**依赖**：E2（demand/order 路径、mode）；与 E1 双向（消费变化→进入/竞争/退出）。

**状态与配置**
- 第 19 组件 `needProfiles: Map<ProfileId, NeedProfile>`（GM/播种显式配置；旧档缺省用一条由 `EconomyVocabulary`
  粮/布常量合成的默认 profile，保证旧行为等价）：
  ```text
  NeedProfile(ProfileId id, String name, List<NeedTier> tiers)
  NeedTier(CommodityId commodity, int rank, boolean essential,
           long basicPerCapitaPerCycle,
           List<ExpectedStep{ long resourceThresholdPerCapita, long quantityPerCapitaPerCycle }> expectedSteps,
           long capPerCapitaPerCycle)
  ```
  显式参数（不伪称旧代码已有公式）：基本口粮/布、资源阈值阶梯、每级增量、封顶；全部可配、整数、确定性。
- 家户默认 profile id 存 `EconomyMeta`（单值）；不按阶层名决定需求。

**四量算法（以“单个人口消费单位”为基准，聚合到家户）**
```text
每人每周期：
  1) basicNeed[c]      = tier.basicPerCapitaPerCycle                         // 没钱也存在
  2) resourcePerCapita = (货币收入 + 实物所得按可观察价折算 + 库存可动用值 − 租赋 − 偿债压力) / population
                         逐商品缺价 → 该商品不计值并记 PRICE_MISSING；禁止把不同商品物理量直接相加
  3) expected[c]       = basic[c] + Σ steps{threshold ≤ resourcePerCapita 的增量}   // 递增但有上限/递减
  4) effective[c]      = max(0, expected×population − 自有库存 − 已确认到货 − 自用产出留用 − 实物报酬)
                         ∩ 预算（按 priority 分配 spendable money/价格）
  5) actual[c]         = 基本优先消费，再按 rank 改善；分别记 satisfied/unmet
```
- 订单：`ordersFor` 改读 profile+effectiveDemand；`naturalNeeds` 写每日期望口径、`effectiveDemand` 写有效买量；
  缺价/缺钱/缺货分别落 `PRICE_MISSING / NO_BUDGET / NO_SUPPLY`。
- 消费步：基本缺口进既有饿死/生理压力链；改善性缺口只进“未满足改善”读数，**不得**与断粮混为一种未满足。
- `FlowRow` 增加 `unmetBasic` / `unmetImprovement`（旧 `unmetNeed` 语义保持 = 基本缺口，供旧读口）；
  codec 旧档缺键 ⇒ 由 `unmetNeed` 兜底。
- 反馈：未满足改善需求（有支付力但供给不足）作为 E2 的 demand 信号/订单可见；消费变化先改库存/货币，
  再改市场订单与价格/成交，再经 E1 影响旧 unit 的规模/状态。

**验收（领域）**
- 同人口、不同所得/库存/租赋/债务的两个家户产生不同消费组合；基本需求先于改善，改善有上限/递减。
- 断粮与“买不起改善品”分别读数、分别后果；自有库存/自用产出/实物报酬从有效需求中扣除，不双算。
- 旧粮/布 baseline 与 M2 代表关账日逐值等价（除显式新需求/新 profile 外）。
- 反馈链复验：改变消费/需求 → 订单变化 → E2 进入/不进入 → 新旧 unit 竞争 → E1 缩产/退出 → 家户处境变化，
  全程人口/商品/货币/资产/债务守恒。

---

### V 最终验收（所有切片后一次性）

- JUnit：按本计划各片验收清单逐条落实（不是按代码反推）；关键项变异自证（改坏→红→还原→比 md5）。
- 守恒：人口（social PopulationGroup × Membership 逐 lot）、商品、货币、土地/资产份额、债权债务；
  旧档迁移幂等与往返；c1 孤儿债为 0。
- 并发/确定性：固定初态 1/4/8 线程 90 tick 领域读数逐值相等；全年 360 tick 记录**墙钟与峰值内存**；
  与既有 4 min 目标对照（未达则如实记）。
- 场景：本计划 §1 里程碑 + 旧计划 §5.1–5.5；每个场景给玩家可观察现象与证据。
- 门禁：`clean verify`（Spotless/Checkstyle/SpotBugs/Surefire）+ `spotbugs:check`；无 `slotCapFallback`；
  无生产路径读取已移除的 `Industry.operator/capacity/progress/inputUsed`；所有新命令在 `PAYLOAD_HINTS` 中有条目。
- **不通过则不以“编译成功”或“四阶层齐全”代替领域验收**。

---

## 3. 贯穿所有切片的守恒与观测口径

| 域 | 初/末核对 | 证据 |
|---|---|---|
| 人口 | `Σ Membership.count(lot) == PopulationGroup.count(lot)`；出生/死亡只经 social 月度回写 | 逐 lot 表 + 差值 0 |
| 商品 | 账户余额 + 在途 + 在制投入；期末−期初 == 产出−投入−消费−损耗 | 逐商品台账 + 守恒式 |
| 货币 | 账户余额逐币种；市场成交/运费/偿还只是转移 | 逐币种汇总不变 |
| 土地/资产 | `Σ AssetShare(location, asset)`；迁移前后与旧 capacity 对账 | 逐份额表 |
| 债权债务 | `Σ Debt.principal` 按商品/主体；`ClassRow.debts` 引用与债务表一一对应 | principal 与引用计数 |
| 劳动 | `Σ allocation(lot) ≤ laborSupply`；退出释放只减配额、不动供给 | 逐 lot 上限 |
| 多线程 | 1/4/8 同初态领域读数逐值相等 | 两份 store/API 读数 diff |

**性能/内存协议**：每片冒烟记墙钟；里程碑片记 JFR 或 `/usr/bin/time -v` 峰值 RSS；V 阶段跑全年 360 tick ×
1/4/8 各一次并落盘日志到 `.superpowers/sdd/2026-09-29-economy-evolution-r4/`。

---

## 4. 关键设计裁定（用户授权范围内的自行裁定）

1. **AssetShare 是唯一实物总账**：生产规模只从 `AssetShare` 派生；`Industry.capacity` 在 B.2 迁移后不再作为产能来源；
   `Industry` 只留模板。
2. **一个 unit = 一个经营者的一段实际生产活动**：投入、产出、关系、状态、经验 mode 都挂 unit；同 industry 多 unit 合法。
3. **债务两端仍是 `HouseholdId`**：ESTATE/WORKSHOP 等聚合 operator 通过 `EconomicHouseholdResolver` 找经济家户；
   找不到就不伪造家户债，允许缩产/退出并处置资产/劳动（不强行造借债主体）。
4. **退出以“资产/劳动/债务/库存都有去向”为完成判据**：不得只改 `OperatorCondition.status`。
5. **自用维生优先于市场标签**：市场滞销不是破产；退出必须有“无法再生产”证据。
6. **预设由 GM 登记、带版本**：模拟器不发明技术/制度；旧 unit 不因登记/修订被悄悄改关系。
7. **经验挂稳定 HouseholdId × modeKey**：阶层/地点是视图；纯收租不产生经验；经验不自动触发质变。
8. **需求层次是配置而非硬编码**：旧代码只有粮/布日需口径，没有“类马斯洛”公式；本计划明确新增可配阈值/阶梯/上限。
9. **不实现**：资本体、国际经贸、完整 GOV、自动发明、复杂个人决策 Agent、跨地区人口迁移（但保留稳定身份/债务/资产路径）。

---

## 5. 逐片执行顺序与文件所有权（防覆盖）

| 顺序 | 切片 | 主要文件所有权（一个代理一次只碰这些） | 出口判据 |
|---|---|---|---|
| 1 | B.2 | `simos-economy-api/relation/*`、`simos-economy` model/EconomyData/ChangeSet/Codec/migrate/time 经济结算、`simos-app` seeder/ApiViews | 双 unit 夹具结算各归各账；旧档可加载；单 unit 90 tick 等价 |
| 2 | B.3 | `simos-app/world/EconomySeeder`、economy migrate/reconcile、`spi` 新命令、`Shell`/`CatalogTool` | 同 hex 多 unit；`Σ份额==capacity`；c1 孤儿债 0 |
| 3 | B.4 | `time/HouseholdClassRule`、`SocialClassId` 读口 | 纯派生；无 slotCapFallback；零富农如实 |
| 4 | E1 | economy `OperatorSettlement/EconomySettlement/StressPolicy`、`HouseholdCondition`、新 `livelihoods`、app 读口 | 退出四去向；自用维生不退出；人口不变 |
| 5 | E2 | economy 新 `DemandEntry/ProductionCandidate/EconomyEntrySettlement/spi`、`MarketSettlement.ordersFor`、`Shell/CatalogTool` | GM 需求 → D+1 订单 → TRIALING；无预设则未满足 |
| 6 | E3 | economy 新 experience 状态/结算/读口、`ProductionUnit.modeKey` 消费 | 实劳实产才有经验；阶层变化不丢；有界渐近 |
| 7 | E4 | economy `NeedProfile`/消费/订单、`FlowRow` 扩展、app 读口 | 同人口不同所得不同消费；基本/改善分开；反馈链闭合 |
| 8 | V | 测试与验收工具 | 全部门禁与场景 |

**每片结束控制方必须**：审 diff → 固定初态冒烟（≤3 min）→ 守恒读数 → 写片报告（现象/证据/差异/阻断）→ `git commit`（不 push）。
