# G3 真实 world 复测账本（市场 → 口岸 → 汇率 → 商户套利）

> 角色：**验证 Agent**（只读 + 运行；不改任何代码/测试/文档）。落盘 2026-10-10。
> 本文件是**证据账本**（命令、读数、日志行），结论摘要另见交接报告。

## 0. 装置（可复现）

| 项 | 值 |
|---|---|
| 被测 jar | `simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar`，mtime `2026-10-10 08:17:02`，md5 `35afc8552eef85aedb9007313e88e598` |
| jar 与源码一致性的判据 | `find . -name '*.java' -newermt '2026-10-10 08:17:02'` = **0 命中**；HEAD=`4cda0b57`（纯 docs）⇒ jar 覆盖全部 15 批 |
| 启动方式 | `JAVA_TOOL_OPTIONS="-Dsimos.economy.logLevel=<DEBUG\|TRACE> -Dsimos.economy.traceLevel=TRACE" tools/run-shaded.sh <jar> --store <store> --world=small-world --gui-port N --mcp-port N+4 --approval-port N+2` |
| 驱动方式 | MCP Streamable-HTTP（`POST http://127.0.0.1:<mcp-port>/mcp`），`initialize → notifications/initialized → tools/call`；脚本 `/tmp/g3-mcp.py`、`/tmp/g3-schema.py`、`/tmp/g3-setup-a.py`、`/tmp/g3-setup-a2.py` |
| 世界 A（**有政策**） | store `/tmp/simos-g3-store-a`；GUI 6311 / MCP 6315 / 审批 6313；日志 `/tmp/simos-g3-runA.log`(tick 0→5, DEBUG) + `/tmp/simos-g3-runA2.log`(5→10, TRACE) + `/tmp/simos-g3-runA3.log`(10→360, TRACE)；账本 md5/读数全程记录 |
| 世界 B（**缺省中性**） | store `/tmp/simos-g3-store-b`；GUI 6411 / MCP 6415 / 审批 6413；日志 `/tmp/simos-g3-runB.log`(0→360, TRACE) |
| tick 数 | A：0→10（装置）→ **360**；B：0→**360** |
| 不动既有服务 | 既有实例 pid 2081（`/tmp/simos-shaded-NwvHbq.jar`，three-powers，store `~/simos-runs/2026-10-09-scale-probe/store`，端口 6111/6115/6113）**全程未动**；未重新打包仓库 jar（复用 08:17 已构建件 + `run-shaded.sh` 快照） |

### A 的装置命令（都在 tick 0~10，GM 面，非代码改动）

1. `economy.SetMarketNumeraire (0,-1) → copper`；`economy.DefineMarketZone` ×3：
   `zone-cap={(0,0)} silver r=1`、`zone-copper={(0,-1)} copper r=1`、`zone-rural=其余 17 格 silver r=1`（⇒ 市场拓扑 `topologyRegions=3`，跨区车道才存在）。
2. `gov.SetAdministrationPlan`（gov-province / gov-central）：`portPlannedLaborMilli=6400`，postTiers tier-3 = {security 400‰, paperwork 400‰, **port 200‰**} ← 口岸维不给编制 ⇒ 口岸效率恒 0 ⇒ 节流永不生效（见 §3.1）。
3. `gov.SetPortPolicy`：
   - gov-province：grain{出限 600‰, 入限 400‰, 出口税 30‰ 从价, 进口税 1 毫/单位从量}；cloth{出限 500‰, 入限 500‰, 出口税 20‰}；`marketControl=true`
   - gov-central：grain{入限 500‰, 进口税 20‰ 从价}；`marketControl=true`
