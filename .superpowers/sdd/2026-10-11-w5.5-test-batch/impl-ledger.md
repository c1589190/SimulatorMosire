# W5.5d 测试批账本：W5.5（取缔借粮 + 货币市场）收口测试

> 责任区：**W5.5d 测试批**（用户任务书「为 W5.5 与 W5.5-fix/fix2 收口测试」）。
> 权威：设计书 `docs/superpowers/specs/2026-10-10-unified-price-and-ordered-settlement-design.md` 的
> **§2 S-12**（七条口径）+ **§6.3 判据清单** + **§7 v3.21/v3.22**（R2 裁定 / 根因更正）。
> 实现面权威：`.superpowers/sdd/2026-10-11-w5.5-money-market/impl-ledger.md`（W5.5 / W5.5-fix / W5.5-fix2 三节）。
> 基线 **HEAD = `4e04ad8c`**；工作树含 W5.5 全链**未提交**改动（本批不 commit、不回退、不碰 `src/main/**`）。
> 状态：**本批交付面全部完成并真跑绿**；★ 两处 main 侧发现已按"只报不修"上报（见 ④）。

---

## ① 判据 → 用例映射（判据出处逐条点名）

新增类 **`simos-economy/src/test/java/io/mosire/simos/economy/time/MoneyMarketSettlementTest.java`**
（形制照 `MintProductionSettlementTest`：真夹具 + 真结算口，不复制算式）。

| # | 判据出处 | 用例（方法名） | 关键断言（逐值） |
|---|---|---|---|
| 1 | **S-12③** 出借额 = 现金 − 生活储备 − 本轮自己的生产花费；≤0 ⇒ 不出借 | `loanPoolIsCashMinusLifeReserveMinusOwnProductionCostAndNeverNegative` | 现金 51/52 ⇒ 供给 **0**（负值夹到 0）；53 ⇒ 可贷 **1**、借 **1**；500 ⇒ 可贷 **448**、借 **100**；人口 2（储备 24）⇒ 64−24−40 = **0** |
| 2 | **S-12④** 借款人无上限、唯一上限 = 放贷池 | `borrowerHasNoOwnLimitAndTheOnlyCapIsThePool` | 需求 10000 / 池 448 ⇒ 借出 **448**（= supply，池被借空）；借方 0→448、贷方 500→52；`MONEY_MARKET_UNFILLED` 逐值 `borrowedMilli=448 unmetMilli=9552 reason=pool-exhausted` |
| 3 | **S-12⑤** 利率随供需（与 §2.1 同一套算式） | `loanRateFollowsSupplyAndDemandWithAlphaZeroFrozenAndZeroFloor` | 缺钱 20→**21**；钱多 20→**19**；D = S ⇒ **不动**（`loanRateNext` 空）；α=0 ⇒ **冻结**（空）；下限 0：1→**0** |
| 4 | **S-12⑥** 国库默认不挂钱 | `treasuryDoesNotLendOrBorrowByDefault` | 国库 10000 毫银 ⇒ 供给 **0**、`govSuppressed=1`、余额一分不动；**判别对照**：同一户去掉国库身份 ⇒ 供给 **9988**、借出 100 |
| 5 | **§6.3 判据 1（守恒式）** + S-12「钱腿只搬钱」 | `lendingOnlyMovesMoneyAndEveryContractHasDistinctDebtorAndCreditor` | Σ货币 624 **不变**（非空转守卫）；2 笔转移全部 `LOAN_PRINCIPAL` + `goods` 空 + `from != to`；Σ 逐笔 = `lentMilli`；每条合同 `debtor != creditor`、本金 ∈ {100,300}、利率 = **本轮 20**（不是写回的 19）、`dueCycle` |
| 6 | **S-12②①** 次序 = 挂商品→挂钱→借钱→买货；钱腿只搬钱 | `borrowedMoneyBuysGoodsInTheSameRoundBeforeMatching` | 买方现金 0 ⇒ 借入 **30**（= 需求 − 现金 = ⌈2905 × 10 ÷ 1000⌉）⇒ **同一轮**实付现金 **30**、成交价仍 = 牌价 **10**；买方 0→+30→−30；贷方 200→170；卖方 0→**+30**（真收钱）；2 条腿分得开（`LOAN_PRINCIPAL` 无货 / 唯一货腿 = 该成交）；`MONEY_MARKET_BUDGET_TOPUP` 存在；利率写回 20→**19**、α 逐值带过；负向：无 `NO_BUDGET` |
| 7 | **S-12④⑦** 池空 ⇒ 借不到 ⇒ 退赊购 | `emptyPoolFallsBackToDeferredPaymentToTheSeller` | 放贷人现金 0 ⇒ `demandMilli=30 / supplyMilli=0 / lentMilli=0 / loans=0`；**货照拿**（数量 2905、实付现金 **0**）；债务转给**卖方**（本金 30）；放贷人余额不动；**次序**：`MONEY_MARKET_WHY` 早于 `MARKET_DEFERRED_PAYMENT`；利率 20→**21**（缺钱） |

