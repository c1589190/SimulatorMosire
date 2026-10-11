# W7 测试批账本 —— 取真读数 + 显式重基线 + 补判据测试（2026-10-11）

- **角色**：W7 测试 Agent（**只改测试**：`simos-app/src/test/**`、`simos-economy/src/test/**`；**未改任何 `src/main/**`**，
  变异自证轮次的临时改动已逐字节还原，见 §4）。
- **被验对象**：HEAD `be963bbb`（W8）+ **未提交的 W7 工作树**（10 个 main 文件改动 + 2 个新 main 文件）。
- **被验 jar**：`simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar`，**md5 `783ec45439f2e50d54b4828a9c54df44`**，
  27,186,753 字节，mtime `2026-10-11 18:48`（`tools/mvn-lock.sh -DskipTests package -pl simos-app -am` rc=0）。
  自证：jar 内含 W7 新类（`MerchantTradeTemplate.class` / `IndustryClassSlots.class`）且 `HaulService.class` 里
  `no-deliverable-haul-service-goods` 在、旧字面量 `no-haul-service-in-shipping-hex` **不在**。
- **判据出处**：设计书 `docs/superpowers/specs/2026-10-10-unified-price-and-ordered-settlement-design.md` **v3.26/v3.27 §7**
  + **§6.2 G1–G7** + **§6.3 判据清单**；**用户原话**「还有，**运力提供不应该也是全市场区共享的吗**」/ 选定「**全国/跨区也能被雇**」/
  「**跑商不是生产方式吗？难到家户不会给预估生产方式预留生产资料吗？**」；W7 派单 ①–⑥。
- **纪律**：不 commit；不跑 `clean verify`（门禁归控制方）；一次一个 Maven；真世界用受管后台作业 + 记 PID；
  端口 **109xx**（未复用 103xx–108xx 与 5711/5817/5857/5867/5715/5757）；**未用 `pkill -f`**（收工按 PID / 由装置 `terminate()`）。

---

## 0. 装置与产物

| 项 | 值 |
|---|---|
| 世界装置 | `/tmp/w6acc/w6run.py`（W6 验收同一份装置：起 shaded jar → `POST /api/advance` → 抽 checkpoint dump；`--log-keep` 只留白名单 event 行） |
| 世界配置 | 运力调查批的 `shell-sw.json`（md5 `7c1a82f8507e27d74287e8d1f558158b`）/ `shell-tp.json`（`8d877ef31c88e149632692d8fe55f767`）—— **与 W4/W5/W6 报告逐字相同**（checkpointInterval=1） |
| 日志级别 | `JAVA_TOOL_OPTIONS=-Dsimos.economy.logLevel=DEBUG -Dsimos.economy.traceLevel=TRACE -Dsimos.social.logLevel=INFO -Dsimos.app.logLevel=INFO`（除了专门标注的 perf 轮） |
| 运行清单 | `sw360`（small-world 一次 0→360 + 逐日 361→689）、`sw3seg`（0→120→240→360）、`swtick`（逐日 0→360）、`tp360`（three-powers 一次 0→360 + 逐日 361→660）、`sw700`（**续跑** 689→700）、`swperf`（0→360、**INFO**） |
| 进程 PID（受管后台作业，逐个记在 `logs/<tag>.result.json` 的 `pid`） | sw360 **402007**、sw3seg **402255**、swtick **402364**、sw700 **406748**、swperf **406815**；★ `tp360` 的 `result.json` **丢失**（见 §6 F4：装置收尾把 4 GB 日志整份读进内存 ⇒ 被 OOM 杀；世界数据完好） |
| 产物 | 本目录：`logs/`（白名单日志 + result.json）、`dumps/`（checkpoint 快照）、`results/`（读数 JSON）、`w7state.py`（状态读数）、`w7log.py`（日志读数）、`run-worlds.sh`、`mutate.sh`、`plans/` |
| 磁盘 | 开工 `df -h /` = **2.0 G 可用（99%）** ⇒ 先清 `/tmp` 里 15:21–16:16 的**陈旧**批次日志 **47.1 GB**（先逐条打印清单核验、全部在 `/tmp` 下且 mtime > 60 min，再删）；收工 **49 G 可用（59%）**。★ `surefire-reports/*.xml` 单轮 **1.1 GB**，跑完即删；tp 的 4.02 GB 白名单日志 **gzip -1 → 0.15 GB** 留档（证据不丢）。本目录合计 **487 MB**。 |
| 收工 | 无 java/python 残留（`pgrep` 空）；**未用 `pkill -f`** |

---

## 1. ① 显式重基线（旧 → 新 + 依据）

### 1.1 `EconomyCycleHealthTest` 逐轮成交分布指纹（本批唯一一条门禁红）

| | 分布指纹（笔数×轮数） | min / 中位 / max | 轮数 | 零成交轮 | Σfills |
|---|---|---|---|---|---|
| **旧（W5.5 基线）** | `15×1, 21×11, 22×8, 23×4, 24×6, 25×5, 26×10, 30×1, 41×1, 43×4, 44×5, 45×24, 46×2, 47×5, 48×1, 49×1, 50×1, 51×1, 56×1, 57×1, 66×1, 92×1, 140×1` | 15 / 43.0 / 140 | 96 | 0 | 3522 |
| **新（W7 真值，已钉）** | `15×1, 21×11, 22×8, 23×4, 24×6, 25×5, 26×10, 30×1, 41×1, 42×3, 43×2, 44×10, 45×8, 46×5, 47×10, 48×3, 49×1, 50×1, 53×1, 55×1, 56×1, 66×1, 151×1, 198×1` | **15 / 42.0 / 198** | 96 | **0** | **3648** |

★ 逐档差异（旧→新）：消失 `51×1 / 57×1 / 92×1 / 140×1`；新增 `42×3 / 53×1 / 55×1 / 151×1 / 198×1`；
计数变化 `43: 4→2`、`44: 5→10`、`45: 24→8`、`46: 2→5`、`47: 5→10`、`48: 1→3`；Σfills **+126**。

**依据（W7 的 ①②③④ 各处改动，逐条具名）**

1. **① 运力池全市场区共享**（用户原话 + v3.26 选定「全国/跨区也能被雇」）：候选集从"发货格的池"→**全池**，行程闸 =
   `d(自家格,发货格) + d(发货格,收货格) ≤ 派生半径` ⇒ **可成交集变了**（发货格没有承运方的 lane 从"根本不建"变成"可被接单"）。
