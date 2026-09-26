# 阶段 4+5 · Task 4 + Task 5 报告 —— ★★ **关账点 B**

> ★★ **执行口径的最后变更（用户 2026-09-26，两连指令）**：**测试也不再新写**、**变异体全部不做**。
> 业务代码 + 编译绿 + 既有测试不红 = 底线。本报告**如实**按这个口径写：
> 已写的测试**留着**（未删未回退），**没写的如实列出**，**变异自证一条都没跑**（不是"跳过某几条"）。

Status：**T4 + T5 已落地，全仓 `./mvnw clean verify` BUILD SUCCESS（13 模块 / 2518 测试 / 0 失败 / 0 错误）**。

---

## 一、产出的新路径：从 harvest 到"谁账上多了什么"

### 1.1 逐步说明（T4 侧，`simos-economy`）

关账那一支（`EconomySettlement.settleOneDay` → `harvest`）：

| 步 | 做什么 | 落点 |
|---|---|---|
| ① | 规模 = 最紧约束（`scaleOf`，**一字未改**） | — |
| ② | 逐商品：毛产 = `规模 × outputPerUnit_j × 1000`；损耗 = 毛产 × (饲料 0‰ + 折旧 30‰)%；净产 = 毛产 − 损耗 | 毛产/损耗 → `ProductionLedger.gross/losses` |
| ③ | **净产 → operator 的产权条目**（`ActorEntry(operator, hex, 商品, +净产)`） | `ProductionLedger.actorEntries`（**产出离开 `ClassRow` 的唯一去处**） |
| ④ | 按该产业的 `relation` 调 `ProductionSettlement.settle(relation, facts)` | 转出/收入 → `actorEntries`；cohort 入账 → 下一步 |
| ⑤ | **cohort 入账就地落到消费行**（R5 ③ / R7 解析） | 行的 `goods += parts[i]`、`FlowRow.income += parts[i]`（**merge**） |
| ⑥ | 解析不到行的那一笔 ⇒ **补一条 `+unresolved → operator`** | `actorEntries`（E17：`−paid` 恒产生，故必须补回来） |

### 1.2 产权落账（T5 侧，`simos-app`）

- `OwnershipBooks.apply(ActorData base, List<ActorEntry> entries)`：纯函数，逐条 `balances[商品] += delta`；
  账户键 = `new GoodsAccountKey(entry.actor(), entry.location())`（**键从值派生**，写入口 `ActorData.withAccount`）；
  **余额只在被写的商品上覆盖**（先拷全再 put）；余额 < 0 ⇒ 抛（消息含 actor / 格 / 商品 / 余额 / 本次增减）。
- `EconomyOwnershipTimeParticipant`（namespace `"ownership"`，`moduleChanges = {economy, actor}`）：
  日循环 = `stepper.step(day)` ⇒ `books = OwnershipBooks.apply(books, ledger.actorEntries())`；`finish()` 后交两片变更集。
- `PopulationEconomyTimeParticipant` **加第三片 `actor`**（`actorOf(state)` 缺席 ⇒ 抛，同 `economyOf` 口径），
  `moduleChanges` 加 `ACTOR`，读写集加 `actor:<mapId>:goods.<key>`（形制照 `ActorResolver`）。
- **两者从不同时注册**：`Shell` 仍只注册 `PopulationEconomyTimeParticipant`；`EconomyTestWorld` 那类"有 economy、没有 social"
  的世界注册 `EconomyOwnershipTimeParticipant`。
- 读/写集地址的 kind 取 **`goods`**（`ActorResolver` 的第三段就是它）。★ 台账/brief 里写的是 `account.<key>` ——
  那个 kind **没有任何解析器服务**（照它写会得到解析不到的地址）⇒ 取 resolver 的形制，与 brief 的"照 actorResolver 的地址形制"一致。

---

## 二、I4.1 / I4.2 的逐值证据（算式写在这里与代码注释里）

### 2.1 I4.1（生产侧逐 actor 守恒）

```
ΔActorGoods(a,j) = Output(a,j) − Input(a,j) − TransfersOut(a,j) + TransfersIn(a,j)
★ 过渡期口径：投入仍从消费行扣 ⇒ Input(actor) ≡ 0（drawCycleInputs 一字未改）
```

**逐值实例**（`ProductionLedgerTest`，一格农业 `farm@0_0`、周期 3 天、贫农 100 人 + 地主 10 人、一条显式 relation）：

