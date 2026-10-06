# D2 施工契约：DecisionPacket / FormattedCall + propose/submit/my + GM packets/decide

> 批次：D2（规划入口 `docs/superpowers/plans/2026-10-22-decision-packet-household-query-llm-mcp-plan.md`）
> 基线：D1 已推送（`24968ae6`）。
> 设计依据：`docs/superpowers/specs/2026-10-21-decision-packet-and-household-query-usability-design.md` §3。
> 用户裁定：决策人填真实工具名 + JSON 参数；一决策人 × 一 tick 一个 packet；GM 整包 true/false；
> 执行者 GM、proposer 留痕；旧的 `sd.IssueDirective` / `sd.AdjudicateTick` 保留为旧口，不断依赖。

## 1. 持久实体（`simos-sd`）

新增模型（`simos-sd/src/main/java/io/mosire/simos/sd/model/`）：
- `DecisionPacketId(String value)`：非空白、`parse`。
- `MergedEffectPlanId(String value)`：非空白、`parse`（D2 先放入状态，D3 用）。
- `PacketStatus`：`DRAFT / PENDING / APPROVED / REJECTED / MERGED / PARTIALLY_APPROVED`。
- `CallStatus`：`PENDING / APPROVED / REJECTED / MERGED`。
- `FormattedCall(
      int callIndex, String toolName, String argsJson, List<CommandTarget> targets,
      String previewJson, List<String> draftChecks, CallStatus status, Optional<String> mergedPlanId)`
  - `targets` 用 `io.mosire.simos.util.spi.CommandTarget`（跨命名空间；Jackson record 序列化应可用，往返 smoke 要覆盖）。
- `DecisionPacket(
      DecisionPacketId id, String branch, long tick, DecisionMakerId proposerId, PacketStatus status,
      String intent, List<FormattedCall> calls, long createdAtRevision,
      Optional<String> decidedBy, OptionalLong decidedAtRevision,
      Optional<String> reasonInfoId, Optional<String> decisionNote)`
  - `intent` 可空字符串；`calls` 保序不可变；`reasonInfoId` 预留 D4 INFO 引用。
- `MergedEffect`（D3 用，D2 可先定义）：`(String toolName, String argsJson, List<String> sourceCallRefs)`。
- `MergedEffectPlan`（D3 用，D2 可先定义）：
  `MergedEffectPlanId id, long tick, List<DecisionMakerId> participantIds, List<MergedEffect> orderedEffects,
   List<String> sources, Optional<String> reasonInfoId, Optional<String> outcome`。

`SdState`（`io.mosire.simos.sd.state.SdState`）追加两个组件：
- `Map<DecisionPacketId, DecisionPacket> decisionPackets`
- `Map<MergedEffectPlanId, MergedEffectPlan> mergedEffectPlans`
- 旧档缺键 ⇒ 空表（`null` 收成 `Map.of()`，不抛）；键必须等于值内 id。
- canonical 构造器变为 14 参；**保留 12 参兼容构造器**（新组件取空表），使既有 `new SdState(...12...)` 调用点不必全改。
- 逐个 `with*` 更新为 14 参 canonical；新增 `withDecisionPackets` / `withMergedEffectPlans`。
- 构造期校验：packet 键 == packet.id；**packet.proposerId 允许指向已被删除的决策人**（历史包不级联删；
  新写入由 `UpsertDecisionPacketHandler` 校验 proposer 存在）；callIndex 在包内严格递增、不重复；
  `MERGED` call 必须带 mergedPlanId；`MERGED` 包的 plan 存在（D3 放宽，D2 可只查 shape）。

`SdChangeSet` 追加两个 `FieldDelta`：
- `decisionPackets`、`mergedEffectPlans`；canonical 变 14 参，**保留 12 参兼容构造器**（新字段 `Unchanged`）。
- `between` / `apply` / `isEmpty` 都要包含新组件。
- `SdCodec.keyModule()` 注册 `DecisionPacketId` / `MergedEffectPlanId` 的 key deserializer。

## 2. 命令与 handler（`simos-sd`）

全部走铁律 2：`Command → ChangeSet → Revision`，handler 只产 `SdChangeSet`。