2. **② `haul` 进 §2.1 自适应定价**：运力 D/S 注入各区 `quantities[haul]`（算式 `adaptiveNextPrice` 一字未改）⇒ 运费牌价随缺口升降 ⇒
   跨格贸易的成本与成交改变。**本世界实测**：tp 三市场牌价 5 → 109,604 / 60,448 / 2,128,784（264 轮，见 §2.2）。
3. **③ 非城市格可长出跑商**：★ **本世界里一条都没长出来**（本批真跑 `MERCHANT_TRADE_INDUSTRY_CREATED` = **0**、
   `ONDEMAND_TRADE_MIGRATION_SKIPPED` = **0**）⇒ **这一条对本次指纹变动没有贡献**（如实记，不把它算进依据）。
4. **④ 两把尺统一**（`providerCapacityMilli`）：决策层候选门槛与结算层议价权读同一份派生量 ⇒ 候选集与排序输入改变。
   **本世界实测**：新理由 `no-deliverable-service-capacity` 已从该口径发出（sw 12 行；见 §2.5）。

**★ 单一形态的两套独立装置各测一次**（AGENTS §三.5：见到"两个结果"先核产物，不先怀疑非确定性）：

| 装置 | 形态 | 结果 |
|---|---|---|
| **A：本测试自身**（进程内真 `Shell` + `AppLogCapture`） | `tools/mvn-lock.sh test -pl simos-app -am -Dtest='EconomyCycleHealthTest' -Dsurefire.failIfNoSpecifiedTests=false`（18:5x，76.06 s） | 分布 = 上表新指纹；`summary.txt` 记 **轮次=96 零成交轮次=0 min/中位/max=15/42.0/198 Σfills=3648（== MARKET_FILL 3648 行）**；**Tests run: 1, Failures: 0 → BUILD SUCCESS** |
| **B：真世界 HTTP**（W7 shaded jar、从创世新播的 three-powers、`POST /api/advance` 0→660、日志白名单） | `python3 w7log.py tp360 logs/tp360.log 0:360 …`，取**同一窗口 day 0–240（96 轮）**的 `MARKET_REPORT` 逐轮 fills | 分布**逐值相同**、Σ = **3648**、零成交轮 = **0**、min/中位/max = **15/42.0/198** |

⇒ 两套装置（进程内 vs 真 HTTP + 独立 store）给出同一份指纹 ⇒ **不是产物陈旧的假象、也不是非确定性**。

**★ 牙一根没放松（逐条对照）**：`零成交轮次 = 0`、轮次数 `== 96`、同日不得两轮、相邻间隔 `≤ 5 天`、
`Σ逐轮 fills == MARKET_FILL 行数` —— 五条**一字未动**；本批**只换期望值**（并把 min/中位/max 随之改钉 15/42.0/198）。

### 1.2 其他模块的红（本批自己复跑确认）

| 范围 | 命令 | 结果 |
|---|---|---|
| W7 改动的经济面 + 相关回归类 | `tools/mvn-lock.sh test -pl simos-economy -am -Dtest='MintProductionSettlementTest,MoneyMarketSettlementTest,DeathDebtWriteOffTest,HaulServiceCommodityAcceptanceTest,MerchantCapacityAcceptanceTest,PortPolicyMerchantLaneAcceptanceTest,ModeMigrationGovServiceFailClosedTest,ExpectedProfitBookTest' -Dsurefire.failIfNoSpecifiedTests=false` | **Tests run: 47, Failures: 0, Errors: 0 → BUILD SUCCESS** |
| 本批新写的两类 | `tools/mvn-lock.sh test -pl simos-economy -am -Dtest='HaulGlobalPoolW7Test,MerchantOnDemandTradeW7Test' -Dsurefire.failIfNoSpecifiedTests=false` | **Tests run: 12, Failures: 0, Errors: 0 → BUILD SUCCESS** |
| app 面（重基线后的那一条） | 见 §1.1 装置 A | **1/1 绿** |

⇒ **除 §1.1 那条指纹外，本批未发现别的红**（`util/map/social/unit/sd/core/gov/army` 不在 W7 改动面内，未逐模块复跑，如实记）。

---

## 2. ② W7 真读数（六类，逐条数字 + 命令 + 窗口）

**★ 窗口口径（AGENTS §九.4）**：下文每个数字都标 **时点**（某个 checkpoint）或 **本期**（某个 day 区间）或 **累计**（全程 0→N）；
"毫" = 最小定点单位。**对照基线**来自运力调查报告（`.superpowers/sdd/2026-10-11-haul-supply-investigation/report.md`）：
`small-world` 本期 day 601–689（36 轮）`no-haul-service-in-shipping-hex` 原始合计 **189,257 毫商品**；
`three-powers` 本期 day 601–660（24 轮）原始合计 **22,379,502,031 毫商品**。

> ★★ **对照的可比性先说清（否则会读错）**：基线的那个 day-600 状态是**改前代码**跑出来的（W7 之前），
> 我的 day-600 状态是 **W7 代码从创世跑出来的** ⇒ 两边的**同一 day 窗口**里世界状态**不同**。
> 因此这是"**同窗口、不同代码谱系**"的对照，不是受控状态对照。**任何差异都同时包含"代码改动"与"状态分叉"两因**，本账本不把它们拆开。

### 2.1 装置自证（真世界读数可信的前提）

- **跨形态确定性（B2 分段等价，G3）**：`0→360` 一次 / `0→120→240→360` 三段 / 逐日 360 次，三份 day-360 dump 的
  **规范状态 md5 全等 `fe311fd45de6d812d4bd6d09eb6eeddd`**（5,529,571 B），逐叶深比对 **差异叶 0 / 总叶数 76,326**；
  Δrev = **1 / 3 / 360**（`canon2.py`，与 W4/W5/W6 同一把尺）。
- **跨进程逐字节**：`swperf`（INFO、另一只 store）与 `sw360`（DEBUG+TRACE）的 day-360 dump **文件 md5 逐字节相同**
  （`3f6c64ff2abc016feb6534c9a910ec19`）⇒ **日志级别不改变世界**，且两次独立 JVM 给同一份字节。

### 2.2 ① 运费定价：`HAUL_PRICE_SIGNAL` 逐轮轨迹（D/S/未服务/α/牌价前后/providers/demandByShippingHex）

命令：`python3 w7log.py <tag> logs/<tag>.log <窗口…>`（逐行流式解析；读数落 `results/<tag>.readings.json`）。

**small-world（单市场区 `single-region`，275 轮有信号，day 3–688）**

