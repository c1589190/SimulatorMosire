# M1（账户与关系）开工基线 —— 现状与缺口勘察

> **性质**：只读勘察。本批**未改任何文件、未跑 Maven、未 commit**。
> 本文件是本次勘察**唯一**创建的产物。
> **勘察日**：2026-09-27 · **HEAD**：`b467049a`（branch `ts/m1`，working tree clean）
> **口径来源**：用户 2026-09-27 裁定（本文件逐字遵守）+ `docs/superpowers/plans/2026-09-27-master-development-plan.md` §四（M1.1–M1.8）
> **用途**：M1 施工图的输入。每项缺口给「改哪个文件 / 加哪个类型」的落点与理由（**不含代码**）。

---

## 0. 一句话结论

**身份与余额已经有了、而且是唯一的真源；"冻结 / 债权双向 / 统一可支配查询 / 跨市场不可重复使用"四件今天一件都没有。**
今天不出问题不是因为守住了，而是因为**结构性回避**：库存在格上分层（`GoodsAccountKey` 带 location）、市场每周期一次且同格、单线程就地改会话副本。
用户裁定的"保留策略在经济决策层"这条边界**今天已经被遵守**（两条 `reserve` 都只在 settlement 里、都是只读算式）——M1.2 要做的是给账户层补上它**完全缺失的执行能力**，而不是把保留搬进账户层。

---

## 1. 逐项勘察表（A–F）

### A. 账户与身份

| 落点 | 现状 | 缺口 |
|---|---|---|
| `simos-actor/.../model/GoodsAccount.java:65-66` | `record GoodsAccount(GoodsAccountKey key, Map<CommodityId,Long> balances, Map<CurrencyId,Long> money)` —— 商品与货币**同住一本账**（裁定 K15/H4，类注 55-59 明说"不是另立一张同键的表"）。 | ★ 三个组件里**没有 `frozen`**、没有预留表、没有任何占用字段。 |
| `GoodsAccount.java:79-117` | 构造期守卫：`key`/`balances`/`money` 非 null；逐值 `< 0` 当场抛（商品 94-97、货币 107-113）；`LinkedHashMap` + `unmodifiableMap` **冻在赋值处**（100、116），明令不用 `Map.copyOf`。 | 守卫只管"非负"，不管"有多少已被承诺"。**"可支配"这个语义在类型上不存在**。 |
| `GoodsAccount.java:75-77` | **两参便捷构造器**：`money = Map.of()`。类注 71-73 自述"它不是忘记传钱的掩护"。 | ★★ **这是 M1.3 的核心陷阱**：任何新写入点用两参构造器就会**静默把钱清零**。今天靠"调用点少 + 注释 + 排序约定"兜（`OwnershipBooks.java:129-132` 专门写了警告）。 |
| `GoodsAccountKey.java:42` | `record (ActorRef owner, HexCoord location)` —— 两段任一不同就是另一本账（类注 7-11）。 | 无。★ 这一维**意外地**成为今天"库存不会被两个市场同时用"的第一道结构性保障（见 E）。 |
| `GoodsAccountKey.java:61-63 / 71-85` | `toString()` = `<owner>\|<location>`，`parse` 按**第一个 `\|`** 切；宁抛不静默。 | 无。类注 32-34 自陈前提：全仓 actor id **必然不含 `\|`**（`HouseholdActors` 为此把分隔符取 `:`）。 |
| `simos-actor-api/.../actor/ActorRef.java:23` | `record (ActorKind kind, String id)` —— 身份 = 种类 + 稳定 id，不依赖任何领域模块。 | 无。铁律 1 已满足（不另造同义 ID）。 |
| `ActorRef.java:41-43 / 59-70` | `toString()` = `<KIND>:<id>`，`parseCanonical` 是它的**逆**（按第一个 `:` 切）；两参 `parse` **故意**不是逆（裁定 R4，类注 19-21 明令不许"顺手修好"）。 | 无。 |
| `simos-actor-api/.../actor/ActorKind.java:29-42` | 7 档词表：`PEOPLE_LOT / UNIT / GOVERNMENT / ORGANIZATION / HOUSEHOLD / ESTATE / WORKSHOP`。 | 无（"谁持有"这一维已齐）。 |
| `simos-economy-api/.../cohort/CohortKey.java:37` | `record (HexCoord hex, ResidenceKind residence, SocialClassId stratum)` —— **家户身份**；`residence` 是 H0.1 补的维（类注 13-17：少了它农村/城镇同阶层会**并账**）。 | 无。 |
| `simos-economy-api/.../cohort/HouseholdActors.java:39-48 / 51-53 / 61-80` | **家户身份 ↔ actor 身份的唯一拼写点**。id 形如 `0_0:rural:poor_peasant`（分隔符 `:`，**不含 `\|`** ⇒ 与 `GoodsAccountKey` 的接缝相容，类注 17-26 给了这条会炸的接缝冲突的完整推导）。反查 `cohortOf` 三档 fail-closed（非 HOUSEHOLD / 段数不为 3 / 段空 ⇒ 抛）。 | 无。 |
| `simos-app/.../time/OwnershipBooks.java` | ★ **不是六个、是八个** `load*/land*`：`loadHouseholdGoods:161` / `landHouseholdGoods:210` / `loadHouseholdMoney:260` / `landHouseholdMoney:301`（家户 4 个）+ `loadOperatorGoods:385` / `loadOperatorMoney:401` / `landOperatorGoods:427` / `landOperatorMoney:465`（经营者 4 个，H5 加）。另有 `fold:85`（当天的账 → 产权条目，**唯一折算点**）、`apply:101`（条目落账）、`accountKeyOf:339`（家户账键的唯一拼写点）、`operatorLocations:357`。 | ★★ **两处失败口径不对称**：`landHouseholdMoney:323-328` 在账本缺席时**抛**（防"商品还没落"的顺序反了），而 `landOperatorMoney:491-495` **静默新建一本空商品账**（`existing == null ? Map.of() : existing.balances()`）。顺序写反时后者不抛，会产出"商品为空"的账。 |
| `OwnershipBooks.java:161-185 / 260-284` | 载入**以 economy 的家户行集为驱动**（不扫全表 —— 因为 `HOUSEHOLD` 这个种类**不只家户**，家庭纺织产业的经营主体也是它，类注 146-148）；载入不出来 ⇒ **抛**（不许把"没有账"静默当成"库存 0"，150-152）。 | 经营者那两条**刻意不抛**（`385-398` 类注 373-382：缺席是合法状态）。⇒ 两个驱动集、两套缺席语义。 |
| `OwnershipBooks.java:210-238 / 301-333` | 落回按**副本绝对值**（家户），**不是**再叠加条目（叠加 = 同一笔记两遍，类注 190-196）；货币落回**必须排在商品之后**（写的是同一本账的另一个余额表，291）。 | 无（口径正确），但**只有注释与调用顺序保证**，类型上不阻止反序（家户那条靠抛兜住，经营者那条不兜 —— 见上）。 |
| `simos-actor/.../ActorData.java:48-51` | `record (Optional<ActorMeta> meta, Map<ActorRef,Actor> actors, Map<GoodsAccountKey,GoodsAccount> accounts)` —— 3 个组件。 | ★ **加 `frozen`/`FinancialAccount` 若落在 `ActorData`，就必须同步改 `ActorChangeSet` + `ActorRoundTripTest`**（见 §4 风险）。 |
| `ActorData.java:143-148 / 109-111` | `withAccount(GoodsAccount)` —— **键从值派生**（"键是 (owner,location)"只许一个拼写点，类注 132-141）；`withAccounts(Map)` 批量。 | `withAccounts` 是**整表覆盖**，`withAccount` 是**整本覆盖**：都是"存量"语义，**没有"加一笔"的入口**（这是刻意的，见 `GoodsAccount` 类注 39-42）。 |
| `simos-actor/.../change/ActorChangeSet.java:38-40` | `record (FieldDelta<ActorMeta> meta, FieldDelta<Actor> actors, FieldDelta<GoodsAccount> accounts)` —— **恰 3 个** `FieldDelta`。 | 组件集由反射守卫（`ActorRoundTripTest.java:94` `hasSize(3)`，且双向 `isSubsetOf`）。 |
| `simos-actor/src/test/.../change/ActorRoundTripTest.java:94` | `assertThat(ActorChangeSet.class.getRecordComponents()).hasSize(3)` + 双向子集断言。 | 新增 `ActorData` 组件而不进变更集 ⇒ **当场红**。 |

#### ★ 回答：今天有没有"冻结"概念？

**没有。零。** 证据（三组 grep，全仓 `simos-*/src`）：

1. `grep -rn "frozen\|Frozen\|FROZEN"` —— **全部命中都是别的意思**：① 指"集合不可变"（`RegionIndex:51`、`SdInfoEntry:96`、`PopulationSeries:47`、各 `*InvariantsTest` 的 `...IsFrozen`）；② 指"字面量钉死"（`HexCoordTest:19` 的 `frozen[]`、`EventTypesTest:21` 的 `FROZEN`）；③ 指"判定已冻结"（`AdjudicatorRunnerTest:51`）。**没有任何一处在经济/账户语义上**。
2. `grep -rni "reserved\|reserve"` —— 经济语义只有**两条常量**，且都在**结算层**：
   - `simos-economy/.../time/MarketSettlement.java:106` `MARKET_SELF_RESERVE_PER_MILLE = 1000`，用法 `sellableOf`（`348-360`）= `max(0, stock − reserve)`；
   - `simos-economy/.../time/EconomySettlement.java:313` `LENDER_SUBSISTENCE_RESERVE_PER_MILLE = 1000`，用法 `lendableOf`（`2519-2530`）= `max(0, stock − reserve)`。
3. ★★ `EconomySettlement.java:2516` **逐字写着**："**保留额不是"冻结起来的一笔粮"**，放贷行自己每天照吃不误 —— 它只是"可贷额"的下界。" ⇒ 两条 `reserve` 都是**只读算式的中间量**，**不写任何字段、不占任何库存**。
4. `grep -rniE "\b(held|lien|escrow|pledge|earmark|blocked)\b"` —— 经济语义**零命中**（命中全是 `available` 的劳动口径、`pending` 的审批/运行时状态）。
5. 挂单/订单占用：**不存在**（`OrderId` 只有 record 定义 + 一个 ID 往返用例，无实现；见 B 的死 ID 一节）。

**⇒ 用户裁定"账户层只执行已明确的冻结与转移"里的"转移"已经有了（唯一 applier），"冻结"这一半是完全空白。**
**⇒ 另一条要注意的**：用户裁定的边界"市场的保留策略放在经济决策层"**今天已经被遵守**。两条 `reserve` 都在 settlement、都是 `max(0, 余额 − reserve)`、都不落任何字段。**M1.2 加 `frozen` 时不要把 `MARKET_SELF_RESERVE_PER_MILLE` / `LENDER_SUBSISTENCE_RESERVE_PER_MILLE` 折进 `frozen`** —— 那就是把决策搬进账户层，直接违反裁定。`frozen` 应只承载**已经由决策层明确下达**的占用（挂单/在途/合同）。

---

### B. 货币

