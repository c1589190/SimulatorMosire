# RC8d 任务书：S7-B 买地迁移与 S7-C 1% flow 修复

> **状态：约束设计书/实现任务书；只写生产代码到 `-DskipTests compile` 通过。**
> 工作树：`/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`。
> 父计划：`docs/superpowers/plans/2026-10-20-d032-v4-full-repair-plan.md` §4 RC8d/RC8e。
> 依赖：RC8a、RC8b 已 compile 绿。
> 目标：修掉当前 360 从 day285 起 `post-land-no-landed-target` 的 76 次拒绝，让无地买地真正能迁入人口，并把 S7-C 从 10% 降到 1% 量级。

---

## 1. S0–S8 锚点

```text
S7-A 市场撮合：LANDLESS 买方可以临时取得 LAND
S7-B PostLandTradeClassCheck：
  - 本轮 LANDLESS 买方按 hex 把 LAND 全部转给该 hex 的 LANDED 目标
  - 目标可以是 0 人口 LANDED 壳（只要行/class standing 存在）
  - 迁移人口按动态 minLandPerPerson / 财富 / 速度；不能永远 0
  - 同一 revision 清零临时 LAND
S7-C HouseholdFlowBook：
  - 同 hex LANDED → LANDLESS
  - 1% 量级；每市场轮最多一次
  - 有 hysteresis，不能任何小额债务反弹都触发
  - LAND 不随人走
```

---

## 2. 当前事实（file:line）

- `PostLandTradeClassCheck.java:54-55` 当前硬编码 `POST_LAND_MIN_LAND_PER_PERSON_MILLI_MU=2667`；
- `PostLandTradeClassCheck.java:181-189` 当前 `maxByWealth = Long.MAX_VALUE`；
- `PostLandTradeClassCheck.java:359-379` 目标过滤 `row.population() <= 0`，会直接跳过 0 人口 LANDED 壳；
- `MarketLandIntentBook.java:277-313` 无地户每次只买 `minimumLandCapacityPerUnit`（当前 1,000 千分亩）；
- `IndustrySeeder.java:67` `capacityPerUnit[LAND]=MILLI_MU_PER_MU=1000`；
- 当前 360：
  - 2,047 笔 LAND 交易全部 `quantityMilliMu=1000`；
  - 2,031 次 post-land 检查全部 `migrants=0`；
  - RC8b 后从 day285 起出现 76 次 `post-land-no-landed-target`，360 末段 REJECTED；
- `HouseholdFlowBook.java:271-273` 任何 `currentDebt > previousObserved` 即触发；
- `HouseholdFlowBook.java:346-352` 当前 `MAX_FLOW_PER_MILLE=100`（10%）。

---

## 3. 本单必须达到的行为

### 3.1 S7-B：0 人口 LANDED 壳可以作为 LAND 目标

`selectTarget` 不能再因 `row.population() <= 0` 跳过 LANDED 目标。

规则：

- 目标必须：
  - 行存在；
  - `ClassNatureBook.classNatureOf(...) == LANDED`；
  - `row.view().hex()` 等于购买 hex；
  - 当前 mode 与买方 mode 匹配；
- **优先正人口目标**；没有正人口时允许 0 人口 LANDED 壳；
- `migrants=0` 时仍必须完成 LAND 转移；
- 若目标行存在但是 0 人口，`HouseholdTransferSettlement` 仅在 `migrants>0` 时被调用；
- 若连 0 人口 LANDED 行都不存在，才继续 fail-closed（不要凭空创建 LANDED，除非本单明确实现；当前 32hex 应始终有壳）。

### 3.2 S7-B：动态 `minLandPerPerson`

`POST_LAND_MIN_LAND_PER_PERSON_MILLI_MU=2667` 不得继续作为当前世界硬编码。

实现一个单独 helper（建议放 `PostLandTradeClassCheck` 包内或 `MarketLandIntentBook` 可复用）：

```text
netGrainPerMuMilli =
    outputPerUnit[GRAIN] × 1000
    − inputPerUnit[GRAIN]

requiredGrainPerPersonPerCycleMilli =
    buyer.naturalNeeds[GRAIN] / max(1, buyerPopulation) × cycleDays

minLandPerPersonMilliMu =
    ceil(requiredGrainPerPersonPerCycleMilli × 1000 / max(1, netGrainPerMuMilli))
```

- `outputPerUnit[GRAIN]` / `inputPerUnit[GRAIN]` 从生产定义读；
- `cycleDays` 从生产定义读；
- `naturalNeeds[GRAIN]` 是 Social 当前注入的逐户日需求；
- 如果定义缺 grain/cycleDays 或分母 ≤0 ⇒ fail-closed（不得回退 2667 假装成功）；
- 该 helper 是 **S7-B 后续 `maxByLand` 和 MarketLandIntent 买地单规模的同一口径**；不要在两处各写一份。

### 3.3 S7-B：`maxByWealth`

不能再用 `Long.MAX_VALUE`。

按 v4 §5.4/派单默认：

```text
landlessWealth = money + goods × 本地市场价
entryCostPerPerson = ceil(minLandPerPersonMilliMu × landPricePerMu / 1000)
maxByWealth =
    landPrice empty ⇒ Long.MAX_VALUE
    landPrice == 0 ⇒ Long.MAX_VALUE（免费地，不被 0 除）
    else ⇒ floor(landlessWealth / max(1, entryCostPerPerson))
```

