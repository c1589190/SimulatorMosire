# 2026-10-08 货币发行 / 命名 / 决策人审批链 现状调查（task-3 · cur-scout）

**性质**：只读调查 —— 不改生产代码、不写测试、不跑 Maven、不 commit。
**纪律**（AGENTS §四「台账/计划措辞一律回代码核」）：每条结论带 `file:line`；不确定的单列 §9「我没核到的」。
**检索写法声明**：本报告**不用** `git grep -- 'simos-*/src/main'`（该写法在本仓**静默 0 命中**，已实测），一律显式目录
`grep -rn … simos-*/src/main`，必要时再过滤 `.claude/worktrees/**`（历史 worktree 副本，不属当前工作树）。
**结论一律读的是当前工作树**（`git status` 干净，只有 3 份 `docs/superpowers/reports/*.md` 未跟踪，其中本文件是我独占的那份）。

**用户 2026-10-08 设计原话（逐字，最高权威）**：

> 「首先，一个GOV拥有发行货币的权利，这个GOV可以发行一种货币，并给这种货币命名、改名等（落成决策人工具，需要GM审批的那种），
> 可以同样通过决策人工具发行货币，这个具体的货币生产后面再做，目前的实际流程就是DM定发多少然后走决策人工具；
> 然后，发货币的政府的总疆域……默认就是这种货币的市场区……市场区本质上是一个政府维护的"法律规定"……
> …重叠辖区是不被允许的……三不管地带的市场行为完全自由，全由市场机制调控」

（市场区 / 口岸 / 三不管属 task-1/2/4 的范围；本报告只在**货币侧**与它冲突的地方引用。）

---

## 0. 一句话结论

代码里「发币」已有**四条腿**（创世 `INITIAL_ENDOWMENT`、周期铸币 `GovernmentSeigniorage`、日结算里"余额不足的单边发行腿"、
**零调用方**的回笼写口 `TreasuryWithdrawal`），但**没有一条是"GOV 自己决定发多少"**：唯一的自动铸币量是 **GM 写在
`Government.seignioragePerCycle` 上的周期性固定增发**；决策人侧**没有任何**铸造 / 命名 / 改名工具，货币**没有"显示名"这一维**
（`CurrencyDef(id, scale)`，`CurrencyId` 就是 id 本身），币种词表是**静态硬编码的 1 条 `silver`**、不是世界状态；
「一币一发行人」有 **9 处**落点守卫（§4）、守得很严 —— 但守卫面只覆盖**日结算转移路径**：`actor.Seed` / `actor.AdjustAccounts`
（**都不是** GM-only）可以凭空造出任意币种 id 的余额、且**不写任何发行审计**。

---

## 1. Q1 「发货币」的实际路径（逐步 file:line）

### 1.1 运行期唯一自动铸币 = `GovernmentSeigniorage`（周期开始日，直接写国库余额）

| 步 | 落点 |
|---|---|
| 触发 | `EconomySettlement.java:784` `if (GovernmentSeigniorage.isCycleStart(base, day))`，:786 调 `settleCycleStart` |
| 触发日判据 | `GovernmentSeigniorage.java:50-63`：`day == 1 ‖ (day-1) % max(industry.cycleDays) == 0`（无产业 ⇒ 只有 day==1；多产业取**最大**周期） |
| 金额 | `GovernmentSeigniorage.java:89` `long amount = government.seignioragePerCycle()`（**一个标量**，不是逐币种） |
| 逐币种 | `GovernmentSeigniorage.java:101` `for (CurrencyId currency : government.issuable())` ⇒ **每个 issuable 币种各发 `amount`** |
| 落账 | `GovernmentSeigniorage.java:102-106`：直接 `treasury.replaceMoney(...)`（**不走 Transfer**，`AccountSession` 的国库工作副本） |
| 审计 | `GovernmentSeigniorage.java:108-121`：写一条 `MoneyIssuanceKind.FISCAL_ISSUE` 到 `session.sheet().moneyIssuances()`，id = `gov-seigniorage-<govId>-<day>-<currency>`（:156-164，确定性、同 id 重复即抛） |
| 审计持久化 | `EconomyChangeSet.java:131,287,330`（`moneyIssuances` 是第 24 个组件的 `FieldDelta`，进 ChangeSet / Codec / 回放） |
| 国库账户解析 | `GovernmentSeigniorage.java:150-153` → `HouseholdRouting.requireHouseholdOf(treasury)`；`HouseholdRouting.java:44-50` **非 HOUSEHOLD 一律抛** |

**回答"`seignioragePerCycle` 是发行人自己决定发多少、还是 GM 改参数等周期自动发？"**：
**是后者**。`Government` 是一个**不可变 record**（`Government.java:32-38`），`seignioragePerCycle` 是它的第 5 个组件、
**只能整体替换 `Government` 记录**才能改；而**唯一**的运行期写口是 GM-only 命令 `economy.RegisterGovernment`
（`EconomyRegisterGovernmentHandler.java:322-329` 显式值时取载荷值、否则保留现值；:527-531 判"载荷是否显式给了"），
且这条命令**没有任何窄工具**，只能由 GM 用 `simos.command.submit` 直投。⇒ 现状语义 = **GM 设定一个固定费率，系统按周期自动增发**；
**没有任何"这一期发多少"的逐次决策**，也没有任何 DM 可参与的参数。

> ★ §四 的"台账/注释 ≠ 代码"又一例：`EconomySeeder.java:757` 注释写「**当前还没有 GM 实时编辑命令**；要改本批口径就改这个常量并重播」——
> **该注释已过期**：`economy.RegisterGovernment`（GM-only，`Shell.java:630` 注册）在载荷里显式给
> `seignioragePerCycle` / `debtIssuePerCycle` 即可热改（上引两处 handler 代码），无需重播、无需改 Java 常量。

