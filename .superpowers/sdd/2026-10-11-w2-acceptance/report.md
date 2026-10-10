# W2 验收报告 —— 市场机制本体（债务式清算 + §2.1 动态价 + 生产方式内含口粮 + S-11 扣产能）

- **角色**：W2 验收 Agent（**只读验收** —— 不改仓库任何源码/测试/文档；装置、store、日志全落 `/tmp/w2acc*`）
- **被验对象**：`893dc088`（HEAD）—— `feat(economy): W2 市场机制本体 …`
- **时间**：2026-10-11 04:27 – 05:00（CST）
- **判据来源**：设计书 `docs/superpowers/specs/2026-10-10-unified-price-and-ordered-settlement-design.md` **v3.9** §2.1 / §6.1 W2 行 / §6.2 G1–G7；本任务书 §1 的 8 项
- **一句话结论**：**1① 绿、1② 绿、1③ 部分（0 价成交✅ / 0 价跨格运费照付未观测）、1④ 绿、2 绿、3 绿、4 绿（但行数口径与任务书预期不符：600 天只有 10 行）、5 红（three-powers 被本批新引入的 E14 回归卡在 day 540）、6 绿、7 绿、8 部分（货币/债务精确；货物只能做单边核对）**。
  **另有一条必须处置的真缺陷：three-powers 在世界第 540 天被 400 拒绝，W1 基线（`50e9817e`）同世界跑 600 天一次推进成功 ⇒ 本批引入的回归（§3 D1）。**

---

## 1. 装置

### 1.1 两个 jar（都是从对应提交**新构建**的，`tools/mvn-lock.sh -DskipTests package -pl simos-app -am`）

| | 改前（W1 基线） | 改后（W2，被验对象） |
|---|---|---|
| 代码 | `50e9817e`（worktree `/tmp/w2acc-pre`；= W2 的代码父提交，中间 5 个提交纯文档） | `893dc088`（主工作树 HEAD） |
| 构建日志 | `/tmp/w2acc/evidence/build-pre.log`：BUILD SUCCESS，16/16，**27.1 s** | `/tmp/w2acc/evidence/build-post.log`：BUILD SUCCESS，16/16，**3.2 s** |
| shaded jar | `/tmp/w2acc-pre/simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar` | `simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar` |
| **md5** | **`a90e11a9ec533010a8b8b568efdad067`** | **`c8e64c97c9fc061f4b582b56c6ba05ac`** |

> 主工作树没有在跑的服务（开工 `pgrep -af java` 只有自己的 shell）；`run-shaded.sh` 把 jar 复制成进程独占快照 ⇒ **从未覆盖任何运行中 jar**。

### 1.2 启动命令（所有运行同一形态）

```bash
bash tools/run-shaded.sh <shaded.jar> \
  --store /tmp/w2acc/store-<tag> --world=<small-world|three-powers> \
  --config /tmp/w2acc/shell*.json --gui-port P --mcp-port P+1 --approval-port P+2
# 日志档（JAVA_TOOL_OPTIONS）：-Dsimos.economy.logLevel=INFO|DEBUG [-Dsimos.economy.traceLevel=TRACE]
# 推进：POST /api/advance  {"branch":"main","expectedRevision":R,"from":A,"to":B}
# GM 写口：POST /api/command {"type":"economy.SetMarketZonePriceAlpha","payloadJson":"{...}","branch":"main","expectedRevision":R}
```

四个配置（`checkpointInterval=1` 是为了 `revision % 1 == 0` 每次都落全量 checkpoint 供读数；`-full` 版 = 1000，只为省盘）：

| 文件 | 内容 | md5 |
|---|---|---|
| `/tmp/w2acc/shell.json` | small-world / interval=1 | `7c1a82f8507e27d74287e8d1f558158b` |
| `/tmp/w2acc/shell-full.json` | small-world / interval=1000 | `c6729a689045c2ec168a7f335d8fcb2f` |
| `/tmp/w2acc/shell-tp.json` | three-powers / interval=1 | `8d877ef31c88e149632692d8fe55f767` |
| `/tmp/w2acc/shell-tp-full.json` | three-powers / interval=1000 | `c6e8f196da4b22dfeb7dee656e4157e7` |

### 1.3 装置脚本（全在 `/tmp`，**未落仓**）

| 脚本 | 作用 | md5 |
|---|---|---|
| `/tmp/w2acc/runner.py` | 起服务 → 等 GUI 就绪 → 按 plan 推进/发命令/落 dump → 收进程；含失败路径落盘与 checkpoint 剪枝 | `4a6b22db40a3054ecbef573d684fbb9c` |
| `/tmp/w2acc/events.py` | 日志事件解析（`event=NAME k=v`，**花括号内不误切**） | `0706d5077785072593e370b1b5d4bda3` |
| `/tmp/w2acc/annotate.py` | 给缺 `day` 字段的事件补"当时的天"（时间序推） | `b66c0d4777210772ad715101abcd2375` |
| `/tmp/w2acc/canon.py` | checkpoint → 只含状态的规范串（**剔除各模块 `ref.revision` 戳**）+ 逐叶深比对 | `7a49573291c36f8bdaecb2d18e81dbb6` |
| `/tmp/w2acc/dumpstats.py` | 逐日 dump 汇总（人口/货币/货物/在途/债务/流量/统计簿） | `d6a2ef7ba63917424aa24dbad4099d1b` |
| `/tmp/w2acc/table600.py` | 600 天逐段读数表 | `c6c46dac7ba799da9e345cf903a61b33` |
| `/tmp/w2acc/b2.sh` / `perf.sh` / `probe.sh` / `mkplan.py` / `state.py` | B2 分段等价 / 性能对照 / 单点探测 / plan 生成 / 状态抽读 | `bab7f6c2…` / `0faf7186…` / `64aceb5f…` / `1e3983eb…` / `f3f2fb12…` |

**读数来源（四路，全部是落盘真数，不是推演）**：① `<store>/checkpoints/main/<rev>.json` 全量信封（各 namespace 的状态树原文）；② `revisions` 表（行数/tick/command_type/changeset 字节）；③ 服务进程 stdout 的具名事件日志（INFO/DEBUG/TRACE 三档）；④ HTTP 读口与 `/api/command` 的响应原文。

### 1.4 运行清单（store / 端口；**一次只跑一个世界实例**、一次只跑一个 Maven）

| tag | 世界 | 推进 | store | 端口 | 日志档 | 结果 |
|---|---|---|---|---|---|---|
| `probe-gen` / `probe20` | small-world | 创世 / 0→20 逐日 | `store-probe-gen`/`store-probe20` | 10301 / 10302 | DEBUG | 探路（口径确认） |
| **`p180`** | small-world | **0→180 逐日**（180 次 advance，每日 dump） | `store-p180` | 10310 | DEBUG | 72 轮、181 份日 dump、40 MB 日志 |
| **`t130`** | small-world | **0→130 单次** | `store-t130` | 10320 | INFO+**TRACE** | 36 MB 逐笔日志（成交/还债/介质序） |
| **`sw600seg`** | small-world | **0→600 分 20 段（每 30 天）** | `store-sw600seg` | 10420 | INFO | 600 天逐段读数表 + 守恒 |
| `sw600w2` / `sw720w2` / `sw1000w2` | small-world | 0→600 / 720 / 1000 单次 | 各自 | 10402/10404/10405 | INFO | 全部 200、Δ=1 |
| `sw520w1` | small-world | 0→520（**W1 基线 jar**） | `store-sw520w1` | 10410 | INFO | **400（既存质押缺陷复现）** |
| **`tp600one`** | three-powers | **0→600 单次** | `store-tp600one` | 10330 | INFO | **400 @ day 540（回归）** |
| **`tp600seg`** | three-powers | **0→600 分 20 段** | `store-tp600seg` | 10350 | INFO | 到 day 510 成功、**510→540 段 400** |
| `repro540` | three-powers | 0→539（200）+ 539→540（**400**） | `store-repro540` | 10390 | INFO | **最小复现** |
| `tp-p300/450/525/530/535` | three-powers | 0→300/450/525/530/535 单次 | 各自 | 10340–10362 | INFO | 全 200（把失败日夹到 536–540） |
| `pre600` | three-powers | 0→600 单次（**W1 基线 jar**） | `store-pre600` | 10380 | INFO | **200、Δ=1、24.9 s** |
| `pre1200` | three-powers | 0→1200 单次（**W1 基线 jar**） | `store-pre1200` | 10480 | INFO | **200、Δ=1、60.7 s** |
| **`alpha0`** | three-powers | 0→60 → GM α=0 → 60→120 | `store-alpha0` | 10370 | DEBUG | α=0 钉死价格 ✅ |
| **`b2one` / `b2seg` / `b2daily`** | small-world | 0→360 一次 / 三段 / **逐 tick** | `store-b2*` | 10430/10434/10438 | INFO | 三份 dump **md5 相同** |
| `perf-pre-1..3` / `perf-post-1..3` | small-world | 0→360 单次 ×6（两 jar 交替） | 各自 | 10460–10470 | INFO | 见 §2.7 |

