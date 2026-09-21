# WebUI 阶段修复 —— 设计（stage fix）

> 状态：**待用户裁决**——`[待裁]` 项**集中列在 §〇.2 一张表**（每项带**默认建议 + 理由**）；其余各节均为**已裁定**（用户原话 / 已核实事实）。★ 2026-09-21 修订：**D2**（端口"加"）与 **D12**（扩导入器搬 legacy 连通性）**已由用户裁定** ⇒ 移入 §〇.1，`[待裁]` 表剩 **11** 项。
> 作者：控制器（AI 代笔）。日期：2026-09-21。仓库：`feat/adr1-core-scope`。
> 前置依据：`2026-09-21-webui-stage-fix-brainstorm.md`（**用户原话 R1~R13 + 用户裁定**）、`2026-09-21-webui-stage-fix-research.md`（**只读调查结论，§A~§E**）、`CLAUDE.md`（五条铁律 / 模块依赖硬约束 / 纪律 / 门禁口径）。
> 本文是**设计**，不含 bite-sized 步骤；计划见 `docs/superpowers/plans/2026-09-21-webui-stage-fix-plan.md`。
> ★ **本文不修改** brainstorm / research 两份既有文档；本阶段**新增的已核实事实**（MCP 端口拓扑、`v17levant` 世界）写在本 spec，标注为「**补充**」。

---

## §〇 裁定表

### 〇.1 已裁定（用户原话 / 已核实事实，逐条）

> ★ **编号说明**：`R1~R7` 沿用 **brainstorm §六「用户裁定」** 的编号（R1 下挂 / R2 对 / R3 `/api/approvals` / R4 两子页 / R5 三件事 / R6 读法 B / R7 富世界）。brainstorm §一 的其余原话（撤审批 / GM 界面 / 时间线 / 开始决策 / 决策人交互）**在 §六 没有独立 R 号**，本表另起 **`P1~P5`** 标之，**不与 §六 的 R 号混用**。★ `A`/`A′`/`A″`/`B` 是本阶段**新增的已核实事实**（补充）；★ **`D2`/`D12`** 是本次（2026-09-21）由用户**新裁定**、从 §〇.2 移入者。

| # | 裁定 | 来源 |
|---|---|---|
| **R1** | ★ **下挂**：地图编辑模式**下挂二级子选项**（**地形** / **连通性**）。地形 type 与道路/河流（连通性）**不是同一个东西** | 用户原话（brainstorm §一.1 + §六 R1「1，下挂」） |
| **R2** | ★ **对**：GSimulator 的连通性编辑**只搬"手势 + 数据结构语义"**、**simos 侧重写**；★ **绝不能**照搬旧仓 `MapDiff` **不携带 `edges`** 的缺陷——**铁律 5 的往返守卫必须覆盖连通性** | 用户原话（§六 R2「2，对」）+ research §A.4 隐患 3 |
| **R3** | 后端 `/api/approvals` **建议保留**（供 MCP/外部 + Java 测试已覆盖）；**前端右栏的审批计数界面撤掉** | 用户问"这是什么意思"（已解释）+ research §B.2 |
| **R4** | ★ **一个模式（模式名 = 「决策」）下挂两个子页**：子页 A「决策人查看」/ 子页 B「审批」（子页命名可再议，见 §〇.2-D4） | 用户原话（§六 R4「4、两个子页面吧」） |
| **R5** | ★★ **三件事三层权限**（本阶段最关键模型，用户亲自纠正）：见 §四.1。**「建议」≠「开始决策」≠「决策人出令」**；★ **决策人 Agent 是异步的、不参与"开始决策"的抢占**——用户澄清"这里的 Agent 指 **GM Agent**" | 用户原话（§六 R5） |
| **R6** | ★★ **读法 B**：**现有 `--mcp-port` 扩大为 GM 面**（挂 GM 工具）；**外部子 Agent 要作为决策人 ⇒ 另开一个端口**（新 MCP server，挂决策人窄工具）。★ `MCP_SERVER_NAME` 同名**不冲突**（只是 serverInfo 字符串） | 用户原话（§六 R6「BBB」）+ 补充事实 A |
| **R7** | ★ **富测试世界**三条：① **复刻 `v17levant` 的地图（含地形 + 河流；★ 该档道路为空，见 §五.4）与区域信息**；② **把其文档整合成 `.md` 文件**供另一个 Agent 阅读；③ ★★ **绝不直接录 `Info`**（丰富文本**不进** `InfoSystem`/`SdInfoEntry`）；④ ★ **`--demo` 就是正式地图、升级为富世界**——**不另开 `--world rich`** | 用户原话（§六 R7 三条）；★ 源世界名**已更正**：不是 `logdemo`（控制器早期记错），是 `v17levant` |
| **P1** | ★ **撤掉右栏审批计数界面**（`index.html:210-211` + `app.js:368-386`），改到**右下角简单通知栏**；★ **后端 `/api/approvals` 保留** | 用户原话（brainstorm §一.2）+ research §B.2 |
| **P2** | ★ **底栏加按钮 ⇒ 全屏 GM 交互界面**；★ **一阶段只显示 GM MCP 的工具使用**（**不做对话**），GM 工具由**外部 Agent 接入**担任 | 用户原话（brainstorm §一.5/§一.6） |
| **P3** | ★ **时间线语义修正**：**推进一 tick ≠ 每 tick 都生成决策**；推进后在决策模式的列表里显示**哪些决策人理论上有待决事项** | 用户原话（brainstorm §一.4） |
| **P4** | ★ **"开始决策"的按钮在 GM 系统**（**用户操作优先、GM Agent 其次**）；★ 「开始决策」的权限/触发模型见 §四.5 | 用户原话（brainstorm §一.4 + §六 R5） |
| **P5** | ★ **决策人模式交互**：国家 ⇒ 地图高亮该国区域、选中区域 ⇒ 左栏显示该国决策人信息；单位 ⇒ 选中**有决策人的单位** ⇒ 左栏显示其决策人信息；**右侧显示全部决策人列表、按类型分类排列** | 用户原话（brainstorm §一.3） |
| **A** | ★★ **补充事实 A：单端口按 caller 分桶在技术上做不到**（三条实测依据见 §二.1）⇒ 读法 B 是**唯一可行方案** | 控制器只读实测（本任务新增，已核实） |
| **A′** | ★★ **补充事实 A′：全仓没有真正的多用户认证**（`AccessToken` 三值枚举、无 token 表、MCP/审批面均"无鉴权"）⇒ **两个口之间的边界是端口级、不是认证级**——"谁知道端口"就是权限边界 | 控制器只读实测（本任务新增，已核实） |
| **A″** | ★ **补充事实 A″：审批的 `callerKey` = `AccessToken` 桶名**（GUEST/DEFAULT/SYSTEM），`requesterId` = `AgentIdentity.instanceId`；**会话放行只按 `callerKey`（桶）**，`AutoApproveGate` **看不到 `requesterId`** ⇒ GM 与决策人 Agent 要在审批层区分，**必须靠"不同端口 + 不同桶/身份"**，否则记为已知限制 | 控制器只读实测（本任务新增，已核实） |
| **B** | ★★ **补充事实 B：`v17levant` 世界的位置与形态**（三份、非 `~/DevMosire/GSimulator`）；**精确计数**见 §五.4 | 控制器只读实测（本任务新增，已核实） |
| **D2** | ★★ **已裁定（用户原话「加」）**：现有 `--mcp-port` 扩大为 GM 面 = **"加"**（**GM ∪ EXTERNAL**，**保留** `simos.command.submit`/`advance`/`fork`）；★ 与 **N9** 的冲突**显式留痕、不悄悄覆盖**——见 §二.5 | 用户裁定（原话「加」）；原 §〇.2 待裁项，本次移出 |
| **D12** | ★★ **已裁定（用户原话「是的」）**：**扩导入器** `tools/gsimap_import.py`，把 legacy 连通性（`edgeTags`/`riverMask`）**忠实搬到** simos `edges`；★ 映射是 **`edgeTags[dir] → EdgeRef` 的忠实转换，不是有损猜测**；`riverMask` 与 `edgeTags` 计数相同 ⇒ 以 `edgeTags` 为准、`riverMask` 仅作交叉校验；区域信息同由导入器生成 | 用户裁定（原话「是的」）；原 §〇.2 待裁项，本次移出 |

