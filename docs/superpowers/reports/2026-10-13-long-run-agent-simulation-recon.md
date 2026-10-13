# 长跑多 Agent 推演（3~5 年）目标现状勘查 · 2026-10-13

> **触发**：用户 2026-10-13 提出目标 —— 「用当前项目加载一个被做好的地图，派多个子Agent跑大概3~5年（365tick*3~5）的中世纪政治经济军事模拟，
> 这其中要求决策人Agent在打完仗等时候能自由更新自己的Skill，推演内容还要在GUI上可见」，并要求先调查目前项目的完成程度。
> **用户追加裁定**：问题先存好，之后由用户具体说想怎么搞（⇒ 本报告**只做现状与差距，不含方案定稿**）。
> **来源**：只读调查 + 一次真机冒烟（起过 small-world 实例、推进 363 tick、截过图，均已停）。**未跑 Maven**。
> **基线**：`main` @ `9c841e06`（工作树干净）。★ 调查期间我起过一个测试实例（PID 34316，GUI 5811），**已停**。
> **口径**：本文只记**回代码核实**的事实；转抄/未跑项一律标「未核」。

---

## 0. 一句话结论

目标链的四段 —— **加载地图 · 派多 Agent 决策 · 打 3~5 年 · GUI 可见** ——
**没有一段是"零基础"**，但也**没有任何一段今天能直接跑通**：

| 段 | 现状 | 判定 |
|---|---|---|
| ① 加载一张已做好的地图 | 三条路全通，实跑过 | ✅ **能做** |
| ② 时间推进 3~5 年（1095~1825 tick） | 实跑过 363 tick / 7.96 s；历史上跑到过 day 1400 | ✅ **能做**（有标定与饱和代价） |
| ③ 多个决策人 Agent 跑很多轮 | 单轮能跑（GUI 点击）；**多轮×多人的循环不存在** | ⚠️ **缺编排器** |
| ④ 决策人自由更新自己的 Skill | 只有**读**；写入口、per-agent 归属全无 | ❌ **不存在** |
| ⑤ 推演内容在 GUI 可见 | 骨架在（地图/⚔/决心理由/命令清单/经济读数），三处空白 | ⚠️ **半有** |

**另有一条今天就能证实的硬阻断**（§4）：决策人链上的每次敏感写都要**人点审批**，没人点就等到 5 分钟上限然后 DENY。
⇒ **当前状态下"无人值守跑 3~5 年"一次都跑不成**，这不是性能问题，是审批语义问题。

---

## 1. ① 地图：三条路全通（已实测一条）

### 1.1 "已做好的地图"是什么

**不是独立地图文件格式，是一个 store 目录**（SQLite + checkpoints）：

- 世界本体 = `<storeDir>/checkpoints/<branch>/<rev>.json`（`CheckpointStore.read`，`simos-core/.../store/CheckpointStore.java:100-119`）
  + `<storeDir>/simos.db`（三张表：`revisions` / `events` / `store_meta`，`SqliteStore.java:42/71/96`）。
- 信封顶层 = `{ref, timestamp, timeBase:"DAY", modules:{map,social,unit,economy,actor,gov,army,ga}, info}`。
- `--world=` **只在空库生效**（`ShellMain.java:243/258` → `CoreSimos.bootstrapGenesis` 对非空库**具名拒绝**，`CoreSimos.java:302-314`）
  ⇒ 非空库时 `--world` **完全无用**，直接开已有世界。

### 1.2 四个登记世界（`WorldRegistry.java:133-153`）

| world id | 体量 | 备注 |
|---|---|---|
| `v17levant`（缺省，`ShellConfig.java:62`） | 59,223 hex / 252 区域 | 内置资源 `simos-app/src/main/resources/worlds/v17levant.json`（4.1 M，`cities=0`） |
| `small-world` | 19 hex / 2 省级辖区 / 4,800 人 | **程序化生成**，`SmallWorld.java`（HEX_COUNT=19 @ `:149`） |
| `corridor` | 3 hex | — |
| `three-powers` | 37 hex / 三区三城三币 | ★ 历史实测 **day 540 硬红**（`sdd/2026-10-11-w2-acceptance/report.md:484`）⇒ **不宜做长跑** |

