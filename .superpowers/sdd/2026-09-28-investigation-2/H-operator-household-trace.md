# H：订单减少如何传递到破产与生存？——一个织布经营者的 120 天只读追踪

> **性质**：只读调查（用户调查单第 4 题）。**未改任何 `src/**` / `docs/**` / 台账**，未 `git add/commit`，未跑 Maven，未跑完整一年。
> **代码态**：本工作树 `ts/m1` @ `a7fbfe46`；生产代码 = `6fb4493c`（`git diff --stat 6fb4493c..HEAD` 只有 docs/SDD 文件；本报告的 `文件:行` 按当前工作树）。
> **数据**：`.superpowers/sdd/2026-09-27-m2-general-market/store-m2`（tick 360 = rev11，服务已停）的只读副本 `/tmp/hstore`；读数 dump `m2t360.json`。
> **装置**：`/tmp/hprobe/io/mosire/simos/economy/time/HProbe.java`（同包探针，`javac` 对既有 `simos-*/target/classes` + shaded jar 编译；**未跑 Maven**），逐日 `EconomyDayStepper.step(day)` 跑 361..480（120 天），并调用生产同一条社会侧月度回写。
> **纪律**：全文严格分 **【代码事实】/【推断】/【用户目标】**；每条给 **结论 / 关键代码位置 / 实际读写路径 / 探针或状态证据 / 未验证**。**没有证据的一律写“不可判定”**，不把“没观测到”写成“没发生”。

---

## 0. 结论速览（先给判断，依据在 §2–§4）

**一句话**：在现有代码里，订单减少**不会**通过债务/固定货币成本把经营者推向“破产”（没有这个状态）；它只把经营者的货币收入压到极小、让布库存不断堆积；而关联农村家户的生存取决于**粮库存与实物分成**，与布订单几乎无关。真正可见的债务恶化发生在**城镇债务人**一侧（只借不还、利息并入本金），但本周期没有传导到织布经营者。

1. **选定的对象**：区域 `c--34_-65` 在 tick360 dump 里是**布未成交金额最大的区**（布供给 297,582,293 毫，最近一轮成交 **0**，卖方未成交 `no_budget` **297,603,582 毫 / 40 笔**，有效需求 0），经营者选该区锚格 `-34_-65` 的 `HOUSEHOLD:weave@-34_-65`。
2. **它在 tick360 持布 23,815,333 毫、纤维 577,571 毫、银 2 毫**；初始配额 343,667 千分劳动/日（2 条 allocation），`progressDays=0`、`capacity={TOOL:643}`。
3. **120 天（361–480）后它没有破产、没有停产、没有退出**：布增到 **30,002,409 毫**、纤维 475,682 毫、银仍 2 毫；`progressDays` 在 day480 关账后回到 0，产业继续存在。
4. **订单（销售）只影响极小一笔货币回流**：48 个市场日里它共卖出布 **92,342 毫（69 笔，收款 503 毫银）**，用这笔钱买回纤维 **475,682 毫（71 笔，付款 503 毫银）**；银余额全程在 **2–22 毫**之间，没有积累、也没有透支。
5. **经营者不能借债——这是关键阻断点**：`Debt.debtor/creditor` 的类型是 `CohortKey`（家户键），`lendDeficits` 的 `rows` / `deficitToday` 全部按 `CohortKey` 建键；`ActorRef` 类型的经营者**在类型上无法成为债务人/债权人**。探针初始还实测 `HouseholdActors.cohortOf(weave operator)` 抛异常、`operatorInHouseholdOfActor=false`。
6. **“破产/退出/关闭”在代码里没有状态表示**：`Industry` 只有 `capacity/progressDays/cycleInputUsedMilli` 等；没有 `active/closed` 字段；唯一 economy 写命令是 `economy.Seed` 且对已占用格整份拒绝；`EconomyData.withIndustries` 在 main 无调用点。⇒ 对经营者只能说“**今天没有可观测的破产态/没有破产通道**”，这**不等于**“在任何将来的制度/参数下都不会破产”（本报告不对未实现的制度做推断）。
7. **经营者的生产成本**（cycle 361–480 实测）：纤维投入 **10,254,338 毫**（其中 577,571 来自自己的经营者账，**9,676,767 来自 relation 指名/代理的家户账**）；实物劳动分成 **3,614,582 毫布**（从新产出里付）；生产损耗 **306,000 毫布**（gross 30‰）；**没有货币工资档、没有地租、没有现金维护费**（household 制度的 relation 只有 4 条 `OUTPUT_SHARE`）。
8. **工资/实物分成继续支付**：day480 关账后 4 条规则 `due=paid`、`owed=0`（R6 上限没有咬合）；operator 自己保留 **6,279,418 毫布**（net 9,894,000 − 实付 3,614,582）。名义 700‰ 只折出 net 的 36.5%，原因是 `LABOR_AMOUNT` 的分母 = **本格 8 行家户**的劳动之和，而受方只有 4 行农村 cohort。
9. **关联家户（4 个农村 cohort）生存没有受订单减少影响**：全周期 `unmetNeed={}`、`newBorrowing=repaid=interestDue=0`；粮食/布/银余额都充裕（如贫农粮 65.34M→65.94M、布 3.17M→3.09M）。**但该格农村地主是 4 个城镇 cohort 的债权人**：120 天内新借出 55,164,004 毫粮，**没有一笔偿还**，day480 计息把本金并入；债权本金（tick360 只有 c3）**52,041,553 → 109,330,627 毫**（day480 含 c3+c4 全部条）；所有 `Debt.defaulted` 仍为 `false`。
10. **社会侧月度回写确实发生了**（390/420/450/480）：全球出生 232,364、死亡 161,560；本格出生 407、死亡 308；4 个农村 cohort 合计净 +144 人。`applyFamine` 默认致死率 0‰ ⇒ 本轨迹的死亡全部来自 `PopulationDynamics` 月度结算，**不是饿死**（这些行 `unmetNeed` 全空）。
11. **证据链断点**：持久 store 只存 revision 边界的**存量**（含经营者 `GoodsAccount` 余额——rev11 里逐值可读）；`ProductionLedger`（当天投入/转移/逐规则 due/paid）、`MarketReport`（逐笔买卖双方）、逐日 `FlowRow` 中间值、会话副本**都不落盘**，只能在进程内探针里看到；已结束进程的历史 day-level 流水**今天完全不可观测**。

---

## 1. 对象选择与探针装置

### 1.1 为什么选 `weave@-34_-65`

**【代码事实 / 状态证据】** `m2t360.json` 是 dump 时刻的读数；`marketReadout.match` 来自进程内最近一轮 `MarketReport`（不落盘，`MarketReport` 类注自述；见 §4）。按全局 `regionId` 去重后，201 个区里按布“卖方未成交量”排名前列：

| 区 | anchor | 成员格 | 布供给（毫） | 最近一轮布成交 | 卖方未成交（毫/笔） | 布有效需求 |
|---|---|---:|---:|---:|---:|---:|
| **c--34_-65** | **-34_-65** | **7** | **297,582,293** | **0** | **297,603,582 / 40（no_budget）** | **0** |
| c--39_-71 | -39_-71 | 3 | 288,855,296 | 53,830 | 288,706,475 / 16 | 1,392 |
| c--29_-71 | -29_-71 | 7 | 271,067,952 | 174 | 271,104,871 / 36 | 2,400 |

选 `c--34_-65` 的锚格 -34_-65（该区 7 格中人口最大、有城镇消费者；其 `weave` operator 即 `HOUSEHOLD:weave@-34_-65`）。这不是“布大量未成交的唯一区”，但它是 dump 可复算口径下的**最大…未成交区**。

### 1.2 起点状态（tick360 rev11）

**【状态证据】**（`m2t360.json` 类+产业；actor 侧用 store rev11 changeset 逐值复核，见 §4.1）：

| 项 | 值 |
|---|---|
| operator | `HOUSEHOLD:weave@-34_-65`（`ActorRef(kind=HOUSEHOLD,id=weave@-34_-65)`） |
| 经营者商品账 | `cloth=23,815,333`、`fiber=577,571` |
| 经营者货币账 | `silver=2` |
| 产业 | `weave@-34_-65`，`regime=household`，`cycleDays=120`，`progressDays=0`，`capacity={TOOL:643}`，`capacityPerUnit={TOOL:1}`，`inputPerUnit={fiber:30000}`，`outputPerUnit={cloth:30}`，`laborPerUnit=1000` |
| relation | `operator` / `inputSupplier` / `residualOwner` 全是 `ToActor(HOUSEHOLD:weave@-34_-65)`；`rules`=4 条 `OUTPUT_SHARE × NET_AFTER_INPUTS × LABOR_AMOUNT 700‰ cloth`，受方=该格 rural 四阶层（poor/middle/rich/landlord），priority 10 |
| 家户侧关联 | `EconomySettlement.householdKeysOf` 返回 **4 个 rural cohort**：`-34_-65|rural|poor_peasant / middle_peasant / rich_peasant / landlord` |
| 配额 | 2 条 `LaborAllocation` 给 weave，来自 `rural:-34_-65:MALE:1` 与 `rural:-34_-65:FEMALE:1`，合计 **343,667 千分劳动/日** |