### `sd.UpsertDecisionPacket`
- payload（扁平，避免嵌套大对象解析）：
  ```json
  {"id":"pkt-dm-1-360","branch":"main","tick":360,"proposerId":"dm-1","status":"DRAFT",
   "intent":"...","createdAtRevision":12,
   "decidedBy":null,"decidedAtRevision":null,"reasonInfoId":null,"decisionNote":null,
   "calls":[{"callIndex":0,"toolName":"simos.unit.raiseUnit","argsJson":"{...}",
             "targets":[{"namespace":"map","path":"Map1/region/r1"}],
             "previewJson":"{...}","draftChecks":["scope-ok"],"status":"PENDING","mergedPlanId":null}]}
  ```
- handler 校验并 `decisionPackets.put(id, packet)`（整包覆盖，同 id 幂等替换）。
- `CommandTargets.targetPaths`：从 payload 的 `proposerId` 返回 `decision-packet/<proposerId>`（决策人自己的包前缀；
  **注意**：不是 packet id，tick 不同也同前缀）。

### `sd.SubmitDecisionPacket`
- payload `{"id":"pkt-...","proposerId":"dm-1"}`；把 packet status 置 `PENDING`，全部 `PENDING` call 保持 PENDING。
- `targetPaths`：`decision-packet/<proposerId>`。
- 仅 DRAFT 可提交；否则具名拒。

### `sd.DecideDecisionPacket`
- payload `{"id":"pkt-...","decision":"APPROVE|DENY","note":"...","callIndexes":[0,1]?}`
- `APPROVE`：整包或指定 callIndexes 置 `APPROVED`，其余 `REJECTED`；无 callIndexes 且全部 approve ⇒ `APPROVED`，
  部分 ⇒ `PARTIALLY_APPROVED`。
- `DENY`：全部 call `REJECTED`，packet `REJECTED`。
- `decidedBy` = 调用者身份（handler 收不到 ToolContext，故 payload 必须带 `decidedBy`，由 GM 工具从
  `context.identity()` 派生后写入，**不采信模型自报**）；`decidedAtRevision` 用 base revision。
- `targetPaths`：`decision-packet/<id>`（GM 侧 sd unlimited，不受影响）。
- MERGE 留 D3（D2 收到 MERGE ⇒ 具名拒）。

**注册**：在 `Shell` 的 CommandHandler 列表里加这三个 handler（放在 `new PutInfoHandler()` 附近）。
三个 handler 的 command type 不得标 `GmOnlyCommand`（`sd.UpsertDecisionPacket` / `Submit` 由决策人工具内部提交；
`Decide` 只被 GM 工具调用）。

## 3. 决策人工具

新增 `simos-app/src/main/java/io/mosire/simos/app/tools/write/`（或 `read/`）：
- `ProposeCallTool`：`simos.sd.propose`，载荷 `{tool, args?, intent?}`。
- `SubmitPacketTool`：`simos.sd.packet.submit`，载荷 `{}`。
- `PacketIntentTool`：`simos.sd.packet.intent`，载荷 `{text}`。
- `MyPacketTool`：`simos.sd.packet.my`，载荷 `{}` 或 `{tick?}`（只读自己的 packet；可加在决策桶）。

四个工具都只在决策人桶，`DecisionCallerFactory.WHITELIST` 加名字；身份从
`DecisionCallerFactory.decisionMakerIdOf(context.identity())` 取，**不采信载荷**。

### 3.1 `simos.sd.propose` 语义
1. 解析 `tool` 名；必须在本批 **ProposalCatalog** 初始清单里（见 §3.4）且 `DecisionMaker` 的
   `allowedTools` 允许（空 `allowedTools` = 允许初始清单；非空 = 交集）。
2. 取当前状态；`tick = state.meta().timestamp().tick()`；读取/新建该 proposer 同 tick 的 DRAFT packet：
   `id = "pkt-" + proposerId + "-" + tick`。
   - 已存在且 status != DRAFT ⇒ 具名拒「本 tick 包已提交/已裁决」。
3. 用 `ProposalCatalog.preview(tool, state, args, deps)` 跑**真预览**（见 §3.3）；失败 ⇒ `BAD_REQUEST`，零写入。
4. 目标 scope 校验：`ProposalCatalog.targets(tool, state, args, preview)` 返回 `List<CommandTarget>`；
   用 `DecisionCallerFactory.resourceScopesFor(DecisionScopeFunctions.defaults(), dm, state, mapId)` 得到的
   `ResourceScopeMap` 逐条判：
   - `map`：hex 目标用 `ToolSupport.hexVisible` 同款两条通道（hex 资源 ∪ 所属 region 资源）；
     region 目标用 region 资源；根 map 路径用 `allows`。
   - `social`：`socialScope.allows(q_r)`。
   - `unit`：`unitScope.allows(unitId)`。
   - `actor`/其他：有 scope 则 `allows`，没 scope 则拒（fail-closed）。
   任一越界 ⇒ `FORBIDDEN` 具名拒（不写 packet）。
