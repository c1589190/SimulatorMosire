# 运力为何只在 2 格 / 跑商生产方式能不能自动生成 —— 调查报告

- **角色**：运力供给调查 Agent（**只读仓**：未改任何源码/测试/文档；开工与收尾 `git status --porcelain` 见 §6）。
- **用户的问题（2026-10-11，逐字）**：
  > 「其他格子自己没产生运力需求吗？难道没有家户自动估算其他生产方式的利润，然后自动设置往其他所需要投入更低、利润更高的家户跑的机制吗？」
- **被查世界**：`f5b4f28d`（HEAD = W5.5 借贷重做）现构 jar 的两个世界：`small-world`（19 格 / 178 户行）与 `three-powers`（37 格 / 342 户行）。
- **产物落点**：本目录（`.superpowers/sdd/2026-10-11-haul-supply-investigation/`，在 `.superpowers/sdd/.gitignore` 的 `*` 之下）。
- **报告内所有统计窗口都带标注**（时点快照 / 本期 / 累计），见 §1.4。

---

## §0 直答（先说结论，数字与 `file:line` 在 §2）

**① 「其他格子自己没产生运力需求吗？」—— 产生了，而且量极大；只是需求的**起点**（发货格）没有运力可买。**

- `small-world` 我的小窗口（**本期** day 601–689）：5 个发货格产生过派生运力需求（`-2_1`/`-1_-1`/`0_-1`/`0_0`/`0_2`），Σ未服务 **3,791 毫商品·程**；`no-haul-service-in-shipping-hex` 原始合计 **189,257 毫商品**（36 个市场轮）。
- `three-powers` 我的小窗口（**本期** day 601–660）：**37 个格全部**产生过派生运力需求，Σ未服务 **9,293,975,607 毫商品·程**（净额口径 Σ`unallocatedNetMilli` = **2,312,595,363**）；`no-haul-service-in-shipping-hex` 原始合计 **22,379,502,031 毫商品**（24 个市场轮）。
- 这些需求**没有变成"能被人接单的订单"**：运输服务的需求由"其他商品的购买"派生（不进订单簿），被拒时只留一条 `LOGISTICS_SERVICE_SUPPLY_REJECTED`（INFO）与 `MARKET` 行的计数；**运力提供者看不到任何"有人要买运力"的挂单**。

**② 「没有家户自动估算利润、自动往利润更高的家户跑吗？」—— 有这套机制，而且**真的跑起来了**；但它只在**已经有 `trade` 产业的城市格**上能落地。**

- 机制在：D-024 的预期利润（`ExpectedProfitBook`）+ D-022 的"迁移而非原地改 mode"两条都实现了，并且**每天都在算**（我的窗口里 sw 344 行 / tp 1,187 行 `PRIMARY_MODE_RANKING_ROW`）。
- 真的跑起来了（**累计** 600 天）：`small-world` **16 笔**、`three-powers` **15 笔** `MIGRATION_MOVE` 的 `targetMode=merchant`；其中 sw 有 **2 笔源户就在非城市格**（`hh--1_0-rural-middle_peasant` day210、`hh--1_1-rural-middle_peasant` day240，`speedPerMille=1000`），**目的地是首府格 `0_0`**。
- 但**目的地永远是已有 `trade` 产业的格**（sw = `0_0`；tp = `0,-3`/`3,0`/`-3,3`）。非城市格的 merchant 候选在 `ExpectedProfitBook.merchantProspect` 的**第一关**就具名失败：`feasible=false, reason="NO_MERCHANT_CAPACITY:no-trade-industry"`（`ExpectedProfitBook.java:628-635`）。

**③ 为什么运力只在 2 格 / 3 格？—— 三道串联的闸，缺一不可：**

| # | 闸 | 证据 | 现状 |
|---|---|---|---|
| G1 | **创世只在"有城镇人口"的格播 `trade` 产业 + 100 头 CATTLE** | `EconomySeeder.java:3534/3537/3573-3577`、`:187-196`；判据 = `hasCraft = urbanPopulation > 0` | sw 只有 `0_0`/`0_2`；tp 只有 `3_0`/`0_-3`/`-3_3`（各城一格） |
| G2 | **非城市格的 merchant 候选被"本格没有 trade 产业模板"具名否掉** | `ExpectedProfitBook.java:628-635`、`ModeMigrationPolicy.java:1494-1520` | 17 格 / 34 格的 merchant 候选**每次评估都失败**，且 `ModeMigrationPolicy.java:832-834` 静默 `continue`（**这条具名理由任何日志里都没有**） |
| G3 | **需求缺口没有任何"⇒ 开产业 / 建商号"的通道**：`haul` 牌价恒 4 毫、从不因缺口变动；需求只进读数与拒绝日志 | `MarketSettlement.java:2790-2796`（服务不进订单簿）、`:8447-8468`；`CapacityDemandBook` 类注"只累加读数与日志，不写状态" | 需求 100% 由"发货格有没有现成服务货"决定，**供给方看不到需求** |

**最小缺口清单（§4 有逐条"补上它要动什么"）**：① 缺"贸易产业/商号的自动诞生"（创世之外没有任何自动路径；GM 工具 `simos.economy.upsertIndustry` 有，但是手动的）；② 非城市格的 merchant 壳户 **人口/劳动 = 0、CATTLE = 0**（即便补上产业模板，`NO_MERCHANT_CAPACITY:no-capacity` 仍然拦）；③ 决策层的 merchant 门槛与结算层的成员判据**两把尺**（前者读"劳动 + 工具"，后者读"手上的服务货"）；④ A3 退役"自报价簿"后，**"家户自己报价卖运力"这件事在仓里不存在**（改由市场牌价定）；⑤ 具名失败（`NO_MERCHANT_CAPACITY:no-trade-industry`）**不进任何日志**，调查只能回代码读。

---

## §1 装置（可复核到字节）

### 1.1 代码基线 / jar

| | 值 |
|---|---|
| HEAD | `f5b4f28d`（W5.5 借贷重做；与财富调查、死亡-债务调查同一提交） |
| 被用 jar | `simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar`，**md5 `7d3d662d953a8009248ac1698a92177b`**，27,169,137 字节，mtime `2026-10-11 16:51:11` |
| 是否重跑 Maven | **没有** —— 与财富调查、W6 总验收用的是同一份字节（三方 md5 一致：本报告 §1.1、财富调查 §1.1、W6 §1.1） |
| 开工核对 | `pgrep -af java` 无 simos 服务；`pgrep -af "classworlds.launcher\|surefirebooter"` 无 Maven；`git status --porcelain` **只有一条非本 Agent 造成的既有改动**（见 §6） |

### 1.2 复用的现成存档（**没有重跑 600 天**）

