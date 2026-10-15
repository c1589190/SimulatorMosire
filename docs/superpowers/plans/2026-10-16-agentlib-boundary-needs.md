# 给 AgentLib 侧的需求书 —— 「给任何 Agent 展示一个工具面」这一层该长在哪

> **提出人**：SimulatorMosire（simos）控制方 · **日期**：2026-10-16
> **来源**：用户判断「**这个给任何 Agent 展示一个工具面，看着有点像 AgentLib 系列应该做的事啊**」⇒
> 控制方**回代码核过两边**后落成此文。★ 全文每条判断都附**可回代码核的实据**；
> ★★ 拿不准的地方**明确标注"未核实"**，不猜。

---

## 0. 结论一句话

**simos 的 `simos-ga-spi`（14 文件 / 1303 行）里，大部分是【框架级】的：工具面契约 · 角色 × 审批的分类 ·
可提议面 · 读写口与回执 · 资源作用域 · 结果折叠。**
而 ★ **AgentLib 已经有一半基础设施**（`AgentTool` / `ToolGate` / `approval/*` / `mcp/*` / `ResourceManifest`）
⇒ 请隔壁核实：**这一层是否本就该由 AgentLib 承接、以及该以什么形态承接。**

★ **支持这个判断的最硬证据不是分层审美，而是"出血位置"**：今晚（2026-10-15 夜）在 simos 侧修掉的
**四笔最贵的缺陷，全部长在这一层**（见 §3）。

---

## 1. AgentLib 现在已有什么（控制方实测，`~/ProjectMosire/AgentLibMosire`）

```
agentlib/tool/AgentTool.java      name() · description() · jsonSchema() · spec()
                                  gate(ToolContext) · resources() · ledgerArgs(ToolContext)
agentlib/approval/                ToolGate · PendingApprovals · ApprovalDecision
                                  DenyGate · AutoApproveGate · ApprovalHttpEndpoint · ApprovalConfig
agentlib/mcp/                     McpToolSource · McpToolAdapter · McpCaller
                                  AgentToMcpServer · McpSourceBridge · McpServerLinkConfig …
agentlib/event/ · agentlib/config/
```

⇒ ★ **「给 Agent 展示工具面」= `AgentTool` + `McpToolSource`/`McpToolAdapter`** —— **AgentLib 已有**
⇒ ★ **「要不要审批」= `ToolGate` + `approval/*`** —— **AgentLib 已有**
⇒ ★ **「资源声明」= `ResourceManifest`** —— **AgentLib 已有**

---

## 2. simos 侧现状：`simos-ga-spi`（14 文件 / 1303 行）

| 文件 | 行 | 它是什么 | 控制方初判归属 |
|---|---:|---|---|
| `SimosTool.java` | 36 | `interface SimosTool extends AgentTool { Set<ToolRole> roles(); }` | ★ **角色面 → 框架** |
| `ToolRole.java` | 34 | 枚举 `{GM, DM}` | 枚举值 **simos 领域**；"角色"概念 → **框架** |
| `ToolClass.java` | 42 | **由 (roles × gate) 派生的三分类**（GM_ONLY / DM_APPROVAL / DM_FREE） | ★★ **纯框架**（「谁可调用 × 要不要审批」的二维划分） |
| `DecisionProposable.java` | 38 | 工具**自己声明**是否属于"可提议面"（`skipKeys()` / `createsUnit()` 两个 default） | ★★ **纯框架**（agent 提议 → supervisor 批 → 原子执行） |
| `ToolWritePort.java` | 296 | **写口 + 回执 id**（`submitWithReceipt` / `submitBatchWithReceipt`） | ★ **框架**（提交 + 可回报落点） |
| `ToolReadPort.java` | 50 | 读口 | ★ **框架** |
| `ToolSchema.java` | 133 | 载荷/字段 schema 与解析（含 fail-closed 语义） | ★ **框架** |
| `ToolResults.java` | 117 | 结果折叠 + 对外 JSON 载荷 | ★ **框架** |
| `ToolResources.java` | 268 | 命名空间 + **路径拼写唯一拼写点** | ★ **机制框架**（命名空间名是 simos 的） |
| `SimosToolAdapter.java` | 129 | 把 domain 工具适配成 `SimosTool` | ★ **框架** |
| `GmOnlyRead.java` | 18 | 纯标记（GM 专读） | 机制 → 框架（语义 simos） |
| `DecisionMakerAffiliationPort.java` | 74 | 决策人**归属**端口 | ★ **simos 领域**（GM/DM 世界概念） |
| `DecisionMakerExistencePort.java` | 31 | 决策人存在性端口 | ★ **simos 领域** |
| `package-info.java` | 37 | 契约说明 | —— |