★ **装置与诚实边界**（写进类注）：不复制算式（真 `MarketRound` / 真 `clearOncePerCycle` / 真 `applyTransfer` /
真 `DebtContractBook.upsert`）；**跨 logger 的绝对次序不可比**（模块测试的 `TestLogCapture` 按 logger 名精确挂槽）⇒
判据 7 的次序证据 = ①同一 logger 内的行序 + ②行为等价性（借到的钱同一轮真被花掉；无池则退赊购）。

### 判据 ②（次序）的补充证据：`EconomyCycleHealthTest` 的 96 轮"零成交 = 0"

W4b 的牙（96 个市场轮逐轮 `fills > 0`）**一字未动**，本批两次实跑均成立 ⇒ 货币腿没有把任何一轮打成空转。

---

## ② 显式重新基线化（旧值 → 新值 + 依据）

### ②-1 `GovGenesisZ6Test`（`:351`）：裸数字 4801 → **能自证的算式**（不是换一个裸数字）

| 项 | 旧 | 新 | 依据 |
|---|---|---|---|
| 第 1 天 Social 人口 | `isEqualTo(4801L)` 裸字面量 | `人口 == 创世人口 + 当日出生 − 当日死亡`（三项都是当场真值） | 判别实验（W5.5-fix2 账本 ③-3）：改前/改后 day1 `POPULATION_SETTLE` **逐值同一行**（`births=1 deaths=2 famineDeaths=2`）⇒ `4800 + 1 − 2 = 4799` 在改动前就是事实；`4801` 早已失效（那一轮在 `:239` 就红了、没跑到这里） |

- 真值读数：`genesisPopulation=4800`、`day1Births=1`、`day1Deaths=2` ⇒ 期望 **4799**。
- **加牙**（按任务书要求）：把"裸数字"换成算式；并**同时钉住**三个量（创世人口 4800 / 出生 1 / 死亡 2），
  两个生死读数取自 Social 唯一结算口 `POPULATION_SETTLE` 的**真日志**（`SocialLog.SETTLE_LOGGER_NAME`，新增一路
  `AppLogCapture`）。找不到/不止一条日志 ⇒ 当场抛（**不静默当 0**）。
- ★ 实测踩到一处：`POPULATION_SETTLE_START` 与 `POPULATION_SETTLE` 前缀相同 ⇒ 过滤器必须写成 `"event=POPULATION_SETTLE "`
  （带尾随空白）。第一轮就是被这条自检拦下的（`实得 2 条`），没有静默放过。

### ②-2 `EconomyCycleHealthTest`（指纹 + min/中位/max）

| 项 | 旧（W4b） | 新（W5.5 实测） | 依据 |
|---|---|---|---|
| 分布指纹 | `{17=10, 18=1, 21=7, 22=9, 23=5, 24=12, 31=1, 34=1, 35=3, 36=4, 37=8, 38=1, 39=2, 40=13, 41=2, 44=1, 53=8, 54=1, 55=3, 56=2, 66=1, 131=1}` | **形态 A**（4/5 次实测）：`{15=1, 21=11, 22=8, 23=4, 24=6, 25=5, 26=10, 30=1, 41=1, 43=4, 44=5, 45=24, 46=2, 47=5, 48=1, 49=1, 50=1, 51=1, 56=1, 57=1, 66=1, 92=1, 140=1}` | W5.5 货币腿（先借钱再买货 ⇒ 每轮成交笔数整体上移）+ R2 参与者收窄（单位户回到买盘）+ W5.5-fix2 单位户创世粮 |
| min / 中位 / max | 17 / 35.0 / 131 | **15 / 43.0 / 140**（形态 A） | 同上；中位 = 96 轮中间两项（43 + 43）/ 2 |
| 轮数 / 零成交轮次 | 96 / 0 | 96 / 0（**未动**） | 同上 |

★★★ **同时如实重新基线化了第二个形态（见 ④-1 的 main 侧发现）**：形态 **B**
`{15=1, 21=18, 22=5, 24=17, 25=2, 26=2, 30=1, 37=1, 38=1, 39=4, 40=9, 41=7, 42=11, 43=7, 44=3, 46=1, 48=1, 51=1, 53=1, 66=1, 85=1, 135=1}`
（min/中位/max = 15 / 38.5 / 135，与 W5.5 账本 fix2 之前那次**逐值相同**）在同一条命令下也出现过 1 次。
⇒ 断言改成"落在**已实测的两个形态之一**，第三个值即红"，min/中位/max 随匹配到的形态逐个钉死。
**这不是放宽**：两个成员都是逐值指纹；"零成交轮次 = 0"与三条自检（轮数 96 / 同日不得两轮 / 间隔 ≤ 5 天）一字未改。
理由与复现命令逐条写在测试注释里。

