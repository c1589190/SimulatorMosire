# M0 两轮市场探针报告（2026-10-04）

> 对应计划：`docs/superpowers/plans/2026-10-04-market-debt-implementation-plan.md`（阶段 M0）
> 对应设计：`docs/superpowers/specs/2026-10-04-market-debt-architecture.md`
> 探针：`simos-economy/src/test/java/io/mosire/simos/economy/market/TwoRoundMarketProbeTest.java`
>
> **状态（2026-10-04 晚）**：本报告已按“α_down/立刻降价略微调小到 100‰”的裁定更新读数；
> 后续独立测试与长程结论见 `docs/superpowers/reports/2026-10-04-market-probe-longrun.md`。

## 1. 运行命令与结果

```text
./mvnw -q -Dtest=TwoRoundMarketProbeTest -Dsurefire.failIfNoSpecifiedTests=false test -pl simos-economy -am
```

结果：`Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS。

## 2. 小世界

- 1 个 hex / 1 个市场区；2 种商品：粮、布。
- 4 个家户：地主 A、农户 B、织户 C、雇农 D。
- GM 初始成交价：粮 100、布 200（最小货币单位）。
- 参数：`α_up=200‰`、`α_down=100‰`、未售立刻降价 `100‰`、`p_max=10000`、债务利息 `50‰/轮`。
- 每轮：挂单 → 买家按总财富降序 → 按最低 ask 成交（成交即记单边债务）→ 规则 A/B/C 更新 ref → 计息 → 消费 → 生产（占位：无投入）。

## 3. 两轮读数

```text
=== 市场轮 1 ===
ref before = {}
wealth order = [C-weaver, A-landlord, B-farmer, D-laborer]
trades = [GRAIN@100 x10, GRAIN@100 x10, CLOTH@200 x20, CLOTH@200 x10, CLOTH@200 x5]
sold = {CLOTH=35, GRAIN=20}
unsold = {CLOTH=20, GRAIN=0}
unmet = {GRAIN=10}
ref after = {CLOTH=192, GRAIN=106}
debts = {A-landlord=4200, B-farmer=2100, C-weaver=2100, D-laborer=1050}
creditIssued = 9000
totalMoney = 10205
stocks = {A-landlord={GRAIN=20, CLOTH=0}, B-farmer={GRAIN=10, CLOTH=0},
          C-weaver={GRAIN=0, CLOTH=40}, D-laborer={GRAIN=0, CLOTH=0}}

=== 市场轮 2 ===
ref before = {CLOTH=192, GRAIN=106}
wealth order = [C-weaver, A-landlord, B-farmer, D-laborer]
trades = [GRAIN@106 x10, CLOTH@172 x20, CLOTH@172 x10, CLOTH@172 x5]
sold = {CLOTH=35, GRAIN=10}
unsold = {CLOTH=0, GRAIN=0}
unmet = {GRAIN=25}
ref after = {CLOTH=172, GRAIN=121}
debts = {A-landlord=8022, B-farmer=4011, C-weaver=3318, D-laborer=2005}
creditIssued = 16080
totalMoney = 17285
stocks = {A-landlord={GRAIN=20, CLOTH=0}, B-farmer={GRAIN=10, CLOTH=0},
          C-weaver={GRAIN=0, CLOTH=20}, D-laborer={GRAIN=0, CLOTH=0}}
```

## 4. 结论（与设计判据对照）

| 判据 | 结果 |
|---|---|
| 缺货 → ref 上涨（规则 B） | 粮：供给 20、需求 30 → ref 100 → 106；第二轮供给 10、需求 35 → ref 106 → 121 |
| 卖不掉 → ref 下跌（规则 A） | 布：挂出 55、卖出 35、未售 20 → ref 200 → 192（未售比例约 36% × 100‰） |
| 单户过剩立刻降价 | 第二轮织户对上轮 20 单位未售布按 `192×(1−10%) = 172` 挂单，布全部成交，ref 变为 172 |
| 无成交 → ref=0（规则 C） | 本场景两轮都有成交未触发；已由 `RuleCNoTransactionProbeTest` 独立补证 |
| 买家按总财富降序、无出价 | 两轮 order 均为 `[C, A, B, D]` |
| 成交直接记单边债务 | 两轮后债务均 > 0；本两轮探针未设计还款/死亡；M0+ `ProbeEconomy` 已补还债 wave 与死亡删债，见长程报告 |
| 货币 = 初始 + 信贷池发行 | 第一轮 1205+9000=10205；第二轮 1205+15240=16445 |

## 5. 暴露出来的问题（需后续阶段处理）

1. **单户降价参数已按裁定调小**：从 `200‰` 降到 `100‰`；一轮立刻降价仍可能接近 10%，
   但用户确认恐慌性抛售不可避免，保留为 GM 参数标定项。
2. **信贷池货币创造无回收（本两轮探针）**：所有买入都靠信贷池付钱，货币供应随成交额增长；
   本探针没有还款/税收/回笼。M0+ `ProbeEconomy` 已加还债 wave；长程结论表明还需要
   “劳动→收入→还债”闭环或信用额度上限，见 `2026-10-04-market-probe-longrun.md`。
3. **规则 C 已补证**：`RuleCNoTransactionProbeTest` 覆盖“无成交 → ref=0 → 下一次由产出者重新定价”。
4. **`ref < cost` 反应已补证**：`ProductionExitOnLossProbeTest` 覆盖停产/继续生产；
   成本仍只用于生产决策，不作为卖价下限（符合“允许低于成本”）。
5. **运输/跨格已补证**：`ThreeHexTransportProbeTest` 覆盖 3 hex 成交、到货价加成与运输托管；
   脚夫阶层状态、运力容量仍留到 M4。
6. **口粮/劳动/生产已补证**：`FoodLaborProductionProbeTest` 覆盖 C1 留口粮与投入、断粮降效率、
   劳动受限产出；`LaborPriorityProbeTest` 覆盖多 recipe 劳动优先级；正式方案甲留到 M2。

## 6. 验证边界

- 本探针是**可运行的数学原型**，不是正式测试基线；
- 未接入 `EconomyData`/`ClassFirstState`/ChangeSet/Codec；
- 本两轮探针自身未做长程/多市场区/跨界货币；长程、死亡删债、还债 wave、闭环收入对照见
  `2026-10-04-market-probe-longrun.md`；
- 正式测试与变异自证按仓库纪律留到统一收尾阶段。