### 1.3 探针怎么跑、跑到了哪一步

**【代码事实】** 同包探针 `/tmp/hprobe/io/mosire/simos/economy/time/HProbe.java`：

```bash
# 编译（不跑 Maven）
CP="$(ls -d /home/cna/SimulatorMosire/simos-*/target/classes | tr '\n' ':')/home/cna/SimulatorMosire/simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar"
javac -proc:none -nowarn -cp "$CP" -d /tmp/hprobe-classes /tmp/hprobe/io/mosire/simos/economy/time/HProbe.java
# 运行（只读副本；原 store 不动）
java -Xmx6g -cp "/tmp/hprobe-classes:$CP" io.mosire.simos.economy.time.HProbe \
  /tmp/hstore 360 480 HOUSEHOLD:weave@-34_-65 weave@-34_-65 /tmp/hprobe-run-H.jsonl true
```

- 只读 replay `store-m2` 的 tick360（rev11），随后逐日 `EconomyDayStepper.step(day)` 跑 **361..480**（一个完整 120 天生产周期：day361 现扣投入、day480 收获关账）。
- **社会侧按月回写**：通过反射调用 `PopulationEconomyTimeParticipant` 的私有 `applyDailyStress` / `unmetOf`（生产协调器同一条路径，见 `PopulationEconomyTimeParticipant.java:203-249`），每到 `day % 30 == 0` 调 `PopulationDynamics.monthly` 并 `stepper.applyPopulationChange`。所以本轨迹**包含**月度出生/死亡对阶层行与劳动配额的缩放。
- 每天抓：经营者 goods/money（`stepper.operatorGoods()/operatorMoney()`）、关联 cohort 的 goods/money、`stepper.lastMarketReport()` 中该经营者/该商品的成交与未成交、当天 `ProductionLedger` 的 transfers（reason/from/to/goods/money）与 `ruleSettlements`、`Industry` 的 `progressDays/cycleInputUsedMilli/cycleLaborMilli`、`Debt` 表、`FlowRow`。
- **墙钟 398.4 s**（探针 stderr 的 day480 行；无 JFR）。输出 `/tmp/hprobe-run-H.jsonl`（122 行 = initial + 120 天 + final-summary）。
- **未做**：没有逐日 `OwnershipBooks.fold/apply`（只在最后做了一次 `land*` 绝对值落点演示）；没有把 120 天写回任何 store/revision；没有跑第二个经营者。
- **未跑 Maven、未改任何仓内文件**；原 `store-m2/simos.db` mtime 仍是 `01:49`，`git status --porcelain` 干净（写本文件前）。

---

## 2. A. 经营者侧（逐条）

### A1. 投入从谁来：`ProductionRelation.inputSupplier` 与 `drawCycleInputs` 实扣路径

**结论（【代码事实】+【状态证据】）**
- relation 的 `inputSupplier` 缺省落成 `ToActor(operator)`（`ProductionRelation.java:86-96`，缺省逻辑在构造器；`RegimeRelations.defaultInputSupplier:351`；household 档模板 `householdRules:442-451`）。
- 周期第一天（day361）实扣：先用 operator **自己的经营者账** 577,571 毫纤维；不够的部分由 **relation 指名的主体** 补——但 operator 是聚合主体（`ActorRef`，不在 `householdOfActor` 里），于是代理人 = `householdKeysOf(weave)` 的 4 个农村家户账；实际从贫农账上扣走 9,676,767 毫纤维。二者合计进 `ledger.inputs[weave][fiber]` = **10,254,338 毫**。
- 同一格还有 craft 在抢纤维：craft 自己账出 446,658（baseline C 的 tick360 ownership 副本；本探针未打印 craft 经营者账）、从同格家户账出 7,326,792（合计 7,773,450）；两业合计从贫农账扣走 **17,003,559 毫**（= 该行 day361 的 `FlowRow.consumed[fiber]`）。

**关键代码位置**
- `ProductionRelation.inputSupplier`：`simos-economy-api/.../relation/ProductionRelation.java:89`；缺省 = operator 的守卫在同文件构造器。
- `RegimeRelations.defaultInputSupplier:351`、`householdRules:442-451`、`HOUSEHOLD_LABOR_SHARE_PER_MILLE:185`。
- `EconomySettlement.drawCycleInputs:1507`；三遍式：`surveyInputDemands:1631` → `rationContestedInputs:1717` → `drawOneIndustryInputs:1820`。
- 供方解析：`supplierAccountsOf:2007`（ToActor 命中 `householdOfActor` ⇒ 该本账；否则聚合主体 ⇒ `householdKeysOf` 代理）。
- 规模：`usableScaleOf:1793`（`min(产能路, 可供量/inputPerUnit)`）；`capacityScaleOf:2208`。
- 落账：`recordInputDraw:2127`（家户供方：`ledger.addInput` + 扣账 + 记 `consumed`；当 `operatorKey=null` 时走“不产生转移”的第二支）；`recordOperatorInputDraw:2179`（经营者自己账：只 `ledger.addInput` + 扣 `operatorGoods`，不记 `consumed`）；`apportionToHouseholds:2069`。

**实际读写路径（day361，按发生次序）**
1. `operatorGoods[weave@-34_-65][fiber]` 577,571 → 0（`recordOperatorInputDraw`，无转移）。
2. relation 名下 4 个农村家户账按人口/最大余数法分派；贫农 `householdGoods[-34_-65|rural|poor_peasant][fiber]` 18,027,788 → 1,024,229；`FlowRow.consumed[fiber]` 记在贫农行。
3. `Industry.cycleInputUsedMilli[fiber]` 累计 10,254,338；`reallocateLabor:2840` 随后把 weave 配额从 343,667 调到 **341,000/日**（= usableScale 341 × laborPerUnit 1000）。
4. 同格 craft（另一个需求方）走它自己的 relation：自己账 446,658 先出，再从 8 行家户账（含农村贫农）取 7,326,792。**争用池**由 `rationContestedInputs:1717` 按需求比例配给。

**探针证据**
- `/tmp/hprobe-run-H.jsonl` day361：`ledger.inputsWeave={fiber:10,254,338}`；`operatorGoods` 从 `{cloth,fiber:577571}` 变 `{cloth}`；贫农 goods fiber `18,027,788→1,024,229`、`flow.consumed.fiber=17,003,559`；同格 `inputsAtWeaveHex` = `weave:10,254,338` + `craft:7,773,450`；`allocations.laborMilliSum` 从初始 343,667 变 341,000。
- 交叉验算：`10,254,338 − 577,571 = 9,676,767`（weave 从家户取）；`17,003,559 − 9,676,767 = 7,326,792`（craft 从家户取），与 craft ledger 合计 7,773,450 相差正好 446,658（craft 自己的账）。

**未验证 / 边界**
- `ledger.inputs` 只按“产业 × 商品”记总量，**不记供方身份**；上面的 weave/craft 拆分是“账户余额变化 + ledger 总量”的算术恒等式，不是逐笔 supplier 标签。我们没有在 `drawOneIndustryInputs` 内部插桩逐笔 supplier。
- 若供方村户账不存在，代码有 fail-closed（`supplierAccountsOf` 抛）；本轨迹未触发。

### A2. 产品归谁：`rules` / `residualOwner` / `yield`

**结论（【代码事实】+【状态证据】）**
- 收获日（day480）由 `harvest:3135` 按 `scaleOf:3753` 算规模，gross → 扣 30‰ 损耗 → **净产一整笔记到 operator 账**（`ledger.addOutputAccrual` + `creditOutput`）。
- 然后 `ProductionSettlement.settle:332` 按 relation 规则逐条把实付从 operator 转给受方；**剩下的净产不产生任何条目**，留在 operator（`residualOwner` 只表达归属，不生成转移）。
- 本 relation = houseold 档：4 条 `OUTPUT_SHARE`、`NET_AFTER_INPUTS`、`LABOR_AMOUNT`、700‰、cloth；受方 = 4 个农村 cohort；`residualOwner=operator`。⇒ 本周期：gross 10,200,000、loss 306,000、net **9,894,000**；4 条实付合计 **3,614,582**；operator 自留 **6,279,418**。

**关键代码位置**
- `harvest:3135-3231`；产出计提 `ledger.addOutputAccrual` / `creditOutput` 在 `:3167-3180`；`scaleOf:3753-3772`；`capacityScaleOf:2208-2216`。
- `ProductionSettlement.settle:332-383`，R6 可用上限 `:360-363`，`shareWithTotal:672-674`，`laborOfCohort:3697-3720`。
- `RegimeRelations.householdRules:442-451`；`ProductionRelation.residualOwner:91`。

