# Task 3 报告：`ProductionSettlement` 纯函数（公式表 + `priority` 序 + E14 守卫）

计划：`docs/superpowers/plans/2026-09-26-s1-stage45-ownership-and-relations.md` §Task 3
brief：同目录 `task-3-brief.md`　★ 关账点 **A**（行为必须未变）

**结论**：`ProductionSettlement`（470 行）+ `ProductionSettlementTest`（508 行 / 16 个用例）落地；
**6 条变异体**逐条当场红、逐条 `md5sum -c` 证明还原到最终字节；全仓 `./mvnw clean verify` **BUILD SUCCESS**
（关账点 A：**13 个模块全绿 / 2,513 个测试 0 失败** —— 本任务**只新增文件、零行为改动**）。
★ 全仓门禁**两次真实命中**并处置（spotless 折行、SpotBugs `EI_EXPOSE_REP` ×7）—— 见 §十。

---

## 一、实现了什么 + ★ 公式表（逐档一条式子）

`ProductionSettlement.settle(ProductionRelation, Facts) → Outcome`：**纯函数**，无 IO、无状态、**无历史余额**。

| 规则 | `basis` | 数量（毫单位、整数、向下取整） | 实现 |
|---|---|---|---|
| `SELF_RETENTION` | （**不读**） | `0`（不动；余额归 `residualOwner`） | `dueAmount` 的 `SELF_RETENTION` 支 |
| `OUTPUT_SHARE` | `GROSS_OUTPUT` | `gross_j × rate ÷ 1000` | `shareOf` |
| `OUTPUT_SHARE` | `NET_AFTER_INPUTS` | `net_j × rate ÷ 1000` | 同上 |
| `OUTPUT_SHARE` | `OPERATOR_SURPLUS` | `(net_j − 已付_j) × rate ÷ 1000`（★ 已付按 priority 序累计 ⇒ 次序是数据） | 同上 |
| `OUTPUT_SHARE` | `LABOR_AMOUNT` | `net_j × rate ÷ 1000 × 本受方劳动 ÷ Σ劳动`（Σ 取 `laborOfCohort` **全体**） | 同上 + `shareWithTotal` |
| `OUTPUT_SHARE` | `ASSET_QUANTITY` | `net_j × rate ÷ 1000 × 本受方资产量 ÷ Σ资产量`（Σ 取 `assetOfActor` **全体**） | 同上 |
| `FIXED_IN_KIND_PER_LABOR` | `LABOR_AMOUNT` | `⌊本受方劳动 ÷ 1000⌋ × fixedAmount`（**不除以** Σ） | `perLabor` |
| `FIXED_IN_KIND_RENT` | `FIXED_AMOUNT` | `fixedAmount`（每周期一笔，与产出/劳动/资产都无关） | `fixedRent` |
| `FIXED_MONEY_WAGE` / `FIXED_MONEY_RENT` | （**不读**） | **不产生任何条目**，进 `deferredMoney`（I5.3） | `dueAmount` 的货币支 → `OptionalLong.empty()` |

其余三条口径（判据的"半句"）：

- **次序** = 按 `priority` 升序、**同值按规则在 `rules` 里的先后**（`List.sort` 是稳定排序 ⇒ 同值天然保表序）；
- **付款上限（R6）** = 每条实付 `min(应付, 可用)`，可用 = `net_j − 已付_j`；实付 ≤ 0 的那条**不产生任何条目**
  （自留的 0 / 付不出的 0 / 受方不在账里的 0 都走这一支）；
- **未登记的 `(type × basis)` 组合** ⇒ 抛（E11：组合守卫的落点就是这张表）。★ 两类组合**不受此判**：
  货币档（不结算）与 `SELF_RETENTION`（数量恒 0）—— 它们**不读 basis**，而"没读的字段不判"。

**三条落点**（"数量公式两族共用，差别只在落到哪里"）：

