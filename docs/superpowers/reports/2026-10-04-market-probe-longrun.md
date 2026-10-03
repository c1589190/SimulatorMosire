# 市场探针长程报告：死亡率、信贷与食物/劳动/生产（2026-10-04）

> 对应设计：`docs/superpowers/specs/2026-10-04-market-debt-architecture.md`
> 对应计划：`docs/superpowers/plans/2026-10-04-market-debt-implementation-plan.md`（M0 探针扩展）
> 探针代码：`simos-economy/src/test/java/io/mosire/simos/economy/market/`

## 1. 本轮新增的探针与运行结果

```text
./mvnw -q \
  -Dtest='TwoRoundMarketProbeTest,RuleCNoTransactionProbeTest,ProductionExitOnLossProbeTest,ThreeHexTransportProbeTest,LongRunMortalityProbeTest,FoodLaborProductionProbeTest,LaborPriorityProbeTest,RepaymentWaveProbeTest,CircularFlowCreditProbeTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test -pl simos-economy -am
```

结果：

| 探针 | 覆盖点 | 测试数 | 结果 |
|---|---|---|---|
| `TwoRoundMarketProbeTest` | 两轮市场、规则 A/B、单边债务 | 1 | 绿 |
| `RuleCNoTransactionProbeTest` | 无成交 → `ref=0` → 下一次重新定价 | 1 | 绿 |
| `ProductionExitOnLossProbeTest` | `ref < cost` 停产；`ref > cost` 继续 | 1 | 绿 |
| `ThreeHexTransportProbeTest` | 3 hex 跨格成交、到货价含运输、运输托管 | 1 | 绿 |
| `LongRunMortalityProbeTest` | 3000 轮、饥饿死亡、死亡删债、信贷发行 | 1 | 绿 |
| `FoodLaborProductionProbeTest` | C1 留口粮/投入、断粮降效率、劳动受限产出 | 3 | 绿 |
| `LaborPriorityProbeTest` | 多 recipe 优先级、劳动预算收缩、投入受限让出劳动 | 3 | 绿 |
| `RepaymentWaveProbeTest` | 有货币的家户按 `min(钱, 债)` 还债 | 1 | 绿 |
| `CircularFlowCreditProbeTest` | 闭环收入下 3000 轮发行/还款/债务恒定（正面对照） | 1 | 绿 |

合计 `Tests run: 13, Failures: 0, Errors: 0`（market 探针包内）。

## 2. 长程场景

- 1 个 hex；地主 `L`（人口 10，不吃饭，固定产粮 100/轮）+ 雇农 `W`（人口 500，每人每轮需 10 粮，
  无生产、无收入，只能借粮）。
- 参数：`α_up=200‰`、`α_down=100‰`、未售立刻降价 `100‰`、`p_max=10000`、
  `interestPerMille=0`、`mortalityPerMille=100`、运输 `50‰`。
- 利息设为 0 是为了隔离“死亡删债”单一机制，并避免 3000 轮正利息把 `long` 撑爆；
  正利息只会让存量债务增长更快（见 §4.4）。

## 3. 关键读数

```text
[LONG] round=1    pop=510 deaths=0  newIssuance=514500 repaid=0   debtDeleted=0      debt=514500     ref=96
[LONG] round=2    pop=510 deaths=0  newIssuance=450000 repaid=100 debtDeleted=0      debt=964400     ref=87
[LONG] round=3    pop=463 deaths=47 newIssuance=24300  repaid=0   debtDeleted=92938  debt=895762     ref=93
[LONG] round=10   pop=234 deaths=23 newIssuance=31800  repaid=0   debtDeleted=55520  debt=540715     ref=369
[LONG] round=500  pop=29  deaths=0  newIssuance=1050000 repaid=0  debtDeleted=0      debt=491378227  ref=10000
[LONG] round=1000 pop=29  deaths=0  newIssuance=1050000 repaid=0  debtDeleted=0      debt=1016378227 ref=10000
[LONG] round=3000 pop=29  deaths=0  newIssuance=1050000 repaid=0  debtDeleted=0      debt=3116378227 ref=10000

[LONG] totalDeaths=481 totalDebtDeleted=10881973 totalRepaid=100 finalPopulation=29
[LONG] earlyIssuance(200轮)=186745800
[LONG] lateIssuance(200轮)=210000000
[LONG] lateIssuance/round=1050000 lateDebt=3116378227
```

## 4. 结论：信贷“流量”趋稳，“存量”不因死亡率单独趋稳

### 4.1 死亡确实删债，饥荒轮总债务出现下降

- 481 人死亡，累计删债 `10,881,973`；
- 第 3 轮总债务从上一轮 `964,400` 降到 `895,762`（新增发行 24,300 < 删债 92,938），
  证明“死亡按人口比例删债”生效。

### 4.2 新发行流量在人口稳定后收敛为常数

- 人口在 `29`（地主 10 + 健存雇农 19）停住；
- 稳定期每轮发行恰好 `1,050,000`（100 粮 × 到货价 10,500），最后 200 轮波动为 0；
- 这就是用户问题里“信贷额度趋于稳定”**成立的那一半**：发行流量不再随饥荒剧烈波动。

