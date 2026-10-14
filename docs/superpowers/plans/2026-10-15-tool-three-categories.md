# 工具三分类：纯派生 + 装配期分区审计（批次 3）

> **状态**：用户 2026-10-15 已裁定分类口径（§0 / §3.1）；本文件是派单依据（§一.8 约束设计书）。
> **责任区**：`simos-ga-spi`（契约）+ `simos-ga`（审计实现 + 工具声明）+ `simos-app`（装配期接线 + `ProposalCatalog`）+ 各域模块（仅 `roles()`/`gate()` 声明的拼法统一）。
> **前提**：**不改任何工具的权限口径**（类归属一个字不动）；这是一批**把已有口径钉死 + 还小债**的活。

---

## 0. 用户原话附录（逐字）

**2026-10-15（本批的裁定）**：

> 「GMDM都能做那肯定是DM的需审批明命令啊，按照你所说的改吧」

配套的**上游口径**（同一裁定链，逐字）：

> 「现有的ga系列命令，权限等级我记得只有三个吧？分别是GMonly、DM经审批执行、DM无条件执行」（2026-10-14）

> 「只要涉及到真实世界变动（一个工具导致世界中某项数据变化），那GM必须审核，如果只是查看……走DM_FREE」（2026-10-14）

> 「那后面如果开放给DM用了呢？放着，不碍事就行」（2026-10-15，指 108 条 GM-only 的 `Ask` ⇒ **保留**）

---

## 1. 这是什么、为什么现在做

**三分类是"这个工具能被谁用 / 要不要问人"的唯一口径**。但代码里它是**两处互不相干的手写声明**：

```java
public Set<ToolRole> roles() { return Set.of(ToolRole.DM, ToolRole.GM); }   // 谁能用
public ToolGate gate(ToolContext c) { return new ToolGate.Ask(...); }        // 要不要问人
```

**没有任何东西检查这两处是否自洽**，也没有东西检查"每一条工具都落进了三格之一"。⇒ 漂移是**无症状**的：一个新工具漏了 `Ask`、或 `roles` 写反，装配期照样过。这与会话里已经解决过的 `ToolRoleAudit.requireFaces`（角色声明 ⇔ 桶，双向全量钉死）**是同一类问题**，本批把它补上。

---

## 2. 现状实测

### 2.1 ★★ 权威数字 = **真装配**（批 3a 实测，`140 / 17 / 37`）

| 类 | 条数 | 组成 |
|---|---|---|
| **类1 `GM_ONLY`** | **140** | 136 条内部 `{GM}` 真工具 + **4 条外来 `{GM}`**（`doc_void` / `mount` / `unmount` / `list_workspaces`，取自具名常量 `ROLES_GM`） |
| **类2 `DM_APPROVAL`** | **17** | `ga.IssueDirective` · `ga.SubmitVerdict` · 9 条 `simos.gov.*` · **3 条外来 doc 写**（`doc_append` / `doc_create` / `doc_edit`） · `gov.SetDiplomaticRelation` · `gov.RecordDiplomaticEvent` · `simos.gov.pay`（★ 后 3 条是纯 `{DM}` + `Ask`，按 §3.1 自动落类2 ✅） |
| **类3 `DM_FREE`** | **37** | — |

★★ **自洽硬证据（这就是它是权威数字的理由）**：分区与**两道既有桶锚点逐位吻合** ——
```
GM 桶     = 140 + 39（共享）= 179 = ShellAssemblySmokeTest.GM_BUCKET_SIZE
决策人桶  =  17 + 37        =  54 = ShellAssemblySmokeTest.DECISION_BUCKET_SIZE
```
⇒ 分区不是"另立一套数"，而是**与既有装配面锚点对得上**的。

### 2.2 我曾用的文件级 grep 取数法**系统性低估**（教训，写给后来者）

控制方原稿给的是 `137 / 15 / 32`，**错了**（低估 +3/+2/+5）。三个原因，都是"按文件 grep"的固有缺陷：

| 原因 | 影响 |
|---|---|
| `git grep -l "Set.of(ToolRole.GM)"` 命中的 **137 个文件里有 1 个不是工具**（`SimosToolSource` 自己的常量声明） | 文件数 ≠ 工具数 |
| **多工具文件**：一个文件里可能装多条工具（外来 AgentLib 工具的适配层就是），按文件数只会数成 1 | §2 把 3 条外来 doc 写数成 1 ⇒ −2 |
| **具名常量**的 `roles`（如 `ROLES_GM` / `ROLES_GM_DM`）grep 不到 | 4 条外来 `{GM}` + 5 条外来 `{GM,DM}` 读口全漏 ⇒ −9 |

⇒ ★ **纪律：工具面的条数一律以"真装配的分区实测"为准，不以文件 grep 为准。** 前者与桶锚点自洽，后者只能当粗估。
（取数命令留档，仅供粗估：`git grep -l "Set<ToolRole> roles()" HEAD -- 'simos-*/src/main/**' | wc -l` = 184 个**文件**，而真工具是 **194** 条 = 140+17+37。）

---

## 3. 设计

### 3.1 ★★ 分类口径：**由 `(roles, gate)` 纯派生，四格收敛成三格**