### ②-3 工具面计数/名单（只改内容与计数，无一处降级）

| 类 | 旧 → 新 | 落点 |
|---|---|---|
| `SimosToolsTest` | GM 桶 `hasSize(164)` → **165**；窄写类扫描 `hasSize(68)` → **69**；handler 扫描 `hasSize(142)` → **143**；`*Tool.java` 扫描 `hasSize(174)` → **175** | `ECONOMY_ZONE_WRITE_NAMES` +`simos.economy.setMarketZoneLoanRateAlpha`；`ECONOMY_ZONE_WRITE_COMMAND_TYPES` +`economy.SetMarketZoneLoanRateAlpha`；`NARROW_WRITE_NAMES`/`WRITE_TOOL_NAMES`/`GM_TOOL_NAMES`/`EXPECTED_COMMAND_TYPES` 及其 javadoc 计数逐条更新 |
| `McpServerTest` | `EXTERNAL_UNION_GM_TOOL_NAMES` +1 条 ⇒ 164 → **165**（37 读 + 128 写） | 名单 + javadoc + 断言消息 |
| `McpPortTopologyTest` | `ECONOMY_ZONE_WRITES` 7 → **8** 条；`GM_NARROW_WRITES` 67 → **68**；总数 164 → **165** | 名单 + 两处 javadoc + `as(...)` 消息 |
| `McpCoverageTest` | `EXPECTED_COMMAND_TYPES` 142 → **143**；`PRECONDITION_REJECT_TYPES` +该命令；`MINIMAL_PAYLOADS` +具名拒例 | ★ 拒因与 handler 实现逐字对齐：`table-missing`（只作用于**已存在的持久价表**；本夹具世界是派生形态 ⇒ 载荷形状合法但必拒） |

★ 与 W5d 同口径：**只改名单内容与计数**；没有把 `containsExactlyInAnyOrder` 降级成 `contains`，没有删任何判据。

---

## ③ 变异自证记录（关键项；改坏 ⇒ 当场红 ⇒ 还原 ⇒ 比 md5）

原字节 md5（`/tmp/w55t-pristine.txt`，所有轮次结束后 `md5sum -c` 逐条 **OK**）：

```
3af7a8ed6d67c5acf727a80e009b8755  MoneyMarketSettlement.java（与 W5.5 账本记的最终字节一致）
9b8e132c1b446f995687347ad7e278e1  MarketSettlement.java（与 W5.5 账本记的最终字节一致）
```

命令（每个变异体一轮）：`tools/mvn-lock.sh test -pl simos-economy -am -Dtest='MoneyMarketSettlementTest' -Dsurefire.failIfNoSpecifiedTests=false`

| # | 关键项 | 变异体（一行/一小段；**全部已还原**） | 变异体 md5 | 结果 | 红在哪一行 |
|---|---|---|---|---|---|
| M1 | 守恒式（逐笔金额） | `lend(...)`：钱腿 `Map.of(currency, amount)` → `amount + 1L` | `131ad97f…` | **5/7 红** | 逐户余额/账户路径：`★ 买方 0 → +30 → −30 ⇒ 0`、`贷方 53 → 52`、`借方拿到 448`… |
| M1′ | **守恒式（Σ 货币）** | 绕过唯一写口 `applyTransfer`：只给借方加钱、不扣贷方 | `fa8ffad9…` | **5/7 红** | ★ `★ Σ货币不变（只搬不铸）`（`lendingOnlyMoves…:363`）+ 逐户贷方余额 |
| M2 | **不丢失（身份）** | `DebtContractBook.upsert(debts, borrower, lender, …)` → `(…, lender, borrower, …)`（两端对调） | `87a8b985…` | **2/7 红** | ★ `★ 债务人是买方`、`b1 的债权人是放贷人 l2` |
| M3 | **静默付 0** | `MarketSettlement.applyMoneyMarketBudgetTopUps`：`if (borrowed.isEmpty()) return;` → `if (!borrowed.isEmpty()) return;`（借到的钱不补进订单预算） | `13326a4f…` | **1/7 红** | ★ `★ 实付现金 = 借入额（借到的钱在同一轮真被花掉）`（`borrowedMoneyBuys…:463`） |
| M4 | **国库不挂钱（S-12⑥）** | 货币腿排除判据 `AUTHORIZATION_ONLY` → `BUY_ONLY`（判据拼错一处） | `18352de4…` | **1/7 红** | ★ `★ S-12⑥：国库的 10000 毫银不进货币供给（默认不挂钱）` |
| M5 | **断粮（最后一道口粮通道）** | `MarketSettlement.deferralPossible`：`ctx.debts != null` → `ctx.debts == null`（关掉赊购） | `72c5b1f6…` | **1/7 红** | ★ `★ 池空不影响拿货（赊购是最后一道）` |

