# 债务存量异动与 G3c 四处异常的只读诊断账本

> 角色：**只读诊断 Agent**。未改任何 `src/**` / 测试 / `docs/**` / `pom`；**未跑 Maven、未起世界**；
> 唯一写盘 = 本文件。证据全部来自：仓库只读（`read`/`grep`/`git show`/`git log -S`/`git diff`）
> + 上一轮留在 `/tmp` 的日志与状态 dump（g3b/g3c 两轮 + 老基线）。
> 日期：2026-10-10。前置账本：`2026-10-10-g3-real-world-verify/`（G3）、`2026-10-10-g3b-reverify/`（G3b）、
> `2026-10-10-g3c-verify-tool-gate/`（G3c，四处缺陷在 §6）。

## 0. 证据基座（谁是谁）

| 装置 | jar / commit | B 世界 dump | B 日志 |
|---|---|---|---|
| 老基线 | `23e11653`（本链 15 批之前） | `/tmp/g3-state-base.json` | `/tmp/simos-g3-runBase.log` |
| G3 轮 | `35afc855…`（HEAD=4cda0b57，15 批全量、未修） | `/tmp/g3-state-b.json` | `/tmp/simos-g3-runB.log` |
| G3b 轮 | `3e4fd805…`（=a42509ab，G3-fix-1） | `/tmp/g3b-state-b.json` | `/tmp/g3b-runB.log` |
| G3c 轮 | `43a28364…`（=c7f38fed，G3-fix-2） | `/tmp/g3c-state-b.json` | `/tmp/g3c-runB.log` |

- 跨轮唯一的源码差 = `a42509ab`（G3-fix-1）与 `c7f38fed`（G3-fix-2）；两者都不改 `ApiViews`/债务代码
  （`git show --stat a42509ab c7f38fed`：EconomySettlement / MarketSettlement / MerchantCapacityPool / MerchantHaul + 账本）。
- dump 的读口 = MCP `simos.economy.hex` 逐格（`/tmp/g3-dump.py`），账本里的 "19 格合计" = Σ 该视图顶层
  `debtPrincipal`/`debtCount`/`creditPrincipal`/`creditCount`。**本次自测复核了这类求和**（下表全部由我重算，与账本逐值相同）。

| 读数（19 格 Σ） | 老基线 | G3 | G3b | G3c | G3c-A | G3b-A |
|---|---|---|---|---|---|---|
| `debtPrincipal` | 17,154 | 1,176,074 | 1,176,074 | **20,372** | 4,200,694 | 4,283,250 |
| `debtCount` | 936 | 1,466 | 1,466 | **1,092** | 1,932 | 1,919 |
| `creditPrincipal` / `creditCount` | 17,154 / 936 | 1,176,074 / 1,466 | 同 | 20,372 / 1,092 | 4,200,694 / 1,932 | 4,283,250 / 1,919 |
| 单笔均额 | 18.3 | 802.2 | 802.2 | **18.7** | 2,174.3 | 2,232.0 |

⇒ 债务人侧 Σ == 债权人侧 Σ **逐值相等**（四版全成立）⇒ 读口的两个半边没有漂移。

---

## 1. ★ 债务存量异动（Q1）：**真实状态，不是读口口径；G3 账本那条"口径已变"的结论要更正**

### 1.1 读口没变（三条独立判据）

1. `git log --oneline 23e11653..HEAD -S'debtPrincipal'` = 只有 `eeda1c3d`/`82d71a74`（docs）、`5758962d`（测试批）
   —— **本链零 main 代码改动**该字段。
2. `git diff 23e11653..HEAD -- simos-app/…/gui/ApiViews.java` 里 `debtPrincipal` 只命中
   `view.put("debtPrincipalMilli", condition.debtPrincipalMilli())`（`OperatorCondition` 的**另一**视图）。
   债务合计块本体未动：`ApiViews.java:1203-1204`（声明）→ `:1311-1315`（逐 `data.debtsOf(key)` 累加 `debt.principal()`）
   → `:1405-1406`（`view.put("debtPrincipal"/"debtCount")`）。
3. 语义未变：`SETTLED` 合同在**两版都记 principal=0**（逐 status 求和：老基线 `{SETTLED:0, NORMAL:8136, DEFAULTED:9018}`，
   G3b `{SETTLED:0, NORMAL:1165199, DEFAULTED:10875}`）⇒ 不是"旧版把已结清本金也算进去"。