| 落点 | 现状 | 缺口 |
|---|---|---|
| `simos-economy-api/.../id/CurrencyId.java:23` | `record CurrencyId(String value)` —— **只是身份**。类注 16-17 明说："它仍然只是'身份'（没有发行）：本批没有任何 `MoneyAuthority` 实现"。 | ★ **没有"币种 ≠ 货币工具"的区分**（见下）。 |
| `simos-economy/.../model/Market.java:39` | `record Market(CurrencyId numeraire, Map<CommodityId,Long> prices)` —— **每格单一计价货币**（裁定 M1-A），类注 13-14 明说"**不做汇率**：跨币种的兑换在本批不存在，币种之间不许求和、也不许折算"。`priceOf:67-69`（未定价 ⇒ 0 = 本格不交易它）。 | ★ 一个 `Market` 只有一个 `numeraire`（单币种）。**一个币种也只对应一种"钱"**——没有工具维。 |
| `GoodsAccount.java:66`（`money`） | **钱的存放点**：`Map<CurrencyId, Long>`，与商品同住一本 `GoodsAccount`。 | 钱的存放**正确**（裁定 M2/K15），但**只有这一层**：无 `FinancialAccount`、无 owner 之外的维度。 |
| `simos-economy/.../time/EconomyDayStepper.java:61-81` | **会话工作副本**：`householdGoods` / `householdMoney`（家户，键 = `CohortKey`）+ `operatorGoods` / `operatorMoney`（经营者，键 = `ActorRef`）。类注 29-49：两份副本"同形、同生命周期、**同样不进 `EconomyData`**"，且"**它是可变对象**（唯一的一个）……**不共享、不并发**：一次推进一个实例（用完即弃）"。 | ★★ **钱在内存里有两个表示**：`GoodsAccount.money`（落盘真源）+ 会话副本（就地改）。两者靠 `load*` → `step` → `land*` 的**绝对值写回**对齐 —— 是"一处可写"（`land*`），但**是两个表示**。M1.3 要裁的正是这条。 |
| `simos-app/src/test/.../world/EconomyMoneyInvariantTest.java:71`（判据 53-64） | 守恒判据现状 = **逐币种 Σ 恒定**，四条：① `genesisMoneyEqualsTheSeederFormulaNotJustItsOwnBooks:225`；② `moneyTotalPerCurrencyIsConstantThroughAllThreeCycles:249`；③ `everyBookLandsItsOwnMoneyWithoutLosingOrMintingAny:268`；④ `moneyMovesBetweenHouseholdsWhileItsTotalStaysConstant:319`；⑤ `theHexMoneyCurrenciesCoverTheMarketNumeraire:355`。类注 66-69 精确写明"**两侧不是各自恒定** —— `handicraft` 的货币工资把工钱从经营者搬到**家户** ⇒ 家户侧 Σ 上浮、经营者侧 Σ 下沉，而**两者之和**逐币种恒定"。 | ★ 判据是"**全世界总量恒定**"——这正是 M1.6 要**明说没有**的那条总不变量（master plan:258：「★ 明写**没有**"全世界总量永远不变"的总不变量」）。今天它成立只因为**零发行人**。 |
| `simos-economy/.../time/EconomySettlement.java:3123-3195` | `applyTransfer(...)` —— **全模块唯一**写会话副本库存的"换手"路径（商品 3132-3148/3182-3189 + 货币 3152-3170/3190-3193）；经营者端 `debitOperator:3217-3265` / `creditOperator:3198-3214`。类注 3103-3121：收成一个函数的理由是"改前有三处各写各的……加第四条路（市场）时没人拦得住它长成第四套写法"。 | ★★ **它不是原子的**：先逐腿扣付方（3132-3148 商品、3152-3170 货币），**再**记收方（3182-3194）。若某条腿在中间抛（余额不足 / `MoneyIssuance` 恒抛），**前面已扣的腿留在副本上**、收方一笔没记 ⇒ **半笔转移**。副本是可变对象，抛错后调用方手里那份仍是半改状态。这是 M1.4「原子结算」**今天就有**的真实病灶（与"多市场并发"无关，见 §2 第 5 条）。 |
| `applyTransfer:3155 / 3247` | 货币腿扣不动时调 `MoneyIssuance.requireIssuerOf(...)` —— 类注 3119-3121："扣成负数时唯一的合法解释是'付方在发行'……本批**没有任何发行人** ⇒ 恒抛"。 | 无（fail-closed 正确）。但它意味着**"钱不够"与"凭空造钱"今天走同一条异常路径**，无法区分。 |
| `simos-economy-api/.../money/MoneyIssuance.java:34 / 39 / 48 / 67-82` | **唯一闸门**。`REGISTERED = List.of()`（34）⇒ `registered()` 恒空、`issuable()` 恒空、`requireIssuerOf` **恒抛**（76-82）。类注 12-13："这不是'还没接线'，而是本批**刻意**的状态：只要有发行人，'Σ货币余额恒定'就不再成立。" | ★ **零实现者、零注册**。类注 24-25 指名了将来的注册点："将来真有实现时，注册点是**这里**（一处拼写点）"。M1.1/M1.6 的发行累计量落点就是它。 |
| `simos-economy-api/.../money/MoneyAuthority.java:33` | 接口存在（`issuable()` / `authorityOf(CurrencyId)`）。 | **零实现者**。 |

#### ★ 回答：有没有"币种 ≠ 货币工具"的区分？有没有 `CurrencyDef` / `MoneyInstrument` / `FinancialAccount`？

**三个都没有。**
- `grep -rniE "CurrencyDef|MoneyInstrument|FinancialAccount|InstrumentId|InstrumentKind|Denomination|Coinage"` 全仓 `simos-*/src` → **零命中**（只命中 `MoneyAuthority` / `MoneyIssuance` 这两个已存在的类型）。
- ★★ 而且**代码自己已经把这一维记成"缺失且属 M1"**：`simos-app/.../gui/ApiViews.java:593`
  ```java
  unavailable.put("paymentInstrumentGap", "货币工具与接受规则属 M1（币种 ≠ 货币工具）⇒ 今天不判「付得出去吗」");
  ```
  这是 M0.3（`EconomyGrainDiagnosisTest` 守着 `unavailable` 三项必须具名）留下的**具名缺口**，落点写明是 M1。
- `simos-economy-api/.../money/package-info.java` 也把"钱从哪来"限定在 `MoneyAuthority` / `MoneyIssuance` 两件，**没有工具这一层**。

**⇒ M1.1 是纯新增（`CurrencyDef` + `MoneyInstrument`），不会与任何既有类型冲突；且 `silver` 今天只是一个 `CurrencyId` 字面量（唯一拼写点 `RegimeRelations.DEFAULT_CURRENCY:137` ← `EconomySeeder.MARKET_NUMERAIRE:378`），迁移面小。**

#### 附带发现：一批"只作为契约保留"的死 ID

`simos-economy-api/.../id/` 下这些 record **只被 `EconomyIdsTest` 的往返用例引用**，生产侧**零引用**：
`AccountId` / `AssetId` / `AssetRightId` / `ClaimId` / `ContractId` / `OrderId` / `ShipmentId`（`Transfer.settles` 用了 `ClaimId` 的类型，但恒 `Optional.empty()`）。
**⇒ M1.1/M1.2 需要的 `AccountId`、M1.4 需要的 `BatchId`、M1.1 需要的 `InstrumentId` 有先例可循（形制 = 单参 record + 裸 `toString` + `parse` 逆 + 空白即抛），但 `AccountId` 已存在且未被使用 —— 用它、不要另造。**

---

### C. 债权债务

| 落点 | 现状 | 缺口 |
|---|---|---|
| `simos-economy/.../model/Debt.java:39-47` | `record (DebtId id, CohortKey debtor, CohortKey creditor, Optional<CommodityId> commodity, long principal, int ratePerMillePerCycle, long dueCycle, boolean defaulted)`。★ **一条记录同时带 debtor 与 creditor** ⇒ "同源"在**数据上已经成立**。 | 8 个组件里**没有 accruedInterest**（利息并入 `principal`）、**没有 state**（只有 `defaulted`）、**没有 dueTick**（只有 `dueCycle`）。 |
| `Debt.java:23-25 / 36-37` | ★★ 自述留位：`dueCycle`"**保留，但当前没有任何代码读它**"；`defaulted`"同理（写死 `false`）"。类注 24-25 明说本仓禁"看起来在记、其实永远不被读"的**静默**字段，故**具名**写在这里。 | ★ 这两个字段是**具名留位**，不是违规。M1.5 的 `Claim.dueTick` / `Claim.state` 正好把它们接上读口（替换时不要留成第二处同义字段）。 |
| `simos-economy-api/.../id/DebtId.java:22` | `record DebtId(String value)`；id 由 `(周期, 债务人, 债权人, 商品)` **确定性算出**（`EconomySettlement.debtIdOf:2934-2951`）⇒ 同周期内同一对主体的多次借入**累加到同一条**（`Debt` 类注 11-14：条数上界从"约 117 万条/年"降到 `O(周期数 × 格子 × 债权人对数)`）。 | 无。 |
| `EconomySettlement.lendDeficits:2374-2494` | 放贷：放贷序列 `地主→富农→中农`（2408-2415），可贷额 `lendableOf`（2519-2530 = `max(0, 余额 − 整周期自需×1000‰)`），借一条走 `applyTransfer`（2455-2456），借到的当场吃掉（2460）。★ 债务条写入时**只把 `debtId` 加进债务人那一行**：`2479 rows.put(debtor, withExtraDebt(rows.get(debtor), debtId))`。 | ★★ **债权人的行/索引完全没被更新**。 |
| `EconomySettlement.repayDebts:2619-2673` | 周期末偿还（`FlowRow.repaid` 的第一个写入点）：`budget = min(⌊本日粮所得×200‰⌋, 当前粮库存)`（2636-2638），逐条债还，走 `applyTransfer` 铸 `LOAN_REPAYMENT` 转移（2654-2667），**本金还清 ⇒ 那条 `Debt` 留在表里、本金为 0**（类注 2610-2611："已结清的历史事实"）。 | ★ 偿还走**债务人行**的 `row.debts()`（2642）找债 —— 债权人**依然没有索引**：谁还给我的、还了多少，债权人侧读不出来。 |
| `EconomySettlement.chargeInterest:3530-3541` | `interest = principal × rate ÷ 1000`（3534）；`principal' = principal + interest`（3538，复利）；**只** `interest.merge(debt.debtor(), charged, Long::sum)`（3539）。 | ★★ 类注 **3518-3519 逐字**："**债权人这一侧本批不记**：它的资产增值体现在债务表里，而 `FlowRow.income` 是**粮食**口径（往里加'利息收入'会让守恒式当场不成立）⇒ 记在债权侧要等 §五 的索取源协议/市场折算（V7+）。" |
| `FlowRow.java:80-84` | `taxPaid`(80) / `interestDue`(81) / `newBorrowing`(82) / `repaid`(83) / `netSurplus`(84)。 | ★ **`FlowRow` 是"逐家户一行"**，`interestDue`/`newBorrowing`/`repaid` 都写在**债务人**那一行 ⇒ 债权人侧的"应收/利息收入"**在流水里无处可放**（这是 `chargeInterest` 类注给的直接理由）。 |
| `simos-economy/.../model/ClassRow.java:60` | `List<DebtId> debts` —— 行里的债务**引用表**（守卫 87-88、116-123）。 | ★ **只有债务人方向**。没有 `credits` / `receivables` 对称字段。 |
| `simos-app/.../gui/ApiViews.java:446-452` | 逐格汇总债务：`for (DebtId debtId : row.debts())` → `debtCount++ / debtPrincipal += ...`。 | ★ 读口**只遍历债务人方向**。 |
| `ApiViews.java:804-808` | 单行读口：`for (DebtId debt : row.debts()) debts.add(debt.value())` → `view.put("debts", debts)`。 | ★ 同上。**没有 `credits` 栏**。 |
| `simos-economy/.../EconomyData.java:210-222` | 构造期守卫：`debt.debtor()` 与 `debt.creditor()` **都必须**存在于 `classes` 里（214-216，v2 spec §八.2）。 | 无（两侧都在守卫里 —— 这又一次说明"同源"在数据层是成立的）。 |

#### ★ 回答：债权人那一侧今天有没有账？能不能查到"谁应收我多少"？

**没有账。查不到（除非全表扫 `EconomyData.debts()` 再自己按 `creditor` 过滤 —— 而全仓没有这个入口）。** 四段证据链：

1. **源**：`Debt` 一条记录**同时**带 `debtor` 与 `creditor`（`Debt.java:41-42`）⇒ "一笔债两视图同源"的**数据基础已经存在**（这是好消息：M1.5 不需要重造债权模型）。
2. **索引**：`ClassRow.debts`（`ClassRow.java:60`）只装 `DebtId`；`lendDeficits:2479` **只**给**债务人**的行追加 `debtId`。债权人那一行从头到尾没被碰过。
3. **读口**：两个 GUI 读口（`ApiViews:446`、`ApiViews:804`）与偿还循环（`EconomySettlement:2642`）**都遍历 `row.debts()`** ⇒ 全部是债务人方向。
4. **流水**：利息只 `merge(debt.debtor(), ...)`（`3539`），类注 `3518-3519` 明说债权人侧不记 —— 因为 `FlowRow.income` 是粮食口径，往债权人行加"利息收入"会**让守恒式当场不成立**。

**⇒ M1.5 的准确工作量 = ① 加债权人方向索引（或把 `Claim` 做成双向可查的独立表）+ ② 给债权人侧的"应收/利息收入"找一个**不破坏粮食守恒**的落点（`FlowRow` 是粮口径 ⇒ 应收必须另立表，不能塞进 `income`）。这是 master plan M1.5 里"债权侧入账在此完成"那句的**真正难点**，比"加个索引"大。**

---

### D. 产品归属 / 供料责任 / 给养义务

