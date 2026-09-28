# E1 实施报告：旧生产方式衰退、经营者/关联家户不同后果、退出处置

> 切片：R4-E1（四项演化目标第 1 项）。基线 `dd95b8f2`；工作树起点干净。
> 只改 `src/main/java`；未写/改测试；未跑 `test`/`verify`；未 commit/push。
> 结论：**生产代码 + 编译/spotless 绿 + 受控场景 A/B/C 74 断言全过 + 0→120 8 线程回归全状态逐组件零差异 + 报告落盘**。

---

## 1. 改动文件 / 方法

| 文件 | 改了什么 |
|---|---|
| `simos-economy/time/EconomicHouseholdResolver.java`（新增） | 经营者 → 关联经济家户的**唯一解析拼写点**（①operator 家户 actor ②relation 的家庭受方 ③份额 owner 先、operator 后 ④否则 empty） |
| `simos-economy/time/SettlementIndex.java` | `build(...)` 增 `relations` 形参；新增 `assetShareIdsByUnit`、`economicHouseholdByUnit`；新增 `assetShareIdsOfUnit`、`economicHouseholdOf`；`withLabor/withDebts` 透传两张新表 |
| `simos-economy/time/OperatorSettlement.java` | `advance(...)` 改用索引解析的家户（去掉 `householdOfActor` 形参、增 `rows`）；新增只读 `canSelfProvision`、私有 `coversNextCycleInputs`；四条 E1 状态转移路径 |
| `simos-economy/time/EconomySettlement.java` | 关账调用点：传 `rows`/新索引；`settleOperatorExits` 扩成"劳动释放 → 资产处置 → 债务 → 状态/理由"的固定顺序；退出后重建一次索引供阶层写回 |
| `simos-economy/time/StressPolicy.java` | 新增 `INPUT_SHORTFALL_CYCLES_BEFORE_CANNOT_REPRODUCE=3`、`UNSOLD_CYCLES_BEFORE_CANNOT_REPRODUCE=3`、`SELF_PROVISION_GUARD_DAYS=30` |
| `simos-economy/model/OperatorCondition.java` | 新增 `withLastReason(String)`（退出处置只追加摘要，不改判据字段） |
| `simos-economy/time/HouseholdCondition.java` | 新增只读 `OptionalLong grainCoveragePerMille`；`derive(..., OptionalLong grainStockMilli)` 重载；`livelihoodOf` 把自用粮覆盖纳入"非 DESTITUTE"判据；覆盖分母复用 `EconomyVocabulary.cumulativeRationMilli(人口, 本户 cycleDays)` |
| `simos-economy/time/MarketReadout.java` | 只更新 `SettlementIndex.build(...)` 新形参（`data.relations()`） |
| `simos-app/gui/ApiViews.java` | 按格读 actor 账本的家户粮库存，传给 `HouseholdCondition.derive`；`householdConditions[]` 新增 `grainCoveragePerMille` + `grainCoverageNote` |

**没有新增 `EconomyData` 状态组件**，因此 `EconomyChangeSet` / `EconomyCodec` / 旧档缺键往返一字未动。
`HouseholdCondition` 是读时派生 record（不落盘、不进状态树），新增的 `grainCoveragePerMille` 是 `OptionalLong`，
读不到账本时给 empty（ApiViews 发 `null` + 具名 note），不填 0 冒充"断粮"。

### 1.1 解析器规则（`EconomicHouseholdResolver.resolve`）

```
① operator 本身是家户 actor（householdOfActor 反查命中）                 ⇒ HouseholdId
② relation.residualOwner（ActorRef 反查；聚合主体不在表里 ⇒ null）
   或 relation.inputSupplier 是 ToHousehold / 可反查成家户 actor 的 ToActor ⇒ HouseholdId
③ unit 名下（industry + operator）AssetShare 的 owner（先）或 operator（后）⇒ HouseholdId
④ 否则 Optional.empty()
```

解析不到的主体（在 B.3a 固定世界）：**ESTATE 主 unit、WORKSHOP 主 unit、聚合 weave 主 unit**（`HOUSEHOLD:weave@hex` 不是任何
`ClassRow` 的 actor），除非 relation 的 `inputSupplier`/`residualOwner` 或份额 owner 能反解成真实家户。
它们 `debtStress=false`（不伪造家户、不强行借债），但市场/投入压力仍可推它们缩产、停业、退出（资产/劳动照处置，债务段跳过）。