4. `economy.SetOfficialRate gov-central silver|copper buy=950 sell=1050`（**buy≥sell 会被判 inverted_quote 两侧停做**，见 §3.2）。
5. `simos.gm.govMarketMandate` authorize `g3-buy-grain`（gov-unit-gov-province，BUY grain 100000 毫，限价 2，350 天后到期）。
6. FX 正/负路径的人为前提（**world 数据补丁，非代码**）：`actor.TransferAccounts` 国库铜 5000 → `hh-0_-1-rural-middle_peasant`；`economy.DefineCurrency token`（gov-province）→ `RecordMoneyIssuance 5000` → `AdjustAccounts` → 转 2000 给 `hh-0_1-rural-middle_peasant`（**无价币**用例）；`economy.SetMarketPrice (0,-1)` grain=3 / cloth=8（让铜购买力更弱、方向可判）。
7. **world 数据修复**（为跨过 §3.3 的崩溃）：`economy.RegisterHousehold` ×19（`weave@<q>_<r>`，q/r=各自格）+ `actor.EnsureHouseholdAccount` ×19。

## 1. 逐问结论（数字 + 日志行）

### Q1 世界还活着吗
- **A（有政策）@360**：世界总人口 **4,929**（创世 4,800）；粮库存合计 **1,094,611,478 毫**；货币守恒（silver 273,200 / copper 100,000 / token 5,000 = 发行量）；市场成交 `MARKET_FILL=2,731`（天 10–360）；信用成交 `MARKET_CREDIT_FILL=5,504`；`DEBT_UPSERT=6,996`。⇒ 活着，未冻结未崩盘。
- **B（无政策）@360**：人口 **4,923**；粮库存合计 **3,867,915,132 毫**；`MARKET_FILL=10,194`、`MARKET_CREDIT_FILL=6,256`、`DEBT_UPSERT=6,930`、`MERCHANT_HAUL_RUN=3,672`。⇒ 活着。
- ★ **前提更正（重要）**：「19 个农村格 = 0 运力」**在本世界不成立**：`MERCHANT_CAPACITY_POOL_HEX` 在 A/B 两世界、全 360 tick 里 **19 格全部 capacityMilli>0**（每格 2 户跑商，日 3 约 24,000 毫、日 360 约 18,000–20,000 毫）。跨格货**走得动**：B `MERCHANT_HAUL_RUN=3,672` / `CARRIER_FEE_PAID=2,673`；A `376 / 259`。同 hex/区内成交照常（B `MARKET_FILL=10,194`）。运力不足是**具名**的：`MARKET_UNFILLED reason=LOGISTICS_CAPACITY` B=362、A=2,751；另有 `MERCHANT_CAPACITY_LANE_TRUNCATED` B=1,799。

### Q2 节流
- A：`MARKET_PORT_THROTTLED` **127 条日汇总**（天 10–360），`MARKET_PORT_LANE_GATED` **615 条逐车道**，`MARKET_PORT_ROUTE_EXHAUSTED` 4 条。Σ`gatedPairs`=615，Σ`blockedTransitMilli`=**10,857,818,934**（≈10.86M 单位货）。
- **逐值核对**：对 628 条（去重）GATED 行逐行验 `allowedTransitMilli == ⌊transit × E源 × E目 ÷ 1e6⌋` 且 `blockedTransitMilli == transit − allowed` ⇒ **0 违例**。样例（day13）：
  `commodity=grain sellerZone=zone-cap buyerZone=zone-copper transit=53977058 allowedTransit=32386234 blockedTransit=21590824 sourceExitOpennessPerMille=1000 destinationEntryOpennessPerMille=600`（53,977,058×1000×600÷1e6=32,386,234.8→32,386,234 ✓）
- **被拦下的量真不进候选集**：`MARKET_UNFILLED reason=PORT_THROTTLED`（A3=3 条，例 day=183 `side=buy actor=hh-0_-1-rural-middle_peasant commodity=grain quantity=143140 hex=0,-1`）；闸用尽另具名 `MARKET_PORT_ROUTE_EXHAUSTED`（例 day=33 `seedlerHex=2_-2 buyerHex=0_-1 buys=5 sells=1 reason=port-transit-cap-exhausted`）。
- ★ 生效前提：**口岸效率必须真的有人**（见 §3.1 缺陷）；B 无政策 ⇒ 上述事件 **0 条**。