### 1.2 创世一次性发行 = `economy.Seed` 载荷里的 `moneyIssuances`（`INITIAL_ENDOWMENT`）

| 步 | 落点 |
|---|---|
| 生成 | `EconomySeeder.java:1447-1490`（逐币种聚合的创世禀赋记录，`kind=INITIAL_ENDOWMENT`，id 含本 seed 的格集锚点） |
| 归属政府 | `EconomySeeder.java:748` `GENESIS_GOVERNMENT_ID = "world-silver"`；不带政府家户时退化成 `genesisAuditGovernment()`（**不持户、`issuable` 空集、旋钮 0**，:745、:3381） |
| 载荷解析 | `EconomyPayloads.java:323-325`（`toData` 解析顶层 `governments` / `moneyIssuances`）；政府项：`EconomyPayloads.java:2074-2113`（`issuable` 逐项 `CurrencyId.parse` 在 :2099） |
| 命令 | `EconomySeedHandler.java:88-113` 首次播种（`meta` 空 ⇒ 整份落盘并打标）；`meta` 非空 ⇒ 按格追加 |
| 追加口径 | `EconomySeedHandler.java:198` `mergeKeepingExisting(base.governments(), seeded.governments())` + :199 `merge(base.moneyIssuances(), seeded.moneyIssuances())` |
| 余额腿 | 家户 / 经营者的创世钱包走 **`actor.Seed`**（`EconomySeeder` 计划里与 `economy.Seed` 同批；见 `SmallWorld.java:101` 的链路注释） |
| 命令面标记 | `EconomySeedHandler.java:50` **不实现** `GmOnlyCommand`（见 §5.3 的后果） |

### 1.3 日结算里的"单边发行腿"（付方 = 发行源、余额不足时只扣实际持有 + 记 FISCAL_ISSUE）

- `EconomySettlement.java:7790-7812`（`validateApplyTransfer` 第一遍只读校验）：付方余额 < 应付 ⇒
  `MoneyIssuance.requireIssuerOf(currency)`（:7798）；**付方 ≠ 发行源 ⇒ 抛**"透支 = 发行"；付方 = 发行源但没有
  `MoneyIssuanceJournal` 落点 ⇒ 亦抛（"不许造钱而没有审计记录"）。
- `EconomySettlement.java:7870-7895`（`debitHouseholdMoney` 实际扣款）：同一判据，差额交 `journal.recordIssuance(...)`。
- `EconomySettlement.java:7972-8008`（`MoneyIssuanceJournal.recordIssuance`）：落 `FISCAL_ISSUE`，**并且**要求
  `government.issuable().contains(currency)`（:7983-7992，第二道授权闸）；发行审计 id = `MoneyIssuanceId.forTransfer(...)`。
- 日结算开始时按世界权威状态重建发行登记表：`EconomySettlement.java:772` `MoneyIssuance.syncAuthorities(base.governments().values())`。

### 1.4 回笼（`WITHDRAWAL`）：**写口已实现、零调用方**

- 唯一实现：`TreasuryWithdrawal.java:42-63`（授权 `currency ∈ Government.issuable`、`amount ≤ 可用余额（余额 − 冻结）`、
  写 `WITHDRAWAL`、id = `MoneyIssuanceId.forWithdrawal(operationRef, currency)`、同 ref 重放幂等）。
- 同包门面：`EconomySettlement.java:7919-7936` `withdrawFromTreasury(...)`，注释自述「**P7/GM 接上之前，本批没有自动调用方**」。
- 全仓验证：`grep -rn "TreasuryWithdrawal" simos-*/src/main` = 3 命中（自身 + `EconomySettlement.java:7919,7933`），
  `grep -rn "withdraw(" simos-*/src/main` = 2 命中（定义 + 那处薄转发）。⇒ **没有任何命令、工具、自动路径调它**。

### 1.5 "GM → 审批 → 落账 → 国库户"今天实际长什么样

**没有货币专属的这条链**。今天最接近"给国库造钱"的两条路都不是发行：

1. `simos.gov.transferTreasury`（GM 桶，`GovTransferTreasuryTool.java:60`）→ `actor.RemitGovTreasury`
   （`RemitGovTreasuryHandler.java`）—— **转账**：源国库/家户可支配量不足 ⇒ 具名拒（:270-310），源侧必须有余额。
2. `actor.AdjustAccounts`（GM-only，`AdjustAccountsHandler.java:89`）—— **裸账原语**，纯正增量 = 凭空造余额、
   **不写发行审计**；创世国库注资走的就是它（见 §5.3）。

⇒ 「GOV 发行货币」今天在代码里的合法形态只有：**GM 直接把 `seignioragePerCycle` 旋钮拧上**（§1.1），或者
**创世 payload 里写 `INITIAL_ENDOWMENT`**（§1.2）。

---

## 2. Q2 决策人工具面 + GM 审批链 + 新增一条"发行货币"决策人工具的落点

### 2.1 审批链机制（三处拼装，两个面各一条链）

