# 2026-10-16 Unit 对接 P1.5：GovSelectExaminees / GovDispatchTeam 接线

> 依据：`docs/superpowers/plans/2026-10-11-unit-integration-p1.md` §3 P1.5；
> P1.0 已把二者 plan 级 fail-closed；本批按 raiseUnit/P1.2/P3 同形接成完整 Social 工单路径。
> 基线：`HEAD 6e0c1320`。

## 1. 目标

- `GovSelectExaminees`：从来源 GOV 辖区的 Social 家户份额里选人 → 新建 `hh-unit:<newUnitId>` 家户
  → `unit.CreateUnit(households=[该家户])` → `economy.RegisterHousehold` + `actor.EnsureHouseholdAccount`
  → 可选 `unit.PlanRoute` → `sd.PutInfo`；不再发 `social.SeedGroups` / `unit.CreateUnit(manpower)`。
- `GovDispatchTeam`：从来源 GOV 的政府家户 `hh-gov-<unitId>` 的成员份额里选 `count` 人 →
  转入新建 `hh-unit:<newUnitId>` 家户 → `unit.CreateUnit(households=[该家户])` +
  `[unit.SetArmyFormation(armed)]` + `economy.RegisterHousehold` + `actor.EnsureHouseholdAccount`
  → `unit.DismissStaff`（兼容 staff 计数）→ `sd.PutInfo`；不再发 `social.SeedGroups` / `manpower`。
- 两个工具的来源选择均复用 `HouseholdManpowerAllocator`（share-aware，不做整批 count/跨户平均）。
- P4b 的军俸政策不自动绑定到新单位；由后续 GM/决策人命令显式设置。

## 2. 选择口径

- `GovSelectExaminees`：
  - 来源 = `sourceGov.jurisdiction` 的 Region hex 集合，`MALE + ADULT`（与科举/选送历史口径一致）；
  - 不足 ⇒ 整条具名拒（不部分、不截断）；
  - 新单位落点 = 来源 GOV effectivePosition；目的 GOV route 规划照旧（`unit.PlanRoute`）。
- `GovDispatchTeam`：
  - 来源 = `hh-gov-<unitId>` 家户成员，`MALE + ADULT`（编制人员；现有人口必须 ≥ count）；
  - `staff[role] ≥ count` 校验照旧；
  - 新单位落点 = 来源 GOV effectivePosition；armed 时同批 `unit.SetArmyFormation(masterGov=来源 GOV)`。
- 两者产出逐 lot `(householdId, lotId, taken)`；守恒 `Σtaken == count`。

## 3. 批序

**GovSelectExaminees**
```text
1. social.SubmitHouseholdWorkOrder
   orderId=gov-select-examinees:<batchId>:<newUnitId>
   target=hh-unit:<newUnitId>
   plan = CREATE_HOUSEHOLD(HH, UNIT(newUnitId), profile=name+"·人口家户", vitalRates=[])
        + 逐 share TRANSFER_MEMBERS(from=source households, to=HH, lotId, count)
2. unit.CreateUnit（id=newUnitId, name, position=来源 GOV effectivePosition,
   households=[HH], equipment=[], speed=4, mobilityPerMille=800；无 manpower）
3. [unit.PlanRoute]（targetGov 给了且不同格时照旧）
4. economy.RegisterHousehold（HH, q/r=position, residence 按是否城市格, stratum=landless_laborer）
5. actor.EnsureHouseholdAccount（HH）
6. sd.PutInfo（key=selectExaminees，value 含新单位/人数/来源 shares/route）
```

**GovDispatchTeam**
```text
1. social.SubmitHouseholdWorkOrder
   orderId=gov-dispatch-team:<batchId>:<newUnitId>
   target=hh-unit:<newUnitId>
   plan = CREATE_HOUSEHOLD(HH, UNIT(newUnitId), profile=name+"·人口家户", vitalRates=[])
        + 逐 share TRANSFER_MEMBERS(from=hh-gov:<unitId>, to=HH, lotId, count)
2. unit.CreateUnit（households=[HH]、无 manpower；armed 时随后 SetArmyFormation）
3. [unit.SetArmyFormation（armed 时；masterGov=来源 GOV，role=角色名）]
4. economy.RegisterHousehold（HH,…）
5. actor.EnsureHouseholdAccount（HH）
6. unit.DismissStaff（unitId, role, count；兼容 staff 计数）
7. sd.PutInfo（key=dispatchTeam）
```

