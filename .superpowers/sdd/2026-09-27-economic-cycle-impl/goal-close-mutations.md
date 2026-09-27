# 关账 · 四类关键项**变异自证**（任务书）

> 出处：`docs/superpowers/plans/2026-09-27-economic-cycle-implementation-plan.md` §五.3 ——
> 「**只对四类关键项做变异自证**：**守恒式 · 不丢失（身份/operator）· 静默付 0 · 断粮**」。
> 目的：证明这四类判据**真的会红**（不是恒绿的装饰）。每一条 = ①改一处实现 → ②跑指定用例 → ③**必须红** → ④改回 → ⑤**必须绿**。

## 铁规矩

- ★ **只在工作树干净（`git status --porcelain` 只有本任务允许的空行）的提交点上做**；每做完一类**立刻恢复原状**并在报告里贴 `git diff --stat` 证明"改回来了"。
- ★ **一次只改一处**；每次改完只跑**点名的那个用例**（`-Dtest=<类>#<方法>`，`surefire.failIfNoSpecifiedTests=false`），**不要跑全量**。
- ★ Maven 一律经 `tools/mvn-lock.sh`，`-pl <模块> -am`（`-am` 不可省）；退出码**不许接管道**（要接用 `${PIPESTATUS[0]}`）。
- ★ **不许**为了让它红而改断言/夹具；**不许**把"红了"当成失败 —— 红**就是**本任务的判据。
- ★ 报告里对每类写清：**改了哪一行**（原行 + 改后行）、**跑了什么命令**、**用例怎么红的**（断言消息原文）、**改回后的绿**。

## 四类

| # | 类 | 变异（改坏哪里） | 期望红的用例 |
|---|---|---|---|
| 1 | **守恒式** | `simos-economy` 的 `EconomySettlement`：把投入的 `ledger.addInput(...)` 那一笔**少记一次**（或把 `consumed` 的那一笔吞掉）⇒ 逐商品守恒式不平 | `simos-app` 的端到端守恒用例（`EconomySettlementEndToEndTest` 里那条"逐商品守恒/账要平"）+ `simos-economy` 的 `EconomyConservationTest`（若存在） |
| 2 | **不丢失（身份 / operator）** | ① `economy-api` 的 `HouseholdActors`：把 id 分隔符从 `:` 改回 `\|`（K9 那个只在落盘时现形的接缝）⇒ 往返当场抛/不等；② 或把 `OwnershipBooks.landHouseholdGoods` 的整本覆盖改成"只写非零键"⇒ operator 净产被抹 | 家户 actor 键的**往返**用例（`CohortKeyTest` / `HouseholdActorsTest`）+ `simos-app` 的"产出落 operator 账"用例（`EconomyRealScaleClothTest` / `EconomyTestWorld` 系的 ownership 用例） |
| 3 | **静默付 0** | `simos-economy` 的 `settleMoneyRule`（或 `RegimeRelations` 的工资/地租档）：把应付额算对、**实付写 0**（或让 `paidNow` 恒 0）而不抛 | `simos-app` 里"作坊货币工资实付 > 0 / 应付=实付+欠"那条（H4/H5 的逐值用例，在 `EconomySettlementEndToEndTest` / `EconomyRealScaleClothTest` 一族里）；★ 若找不到现成用例 ⇒ **这就是缺口，如实记**（并把它补成用例再自证） |
| 4 | **断粮** | `simos-economy` 的 `consumeOwnStock` / `lendDeficits`：把"缺口 → `unmetToday`/`unmetNeed`"那一笔**吞掉**（饿肚子但流水上看不出来），或让断粮兜底直接给粮 | `EconomyTestWorld` 的两种缺口形态用例（`EconomySettlementEndToEndTest`：有粮可借格的债务 / 无粮可借格的 `unmetNeed`）+ "改前有饭吃 ⇒ 改后仍有饭吃"那条 |

## 交付

1. 四类逐条：变异 diff（原行/改后行）· 命令 · **红的断言原文** · 恢复后的绿（`Tests run` 行）。
2. ★ **找不到判别用例的那一类**：如实记为"**该类的判据今天不存在**"，并**补一条最小用例**再自证（补的用例要能守住"改坏了就红"）。
3. 结尾一张表：四类 ×（变异点 · 红的用例 · 是否红得**因为那个原因**）—— 最后一条是重点：**红要红在对的断言上**（红在别的原因上 = 没证明）。

---

# ★★ 实测结果（2026-09-27 执行；每一条都当场跑过）

