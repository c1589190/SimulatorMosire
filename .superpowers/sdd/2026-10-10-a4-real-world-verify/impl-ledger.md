# A4 真实 world 验收账本 —— A 计划（运输服务商品化：跑商并回标准生产管线）的 world 后果

> 角色：**验证 Agent**（起独立世界 / 读日志；**未改任何代码、测试、文档、pom**；本账本与 `/tmp/a4-*` 是唯一写盘）。
> 日期：2026-10-10。判据来源：`docs/superpowers/specs/2026-10-10-haul-service-commodity-design.md` **v1.3** §5 I-H1..I-H7 / §6 T-H1..T-H5、N-H1..N-H3、§7 A4。
> 批次账本：`.superpowers/sdd/2026-10-10-a{1,2,3}-*/impl-ledger.md`、`2026-10-10-a-tests-haul-commodity/`、`2026-10-10-a-fix-service-provider/`。
> 改前基线：`.superpowers/sdd/2026-10-10-g3f-verify-net-unserved/impl-ledger.md`（其日志/档 `/tmp/g3f-*` 本轮仍只读复用）。
> ★ 一句话结论：**T-H5 成立**（服务真的被产出、被成交、需求可逐笔追到跨格货单；平行机器零残留；工具单套化）；
> ★ **N-H1 不成立/不可构造**：改后 jar 在**无牌价**的真实 store 上仍大面积改变数值（A3 的门槛/趟耗删除），
> 且"缺省世界"已不是无牌价世界（A2 给了全市场 `haul=4`）⇒ 缺省中性只剩"服务机制零动作"这一半。

## 0. 装置与 jar 身份

| 项 | 值 |
|---|---|
| HEAD | `54f932807c081edb74f8a4a2f5f322a01bdc6a3f`（A1 `a15fb3ec` → A2 `4194e90a` → A3 `cb053f1a` → 测试 `ae5698dc`+`1e35855b` → F-1/F-3 `54f93280`），`git status --porcelain` = **0 行**（跑前跑后同） |
| 跑前实例检查 | `pgrep -af java \| grep -i simos` = 仅 **pid 2081**（`/tmp/simos-shaded-NwvHbq.jar`，Oct 9 快照，store `~/simos-runs/2026-10-09-scale-probe`）：`stat -c %i` 显示其 inode **49847** ≠ target jar **373202**；`/proc/2081/cwd = /home/cna/SimulatorMosire` 但 jar 是独立 /tmp 副本 ⇒ **无实例持有 target jar**，未停它、未覆盖共享 jar（`tools/run-shaded.sh` 每进程独占快照） |
| 被测 jar | `simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar`，md5 **`def1cbe49e5d948598ed845a7097f261`**，27,037,081 B，mtime `2026-10-10 18:14:56` |
| jar↔源码 | `find . -name '*.java' -newer <jar>` = **0**；**强制自证**：`rm -rf simos-economy/target/classes simos-app/target/classes` 后重编（Economy 198 + App 328 源文件，16 模块 BUILD SUCCESS），再 `unzip` 出 jar 内 6,900 个 class 与各模块 `target/classes` 逐字节比：**仓内 main class 1,868/1,868 全同、0 不同、0 缺**（其余 5,032 为 agentlib/jackson/reactor 等外部依赖） |
| ★ jar md5 不可跨轮当"代码未变"判据 | 同一份源码两次构建得 **`1bcf2d84…`**（18:14:35 空转构建）与 **`def1cbe4…`**（18:14:56 强制重编）⇒ shade 产物的 md5 随 entry 时间戳变；代码同一性只能由 class 逐字节比（上栏）证明 |
| 装置 A | 原生多市场区（`a4-setup1/2` = g3f 版按端口 sed 到 9415：numeraire/3 区/口岸税/官方汇率/铸 token；`SetMarketNumeraire`=1 佐证、`govMarketMandate authorize` 同基线 **REJECTED**），`setup1 → 0→5 → setup2 → 5→10 → 10→360`；committed rev **11 / 20 / 21**（head 21、tick 360）；store **`/tmp/simos-a4-store-a`**（9.8M）；端口 GUI 9411 / MCP 9415 / 审批 9413；日志 `a4-runA.log` = **160,862,479 B / 454,308 行 / md5 `8240e1dea60e9bc4982490230d9eeba9`** |
| 装置 B | 缺省 `--world=small-world`（`SetMarketNumeraire`=0），**0→360 一次推进**；committed rev **2**、tick 360；store **`/tmp/simos-a4-store-b`**（5.6M）；端口 GUI 9511 / MCP 9515 / 审批 9513；日志 `a4-runB.log` = **139,159,818 B / 377,984 行 / md5 `1d22a76017547bb20a2c9527adce480c`** |
| 装置 N-H1 | 改前 store `/tmp/simos-g3f-store-b`（tick 360，**市场无 haul 牌价**）`cp -a` 两份 ⇒ `nhbase`（改前 jar md5 `f0e97d0a…` = `/tmp/simos-shaded-21yJXu.jar`，9711/9715/9713）与 `nhnew`（改后 jar，9811/9815/9813），各 **360→480**（rev 2→3）；`/tmp/a4-nh-base.log`（46,634,242 B）、`/tmp/a4-nh-new.log`（49,455,764 B） |
| 日志档位 | 全部世界 `-Dsimos.economy.logLevel=DEBUG -Dsimos.economy.traceLevel=TRACE`（与 g3f 基线同档） |
| 纪律 | **一次一个 Maven、一次一个实例**（B → A → B dump；N-H1 base → new 顺序）；未 `git commit`、未改仓内文件；跑完 `pgrep -af simos-a4` = 空，仅余他人 pid 2081 |