### 4.3 但存量债务继续线性增长，因为借款人没有收入

- 稳定期每轮新增的 `1,050,000` 全部沉淀为债务；3000 轮后总债务 `3,116,378,227` 且仍在增长；
- 还债 wave 累计只回收了 `100`——雇农开局仅有 100 货币，此后没有卖出任何东西，没有收入；
- 地主虽然货币很多，但没有债务，还债 wave 对总量不起作用；
- 人口稳定其实是**整数死亡向下取整**的探针现象：19 个雇农每轮理论死亡 <1 人，被截断为 0；
  同时粮食供给 100/轮 < 口粮需求 190/轮，健存者长期半饥饿（`W.eat=100/190`）。

### 4.4 正利息只会更差

- 本探针用 0 利息仍观察到线性增长；若按 spec 的 `interestPerMille=50`，存量债务会复利增长，
  3000 轮直接超出 `long` 范围；
- 说明在没有收入闭环、税收回笼或信用上限时，**死亡率删债不能抵消利息累积 + 结构性赤字**。

## 5. 食物 / 劳动 / 生产探针（补齐项）

`FoodLaborProductionProbeTest` 已把 M2 的“方案甲”关键点先钉住：

1. **C1 留口粮 + 生产投入**：生产者 `100 人 × 5 粮 = 500` 口粮，织布 100 单位又需 100 粮投入，
   C1=600；库存 600 时没有可售余粮，买家借不到粮；留出的 100 粮投入转化为 100 布。
2. **断粮 → 效率 → 劳动 → 产出**：100 人、`laborPerCapita=1000‰`、`laborPerUnit=1`、目标 100；
   口粮 0 ⇒ 效率保底 `300‰` ⇒ 有效劳动 30 ⇒ 产出 30。
3. **吃饱 → 满效率 → 满产出**：口粮 500 ⇒ 吃饱度 1000‰ ⇒ 产出 100。
4. **多 recipe 劳动优先级**：家户按“主 recipe → 追加顺序”分配有效劳动；
   100 劳动、两个各要 60 劳动的 recipe ⇒ 高优先级 60、低优先级 40；
   断粮使有效劳动降到 30 时，低优先级 recipe 完全分不到劳动；
   高优先级 recipe 因投入不足只用到 20 劳动时，剩余 80 劳动会让给下一个 recipe。

### 5.5 正面对照：有收入 → 还债 → 信贷稳定

`CircularFlowCreditProbeTest` 把断粮场景缺的那一环补上：

- 地主产粮 `1000/轮`、买布 `250/轮`；织户 `500 人` 产布 `250/轮`、买粮 `1000/轮`；
- 初始价（粮 100、布 400）下两边实物收支刚好平衡，两边都有销售收入；
- 3000 轮里人口稳定 `510`、无死亡；第 2 轮起每轮发行 `200,000`、每轮还债 `200,000`，
  存量债务恒定 `200,000`、家户货币恒定 `200,000`、价格稳定（粮 100、布 400）。

```text
[CIRCLE] round=1    pop=510 newIssuance=200000 repayment=0      debt=200000 money=200000 grainRef=100 clothRef=400
[CIRCLE] round=2    pop=510 newIssuance=200000 repayment=200000 debt=200000 money=200000 grainRef=100 clothRef=400
[CIRCLE] round=3000 pop=510 newIssuance=200000 repayment=200000 debt=200000 money=200000 grainRef=100 clothRef=400
```

对照结论：**“人死删债”只是缓冲；信贷存量真正稳定下来，靠的是借款人能通过劳动/销售获得收入，
让还债 wave 有钱可收。** 这正是 M2 的收入闭环要解决的问题。

`RepaymentWaveProbeTest` 同时确认：有货币的家户会在下一轮开始先按 `min(钱, 债)` 还债，
信贷池能回收货币。

## 6. 尚未补齐 / 需要用户裁定的点

1. **收入闭环（M2 核心）**：探针正面对照已证明“卖出产品获得收入 → 还债 → 信贷稳定”；
   正式运行时还必须给出各阶层/生产方式的收入路径（分成、工资、卖产品），
   尤其是佃农/雇农不直接卖产品时的工资或分成；否则断粮场景的结论依然成立。
2. **多 recipe 劳动优先级已在探针补齐**（`LaborPriorityProbeTest`：优先级分配、劳动预算收缩、
   投入受限时让出劳动）；正式 M2 仍需把这套分配搬进生产代码，并补资产容量与跨户雇工/收入。
3. **信用额度上限**：如果 M2 之前要单独压住存量信贷，需要定义“每户/每人信用额度”
   以及额度随人口、财富、阶层如何变化；否则只能靠死亡/阶层下滑删债。
4. **利息去向**：当前利息只加在债务人头上，信贷池没有把利息花出去或分配给任何人；
   正利息在封闭零增长经济里数学上不可持续，需要明确利息是否构成信贷池收入、税收或转移支付。
5. **整数人口的死亡取整**：19 人时理论死亡 <1 被截断，导致“永久半饥饿但不死亡”；
   正式运行时需要确定死亡是概率化、余数累积，还是人口用定点数表示。