用户原话「GMDM都能做那肯定是DM的需审批命令」⇒ **`{DM}` 与 `{DM,GM}` 在分类这一维等价**（都表示"DM 能用"）；分类只看**要不要问人**：

| 类 | 判据（**纯派生，不看工具名、不查表**） |
|---|---|
| **类1 `GM_ONLY`** | `roles() == {GM}` —— ★ `gate` **不参与判定**（DM 够不着 ⇒ 问不问人都一样；用户裁定保留其 `Ask`，故**不许**把"GM-only 却有 Ask"当异常） |
| **类2 `DM_APPROVAL`** | `roles()` 含 `DM` 且 `gate()` 是 `Ask` |
| **类3 `DM_FREE`** | `roles()` 含 `DM` 且 `gate()` 是 `ALLOW`（或无 `gate` 覆写 = 读口） |
| **✗ 其余** | **当场抛**（例如 `roles()` 为空、含未知角色、或写工具既非 Ask 也非 ALLOW 的第三态） |

⇒ **那 3 条纯 `{DM}` + `Ask` 自动落进类2**，四格收敛成三格，不需要为它们单列一格，也不需要改它们的任何声明。✅

### 3.2 装配期审计（**fail-closed 的分区**，照 `ToolRoleAudit.requireFaces` 的形制）

新增一个审计（与 `ToolRoleAudit` 同址同制：`simos-ga/.../tools/ToolClassAudit.java`；`ToolClass` 枚举进契约层 `simos-ga-spi`，与 `ToolRole` 并列）；接线点 = `Shell:1391`（`ToolRoleAudit.requireFaces(gmToolFace, decisionAgentFace)` 的下一行）。

1. **全量分区**：遍历**两桶并集**里的每一条工具 ⇒ 按 §3.1 判类；判不出 ⇒ `IllegalStateException`（**装配期抛，不静默**）。
2. **三格具名清单**：审计产出三份**不可变清单**（类1/类2/类3），供测试与后续批次取用。
3. ★★ **判据必须来自 `gate()` 的实得档，不许引入"第二个声明"**：
   - `roles()` 是 context-free 的 ⇒ 直接读；
   - `gate(ToolContext)` **需要上下文** ⇒ 用一个**探针上下文**（`arguments() = Map.of()`）调它，判 `Ask` / `ALLOW`。
   - ★ 为什么必须读真 `gate()`：只有读真的，"分类"才**不可能与行为漂移**。若改成让每条工具再声明一个 `toolClass()`，那就是**第二处会漂移的声明** —— 与 `ToolRoleAudit` 存在的理由（消灭第二处声明）正好相反。
   - ★ 既有的 `gate()` 实现里，**档位（Ask/ALLOW）全是常量**，`arguments()` 只用于拼 summary 文案（`args.get(...)` 的字符串拼接，null 安全）⇒ 探针可行。**但这是要实测确认的**：若某个工具的 `gate()` 在空 args 下抛异常、或需要真身份/权限 ⇒ ★ **停下报 BLOCKED**（附该工具与真实异常），**不许**悄悄换成第二处声明、也不许把它跳过。
4. **本批只读**：不删任何 `Ask`、不改任何 `roles()` 的**集合内容**（D1 那一条只管元素顺序）。

### 3.3 小债（同批还清，逐条给现场）

| # | 债 | 现场 | 处置 |
|---|---|---|---|
| D1 | `{DM,GM}` 有**两种拼法** | ★ **控制方复核后降级**：那"1 条反向拼法"是 `SimosToolSource:557` 的**具名常量** `ROLES_GM_DM`（外来 AgentLib 工具的角色表），不是随手字面量；而 `Set.of` **本就无序** ⇒ 两种写法**语义完全相同**。**这是纯外观，不是漂移风险。** | 把 `ROLES_GM_DM` 的元素顺序对齐多数派（1 行），并在常量注释里写明"与各工具 `roles()` 同集，顺序无语义"——**仅为读代码的人** |
| D2 | `ga.CreateDecisionMaker` 的 description **声称 4 个必填**（含 `affiliation`），实际载荷只有 `{id, allowedTools, cadence}` 且 `affiliation` 会被拒 | `SetDecisionMakerAccessTool` / `SdCreateDecisionMakerTool` 的 `description()` | 按**真实载荷**改写 description |
| D3 | `ga.IssueDirective` / `ga.SubmitVerdict` 的类注没写清**哪种回合里不可达** | 两者的类注 | ★ **批 3a 实测纠正了控制方的措辞**：裁掉世界写的只有**同时决策（冻结）回合**（`ga.RunDecisionMakers`）；`ga.RunDecision` 与 GUI 单人跑是**非冻结**回合，**世界写仍在工具面上** ⇒ 类注只许写「**同时决策（冻结）回合里不在工具面上**」。类2 直写对「非冻结回合 + 真人/外部经决策通道」**是有意义的**，不是死格子 |
| D4 | `social.household.members` 在 `ProposalCatalog` 里的必要性 | `ProposalCatalog` 初始清单（7 条） | ★ **本批不动**，随 3b 一起定 |

---

## 4. 批次划分

