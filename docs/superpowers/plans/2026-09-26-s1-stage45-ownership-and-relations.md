# S1 阶段 4+5（合并）：产出归属 + 生产关系结算 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `ProductionActivity` **不再拥有产出** —— 净产出落 `operator` 的 `GoodsAccount`，并在**同一次结算**里按显式 `ProductionRelation`（规则类型 / `basis` / `priority`）在经营者、劳动者 cohort、资产所有者之间**实物结算**；cohort 因此不断粮。

**Architecture:** 契约（`CohortKey` / `ProductionRelation` / `CompensationRule`）落 **`economy-api`**（两侧切片都看得见）；关系表作为 `EconomyData` 的**第 8 个组件**（键 = `IndustryId`，与 `Industry.operator` 有跨表守卫）；**结算计算**是 `simos-economy` 的纯函数（`ProductionSettlement`），**行侧**（cohort 入账）在同一步落、**产权侧**（账户增减）产出成 `ProductionLedger` 交给 **app 协调器**落盘 —— 只有 app 同时看得见两片（spec §三）。

**Tech Stack:** Java 21 · `./mvnw` · JUnit 5 + AssertJ · 本仓 `record` + 构造期守卫 + Snapshot/ChangeSet/Codec 三件套 + `FieldDelta` 往返

**Spec:** `docs/superpowers/specs/2026-09-26-s1-actor-property-design.md`（§2.1 / §2.4 / §2.5 / §三 / §四 / **§五** / §六 / §七）
**Breakdown:** `docs/superpowers/plans/2026-09-26-s1-stage-breakdown.md` §三 阶段 4/5 + §四「4 ↔ 5」

---

## ★ 先修正上游文档的六处（**已回代码核过**，2026-09-26）

**① ★★ spec §五 第二条守恒式在**过渡期**不成立（代数上必然）。** 该式
`ΔΣActorGoods + ΣFinalConsumption + ΣLoss == ΣOutput − ΣProductionInputs` 隐含两件事：
(a) cohort 侧的入账**恰等于**它的最终消费（即 `ΔΣRowGoods ≡ 0`）、(b) 生产投入**从 Actor 账户扣**。
而阶段 4 的过渡态两条都不满足（入账进 `ClassRow.goods`、投入仍从消费行扣，见裁定 R1/R4）。
⇒ **本阶段必须把 `ΔΣRowGoods` 那一项写进等式**（新式见 R1）；spec §五 那一条是它在阶段 6/7 终态的特例。

**② ★★ spec §六 的 `household → SELF_RETENTION` 与 §七 V3「织布的人拿到布」**不相容**。**
现实是：`operator` 是 `HOUSEHOLD:weave@0_0`（actor），织布的人是 `(hex, 阶层)`（cohort，人口住在 `farm@hex|*` 行）。
自留 ⇒ 布全留在 operator 账上，**劳动者一条也拿不到** ⇒ V3/I6.3 永远不成立。⇒ **R8**（改这一格的默认，并注明修订）。

**③ spec §三 把 `CohortKey` / `ProductionRelation` 的契约列在 `simos-actor-api` —— 该模块装不下它们。**
`simos-actor-api` 的**主依赖为零**（连 `util`/`map`/jackson 都不声明，`AGENT.md` 模块表原文），而 `CompensationRule` 需要
`CommodityId`（economy-api）、`CohortKey` 需要 `HexCoord`（map）。⇒ 与阶段 2「`AssetClassKey` 无物可移」同款修正：**契约落 `economy-api`**（R2）。

**④ spec §2.4 的 `ProductionRelation` 用 `laborCompensationRules` + `assetCompensationRules` 两个列表，而同一维度已由
`CompensationRule.basis`（`LABOR_AMOUNT`/`ASSET_QUANTITY`）与 `priority` 表达** ⇒ 两个列表是**第二个拼写点**（同一批规则能被分成两处、次序也被切成两段）。
且 spec 未写「资产所有者的租**归谁**」：`AssetOwner` 是 actor（§2.1），而经营者 actor（`ESTATE:farm@0_0`）与地主**家户**（cohort `(hex, landlord)`）在 S1 **没有同一化机制**。⇒ **R3 / R8**。

**⑤ spec §六 的 `feudal → 实物给养` 没写"谁给养到哪一层"。** 现行 `harvest` 是把净产按
`AllocationRule.Split(700,300)` **发给本产业的四个阶层行**（`EconomySettlement.harvest`，`EconomySettlement.java:1294-1367`）——
即**地主行也拿地权那一份**（土地在各行按人口摊，`EconomySeeder.agriculture`）。产出改为落 operator 之后，**地主 cohort 的粮源会凭空消失**
（它既不是劳动者，也不是 actor）。⇒ **R8** 的第 2 条（地租显式给 `(hex, landlord)` cohort）。

**⑥ breakdown 阶段 1 把 `ClassKey → CohortKey` **并入阶段 4**，而阶段 4 的"改动面"（breakdown 原文）一字未提它。**
⇒ **R9**：本阶段**不合并行键**，`CohortKey` 只作**结算受方身份**引入（代价与理由见 R9）。

---

## ★ 本计划的裁定（**请重点看这 9 条**）

### R1. ★★ 守恒式重画：**旧式怎么改、新式怎么验**

```
【旧式｜今日，只覆盖 ClassRow】Σ前库存_j − Σ后库存_j == Σconsumed_j − Σincome_j        （EconomySettlement 类注 §6.1）
  ★ 产出离开 ClassRow 之后**必然不成立**：行不再收到 income（毛产那份），而 actor 侧多了一笔。

【新式｜本阶段，覆盖行 + actor，逐商品、逐周期】
  ΔΣRowGoods_j + ΔΣActorGoods_j + ΣFinalConsumption_j + ΣLoss_j == ΣOutput_j − ΣProductionInputs_j        （I4.2）

  ΣOutput_j           本期毛产      = 规模 × outputPerUnit_j × 1000（关账那一支）
  ΣProductionInputs_j 本期现扣投入  （现扣步；★ 本阶段**仍从消费行扣**）
  ΣLoss_j             本期生产损耗  = 毛产 × (FEED + DEPRECIATION)‰
  ΣFinalConsumption_j 消费行日耗    （= Σ FlowRow.consumed_j − 投入 − 损耗，逐行读得出）
  ΔΣRowGoods_j        消费行库存变化；ΔΣActorGoods_j  actor 账户变化（本阶段唯一被写的 actor 状态）

【代数（写进 EconomySettlement 的类注，逐项可核）】
  行侧：  ΔΣRow   = 入账 − 投入 − 消费            （同格取材两侧相消，见 transferIntraHexInputs）
  actor： ΔΣActor = 毛产 − 损耗 − 入账
  相加：  ΔΣRow + ΔΣActor = 毛产 − 损耗 − 投入 − 消费
  ⇒ ΔΣRow + ΔΣActor + 消费 + 损耗 = 毛产 − 投入   ∎
  ★ spec §五 的第二式 = 上式在 ΔΣRowGoods ≡ 0（阶段 6/7 终态）时的特例。

【生产侧逐 actor（I4.1）】ΔActorGoods(a,j) = Output(a,j) − Input(a,j) − TransfersOut(a,j) + TransfersIn(a,j)
  ★ 过渡期口径：投入仍从消费行扣 ⇒ **Input(actor) ≡ 0**（阶段 6/7 投入改从 operator 扣时才非 0）。
  ★ 付款上限 = 本周期本 activity 收到的产出（R6）⇒ TransfersOut ≤ Output ⇒ ΔActorGoods(a) ≥ 0 结构性成立。

【行侧（形状不变、口径改）】ΔΣRowGoods_j == Σ FlowRow.income_j − Σ FlowRow.consumed_j
  ★ `FlowRow.income` 从「毛产分成」改成「**实物入账**（结算给本行的量）」—— 读口因此不再撒谎。
```

