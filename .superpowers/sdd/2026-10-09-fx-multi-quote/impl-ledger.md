# 实现架构账本：同币对多份报价并存 + 家户比价 + 冲突日志（A 批）

> **责任区**：设计书 §4.4（G3 核心）——`FxSettlement` 的多窗口并存、`PairRates` 最优摘要、冲突 INFO 日志、
> 多窗口撮合核对、**禁止按区收窄撮合域**。
> **约束设计书**：`docs/superpowers/specs/2026-10-09-market-zone-and-gov-money-design.md` §4.4 / §5 / §6 / §7（A 批）。
> **基线**：`87489770`（对照轮用 `git worktree` 取同一提交编译，见 §6）。
> **改动文件（2）**：
> - `simos-economy/src/main/java/io/mosire/simos/economy/time/FxSettlement.java`（md5 `13cef18f0c1425941c9dc3af5623163e`）
> - `simos-economy/src/main/java/io/mosire/simos/economy/time/CurrencyValuation.java`（md5 `975947a9deb243a617d91d4807e3c38d`）
>
> 未改：`EconomyData` / ChangeSet / Codec / `MarketZone` / `Government` / `FxRoundInput` / `GovFxWindow` /
> 任何 `pom.xml` / 任何 `src/test/**` / `docs/**`。**未跑** test/verify/package，**未** `git commit`。

---

## 1. 关键调查结论（`file:line` → 结论 → 影响）

| # | 证据（改前行号） | 结论 | 影响 |
|---|---|---|---|
| S1 | `FxSettlement.java:101-103`（`pairs.putIfAbsent`） | 同币对多份报价时，后到者被静默丢弃的**只有"摘要" `PairRates`**（家户挂单价的锚）。 | ★ **与设计书 F9 / 派单书的措辞不符**：见 S2。 |
| S2 | `FxSettlement.java:189-207`（建簿段：先按 `pairs` 建空簿，再**遍历 `windows`** 逐窗口 `computeIfAbsent` + `book.add`） | **窗口单本来就全部进簿**（每条窗口各 1 张买单 + ≤1 张卖单），与 `pairs` 无关。 | ★ 派单"改动前应只有第一个生效"不成立；实测：HEAD 的 mq-asks 场景里**第二个窗口（gov-tp-copper）照样成交了 99,936**。F9 描述的缺陷在改前**只影响家户锚价**，不影响"窗口是否在簿里"。 |
| S3 | `FxSettlement.java:246-285`（`planHouseholdOrders` 遍历 `pairs.values()`） | `pairs` 是**每币对一条**的唯一摘要，喂家户两条规则：买单限价 = `askPerMille`、卖单底价 = `bidPerMille×(1−100‰)`。 | 改摘要口径即改家户的价格判断 ⇒ 本批的数值行为改变全部由它引起。 |
| S4 | `FxSettlement.java:696`（`private record PairRates`） | 改前摘要 = **规范序第一个窗口**的 bid/ask（`FxRoundInput` 逐 GOV 升序、逐币对升序 ⇒ 与 GOV id 排序耦合）。 | 家户锚价依赖"哪个 GOV 的 id 更小"，是一种不可见的次序口径。 |
| S5 | `FxSettlement.java:439`（原 `HexCoord location = ask.hex != null ? ask.hex : bid.hex;`）+ `Order.window` 改前恒传 `hex = null` | 两条**窗口单**互相交叉时（同币对多窗口：GOV-A 的买价 ≥ GOV-B 的卖价）两侧 `hex` 都是 null ⇒ `location = null` ⇒ `Transfer` 构造期抛 `IllegalArgumentException`。 | ★ **整轮日结算被炸掉**（不是具名拒，是异常穿透 `CoreSimos.submit`）。改前实测崩溃：`HEAD-mq-cross` 探针。这是"同币对多窗口"直接开启的路径（C 批已允许同区多政府挂价）。 |
| S6 | `CurrencyValuation.java:285-295`（原 `edges.get(...).putIfAbsent(...)`，注释自称"先到先得"） | 同一币对多份报价时，"世界行情表"只取**第一条**的中价；且该条取自窗口序 ⇒ **改前是次序相关的**（实测：同一对报价，窗口倒序 ⇒ V(silver)=850 vs 1025）。 | E 批自己的注释把"按区/按 GOV 的报价冲突"留给 A 批 ⇒ 本批一并纠正（见 §4）。 |
| S7 | `FxSettlement.java:344-364`（自配对守卫 `bid.owner.equals(ask.owner)`） | 该守卫在**同一窗口**自己的买/卖单相遇时**不可达**：交叉判据 `bid.limit < ask.limit` 先 break（同窗口恒 bid < ask，相反报价两侧停做）。 | 无需改动；但它**不覆盖**"两条不同窗口单互相成交"（那是合法成交，不是我引入的）。 |
| S8 | `FxRoundInput.java:114-132` / `MarketZoneBook.effectiveRatesOf:318-330`（F8/F10/F11） | 窗口装配 = 逐 GOV × 每一条生效报价（区级优先、按币对回落）；**一个 GOV 一个币对恰一条**；一个 GOV 被多区覆盖且同币对冲突时取规范序第一个区。 | F11 那条**不动**（理由见 §3）；它是设计书 §9 的待裁定项，且被覆盖的报价已有 DEBUG 具名（`logFxWindows`）。 |
| S9 | `SettlementIndex.java:176`（`householdOfActor = householdByActor(householdEconomies)`） | 窗口被接受的前提是 `round.householdOfActor().get(treasury) != null` ⇒ 该国库户**必有** `householdEconomies` 行 ⇒ 新增的"窗口格"恒非 null。 | `location` 的备用取值（卖方窗口的国库户格）**结构性非空**，不需要额外的 null 兜底。 |

