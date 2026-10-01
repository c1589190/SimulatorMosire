# 后端 + MCP 稳定化开发计划（调查后定稿草案）

> 日期：2026-10-01
> 依据：`docs/superpowers/reports/2026-10-01-backend-mcp-stabilization-investigation.md`
> 状态：**待用户确认 §6 裁定项后实施；本文不写生产代码**
> 总原则：先后端、后 GUI；所有写入 `Command → ChangeSet → Revision`；生成器/初始化/组合工具 preview 先行；一键落盘必须 clean gate；一轮一个写代码代理，测试统一留最后；控制方审后提交。

---

## 0. 目标（后端验收）

1. 任意空白 Region 可由 GM MCP 工具组按意愿初始化人口、批次、城市、家户、经济、GOV、决策人、军队。
2. 城市生成器与省份划分器都有“只出建议 / 一键落盘”两种模式；一键落盘在目标区域已有相关元素时具名拒绝、零 revision、不部分覆盖。
3. 政府创建统一走 Unit 流程，不留第二套政府语义。
4. 按格直接建军与抽取组建分立。
5. 决策人 due / 批量派出 / 汇总 / 裁决 / 改图 / advance 可由纯 MCP 闭环完成（审批见 §6 裁定）。
6. 每条 GM 工具经真实 MCP `tools/call` 可达；写工具 committed 恰 +1 revision 或具名拒。

---

## 1. 批次总览

| 批次 | 名称 | 依赖 | 代码范围 | 门禁 |
|---|---|---|---|---|
| P0 | 基线冻结与工具面自证 | 无 | 文档计数、ResourceManifest 对齐、McpGmToolReachabilityTest 骨架、preview 零 revision 测试模板 | 只编译 + 新测试红后绿（测试阶段统一） |
| P1 | 任意 Region 数据播种 + clean gate | 无 | `simos.region.seed`（或低层四工具）批组合、只读 preflight、`social/economy/actor` 同批 | 空 Region 成功；脏 Region 具名拒、零 rev |
| P2 | 城市生成器 | P1 | `simos.city.generate` plan + apply；复用 SettlementGenerator/Seeders | 确定性样例；干净门；shortfall/全海洋具名 |
| P3 | 省份划分器 | P1 | `simos.province.divide` plan + apply；递归主轴二分 + 连通修复 | 连通/均衡/确定性；一键落盘命令序同源 |
| P4 | Unit 政府流程补全 | 无 | `simos.gov.createOffice` 补 `unit.SetJurisdiction`、gov 读回、level/superior 校验 | 中央/省 GOV 可读回 jurisdiction；手动序逐条同源 |
| P5 | 按格直接建军 | 无 | `simos.unit.spawnArmy` preview/apply；保留 raiseUnit | 同批一条 rev；半写不可能；失败具名 |
| P6 | allowedTools + 决策人读口 | 无 | `DecisionCallerFactory` per-DM 白名单；dueOnly；directives/results/docs GM 读口 | N9 生效；空名单语义按裁定；读口只读 |
| P7 | 审批 + 回合闭环 | P6 | 审批 list/decide MCP 化；`simos.sd.run-due`；adjudicate preview/防二次；advance drain | 纯 MCP 回合脚本可跑；preview 0 rev；失败停点可重试 |
| P8 | 数值风险与假旋钮 | 无 | GovDaily 粮耗口径；`SetFormationOffset`/`SdAddCombatStage` 静默项 | 数值对拍；静默项删除或具名拒 |
| P9 | 后端端到端验收 | P1–P8 | 空白 Region → 城市/省份/GOV/DM/军队 → advance → 决策人回合 → 裁决改图 → 下一回合 | 真 MCP + 真数据；无 GUI 依赖 |

---

## 2. 各批次范围与判据

### P0 基线冻结与工具面自证
- 清掉旧数字漂移：`AGENTS.md:434/441`、`SimosToolsTest`、`McpServerTest`、`McpPortTopologyTest` 中 67/90/非窄 12/unit 22 等。
- 对齐 15 条 `sd.*` 窄写与 `actor.AdjustAccounts` 的 ResourceManifest（实际写 sd/actor 就声明 sd/actor）；`VoidAdjudication` 跨域声明；`ForkTool` 资源声明。
- 新增 `McpGmToolReachabilityTest`：表集合与 `SimosToolsSource` GM 桶、`tools/list`、磁盘 `*Tool.java NAME` 三者双向相等；每工具最小合法调用；写工具 committed 恰 +1 或具名拒。
- 把 preview 零 revision 判据模板从 levy/debt/raise 复制到全部 preview 工具。
- 不引入新功能；不改域语义。

### P1 任意 Region 数据播种
- 高层工具 `simos.region.seed`（推荐）或低层四工具；至少支持：
  - 显式 `entries`（逐格人口/批次/城市）与生成式 `totalPopulation + seed` 混合；
  - `economy: boolean` / `actor: boolean` 显式开关；
  - clean gate：目标 Region 内 population/groups/city/economy/actor 任一记录命中即拒（精确集合按 §6 裁定）。