★ **M3 的一次改进（如实记）**：第一次跑 M3 时第一条红是下游症状（`恰一条借款合同` 因多出一条赊购合同而红）⇒
把"实付现金"那组断言**移到合同条数之前**，再跑 ⇒ 红在 `★ 实付现金 = 借入额` 这一行上（保护的那一行）。
★ 全部变异体还原后 `md5sum -c` = **OK**（逐字节相同），且最终一轮全量复跑绿。

★ **等价存活（如实写）**：本轮**没有**对"R2 把单位户从 `AUTHORIZATION_ONLY` 改回 `BUY_ONLY`"做变异自证 ——
按 W5.5-fix 账本 ③-1 的判别实验，R2 对 `GovGenesisZ6Test` 三条断言**零影响**（改回后逐日序列逐值相同），
故该变异体在**本批的核心断言面**上属**等价存活**，测了也不红；它的守护面（单位户买盘）由
`Z7MarketExclusionTest`（卖出侧原口径）与 `GovGenesisZ6Test`（5/5）间接覆盖。

---

## ④ 建议清单复跑 + 两处 main 侧发现（只报不修）

### ④-1 复跑结果（全部真跑；最终一轮 **exit=0 / BUILD SUCCESS**）

命令：`tools/mvn-lock.sh test -pl simos-app -am -Dtest='MoneyMarketSettlementTest,SimosToolsTest,McpServerTest,McpPortTopologyTest,McpCoverageTest,GovGenesisZ6Test,Z7FleeTest,GovAdminSalaryBudgetZ6Test,Z7D4BudgetLedgerTest,RaiseUnitToolTest,Z7MarketExclusionTest,EconomyCycleHealthTest' -Dsurefire.failIfNoSpecifiedTests=false`

最终一轮读数（surefire 逐类，全部 `Failures: 0, Errors: 0`）：`simos-app` 汇总 **Tests run: 112**；
`simos-economy` 另跑 **8** 条（`MoneyMarketSettlementTest 7` + `Z7MarketExclusionTest 1`）。
★ 跑完已清理 `target/surefire-reports/*.xml`（`EconomyCycleHealthTest` 单轮 XML ≈ 937MB；清理后磁盘余量 17G）。

| 类 | 结果 |
|---|---|
| `MoneyMarketSettlementTest`（**新增**） | **7/7 绿** |
| `GovGenesisZ6Test` | **5/5 绿**（含 ②-1 的算式自证；W5.5-fix2 的 3 条断言保持恢复态） |
| `SimosToolsTest` | **71/71 绿**（165 / 69 / 143 / 175 四处计数） |
| `McpServerTest` | **5/5 绿** |
| `McpPortTopologyTest` | **4/4 绿** |
| `McpCoverageTest` | **1/1 绿** |
| `EconomyCycleHealthTest` | **1/1 绿**（96 轮、零成交 0、形态 A） |
| `Z7FleeTest` | **3/3 绿** |
| `GovAdminSalaryBudgetZ6Test` | **12/12 绿** |
| `Z7D4BudgetLedgerTest` | **3/3 绿** |
| `RaiseUnitToolTest` | **7/7 绿** |
| `Z7MarketExclusionTest` | **1/1 绿** |
⇒ 任务书点名的 5 个"未跑"类**全部真跑且绿**，无需"迁移/删除"处置。

### ④-2 ★★ main 侧发现 1：`EconomyCycleHealthTest` 的逐轮成交分布**同字节两次跑出两个值**（非确定性）

- **现象**：`-Dtest='EconomyCycleHealthTest'` 单独跑连续两轮 ⇒ 形态 A（逐值相同，可复现）；
  同一条合并命令 15:03 一轮 ⇒ 形态 **B**；随后**同一条命令**再跑 ⇒ 又回到 A。5 次实跑：A × 4、B × 1。
- **已排除**：① 无并发写 `src/main`（最新 mtime 14:50 < 首轮 15:03，且本批未碰 main）；
  ② 模拟路径无墙钟/随机源（`Instant.now/Math.random/new Random` 命中不在经济—社会结算路径）；
  ③ 本世界走**单线程**结算（`Shell` 用 `PopulationUnitTimeParticipant(mapId)` 单参构造 ⇒ `economyWorkerCount = 1`）；
  ④ 不是"同 JVM 跑了哪些测试类"的函数（**同一条命令**两次分别给 B 与 A）。
- **影响**：违反 I7/B4 的"内容 ⇒ 同一结果"读法面（至少是逐轮读数的非确定性）；本批因此**无法把指纹收成单一值**。
- **本批处置（只报不修）**：指纹断言改成"两个已实测形态之一（第三个值即红）"，并在测试注释里逐条记下现象、
  排除项与复现命令。★ **建议**：main 侧按本仓已具名的"迭代序不是内容的纯函数"一族排查
  （`Map.copyOf`/`Set.copyOf`/`Set.of` 的 JVM 盐化迭代序 + 市场轮内的并列裁决），查实后本处应**收回到单一形态**。
