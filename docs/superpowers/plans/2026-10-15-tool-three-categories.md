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

## 2. 现状实测（回代码，HEAD `b88dc240`）

| 形状 | 条数 | 其中 `Ask` | 其中 `ALLOW` |
|---|---|---|---|
| 纯 `{GM}` | **137** | **109**（★ 用户裁定：**保留**，不碍事） | 28 |
| `{DM,GM}` | **32** | **12** | **20** |
| 纯 `{DM}` | **15** | **3** | 12 |
| **合计** | **184** | 134（`Ask` 字面 66 ∪ 窄写基类 68） | — |

取数命令（可复核）：
```bash
git grep -l "Set<ToolRole> roles()" HEAD -- 'simos-*/src/main/**' | wc -l          # 184
git grep -l "Set.of(ToolRole.GM)" HEAD -- 'simos-*/src/main/**' | wc -l             # 137
git grep -l "ToolRole.DM, ToolRole.GM\|ToolRole.GM, ToolRole.DM" HEAD -- 'simos-*/src/main/**' | wc -l   # 32
```

**那 3 条纯 `{DM}` + `Ask`**（★ 三分类的原始表述里没有这一格）：
`gov.RecordDiplomaticEvent` · `gov.SetDiplomaticRelation` · `simos.gov.pay`

**12 条 `{DM,GM}` + `Ask`（类2）**：
`ga.IssueDirective` · `ga.SubmitVerdict` · `simos.gov.{assignPosts, defineCurrency, expandHousehold, issueMoney, openPostsToMarket, renameCurrency, setBudgetPolicy, setEstablishment, setFxRate}` · 外来 `workspace` 写

★ **全部 12 条 + 那 3 条都是 `AskKind.SENSITIVE`**（或经 `AbstractNarrowWriteTool`，它硬编码 `AskKind.SENSITIVE`）。

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

新增一个审计（契约层 `simos-ga-spi` 定接口 + `simos-ga` 给实现，与 `ToolRoleAudit` 同址同制）：

1. **全量分区**：遍历**两桶并集**里的每一条工具 ⇒ 按 §3.1 判类；判不出 ⇒ `IllegalStateException`（**装配期抛，不静默**）。
2. **三格计数与具名清单**：审计对象里带 `toolClassOf(tool)` 与三份不可变清单（类1/类2/类3），供**测试与后续批次**取用（★ 不做第二处手写清单——清单**从工具面派生**）。
3. ★ **不新增第二处声明**：判据只有 §3.1 那三条，**没有"期望表"**（有期望表就是又一处会漂移的声明）。审计保证的是"**每条工具都落进三格之一**"这条**结构性**不变量。
4. ★ **本期不改变任何工具的行为**：不删任何 `Ask`、不改任何 `roles()`。审计只**读**。

### 3.3 小债（同批还清，逐条给现场）

| # | 债 | 现场 | 处置 |
|---|---|---|---|
| D1 | `{DM,GM}` 有**两种拼法** | `Set.of(ToolRole.DM, ToolRole.GM)` 31 条 vs `Set.of(ToolRole.GM, ToolRole.DM)` 1 条 | 统一成**一种**（取多数派拼法），并把拼法写成常量/助手，**不留两种** |
| D2 | `ga.CreateDecisionMaker` 的 description **声称 4 个必填**（含 `affiliation`），实际载荷只有 `{id, allowedTools, cadence}` 且 `affiliation` 会被拒 | `SetDecisionMakerAccessTool` / `SdCreateDecisionMakerTool` 的 `description()` | 按**真实载荷**改写 description |
| D3 | `ga.IssueDirective` / `ga.SubmitVerdict` 的类注没写"**决策回合内不可达**" | 两者的类注 | 补一句：类2 只对**非回合路径**（真人/外部经决策通道）有意义；决策回合的工具面上没有它们（冻结回合只留决策包写） |
| D4 | `social.household.members` 在 `ProposalCatalog` 里的必要性 | `ProposalCatalog` 初始清单（7 条） | ★ **本批不动**，随 3b 一起定 |

---

## 4. 批次划分

| 批 | 内容 | 可动范围 |
|---|---|---|
| **3a**（本批） | §3.1 分类口径 + §3.2 装配期审计 + D1/D2/D3 | 新 `ToolClass`/审计类（`simos-ga-spi` + `simos-ga`）· `SimosToolSource`/`Shell` 接线 · D1 涉及的 32 个 `roles()` 声明 · D2/D3 的 `description()`/类注 |
| **3b**（后批，功能性） | ★ 把那 9 条 gov 类2 工具**加进 `ProposalCatalog`**（让类2 有**不阻塞**的第二条路，见 §5）· `ProposalCatalog` 的可达性是否补资源面 · `SocialHouseholdMembers` 去留 | `ProposalCatalog` · `AppGaProposalHost` · 相关 gov 工具（★ 9 条全部实测支持 `preview`，命中 11–16 处/文件 ⇒ 可行，但**逐条**要验 `preview=true` 真能跑通） |

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
| **C1** | **零口径变更**：本批不删任何 `Ask`、不改任何 `roles()` 的**集合内容**（只统一拼法） | 类1=137 / 类2=15 / 类3=32；三格计数与 §2 实测逐字一致 |
| **C2** | **每条工具都落进三格之一** | 审计在装配期跑完不抛；负向：造一条"判不出类"的工具 ⇒ 装配期抛 |
| **C3** | **审计与工具面同源**：清单从桶并集派生，**没有第二处手写清单** | 源码扫描：审计实现里没有工具名/条数的字面量 |
| **C4** | **`roles()` 的拼法只有一种** | `grep -c "ToolRole.GM, ToolRole.DM"` = 0 |
| **C5** | 装配期审计**不得放松**任何既有审计（`ToolRoleAudit.requireFaces` 等） | 既有装配期用例全绿 |

---

## 7. 待裁定

**无。** 用户 2026-10-15 已定分类口径；§3.3 的 D1/D2/D3 是"照事实改文案/拼法"，无内容分歧；D4 推到 3b；§5 是**如实记录**不构成提问。
★ 唯一需要**控制方**当心的是：§3.1 把"纯 `{DM}` + `Ask`"判成类2 —— 这是我从用户原话（「GMDM都能做那肯定是DM的需审批命令」+ `{DM}` 与 `{DM,GM}` 在这一维等价）推出的**收敛**，若用户认为那 3 条该单列一格，本批的审计判据要改一行。