| 批 | 内容 | 可动范围 |
|---|---|---|
| **3a**（本批） | §3.1 分类口径 + §3.2 装配期审计 + D1/D2/D3 | 新 `ToolClass`/审计类（`simos-ga-spi` + `simos-ga`）· `SimosToolSource`/`Shell` 接线 · D1 涉及的 32 个 `roles()` 声明 · D2/D3 的 `description()`/类注 |
| **3b**（后批，功能性） | ★ 把那 9 条 gov 类2 工具**加进 `ProposalCatalog`**（让类2 有**不阻塞**的第二条路，见 §5）· `ProposalCatalog` 的可达性是否补资源面 · `SocialHouseholdMembers` 去留 | `ProposalCatalog` · `AppGaProposalHost` · 相关 gov 工具（★ 9 条全部实测支持 `preview`，命中 11–16 处/文件 ⇒ 可行，但**逐条**要验 `preview=true` 真能跑通） · **`GovCreateOfficePlan`（见下）**

★★ **批 3a 顺带发现的活缺陷（未修，越出 3a 红线）**：`simos-gov/.../GovCreateOfficePlan.java:461-472` **仍在 `ga.CreateDecisionMaker` 的载荷里塞 `affiliation`**（由 `GovCreateOfficeTool:535` 批内提交），而 handler **具名拒绝该字段** ⇒ **`simos.gov.createOffice` 必坏**。
★ 这正是 D2 的"下游"：**description 撒了谎，而有人照着那个谎写了代码**（描述声称 `affiliation` 必填）。⇒ **3b 必修**，并把"description ⇔ 真实载荷"的对账加进判据。

---

## 5. ★ 如实记录：类2 的**已知代价**（本批**不改**，因为它不是分类问题）

类2 的 `Ask` 是 `AskKind.SENSITIVE`，AgentLib 原文：「**永远到人**：完全权限的上级也**不许**代批」。于是无人值守时：

```
DM 回合里调一条类2工具
  → Ask(SENSITIVE) → 决策人链 AutoApproveGate → ConfirmGate(恒 empty) → 到人
  → 阻塞等待（上限 APPROVAL_TIMEOUT = 5 分钟）
  → 而此刻 GM 正在 run-decision-makers 那个 MCP 调用里等着（单客户端、串行）
  → GM agent 无法同时调 simos.gm.approve
  → 每条 5 分钟后 APPROVAL_DENIED
```

★ **这是"机制"问题不是"分类"问题**：用户的分类是对的（写世界 ⇒ 要审），但 `Ask` 的机制是**阻塞 + 到人**，而用户的口径是 **GM 审核**。
⇒ **本批不动它**（用户 2026-10-15 只裁了分类）。**缓解手段在 3b**：那 9 条进 `ProposalCatalog` 之后，DM 走 `propose`（零阻塞、GM 用 `gm.packet.decide` 真审）即可绕开阻塞的直写路径。
⇒ 真机复验时**要专门看这一条**：无人值守下类2 直写是否真的 5 分钟超时（若确认，再回来定"要不要把类2 也改成不阻塞"）。

---

## 6. 不变量与验收判据

| # | 不变量 | 判据 |
|---|---|---|
| **C1** | **零口径变更**：本批不删任何 `Ask`、不改任何 `roles()` 的**集合内容**（只统一拼法） | ★ **类1=140 / 类2=17 / 类3=37**（**真装配**实测，与两道桶锚点 `179` / `54` **自洽**）—— 以实测为准，**不是** §2 早期那版文件 grep 的 137/15/32 |
| **C2** | **每条工具都落进三格之一** | 审计在装配期跑完不抛；负向：造一条"判不出类"的工具 ⇒ 装配期抛 |
| **C3** | **审计与工具面同源**：清单从桶并集派生，**没有第二处手写清单** | 源码扫描：审计实现里没有工具名/条数的字面量 |
| **C4** | **`roles()` 的拼法只有一种** | `grep -c "ToolRole.GM, ToolRole.DM"` = 0 |
| **C5** | 装配期审计**不得放松**任何既有审计（`ToolRoleAudit.requireFaces` 等） | 既有装配期用例全绿 |

---

## 7. 待裁定

**无。** 用户 2026-10-15 已定分类口径；§3.3 的 D1/D2/D3 是"照事实改文案/拼法"，无内容分歧；D4 推到 3b；§5 是**如实记录**不构成提问。
★ 唯一需要**控制方**当心的是：§3.1 把"纯 `{DM}` + `Ask`"判成类2 —— 这是我从用户原话（「GMDM都能做那肯定是DM的需审批命令」+ `{DM}` 与 `{DM,GM}` 在这一维等价）推出的**收敛**，若用户认为那 3 条该单列一格，本批的审计判据要改一行。

---

## 8. 批 3b 设计：让类2 的世界写也能走决策包（**纯加法**）

### 8.0 用户意图（逐字）

> 「这个决策包是何意味，难道DM调一个和世界修改有关的工具，GM必须立马批准才能继续做，而不是先标个假完成GM统一审批吗」（2026-10-15）

⇒ 用户要的正是**决策包那条路**：**DM 先记录、继续干活、GM 统一批**。而类2 现在的机制是**反面**（当场写、当场等人点、5 分钟上限）。

### 8.1 目标

**让那 9 条 gov 类2 工具也能被 `propose`** ⇒ 无人值守时 DM 走 `propose`（**零阻塞**），GM 用 `gm.packet.decide` **真审**、`gm.packet.execute` 执行。

