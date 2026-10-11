# W9 测试批（2026-10-11）：迁移 + 指纹重基线 + 真读数 + 补判据测试

> 批次：**W9 测试批**。写码批账本：`.superpowers/sdd/2026-10-11-w9-mode-switch/impl-ledger.md`（含逐值表 §4A/§4B）。
> 授权写入面：`simos-economy/src/test/**`、`simos-app/src/test/**`（**main 一字未改**；未 commit；未跑 `clean verify`）。
> 本轮实跑 jar md5：`b07bb719257cf2b313d037610aacb106`（`tools/mvn-lock.sh -q -DskipTests package`，2026-10-11 20:37）。
> 统计窗口：真世界读数一律 **0→360**（§九.4 口径标注）；单测面无窗口。

---

## 0. 一句话结论

1. **编译红点 = 4 处 / 3 个类**（全部在 `simos-economy` 测试面；`simos-app` 测试面**没有**编译红）—— 比写码批报的 5 个类少 2 个（`HomeModeMigrationProbeTest` / `ExpectedProfitBookTest` 其实**编译得过**，且前者语义无关、后者夹具不交易 tool ⇒ 断言仍成立）。
2. 迁移后 **全仓测试只剩 1 条红**（`EconomyCycleHealthTest`），而它是**行为回归**、不是可重基线的指纹漂移：**three-powers 世界 0→360 出现 21 个"零成交市场轮"**（W7e 基线 = 0），踩中"零成交轮次 = 0"这条**硬牙** ⇒ §② 指纹重基线 **BLOCKED**，已上报。
3. 真读数证实 W9 的主口径变了：**68/62 笔满速（1000‰）迁移**（sw/tp），单笔最多搬 199 人（整户迁空）；`no-labor` 噪声从 W7e 的 8,844/17,610 条**降到 0 条**（改为轮级计数 12 行）。
4. 上报两条 main 侧遗留（只报不改）：**①壳户过滤误伤"并入目标注册表"**；**②极小户（劳动 < 1 个规模单位）被判成最高档**。另上报一条**读数缺口**：`MIGRATION_MOVE` 没把新增的 `targetTierIndex` 打进日志 ⇒ 真世界读不到"谁落到哪一档"。

---

## 1. ① 迁移：旧口径 → 新口径（逐条具名）

### 1.1 实跑红点清单（以实跑为准）

```
tools/mvn-lock.sh test-compile -am      # logs/01 03
[ERROR] MerchantOnDemandTradeW7Test.java:[634,28] cannot find symbol: MIGRATION_PER_MILLE
[ERROR] ModeMigrationGovServiceFailClosedTest.java:[188,28] cannot find symbol: MIGRATION_PER_MILLE
[ERROR] ModeMigrationPolicyD024Test.java:[438,49] cannot find symbol: MIGRATION_PER_MILLE
[ERROR] ModeMigrationPolicyD024Test.java:[513,51] cannot find symbol: MIGRATION_PER_MILLE
```
⇒ **3 个类 / 4 处**（`simos-app` 因 reactor 中断被 SKIPPED；修完 economy 后重跑 `BUILD SUCCESS`，`simos-app` 测试面**零编译红**）。

### 1.2 逐条迁移