| 项 | 值 |
|---|---|
| 牌价 首 / 末 / 最小 / 最大 | **5 / 1030 / 5 / 6482** 毫 |
| 逐轮上升 / 下降 / 持平 | **197 / 77 / 0** |
| **缺口期轨迹（day 3–15，S=0）** | day3 `D=8,405,466 S=0 unserved=8,405,466 α=50‰ 4→5 providers=0`；day5 `D=6,677,130 → 5→6`；day8 `→6→7`；day10 `→7→8`；day13 `→8→9`；day15 `→9→10` |
| **充足期轨迹（day 675–688，S=96,962、providers=1）** | day675 `D=100 S=96,962 1399→1330`；day678 `→1330→1264`；day680 `→1264→1201`；day683 `→1201→1141`；day685 `→1141→1084`；day688 `→1084→1030` |

⇒ **牌价随缺口上升、充足时回落**（两个方向都实测到）；`providersWithService` 从 0 → 1 与 S 从 0 → 96,962 同步。

**three-powers（3 个市场区，各 264 轮）**

| 市场 | 牌价 首 → 末 | 最小/最大 | 升/降 | 末窗 D vs S |
|---|---|---|---|---|
| `c-tp-copper` | 5 → **109,604** | 5 / 109,604 | 230 / 33 | day660 `D=19,917 S=11,634 → 105,898→109,604` |
| `c-tp-gold` | 5 → **2,128,784** | 5 / 2,128,784 | **263 / 0** | day660 `D=924 S=0 → 2,027,414→2,128,784`（**全程没有可交付服务 ⇒ 只涨不落**，如实记） |
| `c-tp-silver` | 5 → **60,448** | 5 / 60,448 | 215 / 48 | day660 `D=24,117 S=0 → 45,299→47,563` |

★ `demandByShippingHex` 逐格明细在首轮就给出"谁在等运力"（例：tp day3 `{-3_0=627238, -2_-1=625835, -2_0=624866, -1_-2=4022129, …}`）。

### 2.3 ② 非城市格的货能不能运出去（改名后两条理由的计数与未服务运力量）

**★ 先给一个结构性事实（本批实测，很重要）**：`HaulService.NO_DELIVERABLE_SERVICE_GOODS_REASON`
（`no-deliverable-haul-service-goods`）在 `src/main` 里**只有 javadoc 引用、零发射点**
（`grep -rn` 只命中 `HaulService.java:105/125`）⇒ **真世界日志里根本不会出现这个名字**（sw/tp 两个世界均为 **0 次**）。
运行时唯一出现的是池侧 `no-carrier-with-deliverable-service`。**改名做到了"两条名字不再同值"（类型层），
但"货侧"那条名字目前在运行时**没有消费者**（如实记；不修，见 §6 F3）。

| 世界 | 窗口 | `LOGISTICS_SERVICE_SUPPLY_REJECTED`（**本期**笔数） | 同窗口 Σ`truncatedMilli`（**本期**原始合计，毫商品） | 理由分布 |
|---|---|---|---|---|
| small-world | 0→360（144 轮） | **11,871** | **61,432,735** | 100% `no-carrier-with-deliverable-service` |
| small-world | 361→600（96 轮） | **1,675** | **398,077** | 同上 |
| small-world | **601→689（35 轮）** | **0** | **0** | —— |
| three-powers | 0→360（144 轮） | **1,422,094** | **7,549,296,662** | 同上 |
| three-powers | 361→600（96 轮） | **216,754** | **5,984,446** | 同上 |
| three-powers | **601→660（24 轮）** | **0** | **0** | —— |
| 基线（**改前代码**，同窗口） | sw 601–689（36 轮） | —— | **189,257**（旧字面量） | sw：5 格有派生需求 |
| 基线（**改前代码**，同窗口） | tp 601–660（24 轮） | —— | **22,379,502,031**（旧字面量） | tp：37 格全有派生需求 |

★ **另一路读数**（`MERCHANT_CAPACITY_LANE_TRUNCATED`，池的逐 lane 未服务量，**本期**）：
sw 0→360 = **16,141** 笔 / **65,142,802** 毫；tp 0→360 = **2,586,245** 笔 / **7,856,959,786** 毫；两世界后窗均 0。

★ **诚实结论**：**"非城市格的货能不能运出去"在本批真世界里没有被证明改善** ——
0→360 窗口仍有 1.19 万（sw）/ 142 万（tp）笔"全池没有可交付服务货"的拒绝（理由改名后**可读**了，但**量没有消失**）；
后窗降到 0 的原因**未验证**（可能是跨格价差归零 ⇒ 不再派生运力需求，也可能是别的；见 §5 未验证清单）。

**成交笔数 / 成交额（本期，按窗口；成交额 = Σ`goodsPaymentMilli` 逐笔，**按币分列、不跨币相加**）**

| 世界 | 窗口 | 轮数 | 成交笔数 | 零成交轮 | min–max | 成交额（按币） | 运费实收（按币） |
|---|---|---|---|---|---|---|---|
| small-world | 0→360 | 144 | **1,746** | 0 | 3–160 | silver **109,107** | silver **37,278** |
| small-world | 361→600 | 96 | **1,333** | 0 | 8–256 | silver **2,448** | silver **27,798** |
| small-world | 601→689 | 35 | **295** | 0 | 8–11 | silver **0** | silver **80** |
| three-powers | 0→360 | 144 | **5,583** | 0 | **15–198** | copper 94,094 + gold 91,796 + silver 107,434 | copper 13,343 + gold 27,703 + silver 23,659 |
| three-powers | 361→600 | 96 | **3,405** | 0 | 30–46 | 三币均 **0** | copper 64 + silver 9,940 |
| three-powers | 601→660 | 24 | **849** | 0 | 34–37 | 三币均 **0** | 三币均 **0** |

★ **"是否上移"的可比读数**：**成交笔数上移** —— tp **day 0–240（96 轮，同装置同窗口）**由 **3,522 → 3,648 笔（+126，+3.6%）**
（旧值 = W5.5 基线指纹的 Σ，新值 = 本批实测，两者都由同一个 `EconomyCycleHealthTest` 口径给出）。
**成交额的后窗归零**（sw 601–689 = 0、tp 361–660 三币均 0）是价格崩塌到 0 后的"白送"语义（W2 口径）**不是"没有成交"**（同期仍有 295 / 3,405 / 849 笔）。
★ **改前的同指标成交额读数本批拿不到**（W6 标定给的是别的聚合口径）⇒ "成交额是否上移"**未验证**（见 §5）。

### 2.4 ③ 跑商有没有长出来（逐户 + 计数 + ★ 特别回答）

**计数（本批真跑，全部窗口）**

