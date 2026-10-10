# T 批：按验收判据补测试 + 清理被改架构的旧测试 + 跑真门禁（测试 Agent 账本）

> 判据来源：`docs/superpowers/plans/2026-10-10-market-port-fx-merchant-plan.md` §5（T1–T9 / N1–N7 / J1–J3）+ §3 I-C1..I-C10。
> 派单纪律：AGENTS §一.5（测试 Agent 三条）、§一.10/§一.11（老架构老测试直接删/迁）、§三（不信 rc=0、变异自证、如实写"没做"）、§七（门禁命令与真数）。
> 本轮**未 commit**（由控制方审后提交）。**未改** `pom.xml` / `docs/**` / `.superpowers/**`(除本文件) / `AGENTS.md`。

---

## 1. 删除与迁移的测试文件（逐条理由）

| # | 文件 | 处置 | 为什么 |
|---|---|---|---|
| 1 | `simos-economy/.../time/MarketRegulationTest.java` | **删**（368 行） | 整文件测的是 **P-T1c 已删的机制**：8 参 `MarketRegulation`、`referencePrices/open/rules/quotaPerWindow/bidPerMille/askPerMille`、`MarketUnfilledReason.REGULATION_QUOTA`（9 处构造 + 6 处访问器）。§一.11 第 3 条：旧测试直接删，新测试按新判据另写。 |
| 2 | `simos-economy/.../change/EconomyRoundTripTest.java` | **迁移** | ★ 铁律 5 的往返不变式（反射枚举 36 个 `EconomyData` 组件）**不能消失**。迁移点：① 删 `MerchantFirm` import / `merchantFirm()` 夹具 / `MERCHANT_ORGANIZATION` / `mutate`+`changedOf` 的 `merchantFirms` 分支（M-A1 退役该持久组件）；② `OperatorCondition` 夹具按 **P-T4 新形状**给两个**逐币表**（`lastCycleRevenueByCurrency` / `cycleRevenueByCurrency`，原来是两个 `0L`）；③ 组件计数 **37 → 36**、方法名 `changeSetHasExactlyThirtySixComponents`（两侧同名同数，双向子集断言仍成立）。 |
| 3 | `simos-economy/.../time/ExpectedProfitBookTest.java:235` | **删一行** | `fixture.base().merchantFirms()` 引用已退役组件；该断言只是"夹具里没有商号"，删掉不损失判别力。 |
| 4 | `simos-economy/.../time/MarketDemandBookTest.java:108` | **改夹具** | `new MarketReport(...)` 的两个运费读数由标量 `0L` 改为**逐币空表** `Map.of()`（P-T4 / I-C10）。 |
| 5 | `simos-economy/.../time/MarketSettlementSingleHexLossTest.java` | **迁移 + 换世界形状** | ① 两个运费访问器 `isZero()` → **空表** `isEmpty()`（P-T4）；② 跨格用例的**行为**变了：M-A1 起"没有跑商家户 ⇒ 跨格车道不建（具名 LOGISTICS_CAPACITY）"，原用例世界没有运力 ⇒ 那条**损耗守恒**判据会变成"没被测到"。⇒ 给世界补一个**只提供运力**的跑商家户（0 人口/空需求/空货账/空钱账，不生成任何订单），并改用 `settleWithCarriers`；随之把"买方货币 = 初始 − 货款"更新为 **− 货款 − 运费**，并新增"运费进承运户 + 运费读数按币分列"两条断言。 |
| 6 | `simos-app/.../McpCoverageTest.java:489` | **改载荷** | 旧 `gov.SetPortPolicy` 载荷（`commodityRestrictionPerMille` / `currencyRestrictionPerMille` 两个标量表）换成**新四元组**：`commodityRules{商品→{入/出限制 + 入/出税}}` + `currencyRules{币种→挂单类型→规则}` + `marketControl`。该用例的语义仍是"形状合法但缺前置（u-1 不是 GOV）⇒ 具名拒"。 |
| 7 | `simos-gov/.../spi/GovCommandHandlersTest.java` | **新增成功路径** | `McpCoverageTest` 只证明"非法/缺前置被挡"；**只有一条腿的话 `handler 恒拒` 也能全绿** ⇒ 按派单书补成功路径（见 §2-N1）。 |

