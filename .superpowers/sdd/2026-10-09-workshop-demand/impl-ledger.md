# 工坊制品自然需求（布+工具）+ GM 批量加减需求：实现架构账本（责任区 D，2026-10-09）

> **任务**：① 工具（`tool`）进家庭自然需求（布已有）；② `social.SetDemandCoefficient` 加**批量**载荷（一次改多条、整批原子、老载荷向后兼容）。
> **唯一权威文档**：`docs/superpowers/specs/2026-10-09-commodity-freight-and-merchant-ladder-design.md` **§1.3/§1.4**（用户原话）、
> **§4.2**（工坊制品自然需求 G2）、**§5 I-F4**（需求权威在 Social）、**§6 T4 / N4 / N5**、**§9 O1**（系数取值开放点）；
> 背景裁定：`docs/superpowers/specs/2026-10-09-household-currency-acceptance-ruling.md`。
> **基态提交**：`7cc31e3c`（F2 批关账后的 HEAD）。**对照提交**（逐值对照轮）：同一 `7cc31e3c` 的 `git worktree`。
> **任务板**：`team_task_get task-25` 返回"不是 active Agent Team 成员" ⇒ **未能 claim/complete**（按任务书"不要卡住"继续执行，如实报）。
> ★ 本账本只记决策相关事实与真实读数，不抄工具输出、不写流水账。

---

## 0. 一句话结论

**②（GM 批量加减需求）做完并真跑验过**：一次载荷 5 条 ⇒ 一条 revision、逐条读回、11 个负向全部整批具名拒 + 零 revision；
老的单条载荷在**新旧两轮跑出逐字相同的结局**（同 payload、同 revision 号）。
**①（工具进自然需求）Social 侧做完**：`naturalNeeds` 确实长出了 `tool`（实测 203/342 户、合计 2,699 毫/日），
**但 T4 的正向判据不成立**——**经济侧的家户买目标把商品名写死成粮+布两行**，
`tool` 因此**不产生任何买单**（实测 `BUYER_OUTCOMES commodity=tool count=0`、`effectiveDemandMilli=0`）。
⇒ **T4 = BLOCKED**，最小额外范围是 `simos-economy/**` 的**一个方法**（本批文件所有权明令禁止碰 economy，故未动，见 §7/§8）。

---

## 1. 关键调查结论（`file:line`，2026-10-09 于本工作树核实）

| # | 证据 | 结论 / 影响 |
|---|---|---|
| S1 | `SocialProvisioning.defaults()`（改前 `:105-193`）全局默认表 = **六档 × (粮, 布)** 共 **12 行**；`TOOL` 常量不存在（只有 `GRAIN` `:84` / `CLOTH` `:87`） | ① 的落点就是这张表：加 6 行 tool（3 档 × 2 性别）；`EconomyVocabulary.TOOL_COMMODITY_ID` **早已存在**（`EconomyVocabulary.java:62`）⇒ **不需**新增常量（与派单书预期一致） |
| S2 | `SocialData.householdNaturalNeeds(HouseholdId, day, clock)`（`:844-919`）**不硬编码任何商品名**：商品键来自 `commodityKeysFor`（`:927-944`，遍历 provisioning 覆盖表 + 全局默认表）⇒ 默认表加行**自动**逐日展开 | ① 只需改默认表一处；`SocialData` / app 的 `naturalNeedsOf`（`PopulationEconomyTimeParticipant:1800-1838`）一字不动 |
| S3 | `PopulationEconomyTimeParticipant.naturalNeedsOf` ⇒ `EconomyDayStepper.updateNaturalNeeds`（`:404`）⇒ `HouseholdEconomy.naturalNeeds` | Social→Economy 的注入链**已经是多商品**的（按 map 原样注入，不筛商品） |
| S4 | ★★ **经济侧的家户买目标把商品名写死了**：`MarketSettlement.householdLifeReserveOf`（`:5829-5844`）**只取两行**——`expectedNeedMilli(GRAIN, MARKET_LIFE_RESERVE_DAYS)` 与 `(CLOTH, …)`；`ordersFor` 的 `baseTarget = participant.household != null ? life : necessary`（`:1604`） | **T4 的阻断点**：`life` 里没有 tool ⇒ 买目标 0 ⇒ `desiredQuantity(baseTarget, demandParts, …)`（`:1694`）恒 0 ⇒ **零买单**。`naturalNeeds[tool]` 进了状态，却没有任何买家 |
| S5 | `MarketReadout`（GUI/MCP 共用读数）把 `naturalNeedMilli` 与 `effectiveDemandMilli` **分列**（`:583-606`） | 这条分列正好把 S4 照出来：实测 tool 行 `naturalNeedMilli=1125` 而 `effectiveDemandMilli=0`（§6.1） |
| S6 | `SocialProvisioningEdits.setDemand/clearDemand`（`:58-154`）是**纯函数**（只做不可变 copy-with，返回新 `SocialData`） | ② 的原子性天然成立：逐条串在当前值上，任一条抛 ⇒ 整批抛，已算出的中间值全是不可变值（没有"写了一半"的形态）；命令层只比**一次** `SocialChangeSet.between` |
| S7 | `social.SeedGroups`（`SeedGroupsHandler.java:31-58, 203-229`）已有"**一次落 N 条 + 一条 revision + 空 entries 拒 + INFO 计数 + 拒因事件**"的形制，载荷字段就叫 `entries` | ② 照它抄形状：同名字段 `entries`、同一句"一条命令 = 一条 revision"、同款四段日志 |
| S8 | `SetDemandCoefficientHandler` 改前把 `SocialPayloads.parse` **放在 try 内**（`:76-77`），坏 JSON 走 `SOCIAL_DEMAND_COEFFICIENT_REJECTED` WARN + `Rejected` | 重构时必须把这个 try 的**语义**（而不是位置）保住：本批把 parse 单独 try 住、拒因走**同一个** `rejectSingle`（§4 的"逐字"就是这条） |
| S9 | `SocialDemandTool`（app 的 GM 窄工具）与命令**共用** `SocialProvisioningEdits`；`jsonSchema` 的 `required` 改前是 `[ageBracket, sex, commodity, reason]`（`:102`） | 批量形状的必填字段与单条不同 ⇒ `required` 收成 `[reason]`，两种形状的必填改由 `run()` 逐形状强制（缺 ⇒ `BAD_REQUEST`）。全仓**没有**对这条工具 schema 的断言（实测 `grep required.*schema simos-app/src/test` 零命中） |
| S10 | `CatalogTool.PAYLOAD_HINTS` 构造期要求**每个已注册 type 都有提示**（`:847-880`） | 本批**不加命令类型** ⇒ 提示表只改**文案**（加一段批量形状），不动键集，覆盖断言不受影响 |
| S11 | `EconomyVocabularyGuardTest`（`simos-util/.../verify/`）钉"商品 id 字面量恰一份"（`:164-193`：`TOOL_COMMODITY_ID = "tool"` 恰 1 处、`CommodityId("tool")` **零**命中） | 新代码写 `new CommodityId(EconomyVocabulary.TOOL_COMMODITY_ID)`（引用，不含字面量）⇒ 两条断言都**不破** |
| S12 | `EconomyVocabulary.dailyNeedsMilli`（`:232-238`）仍是"粮+布"两行，且**全仓 src/main 零调用点**（只有它自己的类注提到它） | 这条**legacy 非运行时口径**（创世播种 / `OfficePolicy` 默认值）本批**不动**：把 tool 塞进去会让 unit 的官俸/军俸默认政策凭空多一条工具定额（越界到 unit）。见 §5 的 D-4 |
| S13 | `simos-social/pom.xml` 的 economy 相关声明只有 `simos-economy-api`（`:42`），且 HEAD 与本批**逐字相同** | I-F4：本批**没有**给 social 加任何新依赖（§6.3） |