## 2. 实现架构（内部组件拆分与数据流）

```
FxSettlement.match(round, ...)
  ① 逐窗口（input.windows()，序不变）
       · quotesByPair[pairKey] += PairQuote(govId, rate)      ← 只服务冲突日志
       · pairs[pairKey] = pairs[pairKey].best(new)            ← ★ 唯一口径：bid=max、ask=min（§4.4）
       · 国库解析 / 容量（GovFxWindow.quote）/ 两侧停做日志与具名拒 —— 一字未动
       · WindowState(spec, treasury, quote, hex=国库户所在格)  ← ★ 新增 hex（只服务窗口×窗口成交的 location）
  ①.5 logMultipleQuotes(day, quotesByPair, pairs)             ← ★ 多份【不同】⇒ 一条 INFO；否则零输出
  ② 建簿：按 pairs 建空簿 + 逐窗口各加 1 买/≤1 卖（每条窗口单带自己的 hex）  ← 结构未变
  ③ matchBook：bids 降序 / asks 升序、价格优先、吃深度、自配对守卫 —— 未变；仅 location 取值规则改（见 §3.3）
  ④ attributeResidue / finish —— 未变
```

- **`PairRates.best(PairRates)`**：合并口径的**唯一拼写点**（max bid / min ask）。用 `TreeMap.merge` 表达，键仍是
  `OfficialRate.keyOf(base, quote)` ⇒ **`pairs` 仍是"每币对一条"**，`planHouseholdOrders` 的家户单仍是"每户每币对至多一条"。
- **`PairQuote(GovernmentId, OfficialRate)` + `logMultipleQuotes`**：逐轮瞬态的"留名"表，不进状态、不进变更集。
- **`WindowState.hex` / `Order.window(..., hex)`**：窗口单第一次有了格；`regionId` 仍是 `""`。
- **`CurrencyValuation.quotedValues`**：先把同一币对合并成"最优一份"（同一 max/min 口径），再在**该币对第一次出现的
  窗口位置**投一条有向边 ⇒ 单份报价世界的边序/取值逐值不变，多份报价世界不再依赖窗口序。

## 3. 关键判断（为什么这样拆 / 推翻过什么）

### 3.1 为什么 `pairs` 保持"每币对一条"而不是改成 per-window
家户规则是"每户每币对一条挂单"（`planHouseholdOrders` 的 `continue`/互斥）。若把摘要改成 per-window 的列表，
同一户会在同一币对上挂多张买单 ⇒ **需求量按窗口数放大**（正是派单 A-5 要防的"重复投放/数值放大"）。
实测对照（§5 A-5）：2 窗口同价 vs 1 窗口，`FX_ORDER_PLAN buyOrders` **都是 145**。