### 〇.2 `[待裁]` 集中表（★ 供用户一次性裁；每项给默认建议 + 理由）

> 编号 `D1…D13` 只在本 spec 与计划内引用，**不与 R1~R13 混用**。

| # | 待裁问题 | **默认建议** | 理由 |
|---|---|---|---|
| **D1** | R3：`/api/approvals` 后端保留还是收掉？ | **保留**（只撤前端右栏计数） | 用户对"保留"未反对；Java 侧 `ShellApprovalTest` / `GuiApiTest` 覆盖它；通知栏（新）与外部 MCP 都还要用它 |
| **D3** | 决策人 MCP server 的**默认端口** | **`5717`** | 现有缺省：GUI 5711 / 审批 5713（恒回环）/ MCP 5715，避开已用端口；`port=0` 随机端口继续支持（测试用） |
| **D4** | 决策模式两个**子页命名** | 子页 A「**决策人查看**」/ 子页 B「**审批**」 | 照用户原话（§六 R4）；用户已说"具体命名待定" |
| **D5** | "开始决策"的**入口形态**：① 新 `sd.StartDecision` 命令（走 `Command→ChangeSet→Revision`、落 revision）；② GUI 专用端点在事务外触发（不落 revision） | ★ **① 新命令** | **铁律 2**：不存在绕过 `Command→ChangeSet→Revision` 的写入口；"开始决策"改变世界语义（授权/发起），必须落 revision 才可回放/可回退分岔 |
| **D6** | "开始决策"的 **GM Agent 路径**：并发抢占 vs **审批门链** | ★ **审批门链**：GM Agent 发起 ⇒ 排到**用户审批**（`ToolCallAuthorizer` + `Ask` 现成）；用户经 GUI 点 ⇒ **直接生效** | 用户说"用户操作优先、GM Agent 其次"；审批链（M5 T6）是现成的、经端口/桶可区分（A″）；真并发抢占没有现成设施、且会引入竞态 |
| **D7** | P3 的"**待决/建议**"信号判据 | **`due = (当前 tick − 该 dm 最近一次落 `Directive` 的 tick) ≥ decisionCadenceTicks`**；首次（无任何 Directive）恒 `due` | `decisionCadenceTicks` 是**只写字段**（`DecisionMaker.java:19`，全仓无计算）；`Directive` 的 `(dm, tick)` 唯一不变量（R4）是**唯一机器可判的既有事实**（research §C.6）；两者相减即"理论建议" |
| **D8** | R6 权限：现有口扩大后，**GM 桶与外部桶如何并存**（工具面语义） | 现有口工具面 = **EXTERNAL 桶 ∪ GM 桶**（读工具共享、写面相加）；决策人口 = **仅 DECISION_AGENT 桶** | D2 的"加"；`Role` 是单值枚举 ⇒ 需**新增一个复合桶**（或等价物），见 §六.4 |
| **D9** | research §C.7 的 **redaction 洞**：修 / 记为已知限制？ | ★ **本阶段修**（范围：`adjudicationDisclosure` + `redactedFields` 真正生效；**除 overview/units 外的所有读端点在带 `as=` 时也走 `RedactingQueryService`**）；未覆盖的端点**逐一列出**并 fail-closed | 这是**可见性正确性漏洞**（SDSimos spec 的 R10/N6 的核心承诺），不是可选优化；research §C.7 实测"被存却从未应用" |
| **D10** | 通知栏落点 | **`position:fixed` 右下角浮层**（不占右栏、不挡底栏时间线） | 用户说"右下角的简单通知栏"（brainstorm §一.2）；M7g 全屏底图已有 `wb-overlay` 浮层机制（research §B.5） |
| **D11** | 富世界**文档产出**的路径与文件划分 | 目录 `docs/worlds/v17levant/`；按 checkpoint 类别分文件：`factions.md` / `worldview.md` / `narrative.md` / `map.md` / `characters.md` / `internal.md`（★ 后两类是 `v17levant` 实测多出的：`characters 2` / `internal 67`）；★ **只写 md，绝不录 Info** | 用户要"整合成 md 文件方便另一个 Agent 阅读"；按 checkpoint 类别天然分文件（实测 6 类，见 §五.4） |
| **D13** | 决策人信息（左栏）的**数据结构** | **复用现有字段**：`DecisionMaker`（id / affiliation / allowedTools / viewScope / decisionCadenceTicks）+ 由 affiliation 解析出的国家/军队显示名 + **待决状态**（D7）；**不新增 sd 字段** | 左栏要显示的东西现有数据都够（research §C.1/C.3）；新增字段要动 `SdChangeSet`/往返守卫，代价高于收益 |

★ **待裁项合计**：上表共 **11** 项（`D1`、`D3`~`D11`、`D13`），供一次性拍。★ **`D2` 与 `D12` 已于 2026-09-21 由用户裁定并移入 §〇.1**（分别是"加"与"扩导入器搬 legacy 连通性"）；编号**不重排**，以免既有引用漂移。

### 〇.3 写作纪律与来源标注

- 本文每条涉及既有代码的断言都标 `文件:行`；**未实测的一律写"未核实/推断"**（见 §八）。
- 术语沿用 brainstorm / research：**连通性 = 拓扑（`edges`/`edgeTags`/`riverMask`，融合优先级见 §五.2）**，**地形 = 属性（`HexCell.terrain`）**；**决策人 = `DecisionMaker`**（国家/军队两种归属）；**建议 ≠ 开始决策 ≠ 出令**。
- 门禁口径照 `CLAUDE.md`（rc=0 / 模块 `SUCCESS [` / `[ERROR]` 0 / `BugInstance size is 0` / 前端下界两处同改）。

---

## §一 阶段定位与不做什么

### 一.1 定位

本阶段**不是"继续加功能"**，而是**修正已交付物的形态错误**（brainstorm §四）。三件形态错误：① 地图编辑把"属性编辑"与"拓扑编辑"混成一个模式；② 审批 UI 超出派单范围被顺手做进右栏；③ 决策人/GM 这一整块**从来没有 UI**（SDSimos 做的是能力，界面缺席）。

### 一.2 不做什么（明确边界）

- **不做对话式 GM 界面**：一阶段 GM 界面**只显示 GM MCP 的工具使用**，不接 LLM 对话（用户原话，P2 已裁）。
- **不做真正的多用户认证**：端口级边界即是本阶段的权限边界（A′），不引入 token/账号体系。
- **不重做时间线**：M7f 的"一 tick 一节点"**分组语义保留**；本阶段只**修正读法**——推进**不自动产生决策**（P3）。
- **不改五条铁律、不改模块依赖硬约束、不新增模块**。
- **不把丰富文本录进 `InfoSystem`/`SdInfoEntry`**（用户 R7 硬要求）。

---

## §二 端口拓扑与权限模型

### 二.1 补充事实 A：**单端口按 caller 分桶做不到**（三条实测依据）