| store | 用途 | 读法 |
|---|---|---|
| `.superpowers/sdd/2026-10-11-wealth-concentration/stores/sw600`（目录 210 MB；`simos.db` 212,893,696 B，601 revisions） | ① 逐格产业/资产/家户/运力的**时点快照**；② 我的小窗口的**基态** | sqlite 只读 + `checkpoints/main/601.json` |
| `.superpowers/sdd/2026-10-11-wealth-concentration/stores/tp600`（目录 384 MB；`simos.db` 391,028,736 B） | 同上（三政权世界） | 同上 |
| `.superpowers/sdd/2026-10-11-wealth-concentration/dumps/sw600-d{0,120,240,360,480,600}.json`、`tp600-d{0,600}.json` | **时点快照**（全量信封） | 直接 json |
| `.superpowers/sdd/2026-10-11-wealth-concentration/daily/hh-sw600.jsonl`（59.8 MB，逐日逐户） | 交叉核对（本报告只用了它的字段清单，未做逐日回归） | jsonl |
| `.superpowers/sdd/2026-10-11-wealth-concentration/results/{sw,tp}600.log`（30 MB / 58 MB，**已过滤白名单**的 INFO+DEBUG+TRACE） | **累计 600 天**的 `MIGRATION_*`/`HAUL_*`/`MARKET` 读数 | 文本 |
| 财富调查报告 `…/2026-10-11-wealth-concentration/report.md` | 引用其 **600 天累计** 的物流阻塞量与 W6 的逐格运力读数（**已注明来源与口径**） | 文本 |

★ **为什么必须新跑小窗口**：现成 600 天日志的白名单（财富调查 `scripts/run-all.sh` 的 `KEEP`）**漏了** `MERCHANT_*` / `CAPACITY_*` / `LOGISTICS_*` 三族事件，所以
「逐格运力池」「逐格运力需求」「谁在卖运力」「跑商承运逐笔」这四类读数在现成日志里**一条都没有**（财富调查 §5 第 13 条自己如实记了这一点）。

### 1.3 本轮新跑的两个小窗口（唯一新跑的装置）

| | sw-replay | tp-replay |
|---|---|---|
| store（我的目录内**副本**） | `stores/sw-replay/`（`cp -a` 自财富 `sw600`） | `stores/tp-replay/`（`cp -a` 自财富 `tp600`） |
| 基态自证 | `before.cpMd5 = 70c8fed60a32b59222a4cf73f72d7f94` = 财富 `sw600/checkpoints/main/601.json` **逐字节相同** | `before.cpMd5 = d32692f46a778f8d3eb141dcda2a0d29` = 财富 `tp600/…/601.json` **逐字节相同** |
| 端口（避开 5711/5817/5857/5867/5715/5757/103xx/104xx/105xx/106xx） | 10711/10712/10713 | 10821/10822/10823 |
| 推进 | day **601 → 689**（89 个已完结日，revision 690） | day **601 → 660**（60 个已完结日，revision 661） |
| 起点为何是 600 | 复用 600 天世界的**终态**（不重跑 600 天）；续跑 = 确定性续演，不是新世界 | 同 |
| 终点为何停在这 | ★ **day 690 撞到具名失败**：`IllegalStateException: 偿还估值整数溢出的具名失败（拒绝回退到 double）: quantity=11482568556053609 numerator=1000 denominator=1`（`sw-replay2.full.log:2579`，`POST /api/advance` → HTTP 500）。⇒ 这个世界的最大可推进日 = **689** | 跑满 60 天无失败（到点主动收工） |
| 日志档位 / 白名单 | `-Dsimos.economy.logLevel=DEBUG -Dsimos.economy.traceLevel=TRACE`；`event=(MERCHANT_[A-Z_]*\|CAPACITY_[A-Z_]*\|LOGISTICS_[A-Z_]*\|MIGRATION_[A-Z_]*\|MODE_MIGRATION[A-Z_]*\|MARKET \|HAUL_[A-Z_]*\|PRIMARY_MODE[A-Z_]*\|DAY_START \|DAY_END )` | 同 |
| checkpoint 策略 | `checkpointInterval=1` + **每轮剪旧**（只留最新一份）——显式为了省盘（本机 `/dev/sdd` 只剩 3.2 GB） | 同 |
| 原始日志 | `results/sw-replay.log`（1.87 MB，5,476 行白名单命中 / 88,810 行总输出） | `results/tp-replay.log.gz`（压缩前 470,000,000 B / 1,156,689 行，压缩后 6,089,563 B；`results/md5s.txt` 记了压缩前 md5 `2ac49a94ed406c953dde046e143fdc2b`，证据表由**压缩前**的字节生成） |
| 装置脚本 | `scripts/haul_run.py`（md5 `bdba4c27be033380de107530ab7be548`）、`scripts/analyze.py`（md5 `53348f471d30abdd6ba23dccc742902d`）、`scripts/shell-{sw,tp}.json` | 同 |
| 起停 | 按 **PID** 停（`haul_run.py` 自己 `terminate()`），**全程没有用过 `pkill -f`** | 同 |

★ **一处解析噪音（不影响任何读数）**：`sw-replay.log` 里 `day=10/11` 的字面命中来自 `transfersToday=10/11` 的字段切分（不是第 10 天的行）；窗口判定一律改用 `DAY_END` 的 day 集合（sw 89 天、tp 60 天）。

### 1.4 统计窗口口径（★ 读本报告先读这一节）

| 标注 | 含义 | 本报告在哪用 |
|---|---|---|
| **时点快照** | 某一个 revision 的状态（不跨时间求和） | §2.1 的逐格表（day0 / day600）、§2.4 的"全世界 haul 持有者" |
| **本期** | 我的小窗口内**逐日累加**：sw = day 601–689（89 天 / 36 个市场轮）；tp = day 601–660（60 天 / 24 个市场轮） | §2.4 的时间序列、§2.5 的逐格需求/拒绝表 |
| **累计** | 该世界从创世起的**历史累计**（现成 600 天日志） | §2.4 的 600 天迁移/承运笔数、§2.5 引用的财富调查 13,453,751,805 / 117,666,855,687 毫 |
| **原始 vs 净额** | 逐行 `truncatedMilli`/`unallocatedMilli` 是"**每次观察**"的值，同一请求会被多轮反复观察 ⇒ Σ原始**不可**当真实量；净额（`unallocatedNetMilli`）可 | §2.5 两栏都给出并标注 |

### 1.5 只读纪律与一处如实披露

- 只写了本报告目录（`stores/` 复制件、`results/`、`evidence/`、`scripts/`）；仓库源码/测试/文档**一字未改**。
- ★ **如实披露**：用 `python3 -c "sqlite3 … mode=ro"` 读**别人的** store 时，SQLite 会在**源目录**生成伴生文件
  `.superpowers/sdd/2026-10-11-wealth-concentration/stores/{sw600,tp600}/simos.db-shm`（32,768 B）与
  `…/2026-10-11-death-debt-writeoff/stores/sw600/simos.db-shm`（**我读过的三个 store 都有**）。
  实测证据：三个 `simos.db-wal` 都是 **0 字节**（原 run 收工时就是 0）、`revisions` 仍是 601 条、`max(revision)=601`、
  `601.json` 的 md5 与我复制时逐字节一致 ⇒ **db 本体未改**，只有 SQLite 的共享内存伴生文件被建/触碰。
  此后我改为"**先复制到本目录再读**"以免再碰别人的目录。

---

## §2 逐项证据

### 2.1 Q1：哪些格有 `trade`（跑商）产业与 CATTLE 承运资产、判据是什么、非城市格到底有没有

**判据（回代码核，不是文档措辞）**

