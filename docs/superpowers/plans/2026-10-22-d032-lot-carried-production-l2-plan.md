# D-032 生产资料承载生产状态 L2 实施计划（2026-10-23）

> 状态：L2 派单约束；未改代码、未提交。
> 架构：`docs/superpowers/specs/2026-10-22-d032-lot-carried-production-architecture-v1.md`。
> L1 状态：`docs/superpowers/status/2026-10-23-d032-l1-lot-state-progress.md`。
> L1 已完成：LAND lot state 读入、OpportunityBook、S3 只读接入；S3 尚未消费。

## 0. L2 目标

把 LAND 的生产状态真正写回 Map，并让 S4/S5 逐步从 carrier lot 读取/写回；最终让 `ProductionProcess.stageState` 不再是土地长期进度权威。

L2 不一次性删除 unit 引擎；按 L2a→L2e 分层，每层保持旧路径可运行。

## 1. 目标运行序列（LAND 部分）

```text
S1 InventoryLotView
  → S1b ProductionOpportunityBook
  → S3 选择机会 + ProductionClaim
  → S4 按 carrier lot 读 stage/progress，扣实际投入，写回 LandComponent
  → S5 按 lot 收获，HARVEST 后清空生产属性
  → S7 卖地只改 OWNER，state 原样跟随 lot；旧 claim 失效
```

## 2. L2a：LandProductionStateWriter + cycle input 编码

### 允许文件

```text
simos-app/src/main/java/io/mosire/simos/app/world/LandProductionStateWriter.java（新增）
simos-app/src/main/java/io/mosire/simos/app/inventory/InventoryLotViewBuilder.java（修改）
simos-app/src/test/java/io/mosire/simos/app/world/LandProductionStateWriterTest.java（测试 owner）
simos-app/src/test/java/io/mosire/simos/app/inventory/InventoryLotViewBuilderTest.java（测试 owner）
simos-app/src/test/java/io/mosire/simos/app/inventory/InventoryTestFixtures.java（如需 helper）
```

### Writer 形状

```java
record LandProductionStateWrite(LandComponentId componentId, LotState state)
```

```java
static GameMap apply(GameMap base, List<LandProductionStateWrite> writes)
```

规则：

- 纯函数：不写 base，返回新 `GameMap`；失败不产生半成品；
- `InProduction`：
  - 写 `CROP`=planId、`STAGE`=stageId、`PROGRESS`=stageProgressDays、`CYCLE_PROGRESS`=cycleProgressDays、`START_INPUT_PAID`=startInputPaid；
  - `cycleInputUsedMilli` 写成逐商品 `cycle_input:<commodity>` LONG 属性；先清掉目标 component 的所有 `cycle_input:` 旧键，再写新键；
  - 保留 OWNER/FERTILITY/其它非生产属性；
- `Idle`：
  - 删除 `CROP/STAGE/PROGRESS/CYCLE_PROGRESS/START_INPUT_PAID` 与全部 `cycle_input:` 键；
  - 保留 OWNER/FERTILITY；
- `Complete`：具名拒 `land-production-state-invalid`；
- 目标 component 不存在 ⇒ `land-production-lot-missing`；
- 重复 component id ⇒ `land-production-write-duplicate`；
- 不得写 OWNER；必须走 `setComponentAttributes/removeComponentAttributes` 或等价纯操作；
- 属性 kind 必须匹配：PROGRESS/CYCLE_PROGRESS/cycle_input:* 用 LONG，START_INPUT_PAID 用 BOOLEAN，CROP/STAGE 用 STRING；
- 全部目标一次复制地图，最后按 hex 写回；不得触碰非目标 hex。

### Builder 同步

`InventoryLotViewBuilder` 需解析 `cycle_input:<commodity>`：

```text
cycleInputUsedMilli = { CommodityId(prefix 后文本) -> LONG }
```

- 前缀常量与 writer 同一拼写点（建议 Writer 暴露 `CYCLE_INPUT_KEY_PREFIX`，Builder 引用；不得两处硬编码）；
- 非法空 commodity / 负值 ⇒ 具名 `inventory-lot-state-invalid`；
- 旧 5 参构造器兼容不变。

