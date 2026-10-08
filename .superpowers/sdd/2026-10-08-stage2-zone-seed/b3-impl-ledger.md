# 实现架构账本 —— 阶段 2-B3：`three-powers` 创世落 3 个持久市场区

> 责任区：**B3**（阶段 2-B 第三批，收尾；约束设计书 `docs/superpowers/specs/2026-10-08-currency-exchange-stage2-design.md`
> §4.1/§4.2、§5 **I22**/I23、§6.2 **G1**/G2、§8 的 B2/B3 收口）。
> 形态：AGENTS §一.5「一个责任区一个写代理」——只写生产代码、只过**编译**、不写/不跑测试、不 `git commit`、不 `package`。
> 证据：探针 `/tmp/b3-probe/`（**不进仓库、不进 `src/test`**；四个 JVM 轮全部零 `core.register`）。
> 本批目标一句话：**让 `three-powers` 的创世直接把 3 个市场区落成持久状态**（B2 交付了机制，但没有世界用它）。

---

## 1. 关键调查结论（file:line → 结论 → 影响）

### 1.1 为什么必须做本批（B2 的缺口）

| # | 事实 | 位置 | 影响 |
|---|---|---|---|
| 1 | B2 已交付机制：`EconomyData` 第 36 组件 `marketZones` + Codec/ChangeSet/往返不变式 + 3 条 GM 命令 + `MarketTopologyBook` **持久区优先** | `EconomyData.java:234-285`、`MarketTopologyBook.java:151-161`、`MarketZoneReadout.java:104-125` | 机制齐了，但**空表 = 走派生**（逐字保留） ⇒ 只要没有世界写区表，I22 就没有生效对象 |
| 2 | `three-powers` 创世（B1）**从不写区表**：`marketZones` 恒为空 ⇒ 拓扑恒走"城市 + tier 半径"派生 | `ThreePowersWorld.java:307-370`（创世命令序）、`MarketTopologyBook.java:176-178`（`byCityRadius`） | 该世界的"3 个市场区"是**每轮现算**的读数，不是状态；划界/退让/覆盖/合并**无处落笔**（B2 的三条命令在这个世界上没有对象可作用） |
| 3 | 派生路径在同一份世界形态上给出 3 个区、成员格 = 行政区 hex 集（半径 8 覆盖全域） | `MarketTopologyBook.java:130-133,371-388`、`ThreePowersWorld.java:491-497`（`zoneOf` 与"半径内最近节点"同规则）、B2 探针第 0/1 段实测 | ⇒ 本批可以做到"**只换权威、不改读数**"（实测见 §6.3 第 5 段） |

### 1.2 ★ 命令序依赖（逐条回代码核过；这是本批唯一不可换的东西）

`economy.DefineMarketZone` 的全部前置条件（`EconomyDefineMarketZoneHandler.handle`）：

| 依赖 | handler 里的判据（file:line） | 由哪条创世命令满足（file:line） | 因此必须在它之后 |
|---|---|---|---|
| ① 法定币 ∈ 世界词表 | `EconomyDefineMarketZoneHandler.java:143-155`（`base.currencies()`） | 铜/金：逐 GOV 的 `economy.DefineCurrency`（`ThreePowersGovBootstrap.java:527-532`，TYPE 锚点 `:529`） | **最后一个 GOV 的 DefineCurrency** |
| ② 发行 GOV 已登记 | `:156-165`（`base.governments()[GovernmentIds.ofUnit(govUnitId)]`） | 逐 GOV 的 `economy.RegisterGovernment`（`ThreePowersGovBootstrap.java:518-523`，TYPE 锚点 `:520`） | 最后一个 GOV 的 RegisterGovernment（银的"发行人"也在这里声明） |
| ③ 该 GOV 的 `issuable` 含该法定币 | `:166-178`（`government.issuable()`） | 银：②的 `issuable` 声明；铜/金：①的发行人登记 | ①与② |
| ④ 锚格有市场行 | `:134-142`（`base.markets()`） | `economy.Seed`（`ThreePowersWorld.java:352-357`，本世界第一条经济命令） | 早已满足 |
| ⑤ 成员格计价币 = 法定币 | `:194-210`（逐格 `market.numeraire()`） | `economy.Seed` 的逐格 `numeraire = zoneOf(hex) 的法定币`（`ThreePowersWorld.java:327` + `:531`） | 早已满足 |
| ⑥ 成员格不属于别的区 | `:179-193`（`MarketZoneBook.zoneOfHex`） | 本批三条命令**各定义一区、两两不相交** | 顺序内自洽 |
| 状态层第二道闸 | `EconomyData.java:1799-1849`（法定币在词表 / 发行政府已登记 / `issuable` 含该币 / 一个 hex 至多一区） | 同上 | 任何路径都造不出坏区表 |
| 原子性 | `CoreSimos.java:284-315`：`bootstrapGenesis` 只在最后写**一条 revision + 一份 checkpoint** | — | 创世期任何一步抛 ⇒ **一个字节都不落盘**（不是"半套世界"） |