### Q3 三层税
- A：`MARKET_TAX_LAYER_CHARGED`(TRACE) **10 笔**、`MARKET_TAX_COLLECTED`(INFO 逐层逐日) **5 条**、`MARKET_TAX_COLLECTED_TOTAL`(INFO 逐日) **5 条**、`MARKET_TAX_UNVALUED` 0 条。
- **逐值核对（1:1）**：day13 3笔/42 = COLLECTED 42 = `TRANSFER reason=port_tax_entry` 3笔/42；day15 2/26=26=2/26；day18 3/40=40=3/40；day120 1/1=1=1；day270 1/1(copper)=1=1。**Σ charge = Σ COLLECTED = Σ TRANSFER = 110 毫**；笔数也逐日相等 ⇒ 钱真进 `hh-gov-gov-province` 国库户。
- **每层只收一次**：每个成交（day/buyer/seller/commodity/quantity 同键）无重复层（早先看到的「重复」是我把崩溃前落盘的日志与重跑日志拼接造成的假象；按单次运行日志核对后无重复）。
- ★ **实际只收到 2 层中的 1 层**：全部 10 笔都是 `layer=port_entry`；`port_exit` **一笔都没有**，区内市场税层**一笔都没有**（原因见 §3.4/§3.5）。
- 国库余额侧：同期国库 silver 从 114,118→124,098（day 6–10），差额 = 辖区税 `TAX_DAILY_END moneyCollected` 10,041 + 口岸税 99 − 官俸/采购支出；**同账户混流，故逐值证明只给上面那条 1:1 链**。

### Q4 汇率
- A：`FX_HOUSEHOLD_POWER`(DEBUG) **740 条**、`FX_HOUSEHOLD_ORDER`(TRACE) **1 条**、`FX_FILL` **1 条**、`FX_HOUSEHOLD_UNFILLED` **0**、`FX_ORDER_PLAN` **86**。
- **正路径确实通**（day 8，人为给它铜之后）：
  `FX_HOUSEHOLD_POWER household=hh-0_-1-rural-middle_peasant powerMilli=copper=15,silver=4 unquotable= target=silver orders=1 reason=converted-to-strongest`
  `FX_HOUSEHOLD_ORDER side=BUY base=silver quote=copper limitPerMille=3750 baseMilli=1333`
  `FX_FILL base=silver quote=copper baseMilli=1333 quoteMilli=3200 pricePerMille=2400 venue=gov_window buyer=hh-0_-1-rural-middle_peasant seller=hh-gov-gov-central`
- **缺价币没被换（unquotable）**：`FX_HOUSEHOLD_POWER household=hh-0_1-rural-middle_peasant powerMilli=silver=4 unquotable=token target= orders=0 reason=no-comparable-purchasing-power`（186 条，4 户）⇒ 持 token（无价）者**一条单都不挂**。
- ★ **「部分成交下一轮继续」没观测到**：day 8 那笔是**按限价定量、以更优价全额成交**（1333/1333），剩余 1800 铜在 day 10 因 `quantity=1799×1000/3750=479 < FX_MIN_LOT_BASE_MILLI(1000)` 而**不挂**（`reason=no-order-placed（低于最小手 / 给不出限价）`），此后 82 个市场日 `orders=0`。⇒ 机制存在（限价/最小手是具名拒），但**「残余继续挂」在真世界里被最小手挡在门外**，无正向样本。
- ★ **缺省世界里家户换汇根本不触发**：B `FX_HOUSEHOLD_*` / `FX_ORDER_PLAN` / `FX_FILL` **全 0**。原因（实测）：世界只有 `silver` 与 `copper` 两币，而 **100,000 毫铜 100% 在 `official` 类（=两国库户 `hh-gov-gov-central`/`hh-gov-gov-province`）**，国库户被显式排除出家户 FX（`FxSettlement` 注释 R1）；其余家户持币种类 <2 ⇒ 循环空转。**要看到 FX，必须先由 GM 把第二种币发到私人家户手里**（本账本 §0.6 就是这么做才拿到上面那条 FILL）。