| 件 | 落点 |
|---|---|
| 工具体自报"要不要问人" | `AgentTool.gate(ToolContext)` 返回 `ToolGate.Ask(…, AskKind.SENSITIVE)`；**没有**任何集中注册表 —— 是否需审批 = 该工具这次调用报了什么 gate（`ToolGate` / `AskKind` 住在 AgentLib，不在本仓） |
| 决策人链 | `Shell.java:895-901`：`new ApprovalCoordinator(List.of(new AutoApproveGate(pendingApprovals), new ConfirmGate()), List.of(loggingApprovalChannel), pendingApprovals, APPROVAL_TIMEOUT, null)` |
| GM 面链（MCP 口） | `Shell.java:906-911`：`List.of(new GmAutoApproveGate())` —— **无脑过**（2026-09-24 用户裁定，`GmAutoApproveGate.java:9-12`） |
| `AutoApproveGate` 真实语义 | `/home/cna/ProjectMosire/AgentLibMosire/src/main/java/io/mosire/agentlib/approval/AutoApproveGate.java:31-35`：**只有**本进程内 `(callerKey, classKey)` 已被 `APPROVE_SESSION` 才放行，否则 `empty` |
| `ConfirmGate` 真实语义 | 同目录 `ConfirmGate.java:24-26`：**永远 `empty`**（"链的显式终点"，交给编排器去问人）⇒ 链的实际效果 = **未获会话级授权的敏感调用落到 `PendingApprovals` 等人点头** |
| `AskKind.SENSITIVE` | 同目录 `AskKind.java:9-11`：**永远到人，上级 Agent 不许代批** |
| 待批登记表 / 裁决口 | `PendingApprovals`（进程内，AgentLib）；GM 读 `simos.gm.approvals`（`GmApprovalsTool.java:40,85`）、裁决 `simos.gm.approve`（`GmApproveTool.java:62`，`:157` 未接入 ⇒ 具名 `UNAVAILABLE`）；GUI 侧 `GET/POST /api/approvals` 原样透传（`GuiServer.java:200-201,1042`）；HTTP 端点 `ApprovalHttpEndpoint.start(config.approvalPort(), …)`（`Shell.java:913`） |
| 决策人链效果的自证 | `SimosToolSource.java:828-835`、`GovPayTool.java:62`、`GovSetEstablishmentTool.java:33-34` 等 14 条工具的类注都写明"决策人链路 = Ask → 待批、需 GM 点头；GM 链路 GmAutoApproveGate 直接批准" |

**今天"要 GM 审批"的决策人工具**（= 在**决策人桶**里且 `spec().sensitive()=true` + `ToolGate.Ask`）：
`sd.IssueDirective`、`sd.SubmitVerdict`、`sd.SetDiplomaticRelation`、`sd.RecordDiplomaticEvent`（四条走
`AbstractNarrowWriteTool.java:206` 基类 gate）、`simos.gov.pay`、`simos.gov.setEstablishment`、
`simos.gov.setBudgetPolicy`、`simos.gov.assignPosts`、`simos.gov.expandHousehold`、`simos.gov.openPostsToMarket`、
`simos.sd.propose`、`simos.sd.packet.submit`（`SubmitPacketTool.java:96`）、`simos.sd.packet.intent`
（`PacketIntentTool.java:99`）、`simos.sd.report`（`SimosSdReportTool.java:131`）。
（`simos.sd.packet.my` = `MyPacketTool` 只读、无 gate，不在此列。）

### 2.2 新增一条"GOV 发行/命名货币"决策人工具，**要登记的地方（列全）**

按现有同类工具的形态（以 `simos.gov.pay` 为模板）逐处列：

| # | 登记点 | 落点 / 现状证据 | 是否必做 |
|---|---|---|---|
| 1 | **域命令 handler**（数据归属模块：货币归 `simos-economy`） | 形如 `EconomyXxxHandler implements CommandHandler[, CommandTargets][, GmOnlyCommand]` + `TYPE` 常量 | 必做（新写口） |
| 2 | **命令注册**（自动派生 catalog 白名单 / 可嵌令白名单 / 目标表） | `Shell.java:786-806`：`commandTypes`（含 GM-only）、`directiveCommandTypes`（= 注册面 − `GmOnlyCommand`，:796）、`commandTargets`（实现 `CommandTargets` 的 handler）全从这一份 handler 清单派生；经济族加在 `Shell.java:625-640` 一带 | 必做 |
| 3 | **`CatalogTool.PAYLOAD_HINTS`** | `CatalogTool.java:57,766-800`：**已注册 type 缺载荷提示 ⇒ 构造期抛**（不静默） | 必做（若是命令类型） |
| 4 | **窄工具类**（工具名 / schema / preview+apply / 资源声明 / 敏感声明 / `gate()`） | 模板 `GovPayTool.java:75`（NAME）、`:143-146`（`ToolSpec.level(DEFAULT, true, false)` = sensitive）、`:156`（`ToolGate.Ask(SENSITIVE)`）、`:131`（`ResourceManifest`） | 必做 |
| 5 | **注册进桶**：GM 面 + 决策人面 | `SimosToolSource.addGmWrites`（定义 :519，一大段 `built.add(...)` 到 :808，例：`GovTransferTreasuryTool` :672）与 `addDecisionAgentWrites`（定义 :810，例：`GovPayTool` :825） | 决策人面必做；GM 面按是否需要 GM 直调 |
| 6 | **权限组白名单同源** | `DecisionCallerFactory.java:111-181` `WHITELIST`（例：`GovPayTool.NAME` `:158`）；per-DM 再收窄 `whitelistFor` :254-267（`dm.allowedTools()`，空 = 全白名单） | 决策人面必做（只改一处 = 留旁路） |
| 7 | **决策包目录**（若允许 DM 用 `simos.sd.propose` 提议） | `ProposalCatalog.java:104-127` 的 `List.of(entry(...))`（当前恰 7 条：RaiseUnit/GovRecruit/GovRetireStaff/SocialHouseholdMembers/LevyRegion/GovSelectExaminees/GovDispatchTeam），每条要配 `skipKeys` / `createsUnit` | 仅在要走 propose 时 |
| 8 | **`GmOnlyCommand` 标记决策** | 不标 ⇒ 自动进令白名单 / `RegisterEffect` / 决策人 catalog（`Shell.java:780-800`）；标 ⇒ 三条路径一起排除，但 GM 直投仍可用 | 必做（权限单调性，见 §5.3） |
| 9 | **给模型的工具面** | `DecisionToolDefs.requireAll(registry, allowed)`（`DecisionToolDefs.java:69-88`）：白名单里每条**必须**在决策人桶里，缺一条当场抛 ⇒ #5 与 #6 必须同源 | 自动（但装配期会炸） |
| 10 | **命令覆盖测试** | `McpCoverageTest.java:827-841`：`EXPECTED_COMMAND_TYPES` 与注册面 `containsExactlyInAnyOrder`，且 `MINIMAL_PAYLOADS` 必须覆盖除 5 条特例外的**每个** catalog type | 必做（新增命令类型时） |
| 11 | **读口**（DM 要看见"我能发什么 / 我发了多少"） | `simos.economy.hex`（`EconomyHexTool`，**非** `GmOnlyRead`，DM 白名单里有它 → `currencyDefs` / `moneyInstruments` 两栏）、`simos.gov.info`（`GovInfoTool.java:86,308,711` 国库逐币种余额）、`GET /api/economy/gov`（`ApiViews.java:1595-1660`：`issuable` / `seignioragePerCycle` / `issuance`） | 按需；**今天 DM 能看到金币种与国库余额，但没有"发行"的写口** |
| 12 | **审批链接线** | 不需要额外登记：工具自报 `ToolGate.Ask` 即落 `PendingApprovals`（§2.1）；GM 侧 `simos.gm.approve` / GUI `/api/approvals` 已有 | 自动 |

