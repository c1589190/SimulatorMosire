# 只读调查账本 —— `simos.advance` 分段推进为什么不是路径无关

> 任务：只读诊断（不改任何代码/测试/文档；只起世界跑数）。日期 2026-10-10。
> 交账对象：A8 §5-1 / §6-6 的未决项（"是 HEAD 既有还是新近引入"+"根因未定位"）。

## 0. 装置与身份（本轮自建，不复用 A8 的运行）

| 项 | 值 |
|---|---|
| jar | `simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar`，md5 `f5e824871d84131ee62e18cfeb8929fd`（= A8 同一份） |
| 源码新鲜度 | `find . -name '*.java' -newer <jar> \| wc -l` = **0** ⇒ 跑的就是 HEAD 码 |
| 我的 runner | `/tmp/path-run.sh`（自带 `md5(self)` 自指行）；每个世界独立 store `/tmp/simos-path-<tag>` + 独立端口（9931/9935/9933 起，步进 10） |
| 世界 | `--world=small-world`（与 A8 同）；`JAVA_TOOL_OPTIONS=-Dsimos.economy.logLevel=DEBUG -Dsimos.economy.traceLevel=TRACE`（对照轮 c1 除外的 INFO 档） |
| 读数仪器 | `/tmp/g3-dump.py`（19 格 `simos.economy.hex` + `simos.gov.info` + `/api/economy/overview`），对照器 `/tmp/a8-diff.py`（A8 的递归逐叶） |
| 串行纪律 | 全部实例**一次一个**跑（`/tmp/path-chain.sh` → `/tmp/path-followup.sh` → `/tmp/path-bracket.sh`）；未触碰在跑的服务（pid 2081，端口 6111/6115）与共享 jar |

★ **仪器可复现性**：我的 1 段 dump 与 3 段 dump **与 A8 的产物逐字节相同**
（`dec2aa2cbde5b879d787371932f27e1a` = A8 `a8-state-b2-360.json`；
`845fd183b2d266d95dd6546f87e9dcbf` = A8 `a8-state-b3-360.json`）⇒ 装置复现成立，下面的数字可与 A8 直接对齐。

## 1. ★① 复现数字（tick 360 的 19 格 dump 逐叶对照）

| tag | 路径（MCP `simos.advance` 的分段） | dump md5 | vs 1 段差异叶 | vs 3 段 | 日志行 |
|---|---|---|---:|---:|---:|
| s1 | **1 段** 0→360 | `dec2aa2cbde5b879d787371932f27e1a` | 0 | 18,538 | 378,041 |
| s3 | **3 段** 0→120→240→360 | `845fd183b2d266d95dd6546f87e9dcbf` | **18,538** | 0 | 379,918 |
| s12 | **12 段**（每 30 天） | `f0e60bce5c421564c3bdd68ad9817696` | 24,710 | 24,370 | — |
| s360 | **360 段**（逐 tick） | `c1f97b0a9f7ffb30e47ea3eaa91b868d` | **25,673** | **25,538** | 389,549 |

- **A8 的两条数字（18,538 / 25,538）逐值确认**。
- **更正 A8 的一条**：A8 记"1 段 vs 360 段 = 24,456"，我用**同一支仪器**在同一 tick 测得 **25,673**。
  A8 的 B1 tick-360 dump 未留在 `/tmp`（现存 `/tmp/a8-b-daily.jsonl` 是 `{tick,rev,total,byhex,byhh}` 另一种形状，
  不是 g3-dump），无法复核它的 24,456 ⇒ 以本行 25,673 为准。
- 3 段与 360 段 **差 25,538 = A8 原值**；1 段与 3 段 **18,538 = A8 原值** ⇒ 该 24,456 是 A8 三条里唯一没复现上的一条。

## 2. ★② 第一次分叉（三分法定位）

用 `/tmp/path-firstdiff.py`（逐日比较**完整行内容**，剔时间戳、剔"每次 advance 各发一次"的边界事件）：

