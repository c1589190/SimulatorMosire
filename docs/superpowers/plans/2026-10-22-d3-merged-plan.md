# D3 施工契约：MergedEffectPlan + packet.execute + outcome 回写

> 批次：D3（规划入口 `docs/superpowers/plans/2026-10-22-decision-packet-household-query-llm-mcp-plan.md`）
> 基线：D2 已推送（`996cfd41`）。D3 复用 D2 的 `DecisionPacket`/`FormattedCall`/`MergedEffectPlan` 状态与
> `ProposalCatalog` 真预览体系。

## 1. 目标与硬口径

1. 一个 tick 的**所有已批准 call / merged plan** 执行 = **一条 revision**（`CoreSimos.submitBatch` 原子批）。
2. 执行者身份 = GM；每条 call 的 proposer 留在 packet / outcome 里。
3. 冲突 / 批拒 / 规划失败都退回 GM 具名报告，**不静默剔 call、不部分执行**。
4. `FormattedCall` / `MergedEffectPlan` 的 outcome 在**同一批**内回写（批提交才落，批拒零 revision）。

## 2. `FormattedCall` 增补：outcome

D3 给 `FormattedCall` 增加最后一个组件：
`Optional<String> outcomeJson`（执行/批结果摘要，JSON 字符串；未执行 = `Optional.empty()`）。
- 旧 8 参构造器保留（outcome 取 empty）；canonical 变 9 参；compact 构造器把 null 收成 empty。
- `SdPayloads.optionalFormattedCalls` 读 `outcomeJson`（键缺席/null ⇒ empty）。
- `DecisionPacketPayloads.callView` 输出 `outcomeJson`（null 或字符串）。
- `GmPacketTool` / `MyPacketTool` 视图自然带上。

## 3. `MergedEffectPlan` 的写命令

新增 `sd.UpsertMergedEffectPlan`（`simos-sd/spi`）：
- payload 扁平字段：
  ```json
  {"id":"merge-360-1","tick":360,"participantIds":["dm-1","dm-2"],
   "orderedEffects":[{"toolName":"simos.unit.raiseUnit","argsJson":"{...}","sourceCallRefs":["pkt-dm-1-360:0"]}],
   "sources":["pkt-dm-1-360:0","pkt-dm-2-360:0"],"reasonInfoId":null,"outcome":null}
  ```
- 整包 upsert（同 id 幂等替换）；`participantIds` 允许历史已删除决策人（与 packet 同口径，不做存在性校验）。
- `targetPaths`：`merged-plan/<id>`（GM 侧 sd unlimited）。
- 在 `SdPayloads` 增加读取助手；`SdState` 的 `mergedEffectPlans` 构造期只查 shape（已有）。

## 4. `gm.packet.decide` 支持 MERGE

D2 的 `simos.gm.packet.decide` 对 `MERGE` 返回 BAD_REQUEST；D3 改为：
- 工具参数新增 `mergedPlanId?`；`decision=MERGE` 时必须给。
- `DecideDecisionPacketHandler` payload 增加 `mergedPlanId`（仅 MERGE 时必填）：
  - 目标 plan 必须在 `sd.mergedEffectPlans` 里存在；
  - packet 状态置 `MERGED`，**所有当前 PENDING call** 置 `CallStatus.MERGED` 并写 `mergedPlanId`；
  - `decidedBy`/`decidedAtRevision`/`decisionNote` 同 D2；
  - `callIndexes` 与 MERGE 互斥（给了 ⇒ 具名拒）。
- `GmDecidePacketTool` 的 view 同步 MERGE 状态摘要。

## 5. 七条可 propose 工具支持 `planOnly`

D3 执行器需要"同一条 call 在同一 base state 上产出**命令批**但不提交"。给初始清单 7 条工具的 `execute`
增加内部参数 `planOnly`（**不进 jsonSchema，不对外宣传**；只由 D3 执行器用系统上下文注入）：
- `planOnly=true` 时，工具走与 `preview=false` 相同的纯推导 + 组批路径，但在
  `core.submitBatch` **之前**返回：
  ```json
  {"preview":false,"submitted":false,"planOnly":true,
   "plannedCommands":[{"type":"...","payloadJson":"..."}, ...]}
  ```