### 1.2 新阈值（唯一拼写点 = `StressPolicy`）

| 常量 | 值 | 用途 |
|---|---|---|
| `INPUT_SHORTFALL_CYCLES_BEFORE_CANNOT_REPRODUCE` | 3 | `CONTRACTING` 连续投入不足够久且不能自用 ⇒ `SUSPENDED` |
| `UNSOLD_CYCLES_BEFORE_CANNOT_REPRODUCE` | 3 | `CONTRACTING` 连续滞销够久且不能自用 ⇒ `SUSPENDED` |
| `SELF_PROVISION_GUARD_DAYS` | 30 | 用"自用覆盖下一周期投入"作维生证据时，家户至少还要有 30 天口粮（防"有种子没饭吃"） |

`canSelfProvision` 判据（只读，不改状态）：

```
家户可解析且行存在：
  粮库存 ≥ cumulativeRationMilli(人口, industry.cycleDays)                     ⇒ true
  否则：粮库存 ≥ cumulativeRationMilli(人口, SELF_PROVISION_GUARD_DAYS)
        且 Σ min(库存_c, inputPerUnit_c×capacityScale) ≥ Σ inputPerUnit_c×capacityScale ⇒ true
解析不到（ESTATE/WORKSHOP/聚合 weave）：只看 operator 账的同一覆盖判据
可用资产规模为 0 ⇒ 投入覆盖判据 false（没有"下一周期生产"可谈）
```

### 1.3 状态转移（E1 后；只列与 E1 有关的分支，其余沿用 S3 既有路径）

| 现态 | 触发 | 新态 | reason |
|---|---|---|---|
| ACTIVE/TRIALING | 滞销 ≥2 且有 OUTCOMPETED | OVERSUPPLIED | `oversupplied:...`（不变） |
| ACTIVE/TRIALING | 投入不足 ≥2 | CONTRACTING | `contracting:inputShortfallCycles=...`（不变） |
| OVERSUPPLIED | 有市场且滞销 ≥2 | CONTRACTING | `contracting:oversuppliedUnsoldCycles=...`（不变） |
| CONTRACTING | `canSelfProvision` | CONTRACTING（不变） | `self_provision:shortfallCycles=...,unsoldCycles=...,selfUsable=...` |
| CONTRACTING | `!canSelfProvision` 且（投入不足 ≥3 或 滞销 ≥3） | **SUSPENDED** | `cannot_reproduce:shortfallCycles=...,unsoldCycles=...` |
| CONTRACTING | `!canSelfProvision` 且债务压力 ≥2 | INDEBTED | `indebted:debtStressCycles=...`（不变） |
| CONTRACTING | 有成交 + 无投入不足 + 无债务压力 | ACTIVE | `recovered:cycleFilled=...`（不变） |
| INDEBTED | `canSelfProvision` | **CONTRACTING** | `recovered:self_provision` |
| INDEBTED | `!canSelfProvision` 且债务压力 ≥3 | SUSPENDED | `suspended:debtStressCycles=...`（不变） |
| INDEBTED | 有成交 + 无债务压力 + 有现金 | ACTIVE | `recovered:cycleFilled=...`（不变） |
| SUSPENDED | `reopens < MAX_REOPENS` 且无债务压力且有现金/自用库存 | ACTIVE | `reopened:...`（不变） |
| SUSPENDED | 停业 ≥3 **且 `!canSelfProvision`** | **EXITED** | `exited:suspendedCycles=3` |
| SUSPENDED | 停业 ≥3 但 `canSelfProvision` | SUSPENDED（不变） | `suspended:self_provision` |
| EXITING | — | EXITED | `exited:fromExiting`（不变） |
| EXITED/ABANDONED | — | 不再自转 | — |

### 1.4 退出处置顺序（`EconomySettlement.settleOperatorExits`）