| 对照 | 第一个真正分叉的 tick | 该 tick 里第一条不同的事件 | 实得 |
|---|---|---|---|
| s1(1 段) vs s3(3 段) | **day=150**（同日第 2,167 条日志行） | `LIQUIDATION_PLAN`（origin=economy-settlement） | 1 段 `audits=16 declines=0 stressUpdates=77` / 3 段 `audits=92 declines=76 stressUpdates=78` |
| s1(1 段) vs s360(逐 tick) | **day=60** | `LIQUIDATION_PLAN` | `audits=154 declines=27` / `audits=203 declines=76` |
| s1(1 段) vs s12(12 段) | **day=60** | 同上 | 同 s360 |
| t1(1 段 0→60) vs t2(2 段 0→30→60) | **day=60** | `LIQUIDATION_PLAN` | `audits=154 declines=27` / `audits=203 declines=76`；**dump 差 2,538 叶** |
| t1(1 段 0→60) vs t3(2 段 0→5→60) | **day=30** | `PRIMARY_MODE_RANKING_ROW`（origin=economy-migration） | day=30 全天仅 10 行不同：8×`PRIMARY_MODE_RANKING_ROW.yieldPerLaborScaled`（3 行 `reason: DEMAND_CAPPED → 无`）+ 2×`PRIMARY_MODE_CHANGED.fromYieldPerLaborScaled` |

- **分叉 tick 不是固定的**：它**跟着分段位置移动** —— 边界在 day 1 / 5 / 29 都让首个"关账日"（day 30）读数变掉；
  边界在 120/240 让它出现在 day 150（= 第 2 段的第一个关账日）。
  ⇒ 排除"绝对日历相位"（若是相位问题，分叉 tick 不会随分段位置平移）。
- **叶子键路径（状态面）**：`<hex>.classes[k].classStanding.consecutiveDebtStressCycles`
  （t2 里 1→2；连带 `.classStanding.currentPositionId / lastTransitionDay / reason` = 阶层真的下滑了）。
- **子系统归属**：18,538 叶按字段归并的头部**全部落在经济—债务**这一族：
  `classes[].debtDetails[].{id 1,318 / counterparty 1,270 / dueCycle 1,089 / openedDay 938 / lastInterestDay 736 / status 537 / principal 446 / unitKey 402 / …}`
  + `classes[].debts[] 551` + `classes[].credits[] 440`，其后是下游的 `assetShares[] / crisisSignals[].households / classifications[].reason /
  industries[].units[].condition.unsoldStockMilli`。⇒ **债务（E5b 清算/阶层下滑）**，不是社会人口/迁移/日历本体。

## 3. 根因（两条独立来源，第 1 条已追到行）

### 3.1 ★★ 主源：E5b 的"连续债务压力周期"计数器以**本次 advance 的基态**为增量基准

`EconomyLiquidationSettlement`（关账日跑，small-world 实测 30/60/…/360 各一次）里：

- `:89` `DEBT_STRESS_CYCLES_THRESHOLD = 2L`；`:477`（触发合同）/`:538-545`（阶层下滑）/`:649`（债务爆炸读数）三处都拿它当门。
- `:351-353` `EconomyData base = context.base(); Map<...> baseClassMemberships = base.classStandings();`
- `:428-434`
  ```java
  long previous = baseClassMemberships.get(household) == null ? 0L
      : baseClassMemberships.get(household).consecutiveDebtStressCycles();
  long next = counterStress && resolvable.isPresent() ? previous + 1 : (counterStress ? previous : 0);
  ```
- `:456-457` `StressUpdate(household, previous, next, …)`；`:1140-1161` 落盘写的是 **`update.nextCount()`**（`withStressCount(existing, next)`）。

`context.base()` = `EconomySession.base()`（`EconomySession.java:69-71`）= `EconomySettlement.java:1249` 的
`EconomyData base = session.base()` = **本次 `advance` 起点那份快照**（`range.from` 的那个 revision）。

