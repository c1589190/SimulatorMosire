# M4 侦察报告 —— 工具面标准化 · 读口清单（D-3 的派单前置）

> 性质：**只读侦察**。全程未改任何文件、未运行 Maven / node / npm、未做任何 git 写操作。
> 工作区：worktree `/home/dev/SimulatorMosire/.claude/worktrees/ts+m1`，分支 `ts/m1`。
> 本报告的每个数字都是**当场读码数出来的**，不是引用计划里的"23 / 9"。
> ★ 本报告里凡标 **未运行验证** 的，都是**静态读码结论**（没跑构建、没起服务、没发请求）；
> 标 **未核实** 的，是我**没读到那一层**（≠ 不存在）。"我没搜到"一律写出命令与范围。

---

## 〇 我实测到的数字（现数，非引用）

| 项 | 实测值 | 数法（可自查） |
|---|---|---|
| **GUI JSON 读端点** | **17** | 13 条在 `GuiServer.GET_ROUTES`(`GuiServer.java:142-156`) + 3 条前缀路由（`/api/unit/{id}` `:104`、`/api/map/region/{id}` `:107`、`/api/sd/decision-makers/{id}` `:114`，均在 `handleGet` 内 `:418/:436/:462` 取到）+ 1 条 `GET /api/approvals`(`:117`，代理由 `:562-565`) |
| **现有读工具** | **9** | `SimosToolSource.readTools`(`SimosToolSource.java:137-148`) 逐条点数；名字常量逐个 grep `String NAME = ` 于 `simos-app/src/main/java/io/mosire/simos/app/tools/` |
| 静态页面（非 JSON 读） | 5 | `StaticHandler.java:27-31`（`/`、`/index.html`、`/map`、`/unit`、`/social`） |
| **计划里的"23"** | 可对上 | 13+3+**2**（`/api/approvals` 与 `/api/approvals/{id}` 两条 GET）+**5**（静态页）= 23。★ 这是**我的对账**，不是读到的出处 ⇒ 见 §五 第 4 条 |
| 写工具 | 9 | 通用写 3（`SimosToolSource.java:113-119`）+ GM 窄写 4（`:121-128`）+ 决策人窄写 2（`:130-135`） |
| `EXTERNAL_WITH_GM` 桶合计 | 16 | 9 读 + 3 通用写 + 4 GM 窄写（与 `SimosToolsTest.java:276` 的 `.hasSize(16)` 一致） |

**9 条读工具名单**（`SimosToolSource.java:140-148`，名字取自各 `NAME` 常量）：

| # | 名字 | 实现 |
|---|---|---|
| 1 | `simos.command.catalog` | `read/CatalogTool.java:25` |
| 2 | `simos.state.resolve` | `read/StateResolveTool.java:25` |
| 3 | `simos.state.facets` | `read/StateFacetsTool.java:24` |
| 4 | `simos.timeline.branches` | `read/BranchListTool.java:26` |
| 5 | `simos.map.overview` | `read/MapOverviewTool.java:25` |
| 6 | `simos.map.hex` | `read/MapHexTool.java:27` |
| 7 | `simos.unit.list` | `read/UnitListTool.java:25` |
| 8 | `simos.unit.get` | `read/UnitGetTool.java:24` |
| 9 | `simos.social.population` | `read/PopulationTool.java:23` |

★ **读工具是四桶共享**（已复核）：`SimosToolSource.java:100` 先 `new ArrayList<>(readTools(...))`，再按 `Role` 追加写工具（`:101-108`）⇒ 加一条读工具会同时出现在 **EXTERNAL / GM / DECISION_AGENT / EXTERNAL_WITH_GM 四个桶**（含运行时两个 MCP 口）。

---

## 一 逐条对照表

> 口径：**GUI 端点 → 对应读工具**。"缺口"= 没有等价读工具。形状比对只写我在码里读到的差异（静态读码，**未运行验证**）。