---

## 2. 实现架构（本批实际长出来的形状）

```
① 自然需求：SocialProvisioning.defaults() 第 3 列（唯一拼写点）
   SocialProvisioning.java:102   private static final CommodityId TOOL = new CommodityId(EconomyVocabulary.TOOL_COMMODITY_ID);
   SocialProvisioning.java:112-118  三个具名标定常量 TOOL_MILLI_PER_{CHILD=100, ADULT=200, ELDER=150}_PER_YEAR
   SocialProvisioning.java:138-235  18 行（每档 MALE→FEMALE、每格 粮→布→工具）：
       tool 全部 period=PER_CALENDAR_YEAR、cycleDays=0（与布同轴，不与粮的 120 天混轴）
   下游（一字未改，S2/S3 证明已经是多商品）：SocialData.householdNaturalNeeds → app naturalNeedsOf
       → EconomyDayStepper.updateNaturalNeeds → HouseholdEconomy.naturalNeeds

② 批量：纯推导 + 命令分支 + 工具参数
   SocialProvisioningEdits.java:51   MAX_BATCH_EDITS = 1_024
   SocialProvisioningEdits.java:69   record DemandEdit(householdId?, ageBracket, sex, commodity, amountMilli?, period?, cycleDays?)
                                     （构造期拒 null 的 年龄档/性别/商品；其余字段的缺席各有语义）
   SocialProvisioningEdits.java:110  editDemands(base, edits)  ← 唯一批量纯推导
       次序：null → 空批拒 → 超限拒 → 逐条【目标键去重 → 删除时不许带 period/cycleDays → setDemand|clearDemand】
       逐条失败包一层（带"第几条 + 目标键"），整批抛；成功返回**一份**新 SocialData
   SetDemandCoefficientHandler.java:84   ENTRIES_FIELD = "entries"（唯一拼写点）
   SetDemandCoefficientHandler.java:102  handle(): parse（单独 try，拒因走老的 rejectSingle）→ hasEntries ? handleBatch : handleSingle
   SetDemandCoefficientHandler.java:123  handleSingle(): **Batch 4 的原体，逐字搬进来，语义/事件名/日志一字未改**
   SetDemandCoefficientHandler.java:208  handleBatch(): 形状互斥检查 → 逐条目解析 → editDemands → INFO 计数 → DEBUG 明细
                                         → SocialChangeSet.between(base, next) **只比一次** ⇒ 一条 revision
   SetDemandCoefficientHandler.java:327  parseEdit(entry, index)：与单条同一批 SocialPayloads 解析器，坏条目带序号拒
   SocialDemandTool.java:217  runBatch()：工具级批量（与单条参数互斥）；载荷由**解析后的 DemandEdit 重建**（预览/提交同一份值）
   SocialDemandTool.java:297  demandEditArg()：条目参数用与单条**同一批** arg 解析器（ageBracketArg/sexArg/commodityArg/…）
   CatalogTool.java:258-275   PAYLOAD_HINTS 的 SetDemandCoefficient 条目补一段批量形状（键集未动）
   Shell.java:622-628         **只改注释**（注册的 handler 对象、顺序、条数一概未动）
```

**数据流（批量）**：`Command(social.SetDemandCoefficient{reason, entries[]})` → handler 解析成 `List<DemandEdit>`
→ `SocialProvisioningEdits.editDemands` 纯推导（逐条 `withGlobalDemand` / `withHouseholdDemand` / `withoutHouseholdDemand`）
→ `SocialChangeSet.between(base, next)` → Core 落**一条** revision。**任一条抛 ⇒ 上面这条链一步都没发生。**

---

## 3. 工具系数：取值 + 理由（★ 新增标定值，会改变数值行为）

| 年龄档 | 性别 | 工具（毫/人·**历年**） | 布（毫/人·历年，既有） | 工具/布 |
|---|---|---|---|---|
| 0-14 | MALE / FEMALE | **100** | 600 | 1/6 |
| 15-59 | MALE / FEMALE | **200** | 1,000 / 1,200 | 1/6 ~ 1/5 |
| 60+ | MALE / FEMALE | **150** | 800 / 900 | 1/6 ~ 1/5 |

**理由（逐条可核）**：

1. **与布同轴**：`PER_CALENDAR_YEAR` + `cycleDays = 0`，走 `CalendarClock.yearFraction(day, day+1).multiplyFloor(total)`
   —— 工具与布同属"耐用品/年耗品"，**不与粮的 120 天业务周期混轴**（`SocialData.householdNaturalNeeds` 要求同一商品只有一个口径，
   两个口径不能共存于同一商品；工具与布各自保留自己的口径是合法的）。