**语义（用来判 bug/语义的分水岭，代码自己写明）**：`EconomyLiquidationSettlement.java:52`「E5b 在 `anyCycleClosed == true` 的**关账日**推进」；
`:64-67`「`consecutiveDebtStressCycles` 统计**连续**出现"任一合同受压 / F 不足下一轮投入"的**关账周期数**……是 E5b 明确允许的**等价持久判据**」；
`:88`「连续债务压力周期数（**2 个关账周期**）」⇒ 该字段的定义域 = **世界的关账周期数**，与"调用方把 360 天切成几段"无关。
以本次调用基态为增量基准，"关账周期数"就被偷换成了"advance 调用次数"。
于是 **`next` 恒等于 `基态值 + 1`**，与"这一次调用里已经跑过多少关账日"无关：
一次推 360 天里连跑 12 次关账，计数器也只能是 `0/1`（阈值 2 永远够不到）；
把同 360 天拆成 N 次调用，它就能长出 N。

**对照物（同族机制的"正确写法"）**：`OperatorCondition.consecutiveDebtStressCycles` 由
`OperatorSettlement.java:375` 从**工作副本**读 `prev`（`EconomySettlement.java:1329-1330` 传入 `session.sheet().operatorConditions()`）
⇒ 它在 1 段路径里**照样长**（`s1` 实测 {0:198, 1:2, 2:3, 3:2}）。同一个"连续周期计数"模式，两个实现，一个活一个死 —— 这就是判据。

**实测（`classStanding.consecutiveDebtStressCycles` 取值分布，同 tick 60/360）**：

| 路径 | 分布 | 上限 |
|---|---|---:|
| t1（1 次调用，0→60） | `{0:106, 1:67}` | **1** |
| t2（2 次调用，0→30→60） | `{0:115, 1:1, 2:57}` | **2** |
| s1（1 次调用，0→360） | `{0:73, 1:100}` | **1** |
| s3（3 次调用） | `{0:97, 1:33, 2:1, 3:57}` | **3** |
| s12（12 次调用） | `{0:88, 1:24, 2:32, 4:9, 6:2, 8:45, 10:12}` | **10** |

⇒ **上限 = 段数**（受"该户是否连续受压"限制）。1 段路径下阈值 2 **不可达** ⇒
"连续压力 ⇒ 触发清算/阶层下滑"这一整条 E5b 支路在**单段推进下是死的**：
s1 的 day-150 `declines=0` 不是世界稳态，是分支没被打开；t2 只是"多调了一次 advance"就让 49 户（27→76）多下滑了一级。

### 3.2 次源（**实测存在、锚点未追到行**）：任何分段都会在**首个关账日**改掉主业排序读数

t1(0→60) vs t3(0→5→60) / u1(0→1→60) / u2(0→29→60)：三条**互相不同**、且都与 t1 不同
（md5 `a8b05bf5…` vs `3173d779…` / `1b2cfeff…` / `160b5beb…`），
**第一次可见分叉都在 day=30**，都在 `PRIMARY_MODE_RANKING_ROW.yieldPerLaborScaled`
（`hh-0_0-urban-rich_peasant`: 25 → 525 且 `DEMAND_CAPPED` 消失；`hh-0_0-urban-landlord`: −30 → −328）。
同行的 `bargainingPowerPerMille` / `inventoryMilli` 逐值相同、day=30 的 `MARKET_FILL` 与 `LIQUIDATION_PLAN` 也相同
⇒ 差异在这些读数**上游的某个"按调用锚定"的输入**（候选：`MarketTopologyBook.from(state)` 每次调用现算、
`stepper.updateMarketExcludedHouseholds(unitHouseholdExclusions(units))` 用的 `units` 是**调用起点快照**、
`OwnershipBooks.loadAccountSession` 的账户往返），**未逐一定位**（见 §8-3）。
这是**同一族反模式**（日循环里读"调用起点"而不是"当刻工作副本"），与 3.1 的修法纪律同源。

## 4. ★③ 根因候选逐条：支持 / 排除