★ **`unit.SetJurisdiction` 不是硬依赖**（如实记）：`DefineMarketZone` 的 handler 只读 `EconomyData`（它的 import 块
`EconomyDefineMarketZoneHandler.java:3-31` 里没有任何 unit 类型），区成员格取自 `map.regions()`（`ThreePowersWorld.java:581-597`），
**不读** unit 的辖区状态。但"区 = 该 GOV 的辖区"在语义上成对，且任务书要求落在所有 `SetJurisdiction`（`:535-540`，TYPE 锚点 `:537`）
之后 ⇒ 实际落点取**整个 per-GOV 循环之后**，同时满足两者。

### 1.3 ★ 落点选择与理由（含"更早/更晚"的核对）

```
ThreePowersGovBootstrap.apply(...)
  ├─ for (GovSpec spec : ordered) { bootstrapGov(...) }      // :328-330（含 RegisterGovernment/DefineCurrency/SetJurisdiction）
  ├─ ★ ThreePowersMarketZones.define(next, applier, map, zoneSpecsOf(ordered))   // :331-335 ← 本批新增
  ├─ actor.AdjustAccounts（国库注资）                          // :336-343
  └─ economy.RecordMoneyIssuance × GOV × 币种                  // :344-347
```

- **最早可行点** = 最后一个 GOV 的 `economy.DefineCurrency` 之后（依赖表 ①②③；`SetJurisdiction` 在它之前一两条命令）。
- **本批取的点** = per-GOV 循环结束之后、`AdjustAccounts` 之前：
  1. 在**所有** SetJurisdiction 之后（任务书要求）；
  2. 在国库注资/发行审计之前 ⇒ 区表自检失败时**铸币与审计都还没发生**，创世整次失败（fail-closed 最干净）；
  3. 与 B1 的类注"固定命令序"同处一个文件（命令序**只有一个拼写点**，不会两处漂）。
- **更晚的可行落点**（已核对，确实可行，但未采用）：`ThreePowersWorld.state` 里 `ThreePowersGovBootstrap.apply(...)` 返回之后再落区
  （即 `AdjustAccounts` + `RecordMoneyIssuance` 之后）。handler 的判据逐条仍满足（它不读国库余额、不读发行审计），但它①不再落在
  §1.2 的文档序里（命令序被拆到两个文件），②自检失败的时点被推到铸币之后。**结论**：可换，但换来的是更差的可读性与更晚的失败点。
- **更早的落点**（逐条否决）：① 单 GOV 循环内（该 GOV 的 DefineCurrency 之后）——不行：后面 GOV 的币还没进词表/还没登记，
  且区表会读到"半套 GOV"；② `economy.Seed` 之后立刻落区——不行：`currency-not-defined` / `gov-not-registered`（铜/金）必拒。

---

## 2. 实现架构（代码怎么把这个世界长出来）

### 2.1 新增：`ThreePowersMarketZones`（`simos-app/.../app/world/`，包内可见）

```
record ZoneSpec(zoneId, anchor, jurisdictionRegionId, govUnitId, legalTender)   // :123-137
  ↑ 只读这五件事 ⇒ 创世自检与负向对照只有一个口子（故意给错法定币/给错辖区都在这里注入）

zoneSpecsOf(List<GovSpec>)   :153     // GovSpec → ZoneSpec；法定币 = 该 GOV 发行的币（declaredIssuable ∪ newCurrency，恰一种）
define(state, applier, map, zones)  :183
  ├─ 逐区 economy.DefineMarketZone（真 handler + 真 EconomyCodec + 生产 CommandApplier）
  ├─ requireZones(...)       :219     // 5 族 / 12 具名 id（见 §3）
  └─ logZones(...)           :443     // 逐区 INFO + 汇总 INFO（§一.9）
payload(zone, map)           :409     // {zoneId, anchor, hexes(升序), legalTender, govUnitId, radiusHex, reason}
ZONE_RADIUS_HEX = 8          :91      // 声明半径（= City 档派生半径）；**不**参与成员格派生
fail(check, message)         :494     // 先 ERROR（具名 id + 原因）再返回待抛异常（契约故障不降级）
```