## 1. 逐问（命令 + 数字 + 结论）

### Q1 jar 身份 —— 见 §0（md5 `def1cbe4…`；0 个 `.java` 新于 jar；1,868/1,868 class 逐字节同）

### Q2 ★ T-H5 机制是否真的发生 —— **发生，且逐笔可核**

| 读数 | A（原生多区） | B（缺省 small-world） | 命令 |
|---|---|---|---|
| `HAUL_SERVICE_PRODUCED`（INFO，产出） | **3** 行（day 120/240/360；gross 131,000/138,000/105,000；net 127,070/133,860/101,850；Σgross 374,000 **Σnet 362,780**；`pricedMarkets=19`） | **3** 行（Σgross 600,000 **Σnet 582,000**） | `grep -c 'event=HAUL_SERVICE_PRODUCED '` |
| `HAUL_SERVICE_SETTLED`（INFO，成交） | 34 行 / Σ`trades`=**145** / Σ`serviceMilli`=**134,783** / `paidByCurrency={silver=236/232/167}` | 33 行 / Σ`trades`=**121** / Σ读数 182,629（★ 漏报，见 §5-2） | 同上 |
| `HAUL_SERVICE_PAID`（TRACE，**钱腿**） | **145** 笔、Σ`amountMilli`=**635** 毫银 | **121** 笔、Σ=**920** 毫银 | `grep -c 'event=HAUL_SERVICE_PAID '` |
| `HAUL_SERVICE_DELIVERED`（TRACE，**货腿**） | **161** 笔、Σ`serviceMilli`=**134,783** | **141** 笔、Σ=**274,012** | `grep -c 'event=HAUL_SERVICE_DELIVERED '` |
| `MERCHANT_HAUL_RUN`（承运趟） | 48 → **161** | 91 → **141** | 对照 g3f |
| `HAUL_SERVICE_DELIVERY_FAULT(S)`（ERROR） | **0 / 0** | **0 / 0** | 跨切片一致性故障零 |
| `CAPACITY_DEMAND_INSTANCE`（派生需求实例） | **21,614** 条，**21,614/21,614 = 100% 跨格**（`buyerHex != shippingHex`） | **2,112** 条，**100% 跨格** | 逐行解析 |