| 受方 | 落到哪 |
|---|---|
| **付方**（恒 = `relation.operator()`、恒落 `facts.location()`） | 每条实付一条 **转出条目** `−paid`（★ **cohort 受方也照落**：少了它，cohort 入了账而 operator 没扣 ⇒ 同一份产出在账上多一份） |
| `Recipient.ToActor` | 再一条 **收入条目** `+paid`（同格）⇒ 产权侧仍是双分录 |
| `Recipient.ToCohort` | 进 `cohortIntake`（键 = `CohortKey`，**不是行键**；同键 **merge 累加**） |

---

## 二、★★ I5.2 的逐值证据（`GROSS 30%` vs `NET 30%`）

夹具（全字面量）：`gross = 100_000`、`net = 97_000`（**损耗 3_000**）。
两个 `settle` 用**同一份 `Facts`**，只有规则的 `basis` 不同：

| 跑法 | 算式 | 实得 |
|---|---|---|
| `OUTPUT_SHARE × GROSS_OUTPUT`，`rate = 300‰` | `100,000 × 300 ÷ 1000` | **30,000** |
| `OUTPUT_SHARE × NET_AFTER_INPUTS`，`rate = 300‰` | `97,000 × 300 ÷ 1000` | **29,100** |
| 两数之差 | `30,000 − 29,100` | **900** |

**900 = 损耗 × 30%**：`3,000 × 300 ÷ 1000 = 900` —— 即"**差恰好是损耗的那一份分成**"（判据原文）。
断言：两个逐值 + 那个差（`isEqualTo(900L)`）+ `cohortIntake` 两两 `isNotEqualTo`（同一份产出、实得数不同）。

★ 判别力：夹具**刻意让 `gross ≠ net`**。若夹具取 `gross == net`，两档走同一支也全绿（M5 实测见 §九）。

---

## 三、★ I5.3 的证据（`FIXED_MONEY_*` 转移数 = 0 + 怎么报"待 S2"）

夹具（★ 刻意让产出**非零**：`gross = 100_000` / `net = 97_000`，否则"不产生条目"会因为"本来就没东西可分"而假绿）：

- `FIXED_MONEY_RENT`（20,000,000 毫钱 → `(hex, landlord)`，`priority = 20`，表序 **index 0**）
- `FIXED_MONEY_WAGE`（1,000 毫钱 → 作坊 actor，`priority = 10`，表序 **index 1**）

| 读数 | 值 |
|---|---|
| `actorEntries` | **空**（转移数 = **0**） |
| `cohortIntake` | **空** |
| `deferredMoney` | **含两条**，且**按付款次序** = `[工资(10), 租(20)]`（★ 与表序**相反** ⇒ 待办清单也跟着数据走） |

**"待 S2"怎么报出来的**（★ 不静默）：`Outcome.deferredMoney` 只是"**哪些规则被推迟了**"，
而**那句话的文本**由读口 `ProductionSettlement.deferredMoneyReason(rule)` 给出：

```
货币规则只定义、不结算（待 S2）：FIXED_MONEY_WAGE → ToActor[actor=WORKSHOP:craft@0_0]
每周期 1000 毫钱（S1 没有 ledger ⇒ 不假装货币结算成功）
```

★ 用例断言 `.contains("待 S2", "FIXED_MONEY_WAGE")`；★ 「待 S2」这句口径的**唯一拼写点**在该方法里
（别处再写一遍 = 同一个格式的第二处拼写点，本仓硬规矩）。★ 非货币档调它 ⇒ 抛（那句话对它不成立）。

---

## 四、★★ I5.4 的证据（次序是数据 + **为什么这个夹具是非派生的**）

夹具（**全部字面量**）：`gross = net = 100_000`；两条规则：

- **A** = `OUTPUT_SHARE × OPERATOR_SURPLUS`，`500‰` → 贫农 cohort（**读"已付"**的那一档）
- **B** = `OUTPUT_SHARE × NET_AFTER_INPUTS`，`500‰` → 地主 cohort（**不读"已付"**）