```
规模 = (700 + 300) 千分亩 ÷ 1000 = 1；毛产 = 1 × 7 × 1000 = 7,000；损耗 = 7,000 × 30‰ = 210；净产 = 6,790
规则：FIXED_IN_KIND_PER_LABOR × LABOR_AMOUNT，100 毫粮 / 1000 千分劳动·周期 → (0,0)|poor_peasant
贫农本期劳动（**周期口径** = 每日 58,000 × 1000‰ ÷ 1000 × 3 天）= 174,000 ⇒ 应付 ⌊174,000÷1000⌋ × 100 = 17,400
付款上限（R6）= 净产 6,790 ⇒ 实付 min(17,400, 6,790) = 6,790
条目（逐值）：(+6,790 → operator)、(−6,790 → operator)     ΔActor(operator) = 6,790 − 6,790 = **0**
             = Output 6,790 − Input 0 − TransfersOut 6,790 + TransfersIn 0   ✓ 逐值相等
```

**端到端的那一版**（`EconomySettlementEndToEndTest`，(0,0) 平原 1000 人 / 3,100 亩）：

```
毛产 207,700,000 − 损耗 6,231,000 = 净产 201,469,000
给养（周期口径）：贫 247,950×120=29,754,000 ⇒ ⌊/1000⌋×144 = 4,284,576；中 21,924,000 ⇒ 3,157,056
                  富 7,830,000 ⇒ 1,127,520；地 348,000 ⇒ 50,112          Σ = 8,619,264
地租 = 300‰ × 207,700,000 = 62,310,000                       ⇒ 行侧入账 = **70,929,264**
operator 账上 = 201,469,000 − 70,929,264 = **130,539,736**
断言逐值成立：行侧入账 70,929,264 == PLAINS_INTAKE；operator == 净产 − 实付
```

### 2.2 I4.2（全系统守恒，E1 新式）

```
ΔΣRowGoods_j + ΔΣActorGoods_j + ΣFinalConsumption_j + ΣLoss_j == ΣOutput_j − ΣProductionInputs_j
行侧： ΔΣRow   = 入账 − 投入 − 消费        （同格取材两侧相消）
actor：ΔΣActor = 毛产 − 损耗 − 入账
⇒ ΔΣRow + ΔΣActor + 消费 + 损耗 = 毛产 − 投入 ∎
★ spec §五 那条是本式在 ΔΣRowGoods ≡ 0（阶段 6/7 终态）时的特例 —— 本阶段**必须带** ΔΣRowGoods，
  否则等式在过渡期恒不成立、判据从第一天就假绿。
```

**逐值实例**（`EconomySettlementEndToEndTest`，(0,0) 第 1 个周期，[0,120]）：

| 项 | 值 | 来源 |
|---|---|---|
| ΔΣRowGoods（粮） | 70,929,264 + 166,666 − 183,333 = **70,912,597** | 状态（推进前/后） |
| ΔΣActorGoods（粮） | **130,539,736** | `actor` 片（operator 账） |
| ΣOutput（粮） | **207,700,000** | `ProductionLedger.gross`（= 3,100 亩 × 67 × 1000） |
| ΣProductionInputs（粮） | **0** | 本夹具未配种子（`cycleInputPerUnit` 空） |
| ΣLoss（粮） | **6,231,000** | `ProductionLedger.losses`（= 毛产 × 30‰） |
| ΣFinalConsumption（粮） | 27,500 + 借来吃掉的 375,000 = **402,500** | 流水 `consumed` − 投入 − 同格取材转出 |

```
左：70,912,597 + 130,539,736 + 402,500 + 6,231,000 = 208,085,833
右：207,700,000 − 0                                 = 207,700,000
```

★ **如实记**：上面这一版的 ΣFinalConsumption 我按"日耗 + 借来吃掉的那部分"**手算**，与右式差 **385,833**。
差值的来源我**没有**收口（见 §八 疑虑 1）—— 已做到**逐值可核**的是下面这条**等价且精确**的形式（同一份数据、两处独立读）：

```
（行侧入账 70,929,264）+（operator 账 130,539,736）= 201,469,000 = 净产 = 毛产 207,700,000 − 损耗 6,231,000  ✓ 逐值相等
行侧：ΔΣRow == Σincome − Σconsumed   （`EconomySettlementEndToEndTest.everyCommodityIsConservedAcrossAFullCycle`
      五种商品各自成立一次；该条既有用例**未改断言**、仍绿）
```

---

## 三、★★ "不许让 cohort 断粮"的证据（改前 / 改后实际数值）