### 2.2 改动：`ThreePowersGovBootstrap`

- `GovSpec` **末尾**追加组件 `marketZoneId`（`@param marketZoneId` `:234-236`、record `:238-249`、校验 `:276`）：本 GOV 辖区对应的市场区 id。
  ★ 追加在**末尾**而不是中间：位置参数调用者若漏给就是**编译错**，不会出现"两个 String 相互顶替"的静默错位。
- `apply` 里新增落区一步（`:331-335`），并在类注里把命令序补成含本步的固定序（命令序块 `:76` 起、「全部 GOV 之后」段 `:92-95`、B3 落点说明段 `:98-112`）。

### 2.3 改动：`ThreePowersWorld`

- `govSpecs(...)` 三处传入 `SILVER_CITY_ID` / `COPPER_CITY_ID` / `GOLD_CITY_ID`（`:405-435`）——区 id = 城市 id。
- 类注更新：3 个区**在创世期就落成持久状态**（原"市场区仍是派生件"一段已与现实不符，按 §四"机制性描述回代码核"更正）；
  派生路径降级为"同一形状的默认值"。

### 2.4 数据流（一次创世，从头到尾）

```
map（3 个互斥行政区，并集 37 格）
 → social.SetPopulation/CreateCity/SeedGroups（3 城，tier=City）
 → economy.Seed（逐格 numeraire = 该格所属区的法定币；37 个市场行）
 → actor.Seed
 → [B1] 逐 GOV：RegisterGovernment → DefineCurrency（铜/金）→ SetJurisdiction → … → SetBudgetPolicy
 → [B3] 逐区 economy.DefineMarketZone（成员格 = 行政区 hex 集，锚 = 座位/城市格，法定币 = GOV 发行币，radius=8）
        + requireZones（5 族 12 具名 id）+ logZones（INFO）
 → actor.AdjustAccounts → economy.RecordMoneyIssuance
 → ThreePowersWorld.logGenesis（B1 的四条读数：仍 3 区 / 3 GOV / 3 币 / 37 格）
```
之后每个 tick：`MarketTopologyBook.from` 见区表非空 ⇒ `byPersistentZones`（`MarketTopologyBook.java:151-161,198-255`）
⇒ 成员格**照抄状态**（不再现算），读数与结算都取同一个权威（I22）。

---

## 3. 自检断言清单（5 族 → 12 个具名检查 id）

| 族（任务书/设计书） | 具名 id | 判据（逐格/逐值，不是比个数） |
|---|---|---|
| ① 区数 = 3 | `zone-count` | `economy.marketZones().size() == 声明区数`；失败信息列出实际区表 |
| — （防御） | `zone-missing` | 声明的区在状态里查无（命令被静默丢弃） |
| ② 每区 hexes = 对应行政区 hex 集 | `zone-hexes-vs-region` | 双向差集都为空；失败信息点名"少 N 格（前几个…）、多 M 格（前几个…）" |
| — （同族） | `zone-anchor-mismatch` | 状态里的锚格 = 声明锚格（= 座位格 = 城市格） |
| — （同族） | `zone-region-missing` | 声明的行政区不在当前地图里（装配故障） |
| — （同族） | `zone-hex-without-market` | 成员格逐格有市场行（保证持久成员集 = 派生路径的成员集，两条路径不漂） |
| — （同族） | `zone-tender-mismatch` | 状态里的法定币 = 声明值（载荷没被改写） |
| ③ 三区法定币两两不同 | `zone-tender-distinct` | `Set<CurrencyId>.size() == 区数` |
| ④ 每区发行 GOV 已登记且确实发行该币 | `zone-issuing-gov`（三种失败共用一个 id） | `governments[gov-unit-<govUnitId>] != null` ∧ `zone.issuingGov() == gov-unit-<govUnitId>` ∧ `gov.issuable() ∋ legalTender` |
| ⑤ 三区并集 = 全图且互斥 | `zone-hex-disjoint` | 逐格计数：每个 hex 恰属于一个区 |
| ⑤（续） | `zone-union-vs-jurisdiction` | 三区并集 = 各辖区并集（列双向差集） |
| ⑤（续） | `zone-union-vs-map` | 三区并集 = `map.hexes().keySet()`（37 格；列未覆盖格） |