★ **依赖面**（`AGENTS.md` 常驻边界表）：`simos-social` / `simos-unit` / `simos-gov` / `simos-ga` / `simos-app` 都依赖它；
`simos-ga-spi` 自己只允许依赖 `simos-util` + `agentlib-mosire` ⇒ ★ **它已经是"贴着 AgentLib 的那一层"** ✔

---

## 3. ★★ 为什么说这层长错了层：今晚四笔出血全在这里

| # | 缺陷（真机真跑 / 真调挖出） | 抽出来的**通用形状** | 在哪一层该被拦住 |
|---|---|---|---|
| 1 | **4 条工具忽略 `planOnly`** ⇒ 规划阶段**真落盘**，GM 重试**再落一次**（数据损坏类） | 「**规划阶段不许写**」 | 框架（审批/提议状态机） |
| 2 | **`propose` 写后 NPE** ⇒ 报 `TOOL_ERROR` 但**包已落盘** ⇒ 模型重试 ⇒ 两条同 id ⇒ **整 tick 决策全废** | 「**写之后抛 ⇒ 报错但已落盘**」 | ★ 框架（写口必须把"提交 + 组装视图"放进同一保护域） |
| 3 | **`ProposalCatalog` 手写名单** vs `roles()`/`gate()` **漂移** | 「**第二处声明会漂**」 | ★ 框架（清单必须从工具面派生 + 装订期审计） |
| 4 | 为**一条报错文案**，必须把它从 `simos-ga` 上移到 `simos-app` ⇒ **新开 SPI 端口** ⇒ 改到一半**整棵树编译不过**、另一个写者的真跑被打断 | 「**工具面/审批面的编排长在了错误的一层** ⇒ 才需要这种绕法」 | ★ 框架（唯一拼写点由框架提供） |

★ 控制方已把 #2 的通用形状落盘：`docs/superpowers/plans/2026-10-15-batch-S-write-then-throw.md`（含通用纪律：
**写之后的代码必须"不会抛"**，或把可能失败的事移到写之前）。
★ #1 的纪律：`docs/superpowers/plans/2026-10-15-batch-L-…`（planOnly 双写）· #3 的纪律：`docs/superpowers/plans/2026-10-15-batch-K-…`。

---

## 4. ★★ 我们需要的具体能力（逐条：现状 / 我们要什么 / 验收形态）

> ★ 每条都写成**可判"有/没有"**的形态；★ 未核实处已标注。

| # | 能力 | 现状（控制方实测） | 我们要什么 | 验收形态（怎么判"有了"） |
|---|---|---|---|---|
| **N1** | **角色面** | AgentLib `AgentTool` **有 `gate()` 但无 `roles()`**（★ 未核实是否别有机制） | 工具声明"**谁可调用**"（多角色），与 `gate()` **正交** | 给定工具面 + 一个调用者身份 ⇒ 框架能算出**它够得着哪些工具** |
| **N2** | **（角色 × 审批）分类** | 无（simos 自建 `ToolClass` 三分类） | ★ **装订期**算出分类（不是运行时判断），并**审计"目录 ⇔ 工具面"一致** | 装订期能报出三格计数；**目录漏一个工具 ⇒ 装订失败** |
| **N3** | **可提议面** | 无（simos 自建 `DecisionProposable`） | 工具声明"**可被 agent 提议**"，与"可被调用"**正交** | 一个工具可"能被调用但不可提议"；提议一个不可提议的 ⇒ **框架给出可执行报错** |
| **N4** | **提议/审批/执行的包状态机** | `approval/*` 有单条审批（`PendingApprovals`/`ApprovalDecision`）；★ **是否支持"一批"未核实** | **多条调用组成一个包**：`propose → confirm → decide(逐条 callIndexes) → execute(整批原子)`；**冲突则整批退回** | 一个包 N 条 call ⇒ **恰好一条 revision**；任一业务拒 ⇒ **整批零落盘** |
| **N5** | **"规划阶段不许写"的框架级保证** | 无（simos 靠每个工具自己尊重 `planOnly` ⇒ **已因此坏过**） | ★ **框架层拦住**：规划期的调用**不可能落盘** | 一个工具在规划期调用 ⇒ **物理上无法写**（不是靠它自觉） |
| **N6** | **写口 + 回执 + 「写后不许抛」** | simos 自建 `ToolWritePort`；★ **"写后抛"要我们自己一行行发现并修** | 框架保证「**提交 + 组装返回视图**」在**同一保护域**：要么都成，要么都不成 | 写后视图组装抛异常 ⇒ **世界不许已变**（今天 simos 实测反例：`headDelta=1` 而返回 `TOOL_ERROR`） |
| **N7** | **作用域求值 + 授权** | AgentLib 有 `ResourceManifest`（声明）；simos 自建 `ResourceScope`/`ResourceAuthorizer`（**求值**） | 由"声明 + 调用者身份"**算**出可达范围；★ **未表态即拒**（不是回落"不限"） | 一个未表态的命名空间 ⇒ **拒**；且**没有归属的调用者 ⇒ deny-all** |
| **N8** | **唯一拼写点机制** | 无（simos 靠纪律 + 扫描器） | 工具名 / 报错文案 / 命名空间路径的**唯一来源**由框架提供 | 别处再写一份 ⇒ **装订期或扫描期报错** |

