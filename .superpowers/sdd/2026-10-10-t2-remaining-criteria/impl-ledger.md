# T2 批：补计划 §5 剩余判据（T6 / J1 / J3 / V-20 / T4-T5）+ 跑真门禁（测试 Agent 账本）

> 判据来源：`docs/superpowers/plans/2026-10-10-market-port-fx-merchant-plan.md` §2.1/§2.2/§2.3/§2.4/§5.1/§5.3/§6.2（V-20）。
> 派单纪律：AGENTS §一.5（测试 Agent 三条）、§三（不信 rc=0 / 变异自证 / 如实写"没做"）、§七（门禁命令与真数）；
> §一.11（老测试删/迁，不为兼容写夹具）。本轮**未 commit**；**未改** `src/main/**`、`pom.xml`、`docs/**`、`AGENTS.md`、
> 其他 `.superpowers/**`（只写本文件）。

---

## 1. 新增测试（按判据写，不按代码反推）

| 文件（全部在 `simos-economy/src/test/java/io/mosire/simos/economy/time/`） | 用例 | 判据 |
|---|---|---|
| `FxPurchasingPowerAcceptanceTest.java`（6 条） | ① `needCostIsTheBasketSumAndTheCheapestToPayIsTheStrongest`：cost = Σ need×价÷1000 逐值手算（4 vs 5）⇒ **付得最少者最强**；强度全序/最弱先换的首项；② `tiesOnPurchasingPowerBreakByCurrencyId`：同 cost 按币种 id 升序（插入序反向塞表 ⇒ 破平若用迭代序会得到另一答案）；③ `aMissingPriceInTheBasketMakesTheWholeCurrencyUncomparableButFreeIsComparable`：篮子里任一需求>0 的商品缺定价行 ⇒ **整币算不出**；"明确 0 价"照算；需求 0 的键缺价不算缺；④ `limitComesFromTheSamePurchasingPowerAndZeroMeansDoNotQuote`：F-4 限价 1250/800、分母或分子 ≤0 ⇒ 0 = 不挂；⑤ `zoneTablesTakeTheAnchorMarketAndRefuseMismatchedNumeraires`：取价点 = 法定区锚格、计价币漂开 ⇒ 该币无价表、家户自己区优先；⑥ `anEmptyBasketHasNoReadingAtAll` | **T5**（+ F-1/F-2/F-4/V-7） |
| `FxHouseholdRoundAcceptanceTest.java`（3 条） | ① `householdConvertsItsEntireWeakBalanceAndTheCounterpartyReceivesIt`：两区两户（篮子不同 ⇒ 最强币不同 ⇒ 民间簿自撮合）：X 卖银买铜、**成交 base × 自己的限价 ÷ 1000 == 弱币全部余额（100,000）**（F-3 挂单全部）；两腿等值 + 四个余额逐值；② `partialFillLeavesTheRemainderForTheNextRoundWhichRequotesFromWhatIsLeft`：对手方只给 4,000 ⇒ 部分成交；下一轮按**剩下的余额**重挂（7,900，≠ 第一轮的 10,000）；③ `aZoneWithoutAQuoteForTheNeededGoodPlacesNoOrderAtAll`：铜区缺 grain 定价行 ⇒ 零成交、四个余额一个数不动；对照世界（补上该定价行）立刻成交 | **T4**（挂单/循环）+ **V-7** 端到端 |
| `MarketPayCurrencyAcceptanceTest.java`（2 条） | ① `anOrderWithoutACurrencyPaysInTheLocalNumeraireAndIsValueIdenticalAcrossThreeWorlds`：**三世界对照**（单币 / 有铜区但不持铜 / 持铜但银更强）⇒ 成交量·单价·货款·运费·损耗·单价币全逐值相同 + 冻结轴恰为 `{silver}`；② `payingInAForeignCurrencyMovesTheMoneyLegFreezeAndBudgetToThatCurrency`：买方最强币为铜 ⇒ `paymentCurrency=copper`、`unitCurrency=silver`（价格尺度不动）、货款 = ⌈1,452×10,000÷1000⌉=14,520 毫铜、买方银**一个数不动**、卖方收款在铜、**冻结轴 = {copper}**、诊断可花额落在铜上；对照组（铜给足）⇒ 整笔 2,905 | **T6 ①②** |
| `CapacityTruncationPricingAcceptanceTest.java`（2 条） | ① `truncatedCrossHexQuantityNeverEntersTheDemandSupplyStatistics`：**有运力 vs 无运力两案价格表逐值对照** —— 无运力 ⇒ (demand,supply)=(0, S−D) ⇒ 9,500；有运力 ⇒ (D,S) ⇒ 9,921；且两案**互不相等**、都不等于把截断量算进去的值（判别力所在）；② `bothTheDemandAndTheSupplySideExcludeTheTruncatedPart`：加一个**同格**买方（其成交不吃运力）⇒ 需求侧不再被剔光 ⇒ (2,905, 10,000−2,905) ⇒ 9,790 与三个"只剔一侧/都不剔"的候选值 9,725/9,950/9,867 全不相等 ⇒「两侧都剔」可分辨 | **V-20**（+ §2.4 M2） |
| `PortPolicyMerchantLaneAcceptanceTest.java`（2 条） | ① `aZoneRestrictionRemovesTheLaneCandidatesInsteadOfWorseningProfit`（**J1**）：基线 lane 用量 = 成交毛量、运力真被占；zone-a 禁出 ⇒ 零成交、具名 `PORT_THROTTLED`（买卖两侧）、**不含** `LOGISTICS_CAPACITY`、lane 用量 = 0、`bottleneck=false`、**运力池余量 = 满额**（一分未用）、卖方挂单量与报价逐值不变（= 不是"利润变差"）、货一格未动；② `anUnrestrictedWorldLeavesEveryMerchantSideReadingValueIdentical`（**J3**）：显式两侧全开 vs 根本不注入 ⇒ 成交/余额/lane 四读数/运力池总量与余量/卖方读数/未成交归因/下一轮价格表**全部逐值相同**；两侧的政策门控族读数（税项、按币税表、具名归因）**零输出** | **J1 + J3** |