**另有两份不在本仓的世界**：`~/Simos-18Lvt/world`（76 MB，59,223 hex / 252 区域 / 210 城 / 103 单位，head 176，独立 git 仓）
与仓内副本 `test-world/store`（同源，`checkpoints/main/1.json` md5 `20d2f936…`）。

### 1.3 外部地图怎么进来

- **旧 GSimulator 地图** → `python3 tools/gsimap_import.py <input_map.json> <outDir>`，产出 `<outDir>/simos.db` + `checkpoints/main/1.json`（`tools/gsimap_import.py:8-11/965-972`）。地形映射 fail-closed。
- **任意外部地图** → 只要能产出 simos 线格式的 `checkpoints/main/1.json`，放进 store 目录**不传 `--world`** 即可。
  ★ 代码里**没有**"运行时读一个地图文件"的入口。

### 1.4 ★ 我实测的（本报告唯一一次真机跑）

```
./run-small-world.sh          # 空库 run/small-world-db → bootstrap 19 hex
POST /api/advance {"branch":"main","expectedRevision":2,"from":3,"to":363}   → 200, 7.957 s
→ /api/state  tick=363  date=julian 1445-12-30
→ 落盘：revisions=3 行 / events=8 行 / store 14 MB
```
GUI 截图（真浏览器 headless）：19 格六边形地图 + 世界总览（区域 2 / 城市 2 / 城市人口 963 / 农村 2,908 /
粮 44,533,075 / 银 268,700 / 单位 2 / GOV 2 / **决策人 0**）+ 底栏「推演 1 天 / 推进 N 天 / 分叉」+ 时间轴（1445-01-01 → 1445-12-30）。

---

## 2. ② 时间推进：能做，但有三处要记账

- **1 tick = 1 天**（`CalendarClock.java:13`），锚点儒略历 `1445-01-01`（`CalendarDefaults.java:21-22`）。历法年 365/366。
  ★ **但本仓经济关账周期 = 120 天，文档里"一年末"取 tick 360**（`docs/agents/08-七-门禁与关账命令.md:36-40`）
  ⇒ 3~5 年 = **1095~1825 tick（按历法年）** 或 **1080~1800 tick（按 3×120 周期）**，两者要挑一个口径。
- **单次推进上限 36,500 天**（`TimeAdvance.java:96`），内部逐日结算、只落 1 条 revision。
- **性能实测**（历史台账 + 我本轮）：0→360 单次 **7.96 s**（我）/ 10.65 s（台账）；0→1000 单次 **15.37 s**；
  **逐日**推进 400 天（1000→1400）= **152.6 s**。★ 1825 天的单次耗时**未实测**（线性外推约 700 s 未验）。
- **体量**：day 1000–1400 每条 revision 的 `changesetBytes` 已达 **250–520 KB/天** ⇒ 1825 天约 **0.5–0.9 GB** 变更集 + checkpoint。
- **三处代价（不崩但读数失真）**：
  1. **算术饱和**：day 690 的债务估值整数溢出曾是**具名硬停**（`sdd/2026-10-11-haul-supply-investigation/report.md:377`），
     W8 改成饱和（`SaturatedArithmetic`）后能跑到 1400+，但 **day 1400 累计 5.6 万次饱和**（`w8-debt-semantics/impl-ledger.md:133`）
     ⇒ 越界后**经济读数不再可信**。
  2. **19 hex 世界 360 tick 标定问题**：税 **100% 成行政损耗**（`GovEfficiency.of` 的 YAMEN=0 口径）、**GOV 国库全空**、
     军俸 3 周期全 `no-payable-leg` 跳过、铸币政府与运行期 GOV 无财政连接
     （`docs/superpowers/reports/2026-10-23-sw19-economy-360tick-vs-p2.md:41-53`）。
  3. **长跑没有回归护栏**：真实世界长跑测试已由用户裁定**移出程序化门禁**（删了 6 个长跑测试类，
     `docs/agents/08-七-门禁与关账命令.md:13-17`）⇒ 长跑只能手工驱动。