- 资金/商品读取用 buyer 当刻账户与 local market；
- 不把 LAND 价值重复算入（LANDLESS 无 LAND）；
- 价格 empty 与 0 区分；
- `migrants = min(maxByLand, maxByWealth, maxBySpeed, population-1)`，不得为负。

### 3.4 MarketLandIntentBook：无地买地单至少覆盖一个人

`collectLandlessBuyDrafts` 当前只买 `minimumLandCapacityPerUnit`（1,000）。

改为：

```text
quantity = max(
    minimumLandCapacityPerUnit,
    minLandPerPersonMilliMu(buyer)
)
```

- 该 helper 与 S7-B 使用同一 `minLandPerPerson` 计算；
- 不允许因为一次买地小于 `minLandPerPerson` 导致 S7-B 永远 0 迁移；
- 不能改成跨市场轮累计临时 LAND（v4 要求同一 revision 清零）；必须在单次买盘中足够。

### 3.5 S7-C：1% 与 hysteresis

- `HouseholdFlowBook.MAX_FLOW_PER_MILLE` 从 `100L` 改为 `10L`；
- `flowPopulation` 使用整数 floor；`P < 100` 时 0 人可接受，不引入余数累计器；
- 保留 `MIN_LANDED_POPULATION=1` 的 `sourcePopulation - 1` 上限；
- 增加 hysteresis：
  - 建议 `lastObservedDebtPrincipalMilli` 作为**触发基线/高水位**，不再每轮无条件改成当前债务；
  - 仅当 `currentDebt >= 基线 + max(1, 基线/100)` 时触发（即至少约 1% 的债务增量）；
  - 触发后把基线更新到当前债务；
  - 债务下降不触发，不能因为回落后的轻微反弹就再次流动；
  - 每市场轮最多一次仍保留；
- 具名 DEBUG/INFO 记录“为什么没有触发”（delta/threshold），不要静默。

### 3.6 不改 #7

本单不修改：

- `MarketPricer` 土地价格公式；
- `MarketLandIntentBook.collectSellDrafts` 的“全部未使用土地挂卖”语义；
- 任何 seeder 土地总量/初始地价。

地价 0 是上一连串问题的结果，本单只修 S7-B 的买盘规模和 S7-C 的流量/阈值。

---

## 4. 允许修改的文件

```text
simos-economy/src/main/java/io/mosire/simos/economy/time/PostLandTradeClassCheck.java
simos-economy/src/main/java/io/mosire/simos/economy/time/MarketLandIntentBook.java
simos-economy/src/main/java/io/mosire/simos/economy/time/HouseholdFlowBook.java
```

必要时可以抽取包内 helper 到上述文件之一；**不要**改 seeder、MarketPricer、EconomyData 形状、测试文件。  
若需要访问生产定义/自然需求，使用 `SettlementDayContext` 已有字段，不新增持久组件。

禁止：写/跑 test/test-compile/verify/package；commit/push；主树改动。

---

## 5. 验收锚点

### S7-B 正例

1. 存在正人口 LANDED 目标时，优先正人口；没有正人口但有 0 人口 LANDED 壳时，LAND 仍能转移，不抛 `post-land-no-landed-target`；
2. 无地户买 1,000 千分亩、动态 minLandPerPerson≈278 千分亩时，`migrants > 0`，当前 32hex 不再全部 0 迁移；
3. `migrants` 不超过 `maxByLand/maxByWealth/maxBySpeed/population-1`；
4. 多 hex 最大余数拆分守恒；
5. LAND 总量守恒、临时 LAND 清零、LANDLESS 不落盘持地。

### S7-C 正例

1. `MAX_FLOW_PER_MILLE=10`；
2. 90 人或以下源户，1% floor 可为 0，不强行迁 1 人；
3. 债务小幅反弹不再触发；
4. flow 事件/累计迁移显著低于 RC8b 的 1,255 次/当前累计量；
5. LAND 不随人走、source ≥1、每市场轮最多一次。

### 负例

- 0 人口 LANDED 壳被跳过 ⇒ 红；
- 购买量不足 minLandPerPerson 仍允许交易但静默 0 迁移 ⇒ 红；
- `maxByWealth=Long.MAX_VALUE` 无条件保留 ⇒ 红；
- flow 仍任何正 delta 触发 ⇒ 红；
- 把小额债务增加累积成触发但没有任何具名读数 ⇒ 红；
- 修改地价/卖单总量来掩盖 ⇒ 红。

---

## 6. 编译与交账

```bash
cd /home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module
tools/mvn-lock.sh -q spotless:apply
tools/mvn-lock.sh -q -DskipTests compile -pl simos-economy,simos-app -am
```

- 账本落 `.superpowers/sdd/2026-10-20-d032-rc8d-mobility/impl-ledger.md`；
- 报告列：动态 min land 公式、maxByWealth 读取、0 人口目标处理、买盘量、flow 1%/hysteresis、未验证项。

---

## 7. 用户原话附录

```text
5 可以适当减小，减到1%这个数量级
```

```text
7 是之前一连串错误的必然结果，暂时不直接修
```

```text
留着，后面处理，0人就0人
```

```text
还有其他裁定吗，没有的话把修复计划落盘，然后开始修复，直到能重跑360tick
```