**夹具扩三处**（`MarketSettlementFixtures.java`，+61 行，全是测试专用）：
`household(id,hex,pop,商品账,货币账,自然需求篮子)`（旧 5 参重载委托它 ⇒ 旧用例逐值不变）、`withFx(Round,FxRoundInput)`、
`settleWithPool(World,Round,MerchantCapacityPool)`（用例持有池 ⇒ 才能读**轮末**余量）。

---

## 2. 变异自证（§三.2：编原件参照 → 按目标类名推入 → 当场红 → 还原 → 比 md5）

| # | 变异体（`src/main`，只临时改、已还原） | 原件 md5 | 变异 md5 | 期望红 | 实测红（逐字） | 还原 md5 |
|---|---|---|---|---|---|---|
| M1 | `MarketSettlement.orderCurrencyFor` → 恒 `return market.numeraire();`（= 退回"强制本格币"） | `26f64ead7c7f7385dbea020dd780b790` | `1c9cca43c6232a9c4e973a671ec1f633` | T6 ② 支付币 | ✅ `MarketPayCurrencyAcceptanceTest.payingInAForeignCurrency...` 红在 `[★ T6：成交后卖方收到的就是买方指定的币（铜）]`：`expected: copper but was: silver`（1 失败/2；① 三世界对照仍绿 ⇒ 只打中该打的那条） | `26f64ead…` ✅ |
| M2 | `MarketSettlement.adaptPrices` → 两侧都**不再**减 `capacityTruncatedMilli`（"只改读数不改统计"） | `26f64ead…` | `c99bc798f6a61009e2c4e33d621ccdee` | V-20 两案对照 | ✅ 2 条全红：`truncatedCrossHex...` 红在 `[★ V-20：无运力那一案的价格表 = 用剔除后的 demand/supply 算出的值]`：`expected: 9500L but was: 9921L`（9921 = 我断言的那个反例值）；`bothTheDemandAndTheSupplySide...`：`expected: 9790L but was: 9867L` | `26f64ead…` ✅ |
| M3 | `MarketSettlement.matchRoute` → `long portLeft = PortThrottle.NO_GATE_MILLI;`（闸不生效） | `26f64ead…` | `7a939fc0c97b735064b135b430c07ab6` | J1 零候选 | ✅ `PortPolicyMerchantLaneAcceptanceTest.aZoneRestriction...` 红在 `[★ J1：被管住的那类根本不成候选 ⇒ 零成交]`：`Expecting empty but was: [Fill[... to=1_0, commodity=grain, quantity=2905 ...]]`（1 失败/2；J3 仍绿） | `26f64ead…` ✅ |
| M4 | `HouseholdPurchasingPower.needCostMilli` → 缺价 `continue`（当成"这一件不计"） | `289ec8285b19d5ae02ea851b66adbcdc` | `932c795ed7d9b1c26cc98ca03a55706e` | T5/V-7（单元 + 端到端） | ✅ 先红单元：`FxPurchasingPowerAcceptanceTest.aMissingPrice...`：`Expecting an empty OptionalLong but was containing value: 1L`。★ **端到端那条一开始是绿的（等价存活）** ⇒ 我把它的篮子改成**两件商品**（原篮子只有 grain，缺的那件就是全部 ⇒ 变体下 `any=false` 仍返回空）⇒ 重跑同变异：`FxHouseholdRoundAcceptanceTest.aZoneWithoutAQuote...` 也红：`Expecting empty but was: [FxFill[... base=copper, quote=silver, baseMilli=50000 ...]]` | `289ec828…` ✅ |

