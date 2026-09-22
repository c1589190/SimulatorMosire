# 下一阶段开发计划：工具面标准化

> 宗旨见 `2026-09-22-tool-surface-creed.md`（**先读它**）。本计划是它的落地分解。
> 一句话：**把领域数据标准化为工具；一份 `AgentTool` 定义 ⇒ GM 口与 MCP 口同时可用。**

## 〇. 通则

- **同源**：`SimosToolSource` 按 `Role` 分桶（`SimosToolSource.java:47-64`）；**加进桶 = 面对所有 MCP 自动出现**，不为 MCP 另做一套
- **前置即错**：缺前置 ⇒ **报错 + 可读理由**，不静默兜底（用户原话：`CreateArmy`「有对应 Nation/Army 就正常，没有就报错」）
- **连带改动（每加一条命令/工具都必须）**：`CatalogTool.PAYLOAD_HINTS`（缺项**构造期抛**）+ `McpCoverageTest` 双向载荷断言 + catalog 集合相等
- **门禁**：`./mvnw clean verify` rc=0；模块判据 **`SUCCESS \[`**（显示名 `UtilSimos`/`SDSimos`/`SimosApp`）/ 8/8；`[ERROR]` 0；`BugInstance size is 0`；前端下界（**现 201**）两处同改；★ 基线**现场重算**（只取模块汇总行）
- **变异九道门禁**；★ 每条新护栏都要**能被杀掉**；改既有文件 ⇒ 裁定 42 重跑既有变异轮
- **密钥**：★ **一个 key 都不许进代码**（只许 `config/llm-providers.json`）
- ★ **不许 `pkill -f '<含端口/store 名的串>'`**；★ **实例用 jar 时不许重编**（已崩两次）

## 一. 差距普查（现状，实测）

| 面 | 应有 | 现有工具 | 缺口 |
|---|---|---|---|
| **map 写** | `SetTerrain` · `SetEdge` · `CreateRegion` · `UpdateRegion` · `DeleteRegion` · `RandomizeRegion` · `RegisterPathwayGroup`（**7**） | **0** | **7** |
| **unit 写** | `PlaceAt` · `PlanRoute` · `PlanSparseRoute` · `CancelRoute` · `CreateUnit` · `DisbandUnit` · `RenameUnit` · `SetStrength` · `SetStatus` · `ApplyCasualties` · `AttachUnit` · `DetachUnit` · `ReparentUnit` · `ReparentSubtree` · `SetFormationOffset` · `SplitFormation` · `MergeFormation` · `SetRejoinTarget` · `CreateCommandChain` · `UpdateCommandChain`（**20**） | **0** | **20** |
| **sd 造世界/战斗** | `CreateNation` · `CreateArmy` · `CreateDecisionMaker` · `CreateCombat` · `AddCombatStage` · `SetStageOutcomeTable` · `CommitCombatOutcome` · `RecordCasualties` · `RegisterEffect` · `CancelEffect` · `PutInfo` · `SetDecisionMakerProvider`（**12**） | **0**（★ 只有 4 条**判决向**窄写：`IssueDirective`/`SetViewScope`/`StartDecision`/`SubmitVerdict`） | **12** |
| **读口** | GUI **23** 个端点 | **9** 条读工具 | 见 §二 M4 |

★ 现状：**几乎所有写命令只能经 `simos.command.submit`（通用写）** ⇒ 与 N9 的意图相悖（但 D2 已裁"保留通用写"，故为**并存**）。

## 二. 任务（按组；组内可并行，组间视文件冲突串行）

### M1 — 地图组（7 条写）
- `map.SetTerrain` · `map.SetEdge` · `map.CreateRegion` · `map.UpdateRegion` · `map.DeleteRegion` · `map.RandomizeRegion` · `map.RegisterPathwayGroup`
- ★ **归 GM 桶**（造/改世界是 GM 级权限）
- ★ 前置即错：如 `SetEdge` 的 `kind` 未注册 ⇒ 报错；`DeleteRegion` 被守卫拦 ⇒ 报可读理由

### M2 — Unit 组（20 条写）
- ★ **归 GM 桶**（★ 待裁 D-1：是否也给决策人？倾向**不给**）
- ★ 前置即错：如 `AttachUnit` 目标不存在 ⇒ 报错；`DisbandUnit` 在链中 ⇒ 报错（已有守卫，要有可读理由）

### M3 — sd 造世界/战斗组（12 条）
- ★ **归 GM 桶**（`CreateNation`/`CreateArmy`/`CreateDecisionMaker`/`CreateCombat`/…）
- ★★ **用户裁定**：`CreateArmy` **有对应 Nation/Army 就正常，没有就报错**
- ★ `SetDecisionMakerProvider` 是否也开给 GM？（倾向**开**——配 provider 是 GM 职责）

