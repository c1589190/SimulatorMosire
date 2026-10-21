# 生产缺陷修复任务书：DEF-7 锁定雇佣重复承诺导致 ΣPRODUCTION 超预算

> 父序列锚点：v2 M3 EmploymentLock → M6 LaborQueue → 构造期 `Σ PRODUCTION ≤ budget`。
> 工作树：`/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`。
> 测试 owner 已停；只写生产代码到 compile，不写/不跑测试。

## 1. 现象（T1 360）

360 tick 从 day17 起全部 `ADVANCE_EXCEPTION`：

```text
家户 hh--1_1-rural-landless_laborer 的生产劳动配额之和 44040
超过每 tick 预算 44000（GOV_SERVICE 0）
```

日志同一 tick：

```text
HOUSEHOLD_LABOR_CAPPED productionLaborMilli=44080 availableForProductionMilli=44000
D032_P3_EMPLOYMENT_LOCKED ... worker=hh--1_1-rural-landless_laborer
  unit=unit-farm@-1_1-HOUSEHOLD-hh--1_1-rural-landed_peasant role=hired_laborer
  laborMilliPerDay=22040
```

44080 = 两条同一 `(worker, unit, role)` 的 PRODUCTION 承诺（各 22040）。`EmploymentLock` 只有一条 22040，
但 `LaborQueuePlanBook` 对 locked slot 只做“保护不排队”，`preserveIntoBudget` 又把两条 protected 行都整额保留，
于是最终 44040。

## 2. 根因

`LaborQueuePlanBook.applyInternal`：

- `lockedSlots` 按 `(unit, role)` 去重并保护；
- 但既有 `laborCommitments` 中可能已有**同一 `(household, activity, role)` 的多条旧 PRODUCTION 行**（group/period 不同）；
- 队列不处理 locked slot ⇒ 不删重复；
- `preserveIntoBudget` 把 protected 行整额计入 `protectedLabor`，重复行被保护两次 ⇒ 预算无法缩。

## 3. 冻结修复

在 `LaborQueuePlanBook` 中新增包内 helper：

```text
static int reconcileLockedCommitments(
    LinkedHashMap<LaborAllocationId, HouseholdLaborCommitment> laborCommitments,
    List<EmploymentLock> locks,
    long day)
```

规则：

1. `locks == null || empty` ⇒ no-op；
2. 以 `LockedSlot(household=worker, unit, role)` 建锁表；同一 slot 多条 lock 必须 `laborMilliPerDay` 相同，否则具名 fail-closed；
3. 扫 `laborCommitments`，按 `LockedSlot` 收集 `LABOR_COMMITMENT` 行（只 PRODUCTION + productionRole present）；
4. 同一 locked slot 多条旧行：
   - 按 `LaborAllocationId.value()` 升序；
   - 保留第一条（最小 id）作为唯一模板；
   - 其余删除；
   - 保留行的 `laborMilli` 设为该 lock 的 `laborMilliPerDay`（lock 是 M3 的权威快照）；
   - INFO `LABOR_LOCKED_COMMITMENT_DEDUPED`：household/unit/role/kept/removed/laborMilli；
5. 只有一条旧行时不改其值（避免无谓行为变化）；
6. 同一 locked slot 清理后仍多于 1 条 ⇒ 契约故障 ERROR + fail-closed；
7. 在 `applyInternal` 构建 `laborCommitmentsByHousehold` **之前**调用一次；
8. 不删 GOV_SERVICE；不改非 locked slot 的既有逻辑。

## 4. 允许修改

```text
simos-economy/src/main/java/io/mosire/simos/economy/time/LaborQueuePlanBook.java
```

必要时只加 `LaborQueueWritebackBook` 只读 helper；禁止 tests、seeder、EconomyData/ChangeSet/Codec、生产名册/锁生成公式、主树；不 commit/push。

## 5. 验收锚点（测试 owner 后补）

- 同一 worker/unit/role 两条 22040 旧行 + 一条 22040 lock ⇒ 清理后一条 22040，ΣPRODUCTION ≤ 44000；
- 两条旧行 + 无 lock ⇒ 不动（队列原路径）；
- 两条旧行 + 两条同值 lock ⇒ 清理后一条，lock 冲突不误报；
- 两条旧行 + 两条不同值 lock ⇒ 具名 fail-closed；
- GOV_SERVICE 行不被删/缩；
- 360 tick day17 不再超预算。

## 6. 编译

```bash
cd /home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module
tools/mvn-lock.sh -q spotless:apply
tools/mvn-lock.sh -q -DskipTests compile -pl simos-economy,simos-app -am
```

- 账本：`.superpowers/sdd/2026-10-20-d032-def7-locked-commitment-duplicate/impl-ledger.md`。
- 报告：改动位置、compile rc、去重规则、未验证项。