| 事件 | small-world（0→689） | three-powers（0→660） | health 世界（tp 240 天，装置 A） |
|---|---|---|---|
| `MERCHANT_TRADE_INDUSTRY_CREATED` | **0** | **0** | **0** |
| `ONDEMAND_TRADE_MIGRATION_SKIPPED` | **0** | **0** | **0** |
| `MIGRATION_MOVE targetMode=merchant` | **16**（0–360: 12 / 361–600: 4 / 601–689: 0） | **48**（0–360: 25 / 361–600: 19 / 601–660: 4） | **5**（全在 day 240） |
| `MODE_MIGRATION_CANDIDATES`（轮级 DEBUG，每 30 天一轮） | **22** 轮（day 30…660） | **22** 轮 | —— |
| `MODE_MIGRATION_PROSPECT_INFEASIBLE` | **9,927**（`no-carrier-asset` **9,581** + `no-capacity` **346**，全部落在 0→360） | **22,368**（`no-carrier-asset` **22,076** + `no-capacity` **292**） | —— |
| `PRIMARY_MODE_CHANGED` | 142 / 21 / 4（按窗口） | 246 / 21 / 4 | —— |

**逐户（时点读数，dump 解析；sw day 360 与 day 689 两次都同型）**

| 格 | 选了跑商的户 | 其中持 CATTLE | 持服务货 | 人口合计 | 有 trade 模板 |
|---|---|---|---|---|---|
| `0_0`（城市格） | 2 | **100**（landlord） | **93,780**（day360）/ 96,962（day689） | 159 / 162 | ✅ |
| `0_2`（城市格） | 2 | **88**（landlord） | 0 | 45 / 35 | ✅ |
| **其余 17 个非城市格** | **每格 2 户（共 34 户）** | **0** | **0** | **0** | ❌ |

**★★ 特别回答：非城市格有没有出现新的跑商户 —— 答案 = 没有（一户都没有）。** 且**原因已具名、可复核**：

1. 全世界 **200 头 CATTLE 全在城市格**（`0_0` 100 头 + `0_2` 88+12 头，时点值，day360/689 同）；
2. 17 个非城市格里"选了跑商"的 **34 个户，人口 / 劳动 / CATTLE / 服务货全为 0**（它们是创世播下的**壳户**）；
3. W7 的按需通道要求"**该户自备 CATTLE > 0**" ⇒ 这些户在决策层就**具名不可行**
   （`MODE_MIGRATION_PROSPECT_INFEASIBLE reason=NO_MERCHANT_CAPACITY:no-carrier-asset`，
   逐格首个失败理由在轮级 `MODE_MIGRATION_CANDIDATES.merchantFirstFailureByHex` 里逐格可见：
   例 sw day30 `{-2_0=…no-carrier-asset, 0_0=NO_LABOR:hh--1_0-rural-landless_laborer-displaced, …}`）；
4. 因此 `trade@hex` 一次都没按需创建 ⇒ **不会有新跑商户**。
5. 那 16 / 48 笔 `targetMode=merchant` 的迁移**全部迁往已存在 `trade` 模板的城市格**
   （sw 例：day 120/150/180 `hh-0_0-urban-middle_peasant → hh-0_0-urban-landlord @0,0`；tp 例：day 240 五笔全在 `-3,3 / 0,-3 / 3,0`）
   —— **它们是"格内换人/并入既有商号"，不是"非城市格长出跑商"**。

⇒ **写码批的预警被实测证实**（"可能一户都不会出现"）：**W7 的 ③ 在 sw/tp 两个世界里都没有产出**。
本批**不粉饰**：这条判据在当前世界设定下**未被真实触发**（单测层有正向/负向用例，见 §3 的 C3/C4/C5）。

### 2.5 ④ 两把尺（门槛理由前后对照）

命令：`grep -o "event=PRIMARY_MODE_RANKING_EXCLUDED …" logs/sw360.log`（**有界提取**，避免巨行）。

| 户 | 出现次数 | 理由 | 时点 |
|---|---|---|---|
| `hh-0_2-rural-middle_peasant` | **22** | `no-bargaining-power` | 各迁移轮 |
| `hh-0_2-urban-landlord` | **3** | `no-bargaining-power` | **day 300 / 330 / 360** |
| `hh-0_2-urban-landlord` | **12** | **`no-deliverable-service-capacity`（W7 新理由）** | **day 390 → 660**（每 30 天一轮） |
| 其他 | 12 行（`no-deliverable-service-capacity` 总命中 12） | 同上 | 后窗 |

**怎么读这三行（别读反）**：
1. **W7 的新理由确实上线了**：`no-deliverable-service-capacity`（= "这一户拿不出可交付运力"这条**事实**）在 sw 真世界发了
   **12** 行，且**恰好发在调查报告点名的那一户**（`hh-0_2-urban-landlord`）身上 ⇒ ④ 的"两把尺同一口径"在真世界里可见。
2. **`no-bargaining-power` 没有消失**（25 行）：它的语义在 W7 后**收窄**为"可交付运力 > 0，但在**全池**（分母 = 全池可交付运力）
   里占比‰向下取整 = 0"。分母改成全池会让"占比取整为 0"**更容易**发生 ⇒ 它继续出现是**设计后果，不是漏改**
   （写码批账本 §2.2/§3.2 明写分母改全池）。
3. ★ **一处本批没能断开的因果（如实记）**：`hh-0_2-urban-landlord` 在 **day 360 的时点状态**是
   `population 13 / laborMilli 0 / haul 0 / CATTLE 88` ⇒ 若按**该时点**算，`providerCapacityMilli = 0`，本应给**新理由**，
   而日志给的却是 `no-bargaining-power` ⇒ 说明**迁移轮读到的商品账不是"当日结束"的那一份**（迁移轮的时刻在日结算之前/之内），
   **但本批没有取到轮内中间态 dump，无法证明**。⇒ 记入 §5"未验证"，不作结论。

### 2.6 ⑤ 具名失败可观测（`NO_MERCHANT_CAPACITY:*` 有日志）

| 读数 | sw（0→689） | tp（0→660） |
|---|---|---|
| `MODE_MIGRATION_CANDIDATES`（轮级 DEBUG，含 `candidateRows/merchantCandidates/byHex/merchantCandidatesByHex/merchantFirstFailureByHex/failuresByReason/moves/movedPopulation`） | **22** 轮 | **22** 轮 |
| 示例（sw day30） | `candidateRows=3906 merchantCandidates=566 moves=6 movedPopulation=62`，`merchantFirstFailureByHex` 18 格给 `no-carrier-asset`、2 格给 `NO_LABOR:hh--1_0-…`；`failuresByReason` 含 `NO_MERCHANT_CAPACITY:no-carrier-asset=496` | 同型 |
| 逐候选 `MODE_MIGRATION_PROSPECT_INFEASIBLE` | **9,927** 行（含 `household/modeId/hex/reason/deliverableCapacityMilli`） | **22,368** 行 |

