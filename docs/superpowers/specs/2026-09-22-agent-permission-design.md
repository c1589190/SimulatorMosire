# 决策人权限组与可见范围设计（spec）

> 用户 2026-09-22 裁定。本 spec **修订**「工具面标准化」（`2026-09-22-tool-surface-creed.md`）中对
> MCP 与决策人的**认识**，并给出**范围函数**的形态。
>
> 用户原话（四则，按时间序）：
>
> 1. 「目前设计对于 MCP、决策人 Agent 的认识本身有问题；首先，MCP 和 GM Agent 处于同一权限级，
>    想改什么改什么；其次，决策人不能直接改地图等数据，只能获取有限的、被 GM 权限层限制范围的信息，
>    没有暴露 MCP，全在项目内用 Agentlib 相关接口实现」
> 2. 「理论上来说，要在项目内部实现一个 GM Agent 的运行流，并且做额外的 GUI，但是目前很显然
>    没有这么多开发精力，因此直接做一个 GM 级权限组，以及以前者权限组把 Tool 暴露到 MCP 的 MCP 服务就行」
> 3. 「Agentlib 都把权限组功能实现了，那你 Simos 为什么不用？还有，GM 层面要有额外的限制特定决策人
>    权限组的工具，甚至应当做到，让这个决策人只能查看有指定关键词/指定范围的数据信息之类」
> 4. 「决策人一共有两类，一个是国家决策人，一个是军队决策人；对于国家决策人，能查看的范围自然是这个
>    国家对应区域包括的所有 hex 与其附带信息；对于军队，则是军队所在位置一定范围一圈的覆盖信息
>    （具体的视野功能后面再在 unit 里面写，如果没有视野字段，先简单添加这个字段，然后再根据这个字段写权限）」
>    ＋「对于每次调用现算，你不能试试看用函数方法吗？……现在先实现这两种需要的单独函数，
>    后面可能会要求支持更复杂的权限函数操作」＋「对于国家决策人，还要允许国家决策人查看其有什么邻国，
>    对于军队决策人，则是只需要细化到单个 hex 的归属国家」

---

## 〇 判据（验收，逐条可测）

| # | 判据 | 怎么算过 |
|---|---|---|
| **J1** | **MCP 口 = GM 权限组** | 端口的 `ToolContext` 由**显式构造**的 GM 权限组承担（**不是** `AgentPermissionSet.unrestricted(...)` 那种"资源维不表态"的取值）；`SimosToolSource.Role` 里**不再有** `EXTERNAL` 这一档 |
| **J2** | **决策人不暴露 MCP** | 决策人端口不再监听；`McpDecisionChannel` 不存在；渠道回到 GUI / CLI / Http 三条 |
| **J3** | **决策人不能直接改数据** | 决策人工具面里**没有任何地图/单位/sd 的写工具**（只留决策行为：`sd.IssueDirective` / `sd.SubmitVerdict`） |
| **J4** | **范围由函数现算** | 两个范围函数各有用例；且有一条用例证明**世界状态变了范围就变**（军队移动 ⇒ 可见 hex 集合随之变化） |
| **J5** | **国家决策人看得见邻国** | 其可读视图里有"邻国"标识（本国之外的、与本国有区域邻接的国家） |
| **J6** | **军队决策人看得见 hex 的归属国家** | 其可读的 hex 视图带"归属国家"（由该 hex 所属区域的 `nation:` tag 推出） |
| **J7** | **视野字段进状态** | `Unit` 有视野半径字段、缺省 1；带**往返不变式**（铁律 5）与 change set 派生 |
| **J8** | **配权命令取代旧机制** | 新命令成为 GM 唯一的配权入口；旧的 `viewScope` 一套（命令 + 字段 + 读侧过滤）从生产路径消失 |
| **J9** | **资源维不空转** | 至少一条「同一工具、同一参数、两个调用者，一个放过一个拒」的用例 —— **这是资源维不是装饰的唯一证明** |
| **J10** | **门禁与护栏** | `./mvnw clean verify` 绿；每条新护栏自带**故意违规用例 + 变异轮**（本仓既有纪律） |
| **J11** | **决策人自己调工具 + 上下文沿用** | 决策人经 LLM **多轮工具调用**读世界（不是被动收简报）；同一决策人**跨 tick 的会话连续**（历史落 `ConversationStore`，可查、可回放） |

---

## 一 现状（实测锚点，2026-09-22 本机核对）

### 1.1 权限层：用了一半，另一半从没配上值

