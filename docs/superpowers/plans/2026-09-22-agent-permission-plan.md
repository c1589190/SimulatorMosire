# 决策人权限组与可见范围 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development（推荐）或
> superpowers:executing-plans 逐任务实现。步骤用 `- [ ]` 跟踪。

**Goal:** 把 Simos 的权限从"端口边界"改成"**AgentLib 权限组边界**"——GM 组导出为 MCP，
决策人组在进程内按**范围函数现算**的权限集调用工具，不出 MCP、不直接改数据。

**Architecture:** 两个权限组（GM / 决策人）都用 AgentLib 的 `AgentPermissionSet` 表达；决策人的
`resourceScopes` 由 `DecisionScopeFunction`（国家 / 军队两个实现）**每次调用现算**；资源的"范围/关键词"
用 Simos 自定的**段前缀路径语法**（`<mapId>/region/<rid>`、`<mapId>/hex/<q>_<r>`）表达；
"部分可见"的筛逻辑写在工具里（AgentLib 只给断言原语）。

**Tech Stack:** Java 21、Maven、AgentLib（`permission`/`tool`/`mcp` 包）、JUnit 5 + AssertJ。

**Spec:** `docs/superpowers/specs/2026-09-22-agent-permission-design.md`（判据 J1~J10、硬约束 C1~C3）

## Global Constraints

- 五条铁律（尤其 2：所有修改走 `Command → ChangeSet → Revision`；5：change set 从完整状态类型派生 + 往返不变式）。
- 模块边界由 `maven-enforcer-plugin` 强制；**`simos-map` 永不 import social/unit/sd**；app 是组合根（不设限）。
- **`AgentPermissionSet` 不用 `readOnly(true)`**（C1：它拒一切工具）；只读角色用白名单。
- **`resourceScopes` 为 null/空 = "本层不表态"（放行）**，不是 deny-all；要"够不着"必须显式 `ResourceScope.none()`。
- **粗断言 + 细围栏 = 整调被拒**（不是部分可见）⇒ 断言与围栏成对上线。
- 注释与文档用中文；`./mvnw clean verify` 是硬门禁（Spotless + Checkstyle + SpotBugs + 前端门禁）。
- 任一任务若改动已关账任务的**被测文件** ⇒ 按裁定 42 连带重跑相关变异轮并记档。
- 每条新护栏必须能被**变异杀掉**（否则是装饰）；不得为造红放松判据。

---

## 文件结构

| 文件 | 职责 | 动作 |
|---|---|---|
| `simos-unit/.../Unit.java` | 加视野字段 | 改 |
| `simos-unit/.../UnitChangeSet*.java`（含派生处） | 视野字段进 change set | 改 |
| `simos-app/.../tools/ToolSupport.java` | 加**资源路径语法**助手；改 `requireUnitRead`/`requireSocialRead` 的 `"*"` 字面量 | 改 |
| `simos-app/.../access/DecisionScopeFunction.java` | 范围函数接口 | **新建** |
| `simos-app/.../access/DecisionScopeFunctions.java` | 按 `Affiliation` 类型分派的注册表 | **新建** |
| `simos-app/.../access/NationScope.java` | 国家决策人范围 | **新建** |
| `simos-app/.../access/ArmyScope.java` | 军队决策人范围 | **新建** |
| `simos-app/.../access/NeighborNations.java` | 邻国计算（区域邻接） | **新建** |
| `simos-app/.../access/HexOwner.java` | hex 归属国家 | **新建** |
| `simos-app/.../access/DecisionCallerFactory.java` | 决策人 `ToolContext` 构造（权限组现算） | **新建** |
| `simos-app/.../Shell.java` | GM 组；撤决策人 MCP 口；撤 `McpDecisionChannel` 装配 | 改 |
| `simos-app/.../tools/SimosToolSource.java` | `Role` 简化；决策人桶去 unit 写 | 改 |
| `simos-app/.../tools/read/*.java` | 读工具按 scope 过滤（去掉自报 `actor`） | 改 |
| `simos-sd/.../model/DecisionMaker.java` | `viewScope` → `accessLimit` | 改 |
| `simos-sd/.../spi/SetDecisionMakerAccessHandler.java` | 新配权命令 | **新建** |
| `simos-sd/.../spi/SetViewScopeHandler.java` + `model/ViewScope.java` | 删除（被取代） | **删** |

---

## Task 1: unit 域加视野字段 `visionRadius`