**两张规则表一字不动**（A 恒在 index 0、B 恒在 index 1），**只对调两个 `priority` 整数**：

| 跑法 | `priority` | 付款次序 | A（贫农） | B（地主） |
|---|---|---|---|---|
| ① | A=10, B=20 | `[A, B]` | `(100,000 − 0) × 500 ÷ 1000` = **50,000** | `100,000 × 500 ÷ 1000` = **50,000** |
| ② | A=20, B=10 | `[B, A]` | `(100,000 − 50,000) × 500 ÷ 1000` = **25,000** | **50,000** |

⇒ 同一条规则、同一份产出，实得 **50,000 → 25,000**（**只有两个整数变了**，代码一个分支都没动）。

**★★ 为什么这个夹具是"非派生值"（本仓硬规矩 1 的落点）**：

1. **表序 ≠ priority 序**（跑法 ② 里 priority 序是 `[B, A]`，表序是 `[A, B]`）。这正是判据要的东西：
   「把排序换成表序」这种实现（M1）会让跑法 ② 得到 **50,000** ⇒ 断言当场红。
   ★ 若夹具让两者一致（比如两条规则的表序恰好就是 priority 序），表序实现**照样绿** ⇒ 假判别力。
2. **数值不是从被测物推出来的**：`100_000` / `500` / `10` / `20` 全是字面量，期望值 `50_000` / `25_000`
   是**手算**（算式写在 §四上表与用例注释里），**没有一处**调用被测函数再读一遍。
3. **两条规则的 `basis` 刻意不同**（一条读"已付"、一条不读）：若两条都读"已付"，次序仍会影响结果，但
   "那条不读已付的也不受次序影响"这个**对照**就没了 —— 有了它，"影响来自已付累计"这件事被**双向钉住**。

---

## 五、★★ E14 的守卫（抛在哪、消息是什么、测试与变异证据）

**落点**：`ProductionSettlement.requireProducibleCommodities(...)` —— 在 `settle` 里、**任何数量计算之前**
（`ordered = inPaymentOrder(...)` 之后、付款循环之前）调用 ⇒ 与"本期产了多少"无关：

```java
    List<CompensationRule> ordered = inPaymentOrder(relation.rules());
    requireProducibleCommodities(ordered, relation, facts); // ★ E14：在任何数量计算之前
```

判据：**规则指名的商品（实物规则）必须在该产业的产出表里**（`Facts.outputPerUnit` 的**键**）。
只判实物规则（货币档没有商品可判）；★ 采用**统一**口径 —— `SELF_RETENTION` 指名的商品也判（配置错误就是配置错误）。

**消息（实测原文，取自 M6 的日志）**：

```
规则指名的商品不在该产业的产出表里（E14）：activity=farm@0_0 commodity=grain
该产业的 outputPerUnit=[grain] —— 静默付 0 是本仓最反对的形态，故当场抛（规则的商品要么改、
要么改 relation）；规则=CompensationRule[type=OUTPUT_SHARE, recipient=ToCohort[cohort=0_0|poor_peasant],
basis=NET_AFTER_INPUTS, ratePerMille=700, fixedAmount=0, commodity=Optional[grain], priority=10]
```

★ 消息里**两种拼写都摆出来**（规则指名的商品 + 该产业产什么）—— 这是"不许静默付 0"这条裁定的可执行形态。

**两条测试**：

1. `e14_aRuleNamingACommodityTheIndustryDoesNotProduceIsRejectedLoudly`：农业（产出表 `{grain}`）上挂一条**布**规则
   ⇒ 抛，且消息含 `cloth` / `grain` / `farm@0_0`。★ 带**正对照**（同一条规则换成 `grain` ⇒ 不抛、实得
   `100,000 × 700 ÷ 1000 = 70,000`）⇒ 排除"什么关系都抛"的假红。