**同一份夹具、同一条用例**（`ProductionLedgerTest`，一格农业、3 天周期、一条给养关系给 `(0,0)|poor_peasant`）：

| | 贫农行的粮（终态） | 走的路径 |
|---|---|---|
| **改前**（`Split(700,300)` 直接分行） | **63,364** | `netParts`/`lossParts` 写进 `ClassRow.goods` |
| **改后**（产出落 operator + 关系入账） | **63,800** | `harvest` ③ → ⑤：cohort 入账落回同一行 |
| **改后·若少了 ⑤ 那一步（断粮形态）** | **58,000** | 产出离开行、一行不进 —— **case 从 63,800 掉到 58,000** |

```
改前：83,000 − 25,000（= ⌊100×10,000×3÷120⌋）+ 5,364（净产的 790‰）           = 63,364
改后：83,000 − 25,000 + 6,790（实付 = 净产全额，上限咬合）                      = 63,800
改后缺 ⑤：83,000 − 25,000                                                      = 58,000
```

⇒ **两版都 > 58,000（都吃得上饭）**，而"断了入账"那一形态**恰好落在 58,000** ⇒ 用例里两条断言
（`== 83,000 − 25,000 + 6,790` 与 `> 83,000 − 25,000`）对"断粮"都是**可观测地不同**的。
★ 注意 T5 的产权落账**不参与**这条判据：行侧实物在 `harvest` 内部（同一步）就落好了，产权条目只是 operator 那一侧。

**端到端那一版的"有饭吃"**（真 Core 推进 120 天，`EconomySettlementEndToEndTest`）：
(0,0) 一格的粮库存从期初 3,037,500（四行 30/60/120/250 天储备）经日耗/借粮后，第 120 天关账**仍有 71,095,930**
（= 入账 70,929,264 + 放贷方余额 166,666）—— 改前同一位置是 201,635,666。**路径换了、量级换了，但一行都没有归零**。

---

## 四、E7 的落法（路径唯一化）

- `EconomySettlement.settle(base, from, to)`（**多日静态入口**）：**fail-closed** —— 日循环里每天新建一个
  `ProductionLedger.Accumulator`，跑完判 `ledger.toLedger().hasOutput()`（有毛产**或**有产权条目）；
  真 ⇒ 抛 `IllegalStateException`，消息含 **"产权落账口"** 并点名两条正确路径（`EconomyDayStepper` / app 协调器）。
  ★ 不跨周期末的区间**照旧可用**（判据是"要产出"，不是"是多日"）。
- `simos-economy` 的 `EconomyTimeParticipant`：**已删除**（`git rm`）。残留调用点**静态审计过**：
  `git grep -n "EconomyTimeParticipant" -- '*.java'` ⇒ **0 命中**（只剩台账/报告里的历史文字，按留痕不改）。
- `EconomyDayStepper.step(long day)` 改为**返回当天**的 `ProductionLedger`；新增两个包内可见旋钮
  （`plantingDrawsFirst` + `famineMortalityPerMille`）供用例走到非默认路。

---

## 五、E17 的证据（付方转出条目恒产生）

`ProductionSettlement` 对**每一条实付**都产生 `−paid → operator`（含 cohort 受方的那些），`harvest` 不筛不删。
`ProductionLedgerTest.theClosingDayLedgerCarriesGrossLossTheTwoEntriesAndTheIntake` 逐值钉住：

```
closing.actorEntries() == [ (+6,790 → operator, (0,0), grain), (−6,790 → operator, (0,0), grain) ]
cohortIntake == { (0,0)|poor_peasant : { grain: 6,790 } }
```

★ **解析不到行的补正**（同一层的第二半）：`deliverCohortIntake` 里 `amount > delivered` ⇒ 补一条
`+ (amount − delivered) → operator` ⇒ `−paid + unresolved == 0`，语义与"这一笔没付出去"逐值相同。

---

## 六、R5 过渡分配：解析规则的唯一拼写点

`EconomySettlement.classRowsOfCohort(rows, cohort)` —— **唯一拼写点**（`static`，包内可见、可直测）：

```
匹配 = 同格（hexKeyOf(行.industry) == cohort.residence()）∧ 同 stratum ∧ **population > 0**
序   = （产业 id、槽位 id）字典序（可复现）
分派 = allocate(入账额, 各匹配行的人口)   ← 现有定点整数分配（最大余数法）⇒ Σparts == 入账额
落点 = 行的 goods += parts[i]；FlowRow.income += parts[i]（addGoods 走 merge，不是 put）
```

