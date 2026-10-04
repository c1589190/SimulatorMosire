# 2026-10-04 经济系统全量 Log 化设计

> 用户裁定（原话）：「讨论个屁啊，先把经济系统全量Log化，把日志记录给我做全了，并且在项目文档里写明
> 其他模块没有加Log这次调试完记得加」。
>
> 本文是实现前的完整设计，也是实现后的验收清单。**追加式**：后续改口径不删本文，追加版本条目并标注取代关系。

## 0. 一句话

给经济系统（`simos-economy` 主链 + `simos-app` 经济协调器）建立**分类 logger、三档级别、结构化 event 行**的日志体系，
做到"只看日志就能回答：这个 tick 先做了什么、后做了什么、每一步的池子/汇总/拒绝理由是什么、逐笔发生额是谁跟谁"；
日志**只读**、不改任何状态、不把载荷明文写进去。

## 1. 需求来源与非目标

### 1.1 需求来源
- 2026-10-04 用户裁定：经济系统全量 Log 化，日志做全；其他模块没加 Log 要在项目文档写明，经济调试完记得补。
- 直接痛点：排查"12hex 市场后段冻结/借贷不触发"时，`simos-economy/src/main` **零 Logger**；只能事后读
  `ProductionLedger` 倒推次序与原因，无法回答"第 1 天 45 户缺粮为什么一笔都没借到"这类问题。

### 1.2 目标
1. 经济系统的**每个阶段边界**在日志里有位置（先干什么后干什么）；
2. 每个阶段的**池子/汇总/拒绝理由**可读（DEBUG）；
3. 每次状态变化的**逐笔事实**可对账（TRACE：转移/债务/成交槽/迁移 move）；
4. 级别可整体/分类开关，默认 INFO（不淹没正常输出），调试时一条命令行开满；
5. 日志与状态严格分离：不写状态、不影响公式、不参与变更集/存档；关掉日志行为逐值不变。

### 1.3 非目标（本批不做）
- 不引入结构化 JSON 落盘、不引入日志聚合/外部采集；只做 SLF4J + log4j2 控制台。
- 不做全仓所有模块的日志（其他模块现状与待办见 §8）。
- 不为日志新增持久状态组件/版本位；`ProductionLedger`/`MarketReport` 形状不变。
- 不保证并行分区下的日志行序与串行逐字一致（见 §7.3）。

## 2. 现状与目标

| 项 | 现状（2026-10-04 前） | 目标（本设计） |
|---|---|---|
| Logger | `simos-economy/src/main` 零 Logger | `EconomyLog` 门面 + 分类 logger |
| 日结算 | 无可读输出；只有事后 `ProductionLedger` | 每天 INFO `DAY_START`/`DAY_END`；每阶段 DEBUG；逐笔 TRACE |
| 市场 | 报告只在进程内 feed，无日志 | 开市/闭市 DEBUG、市场汇总 INFO、逐成交/未成交/信用/买卖槽 TRACE |
| 债务 | 无日志 | 建立/减少/计息 TRACE，状态迁移/核销 DEBUG，偿还逐债决策 TRACE |
| 迁移 | 无日志 | 计划/执行 INFO（计数），逐 move TRACE |
| 组织/进入/清算/经营者/商人 | 无日志 | 计数 INFO + 关键池子/状态 DEBUG + 逐条 TRACE（按可读性取舍见 §5） |
| 播种 | worldgen 工具有 summary，引擎无日志 | 播种 DEBUG 汇总（profile/map/张数/资产/债/商号） |
| app 协调器 | 只有零星的并行入口日志 | 每次推进 INFO `ECONOMY_ADVANCE_START/END`；月度人口回写 INFO |

## 3. 组件与数据模型

### 3.1 `EconomyLog`（唯一门面，`simos-economy` root 包）

- `ROOT_LOGGER_NAME = "io.mosire.simos.economy"`；分类 logger 全部挂在它下面，一个开关可整体升降级。
- 取用方法（禁止调用点自拼 logger 名）：

