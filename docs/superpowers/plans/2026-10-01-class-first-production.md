# 阶层先行经济：生产代码替换与双向流动计划

> 分支：`refactor/class-first-economy`
> 基础：`refactor/economy-ideal @ e53059f2`（独立试点已跑通 360 tick）
> 原则：不再单独写“阶层流动系统”；阶层只是债务、积累、土地交易、租佃、劳动组织和生产资料所有权变化之后的**重新分类结果**。

## 0. 总目标

1. 把独立 `pilot` 的“阶层先行生产结算”升级为生产代码的第一性状态与日结算阶段；
2. 替换旧的家户合同 + 关账清收 + 单一阶层位置路径；
3. 加入**向下与向上不对称**的双向流动；
4. 所有流动速率、催收比例、土地价格、租地机会、资产获得速度都可由 GM 政策工具调控；
5. 先影子对拍，再切换权威，最后删除旧路径。

## 1. 阶层判定：不存在独立的“流动系统”

阶层由以下物质关系共同决定：

```text
AssetsOwned / AssetsOperated
  + LaborRelation（自己劳动 / 雇人 / 出租劳动）
  + IncomeSource（地租 / 工资 / 经营残值 / 分成）
  + 与生产资料的 relationToMeans
  ⇒ ClassPosition
```

任何资产、租佃、劳动、收入变化发生后，只调用同一个 `ClassPositionResolver` 重新分类；
不为“升级/降级”单独写概率系统。

## 2. 向下流动

### 2.1 触发

`CollectionPolicy`（按 mode/collectorClass 配置，GM 可调控）：

```text
collectionThreshold        账面欠债总价值阈值
collectionTriggerRatio     债务 / (流动商品 + 资产价值 + 预期所得)
collectionRatioPerMille    每期催收比例
downwardCapPerMille        普通环境下每年核心资产强制处置上限
landPricePolicy            地主/GOV 定价
seizurePriority            流动商品 → 土地 → 工具 → 作坊
```

触发后：

```text
1. 先扣流动商品/货币（保留生存/种子/租金储备）
2. 不足 → 按 landPricePolicy 收核心生产资料
3. 收地进入 LandForSale / LeaseSupply 池（不消失）
4. 债务按收走价值减少；剩余 arrears 继续滚动
5. 资产/劳动/收入低于当前阶层下限 ⇒ ClassPositionResolver 重新分类为更低位置
6. 人口按 flowRate 向更低阶层家户流动：
   flowRate = min(maxFlow, baseFlow + debtPressure × flowSlope)
```

### 2.2 向下速率必须可调控

GM 可改：

- `collectionThreshold`
- `collectionTriggerRatio`
- `collectionRatioPerMille`
- `downwardCapPerMille`（例如普通年 15%）
- `landPricePolicy`
- `seizurePriority`
- `baseFlowPerMille` / `flowSlope` / `maxFlowPerMille`

## 3. 向上流动

### 3.1 四个核心量

对每个家户/每个 unit：

```text
InvestableSurplus_h =
  max(0,
      Surplus_h
      − DebtService_h
      − ConsumptionReserve_h
      − ProductionReserve_h)

AccumulationFund_h(t+1) = AccumulationFund_h(t) + InvestableSurplus_h(t)

AssetGap_{h, nextClass} =
  threshold(nextClass) − assetsOf(h)          // 按 asset kind，如土地/工具

OpportunitySupply =
  LandForSale + LeaseSupply + 其它可购/可租生产资料
```

注意：

- 不跨 unit 硬折；粮、钱、工具分别记；需要购买力时用有效期价格；
- `ConsumptionReserve`、`ProductionReserve` 优先于积累；
- `AccumulationFund` 是滚动变量，不每 tick 新建记录。

### 3.2 获得量

```text
Acquisition =
  min(
      AccumulationFund / Price(AssetGap),
      OpportunitySupply,
      AcquisitionCap
  )
```

- `AcquisitionCap` 是**向上速度上限**，普通环境建议为向下处置上限的 1/3～1/2；GM 可调控；
- 成交后不立即改阶层，先更新资产/租佃/劳动/收入；
- 再由 `ClassPositionResolver` 重新分类。

### 3.3 三条典型向上路径

**雇农 → 佃农（最快）**

```text
条件：
  Reserve ≥ C_seed + C_tools + C_rent + C_foodBuffer
  且 LeaseAvailability > 0
  且 有足够家庭劳动力

速率：
  符合资本条件的候选户 × 租地机会 × 年度流动上限
  普通：5%～15% / 年（GM 可调）
  土地稀缺：接近 0
  战后/垦殖/地主缺劳动力：可高于 15%
```

**佃农 → 中农（买地，较慢）**

```text
条件：
  LandPerWorker ≥ L_M
  AccumulationFund ≥ Price(Gap_land)
  LandSupply > 0

成交后：
  要求自有土地达到门槛并维持一个完整生产季
  再重新分类 TENANT → MIDDLE

普通速率上限：2%～8% / 年（GM 可调）
```