| 落点 | 现状 | 缺口 |
|---|---|---|
| `simos-economy-api/.../relation/ProductionRelation.java:86-91` | `record (IndustryId activity, ActorRef operator, Recipient inputSupplier, List<CompensationRule> rules, ActorRef residualOwner)` —— **5 个组件**。身份 = `activity`（不另造 id，铁律 1）。 | 无（就 M1.7 的三面而言，见下）。 |
| `ProductionRelation.java:84 / 29-31` | **「谁拥有产品」= `residualOwner`**：字段明确（`residualOwner` 不得为 null，107-109），语义 = "余额归谁（一般是 `operator`；**不产生任何条目**）"。类注 30-31："空表合法：一条规则都没有 ⇒ 产出**全部**归 `residualOwner`（自留是**缺省**）"。 | 无字段缺口。★ 但读口没有把"这一档制度的产出归谁"作为**独立一栏**发出来（只有 `economyHex` 的 `operator`，见 `ApiViews:714-724`）。 |
| `ProductionRelation.java:81-82 / 100-103` | **「谁负责供料」= `inputSupplier`**（H3/C3 新增）：`Recipient`（sealed: actor \| cohort），**缺省（null）⇒ `ToActor(operator)`**（101-103，构造期补）。类注 48-62 给了两条存在理由：① 把"谁出料"从"按人口猜"（旧的 `rowSharesOf` 按人口占比摊）变成"制度明说"；② 让 GM 能表达"地主出种"这种制度。 | 无字段缺口。★ **实现边界如实记在类注 74-77**：economy 只看得见**家户账**；若指名的供方是**聚合主体**（`ESTATE`/`WORKSHOP`/产业型 `HOUSEHOLD`），由**该产业名下的家户账代理**（逐户按持仓量等比例、最大余数法），取不满则规模缩。 |
| `simos-economy/.../model/RegimeRelations.java:209-217 / 240-295 / 318-333` | 四档规则表 `BY_REGIME`（`RegimeOperators.FEUDAL/HOUSEHOLD/HANDICRAFT/TENANT`）；`defaultRelation`（240-295）产出 `new ProductionRelation(industry, operator, defaultInputSupplier(regime, operator), rules, operator)`（**293-294**：`residualOwner = operator`，裁定 E9"自留不写成规则"）；`defaultInputSupplier`（318-333）= H3/C3 的**唯一拼写点**，四档默认**都**落回 operator（类注 51-53：`feudal`=庄园出 · `tenant`=佃农家户出 · `household`=家户自出 · `handicraft`=作坊主出）。 | 无。 |
| `RegimeRelations.java:355-407` | `feudalRules()`：① `FIXED_IN_KIND_PER_LABOR`（**实物给养**，毫粮/1000 千分劳动，常量 `FEUDAL_SUBSISTENCE_MILLI_PER_LABOR = 144` 于 `165-171`）给四个阶层 cohort；② `OUTPUT_SHARE × GROSS_OUTPUT`（地租 `FEUDAL_RENT_PER_MILLE = 300` 于 `149-153`）给地主 cohort；③ 副产纤维分成（`FEUDAL_BYPRODUCT_SHARE_PER_MILLE = 1000` 于 `156-162`）。 | ★★ **"实物给养义务"今天不是字段、不是类型，而是 `rules` 表里的"某一条规则"**（`RuleType.FIXED_IN_KIND_PER_LABOR`）。见下。 |
| `RegimeRelations.java:409-419 / 421-444 / 445-...` | `householdRules()`（布 700‰ × 劳动量，`HOUSEHOLD_LABOR_SHARE_PER_MILLE = 700` 于 `174-179`）；`handicraftRules()`（布 600‰ + **货币工资** `HANDICRAFT_MONEY_WAGE_MILLI = 1_000` 于 `181-191`）；`tenantRules()`（固定实物租 `TENANT_RENT_MILLI_GRAIN = 20_000_000` 于 `194-200`）。 | 无。 |
| `simos-economy/.../time/ProductionSettlement.java:38-49` | **公式表**（判据就是它）：8 行覆盖 `SELF_RETENTION` / `OUTPUT_SHARE`×4 组合 / `FIXED_IN_KIND_PER_LABOR` / `FIXED_IN_KIND_RENT` / `FIXED_MONEY_*`。除法从左到右逐步向下取整（51）；`Σ量 = 0` **归零不除零**（51-52）；**付款上限 = 本周期收到的产出**（`59-61`：实付 = `min(应付, 可用)`，**不抛、不造账、不留索取权**）。 | ★ **"欠"只报成读数、不落债权**：`ProductionSettlement.java:67-69` 明说"这些读数**只读**：本批**不落债权**（'记欠'开关未定）"；`ProductionRelation.java` 同款边界（`90-91` "cohort 侧索取权 …（S2 的 ledger）"）。**⇒ D 与 C 在这里接头：给养欠账今天既不进 `Debt` 也不进 `Claim`，只在当日 `ProductionLedger` 的读数里（当日丢弃）。** |
| `ProductionSettlement.java:511-515` | `recipientOf(...)` —— 受方解析的**唯一处**：`ToActor → actor()`、`ToCohort → HouseholdActors.of(cohort)`（家户就是那批人的 actor）。类注 73-77："D1-A 的红利：所有受方都是 actor"。 | 无。 |
| `ProductionSettlement.java:105-117 / 416` | `TransferMint` 铸造口：结算只回答"这条转移**是什么**"，id（`tr-<day>-<seq>`）与日号由当天的 `ProductionLedger.Accumulator` 盖。 | 无。 |
| `EconomySettlement.harvest:3002-3098` | **产出计提**：毛产 = `scale × outputPerUnit[j] × 1000`（3026）；损耗 = 毛产 × `(FEED_PER_MMILLE + DEPRECIATION_PER_MILLE)/1000`（3032，`FEED=0` 于 `247`、`DEPRECIATION=30` 于 `256`）；**净产进产出计提（+净产 → operator）**（3041-3042）—— 类注 3040：**它不是一条转移**（产出是造出来的、没有对端）。3016-3017：`scale` 读 `industry.capacity()`（K3 起行里没有生产资料）。3046：同一笔计提**也记进会话副本**（H5 收口）。3051-3054：缺 `relation` ⇒ 产出全部留在 operator（等价路径）。3055-3056：`requireCohortRows` **fail-closed**（受方家户必须存在且住这一格 —— 判在所有公式之前）。 | 无。 |
| `EconomySettlement.drawCycleInputs:1377-1415` | **现扣投入**（H6-lite 三遍式）：① `surveyInputDemands`（**只读**，1389-1398）；② `rationContestedInputs`（**同格争用 ⇒ 开池 + 按需求比例配给**，最大余数法，1399-1400）；③ `drawOneIndustryInputs`（逐产业落账，需求上限 = 配给额，1401-1414）。 | 无（H6-lite 已收口"先到先得"）。★ 这是全仓**唯一**已有的"同一格内两个主体争同一批库存"的仲裁机制 —— 但它是**投入**的口径，不是市场的。 |

#### ★ 回答：三件事今天各写在哪里？有没有明确的字段/类型？

| 三件事 | 今天的落点 | 有没有明确字段/类型 |
|---|---|---|
| **① 谁拥有产品** | `ProductionRelation.residualOwner`（字段，`ProductionRelation.java:84/107-109`）+ `rules[].recipient`（`Recipient` sealed，`511-515` 解析） | ★★ **有**，且是**数据**（改一条关系就换归属）。 |
| **② 谁负责供料** | `ProductionRelation.inputSupplier`（字段，`81-82`；缺省 `ToActor(operator)`，`100-103`；四档默认在 `RegimeRelations.defaultInputSupplier:318-333`） | ★★ **有**（H3/C3 起），是**数据**。 |
| **③ 谁承担实物给养义务** | **散在规则表里**：`RegimeRelations.feudalRules()` 的一条 `RuleType.FIXED_IN_KIND_PER_LABOR`（常量 `FEUDAL_SUBSISTENCE_MILLI_PER_LABOR = 144` 于 `165-171`），经 `ProductionSettlement` 的公式表第 6 行（`46`）算成 `⌊本受方劳动 ÷ 1000⌋ × fixedAmount`，实付走 `Transfer`，**欠额只进当日 `RuleSettlement` 读数** | ★★ **没有**。它既不是 `ProductionRelation` 的字段、也不是具名类型；`FIXED_IN_KIND_PER_LABOR` 是**一个通用的公式档**，"给养"只是 feudal 用它的**一种用法**。⇒ **没有人能回答"这个主体对哪些家户、还欠多少给养"**：受方集合要从 `rules` + 劳动账现算，应付/欠只在当日 ledger 读数里（**当日丢弃**，类注 `59-61`、`67-69`）。 |

**⇒ D 的结论（对 M1.7 要紧）：① 与 ② 已经"明确成字段"，M1.7 只需**扩**（occupations / useRights / laborSource）；③「实物给养义务」是**唯一真的没有落点**的一件 —— 它是"某条通用规则"，不是"一项可查询的义务"。若 M1 要"明确给养义务"，它是一个**新类型**（或 `Claim` 的一个 `kind`），落点应与 M1.5 的 `Claim` 合并考虑。**

---

### E. 统一查询与"不可重复使用"

#### ★ 回答（一）：今天读"某主体有多少可支配库存"要经过哪些路径？

**全部路径（6 类，互相之间没有统一入口）**：

| # | 路径 | 落点 | 说明 |
|---|---|---|---|
| 1 | **生产侧的会话副本（唯一的"结算用"路径）** | `OwnershipBooks.loadHouseholdGoods:161` / `loadHouseholdMoney:260` / `loadOperatorGoods:385` / `loadOperatorMoney:401` | 由两个协调器调用：`PopulationEconomyTimeParticipant.java:161/166/170/172`、`EconomyOwnershipTimeParticipant.java:149/154/158/160`。载入后由 `EconomyDayStepper` 就地改（类注 29-49）。落回：`landHouseholdGoods:210` / `landHouseholdMoney:301` / `landOperatorGoods:427` / `landOperatorMoney:465`。 |
| 2 | **条目落账（产权条目 → 账）** | `OwnershipBooks.apply:101-135` | 键**自拼** `new GoodsAccountKey(entry.actor(), entry.location())`（106）；`fold:85-99` 是"当天的账 → 条目"的**唯一**折算点。 |
| 3 | **GUI / MCP 读口** | `ApiViews.accountsAt:681-690`（**遍历 `actors.accounts().values()` 再按 `location` 过滤**）、`ApiViews.classRowView:798`（单本直查，经 `OwnershipBooks.accountKeyOf`）、`ApiViews.economyOwnership:683`（逐格汇总）、`ApiViews.economyHex:410` | MCP 工具 `EconomyHexTool.java:92`、`EconomyOwnershipTool.java:98` 都走 `ToolSupport.economyHex:722` / `economyOwnership:730`，**与 GUI 同一个 `ApiViews` 函数**（视图只有一份）。 |
| 4 | **地址解析（只判存在，不读余额）** | `simos-actor/.../resolve/ActorResolver.java:112-121` | `resolveGoods`：`GoodsAccountKey.parse(name)` → `data.accounts().containsKey(key)`。 |
| 5 | **播种 / 合并** | `HouseholdSeeder.java:188-213`（`books(...)` 三参重载播经营者账）、`ActorSeedHandler.java:93 / 106`（`merge(base.accounts(), seeded.accounts())`） | |
| 6 | **测试助手** | `EconomyOwnershipFixture`（真协调器推进）、`EconomyTestWorld`、`PopulationEconomyFixture`、`EconomyMoneyInvariantTest:184 probe`、`EconomyRealScaleSeedBottleneckTest:443`（`for (... : books.accounts().entrySet())`）、`S1Stage3TenancyTest:148-158`、`WorldgenInitializeToolTest:670/676`、`GuiApiTest:861` | 测试侧**各自遍历/直查**，无共用入口。 |

计数证据：`grep -rn "\.accounts()" simos-*/src/main` 命中 **23 处**（`OwnershipBooks` 11、`ApiViews` 2、两个协调器 4、`ActorResolver` 1、`ActorSeedHandler` 2、`ActorChangeSet` 2、`ActorCodec` 之外 1）；测试侧另有 **10 个文件**、共 61 处。

#### ★ 回答（二）：有没有一个统一的入口？

**没有。** 三条证据：

1. **唯一被收口的东西是"键的拼法"，不是"读法"**：`OwnershipBooks.accountKeyOf:339-341` 是家户账键的唯一拼写点（类注 336-338、`ApiViews:795-797` 明确"本层不复述家户 id / 账户键的形状"）。但 `ActorResolver:113` 与 `OwnershipBooks.apply:106` 各自拼键，`ApiViews.accountsAt:683` 干脆**不复述键、直接遍历全表**。
2. **没有任何 `availableStockOf(...)` / `disposableOf(...)` 之类的函数**：`grep -rniE "availableStock|disposable|sellableOf|spendableOf"` 只命中 `MarketSettlement.sellableOf:348-360`（**私有**，市场专用）与 `lendableOf`（**私有**，放贷专用）⇒ **"可支配"这个算式在仓里存在两次、都是私有的、都不通用**，且**口径不同**（一个留整周期自需、一个留整周期自需×1000‰ —— 数值同、语义不同，类注 `MarketSettlement:61-65` 特意说明"两种风险不该共用一个数"）。
3. **`EconomicsData` / `GoodsAccount` 都**没有**"可支配"概念**：`GoodsAccount` 只有 `balances`/`money` 两个总量；"可支配"今天的定义**散在调用方**（谁想读就自己遍历 `accounts()` 并自己扣）。

**⇒ 用户裁定第 4 条（统一的可支配库存查询）是**完全空白**。**★ 而"按经济用途动态保留"的算式（`可售库存 = max(0, 持有库存 − 已冻结库存 − 必要生产投入 − 生活保留)`，各项**互不重复扣除**）今天**没有任何落点**：`grep -rn "按经济用途动态保留|可售库存 = max|必要生产投入|生活保留"` 全仓**零命中**（见 §5 第 4 条）。

#### ★ 回答（三）：今天有没有任何机制阻止"同一笔余额/库存被两个市场同时用"？

**没有任何显式机制。今天不出问题靠三条结构性事实（不是靠守卫）。**

**为什么今天不出问题（三条，逐条给证据）**：