2. **量级比布更小**（派单书要求"与布同量纲但更小"）：布 1,000 毫 = **1 件/人·年**（"一年添一身衣裳"，`EconomyVocabulary:97`），
   工具 200 毫 = **0.2 件/人·年 ≈ 5 人年一件器具**。器具是几年一换的耐用品，不是年耗品。
3. **未成年减半（100）**：器具在户内共用、随户不随人；未成年的份额没有成年人高。
4. **老年 150 = 成年的 3/4**：照布表同一条降档口径（布 老人/成人 = 800/1000 = 0.8、900/1200 = 0.75）。
5. **不按性别分（M = F）**：工具是**户级生产资料**，没有衣着那种稳定的性别差异 ⇒ **不为了凑差异造差异**
   （这条与布表不同，是**有意的**：布表的性别差异有现实依据）。
6. **量级可核（真跑读数）**：三区世界 342 户里 **203 户**有 tool 需求、合计 **2,699 毫/日**；
   例：62 人的 `hh--1_-1-rural-poor_peasant` ⇒ `naturalNeeds={cloth=152, grain=4142, tool=27}`、`expectedNeedTool30d=810`（§6.1）。

★ **这组值是"新增标定值"，属会改变数值行为**（工具开始有需求侧）——见 §5 的清单。
★ 按设计书 §4.2，系数取值属 **O1 开放点**，由实现方给具名常量 + 理由，控制方按"与粮可比"审；**已落成代码里的具名常量**
（`SocialProvisioning.java:112-118`，三档各一个，带中文理由注释），不是散落的字面量。

---

## 4. 批量载荷形状 + 原子性 + 向后兼容（证据）

### 4.1 形状（冻结）

```jsonc
// ① 单条（Batch 4 老形状，一字未改）
{"householdId"?, "ageBracket", "sex", "commodity", "amountMilli"?, "period"?, "cycleDays"?, "reason"}

// ② 批量（本批新增；与 ① 的顶层字段互斥）
{"reason": "...",                       // 批级审计理由（必填非空白，与单条同一句 requireReason）
 "entries": [                            // 数组、1..1024 条
   {"householdId"?, "ageBracket", "sex", "commodity",
    "amountMilli"?,                      // 给了 = upsert；缺席 = 删除该家户覆盖键
    "period"?, "cycleDays"?,             // 同单条语义（同进同出；删除时不许给）
    "reason"?}                           // 可选：条目自己的补充理由（只进逐条 DEBUG 明细）
 ]}
```

**互斥规则**（fail-closed，不猜"以哪个为准"）：`entries` 在场（非 JSON null）⇒ 顶层**不得**再给
`householdId/ageBracket/sex/commodity/amountMilli/period/cycleDays`，否则具名拒。
`entries` 缺席**或为 JSON `null`** ⇒ 走老的单条路径（实测：`entries:null` 与不带 `entries` 走同一条路，§4.3）。

**批内不变量**（唯一拼写点 `SocialProvisioningEdits.editDemands`）：
至少 1 条；至多 `MAX_BATCH_EDITS = 1024` 条；同一 `(家户, 年龄档, 性别, 商品)` 批内**不得重复**（否则结果取决于载荷内部次序）；
删除条目不得带 `period/cycleDays`。逐条**复用** `setDemand`/`clearDemand` 的既有校验（家户必须存在、负值拒、口径推断/一致性、全局删键拒）。

### 4.2 原子性怎么成立（不是"检查一遍再写"，是**结构上写不出中间态**）

1. `editDemands` 是**纯函数**：只调 `SocialProvisioning` 的不可变 copy-with，返回新 `SocialData`；没有任何写口。
2. 逐条把结果串在 `current` 上；**任一条抛 ⇒ 整个方法抛**，`current` 的所有中间值都是不可变值、被丢弃。
3. 命令层只在**全部条目都算完之后**才比一次 `SocialChangeSet.between(base, next)`（handler `:303`）
   ⇒ 失败路径上**根本没走到** `between`，自然**零 revision、head 不动**。
4. 实测：11 个负向**全部** `headBefore == headAfter == 34`、`revisionsAdded=0`（§6.2）；成功批 **`revisionsAdded=1`**（§6.3）。

### 4.3 向后兼容证据（"老载荷逐字走老路径"）

| 判据 | 证据 |
|---|---|
| 老的单条载荷**逐字**走老路径 | 新码：`OLD_SINGLE_UPSERT` ⇒ `SOCIAL_DEMAND_COEFFICIENT_SET`（老事件名）+ `Committed@32`（head 31→32，+1 revision）；读回覆盖 = `700`。**旧码同一 payload ⇒ 同一个 `Committed@32`**（§6.4 两轮对照） |
| `entries: null` 也走老路径 | 新码 `OLD_SINGLE_ENTRIES_NULL` ⇒ `Committed@33`、读回 `710`；**旧码同样是 `Committed@33`** |
| 老路径的坏载荷拒因不变 | `SocialPayloads.parse` 的 try 语义保住：坏 JSON 仍走 `SOCIAL_DEMAND_COEFFICIENT_REJECTED` WARN + `Rejected(原消息)`（`rejectSingle`，handler `:344-353`） |
| 老路径的**源码**没被改 | `handleSingle`（`:123-206`）是 Batch 4 原体逐字搬入（只去掉了外层 `SocialPayloads.parse`，那一步移到 `handle` 的同语义 try） |
| 修订号序列未被扰动 | 两轮 30 天推进后第一次提交前 head 都是 **31**；两个老载荷都落在 **32/33** ⇒ 日循环的 revision 数逐值相同 |

---

## 5. 偏离设计书处（主动报）