**未改判据**：所有迁移都是"断言按**新契约**写"。唯一一次"文档 vs 实现"的判定见 §5 的红项（那是**实现缺陷**，不是判据冲突）。

---

## 2. 新增测试（对应判据）

| 文件 | 用例 | 判据 |
|---|---|---|
| `simos-economy/.../time/PortGateTaxAcceptanceTest.java`（9 条，新） | ① `twoSidedGateMultipliesBothSidesAndCapsTheFilledQuantityExactly`：单侧 25‰ ⇒ 成交 = min(需求, ⌊transit×25÷1000⌋)；**两侧各 25‰ ⇒ 成交 = ⌊transit×25×25÷1e6⌋**，且 strictly < 单侧那个数（**取 min 会得到单侧值 ⇒ 口径可区分**）<br>② `eitherSideFullyClosedLeavesZeroCandidatesNamedPortThrottled`：任一侧 E=0 ⇒ 零候选 + 具名 `PORT_THROTTLED` + **不含** `LOGISTICS_CAPACITY`<br>③ `bothSidesFullyOpenIsValueIdenticalToNoPolicyAtAll`：显式全开 == 不注入（逐值：量/货款/运费/损耗/未成交归因）<br>④ `exitAndEntryTaxAreCollectedOnceEachAndLandInTheirOwnTreasury`：出口+进口**各恰好一条**；税额逐值 = 毛量×税率；**钱真进两个国库户**；买方总支出 = 货款+运费+Σ税（I-C1）；`taxByCurrency` 按币分列<br>⑤ `adValoremTaxBaseIsGoodsValueOnlyAndNeverIncludesFreight`：从价 500‰ ⇒ 税 = 货款÷2，且 **≠ (货款+运费)÷2**（税基不含运费，运费>0 时两种口径可区分）<br>⑥ `inZoneMarketTariffIsChargedOnceAndNotOnCrossRegionFills`：区内市场税**恰好一条**、金额 = 毛量×tariff；跨区笔不重复收；无政策世界零税项<br>⑦ `currencyGateRequiresBothTheSourceExitAndTheDestinationEntry`：钱的目的区禁入 / 来源区禁出 ⇒ 各零候选 + 具名 `CURRENCY_NOT_ACCEPTED`，且与另两种归因**互不冒充**<br>⑧ `sameRegionAlwaysPassesAndGateDoesNotConsultLegalTenderStatus`：同区恒放行；禁 `LENDING` 不影响 `COMMODITY`（挂单类型分维）；★ 红线：**判据不读币种法定区**（即使 silver 是两区计价币，规则一设就真挡住）<br>⑨ `missingCurrencyRuleMeansNoRestrictionNotFullBan`：缺键 = 放行（不是"没规则就全禁"） | **T1/N1/T2/T3/N4 + I-C1/I-C10 + J2 + ③红线** |
| `simos-economy/.../time/MerchantCapacityAcceptanceTest.java`（8 条，新） | 运力 = 劳动+工具（派生量）、tier/半径派生；只有"选了跑商"的家户进池；**分配总量 ≤ 运力总量** + Σ分配+未分配 == 请求量；议价权序（占比降序→id 升序）；**报价口径限价升序优先**；**缺工具 ⇒ 该次跑商不成立**（具名 `toolBlockedRuns`，补足即可跑）；超半径零分配；端到端**成交毛量 ≤ 本格运力** + 残余具名 `LOGISTICS_CAPACITY` | **⑦ + M0/M0b/M0c/M2/M3 + A3（分配≤运力）** |
| `simos-economy/.../time/ProcurementPriorityAcceptanceTest.java`（7 条，新） | 置顶 + **消耗 = 被超越户数**；池空 ⇒ 一步不动且**池子不许为负**；只剩 2 份 ⇒ **越过付得起的 2 户后就地停住**（消耗恰好 2、池归零、硬停 1 次）；**只改顺序不改价/量**（价与量逐条不变、集合不变）；缺省中性（无人管控 ⇒ 名次=服务序、零消耗）；池跨簿共享不超支；**同一户两只槽位只算一户** | **④ + T9 + N3 + I-C2** |
| `simos-economy/.../time/MarketCurrencyReadoutTest.java`（5 条，新） | 异币成交 ⇒ `landedUnitPriceMilli()` **无定义**（两币不许相加），同币才有定义；运费币 = 买方支付币；`taxByCurrency` 按币分列（14 与 4 **不许加成 18**）、`taxByLayerGovernmentCurrency` **层这一维保留**；`OperatorCondition` 逐币列在、**旧标量组件名不存在**；`MarketReport` 上那一族标量金额访问器已删 | **⑧ + N5 + I-C10 + §一.11** |
| `simos-gov/.../spi/GovCommandHandlersTest.java`（+2 条） | `setPortPolicyAppliesFourTupleRulesAndRebuildsExactPolicy`（四元组逐个数落盘 + 幂等空变更集）；`setPortPolicyRejectsIllegalPoliciesByNameWithZeroRevision`（负税 / 未登记挂单类型 / 拼错字段名 / marketControl 非布尔 / 非 GOV 单位 / 单位不存在 ⇒ **六种具名拒**） | **N2（非法政策具名拒、零 revision）+ T-正向成功路径** |