---

## 3. Q3 货币的形状 / 不变量 —— **能不能改名？**

### 3.1 五个类型的形状（全部是"身份 + 精度"，**没有"名字"**）

```
CurrencyDef       record(String id, int scale)                       CurrencyDef.java:29
                  ← id 是"词表条目"的键，currencyId() 是 String→CurrencyId 的唯一转换点（:41-44）
CurrencyId        record(String value)                               CurrencyId.java:23-42
                  ← 运行时身份：HouseholdInventory.money / Transfer.money / Market.numeraire /
                    CompensationRule.currency 的**键**（CurrencyId.java:9-14）
MoneyInstrument   record(InstrumentId id, CurrencyId currency,
                         InstrumentKind kind, Optional<ActorRef> issuer,
                         Optional<ActorRef> redeemer)               MoneyInstrument.java:60-65
InstrumentKind    SPECIE | STATE_NOTE | BANK_DEPOSIT（:22-31）；label() 是**档位**的中文标签（:25-31,40-42），
                  requiresIssuer() 一处拼写（:50-55，无 default ⇒ 加档编译不过）
MoneyAuthority    接口：authorityOf(CurrencyId) + issuable()         MoneyAuthority.java:33-49
MoneyIssuance     进程内静态登记表 + 唯一发行查询 requireIssuerOf     MoneyIssuance.java:27-208
MoneyIssuanceRecord record(id, governmentId, day, period, currency,
                         amount, kind, reason)                      MoneyIssuanceRecord.java:32-40（金额恒正、方向由 kind）
```

### 3.2 **改名：现在做不到，而且不是"加个工具"就能做**

- 全仓 `src/main` 里**没有任何**货币"显示名"字段：`grep -rn "displayName" simos-*/src/main | grep -i "curren\|money"` = **0 命中**；
  `grep -rni "rename" simos-*/src/main | grep -i "curren\|money"` = **0 命中**。
- `CurrencyDef` 只有 `(id, scale)`；`MoneyInstrument` 只有 `(id, currency, kind, issuer, redeemer)`；
  `InstrumentKind.label()` 是"金属币/国币/银行存款"**档位**标签，不是某个币种的名字。
- `CurrencyId` **就是 id 本身**，且是**余额表 / 转移腿 / 市场计价 / 补偿规则的键**（`CurrencyId.java:11-14`）。
  ⇒ "改名 = 改 `CurrencyId.value`" 等于**换身份**：所有历史余额、流水、市场、债务、审计记录的键都会与改名后的身份脱节
  （与铁律 1「ID 是身份、改名不动它」直接冲突）。**正确形态只能是"稳定 id + 可变显示名"两分。**
- 本仓**已有**这条范式的现成样板（可作为落点参考，不是货币）：
  `Region` 同时有 `RegionId id`（身份，`RegionId.java:4-12`）与 `String name`（显示名，`Region.java:19`），
  改名走 `Region.withName(String)`（`Region.java:66-68`），命令是 `map.RenameRegion`（`RenameRegionHandler`，已注册）。
  ⇒ 货币要"命名/改名"，技术上= **给 `CurrencyDef` 加一个 `name`/`displayName` 字段 + 把它从静态常量搬进世界状态 + 一条改名命令**。
- ★ **词表当前不是世界状态**：`MoneyVocabulary` 是 `final class` + `static final` 常量 + `List.of(...)`（§6），
  加字段/改名必须先解决"词表进状态"（新组件 ⇒ `EconomyData` 第 26+ 个组件 + `EconomyChangeSet` + `EconomyCodec` + 往返不变式）。

---

## 4. Q4 「一币一发行人」不变量的**全部落点**

