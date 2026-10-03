# 市场与债务实现计划（2026-10-04）

> 依据：`docs/superpowers/specs/2026-10-04-market-debt-architecture.md`；
> 生产方式语义另见 `docs/superpowers/specs/2026-10-05-tenancy-wage-production-modes.md`（佃农制/雇农制、租形/工资形、欠租欠薪）。
> 纪律：一个阶段一个编码代理；阶段门禁只做编译；测试统一留到最后（AGENTS §一.5）。
> 本阶段先做 **M0：两轮探针**，确认价格/债务/清算数学可跑，再排后续运行时。

## 0. 目标与非目标

**目标**
1. 把本轮冻结的架构记成 spec（已完成）；
2. 用一个**测试小世界 + 一个市场区**跑两轮，验证：
   - 缺货 → `ref` 上涨；
   - 卖不掉 → `ref` 下跌（且允许低于成本）；
   - 无成交 → `ref=0`；
   - 买家按总财富降序、无出价、按最低 ask 成交；
   - 成交直接记单边债务；信贷池为卖方创造/支付货币；
   - 商品/货币/债务的账能对上。
3. 出后续阶段的开发批次。

**非目标（M0 不做）**
- 不接入 `EconomyData`、不改 `ClassFirstState`、不建正式市场状态；
- 不实现完整生产方式/劳动/口粮运行时；
- 不实现脚夫阶层、跨市场区、风险溢价、价格归零后的货/钱去向。

## 1. M0：两轮探针（本阶段）

**交付物**
- spec：`docs/superpowers/specs/2026-10-04-market-debt-architecture.md`
- 探针：`simos-economy/src/test/java/io/mosire/simos/economy/market/TwoRoundMarketProbeTest.java`
- 本计划：`docs/superpowers/plans/2026-10-04-market-debt-implementation-plan.md`

**探针世界**
- 1 个 hex / 1 个市场区；2 种商品：粮、布。
- 4 个家户：地主、农户、织户、雇农；各有库存/货币/每轮需求/生产成本。
- GM 初始成交价：粮 100、布 200（最小货币单位）。
- 参数：`α_up=200‰`、`α_down=200‰`、`p_max=10000`、利息 `50‰/轮`、未售即刻降价 `200‰`。
- 每轮顺序：挂单 → 财富排序 → 撮合（成交即记债务）→ 更新 ref（规则 A/B/C）→ 计息 → 消费 → 生产 → 下一轮。

**验收判据（M0）**
- Round 1：粮缺货 → `ref_grain` 上涨；布过剩 → `ref_cloth` 下跌；
- Round 2：延续规则；无成交商品 `ref=0` 的分支至少被执行一次（可通过场景或第二商品触发，见实现备注）；
- 成交记录、未售、未满足、债务、货币发行量可打印并对账；
- `Σ库存` 在交易中守恒，生产只按配方增加；`Σ货币 = 初始货币 + 信贷池发行`；
- 测试可重复、无随机、无时钟、全整数。

**实现备注**
- 探针只实现“市场 + 价格 + 单边债务 + 简单消费/生产/劳动优先级”，正式生产方式/阶层位置/资产容量留到 M1–M2；
- 规则 C（无成交 → ref=0）已用独立小场景补证（`RuleCNoTransactionProbeTest`）；
- 探针是“可跑的数学原型”，不是正式测试基线；但按用户 2026-10-04 要求，已提前补齐 market 探针测试并保持全绿。

### 1.1 M0 探针扩展（2026-10-04 追加）

- `RuleCNoTransactionProbeTest`：无成交 → `ref=0` → 下一次重新定价；
- `ProductionExitOnLossProbeTest`：`ref<cost` 停产、`ref>cost` 继续；
- `ThreeHexTransportProbeTest`：3 hex 跨格成交与运输托管；
- `LongRunMortalityProbeTest`：3000 轮、死亡删债、发行流量/存量债务分离结论；
- `FoodLaborProductionProbeTest`：C1 留口粮与投入、断粮降效率、劳动受限产出；
- `LaborPriorityProbeTest`：多 recipe 优先级、劳动预算收缩、投入受限时让出劳动；
- `RepaymentWaveProbeTest`：有货币家户按 `min(钱, 债)` 还债；
- `CircularFlowCreditProbeTest`：闭环收入下发行/还款/存量债务 3000 轮恒定，作为断粮场景的正面对照。

合计 9 个测试类 13 个测试全绿；完整读数与结论见
`docs/superpowers/reports/2026-10-04-market-probe-longrun.md`。

