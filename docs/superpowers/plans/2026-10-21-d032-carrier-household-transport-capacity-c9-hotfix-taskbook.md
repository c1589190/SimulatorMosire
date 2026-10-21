# D-032 C9 hotfix 任务书：运输马匹 owner 过滤掉合成聚合主体（2026-10-21）

> 工作树：`/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`
> 上游：C9 已完成 compile；C10 test owner 全量 app test 发现 34 个同一 main 缺陷。
> 只修这个缺陷，不扩大范围；不写/跑测试，不 commit/push，不碰主树。

## 现象（test owner 实测）

`ProductionRuntimeSeeder.seedTransportHorses` 在 `householdLocations.get(owner)==null` 时抛：

```text
transport-horse-owner-location-missing: hex=... household=weave@...
```

`ProductionRuntimeSeeder.ownerHouseholdsOf(landHoldings, assetShares)` 把 `assetShares` 中
`owner.kind=HOUSEHOLD` 的**合成聚合经营者** `weave@<hex>` 也算成 OWNER；但该主体没有真实
`householdLocations` / `householdStocks` / 家户行。合成聚合主体不是真实 OWNER 家户，不能取得马匹库存。

## 冻结修法

- 只修改 `ProductionRuntimeSeeder`：
  - `ownerHouseholdsOf` 增加/使用真实家户存在性过滤（至少 `householdLocations.containsKey(owner)`；
    如还有真实家户行/库存挂点，一并作为同一判据）；
  - 过滤后的 `ownerHouseholds` 只含该 hex 真实家户；
  - `seedTransportHorses` 的“缺 location/stock ⇒ fail-closed”保留给**真实候选**缺数据；
  - `transportOperatorHousehold` 仍必须是真实候选，否则具名 fail-closed；
  - 记录 INFO/DEBUG 合成聚合被跳过（reason=`synthetic-aggregate-not-household`），不静默。
- 不改马匹数量/价格/seed 口径；不新增第二本马匹账；不改 C1–C8 语义；不改测试。
- 允许写 `simos-app/src/main/java/io/mosire/simos/app/world/ProductionRuntimeSeeder.java`；
  禁止 test/pom/tools/test-world/主树/docs（除 `.superpowers` 账本）。

## 验收

```bash
tools/mvn-lock.sh -q -DskipTests compile -pl simos-app -am
```

rc=0；被 test owner 复跑的 34 个 app 测试不再因 `weave@` location 缺失而红。
不写/不跑测试（test owner 会复跑）；写账本 `.superpowers/sdd/2026-10-21-d032-carrier-c9-hotfix/impl-ledger.md`。