| AgentLib 能力 | Simos 现状 | 出处 |
|---|---|---|
| `ToolSpec.level(token, sensitive, destructive)` | ✅ 用了 | `AbstractNarrowWriteTool.java:63` |
| `ResourceManifest`（工具声明命名空间） | ✅ 用了 | `ToolSupport.java:67-92` |
| `context.resources().require(Operation, ResourceId)` | ✅ 8 个读工具 + 写工具基类都在调 | `ToolSupport.java:96-118` |
| `AgentPermissionSet` | ⚠️ **只出现一次**，且是 `unrestricted(DEFAULT)` | `Shell.java:656-661` |
| `ResourceScope` / `ResourceScopeMap`（范围） | ❌ **生产代码零使用** | `git grep` 实测 |
| `allowedTools` / `deniedTools` / `readOnly` | ❌ 从未用于区分角色 | 同上 |
| `AgentIdentity` | ⚠️ 只有 `external()` 一种 | `Shell.java:660` |

⇒ **根因**：装配期把权限边界放在**端口**（`Shell.java:467` 注释自陈），于是只有一个身份 `mcpCaller()`；
`unrestricted(...)` 走 6 参构造 ⇒ `resourceScopes` 为 null ⇒ 规范化成空图 ⇒ 资源判定走"调用者未表态"分支放行
⇒ **每一次 `require` 都恒真**。断言在跑，但永远通过。

### 1.2 决策人链路现状

- **两个 MCP 口**：`mcpPort`（桶 `EXTERNAL_WITH_GM`）、`decisionAgentMcpPort`（桶 `DECISION_AGENT`，`Shell.java:482-491`）。
- **决策人桶里有 unit 域 21 条写工具**（`SimosToolSource.java:229-249`，M2 的 D-1 裁定给的）。
- **读工具四桶共享**（`SimosToolSource.java:142`）⇒ 决策人拿到与 GM 完全相同的读工具。
- `McpDecisionChannel`（`app/sd/channel/`）把 MCP 作为决策提交渠道之一。
- 决策人的读限制靠 Simos **自造**的 `viewScope`（sd 字段）+ `RedactingQueryService` + GUI 的 `as=`。

### 1.3 三条"声明与实现不一致"（实测）

1. **`actor` 参数可省略**：读工具的 `actor` 是调用方自报的任意字符串、且**不是 required**
   （`MapOverviewTool.java:50/65-68`）⇒ 省略即拿 GM 全量 ⇒ 限制纯自愿。
2. **`allowedTools` 运行时零校验**：唯一强制点在创建期（`CreateDecisionMakerHandler.java:55-58` 只拒通用写一个值）；
   而 `Shell.java:437` 的注释声称"N9 在 … `allowedTools` 白名单上照旧有效"——**该断言在代码里找不到实现**。
3. **N17 的脱敏渠道零调用者**：`ChannelAdmission.redactedBrief` 主源码无调用点；LLM 判决实际收到的是
   `DecisionAdjudicationService` 自己拼的、**未经 viewScope 过滤**的简报（`AdjudicatorRunner.java:98-102`）。

### 1.4 AgentLib 侧的三条硬约束（决定本设计形态，源码实测）

| # | 约束 | 出处 |
|---|---|---|
| **C1** | **`readOnly(true)` 拒一切工具**（不是"只读工具放行"，且与它自己的 javadoc 不符） | `PermissionChecker.java:34-36` + 用例钉死 |
| **C2** | **只给"断言"原语，不给"过滤"原语** —— 宿主侧没有任何裁剪工具输出的钩子 | `ToolCallAuthorizer.java:122-179` |
| **C3** | **无 per-session 身份**：caller 在 `startHttp` 时定死，一个端口一份权限集 | `AgentToMcpServer.java:61/674-680` |

另：`ResourceScope` 是**段边界前缀匹配**（`/` 分隔、大小写敏感、**无通配/无子串/无否定**），
三态 `unlimited()` / `none()`（deny-all）/ `of(prefixes...)` 互不退化（`ResourceScope.java:41-203`）。

---

## 二 目标形态

```
┌── GM 权限组 ──导出──▶ MCP 服务（外部 agent 连入即 GM，想改什么改什么）
└── 决策人权限组 ──进程内──▶ AgentLib 接口直接调用（不出 MCP，范围每次现算）
```

### 2.1 GM 组

- **权限集**：显式构造的 `AgentPermissionSet`（全工具白名单 + `sensitiveAllowed` + `destructiveAllowed`
  + 各命名空间 `unlimited()`）——**取代** `unrestricted(...)` 的空资源图。