**验证口径**：I4.2 走 **app 协调器**的真实推进（两片同时在场的唯一路径，R4），各右式项**独立手算**（规模 × 产率、损耗率、逐行日耗之和），**不许**从同一份实现再读一遍（那是同义反复，`AGENT.md` §9.1 的假判别力）。

### R2. 契约落 `simos-economy-api`（理由见修正 ③）

| 内容 | 落点 | 依据 |
|---|---|---|
| `CohortKey`（`residence: HexCoord` + `stratum: SocialClassId`） | `economy-api/.../api/cohort/` | 需要 `SocialClassId`（economy-api）+ `HexCoord`（map） |
| `RuleType` / `Basis` / `Recipient` / `CompensationRule` / `ProductionRelation` | `economy-api/.../api/relation/` | 同上；且**两侧切片都要看得见**（economy 算、actor 存）⇒ 只能住契约层（与 `LaborAllocation` 同待遇） |

★ **代价**：`economy-api` 的"只放稳定 ID / 无公式"这句口径要**放宽一格**（`LaborAllocation` 已是先例 ⇒ 记一句"数据记录可以有"）。
★ **错了的代价**：若控制方坚持契约住 `actor-api` ⇒ 那条路是**不可编译**的（主依赖为零），只能退化成本仓最反对的 `String` 槽位。

### R3. 关系表 = `EconomyData` 的**第 8 个组件**（键 `IndustryId`），并与 `Industry.operator` 有跨表守卫

`relations: Map<IndustryId, ProductionRelation>`；`ProductionRelation` **不另造 id**（身份 = 它结算的那个 activity，铁律 1）。
★ **跨表守卫**：`relations[k].operator()` 必须等于 `industries[k].operator()`（同 `EconomyData` 既有的"键 == 值内 key"口径）——
否则"谁经营"有两个拼写点。★ 载荷边缘两处都给时**必须一致**（不一致 ⇒ 抛），缺省时由 regime 同时推两者（R7）。

**问题**：spec §三 把 `ProductionRelation` 的**状态**列在 `simos-actor` 一栏。
**建议**：状态住 economy（**读者只有 economy 的结算**；app 协调器读的是 `ProductionLedger` 而不是关系）。
**理由**：若状态住 actor，则每一次日推进都要由协调器把关系表**当入参递进** economy 的日循环（日循环签名变宽 + "关系是不是当期的"多一条路径），
而经济侧的**写入者必须唯一**（`TimeProposalResolver` 的模块 clash 规则）—— 白白多一处分片缝。
**错了的代价**：低——它是**运行期数据**（§2.4），搬家是一处机械改动；判据（I5.x）一字不动。

### R4. ★★ "完整账"的路径**唯一化**为 app 协调器；economy 单切片入口 `fail-closed`

产出落 operator 那一笔**只能**由同时看得见两片的参与者落（写-写与模块 clash 都要求 economy 的写者唯一）：

| 路径 | 处置 | 理由 |
|---|---|---|
| `PopulationEconomyTimeParticipant`（真档；economy + social） | **加第三个模块 `actor`**；actor 切片**必须在场**（不在场 ⇒ 抛） | 唯一同时看得见三片的地方 |
| **新增** `EconomyOwnershipTimeParticipant`（app；economy + actor） | 服务"有 economy、没有 social"的世界（既有夹具正是这一形态） | 与上者**共用**同一个 app 侧助手，**从不同时注册**（同时注册 ⇒ Core 当场拒，响亮） |
| `EconomySettlement.settle(base, from, to)`（多日静态入口） | ★ **fail-closed**：一旦有产业关账要产出 ⇒ 抛 `IllegalStateException`（"产出落 operator 需要产权落账口：请走协调器"） | 否则产出在账上**凭空消失**（静默）—— 本仓最反对的形态 |
| `simos-economy` 的 `EconomyTimeParticipant` | ★ **删除**（其形态被 app 侧新参与者取代） | 它的实现走 `settle` ⇒ 保留只会变成"一跑就抛"的死类 |

★ **行侧账仍然完整**：`EconomyDayStepper.step(day)` 是 economy 模块内的会话入口，**返回当天的 `ProductionLedger`** ⇒ 单模块用例改走它即可（把 `settle(base,0,N)` 换成 N 次日循环，5 行）。

### R5. **过渡分配** = cohort 入账到**消费行**（`ClassRow.goods`）—— 这就是 4↔5 不能拆的理由

```
关账那一支（harvest）：
  ① 算毛产/损耗/净产（算式不变，EconomySettlement.harvest 现成的）
  ② ★ 净产**不再**写进本产业的行；改为进 ProductionLedger 的产权条目：+ 净产 → operator 的账
  ③ ★★ 同一步里按 relation 逐条（priority 序）把实物**送到 cohort**：ActorRef 受方 → 产权条目（actor→actor）；
     CohortKey 受方 → **写进该 cohort 的消费行**（`ClassRow.goods`）—— 阶段 6 把这一支换成 ConsumptionReceipt
  ④ 余额留 residualOwner（默认 = operator）：不产生任何条目
```
★★ **cohort 断粮就在 ② 与 ③ 之间**：产出离开 `ClassRow` 的那个改动里**必须同时**有 ③，否则行里一份都不进（这是合并的唯一理由）。
★ **本阶段不做 §2.5 的超额上限**（`receipt = min(应得, 消费需求)`）：上限与 `ConsumptionReceipt` 同属阶段 6
（I6.2/V8）——**本阶段的过渡入账是"实得即入账"，与今日 `Split` 的入账形态同构**，故既有 e2e 的"行有粮/有布"这类事实**仍然是事实**（数值按新口径重算）。
**错的代价如实记**：消费行在过渡期仍会**积累库存**（"cohort 无产权能力"要到阶段 6/7 才结构性成立）。

### R6. 付款上限 = 本周期本 activity 收到的产出；**不做跨周期结转与欠租**

理由：结算计算要看 operator 余额，而余额住 actor 切片 ⇒ 只能由协调器递进来（多一处分片缝）。
取"本周期产出"为上限 ⇒ **结算计算不需要任何外部余额输入**，且 `TransfersOut ≤ Output` 结构性成立（I4.1 右侧非负）。
代价：庄园不能拿存粮付租（S2 的 ledger 会给结转与债务）；付不满时**不记账、不留索取权**（与 §2.5 的"超出部分不产生 cohort 侧索取权"同口径）。

### R7. cohort → 行的解析：**唯一拼写点**，且 `population > 0` 是硬条件

```
classRowsOfCohort(rows, cohort) = { rows[k] | hexKeyOf(k.industry()) == cohort.residence()
                                            && k.slot() == cohort.stratum()
                                            && rows[k].population() > 0 }      // 按人口比例分派
```
★ **这一条就是 I4.3 的落点**：`weave@hex|*` 四行**人口恒为 0**（`EconomySeeder.householdWeaving` 明写"它的阶层行不携带人口"）
⇒ 它们**永远不是** cohort 受方 ⇒ 织造的布不再落它们，而落**织布的人**（`farm@hex|<阶层>`）—— V3「织布的人拿到布」在本阶段就成立。
★ 解析不到行 ⇒ **该笔留在 operator**（不抛、不造账）：同格某个阶层人口为 0 是合法状态（`splitByShares` 会造出 0 人的槽位）。
★ **如实记**：`CohortKey(hex, stratum)` 在**城市格**上会把"农村贫农"与"城镇贫农"并成一个 cohort（它们今天靠 `ClassKey` 的产业段区分）
—— 这**正是** R9 那条未合并的身份键的残留，也是 §2.6 目标模型的样子。