2. `e14_theGuardReadsTheRecipeNotThisCyclesNumbers`：产出表里有粮、但**本期规模 0**（`gross`/`net` 都是空表）
   ⇒ **不抛**（"表里有、本期产 0"是合法状态）。★ 这条把守卫钉在**产出表**上：M6（守卫改读 `gross` 的键）**只**让这条红。

---

## 六、★ 两处"brief 没写、但判据强制"的设计决定（**请控制方核**）

### ① `Facts` 多了一个字段：`outputPerUnit`（第 7 个，追加在末尾）

**为什么必须**：E14 要判"商品在不在该产业的 `outputPerUnit` 里"，而 brief 给的 `Facts`
（`location/gross/net/inputs/laborOfCohort/assetOfActor`）与 `ProductionRelation`（`activity/operator/rules/residualOwner`）
**都拿不到该产业的产出表** —— 没有它，这条守卫**无法实现**（判据 ①：能产什么 ≠ 本期产了多少）。
⇒ 照 `EconomyData` 第 8 组件同款：**追加在末尾**，T4 填 `industry.recipe().outputPerUnit()`（值不参与任何公式，
本类**只读键**；类注写明"绝不用它算数量 —— 数量一律取自 gross/net"）。

**为什么不选"给 `settle` 加第三个参数"**：那样 `settle` 的签名（T4 直接消费的那一个）就与计划/ brief 不一致 ——
改字段比改签名对下游的冲击小（T4 只多填一格），且"本周期的事实"本就该用一条 record 走。

★ 这是 T3 相对 brief **唯一**的形状偏离；**几何**上不可避免（另两处见 ② 与 §十二.3）。

### ② 付方的**转出条目恒产生**（含 cohort 受方）

brief/计划只写了"cohort 受方 ⇒ 进 `cohortIntake`、actor 受方 ⇒ 进 `actorEntries`"，没写 cohort 那一支的
**operator 侧**要不要落条目。⇒ 判定：**要落**（`−paid`）。依据三条：

1. **R5 的流程**是 ② 产出 `+net → operator` ③ 再把实物送出去 ⇒ 不落转出，operator 那份**凭空多出来**
   （同一份产出在账上出现两次）；
2. **R1 的守恒式** `ΔActorGoods = Output − Input − TransfersOut + TransfersIn` 里**有** `TransfersOut` 这一项
   —— 否则 T5/T8 的 I4.1/I4.2 开不了账；
3. **I5.3 的期望**（货币档 ⇒ `actorEntries` **空**）不受影响：上限没被吃 ⇒ 没有实付 ⇒ 没有转出条目。
   ★ 实测：R6 那条用例里"付不出的第二条"**没有**条目，而"付得出的第一条"有**一条**转出条目 ⇒ 两者都钉住了。

---

## 七、改了 / 新增哪些文件

| 文件 | 动作 | 说明 |
|---|---|---|
| `simos-economy/src/main/java/io/mosire/simos/economy/time/ProductionSettlement.java` | **新建**（470 行） | 公式表 + 次序 + R6 上限 + E14/E11 守卫 + 读口 `deferredMoneyReason` |
| `simos-economy/src/test/java/io/mosire/simos/economy/time/ProductionSettlementTest.java` | **新建**（508 行 / 16 用例） | 逐值手算夹具（见 §八 的清单） |
| `.superpowers/sdd/2026-09-26-s1-stage45-ownership-and-relations/task-3-report.md` | **新建** | 本文件 |
| `.superpowers/sdd/2026-09-26-s1-stage45-ownership-and-relations/task-3-evidence/` | **新建** | **23 份**：RED ×1 / GREEN ×3 / 变异体 ×12（第一轮 6 + **最终字节上重证的 6**）/ 全仓 verify ×3 / md5 ×4（`git add -f` 入库：该目录被 `.superpowers/sdd/.gitignore` 的 `*` 挡着，照 T1/T2 先例） |