```java
// simos-app/src/main/java/io/mosire/simos/app/world/EconomySeeder.java:3534-3537
long urbanPopulation = populationOf(urbanAll);          // ← 本格 urban:<cityId>:* 批次的 Σ count
long workshops       = urbanPopulation / URBAN_CAPITA_PER_WORKSHOP;
boolean hasRural  = populationOf(ruralAll) > 0L;
boolean hasCraft  = urbanPopulation > 0L;               // ★ 唯一的 trade 开关
…
// :3573-3577
if (hasCraft) {
  plans.add(handicraft(hex, workshops).withOperator(craftOrganizer));
  plans.add(trade(hex, merchantPrincipalActor));        // ★ trade + 100 CATTLE 只在这里长出
}
```

- `urbanPopulation` 的来源 = `plan.cities()`（`PopulationSeeder.java:109-121`）：**每个规划城市一格**，城市人口落在该格；
  随后**每一格**都同时建农村池与城镇池（`:139-164`）——非城市格的城镇池人口为 **0**，但**四个城镇家户行仍然照建**（`EconomySeeder` 的 `cohortRow` "人口为 0 的行：人口/劳动/需求全 0"）。
- `trade` 模板的形状 = `EconomySeeder.java:4734-4814`（制度 `merchant`、`capacity={CATTLE:100}`、`capacityPerUnit={CATTLE:1}`、`outputPerUnit={haul:1}`、`cycleInputPerUnit={CATTLE:{tool:100}}`、显式 operator + 显式最小自留关系）。
- CATTLE 的落点 = 同一份 `capacity` 经 `ownershipStakesOf` 物化成 `owner==operator` 的 `OWNED` 份额（`:4765-4767`、`:5171-5183`）。

**逐格表（时点快照）—— `small-world`**

| 格 | 农村人口 | 城镇人口 | 产业 | CATTLE | merchant 家户（id, 人口, 劳动, 主业） |
|---|---|---|---|---|---|
| `-2_0` | 200 | 0 | farm,weave | 0 | (urban-rich_peasant,0,0,self_employed) (urban-landlord,0,0,principal) |
| `-2_1` | 200 | 0 | farm,weave | 0 | 同上（0 人口） |
| `-2_2` | 200 | 0 | farm,weave | 0 | 同上 |
| `-1_-1` | 200 | 0 | farm,weave | 0 | 同上 |
| `-1_0` | 200 | 0 | farm,weave | 0 | 同上 |
| `-1_1` | 200 | 0 | farm,weave | 0 | 同上 |
| `-1_2` | 200 | 0 | farm,weave | 0 | 同上 |
| `0_-2` | 200 | 0 | farm,weave | 0 | 同上 |
| `0_-1` | 200 | 0 | farm,weave | 0 | 同上 |
| **`0_0`（首府）** | 200 | **700** | craft,farm,mint,office,**trade**,weave | **100** | (urban-rich_peasant,**105**,…,self_employed) (urban-landlord,**35**,…,principal) |
| `0_1` | 200 | 0 | farm,office,weave | 0 | 0 人口 |
| **`0_2`（镇）** | 200 | **300** | craft,farm,**trade**,weave | **100** | (urban-rich_peasant,**45**,…) (urban-landlord,**15**,…) |
| `1_-2` … `2_0`（其余 7 格） | 200 | 0 | farm,weave | 0 | 0 人口 |

（`evidence/perhex-day0.txt`；day600 的同型表见 `evidence/perhex-sw-day600.txt`：结构不变，`0_0` 城镇 728 / `0_2` 城镇 192，`0_2` 的两户主业已变成 `merchant-porter`（`lastTransitionDay=183/213`）。）

**逐格表（时点快照）—— `three-powers`**

37 格里只有 **3 个城市格**有城镇人口 ⇒ 只有它们有 `trade` + 100 CATTLE：
`-3_3`（城镇 250，金）、`0_-3`（城镇 250，铜）、`3_0`（城镇 300，银）；其余 **34 格**：`farm,weave` + CATTLE 0 + merchant 壳户 0 人口。唯一区别是 tp 的农村格是 **150 人**。（`evidence/perhex-tp-day0-day600.txt`）

**"非城市格到底有没有 CATTLE / `trade`"——没有（跨两个世界、跨全部 601 个 revision 都验过）**

| 查法 | 结果 |
|---|---|
| `economy.assetShares` 里 `asset=CATTLE` 的份额（时点快照 day600） | sw **2 条**（`trade@0_0` / `trade@0_2` 各 100）；tp **3 条**（`trade@-3_3`/`trade@0_-3`/`trade@3_0` 各 100） |
| `economy.industries` 里 `regime=merchant` 的产业（时点快照） | sw `['trade@0_0','trade@0_2']`；tp `['trade@-3_3','trade@0_-3','trade@3_0']` |
| **全部 601 个 revision 的 changeset 扫描**：有没有任何一次 `industries` upsert 出过 `trade@<新格>` | **0 次**（`trade@…` 只作为 unit/allocation 的路径出现，从不作为 industries 条目 upsert） |
| `economy.modeTransitions`（mode 级变迁的唯一持久组件） | 600 个 `core.AdvanceTime` **全部是 `unchanged`**（第 1 个 revision 是 Bootstrap，没有该键）⇒ **mode 级变迁 = 0 次** |

**"非城市格的 merchant 壳户手里有什么"（时点快照 day0，actor 账）**：每一户（含 17 个非城市格的那 34 户）都拿到 **12,000 毫工具**创世启动工具（`EconomySeeder.java:519` 的 `MERCHANT_GENESIS_TOOL_PER_HOUSEHOLD_MILLI`，由位置目录判"该户是不是跑了商"）——**有准入门槛的资本、没有人口、没有地、没有畜力、没有产业模板**。

### 2.2 Q2：非城市格里，家户若想跑商，`merchantProspect` 的实际返回值是什么

**返回的是具名不可行，且死在第一关：**

```java
// simos-economy/src/main/java/io/mosire/simos/economy/time/ExpectedProfitBook.java:628-635
Industry trade = industryOrNull != null && isMerchantRegime(industryOrNull)
    ? industryOrNull : resolveIndustry(base, hex, modeId);
if (trade == null || !isMerchantRegime(trade)) {
  return infeasible(household, modeId, position.id(), hex, null,
                    "NO_MERCHANT_CAPACITY:no-trade-industry");
}
```

- `resolveIndustry`（`:1012-1021`）→ `ModeMigrationPolicy.industriesForMode`（`:1494-1520`）：按 `RegimeOperators.defaultRegimeForMode(mode)` 筛本格产业；**merchant 找不到匹配 ⇒ 回空表**（类注原话："绝不把农场模板当商号"）⇒ `trade == null`。
- 于是一整条链**根本没被求值**：`capacity`（劳动项）、`upkeep`（CATTLE 维护）、`routeDemand`/`unfilledBuyer`（需求）、`price`/`rate`（价格与车道）、`relation`（生产关系）**全部不参与**。

| 逐格点名 | 实际返回值 | 缺的是什么 |
|---|---|---|
| 非城市格（sw 17 格 / tp 34 格）的**任何**家户 × merchant | `feasible=false`，`reason="NO_MERCHANT_CAPACITY:no-trade-industry"`（`:634`） | **无产业模板**（唯一缺项；其它缺项被这一关短路） |
| 城市格但本户**没有劳动**（如 sw `0_2` 的两户，day600 劳动=0） | `feasible=false`，`reason="NO_MERCHANT_CAPACITY:no-capacity"`（`:664-667`，判据 = `MerchantCapacity.laborCapacityMilli(participationAdjustedLaborMilli)`） | **无劳动**（纯状态路径读不到商品账 ⇒ 工具项取 0 = 下界，`:650-652`） |
| 城市格且劳动 > 0（如 sw `0_0` 的农村地主） | `feasible=true`，但 notes 里带 `NO_RELATION`/`NO_LANE`/`DEMAND_CAPPED`/`PORTER_LABOR_UNKNOWN` 之类的具名缺口（`:669-727`） | 需求/车道/关系可能是 0 ⇒ 净收益可能为负 |

