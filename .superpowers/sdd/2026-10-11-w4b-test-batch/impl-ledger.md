# W4b 测试批 —— 实现/取证账本

> **责任区**：按**验收判据**（不是按代码反推）补三条测试 + 给一条老守卫加牙。
> 任务书：用户 2026-10-11 的 W4b 测试批派单（本会话）。**本批是测试代理**（AGENTS §一.5：写代码代理不写测试，测试最后单独一个代理）。
> **允许写**：`simos-economy/src/test/**`、`simos-app/src/test/**`、`simos-social/src/test/**`（含 `src/test/resources/**`）。
> **禁止碰** `src/main/**`（**实测零改动**，见 §五）、`simos-unit`/`simos-sd`/`simos-core`、`docs/**`、`AGENTS.md`。
> **纪律**：一次一个 Maven（全程 `tools/mvn-lock.sh`）；**未** `git commit`；**未** 跑 `clean verify` / `package`。
> **判据出处**：`docs/superpowers/specs/2026-10-10-unified-price-and-ordered-settlement-design.md`（**v3.14**）。

---

## 一、判据 → 用例映射

| # | 判据出处 | 用例（类#方法） | 断言内容（逐条） | 实跑真命令与结果 |
|---|---|---|---|---|
| ①-1 | v3.14 §7「老守卫加牙（测试批）」：判据由"每段 > 0"改为"**零成交轮次 = 0**" | `EconomyCycleHealthTest#twoFullCyclesInRealThreePowersWorldKeepMoneyConservedAndMarketAlive`（**改**） | **96 个市场轮逐轮扫描** ⇒ 零成交轮次必须 = 0（旧断言只在 8 个段末取样，**原样保留**） | `tools/mvn-lock.sh test -pl simos-app -am -Dtest='EconomyCycleHealthTest' -Dsurefire.failIfNoSpecifiedTests=false` ⇒ **rc=0**，`Tests run: 1, Failures: 0, Errors: 0, Time elapsed: 77.27 s`（报告 mtime `07:56:02`，本轮内） |
| ①-2 | 同上：**钉基线分布**（验收实测 96 轮、零成交 0、min/中位/max = 17/35/131） | 同上（新断言 `assertEveryMarketRoundTraded`） | 逐轮成交笔数的**整份分布指纹**必须逐值 == 基线 `{17×10, 18×1, 21×7, 22×9, 23×5, 24×12, 31×1, 34×1, 35×3, 36×4, 37×8, 38×1, 39×2, 40×13, 41×2, 44×1, 53×8, 54×1, 55×3, 56×2, 66×1, 131×1}`；另钉 min=17 / 中位=35.0 / max=131 | 同上；实测分布与基线**逐项相同**（见 §四-1 的 summary.txt 真值） |
| ①-3 | 同上 + AGENTS §三.2（读侧自检） | 同上 | **读侧自检 4 条**：轮次数 == 96、同一天不得两轮、相邻轮间隔 ≤ 5 天、`Σ 逐轮 fills == MARKET_FILL 逐笔行数` | 同上；`轮次=96 零成交轮次=0 min/中位/max=17/35.0/131 Σfills=3260（== MARKET_FILL 3260 行）` |
| ②-1 | §6.1 **W2 行判据①** + §1 用户 0–8 第 6 条「一股脑全拿下…把价格全部记为债务」 | `DebtBasedClearingAcceptanceTest#zeroCashHouseholdStillGetsTheGoodsAndTheUnpaidPriceBecomesDebtToTheSeller`（**新**） | 现金 0 的家户仍拿到货（`fills=1`、数量 = 1 人 35 天口粮 `2905`）；`Fill.goodsPaymentMilli()==0`（= **实付现金**）；新增债务 == 未付价款 `⌈2905×10÷1000⌉ = 30`，且 debtor/creditor/unit=Money(silver)/dueCycle 逐值对得上；货腿走、**钱腿为空**、双方余额不变 | `tools/mvn-lock.sh test -pl simos-economy -am -Dtest='DebtBasedClearingAcceptanceTest' -Dsurefire.failIfNoSpecifiedTests=false` ⇒ **rc=0**，`Tests run: 6, Failures: 0, Errors: 0`（0.118 s） |
| ②-1′ | 同上（**日志具名读数**） | 同上 | `MARKET_DEFERRED_PAYMENT` 行逐值：`paymentMilli=30 paidCashMilli=0 deferredMilli=30`、`debtor=hh-buyer`、`creditor=hh-seller` | 同上；真行（本轮实取）：`event=MARKET_DEFERRED_PAYMENT … day=5 commodity=grain debtor=hh-buyer creditor=hh-seller currency=silver quantityMilli=2905 paymentMilli=30 paidCashMilli=0 deferredMilli=30 … outstandingMilli=30` |
| ②-2 | §2 **S-5** + §1 用户第二批第 5 条「只取决于这个家户的哪个种类商品，预估价值最低」 | `#repaymentMediumOrderIsAscendingEstimatedUnitValueWithMoneyLast` + `#repaymentActuallyPaysWithTheLowestValuedMediumNotWithMoney`（**新**） | 介质序 == **估值升序** `[grain(5), cloth(20), tool(100), silver(1000)]`（逐值表写在断言消息里；**货币恒最后**）；实际还款只用 grain `6199` 毫（折 30 合同单位）、**一分钱没动**、其它三种介质余额不变、本金 → 0 | 同上（同一次 6/6 绿） |
| ②-3 | v3.6（用户第六批原话「为啥要设还债窗口，应当是有库存就还，没库存就不还啊」）+ §6.1 W2 判据④ | `#noReserveWindowPaysTheWholeStockDownToZero` + `#householdWithNothingToPayKeepsTheDebtAndIsCountedAsNoStockToRepay`（**新**） | ① 有库存就还：整仓 `10000` 毫粮**全部**出手（旧口径会留 `83×35=2905` 毫）、库存 **还到 0**、本金 1000 → 950 且合同留表；② 没库存：一条腿都不铸、本金原样 30（**挂账、不清零**）、`DEBT_REPAY_ROUND` 具名读数 `debtorsWithOutstanding=1 / householdsRepaid=0 / **householdsWithNoStockToRepay=1** / reserveCommodities=0`、`why` 含 `v3.6-no-reserve-window` | 同上（同一次 6/6 绿） |
| ②-4 | §6.3 判据 3「旧拒绝路径确实被挡住」 | `#withoutDeferredPaymentAbilityTheSameZeroCashBuyerGetsNothing`（**新**，负向对照） | 同一世界**不挂**债务副本（`debts=null` ⇒ W2b §5-1 的旧口径回退面）：一笔都不成交、买方一粒粮没拿到、且**买方根本没进撮合**（未成交表无买方侧条目 —— 需求在订单生成处就被"付得起"折回 0）；正面对照里整轮**无 `NO_BUDGET`** | 同上（同一次 6/6 绿） |
| ③-1 | 设计书 v3.12 §7 **D2 裁定** + **W3-fix3**（控制方 2026-10-11 裁定：按**人数**）+ §6.1 W3 行判据④ | `FamineMortalityCountingTest#famineDeathsCountsThePeopleOfAMultiPersonDeathEventNotTheEventRows`（**新**） | 造"**一条 DEATH 事件含多人且 famine 参与**"：甲户 `100000 人 × 1000 ppm = 100 人/条`（cause=`BASE_PLUS_FAMINE`）、乙户 `10000 × 1000 ppm = 10 人/条`（cause=`BASE`）⇒ `famineDeaths` 必须 = **100（人数）**（条数口径会得 **1**）；`deaths=110`、`famineDeaths ≤ deaths`、逐户死亡原值 `{甲:100, 乙:10}` | `tools/mvn-lock.sh test -pl simos-social -am -Dtest='FamineMortalityCountingTest' -Dsurefire.failIfNoSpecifiedTests=false` ⇒ **rc=0**，`Tests run: 2, Failures: 0, Errors: 0`（0.075 s） |
| ③-2 | §6.1 W3 行判据④「缺口=0 ⇒ 饥荒项不触发」 | `#withoutAnyReportedGapTheFamineItemStaysZero`（**新**，负向） | `FamineMortalityInput.none()` ⇒ `famineDeaths == 0`、死亡只走基准率（`100000 × 1000ppm = 100` 人）、全部 DEATH 事件 `cause=BASE` | 同上（同一次 2/2 绿） |