**派生需求的口径可逐笔核对（0 违反）**：`serviceMilli == ⌈goodsQuantityMilli × workPerGoodPerMille ÷ 1000⌉` 货腿 **161/161（A）+141/141（B）**；`unitFreightMilli == max(1, ⌈w × servicePriceMilli ÷ 1000⌉)` 钱腿 **145/145（A）+121/121（B）**；`demandWorkMilli == ⌈requestedQuantityMilli × w ÷ 1000⌉` 实例 **21,614+2,112 条 0 违反**（`w` 取自同 `(commodity, fromHex, toHex)` lane 的实例，无歧义）。`w` 分布 A = {1015, 2020/2030, 3030/3045, …} = 既有"基础运费 × (1000+路线费率‰)"一族的离散值，**未新造量纲**。

**抽样（A，day 120，lane cloth 0_2→-2_2，一条链完整对上）**：
```
CAPACITY_DEMAND_INSTANCE household=hh--2_2-rural-...-displaced commodity=cloth buyerHex=-2_2 shippingHex=0_2 requestedQuantityMilli=214 servedQuantityMilli=214 workPerGoodPerMille=2030 demandWorkMilli=435
HAUL_SERVICE_DELIVERED day=120 household=hh-0_2-urban-landlord commodity=cloth fromHex=0_2 toHex=-2_2 goodsQuantityMilli=214 serviceMilli=435 stockBeforeMilli=30070
HAUL_SERVICE_PAID      day=120 commodity=cloth from=hh--2_2-... toHousehold=hh-0_2-urban-landlord carrierHex=0_2 fromHex=0_2 toHex=-2_2 amountMilli=2 currency=silver serviceMilli=435 unitFreightMilli=9 servicePriceMilli=4
```
核对：需求 435 = ⌈214×2030/1000⌉ ✓；单价 9 = max(1,⌈2030×4/1000⌉)=⌈8.12⌉ ✓；金额 2 = ⌈(214/1000)×9⌉ = ⌈1.926⌉ ✓（`w=2030=2×(1000+15‰)` = 布基础运费 2 × 1 格路线费率 15‰）。
生产侧抽样（`HAUL_SERVICE_PRODUCED_DETAIL` day=120）：`trade@0_0 grossMilli=100000 haulPerScaleUnit=1 impliedScale=100 laborPerUnit=1000 inputPerUnit={tool=100} marketPriced=true reason=priced`。
⇒ **产出 ✓ / 成交 ✓（钱腿+货腿同额）/ 需求确实由跨格货单派生且与既有运费口径同函数 ✓**。

### Q3 ★ 平行机器零残留 —— **事件名 0 条；`CARRIER_FEE` 在成市世界不再是钱腿**

| 检查 | jar 内 class 命中 | A 日志 | B 日志 | g3f 基线 A/B |
|---|---|---|---|---|
| `MERCHANT_HAUL_TOOL_SHORT_AT_COMMIT` | **0** | **0** | **0** | 0 / 0 |
| `MERCHANT_HAUL_TOOL_BLOCKED_AT_SELECT` | **0** | **0** | **0** | 2,603 / 506 |
| `MERCHANT_HAUL_TOOL_UNPRICED` | **0** | **0** | **0** | 0 / 0 |
| `MERCHANT_PROFIT_TOTAL` / `..._HOUSEHOLD` | **0 / 0** | **0 / 0** | **0 / 0** | 132/242 与 81/174 |
| `MERCHANT_HOUSEHOLDS_LOST_BY_CLONE` | **0** | **0** | **0** | 0 / 0 |
| `CAPACITY_QUOTE_BOOK` | **0**（改用 `CAPACITY_PRICING_ASSEMBLY`） | 0（新名 360） | 0（新名 360） | 360 / 360 |
| `market-merchant-haul`（旧磨损账） | **0** | **0** | **0** | — |
| `toolBurnedMilli` | **0** | **0** | **0** | 每趟 1,000 |
| 退役类 | `MerchantHaul` / `MerchantProfitBook` / `CapacityQuote(Book)` = **0 个 class**；`MerchantCapacityPool` / `LaneUnservedBook` 各 1（降级保留，与 v1.3 §3.4 一致） | | | |