1. **劳动释放**：删除全部 `LaborAllocation.activity == exit.unit().value()` 的行，汇总 `laborMilli`；`laborSupply` 一字不动；
2. **资产处置**：对该 unit 名下（`industry==exit.industry && operator==exit.operator`）每条 `AssetShare`：
   - `owner != operator` ⇒ **只把 `operator` 改回 `owner`**（`id/owner/quantity/kind` 不变）⇒ 份额空闲可再出租；
   - `owner == operator` ⇒ 原样留在 owner 名下（unit 已 EXITED、计划系数 0，不再使用它）；
3. **债务**：只对解析出的 `exit.household()` 执行（null 跳过）：逐条 `paid = min(本金, 可用余额)`，经唯一写口
   `applyTransfer` 铸 `LOAN_REPAYMENT`；不足部分 `defaulted=true`，不删表、不核销；
4. **库存/货币**：剩余留在原主体账，不没收、不蒸发；
5. **状态与理由**：状态机已置 `EXITED`；这里只把摘要追加进 `lastReason`：
   `disposition{laborReleasedMilli,releasedAllocations,assetSharesReturned,assetQuantityReturned,assetSharesKeptOwned,debtPaidMilli,debtDefaultedMilli,debtDefaultedItems,keptGoodsMilli,keptMoneyMilli}`。

退出处置后 `EconomySettlement` 重建一次 `SettlementIndex`（O(unit+份额+配额) 的一次性成本，只发生在退出日），
后面的阶层写回不再拿旧份额快照。日结算的常规路径仍是入口一次建索引，没有引入 `O(units×allocations)` 或
`O(units×shares)` 的逐查询扫描。

---

## 2. 编译 / spotless 原文

```text
$ tools/mvn-lock.sh -DskipTests compile
...
[INFO] EconomySimos ....................................... SUCCESS [  0.013 s]
[INFO] SimosApp ........................................... SUCCESS [  0.033 s]
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  0.858 s
[INFO] Finished at: 2026-09-29T05:25:47+08:00
```

```text
$ tools/mvn-lock.sh spotless:check
...
[INFO] EconomySimos ....................................... SUCCESS [  0.006 s]
[INFO] SimosApp ........................................... SUCCESS [  0.008 s]
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  0.381 s
[INFO] Finished at: 2026-09-29T05:25:48+08:00
```

`spotless:apply` 也跑过且 rc=0。最终打包（跑 MCP 回归用）：

```text
$ tools/mvn-lock.sh -q -Dmaven.test.skip=true package
[frontend-gate] OK tests=297 pass=297 fail=0
shaded jar md5 = 44e0811aa9319fecbec1a4f14096776b
```

> ★ 说明：不带 `maven.test.skip` 的 `package` 会在 **test-compile** 阶段被既有的 S1 前旧测试卡住
> （如 `simos-economy-api/.../LaborTypesTest.java` 仍按旧的 6 参 `LaborAllocation` 构造），这是 V 阶段统一测试适配的既有账，
> 不是本片引入；本片生产代码的 `-DskipTests compile` 全绿。按任务书未跑 `test`/`verify`。

---

## 3. 受控探针：场景 A / B / C

探针：`/tmp/E1Probe.java`（package `io.mosire.simos.economy.time`，与生产同类，因此可直接调用包内
`SettlementIndex.build` / `OperatorSettlement.advance`；`settleOperatorExits` 是 private，按生产唯一调用点签名经反射调用）。
夹具：`cycleDays=120` 的 farm 模板、`LAND capacityPerUnit=1000`；家户平铺在 `(0,0)`；unit A 的
tenant 家户 100 人（周期口粮 = `cumulativeRationMilli(100,120)=1,000,000` 毫粮），relation `inputSupplier=ToHousehold(tenant)`、
`laborSource=TENANT`；unit A 名下两条份额（TENANCY 600：owner=landlord / operator=tenant；OWNED 400：owner=operator=tenant），
三条劳动配额（A1+A2=3,000 给 unit A；A3=3,000 是 tenant 家户卖给 unit B 的劳动），unit B（owner=同一 landlord，
operator=second 家户）用于场景 B/C 的"不受影响"对照。

### 场景 A（自用维生不退出）

驱动：每个关账周期注入"有挂单 1,000、成交 0、有 OUTCOMPETED 证据 1 家"的市场侧周期证据 + `inputShortfall=true`，
连续 8 个关账周期；tenant 家户粮库存 2,000,000 毫（= 2 倍周期口粮）。