### 3.2 为什么 A 批不把撮合域按区收窄
设计书 §1.3 / §2.2 已作废该问法（家户自己看价、直接买）。本批一次都没引入"区"维度。

### 3.3 窗口×窗口成交：**推翻过自己一次**
初判想按 M3「每笔买入必有对手方（**家户**）」把"两条窗口单互不成交"写成一条新禁令。放弃的理由：
① §4.4 明写"簿内所有窗口单按 `limitPerMille` 价格优先 ⇒ 最优价先成交"，没排除窗口之间的交叉；
② 要真排除，必须改 `matchBook` 的双指针语义（同一次扫描里丢掉可成交的"窗口×家户"组合），
   而现有世界的行为会因此漂移 —— 本批的硬约束是"旧世界逐值不变"；
③ 这是**政策**问题（两个政府互相套利会吃掉留给家户的便宜容量），不该由实现方发明。
⇒ 本批只修**崩溃本身**：给窗口单一个真实、稳定的格（国库户所在格），且**有家户参与时 location 逐字保持旧口径**
（`ask.window ? bid.hex : ask.hex`：家户那一侧优先）。**政策问题作为待裁定上报（见 §7）**。

### 3.4 为什么冲突日志只点 GOV 的名、不点区
`FxRoundInput.Window` 不携带区身份（区级覆盖是"投到该区法定币发行者窗口上"的**一条生效报价**）。要报区名就得改
`Window` 的形状 ⇒ 越界（本批"无状态改动/无新组件"）。被区覆盖而没生效的那几条**已有** DEBUG 具名
（`EconomySettlement#logFxWindows` / `FX_WINDOW_ASSEMBLED`），与本条不重复。

### 3.5 为什么顺手纠正 `CurrencyValuation`
派单第 3 条要求"发现别处依赖'同币对单窗口'一并纠正并记账"；该文件自己的注释把"报价冲突"留给 A 批（§1 S6）。
它改前**次序相关**（同一对报价换个窗口顺序，V 从 850 变 1025）——那不是"确定序"，是"GOV id 序"。现在与 `FxSettlement`
共用同一个 max/min 口径（**不新增第二套口径**），单份报价世界逐值不变（§5 估值隔离探针）。

### 3.6 F11（一个 GOV 被多区覆盖、同币对冲突）为什么不动
同一条国库若对同一币对发两条窗口单，就是"同一笔储备被投放两次"（`FxRoundInput` F10 注释明说这是静默的数值放大）。
该点仍是设计书 §9 的待裁定开放点。

## 4. 偏离记录（与约束设计书 / 派单书不一致处，主动报）

| # | 偏离 | 事实依据 |
|---|---|---|
| D1 | **设计书 F9 / 派单"同币对只留第一个报价、后来的静默丢弃"不准确**：改前被丢的只有**家户锚价摘要**，窗口单本来就全部进簿、都能成交。 | §1 S1/S2 + 实测 `HEAD-mq-asks`：第二个窗口（gov-tp-copper）在改前成交 99,936。 |
| D2 | **派单 A-2「先吃 900，900 吃完才吃 950」在 §4.4 的"限价 = 最低卖价"口径下不可能发生**：限价 900 的家户不会去成交 950 的卖单。实测：**改前**恰恰是"900 吃完才吃 950"（限价取第一个窗口的 950）；**改后**只吃 900、950 窗口零成交。 | §5 A-2 逐笔读数。⇒ 设计书 §6.1 A5 的第二半与 §4.4 自相矛盾，**待控制方/用户裁定**（见 §7）。 |
| D3 | **派单 A-1「改动前应只有第一个生效」不成立**（同 D1）。 | §5 A-1。 |
| D4 | **额外改动 `CurrencyValuation`**（超出 §4.4 字面范围，属同一责任区内的"同币对单窗口假设"同族缺陷）。 | §1 S6、§3.5、§5 估值隔离探针。 |
| D5 | 新增"窗口单带格"（`WindowState.hex`），设计书未提。 | §1 S5（改前直接崩溃）。**有家户参与时 location 逐字不变**，A-4 实测为证。 |

## 5. 探针（真装配，零手动 `core.register`）

