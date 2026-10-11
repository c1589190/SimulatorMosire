# W9e 测试批（2026-10-11）：硬牙验证 + 旧口径迁移 + 指纹重基线 + 真读数（sw 主 / tp 对照）

> 批次：**W9e 测试批**。写码批账本：`.superpowers/sdd/2026-10-11-w9-mode-switch/impl-ledger.md`（W9 + W9-fix 节）。
> 授权写入面：`simos-economy/src/test/**`、`simos-app/src/test/**`（**main 一字未改**；未 commit；未跑 `clean verify`）。
> 本轮实跑 jar md5：**`c29ba84c7499c5e990c66ce6fc36d73c`**（`tools/mvn-lock.sh -DskipTests package -pl simos-app -am`，2026-10-11 21:04，前端门禁 `[frontend-gate] OK tests=412 pass=412 fail=0`）。
> 统计窗口：真世界读数一律逐条标注（sw **0→360**；tp 因 main 侧缺陷只能到 **0→240**，见 §9；装置 A 的 0→240 见 §1）。
> 磁盘：跑前 45 G 可用；跑后 43 G（清掉了 842 MB + 1.1 GB + 1.1 GB 三份大日志与两份 `.log.stripped`）。

---

## 0. 一句话结论

1. ★★ **① 硬牙「零成交轮次 = 0」= 恢复（绿）**：three-powers 0→240 = **96 轮 / 零成交 0 / Σfills 3,337 / min·中位·max = 15·35.5·193**，且**两套独立装置逐值相同**（进程内 JUnit + 真 HTTP 独立 store）⇒ 按派单 §1 进入 ②③（重基线）。
2. **② 迁移完成**：4 条逐值/边界/复述按**新分母**重算 + U5 语义改写 + D024 的 2 条（新分母 / 人口闸）+ **H2 夹具劳动改回 0**（U1 回归）。全仓 16 模块 **3,464 条测试 0 红**（app 909 条含 5 skip）。
3. **③ 指纹重基线完成**：`BASELINE_FILLS` 按实跑真值重钉（旧 24 档 → 新 24 档），**五条牙一字未动**，重跑绿。
4. **④ 八类读数**：sw 全跑完；tp **0→360 跑不了**（main 侧缺陷，§9 U-A），改出 **tp 0→240**。★ W9d 的肇事签名（1000‰ / 单笔 199 人）在 sw 与 tp **都为零**。
5. **⑤ 变异自证**：5 处测试侧变异**全部当场红、且红在被守的那一行**（含指纹那一条），还原后 md5 逐值相同。
6. ★ **上报一条 main 侧 blocker**：W9-fix jar 让 three-powers **0→360 在 day=270 抛 `IllegalStateException`（HTTP 500）**，确定性复现，抛出点 `ModeMigrationSettlement.java:1190`，具名不一致 = 计划期按 `scale=1` 预留 vs 执行期按 `scale=8`（`labor=8857`）索取。**只报不修。**

---

## 1. ① ★★ 硬牙验证（最高优先）

### 1.1 判定：**绿 —— 「零成交轮次 = 0」恢复**（⇒ 进 ②③）

装置 A（本测试自身，进程内真 Shell + 真 `advanceAndTime`，three-powers 0→240 分 8 段）：
```bash
tools/mvn-lock.sh test -pl simos-app -am -Dtest='EconomyCycleHealthTest' -Dsurefire.failIfNoSpecifiedTests=false
# 第一轮（迁移前）：Tests run: 1, Failures: 1 —— 红在 :589（**分布指纹**）
#   ★ 它前面的四条牙（轮数 :558 → 同日不两轮 :562 → 间隔≤5 :569 → **零成交轮次=0 :574**）**全部通过**
# 迁移后：Tests run: 1, Failures: 0（见 §3）
```
★ 这条判定**不是**"失败了就猜"：断言次序在源码里是"轮数 → 同日不两轮 → 间隔 → 零成交 → 分布 → min/中位/max → Σfills==MARKET_FILL"，而失败行是**分布**（`:589`），⇒ 前面四条**必须**已经通过。

### 1.2 四个数（并排）+ 独立重算

| 读数 | W7e（旧基线） | W9 首版（踩牙） | **W9-fix 装置 A（0→240）** | **W9-fix 装置 B（真 HTTP 0→240）** |
|---|---|---|---|---|
| 轮数 | 96 | 96 | **96** | **96** |
| **零成交轮次** | **0** | **21** | **0** ✅ | **0** ✅ |
| Σfills | 3,651 | 2,027 | **3,337** | **3,337** |
| min · 中位 · max | 15·42.0·198 | 0·24.0·120 | **15·35.5·193** | **15·35.5·193** |

* **独立重算（不经测试代码）**：从 842 MB 原始日志里抽 96 行 `MARKET_REPORT` ⇒ 轮数 96 / 零成交 0（`[]`）/ Σfills 3,337 / min·中位·max = 15·35.5·193 / **同日两轮 = 否** / **相邻最大间隔 = 3 天**（≤5）/ **Σfills 3,337 == `MARKET_FILL` 行数 3,337**（独立计数）。
  证据：`evidence/devA-market-report-raw.txt`（96 行）+ `evidence/devA2-summary-after-rebaseline.txt`（测试自己写的读数行）。