**既有文件一字未改**（本任务 = 纯新增 ⇒ 关账点 A 的"行为未变"是**结构性**的）。★ `progress.md` 的未提交改动
（T2 台账 + E12–E15 裁定）是控制方的账，照 T1/T2 先例**我不动、不提交**。

**16 个用例 → 判据的映射**：`i52_*`（I5.2）· `i53_*`（I5.3）· `i54_*`（I5.4）· `rulesWithTheSamePriority*`（次序的
第二半句：同值按表序）· `r6_*` · `r7_*` · `cohortAndActorRecipients*`（两族共用公式、差别只在落点）·
`selfRetention*` · `fixedInKindPerLabor*` · `fixedInKindRent*` · `assetQuantity*` · `twoRulesForTheSameCohort*`
（merge 累加）· `e14_*`×2 · `unregisteredRuleTypeAndBasis*`（E11）· `missingOrNegativeFacts*`。

---

## 八、TDD 证据（RED 当场捕获 / GREEN）

### RED ①（新类不存在）：`log-RED-1-production-settlement.txt`

```
./mvnw -pl simos-economy -am test -Dtest=ProductionSettlementTest -Dsurefire.failIfNoSpecifiedTests=false
rc=1
[ERROR] COMPILATION ERROR :
[ERROR] .../ProductionSettlementTest.java:[94,18] cannot find symbol
[ERROR]   symbol:   class Facts
[ERROR] .../[443,25] package ProductionSettlement does not exist
```

★ **为什么这个失败是预期的**：本任务的全部被测物就是**还不存在**的 `ProductionSettlement`
（`testCompile` 编译错 ⇒ **surefire 一个用例都没跑**）—— 与 T1 的 RED 同款（先红在"类型不存在"上，再写实现）。
★ 当场捕获（同一条命令、同一轮），非回放补拍。

### GREEN（实现落地后第一次）：`log-GREEN-1-production-settlement.txt`

```
[INFO] Tests run: 16, Failures: 0, Errors: 0, Skipped: 0 -- in io.mosire.simos.economy.time.ProductionSettlementTest
[INFO] BUILD SUCCESS          （checkstyle-check 全模块通过）
```

★ **如实记**：实现是**对着公式表一次写对的**（16/16 一次绿）—— 所以本任务的判别力证据**不在** GREEN 上，
而在下面的**变异自证**上（本仓的规矩：测试是不是真的在测东西，只看变异体能不能让它红）。

---

## 九、变异自证（**6 条**；每条：自答 → 实测红 → 还原后绿）

自答（每条放进变异体之前先问的那一句）：「**若这条规则不存在/写错，这个变异体会让哪条断言红？**」

| # | 变异体 | 自答（预期红在哪） | 实测 | 日志 |
|---|---|---|---|---|
| **M1** | `inPaymentOrder` 的排序换成**表序**（等价于不排） | I5.4 那条（跑法 ② 应得 25,000） | ★ 红 **2 条**：`i54`（`expected: 25000L but was: 50000L`）+ `i53`（待办清单的次序退化成表序） | `log-M1-tableOrder.txt` |
| **M2** | `OPERATOR_SURPLUS` 的"已付"当成 **0** | I5.4 + 同值按表序那条 | ★ 红 **2 条**：`i54` 与 `rulesWithTheSamePriorityKeepTheTableOrder`（都是 `expected 25000 but was 50000`） | `log-M2-surplusPaidZero.txt` |
| **M3** | **货币规则照常产生条目** | I5.3（`actorEntries` 应空） | ★ 红 **1 条**：`i53`，报的是 **`Expecting empty but was: [ActorEntry[... delta=-1000], ...]`** —— 即断言看的是"**确实没条目**"，不是"返回空" | `log-M3-moneyPaysEntries.txt` |
| **M4** | **摘掉 E14 的守卫**（不再调用 `requireProducibleCommodities`） | E14 第一条 | ★ 红 **1 条**：`e14_aRuleNaming...` —— `Expecting code to raise a throwable` | `log-M4-e14GuardRemoved.txt` |
| **M5** | `NET_AFTER_INPUTS` 与 `GROSS_OUTPUT` **走同一支** | I5.2 | ★ 红 **1 条**：`i52`（`expected: 29100L but was: 30000L`） | `log-M5-netSharesGross.txt` |
| **M6** | E14 的守卫改读 **`gross` 的键**（"本期产了多少"） | E14 第二条（"读产出表不读本期数"） | ★ 红 **1 条**：`e14_theGuardReadsTheRecipeNotThisCyclesNumbers`（`IllegalArgument ... commodity=grain 该产业的 outputPerUnit=[grain]`） | `log-M6-e14GuardReadsGross.txt` |