### Q5 运力/商户
- 汇总读数：B `MERCHANT_CAPACITY_POOL=1,640`（例 `hexes=19 households=38 capacityMilli=1374073 allocatedMilli=119306 allocations=21 priced=true maxAskPerMille=125 toolBlockedRuns=180 toolMilliRemaining=404473 toolMilliPerHaul=1000`）、`MERCHANT_CAPACITY_POOL_HEX=1,558`、`MERCHANT_CAPACITY_HOUSEHOLD=13,680`（含 `askPerMille`、`sharePerMille`、`tier=PORTER`、`runsAffordable=12`）、`CAPACITY_QUOTE_BOOK=360`。
- **按最低价购买**：报价有离散（`askMin` 50/75；`askMax` 50/75/125），池按 hex 汇总 `askMin/askMax`；**但没有任何日志行点名"买到了谁、成交价=第几低"** ⇒ 「按最低价购买」只能间接看（`CARRIER_FEE_PAID` 的 amountMilli），**未逐值验证**（见 §4）。
- **LOGISTICS_CAPACITY**：B 362、A 2,751（`MARKET_UNFILLED reason=LOGISTICS_CAPACITY`，351 卖侧/11 买侧）⇒ 「无运力 ⇒ 不成交」具名成立（本世界是"运力不足/被截断"形态，不是"全 0 运力"形态）。
- **tool 烧毁 / tool-short / tool-frozen**：B `MERCHANT_HAUL_RUN=3,672`，其中 `toolBurnedMilli>0` 仅 **83** 笔（Σ83,000 毫）；`MERCHANT_HAUL_TOOL_SHORT_AT_COMMIT=3,589` 且 **全部 `reason=tool-frozen`**（`stockMilli=12000 frozenMilli=12000 availableMilli=0`）。A3 同形：376 趟 / 61 笔烧 / 314 frozen + 1 short。
- **是否 ~12 趟后停**：**否**。B 37 户跑商，趟数 1/中位 **61**/最大 **395**，**28 户 >12 趟**；A3 29 户，中位 5、最大 70，7 户 >12 趟。`runsAffordable` 读数分布以 10、9 为主（12 只占 135 行）⇒ 「12」是**读数**不是停点。
- 商户利润读数：B `MERCHANT_PROFIT_HOUSEHOLD=1,354`（`marginByCurrency/freightEarnedByCurrency/laborCostByCurrency`）、`MERCHANT_PROFIT_TOTAL=82`；A3 448/127。

### Q6 采购优先级
- A：`MARKET_PROCUREMENT_PRIORITY_APPLIED` **127 天**、`MARKET_PROCUREMENT_PRIORITY_HARD_STOP` **45**、`MARKET_PROCUREMENT_PRIORITY_CONTRACT` **0**；注入读数为 `PROCUREMENT_PRIORITY_REGIME_INJECTED governments=2 poolUnits=2 zeroPoolGovernments=0`。
- 有超越的日（45 天）：`overtakesConsumed=1 poolUnitsLeft=1 promotedSlots=1 hardStops=1`；其余 82 天 `overtakesConsumed=0 poolUnitsLeft=2`。见底硬停样例（day13）：`HARD_STOP government=hh-gov-gov-province blockingHousehold=hh-0_1-rural-landlord landingIndex=42 poolUnitsLeft=0 reason=administrative-pool-exhausted-no-overtake-without-payment` ⇒ **先扣后超越、见底停**成立。
- B（无 `marketControl`）⇒ 三个事件 **全 0** ✔