---

## 2. 逐项真数字

### 2.1 价格机制真的按 §2.1 动

**世界**：small-world 创世新 store，α = 出厂缺省 **50‰**（`MarketZone.priceAlphaPerMille` 缺省；small-world 的 `marketZones` 为空 ⇒ 派生市场，读日志 `MARKET_DEMAND_COMPOSITION.alphaPerMille=50` 实测）。
**轮**：`MARKET_ROUND_START` 实测 72 轮 / 180 天（`PERIODIC` 每 5 天 + `LOW_GRAIN_STOCK` 粮紧张额外开市 ⇒ **每 5 天 2 轮**，轮日 = 3,5,8,10,13,15,…）。

#### ① 缺货商品逐轮涨（前 24 轮原文；`D`/`S` = 该轮该区缺口/可上架，毫商品单位）

| 轮日 | 触发 | grain 前→后 | 实现步长 | grain D | grain S | cloth 前→后 | 实现步长 | fiber | tool | iron |
|---|---|---|---|---|---|---|---|---|---|---|
| 3 | LOW_GRAIN_STOCK | 1→2 | +1000.0‰ | 141,406,570 | 81,836,041 | 5→6 | +200.0‰ | **0** | 19 | 9 |
| 5 | PERIODIC | 2→3 | +500.0‰ | 140,175,672 | 79,230,657 | 6→7 | +166.7‰ | 0 | 18 | 8 |
| 8 | LOW_GRAIN_STOCK | 3→4 | +333.3‰ | 139,317,546 | 73,141,862 | 7→8 | +142.9‰ | 0 | 17 | 7 |
| 10 | PERIODIC | 4→5 | +250.0‰ | 134,566,036 | 65,574,999 | 8→9 | +125.0‰ | 0 | 16 | 6 |
| 13 | LOW_GRAIN_STOCK | 5→6 | +200.0‰ | 132,605,433 | 62,093,464 | 9→10 | +111.1‰ | 0 | 15 | 5 |
| 15 | PERIODIC | 6→7 | +166.7‰ | 131,807,537 | 60,214,773 | 10→11 | +100.0‰ | 0 | 14 | 4 |
| 18 | LOW_GRAIN_STOCK | 7→8 | +142.9‰ | 131,588,393 | 58,446,108 | 11→12 | +90.9‰ | 0 | 13 | 3 |
| 20 | PERIODIC | 8→9 | +125.0‰ | 131,495,462 | 57,472,400 | 12→13 | +83.3‰ | 0 | 12 | 2 |
| 23 | LOW_GRAIN_STOCK | 9→10 | +111.1‰ | 131,545,832 | 56,273,430 | 13→14 | +76.9‰ | 0 | 11 | 1 |
| 25 | PERIODIC | 10→11 | +100.0‰ | 131,475,923 | 55,529,617 | 14→15 | +71.4‰ | 0 | 10 | **0** |
| 28 | LOW_GRAIN_STOCK | 11→12 | +90.9‰ | 131,631,007 | 54,521,159 | 15→16 | +66.7‰ | 0 | 9 | 0 |
| 30 | PERIODIC | 12→13 | +83.3‰ | 131,708,428 | 53,877,933 | 16→17 | +62.5‰ | 0 | 8 | 0 |
| 33 | LOW_GRAIN_STOCK | 13→14 | +76.9‰ | 148,906,976 | 59,979,284 | 17→18 | +58.8‰ | 0 | 7 | 0 |
| 35 | PERIODIC | 14→15 | +71.4‰ | 146,523,000 | 56,354,970 | 18→19 | +55.6‰ | 0 | 6 | 0 |
| 38 | LOW_GRAIN_STOCK | 15→16 | +66.7‰ | 145,204,465 | 53,946,234 | 19→20 | +52.6‰ | 0 | 5 | 0 |
| 40 | PERIODIC | 16→17 | +62.5‰ | 144,379,837 | 52,471,702 | 20→21 | **+50.0‰** | 0 | 4 | 0 |
| 43 | LOW_GRAIN_STOCK | 17→18 | +58.8‰ | 143,894,171 | 51,062,381 | 21→22 | +47.6‰ | 0 | 3 | 0 |
| 45 | PERIODIC | 18→19 | +55.6‰ | 143,576,333 | 50,165,067 | 22→23 | +45.5‰ | 0 | 2 | 0 |
| 48 | LOW_GRAIN_STOCK | 19→20 | +52.6‰ | 143,409,891 | 49,165,200 | 23→24 | +43.5‰ | 0 | 1 | 0 |
| 50 | PERIODIC | 20→21 | **+50.0‰** | 143,304,443 | 48,522,261 | 24→25 | +41.7‰ | 0 | 0 | 0 |
| 53–75 | … | 21→31 | +47.6 → +33.3‰ | 143.3M → 145.1M | 47.7M → 43.9M | 25→35 | +40.0 → +29.4‰ | 0 | 0 | 0 |
| 98→118 | … | 40→56 | +50.0 → +37.0‰ | … | … | 48→66 | … | 0 | 0 | 0 |

- **缺货单调涨、连涨 48 轮**（1→56，day 3 → day 118），全程 `D > S`（如 day 13: D=132.6M vs S=62.1M）。
- **「每轮 ≤ α」的精确口径（必须分开读）**：机制步长 `step‰ = min(α, ⌊α·r⌋) ≤ 50‰` 恒成立；**实现价格变动**在 **价格 ≥ 20 毫**的 261 个步里，**涨幅最大恰为 +50.0‰（0 例超 α）**；价格 < 20 毫时实现涨幅可远超 α（1→2 = +1000‰），这**正是 §2.1 v3.5 明文授权的整数最小步进**（`p' = max(p+1, ⌊p(1000+step)/1000⌋)`，α>0 时生效）。
- ★ **跌侧存在"实现跌幅 > α"**：`⌊p(1000−step)/1000⌋` 向下取整 ⇒ p≥20 的 40 个下跌步里有 **12 步实现跌幅超过 50‰**（例：day 120 grain 56→53 = **−53.6‰**、cloth 66→62 = −60.6‰；最大 **−90.9‰**，出现于 p=11、step=50‰ 时 11→10）。机制步长仍 ≤ α，超出量 ≤ 1 毫/轮。列为 §3 D3（口径/实现差，不是"没按公式算"）。

#### ② 滞销商品逐轮跌到 0（同一世界同一 72 轮）

| 商品 | 起点价 | 逐轮路径（轮日） | 触底 | 触底后成交 |
|---|---|---|---|---|
| **fiber** | 1 | day 3 一轮到 **0** | day 3 | `D=0 / S=321,126,000`，之后 0 成交（S 一直挂着） |
| **iron** | 10 | 10→9→8→7→6→5→4→3→2→1→**0**（day 3…25，10 轮） | day 25 | `D=0 / S=200,000` 每轮 |
| **tool** | 20 | 20→19→…→1→**0**（day 3…50，20 轮） | day 50 | 触底后**有 0 价成交**：day 60 `traded=23 avg=0`、day 90 `traded=4 avg=0`、day 120 `traded=7,035 avg=0` |