| # | 偏离 | 事实与理由 |
|---|---|---|
| **D-1** | ★★ **T4 的正向判据未达成（BLOCKED，不是偏离而是做不到）** | 设计书 §4.2 要求"布+工具进自然需求"，T4 要求"缺工具的家户**产生购买意图**"。Social 侧做到了（§6.1：tool 需求真进状态），但经济侧 `MarketSettlement.householdLifeReserveOf`（`:5829`）把商品名写死成粮+布 ⇒ tool **零买单**。**因果链见 §1 的 S4/S5 + §6.1 的实测**。修它要改 `simos-economy/**`（派单书明令禁止），故**未动**、上报。 |
| **D-2** | 批量**拒绝"批内重复目标键"** | 设计书/派单书都没规定。取 fail-closed：批是**一个原子单元**，同一目标键出现两次会让结果取决于载荷内部次序（"后写的悄悄赢"）——本仓最反对的静默形态。拒因带第几条 + 目标键。★ 若控制方认为"重复 = 后写覆盖"更合用，这条要单独裁定。 |
| **D-3** | 批量**条数上限 1024** | 设计书未提。理由：批载荷是便捷面不是无界导入口。1024 足以覆盖"6 档 × 6 商品 × 多户"。实测两条分支：1025 条 ⇒ 上限拒；1024 条同键 ⇒ **重复键**拒（证明次序：先判上限再判重复，且上限不在 1024 处误伤）。 |
| **D-4** | **`EconomyVocabulary.dailyNeedsMilli` 不加 tool** | 它是 legacy **非运行时**口径（`EconomyVocabulary:11-15` 明写"新增运行时消费点不得再调用"），全仓 src/main **零调用点**。把 tool 塞进去会让 unit 的 `OfficePolicy` / 官俸军俸默认政策凭空多一条工具定额 ⇒ 越界到 unit，且与 I-F4（运行时需求权威在 Social）无益。 |
| **D-5** | 批量成功日志用**新事件名** `SOCIAL_DEMAND_COEFFICIENT_BATCH_APPLIED`（单条仍是 `SOCIAL_DEMAND_COEFFICIENT_SET`） | 派单书要求"整批 INFO（几条 upsert / 几条删除 / 涉及键数）"。用新事件名让"批"与"单条"在日志里可分辨（旧事件名的字段与语义一字未动，读日志的人不会被改口径）。 |
| **D-6** | 工具 `simos.social.demand` 的 `jsonSchema().required` 由 4 项收成 `["reason"]` | 批量形状的必填字段与单条不同，一张静态 `required` 表表达不了"形状相关必填"；两种形状的必填改由 `run()` 逐形状强制（缺 ⇒ 具名 `BAD_REQUEST`）。全仓无该 schema 的断言（S9）。**`required` 从 4 项收到 1 项是"变松"**：单条漏字段从"客户端就可能拦"变成"服务端必拒"——拒因仍具名，不静默。 |
| **D-7** | 条目级 `reason` **接受但只进 DEBUG 明细**（不覆盖批级 reason） | 派单书建议 entries 是"单条同形"（单条含 reason）⇒ 接受它才不会把合法载荷判死；但一条 revision 只能有一个审计理由 ⇒ 批级 `reason` 仍是唯一审计理由，条目级只作补充明细（不静默丢弃：DEBUG 行里带着）。 |

★ **没有偏离**（逐条对齐派单书）：不新增商品种类（只用现有 6 种里的 `tool`）；权威仍在 Social（econ 侧零改动、无第二本需求权威）；
铁/木/纤维不进家庭自然需求；批量走既有 GM 面（**没有**新命令类型 / 新工具名 / 新写资源）；老载荷逐字走老路径；
五条铁律（写入口仍只走 Command→ChangeSet→Revision；需求状态组件形状未改）。

---

## 6. 探针输出（T4 / 批量 / 负向 / 向后兼容 / I-F4 / 粮布未变）—— 真跑读数

**装置**：`/tmp/dprobe/`（**不进仓库、不进 `src/test`**）

- `src/.../DemandProbe.java`：**只用改动前后都存在的老 API** ⇒ 同一份源码对新码/旧码各编一次。
  真 `Shell.start` + 真创世 + 真推进 30 天；读 `ToolSupport.socialData/economyData`、app 的
  `MarketReadoutAssembly.contextFor`（GUI/MCP 共用的同一份读数）、`MarketReport`；
  提交走真 `CoreSimos.submit(new CommandEnvelope(...))`（与 `SocialDemandTool` 组包同形）。
- `src/.../DefaultsDumpProbe.java`：只 dump `SocialProvisioning.defaults()` 的逐行值（供新旧 diff）。
- `src/.../CapProbe.java`：批载荷条数上限两条分支。
- 旧码对照：`git worktree add --detach /tmp/dprobe/oldwt 7cc31e3c` + `tools/mvn-lock.sh -q -o -DskipTests compile -pl simos-app -am`。

**跑法**（每条真跑，exit=0）：

```bash
cd /home/cna/SimulatorMosire
CP_NEW="/tmp/dprobe/out:$(ls -d simos-*/target/classes | tr '\n' ':')/home/cna/SimulatorMosire/simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar"
java -cp "$CP_NEW" io.mosire.simos.app.time.DemandProbe three-powers /tmp/dprobe/store-new-3p 30
java -Dsimos.social.logLevel=DEBUG -cp "$CP_NEW" io.mosire.simos.app.time.DemandProbe three-powers /tmp/dprobe/store-dbg-3p 5
java -cp "$CP_NEW" io.mosire.simos.app.time.CapProbe small-world /tmp/dprobe/store-cap-sw
# 旧码轮：同一份源码、同一命令，classpath 前缀换成 /tmp/dprobe/out-old + /tmp/dprobe/oldwt/simos-*/target/classes
java -cp "$CP_OLD" io.mosire.simos.app.time.DemandProbe three-powers /tmp/dprobe/store-old-3p 30
```

### 6.1 ★ T4：工具需求**进了状态**，但**没有购买意图**（BLOCKED 的实证）

新码（30 天，`three-powers`，342 户）：