**全模块回归（顺带做，非要求）**：`tools/mvn-lock.sh test -pl simos-economy -am` ⇒ **rc=0**、`BUILD SUCCESS`、**simos-economy 288 tests / 0 failures**
（证明新加的测试树 SLF4J 捕获器**没有扰动**既有用例；上游模块同轮全绿）。★ **未**跑 `clean verify`（归控制方门禁）。

---

## 二、变异自证记录（改坏 ⇒ 当场红 ⇒ 还原 ⇒ 比 md5）

**通用纪律**：变异体只推入**目标类**（按方法体改写，不按文件名拷贝）；每轮先记原件 md5；跑完 `cp` 还原后**用 `md5sum -c` 逐字节核对**；`src/main/**` 最终 diff **为空**（见 §五）。

| # | 变异体（落点 + 改法） | 变异体 md5 vs 原件 md5 | 红在哪一行（真输出） | 还原核对 |
|---|---|---|---|---|
| **M1-A** | `MarketSettlement.buyPartsByPriority`：`return weights;` ⇒ **W4 初版的"预先切分"**（`parts[i]=min(weights[i],剩余matched)`），并把两个调用点改回 `buyPartsByPriority(matched, weights/tierBuyWeights)`（= W4-fix2 diff 的反向） | `17b1bc9dc38b802ed792ef6e31a14acf` vs 原件 `2262c885c11c46c89cfa1013e40b27dc`（**字节不同** ✓） | **加牙前（旧判据）**：`rc=1`，红在 `EconomyCycleHealthTest:352`「第 3 段（关账日 day=90…成交笔数必须 > 0）」；未成交 top1 `algorithm_uncovered×227笔/77,589,010毫` —— 与 **W4-fix2 账本 G-1/G-4-2 记的历史读数逐字相同**（坏法复现成功） | `md5sum -c` ⇒ `MarketSettlement.java: OK` |
| **M1-B** | 同上（M1-A 保持）+ **临时探针**：把段末那条 `isNotEmpty()` 短路（`if (false) …`），让**新断言**有机会跑 ⇒ 取"加牙后"的完整报告 | 测试文件探针态 `53bcdabc…` vs 原件 `812b86ab…` | **加牙后（新判据）**：`rc=1`，红在 `assertEveryMarketRoundTraded`「**零成交轮明细=[day13, day15, day18, day63, …, day90, day95, …, day118]**；全部分布=`0×24, 1×15, 2×3, 5×1, 7×1, 16×1, 19×1, 21×4, 22×4, 23×4, 24×8, 25×14, 26×8, 27×1, 28×2, 31×1, 36×1, 40×1, 81×1, 86×1`」 | 两文件**同时**还原：`md5sum -c /tmp/w4b-md5-clean.txt` ⇒ 两个 `OK` |
| **M2-A** | `MarketSettlement.deferralPossible` ⇒ `return false;`（赊购判据整条失效） | `17b1bc9d…`（本轮 MarketSettlement 变异态） | `DebtBasedClearingAcceptanceTest.zeroCashHouseholdStillGetsTheGoodsAndTheUnpaidPriceBecomesDebtToTheSeller:105 [现金 0 的家户必须拿到货（债务式清算：需求全额进撮合）]`；`Tests run: 6, Failures: 1` | `md5sum -c /tmp/w4b-md5-econ-main.txt` ⇒ 两文件 `OK` |
| **M2-B** | `EconomySettlement.repaymentMediumOrder` 的比较器尾加 `.reversed()`（介质序反向 = 旧口径"钱永远第一"） | EconomySettlement 变异态 | 恰**两条**红：`repaymentMediumOrderIsAscendingEstimatedUnitValueWithMoneyLast:222`（介质序）与 `repaymentActuallyPaysWithTheLowestValuedMediumNotWithMoney:250`（实际还款腿）；`Failures: 2` | 同上 ⇒ `OK` |
| **M2-C** | `EconomySettlement.spendableGoods`：把 v3.5 的 35 天保留窗口塞回来（`free = max(0, free − 2905)`） | EconomySettlement 变异态 | 恰**一条**红：`noReserveWindowPaysTheWholeStockDownToZero:282 [★ 有库存就还：整仓 10000 毫粮全部出手…]`；`Failures: 1`（★ 介质序那条仍绿 ⇒ 两条判据互不掩盖） | 同上 ⇒ `OK` |
| **M3-A** | `HouseholdBook`：`Math.addExact(householdFamineDeaths, deaths)` ⇒ `…, 1L)`（口径退回 W3-fix2 的"数事件**条数**"） | `257d8be2…`（HouseholdBook 原件 md5，变异后字节不同） | `FamineMortalityCountingTest…:132 [★★ famineDeaths 必须 = 饥荒致死**人数** 100]`，`expected: 100L but was: 1L`；`Tests run: 2, Failures: 1` | `md5sum -c /tmp/w4b-md5-social-main.txt` ⇒ `HouseholdBook.java: OK` |

