# 池侧"未承运/截断"读数去重（`MERCHANT_CAPACITY_LANE_TRUNCATED` 净额）—— 实现账本

> 责任区：让**池侧**读数"每份未服务量只出现一次量"（只动日志读数与说明；判据/成交/价格/运力分配数值一字不动）。
> 日期：2026-10-10。上游事实 = `.superpowers/sdd/2026-10-10-g3f-verify-net-unserved/impl-ledger.md` §5-1（池侧 Σ = 单份的 3.45 倍）。
> ★ 一句话结论：池侧事件现在**同时**给"本次观察"（`unallocatedMilli`，❌ 不可求和当真实量）与"净额"
> （`unallocatedNetMilli`，✅ **Σ 可当真实量**），另给 `unallocatedObservationIndex`（重复次数读数）与
> `requestKeyTracked`；INFO 汇总另给 raw/net/行数/请求数/最高观察次数。**非日志数值：新旧字节码逐字节相同（探针实测）。**

## 1. 调查结论（file:line → 结论 → 影响）

| 证据 | 结论 |
|---|---|
| `MerchantCapacityPool.java:764`（改前）是 `MERCHANT_CAPACITY_LANE_TRUNCATED` 的**唯一**发射点；`grep -rn "carrierPool.select" src/main` = **1 处** = `MarketSettlement.java:6404`（`executeTrade`） | 池侧读数的重复只能来自"同一个 `select` 请求被调多次" |
| `MarketSettlement.java:5107`（`matchRoute` 的 `for (round < MARKET_MAX_TRANSPORT_ROUNDS)`，常量 `:157` = **4**）+ `pairUp` 逐对试配 | 同一 (买槽,卖槽) 一天内被反复试配 ⇒ 每趟一次 `executeTrade` → 一次 `select` |
| `MarketSettlement.java:1568-1571`（`orderIndex` = 槽在 `ctx.buys/sells` 里的全局下标，轮内唯一）；`LaneUnservedBook:67/89`（D-1b 的 `claimPair` 键 = laneKey + 买槽序 > 卖槽序） | **请求身份在调用方手里、池看不见** ⇒ 必须由 `executeTrade` 拼好传入（这是本次唯一新增的入参） |
| 离线重算 `/tmp/g3f-runA.log`（新 A 档，48,901 行 `MERCHANT_CAPACITY_LANE_TRUNCATED`）：Σ 逐行 = **73,169,862**；按 `(day,车道,请求量)` 去重 = **21,177,007**（= 账本 §5-1 的"唯一副本 21,177,060"，比值 **3.4552** = 账本的 3.45）；按 `(day,车道,请求量,家户)` 去重 = **24,278,096** | 账本 §5-1 的结论成立；但它那个键**偏粗** |
| 同一次离线重算：以 `(day,车道,请求量,家户)` 为请求键的观察次数分布 = `×1:990, ×2:53, ×3:3, ×4:8414, ×8:395, ×16:2, ×24:167, ×28:10, ×32:171, ×36:33`；**组内 `unallocatedMilli` 逐行相同（变化组 = 0 / 10,238）** | 主峰 **×4 就是运力窗口数**；同一请求的多次观察是**同一份量**（不是增量）⇒ 逐行相加必然放大 |
| 同一次重算：`(day,车道,请求量)` 这一个键上**挂多个请求**（1 个请求 4,011 键 / 2 个 598 / 3 个 115 / … / 6 个 427） | 只按"车道+请求量"去重会把**不同请求**并掉 = **漏记约 13%**（21.18M vs 24.28M）⇒ 键必须细到"一对买卖槽" |
| 根因 | **与槽侧同类**：同一请求（= 同一对买卖槽）被多次调用；D-1b 只在那份**判据**簿上认领，池侧读数此前无簿 |

## 2. 实现架构