### L2a 验收

- 10/3/1 写入后三行状态正确；
- C 从 IDLE 写 InProduction 后可读回；
- A 写 Idle 后生产属性全清、OWNER/FERTILITY 保留；
- cycle_input:grain 写回后可读；
- 未知 lot / 重复 write / Complete / 属性类型冲突具名拒；
- 失败时 base 不变；
- 旧 21 个 L1 测试继续绿。

## 3. L2b：S4 按 carrier lot 扣料/推进

### 3.0 L2b1：纯 LAND 日执行内核（先做）

新增 production 内部纯执行器：

```text
ProductionLotDayExecutor.DayRequest
  lotId
  plan
  currentState
  lotQuantityMilli
  availableInputs
ProductionLotDayExecutor.Result
  lotId
  status (CONTINUE / STAGE_ADVANCED / HARVESTED / REJECTED)
  reason
  nextState
  consumedInputs      // 本 tick delta
  outputs             // 仅 HARVEST
  productionScale
```

规则：

- `scale = lotQuantity / LAND capacityPerUnit`，<=0 拒 `land-production-scale-zero`；
- IDLE：从 stage 0、progress 0、startInputPaid=false、cycleInputUsed={} 开始；
- InProduction：stage/progress/startInputPaid/cycleInputUsed 从 lot state 读；
- 本 tick 必须投入 =（首日且未付 start 时）startInput×scale + tickInput×scale；
- 不把 future stages 投入算进本 tick；
- `availableInputs` 不足 ⇒ `REJECTED: stage-input-shortfall`，nextState 原样，consumed 为空；
- 足够 ⇒ consumed=required，cycleInputUsed 累加；
- stage progress+1 < duration ⇒ CONTINUE；
- stage 结束且 NEXT_STAGE ⇒ STAGE_ADVANCED（下一段 progress=0、startInputPaid=false）；
- stage 结束且 HARVEST ⇒ HARVESTED（outputs=最终 output×scale×1000；nextState=Idle）；
- `plan.stages` 为空、stage 未知、progress 超过 duration、LAND capacity 缺失 ⇒ 具名拒；
- 不扣真实库存、不写 Map、不落 ChangeSet；真实库存扣减/写回在 L2b2。

### 3.1 L2b2a：ProductionClaim + LandProductionSettlement 纯服务

- `ProductionClaim`：claimId、lotId、planId、owner、operator、fromTick、toTickExclusive；
- `LandProductionSettlement.settle(map, actor, inventoryLotView, opportunityBook, plans, claims)`：
  - 按 claim 找 lot/opportunity/plan；
  - operator 库存作为 availableInputs；
  - 调 `ProductionLotDayExecutor`；
  - 成功：扣 operator 库存、产出入 operator 库存（`transport_capacity_service` 不入口袋，进 serviceOutputs）、`LandProductionStateWriter` 写回 Map；
  - 拒绝：无库存/Map 变化，进 Rejections；
  - 不构造 ChangeSet/Codec、不读时钟。
- 先由调用方显式给 claims；S3 自动选 claim 在 L2b2b。

### 3.2 L2b2b：S3/S4 接线

- S3 消费 `ProductionOpportunityBook`，生成 `ProductionClaim`；
- S4 用 claim + lot state 调 L2b2a 服务；
- 实扣写回 `cycle_input:<commodity>`；
- 阶段推进写回 `STAGE/PROGRESS/CYCLE_PROGRESS/START_INPUT_PAID`；
- 不足缩规模/拆 lot；
- 本步只做 LAND；legacy 生产保持不变。

## 4. L2c：S5 按 lot 收获 / 服务 sink

