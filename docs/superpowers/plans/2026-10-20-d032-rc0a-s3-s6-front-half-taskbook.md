# RC0a 任务书：v2 S3–S6 的前移与同日工资债 cutoff

> **状态：约束设计书/实现任务书；本责任区只写生产代码到编译过，不写/不跑测试。**
> 工作树：`/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`。
> 父设计：`docs/superpowers/specs/2026-10-19-d032-standard-agriculture-architecture-design-v2.md`
> 的 S3–S8；v4 机制：`docs/superpowers/specs/2026-10-20-d032-two-position-rural-class-mobility-v4.md`。
> 派单总表：`docs/superpowers/plans/2026-10-20-d032-two-position-rural-class-mobility-dispatch-plan.md` §5。
> **本任务不是完整 v2 S3–S8 语义收口。** 它只做第一层硬前置：把当前旧 M 链重排为
> “生产/收获/工资 → 债务清算 → 市场”，保留既有公式、既有单日语义与既有 legacy 回退。
> `DailyDemandBill`、实际盈余卖盘、完整 S7 需求账单、S7-B/C 不在本责任区。

## 1. 目标运行序列（唯一允许的日序）

### 1.1 市场日（`MarketTriggerBook.triggerFor(...) != NONE`）

```text
S0 Social（组合根已先做；economy 入口不重做）
S1 经济载入
S2 进入/组织
S2b 基础装配（到货、actor/家户反查、cycleDays）
S3 预期生产位置选择（现有 planExpectedProduction）
S4 消费/实际编排/实际劳动/实际投入/生产执行（保留现有内部次序）
S5 周期末收获/毛产（现有 closeAndHarvest）
S6 工资（现有 settleWageAndCapitalize）
S7-M2 债务清算（现有 settleDebtsAtMarketOpen，必须移到此处）
S7-A 统一市场（现有 prepareMarketRound + executeMarketRound）
S7-post 债务估值/授权生命周期（现有 settleDebt）
S8 缺口事实/经营者/流水/收尾
```

### 1.2 非市场 tick

不开市场、不清债务本金、不改价格/工资锚；但 S3–S6 每 tick 照常执行：

```text
S1 load → S2 entry/organize → S2b base
→ S3 expected plan
→ S4 consumption/actual plan/labor/input/production
→ S5 harvest
→ S6 wages
→ S7-post/close
```

### 1.3 legacy fallback 也要同序

当 `productionRostersOrBase().isEmpty()`：

```text
S2b prepareProductionBase
→ legacyConsumeAfterBase
→ driveProduction
→ closeAndHarvest
→ wages
→ S7-M2 debt（市场日）
→ market（市场日）
→ settleDebt / close
```

**不得只改新链、把 legacy 链留在旧 M2→M4→M7 顺序。**

## 2. 当前代码事实（file:line）

- `EconomyDayPipeline.java:53-58`：冻结了旧 `M2 债务 → M3 预期编排 → M4 市场 → M5 消费 → M6/M7 生产 → M8 工资`。
- `EconomyDayPipeline.java:56-59`：债务在 **生产之前**。
- `EconomyDayPipeline.java:100-106`：市场在 **消费和生产之前**。
- `EconomyDayPipeline.java:107-117`：消费/实际编排/生产/工资在 **市场之后**。
- `EconomyDayPipeline.java:68-98`：legacy 分支同样把市场放在生产之前。
- `DebtFirstSettlement.java:40-60`：类级说明明确把自己描述为“市场最前面、消费/生产/市场之前”。
- `SettlementDayDebtPipeline.java:18-24`：同样把 M2 描述为消费/生产/市场之前。
- `SettlementDayWagePipeline.java:35-37`：工资注释说明 `M8 在本轮 M2 之后`；重排后必须改成显式 cutoff。
- `SettlementDayContext.java:129`：已有 `sameDayWageDebtIds` 字段。
- `WageSettlementReadout.java:41`：逐义务回执已有 `debtContractId`。
- `WageSettlement.java:337-351`：未付工资债在此处 upsert；回执带 debt id。
- `DebtFirstSettlement.java:143-...`：payer 侧 owed 排序/过滤处；当前没有同日工资债排除。
- `EconomyDayPipeline.java:100-105`：市场轮只接受 `expectedProductionPlan.marketIntents()`；重排后仍在 S7，且只能在 S6 后调用一次。

