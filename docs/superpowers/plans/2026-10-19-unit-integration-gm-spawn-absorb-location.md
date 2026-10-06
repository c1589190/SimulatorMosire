# 2026-10-19 Unit 对接：GM 任意改（SpawnArmy/AbsorbUnit）+ 单位家户位置关联硬化

> 用户 2026-10-19 裁定：
> 1. 「GM 想改什么就应该能改什么」⇒ SpawnArmy 允许 GM 直接建军/造人，但造人必须走 Social 权威；
> 2. GovAbsorbUnit 要把源单位的人真正吸收进 GOV 家户，而不是第二本 headcount；
> 3. 单位对应家户的位置关联应直接挂在单位自身（`HouseholdLocation.UNIT(unitId)`），不要钉 HEX。
> 基线：`HEAD 154993ef`。

## 1. 单位家户位置模型（回答“就不能直接挂到单位自身吗”）

- **已经就是这个模型**：`HouseholdLocation` 是 sealed，只有 `HEX(hex)` 与 `UNIT(unitId)` 两种；
  `hh-unit:<id>` / `hh-gov-<id>` 创建时就是 `UNIT(unitId)`。
- `Unit.households` 是“谁在这个单位里”的唯一列表；`HouseholdPositionResolver` 把
  `UNIT(unitId)` 解析为 `UnitState.effectivePosition(unitId, at)`——**单位动，家户有效格跟着动**，
  Social 的 `Household.location` 一字不改。
- 经济视图（`HouseholdEconomy.view.hex`）由 `alignHouseholdEconomyViews` 每日对齐；
  账户键早就不带格（`HouseholdAccountKey` 只按 HouseholdId）。
- **不支持的路径**：对已在某 `Unit.households` 里的家户直接 `social.SetHouseholdLocation(HEX)`——
  这会制造“列表说在单位、位置说在 hex”的中间态；下轮 reconciliation 会把它修回 `UNIT(unitId)`。
  本批加 app 工具级守卫：`SocialHouseholdMoveTool` 对“单位成员要改 HEX”具名拒，要求先
  `unit.detachHousehold`（合法脱离路径，会同时改 HEX + 从列表移除）。
- 单位移动只走 `unit.PlaceAt`/行军/迁都；不写 Social。

## 2. SpawnArmy（GM 直接建军，造人走 Social）

批序：

```text
1. social.SubmitHouseholdWorkOrder
   orderId=spawn-army:<batchId>:<unitId>
   target=hh-unit:<unitId>
   plan = CREATE_HOUSEHOLD(hh:UNIT(unitId), profile=name+"·人口家户", vitalRates=[])
        + ADD_MEMBERS(hh, lotId=spawn-army:<unitId>:<tick>, sex=MALE,
                      count=member, ageAtAnchorDays=20*365, anchorTick=tick)
2. unit.CreateUnit（id/name/position/equipment/speed/mobility/status/parent?；households=[hh]，无 manpower）
3. [unit.SetArmyFormation]（role 给了才发）
4. sd.CreateArmy（不变）
5. economy.RegisterHousehold（hh, q/r=at, residence 按是否城市格, stratum=landless_laborer）
6. actor.EnsureHouseholdAccount（hh）
7. sd.PutInfo（value 增 household/population）
```

- `member` 是 GM 造人数量；`ADD_MEMBERS` 是 Social 唯一人口加口，GM 授权下允许凭空加。
- `ageAtAnchorDays=20*365`、`MALE`：GM 建军默认成年男丁；后续可加 params 细分（本批不扩）。
- 资源声明补 social/economy/actor；不再发 `unit.CreateUnit(manpower=...)`。

## 3. GovAbsorbUnit（吸收真实人口）

- 前置：吸收方 GOV 必须有 `hh-gov-<govUnitId>`；源单位必须存在、`module` 为空（纯人员单位）、
  `Unit.households()` 非空；`count ≥ 1`；staffCap 校验照旧。
- 来源：源单位 `Unit.households()` 的家户成员份额，用
  `HouseholdManpowerAllocator.allocateFromHouseholds(..., MALE, ADULT, Set.of())` 抽 `count`；
  不足整条拒。
- 批序：

```text
1. social.SubmitHouseholdWorkOrder
   orderId=gov-absorb-unit:<batchId>:<govUnitId>:<sourceUnitId>
   target=hh-gov-<govUnitId>
   plan = 逐 share TRANSFER_MEMBERS(from=源家户, to=GOV 家户, lotId, count)
        +〔disbandSource 且迁移后源家户人口全为 0〕逐源家户 SET_LOCATION(HEX=GOV 单位 effectivePosition)
2. unit.RecruitStaff（role += count）
3. [unit.DisbandUnit(sourceUnitId)]（仅 disbandSource 且迁移后源家户人口全为 0；
   且 SET_LOCATION 已先把孤儿 UNIT 位置摘掉）
4. sd.PutInfo
```

- `SET_LOCATION` 必须在 `DisbandUnit` 之前：否则源家户的 `UNIT(sourceUnitId)` 会变成孤儿位置，
  下轮 `HouseholdUnitConsistency` 会 unresolved。