| GUI 端点（锚点） | 用途（一句话） | 对应读工具 | 暴露风险 | 建议 |
|---|---|---|---|---|
| `GET /api/state`(`:144`, `:388`) | 当前 head 的 `meta` + `branches` | **部分**：`simos.timeline.branches`（只有 `branches`+`heads`，见 `BranchListTool.java:64-67`）；`meta`/完整状态面无读工具 | 中 | 先明确"state"到底要不要给 Agent；若只要 branches ⇒ 已覆盖，**不新增** |
| `GET /api/resolve`(`:145`, `:390`) | 地址 → 候选实体 | `simos.state.resolve` ✅ | 低（无 `as=` 时全量） | 无需新增；若要给决策人 ⇒ 必须接 redaction（`as=` 语义） |
| `GET /api/facets`(`:146`, `:398`) | 某地址的 Facet 条目 | `simos.state.facets` ✅ | 低 | 同上 |
| `GET /api/map/overview`(`:147`, `:404`) | 地图总览（块多边形） | `simos.map.overview` ⚠️ **形状发散** | **中高** | ★ 见 §二-6 与 §四-3：带 `as=` 的 GUI 路径与 MCP 工具**都退回逐格**，真档 ≈1MB |
| `GET /api/map/hex`(`:148`, `:411`) | 单格详情（含 `regions`/`terrainType`/`edges`） | `simos.map.hex` ⚠️ **形状发散** | **高（MCP 面）** | ★ 见 §二-6：MCP 版少了 `regions`/`edges`，正是 M8-U1 的"多从属"信息 |
| `GET /api/map/path`(`:149`, `:414`) | A\* 路径（规划试算） | **缺口**（无 `simos.map.path`） | **高** | ★ 见 §二-4：这是**地形探测**读，且**显式拒绝 `as=`** |
| `GET /api/units`(`:150`, `:425`) | 单位列表 | `simos.unit.list` ✅（有 `actor` 属性，`UnitListTool.java:48/63`） | 中 | 唯一已带 actor 的两条读工具之一，可作其余读工具的**接口范本** |
| `GET /api/social/population`(`:151`, `:440`) | 人口序列 | `simos.social.population` ✅ | 低 | — |
| `GET /api/timeline`(`:152`, `:422`) | 修订节点清单（`branch`/`head`/`nodes`） | **缺口**（`BranchListTool` 只给 branch+head，不给 `nodes`） | 中 | 数据已在 `CoreSimos.revisions(BranchId)`(`CoreSimos.java:307`)，**只差值形状**；★ 加它会撞 §三 的切片缺陷 |
| `GET /api/sd/decision-makers`(`:153`, `:454`) | 决策人列表 | **缺口** | **高** | ★ 见 §二-2：含 `rootUnit`/`viewScope`/`allowedTools`/`providerId` |
| `GET /api/sd/verdicts`(`:154`, `:443`) | 判决（含模型输出 `payload`） | **缺口** | **最高** | ★ 见 §二-1 |
| `GET /api/gm/tool-usage`(`:155`, `:450`) | GM 口工具调用记录 | **缺口** | **高** | ★ 见 §二-3：**GM 内部观测**，且**显式拒绝 `as=`** |
| `GET /api/llm/providers`(`:156`, `:458`) | LLM provider 配置（掩码） | **缺口** | **高（若给决策人）** | ★ 见 §二-5：密钥值**不**泄露（已核），但 **baseUrl/model/key 名**泄露 |
| `GET /api/unit/{id}`(`:104`, `:436`) | 单位详情 | `simos.unit.get` ✅ | 中（`as=` 下有 fail-closed 404，`GuiServer.java:698-700`） | — |
| `GET /api/map/region/{id}`(`:107`, `:418`) | 区域详情（含 hex 列表） | **缺口** | 中 | 区域是 M8-U1 的多对多从属载体；若开口，**先定 `as=` 语义** |
| `GET /api/sd/decision-makers/{id}`(`:114`, `:462`) | 单个决策人（含 `allowedTools`/`providerId`/`viewScope` 计数） | **缺口** | **高** | 同 §二-2 |
| `GET /api/approvals`(`:117`, `:562`) | 待裁决队列（原样代理 AgentLib） | **不算缺口**（AgentLib 自有审批面，非 simos 读工具） | 低 | MCP 侧是否已有等价工具 ⇒ **未核实**（外部依赖，见 §五-2） |