## 3. 必须达到的行为

1. **生产轮先于市场轮**：任何 `SettlementDayMarketPipeline.prepareMarketRound/executeMarketRound` 调用都必须在
   `settleWageAndCapitalize` 之后；不得在 `settleConsumption/planActualProduction/drawActualInputs/driveProduction/closeAndHarvest`
   之前调用。
2. **债务本金清算移到 S7-M2**：市场日 `settleDebtsAtMarketOpen` 必须在 S6 工资后、市场撮合前；不得在生产前调用。
3. **同日工资债 cutoff**：S6 生成的 `WAGE_ARREARS` 债务合同 ID 必须收集到 `ctx.sameDayWageDebtIds`；
   `DebtFirstSettlement` 在 S7-M2 的 owed 过滤中必须跳过这些 ID。它们最早下一市场日同池处理。
   - 不得用“债务 source 是 WAGE_ARREARS”一刀切跳过所有工资债；只跳过**本 tick 新建**的合同。
   - 旧工资债仍必须在 S7-M2 处理。
4. **legacy 回退同序**：legacy 分支也必须先消费/生产/工资，再债务/市场。
5. **非市场 tick**：不得调用 `settleDebtsAtMarketOpen` 或市场装配/撮合；S3–S6 仍执行。
6. **不改变公式**：不重算工资、债务、生产、市场金额；不新增 `DailyDemandBill`、不重排 S4 内部
   `settleConsumption → planActualProduction → planActualLabor → drawActualInputs → driveProduction` 的既有调用顺序。
7. **不改状态形状**：不新增/删除 `EconomyData` 组件，不改 ChangeSet/Codec/生产契约/culture。
8. **不改市场 caller 的单一入口**：仍只允许 `prepareMarketRound` + `executeMarketRound` 一次；不得保留第二个市场
   matcher/phase。
9. **日志**：在 `EconomyDayPipeline` 每个 tick 发一条 INFO 汇总，至少含：
   `day`, `marketDay`, `path=main|legacy`, `order=S3>S4>S5>S6>S7-DEBT>S7-MARKET|S3>S4>S5>S6>CLOSE`，
   以及 `sameDayWageDebtIds` 计数。逐笔/逐户理由可 DEBUG，不要求本批新增 TRACE。
10. **注释不撒谎**：把所有仍写“M2 在消费/生产/市场之前”“M8 在本轮 M2 之后”的 Javadoc/注释改成新时序。

## 4. 允许修改的文件

只允许改这些生产文件（以及为编译必须改的同类注释/import）：

```text
simos-economy/src/main/java/io/mosire/simos/economy/time/EconomyDayPipeline.java
simos-economy/src/main/java/io/mosire/simos/economy/time/SettlementDayDebtPipeline.java
simos-economy/src/main/java/io/mosire/simos/economy/time/DebtFirstSettlement.java
simos-economy/src/main/java/io/mosire/simos/economy/time/SettlementDayWagePipeline.java
```

可选只改注释/日志的相邻文件：

```text
simos-economy/src/main/java/io/mosire/simos/economy/time/SettlementDayProductionPipeline.java
simos-economy/src/main/java/io/mosire/simos/economy/time/SettlementDayMarketPipeline.java
simos-economy/src/main/java/io/mosire/simos/economy/time/SettlementTickChainLog.java
```

**禁止改**：

- `EconomyData` / `EconomyChangeSet` / `EconomyCodec` / 任何持久组件；
- `simos-production` / `simos-culture` / `simos-app` 生产逻辑；
- 任何 `src/test/**`；
- 主树、`.m2-repo`、GUI/服务脚本。

## 5. 实现边界（不要求逐文件施工图）

- 在 `EconomyDayPipeline` 中重排调用；建议把“市场日分支”抽成清晰的 `settleMarketDay` 前/后两半，但**不强制**类/方法名。
- 把 `settleDebtsAtMarketOpen` 从入口 S2b 前移取出，放到 S6 后；市场日才调用。
- 在 `SettlementDayWagePipeline` 中把 `readouts().debtContractId()` 的非空值收进 `ctx.sameDayWageDebtIds`。
- 在 `DebtFirstSettlement` 的 owed 过滤条件中排除 `ctx.sameDayWageDebtIds`；日志用 INFO/DEBUG 记录
  `excludedSameDayWageDebts` 计数与 reason。