⇒ **四例全部"当场红且红在被保护的那一行"**；M4 的"第一次等价存活 ⇒ 改夹具拿到判别力"如实记在上面。
**未自证的**：本次新增的其余断言（J3 的逐值一致族、T6 ① 的三世界逐值族、V-20 的两案对照族里除已被 M2 覆盖的算式外）
不是逐条变异过的；它们靠"两案/三世界对照 + 反例不等"提供判别力，不靠单点变异。

---

## 3. 真门禁（确切命令 + 真数 + 耗时）

```
rm -rf */target/surefire-reports
tools/mvn-lock.sh clean verify          # 前台：10:27:57 → 10:33:12
node simos-app/src/test/js/run-gate.cjs
```

| 模块 | 条数 | 失败 | 错误 | 跳过 |
|---|---|---|---|---|
| simos-util | 211 | 0 | 0 | 0 |
| simos-map | 379 | 0 | 0 | 0 |
| simos-calendar | 33 | 0 | 0 | 0 |
| simos-social-api | 3 | 0 | 0 | 0 |
| simos-actor-api | 8 | 0 | 0 | 0 |
| simos-economy-api | 50 | 0 | 0 | 0 |
| simos-social | 215 | 0 | 0 | 0 |
| simos-unit | 518 | 0 | 0 | 0 |
| simos-core | 231 | 0 | 0 | 0 |
| simos-sd | 232 | 0 | 0 | 0 |
| simos-actor | 130 | 0 | 0 | 0 |
| **simos-economy** | **281**（266 → +15） | 0 | 0 | 0 |
| simos-gov | 111 | 0 | 0 | 0 |
| simos-army | 91 | 0 | 0 | 0 |
| **simos-app** | **907** | 0 | 0 | 5 |
| **合计** | **3400** | **0** | **0** | **5** |

- 报告 mtime 全落在本轮（10:28:02–10:32:50，起点 10:27:57）；`**/target/surefire-reports/*.txt` = **403 个类**。
- **Spotless**：`spotless:3.10.2:check` 16 次（父 + 15 模块）全过（BUILD SUCCESS 的前置）。
- **Checkstyle**：16 行 `You have 0 Checkstyle violations`（含聚合行）。
- **SpotBugs**：**15 个模块 `BugInstance size is 0`**（本轮的 app 也在 `clean verify` 里跑到了 —— 上一批是因为 app surefire 先红才没跑到）。
- **前端门禁**：`node simos-app/src/test/js/run-gate.cjs` ⇒ `[frontend-gate] OK tests=412 pass=412 fail=0`；
  ★ 本批 `clean verify` 里**也**跑到了它（日志 56403 行同一条 OK）——与上一批不同，故无需补跑。
- **5 条跳过**：`RealLlmGovScenarioTest` 2 + `RealLlmUnitDecisionLoopTest` 3（既有环境门控，非本轮引入）。
- **耗时**：`clean verify` **5 分 14 秒**（比上一批的 3:36 长：本轮 app 跑到前端门禁 + 机器负载）。

**为修门禁做的生产代码修改**：**无**（Spotless/Checkstyle/SpotBugs 一次全绿；唯一改动是测试文件）。

---

## 4. 发现但**未改**的实现缺陷（`src/main` 禁改，按派单书只报）

**D-1（V-20 的超额剔除：被服务的跨格量也被剔出价格统计）**——`MarketSettlement.blockLaneWithoutCapacity`（`:6796-6830`）
把**对侧卖槽的整个余量**加进买槽的截断量（`buy.capacityTruncatedMilli += sellTotal`），**没有按本侧余量封顶**，
与方法自己的注释（"两侧的截断量各自按对侧余量封顶（min(本侧余量, 对侧余量)）：买方要 100、卖方只剩 30 ⇒ 双方各记 30"）矛盾。