### ★★ 加牙前/后对照（同一坏法 M1-A，这是本批 ① 的核心证据）

| 判据形态 | M1-A 下的裁决 | 它看得见什么 |
|---|---|---|
| **加牙前**（每段末成交 > 0，8 个取样点） | **段1/段2 绿 → 段3（day 90）红** | 只报**1 个**日子（day 90）。段 1 内部 day 13/15/18 **三轮零成交**它照样放行（day 30 有 21 笔）；段 4 内 day 95/98/100/103/108/110/113/115/118 **九轮零成交**，只要 day 120 有成交它也放行 |
| **加牙后**（96 轮全扫 + 分布指纹） | **红，且一次报全 24 个零成交轮 + 整份分布** | 24 个零成交轮（是旧判据能指认的 **24 倍**）；且**在旧判据会绿的区段里**也红 |

⇒ 结论：新断言在**同一坏法**下把判别力从"8 个采样点"扩到"全部 96 轮"，并额外守住"某轮笔数变了但没归零"的形态（分布指纹）。

---

## 三、文件清单（新增 / 修改）

**新增**
| 路径 | 作用 | md5 |
|---|---|---|
| `simos-economy/src/test/java/io/mosire/simos/economy/time/DebtBasedClearingAcceptanceTest.java` | ② 的 6 条用例（债务式清算 + 还债轮，反射直调真方法） | `a21ff1e93baea6fcc854ab55cadda474` |
| `simos-economy/src/test/java/io/mosire/simos/economy/testing/TestLogCapture.java` | ★ 本模块测试专用的日志采集器（opt-in；无槽时**全级别关** ⇒ 与 NOP 逐值同行为） | `6adfb388f26d52e47a848e4824f8d630` |
| `simos-economy/src/test/java/io/mosire/simos/economy/testing/TestSlf4jProvider.java` | ★ 最小 SLF4J `SLF4JServiceProvider`（只为上面那个采集器服务） | `2f82a51519fed0edd235caa915a24976` |
| `simos-economy/src/test/resources/META-INF/services/org.slf4j.spi.SLF4JServiceProvider` | SLF4J 2.x 的 ServiceLoader 注册行 | `be492b2ce3f172c0448108f6e2510e54` |
| `simos-social/src/test/java/io/mosire/simos/social/household/FamineMortalityCountingTest.java` | ③ 的 2 条用例（人数 vs 条数判别力夹具 + 缺口 0 负向） | `50c7f530782fb0f0208d6a1feab2de13` |