## 2. 后续阶段（只列轮廓，未开工）

| 阶段 | 内容 | 主要落点 | 阶段门禁 |
|---|---|---|---|
| M1 | 家户/生产方式/阶层位置/hex 生产画像 + **tenancy / wage_farm 两个 mode 与租形/工资形** | `ProductionMode`/`ClassStructure`/`ClassPosition`；`ProductionCandidate` 复用；默认 `ProductionRelation`（见 tenancy-wage spec §7 N1/N2） | 编译 |
| M2 | 劳动优先级 + C1 + 生产末循环（方案甲、吃饱度、效率）+ **租/工资结算** | 劳动分配、配方、口粮、六档 `RuleType`、`Pool × Weight`、现金腿、**劳动→收入→还债闭环** | 编译 |
| M3 | 单边债务 + 多边净额 + 迁移/死亡/阶层下滑 + **欠租/欠薪** | 债务账、净额、MobilityPolicy、`rent_arrears`/`wage_arrears` | 编译 |
| M4 | 每格市场订单表 + 规则 A/B/C + 财富优先 + 运输抽象 | 市场区状态、价格、脚夫抽象 | 编译 |
| M5 | 全局货币/信贷池 + 国家兑换/价格调控 | MoneyStock、CreditPool、锚定窗口 | 编译 |
| M6 | 统一测试 + 小世界两轮→多轮 + 长程验收 | 测试/证据 | 全绿 |

### 2.1 多元部门补充（2026-10-05）

来源：`docs/superpowers/specs/2026-10-05-diversified-transport-handicraft.md`。
用户裁定“生产环境本来就是多元化的”，后续测试不看单次歉收，改跑多元 365×4。

| 批次 | 内容 | 落点 | 门禁 |
|---|---|---|---|
| T1 | 探针加 `FIBER` + `TransportTeam`；运输费从 escrow 改为承运队收入；回程/运力读数 | `ProbeEconomy`、运输测试 | 编译 |
| H1 | 探针加纤维户 + 作坊（纤维 + 劳动 → 布，工资钱/粮） | `ProbeEconomy`、手工业测试 | 编译 |
| D1 | 2–3 hex 多元小世界（农村-城镇-集市），跑 1460 轮 | 多元长程测试/报告 | 全绿 |
| M4+ | 正式 `TransportOrganization`、service commodity、脚夫状态、运输市场 | 市场/货币层 | 编译 |
| M1+ | 手工业 `ProductionMode` + relation + 亏损停产/转产选择 | mode/关系层 | 编译 |

### 2.2 城市/商人/城市化补充（2026-10-05）

来源：`docs/superpowers/specs/2026-10-05-city-merchant-urbanization.md`。
用户裁定：城市建筑在当前 hex 算比例；脚夫改成商人/商队；商人越多运费越低但指数级占城区；
城市工坊成本更低 → 农村工坊人员向城市迁移；初始化已有城市 ⇒ 只算城市人口流动，拉力主要来自商贸需求。

| 批次 | 内容 | 落点 | 门禁 |
|---|---|---|---|
| C0a | 每格土地预算：`availableArableMu = arableMu − cityBuiltMu`；农业可支撑人口/农村剩余 | map/social/economy | 编译 |
| C0b | 当前 hex 建筑比例读数（城区/可耕地/剩余） | 读口/报告 | 编译 |
| C1a | 商人数量/商队容量/路线成本折扣 + 指数城区占用 | `City.props`/市场层 | 编译 |
| C1b | M4 市场结算接 `MarketRegion`/`TradeRoute`/`ShipmentBatch`；运费付给商人 | market 运行时 | 编译 |
| C2a | 农村/城市工坊利润比较与聚集折价 | 生产/关系层 | 编译 |
| C3a | 人口迁移机制（rural lot → urban lot，劳动/债务随行） | social+economy+app | 编译 |
| C3b | 商贸拉动的城市人口流；365×4 城市世界验收 | 测试/报告 | 全绿 |

## 3. 风险与已知缺口

- 价格只“跟随上一 tick”可能产生棘轮/振荡；M0 用规则 A/B/C 直接验证；
- 单边债务没有对面债权，账上只有负债规模；货币/债务对账靠信贷池发行量；
- **各政府的货币发行权必须逐国显式**（用户 2026-10-05 裁定）：`Government.issuable` 空集 = 不是发行人；
  同一币种只能有一个发行主体；谁有权发行/回笼必须写进世界状态，不能靠“没有发行记录 = 不能发”默认；
  发行/回笼只能是 `MoneyIssuanceRecord` 的 `INITIAL_ENDOWMENT`/`FISCAL_ISSUE`/`WITHDRAWAL`，
  普通账户不得透支；M5 要把“逐国发行权”接进真实命令路径与审计读口；
