# W9「转业：带库存 + 按库存定档 + 工具可购 + 收益差驱动速度」实现账本

> 批次：**W9**（2026-10-11，写码 Agent 落盘；控制方审后提交）。
> 权威：用户原话（任务书 §0 ①–④）+ 设计书 `docs/superpowers/specs/2026-10-10-unified-price-and-ordered-settlement-design.md`
> **§7 v3.29 / v3.30 / v3.31**（v3.31 是控制方在本批进行中追加的，见 §9「非本批改动」）。
> 允许面：`simos-economy/src/main/**`、`simos-economy-api/src/main/**`、`simos-app/src/main/**`（**本批 app 一字未改**）。
> 硬边界：未碰 `src/test/**` / 其他模块 / `docs/**` / `AGENTS.md`；**未 commit**；未跑 `test`/`verify`/`package`/`clean`（理由见 §8）。

---

## 1. 改动文件清单

| 文件 | 改动性质 |
|---|---|
| `simos-economy/src/main/java/io/mosire/simos/economy/time/ModeMigrationPolicy.java` | 转业速度改成收益差驱动；档位判据；壳户跳过；`MigrationMove.targetTierIndex`；`Target.speedPerMille/tierIndex`；删 `MIGRATION_PER_MILLE` 与 W4 债务速度在转业里的用法 |
| `simos-economy/src/main/java/io/mosire/simos/economy/time/ModeMigrationSettlement.java` | 商品库存随迁（走 `applyTransfer`）；新建户阶层槽位随档位；按需商号闸改判为"买/租得到就放行"；`planAssetMoves` 加"同格跨产业闲置"放宽 |
| `simos-economy/src/main/java/io/mosire/simos/economy/time/ExpectedProfitBook.java` | 新增"指定落点位置"的 `prospect` 重载（档位决定位置）；`no-tool-input` 加"买得到"通道 |
| `simos-economy/src/main/java/io/mosire/simos/economy/time/ClassLadderBook.java` | 新增**升序**位阶 `POSITION_LADDER_ASCENDING`（与降序表同文件、同 ordinal） |
| `simos-economy/src/main/java/io/mosire/simos/economy/time/AccountSession.java` | `householdOfActor()` private → 包内可见（唯一写口 `applyTransfer` 的入参形状，只改可见性） |
| `simos-economy-api/src/main/java/io/mosire/simos/economy/api/transfer/TransferReason.java` | 新增词表项 `MIGRATION_CARRY`（库存随迁的转移原因） |
| `.superpowers/sdd/2026-10-11-w9-mode-switch/impl-ledger.md` | 本文件（gitignore；控制方 `git add -f`） |

---

## 2. 关键调查结论（`file:line` → 结论 → 影响）

> 依据 AGENTS §四「机制性描述一律回代码核」。以下行号是**开工时**的实测行号（改动后行号已漂，末尾附改动后的新落点）。

| # | file:line（开工时） | 结论 | 影响 |
|---|---|---|---|
| 1 | `ModeMigrationPolicy.java:97` | `MIGRATION_PER_MILLE = 10L` 是**基线速度**；`:626` `baselineSpeedPerMille = max(MIGRATION_PER_MILLE, w4SlideSpeedPerMille)` | 用户③要改的正是这条：速度不挂收益差 ⇒ 本批换成收益差映射 |
| 2 | `ModeMigrationPolicy.java:1600` `slideSpeedPerMille(ordering, household)` | 转业速度读的是 `SettlementOrderBook`（债务/资产）那把尺 —— 与 W4 阶层滑落**同一份快照** | 用户④明确这是两层 ⇒ 转业侧整段退出，`SettlementOrderBook` 仍由 W4 的排序/滑落用 |
| 3 | `ModeMigrationPolicy.java:888-889` | 候选权重 `weight = max(0, target.netPerLaborScaled − currentPerLabor)` —— **权重已按收益差**，但只决定"谁更可能走" | 收益差现成可用：本批由同一个 gap 派生速度（不再另造第二个读数） |
| 4 | `ModeMigrationPolicy.java:549-559`（`buildTargets` 尾部） | 目标只产出 `positionId = prospect.positionId()` = `choosePosition(...)`（角色元组匹配，否则第一个可生产位置） | 与档位无关 ⇒ 「按库存定档」需要一条新的落点选择（见 §3 ④） |
| 5 | `ExpectedProfitBook.java:632-651`（旧） | `no-tool-input` = **手头工具商品 ≤ 0 ⇒ 不可行**（硬否决） | 用户①「没有生产工具不会买吗」⇒ 加"市场交易该商品 ⇒ 可买/赊购"通道 |
| 6 | `ModeMigrationSettlement.java:1675-1739`（旧） | 执行期闸门：按需建商号时"这一笔带不来的作业资本 ≤ 0 ⇒ **整笔跳过**"（`ONDEMAND_TRADE_MIGRATION_SKIPPED`） | 用户②「为啥要拦着」⇒ 改判为"买/租得到就放行"，买不到才具名跳过 |
| 7 | W7e 测试批账本 §4.1（实测） | `no-labor` **8,844(sw)/17,610(tp)**、`no-tool-input` 703/3,833 —— 82~93% 来自**人口 0 的壳户 / 劳动 0 的城镇地主户** | ⑥：候选阶段具名跳过，只进轮级汇总 |
| 8 | `ModeMigrationSettlement.java:78-80`（类注）+ `:1507-1600` | 随迁只搬**资产份额**（TOOL/SHIP/CATTLE/MACHINE）与**货币** ⇒ **商品库存不动** | 用户①要"带一部分库存过去"⇒ 本批新增商品库存随迁腿 |
| 9 | `EconomySettlement.java:9230-9265` | `applyTransfer` 是"商品/货币换手"的**唯一写口**（两遍式：先只读校验、再统一落账；校验"扣完不低于冻结"） | 商品随迁必须走它 ⇒ 只带**冻结之外**的可用量，守恒由它保证 |
| 10 | `EconomyDayView.java:85` | 执行期手里有 `marketTables()`（区内一张，键 = `MarketZoneId`），但**没有 topology** | 执行期的"这格市场交易不交易工具"用 `MarketZoneBook.zoneOfHex` 求区（与 `PrimaryModeRanking.marketOf` 同一张表） |
| 11 | `Industry` 的 `capacityPerUnit`（`EconomySeeder:4646-4700` 等） | 农田 = `{LAND:1000}`（**不可移动**）、家庭纺织 = `{TOOL:1}`、作坊 = `{WORKSHOP:1}`、trade = `{TOOL:120}` | 档位判据只对**可移动**作业资本有区分度 ⇒ 农田/作坊目标恒为兜底档（**如实记，见 §6 偏离 D3**） |
| 12 | `ClassLadderBook.java:155-163` | 唯一存在的位阶 = `(relationToMeans, surplusRole)`；`POSITION_HEIGHT_ORDER` 是**降序**、且 private | 档位落点需要**升序**表；反向错误不抛不报 ⇒ 升序表落在同一文件、共用同一组 ordinal（新增 14 行） |
| 13 | `EconomySettlement.java:2698` / `:3311` / `:3392` | 滑落在**市场轮块内**（撮合/价格/统计之后），转业计划/执行在**周期关账钩子内**（同一 tick 的**更晚**处），且计划读的是 `session.sheet().classMemberships()`（工作副本） | ⑥次序：**滑落先、转业后**already 成立，无需改代码（v3.31 亦确认）；频率差 = 滑落每市场轮（5 天）/ 转业每关账（30–120 天），见 §3 ⑥ |
| 14 | `MerchantTradeTemplate.java:152-206` | `capacityKinds() = {TOOL}`、`capacityPerUnit = {TOOL:120}`、`cycleInputPerUnit = {tool:100}`、`TOOL_COMMODITY = tool` | ⑤的"买工具"在本模型里有**两种既有形态**：商品腿（市场买/赊购，`tool` 商品）与**闲置份额租佃**（`OwnershipStakeBook`）—— 本批两者都用，见 §3 ⑤ |

---

## 3. 实现架构（六件事逐条：现状 → 改法 → 算式/落点）

### ① 转业速度 = 收益差驱动（用户③④）

**现状**：`ModeMigrationPolicy.plan:430-432` 建 `SettlementOrderBook` 快照，`:626` `max(10‰, f(债务/资产))` 定速度，`allocate` 对整户用**一个**速度。

**改法**（`ModeMigrationPolicy.java`）
- 删 `MIGRATION_PER_MILLE` 常量、删 `slideSpeedPerMille(...)` 方法、删 `plan()` 里的 `SettlementOrderBook` 快照与 `w4SlideSpeedPerMille` 入参；`planForSource` 不再接收 W4 速度。
- 新增唯一算式（`profitGapSpeedPerMille`，`ModeMigrationPolicy.java:1197` 附近）：
  ```
  gap = 收益_其他 − 收益_当前            （两侧 = Prospect.netPerLaborScaled，同一把百万分之一尺）
  gap ≤ 0                                ⇒ 0（不转业）
  SLOPE ≤ 0（GM 钉死）                   ⇒ 0（与 α=0 同制）
  gap > 0                                ⇒ clamp(⌊SLOPE × gap ÷ max(1, |收益_当前|)⌋, 1, 1000)
  分母口径（具名）：max(1, |收益_当前|) —— 收益_当前 = 0 ⇒ 分母 1；< 0 ⇒ 用 |·|（同一把"相对差"尺）
  SLOPE = PROFIT_GAP_SPEED_SLOPE_PER_MILLE = 100（政策常量，GM 可调预设；写口建议见常量注）
  ```
- **逐目标**给速度：`Target` 新增 `speedPerMille`（`buildTargets:985` 现算）；活候选 = `weight > 0 ∧ speed > 0`；`moveable = max(1, ⌊人口 × 该户最快档速度 ÷ 1000⌋)`（`planForSource` 的收益差支）。
- `allocate/placeInto` 新增"速度覆盖"语义：`speedOverride > 0` 用它（A 规则两支仍传 `A_RULE_TRANSFER_SPEED_PER_MILLE = 1000`），否则用该目标自己的 `Target.speedPerMille`；速度 ≤ 0 ⇒ 不落地。
- `MigrationMove` 的速度值域仍是 `[1, 1000]`（0 的 move 不是"慢"，是"不该存在"）。

**判据对照（逐值表，结构性）**：见 §4「逐值表」。

### ② 不设"有库存"门槛（用户②）

- **最低档不设门槛**：`supportedTierIndex` 的 `t=0` 档需求 ≡ 0（见 ④）⇒ "零库存也能转业并落到兜底档"是**结构上必然**（不是特例分支）。
- **两道硬闸改判**：
  - 决策层 `ExpectedProfitBook.merchantInputFailureReason`（`:680`）：`no-tool-input` 只在"手头没有 ∧ 目标格市场也不交易它"时返回（＝"买不到"那一支）；
  - 执行层 `filterOnDemandMerchantMoves`（`:1887`）：带不来作业资本 ⇒ 先问"买不买得到"（市场交易工具 ∧ 本格有闲置同种资产）⇒ 放行（`ONDEMAND_TRADE_MIGRATION_TOOL_PURCHASE`），否则维持具名跳过（`no-tool-input-to-host-on-demand-trade-industry`，**只跳过这一笔**，不静默、不卡世界）。
- **`no-labor` 例外**（用户派单 ⑥）：人口 0 / 劳动 0 的户在**候选阶段**就跳过，不产生 `no-labor` 噪声；有劳动没库存的户照旧进候选。
- **降档重试**（"降到能支撑的档位"在**位置维**上的落点，`buildTargets`）：默认位置（`choosePosition` 的角色匹配）因**作业资本/劳动**不可行时，再按**最低档的位置**（`tierPosition(producing, 0)`，位阶升序第 1 个）试一次；成功则用它当这一候选的落点（`prospect` 与 `Target.positionId` 同时换成它，档位仍是 0 = 兜底档）。
  - 只对**新建目标**（并入既有户不新建 unit、位置不是这笔能决定的）；
  - 只对**多位置** mode（`producing.size() > 1`）有意义（单位置 mode 没有更低的档）；
  - ★ 方向 fail-... **只增不减**：候选仍由同一个 `ExpectedProfitBook` 判可行 ⇒ 不会静默放行任何不可行目标；它服务的正是用户②「为啥要拦着」。
  - 例：无资产户考虑某农田格 —— `choosePosition` 给出 `family_farmer`（要 LAND ⇒ `NO_ASSET`）⇒ 重试 `wage_farm` 那一支的低位 `wage_laborer`（要既有雇主 ⇒ 有雇主则成立）。

### ③ 默认带一部分库存过去（用户①）

**现状**：随迁只搬资产份额 + 货币，商品库存不动（`ModeMigrationSettlement` 类注 ⑤ + `:548-559` 只搬货币）。

**改法**（`ModeMigrationSettlement.carryGoodsForMove`，`:1375`）
```
可移动商品 = 商品账里的实物库存（粮/纤维/布/工具/铁…），**不含** HAUL 运输服务（服务不入库随迁，见 §6 偏离 D2）
可用量     = 商品账余额 − 该商品冻结量（冻结不越）
逐商品取整 = 整户迁空 ⇒ 可用量全部；否则 ⇒ ⌊可用量 × 本笔人口 ÷ 迁出前人口⌋（余数留源户）
写路径     = EconomySettlement.applyTransfer（唯一写口，两遍式）—— 一条 move 一条转移（多商品腿）
原因       = TransferReason.MIGRATION_CARRY（新词表项，"不是买卖、不是税"）
id         = 有审计账本 ⇒ ProductionLedger.Account#mint（"tr-<day>-<seq>"）；无账本（旧调用点/夹具）⇒ "tr-<day>-migration-<n>"
读数       = INFO MIGRATION_INVENTORY_CARRIED（day/source/target/targetHex/population/goods）
```
- 同一源户的多笔 move **逐笔读活表**（前面的笔已带走库存）⇒ 与 D-023 §4「⌊剩余量 × 比例⌋、余数留源户」逐字一致。
- **守恒**：源减 = 目标增（同一条转移的两端都是已登记家户），商品总量一字不变。