| 落点 | 内容 |
|---|---|
| **新** `time/LaneUnservedObservationBook.java`（125 行，包内） | 与 D-1b `LaneUnservedBook` **同一形制**：`Map<请求键, 已记最大量>` + `observe(key, unallocated)` → `Observation(netMilli, observationIndex, requestKeyTracked)`；另有轮级计数（raw/net/行数/最高观察次数）。★ 只读出去处 = 日志；`requestKey == null` 时**不写 Map**、只动计数（`EMPTY` 单例可能被多线程碰到） |
| `time/MerchantCapacityPool.java` | ① 每池一份 `unservedObservations`（池本身一轮一份 ⇒ 作用域 = 一轮）；② 新增 **5 参** `select(..., String requestKey)`（生产路径），4/3 参委托 `null`（夹具/纯状态读者，签名不变 ⇒ 既有测试零改动）；③ `demandLeft > 0` 时**无条件**记一笔（与日志档位无关），DEBUG 行加 `unallocatedNetMilli` / `unallocatedObservationIndex` / `requestKeyTracked`；④ INFO `MERCHANT_CAPACITY_POOL` 加 `unallocated{Raw,Net}Milli` / `unallocated{Observations,Requests,MaxObservations}`；⑤ 类注 + `select`/`logRoundSummary` 说明写死口径 |
| `time/MarketSettlement.java:6404` | `executeTrade` 拼 `LaneUnservedBook.pairKey(LaneUnservedBook.laneKey(route.from, route.to, route.commodity), buy.orderIndex, sell.orderIndex)` 传入 |
| `time/LaneUnservedBook.java:103` | `pairKey` **只放宽可见性**（private → 包内），串一字未动；两边"同一份量"因此同键 |

## 3. 关键判断（为什么不用另一条路线）

1. **身份由调用方传入**：池只拿到 `(from,to,quantity,workPerGood)`，看不见槽位与商品。用池内可推的粗键 `(lane,requested)`
   会把不同请求并掉（实测漏记 13%）；用家户会把"同户同车道同量的两单"并掉；**pair 键与 D-1b 槽侧认领同键**，最细且可对照。
2. **"最大量 + 增量"而不是"只记第一次"**：与 D-1b 同形制；后续观察若更大（部分成交后余量变化）只补增量 ⇒
   净额恒 ≤ 该请求真正未服务的量（只削重复，不削事实）；实测同请求组内量恒定 ⇒ 增量恒为 0（探针第 2 行的 8,000 是"后一次才看到全量"的那一支）。
3. **无条件记账（不看日志档位）**：否则只开 `INFO` 时 DEBUG 行被抑制、观察簿空 ⇒ 汇总**假报 0**（"静默付 0"陷阱）。记账结果
   **只被两处日志参数读**（`MerchantCapacityPool:817-818` 写、`:1220-1228` 与 DEBUG 行读）。
4. **不用 `Math.addExact`**：日志读数的溢出**不得**把异常抛进结算链（纯加法）。
5. **不复用 `ctx.laneUnserved`**：那是**判据**簿（D-1b 写进 `capacityTruncatedMilli` = V-20 价格输入）；混用会把"读数去重"写进判据。
   两份簿**各自独立**是本次的结构底线。
6. **不改 `CarrierAllocation` / 不动返回值**：新字段全部走 `LogEvent.of` 参数；该方法仍是 `return new CarrierAllocation(choices, quantityMilli, demandLeft, priced)`。

## 4. 偏离记录 / 口径边界（如实记）

- "**Σ net 可当真实量**"的准确含义 = **各请求各自那份未服务量之和**（同一请求只算一次）；它**不是**"车道上物理没运走的货量"
  （不同买槽的需求可以互相重叠）。已写进类注（`LaneUnservedObservationBook` 与 `MerchantCapacityPool` 各一处）。
- 事件仍**没有** `day` 字段（池拿不到世界日；与 `MERCHANT_CAPACITY_HOUSEHOLD` 同因）⇒ 未借本次改动夹带； 轮级合计改由已有的 INFO 汇总（带 `day`）承载。
- 未做（**不属于本责任区**）：槽侧/池侧读数的**统一**（两者语义不同，本批只做池侧读数自解释）。