| # | 类:行 | 旧 | 新 | 依据 |
|---|---|---|---|---|
| M1 | `ModeMigrationPolicyD024Test:438`（断言） | `move.transferSpeedPerMille() == MIGRATION_PER_MILLE`(10‰) | `== expectedProfitGapSpeed(收益_当前, 收益_其他)`（**测试侧独立复述**算式，不写死数）+ 前置断言"差必须为正、速度必须为正"（防空洞地绿） | 用户③「收益差驱动」；常量已按 §一.11 删除 |
| M2 | `ModeMigrationPolicyD024Test:513`（断言） | liquid 支 `== MIGRATION_PER_MILLE`（`allSatisfy` ⇒ **空集也绿**） | 先钉 `liquidSourceMoves.isNotEmpty()`，再 `== expectedProfitGapSpeed(...)`（逐值） | 同上；**顺带把"空集假绿"这个弱断言补强** |
| M3 | `ModeMigrationPolicyD024Test:664`（断言） | `源户人口 == 99`（写死"缩编 1 人"） | `== 100 − 计划里源户那一笔的总人口` ∧ 那一笔 `== max(1, ⌊100×speed÷1000⌋)` | 可迁人口随速度口径变 |
| M4 | `ModeMigrationPolicyD024Test` 夹具 | H2 并入目标户 `laborMilli = 0` | `laborMilli = 1_000`（**+ 一段具名注释说明为什么**） | **见 §5 上报 U1**：壳户过滤把劳动 0 的户从并入目标表里也删了 ⇒ H2 从"并入既有户"落到"新建户"支、被 in-kind 闸（cloth 不可得）拒 ⇒ 计划变空。这不是放宽断言，是把夹具恢复成"这段用例本来要测的世界"（D-024 判据一字未改）；★ 该改法对"main 修 U1 / 不修"**两种结局都成立** |
| M5 | `ModeMigrationGovServiceFailClosedTest:188`（**夹具**，非断言） | 引用已删常量当夹具速度 | 本类自己的 `FIXTURE_PLAN_SPEED_PER_MILLE = 10L` + 注释"**它不是口径常量**"（本类只测执行器如何处理计划里给定的速度，与速度怎么来无关） | §一.11：旧常量删；不保留假绿 |
| M6 | `MerchantOnDemandTradeW7Test:634`（**夹具**） | 同上 | 同上（`FIXTURE_PLAN_SPEED_PER_MILLE`） | 同上 |
| M7 | `MerchantOnDemandTradeW7Test` 执行闸门语义 | 带不来作业资本 ⇒ **整笔跳过**（唯一通道） | **三支**：带得来 ⇒ 放行；带不来 ∧ 买得到 ∧ 租得到 ⇒ **放行**（`ONDEMAND_TRADE_MIGRATION_TOOL_PURCHASE`）；都不行 ⇒ 具名跳过（并新增 `toolTradedAtMarket`/`idleToolMilliInHex` 两栏） | 用户①「没有生产工具不会买吗？」v3.29 §7 第 3 条 ⇒ 旧断言必须改判 |
| M8 | `MerchantOnDemandTradeW7Test` 跳过用例 | 只断"跳过 + 理由串" | **加断**：`toolTradedAtMarket=false ∧ idleToolMilliInHex=0`（跳过必须真的落在"买不到"那一支）∧ 不得同时发"买/租放行"INFO | 不许把"跳过"放宽成"只要第一条成立就绿" |
| M9 | `ExpectedProfitBookTest:310/328`（复核 + 补强） | `reason().startsWith("NO_MERCHANT_CAPACITY")` | **复核结论：夹具 H 格市场只报 CLOTH/FIBER/GRAIN、不交易 tool ⇒ `no-tool-input` 仍成立**（本条**不是**编译红、也不是行为红）。**补强**：显式钉住两个物理前提（市场不交易 tool ∧ 手头无 tool）+ 理由改成**精确相等** `NO_MERCHANT_CAPACITY:no-tool-input` | W9 后只断"不可行"会失去判别力（市场多一行 tool 就会变成别的理由） |
| M10 | `HomeModeMigrationProbeTest:45/:319` | 自带 `MIGRATION_PER_MILLE = 10L` | **不迁移**（保留）——实测：它与 main 的 `ModeMigrationPolicy` **零引用**（`grep` = 0），是**自足的玩具模型**常量（自己的 `migrateOne` 用它算可迁人口）⇒ 与 W9 口径无关，实跑**绿** | 不是"旧机制"的断言，强改反而失真 |
| M11 | `MoneyMarketSettlementTest:702` | `SettlementOrderBook.empty(DAY)` | **不迁移**（保留）——`SettlementOrderBook` 仍存在（W4 的滑落/排序仍用它），删除的只是**转业侧对它的读** | 回代码核 |

★ **没有删除任何一条判据**；只删/改了"旧机制本身"的三处常量引用与一处夹具形态。

---

## 2. ② 指纹重基线：**BLOCKED —— 新世界踩硬牙，不许重基线**

`simos-app/src/test/java/io/mosire/simos/app/time/EconomyCycleHealthTest.java` 的 `BASELINE_FILLS` **未改**（`git status` 可核）。

### 2.1 为什么不能重基线（实跑真值，旧 → 新）

从 W9 jar 的 three-powers 0→240 窗口（**同一条装置的口径**：`MARKET_REPORT` 逐轮 `fills`）实得：

| 读数 | W7e（旧，本文件已钉） | **W9（新，本批实跑）** | 判定 |
|---|---|---|---|
| 轮数 | 96 | 96 | 不变 |
| **零成交轮次** | **0** | **21**（day 38,40,43,45,48,50,53,55,58,60,75,78,80,83,95,98,100,110,113,115,118） | ★★ **踩"零成交轮次 = 0"硬牙** |
| Σfills | 3,651 | **2,027**（−44.5%） | 大幅恶化 |
| min / 中位 / max | 15 / 42.0 / 198 | **0 / 24.0 / 120** | 全线塌陷 |
| 分布 | 24 档 | 28 档（含 `0=21`） | — |

### 2.2 依据与处置