### M4 — 读口补齐（面向"所有数据"）
- 现有 9 条读工具 vs GUI 23 端点 ⇒ 逐条列出**缺哪些**（如 `/api/sd/decision-makers`、`/api/sd/verdicts`、`/api/map/region/{id}`、`/api/map/path`、`/api/unit/{id}`、`/api/llm/providers`、`/api/gm/tool-usage` …）
- ★ 判定哪些**该有读工具**（★ 与"GM 能看什么"一致）、哪些**只是 GUI 内部用**（不给工具）
- ★ 读工具是**四桶共享**（现有 9 条即如此）⇒ 加读工具会牵动所有桶的既有断言，**先把代价核清**
  —— ★ **本行原文写"三桶"，控制器 2026-09-22 按代码回填为"四桶"**：`Role` 有 **4** 个值
  （`EXTERNAL`/`GM`/`DECISION_AGENT`/`EXTERNAL_WITH_GM`），`SimosToolSource.java:117` 的类内 javadoc
  原文即「**读工具四桶共享；写面各自不同**」。⇒ 连带后果（**比"三桶"严重**）：
  **加任何读工具，对外的 `EXTERNAL` 口也同时拿到** ⇒ M4 的真实含义是"凡持有任何一个口的人都拿到"，
  而不是"给 GM 开个便利"。详见 `pending-rulings.md` §二 的 ★★ 与风险表。

### M5 — 通用写收窄评估（★ 只评估，不改）
- 标准化覆盖够之后，**通用写是否收窄**？⇒ **列为开口项**，出一份评估（受益 vs 破坏 D2 取舍）
- ✅ **评估已出（控制器 2026-09-22，零代码改动）**：`.superpowers/sdd/2026-09-22-tool-surface/m5-evaluation.md`。
  实测要点三条：① 工具面上的"通用写"**只有 `simos.command.submit` 一条**（`advance`/`fork` 类型写死）；
  ② **43 个生产命令类型 ↔ 43 条同名窄写工具**（`comm` 实测双射）⇒ 收窄**零命令类型损失**；
  ③ ★★ **只收窄工具面 ≠ 收窄写权限**——`POST /api/command`（`GuiServer.java:498`→`:782`，
  14 行方法体内**无任何审批调用**）是**第二条、且任意 `type`** 的写入口。
  ★ 权威挂钩：spec §八.3（`2026-09-20-sd-simos-design.md:478`）原文即把外部 MCP 桶写成
  「**保留现状或另行收敛，列为挂起**」⇒ **D-4 就是兑现这句「另行收敛」**。
  **要不要动，等用户裁**（三档见 `pending-rulings.md` §五；**控制器未替选**）。

## 三. 每条工具的**统一做法**（模板）

```
1. 照现成窄写形制实现 AgentTool（AbstractNarrowWriteTool 那一族）
2. 前置校验：缺前置 ⇒ 抛可读理由（不静默）
3. 进对应 Role 桶（SimosToolSource）
4. 连带：CatalogTool.PAYLOAD_HINTS 补一条 + McpCoverageTest 补双向载荷
5. 判据 + 变异（至少一条能杀掉"前置被去掉/静默兜底"的变异）
6. 证据落位 + 报告（含 §我未能核实的）
```

## 四. 待裁

| # | 问题 | 倾向 |
|---|---|---|
| **D-1** | unit 20 条是否也给**决策人**桶？ | **不给**（决策人只出令） |
| **D-2** | `SetDecisionMakerProvider` 给 GM？ | **给** |
| **D-3** | 读口补到多全（全部 23 端点 or 只补"GM 该看的"）？ | 先列清单再定 |
| **D-4** | 通用写收窄？（M5） | 只评估 |
| **D-5** | **导入器 tag 改 `nation:<名>`**（去掉每次建国家绕两步的坑）—— 是否并入本阶段 | **并入**（M3 的前置消除） |

## 五. 历史遗留（顺带收口）

- ★ `nation:` tag 契约缺口（导入器 vs `NationTag.PREFIX`）⇒ 用户已倾向"改导入器"
  —— ★★ **已升级为待用户裁定项 D-5**，见 SDD 台账 §三 与 `pending-rulings.md` §一（本阶段**不动**）
- ★ 前端 `expectedRevision` 过期 ⇒ **409**（是否已由 `661ac88` 修到"点一次就成"待复核）
  —— ✅ **已复核（控制器 2026-09-22 只读实测）**：**是**，且句子里的「点一次就成」现在就有断言钉着。
  **范围（如实记）**：`661ac88` 的收口是 **HTTP 层等价物**——真发那条 `POST /api/sd/start-decision`
  （实测 `expectedRevision=10` 过期 ⇒ `409 {"result":"conflict","current":{"revision":11}}`，日志
  `2026-09-22-sd-start-decision-fix/e2e/e2e.sh`），前端行为由**纯函数/替身**断言覆盖；
  **真浏览器点过没有 = 没有**（本机 Chromium 与 Playwright 版本不匹配，是本仓长期遗留）。
  今日字节上的锚点：`simos-app/src/test/js/decision-mode.test.cjs:327`
  `start-decision-retries-once-after-409-with-the-fresh-head`（`:328` 注释逐字含「点一次就成」、
  `:342` `assert.equal(h.calls.startDecision.length, 2, "409 ⇒ 恰自动重试一次")`、
  `:345` `assert.ok(h.calls.refreshState >= 1, "409 ⇒ 必须重取 head")`）+
  同文件「重试轮再 409 ⇒ 如实报失败、不得无限循环」那条。
  生产侧四处 409 分支在现役前端：`app.js:400`、`timeline.js:604`、`panels.js:1019`、`panels.js:1554`。
  ⇒ **本行收口：不再需要本阶段处理**（除上述"真浏览器未点"这一条**继承的**遗留，归入本仓 Playwright/Chromium 开口项）。