**实读（我的窗口，`PRIMARY_MODE_RANKING_ROW`）**：merchant 行只出现在**有 trade 产业的格**——sw 18 行里 `hex=0_0` 16 行 / `hex=0_2` 2 行；tp 52 行里 `-3_3` 14 / `0_-3` 26 / `3_0` 12。**其余格一行都没有**（因为不可行的候选在 `ModeMigrationPolicy.java:832-834` 被 `continue` 掉了）。

**★★ 缺口（可读性）**：`NO_MERCHANT_CAPACITY:no-trade-industry` 这个具名理由**在整个仓里只有两处拼写点**（`ExpectedProfitBook.java:634`、`:666`），**没有任何一行日志发射它**（`grep NO_MERCHANT_CAPACITY` 只命中 `ExpectedProfitBook.java`）。⇒ 调查者若只看日志，会看到"非城市格**从来没被评估过**"，而真相是"被评估了、每次都具名失败、失败理由没打出来"。

### 2.3 Q3：有没有"运力需求 ⇒ 家户自动转跑商"的通道（四条逐条给结论）

#### ① D-024 的预期利润会不会把 merchant 作为候选推荐给非城市格的家户？——**会评估，但必然不可行**

- 候选生成确实**逐格 × 逐 mode** 现算：`ModeMigrationPolicy.java:793-815`（距离 ≤ 1 的每个 hex × 每个 mode）。
- 允许条件**不是 mode 专属的**：`boolean hasIndustry = hasIndustryTemplate(base, hex)`（`:810`，实现见 `:1667-1669` = "本格有**任何**产业模板"）——非城市格有 `farm/weave` ⇒ `hasIndustry = true` ⇒ **merchant 候选照样被送进 `ExpectedProfitBook.prospect`**。
- 结果：`NO_MERCHANT_CAPACITY:no-trade-industry` ⇒ `:832-834` `continue`（不生成目标、不进排序表、**不打日志**）。
- ⇒ **结论：D-024 的"预期利润必须算"在非城市格上确实执行了，但它在第一关就被"没有 trade 产业"挡住 ⇒ merchant 永远不会成为非城市格家户的候选。**

#### ② `ModeMigrationPolicy` / `ModeMigrationSettlement` 允许迁入 merchant 的条件（**是否要求目标格有产业模板/资产**）

| 环节 | 条件 | `file:line` |
|---|---|---|
| 目标生成（候选门槛） | `hasIndustryTemplate(hex)`（任意产业）**或** `hasMerchantCapacityAt(hex)`；merchant 还过一道 `merchantGateReason`："本户在本格运力里的占比‰ > 0" | `ModeMigrationPolicy.java:809-815`、`:282-291`（`PrimaryModeRanking.merchantGateReason`） |
| 目标可行性 | `ExpectedProfitBook.prospect`；merchant ⇒ 必须有 **merchant regime 的产业模板** | `ExpectedProfitBook.java:628-635` |
| 迁入既有家户 | 目标家户的 `currentPositionId`（D-022：**转移/合并，绝不原地改源户 mode**） | `ModeMigrationSettlement.java:329`、`:875`；设计原稿 D-022 §1/§2/§4（`docs/…/2026-10-02-architecture-design-source.md:356-364`） |
| 资产随迁（D-023 §4） | 可移动资产（TOOL/SHIP/CATTLE/MACHINE）**跨 hex 时在"目标产业的同 AssetKind 下重建份额"**；`LAND`/`WORKSHOP` 不可移动 | `ModeMigrationSettlement.java:77-79`；D-023 §4（设计原稿 `:383-387`） |
| 跨 hex 重建的隐含前提 | 目标格必须有**对应产业**（CATTLE ⇒ 要有 `trade`）——没有 ⇒ 份额留原户 + 具名审计（`recordResidualAssetAudits`） | `ModeMigrationSettlement.java:550-552` |

⇒ **回答"是否要求目标格有产业模板"：是。** 对 merchant 而言，目标格必须有 `trade`：候选评估阶段就要求（`ExpectedProfitBook.java:632`），资产随迁阶段还要再要求一次（CATTLE 要在目标 `trade` 下重建）。

#### ③ A3 退役的那两族，退役后谁承接了"逐户自报价 / 利润读数"

| 退役族（唯一拼写点 `MerchantCapacityPool.java:181-192`） | 承接者（同处 `:188-192` 的配对表） | 回代码核（我实测） |
|---|---|---|
| `capacity-quote-book(per-household-self-quote)` | `market-haul-price(MarketTable.prices[haul])` | **代码里已无 `CapacityQuote*` 类**（`ls simos-economy/.../time/` 无该族）；限价改成**纯派生量** `askPerMilleOf = carrierCostPerMille(tier) + GET_READY_SURCHARGE_PER_MILLE(25‰)`（`MerchantCapacityPool.java:677-701`），且条目上的 `posted`（有没有逐户挂单）已随族删除（`:489-490`）。⇒ **"逐户自报价"这件事现在不存在** |
| `merchant-profit-book(parallel-profit-readout)` | `enterprise-profit-book(MARKET_TRADE-revenue-leg)` | `EnterpriseProfitBook.java:41/511/706` 明写收入腿 = `TransferReason.MARKET_TRADE`（含 `CARRIER_FEE`）；服务成交的钱腿按收款家户归集 = `MerchantCapacityPool.freightEarnedByHousehold()`（`:746-765`），由 `OperatorSettlement` 归入产服务的那个 unit |
| `merchant-haul-tool-threshold(…)` | `industry-cycle-input(trade.cycleInputPerUnit={CATTLE:{tool:100}})` | 我读到的 trade 产业模板确实是 `cycleInputPerUnit={"CATTLE":{"tool":100}}`（时点快照） |
| `merchant-haul-tool-reserve(necessaryInputsOf-special-case)` | `industry-declared-input-necessaryInputsOf(standard-reserve)` | 同上 |

#### ④ M0d「运力可计算可购买」到底实现了没有

设计行原文（`docs/superpowers/plans/2026-10-10-market-port-fx-merchant-plan.md:88`，**M0d**）：家户每需求一个商品产生一份运力需求 → **汇总**总量 → 向**市场上最低价的运力提供商买运力（提供者自报价）**；"运力过来同样需要运力" ⇒ 深度 1 + 固定附加费。