**缺口统计：完全缺口 8 条**（`/api/map/path`、`/api/timeline`、`/api/sd/decision-makers`、`/api/sd/decision-makers/{id}`、`/api/sd/verdicts`、`/api/gm/tool-usage`、`/api/llm/providers`、`/api/map/region/{id}`）
**+ 部分缺口 1 条**（`/api/state` 的 `meta` 面）
**⇒ 缺口数 = 9**（8 完全 + 1 部分；`/api/approvals` 不算）。

---

## 二 暴露风险（逐条，含"未核实"标注）

> 前提（**必须一起读**）：GUI 默认绑 **回环**（`ShellConfig.bindAddress` 缺省 `127.0.0.1`，M10 ③ 已实测）。所以"GUI 端点"本身**不是**直接暴露面。
> **真正的风险是：把该端点的视图接成读工具后被四桶共享**（§〇 末条）——那一刻决策人 Agent 与外部 Agent 都会拿到它。
> 下面每条都在回答"**若照抄 GUI 视图做读工具，会发生什么**"。

### 1. `/api/sd/verdicts` 无 `as=` ⇒ **FULL 全量**（含模型输出）——**最高**

- 锚点：`GuiServer.java:443-448` —— 有 `as=` 走 `redactingQueryService.verdicts(actor, target)`，**无 `as=` 走 `redactingQueryService.verdicts(target)`**；后者在 `RedactingQueryService.java:167-168` 写死 `DisclosurePolicy.FULL`，`verdictViews:203-209` 在 FULL 下把 `payload`（模型原始输出）与 `meta{model,promptVersion,inputBriefDigest}` 一并发出。
- **这不是缺陷——是被钉住的有意口径**：`RedactionApiTest.java:184 verdictsWithoutActorAreFullDisclosure` 明写"无 actor = 全量披露"并断言 `payload` 存在。Javadoc 也写"无 actor（GM / 调试）的判决视图"。
- **风险点**：`/api/sd/verdicts` 是**唯一"支持 `as=` 又允许省略"**的 sd 读端点；而 `/api/sd/decision-makers`(`:455`)、`/api/gm/tool-usage`(`:451`)、`/api/llm/providers`(`:459`) 是**显式 `rejectAs`**（fail-closed，`GuiServer.java:474-479`）。⇒ 照抄者很可能只看到"这个端点支持 `as=`"就把它接成读工具，漏掉"省略即全量"。
- **建议**：读工具**必填 `actor`**（不给缺省，或"缺省 = 拒绝"而非"缺省 = FULL"）。这条要在 creed 里写成硬约束。
- **静态读码结论，未运行验证**（结论来自 `:447` 与 `:167` 两处直接取自码，但**我没发过请求**）。

### 2. `/api/sd/decision-makers` 暴露**别人的**作战数据——**高**

- 锚点：`GuiServer.java:454-456` → `decisionMakersReply` → `ApiViews.decisionMakers:556-562` / `decisionMaker:575-605`（含 `allowedTools`、`providerId`、`viewScope` 计数、`affiliation`、`rootUnit`）；数据源 `SdQueryService.info(...):134-159`、`pending(...):173-188`。
- **风险**：`allowedTools` 是**安全白名单本身**（N9 的执法依据），`providerId`/`viewScope` 是**对手的感知配置**，`rootUnit` 是**对手的编制根**。这些是"读别人底牌"。
- **该端点不收 `as=`**（`:455`）⇒ **没有任何按视角裁剪的机制**；一旦成为四桶共享读工具，决策人 Agent 可无差别读取全部决策人的配置。
- **建议**：若开口，工具层必须**强制 actor 过滤**（只回自己的条目）；或明确裁定"决策人桶不给该读工具"。
- **静态读码结论，未运行验证。**

### 3. `/api/gm/tool-usage` 是 **GM 内部观测面**——**高**

- 锚点：`GuiServer.java:450-452`（`rejectAs` + `gmToolUsageReply()`），数据源 `GmToolUsage`（有界 200 条、只存 `Entry(tool, ok, code, atEpochMs)`），由 `Shell.java:445-449` 用 `RecordingToolSource.record(...)` **只包 `EXTERNAL_WITH_GM` 那个 source**。
- **风险**：这是**运行时监督数据**（谁在什么时候调了什么工具、成功与否），语义上属于 GM/运维面，不是世界状态。四桶共享会把"GM 在看什么"暴露给被观测者 ⇒ **观测者被反向观测**。
- **建议**：**明确不开口**（列入 creed 的"读口负面清单"），或在 `SimosToolSource` 里为它单开一个桶语义。
- **静态读码结论，未运行验证。**

