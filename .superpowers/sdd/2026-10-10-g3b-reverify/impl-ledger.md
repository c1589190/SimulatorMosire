# G3b 复验账本 —— G3-fix-1（`a42509ab`）两处真实-world 修复的独立复验

> 角色：**验证 Agent**（只读 + 起独立世界；**未改任何 `src/**` / 测试 / docs**；本账本与 `/tmp` 脚本是唯一写盘）。
> 日期：2026-10-10。前置：G3 账本 `.superpowers/sdd/2026-10-10-g3-real-world-verify/impl-ledger.md`、
> G3-fix-1 账本 `.superpowers/sdd/2026-10-10-g3-fix-1/impl-ledger.md`、进度 §7/§7.6。
> ★ 结论一句话：**修复 1（多区世界硬崩）修好了；修复 2（tool 门槛被冻结绕过）没修好 —— 代码在位、但读的输入恒为空**。

---

## 0. 装置与 jar 身份（可复现）

| 项 | 值 |
|---|---|
| 被测 jar（当前源码） | `simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar`，md5 **`3e4fd80550298868e41cec9d0fb6b9cc`**，mtime `2026-10-10 08:45:32` |
| jar↔源码判据 | HEAD=`a42509ab`（commit 时间 `08:40:49`）⇒ jar 晚于修复提交；`find . -name '*.java' -newermt '2026-10-10 08:45:00'` = **0 命中**；`git status --porcelain` 干净；jar 内 `MarketSettlement.class` 含 `MARKET_SUBJECT_COLLECTIVE_UNRESOLVED` + `isUnresolvedAggregateOperator`、`MerchantCapacityPool.class` 含 `frozenGoods`（`unzip -p … \| strings \| grep -c` = 1/1/1）⇒ **确为修复后源码所构建** |
| 对照 jar（修复前） | `/tmp/simos-shaded-EMWcKq.jar`，md5 **`35afc8552eef85aedb9007313e88e598`** = G3 账本 §0 那一颗（mtime 08:17:02）⇒ 同装置 A/B 对照用的就是"修复前" |
| 启动 | `JAVA_TOOL_OPTIONS="-Dsimos.economy.logLevel=<DEBUG\|TRACE> -Dsimos.economy.traceLevel=TRACE" tools/run-shaded.sh <jar> --store <store> --world=small-world --gui-port N --mcp-port N+4 --approval-port N+2`（每进程独占快照，未覆盖任何共享 jar） |
| 驱动 | MCP Streamable-HTTP：`/tmp/g3b-mcp.py`（call/list）、`/tmp/g3b-adv.sh <mcp> <from> <to> <out>`（自动带 `expectedRevision`）、`/tmp/g3-dump.py`（19 格 + gov + overview 全量读数） |
| 不动既有服务 | pid **2081**（6111/6115/6113，`/tmp/simos-shaded-NwvHbq.jar`，three-powers）全程未动；未跑 Maven（jar 已由控制方构建）、未跑 `package` |

| 世界 | store | 端口 GUI/MCP/审批 | 日志 | 说明 |
|---|---|---|---|---|
| **A（多市场区，原生）** | `/tmp/simos-g3b-store-a` | 6611/6615/6613 | `/tmp/g3b-runA.log`（144 M） | tick 0→5→10 装置，10→**360**；**0 条** `RegisterHousehold`/`EnsureHouseholdAccount` |
| A 对照（修复前，同装置） | `/tmp/simos-g3b-store-apre` | 7211/7215/7213 | `/tmp/g3b-runApre.log` | 同一套 `g3b-setup1pre/2pre.py` |
| **B（缺省中性）** | `/tmp/simos-g3b-store-b` | 6711/6715/6713 | `/tmp/g3b-runB.log`（142 M） | 0→360 一次推进 |
| B 对照（修复前） | `/tmp/simos-g3b-store-bpre` | 6811/6815/6813 | `/tmp/g3b-runBpre.log` | 同参数同 tick |
| B（root TRACE 各一跑） | `…-btrace1` / `…-btrace2` | 7311/7315、7411/7415 | `runBtr1.log` / `runBtr2.log`（159 M，448,788 行） | post / pre，逐行对照 |
| N7 确定性 | `…-n7a` / `…-n7b`（复制自 store-b@360） | 6911/6915、7011/7015 | `runN7a.log` / `runN7b.log` | 各 360→**400** |
| FX 部分成交 | `…-fxc`（复制自 store-b@360）+ GM 注入 | 7111/7115/7113 | `runFX.log` / `runFX2.log` | 见 §4 |