* **装置 B（真世界 HTTP，独立 store，从创世新播）**：shaded jar `c29ba84c…`、`--world=three-powers`、端口 10982、store `/tmp/w9e/stores/tp360`、`POST /api/advance 0→360`（该轮在 day=270 崩了，但 **day 0–240 的 96 轮已落日志**）⇒ 抽出同一窗口 day 0–240 的 96 轮 ⇒ **与装置 A 逐值相同**（含 24 档分布）。证据：`evidence/tp360-deviceB-fingerprint-0-240.txt`、`evidence/tp360-market-report-raw.txt`。
* ⇒ **两套独立装置（进程内 vs 真 HTTP + 独立 store）给出同一份指纹** ⇒ 不是产物陈旧的假象（AGENTS §三.5）。

### 1.3 为什么恢复了（与写码批的读数提示一致）

W9d 的肇事链 = "`收益_当前 ≈ 0` ⇒ 分母退化成 1 ⇒ 1000‰ ⇒ 单笔搬 199 人 ⇒ 一轮搬空 ⇒ 买卖双方同时消失"。本轮真世界里这条链**两个世界都为零**：
* **速度 = 1000‰ 的迁移笔数 = 0**（sw 222 笔里 0 笔；tp 0→240 的 233 笔里 0 笔；W9d 的 tp 是 **62/93 笔**）；
* **单笔最大搬迁人口 = 18（sw）/ 10（tp 0→240）**（W9d：**199 人**）；
* 速度上界：sw max 101‰、tp max 100‰（W9d：1000‰）。
⇒ **分母修正确实是把肇事链按死的那一件**（与写码批 §8 D-a 的读数提示一致）。

---

## 2. ② 迁移（旧口径 → 新口径，逐条具名；**没有放宽语义**）

### 2.1 实跑红点清单（以实跑为准）

* **完整红点 = 3 个类**（不是写码批报的"2 个类 + app 待测"以外的任何东西）：`ModeMigrationPolicyW9Test`（10 跑 4 红）、`ModeMigrationPolicyD024Test`（6 跑 2 红）、`simos-app` 的 `EconomyCycleHealthTest`（1 跑 1 红，= 硬牙那条分布断言）。
  **判别依据**：迁移后全仓 16 模块 0 红（§2.3）⇒ 未被我编辑的文件行为不变 ⇒ 迁移前的红点集必 ⊆ 我编辑的这 3 个类。
* `tools/mvn-lock.sh test-compile -am` **无编译红**（W9d 修掉的 4 处 `MIGRATION_PER_MILLE` 已不存在；`grep -rn MIGRATION_PER_MILLE simos-*/src` 只剩注释与 `HomeModeMigrationProbeTest` 的自足夹具常量）。

### 2.2 逐条迁移

| # | 类:断言 | 旧 | 新 | 依据 |
|---|---|---|---|---|
| M1 | `W9Test.profitGapSpeedMatchesTheFrozenPerValueTable` | `(1e6,1.1e6) ⇒ 10`、`(1e6,2e6) ⇒ 100`、`(1e6,11e6) ⇒ 1000`、`(0,1e6) ⇒ 1000`（分母 `max(1,\|当前\|)`） | `⇒ 9 / 50 / 90 / 100`（分母 `max(\|当前\|,\|其他\|)`），**每行的 `as(...)` 里写了手算算式**（如 `⌊100×100,000 ÷ 1,100,000⌋ = 9`） | W9-fix §3①；用户③「差越大越快」 |
| M2 | `W9Test.profitGapSpeedBoundariesAreExact` | 「相对差 ≥ 10 ⇒ 打满 1000‰；少一毫 ⇒ 999」 | **换成新结构的真边界**：同号支上界 = SLOPE（`(0,1e6) ⇒ 100`；`(1,1,000,001) ⇒ 99` 打不到 100）、反号支上界 = 2×SLOPE（`(-1e6,1e6) ⇒ 200`，少一毫 ⇒ **199**）；地板那一毫保留（差 1 毫 ⇒ 1‰） | 同上（旧边界在新分母下**不可达**，留着就是装饰） |
| M3 | `W9Test.expectedSpeed`（独立复述） | `max(1,\|当前\|)` | `max(\|当前\|,\|其他\|)`（注释具名"两参薄封装 ⇒ 基准 = 0"） | §三.2 判别力（复述不得与实现一起漂） |
| M4 | `W9Test.theBottomTierNeverRequiresAnyInventory` | 钉住 U5 缺陷：`tier(labor=0,0) == 3`、`!= 0`、`tier(999,0) == 3` | **按 U5 修正后的语义**：`labor ∈ {0,1,998,999}`（规模 = 0）× `carried ∈ {0,2_000,7_000,200_000}` ⇒ **全部档 0**；并加**反面对照** `tier(1_000,200_000) == 3`（证明不是"恒 0"的假绿） | W9-fix §3④；用户②「为啥要拦着」 |
| M5 | `D024Test.expectedProfitGapSpeed`（独立复述） | `max(1,\|当前\|)` | `max(\|当前\|,\|target\|)`（不写死任何速度值） | 同 M3 |
| M6 | `D024Test:473` 与 `:785` 的人口断言 | `max(1, ⌊100 × 速度 ÷ 1000⌋)` | **新增 `expectedMoveablePopulation(人口, 速度)`**：`min(速度折算, 上限折算)`，上限折算 = `max(1, ⌊人口×100÷1000⌋)`；两处都改用它 | W9-fix §3②（人口闸）；用户③ |
| M7 | `D024Test` **H2 夹具** | `row(BUYER2, H2, 100L, **1_000L**, …)`（W9d 为绕开 U1 才改） | **改回 `0L`**（与 HEAD 第 183 行逐值相同），并加**U1 回归断言**：`plan.moves()` **非空** ∧ `anyMatch(move.target() == BUYER2)`（落在**并入支**） | 写码批 §7 的具体指令；U1 修复 = §3③ |