| 方法 | logger | 记什么 |
|---|---|---|
| `settlement()` | `.settlement` | 日结算阶段边界、阶段聚合、收获/饿死/偿还/迁移/清算/经营者/商人/播种/stepper 生命周期 |
| `market()` | `.market` | 开市/闭市/市场轮次 |
| `debt()` | `.debt` | 债务建立/计息/偿还/状态迁移/核销 |
| `migration()` | `.migration` | 生产方式变迁计划与执行 |
| `organization()` | `.organization` | 自动生产组织/租佃/经营者归属 |
| `entry()` | `.entry` | 候选预设进入/拒绝 |
| `population()` | `.population` | 出生/死亡/人口回写/劳动缩放 |
| `trace()` | `.trace` | 逐笔原始事件（转移/成交槽/债务变动/迁移 move/逐户饿死） |

- `kv(Object...)`：`day=3 rows=100` 形式的结构化后缀；奇数参数当场抛（防拼写错误静默丢字段）。
- 类 javadoc 写明级别约定与开关；`TRACE_LOGGER_NAME` 常量保留旧名 `io.mosire.simos.economy.trace` 作为兼容别名。

### 3.2 输出格式

统一为 `<event=EVENT> key=value ...`，例如：

```
INFO  io.mosire.simos.economy.settlement - event=DAY_START day=1 mapId=real-twelve rows=100 population=3500 units=101 markets=11 organizations=0 debtContracts=0 modes=8 topologyRegions=1
TRACE io.mosire.simos.economy.trace      - event=TRANSFER id=tr-1-p1-1 day=1 from=hh-0_0-rural-rich_peasant to=hh-0_0-rural-middle_peasant hex=0,0 reason=input_requisition goods={grain=24897} money={}
INFO  io.mosire.simos.economy.settlement - event=DAY_END day=1 population=3500 deaths=0 unmet=243458 borrowedGrainMilli=0 repaidGrainMilli=0 repaidMoneyMilli=0 debtContracts=0 transfers=37 marketFills=0 marketCreditFills=0 marketUnfilled=0
```

- **只记稳定 id / 数量 / 原因档 / 汇总值**；不记密钥、不记载荷明文、不记配置明文。
- 数值单位保持源代码口径（毫商品、毫银、毫粮、‰）；event 行不自造单位换算。
- 每个 event 的关键字段保持稳定，便于 grep：`event=` 前缀是唯一检索入口。

### 3.3 持久状态/派生件

- **不新增任何持久组件**；日志不是 `EconomyData` 的一部分，不进 `EconomyChangeSet`、不进 `EconomyCodec`、不跨 revision。
- 债务/市场的持久事实仍在 `DebtContract`/`Market`/`ProductionLedger`；日志只是这些事实的**投影**。

## 4. 数据流与调用次序

### 4.1 日结算主链（`EconomySettlement.settleOneDayInto`）

日志与结算阶段的对应（数字即日志前缀序号，与代码阶段注释同序）：