## 5. 验证（真实命令与结果）

```bash
# ① 格式化（唯一门禁）
MVN_LOCK_WAIT=1800 tools/mvn-lock.sh -q spotless:apply -pl simos-economy      # exit=0
# ② 编译（★ 必须 -am）
MVN_LOCK_WAIT=1800 tools/mvn-lock.sh -DskipTests compile -pl simos-economy -am # BUILD SUCCESS（201 源文件）
```

**非日志数值不变的证明（三重）**：

1. **新旧字节码行为对拍（★ 最强）**：把 HEAD 的三个文件（`MerchantCapacityPool` / `LaneUnservedBook` / `MarketSettlement`）
   用 `git show HEAD:…` 取到 `/tmp/head-src` 单独编译到 `/tmp/head-classes`，用同一支 /tmp 探针（只用 **4 参** `select`，
   故 HEAD 也有）在**同一夹具**上跑 5 个场景 18 次调用（全耗尽后再问 / 小需求 / 半径外 / 同格 / 无运力格 / 报价口径 /
   零需求 + 三条非法入参）：`diff /tmp/pool-probe/out-head.txt /tmp/pool-probe/out-new.txt` = **逐字节相同**（含 `choices`
   的家户、`quantityMilli`、`consumedWorkMilli`、`toolMilli`、`askPerMille`、`tier`、`pureMerchant`、异常消息）。
2. **新代码内：有键 ≡ 无键 ≡ 每键不同**：`PROBE-EQUALS noKey==sameKey : true`、`noKey==diffKey : true`（返回值逐值相同）。
3. **静态**：`grep -rn "unservedObservations" src/main` 的全部读取点 = DEBUG 行 + INFO 行两处日志参数；
   `LaneUnservedObservationBook` 在 `src/test`、`simos-app` 引用 **0**；`carrierPool.select` 生产调用点 **1** 处（已带键）。

**读数面实测（探针，`/tmp/pool-probe/PoolReadoutProbe.java`，同键 4 次）**：

```
raw : 92000, 100000, 100000, 100000   Σ=392000   ← ❌ 逐行相加（放大了 3.92 倍）
net : 92000,  8000,      0,      0    Σ=100000   ← ✅ 真实量（请求 100,000、承运 8,000）
index: 1, 2, 3, 4   requestKeyTracked=true
INFO MERCHANT_CAPACITY_POOL … unallocatedRawMilli=392000 unallocatedNetMilli=100000
     unallocatedObservations=4 unallocatedRequests=1 unallocatedMaxObservations=4
无身份路径：net ≡ raw、index 恒 1、requestKeyTracked=false、unallocatedRequests=0
键形状：PROBE-KEY 0_0->0_1#tool|7>9   （= D-1b claimPair 的同一拼写）
```

## 6. 未做 / 未验证（如实记）

1. **未跑** `test` / `verify` / 前端门禁 / 任何真实 world（纪律：本责任区只到编译过）。
2. **未改任何测试**；既有测试只断言 `CarrierAllocation#unallocatedMilli()`（**返回值**，本次未动），
   **无**测试断言 `MERCHANT_CAPACITY_LANE_TRUNCATED` 的日志字段（`grep` 该事件名在 `src/test` = 0 命中）⇒ 无需改测试。
3. 新读数在**真实 A 世界的数值**未实测（不能重跑世界）：离线重算给的是**代理键**（家户）估计 24,278,096；
   精确的 pair 键合计（可能略大）**未测**。
4. `EMPTY` 单例的"无身份"路径只动计数、不写 Map（`EMPTY.select` 在生产路径**结构上不可达**：`route != null` 需要
   `hasCapacityAt` 为真，而 `EMPTY` 恒 false）；未做并发压测。
5. 未改 `docs/**`、`pom.xml`、任何 `src/test/**`、`AGENTS.md`；`/tmp` 探针与 `/tmp/head-*` 是本次唯一产物（不随仓提交）。