A 装置（与 G3 §0.1~0.5 同）：`SetMarketNumeraire(0,-1)=copper`；3 个市场区（zone-cap/zone-copper/zone-rural）；
两 GOV 编制 `portPlannedLaborMilli=6400` + tier-3 port 200‰；`SetPortPolicy`×2（含出/入限制与入/出税、`marketControl=true`）；
`SetOfficialRate gov-central silver|copper 1000/1000`（tick0）→ **950/1050**（tick5）；`SetOfficialRate gov-province 950/1050`（FX 实验追加）；
`SetMarketPrice(0,-1) grain=3/cloth=8`；`TransferAccounts` 国库铜/券 → 私户；`DefineCurrency token` + `RecordMoneyIssuance`。
★ `simos.gm.govMarketMandate authorize`（`government=gov-province`）**被拒**（`未知政府…: gov-province`）—— 与 G3 同一脚本同一结果，故 A 的采购优先级事件来自 `marketControl`（与 G3 一致）。

---

## 1. ★ Q1：原生多市场区世界能否跑满 360 tick（修复 1 的验收判据）

**结果：能。** `simos.advance from=10 to=360` ⇒ `{"result":"committed","revision":21}`，`head=21 / tick=360`，**耗时 9.2 s**，全日志 `IllegalStateException` / `ERROR` = **0** 条。

崩点被接住（同址、同日）：
```
MARKET_SUBJECT_COLLECTIVE_UNRESOLVED origin=economy-market originKind=tick day=38
 unit=unit-weave@-1_2-HOUSEHOLD-weave@-1_2 operator=HOUSEHOLD:weave@-1_2 hex=-1_2
 industry=weave@-1_2 usableAssets={TOOL=7} laborAllocations=0
 reason=aggregate-operator-without-any-household-row-is-not-a-market-subject
```
- 这是全日志**第一条** WARN（min day = **38**），unit/operator/hex 与 G3 §3.3 的崩点**逐字相同**；共 **1,971 条** WARN、覆盖 117 个市场日；`MARKET_SUBJECT_UNRESOLVED_UNIT` = **0**。
- 无 GM 世界数据补丁：A 日志与两个 setup 脚本里 `RegisterHousehold|EnsureHouseholdAccount` = **0 命中**（G3 当年正是靠补 19 行 + 19 空账才越过 day38）。

**对照（修复前 jar，同一装置/同一脚本/同端口无冲突）**：
```
advance 10->360 → [isError=true][mosire:code=TOOL_ERROR]
… 时间推进提交失败: IllegalStateException: 市场参与者无法解析到任何家户（账户主体只有家户；聚合主体必须能解析到组织者/经营者家户）：
  day=38 unit=unit-weave@-1_2-HOUSEHOLD-weave@-1_2 operator=HOUSEHOLD:weave@-1_2
  operatorKind=HOUSEHOLD operatorHousehold=weave@-1_2 operatorHouseholdRow=false hex=-1_2 …
```
`head` 停在 **20**（失败不落 revision），日志最后一天 = **38**，`MARKET_SUBJECT_UNRESOLVED_UNIT`=1。
⇒ **判据成立**：同一装置，修复前死于 day38、修复后跑满 360 并落 revision。**修复 1 = 修好了。**

---

## 2. ★ Q2：tool 门槛（`TOOL_SHORT_AT_COMMIT` 是否归零 + fail-closed 是否落到行为）

### 2.1 数字：**没有归零，一格没变**

同装置（small-world 缺省、0→360、同样日志档位）两颗 jar 的逐值对照：