**修改**（**只加不放宽**）
| 路径 | 改动 | md5（最终） |
|---|---|---|
| `simos-app/src/test/java/io/mosire/simos/app/time/EconomyCycleHealthTest.java` | ① 加牙：逐市场轮零成交断言 + 分布指纹 + 4 条读侧自检；类注不变量④同步；**旧断言一字未动** | `6134053e8369b7860bd6d4a138f1f220` |

**为什么 economy 侧要自建日志装置（如实写）**：`MarketSettlementFixtures` 一族住在 `simos-economy/src/test`，而 `DEBT_REPAY_ROUND.householdsWithNoStockToRepay` 与 `MARKET_DEFERRED_PAYMENT.paidCashMilli` **只有日志读口**；
本模块测试类路径上**没有 log4j**（全仓只有 `simos-core` / `simos-app` 的 pom 声明 log4j，见 `pom.xml:150-160` 与两个模块 pom），
而**改 `pom.xml` 不在测试批的授权写入面内**（只允许 `src/test/**`）⇒ 用 `src/test/resources/META-INF/services` + 一个最小 provider 自建等价装置。
★ **缺省零影响**已由"同轮 288/288 全绿（含全部既有 economy 用例）"背书。

---

## 四、真实证据（不是静态推演）

### 4-1 ① 的分布真值（`simos-app/target/economy-cycle-invariant/summary.txt`，mtime = 本轮 `2026-10-11 07:56:02`，md5 `42c58c230ff72127d1b1a1b4e9bb8051`）