⇒ 改前那条"静默 `continue`"（调查报告 §2.2：全仓 `grep NO_MERCHANT_CAPACITY` 只命中一处）**已经变成可读账**：
**轮级一行 + 逐候选一行 + 逐格首个失败理由**三种粒度都在真世界日志里实际出现了。

### 2.7 ⑥ 改名后两条理由互不混淆（真读数 + 单测各一条）

| 项 | 读数 |
|---|---|
| `no-carrier-with-deliverable-service`（池侧，运行时**唯一**出现的那条） | sw **13,546** 行 / tp **1,638,848** 行（= 1,422,094 + 216,754） |
| `no-deliverable-haul-service-goods`（货侧） | sw **0** / tp **0**（★ 运行时无发射点，见 §2.3 与 §6 F3） |
| `no-haul-service-in-shipping-hex`（退役字面量） | sw **0** / tp **0**（旧名字在真世界日志里**已绝迹**） |
| 单测 | `HaulGlobalPoolW7Test.theTwoServiceReasonLiteralsAreDistinctAndSelfExplaining`（两条常量值 + 互不相等 + 都不等于退役值 + 枚举 `detail()` 同源）✅ |

### 2.8 不回归（逐条一条读数）

| 判据 | 读数 | 装置/命令 |
|---|---|---|
| **B2 分段等价** | 三形态 day-360 状态 md5 全等 `fe311fd4…`；**差异叶 0 / 76,326** | `canon2.py`（sw360 / sw3seg / swtick 三份 dump） |
| **Revision Δ** | 一次推进 `0→360` ⇒ **Δrev = 1**；三段 ⇒ **3**；逐 tick ⇒ **360** | 三次运行的 `result.json`（`revisionDelta`） |
| **性能（单次墙钟）** | `0→360` 一次 = **6.298 s**（`swperf`，INFO；W6 验收改前测 **6.738 s** ⇒ **−6.5%**）★ 同一命令形态、同一世界、同一 INFO 档；DEBUG+TRACE 轮为 8.260 s（**不可与 W6 比**，如实分列） | `swperf` / W6 报告 §2 G4 |
| **W4 排序禁自指** | W7 的 diff **不含** `SettlementOrderBook` / `CommitOrder` / 区序生成器（`git diff --name-only` 可核）；排序仍是**内容的纯函数**（B2 三形态状态 md5 全等是最强证据）★ **未复跑 W4 验收的 309/309 窗口吻合实验** | `git diff --name-only` + B2 |
| **W5 铸币** | `MINT_PRODUCTION` 发行记录：day360 **1,500 毫** → day689 **2,500 毫**（时点累计），且**逐币种货币守恒差 = 0**（见 §2.9）；`MintProductionSettlementTest` **7/7 绿** | `w7state.py` + §1.2 命令 |
| **W5.5 货币市场** | health 世界（tp 240 天）：`MONEY_MARKET_ROUND` **96** 行、`MONEY_MARKET_RATE_WRITTEN` **96** 行（每市场轮一行）；`MoneyMarketSettlementTest` **7/7 绿** | 装置 A 日志 + §1.2 命令 |
| **W8 债务语义** | ① **溢出不致命**：从 day-689 档**继续推进到 day 700**（11 次逐日 HTTP 200，rev 331→342）—— 改前的"最大可推进日 = 689、day690 报溢出"那道墙**已不存在**；② **空户不计息**：16 个"人口 0 且未结清"的债务人合同，其本金合计 **1,046,814 毫在 day689→day700 之间逐值不变**（同 11 天里全world本金 5.578e17 → 1.199e18 仍在涨 ⇒ 计息只发生在**活户**上）；③ `DeathDebtWriteOffTest` **3/3 绿** | `sw700` 运行 + `w7state.py`/一次性脚本 + §1.2 命令 |
| **★ W8 那 16 户不是缺口（本批查实）** | 这 16 个"人口 0 且未结清"的户，**Social 人口事件数全部 = 0** ⇒ 它们是**创世就空**的壳户（从未"死"过），不是"户绝没销账" ⇒ **不计入缺陷** | sw d689 dump 的 `populationEvents` 交叉核对 |

### 2.9 守恒（CATTLE / 货币，时点读数 + 发行式）

命令：`python3 w7state.py dumps/sw360-d360-oneshot.json dumps/sw360-d689.json`

| 项 | day 360（时点） | day 689（时点） | 判定 |
|---|---|---|---|
| **CATTLE 总量** | **200**（3 条份额：`0_0`-landlord 100、`0_2`-landlord 88、`0_2`-middle 12） | **200**（4 条：`0_0` 100、`0_2`-landlord 88、`0_2`-middle 8 + TENANCY 4） | **总量不变** ✅（唯一变化是同格内 `12 = 8 OWNED + 4 TENANCY` 的份额重挂 ⇒ 承运资产随迁守恒） |
| **货币（逐币种）** | copper **100,000** / silver **268,700** | copper **100,000** / silver **269,700** | —— |
| **发行记录（累计）** | `GENESIS_ENDOWMENT` silver 200,000；`INITIAL_ENDOWMENT` copper 100,000 + silver 67,200；`MINT_PRODUCTION` silver **1,500** | 同上但 `MINT_PRODUCTION` silver **2,500** | —— |
| **守恒差（Σ账户余额 − Σ发行，逐币种）** | **copper 0 / silver 0** | **copper 0 / silver 0** | ✅ 货币守恒（含铸币腿） |
| 人口（Social 权威） | 3,872 | 3,099 | —— |
| 债务本金（全world，时点） | 1.027e8 | **5.578e17** | ★ 已知开放项（W6 D1 利率无上界），**不是本批回归** |
| （续跑对照）day **700** | —— | CATTLE **200**、copper 100,000 / silver **269,700**、守恒差 **0/0**、人口 3,064、债务 1.199e18 | day689→700 的 11 天里：**CATTLE 与货币逐值不变**，只有活户债务在涨 |

**three-powers（对照世界，同装置；命令同上）**

| 项 | day 360（时点） | day 660（时点） | 判定 |
|---|---|---|---|
| **CATTLE 总量** | **300**（3 个城市格 × 100） | **300** | **总量不变** ✅ |
| **货币（逐币种）** | copper **136,600** / gold **129,400** / silver **424,600** | 三币**逐值相同** | ✅（tp 无铸币产业、无 GM 发行 ⇒ 理应逐值不变） |
| **守恒差（Σ账户 − Σ发行）** | **copper 0 / gold 0 / silver 0** | **copper 0 / gold 0 / silver 0** | ✅ |
| 服务货（时点） | 76,024 | 11,634 | —— |
| 人口 / 债务本金 | 5,019 / 2.196e8 | 4,031 / 1.510e12 | —— |

