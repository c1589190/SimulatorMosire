# 2026-10-13 Unit 对接 P2：战斗人员伤亡回写 Social

> 依据：`docs/superpowers/plans/2026-10-09-social-vital-rates-per-tick-plan.md` §7.3 P2；
> 基线：`HEAD 0fded9d2`（P1/P1.1/P1.2/P1.3 + P3 已完成）。
> 现状（只读定位）：`simos-app/.../ResolveCombatPlan.java:114-126` 对 `CombatUnitLoss.manpower()` 非空直接具名拒：
> “Unit.manpower 已退役，人力战损必须落到 Social 家户成员批次；本工具尚未接线该写口”。

## 1. 目标与边界

- 战斗结局里的 **人员损失** 改为：从涉事 `Unit.households` 的 Social 成员份额里实际减人；
  Unit 侧只保留装备损失（`unit.AdjustComposition` 的 `equipment` 维度）。
- 人员损失走 Social 工单 `social.SubmitHouseholdWorkOrder`（`REMOVE_MEMBERS` 步骤），不写 Unit 第二本 headcount。
- 装备损失仍走 `unit.AdjustComposition`（equipment 维度），本批不改其语义。
- 不新建持久组件；不改 `CombatRecord`/`CombatOutcome`/`CombatUnitLoss` 形状；不改测试。

## 2. 战斗人员损失的选择口径（P2 决定）

- `CombatUnitLoss.manpower()` 是 `List<CompositionDelta>`；P2 只接受 **负增量**（`amount < 0`），
  每条损失的人数 = `|amount|`；出现正增量或 0 混合 ⇒ 具名拒（“增援/补员”另开批次，本批不猜）。
- 涉事单位 = `loss.unit()`；单位必须存在；其 `Unit.households()` 必须非空且能提供足够合格人员。
- **合格人员口径**：`MALE + AgeBracket.ADULT`（与征兵/组军的来源口径一致；不让儿童/老人上战损）。
  不足 ⇒ 整条 plan 具名拒（带 unit / requested / available / 缺口），不部分扣、不换年龄档。
- 选人实现：扩展 `HouseholdManpowerAllocator` 增加“指定家户集合 + 允许 UNIT 家户”的入口
  （复用同一候选/排序/瀑布实现；UNIT 家户 `ManpowerShare.hex` 可空）。顺序 = household id 升序 → lotId 升序。
- 每个单位一张 Social 工单：
  - `orderId = combat-casualty:<combatId>:<stageId>:<unitId>`；
  - `target` = 第一个被抽取家户（工单要求 target 被 plan 引用）；
  - `plan` = 逐 share 的 `REMOVE_MEMBERS(householdId, lotId, count)`；
  - `source.module = "army"`，reason 含 combat/stage/unit；
  - 同一 stage 重复判定由 stage.resolved 守卫挡住；工单 orderId 另做幂等双保险。

## 3. 批顺序

`ResolveCombatPlan` 的 apply 批固定为：

1. 对每个涉事 unit：
   - 若有人员损失 ⇒ `social.SubmitHouseholdWorkOrder`（按 stage 内 unit 的 stable 顺序，逐 unit 一张）；
   - 若有装备损失 ⇒ `unit.AdjustComposition`（只带 `equipment`，不带 `manpower`；载荷仍按原 `{id,equipment}` 形状，
     空 manpower 省略）；
2. `unit.ResolveCombatStage`（更新交战记录判定）；
3. 全部阶段判定完时逐单位 `unit.SetStateDescription`（清状态链接，原逻辑保留）；
4. `sd.PutInfo`（如原工具已有则保留）。

- 没有任何人员损失时行为不变；装备损失照旧。
- 人员损失为 0 的 unit 不发工单。
- 任一工单失败 ⇒ 整批拒、零 revision（与其余组合工具同原子语义）。

## 4. 验收

1. `tools/mvn-lock.sh -q -pl simos-app -am -DskipTests clean compile` rc=0；`package` rc=0；
2. harness（复用 P3 的 `Shell + gmToolAuthorizer` 模式）：
   - 用 raiseUnit 建一个有 5 名 MALE+ADULT 的 UNIT 家户（P3 已可 advance）；
   - 建 combat/stage，outcome 同时带 manpower 损失（如 `-2`）与 equipment 损失（如 `-1` 装备）；
   - `simos.army.resolveCombat` apply：
     - committed；批内命令含 `social.SubmitHouseholdWorkOrder`，且 `unit.AdjustComposition` 载荷不含 manpower；
     - UNIT 家户 Social 人口 −2、economy 行同步 −2、世界总人口 −2；装备 −1；
     - Unit 记录仍无 manpower/headcount 字段；
     - 次日 `advance` 0 `CLASSROW_POPULATION_PROJECTION_UNRESOLVED`、0 UNIT_HOUSEHOLD 异常；
     - 同 orderId/阶段重复判定 ⇒ 拒绝；
     - 人员不足（loss > 可战 MALE+ADULT）⇒ BAD_REQUEST、零 revision、人口不变。
3. 不跑 test/test-compile/verify；不 commit/push。

## 5. 文件范围

**允许**
- `simos-app/src/main/java/io/mosire/simos/app/tools/write/ResolveCombatPlan.java`
- `simos-app/src/main/java/io/mosire/simos/app/tools/write/ResolveCombatTool.java`
- `simos-app/src/main/java/io/mosire/simos/app/tools/write/HouseholdManpowerAllocator.java`（只加集合入口）
- 必要的命令常量/视图/javadoc 同步

**禁止**
- 改 `simos-army` / `simos-sd` / `simos-unit` 的持久状态形状与 handler 语义；
- 改 Social/Unit/Economy 持久组件、Codec、ChangeSet；
- 改测试；commit/push。

## 6. 已知边界

- 伤亡事件在 Social 侧落 `REMOVE_MEMBERS` 的 `GM_ADJUST` 事件（带 reason/source=army）；
  “战斗死亡”专用事件类型是否新增，留后续统一裁定；
- 正增量（增援/补员）、战俘、伤员康复、民夫/随军人员不在本批；
- Unit 决策人/军俸（P4）不在本批。

---

## 7. 实施状态（2026-10-13）

✅ 已实现并验收：

- `HouseholdManpowerAllocator` 增加指定家户集合入口（复用同一瀑布/守恒）；UNIT 家户 hex 可空；
- `ResolveCombatPlan`：manpower 只收负增量，`MALE+ADULT` 从 `unit.households()` 抽取，不足整条拒；
  逐 share 生成 `social.SubmitHouseholdWorkOrder`（`REMOVE_MEMBERS`，`orderId=combat-casualty:<combatId>:<stageId>:<unitId>`）；
  `unit.AdjustComposition` 只带 equipment；批序 social→adjust→resolve→clear-link；
- `ResolveCombatTool` 批组装/视图/description 同步；`SimosToolSource` 改生产构造器；
- 主控独立 smoke（`/tmp/P2Smoke.java`，71 断言）：win 场景 5→3、世界 4000→3998、weapon 3→2、
  Unit 无 manpower、次日 advance 人口 3/3、0 CLASSROW/UNIT 异常；人员不足 BAD_REQUEST 零 revision；
  纯装备损失走旧行为；compile/package rc=0，前端 412/412；
- 未跑 Java test/verify/spotless；`ResolveCombatToolTest` 旧断言需测试迁移。