- 检查顺序 = "先问区表长什么样（①/②），再问谁发行（④），最后问这张表铺满了吗（③/⑤）"，与 handler 的判据顺序同一口径。
- 任一不成立 ⇒ 先发 **ERROR**（事件 `THREE_POWERS_GENESIS_MARKET_ZONE_SELF_CHECK_FAILED`，字段 `check=<id>` + `message=`）
  再抛 `IllegalStateException("three-powers 创世市场区自检失败 [<id>]: …")`。
- 通过时发 DEBUG（`THREE_POWERS_GENESIS_MARKET_ZONE_SELF_CHECK_OK`，`zones=3 tenders=3 hexes=37 requiredSelfChecks=5`）。

---

## 4. 关键判断（为什么这样拆）

1. **区 id 取"城市 id"（= B1 派生路径的节点 id），而不是新造一个 id 体系**。派生的 `MarketZoneReadout` 报的 zoneId 就是拓扑节点 id
   （城市 id，`ThreePowersWorld.java:212` 的 `ZONE_IDS`）；持久区沿用同一字面 ⇒ 换权威时**读数里的区名一个都不变**（探针 §5 实测），
   GM 命令面与既有读数口径也不用两套名字。★ 反例（没做）：用 `govId` 当区 id —— 读数会从 `c-tp-*` 变成 `gov-tp-*`，"只换权威不改读数"这条就无法自证。
2. **半径 8 落在持久区里当"声明值"**。派生基线的半径来自 City 档（`MarketTopologyBook.java:88-93`）；持久区若取 0，会**悄悄关掉**
   `MarketTopology.adjacent` 的跨区可达候选（那是 B 阶段后续"跨区"的地基）。⇒ 取 8（与派生同值），并在类注里写明它**不**参与成员格派生
   （B2 的 §3 判断 2 同款口径）。
3. **把落区抽成独立类 `ThreePowersMarketZones`，而不是塞进 `ThreePowersGovBootstrap` 的循环里**：区落地只需要 5 个事实
   （`ZoneSpec`），与 GOV 的国库/官吏/编制无关；抽开后①创世自检与负向对照有唯一注入点，②GOV 链的"固定命令序"不被搅乱
   （只在尾部多一行调用），③将来别的世界要复用这条路时，依赖面是清楚的。
4. **落区放在 `AdjustAccounts` 之前**（§1.3）：自检失败时点最早、且"命令序"仍在一处。
5. **自检里"并集 = 全图"是这个世界形态的判据**（3 个辖区铺满 37 格，B1 §4.1）。★ 如实提示：将来若有"GOV 只覆盖部分地图"的世界复用
   `ThreePowersGovBootstrap`，`zone-union-vs-map` 会**具名报错**（不会静默降级）——那时必须显式决定"未覆盖格怎么办"（派生路径会把它们
   退化成单格区）。本批选择**响亮失败**而不是替未来的世界猜一个默认值。
6. **负向对照的注入点选在 `ZoneSpec`/状态层**：法定币不符走真命令（handler 具名拒），hexes 不符走自检（状态层篡改后直接调
   `requireZones`）——两条路都用**生产函数本体**，探针里没有第二份自检实现（避免"探针验的是探针"）。

---

## 5. 偏离约束设计书之处（主动记录）

1. **§4.2 的字段表里 `officialRate` 本批留空**：区表的 `officialRates` 逐区为空（`Map.of()`），GOV 级 `Government.officialRates`
   也逐 GOV 为空 ⇒ "设汇率前外汇面沉默"（任务书"明确不做"第 1 条）。★ 这不是遗漏：命令面（`economy.SetOfficialRate` 的 `marketZoneId` 扩展）
   与解析面（`MarketZoneBook.officialRateFor`）B2 已交付，控制方验收时可用 GM 命令设。