### 4. `/api/map/path` 是**地形探测**读，且**没有 redaction**——**高**

- 锚点：`GuiServer.java:414-416`（`rejectAs(path, asPresent)` 后 `mapPathReply(params)`）；`mapPathReply` 是 `PathFinder.findPath(..., TerrainMovementCost.INSTANCE)` 的包装 ⇒ **返回值 = 一条真实可达路径**。
- **风险**：① 路径本身就是**对未见地形的推断**——反复试算可以逐格还原地形成本（探测攻击）；② 它**显式拒绝 `as=`**（`:415`），意味着**它今天就没有视角概念**，照抄成读工具 = 给决策人一条**绕过 viewScope 的测地形通道**；③ MCP 侧已有 `simos.unit.PlanRoute` **写**工具（`write/` 目录下）——**读**口再开一条等于同一能力两副面孔，且读的那副不受审批。
- **建议**：若要给决策人 ⇒ **必填 actor + 对未见格 fail-closed**（同 `seesHex` 口径，`RedactingQueryService.java:105`）；若只给 GM ⇒ 桶归属必须**显式写出**（creed 五）。
- **静态读码结论，未运行验证。**

### 5. `/api/llm/providers` **密钥值不泄露**（已核实）、但泄露**配置与 key 名**——**高（若给决策人）**

- **已核实的"安全"部分**：`AgentLibLlmConfig.view(name):207-229` 只回 `id / valid / baseUrl / model / protocol / readTimeoutMs / connectTimeoutMs / credentialsRef(掩码) / keyConfigured(布尔) / capabilities`；`:220` 走 `maskCredentials(:457-461)`（不匹配 `[A-Za-z0-9_.-]{1,64}` 就变 `"****"`）；`:221` 走 `keyConfigured(:311-320)`，**只报布尔**。**没有任何一处分发出密钥值**（我逐个字段读过）。
- **仍暴露的**：`baseUrl`（内网地址 / 供应商端点）、`model`、以及**`credentialsRef` 的键名**（形如 `keys.deepseek`——掩码函数对"正常名字"**原样返回**，`:461`）。键名不是密钥，但它告诉攻击者"去哪找"。
- ★ **一处未核实**：`:226 view.put("error", e.getMessage())` ——**坏条目**时把 `ConfigException.getMessage()` 原样发出。Javadoc(`:204-205`)只保证 `errorCode` 非敏感。**该消息是否可能带入密钥值，我没读到构造端（未核实）。**
- **建议**：**不给决策人桶**；若必须开，先核 `ConfigException` 的消息构造（§五-1）。

### 6. 同一资源的**两个形状**（GUI vs MCP）⇒ 语义安全品**已发散**——**中高**

- **`simos.map.hex`**：MCP 版 = `{q, r, terrain, height, facets}`（`MapHexTool.java:76-79`）；GUI 版 = 上述 + `regions[]` + `terrainType` + `edges[]`（`ApiViews.java:354-362`）。⇒ **MCP 读工具看不到区域从属与连通性**，而"一个 hex 同时属于多个区域"正是 **M8-U1 用户裁定的地基语义**。两条读路径对同一实体回答不同问题，**没有任何用例跨面比较过它们**。
- **`simos.map.overview` / `/api/map/overview`**：GUI **无 `as=`** 走 `ApiViews.mapOverview`（块多边形，M9 T13 起**不再发逐格地形**，`ApiViews.java:165-175`）；**带 `as=`** 走 `RedactingQueryService.mapOverview:75` → `ToolSupport.mapOverview` = **逐格 `{q,r,terrain,height}` 数组**（`ToolSupport.java:306-314`）；MCP 的 `simos.map.overview`（`MapOverviewTool.java:71`）**同走逐格**。⇒ **M9 的省流改造只落在 ApiViews 一侧**，另两条路径仍是 O(hexes)。真档 19441 格下，M9 实测过同量级载荷 ≈1MB（`ApiViews.java:165` 的注释记着改造前 1,043,837 B）。
- **风险**：给 Agent 开 `simos.map.overview` 会**把 M9 的成果绕过去**（Agent 侧 ≈1MB/次），且 `as=` 那条 GUI 路径同样没吃到省流。这是**成本风险**，不是安全风险，但会直接打回 M9 的判据。
- **建议**：把"读工具 ↔ GUI 视图"的**形状一致性**立成判据（§四-3），而不是靠人记得。
- **静态读码结论，未运行验证。**