### ④ 目标档位 = 用带来的库存能支撑的最高档（用户①）

**判据（控制方授权"自己定并给算式"，本账本具名）**：
```
档位阶梯 = 目标产业模板 slots()（IndustryClassSlots：0 贫农 950‰ / 1 中农 900 / 2 富农 750 / 3 地主 100）
规模_劳动(t) = ⌊该户劳动投入 ÷ max(1, laborPerUnit)⌋
规模_额外(t) = t == 0 ? 0 : max(0, ⌈规模_劳动 × 1000 ÷ 参与率(t)⌉ − 规模_劳动)
需求(t, k)   = capacityPerUnit[k] × 规模_额外(t)
支撑(t)     ⟺ ∀k: 带得走的作业资本(k) ≥ 需求(t, k)
档位 = 满足支撑的最高 t（都不满足 ⇒ 0；需求(0,·) ≡ 0 ⇒ 兜底档恒成立）
带得走的作业资本(k) = 本户自有 ∧ 可移动(isMobileAsset) ∧ 未被既有组织/在产 unit 占用的份额(k) + 工具商品库存（k=TOOL）
```
**落点**：`tierPosition(producing, t)` = 目标 mode 的**可生产位置**按 `ClassLadderBook.POSITION_LADDER_ASCENDING` 升序取第 `min(t, n−1)` 个 ⇒ 该位置进 `Target.positionId`，并由 `ExpectedProfitBook.prospect(..., positionOrNull)` **按那一档的位置**算预期收益（不算甲档落乙档）。
**执行期**：`MigrationMove.targetTierIndex` 随计划走；`ModeMigrationSettlement.stratumForTier` 把档位翻成 `slots().get(min(t, size−1)).id()` 写进**新建户**的 `view.stratum`（贫农→中农→富农→地主）。
**★ 兜底档（t=0）不动既有落点**：`tierPositionId` 只在 `t > 0` 且目标为**新建户**时给出；`t = 0` ⇒ 沿用 `choosePosition`（角色元组匹配）—— 这是 §6 的关键判断 D1。

### ⑤ 缺生产工具可以买（用户①）

本模型里"取得生产资料"只有两条既有通道：**商品腿**（市场买/赊购）与**闲置份额租佃**（`OwnershipStakeBook`）。两条都用上：
- **决策层**：`no-tool-input` 不再否决（市场交易 `tool` 商品 ⇒ "买得到"）；
- **执行层（执行期真的让商号开起来）**：`planAssetMoves` 新增 `crossIndustryIdleInHex` —— 当这一笔**没带来作业资本**时，闲置份额候选集放宽到"**同一格内、同一种 AssetKind**"（`isIdleShare` 判据不变：没有任何组织/在产 unit 在用 ⇒ 租走它不会让别人的产能凭空消失）；
- **决策层的规划复核**：`ModeMigrationPolicy.reserveIdleAssetsFor` 同一放宽（带得来 ⇒ `false`，逐值等于改前）；
- **门 + 读数**：`filterOnDemandMerchantMoves` 的放行判据 + INFO `ONDEMAND_TRADE_MIGRATION_TOOL_PURCHASE`（含市场单价与闲置量）。
- **负向**（用户说的"买不到"）：市场不交易工具 **或** 本格没有闲置同种资产 ⇒ 维持具名跳过（`ONDEMAND_TRADE_MIGRATION_SKIPPED` 新增 `toolTradedAtMarket` / `idleToolMilliInHex` 两栏，卡在哪一条可读）。

### ⑥ 噪声与次序

- **壳户**：`plan()` 的候选构建循环（`:404-419`）对 `population ≤ 0 / 无阶层归属`（`shellSkippedNoPopulation`）与 `参与率折算劳动 = 0`（`shellSkippedNoLabor`）两族**跳过并只计数**；轮级 DEBUG `MODE_MIGRATION_CANDIDATES` 新增这两栏（"没有"也是要读的事实），**不逐户刷屏**。
- **次序（回代码核，不改代码）**：**滑落先、转业后 already 成立** ——
  - 滑落：`EconomySettlement:2698` `ClassSlideSettlement.settleAtMarketRoundEnd`，在**市场轮块内**（撮合 `:2645` 与价格写回之后、4b/4c/5 之前）；
  - 转业：`EconomySettlement:3311` `ModeMigrationPolicy.plan` → `:3392` `ModeMigrationSettlement.apply`，在**周期关账钩子内**（同一 tick 的更晚处），且计划读 `session.sheet().classMemberships()`（工作副本 = 滑落后的最新阶层状态）。
  - ★ **频率差（v3.31 要求具名）**：滑落**每市场轮**（5 天）；转业**只在关账钩子**（官署 30 天 / 120 天关账）。关账日都是 5 的倍数 ⇒ 那些天"市场轮先跑、滑落先算完、转业随后读到滑落后状态"；**但"转业也在市场轮末"是错的**，后人勿照抄。

---

## 4. 逐值表（结构性；运行级读数交测试批）

**A. 速度映射**（`profitGapSpeedPerMille`，SLOPE=100，收益以 `netPerLaborScaled` 计）：

| 收益_当前 | 收益_其他 | gap | 分母 | 速度‰ | 说明 |
|---|---|---|---|---|---|
| 1,000,000 | 1,000,000 | 0 | 1,000,000 | **0** | 差 = 0 ⇒ 不转业 |
| 1,000,000 | 900,000 | −100,000 | 1,000,000 | **0** | 差 < 0 ⇒ 不转业（**债务再高也一样**：本条不再读债务） |
| 1,000,000 | 1,100,000 | 100,000 | 1,000,000 | **10** | 相对差 0.1 |
| 1,000,000 | 2,000,000 | 1,000,000 | 1,000,000 | **100** | 相对差 1.0 |
| 1,000,000 | 11,000,000 | 10,000,000 | 1,000,000 | **1000** | 相对差 ≥ 10 ⇒ 打满（= A 规则同速） |
| 0 | 1,000,000 | 1,000,000 | **1**（具名：不许除零） | **1000** | 现在收益 0 ⇒ 分母 1 ⇒ 打满（★后果见 §6 D4） |
| −1,000,000 | −500,000 | 500,000 | 1,000,000 | **50** | 现在亏、但少亏 ⇒ 相对差 0.5 |
| 1,000,000 | 1,000,001 | 1 | 1,000,000 | **1**（地板） | "只要更高就有速度" |

**B. 档位支撑**（`∈ capacityPerUnit` 的 `k` 逐个判；下表用 trade 模板 `{TOOL:120}`、`laborPerUnit=1000`、20 人户 `laborMilli≈160,000`）：

| 档 t | 参与率‰ | 规模_额外 | 需求（毫工具） | 带 0 / 2,000 / 7,000 / 200,000 毫工具时支撑？ | 落点位置（merchant） |
|---|---|---|---|---|---|
| 0 贫农 | 950 | 0（兜底） | **0** | ✅ / ✅ / ✅ / ✅ | 兜底档 ⇒ 沿用 `choosePosition`（见 D1） |
| 1 中农 | 900 | 18 | 2,160 | ❌ / ❌ / ✅ / ✅ | `self_employed` |
| 2 富农 | 750 | 54 | 6,480 | ❌ / ❌ / ✅ / ✅ | `principal` |
| 3 地主 | 100 | 1,440 | 172,800 | ❌ / ❌ / ❌ / ✅ | `principal`（位置数封顶） |

⇒ 同一户带 0 / 2,000 / 7,000 / 200,000 毫工具 ⇒ 档位 **0 / 0 / 2 / 3**（`middle_peasant` / … / `rich_peasant` / `landlord` 槽位），**逐值可对照**。
★ 农田（`{LAND:1000}`，LAND 不可移动）与作坊（`{WORKSHOP:1}`，同样不可移动）**恒为档 0** —— 见 §6 D3。

**C. 库存随迁取整**（源户 100 人、粮 1,000,000 毫、两笔 move 各 30/70 人）：

| 笔 | 迁前人口 | popTake | 可用粮 | 带走 | 源户剩余 |
|---|---|---|---|---|---|
| 1 | 100 | 30 | 1,000,000 | ⌊1,000,000×30÷100⌋ = **300,000** | 700,000 |
| 2（迁空） | 70 | 70 | 700,000 | 全部 **700,000** | 0 |

**D. 壳户**：`population = 0` 或 `参与率折算劳动 = 0` ⇒ 不进候选；轮级 DEBUG 报两个计数（前后行数对比交测试批）。

---

## 5. 关键判断（为什么这样拆 / 推翻过什么）

| # | 判断 | 理由 / 被推翻的路线 |
|---|---|---|
| J1 | 速度**逐目标**算，而不是整户一个数 | 用户原话是"**其他生产方式**比目前预期收益更高 ⇒ 产生转业速度" ⇒ 差是**对某个候选**的差；整户一个数会让"落到哪个目标"与"走多快"脱钩 |
| J2 | 速度用 `Prospect.netPerLaborScaled`（既有唯一尺），**不新造第二把收益尺** | 权重已用同一把尺（D-024）；两把尺必然漂开 |
| J3 | "可迁人口"仍保留 `max(1, ⌊人口 × 速度 ÷ 1000⌋)` 的**至少 1 人**地板 | 与改前同形制；否则人口 < 1000 的户在任何速度下都走不动（"只要更高就有速度"会被整数截断吃掉） |
| J4 | 档位判据的输入 = **整户**能带走的作业资本（不用逐笔比例） | 逐笔比例在规划期还不知道（popTake 由 allocate 定）；整户口径保守且与执行期的 asset 随迁同源（比例切只会更少，不会更多） |
| J5 | **兜底档（t=0）不动既有落点**（沿用 `choosePosition`） | ① 兜底档必须保住"零库存也能转业"（若强制落到 mode 的**最低位置**，那些位置多为**工资位置**，要求既有雇主 `NO_EMPLOYER` ⇒ 可能整类不可行 ⇒ 反而把②判据做死）；② t=0 是绝大多数户的现状 ⇒ 逐值不变，把行为变化限制在"真带得起资本"的户上。★ 用户②给的两条去处是"最低档**或**更对应档位"⇒ 走后者，**不构成与原话冲突**。候选替代（**未采纳，留给控制方**）：t=0 也强制落到最低位置 |
| J6 | 档位由**计划**给、执行期只翻译成槽位，不重算 | 执行期的"能带走多少"依赖逐笔切分顺序；两处各算一遍必然漂开 |
| J7 | 买工具走"商品腿（可买判据）+ 闲置份额租佃（真的让商号开起来）"两条既有通道 | 本模型**没有资本品市场**：`AssetKind.TOOL` 份额不会从商品买入中长出来（回代码核：`new OwnershipStake(` 的 8 个落点里没有一处由商品派生）。⇒ 只有"租同格闲置"能让新商号拿到真实规模；否则就是 W7 明令禁止的"空商号" |
| J8 | 跨产业闲置租佃**只在"这一笔没带来作业资本"时**放宽 | 带来了 ⇒ 逐值等于改前；没带来 ⇒ 正是 W7 记的 E3 遗留门槛（只有"商号本金主"持 TOOL 份额） |
| J9 | 商品随迁**不搬 HAUL**（运输服务） | 它是"在本格能交付的运力"现货（`MerchantCapacityPool:781` 读它），跨格搬等于凭空承诺别处的服务；其余实物商品都可搬运 |
| J10 | 随迁转移**一条 move 一条转移**（多商品腿），不逐商品一条 | 与既有的"一笔买卖 = 一条货腿 + 一条钱腿"同粒度；`Transfer` 两端不得相等（一条 move 两端恒不同） |
| J11 | 壳户跳过**不逐户打点** | W7e 实测每轮上千条；逐户日志会淹掉"这一轮发生了什么"（与 W7 的级别取舍同族） |
| J12 | 转业**不搬** `Liability`/`Pledge`…… 本批未触及 | 非本批范围（未改） |
| J13 | **降档重试**只做一层（默认位置 → 最低位置），不做逐档下降 | 档位阶梯的**中间档**要的是"更多资本"（本户拿不出就是拿不出）；真正可能救活候选的只有**不要资本**的那一档（最低档）。★ 逐档下降会在 `{LAND}`/`{WORKSHOP}` 类产业上白跑 3 次全量 prospect（性能红线），收益为零 |

---

## 6. 偏离记录（主动、具名）

