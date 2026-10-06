# 2026-10-17 Unit 对接：LevyRegion manpower 接 Social 工单

> 依据：`docs/superpowers/plans/2026-10-11-unit-integration-p1.md` §3 P1.5 边界；目标 §7.3 P1 的
> “levy 等人口变动工具统一走 Social 工单”。
> 基线：`HEAD 238f66a6`（P1.5 select/dispatch 已完成）。

## 1. 现状

- `LevyRegionPlan.plan` 在 P1.0 起对 `manpower>0` plan 级具名拒，避免旧 `social.SeedGroups` 直接删人；
- 粮/钱/布维度正常：从区域家户账户扣、进单位国库家户账户（`actor.AdjustAccounts`）；
- 人力维度需要真实去向：进入被征单位的**人口家户**，走 `social.SubmitHouseholdWorkOrder` 的
  `TRANSFER_MEMBERS`。

## 2. 目标与目标家户口径

- 被征单位 `unitId` 必须存在且有 jurisdiction/区域校验照旧；
- **目标家户** = `unit.households()` 中**恰一个**家户，且该家户在 Social 存在：
  - GOV 单位 ⇒ 它的 `hh-gov-<unitId>`（政府家户即其人口户）；
  - raiseUnit/select/dispatch 建的单位 ⇒ 它的 `hh-unit:<unitId>`；
  - `unit.households()` 为空或多个 ⇒ **plan 级具名拒**（不按列表顺序猜；多户单位需要显式 target 政策，留后续）；
- 来源 = region.hexes() 内的**家户成员份额**（`HouseholdManpowerAllocator.allocateMalesOfAdult`），
  排除目标家户自身（自我转移会被域层拒）；`MALE+ADULT` 口径与征兵/组军一致；
- 不足 ⇒ 整条 plan 具名拒（不部分、不截断）；
- 守恒：Σ来源 taken == manpower；目标家户 +manpower；世界 Social 总人口不变。

## 3. 批序

```text
1. [social.SubmitHouseholdWorkOrder]   // 仅 manpower>0
   orderId=levy-manpower:<batchId>:<unitId>
   target=目标家户
   plan=逐 share TRANSFER_MEMBERS(from=来源家户, to=目标家户, lotId, count)
2. [actor.AdjustAccounts]              // 仅 grain/money/cloth 任一 >0，载荷/顺序照旧
3. sd.PutInfo                          // value 的 manpower 段改为 shares（household/lot/taken/hex）
```

- 两条腿共享同一 batchId/branch/expectedRevision；任一失败整批拒、零 revision；
- 目标家户账户/economy 行不在此批创建：GOV/raiseUnit 路径已由 createOffice/P3 建齐；缺行会在
  work order 目标不存在或后续 advance 具名拒，不静默造户。

## 4. 验收

1. `tools/mvn-lock.sh -q -pl simos-app -am -DskipTests clean compile` rc=0；`package` rc=0；
2. fresh harness：
   - `simos.gov.createOffice` 建 GOV（政府家户/账户）或 `simos.unit.raiseUnit` 建军；
   - `simos.unit.levyRegion` manpower>0（可同时给 grain/money）：
     - apply committed；批内 `social.SubmitHouseholdWorkOrder`（+ `actor.AdjustAccounts`）无 `SeedGroups`；
     - 来源家户合计 −manpower、目标家户 +manpower、世界 Social 总人口不变；
     - 粮/钱路径与旧口径逐值一致；
     - 次日 advance 0 CLASSROW/UNIT/time-budget 异常；
   - 来源不足 ⇒ BAD_REQUEST、零 revision、人口不变；
   - 多户单位/单位无家户 ⇒ BAD_REQUEST 具名拒；
   - 重启同 store：目标家户人口/账户/unit.households 一致。
3. 不跑 test/test-compile/verify；不 commit/push（控制方最后做）。

## 5. 文件范围

**允许**
- `simos-app/src/main/java/io/mosire/simos/app/tools/write/LevyRegionPlan.java`
- `simos-app/src/main/java/io/mosire/simos/app/tools/write/LevyRegionTool.java`
- 必要的常量/视图/javadoc 同步

**禁止**
- 改 Social/Unit/Economy 持久组件与 handler；
- 改 `SpawnArmy`（GM 凭空建军政策未裁定，保持 fail-closed）；
- 改测试；commit/push。

---

## 6. 实施状态（2026-10-17）

✅ 已实现并验收：

- `LevyRegionPlan`：manpower 目标 = `unit.households()` 恰一个且存在于 Social；来源改
  `HouseholdManpowerAllocator.allocateMalesOfAdult`（排除目标户）；粮/钱/布维度与 treasury 解析未动；
- `LevyRegionTool`：批序 `SubmitHouseholdWorkOrder? → AdjustAccounts? → PutInfo`；不再发 SeedGroups；
  work order `orderId=levy-manpower:<batchId>:<unitId>`、source.module=gov；
- 主控独立 smoke（`/tmp/LevySmoke.java`）：manpower=4 + grain=5/money=3：
  - committed；批内无 SeedGroups；来源户 70→66、目标政府户 6→10、世界 4000→4000；
  - 粮/钱扣增与旧口径一致；工作单标记 `work-order:levy-manpower:*` 落账；
  - 次日 advance 目标户 Social=10 == economy=10，0 CLASSROW/UNIT/time-budget 异常；
  - 负例（来源不足/多户/无户/目标不在 Social）全部 BAD_REQUEST 零 revision；
  - 重启同 store：家户/账户/unit.households 一致；
- compile/package rc=0；未跑 Java test/verify/spotless。

边界：多户单位需要显式 target 政策；SpawnArmy/GovAbsorbUnit 仍按未裁定策略 fail-closed；P4c 工具端/测试迁移后置。