**规模**：economy 测试 237 → **266**（+29 新用例，−0 净迁移损失）；gov 109 → **111**。
**未被削弱**：`EconomyRoundTripTest`（铁律 5）、`MarketSettlementSingleHexLossTest`（损耗守恒 + 货币守恒）覆盖**只增不减**。

---

## 3. 变异自证（§三.2：改坏 ⇒ 当场红 ⇒ 还原 ⇒ 比 md5）

每一例都是"编一份原件作参照 → 按**目标类名**推入变异 → 跑目标用例 → 红在**被保护的那一行** → 还原 → 比 md5"。

| # | 变异体（生产代码） | 原件 md5 | 变异 md5 | 期望红 | 实测红（逐字） | 还原 md5 |
|---|---|---|---|---|---|---|
| M1 | `PortThrottle.allowedTransitMilli`：`transit×E源×E目的÷1e6` → **取 min(E源,E目的)** | `95353961c6c8daeb39d5c793630fc858` | `486a644635ef5c4a9bdc1284cf0d93d1` | 两侧闸相乘 | ✅ `PortGateTaxAcceptanceTest.twoSidedGate...` 红在 `[★ 两侧相乘 < 取 min（min 会等于单侧那个数）]`：`expected 4927L to be less than 4927L`（1 失败/9） | `95353961…` ✅ same |
| M2 | `ProcurementPriorityOrder.advance`：去掉 `if (left <= 0) blocked=true; break;`（= 允许先超后欠） | `2983ee81c4634c1c2d99629acc598e71` | `9c1dfb6b8a1453123206f8c2d1de221c` | 见底硬停 | ✅ `ProcurementPriorityAcceptanceTest.partialPoolStops...` 红在 `[★ 消耗恰好 = 池子原有份数]`：`expected 0L but was -1L`（池子被扣成负） | `2983ee81…` ✅ same |
| M3 | `MarketSettlement.taxesFor`：出口税 `collectPortLayer(...)` **调两次**（双重计税） | `636cf81de7295c8227fe2c3ea098aff2` | `e3c85fa067774da60ef7f2f636996366` | 税的三层各一次 | ✅ 2 条红：`exitAndEntryTax...` 红在 `[★ 三层里这一笔只有两层]` → `Expected size: 2 but was: 3`，表里**两条 PORT_EXIT** 并列可见；`adValorem...` 红在国库户余额 `expected 15L but was 30L` | `636cf81d…` ✅ same |
| M4 | `EconomySettlement.applyTransfer`：**收货方照收、付货方不扣**（货物守恒破坏） | `b2ce8a75bc1fafd54318bd1803b4bcfb` | `016271727c94ae945ca1a97abc5aacb3` | 守恒式（货物） | ✅ `MarketSettlementSingleHexLossTest.sameHexFillHasZeroLossAndConservesExactly` 红在 `[★ Σ余额 + losses 守恒]` | `b2ce8a75…` ✅ same |
| M5 | `EconomySettlement.applyTransfer`：货币**只扣不入账**（货币守恒破坏） | `b2ce8a75bc1fafd54318bd1803b4bcfb` | `1e6419c28d593703e4d8f650888ef09e` | 守恒式（货币） | ✅ `adValoremTaxBase...` 红在 `[★ 税进国库户]`：国库户 `expected 15L but was 0L`（钱凭空消失） | `b2ce8a75…` ✅ same |