### 8.2 ★★ 纯加法（这是本批的红线）

| 不动 | 为什么 |
|---|---|
| `roles()` / `gate()` **一个都不改** | 类归属不动 ⇒ §2.1 的三格计数 `140/17/37` 不变 |
| **不删类2 的直写路径** | 它对**有真人看着**的场合（真人经决策通道提交、GUI 点批准）仍然有意义 |
| **不改审批链**（`AutoApproveGate → ConfirmGate`） | 与本批无关 |
| **不动 `APPROVAL_TIMEOUT`** | ★ 用户说过"取消 5 分钟上限"，但**取消做不到他要的效果**：传 `null` ⇒ AgentLib 当 `ZERO` ⇒ **立刻拒**；给巨大值 ⇒ **死锁**（GM 卡在 `run-decision-makers` 里等 DM 批次，无法同时调 `gm.approve`）。⇒ **本批的正确解是"让阻塞路径根本不被走到"**，上限退化成兜底 |

### 8.3 三件事

1. ★ **9 条 gov 类2 工具加进 `ProposalCatalog`**：`simos.gov.{assignPosts, defineCurrency, expandHousehold, issueMoney, openPostsToMarket, renameCurrency, setBudgetPolicy, setEstablishment, setFxRate}`。
   - ★ **逐条**验 `preview=true` 真能跑通（`preview` 命中 11–16 处/文件只是**静态迹象**，不是证据）；跑不通的**单列**并说明。
   - 每条要给出 `DecisionProposable` 的 `preview` + `targets`（后者从 args+preview 递归提取，**照既有 7 条的配置形制**：`skipKeys` / `createsUnit`）。
2. **`ProposalCatalog` 的可达性**：现在 `propose` 只查 `allowedTools`（白名单），**不查资源面** ⇒ 一个 DM 可以 propose 一条它**够不着**的调用（那条 call 在 GM 执行时才会失败）。⇒ **补资源面校验**（复用 `ProposalCatalog` 已有的 scope 校验段，别造第二份）。
3. **`simos.social.household.members` 去留**：它与"世界写"不同（是查询），且已在 catalog 里 ⇒ 决定留还是移出（**要给出理由**）。

### 8.4 判据

| # | 判据 |
|---|---|
| **B1** | 9 条**逐条** `propose` 成功（真 preview + scope 通过）⇒ 包里出现该 call、`gm.packet` 可见 |
| **B2** | ★ **零口径变更**：三格计数仍 `140/17/37`；`roles()`/`gate()` 的 `git diff` **为空** |
| **B3** | ★★ **DM 侧零阻塞**：`propose` 路径**不产生任何 `Ask`/审批**（源码扫描 + 执行期断言） |
| **B4** | GM 侧照旧：`gm.packet.decide` + `gm.packet.execute` 能把这条 call 执行掉（**一条 revision**） |
| **B5** | 可达性：一个**够不着**目标的 DM propose ⇒ **具名拒**（`FORBIDDEN`），包不变 |
| **B6** | A7 式源码扫描：catalog 里没有第二处手写清单（名单从 `ProposalCatalog` 一处派生） |

### 8.5 同源缺陷（另开责任区，不在本批）

`ProvinceApplyPlan:715-727` **仍在拼 `affiliation`**（`ProvinceApplyTool.buildBatch` 逐 GOV 调用）⇒ **`simos.province.apply` 很可能同样整批被拒**。同一个谎的产物 ⇒ **另派**，要求"**先复现红再修**"（照 `createOffice` 那两任的验收形制）。

---

## 9. ★★★ 批 3b 被 BLOCKED 揭出的**更严重的事**：`planOnly` 前置条件（2026-10-15）

### 9.1 catalog 工具有一条**成文前置条件**：必须支持内部参数 `planOnly=true`

`DecisionEffectExecutor` 类注（`:46-50`）写得很清楚：
> 目标工具的 `preview=false` 路径直接 `submitBatch` —— 若执行器也走那条路，N 条 call 会落 N 条 revision，且不可能把 outcome 与效果写进同一批。**故目标工具新增内部参数 `planOnly=true`：走与 `preview=false` 完全相同的纯推导与组批，但在提交前把命令批返回给本执行器。**

⇒ **`ProposalCatalog` 的每一条工具都必须支持它** —— 否则 `gm.packet.execute` 的"规划阶段"**会真落盘**。

### 9.2 ★★ 实测：**现有 7 条里 4 条违反这条前置条件**（**在 main 里，今天就可达**）

| 工具 | 是否真检查 `planOnly` |
|---|---|
| `GovRecruitTool` · `GovRetireStaffTool` · `GovSelectExamineesTool` · `GovDispatchTeamTool` | ❌ **收了参数、`apply` 里从不检查** ⇒ 直接 `submitBatchWithReceipt`（**写盘**） |
| `RaiseUnitTool` · `LevyRegionTool` · `SocialHouseholdMembersTool` | ✅ 写之前先返回待提交命令批（★ 前者用 `if (planOnly)`、末者用 `request.planOnly()`） |