| # | 落点 | 判据 |
|---|---|---|
| 1 | `MoneyIssuance.java:76-83`（`register`） | 逐 issuable 币种：已登记表里该币种有**另一个**发行主体 ⇒ `IllegalArgumentException`（先全校验、后一次性替换，不留半改状态） |
| 2 | `MoneyIssuance.java:140-147`（`syncAuthorities`） | 同一份世界状态里同币种两个不同发行主体 ⇒ `IllegalStateException`；幂等重建、确定性 |
| 3 | `EconomyData.java:1453-1471`（**状态构造期**） | `issuersByCurrency.putIfAbsent`：同币种不同 issuer ⇒ `IllegalArgumentException`（"同一币种只能有一个发行主体"） |
| 4 | `EconomyData.java:1422-1425` | `treasuries.add(...)` 失败 ⇒ "同一个国库 actor 不能同时是两届政府（发行记录无法归属）" |
| 5 | `MoneyIssuance.java:164-181`（`requireIssuerOf`） | 唯一发行查询；**查不到 ⇒ 抛**（零登记 = 谁都不许发行，fail-closed） |
| 6 | `Government.java:93-108` | `authorityOf(currency)`：`issuable` 不含 ⇒ 抛；`issuable()` 空集 = 非发行人 |
| 7 | `EconomySettlement.java:7983-7992`（`recordIssuance`） | 写 FISCAL_ISSUE 前**再判** `government.issuable().contains(currency)`（不按国库归属静默记账） |
| 8 | `TreasuryWithdrawal.java:88-96`（回笼） | `issuerOfRegistered(currency)` 必须 = 该政府国库；`currency ∉ issuable` ⇒ 抛 |
| 9 | `MoneyIssuance.java:198-208`（`requireIssuer`） | `authorityOf` 返回 null ⇒ 抛（"说不出'谁发的'就不是发行人"） |

**"一个 GOV 发行两种货币"允许吗？** 结构上**允许**：`Government.issuable` 是 `Set<CurrencyId>`
（`Government.java:36,80-90`），`GovernmentSeigniorage` 逐 issuable 币种各发一笔（`GovernmentSeigniorage.java:101`）。
但**两处读侧/行为侧限制**：
① 读口只报第一种 —— `ApiViews.java:1638` `mintCurrency = issuable.isEmpty() ? null : issuable.get(0)`；
② 铸币金额是**同一个标量**（每币各发一份 `seignioragePerCycle`，没有逐币金额）。
⇒ "多币种"是**形状上支持、语义上未设计**。

**"一种货币两个发行人"允许吗？** **不允许**，九道闸（上表）。注意实现细节：冲突判据是
`ActorRef` **相等**（`MoneyIssuance.java:79,143`、`EconomyData.java:1466-1470`）—— 理论上"两个政府共用同一个国库 actor
各自称发行人"能过 1/2/3，但被 #4（`EconomyData.java:1422-1425`）在状态构造期判死 ⇒ **实际效果 = 一个币种恰一个发行 actor**。

---

## 5. Q5 发行闸门现在的失败语义 + 权限单调性

### 5.1 谁保证"只有发行人能发自己的币"

- **日结算转移路径**（唯一有对称守卫的路）：付方余额不足 ⇒ `requireIssuerOf(currency)`：
  ① 没人发行该币 ⇒ **抛**（`MoneyIssuance.java:174-179`，H4 原文案逐字保留）；
  ② 付方 ≠ 发行源 ⇒ **抛**"透支 = 发行"（`EconomySettlement.java:7798-7808`、:7878-7888）；
  ③ 付方 = 发行源但入口没带 `MoneyIssuanceJournal` ⇒ **抛**（"不许造钱而没有审计记录"）；
  ④ 记发行时再判 `issuable.contains(currency)`（§4 #7）。
- **负余额守卫**（三层，彼此独立）：`AccountSession.java:326`（账户增量扣成负货币 ⇒ 抛）、
  `AccountIntentBuffer.java:227`（本地意向）、`ActorPayloads`/`HouseholdInventory.java:176`（余额存量非负）。

### 5.2 决策人能否发别国的币？——**不能**（今天根本没有发行工具）

- 决策人白名单 `DecisionCallerFactory.java:111-181` 与决策人桶 `SimosToolSource.java:810-860` 里**没有任何**货币工具；
  唯一动钱的 DM 工具是 `simos.gov.pay`（付款人 = 调用者所属 GOV，身份派生；`GovPayTool.java:55-58`），
  它走 `actor.RemitGovTreasury`（纯转移、源余额不足即拒）。
- 改 `issuable` / `seignioragePerCycle` 的 `economy.RegisterGovernment` 标了 `GmOnlyCommand`
  （`EconomyRegisterGovernmentHandler.java:80-86`，类声明 :85、`implements … GmOnlyCommand` :86），⇒ 不进令白名单 / `RegisterEffect` / 决策人目录（`Shell.java:780-800`）。

### 5.3 ★ 权限单调性缺口（**这是本次调查最重的一条**）

`MoneyIssuance` 的守卫面**只存在于日结算转移路径**；**actor 命令面可以凭空造钱、且不写发行审计**，而其中两条命令
**不是 GM-only**：

| 命令 | GmOnly? | 造钱能力 | 落点 |
|---|---|---|---|
| `actor.Seed` | **否**（`ActorSeedHandler.java:52` 只实现 `CommandHandler, CommandTargets`） | 载荷里 `"money":{"<任意币种 id>":金额}` 直接建账建余额（`ActorPayloads.java:507-528`，:527 `new CurrencyId(field.getKey())`——**币种 id 是自由字符串**） | §1.2 创世钱包就走它 |
| `actor.AdjustAccounts` | 是（`AdjustAccountsHandler.java:89`），但 **GM 的 `simos.command.submit` 直投 + 创世计划直调**都能用 | 纯正增量 = 凭空造余额；缺账 + 纯正增量 ⇒ **新建**账户（`AdjustAccountsHandler.java:52-60`） | 创世国库注资就走它：`GovWorldBootstrap.java:288-293` + `:716-733`（每 GOV 注入 `TREASURY_SILVER_MILLI_PER_GOV = 100_000` 毫银，**没有任何 `INITIAL_ENDOWMENT`/`FISCAL_ISSUE` 记录**） |
| `economy.Seed` | **否**（`EconomySeedHandler.java:50`） | 载荷可带顶层 `governments[].issuable/seignioragePerCycle` + `moneyIssuances[]`（`EconomyPayloads.java:2074-2130`），追加播种时按 id **合并追加**发行记录（`EconomySeedHandler.java:198-199`） | 不是"直接改余额"，但能**写发行账 / 声明新的发行主体** |
| `actor.TransferAccounts` | 是 | 纯转移 | — |