### Q7 缺省中性
- **零输出确认（B，360 tick，TRACE 全开）**：`MARKET_PORT_*` 0、`MARKET_TAX_*` 0、`FX_HOUSEHOLD_*`/`FX_FILL` 0、`MARKET_PROCUREMENT_PRIORITY_*` 0、`GOV_SET_PORT_POLICY_APPLIED` 0、`MARKET_SUBJECT_UNRESOLVED_UNIT` 0。
- 非政策门控的商户/运力层照常非零（设计如此，不该为 0）：`MERCHANT_HAUL_RUN=3,672`、`CARRIER_FEE_PAID=2,673`、`MERCHANT_PROFIT_HOUSEHOLD=1,354`、`LOGISTICS_CAPACITY=362`、`tool-frozen=3,589`。
- **旧提交基线对照**：`23e11653`（本链 15 批之前的 HEAD）已在 worktree `/tmp/simos-baseline` 打包并跑同一世界（见 §2），结论见报告。

## 2. 旧提交基线（缺省中性对照）

- 基线 commit：`4d116367^` = **`23e11653`**（docs(status)：工程优化清单），worktree `/tmp/simos-baseline`。
- 构建：`./mvnw -q -DskipTests package -pl simos-app -am`（本仓唯一在跑的 Maven；不动主树 jar）。
- 运行：world store `/tmp/simos-g3-store-base`，端口 6511/6515/6513，`--world=small-world`，0→360 tick，日志 `/tmp/simos-g3-runBase.log`。
- 对照结果：**见报告 §③④**（本文件落盘时该轮结果以追加行形式补在下方）。

- 基线 jar md5 `fd8f963946715a52cfd02f860ab6a402`（26,911,506 B）；0→360 一次推进，rc 正常，revision 2 / tick 360。
- 读数快照：`/tmp/g3-dump.py <mcp> <gui> <out>` → `/tmp/g3-state-base.json`（旧）与 `/tmp/g3-state-b.json`（新，实例重启后 dump，只读、不改状态）。

**量级对照（同一 world 参数、同 tick 360、两侧都无政策）**

| 读数（19 格合计 / 世界级） | 新 jar（中性 B） | 旧 jar（基线 23e11653） | 判定 |
|---|---|---|---|
| 人口 | 4,923 | 4,922 | 一致（Δ1） |
| 粮库存（毫） | 3,867,915,132 | 3,867,912,504 | 一致（Δ2,628 / 3.87e9） |
| 货币流通（copper/silver） | 100,000 / 273,200 | 100,000 / 273,200 | **逐值相同** |
| `MARKET_FILL`（360 tick） | 10,194 | 9,577 | +6.4% |
| `MARKET_CREDIT_FILL` | 6,256 | 5,960 | +5.0% |
| `DEBT_UPSERT` / 日志侧 Σ`principalMilli` | 6,930 / 930,176,021 | 6,631 / 916,672,345 | +4.5% / **+1.5%** |
| `MARKET_UNFILLED reason=LOGISTICS_CAPACITY` | 362 | 205 | +77%（运力面新机制，默认开启） |
| `MERCHANT_HAUL_RUN` / `CARRIER_FEE_PAID` / `MERCHANT_PROFIT_HOUSEHOLD` | 3,672 / 2,673 / 1,354 | **0 / 0 / 0** | 旧 jar 无这些事件（merchantFirms 旧架构；不是"没跑"） |
| 19 格价格表 | `{cloth5, fiber1, grain1, iron9, tool9}` | `{cloth5, fiber1, grain1, iron9, tool25}` | **tool 价 9 vs 25（19/19 格）**——默认世界实质差异 |
| `economy.hex` 全量 dump 差异 | —— | —— | 6,133 条数值差（多数是逐类 flow/尾部小余额），另有 729 新字段 / 498 旧字段（读口形状变） |
| `debtPrincipal` 读数合计 | 1,176,074 | 17,154 | **读口口径已变**（日志侧 Σ本金只差 1.5%）⇒ 不能当经济差异 |

⇒ 结论：**政策门控事件 0 输出 ✔、人口/粮/货币量级一致 ✔**；但 **tool 价格（9 vs 25）是一处默认世界的实质差异**（商户/运力批次是默认开启的行为变更，不是政策门控），且 `debtPrincipal` 读数字段口径变了（须按日志侧机制证据读，不能跨版本比该字段）。