### 1.2 状态侧自证（合同表本身变了）

- `DEBT_REFERENCES_REBUILT … households=108 added=1466 removed=0 total=1466`
  （`/tmp/g3b-runB.log` 08:50:00.294）vs `… added=1092 removed=0 total=1092`（`/tmp/g3c-runB.log` 09:17:48.690）
  ⇒ 债务引用表 **少了 374 条**，与 dump 的 1,466→1,092 逐值吻合。
- 逐合同（`debtDetails`，字段 `principal/status/openedDay/unitKind/counterparty`）：
  - G3b：**20 条 >10K，Σ=1,150,054 = 全量的 97.8%**；全部 `unitKind=commodity` / `commodity=grain` /
    `status=NORMAL` / `counterparty=hh-0_0-rural-landlord` / `openedDay ∈ {242,272,303,320,325,333}` /
    `terms=…|rate=20|dueCycle=none|dueDay=none`（**敞开式、无到期日**）。
  - G3c：最大一条 3,085（money，DEFAULTED），>10K 的 **0 条**。

### 1.3 少的是"建"的那一半，且集中在一家放贷户（最小证据）

`MARKET_CREDIT_FILL` 按 `unit` 拆（自测）：

| | `commodity:grain` | `commodity:tool` | `money:silver` |
|---|---|---|---|
| 老基线 | 39（Σ5,321,345，max 229,000） | — | 5,921 |
| G3b | **160（Σ8,214,903）** | 151 | 5,945 |
| G3c | **42（Σ5,530,220）** | 95 | 6,063 |

按出借人拆 `unit=commodity:grain`：

- G3b：`hh-0_0-rural-landlord` **121 笔 / Σ2,990,558**；`hh-0_0-urban-middle_peasant` 27 笔；`hh-0_0-urban-rich_peasant` 12 笔。
- G3c：`hh-0_0-rural-landlord` **0 笔**（该户 60 笔信用成交**全是** `money:silver`）。

逐借款人 1:1 核对（G3b，同一出借人）：`hh-1_1-rural-landless_laborer-displaced` 收到 **99,625** ⇒ 对应合同
`principal=102,399`（差 = 20‰/周期利息滚动）；另有 7 户同形（93,007–99,625 ⇒ 合同 95,210–102,351）。
该户在 G3c **仍存在且在卖**（`MARKET_FILL … seller=hh-0_0-rural-landlord` 120 笔；dump 里人口 7→10），
人口/粮/货币守恒逐值不变 ⇒ 不是"户消失/迁移"。
⇒ **那 20 条巨债在 G3c 从未建立**（不是被结清、也不是被写成别的字段）。

### 1.4 结论

1. **① 是状态，不是口径**（1.1–1.3）。−98.3% = "少数几笔敞开式 in-kind 粮债的残余消失"，与 `MARKET_CREDIT_FILL`
   总量只差 0.9% 不矛盾：该读数被 **20 条合同主导（97.8%）**，而总量读数按笔数计。
2. **② 不是本轮提交改的读法**：G3b→G3c 只差 `c7f38fed`（不改债务/读口）；`23e11653→4cda0b57` 的 17,154→1,176,074
   **同样是真实状态差**（老基线 936 条 / **0 条 >10K** / NORMAL Σ=8,136；新 1,466 条 / 20 条 >10K / NORMAL Σ=1,165,199；
   老基线也有 in-kind 粮信用 39 笔 Σ5.32M，只是**当期/当天结清**）。
   ⇒ **`2026-10-10-g3-real-world-verify/impl-ledger.md:105` 的"读口口径已变 ⇒ 不能当经济差异"应更正**：
   结论（不可跨版本比该标量）恰好成立，但**理由是另一个** —— `Σ debtPrincipal` 把 `commodity:grain`（粮单位）
   与 `money:silver`（银毫）**混成一个标量**（`ApiViews.java:1313-1314` 无 unit 拆分），与 dashboard 侧
   `debtPrincipalStockView`（`ApiViews.java:2177-2200`，明文"本金不跨 unit 合计（粮与钱不硬折）"）自相矛盾。
3. **③ 没有"少结债"的证据**：粮债偿还决策 558（G3b）vs 529（G3c）同量级；消失的是**建**的那一半
   （1.3 的出借人 × 单位维度）。