（一条 0.0 价成交的原文见 ③。）

#### ③ 0 价成交真的发生（small-world TRACE 0→130）

- **0 价成交 118 笔**（`unitPriceMilli=0`），全为 `tool`，发生在 day 53…130（32 个轮日），**Σ 成交量 17,175 毫单位**，`goodsPaymentMilli=0`。原文（`MARKET_FILL`，TRACE）：
  ```
  day=53 from=0,2 to=0,2 commodity=tool seller=hh-0_2-urban-middle_peasant
  buyer=hh-0_2-urban-landless_laborer-displaced quantity=1 unitCurrency=silver
  unitPriceMilli=0 goodsPaymentMilli=0 freightCurrency=silver freightPerUnitMilli=0 freightMilli=0 immediate=true
  ```
- ★ **但 118 笔全是同格（`from == to`）⇒ 运费恒 0，本世界未观测到"0 价 + 跨格运费照付"的成交**（如实记未验）。
- **运费照付的等价现场（另一条通道）**：`goodsPaymentMilli=0`（货款全额赊购）**而运费在现金上真收**的**跨格**成交 **20 笔**，例：
  ```
  day=120 from=0,0 to=-2,0 commodity=cloth buyer=hh--2_0-rural-middle_peasant seller=hh-0_0-rural-middle_peasant
  quantity=2111 unitPriceMilli=66 goodsPaymentMilli=0 freightCurrency=silver freightMilli=19
  ```
  ⇒ "货可以先不付钱（记债），**运费照付**"这条语义**有成交流水证据**；"0 价格 + 跨格运费"这一形态**未观测**（不是被拒，是没发生）。

#### ④ α = 0 时该区价格不动（GM 命令真跑）

装置：three-powers 新 store → `advance 0→60` → `POST /api/command {economy.SetMarketZonePriceAlpha, {marketId:"c-tp-gold", alphaPerMille:0}}` → `advance 60→120`。

- 命令**提交成功**：`revisions` 表第 3 行 = `economy.SetMarketZonePriceAlpha`，tick=60，changeset 1,927 字节；`marketZones.c-tp-gold.priceAlphaPerMille` 由 50 → **0**（另两区仍 50）。
- 结果（day 60 → day 120，同一 60 天窗口）：

| 区 | α | grain | cloth | 窗口内 D≠S 的 区×商品行 |
|---|---|---|---|---|
| `c-tp-gold`（被钉死） | **0** | **25 → 25** | **29 → 29** | **120 / 144**（例 day 63: grain D=84,069,618 vs S=8,448,018；cloth D=177,446 vs S=**0**） |
| `c-tp-copper`（对照） | 50 | 25 → **53** | 29 → **63** | 120 / 144 |
| `c-tp-silver`（对照） | 50 | 25 → **53** | 29 → **62** | 120 / 144 |

⇒ **α=0 = 该区价格不随供需变动**（含"最小步进 +1 毫"也不生效），且同一窗口两个对照区照涨 —— **绿**。

### 2.2 债务式清算真的生效

#### ① "现金 0 的家户仍然拿到货"（逐户样本）

**世界**：small-world `p180`（0→180 逐日，DEBUG）。探针口径：取 `MARKET_DEFERRED_PAYMENT` 里 `paidCashMilli=0` 的笔，再核该户**上一日末钱包一分钱都没有**（`money` 与 `frozenMoney` 全空）。

- **180 天：赊购 754 笔**，其中 `paidCashMilli=0` **647 笔**；**严格样本（前一日末零现金）594 笔**；600 天（`sw600seg`）：**赊购 1,227 笔，`paidCashMilli=0` 1,117 笔**。
- 逐户样本（原文 + 日末账）：

| 天 | 户 | 拿了什么 | 货款 | 现金付 | **记债** | 债权户 | 该户日末现金（前一日末 → 当日末） |
|---|---|---|---|---|---|---|---|
| 25 | `hh-0_0-rural-rich_peasant` | grain 9,089 毫单位 | 91 | **0** | **91**（合同未偿 4,743） | `hh-0_0-urban-landlord` | `{} → {}`（day 24/25/26 三天都是**零现金**，`frozenMoney.silver=0`；grain 1,259,714 → 1,266,803） |
| 8 | `hh-0_0-rural-middle_peasant` | grain 1,211,797 毫单位 | 3,636 | **0** | **3,636** | `hh-0_0-urban-landless_laborer-displaced` | day 7 末 833 → day 8 末 57（同日先有别的支出） |
| 8 | `hh-0_0-rural-middle_peasant` | grain 155,447 毫单位 | 467 | **0** | **467** | `hh-0_0-urban-landlord` | 同上 |
| 30 | `hh-0_0-rural-landlord` | grain 1,535 毫单位 | 19 | **0** | **19** | `hh-0_0-urban-landlord` | `{} → {}` |

原文（day 25）：
```
event=MARKET_DEFERRED_PAYMENT day=25 commodity=grain debtor=hh-0_0-rural-rich_peasant
creditor=hh-0_0-urban-landlord currency=silver quantityMilli=9089 paymentMilli=91
paidCashMilli=0 deferredMilli=91 outstandingMilli=4743 reason=unpaid-goods-price-becomes-debt-to-seller
```
另有一条逐轮汇总（INFO）：`MARKET_DEFERRED_PAYMENTS_SETTLED day=8 fills=5 deferredMilli=11138 debtContracts=89`。

#### ② 债务守恒：**新增债务 = Σ未付价款**（逐日逐值）

口径：`Δ(货币债存量) = stock(day) − stock(day−1)`（`debtContracts` 里 `unit.@class=money` 的 Σprincipal）对 `Σ MARKET_DEFERRED_PAYMENTS_SETTLED.deferredMilli(day)`。

- **非关账日 173 天里 172 天逐值相等**（差 0，一天不差）；
- **唯一不等的 1 天 = day 121**：Δ=+5,000 而 deferred=0 —— 归因为 **`GOV_DEBT_ISSUE day=121 issued=5000 lenders=2`**（政府按 `debtIssuePerCycle=5000` 借入，**不是赊购**）；
- **6 个关账日**（30/60/90/120/150/180）Δ ≠ deferred，差额可**具名拆开**：当日还有 `DEBT_REPAY_ROUND`（还款）与 `interestDue`（利息并入本金，`FlowRow.interestDue` 同日读数，例 day 30 = 53,119、day 180 = 4,362,228）；例 day 30：Δ=−33,989，deferred=+444，`flowRepaidMoney.silver=19,619`、`flowRepaidGrain=4,524,434`、`interest=53,119`。
- 600 天合计：**Σ deferredMilli = 5,938,774**（127 个轮有赊购）；**Σ 还粮 = 268,065,024 毫**（20 个还债轮）。

#### ③ 还债介质按**估值最低优先**（S-5）

介质序的现场读数（TRACE `DEBT_REPAY_MEDIUM_ORDER`，338 条，覆盖 4 个关账日）：

| 关账日 | 户 | mediumOrder（原文顺序） | 当时估值（毫/单位） | 实际付出（`DEBT_REPAY_DECISION`） |
|---|---|---|---|---|
| 30 | `hh--1_-1-rural-middle_peasant` | `[commodity:fiber, commodity:tool, commodity:grain, money:silver]` | fiber **0**（市场价 0）< tool **8** < grain **13** < silver **1000**（本币面值 1:1） | `goodsLegs={tool=1050, grain=8918}` / `moneyLegs={}`；该合同 9,350 全清 |
| 30 | `hh--1_-1-rural-landlord` | `[commodity:fiber, commodity:tool, money:silver]` | fiber 0 < tool 8 < silver 1000 | `goodsLegs={tool=140}` / `moneyLegs={silver=24}`；未偿 1,350 → 36 |
| 30 | `hh--1_-1-rural-poor_peasant` | `[commodity:fiber, commodity:tool, money:silver]` | 同上 | 一笔 `{tool=428}`（清）、一笔 `{tool=797}` + `{silver=197}` |