| 序号 | 阶段 | 级别与内容 |
|---|---|---|
| `DAY_START` | 日结算入口 | INFO：day/mapId/rows/population/units/markets/organizations/debtContracts/modes/topologyRegions |
| `00` | 工作副本与索引 | DEBUG |
| `01` | 0-entry 候选预设 | INFO（outcomes/entered/rejected）；TRACE 逐 outcome |
| `02` | 模式变迁到期应用 | INFO（applied/failed）；DEBUG |
| `03` | 自动生产组织 | INFO（createdUnits/organizations/rows）；TRACE created unit ids |
| `04` | 在途到货 | INFO（arrivedBatches/remaining）；DEBUG |
| `05` | 现扣投入 + 自有库存消费 | DEBUG（consumed quantities / deficit households / deficit grain） |
| `06` | 周期末收获/分配 | INFO（closedUnits/gross/losses/inputs/outputAccruals/transfers/ruleSettlements）；DEBUG 逐产业 gross/losses/inputs |
| `07` | 欠租/欠薪资本化 | INFO（capitalized/unresolved） |
| `08` | 市场调度 | DEBUG（trigger/anyCycleClosed/markets） |
| `09` | 市场信用额度 | DEBUG（rowsWithHeadroom/headroomTotal/unpricedDebtRows） |
| `10` | 市场轮结果 | INFO（fills/immediateCrossHex/损耗/unfilled/reasons/creditFills/creditMoney/creditGoods/tariff）；DEBUG 同；TRACE 逐 fill/unfilled/creditFill/sellerOutcome/buyerOutcome |
| `11` | 缺口借粮 | 有借出 ⇒ INFO（loanTransfers/lentGrain）；始终 DEBUG；逐笔转移走 `.trace` |
| `11a` | 逐 hex 借粮池 | DEBUG（lendableLenders/lendableTotal/debtors/debtorsWithGrainStock/debtorsWithHeadroom/headroomTotal/sampleDebtor+capacity） |
| `12` | 偿还 | 有发生 ⇒ INFO（households/repaidGrain/repaidMoney/skippedMediums/debtContracts）；DEBUG；逐债决策 TRACE（`DEBT_REPAY_DECISION`/`DEBT_REPAY_UNPRICED`） |
| `13` | 饿死 | 有死亡 ⇒ INFO（closedUnits/deaths/unmet）；DEBUG；逐户 TRACE（`FAMINE_DEATH`） |
| `14` | 周期末计息 | 有利息 ⇒ INFO（households/interestDue/debtContracts）；DEBUG |
| `15` | 经营者退出 | INFO（exits/households）；TRACE 逐 exit；DEBUG |
| `16` | 清算/阶层下滑 | INFO（audits）；DEBUG 计划/执行计数 |
| `17` | 阶层写回 | INFO（transitions）；DEBUG |
| `18` | 流水写入 | DEBUG |
| `19` | 利润汇总 + 迁移计划 | 有 move ⇒ INFO（moves/organizations/modeHexProfits）；TRACE 逐 move；DEBUG |
| `20` | 迁移执行 | 有 move ⇒ INFO（moves/rowsBefore/rowsAfter/transfers）；DEBUG |
| `21` / `DAY_END` | 日结算结束 | INFO：population/deaths/unmet/borrowed/repaid/debtContracts/transfers/marketFills/marketCreditFills/marketUnfilled；DEBUG 按 reason 的转移计数/货量/钱量 |

### 4.2 逐笔事件的唯一来源

- **每一条转移**在唯一铸造口 `ProductionLedger.Accumulator.mint(...)` 落 `event=TRANSFER`（`.trace`）。
  覆盖：关系实付、投入征调、借粮、偿还、市场买卖（一对）、运费。
- **每一笔债务变动**在唯一写口 `DebtContractBook` 落 `DEBT_UPSERT`/`DEBT_REDUCE`/`DEBT_INTEREST`（`.trace`）
  与 `DEBT_STATUS`/`DEBT_FORGIVE`（`.debug`）。
- **每个市场成交/未成交/信用成交/买卖槽**在 `EconomySettlement` 消费 `MarketReport` 时落 `.trace`
  （报告是唯一事实来源，不在撮合内部再拼一份）。
- **每个迁移 move** 在执行前从 `MigrationPlan` 落 `.trace`（计划是唯一事实来源）。
- **每户饿死**在 `applyFamine` 调用点后落 `.trace`。

### 4.3 app 协调器

- `PopulationEconomyTimeParticipant.simulateWorld`：INFO `ECONOMY_ADVANCE_START/END`（区间、天数、worker、户数/人口/债/市场）。
- 月度人口回写：INFO `POPULATION_WRITEBACK`（births/deaths/households/population）。
- `EconomyDayStepper.step`：DEBUG `STEPPER_STEP`（rows/units/transfers/marketReport/shipments）；
  `applyPopulationChange`/`applyMigrations`：非空时 INFO。
- `EconomySeeder.plan`：DEBUG `ECONOMY_SEED`（profile/map/张数/资产/债/质押/商号/禀赋）。
- `MarketTopologyBook`、`EconomyDayFeed`、`MarketReportFeed` 本批不加（拓扑在 DAY_START 的 topologyRegions + 市场轮 START 可见；
  feed 只是投递，投递事实已由 `recordMarketReport`/`step` 日志表达）。

## 5. 各模块日志覆盖与取舍