---

## 3. ③ 多 Agent 决策：单轮齐备，多轮循环缺失

### 3.1 齐备的那一半

- **决策人实体**：`ga.model.DecisionMaker` = `id / allowedTools / accessLimit / decisionCadenceTicks / providerId / conversationGeneration`
  （`DecisionMaker.java:45-51`）。创建工具 `ga.CreateDecisionMaker`（`SdCreateDecisionMakerTool.java:24`）。
- **单轮真跑**：`DecisionAgentService.runRound`（`DecisionAgentService.java:179/191/205/222`）→ `DecisionAgentRunner`（`simos-ga`），
  模型调用点 `DecisionAgentRunner.java:971`；回合上限 `DEFAULT_MAX_LLM_CALLS = 20`（`:153`）。
- **三个触发入口**：GUI `POST /api/sd/run-decision`（`GuiServer.java:199/1794`，**后台虚拟线程、立即返回**，进度靠 `run-status` 轮询）；
  命令 `ga.RunDecision`；GM 批量口 `simos.sd.run-decision-makers`（`RunDecisionMakersTool.java`）。
- **多人同 tick**：`FrozenDecisionContext`（`DecisionAgentService.java:205`）——世界冻结、世界写工具移出工具面、各写自己的 packet。
- **结算链完整**：回合末 `DecisionTurnFinalizer.java:258 core.submitBatch`（3 条命令同批 = 1 条 revision）
  → GM `simos.gm.packet.decide`（`GmDecidePacketTool.java:55`）→ `simos.gm.packet.execute`（`GmPacketExecuteTool.java:53`）
  → `DecisionEffectExecutor.java:319 core.submitBatch`。执行身份 SYSTEM+unrestricted，initiator=GM，**proposer 不冒充执行者**。
- **LLM provider 可用**：我实测 `config/llm-providers.json` 的 `http://121.40.130.178:3000/v1/models` 回 **200**
  （model `deepseek-flash`，`toolCalling: true`）。fail-closed，无兜底（`LlmProviderResolver.java:34/37`）；**生产无 stub/scripted 模式**。

### 3.2 缺的那一半 ★

1. **没有自动 per-tick / per-N-tick 决策循环**。`DecisionDueRunner` 全仓 **0 命中**（survey #3 得到复核）。
   批量口 `RunDecisionMakersTool` **明确不按 due 自动派**（其类注逐字：「`due` 只在 preview 里作**只读展示**，明确不参与选择；
   本工具只按调用方显式给的名单行动」）。
   ⇒ **3~5 年 × 多决策人的循环必须外部自己写**（今天只能：GUI 手点 / MCP 逐个调 / 自己写 HTTP 驱动脚本）。
2. **`Shell.advanceAndDrain` 在生产路径上没有调用点**。只有 5 个测试类调它；`/api/advance`（`GuiServer.java:1703`）与
   MCP `simos.advance`（`AdvanceTool.java:123`）**都是直接 `core.submit`** ⇒ `SdCommandDrain.drainAfterAdvance` 永不触发 ⇒
   **跨模块排队效果不会落地**（`GaTimeParticipant` 明写 `Action.EnqueueUnitCommand` 交 `SdCommandDrain` 处理）。
   ★ 未判这是 bug 还是 sd 退役迁移的残留（`SdCommandDrain` 类注仍自述"在一次 AdvanceTime 提交成功之后"执行，与事实矛盾）。
3. **survey #59 比记录更严重**：`ProposalCatalog` 的 7 个可提议工具（`ProposalCatalog.java:131-160`）**全部是纯 GM 工具**，
   而 DM 开场消息【本轮可 propose 的工具】正是这 7 条（`DecisionAgentRunner.java:753-757`）
   ⇒ **DM 被明确引导去提议它无权执行的东西**，只能靠 GM 审批 + SYSTEM 上下文兜。
4. **DM 可见工具 43 条，清单只住在测试里**（`simos-app/src/test/.../access/DecisionFaceGolden.java:34`），生产零清单；
   装配期由 `ToolRoleAudit.requireFaces` 双向钉死（`Shell.java:1194`）。