4. 真正待裁的是**设计**：`dueDay=none` + `rate=20‰/周期` + `NORMAL` 的**敞开式 in-kind 粮信用**
   （单户 ~10 万粮、19 格 20 条）是否允许无条件挂到期末 —— 它使"债务存量"这个读数由个别合同决定。

---

## 2. `tool-budget-exhausted`（Q2）：取更小者**是有意的**，但决定性的操作数是**过期的**（判据时点错）

### 2.1 算式与行号（HEAD）

- 装配点（唯一预算来源）：`MerchantCapacityPool.java:259-264` `toolMilli = max(0, goods−frozen)` →
  `:265-267` `MerchantCapacity.of(household, hex, labor, toolMilli)` → Entry 构造 `:500`
  `this.remainingToolMilli = capacity.toolMilli()`（**装配时点可用量 = 本轮预算**）。
- 选择点判据：`:672-675` 现读活视图 `available = max(0, stock−frozen)`；`:676-679`
  `toolCheckMilli = available<0 ? remainingToolMilli : min(remainingToolMilli, available)`；`:680` `affordsRun`。
- 被拦：`:683-691` 具名后 `continue`，**不扣预算**；放行才扣：`:703` `remainingToolMilli -= TOOL_MILLI_PER_HAUL`。
- 归因三分支：`:808-815`（`stock<一趟 ⇒ tool-short`；`available<一趟 ⇒ tool-frozen`；**否则 ⇒ tool-budget-exhausted**）。
- 文档自述（已被实测证伪）：`:800-801`「两者都不是（可用量够）⇒ 本轮的预算镜像已放行过若干趟 —— **生产路径不可达**
  （每次放行都在同一步烧掉工具，预算与账上可用量同步下降）」。

### 2.2 实测（自测，非引用）

- `/tmp/g3c-runA.log`：`reason=tool-budget-exhausted` **29 行 / Σ`budgetBlockedRuns` = 5,824**；
  出现该 reason 的行里 `stockMilli` 取值集合 `{1575,1610,1879,1882,1908,1925,…}`（**全部 ≥1000 = 一趟的量**）。
- 最小样本（day=153）：`… reason=tool-budget-exhausted stockMilli=1610 frozenMilli=0 availableMilli=1610
  neededMilli=1000 toolBudgetRemainingMilli=925`；另 day=125 `stock=1879/budget=575`、day=135 `1908/879`、
  day=145 `1925/908`。⇒ 当刻可用量 1,610 ≥ 1,000，却因**装配时点的快照**（925 < 1,000）被拦。

### 2.3 判定

- **"取更小者"是有意设计**（`c7f38fed` 提交正文 + 类注 `:629-631` 两处写明 `min(本轮预算余量, 当刻可用量)`；
  4/5 参旧路径 `:677-678` 退化成"只判预算"= 逐值退回改前）。
- **但拦截的成因是判据时点**，不是"故意更严"：两个操作数取自**不同时点**（装配 `:259-267` vs 选择 `:672-675`），
  于是实际判据 = `min(装配时点可用量 − 已放行×1000, 当刻可用量)`，与类自身口径冲突
  （`:668-670`「判据只看**工具够不够一趟**…是门槛，不是按量计的费」；`:202-204`「判据量 = …**当刻**现货−当刻冻结」）。
  轮内家户工具存量上升（买工具/产业投入）时，门槛用**过期的低值**拦人。
- 方向 **fail-closed**（只会过严、不会过宽：轮内下降时 `min` 取当刻活视图，轮内上升时才取到旧值）。
- 修法（二选一，属控制方裁定）：① 预算镜像改为**首次 `select` 时从活视图初始化**（或直接
  `当刻可用量 − 本轮已放行×1000`）；② 若"预算 = 装配时点可用量"才是正式口径，则**删掉 `:800-801`"生产路径不可达"**
  并把"当刻"字样从 `:202-204`/`:629-631` 去掉（B 世界该支 = 0，A 世界 5,824 趟）。

---

## 3. `LOGISTICS_CAPACITY` 低估？（Q3）：**不是漏一半，是单位不同 + 它是"日终残余"的归因**

### 3.1 读数产生点与口径