```
NEED_TOTALS grainMilli=424892 clothMilli=15463 clothHouseholds=203 toolMilli=2699 toolHouseholds=203
HOUSEHOLD_NEED household=hh--1_-1-rural-poor_peasant population=62 naturalNeeds={cloth=152, grain=4142, tool=27} expectedNeedTool30d=810 …
EVIDENCE day=30 households=342
READOUT region=c-tp-copper commodity=grain  naturalNeedMilli=5309142 window=cycle            effectiveDemandMilli=45786  supplyMilli=12848537 match=traded=797122 buyerOutcomes=85
READOUT region=c-tp-copper commodity=cloth  naturalNeedMilli=6438    window=last-settled-day effectiveDemandMilli=225155 supplyMilli=0        match=traded=0      buyerOutcomes=85
READOUT region=c-tp-copper commodity=tool   naturalNeedMilli=1125    window=last-settled-day effectiveDemandMilli=0      supplyMilli=0        match=traded=0      buyerOutcomes=0
BUYER_OUTCOMES commodity=grain count=200 orderedQty=1867388
BUYER_OUTCOMES commodity=cloth count=200 orderedQty=618955
BUYER_OUTCOMES commodity=tool  count=0   orderedQty=0
FILLS commodity=grain count=181 / cloth count=0 / tool count=0
```

⇒ **判据①的正面（"缺工具的家户产生购买意图"）不成立**：tool 有**需求**（`naturalNeedMilli=1125`、203 户、`expectedNeedTool30d>0`）
但 `effectiveDemandMilli=0`、`buyerOutcomes=0`、`orderedQty=0`。对照 cloth（同一条多商品链路）有 `effectiveDemandMilli=225155 / buyerOutcomes=85`
⇒ 差异**不在**"Social 没展开"，而在**经济侧买目标只认粮+布**（§1 S4）。

**对照组（旧码 `7cc31e3c`，同世界、同天数、同一份探针源码）**：

```
DEFAULTS_ROWS 12                                     ← 旧码默认表只有 12 行（粮/布）
NEED_TOTALS grainMilli=424892 clothMilli=15463 clothHouseholds=203 toolMilli=0 toolHouseholds=0
READOUT region=c-tp-copper commodity=tool naturalNeedMilli=0 effectiveDemandMilli=0 buyerOutcomes=0
BUYER_OUTCOMES commodity=tool count=0 orderedQty=0
SUBMIT BATCH_MIXED_5 result=Rejected[字段 ageBracket 必须是字符串: {"reason":"probe-batch-5","entries":[…]}]
```

⇒ 对照组成立：改动前**根本没有工具需求**（`toolMilli=0`），批量载荷旧码**整条拒**（不认识 `entries`）。
★ **粮/布的注入值在两轮里逐字相同**（`grainMilli`/`clothMilli`/`clothHouseholds` 三个数一模一样，
`HOUSEHOLD_NEED` 行去掉 tool 字段后 `diff` = **0 行**，`READOUT` 的 grain/cloth 行 `diff` = **0 行**）。

### 6.2 批量：真提交、逐条生效（一条 revision）

```
SUBMIT BATCH_MIXED_5 result=Committed@34 headBefore=33 headAfter=34 revisionsAdded=1
BATCH_REVISIONS_ADDED 1
OVERRIDES households=1
HOUSEHOLD_OVERRIDE household=hh--3_0-rural-poor_peasant ageBracket=15-59 sex=MALE commodity=grain amountMilli=11111 period=PER_CYCLE_DAYS
HOUSEHOLD_OVERRIDE household=hh--3_0-rural-poor_peasant ageBracket=0-14 sex=FEMALE commodity=tool amountMilli=130 period=PER_CALENDAR_YEAR
GLOBAL_READBACK key=15-59/MALE/cloth hit=1100@PER_CALENDAR_YEAR
GLOBAL_READBACK key=0-14/MALE/tool  hit=120@PER_CALENDAR_YEAR
```

载荷 5 条 = 2 条全局默认 upsert + 2 条家户覆盖 upsert + **1 条删除**（删掉 4.3 里老路径建的那条覆盖）。
**5 条全部逐条读回**：全局 1100 / 120；家户 grain 11111（PER_CYCLE_DAYS）、tool 130；被删的 cloth 覆盖**不在** `OVERRIDES` 里了。
INFO 日志（§一.9 的"整批 INFO"）：

```
INFO event=SOCIAL_DEMAND_COEFFICIENT_BATCH_APPLIED entries=5 upserts=4 deletes=1 globalRows=2 households=1 keys=5 reason=probe-batch-5
DEBUG event=SOCIAL_DEMAND_COEFFICIENT_BATCH_ENTRIES entries=[0:global/15-59/MALE/cloth/1100, 1:global/0-14/MALE/tool/120,
      2:hh--3_0-rural-poor_peasant/15-59/MALE/grain/11111, 3:…/0-14/FEMALE/tool/130/entry-level-reason, 4:…/15-59/MALE/cloth/delete]
```

### 6.3 负向：11 条全部整批具名拒 + **零 revision + head 不动**（贴修订号）

| # | 载荷缺陷 | 结果（逐字摘） | head |
|---|---|---|---|
| N1 | 第 2 条未知商品 `unobtainium`（未给口径） | `Rejected[批量需求编辑第 1 条不合法（整批拒绝，零 revision）: household=global ageBracket=60+ sex=MALE commodity=unobtainium ⇒ 找不到商品 unobtainium 的全局需求口径…]` | 34→34 |
| N2 | 第 2 条 `amountMilli=-1` | `…第 1 条…: household=global ageBracket=60+ sex=FEMALE commodity=tool ⇒ DemandCoefficient.amountMilli 不得为负: -1` | 34→34 |
| N3 | 第 2 条未知家户 `hh-does-not-exist` | `…第 1 条…: household=hh-does-not-exist … ⇒ 家户不存在: hh-does-not-exist` | 34→34 |
| N4 | 批内**重复目标键** | `…第 1 条与前面的条目目标键重复（同一个 (家户, 年龄档, 性别, 商品) 在批内只允许一次）: …` | 34→34 |
| N5 | `entries: []` | `批量需求编辑至少要有 1 条（空批不落 revision）` | 34→34 |
| N6 | 批 + 顶层单条字段并存 | `social.SetDemandCoefficient：批载荷（entries）与单条字段 ageBracket 互斥（批用 entries 逐条给键，单条用顶层字段）` | 34→34 |
| N7 | 批内**删全局默认键** | `…第 0 条…: household=global … ⇒ 全局需求默认表不允许删键（会破坏默认完整性）…` | 34→34 |
| N8 | 删除条目带 `period/cycleDays` | `…第 0 条：删除家户覆盖键时不接受 period/cycleDays（没有系数可构造）: …` | 34→34 |
| N9 | 条目不是对象 | `social.SetDemandCoefficient：entries[0] 必须是 JSON 对象` | 34→34 |
| N10 | `entries` 不是数组 | `social.SetDemandCoefficient：字段 entries 必须是数组` | 34→34 |
| N11 | 缺批级 `reason` | `字段 reason 必须是字符串: {"entries":[…]}` | 34→34 |

