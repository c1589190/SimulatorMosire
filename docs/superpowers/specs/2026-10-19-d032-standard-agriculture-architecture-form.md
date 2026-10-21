# D-032 标准小农业经济架构设计表单（2026-10-19）

> **取代者：`docs/superpowers/specs/2026-10-19-d032-standard-agriculture-architecture-design-v2.md`（2026-10-19）** —— 本文仅作历史留痕，**勿据此开工**。
> **状态：控制方按用户最新裁定整理的目标架构表单；不是实现验收。**
> 父设计锚点：`docs/superpowers/specs/2026-10-19-d032-rural-runtime-target-design.md` 与
> `docs/superpowers/specs/2026-10-19-d032-rural-production-land-labor-revision.md`。
> 与本表单冲突的旧目标/旧约束以本表单为准；旧文不回头改，只保留历史留痕。
> 本表单只覆盖**标准小农业**；不是通用经济/工业/加工链的最终架构。

## 0. 用户原话与裁定入口

本表单是架构汇总；逐字原话、`R-2026-10-19-01..32` 裁定、调查记录见：

```text
docs/superpowers/specs/2026-10-19-d032-rural-production-land-labor-revision.md
```

本表单对应的最新用户原话：

```text
4 无敌了，家户算的预期需求必须包括NaturalNeed那一部分，作为一个统一的报账
7.1 是的，组合起来，最终报给经济模块的预期需求其中不但包括日需求口粮，还包括生产需求等所有需求，因为所有需求都必须在市场层实现统一购买
7.2 后者吧，看着靠谱一些（2026-10-19 更正：原“候着吧”是打字错误）
如果没有其他裁定的话，我强烈建议你整理成一份新的架构设计表单，因为旧的表单很难改，需要特殊列出的项目则是修复Agent需要关注的重点
```

## 1. 范围

### 1.1 本批做

- 一个 hex 一个 `standard_agriculture` 标准农业生产聚合；
- 土地、种子粮、口粮；
- 土地所有者按土地份额获得粮食；
- 有地户按利润选择自耕/雇人；
- 受雇是生产位置选择；
- 统一 `DailyDemandBill`（自然需求 + 生产需求 + 其他需求）；
- 生产轮先、市场轮后；
- 市场统一购买全部需求；
- 简单跨 hex 运力函数；
- 标量土地库存与任意数量债务抵地。

### 1.2 本批不做

- 研磨/加工/手工业/织布/贸易生产方式；
- 工具、耕牛、作坊、机器等额外投入；
- 自增量农业进度系统；
- 旧档兼容/迁移；
- 复杂运力体系；
- 生产方式的经验/升级/文化/天气；
- 多作物、土地质量/肥力细分。

## 2. 目标运行序列

> 用 `S0..S8` 表示标准农业新序列，避免与旧 M0–M9 的语义冲突。最终落代码时再统一命名。

| 阶段 | 触发 | 做什么 | 读 | 写 | 失败/边界 |
|---|---|---|---|---|---|
| **S0 Social** | 每 tick | Social 结算出生、死亡、年龄、劳动能力、实际温饱；提供单户温饱线/人口 | Social 权威；上一 tick 温饱事实 | Social 切片；只读事实给经济 | Social 失败 ⇒ 整次 advance 失败 |
| **S1 经济载入** | 每 tick | 载入活家户、土地持有、劳动预算、当前温饱线 | Social 投影；土地库存 | economy 会话投影 | 缺行/投影不一致 ⇒ 契约故障 |
| **S2 进入/迁移/组织** | 每 tick | 新户、迁移、土地取得/卖出后的组织重排 | 人口、土地、债务 | economy 会话；生产组织候选 | 业务拒绝具名；不静默丢户 |
| **S3 生产位置选择** | 每 tick | 家户按市场价格/工资/实际土地/库存选择自耕、雇人、受雇、无业；`hiredLand` 生成雇佣位置并计入预期工资；工单按单位劳动综合收益排序，同收益稳定 tie-break | 上一轮价格/工资；土地份额；劳动预算；温饱线 | 瞬态位置计划、hired 工单、劳动响应 | 无正收益则不占位；工单剩余量留给后续家户 |
| **S4 生产日结算** | 每 tick | 实际播种/实际劳动；播种日扣种子，其他日扣日粮；雇农劳动回传给雇主；生成 `DailyDemandBill`；按日记录效率 | 实际土地/库存/劳动/工单响应 | 库存扣除；日需求账单；劳动/效率读数 | 没种子则只种可覆盖面积；不超预算；不重复扣日粮 |
| **S5 收获/毛产分配** | 周期末 | 按周期累计日效率加权；有收获则算 hex 毛产，按土地份额分给所有者 | S4 每日效率、土地份额、生产状态 | 毛产分配；库存/账本 | 效率按土地占有加权；损失全体按土地份额承担 |
| **S6 工资结算** | 收获后/周期末 | 雇主按实际雇工劳动支付货币工资；不足成普通货币债；雇工不拿产出份额 | 毛产分配后雇主库存；工单/实际劳动 | 工资支付/工资债 | 工资只货币；不拿库存抵工资 |
| **S7 统一市场** | 市场日 | 债务最先；所有需求（NaturalNeed + 生产日耗 + 种子/投入）进同一 `DailyDemandBill`/订单；不查当前库存/现金；卖单只卖实际剩余盈余；商品+土地同一订单簿 | 需求账单；实际库存；地价；运力 | 商品/土地/货币/债务 | 买不到=未满足；未付价款成普通债；统一购买 |
| **S8 Social 事实回传** | 每 tick 末 | 经济回传实际温饱/缺口；Social 据此算下一 tick 出生/死亡 | S4/S7 实际消费与缺口 | Social 事实槽 | 经济不自行判死/缩人口 |