| 类 | 日志 |
|---|---|
| `EconomySettlement` | §4.1 全阶段 + 逐笔引用链 |
| `ProductionLedger.Accumulator` | 每笔转移 TRACE |
| `DebtContractBook` | 债务所有写口 |
| `MarketSettlement` | 市场轮 START（trigger/市场数/区数/商号数/参与户/信用借款人数）DEBUG；结果由报告消费点记录 |
| `ModeMigrationPolicy`/`ModeMigrationSettlement` | 计划/执行在 `EconomySettlement` 记录；move 逐条 TRACE |
| `OperatorSettlement` | 状态机 END DEBUG（closingUnits/exits/statusCounts） |
| `EconomyLiquidationSettlement` | 计划/执行 DEBUG（stressUpdates/debtReductions/pledgeUpdates/audits/declines/explosions/autoDefaults） |
| `MerchantSettlement` | 周期 START/END 与逐商号 DEBUG（revenue/wages/upkeep/arrears/profit/capacity/porters） |
| `EconomyDayStepper` | step/人口回写/迁移 INFO/DEBUG（§4.3） |
| `EconomyOrganizationSettlement` | 结果在 `EconomySettlement` 记（createdUnits ids TRACE）；`organizeOne` 内部逐户决策**未记**（见 §9） |
| `EconomyEntrySettlement` | plan/execute 结果在 `EconomySettlement` 记，逐 outcome TRACE；评估内部逐候选理由**未逐条记**（outcome.reason 已在 TRACE 行） |
| `EconomyModeTransitionSettlement` | Outcome 在 `EconomySettlement` 记（applied/failed INFO）；逐条 transition 成功/失败 TRACE（`MODE_TRANSITION_APPLIED`/`MODE_TRANSITION_FAILED`，含失败 reason） |
| `DebtCapacityBook`/`DebtValuation` | 纯计算，不做逐户日志（逐日结算里会对同一家户多次调用）；容量池在 §4.1 的 09/11a 汇总，还款逐债决策在 12 |

## 6. 配置与开关

`simos-app/src/main/resources/log4j2.xml`：

```xml
<Properties>
  <Property name="economyLevel">${sys:simos.economy.logLevel:-INFO}</Property>
  <Property name="economyTraceLevel">${sys:simos.economy.traceLevel:-INFO}</Property>
</Properties>
...
<Logger name="io.mosire.simos.economy" level="${economyLevel}"/>
<Logger name="io.mosire.simos.economy.trace" level="${economyTraceLevel}"/>
<Root level="info"><AppenderRef ref="Console"/></Root>
```

打开完整日志：

```bash
# 只开主分类到 DEBUG（阶段池子/汇总/理由）
./mvnw -pl simos-app -am -Dtest=... -Dsimos.economy.logLevel=DEBUG test

# 再开逐笔 TRACE（转移/成交槽/债务变动/迁移 move）
./mvnw -pl simos-app -am -Dtest=... \
  -Dsimos.economy.logLevel=DEBUG -Dsimos.economy.traceLevel=TRACE test
```

- `simos-economy` 单模块测试的 classpath 没有 log4j2 实现 ⇒ 日志 no-op；要看到日志走 `simos-app`（组合根持有日志实现）。
- 默认 INFO：正常 3650 天运行约每日 2 条 + 事件行，可接受；需要安静时把 `-Dsimos.economy.logLevel=WARN`。

## 7. 不变量与纪律

1. **日志只读**：不写任何工作表/持久表；关掉日志行为与产物逐值不变。
2. **不泄密/不明文**：不记密钥、不记载荷明文；只记 id/数量/原因/汇总。
3. **不新增第二事实源**：日志从既有权威对象（`ProductionLedger`、`MarketReport`、`DebtContract`、`MigrationPlan`）投影；
   禁止为了日志在结算里另算一套"日志口径"。
4. **异常安全**：日志调用本身不得改变控制流；不为日志加 checked 异常；不在日志里调用可能改写状态的函数。
5. **可检索**：所有行以 `event=...` 开头；逐笔分类固定为 `.trace`。
6. **并行**：`.trace` 行可能跨分区交错；`id` 里的分区段稳定（`tr-<day>-p<part>-<seq>`），诊断按业务键读、不按行序读。

## 8. 其他模块的日志现状与待办（用户明确要求写明）

实测（2026-10-04）：