**Files:**
- Modify: `simos-unit/src/main/java/io/mosire/simos/unit/Unit.java`
- Modify: unit 的 change set 派生处 + codec 往返夹具
- Test: `simos-unit/src/test/java/.../UnitChangeSetTest.java`（或既有往返测试类）

**Interfaces:**
- Produces: `Unit.visionRadius()` → `int`，**缺省 1**；构造期校验 `visionRadius >= 0`。

- [ ] **Step 1: 写失败测试** —— 新建一个单位（不传视野）⇒ `visionRadius() == 1`；显式传 3 ⇒ 3；传 -1 ⇒ 抛。
- [ ] **Step 2: 跑测试确认失败**（`./mvnw -q -pl simos-unit -am -Dtest=<类> -Dsurefire.failIfNoSpecifiedTests=false test`）。
- [ ] **Step 3: 改 `Unit` record** —— 加分量 + 紧凑构造器校验 + **兼容重载**（既有调用点不炸）。
- [ ] **Step 4: change set 派生** —— 新字段进 change set（铁律 5：`apply(changeSet, base)` 逐字段重建）。
- [ ] **Step 5: 往返不变式测试** —— 加一条带非缺省 `visionRadius` 的往返（含 `SdCodec`/`UnitCodec` 路径）。
- [ ] **Step 6: 全模块测试 + 提交** —— `./mvnw -q -pl simos-unit -am verify`；`git add <具体文件>`（**绝不 `git add -A`**）。

---

## Task 2: 资源路径语法助手

**Files:**
- Modify: `simos-app/src/main/java/io/mosire/simos/app/tools/ToolSupport.java`
- Test: `simos-app/src/test/java/.../AccessPathsTest.java`（新建）

**Interfaces:**
- Produces: `ToolSupport.resourceRegion(String mapId, String regionId)`、`resourceHex(String mapId, int q, int r)`、
  `resourceUnit(String unitId)`、`resourceSd(String kind, String id)` —— 各返回 `ResourceId`（命名空间 `map`/`unit`/`sd`）。
- Produces: 改后的 `requireUnitRead(context, unitId)` / `requireSocialRead(context, q, r)`（**取代**字面量 `"*"` 版）。

- [ ] **Step 1: 写失败测试** —— 断言拼出的路径逐字：`region("demo","701")` ⇒ `map:demo/region/701`；
  `hex("demo",-5,-59)` ⇒ `map:demo/hex/-5_-59`（负号、下划线分隔照写）。
- [ ] **Step 2: 跑测试确认失败**。
- [ ] **Step 3: 实现助手**（纯函数，无 IO）。
- [ ] **Step 4: 改 `requireUnitRead`/`requireSocialRead`** —— 从字面量 `"*"` 改成按实体/坐标的细粒度断言；
  **同步更新所有调用点**（8 个读工具）。★ 这一步会让"配了受限 scope"从"全拒"变成"按范围判"。
- [ ] **Step 5: 全量测试** —— 既有读工具用例应全绿（GM 组是 unlimited，不受影响）。
- [ ] **Step 6: 提交**。

---

## Task 3: 范围函数接口 + 国家实现

**Files:**
- Create: `simos-app/src/main/java/io/mosire/simos/app/access/DecisionScopeFunction.java`
- Create: `.../access/DecisionScopeFunctions.java`（注册表）
- Create: `.../access/NationScope.java`
- Test: `.../access/NationScopeTest.java`

**Interfaces:**
- Produces: `interface DecisionScopeFunction { ResourceScopeMap scopesFor(DecisionMaker dm, SimulationState state, String mapId); }`
- Produces: `DecisionScopeFunctions.scopesFor(dm, state, mapId)` —— 按 `Affiliation` 运行时类型分派；
  未注册的类型 ⇒ **响亮失败**（不静默给全量）。

- [ ] **Step 1: 写失败测试** —— 造一个 map：两个区域分别带 `nation:FRA` / `nation:GER` tag；
  国家决策人 `Nation(FRA)` ⇒ 返回范围**含**前者的 `demo/region/<rid>`、**不含**后者。
- [ ] **Step 2: 跑测试确认失败**。
- [ ] **Step 3: 实现 `NationScope`** —— 扫 `GameMap.regions()`，取 `region.meta().tag()` 等于 `"nation:" + nationId`
  （用既有 `NationTag.tagFor`，**不要**手拼字符串）；产出 `ResourceScope.of(前缀…)`。
  无匹配区域 ⇒ **`ResourceScope.none()`**（显式 deny-all，不是空集）。