### R8. 四档默认关系（`RegimeRelations`）：两处**修订 §六**，参数留给 GM

| `regime` | 默认规则（spec §六 + 本计划修订） |
|---|---|
| `feudal` | **实物给养**给劳动者 cohort（`FIXED_IN_KIND_PER_LABOR`，按劳动量）+ ★**地租**给 `(hex, landlord)` cohort（`OUTPUT_SHARE` × `GROSS_OUTPUT`）+ 自留（余额归 operator） |
| `household` | ★**实物分成**给劳动者 cohort（`OUTPUT_SHARE` × `LABOR_AMOUNT`）+ 自留（**修订 §六 的纯 `SELF_RETENTION`**，理由见修正 ②） |
| `handicraft` | 实物工资给劳动者 cohort（`OUTPUT_SHARE` × `LABOR_AMOUNT`）+ 自留 + ★货币工资档**只定义不结算**（I5.3） |
| `tenant` | **固定实物租**给资产所有者（`FIXED_IN_KIND_RENT`）+ 自留（佃农家户经营者） |

★ 参数值（地租率 / 给养额）属**判断结果**（`design-creed` 信条十二，spec §十一）⇒ 表里只放**出厂值**，注释写明
「**约束**：Σ 实物规则之和 ≤ 1000‰（分成类）且 `Σ分成率 × 自给率 ≥ 1000‰`（否则 cohort 断粮，见 R5）」——由 GM 按真档观察后调。
★ 未登记的制度 + 缺 `relation` ⇒ **抛**（同 `RegimeOperators` 的 R1 口径，四档列进消息）。

### R9. `ClassKey → CohortKey` **不在本阶段**（上游修正 ⑥ 的处置）

**问题**：合并行键（去掉 `industry` 段）与"产出落 operator"被 breakdown 判为"同一件事的两面"。
**建议**：**暂不合并**，只在受方身份上引入 `CohortKey`。**理由**：① 它是 259 处引用 / 36 文件的破坏性重构，与产出归属**正交**（本阶段的判据 I4.x/I5.x 一条都不需要它）；② 行键的 `industry` 段今天**仍承载信息**（城市格里农村/城镇两个 cohort 的区分，见 R7）；③ 中间态可测性优先（breakdown §一 的立论）。
**错了的代价**：V9（"同一 cohort 只有一个身份"）与 I1.2 本阶段**开不了账**——**如实记为本阶段的未达成项**，落点：行键合并的那一轮（`ClassRow` 收窄前后）。★ 本计划把 `CohortKey` 的 `toString()`/`parse()` 一并建出来（阶段 6 的 receipt 表会用它作键 ⇒ 硬约束"新键类型自带裸 `toString()` + 单参 `parse`"）。

---

## Global Constraints

- **Java 21**；只走 `./mvnw`；注释与提交信息**中文**
- ★★ **验证命令用 `verify` 不是 `package`**；**一次只能跑一个 Maven**（跑前 `pgrep -af "[s]urefirebooter|[c]lassworlds.launcher"`，★ 方括号技巧免自匹配）
- **测试一次一个类**（多类过滤会假绿）；`-pl X -am` 与 `-Dtest=Y` 并用要加 `-Dsurefire.failIfNoSpecifiedTests=false`
- **门禁 fail-closed**：看 `target/surefire-reports/*.txt` 的 **mtime 落在本轮**，★ **不看 rc**
- **不 `git add -A`**；**机械改动逐个 `Edit`**（绝不用 `sed`/脚本批量）
- ★★ **变异体必须打到被测的那一层**：先自问"**若这条规则不存在，这个变异体还会红吗？**"（本仓已**四次**踩到：红在 POM 校验 / 红在 checkstyle / 打在不经 codec 的入口 / **空转变异体绿得骗人**）⇒ **放进变异体后先确认它真的让某条断言红了再算数**
- ★★★ **判别力来自夹具，不来自断言**：凡要测"某值不是被推导出来的"，夹具就必须是**非派生值**（本仓阶段 3 独立实测三次）
- ★★ **RED 要当场捕获**（回放补拍的 RED 时序证据弱）；日志落 `.superpowers/sdd/2026-09-26-s1-stage45-ownership-and-relations/`
- ★★ **不许放宽既有断言**；因本阶段变红的按新口径**手算重算**期望值（**算式写进注释**），**不许抄实际值**
- ★★ **本阶段会大面积改动既有 e2e 的期望值**（分布口径从 `Split(700,300)` 换成关系规则）——这是**预期内**的；处置只有一条：**按 R1/R8 的算式重算**，不许把断言降级成"关系断言"
- ★ **拒绝 placeholder**；代码块里的 Java **逐字照抄就能编译**（中文引号用 `「」`，别在字符串字面量里嵌 ASCII 双引号）
- ★ **不许加旧归档夹具**（spec §十.4 已裁定不做迁移工具）⇒ 本阶段**不写** `ClassRow.goods → Actor 账户` 的迁移；旧档作废
- **关账点**（每道**全仓 `clean verify` 绿**才进下一道）：**A** = T1–T3（行为未变）；**B** = T4–T5（产出去向切换，**必须同批落地**）；**C** = T6–T8

## Review Focus

spec 隐含、但**没有任务显式覆盖**的四类：

1. **`AllocationRule.Split` 的去留**：harvest 不再读它（关系规则接管）⇒ 它成为**死数据**。期望：**本阶段保留**（载荷/编码/往返全不动，只在类注加一句"本阶段起不由结算读取"）——删它是另一件事，且删了会与 `slots`/`allocation` 的载荷契约一起动。★ 但**不许**为"兼容"给它加一条分支。
2. **`FlowRow.income` 的口径变了**：从"毛产分成"改成"实物入账"。期望：读口（GUI/MCP）**不改代码**（它发的是同一个字段），但注释与既有断言按新口径重算；★ 若某条断言的前提是"income 含毛产"，**改断言的口径说明**，不改字段。
3. **同格两个产业同时给同一个 cohort 入账**（如 farm 与 weave 都把布/粮给 `(hex, poor_peasant)`）：期望**累加**（`merge` 而非 `put`），★ 与 `consumedGoods` 的既有教训同款（`EconomySettlement:1122` 的注释）。
4. **`weave@hex|*` 四行的**投入**（纤维 + 织机）本阶段**仍留在行里**：只有**产出**搬家（R5）。期望：`drawCycleInputs`/`transferIntraHexInputs` **一字不改**；★ 别顺手把输入也搬走——那会把 R6 的"Input(actor) ≡ 0"提前打破，且判据里没有任何一条要求它。

## File Structure