1. ★★ **库存在"格"上分层** —— `GoodsAccountKey(owner, location)`（`GoodsAccountKey.java:42`，类注 7-11："少写 `location` ⇒「全境有多少粮」与「这一格有多少粮」混成一件事"）。⇒ **同一主体在两格是两本账**，跨格的库存共享在类型上就不可能发生。
2. ★★ **市场每周期一次、且同格** —— 市场清算挂在 `anyCycleClosed` 这个**日级**事实上：`EconomySettlement.java:798-820`（类注 799-800："市场是'这一格这个周期的一次集市' ⇒ 一天之内几个产业同时关账也只开一次市"）；`MarketSettlement` 类注 23："**每周期一次**、**同一格内**"；`clearOncePerCycle(base.markets(), ...)` 按 `base.markets()`（`Map<HexCoord, Market>`）逐格跑。
3. ★★ **单线程就地改会话副本，且可售量取"当时"的余额** —— 唯一 applier `applyTransfer:3123` 直接读**当下**副本余额再扣（`3133` `stockOf(householdGoods, from, ...)`）；`sellableOf:348-360` 也是 `max(0, 当时余额 − reserve)`。⇒ 顺序执行下，"两处同时读到同一份余额"**在时间上不可能**（`EconomyDayStepper` 类注 49 明说"**不共享、不并发**：一次推进一个实例（用完即弃）"）。

**一旦多市场并行会缺什么（五项，逐条给"今天没有"的证据）**：

| # | 缺什么 | 今天的证据 |
|---|---|---|
| 1 | **预留 / 占用（`frozen`）** | 零（§1.A 的三组 grep）。★ 而且现有 `reserve` **明确不是占用**：`EconomySettlement.java:2516` 逐字"保留额不是'冻结起来的一笔粮'，放贷行自己每天照吃不误 —— 它只是'可贷额'的下界"。 |
| 2 | **原子提交（`SettlementBatch`）** | 零（`grep -rniE "SettlementBatch|BatchId|BatchStatus|EntryKind"` 无命中）。★ 且 `applyTransfer` **今天就不是原子的**（`3123-3195`：先扣付方全部腿、再记收方；中间抛错 ⇒ **半笔转移留在可变副本上**）。 |
| 3 | **按市场分区的库存视图** | 零。`load*` 只给**一份全局副本**（`OwnershipBooks:161/260/385/401`），两个市场会各拿同一份。 |
| 4 | **钱的按格分区** | ★ **不完整**：`householdMoney` 键 = `CohortKey`（**含 `hex`** ⇒ 家户的钱天然按格分层）；但 `operatorMoney` 键 = **`ActorRef`（不含格）**（`EconomyDayStepper.java:71-81`、`OwnershipBooks.operatorLocations:357-368` 靠 `IndustryHexKeys` **现算**那一格）。⇒ 同一经营者的钱**可以被多格市场同时读**。 |
| 5 | **版本 / 乐观并发控制** | 零。状态树是 `Command → ChangeSet → Revision`（铁律 2），但**会话副本在结算期间完全脱离这条链**（类注 29-35："同样不进 `EconomyData`"）⇒ 结算期间的副本改动**没有任何 revision 或版本号可查**。 |

---

### F. 测试与门禁基线

#### F.1 `simos-app/src/test/java/io/mosire/simos/app/world/` 下与经济账户有关的用例

目录共 **17 个 .java**（14 个真测试类 + 3 个夹具）。与经济账户直接相关的 **11 个**：

| 文件 | 行数 | 一句话 |
|---|---|---|
| `EconomyMoneyInvariantTest.java` | 516 | ★★★ **H6：钱的唯一常驻护栏** —— 逐币种 Σ 恒定 + 逐本落盘一致 + 钱真的动了 + 计价货币自洽（4 条判据见类注 53-64）。真档规模（1 格 14,806 人 + 1 座 1,777 人城）、推 3 个周期（360 天）。 |
| `EconomySettlementEndToEndTest.java` | 1485 | ★★ R3a 日结算 + R4a 周期收获与分配的端到端验收：真 `EconomyCodec` + 真 `EconomyTimeParticipant` + 真协调器。 |
| `EconomyConservationNetTest.java` | 648 | ★★ **M0.5：三条守恒回归网**（类注明说"在它们绿之前不许动 `MarketSettlement` 与偿还链"）。 |
| `EconomyRealScaleClothTest.java` | 771 | ★★ R3（T4/T5）：城乡各有一个非土地生产，且**真档量级**上真的产出东西。 |
| `EconomyRealScaleSeedBottleneckTest.java` | 518 | ★★ Task 7：真档规模下"第三路瓶颈"（缺种子 ⇒ 投入面积缩 ⇒ 减产）真的在起作用；`:443` 遍历 `books.accounts().entrySet()` 逐本查账。 |
| `EconomyGrainDiagnosisTest.java` | 209 | ★★ M0.3：逐格粮食诊断读口（`ApiViews.economyHex` 的 `grainDiagnosis` 栏），守着 `unavailable` 三项必须**具名**（含 `paymentInstrumentGap` ← M1 的落点）。 |
| `EconomySeederTest.java` | 997 | `EconomySeeder` 的纯函数用例（3 个格）；`:419 initialReservesAreLiteralPerClassDayCounts` 直接钉保留额口径。 |
| `S1Stage3TenancyTest.java` | 220 | ★★ I3.2：租佃档 + `AssetOwner ≠ Operator`（阶段 3 Task 5）；`:148-158` 逐键断言 `accounts()` 里的所有权记录。 |
| `EconomyAllocationConsistencyTest.java` | 98 | ★★ 跨模块一致性：`EconomySettlement.allocate ≡ EconomySeeder.splitProportional`。 |
| `PopulationR4Test.java` | 352 | ★★ R4 端到端：人第一次真的随时间变、经济侧跟着变。 |
| `PopulationSeederTest.java` | 207 | ★★ R1 T4：创世批次列表与"同一份喂两条命令"的构造性一致。 |

夹具（3 个，无 `@Test`）：`EconomyOwnershipFixture.java`（91，真协调器推进夹具 —— **M1 的端到端用例应复用它**）、`EconomyTestWorld.java`（784，5 格小测试世界）、`PopulationEconomyFixture.java`（185）。
其余 4 个与本批无关：`CorridorWorldTest`（120 行 / 6 测试）、`RichWorldTest`（208 / 9）、`T12InfoRedlineTest`（267 / 2）、`PopulationSeederTest`（207 / 9）。

★ 目录规模：**17 个 .java / 7676 行 / 14 个测试类（96 个 `@Test`）+ 3 个夹具**；14 个类的 `Tests run:` 之和**恰为 96**（与 surefire 对得上）。
★★ **只有 4 个类在代码里真的碰 accounts/money/ownership**：`EconomyMoneyInvariantTest`、`EconomyConservationNetTest`、`S1Stage3TenancyTest`、`EconomyRealScaleSeedBottleneckTest`；另有 2 个**经私有 helper 间接碰**（`EconomyRealScaleClothTest:636-645`、`EconomySettlementEndToEndTest:1090-1115`），1 个只碰 hex 视图的钱（`EconomyGrainDiagnosisTest:85-105` 的 `actorMoneyTotal`/`numeraireMoney`）。**其余 7 个与本批无关。**
★ 两个"关键词扫描的假阳性"（写文档时别误算）：`EconomySeederTest:384` 的 `GoodsAccount` 只在**断言描述字符串**里；`RichWorldTest` 的 `owners` 是 `List<RegionId>`（区域归属，不是经济产权）。

★ **`simos-actor` 侧 8 个文件 / 2509 行 / 8 个测试类（104 个 `@Test`）/ 0 夹具**（与 surefire 逐值对上）。与经济账户直接相关的 4 个：`GoodsAccountTest.java`（85 行 / 4 测试，逐值判据）、`ActorRoundTripTest.java`（611 / 21，铁律 5 反射守卫 + `:94 hasSize(3)`）、`ActorInvariantsTest.java`（275 / 16，构造期不变量 + 保序冻结）、`ActorCodecTest.java`（710 / 25，JSON 往返 + `:240` 冻结线格式）。

★ **`simos-economy` 侧 15 个文件 / 7742 行 / 14 个测试类（171 个 `@Test`）+ 1 夹具**（`EconomyFixtures.java`，178 行）。M1 最相关的 4 个：`EconomyRoundTripTest`（388 / 5，铁律 5）、`EconomyCodecTest`（654 / 15，JSON 往返 + `OptionalLong lastClosedCycle` + 旧档兼容）、`EconomyDebtTest`（630 / 11，V6 债务聚合 + §7.1 三条放贷规则）、`ProductionSettlementTest`（717 / 16，公式表 + `priority` 次序）。

#### F.2 `EconomyChangeSet` 今天恰几个 `FieldDelta`？

**恰 9 个。**（`simos-economy/.../change/EconomyChangeSet.java:54-62`）

组件名清单（按 record 声明序）：
1. `meta` — `FieldDelta<EconomyMeta>`
2. `industries` — `FieldDelta<Industry>`
3. `classes` — `FieldDelta<ClassRow>`
4. `debts` — `FieldDelta<Debt>`
5. `flows` — `FieldDelta<FlowRow>`
6. `laborSupply` — `FieldDelta<LaborSupply>`
7. `allocations` — `FieldDelta<LaborAllocation>`
8. `relations` — `FieldDelta<ProductionRelation>`
9. `markets` — `FieldDelta<Market>`

守卫：`simos-economy/src/test/.../change/EconomyRoundTripTest.java:164` `assertThat(EconomyChangeSet.class.getRecordComponents()).hasSize(9);`（方法名 `changeSetHasExactlyNineComponents`，类注 155-162 明说"这个名字里的数字**故意写死**（H4 从 `Eight` 改成 `Nine`）：它就是'又加了一个状态组件'这件事在编译/测试面上的**唯一提醒**"），外加双向 `isSubsetOf`（165-170）+ `theExclusionListIsEmpty:152`（豁免集必须为空）。

★ 对照：**`EconomyData` 也是 9 个组件**（`EconomyData.java:104-116`：`meta / industries / classes / debts / flows / laborSupply / allocations / relations / markets`）—— 两边组件集**逐一对应**。
★ 对照：**`ActorChangeSet` 恰 3 个**（`meta / actors / accounts`，`ActorChangeSet.java:38-40`），守卫 `ActorRoundTripTest.java:94 hasSize(3)`。

#### F.3 全仓测试数基线

**与 M0 批收尾的实测一致：11 模块 / 2467 条 / 0 失败 / 0 错误 / 0 跳过。**
（`target/surefire-reports` 目录**存在** —— 11/11 全部存在，共 283 个 `.txt`；报告 mtime 2026-09-27 20:01–20:06，与 HEAD `b467049a` 同批。）

| 模块 | surefire 目录 | Tests run | Failures | Errors | Skipped |
|---|---|---|---|---|---|
| `simos-util` | 存在（24 txt） | 199 | 0 | 0 | 0 |
| `simos-map` | 存在（39 txt） | 379 | 0 | 0 | 0 |
| `simos-social` | 存在（20 txt） | 188 | 0 | 0 | 0 |
| `simos-unit` | 存在（21 txt） | 305 | 0 | 0 | 0 |
| `simos-sd` | 存在（23 txt） | 188 | 0 | 0 | 0 |
| `simos-actor-api` | 存在（1 txt） | 8 | 0 | 0 | 0 |
| `simos-actor` | 存在（8 txt） | 104 | 0 | 0 | 0 |
| `simos-economy-api` | 存在（5 txt） | 26 | 0 | 0 | 0 |
| `simos-economy` | 存在（14 txt） | 171 | 0 | 0 | 0 |
| `simos-core` | 存在（31 txt） | 215 | 0 | 0 | 0 |
| `simos-app` | 存在（97 txt） | 684 | 0 | 0 | 0 |
| **合计** | **11/11 存在（283 txt）** | **2467** | **0** | **0** | **0** |

★ **未跑 Maven**（按用户指令）。以上数字来自**只读求和** `target/surefire-reports/*.txt` 的 `Tests run:` 行（283 个报告**每一个**都是 `Failures: 0, Errors: 0, Skipped: 0`），并与并行的独立清点结果**逐值一致**。
★ 282 个测试类 vs 283 个 `.txt`（`simos-app/.../access/ScopeFixtures.java` 只在 Javadoc 里提到 `@Test`，是纯夹具，无报告）—— 与 `.txt` 计数对得上。

---

## 2. 「已经有 vs 还缺」清单（M1 五项交付物）★★ 最重要

> 这一节的目的：**防止重复实现已经存在的东西**。

### 交付物 1：稳定主体身份、多币种账户、余额与冻结额

**判定：部分有（前两项 + 余额已有；冻结额完全没有）**

| 子项 | 判定 | 证据 |
|---|---|---|
| 稳定主体身份 | ★★ **已有** | `ActorRef(kind, id)`（`ActorRef.java:23`）+ 7 档 `ActorKind`（`ActorKind.java:29-42`）+ 家户身份 `CohortKey(hex, residence, stratum)`（`CohortKey.java:37`）+ 两者互逆的唯一拼写点 `HouseholdActors`（`39-80`）。铁律 1 已满足，`GoodsAccount`/`Debt`/`ProductionRelation` **都不另造 id**。**M1.1 在这件事上无事可做。** |
| 多币种账户 | ★★ **已有** | `GoodsAccount.money` = `Map<CurrencyId, Long>`（`GoodsAccount.java:66`），与商品**同住一本账**（裁定 K15/H4）；`Market.numeraire` 每格单一计价货币（`Market.java:39`）；会话副本 `householdMoney`/`operatorMoney`（`EconomyDayStepper.java:61-81`）。**"多币种"在容器层面已经成立**（`Map` 的键就是币种）。 |
| 余额 | ★★ **已有** | `GoodsAccount.balances` / `GoodsAccount.money`，构造期非负守卫（`94-113`）、0 保留（`44-46`）、保序不可变（`89-116`）。**唯一真源**这一条在商品上已成立（R6/D2-A：`simos-ledger` 整个模块已退役，对面没有那个类了 —— `GoodsAccount` 类注 34-37）。 |
| **冻结额** | ★★ **完全没有** | 三组 grep：`frozen` 零经济语义命中；`reserve` 只有两条**只读算式常量**（`MarketSettlement:106`、`EconomySettlement:313`）且 `EconomySettlement:2516` 明说"不是冻结起来的一笔粮"；`held/lien/escrow/pledge/earmark` 零命中。**无字段、无类型、无写入口、无读口。** |