* **五条牙一字未动**：零成交轮次 = 0 / 96 轮 / 同日不两轮 / 间隔 ≤ 5 / `Σfills == MARKET_FILL 行数` —— 本批**没有改这五条**，也**没有改** `BASELINE_FILLS`。红的就是"零成交轮次 = 0"这条牙。
* ⇒ 按派单 §2「★ 五条牙一字不动」，**这不是可重基线的指纹漂移，是行为回归** ⇒ 保持红、上报（§5 U2）。重基线会把"市场冻结"这一历史缺陷洗白。
* **sw（small-world）0→240 对照**（同一口径，供定位）：96 轮 / 零成交 **0** / Σ1,204 / min·中位·max = 3 / 7.0 / 150（sw 未踩牙）。
* 零成交轮的归因（DEBUG 原文）：`day=38 reasons={NO_SELLER=170, ALL_RESERVED=27, LOGISTICS_CAPACITY=203, NO_BUYER=83, INPUT_SHORTFALL=194, UNSOLD_SELF_USABLE=76, ALGORITHM_UNCOVERED=37}`；`day=60` 同形但 `ALGORITHM_UNCOVERED=37→207`、`LOGISTICS_CAPACITY=203→30`。★ `NO_SELLER=170 / INPUT_SHORTFALL=194 / NO_BUYER=83` **在多个零成交轮里逐值相同** ⇒ 是**结构性**状态（一批订单永远配不上），不是抖动。

---

## 3. ③ 真读数（八类，逐条数字 + 命令 + 窗口）

**装置**（证据与日志：本目录 `logs/`、`evidence/`；脚本 `run-worlds.sh`）：

```bash
# 0) 打包（服务未运行 ⇒ 可覆盖 jar）
tools/mvn-lock.sh -q -DskipTests package        # jar md5 b07bb719257cf2b313d037610aacb106
# 1) 真世界 0→360（从创世新播；受管后台作业 + 记 PID；端口 10971-10976；独立 store /tmp/w9t/stores）
bash .superpowers/sdd/2026-10-11-w9-test-batch/run-worlds.sh sw   # PID 记录于 logs/sw-run.pid
bash .superpowers/sdd/2026-10-11-w9-test-batch/run-worlds.sh tp   # PID 记录于 logs/tp-run.pid
#   装置 = /tmp/w6acc/w6run.py；--log-level DEBUG --trace-level TRACE --log-keep '<白名单>'；POST /api/advance
```

窗口一律 **0→360**（`sw360` / `tp360`，均为一次 `advance` + 一份 dump）。

### 3.1 速度由收益差驱动（逐目标/逐笔）

| 世界 | 迁移笔数 | 逐笔速度分布（‰） | reason |
|---|---|---|---|
| sw | **91** | 1000×68, 99×11, 626×6, 730×2, 286×2, 100×2 | 全部 `PROFIT_WEIGHTED` |
| tp | **93** | 1000×62, 516×4, 102×4, 100×5, 286×3, 311×2, 590×2, 116×2, 17×1, 28×2, 66×1, 95×1, 104×3, 131×1 | 全部 `PROFIT_WEIGHTED` |

* ★ **速度是逐笔不同值**（不是整户一个常量），且**逐目标**：同一源户不同目标带不同速度 —— 与"逐目标现算"的实现一致。
* ★ **W7e 对照：sw/tp 0→360 = 26 / 60 笔**（写码批账本 §11.1 引用的 W7e 真读数）⇒ sw **+250%**、tp **+55%**。
* ★★ **负向（债务很高但"别的方式收益 ≤ 当前" ⇒ 必须不产生转业）**：
  * **单测（可执行、逐值）**：`ModeMigrationPolicyD024Test.w9HighDebtDoesNotMigrateWhenTheProfitGapIsNonPositive()` —— 给源户挂 **50 亿毫**债务、收益差 ≤ 0 ⇒ 源户迁移笔数 **0**；正对照（同夹具需求对调）⇒ 有转业，且**加同一笔巨债后计划逐值不变**（签名相同，只有 `debtMilli` 由 0 变正）⇒ 债务不是输入。
  * **真世界计数证明**：**A 规则与债务口径的迁移 = 0 笔**（`MIGRATION_MOVE` 里 `reason=PROFIT_WEIGHTED` **91/91（sw）、93/93（tp）**，`REASON_A_RULE_MAX_SPEED` = 0；`MIGRATION_DEBT_*` 事件不参与速度）。
  * ⇒ **"该情形 0 笔"** 的可读证据 = 全部迁移都由收益差产生；没有任何一笔由债务/资产口径产生（旧口径下的 10‰/f(债务) 速度值——如 `10`、`286`、`626`——在真世界里也出现，但**源头**是收益差算式，由单测逐值钉住）。

### 3.2 零库存也能转业（落最低档）