---

## 5. 请隔壁核实 / 回答的问题（★ 逐条可答，控制方**没有**替你们假设）

1. ★ **AgentLib 的定位**：它是"**给 Agent 展示工具面 + 审批 + 作用域**"的通用框架吗？§4 的 N1–N8 **在不在它的路线里**？
2. `AgentTool.gate(ToolContext)` 的语义：**每次调用现算**还是**装订期定**？能不能表达「**这一"类"工具需要审批**」（而不是每个工具自己写一遍）？
3. `approval/*` 的模型：`PendingApprovals` / `ApprovalDecision` 是**单条**还是**一批**？支不支持**逐条裁决 + 整批原子执行**？
4. `ResourceManifest` 的语义：能不能表达「**读/写分档** + **前缀作用域** + **未表态即拒** + **无归属 ⇒ deny-all**」？
5. ★ **有没有"唯一拼写点"机制**（工具名 / 文案 / 命名空间路径的单一来源）？
6. **写口/回执**：AgentLib 有没有"提交 + 回执 id"的概念？★ 有没有把「提交 + 组装视图」放进同一保护域的机制？
7. ★★ **接口与兼容策略**：AgentLib 允许**加接口 / 加 default 方法**吗？我们要按什么形态提交改动 ——
   **PR / 直接改 / 只在 simos 侧绕过**？（★ 今天 simos 已多次遇到"ga 定端口、app 给实现"这种绕法，见 §3 #4）
8. ★★ **边界口径**：若承接，**`simos-ga-spi` 的哪些成员该回流**？哪些**明确留在 simos**？
   ★ 我们初判见 §2 最后一列，但**这是你们的设计判断，我们不代裁**。

---

## 6. 边界与代价（供你们判断，不是我们的主张）

- ★ **我们要保的 simos 领域件**：角色**枚举值** `GM`/`DM` · 命名空间 `map/unit/social/gov/army/actor/economy/ga` ·
  工具名 `simos.*` · 领域载荷与校验 · `DecisionMakerAffiliationPort`/`DecisionMakerExistencePort`
- ★ **搬迁代价（控制方粗估，未实测）**：`simos-ga-spi` 14 文件 / 1303 行；依赖它的模块见 §2；
  ★ 且这一层**刚被打磨到有 44 条守卫测试**（`simos-ga-spi/src/test`，2026-10-15 新增）+
  **装订期三格不变量**（类1 132 · 类2 24 · 类3 37 · 并集 193 · GM 桶 180 · 决策人桶 61，**实测值**）
  ⇒ ★ **搬"已验证过的东西"比搬"还在漏的东西"安全**，但也正因如此，**边界要先谈清**
- ★ **simos 侧当前状态**：HEAD 干净且门禁绿（`clean verify` rc=0 · Tests 3356/0/0/7 · SpotBugs 16 模块 0 ·
  前端 412/412）⇒ **核实可以基于 HEAD，不受我们侧在飞工作影响**

---

## 7. 控制方附注（诚实边界）

- ★ 本文**每条"AgentLib 已有"都来自 `~/ProjectMosire/AgentLibMosire` 的文件清单实测**（§1）；
  ★★ 但**我们【没有】系统读过 AgentLib 的实现语义** —— 所以 §5 才逐条列成"请你们核实"。
- ★ 我们**已证实的**只有：今晚四笔出血**都不是** AgentLib 的缺陷（两处最关键的 AgentLib 行为 ——
  匿名凭据「空白 = 有意匿名」、流式停摆「具名上报并中止」—— **都是对的**）。
  ★★ 我们**没有**证明"AgentLib 里没有问题"。
- ★ 本文**不主张任何具体搬法**；它要的是**你们的判断**：这层该不该在 AgentLib、该以什么形态承接。
