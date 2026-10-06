# 2026-10-11 Unit 对接 P1：征兵/退伍/levy 统一走 Social 家户工单

> 依据：`docs/superpowers/plans/2026-10-09-social-vital-rates-per-tick-plan.md` §7.3 P1。
> 基线：`HEAD d4af2d2b`（P0 迁移旁路已关闭；Social 是唯一人口权威）。
> 调查依据：只读报告（本文件 §1 摘要），未改文件、未跑 test/verify。

## 1. 现状（调查结论摘要）

- `GovRecruitPlan/Tool`：批 = `social.SeedGroups` + `unit.RecruitStaff` + `sd.PutInfo`；从辖区批次直接减 count，
  人没有进入任何 Social 家户 ⇒ **删人**。
- `GovRetireStaffPlan/Tool`：批 = `unit.DismissStaff` + `[actor.AdjustAccounts]` + `[social.SeedGroups]` +
  `sd.PutInfo`；回写只是把目标 hex 最小批次人数加 count ⇒ **凭空加人**。
- `LevyRegionPlan/Tool`：批 = `actor.AdjustAccounts` + `[social.SeedGroups]` + `sd.PutInfo`；manpower>0
  没有目标家户 ⇒ 静态路径会直接减人（描述里写的 `unit.AdjustComposition` 拒因与代码不符）。
- `RaiseUnitPlan/Tool`：已是 `social.CreateHousehold` + N×`TransferHouseholdMembers` + `unit.CreateUnit` +
  `[actor.AdjustAccounts]` + `sd.PutInfo`，人口守恒、Unit.households 同步；只是逐条命令、来源用整批 count +
  `householdOfLot`，拆分批次会拒。
- `SpawnArmy`、`GovSelectExaminees`、`GovDispatchTeam`：仍发 `unit.CreateUnit(manpower=[...])` ⇒ 被
  `UnitPayloads.rejectRetiredManpower` 拒（整批原子回滚）；当前实际 apply 失败。
- `GovAbsorbUnit`：plan 级已 fail-closed（manpower 退役）。
- 政府家户约定：`GovernmentHouseholds.of(unitId)` = `hh-gov-<unitId>`；`unit.SetGovFormation` 自动把它加入
  `Unit.households`；GOV 单位必须恰有一个政府家户（`UnitState` 强判）。
- `staff` 兼容字段：`governmentPostsOfHousehold` 非空时语义上只是投影读数；目前只有
  `HouseholdUnitConsistency.staffHouseholdProjection` 只读告警，`GovDaily` 仍读 `staff`，`GovApplyStaffing`
  仍直接写 `staff`。
- 同一批次可被多家户按份额持有（P0/P2-A 份额制）；`SocialData.householdOfLot`/`hexOfLot` 只报第一家户，
  `groupsAt` 后写覆盖，不能再用来选人。

## 2. P1 采用口径（本批决定，可回退）

### 2.1 路径 R：staff 保留兼容角色分解，人口走真实家户

- 本批**不**把普通 staff 全面改成 `GovernmentPostOfHousehold` 严格投影（路径 P 需要 role 家户 id、
  economy 行登记、GovDaily 读法三处新裁定）。
- 征兵：从辖区家户成员份额里选人 → Social 工单 `TRANSFER_MEMBERS` 到 `hh-gov-<unitId>`；同批
  `unit.RecruitStaff` 更新角色 `staff` 兼容计数；`staff` 与政府家户人口只做**变化量一致性**审计
  （`Δstaff == 迁入人数`），不强制 `Σstaff == 全户人口`（政府家户可能含非在编家属）。
- 退伍：从 `hh-gov-<unitId>` 选人 → Social 工单 `TRANSFER_MEMBERS` 到明确目标家户；同批 `unit.DismissStaff` +
  待遇支付；不再用 `SeedGroups` 加人。
- 目标家户约定：
  - 征兵目标 = 既有 `GovernmentHouseholds.of(unitId)`（必须已在 Social；不在 ⇒ plan 级具名拒，不猜/不新建第二户）；
  - 退伍目标 = 新增可选 `toHouseholdId`；未给时从 `reinsertQ/reinsertR` 指定 hex 上按“家户 id 升序、
    有人口、且含 MALE+ADULT 成员”选第一个；找不到 ⇒ plan 级具名拒（不再“并入最小批次”）。