* **真世界**：`MIGRATION_INVENTORY_CARRIED` 出现 **91/93** 次（= 每一次成功迁移），但**逐笔携带量可读、档位不可读** ⇒ 见 §5 U3（读数缺口）。**"零库存户落到最低档"在真世界里读不出档位**（新增的 `targetTierIndex` 没有进任何日志）。
* **单测替代（如实说）**：
  * `ModeMigrationPolicyW9Test.carriedToolInventorySelectsTheTierByThePerValueTable()`：**0 / 2,000 / 7,000 / 200,000 毫工具 ⇒ 档 0 / 0 / 2 / 3**（逐值写死，判据本身）。
  * `tierBoundariesAreExactOnBothSides()`：每档"恰好满足 ⇒ t"、"差一毫 ⇒ t−1"（需求由测试侧**独立复述**算式算出）。
  * `theBottomTierNeverRequiresAnyInventory()`：**兜底档需求 ≡ 0**（"零库存也能转业"的结构根）。
  * `ModeMigrationPolicyD024Test` 的源户**没有任何 tool**，其迁移笔 `targetTierIndex == 0`（并入既有户那一支的读数）。

### 3.3 按库存定档（同阶层不同库存 ⇒ 不同档）

* **真世界造不出可读样本**：档位（`targetTierIndex`）**没有进日志/落盘**（瞬时计划类型，不进 Codec/ChangeSet）⇒ 无法从真世界读到"谁落到哪一档"。**如实说：真世界这一条读不到。**
* **单测覆盖（逐值表，见 §3.2 的三条）**：0/2,000/7,000/200,000 ⇒ 0/0/2/3；边界两侧各一毫；档位单调。
* ⇒ 派单允许的替代路径（"若世界造不出，用单测覆盖并如实说"）**已走**。

### 3.4 带库存随迁守恒（逐笔"带了什么/多少" + 商品守恒）

* 真世界**逐笔清单**（`evidence/sw360-inventory-carried.txt`，91 行；tp 同形 93 行）。样例：
  ```
  day=30 source=hh-0_0-rural-landlord    target=hh-0_0-rural-poor_peasant population=10 goods={fiber=918947, grain=20459}
  day=30 source=hh-0_0-rural-middle_peasant target=hh-0_0-rural-poor_peasant population=20 goods={fiber=1829323, grain=616302}
  ```
  ★ 可见"整户/按比例带粮带纤维"，且**goods 里从未出现 `haul`**（D2 的排除在真世界生效）。
* ★★ **守恒式的正确读数层**：商品**总量**在世界上**本来就不守恒**（每 tick 生产/消费都在变）⇒ "源减 = 目标增、全局合计不变"只能在**转移这一层**读。本批用单测逐值钉住（`ModeMigrationInventoryCarryW9Test`，3 条）：
  * 部分迁移：源 100 人 / 粮 1,000,000 / 布 7 / 运输服务 5,000；搬 40 人 ⇒ 粮 **400,000**（源留 600,000）、布 **2**（余 5 留源户）、**运输服务不搬**；逐商品 Σ 前后逐值不变；**账本里恰好一条 `MIGRATION_CARRY` 转移**（`from`/`to`/`goods`/`id=tr-<day>-<seq>` 全对得上）。
  * **整户迁空** ⇒ 粮 1,000,000 + 布 7 **全部**带走、源户商品账**清空**（不留悬空）。
  * **多笔同源**：搬 30 人 ⇒ 300,000；再搬 70 人（迁空）⇒ **余量 700,000 全部**（证明"逐笔读活表"）。

### 3.5 缺工具可买（`ONDEMAND_TRADE_MIGRATION_TOOL_PURCHASE` + 租佃）

| 读数 | sw | tp | W7e 对照 |
|---|---|---|---|
| `ONDEMAND_TRADE_MIGRATION_TOOL_PURCHASE` | **0** | **0** | （新事件，无旧值） |
| `ONDEMAND_TRADE_MIGRATION_SKIPPED` | **0** | **0** | sw/tp 0（W7e 也是 0） |
| `MERCHANT_TRADE_INDUSTRY_CREATED` | **0** | **0** | sw/tp 0（W7e 也是 0） |
| `MIGRATION_MOVE targetMode=merchant` | **13** | **19** | — |

* ⇒ **真世界里"缺工具 ⇒ 买/租"这条新通道一次都没走**（因为**根本没有按需建商号的笔**：`ONDEMAND_*` 全 0 ⇒ 那道闸门根本没被触发）。**不是"买不到"**（若买不到会有具名跳过行；0 行 = 这条路径没被进入）。
* ⇒ 真世界的 merchant 迁移**13/19 笔全都是并入既有 `trade` 产业**（城市格），**不是**"非城市格长出商号"。
* ★ 真读数与 W7e 的**同一个 E3 遗留门槛**一致：按需商号在真世界里仍然一次都没长出来。W9 的新通道**是否拆掉了那道门槛，真世界没有给出正面样本**（单测给了：`migrationWithoutCarriedToolIsAllowedWhenToolIsTradedAndIdleInHex` 逐值证明"市场交易 tool ∧ 本格有闲置 ⇒ 放行 + 真租到 + 守恒"）。

