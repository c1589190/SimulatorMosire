# DM→GM 决策包迁移：AgentLib 通用包 vs Simos 半迁移（设计对比，2026-10-17）

> **取代者：`docs/superpowers/specs/2026-10-17-dm-gm-agentlib-decision-package-design.md`（2026-10-17）** —— 本文仅作方案对比留痕，**勿据此开工**。
> **性质**：设计对比，不写生产代码、不派单。
> **用户口径**（2026-10-17）：AgentLib 的「所有子 Agent 执行时需要判决的工具调用都进包」，在 Simos 翻译为「**所有 DM 执行、需要 GM 确认的工具调用都进同一个决策包**」。
> **已定交互**：DM 的 `revise/drop/intent` 保留，改成非 `Ask` 的包控制面；纯自然语言新增真实 `request` 工具入包。
> **待定**：AgentLib 是否扩展通用包 API，还是 Simos 只做半迁移。

---

## 0. 一句话结论

**AgentLib 现在的 `DecisionPackages` 不是“写错了”，而是按“一轮、内存态、库发 id、不可追加”的 Brain 场景写的。** 对 Brain 没问题；对 Simos 的“DM × tick 持久包、同 tick 多轮追加”不够通用。

如果目标是“AgentLib 通用决策包真正适配 DM→GM 流程”，应当选 **方案 A：扩展 AgentLib 的通用缝**；方案 B（不改 AgentLib）只能在 Simos 里再做一套包状态机，属于半迁移，不能宣称“包是唯一决策单元”。

---

## 1. 问题现场：AgentLib 的四个假设

| # | AgentLib 当前假设 | 代码证据 | Simos 的真实要求 | 后果 |
|---|---|---|---|---|
| 1 | 包 id 由库生成 `dp-N` | `DecisionPackages.java:1097` `nextId()` | 现有包 id 是 `pkt-<dm>-<tick>` | 要沿用现有包 id/工具/测试，库必须支持宿主给 id，或加映射层 |
| 2 | 同一 proposer 只能有一个未决包，`propose` 后不能追加 | `DecisionPackages.java:246` `PACKAGE_ALREADY_OPEN`；`:255-259` 只在 propose 时写 items | 同一 tick 同一 DM 可跑 4 轮，4 条 call 都进同一包 | 每轮新建包 ⇒ 4 个包；保留一个 queue 跨轮 ⇒ GM 看不到中间提交 |
| 3 | 包是内存态，进程退出即失效 | `DecisionPackages.java:20-22` 明确“不落库 / 进程退出即失效是对的” | `GaState.decisionPackets` 是世界状态，必须可回放、可重启 | 重启后库表空、世界包仍在，GM 再裁决时库不认识它 |
| 4 | `DecisionPackage.items` 是不可变列表，没有 replace/remove | `DecisionPackage.java:36-40` | 用户刚裁定保留 `revise/drop/intent` | 只能把编辑逻辑放到 Simos 侧，又形成一套状态 |

另有两点**不是库的缺陷**：

- **空包**：`DecisionPackages.java:208` 拒绝空 items；但用户已选“新增真实 `request` 工具入包”，包始终非空，这条不用改库。
- **`MERGED`**：AgentLib 通用状态没有 `MERGED`；这是 Simos 的合并效果集领域语义，应留在 Simos 的投影/提交路径，不要求通用库认识它。

### 1.1 这算 AgentLib 出问题了吗？

- **按 Brain 的原始用途**：不算。它明确选择了“一轮一个临时包、内存态”；这是设计取舍，不是实现 bug。
- **按“通用决策包要适配多个宿主”的目标**：算 **通用性缺口**。它把“一轮、临时、库发 id、不可追加”写进了 API，没有给持久宿主留 seam。
- 所以准确说法是：**AgentLib 没有坏，但它现在还不是你要的那个“通用”包；要么把通用性补在库里，要么接受 Simos 的半迁移。**

---

## 2. 目标流程（两个方案共用）