- 该选择保住 `GovDaily`/`GovApplyStaffing` 现读法，是 P1 最小正确路径；路径 P（严格投影）另开批次，
  触发条件与影响面记录在 §5。

### 2.2 share-aware 选人层（P1.0）

新增 `simos-app/src/main/java/io/mosire/simos/app/tools/write/HouseholdManpowerAllocator.java`（暂名）：

```text
输入：SocialData、Region/hex 集合、人数、clock、性别/年龄档过滤、排除家户集合
候选：逐家户（location 是 HEX 且在集合内、不在排除集合）逐成员批次 (lotId, share)
      过滤 PopulationGroup.sex / AgeBracket（现算）
排序：辖区顺序 → 家户 id 升序 → lotId 升序
瀑布：逐候选 take = min(share, remaining)，产出 (householdId, lotId, take, atHex)
输出：List<ManpowerShare>，Σ take == 请求人数（不足 ⇒ 整条具名拒，不部分）
```

- 家户内若需要按份额切分，用 `ProportionalSplit.byDenominator`（同一工具，P0 已验证）。
- 旧 `RegionAllocations.allocateManpower` 保留给未迁移工具；P1 新路径不再用 `householdOfLot`/整批 count。

## 3. 批次划分与验收

### P1.0 share-aware 选人层 + 安全闸

- 新增 `HouseholdManpowerAllocator`（纯函数）。
- `LevyRegionPlan`：`manpower > 0` ⇒ plan 级具名拒（“未接线目标家户；先关掉删人路径”）；粮/钱/布照常。
- `GovSelectExamineesPlan`、`GovDispatchTeamPlan`、`SpawnArmyPlan`：plan 级具名拒 manpower>0（避免
  preview 展示一个必然被 unit 命令拒的批）；`GovAbsorbUnit` 保持。
- 修正陈旧文案：`GovRecruitTool` 的 stress、`LevyRegionTool` 的 AdjustComposition、`SocialHouseholdMembersTool`
  的派生 id、`RaiseUnitTool` 的 CreateUnit manpower 描述。
- 验收：compile rc=0；静态核对新选人层无 `householdOfLot`；smoke 确认 Levy manpower>0 返回 BAD_REQUEST、
  revision 不变、总人口不变。

### P1.1 GovRecruit 走 Social 工单

- plan：用 `HouseholdManpowerAllocator` 选 `(household, lot, take)`；解析 `hh-gov-<unitId>`（不存在 ⇒ 拒）；
  staffCap 校验照旧；Plan 带来源 shares。
- apply 批序：
  1. `social.SubmitHouseholdWorkOrder`（`orderId=gov-recruit:<batchId>`，plan = 逐来源 `TRANSFER_MEMBERS`
     到 `hh-gov-<unitId>`；target = 政府家户）；
  2. `unit.RecruitStaff`（sources = 逐来源 `social_group`? 改为 `household`+`lot` 形状；若 handler 只认旧形状，
     先只发 count + 兼容 sources，或同步扩展 handler；实现时以 handler 实际形状为准）；
  3. `sd.PutInfo`。
- 若政府家户不在 `Unit.households`（数据异常）⇒ plan 级拒，要求先 `unit.SetGovFormation`/修数。
- 验收：batch 内无 `social.SeedGroups`；来源家户份额 −N、政府家户 +N、世界总人口不变；staff 变化 = N；
  次日 advance 无 UNIT_HOUSEHOLD/CLASSROW mismatch。

### P1.2 GovRetireStaff 走 Social 工单

- plan：`toHouseholdId` 可选；未给时按 §2.1 从 reinsert hex 解析；必须能从政府家户选出 count（share-aware）；
  待遇/离编校验照旧。
- apply 批序：
  1. `social.SubmitHouseholdWorkOrder`（TRANSFER_MEMBERS 从 `hh-gov-<unitId>` 到目标家户）；
  2. `unit.DismissStaff`；
  3. `[actor.AdjustAccounts]`；
  4. `sd.PutInfo`。