| # | 偏离 | 说明 |
|---|---|---|
| D1 | **速度映射形状**与**支撑档位判据**都是**控制方口径**（非用户裁定） | 速度形状见任务书 §2①（"控制方冻结的默认形状（可推翻，按此实现并在账本记偏离）"）；档位支撑判据是任务书 §2④ 授权"自己定并给算式"。两条都写在 §3，可被用户/控制方随时推翻 |
| D2 | 可移动商品集合**排除了 HAUL**；其余商品全搬 | 代码里**没有**"商品是否可搬运"的声明位；本批按"实物 vs 服务"划线并落在唯一拼写点 `ModeMigrationSettlement.isMobileCommodity`。★ 后果（具名）：跑商家户迁移时，手上未交付的运输服务货留在原格（跨格交付本就不成立） |
| D3 | 农田/作坊目标**恒为兜底档**（档位不随库存变） | 它们的 `capacityPerUnit` 是 LAND/WORKSHOP —— D-023 §4 明定**不可移动**⇒"带得走"恒 0 ⇒ 高档需求恒不满足。**如实记**：这是**档位阶梯**在这两条路径上不产生新行为（档位恒 0 ⇒ 落点沿用 `choosePosition`）；★ 这两条路径**仍会因 ② 的"降档重试"（N10）与 ① 的速度口径变化而改变行为** —— 那段变化计入 N1/N10，**不是**"逐值不变"。要按库存定档对农田也生效，需要一条"带得走的资本 → 土地权利"的换算（本批不做，属新机制） |
| D4 | `收益_当前 = 0` 时速度按**绝对差**（分母 = 1）⇒ 极易打满 | 这是控制方冻结形状 `max(1, |收益_当前|)` 的直接后果。**具名后果**：当前预期净收益恰为 0 的户（如流民户 `currentProspect` 不可行 ⇒ net = 0）面对任何真实利润 ⇒ **满速 1000‰**。★ 建议（**未实现**）：把分母下限从 1 提到一个"典型单位"（如 `PER_LABOR_SCALE/1000` = 1000），或对 `收益_当前 ≤ 0` 单列一支；本批按冻结形状实现，不改 |
| D5 | 新增 `≥1‰` 地板（gap > 0 ∧ SLOPE > 0 ⇒ 速度 ≥ 1） | 用户原话"只要更高就有速度"；若纯取整会让极小差恒 0（"更高但不走"）。★ 与 D6 一起构成对冻结形状的两处**补充** |
| D6 | `SLOPE = 0` ⇒ 收益差**不产生任何速度**（转业停摆） | 与 α=0「GM 把价格钉死」同制；没有这一条，D5 的地板会把"GM 关掉转业"变成"每轮仍走 1‰" |
| D7 | 无审计账本时随迁转移的 id 形状 = `tr-<day>-migration-<n>` | 唯一 id 分配口是 `ProductionLedger.Account#mint`，而 `apply` 的旧重载允许 `auditLedger = null`（测试/单模块夹具）。★ 两条 id 形状**只差前缀**，且两条路径的**物资效果逐值相同**（都走同一个 `applyTransfer`）；真实 tick 一律走 `mint`（`EconomySettlement:3392` 传的是当天账本） |
| D8 | `Target` / `MoveDraft` / `MigrationMove` 各加一维（`speedPerMille` / `tierIndex` / `targetTierIndex`） | 都是**瞬态计划类型**（不进 Codec/ChangeSet/落盘；`grep` 全仓仅 economy 内部 + 3 个测试类引用）⇒ **不触发铁律 5**。旧 9/10/11 参构造保留为便利重载（默认 0），**不是旧档兼容位**：它们服务的是夹具/手工计划 |
| D9 | `AccountSession.householdOfActor()` 与 `ModeMigrationSettlement.isMobileAsset` 只改可见性（private → 包内） | 唯一写口 `applyTransfer` 的入参形状 / "什么带得走"的唯一拼写点，不得有第二份 ⇒ 共享而不复制 |
| D10 | 转业速度的"上界常量"复用 `A_RULE_TRANSFER_SPEED_PER_MILLE`（1000） | 不新造 `MAX_TRANSFER_SPEED_PER_MILLE`（同一件事两处拼写会漂）；常量注里写明它同时是收益差映射的 clamp 上限 |

---

## 7. 会改变数值行为的清单（相对 HEAD `aed897f3`）

| # | 变化 | 影响面 |
|---|---|---|
| N1 | **转业速度口径整体替换**：`max(10‰, f(债务/资产))` → 收益差映射 `clamp(⌊100×gap÷max(1,|当前|)⌋, 0/1, 1000)` | 全部生产方式迁移的**笔数与人口**；真实世界读数交测试批 |
| N2 | **速度 0 ⇒ 不产生 move**（候选过滤新增 `speed > 0`） | 与 N1 同源；`SLOPE = 0` 时转业完全停摆 |
| N3 | **壳户跳过**：`population > 0 ∧ 参与率折算劳动 = 0` 的户不再进候选 | ① 少掉每轮上千条 `no-labor` 噪声；② 这类户**不再**走 A 规则的 `DISPLACED` 支（它们的 net 恒不可行、本该"继续负债"），行为差异见 §9 未验证项 |
| N4 | **档位改落点**（仅 `tierIndex > 0` 且新建户）：位置改为"该档的位置"、新建户 `view.stratum` 改为 `slots()[档位]` | 只在带得起资本、且目标产业 `capacityPerUnit` 含**可移动**资产（`{TOOL}`：家庭纺织 / trade）时发生；农田/作坊/租佃/自耕（LAND/WORKSHOP 或单位置）**逐值不变** |
| N5 | **商品库存随迁**（新腿） | 每一次成功的迁移笔都会把源户**冻结之外**的实物库存按人口比例搬走（含粮）。⇒ 粮食/纤维/布/工具的**逐户分布**改变（总量不变）；对"源户迁空"的笔，余数随最后一笔走（改前留源户 = 留在 0 人壳户账上） |
| N6 | **`no-tool-input` 不再是硬否决**（市场交易工具即放行） | 跑商候选的可行性面变大 ⇒ merchant 迁移笔数上升（W7e 实测：sw 的 `no-tool-input` 703 条中，凡目标格市场交易 `tool` 的都不再被拒） |
| N7 | **按需商号闸改判 + 同格跨产业闲置租佃** | 非城市格/缺工具户现在能真的开出商号（拿到真实规模的 TOOL 租佃份额）⇒ `MERCHANT_TRADE_INDUSTRY_CREATED`、`ONDEMAND_TRADE_MIGRATION_*` 与相关资产份额分布改变；**闲置**份额的 owner 不变、operator 变为新商号 ⇒ 相关产业的**可租池**变小 |
| N8 | `plan()` 不再建 `SettlementOrderBook` 快照 | 纯性能/读数变化（该快照在 W4 的滑落/排序里仍各建各的）⇒ 转业路径**少一次全表估值**（耗时下降）；不影响任何状态 |
| N9 | 新日志事件 3 条（`MIGRATION_INVENTORY_CARRIED` INFO、`ONDEMAND_TRADE_MIGRATION_TOOL_PURCHASE` INFO、`MODE_MIGRATION_CANDIDATES` 加两栏） | 只影响日志输出（§一.9），不影响状态 |
| N10 | **降档重试**（②）：默认位置不可行 ⇒ 按最低档位置重试一次 | 只在"新建目标 + 多位置 mode + 默认位置不可行 + 低位可行"时**新增**一个候选/一笔迁移（先例：无资产户原本在农田格完全无候选）⇒ 迁移笔数上升；每个候选仍过同一个可行性计算器 |

---

## 8. 硬编码字面量（本批新增/变动的）

| 字面量 | 落点 | 含义 |
|---|---|---|
| `PROFIT_GAP_SPEED_SLOPE_PER_MILLE = 100L` | `ModeMigrationPolicy:128` | 转业速度斜率（政策常量 / GM 可调预设，**写口建议**见常量注：搬进 `MigrationPolicy` 才算真可调） |
| `1000L`（复用 `A_RULE_TRANSFER_SPEED_PER_MILLE`） | `ModeMigrationPolicy:105` | 速度上界（不新造第二个常量） |
| `max(1L, ...)`（分母下限） | `profitGapSpeedPerMille` | 不许除零（控制方冻结形状） |
| `1L`（速度地板） | `profitGapSpeedPerMille` | "只要更高就有速度"（见 D5） |
| `"tr-" + day + "-migration-" + n` | `carryGoodsForMove` | 无账本时的确定性 id 形状（见 D7） |
| `"tr-<day>-<seq>"`（既有） | `ProductionLedger.Account#mint` | 真实 tick 的 id 来源（未改） |
| 档位阶梯的**参与率** | `IndustryClassSlots`（既有） | 950/900/750/100（本批**未改**，只读） |
| 事件名/理由串（新增 3 条） | 见 §7 N9 | 具名读数键 |

---

## 9. 会让既有测试失效的清单（按类名，**只报不改**）

| 类 | 失效原因 |
|---|---|
| `io.mosire.simos.economy.time.ModeMigrationPolicyD024Test` | `:438` / `:513` 断言 `isEqualTo(ModeMigrationPolicy.MIGRATION_PER_MILLE)` —— 该常量**已删**（§一.11），且断言的口径正是用户③推翻的"基线速度" |
| `io.mosire.simos.economy.time.ModeMigrationGovServiceFailClosedTest` | `:188` 引用已删的 `ModeMigrationPolicy.MIGRATION_PER_MILLE`（编译不过） |
| `io.mosire.simos.economy.time.MerchantOnDemandTradeW7Test` | `:634` 引用已删的 `ModeMigrationPolicy.MIGRATION_PER_MILLE`（编译不过）；另 `:100/:298-390`（按需商号"带不来作业资本 ⇒ 整笔跳过"）与 `:477` 的执行路径语义已被 ⑤ 改判（**买/租得到 ⇒ 放行**），需按新判据迁移 |
| `io.mosire.simos.economy.time.HomeModeMigrationProbeTest` | `:45/:319` 用**本类自己的**字面量 10‰ 推算可迁人口 ⇒ 与新速度口径不符（断言会红；不引用 main 常量，故**编译得过**） |
| `io.mosire.simos.economy.time.ExpectedProfitBookTest` | `:310/:328` 只断 `startsWith("NO_MERCHANT_CAPACITY")`；若夹具目标格市场**交易 tool**，则 `no-tool-input` 不再成立（理由变为可行）⇒ 行为面待测试批复核夹具（**未验证**：本批不能跑测试） |

---

## 10. 交账自检

| 项 | 结果 |
|---|---|
| `tools/mvn-lock.sh -q spotless:apply` | **rc=0**；文件集与本批开工时**逐项相同**（7 个文件：本批 6 个 main + 1 个 `docs/**`，见下"非本批改动"） |
| `tools/mvn-lock.sh -q -DskipTests compile -am` | **rc=0**（最后一次：本批 6 个文件全绿；中途 3 次编译红都是缺 import，当场修掉） |
| 一次一个 Maven | 全程遵守（跑前 `pgrep` 只命中本命令自身；`tools/mvn-lock.sh` 锁） |
| 测试 | **一条没写、一条没跑**（§一.5）。★ 本批**无法**跑单条 `-Dtest=…`：`MIGRATION_PER_MILLE` 的删除使 economy 测试模块编译不过（见 §9 前三条），测试批必须先迁移那三处 |
| commit | 无 |
| **非本批改动（如实上报）** | `docs/superpowers/specs/2026-10-10-unified-price-and-ordered-settlement-design.md` 在**本批进行中**被追加了 **v3.31**（控制方：确认"先跌落、后转业"次序 + 两条速度算式形状）。**本批一字未碰 `docs/**`**；`git status` 里那一处不是本批产物 |
| **未完成 / 未验证** | 见 §11 |

---

## 11. 未完成 / 未验证项（BLOCKED 与否）

1. **运行级读数全部未取**（本批禁 `test`，且测试模块当前编译不过）：
   - N1/N2 的真实世界速度分布与迁移笔数（sw/three-powers 0:360 对照 W7e 的 26/60 笔）；
   - N3 的"壳户跳过前后行数"（`MODE_MIGRATION_CANDIDATES` 的两栏）；
   - N5 的"逐笔带了什么、多少"与商品守恒前后合计；
   - N6/N7 的 `MERCHANT_TRADE_INDUSTRY_CREATED`、`ONDEMAND_TRADE_MIGRATION_TOOL_PURCHASE` 读数。
   ⇒ **交测试批**。结构性自证在 §4。
2. **`MIGRATION_PER_MILLE` 的删除使 economy 测试模块编译不过**（§9）：这是**有意**的（§一.11 + 断言的正是被推翻的口径），但**代价如实记**：在测试批迁移那三处之前，economy 的既有回归网**跑不起来**。★ 若控制方要保住"回归网先能跑"，可选替代 = 保留该常量并把速度地板接上它（本批**未采纳**：那会让 D024Test 的旧断言以"假绿"存活）。
3. **档位判据对 LAND/WORKSHOP 类产业不生效**（D3）：如实记为**已知边界**，不是实现缺陷；要覆盖它需要新机制（换算），**不在本批授权范围**。
4. **`收益_当前 = 0` ⇒ 满速**（D4）：按控制方冻结形状实现，**未做**软化；建议已写在 D4，等控制方裁定。
5. **`t = 0` 的落点沿用既有 `choosePosition`**（D1/J5）：若控制方要"t=0 也强制落到 mode 最低位置"，改动面 = `buildTargets` 里 `tierPositionId` 的取值条件 + §4B 的表；**未做**。
6. **`MigrationPolicy` 里的 GM 写口未接**：斜率仍是编译期常量（D/常量注已给写口建议）；真要 GM 可调需动持久形状（铁律 5 三件套），**不在本批**。
7. **`docs/**` 的 v3.31 不是本批产物**（见 §10）。

---

## 12. 落点速查（收工后行号，供测试批/控制方直接跳）