- 原有 preview/apply 行为不变；`planOnly` 只多一个提前返回分支。
- 覆盖：`RaiseUnitTool`、`GovRecruitTool`、`GovRetireStaffTool`、`GovSelectExamineesTool`、
  `GovDispatchTeamTool`、`LevyRegionTool`、`SocialHouseholdMembersTool`。
- 实现建议：把 `apply(...)` 内已有 `batch` 在提交前统一走一个小助手
  `ToolSupport.plannedCommandsView(batch)`（新增 app 层助手；只读 envelope 的 type/payloadJson/顺序）。
- `planOnly` 分支不得调用 `core.submitBatch` / `core.submit`。

## 6. 执行器 `DecisionEffectExecutor`（app 层）

新增 `simos-app/.../decision/DecisionEffectExecutor.java`：
- 输入：`SimulationState base`、`List<EffectCall>`（toolName/argsJson/sourceRefs/所属 packet+callIndex）、
  `CoreSimos`、`ProposalCatalog`（或 7 条工具实例）、`QueryService`。
- 逐步：
  1. 对每条 call 用系统上下文调工具 `execute`，强制 `preview=false`、`planOnly=true`、
     `branch/revision` = base 坐标；失败 ⇒ 具名拒（整批零 revision）。
  2. 汇总所有 `plannedCommands`，按 call 顺序拼接成 `List<CommandEnvelope>`（同一 branch/expectedRevision，
     同一 `batchId`，逐条新 commandId；`initiator` 由调用方给）。
  3. 追加 outcome 回写命令（见 §7）到批尾。
  4. `core.submitBatch(batch)`；`Committed` ⇒ 逐 call 的 command index 区间映射到 `CommandOutcome`；
     `Rejected`/`Conflict` ⇒ 返回具名结局，不写 outcome。
- 排序：稳定序 = `packetId` 字符串序 → `callIndex` 升序；merged plan = `orderedEffects` 顺序。
- 一条 call 可能产出 0 条命令（例如退化批）：允许，outcome 记 `empty`。

## 7. Outcome 回写（同一批）

- 对每条被执行的 call，在 `planning` 后预先构造 outcome 字符串：
  `{"result":"committed","batchId":"...","commandFrom":i,"commandTo":j}`；批提交成功才生效。
- 追加 `sd.UpsertDecisionPacket`：对涉及的每个 packet，把执行到的 call 的 `outcomeJson` 写成上述字符串
  （其余字段原样）。
- 合并计划：追加 `sd.UpsertMergedEffectPlan`，把 plan.outcome 写成同一摘要 + 逐 effect command 区间。
- 追加一条 `sd.PutInfo`（地址 `sd:execution.<batchId>`，key=`result`，value=摘要 JSON 字符串，
  tick=当前 tick）作为可读审计；与效果命令同批 ⇒ 一条 revision。
- `gm.packet` / `sd.packet.my` 因此能读到 outcome。

## 8. GM 工具

### `simos.gm.mergedPlan.upsert`
- 参数 `{planId?, tick?, participantIds?, sources?, reason?, orderedEffects[...]}`；
  `orderedEffects` 每项 `{toolName, argsJson, sourceCallRefs?}`。
- `planId` 缺省生成 `merge-<tick>-<n>`（n = 当前 tick 已有计划数 + 1，稳定确定性）。
- 校验 effect 工具必须在 `ProposalCatalog` 清单（D3 不允许任意工具，避免执行面失控）。
- 提交 `sd.UpsertMergedEffectPlan`；返回计划视图。

### `simos.gm.mergedPlan.apply`
- 参数 `{planId, branch?, expectedRevision?}`；只接受尚未有 outcome 的计划（重复 apply ⇒ 具名拒）。
- 调 `DecisionEffectExecutor`，`orderedEffects` 顺序执行；一条 revision；返回逐 effect outcome。
- 需要先在 `gm.packet.decide(MERGE)` 之前/之后创建？**先后顺序**：
  1. `gm.mergedPlan.upsert` 建计划；
  2. `gm.packet.decide(MERGE, mergedPlanId)` 把 packet/call 标 MERGED；
  3. `gm.mergedPlan.apply` 执行。
  反过来（先 decide MERGE 再 upsert）会被 `Decide` 的"plan 必须存在"拒——这是可接受的具名顺序约束。

