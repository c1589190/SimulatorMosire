# 2026-10-20 决策人跨权限家户目标：CommandTarget 契约 + 单位子树/下辖 GOV 范围

> 用户 2026-10-19/20 裁定：
> 1. 选方案 1：`CommandTargets` 升级为**跨命名空间目标**（namespace + path），不再只返回单命名空间路径；
> 2. 家户权限：若家户在可见 hex 上 ⇒ 可操作；若家户属于可见单位 ⇒ 可操作；
>    可见单位的**下属单位**（含下辖 GOV）对应家户也可操作；
> 3. 跨权限情报：中央政府**能看到下辖政府的单位编制/单位家户**，但**hex 权限不通用**——
>    不能因为能看到某下级政府单位就顺带看它的格子人口；只有单位命名空间的面扩大；
> 4. “调人”是双边操作：`from` 与 `to` 必须都在范围内（否则交 GM/合并审批）；
> 5. 按此设计保存文档并改完代码。
> 基线：`HEAD 154993ef`。

## 1. 新契约：`CommandTarget`（namespace + path）

- 新增 `simos-util/.../util/spi/CommandTarget.java`：

```text
record CommandTarget(String namespace, String path)
  - 二者非空白；namespace = 命令类型第一个 '.' 前的段（与 CommandBus 既有约定同源）
  - toString / 可读

`CommandTargets` 新增默认方法：
  default List<CommandTarget> targetResources(
      String commandType, SimulationState state, String mapId, String payloadJson)
  - 默认实现：namespace = commandType 第一段；包装既有 `targetPaths(mapId,payloadJson)` 的每条路径；
  - 旧 `targetPaths` 保留（80 个 handler 不被迫重写）；
  - 家户级 handler 覆盖本方法，返回 `social`/`unit` 混合目标。
```

- `AdjudicateTickTool` 改调 `targetResources(command.type(), state, mapId, payload)`；
  逐条判 `ResourceId.of(target.namespace(), normalizedPath)`；空列表仍 fail-closed。
- `violations(...)` 增加 `List<CommandTarget>` 重载；旧 `(namespace, List<String>)` 保留给既有测试/调用。

## 2. 家户 → 目标解析（唯一实现放 social handler 内）

规则：

```text
existingHousehold(hh):
  location = SocialData.households[hh].location
  HEX(q,r)   → CommandTarget("social", "q_r")
  UNIT(u)    → CommandTarget("unit",   u)

newHousehold(location 载荷):
  同上（CREATE_HOUSEHOLD / SET_LOCATION 的新位置）

命令引用多个家户 ⇒ 逐条返回、保序、可重复；查无家户（非创建型）⇒ 具名 IllegalArgumentException。
```

覆盖的 handler：

| handler | 目标 |
|---|---|
| `CreateHouseholdHandler` | 载荷 `location`（HEX→social / UNIT→unit） |
| `SetHouseholdLocationHandler` | **旧位置 + 新位置**两条（防止借改位置跨范围） |
| `AddHouseholdMembersHandler` / `RemoveHouseholdMembersHandler` | 家户当前位置 |
| `AdjustHouseholdPopulationHandler` | 家户当前位置 |
| `SetHouseholdVitalRatesHandler` | 家户当前位置 |
| `TransferHouseholdMembersHandler` | `from` + `to` 两条都判 |
| `SubmitHouseholdWorkOrderHandler` | 解析 plan：`CREATE_HOUSEHOLD.location`、`SET_LOCATION.location`、所有 `household`/`from`/`to` 引用的家户（去重后逐条） |
| `MovePopulationLotsHandler` | 若载荷带目标 location/家户引用，按同规则；否则保持空=拒 |

- 双边规则：`from`、`to` 任一越界 ⇒ 整条命令被 `AdjudicateTick` 具名拒；
- 局部工具（GM 组合工具，如 `simos.gov.recruit`）不受影响：它们以 GM 身份提交命令，不走 AdjudicateTick 的 scope。

## 3. 单位子树 / 下辖 GOV 的 unit 范围扩展

新增 app 帮助类 `ScopeUnitExpansion`（`simos-app/.../access/`）：

