# D-032 C7b2 hotfix：0 价锚价不得强制成 1 毫运费（2026-10-21）

> 工作树：`/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`
> 上游：C10 test owner 新增 `CarrierC7LaneFreightTest` 实测失败。
> 只修此 main 缺陷；不写/跑测试，不 commit/push，不碰主树。

## 现象

C7a 锚价=0（明确免费）时，有本地 lane 应成交且运费=0、承运人不得收到 0‰ 普通货币债。
实际 buyer→carrier 出现 principal=1 的普通货币债。

定位：`simos-economy/src/main/java/io/mosire/simos/economy/time/HaulService.java:172-183`
`unitFreightMilli` 对 0 服务价使用 `Math.max(1L, ceilDiv(...))`，把 0 价强改为 1。

## 冻结修法

- `HaulService.unitFreightMilli(workPerGoodPerMille, servicePriceMilli)`：
  - `servicePriceMilli == 0` ⇒ 直接返回 **0**（明确免费，I7/A6）；
  - `servicePriceMilli > 0` ⇒ 保留原算式与 `max(1,...)` 整数粒度；
  - 负价仍拒绝；溢出仍 fail-closed。
- 不改 C7b2 matcher/债务路径；不改非 0 价运费；不改测试。
- 允许写 `simos-economy/src/main/java/io/mosire/simos/economy/time/HaulService.java`；
  禁止 test/pom/tools/test-world/主树/docs（除 `.superpowers` 账本）。

## 验收

```bash
tools/mvn-lock.sh -q -DskipTests compile -pl simos-app -am
```

rc=0；C10 复跑 `CarrierC7LaneFreightTest` 不再出现 0 价强制运费债。
账本 `.superpowers/sdd/2026-10-21-d032-carrier-c7b2-zero-freight-hotfix/impl-ledger.md`。