⇒ **四类关键项全部有判别力、无一例"等价存活"**（M5 是守恒式的货币面，与 M4 的货物面分开各证一次）。
**未自证的**：`J1/J3`（政策→商户行为端到端、无政策世界商户侧逐值不变）、`⑤ FX 购买力`、`⑥ 订单可选币`、`N6 走私档不存在`、`N7 确定性两跑`——见 §6。

---

## 4. 真门禁（确切命令 + 真数 + 耗时）

```
rm -rf */target/surefire-reports
tools/mvn-lock.sh clean verify                       # 前台，08:00:26 → 08:04:02
tools/mvn-lock.sh -DskipTests verify -pl simos-app -am   # 补跑 app 的 SpotBugs + 前端门禁（app surefire 先红 ⇒ 这两步在上一轮没跑到）
node simos-app/src/test/js/run-gate.cjs               # 前端门禁真数
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
| **simos-economy** | **266** | 0 | 0 | 0 |
| **simos-gov** | **111** | 0 | 0 | 0 |
| simos-army | 91 | 0 | 0 | 0 |
| **simos-app** | **907** | 0 | **6** | 5 |
| **合计** | **3385** | 0 | **6** | 5 |

- **Spotless**：clean（首轮曾红 → `tools/mvn-lock.sh -q spotless:apply` 只做格式化，之后全绿）。
- **Checkstyle**：15 个模块 `You have 0 Checkstyle violations`（16 行含聚合）。
- **SpotBugs**：`clean verify` 里 14 个模块 `BugInstance size is 0`（**app 的 SpotBugs 在那一轮没跑到**，因为 app surefire 先红）；单独 `-DskipTests verify -pl simos-app -am` ⇒ **app 也 0 bugs / BUILD SUCCESS**。⇒ 15/15 模块 SpotBugs = 0。
- **前端门禁**：`[frontend-gate] OK tests=412 pass=412 fail=0`（★ 412 与 `run-gate.cjs` 的 `MIN_TESTS` 一致；此步在 `clean verify` 里**没跑到**，是单独跑的，见上）。
- **耗时**：`clean verify` **3 分 36 秒**（08:00:26 → 08:04:02）。
- **5 条跳过**：`RealLlmGovScenarioTest` 2 + `RealLlmUnitDecisionLoopTest` 3（既有环境门控，非本轮引入）。

**为修门禁做的生产代码修改（全部"不改变行为"级）**：

| 文件 | 修改 | 类别 |
|---|---|---|
| `simos-economy/.../spi/EconomyPayloads.java` | 删掉**从未被调用**的私有 `optionalBoolean` | 死代码（UPM_UNCALLED_PRIVATE_METHOD） |
| `simos-economy/.../time/FxSettlement.java` | `powerText`：`keySet()`+`get()` → `entrySet()` | 迭代惯用法（同样语义） |
| `simos-economy/.../time/MarketSettlement.java` | 删掉从未被读的死局部量 `long nominalFreight = freightOf(...)` | 死存储（DLS_DEAD_LOCAL_STORE） |
| `simos-economy/.../time/ModeMigrationSettlement.java` | 删掉只存不读的 `removedEnterprises`（`enterprises.remove` 等写口一字不改） | 无用对象（UC_USELESS_OBJECT） |
| `simos-app/.../time/PortRegimeBridge.java` | `buildPortTaxInput`：两层 `keySet()`+`get()` → `entrySet()`（外层与内层各一处） | 迭代惯用法 |
| `simos-app/.../world/EconomySeeder.java` | 删掉只算不读的 `merchantPrincipalPosition` | 死存储 |

★ 这 6 处**全部是 13 个生产批次留下的、从没被门禁跑过的静态分析债**（派单纪律"只到编译过"），不是判据问题，也不涉及任何数值行为。

---

## 5. 红：唯一一条根因（**实现缺陷，需要裁/修**）

**6 个错误 / 5 个测试类，全部同一个根因**：

```
GovGenesisZ6Test.oneHundredTwentyDaysAdvanceWithoutContractFailure            (1 error)
Z7D1CapitalProvinceTest.provinceTaxNeverHitsSeatOrCentralTreasuryAndStillCollects (1)
GovernmentServiceLaborBridgeZ6Test.dynamicModifiersInjectConsumeThenReturnToNeutral (1)
EconomyCycleHealthTest.twoFullCyclesInRealThreePowersWorldKeepMoneyConservedAndMarketAlive (1)
Z7RemittanceE2ETest（2 条）                                                     (2)
```

**现象（逐字）**：

```
java.lang.IllegalStateException: 转移会花掉家户账上已冻结的商品（冻结只表达已明确的占用）：
家户=hh-0_0-urban-rich_peasant 商品=tool 余额=4842 冻结=7223 扣减=9；
转移=Transfer[id=tr-3-50, day=3, ..., goods={tool=9}, reason=MARKET_TRADE, ...]
  at EconomySettlement.validateApplyTransfer(EconomySettlement.java:8449)
  at MarketSettlement.executeTrade(MarketSettlement.java:6446) → pairUp(:5479)