- 顺序 = `estimatedUnitValueMilli` **升序**：商品按该户价目表（缺省=该市场区价目表）价，**本币 = 1000 毫面值**，说不出价 = 0 ⇒ 排最前；`reserveCommodities=0`（无保留窗口）。
- 与旧口径的**反向对照**：D-030 §4 是"**钱永远第一位** → 商品按数量降序"；实测 **money:silver 恒排最后**。
- ★ 一个必须点名的细节：fiber 估值 0 ⇒ **序排第一，但折算不出价值 ⇒ 实际一件没付**（`goodsLegs` 里没有 fiber，而该户 fiber 库存 6,402,632 毫单位原封不动）。

### 2.3 还债不设保留窗口（用户第六批）

- **"还到见底"样本**（日末账取自 `p180` dump，付出腿取自 `t130` TRACE；两跑的 `DEBT_REPAY_ROUND` 4 行逐字相同，见下）：

| 关账日 | 户 | 还债前（day D−1 末） | 付出腿（原文） | 还债后（day D 末） |
|---|---|---|---|---|
| 30 | `hh--1_-1-rural-middle_peasant` | `tool=1050`、grain 55,889、silver 256、fiber 6,402,632 | `goodsLegs={tool=1050, grain=8918} moneyLegs={}` | **`tool` 键消失（0）**、grain 41,239、silver 247、fiber 6,402,632 |
| 30 | `hh--1_-1-rural-landlord` | `tool=140`、silver 60、fiber 918,947 | `goodsLegs={tool=140} moneyLegs={silver=24}` | **`tool` 键消失（0）**、silver 36、fiber 918,947 |

  ⇒ 库存被**还到见底**（保留量 0），且**货币不在保留之列**（silver 60→36）。
- **"没库存可还 ⇒ 挂账"样本**（同一世界，day 90）：
  ```
  DEBT_REPAY_MEDIUM_ORDER debtor=hh-mig-0_1-handicraft_workshop-hh-0_1-rural-landlord-0 mediumOrder=[] reserveCommodities=0
  DEBT_REPAY_ROUND debtorsWithOutstanding=79 householdsRepaid=28 householdsWithNoStockToRepay=1
                  repaidGrainMilli=8363966 debtContracts=122 reserveCommodities=0 why=v3.6-no-reserve-window (有库存就还、没库存记债)
  ```
  逐户侧 `mediumOrder=[]`（可动用介质一件都没有）与轮侧 `householdsWithNoStockToRepay=1` **两边对上**。
- **600 天 20 个还债轮的完整序列**（`householdsWithNoStockToRepay` / `householdsRepaid` / `repaidGrainMilli`）：

| 关账日 | 30 | 60 | 90 | 120 | 150 | 180 | 210 | 240 | 270 | 300 | 330 | 360 | 390 | 420 | 450 | 480 | 510 | 540 | 570 | 600 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 无货可还 | 0 | 0 | 1 | 0 | 4 | 1 | 8 | 7 | 11 | 11 | 8 | 6 | 8 | 12 | 14 | 14 | 16 | 16 | 16 | 16 |
| 实还户数 | 79 | 40 | 28 | 100 | 57 | 55 | 50 | 83 | 34 | 26 | 20 | 55 | 38 | 19 | 4 | 4 | 2 | 2 | 2 | 2 |
| 还粮(毫) | 4.52M | 6.43M | 8.36M | 35.2M | 13.8M | 36.0M | 17.3M | 72.8M | 8.0M | 9.3M | 0.44M | 10.7M | 0.31M | 14.2M | 14.1M | 14.2M | 2.2M | 0.04M | 0.0009M | 0.04M |

  ⇒ 挂账户从 0 涨到 16（占 94–95 个有债户的 ~17%），且**始终记债、计息、不清零**（合同数：180 天口径 94 → 410）；还债户数从 100 一路降到 2 —— 库存被还空之后**只剩"挂账"这一支**。

### 2.4 S-11 扣产能可观测

**行数（真数）**：`PRODUCTION_CAPACITY_DEDUCTED`（INFO，轮汇总）在 **600 天里只有 10 行**，日子 = **1 / 61 / 121 / 181 / 241 / 301 / 331 / 361 / 421 / 481**（**每个发射日恰 1 行**）；180 天里 3 行（1/61/121）。
`PRODUCTION_CAPACITY_DEDUCTED_DETAIL`（DEBUG）逐 unit：180 天 75 行（day 1: 15、day 61: 1、day 121: 59）。

- ★ **与任务书/账本预期的偏差（必须记）**：任务书要求"INFO 汇总**每轮/每天**恰 1 行、给 **20 天以上**的行数"，W2 账本 §617 也写"每轮/每天一条轮汇总"。**实测不是每轮/每天**：扣产能只发生在**生产投入品抽取阶段**（约每 60 天一次，另有一次 day 331），所以 600 天只有 **10 行**；"20 行以上"在 600 天内**结构上不可能**（需 ~1200 天）。见 §3 D2。
- **真样本**（day 121 原文，最长的一行）：
  ```
  event=PRODUCTION_CAPACITY_DEDUCTED day=121 units=59 households=29 totalPlannedScale=40734
  totalActualScale=9741 totalDeductedScale=30993 maxDeductedPerMille=1000
  scarcestInputs={fiber=17, grain=42}
  why=S-11：实际产能 = 计划产能 × 最短缺投入的满足率（拿不到 = 物理短缺，非因穷）；逐 unit 明细见 PRODUCTION_CAPACITY_DEDUCTED_DETAIL
  ```
  ⇒ **被扣 59 个 unit / 29 户；最短缺投入分布 fiber×17、grain×42；最大扣减比例 1000‰（满足率 0）**；计划规模 40,734 → 实际 9,741（满足率合计 239‰）。
- day 1（部分满足的样本）：`units=15 households=60 totalPlannedScale=32550 totalActualScale=29745 totalDeductedScale=2805 maxDeductedPerMille=86 scarcestInputs={grain=15}` ⇒ 最短缺 grain、最差满足率 **914‰**。
- DEBUG 逐 unit 样本（day 1）：`unit=unit-farm@0_0-… scarcestInput=grain scarcestOnHand=15875000 scarcestRequired=17360000 satisfactionPerMille=914 plannedScale=2170 actualScale=1983 deductedScale=187 perUnit={grain=8000} onHand={grain=15875000}`。

### 2.5 600 天长跑

**世界选择与两个硬事实**（都跑了）：

1. **任务书指定的 three-powers 走不到 600 天**：`advance 0→600` **400 @ day 540**（本批引入的回归，§3 D1）；分段跑同样在 `510→540` 段红。**实际到达 539 天**（`repro540`：0→539 = 200，用时 32.96 s）。
2. **任务书说的"small-world ≥510 天被既存质押缺陷拒"在 W2 下不再复现**：W2 jar `0→520 / 0→600 / 0→720 / 0→1000` **全部 200**（600 天 11.15 s、1000 天 15.37 s，均 Δ=1）。**W1 基线 jar 同世界 `0→520` 仍 400**：
   ```
   OwnershipStakeBook 的 Σ活跃质押必须 ≤ 份额 quantity：
   份额=share-farm@0_2-LAND-HOUSEHOLD-hh--2_2-rural-landlord-…-OWNED-0 质押合计=13，终态数量=12
   ```
   ⇒ 该既存缺陷**在 W2 的新轨迹下不复现**（是否根因已修**未验**，见 §5）。

**因此 600 天读数表用 small-world**（`sw600seg`：0→600 分 20 段×30 天，每次 advance 落一份 checkpoint）：