★ **`tool-short` / `tool-frozen` 需说清**：作为**事件名/判据**两世界 0 条；作为**子串**日志里 492（A）/441（B）行 —— 全部落在 `CAPACITY_PRICING_ASSEMBLY`(360) 与 `MERCHANT_CAPACITY_POOL*`(132) 的**自解释栏** `retiredFamilies=merchant-haul-tool-threshold(per-haul-tool-burn+tool-frozen/tool-short),merchant-profit-book(…),capacity-quote-book(…)` 与其 `replacedBy=` 里 ⇒ 是"已退役声明"，不是残留行为（naive `grep` 会误判，特此记账）。

★ **`CARRIER_FEE` 只作读数？** 逐笔 `event=TRANSFER … reason=` 分布：**A 改后 `carrier_fee`=0 / `market_trade`=3,483（基线 48/3,432）；B 改后 `carrier_fee`=0 / `market_trade`=13,995（基线 84/14,110）**；`CARRIER_FEE_PAID` 0 行（基线 48/84）。代码上 `MarketSettlement:6705` 是**同一个三元表达式二选一**（`route.haulService() ? "HAUL_SERVICE_PAID" : "CARRIER_FEE_PAID"`），钱腿本身只有一条 ⇒ **成市世界里"钱腿只有服务成交那一条路"成立、不可能双记（I-H5）**。★ 但如实记：`TransferReason.CARRIER_FEE` 在 jar 里仍在（3 个 class），在**无牌价**世界仍是真钱腿（N-H1 的改后跑 = `carrier_fee` **1,411** 条），所以它不是"纯读数"，而是"**无牌价分支**的路径"——与 A2 账本"停铸仅指成市 lane"同口径。

### Q4 工具只有一套消耗 —— **是**

- 旧的一套（每趟烧 1,000 毫工具 → `market-merchant-haul` 磨损账）**结构上不存在**：`MerchantHaul` class、`toolBurnedMilli` 字段、`market-merchant-haul` 字面量在 jar 内**命中 0**；`MERCHANT_HAUL_RUN` 改前有 `toolBurnedMilli=1000`、改后该字段消失（`laborHoursMilli=0`，新增 `serviceMilli`）。
- 现存唯一一套 = `trade@hex` 产业的**周期投入**（标准生产管线）：state dump 逐城 **`inputPerUnit={tool:100}`、`outputPerUnit={haul:1}`、`laborPerUnit=1000`、`allocation 300/700`、`capacity={CATTLE:100}`、`cycleDays=120`**；自解释栏 `replacedBy=industry-cycle-input(trade.cycleInputPerUnit={CATTLE:{tool:100}}),…,industry-declared-input-necessaryInputsOf(standard-reserve)`（T-H1/I-H6/T-H4 的面）。
- 观测到投入路径真的在起作用（含**不足**的一面）：`trade@0_0` `cycleInputShortfallCycles=0`、`unsoldStockMilli=0`（服务不囤读数为 0），而 `trade@0_2` **`cycleInputShortfallCycles=1` / `consecutive=2` / status=`contracting`**，day 120 毛产只 **31,000**（满规模应 100,000）⇒ 一城贸易因工具投入不足而缩产。
- **未验到的层**：日志里没有"逐笔产业投入扣减"读数（`HARVEST_BY_INDUSTRY.inputs={}` 新旧皆空，`INPUTS_CONSUMPTION` 是家户口粮面）⇒ 工具单套化只证到"配方声明 + 旧族结构删除 + `cycleInputShortfallCycles` 读数"三层，未证到逐笔账面。

### Q5 回归与健康（逐值表）