| 落点 | file:line |
|---|---|
| 速度算式 | `ModeMigrationPolicy.java:1924` `profitGapSpeedPerMille` |
| 速度常量（斜率） | `ModeMigrationPolicy.java:128` `PROFIT_GAP_SPEED_SLOPE_PER_MILLE = 100L` |
| 速度逐目标现算 | `ModeMigrationPolicy.java:1020`（`buildTargets` 尾部） |
| 档位判据 | `ModeMigrationPolicy.java:1144` `supportedTierIndex` |
| 带得走的作业资本 | `ModeMigrationPolicy.java:1201` `carriedCapitalByKind` |
| 档位→位置 | `ModeMigrationPolicy.java:1236` `tierPosition` |
| 降档重试 | `ModeMigrationPolicy.java:947` |
| 壳户跳过计数 | `ModeMigrationPolicy.java:2528` `CandidateTally.shellSkipped` |
| 商品库存随迁 | `ModeMigrationSettlement.java:1375` `carryGoodsForMove`（调用点 `:585`） |
| 可移动商品判据 | `ModeMigrationSettlement.java:1462` `isMobileCommodity` |
| 档位→阶层槽位 | `ModeMigrationSettlement.java:964` `stratumForTier`（调用点 `:916`） |
| 按需商号闸（买/租放行） | `ModeMigrationSettlement.java:1932-1960`（`filterOnDemandMerchantMoves`） |
| 执行期价表（无 topology） | `ModeMigrationSettlement.java:2007` `marketForHex` |
| 本格闲置作业资本 | `ModeMigrationSettlement.java:2020` `idleToolInHex` |
| 同格跨产业闲置租佃 | `ModeMigrationSettlement.java:1070`（`planAssetMoves` 的 `crossIndustryIdleInHex`） |
| 可买判据（no-tool-input） | `ExpectedProfitBook.java:706` |
| 位置指定重载 | `ExpectedProfitBook.java:240`（+ `:1131` `resolvePosition`） |
| 升序位阶 | `ClassLadderBook.java:173` `POSITION_LADDER_ASCENDING` |
| 新转移原因 | `TransferReason.java:117` `MIGRATION_CARRY` |


---

# W9-fix（分母 + 速率上限 + U1/U5/U3）

> 批次：**W9-fix**（2026-10-11 夜，写码 Agent 落盘；控制方审后提交）。**回归修复批，正确性优先。**
> 权威：控制方裁定 **v3.32 §7 两条 + 第 3/4/5 条**（`docs/superpowers/specs/2026-10-10-unified-price-and-ordered-settlement-design.md:299-311`）
> + 任务书 §1 用户原话（「只要其他生产方式比目前生产方式预期收益更高，就产生转业速度」/「债务…生产方式收益…」）+ W9d 账本 §5 的 U1/U3/U5。
> 授权写入面：`simos-economy/src/main/**`（本批**只动 3 个 main 文件**；`simos-economy-api/src/main/**` 与 `simos-app/src/main/**` **一字未改**）。
> 硬边界：**未碰** `src/test/**`（一字未改、一字未删）/ 其他模块 / `docs/**` / `AGENTS.md`；**未 commit**；**未跑** `verify`/`clean`/`package`（见 §9）。
> 本批落盘 md5（提交时以文件为准）：`ModeMigrationPolicy.java e0139f3f421e371a445797d94193157b`、
> `ExpectedProfitBook.java f251d260a85550f07568f0c31a65e556`、`EconomySettlement.java 7c07c3840eba805bf019a1018d66298a`。

---

## 1. 改动文件清单（本批）

| 文件 | 改动性质 |
|---|---|
| `simos-economy/src/main/java/io/mosire/simos/economy/time/ModeMigrationPolicy.java` | ① 分母改**正参考基准**（三参算式 + `profitScaleBasisScaled`）；② 新增 `MIGRATION_SPEED_CAP_PER_MILLE = 100` + `moveablePopulation` 人口闸（A 规则不受约束）；③ U1：源户候选表与并入目标表**分开**；④ U5：规模 = 0 ⇒ 最低档；⑤ 算式类注/常量注/落点速查同步改写 |
| `simos-economy/src/main/java/io/mosire/simos/economy/time/ExpectedProfitBook.java` | 新增 `public static long perLaborScaled(amountMilli, laborNeedMilli)`（把"毫钱读数"折成每单位劳动的缩放值）—— 与 `netPerLaborScaled` **同一把尺**的唯一拼写点，供收益规模基准复用（唯一算式不复制） |
| `simos-economy/src/main/java/io/mosire/simos/economy/time/EconomySettlement.java` | U3：`MIGRATION_MOVE` 字段表补 `targetTierIndex`（`speedPerMille` **原本已有**，未重复） |
| `.superpowers/sdd/2026-10-11-w9-mode-switch/impl-ledger.md` | 本节（W9d 已把该目录纳入台账） |

★ **W9 + W9d 的未提交改动一字未回退**（`git status` 里其余 9 个文件与本批开工时逐项相同）。

★★ **如实上报一处"非本批本意"的字节改动（不是我编辑的，是强制门禁的副作用）**：`tools/mvn-lock.sh -q spotless:apply`
（任务书 §4 明令要跑）会格式化**反应堆里的全部源文件**；本批那次把 W9d 新增的**未跟踪**测试文件
`simos-economy/src/test/java/io/mosire/simos/economy/time/ModeMigrationInventoryCarryW9Test.java` 重写了一遍
（mtime `20:48:04` = spotless 落盘时刻；另一个新文件 `ModeMigrationPolicyW9Test.java` 本来就合规、未被重写 ⇒ 机制确认）。
* 我**没有编辑**它的内容（`edit`/`write` 一次都没落在 `src/test/**`）；google-java-format **只动空白/换行**。
* 已复核：`-Dtest=ModeMigrationInventoryCarryW9Test` ⇒ **3 条全绿**（`Tests run: 3, Failures: 0, Errors: 0`）。
* 影响面：该文件的**字节/md5 与 W9d 落盘时不同** ⇒ 若 W9d 台账引用了它的 md5，以本批后的字节为准（或让测试批重跑该类）。

---

## 2. 编译真命令 + rc

| 命令 | rc | 说明 |
|---|---|---|
| `tools/mvn-lock.sh -q spotless:apply` | **0** | 跑了两轮（改动后 / 收工前），第二轮无改动（幂等） |
| `tools/mvn-lock.sh -q -DskipTests compile -am` | **0** | `-am` 必需（本仓纪律）；中途无红 |
| `tools/mvn-lock.sh test -pl simos-economy -am -Dtest=ModeMigrationPolicyW9Test -Dsurefire.failIfNoSpecifiedTests=false` | 1（**预期红**，见 §7） | **测试模块编译通过**（这是本批的关键自证前提）；`Tests run: 10, Failures: 4` |
| `tools/mvn-lock.sh test -pl simos-economy -am -Dtest=ModeMigrationPolicyD024Test -Dsurefire.failIfNoSpecifiedTests=false` | 1（**预期红**，见 §7） | `Tests run: 6, Failures: 2` |
| `tools/mvn-lock.sh test -pl simos-economy -am -Dtest=ModeMigrationInventoryCarryW9Test -Dsurefire.failIfNoSpecifiedTests=false` | **0** | `Tests run: 3, Failures: 0` —— 复核被 `spotless:apply` 顺带重排的那个 W9d 新测试文件**语义未变**（见 §1 的上报） |

★ 一次一个 Maven 全程遵守（跑前 `pgrep` 无本仓 Maven；`tools/mvn-lock.sh` 锁）。
★ **测试模块能否编译是本批自证能力的前提**：`profitGapSpeedPerMille(long,long)` 的**两参签名被保留**（见 §3① 偏离 D-e），否则 `ModeMigrationPolicyW9Test` 编译不过 ⇒ 连单条 `-Dtest` 都跑不了。

---

## 3. 五件逐条：现状 → 改法 → 自证

### ① 分母口径修正（必修）

**现状（`file:line`，改前）**：`ModeMigrationPolicy.java:2024`（W9 首版）
`long denominator = Math.max(1L, Math.abs(currentNetPerLaborScaled));` —— 收益_当前 ∈ {0, 极小正} ⇒ 分母退化为 1。

**改法**：
```
分母 = max(|收益_当前|, |收益_其他|, 收益规模基准)          ← ModeMigrationPolicy.java:2036-2040（三参算式 :2026）
收益规模基准 = max( perLaborScaled(当前/目标.revenueMilli,  laborNeed),
                   perLaborScaled(当前/目标.laborCostMilli, laborNeed) )
              两侧取大（调用点 :1020 一带 + :2071-2093 profitScaleBasisScaled）
perLaborScaled(a, l) = ⌊a × PER_LABOR_SCALE ÷ l⌋（a ≤ 0 ∨ l ≤ 0 ⇒ 0）   ← ExpectedProfitBook.java:1332
```
* **基准的两个量都是 Prospect 现成读数**：每单位规模劳动的**毛收入**（产值规模）与**口粮/劳动成本**（养人下限）—— 回代码找的正量，不是拍的魔法数；两者与收益差**同一把尺**（`PER_LABOR_SCALE`）⇒ 比值无量纲。
* **`gap > 0` ⇒ 分母恒 > 0**（前两项不会同时为 0）⇒ 不需要 `max(1,·)` 那种"不许除零"补丁（那正是病根）。
* 两参形态保留为**基准 = 0 的薄封装**（`profitGapSpeedPerMille:2015`）；生产路径只走三参形态（唯一实现）。

**自证（逐值表；实数由**真 Java 求值**给出：`/tmp` 探针直接调 main 的三参算式，非 python 复述）**：
基准 = **1,000,000**（= "该生产方式 1 单位规模劳动的毛收入 ≈ 1 毫钱/千分劳动·日"这一量级，与 W9 账本 §4A 同尺）：

| 收益_当前 | gap | 收益_其他 | 分母 | **新速度‰** | 旧速度‰（W9 首版） |
|---|---|---|---|---|---|
| −1,000,000（负） | 0 | −1,000,000 | 1,000,000 | **0** | 0 |
| −1,000,000 | 1（极小） | −999,999 | 1,000,000 | **1** | 1 |
| −1,000,000 | 100,000（小） | −900,000 | 1,000,000 | **10** | 10 |
| −1,000,000 | 1,000,000（中） | 0 | 1,000,000 | **100** | 100 |
| −1,000,000 | 10,000,000（大） | 9,000,000 | 9,000,000 | **111** | **1000** |
| 0 | 0 | 0 | 1,000,000 | **0** | 0 |
| 0 | 1（极小） | 1 | 1,000,000 | **1** | **100** |
| 0 | 100,000（小） | 100,000 | 1,000,000 | **10** | **1000** |
| 0 | 1,000,000（中） | 1,000,000 | 1,000,000 | **100** | **1000** |
| 0 | 10,000,000（大） | 10,000,000 | 10,000,000 | **100** | **1000** |
| 1（很小正） | 0 | 1 | 1,000,000 | **0** | 0 |
| 1 | 1（极小） | 2 | 1,000,000 | **1** | **100** |
| 1 | 100,000（小） | 100,001 | 1,000,000 | **10** | **1000** |
| 1 | 1,000,000（中） | 1,000,001 | 1,000,001 | **99** | **1000** |
| 1 | 10,000,000（大） | 10,000,001 | 10,000,001 | **99** | **1000** |
| 1,000,000（正常） | 0 | 1,000,000 | 1,000,000 | **0** | 0 |
| 1,000,000 | 1（极小） | 1,000,001 | 1,000,001 | **1** | 1 |
| 1,000,000 | 100,000（小） | 1,100,000 | 1,100,000 | **9** | 10 |
| 1,000,000 | 1,000,000（中） | 2,000,000 | 2,000,000 | **50** | 100 |
| 1,000,000 | 10,000,000（大） | 11,000,000 | 11,000,000 | **90** | **1000** |

★ **结构性质（如实记，偏离 D-a）**：分母含 `|收益_其他|` 且 ≥ 目标的每单位劳动毛收入 ⇒ 在 `收益_当前 ≥ 0` 时 **gap ≤ 分母** ⇒ **速度天然 ≤ SLOPE = 100‰**；只有 `收益_当前 < 0`（两侧反号）时才可能到 200‰（故上限 100‰ 在那一支才真正吃紧）。
★ 基准 = 0 的退化支（两侧都不可行/没有市场，仅夹具）：分母 = max(|当前|, |其他|) ⇒ `gap > 0` 时恒 > 0、速度 ≤ 200‰ —— **不再是 1000‰ 爆炸**。

### ② 转业速率上限（必修，政策预设）

**现状**：无上限（W9 首版让 `moveable = max(1, ⌊人口 × 收益差速度 ÷ 1000⌋)`，速度 1000‰ ⇒ 整户）。

**改法**：
```
每市场轮每户可转人口 ≤ max(1, ⌊该户人口 × MIGRATION_SPEED_CAP_PER_MILLE ÷ 1000⌋)
MIGRATION_SPEED_CAP_PER_MILLE = 100（默认，政策预设；ModeMigrationPolicy.java:162）
唯一拼写点：moveablePopulation(population, speedPerMille)（:2110），返回 min(人口, max(1, ⌊人口×速度÷1000⌋))
调用点：planForSource 收益差支（:742-746）= min(按最快速度折算, 按上限折算)
A 规则两支（`A_RULE_MAX_SPEED` / `DISPLACED_ABSORBED`）**不经过**这里（moveable = 全户人口、速度 1000‰）
```
* 形制与既有"可迁人口"口径同形（`max(1, ⌊人口×速度÷1000⌋)` 的"至少 1 人"地板 J3 保留）。
* **上限是人口闸、不是速度读数闸**：`MigrationMove.transferSpeedPerMille` 仍报收益差算式输出（判据"差 > 0 有速度"逐值可读）；两栏在 `MIGRATION_MOVE` 同时可读（拒绝 D-b）。
* **频率口径（具名，偏离 D-c）**：本仓转业只在**关账钩子**跑（官署 30 / 120 天）⇒ 该上限按**每次计划轮**落 = "每市场轮 ≤ 上限"的**更强形式**（更保守），不会放过任何一个市场轮。