### 2.1 关键顺序

```text
S0 Social
→ S1 载入
→ S2 进入/迁移
→ S3 位置选择
→ S4 生产日结算（扣种子/日粮、实际劳动、日效率）
→ S5 周期末毛产分配（有收获才判）
→ S6 工资
→ S7 统一市场（债务最先，全部需求统一购买）
→ S8 Social 温饱事实
```

`tick0` 没有生产方式正常；除非显式预设，否则不把“已选中的生产组织”当默认已生产。

## 3. 数据模型

### 3.1 土地：标量库存

```text
LandHolding {
  owner: HouseholdId
  hex: HexCoord
  quantityMilliMu: long
}
```

- LAND 不可生产，开局固定；
- 每个 hex 的 `Σ owner.quantityMilliMu == arableMilliMu(hex)`；
- 可以枚举所有 hex 的固定耕地量；
- 市场订单：`(hex, side, quantityMilliMu, price/budget)`；
- 债务抵地：转移具体 hex 的数量；
- 无 block id，无土地池；
- 0 价可转移；作为还债资产价值为 0，不冲减债务；
- 本轮取得土地本轮生产排除，下一轮必须可用。

### 3.2 单 hex 标准农业聚合

> 目标形态，不锁类名。一个 hex 只有一个 `standard_agriculture` 生产总集。

```text
HexStandardAgriculture {
  hex
  totalArableMilliMu          // u，固定
  landOwners: Map<HouseholdId, long>
  selfLandByOwner: Map<HouseholdId, long>
  hiredLandByOwner: Map<HouseholdId, long>
  seedDueByOwner: Map<HouseholdId, Map<CommodityId,long>>
  dailyNeedByOwner: Map<HouseholdId, DailyDemandBill>
  hiredPositions: List<HiredLaborPosition>
  dailyEfficiency: List<DailyEfficiencyRecord>
  grossOutput: Map<CommodityId,long>
}
```

- 有地户按利润选择 `selfLand` / `hiredLand`；
- `hiredLand` 生成雇佣者的 hired-labor 位置，并立即计入预期工资；
- 土地所有者即使不劳动也按土地份额获得毛产；
- 雇农只获得工资/债，不获得产品份额；
- 同一个体可以同时拥有自耕和受雇位置，但总劳动不超过预算。

### 3.3 统一需求账单

```text
DailyDemandBill {
  day
  household
  lines: [
    { commodity, quantity, source: BASE_SUBSISTENCE },
    { commodity, quantity, source: PRODUCTION_DAILY_EXTRA },
    { commodity, quantity, source: PRODUCTION_INPUT },
    ...
  ]
  totalInventoryDeduction
  marketPurchaseNeed
  unmetFacts
}
```

