# D-032 RF2 任务书：S4 生产劳动/投入时序与种子/日粮

> **父计划：** `docs/superpowers/plans/2026-10-20-d032-rural-360-fix-plan.md` §3–§4
> **父目标设计：** `docs/superpowers/specs/2026-10-19-d032-standard-agriculture-architecture-design-v2.md` S3–S6 / §4.5–§4.6 / §6
> **状态：** 可派实现；本批只写生产代码到编译过，不写/不跑测试。
> **工作树：** `/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`
> **前置：** RF1 已 compile rc=0；本批不得回退/覆盖 RF1 的需求价格接受者语义。

## 1. 问题现场（已核实）

1. `simos-economy/.../EconomyDayPipeline.java:107-115`
   - 主路径是 `M5 消费 → M6 planActualProduction/planActualLabor → M7 drawActualInputs → driveProduction`。
2. `LaborQueueBook.java:285-355`
   - 劳动队列用 `cycleInputUsedMilliOf(session, unit)` 的 `drawn` 作为投入通道；
   - day1 M7 尚未现扣，`drawn=0` ⇒ 96 个 poor/middle/rich farm 被判
     `NO_INPUT:grain:drawn=0:perScale=12000`，`maxAbsorbable=0`。
3. `LaborQueueWritebackBook.java:47-90`
   - 进入 decisions 的 `(unit,role)` 先删除旧 commitment；`grant=0` 不再写回；
   - 与 `NO_INPUT` 叠加后，farm 劳动承诺被永久删除。
4. `ProductionInputSettlement.java:309-371`
   - 周期第一天对计划规模做整周期投入调查，不检查 unit 是否被 M3/M6 实际选中、是否有实际劳动/实际播种；
5. 同文件 `:597-657` 与 `ProductionInputDrawSettlement.java:340-377`
   - 同 hex 争用种子时把多个独立家户合成 `sharedKeys` 池，可从“别人账”取料；
6. `ProductionInputDrawSettlement.java:293-390`
   - 取料上限是 `min(stock, ask)`，没有保命日粮；tick1 后 128 个农村非无地户全部 grain=0。

## 2. 目标能力

### 2.1 劳动排队不得把“尚未现扣”当“没有投入”

- M6 位置/劳动选择读**当刻可用投入**（M5 消费后的家户实际库存），不得把 `drawnProductionInputs` 的缺省 0 当作 `NO_INPUT`；
- 如果实现无法在 M6 读到可靠的在手库存，必须把 `inputScale` 视为“待 S4 决定”，**不得**因此把 `maxAbsorbable=0` 并把已有 commitment 删除。
- 只对真实不可行（`asset-location-scope-missing`、无产能、无产出配方、明确无任何可用种子等）作具名拒绝。

### 2.2 只对实际被选中/实际有劳动的单位扣周期投入

- M7 现扣前应有 `actualProductionPlan` / 实际劳动承诺的 unit 集；
- 未入选、无实际劳动、今天不生产的 unit 不得先扣整周期种子；
- 不得因为 unit 存在就默认它会生产。

### 2.3 种子只按实际可生产面积/实际计划规模扣

- 单位投入不得超过该 unit 当刻可支撑的规模（至少不得大于 `plannedCapacityScale` 与可用库存的较小者）；
- 没有种子 ⇒ 只种实际可覆盖面积，不把其他家户/其他用途的粮当默认种子；
- 同 hex 独立家户之间不得共享 grain 种子池；只有同一组织/同一 roster 的共享投入才允许（当前标准农业按家户独立，先按严格不共享实现）。
- 每户在扣种后必须保留**至少一 tick 的基础口粮**（`naturalNeeds.grain` 的当前值）；不得把户粮清零。
  - 这是本批的工程默认，不是用户新玩法裁定；如实现发现该 reserve 与当前模型冲突，在账本标 BLOCKED/待裁定，不得静默放开。

### 2.4 只修输入/劳动链，不改生产物理公式

- 不改变 `ProductionEfficiency` 的三通道公式、`finalScale` 或 `B_cycle`；
- 本批目标是**让有种子、有劳动的自耕 farm 真正进入 `cycleContributions` 并在周期末产出**；
- 不实现 v2 完整 `DailyDemandBill`/单 hex 聚合；那是后续责任区。

## 3. 约束设计

### 3.1 运行顺序

保持主日序不变（本批不重排 S0–S8），但在 M6/M7 内部修正：

```text
M5 消费（已有）
M6 实际生产编排
  → 位置/劳动选择读“M5 后的实际投入可用量”，不读“本 tick 尚未 draw 的 0”
M7 实际投入现扣
  → 仅对实际被选中/实际有劳动的 unit 扣料
  → 每户保留 ≥ 1 tick naturalNeed.grain
  → 独立家户之间不共享 grain 种子池
driveProduction / closeAndHarvest（公式不变）
```