| 文件 | 职责 |
|---|---|
| `simos-economy-api/src/main/.../api/cohort/CohortKey.java` | **新建**：受方身份（§2.6）+ 裸 `toString()`/`parse()` |
| `simos-economy-api/src/main/.../api/relation/{RuleType,Basis,Recipient,CompensationRule,ProductionRelation}.java` | **新建**：关系契约（R2/R3） |
| `simos-economy/src/main/.../EconomyData.java` | Modify：第 8 组件 `relations` + 跨表守卫（R3） |
| `simos-economy/src/main/.../model/RegimeRelations.java` | **新建**：缺 `relation` 时的四档推导（形制照 `RegimeOperators`，R8） |
| `simos-economy/src/main/.../spi/EconomyPayloads.java` | Modify：`industries[]` 的可选 `relation` 键 + 缺省推导 |
| `simos-economy/src/main/.../time/ProductionSettlement.java` | **新建**：结算**计算**（纯函数：产出 + 关系 ⇒ 产权条目 + cohort 入账 + 待 S2 的货币规则） |
| `simos-economy/src/main/.../time/ProductionLedger.java` | **新建**：一次关账的发生额（毛产/损耗/投入/产权条目/cohort 入账/货币待办） |
| `simos-economy/src/main/.../time/EconomySettlement.java` | Modify：`harvest` 改为"产出入 ledger + cohort 入账"；`settle` 多日入口 **fail-closed**（R4）；类注重画守恒式 |
| `simos-economy/src/main/.../time/EconomyDayStepper.java` | Modify：`step(day)` 返回当天 `ProductionLedger`（R4） |
| `simos-economy/src/main/.../time/EconomyTimeParticipant.java` | **删除**（R4） |
| `simos-app/src/main/.../time/OwnershipBooks.java` | **新建**：把 `ActorEntry` 落到 `ActorData.accounts`（纯函数 + 非负守卫） |
| `simos-app/src/main/.../time/EconomyOwnershipTimeParticipant.java` | **新建**：economy + actor 的协调器（无 social 的世界） |
| `simos-app/src/main/.../time/PopulationEconomyTimeParticipant.java` | Modify：加第三个模块 `actor` |
| `simos-app/src/main/.../world/EconomySeeder.java` | Modify：四档关系的**出厂参数**（R8）+ 载荷里的 `relation?` |
| 既有夹具/用例（见 T4/T5 的清单） | Modify：推进路径改协调器 + 期望值按 R1/R8 重算 |
| `.superpowers/sdd/2026-09-26-s1-stage45-ownership-and-relations/readings.md` | **新建**：T8 的逐条读数 |

---

### Task 1: 契约（`CohortKey` + `relation` 包）

**Files:** Create 上面 R2 表里的 6 个文件 + 同包测试 `CohortKeyTest`（`simos-economy-api` 是**契约层**：测试只许用 `java.*`/junit/assertj + 本模块类型）

**Interfaces:**
- Produces: `CohortKey(HexCoord residence, SocialClassId stratum)`（`toString()` = `"<q>_<r>|<stratum>"`，`parse` 是它的逆）；
  `RuleType{SELF_RETENTION, OUTPUT_SHARE, FIXED_IN_KIND_PER_LABOR, FIXED_IN_KIND_RENT, FIXED_MONEY_WAGE, FIXED_MONEY_RENT}`；
  `Basis{GROSS_OUTPUT, NET_AFTER_INPUTS, OPERATOR_SURPLUS, LABOR_AMOUNT, ASSET_QUANTITY, FIXED_AMOUNT}`（第 6 档是本计划补的，见 Step 1）；
  `Recipient`（sealed：`ToActor(ActorRef)` / `ToCohort(CohortKey)`）；
  `CompensationRule(RuleType type, Recipient recipient, Basis basis, int ratePerMille, long fixedAmount, Optional<CommodityId> commodity, int priority)`；
  `ProductionRelation(IndustryId activity, ActorRef operator, List<CompensationRule> rules, ActorRef residualOwner)`

- [ ] **Step 1: 写失败的测试**（只写 4 条，全部是**构造期守卫**，因为本任务零公式）

```java
  /** ★★ R9：新键类型必须自带裸 toString() + 单参 parse（阶段 6 的 receipt 表会用它作键）。 */
  @Test
  void cohortKeyRoundTripsThroughItsCanonicalText() {
    CohortKey key = new CohortKey(new HexCoord(3, -2), new SocialClassId("poor_peasant"));
    assertThat(key.toString()).isEqualTo("3_-2|poor_peasant");
    assertThat(CohortKey.parse(key.toString())).isEqualTo(key);
  }

  /** ★★ 货币档只定义字段、不结算（I5.3）：commodity 空 = 货币规则；实物规则必须带 commodity。 */
  @Test
  void moneyRulesCarryNoCommodityAndInKindRulesRequireOne() {
    Recipient rec = new Recipient.ToCohort(new CohortKey(new HexCoord(0, 0), new SocialClassId("poor_peasant")));
    assertThatThrownBy(
            () ->
                new CompensationRule(
                    RuleType.FIXED_MONEY_WAGE, rec, Basis.FIXED_AMOUNT,
                    0, 5_000L, Optional.of(new CommodityId("grain")), 10))
        .as("★ 货币规则带了商品 ⇒ 抛（二选一是类型事实，不许两处都能填）")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new CompensationRule(
                    RuleType.FIXED_IN_KIND_RENT, rec, Basis.FIXED_AMOUNT,
                    0, 5_000L, Optional.empty(), 10))
        .as("★ 实物规则没有商品 ⇒ 抛")
        .isInstanceOf(IllegalArgumentException.class);
  }
```
★ **契约里必须有的第 6 档 `Basis.FIXED_AMOUNT`**（固定额规则的 basis）：spec §2.4 只列了五个 `basis`，
但 `FIXED_IN_KIND_RENT`/`FIXED_MONEY_*` 的"数量从哪来"在那五档里**没有落点**（它们说的是"每单位什么"，而固定额没有单位）
⇒ 与修正 ③ 同款：**spec 的枚举不全，按 R5 的公式表补一档**（这是本任务第一件要定的事，别绕开）。

- [ ] **Step 2: 跑红（当场捕获）**：`./mvnw -pl simos-economy-api -am test -Dtest=CohortKeyTest -Dsurefire.failIfNoSpecifiedTests=false` ⇒ 编译错（类型不存在）⇒ 日志落台账
- [ ] **Step 3: 写实现**（判据：① 五条守卫逐条；② `Recipient` sealed ⇒ "actor 与 cohort 恰其一"是**类型事实**不是运行时检查；③ `rules` 保序不可变 + 逐项非空 + **`priority` 允许重复但必须 ≥ 0**；④ `ratePerMille ∈ [0, 1000]`、`fixedAmount ≥ 0`；⑤ `Optional` 只用于 `commodity`）
- [ ] **Step 4: 跑绿** ⇒ `CohortKeyTest` PASS（以 surefire 报告核对）
- [ ] **Step 5: 变异自证（两条）**：① `parse` 按**最后**一个 `|` 切 ⇒ 往返红；② 去掉"实物规则必须有 commodity"那条守卫 ⇒ 第二条红
- [ ] **Step 6: 提交**（`feat(economy-api): CohortKey 与关系契约（S1 阶段 4+5）`）

---

### Task 2: `EconomyData.relations`（第 8 组件）+ 四档推导

**Files:** Modify `EconomyData.java`（组件/守卫/`withRelations`/跨表守卫）、`EconomyPayloads.java`（`industries[].relation?` + 缺省）；Create `model/RegimeRelations.java`；Test `model/RegimeRelationsTest.java`、`EconomyRoundTripTest`（加一条）、`EconomyInvariantsTest`（加一条）

**Interfaces:**
- Produces: `EconomyData.relations() → Map<IndustryId, ProductionRelation>`；
  `EconomyData.withRelations(Map<IndustryId, ProductionRelation>)`；
  `RegimeRelations.defaultRelation(RegimeId regime, IndustryId industry, ActorRef operator) → ProductionRelation`；
  `RegimeRelations.registered() → Map<String, RuleType>`（**保序**，四档）