```

**根因（读代码定位）**：`MarketSettlement.settleHaulRuns`（`:6962-6988`）把 M-C 的"跑商一次性消耗工具"落成**直接改会话商品账**：

```java
long consumed = Math.min(stock, cost);          // :6975  ← 上限只看 stock，**没减掉 householdFrozenGoods**
setHouseholdStock(round.householdGoods, choice.household(), TOOL, stock - consumed);  // :6977
```

它**绕过了唯一写口 `applyTransfer`**，因此也不受"余额 − 扣减 ≥ 冻结"这条校验约束。时序：

1. **订单生成**先把该户卖 `tool` 的挂单冻结掉（冻结 = 7223，此时余额 ≈ 12065）；
2. **结算阶段**跑商烧掉工具 ⇒ 余额直接掉到 4842（**冻结仍是 7223 ⇒ "冻结 > 余额"这个非法态出现**）；
3. 同一轮该户的 tool 卖单成交 ⇒ 走 `applyTransfer` ⇒ 校验 `stock − 9 < frozen` ⇒ **硬抛**（世界推进当场崩）。

- 6 条报错的 `商品` **全是 `tool`**、`冻结 > 余额` 全都成立 ⇒ 与上述唯一路径一致；`simos-economy` 的 266 条（含我新写的运力/税/闸用例）**全绿**，说明这是"真实多轮世界才走到"的路径。
- ★ 代码注释 `:6952` 已想到"同轮工具可能已被卖单卖掉一部分"，但**实扣上限只取了 `stock`，漏了 `frozen`**。
- **不在我的授权面**：这是**改行为**的修（要动"烧工具是否/如何让路已冻结量"的口径 = M-C/H-1 与冻结语义的交互），按派单书"若某条要求改行为 ⇒ 停手上报"。**建议的最小修**（供裁）：`consumed = Math.min(Math.max(0L, stock - frozenTool), cost)`，或让烧工具腿走 `applyTransfer`（既守冻结、又回到唯一写口）；两条都要顺带决定"工具被冻结时该次跑商算不算成立"（fail-closed 方向）。
- **需要谁裁**：控制方 → 若涉及"冻结的商品能不能被生产/跑商消耗"这条口径，按 §一.8 三级处置可能需要用户裁定。

---

## 6. 我没做 / 没验证的（如实）

1. **⑤ 汇率层**（T4 换汇循环 / T5 购买力口径 / 缺价不换）：**未写用例**。读了 `HouseholdPurchasingPower`/`FxSettlement` 的接口形状但未落测试；该批的"多币世界 + 各级法定区锚格都有篮子定价"夹具成本高，本轮时间用在了 ①–④⑦⑧。
2. **⑥ 订单可选币**（T6：买方指定支付币 ⇒ 卖方收到该币）：**未写用例**。3c/P-T5b 的 `MarketPayChoice` 是包内私有面，需要另建入口夹具。
3. **J1/J3**（政策⇒商户行为端到端 / 无政策世界商户侧逐值不变）、**N6**（"走私"档不存在，只做了静态确认：`*/src/test` 对 `SmugglingSplit/SEIZURE_BASELINE_MILLI/PortRegimeAggregation.split` 零命中）、**N7**（两跑确定性 / 1-4-8 线程）：**未写用例**。
4. **V-20 的"超出部分不进价格表"**：只证到"成交被运力截断 + 残余具名 LOGISTICS_CAPACITY"；**喂给自适应定价的 demand/supply 两侧剔除**没有逐值对照（未构造"有/无运力"两轮的价格表对照）。
5. **`settleHaulRuns` 绕开唯一写口**这件事本身：我**只**报了它导致的崩溃，**没有**为它写新的红用例（怕在没有裁定的情况下往门禁里加一条必然红的用例）。
6. **任务书提到的"`ExpectedProfitBookTest:235` 旧 `gov.SetPortPolicy` 载荷"**：实际点名的旧载荷在 `McpCoverageTest:489`（已改）；`ExpectedProfitBookTest` 的红是 `merchantFirms`（已改）。`SimosToolsTest:556`/`GovToolsZ6Test:391` 只引用类型字符串，编译与行为均未受影响。
7. **未跑**：真实 world 360 tick 读日志（AGENTS §七：真实行为验证走真实 world，不进程序化门禁）；本轮的"行为验证"= 真实 12-hex/三国世界的那 5 个 E2E 用例（它们现在报的是同一个实现缺陷）。

---

## 7. 遗留与建议

1. ★ **先修 §5 的根因**（6 个红全是它）；修完请重跑 `clean verify`——若根因修好，app 那 6 条应当转绿，全仓即回到 **3385 条 / 0 红 / 5 跳过 + 前端 412/412**。
2. **建议把"烧工具必须走唯一写口/必须让路冻结"写进 `AGENTS.md` 的纪律面**：本轮 13 个批次里，唯一"绕过 `applyTransfer` 直接改会话账"的地方就是它，而它正好是唯一在真实世界里炸的地方。
3. ⑤⑥ 与 J1/J3/N7 建议**另开一个测试批**（判据已在计划 §5 里冻结，夹具成本主要在"多币 + 双区 + 法定区锚格定价"）。
4. `MarketSettlementFixtures` 本轮扩了三件事（多区拓扑 `region(...)`、跑商/国库户 `carrier(...)`/`dormant(...)`、`withPortInputs(...)`），可复用给 ⑤⑥ 的批。