### 交付物 2：债权债务同源、双方可查询

**判定：部分有（"同源"在数据上已有；"双方可查询"只有债务人一侧）**

| 子项 | 判定 | 证据 |
|---|---|---|
| 债权债务**同源** | ★★ **已有** | ★ 这一点比用户预期好：`Debt` **一条记录同时带** `debtor` 与 `creditor`（`Debt.java:39-47`），且 `EconomyData` 构造期**两侧都必判**存在（`214-222`）。⇒ "一笔债两视图同源"的**数据基础已经存在**，M1.5 **不需要重造债权模型**。 |
| 双方**可查询** | ★★ **没有（债权人侧）** | 四段证据链（§1.C 末）：① `ClassRow.debts` 只装 `DebtId`（`ClassRow.java:60`）；② `lendDeficits:2479` **只**给债务人行追加；③ 两个读口（`ApiViews:446`、`ApiViews:804`）与偿还循环（`2642`）**都遍历 `row.debts()`**；④ 利息只 `merge(debt.debtor(), ...)`（`3539`），类注 `3518-3519` 明说债权人侧不记，理由是 `FlowRow.income` 是粮口径、加了会破坏守恒。 |

### 交付物 3：明确产品归属、供料责任和实物给养义务

**判定：部分有（前两项已"明确成字段"；第三项散在规则里，无具名类型、无应付余额、无读口）**

| 子项 | 判定 | 证据 |
|---|---|---|
| 产品归属 | ★★ **已有** | `ProductionRelation.residualOwner`（字段，`84/107-109`）+ `rules[].recipient`（sealed `Recipient`，解析唯一处 `ProductionSettlement.recipientOf:511-515`）。**是数据**（改一条关系就换归属），且 `RegimeRelations.defaultRelation:293-294` 给了四档默认（`residualOwner = operator`，裁定 E9）。 |
| 供料责任 | ★★ **已有**（H3/C3 新建） | `ProductionRelation.inputSupplier`（字段，`81-82`），缺省 `ToActor(operator)`（`100-103`），四档默认在 `RegimeRelations.defaultInputSupplier:318-333`。类注 `48-62` 给了"从按人口猜到制度明说"的理由。**实现边界如实记在类注 `74-77`**（聚合供方由该产业名下家户账代理）。 |
| **实物给养义务** | ★ **部分有 / 基本没有** | 公式档存在（`RuleType.FIXED_IN_KIND_PER_LABOR`，`ProductionSettlement` 公式表第 6 行 `46`；feudal 的取值 `FEUDAL_SUBSISTENCE_MILLI_PER_LABOR = 144` 于 `RegimeRelations:165-171`），**实付也真的在发生**。但：**没有具名字段/类型**（"给养"只是某条通用规则的一种用法）、**没有应付余额**（`ProductionSettlement:59-61`：上限只看本周期，"不抛、不造账、**不留索取权**"）、**没有读口**（欠额只进当日 `RuleSettlement` 读数，而 ledger **当日丢弃**）。⇒ **答不出"这个主体对哪些家户、还欠多少给养"。** |

### 交付物 4：为家户、经营者等主体提供统一的可支配库存查询

**判定：完全没有**

| 子项 | 判定 | 证据 |
|---|---|---|
| 统一入口 | ★★ **没有** | 至少 **6 类路径**各自遍历 `ActorData.accounts()`（§1.E 表）。唯一被收口的是**键的拼法**（`OwnershipBooks.accountKeyOf:339-341`），不是读法。 |
| "可支配"算式 | ★★ **没有（且已存在两份私有副本，口径还不一样）** | `grep -rniE "availableStock\|disposable\|spendableOf"`；`sellableOf` 于 `MarketSettlement:348-360`（**私有**，留整周期自需）、`lendableOf` 于 `EconomySettlement:2519-2530`（**私有**，留整周期自需×1000‰）。类注 `MarketSettlement:61-65` 特意说明"借贷是借出去要还的、市场是卖出去就没了 —— 两种风险不该共用一个数"。 |
| 按经济用途动态保留 | ★★ **没有** | ★ 用户裁定里那条算式（`可售库存 = max(0, 持有库存 − 已冻结库存 − 必要生产投入 − 生活保留)`，各项**互不重复扣除**）在仓里**零命中**：`grep -rn "按经济用途动态保留\|可售库存 = max\|必要生产投入\|生活保留"` → 0；`grep -rn "按经济用途动态保留"` → 0（见 §5 第 4 条）。 |
| 经营者不能因关联人口再替这批人口扣一次完整口粮 | ★★ **没有守** | 今天"经营者"与"家户"是**两套账、两套副本**（`operatorGoods`/`operatorMoney` 键 = `ActorRef`；`householdGoods`/`householdMoney` 键 = `CohortKey`），保留额只在**家户**侧的 `sellableOf`/`lendableOf` 里算 ⇒ 结构上还没有"经营者替家户重复扣一次"的路径。★ **但也没有任何东西阻止它发生** —— 一旦按裁定引入"经营者自己的生活保留/必要投入"，"同一批人口被扣两次"就变成一个**没有守卫的新风险**。 |

### 交付物 5：保证多个市场不能重复使用同一余额或库存

**判定：完全没有（今天不出问题靠三条结构性事实，不是靠机制）**

| 子项 | 判定 | 证据 |
|---|---|---|
| 预留 / 占用 | ★★ **没有** | 见交付物 1 的"冻结额"一行。 |
| 原子提交 | ★★ **没有，且今天就有半笔风险** | 无 `SettlementBatch`；`applyTransfer:3123-3195` **先扣付方全部腿、再记收方** ⇒ 中间抛错留下**半笔转移**在**可变**副本上。 |
| 分区视图 | ★★ **没有** | `load*` 只给一份全局副本（`OwnershipBooks:161/260/385/401`）。 |
| 钱按格隔离 | ★ **部分有（家户有、经营者不完整）** | 商品与家户的钱按 `location`/`hex` 天然分层（`GoodsAccountKey:42`、`CohortKey:37`）；但 `operatorMoney` 键 = `ActorRef`（不含格），格靠 `operatorLocations:357-368` **现算**。 |
| 版本 / 乐观并发 | ★★ **没有** | 会话副本在结算期间**脱离** `Command → ChangeSet → Revision` 这条链（`EconomyDayStepper` 类注 29-35）。 |
| 今天为何不出问题 | —（三条结构性事实） | ① 库存在格上分层（`GoodsAccountKey:42`）；② 市场每周期一次且同格（`EconomySettlement:798-820` + `MarketSettlement` 类注 23）；③ 单线程就地改副本、可售量取"当时"的余额（`applyTransfer:3133`、`sellableOf:348-360`、`EconomyDayStepper` 类注 49"不共享、不并发"）。 |

---

## 3. 一处一处的「落点建议」（不含代码）

> 格式：**改哪个文件 / 加哪个类型** + **理由**。按 M1.1–M1.8 组织（见 §4 的逐任务表给"今天到哪一步"）。

1. **M1.1 `CurrencyDef` + `MoneyInstrument`**
   - **落点**：新增 `simos-economy-api/.../money/CurrencyDef.java`、`InstrumentId.java`（走 `id/` 包，与既有 22 个 ID 同族）、`InstrumentKind.java`（enum: `SPECIE / STATE_NOTE / BANK_DEPOSIT`）、`MoneyInstrument.java`。
   - **理由**：`money/` 包**已存在**（`MoneyAuthority` / `MoneyIssuance` / `package-info`），是天然的落点；`InstrumentId` 放 `id/` 是因为全仓 22 个 ID 都在那里且 `EconomyIdsTest` 已经有一套往返用例的形制（**新 ID 加进去就自动被守**）。
   - ★ **不要**把 `MoneyInstrument` 做成 `CurrencyId` 的子类型或替换 `CurrencyId`：`CurrencyId` 是 `GoodsAccount.money` 的键（`GoodsAccount.java:66`）、`Transfer.money` 的键、`Market.numeraire` 的类型，**三处都在用** ⇒ 替换会牵动 actor/economy/app 三片。裁定口径是"旧的 `CurrencyId` 读口保留"（master plan:216），所以 `MoneyInstrument` 是**新增的一维**，`CurrencyId` 留在原位。
   - ★ **发行累计量的落点**：`MoneyIssuance.REGISTERED`（`MoneyIssuance.java:34`）的类注 24-25 已经指名"将来的注册点是**这里**（一处拼写点）"。M1.6 的 `累计发行 − 累计注销` 应挂在它旁边，而不是新造一个全局表。

2. **M1.2 `FinancialAccount` + 冻结**
   - **落点（数据）**：`FinancialAccount(AccountId, ActorRef owner, InstrumentId instrument, long balance, long frozen)` 放 `simos-economy-api/.../money/`。**`AccountId` 已经存在**（`simos-economy-api/.../id/AccountId.java`，今天只被 `EconomyIdsTest` 引用）⇒ **用它，不要另造**。
   - **落点（状态组件）**：`EconomyData` 加第 10 个组件（master plan:225 已裁）。**必然要同步改三处**：`EconomyChangeSet`（第 10 个 `FieldDelta`）、`EconomyCodec`、`EconomyPayloads`；`EconomyRoundTripTest:164` 的 `hasSize(9)` 与**方法名**都要改（类注 155-162 明说那个数字是"故意写死的提醒"）。
   - **落点（守卫）**：`0 ≤ frozen ≤ balance` 写在 `FinancialAccount` 的构造期，照 `GoodsAccount:79-117` 的形制（逐值判、当处抛、`LinkedHashMap`+`unmodifiableMap` 冻在赋值处）。
   - ★★ **落点（边界，最重要）**：`frozen` **只承载决策层已明确下达的占用**。**不要**把 `MarketSettlement.MARKET_SELF_RESERVE_PER_MILLE:106` / `EconomySettlement.LENDER_SUBSISTENCE_RESERVE_PER_MILLE:313` 折进 `frozen` —— 那两条是"决策层自己算的可售下界"（`EconomySettlement:2516` 逐字"不是冻结起来的一笔粮"），折进账户层就是**把储备政策搬进账户模型**，直接违反用户裁定。
   - ★ **落点（与 `GoodsAccount.money` 的关系）**：M1.3 与 M1.2 是同一个决定的两半（见下）。`FinancialAccount` 与 `GoodsAccount.money` **不能两处可写**：若 `FinancialAccount` 成为权威，`GoodsAccount.money` 必须退化成**投影**（master plan:230 已裁："保留为**兼容读口**，底层**只留一份权威**"）。

3. **M1.3 唯一余额权威**
   - **落点（类型）**：`simos-actor/.../model/GoodsAccount.java`。★ **首选做法是让"钱 = 空表"这个缺省在类型上不可表达** —— 即**删掉两参便捷构造器**（`75-77`）。今天的危险不在于"两处可写"，而在于**任何新写入点用两参构造器就会静默把钱清零**（`OwnershipBooks:129-132` 为此专门写了警告注释）。
   - **落点（写入路径）**：`OwnershipBooks` 的 4 个 `land*`（`210/301/427/465`）+ `apply:101-135`（`131-132` 的"钱原样带过"）+ `EconomySettlement.applyTransfer` 的货币腿（`3152-3170` / `3243-3264`）。
   - ★ **落点（不对称的一处）**：`landOperatorMoney:491-495` 与 `landHouseholdMoney:323-328` 的失败口径**必须统一**。家户那条在账本缺席时抛（防顺序反了），经营者那条静默新建"商品为空"的账 —— 后者在顺序写反时会**静默产出**一本商品为 0 的账（§5 第 7 条）。
   - ★ **判据落点**：新增"新旧两条读路逐值一致"的对拍用例。**必须自己写，不能指望既有用例** —— `GoodsAccount.java:72-73` 声称"钱有没有被序列化丢由 `ActorCodec` 的往返用例守着"，但**那个断言不存在**（§5 第 1 条）。
   - ★ **注意 `ActorCodec`**：它为 `ActorRef` / `GoodsAccountKey` / `CommodityId` 注册了 key deserializer（`ActorCodec.java:94-96`），**没有为 `CurrencyId` 注册**（而 `money` 的键就是它）。今天靠 Jackson 对单 String 构件 record 的隐式处理通过（类注 88 说"今日与'不注册'行为等价"），但**没有任何用例证明它**。M1.3 的对拍用例应先覆盖这一条。