5. 生成 `FormattedCall(callIndex=现有 calls 最大+1, toolName, argsJson 规范化 JSON, targets,
   previewJson, draftChecks=["scope-ok","preview-ok"], status=PENDING)`，追加到 packet.calls，
   `intent` 有则写；用 `core.submit` 提交 `sd.UpsertDecisionPacket`（一条 revision）。
6. 返回 `{packetId, status, callIndex, tool, targets, preview}`。

### 3.2 submit / intent / my
- `submit`：只接受 DRAFT；提交 `sd.SubmitDecisionPacket`；幂等（已 PENDING 返回成功现状；已决定 ⇒ 具名拒）。
- `intent`：只接受 DRAFT；更新 intent（仍走 Upsert）。
- `my`：按 id 读自己的 packet（可给 tick），输出 status/intent/calls（含 `previewJson` 解析后的 Map）。

### 3.3 预览与目标提取（ProposalCatalog，避免复制 Plan 逻辑）
新增 `simos-app/src/main/java/io/mosire/simos/app/decision/ProposalCatalog.java`：
- 为初始清单每条注册一个 `DecisionProposable` 描述：
  - `preview`：用**系统预览上下文**调用目标工具的真 `execute`（`args + preview=true`，
    branch/revision 原样透传），拿 `ToolResult.message()` 解析为 `Map`。目标工具实例由 `ProposalCatalog`
    用 `(core, calendarService, query, initiator, mapId)` 现场构造；GM-only 工具也允许，因为**只跑 preview 不落盘**。
    系统上下文 = `AccessToken.SYSTEM` + `AgentPermissionSet.unrestricted`（必须带
    `ResourceAuthorizer.of(permissions, tool.resources())`，否则工具内部 `requireAll` 会拒）。
  - `targets`：从 **args + preview** 递归提取，按下列键：
    - `regionId` → `map:<mapId>/region/<id>`；
    - `at` / `hex` / `treasuryLocation`（含 `q`,`r` 的对象）→ `map:<mapId>/hex/<q>_<r>` +
      `social:<q>_<r>`；
    - `householdId` / `governmentHouseholdId` / `manpowerTargetHousehold` / `from` / `to`:
      在 state 里查家户，按其 `HouseholdLocation` 解析成 `social:q_r` 或 `unit:u`；查不到（新家户）则跳过；
    - `unitId` / `unit`：默认 → `unit:<id>`；**创建型工具**（`raiseUnit` / `selectExaminees` / `dispatchTeam`）
      的新单位 id 跳过，改为用 `at`/region 目标；
    - `sources[]` 递归（`householdId` + `hex`）。
  - 每条描述带 `Set<String> skipKeys` / `boolean createsUnit` 的配置，保证新单位/新家户不误判越界。
- 初始清单（存在且有 preview 的工具）：`simos.unit.raiseUnit`、`simos.gov.recruit`、
  `simos.gov.retireStaff`、`simos.social.household.members`、`simos.unit.levyRegion`、
  `simos.gov.selectExaminees`、`simos.gov.dispatchTeam`。
  `simos.unit.setArmyPayPolicy` 工具尚未存在（D4 才补 `simos.gm.armyPayPolicy`）⇒ 本批**不注册**，
  D4 实现后加入 catalog 并补 smoke。
  ★ **2026-10-23 补记**：D4 实际只做了 **GM** 工具 `simos.gm.armyPayPolicy`；决策人可 propose 的
  `simos.unit.setArmyPayPolicy` 是否进 catalog/`ProposalCatalog` **仍未定**，属 P4c 未决项
  （见 `docs/superpowers/status/2026-10-23-planned-not-implemented-inventory.md` §1.1）。
- 未在 catalog 的工具 ⇒ propose 具名拒。

## 4. GM 工具

- `simos.gm.packets`（只读，GM 桶，`GmOnlyRead`）：`{tick?, status?, proposerId?, tool?}`；只发摘要
  （id/tick/proposer/status/callCount/toolNames）。
- `simos.gm.packet`（只读，GM 桶，`GmOnlyRead`）：`{packetId}` 或 `{tick,proposerId}`；发全量 calls
  （args/preview/targets/draftChecks/status）。