**后果链（双写）**：
```
DM propose（这 4 条任一，已在 catalog 里）→ GM APPROVE → GM gm.packet.execute
  → 执行器注入 planOnly=true（DecisionEffectExecutor:352），期望"只规划、不落盘、回传 plannedCommands"
  → 那 4 条忽略 planOnly ⇒ **真的落盘了**（一条 revision 已经产生）
  → 又不回传 plannedCommands ⇒ 执行器报 PLANNING_FAILED（:374-377）
  → GM 看到失败、重试 ⇒ **再落一次**        ← ★★ 世界被写了两次，且 outcome 从未回写
```
★ **`GovBatchViews.planOnly(batch)`（`:34`）就是为此准备的现成件，而全仓零调用者** —— 四条工具把参数一路传下去、到 `apply` 丢掉。

★ **为什么没被发现**：唯一跑 `gm.packet.execute` 的 `RealLlmGovScenarioTest` **被 `SIMOS_REAL_LLM` 默认跳过**；`simos-gov/src/test` 里 `planOnly` **零覆盖**。⇒ 与 §8.5 的 `province.apply` 同一个"门控 + 无覆盖"的死角。

### 9.3 ★ 处置与优先级（控制方裁定）

| 优先 | 内容 | 为什么这个顺序 |
|---|---|---|
| **P0** | **修那 4 条既有工具**，接上现成的 `GovBatchViews.planOnly` | 它们**今天就在 catalog 里、今天就可达** ⇒ 双写风险**已经在 main 里** |
| **P1** | 给那 9 条 gov 类2工具补 `planOnly`（然后 3b 的补丁才能上） | 不补就上 3b = **把双写放进 main** ⇒ **禁止"先上一半"** |
| **P2** | 3b 本体（`git apply .superpowers/sdd/2026-10-15-catalog-gov-tools/proposed-catalog-3b.patch`） | 它已验证 `git apply --check` 通过 |

★ **硬性要求**：P0 与 P1 都必须**先复现红**（照 `createOffice`/`province.apply` 那两任的验收形制：仓库外夹具 → 真调 → 拿原文），并给出**"执行后世界只多一条 revision"**的判据。

### 9.4 ★ 控制方自我更正（设计书 §8 两处错）

1. **§8.3(1) 的可行性判据不成立**：我写"9 条全部实测支持 `preview`（命中 11–16 处/文件）⇒ 可行" —— **漏了 `planOnly` 这条前置条件**。`preview` 命中数只是静态迹象，**不是"能进 catalog"的证据**。
2. **§8.3(3) 的事实错误**：我写"`simos.social.household.members` 是**查询**不是世界写" —— **错**。它是 `social.Add/Remove/TransferHouseholdMembers` **三条世界写的入口**（`SocialHouseholdMembersTool:29-34`）。⇒ **它留在 catalog**（已在、planOnly 合规、`householdId`/`from`/`to` 已被 scope 校验），**不移出**。

### 9.5 本批**未做**（如实）

- 补 `planOnly`（P0/P1，范围外）
- **"工具面"那一维**：`validateCall` 在 `dm.allowedTools()` **为空时整条白名单检查被跳过** ⇒ DM 能 propose catalog 里**任何一条**。真封它要动 `DecisionCallerFactory.whitelistFor`（范围外，且会红 `GmActAsToolTest:94`）⇒ **单独立项**。
- **资源面漏的三维**（逐条见 3b 台账 §3.3）：**币种**（`ResourcePaths` 无币种路径）· `issueMoney` 的 `actor:<q>_<r>` 国库格 · `assignPosts`/`openPostsToMarket` 的 `activity` 单位。

---

## 10. §9 的落地结果与两处更正（2026-10-15）

### 10.1 ★ 全扫（机械判据，改前）—— 控制方 §9.2 的核实**逐条成立**

| 判据 | 结果 |
|---|---|
| gov 有写入口的工具 | **23 条** |
| 「收了 `planOnly` 却不用」 | **4 条违反**（`GovRecruit` / `GovRetireStaff` / `GovSelectExaminees` / `GovDispatchTeam`）—— **全在 catalog ⇒ 今天可达** |
| catalog 里"完全不支持 `planOnly`" | **0** |
| 3b 拟入 catalog 的 9 条 | **连参数都没读** |
| 邻近模块（unit / economy / army）既有 `planOnly` 工具 | **全合规** |
| ★ **范围外同族违反** | **7 条**（`simos-social` 的 `AbstractHouseholdGmTool` 7 个子类的 submit 路径不看 `planOnly`）—— **均不在 catalog ⇒ 潜伏** |
| 非 catalog 的 10 条 gov 写工具 | 两条判据都不满足 ⇒ **未改，记为潜伏风险（进 catalog 前必须补）** |

### 10.2 ★★ 真实红/绿（仓库外夹具，真调 `gm.packet.execute`）

**改前红（双写实证）**：
```
headDelta=1（planOnly 真写盘）→ code=TOOL_ERROR「目标工具未按 planOnly 契约返回 plannedCommands」（内裹 "revision":10）
→ GM 重试 ⇒ headDelta=1（revision 11）          ⇒ ★★ 两次 execute = 两条 revision
outcome=(无 outcome) · 编制 50→47
另 3 条 P0 + defineCurrency 直接调：plannedCommands=-1 且 headDelta=1 ⇒ 4 条全真落盘
```
**改后绿**：`plannedCommands` 6 条 + `headDelta=0` · execute 一次 ⇒ `revision=9` **恰好一条**（含 outcome 回写）· 重试 `BAD_REQUEST` 零落盘 · ★ **preview 改前改后 md5 相同**（`061e72889f436c794c79f6277f0df842`，20 字段逐字不变）。

