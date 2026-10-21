# D-032 C10 blocker C hotfix：合户重绑生产劳动承诺 actor/household（2026-10-21）

> 工作树：`/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`
> 上游：C10 `GovGenesisZ6Test` 实测 `economy.production-references` 拒绝。
> 只修此 main 缺陷；不写/跑测试，不 commit/push，不碰主树。

## 现象

合户后 unit operator 已变为 survivor，但 `HouseholdLaborCommitment` 仍指 source：
`activity=unit-craft@0_2-HOUSEHOLD-hh-0_2-urban-middle_peasant`，实例 operator 已是 rural survivor；
actor 仍是旧户 ⇒ `劳动配额 activity 指名的实例与 actor 不一致`。

## 冻结修法

- 在合户生产重绑路径（`HouseholdMergeSettlement.rebindProduction`）里，reoperate 后：
  - 扫 source 名下 `LaborCommitmentKind.PRODUCTION` 的 commitment；
  - 按 `HouseholdLaborCommitment.idOfProduction(unitId, productionRole, group, survivor)` 生成新 id；
  - 复制 commitment，`household=survivor`、`actor=HouseholdActors.of(survivor)`，保留 `group/activity/laborMilli/period/kind/productionRole`；
  - 从 sheet.laborCommitments 删除旧 key、写入新 key；
  - 若新 id 已存在 ⇒ 合并同角色/同 group 的 laborMilli（或具名 fail-closed；优先确定性相加）；
  - 非 PRODUCTION/GOV_SERVICE 不在本路径（C10 blocker A 已过滤），不得静默改。
- 只改 main；允许 `HouseholdMergeSettlement.java`（必要时 `HouseholdMergeRebindBook` 最小配合）。
- 不新增第二套 labor 账；不改 roster/asset/unit id 语义；不写测试。
- 禁止 test/pom/tools/test-world/主树/docs（除 `.superpowers` 账本）。

## 验收

```bash
tools/mvn-lock.sh -q -DskipTests compile -pl simos-app -am
```

rc=0；C10 复跑 `GovGenesisZ6Test` 不再因 production-references/actor mismatch 红。
账本 `.superpowers/sdd/2026-10-21-d032-carrier-c10-blocker-c-hotfix/impl-ledger.md`。