1. **AgentLib 的工具表是 per-server**：`AgentToMcpServer.startHttp` 吃**一个** `ToolRegistry`（`Shell.java:384-393`），`assemble()` 一期建表，`tools/list` 返回 `server.listTools()` 的**同一份**；**caller 身份是建链时绑死的**（`startHttp(..., ToolContext caller, ...)`），`handleCall` 对所有连接用**同一模板**。
2. **协议层只读 `Accept` + `Mcp-Session-Id`**，没有任何区分连接者的凭据；SDK 的 `McpSyncServer` **只有一份 `listTools()`**（无 per-session 表）⇒ "同口同路径按身份给不同表"是 **SDK 模型层的墙**，不是加形参能解决。
3. `include: Predicate<String>` 只存在于 **stdio 族**重载；`startHttp` **写死 `name -> true`**。

⇒ ★ **用户裁定的读法 B（R6）恰好是唯一可行方案**：现有 `--mcp-port` = GM 面；决策人另开端口。**零 AgentLib 改动**，做法 = 各建一个 `ToolRegistry`（`new SimosToolSource(..., Role.GM)` / `Role.DECISION_AGENT`）+ 各配 `ToolContext` + 两次 `startHttp`。**同名 `MCP_SERVER_NAME` 不冲突**（只是 serverInfo 字符串）。

### 二.2 拓扑（★ D2 **已裁**="加"；D3/D8 仍待裁）

```
        ┌───────────────────────────────────────────────────────────────┐
        │  GUI 5711（唯一对外面；/api/* + 静态页）                        │
        │   · 通知栏读 /api/approvals（透传）                             │
        │   · 决策人查询面 /api/sd/...（新）                              │
        └───────────────────────────────────────────────────────────────┘
                 │(1) 内网回环
        ┌────────┴─────────┐        ┌──────────────────────┐
        │ 审批端点 5713     │        │  GM MCP 5715（现有）  │← 外部 GM Agent
        │ 恒回环（AgentLib）│        │  桶 = EXTERNAL ∪ GM  │
        └──────────────────┘        └──────────────────────┘
                                     ┌──────────────────────┐
                                     │ 决策人 MCP 5717（新） │← 外部决策人子 Agent
                                     │ 桶 = DECISION_AGENT  │
                                     └──────────────────────┘
```

### 二.3 ★ 权限边界 = **端口级，不是认证级**（限制，必须写明）

- **全仓没有真正的多用户认证**（A′）：`AccessToken` 是三值枚举、无 token 表、MCP/审批面均"无鉴权"。
- ⇒ **谁连得上 5715 就有 GM 桶的工具，谁连得上 5717 就有决策人桶的工具**。"知道端口"就是权限边界。
- ⇒ 这在**内网/本机**语义下可用；**不得**把本阶段读成"两个身份之间安全隔离"。若日后要真隔离，需先引入 token/账号（**不在本阶段**）。

### 二.4 审批层的区分（A″）

- 请求字段：`id, tool, classKey, summary, digest, createdAtEpochMs, deadlineEpochMs, callerKey, requesterId, kind, goal`（research §C.5）。
- `callerKey` = `AccessToken` 桶名（GUEST/DEFAULT/SYSTEM）；`requesterId` = `AgentIdentity.instanceId`。
- **会话放行只按 `callerKey`（桶）**，`AutoApproveGate` **看不到 `requesterId`**（research §C.5）⇒ 想区分 GM 与决策人，**必须靠"不同端口 ⇒ 不同 `ToolContext` ⇒ 不同桶/身份"**；否则在 spec 记为**已知限制**（默认按 D6 两端口分桶满足区分）。

### 二.5 与既有能力的关系（★ 含 N9 冲突的**显式记账**）

- 现有口"加"GM 桶（**D2 已裁="加"**，见 §〇.1）：**通用写仍在该口**（`simos.command.submit` / `simos.advance` / `simos.fork`），GM 窄工具（`sd.IssueDirective` / `sd.SubmitVerdict` / `sd.SetViewScope`）**新增**其上。⇒ `McpCoverageTest` 的通用命令路径不变。
- 决策人口（新）：**只有 DECISION_AGENT 桶**（`sd.IssueDirective` / `sd.SubmitVerdict`；**无** `SetViewScope`、**无**通用写）。
- ★★ **D2 ↔ N9 的冲突（留痕，不是新裁定）**：SDSimos 既有裁定 **N9** 的原文口径是「**专用窄工具，不给决策 Agent 通用 `simos.command.submit`**」（`2026-09-20-sd-simos-design.md:41`）——而 **D2 把现有 MCP 口定为 `EXTERNAL ∪ GM` 并保留通用写**。两者**在此端口上冲突**。
  - **口径**：★ **D2 以用户裁定为准；N9 就现有 MCP 端口不适用**。
  - **后果（写明）**：⇒ **现有口（5715）的持有者可直接提交任意命令、绕过 GM 窄工具**（`simos.command.submit` / `advance` / `fork` 全在）。这**不是缺陷**、是用户裁定的取舍；但它**推翻了 N9 在该端口上的原意**，故必须记账，**不许悄悄覆盖**。
  - **N9 仍然有效之处**：**决策人口（5717）**与 **`DecisionMaker.allowedTools` 白名单**（`CreateDecisionMakerHandler` 拒通用写）**不受影响**——N9 在那些面上照旧。

---

## §三 编辑线（地形 / 连通性）

### 三.1 两条线是两个概念

| 线 | 对象 | simos 数据 | 编辑手势 |
|---|---|---|---|
| **地形（属性）** | 每个 hex 的 `terrain` type | `HexCell`（`GameMap.hexes`） | 笔刷涂抹（右键拖动） |
| **连通性（拓扑）** | hex 与 hex 之间的边 | `GameMap.edges`（`Map<EdgeRef, EdgeTags>`）+ `pathwayGroups` | 画边/删边（见 §三.3） |

★ 旧仓 GSimulator 在**物理上就是两套字段**（research §A.1：`edges`/`pathwayGroups` 顶层字段 vs `HexCell` 的 `terrain`），工具栏也是**独立按钮 + 互斥 tool 状态**（research §A.2）。本阶段只是把 simos 的 UI 形态对齐这个既有事实。

### 三.2 子选项形态（R1）

- **模式名不变**：`map-edit`（「地图编辑」，`modes.js:24` / `index.html:21`）。
- **面板下挂二级子选项**（默认建议）：
  - 「**地形**」：地形调色板（`#terrain-palette`），现有 `map.SetTerrain` 笔刷；**圈选随机化**（`map.RandomizeRegion`）归此子选项（它改变地形构成）。
  - 「**连通性**」：kind 选择（**河流** / **道路**，★ 候选来自**已注册的 `PathwayGroup` 集合**，见 §三.6）+ 语义选择（`merge` / `replace`，现 `#edge-mode`）；现有 `map.SetEdge`。
- **写白名单不变**（`modes.js:29`）：`map.SetTerrain` / `map.SetEdge` / `map.RandomizeRegion` / `map.UpdateRegion` —— 子选项只是**面板分组**，**不新增模式**、**不新增本模式的编辑命令**（★ 组注册命令 `map.RegisterPathwayGroup` 见 §三.6，**不属**本模式 UI 写面）。
- ★ **子选项命名/粒度** `[待裁]`：默认「地形」/「连通性」两项（randomize 归「地形」）。

### 三.3 连通性手势（照搬 GSimulator，R2）

逐条搬 GSimulator 的**手势**（research §A.2，锚 `js/pathway.js` / `js/events.js`）：

1. **右键拖拽 = 画边**；
2. **左键点/拖 = 删边**；
3. **起点 → 续点 → 同格或非相邻 = 结束**（waypoint 状态机）；
4. **12px 命中阈值**（`findSegmentAtPixel`）；
5. **缺格自动建**（原为自动建 hex；simos 侧若目标格不存在则**跳过**而非静默建，见下）；
6. ★ **双向写边**（`EdgeRef` 顺序无关，`edgeKey = "minQ_minR|maxQ_maxR"` 语义）。

★ **simos 侧的三处就地校正**（"重写"而非照抄，R2）：