★ **没有删除任何一条判据**；M2 是"旧边界在新口径下不可达"所以换成新口径的真边界（并保留地板那条），M7 是把夹具恢复成 HEAD 的形状。

### 2.3 实跑结果（全仓）

```
tools/mvn-lock.sh test -pl simos-app -am          # logs/02-fulltest.log（857 MB，已删；汇总留 evidence/02-fulltest-summary.txt）
[INFO] BUILD SUCCESS    Total time: 03:43 min    RC=0
逐模块：Util 217 / Map 379 / Calendar 33 / SocialApi 3 / ActorApi 8 / EconomyApi 50 / Social 217 /
        Unit 518 / Core 231 / SD 232 / Actor 130 / **Economy 335** / Gov 111 / Army 91 / **App 909(5 skip)** = 3,464 条，0 红
被迁移三类：ModeMigrationPolicyW9Test 12 条绿 / ModeMigrationPolicyD024Test 6 条绿 / EconomyCycleHealthTest 1 条绿
```
★ 与 W9d 的对照：economy 侧 333 → **335**（本轮 +2 条新用例，见 §5）；app 侧唯一红点（`EconomyCycleHealthTest`）转绿。

---

## 3. ③ 指纹重基线（仅在 ① 绿时做；已做）

`simos-app/src/test/java/io/mosire/simos/app/time/EconomyCycleHealthTest.java`：

| 项 | 旧（W7e） | **新（W9e 实跑真值）** | 依据 |
|---|---|---|---|
| 分布指纹 | 24 档（15×1 … 151×1, 198×1） | **24 档**：`15×1, 21×18, 22×8, 23×8, 24×8, 25×1, 27×1, 35×3, 36×14, 37×3, 38×3, 40×2, 41×5, 42×8, 43×1, 44×1, 45×2, 46×3, 47×1, 48×1, 49×1, 66×1, 167×1, 193×1` | W9 ① 速度改收益差驱动（主因）+ W9-fix ③ 分母修正（把零成交轮 21 → 0）+ ④ 人口闸 + ⑤ U1/U5 改落点 |
| min / 中位 / max | 15 / 42.0 / 198 | **15 / 35.5 / 193** | 同上 |
| Σfills（交叉验证） | 3,651 | **3,337**（−8.6%）= `MARKET_FILL` 3,337 行 ✅ | 同上 |

* **五条牙一字未动**（`git diff` 可核）：`BASELINE_MARKET_ROUNDS = 96`、同日不得两轮、相邻间隔 ≤ 5 天、**零成交轮次 = 0**、`Σ逐轮 fills == MARKET_FILL 行数` —— `assertEveryMarketRoundTraded` 的**断言体一个字符都没改**，只换了期望值（分布 + min/中位/max）。
* 类注释里补了 **W9e 段**：旧值 → 新值 + 逐条依据 + "W9 首版曾踩牙 21 轮、当时拒绝重基线"的留痕 + 两套装置读数 + 证据路径。
* **重跑绿**（§2.3 的 App 909 条即含它）。

---

## 4. ④ 真读数（八类；窗口逐条标注）

装置：`/tmp/w6acc/w6run.py`（与 W7e/W9d 同一台尺：起服务 → `POST /api/advance` → 抽 dump）；日志 `--log-level DEBUG --trace-level TRACE`，keep 白名单**比 W9d 更紧**（排除了 `CAPACITY_DEMAND_INSTANCE` / `HOUSEHOLD_GAP` 两个逐户洪泛事件 ⇒ tp 日志 1.6 GB → 1.1 GB）；端口 10981-10988；store `/tmp/w9e/stores/*`；脚本 `run-worlds.sh`。

| 世界 | 窗口 | 结果 |
|---|---|---|
| sw360 | **0→360** | HTTP 200（8.226 s）/ rev Δ=1 / dump md5 `89bbecfa…` |
| tp360 | 0→360 | ★ **HTTP 500（day=270）** —— 见 §9 U-A；窗口内 day 0–240 的 96 轮用于 §1 装置 B |
| tp240 | **0→240** | HTTP 200（33.942 s）/ rev Δ=1 / dump md5 `58d1d5d9…` |
| sw3seg / swtick | 0→360 | Δ=3 / Δ=360；三形态规范化 md5 见 §4.8 |

### 4.1 速度 = 收益差驱动（含负向）

