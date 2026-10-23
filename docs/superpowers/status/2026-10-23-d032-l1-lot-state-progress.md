# D-032 L1 生产资料承载生产状态：进度存档（2026-10-23）

> **状态：历史快照（文档系统重写于 2026-10-23）** · 当前实时状态以 [`docs/agents/STATUS.md`](../../agents/STATUS.md) 为准；本文只作当时事实留痕，勿据此开工。

> 状态：L1a/L1b/L1c 已完成并过门禁；未提交/未推送。
> 架构入口：`docs/superpowers/specs/2026-10-22-d032-lot-carried-production-architecture-v1.md`。
> 实施计划：`docs/superpowers/plans/2026-10-22-d032-lot-carried-production-l1-plan.md`。
> 本文件是跨会话状态页；`.superpowers/sdd/*` 只是本机留痕，不入 git。

## 1. 已完成

### L1a：LAND lot state 读入

- `LotState.InProduction` 增加 `startInputPaid`、`cycleInputUsedMilli`，保留旧 5 参构造器。
- `InventoryLotViewBuilder` 从 LAND component 读：
  - `CROP` → planId
  - `STAGE` → stageId
  - `PROGRESS` → stageProgressDays
  - `CYCLE_PROGRESS` → cycleProgressDays
  - `START_INPUT_PAID` → startInputPaid
- `CROP`/`STAGE` 都缺 ⇒ IDLE；只缺一个 ⇒ `inventory-lot-state-incomplete`；类型不符 ⇒ `inventory-lot-state-invalid`。

### L1b：ProductionOpportunityBook

- 新增 `simos-production/.../contract/ProductionOpportunity.java`、`ProductionOpportunityBook.java`。
- 只处理 LAND lot；本 tick 纯只读派生；不落 ChangeSet/Codec。
- InProduction 继续当前阶段；IDLE 对每个适用 staged 方案生成新开机会。
- 期望输入/输出、市场估值、score、劳动需求、稳定排序、重复 ID 拒绝。
- legacy（stages 为空）暂不生成机会。

### L1c：S3 只读接入

- `EconomySession` 持有瞬态 `InventoryLotView`；`EconomyDayStepper.updateInventoryLotView(...)`。
- `PopulationEconomyTimeParticipant` S1 用 `InventoryLotViewBuilder.build(actor, economy, map)` 装配。
- `SettlementDayContext` 持有 `ProductionOpportunityBook`（缺省空簿）。
- `EconomyDayPipeline` 在 `prepareProductionBase` 后、S3 前派生机会簿；日志 `PRODUCTION_OPPORTUNITY_BOOK_BUILT`。
- **机会簿暂不消费**；S3/S4/S5 行为不变。

## 2. 真实验证

### 目标测试

```bash
cd /home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module
tools/mvn-lock.sh -pl simos-app -am \
  -Dtest=InventoryLotViewBuilderTest,ProductionOpportunityBookTest \
  -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：rc=0；`InventoryLotViewBuilderTest` 12/0/0/0；`ProductionOpportunityBookTest` 9/0/0/0。

### 全量 app/reactor

```bash
tools/mvn-lock.sh -pl simos-app -am test
```

结果：rc=0；3720 tests / 0F / 0E / 7S（501 份报告）；frontend 412/412。

### 门禁

```bash
tools/gate.sh full
```

结果：PASS；358s；全仓 surefire 3720/0/0；frontend 412/412；shaded jar 29,256,588 B。

### 360 tick

```bash
SIMOS_MULTITICK_SKIP_BUILD=1 tools/multitick-sim.sh rural-32hex-360d
```

结果：PASS；70.4s；`MULTITICK_SIM_RESULT=PASS cases=1 failed=0`；`PRODUCTION_OPPORTUNITY_BOOK_BUILT` 出现 360 次，`lots=327 opportunities=0`（该世界仍是 legacy staged-empty 方案，故无机会；不是异常）。

## 3. 当前边界

- L1c 只是只读接入；S3 不消费机会簿，S4/S5 不按 lot 执行。
- `ProductionProcess.stageState` 仍是临时桥。
- `ProductionOpportunityBook` 暂不处理 legacy 单周期方案。
- `cycleInputUsedMilli` 在 LAND view 中仍为空（L2a 需要定义 Map 属性编码）。
- 未做 durable/stateful goods。
- 未提交/未推送，主树源码未改。

## 4. 下一步

L2：LAND 执行与写回。

- L2a：LandProductionStateWriter（CROP/STAGE/PROGRESS/CYCLE_PROGRESS/START_INPUT_PAID/cycle_input:* 写回；IDLE 清理；OWNER 不动）。
- L2b：S4 从 carrier lot 读 state、扣料、写回。
- L2c：S5 按 lot 收获。
- L2d：市场卖地保留状态 + 旧 claim 失效。
- L2e：移除/降级 ProductionProcess 的土地进度权威。

## 5. L2 追加进度（2026-10-23）

### L2a：LAND state writer

- 新增 `simos-app/.../world/LandProductionStateWriter.java`：
  - `CYCLE_INPUT_KEY_PREFIX = "cycle_input:"`；
  - `apply(GameMap, List<Write>)` 纯函数；
  - InProduction 写 CROP/STAGE/PROGRESS/CYCLE_PROGRESS/START_INPUT_PAID + cycle_input:*；
  - Idle 清生产键，保留 OWNER/FERTILITY/其它；
  - 具名失败：lot-missing / duplicate / ambiguous / state-invalid。
- `InventoryLotViewBuilder` 引用同一前缀解析 cycle_input:*。
- 测试：`LandProductionStateWriterTest` 7 tests + `InventoryLotViewBuilderTest` 13 tests；目标 29 tests 绿。
- 全量 app/reactor：3728 tests / 0F / 0E / 7S。

### L2b1：纯日执行内核

- 新增 `simos-production/.../ProductionLotDayExecutor.java`：
  - DayRequest/Result/Status；
  - IDLE/InProduction 上下文；
  - 本 tick 仅扣当前 stage tickInput + 首日未付 startInput；
  - CONTINUE / STAGE_ADVANCED / HARVESTED / REJECTED；
  - 输入不足 stage-input-shortfall；
  - plan mismatch、legacy、unknown stage、progress 越界、Complete、scale<=0 具名拒；
  - 纯函数，不写库存/Map/ChangeSet。
- 测试：`ProductionLotDayExecutorTest` 18 tests；目标绿；A/B/C、ADVANCE、HARVEST、shortfall/负例全中。
- 全量 app/reactor：3746 tests / 0F / 0E / 7S；前端 412/412。
- `production-lot-next-stage-missing` 结构不可达（合法 plan 构造期强制末段 HARVEST）；台账已记，不伪造。

### 当前仍未做

- L2b2：S3 生成 claim、S4 真实库存扣减 + `LandProductionStateWriter` 写回 Map；
- L2c：S5 按 lot 入库存/服务 sink；
- L2d：卖地保留 state + 旧 claim 失效；
- L2e：降级 `ProductionProcess` 土地进度权威；
- 未跑 L2 之后 gate/360（L1c gate 已过；L2 子批只跑全量 app/reactor）。

## 6. 后续验收（2026-10-23）

L2 已完成并过最终门禁/360；后续状态与边界以
`docs/superpowers/status/2026-10-23-d032-l2-acceptance.md` 为准。