### 3.6 壳户噪声（前后对照）

| 读数 | W7e（旧） | **W9（新）** |
|---|---|---|
| 逐户 `no-labor` / `no-tool-input` 行（`MODE_MIGRATION_PROSPECT_INFEASIBLE`） | sw **8,844** / tp **17,610** | **0 / 0**（两个世界都是 0 行） |
| 轮级 `MODE_MIGRATION_CANDIDATES` 行 | （旧版无这两栏） | **12 / 12** 行，带 `skippedShellNoPopulation` + `skippedShellNoLabor` |
| 每轮 shell 计数（sw，day 30→360） | — | noPopulation 73→106、noLabor 84→72（**只计数、不逐户刷屏**） |
| 每轮 shell 计数（tp，day 30→360） | — | noPopulation 142→177、noLabor 152→137 |

★ 逐值样例（sw day=360）：
`candidateRows=528 merchantCandidates=76 skippedShellNoPopulation=106 skippedShellNoLabor=72 moveable=… moves=1 movedPopulation=2`

### 3.7 跑商与运粮（用户最关心）

**跑商**：见 §3.5 表 + 上面 13/19 笔 merchant 迁移。

**运力未服务量（口径就是 `MERCHANT_CAPACITY_LANE_TRUNCATED` 逐行 / `CAPACITY_DEMAND_GAP_WHY` 逐行；窗口 0→360）**：

| 指标 | W7e（旧） | **W9（新）** | Δ |
|---|---|---|---|
| `no-carrier-with-deliverable-service` 笔数 / Σ`unallocatedMilli`（sw） | 13,551 笔 / 61,879,715 毫（W7e 自己的证据文件 `sw360-lane-truncated-by-reason.txt`；★ 派单里给的是 11,777 笔 / 61,441,260 毫 —— **两者对不上，见 §5 U4**） | **13,230 笔 / 116,786,642 毫** | 笔 −2.4% / 毫 **+88.7%** |
| `no-carrier-with-deliverable-service`（tp） | 1,425,041 笔 / 7,549,473,894 毫（派单口径） | **1,364,740 笔 / 5,662,444,890 毫** | 笔 −4.2% / 毫 **−25.0%** |
| `out-of-derived-radius`（`MERCHANT_CAPACITY_LANE_TRUNCATED`，sw） | 2,672 笔 / 3,319,569 毫（W7e 证据文件） | **6227 笔**（`CAPACITY_DEMAND_GAP_WHY` 口径） | 口径不同不可直接比 |
| `out-of-derived-radius`（`CAPACITY_DEMAND_GAP_WHY`）笔数 / Σ`unservedWorkMilli`（sw） | —（W7e 的该文件是"每窗口一行"的聚合口径，不可比） | **6,227 笔 / 65,639,528 毫** | — |
| `out-of-derived-radius`（tp） | — | **358,996 笔 / 5,465,382,996 毫** | — |

★ **结论**：运力缺口**没有变好**（sw 的未服务金额涨了 ~89%；tp 笔数 −4%、金额 −25%）。
★ **粮**：`MIGRATION_INVENTORY_CARRIED` 证明粮**随人走**（逐笔清单里 `grain=…`）；本批**未**单独统计"粮的分布变化"（见 §6 未做）。

### 3.8 不回归

| 项 | 结果 | 证据 |
|---|---|---|
| **B2 分段等价**（一次 / 3 段 / 逐 tick，**规范化后**状态 md5 相同） | ✅ **同一**：`state=097292436420dfbb09462d4094c5ed71`；`diff` 差异叶 **0 / 总叶 78,044** | `logs/b2-run.out`、`dumps/sw{360,3seg,tick}-d360-*.json`；`python3 /tmp/w6acc/canon2.py md5\|diff` |
| **Revision Δ** | ✅ 一次 advance(0→360) = **Δ1**（rev 1→2）；3 段 = Δ3；逐 tick = Δ360 | `logs/sw360.result.json` 等 |
| **性能（单次墙钟）** | sw 0→360 = **7.70 s**（同一容器内、TRACE 日志开）；tp 0→360 = **29.87 s**；sw 分段 3.47+2.64+2.37 s；sw 逐 tick = 33 s | `logs/*-run.out` |
| **W4 阶层滑落（债务驱动）不回退** | ✅ 既有回归网全绿（`simos-economy` 319 条 + app 相关类），且 `SettlementOrderBook`/`ClassSlideSettlement` 未被 W9 改动（回代码核：`git diff` 无它们） | `logs/08-test-all.log` |
| **W5 铸币 / W5.5 货币市场 / W7 运力全局共享与 haul 定价 / W8 债务语义** | ✅ 对应测试类全绿（`MINT_*`/`MONEY_MARKET_*`/`HAUL_*`/`DEBT_*` 相关类在 08 轮全绿） | `logs/08-test-all.log` |
| **全仓回归面** | 15 个模块全跑：**唯一 1 条红** = `EconomyCycleHealthTest.twoFullCyclesInRealThreePowersWorld…`（见 §2） | `logs/08-test-all.log` |