**★ M3 的一条实测教训（写下来给后来者）**：货币规则**没有 `commodity`**（契约层判死）⇒ 一个"照常产生条目"的
变异体**没法只改一处**（否则会先炸在 `IllegalStateException` 上，而不是红在"产生了条目"上）。M3 因此是**两处**
小改：货币档给出应付量 + `commodityOf` 兜一个商品。★ **这本身就是 I5.3 的强度所在**：契约（类型）与公式表
（`deferredMoney`）**两层**各自都在挡它。

★ 上表"日志"列是**第一轮**（pre-spotless 字节）的；**最终字节**上的重证日志是同一目录的
`log-M{1..6}-final-*.txt`（红的原因逐条相同，另附"还原后 md5 OK"）。

**还原的逐字节证据（两轮；★ 第二轮才是落盘可核的）**：

- **第一轮**（**pre-spotless** 的字节，`md5-baseline.txt`）：6 条逐条跑过、每条反向 `Edit` 还原后 `md5sum -c` 打 OK、
  最后一次复跑 16/16 绿（`log-GREEN-2-restored.txt`）。★ **如实记**：这一轮那几次 `md5sum -c` 的**输出没有单独落盘**
  （当时只看了 `OK`）—— 且此后 `spotless:apply` 与 SpotBugs 修复改动了字节 ⇒ 该基线已失效，**不再作为凭据**。
- **第二轮**（**最终字节**，`md5-prefinal-mutants.txt` = `bd069f65…` / `a869202c…`，与关账点 A 那次全仓 verify 跑的
  **是同一批字节**）：6 条**逐条重证**，每条日志里同时落盘两样东西 ——
  ① `M* maven rc=1` + **失败断言原文**（见上表"实测"列）　② 还原后的 `md5sum -c md5-prefinal-mutants.txt` ⇒ **两份文件都 OK**；
  日志：`log-M{1..6}-final-*.txt`。收尾再跑一次 ⇒ **16/16 绿 + BUILD SUCCESS + md5 再校验 OK**
  （`log-GREEN-3-final-after-mutants.txt`）。★ 于是"红过、又逐字节回到了绿的字节"这件事**在证据目录里自证**。

---

## 十、★ 关账点 A：全仓 `./mvnw clean verify`（逐模块 + 测试总数）

命令：`./mvnw clean verify`（★ 一次一个 Maven：跑前 `pgrep -af "[s]urefirebooter|[c]lassworlds.launcher"` 为空）
日志：`task-3-evidence/log-VERIFY-full-repo-3-final.txt`　⇒　**rc = 0 / BUILD SUCCESS / Total time 04:31 min**