- **身份**：保留 `AgentIdentity.external()`（用户裁定①）。理由：它是**审批面语义**（"这不是本进程派生的下级"），
  与权限**级别**正交；GM 若是经 MCP 连入的外部 agent，这个身份是对的。
- **工具面**：现有 `EXTERNAL_WITH_GM` 那批**逐条不变**（读 + 通用写 + 全部窄写），只是桶概念改名；
  `Role.EXTERNAL` 与 `Role.EXTERNAL_WITH_GM` 合并为 `Role.GM`。

### 2.2 决策人组

- **权限集**：白名单（受限读工具 + `sd.IssueDirective` + `sd.SubmitVerdict`）；
  **不用 `readOnly(true)`**（C1 会拒一切）；`resourceScopes` = **范围函数现算**。
- **身份**：`AgentIdentity.subagent("decision-maker:" + <id>, CommandMode.LIMITED, goal, 1)`。
- **执行**：`ToolCallAuthorizer.execute(registry, toolName, ctx)` —— **这是进程内调用的唯一路径**：
  判定者只在 `ToolCallAuthorizer` 第 ③ 段注入，手工拼的 `ToolContext` 里 `resources()` 是 deny-all
  ⇒ 直接 `tool.execute(ctx)` 会让**每个** Simos 工具 `RESOURCE_DENIED`（因为它们都调 `require`）。
- **不开 MCP**：C3 决定了 MCP 口表达不了"每个决策人一份权限"；而范围随世界状态变（见 §三）
  ⇒ **进程内现算**是唯一可行路径 —— 用户的"不暴露 MCP"因此不只是权限收敛，是**实现上的必然**。

### 2.3 决策人 agent 运行流（用户 2026-09-22：「要决策人自己调工具、带上下文」）

**现状**（实测）：决策人的 LLM 路径是**单轮**——`AdjudicatorRunner` 把"简报 + 输出 schema"喂给模型、
收一段 JSON（`DecisionAdjudicator.adjudicate`），**既无工具调用、也无跨 tick 上下文**。
⇒ 本节的运行流是**新做**的，不是改造。

**AgentLib 侧的事实**（决定"自建 or 改隔壁"）：

| 需要的能力 | AgentLib 有吗 | 在哪 |
|---|---|---|
| LLM 调用（带工具定义） | ✅ `LlmClient.chat(LlmRequest)`，`LlmRequest.tools()` | `agentlib/llm` |
| 模型返回工具调用 | ✅ `LlmResponse.toolCall(...)` / `assistantMessage()` | 同上 |
| 消息与会话持久化 | ✅ `ConversationStore` + `SqliteConversationStore`（`append`/`load`/`compact`） | `agentlib/store` |
| 工具执行（含权限、审批） | ✅ `ToolCallAuthorizer.execute(...)` | `agentlib/tool` |
| **循环本身**（LLM ↔ 工具 ↔ 历史） | ❌ **没有** | —— **在 `BrainMosire`**：`brain/runtime/AgentPipeline.java` |

★ **不改 AgentLib**：`AgentPipeline` 的核心依赖（`AgentConfig`/`ContextAssembler`/`Compactor`/
`CompactSummarySlot`/`TurnResult`）**全是 Brain 自己的类**，搬不动；而 AgentLib 的定位就是设施库、
**运行流属于应用层**（Brain 的先例）。⇒ Simos 在 **app 层**自建一个精简循环。

**形态**：

```
conversationId = "decision-maker:" + <DecisionMakerId>        ← 每个决策人一条会话
每 tick（决策 cadence 到点）跑一次 run：
    history = conversationStore.load(cid)
    while (true):
        resp = llmClient.chat(new LlmRequest(history, toolDefs(决策人可见的工具)))
        if (resp 不含 toolCall) break
        result = toolCallAuthorizer.execute(registry, toolName, 决策人的 ToolContext)  ← ★ 权限在这层强制
        history += [resp.assistantMessage(), toolResultMessage(result)]
        conversationStore.append(cid, ...)                     ← ★ 跨 tick 沿用的载体
    收尾：决策产出经 `sd.IssueDirective` / `sd.SubmitVerdict` 落 revision（铁律 2 不破）
```

**三条要点**：

1. **工具面 = 决策人权限组下的工具**（§2.2 的 caller 现算权限集）⇒ 决策人"能调什么工具"与"能看什么数据"
   由同一套 AgentLib 权限机制表达，**不是**靠提示词自律。