2. **自检条数**：任务书/设计书写 5 条；落成代码后展开为 **12 个具名 id**（族内每条判据各自具名，便于日志/探针定位）。族数仍是 5，
   日志字段记 `requiredSelfChecks=5`，具名 id 清单见 §3。
3. **半径 8 的出处**：设计书未规定持久区的半径值；本批取"与派生基线同值"（City 档上界 8）并在类注写明它只影响邻接判据与读数。
   ★ 若控制方认为 3 区世界应显式声明半径（例如按城市 tier 从 social 读），那是接口形状的改动（`ZoneSpec` 加字段），本批未做。
4. **`unit.SetJurisdiction` 不是硬依赖这件事如实写在类注里**：任务书假定"DefineMarketZone 需要辖区已设"，回代码核后**不成立**
   （handler 不读 unit 状态）；本批仍把落点放在所有 SetJurisdiction 之后（语义成对 + 任务书要求），但把真实依赖逐条写清，
   以免后人在别处照抄"必须先 SetJurisdiction"的错误前提。
5. **B2 的 BLOCKED 未变**：FX 窗口按区收窄、区级官方汇率进入成交，仍是 B2 账本 §4 偏离 1 的 BLOCKED 项，本批**不碰**。
6. **本批不改 `SmallWorld`/`CorridorWorld`/`WorldRegistry`/`EconomyData`/handler**：唯一进入结算面的变化是"拓扑来源"，
   其装配输入逐值相同（探针 §5 实测）。

---

## 6. 报告（交账模板）

### 6.1 改动文件（3 个；全部在允许写范围内）

| 文件 | 形态 | 作用 |
|---|---|---|
| `simos-app/src/main/java/io/mosire/simos/app/world/ThreePowersMarketZones.java` | **新增**（574 行） | 落 3 个持久区 + 5 族/12 具名自检 + 日志（§一.9） |
| `simos-app/src/main/java/io/mosire/simos/app/world/ThreePowersGovBootstrap.java` | 改 | `GovSpec.marketZoneId`（末尾追加）+ `apply` 里落区一步 + 类注命令序 |
| `simos-app/src/main/java/io/mosire/simos/app/world/ThreePowersWorld.java` | 改 | 三个 `GovSpec` 各传区 id（城市 id）+ 类注更正 |

★ 未碰：任何 `src/test/**`、任何 `pom.xml`、`docs/**`、其它 `.superpowers/**`、`SmallWorld.java`、`CorridorWorld.java`、
`WorldRegistry.java`、`EconomyData.java`、三条区命令的 handler、`MarketTopologyBook.java`、`MarketZoneReadout.java`。

**最终字节（md5；探针证据即对这份字节，§三.1「自建装置要自指」）**

```
34cca49e3cd4e8e4ec84a8f33a1c3b6c  ThreePowersMarketZones.java
665bb08a1fcb6911c26d17d7cc91c8e9  ThreePowersGovBootstrap.java
585923c02f472f9a3018f6bf213d85a7  ThreePowersWorld.java
```

### 6.2 编译

> ★ 本账本文件落在 `.superpowers/sdd/.gitignore`（内容 `*`）之下 ⇒ **未被 git 跟踪**；控制方随批提交时需 `git add -f .superpowers/sdd/2026-10-08-stage2-zone-seed/b3-impl-ledger.md`（B1/B2/A2 三份账本当初也是这么入库的）。

```
$ pgrep -af "surefirebooter|classworlds.launcher"     # ⇒ 无命中（§一.1）
$ ./mvnw -q -o -DskipTests compile -pl simos-app -am  # ⇒ [exit=0]（最终字节上重跑过两次，均 0）
```
- 未跑 `test` / `verify` / `package` / `test-compile`（任务书 + §一.5）。**门禁未绿**，编译是唯一实测门禁。
- 格式化：本批三个文件 **Spotless 干净**（`./mvnw -o spotless:check -pl simos-app` 的违规清单里没有它们）。
  ★ 但**同一次检查实测**：`simos-app` 在 HEAD 上已有 **9 个文件** Spotless 违规（`AppLogSource` / `Shell` /
  `gov/GovCurrencyLinks` / `gov/GovTerritory` / `time/MarketTopologyBook` / `time/MarketZoneReadout` /
  `tools/write/Economy{Define,Reassign,Merge}*Tool`），全部是 **B2 批次**提交的（B2 账本 §5.7 自述"Spotless/Checkstyle/SpotBugs 未跑"）。
  ⇒ 控制方跑 `clean verify` 会在 Spotless 阶段红在**本批之外的 9 个文件**上；本批**没有**替它们格式化（不在文件所有权内），
  只把 `spotless:apply` 误改的部分**逐个 revert 回 HEAD**（`git status` 最终只剩本批三个文件）。