- 迁移后源仍有剩余人口 ⇒ **不**解散源单位，`disbandSkippedReason` 具名；
- 源单位 `module` 非空（Army/GOV）照旧拒；
- `count` 必须能抽到 MALE+ADULT；不足 ⇒ plan 级拒。
- 资源声明补 social/economy（RegisterHousehold/EnsureAccount 不在此工具：源/目标家户都已登记）。

## 4. 位置关联硬化

- `SocialHouseholdMoveTool`：若目标家户当前在任一 `Unit.households()` 里，目标是 HEX（或别的 UNIT）
  ⇒ 具名拒；只有 `UNIT(同一个 unitId)` 或先 `unit.detachHousehold` 才允许。
- `UnitDetachHouseholdTool` 保持合法：先 `social.SetHouseholdLocation(HEX)`、再 `unit.SetUnitHouseholds(去掉)`。
- `UnitAssignHouseholdTool` 保持：`SET_LOCATION(UNIT(target))` + `SetUnitHouseholds`。
- 不加 Social 模块对 Unit 的依赖；守卫在 app 组合根。

## 5. 验收

1. `tools/mvn-lock.sh -q -pl simos-app -am -DskipTests clean compile` rc=0；`package` rc=0；
2. smoke：
   - `simos.unit.spawnArmy`：apply committed；批内 Social work order（CREATE+ADD_MEMBERS）→ CreateUnit(households)
     → [SetArmyFormation] → sd.CreateArmy → RegisterHousehold → EnsureAccount → PutInfo；新家户 1 批 `member` 人、
     Location=UNIT、经济行/账户/unit.households 齐；次日 advance 0 CLASSROW/UNIT 异常；
   - `simos.gov.absorbUnit`：建 GOV+源纯人员单位（raiseUnit/select 各一），吸收 count：
     committed；源家户 −count、GOV 家户 +count、staff +count、世界人口不变；
     `disbandSource=true` 且源清空 ⇒ 源家户位置改为 HEX、源单位被解散、advance 无 orphan；
     源未清空 ⇒ 不解散并具名；
   - 位置守卫负例：对在编家户 `social.household.move` 到 HEX ⇒ BAD_REQUEST；detach 工具仍可成功。
3. 不跑 test/test-compile/verify；不 commit/push（控制方最后做）。

## 6. 文件范围

**允许**
- `simos-app/.../tools/write/SpawnArmyPlan.java`、`SpawnArmyTool.java`
- `simos-app/.../tools/write/GovAbsorbUnitPlan.java`、`GovAbsorbUnitTool.java`
- `simos-app/.../tools/write/SocialHouseholdMoveTool.java`
- 必要的常量/视图/javadoc 同步

**禁止**
- 改 Social/Unit/Economy 持久组件与 handler；
- 改测试；commit/push。

## 7. 决策人预执行体系（本轮先解释，不在这个功能批里改）

- 现状代码骨架：决策人只能 `sd.IssueDirective`（命令 `{type,payloadJson}`）+ `sd.SubmitVerdict`；
  GM 用 `sd.AdjudicateTick` 一次性裁决/执行，`CommandTargets` 判目标、按决策人 scope 授权。
- 确认过的提案设计（`docs/superpowers/specs/2026-09-30-decision-packet-proposal-design.md`）要求
  格式化调用/决策包/GM true-false 审批；目前 **DecisionPacket/FormattedCall 尚未实现**。
- 新 unit/social 工具要进决策人预执行体系，需要把"提议的动作"变成可审批的格式化调用；
  本批不实现该体系，先把 GM 侧功能补全（用户本次要求）。

---

## 8. 实施状态（2026-10-19/20）

✅ 已实现并验收：

- **SpawnArmy**（提交 `40baccc2`）：去掉 fail-closed；`social.SubmitHouseholdWorkOrder`
  `CREATE_HOUSEHOLD(hh-unit:<id>, UNIT)` + `ADD_MEMBERS`（MALE/20 岁/`member` 名）→
  `unit.CreateUnit(households=[hh], 无 manpower)` → `[SetArmyFormation]` → `sd.CreateArmy` →
  `economy.RegisterHousehold` → `actor.EnsureHouseholdAccount` → `sd.PutInfo`；
  主控独立 smoke 118 PASS：两个 spawn 用例、次日 advance、重启、位置守卫正负例全过。
- **位置守卫**：`SocialHouseholdMoveTool` 对在编家户改 HEX/别的 UNIT 具名拒，指路
  `unit.detachHousehold`；detach 后 HEX 移动照常。
- **GovAbsorbUnit**（本提交）：去掉 fail-closed；源纯人员单位 → 逐 share
  `TRANSFER_MEMBERS` 到 `hh-gov-<govUnitId>`；`disbandSource=true` 且源家户全空时
  逐户 `SET_LOCATION` 到 HEX 后再 `unit.DisbandUnit`（防孤儿 UNIT 位置）；源未清空则具名
  `disbandSkippedReason` 不解散；staffCap/源带 module/自吸收/人口不足均 plan 级拒。
  主控独立 smoke 119 PASS：源 5→2 + GOV +3 + staff +3；再吸收 2 并解散源（源位置 HEX、
  单位消失）；次日 advance 无 CLASSROW/UNIT/time-budget 异常；重启一致。
- `compile` / `package` rc=0；未跑 Java test/verify/spotless。