---

## 4. ★★ 4. 审批：**无人值守跑不通的硬阻断**（今天可证实）

**决策人链的闸链 = `[AutoApproveGate, ConfirmGate]`**（`Shell.java:1079-1084`），`APPROVAL_TIMEOUT = 5 分钟`（`:287`）。

- `ConfirmGate` 源码**恒 `Optional.empty()`**（`~/ProjectMosire/AgentLibMosire/.../approval/ConfirmGate.java:22-25`），
  类注逐字：「永远 {@code empty} = 交给后面的人（编排器去问人）」「{@code empty} **不是**放行」。
- `AutoApproveGate` 只在**本进程内**已有 `(callerKey, classKey)` 会话级放行时返回 `APPROVE_SESSION`，否则 `empty`
  （`AutoApproveGate.java:31-36`）。而 `sessionGrantable` 缺省**只认 `"SYSTEM"`**（`ApprovalCoordinator.java:69`）
  ⇒ **DM 每次都要问人**。
- `HttpApprovalChannel` 端口已 `markUp()`（`Shell.java:1104`）⇒ 通道**可用** ⇒ 不会走"无通道立即 DENY"那条
  ⇒ **走 `await`**：没人答就等到 5 分钟上限然后 **DENY**（`ApprovalCoordinator` 类注第 2、4 条）。
- **哪些写会打这个闸**：`AbstractNarrowWriteTool.gate()` 恒 `new ToolGate.Ask(name(), …, AskKind.SENSITIVE)`
  （`AbstractNarrowWriteTool.java:184`）。⇒ 决策人回合里的 **`simos.sd.packet.submit`**（`SubmitPacketTool.java:103`）与
  **`simos.sd.propose`**（`ProposeCallTool.java:148`）**都在内**。
- **没有配置开关能关掉决策人审批**：`config/shell.json` 只有 `world`/`mapId`；
  GM 面另起一条链（只挂 `GmAutoApproveGate` → 恒 `APPROVE_ONCE`，`Shell.java:1088-1095` + `GmAutoApproveGate.java:48`）
  ⇒ **现成的对照件就在同一个文件里**（GM 面免审批），缺的是决策人面同等的东西。

⇒ **今天的状态**：一个决策人跑一轮，至少两次敏感写各停在 5 分钟上限然后被拒 ⇒ **一轮白烧约 10 分钟且世界不前进**。
**"无人值守跑 3~5 年"在当前代码下一次都跑不成。**

★ 与本会话的运行时状态的关系：本会话的审批策略已被置为 **"never"**（系统提示原话：approval 请求会被自动拒绝）
⇒ 上面这条阻断是**当下就会发作**的，不是理论风险。

---

## 5. ④ Skill：只有读；写那一半完全不存在

| 维度 | 事实 | file:line |
|---|---|---|
| 库本体 | `SkillLibrary`，公开面只有 `open/list/find`，**零写方法** | `simos-app/.../app/skill/SkillLibrary.java:92/114/124` |
| 两处来源 | 仓种子 `config/skills/` + store 覆盖 `<storeDir>/skills/`，**同 id store 版优先** | `:65/:94/:134` |
| 热更新 | 按 mtime 判重读 ⇒ **改文件不用重启**；坏文件保留上一版 + 记 `SKILL_FILE_PARSE_FAILED` | `:56-57/:186-204/:220-231` |
| 读工具 | `simos.skill`：不给 id 出目录、给 id 出正文；schema 只有 `id` 一个属性 | `app/tools/read/SkillTool.java:40/65/70-96` |
| 谁能用 | `roles() = {DM, GM}` | `SkillTool.java:133-135` |
| 注册/装配 | `SimosToolSource.java:1231` / `Shell.java:1112` | — |
| **不存在** | 任何写工具、任何 per-agent 技能归属、任何技能 DB 表（`SqliteStore` 只有 3 表）、GUI 技能面板（`webui` grep `skill` = 0） | 实测 |
| 不变量豁免 | 技能文件**按设计不在世界 revision 内**（与 `conversations.db`、`agentlib/` 同族）⇒ 改技能**无世界版本锚** | `SkillLibrary.java:36-38` |
| prompt 里的位置 | 只广告**工具名**一句，技能正文**从不进 prompt**；身份/规则是硬编码 Java 串（system 只有一个空格） | `DecisionAgentRunner.java:741/:835/:727-731` |
| 反面计划 | 「**不做** Brain 那套（子 agent、**技能**、压缩档位、bash 工具）」 | `docs/superpowers/specs/2026-09-22-agent-permission-design.md:158` |