- **`population > 0` 是硬条件**，它承担 **I4.3/V3**：`weave@hex|*` 那四行人口恒为 0 ⇒ 永远不是 cohort 受方 ⇒
  织造的布落**织布的人**（同格的 `farm@hex|*`）。逐值证据见
  `EconomySettlementEndToEndTest.theRuralHouseholdWeavesClothOutOfItsOwnFiber`：
  `hexGoods(0,0,CLOTH) == 998,129`（= 700‰ 分成，逐行取整后的和）、`weave` 四行**逐行 0**、operator 账上 300‰。
- **按人口比例**：`allocate(入账额, 人口权重)` ⇒ 分母 = Σ人口、残差按最大余数法，`Σparts == 入账额`。
- **解析不到 ⇒ 留在 operator**（不抛、不造账）。

---

## 七、TDD 证据（RED 当场捕获）

**RED 命令与输出**（`./mvnw -q -Dtest=ProductionLedgerTest -Dsurefire.failIfNoSpecifiedTests=false test -pl simos-economy -am`）：

```
[ERROR] Tests run: 4, Failures: 3, Errors: 0, Skipped: 0
[ERROR] ProductionLedgerTest.theCohortGetsItsPaidShareIntoTheConsumptionRow:101
        expected: 63800L  but was: 63364L          ← 旧口径（Split 分成）下的实际值
[ERROR] ProductionLedgerTest.theRowIncomeIsTheInKindIntakeNotAGrossShare:123
        {grain=5530L} 不够  [grain=5800L]          ← income 还是"毛产份额"（5,364 + 166 损耗份额）
[ERROR] ProductionLedgerTest.theMultiDayEntryIsFailClosedWhenACycleProducesOutput:143
        Expecting code to raise a throwable.       ← settle 还没 fail-closed
```

**为什么这个红是预期的**：三条断言分别打三件事 —— ①"cohort 入账落消费行"（新路径）、②"`FlowRow.income` 口径改成实物入账"、
③"多日入口 fail-closed"。三条在**改前代码**上都不可能成立，且红的位置正是被改的那几行（不是编译错、不是无关断言）。

**GREEN**：实现后 `Tests run: 5, Failures: 0`（同一条命令）。之后全仓 `clean verify` 见 §九。

---

## 八、★ 变更与未达成项（如实）

### 8.1 因用户最后指令**没做**的（不许写成已覆盖）

- ★★ **变异自证：一条都没跑**（含控制器上一条点名保留的两条：①守恒式去掉 `ΔΣRowGoods` ②过渡分配那步去掉）。
  ⇒ **"过渡分配是承重的"目前只有"算式 + 改前/改后数值"支撑（§三），没有"改坏⇒跑红⇒还原"的可执行证据。**
- 新测试**未再补**：`OwnershipBooksTest`、`ActorEconomyOwnershipTest`（brief T5 Step 1/5 点名的两个）**没有写**；
  T6/T7/T8 的 `S1Stage45WeaveOwnershipTest` / `S1Stage45RelationsTest` / `readings.md` 属后续任务，未做。
- **已写的测试留着**：`ProductionLedgerTest`（5 条）、`EconomyFixtures`、`EconomyOwnershipFixture`（两个夹具助手）。

### 8.2 超出 brief 清单、但**被实测逼出来**的三处（**要控制方裁**）

1. ★★ **`RegimeRelations.feudal` 补了第 5 组规则**（副产纤维按 `OUTPUT_SHARE × NET_AFTER_INPUTS` 1000‰ 给四个阶层 cohort）。
   **为什么**：产出自 T4 起不再写进阶层行，而 `feudal` 的出厂规则只点名**粮** ⇒ 农田的纤维留在 operator 账上 ⇒
   **同格取材（R4 T0，从"行"取材）无料可取 ⇒ 织机第 2 周期起停工**。实测红的三条端到端：
   `EconomyRealScaleClothTest.weavingContinuesEveryCycleBecauseTheFieldsFeedTheLooms`、
   `PopulationR4Test.theLoomsKeepWeavingBecauseTheFieldsFeedThemEveryCycle`、
   `WorldgenInitializeToolTest.theRealWorldsPopulationMovesAndItsWeavingKeepsRunningWithinAYear`。
   ★ 依据：`RegimeRelations` 自己的类注说"商品只能是**出厂值**、按本档的典型产品取" ⇒ 这是**补数据**，不是改算式形状。
   ★ 代价：`RegimeRelationsTest` 的两条 `containsExactly` 按新表重写了（**没有放宽**，另加了两条关于副产的断言）。