* **sw 0→360：222 笔，`reason=PROFIT_WEIGHTED` 222/222**；速度分布 `10×2, 23, 25×6, 26, 29×4, 30×4, 33×4, 38×2, 39×13, 43×21, 44×4, 45×51, 64, 66, 67×18, 68×61, 90×2, 99×10, 100×15, 101`（min/中位/max = 10/45.0/101；**1000‰ = 0 笔**）。
* **tp 0→240：233 笔，`PROFIT_WEIGHTED` 233/233**；速度 `5,7,12,22×7,25×3,29×11,30×12,43×4,44×2,63×34,64×3,66×52,67×34,70,72,89×2,93×4,94,95×2,97,98,99×17,100×36`（min/中位/max = 5/66/**100**；**1000‰ = 0 笔**）。
* **"差越大越快"**：速度是**逐笔不同值**（不是整户一个常量），且逐目标 —— 与算式一致；单调性由 `W9Test.profitGapSpeedIsMonotoneInTheGap` 逐值守。
* **负向（债高但 gap ≤ 0 ⇒ 0 笔）**：
  * **单测**（逐值、可执行）：`D024Test.w9HighDebtDoesNotMigrateWhenTheProfitGapIsNonPositive` —— 50 亿毫债务 + gap ≤ 0 ⇒ 该源户 **0 笔**；正对照加同一笔巨债后计划逐值不变（债务不是输入）。
  * **真世界**：**A 规则与债务口径的迁移 = 0 笔**（`reason=PROFIT_WEIGHTED` **222/222（sw）、233/233（tp）**；`MIGRATION_DEBT_*` 事件存在但只做债务随迁/抵消，不产生速度）。
  * ★ 如实说：真世界**读不到**"某个高债户的 gap"，只能证明"没有任何一笔由债务/资产口径产生"；逐值那一条是单测给的。

### 4.2 人口闸生效（逐笔"可迁人口 vs 速度折算"）

* **单测逐值表（10 行，两侧都算出来不写死）**：`W9Test.theMigrationSpeedCapClampsTheMoveablePopulationPerPlanRound` —— 人口 200/速度 200‰ ⇒ 速度折算 40、上限折算 20 ⇒ **20**；200/1000‰ ⇒ **20**（W9 首版是 200 = 整户）；1000/1000‰ ⇒ **100**；5/200‰ 与 7/1000‰ ⇒ 地板 1。
* **真世界逐笔样本（day=360，sw；源户人口 pre = dump post + 当日迁出总和 —— dump 与 move 是同一 revision 的两侧）**：9 笔 / 6 个源户，**全部满足** `Σ迁出 ≤ min(速度折算, 上限折算)`：
  ```
  hh-0_0-urban-middle_peasant  pre=174 Σmoved=16  速度 99‰  ⇒ min(17,17)=17  OK
  hh-0_0-urban-rich_peasant    pre=101 Σmoved=10  速度 100‰ ⇒ min(10,10)=10  OK
  hh-0_0-rural-middle_peasant  pre=38  Σmoved=3   速度 45/100‰ ⇒ min(3,3)=3 OK
  （逐笔 9 行与两切片人口一致性校验见 evidence/sw360-population-gate-day360.txt）
  ```
* ★ **如实说两条**：① 真世界**只有 day=360 这一天**能做这种逐笔判读（每个迁移日都需要当日 checkpoint；本批没跑"逐日 + keep-store"，见 §7）；② sw 0→360 里速度最大 101‰、只有 1 笔 > 100‰且人口 = 1 ⇒ **上限在 sw 里几乎不吃紧**（与写码批 §8 D-a 的提示一致：上限主要在 `收益_当前 < 0` 的反号支吃紧）。**上限真正吃紧的样本是单测那张表**（200 人 @ 200‰ ⇒ 20）。

### 4.3 U1 修复（劳动 0 的并入户能否再被并入）

* **单测（U1 回归）**：`D024Test` 的 H2 夹具劳动改回 **0**（= HEAD 形状）后，`plan.moves()` **非空** ∧ 该笔 `target == BUYER2`（**并入支**）；若把两张表合回去，这条会当场红。
* **真世界（sw 0→360 与 tp 0→240 都成立）**：`MIGRATION_APPLIED` 逐轮 **`rowsBefore == rowsAfter`（sw 12/12 轮：178 == 178；tp 8/8 轮：342 == 342）** ⇒ **迁移全部并入既有户、新建户行 0 个**；且 W9d 的具名拒绝 `MIGRATION_IN_KIND_LEG_UNAVAILABLE_REFUSED` **0 行**（W9d 的 sw/tp 里有，正是 U1 那个"并入不了 ⇒ 落到新建支 ⇒ 撞实物腿闸"的症状）。
* ★ **一条待控制方确认的读数（不是缺陷判定）**：自 W9-fix 起真世界里"新建户"分支 **0 次触发**（12/12 与 8/8 轮行数不变），而 W9 首版（W9d）是**有**新建户的。这可能是 U1"恢复 W9 前形状"的正确结果，也可能是**过修**（把新建支也堵住了）。判据缺口：本批没有"W9 之前 tp 的 rowsBefore/rowsAfter"读数可对照 —— 见 §7。

### 4.4 U5 修复（极小户落档 0）

* **单测逐值**：规模 = 0（劳动 0/1/998/999）× 携带 0/2,000/7,000/200,000 ⇒ **全部档 0**（改前 = 档 3）；反面对照 `labor=1_000, carried=200_000 ⇒ 档 3`。
* **真世界**：`targetTierIndex` 分布 sw = **0×211, 1×11**；tp 0→240 = **0×217, 1×9, 2×7**。
* ★ **如实说**：真世界**没有**逐户的"该户劳动 < 1 个规模单位"读数（那要读源户 `laborMilli`，日志面没有），所以"极小户落档 0"在真世界**只是分布层面的旁证**（档 0 占 95%+、且档 2/3 基本不出现），**逐值判据是单测**。

### 4.5 档位可读（U3：`MIGRATION_MOVE.targetTierIndex`）

真日志样本（sw 360 日，TRACE 通道）：
```
event=MIGRATION_MOVE day=360 source=hh-0_0-urban-middle_peasant target=hh-0_0-urban-landlord
  targetHex=0,0 targetMode=merchant population=15 moneyMilli=21 moneyByCurrency={silver=21}
  debtMilli=4 speedPerMille=99 targetTierIndex=1 reason=PROFIT_WEIGHTED
```
⇒ U3 修复在真世界**可读**（W9d 的读数缺口已关闭）：sw 222 笔全部带栏（0×211, 1×11），无缺失。

### 4.6 带库存随迁守恒 + 壳户噪声

| 读数 | W7e（旧） | W9d（W9 首版） | **W9e（W9-fix）** |
|---|---|---|---|
| 逐户 `no-labor` / `no-tool-input` 行（`MODE_MIGRATION_PROSPECT_INFEASIBLE`） | sw 8,844 / tp 17,610 | 0 / 0 | **0 / 0**（sw/tp 都是 0 行）✅ |
| 轮级 `MODE_MIGRATION_CANDIDATES` | （旧版无） | 12 / 12 | **12（sw）/ 8（tp 0→240）**，带 `skippedShellNoPopulation` + `skippedShellNoLabor` |
| Σ shell 计数 | — | — | sw：ΣnoPopulation **876**、ΣnoLabor **943**（只计数不刷屏） |
| `MIGRATION_INVENTORY_CARRIED` | — | 91/93（= 每次迁移） | **222/222（sw）、233/233（tp）** ⇒ 每笔迁移都带库存 |
| 携带物里出现 `haul` | — | 0 | **0 笔**（D2 排除生效）✅ |
| 携带物里带 `grain` | — | — | **156 / 222**（sw）⇒ 粮随人走可读 |
| 逐笔样本 | — | — | `day=30 hh-0_0-urban-rich_peasant -> hh-0_2-urban-middle_peasant pop=4 goods={grain=282222, fiber=1122, iron=888, tool=683}` |

★ 守恒式的正确读数层：商品**总量**在世界上本来就不守恒（每 tick 生产/消费）⇒"源减 = 目标增、全局合计不变"只能在**转移层**读，由 `ModeMigrationInventoryCarryW9Test`（3 条）逐值钉住（W9d 已建，本批复核 3 条全绿）。

### 4.7 跑商与运粮

| 读数 | W7e | W9d | **W9e（sw 0→360）** |
|---|---|---|---|
| `MERCHANT_TRADE_INDUSTRY_CREATED` | 0 | 0 | **0** |
| `ONDEMAND_TRADE_MIGRATION_TOOL_PURCHASE` | （新事件） | 0 | **0** |
| `ONDEMAND_TRADE_MIGRATION_SKIPPED` | 0 | 0 | **0** |
| `MIGRATION_MOVE targetMode=merchant` | — | 13 | **16**（sw）⇒ 全是**并入既有 trade 产业**，不是"非城市格长出商号" |
| `no-carrier-with-deliverable-service`（`MERCHANT_CAPACITY_LANE_TRUNCATED`） | 13,551 笔 / 61,879,715 毫（W7e **自己的证据文件**；★ 派单给的 11,777 / 61,441,260 与之对不上，两者并列，见 §9 U-B） | 13,230 笔 / 116,786,642 毫 | **7,525 笔 / 58,888,186 毫**（笔 −43% vs W9d、毫 −50%） |
| `out-of-derived-radius`（LANE_TRUNCATED 口径） | 2,672 笔 / 3,319,569 毫 | 6,227 笔（另一口径） | **4,074 笔 / 5,476,991 毫**；另有 `+sub-unit-residual` 1,946 笔 |
| `CAPACITY_DEMAND_GAP_WHY` | — | 6,227 笔 / 65,639,528 毫（理由名 `out-of-derived-radius`） | **297 笔 / 67,084,135 毫**（理由名 = **`capacity-budget-below-demand-or-out-of-derived-radius`** —— F2 合并后的唯一拼写点；**口径不同，不可与 W9d 逐值比**） |
| `HAUL_*` | — | — | `HAUL_SERVICE_DELIVERED` 438、`PAID` 319、`SETTLED` 144、`HAUL_PRICE_SIGNAL` 144、`EXPIRED` 2 |
| 未服务运力量 | — | — | 见上两行（sw 的未服务**金额降了 ~50% vs W9d**，但理由口径变过，只作方向性读数） |

★ **结论（如实）**：① 跑商新通道（缺工具 ⇒ 买/租）在**真世界里仍然 0 次触发**（没有按需商号 ⇒ 那道闸门没被进入；**不是"买不到"**——买不到会有具名跳过行）；② 运力缺口的**笔数**明显下降，金额也下降，但**理由名/口径与 W9d 不同**，只能算"没有变坏"；③ 与 W7e 的对照要用 **W7e 自己的证据文件数**（13,551 / 61,879,715），派单里的 11,777 / 61,441,260 与本批读数**并列保留**，不替控制方选。
★ ★ **tp 世界的跑商读数只到 day 240**（`MERCHANT_TRADE_INDUSTRY_CREATED` 在 tp 0→270 里出现过 **1 条**，与 §9 U-A 的肇事 hex `2_0` 对得上 —— 这是 W9-fix 后 tp **新长出按需商号**的第一个真样本，也是 main 缺陷的现场）。

### 4.8 不回归

| 项 | 结果 | 证据 |
|---|---|---|
| **B2 分段等价**（一次 / 3 段 / 逐 tick） | ✅ **三形态规范化 md5 逐值相同**：`5ec07665c26d37e4b3f04b9213102d77` ×3；`canon2.py diff` **差异叶 0 / 总叶 81,703**（两次对比都是 0） | `dumps/sw{360,3seg,tick}-d360-*.json`、`logs/0{4,6}*.out` |
| **Revision Δ** | ✅ 一次 `advance(0→360)` = **Δ1**（rev 1→2）；3 段 = **Δ3**；逐 tick = **Δ360** | `logs/sw*.result.json` |
| **性能（单次墙钟）** | sw 0→360 = **8.226 s**（W9d 7.70 s，+7%）；sw 3 段 = 3.744+3.092+2.892 s；sw 逐 tick = ~35 s（≈0.097 s/天）；tp 0→240 = **33.942 s**（≈141 ms/天；W9d 的 tp 0→360 = 29.87 s ≈ 83 ms/天 ⇒ **tp 每天慢 ~70%**，且本批 keep 白名单更紧、日志更小 ⇒ 不是日志 I/O 造成的） | `logs/04-worlds.out`、`logs/06-tp240.out` |
| **W4 阶层滑落（债务驱动）** | ✅ `CLASS_SLIDE_ROUND/SETTLED` 144/144、`CLASS_SLIDE` 44、`CLASS_TRANSITIONS` 1(+DETAIL 12)；`SettlementOrderBook`/`ClassSlideSettlement` 未被 W9/W9-fix 改动（`git diff` 无它们） | `evidence/sw360-extract.txt` |
| **W5 铸币** | ✅ `MINT_SETTLED` 3、`MINT_LIMIT_WINDOW` 9、`MINT_LAST_CYCLE_READOUT` 1（three-powers 无铸币产业 ⇒ 货币理应不变，装置 A 的逐币种守恒 8/8 段逐值成立） | 同上 + `devA2-summary` |
| **W5.5 货币市场** | ✅ `MONEY_MARKET_ROUND/WHY/UNFILLED/RATE_WRITTEN/GOV_SUPPLY_SUPPRESSED/BUDGET_TOPUP` 各 144 | 同上 |
| **W7 运力/运费** | ✅ `HAUL_PRICE_SIGNAL` 144、`HAUL_SERVICE_DELIVERED` 438、`MERCHANT_CAPACITY_*` 一族齐全；运力读数见 §4.7 | 同上 |
| **W8 债务语义** | ✅ `DEBT_REPAY_*`/`DEBT_STRESS_*` 一族齐全（`DEBT_REPAY_DECISION` 3,786、`DEBT_STRESS_THRESHOLD_FIRED` 872、`MIGRATION_DEBT_MOVED` 118、`MIGRATION_DEBT_SELF_NETTED` 41）；全仓 3,464 条 0 红 | 同上 + §2.3 |

---

## 5. ⑤ 新用例与判据出处

| 类 | 新用例（本轮 +2） | 判据出处 |
|---|---|---|
| `ModeMigrationPolicyW9Test` | `theProfitScaleBasisKeepsTheZeroProfitDenominatorFromSaturating`（**三参形态**：基准 = 1,000,000 时 `(当前 0, 其他 1) ⇒ 1‰`、`(0, 1e6) ⇒ 100‰`、`(0, 1e7) ⇒ 100‰`；反号支 200‰；`|当前| ≥ 基准` 时与两参逐值相同） | W9-fix §3① + 写码批 §4 判据 1（"未跑真世界"那条的**单测化**）；用户③ |
| `ModeMigrationPolicyW9Test` | `theMigrationSpeedCapClampsTheMoveablePopulationPerPlanRound`（10 行逐值 + 两闸分工 + 边界） | W9-fix §3②；用户③ |

★ 其余新用例（`ModeMigrationInventoryCarryW9Test` 3 条、`ModeMigrationPolicyD024Test` 的 `w9HighDebtDoesNotMigrateWhenTheProfitGapIsNonPositive`、`MerchantOnDemandTradeW7Test` 的 3 条）是 W9d 建的，本批复核**全绿**（未改语义）。
★ **判据出处汇总**：用户原话 ①②③④（设计书 §7 v3.29–v3.32）+ 控制方裁定 v3.32 §7 → 写码批 §3/§4 → 本批 §2/§3。

---

## 6. ⑥ 变异自证（5 处测试侧变异 ⇒ 全部当场红 ⇒ 还原 ⇒ md5 逐值相同）

| # | 变异 | 期望红点 | 实测 |
|---|---|---|---|
| M1 | `EconomyCycleHealthTest` 指纹档 `36×14 ⇒ 36×13` | 分布断言 | ✅ 红在 `assertEveryMarketRoundTraded:638`，消息里 `基线=…36×13… 实测=…36×14…`（**红在被守的那一行**） |
| M2 | `W9Test` 逐值表 `⌊100×100,000÷1,100,000⌋ = 9 ⇒ 10` | 逐值表 | ✅ `profitGapSpeedMatchesTheFrozenPerValueTable` FAILURE |
| M3 | `W9Test` 人口闸 `Row(200, 1000‰, 20) ⇒ 200`（W9 的整户读数） | 人口闸表 | ✅ `theMigrationSpeedCapClampsTheMoveablePopulationPerPlanRound` FAILURE |
| M4 | `W9Test` U5 断言 `档 0 ⇒ 档 3`（U5 修复前的缺陷值） | U5 块 | ✅ `theBottomTierNeverRequiresAnyInventory` FAILURE |
| M5 | `D024Test` U1 并入支 `target==BUYER2 ⇒ BUYER1` | U1 回归 | ✅ `hasReadingFalseButPositiveExpectedCandidateYieldsPlan` FAILURE |

* **第一轮（M1–M5 同时变异）**：`ModeMigrationPolicyW9Test` 12 跑 3 红、`ModeMigrationPolicyD024Test` 6 跑 1 红，**Maven 在 economy 模块就 `BUILD FAILURE` ⇒ app 模块的 M1 根本没跑**（"没红要先问为什么没红"）⇒ 把 economy 两个文件**先还原**、只留 M1 单独跑一轮 ⇒ M1 红在指纹行。证据（原文摘要）：`evidence/06-mutation-and-final-green.txt`（三轮的原始日志共 ~1.7 GB 已按磁盘纪律删除，汇总行留档）。
* **还原**：`evidence/00-md5-mutation-baseline.txt` 与 `evidence/00-md5-after-restore.txt` **三对三 md5 逐值相同**；还原后最终轮 = 19 条全绿（`Tests run: 18, Failures: 0` + `Tests run: 1, Failures: 0` + `BUILD SUCCESS`，见同一份 evidence）。
* ★ **做不到的（如实说）**：**没有**对 main 做变异（派单明令 main 禁改）⇒ "把分母改回 `max(1,|当前|)` ⇒ 硬牙必须重新变红"这条**最有价值的变异体本批没做**。它的判别力由 **W9 首版的自然红**替代证明：同一颗牙在 W9 世界实测红 21 轮（21 个零成交轮）、W9-fix 世界绿 —— 这是最强的一种"变异体"（真实世界里的真实缺陷）。

---

## 7. ⑦ 我没做 / 没验证的

1. **tp 世界 0→360 的真读数：拿不到**（main 侧缺陷卡在 day=270，§9 U-A）⇒ 只给了 **tp 0→240**，且 tp 的运力/跑商读数是 0→240 窗口（与 sw 的 0→360 **不同窗口**，逐条已标注）。
2. **逐日 checkpoint 的"全 222 笔人口闸判读"：没做**（要 `advance_daily` + `--keep-store` ⇒ ~360 份 checkpoint ≈ 2 GB，且要逐份解析）。本批只做了 **day=360 的 9 笔**（用 d360 dump 反推源户 pre 人口）。**没有**用"用 d360 近似其它日"这种不严谨做法。
3. **`MIGRATION_MOVE` 里没有"源户人口"这一栏** ⇒ 真世界的"可迁人口 vs 速度折算"必须靠 dump 反推（见上）；若要逐笔直接可读，需要 main 加一栏（本批**只报**，不加）。
4. **U5 的真世界逐户样本：没有**（读不到源户 `laborMilli`）⇒ 只有档位分布旁证 + 单测逐值。
5. **`ONDEMAND_TRADE_MIGRATION_TOOL_PURCHASE` 真样本：没有**（sw 里该通道 0 次触发；tp 的按需商号在 day≤270 只出现 1 条且随后 500）。
6. **"新建户分支 0 次触发"是否预期：未定论**（§4.3 的待确认项）—— 缺"W9 之前 tp 的 rowsBefore/rowsAfter"对照读数；本批**没有**回头跑 W9/HEAD 的 jar（那要重建旧 jar，且旧 jar 与 W9-fix 的 main 不同，属另一批）。
7. **600 天窗口 / 多进程逐字节确定性：没跑**（只跑 0→360 三形态 + 0→240）。
8. **W7e 的运力对照口径**：只并列了 W7e 自己证据文件的数（13,551 / 61,879,715）与派单数（11,777 / 61,441,260），**没有**去 W7e 目录重新逐行重算（见 §9 U-B）。
9. **`clean verify`：没跑**（派单明令，归控制方门禁）；spotbugs/checkstyle 未单独复核（`test` 阶段不含它们，`package` 阶段跑了前端门禁）。
10. **`MIGRATION_APPLIED` 的 `rowsBefore/rowsAfter` 只核了"相等"**，没有核"每轮 move 数与新建户数"的逐轮明细（日志里没有新建户计数栏）。

---

## 8. ⑧ 未完成 / BLOCKED

| 项 | 状态 | 说明 |
|---|---|---|
| ① 硬牙验证 | ✅ **绿** | 两套装置逐值相同；四个数 + 三条读侧自检齐全 |
| ② 迁移 | ✅ 完成 | 7 条具名迁移；全仓 3,464 条 0 红 |
| ③ 指纹重基线 | ✅ 完成 | 只换期望值，五条牙一字未动；重跑绿 |
| ④.1–4.8（sw） | ✅ 完成 | 八类读数齐全；其中 4.2/4.4 的"真世界逐笔"部分按 §7 的缺口如实标注 |
| ④（tp 0→360） | ★ **BLOCKED（main 侧）** | HTTP 500 @ day=270，见 §9 U-A；已给最小复现与具名不一致 |
| ⑤ 变异自证 | ✅ 5/5 红 | main 侧变异按纪律不做（§6 末条如实记） |
| 大盘清理 | ✅ 已做 | 删 842 MB + 1.1 GB + 1.1 GB 三份大日志与两份 `.log.stripped`；原件留 `evidence/` |
| 服务进程 | ✅ 无残留 | 每个世界跑完进程自行退出（`pgrep -af "simos-app|run-shaded"` = 空）；**未用 `pkill -f`** |
| `git commit` | ❌ 未做 | 派单明令 |

---

## 9. 上报（只报不改）

| # | 事项 | 最小复现 / 证据 | 影响 |
|---|---|---|---|
| **U-A** | ★★★ **three-powers 真世界 0→360 在 day=270 抛 `IllegalStateException` ⇒ `POST /api/advance` HTTP 500**（W9-fix jar；W9 jar 在同一装置上是 HTTP 200 / 29.87 s） | 复现命令见 §4 装置 + `evidence/tp500-rootcause.txt`：<br>`error=IllegalStateException message=新建目标户的租赁资产不足（计划不该包含此目标；已按 id 序遍历该 hex 全部产业模板）: hex=2_0 labor=8857`；抛出点 `ModeMigrationSettlement.java:1190`（P10.2 `694a194a` 起就有的 fail-closed 守卫）；两次独立跑都停在 **day=270**；day=270 恰有 1 笔 `targetHex=2,0 targetMode=merchant`、tp 全程 `MERCHANT_TRADE_INDUSTRY_CREATED` = 1 | **具名不一致（两侧都在 main）**：计划期 `ModeMigrationPolicy.java:1700-1710` 明写"新建目标行的 labor 在执行期是 0（P0 由 App 回写）"⇒ 只按 `laborPerUnit`（**scale = 1**）预留作业资本；执行期 `ModeMigrationSettlement.java:1170` 用 `scale = max(1, targetHouseholdEconomy.laborMilli()/laborPerUnit)` = **8**（`labor=8857`、trade 模板 `laborPerUnit=1000`）⇒ 8 倍缺口。★ "是 W9-fix 的哪一件改动让它可达"= **假设未验证**；已确认的是 jar 对照（W9 过、W9-fix 崩）与"装置 A 只跑 240 天 ⇒ 240–360 才是新暴露面" |
| **U-B** | **W7e 基线数字两处对不上**（口径冲突，沿 W9d 的 U4） | 派单：`sw 0:360 = 11,777 笔 / 61,441,260 毫`；W7e 自己的证据文件 `.superpowers/sdd/2026-10-11-w7e-test-batch/evidence/sw360-lane-truncated-by-reason.txt`：**13,551 笔 / 61,879,715 毫** | 影响 §4.7 的"vs W7e"口径；本批**两个都列**，不替控制方选 |
| **U-C** | **`GUI_REQUEST_FAILED` 只打消息、不打异常栈**（`io.mosire.simos.app.gui`，WARN） | `evidence/tp500-rootcause.txt` 那一行只有 `error=` + `message=`；本轮是靠把 keep 过滤器加上 `ERROR\|Exception\|\tat ` 才捞到消息，栈**完全没有** | 真世界 500 的归因成本极高（本批为定位花了 2 次重跑 + 手工 keep 过滤器）；建议后续把栈并入该事件（**只报不改**） |
| **U-D** | **"新建户"分支自 W9-fix 起在真世界 0 次触发**（sw 12/12、tp 8/8 轮 `rowsBefore == rowsAfter`） | §4.3；W9d 的 W9 世界里**有**新建户 | **待确认是"U1 恢复 W9 前形状"的正确结果，还是过修**（把新建支也堵住）；判据缺口 = 缺 W9 之前同世界的 rowsBefore/rowsAfter 对照 |
| **U-E** | **tp 性能每天慢 ~70%**（141 ms/天 vs W9d 83 ms/天），而本批 keep 白名单更紧（日志更小） | `logs/06-tp240.out`（0→240 = 33.942 s）vs W9d `tp 0→360 = 29.87 s` | 只是读数与提醒（不是阻塞项）；与 U-A 可能是同一片代码路径（迁移候选/合并更重） |

---

## 10. 落点速查

```
.superpowers/sdd/2026-10-11-w9e-test-batch/
├── impl-ledger.md                     ← 本文件
├── run-worlds.sh                      ← 真世界装置（sw360/tp360/sw3seg/swtick）
├── extract.py                         ← 八类读数抽取器（窗口可标注）
├── plans/plan-{sw-360,sw-3seg,sw-tick,tp-360,tp-240}.json
├── logs/  03-package.log 04-worlds.out 05-tp360diag.out 06-tp240.out
│          sw360.log(+result) sw3seg.log(+result) swtick.log(+result) tp240.result.json
│          （tp360/tp360diag/tp240 的大日志与全部 .log.stripped、三轮变异/最终 Maven 日志 ~1.7 GB 已按磁盘纪律删除）
├── dumps/ sw360-d360-oneshot.json sw3seg-d360-3seg.json swtick-d360-tick.json tp240-d240-oneshot.json
└── evidence/
    ├── 00-md5-pre.txt / 00-md5-mutation-baseline.txt / 00-md5-after-restore.txt
    ├── devA-market-report-raw.txt（96 行逐轮）/ devA-failure-block.txt / devA-surefire.txt
    ├── devA2-summary-after-rebaseline.txt        ← 装置 A 迁移后的读数行 + 5 组不变量
    ├── tp360-deviceB-fingerprint-0-240.txt / tp360-market-report-raw.txt
    ├── 02-fulltest-summary.txt                   ← 全仓 RC + 逐模块汇总 + 三个被迁移类
    ├── 06-mutation-and-final-green.txt           ← 变异 5 红 + M1 单独红 + 还原后全绿
    ├── sw360-extract.txt / tp240-extract.txt     ← 八类读数
    ├── sw360-population-gate-day360.txt          ← 人口闸逐笔判读
    └── tp500-rootcause.txt                       ← U-A 的异常行 + 现场
```