4. **M1.4 预留 + 原子结算**
   - **落点**：新增 `simos-economy-api/.../settle/` 包：`BatchId`（`id/` 包，同族）、`BatchStatus`、`EntryKind`、`SettlementBatch`、`Entry`。
   - ★★ **落点（唯一 applier 不许动）**：master plan:241 已裁"`applyTransfer` 仍是**唯一写口**（不新增第二个 applier）"。⇒ `SettlementBatch` 的"一次提交全部分录"**必须**以现有 `applyTransfer:3123` 为唯一的落账原语 —— 建议的做法是把它变成**两阶段**（先全量验证、再全量落），**而不是**在旁边新写一个 applier。
   - ★★ **落点（真正的病灶）**：`applyTransfer:3123-3195` 今天**不是原子的**：付方逐腿扣（`3132-3148` 商品、`3152-3170` 货币）在收方记账（`3182-3194`）**之前**，中间抛错就留半笔（§5 第 5 条）。**M1.4 的最小任务 = 让"验证"与"落账"分成两遍**（照 `drawCycleInputs:1377-1415` 的 H6-lite **三遍式**先例：调查 → 配给 → 落账），这样"半途失败不留半笔"变成结构性成立，而不是靠调用方自觉。
   - ★ **动机要改写**：master plan:240 写的流程（"冻结资源 → 验证双方与（将来的）路线条件 → 一次提交 → 释放"）今天**没有可服务的并发对手**（只有一个市场、每周期一次、且副本不并发）。**M1.4 的动机应从"多市场并发"改写成"单次结算内的原子性"**（§5 第 5 条）—— 否则会被误判为"没有需求"而跳过。

5. **M1.5 `Claim` 双向视图**
   - **落点（类型）**：新增 `simos-economy-api/.../claim/` 包：`Claim`、`ClaimState`（`ACTIVE / OVERDUE / RESTRUCTURED / WRITTEN_DOWN / EXTINGUISHED`）、`AssetRef`。**`ClaimId` 已经存在**（`simos-economy-api/.../id/ClaimId.java`，且已被 `Transfer.settles:93` 用为类型）⇒ **用它，不要另造**。
   - ★★ **落点（必须先裁的一条）**：`Transfer.java:76-78` **逐字写着**"`Transfer.settles` 的类型是 `ClaimId`，而 `DebtId` …**两者不是同一种东西**（`Claim` 体系随 `simos-ledger` 在 2026-09-27 退役，`ClaimId` 只作为契约保留）⇒ 把 `DebtId` 硬塞进一个 `ClaimId` 的槽位，等于**凭空发明一套"债 = 债权"的对应关系**"。而 master plan:251 说 M1.5 要"**替换/包裹今天的 `Debt`**"。**⇒ 这两句话直接冲突，M1.5 开工前必须先裁 `DebtId` 与 `ClaimId` 的对应关系**（§5 第 2 条）。
   - **落点（债权人侧账）**：三个候选，按侵入度排序 —— ① `ClassRow` 加一个**反向索引**（对称于 `debts:60`）；② 一个独立的 `Map<ActorRef, ...> claims` 组件（`EconomyData` 第 11 个）；③ 把 `Claim` 做成**独立表**（`Map<ClaimId, Claim>`）并让 `ClassRow.debts` 退化为投影。★ **推荐 ③**：因为"双方可查询"要求两侧都能按主体查，而 `Claim` 独立表 + 两个方向索引比"在 `ClassRow` 上加对称字段"更接近"同一份事实只写一处"。
   - ★★ **落点（最难的一处：债权人侧的"应收"往哪放）**：`FlowRow` 是**逐家户一行**、且 `income`/`consumed` 是**粮口径**（`FlowRow.java:47-56`），`chargeInterest` 类注 `3518-3519` 明说"往债权人行加'利息收入'会让守恒式**当场不成立**"。⇒ **应收/利息收入不能进 `FlowRow`**，必须是 `Claim` 表自己的字段（`principalAmount` / `accruedInterest`），并在守恒式里**另立一项**。这是 M1.5 的真正工作量，比"加个索引"大。

6. **M1.6 逐工具守恒**
   - **落点（判据）**：`simos-app/src/test/.../world/EconomyMoneyInvariantTest.java` 扩写（master plan:257）。今天的 4 条判据（类注 `53-64`）里，`moneyTotalPerCurrencyIsConstantThroughAllThreeCycles:249` **正是要改的那一条**：从"Σ 恒定"改成 `Σ持有账户 = 创世 + 累计发行 − 累计注销`（本阶段发行=注销=0 ⇒ 退化为今天的形态）。
   - **落点（分栏）**：`ApiViews` 的货币合计栏（`ApiViews.java:565-570` 的 `numeraireMoney`、`economyOwnership` 的 `actorMoneyTotal`）加"私人流通 / 全部基础货币 /（将来）银行存款"分栏。
   - ★ **落点（一条要明写的否定判据）**：类注 `66-69` 今天的措辞是"**两者之和**逐币种恒定"—— 这是 M1.6 要**限量**的那条。应改为"逐工具守恒"，并**明写没有"全世界总量永远不变"这条总不变量**（master plan:258）。
   - ★ **依赖**：M1.6 的"逐工具"需要 M1.2 的 `FinancialAccount` 先存在（否则"持有账户"集合只有一个 —— `GoodsAccount.money`）。⇒ 次序不能倒。

7. **M1.7 关系面**
   - **落点（类型）**：`simos-economy-api/.../relation/` 新增 `AssetOccupation` / `UseRight` / `LaborSource`（含 `LaborSource` 的四档：`FAMILY_SELF / TENANCY / DEPENDENT / EMPLOYED(employer, laborMilli, rewardRule)`）。`relation/` 包**已存在**（`Basis` / `CompensationRule` / `Pool` / `ProductionRelation` / `Recipient` / `RuleType` / `Weight`）⇒ 同族新增。
   - **落点（`ProductionRelation` 扩 3 个组件）**：`simos-economy-api/.../relation/ProductionRelation.java:86-91`。★ **`inputSupplier` 的"缺省 = operator"先例**（`100-103`，类注 69-72 给三条理由：与四档默认同值、旧档兼容、载荷边缘不必再写一遍缺省）**必须照抄**给新组件 —— 否则旧档 JSON 打不开。
   - **落点（缺省展开）**：`RegimeRelations.defaultRelation:240-295` 加三面的四档缺省；`defaultInputSupplier:318-333` 是"某一面的默认值只有一个拼写点"的**现成范本**。
   - **落点（守卫，`Σ occupations[kind] ≤ capacity[kind]`）**：★ master plan:269 说要"构造期守卫"。**注意落点选在哪**：`ProductionRelation` 看得见自己、**看不见 `Industry`**（`ProductionRelation.java:13-14` 逐字："本类型看得见自己，看不见那张产业表"）⇒ 这条跨表守卫**只能住 `EconomyData`**（照既有 `relation.operator` 与 `Industry.operator` 的跨表守卫先例）。
   - **落点（判据 ②：地租受方 = 权利主体）**：`RegimeRelations` 的规则表（`feudalRules:355-407` 今天把地租给 `CohortKey(hex, 地主)`）—— 这条要改成"按 `useRights` 里的权利主体解析"，落点在 `harvest`（`EconomySettlement:3051-3056` 的 `relations.get(...)` 之后）。
   - **落点（读口）**：`ApiViews.industryView:714-724`（已发 `operator`）与 `EconomyChangeSet`（`ProductionRelation` 是第 8 个组件 ⇒ **它的组件扩了不需要改变更集**，因为 `FieldDelta<ProductionRelation>` 是整值替换；但 **JSON 线格式会变** ⇒ `EconomyCodecTest` 要跟着看）。

8. **M1.8 修 M-4（`participationPerMille` 进计算）**
   - **落点**：`simos-app/.../world/EconomySeeder.java:1083/1132`（病灶：配额预算用 `grossLaborMilli`）+ `simos-economy/.../time/EconomySettlement.java` 的 `reallocateLabor:2707+`（H5 ③ 的劳动再分配，它读 `allocations`）。
   - **度量落点**：`ApiViews:1366-1407` 的 `utilizationPerMille = allocated × 1000 ÷ available`（类注 `1366-1371` 明说"同一批人被两个产业各算一次满额时，占用率会**超过 1000‰**"）—— 修 M-4 后这个读数应该变化，是**现成的判别点**。
   - ★ **不要碰的不变量**：`Σ allocated ≤ available` 是构造期守卫（`EconomyData:323-333`），M1.8 必须保持它绿（master plan:279 已裁）。

---

## 4. 风险清单：改这些地方会牵动哪些既有测试 / 不变量

### 4.1 ★★ 加状态组件 ⇒ 牵动哪个往返用例（**点名**）

| 若你改… | 必然红/必须同步改的用例 | 具体断言 |
|---|---|---|
| `ActorData` 加组件（如 `frozen` 表或 `FinancialAccount` 表） | **`simos-actor/src/test/.../change/ActorRoundTripTest.java`** | `:94 assertThat(ActorChangeSet.class.getRecordComponents()).hasSize(3)` —— **数字写死**，且 `:95-100` 双向 `isSubsetOf` 要求 `ActorChangeSet` 与 `ActorData` 组件集**完全相同**。⇒ 改 `ActorData` 就必须同一次改 `ActorChangeSet`（`ActorChangeSet.java:38-40`）+ 那个 `hasSize(3)` + `ActorChangeSet.apply:74-77` 的逐组件 `rebuild`。 |
| `EconomyData` 加组件（M1.2 的 `FinancialAccount` 第 10 个） | **`simos-economy/src/test/.../change/EconomyRoundTripTest.java`** | `:164 hasSize(9)`（**方法名 `changeSetHasExactlyNineComponents` 也要改** —— 类注 155-162 明说"这个名字里的数字故意写死"，H4 有从 `Eight` 改成 `Nine` 的先例）；`:165-170` 双向子集；`:152 theExclusionListIsEmpty`（**豁免集必须保持为空**，不许把新组件加进 `EXCLUDED_FROM_CHANGE_SET`）。 |
| 上述任一 | **`ActorCodecTest` / `EconomyCodecTest`** | ★★ **`ActorCodecTest:240 theEmptySnapshotHasAFrozenWireShape`** 把空态的**整串 JSON** 钉成字面量：`{"ref":…,"timestamp":…,"data":{"meta":null,"actors":{},"accounts":{}}}` —— **`ActorData` 加组件必然改这串**，这条当场红。`EconomyCodecTest` 同族（本次未逐行读，**未确认**它是否也有整串字面量断言）。 |
| 上述任一 | **`ActorInvariantsTest` / `EconomyInvariantsTest`** | 构造期不变量 + 保序冻结（`ActorInvariantsTest:148/234/243-266` 钉 `accounts()` 的插入序与不可变；`EconomyInvariantsTest:698 cycleInputKeepsInsertionOrderIsFrozen`）。 |
| `GoodsAccount` 组件（**M1.3 若给 `frozen` 加在这里**） | **`GoodsAccountTest.java`**（85 行，逐值判据）+ **`ActorRoundTripTest:148/212/258/429/486/511/556/590`**（大量两参构造器调用点，**删两参构造器会全红**）+ `ActorCodecTest:409/562/692` + `ActorInvariantsTest:273` + `ActorResolverTest:61` + `S1Stage3TenancyTest:136` + `GuiApiTest:861` | ⇒ ★ **这就是"删两参构造器"的代价清单**：main 侧只有 1 处（`OwnershipBooks:132` 用的是**三参**，两参只在测试里），但**测试侧有 15+ 处**要改。**这是 M1.3 的主要工作量，不是生产代码**。 |
| `ProductionRelation` 组件（M1.7 加 3 面） | **`RegimeRelationsTest` / `EconomyRoundTripTest` / `ProductionSettlementTest`** | `ProductionRelation` 是 `EconomyData` 第 8 组件（`FieldDelta<ProductionRelation>` **整值替换** ⇒ 组件数不变、`hasSize(9)` **不红**）；但 ① 构造期若把 `inputSupplier` 式缺省漏掉 ⇒ **旧档 JSON 打不开**（`EconomyCodecTest`）；② `RegimeRelationsTest` 钉四档规则表；③ `ProductionSettlementTest` 钉公式表。 |

### 4.2 按缺口点名的测试风险