- `BASE_SUBSISTENCE`：Social 温饱线；
- `PRODUCTION_DAILY_EXTRA`：生产方式额外日耗（小幅增加）；
- `PRODUCTION_INPUT`：种子等生产投入（播种日/未耕种面积）；
- 所有需求都在这张账单里；市场只买/卖这张账单对应的需求与盈余；
- 种子是播种日/周期成本，不混进每天的生活需求；但作为 `PRODUCTION_INPUT` 进入同一账单；
- 不再有独立 `naturalNeeds` 表、独立 M5 口粮路径、独立 `SocialSurvivalFacts` 需求行。

### 3.4 劳动位置

```text
HiredLaborPosition {
  employer: HouseholdId
  hex: HexCoord
  landQuantity: long
  requiredLaborMilli: long
  remainingLaborMilli: long
  wagePerLaborMilli / wagePerTick
}

LaborResponse {
  worker: HouseholdId
  position
  suppliedLaborMilli
  realizedWage
}
```

- 同 hex 任何有剩余劳动的家户可响应；
- 按单位劳动综合收益排序，同收益稳定 tie-break；
- 部分响应留下剩余工单给后续家户；
- 实际生产按实际响应劳动；
- 不需要工资的自家劳动优先由家户位置选择决定，不强制。

## 4. 公式

### 4.1 单日效率

对每个有地家户/土地份额：

```text
required_i = a_i * v
e_i = min(1, w_i / required_i)
B_day = Σ_i (a_i / u) * e_i
```

- `u` = hex 总农业土地；
- `a_i` = 该户在该 hex 的土地；
- `v` = 当前生产方式每单位土地每 tick 所需劳动；
- `w_i` = 落到该户土地上的实际劳动（自耕 + 收到雇工劳动）；
- 雇农劳动先回传给雇主，等所有家户劳动分配完，再计入雇主的 `w_i`。

### 4.2 周期收获效率

用户 7.2 已更正为“后者吧，看着靠谱一些”，采用按土地量×天数累计的后者形式（u 固定时等价于每日 `B_day` 的算术平均）：

```text
B_cycle = Σ_d (u × B_day_d) / (u × cycleDays)
```

### 4.3 毛产与账本

```text
hexGrossOutput = baseOutputPerLandTick * u * cycleDays * B_cycle
ownerGrossShare = hexGrossOutput * (a_owner / u)
```

其中 `baseOutputPerLandTick` 是生产方式的基础产出常数（当前示例按满效率 1 粮/土地/tick）。

两笔账：

1. 毛产账：先记录 `grossOutput`；
2. 扣账/给账：按责任主体扣种子/日粮/生产投入，再按土地份额分毛产，再付工资/记债。

### 4.4 责任主体

| 项目 | 责任主体 |
|---|---|
| 自耕土地种子/日粮 | 种田家户 |
| hiredLand 种子/生产成本 | 雇主 |
| 雇工日粮 | 雇工家户（由雇工位置产生） |
| 土地毛产 | 土地所有者，按土地份额 |
| 雇工工资 | 雇主家户；未付成普通货币债 |

## 5. 市场与订单

- 市场轮生产后开；
- 债务最先（市场轮开头）；
- 需求单：`DailyDemandBill` 全部需求，直接按预期缺口挂，不看当前库存/现金；
- 供给单：生产后的实际剩余盈余；
- 商品与土地同一订单簿、同一撮合器；
- 土地订单按 `(hex, quantity)`，无 block；
- 未付货款成普通货币债；
- 自动订单按 `(actor, commodity, side)` 合并，不能 actor 级全删；
- 跨 hex 先用简单 `transportCapacity/price` 函数，不新增运输生产方式。

## 6. 修复 Agent 重点表

> 这是“特殊列出的项目”，修复 Agent 必须逐项核对；不是可选建议。