| 读数 | 修复前 `35afc855` | 修复后 `3e4fd805` | G3 账本（修复前） |
|---|---|---|---|
| `MERCHANT_HAUL_TOOL_SHORT_AT_COMMIT` | **3,589** | **3,589** | 3,589 |
| ↳ `reason=tool-frozen` 占比 | 3,589/3,589 | 3,589/3,589 | 全部 |
| `MERCHANT_HAUL_RUN` 且 `toolBurnedMilli=0` | 3,589 | **3,589** | 3,589 / 3,672 |
| `CARRIER_FEE_PAID` | 2,673 | 2,673 | 2,673 |
| `MARKET_FILL` / `MARKET_CREDIT_FILL` | 10,194 / 6,256 | 10,194 / 6,256 | 10,194 / 6,256 |
| `MARKET_UNFILLED reason=LOGISTICS_CAPACITY` | 362 | 362 | 362 |
| `MERCHANT_CAPACITY_LANE_TRUNCATED` | 1,799 | 1,799 | 1,799 |
| `DEBT_UPSERT`（root TRACE） | 6,930 | 6,930 | 6,930 |

- **状态字节级**：两颗 jar 的 19 格全量读数 dump 规范化 JSON **md5 相同**（`8e0c431e424885d3fbf1254a0d38c754`，11.8 MB 原始 / 1,466 债务 / 178 户）。
- **日志逐行级**（root TRACE，448,788 行，去掉时间戳后 diff）＝ **21 行差异**，全部是：快照路径、GUI/MCP 端口、store 路径、`ADVANCE_END` 的 `commandId` UUID、以及我 kill 进程产生的 `SHELL_STOP_SIGNAL`。**经济事件 0 差异**。
- DEBUG 档同样：406,773 行 → 20 行 diff，同一批基础设施行。
⇒ 修复 2 在本世界是**行为空操作**（no-op）。

### 2.2 fail-closed 落到行为了吗：**没有**（同户同日三连仍在）

B 世界 day=10 / `hh-0_0-urban-rich_peasant`（三行相邻，同一毫秒区间）：
```
line12921 CARRIER_FEE_PAID        … day=10 commodity=grain toHousehold=hh-0_0-urban-rich_peasant carrierHex=0_0 …
line12928 MERCHANT_HAUL_TOOL_SHORT_AT_COMMIT household=hh-0_0-urban-rich_peasant neededMilli=1000 burnedMilli=0
          stockMilli=4077 frozenMilli=3467 availableMilli=610 reason=tool-frozen
line12929 MERCHANT_HAUL_RUN        … household=hh-0_0-urban-rich_peasant commodity=grain from=0_0 to=-2_0
          quantityMilli=6018 toolBurnedMilli=0 …
```
day=110 / `hh-0_2-urban-rich_peasant` 同形（`stockMilli=7603 frozenMilli=6973 availableMilli=630 neededMilli=1000`）。
规模：**928** 个 `(day, household)` 同时有 SHORT 与 `toolBurnedMilli=0` 的 HAUL_RUN；其中 **708** 个同日同户还有 `CARRIER_FEE_PAID`；涉及 35 户、77 个市场日（day 3→360，出现最晚的到 day=360）。
⇒ **货照样运走、运费照样铸、门槛一分没付** —— G3 §3.6 的缺陷原样存在。

### 2.3 根因（"没修到"而不是"另一处"）：**池装配早于本轮冻结写入 ⇒ 减项恒为 0**

- 代码顺序（只读核）：`EconomySettlement:1854-1861` 装配 `MerchantCapacityPool`（**已**传 `householdFrozenGoods`，`:1860`），
  该池在 `:2020` 才被传进 `MarketSettlement.clearOncePerCycle(...)`；而本轮的卖单冻结在**市场轮内部** `MarketSettlement.commitFreezes`（`:1584`，写 `refreshSellFrozen`）才落表。
  ⇒ 装配时读到的冻结表**结构上不可能**含本轮冻结；G3-fix-1 账本 §2.2 的断言"装配时读到的冻结 = 本轮冻结的同一事实"**不成立**。