| # | 模块 | 结果 | 测试数 | SpotBugs |
|---|---|---|---|---|
| 1 | SimulatorMosire（父） | SUCCESS | —（无测试） | —（无 spotbugs 目标） |
| 2 | UtilSimos | SUCCESS | 199 | 0 |
| 3 | MapSimos | SUCCESS | 379 | 0 |
| 4 | ActorApiSimos | SUCCESS | 16 | 0 |
| 5 | EconomyApiSimos | SUCCESS | 23 | 0 |
| 6 | SocialSimos | SUCCESS | 188 | 0 |
| 7 | UnitSimos | SUCCESS | 305 | 0 |
| 8 | CoreSimos | SUCCESS | 215 | 0 |
| 9 | SDSimos | SUCCESS | 188 | 0 |
| 10 | ActorSimos | SUCCESS | 129 | 0 |
| 11 | LedgerSimos | SUCCESS | 26 | 0 |
| 12 | **EconomySimos** | SUCCESS | **170**（= T2 的 154 + 本任务 16） | 0 |
| 13 | SimosApp | SUCCESS | 675 | 0 |

- **合计 2,513 个测试：0 失败 / 0 错误 / 0 跳过**；checkstyle **13/13** 模块「You have 0 Checkstyle violations」；
  spotless **12/12**「0 needs changes to be clean」；SpotBugs **12/12** 模块 `BugInstance size is 0`。
- ★ 门禁 **fail-closed 读法**（不看 rc 看报告）：`find . -name '*.txt' -path '*surefire-reports*' ! -newermt <本轮起点>`
  ⇒ **0 个**（284 份 surefire 报告 mtime **全落本轮**）。
- ★ **"行为未变"在本任务是结构性结论**：只新增了两个文件，既有 `src/main` / `src/test` **一字未改** ⇒
  既有的全部用例（含 app 侧 675 条端到端）逐条绿 —— 这正是关账点 A 要的那件事。

### ★★ 两次真实门禁命中（如实记 + 处置；红→改→绿的时序可核）

1. **第一次（`log-VERIFY-full-repo.txt`）：红在 `spotless:check`** —— 两个新文件的 **javadoc 折行**不合
   google-java-format（★ 测试 **170/170 全绿**、checkstyle 0 ⇒ 红的是格式闸，不是行为）。
   处置：`./mvnw -pl simos-economy spotless:apply`（"2 were changed to be clean"，**只动这两个文件**）⇒
   逐行 grep 关键行（E14 调用 / `OptionalLong.empty()` / `paidOf` / `comparingInt(priority)` /
   `NET_AFTER_INPUTS`）确认**只重排格式、无语义改**。
2. **第二次（`log-VERIFY-full-repo-2-post-spotless.txt`）：红在 `spotbugs:check`** —— **7 条 `EI_EXPOSE_REP`**，
   全部落在 `Facts` / `Outcome` 的 **record 访问器**上。**根因**：不可变写在**校验助手**里
   （`requireQuantities` 返回 `Collections.unmodifiableMap(...)`），而 SpotBugs **看不穿方法边界**；
   本仓既有的 record（如 `Industry.outputPerUnit`，`Industry.java:211`）是在**赋值处**字面写
   `Collections.unmodifiableMap(...)` ⇒ 不报。⇒ 处置：把不可变**移到赋值处**（照 `Industry` 先例），
   助手只负责"校验 + 拷贝"；★ **没有**用 `@SuppressFBWarnings` 压 —— 压了等于把这一类问题对该类型整片关掉
   （本仓的注解位置注释也把它列为"误报"才用的兜底）。
3. **第三次（`log-VERIFY-full-repo-3-final.txt`）：全绿**（本节上表）。
   ★ 那次门禁跑的字节 = `md5-final-post-spotbugs-fix.txt` = `md5-prefinal-mutants.txt`
   （`bd069f65…` / `a869202c…`）；6 条变异体随后在**同一批字节**上逐条重证并逐条还原（§九）⇒ 证据闭环。

---

## 十一、自审发现（读自己的 diff 找出来的）

1. **★★ 组合守卫我一开始想放两处**（上游"配置 pass" + 公式表里）—— **错**：两处互为兜底 ⇒"摘掉一处"的变异体
   **照样绿**（不可观测）。⇒ 改成**只**放公式表里（E11 说落点就是它）；E14 **只**放上游 pass（因为公式表里
   没有一行读得到 `outputPerUnit`）。★ 与"同一件事只有一处拼写"是同一条纪律的两种用法。