**后果（可执行判据）**：`MoneyStock.circulationMinusNetIssuance`（`MoneyStock.java:59-80`）的"逐币种差额"在
**任何走过 `GovWorldBootstrap` 的创世世界里必然非 0**（国库被注资但无发行记录）；该读口自己也声明"非 0 是待裁定口径差"。
因为 `economy.Seed` / `actor.Seed` 不标 `GmOnlyCommand`，它们**会**进 `DirectiveWhitelist`
（`Shell.java:796` → `IssueDirectiveHandler(new DirectiveWhitelist(directiveCommandTypes))`），
⇒ **决策人可以出令嵌入 `actor.Seed`（造任意币种余额）/ `economy.Seed`（写发行账）**，落地时由 GM 的裁决动作执行。
（边界如实记：`DirectiveWhitelist` 只排 `sd.*` 自指与 `simos.command.submit`，`DirectiveWhitelist.java:31-47`；
这些目标还过 `CommandTargets` 的**格级** scope 校验，所以不是"任意格"，但"任意金额/任意币种"仍成立。
这条路径最终要 GM 触发裁决 —— 是"有人点头"，不是"结构上不可能"。）

---

## 6. Q6 币种词表 `allCurrencyDefs()` 现在几条、从哪读、GM 能不能加

- **1 条**：`MoneyVocabulary.java:87-89` `return List.of(SILVER);`；工具表同样 1 条（:96-98 `List.of(SILVER_SPECIE)`）。
- **来源 = 静态 Java 常量**，不是 `worldParams`、不是创世载荷、不是任何状态组件：
  `SILVER_CURRENCY_ID = "silver"`（:41）、`SILVER_SCALE = 3`（:50）、`SILVER`（:53）、`SILVER_CURRENCY`（:59）、
  `SILVER_SPECIE_INSTRUMENT_ID = "silver-specie"`（:62）、`SILVER_SPECIE`（:71-77）。
- `CurrencyDef.java:19` 的类注写「本类型是词表条目（**V7 起从 `worldParams` 读进来的数据**）」——
  **V7 未实现**：`grep -rn "worldParams" simos-*/src/main` = **2 命中、两条都是注释**
  （`CurrencyDef.java:19`、`simos-util/.../EconomyVocabulary.java:47`），零代码。⇒ 这句话是**未来承诺**，不是现状。
- **GM 不能加币种**：没有命令、没有工具（§2.2 表里的 `MoneyVocabulary` 无写口）。变相"加币种"只有两条**旁路**：
  ① `actor.Seed`/`actor.AdjustAccounts` 的 `money` 键（自由币种 id，`ActorPayloads.java:527`）；
  ② `economy.Seed` 载荷里的 `governments[].issuable`（`EconomyPayloads.java:2099`）——
  但这两条都不会让 `allCurrencyDefs()` 多一条，读口依旧只发 `silver`（`ApiViews.java:4287-4295`）。
- **词表唯一性护栏**：`EconomyVocabularyGuardTest.java:204-220` 用源扫描钉住
  `SILVER_CURRENCY_ID = "silver"` 全仓 `src/main` **恰一处**、且禁 `new CurrencyId("silver")`；
  该测试的 `MODULES` 清单还要与根 pom 的 `<module>` 集合**逐条相等**（:77-89）—— 新增模块必须两处同加。
- DM 也能读词表：`EconomyHexTool`（`simos.economy.hex`）**不**标 `GmOnlyRead`（类注 `EconomyHexTool.java:19`），
  且在白名单里（`DecisionCallerFactory.java:143`）⇒ `currencyDefs` / `moneyInstruments` 对 GM 与 DM 同样可见。

---

## 7. Q7 三档缺口（对用户设计而言）

### 第一档「有 / 可用」

1. **发行主体的接口与闸门**：`MoneyAuthority` + `MoneyIssuance`（进程内登记 + 唯一查询 + 失败闭合）`MoneyIssuance.java:27-208`。
2. **"一币一发行人"九道守卫**（§4），含状态构造期判死（`EconomyData.java:1453-1471`）。
3. **周期铸币**：`GovernmentSeigniorage`（自动、确定性 id、可重放、FISCAL_ISSUE 审计、日志 `GOV_SEIGNIORAGE`）。
4. **创世发行**：`INITIAL_ENDOWMENT` 显式审计（`EconomySeeder.java:1447-1490`）+ 发行审计进 ChangeSet/Codec。
5. **回笼写口**（实现完整但零调用方）：`TreasuryWithdrawal.withdraw`。
6. **决策人工具 + GM 审批链**：机制完备（Ask → AutoApproveGate → ConfirmGate → PendingApprovals；GM 面
   `GmAutoApproveGate` 无脑过），已有 14 条 DM 工具在用同一套（§2.1）。
7. **读口**：`currencyDefs`/`moneyInstruments`（`ApiViews.java:1414,1590,4287-4318`）、
   `GET /api/economy/gov`（`ApiViews.java:1595-1660`，政策旋钮与事实读数并排）、`MoneyStock` 守恒式三件套。

### 第二档「半成品」

1. **币种词表仍是静态常量**（`MoneyVocabulary`），不是世界状态、GM 不可加、更不可改名；`CurrencyDef` 类注里的
   "V7 从 `worldParams` 读"是**未实现的承诺**（`worldParams` 全仓 0 代码命中）。
2. **多币种只有形状**：`issuable` 是 Set，但金额是单标量（每币各发同一份）、读口 `mintCurrency` 只报第一个
   （`ApiViews.java:1638`）。
3. **`STATE_NOTE` / `BANK_DEPOSIT` 有类型守卫、词表刻意留空**（`MoneyVocabulary.java:29-33` 自述：没有发行人 ⇒ 硬造会被守卫拦下）
   ⇒ "GOV 发国币"缺的是**数据**，不是类型。
