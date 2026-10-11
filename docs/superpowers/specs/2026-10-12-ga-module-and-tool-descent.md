# `simos-ga`（推演框架）+ `simos-ga-spi`（工具契约）+ 工具面下放：约束设计书

> **性质**：约束设计书（供派单实现，AGENTS §一.8 / §一.8.1）。**追加式**；与既有文档冲突处以本文为准（§四.1）。
> **状态**：**已获用户裁定**（2026-10-12 夜间），可据此派实现 Agent。

---

## 1. 用户原话附录（逐字照录，不改写、不缩写、不意译）

**2026-10-12（夜间，本主题的裁定序列；按时间顺序）**：

> ① 「**我认为应该把各个工具的实现都下放到各个具体的功能模块，然后决策人包在哪？**」
>
> ② 「**我的方案是把所有和决策人、判定Agent相关的功能全部移到一个新包，就叫gameagent吧，
> 旗下实现标准的决策人流程和MCP工具（给dm）然后把现有的各个工具实现下放到各个功能模块，
> 如果要复写决策人，那直接调我上面说的这个包，app只负责组装**」
>
> ③ 「**gm工具啊……理论上来说，gm工具的功能实际上和dm没啥区别啊，dm（决策人）用的时候都得过gm审批工具的审批，
> gm则是要用直接用；我建议直接改造sd模块，扩大其语义为整个决策人-推演人（dm-gm）的总实现，
> 和其他功能模块实现相关工具的接口，有感觉吗**」
>
> ④ 「**不是不是，为啥其他模块要写工具不能直接依赖ga这一整个模块啊？还是必须得写个ga-spi才满意**」
>
> ⑤ 「**那整个ga-spi吧，其中实现工具实现的各类标签，先把工具运作、决策人运行放到ga模块，
> sd直接修改，原地扩充，就是3我不理解，什么工具必须大动干戈到放到app**」
>
> ⑥ 「**这些按照正常模块流程留app，其他的放到各个模块分别实现，我要睡了，你自己一步一步做，不需要我确认**」

**控制方的读解（非用户原话；用户可推翻）**：

| # | 冻结口径 | 依据 |
|---|---|---|
| **G-1** | 新建 **`simos-ga-spi`**：装**工具实现的各类标签与契约**（工具接口、角色标签、GM-only 标签） | 原话 ⑤ |
| **G-2** | 新建/落 **`simos-ga`**：**工具运作**（工具注册与装配、审批链、执行桥）+ **决策人运行**（LLM 回合、裁决、决策包执行） | 原话 ⑤ |
| **G-3** | `simos-sd` **原地扩语义**（不新建 `gameagent` 包、不迁包名）：sd = **决策人-推演人（dm-gm）的总实现** | 原话 ③⑤ |
| **G-4** | **工具实现下放到各功能模块**；GM 与 DM 用**同一批工具**，差别只在**审批链**（GM 直接用、DM 过审批） | 原话 ①②③ |
| **G-5** | **违反既有禁令的"正常模块流程性"工具留 app**：创世/播种/清空三件套（`WorldgenInitialize` / `RegionSeed` / `RegionClearData` + 各 `*Plan`） | 原话 ⑥ |
| **G-6** | 其余工具**按域归属**分别落各模块（归属表见 §4.3） | 原话 ⑥ |
| **G-7** | **app 只负责组装** | 原话 ② |

★ **术语**：用户口语里的 "gameagent/ga" = 本设计书的 `simos-ga`（模块名取 `simos-ga`，与 "ga-spi" 对称）。

---

## 2. 目标 / 非目标

**目标**
1. **一处声明"这个工具能被谁用"**：消灭今天"GM 桶 / 决策人桶 / 白名单"三处手抄（`SimosToolSource.java:890-895` 自述"只改一处 = 留旁路"）。
2. **工具实现回到它自己的域**：领域模块自带工具（今天领域模块 `*Tool.java` = **0 个**）。
3. **运行框架与领域解耦**：`ga` 只做"怎么跑"，不知道"有哪些域"。
4. `sd` 成为 dm-gm 总实现，app 退化为装配。