- [ ] **Step 4: 实现注册表 + 接口**。
- [ ] **Step 5: 跑测试确认通过**；补一条"未注册类型响亮失败"的用例。
- [ ] **Step 6: 提交**。

---

## Task 4: 军队范围函数

**Files:**
- Create: `simos-app/src/main/java/io/mosire/simos/app/access/ArmyScope.java`
- Test: `.../access/ArmyScopeTest.java`

**Interfaces:**
- Consumes: `Unit.visionRadius()`（Task 1）、`ToolSupport.resourceHex`（Task 2）。
- Produces: `ArmyScope`（`DecisionScopeFunction` 实现）—— 军队位置由 `UnitState.effectivePosition(unitId, at)` 取
  （**与既有同口径**，不重算）；半径 = `visionRadius`；六角距离 ≤ R 的格 ⇒ `map/<mapId>/hex/<q>_<r>` 前缀。

- [ ] **Step 1: 写失败测试** —— 军队在 (1,1)、`visionRadius=1` ⇒ 范围**含** 7 格（自身 + 6 邻）、**不含** (3,3)。
- [ ] **Step 2: 跑测试确认失败**。
- [ ] **Step 3: 实现**（六角距离用既有 `HexCoord` 的距离口径；**纯半径，无地形遮挡**——用户裁定）。
- [ ] **Step 4: ★ 写"世界变了范围就变"的用例**（J4）—— 同一决策人，军队移动后再算 ⇒ 可见集合随之变化。
- [ ] **Step 5: 跑测试**。
- [ ] **Step 6: 提交**。

---

## Task 5: GM 组（MCP 口 = GM 权限组）

**Files:**
- Modify: `simos-app/src/main/java/io/mosire/simos/app/Shell.java`（`mcpCaller()`）
- Modify: `.../tools/SimosToolSource.java`（`Role` 枚举）

**Interfaces:**
- Produces: `Shell.gmCaller()` —— 显式 `AgentPermissionSet.builder(DEFAULT).allowAll().sensitiveAllowed(true)
  .destructiveAllowed(true).resourceScopes(<各命名空间 unlimited>).build()`；身份保留 `AgentIdentity.external()`（用户裁定①）。

- [ ] **Step 1: 写失败测试** —— 断言 GM 权限组的 `resourceScopes` **不是**空图（"表过态"），且 `allowedTools` 覆盖 GM 面全部工具名。
- [ ] **Step 2: 跑测试确认失败**。
- [ ] **Step 3: 改 `Role`** —— `EXTERNAL` / `EXTERNAL_WITH_GM` ⇒ `Role.GM`（旧值删除，不是保留别名）；更新所有引用点。
- [ ] **Step 4: 改 `mcpCaller()` ⇒ `gmCaller()`**，更新 javadoc（把"权限边界在端口"的旧口径改成"权限组在组"）。
- [ ] **Step 5: 全量测试**（既有 MCP 端到端应全绿——GM 组等价于原 `unrestricted` 的放行效果）。
- [ ] **Step 6: 提交**。

---

## Task 6: 决策人 caller（进程内、权限组现算）

**Files:**
- Create: `simos-app/src/main/java/io/mosire/simos/app/access/DecisionCallerFactory.java`
- Test: `.../access/DecisionCallerFactoryTest.java`

**Interfaces:**
- Consumes: `DecisionScopeFunctions`（Task 3/4）。
- Produces: `DecisionCallerFactory.callerFor(DecisionMaker dm, SimulationState state, String mapId)` → `ToolContext`：
  白名单（受限读 + `sd.IssueDirective` + `sd.SubmitVerdict`）+ `resourceScopes`（现算）+ `AgentIdentity.subagent(...)`。
- Produces: `DecisionCallerFactory.execute(ToolRegistry, String toolName, DecisionMaker, SimulationState, Map<String,Object> args)`
  ⇒ 经 `ToolCallAuthorizer`（**带 approvalCoordinator 的那个工厂**）执行。