```text
DM 回合
  ToolGate.Ask
    └─▶ PendingDecisionQueue（本轮）
          ├─ 不执行、不阻塞、不发 PendingApprovals
          └─ 返回「已加入决策包」

  回合结束
    └─▶ 把队列条目追加到该 DM 当前 tick 的包

GM
  看包（DecisionPacket 投影）
    ├─ 整包批 / 整包驳 / 逐条批驳
    ├─ revise/drop/intent（非 Ask 的包控制面）
    └─ MERGED（Simos 领域扩展）

  执行
    └─▶ DecisionCommitPort / DecisionEffectExecutor
          └─▶ 被批子集一次 submitBatch = 一条 revision
```

关键不变量：

1. 一 DM × 一 tick × 一个包；
2. 同一 tick 多轮追加，包 id 不变；
3. 所有 DM `Ask` 自动入包，不再走单条 `PendingApprovals`；
4. `Block` 仍硬拒、`Allow` 仍直执行；
5. GM 裁决/执行仍只落一条 revision；
6. 包状态可持久、可回放、可重启恢复；
7. `request` 工具是纯文本包条目，不产生本地世界写。

---

## 3. 方案 A：扩展 AgentLib，做完整迁移

### 3.1 AgentLib 侧（只增不减）

建议新增/开放以下通用能力，默认旧行为不变：

| 能力 | 形状（示意） | 说明 |
|---|---|---|
| 宿主包 id | `ProposeRequest` 可带 id，或 `propose(request, id)` 重载 | 保留 `dp-N` 为默认；Simos 传 `pkt-<dm>-<tick>` |
| 同包 append | `append(packageId, List<PackageItem>)`，只允许 `PROPOSED`/`DECIDING` | 追加条目统一分配 index；不跨 DM/tick |
| 包内编辑 | `replaceItem/removeItem` 或 `DecisionDraft` 控制面 | 支撑 DM 的 revise/drop/intent；只能在未裁决包上 |
| 快照/恢复 | `snapshot(packageId)` + `restore(DecisionPackage)`，或 `DecisionPackageStore` | 让持久宿主在重启后重建包表 |
| 事件/持久钩子 | 可选 `DecisionPackageStore` / 事件端口 | 包状态变化与宿主 revision 对齐，避免“先改内存、后落盘失败”两套状态 |

不要求 AgentLib 知道 GM/DM、tick、preview、targets、MergedEffectPlan。

### 3.2 Simos 侧

- `DecisionAgentService.runRound`：绑定 `DecisionTurnScope` + 每轮 `PendingDecisionQueue`，`finally` 清理；
- DM 面 `ToolCallAuthorizer`：`Ask` 自动入 queue；`PendingApprovals` 只留给 GM/其他链；
- 回合末：`queue.toProposeRequest(...)` → `append` 到 `pkt-<dm>-<tick>`；
- `ProposalCatalog`：改成 `ItemValidator` / preview / 目标提取；
- `DecisionProposable`：不再决定“能否入包”，退役；
- 显式 `propose/confirm`：删除或改为非 Ask 的包控制；`revise/drop/intent` 改成非 Ask 控制面；
- 新增 `simos.ga.request`：纯文本、`Ask`，入包，满足纯 NL 场景；
- `DecisionPacket`：保留为**持久投影 + 领域明细**（branch/tick/intent、FormattedCall 的 preview/targets/draftChecks/outcome、mergedPlanId）；
- GM：`GmDecidePacket`/逐条裁决 → `DecisionSubmissionPort`；`GmPacketExecute` → `DecisionCommitPort`；
- `DecisionEffectExecutor`：实现 `DecisionCommitPort`，`CommitReceipt.revisionRef` 映射 Simos revision；
- `MERGED`：投影状态 `MERGED` + `mergedPlanId`，执行仍走合并计划/专用 commit 路径，不要求 AgentLib 增加状态；
- `DecisionPackages` 的 deadline：默认不新增墙钟语义；宿主按 tick 生命周期计算 deadline 或使用极长值，另条设计再定。

### 3.3 代价

- 需要改 `~/ProjectMosire/AgentLibMosire` 并升版本；
- 需要保证 Brain 既有 `propose` 默认行为与测试不变；
- 包状态迁移与 Simos revision 的事务/恢复要单独设计；
- 工作量最大，但只有一套包状态机。