2. **会话落 `ConversationStore`**（`SqliteConversationStore` 落 `<store>` 下）⇒ 进程重启后同一决策人仍
   接得上；`conversationId` 由决策人 id 派生，**不隐式取全局状态**。
3. **不做** Brain 那套（子 agent、技能、压缩档位、bash 工具）—— Simos 的决策人是"游戏里的 AI 玩家"，
   需求面窄得多；将来若两者都成熟，再议是否抽到 AgentLib 共用（**那时才动隔壁**）。

---

## 三 范围函数（本轮核心交付）

### 3.1 形态：函数，不是配置

```java
/** 决策人可见范围函数（app 层）：决策人 + 世界状态 → AgentLib 资源范围。 */
@FunctionalInterface
public interface DecisionScopeFunction {
  ResourceScopeMap scopesFor(DecisionMaker dm, SimulationState state, String mapId);
}
```

- **按归属类型分派**的注册表（`Affiliation` 是 sealed：`Nation(NationId)` | `Army(ArmyId)`，
  两类已有类型基础，不新造）：注册表按 `Affiliation` 的运行时类型选函数。
- **可扩展**（用户："后面可能会要求支持更复杂的权限函数操作"）：新增一类范围 = 新增一个实现 + 注册一行，
  不改调用点。
- **每次调用现算**：不缓存、不落盘 —— 世界状态变则范围变。

### 3.2 两个实现

**`NationScope`（国家决策人）**

- 输入 `Affiliation.Nation(nationId)`。
- 逻辑：扫 `GameMap.regions()`，取 **`region.meta().tag()` 等于 `"nation:" + nationId`** 的区域
  （`RegionMeta.tag` 是**单个 String** ⇒ 一个区域只属一个国家，无歧义）。
- 资源前缀：`"<mapId>/region/<rid>"` 每条 —— **几条到几十条**，与 hex 数无关。
- `unit` 命名空间：本国单位（按单位位置落在本国区域内算）的 id 前缀。
- `social` 命名空间：本国区域内的 hex 前缀（人口按 hex 取）。

**`ArmyScope`（军队决策人）**

- 输入 `Affiliation.Army(armyId)`。
- 逻辑：取该军队的**当前位置**（`UnitState.effectivePosition`，与既有同口径）+ **视野半径**
  （`Unit.visionRadius`，见 §四.1）⇒ 六角距离 ≤ R 的格。
- 资源前缀：`"<mapId>/hex/<q>_<r>"` 每条（R=1 ⇒ 7 条、R=2 ⇒ 19 条，可控）。
- **纯半径，不做地形遮挡**（用户裁定⑥：遮挡留待将来）。

### 3.3 资源路径语法（Simos 自定；AgentLib 对领域实体一无所知）

```
map:    <mapId>/region/<rid>            区域（国家决策人的主力前缀）
        <mapId>/hex/<q>_<r>             单格（军队决策人的前缀）
unit:   <unitId>
social: <q>_<r>
sd:     decision-maker/<id> · nation/<id> · army/<id> · combat/<id>
```

★ **路径设计的两条硬要求**（否则前缀匹配表达不了需求）：
1. **按区域组织国家范围**，不逐 hex 枚举 —— 真档 59223 hex，逐格前缀不可行；区域级前缀只需几条。
2. **关键词必须独占一个路径段** —— 匹配是段边界前缀，`tag=supply` 或子串匹配**不成立**。

### 3.4 字段级可见性（与资源级分开）

`ResourceScope` 只管"能不能碰这个资源"，**管不到"能看这个资源的哪些字段"** ⇒ 字段级靠视图层裁剪：

| 决策人 | 资源范围（scope） | 字段粒度 |
|---|---|---|
| 国家决策人 | 本国区域 + 其内 hex | 本国 hex **全字段**（地形/高度/人口/单位…）＋ **邻国标识** |
| 军队决策人 | 视野圈内 hex | 每个 hex 的**归属国家**（由所属区域的 `nation:` tag 推）＋ 格基础信息 |

- **"邻国"的定义**：与本国任一区域**六角邻接**的、属于**其他国家**的区域 ⇒ 取那些区域的 `nation:` tag。
  实现放 **app 层**（它是权限/视图的派生信息，不是地图的核心语义；放 app 可避免改动已关账的 map 模块，
  减少变异轮作废面）。
- **"归属国家"**：`hex → 所属区域（多对多，M8-U1）→ 其中带 nation tag 的那些 → 国家集合`。
  多从属时返回集合（不假定唯一）。

---

## 四 数据模型改动

### 4.1 unit：视野字段（`visionRadius`）