**差异溯源（创世快照逐值 diff，`/tmp/simos-g3-store-*/checkpoints/main/1.json`，两侧同 rev1）**——全部分歧都能追到 t=0 的**播种改动**，不是政策门控事件的泄漏：

| 模块 | 差异 | 性质 |
|---|---|---|
| `actor` | **ONLY-NEW 38 条**：19 格 × {`urban-landlord`, `urban-rich_peasant`} 的 `balances/tool` | 新的工具禀赋（跑商门槛用）；旧档这些户无 tool 余额键 |
| `economy` | **ONLY-BASE 1 条**：`data/merchantFirms` | 设计的组件退役（AGENTS §一.11 不兼容旧档） |
| `economy` | **VAL 2 条**：`industries/trade@0_0` 与 `trade@0_2` 的 `cycleInputPerUnit = {CATTLE:{tool:100}}`（旧 = `{}`） | M-D「trade 加 tool 投入」的播种 |
| （其余） | 无其它值差 | 创世其余部分逐值相同（人口/货币/价格/产业/阶层） |

⇒ 缺省世界的差异**可归因且封闭**在"工具/跑商"这一批（默认开启的行为变更）；政策门控面（口岸/税/FX/采购）在中性世界**零条日志、零状态影响**。**没有**发现"政策门控功能偷偷改变默认世界"的证据。

## 3. 发现的缺陷/异常（只报不改）

### 3.1 口岸节流需要"口岸维编制"，否则恒不生效（配置面缺陷，非崩溃）
`GovEfficiency` 的 `portEfficiencyPerMille = (portSupplyLaborMilli==0) ? 0 : …`，而 `portSupplyLaborMilli` 来自编制岗位档位里的 **port 权重**；`GovAdministrationPlan.DEFAULT_*_TIER` 三档的 port 权重**全是 0** ⇒ 创世世界口岸效率恒 0 ⇒ `PortThrottle` 里 `E=1000−0` ⇒ **一条政策都咬不动**。本账本 §0.2 必须显式给 tier-3 拨 200‰ 口岸劳动才让节流真的发生。**另**：`simos.command.catalog` 的 `gov.SetAdministrationPlan` 载荷提示**没列** `portPlannedLaborMilli`/`port*Modifier`/`portWeightPerMille`（实际 handler 支持，`GovPayloads:97`）⇒ 按 catalog 写政策必然写出一个"永不生效"的口岸政策。

### 3.2 官方汇率必须 bid<ask，否则窗口两侧停做（口径未文档化）
`economy.SetOfficialRate buy=1000 sell=1000` ⇒ `FX_WINDOW_BUY_BLOCKED reason=inverted_quote` + `FX_WINDOW_SELL_BLOCKED` + `FX_REJECTED reason=inverted_quote`（`GovFxWindow:171 if (bidPerMille >= askPerMille)`）。catalog 只说 `buyPerMille>0, sellPerMille>0`，没说必须 bid<ask；照 catalog 设 1:1 ⇒ 外汇窗口静默不可用（有具名日志，但只在跑起来后才看见）。

### 3.3 ★ 多市场区（≥2 区）世界在第一个"集合主体参与跨区"的市场日**硬崩**（fail-closed，无脏状态）
- 复现：A 世界（3 个持久市场区）`simos.advance from=10 to=360` ⇒ day=38 抛
  `IllegalStateException: 市场参与者无法解析到任何家户（账户主体只有家户…）：day=38 unit=unit-weave@-1_2-HOUSEHOLD-weave@-1_2 operator=HOUSEHOLD:weave@-1_2`
  前置 ERROR 行：`MARKET_SUBJECT_UNRESOLVED_UNIT … operatorKind=HOUSEHOLD operatorHousehold=weave@-1_2 operatorHouseholdRow=false hex=-1_2`
  ⇒ 整条 AdvanceTime **不落 revision**（head 停在 22/tick 10，无半推进）。