### 7. 其余（低）——逐条一句话

| 端点 | 风险 | 说明 |
|---|---|---|
| `/api/state`、`/api/resolve`、`/api/facets` | 低 | 无 `as=` 时全量；`/api/state` 只有 `branches` 被读工具覆盖 |
| `/api/units`、`/api/unit/{id}` | 中 | **已带 actor**（`UnitListTool.java:48/63`）；`as=` 下 `seesUnit` fail-closed 404（`GuiServer.java:698-700`）⇒ **可作范本** |
| `/api/social/population`、`/api/map/region/{id}` | 中/低 | 无视角概念；区域详情含完整 hex 列表 |
| `POST /api/llm/providers*`（`:158-167`） | — | **写面**，不属本次读口；但同一路径 GET/POST 双挂（`GET_ROUTES` 与 `POST_ROUTES` 都含 `LLM_PROVIDERS_PATH`），**读口与配置面同址**，别在 refactor 时混掉 |
| 审批 `summary` 是否泄露 **已核**（非本任务的读口） | 低 | GM 窄写的 `summary` 只带 `branch=`/`expected=`，**不带 `payloadJson`**；上游 `ApprovalHttpEndpoint.handleList`（`AgentLibMosire/.../ApprovalHttpEndpoint.java:157-172`）只回 `{id, tool, classKey, summary, digest, createdAtEpochMs, deadlineEpochMs}`，**不回参数** ⇒ simos 工具的待审命令载荷不进审批列表 |

---

## 三 代价核算

> 问题：**加一条读工具，哪些既有断言会红？**
> ★ 结论先行：**会红的是"名字集合"类断言（3 个文件）**；**不会红的是"行为/覆盖"类断言**——而那些**恰恰是最该红的**（§四）。

### 3.1 会红（必须连带改）

| 文件 | 锚点 | 为什么红 |
|---|---|---|
| `simos-app/src/test/java/io/mosire/simos/app/tools/SimosToolsTest.java` | `EXTERNAL_UNION_GM_TOOL_NAMES` `:104-121`（16 名）；`:204 registryContainsExactlyTheExternalUnionGmTools` → `.containsExactlyInAnyOrderElementsOf(...)` `:207`；`:276` `.hasSize(16)` | 精确集合断言 + 精确计数 ⇒ 加一条即不等 |
| 同上 | `:321 readsAreAllowGatedAndDeclareTheirResources` | 只遍历 `EXTERNAL_UNION_GM_TOOL_NAMES.subList(0, 9)`(`:322`) / 写闸 `subList(9, 16)`(`:343`) ⇒ **名单一变长，切片仍"合法"**：新读工具若追加到末尾就**完全不被读闸覆盖**（照绿）；若插进前 9 位则被**写闸**拿去当写工具断言（红/误判）。★ **M1 裁决 C 已点名的真缺陷，仍然在案** |
| `simos-app/src/test/java/io/mosire/simos/app/McpServerTest.java` | 自带同一份 16 名名单 `:99-116`；`:169-173` 用**真 SDK `client.listTools()`** 断言 `containsExactlyInAnyOrderElementsOf(EXTERNAL_UNION_GM_TOOL_NAMES)` | 同上 ⇒ 红。★ 注意这是**第二份手抄名单**，改一处必须改两处（与前端 `MIN_TESTS`/`MIN_ASSERTIONS` 同族的双写） |
| `simos-app/src/test/java/io/mosire/simos/app/McpPortTopologyTest.java` | `READ_TOOLS` `:42-52`；现有口 `:90-91` `concat(READ_TOOLS, GENERIC_WRITES, GM_NARROW_WRITES)`；决策人口 `:107` `concat(READ_TOOLS, DECISION_AGENT_WRITES)` + `:110` `doesNotContainAnyElementsOf(GENERIC_WRITES)` | `READ_TOOLS` 被**两个端口**共用 ⇒ 加一条读工具**两处同时红**。这是"读工具四桶共享"在测试面的**可见证据** |
| `simos-app/src/test/java/io/mosire/simos/app/AppWritePathGuardTest.java` | `FORBIDDEN = List.of("SqliteStore", "Timeline", "CheckpointStore")` `:35-36`；扫描 `simos-app` main 源码 `:39-50` | 只在**新读工具直接引用存储类型**时红。★ 想要 `/api/timeline` 那种能力的正确写法是走 `CoreSimos.revisions(BranchId)`(`CoreSimos.java:307`)——**不是**绕过 Core 拿 `Timeline` |

