# 阶层先行生产结算 + 滚动债权 + 催收/人口下滑：最小试点计划

> 目标：先在一个独立试点里验证你描述的机制，不改三国主路径、不改旧周期生产。
> 试点通过后，再把状态组件化进 `EconomyData` 并替换旧结算。

## 0. 机制定义

单 tick 顺序：

1. **生产要求**：生产组织按 mode 制定本期投入需求（土地、劳动、种子、工具、口粮）。
2. **投入抽取**：
   - 自己提供 ⇒ `self-input`，不产生外部债权；
   - 其他家户/商户提供 ⇒ 提供者获得滚动索取权，生产组织负担滚动债务；
   - 抽不到 ⇒ 生产规模按缺口缩减并记短缺。
3. **生产**：产出按商品/单位落账。
4. **阶层分配**：按 mode/class 的分配规则，把产出分到各阶层位置：
   - 所有者 → 地租；
   - 劳动者 → 工资/分成/给养；
   - 经营者 → 残值；
   - 国家 → 税（本试点先为 0）。
5. **分到家户**：按 `ModeParticipation.sharePerMille` 把阶层结果分给参与家户。
6. **滚动账户**：每个 `(家户, mode, 对手方, unit, terms)` 一条账户：
   - 正净额 ⇒ `claim`（别人欠它）；
   - 负净额 ⇒ `debt`（它欠别人）；
   - 利息只加在 `debt` 上；不是每 tick 新增记录。
7. **消费**：
   - `baseRation`：所有人口都要；
   - `laborRation`：只有实际出劳动的家户/成员需要；
   - 缺非必要品 ⇒ 扣劳动效率，不阻生产；
   - 缺基础口粮 ⇒ 记饥饿缺口/红灯。
8. **借款顺序**：借商品 → 借钱买 → 需求红灯。
9. **到期/催收**：
   - 只有到期、阈值或压力触发，才催收；
   - 先扣流动商品/货币（保留保护储备），再按地主/GOV 定价收土地/工具；
   - 收地减债，资产份额滚动转移。
10. **人口下滑**：
    - 债务压力越高，人口向更低阶层家户流入越快；
    - `flowRate = min(max, base + debtPressure × slope)`；
    - 人口按 `Membership` 拆分转移，劳动/需求/阶层同步更新；
    - 数据上表现为中农/富农人口持续流向贫农/雇农。

## 1. 试点范围（本阶段只做这些）

- 独立试点包：`simos-economy/.../pilot/`。
- 单 mode（**佃农制农业**），只做这一套；政治经济学语义必须先写死：

  | 位置 | relationToMeans | laborRole | surplusRole | 分配规则 |
  |---|---|---|---|---|
  | 地主 | OWNER | NONE | SURPLUS_RECEIVER | 收地租（试点取固定实物粮租） |
  | 自耕农/中农 | MIXED | BOTH | SELF_SUBSISTENCE | 自有地经营；产出先覆盖投入，残值归自己 |
  | 佃农 | DIRECT_LABORER | PROVIDER | WAGE_EARNER / 分成 | 交租后余粮归己；欠租资本化为债务 |
  | 雇农 | DIRECT_LABORER | PROVIDER | WAGE_EARNER | 工资/给养；无地 |

  - `TENANCY` 份额不能由佃农卖；土地所有权只在地主/自耕农之间转移；
  - 地租、工资、残值先分到阶层位置，再按 `ModeParticipation` 分到家户；
  - 初始家户：**H1 地主、H2 中农/佃农、H3 雇农/贫农；不放商户。**
- **放贷/商业本阶段不塞进农业 mode**：
  - 若试点需要外部债权人，使用独立 `PilotLender` / GOV 特殊账户：默认拥有大量资金、0 流动性，利率与条款由制度参数给出；它不参与农业阶层结构；
  - 商业将来另做独立生产方式（commerce mode），由 GOV 或专门单位执行；本试点默认不启用。
- 不接市场全量撮合、不接跨格运输、不接 GOV 税；价格/利率/地租用试点内显式配置。
- 不修改 `EconomyData` 29 组件、不修改三国 compact world；达到验收后再产品化。