**装置**：`/tmp/fxprobe/src/io/mosire/simos/app/FxMultiQuoteProbe.java`（包 = `io.mosire.simos.app`，故可调
`ShellMain.seedGenesisIfEmpty` = 真创世路径）。真 `Shell.start`（真 codec/handler/participant 装配 + 真 GUI/MCP 端口）
+ 真世界生成器（`--world` 等价：`small-world` / `three-powers`）+ 真 GM 命令 `economy.SetOfficialRate` /
`actor.AdjustAccounts`。探针只**读**状态（读数三件套：`state.module("economy") → EconomySnapshot.data()`、
`ActorSnapshot.data()`、`FxRoundInput.of(...)`、`GovFxWindow.quote(...)`），**不注册任何东西**。

**跑法**（编译 = 派单允许的那一条 Maven；探针 = javac/java）：
```bash
tools/mvn-lock.sh -q -o -DskipTests compile -pl simos-app -am
CP=$(ls -d simos-*/target/classes | tr '\n' ':')simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar
javac -nowarn -d /tmp/fxprobe/out -cp "$CP" /tmp/fxprobe/src/io/mosire/simos/app/FxMultiQuoteProbe.java
java -cp "/tmp/fxprobe/out:$CP" io.mosire.simos.app.FxMultiQuoteProbe <scenario> <store> 5 <port>
```
**改动前对照**：`git worktree add /tmp/fxprobe/head-wt 87489770` + 同一份探针源码编译运行（`HEAD-*.log`）。
**改动后**：主树（`FINAL-*.log`，与中间轮 `NEW-*.log` 的 FX/digest 行 **diff = 0**，行数 8/24/318/314/319/312/25 逐项相等）。

### A-1 并存（同一币对、两个 GOV、不同价）
- 窗口读数（改前后相同，2 个窗口都在）：
  `pair=silver|copper windows=2 {gov-tp-copper bid=800 ask=1000 reserve=100000 sellCap=100000}{gov-tp-silver bid=880 ask=900 reserve=200000 sellCap=200000}`
- **两个窗口都能成交**（改后 `FINAL-mq-cross`，第 3 天一本簿里两条成交）：
  - `buyer=hh-gov-gov-tp-copper seller=hh-gov-gov-tp-silver baseMilli=105263 quoteMilli=97369 pricePerMille=925 venue=gov_window`
  - `buyer=HOUSEHOLD:hh-0_-3-urban-landlord seller=hh-gov-gov-tp-silver baseMilli=3247 quoteMilli=2923 pricePerMille=900 venue=gov_window`
  - `FX_ROUND day=3 windows=2 fills=2 baseVolumeMilli=108510 windowFills=2`
- **改前该场景崩溃**（`HEAD-mq-cross`）：`Exception in thread "main" java.lang.IllegalArgumentException: Transfer.location 不得为 null（账户 = (actor, location)）`。
- 改前**第二个窗口本来也成交**（证伪派单假设）：`HEAD-mq-asks day=3` 两条成交的卖方分别是 `hh-gov-gov-tp-silver`（900）与 `hh-gov-gov-tp-copper`（950）。

### A-2 比价（卖价 950 / 900；改前后逐笔）
| 轮 | 成交（卖方 / 数量 / 价） | `FX_ROUND` |
|---|---|---|
| HEAD（限价 = 第一个窗口的 950） | ① `gov-tp-silver` 199,936 @**925**（=mid(950,900)，**多付 25‰**）② `gov-tp-copper` 99,936 @**950** ← 正是"900 吃完才吃 950" | `fills=2 baseVolumeMilli=299872 windowFills=2` |
| FINAL（限价 = 最低卖价 900） | ① `gov-tp-silver` 199,936 @**900** ② **无**（950 窗口零成交） | `fills=1 baseVolumeMilli=199936 windowFills=1` |
- 买侧（家户卖银，最高买价口径；`mq-bids`）：同一笔由 `hh-gov-gov-tp-copper` 作买方，`baseMilli=125000`
  成交价 **760 → 796**（改前底价 = 第一个窗口 bid 800×0.9=720；改后 = 最高买价 880×0.9=792）。
- 冲突日志给出摘要取值：`summaryBidPerMille=850 summaryAskPerMille=900`（= max bid / min ask）。