### 3.2 **不会**红（⇒ 缺口，不是好消息）

| 文件 | 锚点 | 说明 |
|---|---|---|
| `simos-app/src/test/java/io/mosire/simos/app/McpCoverageTest.java` | 唯一实义用例 `:313 everyCatalogTypeIsReachableThroughMcpAndTakesEffect`；`listTools()` **零命中**（grep：`git grep -n "listTools\|toolNames" -- .../McpCoverageTest.java` → 无输出，rc=1）；`callTool` 只在 `:407`（catalog）与 `:448`（提交） | **纯命令类型驱动** ⇒ 加读工具**不红**。★ 这同时说明：**读口没有 McpCoverageTest 的等价物**（§四-1） |
| `simos-app/src/test/java/io/mosire/simos/app/sd/StartDecisionEndToEndTest.java` | `:230/:233/:236` per-role `contains`/`doesNotContain` | 无计数 ⇒ 不红（**正确的写法**，可作范本） |
| `simos-app/src/test/java/io/mosire/simos/app/gui/RedactionApiTest.java` | 12 条端点级用例（`:133`~`:233`） | 端点级，与工具面无关 ⇒ 不红。★ 但 `:184 verdictsWithoutActorAreFullDisclosure`、`:221 endpointsWithoutRedactionRejectTheAsParameter`（`:225-230` 列 `/api/map/path`、`/api/sd/decision-makers`、`/api/sd/decision-makers/{id}` ⇒ 400）是**既有 redaction 口径的抓手**，新读工具若照抄这些端点**必须**先过它们 |
| `simos-app/src/test/java/io/mosire/simos/app/gui/GuiApiTest.java` | 29 个 `@Test`（`git grep -c "  void "` = 29） | 端点级 ⇒ 不红 |
| `simos-app/src/test/java/io/mosire/simos/app/tools/SimosToolsTest.java:453 readToolsMatchQueryServicePerValue` | 只对 3 条（resolve/population/unit.list）逐值对拍 | 新读工具**不在其对拍名单里 ⇒ 不红、不被覆盖**（§四-1） |
| 前端门禁 | `simos-app/src/test/js/run-gate.cjs:30 MIN_TESTS = 204`；`gate-contract.test.cjs:47 MIN_ASSERTIONS = 204` + `REQUIRED_FILES:9-27`（17 个 `.test.cjs`） | 读工具是 Java 侧 ⇒ **不牵动**。★ 只在 M4 **新增 JS 测试文件**时：`REQUIRED_FILES` 要加名、两处下界要同改（"改一处必须改两处"） |

### 3.3 `CatalogTool.PAYLOAD_HINTS` —— **不加读工具就不该动**

- creed 五写"每加一条**命令/工具** ⇒ 连带改 `CatalogTool.PAYLOAD_HINTS` + `McpCoverageTest` 双向载荷"。**逐字读会误导**：该规矩的对象是**命令类型**（`PAYLOAD_HINTS` 是"命令 type → 载荷提示"表，且 `CatalogTool` **构造期强制**每条注册 type 都有提示——`:290 catalogRejectsACommandTypeWithoutAPayloadHint`）。
- **加读工具不产生新命令类型** ⇒ `PAYLOAD_HINTS` 与 `McpCoverageTest` 的 `EXPECTED_COMMAND_TYPES`/`MINIMAL_PAYLOADS` **都不动**。（已核：`SimosToolsTest.java:211-219` 与 `:231-243` 两条断言只认命令 type。）
- **建议**：creed 的这条措辞改成"每加一条**命令**"，否则实现者会去找一个不存在的表项。