读数（`/tmp/e1-probe-final.out`）：

```text
A.lastStatus=CONTRACTING
reason=self_provision:shortfallCycles=8,unsoldCycles=8,selfUsable=8000
householdStatus=TENANT laborSold=3000 laborSelf=3000 unmetGrain=0 coverage=1000
```

断言（全部 PASS）：状态路径 `ACTIVE→ACTIVE→OVERSUPPLIED→CONTRACTING×5`；**没有一次** `INDEBTED/SUSPENDED/EXITED`；
最终 reason 以 `self_provision:` 开头；家户 `HouseholdCondition` 非 `DESTITUTE`、`unmetNeedMilliGrain=0`、
`grainCoveragePerMille=1000`（库存 2,000,000 ÷ 周期口粮 1,000,000 封顶 1000）、`laborSoldMilli=3000`、
`laborSelfMilli=3000`。

### 场景 B（无法再生产 → SUSPENDED → EXITED + 事实处置）

驱动：同 A，但粮库存 123 毫、银 200 毫、一笔货币债（tenant→creditor，principal 500、利率 0）；
连续关账推进直到 `advance` 产出 `Exit`，再对同一次调用点签名执行退出处置。

推进序列：`ACTIVE → OVERSUPPLIED → CONTRACTING → SUSPENDED → SUSPENDED → SUSPENDED → EXITED`（第 7 个关账周期退出）。

处置读数与状态 diff：

```text
B.diff allocations=removed=2,added=0,sharesChanged=1,debtsChanged=1,goodsChanged=0,moneyChanged=2
B.lastReason=exited:suspendedCycles=3;disposition{laborReleasedMilli=3000,releasedAllocations=2,
  assetSharesReturned=1,assetQuantityReturned=600,assetSharesKeptOwned=1,
  debtPaidMilli=200,debtDefaultedMilli=300,debtDefaultedItems=1,keptGoodsMilli=123,keptMoneyMilli=0}
householdAfterExit=TENANT coverage=0
```

逐条断言（全部 PASS）：

- **劳动**：unit A 的 A1/A2 两条配额删除、释放量 3,000；unit B 的配额与 tenant 卖给 unit B 的 A3 配额原样保留；
  `laborSupply` 的 `grossLaborMilli` 不变（1,000,000）。
- **资产**：TENANCY 600 的 `operator` 由 tenant 改回 landlord，`owner=landlord`、`quantity=600`、`kind=TENANCY` 不变；
  OWNED 400 仍 `owner=operator=tenant`、数量不变；unit B 的份额逐字段不变；全单位**份额总量**处置前后相等（1000→1000；
  本夹具只有一个 `(industry,asset)` 键）。
- **债务**：原 principal 500 = 已还 200 + 剩余 300；剩余那条 `defaulted=true`；creditor 银由 0 → 200（经唯一写口）；
  tenant 银 200 → 0。principal 逐笔守恒。
- **库存/货币去向**：tenant 的 123 毫粮留在原主体账（`goodsChanged=0`）；除债务转移外货币无其它变动（`moneyChanged=2`
  的两条正是 debtor/creditor 两侧）。
- **人口**：`ClassRow` 表处置前后逐值相等（`rows.equals(rowsBefore)`）。
- **无"只改 status"残留**：状态 diff 明确列出被改的 2 条配额、1 条份额、1 笔债务、2 本货币账；没有"status 变了、事实没动"的组合。
- **关联家户后果**：退出后 `HouseholdCondition.derive` 仍为 `TENANT`（因 tenant 另有 A3 劳动卖给 unit B）、
  A 的 OWNED 小块地仍在 tenant 名下，不判 `DESTITUTE`；grainCoverage=0 只是粮不足，不是"无资产无劳动"。

### 场景 C（同一 owner 两个 unit：一个退出、一个存续）

驱动：unit A 与 unit B 的份额 owner 同为 landlord；只驱动 unit A 到退出并处置。

读数：

```text
C.reasonA=exited:suspendedCycles=3;disposition{...,debtPaidMilli=0,debtDefaultedMilli=0,...,keptMoneyMilli=0}
bShareOperator=HOUSEHOLD:hh-0_0-rural-rich_peasant
bAllocLabor=500
bDebtPrincipal=700
```