- `Unit` 新增 **`visionRadius`**（视野半径，单位 = 六角圈数），**缺省 1**（用户裁定⑤）。
- 按铁律 5：change set 从完整状态类型派生 + **往返不变式测试**（`apply(changeSet, base)` 逐字段重建 target）。
- **视野功能本身（迷雾、探测、遮挡）不在本轮**（用户："具体的视野功能后面再在 unit 里面写"）；
  本轮只加字段 + 让权限读它。

### 4.2 sd：`DecisionMaker` 的权限字段

- **移除** `viewScope`（自造那套的载体）。
- **新增** `accessLimit`：GM 配的**额外限制**（可为空 = 无额外限制），语义 = **交集**（`narrowTo`）。
  ⇒ 「GM 只能额外收紧、不能放大」由 AgentLib 的 `narrowTo`/`covers` 原生保证。
- `adjudicationDisclosure`（判决三档披露）是 sd 域语义、**保留**，从 `ViewScope` 迁到新字段。
- 新命令 **`sd.SetDecisionMakerAccess`**（取代 `sd.SetViewScope`，用户裁定③）：
  载荷 = `decisionMakerId` + `allowedTools[]` + `accessLimit{命名空间: [前缀…]}`；
  走审批（sensitive）；落 revision ⇒ **可回放、可回退分岔**（铁律 2）。

### 4.3 撤销项

| 撤什么 | 理由 |
|---|---|
| 决策人 MCP 口（`decisionAgentMcpPort`） | 用户：决策人没有暴露 MCP |
| `McpDecisionChannel` | 渠道回到三条 |
| 决策人桶的 unit 域 21 条写工具 | 用户：不能直接改地图等数据；指挥走 `sd.IssueDirective`（spec §八.2 的 D2 原设计） |
| `Role.EXTERNAL` / `Role.EXTERNAL_WITH_GM` | 合并为 `Role.GM` |
| `sd.SetViewScope` + `DecisionMaker.viewScope` + 读侧的 viewScope 过滤 | 用户裁定③：新命令取代 |
| 读工具的 `actor` 参数（自报、可省略） | 身份从 `ToolContext` 来（`AgentIdentity`），不由参数自报 |

---

## 五 强制点与护栏

### 5.1 判定链（AgentLib 既有，无需自造）

`AgentToMcpServer.handleCall` → `ToolCallAuthorizer.execute` →
① 工具存在 → ② 工具名级（`PermissionChecker`：readOnly → denied → 白名单 → token 级别 → 敏感位）
→ ③ **资源判定**（前置闸 + 注入 `context.resources()`）→ ④ 命令闸（`ToolGate`）→ ⑤ 执行。

### 5.2 必备护栏（缺一条，资源维就是装饰）

1. **两调用者判别用例**：同一工具、同一参数、两个调用者，一个放过一个拒
   （照抄 `ToolCallAuthorizerResourceTest.java:127-153` 的形状）。
2. **断言粒度 ≥ 围栏粒度**：粗断言（`ResourceId.of("map", mapId)`）+ 细围栏 = **整调被拒**（不是部分可见）
   ⇒ 改断言与配围栏**必须成对上线**。`unit`/`social` 今天断言的是**字面量 `"*"`**
   （`ToolSupport.java:100-106`）⇒ 配任何受限 scope 等于全拒，**必须改这些断言**。
3. **`null` ≠ deny-all**：`resourceScopes` 为 null/空图 = "本层不表态"（放行）；要"够不着"必须显式 `none()`。
   两条方向相反，配错即**静默放宽**。
4. **同一决策人在世界变化前后的范围不同**（J4）—— 证明"现算"真的发生了。

---

## 六 我未能核实的 / 明确的边界

1. **AgentLib 类型的 Jackson 往返未验** ⇒ 权限描述**存 sd 自己的类型**（可回放、可控往返），
   app 层在调用时翻译成 AgentLib 权限组。**存储与执行分离**是架构选择，不是权宜。
2. **无 per-session 身份**（C3）：需要 AgentLib 改接口才能"同一端口多角色"，**不是 Simos 能补的**。
3. **无否定语义**：`ResourceScope` 只有正向前缀集 ⇒ "除 X 外都能看"表达不了（只能枚举）。
4. **契约性防线**：工具若自己不调 `require`/不按 scope 过滤，宿主无从知晓（AgentLib 自己把这条钉成用例）
   ⇒ **新增工具时的检查表比任何机制都重要**。
5. **地形遮挡（真视野）不做**（用户裁定⑥），纯半径。