- 单区世界里同一批 unit 走的是**另一条路**且被优雅跳过：B（19 格同币 ⇒ `MarketTopology.singleRegion`）`MARKET_SUBJECT_COLLECTIVE_SKIPPED=1,558`（19 格 × 82 市场日），0 崩溃。
- 结论：**跨区/多区路径缺"集合主体跳过"这一半**；而口岸/节流/过境税**必须以多区为前提** ⇒ 新功能一开，世界就活不过第 38 天（本世界）。
- 我为了让 A 跑满 360，用 GM 命令补了 19 条 `weave@<q>_<r>` 经济行 + 空账（§0.7）；补完当天又先撞上第二道闸：
  `IllegalStateException: 家户 actor / 账本缺失 19 个（家户账是日结算的唯一读口…）` ⇒ 再补 `actor.EnsureHouseholdAccount` 才过。**这是 world 数据补丁，不是代码修复**。

### 3.4 区内市场税（第三层）在生产路径**没有写入口** ⇒ 永远 0
全仓 `new MarketRegulation(...)` 的**生产**构造只有 `EconomySettlement:1836` 的 `MarketRegulation.defaultsFor(markets)`（= 只定锚格、`tariffPerUnit` 空 ⇒ `defined()=false`）；`tariffPerUnit` 的非空样本只在测试 `PortGateTaxAcceptanceTest:345`。没有命令/状态组件能设它。⇒ 「三层税」实际只可能收到两层（且见 3.5），`MARKET_TAX_LAYER_CHARGED` 永远不会出现第三层。

### 3.5 出口税（从价）在本世界的量级下**恒向下取整到 0**
政策给 grain 出口税 30‰ 从价；跨区成交量级 ~17 单位 × 价 1 ⇒ 税额 0.5 毫 ⇒ floor 0 ⇒ 不落任何 charge 行（Σ 全部 10 笔都是 `port_entry`）。⇒ **同一个"三层税"里，从量腿能收到钱、从价腿在小额贸易下恒 0**（不是没收，是取整吃掉；但读口上就是"出口税从未收过"）。

### 3.6 ★ `tool` 门槛被冻结绕过：**具名 short 了，货照样运走、运费照样铸**
- B（中性世界）day=3 同一户三连（同一毫秒区间）：
  `CARRIER_FEE_PAID from=hh--2_0-rural-landlord toHousehold=hh--1_-1-urban-landlord amountMilli=…`
  `MERCHANT_HAUL_TOOL_SHORT_AT_COMMIT household=hh--1_-1-urban-landlord neededMilli=1000 burnedMilli=0 stockMilli=12000 frozenMilli=12000 availableMilli=0 reason=tool-frozen`
  `MERCHANT_HAUL_RUN household=hh--1_-1-urban-landlord commodity=grain from=-1_-1 to=-2_0 quantityMilli=11881 toolBurnedMilli=0 laborHoursMilli=0 waivedFreightMilli=0`
- 规模：B 3,672 趟里 **3,589 趟**在 `tool-frozen` 下照跑、`toolBurnedMilli=0`；只有 83 趟真烧了工具（Σ83,000 毫）。A3 376/314/61 同形。
- 推论：①「跑商门槛=工具」在真世界里**几乎从不扣费**；②`MERCHANT_HAUL_TOOL_SHORT_AT_COMMIT` 的 fail-closed 只落在"烧工具"这一步，**没有让这次承运不成立**（与 `MerchantHaul` 类注"H-5：缺工具 ⇒ 该次跑商不成立"相反）；③正因如此**看不到 ~12 趟后停**（`runsAffordable=12` 只是读数）。这正是 §7.4-6 那条"没付门槛却收了运费"的**实测复现**，且量级很大。