日志面（AGENTS §一.9 / 派单书"单条拒时 DEBUG 写为什么"）：每条拒都同时落
`WARN event=SOCIAL_DEMAND_COEFFICIENT_BATCH_REJECTED reason=…` + `DEBUG event=SOCIAL_DEMAND_COEFFICIENT_BATCH_REJECTED_DETAIL detail=<第几条 + 目标键 + 内层原因>`（DEBUG 轮实测 11 条全在）。
上层还有 Core 的 `INFO event=COMMAND_REJECTED … reason=…`。**零 revision** 也可由 `PROBE_END revision=34` 复核。

**条数上限两条分支**（`CAP-sw.log`）：

```
CAP_SUBMIT CAP_1025     payloadBytes=74859 result=Rejected[批量需求编辑最多 1024 条（本批 1025 条）]     headBefore=1 headAfter=1 revisionsAdded=0
CAP_SUBMIT CAP_1024_DUP payloadBytes=74786 result=Rejected[批量需求编辑第 1 条与前面的条目目标键重复…]  headBefore=1 headAfter=1 revisionsAdded=0
```

### 6.4 向后兼容（同 payload ⇒ 新旧两轮**同结局同修订号**）

| 载荷 | 新码 | 旧码 `7cc31e3c` |
|---|---|---|
| `{"householdId":…,"ageBracket":"15-59","sex":"MALE","commodity":"cloth","amountMilli":700,"reason":"probe-old-single-upsert"}` | `Committed@32`（31→32，+1 revision）；读回 `700`；事件 `SOCIAL_DEMAND_COEFFICIENT_SET` | **`Committed@32`**（+1 revision） |
| 同上但带 `"entries":null`（`amountMilli=710`） | `Committed@33`（32→33）；读回 `710` | **`Committed@33`** |
| `{"reason":"probe-batch-5","entries":[…]}` | `Committed@34` | `Rejected[字段 ageBracket 必须是字符串: …]`（旧码只认单条形状） |

### 6.5 I-F4：`social` 没有新增 economy 依赖（贴核对）

```
$ git diff -- simos-social/src/main | grep -E '^\+import'          # 本批在 social 新增的全部 import
+import java.util.LinkedHashSet;                                    # JDK
+import java.util.List;                                              # JDK
+import java.util.Set;                                               # JDK
+import io.mosire.simos.social.provisioning.SocialProvisioningEdits.DemandEdit;   # 同模块嵌套类型
+import java.util.ArrayList;  +import java.util.LinkedHashMap;  +import java.util.Map;   # JDK

$ grep -rn '^import io.mosire.simos.economy\.' simos-social/src/main | grep -v 'economy\.api\.' | wc -l
0        # 新码；对照：HEAD 旧码同样 0
$ grep -n economy simos-social/pom.xml     # 只有 simos-economy-api（:42）；与旧码逐字相同（pom 未改）
```

⇒ social 侧**只用** `economy.api.id.CommodityId`（契约层，文档三处明文允许，且**早在改动前就在用**）；
**零** `simos-economy` 依赖；本批**没有**在 economy 侧另造第二本需求权威（`git diff --stat -- simos-economy` = 0 行）。

### 6.6 粮/布的既有默认值**逐值未变**（贴对照）

```
$ java -cp "$CP_OLD" …DefaultsDumpProbe        # 旧码：DEFAULTS_DEMAND_ROWS 12，commodities=[grain, cloth]
$ java -cp "$CP_NEW" …DefaultsDumpProbe        # 新码：DEFAULTS_DEMAND_ROWS 18，commodities=[grain, cloth, tool]
$ diff <(旧码 DEMAND/LABOR/BASIS 行，剔除 tool) <(新码 同) ; echo $?
0        # ← 12 行粮/布 + 6 行劳动 + 全部 BASIS 行逐字相同（唯一差异是行数行 12→18 与新增的 6 行 tool）
新码新增的 6 行：
DEMAND ageBracket=0-14  MALE/FEMALE commodity=tool amountMilli=100 period=PER_CALENDAR_YEAR cycleDays=0
DEMAND ageBracket=15-59 MALE/FEMALE commodity=tool amountMilli=200 period=PER_CALENDAR_YEAR cycleDays=0
DEMAND ageBracket=60+   MALE/FEMALE commodity=tool amountMilli=150 period=PER_CALENDAR_YEAR cycleDays=0
BASIS commodity=tool basis=DemandBasis[period=PER_CALENDAR_YEAR, cycleDays=0]
```

另：世界状态里的默认表（不是再调一次 `defaults()`）逐行 dump 也是这 18 行（`WORLD_DEFAULT …`，§6.1 的 `DEFAULTS_ROWS 18`）；
`SOCIAL_PROVISIONING_DEFAULTS_SEEDED demandRows=18 laborRows=6 commodities=[grain, cloth, tool]`。

### 6.7 权限面（结构性核对）

- **没有**新增命令类型（仍 `social.SetDemandCoefficient`）、**没有**新增工具名（仍 `simos.social.demand`）、**没有**新增写资源
  （`WRITE_RESOURCES` 仍是 `social:*`；命令仍 `GmOnlyCommand`）。
- `Shell.java` 的 diff **只有注释**（已在 diff 里逐行核过）；`grep -c 'new .*Handler()' Shell.java` 新旧**相等**。
- 工具 `jsonSchema()` 的**属性集**只多了 `entries` 一个（`required` 由 4 项收到 1 项，见 D-6）——**没有**把任何工具搬进决策人桶。

---

## 7. 会改变数值行为的清单（★ 给测试代理当输入）