- **缺格自动建**：GSimulator 会在画边时**自动创建 hex**；simos 的 `GameMap` 有**地形分割不变式**（`TerrainBlocks.requirePartition`，`GameMap.java:89`）⇒ 自动建格会牵动 `terrainBlocks`。**默认**：目标格不存在 ⇒ **不写该边、给可见提示**（不静默建格）；是否恢复"自动建格" `[待裁]`（并入 D14，见下）。
- **后端相邻校验**：GSimulator 后端**不校验相邻**、MCP 可连非相邻格（research §A.3，真缺口）。simos 侧**默认**在 `map.SetEdge` handler 里**校验六邻相邻**，非相邻 ⇒ 拒绝（fail-closed）。是否加此校验 `[待裁]`（并入 D14）。
- **没有自动生成**：GSimulator 没有"河流由高度场生成"（research §A.3）；本阶段**不做**河流自动生成。

### 三.4 ★ 往返守卫必须覆盖连通性（铁律 5）

- **现状（已核实）**：simos 的 `MapChangeSet` **8 个组件里已含 `edges`**（`FieldDelta<EdgeTags> edges`，`MapChangeSet.java:56`），`GameMap` 9 组件含 `edges`（`GameMap.java:69`）⇒ **结构上已覆盖**。
- **旧仓缺陷**：GSimulator 的 `MapDiff` **不携带 `edges`/`pathwayGroups`**（research §A.4 隐患 3）⇒ 非根节点上的连通性写入**不可表达、静默丢失**。★ **本阶段绝不复刻**。
- **本阶段要求**：新增/改造连通性写路径时，**必须**有一条**非空 `edges` 的往返用例**（`apply(between(base,target), base)` 逐字段重建 `target`），且该用例**能被变异杀掉**（去掉 `edges` 组件 ⇒ 红）。若既有 `RoundTripComponentsTest` / `EdgeOperationsTest` 已覆盖非空 `edges`，**引用并补证**，不重复造。

### 三.5 写命令语义（`map.SetEdge` 既有不改；组注册见 §三.6）

- 地形：`map.SetTerrain`（`SetTerrainHandler`）。
- 连通性：`map.SetEdge`（`SetEdgeHandler`），载荷含 kind + mode（`merge`/`replace`）+ hex 对；`merge`/`replace` **必须显式**（M8 Q2）。
- ★ **不新增"改单条边"的命令类型**（`map.SetEdge` 仍是唯一改边命令）；本阶段只改**子选项 UI 分组**与**手势**。★ **例外**：§三.6 的"词表可自定义"要新增一个**组注册**入口（`map.RegisterPathwayGroup`，改的是**组定义**、不是边）——两者分开。

### 三.6 ★★ 连通性词表 = 默认 + 可自定义（用户裁定）

- **现状（实测）**：`EdgeOperations` **硬编码** `private static final Set<String> KINDS = Set.of("river", "road")`（`EdgeOperations.java:37`），注释在 `:26` 写"词表固定为 `river`/`road`"⇒ **每加一种通路都得改代码**。
- ★★ **用户裁定（原话「对，需要改成默认+可自定义」）**：**不再硬编码** ⇒ **默认提供 `river`/`road` 两组**，同时**支持任意自定义通路类型**。
- **设计（从 `PathwayGroup` 派生，而不是代码常量）**：
  - `PathwayGroup` **已是现成类型**（`PathwayGroup.java:23`）；`GameMap` 已有组件 `Map<String, PathwayGroup> pathwayGroups`（`GameMap.java:68`，含 `withPathwayGroups` `:153`）；`MapChangeSet` 已有 `FieldDelta<PathwayGroup> pathwayGroups`（`MapChangeSet.java:55`）⇒ **数据结构齐备，只差接线**。
  - `EdgeOperations.KINDS` 改为**从状态里的组集合派生**（`base.pathwayGroups().keySet()`）：`kind` 校验 = "是**已注册组**的 id"（大小写归一照旧）；**未注册 ⇒ 仍 fail-closed 拒绝**。
  - **组定义由 Command / 上层供给**（既有口径：`RiverBuilder.java:54`「组定义由 Command / 上层供给（M2 台账挂起项）」）。⇒ 新增**组注册入口**，**默认**新命令 **`map.RegisterPathwayGroup`**（载荷 = `PathwayGroup` 的字段；构造期守卫照 `PathwayGroup` 既有：id/name 空白即抛、color 须 `#RRGGBB`）。
  - **默认组**：genesis / 导入器提供 `river`/`road` 两组（与现状等价）⇒ **既有用例与真档不受影响**。
- ★ **本项目那条通则的又一例**：**"声明式清单不随注册面自动延伸"**——硬编码 `KINDS` 就是"每加一种通路都要改代码"；改成从注册面派生后，加一种通路**只需发一条注册命令、不再改代码**。
- ★ **与 `SetEdge` 的关系**：`map.SetEdge` **本身不变**（仍是唯一改边命令，`merge`/`replace` 仍必须显式）；变的只是它的 **`kind` 校验来源**（硬编码词表 ⇒ 已注册组集合）。

---

## §四 决策模式与三件事模型

### 四.1 ★★ 三件事三层权限（用户亲自纠正的模型，R5）

```
① 推进一 tick（世界前进，与"决策"无关）
② 服务端算出【本 tick 哪些决策人理论上有待决事项】   ← 现在不存在，必须新造（§六.2）
③ 列表显示（决策模式右栏）
④ 「开始决策」按钮在 GM 系统 ← 用户点 = 直接生效；GM Agent 点 = 过审批门链
⑤ 决策人 Agent 被发起后【异步】出令（经渠道提交）
⑥ 结果落 revision
```

- ★ **「建议」≠「开始决策」≠「决策人出令」**——**三件事、三层权限**。
- ★ **决策人 Agent 是异步的、不参与"开始决策"的抢占**（用户澄清）；参与"开始决策"抢占的只有 **GM Agent**（"其次"于用户）。
- 现有实现面：②**不存在**（`decisionCadenceTicks` 只写字段、`AdjudicatorRunner.run` 的 `due` 无人计算且未接入 `Shell`，research §C.6）；⑤的渠道**有抽象、无入口**（`DecisionChannel` 四实现只被测试调用，research §C.8）；⑥落 `sd.*` revision（现成）。

### 四.2 一个模式、两个子页（R4）

- 新增**第六个模式**：id `decision`，label「决策」。
- **两个子页**（模式内切换，不是两个模式）：
  - 子页 A「**决策人查看**」：P5 的三处交互（§四.3）。
  - 子页 B「**审批**」：`/api/approvals` 的列表 + 批准/驳回（后端已具备；前端新建）。
- ★ 新增模式要改的 9 处照 research §B.1（`modes.js` / `index.html` 按钮 / `<section data-modes>` / `map.js` 若改地图行为 / `styles.css` / `WebuiAssetsTest.java` / `modes.test.cjs` / `gate-contract.test.cjs` 两处 / `run-gate.cjs`）。
- ★ **写白名单**（`modes.js` 的 `writes`）：决策模式默认 **`[]`**（查看 + 审批走 `/api/approvals`，不是命令写）；若 D5 选"新命令"，则加 `sd.StartDecision`。

### 四.3 决策人模式交互（P5）

- **国家**：选择某个国家 ⇒ **地图高亮该国区域**（该国 = 带某 `tag` 的 Region 集合，R13 既有语义）⇒ **选中区域 ⇒ 左栏显示该国决策人信息**。
  - 国家 → 区域集合的映射：由区域的 `tag`/`annexedBy` 等 `RegionMeta` 决定（`RegionMeta(String color, String tag, String description, String annexedBy)`）。★ 本 spec 的 `R13` 引用的是 **SDSimos spec §〇.1 R13**（"Nation 区域" = 带特定 `tag` 的 Region），**不是** brainstorm 的 R 号。