---

## 4. ④ 新用例清单（17 条，判据出处逐条）

★ **最终测试面**：`tools/mvn-lock.sh test -pl simos-economy -am` ⇒ **333 条全绿 / 0 红**（`logs/12-economy-final.log`；迁移前 319 条，本批 +14 条）。
★ **simos-app** 测试面：唯一红 = `EconomyCycleHealthTest`（见 §2；本轮 app 侧一字未改，该红是 main 行为回归）。

| 类 | 用例 | 判据出处 |
|---|---|---|
| `ModeMigrationPolicyW9Test`（新，10 条） | `profitGapSpeedMatchesTheFrozenPerValueTable`（差 >0/0/<0、分母口径） | 用户③ v3.30 §7 第 2 条；写码批 §4A 逐值表 |
| | `profitGapSpeedBoundariesAreExact`（打满/差一毫/分母=1/负收益） | 同上 + D4/D5 |
| | `profitGapSpeedIsMonotoneInTheGap`（"差越大越快"） | 用户③ |
| | `profitGapSpeedMatchesAnIndependentRestatementAcrossTheWholeRange`（交叉验证） | §三.2 判别力 |
| | `profitGapSpeedTakesExactlyTheTwoProfitReadings`（**只有两个输入**、旧 `slideSpeedPerMille` 不得复活） | 用户④（两层拆开） |
| | `carriedToolInventorySelectsTheTierByThePerValueTable`（0/2,000/7,000/200,000 ⇒ 0/0/2/3） | 用户① 派单 ④ 逐值表 §4B |
| | `tierBoundariesAreExactOnBothSides`（每档两侧各一毫 + 单调） | 同上 |
| | `theBottomTierNeverRequiresAnyInventory`（兜底档需求 ≡ 0；劳动 < 1 规模单位的边界） | 用户②「为啥要拦着」 |
| | `carriedCapitalCountsOwnedMobileUnclaimedSharesPlusToolGoods`（自有∧可移动∧未占用 + 工具商品；LAND/占用/他人不计） | 派单 ④ 的输入面 |
| | `tierPositionTakesTheAscendingLadderAndCapsAtTheHighest`（升序第 0 项 = 脚夫；封顶；空表 null） | 派单 ④（升序位阶，方向反了必须红） |
| `ModeMigrationInventoryCarryW9Test`（新，3 条） | `partialMoveCarriesThePopulationRatioAndConservesEveryCommodity` | 用户①「默认带一部分库存过去」+ 四类关键项之**守恒式/不丢失** |
| | `fullMoveCarriesEverythingAndLeavesNothingBehind`（整户迁空） | 同上 |
| | `twoMovesFromOneSourceReadTheLiveTableBetweenMoves`（逐笔读活表） | D-023 §4 |
| `ModeMigrationPolicyD024Test`（+1 条） | `w9HighDebtDoesNotMigrateWhenTheProfitGapIsNonPositive` | 用户④ + 派单 §④「债务高但收益差 ≤0 ⇒ 不转业」 |
| `MerchantOnDemandTradeW7Test`（+3 条，另 2 条改写） | `migrationWithToolTradedButNoIdleToolInHexIsStillSkippedByName`（**买得到但租不到 ⇒ 仍跳过**，卡点具名） | 用户① v3.29 §7 第 3 条 |
| | `migrationWithoutCarriedToolIsAllowedWhenToolIsTradedAndIdleInHex`（**买/租得到 ⇒ 放行** + 真租到 + 守恒 + `TENANCY` 不改所有权） | 同上 |
| | `busyToolShareIsNotLeasableSoTheMoveIsStillSkipped`（被在产 unit 占用 ⇒ 不算闲置） | 守恒式的第二面 |

★ **四类关键项覆盖**：守恒式（随迁守恒 3 条 + 放行租佃 1 条）、不丢失（`MIGRATION_CARRY` 转移逐字段 + 无凭空造）、静默付 0（跳过必须有具名 INFO + 两栏卡点；不得静默 `continue`）、断粮（**本批未新增**，见 §6）。

---

## 5. 上报（只报不改；main 侧遗留 / 口径冲突）