```
★ W4b 加牙（逐市场轮）：轮次=96 零成交轮次=0 min/中位/max=17/35.0/131 Σfills=3260（== MARKET_FILL 3260 行）
★ W4b 加牙（分布 笔数:轮数）：17×10, 18×1, 21×7, 22×9, 23×5, 24×12, 31×1, 34×1, 35×3, 36×4, 37×8, 38×1, 39×2, 40×13, 41×2, 44×1, 53×8, 54×1, 55×3, 56×2, 66×1, 131×1
```

- **与我实跑的真值 vs 派单书给的验收数字：逐项相同**（96 轮 / 零成交 0 / 17,35,131 / 分布逐项）⇒ **没有放宽、也不需要改基线**。
  ★ 差异说明：本测试用 **8 段 × 30 天**推进（分段推进，性能口径），验收报告 §2.9 用的是 **0→240 单次 advance**；两者分布**逐项相同**，与 G3/B2 的"分段等价"一致。
- ★ **首轮踩到的读侧坑（已修，值得留痕）**：`MARKET_REPORT` 发在 **settlement** 分类（`EconomySettlement` 的 `TRACE = EconomyLog.settlement()`），`MARKET_FILL` 发在 **trace** 分类（`RAW = EconomyLog.trace()`，TRACE 级）——
  我第一版挂错了 logger ⇒ 采到 **0 轮**，被新加的"轮次数 == 96"自检**当场拦下**（判据没有静默变弱）；第二版补齐两个采集器后 4 条自检全过。

### 4-2 ② 的逐值对账（日志真行，本轮实取）

```
event=MARKET_DEFERRED_PAYMENT origin=economy-market originKind=tick day=5 commodity=grain
  debtor=hh-buyer creditor=hh-seller currency=silver quantityMilli=2905
  paymentMilli=30 paidCashMilli=0 deferredMilli=30 … outstandingMilli=30
  reason=unpaid-goods-price-becomes-debt-to-seller
```

- `⌈2905 × 10 ÷ 1000⌉ = 30` == `paymentMilli` == `deferredMilli` == `outstandingMilli` == 债合同 `principal` ⇒ **未付价款全额入债**，一分不差。
- `paidCashMilli=0` 与"本轮唯一转移腿 = 货腿、`money()` 为空"两条独立读数互证。

### 4-3 ③ 的判别力

