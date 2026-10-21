# 生产缺陷修复任务书：DEF-5 旧 ClassSlide 不得改二元农村位置

> 父序列锚点：v4 §3 S7-C（RC7 flow table）、§7 向下流动；旧 `ClassSlideSettlement` 是 pre-v4 阶层滑落路径。
> 工作树：`/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`。
> 测试 owner 已停；只写生产代码到 compile，不写/不跑测试。

## 1. 现象（T1 报告）

- `ClassSlideSettlement` 在二元农村把 `LANDED` 户的 `currentPositionId` 直接滑到 `LANDLESS`
  （`ClassLadderBook.nearestLower`，ClassSlideSettlement.java:205-249）。
- 次日 `HouseholdMergeBook` 按 `(hex,cultureKey,mode,classNature)` 把它与真正 landless 户合并。
- 两条原债务端点重键到同一 `(debtor,creditor,unit,terms)` ⇒
  `household-merge-debt-id-collision`，正常世界 advance 失败。
- 日志可见：
  `source=hh--1_-1-rural-landless_laborer survivor=hh--1_-1-rural-landed_peasant ...`，
  同时 plan 已把 `hh--1_-1-rural-landed_peasant` 的 classNature 记成 LANDLESS。

## 2. 根因与冻结修复

根因是**两套阶层机制并存**：

- v4 二元农村的向下流动 = RC7 flow table：LANDED 户保留至少 1 人，按债务扩大把**人口/钱/货/债**流向 LANDLESS 目标；
- 旧 `ClassSlideSettlement` 则把整个户的 `currentPositionId` 改成下一级；在二元农村下一级就是 LANDLESS。

修复原则：**position 声明了 classNature 的户（LANDED/LANDLESS）不进入旧 ClassSlide**，其向下流动只走 RC7 flow table；
classNature empty 的旧/城市位置保持旧 ClassSlide 行为。

实现：

1. 在 `ClassSlideSettlement` 新增私有 helper：
   ```text
   hasDeclaredClassNature(
       HouseholdClassMembership membership,
       Map<ClassPositionId, ProductionRole> positions)
   ```
   遍历 `membership.effectivePositionIds()`，任一 `positions.get(id).classNature().isPresent()` ⇒ true。
2. `settleAtMarketRoundEnd` 逐户循环内、估值之前：
   ```text
   HouseholdClassMembership membership = classMemberships.get(household);
   if (hasDeclaredClassNature(membership, positions)) {
       skippedPositionLevelClassNature++;
       log CLASS_SLIDE_SKIPPED reason=position-level-class-nature-delegated-to-flow-table
       continue;
   }
   ```
3. `SlideRound` 增加 `long skippedPositionLevelClassNature`（放在 `skippedNoLowerTarget` 后），
   更新两个构造点（空态和正常返回）与 `SettlementDayMarketPipeline` 的 `CLASS_SLIDE_ROUND` 日志字段。
4. 禁止把 LANDED/LANDLESS 的 `currentPositionId` 改成另一 class nature；RC7 flow table 继续只搬人/钱/货/债，LAND 不动。

## 3. 允许修改

```text
simos-economy/src/main/java/io/mosire/simos/economy/time/ClassSlideSettlement.java
simos-economy/src/main/java/io/mosire/simos/economy/time/SettlementDayMarketPipeline.java（只加日志字段）
```

禁止 tests、seeder、EconomyData/ChangeSet/Codec、MarketFreezeBook、HouseholdMergeBook/HouseholdDebtMergeBook、主树；不 commit/push。

## 4. 验收锚点（测试 owner 后补）

- LANDED 户债务再高也不被 ClassSlide 改成 LANDLESS；`skippedPositionLevelClassNature` +1，日志具名；
- LANDLESS 户也不被 ClassSlide 改位置；
- classNature empty 的旧位置仍按原 ClassSlide 滑落（旧行为不变）；
- 合户不再出现 LANDED/landless 跨 nature 合并，也不因该路径触发 debt-id-collision；
- `CLASS_SLIDE_ROUND` 日志仍含旧字段并新增 skip 计数。

## 5. 编译

```bash
cd /home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module
tools/mvn-lock.sh -q spotless:apply
tools/mvn-lock.sh -q -DskipTests compile -pl simos-economy,simos-app -am
```

- 账本：`.superpowers/sdd/2026-10-20-d032-def5-class-slide-two-position/impl-ledger.md`。
- 报告：改动文件、compile rc、skip 判据、旧路径不变说明、未验证项。
