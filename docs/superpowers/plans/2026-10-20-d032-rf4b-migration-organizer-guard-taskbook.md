# D-032 RF4b 任务书：迁移 organizer 整户合并的临时守卫

> **父计划：** `docs/superpowers/plans/2026-10-20-d032-rural-360-fix-plan.md` §3 F3/F4
> **状态：** 可派实现；只写生产代码到编译过，不写/不跑测试。

## 1. 现场

RF4a 后 360 tick 在 day244（记录为 tick300 后）触发：

```text
IllegalStateException: organizer-merge-requires-rebind:
organizer 合户必须走 production.RebindInstanceOperator:
unit=unit-farm@-1_6-HOUSEHOLD-hh--1_6-rural-landlord
source=hh--1_6-rural-landlord target=hh--1_5-rural-poor_peasant
at MigrationPopulationMove.applySource(:355)
```

`MigrationPopulationMove` 只有在 `source` 整户迁空（`empties=true`）且它是某个 roster 的 organizer 时抛此错。
当前 `production.RebindInstanceOperator` 尚未实现/接线，因此迁移执行到一半才失败，整个 advance 被拒。

## 2. 目标

在 `ModeMigrationSettlement.apply` **执行任何 move 之前**做临时守卫：

- 若一个 migration source 是任意 `productionRosters` 的 organizer，则**跳过该 source 的全部 move**；
- 不做半迁移、不合并 roster、不改资产/债务/人口；
- 源户与 unit/roster 保持原状，生产继续；
- 其它非 organizer source 的迁移照常；
- 记 INFO `MIGRATION_ORGANIZER_REBIND_UNSUPPORTED`：source、target(s)、unit(s)、
  `reason=production-rebind-not-implemented`；
- 这是**临时安全阀门**，不是最终迁移设计；最终应实现 `production.RebindInstanceOperator` 后再放开。

## 3. 约束

- 只允许改 `simos-economy/src/main/java/io/mosire/simos/economy/time/ModeMigrationSettlement.java`
  （如 `MigrationPopulationMove` 有更小必要改动，先报告）。
- 过滤发生在 `bySource`/`applySource` 之前；不得在任何 move 落账后才发现。
- 不修改 `ModeMigrationPolicy` 的计划生成；不改 Social/人口权威。
- 不新增持久组件/ChangeSet/Codec。
- 不实现 rebind；不删 0 地 unit；不改 F1/F2/F3/RF4a。
- 不写/改测试，不跑 test/verify。

## 4. 编译与交账

```bash
tools/mvn-lock.sh -q -DskipTests compile -pl simos-economy -am
```

真实 rc=0。报告改动方法、过滤规则、日志字段、数值行为变化、受影响硬编码/既有断言、未完成；
账本 `.superpowers/sdd/2026-10-20-d032-rf4b-migration-organizer-guard/impl-ledger.md`。

## 5. 验收锚点（测试 owner 后补）

- organizer source 的任何 move 都不执行，源户/roster/unit 完整；
- 非 organizer source 的 move 仍执行；
- 360 tick 不再出现 `organizer-merge-requires-rebind`；
- 无半迁移：源户人口/劳动/资产/债务/roster 在拒绝后逐值不变；
- 日志能按 source/target/unit 审计被跳过原因。