★ 全部时点读数落 `results/state-all.json`（5 份 dump：sw d360/d689/d700 + tp d360/d660）。

---

## 3. ③ 补 W7 的判据测试（判据出处 + 断言 + 实跑结果）

**新增两个测试类共 12 条**（`simos-economy/src/test/java/io/mosire/simos/economy/time/`）：

### `HaulGlobalPoolW7Test`（6 条，全绿）

| # | 用例 | 判据出处 | 断言要点 | 结果 |
|---|---|---|---|---|
| P1 | `carrierInAnotherHexServesTheLaneWhoseShippingHexHasNoCarrier` | 用户原话「运力提供不应该也是全市场区共享的吗」+ v3.26「全国/跨区也能被雇」 | 夹具自证**发货格 0 个承运方**；全池承运方被分配到该 lane；`choices[0].hex == CARRIER_HEX`；整笔 1000 毫被运走 | ✅ |
| P2 | `laneWhoseTripExceedsTheDerivedRadiusIsRefusedByName` | v3.26 + 写码批 D3（总行程 ≤ 半径，fail-closed） | 夹具自证**全池有货**；`servesLane(终点)=true` 而 `servesTrip(发货,收货)=false`（判别力自证）；零分配；归因 = `OUT_OF_RADIUS`；非业务拒绝 | ✅ |
| P3 | `carriersOfTheOtherCaliberAreNotHandedAServicePricedLane` | 写码批账本 §2.2 的口径闸（混接会 ERROR + 废单） | 服务货口径承运方不得承接**未定价** lane ⇒ 零分配 | ✅ |
| P4 | `laborlessHouseholdWithServiceGoodsReadsTheSameRulerOnBothSides` | W7 派单 ④（两把尺统一到 `providerCapacityMilli`） | 旧尺（劳动+工具）= 0；新尺 = 150,000；占比 = 1000‰；**门槛理由 = null**（改前是 `no-bargaining-power`）；无货时 = **`no-deliverable-service-capacity`** | ✅ |
| P5 | `noBargainingPowerSurvivesForAHouseholdWhoseShareFloorsToZero` | 同上（负向面：既有理由不许被顺手删掉） | 大户（1,000,000）通过；小户（1）× 全池分母 1,000,001 ⇒ 占比取整 0 ⇒ **`no-bargaining-power` 原样保留** | ✅ |
| P6 | `theTwoServiceReasonLiteralsAreDistinctAndSelfExplaining` | W7 派单 ⑥ / AGENTS §四 结构性歧义 | 两条常量值逐字、互不相等、都不等于退役值 `no-haul-service-in-shipping-hex`；枚举 `detail()` 同源；第三支 `NO_MERCHANT_HOUSEHOLD` 仍独立 | ✅ |

### `MerchantOnDemandTradeW7Test`（6 条，全绿）

| # | 用例 | 判据出处 | 断言要点 | 结果 |
|---|---|---|---|---|
| C1 | `prospectWithoutTradeIndustryAndWithoutCattleIsInfeasibleByName` | 用户原话「跑商不是生产方式吗…」+ 派单 ③⑤ | 非城市格无模板 + 自备牲口 0 ⇒ **`NO_MERCHANT_CAPACITY:no-carrier-asset`**（不是改前的 `…:no-trade-industry`）；决策层**不落状态** | ✅ |
| C2 | `prospectWithSelfSuppliedCattlePassesTheCarrierAssetGate` | 派单 ③（自备即可行） | 自备 10 头 ⇒ 理由**不再是** `NO_MERCHANT_CAPACITY:*`；仍不落状态 | ✅ |
| C3 | `merchantTradeTemplateIsPureAndCarriesNoStock` | 用户原话「难到家户不会给预估生产方式预留生产资料吗？」+ 铁律（禁凭空造资产） | 纯函数（两次调用逐字段同值）；声明 `CATTLE 1/单位规模`、`haul 1/周期`、工具周期投入 100 毫 —— **是配方不是存量** | ✅ |
| C4 | `migrationWithoutSelfSuppliedCattleIsSkippedByNameAndCreatesNothing` | 派单 ③（带不来牲口 ⇒ INFO `ONDEMAND_TRADE_MIGRATION_SKIPPED`）+ §6.3 负向 | ① 具名 INFO + 具名理由；② **没有**建 `trade@H2`；③ **没有**搬人（无 outbox / 人口不变）；④ CATTLE 总量 0 = 0（**没有凭空造**） | ✅ |
| C5 | `migrationWithSelfSuppliedCattleCreatesTheTemplateAndConservesCattle` | 派单 ③ + **四类关键项「守恒式」** | ① 建出 `trade@H2` 且**逐字段 == `MerchantTradeTemplate.industry(H2)`**（不丢失）；② outbox 一笔（source/target/人口/mode/hex 逐值）；③ 经济行人口**不被旁路写**（P0 防回归）；④ **ΣCATTLE 8 = 8**、源 8→5、目标 0→3、**源减 == 目标增**、`>0` 份额恰 2 条 | ✅ |
| C6 | `fullMoveCarriesAllCattleAndKeepsTheTotalUnchanged` | 同上（**第二条分支**：整户迁空 ⇒ 全带走） | 源 0 / 目标 8；Σ 仍 8；outbox 一笔 | ✅ |

★ **测试夹具的两次修正（如实记，不是"实现错"）**：① 我最初把"部分迁移"写成"整户迁空"，被实跑纠正
（`⌊8 × 40 ÷ 100⌋ = 3`）；② 我最初断言"经济行人口被扣"，被实跑纠正 —— **P0 之后迁移执行器只推投影账 + outbox**
（经济行人口逐值不变才是正确的），断言改成 outbox 内容。两处都是**测试夹具错**，实现无误。

---

## 4. ④ 变异自证记录（关键项；改坏 ⇒ 当场红 ⇒ 还原 ⇒ 比 md5）

**纪律**：变异体只**临时**改 `src/main`，脚本 `mutate.sh` 每轮从 pristine 副本还原并当场 `md5sum -c`；
**收工 `md5sum -c` 四条全 OK**（`ModeMigrationSettlement.java` / `MerchantCapacityPool.java` / `OwnershipStakeBook.java` /
`MerchantTradeTemplate.java` 逐字节 = 变异前），工作树 main 侧只剩 W7 写码批的既有改动（`git status` 可核）。
命令（每轮）：`tools/mvn-lock.sh test -pl simos-economy -am -Dtest='HaulGlobalPoolW7Test,MerchantOnDemandTradeW7Test' -Dsurefire.failIfNoSpecifiedTests=false`。