**中农 → 地主（最慢，不能只是有钱）**

```text
条件：
  LandOwned > LandSelfOperable
  且 RentIncome + ResidualFromOthers 占比达到门槛

机制：
  中农积累 → 买下负债户出售的土地 → 土地超过家庭自营能力
  → 出租/组织他人劳动 → 自动重分类为 LANDLORD

不给显式升级概率；由土地市场与生产关系自然产生。
```

### 3.4 上下流动互相提供机会

```text
A 中农负债 → 出售土地
  → LandForSale 增加
  → 地主 / 富裕中农 / 积累多年的佃农都可买
  → A 可能 MIDDLE→TENANT，同时 B 可能 TENANT→MIDDLE，C 可能 MIDDLE→LANDLORD
```

因此向下出售的资产不消失，而是向上流动的 `OpportunitySupply`。
这也是防止“所有人迟早掉到底层黑洞”的关键。

## 4. 不对称约束

普通环境：

```text
DownwardCapPerMille  ≈ 10%～15% / 年   // 债务强制处置上限
UpwardCapPerMille    ≈ 3%～8%  / 年   // 积累获得核心资产上限
```

即：

```text
T_up > T_down
```

特殊时期（垦殖、战后、土地释放、移民）可临时放开 `UpwardCapPerMille`；
GM 工具只改政策参数，不改派生读数。

## 5. GM 政策工具

新增/扩展 `MobilityPolicy` / `CollectionPolicy`（按 mode + class transition）：

```text
MobilityPolicy(
  modeId,
  fromClassPosition, toClassPosition,
  direction: UP | DOWN,
  triggerThreshold,
  collectionRatioPerMille,
  downwardCapPerMille,
  upwardCapPerMille,
  landPricePolicy,
  leaseAvailabilityPerMille,
  assetGapThresholds,
  reason
)
```

- GM 用现有 `economy.GmAdjust` 模式做：预览 / 原因 / 前后差异 / 审计；
- 普通 GOV Agent 不可调用；
- 只改源状态（阈值、价格、上限、供给），不直接改阶层/债务读数。

## 6. 生产代码替换阶段

### C1：状态组件化 + 影子运行

- 把 pilot 的 `ModeParticipation`、`ClassProductionAccount`、`HouseholdProductionAccount`、`MobilityPolicy/CollectionPolicy`、`ClassFlowEvent` 升为 `simos-economy` 正式模型；
- 加进 `EconomyData`/`EconomyChangeSet`/`EconomyCodec`/`EconomyStateBuilder`/`EconomyPayloads`/`EconomySeedHandler`，旧档缺键为空；
- 新增 `ClassFirstSettlement`，只在 `modeParticipations` 非空时**影子运行**：计算账户、流动事件、政策读口，不改旧生产/消费/市场/债务路径；
- 读口/dashboard：阶层账户、债务/索取权、土地集中、迁移事件、红灯。

### C2：影子对拍

- 用三国 COMPLETE 世界和 pilot 世界同时跑旧路径与阶层路径；
- 对拍：人口、粮/钱守恒、债务规模、阶层计数、迁移事件；
- 差异逐项报告，不通过就停。

### C3：权威切换

- 对 `modeParticipations` 非空的 mode/world，新的阶层结算成为权威：
  * 生产、投入、分配、消费、借款、催收、人口流动走阶层路径；
  * 旧 `EconomySettlement` 的对应阶段对这些 mode 不再执行；
  * 旧路径保留 fallback 开关。

### C4：双向流动与 GM 政策

- 加入 `InvestableSurplus / AccumulationFund / AssetGap / OpportunitySupply / AcquisitionCap`；
- 加入土地/租佃市场池；
- 加入上下不对称速率与 GM 政策工具；
- 360 tick 验证：中农→佃农、佃农→中农、中农→地主可同时发生。

### C5：删除旧路径

- 旧 household 合同、关账清收、单 ClassStanding 位置、旧周期生产在全部世界退役；
- 迁移旧档：household 债务 → 阶层/家户生产账户；旧 stratum → ClassPosition；
- `clean verify`、重放、守恒、旧档迁移全部通过。

## 7. 验收

1. 360/720 tick 内同时出现向下与向上流动，而不是单向阶层黑洞；
2. 债务跨 tick 滚动，按 installment/到期处理，不每 tick 清收；
3. 正净额只记 claim，负净额只记 debt；
4. 非必要品缺口只扣效率，不阻生产；
5. 土地出售/出租进入 OpportunitySupply，被其它阶层买走/租走；
6. GM 调 `upwardCapPerMille` / `downwardCapPerMille` / 地价 / 租地供给，能直接改变流动速度和数量；
7. 守恒：人口、商品、货币、土地、账户净额全部对账。

## 8. 非目标（本计划暂不包含）

- GOV 税、救济、政府采购的完整制度实现；
- 商业/金融作为一个独立生产方式的完整实现（放贷先用独立 Lender/GOV 账户模拟）；
- 全地图跨格市场与运输重构。