- 验收：政府家户 −M、目标家户 +M、staff −M、世界总人口不变；支付金额与旧口径逐值相同；
  batch 内无 `social.SeedGroups`。

### P1.3 RaiseUnit 统一 work order

- `RaiseUnitPlan` 的 `social.CreateHousehold` + N×`social.TransferHouseholdMembers` 收成一张
  `social.SubmitHouseholdWorkOrder`（`CREATE_HOUSEHOLD` + N×`TRANSFER_MEMBERS`）；
- 来源选择切到 `HouseholdManpowerAllocator`（拆分批次可用）；
- `unit.CreateUnit(households=[hh-unit:<newUnitId>])`、账户、sd.PutInfo 不变。
- 验收：拆分批次场景可成功；unit population = N；源家户份额 −N；总人口不变；次日 advance 无 mismatch。

### P1.4（P2 前）staff 审计与决策白名单

- 推进入口对每个 GOV：记录 `staff` 与 `hh-gov-<unitId>` 人口的差值基线；征兵/退伍批内做 Δ 对齐审计；
- `GovApplyStaffingTool` 在 posts 非空时拒（或改投影）——本批先加具名拒，路径 P 再改；
- 评估把 `social.SeedGroups`、直接人口命令标 `GmOnlyCommand` / 从 `DirectiveWhitelist` 剔除，只留
  `social.SubmitHouseholdWorkOrder` 作为决策侧人口入口（需用户裁定，未裁前只记录）。

## 4. 文件范围

**允许**
- `simos-app/src/main/java/io/mosire/simos/app/tools/write/` 下：
  `HouseholdManpowerAllocator.java`（新）、`GovRecruitPlan.java`、`GovRecruitTool.java`、
  `GovRetireStaffPlan.java`、`GovRetireStaffTool.java`、`RaiseUnitPlan.java`、`RaiseUnitTool.java`、
  `LevyRegionPlan.java`、`LevyRegionTool.java`、`GovSelectExamineesPlan.java`、`GovSelectExamineesTool.java`、
  `GovDispatchTeamPlan.java`、`GovDispatchTeamTool.java`、`SpawnArmyPlan.java`、`SpawnArmyTool.java`
  （后三/四者本批只加 plan 级拒与文案）；
- 若 `unit.RecruitStaff` / `DismissStaff` 的 payload 需要容纳 `household+lot` 来源：可改
  `simos-unit/.../RecruitStaffHandler.java` / `DismissStaffHandler.java` / `UnitOperations` 的入参形状，
  但**不碰** Unit 状态组件字段；
- `simos-social` 的 `SubmitHouseholdWorkOrder` 已够用，本批不改状态类型；若载荷缺字段只补解析（另开窄口）。

**禁止**
- 改 `Unit.households` / `GovernmentFormation` / `ArmyFormation` 的持久组件形状；
- 改 `EconomyData` / SocialData 持久组件；
- 把 `GovernmentPostOfHousehold` 全面铺到普通 staff（路径 P 未裁定）；
- 测试迁移、commit/push（由控制方最后做）。

## 5. 待裁定 / 本批暂不做

1. **staff 严格投影（路径 P）**：role 家户 id 命名、每户是否只算一个 role、新家户 economy 行登记、
   `GovDaily` 改读投影。P1 先路径 R。
2. **LevyRegion manpower 完整语义**：转入 levying unit 家户、GOV 家户、还是人口 sink；本批只关闭删人路径。
3. **SpawnArmy GM 凭空建军**：允许 `ADD_MEMBERS` 世界编辑造人，还是必须从来源家户转移；本批保持 plan 级拒。
4. **决策令白名单**：直接人口命令是否降为 GmOnly；本批只记录。
5. **经济/UNIT 家户投影**：新建 UNIT 家户缺 economy class row 时 `HouseholdEconomyProjection` 会 unresolved；
   P1 只使用既有 `hh-gov-<unitId>`（已有 economy 行）作为征兵目标，规避该缺口；P3 再补 UNIT smoke。