- [ ] **Step 1: 写失败测试** —— 同一工具、同一参数、两个决策人（范围不同）⇒ 一个 `ok`、一个 `RESOURCE_DENIED`（**J9 的核心用例**）。
- [ ] **Step 2: 跑测试确认失败**。
- [ ] **Step 3: 实现** —— ★ 必须经 `ToolCallAuthorizer.execute`（手工拼 ctx 直接 `execute` 会全拒，因为判定者只在第 ③ 段注入）。
- [ ] **Step 4: 补用例** —— 决策人调 unit 域写工具 ⇒ 拒（**J3**）。
- [ ] **Step 5: 跑测试**。
- [ ] **Step 6: 提交**。

---

## Task 7: 撤决策人 MCP 口与渠道

**Files:**
- Modify: `Shell.java`（去掉 `decisionMcpServer` 的创建/关闭/端口读回）
- Delete: `.../app/sd/channel/McpDecisionChannel.java`
- Modify: `ShellConfig`（去 `decisionAgentMcpPort`）+ `ShellMain`（去开关与打印）
- Test: 既有 `McpPortTopologyTest`（T4 的"两口并存"用例）**改成断言只有一口**；e2e/工具面测试同步

- [ ] **Step 1: 改测试** —— 断言"决策人端口不监听"（连接被拒），主口仍可用。
- [ ] **Step 2: 跑测试确认失败**。
- [ ] **Step 3: 删代码**（渠道 + 装配 + 配置 + 端口常量）。
- [ ] **Step 4: 全量测试**（扫所有引用 `decisionMcpServer`/`DECISION_AGENT` 桶/`decisionAgentMcpPort` 的地方）。
- [ ] **Step 5: 提交**。

---

## Task 8: 决策人工具面收窄（撤 unit 域写）

**Files:**
- Modify: `.../tools/SimosToolSource.java`（`addDecisionAgentWrites` 只留两条决策窄工具）
- Modify: `.../tools/SimosToolSource.java` 的读工具共享（决策人**不再**自动共享全量读工具——见 Task 10）
- Test: `simos-app/src/test/java/.../SimosToolsTest.java`（决策人桶断言改成"无写工具"）

- [ ] **Step 1: 改测试** —— 断言决策人桶里 unit/map/sd 的写工具**一个都没有**，只有 `sd.IssueDirective`/`sd.SubmitVerdict`。
- [ ] **Step 2: 跑测试确认失败**（现在有 21 条）。
- [ ] **Step 3: 改 `SimosToolSource`**。
- [ ] **Step 4: 全量测试**（M2 的 D-1 相关用例逐条按新语义改，**不得改成恒真**）。
- [ ] **Step 5: 提交**。

---

## Task 9: sd 域——`accessLimit` 取代 `viewScope` + 新配权命令

> ★ **影响面实测（2026-09-22）**：引用 `viewScope`/`ViewScope` 的有 **main 25 个文件 + test 24 个文件**、
> 共 **224 处**（simos-app 28 文件 / simos-sd 15 文件）。⇒ **必须拆成三小步派单**，否则单轮改动面过大、
> 证据无法归因：**9a** 模型与命令（`DecisionMaker`/`AccessLimit`/新 handler/载荷/codec，动 simos-sd）、
> **9b** 读侧迁移（`RedactingQueryService`/`GuiServer`/`ApiViews`/读工具，动 simos-app）、
> **9c** 测试迁移（24 个测试文件逐条按新语义改，**不得改成恒真**）。

**Files:**
- Modify: `simos-sd/.../model/DecisionMaker.java`（`viewScope` → `accessLimit`；`adjudicationDisclosure` 迁入）
- Create: `simos-sd/.../model/AccessLimit.java`（**sd 自己的类型**：命名空间 → 前缀列表 + 披露档）
- Create: `simos-sd/.../spi/SetDecisionMakerAccessHandler.java`（命令 `sd.SetDecisionMakerAccess`）
- Delete: `.../model/ViewScope.java` + `.../spi/SetViewScopeHandler.java`
- Modify: `SdPayloads`（`requireViewScope` → `requireAccessLimit`）、`SdChangeSet`/`SdCodec` 派生、`CatalogTool.PAYLOAD_HINTS`
- Test: sd 的往返 + handler 用例

**Interfaces:**
- Produces: `AccessLimit(Map<String, Set<String>> prefixesByNamespace, DisclosurePolicy disclosure)`；空 `Optional` = 无额外限制。
- 语义：与范围函数的结果做 **`narrowTo`（交集）** ⇒ GM **只能收紧**。