**自证（逐值表，真 Java 求值 `moveablePopulation`）**：

| 人口 | 速度‰ | 速度折算 | **上限折算** ⌊人口×100÷1000⌋(≥1) | **可迁人口** | 上限生效？ |
|---|---|---|---|---|---|
| 200 | 200 | 40 | 20 | **20** | ★是 |
| 200 | 100 | 20 | 20 | **20** | 否（两条闸同点） |
| 100 | 200 | 20 | 10 | **10** | ★是 |
| 20 | 200 | 4 | 2 | **2** | ★是 |
| 5 | 200 | 1 | 1 | **1** | 否（"至少 1 人"地板） |
| 3 | 1 | 1 | 1 | **1** | 否 |
| 1 | 1000 | 1 | 1 | **1** | 否 |

★ **W9 对照**：`moveablePopulation(200, 1000) = 200`（整户迁空）—— W9d 实测"单笔最多搬 199 人"正是它；修复后同一笔上界 = ⌊200×100÷1000⌋ = **20 人**。
★ **计划级样本（D024 夹具副本，`/tmp`，非仓内测试）**：亏损户 + 有流动性 ⇒ `reason=[PROFIT_WEIGHTED] speed=101 population=10`（上限折算 = 10，速度折算 = max(1,⌊100×101÷1000⌋) = 10 ⇒ 两条闸同时落 10）；对照 `population=10` 是把"整户 100 人"夹住的结果（旧口径该支速度 101‰、可迁 10 —— 这一支两条闸重合，严格生效的样本见上表 200/200 与 20/200 两行）。
★ **A 规则的对照（如实记，不是缺陷）**：同一夹具在"亏损 + 无流动性"下走 A 规则 ⇒ `speed=1000 population=100`（**整户**）。控制方裁定明写"A 规则不受本上限约束"，本批**未动**；W9d 真世界实测 A 规则迁移 = **0 笔**（sw 91/91、tp 93/93 全是 `PROFIT_WEIGHTED`）⇒ 它不是本次硬牙的肇事者。

### ③ U1（未声明副作用，必修）：壳户过滤误伤"并入目标"

**现状（`file:line`，改前）**：`ModeMigrationPolicy.java:404-428`（W9 首版）—— **同一张 `modeByHousehold`** 既喂源户循环（`:496`），又经 `planForSource → buildTargets → bestExistingTarget`（`:1803` `modeByHousehold.get(household)`）当**并入目标注册表**；而建表时"参与率折算劳动 = 0 ⇒ `continue`"（`:416-419`）把这类户从**两张表**里一起删了。

**改法（两张表分开）**：
* `modeByHousehold` = **并入目标注册表**：与 W9 之前**逐值同形**（人口 > 0 ∧ 有阶层归属 ∧ 位置可解析）—— **不**因"劳动 0"剔户（`:449-476`）；
* `sourceCandidates`（`LinkedHashSet<HouseholdId>`）= **源户候选表**：在①之上再要求"参与率折算劳动 > 0"（`:474-475`），源户循环只走它（`:497`）；
* 计数口径**逐值不变**（先判劳动、再解析位置 ⇒ `skippedShellNoPopulation` / `skippedShellNoLabor` 与 W9 同值）；早退条件从 `modeByHousehold.isEmpty()` 改为 `sourceCandidates.isEmpty()`（并补同一条轮级 DEBUG）。

**自证**：
* **运行级（本批实测，真 Java）**：把 D024 夹具的**并入目标户 BUYER2 劳动改回 0**（副本在 `/tmp`，仓内 `src/test/**` 一字未改）⇒ `moves=1`：
  `U1-PROBE move source=hh-source target=hh-buyer-2 pop=4 speed=49 tier=0`。
* **跨批次对照（W9d 实测，同一夹具）**：`skippedShellNoLabor=2` + `MIGRATION_IN_KIND_LEG_UNAVAILABLE_REFUSED … in-kind-leg-unavailable:cloth` ⇒ **`moves=0`**；把劳动改 1,000 ⇒ 计划恢复（W9d 账本 §5 U1）。
  ⇒ **同一夹具：W9 ⇒ 0 笔；本批 ⇒ 1 笔（并入既有户那一支）** = 修复生效、方向正确。
* 改后行为的"已知正确性锚"：恢复的正是 **W9 之前（W7e 已验收）**的目标表形状（`git show HEAD:…ModeMigrationPolicy.java` 的建表循环）。

### ④ U5（判档缺陷，必修）：极小户被判最高档

**现状（`file:line`，改前）**：`ModeMigrationPolicy.java:1152-1159`（W9 首版）—— `scaleLabor = ⌊labor ÷ laborPerUnit⌋`，`laborMilli < laborPerUnit ⇒ scaleLabor = 0` ⇒ 每档 `extraScale = ⌈0×1000 ÷ 参与率⌉ − 0 = 0` ⇒ 每档都"天然支撑" ⇒ `best` 一路推到 **最高档 3（地主档）**（W9d 单测钉住：`tier(trade, 999, 0) == 3`）。

**改法**：`ModeMigrationPolicy.java:1235-1237` —— `scaleLabor <= 0 ⇒ return 0`（最低档；`需求(0,·) ≡ 0` 仍是"零库存也能转业"的结构根）。公式其余部分一字未改。

**自证（逐值表，真 Java 求值；trade 模板：4 档 950/900/750/100‰、`laborPerUnit=1000`、`capacityPerUnit={TOOL:120}`）**：

| 劳动（毫） | 规模_劳动 | 带 0 工具 | 2,000 | 7,000 | 200,000 |
|---|---|---|---|---|---|
| 0 | 0 | **0**（改前 **3**） | **0**（改前 3） | **0**（改前 3） | **0**（改前 3） |
| 1 | 0 | **0**（改前 3） | **0** | **0** | **0** |
| 998 | 0 | **0**（改前 3） | **0** | **0** | **0** |
| 999（= laborPerUnit−1） | 0 | **0**（改前 **3**） | **0** | **0** | **0** |
| 1,000（= laborPerUnit） | 1 | 0 | 3 | 3 | 3 |
| 3,000（= laborPerUnit×3） | 3 | 0 | 2 | 3 | 3 |
| 160,000（规模 160；**W9 账本 §4B 的原行**） | 160 | 0 | 0 | 2 | 3 |

★ 最后一行与 W9 账本 §4B 的 `0 / 0 / 2 / 3` **逐值相同** ⇒ 修复**只动"规模 = 0"那一支**（规模 ≥ 1 的判档面逐值不变）。

### ⑤ U3（读数缺口，必修）：`MIGRATION_MOVE` 补 `targetTierIndex`

**现状（`file:line`，改前）**：`EconomySettlement.java:3349-3372` 的 `MIGRATION_MOVE` 字段表 = day/source/target/targetHex/targetMode/population/moneyMilli/moneyByCurrency/debtMilli/**speedPerMille**/reason —— **没有** `targetTierIndex`（W9 新增的这一维只活在瞬态计划类型里 ⇒ 真世界读不到"谁落到哪一档"）。

**改法**：`EconomySettlement.java:3371-3377` 在 `speedPerMille` 之后插入 `"targetTierIndex", move.targetTierIndex()`。**级别/发射条件一字未动**（仍是 `RAW` 通道的 TRACE、`if (RAW.isTraceEnabled())`）。
★ **同类字段核查（任务书要求）**：`speedPerMille` **原本已在**（`:3369-3370`，未重复补）；同事件其余字段无缺口。

**自证**：计划级探针直接打印同一 accessor：`U1-PROBE move … tier=0`（与写入字段表的是同一个 `move.targetTierIndex()`）；字段表本身的**真世界可读性**交测试批（本批不打包，见 §9）。

---

## 4. 判据 1–5

| # | 判据 | 本批结论 |
|---|---|---|
| 1 | ★★ three-powers 0→240「零成交轮次 = 0」恢复 + Σfills / min·中位·max | **未跑真世界**（见 §9 理由：本批不打包 ⇒ 起不了世界）。**结构性自证**：肇事机制 = "收益_当前 ≈ 0 ⇒ 分母 1 ⇒ 1000‰ ⇒ 整户迁空"，两条闸各自把它按死 —— ① 分母含正基准（表 A：0 收益户 `1000‰ ⇒ 100/10/1‰`）；② 人口闸（200 人户 `200 人/笔 ⇒ 20 人/笔`）。**运行级读数交测试批** |
| 2 | 五条牙一字不动 | **未改**这五条、**未改** `BASELINE_FILLS`；本批只动 main 的 3 个文件（`git status` 可核） |
| 3 | W9 四条原判据不退化 | 收益差驱动：算式仍是唯一入口（差 ≤ 0 ⇒ 0，表 A 第 1 行；债务不参与 —— 本批**未**给算式加任何输入，三参里没有债务/资产）；零库存能转：`需求(0,·) ≡ 0` 未动，U5 后"劳动不足 1 单位"也落 0（表 C）；带库存随迁守恒：本批**未碰** `ModeMigrationSettlement` 的随迁腿（W9d 3 条守恒用例所在类不调 `plan` ⇒ 不受影响）；缺工具可买/可租：本批**未碰** `ExpectedProfitBook` 的 `no-tool-input` 通道与执行期闸门 |
| 4 | 速率上限逐值 | §3② 表（真 Java 求值）+ 计划级样本（`speed=101 population=10`）+ A 规则对照（`speed=1000 population=100`，按裁定不受约束） |
| 5 | 不回归（B2 / Revision / 性能 / W4 / W5 / W5.5 / W7 / W8） | **未跑**（不打包 ⇒ 无真世界）。**静态面**：本批未碰 `SettlementOrderBook`/`ClassSlideSettlement`/铸币/货币市场/运力/债务语义（`git diff` 只 3 个文件）。★ 具名风险：**B2 分段等价与 Revision 判据是"同版本内自洽"**，本批按设计改变世界轨迹 ⇒ 三形态 md5 会**整体换值**（不是漂移）；测试批必须**重跑三形态对比**（md5 相同 + Δ=1/Δ=3/Δ=360），**不是**与 W9d 的旧 md5 比 |

---

## 5. 会改变数值行为的清单（相对 W9/W9d 工作树）

| # | 变化 | 影响面 |
|---|---|---|
| N1 | **转业速度分母替换**：`max(1,|当前|)` ⇒ `max(|当前|, |其他|, 收益规模基准)` | 全部收益差驱动的迁移**速度值**；`收益_当前 ≈ 0` / 很小正的户降幅最大（表 A：1000‰ ⇒ 100/10/1‰）；`|当前|` 已 ≥ 两者的户**逐值不变**（如亏损户 −5,500,500 对 88,600：101 ⇒ 101‰，实测） |
| N2 | **新增速率上限**（100‰ ⇒ 每户每计划轮 ≤ ⌊人口×100÷1000⌋） | 收益差支的**可迁人口**；速度 > 100‰ 时夹住（cur < 0 反号支）；`speed ≥ 1000‰` 的整户迁移不再可能（除 A 规则） |
| N3 | **U1：劳动 0 的户重回"并入目标注册表"** | 迁入目标从"新建户"改回"并入既有户"⇒ 迁移笔的 `target`/`targetHex`/新建户数/随迁资产与商品的落点改变；`MIGRATION_IN_KIND_LEG_UNAVAILABLE_REFUSED` 计数下降（那些候选不再落到新建支） |
| N4 | **U5：规模 = 0 的户档位 3 ⇒ 0** | 极小户（`laborMilli ∈ [1, laborPerUnit−1]`）的落点与新建户 `stratum` 不再被抬到最高档；`tierIndex` 读数改变 |
| N5 | **U3：`MIGRATION_MOVE` 多一栏 `targetTierIndex`** | **只影响日志输出**（TRACE），不影响状态 |
| N6 | `plan()` 早退时多一行轮级 DEBUG（`sourceCandidates` 为空） | 只影响日志输出 |

---

## 6. 硬编码字面量（本批新增/变动的）

| 字面量 | 落点 | 含义 |
|---|---|---|
| `MIGRATION_SPEED_CAP_PER_MILLE = 100L` | `ModeMigrationPolicy.java:162` | 转业速率上限（‰/计划轮）；政策预设、**列 W6 标定首查项**；GM 写口见 §9 提案 |
| `max(0L, profitScaleBasisScaled)` / `Math.max(0L, …)` | `profitGapSpeedPerMille:2036-2040` | 分母三项都夹成非负（`absOrMax` 对 `Long.MIN_VALUE` 饱和，`profitScaleBasisScaled` 对负值给 0） |
| `1000L`（复用 `A_RULE_TRANSFER_SPEED_PER_MILLE`） | `moveablePopulation:2117` | "可迁人口"的分母，不新造第二个 1000 |
| `1L`（至少 1 人地板） | `moveablePopulation:2121` | 逐值等于 W9 的 J3 口径 |
| `PER_LABOR_SCALE`（既有 `1_000_000L`） | `ExpectedProfitBook.perLaborScaled:1332` | 与 `netPerLaborScaled` 同一把尺（唯一拼写点） |

---

## 7. 会让既有测试失效的清单（按类名，**只报不改**；红点已**实测**）