- **单位**：选中**有决策人的单位** ⇒ 左栏显示该单位决策人信息。
  - ★ **"单位决策人" = `Affiliation.Army`**（`Army` 才带 `rootUnit: UnitId`，`Affiliation.java:34` / research §C.1）——**没有直接指向 `UnitId` 的第三归属**。国家决策人通过其下 `Army.rootUnit` 间接关联单位。
- **右侧**：**全部决策人列表、按类型分类排列**（类型 = 归属种类：国家 / 军队；建议再按 id 字典序，Q6 口径）。
- **左栏字段**（D13 默认）：id / 归属（国家或军队 + 显示名）/ `decisionCadenceTicks` / `allowedTools` / viewScope 摘要（可见区域数/hex 数/单位数/`seeOwnUnits`/`adjudicationDisclosure`/`redactedFields`）/ **待决状态**（D7）。

### 四.4 时间线语义修正（P3）

- **现状（已核实）**：M7f 的"一 tick 一节点"是**分组语义**，推进 = **显式点击** `#timeline-create`；**没有自动发命令、没有自动推进**（research §B.4）⇒ 用户说的"推进即决策"是 **UX 语义**，不是行为。
- **修正**：推进按钮文案/交互**明示"推进 ≠ 决策"**；推进后**不**自动在时间线上生成"决策"标记；"哪些决策人待决"显示在**决策模式的列表**（§四.5），不在时间线。

### 四.5 "开始决策"的权限与入口（P4，D5/D6）

- **用户**：在 GM 系统里点「开始决策」⇒ **直接生效**（GUI 的 `initiator="player:gui"`）。
- **GM Agent**：经 **GM MCP 口**（现有 `--mcp-port`，D2）发起 ⇒ 走**审批门链**（`Ask(SENSITIVE)` 现成），用户批准后才生效。
- **决策人 Agent**：**不参与**"开始决策"；它只被**异步**发起后经渠道出令（§六.3）。
- **落点**：默认新命令 `sd.StartDecision`（D5），落一条 revision（授权/发起的事实）；其后的"出令"经 `sd.IssueDirective` / `sd.SubmitVerdict` 落各自 revision。

---

## §五 富世界与文档产出

### 五.1 `--demo` 升级为富世界（R7④）

- **不新增 `--world rich`**；`--demo`（`ShellMain.java:70`）首启种入的世界**升级为数据丰富的正式地图**。
- 现有 `DemoWorld` 是 3 格沙漠走廊 + 1 个单位 + 1 条人口（`DemoWorld.java`），用户评价"测试世界太不完善"。
- ★ `bootstrapGenesis` 是**唯一绕过 `submit` 的写路径、只在空库**（CLAUDE.md M5 行）；富世界仍在**空库首启**种入，不覆盖已有世界（`ShellMain.java:105`）。

### 五.2 复刻 `v17levant` 的地图与区域信息（R7①）

- **来源**（补充事实 B，实测）：★ **不是 `logdemo`**——那是控制器早期**记错**（已更正）；★ 目录名是 **`worlds`（复数）**，`world/`（单数）**不存在**。**三份副本**：
  - `~/DevMosire/workspace/worlds/v17levant` —— 节点 `n0000`~`n0008`；
  - `~/DevMosire/workspace/worlds/v17levant_2` —— 节点 `n0000`~`n0008`；
  - ★ **`~/DevMosire/testspace/worlds/v17levant_2`（信息最全：节点到 `n0012`，多出 `n0009`~`n0012`）**；★ 同树（`~/DevMosire/testspace/`）另有 **`caches/`**，存**决策原文**（`Orchestrator_*.json` + 按势力命名的 `*.json`），可作文本产出的**交叉来源**（**补充**）。
  - 三份的 `n0000_map.json` **逐字节同源**（均 **12,113,553 B**，`md5=6424529b376882c52838ae901abe98db`，实测）。
- **地图格式是旧的**（实测，`n0000_map.json`）：
  - `hexes` **59223** 个（key 形如 `-54_30`；每格字段 `color/terrain/symbol/symbolColor/description/riverMask/edgeTags`）；
  - ★★ **`edges` 是空的（`{}`，0 条）**——连通性**没有**写进该字段；★ 与 research §A.4 说的"旧仓**代码**里 `MapDiff` 不携带 `edges`/`pathwayGroups`"是**两件独立的事**（见下）。
  - ★ **真实连通性 = `edgeTags` + `riverMask`**：两者各有 **248 / 59223** 格非空（**计数相同**）；**道路为空**（`roads` 空；`edgeTags` 里只有 `river`）。
  - `terrainTypes` 定义 **9 项**，**实际只用到 7 项**（`tundra`/`forest` 是**死条目**，直方图里 0 格）。
  - `provinces` **98** 个（`tag`：**97 个 `"Nation"` + 1 个 `"王国"`**，不是清一色）；`cities` / `rivers` / `roads` / `terrainBlocks` 见 §五.4。
- ★★ **与 research §A.4 的关系（独立的两件事，避免下游误读）**：
  - research §A.4 的「**旧仓 `MapDiff` 不携带 `edges`/`pathwayGroups`**」说的是 **`~/DevMosire/GSimulator` 的代码形态**（已核实是**最新一份代码**）—— **保留**，那是"旧仓写路径会静默丢连通性"的根因、铁律 5 的教训。
  - 本文说的「**`v17levant` 数据里 `edges` 为空**」是**数据事实**：该档的连通性**从没写进 `edges` 字段**，只留在 `edgeTags`/`riverMask` 里。⇒ **代码有 `edges` 字段、数据里 `edges` 空**，两件独立的事，**不要互相推断**。
- **导入路径**（**D12 已裁**，见 §〇.1）：**扩 M6 导入器**（`tools/gsimap_import.py`）把 legacy 连通性**忠实搬到** simos `edges`；区域信息同由导入器生成。
  - ★ 映射是 **`edgeTags[dir] → EdgeRef` 的忠实转换、不是有损猜测**：`edgeTags` 的方向码 `0`~`5` 按旧仓 `edgeKey` 语义解析成相邻 hex 对，每条得一个 `EdgeRef`，`kind="river"`。
  - ★ `riverMask` 与 `edgeTags` 计数相同（均 248）⇒ **以 `edgeTags` 为准**，`riverMask` **仅作交叉校验**（两者不一致 ⇒ 报错，**不静默取一**）。
  - ★ M6 导入器对 legacy 连通性**当前是 fail-closed** ⇒ **必须先扩**；走真命令路径造 ~6 万格在时空上都不现实。
- ★★ **连通性三份表示的融合优先级（本次新裁，结 M6 挂起项）**：**`edges` 权威 > `edgeTags` > `riverMask`**。
  - `edges`（非空）⇒ 以 `edges` 为准，据它重建 `edgeTags`、忽略 `riverMask`（即 research §A.4 说的"正确融合规则"）。
  - `edges` 空而 `edgeTags` 非空（**`v17levant` 正是此形**）⇒ 以 `edgeTags` 为准，`riverMask` 仅交叉校验。
  - 三者皆空 ⇒ 无连通性。
  - ⇒ M6「连通性三份表示非空即报错」的 fail-closed **就此升级为该优先级**（不再是"非空即报错"）。
- **规模**：`v17levant` 是 **59223** 格（是 simos 现有真档 `test_integration` 的 **19441 格的 3 倍**）⇒ 富世界必须过 M9 的块/渲染性能面（M9 已把 44 块 / 227,377B overview 等能力交付）。

### 五.3 文档产出（R7②③）：★ 绝不直接录 Info