| # | 何时改变 | 改什么 | 量级（实测/推算） |
|---|---|---|---|
| **N-1** | **无条件**（新世界一创世就有） | 家户 `HouseholdEconomy.naturalNeeds` 多一个 `tool` 键；`expectedNeedMilli(tool, n) > 0` | 三区 342 户里 **203 户**有 tool 需求，合计 **2,699 毫/日**；62 人户 `tool=27 毫/日`、30 天目标 810 |
| **N-2** | 同上 | `MarketReadout` 的逐区 tool 行 `naturalNeedMilli` 由 **0 → 1125**（c-tp-copper） | 逐区不同；`naturalNeedWindow=last-settled-day`（非粮商品没有周期累加器） |
| **N-3** | 同上 | `MarketSettlement.moneyReserveOfHousehold`（`:6000-6026`，**遍历全部 naturalNeeds**）为 tool 多算一笔货币保留额 | 无 tool 库存时 ≈ `uncovered(tool) × tool价 ÷ 1000` 毫；有库存后 `uncovered=0` ⇒ **一次性/自限**（三区 30 天里 tool 买卖都是 0，未观测到成交层面的后果） |
| **N-4** | 同上 | `MarketDemandBook` 的消费者需求桶（`:250`）多 tool 一项；`ExpectedProfitBook`（`:372`）与 `HouseholdValuationBook`（`:199/:342`）的 **30 天目标保有量**对 tool 由 0 变正 ⇒ 持 tool 户的**保留价/不卖倾向**变强 | 逐值未测（三区 30 天 `supplyMilli(tool)=0` 未变）；**这是"卖侧"最可能被本批改动的地方**，测试代理应重点看 |
| **N-5** | ★ **不改变** | `tool` **不产生买单**（`BUYER_OUTCOMES tool=0`、`effectiveDemandMilli=0`、`orderedQty=0`） | 因为 `householdLifeReserveOf` 只认粮+布（§1 S4）⇒ **T4 正面不成立** |
| **N-6** | ★ **不改变** | 粮/布的**全部**需求口径与注入值；日循环的 **revision 条数**；老单条命令的结局与修订号 | 实测：`grainMilli=424892 / clothMilli=15463 / clothHouseholds=203` 两轮逐字相同；`HOUSEHOLD_NEED`（去 tool）diff=0；`READOUT` grain/cloth diff=0；30 天后 head 都是 31、老载荷都落 32/33 |
| **N-7** | 仅在 GM 用**批量**载荷时 | 一条命令可改最多 1024 条；**新增** 6 类具名拒（空批 / 超限 / 重复键 / 两形状并存 / `entries` 非数组 / 条目非对象） | 见 §6.3；老载荷**不受影响** |

**受影响硬编码字面量（给测试代理）**：新增 3 个具名常量
`SocialProvisioning.TOOL_MILLI_PER_{CHILD=100,ADULT=200,ELDER=150}_PER_YEAR`（private，`SocialProvisioning.java:112-118`）；
**未改**任何既有字面量（粮/布/劳动/口径一律未动）；**未新增** `EconomyVocabulary` 常量（`TOOL_COMMODITY_ID` 早已存在）；
`MAX_BATCH_EDITS = 1_024`（`SocialProvisioningEdits.java:51`）与 `ENTRIES_FIELD = "entries"`（handler `:84`）是本批新增的具名常量。

---

## 8. 会让既有测试失效的清单（★ 我没改任何测试、没跑任何测试）

**编译层：预期零失效。** 本批**只新增**（新嵌套 record `DemandEdit`、新方法 `editDemands`、新常量、新 handler 私有方法），
**没有**删除/改名/改签名任何既有公开 API；唯一"改形状"的是工具 `jsonSchema()`（多一个属性、`required` 由 4 收到 1）与
`CatalogTool` 的**提示文案**——两者在既有测试里都**没有断言**（实测：`grep required.*schema simos-app/src/test` 零命中；
对 `simos.social.demand` 只出现在**工具名清单**里：`SimosToolsTest:316` / `McpServerTest:221` / `McpPortTopologyTest:139`，名字未变）。

**行为层：候选（我未跑，如实列）**——判据是"是否会跑真创世 + 日循环 + 对经济读数下断言"：

| # | 测试 | 为何可能失效 | 我的判断 |
|---|---|---|---|
| T-1 | `simos-app` `tools/write/GovToolsZ6Test` | 走真创世 + `advanceAndDrain` + 对 **gov 国库 money** 下断言（`:982/:991`） | **低风险**：断言的是 GOV 户银账、remit 是显式金额；但 tool 进了 `naturalNeeds` ⇒ 家户货币保留额与市场结果可能变 ⇒ **建议测试代理实跑确认** |
| T-2 | `simos-app` `time/Z7RemittanceE2ETest`、`ShellSmokeTest`、`UnitExtensionEndToEndTest` | 都真推进；`ShellSmokeTest`/`UnitExtensionEndToEndTest` 里 economy 是 `EconomyData.empty()`（无市场 ⇒ 不受影响） | **预期仍绿**（无 economy 数值断言），但 `Z7RemittanceE2ETest` 未逐行核 |
| T-3 | `simos-app` `McpCoverageTest:435-445` 的 `social.SetDemandCoefficient` 最小载荷 `{ageBracket:"NO_SUCH_BRACKET",…}` | 断言"具名拒" | **预期仍绿**：`entries` 缺席 ⇒ 老路径 ⇒ 同一句拒因（§4.3） |
| T-4 | `simos-util` `verify/EconomyVocabularyGuardTest:164-193` | 钉 `TOOL_COMMODITY_ID = "tool"` 恰 1 处、`CommodityId("tool")` 零命中 | **预期仍绿**：新代码用引用 `EconomyVocabulary.TOOL_COMMODITY_ID`（§1 S11） |
| T-5 | `simos-gov` `GovEfficiencyTest:396`、`GovDailyTest:131` | 前者只断言 `standardLaborMilliHoursPerTick()`（劳动表未动）；后者断言 `OfficePolicy.defaults()` 的粮/布两件（unit 侧，未动） | **预期仍绿** |
| T-6 | `simos-economy` 全部 | 经济侧**零改动**；其测试多用手搭 `HouseholdEconomy`（18 个文件 `new HouseholdEconomy(`）自造 `naturalNeeds` | **预期仍绿** |

