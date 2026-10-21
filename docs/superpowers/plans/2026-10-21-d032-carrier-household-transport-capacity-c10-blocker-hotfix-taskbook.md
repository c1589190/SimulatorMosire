# D-032 C10 blocker hotfix：合并非生产承诺 + durable-only 资产预算（2026-10-21）

> 工作树：`/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`
> 上游：C10 test owner 全量 test 发现两个 main 缺陷。
> 只修这两个缺陷；不写/跑测试，不 commit/push，不碰主树。

## 缺陷 A：合户把 GOV_SERVICE 承诺户卷入导致整次 advance 失败

实测：`HouseholdMergeSettlement.preflightSource`（约 :449）对 source 的
`HouseholdLaborCommitment.kind != PRODUCTION` 抛
`household-transfer-non-production-commitment-cannot-vanish`；C2 去掉 mode 后，gov-service 承诺户进入同 key 合并。

冻结修法（安全优先）：

- 在合户执行前过滤/跳过**含非 PRODUCTION 承诺的 source**；不得把 GOV_SERVICE 承诺静默重绑/降级/丢失。
- 若过滤后 plan 只剩一个成员 ⇒ 该 plan 不执行；其余 plan 继续。
- 记录 INFO `HOUSEHOLD_MERGE_SKIPPED_NON_PRODUCTION_COMMITMENT`（source/commitment/kind）。
- 保留 `preflightSource` 对真实非法 source 的 fail-closed；C2 merge key/其他绑定不变。
- 允许改 `HouseholdMergeSettlement.java`（必要时 `HouseholdMergeBook`/调用方最小修整）。

## 缺陷 B：durable-only 运输配方在 D4 资产预算里无 AssetKind

实测：`ProductionProcessBook.assetOccupancyBudget` 约 :297 在
`capacityPerUnit` 为空（只有 durableCommodityCapacityPerUnit）时抛
`asset-occupancy-horizon-unsupported`。

冻结修法：

- durable-only（`capacityPerUnit` 空且 durableCommodityCapacity 非空）的 unit 不产生 AssetKind occupancy request；
  跳过该 unit 的资产预算，不做假 AssetKind、不默认 1。
- 其他有 AssetKind capacity 的 unit 行为不变；真正 empty/无锚的非法定义仍由定义构造期/fail-closed 拒绝。
- 允许改 `ProductionProcessBook.java`（必要时 `IndustryDefinitionView` 读取只读 durable map）。

## 验收

```bash
tools/mvn-lock.sh -q -DskipTests compile -pl simos-app -am
```

rc=0；C10 test owner 复跑受影响测试。账本 `.superpowers/sdd/2026-10-21-d032-carrier-c10-blocker-hotfix/impl-ledger.md`。