**实际读写路径（day480）**
1. `harvest` 读当天 `Industry`（capacity 643、cycleLaborMilli 累计、cycleInputUsedMilli[fiber]=10,254,338、recipe.outputPerUnit[cloth]=30）。
2. `scaleOf` = min(产能 643, 劳动路 `avgLabor/laborPerUnit`, 投入路 `drawn/inputPerUnit`) = **340**。
3. `gross = 340×30×1000 = 10,200,000`；`loss = 10,200,000×30/1000 = 306,000`；`net = 9,894,000`。
4. `creditOutput` 把 `+9,894,000` 写进 `operatorGoods[weave]`；`ledger.outputAccruals` 留一条 actor entry。
5. `settle` 逐规则 `due=shareWithTotal(net×700‰, 本 cohort 劳动, 全格 8 行劳动之和)`；实付从 operator 账划给 `HouseholdActors.of(cohort)`（`RELATION_PAYMENT` 转移），并写受方 `FlowRow.income[cloth]`。
6. 没有规则覆盖的余额 = operator 的 `residualOwner` 自留，**只有账上余额变化，没有 transfer/entry**。

**探针证据**
- day480 `ledger.grossWeave={cloth:10,200,000}`、`lossesWeave={cloth:306,000}`、`outputAccrualsForOperator=[{delta:9,894,000}]`。
- `ruleSettlementsForWeave` 4 条：due=paid（贫农 1,797,655 / 中农 1,326,260 / 富农 469,549 / 地主 21,118），`owed=0`。
- 4 条 `RELATION_PAYMENT` transfers from `HOUSEHOLD:weave@-34_-65` 到 4 个 cohort，goods 分别为上述 4 个数。
- 交叉验算：`23,722,991（关账前布） + 9,894,000 − 3,614,582 = 30,002,409`（day480 终值，逐毫对上）。

**推断（明确标注）**
- 4 条实付合计 3,614,582 = 9,894,000 × 36.53%；36.53% ≈ 700‰ × (rural 4 行劳动 / 本格 8 行劳动) ⇒ **份额被分母稀释**，而不是 R6 截断。此处 36.53/0.7 = 52.19%，与“农村劳动约占本格 52%”一致（本报告未直接打印 8 行劳动总量，只打印了 4 行；该比例是由实付反推的**推断**）。
- 若净产不够付，R6 会把 paid 截到 `net − 已付`，`owed` 只进读数（`RuleSettlement.owed`），**不产生跨周期债务**（`ProductionSettlement` 类注；无 `Claim`/`Debt` 写入）。

**未验证**
- 没有构造“净产 < 应付”的周期去实测 R6 截断；本周期 R6 未咬合（paid=due、owed=0）。
- 没有跑 481+，不知道下一周期是否把 30.00M 布继续挂单、以及 fiber 再分配后的 scale。

### A3. 卖不出去的库存留在哪本账：`GoodsAccount(owner,location)` + `OwnershipBooks.land*`

**结论（【代码事实】+【状态证据】）**
- 市场卖单/未成交是**瞬态**：`MarketSettlement.ordersFor:679-748` 只在开市轮生成 `BuyOrder/SellOrder`；不落进 `EconomyData` 的任何组件；`MarketReport` 也不落盘。
- 经营者的布库存在日循环里留在 **`EconomyDayStepper.operatorGoods` 会话副本**（键 = `ActorRef`），推进结束时由 app 组合根 `OwnershipBooks.landOperatorGoods:495` **按绝对值**写回 actor 切片的 `GoodsAccount`；账户键 = **`GoodsAccountKey(owner=operator, location=-34_-65)`**。货币同理 `landOperatorMoney:547`。
- 持久产物里能追到的就是这本账的余额；**订单/成交身份追不到**。

**关键代码位置**
- 会话副本：`EconomyDayStepper.operatorGoods:425`、`operatorMoney:430`；类注自述“不进 EconomyData/变更集、不跨 revision 存活”。
- 落回：`OwnershipBooks.landOperatorGoods:495`、`landOperatorMoney:547`；`OwnershipBooks.REASONS_NOT_FOLDED = {MARKET_TRADE}:90`（市场效果由副本绝对值覆盖，fold 不重复叠）。
- `GoodsAccount` / `GoodsAccountKey`：`simos-actor/.../model/GoodsAccount.java:103`、`GoodsAccountKey.java:42-62`。
- 卖单：`MarketSettlement.ordersFor:679-748`（`sellable=max(0,stock−frozen−necessary−life)`）；`necessaryInputsOf:1699-1725`（operator 的必要投入 = `capacityScale×inputPerUnit`，对 weave 只是 fiber，不含 cloth）。
- 瞬时报告：`MarketReport:49-62`；`EconomyDayStepper.lastMarketReport:512`。

**实际读写路径（本轨迹）**
1. 每 5 天（+低粮追加轮）`clearOncePerCycle:435` 把 operator 的布挂成卖单：`sellable = 30.00M − 0 − 0 − 0`（household relation 没有 `FIXED_IN_KIND_PER_LABOR`，`retentionOf` 返回空 ⇒ 生活保留 0）。
2. 成交时 `executeTrade` mint 一对 `MARKET_TRADE` 转移；`applyTransfer:3259/3288` 写 `operatorGoods/operatorMoney` 会话副本。
3. 日末不写 actor；只有在“app 推进结束”时 `landOperatorGoods/landOperatorMoney` 按绝对值写 actor 账。
4. **未成交**的布没有任何条目——它只是从未离开 operator 的会话副本/最终 actor 余额。

**探针/状态证据**
- 持久：`store-m2` rev11 的 `actor` changeset 里逐值可读：
  `HOUSEHOLD:weave@-34_-65|-34_-65` → `balances:{cloth:23,815,333,fiber:577,571}`、`money:{silver:2}`（解析 `changeset_json` 得到，见 §4.1）。
- 探针：final-summary `operatorSessionGoodsFinal = {cloth:30,002,409,fiber:475,682}`；一次性 `land*` 后 `operatorLandedGoodsFinal` 完全相同，`operatorSessionEqualsLandedGoods/Money = true`。
- 市场：day480 最后一行 operator sell order `{side:sell,commodity:cloth,quantity:30,002,409,reason:NO_BUDGET}`；整个 120 天只成交 92,342 毫布。

**未验证**
- 探针只在最后做了一次 `land*`，没有逐日 `fold/apply`；因此“最终 8,191 本 actor 账全部与真实推进一致”只对 operator 与 4 个抽样 cohort 逐值核过，**没有全量核对**。
- 我们没有把 361–480 写回 store，也没有产生 tick480 的持久 revision；480 的库存只存在于 `/tmp` 探针输出与内存。

### A4. 经营者承担哪些成本（投入、劳动、维护、货币工资档）

**结论（【代码事实】+【状态证据】）** 本周期四项实测：

| 成本项 | 本周期量 | 路径/证据 |
|---|---:|---|
| 投入（纤维） | **10,254,338 毫** | 其中自己的经营者账 577,571；relation 名下家户账 9,676,767。`drawCycleInputs:1507`、`recordOperatorInputDraw:2179`、`recordInputDraw:2127` |
| 劳动（实物分成） | **3,614,582 毫布** | 从 day480 新净产里付；4 条 `OUTPUT_SHARE` 实付；`ProductionSettlement.settle:332` |
| 维护/损耗 | **306,000 毫布** | `harvest:3165` 的折旧 30‰（`FEED_PER_MILLE=0` + `DEPRECIATION_PER_MILLE=30`）；只进 `ledger.losses`，不是现金支出 |
| 货币工资档 | **无**（0） | `RegimeRelations.householdRules:442-451` 没有 `FIXED_MONEY_WAGE`；`RuleSettlement` 里也没有货币条目。对比 handicraft 档 `handicraftRules:454-475` 有 `FIXED_MONEY_WAGE`（本探针同格的 craft 有，但受付方余额上限约束） |
| 市场净货币支出 | **收入 503 毫银 / 支出 503 毫银** | 69 笔卖布收款、71 笔买纤维付款；银余额 2–22 毫；`MarketReport.fills` 逐笔 |

**关键代码位置**：`EconomySettlement` 的 `FEED_PER_MILLE/DEPRECIATION_PER_MILLE` 常量区（约 `:243-260`）；`harvest:3165`；`RegimeRelations.householdRules:442-451`；`ProductionSettlement.settleMoneyRule`（货币档真的结算，上限=付方本期可用货币）。

**实际读写路径**
- 投入：纤维从 `operatorGoods` 先扣，再从 `householdGoods` 扣；进 `Industry.cycleInputUsedMilli`；家户端进 `FlowRow.consumed`。
- 劳动：没有“工资现金”路径；`harvest` 后按 rules 铸 `RELATION_PAYMENT` 实物转移，operator 账 −cloth、cohort 账 +cloth，并记 cohort `FlowRow.income[cloth]`。
- 维护：只在 `ledger.losses` 记一笔（“蒸发”），不进任何人的 `FlowRow.consumed`（R1 口径）。
- 货币：只有 `MARKET_TRADE` 转移写 `operatorMoney`；没有地租/工资的货币转移。