- 固定命令序与 `WorldgenInitializeTool` 同源：`SetPopulation → CreateCity×N → SeedGroups → [economy.Seed] → [actor.Seed] → sd.PutInfo`。
- `preview=true` 只读、零 revision；`preview=false` 一条 revision；脏区具名拒。
- 复用 `PopulationSeeder` / `EconomySeeder.plan` / `HouseholdSeeder.payload`，不写第二份推导。

### P2 城市生成器
- `simos.city.generate`：输入 `regionId + totalPopulation + urbanizationRate + ... + preview`；输出 `SettlementPlan` 摘要 + `commandsPreview`。
- 建议模式零写入；一键落盘走 P1 的批组合与 clean gate。
- 边界：全海洋/零承载力 → `BAD_REQUEST`；shortfall>0 原样返回但不阻止；`CapitalAnchor` 暂无 hex，输入用现有 name/targetPopulation；G 的 `capitalHex` 作为后续增量（需扩展 request）。
- 参数默认来源：`SettlementParams.defaults()`；不要依赖 3 国 JSON。

### P3 省份划分器
- `simos.province.divide`：只出建议；输入 `regionId + maxHexPerProvince(默认50) + minHexPerProvince(默认20) + capitalHex? + namingPrefix?`。
- 算法：递归主轴二分 + 连通修复 + 确定性 tie-break；建议输出 `provinces[]/capitalDistrict?/warnings[]`。
- 一键落盘：`map.CreateRegion×N → unit.CreateUnit×(N+1) → unit.SetGovFormation×(N+1) → unit.SetJurisdiction×N → sd.CreateDecisionMaker×(N+1) → [provider/access] → sd.PutInfo`；命令序列与 Agent 手动调用同源。
- clean gate：相关元素定义按 §6 裁定（尤其“外部相交 Region”是否算）。
- 命令数 4N+3；N 大时是否分片按裁定；无论分片与否，一键落盘必须“全部成功一条 revision / 任一拒零 revision”。

### P4 Unit 政府流程补全
- `GovCreateOfficePlan` 增加 `unit.SetJurisdiction`（中央 `regions=[]` 或首都圈；省本省 Region）。
- 新增/扩展 gov 读回：`level/superiorGov/staff/policy/jurisdiction`（扩展 `simos.unit.get` 或新增 `simos.gov.get`）。
- 可选：CENTRAL⇒无 superiorGov、PROVINCE⇒superiorGov 为既有 GOV 的硬校验。
- 保持 `simos.gov.createOffice` 为薄组合；不新建政府实体命令。

### P5 按格直接建军
- `simos.unit.spawnArmy`（GM-only）：preview/apply；批序 `unit.CreateUnit → [unit.SetArmyFormation?] → sd.CreateArmy → sd.PutInfo`；一条 revision。
- 语义：直接建军不抽人抽粮；`hex` 必须在 `GameMap` 上；`member≥1`；`equipment` 默认 `{}`；`masterGov` 只写 sd 或双边按 §6 裁定。
- `raiseUnit` 保持抽取语义，不动；若要求管辖前置，另加具名门并给 `unit.SetJurisdiction` 指路。

### P6 allowedTools + 决策人读口
- 修 `DecisionCallerFactory`：新增 `whitelistFor(DM)`；`permissionsFor` 与 `DecisionAgentRunner` 工具面都按 DM 的 allowedTools 求交；空名单语义按 §6 裁定。
- 新增 `dueOnly` 过滤（优先并入现有 `simos.sd.decision-makers`）。
- 新增 GM 读口：`simos.sd.directives`、GM 版 `DecisionResults`/`DecisionDocs`（若裁定开放）、run-status（若需要）。

### P7 审批 + 回合闭环
- MCP 工具：`simos.gm.approvals`（只读 list）、`simos.gm.approve`（decide；按 AgentLib `PendingApprovals.decide` + `ApprovalCoordinator.effectiveDecision`；非世界 revision，但需审计）。
- `simos.sd.run-due`：preview 只算 due 名单（0 rev）；apply 逐 DM 调 `sd.RunDecision`、非原子、返回每 DM 状态/轨迹；与 GUI run 共享并发锁（建议引入 `DecisionRunRegistry` 或等价单飞锁）。
- `sd.AdjudicateTick`：补 dryRun/preview 或独立 preview 工具；禁止同一 tick 重复落 `EFFECTIVE` 记录（先 void 再裁或显式拒绝）。
- `simos.advance`：接线 `Shell.advanceAndDrain`，确保 sd effect 队列在回合边界被处理。

### P8 数值风险与假旋钮
- `GovDaily` 粮耗：与 `EconomyVocabulary` 的 per-capita/per-cycle 定义对拍；修正 120× 或改默认 policy，二者选一。
- `unit.SetFormationOffset`：已不影响计算 → 删除、具名拒绝或降级为纯记账（需在描述里诚实标注）。
- `sd.AddCombatStage` 非首阶段静默忽略参数 → 具名拒或明确 merge 语义。
- class-first 低人口硬失败：`EconomySeeder.planClassFirst` 对“有地无家户”的情况给具名前置检查，避免 P1/P2 一键落盘时才炸。