- 保持 `settleDebt(ctx)`（非本金、授权生命周期、估值刷新）在市场之后。
- 重排 legacy 分支的同一组调用。
- 对 `plantingDrawsFirst` 的解释只保留在 legacy/S4 内部，不再让它决定市场在生产前还是后。

## 6. 失败/回滚/守恒边界

- 任一阶段抛异常 ⇒ 整次 advance 失败，不落 revision；不得在重排后把部分市场调用吞掉。
- `sameDayWageDebtIds` 只在本次 `EconomySession` 内有效，不落 Codec/ChangeSet。
- 债务合同/工资债/账户/库存守恒不变；本批不改变任何金额。
- 市场日必须有且仅有一次 `prepareMarketRound` + 一次 `executeMarketRound`。
- 非市场日必须零次。
- 若 `ctx.expectedProductionPlan == null` 且非 legacy（异常状态）⇒ 沿用现有 fail-closed 路径，不伪造空市场意向。

## 7. 验收锚点（测试 owner 后补，不由本 writer 运行）

正例：

1. 市场日：日志/静态断言顺序 = `S3 > S4 > S5 > S6 > S7-DEBT > S7-MARKET`。
2. 非市场日：无 `S7-DEBT`、无市场；S3–S6 仍存在。
3. 本 tick 新生成 `WAGE_ARREARS` 的 debt id 出现在 `sameDayWageDebtIds`；同一 tick S7-M2 不偿还；
   下一市场日仍可处理。
4. 旧工资债（非本 tick 新建）仍按 S7-M2 处理。
5. legacy 世界：市场也在工资之后。
6. 生产输入的扣减、产出、工资、债务本金总额与重排前同一夹具的预期差异只来自“市场不再供应本 tick 生产/消费”，
   不应出现无来源的金额。

负例：

- 市场调用出现在 `settleConsumption` 之前 ⇒ 红。
- `settleDebtsAtMarketOpen` 出现在 `planExpectedProduction` 之前 ⇒ 红。
- 本 tick 新工资债在 S7-M2 被偿还 ⇒ 红。
- 非市场日调用市场 ⇒ 红。
- 同日工资债过滤按 source 一刀切，误伤旧工资债 ⇒ 红。
- legacy 分支仍保留旧顺序 ⇒ 红。

## 8. 编译与交账

命令（本树）：

```bash
cd /home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module
tools/mvn-lock.sh -q spotless:apply
tools/mvn-lock.sh -q -DskipTests compile -pl simos-economy,simos-app -am
```

要求：

- **只写生产代码**；不写/不跑 test/verify/package；不 commit/push。
- 报告必须列：改动文件、编译 rc、会改变数值行为的点、受影响的硬编码/测试断言清单、
  与任务书不一致处、未完成/未验证项。
- 实现账本落 `.superpowers/sdd/2026-10-20-d032-rc0a-s3-s6-front-half/impl-ledger.md`。
- 中期交账必须含父阶段锚点 S3–S8、实际实现与偏离、对顺序/预算/持久化/失败/日志/测试的影响表。

## 9. 本责任区明确不做

- 不做完整 v2 `DailyDemandBill`、实际盈余卖盘、S7-B LANDLESS 买地阶级检查、S7-C 向下流动表；
- 不改 S4 内部的“消费/实际编排/劳动/投入/执行”公式与次序；
- 不删除 legacy 回退（除非控制方另行派单）；
- 不改 `class nature`、culture label、land 规则；
- 不调人口上限、不改 seeder、不改 32hex 夹具；
- 不跑 360 tick（测试 owner 后置）。

## 10. 用户原话附录（逐字）

来源：`docs/superpowers/specs/2026-10-19-d032-rural-production-land-labor-revision.md` §一、2026-10-19。

```text
4 不是说了先生产后开市场吗
如果按照先生产后市场的顺序，tick0确实是没有生产方式的，这很正常，除非你搞预设了
```

来源：`docs/superpowers/specs/2026-10-20-d032-two-position-rural-class-mobility-v4.md` §1.2，2026-10-20。

```text
R6：测试世界依旧32hex，每个hex一个有地农民家户一个无地农民家户就行，政府和市场区配置照旧
```

来源：2026-10-20 当前会话。

```text
需求被允许挂价了吗？家户提的需求，面对市场不管多贵都会直接接受的；其他没啥问题，记录一下你的修复方案，开始吧
```