4. **回笼无路径**：写口在、命令/工具/自动路径全无（`EconomySettlement.java:7919-7936` 自述）。
5. **发行闸门只覆盖结算路径**：`actor.Seed` / `actor.AdjustAccounts` / `economy.Seed` 三条命令面可绕开（§5.3）。
6. **`Government.treasury` 允许 `GOVERNMENT` kind，但铸币只认 `HOUSEHOLD`**
   （`Government.java:69-72` vs `GovernmentSeigniorage.java:150-153` + `HouseholdRouting.java:44-50`）
   ⇒ "GOVERNMENT 国库 + `seignioragePerCycle > 0`"会在日结算当场抛；今天只有 household 国库形态真的能铸币。
7. **没有任何测试钉住铸币行为**：`grep -rn "seignioragePerCycle" simos-*/src/test` = **0 命中**
   （`MoneyIdentityTest` 只钉类型与词表形状，不钉发行路径）。

### 第三档「完全没有」

1. **货币命名 / 改名**：无显示名字段、无命令、无工具（§3.2）。
2. **"发行一种新货币"的世界状态路径**：无 `economy.DefineCurrency` 之类命令；`MoneyVocabulary` 无写口。
3. **计划中的 `gov.IssueMoney` / `gov.InitialEndowment`**（`POLITICAL_ECONOMY_DESIGN.md:623-624`，目标态命令清单）
   **零实现**：`grep -rn "RenameCurrency|DefineCurrency|IssueMoney|InitialEndowment|CurrencyName" simos-*/src/main` = **0 命中**。
4. **DM 侧任何铸币 / 回笼 / 命名工具**，以及"这一期发多少"的决策人参数（`ProposalCatalog` 只收 7 条写工具；
   `addDecisionAgentWrites` 15 条工具里零货币）。
5. **发行额度 / 上限 / 审批语义**（除通用 `ToolGate.Ask` 外，货币没有任何专属审批或配额；`seignioragePerCycle` 无上界）。
6. **货币与疆域 / 市场区的连接**：`Government` 只有不透明的 `nationRef`（`Government.java:26`），
   `Market` 是**逐格**单一计价货币（`Market.java:10,43-50`）⇒ "政府的币在其总疆域内通行"在货币侧**没有任何落点**
   （市场区本身属 task-1/2/4）。
7. **币种间兑换 / 折算**：`MoneyVocabulary.java:36` 与 `CurrencyDef.java:24` 明文「本批不做汇率：不许求和、不许折算（裁定 M1-A）」。

---

## 8. 与用户 2026-10-08 设计的矛盾点（重点：**改名** 与 **DM 定发多少**）

| # | 用户原话 | 代码实际 | 冲突性质 |
|---|---|---|---|
| 1 | 「这个GOV可以发行一种货币，并给这种货币**命名、改名**」 | 货币**没有"名"这一维**：`CurrencyDef(id, scale)`、`CurrencyId(value)`、`MoneyInstrument` 都没有 name；`CurrencyId` 就是余额/流水/市场/债务的**键**（`CurrencyId.java:11-14`）⇒ "改名"若改 id = 换身份（违铁律 1）；若加显示名 = 词表必须先**进世界状态**（今天是静态常量 `MoneyVocabulary.java:87-89`，V7 未实现） | **设计缺失**（不是实现 bug）：需要新增"稳定 id + 可变显示名"两分 + 改名命令/工具 + 词表入状态三件事，缺一不可 |
| 2 | 「目前的实际流程就是 **DM 定发多少** 然后走决策人工具」 | **没有"发多少"这个决策点**：唯一的自动铸币量 = `Government.seignioragePerCycle`（不可变 record 组件），唯一写口 = GM-only 的 `economy.RegisterGovernment`（`EconomyRegisterGovernmentHandler.java:322-329`），且**无窄工具**（GM 只能用 `simos.command.submit`）。DM 侧零货币工具（白名单 `DecisionCallerFactory.java:111-181` / 决策人桶 `SimosToolSource.java:810-860` 都没有） | **设计缺失**：需要一个"DM 决策 → GM 审批 → 落账"的发行命令 + 工具；今天只有"GM 设费率、系统周期自动发" |
| 3 | 「落成**决策人工具，需要GM审批的那种**」 | 机制**现成**（Ask → AutoApproveGate → ConfirmGate → PendingApprovals；14 条 DM 工具已在用），但**货币工具不存在**；且 `GmOnlyCommand` 与"DM 工具"是**互斥的两条路**（标了 GmOnly 就进不了令白名单/决策人目录，`Shell.java:796`）⇒ 新工具必须**不标** `GmOnlyCommand`，而把权限收紧交给审批链 + 身份派生 | 可实现，无原理冲突；但要想清"DM 能否发别国的币"必须**身份派生**（照 `GovPayTool.java:55-58`），因为工具的载荷可被模型随便填 |
| 4 | 「一个GOV拥有**发行货币的权利**」 | 语义吻合：`Government.issuable` = "能发哪些币"的唯一落点（`Government.java:36,93-108`），`MoneyAuthority.authorityOf` = 唯一发行主体。**但**"GOV 单位"与"Government 记录"是两套身份、靠派生 id 绑定（`GovernmentIds.ofUnit`），且创世 `world-silver` 是**非单位**主体（`issuable` 空集）⇒ 现阶段"一个 GOV 一个发行权"只在 demo 世界里成立 | 语义吻合、粒度待补 |
| 5 | 「发货币的政府的**总疆域**……默认就是这种货币的**市场区**」 | 货币侧与疆域**零连接**；`Government.nationRef` 是不透明字符串（`Government.java:26`），`Market.numeraire` 是**逐格**写死的计价货币（`Market.java:46`） | 属 task-1/2/4 的市场区调查；**但**"市场区是法律规定、与户实际用啥币无关"这条在货币侧也无任何落点（没有"某币在某区通行"的形状） |
| 6 | 「同样通过决策人工具**发行货币**」（无 GM 审批字样，与前半句"需要GM审批的那种"连读） | 今天"发行"的两条真路径都不经 DM、也不经审批：① `GovernmentSeigniorage` 自动；② 创世 payload。第三条（回笼）零调用方 | 设计缺失（与 #2 同一条） |
| 7 | 隐含：货币总量/发行应有审计 | **审计形状齐全**（`MoneyIssuanceRecord` 恒正 + kind 表方向 + 进 ChangeSet/Codec/回放），**但**创世国库注资（`GovWorldBootstrap.java:290,716-733`，每 GOV 100 银）与 actor 命令面的造钱**没有任何发行记录** ⇒ `MoneyStock.circulationMinusNetIssuance` 在真实创世世界必然非 0 | 实现缺口（口径差，非设计冲突） |