- [ ] **Step 1: 改 `EconomyData`**
  - 第 8 组件追加在**末尾**（`allocations` 之后）；`null ⇒ Map.of()`（照包内既有口径）；
  - 跨表守卫（R3）：`relations` 的每个键**必须**在 `industries` 里（`"关系指名的产业不存在"`），且 `relations[k].operator()` 必须 `equals(industries[k].operator())`（**两处拼写必须一致**）；
  - ★ 守卫**不**检查 cohort 侧的行是否存在（同 `ActorData`「表与表之间没有引用完整性约束」的口径：逐组件增量落盘 ⇒ 关系先到、行后到是合法写序）。
- [ ] **Step 2: `RegimeRelations`**（形制**逐字照** `RegimeOperators`：`final class` + 私有构造 + 静态块 + `LinkedHashMap` + `Collections.unmodifiableMap`，★ **绝不用 `Map.of`**——错误消息要列档位）
  - 四档的规则见表 R8；`registered()` 返回档位表；
  - **每条规则的 `recipient`**：劳动者 cohort 用 `ToCohort(new CohortKey(hexOf(industry), stratum))`，其中 `hexOf` 走 `IndustryHexKeys.hexKeyOf`（**唯一拼写点**；拿不到 hex ⇒ 抛，理由：关系必须有地点）；
    ★ 于是"劳动者"在 S1 = **该格的四个阶层 cohort 各一条规则**（与 R7 的解析口径一致：人口为 0 的那些自然解析不到、留在 operator）。
  - ★ 参数（率/固定额）取**出厂值**并注释写明 R8 的约束算式。
- [ ] **Step 3: `EconomyPayloads`**：`industries[]` 可选键 `relation`；缺 ⇒ `RegimeRelations.defaultRelation(regime, id, RegimeOperators.defaultOperator(regime, id))`（**推导只在载荷边缘**，同阶段 3 的 D1）；给了 `relation` 但 `operator` 与产业的不一致 ⇒ **抛**（R3）。
- [ ] **Step 4: 跑受影响的类**（一次一个）：`RegimeRelationsTest`·`EconomyRoundTripTest`·`EconomyInvariantsTest`·`EconomyCodecTest`·`EconomySeedHandlerTest`·`EconomySettlementTest`
- [ ] **Step 5: 变异自证（两条）**：① 跨表守卫放宽成"只查键在不在" ⇒ `EconomyInvariantsTest` 的新用例红；② 缺省推导从"按 regime"改成"取第一条已登记档" ⇒ `EconomySeedHandlerTest` 的逐值断言红
- [ ] **Step 6: 提交**；★ **关账点 A 的前半**（本任务行为不变：harvest 还没改）

---

### Task 3: `ProductionSettlement` —— 结算**计算**（纯函数 + 公式表）

**Files:** Create `simos-economy/src/main/.../time/ProductionSettlement.java` + `ProductionSettlementTest.java`

**Interfaces:**
- Produces:
```java
public final class ProductionSettlement {
  /** 一条产权条目：+ 收 / − 付（毫单位）。账户 = (actor, location)。 */
  public record ActorEntry(ActorRef actor, HexCoord location, CommodityId commodity, long delta) {}
  /** 一次关账的结算结果：产权条目（**按落账次序**）+ cohort 入账 + 待 S2 的货币规则（I5.3）。 */
  public record Outcome(
      List<ActorEntry> actorEntries,
      Map<CohortKey, Map<CommodityId, Long>> cohortIntake,
      List<CompensationRule> deferredMoney) {}
  /** 结算的事实输入：**全部来自本周期**（付款上限见 R6 ⇒ 不需要任何历史余额）。 */
  public record Facts(
      HexCoord location,
      Map<CommodityId, Long> gross,
      Map<CommodityId, Long> net,
      Map<CommodityId, Long> inputs,
      Map<CohortKey, Long> laborOfCohort,
      Map<ActorRef, Long> assetOfActor) {}
  public static Outcome settle(ProductionRelation relation, Facts facts) { ... }
}
```
- [ ] **Step 1: 写失败的测试**（★ 期望值全部**手算**、算式写进注释；★ 夹具用**非派生值**）
  - **I5.2**：同一份产出，`OUTPUT_SHARE × GROSS_OUTPUT 30%` 与 `OUTPUT_SHARE × NET_AFTER_INPUTS 30%` 的**实得数不同** —— 恰好差 `30% × 损耗`（夹具取 `gross=100_000`、`loss=3_000` ⇒ 两数分别 `30_000` 与 `29_100`）。
  - **I5.3**：`FIXED_MONEY_RENT` ⇒ `actorEntries` 与 `cohortIntake` **都空**，且 `deferredMoney` **含它**（并断言消息口径"待 S2"由读口/用例读出）。
  - **I5.4**：两条规则**只交换 `priority` 值**（数据改动、无代码分支）⇒ 结果不同（`OPERATOR_SURPLUS` 规则排在前/后，实得数不同）。
  - **R6**：规则要得比产出多 ⇒ 实付 = 产出（**不抛、不造账**），且 `Σ付款 ≤ 净产`。
  - **R7**：`cohortIntake` 的键是 `CohortKey`（不是行键）；`laborOfCohort` 里没有的 cohort ⇒ 该条归零、不产生条目。
- [ ] **Step 2: 跑红（当场捕获）**
- [ ] **Step 3: 写实现**（公式表 = 判据，逐条兑现）：

| 规则 × basis | 数量（毫单位，向下取整） |
|---|---|
| `SELF_RETENTION` | **0**（不动；余额归 `residualOwner`） |
| `OUTPUT_SHARE` × `GROSS_OUTPUT` | `gross_j × rate ÷ 1000` |
| `OUTPUT_SHARE` × `NET_AFTER_INPUTS` | `net_j × rate ÷ 1000` |
| `OUTPUT_SHARE` × `OPERATOR_SURPLUS` | `(net_j − 已付_j) × rate ÷ 1000`（**已付按 priority 序累计** ⇒ 次序是数据） |
| `OUTPUT_SHARE` × `LABOR_AMOUNT` | `net_j × rate ÷ 1000 × 本受方劳动 ÷ Σ劳动`（Σ 取 `laborOfCohort` 全体） |
| `OUTPUT_SHARE` × `ASSET_QUANTITY` | `net_j × rate ÷ 1000 × 本受方资产量 ÷ Σ资产量` |
| `FIXED_IN_KIND_PER_LABOR`（basis `LABOR_AMOUNT`） | `⌊本受方劳动 ÷ 1000⌋ × fixedAmount` |
| `FIXED_IN_KIND_RENT`（basis `FIXED_AMOUNT`） | `fixedAmount`（每周期一笔） |
| `FIXED_MONEY_*` | **不产生条目**，进 `deferredMoney`（I5.3） |

  - ★ 次序：**按 `priority` 升序、同值按规则在 `rules` 里的序**（保序、可复现）；
  - ★ **付款上限**（R6）：每条实付 = `min(应付, 本周期该商品剩余可用)`，可用 = 自己收到的产出（`+net`）− 已付；
  - ★ cohort 受方与 actor 受方**共用同一套数量公式**，差别只在**落到哪里**（cohort 进 `cohortIntake`、actor 进 `actorEntries`）；
  - ★ **货币规则不许**进 `actorEntries`（I5.3 的判别力就在这一条）。
- [ ] **Step 4: 跑绿** ⇒ `ProductionSettlementTest` PASS
- [ ] **Step 5: 变异自证（三条，逐条当场捕获 RED）**：① 把 `priority` 排序换成"表序"且夹具的两条规则**表序 ≠ priority 序** ⇒ I5.4 红；② `OPERATOR_SURPLUS` 的"已付"改成 0 ⇒ 该用例红；③ 货币规则照常产生条目 ⇒ I5.3 红（★ **若第 ③ 条不红，说明断言只看"返回空"而没看"确实没条目"——改断言，别改实现**）
- [ ] **Step 6: 提交**；★ **关账点 A**：`./mvnw clean verify` 全绿（T1–T3 行为未变）