| # | 关键项 | 变异体（**全部已还原**） | 变异体 md5 | 结果 | 红在哪一行 |
|---|---|---|---|---|---|
| **M1** | **守恒式**（随迁份额的比例口径） | `migrateAssetsForMove`：按人口比例 floor 的 `take` → `long take = share.quantity();`（永远整条带走） | `b999e44f646043c6fd2af8b67433ded2` | **1/12 红** | ★ `★ 源份额同量减少（8 → 5：⌊8 × 40 ÷ 100⌋ = 3 头随迁）` |
| **M1′** | **守恒式本体（Σ）** | `OwnershipStakeBook.execute` 的目标侧写回 `movedIn` 凭空 `+1` | `4574d954aaabec283088d04a33e2542b` | **2/12 红** | ★ `★ 守恒式：CATTLE 总量前后逐值相同（8 → 10）` + `整户迁空 ⇒ 源侧一头不剩` ⇒ **书的 (delta) 自检看不见它，是我的 Σ 断言抓到的** |
| **M2** | **不丢失（配方/身份）** | `MerchantTradeTemplate.industry`：不再声明 `capacityPerUnit{CATTLE:1}` | `8b6e3dba44af48a37078555a4945d33b` | **4 错**（Errors） | 模板断言 + 迁移/预估三支（下游因"目标产业无法承载该资产"当场抛 ⇒ 也是 fail-closed 生效） |
| **M3** | **静默付 0**（拒绝不再具名可读） | `ONDEMAND_TRADE_MIGRATION_SKIPPED` 的 `.info(` → `.trace(`（§一.9：业务拒绝必须 INFO） | `3d5dfb1fcb24f33cd288f152ec63756c` | **1/12 红** | ★ `★ 必须有一条具名 INFO（世界可读：为什么这一笔没发生）` |
| **M4** | **断粮**（运力到不了 ⇒ 货发不出） | `MerchantCapacityPool.select`：候选集 `globalOrder` → `pool`（**退回改前的逐格口径**） | `26a40738ea8506105bfb84a92ab644dc` | **2/12 红** | ★ `★ 全局共享：发货格没有承运方，但全池有 ⇒ 这一笔必须被分到` + 半径负向 |
| **M5** | **口径一致**（两类承运方不许混接） | `select` 的口径闸 `!=` → `==`（判据反向） | `f0221f272de20c1f5cff0dc9f15363f2` | **2/12 红** | ★ `★ 口径不一致 ⇒ 不分配（绝不把服务货口径的承运方塞给未定价 lane）` + 跨格正向 |

★ **等价存活 / 未自证（如实写）**：
- **"断粮"（口粮通道）** 的字面判据属 W3/W5.5 面（`MarketSettlement.deferralPossible` 那一族），**W7 未改动它** ⇒
  本批用它的**同构面**（"运力不到 ⇒ 货发不出"）做了 M4，**没有**对"赊购/口粮"本体做变异（W5.5 测试批已做过）。
- **"静默付 0"** 的字面面（借钱不补进订单预算）同样属 W5.5 面；本批做的是 W7 的同构面（W7 新引入的
  `ONDEMAND_TRADE_MIGRATION_SKIPPED` 一旦不具名/降级，世界就"静默地什么都没发生"）。

---

## 5. ⑤ 我没做 / 没验证的（逐条，不许读成"通过"）

1. **`clean verify` 未跑**（纪律：归控制方门禁）。本批只跑了上表那些类；`util/map/social/unit/sd/core/gov/army`
   **未逐模块复跑**（不在 W7 改动面内）。
2. **W7 的 ③（非城市格长出跑商）在真世界里未被触发**：`MERCHANT_TRADE_INDUSTRY_CREATED` = 0 / `ONDEMAND_TRADE_MIGRATION_SKIPPED` = 0。
   单测覆盖了它的正/负两支，但**"真世界能长出来"没有实测样本**（原因已定量：非城市格 34 户 CATTLE = 0）。
   ⇒ 判据 ③ 的**真实世界分支 = 未验证（No-Op）**。
3. **"非城市格的货能运出去"没有被证明改善**：0→360 仍有 1.19 万（sw）/ 142 万（tp）笔"全池没有可交付服务"的拒绝；
   后窗（sw 601–689 / tp 601–660）拒绝降到 0 的**机制未验证**。
4. **与运力调查基线的对照不是受控对照**：基线的 day-600 状态由**改前代码**产生，我的是 W7 从创世跑的 ⇒ 同窗口、不同状态。
   差异**不能**单独归因给 W7（本账本未拆因）。
5. **"成交额是否上移"未验证**：改前没有同指标的成交额读数（W6 标定是别的聚合口径）。本批只给了**成交笔数**的可比读数（+3.6%）。
6. **§2.5 那处因果未断开**：`hh-0_2-urban-landlord` 在 day 300/330/360 报 `no-bargaining-power`，
   而 day-360 **时点**状态按 `providerCapacityMilli` 应给新理由 ⇒ 迁移轮的读数时刻 vs 日末快照的关系**未证明**
   （需要轮内中间态 dump 或针对性单测，本批未做）。
7. **W4 的 309/309 窗口吻合 + 4/96 反事实反例未复跑**（那是 W4 验收的只读复算）；本批只给"diff 不含排序写点 + B2 md5 全等"两条间接读数。
8. **`MODE_MIGRATION_CANDIDATES` 在 health 世界（装置 A）里没采到**（装置 A 只挂了 `settlement`/`trace` 两个 logger，
   没挂 `migration`）—— 那是**采样口径**问题，不是事件没发（真世界两份日志里各有 22 轮）。
9. **`tp360` 的 `result.json` 丢失**（OOM，§6 F4）⇒ three-powers 的 `readySeconds` / 逐日耗时 / PID 无档；
   但**世界数据完好**（dumps + 完整日志，末行 day=660）。
10. **perf 读数只有小世界一次 0→360**（6.298 s）；three-powers 未做 INFO 档计时，未做多次取中位。
11. **未做**：`git commit`（纪律）；未改任何 `docs/**`（工作树里那份 spec 的改动**不是我**的，见 §6 F5）。

---

## 6. 未完成 / BLOCKED + 只报不修的发现

### 状态：**本批无 BLOCKED**（三块交付 ①②③ 都完成；④ 变异自证完成）

### 只报不修（main 侧发现，附最小复现；**未改一行 main**）