断言（全部 PASS）：unit A 的份额按规则回 owner；unit B 的份额逐字段不变；unit B 的劳动配额 `laborMilli=500` 不变；
unit B 家户的债务 principal 仍 700、`defaulted=false` 不变；unit B 家户货币账不变；unit B 的 `OperatorCondition`
仍是 `ACTIVE`（清点 `closingUnits` 只含 A）；unit A 为 `EXITED`。

### 确定性

同一受控初态（场景 B 夹具）重放两次，`shares/allocations/debts/conditions/householdGoods/householdMoney` 六张表逐值相等；
74 条断言总计 `E1PROBE PASS pass=74 fail=0`。任务书允许的"或同一状态重放两次"由此覆盖；未单独跑受控场景的 1 vs 8 线程
（受控探针是单线程直接调用；8 线程证据见下一节 0→120 回归）。

---

## 4. 0→120 回归（B.3a 固定世界，8 线程）与守恒

- 初态：`/tmp/b3a-perf-120base-store`（rev1–4，tick0）。已核对其 rev1–4 的 changeset JSON 与 B.4 8 线程回归所用
  `/tmp/b4-8-store` 的 rev1–4 **逐字节相同**（SHA-256 相同）。
- 运行：复制到 `/tmp/e1-store-final2`，`tools/run-shaded.sh` 起服务（`--economy-threads 8`），
  `simos.advance {from:0,to:120}` ⇒ `committed`（revision 5）。
- **墙钟 34.183 s**（B.3a-perf 基线 ~36 s；未回退）。峰值 RSS 未正式测（V 阶段协议）。
- 守恒/聚合读数：`/tmp/e1-state-final2-tick120.json` 与 B.3a-perf tick120 基准
  `/tmp/b3a-state2-tick120.json` **逐值全等**（`diff` 无输出，落盘 `/tmp/e1-tick120-aggregate-diff.txt`）：
  - 人口 11,622,725（= 11,830,000 + 出生 99,472 − 死亡 306,747）；
  - `alloc_total=5,559,098,523`、`max(Σalloc−available)=−1260`（无超发）；
  - `asset_share_total=2,286,937,777`、8,940 条份额、0 负值；货币 142,924,800 毫银；无负库存/负货币；
  - unit/relation/condition = 8,940/8,940/8,940，关系 operator 不一致 0。
- **全状态逐组件 diff**：以 `/tmp/b4-8-store`（B.4 8 线程 0→120）为对照，对本轮最终 store 的**全部模块/组件**
  做规范重放比较 ⇒ `changed_components=NONE`（落盘 `/tmp/e1-full-state-diff.txt`）。
  即：E1 在真实固定世界 0→120 上没有改变任何既有数值行为。
- 该轮 8,940 个 `operatorConditions` 结束时全为 `ACTIVE`（只有一个关账周期，没有自然发生的退出）——
  这是"E1 没有顺手改变自然曲线"的证据，也说明退出路径只由受控探针覆盖（见第 6 节）。

### 既有回归探针

| 探针 | 结果 |
|---|---|
| `Probe3`（旧档 changeset 重放 / 严格往返 / 兼容位） | `PASS=60 FAIL=0` |
| `Probe5`（旧 useRights gap / 旧 Remove 键实测） | `PASS=11 FAIL=0` |
| `Probe2`（world changeset 严格读入） | `/tmp/world-rev2.json`、`/tmp/world-rev4.json` 两条均 `OK`（`/tmp/e1-probe2.out`） |
| `ProbeB4`（分类纯派生回归） | `classificationMismatches=0`、`simulatedWritebackNonViewDiffs=0`、`fingerprintUnchanged=true`；分布与 B.4 报告一致 |
| 真实读口 | 以 tick120 store 起服务调 `simos.economy.hex q=-56 r=-65`：`householdConditions[]` 正常返回并含 `grainCoveragePerMille`（样例 341）与 note；无异常 |

---

## 5. 与计划不同处 / 设计取舍

1. **没有新增第 15 个 `livelihoods` 状态组件**（计划 §E1 原案曾有）：按本任务书 D 执行"家户后果保持读时派生"，
   `EconomyData/ChangeSet/Codec` 一字未动。