★ **仍要为"新判据"补用例**（下一批测试代理）：tool 进了 `naturalNeeds` 且逐日/逐年折算正确（`PER_CALENDAR_YEAR` 平年 365/闰年 366）、
粮/布逐值未变、批量的原子性（成功=1 revision、任一非法=0 revision）、11 条负向拒因、老单条载荷回归、
条数上限两条分支、I-F4（social 无 economy 依赖）。
★ **T4 的正面用例在 economy 侧修好之前写不出来**（写了必红）——见 §9。

---

## 9. 未完成 / 未验证 / BLOCKED

| # | 项 | 状态 |
|---|---|---|
| U1 | **编译**（本批唯一获授权的 Maven）：`tools/mvn-lock.sh -q -o -DskipTests compile -pl simos-app -am` | ✅ **exit=0**（跑前 `pgrep` 确认无 `classworlds.launcher`/`surefirebooter`；跑了两次，末次在所有源码改完之后） |
| U2 | `test` / `verify` / `package` / Spotless / Checkstyle / SpotBugs / 前端门禁 | ❌ **未跑**（派单书禁止）。已手工把每行控在 ≤100 列，但 **Spotless 是否逐字满意未验证** |
| **B1** | ★★ **T4 正面判据 BLOCKED：工具不产生购买意图** | **做不到，不是没做**。原因：`simos-economy/.../MarketSettlement.java:5829` 的 `householdLifeReserveOf` **只取粮/布两行**（`expectedNeedMilli(GRAIN/CLOTH, MARKET_LIFE_RESERVE_DAYS)`），而家户买目标就是它（`:1604` `baseTarget = life`）⇒ tool 的 `life` 恒空 ⇒ `desiredQuantity` 恒 0。**最小额外范围 = 1 个文件 / 1~2 个方法**：把该方法（以及单位户经营者侧的 `operatorLifeRetentionOf`（`:5846`））改成**遍历本户注入的 `naturalNeeds` 键**：<br>`for (CommodityId c : householdEconomy.naturalNeeds().keySet()) { long need = householdEconomy.expectedNeedMilli(c, MARKET_LIFE_RESERVE_DAYS); if (need > 0) life.put(c, need); }`<br>★ 该改动**越出本批文件所有权**（`simos-economy/**` 明令禁止碰）⇒ **未做，上报控制方裁定**。<br>★ **安全降级已就位**（fail-closed，无需额外动作）：本批的改动是**纯加行**，粮/布逐值不变（§6.6），tool 需求被读口如实报出却不凭空造订单——**没有半接线路径**。 |
| U3 | 窄工具 `simos.social.demand` 的**批量分支**（`SocialDemandTool.runBatch`） | ⚠️ **编译过、未端到端真跑**：探针走的是**命令载荷**路径（`CoreSimos.submit`），工具路径需要 GM 权限令牌 + `SimosToolSource` 装配（本批未搭）。它与命令**共用同一份纯推导** `editDemands`，差异只在参数解析与视图拼装（逐字段与单条路径同形）。 |
| U4 | 批载荷的**并发/冲突**语义（`expectedRevision` 不匹配） | ❌ 未打（沿用既有 Core 路径，本批未改；`Conflict` 分支未真跑） |
| U5 | `v17levant` 大世界 | ❌ 未跑（时间/体量）。T4 与批量的证据来自 `three-powers`（30 天）+ `small-world`（创世） |
| U6 | 决策人桶**间接**路径（把批载荷嵌进 `sd.IssueDirective` 的 `RegisterEffect`） | ❌ 未端到端打：命令仍 `GmOnlyCommand`（类级注解未改）⇒ 组合根的令白名单过滤照旧；本批只证到"GM 桶有 / 决策人桶无"的结构不变（§6.7） |
| U7 | 往返不变式（铁律 5） | ❌ 未单独打：本批**未改**任何状态组件/变更集形状（只改需求表的**行数**与命令载荷），`SocialChangeSet` 的字段与外延一字未动 |
| U8 | **任务板 claim/complete** | ⚠️ `task-25` 返回"不是 active Agent Team 成员" ⇒ **未能 claim/complete**（按任务书继续执行，如实报） |

---

## 10. 改动文件（6 改，全在允许面内）

| 文件 | 改了什么 |
|---|---|
| `simos-social/.../provisioning/SocialProvisioning.java` | ① 的**唯一落点**：`TOOL` 常量（`:102`）、三个具名标定常量（`:112-118`）、6 行 tool（`:138-235`）、类注/表注更新 |
| `simos-social/.../provisioning/SocialProvisioningEdits.java` | ② 的**唯一纯推导**：`MAX_BATCH_EDITS`（`:51`）、`record DemandEdit`（`:69`）、`editDemands`（`:110`）、私有 `BatchKey` |
| `simos-social/.../spi/SetDemandCoefficientHandler.java` | `ENTRIES_FIELD`（`:84`）、`handle` 分流、`handleSingle`（原体逐字）、`handleBatch`（`:208`）、`parseEdit`（`:327`）、`rejectSingle`；类注加批量形状 |
| `simos-app/.../tools/write/SocialDemandTool.java` | `entries` 参数（schema/描述）、`runBatch`（`:217`）、`demandEditArg`（`:297`）、`editPayload`/`entryReasonArg`/`stringKeyed`（与单条共用 arg 解析器） |
| `simos-app/.../tools/read/CatalogTool.java` | `PAYLOAD_HINTS` 的 `social.SetDemandCoefficient` 条目补一段批量形状（键集/条数未动） |
| `simos-app/.../app/Shell.java` | **只改注释**（注册的 handler 对象、顺序、条数一概未动） |

**未碰**：任何 `src/test/**`、任何 `pom.xml`、`docs/**`、`.superpowers/**`（除本目录）、**`simos-economy/**`**（`git diff --stat` = 0 行）、
`simos-util/**`（`EconomyVocabulary` 未改）、口岸/关税、货运表（F/F2 批）、`.superpowers/sdd/2026-10-09-commodity-freight*/`（留痕不改）。

**探针（不进仓库）**：`/tmp/dprobe/{src,out,out-old,logs}/`、`three-powers` 三个 store、`small-world` 一个 store、
参照 worktree `/tmp/dprobe/oldwt`（`7cc31e3c`，可 `git worktree remove /tmp/dprobe/oldwt`）。