---

## 9. 我没核到的（如实记）

1. **AgentLib 侧的审批细节只读了源码，没有跑**：`AutoApproveGate` / `ConfirmGate` / `PendingApprovals` / `AskKind`
   引自 `/home/cna/ProjectMosire/AgentLibMosire/src/main/java/io/mosire/agentlib/approval/`（依赖
   `agentlib-mosire:0.1.0-SNAPSHOT`，根 `pom.xml:78,162-163`）；我**没有**验证该 checkout 与 `~/.m2` 里那份 SNAPSHOT
   逐字节一致，也没读 `ApprovalCoordinator` 的超时/通道回退细节（只据 `Shell.java:895-911` 的装配与
   `LoggingApprovalChannel` 类注）。
2. **`seignioragePerCycle` 的运行期实际取值**：我核到 demo/小世界的出厂常量
   （`EconomySeeder.java:759 = 2_000` 毫/周期）与"可由 GM 热改"，**没有**核到任何真实存档里该旋钮的当前值，
   也没跑一行 Maven/实例去观察一次 `GOV_SEIGNIORAGE` 日志。
3. **`economy.RegisterGovernment` 是否真能改"已有政府"的 `issuable`**：
   `EconomyRegisterGovernmentHandler.java:322-329` 的写法看起来"显式值覆盖、缺省保留"，`issuable` 的具体
   合并语义我**只读了这一段**，没有逐行走完 `:340-400` 的整条写路径，也没有对应的测试佐证
   （`grep -rn "issuable" simos-*/src/test` 未逐条核）。
4. **DM 通过令嵌入 `actor.Seed` / `economy.Seed` 的可行性**：我核到 ① 两条命令**未标** `GmOnlyCommand`；
   ② `Shell.java:796` 会把它们放进 `directiveCommandTypes`；③ `DirectiveWhitelist` 只排 `sd.*` 与通用写。
   **我没有**核到 `sd.IssueDirective` 的 `DirectiveCommand` 形状校验是否对这两条命令有额外限制、
   也没核 `AdjudicateTickTool` 逐命令校验表里是否为它们写了专属规则（只看到 `actor.RemitGovTreasury` 有
   "只能上缴 superiorGov"的专属口径，`GovScope.java:123-130`）。⇒ §5.3 的结论**方向确定、完整边界待补**。
5. **`MoneyAuthority` 的"发行人可透支"形态与 `AccountSession` 的交互**：`MoneyAuthority.java:18-21` 说"唯一能透支的是发行人
   自己"，但实际代码里 `AccountSession.java:326` 对**任何**账户的负货币增量都抛；"透支"只以"结算期单边发行腿
   （只扣实际持有 + 记 FISCAL_ISSUE）"的形态存在。**我没有**穷尽所有写口去证明"发行人真的不能出现负余额"，
   也没找到"发行主体账户允许负余额"的实现（`POLITICAL_ECONOMY_DESIGN.md:498` 曾把它当一种选项）。
6. **GUI 侧是否存在货币/政府的写入口**：我核了 `GET /api/economy/gov` 是**只读**（`GuiServer.java:298-320` 一带），
   但**没有**逐页核对 `simos-app/src/main/resources/webui/**` 的前端是否有隐藏的写按钮。
7. **`simos-*/src/main` 之外的口子**：`config/` 与 `simos-app/src/main/resources/` 里 `grep -rln "currency|Currency"` = **0 命中**
   （已实测），但我没有检查 `test-world/`、`run-small-world.sh`、`~/Simos-18Lvt` 这些**运行期世界档**
   里的 `seignioragePerCycle` / `issuable` 现值。

---

## 附：本次调查用到的关键检索（可复核）

```bash
cd /home/cna/SimulatorMosire
grep -rn "seignioragePerCycle" --include=*.java .            # 含测试：src/test 零命中
grep -rn "TreasuryWithdrawal" simos-*/src/main --include=*.java
grep -rn "withdraw(" simos-*/src/main --include=*.java
grep -rn "CurrencyId(" simos-*/src/main --include=*.java     # ActorPayloads:527,552 是自由币种 id 的唯一入口
grep -rn "worldParams" simos-*/src/main --include=*.java     # 2 命中，全是注释
grep -rni "rename" simos-*/src/main --include=*.java | grep -i "curren\|money"   # 0
grep -rn "displayName" simos-*/src/main --include=*.java | grep -i "curren\|money" # 0
grep -rn "RenameCurrency\|DefineCurrency\|IssueMoney\|InitialEndowment\|CurrencyName" simos-*/src/main --include=*.java  # 0
grep -rhno '"\(economy\|actor\|gov\)\.[A-Za-z]*"' simos-*/src/main --include=*.java | sort -u   # 命令名全表：无任何 Issue/Mint/Withdraw
```