| 天 | 人口 | 粮存量(毫) | 债 grain(毫) | 债 silver(毫) | 本段还粮(毫) | 本段还钱(silver毫) | 挂账户 | 有债户 | 成交笔数 | grain价 | cloth价 | tool价 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 30 | 4811 | 83,808,215 | 1,702,925 | 689 | 4,524,766 | 19,619 | 0 | 79 | 185 | 13 | 17 | 8 |
| 60 | 4824 | 80,089,062 | 2,046,721 | 1,973 | 6,428,875 | 14,947 | 0 | 80 | 172 | 25 | 29 | 0 |
| 90 | 4834 | 76,761,130 | 2,365,165 | 12,291 | 8,363,966 | 14,945 | 1 | 79 | 180 | 37 | 42 | 0 |
| 120 | 4837 | 516,666,931 | 99,717,844 | 254,470 | 35,231,548 | 15,612 | 0 | 100 | 168 | 53 | 62 | 0 |
| 150 | 4847 | 379,319,908 | 106,827,547 | 804,613 | 13,822,137 | 5,589 | 4 | 100 | 345 | 28 | 30 | 0 |
| 180 | 4857 | 371,264,125 | 127,896,337 | 1,756,264 | 35,979,122 | 774 | 1 | 94 | 154 | 25 | 13 | 0 |
| 210 | 4860 | 362,984,875 | 133,116,933 | 2,256,494 | 17,303,480 | 1,261 | 8 | 97 | 152 | 37 | 1 | 0 |
| 240 | 4879 | 690,888,142 | 177,298,704 | 1,474,105 | 72,769,667 | 1,314 | 7 | 99 | 222 | 46 | 0 | 0 |
| 270 | 4895 | 561,595,760 | 172,768,629 | 1,893,621 | 8,022,801 | 5,000 | 11 | 99 | 223 | 20 | 0 | 0 |
| 300 | 4915 | 555,640,082 | 177,813,850 | 2,051,830 | 9,314,223 | 213 | 11 | 95 | 119 | 8 | 0 | 0 |
| 330 | 4920 | 536,520,449 | 182,506,511 | 2,274,859 | 435,989 | 305 | 8 | 92 | 186 | 0 | 0 | 0 |
| 360 | 4930 | 662,997,490 | 198,569,143 | 2,320,283 | 10,739,499 | 304 | 6 | 92 | 272 | 0 | 0 | 0 |
| 390 | 4942 | 610,036,591 | 203,867,323 | 2,366,723 | 311,234 | 5,010 | 8 | 93 | 320 | 0 | 0 | 0 |
| 420 | 4958 | 612,892,515 | 241,852,479 | 2,413,837 | 14,222,253 | 191 | 12 | 92 | 210 | 0 | 0 | 0 |
| 450 | 4968 | 608,164,982 | 234,203,713 | 2,462,062 | 14,124,338 | 18 | 14 | 92 | 199 | 0 | 0 | 0 |
| 480 | 4985 | 605,132,440 | 224,931,583 | 2,511,217 | 14,156,901 | 17 | 14 | 94 | 172 | 0 | 0 | 0 |
| 510 | 5006 | 593,547,313 | 227,492,689 | 2,561,510 | 2,227,273 | 5,017 | 16 | 95 | 225 | 0 | 0 | 0 |
| 540 | 5014 | 591,452,738 | 232,349,900 | 2,612,609 | 44,544 | 116 | 16 | 95 | 216 | 0 | 0 | 0 |
| 570 | 5021 | 575,765,371 | 237,382,567 | 2,664,828 | 889 | 1 | 16 | 95 | 228 | 0 | 0 | 0 |
| 600 | 5025 | 573,249,436 | 242,496,058 | 2,718,089 | 41,519 | 0 | 16 | 94 | 214 | 0 | 0 | 0 |

- 600 天共 **240 个市场轮、4,162 笔成交**；价格区间：grain 1→56→0、cloth 5→66→0、tool 20→0、fiber/iron 一轮/十轮触底 0；末期三商品**全部 0 价（白送）**。
- 债务存量（grain 计）1.70M → 242.5M 毫；货币债 689 → 2,718,089 毫；还债总粮 268.1M 毫；挂账户 0 → 16。
- 逐段读数表脚本 `table600.py`（md5 `c6c46dac…`），原始逐段 JSON 落 `/tmp/w2acc/table600-sw600segd.json`。

### 2.6 B2 分段等价（不许丢）

同一世界（small-world，`checkpointInterval=1`）、同一命令、同一 jar，三次推进到 **tick = 360**：

| 跑法 | advance 次数 | 末 revision | 耗时 | 规范状态 md5（**剔除各模块 `ref.revision` 戳**） |
|---|---|---|---|---|
| `0→360` 一次 | 1 | 2 | 6.522 s | **`d2b232c5a881af455537c71397c97c22`** |
| `0→120→240→360` 三段 | 3 | 4 | 7.290 s（2.836/2.352/2.107） | **`d2b232c5a881af455537c71397c97c22`** |
| **逐 tick（360 次 advance）** | 360 | 361 | 28.85 s | **`d2b232c5a881af455537c71397c97c22`** |

- **差异叶数 = 0 / 61,986 叶**（三份两两比对）；唯一原始差异是每个模块载荷里的 `ref.revision.value`（2 / 4 / 361），那是"写在哪个 revision"的戳、不是状态（剔除后三份逐字节相同）。
- 逐 tick 跑的 checkpoint 用剪枝只留最新一份（`--prune`），不影响可比性。
- ⇒ **G3 的 B2 红利未丢**。

### 2.7 性能红线

**(a) Revision Δ**：`advance` 一次 = 一次落盘，**不随天数增长**：

| 命令 | Δ | 行数（revisions 表） | 耗时 |
|---|---|---|---|
| small-world `0→360` 单次 | **1** | 创世 + 1 | 6.522 s |
| small-world `0→600` 单次 | **1** | 创世 + 1 | 11.150 s |
| small-world `0→1000` 单次 | **1** | 创世 + 1 | 15.373 s |
| three-powers `0→539` 单次 | **1** | 创世 + 1 | 32.956 s |
| small-world `0→120→240→360` 三段 | 3（=推进次数） | 创世 + 3 | 7.290 s |
| small-world 逐 tick 360 次 | 360（=推进次数） | 创世 + 360 | 28.85 s |

**(b) 同世界改前/改后耗时对照**（small-world，`advance 0→360` 单次，两 jar 交替各 3 轮，同 config 同机同时段）：

| 侧 | jar | 三次（s） | 中位 | 最小 | 最大 | Δ |
|---|---|---|---|---|---|---|
| 改前 `50e9817e` | `a90e11a9…` | 7.297 / 7.158 / 7.115 | **7.158** | 7.115 | 7.297 | 1 |
| 改后 `893dc088` | `c8e64c97…` | 6.650 / 7.820 / 6.622 | **6.650** | 6.622 | 7.820 | 1 |

⇒ **中位 −7.1%**（更快），最大单次 +7.2%；样本内波动 ±9% 量级 ⇒ **无性能红线的劣化**（同世界可比，两侧同一命令行/同一 store 形态）。参考：W1 验收报的 small-world 中位 7.514 s（其"改后"侧），本批 6.650 s —— 同一量级。

**(c) 结构证据（新路径无存盘）**：
- `git show 893dc088 -- simos-economy/src/main simos-app/src/main | grep -E '^\+.*(save|persist|checkpoint)'` = **0 命中**；
- `MarketSettlement.java` / `EconomySettlement.java`（新路径两个主文件）里 `\b(save|persist|checkpoint)\b` = **各 0 命中**；`simos-economy/src/main` 全域命中只有 `EconomyCodec` 的注释与 `WorkingDayView` 的"我不落盘"声明。

### 2.8 守恒（三个守恒式的核对结果）

**(1) 货币总量（Σ家户 + 国库 + 在途）：精确对上 ✔**

口径：`actor` 切片全部账户的 `money` + `frozenMoney`（在途货币：结构上不存在，`shipments` 只运货；`frozenMoney` 实测恒 0）。

| 币 | day 1 | day 30 | day 150 | day 270 | day 390 | day 510 | day 600 |
|---|---|---|---|---|---|---|---|
| silver（含国库） | 269,200 | 269,200 | 271,200 | 273,200 | 275,200 | 277,200 | **277,200** |
| copper | 100,000 | 100,000 | 100,000 | 100,000 | 100,000 | 100,000 | **100,000** |

核对式：`269,200 + 2,000×4（GOV_SEIGNIORAGE day 121/241/361/481，amount=2000，原文见日志）= 277,200` ✔；
`GOV_DEBT_ISSUE`（5 次 × issued=5,000）= 债权人→国库的**转移**，总量不变 ✔（逐条原文：`day=121 target=5000 issued=5000 lenders=2 treasuryBefore=3900 treasuryAfter=8900`）。
⇒ **除铸币（W5 的口子）外，货币总量守恒**；这正是"S-3 铸币仍是直接加进国库户"的既存形态（W5 处置），**不是本批缺陷**。