| 项 | A 基线(g3f)→A4 | B 基线(g3f)→A4 |
|---|---|---|
| `advance` committed | 11/20/21，head 21、tick 360 → **同** | rev 2、tick 360 → **同** |
| `Exception`/`ERROR`/`IllegalStateException`/`at io.mosire` | 0/0/0/0 → **0/0/0/0** | 0/0/0/0 → **0/0/0/0** |
| WARN | 2,022 → **2,022，构成逐项同**（`MARKET_SUBJECT_COLLECTIVE_UNRESOLVED` 1,994 + `ACCOUNT_SUBJECT_UNRESOLVED` 17 + `POPULATION_SETTLE_REMAINDERS_CLEANED` 11） | 7 → **12，全部 `POPULATION_SETTLE_REMAINDERS_CLEANED`** |
| 人口 Σ | 4,926 → **4,927（+1）** | 4,923 → **4,928（+5）** |
| 粮 Σ | 1,071,755,304 → **1,090,540,229（+1.75%）** | 3,867,997,193 → **3,848,788,464（−0.50%）** |
| 货币守恒 `circulation==baseMoney` | True（copper 100,000/silver 273,200/token 5,000）→ **True，同值** | True（copper/silver）→ **True，同值** |
| 债务/信用 principal、count | 4,200,694 / 1,933 → **4,469,636 / 1,933（+6.4% / 0）** | 20,372 / 1,092 → **18,596 / 1,064（−8.7% / −2.6%）** |
| `creditPrincipal == debtPrincipal` | 是 → **是**（两侧逐值相同） | 是 → **是** |
| `Σ(byUnit)==标量` 逐格核对 | 38/38 → **38/38** | 38/38 → **38/38** |
| 成交 `MARKET_FILL` | 1,716 → **1,669（−47，−2.7%）** | 7,055 → **6,937（−118，−1.7%）** |
| 信用成交 `MARKET_CREDIT_FILL` | 2,848 → **2,878（+30）** | 6,200 → **6,032（−168）** |
| `MERCHANT_HAUL_RUN`（真承运趟） | 48 → **161（3.35×）** | 91 → **141（1.55×）** |
| 承运方运费收入 Σ | 59 毫 → **635 毫（10.8×）** | 224 毫 → **920 毫（4.1×）** |
| Σ 需求运力 / Σ 已服务 / Σ 未服务 / 截断货量 | 80,385,645 / 14,010 / 80,371,635 / 28,817,292 → **51,873,623 / 134,783 / 51,738,840 / 21,445,145** | 15,677,586 / 98,827 / 15,578,759 / 15,160,581 → **15,284,978 / 274,012 / 15,010,966 / 14,657,851** |
| 状态 dump md5（整档） | `31e33834…` → **`fba1128f…`**（19/19 格不同） | `4147153e…` → **`4ad6fbc8…`**（19/19 格不同） |

**跨格成交方向**：以 `MARKET_FILL` 为代理 → **小幅下降**（A −47 / B −118）；
但**真正的跨格承运**（`MERCHANT_HAUL_RUN`）**大幅上升**（3.35× / 1.55×），运费支出上升 10.8× / 4.1×。
⇒ T-H5 的"回升"在**承运趟数**上成立，在**总成交笔数**上不成立（单位运费从改前口径抬到服务牌价 ⇒ 买方运费变贵，`MARKET_FILL` 反而略降）。两者都要看，不能只看一个。
A 的 WARN 构成与基线逐项同；B 的 WARN +5 全为人口结算余数清理（人口轨迹不同 ⇒ 清理次数不同），非新故障。

### Q6 缺省中性（N-H1）—— **不成立；字面世界不可构造**