2. **`HouseholdCondition` record 新增一个只读组件 `OptionalLong grainCoveragePerMille`**（不落盘、不进 codec、不进
   `EconomyData`），并保留 3 参 `derive` 重载（无账本时给 empty）。这是本片唯一"字段形状"变化。
3. **解析器第 ② 步的类型修正**：`ProductionRelation.residualOwner` 是 `ActorRef`（不是 `Recipient`），所以它走
   `householdOfActor` 反查；`inputSupplier` 是 `Recipient`，`ToHousehold` 直取、`ToActor` 也走同一张反查表
   （把"显式写给家户 actor"的旧数据一并归一）。`ToCohort` 不解析（不猜视图）。
4. **`SELF_PROVISION_GUARD_DAYS=30` 的落点**：用于"以自用投入覆盖代替整周期口粮"那一支——家户必须同时至少有
   30 天口粮；否则"有种子没饭吃"会被误判成可维生。投入/滞销的"无法再生产"阈值取 3（进入 CONTRACTING 是 2，再观察一周期）。
5. **`coversNextCycleInputs` 在 capacityScale=0 时返回 false**：没有可用资产就没有"下一周期生产"可谈，
   不能让"投入需求为 0"被读成"已覆盖"。
6. **退出处置对"说不出去处"（location==null）的债务处理**：旧实现是整条 exit `continue`；现在劳动/资产照处置、
   债务段跳过（无法铸转移就不铸），不影响家户债表；真实档 unit 的 industry id 都带格键，走不到该分支。
7. **退出处置后重建一次索引**：只发生在退出日（低频），避免阶层写回读到处置前的旧份额；常规日仍是入口一次建索引，
   没有恢复 `O(units×shares)` 或 `O(units×allocations)` 扫描。
8. **未实现 `economy.CloseProductionUnit` 命令**：B.3b 台账把它"并入 E1"，但本任务书 A–D 没有要求命令面，
   且硬约束说不要顺手扩切片 ⇒ 退出只能由状态机自然触发（GM 想显式停业目前没有生产入口）。这条如实留作缺口。
9. `OperatorSettlement.advance` 去掉了 `householdOfActor` 形参（唯一解析改走 `SettlementIndex.economicHouseholdOf`），
   避免"同一事实两处解析"；`EconomySettlement` 的调用点同步更新。

---

## 6. 我没做 / 没验证的

- **自然发生的退出**：0→120 只有 1 个关账周期，8,940 条 condition 全停在 `ACTIVE`；没有观察到自然退出/破产。
  退出、处置、家户后果全部由受控探针验证（任务书允许并优先要求受控场景）。
- **受控场景的 1 vs 8 线程**：未跑；用的是任务书允许的"同一状态重放两次"（六张表逐值一致）+ 0→120 的 8 线程。
- **全年（360 天 / 多周期）**、真实规模自然衰退、峰值 RSS / JFR 未测（留给 V）。
- **真实 c1 旧档 / 旧 `useRights` 档案**：未走完整 command/advance 路径（既有 R3 前 `useRights` 严格绑定 gap，
  Probe5 已如实标出）。
- **GOV 租赋、E2 需求/预设、E3 经验、E4 梯度消费**：未做（本片边界）。
- **`economy.CloseProductionUnit` 命令**：未实现（见 §5.8）；显式停业/GM 直接触发退出的路径当前不存在。
- **JUnit / `test` / `verify` / SpotBugs / 前端以外的门禁**：按任务书未跑；`package` 还用 `-Dmaven.test.skip=true`
  绕过了既有的旧测试 test-compile 失败（不是本片引入）。

### 证据文件

- 生产 diff：`git diff`（8 个改动文件）+ `simos-economy/time/EconomicHouseholdResolver.java`（新增）
- 探针：`/tmp/E1Probe.java`；输出 `/tmp/e1-probe-final.out`
- 编译/spotless：`/tmp/e1-final-compile.log`、`/tmp/e1-final-spotless.log`、`/tmp/e1-final-package.log`
- 回归：`/tmp/e1-advance-final2.json`、`/tmp/e1-state-final2-tick120.json`、
  `/tmp/e1-tick120-aggregate-diff.txt`、`/tmp/e1-full-state-diff.txt`、`/tmp/e1-probe2.out`
- 分类回归：`/tmp/e1-b4-classify-final.json`