- 每 lot 按自身 quantity/state 计算产出；
- HARVEST 后清空生产属性、保留 OWNER/FERTILITY；
- 普通商品入 operator 库存；
- `transport_capacity_service` 不入普通库存：LandProductionCycle 把 receipt 中的服务产出转成 capacityWorkMilli（outputMilli/1000），按 `(operator, homeHex, marketRoundIdOf(tick))` 累加进 EconomyData.transportCapacityBatches；
- operator 户缺 homeHex / 市场区缺失 / zone 漂移 / 非整千服务量 ⇒ 具名 fail-closed；
- economy 切片必须存在，否则 `land-production-service-economy-missing`；
- PopulationUnitTimeParticipant 在 landState 改变 economy 时，把 population 的 economy 增量应用到 land 后重新派生 base→final EconomyChangeSet；
- 旧 unit harvest 路径保留给 legacy。

## 5. L2d：市场交易保留状态

- 卖地只改 OWNER；
- 旧 claim 失效；
- 新 owner 下一轮可 claim 并从原 stage/progress 继续；
- 使用 U3 已有 `transferFromComponent` 状态复制；补 4,000/10,000 部分交易测试。

## 6. L2e：降级/移除 ProductionProcess 土地进度权威

- 土地路径不再写/读 `ProductionProcess.stageState`；
- `ProductionData.instances` 对 LAND 不再存进度；
- 保留 legacy/无 carrier 短周期路径；
- L2e 先做**过渡隔离守卫**：当 LAND lot 已有生产状态、且同一 location+plan 仍存在 `ProductionProcess` 实例时，land cycle 具名 fail-closed `land-production-legacy-process-authority-conflict`，禁止双权威同时推进；
- 旧 unit 引擎的 `stageState` 只保留给 legacy/non-carrier 路径；LAND 路径以 Map component 为唯一权威；
- 迁移/兼容按 Alpha 不兼容旧档处理，不写旧档迁移。

## 7. 不可破坏

- 铁律 1–5；
- Map 拥有 LAND；actor 拥有商品；economy 拥有市场/劳动/账户；production 拥有定义；
- 所有写入 Command→ChangeSet→Revision；
- 普通商品不得因承载生产状态变成 lot；
- 一 lot 一生产状态；部分生产先拆 lot；
- 交易不改 stage/progress；
- 机会/claim/batch 只是本 tick 内存态，不落 ChangeSet/Codec。

## 8. 测试/门禁

- 每个 L2 子批：写者只编译；测试独立 owner；目标测试 + 全量 app/reactor；必要时 360。
- 最后一个子批后：`tools/gate.sh full` + `SIMOS_MULTITICK_SKIP_BUILD=1 tools/multitick-sim.sh rural-32hex-360d`。
- 变异自证至少覆盖：writer 漏清 IDLE、交易重置 stage、S4 不写回 cycle_input。

## 3.3 L2b2b：单推进步的 LAND continuation 接线（先做安全口径）

> 目标：在**不抢现有 economy account session** 的前提下，把已有 `InProduction` land lot 的日推进接进真实 Advance 提案；不自动启动 IDLE lot。

新增 app 纯桥：

```text
LandProductionCycle.run(SimulationState state, long tick)
  → 从 map 建 LAND-only InventoryLotView
  → 从 production 定义建 OpportunityBook
  → 只给 InProduction LAND lot 生成 owner/operator 相同的 ProductionClaim
  → LandProductionSettlement.settle
  → 返回带新 actor/map snapshots 的 SimulationState
```

接线点：

- `PopulationUnitTimeParticipant.simulateWorld` 在 population 之前先跑 land cycle；
- population 看到 land 产出/扣料后的 actor/map；
- 最终 actor/map ChangeSet 都从 base 到 final 重新派生；
- 无 land claim 时逐值退化，不改任何行为；
- 只处理已有 `InProduction` lot；IDLE 启动留给后续 S3 选择；
- 一次 Advance 只跑一个 land day（当前 360/真实入口按 1 tick Advance）；多 tick Advance 的逐日待后续；
- 不接 market/labor/缩规模；不改变 legacy unit 生产。

### 验收锚点

- 真实 SimulationState + Advance 中，10/3/1 的 A/B 推进，C 仍 IDLE；
- 扣料/产出/Mapt state 在同一 revision；
- 无 claim 的世界（360）actor/map 不变；
- IDLE lot 不自动启动（T4 不回归）。