1. **"缺省世界"已经不是无牌价世界**：A2 把 `haul=4` 加进 `MARKET_PRICES_FACTORY`（`EconomySeeder:873/1886`）⇒ **A/B 两世界 19/19 格价格表都是 6 项含 `haul=4`**（逐格核对），`HAUL_SERVICE_PRODUCED.pricedMarkets=19`。⇒ 用缺省世界做"无牌价 ⇒ 逐值不变"的对照在结构上就不成立（B 的世界 dump md5 与基线不同即为此）。
2. **真做了跨 jar 的"无牌价"实验**（§0 装置 N-H1）：改前 store（tick 360，**市场无 haul**）双份副本，**改前 jar vs 改后 jar** 各 360→480、同参数。结果：
   - **服务机制本身零动作**：`HAUL_SERVICE_PRODUCED/SETTLED/PAID/DELIVERED` 两跑**皆 0**（无牌价 ⇒ 零服务订单/成交/钱动）——这一半符合设计。
   - **但数值大面积不同**：`CARRIER_FEE_PAID` 39 → **1,411**、`MERCHANT_HAUL_RUN` 41 → **1,465**、`MARKET_FILL` 1,758 → **3,042（+73%）**、`TRANSFER` 6,766 → 10,798、`PRIMARY_MODE_RANKING_EXCLUDED` 88 → **0**、`MERCHANT_HAUL_TOOL_BLOCKED_AT_SELECT` 150 → 0；**127 vs 123 种事件里 46 种计数不同**；状态 dump md5 `a45b01ad…` vs `91adce7b…`，**19/19 格 + `__gov` + `__overview` 全不同**（叶级 1,724 个值不同）。差集与 **A3 账本 §6 第 1–4 条自列的"会改变数值"完全对应**（门槛删除 ⇒ 旧被 `tool-short/tool-frozen` 拦下的承运全部放行、趟耗删除、§16 预留撤回、决策层门槛删除）。
   - ⇒ **`I-H3`/A2 账本那句"无牌价 ⇒ 逐值不变"在真实 world 里为假**；成立的只有"无牌价 ⇒ 服务机制零动作"。
3. **N-H1 的字面前提（无跑商/无跨格需求的世界）在真实 world 里构造不出**：世界只有 4 个（`v17levant`/`small-world`/`corridor`/`three-powers`），有经济的都跑 `EconomySeeder` ⇒ 恒有 `haul` 牌价 + 跨格需求；GM 无删价路径（`EconomySetMarketPriceHandler` 对 `price<=0` 具名拒绝、只 upsert；`economy.Seed` 载荷没有 markets/prices 字段）。**且** `commodityIds` 读的是词表常量（`ApiViews.java:1441` → `EconomyVocabulary.allCommodityIds()`）⇒ 即使构造出"零需求"世界，读出口仍会多出 `haul` 一项 ⇒ **md5 级 N-H1 在任何世界都不可能成立**（本实验的改后跑里 `commodityIds` 已多出 `haul`，见 §6 差集）。
   ⇒ 缺省中性的**可验边界**：① 服务机制零动作（已证，两跑 0 事件）；② 未触碰的路径逐字保留（`MerchantCapacityPool:466-476` 无牌价格"逐表达式"退回旧算式）；③ 夹具层（A2/A3 账本 + 测试批 `ae5698dc`）——真实 world 层**未通过**。

## 2. T-H5 判定

**成立（机制面）/ 需修正措辞（方向面）**：

| T-H5 分句 | 判定 | 证据 |
|---|---|---|
| `MERCHANT_HAUL_TOOL_SHORT_AT_COMMIT` 一类"缺工具照运"归零 | ✅ **成立** | 该事件改前改后皆 0（本就没样本）；真正有量的是 `MERCHANT_HAUL_TOOL_BLOCKED_AT_SELECT` 2,603/506 → **0/0**，且事件名在 jar 内 0 命中 |
| 跨格成交"回升"到由需求与运力共同决定 | ⚠ **半成立** | **承运趟数** 48→161 / 91→141（↑）；**总成交** 1,716→1,669 / 7,055→6,937（↓）；Σ需求运力 51.9M vs Σ已服务 134,783 ⇒ 运力/服务货是硬上界（`Σ未服务 51.7M`、截断 21.4M），需求侧也被单位运费抬价压住 |
| 机制真的发生 | ✅ **成立** | §Q2 全表 + 逐笔算式 0 违反 |