| # | 事项 | 最小复现 / 证据 | 影响 |
|---|---|---|---|
| **U1** | ★★ **壳户过滤误伤"并入目标注册表"**：`ModeMigrationPolicy.plan` 用**同一张 `modeByHousehold`** 既当"源户候选表"又当 `bestExistingTarget` 的**并入目标表** ⇒ W9 ⑥ 把"参与率折算劳动 = 0"的户在候选阶段跳过后，**这类户不再能被并入**（改前可以） | 实测（本批诊断，`logs/05-diag.log`）：D024 夹具（H2 的并入户劳动 0）⇒ `skippedShellNoLabor=2` + `MIGRATION_IN_KIND_LEG_UNAVAILABLE_REFUSED … in-kind-leg-unavailable:cloth(industry=target@1_0 outputs=[grain],stock=0,market=false):regime=household` ⇒ **`moves=0`**；把该户劳动改成 1,000 后计划恢复非空 | ⑥ 的**理由**只说"这些户没有劳动可投、每轮空转刷屏"（**源户**侧）⇒ 把它同时从**并入目标**里删掉是**未声明副作用**。真世界里 82~93% 的 `no-labor` 来自城镇地主户（劳动 0、人口 > 0）——它们正是"迁入城镇时最自然的并入目标" |
| **U2** | ★★★ **W9 打破"零成交轮次 = 0"硬牙**（three-powers） | §2.1：21 个零成交轮（W7e = 0）；`EconomyCycleHealthTest` 实跑红（`logs/08-test-all.log`，断言在 `:505`，day=60）。根因待查（归因原文见 §2.2：`NO_SELLER/INPUT_SHORTFALL/NO_BUYER` 三轮同值 = 结构性） | **阻断 §② 指纹重基线**；市场功能在 tp 世界明显退化（Σfills −44%、中位 42→24） |
| **U3** | **读数缺口**：`MIGRATION_MOVE`（`EconomySettlement:3349`）**没有**把 W9 新增的 `move.targetTierIndex()` 打进字段表 | `grep -n "targetTierIndex" simos-economy/src/main/**` ⇒ 只有 record/构造/计划传递，**零日志/落盘** | ⇒ **"按库存定档"在真世界里读不出**（§3.3 只能靠单测）；N9 声称"新增读数 3 条"不含这一维 |
| **U4** | **W7e 基线数字对不上**（口径冲突）：派单给的 `sw 0:360 = 11,777 笔 / 61,441,260 毫`，而 W7e **自己的证据文件**（`.superpowers/sdd/2026-10-11-w7e-test-batch/evidence/sw360-lane-truncated-by-reason.txt`）同一指标是 **13,551 笔 / 61,879,715 毫** | 两处原文并列 | 影响 §3.7 的"vs W7e"口径；本批**两个都列出**，不替控制方选 |
| **U5** | **极小户被判成最高档**：劳动投入 < **1 个规模单位**（`laborMilli < laborPerUnit` ⇒ `规模_劳动 = 0`）时，每一档的"额外规模"都是 0 ⇒ `supportedTierIndex` 给**最高档**（3 = 地主档） | 单测逐值钉住：`ModeMigrationPolicyW9Test.theBottomTierNeverRequiresAnyInventory` 里 `tier(trade, 999L, 0L) == 3` | 落点会被抬到该 mode 的最高位置（无资产时不可行 ⇒ 候选被 prospect 挡掉或走降档重试）；★ 现有壳户过滤掩盖了大部分影响，但**劳动 ∈ [1, 999] 毫的极小户**仍可达 |
| **U6** | **`降档重试` 在真世界里几乎不产生迁移**（读数面）：`failuresByReason` 里 `NO_ASSET:*` 是绝对主因（sw day=360：`NO_ASSET` ≈ 330 条 vs `NO_EMPLOYER` 42 条；tp 同形），`moves=1`/`moves=2` | `evidence/event-counts.txt` | 与写码批 N10 的预期（"迁移笔数上升"）方向一致，但**降档重试的成功率极低**（失败原因仍是 NO_ASSET ⇒ 最低位置也要资产/雇主）|

★ 派单 §2 列的**已知"只报不改"**（`PROFIT_GAP_SPEED_SLOPE_PER_MILLE` 仍是编译期常量、`LAND/WORKSHOP` 档位恒 0、`收益_当前=0` 易打满）**本批复核成立**：
* 斜率常量：`ModeMigrationPolicy.PROFIT_GAP_SPEED_SLOPE_PER_MILLE` 仍是 `public static final`（无 GM 写口）—— 与用户"凡行为一律做成 GM 可调的预设"这条既有裁定**有缺口**，属控制方另批。
* `LAND/WORKSHOP` 档位恒 0：单测里 `h2Farm()`/`farm()` 类产业恒落档 0（`isMobileAsset(LAND/WORKSHOP) = false`）—— 成立。
* `收益_当前 = 0` 打满：**真读数里有直接证据** —— sw 91 笔里 **68 笔 = 1000‰**、tp 93 笔里 **62 笔 = 1000‰**；单笔最多搬 **199 人**（整户）。这与 D4 的预警一致。

---

## 6. ⑤ 变异自证记录