**修法**：13 文件（全在 `simos-gov/.../tools/write/`）—— P0 四条在写之前插 `if (planOnly) return ok(GovBatchViews.planOnly(batch));`；P1 九条照 `AbstractEconomyGmTool:112` 形制读 args 分支；两条 policy 工具让 **noop 支优先**（noop ⇒ 空批）。★ **`GovBatchViews.java` 0 字节 diff**，其现成件 `planOnly(batch)` 的调用者 **0 → 13 文件 / 15 处**。`roles()`/`gate()`/`jsonSchema()`/`description()` **零改动**。

### 10.3 ★ 控制方更正 §9.2 的一处措辞（字面 vs 语义）

§9.2 我写「执行器报 `PLANNING_FAILED`」—— **语义成立、字面不成立**：`PLANNING_FAILED` 是**内部枚举**；GM **可见的码是 `TOOL_ERROR`**，正文是「目标工具未按 planOnly 契约返回 plannedCommands」。⇒ 引用时以**可见码 + 正文**为准。

### 10.4 控制方裁定（两条）

| # | 问题 | 裁定 |
|---|---|---|
| ① | `simos-social` 那 7 条同族违反是否另派 owner | **暂不派**。它们**不在 catalog ⇒ 没有活风险**；但**进 catalog 前必修**（写进 §10.1 的潜伏清单）。理由：先清活风险，不为"潜伏"开新战线 |
| ② | 非 catalog 的 10 条 gov 写工具是否**预防性**补 | **不补**。实现方按"只修可达的 + 即将可达的"判据办事是**对的**；预防性改动 = 未被任何验收覆盖的新面。⇒ 同样进潜伏清单 |

★ **潜伏清单（两条判据：① 在 catalog 里 或 ② 将被加进 catalog ⇒ 必修；其余先记）**：
`simos-social` 7 条（`AbstractHouseholdGmTool` 子类）· gov 非 catalog 10 条（absorbUnit / applyStaffing / createOffice / dismiss / pay / remit / transferTreasury / provinceApply / provinceAssignCities / regionClearStructures）。

### 10.5 未验证（如实）

- P1 另外 8 条只做**结构验证**（同处插入 + 编译 + grep）—— 3b 未上 ⇒ 执行器**够不到**它们（`catalog.requireTool` 会抛）；P1 里**真调过的只有 `defineCurrency`**。
- 两条 policy 工具的 **noop + planOnly 空批**分支未实测。
- **无测试**（红线）—— ★ 而 `simos-gov/src/test` 里 `planOnly` **零覆盖**，**正是这个缺陷长期存活的原因**。⇒ 测试面必补（见 §10.6）。

### 10.6 测试面待办（下一批）

1. ★ **`planOnly` 的覆蓋**（`simos-gov/src/test` 目前为零）：每条 catalog 成员都应有"`planOnly=true` ⇒ 返回 `plannedCommands` 且 **零 revision**"的判据。
2. ★ **`gm.packet.execute` 的端到端**：一次执行 ⇒ **恰好一条 revision**（含 outcome 回写）；重试 ⇒ 非成功且零落盘。
3. ★ **把两任修复者留在 `/tmp` 的探针沉淀成真测试**（`OfficeProbe` / `CreateOfficeAcceptProbe` / `planonly-probe` / `province-probe`）—— 它们**能走到这些工具并读到真状态**，而仓里那条 e2e 走不到（`SIMOS_REAL_LLM` 门控 + `seedGenesis()` 缺 army 片）。
4. §9.5 的两条：`validateCall` 在 `allowedTools` 为空时**跳过整条白名单检查** · 资源面漏的三维。

---

## 11. 批 3b 落地结果（2026-10-15）

### 11.1 ★ 前置条件复核（**通过**）
- **静态**：九条的 `if (planOnly)` 一律排在**唯一**提交点之前（如 `GovIssueMoneyTool:239 < 243`），每条直接 `writes.` 调用数 = **0**（无第二条写路；两条 policy 工具多一处 noop 空批分支，是有意为之）。
- **动态**：九条**逐条**真调 `preview=false + planOnly=true` ⇒ `plannedCommands` 1/1/2/6/2/1/1/2/1 条 **且 `headDelta=0`** ⇒ `planOnlyOk=9/9`。
  ★ **顺带补上了 `c27febc7` §10.5 自认未验证的另外 8 条** —— 那条"只做结构验证"的欠账**已还清**。

### 11.2 应用与验收
- 补丁 `git apply` **cleanly**（无需手工落地）；落地后 md5 `30b35b74a4f194792662610e11ff3695` 与上一任 proposed **逐字节相同**；`+59/−3` 单文件。
- **九条逐条真 propose：9/9 成功**（生产装配，`SmallWorld` 8 片含 army）；`gm.packet` 读回 9 条工具名齐全。
- **B1–B6 全过**。★ **B3 做了判别对照**（这是本批最漂亮的一笔）：
  ```
  同一链上直接调 setBudgetPolicy ⇒ APPROVAL_DENIED，asks=1     ← 类2 的阻塞路径真的存在
  同一链上走 propose            ⇒ success，     asks 不增      ← 决策包路径零阻塞（零审批）
  ```
  ⇒ **"DM 侧零阻塞"不是推理，是对照实验测出来的。**
