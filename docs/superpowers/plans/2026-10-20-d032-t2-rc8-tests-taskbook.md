# T2 任务书：RC8 修复后的测试迁移与验收

> **状态：测试任务书；只改测试/夹具，不改生产代码。**
> 工作树：`/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`。
> 父计划：`docs/superpowers/plans/2026-10-20-d032-v4-full-repair-plan.md` §4 T2。
> 前置：RC8a/RC8b/RC8c/RC8d/RC8e 已 compile 绿；32hex 360 当前 `OK=360`。

---

## 1. 目标

把旧测试/夹具迁移到 RC8 后的真实语义，并补上本次修复的验收锚点：

```text
RC8a：服务成市 = 有 haul 牌价 + 当前有可交付 haul 货；无货走 CARRIER_FEE；交付 fault fail-closed
RC8b：当前户 actual labor / natural needs 是生产计划输入；迁移承诺按剩余人口保留
RC8c：每户每 tick 唯一 DailyDemandBill；S7 用 S4 后当前库存/实际盈余
RC8d：S7-B 0 人口 LANDED 壳可承接；动态 minLand/wealth；买盘 ≥ minLandPerPerson
RC8e：S7-C 10‰、P<100 可 0、债务高水位 hysteresis
```

---

## 2. 当前已知失败（gate full，2026-10-21 01:27）

| 测试 | 失败 | 判定 |
|---|---|---|
| `HaulServiceCommodityAcceptanceTest.noServiceGoodsMeansNoCrossHexFillAndTheReasonIsNamed` | 期望无 `LOGISTICS_CAPACITY`，实际有 2 | 需按 RC8a 新语义重写/迁移；先判夹具是否仍把有牌价当服务成市 |
| `Rc6PostLandTradeClassCheckTest.zeroMigrantsStillTransfersAllLand` | `post-land-grain-natural-need-missing` | 夹具没有 grain naturalNeeds；RC8d 要求 fail-closed，应补 Social 需求夹具 |
| `Rc6PostLandTradeClassCheckTest.multipleHexAcquisitionsTransferAllLandAndSplitPopulationByLandWeight` | 同上 | 同上 |
| `Rc6PostLandTradeClassCheckTest.noLandedTargetFailsClosed` | 先撞 grain naturalNeed 缺失，未到 no-target | 先补自然需求，再断言 `post-land-no-landed-target`；同时补 0 人口 LANDED 壳正例 |
| `Rc7HouseholdFlowBookTest.firstMarketRoundOnlyBuildsTheTableAndDebtExpansionFlowsOnceWithAssets` | 期望 100‰ 流 10，实际 1 | 改为 10‰（100 人 → 1 人）；补 0 人/阈值/hysteresis 用例 |
| `W3WageSettlementTickIntegrationTest.*` | 期望 wage debt 96，实际 196 | 判定为夹具：RC8b 后 `HouseholdEconomy.laborMilli` 是当前实际劳动；WORKER row 仍写 200,000，与 composition/roster 的 100,000 不一致。修夹具为 100,000，不改生产公式 |

> 注意：先判“夹具旧口径” vs “生产回归”。如果发现生产逻辑错误，**不要改生产代码**，在报告中 BLOCKED 并给最小复现。

---

## 3. 必做测试

### RC8a 运输

- 无 haul 库存的 hex：`haulServiceMarketHexes` 不包含该 hex；成交走 CARRIER_FEE；`HAUL_SERVICE_DELIVERY_FAULT=0`；
- 有 haul 库存：服务商品腿可交付；
- `HAUL_SERVICE_DELIVERY_FAULT>0` 必须 fail-closed。

### RC8b 实际劳动/需求

- Social 的 `householdLaborMilli` / `householdNaturalNeeds` 被 economy 投影读取；不在 economy 重算年龄/性别；
- 160 人迁 16 人：`before=84,093` → `after≈75,683`；
- source 20 人时 organizer 计划不再用 160 人历史值；
- target 180 人时 hired 可提供量随当前劳动变化，但只授予 farm 实际需求。

### RC8c DailyDemandBill

- 每户每 tick 唯一账单；
- S4 provided 不在 S7 重复挂买；
- base/extra ×5，input 不乘 5；
- SELL actualSurplus ≤ 当前库存；不卖未来产出；
- S7 商品 intent 来自账单/当前库存，不来自 S3 expected intent。

### RC8d S7-B

- 0 人口 LANDED 壳可承接 LAND；正人口优先；
- 无任何 LANDED 行时 fail-closed；
- dynamic minLandPerPerson（32hex 约 277）；
- 1,000 千分亩买盘 + minLand≈277 ⇒ `migrants > 0`；
- `maxByWealth` 财富门（正地价）与 empty/0 地价分支；
- 多 hex 最大余数守恒。

### RC8e S7-C

- `MAX_FLOW_PER_MILLE=10`；
- 100 人 → 1 人；P<100 floor=0；
- 债务增量 <1% 不触发；高水位下降不重置；触发后基线推进；
- 每市场轮最多一次；LAND 不随人走。

---

## 4. 允许修改

```text
simos-economy/src/test/java/**（仅测试/夹具）
simos-app/src/test/java/**（仅测试/夹具，若 full gate 需要）
```

禁止：

- 改任何 `src/main/java`；
- 为迁就实现改断言/放宽判据；
- 写/跑 `package`/shade（除非 gate 明确要求）；
- commit/push；主树改动。

---

## 5. 运行命令

先 economy：

```bash
cd /home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module
tools/mvn-lock.sh -q test -pl simos-economy -am
```

通过后跑：

```bash
tools/mvn-lock.sh -q test -pl simos-app -am
```

最后（若时间允许）跑 `tools/gate.sh full`；若 full gate 需要由控制方统一跑，测试 owner 至少交 economy/app 测试真实计数。

---

## 6. 报告要求

- 改了哪些测试/夹具、为什么；
- 哪些是旧期望迁移、哪些新增 RC8 验收；
- 真实命令 + rc + surefire 计数；
- 未做/未验证（例如 full gate、mutation）；
- 若发现生产回归：给最小复现和 BLOCKED，不得擅自改 main。

---

## 7. 用户原话附录

```text
2我认为是主要问题，单个家户的实际总提供劳动力、实际总口粮需求等，都是要根据据家户的人员规模（甚至根据Social的设计，还得分年龄段）来算的，这是不是没做？
```

```text
5 可以适当减小，减到1%这个数量级
```

```text
7 是之前一连串错误的必然结果，暂时不直接修
```

```text
还有其他裁定吗，没有的话把修复计划落盘，然后开始修复，直到能重跑360tick
```