- 行：`EconomySettlement.java:2133-2151` `MARKET_UNFILLED`（TRACE；`day/side/actor/commodity/quantity/reason/hex`）。
  ★ 同一批事实在日志里**各落三条**：`MARKET_UNFILLED`（397）+ `MARKET_SELLER_OUTCOME`（378）+
  `MARKET_BUYER_OUTCOME`（19）（后两者由 `MarketSettlement:7607-7608` 的 `collectSellerOutcomes/collectBuyerOutcomes` 发），
  所以统计必须锚事件名（裸 `grep 'reason=LOGISTICS_CAPACITY'` = 794）。
- 数据：`MarketSettlement.collectUnfilled` `:7540-7606` —— **逐槽位**：凡 `remaining>0` 的 buy/sell 槽各出一条
  （`quantity` = 该槽位当日剩余量），不是逐笔成交、不是逐车道、不是总量。
- `reason=LOGISTICS_CAPACITY` 的赋值：
  - 卖侧 `MarketSettlement:7639-7641`（`sell.capacityBlocked` 优先于 `sell.blocked`）；
  - flag 三个落点：`markCapacityBlocked :6773-6786`（由 `executeTrade :6412`(=全拦) / `:6418`(=截断) 调用，
    **工具门槛拦下的也走这里** ⟹ 该档把"缺工具"和"缺运力"**混成一档**）、
    `blockLaneWithoutCapacity :6820-6827`（发货格无运力 ⇒ 车道不建）、`:5290-5298`（车道窗口运力用尽）。
- 生产点确认：`EconomySettlement:1854` 装配池 → `MarketSettlement:6396` `carrierPool.select(...)`（唯一调用点）。

### 3.2 单位对不上（自测）

| 读数 | G3b | G3c | Δ |
|---|---|---|---|
| `MARKET_UNFILLED reason=LOGISTICS_CAPACITY` 行数 | 362（卖351/买11） | **397**（卖378/买19） | +9.7% |
| 同上 **Σquantity**（毫商品） | 809,190,329 | **721,625,195** | **−10.8%** |
| `MERCHANT_CAPACITY_LANE_TRUNCATED`（按车道） | 1,799 | **2,260** | +26% |
| ↳ 其中 tool 因（frozen/short/组合） | 1,258 | **2,039** | +62% |
| ↳ `unallocatedMilli`（tool 因合计） | ≈11.8M | **≈13.3M** | +13% |
| `MERCHANT_HAUL_TOOL_BLOCKED_AT_SELECT`（新） | — | 506 行 / **Σ`blockedRuns`=4,102** | — |
| `CAPACITY_DEMAND_INSTANCE`（需求实例） | 5,354 | 2,351 | −56% |

- "4,102 趟"是**池内条目 × `select` 调用**（`:653-656` 计数、`:683-691` 累加）：单条 `(day=3, 户)` 行
  `blockedRuns=55`，最大 93，中位 4（自测）⇒ 与"槽位/日"不同单位。
- 车道级（唯一与"趟"可比的）是 `LANE_TRUNCATED`：**1,799→2,260**（+26%），其 `unallocatedMilli` 才是"运不动的量"。
- 而 `LOGISTICS_CAPACITY` 的 Σquantity（≈721,625 单位）远大于 tool 因 `unallocatedMilli`（≈13,328 单位）
  ⇒ 该档的量主要由**没走到 select 的路线**（车道不建/窗口用尽）贡献。

### 3.3 判定

- 该读数是"**日终仍未满足的槽位，其直接原因**"，因此结构上会**漏掉**：① 被拦的 `select` 尝试若同槽位后来被别的
  对手方吃掉（`remaining` 归 0），**一条行都不落**（尝试 ≠ 槽位）；② 无法分"缺工具/缺运力"（同走 `markCapacityBlocked`）；
  ③ 不是量纲（count 升、quantity 降，方向相反）。
- ⇒ G3c 账本 §6-3「`LOGISTICS_CAPACITY` 只 +9.7% ⇒ 严重低估」的**表述要改写**：不是"漏算一半"，
  而是**拿槽位计数比尝试计数**；要比就比 `LANE_TRUNCATED`（+26%，tool 因 +62%）与 `unallocatedMilli`。
  作"运力不足总量指标"确实不称职（这一点 G3c 的结论方向对）。

---

## 4. `MERCHANT_CAPACITY_HOUSEHOLD` 缺 `day`（Q4）：**本链引入**（M-A1），只影响可观测性