- **没有收入闭环时，死亡率单独不能稳定存量信贷**：长程探针（`docs/superpowers/reports/2026-10-04-market-probe-longrun.md`）
  显示死亡删债使发行流量收敛为常数，但借款阶层无收入、还债 wave 收不到钱，存量债务仍线性/复利增长；
  M2 必须把劳动→收入→还债跑通，或在 M5 增加信用额度上限/利息去向规则；
- **现金租/现金工资需要“先市场后结算”或欠租/欠薪债务**：当前货币档只付“付款方本期可见货币”，
  付不出的部分只进读数、不落债权；货币租佃农卖不出粮、雇农制经营者垫不出工资时会退化成“静默付 0”。
  M2/M3 必须二选一并写进主循环：① 先市场、后现金租/工资；② 不足部分落 `rent_arrears`/`wage_arrears` 单边债务；
- **整数人口死亡取整**：小队长程探针里理论死亡 <1 被截断，出现“永久半饥饿但不死亡”的假平衡；
  正式运行时需在概率化死亡、余数累积、定点人口中选一；
- 无出价系统时，需求只通过“买不到/未满足”进入读数，价格由卖家 ask 和 A/B/C 驱动；
- 运力/脚夫/跨格尚未建模，M0 只做同格市场；
- 本阶段按用户要求提前写了 market 探针测试；它们仍是“数学原型”而非正式运行时测试，
  正式测试与变异自证按仓库纪律留到 M6 收尾阶段。

## 4. 下一步

1. **M0 已完成**：探针两轮输出见
   `docs/superpowers/reports/2026-10-04-market-two-round-probe.md`
   （粮缺货涨、布过剩跌、单户降价、单边债务、货币=初始+信贷发行均已跑通）；
2. **M0 探针扩展已完成**（2026-10-04）：规则 C、`ref<cost` 停产、3 hex 运输、3000 轮死亡/删债、
   食物/劳动/生产 C1、多 recipe 劳动优先级、还债 wave、闭环收入正面对照共 9 个测试类 13 个测试全绿；
   长程结论与待定项见 `docs/superpowers/reports/2026-10-04-market-probe-longrun.md`；
3. **佃农制/雇农制语义已冻结 + 探针已跑通**：
   - 设计见 `docs/superpowers/specs/2026-10-05-tenancy-wage-production-modes.md`
     （两种 mode、三种租形、三种工资组合、角色四分层、现金腿/欠租欠薪、选择评分）；
   - 结果见 `docs/superpowers/reports/2026-10-05-tenancy-wage-cycle-results.md`
     （四条基准循环闭合；收成冲击下各 mode 的风险分配读数）；
   - 探针累计 10 个测试类 20 个测试全绿；
4. 用户裁定“收入闭环 / 信用额度 / 利息去向 / 死亡取整 / 租形-工资形默认值”后，按 M1–M6 分批派编码代理；
5. **T1/H1/D1 已完成**（2026-10-05）：运输队 + 运费归户、纤维 → 作坊 → 布、农村-城镇-集市
   365×4 多元长程测试全部跑通；设计见
   `docs/superpowers/specs/2026-10-05-diversified-transport-handicraft.md`，
   结果见 `docs/superpowers/reports/2026-10-05-diversified-transport-handicraft-results.md`；
   **自然死亡/出生探针已补**（`NaturalMortalityProbeTest`，25‰/30‰ 年率），市场探针累计 15 个测试类 26 个测试全绿；
6. **城市/商人/城市化已调查并排入 C0–C3**（2026-10-05）：土地预算（可耕地 − 城区占地）、
   商人/商队替代脚夫（路线成本折扣 + 指数城区占用）、城市工坊成本折价、rural→urban 人口迁移、
   商贸需求拉动的城市人口流；设计见
   `docs/superpowers/specs/2026-10-05-city-merchant-urbanization.md`；
   **7 格中心城探针已先行跑通**：商人阶层（脚夫/个体户/老板）、农村贸易成本累积、城市承载/慢速扩建，
   结果见 `docs/superpowers/reports/2026-10-05-city-merchant-probe-results.md`；
   已明确：城市/商人不得绕开各国 `Government.issuable` 的货币发行权；
7. 收尾阶段统一补测试与变异自证（守恒、不丢失、静默付 0、断粮、欠租欠薪、自然死亡/出生、发行权七类关键项）。