| # | 候选 | 判定 | 判据（代码 file:line + 实测） |
|---|---|---|---|
| ① | `advance` 每次做**段边界处理**（DAY_START / 周期关账 / 相位对齐 / 首日跳过 / 结算器初始化） | **部分支持，但不是主因** | 每次调用确有 prologue（`PopulationEconomyTimeParticipant.java:543-611`：拓扑/组成/劳动预算/市场排除集/flows 基线），但全部由 `base` 纯派生；`t3`(边界在 day 5，第一个关账日之前) **state 仍与 t1 不同**（`3173d779…`≠`a8b05bf5…`）⇒ 边界本身有可观效应，但效应不在"结算器初始化"上（后者逐值可复算）。主因是 3.1 的"以基态为增量基准" |
| ② | **按调用重置 / 以调用基态为基准的累加器**（人口生死余数、市场余数、损耗累计、时间事件队列…） | **★ 支持（主根因）** | `EconomyLiquidationSettlement.java:352 + 428-434 + 1140-1161`（详见 §3.1）；实测上限 = 段数（§3.1 表）；分叉 tick 随边界位置平移（day 60 / 150）且与"第 2 段的第一个关账日"逐条吻合 |
| ③ | **日历/相位**依赖调用次数（年/月/日/周期只看绝对 tick？） | **排除** | `CalendarClock` 全 final 字段（`CalendarClock.java:21-23`），`dateOfTick(long tick)`/`daysInYearAtTick(long tick)` 是 tick 的纯函数；`HouseholdBook.settleOneTick(currentSocial, day, …)`（`PopulationEconomyTimeParticipant.java:616-617`）传**绝对 day**；实测分叉 tick 随分段位置平移（60/150），不是某个绝对相位 |
| ④ | **worker/分区/缓存按调用边界重建**（`copyForWorker` 一族） | **排除** | 本装置的 workerCount = **1**：`Shell.java:900` `new PopulationUnitTimeParticipant(config.mapId())` → `PopulationUnitTimeParticipant.java:52-54` `this(mapId, 1)`；`EconomyParallelism.of(≤1)` 走**单线程退化路径**（`EconomyParallelism.java:56-60`，同一份 PartitionPlan/commit）⇒ 观察到的差异与并行无关 |
| ⑤ | **日志档位/顺序差异污染状态** | **排除（负向对照实测）** | 同一 3 段路径、经济日志降到 INFO 档：dump md5 `845fd183b2d266d95dd6546f87e9dcbf` **与 DEBUG/TRACE 档逐字节相同**（c1 日志 4.4 MB/16,295 行 vs s3 140 MB/379,918 行） |

## 5. ★④ 与既有不变量的关系

- **管"分段等价"的那一条不在 `AGENTS.md`，在代码契约里**（`AGENTS.md` 无 I7 原文；它只在 §三.0 提"I1–I7 那一类"，
  以及 line 621 转述 M0.1）：
  - `TimeAdvance.java:71-77`：「**判据是等价性**：`advance(from, from+N)` 的**终态** == N 次单日 advance 的终态（事件链可以不同）」；
  - `AdvanceTool.java:33`：「结算语义不跳日：各参与者在这一次推进内部**逐日**推进（等价性见 Core 与 e2e 护栏）」；
  - ★★ `PopulationEconomyTimeParticipant.java:97-98`：**把这次踩的坑逐字写成了反模式** ——
    「§十一 等价性要求"逐日"落在同一个参与者内部 —— **若让两个参与者各自读对方的基态**，一次推 365 天时只能看到第 0 天的状态，
    而 365 次单日推进每天都能看到前一天的 ⇒ **两条路径必然不等价**」。
    E5b 犯的是同一条：**在日循环里读自己这次调用的基态**。
- **"路径无关"旧结论的出处与规模差异**：
  - 出处：`.superpowers/sdd/2026-09-27-m0-instrument/progress.md:186`（"一次推 0→360 与分段推 0→120→240→360 实测同值，都是 18,042,000"）
    与 `:199`（"M0.1 已逐值证明 360 == 120×3（`EconomySettlementEndToEndTest`，economy 与 actor 两层终态逐值相等）⇒ **路径无关**"）。
  - **当时的装置**：`simos-app/src/test/java/io/mosire/simos/app/world/EconomySettlementEndToEndTest.java`，
    夹具 = **5 格测试世界** `EconomyTestWorld`，断言 = economy+actor **两片终态逐值**（现值之和/纤维账户那一族读数）；
  - **今天**：`small-world` **19 格** / 360 天 / **全量 19 格 dump 18,538 叶**，含 E5a/E5b 之后才有的
    `classStanding.consecutiveDebtStressCycles` 这类**跨周期累加器**。
  - ★ **`EconomySettlementEndToEndTest` 已于 `b243e3d9`（2026-09-30，"R3a 删除旧结算运行时"）删除**；
    现存测试树按"等价/一次推/360 == 120×3"关键词扫描**未见**分段等价对照用例（未逐类通读，见 §8-4）。