- **复现命令**（逐字）：
  `tools/mvn-lock.sh test -pl simos-app -am -Dtest='SimosToolsTest,McpServerTest,McpPortTopologyTest,McpCoverageTest,GovGenesisZ6Test,EconomyCycleHealthTest' -Dsurefire.failIfNoSpecifiedTests=false`

### ④-3 ★ main 侧发现 2：放贷池**全空**时 `MONEY_MARKET_UNFILLED` 不发、且 `unmetMilli` 读 0（读数不对称）

- **现象**（实测日志，最小复现 = 新增用例 `emptyPoolFallsBackToDeferredPaymentToTheSeller`）：
  `supply == 0` 时 `MONEY_MARKET_WHY … demandMilli=30 supplyMilli=0 lentMilli=0 loans=0 unmetMilli=0`，
  且**没有** `MONEY_MARKET_UNFILLED` 行。
- **根因（回代码核）**：`MoneyMarketSettlement.settleZone` 的配对段整段在
  `if (demandTotal > 0L && supplyTotal > 0L)`（`:375`）里 ⇒ 池空时既不逐户报缺口，`ZoneRound.unmetMilli` 也留 0；
  而"池非空但不够"那条路会报（`borrowerHasNoOwnLimitAndTheOnlyCapIsThePool` 实测 `borrowedMilli=448 unmetMilli=9552`）。
- **影响**：**纯读数**（`unmetMilli` 只进 DEBUG 日志，不参与任何判定/状态/金额）；但"需求 30 全落空"却读
  `unmetMilli=0` 会误导排查（S-12④ 的"池空 ⇒ 借不到"在默认级别下只能从 `demand/supply/lent` 三个数读出来）。
- **本批处置（只报不修）**：用例改用 `MONEY_MARKET_WHY` 的三个数钉"池空 ⇒ 借不到"，并在测试注释里点明这条边界；
  ★ **建议**：把 `MONEY_MARKET_UNFILLED`（或 `unmetMilli`）在 `supply == 0` 时也给出（一行改动，属 main 面）。

---

## ⑤ 我没做 / 没验证的（如实）

1. **没跑** `clean verify` / `package` / 全量测试（按任务书硬纪律：归控制方门禁）。
   ⇒ **前端门禁、checkstyle、spotbugs、enforcer、模块边界**均**未在本批跑过**；本批只跑了 `test`（含编译）与
   `spotless:apply`（rc=0）。★ 本批改动的 6 个测试文件已过 `spotless:apply`（google-java-format 干净）。
2. **没做跨区/跨币借贷**的用例（S-12 本批口径：逐区清算、不做跨区）⇒ 未验证，也未声称。
3. **没验**国库"显式授权出借"（S-12⑥ 后半；main 侧本批未建写口 ⇒ 无从验）。
4. **没验** `MoneyMarketSettlement` 的 TRACE 逐笔事件在**默认日志级别**下的可见性（模块测试要 `TestLogCapture` 才采得到；
   app 侧默认级别由 `log4j2.xml` 决定，本批未跑真 `-Dsimos.*` 属性轮）。
5. **没做** `EconomyCycleHealthTest` 形态 B 的**独立复现**（只观察到 1 次；未能构造出"必然给 B"的命令）。
6. **无变异自证**的项：R2 判据本体（等价存活，见 ③ 末）、`MarketTable.withPrices` 带字段（由既有 W1 用例覆盖）、
   跨进程确定性（`CrossProcessDeterminismTest` 本批**未跑**，W5.5-fix2 账本记其 1/1 绿）。
7. **未验**"逐户外部性"（W5.5-fix 账本 ⑤-⑧ 的 +1 户缺口/+783 毫落在谁头上）——需 TRACE 逐户明细，本批未做。
8. 本批**没有**触碰 `src/main/**`（变异体是"改坏⇒跑⇒还原⇒比 md5"，全部还原为原字节，`md5sum -c` = OK）。

---

## ⑥ 未完成 / BLOCKED

**无 BLOCKED**。本批交付面（① 两处重基线、② 工具面计数、③ 新用例与变异自证、④ 建议清单复跑、⑤ 账本）全部完成。

★ 两处 **main 侧**发现（④-2 非确定性、④-3 池空读数）按"只报不修"上报，**不影响本批交付**，但 ④-2 决定了
"分布指纹"这条牙目前只能收成**两个形态**：控制方若要单一形态，需先修 main 侧非确定性（或裁定接受两形态守卫）。

---

## ⑦ 改动文件清单（含 md5，`spotless:apply` 之后）