### 3.7 `unquotable` 计数器语义混装（日志归因错）
`FxSettlement:404`：`placeHouseholdOrder` 返回 null 后，只有"可花额 < 最小手"记 `belowMinLot`，其余（**订单量**按限价折算后 < 最小手、或给不出限价）一律记成 `unquotable++`。实测：持 1800 毫铜（**有价**）的家户被记进 `FX_ORDER_PLAN unquotable=1, belowMinLot=0` ⇒ 读日志会把"残余太小"误读成"这个币没有价"。真正"无价币"那条另有 `FX_HOUSEHOLD_POWER … unquotable=token` 具名，两者混在同一个计数名里。

### 3.8 事实性观察（非缺陷，但报告口径必须知道）
- 「19 农村格 = 0 运力」**不成立**（A/B 均 19/19 有运力）。
- 家户自动换汇在**缺省创世世界 100% 不触发**：全部第二币（铜）在国库户，而国库户被排除（§Q4）。
- `MARKET_PROCUREMENT_PRIORITY_APPLIED` 在 `promotedSlots=0, overtakesConsumed=0` 的日子**照样发**（127 天里 82 天如此）⇒ 该事件名读作"本轮跑过优先级阶段"更准，"应用了"会误导。

## 4. 我没验证 / 没做的（如实）

1. **改代码**：全程未改任何 `src/**`、测试、`docs/**`（`git status` 只应有本账本目录）。所有"修复"都是 GM 世界数据 + 政策。
2. **部分成交→下一轮继续**：无正向样本（§Q4）；只有"低于最小手 ⇒ 不挂"的负向样本。
3. **「按最低价购买运力」逐值**：日志不点名成交的承运方与名次，只验到"报价有离散 + 运费真付"。
4. **三层税的平衡侧逐值**（国库余额增量 == Σ税额）：同账户混着辖区税/官俸/采购，**只做到日志侧 1:1**（charge=COLLECTED=TRANSFER）；余额侧只给了 day6–10 的分解，含未逐笔穷尽的官俸腿。
5. **`MARKET_PORT_*` 的 ROUTE_EXHAUSTED / ZONE_KEY_CONTRACT 负向**：ZONE_KEY_CONTRACT（区键不一致的 fail-closed）没构造，0 命中。
6. **`lending`/`exchange` 币种挂单过滤（P-T1e）**：`PORT_REGIME_INJECTED … currencyFeeClasses=0`，我没给币种规则 ⇒ 币种维禁入/禁出**未被真世界触发**（只有形状与注入读数）。
7. **确定性两跑（N7）**：没跑第二次相同世界的逐值对比。
8. **v17levant（59,223 格）大世界**：没跑（本轮只跑 small-world 19 格）。
9. **`tool` 烧毁的真实消耗路径**：83 趟真烧的成因（何时工具不冻结）没深挖。
10. **未跑全仓门禁/测试**：验证 Agent 不做代码门禁；`clean verify` 不在本账本范围。

---

## ★ 2026-10-10 追加更正（留痕，不改上文）

**本文件 §5-7 / `:105` 的结论"`debtPrincipal` 跨版本不可比（口径已变）"——理由错、结论凑巧对。**
只读诊断（`.superpowers/sdd/2026-10-10-diagnose-debt-anomaly/impl-ledger.md`）实测：
债务读口**零改动**（`git log -S'debtPrincipal'` 本链只有 docs/test 三条；`ApiViews` 债务合计块未动），
`17,154 → 1,176,074` 是**真实状态差** —— 真因是那一轮多出 **20 条巨额实物粮债**
（全 `commodity:grain` / NORMAL / 债权人 `hh-0_0-rural-landlord` / `dueDay=none`；
该出借人 in-kind 粮信用 **121 笔 / Σ2,990,558 → 0 笔**），且**无"少结债"证据**。
★ 另记：`debtPrincipal` 顶层标量**跨 unit 混算**（粮与银相加），与 `ApiViews.java:2177-2200`
"本金不跨 unit 合计"自相矛盾 ⇒ 台账应改用 `byUnit`。