```text
descendants(UnitState units, Set<UnitId> roots, SimosTimestamp at):
  沿 Unit.parent().valueAt(at) 建 parent→children 索引；BFS/DFS 收集所有后代（visited 防环）
```

- `ArmyScope`：视野圈内单位集合 → 追加它们的全部后代 unit 路径（`ResourcePaths.unit`）；
  后代在家户目标解析里会以 `UNIT(u)` 命中 unit 命名空间 ⇒ 自动可操作。
- `NationScope`：本国区域内单位集合 → 追加全部后代 unit 路径。
- `GovScope`：
  - 已有“辖区内单位 + 自己” ⇒ 追加全部后代；
  - **新增下辖 GOV 链**：扫描 `UnitState`，若某单位的 `GovernmentFormation.superiorGov` 链（有限深度/防环）
    最终指到自己 ⇒ 把该 GOV 单位及其全部后代加入 **unit 命名空间**；
  - 只扩 `unit`，`map`/`social`/`actor` 前缀**不扩**：中央能看到下辖政府编制/单位家户，
    但不能顺带读它们的格人口/地图；actor 仍只保留自己 + superiorGov 的国库路径（原有口径）。
- 决策人读单位：`UnitGetTool`/`UnitListTool` 的目标是 unit 路径 ⇒ 下辖单位读得到；
  读某格人口仍要求该 hex 在 map/social 范围内，保持“hex 权限不通用”。

## 4. 验收

1. `tools/mvn-lock.sh -q -pl simos-app -am -DskipTests clean compile` rc=0；`package` rc=0；
2. harness（可直接调 `CommandTargets`/scope 函数，避免起长服务）：
   - 造三个家户：HEX 家户、UNIT 家户、UNIT 下属单位家户；
   - `CreateHousehold`/`SetHouseholdLocation`/`TransferHouseholdMembers`/`SubmitHouseholdWorkOrder` 的
     `targetResources(...)` 返回预期 `social`/`unit` 混合目标；`from` 与 `to` 都列出；
   - 建 GOV 决策人（`sd.CreateDecisionMaker` 或直接构造 `Affiliation.Gov` 调 `GovScope.scopesFor`）：
     - 自己单位、辖区内单位、下属单位、下辖 GOV 单位的 unit 路径 **允许**；
     - 下辖 GOV 位置所在 hex 的 social/map 路径 **仍拒绝**；
   - `AdjudicateTickTool.violations(fence, List<CommandTarget>)` 对“单位家户目标在范围内”放行、
     对“HEX 不在范围内”拒绝；
   - 负例：跨边界转移（from 在范围内、to 不在）⇒ 具名拒。
3. 不跑 test/test-compile/verify；不 commit/push（控制方最后做）。

## 5. 文件范围

**允许**
- `simos-util/src/main/java/io/mosire/simos/util/spi/CommandTarget.java`（新）
- `simos-util/src/main/java/io/mosire/simos/util/spi/CommandTargets.java`
- `simos-app/src/main/java/io/mosire/simos/app/tools/write/AdjudicateTickTool.java`
- `simos-app/src/main/java/io/mosire/simos/app/access/ScopeUnitExpansion.java`（新）
- `simos-app/src/main/java/io/mosire/simos/app/access/ArmyScope.java`
- `simos-app/src/main/java/io/mosire/simos/app/access/NationScope.java`
- `simos-app/src/main/java/io/mosire/simos/app/access/GovScope.java`
- `simos-social/src/main/java/io/mosire/simos/social/spi/` 下上述家户 handler（仅加目标解析，不改写语义）
- 必要的 javadoc/常量同步

**禁止**
- 改 `SocialData`/`UnitData`/`EconomyData` 持久组件；
- 改 Unit/Social handler 的写语义；
- 改测试；commit/push。

## 6. 情报口径（写入文档）

- **跨权限情报** = 决策人所在 scope 之外的信息。现口径：
  - 中央政府（GOV scope）能读下辖政府**单位编制/单位家户**（unit 命名空间），
    但不能读下辖政府的 hex/人口/地图（map/social 不扩）；
  - 需要别的权限域情报时，仍只能找 GM（或由 GM 明确授权/合并审批）；
  - 本批不改读工具的可见性；只让“命令目标的家户”按 unit/hex 规则进入授权判定。