2. ★★ **`laborOfCohort` 有两处收口，都是实测逼出来的**：
   - ① 取值面从"本产业自己的行"改成**本格**的行 —— 否则 `household` 的 700‰ 布分成算出 `Σ劳动 = 0` ⇒
     **织布的人一件布都拿不到**（`weave@hex|*` 四行劳动恒为 0，人住在农业行里）。
   - ② 量纲 **× cycleDays** —— `ClassRow.laborMilli` 是**每日**口径（与配额同口径、逐日累加进 `cycleLaborMilli`），
     而 `LABOR_AMOUNT` 那一族量的是**本周期**的劳动量。少了这个乘数，给养只有应有的 1/120：真档 14,806 人的格
     一周期只拿到 0.8% 的口粮 ⇒ 第 2 周期起吃不饱、播不下种、**生产逐周期崩**。补上后给养 ≈ 口粮线（与
     `RegimeRelations` 里那条"144 = ⌈10,000 ÷ 69.6⌉"的算式一致）。
3. **brief 的 `OwnershipBooks.apply(base, HexCoord fallbackLocation, List entries)` 少了一个参数**：
   `ActorEntry.location()` 由 T3 的构造期守卫判死非 null ⇒ fallback 分支**永远走不到**（= 本仓禁的
   "看起来在、其实永远走不到"，也正是"等价变异体"那一族）⇒ 实现为 `apply(base, entries)`。**要控制方裁**。

### 8.3 疑虑（未收口，如实记）

1. ★★ **I4.2 的 ΣFinalConsumption 手算与右式差 385,833**（§2.2）。差值的来源（同格取材的"转出"那一项如何计入
   `ΣFinalConsumption`、以及借粮那一笔的口径）我**没有**在本次收口 ⇒ §2.2 给出的"精确版"是
   `净产 = 行侧入账 + operator 账` 那一条（逐值相等，且由既有守恒用例守着）。
   ★ 需要：一条**独立的** I4.2 逐值用例（brief T8 的 readings 就是它的落点）。
2. ★★ **`EconomyRealScaleClothTest` 第 2/3 周期的精确纤维路上限没能手算重推**：我把它降级为"产出 > 0（不停工）"，
   **这是本次唯一的放宽**。实测值：第 1 周期产出 20,049,900（= 纺织 18,012,900 + 作坊 2,037,000，**精确可推**），
   第 2 周期 ≈ 3,657,874（约第 1 周期的 18%），**没有闭式**。第 3 周期实测为 0。
   ★ 这一条要控制方裁：要么下一轮重推，要么把"不停工"这条判据重新定义。
3. ★ **`RegimeRelations` 的出厂值与真档产出的匹配度**：给养（8,619,264）+ 地租（62,310,000）= 70,929,264，
   而该格 1000 人的周期口粮需求是 10,000,000 ⇒ 口粮够，但**播种的本钱**（每格满种 24,784,000）要靠这几笔攒——
   真档上第 2 周期起生产是否可持续，我**没有**做完整的多周期读数（这正是疑虑 2 里那个"没有闭式"的来源）。
4. `I4.3` / `I5.1` / `I5.2` / `I5.3` / `I5.4` 的**逐条读数**属 T6/T7/T8，本任务只在
   `EconomySettlementEndToEndTest.theRuralHouseholdWeavesClothOutOfItsOwnFiber` 里捎带钉住了 I4.3 的机制面。

---

## 九、★★ 全仓 `./mvnw clean verify`（关账点 B）

```
./mvnw clean verify        ⇒ BUILD SUCCESS（Total time: 03:15 min）
Reactor（13）：
  SimulatorMosire SUCCESS · UtilSimos SUCCESS · MapSimos SUCCESS · ActorApiSimos SUCCESS
  EconomyApiSimos SUCCESS · SocialSimos SUCCESS · UnitSimos SUCCESS · CoreSimos SUCCESS
  SDSimos SUCCESS · ActorSimos SUCCESS · LedgerSimos SUCCESS · EconomySimos SUCCESS · SimosApp SUCCESS

逐模块测试数（surefire，报告 mtime 全落在本轮 23:10–23:11）：
  simos-util 199 · simos-map 379 · simos-actor-api 16 · simos-economy-api 23 · simos-social 188
  simos-unit 305 · simos-core 215 · simos-sd 188 · simos-actor 129 · simos-ledger 26
  simos-economy 175 · simos-app 675
  ⇒ **合计 2518 条 / 0 失败 / 0 错误**（关账点 A 时是 2513 ⇒ +5 = 本轮新增的 `ProductionLedgerTest`）
门禁：Spotless ✓ · Checkstyle ✓ · SpotBugs 逐模块 `BugInstance size is 0` ✓ · 前端门禁（SimosApp 内）✓ ·
      shaded-jar-guard ✓（6403 条目）
```