| # | 发现 | 性质 | 最小复现 |
|---|---|---|---|
| **F1** | **发货格一个跑商家户都没有、而全池有货但都够不着**时，`select` 的归因报 **`no-merchant-household-in-shipping-hex`**（逐格事实），而不是 `out-of-derived-radius`（全池事实）。**判据/数值不受影响**（`choices`/`unallocatedMilli` 逐值不变），只是**理由的主语**还停在逐格语境 | **读数面残留**（与 v3.26「全局共享」的口径不一致；§一.9 可读性） | `HaulGlobalPoolW7Test` 里把 `emptyLocalMerchant`（发货格的 0 预算户）去掉 ⇒ 同一夹具立刻从 `OUT_OF_RADIUS` 变成 `NO_MERCHANT_HOUSEHOLD`（本批就是这么发现的） |
| **F2** | `HaulService.NO_DELIVERABLE_SERVICE_GOODS_REASON`（`no-deliverable-haul-service-goods`，W7 新拆出的"货侧"名字）**在 `src/main` 里零发射点**（只有 javadoc 引用）⇒ 真世界日志里 sw/tp 各 **0 次**；运行时只有池侧那条。⇒ 改名在**类型层**完成了"两条不再同值"，但"货侧"这条**名字没有消费者** | 读数面覆盖缺口（不是行为缺陷） | `grep -rn "NO_DELIVERABLE_SERVICE_GOODS_REASON" simos-economy/src/main/java/` = 5 命中，**全是注释/javadoc**；`grep -c no-deliverable-haul-service-goods logs/sw360.log` = 0 |
| **F3** | 装置侧（**非仓内代码**）：`/tmp/w6acc/w6run.py` 收尾把日志**整份读进内存**算"去时间戳 md5" ⇒ 4 GB 的 tp 日志把 python 顶到 **11 GB RSS、被内核 OOM 杀**（`dmesg` 有 `oom-kill … python3 total-vm:16604736kB`），导致 `tp360.result.json` 与 `stripped.md5` 缺失（世界数据完好） | 运维/装置缺陷（本批已改用**逐行流式**的 `w7log.py`） | 任何 `--trace-level TRACE` 的 three-powers 长跑（本批 tp 日志 **4.02 GB**） |
| **F4** | `docs/superpowers/specs/2026-10-10-unified-price-and-ordered-settlement-design.md` 在我这批期间**被别的 agent 改了**（`git status` 里的 ` M`）—— 不是我改的（本批**未碰 `docs/**`**）；提醒控制方提交时区分归属 | 协作留痕 | `git status --porcelain` 该行 |

---

## 7. 附：命令与产物清单（可复跑）

```bash
# 0) 磁盘：开工 2.0G 可用 ⇒ 清 /tmp 陈旧批次日志（47.1 GB，逐条打印后删）
df -h / ; find /tmp -maxdepth 3 -type f -size +100M -mmin +60 -printf '%s %p\n' | sort -rn

# 1) 构建被验 jar
tools/mvn-lock.sh -DskipTests package -pl simos-app -am          # rc=0；jar md5 783ec45439f2e50d54b4828a9c54df44

# 2) 四条真世界运行（受管后台作业；端口 109xx；一次一个世界）
bash .superpowers/sdd/2026-10-11-w7-test-batch/run-worlds.sh   # sw360 / sw3seg / swtick / tp360
python3 /tmp/w6acc/w6run.py --jar /abs/.../-shaded.jar --tag sw700  --store /tmp/w7t/stores/sw360  --gui-port 10941 ... --keep-store   # W8：689→700
python3 /tmp/w6acc/w6run.py --jar /abs/.../-shaded.jar --tag swperf --store /tmp/w7t/stores/swperf --gui-port 10951 ... --log-level INFO # 性能

# 3) 读数（流式，不整份载入）
python3 .superpowers/.../w7log.py   sw360 logs/sw360.log 0:360 361:600 601:689   > results/sw360.readings.json
python3 .superpowers/.../w7log.py   tp360 logs/tp360.log 0:360 361:600 601:660   > results/tp360.readings.json
python3 .superpowers/.../w7state.py dumps/sw360-d360-oneshot.json dumps/sw360-d689.json > results/state-sw.json
python3 /tmp/w6acc/canon2.py md5 dumps/sw360-d360-oneshot.json dumps/sw3seg-d360-3seg.json dumps/swtick-d360-tick.json
python3 /tmp/w6acc/canon2.py diff dumps/sw360-d360-oneshot.json dumps/swtick-d360-tick.json

# 4) 测试
tools/mvn-lock.sh test -pl simos-app     -am -Dtest='EconomyCycleHealthTest' -Dsurefire.failIfNoSpecifiedTests=false
tools/mvn-lock.sh test -pl simos-economy -am -Dtest='HaulGlobalPoolW7Test,MerchantOnDemandTradeW7Test' -Dsurefire.failIfNoSpecifiedTests=false
tools/mvn-lock.sh test -pl simos-economy -am -Dtest='MintProductionSettlementTest,MoneyMarketSettlementTest,DeathDebtWriteOffTest,HaulServiceCommodityAcceptanceTest,MerchantCapacityAcceptanceTest,PortPolicyMerchantLaneAcceptanceTest,ModeMigrationGovServiceFailCloseTest,ExpectedProfitBookTest' -Dsurefire.failIfNoSpecifiedTests=false

# 5) 变异自证（改坏 ⇒ 红 ⇒ 还原 ⇒ md5）
bash .superpowers/sdd/2026-10-11-w7-test-batch/mutate.sh        # 6 轮；收工 md5sum -c 四条全 OK
```

**本批改动的测试文件（唯一交付面）**

| 文件 | 改动 |
|---|---|
| `simos-app/src/test/java/io/mosire/simos/app/time/EconomyCycleHealthTest.java` | 指纹重基线（旧→新 + 120 行依据注释，含两套装置的自证）；`baselineFills()` 逐值改钉 + min/中位/max 改 `15/42.0/198`；**五条牙一字未动** |
| `simos-economy/src/test/java/io/mosire/simos/economy/time/HaulGlobalPoolW7Test.java` | 新增（6 条：全局池跨格/行程负向/口径闸/两把尺/既有理由保留/改名不混淆） |
| `simos-economy/src/test/java/io/mosire/simos/economy/time/MerchantOnDemandTradeW7Test.java` | 新增（6 条：按需模板正负向 + 守恒式 + 整户迁空 + 具名可观测） |

**收尾状态**：无 java/python 残留；`target/surefire-reports/*.xml` 已清（单轮 1.1 GB）；磁盘 44 G 可用；
`git status` 无 `src/main` 的**新增**改动（W7 写码批那 12 个文件原样）。