| 类 | 红点数（实测） | 失效原因 |
|---|---|---|
| `io.mosire.simos.economy.time.ModeMigrationPolicyW9Test` | **10 条里 4 红** | ① `profitGapSpeedMatchesTheFrozenPerValueTable`（`相对差 0.1 ⇒ 10‰` 断言，实测 **9**）；② `profitGapSpeedBoundariesAreExact`（`1000L` 断言，实测 **90**）；③ `profitGapSpeedMatchesAnIndependentRestatementAcrossTheWholeRange`（`current=-5000000 other=50000000` ⇒ 实测 **110**）；④ `theBottomTierNeverRequiresAnyInventory`（**如实钉住 U5 缺陷**的两条断言 `tier(…,0L,0L)==3` / `tier(…,999L,0L)==3` ⇒ 实测 **0**）。★ 另 6 条仍绿（含 `profitGapSpeedIsMonotoneInTheGap`、`profitGapSpeedTakesExactlyTheTwoProfitReadings` —— 两参签名仍在 ⇒ 反射哨兵仍有效） |
| `io.mosire.simos.economy.time.ModeMigrationPolicyD024Test` | **6 条里 2 红** | ① `hasReadingFalseButPositiveExpectedCandidateYieldsPlan:473`（速度断言 99 ⇒ 实测 **49**，夹具 cur=44,500 / target=88,600 ⇒ 新分母 = 88,600）；② `planAndExecutionKeepSourceModeStandingOrganizationAndUnitModeKey:785`（可迁人口 9 ⇒ 实测 **4**）。两条都是**测试侧独立复述算式没有跟着改分母**（不是 main 的错） |
| `io.mosire.simos.app.time.EconomyCycleHealthTest`（simos-app） | **待测试批实测** | 它是"零成交轮次 = 0"这条硬牙的判据；本批**未跑**（不打包）。修好后应转绿；若仍红 ⇒ 根因不止 D4，需另查 |
| 未受影响（已核，`grep` 逐个确认不调 `plan` / 不引用算式） | — | `ModeMigrationInventoryCarryW9Test`、`MerchantOnDemandTradeW7Test`（只用 `FIXTURE_PLAN_SPEED_PER_MILLE` 手工计划；仅注释提到算式）、`ModeMigrationGovServiceFailClosedTest`（同上）、`ModeMigrationPolicyClaimedAssetsTest`、`ExpectedProfitBookTest`、`ExpectedProfitBookEmployerOfTest`、`HomeModeMigrationProbeTest`（自足玩具模型） |

★ **本批没有删/改任何测试**；上表的红点是"判据随口径变更"，**不许**用重基线洗掉（尤其 `theBottomTierNeverRequiresAnyInventory` 里那两条"如实钉住缺陷"的断言 —— 它们现在红，正是 U5 修复的**判据**）。
★ **给测试批的一条具体指令**：`ModeMigrationPolicyD024Test` 的 H2 夹具劳动（`classes.put(BUYER2, row(BUYER2, H2, 100L, 1_000L, …))`，W9d 为绕开 U1 才改成 1,000）**现在可以改回 0** —— 它同时是 U1 的回归用例（改回后计划应仍非空，且该笔应落在 `existing != null` 的并入支）。

---

## 8. 与 v3.32 不一致处及原因

| # | 项 | 处置 |
|---|---|---|
| D-a | v3.32 建议 `denominator = max(|收益_当前|, |收益_其他|, 规模基准)` | **照做**。★ 后果如实记：`收益_当前 ≥ 0` 时速度**天然 ≤ SLOPE = 100‰** ⇒ 上限在那一支结构冗余（只对 `cur < 0` / GM 调高斜率吃紧）。两条闸同点不是缺陷，是"上限 = 卫生"的字面结果。★★ **给测试批/控制方的读数提示**：真正把"0 收益户 1000‰ ⇒ 整户迁空"按死的是**分母修正**（1000‰ ⇒ 100/10/1‰）；上限主要在 `cur < 0` 反号支吃紧。**若 tp 世界仍踩"零成交轮次 = 0"，下一个该动的是 `PROFIT_GAP_SPEED_SLOPE_PER_MILLE`（100）或候选/权重面，而不是把上限值继续下调**（下调只会让迁移更慢，不改候选集） |
| D-b | 上限是否也夹速度读数？ | **不夹**（任务书只要求"可转人口 ≤ …"）。理由：`transferSpeedPerMille` 仍报算式输出 ⇒ 判据"差 > 0 有速度 / 差越大越快"逐值可读；人口闸独立可读。→ 若控制方要"读数也夹到 ≤ 上限"，改动 = `planForSource` 里对 `bestSpeedPerMille` 取 min + `Target.speedPerMille` 同步 |
| D-c | "每**市场轮**"的频率口径 | 本仓转业只在**关账钩子**（30/120 天）跑 ⇒ 上限按**每次计划轮**落 = 更强的形式（更保守），不会放过任何市场轮 |
| D-d | `moveablePopulation` 的乘法溢出 | 溢出 ⇒ 返回全户人口（fail-closed，不静默回绕）；W9 旧写法无此保护 |
| D-e | 保留两参 `profitGapSpeedPerMille`（基准 = 0） | 唯一实现是三参版；两参只是"没有规模基准的调用点"的薄封装。**为什么保留**：删签名 ⇒ `ModeMigrationPolicyW9Test` **编译不过** ⇒ economy 测试模块整体编不过 ⇒ 本批连单条 `-Dtest` 都跑不了（自证能力归零），且测试批必须先修编译才能跑任何东西 |
| D-f | 规模基准取 `max(毛收入, 口粮/劳动成本)` | 控制方授权"回代码找一个有意义、可解释的正量"；两者都是 `Prospect` 现成读数、与收益差同尺。**未采纳**的替代：`PER_LABOR_SCALE/1000`（W9 账本 D4 的建议）—— 那是拍的数、与本生产方式无关；`MIN_NET_PER_LABOR_SCALED = 1`（同尺但恒为 1，等于没改） |

### ② 的 GM 写口：**具名提案（本批未实现，落常量）**

* **宿主结论**：**三个既有载体都不合适** ——
  `MarketZone.priceAlphaPerMille`（GM 写口 `economy.SetMarketZonePriceAlpha`）是**逐区**的，而转业跨区、无区界；
  `Government.mintScalePerCycle`（GM 写口 `economy.SetGovernmentMintScale`）是**逐辖区**的，世界可有多个政府 ⇒ "读哪一个"是新歧义；
  `EconomyMeta` 是**激活元信息**（mapId / activatedDay / rulesVersion），不是行为预设。
  ⇒ **新增世界级政策组件**是唯一不引入新歧义的路。
* **提案（建议与 W6 标定同批，因为值本身也要标定）**：`EconomyData` 第 32 个组件 `economyPolicies`（值类型 `EconomyPolicy`：
  `migrationSpeedCapPerMille` 默认 100、`profitGapSpeedSlopePerMillle` 默认 100）；`EconomyChangeSet` 第 32 个组件 + `EconomyCodec`
  组件 + **往返**（铁律 5 三件套）；GM-only 命令 `economy.SetEconomyPolicy`（形制照 `economy.SetGovernmentMintScale`：与现值相同 ⇒
  具名拒 `policy-unchanged`）；`ModeMigrationPolicy` 从 `base` 读，**缺组件 ⇒ 用本批常量**（缺省语义中性，§一.11）。
* **为什么本批不做**：动持久形状 + 新 GM 命令 + SPI 注册，而**往返测试本批不能写**（§一.5：写码代理不写测试）⇒ 无法"按铁律 5 做全"；任务书明许"宿主不合适/成本过大 ⇒ 只报设计 + 落常量"。

---

## 9. 未完成 / 未验证项（BLOCKED 与否）

1. **真世界读数全部未取**（判据 1 的"零成交轮次 = 0"、Σfills、min/中位/max）—— **不是 BLOCKED，是批次分工**：本批只允许 `spotless:apply` + `-DskipTests compile -am`（+ 单条 `-Dtest`），**不许** `package`（既越权、也会覆盖运行中的 jar）。⇒ 交测试批：`tools/mvn-lock.sh -q -DskipTests package` + `run-worlds.sh` 同口径 0→240/0→360。
2. **B2 分段等价 / Revision / 性能 / W4 滑落 / W5 / W5.5 / W7 / W8**：未跑（同上）。静态面已核：本批只碰 3 个 main 文件，未触碰那些路径。
3. **真世界的 `targetTierIndex` 读数**：字段已进事件表，但**未在真实 TRACE 日志里看过一行**（需打包跑世界）。
4. **GM 可调写口**：只报设计（§8 提案），本批落常量 ⇒ 与用户"凡行为一律做成 GM 可调的预设"这条既有裁定**仍有缺口**（与 W9d 上报的 `PROFIT_GAP_SPEED_SLOPE_PER_MILLE` 同族，两个旋钮应在同一批一起进政策组件）。
5. **上限值 100‰ 未标定**（列 W6 首查项）。
6. **`E: /tmp` 下的两件自证装置不是仓内产物**（`/tmp/w9fix/`：纯函数探针 + D024 夹具副本；后者为验证 U1 把 H2 劳动改回 0）—— 如实记：它们**不进仓、不会被 CI 重跑**，正式判据要由测试批落成仓内用例。
7. **未 commit**（派单明令）。

---

# W9-fix2（尺度唯一拼写点 + 异常栈）

> 批次：**W9-fix2**（2026-10-11）。派单：修 `three-powers` 真世界 0→360 在 day=270 的 fail-closed 崩溃（HTTP 500）+ `GUI_REQUEST_FAILED` 的可观测性缺口。
> 授权写面：`simos-economy/src/main/**`、`simos-economy-api/src/main/**`、`simos-app/src/main/**`；**未 commit**；**未跑** `test` / `verify`（本批唯一例外 = 打包 + 跑那一条真世界）。
> 最终字节 jar md5 = **`ce0bf3ba8f388492b0f0ab8ae079c22c`**；真世界 `three-powers 0→360` = **HTTP 200 / 48.932 s / day=360**（§5）。
> 本批产物目录：`.superpowers/sdd/2026-10-11-w9-fix2/`（`logs/`、`evidence/`、`dumps/`、`run-tp360.sh`、`run-tp360-final.sh`、`plan-tp-360.json`）。

## 0. 一句话结论 + ★ 对派单前提的一处**代码更正**

1. ✅ **两把尺合成一个拼写点**：`ModeMigrationPolicy.requiredWorkCapitalScale(industry, laborMilli)`（`ModeMigrationPolicy.java:1844`）现在是「本笔需要多少作业资本」的**唯一算式**，计划期预留（`reserveIdleAssetsFor`）与执行期索取（`ModeMigrationSettlement.createEnterpriseAndProcess`）**共用它**；P0 语义（执行期读的是**投影工作行**、不是落盘行）只在那一个 javadoc 里写一次。
2. ✅ **真世界 0→360 通过**：HTTP 200、48.932 s、day=360、`Δrev=1`、日志异常 0 行（§5）。
3. ✅ **守卫未被放宽**（源码对照 §3.4）；改的是**预留这一侧**。
4. ✅ **可观测性**：`GUI_REQUEST_FAILED`（WARN）现在**自带异常栈**（§4）。
5. ★★★ **须更正的派单前提（回代码核，§四）**：派单说"U1 把落点从新建户改回并入既有户（既有 labor 非 0）⇒ 执行侧 scale 由 1 跳到 8"——**与代码不符**：
   * `createEnterpriseAndProcess`（`ModeMigrationSettlement.java:1118`，即 :1190/:1201 抛点的宿主）**只在 `newTarget == true` 时被调用**（`:499` 的 `if (newTarget && !DISPLACED…)`）；
   * **并入既有户那一支根本不建企业、不索取任何作业资本**，计划期对应地也**不预留**（`placeInto` 的 existing 支 `:1621-1634` 没有 `reserveIdleAssetsFor`）⇒ **并入支上两把尺本来就是一致的（都 = 0）**；
   * 真正的根因是**新建户支的 P0 读法错误**（见 §3.1）。崩点那一笔恰恰是**新建户**（守卫消息自称"新建目标户"）。
6. ★ **U-D 有了正面证据**（§9.4）：新建户支**可达**（改前的 W9e 世界在 day=270 真的计划了一笔新建户），本批 0→360 里 0 次触发是因为**那一笔的作业资本不够、在计划期按新尺度被拒** —— 不是"过修把新建支堵死"。

## 1. 改动文件清单（本批，3 个 main 文件）

| 文件 | 改动 |
|---|---|
| `simos-economy/src/main/java/io/mosire/simos/economy/time/ModeMigrationPolicy.java` | ① 新增**唯一拼写点** `requiredWorkCapitalScale`（:1844）+ 计划期劳动口径 `laborCarriedByMoveUpperBound`（:1864）；② `reserveIdleAssetsFor` 改调它（:1882）；③ `perMoveNewRoom` 的按需 trade 支把预留劳动从 `laborPerUnit`（⇒ scale 1）改成 `laborEstimate`（:1719）；④ 把那条**写错的 P0 注释**（"执行期实际只要求 scale = 1"）改写成正确读法并入拼写点 |
| `simos-economy/src/main/java/io/mosire/simos/economy/time/ModeMigrationSettlement.java` | `createEnterpriseAndProcess` 的 inline scale 算式改调同一个拼写点（:1166-1168）；抛点消息追加 `tried=[候选:scale,need]`（:1201-1210，**条件一字未改**） |
| `simos-app/src/main/java/io/mosire/simos/app/gui/GuiServer.java` | `GUI_REQUEST_FAILED` 用 SLF4J `warn(String, Throwable)` 发**同一事件**（:632-658），删除重复的 DEBUG 事件 `GUI_REQUEST_FAILED_STACK`；响应体不变 |

