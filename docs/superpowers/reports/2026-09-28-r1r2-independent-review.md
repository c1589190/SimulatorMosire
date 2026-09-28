# R0+R1+R2 独立只读评审报告（2026-09-28）

> **代码态**：`ts/m1` @ `7a848413`（R0+R1+R2 candidate checkpoint；仅 `-DskipTests compile` 通过）。
> **性质**：独立只读评审，未修改生产代码、未跑测试/模拟/性能。所有结论均为静态阅读；运行时结论留 V 阶段。
> **输入**：`docs/superpowers/plans/2026-09-28-pre-modern-economy-development-plan.md`、`AGENTS.md`、工作树 diff。

## 0. 总体判断

- 未发现足以判死的 **Blocker**。
- 「线程安全由结构保证」在 R2 当前已接入的 6 个分区阶段成立（到货/消费/周期投入/收获/同格借粮/劳动再分配/区内市场订单生成）。
- 但仍有 3 处“靠约定/靠调用方”的接缝，另有 5 条语义/兼容风险；建议在 R3/R4 前集中修复。

## 1. 已静态确认的正面结构

- `AccountPartitionKey.partitionIndex` 只依赖 canonical 串的 `String.hashCode()` + `floorMod`，无线程 id/随机/到达序。
- `EconomyParallelism.STRUCTURAL_PARTITIONS = 32` 固定结构分区数；`workerCount` 只决定池大小，不参与分区/提交序。
- `PartitionPlan` 用 `TreeSet`/`TreeMap` 去重升序；`SettlementExecutor.commit` 按 `(stage, partitionIndex, canonicalKey, intraIndex)` 排序，并列键当场判死。
- 所有活表/索引/提交访问走 `AccountSession.checkCoordinatorThread()`；已接入阶段的 worker 只写本地缓冲/分区副本，共享 `rows`/`debts`/`allocations`/`industries` 的写回在协调器。
- 区内市场“按区并行生成订单、协调器按 hex (q,r) 拼回”与原串行遍历序逐字相同；参与者去重用 `LinkedHashMap`，每格内 `planFor` 序不变。
- `MarketTopologyBook.terrainCostIndex` 与旧 `moveCostAt` 逐值等价；P1.1 落实。

## 2. Blocker

无。

## 3. Major（建议 R3/R4 前集中修复）

| # | 问题 | 位置 | 风险 | 建议修法 |
|---|---|---|---|---|
| M1 | 活表内层 Map 经合法访问器裸逸出：`AccountSession.householdGoods()` 等 8 个视图返回活内层表；`AccountView` 不再调 owner 守卫；`EconomyDayStepper` 的 8 个访问器是 public | `AccountSession.java:484-561`、`:582-586`；`EconomyDayStepper.java:150-187` | worker 只要拿到对象就能绕过 `commit` 直写活账本；R3 接跨区/偿还时极易踩 | 把 8 个访问器收窄到包内；给 worker 的只读视图加“已借出/只读”标记或类型分离 |
| M2 | 绝对值写口把负值静默钳到 0：`BufferedAccountTables.put/replaceAll` 用 `Math.max(0L, value)` | `BufferedAccountTables.java:319-326`、`:345` | 表达式符号写错不会红，只会在 `landAccountSession` 才现形；违反“护栏是红不是钳” | 改成 `if (value < 0) throw IllegalArgumentException(...)`，与 `GoodsAccount` 非负守卫同向 |
| M3 | `AccountSession.intentBuffer()` 与 `AccountDelta.merge()` 是 main 零调用的双入口；`merge` 键序不按 canonical 排序，与类注不一致 | `AccountSession.java:206-211`；`AccountDelta.java:71-86` | R3 可能误用第二个拼写点，产生非确定性 | 删除，或让 `merge` 返回 canonical 排序后的冻结副本；`intentBuffer` 只留一个官方入口 |
| M4 | 旧档迁移 `deriveMemberships` 只收集 pending 配额；非 pending 旧配额不进 `former` | `LegacyHouseholdMigration.java:128-137`、`:264-303` | 混合旧档下逐 lot 份额与真实劳动归属不一致；economy 守卫只有全局和一个房间看不见 | `former` 改为“凡能定位产业格的行都收集”；或明确把非 pending 排除并写进注释 + app 侧 fail-closed |
| M5 | `SubsistenceObligation.laborOf` 对未归一化 `ToCohort` 直接返回 0；同一状态下结算侧会抛 | `SubsistenceObligation.java:145-150`；`EconomySettlement.java:4436-4462` | 读口显示 0，结算抛，口径不一致；违反“不静默付 0” | 读口对未归一化 `ToCohort` 走 `unavailable` 具名项；或让 `laborOf` 抛同款异常，调用方显式处理 |
| M6 | 到货路径对“买方账不在会话”由旧口径的建账/跳过改为抛 | `EconomySettlement.java:1120-1126`；`OwnershipBooks.loadAccountSession` | 手搭夹具/跨区经营者未载入时整条推进失败；失败面比旧实现大 | 与旧口径对齐：能定位或由 app 落账的买家跳过 + 由 `fold` 落账；真正无主的才抛，并把语义写进注释/V 清单 |
| M7 | 收获/投入等并行阶段 `ProductionLedger` 合并顺序由“产业遍历序”变“分区序”，转移 id 变 `tr-<day>-p<partition>-<seq>` | `ProductionLedger.java:201-232`；`EconomySettlement.java:1407-1410/1570-1580` | 跨代码态 `seq` 不可比；按“当天第几条”写死的旧断言会红；同代码态内确定 | 明确 V 阶段比较口径“按业务键排序后比较集合”；代码注释写清兼容边界 |
| M8 | `AccountIntentBuffer` 冻结写与跨分区可见性：本地 `frozenGoods` 看不到另一分区同阶段放置的冻结 | `AccountIntentBuffer.java:127-133`、`:145-164`、`:311-312`；`AccountSession.java:361-372` | R3 把挂冻结算进并行时会在提交期才炸，且可能两处都不报错 | 规定“冻结只允许协调阶段写”；或 `FreezeIntent` 一律在提交器按 canonical 合并；R3 接入前先修 |