**非目标**
- **不**改任何工具的功能语义、载荷形状、拒绝语义、数值算式。
- **不**改状态形状（本批无持久状态变更）⇒ 不涉旧档兼容（§一.11 本就不考虑）。
- **不**做 GUI/webui 外观改动。

---

## 3. 现状事实（`file:line`，2026-10-12 于 `1630104e` 核实）

| # | 事实 | 证据 |
|---|---|---|
| F1 | 工具实现全在 app：`tools/**` **207 个 `.java` / 176 个工具类**（read 39 + write 137）；领域模块 `*Tool.java` = **0** | `find` 实测 |
| F2 | 注册唯一出处 = `SimosToolSource.java`（150 处 `built.add`），两桶 GM / DECISION_AGENT | `SimosToolSource.java:459-505/532-872/874-927` |
| F3 | **同一工具在 GM 与 DM 各注册一次**：`GovSetEstablishmentTool` 在 `:677` 与 `:894`（同类、同参数） | 同上；注释 `:890-895` 自述"只改一处 = 留旁路" |
| F4 | 第三条手抄：`DecisionCallerFactory.WHITELIST` 44 条工具名 | `access/DecisionCallerFactory.java:115-192` |
| F5 | **162 个工具类直连 `CoreSimos`**；实际只用到 5 个方法：`submit` 17 处 / `submitBatch` 26 处 / `head` 12 处 / `submitRestore` 1 处 / `revisions` 1 处 | `grep` 实测 |
| F6 | `CoreSimos` 公开 API：`submit(Command)`、`submitBatch(List<CommandEnvelope>)`、`submitRestore(...)`、`head(BranchId)`、`revisions(BranchId)` | `CoreSimos.java:211/232/247/402/428` |
| F7 | `CommandResult` = sealed：`Committed(StateRef)` / `Rejected(String)` / `Conflict(StateRef)` | `core/command/CommandResult.java:13-34` |
| F8 | `ToolSupport`（959 行）是**三合一上帝类**：资源/schema 助手 + 可见性谓词（依赖 `QueryService`/`ApiViews`）+ 领域读取；`import` 覆盖 core + 全部领域 + app | `tools/ToolSupport.java` |
| F9 | 标签现居：`GmOnlyRead` 在 **app**（`tools/GmOnlyRead.java`）、`GmOnlyCommand` 在 **util**（`util/spi/GmOnlyCommand.java`，53 个 handler 实现） | 实测 |
| F10 | **`agentlib-mosire` 不依赖 simos**（`grep io.mosire.simos` = 0），但自带 `sqlite-jdbc` / `pf4j` / `mcp-core` / `mcp-json-jackson2` / `json-schema-validator` | agentlib `pom.xml` + 源码实测 |
| F11 | 决策人相关代码分布在 **2 个模块**：`sd` 31 个文件（域状态/ID/codec/resolver/10 个 `sd.*` handler）、`app` 55 个文件（`decision/` 9 + `access/` 8 + `tools/` 35 + query/gui/llm/gov 4） | 实测 |
| F12 | `app/decision` 的领域 import：`sd` 22、`map` 5、`social` 4、`core` 10；`app/access`：`sd` 22、`map` 22、`unit` 13、`army` 1 | 实测 |
| F13 | 角色分离**已按"链"而非"工具"实现**：GM 面挂 `GmAutoApproveGate`（无脑过），DM 面挂 `AutoApproveGate → ConfirmGate → PendingApprovals` | `access/GmAutoApproveGate.java` 类注（用户 2026-09-24 原话「为啥这种 GM 级命令要额外审批？改成 MCP/GM Agent 无脑过」） |
| F14 | 创世/播种/清空三件套确为**跨全部切片**：`RegionSeedTool` 写 6 个命名空间（map/social/economy/actor/sd + branch）、`RegionClearDataTool` 同；`WorldgenInitializeTool` 聚合全部 | 三者的 `ResourceManifest` 实测 |

---

## 4. 目标架构（冻结）

### 4.1 模块依赖（新增两块，**无环**）