**"Skill 更新"的既有口径属于操作者/GM，不属于世界内 DM**：
`docs/agents/13-十二-推演Skill文档规则.md` 的用户原话是「由于是第一次进行推演，应当创建Skill文档，在本次推演完成后总结可复用的推演守则……」，
§十二.2 说"每轮推演结束：把稳定可复用的推演守则回填通用 skill（首轮 `config/skills/simulation-runbook.md`）"。
**实体证据**：`~/Simos-18Lvt/config/skills/simulation-runbook.md`（18,739 B）+ `~/Simos-18Lvt/world/skills/turn-001-beigu.md`（14,686 B）
由**操作者手写 + git commit**（世界仓 8 个提交）。且那次推演的「决策人」**全部 `providerId=null` / `allowedTools=[]` / 无 LLM provider**
（`turn-001-beigu.md` §3.3）—— **那是人驱动 GM 的推演，不是 Agent 自动推演**。
★ 本仓 `config/skills/` 只有 4 篇全局方法论：`00-decision-basics` / `10-political-judgement` / `20-economic-judgement` / `30-military-judgement`；
`simulation-runbook.md`、`SKILL.md` 在本仓**零命中**，`.agents/skills/` 是空目录。

**用户诉求的三种可能口径（待用户裁定）**：
(a) **每个决策人有自己的技能，Agent 在推演中改自己的** —— **需要新做**（写入口 + per-DM 归属 + 触发时机）；
(b) **沿用 §十二：GM/操作者每轮总结回填 runbook** —— **机制已在**，只需在自动化里放一个总结环节；
(c) 两者都要。

**最小接缝（供后续方案用）**：`SkillLibrary` 的 `storeDir()` 旁加写方法（头部格式由 `parse():244-280` 反向给出）+
一条 DM 角色窄写工具挂 `SimosToolSource.java:1231`；per-agent 归属的先例是 `DecisionCallerFactory.java:158-173`
的"全局白名单 ∩ `dm.allowedTools()`"。文件路**天然跨重启** + mtime 热更 ⇒ 不需 DB、不需重启。

---

## 6. ⑤ GUI 可见性：骨架在，三处空白

- **架构**：32 个纯文件（无 npm/无打包器），`index.html:560-593` 按序 `<script>` 加载（**顺序是语义的一部分**，`docs/agents/07-六-前端.md`）；
  `StaticHandler.java:20/39` 服务，挂 `GuiServer.java:397/536/619`。**新鲜度 = 纯轮询**：`app.js:349` 每 5 s 拉 `/api/state`；
  **零 SSE / 零 WebSocket**。
- **今天能看见的**：地图 canvas（`renderer.js`/`map.js`）+ 9 键图层（含**战斗事件**）+ 底栏时间轴 +
  决策模式 5 个子页（决策人/审批/Provider/**决策结果**/**文档**）+ **决心/理由 intentInfo 明文** + 命令清单 + 本轮轨迹
  （toolCalls/finalText/llmCalls）+ 逐格经济读数 + 世界总览 + 战斗 ⚔ 浮层（`events.js:446-452`）。
- **三处真空白**：
  1. **事件时间线**：底栏时间轴是**命令/revision 轴**，不是事件流（`timeline.js:342-345`）。
  2. **日志流**：`webui` 里 `log-stream|/api/log` **0 命中** —— 无日志面板、无日志端点。
  3. **LLM 原始推理 + 决策包审阅页**：`reasoningContent` **一个字都不显示**；`webui` grep `packet` **0 文件**；
     `/gov` 页在 `StaticHandler` **0 命中**（survey #10 复现）。
