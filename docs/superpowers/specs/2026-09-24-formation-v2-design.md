# 编制 v2：取消跟随 · 同格成编 · 顶层带动整支移动（2026-09-24）

> 本文是**用户裁定的落点**，并**声明取代** Unit 扩容那一批 spec 里"位置继承式跟随"的部分。
> 历史文档**一字不改**（`docs/superpowers/specs/2026-09-20-unit-extension-design.md` 与
> `.superpowers/sdd/2026-09-20-unit-extension/**` 的台账照旧）；要更正就在**本文**追加说明。

## 〇 用户原话（2026-09-24）

1. 「这样，GM 可以通过命令拆分/合并/移动任意单位，**如果选中一个独立单位的最顶层，即使这个单位有归属，也可以自动移动**，
   这个单位**有下挂单位总速度为下挂单位中最慢者速度**；如果选中一个**有依附/一同移动**的单位，且**不是最父单位**，
   那么**提示应当控制顶层进行移动或拆分**。」
2. 「**把跟随功能取消掉吧**，如果要合并到同一一同移动的编制，**有且只有单位在同一格子时生效**。」
3. 速度口径追问 ⇒ 选「**含状态折算**」。
4. `mergeFormation` 把存活方 `speed` 写成子树最小值 —— 「**保留字段写入**」。
5. GUI 侧本轮「**先做只读提示**」。

## 一 新语义（取代 §一.3 / §一.4 的"跟随"）

| 概念 | 语义 |
|---|---|
| `parent` | **编制归属**（谁向谁报告）。可以"有归属但独立行动"。 |
| `attached` | 是否**与父同属一支"一同移动"的编制**。**不再**表示"位置由父继承"。 |
| **顶层（带动者）** | 沿 `parent` 上溯，遇到 `attached=false` 就停在它；**无父者一律算顶层**（不管 `attached`）。 |
| 可移动者 | **只有顶层**。顶层一动 ⇒ 它全部 `attached=true` 的后代**一起搬到同一格**（整支**始终同格**）。 |
| 成员 | **不能**自己下路线（拒，理由点名顶层 + 两条出路：控制顶层 / `unit.SplitFormation`·`unit.DetachUnit` 拆出来）。 |
| 进入编制 | **必须同格**（`attach`、`merge`、`createUnit` 三处各判）。⇒ 撤销"偏移式加入（不同格也能 attach）"。 |
| 位置 | **每个单位的位置永远是自己的**；`effectivePosition` 不再向父取（无位置 = 不知在哪）。 |
| `offset` / `SetFormationOffset` | 字段与命令保留（不破老档往返），但**不再影响任何计算**；描述里如实标注。 |
| 整支移动 | 按**顶层**的路线与地形成本推进；`Movement.speedAtDeparture` = 子树内 `effectiveSpeed` 的**最小值**（含状态折算）。 |

**取代声明**：`1edb74b`（让"移动时跟随"可达 + GUI 脱离/加入）与 `8a005b7`（偏移式加入 + 合体取最慢）服务的是"跟随"；
本次**反转**其方向——不要跟随，改为"要合并就回同格"。其中 `8a005b7` 的"合体速度取整支最慢"**保留**（裁定 4），
"不再要求同格"**作废**。

## 二 落点（代码）

- 引擎：`UnitState.effectivePosition`（去继承）、新增 `formationRoot` / `formationMembers` / `formationSpeed`、
  `UnitOperations.requireTopOfFormation`（闸门）+ `planRoute` 装载速度、`attachSubtree`（同格校验，不清位/不反算 offset）、
  `detachUnit`（只翻位）、`rejoinRoute`（只顶层）、`CreateUnitHandler`（无父 ⇒ 顶层；有父 ⇒ 同格）、
  `UnitTimeParticipant`（顶层推进时把成员一起搬到同一格）。
- 读口：`ApiViews.unit`（唯一实现）新增 `attached` / `formationRootId` / `formationSize` / `formationSpeed`；
  `ToolSupport.unit` 改为**转调**它（GUI 与 MCP 两面同源，不再各写一份）。
- 工具说明书：`PlanRoute`/`PlanSparseRoute` 补"首点必须是当前所在格"与"只有顶层能移动"；
  `Attach`/`Merge` 改"必须同格"；`Split`/`Detach` 补"拆出后成为独立顶层"；`SetFormationOffset` 标"v2 起无作用"。
- 前端（只读提示）：`renderer.formationOf`、`map-uniteditor.isFormationMember`（成员禁用移动 + 状态行点名顶层）、
  `unitTree` 节点标"随行 / 独立"、两个编队按钮的 title。

## 三 已知边界（如实记）

- **`attached=true` 的跨格成员可由 `ReparentUnit`/`ReparentSubtree` 造出**（它们只改归属、不看位置）：
  同格由"进入编制"的三条命令把关；越界状态会在**下一次整支移动**时被带到顶层所在格（自愈）。
- **`placeAt` 未设闸门**（它是显式落位的调试/运维命令）：对成员直接 `placeAt` 会把编制暂时拆开，需自行再对齐。
- **老档不回改**：若老档里真有"无自身位置 + attached ⇒ 向父取位"的单位，v2 下它们变成"不知在哪"（出手是 `unit.PlaceAt`）。
  本局 worldgen 的每个单位都自带位置，故无观测差异；`CreateUnitHandler` 已改为不再产生那种形态。
- `command_chain`（报告链）仍**不影响移动**（与 PFormation 正交，spec E1 的原设计）。