| 改动 | 会牵动的测试类 | 为什么 |
|---|---|---|
| **M1.1** 加 `CurrencyDef`/`MoneyInstrument` | `EconomyIdsTest`（`simos-economy-api`，新 ID 加进去自动被守）、`RegimeRelationsTest`（`DEFAULT_CURRENCY:137`）、`EconomySeederTest`（`MARKET_NUMERAIRE:378`）、`EconomyMoneyInvariantTest:355 theHexMoneyCurrenciesCoverTheMarketNumeraire` | `silver` 的**两个拼写点**（`RegimeRelations.DEFAULT_CURRENCY` ← `EconomySeeder.MARKET_NUMERAIRE`）必须收敛成"一个 `CurrencyDef` + 一个 `SPECIE` 工具"，而 `Market.numeraire` 的类型是 `CurrencyId` ⇒ **改 `numeraire` 的类型会牵动 `MarketSettlement` 全部 5 处 `numeraire` 形参**（`151/172/218/261/294/328/378/391`）。 |
| **M1.2** 加 `FinancialAccount` | `EconomyRoundTripTest`（见上）、`EconomyCodecTest`、`EconomyInvariantsTest`、`EconomySeedHandlerTest`、`WorldgenInitializeToolTest` | 新组件 ⇒ 变更集 + codec + payloads + 读口四处同步（master plan:226 已列）。 |
| **M1.3** 唯一余额权威 | ★★ **`EconomyMoneyInvariantTest`（4 条判据全在 `OwnershipBooks` 的 8 个 `load*`/`land*` 上）**、`ActorPayloadsTest`（`:76-77/105` 钉 `accounts()` 落盘）、`ActorCodecTest`、`EconomyRealScaleSeedBottleneckTest:443-496`、`HouseholdSeeder`/`ActorSeedHandler` 的用例 | `EconomyMoneyInvariantTest` 类注 `41-45` **逐字**说"app 侧两次落账的唯一入口是 `OwnershipBooks`（`loadHouseholdMoney`/`landHouseholdMoney`/`loadOperatorMoney`/`landOperatorMoney`），协调器是 `PopulationEconomyTimeParticipant`/`EconomyOwnershipTimeParticipant`" ⇒ **这 4 个方法与 2 个协调器就是 M1.3 的爆破面**。 |
| **M1.4** 原子结算 | ★★ **`EconomyConservationNetTest`（648 行，类注明写"在它们绿之前不许动 `MarketSettlement` 与偿还链"）**、`EconomySettlementTest`、`EconomyDebtTest`、`EconomySettlementEndToEndTest` | 半笔风险就在 `applyTransfer`，而它被 4 条链共用（关系实付、市场、借粮、偿还）⇒ 改它必然触这 4 个测试类。★ **改前必须先让 `EconomyConservationNetTest` 绿（这是 M0.5 立网的原话）**。 |
| **M1.5** Claim 双向 | `EconomyData` 构造期守卫（`210-222` 两侧存在性）、`EconomyDebtTest`（630 行，V6 债务聚合 + §7.1 三处规则逐值）、`ApiViews` 的 `debts` 栏（`446-452`/`804-808`）、`EconomyMoneyInvariantTest`（若"应收"进了 `FlowRow` ⇒ **守恒式当场不成立**，类注 `3518-3519` 已经预告） | 见 §3 第 5 条的落点警告。 |
| **M1.6** 逐工具守恒 | `EconomyMoneyInvariantTest:249 moneyTotalPerCurrencyIsConstantThroughAllThreeCycles`（**这条就是要改的**）、`EconomyConservationNetTest`、`ApiViews` 的货币栏（`565-570`/`683`） | 判据换口径 ⇒ 旧断言必须**改写而不是删除**（否则"守住了什么"无从复核）。 |
| **M1.7** 关系面 | `RegimeRelationsTest`、`ProductionSettlementTest`、`EconomyRoundTripTest`、`EconomyCodecTest`、`S1Stage3TenancyTest`（租佃档 + `AssetOwner ≠ Operator`）、`EconomyData` 构造期守卫 | `S1Stage3TenancyTest` 正是"占有与经营可分"的现成用例（`148-158` 逐键断言 `accounts()`），M1.7 应**扩它而不是另立一个**。 |
| **M1.8** 修 M-4 | `EconomyAllocationConsistencyTest`（`EconomySettlement.allocate ≡ EconomySeeder.splitProportional` 的跨模块一致性）、`EconomyData:323-333` 的 `Σ allocated ≤ available` 构造期守卫、`WorldgenInitializeToolTest:961`（实测把 `LENDER_SUBSISTENCE_RESERVE_PER_MILLE` 改回 0 就会红 —— 是**保留额口径的现成判别点**）、`ApiViews:1366-1407` 的 `utilizationPerMille` | 配额预算换口径 ⇒ 每个"逐值钉死分配结果"的用例都可能红（`EconomySettlementTest` / `EconomyAllocationConsistencyTest` / `EconomyRealScaleSeedBottleneckTest`）。 |

### 4.3 三条"改了就静默坏"的地方（**没有测试守**）

| 地方 | 为什么静默 |
|---|---|
| **`OwnershipBooks.apply:129-132` 的"钱原样带过"** | 只有注释警告。若有人改成两参构造器，钱被清零，而**`EconomyMoneyInvariantTest` 会红**（这条守得住 ✓）—— 但**只有真档规模用例守**，单模块/小夹具用例（`EconomySettlementTest` 等）**读不到**。 |
| ★★ **`GoodsAccount.java:72-73` 声称的 `ActorCodec` 钱往返守卫** | **不存在**（§5 第 1 条）。`AgentCodec` 甚至没有 `CurrencyId` 的 key deserializer 注册（`ActorCodec.java:94-96` 只有 `ActorRef`/`GoodsAccountKey`/`CommodityId`）。⇒ 这条**今天真的没人守**。 |
| **`Debt.dueCycle` / `defaulted`** | 零 reader（`Debt.java:23-25/36-37` 自陈）。M1.5 替换时若把它们**留在原地又新增同义字段**，就是"同一事实两处拼写"（本仓明令禁止）。 |

---

## 5. 如实记：与用户裁定里的假设**不符**的地方 / 未确认

> 按严重度排序。前 5 条是**会影响 M1 计划本身**的。

1. ★★★ **`GoodsAccount` 的 Javadoc 声称的序列化守卫不存在（"以为有、其实没有"）。**
   `GoodsAccount.java:71-73` 逐字："而'钱有没有被序列化丢'由 `ActorCodec` 的往返用例守着。"
   **实测（三重独立确认）：**
   - `grep -rn "money\|Money\|CurrencyId\|silver" simos-actor/src/test/` → **只命中 `ActorRoundTripTest.java:37/121` 两处注释**里提到 spec 禁令的 `Money`，**零断言**；
   - **`GoodsAccountTest.java`（那个专门的逐值判据文件，4 个测试）从未调用过 `money()`** —— 它只测 `key`/`balances`/非负/防御性拷贝；
   - ★ **全仓调用 `GoodsAccount.money()` 的只有 3 个 app 侧文件**：`EconomyMoneyInvariantTest`、`EconomyConservationNetTest:642`、`EconomyRealScaleSeedBottleneckTest:446`。**`simos-actor` 与 `simos-economy` 的测试一个都没碰过钱。**
   - `ActorCodec` 为 `ActorRef`/`GoodsAccountKey`/`CommodityId` 注册了 key deserializer（`ActorCodec.java:94-96`），**没有为 `CurrencyId` 注册**（而 `money` 的键就是它）；`ActorCodecTest:240` 那条"冻结线格式"钉的是**空** `accounts:{}`，不覆盖 money。
   **⇒ M1.3 的"唯一权威"若指望这条判据兜底，今天兜不住。钱的序列化与落账，今天**只有真档规模的 3 个 app 用例在守**（单模块用例全都读不到）。M1.3 必须自己写对拍用例。**

2. ★★★ **`DebtId` 与 `ClaimId` 的关系已被明令禁止，而 M1.5 的计划要求它们接上（计划内部冲突）。**
   `Transfer.java:76-78` 逐字："`Transfer.settles` 的类型是 `ClaimId`，而 …`DebtId` —— **两者不是同一种东西**（`Claim` 体系随 `simos-ledger` 在 2026-09-27 退役，`ClaimId` 只作为契约保留）⇒ 把 `DebtId` 硬塞进一个 `ClaimId` 的槽位，等于**凭空发明一套'债 = 债权'的对应关系**。"
   而 master plan:251 说 M1.5 要"**替换/包裹今天的 `Debt`（粮债）**"，`:252` 说"**一笔债两视图同源**"。
   **⇒ "替换 `Debt`"与"债务表与索取权体系是两套东西"这两句不能同时成立。M1.5 开工前必须先裁。**
   ★ 附带：`ClaimId` **已经存在**（`simos-economy-api/.../id/ClaimId.java`），M1.5 不是"从零造 id"。

3. ★★ **"冻结"确实完全没有（与假设一致），但"保留"已经有了、且都在正确的那一层 —— 不要把它们折进 `frozen`。**
   用户裁定"市场的保留策略放在经济决策层，账户层只执行已明确的冻结与转移"——**这条边界今天已经被遵守**：`MARKET_SELF_RESERVE_PER_MILLE`（`MarketSettlement.java:106`）与 `LENDER_SUBSISTENCE_RESERVE_PER_MILLE`（`EconomySettlement.java:313`）都在 settlement、都是 `max(0, 余额 − reserve)` 的**只读算式**、**不写任何字段**；`EconomySettlement.java:2516` 逐字"保留额不是'冻结起来的一笔粮'"。
   **⇒ M1.2 加 `frozen` 时，若把它与这两条 `reserve` 合并，就是"把储备政策搬进账户模型"，直接违反裁定。**

4. ★★ **"按经济用途动态保留"这条裁定在仓库里查不到（尚未落纸）。**
   `grep -rn "按经济用途动态保留\|可售库存 = max\|必要生产投入\|生活保留"` 全仓 `.md` + `simos-*/src` **零命中**。
   仓库里最接近的是：master plan §8 待裁甲（`:392`，"自留倍数 `MARKET_SELF_RESERVE_PER_MILLE`（现 1000‰）怎么办"）与 `.superpowers/sdd/2026-09-27-m0-instrument/m04-attribution.md` §2.4 的敏感性表（`:340-355`，把自留额从"日耗×120"换成"人口×10,000"看结论走多远）。
   **⇒ 这条裁定目前只存在于本次任务的措辞里。建议 M1 开工前先补进 master plan §八 / AGENT.md**，否则施工图的"生活保留/必要生产投入/供给义务"各项没有权威出处。
   ★ 附带确认：该算式**今天没有任何落点**（`GoodsAccount` 只有总量；`sellableOf`/`lendableOf` 是两份**私有**算式，口径还不同）—— 即"统一的可支配库存"这一项是**完全空白**的。

5. ★★ **M1.4 的动机需要改写：今天连"多市场"这个前提都不成立，"重复使用同一余额"不是当前的病灶；当前的病灶是"半笔转移"。**
   `Market` 是 `Map<HexCoord, Market>`（`EconomyData.java:116`），**每格一个**；市场清算是**每周期一次**且挂在 `anyCycleClosed` 这个日级事实上（`EconomySettlement.java:798-820`）；`EconomyDayStepper` 类注 `49` 明说副本"**不共享、不并发**：一次推进一个实例（用完即弃）"。
   **⇒ 今天不存在"两个市场同时用同一余额"的场景**（不是守住了，是没有这个场景）。
   **但有一条真实且今天就存在的原子性缺陷**：`applyTransfer:3123-3195` **先扣付方全部腿（商品 `3132-3148`、货币 `3152-3170`）、再记收方（`3182-3194`）**；若某条腿在中间抛（余额不足 / `MoneyIssuance` 恒抛），**前面已扣的腿留在可变副本上、收方一笔没记** ⇒ 半笔转移。
   **⇒ M1.4 应立为"单次结算内的原子性"（验证与落账分两遍，照 `drawCycleInputs:1377-1415` 的 H6-lite 三遍式先例），而不是"多市场并发防护"。** 否则容易被判"没有需求"而跳过。

6. ★ **"可查询的债权"缺的比计划里写的多一层：债权人侧的"应收/利息收入"在 `FlowRow` 里无处可放。**
   `chargeInterest:3539` 只 `merge(debt.debtor(), ...)`，类注 `3518-3519` 给的**理由是结构性的**："`FlowRow.income` 是**粮食**口径（往里加'利息收入'会让守恒式**当场不成立**）"。
   ⇒ M1.5 不只是"加一个反向索引"，还必须给债权人侧的应收**找一个新的落点**（不能塞 `FlowRow`）。master plan:251 那句"债权人的'应收'必须落账（今天**零**）"低估了这一层。

7. ★ **`landOperatorMoney` 与 `landHouseholdMoney` 的失败口径不对称（一处今天就存在的静默风险）。**
   `OwnershipBooks.java:323-328`（家户）账本缺席 ⇒ **抛**，理由写明"货币与商品住**同一本账** ⇒ 账本缺席只可能是'商品那一步还没跑'（顺序反了）"；
   `OwnershipBooks.java:491-495`（经营者）账本缺席 ⇒ **静默新建**一本 `Map.of()` 商品账。
   ⇒ 经营者侧顺序写反时**不抛**，会静默产出"商品为空"的账（只要中间有任何读者就会读到 0）。M1.3/M1.4 该把两条口径统一（推荐都 fail-closed）。

8. ★ **小事订正：`OwnershipBooks` 的 `load*/land*` 不是六个，是八个**（家户 4 + 经营者 4，经营者那 4 个是 H5 加的：`loadOperatorGoods:385` / `loadOperatorMoney:401` / `landOperatorGoods:427` / `landOperatorMoney:465`）。另有 `fold:85` / `apply:101` 两个非 `load*/land*` 的落账函数。

9. ★ **小事订正：`Transfer.settles` 恒 `Optional.empty()`**（`Transfer.java:81`）—— M1.4/M1.5 若要让转移"清偿指针"真的工作，这是现成的留位槽（类型已是 `ClaimId`，见第 2 条）。

### 未确认 / 没查到的地方（明写）