### A-3 日志
- 多份**不同**报价 ⇒ INFO 具名（改后，逐轮一条；改前 0 条）：
  `event=FX_PAIR_MULTIPLE_QUOTES origin=economy-fx originKind=tick day=3 pair=silver|copper quotes=2 distinctQuotes=2 detail=gov-unit-gov-tp-copper:850/950, gov-unit-gov-tp-silver:800/900 bestBid=gov-unit-gov-tp-copper=850 bestAsk=gov-unit-gov-tp-silver=900 summaryBidPerMille=850 summaryAskPerMille=900`
- 多份**相同**报价（`mq-identical`：880/900 ×2）⇒ **0 条**；不同币对各一份（`three-pairs`）⇒ **0 条**；无窗口（`small-world`）⇒ **0 条**。
- 改前 `mq-asks` 的该事件计数 = **0**（静默）。

### A-4 旧世界不动（逐值摘要 + FX 面逐行）
摘要 = `PROBE_DIGEST`（SHA-256 over：逐户 id/格/人口/劳动/参与率/需求 + 家户账余额与冻结 + 逐格市场计价币与价格 +
逐 GOV issuable/报价/国库账 + 逐区 `describe` + 头部 revision），每轮 6 个采样点（day 0/1/2/3/4/5）：
- `small-world`（单币、无窗口）：**6/6 SAME**（`a637594c… / 47df367b… / 3fe645e8… / 063b4768… / 93ee5c79… / 8b49b0c2…`）
- `three-powers`（两窗口、**不同币对**，本批对它应零影响）：**6/6 SAME**，且 `event=FX_*` 日志**逐行 diff 为空**。
- 附加零影响对照：`mq-identical`（同币对两份**相同**价）**6/6 SAME**、`mq-single`（单窗口）**6/6 SAME**。
- 预期变化（如实列）：`mq-asks` / `mq-bids` / `mq-cross` 在**开市日 day3 起 DIFF**（改动目标所在）。

### A-5 负向（不得重复投放 / 数值放大）
同一份注资（铜区 146 户各 +3,000,000 毫铜）、同一价位 880/900：
| 场景 | 窗口 | 家户买单数（`FX_ORDER_PLAN`） | 成交 | 各卖方 |
|---|---|---|---|---|
| `mq-identical` | **2** | **145** | 99,936 + 199,936 = **299,872** | `gov-tp-copper`（其储备 100,000）/ `gov-tp-silver`（其储备 200,000） |
| `mq-single` | 1 | **145** | 199,936 | `gov-tp-silver`（其储备 200,000） |
- **家户订单数不随窗口数增长**（145 = 145）⇒ 没有"同一户按窗口数重复挂单"。
- **总量 = 各自国库的实有**，不是单窗口的倍数：`299,872 = (100,000 + 200,000) − 128`（128 = 2 天 × 32 毫银 × 2 个 GOV 的日俸）；
  `mq-single`：`199,936 = 200,000 − 64`。每个国库在成交里**恰出现一次**（逐笔 `FX_FILL` 的 `seller` 去重后 = 2）。
- **没有任何一笔超过对应国库的可花额**（对照 `PROBE_PAIR` 的 `reserve` 读数）。

### 附：估值口径隔离探针（`FxValuationQuoteProbe`，合成入参，非真装配）
| 用例 | 改前 | 改后 |
|---|---|---|
| 同币对 800/900 然后 950/1100 | V(silver)=**850** | V(silver)=**925** |
| 同币对 950/1100 然后 800/900（倒序） | V(silver)=**1025**（★ 次序相关） | V(silver)=**925**（次序无关） |
| 同币对两份相同价 880/900 | 890 | 890（不变） |
| 单份报价 880/900 | 890 | 890（不变） |
| 反向币对（silver\|copper + copper\|silver） | 850 | 850（不变） |

## 6. 会改变数值行为的清单（给测试代理当输入）

1. **任何"同一币对 ≥2 份不同报价"的世界**（本批的目标形态）：
   - 家户买单限价：`第一个窗口的 sellPerMille` → `所有窗口 sellPerMille 的最小值`；
   - 家户卖单底价：`第一个窗口的 buyPerMille × 0.9` → `所有窗口 buyPerMille 的最大值 × 0.9`；
   - ⇒ 成交价、成交量、成交对手方、家户余额、国库余额都会变（实测例：900 窗口 925→900；950 窗口有→无；
     家户卖银 760→796）。