---

### Task 4: `harvest` 切换 + **过渡入账** + `ProductionLedger`（economy）

**Files:** Modify `EconomySettlement.java`（`harvest` + `settle` 的 fail-closed + 类注重画守恒式）、`EconomyDayStepper.java`（`step` 返回 ledger）；Create `time/ProductionLedger.java`；Delete `time/EconomyTimeParticipant.java`；Test `EconomySettlementTest`/`EconomyCycleBoundaryTest`/`EconomyFlowCycleTest`/`EconomySowingTest`/`EconomyDebtTest`/`EconomyLaborAllocationTest` 的**推进写法**（见 Step 5 配方）

**Interfaces:**
- Produces: `EconomyDayStepper.step(long day) → ProductionLedger`；
  `ProductionLedger(Map<IndustryId, Map<CommodityId, Long>> gross, Map<...> losses, Map<...> inputs, List<ActorEntry> actorEntries, Map<CohortKey, Map<CommodityId, Long>> cohortIntake, List<CompensationRule> deferredMoney)`

- [ ] **Step 1: 写失败的测试**（economy 侧，两个面）
  - **过渡面（R5）**：一个只有 agriculture + 一条显式 `relation`（给养给 `(hex, poor_peasant)`）的世界，推满一个周期 ⇒ 断言 **`farm@hex|poor_peasant` 的粮库存 = 期初 + 实付（手算）**，且 `ledger.actorEntries` 里恰好有一条 `+净产 → operator`（手算量）—— ★ 这一条**就是**"cohort 不断粮"的守门用例。
  - **fail-closed（R4）**：`EconomySettlement.settle(base, 0, cycleDays)` ⇒ **抛**（消息含"产权落账口"）。
- [ ] **Step 2: 跑红（当场捕获）**
- [ ] **Step 3: 改 `harvest`**：
  - 毛产/损耗/净产的算式**一字不改**；`netParts`/`lossParts` 两处分配**删除**（不再分给行）；
  - 每个商品：`+net → operator`（`ProducerEntry` 一条，`location` = `hexKeyOf(industry.id())`）；`loss` 只进 `ledger.losses`；
  - 调 `ProductionSettlement.settle(relation, facts)` ⇒ `actorEntries` 追加进 ledger、`cohortIntake` **就地落到消费行**（R7 的解析：`population > 0` + 按人口比例；解析不到 ⇒ 留在 operator）、`deferredMoney` 进 ledger；
  - ★ `FlowRow.income` 改成记**实物入账**（cohort 入账那一笔；★ **merge 不 put**，理由同 `consumedGoods`）；
  - ★ `productionLoss` 那一族累加器**删除或改挂 ledger**（判据：不许留下"分了损耗但没人收"的中间残留）。
- [ ] **Step 4: `settle` fail-closed**（R4）+ `EconomyDayStepper.step` 返回 ledger + 删 `EconomyTimeParticipant`（连带其用例改注册新协调器——**但本任务先到不了那一步**，见 Step 5 的顺序）
- [ ] **Step 5: 既有用例的**迁移配方**（机械，逐个 `Edit`）**
  ```
  EconomySettlement.settle(base, 0, N)   →   EconomyDayStepper stepper = new EconomyDayStepper(base);
                                             for (long d = 1; d <= N; d++) { stepper.step(d); }
                                             EconomyData after = stepper.finish();
  ```
  ★ 需要"产出侧事实"的用例：把 `stepper.step(d)` 的返回值攒起来（`ledger`），**不要**再算一遍公式。
- [ ] **Step 6: 跑绿**（一次一个类）⇒ 报告 mtime 落本轮；★ 期望值按 R1/R8 重算（算式写进注释）
- [ ] **Step 7: 变异自证（两条）**：① 删掉"cohort 入账"那一步（产出只进 ledger）⇒ Step 1 第一条红（**这就是"断粮"的可执行证据**）；② `settle` 的 fail-closed 去掉 ⇒ 第二条红
- [ ] **Step 8: 提交**（★ 与 T5 属**同一批**：单独提交也可，但**不许单独进关账点 B**）

---

### Task 5: ★ 协调器（app）：产权落账 + 两个参与者的接线

**Files:** Create `simos-app/src/main/.../time/OwnershipBooks.java`、`.../time/EconomyOwnershipTimeParticipant.java`；Modify `.../time/PopulationEconomyTimeParticipant.java`；Modify 夹具：`EconomyTestWorld.genesis()`（加 `actor` 快照）、`PopulationEconomyFixture`（加 `actor`）、`WorldgenInitializeToolTest`/`EconomySettlementEndToEndTest` 的参与者注册；Test `OwnershipBooksTest`（新建）、`ActorEconomyOwnershipTest`（新建，端到端）

**Interfaces:**
- Produces: `OwnershipBooks.apply(ActorData base, HexCoord fallbackLocation, List<ActorEntry> entries) → ActorData`；
  `EconomyOwnershipTimeParticipant(String mapId)`（namespace = `"ownership"`，`moduleChanges` = `{economy, actor}`）

- [ ] **Step 1: `OwnershipBooks`（纯函数）**
  - 逐条：`balances[commodity] += delta`；账户键 = `new GoodsAccountKey(entry.actor(), entry.location())`（**键从值派生**，走 `ActorData.withAccount`，**不自己拼键**）；
  - ★ **余额不得为负**（`GoodsAccount` 的构造期守卫兜底）⇒ 协调器**再判一层**并抛（消息含 actor/商品/余额）；
  - ★ 余额**只在被写的商品上覆盖**（其余商品原样带过）⇒ 两张表不互相抹。
- [ ] **Step 2: `EconomyOwnershipTimeParticipant`**：日循环 = `EconomyDayStepper.step(d)` ⇒ `books = OwnershipBooks.apply(books, hex, ledger.actorEntries())`；`finish()` 后交 `{economy: EconomyChangeSet.between(...), actor: ActorChangeSet.between(...)}`；读写集含 `actor:<mapId>:account.<key>`（照 `ActorResolver` 的地址形制）
- [ ] **Step 3: `PopulationEconomyTimeParticipant` 加第三片**：`actorOf(state)`（**缺席 ⇒ 抛**，同 `economyOf` 的既有口径）；日循环里同一处 apply；提案的 `moduleChanges` 加 `ACTOR`
- [ ] **Step 4: 接线与夹具**：① 两处注册点换成新参与者（★ 两者**从不同时注册**）；② `EconomyTestWorld.genesis()` 与 `PopulationEconomyFixture` 的 `modules` 各加一条 `actor` 快照（`ActorData.empty()`）；③ `Shell` 不变（已注册 `PopulationEconomyTimeParticipant`）
- [ ] **Step 5: 跑红→跑绿**（端到端）：先写 `ActorEconomyOwnershipTest` 的断言（一个真播种器世界推满一个周期）：
  ```java
    assertThat(afterActor.accounts().get(new GoodsAccountKey(ESTATE, HEX)).balances())
        .as("★★ I4.1：净产落 operator 的账；付出去的那笔已扣（算式：净产 − 实付）")
        .containsEntry(GRAIN, netMinusPaid);
    assertThat(rows.income()).as("★ 行侧的实得 = cohort 入账（不是毛产分成）").containsEntry(GRAIN, intake);
  ```
  ⇒ **红**（还没接线）⇒ 接线 ⇒ **绿**