| 户 | 份额 × 有效率 | DEATH 事件 | `count` | cause | 计入 famineDeaths |
|---|---|---|---|---|---|
| 甲（断顿 gap==need ⇒ faminePpm=1000，基准 0） | 100000 × 1000 ppm | 1 条 | **100** | `BASE_PLUS_FAMINE` | **100**（人数口径）／1（条数口径） |
| 乙（缺口 0，基准 1000 ppm） | 10000 × 1000 ppm | 1 条 | 10 | `BASE` | 0 |

⇒ `deaths=110`、`famineDeaths=100`；M3-A 把口径退回条数 ⇒ 读数变 **1**，当场红（`expected: 100L but was: 1L`）。

---

## 五、四类关键项（AGENTS §三.0）覆盖核对

| 类别 | 覆盖点 | 说明 |
|---|---|---|
| **守恒式** | ① 的 `Σ 逐轮 fills == MARKET_FILL 逐笔行数`（3260 == 3260，两处独立读数）+ ① 既有的**逐币种货币守恒**（本次未改、仍绿）；② 的 `新增债务 == 未付价款`（30 == ⌈2905×10/1000⌉）与"货腿毛量出/净量入 + 钱腿为空" | ② 侧是**逐值**守恒，不是区间 |
| **不丢失（身份）** | ② 的债务合同身份逐条断言：`debtor=买方 / creditor=卖方 / unit=Money(silver) / dueCycle`；还款腿 `from=HouseholdActors.of(债务人) / to=HouseholdActors.of(债权人)` | 身份没被"归一/丢失"；★ **未**新增 operator 面的身份用例（既有 `EconomyRoundTripTest` 6/6 绿覆盖形状往返） |
| **静默付 0** | ② 的 `householdWithNothingToPayKeepsTheDebtAndIsCountedAsNoStockToRepay`：没库存 ⇒ **一条腿都不铸**、本金原样 30 **挂账不清零**、`householdsWithNoStockToRepay=1`；正面对照里 `paidCash=0` 的那部分**全额记债**（不是静默免债） | 两条互补：不许"付不出就悄悄当 0"、也不许"付不出就悄悄免掉" |
| **断粮** | ③ 的两条（多人 DEATH 事件按**人数**计 + 缺口 0 ⇒ 饥荒项不触发） | ③ 的夹具是该类唯一可区分"人数/条数"的场景（W3-fix3 §W3f3-7 第 3 条点名） |

---

## 六、我没做 / 没验证的（**如实记**，AGENTS §三.3）

1. **没跑 `clean verify` / `package` / `clean`**（派单书硬纪律：归控制方门禁；`package` 还有覆盖运行中 jar 的风险）。本批所有结论都来自 `test` 目标。
2. **没跑 `simos-app` 的全量测试套件**：只跑了 `EconomyCycleHealthTest` 一个类（本批在 app 侧只改这一个文件，无共享夹具/静态状态被改动）。其余 app 用例**未跑**（`clean verify` 归控制方）。
3. **② 的 `paidCash` / `householdsWithNoStockToRepay` 两条读数靠测试树自建的 SLF4J 捕获器**（不是生产读口）。★ 边界：若将来有人给 `simos-economy` 加上 `log4j-core` 测试依赖，SLF4J 会看到**两个 provider**（行为以 SLF4J 的 provider 选择规则为准，本装置可能失效）⇒ 届时须改回 log4j 的 `AppLogCapture` 形态或删掉本装置。
4. **① 的分布指纹是"基线守卫"**：世界行为**有意**改变（如 W5 铸币入市）时它会红，必须**显式重新基线化**（重跑并把新分布与理由写进账本/提交信息）—— 这是本批刻意选择的强度，**不是**可以放宽的断言。
5. **① 的运行时成本上升**：`EconomyCycleHealthTest` 由 ~45 s（无逐笔日志）升到 **77–98 s**（挂 `DEBUG` + `TRACE` 两个采集器，~3260 条 `MARKET_FILL` + 每轮 `MARKET_REPORT`）。未做"只在需要时开逐笔"的优化（那会把交叉验证判据拿掉）。
6. **② 未覆盖**：真世界（three-powers / small-world）端到端 600 天读数、抢手/崩溃两场景、α=0 钉死价、0 价成交、S-11 产能满足率 —— 这些属 W2 判据 ⑦⑧⑩ 与 §6.3 判据 4，**本批未做**（W2/W2-fix 账本 §7/§5 已把它们列为未覆盖项，仍开放）。
7. **③ 只覆盖"一条 DEATH 事件含多人 + famine 参与"这一格**；未覆盖"同一 tick 多条 famine 事件"（同族另一格，口径上由"逐条累加"覆盖，但**没有独立夹具**）。
8. **未验证**：`famineDeaths` 在**真世界**里的读数（本批只在受控夹具里断言；W3 验收报告的 377 是历史读数，本批**未重跑**）。
9. **未做 `git commit`**（按纪律）。
10. **未做**：`simos-app` 侧的 `spotless:check`（跑的是 `spotless:apply`，见 §七；格式化后**重跑**了三个测试类，全绿）。