2. **`CurrencyValuation`（世界行情表 V）**：同币对多份报价时，边权中价 = 第一条 → `(max bid + min ask)/2`；
   次序相关 → 次序无关。会传导到"家户收币按估值"的判据与跨币成交比例。
3. **两条窗口单互相交叉的世界**（同币对多窗口且 GOV-A 买价 ≥ GOV-B 卖价）：改前**崩**（整轮日结算异常穿透），
   改后**成交**（窗口×窗口，`location` = 卖方窗口的国库户格）。这是"从不可用变为可用"，不是数值微调。
4. **机制口径**：新增 INFO 事件 `FX_PAIR_MULTIPLE_QUOTES`（只在"同币对 ≥2 份不同报价"时发）。
5. 不受影响（实测逐值不变）：单份报价的世界、不同币对多窗口的世界（`three-powers`）、无窗口的世界
   （`small-world`）、同币对多份**相同**报价的世界。

## 7. 会让既有测试失效的清单

- **静态结论（实测）**：`grep -rln "FxSettlement\|FxRoundInput\|CurrencyValuation\|GovFxWindow" simos-*/src/test` = **0 命中**；
  `grep -rn "three-powers\|ThreePowersWorld" simos-*/src/test` = **0 命中**。⇒ **本仓当前没有任何直接覆盖 FX 撮合 /
  行情表 / three-powers 的测试**，所以"因本批而红的既有用例"预期为 **0 条**。
- 间接提及 FX 名字的三个用例（未运行验证，只做静态判断）：`SimosToolsTest`（工具目录/样本，含
  `economy.SetOfficialRate` 载荷样本）、`McpServerTest`、`McpPortTopologyTest` —— 它们不设价、不推进市场轮
  ⇒ 预期不受影响。
- ★ **未跑 `test` / `verify`**（派单只允许 compile）⇒ 以上是**静态判断**，不是"测试通过"。

## 8. 未完成 / 未验证 / BLOCKED

1. **未跑 `test` / `verify` / `package`**（派单硬约束）；既有回归网**没有**被本批运行过 ⇒ 行为回归的验证责任在测试代理。
2. **未跑 `spotless:apply`**（派单只允许 `compile` 一条命令）。已手工把新增/改写的 Javadoc 按"贪心填到 ≤100 列"折行，
   并自查"无相邻可被 GJF 合并的行"、无 >100 列的行；但**不保证**与 `google-java-format` 输出逐字节一致
   ⇒ **关账前请控制方跑一次 `tools/mvn-lock.sh -q spotless:apply` 再 `verify`**。
3. **G3 复测（设计书 §7 明确要求 A 批次复测）未做**：本批探针是 5 天窗口，没有跑 360 天三国世界对照
   "成交衰减 6→4→2→1→0 是否改善"。⇒ **未验证，不粉饰**。
4. `corridor` 世界、30 tick 数值 diff（设计书 §6.3 提到）**未跑**；只跑了 `small-world` / `three-powers` 各 5 tick。
5. `CurrencyValuation` 的改动只有**单元隔离探针**（合成 `FxRoundInput`）证据；**没有**在真世界里跑"异币支付按估值"
   的端到端读数 ⇒ 该路径的数值影响**未端到端验证**。
6. **待裁定（不是实现困难，是设计与派单自相矛盾）**：
   - 派单 A-2 / 设计书 §6.1 A5 的"900 吃完才吃 950" vs §4.4 的"家户限价 = 最低卖价"。本批按 §4.4 实现
     （也更贴用户原话「货比三家自己看哪个划算然后接受」「挑最划算的」）；若控制方要 A5 的字面行为，
     需要另一种语义（例如"剩余需求按下一档最优价重新挂单" = 走簿），那是**另一批**的改动，且会动到
     "每户每币对至多一条挂单"的口径（A-5 的防放大前提）。
   - **窗口×窗口成交**（两个政府互相套利、吃掉留给家户的便宜容量）是否允许：本批只修了崩溃，未下禁令（§3.3）。
7. **BLOCKED**：无（本批的交付物可编译、可复现；上面第 6 条是待裁定，不阻塞本批落地）。