| # | 类 | 变异（改坏哪里） | 期望红的用例 | 实测 |
|---|---|---|---|---|
| 1 | **守恒式** | `EconomySettlement.recordInputDraw` / `recordOperatorInputDraw` 里的 `ledger.addInput(...)` 那一笔**吞掉** | 端到端守恒 + ledger 用例 | ★★ **没有红** —— 见下"缺口①" |
| 1′ | **守恒式** | `ProductionSettlement` 把**实付**算对但写 0（见 #3 的同一处，两个面） | 见 #3 | 见 #3 |
| 2 | **不丢失（身份）** | `HouseholdActors.SEGMENT_SEPARATOR` 从 `":"` 改成 `"|"` | 家户 actor 键的往返用例 | 第一次**没有红** ⇒ **补了 `HouseholdActorsTest`（新文件）** ⇒ 再放变异体**红在对的断言上**（见"缺口②"） |
| 2′ | **不丢失（operator）** | `OwnershipBooks.landHouseholdMoney` 用**两参** `GoodsAccount` 落回（把这一本钱清零） | 钱守恒/逐本落盘用例 | ★ **红在 `EconomyMoneyInvariantTest` 的 4 条上**（`everyBookLandsItsOwnMoney…` 期望 203,796 实得 800 等）—— 见下"真红之一" |
| 2″ | 同上的**弱形态** | 落回时**只写非零键** | —— | ★ **等价存活**（金额恒等：0 键写与不写同义；本世界无 0 额腿）—— 如实记，不算失败 |
| 3 | **静默付 0** | `ProductionSettlement.settleMoneyRule` 把 `paidNow = Math.min(due, available)` 改成 `paidNow = 0L` | 作坊货币工资"实付 > 0"那条 | ★★ 第一次**整类绿**（既有 15 个用例的 `availableMoney` 全是空表 ⇒ 只覆盖"付不出"那一支）⇒ **补了 `ProductionSettlementTest#i53b_…`** ⇒ 再放变异体**红在对的断言上**（见"缺口③"） |
| 4 | **断粮** | `EconomySettlement.trimUnmet` 把 `reduced` 钉成 0（吃到了第二口、缺口读数不肯减） | `EconomyDebtTest` 的缺口逐值 | ★ **红在对的断言上**：`贫农：总需求 − 借到的` 期望 **66,499** 实得 **66,666** |

## 真红之一（#2′ 的断言原文）

```
[★ 家户侧 + 经营者侧 == 创世总量（逐币种守恒落到每一侧）] expected: 203796L but was: 800L
[★ 至少一本家户账**多了钱**（收方那一半）] Expecting actual not to be empty
[★ 该格钱的币种集合必须含该格的计价货币] Expecting LinkedKeySet: [] to contain: ["silver"]
```
（`EconomyMoneyInvariantTest` 5 条里红 4 条；还原后 5/5 绿。）

## ★★ 三个缺口（如实记 —— 其中两个当场补了，一个本轮不补）

### 缺口① `ΣProductionInputs` **今天没有判别力**（本轮不补）

把 `ledger.addInput(industry.id(), commodity, drawn)` **整句吞掉**之后，跑
`EconomySettlementEndToEndTest#everyCommodityIsConservedAcrossAFullCycle`、`ProductionLedgerTest`、
`EconomyRealScaleClothTest` **全绿**。
⇒ 结论：端到端守恒式读的是**行流水 + 库存**（`Σ消费 − Σ所得 == Δ库存`），**不读 ledger 的 `inputs()`**；
而 `ProductionLedger.inputs()` 的**唯一读者**是它自己的 javadoc（`grep` 全仓：除注释外零命中）。
⇒ **这不是"变异体等价"，是这条读数的护栏缺失**。补它要动守恒式的口径（把 `ΣProductionInputs` 纳进来），
属下一轮；本轮按任务书要求**如实记为缺口**，不假称"守恒式一类已自证"。

### 缺口② 家户 actor id 的**文本形状**此前一条断言都没有（**已补**）

`HouseholdActorsTest`（新，3 条）：冻结字面量 `3_-7:rural:poor_peasant` + `of`/`cohortOf` 互为逆
（逐居住类型 × 阶层）+ 非 `HOUSEHOLD`/少一段/空段当场抛。
变异体重放 ⇒ `actorIdIsPinnedToTheCanonicalShape` 红，断言原文：
```
expected: "3_-7:rural:poor_peasant"  but was: "3_-7|rural|poor_peasant"
```
★ 边界（如实记）：**磁盘往返**这一半仍不在本文件里（`SqliteStoreTest` / `ReplayTest` 只覆盖信封与快照的通用往返）。

### 缺口③ 货币档"钱真的搬走"此前只被"付不出"那一支覆盖（**已补**）

`ProductionSettlementTest#i53b_moneyRulesActuallyMoveMoneyWhenThePayerHasIt`（新）：
`availableMoney = 5,000 毫银`、工资 1,000、地租 9,000 ⇒ 工资付满 1,000、地租只付得起**剩下的** 4,000、
两条各铸一条只带货币腿的转移、受方逐值拿到。
变异体重放 ⇒ 红，断言原文：`[(1000L, 0L), (9000L, 0L)]` vs 期望 `[(1000L, 1000L), (9000L, 4000L)]`。

## 还原的证据（每一条都以 md5 / `git checkout` 核对）

- `ProductionSettlement.java`：变异前 md5 `20ca501d5ae5fe45ce56478c271a3f04` ⇒ 还原后**逐字节相同**。
- `EconomySettlement.java`：变异前 md5 `22a7527bdbb6bdc1d01156ad7de6f9ff` ⇒ 还原后**逐字节相同**。
- `OwnershipBooks.java` / `HouseholdActors.java`：`git checkout` 还原，`git status --porcelain` 只剩本轮**有意**的改动
  （两个新测试文件 + 台账）。
- 还原后的复跑：`HouseholdActorsTest 3/3`、`CohortKeyTest 14/14`、`ProductionSettlementTest 16/16`、
  `EconomyDebtTest 11/11`、`EconomyMoneyInvariantTest 5/5` 全绿。

## 结论（照任务书第 3 条）

四类里 **2′（不丢失·operator）与 4（断粮）红在既有用例的对的断言上**；
**2（不丢失·身份）与 3（静默付 0）原本没有判别力，本轮各补一条用例之后才红在对的断言上**；
**1（守恒式）里"投入那一笔"这一面今天仍没有判别力（缺口①，如实记）**。

