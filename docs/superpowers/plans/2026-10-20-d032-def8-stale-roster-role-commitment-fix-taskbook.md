# 生产缺陷修复任务书：DEF-8 陈旧名册角色劳动承诺

> 父序列锚点：v2 M3 名册 → M6 劳动队列 → M7 生产执行；`ProductionRoster` 是参与户×角色的唯一权威。
> 工作树：`/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`。
> 测试 owner 已停；只写生产代码到 compile，不写/不跑测试。

## 1. 现象（T1 360）

360 tick：`OK=120 / REJECTED=240`，tick 121 起同一异常：

```text
IllegalStateException:role-unknown:
劳动承诺的角色不在名册里（跨切片不一致）:
unit=unit-farm@-1_0-HOUSEHOLD-hh--1_0-rural-landed_peasant
household=hh--1_0-rural-landed_peasant
role=hired_laborer
```

抛点：`ProductionRunSettlement.java:353-360`。

同 tick 的 `D032_P3_EMPLOYMENT_LOCKED` 正确记录：

```text
employer=hh--1_0-rural-landed_peasant
worker=hh--1_0-rural-landless_laborer
role=hired_laborer
```

⇒ 名册/锁本身是 `(landless worker → hired_laborer)`；但 `laborCommitments` 里残留了一条
`(landed organizer → hired_laborer)` 的旧 PRODUCTION 承诺。

## 2. 根因

- `ProductionRoster` 已把 `(household, role)` 限定为唯一合法参与组合；
- 劳动承诺表可能因历史队列/迁移/组织重建留下**角色已不在名册**的 PRODUCTION 行；
- `ProductionRunSettlement` 直接按承诺的 `(household, role)` 校验名册 ⇒ fail-closed；
- 当前 `applyPlan`/`preserveIntoBudget` 的 `UnitRole` 只按 `(unit, role)` 保护/排队，不校验 household 是否仍在该 role；
  `changed`/锁清理也无法识别“household 不再是该 role 的参与者”这一 stale 行。

## 3. 冻结修复

新增包内 helper（放 `LaborQueuePlanBook` 或独立 `LaborCommitmentReconcileBook`）：

```text
static int pruneStaleRoleCommitments(
    Map<LaborAllocationId, HouseholdLaborCommitment> laborCommitments,
    Map<ProductionUnitId, ProductionProcess> units,
    Map<ProductionUnitId, ProductionRoster> rosters,
    long day)
```

规则：

1. 只处理 `kind == PRODUCTION` 且 `productionRole` present 的承诺；
2. `unit = new ProductionUnitId(commitment.activity())`；
3. `roster = rosters.get(unit)`；roster 缺失 ⇒ 不动（旧 unit/无 roster 的既有路径）；
4. roster 存在且 `roster.participant(commitment.household(), role).isEmpty()` ⇒ 删除该承诺，
   INFO `LABOR_COMMITMENT_STALE_ROLE_REMOVED`（day/household/unit/role/id/reason=role-not-in-roster）；
5. `GOV_SERVICE`、零/负 labor 的旧路径不动；不新增承诺；不修改 roster；
6. 遍历按 `LaborAllocationId.value()` 稳定序，避免迭代序依赖；
7. 清理后必须重扫一次，确认无 stale 行；仍有 ⇒ 具名 ERROR + fail-closed。

调用点：

- `LaborQueuePlanBook.applyInternal`：在构建 `laborCommitmentsByHousehold`/`plannedLabor` 之前调用一次；
- `ProductionRunSettlement.driveProductionDay`：在聚合 `contributionsByUnit` 之前调用一次（M7 防线；两处都幂等）。

## 4. 不可破坏

- `ProductionRoster` 仍是角色权威；不得用 class nature 猜角色；
- 不得删除 `GOV_SERVICE`；
- 不得对无 roster 的旧 unit 做清理；
- 清理只删“当前名册明确没有该 (household,role)”的行，不碰合法角色/多 lot 行；
- 不新增/改 EconomyData/ChangeSet/Codec 形状；不改 LAND/钱/债/工资公式。

## 5. 允许修改

```text
simos-economy/src/main/java/io/mosire/simos/economy/time/LaborQueuePlanBook.java
simos-economy/src/main/java/io/mosire/simos/economy/time/ProductionRunSettlement.java
```

必要时新增一个 package-private helper 文件；禁止 tests、seeder、EconomyData/ChangeSet/Codec、生产名册/锁公式、主树；不 commit/push。

## 6. 验收锚点（测试 owner 后补）

- 名册只有 `(landed, operator)` 与 `(landless, hired_laborer)`，承诺表多一条 `(landed,hired_laborer)` ⇒ M6/M7 前被删，不抛 role-unknown；
- 承诺表 `(landless,hired_laborer)` 合法 ⇒ 保留；
- 无 roster 的旧 unit 的多角色承诺 ⇒ 不清理，旧路径行为不变；
- GOV_SERVICE 行 ⇒ 不删；
- 多 lot 合法承诺 ⇒ 保留；
- 360 tick day121 不再 role-unknown。

## 7. 编译

```bash
cd /home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module
tools/mvn-lock.sh -q spotless:apply
tools/mvn-lock.sh -q -DskipTests compile -pl simos-economy,simos-app -am
```

- 账本：`.superpowers/sdd/2026-10-20-d032-def8-stale-roster-role/impl-ledger.md`。
- 报告：改动位置、compile rc、清理规则、未验证项。
