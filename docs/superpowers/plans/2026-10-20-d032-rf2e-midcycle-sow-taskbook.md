# D-032 RF2e 任务书：生产周期中途首次入选时补扣种子

> **父计划：** `docs/superpowers/plans/2026-10-20-d032-rural-360-fix-plan.md`
> **状态：** 可派实现；只写生产代码到编译过，不写/不跑测试。

## 1. 现场

RF2d 的 360 tick 已经全部 `status=OK`，tick120 有 `productionGrainMilli=525,456,000`，但 tick240/360 的农场产出又为 0。
根因链：

- tick120 关账后新周期开始，但当日 M3 只选到 4 个 craft 位置；
- tick121 只选到 1 个 craft；tick122 选择 71 个 weave，仍无 farm；
- tick125 才重新选到 62 个 farm；
- 但 `ProductionInputSettlement.surveyInputDemands()` 在 `:395` 只允许 `progressDays == 0` 扣周期投入；
  这些 farm 已经是 `progressDays=4`，且 `cycleInputUsedMilli` 为空 ⇒ **永远补不到种子**；
- tick240 因此有 128 个 farm 有劳动、但只有 42 个有 grain 输入；其余关账 `finalScale=0`。

这不是“周期中途重复扣种”：这些 unit 本周期一次都还没扣过投入；它们是在周期中途才第一次被 M3/M6 实际选中。

## 2. 目标

- 生产周期内**首次**被实际选中、且 `cycleInputUsedMilli` 仍为空的 unit，允许在本次 M7 补扣本周期投入。
- 已扣过投入的 unit 不得重复扣；未入选/无实际劳动的 unit 仍不得扣。
- 仍按 RF2 的：
  - `producingUnits` 资格过滤；
  - `drawableStockOf` 保留一 tick 口粮；
  - 独立家户不共享 grain 种子池；
  - 真实扣减只走 `recordInputDraw`/AccountSession。
- 补扣成功后 unit 的 `cycleInputUsedMilli` 变为非空，下一次自然不再补扣。

## 3. 约束设计

- 在 `ProductionInputSettlement.surveyInputDemands(...)` 的周期门：
  ```java
  boolean cycleStart = unit.progressDays() == 0L;
  boolean firstSowInCycle =
      unit.progressDays() > 0L
      && unit.cycleInputUsedMilli().isEmpty()
      && producingUnits != null
      && producingUnits.contains(id);
  if (!cycleStart && !firstSowInCycle) continue;
  ```
  （也可等价实现；`producingUnits == null` 的 legacy 路径保持原行为。）
- 为空表 `inputPerUnit` / 产能 0 / assetRejection 的分支顺序不变。
- 在 `PRODUCTION_INPUT_SELECTION` 或新增 DEBUG 中记录 `cycleStartUnits` 与 `firstSowUnits` 数量。
- 不改变 `ProductionProcess.cycleInputUsedMilli` 的权威；不新增状态。
- 不在 M7 对同一 unit 同 tick 多次调用；现有一 tick 一次调用保证。
- 不改变 RF2 的口粮保留/共享池拆分/未入选不扣规则。

## 4. 允许文件

- `simos-economy/src/main/java/io/mosire/simos/economy/time/ProductionInputSettlement.java`
- 仅编译必需时：同目录最小相邻文件（先报告）

禁止：测试、EconomyData/ChangeSet/Codec、production 定义、app seeder、主树、GUI/服务。

## 5. 编译与交账

```bash
tools/mvn-lock.sh -q -DskipTests compile -pl simos-economy -am
```

真实 rc=0。报告改动方法、数值行为变化、受影响硬编码/既有断言、未完成/未验证；
账本 `.superpowers/sdd/2026-10-20-d032-rf2e-midcycle-sow/impl-ledger.md`。

## 6. 验收锚点（测试 owner 后补）

- 周期第一天首次入选 ⇒ 扣种一次；
- 周期中途首次入选（progress>0 且 cycleInputUsed 空）⇒ 补扣一次；
- 同 unit 下一 tick 不再补扣；
- 未入选/无劳动 ⇒ 不扣；
- 补扣后户粮仍保留 ≥1 tick 口粮；同 hex 独立户仍不共享 grain seed；
- 后续 360 tick 第二/第三周期 farm `productionGrainMilli > 0`（测试 owner 复跑）。

## 7. 用户原话

```text
需求被允许挂价了吗？家户提的需求，面对市场不管多贵都会直接接受的；其他没啥问题，记录一下你的修复方案，开始吧
```