| 文件 | md5 | 性质 |
|---|---|---|
| `simos-economy/src/test/java/io/mosire/simos/economy/time/MoneyMarketSettlementTest.java` | `db4a815f443abae4ace58def96dd9eea` | **新增**（7 用例） |
| `simos-app/src/test/java/io/mosire/simos/app/gov/GovGenesisZ6Test.java` | `89a41359cebc05eb64fcb263c53487a0` | 改（②-1） |
| `simos-app/src/test/java/io/mosire/simos/app/time/EconomyCycleHealthTest.java` | `8f7b5d1374b129e14377a6e09b2da2b4` | 改（②-2 + ④-2 两形态） |
| `simos-app/src/test/java/io/mosire/simos/app/tools/SimosToolsTest.java` | `e5107ede9090b24b56539edcc3022c27` | 改（②-3） |
| `simos-app/src/test/java/io/mosire/simos/app/McpServerTest.java` | `e53617d83a024b1c1148e5942f72003b` | 改（②-3） |
| `simos-app/src/test/java/io/mosire/simos/app/McpPortTopologyTest.java` | `83ed1dc1fb8e25346d24415b1501cc00` | 改（②-3） |
| `simos-app/src/test/java/io/mosire/simos/app/McpCoverageTest.java` | `32f1f554756febc38f80a1e9cf611507` | 改（②-3） |

★ `src/main/**`、`docs/**`、`AGENTS.md`、其他模块：**一字未改**（md5 对照见 ③）。
★ 未 `git commit`；工作树里 W5.5 全链的未提交改动**未回退**。

---

# W5.5e（守卫收回单形态 + 池空正向读数）

> 责任区：**W5.5e 测试批**（控制方任务书：按 W5.5-fix3 的诊断结论收口两处测试面）。
> 权威：设计书 **v3.23 §7**；实现面权威 `.superpowers/sdd/2026-10-11-w5.5-money-market/impl-ledger.md` 的 `# W5.5-fix3` 节。
> 允许改：`simos-app` / `simos-economy` / `simos-social` 的 `src/test/**`；**`src/main/**` 一字未改**；
> 未 `git commit`；工作树里 W5.5 全链的未提交改动**未回退**。
> 状态：**两处改动完成；两个类真跑绿（含最终 `spotless` 字节的干净轮）；两处变异自证都当场红、还原后 md5 相等。**

## ① 两处改动（旧 → 新 + 依据）

### ①-1 `simos-app/.../time/EconomyCycleHealthTest.java`：两形态守卫 → **单一期望**

| 项 | 旧（W5.5d） | 新（W5.5e） | 依据 |
|---|---|---|---|
| 期望形态的载体 | `List<FillRegime> KNOWN_FILL_REGIMES = List.of(regimeA(), regimeB())` + `matched` 匹配 | 单一常量 `FillRegime BASELINE_FILLS = baselineFills()`（= 原形态 A 的逐值分布） | W5.5-fix3 §② 的判别实验链 |
| 指纹断言 | `assertThat(matched).isNotNull()`（"落在两个已实测形态之一"） | `assertThat(actualDistribution).isEqualTo(BASELINE_FILLS.distribution())`（**整份逐值**） | 同上 |
| min/中位/max | `matched.minFills() / medianFills() / maxFills()`（随匹配形态） | 逐值 `BASELINE_FILLS.minFills() / medianFills() / maxFills()`（15 / 43.0 / 140） | 同上 |
| 第二形态代码 | `regimeB()`（22 行） | **删除** | 同上 |
| 注释 | "一处**未解释的非确定性**"整段（15:03/15:05 命令表 + 已排除原因清单 + "建议 main 侧排查"） | **删除**；改写为诊断结论：**不是非确定性**，是 `target/classes` 陈旧造成的另一份编译产物（附判别实验一句：pre-fix2 的 `ThreePowersGovBootstrap.class` 前置 ⇒ 分布变那一族） | 任务书 ①；AGENTS §三.5 |

★ **没动的牙（逐条核对）**：① 零成交轮次 = 0；② 轮次数 == 96；③ 同日不得两轮；④ 相邻轮间隔 ≤ 5 天；
⑤ `Σfills == MARKET_FILL 行数` —— **语义与文字均未改**（只新增 `distributionText(Map)` 重载供基线打印，原
`distributionText(List)` 改为委托，行为不变）。

### ①-2 `simos-economy/.../time/MoneyMarketSettlementTest.java`：补正向读数 + 修过期描述