---

## 七、格式与工作树

| 项 | 命令 | rc | 结果 |
|---|---|---|---|
| 格式化 | `tools/mvn-lock.sh -q spotless:apply -pl simos-economy,simos-social,simos-app` | **0** | 静默；**改动面 = 恰好本批那 5 个文件**（对三个模块 `src/test` 全树 265 个文件做前后 md5 普查，diff 只有这 5 条）；`src/main/**` 零改动 |
| 格式化后重跑 | `… test -pl simos-economy,simos-social -am -Dtest='DebtBasedClearingAcceptanceTest,FamineMortalityCountingTest'` | **0** | 6/6 + 2/2 全绿 |
| 格式化后重跑 | `… test -pl simos-app -am -Dtest='EconomyCycleHealthTest'` | **0** | 1/1 绿（77.27 s） |
| 工作树 | `git status --porcelain` | —— | ` M simos-app/src/test/.../EconomyCycleHealthTest.java` + 3 个未跟踪目录/文件（见 §三）；**HEAD = `7e11770f`、未 commit** |

**`src/main/**` 零改动（自证）**：`git diff --stat -- '*/src/main/*'` = 空；且三个被动过变异的生产文件 md5 与开工前/还原后**逐字节相同**：
`MarketSettlement.java 2262c885c11c46c89cfa1013e40b27dc`、`EconomySettlement.java c96e0399373915214f855a650bcb612c`、`HouseholdBook.java 257d8be23847b3e9895b0a35f094c587`。

---

## 八、观察（只报不改，`src/main` 不许碰）

1. **`MarketRound.withDeferredPayments` 只带过 `arbitrage` / `fx` / `govMandates` 三个字段**，**不带** `portEnforcement` / `portTax` / `procurementPriority`（`MarketSettlement:1049-1079`）。
   生产路径的注入次序（`withDeferredPayments` → … → 三个口岸/置顶 wither，`EconomySettlement:2195/2219/2234-2239`）**恰好让它不触发**，因此**不是**一个可复现缺陷；
   但这是一个"克隆丢字段"形状的**潜在坑**（谁把 `withDeferredPayments` 挪到那三个 wither 之后，口岸/税/置顶会整段静默失效且毫无报错）。**本批只报不改**。
2. **W2b 账本 §5-1 的缺口已闭**：`src/test` 里现在有 `deferralPossible` / `withDeferredPayments` / `repayDebts` / 介质序 / 无保留窗口的实跑覆盖（本账本 §一 的 ② 行）。
3. **`householdsWithNoStockToRepay` 的语义边界**（读代码得到，非本批断言）：它只数"可动用介质**一件都没有**"的户；"有介质但全部缺价"的户落**另一个**具名面（`DebtRepaymentSkip` / `DEBT_REPAY_UNPRICED`），**不计入**这个数 ⇒ 两者相加**不**等于 `debtorsWithOutstanding`。本批**没有**为"缺价介质"造用例（见 §六-6）。