- **git 归因（回答 A8 §6-6"是 HEAD 既有还是新近引入"）**：**不是基线，是 E5b 引入的**。
  `git log --diff-filter=A` → `EconomyLiquidationSettlement.java` 首现于 **`0b75bc61`（2026-09-29 23:59 E5b）**，
  且该首版第 379-382 行**已经是** `baseStandings.get(household).consecutiveDebtStressCycles()`；
  而 M0.1 的结论在 **`b467049a`（2026-09-27）** —— 早两天。⇒ **旧结论在旧码上成立，不是"当时就错"**；
  路径无关是被 `0b75bc61` 之后的代码破坏的。

## 6. ★⑤ 判定：**bug（该修）**，不是"分段=会话边界"的语义

1. 它直接违反**写明的契约**（§5 三条）：契约要求"一次 N 天 == N 次单日"的**终态**，实测 18,538~25,673 叶不等。
2. 它不是"边界效应的合理代价"：1 段路径把 `DEBT_STRESS_CYCLES_THRESHOLD=2` 变成**不可达**，
   E5b 的"连续压力 ⇒ 触发清算/阶层下滑"整条支路**在单段推进下永不开火**（s1 的 `declines` 只有 day-60 的 27，
   其余全 0；t2 同一天 76）。**是功能没生效，不是"读数差一点"**。
3. 反方向的代价同样真实：真实世界用"每天一次 `simos.advance`"跑，计数器**每天长 1**（上限 = 调参），
   与"每关账日长 1"的语义也不符 —— 两条路径都错，只是错法不同。
4. 副产物：任何"跨轮/跨版本对照"若不同路径就会把**路径效应**读成**代码效应**（A8 已发现这一点）。

## 7. ★⑥ 处置建议（只给方案，本轮不改代码）

**若修（建议）**

- 最小修法（1 处 2 行，不改契约、不改状态形状）：`EconomyLiquidationSettlement.plan()` 的
  `:352-353` 把 `base.classStandings()` / `base.classPositions()` 的**归属表**换成**当刻工作副本**
  `context.session().sheet().classMembershipsOrBase()`（`EconomyStateBuilder.java:286-295` 就是为此存在的口）；
  `:1137-1138`（`writeStressAndDecline` 里同名读法）同步改。**`classPositions` 是静态模板，可不动**。
- 同批**审计同族直读点**（都是"日循环里读 advance 基态"）：`EconomyLiquidationSettlement.java:958`
  （`preferredMode(… base.classStandings() …)`）、`ExpectedProfitBook.java:974`（`currentPositionOf`）、
  `MerchantCapacityPool.java:566/584/634`、`ModeMigrationSettlement.java:857`；
  以及 §3.2 的候选锚点（`MarketTopologyBook.from(state)` 的每次调用现算、`units` 快照、账户往返）。
- **验收判据（可执行、判别力足）**：
  1. `0→60` 一次 vs `0→30→60` 两次，tick-60 的 19 格 dump **md5 逐字节相同**（今天 `a8b05bf5…`≠`ac8e00c9…`，差 2,538 叶）；
  2. `0→360` 的 1/3/12/360 段四份 dump **md5 逐字节相同**（今天四份互不相同：18,538 / 24,370 / 25,538 / 25,673 叶）；
  3. 1 段路径下 `classStanding.consecutiveDebtStressCycles` **出现 ≥2 的样本**（今天恒 ≤1），
     且 12 段与逐 tick 的取值分布相同（语义 = 每关账日 +1，与调用次数无关）；
  4. **变异自证**：把 `classMembershipsOrBase()` 改回 `base.classStandings()` ⇒ 上述 1/2/3 必须当场红；
     还原 ⇒ 绿 + md5 回原值。