- ★ **B4 的 revision 逐跳计数**（世界真的只变一次，有数字）：
  ```
  head 1 →(九条 planOnly) 0 →(9 propose) 10 →(B3 对照) 11 →(B4 propose) 12
       →(confirm) 13 →(decide APPROVE) 14 →(execute) 15 恰 +1，含 outcome 回写
       → 重试 BAD_REQUEST 15→15
  国库 copper 100000 → 107777（+7777，真落盘）· plannedCommands=2 且 headDelta=0
  ```

### 11.3 ★★ 控制方更正：真实流程 **多一步 `confirm`**

我在 §8.4 的 B4 里写「`propose` → `decide` → `execute`」——**漏了一步**：
`propose` 落的是 **`DRAFT`**，而 **GM 不能裁决 DRAFT**（`GmDecidePacketTool`：只有 `PENDING` 可裁决）⇒ **真实流程是**
```
DM propose → DM confirm（→ PENDING）→ GM decide → GM execute
```
★ 这不是缺陷，是**既有状态机**，而且正是用户「DM 确认决策」那条裁定的落地。⇒ 引用流程时**必须带 `confirm`**。

### 11.4 ★ 控制方裁定（两条，2026-10-15）

| # | 问题 | 裁定 |
|---|---|---|
| **(a)** | 九条工具的 `description()` 写「决策人可省 `govUnitId` = 自己的 GOV」，但 **propose 路径用 `AgentIdentity.external()` ⇒ 走不到那个便利**，DM 必须**显式**给 `govUnitId` | ★ **必须改描述**（或让两条路径行为一致）。理由：**这正是本仓反复犯的那个病 —— 描述承诺一个走不到的便利**（与 `affiliation` 那句谎同族）。⇒ 已派单：**先核清两条路径各自的真实行为**，再照事实写 |
| **(b)** | 任务书 B4 漏了 `confirm` 那一步 | ✅ **接受更正**（见 §11.3），设计书已改 |

### 11.5 未做（如实）
- **资源面漏的三维**：币种（`ResourcePaths` 无币种路径）· `issueMoney` 的 `actor:<q>_<r>` 国库格 · `assignPosts`/`openPostsToMarket` 的 `activity` 单位 —— 要改 `ResourcePaths` + 各 Scope，**越界**。
- **`allowedTools` 为空 ⇒ 整条白名单检查被跳过**（`DecisionCallerFactory` 越界）⇒ 单独立项。
- 两条 policy 的 **noop + planOnly 空批**分支仍未实测。
- 另外 8 条**未逐条走完** `decide → execute`（只验了 `planOnly` 交批 + `propose` 成功）。
- ★ **证据全在仓库外，未沉淀成回归用例**（红线）⇒ 见 §10.6 测试面待办。

---

## 12. 真 LLM 验证（2026-10-15 凌晨）：**跑不起来，两个门控用例都坏** —— 但坏在测试侧

### 12.0 结论先说

**生产代码没有回归**（3289 条非门控用例全绿）。★ 跑不起来的两个原因**都在测试侧**：
1. `RealLlmUnitDecisionLoopTest` —— ★★ **前提早就作废，出生就是红的**（见 §12.2）
2. `RealLlmGovScenarioTest` —— 引用 10 处**已改名**的工具（见 §12.3）

⇒ **真 LLM 验证目前不可能完成**，除非先把这两处修好。**这是"门控 + 坏夹具 + 零覆盖"死角的第 5、6 次。**

### 12.1 端点与跑法（已验证，可直接复用）

- `mosire-flash`（远程中转站）与 `gemma4-local`（本机 llama.cpp）**tool calling 都正常**：实测 `finish_reason=tool_calls` + 参数正确（0.7s / 1.1s）。
- `config/llm-providers.json` **在 git 里** ⇒ 用默认 provider 即**零配置改动**。
- 跑法（类注原文）：`SIMOS_REAL_LLM=1 tools/mvn-lock.sh -pl simos-app -am -Dtest='RealLlm…' -Dsurefire.failIfNoSpecifiedTests=false test`
- ★ 建议在**独立工作树**里跑（不被在飞的批污染）：`git worktree add --detach /tmp/x HEAD`。

### 12.2 ★★ `RealLlmUnitDecisionLoopTest`：**夹具前提已作废 ⇒ 出生即红**

**症状**（HEAD `932602aa`，独立工作树，真跑）：3 个用例全在 `startShell:260 -> seedGenesis:1188` 炸：
```
IllegalArgument: checkpoint 信封要写的模块没有装配 codec（信封与本实例的 codec 表不同源）: gov
```
⇒ **它给的 codec 表只有 `Map/Social/Unit/Ga/Economy/Actor`，而创世状态里有 `gov` 与 `army` 两个切片** ⇒ 该门控用例**连创世都过不去**。
（控制方在临时工作树补上 `GovCodec`+`ArmyCodec` 作**诊断**后，它才跑起来；该补丁**不是交付物**。）

