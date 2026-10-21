# RC4-C 任务书：economy 结构引用重绑（资产/企业/关系/名册）

> **状态：约束设计书/实现任务书；只写生产代码到编译过，不写/不跑测试。**
> 工作树：`/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`。
> 父设计：v4 R1/R5；v2 S2。
> 依赖：RC4-A merge plan、RC4-B production 周期贡献重键均已 compile 绿。
> 本单只做 economy 结构引用纯重绑，不做 LAND/钱货债/人口/经验，不做 S2 接线。

## 1. 目标

实现 `HouseholdMergeRebindBook`（economy/time）的一个可编译、确定性的结构重绑入口：

```text
source household -> survivor household
```

覆盖：

- 非 LAND `OwnershipStake` 的 owner/operator 与派生 `AssetShareId`；
- `ProductionEnterprise` organizer / laborSources / assetSources / inputSources / outputOwnership；
- `ProductionRules` operator / inputSupplier / residualOwner / rules[].recipient；
- `ProductionRoster` participant household / organizer / rightsStakes；
- 所有引用新 assetShareId 的现有引用（enterprise.assetSources、roster.rightsStakes）；
- 不改变 unit id、enterprise id、relation key、roster key。

本单不搬 LAND、不动账户/货币/债务/人口/经验，不做 S2 执行。

## 2. 当前事实（file:line）

- `OwnershipStake` 非 LAND 的 id 含 owner/operator/kind/location（`OwnershipStake.java:172-260`）；LAND 不进 assetShares（`EconomyDataInvariants.java:331-338`）。
- `OwnershipStakeBook.transfer(...)` 是唯一资产份额转移/新 id 写口（`OwnershipStakeBook.java:78-100`），支持整条/部分量、目标 tuple 合并、返回新 id。
- `ProductionEnterprise` 字段：organizer/laborSources/assetSources/inputSources/outputOwnership（`ProductionEnterprise.java:73-85`）。
- `ProductionRules` 字段：operator/inputSupplier/rules/residualOwner/laborSource（`ProductionRules.java:90-96`）；`CompensationRule.recipient` 是 `Payee`。
- `Payee` 变体：ToActor / ToHousehold / ToCohort（`Payee.java`）。
- `ProductionRosters.mergeHousehold(...)` 已处理 participant household、同 role labor/weight/rights/ organizer OR（`ProductionRosters.java:109-173`）。
- 名册 `rightsStakes` 持旧 `AssetShareId`（`ProductionParticipant`）；企业 `assetSources` 持旧 `AssetShareId`。
- `HouseholdReferenceValidation` 要求 enterprise.laborSources == roster.households()（audit 已定位）。

## 3. 冻结实现

新增：

```text
simos-economy/time/HouseholdMergeRebindBook.java
```

公开（或 package）入口：

```text
static RebindOutcome rebind(
    EconomyStateBuilder sheet,
    Map<IndustryId, IndustryDefinitionView> industries,
    HouseholdId source,
    HouseholdId survivor)
```

`RebindOutcome` 至少含：

- `Map<AssetShareId, AssetShareId> assetIdMapping`（旧 id→新 id）；
- `int assetRowsRebound`;
- `int enterprisesRebound`;
- `int relationsRebound`;
- `int rosterParticipantsMerged`;
- `boolean changed`。

### 3.1 资产份额（非 LAND）

- 遍历 `sheet.assetShares()` 的**稳定序**（`AssetShareId.value()` 升序）；
- 若 `asset == LAND` 且 owner/operator 命中 source ⇒ **fail-closed**：
  `household-merge-land-in-assetshares`（LAND 只能走 LandBook，RC4-D）；
- 若 owner/source 或 operator/source：
  - 目标 owner = owner==source ? survivorActor : owner；
  - 目标 operator = operator==source ? survivorActor : operator；
  - 调用 `OwnershipStakeBook.transfer(shares, industries, id, quantity, targetOwner, targetOperator, kind)`；
  - 记录 old id→returned id；
  - 若源行被删除/合并，保持 map 只有合法行；
- 若 owner/operator 均不命中 source ⇒ 原样保留；
- 不改变 quantity/industry/asset/kind/location；
- 若 `industries` 缺少该 stake.industry ⇒ 具名 fail-closed；
- 所有 asset id 映射稳定、无 HashMap 序依赖。

### 3.2 ProductionEnterprise

- 遍历企业稳定序（`ProductionOrganizationId.value()` 升序），键不变；
- `organizer == HouseholdActors.of(source)` ⇒ survivor actor；
- `laborSources`：source→survivor，去重保序；必须与名册 households 同口径；
- `assetSources`：逐旧 id → `assetIdMapping`；若某个 id 不在映射中但被源份额替换，必须具名 fail；
- `inputSources`：逐 `Payee` 映射（source actor/household→survivor）；
- `outputOwnership`：同样映射；
- `unitId`/modeId/classPositionId/status/statusReason/relationTemplateRef 不变；
- 企业旧 opaque id 不重派生。

### 3.3 ProductionRules

- 遍历 `sheet.relations()` 稳定 unit 序；
- `operator == sourceActor` ⇒ survivorActor；
- `inputSupplier` / `rules[].recipient` / `residualOwner` 做同一 Payee/Actor 映射；
- `rules` 保序、字段逐项不变，仅 recipient 重指；
- `laborSource` 不变；unit key 不变。