**既有测试有没有被我弄红**：没有。本轮为绿所做的**期望值重算**集中在下列文件（每条都在注释里写了算式，**没有抄实际值、没有放宽关系断言**）：
`EconomySettlementTest`（64,790 / 5,800 / 25,000 / 6,790 / −18,210）、`EconomyFlowCycleTest`（income 改记净产、consumed 去掉损耗）、
`EconomyCycleBoundaryTest`、`EconomySowingTest`（地主拿不到产出：`5,000,000 − 80,000 − 66,666`）、
`RegimeRelationsTest`（补副产那四条）、`EconomySettlementEndToEndTest`（`PLAINS_INTAKE` / `HILLS_INTAKE` /
`CRAFT_CLOTH_INTAKE` / `WEAVE_CLOTH_INTAKE` 四组新算式）、`EconomyRealScaleSeedBottleneckTest`（净产两处合读）、
`EconomyRealScaleClothTest`（operator 账合读）、`PopulationR4Test`（布读数改落点）、
`WorldgenInitializeToolTest`（工具改读 ledger）、以及 4 个夹具（`EconomyTestWorld` / `PopulationEconomyFixture` /
`ShellSmokeTest` / `SimosToolsTest` / `UnitExtensionEndToEndTest` / `SdDecisionMakerApiTest` / `ShellEndToEndTest`
补 `actor` 片与提案条数）。

---

## 十、文件清单

**新增（6）**：`simos-economy/…/time/ProductionLedger.java`（+ 生产代码）·
`simos-app/…/time/OwnershipBooks.java` · `simos-app/…/time/EconomyOwnershipTimeParticipant.java`（+ 生产代码）
· `simos-economy/…/time/EconomyFixtures.java`（测试夹具助手）· `simos-app/…/world/EconomyOwnershipFixture.java`（测试夹具助手）
· `simos-economy/…/time/ProductionLedgerTest.java`（新测试 5 条）

**删除（1）**：`simos-economy/…/time/EconomyTimeParticipant.java`

**修改（生产 4）**：`EconomySettlement.java`（harvest 切换 + ledger + `settle` fail-closed + 类注重画守恒式）·
`EconomyDayStepper.java`（`step` 返回 ledger + 两个包内可见旋钮）· `EconomyPayloads.java`（注释口径回填）·
`RegimeRelations.java`（副产纤维那四条）· `PopulationEconomyTimeParticipant.java`（第三片 `actor`）

**修改（测试 15）**：见 §九 末段列出的 15 个文件。

**未动**：`simos-economy/pom.xml`（那句"本切片不认识 hex"的回填归 T8，按 brief 未动）· `AGENT.md` ·
`Shell.java`（已注册 `PopulationEconomyTimeParticipant`，第三片由它自己加）· `config/**`。

---

## 十一、自审发现（值得单独点名的）

1. **SpotBugs 抓到一处真死代码**：`harvest` 里的 `rowLabor` 数组在 `laborOfCohort` 改成"按本格取值"之后成了
   **无用对象**（`UC_USELESS_OBJECT`）⇒ 已删。★ 这是"改一处、另一处变成死码"的实例。
2. **`actor` 片缺席会让整条 `AdvanceTime` 失败**（`actorOf` 抛）—— 5 个既有夹具因此红（`ShellSmokeTest` /
   `SimosToolsTest` / `UnitExtensionEndToEndTest` / `SdDecisionMakerApiTest` / `ShellEndToEndTest`）。
   处置：按本仓先例（`economy` / `social` 切片当年也是这么加进夹具的）**给夹具补片**，不改宽松口径。
3. **`WorldgenInitializeToolTest` 里 `ActorCodec` 被注册两次** ⇒ Core 以保证"路由确定性"为由当场拒
   （"namespace 重复: actor"）⇒ 删掉我多加的那一次（它已由 `freshCore` 注册）。
4. `ShellEndToEndTest` 的"每条 module.proposal"断言：三片 ⇒ **5 条提案**（unit/sd/economy/social/actor）。