★ 未碰：`simos-economy-api`（本批不需要动）、任何 `src/test/**`、`docs/**`、`AGENTS.md`、其他模块。`git commit` 未做。

## 2. 编译真命令 + rc

```bash
tools/mvn-lock.sh -q spotless:apply                 # rc=0（只格式化本批 3 个文件；git status 前后一致）
tools/mvn-lock.sh -DskipTests compile -am           # rc=0 / BUILD SUCCESS / 16 模块全 SUCCESS / 9.0 s
tools/mvn-lock.sh -DskipTests package -pl simos-app -am   # rc=0 / BUILD SUCCESS / [frontend-gate] OK tests=412 pass=412 fail=0
# 最终 jar md5 = ce0bf3ba8f388492b0f0ab8ae079c22c（21:26 打包）
```
★ 第一次打包（21:20，jar `90e9f85abd4a620562926752fc4b1b8f`）与本轮之间只差一处**逐值等价**的改写（`ceilDiv` → 商/余数形态，防 Long 溢出），两轮真世界的 **dump md5 相同**（§5.3）。

## 3. ① 两把尺合成一个拼写点

### 3.1 现状（改前，file:line）→ 根因

* **计划期**（`ModeMigrationPolicy.java:1704-1712`，改前原文）：注释与代码**明写**"`createEnterpriseAndProcess` 对新建目标户算 `scale = max(1, 目标行.laborMilli / laborPerUnit)`，而 **P0 起新建行的 labor 是 0**（人口/劳动由 App 按 outbox 回写）⇒ 执行期实际只要求 `scale = 1`"，于是按需 trade 支传 `onDemand.recipe().laborPerUnit()`（⇒ scale = 1）预留。
* **执行期**（`ModeMigrationSettlement.java:1159-1164`，改前原文）：`scale = max(1, targetHouseholdEconomy.laborMilli() / laborPerUnit)`。
* ★★★ **真凶 = 上面那句 P0 读法是错的**：执行期传进来的 `targetHouseholdEconomy` 是 `apply` 在 `:508-509` 构造的 **`withPopulationAndLabor(plannedPopulation.get(target), plannedLabor.get(target))` 投影工作行**，而新建户的 `plannedLabor` 在 `:435-436` 从 0 起步、`:468` 加上本笔迁入劳动 ⇒ **执行期读到的是「本笔迁入劳动」，不是落盘行的 0**（落盘行确实是 0，由 App 按 outbox 回写 —— 那是**另一件事**）。逐字读落盘行只发生在计划期的这句注释里。
* 崩点数字自洽：day=270 那笔 `laborTake = 8857`、trade 模板 `laborPerUnit = 1000` ⇒ 执行期 scale = **8**（需 8 × `capacityPerUnit[TOOL]=120` = **960 毫工具**），计划期按 scale = 1 只预留了 **120** ⇒ 8 倍缺口 ⇒ `planAssetMoves` 全候选返回 null ⇒ 守卫抛 ⇒ HTTP 500。
* ★ **为什么这笔在 day=270 才可达**（具名，未验证的部分也写明）：该笔是**按需 `trade@2_0`**（hex `2_0` 本格无 merchant 模板）。执行期先在 `:200-234` 按需创建 `trade@2_0`（`MERCHANT_TRADE_INDUSTRY_CREATED`=1，与 W9e 读数对上），再 `industriesForMode(工作副本)` 才看得见它 ⇒ 走 scale = 8 那一支。★ 派单把成因归给"U1 改了落点"——**本批未回跑 W9 首版，故该因果未验证**；已确认的是"崩点落在**新建户 × 按需 trade**支"。

### 3.2 改法（唯一拼写点在哪）

**唯一拼写点 = `ModeMigrationPolicy.requiredWorkCapitalScale(Industry, long laborMilli)`（`ModeMigrationPolicy.java:1844-1848`）**：

```java
static long requiredWorkCapitalScale(Industry industry, long laborMilli) {
  long laborPerUnit = industry.recipe().laborPerUnit();
  return laborPerUnit <= 0L ? 1L : Math.max(1L, laborMilli / laborPerUnit);
}
```
* 计划期：`reserveIdleAssetsFor`（`:1882`）= `long scale = requiredWorkCapitalScale(industry, laborMilli);`（改前是同一式子的 inline 副本）。
* 执行期：`ModeMigrationSettlement.createEnterpriseAndProcess`（`:1166-1168`）= `ModeMigrationPolicy.requiredWorkCapitalScale(candidateIndustry, targetHouseholdEconomy.laborMilli())`（改前是 inline 副本）。
* **P0 语义只写在拼写点的 javadoc 里**（`:1820-1841`）：`laborMilli` **必须**是执行期会读到的那一行 —— 对新建户 = **带本笔随迁投影的工作行视图**（`0 + 本笔迁入劳动`）；落盘行恒 0（App 按 outbox 回写）、逐字读它会得到 scale = 1（= 改前的错读法）。
* **口径的另一半也有唯一拼写点**：`laborCarriedByMoveUpperBound(sourceLabor, sourcePopulation, take)`（`:1864-1874`）= `⌈劳动 × 迁入人口 ÷ 源户人口⌉`（源户人口 ≤ 0 ⇒ `take`，与改前同）；计划期预留用它当 labor 入参。

### 3.3 两种落点的尺度算式（**回代码核过的实情**）

| 落点 | 执行期是否索取作业资本 | 执行期尺度 | 计划期预留 | 两把尺是否一致 |
|---|---|---|---|---|
| **并入既有户**（`newTarget == false`，`:396-416`） | **否** —— `:499` 的 `if (newTarget && …)` 不成立，`createEnterpriseAndProcess` 不被调用 | 无（不建企业） | `placeInto` existing 支（`:1621-1634`）**不预留** | ✅ 一致（两侧都 = 0）。★ 若将来在执行期为并入户补建企业，尺度同样走 `requiredWorkCapitalScale(模板, 该户当日 labor)` |
| **新建户**（`newTarget == true`） | 是（先按需建产业，再 `planAssetMoves` 拆闲置份额） | `requiredWorkCapitalScale(模板, 投影行 labor = 本笔迁入劳动)` | `requiredWorkCapitalScale(模板, laborCarriedByMoveUpperBound(源户))` | ✅ 同式、同量（在"劳动随人口成比例"这条既有假设下逐值相同） |

### 3.4 守卫**没有**被放宽（源码对照）

* 守卫条件逐字未变：`if (industryId == null || industry == null)`（改后 `:1201`；= 遍历完全部候选都没预留成功）。
* 索取算式逐字未变：`planAssetMoves` 的 `need = ⌈scale × capacityPerUnit[每维]⌉`，任一维 `need > 0` ⇒ `return null`（本批**未碰**该方法）。
* 本批在该方法里只改两处：scale 改调共享拼写点；抛出消息追加 `tried=[候选:scale,need]`（**只加信息、不改判定**）。证据：`evidence/02-guard-unchanged.txt`（改前/改后原文并排）。

### 3.5 改后该笔去哪了（可读的"拒绝"，不是"崩"）

* 计划期唯一拼写点算出的 need = 960 毫工具；可用 = 本笔随迁携带（**< 960**，由改前那次抛出本身证明：`planAssetMoves` 在"承载覆盖 < 960"时才会走到 `need > 0`）+ hex `2_0` 同格闲置工具（day=360 dump 读数 = **7 毫**）⇒ `reserveIdleAssetsFor` 返回 false ⇒ `perMoveNewRoom` 返回 0 ⇒ **该目标不进计划**。
* 真世界读数印证：全窗 `MERCHANT_TRADE_INDUSTRY_CREATED = 0`、`ONDEMAND_* = 0`、`trade@2_0` **不在** day=360 dump 的产业表里、day=270 的 `targetHex=2,0 targetMode=merchant` 笔数 = **0**（改前 = 1）。

## 4. ② 可观测性：`GUI_REQUEST_FAILED` 并入异常栈

* **改法**（`GuiServer.java:632-658`）：
  ```java
  if (AppLog.gui().isWarnEnabled()) {
    AppLog.gui().warn(LogEvent.of("GUI_REQUEST_FAILED", AppLogSource.GUI_REQUEST, …).toLine(), e);
  }
  ```
  = SLF4J `warn(String, Throwable)`：**同一事件、同一行首，栈紧随其后**；级别仍 **WARN**；删除重复的 DEBUG 事件 `GUI_REQUEST_FAILED_STACK`（改前它在 `-Dsimos.app.logLevel=INFO` 下**一次都不发射** —— 这正是 W9e 归因成本高的原因）；响应体**不变**（`safeError(exchange, 500, "internal error")` ⇒ `{"error":"internal error"}`）。
* **改前**（W9e 现场，`evidence/03-gui-failed-stack.txt`）：
  `21:07:59.615 WARN io.mosire.simos.app.gui - event=GUI_REQUEST_FAILED … error=IllegalStateException message=新建目标户的租赁资产不足（…）: hex=2_0 labor=8857` —— **此后再无任何行**。
* **改后**（同一份 shaded jar 的 `log4j2.xml`、同一 logger 名、`-Dsimos.app.logLevel=INFO`，渲染探针实测）：
  ```
  21:24:04.287 WARN  io.mosire.simos.app.gui - event=GUI_REQUEST_FAILED method=POST path=/api/advance caller=127.0.0.1 error=IllegalStateException message=新建目标户的租赁资产不足（计划不该包含此目标）: hex=2_0 labor=8857
  java.lang.IllegalStateException: probe: 栈是否随 WARN 事件打出
  	at LogProbe.main(LogProbe.java:18)
  	…
  ```
  ⇒ 默认 INFO 下 WARN 事件行后**直接跟完整栈**（log4j2 `PatternLayout` 的 `alwaysWriteExceptions` 默认 true；本仓 `log4j2.xml` 的 pattern 未含 `%throwable`，靠该默认逐字生效 —— **探针实测**，非推演）。
* ★ **如实说**：本批**没有**拿到真 500 的栈现场 —— 修好之后这条世界上唯一的 500 消失了；我又用同一 jar 起了一次服务（复制 store、端口 10994、`app-level=INFO`）打了 12 发负向请求（未知命令类型 / 未知 region·unit·决策人 id / 未知 branch / 空 TimeRange / 坏载荷 / 未注册维度），状态码全部落在 **400/404/409/422**，**没有一发进到 `catch (Exception)` 那条 500 路径** ⇒ ② 的"改后"证据是**渲染探针**（逐字证明栈会打出来），不是真 500。探针源码 `/tmp/w9f2/probe/LogProbe.java`（**不进仓、CI 不重跑**）。

## 5. 真世界结果（授权范围内：打包 + 跑这一条）

### 5.1 最终字节轮（`jar md5 = ce0bf3ba8f388492b0f0ab8ae079c22c`）

```
bash .superpowers/sdd/2026-10-11-w9-fix2/run-tp360-final.sh      # 同一台尺 /tmp/w6acc/w6run.py，plan = advance 0→360 + dump
[21:26:11] 起服务: run-shaded.sh … --store /tmp/w9f2/stores/tp360b --world=three-powers --gui-port 10991 --mcp-port 10992 --approval-port 10993
[21:26:13] GUI 就绪（1.74s）   创世后 head rev=1 tick=0
[21:27:02] advance 0->360 HTTP 200 48.932s
[21:27:02] dump d360-oneshot rev=2 bytes=12371339 md5=838477e41aaf5b9db732d12a424c7605
```
| 判据 | 结果 |
|---|---|
| HTTP 状态 | **200**（改前 = 500 @ day=270） |
| 跑到 day | **360**（`tick=360`、`headRevision=2`、`Δrev=1`） |
| 耗时 | **48.932 s**（≈136 ms/天；W9 jar 同世界 0→360 = 29.87 s ⇒ 仍偏慢，与 W9e 的 U-E 同族，非本批引入） |
| 异常 / 栈 / `GUI_REQUEST_FAILED` | **0 行** |
| PID / 收工 | 服务进程由装置自行 SIGTERM 退出（`pgrep -af shaded.jar` 空）；**未用 `pkill -f`** |

### 5.2 第一轮（`jar md5 = 90e9f85abd4a620562926752fc4b1b8f`，`ceilDiv` 版）

HTTP **200** / **47.794 s** / day=360 / `Δrev=1` / dump md5 **同上**。读数与最终轮逐值相同 ⇒ 那处改写数值等价。

### 5.3 前后对照（迁移笔数 / 落点，**一行 before、一行 after**）

| | 窗口 | 迁移笔数 | 落点（`MIGRATION_APPLIED`） | tier 分布 | 市场轮 | 按需 trade | 异常 |
|---|---|---|---|---|---|---|---|
| **before**（W9e 账本 §1/§4.3/§4.7） | tp **0→240** | **233** | **8/8 轮 `rowsBefore == rowsAfter == 342`** | 0×217,1×9,2×7 | 96 | `MERCHANT_TRADE_INDUSTRY_CREATED = 1`（0→270 窗口，就是肇事那条） | 500 @ day=270 |
| **after**（本批，最终 jar） | tp **0→240**（同窗口） | **233** | **8/8 轮 342 == 342** | 0×217,1×9,2×7 | 96 | **0** | **0** |
| **after**（本批，最终 jar） | tp **0→360**（全窗） | **389** | **12/12 轮 342 == 342** | 0×349,1×33,2×7 | 144 | **0** | **0** |