- **`EconomyCodecTest` 是否也有"整串 JSON 字面量"式断言**：只确认了 `ActorCodecTest:240` 有（那条**必然**被 `ActorData` 加组件打红）。`EconomyCodecTest` 已清点到规模（654 行 / 15 测试，覆盖 `Optional<EconomyMeta>`、`OptionalLong lastClosedCycle`、typed keys、四种 `FieldDelta` 变体、字节稳定性、旧档兼容），但**没有逐行读它的断言** ⇒ "它有没有整串字面量"**未确认**（倾向于"没有"：`grep -n "frozen\|WireShape\|isEqualTo(\"{"` 零命中）。M1.2 落地前请自己扫一眼 `EconomyCodecTest` 的 `theEmptySnapshotHasAFrozenWireShape` 对应物。
- **`EconomyPayloads` 的当前组件清单**：本次未读（`EconomySeeder` 的 `markets`/`relations` 载荷节点在 `709-712`、`HouseholdSeeder.goodsNode:281-300` 见过，整体未清点）。M1.2 落地时要自己核一遍。
- **`RegimeRelations.tenantRules()` 的具体规则行号**（`:445` 起，本次只看到声明行，未读内容）。
- **"按经济用途动态保留"的权威出处**：仓库里查不到（见第 4 条）⇒ 无法确认它的措辞是否已被正式裁定、还是本次任务首次传达。**按"逐字遵守"处理，但建议先落纸。**
- **`simos-economy` 的 pom 禁列 `simos-unit` 这条铁律**：本次**未核对 pom**（只按用户给的约定引用）。M1.1 新增类型若放 `simos-economy-api`（本报告的建议），该禁令不构成约束；但 **M1.2 若把 `FinancialAccount` 放进 `simos-economy`（而非 `-api`），需先核 pom**。
- **本次未读的既有测试类**：`EconomySettlementTest` / `EconomyDebtTest` / `EconomyConservationNetTest` / `ProductionSettlementTest` / `RegimeRelationsTest` 等只读了类级 Javadoc 与文件规模，未逐条读断言。§4 的风险清单是按"哪个类碰哪条链"的**结构性**推断给的，**不是**逐用例清点。

---

## 6. M1.1–M1.8 逐任务：今天到哪一步了、还差什么

| 任务 | 今天到哪一步 | 还差什么 |
|---|---|---|
| **M1.1 币种与货币工具身份** | ★ **约 40%**：`CurrencyId` 已存在且已被三处用（`GoodsAccount.money` / `Transfer.money` / `Market.numeraire`）；`money/` 包已存在（`MoneyAuthority` + `MoneyIssuance`）；`silver` 已有唯一拼写点（`RegimeRelations.DEFAULT_CURRENCY:137` ← `EconomySeeder.MARKET_NUMERAIRE:378`）；缺失已被**具名**记进读口（`ApiViews:593 paymentInstrumentGap` ← 属 M1）。 | ① `CurrencyDef` / `InstrumentId` / `InstrumentKind` / `MoneyInstrument` **四个类型全无**；② `silver` 迁成"一个 `CurrencyDef` + 一个 `SPECIE` 工具"；③ `Market.numeraire` 的类型决策（留 `CurrencyId` 还是换 `InstrumentId`）；④ `MoneyIssuance` 零注册（`REGISTERED = List.of():34`）与"发行累计量"的落点。 |
| **M1.2 `FinancialAccount` + 冻结** | ★ **约 10%**：`0 ≤ 余额` 的非负守卫**已有且已复用两次**（`GoodsAccount:94-113`、`OwnershipBooks` 的 4 个 `land*`）；`AccountId` **已存在但未被使用**（可直接用）；跨表守卫与"冻在赋值处"的形制有多个现成范本（`GoodsAccount` / `Market` / `Debt`）。 | ① `FinancialAccount` 类型（含 `0 ≤ frozen ≤ balance`）；② `EconomyData` 第 10 个组件 + **`EconomyChangeSet` 回填**（`hasSize(9)` → 10，方法名也要改）；③ `frozen` 的**写入口**（谁下达冻结）与**读入口**（可支配查询）；④ `EconomyCodec` / `EconomyPayloads` / `ApiViews` 三处同步；⑤ ★ 与 M1.3 的"唯一权威"关系必须一次裁清（不能两处可写）。 |
| **M1.3 唯一余额权威** | ★ **约 35%**：(a) 商品侧**已经是唯一真源**（`simos-ledger` 整个模块退役，对面没有那个类 —— `GoodsAccount` 类注 34-37）；(b) 钱的**落盘写入口只有一处**（`OwnershipBooks` 的 2 个 `land*Money`，`301/465`）；(c) `apply` 的"钱原样带过"已正确（`131-132`）；(d) 会话副本机制**有完整的载入/落回对**（4 对）。 | ① 钱有**两个表示**（`GoodsAccount.money` on disk + `EconomyDayStepper` 的内存副本）；② ★★ **两参构造器是类型层的静默清零陷阱**（`GoodsAccount:75-77`，15+ 个测试调用点）；③ ★★ **声称的 codec 往返守卫不存在**（§5 第 1 条）；④ `landOperatorMoney` 与 `landHouseholdMoney` 的失败口径不对称（§5 第 7 条）；⑤ `ActorCodec` 未为 `CurrencyId` 注册 key deserializer（`94-96`）。 |
| **M1.4 预留 + 原子结算** | ★ **约 15%**：**"唯一 applier"已经收口**（`applyTransfer:3123`，`3103-3121` 类注给了收成一处的原因）；**"先调查、再配给、后落账"的三遍式先例已经存在**（`drawCycleInputs:1377-1415` 的 H6-lite，含同格争用开池 + 最大余数法配给）；`Command → ChangeSet → Revision` 的事务原子性在**命令链**上已有（`CommandBus.commitBatch:428`，铁律 2）。 | ① `SettlementBatch` / `BatchId` / `BatchStatus` / `EntryKind` / `Entry` **全无**；② ★★ **`applyTransfer` 今天不是原子的**（先扣付方全部腿、再记收方 ⇒ 中间抛错留半笔）；③ 无冻结 ⇒ "冻结 → 验证 → 提交 → 释放"这条流程的第一步就不存在；④ ★ **动机要改写**（今天没有并发对手，见 §5 第 5 条）。 |
| **M1.5 `Claim` 双向视图** | ★ **约 30%**：(a) ★★ **"同源"在数据上已经成立**（`Debt` 一条记录同时带 `debtor` 与 `creditor`，`Debt.java:41-42`；两侧存在性都在构造期守卫里，`EconomyData:214-222`）；(b) `ClaimId` **已存在**且已被 `Transfer.settles:93` 用为类型；(c) 本金偿还**真的在跑**（`repayDebts:2619-2673`，走转移、可对账）；(d) 计息**真的在跑**（`chargeInterest:3530-3541`，并入本金）。 | ① ★★ **债权人侧零账**（`ClassRow.debts:60` 单向 + `lendDeficits:2479` 只写债务人 + 两个读口都遍历 `row.debts()`）；② ★★ **债权人侧的应收/利息收入在 `FlowRow` 里无处可放**（粮口径，类注 `3518-3519`）；③ `Claim` / `ClaimState` / `AssetRef` 类型全无；④ ★★ **`DebtId` vs `ClaimId` 的对应关系已被明令禁止，必须先裁**（§5 第 2 条）；⑤ `dueCycle`/`defaulted` 零 reader，替换时不要留成同义字段。 |
| **M1.6 逐工具守恒** | ★ **约 20%**：**一条真实档规模的常驻守恒护栏已经存在**（`EconomyMoneyInvariantTest`，516 行、4 条判据、推 360 天）；它已经把"非平凡"钉住（`genesisMoneyEqualsTheSeederFormulaNotJustItsOwnBooks:225` = 独立算式，不是"0 == 0"）；"两侧之和 vs 各自"的口径已有精确表述（类注 `66-69`）。 | ① 判据仍是"**Σ 恒定**"，不是 `Σ持有 = 创世 + 累计发行 − 累计注销`；② 没有"累计发行/注销"的落点（`MoneyIssuance` 零注册，`REGISTERED:34`）；③ 读口没有"私人流通 / 全部基础货币 / 银行存款"分栏（`ApiViews:565-570` 只有 `numeraireMoney`）；④ ★ **要明写"没有全世界总量永远不变这条总不变量"**（master plan:258）。 |
| **M1.7 关系面** | ★ **约 55%（比预期好）**：★★ **"产品归属"与"供料责任"已经明确成字段**（`ProductionRelation.residualOwner:84` + `inputSupplier:81-82`，后者是 H3/C3 刚建的）；四档规则表 + 缺省展开**已有**（`RegimeRelations.BY_REGIME:209-217` / `defaultRelation:240-295` / `defaultInputSupplier:318-333`）；`ProductionRelation` 已是 `EconomyData` 第 8 组件（往返用例已覆盖）；"改一条关系就换收租人"的数据基础已有（`feudalRules` 的地租规则受方是 `CohortKey(hex, 地主)`）；`S1Stage3TenancyTest` 已经钉着"占有与经营可分"。 | ① `occupations` / `useRights` / `laborSource` **三面类型全无**；② `Σ occupations[kind] ≤ capacity[kind]` 守卫**要落 `EconomyData`**（`ProductionRelation` 看不见产业表）；③ 判据②"地租受方 = 权利主体"今天还是**硬给的 cohort**（`feudalRules`），不是从权利解析；④ 判据③"雇佣量 ≤ 支配规模所需劳动"无落点；⑤ ★★ **实物给养义务仍不是具名义务**（它只是 `FIXED_IN_KIND_PER_LABOR` 的一种用法，欠额只进当日 ledger 读数）—— 这一条**比"三面字段"更难**，且与 M1.5 的 `Claim` 天然合并。 |
| **M1.8 修 M-4（`participationPerMille` 进计算）** | ★ **约 15%**：病灶已**精确定位**（master plan:276：配额预算用 `grossLaborMilli`，`EconomySeeder.java:1083/1132` ⇒ 真档实测四阶层 `labor/pop` **全部 = 562.8**）；`participationPerMille` **已经写进字段与视图**（`ApiViews:794`）；度量点已有（`ApiViews:1366-1407` 的 `utilizationPerMille`）；劳动再分配（H5 ③）**已实现**（`reallocateLabor:2707+`，会真的把配额在产业间搬）。 | ① 配额预算**没有**按参与率折扣；② `Σ allocated ≤ available` 这条构造期守卫（`EconomyData:323-333`）改口径后**必须保持绿**；③ ★ 会牵动一批"逐值钉死分配结果"的用例（`EconomyAllocationConsistencyTest` / `EconomySettlementTest` / `EconomyRealScaleSeedBottleneckTest` / `WorldgenInitializeToolTest:961`）；④ 修好后 `labor/pop` 应"拉开约 9.5 倍"（地主 ≈100‰ vs 贫农 ≈950‰）—— **这条判据还没有落点，要新写**。 |

---

## 7. 附：本次勘察用到的关键证据命令（可复算）

```bash
# 冻结/预留：全仓零经济语义
grep -rn "frozen\|Frozen\|FROZEN" --include=*.java simos-*/src
grep -rni "reserved\|reserve" --include=*.java simos-*/src
grep -rniE "\b(held|lien|escrow|pledge|earmark)\b" --include=*.java simos-*/src/main

# 币种 ≠ 工具：零命中（M1.1 是纯新增）
grep -rniE "CurrencyDef|MoneyInstrument|FinancialAccount|InstrumentId|InstrumentKind" --include=*.java simos-*/src

# 动态保留算式：仓库里查不到（尚未落纸）
grep -rn "按经济用途动态保留\|可售库存 = max\|必要生产投入\|生活保留" --include=*.md --include=*.java .

# 债权人侧：只有债务人方向
grep -rn "creditor" --include=*.java simos-*/src/main
grep -rn "withExtraDebt" --include=*.java simos-*/src

# 读"可支配库存"的全部路径
grep -rn "\.accounts()" --include=*.java simos-*/src/main     # 23 处
grep -rn "\.accounts()" --include=*.java simos-*/src/test     # 10 文件 / 61 处

# 钱的"以为有其实没有"的守卫
grep -rn "money\|Money\|CurrencyId\|silver" --include=*.java simos-actor/src/test/   # 仅 2 处注释

# 门禁基线（未跑 Maven）
for m in simos-*; do [ -d "$m/target/surefire-reports" ] && grep -h "^Tests run:" $m/target/surefire-reports/*.txt; done
```

---

## 8. 十二字结论

```
余额有，冻结·双向·统一·隔离缺
```

| M1 交付物 | 判定 |
|---|---|
| ① 稳定主体身份、多币种账户、余额与冻结额 | **部分有**（身份 ✓ / 多币种账户 ✓ / 余额 ✓ / **冻结额 ✗**） |
| ② 债权债务同源、双方可查询 | **部分有**（同源 ✓ / 债务人可查 ✓ / **债权人侧零账 ✗**） |
| ③ 明确产品归属、供料责任和实物给养义务 | **部分有**（归属 ✓ / 供料 ✓ / **给养只是某条规则、无应付余额、无读口 ✗**） |
| ④ 统一的可支配库存查询 | **没有**（6 类路径各自遍历；`可支配` 算式只有两份私有副本，口径还不同） |
| ⑤ 多个市场不能重复使用同一余额或库存 | **没有**（无冻结/无原子提交/无分区视图；今天靠"每周期一次 + 同格 + 单线程"结构性回避） |