若实现者认为必须把 `drawActualInputs` 放到 `planActualLabor` 前，必须在实现账本说明：为什么不会重新引入“未入选 unit 预扣/清零口粮”，并证明只在选中 unit 上扣。

### 3.2 数据/写口

- 不新增 `EconomyData` 组件、不改 ChangeSet/Codec。
- 不改变 `ProductionProcess.cycleInputUsedMilli` 的权威；`EconomySession.drawnProductionInputs` 仍只记本 tick delta。
- 不在 `LaborQueueBook` 复制生产配方计算；读取已有 `production.probe` 与公共 helper。
- 不修改 `ProductionRuntimeEngine` 的毛产/效率公式，除非仅为接线必需且要在账本说明。

### 3.3 日志

新增/修改路径必须有可 grep 证据：

- `LABOR_QUEUE_PLAN`：输入通道值、来源（on-hand/awaiting-draw）、为何 grant/ idle；
- `PRODUCTION_INPUT_DELTA_RECORDED`：仍是实际扣减；
- 新增 `PRODUCTION_INPUT_RESERVED` / 等价：unit、household、commodity、requested、drawn、reservedForFood；
- 共享池被拒/未使用：`PRODUCTION_INPUT_SHARED_POOL` 或等价（只报业务拒绝/读数，不静默）。

## 4. 允许修改的文件

**主要：**

- `simos-economy/src/main/java/io/mosire/simos/economy/time/LaborQueueBook.java`
- `simos-economy/src/main/java/io/mosire/simos/economy/time/LaborQueuePlanBook.java`
- `simos-economy/src/main/java/io/mosire/simos/economy/time/LaborQueueWritebackBook.java`
- `simos-economy/src/main/java/io/mosire/simos/economy/time/ProductionInputSettlement.java`
- `simos-economy/src/main/java/io/mosire/simos/economy/time/ProductionInputDrawSettlement.java`
- `simos-economy/src/main/java/io/mosire/simos/economy/time/SettlementDayProductionPipeline.java`
- `simos-economy/src/main/java/io/mosire/simos/economy/time/EconomyDayPipeline.java`（仅当确需调整 M6/M7 调用/传参）

**仅在编译必需时：**

- `simos-economy/src/main/java/io/mosire/simos/economy/time/` 内最小相邻文件；
- `EconomySession` 的只读查询方法（不得新增持久组件）。

**禁止：**

- 测试文件；
- `EconomyData`/ChangeSet/Codec 形状；
- `simos-production` 物理公式/定义（除非编译必需，先报告）；
- 主树、GUI/服务、`.m2-repo`；
- 回退 RF1 的 `BuyOrder.maxLandedPrice OptionalLong` / `MarketIntent` 价格接受者语义。

## 5. 交账与编译

- 只跑：
  ```bash
  tools/mvn-lock.sh -q -DskipTests compile -pl simos-economy -am
  ```
  若改了 `simos-production`：
  ```bash
  tools/mvn-lock.sh -q -DskipTests compile -pl simos-production,simos-economy -am
  ```
- 不跑 test/verify；不写/改测试；不 commit。
- 报告：
  1. 改动文件/关键方法；
  2. 编译命令与真实 rc；
  3. 会改变数值行为的清单；
  4. 受影响硬编码字面量、可能失效的既有测试断言；
  5. 与父计划/本任务书不一致处；
  6. 未完成/未验证；
  7. 实现账本：`.superpowers/sdd/2026-10-20-d032-rf2-s4-labor-seed/impl-ledger.md`。
- 若发现用户原话/五条铁律/模块边界冲突：立即停手上报。

## 6. 验收锚点（测试 owner 后补）

正例：

- day1 96 个 poor/middle/rich farm 不再因 `drawn=0` 被判 `NO_INPUT`；实际劳动 > 0；
- tick119/120：这些 farm 的 `cycleContributions` 非空，`finalScale > 0`，`productionGrainMilli > 0`；
- tick1 后，每个原本有粮的农村非无地户至少保留一 tick 口粮；
- 无实际劳动/未入选 unit 不产生 `PRODUCTION_INPUT_DELTA_RECORDED`；
- 同 hex 独立家户的 grain 不互相扣种（日志/库存守恒）。

负例：

- 真无种子/真无劳动 ⇒ 不伪造规模、不产出；
- 不能把别户口粮当 seed；
- 不能把 `NO_INPUT` 静默变成全额 grant；
- 不允许同 tick 对同一 unit 重复扣种；
- 不允许把当前 tick 新工资债纳入 S7-M2（S6/S7 cutoff 不变）。