★ 0→240 窗口**逐值相同** ⇒ 本批的改动对这个世界的既有轨迹**没有任何扰动**（`targetMode=merchant` 笔数 0→240 = 16、0→360 = 120 一并记下作基线）。全窗新增量（240→360 = 156 笔）是 W9e 拿不到的那一段。
★ 证据：`evidence/01-tp360-readings.txt`、`evidence/04-tp360-final-confirmation.txt`、`logs/tp360fix2b.log`（3.0 MB / 7626 行，留档）、`logs/tp360fix2b.result.json`、`dumps/tp360fix2b-d360-oneshot.json`。

### 5.4 第二处"计划期假设 vs 执行期读法"对偶（**点名，只报不改**）

* **计划期** `carriedCarrierAssetsForMove`（`ModeMigrationPolicy.java:1765-1797`）= "该户**全部**自有、模板作业资本维、quantity > 0 的份额按人口比例（整户迁空 ⇒ 全部）"。
* **执行期** `migrateAssetsForMove`（`ModeMigrationSettlement.java:1719-1806`）在同一个比例式之上**还有三道排除**：`foreignUsedShareIds`（已租给外组织实际使用的份额**留原户**）、跨 hex 的 `isMobileAsset`、跨 hex 的 `hostIndustryFor(...).isEmpty()`（无宿主产业 ⇒ 不搬）⇒ **实际随迁覆盖 ≤ 计划期估计**。
* 后果：`reserveIdleAssetsFor` 的 `available = carried + idle` 会把"搬不动的那部分"也算成可用 ⇒ 计划期**少预留**闲置份额 ⇒ 同一守卫（`:1201`）在极端情形仍可能被撞。**这是同一类缺陷**（"计划期假设 vs 执行期读法"）。
* ★ 本批**只报不改**：修它要动"哪些份额能随迁"的判据面（`claimedByEnterprise`/`foreignUsed`/宿主产业三处对齐），属另一个责任区；按派单"按风险定"，本批选择**最小 fail-closed 改动**（只统一尺度这一把尺）。
* ★ 另注（同族，轻）：`reserveIdleAssetsFor` 的 `reservedIdle` 台账按 `IndustryId` 记，而计划期是**每源户顺序**预留、执行期是**每源户顺序**兑现（`:235-269` 按 source 排序且保计划内序）⇒ 两条序一致；但执行期会被 `filterOnDemandMerchantMoves`（`:1892`）**丢掉若干笔** ⇒ 后续笔的"运行中剩余劳动/人口"与计划期不同。本批已把计划期取整改成**向上**（取较大者）吸收这一点（`laborCarriedByMoveUpperBound` 的类注具名）。

## 6. 会改变数值行为的清单（相对 W9e 工作树）

1. ★★★ **按需 trade 支的预留规模**：`laborPerUnit`（scale 恒 1）→ `laborCarriedByMoveUpperBound(...)`（= 执行期会读到的迁入劳动）⇒ **该支的目标变严**。本世界后果：day=270 那笔不再发生（全窗 `MERCHANT_TRADE_INDUSTRY_CREATED` 1 → **0**）。**这是本批要的 fail-closed 方向**。
2. **计划期劳动估计 floor → ceil**：`laborEstimate` 由 `⌊L×take÷P⌋` 改成 `⌈L×take÷P⌉`。只在 `⌊…⌋ ≡ (laborPerUnit − 1) (mod laborPerUnit)` 时把预留的**规模**抬 1 个单位（≈0.1% 的笔），否则**逐值不变**（含 scale 的下限 1）。本世界 0→240 无差异（§5.3 逐值相同）。
3. **异常消息文本**：守卫消息追加 ` tried=[候选:scale,need]`（判定逻辑不变）。
4. **日志**：`GUI_REQUEST_FAILED`（WARN）现在带 throwable ⇒ 该行后**多出栈行**；删除 DEBUG 事件 `GUI_REQUEST_FAILED_STACK`（默认 INFO 下本来就 0 行 ⇒ 正常轮无差异）。
5. **未改**：任何标定值（`laborPerUnit` / `capacityPerUnit` / 速度表 / 门槛 / 分子分母）、任何状态形状、任何 codec/ChangeSet、任何 GM 工具载荷。

## 7. 硬编码字面量（本批新增/变动的）

* **无标定值**新增或改动。
* 新增**结构性**字面量两处（非可调参数）：`Math.addExact(quotient, 1L)`（向上取整的 +1）、`triedCandidates.length() > 0`（空判的 0）。
* ★ 借机会点名：本批引入的这条口径（"需要多少作业资本"= 劳动反解规模）**没有 GM 旋钮** —— 与用户"凡行为一律做成 GM 可调的预设"这条既有裁定的缺口**没有扩大但也没有收窄**（`laborPerUnit` 仍是产业模板里的常量）。

## 8. 会让既有测试失效的清单（按类名，**只报不改**；本批**未跑** `test`）

★ 下面全部是**按代码面推断**的候选，**不是实测红点**（本批授权不含 `test`）：

| 类 | 为什么可能失效 |
|---|---|
| `io.mosire.simos.economy.time.MerchantOnDemandTradeW7Test` | 最可能的红点：它的夹具走**按需 trade** 通道，预留规模由 1 变 `⌈labor/laborPerUnit⌉` ⇒ 原来"携带 ≥120 毫工具即通过"的夹具，现在需要 `scale × 120`（同理 `ONDEMAND_TRADE_MIGRATION_TOOL_PURCHASE` 的放行条件随之变严） |
| `io.mosire.simos.economy.time.ModeMigrationPolicyW9Test` | 逐值表 / U5 档位 / 人口闸的夹具若落在"按需 trade"或"`⌊…⌋ ≡ 999 (mod laborPerUnit)`"边界上，计划会少一笔 |
| `io.mosire.simos.economy.time.ModeMigrationPolicyD024Test` | H2 夹具（劳动 0 的并入户）与 `hasReadingFalseButPositiveExpectedCandidateYieldsPlan` 一类"预期有候选"的断言，可能因预留变严而变空计划 |
| `io.mosire.simos.economy.time.ModeMigrationInventoryCarryW9Test` | 携带量守恒用例若同时断言"该笔被计划"（预留通过），会随门槛变严而红 |
| `io.mosire.simos.economy.time.ModeMigrationGovServiceFailClosedTest` | 迁移 fail-closed 一族；若夹具以"按需 trade 能成"为前提，前提不再成立 |
| `io.mosire.simos.app.time.EconomyCycleHealthTest` | 世界级分布指纹（96 轮 / Σfills / min·中位·max，`small-world`）：边界带（第 2 条）若在 sw 的 222 笔里命中一笔，指纹就会漂 —— **本批未跑 sw，无法判定** |

★ 交付测试批的建议：先跑这 6 个类拿实测红点；红点若落在上述两条口径（按需 trade 门槛 / `≡999 (mod laborPerUnit)` 边界），**按新契约重钉期望值**，不要为迁就实现改口径。

## 9. 与 v3.33 不一致处及原因（含 U-D）

1. **无**与设计书 v3.33 §7 的正面冲突：本批只动"预留尺度"这一处内部口径，"带库存 / 按库存定档 / 工具可购 / 收益差驱动速度"四条一律未动。
2. ★ **与派单书的一处机制描述不一致**（§0.5）：派单的"并入既有户 ⇒ 执行侧 scale 由 1 跳到 8"与代码不符（并入支不建企业、不索取）；真凶是**新建户支**的 P0 读法。**已按代码实情实现**，并在此具名记偏离。
3. ★ **"语义有歧义 ⇒ 按较大者预留"的落点**（派单裁定，已实现）：两种读法 = P0 字面（labor = 0 ⇒ scale 1）与随迁劳动（labor = 本笔迁入劳动 ⇒ scale N）。实现 = ① 唯一拼写点的 `max(1, …)` 把"字面 1"兜成**下限**；② 计划期的劳动估计取 `⌈…⌉`（取较大者）。**代价具名**：极端情形会多预留 1 个单位规模。
4. ★ **U-D（新建户分支 0 次触发：正确还是过修）—— 本批给了正面证据，判为"正确"**：改前的 W9e 世界在 day=270 **确实**计划并执行到"新建目标户"那一支（守卫消息 `新建目标户… labor=8857` 就是它），⇒ **新建支没有被 U1 堵死、也不是过修**；本批 0→360 里 0 次触发，是因为**那一笔的作业资本（< 960 毫工具 + hex 闲置 7 毫）不够，被计划期按新尺度正确地拒了**（拒绝路径可读：该日 `targetHex=2,0 targetMode=merchant` 笔数 0）。★ 残留缺口：本批**没有**逐轮打印"新建户数"（日志没有该栏），"0 次"是从 `rowsBefore == rowsAfter` 反推的（与 W9e §7.10 同一条缺口）。
5. ★ **U-E（tp 每天偏慢）仍在**：本批 136 ms/天（W9e 141 ms/天、W9 首版 83 ms/天）—— 本批未处理，只记读数。

## 10. 未完成 / 未验证项（BLOCKED 与否）

1. **既有测试一条没跑**（派单明令；本批例外只有"打包 + 跑那一条世界"）⇒ §8 的失效清单是**推断，不是实测**。**不是 BLOCKED，是批次分工**。
2. **`small-world` 未重跑**：`EconomyCycleHealthTest` 的世界级指纹是否随本批漂移，**未验证**（这要另跑一条世界，超出本批授权）。
3. **day=240 dump 的对拍没做**：W9e 的 `tp240-d240-oneshot.json`（md5 `58d1d5d9…`）与本批同窗口 dump **未逐字节对比**（本批只在读数层做到"逐值相同"）。
4. **② 的真 500 栈现场没拿到**（§4 末条）：证据是**渲染探针**（同一 jar 的 log4j2.xml + 同一 logger + 默认 INFO 实测打印），不是真 500。
5. **派单说的"U1 ⇒ 落点改变"因果未验证**（§3.1 末条）：未回跑 W9 首版 jar。
6. **§5.4 的第二处对偶未修**（只报）：计划期 carried 估计 ⊇ 执行期实际覆盖 ⇒ 仍可能少预留闲置。
7. **"新建户计数"仍未进轮级日志**：U-D 的判定靠 `rowsBefore == rowsAfter` 反推（建议下一批给 `MIGRATION_APPLIED` 加一栏 `newHouseholds=`，本批**只报不改**）。
8. **`clean verify` / spotbugs / checkstyle 未跑**（派单明令）。
9. **未 commit**（派单明令）。
10. **磁盘**：第一轮 1.1 GB + 1.07 GB 两份日志已删（保留 md5 `939805d8b7b1008cbf6fbeabd1b46ca9` 与读数抽取）；最终轮 3.0 MB 日志留档。

**BLOCKED 判定：无**（判据"advance 0→360 = HTTP 200、无异常、day=360"已通过）。

## 11. 偏离记录（主动、具名）

| # | 偏离 | 原因 |
|---|---|---|
| D1 | 派单说"并入既有户 ⇒ 执行侧 scale 8"，实现按**新建户支**改 | 回代码核（§0.5/§3.1）：并入支不建企业；改错地方会既不修崩溃又白改 |
| D2 | 计划期劳动估计改**向上取整**（floor → ceil） | 派单"语义有歧义 ⇒ 按较大者预留"；代价 ≤ 1 毫劳动，只在规模边界下 1 毫内多预留 1 个单位 |
| D3 | 新增了一处**消息增强**（`tried=[…]`）与一处**防溢出改写**（`ceilDiv` → 商/余数） | 前者把本次事故的诊断成本从"两次重跑 + keep 过滤器"降到一行；后者避免在极端值上静默给错（都不改判定、不改数值语义） |
| D4 | 删除了 DEBUG 事件 `GUI_REQUEST_FAILED_STACK` | 派单说"栈并入该事件"；保留它就是同一栈打两遍（默认 INFO 下它本来就 0 行） |
| D5 | §5.4 的第二处对偶**只报不改** | 修它要动"哪些份额能随迁"的判据面；本批按最小 fail-closed 改动收口 |

## 12. 落点速查

```
.superpowers/sdd/2026-10-11-w9-fix2/
├── run-tp360.sh / run-tp360-final.sh     ← 真世界装置（10991/10992/10993；store /tmp/w9f2/stores/{tp360,tp360b}）
├── plan-tp-360.json                      ← advance 0→360 + dump（与 W9e 同形）
├── logs/ 01-tp360.out 02-tp360-final.out tp360fix2.result.json
│         tp360fix2b.log（3.0 MB，最终轮留档）tp360fix2b.result.json tp360fix2b.log.stripped.md5
│         （第一轮 1.1 GB + 1.07 GB 日志已删，md5 = 939805d8b7b1008cbf6fbeabd1b46ca9）
├── dumps/ tp360fix2-d360-oneshot.json  tp360fix2b-d360-oneshot.json（md5 都是 838477e41aaf5b9db732d12a424c7605）
└── evidence/
    ├── 01-tp360-readings.txt              ← 第一轮八类读数抽取
    ├── 02-guard-unchanged.txt             ← 守卫改前/改后源码对照（未放宽）
    ├── 03-gui-failed-stack.txt            ← ② 改前/改后（含渲染探针原文 + 12 发负向请求状态码）
    └── 04-tp360-final-confirmation.txt    ← 最终轮判据 + 与第一轮/dump md5 逐值相同
源码落点：ModeMigrationPolicy.java:1844（requiredWorkCapitalScale）、:1864（laborCarriedByMoveUpperBound）、
          :1719（按需 trade 支传 laborEstimate）、:1882（预留调用）；
          ModeMigrationSettlement.java:1166（执行期调用同一拼写点）、:1201（守卫 + tried=…）；
          GuiServer.java:632-658（WARN 事件自带 throwable）。
```