### 3.4 ProductionRoster

- 调 `ProductionRosters.mergeHousehold(rosters, source, survivor)`；
- 再遍历每个 roster participant 的 `rightsStakes`，逐 id 映射到新 assetIdMapping（去重保序）；
- organizer 仍由 `ProductionRosters` 的 organizer OR 规则决定；
- 结果必须恰好一个 organizer；
- roster key/unit/plan 不变。

### 3.5 顺序与原子性

固定顺序：

```text
1. 资产份额转移（生成 complete assetIdMapping）
2. ProductionRules Payee/Actor 重指
3. ProductionRoster merge + rightsStakes remap
4. ProductionEnterprise organizer/laborSources/assetSources/payees remap
5. 全部写回 sheet 工作副本
```

- 先校验、后写；任何失败整次 advance 丢弃 session，不得半写。
- 不得调用 `session.build()`/`preview()`；只在工作副本上写。
- 无 source 引用时返回 `changed=false`，map 原样。
- 日志 INFO `HOUSEHOLD_STRUCTURAL_REBIND_APPLIED`（source/survivor/assetRows/enterprises/relations/rosters），逐项 DEBUG。

## 4. 必须达到的行为

1. owner=source 的非 LAND 份额整体转 survivor；operator 保持不变；
2. operator=source 的份额整体转 survivor；owner 保持不变；
3. owner/operator 都是 source 的份额转 survivor/survivor；
4. 新 asset id 由 OwnershipStakeBook 生成/合并；enterprise.assetSources 与 roster.rightsStakes 全部指向新 id，无悬空；
5. enterprise.laborSources 与 roster.households 一致（source 去重为 survivor）；
6. rules/enterprise 中 source actor/household payee 全部改为 survivor；
7. 非 source 引用逐值不变；
8. unit/enterprise/relation/roster key 不变；LAND 不进 assetShares 的新路径；
9. 无 state 组件/Codec/ChangeSet 形状变化；
10. 重复调用（source 已无引用）幂等 no-op。

## 5. 允许修改的文件

```text
simos-economy/src/main/java/io/mosire/simos/economy/time/HouseholdMergeRebindBook.java（新增）
simos-economy/src/main/java/io/mosire/simos/economy/time/ProductionRosters.java（仅当需要 rightsStakes remap 的 helper）
simos-economy/src/main/java/io/mosire/simos/economy/time/OwnershipStakeBook.java（仅当需要只读/写口适配，不改既有语义）
```

必要时只改 `ProductionEnterprise`/`ProductionRules` 的便利构造器/注释；**禁止**改持久组件形状、LAND/账户/债务/人口/经验、S2、seeder、tests、主树。

## 6. 验收锚点（测试 owner 后补）

正例：

1. 源份额 owner=source ⇒ 新 id、数量守恒、enterprise/roster 引用同步；
2. operator=source ⇒ 新 id、owner 保持；
3. both=source ⇒ survivor/survivor；
4. 同 tuple 已有 survivor 行 ⇒ OwnershipStakeBook 合并，旧引用映射到现有 id；
5. 两个 enterprise 分别 organizer source/survivor ⇒ organizer 重指后仍各自 unitId，enterprise id 不变；
6. roster participant household source→survivor，同 role 计划劳动相加、rights 并集；
7. 非 source 资产/企业/关系/名册逐值不变。

负例：

- LAND 出现在 assetShares 且命中 source ⇒ fail-closed；
- enterprise.assetSources 仍指旧 id ⇒ 红；
- roster.rightsStakes 仍指旧 id ⇒ 红；
- enterprise.laborSources 与 roster.households 不一致 ⇒ 红；
- 旧 enterprise/unit/relation id 被重派生 ⇒ 红；
- source 无引用时 changed=true/写状态 ⇒ 红。

## 7. 编译与交账

```bash
cd /home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module
tools/mvn-lock.sh -q spotless:apply
tools/mvn-lock.sh -q -DskipTests compile -pl simos-economy,simos-app -am
```

- 只写生产代码；不写/不跑 test/test-compile/verify/package；不 commit/push。
- 账本：`.superpowers/sdd/2026-10-20-d032-rc4c-household-structural-rebind/impl-ledger.md`。
- 报告列：改动文件、编译 rc、重绑顺序、asset id 映射规则、enterprise/relation/roster 的同步点、可能失效测试断言、未验证项。

## 8. 本责任区不做

- 不做 LAND/钱/货/债/人口/经验迁移；
- 不做 labor commitments/occupancy/reservation/demand/wageCarry（RC4-D）；
- 不做 S2 接线/旧 organizer guard 删除；
- 不做 S7-B/C/flow table；
- 不做 §8 整组织交易。

## 9. 用户原话附录（逐字）

来源：`docs/superpowers/specs/2026-10-20-d032-two-position-rural-class-mobility-v4.md` §1.2，2026-10-20。

```text
R1：如果一个hex上有多个同一文化属性、同一生产方式和对应阶级性的家户，只是库存和人口不同，那么这两个家户应当合并，目前的有地农民家户早已脱离地主语义了，事实上是小农和地主的集合体——地主导致的灾难性阶级分化事实上也能表示，就是单个hex的有地农民人口极少土地极多，大部分人口都只能接受雇佣
R5：是的，随着人走
```