```
util ─┬─ ga-spi ──── (agentlib-mosire)        ← 工具契约层：工具接口 + 角色标签 + GM-only 标签 + 窄写口
      │      ↑
      │      └── 各领域模块的工具实现依赖它（不依赖 ga！）
      ├─ map ── calendar / social-api
      ├─ social-api / social / unit / …
      ├─ ga ──── { 工具运作 + 决策人运行 }，依赖 core + sd + util + ga-spi + agentlib
      └─ core ── 组合与调度（main 只 util + agentlib）
      sd ──── 原地扩语义：dm-gm 总实现（决策流程 + 权限范围 + 两个工具面）
      app ── 组合根：装配 + GUI + 创世/播种/清空三件套
```

★ **为什么域模块依赖 `ga-spi` 而不是 `ga`**（用户原话 ④ 的答案）：`ga` 必须依赖各域（它要跑回合、读状态），
域再依赖 `ga` ⇒ **环**，Maven 建图期直接拒。契约层是唯一出路 —— 与既有 `actor-api`/`economy-api`/`social-api`
**同一形制**（本仓已有三块这种"API 模块"）。

★ **为什么不让域模块直连 `agentlib`**（用户选 `ga-spi` 的理由）：域模块的依赖树会凭空多出
`sqlite-jdbc` / `pf4j` / MCP SDK（F10），与"领域模块不碰存储 / 不认识编排"的既有纪律直接冲突。

### 4.2 `simos-ga-spi` 的内容（**只放契约，零逻辑**）

| 内容 | 来源 | 说明 |
|---|---|---|
| `SimosTool`（工具接口） | 新 | 从 `AgentTool` 收窄 + 本仓需要的声明面 |
| `ToolRole`（角色标签） | 新 | `{GM}` / `{DM}` / `{GM, DM}` —— **"谁能用"的唯一拼写点** |
| `GmOnlyRead` / `GmOnlyCommand` 的**标签面** | app `tools/GmOnlyRead.java` + util `spi/GmOnlyCommand.java` | 迁到本模块（`GmOnlyCommand` 从 util 迁出，util 保持零依赖） |
| `ToolWritePort`（窄写口） | 新 | 替代 162 个工具直连 `CoreSimos`：只暴露 F6 那 5 个方法的**最小形态** |
| `ToolResource` / `ToolSchema`（资源与 schema 助手） | 从 `ToolSupport` 拆出 | 纯函数，只依赖 util + agentlib |

★ **`ga-spi` 不依赖 `core`**：窄写口用 `(String type, String payloadJson)` + 自己的结果类型表达，
不引 `CommandEnvelope`/`CommandResult`（**背离记录**：控制方初判"ga-spi → core 可行"，实测 `core` 的 test scope
依赖 `map`/`social`/`unit`（F1 的既有口径）⇒ `map → ga-spi → core → map(test)` 会成环。**定稿 = 不依赖 core**，
由 app 注入实现）。

### 4.3 工具归属表（用户原话 ⑥："按正常模块流程留 app，其他的放到各个模块分别实现"）

| 归属 | 工具（按现有类名） |
|---|---|
| **app（留）** | `WorldgenInitializeTool` · `RegionSeedTool` · `RegionClearDataTool`（+ `RegionSeedPlan` / `RegionClearPlan` / 各自 `*Plan`）—— F14 证明它们跨全部切片 |
| **`simos-unit`** | 全部 `Unit*`（32 个）· `LevyRegionTool` · `RaiseUnitTool` · `SpawnArmyTool` |
| **`simos-gov`** | 全部 `Gov*`（30 个）· `ProvinceApplyTool` / `ProvinceAssignCitiesTool` / `ProvinceDivideTool` · `RegionClearStructuresTool` |
| **`simos-army`** | `ArmyRecordCombatTool` · `StartCombatTool` · `ResolveCombatTool` · `FormatUnitTool` · `AssignArmyGovTool` · `GmArmyPayPolicyTool` |
| **`simos-economy`** | 全部 `Economy*`（13 个）· `GmPeriodicAdjustmentTool` · `GmGovMarketMandateTool` |
| **`simos-social`** | 全部 `Social*`（8 个）· `GmVitalRatesTool` · `GmAdjustPopulationTool` · `UnitDetachHouseholdTool` / `UnitAssignHouseholdTool`（家户归属走 social 工单） |
| **`simos-map`** | 全部 `Map*`（15 个）· `RegionClearStructuresTool` 的 map 只读腿（若需） |
| **`simos-ga`** | 审批与决策包族：`GmApproveTool` · `GmApprovalsTool` · `GmPacketTool` · `GmPacketsTool` · `GmDecidePacketTool` · `GmMergedPlanUpsertTool` · `GmMergedPlanApplyTool` · `GmPacketExecuteTool` · `GmToolUsageTool` · 全部 `Sd*`（20 个）· `Decision*`（5 个）· `IssueDirective` / `SubmitVerdict` / `RejectDirective` / `VoidAdjudication` / `AdjudicateTick` / `RunDecision(Makers)` / `StartDecision` / `SetDecisionMakerAccess` / `ResetDecisionMakerConversation` / `ProposeCall` / `SubmitPacket` / `PacketIntent` / `MyPacket` / `SimosSdReport(s)` |
| **`simos-sd`** | 决策**域状态**与命令 handler（`DecisionMaker` / `Directive` / `DecisionPacket` / `MergedEffectPlan` + 10 个 `sd.*` handler）；`ga` 挂它的状态 |