### P9 后端端到端验收
用一个**真空白 Region**（例如任意噪声 Region 或新增 test world）做纯 MCP 验收：
1. `simos.region.seed` 初始化人口/批次/城市（可选 economy/actor）；
2. `simos.city.generate` 与 `simos.province.divide` 各跑建议与一键落盘（干净区成功；脏区具名拒）；
3. Agent 手动用 GM 工具建中央 + 省 GOV、jurisdiction、决策人；
4. `simos.unit.spawnArmy` 按格建军；
5. `dueOnly` 查 due → `run-due` 派工（含审批）；
6. `adjudicate` 裁决、改地图、`advance`；
7. 断言 revision 数、地图/单位/人口/经济守恒、N9 白名单生效、无半写与无静默 no-op。

---

## 3. 测试策略（统一在最后）

- 每个批次先由写代码代理只编译；测试代理按批次判据统一补。
- 新增测试类别：
  1. generator 纯函数确定性（同输入同输出、换插入序同输出）；
  2. clean gate 命中矩阵（各相关元素各一条、命中即拒且零 revision）；
  3. 一键落盘批原子（成功恰一条 revision；批内任一拒零 revision）；
  4. Unit 政府流程顺序（中央先于省、SetJurisdiction 必须存在、level/superior 校验）；
  5. 直接建军原子与两条语义分立；
  6. allowedTools 生效（空/子集/未知项/GM 不误伤/工具面与权限组同源）；
  7. 回合闭环（due、run-due、审批、adjudicate、advance drain、失败停点）；
  8. MCP 逐 GM 工具可达性（tools/list 与源码双向对齐、读/写最小调用、preview 零 revision）；
  9. 数值对拍（GovDaily 粮耗、class-first 低人口）。
- 变异自证仅四类：守恒式 · 不丢失 · 静默付 0 · 断粮（照既有纪律）。

---

## 4. 风险与对策

| 风险 | 对策 |
|---|---|
| 一键落盘范围过大/命令数过多导致 MCP 报文超限 | 先量测；N 大时只分片组批，落盘仍要求单 revision；必要时设 N 上限 |
| 真档 Region 互相重叠导致省份 clean gate 恒拒 | §6 裁定“相关元素”定义；建议只拒完全落在父 Region 内的子 Region |
| class-first 低人口种子硬失败 | P1/P2 前置最小人口检查；economy/actor 默认关，显式开启 |
| 决策人回合卡人工审批 | P7 审批 MCP 化 + run-due 串行；每一步失败停点可重试 |
| allowedTools 修复破坏旧档/夹具 | §6 裁定空名单语义；优先“空=全局 WHITELIST”兼容 |
| GovDaily 粮耗 120× | P8 数值对拍后再改，避免按推断直接改坏旧档 |
| MCP 工具越加越乱 | P0 的 tools/list 双向对齐 + 逐工具可达性测试作为硬门 |

---

## 5. 非目标（本轮不做）

- GUI 页面、前端模式、面板设计。
- 跨 Region 市场/运输、逐格市场恢复、市场结算重接线。
- 新商品、新生产公式。
- MCP 协议级 batch/Tasks 作为第二写入口。
- 决策人显式行动名册世界状态（MVP 先用 due + run-due）。

---

## 6. 需用户确认的裁定项（实施前）

1. **城市 clean gate**：人口序列 0 值 / 空批次 / 空账是否算“已有元素”？（建议：存在记录即拒，含 0/空。）
2. **省份 clean gate 的“相关元素”**：
   - A 任何相交 Region（父自身除外）即拒；
   - B 只拒完全落在目标 Region 内的子 Region + GOV/DM/jurisdiction（推荐）；
   - C 列出相交、要求显式 override。
3. **城市一键落盘默认范围**：A 只 social（推荐）；B social+economy+actor。
4. **省份一键落盘是否同时建 N+1 决策人**：建议是，可关闭。
5. **中央 GOV jurisdiction**：建议空 `regions:[]`；若需首都行政，单独建首都圈 Region。
6. **直接建军**：允许无人口/无国库（GM 特权）？`masterGov` 只写 sd 还是双边？建议允许、只写 sd。
7. **allowedTools 空名单语义**：A 空=全局 WHITELIST（推荐兼容）；B 空=无工具（严格 N9）。
8. **“本 tick 必须行动”**：MVP 只读 due + `run-due`（推荐）；显式名册后续。
9. **审批 MCP 化**：允许外部 GM MCP 口 list/decide 审批队列吗？建议允许（GM-only、审计留痕）。
10. **preview 标准化范围**：只对生成器/初始化/组合工具强制 preview（推荐），primitive 窄写保持 apply-only + expectedRevision。
11. **假旋钮处理**：`SetFormationOffset` 与 `SdAddCombatStage` 静默参数：删除 / 具名拒 / 显式记账？
12. **GovDaily 粮耗 120× 风险**：是否纳入本轮 P8？