## 2. 试点数据结构

```text
PilotClassPosition { id, name, relationToMeans, laborRole, surplusRole }
PilotMode { id, classRules: Map<classPosition, PilotClassRule> }
PilotClassRule {
  rentSharePerMille, wageSharePerMille, residualTo,
  inputResponsibility, lossResponsibility
}
PilotHousehold {
  id, classPosition, population, laborPerCapita,
  goods: Map<unit, qty>, money, land: qty, tools: qty,
  participationSharePerMille
}
PilotLender {                    // 独立放贷方：GOV/特殊单位，不属于农业阶层
  id, fundsByUnit, interestRatePerMille, nextDueTick, collectionPower
}
PilotProductionAccount { mode, classPosition, tick,
  requiredInputs, actualInputs, output, distributed, residual }
PilotHouseholdAccount { household, mode, counterparty, unit, terms,
  cumulativeNet, debt, claim, interestAccrued, nextDueTick, status }
PilotCollectionPolicy {
  collectorClass, collectionThreshold, collectionTriggerRatio,
  collectionRatioPerMille, landPricePerUnit, seizurePriority,
  baseFlowPerMille, flowSlopePerMille, maxFlowPerMille
}
PilotTickReport { tick, classCounts, debtByClass, claimByClass,
  goodsByHousehold, landByHousehold, redLights, transitions }
```

## 3. 引擎流程

```text
for tick in 1..360:
  1. plan(mode)
  2. procureInputs(plan)         // self / external claim / shortage
  3. produce(plan)
  4. distributeByClass(plan)
  5. allocateToHouseholds()
  6. rollAccounts()              // +new net, +interest on debt
  7. consume()                   // base + labor ration, efficiency penalty
  8. borrowIfNeeded()            // 从 PilotLender/GOV 借：goods -> money -> red light
  9. dueAndCollect()             // threshold/ratio/due, liquid -> land
 10. populationFlow()            // debt pressure -> membership transfer
 11. recordTickReport()
```

守恒要求：

- 每 unit 的商品/货币真实库存变化 == 产出 − 消费 − 投入 − 收缴 + 转移；
- 正 claim 与负 debt 必须对冲（同一生产账户内）；
- 土地/工具转移前后总量守恒；
- 人口转移前后总数守恒。

## 4. 验收场景

- 跑 360 tick，输出：
  - 每个 tick 的阶层家户/人口计数；
  - 每阶层债务/索取权规模；
  - 各户粮/钱/土地/工具；
  - 红色需求灯数量；
  - 阶层迁移事件（谁、何时、从哪档到哪档、迁出多少人口、收走多少地）。
- 必须验证：
  1. 债务跨 tick 累积，不每 tick 强制清收；
  2. 正净额记 claim，负净额记 debt；
  3. 非必要品缺口只扣效率，不阻生产；
  4. 借商品→借钱→红灯的顺序；
  5. 催收触发时先扣流动商品，再按地主定价收地减债；
  6. 债务压力上升 ⇒ 人口向更低阶层家户流入；
  7. 360 tick 内至少出现一次中农/富农 → 贫农/雇农的人口迁移与阶层计数变化。
- 若某一步不触发，必须输出逐 tick 原因，不得调数据绕过。

## 5. 实施顺序

1. **P5a**：试点数据结构与引擎骨架（`pilot` 包，纯规则、无 `EconomyData` 改动）。
2. **P5b**：投入抽取、生产、阶层分配、家户份额、滚动账户。
3. **P5c**：消费/劳动口粮/效率、借款顺序、红灯。
4. **P5d**：催收、土地定价与转移、人口流动、阶层迁移。
5. **P5e**：360 tick 测试 + `PILOT` 读数输出；只跑 `simos-economy` 目标测试与编译门禁。
6. 控制方复核后提交；通过后再规划 `EconomyData` 组件化与三国主路径接入。

## 6. 边界

- 不做：GOV 税、救济、政府采购、全面市场撮合、跨格运输、货币发行。
- 不改：E1–E6、P1–P3、三国 compact world、旧结算路径。
- 不写超过必要规模的大测试；试点测试单文件控制在必要范围内，读数直接打印。