★ **"跨模块工单不算越界"**：`Levy/Raise/Spawn` 要动 `social` 切片，但走的是
`social.SubmitHouseholdWorkOrder`（**社会是人口的唯一权威**这条既有裁定）⇒ 它们**属于 unit**。

### 4.4 一处声明、三处派生（消灭 F3/F4）

```java
enum ToolRole { GM, DM }
interface SimosTool extends AgentTool {
  Set<ToolRole> roles();          // 唯一拼写点
  default boolean exported() { … } // 原 GmOnlyRead 的语义面
}
```
派生关系（**不再手抄**）：
1. GM 桶 = 全部 `roles().contains(GM)` 的工具；
2. DM 桶 = 全部 `roles().contains(DM)` 的工具；
3. `DecisionCallerFactory.WHITELIST` = 从桶派生（而不是手抄 44 条）；
4. 审批链仍按**装配**决定（F13 的既有口径不动）：GM 面 `GmAutoApproveGate`，DM 面 `ConfirmGate`。

### 4.5 `ga` 的内容（工具运作 + 决策人运行）

- 从 `app` 迁入：`decision/DecisionAgentRunner` / `DecisionAgentService` / `DecisionTurnFinalizer` /
  `DecisionEffectExecutor` / `ProposalCatalog` / `DecisionToolDefs` / `DecisionRunRegistry` / `DecisionProposable` / `NationOpeningSnapshot`；
- 从 `app` 迁入：`SimosToolSource`（装配两桶，改名为 `GaToolSource` 或保留名）+ `tools/` 的 ga 归属工具（§4.3）；
- **`GmAutoApproveGate` / 审批编排**：迁 `ga`（属"工具运作"）；
- **权限范围函数（`GovScope`/`NationScope`/`ArmyScope`/`DecisionScopeFunction(s)`）留在 app**：
  它们要看辖区 + 单位 + 外交（F12：`app/access` 的领域 import = sd 22 / map 22 / unit 13 / army 1）⇒
  塞进 `ga` 会让"运行框架"认识所有领域，与 G-3 的意图相反。

---

## 5. 不变量（逐条可验）

| # | 不变量 |
|---|---|
| I-G1 | **零行为变化**：工具名、载荷、拒绝码、`ToolSpec`、`ResourceManifest`、审批链语义**逐字不变** |
| I-G2 | **无环**：`ga-spi` 不依赖任何领域模块与 `core`；`ga` 不依赖 `app`；域模块不依赖 `ga` |
| I-G3 | **一处声明**：工具"能被谁用"只写在 `roles()`；桶与白名单从它派生（禁止再出现手抄的第 2/3 处） |
| I-G4 | **注册面判据不退化**：既有结构扫描守（磁盘 70 个窄写类、GM∪DM == 磁盘集合、37 条读工具逐条真调）必须继续绿 |
| I-G5 | **领域模块不碰存储/编排**：域模块新增依赖只有 `ga-spi`（+ 既有允许面），**不得**出现 `agentlib`/`core`/`ga` |
| I-G6 | 铁律 3/4 与 enforcer 闭合**不放松**：新模块进各模块禁列（按冻结文档 §4 I-F4 的口径） |
| I-G7 | `simos-app` 仍**不设** enforcer（文档明文） |