- 写入点：`MerchantCapacityPool.java:318-356` 的 `EventLog.channel(LOG).debug(LogEvent.of("MERCHANT_CAPACITY_HOUSEHOLD",
  EconomyLogSource.ECONOMY_ORGANIZATION, "hex", …, "household", …, …, "priced", built.priced))` ——
  字段清单 = `hex/household/laborMilli/toolMilli/capacityMilli/hexTotalMilli/sharePerMille/tier/serviceRadiusHex/
  askPerMille/posted/pureMerchant/toolRemainingMilli/runsAffordable/priced`，**无 `day`**（`:325` 是事件名行）。
- 同族对照**有** `day`：`MERCHANT_CAPACITY_POOL_HEX` `:1009-1012`（`"day", day`）、`MERCHANT_CAPACITY_POOL` `:1035`；
  运行期日志 `event=MERCHANT_CAPACITY_POOL_HEX … day=3 hex=-1_-1 households=2` 可见。
- 溯源：`git log -S'MERCHANT_CAPACITY_HOUSEHOLD'` 首现 **`2488b3e1`（M-A1）**，其后 `ef923d5a`/`711a6e94`/`a42509ab` 各改过一次；
  `2488b3e1` 属 `23e11653..HEAD` ⟹ 相对本链是**引入**（G3b 账本 §6-3 称"老缺陷未修"= 相对"当前这轮修复"是老的，不矛盾）。
- 影响：**仅读数不可按日对齐**，不改任何数值行为。变通：`MERCHANT_HAUL_TOOL_BLOCKED_AT_SELECT`（`c7f38fed` 新增）带 `day`。

---

## 5. 总判定：该修 / 该记 / 待裁定

### 该修（本链引入且已证有行为或可用性后果）

| # | 项 | 落点 | 依据 |
|---|---|---|---|
| 1 | `MERCHANT_CAPACITY_HOUSEHOLD` 补 `day` | `MerchantCapacityPool.java:318-356` | §4；M-A1(`2488b3e1`) 引入；同族 POOL/POOL_HEX 都有 |
| 2 | tool 预算镜像的**时点**（过严拦 5,824 趟/29 行，A 世界） | `:500` + `:259-267` vs `:672-679`、`703`、`800-801` | §2；口径与类注自相矛盾；fail-closed 但偏差 |

### 该记（既有缺口 / 设计取舍，记账即可，不改行为）

| # | 项 | 落点 | 依据 |
|---|---|---|---|
| 3 | `debtPrincipal` 顶层标量**跨 unit 混算**；dashboard 侧已分 unit | `ApiViews.java:1203-1204/1311-1315/1405-1406` vs `:2096/2177-2200` | §1.4；台账应改用 `stocks.debtPrincipal.byUnit` |
| 4 | 更正 G3 账本"读口口径已变"的结论（真因是状态 + 混算） | `2026-10-10-g3-real-world-verify/impl-ledger.md:105` | §1.4（追加更正行，不篡改原行） |
| 5 | `LOGISTICS_CAPACITY` 表述："槽位/日 vs 尝试"、量反向 | G3c 账本 §6-3 | §3.3 |
| 6 | `tool-budget-exhausted` 的"生产不可达"断言作废（若采纳修法②） | `MerchantCapacityPool.java:800-801` | §2.1/§2.2 |

### 待裁定（不是修不修，是设计）

7. **敞开式 in-kind 粮信用**：`terms=…|rate=20|dueCycle=none|dueDay=none`、`status=NORMAL` 可挂到期末，
   单户 ~10 万粮（19 格 20 条 = 债务存量的 97.8%）⇒ 债务存量读数由此类个别合同主导，跨版本/跨世界不可比是结构性的。
8. tool 门槛的"预算"语义：一次性消耗（H-1）是否等于"本轮不许超过装配时点可用量"。

### 明确**不是**回归（本次澄清）

- **B 世界债务 −98.3%**：真实状态变化且**没有"少结债"证据**；消失的是"巨债的建立"（§1.3）。
- **A 世界 `tool-budget-exhausted` 可达**：是实现账本断言被证伪（§2），不是本轮代码的新缺陷；
  缺陷是**判据时点**（§2.3），收窄即可。

---

## 6. 我没做 / 没验证的（如实）

