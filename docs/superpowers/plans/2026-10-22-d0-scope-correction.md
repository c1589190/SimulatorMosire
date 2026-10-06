# D0 施工记录：GovScope 撤销下辖 GOV/后代自动可见（中央只直辖区）

> 批次：D0（规划入口 `docs/superpowers/plans/2026-10-22-decision-packet-household-query-llm-mcp-plan.md`）
> 基线：`HEAD 4fb5b301`
> 用户裁定（2026-10-21）：中央决策人也只能看自己**直辖区**内的 hex 相关数据；辖区外信息必须走**上报**；
> GM 与决策人共用工具，GM 全图（`scope=ALL`）。

## 1. 改动

- `simos-app/.../access/GovScope.java`
  - `unit` 面：自己 + 位置落在直辖区 hex 内的单位；
  - **删除**沿 `GovernmentFormation.superiorGov` 扫描下辖 GOV 的逻辑（原 `subordinateGovUnits`）；
  - **删除**对 `ScopeUnitExpansion.descendants(...)` 的调用（Gov 不再自动扩辖区外后代）；
  - 类注/边界一/边界二 javadoc 改为“2026-10-21 口径”；actor 仍只保留自己 + `superiorGov` 国库路径。
- `simos-app/.../access/ScopeUnitExpansion.java`
  - javadoc 注明仅供 `ArmyScope`/`NationScope`；Gov 不调用。
- 文档同步：
  - `docs/superpowers/plans/2026-10-20-cross-scope-command-targets.md` 加“2026-10-21 修订”横幅、
    更新 Gov 范围/验收/情报口径；
  - `docs/superpowers/specs/2026-10-21-decision-packet-and-household-query-usability-design.md`
    更新易用性原则、`simos.social.households` unit 可见性、示例；
  - `docs/superpowers/plans/2026-10-22-decision-packet-household-query-llm-mcp-plan.md` 标记 D0 完成；
  - 本施工记录。

## 2. 门禁证据

- `tools/mvn-lock.sh -q -pl simos-app -am -DskipTests clean compile` → rc=0。
- `/tmp/PermSmoke.java` 更新为 2026-10-21 口径后运行：
  - 自己 `unit:gov-central` 允许；直辖区内 `unit:in-region` 允许；
  - 无有效位置的下属 `unit:central-child` 拒绝；
  - 辖区外下辖 GOV `unit:gov-prov` 及其下属 `unit:prov-child` 拒绝；
  - 下辖 GOV 所在 hex 的 `social:2_2`/`map:Map1/hex/2_2` 拒绝；
  - `[PERM-SMOKE-OK]` 全部断言通过。
- 证据日志：`/tmp/d0-perm-smoke.log`。

## 3. 边界与后续

- 决策人不再能直接读辖区外原始数据；D4 提供 `simos.sd.report`/`simos.sd.reports`
  上报/读报工具，作为跨区情报唯一入口。
- `CommandTargets` 跨命名空间目标解析不受影响（命令目标仍按家户 HEX/UNIT 判权限，
  是否在范围内由 GovScope 决定）。