## 3. 未验证 / 构造不出（如实）

1. **N-H1 的"零需求世界"**：不可构造（§Q6-3）；真实 world 层只能给出"服务机制零动作"+"无牌价仍有 A3 侧数值变化"两条。
2. **N-H3 两跑确定性**（同 store 同参数两跑逐字节）本轮**未跑**（不在本任务判据内）。
3. **逐笔产业投入扣减**：无日志读数（§Q4 末），只到配方 + 结构删除 + 缺口读数。
4. **T-H3 的"`EnterpriseProfitBook` 一侧可见"不可观测**：见 §6-3。
5. 未跑 `test`/`verify`/前端门禁；未跑 `three-powers`/`v17levant`；未跑 3600+ tick；未跑 FX/GM 家户补丁世界。
6. 基线的"逐值不变"对照用的是 g3f 轮的日志/档（同 commit 的改前 jar `f0e97d0a`），不是本轮重跑；A 世界 day-360 末态不同 ⇒ 只能给"量级 + 守恒 + 构成"级对照（同 g3f 账本 §4-7 的口径）。

## 4. 复现命令

```bash
cd /home/cna/SimulatorMosire
pgrep -af java | grep -i simos                     # 只应有他人 pid 2081（inode ≠ target jar）
MVN_LOCK_WAIT=1800 tools/mvn-lock.sh -DskipTests package                                  # BUILD SUCCESS 16/16
rm -rf simos-economy/target/classes simos-app/target/classes && MVN_LOCK_WAIT=1800 tools/mvn-lock.sh -DskipTests package
md5sum simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar    # def1cbe49e5d948598ed845a7097f261
find . -name '*.java' -newer simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar | wc -l  # 0
bash /tmp/a4-runall.sh        # B(9511/9515/9513, a4-store-b) → A(9411/9415/9413, a4-store-a) → B 只读 dump
bash /tmp/a4-nh.sh            # N-H1：g3f-store-b 复制两份，改前/改后 jar 各 360→480，dump 对照
grep -c 'event=HAUL_SERVICE_PRODUCED ' /tmp/a4-runA.log      # 3
python3 /tmp/g3d-health.py /tmp/g3f-state-a.json /tmp/a4-state-a.json /tmp/g3f-state-b.json /tmp/a4-state-b.json
```

**产物**：日志 `/tmp/a4-run{A,B}.log`、`/tmp/a4-nh-{base,new}.log`；读数 `/tmp/a4-state-{a,b}.json`、`/tmp/a4-state-nh{base,new}.json`；store `/tmp/simos-a4-store-{a,b,nhbase,nhnew}`；推进 `/tmp/a4-adv*`（进程已全停，jar md5 未变）。

## 5. 新发现缺陷（只报不改）