- ★★ **硬要求**：把 `v17levant` 的**丰富文本整合成 `.md` 文件**供**另一个 Agent 阅读**；**绝不**写进 `InfoSystem` / `SdInfoEntry`（`SdInfoEntry` 在 SDSimos 里是 revision 内的数据，本阶段**不碰**）。
- 产出位置（D11 默认）：`docs/worlds/v17levant/`。
- 来源 = 节点 JSON 的 **`checkpoints[].elements[]`**（**不是独立文件、也不在 `description` 字段**：逐格 `description` 非空 **0 / 59223**、区域 `description` 非空仅 **2 / 98**，见 §五.4）。

### 五.4 ★ 补充事实 B：`v17levant` 的精确形态（实测计数，直接写进文档）

> 实测命令：`python3 -c json.load(...)` 于 **`~/DevMosire/testspace/worlds/v17levant_2/nodes/`**（**最全副本**）。

| 项 | 实测值 |
|---|---|
| 规模 | **59223 hex** / **98 区域** |
| `edges` | **`{}`，0 条**（连通性不在该字段） |
| `edgeTags` / `riverMask` | 各有 **248 / 59223** 格非空（**两者计数相同**）；只有 `river` |
| `terrainTypes` | 定义 **9 项**、**实际用到 7 项**（`tundra`/`forest` 为死条目，0 格） |
| 地形直方图 | `lowland 16933 / water 14927 / hills 14107 / plains 11315 / desert 746 / mountain 1096 / swamp 99`（7 类） |
| 文本载体 | 节点 JSON 的 **`checkpoints[].elements[]`**（元素字段 `key/type/value/tags/…`）；跨 `n0000`~`n0012` **共 615 条 / 319,630 字** |
| checkpoint 类别 | `narrative` **170** / `map` **318** / `internal` **67** / `factions` **52** / `worldview` **6** / `characters` **2**（合计 **615**）；★ **`n0000` 是唯一的根**（5 类：narrative 7 / worldview 6 / characters 2 / factions 13 / map 98），`n0008` **无 checkpoint**（空） |
| 有名势力 | ★ **只有 16 / 98 个区域**在 `factions` 里有长篇设定（16 = 全部 `factions` 元素 key ∩ 98 个 province 名，实测交集）；**其余 82 个只有模板化短条目** |
| 逐格描述 | 非空 **0 / 59223** |
| 区域描述 | 非空仅 **2 / 98**（实测为 `大蜀`、`北谷`） |
| 区域 tag | `"Nation"` **97** + `"王国"` **1**（★ **不是**清一色 `Nation`） |

★ **用户口中的"每一个区域" = 那 16 个有名有姓的势力**（其余 82 个是程序化噪声区域、无长篇文本）——这条**必须**写进产出文档，否则另一个 Agent 会以为 98 个区域都有资料。

### 五.5 复刻的取舍（挂起项见 §八）

- `compressedRegions`（纯渲染缓存）、`rivers`/`roads`（旧仓 @Deprecated）、`terrainBlocks`（旧仓 @Deprecated）、`gridSize`：**丢弃**（沿 M6 口径）。
- ★ **地形缺口（本次实测，必须处理）**：**`lowland` 16933 格（28.6%）simos 完全没有对应**；`swamp` 99 格也没有。★ 现导出器 `TERRAIN_MAP` 里 **`lowland`/`swamp` 都 → `plains`**（`tools/gsimap_import.py:39`/`:44`），但 **`LOSSY_KEYS` 只列了 `swamp`**（`:48`）⇒ **`lowland` 今天是"未标 LOSSY 的静默丢失"**，**T11 必须把它补进 `LOSSY_KEYS`**。⇒ **在计划 T11 明确写清映射到哪个现有地形 + 标 LOSSY**；若建议新增地形词表（如 `lowland`），标 `[待裁]` 并给默认（默认：**不新增词表**，`lowland`/`swamp` 一并 lossy → `plains`，如实记录损失）。
- 带洞区域、`edgeTags` 非空：`v17levant` 是**真样本**（`edgeTags` 实测 248 格非空），与 M6 时"无真实样本"相比**这次有样本**。

---

## §六 要新造的查询面 / 信号 / 入口（逐条落点）

> research §D 的 7 项，逐条给**落点**与**是否本阶段做**。

### 六.1 决策人查询面（research §D#1）

- **现状**：**完全没有**。`Shell.java:460-476 currentActorIds` 只全量遍历、**不过滤 affiliation**；`RedactingQueryService.scopeOf` 只按 ID 查（`:47-51`）。
- **落点**：
  - `GuiServer` 新增只读端点（默认）：
    - `GET /api/sd/decision-makers` ⇒ 全部决策人列表（含 affiliation 解析出的显示名、cadence、待决状态 D7）；支持 `?affiliation=nation:<id>` / `army:<id>` 过滤；
    - `GET /api/sd/decision-makers/{id}` ⇒ 单个详情。
  - 可选：新增读工具（`SimosToolSource` 的九条读工具族）——★ 若新增工具要同步 `SimosToolsTest` / `McpCoverageTest` 的工具面断言（**先核实再动**，见 §八）。
- **数据来源**：`SdState.decisionMakers()` / `armies()` / `nations()`（经 `QueryService.stateAt` + `SdSnapshot`）。

### 六.2 "建议/待决"信号（research §D#2）

- **现状**：**不存在**。`decisionCadenceTicks` 只写字段；`AdjudicatorRunner.run(..., due, ...)` 的 `due` 无人计算、且该类未接入 `Shell`（research §C.6）。
- **落点**（D7 默认）：在 app 层（`QueryService` 或新 `SdQueryService`）计算 `due`：
  `due(dm, tick) = tick − lastDirectiveTick(dm) ≥ dm.decisionCadenceTicks`（无任何 Directive ⇒ `lastDirectiveTick = -∞` ⇒ 恒 `due`）。
  `lastDirectiveTick` 扫 `SdState.directives()` 里该 dm 的最大 tick（R4 保证 `(dm,tick)` 唯一，research §C.6）。
- **暴露**：并入 §六.1 的列表/详情响应（字段 `due: true|false`、`lastDirectiveTick` / `ticksSinceLast`）。

### 六.3 "开始决策"的权限与入口（research §D#3）

- **现状**：`AdjudicatorRunner` 未接入 `Shell`；`DecisionChannel` 四实现无任何入站传输（research §C.8）。
- **落点**（D5/D6 默认）：
  - 新命令 `sd.StartDecision`（载荷：目标决策人 id + 可选说明）⇒ 落一条 revision（授权/发起事实）；
  - GUI 端点 `POST /api/sd/start-decision`（经 `CoreSimos.submit`，`initiator="player:gui"`）⇒ 用户路径；
  - GM MCP 口暴露同一命令（GM 桶）⇒ GM Agent 路径，经审批门链（`Ask(SENSITIVE)`）；
  - **异步出令**：本阶段**可只落地"发起"，不实现自动 LLM 出令**——出令仍由外部决策人 Agent 经渠道提交（`sd.IssueDirective` / `sd.SubmitVerdict`）。★ 若本阶段要把 `AdjudicatorRunner` 接进 `Shell`，需先裁决（**挂起**，见 §八）。

### 六.4 GM 桶的传输（research §D#4）

- **现状**：`Shell` 用 5 参构造器 ⇒ 默认桶 = `Role.EXTERNAL`；**GM / DECISION_AGENT 的桶不在任何运行中的 MCP 上**；`Shell.toolsFor(role)` 只被测试调用（research §C.4）。
- **落点**（读法 B）：
  - 现有口：`ToolRegistry` 的内容 = **EXTERNAL ∪ GM**（★ **D2 已裁**="加"；**D8** 复合桶形态待裁）；实现上新增一个**复合桶**（如 `Role.EXTERNAL_WITH_GM`，或让 `SimosToolSource` 接受桶集合）——**具体形态归计划**；
  - 新口：第二个 `ToolRegistry` + `McpSourceBridge.bind(new SimosToolSource(..., Role.DECISION_AGENT), r2)` + 第二次 `startHttp`（新端口 D3）；
  - `ShellConfig` 加 `decisionAgentMcpPort`；`ShellMain` 加 `--decision-agent-mcp-port`（缺省 D3）；`Shell.close()` 关闭次序：新 server 并入现有次序（GUI → MCP → 新 MCP → 审批）。