| 优先级 | 重点 | 现状/风险 | 必须达到 |
|---|---|---|---|
| P0-1 | `naturalNeeds` 双轨 | Social/`SocialSurvivalFacts`/`HouseholdEconomy.naturalNeeds`/市场/M5/GUI 多处各一份 | 只保留 `DailyDemandBill` 一份；所有读者改读同一账单 |
| P0-2 | 生产方式成本 | `MarketRosterBook` 用 full planned seed + 5 天 naturalNeeds；`MarketIntentBook` 查库存/现金 | 种子只按未耕种面积；日粮/生产额外项并入统一账单；需求不查支付能力 |
| P0-3 | 同 hex 抢粮池 | `rationContestedInputs` 把独立家户合成共享池 | 土地/粮食归具体家户；只有同组织/roster 才可共池 |
| P0-4 | 生产日/收获时序 | M6 劳动早于 M7 扣种；M7 按 `progressDays==0` 全量扣种 | 每 tick 先算实际播种/日耗；有收获才在周期末判 |
| P0-5 | 单 hex 农业聚合 | 当前 `ProductionProcess` 单 operator + 每户 unit/roster | 改为 hex 标准农业聚合 + 土地份额 + 雇主/雇工位置 |
| P0-6 | 共享收入/product-share | `RoleDistribution`/`ProductionRules` 产品份额池 | 毛产按土地份额给 owner；雇农只工资/债 |
| P0-7 | 标量 LAND | `landBlockIdOf`、block 转移、block set | `(hex, quantity)`；任意数量；无 block id/池 |
| P1-1 | 每日效率 | 当前 `ProductionEfficiency` 无逐日效率状态 | 记录 `e_i/B_day`；按土地占有加权；周期末汇总 |
| P1-2 | 劳动工单 | 预置 displaced 雇工；固定 roster | 同 hex 动态工单；按收益/劳动选；剩余量传给下一户 |
| P1-3 | 雇工日粮 | 容易在自耕/受雇位置各扣一次 | 每户每 tick 只扣一次日粮；按位置归属/分摊 |
| P1-4 | 市场卖单 | M3 只卖预期产出；自动卖单 actor 级删除 | 卖实际盈余；按 `(actor, commodity, side)` 合并 |
| P1-5 | 运力 | `MERCHANT_CAPACITY_HOUSEHOLD` 全 0 | 简单函数 + 象征性价格；不新增生产方式 |
| P1-6 | 标准世界 | 创世预置 160 farm unit/roster；weave/craft/trade 抢劳动 | 标准农业只保留 `standard_agriculture` + 无业/必要占位；tick0 不默认已选生产 |
| P2-1 | 土地抵债 | 整块/0 价仍转移；landless 无组织 | 0 价可转移但不冲债；下一轮可用；landless 进入下一轮小农生产 |

## 7. 验收锚点

1. 一个 hex 只有一套 `standard_agriculture` 聚合，土地份额合计 = 该 hex 固定耕地；
2. `DailyDemandBill` 是唯一需求来源；不存在独立 `naturalNeeds`/M5 第二套消费；
3. 每户需求账单包含 NaturalNeed + 生产日耗 + 生产投入，所有需求由市场统一购买；
4. 未耕种面积不产生种子需求；全部种完种子需求 0；
5. 每个生产 tick 按实际播种/日耗扣库存；收获只在周期末；
6. 农忙日效率按土地占有加权；缺工损失全 hex 按土地份额共同承担；
7. 毛产先记录，按土地份额分给 owner，再支付雇工工资/记债；雇工无产品份额；
8. 自耕种子/日粮归种田户；hiredLand 种子/成本归雇主；雇工日粮归雇工；
9. 同 hex 工单按单位劳动收益排序；同收益稳定 tie-break；剩余工单传给下一户；
10. LAND 是 `(hex, quantity)` 标量，无 block；0 价可转移但不冲债；下一轮可用；
11. 市场自动订单按商品/方向合并；跨 hex 有非零简单运力；
12. tick0 没有生产方式正常；不把预置 unit/roster 当已选生产。

## 8. 未冻结/待实现确认

- ~~`B_cycle` 的最终汇总式~~（已收口：用户 7.2 更正为后者，按 `Σ_d (u × B_day_d) / (u × cycleDays)`）；
- `DailyDemandBill` 由 app 组装、production 提供额外项、economy 持有唯一副本的具体边界；
- “生产成本额外日耗”的小增量公式；
- 同一户自耕+受雇时，如何把每户一份日粮分摊到多个位置；
- 工单 tie-break 的具体 id；
- 多户同 hex 雇主的工资义务/订单顺序；
- 简单运力函数的容量/价格数值。

## 9. 非目标与旧文档关系

- 本文**取代**目标设计/旧经济设计中与以下冲突的部分：
  - 经济侧独立 `naturalNeeds` 双轨；
  - 每户独立 `grain_farm` mode 默认；
  - 产品份额/共享收入池；
  - LAND block/整块转移；
  - M3 先查库存/现金再挂需求；
  - 非市场 tick 只检查不重选位置（按用户“人一天能不工作”及本轮示例，实际生产位置每 tick 重选）。
- 旧文档仍作历史留痕，不回头改。