**未验证**
- 成本里没有出现“工具/作坊维护的现金/实物支出”；`DEPRECIATION_PER_MILLE=30‰` 是唯一维护项。若真档有其它维护来源，本探针没有暴露。
- 没有把时间延伸到下一个周期，所以“维护/投入是否会因库存卖不出去而断供”只在一个周期内看过。

### A5. 工资/实物分成是否继续支付：R6 上限 = 实付 ≤ 本周期可用产出

**结论（【代码事实】+【状态证据】）** **继续支付**。day480 的 4 条规则全部 `due=paid`、`owed=0`；实付来自当天新建的净产，不依赖布是否卖得出去。R6 的上限是 `available = max(0, net[commodity] − 已付[commodity])`（`ProductionSettlement.java:360-363`），本周期没有咬合。

**关键代码位置**
- `ProductionSettlement.settle:332-383`；R6 的 `available` 在 `:360`；`RuleSettlement` 记录 `due/paid/owed` 在 `:189-228`（`owed()` 只是读数，不落债权）。
- `harvest` 里 `ProductionSettlement.Outcome outcome = settle(...)` 与 `ledger.addRuleSettlement`：`EconomySettlement.java:3209-3229`。

**探针证据**：见 A2 的表。另：operator 在 day480 关账前布 23,722,991，关账后 30,002,409；+6,279,418 自留正好等于 net −实付。

**推断**
- 名义 700‰ 与实付 36.5% 的差额**不是欠款**，而是 `LABOR_AMOUNT` 分母（本格 8 行）大于受方集合（4 行农村）造成的“制度分母账”；`owed=0` 也证实没有欠款。此为按代码公式的**推断**（未单独打印 8 行劳动总量）。
- 由于 `owed` 不落 `Debt/Claim`，即使某周期净产不足，也不会出现“经营者欠劳动者 → 破产”的债务链条。

**未验证**
- 没有实测“净产 < 应付”的 R6 截断周期。

### A6. 经营者能否借债：`lendDeficits` 的键是 `ClassRow/CohortKey`，不是 `ActorRef`——关键阻断点

**结论（【代码事实】，最强的一条）** **不能。** `Debt` 的债务人/债权人类型是 `CohortKey`（家户键）；`lendDeficits` 的入参/循环/`HouseholdActors.of` 全部键到 `CohortKey`。`ActorRef` 类型的经营者（`HOUSEHOLD:weave@-34_-65`）在类型上不能成为债务主体。

**关键代码位置**
- `Debt.debtor/creditor`：`simos-economy/.../model/Debt.java:39-47`（record 第 2/3 组件是 `CohortKey`）；`defaulted` 现恒 `false`，唯一 `new Debt` 写入是 `EconomySettlement.java:2603-2611()` 里写死 `false`；`withPrincipal:3081-3089` 只复制原值。
- `lendDeficits` 签名：`EconomySettlement.java:2507-2526`（`LinkedHashMap<CohortKey,ClassRow> rows`、`deficitToday` 也是 `Map<CohortKey,Long>`）；放贷环 `for (CohortKey debtor : debtors)`、`HouseholdActors.of(lender/debtor)`：`:2554/2584-2585`。
- `debtIdOf:3067`：id = `debt-c<cycle>-<债务人家户键>><债权人家户键>-<商品>`，两段都来自 `CohortKey`。
- `repayDebts:2752-2805`：`for (CohortKey debtor : rows.keySet())`；`chargeInterest:3864-3882`：只对 `debt.debtor()`（CohortKey）累计。
- `householdOfActor` 由 `householdActorsOf(rows)` 建（`EconomySettlement.java:3616`）：只把 `CohortKey → HouseholdActors.of(cohort)` 放进表；**经营者 ActorRef 不在表内**（探针初始 `operatorInHouseholdOfActor=false`）。

**实际读写路径（探针）**
- 初始：`HouseholdActors.cohortOf(HOUSEHOLD:weave@-34_-65)` 抛 `IllegalArgumentException: 家户 actor id 形状非法（应为 <q>_<r>:<residence>:<stratum>）: weave@-34_-65` ⇒ operator 无法转换成 CohortKey。
- 120 天内 `debt` 表从 699 条（tick360）增到 1,467 条（day480）；所有与本经营者/关联家户相关的条目 debtor/creditor 都是 cohort 键；**没有任何一条的 debtor/creditor 是 `weave@-34_-65`**。`operator` 账户的银只由市场交易改变。
- `lendDeficits` 给 operatorGoods/operatorMoney 的入参只用于 `applyTransfer` 的“谁是operator端”分支；本 relation 的债务两端都是家户，operator 不参与。

**推断（明确标注）**
- 因 operator 不能借债、也没有固定货币义务（household 档没有货币工资/地租），系统里**不存在“经营者资不抵债 → 破产”的状态或通道**。这是从类型与写入面推出的，不是运行观测到“破产不发生”。
- 若某天 operator 的银为 0 且卖不出布，它能继续生产，因为投入的首选路径是“自己的 fiber + relation 名下家户账”，不需要货币；本周期次周期投入不会被货币阻断。

**未验证**
- 没有做“把 operator 的 fiber 账户清零、家户 fiber 也清零”的构造实验；那种情况下 `usableScale` 会 floor 到 0、当周期无产出（`scaleOf:3765-3771`），但**仍然不是债务/破产**，而是“缺料停产”。
- 没有 grep 全部模块（只读了 main 且以 `new Debt(` 为主）——baseline D 已做过全仓 main `RecipeId` 等零引用核查；本报告对 Debt 的写入口只核到 `new Debt(` 的两处。

### A7. 缩产/停产的触发：`capacityScaleOf` / `progressDays` / `cycleInputUsedMilli` / `reallocateLabor`

**结论（【代码事实】+【状态证据】）**
- **产能本身没有变**：`capacityScaleOf = min_k floor(capacity[k]/capacityPerUnit[k]) = 643` 全程不变。
- 开工规模在周期第一天由 **投入可供量** 决定：`usableScaleOf:1793` 与本格争用配给（`rationContestedInputs:1717`）→ 本周期 weave 可开 **341** 台；`reallocateLabor:2840` 随后把 weave 配额调到 `341 × laborPerUnit(1000) = 341,000/日`。
- 收获规模由 `scaleOf:3753` 取最紧路：产能 643、劳动路 `avgLabor/laborPerUnit`、投入路 `drawn/inputPerUnit`。本周期实测 **340**（因为月度死亡把配额从 341,000 逐月缩到 340,671，120 天平均的 floor 是 340；投入路 10,254,338/30,000=341；产能 643 不是瓶颈）。
- **没有订单驱动的缩产**：卖不出去不改 `capacity`、不改 `progressDays`、不改 `cycleInputUsedMilli`、不触发 `reallocateLabor`；后两者的输入只有容量/料/劳动。
- **停产/开工的可见形态**：`capacity=0` 是合法状态（`Industry` 构造期允许 0），但没有运行期命令把它改成 0；`scaleOf` 的产能路会给 0，`laborNeedOf` 给 0，配额全回池（`reallocateLabor:2879-2887`）。本轨迹没有出现。

**关键代码位置**
- `capacityScaleOf:2208-2216`、`usableScaleOf:1793-1802`、`scaleOf:3753-3772`。
- `progressDays` 推进/归零：`settleOneDay` 每日 `progressed = progressDays + 1`、`progressed >= cycleDays` 则 harvest 后 `nextProgress=0`（`:810-841`）。
- `cycleInputUsedMilli`：周期第一天 `drawOneIndustryInputs` 累加（`:1928-1937`，写回 `withCycleState`），关账清零（`:836-838`）。
- `reallocateLabor:2840-2931`；`laborNeedOf:2958-2978`。

**探针证据（T1）**
- 343,667（tick360）→ **341,000**（day361，reallocate 后）→ 340,917（day390）→ 340,835（420）→ 340,753（450）→ 340,671（480）。
- `cycleInputUsedMilli[fiber]=10,254,338` 从 day361 保持到 day479，day480 关账后 `{}`；`progressDays` 1..120 → 0。
- gross 10,200,000 ⇒ 规模 340（10,200,000/(30×1000)），与劳动路一致。

**未验证**
- 我们**没有实跑**“把 fiber 全部抽干/把 capacity 改 0”的实验；`capacity` 在本世界没有写入口，停机路径是代码推演（D §7.4/§8.3）。
- 481+ 的下一次现扣没有跑；下一周期的 `usableScale` 取决于 day480 关账后农村 fiber 的再生量（day480 farm 给了贫农 18,042,000 毫 fiber），本报告不预报。

### A8. 有没有“退出/关闭”状态