- **实测**：运力 = 1,000（< 需求 2,905）时，成交 1,000、未成交 1,905（卖方剩 3,000）；撮合循环**重试**同一条 lane，
  第二次调用本方法时 `sellTotal = 3,000` ⇒ 买槽截断量累计 1,905 + 3,000 = 4,905 > 挂单量 2,905 ⇒
  `adaptPrices` 的 `quantity − min(quantity, truncated)` = **0** —— **连已经成交的那 1,000 也从 demand 里被剔掉了**。
  结果：价格表 = `adaptiveNextPrice(10,000, 0, 1,095)` = **9,500**，而"只剔真正被截断的 1,905"应为 **9,823**
  （我原按判据写的期望值）。判别力佐证：`capacityTruncatedMilli > 本槽统计量` 这一形态只在**跨 lane 重试**时出现。
- **性质**：V-20 的方向（被截断的部分不进价格表）**成立**；本缺陷是**精度**面 —— 已服务的那一份也丢了，
  价格信号因此比真实市场更小。**是否要把"已服务量"留在统计里需要裁**（可能是有意的保守口径）。
- **建议的最小修**（供裁，未做）：`buy.capacityTruncatedMilli += Math.min(buyTotal, sellTotal)`（与注释一致），
  或把截断量改成"本槽本轮真正未服务的量"的净额记账。

**观察 O-1（lane 读数的时点）**：被口岸完全拦下的 lane 仍在 `report().routes()` 里有读数，且 `demandMilli/supplyMilli > 0`
（它们在该轮 transport-loop 里先算、闸在随后才生效），但 `used = 0`、`bottleneck = false`。⇒ J1 的"不出现候选"在
**现行读数面上的可判形态 = 用量 0 + 具名 `PORT_THROTTLED` + 运力未消耗**，不是"lane 读数整条消失"。已如实写进 J1 用例的断言与注释。

**观察 O-2（诊断读数的时点）**：`MarketReport.BuyerOutcome.spendableMoneyMilli` 是**结算之后**收集的（冻结已释放、成交已落账），
所以 T6 ② 里它是"付完剩下的铜（5 毫）"而不是挂单当时预算（14,525）。判据（预算按买方支付币）改由"挂单量被铜预算封顶 = 1,452"
+ 冻结轴币种 = copper 两条钉住；本类注释已写明该读数的时点。

---

## 5. 我没做 / 没验证的（如实）

1. **J3 的"商号利润读数逐值不变"**：`MerchantProfitBook` 是 `MatchContext` 的**内部逐轮瞬态**，不进任何公开读数
   （`MarketReport`/`MarketOutcome` 都没有它）⇒ 我用**可读的商户面**替代：逐 lane 用量/供需/瓶颈、运力池总量与余量、
   `toolBlockedRuns`、卖方成本档与报价、未成交归因、下一轮价格表。**没有**对照利润簿本身。
2. **T4 的"换完后不再挂"**：只证到"部分成交 ⇒ 下一轮按剩余额重挂"。没构造"弱币真的归零 ⇒ 下一轮不挂"的第三轮
   （现夹具里成交价 = 买卖限价中点 ⇒ 余额不会被恰好花光；要构造需另设限价对称的一对户）。
3. **T6 ② 的冻结"金额"**：冻结在本轮内会被释放（`releaseAllFreezes`），轮末只能读到**冻结轴的币种**（值为 0）。
   ⇒ "冻结按该币"由轴的币种 + 预算封顶的数值共同钉住；**轮内冻结额**没有独立读数，未测。
4. **V-20 的"部分运力"一案**：本想要"两侧都剔"在同一案内的完整判别力，但受 D-1 影响，该案 demand 侧被剔光 ⇒
   改用"同格买方 + 跨格买方"的形状（用例②）。D-1 本身**没有**写红用例（怕在没有裁定的情况下往门禁里加必然红的用例）。
5. **不在本批范围的判据**：T1/T2/T3/N1–N5 已由上一批覆盖（`PortGateTaxAcceptanceTest` 等），T7/T8/T9、N6/N7 仍未写；
   真实 world 360 tick 读日志未跑（AGENTS §七：长跑不进程序化门禁，另走真实 world）。
6. **未做**：任何 `src/main/**` 修改；commit（按派单由控制方审后提交）。