- [ ] **Step 1: 写失败测试** —— 新命令能改 `accessLimit`；改完往返一致（铁律 5）。
- [ ] **Step 2: 跑测试确认失败**。
- [ ] **Step 3: 模型改造 + 命令 handler + 载荷**。
- [ ] **Step 4: 删旧**（`ViewScope`/`SetViewScope`/`requireViewScope`）并逐条修引用点（含 `RedactingQueryService`、`ApiViews`、GUI）。
- [ ] **Step 5: 全量测试**（D4 的既有断言按新语义改）。
- [ ] **Step 6: 提交**。

---

## Task 10: 读工具按 scope 过滤（去掉自报 `actor`）

**Files:**
- Modify: `.../tools/read/MapOverviewTool.java`、`MapHexTool.java`、`UnitListTool.java`、`UnitGetTool.java`、
  `PopulationTool.java`、`StateFacetsTool.java`、`StateResolveTool.java`
- Modify: `.../query/RedactingQueryService.java`（过滤依据从 `viewScope` 换成 `ctx.resources()`）

**Interfaces:**
- Produces: 读工具用 `context.resources().allows(Operation.READ, <细粒度资源>)` **逐项筛**（部分可见），
  或 `require(...)`（整调拒）——**按"要部分可见还是要整调拒"选**。

- [ ] **Step 1: 写失败测试** —— 决策人调 `map.overview` ⇒ 只返回其范围内区域/hex；GM 调 ⇒ 全量。
- [ ] **Step 2: 跑测试确认失败**。
- [ ] **Step 3: 改工具**（去掉 `actor` 参数；身份从 `context.identity()`/`context.permissions()` 来）。
- [ ] **Step 4: 跑测试**；补一条"越界资源整调被拒"的用例（防走回头路）。
- [ ] **Step 5: 提交**。

---

## Task 11: hex 归属国家 + 邻国

**Files:**
- Create: `simos-app/.../access/HexOwner.java`、`.../access/NeighborNations.java`
- Modify: 读视图（`map.hex` 的返回里加 `nation`；国家决策人的视图里加 `neighbors`）

**Interfaces:**
- Produces: `HexOwner.nationsOf(GameMap, HexCoord)` → `Set<String>`（hex 所属区域中带 `nation:` tag 的；**多从属返回集合**）。
- Produces: `NeighborNations.of(GameMap, NationId)` → `Set<String>`（与本国区域六角邻接的**其他国家**）。

- [ ] **Step 1: 写失败测试** —— hex 在两个区域（其一 `nation:FRA`）⇒ `nationsOf` 含 FRA；
  邻接国家的 fixture ⇒ `NeighborNations` 给出 GER（不含本国）。
- [ ] **Step 2: 跑测试确认失败**。
- [ ] **Step 3: 实现两个纯函数**（app 层，不动 map 模块）。
- [ ] **Step 4: 接进读视图**（军队决策人看 hex ⇒ 带 `nation`；国家决策人 ⇒ 带 `neighbors`）。
- [ ] **Step 5: 跑测试 + 提交**。

---

## Task 11B: 决策人 agent 运行流（LLM + 工具 + 会话）

**Files:**
- Create: `simos-app/src/main/java/io/mosire/simos/app/decision/DecisionAgentRunner.java`（循环 + 会话）
- Create: `.../decision/DecisionToolDefs.java`（把决策人可见的 `AgentTool` 转成 `ToolDef`）
- Test: `.../decision/DecisionAgentRunnerTest.java`

**Interfaces:**
- Consumes: `DecisionCallerFactory`（Task 6）、AgentLib 的 `ConversationStore` / `LlmClient` / `ToolCallAuthorizer`。
- Produces: `DecisionAgentRunner.run(DecisionMaker dm, SimulationState state)` —— 跑一轮：LLM ↔ 工具，直到模型不再请求工具调用。

- [ ] **Step 1: 写失败测试**（用 AgentLib 的 `FakeLlmClient` 注入一段"先 toolCall、后纯文本"的脚本）——
  断言：① 工具**真的被执行**（有可观察副作用）；② 会话追加了 assistant 与 tool-result 两条消息；
  ③ **第二次 `run` 时 `load(cid)` 能看到上一轮的历史**（**J11 的上下文沿用**）。
- [ ] **Step 2: 跑测试确认失败**。
- [ ] **Step 3: 实现循环**（`while(true)` + `chat` + `authorizer.execute` + 追加历史）。
- [ ] **Step 4: 接 `SqliteConversationStore`**（落 `<store>` 下）＋ 一条"换新 store 实例仍读得到同一 cid 的历史"的用例。
- [ ] **Step 5: 跑测试 + 提交**。