**结论（【代码事实】）** **代码里没有“退出/关闭”状态字段或命令。**
- `Industry` 的组件只有周期/进度/产能/配方/槽位/operator（`Industry.java:108-125`）；没有 `active/closed/retired`。
- 唯一 economy 命令 handler 是 `EconomySeedHandler`（`type="economy.Seed"`，`Shell.java:439`）；对已激活切片按格追加时，**该格已有产业/阶层行 ⇒ 整份拒绝**（`EconomySeedHandler.java:74-80`，`occupiedHexKeys:105-115`），所以**不能改既有格**。
- `EconomyData.withIndustries` 是 copy-with，main 零调用点（`grep` 仅定义+test）。
- 生产的中断只有“规模 0”（料/劳动/产能任一为 0），而不是“退出”；下一周期第一天若条件恢复，`drawCycleInputs + reallocateLabor` 可以把规模重新开起来（但需要下一周期，未跑）。

**推断**：对“经营者退出/关闭”这个问题，今天**没有对应状态可观测**；`capacity=0` 只是“不生产”的技术状态，且没有写入命令。**不能把本轨迹的“还在生产”读成“系统设计上不会退出”**——只是本轮没有退出表示。

**未验证**：没有查 GM 工具面之外的隐藏写口（只按 main handler/grep；baseline D §7.4 同口径）。

---

## 3. B. 劳动家户侧（逐条）

### B1. 关联哪些 cohort：`EconomySettlement.householdKeysOf`

**结论（【代码事实】+【状态证据】）** 对 `weave@-34_-65`，返回 **该格 4 个农村 cohort**：
`-34_-65|rural|poor_peasant / middle_peasant / rich_peasant / landlord`。
它不按“谁给这个产业出了劳动”逐批次返回，而是按 **配额表里出现过的居住类型集合 × 四阶层** 摊出 4 行；该产业有 2 条配额（rural MALE/FEMALE 批次），居住类型集合 = {rural}。

**关键代码位置**：`householdKeysOf:2237-2252`；`householdKeysAt:2254-2277`；`householdKeysOfLot:2279-2290`；`ResidenceKind.ofLot`（economy-api `ResidenceKind.java:79`）。

**探针证据**：initial 与 final day 的 `associated` 都是这 4 个键；经营者所在的 `weave@-34_-65` 本身**不是**其中一个（id 形状不同）。

**未验证**：没有把 4 行内部的“哪一阶层为 weave 出多少劳动”与批次配额逐值对到一行（配额给出的是 MALE/FEMALE 两条，按居住类型映射到 4 个阶层；具体分配由 `ClassRow` 与规则权重决定，本报告只按行汇总）。

### B2. 实物所得：规则 → `RuleSettlement` → `FlowRow.income`

**结论（【代码事实】+【状态证据】）** 4 个农村 cohort 在本周期（361–480）拿到：
- weave 的布分成：贫农 1,797,655；中农 1,326,260；富农 469,549；地主 21,118（day480 的 4 条 `RELATION_PAYMENT`）。
- 还有 farm 的粮/纤维规则（同一批 cohort 同时供给 `farm@-34_-65`）：例如贫农 `income.grain=51,653,520`、`income.fiber=18,042,000`；地主 `income.grain=62,916,816`。这些是 farm 的 feudal 规则，不是 weave 的。

**关键代码位置**
- `ProductionSettlement.settle:332-383`（每规则 `RuleSettlement`）；`EconomySettlement.harvest:3216-3226`（`applyTransfer` + `addGoods(income,...)`）。
- `FlowRow.income` 字段：`FlowRow.java:78`；周期末整周期值、新周期第一天清零（`settleOneDay:1011-1025`）。

**探针证据（终值 cycle 361–480）**：见 T5 表（§5）。Weave 的 4 笔布份额可由 `ruleSettlementsForWeave` + `RELATION_PAYMENT` 逐条对上；farm 的粮/纤维收入在 `FlowRow.income` 里，未按产业拆分，**这是读数的结构（income 无产业维）**。

**未验证**：`FlowRow.income` 不区分产业来源；上面把 weave 布份额与 farm 粮/纤维分别引用，是因为它们在 day480 的 ledger 里有明确 reason/from/to。日常（非关账日）的收入没有发生。

### B3. 消费与未满足（`FlowRow`）

**结论（【代码事实】+【状态证据】）** 4 个 cohort 全周期 `unmetNeed={}`（棉布/粮/纤维都没有缺），因为库存远大于日耗与周期投入。消费量（`consumed` = 日耗 + 现扣投入）按 T5。

**关键代码位置**
- `consumeOwnStock:2423-2463`：先 `withDailyNeed` 覆写 `naturalNeeds`，再吃库存；缺口写 `unmetNeed`/`deficitToday`。
- `withDailyNeed:4321-4340`；`FlowRow.consumed/unmetNeed`：`FlowRow.java:79/85`。
- `lendDeficits` 只对 `deficitToday` 非空的行启动（`settleOneDay:883-908`）。

**探针证据**：T5 的 `unmet` 列全 `{}`；`consumed` 列：贫农 cloth 1,871,178、grain 56,915,000、fiber 17,003,559；中农/富农/地主主要是 cloth+grain。贫农的 fiber consumed 是它供给 weave+craft 的现扣投入；其余 cohort 无 fiber 支出（他们的 fiber 收入为 0）。

**推断**：本轨迹里的消费不足没有发生，不是因为“市场买到了”，而是因为初始库存足够；`unmetNeed` 是唯一可读的“未满足”字段，但它对 cloth 的窗口与 `naturalNeeds` 同步（本轨迹全 0）。

**未验证**：没有逐日打印 `FlowRow` 的中间值到报告表（只在 JSONL 里有；报告给周期终值），也没有单独统计“市场买到多少粮/布”是否冲减了 `unmetNeed`（本轨迹无缺口，无从分辨）。

### B4. 借债/偿还/计息/违约（`lendDeficits` / `repayDebts` / `chargeInterest` / `Debt.defaulted`）

**结论（【代码事实】+【状态证据】）**
- **4 个农村 cohort 本身没有借债**：`newBorrowing=0`、`repaid=0`、`interestDue=0`、`debts`（作为 debtor）为空。
- **其中农村地主是债权人**：对**城镇** poor/middle/rich/landlord 4 个 cohort 共 **7 条粮债**。tick360 时 c3 债务本金合计 52,041,553；到 day480（加上 c4 新借与计息）合计 **109,330,627 毫**。
- 本周期地主新借出 **55,164,004 毫粮**（61 个有放贷的日子，来自 `LOAN_PRINCIPAL` 转移）；**没有任何一笔 `LOAN_REPAYMENT` 涉及该地主**（day480 的 26 条相关转移里 reason=RELATION_PAYMENT 10、MARKET_TRADE 12、LOAN_PRINCIPAL 4，没有 LOAN_REPAYMENT；全 120 天也一样）。
- day480 关账计息：c3 本金 52,041,553 → 53,082,384（+1,040,831 = 20‰）；c4 在 day479 的本金 54,212,042 → day480 56,248,243（+1,084,240 利息 +951,962 当日新借；±1 为逐条 floor 的取整差）。合计利息并入约 **2,125,071 毫**。
- **所有 `Debt.defaulted=false`**，包括逾期（`dueCycle=4` 的 c3 债务在 day480 已到期）仍未偿还的条；代码里没有把 `defaulted` 写成 true 的路径。

**关键代码位置**
- `lendDeficits:2507-2743`（放贷序列地主→富农→中农；债务人按阶层序；信用线 `creditLinesOf:2685`；可贷余粮 `lendableOf:2652`）；`Debt` 构建 `:2603-2611`（`defaulted=false`）。
- `repayDebts:2752-2805`（只对 `rows` 里的 `CohortKey` 债务人；预算 = min(本期所得粮×200‰, 当前粮库存)；铸 `LOAN_REPAYMENT`）；`DEBT_REPAYMENT_SHARE_PER_MILLE` 常量区 `:262-322`。
- `chargeInterest:3864-3882`（本金并入、`interestDue` 只记债务人侧）；调用条件 `if (anyCycleClosed)`：`settleOneDay:964-970`。
- `Debt.defaulted`：`Debt.java:47`，类注写明“当前恒 false”。

**探针证据**：T6 表逐条给出 c3/c4 的 debtor、principal@361→@480、dueCycle、defaulted；`transfersInvolving` 里 `LOAN_PRINCIPAL` 55,164,004 / `LOAN_REPAYMENT` 0；`FlowRow` 里 4 个农村 cohort 的 `newBorrowing=repaid=interestDue=0`。

**推断（明确标注）**
- 城镇债务人无法偿还的**可能机制**：`repayDebts` 的预算只认 `income[grain]`（本期规则所得）与当前粮库存；城镇 cohort 若没有 farm 的粮规则收入、粮库存又被日耗/借粮吃掉，预算就是 0。本报告**没有**打印城镇 4 行的 `FlowRow`（探针只抓了 weave 关联的 4 个农村 cohort 与“涉及它们”的转移），所以“为什么 0 偿还”只能说是**行为观测 + 代码预算口径的推断**，不是逐行证明。
- 由于 `defaulted` 恒 false、没有违约处置/债务免除/破产状态，城镇债务人的债只会随利息增长；**这是“生存”侧唯一可见的恶化通道**，但它在这一个周期里没有传导到经营者。