- **两个已复现缺陷**：`renderer.js:2902 paintBorders` **悬空引用**（全仓无定义，survey #29）；
  `panels.js:767-772` 读 `currentStageName/participantCount`，而真值形状是 `{combatId,name,stage,stageName,outcome,participants}`
  （`renderer.js:625-634`）⇒ 该行渲染成「—」（survey #30）。
- ★ **口径提醒**：`/api/army/combats` **缺省只发世界当前 tick**（`GuiServer.java:1411/1419-1421`），
  而 GUI 的 `events.js` 没带 `?tick=` ⇒ **GUI 只能看"当前 tick"的战斗，看不到历史战斗**。

---

## 7. 差距清单（按"挡不挡得住目标"排序）

| # | 差距 | 挡住什么 | 现状证据 |
|---|---|---|---|
| **G1** | **决策人审批无法放行** | **无人值守完全不可行**（每轮白烧 ~10 分钟） | §4 |
| **G2** | **无多轮×多人的长跑编排器** | "派多个子 Agent 跑 3~5 年"本身 | §3.2-1；`DecisionDueRunner` 0 命中 |
| **G3** | **Skill 无写入口、无 per-agent 归属** | "决策人自由更新自己的 Skill" | §5 |
| **G4** | **`advanceAndDrain` 无生产调用点** | 排队的跨模块效果不落地 | §3.2-2 |
| **G5** | GUI 缺事件时间线 / 日志流 / LLM 推理 / 决策包页 | "推演内容可见"的完整度 | §6 |
| **G6** | DM 被引导 propose 7 个纯 GM 工具 | 决策回合大量无效提议 | §3.2-3 |
| **G7** | 19 hex 世界经济标定失真（税全成损耗、国库空、军俸跳过） | 推演出的政治经济结论不可信 | §2 |
| **G8** | 长跑无回归护栏；算术饱和无上限告警 | 长跑质量无保障 | §2 |

---

## 8. 待用户裁定（口径溯源已于 §1~§6 落盘；**已有裁定不重复问**）

1. **用哪张地图**：`small-world` 19 格（轻、快、已实测）/ `~/Simos-18Lvt/world` 59,223 格（真、重）/ `test-world` 副本。
2. **几个决策人 + 多久一次决策**（直接决定 LLM 调用量：单回合上限 20 次调用）。
3. **"自由更新自己的 Skill"取 §5 的哪一口径**（a / b / c）。
4. **"3~5 年"按历法年（1095~1825 tick）还是按 3×120 周期（1080~1800 tick）**。
5. **G1 怎么解**：决策人面加免审批闸（照 GM 面 `GmAutoApproveGate` 的形状）/ 还是保留人点、把长跑做成有人值守。
6. **先攻哪一段**（G1+G2 编排器 / G3 技能 / G5 GUI）。

---

## 9. 未核 / 没做（如实）

1. **未跑 Maven**（本次为只读调查 + 一次真机冒烟）⇒ 一切测试计数均未复核；未跑门禁。
2. **未实测** 1825 tick 单次 advance 的耗时与内存（只到 1000 tick 单次 15.37 s、逐日 400 天 152.6 s）。
3. **未实测** 外部自造地图端到端（只读导入器代码，未跑 `gsimap_import.py`）。
4. **未判** `Shell.advanceAndDrain` 不被生产调用是 bug 还是 sd 退役迁移残留。
5. **未核** `GmDecidePacketTool.expectedStatus` 缺省 APPROVED 是否被上游兜住。
6. **未核** `~/Simos-18Lvt/world/checkpoints/main/*` 的当前 head（只读 README 与 test-world 副本）。
7. **未核** survey #58 的 `sd` 资源面计数（未跑等价 grep）。
8. **未评估** `three-powers` day 540 硬红的根因是否已修（只读到"不建议长跑"）。
9. 用户已裁定**问题先存好**，故本文**不含方案定稿**；§5/§7 的"最小接缝"只是观察到的形状，不是设计。