| 模块 | `LoggerFactory`/`System.out` 命中 | 结论 |
|---|---|---|
| `simos-map` | 0 | 没有日志 |
| `simos-social` | 0 | 没有日志 |
| `simos-unit` | 0 | 没有日志 |
| `simos-sd` | 0 | 没有日志 |
| `simos-actor` | 0 | 没有日志 |
| `simos-actor-api` | 0 | 没有日志 |
| `simos-economy-api` | 0 | 没有日志 |
| `simos-calendar` | 0 | 没有日志 |
| `simos-core` | 4 个文件 | 有启动/检查点等零星日志，无阶段日志 |
| `simos-app` | 11 个文件 | 有启动/人口/税/经济推进等零星日志，无全阶段日志 |

**待办（用户裁定：经济调试收口后补，不是可选）**：按本文同一形态给其他模块各建 `XxxLog` 门面 +
INFO/DEBUG/TRACE 三档 + `event=` 行；先写设计文档再派实现（AGENTS.md §一.8）；补日志前，"其他模块为什么没动作"
只能读代码、不能读日志这条缺口，在排查报告里必须如实列出。

## 9. 已知缺口与风险（如实记，不假装已全）

1. `MarketSettlement` 内部**订单生成与配额分配的逐槽决策**只在结果报告（`sellerOutcomes`/`buyerOutcomes`）里可见，
   撮合内部的分轮/分运力取舍没有逐步日志；诊断"为什么这单没配上"以报告 TRACE 行为准。
2. `EconomyOrganizationSettlement.organizeOne` 的**逐户组队决策**（选 lot/选产业/租佃拆分）没有逐户日志，
   只有 createdUnits ids TRACE；要回答"这户为什么没被组织"需要后续补。
3. `EconomyModeTransitionSettlement` 的逐条 transition 明细已补（`MODE_TRANSITION_APPLIED`/`MODE_TRANSITION_FAILED` TRACE，
   含 failure reason）；`modeTransitions` 表本身仍是权威审计。
4. `DebtCapacityBook`/`DebtValuation` 的逐户/逐腿计算无日志（有意：调用量大）；容量池与逐债偿还决策已在 09/11a/12。
5. `MarketTopologyBook`、`EconomyDayFeed`、`MarketReportFeed` 无日志（有意，见 §4.3）。
6. 并行 worker 的 `.trace` 行序不保证与串行一致（§7.3）。
7. 日志量：TRACE 在 3650 天、3500 人规模下会很大（每笔转移一行）；建议重跑长测时落文件、不要把 TRACE 直接打屏。

## 10. 验收判据

- [x] `simos-economy/src/main` 有 `EconomyLog` 门面；所有经济日志走它，无自拼 logger 名。
- [x] `log4j2.xml` 有 `simos.economy.logLevel`/`simos.economy.traceLevel` 开关。
- [x] 日结算主链 §4.1 每个阶段都有 INFO 或 DEBUG 行；事件阶段有对应 INFO。
- [x] 转移/债务/成交槽/迁移 move 有逐笔 TRACE。
- [x] 真实 12hex 1 tick 跑通并产出完整样例日志（`RealTwelveOneTickTraceTest`，2/2 绿；1 tick 与 0→5 天含开市）。
- [x] 关闭日志（默认 INFO 或 WARN）不影响任何状态/数值：日志只在读侧调用，未改结算写口。
- [x] AGENTS.md §一.9 写明其他模块没 Log、经济调试完必须补。
- [x] 全量 economy 测试（`./mvnw -pl simos-economy -am test`）257 条、0 失败；真实 12hex 3650
      （`RealTwelveHexProductionRuntime3650Test`）2/2 绿，日志开启后行为与验收判据不变。

## 11. 版本记录

- **v1（2026-10-04）**：用户裁定后首个实现版本；logger 门面/三档级别/主链与关键写口覆盖/配置/文档/1 tick + 5 tick 样例；
  economy 257 绿、12hex 3650 2/2 绿。真实 3650 日志显示：信用市场轮从第 240 天起大量出现（`creditFills > 0` 的市场轮 1010 个），
  但后段仍有 `NO_CREDIT_LIMIT` 导致的零成交轮 —— 这是日志已经能直接回答的下一个排查点（不属于本批日志实现）。