**(2) 债务（新增 = 还清 + 存续 + 违约/灭失）：精确对上 ✔（非关账日逐值；关账日具名拆项）**

- 非关账日 **172/173 天** `Δ存量 == Σ deferredMilli`（**差 0**）；唯一例外 = day 121 的 `GOV_DEBT_ISSUE +5,000`（具名）。
- 关账日：`Δ = deferred − 已还本金 + 资本化利息`，三项都有当日读数（例 day 150：`deferred=12,769`、`flowRepaidGrain=13,734,232`、`flowRepaidMoney.silver=589`、`interest=2,113,442`）。
- **违约/灭失 = 0**：`debtContracts` 只增不减（94 → 410），`DEBT_REPAY_ROUND` 的 `why=v3.6-no-reserve-window (有库存就还、没库存记债)`，**没有任何清零/核销事件**（全日志 `grep -iE 'writeoff|default|charge-off'` 0 命中）。挂账 16 户继续计息 ⇒ "违约/灭失"这一支**在本世界为 0**。

**(3) 货物：单边核对通过（无凭空造物），但**无法用现有读数面把"产出 = 消费 + 损耗 + 存量增"闭合**（读数面缺口）**

- **负余额扫描**：181 份日 dump × 178 账户 × 4 个余额表（`balances`/`frozenBalances`/`money`/`frozenMoney`）= **0 处负数** ✔
- **逐日 ΔΣ货物（grain+fiber+cloth+tool+iron+haul，含在途）**：179 个日差里 **177 天 Δ ≤ 0**（中位 **−155,523 毫**，即当日消费/损耗），**只有 2 天为正**：day 120 **+522,651,782**（收获）、day 180 +107,262（收获）。
  `Σ正 = 522,759,044`（= 生产）− `Σ负 165,195,431`（= 消费/损耗）= **+357,563,613** = 实测 `期末 − 期初` ✔ ⇒ **除收获日外没有凭空造物**。
- ★ **闭合失败的那一支（读数缺口，如实记）**：day 120 收获日实测 `+442,665,098`（grain 部分），而同日 `FlowRow.income.grain` 只有 **78,879,504**（差 5.6×）⇒ **`flows.income` 不是完整产出账**；同时**没有"损耗"读数面**（全日志无 LOSS 事件、状态里无损耗账户）⇒ 三式中的"货物"只能做到上面两条**单边**核对。详见 §3 D7。

---

## 3. 缺陷 / 异常清单

> 纪律：**只报不修**。每条给最小复现 + 证据。

### D1 ★★★【回归·红】three-powers 在世界第 540 天被 fail-closed 契约错误 400 拒绝（本批引入）

- **最小复现**（`repro540`，两段式，一次成功一次失败，日内定位到天）：
  ```bash
  bash tools/run-shaded.sh simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar \
    --store /tmp/w2acc/store-repro540 --world=three-powers --config /tmp/w2acc/shell-tp-full.json \
    --gui-port 10390 --mcp-port 10391 --approval-port 10392
  curl -s -X POST .../api/advance -d '{"branch":"main","expectedRevision":1,"from":0,"to":539}'   # → 200（32.96 s）
  curl -s -X POST .../api/advance -d '{"branch":"main","expectedRevision":2,"from":539,"to":540}' # → 400（0.166 s）
  ```
- **400 原文（全文）**：
  ```
  规则指名的商品不在该产业的产出表里（E14）：
  activity=unit-trade@-3_3-HOUSEHOLD-hh-mig--3_3-tenancy_cash-hh--3_3-urban-middle_peasant-0
  commodity=grain 该产业的 outputPerUnit=[haul] —— 静默付 0 是本仓最反对的形态，故当场抛
  （规则的商品要么改、要么改 relation）；
  规则=CompensationRule[type=FIXED_IN_KIND_RENT, recipient=ToHousehold[household=hh--3_3-urban-landlord],
  pool=FIXED_AMOUNT, weight=NONE, ratePerMille=0, fixedAmount=20000000,
  commodity=Optional[grain], currency=Optional.empty, priority=10]
  ```
- **是哪一格/哪一户/哪个量**：格 `(-3,3)`；户 `hh-mig--3_3-tenancy_cash-hh--3_3-urban-middle_peasant-0`（迁入 `tenancy_cash` 生产方式的家户）；量 = `FIXED_IN_KIND_RENT` 实物租 **grain 固定 20,000,000 毫** 付给 `hh--3_3-urban-landlord`，但该 activity 的 `outputPerUnit` 只有 `[haul]` ⇒ 规则指名的 grain 不在产出表里（fail-closed 抛，不静默付 0）。
- **期望 vs 实际**：期望"600 天跑完（W1 基线能跑 3600 天）"；实际"**day 539 完成、day 540 当场 400、状态不落 revision**"。
- **第一次分叉的天数 = 540**（日志证据：`results/tp600seg.log` 最后的事件序列是 `… day=539 TAX_DAILY_END / GOV_DAILY_END … day=540 POPULATION_SETTLE_START / PRODUCTION_MODIFIER_INJECTED / DAY_START / LABOR_QUEUE / HAUL_SERVICE_EXPIRED`，随后 `GUI_ACCESS POST /api/advance status=400`；单次 0→535/536…539 全 200、0→560 与分段 `510→540` 均 400）。
- **是不是本批引入 ⇒ 是（同世界同命令对照）**：

| 侧 | jar | 命令 | 结果 |
|---|---|---|---|
| 改前 `50e9817e` | `a90e11a9…` | three-powers `0→600` 单次 | **200、Δ=1、24.90 s** |
| 改前 `50e9817e` | `a90e11a9…` | three-powers `0→1200` 单次 | **200、Δ=1、60.7 s**（改前跑到 1200 天也不触发） |
| 改后 `893dc088` | `c8e64c97…` | three-powers `0→600` 单次 | **400 @ day 540**（33.5 s 后失败） |

  ⇒ 该世界**在改前可推进到 600 天**，改后卡在 540。**W2 之后 W3–W6 的"600 天真实世界"判据在本世界不可执行**，必须处置（本批只报不修）。
- **归因（重要，决定修法）**：W2 的 diff **一行都没碰**这条路径 ——
  `git show 893dc088 | grep -E '^[+-].*(tenancy|FIXED_IN_KIND_RENT|CompensationRule|outputPerUnit|unit-trade)'` = **0 命中**；
  这些符号的落点是 `ProducerCostBook` / `ProductionSettlement` / `LaborSourcePolicy` / `ModeMigrationSettlement` / `DefaultProductionModes`。
  ⇒ 这是**间接回归**：W2 改了"生产方式内含口粮/生产花费"后，世界轨迹走到 day 540 时第一次出现"迁入 `tenancy_cash` 的家户 + 那条 `FIXED_IN_KIND_RENT(grain)` 租约"的组合，而这个组合**本来就与 `outputPerUnit=[haul]` 不相容**（既存的规则/产出不一致），fail-closed 当场抛出。
  修法因此不是"回退 W2"，而是**判定该租约规则与产出表谁对**（E14 要求"规则的商品要么改、要么改 relation"）。
- 附带：本批提交信息里的"未跑 600 天真实世界验收"正好落在这一段；W2 账本把 three-powers 的健康度结论建立在 `EconomyCycleHealthTest` 的 240 天上。

### D2 【文档 vs 实现】`PRODUCTION_CAPACITY_DEDUCTED` 的发射频率被高估 ~60 倍