2. **测试里 `.as("不读"已付"的那一条…")` 嵌了 ASCII 双引号** ⇒ 字符串字面量当场坏掉（自查时抓到，已改「」）。
   brief 那句"中文引号用「」"是针对**字符串字面量**的，注释里的 ASCII 引号无害（本仓既有文件也用）。
3. **`Facts.inputs` 本阶段没有任何公式读它** —— 我**没有**编一条公式去用它（那才是本仓反对的"把没定的设计写进
   实现"），而是如实记在类注与 §十二。★ 同理 `ASSET_QUANTITY` 的 cohort 受方、`LABOR_AMOUNT` 的 actor 受方
   一律**归零**（各写一句"本阶段没有那份账"），而不是拿别的账凑数。
4. **`commodityOf` 的 `orElseThrow` 是不可达分支**（货币档已在 `dueAmount` 分流）—— 它是**兜底**、不是判据，
   故意不做成变异体目标（不可达分支上的变异体 = 空转变异体，绿得骗人）。
5. **`ActorEntry`/`Outcome`/`Facts` 的守卫是 brief 之外的加固**（null ⇒ 抛、负数量 ⇒ 抛、集合冻在赋值处）——
   理由：本仓每个 record 都这么写，且"负数事实"会让实付变成**反向的收**（静默）。已各配一条用例。
6. **★ 本任务 16/16 一次绿，但"绿"本身不是证据**：真正把它钉住的是 §九 的 6 条变异体（每条都当场红）。
   ★ 这也是我在 §八 如实写"实现是对着公式表一次写对的"的原因 —— 本仓不要"看起来过了"。

---

## 十二、疑虑 / 未验 / 边界（如实记）

1. **★ 两条 T4 会踩到的口径**（请控制方在 T4 派活前确认，改的代价现在最低）：
   ① `Facts` 需要第 7 字段 `outputPerUnit`（§六.①）；② 付方转出条目恒产生（§六.②）。
2. **自付自的边界**（已写进类注、**未**做用例）：受方 = operator 时会产生一对净额为 0 的条目，**且占用付款额度**
   （后续 `OPERATOR_SURPLUS` 看到的是"已付"）。真档四档制度的受方全是 cohort ⇒ 无实际影响；
   ★ 若 GM 写"自留 = 一条给 operator 的规则"，这条会让它吃掉额度而实物没离开 operator —— **S2 的 ledger 口径**该定。
3. **`simos-economy/pom.xml` 的 `<description>` 里那句「**不依赖 map**：经济树里没有任何地图类型（本切片不认识 hex）」
   已被事实作废**：T2 的 `RegimeRelations` 已 `import io.mosire.simos.map.hex.HexCoord`，本任务的
   `Facts.location` / `ActorEntry.location` 再用一次（`economy-api` 的 `CohortKey`/`Recipient` 也带 `HexCoord` ⇒
   `map` 通过 `economy-api` 传递可达）。★ 我**不动** `pom.xml`（本任务的文件清单里没有它，且它是"配置/前序产物"）
   ⇒ **建议 T8 回填那句注释**（否则后来者会按它去做一次错误的依赖收窄）。
4. **未达成项（本任务范围内）**：§2.5 的超额上限与 `ConsumptionReceipt`（阶段 6）；cohort 侧索取权
   （"付不满不留债权"，R6 明文）；跨周期结转/欠租（S2）；`AssetHolding` 未在真档种入 ⇒ `ASSET_QUANTITY`
   **只有夹具覆盖**（E11 已裁定的已知边界）。
5. **未验**：真档（600 天）上的数值后果 —— 本任务 `harvest` **还没读** relations（切在 T4），
   故 I5.1/I5.2 的**端到端**读数只能在 T7 开账。
6. **门禁口径**：本任务按 brief 跑**全仓** `clean verify`（关账点 A，控制方执行口径，见 §十）。