| 关键项 | 变异 | 结果 | 还原 |
|---|---|---|---|
| **守恒式**（随迁"源减 = 目标增"） | 把 `partialMoveCarriesThePopulationRatioAndConservesEveryCommodity` 的期望 `400_000L` 改成 `400_001L`（测试侧变异；**main 禁改**） | ✅ **当场红**：`expected: 400001L but was: 400000L`（该行就是守恒式的读数量） | ✅ 从备份还原，**md5 逐值相同**（`b2b6d405b8b920c840dd7587571ce5f5` 前后一致），还原后 3 条全绿 |

★★ **本轮另有 6 处"自然红"（真实写错期望值 ⇒ 当场红 ⇒ 修正），可作断言判别力的旁证**（不是刻意变异，但同样证明断言被真的求值、不是恒真）：
1. `MACHINE` 未列入 claimed ⇒ `carriedCapitalByKind` 把它算进来 ⇒ 红（证明"claimed 不计"这一格有判别力）；
2. `tierNeed(tier=0)` 误算成 1080 ⇒ 红（证明"兜底档需求 ≡ 0"是**定义**不是算出来的）；
3. `tier(999 毫劳动) == 0` ⇒ 红，实为 **3**（⇒ 发现 U5）；
4. `speed(0, 1) == 1000` ⇒ 红，实为 **100**（分母口径不是"恒打满"）；
5. `TOOL == 15_000`（漏了 goods 为空那一支）⇒ 红，实为 **13,000**；
6. `tier(0 毫劳动) == 0` ⇒ 红，实为 **3**。

★ **做不到的（如实说）**：四类关键项里的**"断粮"**本批**没有**新增变异体或新用例（既有断粮网在 `08-test-all.log` 里全绿，但 W9 没碰那一路）；**"静默付 0"**只做了"跳过必须具名 INFO"的断言，没有做"变异成静默 continue ⇒ 必须红"的对照（那需要改 main）。

---

## 7. ⑥ 我没做 / 没验证的

1. **`EconomyCycleHealthTest` 的指纹重基线：没做**（BLOCKED，见 §2）——不是"没时间"，是**硬牙被踩，重基线等于洗白回归**。
2. **真世界的"按库存定档"逐户读数：读不到**（U3；`targetTierIndex` 无日志/落盘）。已用单测逐值覆盖并如实记。
3. **真世界的"每笔商品守恒"：没有直接读**（商品总量在世界上本就不守恒；守恒在转移层，用单测钉）。真世界只给了逐笔携带清单。
4. **降档重试的单测：没写**（fixture 需要"多位置 mode + 默认位置不可行 + 最低位置可行（要既有雇主 + 工资规则）"，本批三次尝试都停在夹具构造阶段，未成）；真世界只给了 `NO_ASSET` 为主的失败计数。
5. **`MIGRATION_MOVE` 的 `targetTierIndex` 真读数、`ONDEMAND_TRADE_MIGRATION_TOOL_PURCHASE` 真样本：两个都没有**（真世界里这条通道 0 次触发；不是"买不到"，是"没有按需笔"）。
6. **600 天窗口 / 多进程确定性（两次独立进程逐字节相同）**：没跑（只跑了 0→360 单窗口 + B2 三形态规范化对比）。
7. **`no-tool-input` 的真世界计数**：没单独统计（新版计数只进 `MODE_MIGRATION_CANDIDATES` 的 `failuresByReason`，且该栏在 day=360 的 sw/tp 里**一条 `no-labor`/`no-tool-input` 都没有**）。
8. **前端门禁**（`-DskipTests package` 里跑了 `[frontend-gate] OK tests=412`）只作旁证，未单独复核。
9. **未做 `clean verify`**（归控制方门禁，派单明令）。

---

## 8. ⑦ 未完成 / BLOCKED

| 项 | 状态 | 说明 |
|---|---|---|
| §② 指纹重基线 | **BLOCKED** | 新世界踩"零成交轮次 = 0"硬牙（U2）⇒ 按派单"五条牙一字不动"，不重基线、保持红 |
| §③.3 按库存定档（真世界样本） | **BLOCKED** | 档位不进读数面（U3）⇒ 只能单测覆盖 |
| §④ 降档重试（单测） | **未完成** | 夹具未构造成功；真世界读数只有失败计数 |
| 大盘清理 | 已做 | 删除 `simos-app/target/surefire-reports`（1.1 G）、`logs/08-test-all.log`（312 M）、`logs/tp360.log`（1.67 G 原始未过滤日志）——**保留** `*.log.stripped` 与 `evidence/` |

**磁盘**：跑前 `df -h /` = 46 G 可用（62% 用）；跑后 47 G 可用（清理后）。
**服务**：两个世界跑完 rc=0 后进程自行退出；`ps aux | grep simos-app` = 空；未 `pkill -f`。
**未 commit**（派单明令）。