| 项 | 旧 | 新 | 依据 |
|---|---|---|---|
| `:562` 的注释（旧行号） | "`MONEY_MARKET_UNFILLED` 只在池非空但不够时发；池空 ⇒ `unmetMilli` 读 0、该行也不发"（**旧行为**） | 具名 **旧 → 新**：池空 ⇒ `unmetMilli` = Σ 缺口（= demand − lent）、逐借款人落 `MONEY_MARKET_UNFILLED reason=pool-empty` | W5.5-fix3 的**发现 2** |
| 池空用例断言 | 只用 `MONEY_MARKET_WHY` 钉 demand / supply / lent / loans / borrowers | 新增 **①′** `unmetMilli == demandMilli − lentMilli`（`isEqualTo(whyDemand − whyLent)`）**且** `unmetMilli > 0`；**①″** `MONEY_MARKET_UNFILLED` 行 `borrowedMilli=0 / unmetMilli=30 / reason=pool-empty` | 任务书 ②；恒等式 = S-12④ 的读数面 |
| 钉旧读数的既有断言 | **无**（全仓 grep `unmetMilli`：只有本文件 `:214-219` 的池非空用例 + `:562` 注释，**没有**任何断言钉 `unmetMilli=0`） | 未按旧口径改写任何断言 | 实测（grep 输出见 ③） |

## ② 实跑（真命令 + rc + 关键输出；★ 一次一个 Maven）

**产物新鲜度先核**（AGENTS §三.5）：逐文件比 mtime —— `simos-app` 332 个 main 源、`simos-economy` 213 个
main 源，**stale = 0 / missing = 0**；且 `ThreePowersGovBootstrap`(14:50:15) / `HouseholdSeeder`(14:50:18) /
`EconomySeeder`(14:48:44) 三源 mtime **<** 其 `.class`(16:06:31–32) ⇒ **无需** `rm -rf target/classes`。

命令模板（两条，逐字）：

```
经济：tools/mvn-lock.sh test -pl simos-economy -am -Dtest='MoneyMarketSettlementTest' -Dsurefire.failIfNoSpecifiedTests=false
app ：tools/mvn-lock.sh test -pl simos-app    -am -Dtest='EconomyCycleHealthTest'    -Dsurefire.failIfNoSpecifiedTests=false
```

| # | 轮次（起→止） | 命令 | rc | 关键输出（报告 mtime 落在本轮） |
|---|---|---|---|---|
| 1 | 16:11:03→16:11:07 | 经济 | **0** | `Tests run: 7, Failures: 0, Errors: 0`（本轮真编译 60 个 test 源；class mtime 16:11:06 > 编辑 16:10:47） |
| 2 | 16:11:13→16:12:43 | app | **0** | `Tests run: 1 ... Time elapsed: 68.17 s`；指纹见下 |
| 3 | 报告 16:14:52 | 经济（变异后**干净轮**） | **0** | `Tests run: 7, Failures: 0, Errors: 0` |
| 4 | 16:14:54→16:16:21 | app（还原后） | **0** | `Tests run: 1 ... 67.27 s`；指纹**逐值同 #2** |
| 5 | 16:16:54→16:18:20 | app（`spotless:apply` **之后**的最终字节） | **0** | `Tests run: 1 ... 67.21 s`；指纹**逐值同 #2** |
| 6 | 16:20:29→16:21:56 | app（变异后**最终干净轮**） | **0** | `Tests run: 1 ... 67.39 s`；报告 `...EconomyCycleHealthTest.txt` mtime **16:21:42**、344 字节、绿 |

★★ **实测指纹（与任务书给的值逐值相同，四次绿轮全等）**：

```
15×1, 21×11, 22×8, 23×4, 24×6, 25×5, 26×10, 30×1, 41×1, 43×4, 44×5, 45×24, 46×2, 47×5,
48×1, 49×1, 50×1, 51×1, 56×1, 57×1, 66×1, 92×1, 140×1
min/中位/max = 15 / 43.0 / 140；轮次 = 96；零成交轮次 = 0；Σfills = 3522（== MARKET_FILL 3522 行）
```

⇒ **与 W5.5-fix3 给的值逐值一致，无差异**，故未触发"清 `target/classes` 重编"的判别分支（产物非陈旧已另行独立证明）。
★ 说明：`Σfills = 3522` 由该分布唯一决定（Σ 笔数×轮数 = 3522，轮数 Σ 计数 = 96），并与 `MARKET_FILL` 逐笔行数交叉验证。

★ **磁盘**：`EconomyCycleHealthTest` 每轮 surefire XML ≈ **974 MB**（stdout 2 944 402 行）⇒ 每轮跑完**立即删**
`TEST-*.xml` 与大日志；收工时 `simos-app/target/surefire-reports` = **568 K**，`df /` 跑前跑后都是 **11 G 可用 / 92%**。

## ③ 变异自证（改坏 ⇒ 当场红 ⇒ 还原 ⇒ 比 md5）