- 任务书要求"INFO 汇总**每轮/每天恰 1 行**、给 **20 天以上**的行数"；W2 账本 §617 写"**每轮/每天**一条轮汇总"。
- **实测**：600 天 **10 行**（day 1/61/121/181/241/301/331/361/421/481，每个发射日恰 1 行）；180 天 **3 行**。原因是它只在**生产投入品抽取阶段**（约每 60 天 / 另加 day 331）发射，且只在真发生扣减时发射。
- ⇒ "20 天以上的行数"在 600 天内**结构上不可能**；`PRODUCTION_CAPACITY_DEDUCTED_DETAIL`（DEBUG）同样只在那 3/10 天出现。
- 影响：以"每天一条"为前提的巡检/对账脚本会以为"这些天没扣产能"，实际是"这些天这个阶段没跑"。

### D3 【口径 vs 实现】跌侧实现跌幅可超 α（floor 伪影，≤1 毫/轮）

- §2.1：`p' = max(0, ⌊p(1000−step)/1000⌋)`，`step = min(α, ⌊α·r⌋) ≤ α`。**机制步长 ≤ α 恒成立**；但**实现价格变动**因向下取整可 > α：
  - p≥20 的 40 个下跌步里 **12 步超 50‰**，例 day 120 grain 56→53 = **−53.6‰**、cloth 66→62 = **−60.6‰**；全样本最大 **−90.9‰**（p=11 → 10）。
  - 涨侧在 p≥20 的 261 个步里**最大恰 +50.0‰、0 例超 α**；p<20 的涨侧因"整数最小步进 +1 毫"必然超 α（1→2 = +1000‰）——**这是 §2.1 v3.5 明文授权的**，不算缺陷。
- ⇒ 若用户的"**每轮 ≤α**"指**实现价格变动**（而不是公式里的 `step‰`），则**跌侧违反**，且**"触底 0"那一步**（1→0）永远是 −1000‰。建议在 §2.1 明写"α 约束的是机制步长；实现变动受整数取整影响，单轮最多多 1 毫"。

### D4 【读数口径】统计簿"成交额"只数现金 ⇒ 债务式清算下严重低估市场

- `MarketReport.Fill.goodsPaymentMilli` = **实付货款（现金腿）**，未付部分进债务不计入成交额（代码注释自述："统计簿的成交额因此只数真钱"）；`MarketRoundStats.lastRoundTurnoverMilli` 与 `MarketSettlement:2128` 都用它。
- 实测低估幅度（p180，同一轮）：

| 天 | grain 成交量(毫单位) | 均价(毫) | 名义货值≈ | 统计簿 `lastRoundTurnoverMilli` | 覆盖率 |
|---|---|---|---|---|---|
| 8 | 4,595,854 | 3 | 13,787 | 2,681 | 19% |
| 30 | 43,591 | 12 | 523 | 91 | 17% |
| 60 | 51,718 | 24 | 1,241 | 38 | 3% |
| **120** | **16,017,382** | **56** | **896,973** | **1,543** | **0.17%** |
| 180 | 19,296 | 24 | 463 | 0 | 0% |

- 商品级 `tradedMilli`（量）与 `averagePriceMilli`（名义价）**不受影响**，只有"成交额/换手"这一栏受影响。
- **下游后果**：W1 的交割物里"上周期成交额"被指定为 **W4 的市场区排序量**（§6.1 W4 行、S-4）⇒ 在几乎全赊购的世界里，区排序会退化成"谁付过现金"。建议 W4 排序量改用 `Σ(量×价)` 或显式声明用现金口径。

### D5 【日志面】三条还债事件**都没有 `day` 字段**

- `DEBT_REPAY_ROUND`（INFO）、`DEBT_REPAY_DECISION`（TRACE）、`DEBT_REPAY_MEDIUM_ORDER`（TRACE）实测原文里均无 ` day=`（`grep -c ' day='` = 0/0/0）。
- 影响：还债现场无法按天直接检索/对账（本报告只能用"日志时间序的最近一个 `day=`"推，脚本 `annotate.py`）。
- 对照：同批新增的 `PRODUCTION_CAPACITY_DEDUCTED` 带 `day`，`MARKET_DEFERRED_PAYMENT` 也带。

### D6 【既知未收窄】`ALGORITHM_UNCOVERED` 兜底归因档占比很大

- 600 天 `MARKET` 轮汇总的未成交原因分布（笔数）：**`ALGORITHM_UNCOVERED` 53,080**、`UNSOLD_SELF_USABLE` 21,541、`LOGISTICS_CAPACITY` 9,924、`OUTCOMPETED` 4,870、`NO_ADJACENT_SUPPLY` 4,230、`NO_BUYER` 3,097、`ALL_RESERVED` 728。
- 前 30 天为 0；**最后 30 天 3,457 笔**（占该窗口未成交 4,799 的 72%）⇒ 末期"未成交"读数基本不可解释。
- W2 提交信息已把它列为未做项（"`ALGORITHM_UNCOVERED` 兜底归因档待收窄"），此处给量化。

### D7 【读数面缺口】货物守恒无法闭合

- 实测：day 120 收获日 grain 存量 **+442,665,098**，而 `FlowRow.income.grain` 只有 **78,879,504**；**没有任何"损耗/服务消耗/作废"的持久读数**（全日志无 LOSS 类事件；状态里无损耗账户）。
- 影响：§6.2 的守恒判据里"货物"这一支**只能在单边意义下核对**（见 §2.8(3)：无凭空造物、无负余额），**不能**给出"产出 = 消费 + 损耗 + 存量增"的完整对账。
- 建议（供 W6/后续批）：把 `ProductionLedger` 的"产出/投入/损耗"三个账在周期末落一条轮汇总 INFO（现有 `HARVEST` 行只有计数、`gross/losses/inputs` 恒 0，读不出量）。

### D8 【既存·W2 下不复现】small-world ≥510 天的 `OwnershipStakeBook` 质押缺陷

- **W1 基线 jar 复现**（`sw520w1`，small-world，单次 `0→520`）：
  ```
  400 OwnershipStakeBook 的 Σ活跃质押必须 ≤ 份额 quantity：
  份额=share-farm@0_2-LAND-HOUSEHOLD-hh--2_2-rural-landlord-…-OWNED-0 质押合计=13，终态数量=12
  ```
- **W2 jar 同命令**：`0→520 / 600 / 720 / 1000` **全部 200**（1000 天 15.37 s，Δ=1）。
- ⇒ 该既存缺陷在 W2 的新轨迹下**不再触发**；是"根因已修"还是"轨迹绕开"**未验**（判定需要读 `OwnershipStakeBook` 的质押写口，超出只读验收的时间预算）。

### D9 【未观测·非缺陷】"0 价 + 跨格运费照付"这一形态没有样本

- small-world 130 天里 0 价成交 118 笔**全部同格**（运费恒 0）；跨格成交只出现在 cloth（42 笔，41 笔有运费），而 cloth 从未跌到 0。
- 语义上成立的替代证据见 §2.1③（20 笔"货款全额赊购（`goodsPaymentMilli=0`）+ 跨格运费现金照付"）。
- 处置建议：three-powers 的 `grain` 在 day 450 已是 0 价且有成交（`c-tp-copper` day 450: `tradedMilli=1069 averagePriceMilli=0`），若需要该样本，可在能跑通 600 天的世界（本批被 D1 挡住）上开 TRACE 复取。

---

## 4. 结论（逐项 绿/红/未验）