## 4. Minor

1. `EconomyOwnershipTimeParticipant` 已不在 Shell 注册，且不做 `MembershipWriteback.reconcile`；建议标 deprecated 或删除，避免 R3 接错入口。
2. `AccountPartitionKey.parseCanonical` 依赖“actor id 不含 `|`”的仓级约定；fail-closed 已有，建议加自检或 V 阶段测试钉住。
3. `AccountDelta.merge`/`AccountSession.intentBuffer` 死代码（同 M3）。
4. `LegacyHouseholdMigration` 合成的 `legacy-<view>` lot 只保证 Σ 守恒，不保证逐 lot == social；类注已如实记。
5. `EconomyData` 构造期自动跑旧档迁移：任何旧形状测试夹具都会被静默补 `memberships`/`useRights`；V 阶段适配旧测试时要点名。
6. `MarketTopologyBook.terrainCostIndex` 语义逐值等价，无需返工。
7. 区内市场并行拼回序确认与旧串行逐字相同，无需返工。

## 5. 必须留到 V 阶段实测的清单

| # | 事项 |
|---|---|
| V1 | 1/4/8 线程 canonical 一致（域状态/变更集/账户/读口四层） |
| V2 | 旧 tick360 store 迁移：`rebuildLegacy.requireViewTotals`、旧 DebtId、actor 账户搬家幂等、`ToCohort` 归一 |
| V3 | `Σ Membership.count(lot) == PopulationGroup.count(lot)` 的 tick0 seed / 旧档迁移 / 月度 births·deaths 三条路径 |
| V4 | `transfers`/`outputAccruals`/`ruleSettlements` 列表序与 id 兼容边界（M7） |
| V5 | 市场冻结净额回归（轮末 frozen 回基值） |
| V6 | `due > 0` 但 `take == 0` 的未成交/短缺口径，不得静默丢 |
| V7 | 性能：P1.1/P1.2/P1.3/P1.4/P1.5 的实际收益 |
| V8 | 既有测试对新 API 的适配与 `clean verify` |

## 6. R2 结构是否足以让 R3 复用

**有条件 = 是。** 可复用骨架：`SettlementStage` 具名序 + `PartitionBasis`、`PartitionPlan` canonical 分组、`SettlementExecutor` 计划序意向 + 单线程稳定提交、`AccountIntentBuffer` 快照+本地+影子、`ProductionLedger.Accumulator` 分区铸号 + 按分区序 absorb。
**前提**：先修 M1、M8、M3；否则 R3 的跨区成交（同时触碰两区账户/冻结）要么被迫完全留在协调器，要么必踩接缝。

## 7. 诚实边界

- 本次未跑任何测试、模拟、性能、重放；未改任何文件。
- `EconomySettlement.java`、`MarketSettlement.java` 未逐行通读全文；M7/M8 之外可能还有未覆盖排序细节。