1. **未卖出的运输服务不清零、跨周期累积且可再卖**（与设计 §3.1 v1.2"未卖出者本轮不被'存下来卖两轮'"、I-H3"未卖出的服务按'不可储存'口径本轮消耗"冲突）：观测 Σ 现货 = Σ净产 − Σ已交付 **恰好相等** —— A `362,780 − 134,783 = 227,997`（实测 hex `goods.haul` 合计 **227,997**，非零格 0_0=160,853 / 0_2=67,144）；B `582,000 − 274,012 = 307,988`（实测 **307,988**）。唯一消耗点是**成交那一刻**（`MarketSettlement:7147` → `consumeForLoss`），**没有轮末/周期末清扫**；而池的供给 = `max(0, 现货 − 冻结)`（`MerchantCapacityPool:447-455`，**无周期过滤**）⇒ 上一周期剩的服务下一周期照样被当运力卖出去。两读法（"不可储存转卖"=只能现产现卖 vs 只指成交即消耗）需要用户裁定。
2. **B 世界 `HAUL_SERVICE_SETTLED`（INFO）漏报 10 天**：该行只在 `ctx.haulServiceTrades > 0` 时发（`MarketSettlement:1613`）⇒ 全天免运费/自承运的日子一条不打；实测 B 有交付的天数 43、有 SETTLED 的天数 33，**Σ读数 182,629 vs 实际交付 274,012，少报 91,383 毫服务（33.4%）**（A 恰好无此情形，34/34 相等）。⇒ **INFO 读数不能当"本轮交付了多少服务"用**，`HAUL_SERVICE_DELIVERED` 才是权威；建议（不改）把汇总行改成"有交付就发、`trades` 可为 0"。
3. **T-H3 的"跑商收益在标准企业利润一侧可见"在读数面不可见 / 且可见栏误导**：钱腿 reason 已是 `market_trade`（A 3,483 笔、`carrier_fee` 0），`EnterpriseProfitBook:510-517` 按 `toOrg` 把它计 revenue ⇒ **结构上**入账；但 (a) 工具面**没有** EnterpriseProfitBook 的读出口（`simos.economy.*` 读工具只有 `hex`/`ownership`，GUI 只有 `overview`/`gov`/`hex`/`ownership`，hex 视图无 enterprise/profit 键），(b) 唯一可见的逐产业收入栏 `industries[].condition.lastCycleRevenueByCurrency` 对 `trade@0_0`/`trade@0_2` **恒为 `{}`**（`lastCycleCostMilli=104`、`lastCycleNetMilli=-104`，两世界皆然）⇒ 读数面呈现"贸易产业零收入/亏损"。判据"一侧可见"**当前不可验收**。
4. **`EconomySeeder:4312` 注释与 `:1886` 代码自相矛盾**：注释仍写"本批的缺省中性：运输服务**没有牌价**（`MARKET_PRICES_FACTORY` 不含它）"，而 A2 已把 `haul=4` 加进该表 ⇒ 陈旧注释，会让人误以为缺省世界无牌价（正是 §Q6-1 的陷阱）。
5. **`trade@0_2` 工具投入不足致缩产**（机制的真实后果，非缺陷，但须知）：`cycleInputShortfallCycles=1`/`consecutive=2`/status=`contracting`，day 120 毛产 31,000 vs 满规模 100,000 ⇒ **运力上界不只由需求定，也由工具市场供给定**；T-H5 的"由需求与运力共同决定"应把这一项写进去。
6. **shaded jar 的 md5 不是构建稳定的**（同一份源码两次构建得两个 md5，差在 entry 时间戳）⇒ 跨轮用 jar md5 声明"代码未变"是**无效判据**（本轮改用 1,868 个 class 逐字节比对）。

## 6. 与各批账本的一致性核对（回代码核，§四）

- A1 自报的两处设计书错（`outputPerUnit` 形状、`laborWeightPerMille` 字段混用）在真实 world 里**读数与 A1 口径一致**（`outputPerUnit={haul:1}`、`laborPerUnit=1000`）✅
- A3 自报"会改变数值"的 4 条，本轮在**两个世界都观测到**（A/B 的 dump md5 全变、承运趟数上升、`MERCHANT_HAUL_TOOL_BLOCKED_AT_SELECT` 归零、`PRIMARY_MODE_RANKING_EXCLUDED` 在无牌价跑里 88→0）✅
- A3 自报"不改数值"的 2 条（`MerchantProfitBook` 删除、`CapacityQuote*` 等价替换）：本轮**只证到删除**（事件/类 0 命中），"逐值不变"未单独复现（老 store 无牌价跑里 `CAPACITY_QUOTE_BOOK`→`CAPACITY_PRICING_ASSEMBLY` 是改名，未做逐值对照）⚠
- v1.3 更正过的一句（`LaneUnservedBook` **保留**、是 V-20 价格统计输入）与 jar 实测一致：`LaneUnservedBook.class` 在、`CAPACITY_DEMAND_TOTAL.goodsBlockedByCapacityMilli` 逐日仍有值 ✅
- A2 的"缺省中性：无牌价 ⇒ 除价表多一行外零变化"：**与真实 world 实测冲突**（§Q6-2）❌