| M0d 的三个部件 | 实现了吗 | 证据 |
|---|---|---|
| **逐需求一份 + 汇总** | ✅ 实现了 | `CapacityDemandBook`（类注 `:17-38`：单户每需求一个商品一份、按 (家户,商品) 去重计数、逐需求格/逐发货格/逐区三张汇总）；我的窗口里 129 条（sw）/ 97,573 条（tp）`CAPACITY_DEMAND_INSTANCE` |
| **向最低价提供者买** | ✅ 实现了（但"价"不是家户报的，是市场牌价派生的） | `MerchantCapacityPool.select`（`:877-933`）按 `askPerMille` **升序**取，同价按议价权降序 → 家户 id 升序（`:508-514`）；买方从最低价起买（类注 `:846-856`） |
| **提供者自报价** | ❌ **被 A3 删掉了**（有意替换，不是遗漏） | 见 ③：`CapacityQuote`/`CapacityQuoteBook` 整族退役，价改由 `MarketTable.prices[haul]` 定（sw/tp 两个世界在我核到的四个端点上都是 **4 毫**，见 §5-11） |
| **"买运力"是不是一个显式动作** | ❌ 不是：服务**不进订单簿** | `MarketSettlement.java:2790-2796`：haul 被显式 `return new PlannedOrders(List.of(), List.of())`（供应商挂卖单会把要卖的服务冻住 ⇒ 结构性冲突）；需求由其他商品的购买派生，钱腿走 `MARKET_TRADE` 且"成交即消耗"（`HaulService.java:42-44`） |

⇒ **结论：M0d 的"需求汇总 + 从最低价起买"两条落地了；"提供者自报价"与"一张能挂/能接的运力订单"两条没有（前者被 A3 有意退役、后者被设计为不可挂簿）。**

### 2.4 Q4：600 天里有没有任何一户真的迁进/从事跑商 + 运力供给的时间序列

#### 4.1 有没有人**迁进**跑商（`MIGRATION_MOVE` 的 `targetMode=merchant`，**累计** 600 天）

| 世界 | 笔数 | 目的地（targetHex） | 源户在非城市格的笔数 |
|---|---|---|---|
| `small-world` | **16**（day 120…510，每次 1–2 人） | **全部 `0,0`**（首府格） | **2**：`hh--1_0-rural-middle_peasant`（day210，`speedPerMille=1000`）、`hh--1_1-rural-middle_peasant`（day240，1000）；其余是 `0_0` 城镇中农户自身 |
| `three-powers` | **15**（day 270…600） | `-3,3` / `0,-3` / `3,0`（三个城市格） | **0** |

⇒ **有**。而且其中 2 笔**源户就住在非城市格**（正是用户设想的"往利润更高的家户跑"）——但它们**跑的方向是城市格**，落点是"目标格已有的 merchant 家户"（`hh-0_0-urban-landlord`），不是"在自己格开一间商号"。

#### 4.2 有没有人**从事**跑商（承运逐笔）

| 读数 | 值 |
|---|---|
| **累计** 600 天 `HAUL_SERVICE_DELIVERED` | **147 笔**：`hh-0_0-urban-landlord`（发货格 `0_0`）138 笔（cloth 114 / grain 24）+ `hh-0_2-urban-landlord`（发货格 `0_2`）**8 笔**（全是 cloth，全部发生在前 240 天） |
| **本期** sw（day601–689）`MERCHANT_HAUL_RUN` | **6 笔，全部 `hh-0_0-urban-landlord`，全部 `0_0 → -1_1`**（day 603/633/663 各 2 笔） |
| **本期** tp（day601–660） | **3 笔，全部 `hh-0_-3-urban-rich_peasant`，全部从 `0_-3` 出发**（day 630） |
| 经营 `trade` unit 的家户（时点快照 day600） | sw：`unit-trade@0_0-…hh-0_0-urban-landlord`、`unit-trade@0_2-…hh-0_2-urban-landlord`（**各只有 1 户**；同格另一户 merchant 手上没有 unit） |

#### 4.3 运力供给的时间序列（**逐格逐轮**，本期）

| 世界 | 格 | 轮数 | ΣcapacityMilli | 非零轮 | 形状 |
|---|---|---|---|---|---|
| sw | **`0_0`** | 36 | **3,135,268** | **36/36** | 93,545 → 90,403 → 86,799 → 83,209（每轮被卖掉一点，单调下降） |
| sw | 其余 **18 格（含 `0_2`）** | 各 36 | **0** | **0** | 恒 0（`households=2` 但预算 0） |
| tp | **`0_-3`** | 24 | **5,383** | 24/24 | **23 轮都是 `1` 毫**，只有 day630 是 `5,360`（那天发生了 3 笔承运） |
| tp | 其余 **36 格** | 各 24 | **0** | **0** | 恒 0 |

- 现成 **累计 600 天 / 240 轮**（W6 轮日志，来源 = 财富调查 §2.3 F3 与 D2 行）：`0_0` Σ1,196,432、`0_2` Σ535,940、**其余 17 格全 0**。
- 每轮全局汇总（`MERCHANT_CAPACITY_POOL`，本期）：sw **36 轮**，`hexes=19 households=38`，`serviceGoodsAbsentHouseholds={merchant-household-holds-no-service-goods=37}` **恒定 37/38**；tp **24 轮**，`hexes=37 households=74`，**恒定 73/74**。
  ⇒ ★ 每一轮都有 **37（sw）/ 73（tp）个"选了跑商的家户"手上没有一件可交付的服务货**；全世界的运力握在 **1 户**手里。
- 时点快照 day600（actor 账）：**全世界只有 `hh-0_0-urban-landlord` 持有 `haul` = 93,545 毫**（`0_2` 的经营者持 0）。

#### 4.4 `0_2`（镇）为什么也归零——一条完整的因果链（副产物读数：**不是机制缺陷**，是 D-025"允许城市衰亡"的后果）

| 时点 | `hh-0_2-urban-landlord`（镇商号经营者） | `hh-0_0-urban-landlord` | `HAUL_SERVICE_PRODUCED` |
|---|---|---|---|
| day0 | pop 15 / labor **116,000** / sat — | pop 35 / labor 280,000 | — |
| day120 | pop 14 / labor **108,000** | pop 38 / labor 308,000 | `industries=2`，net 194,000 |
| day240 | pop 14 / labor **0** / **sat 0** | pop 48 / labor 408,000 | `industries=2`，net 159,080（镇这一份 64,000 = 上一个周期的劳动） |
| day360 | pop 12 / labor 0 / sat 0 | pop 55 / labor 504,000 | **`industries=1`**，net 97,000 |
| day480 | pop 11 / labor 0 / sat 0 | pop 64 / labor 608,000 | `industries=1` |
| day600 | pop 9 / labor 0 / sat 0 | pop 66 / labor 612,000 | `industries=1` |

- 机制（回代码核）：`householdLaborMilli = 基础劳动 × satietyPerMille ÷ 1000`（`GovernmentServiceLaborBridge.java:247`；`GovInfoTool.java:487` 明写"laborMilli 由 app 从 Social householdLaborMilli（含 satiety 折算）注入"）。
- 时点快照 day600 的联合分布（只统计 `pop>0` 的行）：**satiety=0 的 74 行全部 labor=0；satiety=1000 的 6 行全部 labor>0**（另有 6 行没有 satiety 记录、labor>0）⇒ 断顿 ⇒ 劳动 0 ⇒ `trade` unit 拿不到劳动配额（day600 的 `allocations` 里 `trade@0_2` **一条都没有**）⇒ 停产。
- ⇒ **镇上那 100 头 CATTLE 与 `trade@0_2` 模板还在，但没有人拉得动它**（"运力资产 ≠ 运力"）。