- [ ] **Step 6: 变异自证（三条）**：① `OwnershipBooks` 不落"产出入 operator"那条 ⇒ 断言红；② 参与者的 `moduleChanges` 去掉 `actor` ⇒ 断言红（★ **这一条必须试**：它证明"产权账真的过线"）；③ `ActorEntry` 的 `location` 换成 `(0,0)` 常量 ⇒ 非零格的世界红
- [ ] **Step 7: 提交**；★★ **关账点 B**：`./mvnw clean verify` 全绿（T4+T5 同批；既有 e2e 期望值已按新口径重算）

---

### Task 6: I4.3 —— `weave` 不再作为产权主体持有布（+ 「过渡分配承重」的变异）

**Files:** Create `simos-app/src/test/java/io/mosire/simos/app/world/S1Stage45WeaveOwnershipTest.java`；Modify `EconomyRealScaleClothTest`（读口口径）

**Interfaces:** Consumes `EconomySeeder`（真载荷）+ `EconomySeedHandler` + `PopulationEconomyFixture` 的推进法（真协调器）

- [ ] **Step 1: 写测试**（真播种器世界，推满一个周期后逐值断言）
  ```java
    assertThat(clothOfAllRowsOf(after, WEAVE, hex))
        .as("★★ I4.3：weave 四行**一行都不持有布**（人口为 0 ⇒ 永不是 cohort 受方，R7）")
        .containsOnly(0L);
    assertThat(accountOf(afterActor, new ActorRef(ActorKind.HOUSEHOLD, "weave@0_0"), hex, CLOTH))
        .as("★ 产出落 operator 的账（布在这里，不在 weave 行里）")
        .isPositive();
    assertThat(clothOfRowsOf(after, FARM, hex))
        .as("★★ V3 的机制面：织布的人（farm 行的 cohort）拿到了布 —— 阶段 6 的 I6.3 读的就是这个")
        .anyMatch(v -> v > 0L);
  ```
- [ ] **Step 2: 跑红（当场捕获）**：在 T4/T5 已落地的树上，这三条**应当直接绿** ⇒ **RED 由 Step 3 的变异体当场补**，如实记「本用例的 RED 来自变异体、不是实现缺口」（**不许**把补拍写成"先红后绿"，同阶段 3 Task 5 Step 2）
- [ ] **Step 3: 变异自证（两条，各打一条判据）**：① 把 cohort 解析里的 `population > 0` 去掉 ⇒ 第一条红（布又落回 weave 行）；② 把 cohort 解析换成"活动自己的行" ⇒ 第一条**与**第三条同时红
- [ ] **Step 4: 「过渡分配承重」的证据**：临时把 cohort 入账改成"零入账"（产出只进 ledger）⇒ **既有 e2e 的缺口/饿死用例当场红**（点名哪几条）⇒ 还原 ⇒ 复绿；★ 这条证明"过渡分配"是承重的，不是装饰
- [ ] **Step 5: 提交**

---

### Task 7: I5.1 / I5.2 —— 制度可分辨（端到端）

**Files:** Create `simos-app/src/test/java/io/mosire/simos/app/world/S1Stage45RelationsTest.java`（或落 economy 侧，若夹具不需要 actor —— **判据：能用 `EconomyDayStepper` 就地读 ledger 就不进 app**）

- [ ] **Step 1: I5.1 —— 同格同产能、佃制 ≠ 庄园制**
  ```java
    assertThat(distributionOf(TENANT_HEX)).as("★★ spec §七 V2：same regime + different relation → different result")
        .isNotEqualTo(distributionOf(FEUDAL_HEX));
    assertThat(tenantRow).as("★ 佃制：佃农家户（operator）自留，地主 cohort 拿固定实物租（手算值）").containsEntry(GRAIN, rent);
  ```
  ★ 两个世界的**人口/土地/配方/operator 完全一致**，**只有 `relation` 不同** ⇒ 差异只可能来自关系（判别力来自夹具）
- [ ] **Step 2: I5.2 —— `basis` 有区别**：同一格跑两遍（只换 basis，产出相同）⇒ 实得数差 = `30% × 损耗`（**手算写进注释**）
- [ ] **Step 3: RED 的处置**：同 T6 Step 2（预期直接绿 ⇒ RED 来自变异体，当场补）
- [ ] **Step 4: 变异自证（两条）**：① `NET_AFTER_INPUTS` 与 `GROSS_OUTPUT` 走同一支 ⇒ I5.2 红；② `RegimeRelations` 表里 `TENANT` 与 `FEUDAL` 指向同一组规则 ⇒ I5.1 红
- [ ] **Step 5: 提交**

---

### Task 8: 逐条验收 + 关账（I4.1 / I4.2 / I4.3 / I5.1–I5.4）

**Files:** Create `.superpowers/sdd/2026-09-26-s1-stage45-ownership-and-relations/readings.md`；**无**生产/测试改动（发现缺口 ⇒ 回对应 Task，**不许就地补**）

- [ ] **Step 1: 收尾门禁（前台）**：`./mvnw clean verify` ⇒ **BUILD SUCCESS**（全模块；SpotBugs `BugInstance size is 0`；**全部 surefire 报告 mtime 落在本轮**）
- [ ] **Step 2: 逐条判据读数（写进 `readings.md`）**

| 判据 | 怎么读 | 期望 |
|---|---|---|
| **I4.1** | `ActorEconomyOwnershipTest`：`ΔActorGoods == 净产 − 实付`（逐 actor、逐商品） | 手算逐值 |
| **I4.1**（过渡口径） | 同上用例注释：`Input(actor) ≡ 0`（投入仍从消费行扣） | 口径写明 |
| **I4.2** | T5/T6 的世界：`ΔΣRow + ΔΣActor + Σ消费 + Σ损 = Σ毛产 − Σ投入`（逐商品，右式独立手算） | == 0 |
| **I4.3** | `S1Stage45WeaveOwnershipTest`：`weave@hex|*` 的布逐行 0 + operator 账上有布 | 逐值 |
| **I5.1** | `S1Stage45RelationsTest`：同格同产能、佃 ≠ 庄园 | 不等 + 逐值 |
| **I5.2** | 同上：`GROSS 30%` vs `NET_AFTER_INPUTS 30%` 差 = `30% × 损耗` | 逐值 |
| **I5.3** | `ProductionSettlementTest`：货币规则零条目 + `deferredMoney` 含它 | 逐值 |
| **I5.4** | 同上：只换 `priority` 值 ⇒ 结果不同 | 不等 |
| **承重**（4↔5 合并的理由） | T6 Step 4 的变异：cohort 入账置零 ⇒ 既有缺口/饿死用例红 | 点名哪几条 |
- [ ] **Step 3: 增量性核对**：`git diff <阶段4起点 sha>..HEAD --stat -- simos-economy/src/main simos-economy-api/src/main simos-app/src/main` ⇒ 与 File Structure 表**逐条对上**；多出来的 ⇒ 停下来核
- [ ] **Step 4: 未达成项如实记**（写进 `readings.md`）：**V9 / I1.2**（行键未合并，R9）；**V4 / I6.1**（cohort 侧仍有 goods 字段，阶段 6/7）；**V8 / I6.2**（无上限规则，R5）；**`AssetHolding` 未在真档种入**（`ASSET_QUANTITY` 只有夹具覆盖）
- [ ] **Step 5: 文档回填**：`AGENT.md` **不改**（不新增模块、不改依赖方向）；★ **证据块自指**：把"本轮跑的是哪份字节（md5）"追加进台账；本计划对 spec §五/§六 的两处修正按 `AGENT.md` §五.4 **追加标注**（不篡改 spec 原文）
- [ ] **Step 6: 提交**

---

## Self-Review

**Spec coverage**（对照 spec §2.1 / §2.4 / §2.5 / §三 / §四 / §五 / §六 / §七）：