- ★ **`MCP_SERVER_NAME` 同名不冲突**（只是 serverInfo），但**建议**决策人口用 `simos-shell-decision` 以便区分（可选）。

### 六.5 通知栏（research §D#5）

- **落点**：`index.html` 新增 `position:fixed` 右下角浮层元素 + 新脚本（或并入现有 `panels.js`/`app.js`）；读 `GET /api/approvals`（`api.js:252-254` 已存在）。
- ★ **隐藏断点**：`simos-app/src/test/js/write-allowlist.test.cjs:112` 调了 `api.approvals()`。**只要保留 `api.approvals`**（通知栏要用它），该用例**不红**；若任何变更删掉 `api.approvals`，**必须在同一任务**改这条件例的**被측对象**（换成通知栏模块），**不许偷偷删断言**（`CLAUDE.md` 纪律）。
- **撤除对象**：`index.html:210-211`（`<h3>待批</h3>` + `#approvals-count`）、`app.js:368-386 mountApprovals`、`index.html:246` 的 `boot({approvals:...})`、`app.js:393-395`/`:450` 的挂载与导出。

### 六.6 `adjudicationDisclosure` / `redactedFields` 真正生效（research §D#6，D9）

- **现状（漏洞）**：① 两者被解析、被存，**从未被应用**（只经 `ChannelAdmission.redactedBrief:61-81` 回显）；② 除 `/api/map/overview?as=` 与 `/api/units?as=`（`GuiServer.java:295-305,318-333`）外，**其余端点完全不过 redaction**（`/api/state`、`/api/timeline`、`/api/map/hex`、`/api/unit/{id}`、`/api/social/population`、`/api/resolve`、`/api/facets`）。
- **落点**（D9 默认"修"）：
  - `RedactingQueryService` 应用 `adjudicationDisclosure`（判决披露口径）与 `redactedFields`（按字段名剔除）；
  - 把所有读端点接进 redaction（带 `as=` 时）；**不带 `as=` 保持全量**（GM/调试）。
  - **fail-closed 清单**：若某端点暂不接，必须在 `RedactingQueryService` 或 spec 里**逐条列出**并让该端点带 `as=` 时**拒绝**而非静默返回全量（"没接"不得伪装成"全可见"）。

### 六.7 决策人信息数据结构（research §D#7，D13）

- 同 §四.3 的"左栏字段"；**复用现有字段，不新增 sd 数据**。

---

## §七 判据（逐条可实测）

> 每条写成**可观测的句子**：**观测点**（读哪个文件/端点/元素/日志）+ **期望值**。下游照此写测试与变异体。

### 七.1 编辑线

| # | 判据 |
|---|---|
| **C1** | `index.html` 的地图编辑面板里存在**二级子选项控件**（默认「地形」/「连通性」）；切换子选项时，**恰一个**子面板可见（`terrain` 控件可见 ⇔ 子选项=地形；`edge` 控件可见 ⇔ 子选项=连通性），另一个 `hidden`。 |
| **C2** | 子选项=地形时，**不发出**任何 `map.SetEdge`；子选项=连通性时，**不发出** `map.SetTerrain`（静态扫描 `map.js` 的写调用受子选项门控；运行时以"切换后拖动"的实际写请求为准）。 |
| **C3** | 连通性手势逐条：右键拖动 ⇒ 一条 `map.SetEdge`；左键点边 ⇒ 一条 `map.SetEdge`（删）；起点→续点→同格 ⇒ **结束**（不再追加）；非相邻续点 ⇒ **拒绝/提示**、不写；12px 阈值内有边 ⇒ 命中；`EdgeRef` 双向（先点 A→B 后点 B→A）**不产生两条不同边**。 |
| **C4** | ★ **往返守卫覆盖连通性**：存在一条 `edges` **非空**的用例，`apply(MapChangeSet.between(base,target), base)` 逐字段等于 `target`；**删掉 `MapChangeSet.edges` 组件 ⇒ 该用例红**（变异杀）。 |
| **C5** | 非相邻 `map.SetEdge`（MCP/命令路径）**被拒绝**（若 D14 选"加校验"）；拒绝时 `revisions` 行数不变。 |
| **C34** | ★ **连通性词表可自定义**（§三.6）：先经 `map.RegisterPathwayGroup` 注册自定义组 `canal` ⇒ `map.SetEdge{kind:canal}` **成功**（落 revision）；**未注册的 `kind` 仍被拒**（fail-closed）；默认 `river`/`road` 不受影响。★ 观测点：`EdgeOperations` 的 `kind` 校验来源改为 `base.pathwayGroups().keySet()`（**现状**硬编码在 `EdgeOperations.java:37` 的 `Set.of("river","road")`，改造后该常量消失）。 |

### 七.2 端口拓扑与权限

| # | 判据 |
|---|---|
| **C6** | 现有 MCP 口（缺省 **5715**、路径 `/mcp`）的 `tools/list` **同时含** `simos.command.submit` / `simos.advance` / `simos.fork`（通用写，D2="加"）**与** `sd.IssueDirective` / `sd.SubmitVerdict` / `sd.SetViewScope`（GM 窄工具）。 |
| **C7** | 新决策人口（缺省 `decisionAgentMcpPort`）的 `tools/list` **含** `sd.IssueDirective` / `sd.SubmitVerdict`、**不含** `simos.command.submit` / `simos.advance` / `simos.fork` / `sd.SetViewScope`。 |
| **C8** | 两个 MCP server **同时监听**、`boundPort` 不同；`Shell.close()` 后两者都不可连（关闭次序不挂死）。 |
| **C9** | 端口是**唯一**边界：以调试方式连接新口调 `simos.command.submit` ⇒ **工具不存在**（不是"存在但被拒"）。 |

### 七.3 决策模式与三件事

| # | 判据 |
|---|---|
| **C10** | 模式栏有**恰 6 个**按钮，含「决策」；点它 ⇒ 决策面板可见、其余模式面板不可见；`modes.test.cjs` 的白名单与该模式 `writes` 一致。 |
| **C11** | 决策模式内有两个子页切换控件；子页 A ⇒ 显示决策人查看；子页 B ⇒ 显示审批列表。 |
| **C12** | **国家 ⇒ 高亮**：选中一个国家 tag ⇒ `/api/map/overview`（或前端高亮计划）里的高亮区域集合 = 该 tag 的 Region 集合（逐值相等，**不是子集**）。 |
| **C13** | 选中某区域 ⇒ 左栏出现该国决策人信息（字段非空、与 `GET /api/sd/decision-makers/{id}` 逐值一致）。 |
| **C14** | 选中**有决策人的单位** ⇒ 左栏出现该军队决策人信息；选中**无决策人的单位** ⇒ 明确显示"无决策人"（不静默空白）。 |
| **C15** | 右侧列表**按类型分类**：国家决策人一组、军队决策人一组；组内按 id 字典序；列表长度 = `/api/sd/decision-makers` 返回长度。 |
| **C16** | ★ **推进 ≠ 决策**：点一次"推进 N tick"后，`/api/timeline` 的 revision 行数**只按推进本身增加**；**不产生**任何 `sd.IssueDirective` / 决策标记；决策列表的 `due` 状态**随 tick 更新**（基于 D7 公式，逐值可算）。 |
| **C17** | 推进后，决策列表里 `due=true` 的集合 = 按 D7 公式**离线算出**的集合（逐值相等）。 |
| **C18** | ★ **开始决策三层**：① 用户经 GUI 发 `sd.StartDecision` ⇒ **直接落 revision**（`revisions` +1，头部前进）；② GM Agent 经 GM 口发同一命令 ⇒ **进入审批**（未决时 `revisions` 不变），批准后才 +1；③ 决策人 Agent 口**没有** `sd.StartDecision`。 |
| **C19** | 决策人 Agent 的**异步性**：开始决策**不阻塞**在同一请求里等出令；出令是另一条（`sd.IssueDirective`/`sd.SubmitVerdict`）revision。 |