1. **未跑 Maven、未起世界、未改任何文件**（除本账本）；结论只基于只读代码 + `/tmp` 既有日志/dump 的重新聚合。
2. **未逐笔证明** G3c 里那 20 条巨债的**每一个**借款人在 G3c 也没拿到等量 in-kind 粮信用：只做了 1 笔 1:1 逐值
   （99,625 → 102,399）+ 另 7 笔量级吻合，且给出"G3c 该出借人 in-kind 粮信用 = 0 笔"的整体证据。
3. **未证明**被拦的 `select` 尝试中有多少槽位后来在别处成交（§3.3 ①是代码结构推论 + 计数对比，不是逐笔追踪）。
4. 未拆 `NO_BUDGET`/`noMoney`/`PORT_THROTTLED` 的分侧；未核 A 世界 75→78 条巨债为何全是 `DEFAULTED`（B 是 `NORMAL`）。
5. 未覆盖 3600/3650 tick、`three-powers`、N7 复跑；未核 `-Dsimos.*` 其它档位的日志覆盖差异（G3b §6-2 已记）。
6. 未核 `MerchantCapacity.of` 的 `capacityMilli` 是否随 tool 变化而变（本诊断不依赖它；§2 只用到 `toolMilli`）。

## 7. 复现命令（只读，全部可重跑）

```bash
cd /home/cna/SimulatorMosire
git log --oneline 23e11653..HEAD -S'debtPrincipal' | cat                 # 只有 docs/test 三条
git diff 23e11653..HEAD -- simos-app/src/main/java/io/mosire/simos/app/gui/ApiViews.java | grep -n 'debtPrincipal\|debtsOf'
git log --oneline -S'MERCHANT_CAPACITY_HOUSEHOLD' | cat                  # 首现 2488b3e1（M-A1）

# 债务：状态侧自证（两轮合同的引用表）
grep -m1 'DEBT_REFERENCES_REBUILT' /tmp/g3b-runB.log /tmp/g3c-runB.log    # total=1466 vs total=1092
# 债务：逐合同（需 /tmp/g3b-state-b.json、/tmp/g3c-state-b.json）
python3 - <<'PY'
import json,collections
for f in ('/tmp/g3b-state-b.json','/tmp/g3c-state-b.json'):
    d=json.load(open(f)); rows=[]
    for h,v in d.items():
        if h.startswith('__'): continue
        for c in v['classes']:
            for dd in (c.get('debtDetails') or []):
                if dd.get('direction')=='payable': rows.append(dd)
    big=[r for r in rows if r['principal']>10000]
    print(f, 'payable', len(rows), 'sum', sum(r['principal'] for r in rows),
          '>10k', len(big), sum(r['principal'] for r in big),
          collections.Counter((r['unitKind'],r['status'],r['counterparty']) for r in big).most_common(2))
PY
# 债务：in-kind 粮信用按出借人（G3b vs G3c）
grep -c 'MARKET_CREDIT_FILL .*lenderOrSeller=hh-0_0-rural-landlord.*unit=commodity:grain' /tmp/g3b-runB.log /tmp/g3c-runB.log

# tool 预算镜像
grep -m3 'reason=tool-budget-exhausted' /tmp/g3c-runA.log | cut -c1-460    # stock 1610 / budget 925 ……

# 运力读数口径
grep -c 'event=MARKET_UNFILLED .*reason=LOGISTICS_CAPACITY' /tmp/g3c-runB.log   # 397（槽位/日）
#   ★ 必须锚事件名：裸 grep 'reason=LOGISTICS_CAPACITY' = 794，因为同一批事实还各落一条
#     MARKET_SELLER_OUTCOME(378) / MARKET_BUYER_OUTCOME(19)（collectSellerOutcomes/collectBuyerOutcomes）。
grep -c 'MERCHANT_HAUL_TOOL_BLOCKED_AT_SELECT' /tmp/g3c-runB.log                # 506（ΣblockedRuns=4,102，条目×调用）
```

★ **落盘提醒**：`.superpowers/sdd/.gitignore` 内容为 `*` ⇒ 本账本（及同目录所有账本）**默认被 git 忽略**，
要用 `git add -f .superpowers/sdd/2026-10-10-diagnose-debt-anomaly/impl-ledger.md` 才会进提交
（既有账本都是被 `-f` 加进来的：`git ls-files .superpowers/sdd/2026-10-10-g3c-verify-tool-gate/` 有输出）。