| # | 变异体 | 变异体 md5 | 结果（红在**被保护的那一行**） | 还原后 md5 |
|---|---|---|---|---|
| M1 | 指纹 `distribution.put(45L, 24)` → `23`（rev A，`spotless` 之前） | `76ef6832…` → 变异后 | **rc=1**，红在 `assertEveryMarketRoundTraded:487`：基线 `…45×23…` vs 实测 `…45×24…` | `76ef68321ae765e9c7669bf3aa1a4e28`（== 备份） |
| M1b | 同一变异，**最终字节 rev B** | `14bbb093…` → `cdf073521998868ece7c26995dca7458` | **rc=1**，红在 `assertEveryMarketRoundTraded:485`：基线 `…45×23…` vs 实测 `…45×24…` | `14bbb0934918ba56d4114e96a32c0930`（== 绿轮字节） |
| M2 | 池空正向读数 `.isPositive()` → `.isZero()`（= **旧口径 0**） | `7b298c92…` → 变异后 | **rc=1**，红在 `MoneyMarketSettlementTest.java:591`：`[★ 池空 ⇒ 缺口必须 > 0 …] expected: 0L but was: 30L` | `7b298c9205bec7ba8ca8ffdc793d7906`（== 备份） |

★ M2 的 `expected: 0L but was: 30L` 同时是"**旧口径 0 已成历史**"的判别读数：把断言写回旧值 ⇒ 当场红。
★ 还原后工作目录 = 干净世界：变异体 `.class` 已删（`simos-app/target/test-classes/.../EconomyCycleHealthTest*.class`
删除后 `ls` 为空），最终一轮（#6）从还原源**重编并绿**。
★ 三处"红"都不是编译错误、也不是别处先红（M1/M1b 红在分布断言、M2 红在新增正向断言）。

**既有断言钉旧读数？** 全仓 `grep -rn "unmetMilli|MONEY_MARKET_UNFILLED"`（`*.java`）命中 37 处：
`MoneyMarketSettlementTest` 的 `:214-219`（池**非空**用例，值 9552）与 `:562` 注释，`CommodityRound`/`MarketSettlement`
是另一个同名字段（商品未满足量，与本批无关）⇒ **没有**任何既有断言钉"池空 ⇒ `unmetMilli=0`"，无需按新口径改写。

## ④ 会改变数值行为的清单

**无**（本批只改测试面；`src/main/**` 未碰一字节）。

## ⑤ 我没做 / 没验证的（如实）

1. **没跑** `clean verify` / `package`（任务书禁止，归控制方门禁）；`spotless:check` 全仓跑过（rc=0），
   **checkstyle / spotbugs 未跑**。
2. **没跑**其它测试类（`SimosToolsTest` / `McpServerTest` / `GovGenesisZ6Test` / `CrossProcessDeterminismTest` 等），
   也没跑跨进程确定性轮 —— 本批只要求这两类绿。
3. **没碰 main**：W5.5-fix3 的"不是非确定性"结论是**采信**其判别实验；我独立复核的是**结果面**
   （同一份最终字节 4 次绿轮指纹逐值相同 + 产物新鲜度逐文件扫描），**没有重做**它的"盐 / identity-hash 位移 /
   前置旧 `.class`"三个实验。
4. 未验证 `simos-social/src/test/**`（本批未改该模块，也未跑该模块任何测试）。
5. **未保留**完整 surefire XML 作为留痕（每份 ≈ 974 MB）；留痕 = `simos-app/target/economy-cycle-invariant/summary.txt`
   + 344 字节的 `.txt` 报告 + 本节逐值指纹。
6. 本批**没有**再观察到一个"第二形态"（4 次绿轮全等）；**不能**由这 4 次推出"B 形态永不再现"，只能说：
   在当前产物新鲜的前提下不可复现，与 W5.5-fix3 的结论一致。

## ⑥ 未完成 / BLOCKED

**无。**

## ⑦ 改动文件清单（含 md5，`spotless:apply` 之后）

| 文件 | 旧 md5（W5.5d 账本 ⑦） | 新 md5 | 性质 |
|---|---|---|---|
| `simos-app/src/test/java/io/mosire/simos/app/time/EconomyCycleHealthTest.java` | `8f7b5d1374b129e14377a6e09b2da2b4` | `14bbb0934918ba56d4114e96a32c0930` | 改（①-1） |
| `simos-economy/src/test/java/io/mosire/simos/economy/time/MoneyMarketSettlementTest.java` | `db4a815f443abae4ace58def96dd9eea` | `7b298c9205bec7ba8ca8ffdc793d7906` | 改（①-2） |

★ `src/main/**` 一字未改（`MoneyMarketSettlement.java` 仍为 W5.5-fix3 的 `3b7c918ca125535d56df5434d1550b18`）；
`docs/**`、其他模块未碰。
★ **给控制方的一条观察（不是本批改动）**：`find . -type f -newermt "2026-10-11 16:09"`（排除 `target/`、`.git/`）
在收尾时列出 `AGENTS.md`；本批**从未**写过它（它在本批开工前的 `git status` 里已是 `M`）⇒ 疑似同工作树内有其他
写者，请核。（`spotless:apply` 只改了 1 个文件，日志原文 `1 were changed to be clean`，且 `-DspotlessFiles`
只匹配 `.*EconomyCycleHealthTest\.java`。）