### 七.4 通知栏与审批

| # | 判据 |
|---|---|
| **C20** | `index.html` **不含** `#approvals-count` / `<h3>待批</h3>`；`app.js` **不含** `mountApprovals`；右下角存在通知栏元素，且它读 `GET /api/approvals`。 |
| **C21** | 后端 `GET /api/approvals` 仍 200/503（保留）；`write-allowlist.test.cjs` **仍绿**（`api.approvals` 保留；若改则同步改被측对象，断言数不下滑）。 |
| **C22** | 底栏有 GM 按钮；点它 ⇒ 全屏 GM 交互界面；界面显示 **GM MCP 的工具使用**（至少：工具名 + 结果），**不含**对话输入框。 |

### 七.5 富世界与文档

| # | 判据 |
|---|---|
| **C23** | 富世界（`--demo`）在空库首启后：`hexCount`（**59223**）/ `provinces` 数（**98**）/ 地形直方图（**7 类**，含 `lowland 16933`、`swamp 99` 的 lossy 承接）/**河流**（由 `edgeTags` 忠实转换来的 `edges` 的 `river` 标注）与 `v17levant` 源档**逐值对拍**（对拍脚本产物在证据目录）。 |
| **C24** | 富世界的区域与 `v17levant` 的 `provinces` 逐一对应（`tag`：**97 Nation + 1 王国**）；★ **16 个有名势力有文本、82 个噪声区域**如实在文档标注为无文本。 |
| **C25** | ★ **非空库不覆盖**：对已有库起 `--demo` ⇒ 世界不变（沿 `ShellMain.java:105` 的空库判定）。 |
| **C26** | ★ **绝不录 Info**：产出目录下**只有 `.md`**；`InfoSystem` 与 `SdInfoEntry` 的条目数不因富世界而变（用例断言）。 |
| **C27** | 产出 md 里**含全部 615 条 checkpoint 元素**（`narrative` **170** / `map` **318** / `internal` **67** / `factions` **52** / `worldview` **6** / `characters` **2**，计数逐值）；档案里出现的"区域NNN"噪声被标注为程序化。 |

### 七.6 可见性（redaction）

| # | 判据 |
|---|---|
| **C28** | 两个不同 `ViewScope` 的决策人调**同一**读端点（带 `as=`）⇒ 返回**不同**数据；`adjudicationDisclosure=WITHHELD` 的 actor ⇒ 判决字段**整条消失**（不是空串）。 |
| **C29** | `redactedFields=["position"]` 的 actor ⇒ 单位读数里 `position` 字段**消失**；另一 actor 同请求里 `position` **在**。 |
| **C30** | ★ **fail-closed 清单**：带 `as=` 时，未接入 redaction 的端点**返回拒绝**（而非全量）；清单在 spec/代码里**逐条可查**。 |

### 七.7 门禁（每任务）

| # | 判据 |
|---|---|
| **C31** | `./mvnw clean verify` **rc=0**；模块 **`SUCCESS [`**（显示名 `UtilSimos` / `MapSimos` / `SocialSimos` / `UnitSimos` / `CoreSimos` / `SDSimos` / `SimosApp`）逐行在案；`[ERROR]` **0 行**；`BugInstance size is 0`（模块数）；前端 `[frontend-gate] OK tests=N pass=N fail=0`。 |
| **C32** | 改 JS 测试 ⇒ `run-gate.cjs` 的 `MIN_TESTS` 与 `gate-contract.test.cjs` 的 `MIN_ASSERTIONS` **两处同改**；新文件进 `REQUIRED_FILES`。 |
| **C33** | 新增/改动 `catalog` 里已有命令的注册 ⇒ `McpCoverageTest` 的**双向载荷断言**喂饱（每个注册 type 都有可提交载荷 + head 前进）。 |

---

## §八 挂起项与盲区

### 八.1 已知限制（本阶段接受，明确写出）

1. **端口级边界**（A′）：无认证；"知道端口"就是权限。内网语义可用，不构成安全隔离。
2. **审批层只按桶区分**（A″）：`AutoApproveGate` 看不到 `requesterId`；靠端口/桶区分，否则记为限制。
3. **GM 界面不做对话**（P2）：只显示工具使用。
4. **决策人 Agent 出令不自动**：本阶段可只做"发起"（§六.3），自动 LLM 出令归后续。

### 八.2 待裁项

- §〇.2 的 **11** 项（`D1`、`D3`~`D11`、`D13`）；★ **`D2` 与 `D12` 已由用户裁定**（见 §〇.1），**不在**待裁。另 **D14**（三.3 衍生）：是否恢复"缺格自动建格"、是否为非相邻边加后端校验。**默认**：不自动建格、加相邻校验。

### 八.3 我未能核实的（推断 / 未验）

1. **`agentlib-mosire` 是外部依赖**（research §E.1）：审批请求字段 / MCP server 构造读的是本机另一份源码，**与 `~/.m2` 里的 `0.1.0-SNAPSHOT` 是否一致未核**。新增第二个 `startHttp` 的**可用性**属**推断**（以方法签名推）。
2. **`v17levant` 的 `edgeTags` 语义**：实测有 `{"0":["river"]}` 形态，且 **`riverMask` 与 `edgeTags` 的非空格数**相同（均 **248 / 59223**）；但 **两者的逐格/逐方向对应关系未逐值验证**（只知计数相同，非同一口径）⇒ 故判据里 **`edgeTags` 为准、`riverMask` 仅交叉校验**（§五.2）。
3. **59223 格富世界在浏览器/服务端的实际性能未测**（M9 只测过 19441 格）。
4. **`lowland`/`swamp` lossy 合并**：M6 映射表把 `swamp → plains`；`v17levant` 有 **`lowland` 16933 格（28.6%）+ `swamp` 99 格**，simos 词表**两者皆无对应**，合并（→ `plains`）是**推断**会如 M6 口径执行；是否新增 `lowland` 词表见 §五.5 的 `[待裁]`。
5. **新增读工具是否破坏 `SimosToolsTest` / `McpCoverageTest` 的工具面断言**：未读那两处断言的具体形状；六.1 的"可选读工具"须**先核实再动**。
6. **决策人模式打开是否为空**：research §E.4 指出 **demo 世界有没有 nation/army/决策人/带 tag 的区域未验**；富世界（本阶段）会补上这些数据，但**既有 `DemoWorld` 里确认没有**（已读 `DemoWorld.java`：只有 map/unit/social/sd 空）。

### 八.4 盲区（结构性）

- **前端护栏强度低于后端**（CLAUDE.md M7 行 / M8 T2 已接 `node --test` 门禁）：本阶段新增的前端交互**必须**进 `simos-app/src/test/js/` 门禁，否则是"证据级"。
- **`tools/` 不入 Maven reactor**（M6 行）：导入器改动**不在门禁内**，必须靠"对拍脚本 + 产物断言"自证。

---

## 附：本 spec 与既有文档的关系

- 本文**不修改** brainstorm / research；**补充事实 A / A′ / A″ / B** 是本阶段新增的已核实事实（见 §〇.1）。
- 本文的 `[待裁]` 表（§〇.2）供用户一次性裁决；裁定后**本 spec 原地更新**（照 SDSimos spec 的"已裁定"写法），**不改 brainstorm/research**。
- 计划（`2026-09-21-webui-stage-fix-plan.md`）**只写已裁与默认项**的步骤；`[待裁]` 项的实现步骤**待裁后补**。