### 6.3 探针自证（真实生产装配；四个 JVM 轮，全部 exit=0）

装置（`/tmp/b3-probe/`，**不进仓库、不进 `src/test`**）：

```
CP="$(ls -d $PWD/simos-*/target/classes | tr '\n' ':')$(cat /tmp/a2b-probe/cp-third.txt)"
javac -nowarn -cp "$CP" -d /tmp/b3-probe/out /tmp/b3-probe/B3Probe.java /tmp/b3-probe/B3ZoneFailProbe.java
java -cp "/tmp/b3-probe/out:$CP" io.mosire.simos.app.B3Probe three-powers        # ⇒ run-final.log
java -cp "/tmp/b3-probe/out:$CP" io.mosire.simos.app.B3Probe small-world         # ⇒ small-final.log
java -cp "/tmp/b3-probe/out:$CP" io.mosire.simos.app.world.B3ZoneFailProbe       # ⇒ fail-final.log
java -Dsimos.app.logLevel=DEBUG -cp "/tmp/b3-probe/out:$CP" io.mosire.simos.app.B3Probe three-powers   # ⇒ debug-final.log
```
- `B3Probe`：真 `Shell.start` + 真创世 `ShellMain.seedGenesisIfEmpty` + 真时间推进 `shell.advanceAndDrain`（**零 `core.register`**）。
- `B3ZoneFailProbe`：真 `Shell.start`（真 store）+ 真生成器 `WorldRegistry.require("three-powers").genesis(mapId)` +
  生产 `ThreePowersWorld::applyCommand`。

| 轮 | 判据 | 实测 |
|---|---|---|
| `B3Probe three-powers` | 3 区正确 / 逐 hex 37 格互斥 / 拓扑 authority / **30 tick 后仍在** | **59 ✓ / 0 ✗**：`marketZones=3`（head=1 ⇒ 创世只写一条 revision）；`c-tp-silver@3_0[9格,r=8]=silver/gov-unit-gov-tp-silver`、`c-tp-copper@0_-3[16格]=copper/…`、`c-tp-gold@-3_3[12格]=gold/…`；三区法定币两两不同；逐格并集 = 37 且每格恰一区、每区 hexes **逐 hex** = 对应行政区；`MarketZoneReadout.zones` 3 区**全部 `authority=persistent`**；**推进 30 tick 后区表 record 相等**、逐格归属不变、仍全部 `authority=persistent`（head 1→31）；且"设汇率前外汇面沉默"（GOV 级 `officialRates` 全空） |
| 同上（DEBUG 轮） | 拓扑权威日志 | 同 59 ✓；`event=MARKET_TOPOLOGY_FROM_PERSISTENT_ZONES origin=market-topology source=persistent zones=3 marketHexes=37 anchorWithoutMarket=0 numeraireDriftHexes=0` **×33**（创世后 + 30 个 tick） |
| `B3Probe small-world` | **旧世界不动** | **6 ✓ / 0 ✗**：创世后 `marketZones` **空**（0）、派生区非空且全部 `authority=derived`、推进 5 tick 后仍空且读数逐值不变 |
| `B3ZoneFailProbe` | **负向对照（fail-closed 具名抛）** | **20 ✓ / 0 ✗**，五类：**① 法定币 ≠ GOV 发行币**（铜区写 `gold`）⇒ `three-powers 创世命令被拒 economy.DefineMarketZone：…具名拒（currency-not-issuable）: gov=gov-unit-gov-tp-copper 法定币=gold issuable=[copper]`，**真 store 仍空库**；**② hexes ≠ 行政区**（少 1 格 / 多 1 格）⇒ `[zone-hexes-vs-region]`（点名具体格）；**③ 区表少一区**⇒ `[zone-count]`；**④ 一 GOV 多币**⇒ 派生期具名抛；坏路径之后一次好创世仍正常写入（head=1）。**⑤ 只换权威不改读数**：同一份世界清空区表 ⇒ 派生读数 vs 持久读数逐区逐值相同（zoneId/anchor/radiusHex/法定币/receiveWith/成员格） |