| # | 判据 | 结论 | 一句话依据 |
|---|---|---|---|
| 1① | 缺货逐轮涨、每轮 ≤ α | **绿（附 D3 口径注）** | 72 轮里 grain 连涨 48 轮 1→56、cloth 5→66；p≥20 时实现涨幅最大 **恰 +50.0‰**、0 例超 α；p<20 的 +1000‰ 是 §2.1 v3.5 授权的最小步进；**跌侧**实现跌幅可超 α（≤1 毫/轮，D3） |
| 1② | 滞销逐轮跌到 0 | **绿** | fiber 1 轮触底、iron 10 轮、tool 20 轮，全部到 **0** 并停留（tool 触底后还有 0 价成交） |
| 1③ | 0 价成交真的发生 | **部分（绿/未验各半）** | **绿**：118 笔 `unitPriceMilli=0` 成交（tool，day 53–130，Σ17,175 毫单位）；**未验**：0 价**跨格**成交（运费照付）本世界无样本 —— 替代证据 = 20 笔"货款全赊（goodsPayment=0）+ 跨格运费现金 19 毫" |
| 1④ | α=0 该区价格不动 | **绿** | GM 命令真提交（rev 3）；`c-tp-gold` α=0 后 60 天内 144 行里 120 行 D≠S，价格 **25→25 / 29→29** 纹丝不动；两对照区 25→53 / 29→63 |
| 2① | 现金 0 的家户仍拿到货 | **绿** | 180 天 **594 个严格样本**（前一日末钱包全空 + `paidCashMilli=0`）；逐户原文如 day 25 `hh-0_0-rural-rich_peasant` 拿 grain 9,089 / 记债 91（该户 day 24/25/26 均零现金） |
| 2② | 债务守恒（新增 = Σ未付价款） | **绿** | **172/173 个非关账日逐值相等（差 0）**；唯一例外 day 121 = `GOV_DEBT_ISSUE 5000`（具名）；关账日差额由 `还款 + 资本化利息` 具名拆开；违约/灭失 = 0 |
| 2③ | 还债介质按估值最低优先 | **绿** | 介质序原文 `[fiber, tool, grain, silver]`（估值 0 < 8 < 13 < **1000**），**货币恒最后**（旧口径"钱永远第一"已反转）；实际付出腿 = `{tool=1050, grain=8918}`；0 估值商品序最前但折不出价值故实付 0 |
| 3 | 还债不设保留窗口 + 无货挂账 | **绿** | `reserveCommodities=0`（每条都写 `why=v3.6-no-reserve-window`）；两户 **tool 库存还到 0**（1050→0 / 140→0），货币也还（60→36）；day 90 `mediumOrder=[]` 的户 ⇔ `householdsWithNoStockToRepay=1`；600 天挂账 0→16 |
| 4 | S-11 扣产能可观测 | **绿（行数口径不符，见 D2）** | 真有行且**每发射日恰 1 行**：600 天 **10 行**（day 1/61/121/181/241/301/331/361/421/481）；样本 day 121 `units=59 households=29 scarcestInputs={fiber=17, grain=42} maxDeductedPerMille=1000`；**但"每轮/每天 1 行、20 天以上"不成立**（阶段约每 60 天跑一次） |
| 5 | 600 天长跑（three-powers） | **红** | three-powers `0→600` **400 @ day 540**（D1，W1 基线同命令 200）；分段同样卡在 510→540。**实际到达 539 天**。补：small-world `0→600` 单次 + 分 20 段**跑通**，逐 30 天读数表已给 |
| 6 | B2 分段等价（md5 相同） | **绿** | 一次/三段/逐 tick 三份 dump 规范状态 md5 全 = **`d2b232c5a881af455537c71397c97c22`**，**差异叶 0 / 61,986** |
| 7 | 性能红线（Δ=1、耗时、无存盘） | **绿** | `0→360/600/1000` 单次 **Δ=1**（三段 Δ=3、逐 tick Δ=360，均 = 推进次数）；同世界两 jar 交替：中位 **7.158 → 6.650 s（−7.1%）**，Δ 均 1；新路径 `save/persist/checkpoint` **0 命中** |
| 8 | 守恒（货币/货物/债务） | **部分** | **货币精确**（269,200 + 2,000×4 = 277,200；copper 100,000 恒定；国库债发行是转移）；**债务精确**（见 2②）；**货物只能单边**：0 处负余额、177/179 天 Δ≤0（Σ生产 522,759,044 − Σ消费/损耗 165,195,431 = 期末−期初），但**没有完整产出/损耗读数**故无法闭合（D7） |

**总评**：W2 的**机制本体（价格、债务式清算、还债序、S-11、日志）在 small-world 上逐条可验且数字自洽**；**但 three-powers 在 day 540 出现本批引入的 fail-closed 回归（D1），使"600 天真实世界"这一全局判据在世界选择上被卡死** —— 这条不处置，W3–W6 的 600 天验收无法开工。

---

## 5. 我没做 / 没验证的（如实）

1. **three-powers 540 天以后**：没跑（被 D1 挡住）。D1 的**根因**没查（只做到"最小复现 + 改前/改后对照 + 第 540 天定位"）；没确认它与"生产方式内含口粮（S-10）改了 mode 花费"的因果链，也没查 `FIXED_IN_KIND_RENT` 规则是谁在第 540 天种下的。
2. **"0 价 + 跨格运费照付"**：**无样本**，未验（D9）。
3. **D=S ⇒ 价格不动**：**本世界 72 轮里 D==S 的（区×商品）轮 = 0 个**，该分支**未验**（只有 `α=0` 与"最低价触底"两个旁证）。
4. **`blockedByPortMilli ≠ 0`（口岸拦下计入 D）**：small-world 无口岸政策，`MARKET_DEMAND_COMPOSITION.blockedByPortMilli` 全 0，**未验**（与 W2 提交信息的自述一致）。
5. **D-030 市场信用撮合删除后的负面证据**：只做了日志面观察（`no_lendable_goods` 档消失、`MARKET_CREDIT_*` 事件 0 命中），**没有**对"卖方实物余量不再被一次搬空"做逐轮量化的前后对照（改前 jar 需要另跑 240 天 TRACE，超预算）。
6. **货物守恒的完整对账**：没做到（D7，读数面缺"产出/损耗"）；只给了单边核对与残差。
7. **`ALGORITHM_UNCOVERED`**：只量化占比（D6），没收窄、没逐笔归类。
8. **性能**：只在 small-world 做了同世界改前/改后（3 轮/侧）；**three-powers 的改前/改后耗时没比**（改后跑不到 600 天，且 0→539 与 0→600 不同目标不可比）；没测内存/GC。
9. **既存质押缺陷（D8）**：只证明"W1 复现 / W2 不触发"，**没有**判定根因是否已修。
10. **单元级/门禁**：本报告**没跑** `clean verify`（本批提交信息自带 16/16 SUCCESS、3409 条、SpotBugs 15/15、前端 412/412；验收只跑真实世界与读数，**未独立复跑门禁**）。
11. **B2 的三份 dump 比较**剔除了各模块载荷里的 `ref.revision` 戳（2/4/361）；**没有**验证"同一 revision 下三份逐字节相同"（那需要同一 revision 号，做不到）。
12. **`MARKET_ROUND_STATS_DETAIL`（DEBUG）** 在本次 600 天跑用的是 INFO 档 ⇒ 该行没采；W1 验收 §7.2 的日志口径缺陷是否真被 W1 收尾批修好，本报告**没复验**。
13. 三层税/运费口径、跨币成交、FX、运输服务成市等 W2 之外的机制**不在本次验收范围**，一行没验。

---

## 附：原始产物位置（全部在 `/tmp`，未落仓）

| 内容 | 路径 |
|---|---|
| 装置脚本（含 md5 表见 §1.3） | `/tmp/w2acc/*.py`、`/tmp/w2acc/*.sh`、`/tmp/w2acc/shell*.json` |
| dump（全量 checkpoint 信封） | `/tmp/w2acc/dumps/{probe-gen,probe20,p180,t130,sw600seg,p2*,b2*,alpha0,tp600seg,...}-*.json` |
| 服务日志（INFO/DEBUG/TRACE） | `/tmp/w2acc/results/<tag>.log`（p180 40 MB、t130 36 MB） |
| 运行结果（revisions / 各段耗时 / dump md5） | `/tmp/w2acc/results/<tag>.result.json` |
| 逐段表 JSON / 逐轮价格 JSON | `/tmp/w2acc/table600-sw600segd.json`、`/tmp/w2acc/p180-rounds.json`、`/tmp/w2acc/stats-p180.json` |
| 构建日志 | `/tmp/w2acc/evidence/build-pre.log`、`build-post.log` |
| 基线 worktree | `/tmp/w2acc-pre`（`50e9817e`，其 shaded jar md5 `a90e11a9ec533010a8b8b568efdad067`） |

**纪律核对**：开工与收尾 `git status --porcelain` 均为空（未改仓库任何源码/测试/文档）；未覆盖任何运行中服务的 jar；一次只有一个世界实例、一个 Maven；端口 103xx/104xx。