**未验证**
- 没有打印城镇债务人的 `FlowRow`（income/consumed/unmet/newBorrowing/repaid），因此“他们是否买到粮、是否挨饿、为什么还不出”**不可判定**。
- 没有跑第 2 个周期，不能判断 c3/c4 债务是否会在后续被偿还或继续核销。

### B5. 劳动配额变化（`LaborAllocation`）

**结论（【代码事实】+【状态证据】）**
- day361 `reallocateLabor` **真的改了配额**：weave 合计 343,667 → **341,000/日**（`usableScale=341 × laborPerUnit=1000`）。
- 此后每月死亡按存活比例缩：day390 340,917 → day480 340,671；**出生不增加配额**（`applyPopulationChange` 把 births 加到行人口，但 `labor` 只按 `remaining/population` 缩；`scaleLaborOfGroup` 只在 `after < before` 时按比例缩，没有放大路径）。

**关键代码位置**
- `LaborAllocation`：`simos-economy-api/.../labor/LaborAllocation.java`（id/group/actor/activity/laborMilli/period）。
- `reallocateLabor:2840-2931`；`laborByActor:3957`；每日 `cycleLaborMilli` 累加读配额：`settleOneDay:767/808-809`。
- 月度回写：`applyPopulationChange:1134-1218`；`scaleLaborOfGroup:1355-1381`（只缩）；`scaleSupply:1384-1401`。

**探针证据**：T1 的 `cycleLabor` 与 `allocationsFinal`；allocation 变化点 361/390/420/450/480 逐值见 §5 引用的 JSONL。

**未验证**：没有核“配额 id 的 period 字段是否在运行期变化”（代码是常设配额；本轨迹 id/period 未变，只有 laborMilli 在缩）。

### B6. 饥饿与死亡：`applyFamine` 非零条件与 `PopulationDynamics` 月度回写

**结论（【代码事实】+【状态证据】）**
- `applyFamine:3804-3832` 公式：`cycleNeed = cumulativeRation(pop,day) − cumulativeRation(pop,day−cycleDays)`；`faminePerMille = min(1000, cycleUnmet×1000/cycleNeed)`；`dead = pop × faminePerMille/1000 × famineMortalityPerMille/1000`。**默认 `FAMINE_MORTALITY_PER_MILLE=0`（`:345`）⇒ dead 恒 0**；非零只在夹具注入非 0 致死率时可能。
- 本轨迹的死亡来自 **`PopulationDynamics.monthly`**（每 30 天，`PopulationDynamics.java:205`），经 `PopulationEconomyTimeParticipant` 的 `applyDailyStress`（读当天 `naturalNeeds` 与 `FlowRow.unmetNeed`）与 `PopulationDynamics.stressAfter:174`、`BASE_MORTALITY_PER_MILLE_PER_MONTH:113` 算出；然后 `stepper.applyPopulationChange` 回写行人口/劳动配额/`FlowRow.births/deaths`。
- 本探针（含社会侧回写）实测 4 个月：全球 births 232,364 / deaths 161,560；本格 births 407 / deaths 308；4 个农村 cohort 的 `FlowRow.births/deaths` 分别为贫农 124/56、中农 96/40、富农 40/24、地主 12/8；农村行合计 +272/−128，城镇行承担其余 −180/+135，合计净 +99 = 407−308。由于 4 行 `unmetNeed={}`，这些死亡**不能归因于饥饿**；是基础死亡率/压力路径的月度结算（压力具体值本探针未打印）。

**关键代码位置**：`applyFamine:3804-3832`、`FAMINE_MORTALITY_PER_MILLE:345`；`PopulationEconomyTimeParticipant:203-249`（日循环、`applyDailyStress`、月度 `PopulationDynamics.monthly`、`applyPopulationChange`）；`PopulationDynamics.monthly:205`、`stressAfter:174`；`FlowRow.births/deaths:86-87`。

**探针证据**：T7 表；final-summary 的 `monthlyBirthsRunTotal/monthlyDeathsRunTotal`、`birthsAtWeaveHexRunTotal/deathsAtWeaveHexRunTotal`、`socialPopulationStart/End`。

**推断**
- 若某行 `unmetNeed` 长期不为 0，`stressAfter` 会按粮/布缺逐日累加压力；超过 `STRESS_MORTALITY_THRESHOLD=300` 后抬高月死亡率。本轨迹没有缺粮，所以这条通道没有触发。**这是代码事实的推断，不是本轨迹发生的事件。**

**未验证**
- 没有打印各 cohort 的 `PopulationGroup.physiologicalStress`；没有观测到缺粮状态下的死亡；没有构造非 0 `FAMINE_MORTALITY_PER_MILLE` 的探针。
- `PopulationDynamics.monthly` 的全局出生/死亡与 4 行 `FlowRow.births/deaths` 的逐行摊派没有全量对账（只对上农村/城镇两组的合计方向）。

---

## 4. C. 证据链与断点：从持久产物能追到哪一步

### 4.1 持久产物里能追到的（实测）

**（a）store 的 revision/changeset 能追到“revision 边界的存量”**

**【状态证据】** 对 `/tmp/hstore/simos.db` 只读解析（`sqlite3` 只读 URI / Python `sqlite3`）：

| 事实 | 值 |
|---|---|
| `revisions` 行数 | **11**（rev1..rev11，tick 0/180/210/240/270/300/330/360） |
| rev11 tick | 360，`changeset_json` 长度 **10,548,843 字符** |
| changeset 顶层模块 | `actor / economy / sd / social / unit`（map 无变更） |
| economy 组件 | `industries / classes / debts / flows / laborSupply / allocations / relations / markets / shipments` |
| **经营者账** | actor changeset `accounts.entries["HOUSEHOLD:weave@-34_-65|-34_-65"]` = `balances:{cloth:23,815,333,fiber:577,571}`、`money:{silver:2}`、`frozen={}` |
| 关联贫农账 | `accounts.entries["HOUSEHOLD:-34_-65:rural:poor_peasant|-34_-65"]` = grain 65,341,617 / cloth 3,170,406 / fiber 18,027,788 / silver 36,653 |
| 产业状态 | `economy.industries` upsert 含 `weave@-34_-65`：`progressDays=0`、`cycleInputUsedMilli={}`、`capacity={TOOL:643}`、配方/operator 全在 |
| 债务 | `debts` upsert **699** 条（tick360） |
| events 表 | 只有 `simos.module.proposal / simos.command.received / simos.command.committed / simos.time.advance.started / simos.time.advance.finished` 五类命令生命周期事件；**没有任何 transfer/market 行** |
| checkpoint | `checkpoints/main/1.json` 是 tick0（rev1），本批 advance 未命中 checkpoint 间隔 |

**能追到**：用 `CoreSimos.replay` 从 rev1 重放到 rev11，可恢复 tick360 的完整 economy/actor/social 状态；任意两个 revision 之间的**存量差**可算（例如经营者布从 tick180→tick360 的差）。**不能追到**：每个日循环内部的投入/转移/市场成交/逐规则 due-paid/逐日 FlowRow；changeset 是增量，不是“每日账本”。

**（b）dump 能追到“dump 时刻的读时派生量 + 最近一轮市场报告聚合”**

- `m2t360.json` 的 `marketReadout` 是 dump 时刻由 `MarketReadout.derive`/`planOrders` 现算的读时值；`match.*` 来自**最近一次开市**的进程内 `MarketReport`（`unavailable.matchResults` 自述“不落盘、重启即失”）。
- 对 `c--34_-65`（201 区全局去重）逐值可复算：布 `supplyMilli=297,582,293`、`effectiveDemandMilli=0`、`match.tradedMilli=0`、卖方 `no_budget` 297,603,582/40；纤维 supply 128,118,939、traded 96,356；粮 supply 1,210,152,021、traded 116,922。**没有逐笔买卖方身份**。

**（c）本轮探针自己的“持久产物”**

- 没有写任何 store/revision/dump；`/tmp/hprobe-run-H.jsonl` 是进程内结果落成文本，不是系统持久层。原 `store-m2` 未被写（mtime 仍 01:49；只读副本 `/tmp/hstore` 上跑）。

### 4.2 只能靠进程内探针的（今天重启即失）