---

## 6. 批次与顺序（一批一责任区，每批一编译门禁）

| 批 | 责任区 | 可独立验收点 | 风险 |
|---|---|---|---|
| **B1** | 建 `simos-ga-spi` 模块（标签 + 工具接口 + 窄写口 + 资源/schema 助手），并把 `GmOnlyRead` 迁入；**app/util 里的旧标签留转发或同步替换** | `compile` + 新模块进父 pom + enforcer 闭合回填 | 低（纯新增） |
| **B2** | 建 `simos-ga` 模块：迁工具运作 + 决策人运行（§4.5），`SimosToolSource` 落 ga；app 只保留装配调用 | `compile` + 既有 `McpServerTest`/`SimosToolsTest` 的**工具面集合不变** | 中（跨模块搬移） |
| **B3** | 域工具下放：按 §4.3 表把 176 个工具类搬到各域（含 `ToolSupport` 三分、窄写口接线替代 `CoreSimos`） | `compile` + 各域自带的工具类取代 app 侧 | **高**（面最大） |
| **B4** | 一处声明收口：`roles()` 派生两桶 + 白名单；删掉手抄清单；跑终审 `clean verify` | 全仓 `clean verify` + 负向（新工具不声明 `roles()` ⇒ 编译期或测试期红） | 中 |

**顺序理由**：B1 是 B2/B3 的前提（契约先立）；B3 面最大且要动 162 个工具的写口，放 B2 之后（此时运行框架已独立，
搬工具不会连带搬框架）；B4 最后收口（此时才能安全删手抄清单）。

---

## 7. 验收判据

1. **工具面集合不变**（负向对照）：迁移前后 `shell.toolRegistry().list()` 的工具名集合**逐条相同**（166 条）；
2. **权限不放大**（负向）：DM 桶仍**没有**任何 unit/map 窄写；GM-only 三条路径（令 / `RegisterEffect` / 决策人 catalog）不放大；
3. **审批链不变**（负向）：GM 面写工具无审批；DM 面同名工具仍需审批（读 `pendingApprovals().pending()` 的既有判据）；
4. **无环**：`ga-spi` 的 `dependency:tree` 里不出现任何 `simos-*` 领域模块与 `core`；
5. **一处声明**：`DecisionCallerFactory.WHITELIST` 与两个桶均由 `roles()` 派生（源码扫描：不得再有工具名字面量清单）；
6. `clean verify` 全绿（15 → 17 模块）；
7. **行为零变化**：既有 3409 条测试**全绿**（本批不新增行为，只搬位置）。

---

## 8. 文件所有权

- **B1 允许写**：新建 `simos-ga-spi/**`、父 `pom.xml` 的 `<modules>`、14 个模块 pom 的 enforcer 禁列（把 `ga-spi` 从禁列除名）、
  `tools/GmOnlyRead.java`（迁出后留转发或删除）。
- **B1 禁止碰**：任何 `src/main` 的工具实现（B1 只立契约）、状态类型、算式。
- **B2/B3/B4** 的所有权在各自派单书里写死。

---

## 9. 未核项 / 待裁定（如实）

1. **`ga` 与 `simos-sd` 的边界细节**（哪些 handler 属 sd、哪些属 ga）按 §4.3 的表分工，但**未逐类核**；
   实现 Agent 若发现某类两边都要，按 §一.8 的三级处置（技术分歧 = 控制方当场裁）。
2. **`SimosToolSource` 是否改名**（`GaToolSource`）—— 控制方倾向**不改名**（少一次全仓引用改动），待实现时定。
3. **`ToolSupport` 三分的落点**（资源/schema 助手 → `ga-spi`；可见性谓词 → 留 app；领域读取 → 各域）**未逐方法核**。
4. **GUI 直调路径**（`GuiServer` 的 `/api/*` 不过工具层）**不在本设计书范围**，另议。
5. 本设计书**未跑 Maven**（只读核实 + 事实表）。