### `simos.gm.packet.execute`
- 参数 `{tick?, packetId?, branch?, expectedRevision?}`；`tick` 与 `packetId` 至少一个（都缺省 ⇒ 当前 tick）。
- 选出状态 `APPROVED` / `PARTIALLY_APPROVED` 的 packet 中 `CallStatus.APPROVED` 的 call，
  按稳定序执行一条 revision；`MERGED`/`REJECTED`/`PENDING` 不执行。
- 重复 execute：已被 outcome 记过的 call 跳过；至少一条可执行 call 才提交，否则返回 `BAD_REQUEST`。
- 返回 `{batchId, revision?, executed:[{packetId, callIndex, tool, commandFrom, commandTo, result}],
  submission?}`。

## 9. 注册

- 三个 handler 加入 `Shell` handler 列表：`UpsertMergedEffectPlanHandler`。
- 三个 GM 工具加 `SimosToolSource.addGmWrites`（`simos.gm.mergedPlan.upsert` / `.apply` /
  `simos.gm.packet.execute`），资源声明 `sd` UNRESTRICTED；都是敏感写，走 `ToolGate.Ask`。
- `CatalogTool.PAYLOAD_HINTS` 补 `sd.UpsertMergedEffectPlan` 一条（新命令类型必须有提示，否则装配炸）。
- 旧 `DecisionCallerFactory.WHITELIST` 只加决策人工具，不动。

## 10. 门禁 smoke（控制方执行）

- `tools/mvn-lock.sh -q -pl simos-app -am -DskipTests clean compile` rc=0。
- `/tmp/D3Smoke.java`：
  1. small-world + 两个 GOV 决策人（直辖区含家户）；各自 propose 一条会冲突/相交的
     `simos.unit.raiseUnit`（同一 region、总人力够但单独抽会撞车）→ 各自 submit；
  2. GM `gm.mergedPlan.upsert` 建计划 `merge-0-1`（把两条 call 合成两条 effect，指定顺序）；
  3. GM `gm.packet.decide MERGE mergedPlanId` 把两个 packet 标 MERGED + calls MERGED；
  4. 记录 apply 前 head；`gm.mergedPlan.apply`；
  5. 断言 head 只 +1；两条 effect 都 committed；outcome 写回 plan、call、INFO；
  6. 另建两个同 tick APPROVED packet，走 `gm.packet.execute`；断言 head 只 +1、逐 call outcome；
  7. 冲突/重复 apply/未知 plan ⇒ 具名拒且零 revision。
- 记录到 `/tmp/d3-smoke.log`；不跑 test/verify；不 commit/push。

## 11. 实际 smoke 证据（2026-10-22）

- `tools/mvn-lock.sh -q -pl simos-app -am -DskipTests clean compile` → rc=0（`/tmp/d3-parent-gate.log`）。
- `/tmp/D3Smoke.java`（临时，真实 small-world Shell）：
  - `gm.mergedPlan.upsert` 建 `merge-0-1`（2 effects）→ 两包 `gm.packet.decide(MERGE, planId)`；
  - `gm.mergedPlan.apply`：head 只 +1；2 effect 全 committed；`plan.outcome`、两条来源 call 的
    `outcomeJson`、`sd:execution.<batchId>` INFO 同批落盘；
  - 另两个 APPROVED packet 走 `gm.packet.execute`：head 只 +1、逐 call outcome、call 状态不被静默改；
  - 负例：未知 plan `NOT_FOUND`、重复 apply/execute `BAD_REQUEST`、过期 `expectedRevision` `CONFLICT`、
    MERGE 缺 `mergedPlanId`/未知 plan 具名拒，全部零 revision；
  - 结论：47 PASS / ALL PASS（`/tmp/d3-parent-smoke.log`）。
- 已知边界：`gm.mergedPlan.apply` 在合并计划的 effect 工具 `planOnly` 规划失败（例如两条 raiseUnit
  在同一 base 上抓同一成员批次）时，整批 REJECTED、head 不变；这是"冲突退回 GM"的预期行为。