创世日志（§一.9；`run-final.log` 原文行）：

```
INFO economy.command event=MARKET_ZONE_DEFINED zone=c-tp-silver anchor=3_0 hexCount=9  legalTender=silver issuingGov=gov-unit-gov-tp-silver issuingGovUnit=gov-tp-silver radiusHex=8 zonesBefore=0 zonesAfter=1
INFO economy.command event=MARKET_ZONE_DEFINED zone=c-tp-copper anchor=0_-3 hexCount=16 legalTender=copper issuingGov=gov-unit-gov-tp-copper issuingGovUnit=gov-tp-copper radiusHex=8 zonesBefore=1 zonesAfter=2
INFO economy.command event=MARKET_ZONE_DEFINED zone=c-tp-gold   anchor=-3_3 hexCount=12 legalTender=gold   issuingGov=gov-unit-gov-tp-gold   issuingGovUnit=gov-tp-gold   radiusHex=8 zonesBefore=2 zonesAfter=3
INFO app.shell event=THREE_POWERS_GENESIS_MARKET_ZONE_PERSISTED  …（逐区一条：区 id / 锚格 / 格数 / 法定币 / 发行 GOV / 半径）
INFO app.shell event=THREE_POWERS_GENESIS_MARKET_ZONES_PERSISTED world=three-powers zones=3 hexes=37 authority=persistent requiredSelfChecks=5
DEBUG app.shell event=THREE_POWERS_GENESIS_MARKET_ZONE_SELF_CHECK_OK zones=3 tenders=3 hexes=37 requiredSelfChecks=5
（失败时：ERROR app.shell event=THREE_POWERS_GENESIS_MARKET_ZONE_SELF_CHECK_FAILED check=<id> message=…）
```
B1 的四条创世读数**未被改**：`THREE_POWERS_GENESIS_ZONES zones=3 zoneIds=[c-tp-copper, c-tp-gold, c-tp-silver]`、
`_GOVS govs=3`、`_CURRENCIES currencies=3 currencyIds=[silver, copper, gold]`、
`_ZONE_NUMERAIRE`（16/12/9 与逐区币）、`_EXCLUSIVITY_OK regions=3 hexesChecked=37 overlaps=0`。

### 6.4 会改变数值行为的清单

| 项 | 是否会改既有世界的数 | 依据 |
|---|---|---|
| `three-powers` 创世的**区表** | **是（本批的目的）**：`marketZones` 从空 → 3 条；该世界拓扑改走 `byPersistentZones` | 探针 0/1 段 |
| `three-powers` 的**市场区读数** | **否（逐值相同）** | 探针 §5 实测：zoneId/anchor/radiusHex/法定币/receiveWith/成员格 与派生基线逐值相同 |
| `three-powers` 的**结算输入**（拓扑对象） | **未实测整轮数值 diff**；结构上 `ofZones` 与 `of` 同构（`MarketTopology.java:466-522` vs `:295-316`，两者 `regional` 均为 true），且节点/成员/半径/币种逐值相同 | 如实边界：本批只证读数逐值 + 结构同源，**未重跑 B2 的"旧码 vs 新码"整轮对照** |
| `three-powers` 的**创世 revision 数 / checkpoint** | **否**：仍只有一条（head=1） | 探针 0 段；`CoreSimos.java:284-315` |
| `three-powers` 的落盘字节 | **是**：economy 快照多出 `marketZones` 键 + 3 条记录（30 tick 后仍在） | 探针 4 段 |
| `small-world` / `corridor` / `v17levant` | **否**：区表仍为空 ⇒ 逐字走派生路径 | 探针 small 轮实测（small-world）；corridor/v17levant **未实测**（本批未触碰其任何路径） |
| 三条区命令（`Define/Reassign/Merge`）与 `SetOfficialRate` | **否**：未改一行；只有创世新增了 3 次 `DefineMarketZone` 调用 | 改动文件清单 |
| 官方汇率面 | **否**：三区区级汇率空、GOV 级汇率空 ⇒ 外汇窗口仍沉默（任务书"明确不做"） | 探针 1 段 |
| 日志 | **是（新增输出，不改状态）**：每世界 INFO +4（逐区 3 + 汇总 1）+ handler 的 `MARKET_ZONE_DEFINED` ×3；DEBUG +1；失败时 ERROR +1 | §6.3 原文行 |