---

## Task 12: 护栏、端到端与关账

- [ ] **Step 1: 补齐 J1~J9 的判据用例**（逐条点名 spec §〇 的表）。
- [ ] **Step 2: 变异轮** —— 每条新护栏配**故意违规变异体**（如：范围函数返回 `unlimited()`、
  决策人桶放回一条写工具、`narrowTo` 改成 `unlimited` 覆盖），逐条证明会红。
- [ ] **Step 3: 端到端** —— 起壳，经 GUI（GM 路径）与决策人路径各跑一次真实链路；决策人**确认调不到** MCP 口。
- [ ] **Step 4: 门禁** —— `./mvnw clean verify`（前台、记第几次尝试）；`BugInstance size is 0`；前端门禁。
- [ ] **Step 5: 文档与台账** —— 更新 creed（§一/§四 的旧口径）、`pending-rulings.md`（M4/M5 那两节的前提已作废）、
  CLAUDE.md 状态表；SDD 台账 `.superpowers/sdd/2026-09-22-tool-surface/progress.md` 记逐任务裁定。
- [ ] **Step 6: 提交 + 推送**（私有仓，用户口径：该推就推）。

---

## 自审（对照 spec）

| spec 判据 | 落在 |
|---|---|
| J1 GM 组 | T5 |
| J2 无 MCP | T7 |
| J3 不能直接改数据 | T6 Step 4 + T8 |
| J4 范围现算 | T4 Step 4 + T6 Step 1 |
| J5 邻国 | T11 |
| J6 hex 归属国家 | T11 |
| J7 视野字段 | T1 |
| J8 配权取代 | T9 |
| J9 资源维不空转 | T6 Step 1 |
| J10 门禁与护栏 | T12 |
| J11 决策人自己调工具 + 上下文沿用 | T11B |

**未覆盖项（有意）**：地形遮挡（用户裁定⑥：纯半径）；AgentLib 的 per-session 身份（需改外部仓库，非 Simos 能补）；
"除 X 外"否定语义（`ResourceScope` 无此能力）。

## 风险与顺序约束

- **T1 动 `simos-unit`**（已关账多轮）⇒ 按裁定 42，改动被测文件须连带重跑相关变异轮。
- **T9 动 `simos-sd`**（D4 的 viewScope 成果作废）——**最大的一块**，建议单独一个 worktree、单独一轮门禁。
- **T2/T10 动读工具断言** ⇒ 必须与围栏配置**成对上线**（否则表现为"某 role 突然什么都读不到"）。
- **一次只准有一个 Maven 在跑**（本机 nproc=8，但"agent 与 Maven 并存"未测 ⇒ 保守串行）。
- 门禁耗时本机实测 75 s（nproc=8），不受 600 s 线约束。

---

## 验收（用户 2026-09-22 指定的**真实流程**，改造完成后执行）

**手段**：真 LLM（仓库 `config/llm-providers.json` 的 `mosire-flash` = `121.40.130.178:3000/v1` /
`deepseek-flash`）＋ **Python 模拟的外部 agent 环境连 MCP**（控制器自己用 bash 起 MCP 客户端，不经 GUI/HTTP 捷径）。

**流程（必须真跑，不是推演）**：

1. **GM 侧（经 MCP）**：找一个国家 → 创建一个军事单位 → 建**两个**决策人（国家决策人 + 军队决策人）。
2. **决策人侧**：让这两个决策人作决策 —— 具体是让它们经决策系统**报告"自己能看见什么"**。
3. **判定**：在 MCP 里为这些决策判定 —— **正确 ⇒ 设决策为成功；权限出问题 ⇒ 失败**。
4. **上下文沿用**：用 MCP 推进时间线，在**不同 tick 让同一个决策人 agent 用同一上下文**做多轮工具调用与决策
   （★ 若当前系统没有跨 tick 的上下文沿用机制，这一条会把它暴露出来——属**预期发现**，如实记录再定修法）。
5. **闭环**：发现的问题**当场修、重跑**，直到没有新发现；全程留痕（脚本、日志、判定依据）。

**要盯的三件事**（用户点名）：GM 工具调用是否正常 · 决策人工具权限是否正确（含"看不到不该看的"）·
决策提交是否可行、有没有 bug。