| 对象 | 代码位置 | 为什么不可从 store/dump 追 |
|---|---|---|
| `ProductionLedger`（每天一本） | `ProductionLedger.java:60-67`；`EconomyDayStepper.step:460` 返回；`PopulationEconomyTimeParticipant:209-213` 当天 fold 后丢弃 | 只作为日循环局部变量；不属 `EconomyData` 十组件，不进 `EconomyChangeSet`；`step` 返回后调用方只把它折成 actor entries，原 ledger 无引用 |
| 逐笔 transfers（reason/from/to/goods/money） | `ProductionLedger.transfers:65`；`mint:Accumulator`；我们的探针逐日打印 | `fold` 只产出 actor 条目；市场成交被 `REASONS_NOT_FOLDED` 排除；没有 transfer 表 |
| `RuleSettlement`（due/paid/owed） | `ProductionSettlement.java:189-228`；`ledger.ruleSettlements` | 只是当天的“读数”；`owed` 不落债权；无状态组件承载 |
| `MarketReport.fills/unfilled`（逐笔身份/原因） | `MarketReport.java:49-62`；`EconomyDayStepper.lastMarketReport:512` | 报告不落盘；dump 只保留聚合；`MarketReportFeed.publish` 进程内且重启即失（baseline C 已核） |
| 逐日 `FlowRow` 中间值 | `EconomyDayStepper.flows:390`；`settleOneDay:1011-1025` 周期末挂上 | 只把周期累计挂到 `EconomyData.flows`；日中间值只存在会话里 |
| 会话副本 `householdGoods/householdMoney/operatorGoods/operatorMoney` | `OwnershipBooks.load*` / `EconomyDayStepper` 访问器 | 明确“不进 EconomyData、不进变更集、不跨 revision”；只有 `land*` 的绝对值落 actor |
| 产业工作副本（`progressDays/cycleInputUsedMilli` 的当天中间态） | `settleOneDay` 内 `industries` 工作副本；`withCycleState` | 只有每天末态的 `EconomyData` 能进变更集；本探针每天从 `stepper.data()` 读 |

**探针能抓到的边界（我们实际抓了）**：`ledger.inputs/gross/losses/outputAccruals/transfers/ruleSettlements/deferredMoney`、`lastMarketReport` 的买卖双方与未成交原因、`industry.progressDays/cycleInputUsedMilli/cycleLaborMilli`、`data.debts()`、`stepper.flows()`、4 个 cohort 的 goods/money/FlowRow。

### 4.3 今天完全不可观测的

1. **进程退出后的历史日级流水**：`store-m2` 没有 ledger/transfer/market 表或列；events 只有命令生命周期。任何“day X 谁付给谁”在一年推进结束后**无法从持久产物复算**（baseline C §1.4 的结论在本探针里复现：我们只能看到期末余额与探针当天的逐笔）。
2. **重启后的最近市场报告**：`MarketReportFeed` 不落盘；tick360 dump 里的 `match` 是 dump 当时进程内报告的投影，重启后同一天也读不到。
3. **`RuleSettlement.owed` 的历史**：不落 `Debt`/`Claim`；没有欠款台账。
4. **经营者的债务/破产态**：不是“没存”，而是**没有这个状态/通道**（§A6）。因此“经营者是否已经破产”在所有持久产物上都是**不可判定**；本报告也**没有**说“已经发生破产”。
5. **会话副本的轮内峰值**（例如市场挂单冻结的瞬时值）：tick 末快照的 frozen 恒空（baseline C 已核）；探针的 final-summary 也不是轮内峰值。
6. **跨 revision 的逐日 FlowRow 断点**：一个 advance 只写一条 revision；如果分块推进，chunk 边界上的 FlowRow 周期累计可读，但 chunk 内的逐日不明。

### 4.4 一条可复算的“持久 → 探针”闭环（本次实测）

1. store rev11 changeset 里 operator 账号：`cloth=23,815,333 / fiber=577,571 / silver=2`（持久）。
2. 探针 replay 后 `OwnershipBooks.loadOperatorGoods/loadOperatorMoney` 得到同样的初值；`initial` 记录输出一致。
3. 探针 day480 结束时 `operatorGoods={cloth:30,002,409,fiber:475,682}`；一次性 `landOperatorGoods/Money` 后 actor 账 `operatorLandedGoodsFinal/Money` 与 session **逐值相等**（final-summary `operatorSessionEqualsLandedGoods/Money=true`）。
4. 也就是说：**“卖不出去的布留在哪本账”可以追到持久层（GoodsAccount 余额）**；**“它每天挂了多少单、谁买了多少、为什么没成交”只能在进程内追**。

---

## 5. 探针数字表（`/tmp/hprobe-run-H.jsonl`，120 天，361–480）

> 单位：商品=毫商品、货币=毫银、劳动=千分劳动（日或累计）、价格=毫银/商品单位。T1/T3 为节选；完整 120 天在 JSONL。

### T1. 经营者/产业关键日

| day | progress | cycleInput fiber | cycleLabor | capScale | op cloth | op fiber | op money | market? |
|---|---:|---:|---:|---:|---:|---:|---:|---|
| 360(initial) | 0 | 0 | 0 | 643 | 23,815,333 | 577,571 | 2 | n/a |
| 361 | 1 | 10,254,338 | 341,000 | 643 | 23,815,333 | 0 | 2 | no |
| 363 | 3 | 10,254,338 | 1,023,000 | 643 | 23,815,333 | 0 | 2 | yes |
| 365 | 5 | 10,254,338 | 1,705,000 | 643 | 23,815,333 | 0 | 2 | yes |
| 370 | 10 | 10,254,338 | 3,410,000 | 643 | 23,814,806 | 3,280 | 2 | yes |
| 390 | 30 | 10,254,338 | 10,230,000 | 643 | 23,810,090 | 30,089 | 2 | yes |
| 420 | 60 | 10,254,338 | 20,457,510 | 643 | 23,791,446 | 121,553 | 10 | yes |
| 450 | 90 | 10,254,338 | 30,682,560 | 643 | 23,762,912 | 267,817 | 10 | yes |
| 470 | 110 | 10,254,338 | 37,497,620 | 643 | 23,734,434 | 403,817 | 15 | yes |
| 479 | 119 | 10,254,338 | 40,564,397 | 643 | 23,722,991 | 455,817 | 22 | no |
| 480 | 0 | 0 | 0 | 643 | 30,002,409 | 475,682 | 2 | yes |

### T2. 经营者市场汇总（120 天）

| 指标 | 值 |
|---|---:|
| 市场轮 | **48**（PERIODIC 24 / LOW_GRAIN_STOCK 24） |
| 卖布成交 | **69 笔 / 92,342 毫布 / 收款 503 毫银** |
| 买纤维成交 | **71 笔 / 475,682 毫纤维 / 付款 503 毫银** |
| 银余额 min/max/final | **2 / 22 / 2** |
| day480 未成交 | 卖布 30,002,409（NO_BUDGET）；买纤维 2,135（ALGORITHM_UNCOVERED） |

### T3. 市场轮节选

| day | trigger | 卖布成交 | 卖布收款 | 买纤维 | 买纤维付款 | 卖布未成交 | 买纤维未成交 |
|---|---|---:|---:|---:|---:|---:|---:|
| 363 | LOW_GRAIN_STOCK | 0 | 0 | 0 | 0 | 23,815,333 | 2,000 |
| 365 | PERIODIC | 0 | 0 | 0 | 0 | 23,815,333 | 2,000 |
| 390 | PERIODIC | 284 | 2 | 2,000 | 2 | 23,810,090 | 0 |
| 420 | PERIODIC | 1,859 | 11 | 15,000 | 16 | 23,791,446 | 0 |
| 450 | PERIODIC | 1,983 | 11 | 15,000 | 16 | 23,762,912 | 0 |
| 470 | PERIODIC | 2,849 | 15 | 21,000 | 21 | 23,734,434 | 0 |
| 480 | PERIODIC | 0 | 0 | 19,865 | 20 | 30,002,409 | 2,135 |

### T4. day480 收获与分配

| 项 | 值 |
|---|---|
| gross / loss / net | 10,200,000 / 306,000 / **9,894,000 毫布** |
| 实付规则（受方） | 贫农 1,797,655；中农 1,326,260；富农 469,549；地主 21,118（每条 due=paid，owed=0） |
| 实付合计 / operator 自留 | **3,614,582 / 6,279,418** |
| operator 布：关账前→关账后 | 23,722,991 → **30,002,409** |
| 关账后市场挂单 | 卖 30,002,409 毫布（NO_BUDGET，无成交）；买 19,865 毫纤维 |
| 终值（session=landed） | 布 30,002,409；纤维 475,682；银 2 |

### T5. 关联 4 个农村 cohort 的 cycle 361–480