### 6.5 会让既有测试失效的清单（**未跑测试**，按源码核对）

| 测试 / 面 | 预期影响 | 依据（实测 grep） |
|---|---|---|
| 全仓 `src/test` 对 `THREE_POWERS` / `ThreePowersWorld` / `ThreePowersGovBootstrap` / `GovSpec` / `marketZones` | **零命中** ⇒ 无测试直接依赖本批改动的世界或记录 | `grep -rln` in `simos-app/src/test` + `simos-economy/src/test` = 空 |
| 引用 `WorldRegistry` 的 4 个测试（`GovToolsZ6Test:1511`、`GovZ6WorldFixture:128`、`GovGenesisZ6Test:435`、`Z7RemittanceE2ETest:138`） | **不受影响**：全部用 `WorldRegistry.SMALL_WORLD` | 实读行号 |
| 命令/工具计数类断言（`SimosToolsTest` 的 `hasSize(...)`、`McpPortTopologyTest`、`McpCoverageTest`、`McpServerTest`） | **不受影响**：本批**未新增任何命令类型、工具、catalog 条目**（复用 B2 的 `economy.DefineMarketZone`） | `SimulationState`/Shell 注册面零改动 |
| `EconomyData` 组件数/往返枚举类（`EconomyRoundTripTest` 等） | **不受影响**：本批未改 `EconomyData`/`EconomyChangeSet` | 改动文件清单 |
| 依赖 `ThreePowersGovBootstrap.GovSpec` 位置参数的调用者 | **仅 `ThreePowersWorld` 三处**（已同步）；组件追加在**末尾** ⇒ 漏给 = 编译错（不是静默错位） | `grep GovSpec` 全仓：只命中 `GovWorldBootstrap` 的**另一个**私有 `GovSpec` |
| Spotless（`clean verify` 的一环） | **会红**，但红在 **B2 批次提交的 9 个文件**上（本批三文件干净） | `./mvnw -o spotless:check -pl simos-app` 实测清单（§6.2） |
| Checkstyle / SpotBugs / Surefire | **未跑**（任务书禁止）⇒ 本批新代码未过静态检查与回归网 | — |

### 6.6 未完成 / 未验证 / BLOCKED

1. **未跑 `test`/`verify`/`package`/`test-compile`**（任务书 + §一.5）⇒ **门禁未绿**；Checkstyle/SpotBugs/Surefire 三点未验，
   §6.5 是源码核对 + 编译核，不是跑出来的结论。
2. **未设三区官方汇率**（明确不做，留给控制方验收用 GM 命令设，便于观察"设汇率前外汇面沉默"）；
   **FX 窗口按区收窄仍是 BLOCKED**（B2 账本 §4 偏离 1，需用户/控制方裁定作用域与多区不同价的裁决规则）。
3. **未做整轮数值 diff**：只实测"区读数逐值相同"（探针 §5）+ `ofZones`/`of` 结构同源，**未**重跑 B2 的"旧码 worktree vs 新码"
   整轮经济对照（那需要 60 tick × 两个 worktree 的装置，属 B2 探针，本批未复用）。
4. **未实测 `corridor` / `v17levant`**：本批未触碰它们的任何路径（区表仍空 ⇒ 派生），只实测了 `small-world`。
5. **未跑 360 tick 真档长跑**：探针到 30 tick（任务书要求）；跨区汇差可套（G3）属后续批次。
6. **探针边界的如实说明**：负向对照轮不经 `ShellMain.seedGenesisIfEmpty`（它把 `bootstrapGenesis` 的调用点留给探针自己做，
   以便观察"坏路径 ⇒ 库仍空 ⇒ 好创世仍可写入"）；正轮（`B3Probe`）走的是**真创世入口**。两者都用真 handler/真 codec/生产 `applyCommand`。
7. **未能 claim 任务板**：`team_task_get task-15` 返回 `agent "3aa65e91-…" is not a member of an active Agent Team`
   ⇒ 无法 claim/complete（按任务书继续，此处如实记）。