- `simos.gm.packet.decide`（写，GM 桶）：`{packetId, decision: APPROVE|DENY, note?, callIndexes?}`；
  从 `context.identity()` 派生 `decidedBy`，提交 `sd.DecideDecisionPacket`；MERGE 返回 `BAD_REQUEST("MERGE 留 D3")`。
- 三个工具都需在 `SimosToolSource` 注册；`gm.packets`/`gm.packet` 用 `GmOnlyRead`；
  `gm.packet.decide` 加在 `addGmWrites`，资源声明 `sd` UNRESTRICTED。
- Catalog/GUI 不属 D2；D4 统一补。

## 5. 权限

- `DecisionCallerFactory.selfDecisionScope(dm)` 增加第二条前缀 `sd:decision-packet/<dm.id()>`：
  决策人只能写/读自己的 packet 前缀（propose/submit/intent/my 的目标声明是
  `decision-packet/<proposerId>`）。这是**自指资源的第二段**，不是放宽到全 sd。
- `DecisionCallerFactory.WHITELIST` 加四条决策人工具名。
- GM 侧 sd 已 unlimited，无需改。

## 6. 旧档兼容

- `SdState` / `SdChangeSet` 的新组件缺键 ⇒ 空表 / `Unchanged`；12 参兼容构造器保留。
- 旧 `Directive` / `sd.IssueDirective` / `sd.AdjudicateTick` 原样保留；新体系不读写它们。
- 重启重载 smoke：写 packet → 重启 Shell → 从 checkpoint/change set 读回逐字段相等。

## 7. 门禁 smoke（控制方执行）

- `tools/mvn-lock.sh -q -pl simos-app -am -DskipTests clean compile` rc=0。
- `/tmp/D2Smoke.java`（临时 harness）：
  1. small-world genesis；建 GOV 决策人（直辖区一个 region/hex，构造 `DecisionMaker` 直接使用）；
  2. `simos.sd.propose` 提 `simos.unit.raiseUnit`（目标在新直辖区 region 内、兵力/粮钱来自该 region 的家户）；
     - 断言 packet DRAFT、call 1 条、preview 含 `manpowerAllocation`、targets 全在 scope 内；
  3. `simos.sd.packet.submit` ⇒ PENDING；
  4. GM `simos.gm.packets`/`gm.packet` 看到 preview/args/targets；
  5. GM `simos.gm.packet.decide` APPROVE ⇒ APPROVED；
  6. 重启 Shell 后 `simos.sd.packet.my` / `gm.packet` 逐字段重载相等；
  7. 负例：propose 未知工具 / 目标越 scope（跨 region）/ 参数坏 ⇒ BAD_REQUEST/FORBIDDEN 且 packet 不新增；
  8. `sd.packet.my` 只能读自己的包；身份从 context 派生，载荷自报 proposer 被忽略。
- 不跑 test/test-compile/verify；不 commit/push。

## 8. 实际 smoke 证据（2026-10-22）

- `tools/mvn-lock.sh -q -pl simos-app -am -DskipTests clean compile` → rc=0（`/tmp/d2-parent-gate.log`）。
- `/tmp/D2RoundTrip.java`（临时）：`CommandTarget` / `FormattedCall` / `DecisionPacket` /
  含两个新组件的 `SdSnapshot` 往返逐字段相等；旧档缺 `decisionPackets`/`mergedEffectPlans` 键 ⇒
  空表 / `Unchanged` 且不 NPE；7/7 PASS。
- `/tmp/D2Smoke.java`（临时，真实 small-world Shell + 真身份上下文）：
  propose raiseUnit（真预览含 manpowerAllocation、targets 全在 scope、载荷自报 proposer 被忽略）→
  submit（幂等）→ GM `gm.packets`/`gm.packet` 看到 args/preview/targets → APPROVE（decidedBy=external-mcp）→
  `sd.packet.my` 身份自指读取 → 关壳重开同 store 后逐字段相等、preview 仍在 → 未知工具/坏参数 BAD_REQUEST、
  跨 region FORBIDDEN 且零写入 → 部分批准 `PARTIALLY_APPROVED` → 已裁决包 intent BAD_REQUEST；48/48 PASS
  （`/tmp/d2-parent-smoke2.log`）。
- 历史包兼容：`DeleteDecisionMaker` 删身份后不级联 packet；`SdState` 允许 packet.proposerId 指向已删除决策人
  （`/tmp/PacketDeleteSmoke.java` PASS）。