- 是否顺手加"跨路径对照"守卫：把上面 (1)(2) 写成 e2e 用例（现无此类用例，见 §5）。

**若判为语义（不修）**

- 那就要在 `simos.advance` 的**契约面**写明"分段=会话边界，允许边界效应"，并**同时**做到：
  ① 把 `TimeAdvance.java:71-77` 的等价性判据与 `PopulationEconomyTimeParticipant.java:97-98` 的反模式注释**一起改写**
  （现在它们明说相反的话，不改就是自相矛盾）；② 在 `AGENTS.md` 加一条"**跨轮对照必须同路径**"
  （A8 已在 `docs/superpowers/status/2026-10-10-resume-points-and-backlog.md:410` 写了方法论）；③ 显式声明
  E5b 的"连续压力阈值"在单段推进下不可达、即该支路默认不生效。
- **成本对比**：① 要求把 3.1/3.2 两个"锚定"都当成规格写死，并接受"同一世界按不同分段跑出不同历史"；
  收益只是省掉一处 2 行修改 ⇒ **不建议走这条**。

## 8. ★⑦ 未验证 / 构造不出（如实）

1. **A8 的 24,456 未复现**（见 §1）；我给的 25,673 是同一支仪器同一 tick 的读数，A8 那条的装置形态无法复原。
2. **§3.2 的锚点未追到行**：只证到"任何分段都会在首个关账日改掉主业排序读数"，未证明是
   `MarketTopology` / `units` 快照 / 账户往返中的哪一个；未做该子问题的二分。
3. **sd 切片未测**：dump 只覆盖 19 格 economy + gov + overview；`SdTimeParticipant` 侧是否有独立的路径依赖**未验证**。
4. 未逐个测试类通读，只按关键词扫描"是否还有分段等价的守卫用例"；"现无守卫"是扫描结论，不是证明。
5. 未跑其它世界（`three-powers` / `corridor` / `v17levant` / 3600 tick）与 GM-FX 补丁世界；
   未验证 3.1 的机制在 120 天周期世界（`CYCLE_DAYS=120`）里是否同样只受关账日驱动。
6. 未做代码级变异自证（本轮零代码改动；判据强度靠"三条预测被实测逐条命中"提供：
   逐 tick/12 段应在 day 60 分叉、120/240 分段应在 day 150 分叉、2 段应在 day 60 分叉 —— 全部命中）。
7. 未跑 `test`/`verify`/SpotBugs/前端门禁（只读调查，且跑测试会动 `target/`）。

## 9. 复现命令（我方装置）

```bash
# 装置（独立 store / 端口；一次一个实例）
bash /tmp/path-chain.sh      # s1(1段) s3(3段) t1(1段0->60) t2(2段) s12(12段) s360(逐tick)
bash /tmp/path-followup.sh   # t3(0->5->60) + c1(3段/INFO 档，⑤ 负向对照)
bash /tmp/path-bracket.sh    # u1(0->1->60) u2(0->29->60)
# 读数
python3 /tmp/a8-diff.py /tmp/path-state-s1.json /tmp/path-state-s3.json      # 18,538
python3 /tmp/a8-diff.py /tmp/path-state-s1.json /tmp/path-state-s360.json    # 25,673
python3 /tmp/a8-diff.py /tmp/path-state-s3.json /tmp/path-state-s360.json    # 25,538
python3 /tmp/path-firstdiff.py /tmp/path-runs1.log /tmp/path-runs3.log 360   # day=150 LIQUIDATION_PLAN
python3 /tmp/path-firstdiff.py /tmp/path-runt1.log /tmp/path-runt3.log 60    # day=30  PRIMARY_MODE_RANKING_ROW
md5sum /tmp/path-state-c1.json /tmp/path-state-s3.json                       # 相同 ⇒ ⑤ 排除
```

**产物**：dump `/tmp/path-state-{s1,s3,s12,s360,t1,t2,t3,u1,u2,c1}.json`；
日志 `/tmp/path-run{tag}.log`；store `/tmp/simos-path-{tag}`；脚本 `/tmp/path-{run,run-ctrl,chain,followup,bracket,firstdiff,daily2,cat}.{sh,py}`。