#### 4.5 生产方式（mode 级）转变：**600 天 0 次**（不是"没有机制"，是机制按 D-022 走"迁移"而非"原地改 mode"）

- `economy.modeTransitions` 在 600 个 `core.AdvanceTime` changeset 里**全部 `unchanged`**；该组件的唯一写口是 GM 命令 `economy.SwitchMode`（`ModeTransition.java:13-20`：命令只登记 `PENDING`，日结算执行），本世界从未发过该命令。
- 我的窗口里的 `PRIMARY_MODE_CHANGED`（sw 4 条 / tp 6 条）**不是**"家户改了 mode"：`ModeMigrationPolicy.maybeLogPrimaryChange`（`:743-758`）只在"**本轮确实发生了迁移**且排序表第 1 项与当前主业不同"时打点。
  - 实测对账：sw 打到 `hh-0_0-urban-landlord from merchant-principal → to family_farm`（day630/660），但 **day689 快照里它的 `currentPositionId` 仍是 `merchant-principal`**（`stores/sw-replay/checkpoints/main/690.json`）⇒ 源户 standing 未被改写，与 D-022 §1/§2 逐字一致。
  - tp 同一天出现**双向**流动：`hh-0_-3-urban-middle_peasant` **迁入** merchant-porter（`bargainingPowerPerMille=897`，`toYield=7`），同时原两户 merchant **迁出**到 handicraft（`fromYield=-342 / -19`，`toYield=2083`）⇒ **"预期利润驱动进出跑商"这条通道在城市格上是活的**。

### 2.5 Q5：其他格有没有产生运力需求（逐格读数 + 被哪条拒 + 有没有变成订单）

#### 5.1 逐格需求/供给（**本期**，`CAPACITY_DEMAND_SUPPLY_HEX`，一行 = 一个发货格 · 一个市场轮）

**`small-world`（day601–689，36 轮）**

| 发货格 | 有需求轮数 | Σ需求实例 | Σ需求工作量 | Σ已服务 | Σ未服务 | Σ运力预算 |
|---|---|---|---|---|---|---|
| `-2_1` | 2 | 2 | 4 | 0 | 4 | 0 |
| `-1_-1` | 36 | 93 | 1,073 | 0 | 1,073 | 0 |
| `0_-1` | 21 | 22 | 47 | 0 | 47 | 0 |
| **`0_0`** | 3 | 6 | 10,336 | **10,336** | 0 | 270,747 |
| `0_2` | 4 | 6 | 2,667 | 0 | 2,667 | 0 |
| **合计** | 66 | 129 | 14,127 | 10,336 | **3,791** | 270,747 |

**`three-powers`（day601–660，24 轮）**：**37 个格全部**有需求（每格 24/24 轮）。合计 Σ需求实例 **97,573**、Σ需求工作量 **9,293,980,966**、Σ已服务 **5,359**、**Σ未服务 9,293,975,607**；`0_-3`（有服务货的那一格）之外**每一格的 Σ已服务都是 0**。
（逐格全表：`evidence/tp-replay-perhex-demand.md`。）

#### 5.2 被哪一条拒（逐笔具名，**本期**）

| 世界 | 具名理由 | 行数 | 涉及发货格 |
|---|---|---|---|
| sw | `no-haul-service-in-shipping-hex` | 123 | `-2_1`(2) / `-1_-1`(93) / `0_-1`(22) / `0_2`(6) |
| tp | `no-haul-service-in-shipping-hex` | **372,084** | **全部 37 格**（每格 6,048–27,032 行） |

- ★ **这条字面量的语义要说清**：它是 `MerchantCapacityPool.LogisticsBlock.NO_SERVICE_GOODS` 的 `detail()`（`MerchantCapacityPool.java:1051-1052`），与 `HaulService.NO_SERVICE_SUPPLY_REASON`（`HaulService.java:108`）**同拼写**。
  判定次序（`MerchantCapacityPool.java:1101-1119`）：① 池为空 ⇒ `no-merchant-household-in-shipping-hex`；② **服务成市格但预算合计 ≤ 0 ⇒ `no-haul-service-in-shipping-hex`**；③ 半径外；④ 亚单位残余；⑤ 运力耗尽。
  我的窗口里**全部命中 ②**（池非空：每格都有 2 户"选了跑商"的壳户，预算 0）⇒ **不是"没有跑商家户"，而是"本格没有可交付的服务货"**。
  ★ 顺带更正一处口径：**"服务成市的格"不是 2 个而是全部格** —— `EconomySettlement.haulServiceHexes`（`:747-756`）按 `HaulService.pricedAt` 逐格判，而 sw 的唯一价表 `single-region` 覆盖 **19 格**且含 `haul:4`（时点快照 `marketTables`）⇒ 19 格都是"服务成市格"。（财富调查 D3 把这条归因读成"发货格没有 haul 牌价"，**按代码不成立**；见 §4-A2。）

#### 5.3 需求有没有变成"能被人接单的订单"——**没有**

| 事实 | 证据 |
|---|---|
| 服务**不进订单簿** | `MarketSettlement.java:2790-2796`：成市格的 haul 直接返回空单（并写明理由：给 haul 挂卖单会被 `commitFreezes` 冻住，与"服务成交实扣走同一张活表"结构性冲突） |
| 需求由**别的商品**的购买派生 | `CapacityDemandBook` 类注 `:17-38`：每需求一份 → 逐格/逐区汇总，**只累加读数与日志**，"不是先囤总量再分发的配额表" |
| 被拒只留日志 | `LOGISTICS_SERVICE_SUPPLY_REJECTED`（INFO，`MarketSettlement` 的 `logLogisticsBusinessRejection`）+ `MARKET` 行的 `logisticsDetailQuantities={no-haul-service-in-shipping-hex=…}` + `MERCHANT_CAPACITY_LANE_TRUNCATED`（DEBUG） |
| 运力提供者看不到需求 | `MerchantCapacityPool.select` 只按"手上有没有服务货"分配；**没有任何"缺口 ⇒ 报价/接单/开产业"的写口** |
| 价格不反馈 | `haul` 牌价在两世界都是 **4 毫**（我核到的端点：sw day600 与 day689、tp day600 与 day660；逐轮牌价**未核**，见 §5-11）；逐轮读到的 `askMin/askMaxPerMille` 恒 **50‰**（= 派生限价，不是牌价）⇒ 缺口再大也不改变任何价格输入 |

**本期 vs 累计的量级对照（都标口径）**

| | sw | tp |
|---|---|---|
| `no-haul-service-in-shipping-hex`（**本期**，原始可重复观察） | 189,257 毫商品 | 22,379,502,031 毫商品 |
| 同一条（**累计** 600 天，财富调查 §2.3 F3 引 W6 轮日志） | 13,453,751,805 毫 | 117,666,855,687 毫 |
| `LOGISTICS_CAPACITY` 的 `reasons` 计数（**本期**） | 155 件 | 8,179 件 |

⇒ **需求不但有，而且在 tp 世界是"每格每轮都有、量级上亿毫商品"；它 100% 变成了拒绝日志，0% 变成订单。**

---

## §3 对用户那句话的直接回答（机制在不在、被什么挡住、要补什么）

> 用户：「其他格子自己没产生运力需求吗？难道没有家户自动估算其他生产方式的利润，然后自动设置往其他所需要投入更低、利润更高的家户跑的机制吗？」

**逐句回答：**