---

## 四 判据缺口

### 1. 9 条读工具**没有**"MCP 可达 + 形状正确"的逐条守卫

现有守卫**全在名字集合层**，没有一条真的**调**过读工具：

| 现有守卫 | 锚点 | 它证了什么 | 它**没**证什么 |
|---|---|---|---|
| `registryContainsExactlyTheExternalUnionGmTools` | `SimosToolsTest.java:204-207` | 名字集合相等（registry 面） | 工具**能不能跑** |
| `roleBucketsNeverCarryGenericWrite` | `:250-276` | 桶不含通用写 + `.hasSize(16)` | 读工具的**行为** |
| `readsAreAllowGatedAndDeclareTheirResources` | `:321-339` | **按 `subList(0,9)` 切片**选的 9 条：`spec()==DEFAULT`、`gate()==ALLOW`、6 条硬编码的资源声明 | **形状**、**MCP 可达**；且切片选法本身是缺陷 |
| `readToolsMatchQueryServicePerValue` | `:453-475` | **3 条**（resolve/population/unit.list）逐值 | 另 **6 条**的形状 |
| `McpServerTest:169-173` / `McpPortTopologyTest:90/:107` | 各一处 | **真 SDK `listTools()`** 的名字集合 | **没有一次 `callTool`**（grep 确认这是全 `simos-app/src/test` 里唯二调 `listTools()` 的地方） |
| `McpCoverageTest:313` | — | 43 个**命令** type 逐条经 MCP 提交并生效 | **读**工具一条都没覆盖 |

**⇒ 缺口（推论，非既成事实）**：一条读工具可以有**正确的名字**、**正确的 `spec()`**，但**形状错、抛异常、或压根不可达**，而**全部现有断言照绿**。
**建议**：给读工具面立一条与 `McpCoverageTest` 等价的判据（逐条 `callTool` + 断言最小形状），且**按名单选**而非切片。

### 2. catalog 式**同源强判据只覆盖命令**

- 唯一"扫源码求同源"的强判据：`SimosToolsTest.java:231-243 catalogCoversEveryCommandHandlerImplementation` + `:296-318 handlerTypesFromSources()`（扫 `../simos-unit|map|sd` 的 `*Handler.java` 抽 `public String type()`，断言 `hasSize(43)` 且 == catalog 集合）。
- **对象是 `CommandHandler`，不是 `AgentTool`**。全仓**没有**任何"扫 `AgentTool`/`*Tool.java` 实现 → 与 `SimosToolSource.readTools` 求同源"的判据（grep 范围见 §五-5）。
- ⇒ 加一条 `read/*Tool.java` 却忘了加进 `readTools(...)`：**不会红**（`SimosToolsTest` 的两份手抄名单都不认识它，`McpCoverageTest` 不看工具）。
- ★ 顺带一处**判据文本自相矛盾**（不是行为缺陷）：`:223-228` 的 javadoc 一处在说"恰为 **43** 个 `*Handler.java`"、另一处说"文件数必须恰为 **30**"，而断言是 `hasSize(43)`(`:235`)。建议顺手对齐文案（SDSimos 后实际计数以断言为准）。

### 3. **形状发散无判据**（GUI 面 vs MCP 面）

- 已实测两处发散（§二-6）：`simos.map.hex` 少 `regions`/`edges`；`simos.map.overview`/`as=` 版本是逐格而 GUI 无 `as=` 版是块多边形。
- **没有任何用例跨面比较**。⇒ "同源"在**定义层**（一份 `AgentTool` 定义、多张脸）成立，在**载荷层**没有护栏。
- **建议**：若 creed 的"同源"包含"同一资源的形状一致"，就需要一条跨面判据；若**不**包含（= 允许两面临界不同），则**要写进 creed 明文**——否则下一个实现者会照 `ApiViews` 抄一份形状不同的 MCP 读工具。

### 4. 桶归属的"显式写出"还没有落地物