- 所有信封共享同一 batchId/branch/expectedRevision；任一腿失败整批拒、零 revision。
- `economy.RegisterHousehold` / `actor.EnsureHouseholdAccount` 是 GM-only；两工具均走 GM 授权（现有工具语义）。

## 4. 验收

1. `tools/mvn-lock.sh -q -pl simos-app -am -DskipTests clean compile` rc=0；`package` rc=0；
2. fresh harness（复用 P3/P4 的 `Shell + gmToolAuthorizer/command.submit` 模式）：
   - 建 GOV（`simos.gov.createOffice`），`GovRecruit` 若干人进 `hh-gov-<unitId>`；
   - `simos.gov.selectExaminees`：apply committed；批内无 SeedGroups/manpower；
     `hh-unit:<newUnitId>` 家户/经济行/账户/Unit.households 齐；来源家户合计 −count；
     目的 GOV route 命令照旧；次日 advance 无 CLASSROW/UNIT 异常；
   - `simos.gov.dispatchTeam`（armored 与 unarmed 两例）：apply committed；批内无 SeedGroups/manpower；
     来源政府家户 −count、新单位家户 +count、staff −count；新单位 households/经济行/账户齐；
     次日 advance 无异常；
   - 不足/非法载荷（来源人口不足 / 新 id 冲突 / staff 不足）⇒ BAD_REQUEST、零 revision；
   - 同 store 重启：新家户/账户/unit/households 一致。
3. 不跑 test/test-compile/verify；不 commit/push（控制方最后做）。

## 5. 文件范围

**允许**
- `simos-app/src/main/java/io/mosire/simos/app/tools/write/GovSelectExamineesPlan.java`、`GovSelectExamineesTool.java`
- `simos-app/src/main/java/io/mosire/simos/app/tools/write/GovDispatchTeamPlan.java`、`GovDispatchTeamTool.java`
- `simos-app/src/main/java/io/mosire/simos/app/tools/write/HouseholdManpowerAllocator.java`（只加必要入口）
- 必要的命令常量/视图/javadoc 同步

**禁止**
- 改 Social/Unit/Economy 持久组件与 handler 语义；
- 改测试；commit/push。

## 6. 边界 / 后续

- `LevyRegion` manpower 仍保持 P1.0 plan 级 fail-closed（目标家户/用途未裁定）；
- `SpawnArmy` 仍保持 GM 凭空建军 policy 未裁定；
- P4c 工具端、测试迁移、clean verify 仍后置。

---

## 7. 实施状态（2026-10-16）

✅ 已实现并验收：

- `GovSelectExaminees`：来源 = jurisdiction Region 家户 `MALE+ADULT` share-aware 瀑布；
  work order（CREATE_HOUSEHOLD `hh-unit:<newId>` + TRANSFER_MEMBERS）→ CreateUnit(households, 无 manpower)
  → [PlanRoute] → RegisterHousehold → EnsureHouseholdAccount → PutInfo；
- `GovDispatchTeam`：来源 = `hh-gov-<unitId>` 成员份额；work order → CreateUnit →
  [SetArmyFormation] → RegisterHousehold → EnsureHouseholdAccount → DismissStaff → PutInfo；
  未 armed 时不发 SetArmyFormation；
- 两工具资源声明补 social/economy/actor；不再 plan 级 fail-closed，也不再发 SeedGroups/manpower；
- 主控独立 smoke（`/tmp/P15Smoke.java`，107 断言全 PASS）：
  - select 4 人：`hh-unit:u-p15-exam` 人口 4、经济行/账户/unit.households 齐、来源户 −4、世界人口不变；
  - select+route：批含 PlanRoute，waypoints 正确；
  - dispatch armed/unarmed：gov 家户 14→12→10、staff 同步减；新单位家户 +2/+2、经济行/账户齐；
    armed 有 ArmyFormation、unarmed 无 module；
  - 负例全部 BAD_REQUEST 零 revision；次日 advance 经济行人口逐户相等、0 CLASSROW/UNIT 异常；
  - 同 store 重启：四个新家户/经济行/账户/Unit.households 完全一致；
- compile/package rc=0；未跑 Java test/verify/spotless。

边界：`LevyRegion` manpower 仍 fail-closed；`SpawnArmy` GM 凭空建军 policy 未裁定；P4c 工具端后置。