1. **"其他格子自己没产生运力需求吗？"** —— **产生了**。sw 本期 5 个格、tp 本期全部 37 个格都产生了派生运力需求（tp 本期 Σ未服务 92.9 亿毫商品·程）。
   但它们**买不到**：运力的"供给"被定义成**发货格现成的运输服务货**，而服务货只由 `trade` 产业产出，`trade` 只在有城镇人口的格。
2. **"难道没有家户自动估算其他生产方式的利润、然后自动往利润更高的家户跑的机制吗？"** —— **有，而且活着**。D-024 的预期利润逐格逐 mode 现算（每天 344 / 1,187 行排序表读数），D-022 的"迁移而非改 mode"也照做（600 天 0 次 mode 级改写，但有 31 笔迁入 merchant 的迁移）。**这套机制本身不是缺口。**
3. **"为什么只在 2 格？"** —— 因为这套机制**只被允许迁到"已经有那种产业模板"的格**：
   - 非城市格**没有 `trade` 产业模板、没有 CATTLE、merchant 壳户人口/劳动 = 0**（G1）；
   - merchant 候选在非城市格**必然**具名失败（`NO_MERCHANT_CAPACITY:no-trade-industry`）并被静默丢弃（G2）；
   - 需求缺口**没有任何通道**去催生"新产业/新商号"（G3）：D-024 只算"既有产业的预期利润"，它**不会创建产业**；创世之后唯一能造 `trade` 的入口是 GM 命令 `simos.economy.upsertIndustry`（手动）。
4. **一句话**：**用户设想的机制存在，但它被"生产方式必须先有产业模板"这条前置条件锁死在创世播过 `trade` 的格上；而"需求 ⇒ 新产业"这一环在仓里根本不存在。**

**最小缺口清单（每条给"补上它要动什么"）** —— 详见 §4：

| 缺口 | 补上它要动什么（最小范围） |
|---|---|
| **G1 创世之外没有"贸易产业/商号的自动诞生"** | 要么给 `trade` 一个"按需求/城市化派生"的生成路径（新阶段：产业诞生结算），要么允许 D-024 候选在评估期"新建产业+组织"（`ExpectedProfitBook` 只读 `base.industries()`，要扩成可写副本） |
| **G2 非城市格缺劳动/资产** | merchant 壳户人口=0 是创世的必然（城镇人口只在城市格）；要跑商就得先"有城镇人口"，即 D-025 的城市化率派生链——**不是本批能补的**，须与用户确认"商号能不能脱离城镇存在" |
| **G3 需求→供给没有价格信号** | 需要一条"缺口 ⇒ 报价/建商号"的通道；当前 `haul` 牌价是 GM 数据（`MarketTable.prices`），不随缺口变动 |
| **G4 决策层/结算层两把尺** | `merchantGateReason` 读"劳动+工具"（`sharePerMilleAsProviderAt`→`MerchantCapacity.of`），池成员判据在服务成市格读"手上的服务货"（`MerchantCapacity.ofService`）⇒ `0_2` 那两户**即便有服务货也可能因 labor+tool=0 被判 no-bargaining-power**（我的窗口实读：`PRIMARY_MODE_RANKING_EXCLUDED … hh-0_2-urban-landlord modeId=merchant reason=no-bargaining-power`） |
| **G5 具名失败不可观测** | `ModeMigrationPolicy.java:832-834` 加一条具名 DEBUG/INFO（按 §一.9 的"业务拒绝 = INFO"），否则调查只能读代码 |
| **G6 A3 之后没有"逐户自报价"** | 若用户要"家户自己报价卖运力"，得新增一条写口（当前 `posted` 已被删除，价是纯派生量）——**这是有意退役，不是缺陷**，需用户裁定是否恢复 |

---

## §4 缺陷 / 缺口清单（逐条：现象 + 证据 + 影响 + 补上要动什么）

| # | 现象 | 证据（`file:line` / 读数） | 影响 | 补上要动什么 |
|---|---|---|---|---|
| **A1** | **非城市格的 merchant 候选每次评估都失败，且失败理由不打日志** | `ExpectedProfitBook.java:628-635`（具名 `NO_MERCHANT_CAPACITY:no-trade-industry`）；`ModeMigrationPolicy.java:832-834` `continue` 无日志；全仓 `grep NO_MERCHANT_CAPACITY` 仅命中 `ExpectedProfitBook.java:634/666` | 调查/运维只看日志会误判成"从没评估过"；用户看到的现象（"没人往我这里跑商"）无法从读数自证原因 | 在 `:832-834` 的 `continue` 前加一条具名行（`PROSPECT_INFEASIBLE`，带 `reason`/`modeId`/`hex`）；按 §一.9 分级：业务拒绝 = INFO |
| **A2** | **"服务成市格 = 全部格"与"运力只在 2 格"是两件事，容易被读混** | `EconomySettlement.java:747-756` + 时点快照 `marketTables['single-region'].prices.haul = 4`（覆盖 19 格）；`MerchantCapacityPool.java:1101-1119` 的 ② 支 | 财富调查 D3 把 `no-haul-service-in-shipping-hex` 读成"发货格没有 haul 牌价"（**按代码不成立**）；正确含义是"有牌价、有跑商家户、但本格没有可交付的服务货" | 不改代码；把归因档的**具名**写进读口说明（`NO_SERVICE_GOODS` vs `HaulService.NO_SERVICE_SUPPLY_REASON` 同拼写是**结构性歧义**，建议给前者换一个不同拼写） |
| **A3** | **决策层与结算层对"运力"两把尺** | 结算：`MerchantCapacityPool.java:442-481`（服务成市格 ⇒ 预算 = 手上服务货）；决策：`PrimaryModeRanking.java:282-291` → `MerchantCapacityPool.sharePerMilleAsProviderAt`（`:617-646`）→ `MerchantCapacity.of`（劳动+工具） | 一个"手上有服务货但劳动=0"的户（`0_2` 正是）会被判 `no-bargaining-power`，**永远当不上主业跑商**，而它其实能卖服务 | 把 `sharePerMilleAsProviderAt` 的分支与服务成市格对齐（或明确文档化为"决策层用下界"），并加往返判据 |
| **A4** | **`0_2` 的 `trade` 产业在 day240 之后停产，运力资产（100 头 CATTLE）闲置** | `HAUL_SERVICE_PRODUCED` day120/240 = 2 industries、day360/480/600 = 1；`hh-0_2-urban-landlord` labor 108,000(day120) → **0**(day240 起)，satiety 0；day600 的 `allocations` 里 `trade@0_2` **0 条**；因果链见 §2.4 | "运力资产 ≠ 运力"：全世界实际可卖的运力只剩 1 户 | 这是饥荒 ⇒ 劳动 0 的直接后果（D-025 允许城市衰亡）；若要"商号不随城市饿死"，需要独立的"商号维持"机制（**属新设计，需用户裁定**） |
| **A5** | **`small-world` 的 day 690 撞上"债务估值整数溢出"的具名失败（fail-closed），该世界只能跑到 day 689**（`three-powers` 在我跑的 60 天里没遇到） | `sw-replay2.full.log:2579`：`偿还估值整数溢出的具名失败（拒绝回退到 double）: quantity=11482568556053609 numerator=1000 denominator=1`；`POST /api/advance` → 500 | 小世界 600 天世界的**最大可推进日 = 689**（这本身是 fail-closed 的正确行为，但它封死了长跑） | 不在本调查范围；建议单独开调查/批次（`quantity≈1.148e16` 属名义债额爆炸，与 W5.5 的债务累积链同源） |
| **A6** | **读别人的 store 会留下 `-shm` 伴生文件** | §1.5；三个源 store 各多出一个 32,768 B 的 `simos.db-shm`（`-wal` 0 字节、db md5 未变） | 只读纪律的"零痕迹"不成立（db 本体无损） | 约定：读别人的 store 先 `cp` 到自己的目录（本报告后半程已改为此法） |
| **A7** | **需求读数不可逐行相加** | `MerchantCapacityPool.java:140-157`（`unallocatedMilli` 原始 / `unallocatedNetMilli` 净额 / `unallocatedObservationIndex`）；我的实测：tp 24 轮 Σ原始 8,738,318,882 vs Σ净额 **2,312,595,363**（放大 3.78 倍） | 误用会把缺口算大数倍 | 已在读口分成两栏（本报告两栏都给）；无代码改动 |