- creed 五要求"哪个工具归哪个桶要**明确写出来**，不是默认"。当前实际形态：读工具**四桶全给**（`SimosToolSource.java:100`），写工具按 `Role` switch(`:101-108`)。
- **缺口**：读工具的桶归属是**结构默认**给的，不是"写出来的"。若 M4 要"点名"，需要一份**读工具的桶语义表**（哪条给决策人 / 哪条只给 GM），而今天**没有这个位置**——`readTools(...)` 是无参的共享列表。
- **建议**：M4 的清单（本报告 §一）就是这张表的雏形；派单时应要求**逐条给桶判定**，而不是"9 条都加进去"。

---

## 五 我未能核实的

1. **`ConfigException.getMessage()` 是否可能带密钥值**（§二-5 的 `AgentLibLlmConfig.java:226`）。消息由 `LlmRouteLoader.load` 抛出，**我没读它的构造端**（`AgentLibLlmConfig` 内的 `LlmRouteLoader` 参考实现 / AgentLib 侧）。⇒ 只敢说"我读到的字段里没有密钥值"，不敢说"坏条目路径也干净"。
2. **`agentlib-mosire` 一侧的能力**（**外部 SNAPSHOT 依赖，不在本仓**）：MCP 侧是否已有"列待审"工具、`ToolGate`/`ToolCallAuthorizer` 在**决策人端口**的具体判定、`ApprovalRequest.digest` 的实际脱敏强度。本报告只读了本仓调用点与 `ApprovalHttpEndpoint` 的源码（在 `~/ProjectMosire`），**没跑过**。
3. **`GET /api/approvals/{id}` 的实际行为**：静态读 `ApprovalHttpEndpoint.java:146-154` 显示 `/{id}` **要求 POST**（`requireMethod(exchange, "POST")`）⇒ GET 应得 405。**未运行验证**（我没发过这个请求）。因此 §〇 把"23"里的 2 条审批 GET 计入**是对账推算**，不是实测。
4. **计划里的"23"的原始出处**：我**没有找到**它引用的类/文件（我只在 `docs/superpowers/plans/2026-09-22-tool-surface-plan.md:23` 读到"GUI **23** 个端点"这个数字本身）。我的 13+3+2+5 对账是**我的解释**，不是读到的口径 ⇒ 请当作**待确认**。
5. **"没有扫到"的范围声明**（"我没搜到" ≠ "不存在"）：
   - `git grep -n "listTools()" -- simos-app/src/test/java/` ⇒ 只有 `McpPortTopologyTest.java:180` 与 `McpServerTest.java:169`（rc=0）。**范围 = 已入库的 `simos-app/src/test/java/`**。
   - `git grep -n "listTools\|toolNames" -- .../McpCoverageTest.java` ⇒ 无输出（rc=1）。同上范围。
   - `git grep -n "String NAME = " -- simos-app/src/main/java/io/mosire/simos/app/tools/` ⇒ 16 个命中（9 读 + 7 写在此目录；另 2 条 GM 窄写 `sd.IssueDirective`/`sd.SubmitVerdict` 与 `sd.SetViewScope` 也在其中，总数以命中为准）。
   - ★ **`git grep` 默认只看已入库文件**；本 worktree 里若有未入库的新文件，**上列结论都不适用**（本机 `grep` 是 ugrep，默认尊重 `.gitignore` 且跳隐藏目录 ⇒ 我**没用**裸 `grep` 下任何全仓结论）。
6. **所有运行期行为**：本报告**没有起过服务、没有发过一个 HTTP 请求、没有跑过 Maven/node/npm**（遵守只读约束 + 本机 `nproc=2` 且另有实现者在跑构建）。§一/§二 的所有形状与状态码陈述均为**静态读码，未运行验证**。
7. **前端 JS 的读写白名单**（`webui/modes.js` 的 fail-closed 白名单）是否受 M4 影响：**未查**——M4 是读工具面，我判断不改 JS；但若 M4 顺带改 GUI 视图，workbench 白名单与 `workbench-write-calls-are-all-whitelisted` 派生式扫描需要另核。
8. **`SimosToolsTest.java:343` 的 `subList(9, 16)`**：我只读到它**存在**且是切片形式；「插进前 9 位会怎样」是我的**推导**（M1 裁决 C 的同一根因），**未运行验证**。