| Spec 要求 | 落点 |
|---|---|
| §四 ①→⑤ 结算流程 | T3（计算）+ T4（行侧/ledger）+ T5（产权侧） |
| §2.4 规则类型 / `basis` 五种 / `priority` | T1（契约）+ T3（公式表）+ T7（I5.1/I5.2） |
| §2.4 货币规则**只定义不结算** | T1（`Optional<CommodityId>`）+ T3（`deferredMoney`，I5.3） |
| §2.5 实物报酬是**流量**、上限规则 | ★ **上限规则属阶段 6**（R5，如实记）；本阶段的过渡形态是"实得即入账" |
| §五 两条守恒式 | ★ **R1 重画**（过渡期必须带 `ΔΣRowGoods`） |
| §六 四档默认 + 租佃档 | T2（`RegimeRelations`）；★ `household` 一格按 V3 修订（R8） |
| §七 V1/V2/V5/V6/V9 | T7（V2/V5）+ T3（V6）+ T2（V1 的载体：关系不丢）；★ V9 未达成（R9） |

**Placeholder scan：** 无 TBD/TODO。★ 三处"红从哪来"**已如实处理**：T6 Step 2、T7 Step 3（预期直接绿 ⇒ RED 来自变异体，当场捕获、不许回放补拍）。
★ **参数值**（地租率/给养额）在 T2 以**具名出厂值 + 约束算式**出现，不当成"已定的判断结果"（信条十二）。

**Type consistency：** `CohortKey`/`CompensationRule`/`ProductionRelation` 在 T1 定义，T2/T3/T4/T7 消费；
`ActorEntry`/`Outcome`/`Facts` 在 T3 定义，T4（ledger）/T5（`OwnershipBooks`）消费；`ProductionLedger` 在 T4 定义、T5 消费；
`EconomyDayStepper.step` 的返回类型 T4 改、T5 消费（既有调用方忽略返回值 ⇒ 源兼容）。

**Review Focus coverage：** ① → 不删 `AllocationRule.Split`（T4 只停读）；② → T4 Step 3 的 `FlowRow.income` 口径 + T8 Step 3 的核对；③ → T4 Step 3 的 `merge` 硬要求；④ → T4 不改 `drawCycleInputs`/`transferIntraHexInputs`。

## 本计划的验证边界

- **未跑 Maven**：所有 `Expected` 都是**预测**（`AGENT.md` §9.2：与实际不符 ⇒ 先核算式、先查被测物，**不许把 Expected 改成实际值**）。
- **未清点**：`settle(...)` 的直接调用点与"受影响既有用例"的**完整清单**（只按 `grep` 抽样：`EconomyRealScale{Cloth,SeedBottleneck}Test`、`EconomyCycleBoundaryTest`、`WorldgenInitializeToolTest`、`EconomySettlementEndToEndTest`、`PopulationR4Test`、`PopulationEconomyFixture` 等）⇒ 执行时以**编译错 + 红**为准逐个过。
- **未定**：T2 的**出厂参数值**只给了约束算式，真值要按真档观察后定（信条十二）。
- **未做**：`ClassKey → CohortKey` 行键合并（R9）；§2.5 的超额上限与 `ConsumptionReceipt`（阶段 6）；`ClassRow` 收窄（阶段 7）；`AssetHolding` 的真档种入。
- **未验证**：R7 的"城市格里农村/城镇同阶层合成一个 cohort"在真档（有城市的格）上的**数值后果**——只有 T6/T7 的夹具覆盖了"同格两产业"的形态，真档 600 天的读数属阶段 6 的 I6.4。

---

## ★★ 控制方裁定（2026-09-26，计划交付后 / 派活前）—— **执行者照这个做**

计划撰写者提出 7 处上游不符/待拍板。**逐条裁定**（都能从 spec + 代数 + 本仓口径推出，故不上升给用户）：

### E1：守恒式**必须带 `ΔΣRowGoods` 项**（计划 R1 通过）

`ΔΣRowGoods + ΔΣActorGoods + ΣFinalConsumption + ΣLoss = ΣOutput − ΣProductionInputs`。
★ spec §五 那条是它在 `ΔΣRowGoods ≡ 0`（阶段 6/7 终态）时的**特例**。
—— **依据**：过渡期 cohort 有库存、且 `ClassRow` 仍持有实物 ⇒ 不带该项**等式恒不成立**，
判据会**从第一天就假绿**。**这是代数问题，不是口味问题。**

### E2：spec §六 `household → SELF_RETENTION` **改成「实物分成给劳动者 + 自留」**（计划 ② 通过）

—— **依据**：spec §七 V3 要「**织布的人拿到布**」，而 operator 是 **actor**、劳动者是 **cohort**
⇒ `SELF_RETENTION` 会让布留在 actor 账上，**V3 与 I6.3 永远开不了账**。这是 **spec 内部矛盾**，取 V3 那一支。
—— **代价**：`household` 档的自留比例不再是纯自留 —— 记为**对 spec §六 的一处修正**（本计划的裁定优先）。

### E3：契约（`CohortKey` + `relation` 包）落 **`economy-api`**，不落 `simos-actor-api`（计划 ③ 通过）

—— **依据**：`simos-actor-api` 的**主依赖为零**（阶段 2 的 R-e/R-h 把 `simos-map`/`simos-util` 都删了）
⇒ 它**装不下**要用 `CommodityId`/`HexCoord` 的类型，硬放会成 `actor-api → economy-api → actor-api` **循环**。
—— **代价**：与 spec §三 的措辞不符 —— 记为**对 spec §三 的一处修正**。

### E4：**单列表 + `priority`**（计划 ④ 通过）；租**显式给 `(hex, landlord)` cohort**

—— **依据**：spec §2.4 的 labor/asset **两个规则列表**是**第二拼写点**；
且它没写「资产所有者的租归谁」⇒ 不显式给，**地主 cohort 的粮源会凭空消失**。
—— **代价**：低（一档数据的归属写清楚）。

### E5：`Basis` **补第 6 档 `FIXED_AMOUNT`**（计划 ⑤ 通过）

—— **依据**：I5.3 要 `FIXED_MONEY_*`；spec 的五档**没有固定额规则的落点**。
—— **代价**：低（一档枚举）。

### E6：★ **`ClassKey → CohortKey` 本阶段不做**（计划 ⑥ 通过）—— **但记为对既有计划的第二次推迟**

—— **依据**：阶段 1 把 `ClassKey → CohortKey` 并入阶段 4 的**理由是**「去掉 industry 后
`weave@hex|*` 四个行就不存在了，而它们正是布的唯一落点」。★ 而本计划的 **R7 已证明这个理由不再成立**：
那四行 `population` **恒为 0** ⇒ **永不是 cohort 受方** ⇒ 布本来就会落到 `farm@hex|<阶层>`（真正织布的人）。
⇒ **"布的唯一落点"问题被 R7 正面解决，不再需要动键的形状。**
—— **代价**：**V9 / I1.2 本阶段开不了账**（如实记为**未达成项**）。★ 这是它**第二次**被推迟
（阶段 1 → 阶段 4 → 现在）⇒ **必须写进台账与最终报告**，不许静默。
—— **落点**：阶段 6/7（`ClassRow` 收窄时一并看）。

### E7：★ **完整账的路径唯一化为 app 协调器**（计划 R4 通过）

`EconomySettlement.settle`（多日入口）**fail-closed**、**删除** `EconomyTimeParticipant`。
—— **依据**：否则产出会**在账上静默消失** —— 正是本仓最反对的形态。
—— **代价**：调用面收窄，旧的独立入口没了 —— 记为**有意的收窄**。