**跑起来之后**：真模型 14 次 LLM 调用 / 24 次工具调用，一轮走完（`head 1→7`），★ **但断言仍红**，且红的是**夹具自检** `:345`：
```
Expecting value to be true but was false
  at RealLlmUnitDecisionLoopTest.fixtureScopeIsWiredAndOutOfScopeControlIsReal(:345)
```
`:345` 断言 `ResourceScopesFor(DecisionScopeFunctions.defaults(), dm, state, MAP_ID)` 算出的 `map` 作用域**允许** `Map1/hex/1_1`。

**根因（回代码核实，`DecisionScopeFunctions` 类注 `:38-40` 原文）**：
> **没有归属 ⇒ deny-all**（裸决策人；用户原话「允许创建没有更多附加状态的单个决策人」）：四个命名空间全部 `ResourceScope.none()`——不抛、不回落、不给全量

而夹具造的是 `new DecisionMaker(DM, Set.of(), accessLimit(), …)` —— **不带归属**（`:27` 原文：「新形状：决策人**不带归属**（`DecisionMaker` 已去掉那个字段）」）。
⇒ **按设计就是 deny-all** ⇒ `narrowTo(accessLimit)` 只**收窄**、**不会加**范围 ⇒ 该断言**不可能成立**。
⇒ 它假设的是 **2026-10-12「归属归模块」之前**的旧设计（决策人身上带 `Affiliation`，范围 = `ArmyScope(位置+视野圈) ∩ accessLimit`）。**它出生就是红的**，且因门控**从未被告知**。

**旁证（同一轮真跑的产物完全自洽）**：模型如实报告「可达面只有 `docs`、`ga=decision-packet/dm-u1` 与 `peer=gm`」，8 次读被 `RESOURCE_DENIED`、出令被裁「超出出令决策人 dm-u1 的可达面，命令被拒（目标资源）: unit:u-1」、`taxAfter=0`。
★ 测试自己记了 `adjudicationGap`：「令里有命令被 GM 裁决拒……**如实记录，不伪造**」——**这测试的自我诚实是好的，坏的是前提**。

**修法（测试侧，两个方向选一）**：① 给夹具的决策人**一条真的 army 归属**（让 `ArmyScope` 生效），断言随之恢复有意义；② 或把断言改成"**裸决策人 = deny-all**"并新增一条"有归属 ⇒ 有范围"的对照。★ 建议 **①+② 都做**：一条证明"有归属才有范围"，一条证明"没归属就 deny-all"（两条都是**用户裁定**的落地）。

### 12.3 `RealLlmGovScenarioTest`：10 处旧名（与 §12 首版同）

见本文件 §12 首版（`docs/superpowers/plans/2026-10-14-gm-dm-interaction.md` §12.1）：`:159-162` 的 `DECISION_ALLOWED_TOOLS` + `:945`–`:985` 的**提示词**都还在叫 `simos.sd.packet.intent` / `simos.sd.propose` / `simos.sd.packet.submit`。
★ 控制方 03:53 复核：覆盖死角批改了该文件（`+9−2`）但**这 10 处旧名仍在** ⇒ 仍需修。

### 12.4 §11.4(a) 的落地：**三路径实测矩阵**（控制方裁定成立）

9 条 gov 工具「决策人可省 `govUnitId`」这条便利，**只在一条路径上成立**：

| 路径 | 身份来源 | 省 `govUnitId` | 结果 |
|---|---|---|---|
| **GM 面直调** | `AgentIdentity.external()`（`Shell.gmCaller:1706`） | 省 | **BAD_REQUEST**「govUnitId 必填（GM 调用必须显式点名要操作的 GOV 单位）」9/9 · headDelta=0 |
| **DM 直写** | `decision-maker:<id>`（`DecisionCallerFactory:218`） | 省 | ✅ **成功 9/9**，`govDerivedFromIdentity=true`，真写一笔（国库 copper +4321） |
| **propose** | `AgentIdentity.external()`（`ProposalCatalog.preview:250`） | 省 | **BAD_REQUEST**「目标工具预览失败(BAD_REQUEST): govUnitId 必填…」9/9 · 包不变 |
| **execute** | `external()` + `SYSTEM`（`DecisionEffectExecutor.planCall:360`） | 省 | 同 propose；且**构造上不可达**（propose 用同一身份先拒） |

⇒ 与 §11.4(a) 的裁定一致：**描述承诺了一个只有一条路径走得通的便利** ⇒ 已改 9/9 文件（`description()` + `jsonSchema` 字段说明 + 类注 + 末尾逐路径句），机械自证 `roles(`/`gate(`/载荷键/校验/`planOnly` 命中数 = **0**；改后三路径矩阵**逐格与改前一致**（`head=15` 同数）。

### 12.5 记账：本轮新增的三条同族待办（都不阻塞真 LLM 前置）

| # | 位置 | 事 |
|---|---|---|
| ① | 9 条工具描述里的「★ 需 GM 在审批面点头（决策人链路会停在待批）」 | 只对 **DM 直写**成立（§11.2 B3：propose 是 success、asks 不增）⇒ 同族第二处 |
| ② | `GovToolSupport:159` 的错误正文 | 对两条 policy 工具也写 `govUnitId`，而字段实为 `payloadJson.unitId` ⇒ 改消息要动 `GovToolSupport` |
| ③ | `simos-social` 7 条潜伏 `planOnly` | §10.4 潜伏清单，未扫 |
