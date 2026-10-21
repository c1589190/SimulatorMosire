# D-032 C9 caravan labor hotfix：让运输 unit 获得真实劳动（2026-10-21）

> 工作树：`/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`
> 上游：360 已确认有跨格需求（plan D=164916），但 `TRANSPORT_CAPACITY_BATCHES_CLEARED=0`、supply=0。
> 只修 C9 商队劳动接线，不写/不跑测试，不 commit/push，不碰主树。

## 已确认根因

- 32 个 caravan unit/roster/1,000,000 毫马已 seed；
- genesis `allocations` 中**没有 caravan 劳动配额**（活动不含 `caravan_transport`）；
- roster operator plannedLabor=0；hired_driver planned=22040，但该 driver 与农场雇工是同一户同一份 22040 预算
  （`D032_P5B2_HIRED_LABOR` 只有 farm 那条）；
- `ProductionRunSettlement` 实际 labor 只读 `HouseholdLaborCommitment` ⇒ contributions 空
  ⇒ `laborPerUnit=1000` 下 scale=0 ⇒ 服务产出 0。
- 另：seed roster 曾出现 unit id 带 rural-landed、organizer 是 urban-middle 的不一致，需一并一致化。

## 冻结修法

1. 在 `ProductionRuntimeSeeder` 中为每个 caravan unit 创建**真实劳动分配**：
   - 优先 operator OWNER **自雇**：从该 operator 户在本 hex 的 landed pool 取一份劳动；
   - 用 `appendAllocation`（唯一性别/预算算法）生成 allocation，activity = caravan unit id，activity 标签用新
     `TRANSPORT`（加入 `ACTIVITY_SEX_WEIGHT_PER_MILLE`，建议 600/400；不得复用 FARM 标签冒充）；
   - 该份劳动必须从 farm/weave 的同一份 `ruralDaily` 预算中**切出**（减少 farm total，保持 Σ ≤ 可用劳动），
     不得新增第二份劳动；
   - 至少覆盖 `CaravanTransportTemplate.LABOR_MILLI_PER_SCALE_UNIT`（1000）所需的日劳动；
   - `caravanRosterNode` 的 operator `plannedLaborMilliPerDay` 改为该真实份额；
   - 如果 operator 不在 rural pool（仅城市 owner），同样从对应 urban pool/craft 配额切出；不可切出 ⇒ 具名
     业务拒绝/INFO，不得伪造 0 劳动 roster。
2. 保留 hired_driver 的工资位置合同；但只有真实剩余劳动才可形成实际 driver commitment；不得重复使用
   farm 已占用的同一份劳动。
3. `caravanRosterNode` 的 unit id 与 organizer 必须来自**同一个 operator 来源**；
   若 `plan.unitId` 与 organizer household 不一致 ⇒ 具名 fail-closed，不静默生成矛盾 roster。
4. 每 tick 的 labor queue 必须能由该 roster/plannedLabor 重发 PRODUCTION commitment；
   若当前 queue 不重发，修最小入口使运输 unit 每 tick 有实际劳动。不得只 seed 一次然后假设永久有效。
5. 马匹 durable occupancy / C6 sink / C7 lane 不改；不改农场/织布/作坊的既有分配口径，除为运输切出的份额。
6. 允许改 `simos-app/src/main/java/io/mosire/simos/app/world/ProductionRuntimeSeeder.java`,
   `RuralLaborSeeder`/`WorldBalanceSeeder` 的必要常量，及 economy 侧 labor queue 的最小接线；
   禁止 test/pom/tools/test-world/主树/docs（除 `.superpowers` 账本）。

## 验收

```bash
tools/mvn-lock.sh -q -DskipTests compile -pl simos-app -am
```

rc=0；控制方随后跑 360，期望：
- `TRANSPORT_CAPACITY_BATCH_*` >0（至少一次 accumulate/clear）
- `anchor supplyWorkMilli >0`
- 跨 hex 成交/运费/freight debt 随需求出现

账本 `.superpowers/sdd/2026-10-21-d032-carrier-c9-caravan-labor-hotfix/impl-ledger.md`，
报告实际劳动份额、Σ 预算证据、unit/roster 一致性修法、编译 rc、未完成/blocker。