---

## 4. 方案 B：不改 AgentLib，Simos 半迁移

### 4.1 做法

- 只复用 `PendingDecisionQueue` + `DecisionTurnScope`；
- 回合末把 queue 条目转成 `FormattedCall`，继续走现有 `UpsertDecisionPacket` / `SubmitDecisionPacket`；
- GM 裁决/执行仍由现有 `DecisionPacket` 状态机承担；
- `DecisionPackages` / `DecisionPackagesHttpHandler` / `DecisionCommitPort` 仍不使用（或只做未来预留）；
- 现有 `ProposalCatalog`/`DecisionProposable`/`revise/drop/intent` 继续保留，但需要把 `propose/confirm` 从 `Ask` 改成非 Ask 控制面，避免“入包递归”。

### 4.2 代价

- 只能算“AgentLib 队列 + Simos 包状态机”，**不是 AgentLib 决策包迁移**；
- `DecisionPacket` 与 `DecisionPackage` 的语义会长期并存，未来接第三个宿主时还要重做；
- `DecisionPackages` 的 append/persistence/restore 问题仍在，只是暂时不用；
- 工作量最小，风险集中在 Simos 本地，但会留下“两套包状态机”的长期债。

---

## 5. 状态映射（方案 A 需要；方案 B 保持现有）

| Simos 现有 | AgentLib `PackageState` | 说明 |
|---|---|---|
| 无包（仅 queue） | 无 | 本轮还没 flush |
| `DRAFT` | `PROPOSED` | 包已建、仍可追加/编辑 |
| `PENDING` | `PROPOSED` / `DECIDING` | “提交给 GM”不是锁；逐条裁决中进入 DECIDING |
| `APPROVED` / `PARTIALLY_APPROVED` | `DECIDED` | 已裁决、等执行 |
| `MERGED` | `DECIDED` + 领域 `mergedPlanId` | AgentLib 不新增 `MERGED`；投影保留领域状态 |
| 执行成功 | `COMMITTED` | 一次提交、一条 revision |
| 执行失败/冲突 | `REJECTED` / `CONFLICTED` | 零落盘语义保持一致 |
| 超时/作废 | `EXPIRED` | deadline/tick 语义需另定 |
| 纯 NL | 无包 / `request` 条目 | 用户已选“新增真实 request 工具入包” |

---

## 6. 验收判据（两方案都必须覆盖）

1. DM 的所有 `Ask` 都返回“已加入决策包”，不执行、不进 `PendingApprovals`；
2. `Block` 仍硬拒，`Allow` 仍直执行；
3. 同一 tick 同一 DM 连跑 4 轮，同一个 `pkt-<dm>-<tick>`，call 累积为 4 条（现有 `GmActAsToolTest` 判据）；
4. `revise/drop/intent` 在未裁决包上可用，且不触发再入包；
5. GM 整包批/逐条批/驳，批准子集一次提交、一条 revision；
6. `MERGED` 合并计划仍可执行，原 call 和 outcome 可追溯；
7. 纯 NL 的 `request` 入包，不产生本地世界写；
8. 重启/回放后，未决包仍可被 GM 看到并裁决；
9. 重复提交/提交已裁决包/越界调用有具名拒，零落盘；
10. 现有 `DecisionPacket` 端到端用例逐条保留或明确改判，不能用“新包能跑”替代旧语义。

---

## 7. 建议

- **如果目标是“AgentLib 通用决策包真正成为 DM→GM 的唯一包状态机”：选 A。**
- **如果当前只求最小改动、接受两套状态机：选 B，但文档必须标“半迁移”，不能写成“AgentLib 决策包已完成迁移”。**
- 我建议先把 A 的通用 API 设计冻结，再派实现；不要把 B 的临时适配伪装成 A。

---

## 8. 未做 / 未验证

- 未修改生产代码；未改 AgentLib；未提交。
- 未运行测试、门禁、真 LLM。
- 未逐项实现 A 的 API；本文只是设计对比，不是实现授权。
- `deadline`、`EXPIRED`、事务/回滚、host store 的具体形状仍需后续设计批次定。