- 现场实测（A 世界，day 3，同一户同一轮）：
  池读 `MERCHANT_CAPACITY_HOUSEHOLD hex=-2_1 household=hh--2_1-urban-landlord laborMilli=0 toolMilli=12000 capacityMilli=12000 toolRemainingMilli=12000 runsAffordable=12`
  → 同轮提交 `… SHORT … stockMilli=12000 frozenMilli=12000 availableMilli=0 reason=tool-frozen`（池 12,000 / 提交 0，与修复前一模一样）。
- 该户 360 个市场日的池读数 `toolMilli` 分布全是**原始存量**（7030/10766/8557/…），从不出现"可用量"被扣后的值；
  B 世界 13,680 行池读数里 `toolMilli=0` 仅 12 行，且 pre/post **逐行相同** ⇒ 修法新增的 `max(0, stock − frozen)` 从未生效过一次。
⇒ 判据：**修复 2 = 没修好**（落点对、口径对、调用点也传了表，但**输入恒为空**）。属"没修到"，不是"另一处崩溃"。

---

## 3. Q3 世界健康：与 G3 同装置对照（量级/守恒/成交/债务）

| 读数 @tick 360 | B 修复后 | B 修复前 | G3 B（修复前） | A 修复后（多区，原生） | G3 A（多区，**有补丁**） |
|---|---|---|---|---|---|
| 人口 | 4,923 | 4,923 | 4,923 | **4,926** | 4,929 |
| 粮库存（毫） | 3,867,915,132 | 3,867,915,132 | 3,867,915,132 | 1,085,187,066 | 1,094,611,478 |
| 货币（copper/silver[+token]） | 100,000/273,200 | 同 | 同 | 100,000/273,200/**5,000** | 同 |
| 货币守恒（流通 == 发行） | ✔ | ✔ | ✔ | ✔ | ✔ |
| `MARKET_FILL` | 10,194 | 10,194 | 10,194 | 2,092 | 2,731（tick10–360） |
| `MARKET_CREDIT_FILL` | 6,256 | 6,256 | 6,256 | 2,760 | 5,504 |
| `DEBT_UPSERT`（TRACE） | 6,930 | 6,930 | 6,930 | — (档位未开) | 6,996 |
| `debtPrincipal` 读数合计 / 笔数 | 1,176,074 / 1,466 | 逐值同 | 1,176,074 | 4,283,250 / 1,919 | — |
| 跨格成交（`MARKET_FILL from≠to`） | **9,325** | **9,325** | 未记 | 1,067 | 未记 |
| `MARKET_UNFILLED reason=LOGISTICS_CAPACITY` | **362** | **362** | 362 | 2,736 | 2,751 |
| `MERCHANT_HAUL_RUN` / `CARRIER_FEE_PAID` | 3,672 / 2,673 | 同 | 3,672 / 2,673 | 424 / 305 | 376 / 259 |

**结论**：
1. 人口 / 粮 / 货币守恒 / 成交 / 信用 / 债务**全部量级一致**，B 世界与 G3 与修复前**逐值相同**；A 世界人口 +? （4,926 vs G3 4,929，差 3，G3 A 世界多 19 条 GM 补丁家户行）⇒ **没有因修复而恶化**。
2. **预期落空**：题面预期"跨格成交减少、`LOGISTICS_CAPACITY` 增加"——**实测两者都不变**（9,325 → 9,325；362 → 362，A 1,067/2,736 与 G3 的 2,751 同量级）。
   原因同 §2.3：门槛修法没有改变任何一次承运判定。⇒ 该预期**不成立**（因为修复 2 无效，而不是因为方向反了）。
3. A 世界成交数比 G3 的 A 少（2,092 vs 2,731）与 `debtPrincipal` 高（4.28 M vs 无读数）**不能归因于修复**：G3 的 A 世界多了 19 条 GM 家户行 + 19 空账（多 19 个市场参与者），本账本的 A 是**原生**世界；需要同装置对照时以 §1 的 pre-fix A（day38 崩）为准。

---

## 4. ★ 补 G3 未验证项

### 4.1 ① 家户 FX「部分成交 → 下一轮继续挂」：**构造出了正向样本**（需 GM 世界数据，非缺省世界自然发生）

装置（world fxc = B@360 副本，GM 注入，**不改代码**）：`SetMarketNumeraire(0,-1)=copper`；3 个市场区（(0,-1)=铜法定区）；
`SetMarketPrice(0,-1) grain=3/cloth=8`（铜购买力更弱 ⇒ 该格家户"铜→银"）；`SetOfficialRate gov-central` 与
**`gov-province`**（950/1050，白银在省国库 226,931 毫，central 为 0）；`simos.gov.issueMoney` 铜 3,000,000 毫 +
`actor.TransferAccounts` → `hh-0_-1-rural-middle_peasant`（存量 1.6 M–2.76 M 铜）。
用 `-Dsimos.economy.logLevel=TRACE` 才能看到逐笔（逐笔事件在 `…economy.fx` 分类的 TRACE 档，见 §6.2）。

**正向样本（day 465→490，每个市场日都有）**：
```
day=465 FX_HOUSEHOLD_ORDER household=hh-0_-1-rural-middle_peasant side=BUY base=silver quote=copper limitPerMille=4000 baseMilli=401410
day=465 FX_FILL  base=silver quote=copper baseMilli=29435 quoteMilli=74324 pricePerMille=2525 venue=gov_window
                 buyer=…middle_peasant seller=hh-gov-gov-province                      ← 成交 29,435 / 挂单 401,410 ≈ 7.3%
day=470 挂单 382,824 → 成交 29,434 ; day=475 挂单 364,239 → 29,438 ; day=480 345,652 → 29,438
day=483 挂单 328,945 → 16,336 ; day=485 318,631 → 11,970 ; day=490 311,070 → 28,832
```
- **部分成交成立**：每轮成交量 ≈ 挂单量的 3–7%（对手方=省窗口，其可卖白银 = 国库储备 + 当日税入，逐轮被吃干/补充）。
- **下一轮继续挂成立**：7 个连续市场日各一条 `FX_HOUSEHOLD_ORDER`；挂单量按**剩余可用铜**重算（订单逐轮瞬态、不跨轮存状态）：
  401,410 → 382,824 的差 18,586 base 毫 × 2.525 ≈ **74,344 铜毫 ≈ 上一轮 `quoteMilli=74,324`** —— 即"残余（未成交铜）在下一轮重新挂出来"逐值对得上。
- 更早的相位：day=363 成交 88,416（窗口当日储备被吃干）→ day=365…420 每轮**仍挂单但无对手方**（`FX_ORDER_PLAN orders=1`，`fills=0`）；
  追加省窗口后 day=423 成交 227,952 → day=425/430/…/460 每轮继续成交小额。⇒ "继续挂"与"部分成交"两头都有样本。
- 反面对照（G3 观察到的卡点）也复现：`belowMinLot=2`、`unquotable=1`（`FX_ORDER_PLAN`）、
  以及"窗口储备 0 ⇒ 只挂不成交"（`FX_WINDOW_SELL_BLOCKED reason=reserve_exhausted reserveBaseMilli=0`）。
⇒ **构造得出**，但**代价 = GM 世界数据**（铸币 + 转移 + 第二个窗口）：缺省小世界里第二个币全在国库户（国库户被 `FxSettlement` 排除出家户 FX），
**没有**任何私户天然持双币 ⇒ 自然世界跑不出这个样本（与 G3 §Q4 的判断一致）。

### 4.2 ② 两跑确定性（N7）：**逐值相同**（状态 dump 字节级相同）

装置：`store-b`@360（post-fix B 跑满后）复制两份 `n7a`/`n7b`（复制前 store 文件 md5 相同 `cd3aae19…`），
同 jar、同参数（同 world/同 tick）、各自 360→400。

| 项 | n7a | n7b | 判定 |
|---|---|---|---|
| `head` / `tick` | 3 / 400 | 3 / 400 | 同 |
| 19 格 + gov + overview 全量读数 dump（11.8 MB） | md5 `fcc2144c8e31e940003559ed939814ae` | **同 md5** | **字节级相同** |
| 规范化 JSON 逐键比较（10.36 MB 字面量） | — | — | **完全一致** |
| revision 节点字段（除 `commandId`） | — | — | 逐字段相同 |
| 人口 / 粮 / 货币 / `debtPrincipal` / `debtCount` | 4,943 / 3,416,973,694 / 铜100,000·银275,200 / 3,728,460 / 1,661 | 同 | 同 |
| `simos.db`（journal）md5 | `07cd4454…` | `ebb…040…` | **不同**（journal 内嵌随机 `commandId`/`correlationId` UUID）⇒ 属"差异清单"的**唯一**一项，非模拟状态 |

⇒ **N7 = 通过**（关键读数 + 状态字节逐值相同；已知唯一差异 = 命令 UUID）。★ 另有更强的旁证：修复前后两颗 jar 各跑一次 B（0→360）状态 dump **md5 也相同**（`8e0c431e…`），日志逐行相同。

---

## 5. 逐问回答（摘要）

1. **原生多区世界 360 tick**：✅ 能（`committed` revision 21 / tick 360 / 0 异常 / 9.2 s），崩点 day38 `unit-weave@-1_2` 变成具名 WARN；修复前同装置死在 day38（同一条 `IllegalStateException`）。
2. **tool 门槛**：❌ `TOOL_SHORT_AT_COMMIT` **3,589 → 3,589（未归零）**；fail-open **未落到行为**（同户同日三连 928 组；708 组连运费腿一起成立）；根因 = 池装配早于本轮冻结写入 ⇒ 减项恒 0。
3. **世界健康**：量级一致、无恶化；**跨格成交 9,325→9,325、LOGISTICS_CAPACITY 362→362（预期方向未出现）**。
4. **① FX 部分成交→下轮续挂**：✅ 构造出（day465–490，7 轮，成交 3–7% + 挂单量按残余重算；需 GM 世界数据）。**② N7**：✅ 逐值相同（dump 同 md5；唯一差异 = journal 里的命令 UUID）。

---

## 6. 新发现的缺陷（只报不改）

1. **★★ 修复 2 无效（最重）**：`MerchantCapacityPool.of(..., frozenGoods, ...)` 的减项在本轮冻结之前读取 ⇒ 真实世界里恒不生效；
   G3-fix-1 账本 §2.2 的时序论证（"装配时读到的冻结 = 本轮冻结的同一事实"）与代码顺序相反（池装配 `EconomySettlement:1854` → 市场轮 `commitFreezes` `MarketSettlement:1584`）。
   建议方向（不属本 Agent 权限）：门槛判据要么在**市场轮内**取《可用量》重算（select 之前），要么把冻结表**跨轮持久**到装配可见——两者都需控制方裁定。
2. **逐笔日志档位覆盖不一致（日志面缺陷）**：`FX_FILL`/`FX_HOUSEHOLD_ORDER` 挂在 `io.mosire.simos.economy.fx` 的 **TRACE**、
   `DEBT_UPSERT`/`DEBT_REDUCE` 挂在 `…economy.debt` 的 TRACE，而 `log4j2.xml` 只把 `io.mosire.simos.economy.trace` 子 logger 抬到 `simos.economy.traceLevel`。
   ⇒ 按 AGENTS §一.9 的推荐组合 `-Dsimos.economy.logLevel=DEBUG -Dsimos.economy.traceLevel=TRACE` 跑真实世界时，**每一笔 FX 成交、每一笔债务建立都不落日志**（实测：DEBUG 档 `FX_FILL`=0、`DEBT_UPSERT`=0；把 root 抬到 TRACE 后，缺省 B 世界 0→360 得 `DEBT_UPSERT=6,930`（pre/post 同为 6,930），FX 实验里逐笔 `FX_FILL` 8 条）。
   G3 账本里 `FX_FILL=1 条`、`DEBT_UPSERT=6,930` 只有在 root=TRACE 时成立 —— 台账与默认档读数会互相矛盾。
3. **`MERCHANT_CAPACITY_HOUSEHOLD` 不带 `day`**（逐户读池读数无法与某一天对齐；`MERCHANT_CAPACITY_POOL_HEX` 带 day）⇒ 按日核对困难（G3 与本轮的日级对照都得靠文件顺序推断）。
4. **WARN 刷量**：修复 1 的新 WARN 在多区世界 per market-day × per unit 全量出现（本案 1,971 条 / 117 天）。属可接受，但读日志时要知道它是"每轮重复事实"，不是 1,971 次新故障。
5. **`economy.SetOfficialRate` 缺省窗口可能是死的**：小世界 silver 全在 `hh-gov-gov-province`，把窗口设在 `gov-central`（储备 0）⇒ 卖侧恒 `reserve_exhausted`、家户只挂不成交（具名 INFO ✔，但 catalog 没提示"窗口要有储备"）。

---

## 7. 我没做 / 没验证（如实）

1. **未跑 Maven**（`test`/`verify`/`package`）—— 验证 Agent 不做代码门禁；jar 由控制方构建，我只核身份（md5 + 源码新于判据 + class 标记）。
2. **未跑 v17levant 大世界**、未跑 `--world=three-powers`、未跑 3600/3650 tick。
3. **A 世界与 G3 的 A 世界不是逐值可比**（G3 的 A 含 19 条 GM 补丁家户行）；我给了同装置 pre-fix A 作对照，但**没有**给"原生 A 的修复前/后逐值 diff"以外的第三方基线。
4. **FX 部分成交样本靠 GM 世界数据**（铸币 3,000,000 铜 + 省窗口）：**自然世界跑不出**（私户无双币、国库户被排除）；且样本的成交价 = 挂单限价与窗口报价的中点（2525），**未**逐值验证撮合优先级（价格-时间优先）在多方簿下的次序。
5. **未验证** `MERCHANT_HAUL_TOOL_SHORT_AT_COMMIT` 中的 8 条 `reason=tool-short`（真缺货）是否**应当**也 fail-closed 到"不承运"（我只报了它们同样落在 `toolBurnedMilli=0` 的 HAUL_RUN 上）。
6. 未做 `LOGISTICS_CAPACITY` 的**分侧**（买/卖）拆解、未逐值核对 `sharePerMille`/`runsAffordable` 的算式。
7. 未覆盖 `MARKET_PORT_*` / 三层税 / 采购优先级（G3 已验，本轮不在题面）。

---

## 8. 复现命令（关键几条）

```bash
# jar 身份
md5sum simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar   # 3e4fd805…（mtime 08:45:32 > a42509ab 08:40:49）
find . -name '*.java' -newermt '2026-10-10 08:45:00' | wc -l  # 0

# A（原生多区）与 B（缺省），各 0/10→360
JAVA_TOOL_OPTIONS="-Dsimos.economy.logLevel=DEBUG -Dsimos.economy.traceLevel=TRACE" \
  tools/run-shaded.sh simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar \
  --store /tmp/simos-g3b-store-a --world=small-world --gui-port 6611 --mcp-port 6615 --approval-port 6613
python3 /tmp/g3b-setup1.py ; /tmp/g3b-adv.sh 6615 0 5 /tmp/out ; python3 /tmp/g3b-setup2.py
/tmp/g3b-adv.sh 6615 5 10 /tmp/out ; /tmp/g3b-adv.sh 6615 10 360 /tmp/out

# 修复前对照（同一颗 35afc855 快照）
tools/run-shaded.sh /tmp/simos-shaded-EMWcKq.jar --store /tmp/simos-g3b-store-apre …（脚本 g3b-setup1pre/2pre.py）

# 计数与逐行对照（去时间戳）
grep -c "event=MERCHANT_HAUL_TOOL_SHORT_AT_COMMIT " /tmp/g3b-runB.log      # 3589（pre 同）
sed 's/^[0-9:.]* //' /tmp/g3b-runBtr2.log > a; sed 's/^[0-9:.]* //' /tmp/g3b-runBtr1.log > b; diff a b | wc -l  # 21
```

**产物清单**：日志 `/tmp/g3b-run{A,Apre,B,Bpre,Btr1,Btr2,N7a,N7b,FX,FX2}.log`；读数
`/tmp/g3b-state-{a,b,bpre,n7a,n7b}.json`；驱动 `/tmp/g3b-{mcp,adv.sh,setup1,setup2,setup1pre,setup2pre,fx-setup}.py`；diff `/tmp/tr.diff`、`/tmp/b.diff`。