---

## §5 未做 / 未验证项（★ 不写"应该是"，只写没做）

1. **没有跑第二个 600 天**：两个小窗口都是复用现成 store 的**续跑**（sw 601→689、tp 601→660），没有重跑长窗口。
2. **没有重跑 Maven / 没有重跑测试**：全程用现成 jar（md5 已核）。
3. **没有验证 `three-powers` 的 day 600 之后的衰减链**（`0_-3` 为什么只剩 1 毫运力）：只读了逐轮读数，**没有**像 §2.4 对 `0_2` 那样回代码定位到"哪一户、哪一天、为什么"。
4. **`0_2` 的 8 笔承运与 2 户 merchant-porter 的 `PRIMARY_MODE_CHANGED`（day 183/213）我没有找到当时的日志行**：这两个日期的原始日志在财富调查的白名单之外（`PRIMARY_MODE_*` 不在 `KEEP` 里）⇒ 只从**时点快照**（`lastTransitionDay`）读到，**没有**逐笔事件佐证。
5. **没有做"判别实验"级别的因果隔离**：G1/G2/G3 三条闸是**读代码 + 读状态**得到的，**没有**做"只补一处 ⇒ 现象消失"的单点实验（例如手工给 `-1_1` 造一个 `trade` 产业看它是否开始跑商）。这是本报告最大的未验证面。
6. **没有动任何代码/命令**（除起停我自己的服务）：`simos.economy.upsertIndustry` 这个 GM 工具**没有实测过**它能不能在非城市格造出可用的 `trade`（只从工具清单与 handler 存在性确认）。
7. **没有做 `haul` 牌价变动的实验**（把 `prices[haul]` 改掉看行为）——那需要写命令，超出只读边界。
8. **没有逐日回归 `daily/hh-sw600.jsonl`**（只用了它的字段清单）。
9. **没有覆盖 `three-powers` 的"跨区（三政权）lane"**：tp 的 3 个城市分属 3 个政权/币种，我的窗口里跨区物流的读数**没有单独拆出来**（`CAPACITY_DEMAND_REGION` 我只做了合计，未逐区对比）。
10. **没有验证 §2.4 里 `0_2` 那 8 笔承运当时的运费/收入**（`freightEarnedByCurrency` 我只在池汇总行里看到 `{}`）。
11. **`haul` 牌价"不变"只核到两端点**：sw 的 day600 与 day689（`single-region`，19 格，`haul:4`）、tp 的 day600 与 day660（3 个区市场表各 `haul:4`）——**没有**逐轮核价（日志里 `askMin/askMaxPerMille` 恒 50‰ 是**派生限价**，不等于市场牌价）。

---

## §6 交账自检

| 项 | 结果 |
|---|---|
| 开工 `git status --porcelain` | ` M docs/superpowers/specs/2026-10-10-unified-price-and-ordered-settlement-design.md`（**非本 Agent 造成**，开工前就在） |
| 收尾 `git status --porcelain` | 同上，**逐字一致**（本 Agent 只写 `.superpowers/sdd/2026-10-11-haul-supply-investigation/`，在 `.superpowers/sdd/.gitignore` 的 `*` 之下） |
| 进程 | `haul_run.py` 两个窗口都已 `terminate()`；收尾 `pgrep -af "simos-shaded\|simos-app"` 无残留 |
| 端口 | 只用 10711-10713 / 10821-10823；未复用 5711/5817/5857/5867/5715/5757/103xx/104xx/105xx/106xx |
| jar | 未重建、未覆盖（`md5 7d3d662d953a8009248ac1698a92177b`，mtime 16:51:11 未变） |
| Maven | 一次都没跑 |
| 停服务方式 | 全程按 **PID**（`proc.terminate()`），**没有用过 `pkill -f`** |
| 磁盘 | 本目录 **672 MB**（含两个 store 副本 625 MB；tp 原始日志 470 MB → 压缩后 6 MB）；`/dev/sdd` 余量 2.8 GB；tp 原始日志 md5 `2ac49a94ed406c953dde046e143fdc2b`（压缩前，1,156,689 行）记在 `results/md5s.txt` |

### 证据文件索引（都在本报告目录内）

| 文件 | 内容 |
|---|---|
| `evidence/perhex-day0.txt` / `perhex-sw-day600.txt` | sw 逐格（产业/CATTLE/城镇人口/merchant 家户）时点快照 |
| `evidence/perhex-tp-day0-day600.txt` | tp 逐格同上（day0 + day600） |
| `evidence/perhex-demand.md` / `tp-replay-perhex-demand.md` | 逐**发货格**运力需求/供给（本期） |
| `evidence/perhex-capacity-pool.md` / `evidence/tp-replay-perhex-capacity-pool.md` | 逐格运力池（本期） |
| `evidence/perhex-capacity-timeseries.csv` / `evidence/tp-replay-perhex-capacity-timeseries.csv` | 逐格逐轮 capacityMilli（时间序列原始点） |
| `evidence/perhex-buyer.md` / `evidence/tp-replay-perhex-buyer.md` | 逐**买方格**派生需求 |
| `evidence/rejections.md` / `evidence/tp-replay-rejections.md` | 具名业务拒绝 + 车道明细 |
| `evidence/market-rounds.md` / `evidence/tp-replay-market-rounds.md` | 逐轮 MARKET 汇总 + 本期合计 |
| `evidence/merchant-and-migration.md` / `evidence/tp-replay-merchant-and-migration.md` | 承运逐笔 + 迁移/排序表/服务对账 |
| `evidence/perhousehold-pool.md` / `evidence/tp-replay-perhousehold-pool.md` | 逐户运力池条目（谁在卖、卖多少） |
| `evidence/pool-round-summary.md` | 逐轮全局池汇总（含"37/38 无服务货"的恒定读数） |
| `scripts/{haul_run.py,analyze.py,shell-sw.json,shell-tp.json}` | 本轮装置（md5 见 §1.3） |
| `results/{sw-replay,tp-replay}.result.json` | 运行元数据（基态 md5、逐日耗时、失败点） |
| `results/sw-replay.log` / `results/tp-replay.log.gz` | 过滤日志（分析用的原始字节） |
| `results/sw-replay2.full.log` | sw day690 崩溃的**未过滤**日志（含异常原文） |