| cohort | 人口 360→480 | 布 360→480 | 粮 360→480 | 银 360→480 | income cloth | income grain | income fiber | consumed cloth | consumed grain | consumed fiber | unmet | newBorrow | repaid | interestDue | births | deaths |
|---|---|---|---|---|---:|---:|---:|---:|---:|---:|---|---:|---:|---:|---:|---:|
| `-34_-65\|rural\|poor_peasant` | 5,666→5,734 | 3,170,406→3,091,572 | 65,341,617→65,940,559 | 36,653→32,849 | 1,797,655 | 51,653,520 | 18,042,000 | 1,871,178 | 56,915,000 | 17,003,559 | `{}` | 0 | 0 | 0 | 124 | 56 |
| `-34_-65\|rural\|middle_peasant` | 4,409→4,465 | 2,187,913→2,054,684 | 48,719,680→49,231,725 | 30,153→24,218 | 1,326,260 | 38,108,448 | 0 | 1,456,437 | 44,300,000 | 0 | `{}` | 0 | 0 | 0 | 96 | 40 |
| `-34_-65\|rural\|rich_peasant` | 1,878→1,894 | 644,365→640,425 | 18,032,957→18,215,361 | 16,644→10,663 | 469,549 | 13,491,936 | 0 | 619,399 | 18,840,000 | 0 | `{}` | 0 | 0 | 0 | 40 | 24 |
| `-34_-65\|rural\|landlord` | 632→636 | 78,282→78,529 | 63,497,359→63,550,938 | 76,520→76,804 | 21,118 | 62,916,816 | 0 | 208,273 | 6,335,000 | 0 | `{}` | 0 | 0 | 0 | 12 | 8 |

### T6. 地主的债权（城镇债务人；day361 vs day480）

| debt id | debtor | principal@361 | principal@480 | dueCycle | defaulted |
|---|---|---:|---:|---:|---|
| `debt-c3-…\|urban\|poor_peasant>…\|rural\|landlord-grain` | urban poor | 34,215,751 | 34,900,066 | 4 | false |
| `debt-c3-…\|urban\|middle_peasant>…` | urban middle | 17,685,302 | 18,039,008 | 4 | false |
| `debt-c3-…\|urban\|rich_peasant>…` | urban rich | 140,500 | 143,310 | 4 | false |
| `debt-c4-…\|urban\|middle_peasant>…` | urban middle | 335,583 | 20,887,353 | 5 | false |
| `debt-c4-…\|urban\|poor_peasant>…` | urban poor | 431,833 | 26,529,212 | 5 | false |
| `debt-c4-…\|urban\|rich_peasant>…` | urban rich | 142,333 | 8,707,950 | 5 | false |
| `debt-c4-…\|urban\|landlord>…` | urban landlord | 0 | 123,728 | 5 | false |
| **列合计** | | **52,951,302** | **109,330,627** | | |

> 列合计口径：@361 是 day361 当天首次放贷后（c4 新条已出现）；tick360 时的存量只有 c3，为 **52,041,553**。

### T7. 月度社会回写（4 次）

| day | 全球 births | 全球 deaths | changes | 本格 births | 本格 deaths |
|---|---:|---:|---:|---:|---:|
| 390 | 58,835 | 36,249 | 5,739 | 120 | 62 |
| 420 | 58,896 | 38,578 | 5,744 | 129 | 60 |
| 450 | 58,317 | 41,484 | 5,751 | 90 | 60 |
| 480 | 56,316 | 45,249 | 5,787 | 68 | 126 |
| **合计** | **232,364** | **161,560** | — | **407** | **308** |

社会总人口 11,544,684 → 11,615,488（+70,804）。

---

## 6. 三分总表（哪些是事实、哪些是推断、哪些没验证）

### 6.1 代码事实（可回代码核）

- `inputSupplier` 缺省/四档默认都落 operator；`drawCycleInputs` 三遍式先自己账、再 relation 指名家户账、再同格争用池。
- `harvest` 净产先记 operator，rules 只在“本周期净产”上限内实付，余额留 operator；`owed` 不落债权。
- 经营者的 GoodsAccount 键 = `(owner, location)`；市场订单与报告都是瞬态的；库存落盘走 `landOperatorGoods`。
- `Debt.debtor/creditor` 是 `CohortKey`；`lendDeficits/repayDebts/chargeInterest` 全按家户键；`defaulted` 无写 true 路径。
- 没有“产业退出/关闭”状态或运行时命令；`economy.Seed` 拒绝已占用格。
- `applyFamine` 默认致死率 0；真正的死亡走 `PopulationDynamics` 月度；出生不增加劳动配额。
- 持久层只有 revision 边界存量；ledger/market report/FlowRow 中间值不落盘。

### 6.2 推断（有代码依据，但未逐值实跑或未全量打印）

- “经营者不会因卖不出去而破产/停产”：因为（a）没有经营者债务通道，（b）没有固定货币成本，（c）投入可由 relation 名下的家户账供给，（d）本周期实测生产持续。**这是解释性推断 + 一个周期的实测，不是对所有世界形态的证明。**
- 700‰ 名义分成 vs 36.5% 实付：由 `shareWithTotal` 分母 = 本格 8 行劳动的代码推出；本报告未直接打印 8 行劳动总和。
- 城镇债务人还不出粮的可能机制：预算只认 `income[grain]` 与当前库存；城镇行的实际 FlowRow 未打印。
- 下一周期（481+）能否继续：day480 收获给了 fiber、capacity 仍在、operator 账有新布；但下一次现扣/劳动重排没有实跑。

### 6.3 用户目标（任务书要求，本报告不替用户裁决）

- 用户要问“订单减少如何传递到破产与生存”。本报告只回答“在现有代码里，这个传递通道有什么/缺什么”，**不提出**应该怎么改。
- 用户明确要求“不许推测已经发生破产”。本报告没有说任何主体已经破产；唯一能说的是“经营者破产态在代码里没有表示，故不可判定”。

---

## 7. 我没做 / 没验证的

1. **没改任何 `src/**`、`docs/**`、台账**；没 `git add/commit`；没跑 Maven；没跑完整一年。
2. **只跑了 361–480 一个 120 天周期**（398.4 s），没有跑 481+ 的第二个周期；因此“下一周期是否继续生产/继续挂单”未验证。
3. **只选了一个经营者**（`weave@-34_-65`）与它的 4 个农村关联 cohort；没有对别的区/别的 weave 经营者做对照；因此“区域代表性”只来自 dump 排名（最大布未成交区），不是随机/统计样本。
4. **没有做订单减少的受控实验**：本轨迹观测的是一个“订单已经很少、卖单大量未成交”的区；没有先跑“高订单”基线再人为减少订单。因此“订单减少的因果”只能由“现有订单本就极少而生产照常”间接说明，**不是对照实验**。
5. **没有逐日 `OwnershipBooks.fold/apply`**：只在最后做了一次 `land*` 演示；最终 8,191 本 actor 账只对 operator 和 4 个农村 cohort 逐值核到 `session==landed`，其余未全量核对。
6. **没有把任何 361–480 状态写回 store**；因此没有 tick480 的持久 revision 可查；480 的库存/债务只在本报告与 `/tmp/hprobe-run-H.jsonl`。
7. **没有打印城镇 4 个债务人的 FlowRow**（income/consumed/unmet/newBorrowing/repaid/interestDue/deaths），所以“城镇债务人为什么一笔不还、是否挨饿”**不可判定**；T6 只证明地主债权在增长、没有还款转移。
8. **没有打印各 cohort 的 physiologicalStress**；死亡/出生只用了 `PopulationDynamics.monthly` 的输出与 `FlowRow.births/deaths`，没有独立核对“压力是否 0”“死亡是否全部基础死亡率”。
9. **没有实测 R6 截断**（净产<应付）与 `applyFamine` 非 0 致死率；这两条只按代码读，未构造实验。
10. **没有实测停产/重开、capacity 修改、relation 覆写**；这些在现有 main 没有运行时入口，本报告按静态阅读记，未跑命令。
11. **没有全仓 grep 所有写 `Debt.defaulted` 的路径**；结论“无 true 写入”基于 main 的 `grep defaulted`（只有读取/复制）与 `new Debt` 两处（一处写 false、一处复制原值）。
12. **没有验证 §4.1 的 changeset 解析对所有账户有效**：我们只抽读了 operator/贫农两本账与组件标记；没有全量反序列化重放后与 dump 对账。
13. **服务未起**（本次不需要 GUI/MCP）；没有任何服务 PID 需要关闭；原 store 未被写，`git status --porcelain` 在写本文件前为空。
14. **未对 B-determinism-bidask.md 逐条复核**；本报告只引用了 A/C/D 中与本问题直接相关的口径。

---

## 附：临时物与复现入口（均在 /tmp，重启即失）

- 探针源：`/tmp/hprobe/io/mosire/simos/economy/time/HProbe.java`；编译产物 `/tmp/hprobe-classes`。
- 运行输出：`/tmp/hprobe-run-H.jsonl`（122 行），stderr：`/tmp/hprobe-run-H.err`；墙钟 day480 行 = 398.4 s。
- store 副本：`/tmp/hstore`（`cp -a` 自 `.superpowers/sdd/2026-09-27-m2-general-market/store-m2`）；原 store 未写。
- 本报告的表 T1–T7 由 `/tmp/hprobe-tables.md` 的生成脚本逻辑从 JSONL 复算；解析 SQLite 的只读脚本命令在本报告 §4.1 描述（Python `sqlite3` + `file:...?mode=ro`）。
